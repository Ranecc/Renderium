// Renderium - 可扩展 Shader 节点系统
// SPIRVShaderModule - SPIR-V 着色器模块管理
//
// 核心功能：
//   1. 加载和管理 .spv 二进制文件
//   2. Vulkan Pipeline Cache 集成
//   3. 着色器变体管理（不同质量等级的 SPIR-V 版本）
//   4. Uniform 布局反射（自动从 SPIR-V 提取 Uniform 信息）
//
// 架构设计：
//   .spv 文件 ──→ SPIRVShaderModule ──→ Vulkan ShaderModule
//                   │
//                   ├── 二进制数据缓存
//                   ├── 变体管理 (high/medium/low)
//                   ├── Uniform 反射信息
//                   └── Pipeline Cache 集成

package com.renderium.shader.spirv;

import com.renderium.shader.comp.ShaderCompDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * SPIR-V 着色器模块管理
 * <p>
 * 封装 SPIR-V 字节码的加载、变体管理和元数据反射。
 * 每个 SPIRVShaderModule 对应一个着色器的完整生命周期，
 * 包括不同质量等级的变体和从二进制中提取的 Uniform 布局信息。
 *
 * <h2>功能概览：</h2>
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │            SPIRVShaderModule                 │
 * ├──────────────────────────────────────────────┤
 * │ spirvData:     byte[] (主 SPIR-V 二进制)      │
 * │ variants:      Map&lt;quality, byte[]&gt;          │
 * │ uniformLayout: List&lt;UniformEntry&gt;           │
 * │ entryPoint:    String                        │
 * │ stage:         SpirvStage                    │
 * │ cacheHandle:   long (Vulkan Pipeline Cache)   │
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 从 SpirvReference 加载
 * SPIRVShaderModule module = SPIRVShaderModule.load(spirvReference);
 *
 * // 获取指定质量等级的字节码
 * byte[] highQualitySpirv = module.getSpirvData("high");
 *
 * // 查询 Uniform 布局
 * for (SPIRVShaderModule.UniformEntry uniform : module.getUniformLayout()) {
 *     System.out.printf("uniform %s at binding=%d, offset=%d%n",
 *             uniform.name, uniform.binding, uniform.offset);
 * }
 *
 * // 获取原始字节码（默认质量）
 * byte[] defaultSpirv = module.getSpirvData();
 * </pre>
 *
 * @see ShaderCompDescriptor.SpirvReference
 * @since 7.0.0
 */
public final class SPIRVShaderModule {

    private static final Logger LOGGER = Logger.getLogger("Renderium|SPIRVModule");

    /** 默认质量等级 */
    public static final String DEFAULT_QUALITY = "medium";

    // ==================== 内部数据类 ====================

    /**
     * Uniform 变量条目（从 SPIR-V 反射提取）
     * <p>
     * 描述着色器中一个 Uniform 变量的布局信息。
     */
    public static final class UniformEntry {
        /** Uniform 名称 */
        private final String name;

        /** Binding 点（Vulkan descriptor set + binding） */
        private final int binding;

        /** 在 UBO 中的字节偏移 */
        private final int offset;

        /** 数据大小（字节） */
        private final int size;

        /** 数据类型 */
        private final String dataType;

        /** 所在 Descriptor Set 编号 */
        private final int setNumber;

        /**
         * 构造 Uniform 条目
         *
         * 【参数说明】
         * @param name      String - 变量名
         * @param binding   int    - Binding 点
         * @param offset    int    - 字节偏移
         * @param size      int    - 大小（字节）
         * @param dataType  String - 数据类型
         * @param setNumber int    - Descriptor Set 编号
         */
        public UniformEntry(String name, int binding, int offset,
                            int size, String dataType, int setNumber) {
            this.name = name;
            this.binding = binding;
            this.offset = offset;
            this.size = size;
            this.dataType = dataType;
            this.setNumber = setNumber;
        }

        /** @return String - Uniform 变量名 */
        public String getName() { return name; }
        /** @return int - Binding 点 */
        public int getBinding() { return binding; }
        /** @return int - 字节偏移 */
        public int getOffset() { return offset; }
        /** @return int - 数据大小（字节） */
        public int getSize() { return size; }
        /** @return String - 数据类型 */
        public String getDataType() { return dataType; }
        /** @return int - Descriptor Set 编号 */
        public int getSetNumber() { return setNumber; }

        @Override
        public String toString() {
            return String.format("Uniform{name=%s, binding=%d:%d, offset=%d, size=%d, type=%s}",
                    name, setNumber, binding, offset, size, dataType);
        }
    }

    /**
     * 推入常量条目（SPIR-V Specialization Constant）
     */
    public static final class SpecConstantEntry {
        /** 常量 ID */
        private final int constantId;

        /** 常量名称 */
        private final String name;

        /** 默认值 */
        private final Object defaultValue;

        /** 数据类型 */
        private final String dataType;

        /**
         * 构造推送常量条目
         */
        public SpecConstantEntry(int constantId, String name, Object defaultValue, String dataType) {
            this.constantId = constantId;
            this.name = name;
            this.defaultValue = defaultValue;
            this.dataType = dataType;
        }

        public int getConstantId() { return constantId; }
        public String getName() { return name; }
        public Object getDefaultValue() { return defaultValue; }
        public String getDataType() { return dataType; }

        @Override
        public String toString() {
            return String.format("SpecConst{id=%d, name=%s, default=%s, type=%s}",
                    constantId, name, defaultValue, dataType);
        }
    }

    // ==================== 核心字段 ====================

    /** 主 SPIR-V 二进制数据 */
    private final byte[] spirvData;

    /** 质量变体映射（qualityLevel -> spirvBytes） */
    private final Map<String, byte[]> variants;

    /** 入口点函数名 */
    private final String entryPoint;

    /** 着色器阶段 */
    private final ShaderCompDescriptor.SpirvStage stage;

    /** Uniform 布局反射结果 */
    private final List<UniformEntry> uniformLayout;

    /** 推送常量列表 */
    private final List<SpecConstantEntry> specConstants;

    /** 来源路径（用于调试和热重载） */
    private final String sourcePath;

    /** 是否已成功加载 */
    private final boolean valid;

    /** 数据哈希值（用于变化检测和缓存键） */
    private final long dataHash;

    /** Vulkan Pipeline Cache 句柄（由外部设置） */
    private volatile long pipelineCacheHandle = 0L;

    /** 最后加载时间戳 */
    private final long loadedAt;

    /** 模块大小（字节） */
    private final int dataSize;

    /**
     * 私有构造函数（通过 load() 工厂方法创建）
     */
    private SPIRVShaderModule(byte[] spirvData, Map<String, byte[]> variants,
                              String entryPoint, ShaderCompDescriptor.SpirvStage stage,
                              List<UniformEntry> uniformLayout, List<SpecConstantEntry> specConstants,
                              String sourcePath, boolean valid) {
        this.spirvData = spirvData != null ? spirvData : new byte[0];
        this.variants = variants != null ? Collections.unmodifiableMap(new HashMap<>(variants)) : Collections.emptyMap();
        this.entryPoint = entryPoint != null ? entryPoint : "main";
        this.stage = stage != null ? stage : ShaderCompDescriptor.SpirvStage.FRAGMENT;
        this.uniformLayout = uniformLayout != null ? Collections.unmodifiableList(new ArrayList<>(uniformLayout)) : Collections.emptyList();
        this.specConstants = specConstants != null ? Collections.unmodifiableList(new ArrayList<>(specConstants)) : Collections.emptyList();
        this.sourcePath = sourcePath;
        this.valid = valid;
        this.dataHash = computeHash(this.spirvData);
        this.loadedAt = System.currentTimeMillis();
        this.dataSize = this.spirvData.length;
    }

    // ==================== 工厂方法 ====================

    /**
     * 从 SpirvReference 加载 SPIR-V 模块
     * <p>
     * 根据 SpirvReference 中定义的路径或内嵌数据创建 SPIRVShaderModule。
     * 自动检测并加载所有可用的质量变体。
     *
     * <h3>加载流程：</h3>
     * <ol>
     *   <li>检查引用模式（内嵌 vs 外部文件）</li>
     *   <li>读取主 SPIR-V 二进制</li>
     *   <li>尝试加载各质量变体</li>
     *   <li>执行 SPIR-V 反射提取 Uniform 布局</li>
     *   <li>构建并返回模块实例</li>
     * </ol>
     *
     * 【方法参数】
     * @param ref SpirvReference - SPIR-V 引用对象
     *
     * @return SPIRVShaderModule - 加载完成的模块实例
     *
     * @throws IllegalArgumentException 如果引用无效或文件不存在
     */
    public static SPIRVShaderModule load(ShaderCompDescriptor.SpirvReference ref) {
        if (ref == null) {
            throw new IllegalArgumentException("SpirvReference 不能为 null");
        }

        byte[] mainSpirvData;
        Map<String, byte[]> variantMap = new HashMap<>();
        String sourcePath = null;

        if (ref.isEmbedded()) {
            // 内嵌模式：直接使用 spirvData
            mainSpirvData = ref.getSpirvData();
            LOGGER.fine(String.format("加载内嵌 SPIR-V 数据 (%d bytes)", mainSpirvData.length));
        } else {
            // 外部文件模式：从磁盘读取
            Path path = Paths.get(ref.getPath());
            sourcePath = ref.getPath();

            if (!Files.exists(path)) {
                LOGGER.warning(String.format("SPIR-V 文件不存在: %s，将创建空模块", ref.getPath()));
                mainSpirvData = new byte[0];
            } else {
                try {
                    mainSpirvData = Files.readAllBytes(path);
                    LOGGER.fine(String.format("从文件加载 SPIR-V: %s (%d bytes)",
                            ref.getPath(), mainSpirvData.length));
                } catch (Exception e) {
                    throw new IllegalArgumentException(String.format("无法读取 SPIR-V 文件: %s - %s",
                            ref.getPath(), e.getMessage()));
                }
            }

            // 加载质量变体
            for (Map.Entry<String, String> variant : ref.getVariants().entrySet()) {
                Path variantPath = Paths.get(variant.getValue());
                if (Files.exists(variantPath)) {
                    try {
                        byte[] variantData = Files.readAllBytes(variantPath);
                        variantMap.put(variant.getKey(), variantData);
                        LOGGER.fine(String.format("加载变体 %s: %s (%d bytes)",
                                variant.getKey(), variant.getValue(), variantData.length));
                    } catch (Exception e) {
                        LOGGER.warning(String.format("加载变体失败 [%s]: %s", variant.getKey(), e.getMessage()));
                    }
                }
            }
        }

        // SPIR-V 反射（提取 Uniform 布局）
        List<UniformEntry> uniforms = reflectUniformLayout(mainSpirvData);

        // 提取推送常量
        List<SpecConstantEntry> specConsts = reflectSpecConstants(mainSpirvData);

        boolean isValid = mainSpirvData.length > 0 && mainSpirvData.length % 4 == 0;

        return new SPIRVShaderModule(
                mainSpirvData, variantMap,
                ref.getEntryPoint(), ref.getStage(),
                uniforms, specConsts,
                sourcePath, isValid
        );
    }

    /**
     * 从字节数组直接创建模块（用于测试或运行时生成）
     *
     * @param spirvData  byte[]   - SPIR-V 字节码
     * @param entryPoint String   - 入口点名称
     * @param stage      SpirvStage - 着色器阶段
     * @return SPIRVShaderModule - 模块实例
     */
    public static SPIRVShaderModule fromBytes(byte[] spirvData, String entryPoint,
                                               ShaderCompDescriptor.SpirvStage stage) {
        List<UniformEntry> uniforms = reflectUniformLayout(spirvData);
        List<SpecConstantEntry> specConsts = reflectSpecConstants(spirvData);

        boolean isValid = spirvData != null && spirvData.length > 0 && spirvData.length % 4 == 0;

        return new SPIRVShaderModule(
                spirvData, Collections.emptyMap(),
                entryPoint, stage,
                uniforms, specConsts,
                null, isValid
        );
    }

    // ==================== 数据访问 API ====================

    /**
     * 获取主 SPIR-V 二进制数据
     *
     * @return byte[] - SPIR-V 字节码副本（防止外部修改内部状态）
     */
    public byte[] getSpirvData() {
        return spirvData.clone();
    }

    /**
     * 获取指定质量等级的 SPIR-V 数据
     * <p>
     * 如果请求的质量等级不存在，回退到默认质量等级的数据。
     *
     * @param qualityLevel String - 质量等级标识（如 "high", "medium", "low"）
     * @return byte[] - 对应质量的 SPIR-V 字节码
     */
    public byte[] getSpirvData(String qualityLevel) {
        if (qualityLevel == null || !variants.containsKey(qualityLevel)) {
            return getSpirvData();  // 回退到主数据
        }
        return variants.get(qualityLevel).clone();
    }

    /**
     * 获取所有可用的质量等级列表
     *
     * @return Set - 质量等级名称集合
     */
    public Set<String> getAvailableQualities() {
        Set<String> qualities = new TreeSet<>(variants.keySet());
        qualities.add(DEFAULT_QUALITY);  // 主数据作为默认质量
        return qualities;
    }

    /** @return String - 入口点函数名 */
    public String getEntryPoint() { return entryPoint; }

    /** @return SpirvStage - 着色器阶段 */
    public ShaderCompDescriptor.SpirvStage getStage() { return stage; }

    /** @return List - Uniform 布局反射结果 */
    public List<UniformEntry> getUniformLayout() { return uniformLayout; }

    /**
     * 获取 Uniform 变量列表（别名方法）
     *
     * @return List<UniformEntry> - 所有 Uniform 变量条目
     * @see #getUniformLayout()
     */
    public List<UniformEntry> getUniforms() { return uniformLayout; }

    /** @return List - 推送常量列表 */
    public List<SpecConstantEntry> getSpecConstants() { return specConstants; }

    /** @return String - 来源文件路径 */
    public String getSourcePath() { return sourcePath; }

    /** @return boolean - 模块是否有效（数据合法且非空） */
    public boolean isValid() { return valid; }

    /** @return long - 数据哈希值 */
    public long getDataHash() { return dataHash; }

    /** @return long - 加载时间戳 */
    public long getLoadedAt() { return loadedAt; }

    /** @return int - SPIR-V 数据大小（字节） */
    public int getDataSize() { return dataSize; }

    /** @return long - Vulkan Pipeline Cache 句柄 */
    public long getPipelineCacheHandle() { return pipelineCacheHandle; }

    /**
     * 设置 Pipeline Cache 句柄（由外部图形后端调用）
     *
     * @param handle long - Vulkan Pipeline Cache 对象句柄
     */
    public void setPipelineCacheHandle(long handle) {
        this.pipelineCacheHandle = handle;
    }

    // ==================== 查询 API ====================

    /**
     * 按名称查找 Uniform
     *
     * @param name String - Uniform 变量名
     * @return UniformEntry - 匹配的 Uniform 条目，不存在返回 null
     */
    public UniformEntry findUniformByName(String name) {
        if (name == null) return null;
        for (UniformEntry u : uniformLayout) {
            if (u.getName().equals(name)) return u;
        }
        return null;
    }

    /**
     * 按 Binding 查找 Uniform
     *
     * @param set     int - Descriptor Set 编号
     * @param binding int - Binding 点
     * @return UniformEntry - 匹配的 Uniform 条目
     */
    public UniformEntry findUniformByBinding(int set, int binding) {
        for (UniformEntry u : uniformLayout) {
            if (u.getSetNumber() == set && u.getBinding() == binding) return u;
        }
        return null;
    }

    /**
     * 计算总 UBO 大小
     * <p>
     * 基于 Uniform 布局计算所需的 UBO 总大小。
     *
     * @return int - UBO 大小（字节），考虑对齐要求
     */
    public int calculateTotalUboSize() {
        int maxSize = 0;
        for (UniformEntry u : uniformLayout) {
            int end = u.getOffset() + u.getSize();
            if (end > maxSize) maxSize = end;
        }
        // UBO 大小需要满足 Vulkan 的 minUBOAlignment 要求（通常为 16 bytes 对齐）
        return ((maxSize + 15) / 16) * 16;
    }

    /**
     * 检查是否存在指定质量等级的变体
     *
     * @param quality String - 质量等级
     * @return boolean - 是否存在
     */
    public boolean hasVariant(String quality) {
        return quality != null && variants.containsKey(quality);
    }

    // ==================== SPIR-V 反射（占位符实现）====================

    /**
     * 从 SPIR-V 二进制中反射 Uniform 布局
     * <p>
     * 解析 SPIR-V 字节码中的 OpDecorate 和 OpMemberDecorate 指令，
     * 提取 Uniform Buffer 的成员布局信息。
     *
     * <h3>SPIR-V 格式要点：</h3>
     * <ul>
     *   <li>Magic Number: 0x07230203</li>
     *   <li>每个指令是 32-bit 字（word）</li>
     *   <li>前 word 包含 opcode (16bit) + 操作数数量 (16bit)</li>
     *   <li>Decoration 通过 OpDecorate/OpMemberDecorate 绑定到变量</li>
     * </ul>
     *
     * @param spirvBytes byte[] - SPIR-V 字节码
     * @return List - 提取的 Uniform 列表
     */
    private static List<UniformEntry> reflectUniformLayout(byte[] spirvBytes) {
        List<UniformEntry> uniforms = new ArrayList<>();

        if (spirvBytes == null || spirvBytes.length < 20) {
            return uniforms;
        }

        // SPIR-V 反射解析（占位符模式）
        // 完整实现建议使用以下库之一：
        //   - SPIRV-Reflect (C/C++，可通过 FFM/JNI 调用)
        //   - spirv-cross (C/C++)
        //   - 自行实现的 Java SPIR-V 解析器
        //
        // 完整解析流程：
        // 1. 验证 Magic Number (0x07230203)
        // 2. 扫描 OpTypeStruct 定义 UBO 类型
        // 3. 找到 OpVariable (StorageClass::Uniform)
        // 4. 提取 OpMemberDecorate (Offset, Binding, DescriptorSet)
        // 5. 构建 UniformEntry 列表
        // 6. 处理 OpTypeArray / OpTypeRuntimeArray 动态数组

        LOGGER.fine("[SPIRV] parseReflection 使用占位符模式（返回空 Uniform 列表）");

        // 占位符：返回空列表（实际使用时需完成反射逻辑）
        return uniforms;
    }

    /**
     * 从 SPIR-V 二进制中反射 Spec Constants
     *
     * @param spirvBytes byte[] - SPIR-V 字节码
     * @return List - 推送常量列表
     */
    private static List<SpecConstantEntry> reflectSpecConstants(byte[] spirvBytes) {
        List<SpecConstantEntry> constants = new ArrayList<>();

        if (spirvBytes == null || spirvBytes.length < 20) {
            return constants;
        }

        // SpecConstant 反射解析（占位符模式）
        // 完整解析流程：
        // 1. 扫描 OpSpecConstant 和 OpSpecConstantFalse/True 指令
        // 2. 结合 OpName 提取常量名称
        // 3. 结合 OpDecorate 提取 ID 和默认值
        // 4. 构建 SpecConstantEntry 列表
        // 5. 支持 OpSpecConstantOp 复合表达式

        LOGGER.fine("[SPIRV] parseSpecConstants 使用占位符模式（返回空 SpecConstant 列表）");

        return constants;
    }

    /**
     * 计算数据的哈希值（用于缓存键和变化检测）
     *
     * @param data byte[] - 输入数据
     * @return long - FNV-1a 64位哈希值
     */
    private static long computeHash(byte[] data) {
        if (data == null || data.length == 0) return 0L;

        // FNV-1a 64-bit hash
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
                "SPIRVShaderModule{stage=%s, entry=%s, size=%dKB, uniforms=%d, " +
                "specConsts=%d, variants=%d, valid=%b, hash=0x%016x}",
                stage, entryPoint, dataSize / 1024, uniformLayout.size(),
                specConstants.size(), variants.size(), valid, dataHash
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SPIRVShaderModule)) return false;
        return dataHash == ((SPIRVShaderModule) o).dataHash;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(dataHash);
    }
}
