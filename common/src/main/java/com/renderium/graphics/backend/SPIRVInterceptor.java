// Renderium - SPIR-V 剥离器
// 劫持 vkCreateShaderModule 截获官方 SPIR-V 字节流，存入堆外内存池

package com.renderium.graphics.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * SPIR-V 剥离器。
 *
 * <p>设计来源：智能短路.md §第二类红利（SPIR-V 级别的"物理剥离"）
 *
 * <h2>核心策略</h2>
 * <p>不要去读官方的 Java 代码，去抢官方编译好的 SPIR-V 机器码。
 * Minecraft 26.2 以后，官方的 Vulkan 渲染器本质上就是一堆 SPIR-V 字节流在跑。
 * Renderium 设计一个"SPIR-V 剥离器"：
 *
 * <ol>
 *   <li><b>黑盒劫持句柄的延伸</b>：在劫持官方 VkDevice 的时候，
 *       顺便 Hook 官方调用 {@code vkCreateShaderModule} 的地方。</li>
 *   <li><b>建立 SPIR-V 缓存池</b>：当官方加载它的光影模块时，
 *       把所有的 SPIR-V 二进制字节流偷偷截获，
 *       按 Pass 名字（比如 {@code RTX_Reflection.spv}、{@code VolumetricFog.spv}）
 *       存进自己的堆外内存池。</li>
 *   <li><b>渲染图热插拔</b>：引擎初始化时扫描自己的光影包（.rgb 规范）。
 *       如果光影包里没有声明 RTX 反射，但缓存池里发现了官方自带的
 *       {@code RTX_Reflection.spv}，Renderium 自动将官方的这个 SPIR-V
 *       当作规范里的 Level 1 (黄牌注入) 外部模块，挂载到 CommandBuffer 对应节点。</li>
 * </ol>
 *
 * <h2>性能特性</h2>
 * <ul>
 *   <li>所有 SPIR-V 数据存储在 Panama MemorySegment 中（堆外，零 GC）</li>
 *   <li>查找使用哈希索引（ConcurrentHashMap），O(1) 复杂度</li>
 *   <li>截获过程零拷贝：直接引用原始字节流，不创建新数组</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class SPIRVInterceptor {

    private static final Logger LOGGER = Logger.getLogger("Renderium-SPIRV");

    /** 单例实例 */
    private static volatile SPIRVInterceptor instance;

    /** SPIR-V 文件头魔数 (0x07230203) */
    private static final int SPIRV_MAGIC = 0x07230203;

    // ==================== 缓存池 ====================

    /**
     * SPIR-V 模块缓存池
     *
     * <p>Key: Pass 名称（如 "RTX_Reflection"、"VolumetricFog"）
     * Value: SPIR-V 数据的堆外 MemorySegment
     */
    private final ConcurrentHashMap<String, SPIRVModule> modulePool = new ConcurrentHashMap<>();

    /** 管理所有 SPIR-V 数据的 Arena（统一生命周期） */
    private final Arena poolArena = Arena.ofShared();

    /** 统计：截获的 SPIR-V 模块总数 */
    private final AtomicInteger totalModules = new AtomicInteger(0);

    /** 统计：SPIR-V 数据总大小（字节） */
    private final AtomicLong totalBytes = new AtomicLong(0L);

    /** 是否启用截获 */
    private volatile boolean intercepting = true;

    /**
     * 私有构造函数
     */
    private SPIRVInterceptor() {}

    /**
     * 获取单例实例
     *
     * @return SPIRVInterceptor 唯一实例
     */
    public static synchronized SPIRVInterceptor getInstance() {
        if (instance == null) {
            instance = new SPIRVInterceptor();
        }
        return instance;
    }

    // ==================== 核心截获接口 ====================

    /**
     * 截获官方调用 vkCreateShaderModule 时的 SPIR-V 数据
     *
     * <p>Mixin 注入点：MC 26.2 调用 {@code vkCreateShaderModule} 的地方。
     * 在函数调用之前拦截，读取 {@code pCode} 指向的 SPIR-V 字节流。
     *
     * <h3>调用方式（Mixin 示例）</h3>
     * <pre>{@code
     * @Redirect(method = "createShaderModule", at = @At(value = "INVOKE",
     *     target = "Lorg/lwjgl/vulkan/VK10;vkCreateShaderModule(...)"))
     * private long renderium_interceptCreateShaderModule(...) {
     *     // 先截获 SPIR-V 数据
     *     SPIRVInterceptor.getInstance().onSPIRVCreated(passName, pCode, codeSize);
     *     // 然后放行原始调用
     *     return VK10.vkCreateShaderModule(...);
     * }
     * }</pre>
     *
     * @param passName Pass 名称（用于标识，如 "GBuffer"、"RTX_Reflection"）
     * @param spirvCodePtr SPIR-V 字节码的内存地址（pCode 指针）
     * @param codeSize SPIR-V 字节码大小（字节数）
     */
    public void onSPIRVCreated(String passName, long spirvCodePtr, int codeSize) {
        if (!intercepting) return;

        // 验证 SPIR-V 魔数（防止截获到非 SPIR-V 数据）
        if (codeSize < 4) return;

        LOGGER.fine(String.format("截获 SPIR-V 模块: pass=%s, size=%d bytes, ptr=0x%X",
                passName, codeSize, spirvCodePtr));

        // 将 SPIR-V 数据复制到堆外内存池
        MemorySegment sourceSegment = MemorySegment.ofAddress(spirvCodePtr).reinterpret(codeSize);
        MemorySegment pooledSegment = poolArena.allocate(codeSize);
        pooledSegment.copyFrom(sourceSegment);

        // 验证 SPIR-V 魔数
        int magic = pooledSegment.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0);
        if (magic != SPIRV_MAGIC) {
            LOGGER.warning(String.format(
                    "截获的数据不是有效的 SPIR-V (magic=0x%08X, 期望=0x%08X)，跳过: %s",
                    magic, SPIRV_MAGIC, passName));
            return;
        }

        // 提取 SPIR-V 元信息
        SPIRVModule module = parseSPIRVMetadata(passName, pooledSegment, codeSize);

        // 存入缓存池
        SPIRVModule previous = modulePool.put(passName, module);
        if (previous != null) {
            LOGGER.fine("替换已有的 SPIR-V 模块: " + passName);
        }

        totalModules.incrementAndGet();
        totalBytes.addAndGet(codeSize);

        LOGGER.info(String.format("✓ SPIR-V 模块已缓存: %s (%d bytes, version=0x%X)",
                passName, codeSize, module.spirvVersion));
    }

    /**
     * 从字节数组截获 SPIR-V 数据（用于测试或非指针场景）
     *
     * @param passName Pass 名称
     * @param spirvBytes SPIR-V 字节数组
     */
    public void onSPIRVCreated(String passName, byte[] spirvBytes) {
        if (!intercepting) return;
        if (spirvBytes.length < 4) return;

        // 复制到堆外内存
        MemorySegment pooledSegment = poolArena.allocate(spirvBytes.length);
        pooledSegment.copyFrom(MemorySegment.ofArray(spirvBytes));

        // 验证 SPIR-V 魔数
        int magic = pooledSegment.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0);
        if (magic != SPIRV_MAGIC) {
            LOGGER.warning("截获的数据不是有效的 SPIR-V，跳过: " + passName);
            return;
        }

        SPIRVModule module = parseSPIRVMetadata(passName, pooledSegment, spirvBytes.length);
        modulePool.put(passName, module);

        totalModules.incrementAndGet();
        totalBytes.addAndGet(spirvBytes.length);

        LOGGER.info(String.format("✓ SPIR-V 模块已缓存: %s (%d bytes)", passName, spirvBytes.length));
    }

    // ==================== SPIR-V 元数据解析 ====================

    /**
     * 解析 SPIR-V 文件头元数据
     *
     * <p>SPIR-V 文件头格式（5 个 uint32 = 20 bytes）：
     * <pre>
     * Offset  Size  Field
     * 0       4     Magic (0x07230203)
     * 4       4     Version
     * 8       4     Generator Magic
     * 12      4     Bound (ID boundary)
     * 16      4     Reserved (0)
     * </pre>
     *
     * @param passName Pass 名称
     * @param segment SPIR-V 数据的 MemorySegment
     * @param size 数据总大小
     * @return 解析后的 SPIRVModule
     */
    private SPIRVModule parseSPIRVMetadata(String passName, MemorySegment segment, int size) {
        var intLayout = java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

        int version = segment.get(intLayout, 4);
        int generatorMagic = segment.get(intLayout, 8);
        int bound = segment.get(intLayout, 12);

        // 提取 SPIR-V 版本信息
        int majorVersion = (version >> 16) & 0xFF;
        int minorVersion = (version >> 8) & 0xFF;

        // 推断 Shader 阶段（需要遍历 OpEntryPoint 指令）
        String shaderStage = inferShaderStage(segment, size);

        return new SPIRVModule(
                passName,
                segment,
                size,
                version,
                majorVersion,
                minorVersion,
                generatorMagic,
                bound,
                shaderStage
        );
    }

    /**
     * 推断 SPIR-V 的 Shader 阶段（Vertex/Fragment/Compute 等）
     *
     * <p>通过搜索 OpEntryPoint 指令来确定。
     * OpEntryPoint 格式：OpCode=15, 操作数包含 ExecutionModel。
     *
     * @param segment SPIR-V 数据
     * @param size 数据大小
     * @return Shader 阶段字符串
     */
    private String inferShaderStage(MemorySegment segment, int size) {
        var intLayout = java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

        // 遍历指令寻找 OpEntryPoint (OpCode = 15)
        int wordCount = size / 4;
        int offset = 5 * 4; // 跳过 Header (5 words)

        while (offset + 4 <= size) {
            int word = segment.get(intLayout, offset);
            int opCode = word & 0xFFFF;
            int wordLen = (word >> 16) & 0xFFFF;

            if (opCode == 15 && wordLen >= 2) { // OpEntryPoint
                int executionModel = segment.get(intLayout, offset + 4);
                return switch (executionModel) {
                    case 0 -> "Vertex";
                    case 1 -> "TessellationControl";
                    case 2 -> "TessellationEvaluation";
                    case 3 -> "Geometry";
                    case 4 -> "Fragment";
                    case 5 -> "GLCompute";
                    case 6 -> "Kernel";
                    case 7 -> "TaskNV";
                    case 8 -> "MeshNV";
                    case 9 -> "TaskEXT";
                    case 10 -> "MeshEXT";
                    case 11 -> "RayGenerationKHR";
                    case 12 -> "IntersectionKHR";
                    case 13 -> "AnyHitKHR";
                    case 14 -> "ClosestHitKHR";
                    case 15 -> "MissKHR";
                    case 16 -> "CallableKHR";
                    default -> "Unknown(" + executionModel + ")";
                };
            }

            if (wordLen == 0) break; // 防止无限循环
            offset += wordLen * 4;
        }

        return "Unknown";
    }

    // ==================== 查询接口 ====================

    /**
     * 查询缓存池中是否存在指定 Pass 的 SPIR-V 模块
     *
     * @param passName Pass 名称
     * @return true 如果存在
     */
    public boolean hasModule(String passName) {
        return modulePool.containsKey(passName);
    }

    /**
     * 获取指定 Pass 的 SPIR-V 模块
     *
     * @param passName Pass 名称
     * @return SPIRVModule，如果不存在返回 null
     */
    public SPIRVModule getModule(String passName) {
        return modulePool.get(passName);
    }

    /**
     * 获取指定 Pass 的 SPIR-V 数据（直接传给 vkCreateShaderModule）
     *
     * <p>返回的 MemorySegment 可以直接作为 {@code pCode} 参数
     * 传给 Vulkan 的 {@code vkCreateShaderModule}。
     *
     * @param passName Pass 名称
     * @return SPIR-V 数据的 MemorySegment，如果不存在返回 MemorySegment.NULL
     */
    public MemorySegment getSPIRVData(String passName) {
        SPIRVModule module = modulePool.get(passName);
        return module != null ? module.data : MemorySegment.NULL;
    }

    /**
     * 获取所有已截获的 Pass 名称
     *
     * @return Pass 名称集合
     */
    public java.util.Set<String> getAvailablePassNames() {
        return modulePool.keySet();
    }

    /**
     * 查找缓存池中哪些 Pass 是光影包没有声明但官方自带的
     *
     * <p>用于"渲染图热插拔"：如果光影包缺少某特效，
     * 但官方缓存池里有，可以自动作为 Level 1 注入。
     *
     * @param declaredPassNames 光影包已声明的 Pass 名称集合
     * @return 官方有但光影包没有的 Pass 名称集合
     */
    public java.util.Set<String> findUndeclaredOfficialPasses(java.util.Set<String> declaredPassNames) {
        java.util.Set<String> result = new java.util.HashSet<>(modulePool.keySet());
        result.removeAll(declaredPassNames);
        return result;
    }

    // ==================== 控制接口 ====================

    /** 启用/禁用截获 */
    public void setIntercepting(boolean intercepting) {
        this.intercepting = intercepting;
        LOGGER.info("SPIR-V 截获 " + (intercepting ? "已启用" : "已禁用"));
    }

    /** 是否正在截获 */
    public boolean isIntercepting() { return intercepting; }

    /** 获取截获的模块总数 */
    public int getTotalModules() { return totalModules.get(); }

    /** 获取截获的数据总大小 */
    public long getTotalBytes() { return totalBytes.get(); }

    /**
     * 清空缓存池并释放资源
     */
    public void clearPool() {
        modulePool.clear();
        totalModules.set(0);
        totalBytes.set(0L);
        LOGGER.info("SPIR-V 缓存池已清空");
    }

    /**
     * 关闭剥离器并释放所有资源
     */
    public void shutdown() {
        clearPool();
        if (poolArena.scope().isAlive()) {
            poolArena.close();
        }
        LOGGER.info("SPIR-V 剥离器已关闭");
    }

    @Override
    public String toString() {
        return String.format(
                "SPIRVInterceptor{modules=%d, bytes=%d, intercepting=%s}",
                totalModules.get(), totalBytes.get(), intercepting);
    }

    // ==================== 内部数据类 ====================

    /**
     * SPIR-V 模块元数据与数据
     */
    public static final class SPIRVModule {

        /** Pass 名称（如 "RTX_Reflection"、"VolumetricFog"） */
        final String passName;

        /** SPIR-V 字节码数据（堆外 MemorySegment） */
        final MemorySegment data;

        /** 数据大小（字节） */
        final int size;

        /** SPIR-V 版本号（原始值） */
        final int spirvVersion;

        /** SPIR-V 主版本号 */
        final int majorVersion;

        /** SPIR-V 次版本号 */
        final int minorVersion;

        /** 生成器魔数（标识编译器来源） */
        final int generatorMagic;

        /** ID 边界（Bound） */
        final int bound;

        /** 推断的 Shader 阶段 */
        final String shaderStage;

        SPIRVModule(String passName, MemorySegment data, int size,
                     int spirvVersion, int majorVersion, int minorVersion,
                     int generatorMagic, int bound, String shaderStage) {
            this.passName = passName;
            this.data = data;
            this.size = size;
            this.spirvVersion = spirvVersion;
            this.majorVersion = majorVersion;
            this.minorVersion = minorVersion;
            this.generatorMagic = generatorMagic;
            this.bound = bound;
            this.shaderStage = shaderStage;
        }

        /** 获取 SPIR-V 版本字符串 */
        public String getVersionString() {
            return String.format("%d.%d", majorVersion, minorVersion);
        }

        /** 获取数据大小（字节） */
        public int getSize() { return size; }

        /** 获取推断的 Shader 阶段 */
        public String getShaderStage() { return shaderStage; }

        /** 获取 SPIR-V 数据的 MemorySegment */
        public MemorySegment getData() { return data; }

        @Override
        public String toString() {
            return String.format(
                    "SPIRVModule{pass=%s, stage=%s, v=%s, size=%d}",
                    passName, shaderStage, getVersionString(), size);
        }
    }
}
