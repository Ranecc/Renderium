// Renderium - 异步 Chunk 构建管道 (v1)
// 多线程区块网格构建 + 双缓冲结果队列
// 参考: sodium-dev ChunkBuilder + RenderSectionManager 改写
// 设计目标:
//   1. 主线程零阻塞：构建任务完全在工作线程执行
//   2. 帧预算控制：根据帧时间动态调整构建数量
//   3. 优先级队列：近距离/重要区块优先构建
//   4. 结果去重：只保留最新版本的构建结果

package com.ranecc.renderium.feature.pipeline.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 异步 Chunk 构建管道
 * <p>
 * 管理多线程的区块网格（mesh）构建任务，
 * 实现与渲染主线程完全解耦的异步构建流程。
 *
 * <h3>工作流程：</h3>
 * <pre>
 * 渲染线程                    工作线程池                   GPU 上传
 *     │                          │                           │
 *     │── submitBuild() ──────▶│                           │
 *     │                         │── buildMesh() ──────────▶│
 *     │                         │   (多线程并行)              │
 *     │◀── collectResults() ──│◀── BuildResult ──────────│
 *     │   (O(1) 无锁收集)       │                           │
 * </pre>
 *
 * <h3>帧预算控制：</h3>
 * <p>
 * 为避免工作线程抢占过多 CPU 时间导致帧率下降，
 * 每帧限制可提交的任务数量，基于：
 * <ul>
 *   <li>上一帧的实际帧时间</li>
 *   <li>当前工作线程的忙碌程度</li>
 *   <li>上传带宽预算</li>
 * </ul>
 */
public final class AsyncChunkBuildPipeline {

    private static final Logger LOGGER = Logger.getLogger(AsyncChunkBuildPipeline.class.getName());

    // ==================== 配置常量 ====================

    /** 默认最大同时构建任务数 */
    private static final int DEFAULT_MAX_CONCURRENT_BUILDS = 8;

    /** 默认每帧最大上传时间预算（纳秒）= 2ms */
    private static final long DEFAULT_UPLOAD_TIME_BUDGET_NS = 2_000_000L;

    /** 默认上传大小预算（字节）= 256KB */
    private static final long DEFAULT_UPLOAD_SIZE_BUDGET = 256 * 1024;

    /** 构建结果缓存容量 */
    private static final int RESULT_CACHE_CAPACITY = 512;

    // ==================== 核心组件 ====================

    /** 单例实例 */
    private static volatile AsyncChunkBuildPipeline instance;

    /** 构建任务优先级队列（按距离排序） */
    private final PriorityBlockingQueue<BuildTask> taskQueue;

    /** 正在执行的任务集合（用于去重） */
    private final ConcurrentHashMap<Long, BuildTask> activeTasks;

    /** 构建结果缓存（双缓冲：写入/读取） */
    private final LockFreeRingBuffer<BuildResult> resultCache;

    // ==================== 状态字段 ====================

    /** 当前帧号 */
    private volatile int currentFrame = 0;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 最大并发构建数 */
    private int maxConcurrentBuilds = DEFAULT_MAX_CONCURRENT_BUILDS;

    // ==================== 统计字段 ====================

    /** 总提交任务数 */
    private final AtomicLong totalSubmittedTasks = new AtomicLong(0);

    /** 总完成任务数 */
    private final AtomicLong totalCompletedTasks = new AtomicLong(0);

    /** 总取消任务数 */
    private final AtomicLong totalCancelledTasks = new AtomicLong(0);

    /** 因预算不足而延迟的任务数 */
    private final AtomicLong totalDeferredTasks = new AtomicLong(0);

    /** 总上传字节数 */
    private final AtomicLong totalUploadedBytes = new AtomicLong(0);

    /** 当前活跃任务数 */
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);

    // ==================== 内部数据结构 ====================

    /**
     * 构建任务
     */
    public static class BuildTask implements Comparable<BuildTask> {
        /** 区块唯一标识 (chunkX << 40 | chunkY << 20 | chunkZ) */
        public final long sectionKey;

        /** 区块 X 坐标 */
        public final int chunkX;

        /** 区块 Y 坐标 */
        public final int chunkY;

        /** 区块 Z 坐标 */
        public final int chunkZ;

        /** 到相机的距离平方 */
        public final float distanceSquared;

        /** 任务优先级（数值越小越优先） */
        public final int priority;

        /** 任务类型 */
        public final BuildType buildType;

        /** 提交时的帧号 */
        public int submitFrame;

        /** 预估构建耗时（纳秒） */
        public long estimatedDurationNs;

        /** 预估输出大小（字节） */
        public long estimatedOutputSizeBytes;

        /** 是否为阻塞任务（需要本帧完成） */
        public boolean blocking = false;

        /** 创建时间戳 */
        public final long createTimeNs;

        /**
         * 构建类型
         */
        public enum BuildType {
            /** 初始构建（首次加载） */
            INITIAL,
            /** 重建（方块变更后） */
            REBUILD,
            /** 排序更新（半透明面排序） */
            SORT_ONLY
        }

        public BuildTask(long sectionKey, int chunkX, int chunkY, int chunkZ,
                         float distanceSquared, int priority, BuildType buildType) {
            this.sectionKey = sectionKey;
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.distanceSquared = distanceSquared;
            this.priority = priority;
            this.buildType = buildType;
            this.submitFrame = 0;  // 由 pipeline 设置
            this.createTimeNs = System.nanoTime();
        }

        @Override
        public int compareTo(BuildTask other) {
            // 先按优先级排序，再按距离排序
            int cmp = Integer.compare(this.priority, other.priority);
            if (cmp != 0) return cmp;
            return Float.compare(this.distanceSquared, other.distanceSquared);
        }

        /** 生成区段唯一键 */
        public static long makeSectionKey(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 |
                   ((long) y & 0xFFFFF) << 20 |
                   ((long) z & 0xFFFFF);
        }
    }

    /**
     * 构建结果
     */
    public static class BuildResult {
        /** 关联的任务 */
        public final BuildTask task;

        /** 成功标志 */
        public final boolean success;

        /** 输出数据（顶点缓冲 + 索引缓冲引用） */
        public final Object meshData;

        /** 半透明数据（可选） */
        public final Object translucentData;

        /** 构建信息（区块包含的内容类型等） */
        public final Object buildInfo;

        /** 实际构建耗时（纳秒） */
        public final long actualDurationNs;

        /** 实际输出大小（字节） */
        public final long actualOutputSizeBytes;

        /** 完成时的帧号 */
        public final int completionFrame;

        public BuildResult(BuildTask task, boolean success, Object meshData,
                            Object translucentData, Object buildInfo,
                            long durationNs, long outputSizeBytes, int frame) {
            this.task = task;
            this.success = success;
            this.meshData = meshData;
            this.translucentData = translucentData;
            this.buildInfo = buildInfo;
            this.actualDurationNs = durationNs;
            this.actualOutputSizeBytes = outputSizeBytes;
            this.completionFrame = frame;
        }
    }

    /**
     * 帧预算（控制每帧的工作量）
     */
    public static class FrameBudget {
        /** 可用的时间预算（纳秒） */
        public long timeBudgetNs;

        /** 可用的上传大小预算（字节） */
        public long sizeBudgetBytes;

        /** 已使用的时间 */
        public long usedTimeNs = 0;

        /** 已使用的上传大小 */
        public long usedSizeBytes = 0;

        public FrameBudget(long timeBudgetNs, long sizeBudgetBytes) {
            this.timeBudgetNs = timeBudgetNs;
            this.sizeBudgetBytes = sizeBudgetBytes;
        }

        /** 检查是否还有剩余时间预算 */
        public boolean hasTimeRemaining() {
            return usedTimeNs < timeBudgetNs;
        }

        /** 检查是否还有剩余大小预算 */
        public boolean hasSizeRemaining() {
            return usedSizeBytes < sizeBudgetBytes;
        }

        /** 消耗时间预算 */
        public void consumeTime(long ns) {
            usedTimeNs += ns;
        }

        /** 消耗大小预算 */
        public void consumeSize(long bytes) {
            usedSizeBytes += bytes;
        }
    }

    // ==================== 构造函数 ====================

    private AsyncChunkBuildPipeline() {
        this.taskQueue = new PriorityBlockingQueue<>(
                DEFAULT_MAX_CONCURRENT_BUILDS * 4);  // 预分配空间
        this.activeTasks = new ConcurrentHashMap<>();
        this.resultCache = new LockFreeRingBuffer<>(RESULT_CACHE_CAPACITY);

        LOGGER.info("AsyncChunkBuildPipeline 初始化完成");
    }

    // ==================== 单例访问 ====================

    public static AsyncChunkBuildPipeline getInstance() {
        if (instance == null) {
            synchronized (AsyncChunkBuildPipeline.class) {
                if (instance == null) {
                    instance = new AsyncChunkBuildPipeline();
                }
            }
        }
        return instance;
    }

    // ==================== 生命周期管理 ====================

    /**
     * 初始化构建管道
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.info("AsyncChunkBuildPipeline 已启动");
    }

    /**
     * 关闭构建管道
     */
    public synchronized void shutdown() {
        if (!initialized) {
            return;
        }

        // 取消所有活跃任务
        for (BuildTask task : activeTasks.values()) {
            totalCancelledTasks.incrementAndGet();
        }
        activeTasks.clear();
        taskQueue.clear();

        initialized = false;

        LOGGER.info(String.format(
                "AsyncChunkBuildPipeline 已关闭 [submitted=%d, completed=%d, cancelled=%d]",
                totalSubmittedTasks.get(), totalCompletedTasks.get(),
                totalCancelledTasks.get()));
    }

    // ==================== 核心 API ====================

    /**
     * 提交构建任务
     *
     * @param task 构建任务
     * @return true 如果成功入队，false 如果任务重复或队列满
     */
    public boolean submitBuild(BuildTask task) {
        if (!initialized || task == null) {
            return false;
        }

        // 去重检查：如果同一区块已有活跃任务，跳过
        BuildTask existing = activeTasks.putIfAbsent(task.sectionKey, task);
        if (existing != null) {
            // 同一区块已有任务在执行，不重复提交
            return false;
        }

        task.submitFrame = currentFrame;
        taskQueue.offer(task);
        totalSubmittedTasks.incrementAndGet();
        activeTaskCount.incrementAndGet();

        LOGGER.fine(String.format("提交构建任务: [%d,%d,%d] type=%s priority=%d",
                task.chunkX, task.chunkY, task.chunkZ,
                task.buildType.name(), task.priority));

        return true;
    }

    /**
     * 处理待构建任务（由工作线程调用）
     * <p>
     * 从优先级队列中取出任务并执行构建。
     *
     * @param budget 帧预算
     * @return 本批次处理的任务数
     */
    public int processPendingBuilds(FrameBudget budget) {
        int processed = 0;

        while (!taskQueue.isEmpty()
                && activeTaskCount.get() < maxConcurrentBuilds
                && budget.hasTimeRemaining()) {
            BuildTask task = taskQueue.poll();
            if (task == null) break;

            // 执行构建
            long startTime = System.nanoTime();
            BuildResult result = executeBuild(task);
            long elapsed = System.nanoTime() - startTime;

            // 更新统计
            budget.consumeTime(elapsed);
            if (result.actualOutputSizeBytes > 0) {
                budget.consumeSize(result.actualOutputSizeBytes);
            }

            // 存储结果
            resultCache.enqueue(result);

            // 清除活跃标记
            activeTasks.remove(task.sectionKey);
            activeTaskCount.decrementAndGet();
            totalCompletedTasks.incrementAndGet();
            totalUploadedBytes.addAndGet(result.actualOutputSizeBytes);

            processed++;
        }

        // 记录被延迟的任务数
        if (!taskQueue.isEmpty() && !budget.hasTimeRemaining()) {
            totalDeferredTasks.addAndGet(taskQueue.size());
        }

        return processed;
    }

    /**
     * 收集构建结果（由渲染线程调用）
     *
     * @param processor 结果处理器
     * @return 收集到的结果数
     */
    public int collectResults(java.util.function.Consumer<BuildResult> processor) {
        return resultCache.drainTo(result -> {
            try {
                processor.accept(result);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "构建结果处理器异常", e);
            }
        });
    }

    // ==================== 构建执行 ====================

    /**
     * 执行单个构建任务
     * <p>
     * 冷路径存根：移除 Thread.sleep 模拟，直接返回空结果，
     * 避免阻塞工作线程。实际构建逻辑待集成 ChunkBuilder。
     */
    private BuildResult executeBuild(BuildTask task) {
        long startTime = System.nanoTime();

        try {
            // 冷路径存根：直接返回空 BuildResult，不阻塞工作线程
            // 实际构建逻辑应调用 ChunkBuilderMeshingTask 执行网格化
            LOGGER.log(Level.FINEST, String.format(
                    "构建存根: [%d,%d,%d] type=%s（等待实际 ChunkBuilder 集成）",
                    task.chunkX, task.chunkY, task.chunkZ, task.buildType.name()));

            return new BuildResult(
                    task,
                    false, // 存根返回失败，避免下游误用空 meshData
                    null,  // meshData
                    null,  // translucentData
                    null,  // buildInfo
                    System.nanoTime() - startTime,
                    0,     // 存根无输出
                    currentFrame
            );
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    String.format("构建异常: [%d,%d,%d]", task.chunkX, task.chunkY, task.chunkZ), e);
            return new BuildResult(task, false, null, null, null,
                    System.nanoTime() - startTime, 0, currentFrame);
        }
    }

    // ==================== 帧管理 ====================

    /**
     * 新帧开始（递增帧号）
     */
    public void beginFrame(int frameNumber) {
        this.currentFrame = frameNumber;
    }

    /**
     * 获取当前帧号
     */
    public int getCurrentFrame() { return currentFrame; }

    // ==================== 统计 API ====================

    /** 获取总提交任务数 */
    public long getTotalSubmittedTasks() { return totalSubmittedTasks.get(); }

    /** 获取总完成任务数 */
    public long getTotalCompletedTasks() { return totalCompletedTasks.get(); }

    /** 获取总取消任务数 */
    public long getTotalCancelledTasks() { return totalCancelledTasks.get(); }

    /** 获取总延迟任务数 */
    public long getTotalDeferredTasks() { return totalDeferredTasks.get(); }

    /** 获取当前活跃任务数 */
    public int getActiveTaskCount() { return activeTaskCount.get(); }

    /** 获取待处理队列大小 */
    public int getPendingQueueSize() { return taskQueue.size(); }

    /** 获取总上传字节数 */
    public long getTotalUploadedBytes() { return totalUploadedBytes.get(); }

    /**
     * 获取格式化统计报告
     */
    public String getStatistics() {
        return String.format(
                "AsyncChunkBuildPipeline{submitted=%d, completed=%d, cancelled=%d, deferred=%d, active=%d, pending=%d, uploaded=%.1fKB}",
                getTotalSubmittedTasks(),
                getTotalCompletedTasks(),
                getTotalCancelledTasks(),
                getTotalDeferredTasks(),
                getActiveTaskCount(),
                getPendingQueueSize(),
                getTotalUploadedBytes() / 1024.0
        );
    }
}
