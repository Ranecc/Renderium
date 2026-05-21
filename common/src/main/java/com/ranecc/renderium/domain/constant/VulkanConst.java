package com.ranecc.renderium.domain.constant;

/**
 * VulkanConst - Vulkan API 常量集中定义 (26.2-snapshot-3 兼容)
 *
 * <p>将分散在 LWJGL 各个 VK* 类中的常用常量集中到一个位置，
 * 提高代码可读性和维护性。所有值均来自 Vulkan 规范。</p>
 *
 * <h3>包含内容:</h3>
 * <ul>
 *   <li>API 版本号 (VK_VERSION_*)</li>
 *   <li>格式常量 (VK_FORMAT_*)</li>
 *   <li>颜色空间常量 (VK_COLOR_SPACE_*)</li>
 *   <li>常用标志位</li>
 * </ul>
 */
public final class VulkanConst {
    private VulkanConst() {}

    // ==================== API 版本号 ====================
    /** Vulkan 1.0 = (1 << 22) | (0 << 12) | 0 */
    public static final int VERSION_1_0 = 0x00380000;
    /** Vulkan 1.1 = (1 << 22) | (1 << 12) | 0 */
    public static final int VERSION_1_1 = 0x003A0000;
    /** Vulkan 1.2 = (1 << 22) | (2 << 12) | 0 */
    public static final int VERSION_1_2 = 0x003C0000;
    /** Vulkan 1.3 = (1 << 22) | (3 << 12) | 0 */
    public static final int VERSION_1_3 = 0x003E0000;

    // ==================== 常用格式 ====================
    public static final int FORMAT_UNDEFINED = 0;
    /** VK_FORMAT_R8G8B8A8_UNORM - 4通道8位无符号归一化 */
    public static final int FORMAT_R8G8B8A8_UNORM = 37;
    /** VK_FORMAT_R8G8B8A8_SRGB - 4通道8位sRGB颜色空间 */
    public static final int FORMAT_R8G8B8A8_SRGB = 43;
    /** VK_FORMAT_B8G8R8A8_UNORM - BGRA格式无符号归一化 */
    public static final int FORMAT_B8G8R8A8_UNORM = 44;
    /** VK_FORMAT_B8G8R8A8_SRGB - BGRA格式sRGB颜色空间 */
    public static final int FORMAT_B8G8R8A8_SRGB = 50;
    /** VK_FORMAT_R32_SFLOAT - 单通道32位浮点 */
    public static final int FORMAT_R32_SFLOAT = 100;
    /** VK_FORMAT_R32G32_SFLOAT - 双通道32位浮点 */
    public static final int FORMAT_R32G32_SFLOAT = 103;
    /** VK_FORMAT_R32G32B32A32_SFLOAT - 四通道32位浮点 */
    public static final int FORMAT_R32G32B32A32_SFLOAT = 106;
    /** VK_FORMAT_D16_UNORM - 16位深度（无模板） */
    public static final int FORMAT_D16_UNORM = 124;
    /** VK_FORMAT_D24_UNORM_S8_UINT - 24位深度+8位模板 */
    public static final int FORMAT_D24_UNORM_S8_UINT = 125;
    /** VK_FORMAT_D32_SFLOAT - 32位浮点深度（无模板） */
    public static final int FORMAT_D32_SFLOAT = 126;
    /** VK_FORMAT_X8_D24_UNORM_PACK32 - 打包的24位深度（无模板） */
    public static final int FORMAT_X8_D24_UNORM_PACK32 = 127;
    /** VK_FORMAT_D32_SFLOAT_S8_UINT - 32位浮点深度+8位模板 */
    public static final int FORMAT_D32_SFLOAT_S8_UINT = 128;

    // ==================== 颜色空间 ====================
    /** VK_COLOR_SPACE_SRGB_NONLINEAR_KHR - 标准sRGB非线性颜色空间 */
    public static final int COLOR_SPACE_SRGB_NONLINEAR_KHR = 0;

    // ==================== 队列标志位 ====================
    /** Queue 支持图形操作 (VK_QUEUE_GRAPHICS_BIT) */
    public static final int QUEUE_GRAPHICS_BIT = 0x00000001;
    /** Queue 支持计算操作 (VK_QUEUE_COMPUTE_BIT) */
    public static final int QUEUE_COMPUTE_BIT = 0x00000008;
    /** Queue 支持传输操作 (VK_QUEUE_TRANSFER_BIT) */
    public static final int QUEUE_TRANSFER_BIT = 0x00000004;
    /** Queue 支持稀疏内存操作 (VK_QUEUE_SPARSE_BINDING_BIT) */
    public static final int QUEUE_SPARSE_BINDING_BIT = 0x00000002;

    // ==================== 内存属性标志位 ====================
    /** 内存属性: 设备本地 (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) */
    public static final int MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 0x00000001;
    /** 内存属性: 主机可见 (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) */
    public static final int MEMORY_PROPERTY_HOST_VISIBLE_BIT = 0x00000002;
    /** 内存属性: 主机一致性 (VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) */
    public static final int MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x00000004;
    /** 内存属性: 主机缓存 (VK_MEMORY_PROPERTY_HOST_CACHED_BIT) */
    public static final int MEMORY_PROPERTY_HOST_CACHED_BIT = 0x00000008;

    // ==================== Image Aspect 标志 ====================
    /** 颜色 Image Aspect (VK_IMAGE_ASPECT_COLOR_BIT) */
    public static final int IMAGE_ASPECT_COLOR_BIT = 0x00000001;
    /** 深度 Image Aspect (VK_IMAGE_ASPECT_DEPTH_BIT) */
    public static final int IMAGE_ASPECT_DEPTH_BIT = 0x00000002;
    /** 模板 Image Aspect (VK_IMAGE_ASPECT_STENCIL_BIT) */
    public static final int IMAGE_ASPECT_STENCIL_BIT = 0x00000004;
    /** 元数据 Image Aspect (VK_IMAGE_ASPECT_METADATA_BIT) */
    public static final int IMAGE_ASPECT_METADATA_BIT = 0x00000008;

    // ==================== Image 使用标志 ====================
    /** Image 可用于颜色附件 (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) */
    public static final int IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 0x00000010;
    /** Image 可用于深度/模板附件 (VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) */
    public static final int IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT = 0x00000020;
    /** Image 可用于采样器 (VK_IMAGE_USAGE_SAMPLED_BIT) */
    public static final int IMAGE_USAGE_SAMPLED_BIT = 0x00000004;
    /** Image 可用于存储 (VK_IMAGE_USAGE_STORAGE_BIT) */
    public static final int IMAGE_USAGE_STORAGE_BIT = 0x00000020;
    /** Image 可用于传输源 (VK_IMAGE_USAGE_TRANSFER_SRC_BIT) */
    public static final int IMAGE_USAGE_TRANSFER_SRC_BIT = 0x00000001;
    /** Image 可用于传输目标 (VK_IMAGE_USAGE_TRANSFER_DST_BIT) */
    public static final int IMAGE_USAGE_TRANSFER_DST_BIT = 0x00000002;

    // ==================== Buffer 使用标志 ====================
    /** Buffer 可用于传输源 (VK_BUFFER_USAGE_TRANSFER_SRC_BIT) */
    public static final int BUFFER_USAGE_TRANSFER_SRC_BIT = 0x00000001;
    /** Buffer 可用于传输目标 (VK_BUFFER_USAGE_TRANSFER_DST_BIT) */
    public static final int BUFFER_USAGE_TRANSFER_DST_BIT = 0x00000002;
    /** Buffer 可用于 Uniform Texel Buffer (VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT) */
    public static final int BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT = 0x00000004;
    /** Buffer 可用于 Storage Texel Buffer (VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT) */
    public static final int BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT = 0x00000008;
    /** Buffer 可用于 Uniform Buffer (VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) */
    public static final int BUFFER_USAGE_UNIFORM_BUFFER_BIT = 0x00000010;
    /** Buffer 可用于 Storage Buffer (VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) */
    public static final int BUFFER_USAGE_STORAGE_BUFFER_BIT = 0x00000020;
    /** Buffer 可用于索引缓冲区 (VK_BUFFER_USAGE_INDEX_BUFFER_BIT) */
    public static final int BUFFER_USAGE_INDEX_BUFFER_BIT = 0x00000040;
    /** Buffer 可用于顶点缓冲区 (VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) */
    public static final int BUFFER_USAGE_VERTEX_BUFFER_BIT = 0x00000080;
    /** Buffer 可用于间接绘制 (VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) */
    public static final int BUFFER_USAGE_INDIRECT_BUFFER_BIT = 0x00000100;

    // ==================== Shader Stage 标志 ====================
    /** 顶点着色器阶段 (VK_SHADER_STAGE_VERTEX_BIT) */
    public static final int SHADER_STAGE_VERTEX_BIT = 0x00000001;
    /** 片段着色器阶段 (VK_SHADER_STAGE_FRAGMENT_BIT) */
    public static final int SHADER_STAGE_FRAGMENT_BIT = 0x00000010;
    /** 计算着色器阶段 (VK_SHADER_STAGE_COMPUTE_BIT) */
    public static final int SHADER_STAGE_COMPUTE_BIT = 0x00000020;
    /** 几何着色器阶段 (VK_SHADER_STAGE_GEOMETRY_BIT) */
    public static final int SHADER_STAGE_GEOMETRY_BIT = 0x00000008;
    /** Tessellation Control 着色器阶段 (VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT) */
    public static final int SHADER_STAGE_TESSELLATION_CONTROL_BIT = 0x00000004;
    /** Tessellation Evaluation 着色器阶段 (VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT) */
    public static final int SHADER_STAGE_TESSELLATION_EVALUATION_BIT = 0x00000002;

    // ==================== Pipeline 标志 ====================
    /** Pipeline 绑定点: 图形管线 (VK_PIPELINE_BIND_POINT_GRAPHICS) */
    public static final int PIPELINE_BIND_POINT_GRAPHICS = 0;
    /** Pipeline 绑定点: 计算管线 (VK_PIPELINE_BIND_POINT_COMPUTE) */
    public static final int PIPELINE_BIND_POINT_COMPUTE = 1;

    // ==================== Filter 模式 ====================
    /** 最近邻过滤 (VK_FILTER_NEAREST) */
    public static final int FILTER_NEAREST = 0;
    /** 双线性过滤 (VK_FILTER_LINEAR) */
    public static final int FILTER_LINEAR = 1;

    // ==================== Sampler Address Mode ====================
    /** 重复寻址模式 (VK_SAMPLER_ADDRESS_MODE_REPEAT) */
    public static final int SAMPLER_ADDRESS_MODE_REPEAT = 0;
    /** 镜像重复寻址模式 (VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT) */
    public static final int SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT = 1;
    /** 钳制到边缘寻址模式 (VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE) */
    public static final int SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE = 2;
    /** 钳制到边框色寻址模式 (VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER) */
    public static final int SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER = 3;
    /** 镜像钳制到边缘 (VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE) */
    public static final int SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE = 4;

    // ==================== Polygon Mode ====================
    /** 填充多边形模式 (VK_POLYGON_MODE_FILL) */
    public static final int POLYGON_MODE_FILL = 0;
    /** 线框多边形模式 (VK_POLYGON_MODE_LINE) */
    public static final int POLYGON_MODE_LINE = 1;
    /** 点多边形模式 (VK_POLYGON_MODE_POINT) */
    public static final int POLYGON_MODE_POINT = 2;

    // ==================== Cull Mode ====================
    /** 无剔除 (VK_CULL_MODE_NONE) */
    public static final int CULL_MODE_NONE = 0;
    /** 剔除正面 (VK_CULL_MODE_FRONT_BIT) */
    public static final int CULL_MODE_FRONT_BIT = 0x00000001;
    /** 剔除背面 (VK_CULL_MODE_BACK_BIT) */
    public static final int CULL_MODE_BACK_BIT = 0x00000002;
    /** 剔除正面和背面 (VK_CULL_MODE_FRONT_AND_BACK) */
    public static final int CULL_MODE_FRONT_AND_BACK = 0x00000003;

    // ==================== Front Face ====================
    /** 顺时针为正面 (VK_FRONT_FACE_CLOCKWISE) */
    public static final int FRONT_FACE_CLOCKWISE = 0;
    /** 逆时针为正面 (VK_FRONT_FACE_COUNTER_CLOCKWISE) */
    public static final int FRONT_FACE_COUNTER_CLOCKWISE = 1;

    // ==================== Compare Op ====================
    /** 从不通过比较 (VK_COMPARE_OP_NEVER) */
    public static final int COMPARE_OP_NEVER = 0;
    /** 总是通过比较 (VK_COMPARE_OP_ALWAYS) */
    public static final int COMPARE_OP_ALWAYS = 1;
    /** 小于比较 (VK_COMPARE_OP_LESS) */
    public static final int COMPARE_OP_LESS = 2;
    /** 小于等于比较 (VK_COMPARE_OP_LESS_OR_EQUAL) */
    public static final int COMPARE_OP_LESS_OR_EQUAL = 3;
    /** 等于比较 (VK_COMPARE_OP_EQUAL) */
    public static final int COMPARE_OP_EQUAL = 4;
    /** 不等于比较 (VK_COMPARE_OP_NOT_EQUAL) */
    public static final int COMPARE_OP_NOT_EQUAL = 5;
    /** 大于等于比较 (VK_COMPARE_OP_GREATER_OR_EQUAL) */
    public static final int COMPARE_OP_GREATER_OR_EQUAL = 6;
    /** 大于比较 (VK_COMPARE_OP_GREATER) */
    public static final int COMPARE_OP_GREATER = 7;

    // ==================== Stencil Op ====================
    /** 保持模板值不变 (VK_STENCIL_OP_KEEP) */
    public static final int STENCIL_OP_KEEP = 0;
    /** 将模板值置零 (VK_STENCIL_OP_ZERO) */
    public static final int STENCIL_OP_ZERO = 1;
    /** 替换模板值为参考值 (VK_STENCIL_OP_REPLACE) */
    public static final int STENCIL_OP_REPLACE = 2;
    /** 递增并钳制模板值 (VK_STENCIL_OP_INCREMENT_AND_CLAMP) */
    public static final int STENCIL_OP_INCREMENT_AND_CLAMP = 3;
    /** 递减并钳制模板值 (VK_STENCIL_OP_DECREMENT_AND_CLAMP) */
    public static final int STENCIL_OP_DECREMENT_AND_CLAMP = 4;
    /** 递增并翻转模板值 (VK_STENCIL_OP_INVERT) */
    public static final int STENCIL_OP_INVERT = 5;
    /** 递增并包装模板值 (VK_STENCIL_OP_INCREMENT_AND_WRAP) */
    public static final int STENCIL_OP_INCREMENT_AND_WRAP = 6;
    /** 递减并包装模板值 (VK_STENCIL_OP_DECREMENT_AND_WRAP) */
    public static final int STENCIL_OP_DECREMENT_AND_WRAP = 7;

    // ==================== Blend Factor ====================
    /** Blend factor: 零 (VK_BLEND_FACTOR_ZERO) */
    public static final int BLEND_FACTOR_ZERO = 0;
    /** Blend factor: 一 (VK_BLEND_FACTOR_ONE) */
    public static final int BLEND_FACTOR_ONE = 1;
    /** Blend factor: 源颜色 (VK_BLEND_FACTOR_SRC_COLOR) */
    public static final int BLEND_FACTOR_SRC_COLOR = 2;
    /** Blend factor: 一减去源颜色 (VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR) */
    public static final int BLEND_FACTOR_ONE_MINUS_SRC_COLOR = 3;
    /** Blend factor: 目标颜色 (VK_BLEND_FACTOR_DST_COLOR) */
    public static final int BLEND_FACTOR_DST_COLOR = 4;
    /** Blend factor: 一减去目标颜色 (VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR) */
    public static final int BLEND_FACTOR_ONE_MINUS_DST_COLOR = 5;
    /** Blend factor: 源Alpha (VK_BLEND_FACTOR_SRC_ALPHA) */
    public static final int BLEND_FACTOR_SRC_ALPHA = 6;
    /** Blend factor: 一减去源Alpha (VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA) */
    public static final int BLEND_FACTOR_ONE_MINUS_SRC_ALPHA = 7;
    /** Blend factor: 目标Alpha (VK_BLEND_FACTOR_DST_ALPHA) */
    public static final int BLEND_FACTOR_DST_ALPHA = 8;
    /** Blend factor: 一减去目标Alpha (VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA) */
    public static final int BLEND_FACTOR_ONE_MINUS_DST_ALPHA = 9;

    // ==================== Blend Op ====================
    /** Blend 操作: 加法 (VK_BLEND_OP_ADD) */
    public static final int BLEND_OP_ADD = 0;
    /** Blend 操作: 减法 (VK_BLEND_OP_SUBTRACT) */
    public static final int BLEND_OP_SUBTRACT = 1;
    /** Blend 操作: 反向减法 (VK_BLEND_OP_REVERSE_SUBTRACT) */
    public static final int BLEND_OP_REVERSE_SUBTRACT = 2;
    /** Blend 操作: 最小值 (VK_BLEND_OP_MIN) */
    public static final int BLEND_OP_MIN = 3;
    /** Blend 操作: 最大值 (VK_BLEND_OP_MAX) */
    public static final int BLEND_OP_MAX = 4;

    // ==================== Logic Op ====================
    /** Logic 操作: 清除 (VK_LOGIC_OP_CLEAR) */
    public static final int LOGIC_OP_CLEAR = 0;
    /** Logic 操作: 设置 (VK_LOGIC_OP_SET) */
    public static final int LOGIC_OP_SET = 1;
    /** Logic 操作: 复制 (VK_LOGIC_OP_COPY) */
    public static final int LOGIC_OP_COPY = 2;
    /** Logic 操作: 复制反转 (VK_LOGIC_OP_COPY_INVERTED) */
    public static final int LOGIC_OP_COPY_INVERTED = 3;
    /** Logic 操作: 无操作 (VK_LOGIC_OP_NO_OP) */
    public static final int LOGIC_OP_NO_OP = 4;
    /** Logic 操作: 反转无操作 (VK_LOGIC_OP_INVERT) */
    public static final int LOGIC_OP_INVERT = 5;
    /** Logic 操作: 与 (VK_LOGIC_OP_AND) */
    public static final int LOGIC_OP_AND = 6;
    /** Logic 操作: 与非 (VK_LOGIC_OP_NAND) */
    public static final int LOGIC_OP_NAND = 7;
    /** Logic 操作: 或 (VK_LOGIC_OP_OR) */
    public static final int LOGIC_OP_OR = 8;
    /** Logic 操作: 或非 (VK_LOGIC_OP_NOR) */
    public static final int LOGIC_OP_NOR = 9;
    /** Logic 操作: 异或 (VK_LOGIC_OP_XOR) */
    public static final int LOGIC_OP_XOR = 10;
    /** Logic 操作: 同或 (VK_LOGIC_OP_EQUIVALENT) */
    public static final int LOGIC_OP_EQUIVALENT = 11;
    /** Logic 操作: 反转与 (VK_LOGIC_OP_AND_REVERSE) */
    public static final int LOGIC_OP_AND_REVERSE = 12;
    /** Logic 操作: 反转或 (VK_LOGIC_OP_OR_REVERSE) */
    public static final int LOGIC_OP_OR_REVERSE = 13;

    // ==================== Attachment Load/Store Op ====================
    /** 加载操作: 不关心内容 (VK_ATTACHMENT_LOAD_OP_DONT_CARE) */
    public static final int ATTACHMENT_LOAD_OP_DONT_CARE = 0;
    /** 加载操作: 加载之前的内容 (VK_ATTACHMENT_LOAD_OP_LOAD) */
    public static final int ATTACHMENT_LOAD_OP_LOAD = 1;
    /** 加载操作: 清除 (VK_ATTACHMENT_LOAD_OP_CLEAR) */
    public static final int ATTACHMENT_LOAD_OP_CLEAR = 2;
    /** 存储操作: 不关心内容 (VK_ATTACHMENT_STORE_OP_DONT_CARE) */
    public static final int ATTACHMENT_STORE_OP_DONT_CARE = 0;
    /** 存储操作: 存储结果 (VK_ATTACHMENT_STORE_OP_STORE) */
    public static final int ATTACHMENT_STORE_OP_STORE = 1;

    // ==================== Descriptor Type ====================
    /** 描述符类型: Uniform Buffer (VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) */
    public static final int DESCRIPTOR_TYPE_UNIFORM_BUFFER = 0;
    /** 描述符类型: Combined Image Sampler (VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) */
    public static final int DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;
    /** 描述符类型: Sampled Image (VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE) */
    public static final int DESCRIPTOR_TYPE_SAMPLED_IMAGE = 2;
    /** 描述符类型: Storage Image (VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) */
    public static final int DESCRIPTOR_TYPE_STORAGE_IMAGE = 3;
    /** 描述符类型: Uniform Texel Buffer (VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER) */
    public static final int DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER = 4;
    /** 描述符类型: Storage Texel Buffer (VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER) */
    public static final int DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER = 5;
    /** 描述符类型: Storage Buffer (VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) */
    public static final int DESCRIPTOR_TYPE_STORAGE_BUFFER = 6;
    /** 描述符类型: Uniform Buffer Dynamic (VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC) */
    public static final int DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC = 7;
    /** 描述符类型: Storage Buffer Dynamic (VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC) */
    public static final int DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC = 8;
    /** 描述符类型: Input Attachment (VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT) */
    public static final int DESCRIPTOR_TYPE_INPUT_ATTACHMENT = 9;

    // ==================== Command Buffer Level ====================
    /** Command Buffer 级别: 主要 (VK_COMMAND_BUFFER_LEVEL_PRIMARY) */
    public static final int COMMAND_BUFFER_LEVEL_PRIMARY = 0;
    /** Command Buffer 级别: 次要 (VK_COMMAND_BUFFER_LEVEL_SECONDARY) */
    public static final int COMMAND_BUFFER_LEVEL_SECONDARY = 1;

    // ==================== Index Type ====================
    /** 索引类型: 16位无符号整数 (VK_INDEX_TYPE_UINT16) */
    public static final int INDEX_TYPE_UINT16 = 0;
    /** 索引类型: 32位无符号整数 (VK_INDEX_TYPE_UINT32) */
    public static final int INDEX_TYPE_UINT32 = 1;

    // ==================== Result Codes ====================
    /** 操作成功完成 (VK_SUCCESS) */
    public static final int SUCCESS = 0;
    /** 尚未完成 (VK_NOT_READY) */
    public static final int NOT_READY = 1;
    /** 超时 (VK_TIMEOUT) */
    public static final int TIMEOUT = 2;
    /** 事件被设置 (VK_EVENT_SET) */
    public static final int EVENT_SET = 3;
    /** 事件未被设置 (VK_EVENT_RESET) */
    public static final int EVENT_RESET = 4;
    /** 未完全完成 (VK_INCOMPLETE) */
    public static final int INCOMPLETE = 5;
    /** 操作正在执行中 (VK_SUBOPTIMAL_KHR) */
    public static final int SUBOPTIMAL_KHR = 1000001003;
    /** 交换链过期 (VK_ERROR_OUT_OF_DATE_KHR) */
    public static final int ERROR_OUT_OF_DATE_KHR = -1000001004;

    // ==================== Streamline SDK 结构体类型 ====================
    /** Streamline 结构体类型: ViewportHandle (SL_STRUCT_TYPE_VIEWPORT_HANDLE) */
    public static final int SL_STRUCT_TYPE_VIEWPORT_HANDLE = 0x19;
    /** Streamline 结构体类型: DLSS Options (SL_STRUCT_TYPE_DLSS_OPTIONS) */
    public static final int SL_STRUCT_TYPE_DLSS_OPTIONS = 0x1B;
    /** Streamline 结构体类型: DLSS-G Options (SL_STRUCT_TYPE_DLSS_G_OPTIONS) */
    public static final int SL_STRUCT_TYPE_DLSS_G_OPTIONS = 0x1C;

    // ==================== Streamline SDK 结构体大小 ====================
    /** ViewportHandle 结构体大小 (字节) */
    public static final int SL_VIEWPORT_HANDLE_SIZE = 8;
    /** DLSSOptions 结构体大小 (字节) */
    public static final int SL_DLSS_OPTIONS_SIZE = 32;
    /** DLSS-G Options 结构体大小 (字节) */
    public static final int SL_DLSS_G_OPTIONS_SIZE = 16;
    /** DLSS 输出设置结构体大小 (字节) */
    public static final int SL_DLSS_OPTIMAL_SETTINGS_SIZE = 32;

    // ==================== Streamline SDK 结构体偏移量 ====================
    /** DLSSOptions: sType 偏移量 */
    public static final int SL_DLSS_OPTIONS_OFFSET_STYPE = 0;
    /** DLSSOptions: mode 偏移量 */
    public static final int SL_DLSS_OPTIONS_OFFSET_MODE = 8;
    /** DLSSOptions: outputScalingEnabled 偏移量 */
    public static final int SL_DLSS_OPTIONS_OFFSET_OUTPUT_SCALING = 12;
    /** DLSSOptions: sharpness 偏移量 */
    public static final int SL_DLSS_OPTIONS_OFFSET_SHARPNESS = 16;
    /** DLSSOptions: renderWidth 偏移量 */
    public static final int SL_DLSS_OPTIONS_OFFSET_RENDER_WIDTH = 20;
    /** DLSSOptions: renderHeight 偏移量 */
    public static final int SL_DLSS_OPTIONS_OFFSET_RENDER_HEIGHT = 24;
    /** DLSSOptions: displayWidth 偏移量 */
    public static final int SL_DLSS_OPTIONS_OFFSET_DISPLAY_WIDTH = 28;

    /** DLSS 输出设置: recommendedRenderSizeX 偏移量 */
    public static final int SL_DLSS_SETTINGS_OFFSET_RENDER_SIZE_X = 8;
    /** DLSS 输出设置: recommendedRenderSizeY 偏移量 */
    public static final int SL_DLSS_SETTINGS_OFFSET_RENDER_SIZE_Y = 12;

    /** ViewportHandle: sType 偏移量 */
    public static final int SL_VIEWPORT_HANDLE_OFFSET_STYPE = 0;
    /** ViewportHandle: index 偏移量 */
    public static final int SL_VIEWPORT_HANDLE_OFFSET_INDEX = 4;

    /** DLSS-G Options: sType 偏移量 */
    public static final int SL_DLSS_G_OPTIONS_OFFSET_STYPE = 0;
    /** DLSS-G Options: mode 偏移量 */
    public static final int SL_DLSS_G_OPTIONS_OFFSET_MODE = 8;
    /** DLSS-G Options: numFramesToGenerate 偏移量 */
    public static final int SL_DLSS_G_OPTIONS_OFFSET_NUM_FRAMES = 12;

    // ==================== Streamline VulkanInfo 结构体 ====================
    
    /** sl::VulkanInfo 结构体大小 (字节) - 8 fields * 4/8 bytes */
    public static final int SL_VULKAN_INFO_SIZE = 40;
    
    /** VulkanInfo: instance 偏移量 (VkInstance, 8 bytes) */
    public static final int SL_VULKAN_INFO_OFFSET_INSTANCE = 0;
    /** VulkanInfo: physicalDevice 偏移量 (VkPhysicalDevice, 8 bytes) */
    public static final int SL_VULKAN_INFO_OFFSET_PHYSICAL_DEVICE = 8;
    /** VulkanInfo: device 偏移量 (VkDevice, 8 bytes) */
    public static final int SL_VULKAN_INFO_OFFSET_DEVICE = 16;
    /** VulkanInfo: computeQueue 偏移量 (VkQueue, 8 bytes) */
    public static final int SL_VULKAN_INFO_OFFSET_COMPUTE_QUEUE = 24;
    /** VulkanInfo: computeQueueIndex 偏移量 (uint32_t, 4 bytes) */
    public static final int SL_VULKAN_INFO_OFFSET_COMPUTE_QUEUE_INDEX = 32;
    /** VulkanInfo: graphicsQueueIndex 偏移量 (uint32_t, 4 bytes) */
    public static final int SL_VULKAN_INFO_OFFSET_GRAPHICS_QUEUE_INDEX = 36;

    // ==================== Streamline ResourceTag 结构体 ====================
    
    /** sl::ResourceTag 结构体大小 (字节) */
    public static final int SL_RESOURCE_TAG_SIZE = 24;
    
    /** ResourceTag: bufferType 偏移量 */
    public static final int SL_RESOURCE_TAG_OFFSET_BUFFER_TYPE = 0;
    /** ResourceTag: imageView 偏移量 (VkImageView, 8 bytes) */
    public static final int SL_RESOURCE_TAG_OFFSET_IMAGE_VIEW = 8;
    /** ResourceTag: width 偏移量 */
    public static final int SL_RESOURCE_TAG_OFFSET_WIDTH = 16;
    /** ResourceTag: height 偏移量 */
    public static final int SL_RESOURCE_TAG_OFFSET_HEIGHT = 20;
    /** ResourceTag: resourceLifecycle 偏移量 */
    public static final int SL_RESOURCE_TAG_OFFSET_LIFECYCLE = 24;

    // ==================== Streamline ResourceLifecycle 枚举 ====================
    
    /** 资源仅在当前帧有效 */
    public static final int RESOURCE_LIFECYCLE_ONLY_VALID_NOW = 0;
    /** 资源在呈现前有效 */
    public static final int RESOURCE_LIFECYCLE_VALID_UNTIL_PRESENT = 1;
    /** 资源在评估时有效 */
    public static final int RESOURCE_LIFECYCLE_VALID_UNTIL_EVALUATE = 2;
}
