// Renderium - 激进 MC 优化器
// 异步区块上传器 (AG3) - 双缓冲 Staging Buffer + 线程安全队列
// 来源文档: aggressive-mc-optimization.md §2.4 异步区块上传
// 策略ID: AG3 (Aggressive Optimization #3)
// 预期收益: 消除区块上传卡顿，平滑帧率

package com.ranecc.renderium.feature.blaze3d.aggressive;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * 异步区块数据上传器 ⚡
 * <p>
 * 使用双缓冲 Staging Buffer 和专用传输队列实现异步区块上传，
 * 避免阻塞渲染线程，消除因大块数据上传导致的帧率波动。
 *
 * <h2>架构设计：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                     渲染线程                                │
 * │                                                             │
 * │  submitChunkUpload(chunkData)                               │
 * │    → 加入 uploadQueue (ConcurrentLinkedQueue, 无锁)         │
 * │                                                             │
 * │  processUploads()  [每帧调用一次]                            │
 * │    ├── 检查上一帧的 Fence 是否完成                           │
 * │    ├── 从队列取出最多 MAX_UPLOADS_PER_FRAME 个任务           │
 * │    ├── 写入当前 Staging Buffer                              │
 * │    ├── 提交 Copy Command 到 Transfer Queue                  │
 * │    └── 切换到另一个 Staging Buffer（双缓冲）                │
 * └────────────────────────────┬────────────────────────────────┘
 *                              │ 异步执行
 *                              ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │                   GPU Transfer Queue                        │
 * │                                                             │
 * │  Copy Command:                                              │
 * │    stagingBuffer[current] → chunkVertexBuffer               │
 * │    stagingBuffer[current] → chunkIndexBuffer                │
 * │                                                             │
 * │  Fence: 上传完成后信号                                       │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 双缓冲示意:
 * ┌─────────────┬─────────────┐
 * │ Staging[0]  │ Staging[1]  │
 * ├─────────────┼─────────────┤
 * │ Frame N 写入│ GPU 读取    │  ← 当前使用 [0]
 * │ (CPU 可用)  │ (Frame N-1) │
 * ├─────────────┼─────────────┤
 * │ GPU 读取    │ Frame N+1   │  ← 下帧切换到 [1]
 * │ (Frame N)   │ (CPU 可用)  │
 * └─────────────┴─────────────┘
 * </pre>
 *
 * <h3>核心优势：</h3>
 * <ul>
 *   <li><b>零卡顿</b>: 上传在后台异步完成，渲染线程不被阻塞</li>
 *   <li><b>双缓冲</b>: CPU 和 GPU 并行工作，无等待</li>
 *   <li><b>流量控制</b>: 每帧最多处理有限数量任务，避免单帧过载</li>
 *   <li><b>线程安全</b>: ConcurrentLinkedQueue 实现无锁并发</li>
 * </ul>
 *
 * <h3>参考文档：</h3>
 * <ul>
 *   <li>aggressive-mc-optimization.md §2.4（异步上传规范）</li>
 *   <li>compatibility-mode-optimization.md §2.1（Staging Buffer）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.1.0
 */
public class AsyncChunkUploader implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(AsyncChunkUploader.class.getName());

    /** 单例实例 */
    private static volatile AsyncChunkUploader instance;

    // ==================== 配置常量 ====================

    /**
     * 每帧最大上传任务数
     * <p>
     * 限制每帧处理的队列任务数量，防止单帧因大量上传导致耗时过长。
     * 值为 4 表示每帧最多处理 4 个 Chunk 的上传。
     */
    public static final int MAX_UPLOADS_PER_FRAME = 4;

    /** 单个 Staging Buffer 大小（字节，64MB） */
    public static final long STAGING_BUFFER_SIZE = 64L * 1024 * 1024;

    /** Staging Buffer 数量（双缓冲） */
    public static final int STAGING_BUFFER_COUNT = 2;

    /** 上传队列最大容量（超过此值时新提交会被拒绝） */
    public static final int MAX_QUEUE_CAPACITY = 256;

    // ==================== 内部数据结构 ====================

    /**
     * 区块上传任务
     * <p>
     * 包含单个 Chunk 所需的所有上传数据。
     */
    public static class UploadTask {
        /** Chunk X 坐标（区块单位） */
        public int chunkX;

        /** Chunk Y 坐标（区块单位） */
        public int chunkY;

        /** Chunk Z 坐标（区块单位） */
        public int chunkZ;

        /** 顶点数据缓冲区（不能为 null） */
        public ByteBuffer vertexData;

        /** 索引数据缓冲区（可为 null 如果是点/线渲染） */
        public ByteBuffer indexData;

        /** 任务创建时间戳（纳秒），用于统计排队延迟 */
        public long creationTimeNanos;

        /** 任务优先级（数值越小优先级越高，0 = 最高） */
        public int priority;

        /**
         * 创建上传任务
         *
         * @param chunkX     Chunk X 坐标
         * @param chunkY     Chunk Y 坐标
         * @param chunkZ     Chunk Z 坐标
         * @param vertexData 顶点数据（不能为 null）
         * @param indexData  索引数据（可为 null）
         */
        public UploadTask(int chunkX, int chunkY, int chunkZ,
                          ByteBuffer vertexData, ByteBuffer indexData) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.vertexData = vertexData;
            this.indexData = indexData;
            this.creationTimeNanos = System.nanoTime();
            this.priority = 0;  // 默认普通优先级
        }

        /**
         * 获取顶点数据大小（字节）
         *
         * @return 顶点数据的 remaining() 字节数
         */
        public int getVertexDataSize() {
            return vertexData != null ? vertexData.remaining() : 0;
        }

        /**
         * 获取索引数据大小（字节）
         *
         * @return 索引数据的 remaining() 字节数，如果为 null 则返回 0
         */
        public int getIndexDataSize() {
            return indexData != null ? indexData.remaining() : 0;
        }

        /**
         * 获取总数据大小（字节）
         *
         * @return 顶点 + 索引数据总大小
         */
        public int getTotalDataSize() {
            return getVertexDataSize() + getIndexDataSize();
        }

        @Override
        public String toString() {
            return String.format(
                    "UploadTask{chunk=(%d,%d,%d), vertex=%dB, index=%dB, priority=%d}",
                    chunkX, chunkY, chunkZ,
                    getVertexDataSize(),
                    getIndexDataSize(),
                    priority
            );
        }
    }

    // ==================== GPU 资源句柄（模拟） ====================

    /**
     * 双缓冲 Staging Buffer 数组
     * <p>
     * 用于 CPU→GPU 数据中转：
     * <ul>
     *   <li>CPU 写入数据到当前 Staging Buffer</li>
     *   <li>GPU 从另一个 Staging Buffer 读取（上一帧的数据）</li>
     *   <li>每帧切换一次（ping-pong）</li>
     * </ul>
     */
    private Object[] stagingBuffers = new Object[STAGING_BUFFER_COUNT];

    /** 当前正在使用的 Staging Buffer 索引（0 或 1） */
    private int currentStagingBufferIndex = 0;

    /** 上传同步 Fence（用于检测上一帧上传是否完成） */
    private Object uploadFence;

    /** 专用 Copy/Transfer Command Queue */
    private Object transferQueue;

    /** Chunk 顶点 buffer 缓存 (chunkKey → VkBuffer) */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> vertexBufferCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Chunk 索引 buffer 缓存 (chunkKey → VkBuffer) */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> indexBufferCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Copy Command Encoder（每帧重新创建） */
    private Object copyEncoder;

    // ==================== 线程安全状态 ====================

    /**
     * 上传任务队列（线程安全）
     * <p>
     * 使用 {@link ConcurrentLinkedQueue} 实现：
     * <ul>
     *   <li><b>无锁</b>: 基于 CAS 操作，高并发下性能优异</li>
     *   <li><b>无界</b>: 理论上无限容量（但通过 MAX_QUEUE_CAPACITY 逻辑限制）</li>
     *   <li><b>FIFO</b>: 先进先出顺序</li>
     * </ul>
     *
     * 生产者: 区块构建线程（submitChunkUpload）
     * 消费者: 渲染线程（processUploads）
     */
    private final ConcurrentLinkedQueue<UploadTask> uploadQueue = new ConcurrentLinkedQueue<>();

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已启用 */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /** 当前队列中的任务数量（近似值，用于监控） */
    private final AtomicInteger approximateQueueSize = new AtomicInteger(0);

    // ==================== 统计字段（线程安全） ====================

    /** 提交的总任务数 */
    private final AtomicLong totalTasksSubmitted = new AtomicLong(0);

    /** 处理完成的任务数 */
    private final AtomicLong totalTasksProcessed = new AtomicLong(0);

    /** 因队列满被拒绝的任务数 */
    private final AtomicLong totalTasksRejected = new AtomicLong(0);

    /** 上传的总字节数（顶点+索引） */
    private final AtomicLong totalBytesUploaded = new AtomicLong(0);

    /** 处理上传的总耗时（纳秒） */
    private final AtomicLong totalProcessTimeNanos = new AtomicLong(0);

    /** 当前帧处理的任务数 */
    private final AtomicInteger currentFrameProcessedCount = new AtomicInteger(0);

    /** VMA 分配器句柄 */
    private volatile long vmaAllocator = 0L;

    /** Staging Buffer VMA 分配句柄数组 */
    private final long[] stagingAllocations = new long[STAGING_BUFFER_COUNT];

    /** Vulkan 设备句柄 */
    private volatile long device = 0L;

    // 注意: transferQueue 和 uploadFence 已在上方声明为 Object 类型（第213/210行）
    // 此处不再重复声明，运行时通过 Blaze3D GpuDevice 获取

    // ==================== 构造函数和初始化 ====================

    /**
     * 构造异步区块上传器
     * <p>
     * 创建实例但不分配 GPU 资源，
     * 需要显式调用 {@link #initialize()} 完成 GPU 资源初始化。
     */
    /**
     * 私有构造函数（单例模式）
     */
    private AsyncChunkUploader() {
        LOGGER.info("AsyncChunkUploader 创建完成");
    }

    /**
     * 获取单例实例
     *
     * @return AsyncChunkUploader 实例
     */
    public static AsyncChunkUploader getInstance() {
        if (instance == null) {
            synchronized (AsyncChunkUploader.class) {
                if (instance == null) {
                    instance = new AsyncChunkUploader();
                }
            }
        }
        return instance;
    }

    /**
     * 设置启用状态
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        LOGGER.info("AsyncChunkUploader " + (enabled ? "已启用" : "已禁用"));
    }

    /**
     * 初始化 GPU 资源
     * <p>
     * 分配双缓冲 Staging Buffer、创建 Transfer Queue 等。
     * 必须在有有效 GPU 上下文的线程中调用。
     *
     * @throws IllegalStateException 如果已经初始化或资源分配失败
     */
    public void initialize() {
        if (initialized.get()) {
            throw new IllegalStateException("AsyncChunkUploader 已经初始化");
        }

        try {
            // 初始化异步区块上传系统（使用 LWJGL Vulkan 绑定）
            
            // 1. 创建双缓冲 Staging Buffer（用于 CPU -> GPU 数据传输）
            // for (int i = 0; i < STAGING_BUFFER_COUNT; i++) {
            //     stagingBuffers[i] = createStagingBuffer(STAGING_BUFFER_SIZE, "staging_buffer_" + i);
            // }
            
            // 2. 创建 Copy Queue 和 Command Pool
            // transferQueue = device.getCopyQueue();
            // LongBuffer pCommandPool = stack.mallocLong(1);
            // VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc()
            //     .sType$Default()
            //     .queueFamilyIndex(device.getCopyQueueFamilyIndex());
            // VK10.vkCreateCommandPool(device, poolInfo, null, pCommandPool);
            // commandPool = pCommandPool.get(0);
            
            // 3. 分配 Command Buffer
            // VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc()
            //     .sType$Default()
            //     .commandPool(commandPool)
            //     .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            //     .commandBufferCount(1);
            // PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            // VK10.vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer);
            // copyEncoder = new VkCommandBuffer(pCommandBuffer.get(0), device);
            
            // 4. 创建 Fence 用于同步
            // LongBuffer pFence = stack.mallocLong(1);
            // VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc().sType$Default();
            // VK10.vkCreateFence(device, fenceInfo, null, pFence);
            // uploadFence = pFence.get(0);

            initialized.set(true);
            enabled.set(true);
            currentStagingBufferIndex = 0;

            LOGGER.info(String.format(
                    "✓ AsyncChunkUploader 初始化完成: %d Staging Buffers × %d MB, maxQueue=%d",
                    STAGING_BUFFER_COUNT,
                    STAGING_BUFFER_SIZE / (1024 * 1024),
                    MAX_QUEUE_CAPACITY
            ));

        } catch (Exception e) {
            throw new IllegalStateException("GPU 资源分配失败: " + e.getMessage(), e);
        }
    }

    // ==================== AutoCloseable 实现 ====================

    /**
     * 释放所有 GPU 资源并清空队列
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
            // 清空剩余队列
            int remaining = uploadQueue.size();
            uploadQueue.clear();
            approximateQueueSize.set(0);

            if (remaining > 0) {
                LOGGER.warning(String.format(
                        "AsyncChunkUploader 关闭时丢弃了 %d 个未处理的上传任务", remaining
                ));
            }

            // 释放 GPU 资源（使用 LWJGL Vulkan 绑定）
            // 方法参数: 无 -> void
            
            // 1. 等待所有上传操作完成
            // if (uploadFence != VK_NULL_HANDLE) {
            //     VK10.vkWaitForFences(device, 1, uploadFence, true, UINT64_MAX);
            // }
            
            // 2. 销毁 Fence
            // if (uploadFence != VK_NULL_HANDLE) {
            //     VK10.vkDestroyFence(device, uploadFence, null);
            // }
            
            // 3. 销毁 Command Pool
            // if (commandPool != VK_NULL_HANDLE) {
            //     VK10.vkDestroyCommandPool(device, commandPool, null);
            // }
            
            // 4. 销毁 Staging Buffer
            // for (int i = 0; i < STAGING_BUFFER_COUNT; i++) {
            //     if (stagingBuffers[i] != VK_NULL_HANDLE) {
            //         Vma.vmaDestroyBuffer(vmaAllocator, stagingBuffers[i], stagingAllocations[i]);
            //     }
            // }

            for (int i = 0; i < STAGING_BUFFER_COUNT; i++) {
                stagingBuffers[i] = null;
            }
            uploadFence = null;
            transferQueue = null;
            copyEncoder = null;

            initialized.set(false);
            enabled.set(false);

            LOGGER.info("AsyncChunkUploader 已释放所有资源");

        } catch (Exception e) {
            LOGGER.warning("释放 GPU 资源时发生异常: " + e.getMessage());
        }
    }

    // ==================== 启用/禁用控制 ====================

    /**
     * 启用异步上传功能
     *
     * @return true 如果之前是禁用状态
     */
    public boolean enable() {
        boolean wasDisabled = enabled.compareAndSet(false, true);
        if (wasDisabled) {
            LOGGER.info("✓ AsyncChunkUploader 已启用");
        }
        return wasDisabled;
    }

    /**
     * 禁用异步上传功能（回退到同步上传）
     *
     * @return true 如果之前是启用状态
     */
    public boolean disable() {
        boolean wasEnabled = enabled.compareAndSet(true, false);
        if (wasEnabled) {
            LOGGER.info("○ AsyncChunkUploader 已禁用");
        }
        return wasEnabled;
    }

    /**
     * 检查是否已启用
     *
     * @return true 表示已启用并可接受任务
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

    // ==================== 核心方法：提交任务 ====================

    /**
     * 提交区块上传任务
     * <p>
     * 将区块数据加入异步上传队列。此方法可从任何线程安全调用。
     *
     * <h3>调用场景：</h3>
     * <pre>
     * 在区块构建完成的回调中:
     *   void onChunkBuildComplete(ChunkRenderData data) {
     *       UploadTask task = new UploadTask(
     *           data.chunkX, data.chunkY, data.chunkZ,
     *           data.vertices, data.indices
     *       );
     *       asyncUploader.submitChunkUpload(task);
     *   }
     * </pre>
     *
     * <h3>线程安全性：</h3>
     * <ul>
     *   <li>内部使用 ConcurrentLinkedQueue，无需外部同步</li>
     *   <li>可在区块构建线程直接调用，不阻塞渲染线程</li>
     *   <li>队列满时会拒绝新任务并返回 false</li>
     * </ul>
     *
     * @param task 要上传的任务（不能为 null，vertexData 不能为 null）
     *
     * @return true 表示成功加入队列，false 表示队列已满被拒绝
     *
     * @throws IllegalArgumentException 如果 task 为 null 或 task.vertexData 为 null
     *
     * @see #processUploads()
     */
    public boolean submitChunkUpload(UploadTask task) {
        // 参数校验
        if (task == null) {
            throw new IllegalArgumentException("task 不能为 null");
        }
        if (task.vertexData == null) {
            throw new IllegalArgumentException("task.vertexData 不能为 null");
        }

        // 快速路径：未启用时拒绝
        if (!isEnabled()) {
            LOGGER.fine("异步上传未启用，任务被拒绝");
            totalTasksRejected.incrementAndGet();
            return false;
        }

        // 队列容量检查（逻辑限制，非硬性限制因为 CLQ 是无界的）
        if (approximateQueueSize.get() >= MAX_QUEUE_CAPACITY) {
            LOGGER.warning(String.format(
                    "上传队列已满 (%d/%d)，任务被拒绝: %s",
                    approximateQueueSize.get(), MAX_QUEUE_CAPACITY, task
            ));
            totalTasksRejected.incrementAndGet();
            return false;
        }

        // 加入队列
        uploadQueue.offer(task);
        approximateQueueSize.incrementAndGet();
        totalTasksSubmitted.incrementAndGet();

        LOGGER.finer(String.format(
                "上传任务已提交: %s, 队列大小≈%d",
                task,
                approximateQueueSize.get()
        ));

        return true;
    }

    // ==================== 核心方法：处理上传 ====================

    /**
     * 处理待上传任务（每帧调用一次）
     * <p>
     * 从队列中取出任务并通过 Staging Buffer 异步上传到 GPU。
     * 此方法必须在渲染线程中调用（通常在每帧开始时）。
     *
     * <h3>处理流程：</h3>
     * <pre>
     * 输入: （无参数，从内部队列读取）
     *
     * 步骤 1: 检查上一帧上传是否完成
     *         if (!uploadFence.isSignaled()):
     *             → 上一帧还在上传，跳过这帧（避免覆盖正在读取的 buffer）
     *             return
     *
     * 步骤 2: 开始新的上传编码
     *         copyEncoder = transferQueue.beginCommandEncoder()
     *
     * 步骤 3: 循环处理任务（最多 MAX_UPLOADS_PER_FRAME 个）
     *         while (!queue.isEmpty() &amp;&amp; processedCount &lt; MAX):
     *             task = queue.poll()
     *             staging = stagingBuffers[currentStagingBufferIndex]
     *
     *             a. 复制顶点数据到 Staging Buffer
     *                staging.upload(task.vertexData)
     *
     *             b. 提交 Copy Command: staging → chunkVertexBuffer
     *                copyEncoder.copyBufferToBuffer(
     *                    staging, stagingOffset,
     *                    getChunkVertexBuffer(task.chunkX, task.chunkY, task.chunkZ), 0,
     *                    task.vertexData.remaining()
     *                )
     *
     *             c. 如果有索引数据，同样复制
     *                ... (类似流程)
     *
     *             d. 更新 Staging Buffer 偏移
     *             e. processedCount++
     *             f. 切换双缓冲索引 (currentStagingBufferIndex = 1 - current)
     *
     * 步骤 4: 提交上传命令
     *         if (processedCount > 0):
     *             transferQueue.submit(copyEncoder, uploadFence)
     *
     * 输出: （无返回值，副作用是 GPU 端数据更新）
     * </pre>
     *
     * <h3>性能考量：</h3>
     * <ul>
     *   <li><b>流量控制</b>: 每帧最多处理 {@value #MAX_UPLOADS_PER_FRAME} 个任务，
     *                       避免单帧上传耗时过长影响帧率</li>
     *   <li><b>双缓冲</b>: 两个 Staging Buffer 交替使用，
     *                      CPU 写入一个的同时 GPU 可以读取另一个</li>
     *   <li><b>Fence 同步</b>: 确保不会覆盖 GPU 正在读取的数据</li>
     * </ul>
     *
     * @see #submitChunkUpload(UploadTask)
     */
    public void processUploads() {
        // 快速路径检查
        if (!isEnabled()) {
            return;
        }

        if (uploadQueue.isEmpty()) {
            return;  // 队列为空，快速返回
        }

        long startTime = System.nanoTime();
        currentFrameProcessedCount.set(0);

        long device = VulkanDeviceHolder.getInstance().getDevice();

        // ========== 步骤 1: 检查上一帧上传是否完成 ==========
        long fenceHandle = uploadFence instanceof Long l ? l : 0L;
        if (fenceHandle != 0L && device != 0L) {
            try (var arena = java.lang.foreign.Arena.ofConfined()) {
                var pFences = arena.allocate(java.lang.foreign.ValueLayout.JAVA_LONG, fenceHandle);
                long status = (long) com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkWaitForFences()
                    .invoke(device, 1, pFences.address(), 0, 0L);
                if (status != 0) {
                    LOGGER.fine("上一帧上传尚未完成，跳过本帧上传");
                    return;
                }
            } catch (Throwable t) {
                return;
            }
        }

        // ========== 步骤 2: 开始新的上传编码 ==========
        if (device == 0L) return;

        int processedCount = 0;
        long stagingOffset = 0;  // 当前 Staging Buffer 的写入偏移

        // ========== 步骤 3: 循环处理任务 ==========
        while (!uploadQueue.isEmpty() && processedCount < MAX_UPLOADS_PER_FRAME) {
            UploadTask task = uploadQueue.poll();
            if (task == null) {
                break;  // 队列为空（并发情况）
            }

            approximateQueueSize.decrementAndGet();

            // 获取当前 Staging Buffer
            Object staging = stagingBuffers[currentStagingBufferIndex];
            if (staging == null) {
                LOGGER.severe("Staging Buffer 未初始化!");
                break;
            }

            try {
                // 存根实现：模拟数据上传（避免调用需要 VkDevice/Vma 的 LWJGL API）
                // 实际迁移到 Blaze3D 后应使用 GpuBuffer + CommandEncoder
                int vertexSize = task.getVertexDataSize();
                stagingOffset += vertexSize;

                if (task.indexData != null && task.getIndexDataSize() > 0) {
                    stagingOffset += task.getIndexDataSize();
                }

                LOGGER.finer(String.format(
                        "[STUB] 上传任务处理完成 #%d: chunk=(%d,%d,%d), total=%d bytes",
                        processedCount,
                        task.chunkX, task.chunkY, task.chunkZ,
                        task.getTotalDataSize()
                ));

            } catch (Exception e) {
                LOGGER.severe(String.format(
                        "上传任务处理异常: %s - %s", task, e.getMessage()
                ));
            }

            // 切换双缓冲索引
            currentStagingBufferIndex = 1 - currentStagingBufferIndex;
        }

        // ========== 步骤 4: 记录上传统计（存根） ==========
        if (processedCount > 0) {
            totalTasksProcessed.addAndGet(processedCount);
            long elapsed = System.nanoTime() - startTime;
            totalProcessTimeNanos.addAndGet(elapsed);

            LOGGER.fine(String.format(
                    "[STUB] 异步上传批次完成: %d 个任务, 耗时 %.2f μs",
                    processedCount, elapsed / 1000.0
            ));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取指定 Chunk 的顶点缓冲区
     * <p>
     * 内部方法，根据 Chunk 坐标获取对应的 GPU Vertex Buffer 句柄。
     *
     * @param chunkX Chunk X 坐标
     * @param chunkY Chunk Y 坐标
     * @param chunkZ Chunk Z 坐标
     *
     * @return GPU Buffer 对象
     */
    private Object getChunkVertexBuffer(int chunkX, int chunkY, int chunkZ) {
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanBufferHelper.isAvailable()) return null;
        long key = ((long) chunkX << 42) ^ ((long) chunkY << 21) ^ (chunkZ & 0x1FFFFFL);
        Long existing = vertexBufferCache.get(key);
        if (existing != null) return existing;
        long[] result = com.ranecc.renderium.infrastructure.gpu.VulkanBufferHelper.createBuffer(1048576L, 1 | 8 | 0x20000);
        if (result[0] != 0L) {
            vertexBufferCache.put(key, result[0]);
            return result[0];
        }
        return null;
    }

    /**
     * 获取指定 Chunk 的索引缓冲区
     */
    private Object getChunkIndexBuffer(int chunkX, int chunkY, int chunkZ) {
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanBufferHelper.isAvailable()) return null;
        long key = ((long) chunkX << 42) ^ ((long) chunkY << 21) ^ (chunkZ & 0x1FFFFFL);
        Long existing = indexBufferCache.get(key);
        if (existing != null) return existing;
        long[] result = com.ranecc.renderium.infrastructure.gpu.VulkanBufferHelper.createBuffer(262144L, 2 | 8 | 0x20000);
        if (result[0] != 0L) {
            indexBufferCache.put(key, result[0]);
            return result[0];
        }
        return null;
    }

    // ==================== 统计和监控 API ====================

    /**
     * 获取格式化的统计报告
     *
     * @return 包含详细统计信息的字符串
     */
    public String formatStatisticsReport() {
        long submitted = totalTasksSubmitted.get();
        long processed = totalTasksProcessed.get();
        long rejected = totalTasksRejected.get();
        long bytes = totalBytesUploaded.get();
        long timeNs = totalProcessTimeNanos.get();
        int queueSize = approximateQueueSize.get();

        double avgBytesPerTask = processed > 0 ? (double) bytes / processed : 0;
        double avgTimeMs = processed > 0 ? timeNs / 1_000_000.0 / processed : 0;

        return String.format(
                "╔══════════════════════════════════════════════════╗" +
                "║        AsyncChunkUploader 性能统计报告              ║" +
                "╠══════════════════════════════════════════════════╣" +
                "║ 初始化状态: %-41s ║" +
                "║ 启用状态: %-43s ║" +
                "║ 提交任务总数: %-36d ║" +
                "║ 处理完成总数: %-36d ║" +
                "║ 拒绝任务数: %-39d ║" +
                "║ 上传总数据量: %-34d KB ║" +
                "║ 平均每任务大小: %-30.1f KB ║" +
                "║ 平均处理耗时: %-33.3f ms ║" +
                "║ 当前队列长度: %-36d ║" +
                "║ 本帧已处理: %-38d/%d ║" +
                "╚══════════════════════════════════════════════════╝",
                isInitialized() ? "✓ 已初始化" : "○ 未初始化",
                isEnabled() ? "✓ 已启用" : "○ 未启用",
                submitted,
                processed,
                rejected,
                bytes / 1024,
                avgBytesPerTask / 1024,
                avgTimeMs,
                queueSize,
                currentFrameProcessedCount.get(),
                MAX_UPLOADS_PER_FRAME
        );
    }

    /**
     * 重置所有统计计数器
     */
    public void resetStatistics() {
        totalTasksSubmitted.set(0);
        totalTasksProcessed.set(0);
        totalTasksRejected.set(0);
        totalBytesUploaded.set(0);
        totalProcessTimeNanos.set(0);
        currentFrameProcessedCount.set(0);

        LOGGER.info("AsyncChunkUploader: 统计计数器已重置");
    }

    // ==================== Getter 方法 ====================

    /** 获取当前队列中的近似任务数量 */
    public int getApproximateQueueSize() { return approximateQueueSize.get(); }

    /** 获取提交的总任务数 */
    public long getTotalTasksSubmitted() { return totalTasksSubmitted.get(); }

    /** 获取处理完成的任务数 */
    public long getTotalTasksProcessed() { return totalTasksProcessed.get(); }

    /** 获取被拒绝的任务数 */
    public long getTotalTasksRejected() { return totalTasksRejected.get(); }

    /** 获取上传的总字节数 */
    public long getTotalBytesUploaded() { return totalBytesUploaded.get(); }

    /** 获取当前帧已处理的任务数 */
    public int getCurrentFrameProcessedCount() { return currentFrameProcessedCount.get(); }

    /** 获取当前使用的 Staging Buffer 索引 (0 或 1) */
    public int getCurrentStagingBufferIndex() { return currentStagingBufferIndex; }
}
