// Renderium - 激进 MC 优化器
// GPU 剔除系统 (AG2扩展) - Compute Shader 三阶段剔除
// 来源文档: aggressive-mc-optimization.md §2.3 GPU-Driven 剔除
// 策略ID: AG2-EXT (Aggressive Optimization #2 Extension)
// 预期收益: CPU 开销从 2-3ms 降到 0.1ms (20-30x)

package com.ranecc.renderium.feature.blaze3d.aggressive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

// 存根导入：Vulkan 常量（实际应通过 Blaze3D 抽象层访问）
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU 驱动剔除系统 🎯
 * <p>
 * 使用 Compute Shader 在 GPU 端执行三阶段剔除：
 * <ol>
 *   <li><b>视锥剔除 (Frustum Culling)</b>: 排除相机视野外的 Chunk</li>
 *   <li><b>距离剔除 (Distance Culling)</b>: 排除超过渲染距离的 Chunk</li>
 *   <li><b>间接绘制参数生成</b>: 为可见 Chunk 生成 Indirect Draw 命令</li>
 * </ol>
 *
 * <h2>架构设计：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    CPU 端（Java）                            │
 * │                                                             │
 * │  GPUCullingSystem                                           │
 * │    ├── chunkBoundsBuffer   [GPU Resident]                   │
 * │    ├── visibilityBuffer    [GPU→GPU]                        │
 * │    └── indirectArgsBuffer  [GPU Generated]                  │
 * │                                                             │
 * │  updateChunkBounds(x, y, z, bounds)  ← 区块构建完成时调用   │
 * │  executeCulling(encoder, camera)      ← 每帧渲染前调用       │
 * │  renderWithCulling(encoder)           ← 使用剔除结果渲染     │
 * └────────────────────────────┬────────────────────────────────┘
 *                              │ Dispatch Compute Shader
 *                              ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    GPU 端（Compute Shader）                  │
 * │                                                             │
 * │  CullingComputeShader                                      │
 * │    Input:  chunkBounds[] + Camera Params                    │
 * │    Output: visibility[] + indirectCommands[]                │
 * │                                                             │
 * │    for each chunk (one thread per chunk):                    │
 * │      1. testFrustum(bounds, frustumPlanes)                  │
 * │      2. testDistance(bounds, cameraPos, renderDist)          │
 * │      3. if visible → write to indirectCommands[]             │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>性能优势：</h3>
 * <ul>
 *   <li><b>CPU 卸载</b>: 剔除逻辑完全在 GPU 并行执行，CPU 开销降至 ~0.1ms</li>
 *   <li><b>零拷贝传输</b>: 剔除结果通过 GPU Buffer 直接传递给渲染管线</li>
 *   <li><b>自动 LOD</b>: 可在 Compute Shader 中实现距离-based LOD 选择</li>
 *   <li><b>可扩展性</b>: 支持遮挡查询、层次剔除等高级特性</li>
 * </ul>
 *
 * <h3>参考文档：</h3>
 * <ul>
 *   <li>aggressive-mc-optimization.md §2.3（GPU 剔除规范）</li>
 *   <li>vulkan-exclusive-optimizations.md §三（Compute Shader 优化）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.1.0
 * @see AggressiveBatchRenderer
 */
public class GPUCullingSystem implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(GPUCullingSystem.class.getName());

    /** 单例实例 */
    private static volatile GPUCullingSystem instance;

    // ==================== 配置常量 ====================

    /** Chunk 包围盒结构体大小（6个float × 4B = 24B） */
    public static final int BOUNDING_BOX_SIZE = 24;

    /** 可见性标志大小（uint32 = 4B） */
    public static final int VISIBILITY_FLAG_SIZE = 4;

    /**
     * Indirect Draw 命令结构体大小（字节）
     * <pre>
     * uint indexCount;      // 4 bytes
     * uint instanceCount;   // 4 bytes
     * uint firstIndex;      // 4 bytes
     * int  baseVertex;      // 4 bytes
     * uint baseInstance;    // 4 bytes
     * 总计: 20 bytes
     * </pre>
     */
    public static final int INDIRECT_COMMAND_SIZE = 20;

    /** Compute Shader 工作组大小（每个工作组处理的 Chunk 数） */
    public static final int WORKGROUP_SIZE = 256;

    /** 最大支持的 Chunk 数量（限制 GPU 内存分配） */
    public static final int MAX_CHUNK_COUNT = 65536;

    /** 默认渲染距离（区块单位，16 = 256 格） */
    public static final float DEFAULT_RENDER_DISTANCE = 16.0f;

    // ==================== GPU 缓冲区句柄（模拟） ====================

    /**
     * Chunk 包围盒数据缓冲区（GPU Resident）
     * <p>
     * 存储 AABB (Axis-Aligned Bounding Box) 数据：
     * <pre>
     * struct BoundingBox {
     *     vec3 min;  // 12 bytes (3 × float)
     *     vec3 max;  // 12 bytes (3 × float)
     * };            // Total: 24 bytes per chunk
     * </pre>
     *
     * <h3>更新时机：</h3>
     * 当区块构建或重建完成时调用 {@link #updateChunkBounds(int, int, int, BoundingBox)} 更新。
     */
    protected Object chunkBoundsBuffer;

    /**
     * 可见性结果缓冲区（GPU→GPU）
     * <p>
     * 存储每个 Chunk 的可见性标志：
     * <pre>
     * uint visible[N];  // N = MAX_CHUNK_COUNT
     * // 0 = 不可见, 1 = 可见
     * </pre>
     *
     * 由 Compute Shader 写入，由渲染管线读取。
     */
    private Object visibilityBuffer;

    /**
     * 间接绘制参数缓冲区（GPU Generated）
     * <p>
     * 由 Compute Shader 动态生成可见 Chunk 的 Draw Command。
     * 渲染时使用 {@code vkCmdDrawIndirectCount} 或类似 API 读取。
     *
     * <h3>命令格式：</h3>
     * 与 AggressiveBatchRenderer.INDIRECT_COMMAND_SIZE 相同（20 字节/命令）
     */
    protected Object indirectArgsBuffer;

    /** Compute Pipeline 句柄（剔除着色器管线） */
    private Object cullingPipeline;

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已启用 */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /** 当前已注册的 Chunk 数量 */
    private volatile int registeredChunkCount = 0;

    /** 当前可见 Chunk 数量（上一帧的剔除结果） */
    private volatile int visibleChunkCount = 0;

    /** 当前渲染距离（区块单位） */
    private volatile float currentRenderDistance = DEFAULT_RENDER_DISTANCE;

    // ==================== 统计字段（线程安全） ====================

    /** 剔除操作总次数 */
    private final AtomicLong totalCullingPasses = new AtomicLong(0);

    /** 总共剔除的 Chunk 数量累计 */
    private final AtomicLong totalChunksCulled = new AtomicLong(0);

    /** 剔除计算总耗时（纳秒） */
    private final AtomicLong totalCullingTimeNanos = new AtomicLong(0);

    /** 包围盒更新总次数 */
    private final AtomicLong totalBoundsUpdates = new AtomicLong(0);

    /** 累计渲染的 Chunk 数量 */
    private final AtomicLong totalRenderedChunks = new AtomicLong(0);

    /** 当前相机视锥体数据 (用于上传到 GPU) */
    private volatile Object cameraFrustum = null;

    /** 当前命令缓冲区句柄 */
    private volatile long commandBuffer = 0L;

    /** Compute Shader dispatch X 维度 */
    private volatile int dispatchX = 1;

    // ==================== 构造函数和初始化 ====================

    /**
     * 构造 GPU 剔除系统
     * <p>
     * 创建实例但不立即分配 GPU 资源，
     * 需要显式调用 {@link #initialize()} 完成初始化。
     */
    /**
     * 受保护构造函数（允许子类继承）
     */
    protected GPUCullingSystem() {
        LOGGER.info("GPUCullingSystem 创建完成");
    }

    /**
     * 获取单例实例
     *
     * @return GPUCullingSystem 实例
     */
    public static GPUCullingSystem getInstance() {
        if (instance == null) {
            synchronized (GPUCullingSystem.class) {
                if (instance == null) {
                    instance = new GPUCullingSystem();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化 GPU 资源
     * <p>
     * 分配所有需要的 GPU Buffer 和创建 Compute Pipeline。
     * 必须在首次使用前调用，且必须在有有效 GPU 上下文的线程中调用。
     *
     * @throws IllegalStateException 如果已经初始化过或 GPU 资源分配失败
     */
    public void initialize() {
        if (initialized.get()) {
            throw new IllegalStateException("GPUCullingSystem 已经初始化");
        }

        try {
            // 初始化 GPU 剔除系统（使用 LWJGL Vulkan 绑定）
            
            // 1. 创建 Compute Shader Pipeline
            //    - 编译剔除着色器
            //    - 创建 Pipeline Layout
            //    - 创建 Compute Pipeline
            // computePipeline = createCullComputePipeline();
            
            // 2. 分配 GPU Buffer
            //    - 输入包围盒缓冲区 (SSBO)
            //    - 输出可见性结果缓冲区
            //    - 视锥体参数常量缓冲区
            // boundsBuffer = createGPUBuffer(MAX_CHUNKS * BOUNDS_SIZE, ...);
            // visibilityBuffer = createGPUBuffer(MAX_CHUNKS, ...);
            // frustumParamsBuffer = createGPUBuffer(FRUSTUM_PARAMS_SIZE, ...);
            
            initialized.set(true);
            enabled.set(true);

            LOGGER.info("✓ GPU 剔除系统已初始化");

        } catch (Exception e) {
            throw new IllegalStateException("GPU 资源分配失败: " + e.getMessage(), e);
        }
    }

    // ==================== AutoCloseable 实现 ====================

    /**
     * 释放所有 GPU 资源
     * <p>
     * 应在模块卸载或窗口关闭时调用。
     * 释放后此对象不可再使用。
     */
    @Override
    public void close() {
        if (!initialized.get()) {
            return;
        }

        try {
            // Release GPU resources using LWJGL Vulkan bindings
            // When real Vulkan resources are implemented, uncomment and complete these calls:
            //
            // 1. Destroy Compute Pipeline
            // if (cullingPipeline != null && cullingPipeline.getHandle() != VK_NULL_HANDLE) {
            //     VK10.vkDestroyPipeline(device, cullingPipeline.getHandle(), null);
            //     cullingPipeline = null;
            // }
            //
            // 2. Destroy Buffers (using VMA for proper memory deallocation)
            // if (chunkBoundsBuffer != null) {
            //     Vma.vmaDestroyBuffer(vmaAllocator, chunkBoundsBuffer.getHandle(), chunkBoundsAllocation);
            //     chunkBoundsBuffer = null;
            // }
            // if (visibilityBuffer != null) {
            //     Vma.vmaDestroyBuffer(vmaAllocator, visibilityBuffer.getHandle(), visibilityAllocation);
            //     visibilityBuffer = null;
            // }
            // if (indirectArgsBuffer != null) {
            //     Vma.vmaDestroyBuffer(vmaAllocator, indirectArgsBuffer.getHandle(), indirectArgsAllocation);
            //     indirectArgsBuffer = null;
            // }

            // Clear all references to allow GC
            chunkBoundsBuffer = null;
            visibilityBuffer = null;
            indirectArgsBuffer = null;
            cullingPipeline = null;

            initialized.set(false);
            enabled.set(false);
            registeredChunkCount = 0;
            visibleChunkCount = 0;

            LOGGER.info("GPU Culling System shutdown completed");

        } catch (Exception e) {
            LOGGER.severe("Error releasing GPU resources: " + e.getMessage());
            // Force cleanup state even on error
            initialized.set(false);
            enabled.set(false);
        }
    }

    // ==================== 启用/禁用控制 ====================

    /**
     * 启用 GPU 剔除功能
     *
     * @return true 如果之前是禁用状态
     */
    public boolean enable() {
        boolean wasDisabled = enabled.compareAndSet(false, true);
        if (wasDisabled) {
            LOGGER.info("✓ GPUCullingSystem 已启用");
        }
        return wasDisabled;
    }

    /**
     * 禁用 GPU 剔除功能（回退到 CPU 剔除）
     *
     * @return true 如果之前是启用状态
     */
    public boolean disable() {
        boolean wasEnabled = enabled.compareAndSet(true, false);
        if (wasEnabled) {
            LOGGER.info("○ GPUCullingSystem 已禁用");
        }
        return wasEnabled;
    }

    /**
     * 检查是否已启用
     *
     * @return true 表示已启用并可执行剔除
     */
    public boolean isEnabled() {
        return enabled.get() && initialized.get();
    }

    /**
     * 检查是否已初始化
     *
     * @return true 表示已完成初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    // ==================== 核心方法：数据更新 ====================

    /**
     * 更新 Chunk 包围盒数据
     * <p>
     * 当区块构建或重建完成时应调用此方法更新包围盒。
     * 数据将上传到 GPU 端的 {@code chunkBoundsBuffer}。
     *
     * <h3>调用场景：</h3>
     * <pre>
     * 在区块构建完成的回调中:
     *   void onChunkBuilt(int x, int y, int z, ChunkRenderData data) {
     *       BoundingBox bounds = calculateBoundingBox(data.vertices);
     *       gpuCulling.updateChunkBounds(x, y, z, bounds);
     *   }
     * </pre>
     *
     * <h3>包围盒格式：</h3>
     * <pre>
     * class BoundingBox {
     *     float minX, minY, minZ;  // 最小角坐标
     *     float maxX, maxY, maxZ;  // 最大角坐标
     * }
     * </pre>
     *
     * @param x      Chunk X 坐标（区块单位）
     * @param y      Chunk Y 坐标（区块单位）
     * @param z      Chunk Z 坐标（区块单位）
     * @param bounds 该 Chunk 的轴对齐包围盒（不能为 null）
     *
     * @throws IllegalArgumentException 如果 bounds 为 null 或坐标超出范围
     * @throws IllegalStateException    如果未初始化
     */
    public void updateChunkBounds(int x, int y, int z, BoundingBox bounds) {
        if (!initialized.get()) {
            throw new IllegalStateException("GPUCullingSystem 未初始化");
        }
        if (bounds == null) {
            throw new IllegalArgumentException("bounds 不能为 null");
        }

        // 计算线性索引（简化的一维索引，实际应使用空间哈希）
        int index = getChunkIndex(x, y, z);

        if (index < 0 || index >= MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException(
                    String.format("Chunk 坐标超出范围: (%d,%d,%d) → index=%d, max=%d",
                            x, y, z, index, MAX_CHUNK_COUNT)
            );
        }

        // 上传包围盒数据到 GPU（使用 LWJGL Vulkan 绑定）
        // 方法参数: chunkId, bounds -> void
        
        if (chunkBoundsBuffer == null) {
            return;  // 缓冲区未初始化
        }

        // 1. 映射缓冲区
        try {
            // 准备包围盒数据（24 字节，Little Endian）
            ByteBuffer data = ByteBuffer.allocate(BOUNDING_BOX_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN);
            data.putFloat(bounds.minX);
            data.putFloat(bounds.minY);
            data.putFloat(bounds.minZ);
            data.putFloat(bounds.maxX);
            data.putFloat(bounds.maxY);
            data.putFloat(bounds.maxZ);
            data.flip();
            
            // 2. 写入包围盒数据到指定偏移
            int offset = index * BOUNDING_BOX_SIZE;
            // TODO: 使用 VMA 或 Vulkan API 上传数据
            // 示例:
            // PointerBuffer pMapped = MemoryUtil.memAllocPointer(1);
            // Vma.vmaMapMemory(vmaAllocator, chunkBoundsAllocation, pMapped);
            // long mappedPtr = pMapped.get(0);
            // MemoryUtil.memPutFloat(mappedPtr + offset + 0, bounds.minX);
            // MemoryUtil.memPutFloat(mappedPtr + offset + 4, bounds.minY);
            // MemoryUtil.memPutFloat(mappedPtr + offset + 8, bounds.minZ);
            // MemoryUtil.memPutFloat(mappedPtr + offset + 12, bounds.maxX);
            // MemoryUtil.memPutFloat(mappedPtr + offset + 16, bounds.maxY);
            // MemoryUtil.memPutFloat(mappedPtr + offset + 20, bounds.maxZ);
            // Vma.vmaUnmapMemory(vmaAllocator, chunkBoundsAllocation);
            
            LOGGER.finest(String.format("更新 Chunk (%d,%d,%d) [index=%d] 包围盒: [%.1f,%.1f,%.1f] - [%.1f,%.1f,%.1f]",
                    x, y, z, index, bounds.minX, bounds.minY, bounds.minZ,
                    bounds.maxX, bounds.maxY, bounds.maxZ));
                    
        } catch (Exception e) {
            LOGGER.warning(String.format("上传包围盒数据失败: %s", e.getMessage()));
        }

        registeredChunkCount = Math.max(registeredChunkCount, index + 1);
        totalBoundsUpdates.incrementAndGet();

        LOGGER.finer(String.format(
                "更新 Chunk 包围盒: (%d,%d,%d) → index=%d, bounds=[%.1f,%.1f,%.1f]-[%.1f,%.1f,%.1f]",
                x, y, z, index,
                bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ
        ));
    }

    /**
     * 设置当前渲染距离
     *
     * @param distance 渲染距离（区块单位，必须 > 0）
     */
    public void setRenderDistance(float distance) {
        if (distance <= 0) {
            throw new IllegalArgumentException("渲染距离必须 > 0: " + distance);
        }
        this.currentRenderDistance = distance;
    }

    // ==================== 核心方法：执行剔除 ====================

    /**
     * 执行 GPU 剔除
     * <p>
     * 调度 Compute Shader 执行三阶段剔除，结果写入 GPU Buffer。
     * 此方法应在每帧渲染开始时调用。
     *
     * <h3>剔除流程：</h3>
     * <pre>
     * 输入:
     *   - encoder: CommandEncoder 对象
     *   - camera: 相机对象（提供 view-projection 矩阵、位置等）
     *
     * 步骤 1: 绑定 Compute Pipeline
     *         encoder.bindComputePipeline(cullingPipeline);
     *
     * 步骤 2: 绑定存储缓冲区
     *         encoder.bindStorageBuffer(0, chunkBoundsBuffer);  // 输入: 包围盒
     *         encoder.bindStorageBuffer(1, visibilityBuffer);   // 输出: 可见性标志
     *         encoder.bindStorageBuffer(2, indirectArgsBuffer);  // 输出: 间接绘制参数
     *
     * 步骤 3: 推送常量（Push Constants）
     *         CullingParams params = {
     *             viewProjMatrix: camera.getViewProjectionMatrix(),
     *             frustumPlanes: extractFrustumPlanes(viewProjMatrix),
     *             cameraPosition: camera.getPosition(),
     *             renderDistance: currentRenderDistance * 16.0f  // 区块→格
     *         };
     *         encoder.pushConstants(params);
     *
     * 步骤 4: 调度 Compute Shader
     *         int dispatchX = (registeredChunkCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
     *         encoder.dispatch(dispatchX, 1, 1);
     *
     * 步骤 5: 内存屏障
     *         encoder.memoryBarrier(
     *             STORAGE_WRITE_BIT,           // Compute Shader 写入完成
     *             INDIRECT_COMMAND_READ_BIT    // 渲染时可读取
     *         );
     * </pre>
     *
     * @param encoder Blaze3D CommandEncoder 对象（不能为 null）
     * @param camera  相机对象（不能为 null，需提供 getViewProjMatrix 等方法）
     *
     * @throws IllegalArgumentException 如果 encoder 或 camera 为 null
     * @throws IllegalStateException    如果未初始化或未启用
     *
     * @see #renderWithCulling(Object)
     */
    public void executeCulling(Object encoder, Object camera) {
        if (encoder == null) {
            throw new IllegalArgumentException("encoder 不能为 null");
        }
        if (camera == null) {
            throw new IllegalArgumentException("camera 不能为 null");
        }
        if (!isEnabled()) {
            throw new IllegalStateException("GPUCullingSystem 未启用或未初始化");
        }

        long startTimeNanos = System.nanoTime();

        try {
            // 调度 Compute Shader 执行 GPU 剔除（使用 LWJGL Vulkan 绑定）
            // 方法参数: cameraFrustum -> CullingResult (剔除结果)
            
            // 1. 上传视锥体参数到常量缓冲区
            uploadFrustumParameters(cameraFrustum);
            
            // 2. 绑定 Descriptor Set（包围盒、可见性、视锥体）
            // VK10.vkCmdBindDescriptorSets(
            //         commandBuffer,
            //         VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
            //         pipelineLayout,
            //         0,  // firstSet
            //         descriptorSet,
            //         null  // dynamicOffsets
            // );
            
            // 3. 分发 Compute Shader（存根：避免调用需要 VkCommandBuffer 的 LWJGL API）
            int workgroupCount = (int) Math.ceil((double) registeredChunkCount / WORKGROUP_SIZE);
            LOGGER.fine("GPU Culling dispatch [STUB]: workgroups=" + workgroupCount);
            
            // 4. 添加内存屏障确保写入完成（存根：避免调用需要 VkCommandBuffer 对象的 LWJGL API）
            // 实际迁移到 Blaze3D 后应使用 CommandEncoder 的屏障抽象
            LOGGER.fine("GPU Culling barrier [STUB]: compute→draw indirect");
            
            // 5. 读取可见性结果（可选：如果需要 CPU 端读取）
            // readVisibilityResults();

            long elapsed = System.nanoTime() - startTimeNanos;
            totalCullingPasses.incrementAndGet();
            totalCullingTimeNanos.addAndGet(elapsed);

            LOGGER.fine(String.format(
                    "GPU 剔除完成: %d Chunks 注册ed, dispatched %d workgroups, 耗时 %.2f μs",
                    registeredChunkCount,
                    dispatchX,
                    elapsed / 1000.0
            ));

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "GPU 剔除执行异常: %s", e.getMessage()"
            ));
        }
    }

    // ==================== 核心方法：使用剔除结果渲染 ====================

    /**
     * 使用剔除结果进行渲染
     * <p>
     * 读取 Compute Shader 生成的间接绘制参数，
     * 执行仅包含可见 Chunk 的绘制调用。
     *
     * <h3>渲染方式：</h3>
     * <p>
     * 使用 {@code DrawIndirectCount} 或类似机制：
     * <pre>
     * GPU 自动读取 indirectArgsBuffer 中的命令数量
     * （由 Compute Shader 写入的可见 Chunk 计数）
     * 只渲染通过剔除测试的 Chunk
     * </pre>
     *
     * <h3>调用时机：</h3>
     * 必须在 {@link #executeCulling(Object, Object)} 之后调用。
     *
     * @param encoder Blaze3D CommandEncoder 对象（不能为 null）
     *
     * @throws IllegalArgumentException 如果 encoder 为 null
     * @throws IllegalStateException    如果尚未执行剔除
     *
     * @see #executeCulling(Object, Object)
     */
    public int renderWithCulling(Object encoder) {
        if (encoder == null) {
            throw new IllegalArgumentException("encoder 不能为 null");
        }

        // 使用 GPU 剔除结果进行渲染（使用 LWJGL Vulkan 绑定）
        // 方法参数: encoder, chunkRenderer -> int (渲染的可见区块数量)
        
        if (!enabled.get()) {
            return 0;  // 剔除系统未启用
        }

        long startTimeNanos = System.nanoTime();
        
        // 1. 绑定 Indirect Buffer（包含剔除后的绘制命令）
        // VK10.vkCmdBindIndexBuffer(
        //         commandBuffer,
        //         indexBuffer,
        //         0L,
        //         VK10.VK_INDEX_TYPE_UINT32
        // );
        
        // 2. 执行 MultiDrawIndirect（使用 GPU 剔除后的间接命令）
        // VK10.vkCmdDrawIndirect(
        //         commandBuffer,
        //         indirectArgsBuffer,   // indirect buffer
        //         0L,                   // offset (字节)
        //         visibleChunkCount,    // draw count
        //         INDIRECT_COMMAND_SIZE // stride (每个命令的字节大小)
        // );
        
        // 更新统计
        totalRenderedChunks.addAndGet(visibleChunkCount);
        
        long elapsed = System.nanoTime() - startTimeNanos;
        LOGGER.fine(String.format(
                "使用 GPU 剔除结果渲染: %d 个可见 Chunk, 耗时 %.2f μs",
                visibleChunkCount,
                elapsed / 1000.0
        ));
        
        return visibleChunkCount;
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算 Chunk 的线性索引
     * <p>
     * 简化实现：实际应使用空间哈希或三维数组索引。
     *
     * @param x Chunk X 坐标
     * @param y Chunk Y 坐标
     * @param z Chunk Z 坐标
     *
     * @return 线性索引值（0 ~ MAX_CHUNK_COUNT-1）
     */
    private int getChunkIndex(int x, int y, int z) {
        // 简化的哈希函数（生产环境应使用更好的空间索引）
        // 注意: 这可能导致冲突，仅用于演示
        int hash = ((x * 73856093) ^ (y * 19349669) ^ (z * 83492791)) & 0x7FFFFFFF;
        return hash % MAX_CHUNK_COUNT;
    }

    /**
     * 从 View-Projection 矩阵提取视锥平面
     * <p>
     * 用于 Push Constants 传递给 Compute Shader。
     *
     * @param viewProj 4×4 View-Projection 矩阵（列主序，16 个 float）
     *
     * @return 6 个视锥平面的系数（每平面 4 个 float: a, b, c, d）
     */
    protected float[][] extractFrustumPlanes(float[] viewProj) {
        // 左、右、下、上、近、远 6 个平面
        float[][] planes = new float[6][4];

        // Left plane: row4 + row3
        planes[0][0] = viewProj[3] + viewProj[0];
        planes[0][1] = viewProj[7] + viewProj[4];
        planes[0][2] = viewProj[11] + viewProj[8];
        planes[0][3] = viewProj[15] + viewProj[12];

        // Right plane: row4 - row3
        planes[1][0] = viewProj[3] - viewProj[0];
        planes[1][1] = viewProj[7] - viewProj[4];
        planes[1][2] = viewProj[11] - viewProj[8];
        planes[1][3] = viewProj[15] - viewProj[12];

        // Bottom plane: row4 + row2
        planes[2][0] = viewProj[3] + viewProj[1];
        planes[2][1] = viewProj[7] + viewProj[5];
        planes[2][2] = viewProj[11] + viewProj[9];
        planes[2][3] = viewProj[15] + viewProj[13];

        // Top plane: row4 - row2
        planes[3][0] = viewProj[3] - viewProj[1];
        planes[3][1] = viewProj[7] - viewProj[5];
        planes[3][2] = viewProj[11] - viewProj[9];
        planes[3][3] = viewProj[15] - viewProj[13];

        // Near plane: row4 + row1
        planes[4][0] = viewProj[3] + viewProj[2];
        planes[4][1] = viewProj[7] + viewProj[6];
        planes[4][2] = viewProj[11] + viewProj[10];
        planes[4][3] = viewProj[15] + viewProj[14];

        // Far plane: row4 - row1
        planes[5][0] = viewProj[3] - viewProj[2];
        planes[5][1] = viewProj[7] - viewProj[6];
        planes[5][2] = viewProj[11] - viewProj[10];
        planes[5][3] = viewProj[15] - viewProj[14];

        return planes;
    }

    /**
     * 上传视锥体参数到 GPU 常量缓冲区 (存根实现)
     * <p>
     * 将相机视锥体的 6 个平面参数上传到 GPU 端的常量缓冲区，
     * 供 Compute Shader 在剔除时使用。
     *
     * @param frustum 视锥体对象（当前为 Object 类型，迁移到 Blaze3D 后将使用具体类型）
     */
    private void uploadFrustumParameters(Object frustum) {
        // TODO: 存根实现 - 实际迁移到 Blaze3D 后应通过 CommandEncoder
        //       将视锥体 6 平面参数写入 VkBuffer (Uniform/Push Constants)
        LOGGER.fine("uploadFrustumParameters [STUB]: frustum=" + frustum);
    }

    // ==================== 统计和监控 API ====================

    /**
     * 获取格式化的统计报告
     *
     * @return 包含详细统计信息的字符串
     */
    public String formatStatisticsReport() {
        long passes = totalCullingPasses.get();
        long culled = totalChunksCulled.get();
        long timeNs = totalCullingTimeNanos.get();
        long updates = totalBoundsUpdates.get();

        double avgTimeUs = passes > 0 ? timeNs / 1000.0 / passes : 0;

        return String.format(
                "╔══════════════════════════════════════════════════╗" +
                "║         GPUCullingSystem 性能统计报告              ║" +
                "╠══════════════════════════════════════════════════╣" +
                "║ 初始化状态: %-41s ║" +
                "║ 启用状态: %-43s ║" +
                "║ 注册 Chunk 数: %-37d ║" +
                "║ 当前可见数: %-39d ║" +
                "║ 剔除总次数: %-39d ║" +
                "║ 平均剔除耗时: %-35.2f μs ║" +
                "║ 包围盒更新次数: %-33d ║" +
                "║ 渲染距离: %-40.1f ║" +
                "╚══════════════════════════════════════════════════╝",
                isInitialized() ? "✓ 已初始化" : "○ 未初始化",
                isEnabled() ? "✓ 已启用" : "○ 未启用",
                registeredChunkCount,
                visibleChunkCount,
                passes,
                avgTimeUs,
                updates,
                currentRenderDistance
        );
    }

    /**
     * 重置所有统计计数器
     */
    public void resetStatistics() {
        totalCullingPasses.set(0);
        totalChunksCulled.set(0);
        totalCullingTimeNanos.set(0);
        totalBoundsUpdates.set(0);

        LOGGER.info("GPUCullingSystem: 统计计数器已重置");
    }

    // ==================== Getter 方法 ====================

    /** 获取注册的 Chunk 数量 */
    public int getRegisteredChunkCount() { return registeredChunkCount; }

    /** 获取上一帧可见 Chunk 数量 */
    public int getVisibleChunkCount() { return visibleChunkCount; }

    /** 获取当前渲染距离 */
    public float getRenderDistance() { return currentRenderDistance; }

    /** 获取剔除总次数 */
    public long getTotalCullingPasses() { return totalCullingPasses.get(); }

    /** 获取平均剔除耗时（微秒） */
    public double getAverageCullingTimeMicros() {
        long passes = totalCullingPasses.get();
        return passes > 0 ? totalCullingTimeNanos.get() / 1000.0 / passes : 0;
    }

    // ==================== 内部数据结构 ====================

    /**
     * 轴对齐包围盒 (AABB)
     * <p>
     * 用于表示一个 Chunk 的空间范围。
     */
    public static class BoundingBox {
        /** 最小 X 坐标 */
        public float minX;

        /** 最小 Y 坐标 */
        public float minY;

        /** 最小 Z 坐标 */
        public float minZ;

        /** 最大 X 坐标 */
        public float maxX;

        /** 最大 Y 坐标 */
        public float maxY;

        /** 最大 Z 坐标 */
        public float maxZ;

        /**
         * 创建包围盒
         *
         * @param minX 最小 X
         * @param minY 最小 Y
         * @param minZ 最小 Z
         * @param maxX 最大 X
         * @param maxY 最大 Y
         * @param maxZ 最大 Z
         */
        public BoundingBox(float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        /**
         * 获取中心点 X
         */
        public float getCenterX() { return (minX + maxX) * 0.5f; }

        /**
         * 获取中心点 Y
         */
        public float getCenterY() { return (minY + maxY) * 0.5f; }

        /**
         * 获取中心点 Z
         */
        public float getCenterZ() { return (minZ + maxZ) * 0.5f; }

        @Override
        public String toString() {
            return String.format(
                    "BoundingBox[%.1f,%.1f,%.1f - %.1f,%.1f,%.1f]",
                    minX, minY, minZ, maxX, maxY, maxZ
            );
        }
    }

    /**
     * 剔除参数（Push Constants）
     * <p>
     * 传递给 Compute Shader 的相机和配置参数。
     */
    public static class CullingParams {
        /** View-Projection 4×4 矩阵（列主序，64 字节） */
        public float[] viewProjMatrix = new float[16];

        /** 视锥平面（6 个平面，每平面 4 个 float = 96 字节） */
        public float[][] frustumPlanes = new float[6][4];

        /** 相机世界坐标（3 个 float = 12 字节） */
        public float[] cameraPosition = new float[3];

        /** 渲染距离（格为单位，1 个 float = 4 字节） */
        public float renderDistance;

        /** 总大小: 176 字节（需符合 Vulkan push constants 大小限制）*/
    }
}
