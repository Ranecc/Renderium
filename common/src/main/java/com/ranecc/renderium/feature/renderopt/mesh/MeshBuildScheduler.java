// Renderium - 多线程网格构建调度器
// 管理 Worker 线程池，调度 MeshBuildTask，收集构建结果
// 兼容模式和狂暴模式共用

package com.ranecc.renderium.feature.renderopt.mesh;

import com.ranecc.renderium.None;
import com.ranecc.renderium.domain.model.MeshBuildResult;
import com.ranecc.renderium.feature.blaze3d.modern.MeshData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 多线程网格构建调度器。
 *
 * <p>管理一组 Worker 线程，将区块网格构建任务分发到多个线程并行执行。
 * 构建完成后，结果由主线程收集并提交到渲染队列。
 *
 * <h2>架构</h2>
 * <pre>
 * 主线程 (Render Thread)
 *     ↓ 提交 MeshBuildTask
 * MeshBuildScheduler (任务队列 + 线程池)
 *     ↓ 分发到 Worker
 * Worker-0 [ChunkMeshBuilder]  →  MeshData
 * Worker-1 [ChunkMeshBuilder]  →  MeshData
 * Worker-2 [ChunkMeshBuilder]  →  MeshData
 *     ↑ 结果入队
 * 主线程 (poll 结果 → 上传 GPU)
 * </pre>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>每个 Worker 持有自己的 {@link ChunkMeshBuilder} 实例，无竞争</li>
 *   <li>任务队列使用 {@link ConcurrentLinkedQueue}，无锁并发</li>
 *   <li>结果队列使用 {@link ConcurrentLinkedQueue}，主线程无锁 poll</li>
 *   <li>统计计数器使用 {@link AtomicLong}，线程安全</li>
 *   <li>shutdown 状态使用 volatile 确保可见性</li>
 * </ul>
 *
 * <h2>双模式共用</h2>
 * <p>兼容模式下：构建 Vulkan 后处理所需的辅助网格（如 LOD 简化网格）<br>
 * 狂暴模式下：构建完整的区块渲染网格
 *
 * @see ChunkMeshBuilder
 * @see MeshBuildTask
 * @author Renderium Team
 * @since 1.0.0
 */
public final class MeshBuildScheduler {

    private static final Logger LOGGER = Logger.getLogger("Renderium-MeshScheduler");

    /** 默认 Worker 数量（CPU 核心数 - 1，最少 1，最多 ConfigConstants.MAX_MESH_BUILD_WORKERS） */
    private static final int DEFAULT_WORKER_COUNT = Math.max(1,
            Math.min(ConfigConstants.MAX_MESH_BUILD_WORKERS,
                    Runtime.getRuntime().availableProcessors() - ConfigConstants.RESERVED_CORES_FOR_RENDER_THREAD));

    /** 单例实例 */
    private static volatile MeshBuildScheduler instance;

    // ==================== 线程池 ====================

    /** Worker 线程池 */
    private final ThreadPoolExecutor workerPool;

    /** Worker 数量 */
    private final int workerCount;

    // ==================== 任务队列 ====================

    /** 待处理任务队列（无锁并发） */
    private final ConcurrentLinkedQueue<MeshBuildTask> pendingTasks;

    /** 已完成结果队列（主线程 poll） */
    private final ConcurrentLinkedQueue<MeshBuildResult> completedResults;

    // ==================== Worker 本地资源 ====================

    /** 每个 Worker 的 ChunkMeshBuilder（ThreadLocal 保证线程安全） */
    private final ThreadLocal<ChunkMeshBuilder> workerBuilder;

    // ==================== 统计 ====================

    /** 提交的任务总数 */
    private final AtomicLong totalSubmitted = new AtomicLong(0);

    /** 完成的任务总数 */
    private final AtomicLong totalCompleted = new AtomicLong(0);

    /** 跳过的任务总数（队满时丢弃低优先级任务） */
    private final AtomicLong totalSkipped = new AtomicLong(0);

    /** 构建总耗时（纳秒） */
    private final AtomicLong totalBuildTimeNs = new AtomicLong(0);

    /** 是否已关闭（volatile 确保跨线程可见性） */
    private volatile boolean shutdown = false;

    private MeshBuildScheduler() {
        this(DEFAULT_WORKER_COUNT);
    }

    private MeshBuildScheduler(int workerCount) {
        this.workerCount = workerCount;
        this.pendingTasks = new ConcurrentLinkedQueue<>();
        this.completedResults = new ConcurrentLinkedQueue<>();

        // 创建 ThreadLocal ChunkMeshBuilder（每个 Worker 线程一个实例）
        this.workerBuilder = ThreadLocal.withInitial(() -> {
            NeighborFaceCuller culler = new NeighborFaceCuller();
            CompactVertexFormat format = CompactVertexFormat.CHUNK_MESH;
            return new ChunkMeshBuilder(culler, format);
        });

        // 创建 Worker 线程池
        AtomicInteger workerId = new AtomicInteger(0);
        this.workerPool = new ThreadPoolExecutor(
                workerCount, workerCount,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(ConfigConstants.MESH_BUILD_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "Renderium-MeshBuilder-" + workerId.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );

        LOGGER.info("═══ MeshBuildScheduler 已初始化 ═══");
        LOGGER.info("  Worker 数量: " + workerCount);
        LOGGER.info("════════════════════════════════════");
    }

    /**
     * 获取单例实例
     */
    public static synchronized MeshBuildScheduler getInstance() {
        if (instance == null || instance.shutdown) {
            instance = new MeshBuildScheduler();
        }
        return instance;
    }

    // ==================== 任务提交 ====================

    /**
     * 提交网格构建任务
     *
     * <p>任务会被分发到 Worker 线程池异步执行。
     * 完成后结果放入 {@link #completedResults}，由主线程 poll。
     *
     * <p>Thread-safety: 线程安全。shutdown 状态使用 volatile 保证可见性。
     *
     * @param task 构建任务
     * @return true 如果成功入队，false 如果队列已满
     */
    public boolean submit(MeshBuildTask task) {
        if (shutdown) return false;

        totalSubmitted.incrementAndGet();
        pendingTasks.add(task);

        workerPool.execute(() -> processTask(task));
        return true;
    }

    /**
     * 批量提交任务
     *
     * @param tasks 任务列表
     * @return 成功入队的任务数量
     */
    public int submitAll(List<MeshBuildTask> tasks) {
        int count = 0;
        for (MeshBuildTask task : tasks) {
            if (submit(task)) count++;
        }
        return count;
    }

    // ==================== 结果收集 ====================

    /**
     * 轮询已完成的构建结果
     *
     * <p>由主线程在每帧调用，收集所有已完成的 MeshData。
     *
     * @return 已完成的结果列表（可能为空）
     */
    public List<MeshBuildResult> pollResults() {
        List<MeshBuildResult> results = new ArrayList<>();
        MeshBuildResult result;
        while ((result = completedResults.poll()) != null) {
            results.add(result);
        }
        return results;
    }

    /**
     * 轮询指定数量的结果
     *
     * @param maxCount 最大数量
     * @return 结果列表
     */
    public List<MeshBuildResult> pollResults(int maxCount) {
        int batchSize = Math.min(ConfigConstants.SMALL_BUFFER_SIZE, maxCount);
        List<MeshBuildResult> results = new ArrayList<>(batchSize);
        MeshBuildResult result;
        int count = 0;
        while (count < maxCount && (result = completedResults.poll()) != null) {
            results.add(result);
            count++;
        }
        return results;
    }

    /**
     * 获取待处理任务数量
     */
    public int getPendingTaskCount() {
        return pendingTasks.size();
    }

    /**
     * 获取已完成但未收集的结果数量
     */
    public int getCompletedResultCount() {
        return completedResults.size();
    }

    // ==================== 内部处理 ====================

    /**
     * Worker 线程执行构建任务
     */
    private void processTask(MeshBuildTask task) {
        if (shutdown) return;

        long startTimeNs = System.nanoTime();

        try {
            // 获取当前线程的 ChunkMeshBuilder（ThreadLocal）
            ChunkMeshBuilder builder = workerBuilder.get();

            // 执行构建
            MeshData meshData = builder.build(
                    task.blockStates,
                    task.sectionX, task.sectionY, task.sectionZ,
                    task.chunkX, task.chunkZ
            );

            long elapsedNs = System.nanoTime() - startTimeNs;
            totalBuildTimeNs.addAndGet(elapsedNs);

            // 从待处理队列移除
            pendingTasks.remove(task);

            // 构建结果入队
            MeshBuildResult result = new MeshBuildResult(
                    task, meshData, elapsedNs
            );
            completedResults.add(result);

            totalCompleted.incrementAndGet();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Worker 线程执行任务 " + task + " 失败", e);
            totalSkipped.incrementAndGet();
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭调度器
     *
     * <p>Thread-safety: shutdown 状态使用 volatile 保证可见性。
     * 使用 shutdown + awaitTermination 模式优雅关闭线程池。
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;

        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(ConfigConstants.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warning("Worker 线程池未在 " + ConfigConstants.SHUTDOWN_TIMEOUT_SECONDS +
                        " 秒内关闭，强制终止");
                workerPool.shutdownNow();
                if (!workerPool.awaitTermination(ConfigConstants.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    LOGGER.severe("Worker 线程池强制终止失败");
                }
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        pendingTasks.clear();
        completedResults.clear();

        LOGGER.info("MeshBuildScheduler 已关闭");
    }

    // ==================== 统计 ====================

    public long getTotalSubmitted() { return totalSubmitted.get(); }
    public long getTotalCompleted() { return totalCompleted.get(); }
    public long getTotalSkipped() { return totalSkipped.get(); }
    public int getWorkerCount() { return workerCount; }
    public boolean isShutdown() { return shutdown; }

    public double getAverageBuildTimeMs() {
        long completed = totalCompleted.get();
        if (completed == 0) return 0.0;
        return (totalBuildTimeNs.get() / (double) completed) / 1_000_000.0;
    }

    public String getStatistics() {
        return String.format(
                "MeshBuildScheduler{workers=%d, submitted=%d, completed=%d, skipped=%d, avg=%.2fms, pending=%d, results=%d}",
                workerCount, totalSubmitted.get(), totalCompleted.get(), totalSkipped.get(),
                getAverageBuildTimeMs(), getPendingTaskCount(), getCompletedResultCount()
        );
    }
}
