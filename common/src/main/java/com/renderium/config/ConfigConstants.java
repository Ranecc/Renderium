// Renderium - 全局配置常量定义
// 集中管理所有硬编码配置默认值、几何常量、性能常量

package com.renderium.config;

/**
 * Renderium 全局配置常量。
 *
 * <p>集中管理所有硬编码的配置默认值、几何常量、性能常量，
 * 避免魔法数字散布在代码各处。
 *
 * <h2>使用指南</h2>
 * <pre>
 * // 错误：使用魔法数字
 * int[] buffer = new int[1024];
 * int parallelism = Runtime.getRuntime().availableProcessors() - 1;
 *
 * // 正确：使用命名常量
 * int[] buffer = new int[ConfigConstants.DEFAULT_BUFFER_SIZE];
 * int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors()
 *         - ConfigConstants.RESERVED_CORES_FOR_RENDER_THREAD);
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class ConfigConstants {

    private ConfigConstants() {
        // 工具类，禁止实例化
    }

    // ==================== 缓冲区大小常量 ====================

    /**
     * 默认缓冲区大小（1024 元素）
     * <p>用于中等大小的临时数组、命令缓冲区等
     */
    public static final int DEFAULT_BUFFER_SIZE = 1024;

    /**
     * 大型缓冲区大小（65536 元素 = 64K）
     * <p>用于区段可见性数据、大规模顶点缓冲等
     */
    public static final int LARGE_BUFFER_SIZE = 65536;

    /**
     * 小型缓冲区大小（64 元素）
     * <p>用于临时命令列表、小型查询结果等
     */
    public static final int SMALL_BUFFER_SIZE = 64;

    /**
     * 微缓冲区大小（16 元素）
     * <p>用于极小型临时数据、参数传递等
     */
    public static final int MICRO_BUFFER_SIZE = 16;

    // ==================== 几何常量 ====================

    /**
     * Minecraft 区块尺寸（16 方块）
     * <p>一个标准区块在 X/Z 方向上的方块数量
     */
    public static final int CHUNK_SIZE = 16;

    /**
     * Minecraft 区块段尺寸（16 方块）
     * <p>一个区块段（Section）在 X/Y/Z 方向上的方块数量
     */
    public static final int SECTION_SIZE = 16;

    /**
     * 区块段体积（16^3 = 4096 方块）
     * <p>一个区块段包含的总方块数量
     */
    public static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    /**
     * 区块截面积（16^2 = 256 方块）
     * <p>一个区块在水平面上的方块数量
     */
    public static final int CHUNK_AREA = CHUNK_SIZE * CHUNK_SIZE;

    // ==================== 线程池常量 ====================

    /**
     * 为渲染线程保留的 CPU 核心数
     * <p>计算工作线程并行度时减去此值，为主线程留出资源
     */
    public static final int RESERVED_CORES_FOR_RENDER_THREAD = 1;

    /**
     * 默认网格构建 Worker 最大数量
     * <p>即使 CPU 核心很多，也不应创建过多 Worker 线程
     */
    public static final int MAX_MESH_BUILD_WORKERS = 10;

    /**
     * 网格构建任务队列最大容量
     * <p>防止任务堆积导致内存溢出
     */
    public static final int MESH_BUILD_QUEUE_CAPACITY = 4096;

    /**
     * 线程池优雅关闭超时时间（秒）
     * <p>等待正在执行的任务完成的最大时间
     */
    public static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    // ==================== 对象池常量 ====================

    /**
     * 对象池默认每线程最大容量
     * <p>每个线程本地池最多保留的对象数量，防止内存泄漏
     */
    public static final int DEFAULT_OBJECT_POOL_CAPACITY = 32;

    /**
     * 小型对象池每线程最大容量
     * <p>用于 float[2]、float[4] 等小数组
     */
    public static final int SMALL_OBJECT_POOL_CAPACITY = 16;

    /**
     * 矩阵对象池每线程最大容量
     * <p>用于 float[16] 4x4 矩阵等
     */
    public static final int MATRIX_POOL_CAPACITY = 8;

    /**
     * StringBuilder 池容量
     */
    public static final int STRING_BUILDER_POOL_CAPACITY = 8;

    // ==================== Compute Shader 常量 ====================

    /**
     * Compute Shader 工作组大小
     * <p>GPU 并行计算的本地工作组线程数
     */
    public static final int COMPUTE_WORKGROUP_SIZE = 64;

    /**
     * Compute Shader 处理的最大区段数量
     * <p>对应 65536 个区段，足够覆盖大型世界
     */
    public static final int MAX_COMPUTE_SECTION_COUNT = 65536;

    /**
     * Compute Shader 每个工作组处理的区段数
     * <p>实际调度时使用此值计算工作组数量
     */
    public static final int SECTIONS_PER_WORKGROUP = 256;

    // ==================== 渲染常量 ====================

    /**
     * DrawCall 批处理最大批次大小
     * <p>单次批处理合并的最大 DrawCall 数量
     */
    public static final int MAX_BATCH_SIZE = 256;

    /**
     * 默认顶点格式颜色通道数（RGBA = 4）
     */
    public static final int VERTEX_COLOR_CHANNELS = 4;

    /**
     * 4x4 变换矩阵元素数量
     */
    public static final int MATRIX_4X4_SIZE = 16;

    /**
     * 3x4 变换矩阵元素数量
     */
    public static final int MATRIX_3X4_SIZE = 12;

    /**
     * 2D 向量元素数量
     */
    public static final int VECTOR_2D_SIZE = 2;

    /**
     * 4D 向量元素数量（XYZW 位置、RGBA 颜色等）
     */
    public static final int VECTOR_4D_SIZE = 4;

    // ==================== 可见性编码常量 ====================

    /**
     * 可见性编码方向掩码（6 个方向 = 0b111111）
     * <p>用于表示上下左右前后六个方向的可见性
     */
    public static final int ALL_DIRECTIONS_MASK = 0x3F;

    /**
     * 可见性编码方向数量
     */
    public static final int DIRECTION_COUNT = 6;

    // ==================== 帧生成常量 ====================

    /**
     * 帧评估历史窗口大小
     * <p>用于计算帧时间移动平均的样本数量
     */
    public static final int FRAME_EVAL_HISTORY_SIZE = 64;

    /**
     * 默认目标帧率（FPS）
     */
    public static final int DEFAULT_TARGET_FPS = 60;

    // ==================== 内存管理常量 ====================

    /**
     * VMA 内存预算警告阈值（0.8 = 80%）
     * <p>当内存使用超过此比例时发出警告
     */
    public static final double MEMORY_BUDGET_WARNING_THRESHOLD = 0.8;

    /**
     * 延迟释放队列最大大小
     * <p>延迟销毁的 Vulkan 资源最大数量
     */
    public static final int MAX_DEFERRED_DESTRUCTION_QUEUE_SIZE = 256;

    // ==================== 日志和调试常量 ====================

    /**
     * 性能统计采样窗口大小（帧数）
     */
    public static final int PERF_STATS_WINDOW_SIZE = 120;

    /**
     * 调试信息刷新间隔（毫秒）
     */
    public static final long DEBUG_REFRESH_INTERVAL_MS = 1000;

    // ==================== Vulkan 相关常量 ====================

    /**
     * Shader Stage: Compute
     */
    public static final int VK_SHADER_STAGE_COMPUTE_BIT = 0x00000020;

    /**
     * Buffer Usage: Storage Buffer
     */
    public static final int VK_BUFFER_USAGE_STORAGE_BUFFER_BIT = 0x00000040;

    /**
     * Buffer Usage: Transfer Destination
     */
    public static final int VK_BUFFER_USAGE_TRANSFER_DST_BIT = 0x00000080;

    /**
     * Buffer Usage: Uniform Buffer
     */
    public static final int VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT = 0x00000010;

    /**
     * Memory Property: Host Visible
     */
    public static final int VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 0x00000020;

    /**
     * Memory Property: Host Coherent
     */
    public static final int VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x00000040;

    /**
     * Push Constant 最大大小（字节）
     * <p>Vulkan 规范要求至少支持 128 字节
     */
    public static final int PUSH_CONSTANT_MAX_SIZE = 128;

    /**
     * Descriptor Set 绑定数量上限
     * <p>Compute Shader 使用的 SSBO/UBO 绑定数量
     */
    public static final int DESCRIPTOR_BINDING_COUNT = 4;

    // ==================== 配置默认值 ====================

    /**
     * 默认 Streamline SDK 子目录名
     */
    public static final String DEFAULT_STREAMLINE_SDK_SUBDIR = "streamline-sdk";

    /**
     * 默认配置文件名
     */
    public static final String DEFAULT_CONFIG_FILENAME = "renderium.properties";

    /**
     * 默认 LOD 层级数量
     */
    public static final int DEFAULT_LOD_LEVELS = 4;

    /**
     * 默认渲染距离（区块数）
     */
    public static final int DEFAULT_RENDER_DISTANCE = 12;

    /**
     * 最大渲染距离（区块数）
     */
    public static final int MAX_RENDER_DISTANCE = 32;
}
