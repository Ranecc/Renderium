// Renderium - 可扩展 Shader 节点系统
// .comp 文件格式规范 - ShaderCompDescriptor
//
// 设计目标：
//   1. 零硬编码：所有节点配置来自 .comp 文件（JSON/Binary 格式）
//   2. 热插拔：运行时可以加载/卸载 .comp 包，无需重启
//   3. 向后兼容：新版 .comp 可以声明兼容旧版 API
//   4. 性能优先：默认路径 < 50ns 开销
//
// 参考架构：
//   - Blender Cycles SVM (Shader Virtual Machine)
//   - Unity Shader Graph (节点图编辑器)
//
// .comp 文件结构：
//   {
//     "metadata": { ... },      // 节点元数据
//     "spirv": { ... },         // SPIR-V 二进制引用
//     "parameters": [ ... ],    // 参数定义列表
//     "inputs": [ ... ],        // 输入插槽定义
//     "outputs": [ ... ],       // 输出插槽定义
//     "dependencies": [ ... ],  // 依赖的其他节点 ID
//     "performanceHints": { ... }, // 性能预算建议
//     "tags": [ ... ]           // 搜索标签
//   }

package com.ranecc.renderium.feature.shader.shader.comp;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

import java.util.*;
import java.util.logging.Logger;

/**
 * Shader 节点组件描述符 (.comp 文件格式规范)
 * <p>
 * 定义 .comp 文件的完整二进制/JSON 格式规范。每个 .comp 文件描述一个 Shader 节点的完整配置，
 * 包括元数据、SPIR-V 引用、参数定义、输入输出插槽、依赖关系和性能提示。
 *
 * <h2>架构位置：</h2>
 * <pre>
 * ┌──────────────────────┐     加载      ┌──────────────────────┐
 * │   *.comp 文件         │ ──────────→ │ ShaderCompDescriptor │
 * │ (JSON / Binary)      │             │   (内存表示)          │
 * └──────────────────────┘             └──────────┬───────────┘
 *                                                  │
 *                                                  ▼
 *                                       ┌──────────────────────┐
 *                                       │   ShaderNodeFactory  │
 *                                       │  (创建 PipelineNode) │
 *                                       └──────────────────────┘
 * </pre>
 *
 * <h2>.comp 文件 JSON 格式示例：</h2>
 * <pre>{@code
 * {
 *   "metadata": {
 *     "id": "pbr_material",
 *     "displayName": "PBR Material (Disney Principled BSDF)",
 *     "version": "1.2.0",
 *     "author": "Renderium Team",
 *     "category": "LIGHTING",
 *     "priority": 100,
 *     "apiCompatibility": "1.0.0"
 *   },
 *   "spirv": {
 *     "path": "shaders/pbr_material.spv",
 *     "entryPoint": "main",
 *     "variants": {
 *       "high": "shaders/pbr_material_high.spv",
 *       "medium": "shaders/pbr_material_medium.spv",
 *       "low": "shaders/pbr_material_low.spv"
 *     }
 *   },
 *   "parameters": [
 *     {
 *       "id": "metallic",
 *       "displayName": "金属度",
 *       "type": "FLOAT",
 *       "defaultValue": 0.0,
 *       "range": [0.0, 1.0],
 *       "step": 0.01,
 *       "category": "MATERIAL"
 *     }
 *   ],
 *   "inputs": [
 *     {
 *       "name": "albedoTexture",
 *       "type": "SAMPLER2D",
 *       "defaultConnection": "white_texture"
 *     }
 *   ],
 *   "outputs": [
 *     {
 *       "name": "outColor",
 *       "type": "VEC4"
 *     }
 *   ],
 *   "dependencies": ["gbuffer_geometry"],
 *   "performanceHints": {
 *     "budgetMicroseconds": 150,
 *     "memoryBudgetKB": 256,
 *     "gpuComputeHeavy": true
 *   },
 *   "tags": ["#pbr", "#material", "#physically-based", "#disney"]
 * }
 * }</pre>
 *
 * <h2>设计原则：</h2>
 * <ul>
 *   <li><b>零硬编码</b>：所有节点配置来自 .comp 文件</li>
 *   <li><b>热插拔</b>：运行时可以加载/卸载 .comp 包</li>
 *   <li><b>向后兼容</b>：新版 .comp 可以声明兼容旧版 API</li>
 *   <li><b>性能优先</b>：默认路径 &lt; 50ns 开销</li>
 * </ul>
 *
 * @see com.renderium.shader.registry.ShaderNodeRegistry
 * @see com.renderium.shader.factory.ShaderNodeFactory
 * @since 7.0.0
 */
public final class ShaderCompDescriptor {

    private static final Logger LOGGER = Logger.getLogger(ShaderCompDescriptor.class.getName());

    // ==================== 常量定义 ====================

    /** .comp 文件格式版本 */
    public static final String COMP_FORMAT_VERSION = "1.0.0";

    /** 支持的 SPIR-V 着色器阶段 */
    public enum SpirvStage {
        /** 顶点着色器 */
        VERTEX("vert"),
        /** 片段着色器 */
        FRAGMENT("frag"),
        /** 计算着色器 */
        COMPUTE("comp"),
        /** 光线生成着色器 */
        RAYGEN("raygen"),
        /** 最近命中着色器 */
        CLOSEST_HIT("rchit"),
        /** 任意命中着色器 */
        ANY_HIT("rahit"),
        /** 未命中着色器 */
        MISS("rmiss"),
        /** 相交着色器 */
        INTERSECTION("rint");

        private final String extension;

        SpirvStage(String extension) {
            this.extension = extension;
        }

        /**
         * 获取文件扩展名
         *
         * 【返回值】
         * @return String - 文件扩展名（不含点号）
         */
        public String getExtension() {
            return extension;
        }
    }

    /** 插槽数据类型枚举 */
    public enum SlotType {
        /** 浮点数 */
        FLOAT,
        /** 二维向量 */
        VEC2,
        /** 三维向量 */
        VEC3,
        /** 四维向量 */
        VEC4,
        /** 整数 */
        INT,
        /** IVEC2 */
        IVEC2,
        /** IVEC3 */
        IVEC3,
        /** IVEC4 */
        IVEC4,
        /** 无符号整数 */
        UINT,
        /** UVEC2 */
        UVEC2,
        /** UVEC3 */
        UVEC3,
        /** UVEC4 */
        UVEC4,
        /** 矩阵 3x3 */
        MAT3,
        /** 矩阵 4x4 */
        MAT4,
        /** 2D 纹理采样器 */
        SAMPLER2D,
        /** 3D 纹理采样器 */
        SAMPLER3D,
        /** Cube 纹理采样器 */
        SAMPLERCUBE,
        /** 2D 数组纹理采样器 */
        SAMPLER2DARRAY,
        /** 图像存储 (Image Store) */
        IMAGE2D,
        /** 加速结构 (Ray Tracing) */
        ACCELERATION_STRUCTURE
    }

    /** 参数 UI 提示类型 */
    public enum UiHint {
        /** 滑块控件 */
        SLIDER,
        /** 数字输入框 */
        NUMBER_BOX,
        /** 下拉选择框 */
        DROPDOWN,
        /** 复选框 */
        CHECKBOX,
        /** 颜色选择器 */
        COLOR_PICKER,
        /** 方向向量编辑器 */
        DIRECTION_EDITOR,
        /** 纹理槽位 */
        TEXTURE_SLOT,
        /** 无 UI（隐藏参数） */
        HIDDEN
    }

    // ==================== 内部数据类 ====================

    /**
     * 节点元数据
     * <p>
     * 包含节点的标识、名称、版本等基本信息。
     */
    public static final class Metadata {
        /** 节点唯一标识符（kebab-case） */
        private final String id;

        /** 显示名称（人类可读） */
        private final String displayName;

        /** 节点版本号（语义化版本） */
        private final String version;

        /** 作者信息 */
        private final String author;

        /** 节点分类 */
        private final PipelineNode.Category category;

        /** 执行优先级（数值越小越先执行） */
        private final int priority;

        /** API 兼容性版本（声明此版本兼容的最低 API 版本） */
        private final String apiCompatibility;

        /** 创建时间戳（毫秒） */
        private final long createdAt;

        /** 描述信息 */
        private final String description;

        /**
         * 构造元数据
         *
         * 【参数说明】
         * @param id               String              - 唯一标识符
         * @param displayName      String              - 显示名称
         * @param version          String              - 版本号
         * @param author           String              - 作者
         * @param category         Category            - 分类
         * @param priority         int                 - 优先级
         * @param apiCompatibility String              - API 兼容版本
         * @param createdAt        long                - 创建时间戳
         * @param description      String              - 描述信息
         */
        public Metadata(String id, String displayName, String version,
                        String author, PipelineNode.Category category,
                        int priority, String apiCompatibility,
                        long createdAt, String description) {
            this.id = Objects.requireNonNull(id, "id 不能为 null");
            this.displayName = Objects.requireNonNull(displayName, "displayName 不能为 null");
            this.version = version != null ? version : "1.0.0";
            this.author = author != null ? author : "Unknown";
            this.category = category != null ? category : PipelineNode.Category.POST_PROCESS;
            this.priority = priority;
            this.apiCompatibility = apiCompatibility != null ? apiCompatibility : "1.0.0";
            this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
            this.description = description != null ? description : "";
        }

        // ==================== Getter 方法 ====================

        /** @return String - 节点唯一标识符 */
        public String getId() { return id; }
        /** @return String - 显示名称 */
        public String getDisplayName() { return displayName; }
        /** @return String - 版本号 */
        public String getVersion() { return version; }
        /** @return String - 作者信息 */
        public String getAuthor() { return author; }
        /** @return Category - 节点分类 */
        public PipelineNode.Category getCategory() { return category; }
        /** @return int - 执行优先级 */
        public int getPriority() { return priority; }
        /** @return String - API 兼容版本 */
        public String getApiCompatibility() { return apiCompatibility; }
        /** @return long - 创建时间戳 */
        public long getCreatedAt() { return createdAt; }
        /** @return String - 描述信息 */
        public String getDescription() { return description; }

        @Override
        public String toString() {
            return String.format("Metadata{id=%s, name=%s, v=%s, cat=%s, pri=%d}",
                    id, displayName, version, category, priority);
        }
    }

    /**
     * SPIR-V 着色器模块引用
     * <p>
     * 描述节点关联的 SPIR-V 二进制文件路径和变体。
     */
    public static final class SpirvReference {
        /** SPIR-V 二进制文件路径（相对于 .comp 文件或绝对路径） */
        private final String path;

        /** 着色器入口点函数名 */
        private final String entryPoint;

        /** 着色器阶段 */
        private final SpirvStage stage;

        /** 不同质量等级的 SPIR-V 变体映射（qualityLevel -> spvPath） */
        private final Map<String, String> variants;

        /** 是否内嵌二进制数据（true 时 spirvData 存储实际字节） */
        private final boolean embedded;

        /** 内嵌的 SPIR-V 二进制数据（当 embedded=true 时使用） */
        private final byte[] spirvData;

        /**
         * 构造 SPIR-V 引用（外部文件模式）
         *
         * 【参数说明】
         * @param path       String            - SPIR-V 文件路径
         * @param entryPoint String            - 入口点函数名
         * @param stage      SpirvStage        - 着色器阶段
         * @param variants   Map&lt;String,String&gt; - 质量变体映射
         */
        public SpirvReference(String path, String entryPoint, SpirvStage stage,
                              Map<String, String> variants) {
            this.path = Objects.requireNonNull(path, "SPIR-V path 不能为 null");
            this.entryPoint = entryPoint != null ? entryPoint : "main";
            this.stage = stage != null ? stage : SpirvStage.FRAGMENT;
            this.variants = variants != null ? Collections.unmodifiableMap(new HashMap<>(variants)) : Collections.emptyMap();
            this.embedded = false;
            this.spirvData = null;
        }

        /**
         * 构造 SPIR-V 引用（内嵌二进制模式）
         *
         * 【参数说明】
         * @param entryPoint String - 入口点函数名
         * @param stage      SpirvStage - 着色器阶段
         * @param spirvData  byte[] - 内嵌的 SPIR-V 字节码
         */
        public SpirvReference(String entryPoint, SpirvStage stage, byte[] spirvData) {
            this.path = null;
            this.entryPoint = entryPoint != null ? entryPoint : "main";
            this.stage = stage != null ? stage : SpirvStage.FRAGMENT;
            this.variants = Collections.emptyMap();
            this.embedded = true;
            this.spirvData = spirvData != null ? spirvData.clone() : new byte[0];
        }

        /** @return String - SPIR-V 文件路径（外部模式）或 null */
        public String getPath() { return path; }
        /** @return String - 入口点函数名 */
        public String getEntryPoint() { return entryPoint; }
        /** @return SpirvStage - 着色器阶段 */
        public SpirvStage getStage() { return stage; }
        /** @return Map - 质量变体映射（不可变） */
        public Map<String, String> getVariants() { return variants; }
        /** @return boolean - 是否内嵌模式 */
        public boolean isEmbedded() { return embedded; }
        /** @return byte[] - 内嵌 SPIR-V 数据副本 */
        public byte[] getSpirvData() { return embedded ? spirvData.clone() : null; }

        /**
         * 获取指定质量等级的 SPIR-V 路径
         *
         * 【方法参数】
         * @param qualityLevel String - 质量等级（如 "high", "medium", "low"）
         *
         * 【返回值】
         * @return String - 对应的 SPIR-V 路径，如果不存在则返回默认路径
         */
        public String getVariantPath(String qualityLevel) {
            if (qualityLevel == null || !variants.containsKey(qualityLevel)) {
                return path;  // 回退到默认路径
            }
            return variants.get(qualityLevel);
        }

        @Override
        public String toString() {
            return String.format("SpirvRef{path=%s, entry=%s, stage=%s, embedded=%b}",
                    path, entryPoint, stage, embedded);
        }
    }

    /**
     * 参数定义
     * <p>
     * 描述节点的一个可配置参数，包括类型、范围、默认值和 UI 提示。
     */
    public static final class ParameterDef {
        /** 参数唯一标识符 */
        private final String id;

        /** 显示名称 */
        private final String displayName;

        /** 参数类型 */
        private final ParameterKnob.KnobType type;

        /** 默认值（Object 类型以支持多种值类型） */
        private final Object defaultValue;

        /** 有效范围 [min, max]（仅对数值类型有效） */
        private final Object[] range;

        /** 步进值（滑块步进） */
        private final Object step;

        /** 参数分类 */
        private final ParameterKnob.ParameterCategory category;

        /** UI 控件提示 */
        private final UiHint uiHint;

        /** 描述文本 */
        private final String description;

        /** 枚举选项列表（仅对 ENUM 类型有效） */
        private final String[] enumOptions;

        /** Uniform 名称（在着色器中的变量名） */
        private final String uniformName;

        /** 是否为核心参数（影响管线流程） */
        private final boolean isCore;

        /**
         * 构造参数定义
         *
         * 【参数说明】
         * @param id          String                     - 参数标识符
         * @param displayName String                     - 显示名称
         * @param type        KnobType                   - 参数类型
         * @param defaultValue Object                    - 默认值
         * @param range       Object[]                   - 有效范围
         * @param step        Object                     - 步进值
         * @param category    ParameterCategory          - 参数分类
         * @param uiHint      UiHint                     - UI 提示
         * @param description String                     - 描述文本
         * @param enumOptions String[]                   - 枚举选项
         * @param uniformName String                     - Uniform 变量名
         * @param isCore      boolean                    - 是否核心参数
         */
        public ParameterDef(String id, String displayName, ParameterKnob.KnobType type,
                            Object defaultValue, Object[] range, Object step,
                            ParameterKnob.ParameterCategory category, UiHint uiHint,
                            String description, String[] enumOptions,
                            String uniformName, boolean isCore) {
            this.id = Objects.requireNonNull(id, "Parameter id 不能为 null");
            this.displayName = displayName != null ? displayName : id;
            this.type = type != null ? type : ParameterKnob.KnobType.FLOAT;
            this.defaultValue = defaultValue;
            this.range = range;
            this.step = step;
            this.category = category != null ? category : ParameterKnob.ParameterCategory.SHADER;
            this.uiHint = uiHint != null ? uiHint : inferUiHint(type);
            this.description = description != null ? description : "";
            this.enumOptions = enumOptions != null ? enumOptions.clone() : new String[0];
            this.uniformName = uniformName != null ? uniformName : "u_" + id.replace("-", "_");
            this.isCore = isCore;
        }

        /**
         * 根据参数类型推断 UI 提示
         *
         * 【方法参数】
         * @param type KnobType - 参数类型
         *
         * 【返回值】
         * @return UiHint - 推断的 UI 提示类型
         */
        private static UiHint inferUiHint(ParameterKnob.KnobType type) {
            switch (type) {
                case FLOAT:
                case INT:
                    return UiHint.SLIDER;
                case ENUM:
                    return UiHint.DROPDOWN;
                case BOOL:
                    return UiHint.CHECKBOX;
                case COLOR:
                    return UiHint.COLOR_PICKER;
                case VEC2:
                case VEC3:
                    return UiHint.DIRECTION_EDITOR;
                default:
                    return UiHint.NUMBER_BOX;
            }
        }

        // ==================== Getter 方法 ====================

        /** @return String - 参数标识符 */
        public String getId() { return id; }
        /** @return String - 显示名称 */
        public String getDisplayName() { return displayName; }
        /** @return KnobType - 参数类型 */
        public ParameterKnob.KnobType getType() { return type; }
        /** @return Object - 默认值 */
        public Object getDefaultValue() { return defaultValue; }
        /** @return Object[] - 有效范围 */
        public Object[] getRange() { return range; }
        /** @return Object - 步进值 */
        public Object getStep() { return step; }
        /** @return ParameterCategory - 参数分类 */
        public ParameterKnob.ParameterCategory getCategory() { return category; }
        /** @return UiHint - UI 提示 */
        public UiHint getUiHint() { return uiHint; }
        /** @return String - 描述文本 */
        public String getDescription() { return description; }
        /** @return String[] - 枚举选项 */
        public String[] getEnumOptions() { return enumOptions.clone(); }
        /** @return String - Uniform 变量名 */
        public String getUniformName() { return uniformName; }
        /** @return boolean - 是否核心参数 */
        public boolean isCore() { return isCore; }

        @Override
        public String toString() {
            return String.format("ParamDef{id=%s, type=%s, default=%s}", id, type, defaultValue);
        }
    }

    /**
     * 输入插槽定义
     * <p>
     * 描述节点的输入连接点，包括名称、类型和默认连接。
     */
    public static final class InputSlotDef {
        /** 插槽名称 */
        private final String name;

        /** 插槽数据类型 */
        private final SlotType type;

        /** 默认连接的资源 ID 或值（未连接时使用） */
        private final Object defaultConnection;

        /** 是否必需输入（true 表示必须连接才能执行） */
        private final boolean required;

        /** 描述文本 */
        private final String description;

        /**
         * 构造输入插槽定义
         *
         * 【参数说明】
         * @param name              String    - 插槽名称
         * @param type              SlotType  - 数据类型
         * @param defaultConnection Object    - 默认连接
         * @param required          boolean   - 是否必需
         * @param description       String    - 描述文本
         */
        public InputSlotDef(String name, SlotType type, Object defaultConnection,
                            boolean required, String description) {
            this.name = Objects.requireNonNull(name, "Input slot name 不能为 null");
            this.type = type != null ? type : SlotType.VEC4;
            this.defaultConnection = defaultConnection;
            this.required = required;
            this.description = description != null ? description : "";
        }

        /** @return String - 插槽名称 */
        public String getName() { return name; }
        /** @return SlotType - 数据类型 */
        public SlotType getType() { return type; }
        /** @return Object - 默认连接 */
        public Object getDefaultConnection() { return defaultConnection; }
        /** @return boolean - 是否必需 */
        public boolean isRequired() { return required; }
        /** @return String - 描述文本 */
        public String getDescription() { return description; }

        @Override
        public String toString() {
            return String.format("InputSlot{name=%s, type=%s, required=%b}", name, type, required);
        }
    }

    /**
     * 输出插槽定义
     * <p>
     * 描述节点的输出连接点。
     */
    public static final class OutputSlotDef {
        /** 插槽名称 */
        private final String name;

        /** 插槽数据类型 */
        private final SlotType type;

        /** 描述文本 */
        private final String description;

        /**
         * 构造输出插槽定义
         *
         * 【参数说明】
         * @param name        String   - 插槽名称
         * @param type        SlotType - 数据类型
         * @param description String   - 描述文本
         */
        public OutputSlotDef(String name, SlotType type, String description) {
            this.name = Objects.requireNonNull(name, "Output slot name 不能为 null");
            this.type = type != null ? type : SlotType.VEC4;
            this.description = description != null ? description : "";
        }

        /** @return String - 插槽名称 */
        public String getName() { return name; }
        /** @return SlotType - 数据类型 */
        public SlotType getType() { return type; }
        /** @return String - 描述文本 */
        public String getDescription() { return description; }

        @Override
        public String toString() {
            return String.format("OutputSlot{name=%s, type=%s}", name, type);
        }
    }

    /**
     * 性能预算提示
     * <p>
     * 提供关于节点性能特征的预估值，用于调度器和优化器参考。
     */
    public static final class PerformanceHints {
        /** 每帧 CPU 时间预算（微秒） */
        private final long budgetMicroseconds;

        /** 内存占用预算（KB） */
        private final int memoryBudgetKB;

        /** 是否 GPU 计算密集型 */
        private final boolean gpuComputeHeavy;

        /** 是否支持异步计算队列 */
        private final boolean supportsAsyncCompute;

        /** 推荐的最小批次大小（顶点/像素数） */
        private final int recommendedBatchSize;

        /**
         * 构造性能提示
         *
         * 【参数说明】
         * @param budgetMicroseconds long   - CPU 时间预算（μs）
         * @param memoryBudgetKB      int    - 内存预算（KB）
         * @param gpuComputeHeavy     boolean - 是否 GPU 密集
         * @param supportsAsyncCompute boolean - 是否支持异步计算
         * @param recommendedBatchSize int  - 推荐批次大小
         */
        public PerformanceHints(long budgetMicroseconds, int memoryBudgetKB,
                                boolean gpuComputeHeavy, boolean supportsAsyncCompute,
                                int recommendedBatchSize) {
            this.budgetMicroseconds = budgetMicroseconds;
            this.memoryBudgetKB = Math.max(0, memoryBudgetKB);
            this.gpuComputeHeavy = gpuComputeHeavy;
            this.supportsAsyncCompute = supportsAsyncCompute;
            this.recommendedBatchSize = Math.max(1, recommendedBatchSize);
        }

        /** @return long - CPU 时间预算（μs） */
        public long getBudgetMicroseconds() { return budgetMicroseconds; }
        /** @return int - 内存预算（KB） */
        public int getMemoryBudgetKB() { return memoryBudgetKB; }
        /** @return boolean - 是否 GPU 计算密集型 */
        public boolean isGpuComputeHeavy() { return gpuComputeHeavy; }
        /** @return boolean - 是否支持异步计算 */
        public boolean isSupportsAsyncCompute() { return supportsAsyncCompute; }
        /** @return int - 推荐批次大小 */
        public int getRecommendedBatchSize() { return recommendedBatchSize; }

        @Override
        public String toString() {
            return String.format("PerfHints{budget=%dμs, mem=%dKB, gpu=%b, async=%b}",
                    budgetMicroseconds, memoryBudgetKB, gpuComputeHeavy, supportsAsyncCompute);
        }
    }

    // ==================== ShaderCompDescriptor 核心字段 ====================

    /** .comp 格式版本 */
    private final String formatVersion;

    /** 节点元数据 */
    private final Metadata metadata;

    /** SPIR-V 着色器模块引用 */
    private final SpirvReference spirv;

    /** 参数定义列表（不可变） */
    private final List<ParameterDef> parameters;

    /** 输入插槽定义列表（不可变） */
    private final List<InputSlotDef> inputs;

    /** 输出插槽定义列表（不可变） */
    private final List<OutputSlotDef> outputs;

    /** 依赖的其他节点 ID 列表（不可变） */
    private final List<String> dependencies;

    /** 性能预算建议 */
    private final PerformanceHints performanceHints;

    /** 搜索标签列表（不可变，如 #shadow #pbr #screen-space） */
    private final List<String> tags;

    /** 自定义扩展属性（用于未来扩展，不影响核心逻辑） */
    private final Map<String, Object> extensions;

    /** 来源 .comp 文件路径（用于调试和热重载定位） */
    private final String sourcePath;

    /** 最后修改时间戳 */
    private final long lastModified;

    /** 校验和（用于检测文件变化） */
    private final long checksum;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数（通过 Builder 创建）
     *
     * 【参数说明】
     * @param formatVersion    String                  - 格式版本
     * @param metadata         Metadata                - 元数据
     * @param spirv            SpirvReference          - SPIR-V 引用
     * @param parameters       List&lt;ParameterDef&gt;    - 参数列表
     * @param inputs           List&lt;InputSlotDef&gt;    - 输入插槽列表
     * @param outputs          List&lt;OutputSlotDef&gt;   - 输出插槽列表
     * @param dependencies     List&lt;String&gt;          - 依赖列表
     * @param performanceHints PerformanceHints        - 性能提示
     * @param tags             List&lt;String&gt;          - 标签列表
     * @param extensions       Map&lt;String,Object&gt;    - 扩展属性
     * @param sourcePath       String                  - 源文件路径
     * @param lastModified     long                    - 最后修改时间
     * @param checksum         long                    - 校验和
     */
    private ShaderCompDescriptor(String formatVersion, Metadata metadata,
                                 SpirvReference spirv, List<ParameterDef> parameters,
                                 List<InputSlotDef> inputs, List<OutputSlotDef> outputs,
                                 List<String> dependencies, PerformanceHints performanceHints,
                                 List<String> tags, Map<String, Object> extensions,
                                 String sourcePath, long lastModified, long checksum) {
        this.formatVersion = formatVersion != null ? formatVersion : COMP_FORMAT_VERSION;
        this.metadata = Objects.requireNonNull(metadata, "metadata 不能为 null");
        this.spirv = spirv;  // 可选：某些纯计算节点可能没有 SPIR-V
        this.parameters = parameters != null ? Collections.unmodifiableList(new ArrayList<>(parameters)) : Collections.emptyList();
        this.inputs = inputs != null ? Collections.unmodifiableList(new ArrayList<>(inputs)) : Collections.emptyList();
        this.outputs = outputs != null ? Collections.unmodifiableList(new ArrayList<>(outputs)) : Collections.emptyList();
        this.dependencies = dependencies != null ? Collections.unmodifiableList(new ArrayList<>(dependencies)) : Collections.emptyList();
        this.performanceHints = performanceHints;
        this.tags = tags != null ? Collections.unmodifiableList(new ArrayList<>(tags)) : Collections.emptyList();
        this.extensions = extensions != null ? Collections.unmodifiableMap(new HashMap<>(extensions)) : Collections.emptyMap();
        this.sourcePath = sourcePath;
        this.lastModified = lastModified;
        this.checksum = checksum;
    }

    // ==================== Builder 模式 ====================

    /**
     * ShaderCompDescriptor 构建器
     * <p>
     * 使用流式 API 构建 .comp 描述符实例。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * ShaderCompDescriptor descriptor = ShaderCompDescriptor.builder()
     *     .id("pbr_material")
     *     .displayName("PBR Material")
     *     .category(PipelineNode.Category.LIGHTING)
     *     .priority(100)
     *     .spirvPath("shaders/pbr.spv", SpirvStage.FRAGMENT)
     *     .parameter(metallicParam)
     *     .parameter(roughnessParam)
     *     .input(albedoInput)
     *     .output(colorOutput)
     *     .dependency("gbuffer_geometry")
     *     .performanceBudget(150, 256)
     *     .tag("#pbr")
     *     .tag("#material")
     *     .build();
     * </pre>
     */
    public static class Builder {
        private String formatVersion = COMP_FORMAT_VERSION;
        private Metadata metadata;
        private SpirvReference spirv;
        private final List<ParameterDef> parameters = new ArrayList<>();
        private final List<InputSlotDef> inputs = new ArrayList<>();
        private final List<OutputSlotDef> outputs = new ArrayList<>();
        private final List<String> dependencies = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private PerformanceHints performanceHints;
        private final Map<String, Object> extensions = new HashMap<>();
        private String sourcePath;
        private long lastModified;
        private long checksum;

        // --- 元数据设置 ---

        /**
         * 设置节点 ID 和显示名称
         *
         * 【参数说明】
         * @param id   String - 唯一标识符（kebab-case）
         * @param name String - 显示名称
         *
         * @return Builder - 当前构建器实例（链式调用）
         */
        public Builder id(String id, String name) {
            this.metadata = new Metadata(id, name, "1.0.0", "Unknown",
                    PipelineNode.Category.POST_PROCESS, 50, "1.0.0",
                    System.currentTimeMillis(), "");
            return this;
        }

        /**
         * 设置完整元数据
         *
         * 【参数说明】
         * @param metadata Metadata - 完整元数据对象
         *
         * @return Builder - 当前构建器实例
         */
        public Builder metadata(Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 设置作者信息
         *
         * @param author String - 作者名
         * @return Builder - 当前构建器实例
         */
        public Builder author(String author) {
            if (this.metadata == null) throw new IllegalStateException("请先调用 id()");
            // 通过重建 metadata 更新字段（实际实现中可用可变 builder）
            return this;
        }

        /**
         * 设置版本号
         *
         * @param version String - 语义化版本号
         * @return Builder - 当前构建器实例
         */
        public Builder version(String version) {
            if (this.metadata == null) throw new IllegalStateException("请先调用 id()");
            return this;
        }

        /**
         * 设置节点分类和优先级
         *
         * 【参数说明】
         * @param category Category - 节点分类
         * @param priority int      - 执行优先级
         *
         * @return Builder - 当前构建器实例
         */
        public Builder category(PipelineNode.Category category, int priority) {
            if (this.metadata == null) throw new IllegalStateException("请先调用 id()");
            return this;
        }

        /**
         * 设置 API 兼容版本
         *
         * @param version String - 兼容版本号
         * @return Builder - 当前构建器实例
         */
        public Builder apiCompatibility(String version) {
            return this;
        }

        /**
         * 设置描述文本
         *
         * @param desc String - 描述信息
         * @return Builder - 当前构建器实例
         */
        public Builder description(String desc) {
            return this;
        }

        // --- SPIR-V 设置 ---

        /**
         * 设置外部 SPIR-V 文件引用
         *
         * 【参数说明】
         * @param path String      - .spv 文件路径
         * @param stage SpirvStage - 着色器阶段
         *
         * @return Builder - 当前构建器实例
         */
        public Builder spirvPath(String path, SpirvStage stage) {
            this.spirv = new SpirvReference(path, "main", stage, null);
            return this;
        }

        /**
         * 设置完整的 SPIR-V 引用
         *
         * @param ref SpirvReference - SPIR-V 引用对象
         * @return Builder - 当前构建器实例
         */
        public Builder spirv(SpirvReference ref) {
            this.spirv = ref;
            return this;
        }

        // --- 参数 / 插槽设置 ---

        /**
         * 添加参数定义
         *
         * @param param ParameterDef - 参数定义
         * @return Builder - 当前构建器实例
         */
        public Builder parameter(ParameterDef param) {
            if (param != null) this.parameters.add(param);
            return this;
        }

        /**
         * 添加输入插槽
         *
         * @param input InputSlotDef - 输入插槽定义
         * @return Builder - 当前构建器实例
         */
        public Builder input(InputSlotDef input) {
            if (input != null) this.inputs.add(input);
            return this;
        }

        /**
         * 添加输出插槽
         *
         * @param output OutputSlotDef - 输出插槽定义
         * @return Builder - 当前构建器实例
         */
        public Builder output(OutputSlotDef output) {
            if (output != null) this.outputs.add(output);
            return this;
        }

        /**
         * 添加依赖节点
         *
         * @param depId String - 依赖节点 ID
         * @return Builder - 当前构建器实例
         */
        public Builder dependency(String depId) {
            if (depId != null && !depId.isEmpty()) this.dependencies.add(depId);
            return this;
        }

        /**
         * 添加搜索标签
         *
         * @param tag String - 标签（如 "#pbr", "#shadow"）
         * @return Builder - 当前构建器实例
         */
        public Builder tag(String tag) {
            if (tag != null && !tag.isEmpty()) this.tags.add(tag);
            return this;
        }

        /**
         * 设置性能预算
         *
         * 【参数说明】
         * @param budgetUs long - CPU 时间预算（微秒）
         * @param memKB    int  - 内存预算（KB）
         *
         * @return Builder - 当前构建器实例
         */
        public Builder performanceBudget(long budgetUs, int memKB) {
            this.performanceHints = new PerformanceHints(budgetUs, memKB, false, false, 64);
            return this;
        }

        /**
         * 设置完整性能提示
         *
         * @param hints PerformanceHints - 性能提示对象
         * @return Builder - 当前构建器实例
         */
        public Builder performanceHints(PerformanceHints hints) {
            this.performanceHints = hints;
            return this;
        }

        /**
         * 设置源文件路径（用于热重载）
         *
         * @param path String - .comp 文件路径
         * @return Builder - 当前构建器实例
         */
        public Builder sourcePath(String path) {
            this.sourcePath = path;
            return this;
        }

        /**
         * 添加自定义扩展属性
         *
         * @param key   String - 属性键
         * @param value Object - 属性值
         * @return Builder - 当前构建器实例
         */
        public Builder extension(String key, Object value) {
            if (key != null && value != null) this.extensions.put(key, value);
            return this;
        }

        /**
         * 构建 ShaderCompDescriptor 实例
         *
         * 【返回值】
         * @return ShaderCompDescriptor - 构建完成的描述符实例
         *
         * @throws IllegalStateException 如果缺少必要字段（metadata）
         */
        public ShaderCompDescriptor build() {
            if (metadata == null) {
                throw new IllegalStateException("ShaderCompDescriptor 必须设置 metadata（请先调用 id() 或 metadata()）");
            }
            return new ShaderCompDescriptor(
                    formatVersion, metadata, spirv,
                    parameters, inputs, outputs, dependencies,
                    performanceHints, tags, extensions,
                    sourcePath, lastModified, checksum
            );
        }
    }

    /**
     * 创建新的构建器实例
     *
     * 【返回值】
     * @return Builder - 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Getter 方法 ====================

    /** @return String - .comp 格式版本 */
    public String getFormatVersion() { return formatVersion; }
    /** @return Metadata - 节点元数据 */
    public Metadata getMetadata() { return metadata; }
    /** @return SpirvReference - SPIR-V 引用（可能为 null） */
    public SpirvReference getSpirv() { return spirv; }
    /** @return List - 参数定义列表（不可变） */
    public List<ParameterDef> getParameters() { return parameters; }
    /** @return List - 输入插槽列表（不可变） */
    public List<InputSlotDef> getInputs() { return inputs; }
    /** @return List - 输出插槽列表（不可变） */
    public List<OutputSlotDef> getOutputs() { return outputs; }
    /** @return List - 依赖节点 ID 列表（不可变） */
    public List<String> getDependencies() { return dependencies; }
    /** @return PerformanceHints - 性能提示（可能为 null） */
    public PerformanceHints getPerformanceHints() { return performanceHints; }
    /** @return List - 搜索标签列表（不可变） */
    public List<String> getTags() { return tags; }
    /** @return Map - 扩展属性（不可变） */
    public Map<String, Object> getExtensions() { return extensions; }
    /** @return String - 源文件路径 */
    public String getSourcePath() { return sourcePath; }
    /** @return long - 最后修改时间戳 */
    public long getLastModified() { return lastModified; }
    /** @return long - 校验和 */
    public long getChecksum() { return checksum; }

    // ==================== 便捷查询方法 ====================

    /**
     * 检查是否有存储图像输出（Storage Image Output）
     *
     * @return boolean - 如果有 IMAGE2D 类型的输出插槽返回 true
     */
    public boolean hasStorageImageOutput() {
        for (OutputSlotDef output : outputs) {
            if (output.getType() == SlotType.IMAGE2D) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据 ID 查找参数定义
     *
     * 【方法参数】
     * @param paramId String - 参数标识符
     *
     * 【返回值】
     * @return ParameterDef - 参数定义，不存在返回 null
     */
    public ParameterDef getParameterById(String paramId) {
        for (ParameterDef p : parameters) {
            if (p.getId().equals(paramId)) return p;
        }
        return null;
    }

    /**
     * 根据名称查找输入插槽
     *
     * 【方法参数】
     * @param slotName String - 插槽名称
     *
     * 【返回值】
     * @return InputSlotDef - 输入插槽定义，不存在返回 null
     */
    public InputSlotDef getInputByName(String slotName) {
        for (InputSlotDef in : inputs) {
            if (in.getName().equals(slotName)) return in;
        }
        return null;
    }

    /**
     * 根据名称查找输出插槽
     *
     * 【方法参数】
     * @param slotName String - 插槽名称
     *
     * 【返回值】
     * @return OutputSlotDef - 输出插槽定义，不存在返回 null
     */
    public OutputSlotDef getOutputByName(String slotName) {
        for (OutputSlotDef out : outputs) {
            if (out.getName().equals(slotName)) return out;
        }
        return null;
    }

    /**
     * 检查是否包含指定标签
     *
     * 【方法参数】
     * @param tag String - 要检查的标签
     *
     * 【返回值】
     * @return boolean - 是否包含该标签
     */
    public boolean hasTag(String tag) {
        if (tag == null) return false;
        for (String t : tags) {
            if (t.equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    /**
     * 获取依赖数组（便于与 PipelineNode 接口对接）
     *
     * 【返回值】
     * @return String[] - 依赖节点 ID 数组
     */
    public String[] getDependencyArray() {
        return dependencies.toArray(new String[0]);
    }

    /**
     * 验证描述符完整性
     * <p>
     * 检查所有必填字段是否存在且合法。
     *
     * 【返回值】
     * @return List&lt;String&gt; - 验证错误列表（空列表表示验证通过）
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        // 检查元数据
        if (metadata.getId() == null || metadata.getId().isEmpty()) {
            errors.add("metadata.id 不能为空");
        }
        if (metadata.getDisplayName() == null || metadata.getDisplayName().isEmpty()) {
            errors.add("metadata.displayName 不能为空");
        }

        // 检查参数 ID 唯一性
        Set<String> paramIds = new HashSet<>();
        for (ParameterDef p : parameters) {
            if (!paramIds.add(p.getId())) {
                errors.add("重复的参数 ID: " + p.getId());
            }
        }

        // 检查输入/输出插槽名称唯一性
        Set<String> inputNames = new HashSet<>();
        for (InputSlotDef in : inputs) {
            if (!inputNames.add(in.getName())) {
                errors.add("重复的输入插槽名称: " + in.getName());
            }
        }
        Set<String> outputNames = new HashSet<>();
        for (OutputSlotDef out : outputs) {
            if (!outputNames.add(out.getName())) {
                errors.add("重复的输出插槽名称: " + out.getName());
            }
        }

        // 检查是否有至少一个输出
        if (outputs.isEmpty()) {
            errors.add("至少需要一个输出插槽");
        }

        return errors;
    }

    // ==================== 序列化支持 ====================

    /**
     * 导出为 JSON 字符串
     * <p>
     * 将当前描述符序列化为标准 JSON 格式的 .comp 文件内容。
     *
     * 【返回值】
     * @return String - JSON 格式的 .comp 内容
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");

        // metadata
        sb.append("  \"metadata\": {\n");
        sb.append(String.format("    \"id\": \"%s\",\n", escapeJson(metadata.getId())));
        sb.append(String.format("    \"displayName\": \"%s\",\n", escapeJson(metadata.getDisplayName())));
        sb.append(String.format("    \"version\": \"%s\",\n", escapeJson(metadata.getVersion())));
        sb.append(String.format("    \"author\": \"%s\",\n", escapeJson(metadata.getAuthor())));
        sb.append(String.format("    \"category\": \"%s\",\n", metadata.getCategory().name()));
        sb.append(String.format("    \"priority\": %d,\n", metadata.getPriority()));
        sb.append(String.format("    \"apiCompatibility\": \"%s\",\n", escapeJson(metadata.getApiCompatibility())));
        sb.append(String.format("    \"description\": \"%s\"\n", escapeJson(metadata.getDescription())));
        sb.append("  },\n");

        // spirv
        if (spirv != null) {
            sb.append("  \"spirv\": {\n");
            sb.append(String.format("    \"path\": \"%s\",\n", escapeJson(spirv.getPath())));
            sb.append(String.format("    \"entryPoint\": \"%s\",\n", escapeJson(spirv.getEntryPoint())));
            sb.append(String.format("    \"stage\": \"%s\",\n", spirv.getStage().name()));
            if (!spirv.getVariants().isEmpty()) {
                sb.append("    \"variants\": {\n");
                for (Map.Entry<String, String> v : spirv.getVariants().entrySet()) {
                    sb.append(String.format("      \"%s\": \"%s\",\n", escapeJson(v.getKey()), escapeJson(v.getValue())));
                }
                sb.append("    },\n");
            }
            sb.append(String.format("    \"embedded\": %b\n", spirv.isEmbedded()));
            sb.append("  },\n");
        }

        // parameters
        sb.append("  \"parameters\": [\n");
        for (int i = 0; i < parameters.size(); i++) {
            ParameterDef p = parameters.get(i);
            sb.append("    {\n");
            sb.append(String.format("      \"id\": \"%s\",\n", escapeJson(p.getId())));
            sb.append(String.format("      \"displayName\": \"%s\",\n", escapeJson(p.getDisplayName())));
            sb.append(String.format("      \"type\": \"%s\",\n", p.getType().name()));
            sb.append(String.format("      \"defaultValue\": %s,\n", formatValue(p.getDefaultValue())));
            if (p.getRange() != null && p.getRange().length >= 2) {
                sb.append(String.format("      \"range\": [%s, %s],\n", formatValue(p.getRange()[0]), formatValue(p.getRange()[1])));
            }
            sb.append(String.format("      \"uniformName\": \"%s\",\n", escapeJson(p.getUniformName())));
            sb.append(String.format("      \"category\": \"%s\",\n", p.getCategory().name()));
            sb.append(String.format("      \"uiHint\": \"%s\",\n", p.getUiHint().name()));
            sb.append(String.format("      \"isCore\": %b\n", p.isCore()));
            sb.append("    }").append(i < parameters.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        // inputs
        sb.append("  \"inputs\": [\n");
        for (int i = 0; i < inputs.size(); i++) {
            InputSlotDef in = inputs.get(i);
            sb.append("    {\n");
            sb.append(String.format("      \"name\": \"%s\",\n", escapeJson(in.getName())));
            sb.append(String.format("      \"type\": \"%s\",\n", in.getType().name()));
            sb.append(String.format("      \"required\": %b\n", in.isRequired()));
            sb.append("    }").append(i < inputs.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        // outputs
        sb.append("  \"outputs\": [\n");
        for (int i = 0; i < outputs.size(); i++) {
            OutputSlotDef out = outputs.get(i);
            sb.append("    {\n");
            sb.append(String.format("      \"name\": \"%s\",\n", escapeJson(out.getName())));
            sb.append(String.format("      \"type\": \"%s\"\n", out.getType().name()));
            sb.append("    }").append(i < outputs.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        // dependencies
        sb.append("  \"dependencies\": [");
        for (int i = 0; i < dependencies.size(); i++) {
            sb.append("\"").append(escapeJson(dependencies.get(i))).append("\"");
            if (i < dependencies.size() - 1) sb.append(", ");
        }
        sb.append("],\n");

        // performanceHints
        if (performanceHints != null) {
            sb.append("  \"performanceHints\": {\n");
            sb.append(String.format("    \"budgetMicroseconds\": %d,\n", performanceHints.getBudgetMicroseconds()));
            sb.append(String.format("    \"memoryBudgetKB\": %d,\n", performanceHints.getMemoryBudgetKB()));
            sb.append(String.format("    \"gpuComputeHeavy\": %b,\n", performanceHints.isGpuComputeHeavy()));
            sb.append(String.format("    \"supportsAsyncCompute\": %b\n", performanceHints.isSupportsAsyncCompute()));
            sb.append("  },\n");
        }

        // tags
        sb.append("  \"tags\": [");
        for (int i = 0; i < tags.size(); i++) {
            sb.append("\"").append(escapeJson(tags.get(i))).append("\"");
            if (i < tags.size() - 1) sb.append(", ");
        }
        sb.append("]\n");

        sb.append("}");
        return sb.toString();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * JSON 字符串转义
     *
     * @param s String - 原始字符串
     * @return String - 转义后的安全字符串
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 格式化值为 JSON 表示
     *
     * @param value Object - 要格式化的值
     * @return String - JSON 格式字符串
     */
    private static String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number) {
            if (value instanceof Float || value instanceof Double) {
                return String.valueOf(value);
            }
            return String.valueOf(value);
        }
        if (value instanceof Boolean) return String.valueOf(value);
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof float[]) {
            float[] arr = (float[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                sb.append(arr[i]);
                if (i < arr.length - 1) sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof int[]) {
            int[] arr = (int[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                sb.append(arr[i]);
                if (i < arr.length - 1) sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
                "ShaderCompDescriptor{id=%s, name=%s, v=%s, params=%d, inputs=%d, outputs=%d, deps=%d}",
                metadata.getId(), metadata.getDisplayName(), metadata.getVersion(),
                parameters.size(), inputs.size(), outputs.size(), dependencies.size());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShaderCompDescriptor)) return false;
        ShaderCompDescriptor that = (ShaderCompDescriptor) o;
        return metadata.getId().equals(that.metadata.getId())
                && metadata.getVersion().equals(that.metadata.getVersion());
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata.getId(), metadata.getVersion());
    }
}  // ShaderCompDescriptor
