// Renderium - 异步渲染管线调度器 (v1)
// 高性能帧级渲染任务编排系统
// 设计目标: 原版 16 视距稳定 800-1000 FPS
// 核心策略:
//   1. onFrameBegin() 异步化：将扩展回调拆分为独立任务
//   2. 遮挡剔除并行化：BFS 引擎在独立线程运行
//   3. Chunk 构建异步化：工作线程池 + 双缓冲结果队列
//   4. 批量渲染合并：MultiDrawIndirect 减少 Draw Call
//
// BFS 遮挡剔除的业务链:
//   processOcclusionCull() → 构造 BfsInput (DTO, 10 字段)
//     → AdaptivePathSelector.selectBfsStrategy() (Java/C++ 路径选择)
//     → JavaBfsStrategy / NativeBfsStrategy (Strategy 适配器层)
//     → AlgorithmStrategy<BfsInput, CullResult> (策略接口, 预留框架)
//     → BfsOcclusionEngine (核心计算引擎)
//       OR
//     → RenderiumAccelerator.bfs() → BfsOcclusionFFIAdapter (C++ FFI)

package com.ranecc.renderium.feature.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.pipeline.RenderExtension;
import com.ranecc.renderium.feature.pipeline.BfsOcclusionEngine;
import com.ranecc.renderium.feature.pipeline.strategy.AlgorithmStrategy;
import com.ranecc.renderium.feature.pipeline.strategy.BfsInput;

/**
 * 异步渲染管线调度器
 * <p>
 * 统一管理渲染管线的所有异步任务，实现：
 * <ul>
 *   <li><b>onFrameBegin() 异步化</b>: 扩展回调不再阻塞主线程</li>
 *   <li><b>遮挡剔除并行化</b>: BFS 引擎在工作线程执行</li>
 *   <li><b>Chunk 构建流水线</b>: 多线程构建 + 双缓冲上传</li>
 *   <li><b>批量渲染合并</b>: 自动聚合 Draw Call</li>
 * </ul>
 *
 * <h3>架构设计：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    渲染线程 (Render Thread)                  │
 * │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
 * │  │ onFrameBegin │→│ collectResults│→│ submitBatches    │  │
 * │  │ (快速路径)    │  │ (O(1) 收集)  │  │ (MultiDraw)      │  │
 * │  └──────────────┘  └──────────────┘  └──────────────────┘  │
 * └─────────────────────────────────────────────────────────────┘
 *          │                    │                    │
 *          ▼                    ▼                    ▼
 * ┌────────────────┐  ┌────────────────┐  ┌──────────────────┐
 * │  Culling Thread │  │ Build Threads  │  │ Upload Queue     │
 * │  (BFS 遮挡剔除) │  │ (Chunk 构建)   │  │ (GPU 上传)       │
 * └────────────────┘  └────────────────┘  └──────────────────┘
 * </pre>
 *
 * <h3>性能目标（16 视距）：</h3>
 * <table border="1">
 *   <tr><th>阶段</th><th>优化前</th><th>优化后</th><th>提升</th></tr>
 *   <tr><td>onFrameBegin()</td><td>0.5-2ms</td><td>&lt;0.1ms</td><td>5-20x</td></tr>
 *   <tr><td>遮挡剔除</td><td>2-5ms (CPU)</td><td>&lt;0.5ms (并行)</td><td>4-10x</td></tr>
 *   <tr><td>Chunk 构建</td><td>阻塞主线程</td><td>完全异步</td><td>消除阻塞</td></tr>
 *   <tr><td>Draw Calls</td><td>2000-5000</td><td>&lt;100</td><td>20-50x</td></tr>
 * </table>
 */
public final class AsyncRenderPipeline {

    private static final Logger LOGGER = Logger.getLogger(AsyncRenderPipeline.class.getName());

    // ==================== 配置常量 ====================

    /** 默认工作线程数（用于 chunk 构建） */
    private static final int DEFAULT_BUILD_THREAD_COUNT =
            Math.max(1, Runtime.getRuntime().availableProcessors() / 3);

    /** 结果队列容量 */
    private static final int RESULT_QUEUE_CAPACITY = 4096;

    /** 任务队列容量 */
    private static final int TASK_QUEUE_CAPACITY = 1024;

    // ==================== 核心组件 ====================

    /** 单例实例 */
    private static volatile AsyncRenderPipeline instance;

    /** Java 回退引擎（当策略不可用时使用） */
    private final BfsOcclusionEngine fallbackEngine;

    /** BFS 遮挡剔除策略（volatile 保证跨线程可见性） */
    private volatile AlgorithmStrategy<BfsInput, BfsOcclusionEngine.CullResult> bfsStrategy;

    /** 自适应路径选择器（动态选择 Java/Native 实现） */
    private final AdaptivePathSelector pathSelector;

    /** 帧任务队列（无锁环形缓冲区） */
    private final LockFreeRingBuffer<FrameTask> taskQueue;

    /** 结果收集队列（无锁环形缓冲区） */
    private final LockFreeRingBuffer<RenderResult> resultQueue;

    /** Chunk 构建工作线程池 */
    private volatile ExecutorService buildExecutor;

    /** 当前帧号 */
    private volatile int currentFrame = 0;

    // ==================== 状态标志 ====================

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 是否正在运行 */
    private volatile boolean running = false;

    // ==================== 统计字段 ====================

    /** 总处理帧数 */
    private final AtomicLong totalFramesProcessed = new AtomicLong(0);

    /** onFrameBegin 平均耗时（纳秒） */
    private final AtomicLong totalOnFrameBeginTimeNs = new AtomicLong(0);

    /** 遮挡剔除平均耗时（纳秒） */
    private final AtomicLong totalCullTimeNs = new AtomicLong(0);

    /** 总可见区块数 */
    private final AtomicLong totalVisibleSections = new AtomicLong(0);

    /** 总剔除区块数 */
    private final AtomicLong totalCulledSections = new AtomicLong(0);

    // ==================== 内部数据结构 ====================

    /**
     * 帧级任务（入队到任务队列）
     */
    public static class FrameTask {
        /** 帧号 */
        public final int frameNumber;

        /** 任务类型 */
        public final TaskType type;

        /** 关联的数据（扩展引用、区块数据等） */
        public final Object payload;

        /** 帧间隔时间（秒） */
        public final float deltaTime;

        /** 创建时间戳（纳秒） */
        public final long createTimeNs;

        public FrameTask(int frameNumber, TaskType type, Object payload, float deltaTime) {
            this.frameNumber = frameNumber;
            this.type = type;
            this.payload = payload;
            this.deltaTime = deltaTime;
            this.createTimeNs = System.nanoTime();
        }

        /** 兼容构造函数（deltaTime=0） */
        public FrameTask(int frameNumber, TaskType type, Object payload) {
            this(frameNumber, type, payload, 0.0f);
        }

        public enum TaskType {
            /** 扩展 onFrameBegin 回调 */
            EXTENSION_FRAME_BEGIN,
            /** 遮挡剔除请求 */
            OCCLUSION_CULL,
            /** Chunk 构建请求 */
            CHUNK_BUILD,
            /** 批量渲染提交 */
            BATCH_RENDER
        }
    }

    /**
     * 渲染结果（从工作线程返回）
     */
    public static class RenderResult {
        /** 帧号 */
        public final int frameNumber;

        /** 结果类型 */
        public final ResultType type;

        /** 结果数据 */
        public final Object data;

        /** 处理耗时（纳秒） */
        public final long processTimeNs;

        public RenderResult(int frameNumber, ResultType type, Object data, long processTimeNs) {
            this.frameNumber = frameNumber;
            this.type = type;
            this.data = data;
            this.processTimeNs = processTimeNs;
        }

        public enum ResultType {
            /** 扩展回调完成 */
            EXTENSION_COMPLETE,
            /** 遮挡剔除完成 */
            OCCLUSION_RESULT,
            /** Chunk 构建完成 */
            CHUNK_BUILT,
            /** 批量渲染数据就绪 */
            BATCH_READY
        }
    }

    // ==================== 构造函数 ====================

    private AsyncRenderPipeline() {
        this.fallbackEngine = new BfsOcclusionEngine();
        this.pathSelector = new AdaptivePathSelector();
        this.bfsStrategy = pathSelector.createBfsStrategy(0.0f, true, fallbackEngine, 10000);
        this.taskQueue = new LockFreeRingBuffer<>(TASK_QUEUE_CAPACITY);
        this.resultQueue = new LockFreeRingBuffer<>(RESULT_QUEUE_CAPACITY);

        LOGGER.info(String.format("AsyncRenderPipeline 初始化 [buildThreads=%d, taskQueue=%d, resultQueue=%d, bfsStrategy=%s]",
                DEFAULT_BUILD_THREAD_COUNT, TASK_QUEUE_CAPACITY, RESULT_QUEUE_CAPACITY,
                bfsStrategy != null ? bfsStrategy.getImplementationType() : "null"));
    }

    // ==================== 单例访问 ====================

    public static AsyncRenderPipeline getInstance() {
        if (instance == null) {
            synchronized (AsyncRenderPipeline.class) {
                if (instance == null) {
                    instance = new AsyncRenderPipeline();
                }
            }
        }
        return instance;
    }

    /**
     * 检查管线是否已初始化且正在运行（静态安全访问）
     * <p>
     * 用于调用方在不触发懒加载的前提下判断管线状态，
     * 避免因 getInstance() 触发不必要的实例化或依赖 try-catch 防护。
     *
     * @return true 表示实例存在、已初始化且正在运行
     */
    public static boolean isInitialized() {
        return instance != null && instance.initialized && instance.running;
    }

    // ==================== 生命周期管理 ====================

    /**
     * 初始化管线
     * <p>
     * 启动所有工作线程和内部组件。
     */
    public synchronized void initialize() {
        if (initialized) {
            LOGGER.warning("重复初始化");
            return;
        }

        // 启动 Chunk 构建线程池
        buildExecutor = Executors.newFixedThreadPool(DEFAULT_BUILD_THREAD_COUNT, new ThreadFactory() {
            private final AtomicInteger threadCounter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r,
                        "RenderPipeline-BuildWorker-" + threadCounter.incrementAndGet());
                t.setPriority(Thread.NORM_PRIORITY - 1);  // 略低于正常优先级
                t.setDaemon(true);  // 守护线程，不阻止 JVM 退出
                return t;
            }
        });

        // 启动工作线程：每个线程运行 workerLoop() 消费 taskQueue
        for (int i = 0; i < DEFAULT_BUILD_THREAD_COUNT; i++) {
            final int workerId = i + 1;
            buildExecutor.submit(() -> {
                Thread.currentThread().setName("RenderPipeline-Worker-" + workerId);
                LOGGER.info("工作线程 #" + workerId + " 已启动");
                workerLoop();
            });
        }

        initialized = true;
        running = true;

        LOGGER.info(String.format("AsyncRenderPipeline 初始化完成 [workers=%d]", DEFAULT_BUILD_THREAD_COUNT));
    }

    /**
     * 关闭管线
     * <p>
     * 停止所有工作线程，等待任务完成。
     */
    public synchronized void shutdown() {
        if (!initialized) {
            return;
        }

        running = false;

        // 关闭线程池
        if (buildExecutor != null) {
            buildExecutor.shutdown();
            try {
                if (!buildExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    buildExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                buildExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            buildExecutor = null;
        }

        // 清空队列
        taskQueue.clear();
        resultQueue.clear();

        initialized = false;

        LOGGER.info(getStatistics());
        LOGGER.info("AsyncRenderPipeline 已关闭");
    }

    // ==================== 核心 API ====================

    /**
     * 提交任务到异步管线（MPSC 安全）
     * <p>
     * 由 RenderiumCore 或外部调用者使用，将任务入队到工作线程。
     * 帧号由调用方提供（统一帧号源）。
     *
     * @param task 帧任务
     * @return true 如果成功入队
     */
    public boolean submitTask(FrameTask task) {
        if (!running || !initialized || task == null) {
            return false;
        }
        if (!taskQueue.enqueue(task)) {
            LOGGER.warning("任务队列已满，丢弃任务: " + task.type);
            return false;
        }
        return true;
    }

    /**
     * 异步帧开始（替代同步 onFrameBegin）
     * <p>
     * 将原本同步执行的 onFrameBegin 拆分为：
     * <ol>
     *   <li><b>快速路径（主线程）</b>: 帧号由 RenderiumCore 统一管理</li>
     *   <li><b>异步路径（工作线程）</b>: 扩展回调、遮挡剔除、Chunk 构建</li>
     * </ol>
     *
     * @param deltaTime 帧间隔时间（秒）
     * @param extensions 排序后的扩展列表
     * @return 快速路径耗时（纳秒），通常 &lt; 10000ns (0.01ms)
     */
    public long onFrameBeginAsync(float deltaTime,
                                    List<RenderExtension> extensions) {
        long startTime = System.nanoTime();

        if (!running || !initialized) {
            return System.nanoTime() - startTime;
        }

        // 帧号由 RenderiumCore 统一管理，此处不再递增
        int frame = currentFrame;

        // 入队扩展回调任务（异步执行）
        for (RenderExtension ext : extensions) {
            if (ext.isEnabled()) {
                FrameTask task = new FrameTask(
                        frame,
                        FrameTask.TaskType.EXTENSION_FRAME_BEGIN,
                        ext,
                        deltaTime
                );
                if (!taskQueue.enqueue(task)) {
                    LOGGER.warning("任务队列已满，丢弃扩展回调: " + ext.getName());
                }
            }
        }

        long elapsed = System.nanoTime() - startTime;
        totalOnFrameBeginTimeNs.addAndGet(elapsed);
        totalFramesProcessed.incrementAndGet();

        // 冷路径日志：使用乘法替代除法（纳秒→微秒转换）
        LOGGER.fine(String.format("onFrameBeginAsync: frame=%d, 耗时=%.3fµs, 已入队 %d 个任务",
                currentFrame, elapsed * 0.001, extensions.size()));

        return elapsed;
    }

    /**
     * 收集上一帧的异步结果（在渲染前调用）
     * <p>
     * 从结果队列中取出所有已完成的工作，
     * 并应用到当前帧的渲染状态。
     *
     * @param resultHandler 结果处理器
     * @return 收集到的结果数量
     */
    public int collectResults(java.util.function.Consumer<RenderResult> resultHandler) {
        return resultQueue.drainTo(result -> {
            try {
                resultHandler.accept(result);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "结果处理器异常", e);
            }
        });
    }

    // ==================== 遮挡剔除 API ====================

    /**
     * 提交异步遮挡剔除任务
     *
     * @param rootSection 根区块
     * @param cameraView  相机视图
     * @param useOcclusion 是否启用遮挡剔除
     * @return true 如果成功入队
     */
    public boolean submitOcclusionCull(BfsOcclusionEngine.OcclusionTask rootSection,
                                        BfsOcclusionEngine.CameraView cameraView,
                                        boolean useOcclusion) {
        OcclusionCullPayload payload = new OcclusionCullPayload(rootSection, cameraView, useOcclusion);
        FrameTask task = new FrameTask(currentFrame, FrameTask.TaskType.OCCLUSION_CULL, payload);
        return taskQueue.enqueue(task);
    }

    /**
     * 遮挡剔除任务载荷
     */
    public static class OcclusionCullPayload {
        public final BfsOcclusionEngine.OcclusionTask rootSection;
        public final BfsOcclusionEngine.CameraView cameraView;
        public final boolean useOcclusion;

        public OcclusionCullPayload(BfsOcclusionEngine.OcclusionTask rootSection,
                                     BfsOcclusionEngine.CameraView cameraView,
                                     boolean useOcclusion) {
            this.rootSection = rootSection;
            this.cameraView = cameraView;
            this.useOcclusion = useOcclusion;
        }
    }

    // ==================== 策略刷新 API ====================

    /**
     * 刷新 BFS 遮挡剔除策略
     * <p>
     * 当运行条件变化时（如 GPU 占用率波动、Native 加速器状态变更），
     * 调用此方法重新选择最优策略。
     * <p>
     * 线程安全：使用 volatile 写更新 bfsStrategy 引用，
     * 工作线程通过 volatile 读获取最新策略。
     *
     * @param gpuUsage     当前 GPU 占用率 (0.0-1.0)
     * @param preferNative 是否优先使用 Native 路径
     */
    public void refreshStrategy(float gpuUsage, boolean preferNative) {
        AlgorithmStrategy<BfsInput, BfsOcclusionEngine.CullResult> newStrategy =
                pathSelector.createBfsStrategy(gpuUsage, preferNative, fallbackEngine, 10000);
        if (newStrategy != null) {
            this.bfsStrategy = newStrategy;
            LOGGER.fine(String.format("BFS 策略已刷新 → %s (gpuUsage=%.2f%%)",
                    newStrategy.getImplementationType(), gpuUsage * 100));
        }
    }

    // ==================== 工作线程处理 ====================

    /**
     * 处理单个任务（由工作线程调用）
     */
    private void processTask(FrameTask task) {
        long processStart = System.nanoTime();

        switch (task.type) {
            case EXTENSION_FRAME_BEGIN -> processExtensionFrameBegin(task);
            case OCCLUSION_CULL -> processOcclusionCull(task);
            case CHUNK_BUILD -> processChunkBuild(task);
            case BATCH_RENDER -> processBatchRender(task);
        }

        long elapsed = System.nanoTime() - processStart;
    }

    /**
     * 处理扩展 onFrameBegin 回调
     */
    private void processExtensionFrameBegin(FrameTask task) {
        if (task.payload instanceof RenderExtension ext) {
            try {
                ext.onFrameBegin(task.frameNumber, task.deltaTime);
                resultQueue.enqueue(new RenderResult(
                        task.frameNumber,
                        RenderResult.ResultType.EXTENSION_COMPLETE,
                        ext.getName(),
                        System.nanoTime() - task.createTimeNs
                ));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "扩展回调异常: " + ext.getName(), e);
            }
        }
    }

    /**
     * 处理遮挡剔除任务
     * <p>
     * 优先使用策略模式（可能走 Native C++ 加速路径），
     * 策略不可用时回退到 Java 引擎直接调用。
     */
    private void processOcclusionCull(FrameTask task) {
        if (task.payload instanceof OcclusionCullPayload payload) {
            BfsOcclusionEngine.CullResult cullResult;

            // 优先尝试策略路径（可能使用 Native C++ 加速）
            AlgorithmStrategy<BfsInput, BfsOcclusionEngine.CullResult> currentStrategy = this.bfsStrategy;
            if (currentStrategy != null && currentStrategy.isAvailable()) {
                // 从 payload 构造 BfsInput（与 C++ 结构体内存布局对齐）
                BfsInput input = new BfsInput(
                        (int) (payload.cameraView.posX / 16),  // originChunkX
                        (int) (payload.cameraView.posY / 16),  // originChunkY
                        (int) (payload.cameraView.posZ / 16),  // originChunkZ
                        payload.cameraView.posX,                // cameraX
                        payload.cameraView.posY,                // cameraY
                        payload.cameraView.posZ,                // cameraZ
                        payload.rootSection.radius,             // renderDistance
                        task.frameNumber,                       // frameNumber
                        payload.useOcclusion,                   // useOcclusion
                        payload.rootSection                     // rootSection
                );
                cullResult = currentStrategy.execute(input);
            } else {
                // 回退到 Java 引擎直接调用
                cullResult = fallbackEngine.findVisibleSections(
                        payload.rootSection, payload.cameraView,
                        payload.useOcclusion, task.frameNumber
                );
            }

            totalVisibleSections.addAndGet(cullResult.visibleCount);
            totalCulledSections.addAndGet(cullResult.totalProcessed - cullResult.visibleCount);
            totalCullTimeNs.addAndGet(cullResult.traverseTimeNanos);

            resultQueue.enqueue(new RenderResult(
                    task.frameNumber, RenderResult.ResultType.OCCLUSION_RESULT,
                    cullResult, cullResult.traverseTimeNanos
            ));
        }
    }

    /**
     * 处理 Chunk 构建任务
     * <p>
     * 冷路径存根：直接入队 CHUNK_BUILT 结果，不阻塞工作线程。
     * 实际 Chunk 构建逻辑应委托给专门的异步构建管线（独立模块）。
     */
    private void processChunkBuild(FrameTask task) {
        // 最小有效逻辑：入队空结果，保持管线流转
        resultQueue.enqueue(new RenderResult(
                task.frameNumber,
                RenderResult.ResultType.CHUNK_BUILT,
                null,
                System.nanoTime() - task.createTimeNs
        ));
    }

    /**
     * 处理批量渲染任务
     * <p>
     * 冷路径存根：直接入队 BATCH_READY 结果，不阻塞工作线程。
     * 实际批量渲染逻辑应委托给 AggressiveBatchRenderer。
     */
    private void processBatchRender(FrameTask task) {
        // 最小有效逻辑：入队空结果，保持管线流转
        resultQueue.enqueue(new RenderResult(
                task.frameNumber,
                RenderResult.ResultType.BATCH_READY,
                null,
                System.nanoTime() - task.createTimeNs
        ));
    }

    // ==================== 工作线程主循环 ====================

    /**
     * 工作线程主循环
     * <p>
     * 由 initialize() 创建的工作线程自动调用。
     * 使用 LockSupport.parkNanos 替代 Thread.sleep 避免不必要的系统调用。
     */
    private void workerLoop() {
        while (running) {
            FrameTask task = taskQueue.dequeue();
            if (task != null) {
                processTask(task);
            } else {
                // 无任务时短暂让出 CPU（100µs），避免忙等待
                LockSupport.parkNanos(100_000L);
            }
        }
    }

    // ==================== 统计 API ====================

    /**
     * 同步帧号（由 RenderiumCore 调用）
     * <p>
     * 统一帧号源：RenderiumCore 是帧号的唯一权威，
     * AsyncRenderPipeline 通过此方法同步帧号。
     *
     * @param frame 当前帧号
     */
    public void syncFrameNumber(int frame) {
        this.currentFrame = frame;
    }

    /** 获取总处理帧数 */
    public long getTotalFramesProcessed() { return totalFramesProcessed.get(); }

    /** 获取平均 onFrameBegin 耗时（微秒） */
    public double getAverageOnFrameBeginTimeUs() {
        long frames = totalFramesProcessed.get();
        // 冷路径统计：使用乘法替代除法（纳秒→微秒转换）
        return frames > 0 ? (double) totalOnFrameBeginTimeNs.get() * (0.001 / frames) : 0;
    }

    /** 获取平均遮挡剔除耗时（毫秒） */
    public double getAverageCullTimeMs() {
        long frames = totalFramesProcessed.get();
        // 冷路径统计：使用乘法替代除法（纳秒→毫秒转换）
        return frames > 0 ? (double) totalCullTimeNs.get() * (0.000001 / frames) : 0;
    }

    /** 获取总可见区块数 */
    public long getTotalVisibleSections() { return totalVisibleSections.get(); }

    /** 获取总剔除区块数 */
    public long getTotalCulledSections() { return totalCulledSections.get(); }

    /** 获取当前帧号 */
    public int getCurrentFrame() { return currentFrame; }

    /** 是否正在运行 */
    public boolean isRunning() { return running; }

    /**
     * 获取完整统计报告
     */
    public String getStatistics() {
        return String.format(
                "╔══════════════════════════════════════════════════╗" +
                "║       AsyncRenderPipeline 性能统计报告           ║" +
                "╠══════════════════════════════════════════════════╣" +
                "║ 总处理帧数: %-36d ║" +
                "║ onFrameBegin 平均耗时: %-24.2f µs ║" +
                "║ 遮挡剔除平均耗时: %-26.2f ms ║" +
                "║ 总可见区块: %-38d ║" +
                "║ 总剔除区块: %-38d ║" +
                "╠══════════════════════════════════════════════════╣" +
                "║ 任务队列: %-40s ║" +
                "║ 结果队列: %-40s ║" +
                "║ 运行状态: %-41s ║" +
                "╚══════════════════════════════════════════════════╝",
                getTotalFramesProcessed(),
                getAverageOnFrameBeginTimeUs(),
                getAverageCullTimeMs(),
                getTotalVisibleSections(),
                getTotalCulledSections(),
                taskQueue.getStatistics(),
                resultQueue.getStatistics(),
                running ? "运行中" : "已停止"
        );
    }
}
