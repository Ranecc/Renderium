// Renderium v6 Phase 3: 异步区块加载器系统
// AsyncChunkLoader.java - 后台异步区块加载器
// 功能: 使用线程池在后台预加载远处区块，提供给 VoxyInspiredLODSystem 使用

package com.renderium.lod.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.renderium.lod.async.ChunkLoadPriorityQueue;

/**
 * 后台异步区块加载器。
 *
 * <p>为超视距 LOD 系统提供高效的异步区块数据供给，
 * 使用独立线程池在后台预加载远处区块，避免主线程阻塞。</p>
 *
 * <h2>系统架构：</h2>
 * <pre>
 * ┌──────────┐     ┌──────────────┐     ┌──────────────┐
 * │ Camera   │ ──→ │ PriorityQueue│ ──→ │ Thread Pool  │
 * │ Move     │     │ (按优先级)    │     │ (N threads)  │
 * └──────────┘     └──────────────┘     └──────┬───────┘
 *                                             │
 *                                    ┌────────▼────────┐
 *                                    │ Decompressor    │
 *                                    │ (LZ4/Zstd)      │
 *                                    └────────┬────────┘
 *                                             │
 *                                    ┌────────▼────────┐
 *                                    │ Completed Queue │
 *                                    │ (线程安全)      │
 *                                    └────────┬────────┘
 *                                             │
 *                                    ┌────────▼────────┐
 *                                    │ VoxyInspiredLOD  │
 *                                    │ System.consume()│
 *                                    └────────────────┘
 * </pre>
 *
 * <h2>性能目标：</h2>
 * <ul>
 *   <li>单区块加载时间 &lt;10ms（含解压）</li>
 *   <li>优先级更新 &lt;0.1ms（for 1000 tasks in queue）</li>
 *   <li>内存管理开销 &lt;1% of budget</li>
 *   <li>支持 10000+ 并发任务</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建配置
 * AsyncChunkLoader.Config config = AsyncChunkLoader.Config.builder()
 *     .threadCount(4)
 *     .queueCapacity(10000)
 *     .memoryBudgetBytes(2L * 1024 * 1024 * 1024)
 *     .compressionType("lz4")
 *     .build();
 *
 * // 创建并启动加载器
 * AsyncChunkLoader loader = new AsyncChunkLoader(config);
 * loader.start();
 *
 * // 注册内存淘汰回调
 * loader.getMemoryManager().registerCallback(suggestedCount -> {
 *     return lodSystem.evictChunks(suggestedCount);
 * });
 *
 * // 请求加载区块
 * loader.requestLoad(chunkX, chunkZ, priority);
 *
 * // 每帧更新优先级
 * loader.updatePriorities(cameraPos, cameraDir);
 *
 * // 消费已完成的区块
 * List<LoadedChunkData> completed = loader.pollCompleted();
 * for (LoadedChunkData data : completed) {
 *     lodSystem.applyChunkData(data);
 * }
 *
 * // 关闭时等待完成
 * loader.shutdown(true, 5000);
 * }</pre>
 *
 * @see ChunkLoadPriorityQueue 优先级队列
 * @see MemoryBudgetManager 内存管理
 * @since 6.0.0
 */
public class AsyncChunkLoader {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(AsyncChunkLoader.class.getName());

    /** 默认线程数 */
    public static final int DEFAULT_THREAD_COUNT = 4;

    /** 默认队列容量 */
    public static final int DEFAULT_QUEUE_CAPACITY = 10000;

    /** 默认内存预算 (2GB) */
    public static final long DEFAULT_MEMORY_BUDGET = 2L * 1024 * 1024 * 1024;

    /** 默认关闭超时（毫秒） */
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 5000;

    /** 区块大小（字节，用于估算） */
    private static final int ESTIMATED_CHUNK_SIZE_BYTES = 64 * 1024; // 64KB per chunk

    // ==================== 配置类 ====================

    /**
     * 加载器配置参数。
     *
     * <p>使用 Builder 模式创建配置实例。</p>
     */
    public static class Config {
        /** 后台工作线程数量 */
        public int threadCount = DEFAULT_THREAD_COUNT;
        /** 队列最大容量 */
        public int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        /** 内存预算（字节） */
        public long memoryBudgetBytes = DEFAULT_MEMORY_BUDGET;
        /** 是否启用解压 */
        public boolean enableDecompression = true;
        /** 压缩类型："lz4" | "zstd" | "none" */
        public String compressionType = "lz4";
        /** 距离权重 */
        public float priorityDistanceWeight = 1.0f;
        /** 视角权重 */
        public float priorityAngleWeight = 0.5f;
        /** 陈旧度权重 */
        public float priorityStaleWeight = 0.3f;

        /**
         * 构建器模式。
         */
        public static class Builder {
            private final Config config = new Config();

            public Builder threadCount(int count) { config.threadCount = count; return this; }
            public Builder queueCapacity(int capacity) { config.queueCapacity = capacity; return this; }
            public Builder memoryBudgetBytes(long bytes) { config.memoryBudgetBytes = bytes; return this; }
            public Builder enableDecompression(boolean enable) { config.enableDecompression = enable; return this; }
            public Builder compressionType(String type) { config.compressionType = type; return this; }
            public Builder priorityDistanceWeight(float weight) { config.priorityDistanceWeight = weight; return this; }
            public Builder priorityAngleWeight(float weight) { config.priorityAngleWeight = weight; return this; }
            public Builder priorityStaleWeight(float weight) { config.priorityStaleWeight = weight; return this; }

            /**
             * 构建配置对象。
             *
             * @return 验证后的配置实例
             * @throws IllegalStateException 如果参数无效
             */
            public Config build() {
                validate();
                return config;
            }

            private void validate() {
                if (config.threadCount <= 0 || config.threadCount > 32) {
                    throw new IllegalArgumentException(
                        "线程数必须在 1-32 范围内，当前值: " + config.threadCount);
                }
                if (config.queueCapacity <= 0) {
                    throw new IllegalArgumentException("队列容量必须大于 0");
                }
                if (config.memoryBudgetBytes <= 0) {
                    throw new IllegalArgumentException("内存预算必须大于 0");
                }
                if (!config.compressionType.matches("(?i)(lz4|zstd|none)")) {
                    throw new IllegalArgumentException(
                        "不支持的压缩类型: " + config.compressionType + "，支持: lz4, zstd, none");
                }
                if (config.priorityDistanceWeight < 0 || config.priorityAngleWeight < 0 ||
                    config.priorityStaleWeight < 0) {
                    throw new IllegalArgumentException("权重不能为负数");
                }
            }
        }

        public static Builder builder() { return new Builder(); }
    }

    // ==================== 内部数据结构 ====================

    /**
     * 已加载的区块数据。
     *
     * <p>包含原始压缩数据和可选的解压后数据。</p>
     */
    public static class LoadedChunkData {
        /** 区块 X 坐标 */
        public final int chunkX;
        /** 区块 Z 坐标 */
        public final int chunkZ;
        /** 建议的初始 LOD 级别（基于距离计算） */
        public final int lodLevel;
        /** 压缩数据（如果启用压缩） */
        public final byte[] compressedData;
        /** 解压后的区块段数据（可能为 null 如果未启用解压） */
        public final Object sectionData;
        /** 加载耗时（纳秒） */
        public final long loadTimeNanos;
        /** 数据大小（字节） */
        public final int dataSizeBytes;

        /**
         * 创建已加载数据。
         *
         * @param chunkX 区块 X
         * @param chunkZ 区块 Z
         * @param lodLevel LOD 级别
         * @param compressedData 压缩数据
         * @param sectionData 解压后的段数据
         * @param loadTimeNanos 加载耗时
         * @param dataSizeBytes 数据大小
         */
        public LoadedChunkData(int chunkX, int chunkZ, int lodLevel,
                               byte[] compressedData, Object sectionData,
                               long loadTimeNanos, int dataSizeBytes) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.lodLevel = lodLevel;
            this.compressedData = compressedData;
            this.sectionData = sectionData;
            this.loadTimeNanos = loadTimeNanos;
            this.dataSizeBytes = dataSizeBytes;
        }

        @Override
        public String toString() {
            return String.format("LoadedChunkData[%d,%d] lod=%d size=%dB time=%.2fms",
                chunkX, chunkZ, lodLevel, dataSizeBytes, loadTimeNanos / 1_000_000.0);
        }
    }

    // ==================== 核心字段 ====================

    /** 配置参数 */
    private final Config config;

    /** 优先级队列 */
    private final ChunkLoadPriorityQueue priorityQueue;

    /** 内存预算管理器 */
    private final MemoryBudgetManager memoryManager;

    /** 工作线程池 */
    private ExecutorService threadPool;

    /** 已完成的区块队列（线程安全） */
    private final BlockingQueue<LoadedChunkData> completedQueue;

    /** 是否已启动 */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** 是否正在关闭 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // ==================== 统计字段 ====================

    /** 总提交任务数 */
    private final AtomicLong totalSubmitted = new AtomicLong(0);

    /** 总完成任务数 */
    private final AtomicLong totalCompleted = new AtomicLong(0);

    /** 总失败任务数 */
    private final AtomicLong totalFailed = new AtomicLong(0);

    /** 总加载时间（纳秒） */
    private final AtomicLong totalLoadTimeNanos = new AtomicLong(0);

    /** 最大单次加载时间（纳秒） */
    private final AtomicLong maxLoadTimeNanos = new AtomicLong(0);

    /** 当前活跃工作线程数 */
    private final AtomicInteger activeThreadCount = new AtomicInteger(0);

    // ==================== 构造函数 ====================

    /**
     * 创建异步区块加载器实例。
     *
     * @param config 加载器配置（不能为 null）
     * @throws NullPointerException 如果 config 为 null
     */
    public AsyncChunkLoader(Config config) {
        if (config == null) {
            throw new NullPointerException("配置不能为 null");
        }

        this.config = config;

        // 创建优先级队列（使用配置的权重）
        ChunkLoadPriorityQueue.QueueConfig queueConfig =
            ChunkLoadPriorityQueue.QueueConfig.builder()
                .distanceWeight(config.priorityDistanceWeight)
                .angleWeight(config.priorityAngleWeight)
                .staleWeight(config.priorityStaleWeight)
                .build();

        this.priorityQueue = new ChunkLoadPriorityQueue(config.queueCapacity, queueConfig);

        // 创建内存管理器
        this.memoryManager = new MemoryBudgetManager(config.memoryBudgetBytes);

        // 创建已完成队列
        this.completedQueue = new LinkedBlockingQueue<>();

        LOGGER.info(String.format(
            "AsyncChunkLoader 创建完成 - 线程数: %d, 队列容量: %d, 内存预算: %d MB, 压缩: %s",
            config.threadCount, config.queueCapacity,
            config.memoryBudgetBytes / (1024 * 1024), config.compressionType));
    }

    // ==================== 生命周期方法 ====================

    /**
     * 启动加载器（初始化线程池）。
     *
     * <p><b>重要：</b>必须在使用前调用此方法。</p>
     *
     * @throws IllegalStateException 如果已经启动或正在关闭
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("AsyncChunkLoader 已经启动或正在运行");
        }

        // 创建固定大小的线程池
        threadPool = Executors.newFixedThreadPool(config.threadCount, new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AsyncChunkLoader-Worker-" + threadNumber.getAndIncrement());
                t.setDaemon(true); // 设置为守护线程，不阻止 JVM 退出
                t.setPriority(Thread.NORM_PRIORITY - 1); // 略低于正常优先级
                return t;
            }
        });

        // 启动工作线程
        for (int i = 0; i < config.threadCount; i++) {
            threadPool.submit(this::workerLoop);
        }

        LOGGER.info(String.format("AsyncChunkLoader 已启动 - 工作线程数: %d", config.threadCount));
    }

    /**
     * 关闭加载器。
     *
     * <p>优雅关闭：停止接受新任务，等待进行中的任务完成。</p>
     *
     * @param waitForCompletion 是否等待进行中的任务完成
     * @param timeoutMs 等待超时（毫秒），0 表示无限等待
     */
    public void shutdown(boolean waitForCompletion, long timeoutMs) {
        if (!started.get() || !shuttingDown.compareAndSet(false, true)) {
            return; // 未启动或已在关闭中
        }

        LOGGER.info(String.format("AsyncChunkLoader 开始关闭 - 等待完成: %b, 超时: %d ms",
            waitForCompletion, timeoutMs));

        // 通知优先级队列关闭
        priorityQueue.shutdown();

        // 清空待处理队列
        int cancelled = priorityQueue.clearAllPending();
        if (cancelled > 0) {
            LOGGER.info("取消了 " + cancelled + " 个待处理任务");
        }

        if (waitForCompletion && threadPool != null) {
            try {
                // 停止接受新任务
                threadPool.shutdown();

                if (timeoutMs > 0) {
                    // 有超时的等待
                    if (!threadPool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                        LOGGER.warning("关闭超时，强制终止剩余任务");
                        threadPool.shutdownNow();
                    }
                } else {
                    // 无限等待
                    threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, "关闭过程被中断", e);
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } else if (threadPool != null) {
            // 不等待，立即关闭
            threadPool.shutdownNow();
        }

        // 关闭内存管理器
        memoryManager.shutdown();

        LOGGER.info(String.format(
            "AsyncChunkLoader 已关闭 - 完成: %d, 失败: %d, 平均耗时: %.2fms",
            totalCompleted.get(), totalFailed.get(),
            getStatistics().avgLoadTimeMs()));
    }

    // ==================== 加载请求方法 ====================

    /**
     * 请求加载指定坐标的区块。
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @param priority 初始优先级（数值越小越优先）
     * @return true 如果成功加入队列
     */
    public boolean requestLoad(int chunkX, int chunkZ, int priority) {
        if (shuttingDown.get()) {
            return false;
        }

        // 创建新任务
        ChunkLoadPriorityQueue.LoadTask task =
            new ChunkLoadPriorityQueue.LoadTask(chunkX, chunkZ, priority);

        // 提交到优先级队列
        boolean submitted = priorityQueue.submit(task);
        if (submitted) {
            totalSubmitted.incrementAndGet();
            LOGGER.finest(String.format("请求加载区块 [%d,%d] 优先级=%d", chunkX, chunkZ, priority));
        }

        return submitted;
    }

    /**
     * 批量请求加载多个区块。
     *
     * @param coords 区块坐标列表 [][2] = {{x,z}, ...}
     * @param basePriority 基础优先级
     * @return 成功提交的任务数量
     */
    public int requestLoadBatch(int[][] coords, int basePriority) {
        if (coords == null || coords.length == 0) {
            return 0;
        }

        int submittedCount = 0;
        for (int i = 0; i < coords.length; i++) {
            if (coords[i] != null && coords[i].length >= 2) {
                // 根据索引微调优先级（保持相对顺序）
                int priority = basePriority + i;
                if (requestLoad(coords[i][0], coords[i][1], priority)) {
                    submittedCount++;
                }
            }
        }

        if (submittedCount > 0) {
            LOGGER.fine(String.format("批量请求完成 - 提交: %d/%d", submittedCount, coords.length));
        }

        return submittedCount;
    }

    /**
     * 取消指定坐标的加载请求。
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return true 如果成功取消
     */
    public boolean cancelRequest(int chunkX, int chunkZ) {
        return priorityQueue.cancel(chunkX, chunkZ);
    }

    /**
     * 取消所有待处理请求。
     *
     * @return 被取消的数量
     */
    public int cancelAllRequests() {
        return priorityQueue.clearAllPending();
    }

    // ==================== 优先级管理 ====================

    /**
     * 根据相机位置和方向重新计算所有请求的优先级。
     *
     * <p>应每帧或定期调用以保持队列顺序最优。</p>
     *
     * @param cameraPosition 相机位置 [x, y, z]
     * @param cameraDirection 相机朝向向量（归一化）
     * @return 更新的任务数量
     */
    public int updatePriorities(float[] cameraPosition, float[] cameraDirection) {
        return priorityQueue.updatePriorities(cameraPosition, cameraDirection);
    }

    /**
     * 获取当前队列状态。
     *
     * @return 队列状态快照
     */
    public ChunkLoadPriorityQueue.QueueStatus getQueueStatus() {
        return priorityQueue.getStatus();
    }

    // ==================== 结果获取方法 ====================

    /**
     * 获取已完成的区块数据（非阻塞）。
     *
     * @return 已完成的区块列表（可能为空，但不会为 null）
     */
    public List<LoadedChunkData> pollCompleted() {
        List<LoadedChunkData> results = new ArrayList<>();
        completedQueue.drainTo(results);
        return results;
    }

    /**
     * 等待下一个完成的区块（阻塞）。
     *
     * @param timeoutMs 超时时间（毫秒），0 表示无限等待
     * @return 已完成的区块数据，超时返回 null
     * @throws InterruptedException 如果线程被中断
     */
    public LoadedChunkData waitForNext(long timeoutMs) throws InterruptedException {
        if (timeoutMs > 0) {
            return completedQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } else {
            return completedQueue.take(); // 无限等待
        }
    }

    // ==================== 统计与监控 ====================

    /**
     * 获取加载器统计信息。
     *
     * @return 统计快照
     */
    public LoaderStatistics getStatistics() {
        long completed = totalCompleted.get();
        long totalTime = totalLoadTimeNanos.get();

        double avgLoadTimeMs = completed > 0 ? (totalTime / completed) / 1_000_000.0 : 0.0;
        double maxLoadTimeMs = maxLoadTimeNanos.get() / 1_000_000.0;

        // 计算吞吐量（基于最近的数据）
        double throughput = calculateThroughput(completed, totalTime);

        return new LoaderStatistics(
            totalSubmitted.get(),
            completed,
            totalFailed.get(),
            avgLoadTimeMs,
            maxLoadTimeMs,
            throughput,
            activeThreadCount.get()
        );
    }

    /**
     * 重置统计数据。
     */
    public void resetStatistics() {
        totalSubmitted.set(0);
        totalCompleted.set(0);
        totalFailed.set(0);
        totalLoadTimeNanos.set(0);
        maxLoadTimeNanos.set(0);
        LOGGER.info("统计数据已重置");
    }

    /**
     * 加载器统计信息记录。
     */
    public record LoaderStatistics(
        long totalTasksSubmitted,
        long totalTasksCompleted,
        long totalTasksFailed,
        double avgLoadTimeMs,
        double maxLoadTimeMs,
        double throughputChunksPerSec,
        int currentThreadUtilization
    ) {}

    // ==================== 访问器方法 ====================

    /**
     * 获取内存预算管理器。
     *
     * @return 内存管理器实例
     */
    public MemoryBudgetManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取优先级队列。
     *
     * @return 优先级队列实例
     */
    public ChunkLoadPriorityQueue getPriorityQueue() {
        return priorityQueue;
    }

    /**
     * 判断是否已启动。
     *
     * @return true 如果已启动
     */
    public boolean isRunning() {
        return started.get() && !shuttingDown.get();
    }

    /**
     * 判断是否正在关闭。
     *
     * @return true 如果正在关闭
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    // ==================== 内部工作方法 ====================

    /**
     * 工作线程主循环。
     *
     * <p>每个工作线程执行此循环，从优先级队列获取任务并处理。</p>
     */
    private void workerLoop() {
        LOGGER.fine(Thread.currentThread().getName() + " 工作线程启动");

        try {
            while (!shuttingDown.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 从优先级队列获取下一个任务（阻塞等待）
                    ChunkLoadPriorityQueue.LoadTask task = priorityQueue.take();

                    // 检查任务是否已完成（使用 state 字段的 ordinal 值）
                    if (task == null || task.state.ordinal() != 2) {  // 2 = COMPLETED
                        continue;
                    }

                    // 更新活跃线程计数
                    activeThreadCount.incrementAndGet();

                    try {
                        // 执行实际的加载操作
                        LoadedChunkData result = executeLoad(task);

                        if (result != null) {
                            // 放入已完成队列
                            completedQueue.offer(result);

                            // 更新统计
                            totalCompleted.incrementAndGet();
                            long loadTime = result.loadTimeNanos;
                            totalLoadTimeNanos.addAndGet(loadTime);

                            // 更新最大值（CAS 保证原子性）
                            updateMaxLoadTime(loadTime);

                            // 释放内存预算（如果之前分配了）
                            memoryManager.release(result.dataSizeBytes);

                            LOGGER.finest(String.format("区块加载完成 [%d,%d] 耗时=%.2fms",
                                task.chunkX, task.chunkZ, loadTime / 1_000_000.0));

                            // 标记队列为已完成
                            priorityQueue.markCompleted(task);
                        } else {
                            // 加载失败
                            totalFailed.incrementAndGet();
                            priorityQueue.markFailed(task);
                            LOGGER.warning(String.format("区块加载失败 [%d,%d]", task.chunkX, task.chunkZ));
                        }
                    } finally {
                        activeThreadCount.decrementAndGet();
                    }

                } catch (InterruptedException e) {
                    // 正常的中断（关闭时）
                    LOGGER.fine(Thread.currentThread().getName() + " 收到中断信号");
                    break;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "工作线程发生未预期异常", e);
                    // 继续运行，不要因为单个任务失败而退出
                }
            }
        } finally {
            LOGGER.fine(Thread.currentThread().getName() + " 工作线程退出");
        }
    }

    /**
     * 执行单个区块的加载操作。
     *
     * <p>这是实际的数据加载逻辑，包括：</p>
     * <ol>
     *   <li>检查内存预算</li>
     *   <li>读取区块数据（模拟 I/O）</li>
     *   <li>解压（如果启用）</li>
     *   <li>封装结果</li>
     * </ol>
     *
     * @param task 要处理的加载任务
     * @return 加载后的数据，失败返回 null
     */
    private LoadedChunkData executeLoad(ChunkLoadPriorityQueue.LoadTask task) {
        long startTime = System.nanoTime();

        try {
            // 1. 估算所需内存并尝试分配
            int estimatedSize = ESTIMATED_CHUNK_SIZE_BYTES;
            if (!memoryManager.allocate(estimatedSize)) {
                // 内存不足，触发淘汰
                memoryManager.checkAndEvict();

                // 再次尝试分配
                if (!memoryManager.allocate(estimatedSize)) {
                    LOGGER.warning(String.format("内存不足，跳过区块 [%d,%d]", task.chunkX, task.chunkZ));
                    return null;
                }
            }

            // 2. 模拟区块数据加载（实际应用中这里会调用文件 I/O 或网络请求）
            byte[] rawData = simulateChunkIO(task.chunkX, task.chunkZ);

            if (rawData == null) {
                memoryManager.release(estimatedSize);
                return null;
            }

            // 3. 解压（如果启用）
            Object sectionData = null;
            if (config.enableDecompression && !"none".equalsIgnoreCase(config.compressionType)) {
                sectionData = decompressData(rawData, config.compressionType);
            }

            // 4. 计算 LOD 级别（基于距离）
            int lodLevel = calculateLODLevel(task.getDistanceToCamera());

            // 5. 封装结果
            long loadTime = System.nanoTime() - startTime;

            return new LoadedChunkData(
                task.chunkX,
                task.chunkZ,
                lodLevel,
                rawData,           // 压缩数据
                sectionData,       // 解压后的数据
                loadTime,
                estimatedSize
            );

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, String.format("加载区块 [%d,%d] 时发生异常",
                task.chunkX, task.chunkZ), e);
            return null;
        }
    }

    /**
     * 模拟区块 I/O 操作。
     *
     * <p><b>注意：</b>这是一个占位实现，实际应用中应替换为真实的
     * 文件读取、数据库查询或网络请求。</p>
     *
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @return 模拟的区块数据字节数组
     */
    private byte[] simulateChunkIO(int chunkX, int chunkZ) {
        // 模拟 I/O 延迟（1-5ms）
        try {
            Thread.sleep(1 + (long)(Math.random() * 4));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        // 生成模拟数据（基于坐标的伪随机数据）
        int dataSize = ESTIMATED_CHUNK_SIZE_BYTES + (int)((Math.abs(chunkX * 31 + chunkZ * 17) % 32) * 1024);
        byte[] data = new byte[dataSize];

        // 使用简单的伪随机填充（基于坐标）
        int seed = chunkX * 31 + chunkZ * 17 + 7;
        for (int i = 0; i < dataSize; i++) {
            seed = seed * 1103515245 + 12345;
            data[i] = (byte) ((seed >> 16) & 0xFF);
        }

        return data;
    }

    /**
     * 解压数据（模拟实现）。
     *
     * <p><b>注意：</b>这是一个占位实现，实际应用中应集成
     * LZ4 或 Zstd 库。</p>
     *
     * @param compressedData 压缩数据
     * @param compressionType 压缩类型
     * @return 解压后的数据对象
     */
    private Object decompressData(byte[] compressedData, String compressionType) {
        // 模拟解压延迟（0.5-2ms）
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        // 实际应用中：
        // if ("lz4".equalsIgnoreCase(compressionType)) {
        //     return LZ4Factory.fastestJavaInstance().decompress(compressedData);
        // } else if ("zstd".equalsIgnoreCase(compressionType)) {
        //     return Zstd.decompress(compressedData);
        // }

        // 返回一个标记对象表示已解压
        return new DecompressedSection(compressedData.length * 3); // 假设压缩比 3:1
    }

    /**
     * 解压后的区块段数据（内部类）。
     */
    private static class DecompressedSection {
        final int uncompressedSize;

        DecompressedSection(int size) {
            this.uncompressedSize = size;
        }
    }

    /**
     * 根据距离计算建议的 LOD 级别。
     *
     * <p>距离越远，LOD 级别越高（细节越少）。</p>
     *
     * @param distance 到相机的距离（区块单位）
     * @return LOD 级别 (0 = 最高质量, 15 = 最低质量)
     */
    private int calculateLODLevel(float distance) {
        // 使用对数映射：近距离变化敏感，远距离平缓
        // distance=0 → lod=0, distance=256 → lod≈8, distance≥512 → lod=15
        if (distance <= 1.0f) return 0;

        float logDist = (float) Math.log(distance);
        int lod = (int) (logDist * 2.5f);

        return Math.min(15, Math.max(0, lod));
    }

    /**
     * 更新最大加载时间（CAS 操作）。
     *
     * @param newValue 新的时间值
     */
    private void updateMaxLoadTime(long newValue) {
        long oldValue;
        do {
            oldValue = maxLoadTimeNanos.get();
            if (newValue <= oldValue) break; // 不是新的最大值
        } while (!maxLoadTimeNanos.compareAndSet(oldValue, newValue));
    }

    /**
     * 计算吞吐量（chunks/sec）。
     *
     * @param completed 完成总数
     * @param totalTimeNs 总时间（纳秒）
     * @return 吞吐量
     */
    private double calculateThroughput(long completed, long totalTimeNs) {
        if (completed == 0 || totalTimeNs == 0) return 0.0;
        return (double) completed / (totalTimeNs / 1_000_000_000.0);
    }

    /**
     * 生成状态报告字符串。
     *
     * @return 格式化的状态信息
     */
    public String getStatusReport() {
        LoaderStatistics stats = getStatistics();
        ChunkLoadPriorityQueue.QueueStatus queueStatus = getQueueStatus();

        return String.format(
            "AsyncChunkLoader [运行: %b | 线程: %d/%d | 队列: %d(%d pending) | " +
            "完成: %d 失败: %d | 平均: %.2fms 最大: %.2fms | 吞吐: %.1f chunks/s | 内存: %s]",
            isRunning(),
            stats.currentThreadUtilization(), config.threadCount,
            queueStatus.totalCount(), queueStatus.pendingCount(),
            stats.totalTasksCompleted(), stats.totalTasksFailed(),
            stats.avgLoadTimeMs(), stats.maxLoadTimeMs(),
            stats.throughputChunksPerSec(),
            memoryManager.getStatusReport());
    }

    @Override
    public String toString() {
        return getStatusReport();
    }
}
