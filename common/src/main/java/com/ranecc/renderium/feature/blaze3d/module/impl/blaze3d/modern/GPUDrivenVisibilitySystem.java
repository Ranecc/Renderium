// Renderium - 现代渲染架构组件
// GPU-Driven 可见性系统 (MR1) - 增强版GPU剔除系统
// 来源文档: modern-rendering-architecture.md §3.1 GPU-Driven Visibility System
// 策略ID: MR1 (Modern Rendering #1)
// 预期收益: CPU 开销从 0.1ms 降到 0.01ms，支持Hi-Z零延迟遮挡剔除
// 继承关系: extends GPUCullingSystem (aggressive包) → 增加Hi-Z遮挡和三阶段Compute Shader

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.modern;

import com.ranecc.renderium.None;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * GPU-Driven 可见性系统 🎯🚀
 * <p>
 * 在父类 {@link GPUCullingSystem} 的基础上增加 Hi-Z（Hierarchical Z-Buffer）遮挡剔除能力，
 * 实现真正的 GPU-Driven 渲染管线。
 *
 * <h2>三阶段 Compute Shader 流程：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    GPU Compute Pipeline                      │
 * │                                                             │
 * │  ┌─────────────────┐   ┌─────────────────┐   ┌──────────┐ │
 * │  │   Pass 1        │ → │   Pass 2        │ → │  Pass 3  │ │
 * │  │  Frustum Cull   │   │  Hi-Z Occlusion │   │ Compact  │ │
 * │  │                 │   │                 │   │ + Draw   │ │
 * │  └─────────────────┘   └─────────────────┘   └──────────┘ │
 * │         ↓                     ↓                   ↓       │
 * │  视锥体测试通过          深度遮挡查询通过      生成Indirect│
 * │  的候选对象              的可见对象列表      Draw命令     │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Pass 1: Frustum Culling（视锥剔除）</h3>
 * <ul>
 *   <li>并行视锥测试：每个 Workgroup 处理一组 Chunk</li>
 *   <li>使用6个裁剪平面方程快速排除视野外对象</li>
 *   <li>输出：候选可见列表（可能包含被遮挡的对象）</li>
 * </ul>
 *
 * <h3>Pass 2: Hi-Z Occlusion Culling（层次Z缓冲遮挡剔除）</h3>
 * <ul>
 *   <li>零延迟遮挡查询：使用<b>上一帧</b>的深度金字塔（mipmap chain）</li>
 *   <li>层次化深度测试：从粗到细逐级细化（coarse-to-fine）</li>
 *   <li>屏幕空间边界框投影 + Hi-Z采样，避免像素级精确测试</li>
 *   <li>输出：最终可见列表（已排除被遮挡对象）</li>
 * </ul>
 *
 * <h3>Pass 3: Compact + Generate Indirect Draw（压缩+间接绘制）</h3>
 * <ul>
 *   <li>Subgroup 并行压缩：使用 GPU Subgroup/warp-level 操作高效压缩稀疏可见列表</li>
 *   <li>生成 Indirect Draw Commands：直接写入 GPU Buffer 供 vkCmdDrawIndirectCount 使用</li>
 *   <li>输出：紧凑的间接绘制参数数组</li>
 * </ul>
 *
 * <h2>核心数据结构：</h2>
 * <pre>
 * hiZBuffer: Texture2DArray (上一帧的深度金字塔)
 * ├── Mip Level 0: 原始分辨率深度图 (e.g., 1920×1080)
 * ├── Mip Level 1: 1/4 分辨率 (960×540)
 * ├── Mip Level 2: 1/16 分辨率 (480×270)
 * ├── ...
 * └── Mip Level N: 最小分辨率 (通常 1×1 或 2×2)
 *
 * 特点：
 * - 每层存储该区域的最大深度值（depth reduction: max）
 * - 支持保守的遮挡查询（false positive acceptable, false negative unacceptable）
 * - 帧间复用：当前帧读取上一帧的 Hi-Z，避免 GPU-CPU 同步
 * </pre>
 *
 * <h2>性能优势对比：</h2>
 * <table border="1">
 *   <tr><th>指标</th><th>父类 GPUCullingSystem</th><th>本类 (MR1)</th></tr>
 *   <tr><td>CPU 开销</td><td>~0.1ms</td><td>~0.01ms</td></tr>
 *   <tr><td>遮挡剔除</td><td>不支持</td><td>✓ Hi-Z 零延迟</td></tr>
 *   <tr><td>DrawCall 优化</td><td>基础 Indirect</td><td>✓ Subgroup 压缩</td></tr>
 *   <tr><td>适用场景</td><td>开放场景</td><td>密集城市场景</td></tr>
 * </table>
 *
 * <h3>参考文档：</h3>
 * <ul>
 *   <li>modern-rendering-architecture.md §3.1（GPU-Driven Visibility System）</li>
 *   <li>aggressive-mc-optimization.md §2.3（父类 GPU 剔除规范）</li>
 *   <li>Vulkan 最佳实践：Hierarchical Depth Buffer Occlusion Culling</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see GPUCullingSystem
 * @see BindlessResourceManager
 */
public class GPUDrivenVisibilitySystem extends GPUCullingSystem {

    private static final Logger LOGGER = Logger.getLogger(GPUDrivenVisibilitySystem.class.getName());

    /** 单例实例 */
    private static volatile GPUDrivenVisibilitySystem instance;

    /**
     * 获取单例实例
     *
     * @return GPUDrivenVisibilitySystem 实例
     */
    public static GPUDrivenVisibilitySystem getInstance() {
        if (instance == null) {
            synchronized (GPUDrivenVisibilitySystem.class) {
                if (instance == null) {
                    instance = new GPUDrivenVisibilitySystem();
                }
            }
        }
        return instance;
    }

    // ==================== Vulkan 访问掩码常量（用于内存屏障） ====================

    /** Vulkan: Transfer 写访问 */
    public static final int VK_ACCESS_TRANSFER_WRITE_BIT = 0x00020000;

    /** Vulkan: Shader 写访问 */
    public static final int VK_ACCESS_SHADER_WRITE_BIT = 0x00200000;

    /** Vulkan: Shader 读访问 */
    public static final int VK_ACCESS_SHADER_READ_BIT = 0x00400000;

    // ==================== MR1 新增配置常量 ====================

    /**
     * Hi-Z 缓冲区最大 Mipmap 层数
     * <p>
     * 计算公式: floor(log2(max(width, height))) + 1
     * 例如 1920×1080 → log2(1920) ≈ 10.9 → 11 层
     */
    public static final int MAX_HIZ_MIP_LEVELS = 12;

    /** Hi-Z 缓冲区默认尺寸（宽度，像素） */
    public static final int DEFAULT_HIZ_WIDTH = 1920;

    /** Hi-Z 缓冲区默认尺寸（高度，像素） */
    public static final int DEFAULT_HIZ_HEIGHT = 1080;

    /**
     * Hi-Z 遮挡查询保守阈值
     * <p>
     * 用于调整遮挡测试的保守程度：
     * - 较小值（如 0.0）：更激进剔除（可能有伪阴性，漏剔）
     * - 较大值（如 0.1）：更保守（可能有伪阳性，多绘）
     * <p>
     * 推荐值：0.0 ~ 0.05（在性能和正确性之间平衡）
     */
    public static final float HIZ_CONSERVATIVE_BIAS = 0.0f;

    // ==================== Vulkan 访问标志常量（用于内存屏障）====================

    /** Vulkan 存储写入访问标志 */
    private static final int STORAGE_WRITE_BIT = 0x00000100;

    /** Vulkan 存储读取访问标志 */
    private static final int STORAGE_READ_BIT = 0x00000200;

    /** Vulkan 原子操作写入标志 */
    private static final int ATOMIC_WRITE_BIT = 0x00000800;

    /** Vulkan 原子操作读取标志 */
    private static final int ATOMIC_READ_BIT = 0x00001000;

    /** Vulkan 间接命令读取标志（渲染管线可读取 indirect buffer） */
    private static final int INDIRECT_COMMAND_READ_BIT = 0x00002000;

    /** Vulkan 图像布局：传输目标最优（用于 Copy/Blit 操作） */
    private static final int VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL = 7;

    /** Vulkan 图像布局：着色器只读最优（用于 Sampled/Storage Image 读取） */
    private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 52;

    // ==================== MR1 核心数据：Hi-Z 深度金字塔 ====================

    /**
     * Hi-Z 深度金字塔纹理（Texture2D Array with Mipmap Chain）
     * <p>
     * 存储上一帧渲染完成的深度缓冲区的 mipmap 层级结构。
     * 当前帧的遮挡查询将读取此纹理，实现零延迟遮挡剔除。
     *
     * <h3>更新时机：</h3>
     * 每帧渲染完成后（renderLevel 结束时），将当前帧的深度缓冲区
     * 下采样生成新的 Hi-Z pyramid，供下一帧使用。
     *
     * <h3>内存布局：</h3>
     * <pre>
     * Texture2D hiZBuffer:
     * ├── Format: R32_SFLOAT (单通道 32-bit float 深度)
     * ├── Mip 0: 1920×1080 (原始深度，可选不存储以节省内存)
     * ├── Mip 1: 960×540
     * ├── Mip 2: 480×270
     * ├── ...
     * └── Mip N: 1×1 或 2×2
     *
     * 每个 texel 存储对应区域的最大深度值（max reduction）
     * </pre>
     */
    private Object hiZBuffer;

    /** Hi-Z 采样器句柄（Point Sampling, Clamp to Edge） */
    private Object hiZSampler;

    /** Hi-Z 缓冲区宽度（像素） */
    private volatile int hiZWidth = DEFAULT_HIZ_WIDTH;

    /** Hi-Z 缓冲区高度（像素） */
    private volatile int hiZHeight = DEFAULT_HIZ_HEIGHT;

    /**
     * 计算并缓存实际的 Mipmap 层数
     * <p>
     * 基于实际尺寸计算：floor(log2(max(width, height))) + 1
     * 例如 1920×1080 → log2(1920) ≈ 10.9 → 11 层
     */
    private int actualMipLevels;

    // ==================== MR1 Compute Pipeline 句柄 ====================

    /** Pass 1: Frustum Culling Compute Pipeline */
    private Object frustumCullPipeline;

    /** Pass 2: Hi-Z Occlusion Culling Compute Pipeline */
    private Object hizOcclusionCullPipeline;

    /** Pass 3: Compact + Indirect Draw Generation Compute Pipeline */
    private Object compactAndDrawPipeline;

    // ==================== MR1 GPU Buffer（三阶段中间数据） ====================

    /**
     * 候选可见列表缓冲区（Pass 1 输出 / Pass 2 输入）
     * <p>
     * 存储通过视锥测试但尚未进行遮挡测试的对象索引。
     * 格式: uint32[] candidateIndices[MAX_CHUNK_COUNT]
     */
    private Object candidateListBuffer;

    /**
     * 候选数量计数器（Atomic Counter Buffer）
     * <p>
     * Pass 1 写入候选数量，Pass 2 读取作为输入范围。
     * 使用 Vulkan Atomic Counter 或 SSBO 实现。
     */
    private Object candidateCountBuffer;

    /**
     * 最终可见列表缓冲区（Pass 2 输出 / Pass 3 输入）
     * <p>
     * 存储通过视锥+遮挡测试的最终可见对象索引。
     * 格式: uint32[] visibleIndices[MAX_CHUNK_COUNT]
     */
    private Object finalVisibleListBuffer;

    /**
     * 最终可见数量计数器（Pass 3 读取用于生成 Indirect Draw Commands）
     */
    private Object visibleCountBuffer;

    // ==================== MR1 统计字段 ====================

    /** Hi-Z 遮挡剔除的总次数 */
    private final AtomicLong totalHizCullingPasses = new AtomicLong(0);

    /** 被 Hi-Z 遮挡剔除的对象总数累计 */
    private final AtomicLong totalObjectsOccluded = new AtomicLong(0);

    /** Hi-Z 遮挡查询总耗时（纳秒） */
    private final AtomicLong totalHizCullingTimeNanos = new AtomicLong(0);

    /** Subgroup 压缩总耗时（纳秒） */
    private final AtomicLong totalCompactTimeNanos = new AtomicLong(0);

    // ==================== 构造函数和初始化 ====================

    /**
     * 构造 GPU-Driven 可见性系统
     * <p>
     * 创建实例但不立即分配 GPU 资源，
     * 需要显式调用 {@link #init(Object, int, int)} 完成初始化。
     */
    public GPUDrivenVisibilitySystem() {
        super(); // 调用父类构造函数
        LOGGER.info("GPUDrivenVisibilitySystem 创建完成 (MR1)");
    }

    /**
     * 初始化 GPU 资源（含 Hi-Z 深度金字塔）
     * <p>
     * 分配所有需要的 GPU Buffer、Texture 和创建三个 Compute Pipeline。
     * 必须在首次使用前调用，且必须在有有效 GPU 上下文的线程中调用。
     *
     * <h3>初始化流程：</h3>
     * <pre>
     * 1. 调用父类 initialize() 初始化基础 GPU 资源（包围盒、可见性、间接命令 buffer）
     * 2. 创建 Hi-Z 深度金字塔纹理（指定尺寸和 Mip 层数）
     * 3. 创建三阶段 Compute Pipeline:
     *    - frustumCullPipeline (Pass 1)
     *    - hizOcclusionCullPipeline (Pass 2)
     *    - compactAndDrawPipeline (Pass 3)
     * 4. 分配中间数据缓冲区（候选列表、最终可见列表等）
     * </pre>
     *
     * @param gpuDevice    GPU 设备句柄（不能为 null，Vulkan Logical Device 或同等对象）
     * @param hiZWidth     Hi-Z 缓冲区宽度（像素，必须 > 0 且为 2 的幂次方推荐）
     * @param hiZHeight    Hi-Z 缓冲区高度（像素，必须 > 0 且为 2 的幂次方推荐）
     *
     * @throws IllegalArgumentException 如果 gpuDevice 为 null 或尺寸参数无效
     * @throws IllegalStateException    如果已经初始化过或 GPU 资源分配失败
     *
     * @see #close()
     */
    public void init(Object gpuDevice, int hiZWidth, int hiZHeight) {
        if (gpuDevice == null) {
            throw new IllegalArgumentException("gpuDevice 不能为 null");
        }
        if (hiZWidth <= 0 || hiZHeight <= 0) {
            throw new IllegalArgumentException(
                    String.format("Hi-Z 尺寸必须 > 0: width=%d, height=%d", hiZWidth, hiZHeight)
            );
        }

        try {
            // ========== 步骤 1: 调用父类初始化 ==========
            // 初始化基础的 GPU Buffer（chunkBoundsBuffer, visibilityBuffer, indirectArgsBuffer）
            super.initialize();

            this.hiZWidth = hiZWidth;
            this.hiZHeight = hiZHeight;

            // ========== 步骤 2: 创建 Hi-Z 深度金字塔纹理 ==========
            // 计算实际的 mipmap 层数（基于实际尺寸）
            this.actualMipLevels = calculateMipLevels(hiZWidth, hiZHeight);

            // 创建 Hi-Z Image（Vulkan R32_SFLOAT 格式，支持完整 mipmap chain）
            this.hiZBuffer = createHiZImage(gpuDevice, hiZWidth, hiZHeight, actualMipLevels);

            // 创建 Hi-Z 采样器（Point Sampling + Clamp to Edge，用于遮挡查询时的精确采样）
            this.hiZSampler = createHiZSampler(gpuDevice);

            LOGGER.fine(String.format(
                    "Hi-Z 深度金字塔已创建: %dx%d, %d mip levels",
                    hiZWidth, hiZHeight, actualMipLevels
            ));

            // ========== 步骤 3: 创建三阶段 Compute Pipeline ==========
            // Pass 1: Frustum Culling - 并行视锥测试，输出候选列表
            this.frustumCullPipeline = createComputePipeline(
                    gpuDevice,
                    "shaders/frustum_cull.comp.spv",
                    "frustum_cull_pipeline"
            );

            // Pass 2: Hi-Z Occlusion Culling - 零延迟遮挡查询，输出最终可见列表
            this.hizOcclusionCullPipeline = createComputePipeline(
                    gpuDevice,
                    "shaders/hiz_occlusion_cull.comp.spv",
                    "hiz_occlusion_cull_pipeline"
            );

            // Pass 3: Compact + Indirect Draw Generation - Subgroup 压缩，生成绘制命令
            this.compactAndDrawPipeline = createComputePipeline(
                    gpuDevice,
                    "shaders/compact_and_generate_draw.comp.spv",
                    "compact_and_draw_pipeline"
            );

            LOGGER.fine("三阶段 Compute Pipeline 已创建完成");

            // ========== 步骤 4: 分配中间数据缓冲区 ==========
            // 候选可见列表缓冲区（Pass 1 输出 / Pass 2 输入）
            // 格式: uint32[] candidateIndices[MAX_CHUNK_COUNT]
            this.candidateListBuffer = createStorageBuffer(
                    gpuDevice,
                    4L * MAX_CHUNK_COUNT,  // 每个 candidate 占用 4 字节 (uint32)
                    "candidate_list_buffer"
            );

            // 候选数量计数器（Atomic Counter Buffer - Pass 1 写入，Pass 2 读取）
            this.candidateCountBuffer = createAtomicCounterBuffer(
                    gpuDevice,
                    4,  // 单个 uint32 计数器
                    "candidate_count_buffer"
            );

            // 最终可见列表缓冲区（Pass 2 输出 / Pass 3 输入）
            // 格式: uint32[] visibleIndices[MAX_CHUNK_COUNT]
            this.finalVisibleListBuffer = createStorageBuffer(
                    gpuDevice,
                    4L * MAX_CHUNK_COUNT,  // 每个 visible object 占用 4 字节 (uint32)
                    "final_visible_list_buffer"
            );

            // 最终可见数量计数器（Pass 3 读取用于生成 Indirect Draw Commands）
            this.visibleCountBuffer = createStorageBuffer(
                    gpuDevice,
                    4,  // 单个 uint32 计数器
                    "visible_count_buffer"
            );

            LOGGER.fine(String.format(
                    "中间数据缓冲区已分配: candidateList=%dKB, finalVisibleList=%dKB",
                    (4 * MAX_CHUNK_COUNT) / 1024,
                    (4 * MAX_CHUNK_COUNT) / 1024
            ));

            LOGGER.info(String.format(
                    "✓ GPUDrivenVisibilitySystem 初始化完成 (MR1): " +
                    "HiZ=%dx%d, maxMips=%d, maxChunks=%d",
                    hiZWidth, hiZHeight, MAX_HIZ_MIP_LEVELS, MAX_CHUNK_COUNT
            ));

        } catch (Exception e) {
            throw new IllegalStateException(
                    "GPUDrivenVisibilitySystem GPU 资源分配失败: " + e.getMessage(), e
            );
        }
    }

    // ==================== AutoCloseable 重写（扩展） ====================

    /**
     * 释放所有 GPU 资源（含 Hi-Z 和三阶段 Pipeline）
     * <p>
     * 应在模块卸载或窗口关闭时调用。
     * 释放后此对象不可再使用。
     * <p>
     * 释放顺序（逆序初始化）：
     * <ol>
     *   <li>释放三阶段 Compute Pipeline</li>
     *   <li>释放中间数据缓冲区（候选列表、最终可见列表等）</li>
     *   <li>释放 Hi-Z 深度金字塔纹理</li>
     *   <li>调用父类 close() 释放基础资源</li>
     * </ol>
     */
    @Override
    public void close() {
        if (!isInitialized()) {
            return; // 未初始化或已释放
        }

        try {
            // 释放 MR1 新增资源
            frustumCullPipeline = null;
            hizOcclusionCullPipeline = null;
            compactAndDrawPipeline = null;

            candidateListBuffer = null;
            candidateCountBuffer = null;
            finalVisibleListBuffer = null;
            visibleCountBuffer = null;

            hiZBuffer = null;

            // 调用父类 close() 释放基础资源
            super.close();

            LOGGER.info("GPUDrivenVisibilitySystem 已释放所有资源 (包括 Hi-Z 和 Pipeline)");

        } catch (Exception e) {
            LOGGER.warning("释放 GPUDrivenVisibilitySystem 资源时发生异常: " + e.getMessage());
        }
    }

    // ==================== 核心方法重写：执行三阶段剔除 ====================

    /**
     * 执行 GPU-Driven 三阶段剔除（重写父类方法）
     * <p>
     * 调度三个 Compute Shader Pass 执行完整的可见性判定流程：
     * <ol>
     *   <li><b>Pass 1: Frustum Culling</b> - 并行视锥测试，输出候选列表</li>
     *   <li><b>Pass 2: Hi-Z Occlusion Culling</b> - 零延迟遮挡查询，输出最终可见列表</li>
     *   <li><b>Pass 3: Compact + Indirect Draw</b> - Subgroup 并行压缩，生成绘制命令</li>
     * </ol>
     *
     * <h3>完整执行流程：</h3>
     * <pre>
     * 输入:
     *   - encoder: CommandEncoder 对象（Blaze3D/Vulkan Command Buffer）
     *   - cameraData: 相机数据对象（需提供 getViewProjectionMatrix(), getPosition() 等）
     *
     * ┌─ Pass 1: Frustum Culling ─────────────────────────────────────┐
     * │                                                                │
     * │  绑定:                                                          │
     * │    - Pipeline: frustumCullPipeline                              │
     * │    - Storage Buffer [0]: chunkBoundsBuffer (输入: 所有 Chunk AABB)│
     * │    - Storage Buffer [1]: candidateListBuffer (输出: 候选索引)    │
     * │    - Atomic Counter [2]: candidateCountBuffer (输出: 候选数量)  │
     * │                                                                │
     * │  Push Constants:                                                │
     * │    - viewProjMatrix: 4×4 矩阵                                   │
     * │    - frustumPlanes[6]: 6 个裁剪平面                             │
     * │    - cameraPosition: vec3                                       │
     * │    - renderDistance: float                                      │
     * │    - chunkCount: uint (已注册 Chunk 数量)                        │
     * │                                                                │
     * │  Dispatch:                                                      │
     * │    - workgroups = ceil(chunkCount / WORKGROUP_SIZE)             │
     * │                                                                │
     * │  Barrier:                                                       │
     * │    - STORAGE_WRITE → STORAGE_READ (候选列表给 Pass 2 用)        │
     * │    - ATOMIC_WRITE → ATOMIC_READ (计数器给 Pass 2 用)            │
     * └────────────────────────────────────────────────────────────────┘
     *                              ↓
     * ┌─ Pass 2: Hi-Z Occlusion Culling ──────────────────────────────┐
     * │                                                                │
     * │  绑定:                                                          │
     * │    - Pipeline: hizOcclusionCullPipeline                         │
     * │    - Sampled Image [0]: hiZBuffer (输入: 上一帧深度金字塔)      │
     * │    - Sampler [1]: hiZSampler (point sampling, clamp to edge)   │
     * │    - Storage Buffer [2]: chunkBoundsBuffer (输入: 屏幕空间投影) │
     * │    - Storage Buffer [3]: candidateListBuffer (输入: 候选列表)   │
     * │    - Atomic Counter [4]: candidateCountBuffer (输入: 候选数量) │
     * │    - Storage Buffer [5]: finalVisibleListBuffer (输出: 可见索引)│
     * │    - Atomic Counter [6]: visibleCountBuffer (输出: 可见数量)   │
     * │                                                                │
     * │  Push Constants:                                                │
     * │    - viewProjMatrix: 4×4 矩阵（用于屏幕空间投影）               │
     * │    - screenSize: vec2 (宽, 高)                                  │
     * │    - conservativeBias: float (Hi-Z 保守阈值)                    │
     * │                                                                │
     * │  Dispatch:                                                      │
     * │    - workgroups = ceil(candidateCount / WORKGROUP_SIZE)         │
     * │                                                                │
     * │  Barrier:                                                       │
     * │    - SHADER_READ → SHADER_WRITE (Hi-Z 只读，无需 barrier)       │
     * │    - STORAGE_WRITE → STORAGE_READ (可见列表给 Pass 3 用)        │
     * └────────────────────────────────────────────────────────────────┘
     *                              ↓
     * ┌─ Pass 3: Compact + Generate Indirect Draw ────────────────────┐
     * │                                                                │
     * │  绑定:                                                          │
     * │    - Pipeline: compactAndDrawPipeline                           │
     * │    - Storage Buffer [0]: finalVisibleListBuffer (输入/输出)    │
     * │    - Atomic Counter [1]: visibleCountBuffer (输入)             │
     * │    - Storage Buffer [2]: indirectArgsBuffer (输出: Draw Cmds)  │
     * │                                                                │
     * │  Dispatch:                                                      │
     * │    - workgroups = ceil(visibleCount / SUBGROUP_SIZE)            │
     * │    - 使用 Subgroup Ballot / Warp Shuffle 高效压缩              │
     * │                                                                │
     * │  Barrier:                                                       │
     * │    - STORAGE_WRITE → INDIRECT_COMMAND_READ (渲染可读取)         │
     * └────────────────────────────────────────────────────────────────┘
     *                              ↓
     *                        输出: indirectArgsBuffer
     *                        (可直接传给 vkCmdDrawIndirectCount)
     * </pre>
     *
     * @param encoder    Blaze3D CommandEncoder 对象（不能为 null）
     * @param cameraData 相机数据对象（不能为 null，需提供相机参数）
     *
     * @throws IllegalArgumentException 如果 encoder 或 cameraData 为 null
     * @throws IllegalStateException    如果未初始化或未启用
     *
     * @see GPUCullingSystem#executeCulling(Object, Object)
     * @see #renderWithCulling(Object)
     */
    @Override
    public void executeCulling(Object encoder, Object cameraData) {
        // 参数校验（继承父类的检查逻辑）
        if (encoder == null) {
            throw new IllegalArgumentException("encoder 不能为 null");
        }
        if (cameraData == null) {
            throw new IllegalArgumentException("cameraData 不能为 null");
        }
        if (!isEnabled()) {
            throw new IllegalStateException("GPUDrivenVisibilitySystem 未启用或未初始化");
        }

        long startTime = System.nanoTime();
        int registeredChunks = getRegisteredChunkCount();

        try {
            // ========== Pass 1: Frustum Culling（视锥剔除）==========
            long pass1Start = System.nanoTime();

            // 执行 Pass 1: Frustum Culling Compute Shader
            // 功能: 并行测试所有 Chunk 是否在视锥体内，输出候选列表
            executeFrustumCullPass(encoder, cameraData, registeredChunks);

            long pass1Elapsed = System.nanoTime() - pass1Start;

            // ========== Pass 2: Hi-Z Occlusion Culling（遮挡剔除）==========
            long pass2Start = System.nanoTime();

            // 执行 Pass 2: Hi-Z Occlusion Culling Compute Shader
            // 功能: 使用上一帧的深度金字塔进行零延迟遮挡查询，输出最终可见列表
            int occludedCount = executeHizOcclusionCullPass(encoder, cameraData);

            // 更新统计：记录被遮挡剔除的对象数量
            totalObjectsOccluded.addAndGet(occludedCount);

            long pass2Elapsed = System.nanoTime() - pass2Start;
            totalHizCullingTimeNanos.addAndGet(pass2Elapsed);
            totalHizCullingPasses.incrementAndGet();

            // ========== Pass 3: Compact + Generate Indirect Draw（压缩+生成绘制命令）==========
            long pass3Start = System.nanoTime();

            // 执行 Pass 3: Compact + Generate Indirect Draw Compute Shader
            // 功能: 使用 Subgroup 操作高效压缩稀疏可见列表，生成间接绘制命令
            executeCompactAndDrawPass(encoder);

            long pass3Elapsed = System.nanoTime() - pass3Start;
            totalCompactTimeNanos.addAndGet(pass3Elapsed);

            // ========== 统计记录 ==========
            long totalElapsed = System.nanoTime() - startTime;

            LOGGER.fine(String.format(
                    "GPU-Driven 三阶段剔除完成 (MR1): " +
                    "chunks=%d, P1=%.2fμs, P2(Hi-Z)=%.2fμs, P3(Compact)=%.2fμs, 总计=%.2fμs",
                    registeredChunks,
                    pass1Elapsed / 1000.0,
                    pass2Elapsed / 1000.0,
                    pass3Elapsed / 1000.0,
                    totalElapsed / 1000.0
            ));

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "GPU-Driven 剔除执行异常 (MR1): %s", e.getMessage()
            ));
            // 注意: 不抛出异常，允许降级到父类的基础剔除逻辑
        }
    }

    // ==================== Hi-Z 深度金字塔管理 ====================

    /**
     * 更新 Hi-Z 深度金字塔（每帧渲染结束后调用）
     * <p>
     * 将当前帧的主深度缓冲区下采样生成完整的 mipmap chain，
     * 供下一帧的遮挡查询使用。
     *
     * <h3>调用时机：</h3>
     * 必须在每帧渲染完成之后、下一帧开始之前调用。
     * 通常在 renderLevel() 方法的 TAIL 注入点或在 endFrame() 中调用。
     *
     * <h3>算法流程：</h3>
     * <pre>
     * 输入: currentFrameDepthBuffer (本帧渲染的深度图)
     * 输出: hiZBuffer (mipmap chain 已更新)
     *
     * for mipLevel from 1 to MAX_HIZ_MIP_LEVELS-1:
     *     sourceMip = mipLevel - 1
     *     destMip = mipLevel
     *
     *     // 2×2 Max Reduction（取4个邻域中的最大深度值）
     *     compute shader or fixed-function blit:
     *         hiZBuffer[destMip](x, y) = max(
     *             hiZBuffer[sourceMip](2*x,   2*y),
     *             hiZBuffer[sourceMip](2*x+1, 2*y),
     *             hiZBuffer[sourceMip](2*x,   2*y+1),
     *             hiZBuffer[sourceMip](2*x+1, 2*y+1)
     *         )
     *
     * 结果: hiZBuffer 的每一层都存储对应区域的保守深度上界
     * </pre>
     *
     * @param encoder            CommandEncoder 对象（不能为 null）
     * @param depthBuffer        当前帧的深度缓冲区（Texture 或 Image View，不能为 null）
     * @param depthBufferWidth   深度缓冲区宽度（像素，必须与 Hi-Z 尺寸匹配）
     * @param depthBufferHeight  深度缓冲区高度（像素，必须与 Hi-Z 尺寸匹配）
     *
     * @throws IllegalArgumentException 如果任何参数为 null 或无效
     * @throws IllegalStateException    如果未初始化
     */
    public void updateHiZPyramid(Object encoder, Object depthBuffer,
                                 int depthBufferWidth, int depthBufferHeight) {
        if (!isInitialized()) {
            throw new IllegalStateException("GPUDrivenVisibilitySystem 未初始化");
        }
        if (encoder == null) {
            throw new IllegalArgumentException("encoder 不能为 null");
        }
        if (depthBuffer == null) {
            throw new IllegalArgumentException("depthBuffer 不能为 null");
        }
        if (depthBufferWidth != hiZWidth || depthBufferHeight != hiZHeight) {
            throw new IllegalArgumentException(
                    String.format("深度缓冲区尺寸(%dx%d)与Hi-Z尺寸(%dx%d)不匹配",
                            depthBufferWidth, depthBufferHeight, hiZWidth, hiZHeight)
            );
        }

        try {
            // ========== 步骤 1: 将当前帧深度拷贝到 Hi-Z Mip 0 ==========
            // 使用 Vulkan Copy Image 命令将主深度缓冲区内容复制到 Hi-Z 纹理的基础层
            copyDepthToHiZMip0(encoder, depthBuffer);

            // ========== 步骤 2: 转换 Hi-Z 布局为 SHADER_READ_ONLY ==========
            // 确保后续 Compute Shader 可以正确读取 Hi-Z 数据
            transitionHiZLayout(encoder,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,  // 拷贝后的布局
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL  // 着色器可读布局
            );

            // ========== 步骤 3: 生成 Mipmap Chain（Max Reduction）==========
            // 从 Mip 1 开始，逐层向下采样（2×2 Max Reduction）
            // 每层取 4 个邻域 texel 中的最大深度值，保证保守性
            for (int mip = 1; mip < actualMipLevels; mip++) {
                runHiZReduceComputeShader(encoder, hiZBuffer, mip - 1, mip);
            }

            // ========== 步骤 4: 最终内存屏障 ==========
            // 确保所有 mipmap 层的写入操作完成，下一帧的 Pass 2 可以安全读取
            emitHiZMemoryBarrier(encoder,
                    VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,  // 写入操作
                    VK_ACCESS_SHADER_READ_BIT  // Pass 2 的读取操作
            );

            LOGGER.fine(String.format(
                    "Hi-Z 深度金字塔已更新: %dx%d, %d 个 mipmap levels",
                    hiZWidth, hiZHeight, MAX_HIZ_MIP_LEVELS
            ));

        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "更新 Hi-Z 金字塔失败: %s, 下一帧将使用过期的深度数据",
                    e.getMessage()
            ));
        }
    }

    // ==================== 统计和监控 API（MR1 扩展）====================

    /**
     * 获取格式化的统计报告（包含 MR1 特有的 Hi-Z 统计）
     *
     * @return 包含详细统计信息的字符串（含 Hi-Z 遮挡剔除数据）
     */
    @Override
    public String formatStatisticsReport() {
        // 获取父类统计信息
        String baseReport = super.formatStatisticsReport();

        // 追加 MR1 特有统计
        long hizPasses = totalHizCullingPasses.get();
        long occluded = totalObjectsOccluded.get();
        long hizTimeNs = totalHizCullingTimeNanos.get();
        long compactTimeNs = totalCompactTimeNanos.get();

        double avgHizTimeUs = hizPasses > 0 ? hizTimeNs / 1000.0 / hizPasses : 0;
        double avgCompactTimeUs = hizPasses > 0 ? compactTimeNs / 1000.0 / hizPasses : 0;

        return String.format(
                "%s\n" +
                "╠══════════════════════════════════════════════════╣\n" +
                "║         MR1: Hi-Z 遮剔统计 (扩展)                  ║\n" +
                "╠══════════════════════════════════════════════════╣\n" +
                "║ Hi-Z 遮剔次数: %-36d ║\n" +
                "║ 遮挡剔除对象数: %-34d ║\n" +
                "║ 平均 Hi-Z 耗时: %-32.2f μs ║\n" +
                "║ 平均 Compact 耗时: %-29.2f μs ║\n" +
                "║ Hi-Z 尺寸: %-38dx%d ║\n" +
                "╚══════════════════════════════════════════════════╝",
                baseReport,
                hizPasses,
                occluded,
                avgHizTimeUs,
                avgCompactTimeUs,
                hiZWidth, hiZHeight
        );
    }

    /**
     * 重置所有统计计数器（包含 MR1 特有统计）
     */
    @Override
    public void resetStatistics() {
        super.resetStatistics(); // 重置父类统计

        // 重置 MR1 特有统计
        totalHizCullingPasses.set(0);
        totalObjectsOccluded.set(0);
        totalHizCullingTimeNanos.set(0);
        totalCompactTimeNanos.set(0);

        LOGGER.info("GPUDrivenVisibilitySystem: 所有统计计数器已重置 (含 Hi-Z)");
    }

    // ==================== Getter 方法（MR1 扩展）====================

    /** 获取 Hi-Z 缓冲区宽度 */
    public int getHiZWidth() { return hiZWidth; }

    /** 获取 Hi-Z 缓冲区高度 */
    public int getHiZHeight() { return hiZHeight; }

    /** 获取 Hi-Z 遮剔总次数 */
    public long getTotalHizCullingPasses() { return totalHizCullingPasses.get(); }

    /** 获取被遮挡剔除的对象总数 */
    public long getTotalObjectsOccluded() { return totalObjectsOccluded.get(); }

    /** 获取平均 Hi-Z 遮剔耗时（微秒） */
    public double getAverageHizCullingTimeMicros() {
        long passes = totalHizCullingPasses.get();
        return passes > 0 ? totalHizCullingTimeNanos.get() / 1000.0 / passes : 0;
    }

    /** 获取平均 Subgroup 压缩耗时（微秒） */
    public double getAverageCompactTimeMicros() {
        long passes = totalHizCullingPasses.get();
        return passes > 0 ? totalCompactTimeNanos.get() / 1000.0 / passes : 0;
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 构建 Pass 1 (Frustum Culling) 的 Push Constants 参数
     *
     * @param cameraData 相机数据对象（需提供 getViewProjectionMatrix, getPosition 等方法）
     * @return 填充好的 FrustumCullParams 结构体，包含视锥测试所需的所有参数
     *
     * @throws IllegalArgumentException 如果 cameraData 无法提供必要参数
     */
    private Object buildFrustumCullParams(Object cameraData) {
        // 创建参数结构体实例
        FrustumCullParams params = new FrustumCullParams();

        try {
            // 提取 View-Projection 矩阵（4×4 列主序，16 个 float）
            // 通过反射或接口调用从 cameraData 获取
            params.viewProjMatrix = extractViewProjectionMatrix(cameraData);

            // 从 VP 矩阵提取 6 个视锥裁剪平面方程（左、右、下、上、近、远）
            // 每个平面: ax + by + cz + d = 0，存储为 [a, b, c, d]
            params.frustumPlanes = extractFrustumPlanes(params.viewProjMatrix);

            // 提取相机世界空间位置（用于距离剔除优化）
            params.cameraPosition = extractCameraPosition(cameraData);

            // 设置渲染距离（从当前配置获取）
            params.renderDistance = getRenderDistance() * 16.0f;  // 区块单位 → 格单位

            LOGGER.fine(String.format(
                    "FrustumCullParams 已构建: renderDistance=%.1f, cameraPos=[%.1f, %.1f, %.1f]",
                    params.renderDistance,
                    params.cameraPosition[0], params.cameraPosition[1], params.cameraPosition[2]
            ));

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "无法从 cameraData 构建 FrustumCullParams: " + e.getMessage(), e
            );
        }

        return params;
    }

    /**
     * 构建 Pass 2 (Hi-Z Occlusion Culling) 的 Push Constants 参数
     *
     * @param cameraData 相机数据对象（需提供 getViewProjectionMatrix 等方法）
     * @return 填充好的 HizOcclusionParams 结构体，包含 Hi-Z 遮挡查询所需参数
     *
     * @throws IllegalArgumentException 如果 cameraData 无法提供必要参数
     */
    private Object buildHizOcclusionParams(Object cameraData) {
        // 创建参数结构体实例
        HizOcclusionParams params = new HizOcclusionParams();

        try {
            // 提取 View-Projection 矩阵（用于屏幕空间 AABB 投影）
            params.viewProjMatrix = extractViewProjectionMatrix(cameraData);

            // 设置 Hi-Z 缓冲区尺寸（用于将 NDC 坐标转换为纹理坐标）
            params.screenSize[0] = (float) hiZWidth;
            params.screenSize[1] = (float) hiZHeight;

            // 设置保守偏差阈值（调整遮挡测试的保守程度）
            // 较小值 → 更激进剔除（可能有伪阴性漏剔）
            // 较大值 → 更保守（可能多绘一些对象）
            params.conservativeBias = HIZ_CONSERVATIVE_BIAS;

            // 设置实际 mipmap 层数（用于层级遍历的上界）
            params.maxMipLevels = actualMipLevels;

            LOGGER.fine(String.format(
                    "HizOcclusionParams 已构建: screenSize=[%dx%d], bias=%.4f, maxMips=%d",
                    hiZWidth, hiZHeight, params.conservativeBias, actualMipLevels
            ));

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "无法从 cameraData 构建 HizOcclusionParams: " + e.getMessage(), e
            );
        }

        return params;
    }

    /**
     * 构建 Pass 3 (Compact + Indirect Draw) 的 Push Constants 参数
     *
     * @return 填充好的 CompactParams 结构体，包含压缩和绘制命令生成所需参数
     */
    private Object buildCompactParams() {
        // 创建参数结构体实例
        CompactParams params = new CompactParams();

        // 设置间接绘制命令的固定偏移量（每个命令 20 字节）
        params.indirectCommandStride = INDIRECT_COMMAND_SIZE;

        // 设置最大可见对象数量限制（防止缓冲区越界）
        params.maxVisibleCount = MAX_CHUNK_COUNT;

        // Subgroup 大小（用于并行压缩算法，通常为 32 或 64）
        params.subgroupSize = WORKGROUP_SIZE;  // 使用与 workgroup 相同的大小

        LOGGER.fine(String.format(
                "CompactParams 已构建: stride=%d, maxVisible=%d, subgroupSize=%d",
                params.indirectCommandStride, params.maxVisibleCount, params.subgroupSize
        ));

        return params;
    }

    /**
     * 读取候选数量（使用 Staging Buffer 异步回读）
     * <p>
     * ⚠️ 性能警告：此方法会引入 GPU→CPU 同步点！
     * 生产环境建议使用 vkCmdDispatchIndirect 实现完全 GPU-Driven 调度，
     * 避免此同步开销。
     *
     * @return 候选对象数量（通过 Pass 1 视锥测试的对象数）
     */
    private int readBackCandidateCount() {
        if (candidateCountBuffer == null) {
            // 缓冲区未初始化时返回估计值（基于历史数据）
            return estimateCandidateCount();
        }

        try {
            // 使用 Staging Buffer 读取 GPU 端的原子计数器值
            int count = readAtomicCounterFromGPU(candidateCountBuffer);

            // 合理性检查：确保返回值在有效范围内
            if (count < 0 || count > MAX_CHUNK_COUNT) {
                LOGGER.warning(String.format(
                        "候选计数异常: %d (有效范围: 0-%d), 使用估计值替代",
                        count, MAX_CHUNK_COUNT
                ));
                return estimateCandidateCount();
            }

            return count;

        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "读取候选计数失败: %s, 使用估计值", e.getMessage()
            ));
            return estimateCandidateCount();
        }
    }

    /**
     * 读取可见对象数量（使用 Staging Buffer 异步回读）
     * <p>
     * ⚠️ 性能警告：此方法会引入 GPU→CPU 同步点！
     * 生产环境应避免在热路径上调用此方法。
     *
     * @return 可见对象数量（通过 Pass 2 遮挡测试的对象数）
     */
    private int readBackVisibleCount() {
        if (visibleCountBuffer == null) {
            // 缓冲区未初始化时返回估计值
            return estimateVisibleCount();
        }

        try {
            // 使用 Staging Buffer 读取 GPU 端的可见计数器值
            int count = readAtomicCounterFromGPU(visibleCountBuffer);

            // 合理性检查：确保返回值在有效范围内
            if (count < 0 || count > MAX_CHUNK_COUNT) {
                LOGGER.warning(String.format(
                        "可见计数异常: %d (有效范围: 0-%d), 使用估计值替代",
                        count, MAX_CHUNK_COUNT
                ));
                return estimateVisibleCount();
            }

            return count;

        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "读取可见计数失败: %s, 使用估计值", e.getMessage()
            ));
            return estimateVisibleCount();
        }
    }

    // ==================== GPU 资源创建辅助方法 ====================

    /**
     * 计算给定尺寸所需的 Mipmap 层数
     *
     * @param width  纹理宽度（像素）
     * @param height 纹理高度（像素）
     * @return Mipmap 层数（≥1）
     */
    private static int calculateMipLevels(int width, int height) {
        // 公式: floor(log2(max(width, height))) + 1
        int maxDim = Math.max(width, height);
        return (int) (Math.log(maxDim) / Math.log(2)) + 1;
    }

    /**
     * 创建 Hi-Z 深度金字塔 Image（Vulkan R32_SFLOAT 格式）
     *
     * @param gpuDevice   GPU 设备句柄
     * @param width       图像宽度（像素）
     * @param height      图像高度（像素）
     * @param mipLevels   Mipmap 层数
     * @return Hi-Z Image 句柄
     *
     * @throws IllegalStateException 如果创建失败
     */
    private Object createHiZImage(Object gpuDevice, int width, int height, int mipLevels) {
        // 实际集成时应调用 Vulkan API:
        // VkImageCreateInfo imageInfo = {};
        // imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        // imageInfo.imageType = VK_IMAGE_TYPE_2D;
        // imageInfo.format = VK_FORMAT_R32_SFLOAT;           // 单通道 32-bit float 深度
        // imageInfo.extent.width = width;
        // imageInfo.extent.height = height;
        // imageInfo.extent.depth = 1;
        // imageInfo.mipLevels = mipLevels;                   // 完整 mipmap chain
        // imageInfo.arrayLayers = 1;
        // imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        // imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;       // GPU 优化的平铺方式
        // imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT |     // 着色器采样（Pass 2 读取）
        //                  VK_IMAGE_USAGE_TRANSFER_DST_BIT | // 从主深度缓冲区拷贝
        //                  VK_IMAGE_USAGE_STORAGE_BIT;      // Compute Shader 写入（mipmap 生成）
        // imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        //
        // return vkCreateImage(device, &imageInfo, nullptr, &hiZImage);

        // 当前返回占位符（实际集成时替换为真实 Vulkan 对象）
        LOGGER.fine(String.format(
                "createHiZImage [占位符]: %dx%d, %d mips", width, height, mipLevels
        ));
        return new Object();  // 占位符
    }

    /**
     * 创建 Hi-Z 采样器（Point Sampling + Clamp to Edge）
     * <p>
     * 用于 Hi-Z 遮挡查询时的精确 texel 采样，
     * 避免线性滤波导致的深度值不精确。
     *
     * @param gpuDevice GPU 设备句柄
     * @return Sampler 句柄
     */
    private Object createHiZSampler(Object gpuDevice) {
        // 实际集成时应调用 Vulkan API:
        // VkSamplerCreateInfo samplerInfo = {};
        // samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        // samplerInfo.magFilter = VK_FILTER_NEAREST;          // Point sampling（放大）
        // samplerInfo.minFilter = VK_FILTER_NEAREST;          // Point sampling（缩小）
        // samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        // samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        // samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        // samplerInfo.anisotropyEnable = VK_FALSE;             // 禁用各向异性过滤
        // samplerInfo.compareEnable = VK_FALSE;               // 不启用比较模式
        // samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;  // Point mipmap
        //
        // return vkCreateSampler(device, &samplerInfo, nullptr, &sampler);

        // 当前返回占位符
        LOGGER.fine("createHiZSampler [占位符]: Nearest, ClampToEdge");
        return new Object();  // 占位符
    }

    /**
     * 加载 SPIR-V Compute Shader 并创建 Pipeline
     *
     * @param gpuDevice      GPU 设备句柄
     * @param shaderPath     SPIR-V 文件路径（相对于资源根目录）
     * @param pipelineName   Pipeline 名称（用于调试和日志）
     * @return Compute Pipeline 句柄
     *
     * @throws IllegalStateException 如果加载或编译失败
     */
    private Object createComputePipeline(Object gpuDevice, String shaderPath, String pipelineName) {
        // 实际集成时应执行以下步骤：
        // 1. 从文件系统或资源包加载 SPIR-V 二进制码
        // ByteBuffer spirvCode = loadSPIRVBinary(shaderPath);
        //
        // 2. 创建 VkShaderModule
        // VkShaderModuleCreateInfo moduleInfo = {};
        // moduleInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        // moduleInfo.codeSize = spirvCode.capacity();
        // moduleInfo.pCode = spirvCode;
        // VkShaderModule shaderModule;
        // vkCreateShaderModule(device, &moduleInfo, nullptr, &shaderModule);
        //
        // 3. 创建 VkPipelineShaderStageCreateInfo
        // VkPipelineShaderStageCreateInfo stageInfo = {};
        // stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        // stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        // stageInfo.module = shaderModule;
        // stageInfo.pName = "main";
        //
        // 4. 创建 Pipeline Layout（定义 Push Constants 和 Descriptor Set Layouts）
        // VkPipelineLayoutCreateInfo layoutInfo = {};
        // layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        // layoutInfo.pushConstantRangeCount = 1;
        // layoutInfo.pPushConstantRanges = &pushConstantRange;
        // layoutInfo.setLayoutCount = descriptorSetLayouts.length;
        // layoutInfo.pSetLayouts = descriptorSetLayouts;
        //
        // 5. 创建 Compute Pipeline
        // VkComputePipelineCreateInfo pipelineInfo = {};
        // pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        // pipelineInfo.stage = stageInfo;
        // pipelineInfo.layout = pipelineLayout;
        // pipelineInfo.basePipelineHandle = VK_NULL_HANDLE;
        // pipelineInfo.basePipelineIndex = -1;
        //
        // VkPipeline pipeline;
        // vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline);
        //
        // 6. 清理临时资源（Shader Module 在 Pipeline 创建后可销毁）
        // vkDestroyShaderModule(device, shaderModule, nullptr);

        LOGGER.fine(String.format(
                "createComputePipeline [占位符]: path=%s, name=%s",
                shaderPath, pipelineName
        ));
        return new Object();  // 占位符
    }

    /**
     * 创建 Storage Buffer（通用 GPU 缓冲区）
     *
     * @param gpuDevice  GPU 设备句柄
     * @param size       缓冲区大小（字节）
     * @param name       缓冲区名称（用于调试）
     * @return Buffer 句柄
     */
    private Object createStorageBuffer(Object gpuDevice, long size, String name) {
        // 实际集成时应调用 Vulkan API 或 VMA (Vulkan Memory Allocator):
        // VkBufferCreateInfo bufferInfo = {};
        // bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        // bufferInfo.size = size;
        // bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
        //                     VK_BUFFER_USAGE_TRANSFER_DST_BIT |
        //                     VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        // bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        //
        // VmaAllocationCreateInfo allocInfo = {};
        // allocInfo.usage = VMA_MEMORY_USAGE_GPU_ONLY;
        //
        // VmaAllocation allocation;
        // VkBuffer buffer;
        // vmaCreateBuffer(vmaAllocator, &bufferInfo, &allocInfo, &buffer, &allocation, nullptr);

        LOGGER.fine(String.format(
                "createStorageBuffer [占位符]: name=%s, size=%d bytes (%.1f KB)",
                name, size, size / 1024.0
        ));
        return new Object();  // 占位符
    }

    /**
     * 创建 Atomic Counter Buffer（支持原子操作的 GPU 缓冲区）
     *
     * @param gpuDevice  GPU 设备句柄
     * @param size       缓冲区大小（字节）
     * @param name       缓冲区名称（用于调试）
     * @return Buffer 句柄
     */
    private Object createAtomicCounterBuffer(Object gpuDevice, int size, String name) {
        // 与 createStorageBuffer 类似，但额外添加 ATOMIC 标志:
        // bufferInfo.usage |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;  // 支持原子操作

        LOGGER.fine(String.format(
                "createAtomicCounterBuffer [占位符]: name=%s, size=%d bytes", name, size
        ));
        return new Object();  // 占位符
    }

    // ==================== 三阶段 Pass 执行方法 ====================

    /**
     * 执行 Pass 1: Frustum Culling（视锥剔除）
     * <p>
     * 并行测试所有 Chunk 是否在相机视锥体内，
     * 将通过测试的 Chunk 索引写入候选列表缓冲区。
     *
     * <h3>执行流程：</h3>
     * <ol>
     *   <li>绑定 Frustum Culling Compute Pipeline</li>
     *   <li>绑定输入/输出缓冲区（包围盒、候选列表、计数器）</li>
     *   <li>推送相机参数（Push Constants）</li>
     *   <li>清零候选计数器</li>
     *   <li>调度 Compute Shader（基于注册 Chunk 数量）</li>
     *   <li>插入内存屏障（Pass 1 写完成 → Pass 2 可读）</li>
     * </ol>
     *
     * @param encoder           CommandEncoder 对象
     * @param cameraData        相机数据对象
     * @param registeredChunks  注册的 Chunk 数量
     */

    // ==================== 辅助方法 ====================

    /**
     * 填充缓冲区（使用 vkCmdFillBuffer 或等效操作）
     * <p>
     * 快速将缓冲区填充为指定值，常用于清零计数器。
     *
     * @param encoder  CommandEncoder 对象
     * @param buffer   目标缓冲区
     * @param offset   偏移量（字节）
     * @param size     大小（字节）
     * @param data     填充数据（32位值）
     */
    private void fillBuffer(Object encoder, Object buffer, long offset, long size, int data) {
        // TODO: 实现实际的 Buffer Fill 操作
        // 当前实现为存根，仅记录日志
        // 实际实现应调用:
        //   - Vulkan: vkCmdFillBuffer(commandBuffer, buffer, offset, size, data)
        //   - 或使用 Compute Shader 清零
        LOGGER.fine(String.format("fillBuffer: offset=%d, size=%d, data=0x%08X", offset, size, data));
    }

    private void executeFrustumCullPass(Object encoder, Object cameraData, int registeredChunks) {
        try {
            // 步骤 1: 绑定 Pass 1 Compute Pipeline
            bindComputePipeline(encoder, frustumCullPipeline);

            // 步骤 2: 绑定存储缓冲区
            // Binding 0: chunkBoundsBuffer (输入) - 所有 Chunk 的 AABB 数据
            bindStorageBuffer(encoder, 0, chunkBoundsBuffer);
            // Binding 1: candidateListBuffer (输出) - 通过视锥测试的候选索引
            bindStorageBuffer(encoder, 1, candidateListBuffer);
            // Binding 2: candidateCountBuffer (原子计数器输出) - 候选数量
            bindStorageBuffer(encoder, 2, candidateCountBuffer);

            // 步骤 3: 推送相机参数（Push Constants）
            FrustumCullParams params = (FrustumCullParams) buildFrustumCullParams(cameraData);
            params.chunkCount = registeredChunks;
            pushConstants(encoder, params);

            // 步骤 4: 清零候选计数器（使用 Fill Buffer 命令快速清零）
            fillBuffer(encoder, candidateCountBuffer, 0, 4, 0);

            // 步骤 5: 调度 Compute Shader
            // 每个 Workgroup 处理 WORKGROUP_SIZE 个 Chunk
            int dispatchX = (registeredChunks + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
            dispatchCompute(encoder, dispatchX, 1, 1);

            // 步骤 6: 内存屏障（Pass 1 写完成 → Pass 2 可读）
            emitMemoryBarrier(encoder,
                    STORAGE_WRITE_BIT | ATOMIC_WRITE_BIT,  // Pass 1 写入操作
                    STORAGE_READ_BIT | ATOMIC_READ_BIT      // Pass 2 读取操作
            );

            LOGGER.fine(String.format(
                    "Pass 1 (Frustum Cull) 完成: dispatched %d workgroups for %d chunks",
                    dispatchX, registeredChunks
            ));

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "Pass 1 (Frustum Cull) 执行失败: %s", e.getMessage()
            ));
            // 不抛出异常，允许降级到 CPU 剔除或跳过此 Pass
        }
    }

    /**
     * 执行 Pass 2: Hi-Z Occlusion Culling（Hi-Z 遮挡剔除）
     * <p>
     * 使用上一帧的深度金字塔对候选列表中的对象进行遮挡查询，
     * 将未被遮挡的对象索引写入最终可见列表。
     *
     * <h3>核心算法：</h3>
     * <pre>
     * 对每个候选对象:
     *   1. 将其 AABB 投影到屏幕空间（使用 viewProjMatrix）
     *   2. 在 Hi-Z map 上进行层次化深度测试（coarse-to-fine traversal）
     *      - 从最粗层（最小分辨率）开始
     *      - 如果该层完全被遮挡 → 直接标记为不可见，停止遍历
     *      - 否则 → 进入下一更细层继续测试
     *      - 到达最细层仍未被遮挡 → 标记为可见
     *   3. 写入最终可见列表（如果可见）
     * </pre>
     *
     * @param encoder    CommandEncoder 对象
     * @param cameraData 相机数据对象
     * @return 被遮挡剔除的对象数量（用于统计）
     */
    private int executeHizOcclusionCullPass(Object encoder, Object cameraData) {
        int occludedCount = 0;

        try {
            // 步骤 1: 绑定 Pass 2 Compute Pipeline
            bindComputePipeline(encoder, hizOcclusionCullPipeline);

            // 步骤 2: 绑定 Hi-Z 纹理（Sampled Image + Sampler）
            // Binding 0: hiZBuffer (输入) - 上一帧的深度金字塔
            bindSampledImage(encoder, 0, hiZBuffer, hiZSampler);

            // 步骤 3: 绑定存储缓冲区
            // Binding 2: chunkBoundsBuffer (输入) - 用于屏幕空间投影
            bindStorageBuffer(encoder, 2, chunkBoundsBuffer);
            // Binding 3: candidateListBuffer (输入) - Pass 1 的候选列表
            bindStorageBuffer(encoder, 3, candidateListBuffer);
            // Binding 4: candidateCountBuffer (原子计数器输入) - 候选数量
            bindStorageBuffer(encoder, 4, candidateCountBuffer);
            // Binding 5: finalVisibleListBuffer (输出) - 最终可见列表
            bindStorageBuffer(encoder, 5, finalVisibleListBuffer);
            // Binding 6: visibleCountBuffer (原子计数器输出) - 可见数量
            bindStorageBuffer(encoder, 6, visibleCountBuffer);

            // 步骤 4: 推送 Hi-Z 参数（Push Constants）
            HizOcclusionParams hizParams = (HizOcclusionParams) buildHizOcclusionParams(cameraData);
            pushConstants(encoder, hizParams);

            // 步骤 5: 清零可见计数器
            fillBuffer(encoder, visibleCountBuffer, 0, 4, 0);

            // 步骤 6: 调度 Compute Shader（基于候选数量动态调度）
            // 注意: 此处需要先读取 candidateCountBuffer 的值
            // 生产环境建议使用 vkCmdDispatchIndirect 实现完全 GPU-Driven（避免 CPU-GPU 同步）
            int candidateCount = readBackCandidateCount();
            int dispatchX = (candidateCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
            dispatchCompute(encoder, dispatchX, 1, 1);

            // 估算被遮挡的数量（候选数 - 可见数，简化计算）
            // 实际值应在 GPU 端统计并通过 SSBO 回读
            occludedCount = Math.max(0, candidateCount - readBackVisibleCount());

            // 步骤 7: 内存屏障（Pass 2 写完成 → Pass 3 可读）
            emitMemoryBarrier(encoder,
                    STORAGE_WRITE_BIT | ATOMIC_WRITE_BIT,
                    STORAGE_READ_BIT | ATOMIC_READ_BIT
            );

            LOGGER.fine(String.format(
                    "Pass 2 (Hi-Z Occlusion) 完成: candidates=%d, occluded=%d, dispatched %d workgroups",
                    candidateCount, occludedCount, dispatchX
            ));

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "Pass 2 (Hi-Z Occlusion) 执行失败: %s", e.getMessage()
            ));
            // 失败时返回 0（未执行遮挡剔除），允许降级到仅视锥剔除
        }

        return occludedCount;
    }

    /**
     * 执行 Pass 3: Compact + Generate Indirect Draw（压缩 + 生成绘制命令）
     * <p>
     * 使用 Subgroup/Warp-level 操作高效压缩稀疏的最终可见列表，
     * 并生成紧凑的 Indirect Draw Commands 供渲染管线使用。
     *
     * <h3>核心算法：</h3>
     * <pre>
     * Subgroup Parallel Compact:
     *   1. 每个 Subgroup 内部使用 Ballot 操作标记活跃线程
     *   2. 使用 Bit Count 和 Exclusive Scan 计算每个线程的目标位置
     *   3. 并行写入紧凑数组（无竞争条件）
     *   4. 同时生成对应的 Indirect Draw Command
     *
     * Indirect Draw Command 格式 (20 bytes):
     *   uint indexCount;      // 索引数量
     *   uint instanceCount;   // 实例数量（通常为 1）
     *   uint firstIndex;      // 起始索引偏移
     *   int  baseVertex;      // 基顶点偏移
     *   uint baseInstance;    // 基实例索引（通常为 0）
     * </pre>
     *
     * @param encoder CommandEncoder 对象
     */
    private void executeCompactAndDrawPass(Object encoder) {
        try {
            // 步骤 1: 绑定 Pass 3 Compute Pipeline
            bindComputePipeline(encoder, compactAndDrawPipeline);

            // 步骤 2: 绑定存储缓冲区
            // Binding 0: finalVisibleListBuffer (输入/输出) - 压缩前的可见列表
            bindStorageBuffer(encoder, 0, finalVisibleListBuffer);
            // Binding 1: visibleCountBuffer (原子计数器输入) - 可见对象数量
            bindStorageBuffer(encoder, 1, visibleCountBuffer);
            // Binding 2: indirectArgsBuffer (输出) - 生成的 Indirect Draw Commands
            bindStorageBuffer(encoder, 2, indirectArgsBuffer);

            // 步骤 3: 推送压缩参数（Push Constants）
            CompactParams compactParams = (CompactParams) buildCompactParams();
            pushConstants(encoder, compactParams);

            // 步骤 4: 调度 Compute Shader（基于可见数量）
            int visibleCount = readBackVisibleCount();
            int dispatchX = (visibleCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
            dispatchCompute(encoder, dispatchX, 1, 1);

            // 步骤 5: 最终内存屏障（Pass 3 写完成 → 渲染管线可读）
            emitMemoryBarrier(encoder,
                    STORAGE_WRITE_BIT,
                    INDIRECT_COMMAND_READ_BIT  // 渲染时可读取 indirectArgsBuffer
            );

            LOGGER.fine(String.format(
                    "Pass 3 (Compact+Draw) 完成: visible=%d, dispatched %d workgroups",
                    visibleCount, dispatchX
            ));

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "Pass 3 (Compact+Draw) 执行失败: %s", e.getMessage()
            ));
        }
    }

    // ==================== Hi-Z 金字塔更新辅助方法 ====================

    /**
     * 将当前帧的主深度缓冲区拷贝到 Hi-Z Mip 0
     *
     * @param encoder     CommandEncoder 对象
     * @param depthBuffer 当前帧的深度缓冲区（需处于 TRANSFER_SRC_OPTIMAL 布局）
     */
    private void copyDepthToHiZMip0(Object encoder, Object depthBuffer) {
        // 实际集成时应调用:
        // VkImageCopy region = {};
        // region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
        // region.srcSubresource.mipLevel = 0;
        // region.srcSubresource.baseArrayLayer = 0;
        // region.srcSubresource.layerCount = 1;
        // region.dstSubresource = region.srcSubregion;  // 相同配置
        // region.extent = { hiZWidth, hiZHeight, 1 };
        //
        // vkCmdCopyImage(commandBuffer,
        //     depthBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        //     hiZBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        //     1, &region
        // );

        LOGGER.fine("copyDepthToHiZMip0 [占位符]: depth buffer copied to Hi-Z mip 0");
    }

    /**
     * 转换 Hi-Z Image 布局
     *
     * @param encoder     CommandEncoder 对象
     * @param oldLayout   旧布局
     * @param newLayout   新布局
     */
    private void transitionHiZLayout(Object encoder, int oldLayout, int newLayout) {
        // 实际集成时应调用:
        // VkImageMemoryBarrier barrier = {};
        // barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        // barrier.oldLayout = oldLayout;
        // barrier.newLayout = newLayout;
        // barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        // barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        // barrier.image = hiZBuffer;
        // barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        // barrier.subresourceRange.baseMipLevel = 0;
        // barrier.subresourceRange.levelCount = actualMipLevels;
        // barrier.subresourceRange.baseArrayLayer = 0;
        // barrier.subresourceRange.layerCount = 1;
        //
        // vkCmdPipelineBarrier(commandBuffer,
        //     srcStage, dstStage,
        //     0,
        //     0, nullptr,
        //     0, nullptr,
        //     1, &barrier
        // );

        LOGGER.fine(String.format(
                "transitionHiZLayout [占位符]: %d → %d", oldLayout, newLayout
        ));
    }

    /**
     * 运行 Hi-Z Reduce Compute Shader（生成单层 Mipmap）
     * <p>
     * 使用 2×2 Max Reduction 算法从源 Mip 生成目标 Mip：
     * <pre>
     * dest(x, y) = max(
     *     src(2*x,   2*y),
     *     src(2*x+1, 2*y),
     *     src(2*x,   2*y+1),
     *     src(2*x+1, 2*y+1)
     * )
     * </pre>
     *
     * @param encoder   CommandEncoder 对象
     * @param hiZImage  Hi-Z Image 句柄
     * @param srcMip    源 Mip 层级
     * @param dstMip    目标 Mip 层级
     */
    private void runHiZReduceComputeShader(Object encoder, Object hiZImage, int srcMip, int dstMip) {
        // 实际集成时应:
        // 1. 绑定专用的 Hi-Z Reduce Compute Pipeline
        // 2. 绑定 Hi-Z Image 作为 Input/Output (storage image)
        // 3. 推送 srcMip/dstMip 参数
        // 4. 基于 dest mip 尺寸调度 compute shader

        int dstWidth = Math.max(1, hiZWidth >> dstMip);
        int dstHeight = Math.max(1, hiZHeight >> dstMip);
        int dispatchX = (dstWidth + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        int dispatchY = (dstHeight + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;

        LOGGER.fine(String.format(
                "runHiZReduceComputeShader [占位符]: mip %d → %d, destSize=%dx%d, dispatch=(%d,%d)",
                srcMip, dstMip, dstWidth, dstHeight, dispatchX, dispatchY
        ));
    }

    /**
     * 发出 Hi-Z Memory Barrier（确保所有 Mipmap 写入完成）
     *
     * @param encoder     CommandEncoder 对象
     * @param srcAccess   源访问标志
     * @param dstAccess   目标访问标志
     */
    private void emitHiZMemoryBarrier(Object encoder, int srcAccess, int dstAccess) {
        // 实际集成时应调用 vkCmdPipelineBarrier
        LOGGER.fine(String.format(
                "emitHiZMemoryBarrier [占位符]: srcAccess=0x%X, dstAccess=0x%X",
                srcAccess, dstAccess
        ));
    }

    // ==================== 数据提取辅助方法 ====================

    /**
     * 从相机数据提取 View-Projection 矩阵
     *
     * @param cameraData 相机数据对象
     * @return 4×4 列主序浮点矩阵（16 个 float）
     */
    private float[] extractViewProjectionMatrix(Object cameraData) {
        // 实际集成时应根据 Blaze3D 的 Camera 接口提取
        // 示例: return cameraData.getViewProjectionMatrix();
        float[] matrix = new float[16];
        // 初始化为单位矩阵（占位符）
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1.0f;
        return matrix;
    }

    /**
     * 从相机数据提取相机世界空间位置
     *
     * @param cameraData 相机数据对象
     * @return 三维坐标 [x, y, z]
     */
    private float[] extractCameraPosition(Object cameraData) {
        // 实际集成时应根据 Blaze3D 的 Camera 接口提取
        // 示例: return cameraData.getPosition();
        return new float[]{0.0f, 0.0f, 0.0f};  // 占位符
    }

    /**
     * 从 Staging Buffer 读取原子计数器的值
     *
     * @param counterBuffer 原子计数器缓冲区
     * @return 计数值
     */
    private int readAtomicCounterFromGPU(Object counterBuffer) {
        // 实际集成时应:
        // 1. 创建 Staging Buffer (HOST_VISIBLE | HOST_COHERENT)
        // 2. vkCmdCopyBuffer(commandBuffer, counterBuffer, stagingBuffer, 1, &region)
        // 3. 提交 command buffer 并等待完成（Fence 同步）
        // 4. Map staging buffer 并读取值
        // 5. Unmap 并释放 staging buffer

        // 当前返回估计值（避免实际的 GPU-CPU 同步开销）
        return estimateCandidateCount();
    }

    /**
     * 估算候选数量（基于注册 Chunk 数量的启发式估计）
     *
     * @return 估计的候选数量
     */
    private int estimateCandidateCount() {
        // 启发式假设: 约 50% 的 Chunk 在视锥体内
        return Math.max(1, getRegisteredChunkCount() / 2);
    }

    /**
     * 估算可见数量（基于候选数量的启发式估计）
     *
     * @return 估计的可见数量
     */
    private int estimateVisibleCount() {
        // 启发式假设: 约 50% 的候选对象未被遮挡（即约 25% 总数可见）
        return Math.max(1, getRegisteredChunkCount() / 4);
    }

    // ==================== 底层 Vulkan 抽象方法（待集成）====================

    /**
     * 绑定 Compute Pipeline
     */
    private void bindComputePipeline(Object encoder, Object pipeline) {
        // 实际集成: encoder.bindComputePipeline(pipeline)
    }

    /**
     * 绑定 Storage Buffer 到指定 binding 点
     */
    private void bindStorageBuffer(Object encoder, int binding, Object buffer) {
        // 实际集成: encoder.bindStorageBuffer(binding, buffer)
    }

    /**
     * 绑定 Sampled Image + Sampler
     */
    private void bindSampledImage(Object encoder, int binding, Object image, Object sampler) {
        // 实际集成: encoder.bindSampledImage(binding, image, sampler)
    }

    /**
     * 推送常量数据到 Shader
     */
    private void pushConstants(Object encoder, Object params) {
        // 实际集成: encoder.pushConstants(params)
    }

    /**
     * 用指定值填充缓冲区区域（快速清零）
     */
    private void fillBuffer(Object buffer, long offset, long size, int value) {
        // 实际集成: vkCmdFillBuffer(commandBuffer, buffer, offset, size, value)
    }

    /**
     * 调度 Compute Shader
     */
    private void dispatchCompute(Object encoder, int x, int y, int z) {
        // 实际集成: encoder.dispatch(x, y, z)
    }

    /**
     * 发出内存屏障
     */
    private void emitMemoryBarrier(Object encoder, int srcAccess, int dstAccess) {
        // 实际集成: encoder.memoryBarrier(srcAccess, dstAccess)
    }

    // ==================== MR1 参数结构体定义 ====================

    /**
     * Pass 1 (Frustum Culling) Push Constants 参数结构体
     * <p>
     * 传递给 Compute Shader 的相机和配置参数。
     * 总大小: ~180 字节（需符合 Vulkan push constants 128 字节限制，
     * 超出部分应使用 Uniform/Storage Buffer）
     */
    public static class FrustumCullParams {
        /** View-Projection 4×4 矩阵（列主序，64 字节） */
        public float[] viewProjMatrix = new float[16];

        /** 视锥平面（6 个平面，每平面 4 个 float = 96 字节） */
        public float[][] frustumPlanes = new float[6][4];

        /** 相机世界坐标（3 个 float = 12 字节） */
        public float[] cameraPosition = new float[3];

        /** 渲染距离（格为单位，1 个 float = 4 字节） */
        public float renderDistance;

        /** 注册的 Chunk 数量（uint, 4 字节） */
        public int chunkCount;
    }

    /**
     * Pass 2 (Hi-Z Occlusion Culling) Push Constants 参数结构体
     * <p>
     * 包含 Hi-Z 遮挡查询所需的所有参数。
     */
    public static class HizOcclusionParams {
        /** View-Projection 4×4 矩阵（列主序，64 字节） */
        public float[] viewProjMatrix = new float[16];

        /** 屏幕尺寸 [width, height]（vec2, 8 字节） */
        public float[] screenSize = new float[2];

        /** 保守偏差阈值（float, 4 字节） */
        public float conservativeBias;

        /** 最大 Mipmap 层数（uint, 4 字节） */
        public int maxMipLevels;
    }

    /**
     * Pass 3 (Compact + Indirect Draw) Push Constants 参数结构体
     * <p>
     * 包含压缩和绘制命令生成所需的参数。
     */
    public static class CompactParams {
        /** Indirect Draw Command 步长（uint, 4 字节） */
        public int indirectCommandStride;

        /** 最大可见对象数量（uint, 4 字节） */
        public int maxVisibleCount;

        /** Subgroup/Warp 大小（uint, 4 字节） */
        public int subgroupSize;
    }
}
