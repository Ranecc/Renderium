// Renderium - 异步网格构建器
// 使用 Work-Stealing 线程池并行构建区块网格
// 借鉴 Sodium 的多线程构建策略

package com.ranecc.renderium.feature.renderopt.mesh;

import com.ranecc.renderium.None;
import com.ranecc.renderium.feature.renderopt.mesh.MeshData;
import com.ranecc.renderium.domain.constant.ConfigConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.culling.optimization.NeighborFaceCuller;
/**
 * 异步网格构建器。
 *
 * <p>使用 Work-Stealing 线程池并行构建区块网格，
 * 借鉴 Sodium 的多线程构建策略。
 *
 * <h2>架构</h2>
 * <pre>
 * 主线程（渲染线程）
 *   ↓ 提交构建任务
 * AsyncMeshBuilder
 *   ↓ 分发到线程池
 * ForkJoinPool (Work-Stealing)
 *   ├── Worker-0: ChunkMeshBuilder → MeshData
 *   ├── Worker-1: ChunkMeshBuilder → MeshData
 *   ├── Worker-2: ChunkMeshBuilder → MeshData
 *   └── Worker-3: ChunkMeshBuilder → MeshData
 *   ↓ 收集结果
 * CompletableFuture&lt;MeshData&gt;
 *   ↓ 主线程取回
 * 上传到 GPU
 * </pre>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>每个 Worker 持有自己的 {@link ChunkMeshBuilder} 实例（ThreadLocal）</li>
 *   <li>输入数据（blockStates 数组）在提交时拷贝，Worker 不共享可变状态</li>
 *   <li>输出 {@link MeshData} 是不可变的，可安全跨线程传递</li>
 * </ul>
 *
 * <h2>与 Sodium 的对比</h2>
 * <p>Sodium 使用自定义的 {@code MeasuredThreadFactory} 和固定大小线程池。
 * Renderium 使用 {@link ForkJoinPool} 的 Work-Stealing 调度器，
 * 自动平衡负载，不需要手动分配任务到线程。
 *
 * @see ChunkMeshBuilder
 * @see MeshData
 * @author Renderium Team
 * @since 1.0.0
 */
public final class AsyncMeshBuilder {

    private static final Logger LOGGER = Logger.getLogger(AsyncMeshBuilder.class.getName());

    /** 默认并行度（CPU 核心数 - 1，至少 1） */
    private static final int DEFAULT_PARALLELISM = Math.max(1,
            Runtime.getRuntime().availableProcessors() - ConfigConstants.RESERVED_CORES_FOR_RENDER_THREAD);

    // ==================== 构建任务定义 ====================

    /**
     * 网格构建任务
     */
    public static final class BuildTask {
        /** 区块段方块状态数据（拷贝，线程安全） */
        public final int[] blockStates;
        /** 区段坐标 */
        public final int sectionX, sectionY, sectionZ;
        /** 区块坐标 */
        public final int chunkX, chunkZ;
        /** 任务 ID（用于追踪） */
        public final long taskId;
        /** 任务取消标志（支持中断正在执行的任务） */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private static final AtomicInteger ID_GENERATOR = new AtomicInteger(0);

        public BuildTask(int[] blockStates, int sectionX, int sectionY, int sectionZ,
                         int chunkX, int chunkZ) {
            // 拷贝方块状态数据，确保线程安全
            this.blockStates = Objects.requireNonNull(blockStates).clone();
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.taskId = ID_GENERATOR.incrementAndGet();
        }

        /**
         * 取消此任务
         *
         * <p>如果任务尚未开始执行，将阻止其执行；
         * 如果正在执行，将设置取消标志，Worker 应检查此标志并尽快退出。
         */
        public void cancel() {
            cancelled.set(true);
        }

        /**
         * 检查任务是否已被取消
         *
         * @return true 如果任务已取消
         */
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /**
     * 构建结果
     */
    public static final class BuildResult {
        /** 原始任务 */
        public final BuildTask task;
        /** 构建完成的网格数据（可能为 null 如果构建失败） */
        public final MeshData meshData;
        /** 构建耗时（纳秒） */
        public final long buildTimeNs;
        /** 是否成功 */
        public final boolean success;

        BuildResult(BuildTask task, MeshData meshData, long buildTimeNs, boolean success) {
            this.task = task;
            this.meshData = meshData;
            this.buildTimeNs = buildTimeNs;
            this.success = success;
        }
    }

    // ==================== 实例字段 ====================

    /** Work-Stealing 线程池 */
    private final ForkJoinPool executor;

    /** 每个 Worker 的 ChunkMeshBuilder（ThreadLocal） */
    private final ThreadLocal<ChunkMeshBuilder> threadLocalBuilder;

    /** 待处理任务队列 */
    private final ConcurrentLinkedQueue<CompletableFuture<BuildResult>> pendingFutures;

    /** 已完成但未取回的结果 */
    private final ConcurrentLinkedQueue<BuildResult> completedResults;

    /** 统计：提交的任务总数 */
    private final AtomicInteger totalTasksSubmitted = new AtomicInteger(0);

    /** 统计：完成的任务总数 */
    private final AtomicInteger totalTasksCompleted = new AtomicInteger(0);

    /** 统计：失败的任务总数 */
    private final AtomicInteger totalTasksFailed = new AtomicInteger(0);

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    // ==================== 构造与生命周期 ====================

    /**
     * 创建异步网格构建器
     *
     * @param faceCuller 邻居面剔除器（共享，只读）
     * @param vertexFormat 顶点格式
     */
    public AsyncMeshBuilder(NeighborFaceCuller faceCuller, CompactVertexFormat vertexFormat) {
        this.executor = createForkJoinPool(DEFAULT_PARALLELISM);
        this.threadLocalBuilder = ThreadLocal.withInitial(
                () -> new ChunkMeshBuilder(faceCuller, vertexFormat));
        this.pendingFutures = new ConcurrentLinkedQueue<>();
        this.completedResults = new ConcurrentLinkedQueue<>();

        LOGGER.info("AsyncMeshBuilder created with " + DEFAULT_PARALLELISM + " workers");
    }

    /**
     * 创建 ForkJoinPool 并配置线程工厂
     *
     * <p>配置自定义线程工厂，设置线程名称、优先级和守护状态。
     * 使用最小优先级避免与工作线程竞争 CPU 时间。
     *
     * @param parallelism 并行度
     * @return 配置好的 ForkJoinPool
     */
    private ForkJoinPool createForkJoinPool(int parallelism) {
        AtomicInteger threadId = new AtomicInteger(0);

        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("Renderium-MeshBuilder-" + threadId.incrementAndGet());
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            return worker;
        };

        return new ForkJoinPool(parallelism, factory, null, true);
    }

    /**
     * 提交构建任务
     *
     * @param task 构建任务
     * @return CompletableFuture，完成后可获取 BuildResult
     */
    public CompletableFuture<BuildResult> submit(BuildTask task) {
        if (shutdown) {
            return CompletableFuture.completedFuture(
                    new BuildResult(task, null, 0, false));
        }

        // 检查任务是否已被取消
        if (task.isCancelled()) {
            totalTasksFailed.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new BuildResult(task, null, 0, false));
        }

        totalTasksSubmitted.incrementAndGet();

        CompletableFuture<BuildResult> future = CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            try {
                // 检查取消标志
                if (task.isCancelled()) {
                    LOGGER.fine("Build task " + task.taskId + " was cancelled before execution");
                    totalTasksFailed.incrementAndGet();
                    return new BuildResult(task, null, 0, false);
                }

                ChunkMeshBuilder builder = threadLocalBuilder.get();
                MeshData meshData = builder.build(
                        task.blockStates,
                        task.sectionX, task.sectionY, task.sectionZ,
                        task.chunkX, task.chunkZ);

                long elapsed = System.nanoTime() - startTime;
                totalTasksCompleted.incrementAndGet();
                return new BuildResult(task, meshData, elapsed, true);
            } catch (CancellationException e) {
                long elapsed = System.nanoTime() - startTime;
                totalTasksFailed.incrementAndGet();
                LOGGER.fine("Build task " + task.taskId + " was cancelled during execution");
                return new BuildResult(task, null, elapsed, false);
            } catch (Exception e) {
                long elapsed = System.nanoTime() - startTime;
                totalTasksFailed.incrementAndGet();
                LOGGER.log(Level.WARNING, "Mesh build failed for task " + task.taskId, e);
                return new BuildResult(task, null, elapsed, false);
            }
        }, executor);

        pendingFutures.add(future);
        return future;
    }

    /**
     * 批量提交构建任务
     *
     * @param tasks 任务列表
     * @return CompletableFuture，所有任务完成后可获取结果列表
     */
    public CompletableFuture<List<BuildResult>> submitAll(List<BuildTask> tasks) {
        List<CompletableFuture<BuildResult>> futures = new ArrayList<>(tasks.size());
        for (BuildTask task : tasks) {
            futures.add(submit(task));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<BuildResult> results = new ArrayList<>(futures.size());
                    for (CompletableFuture<BuildResult> f : futures) {
                        results.add(f.join());
                    }
                    return results;
                });
    }

    /**
     * 轮询已完成的构建结果
     *
     * <p>在主线程每帧调用，取回已完成的 MeshData。
     *
     * @return 已完成的构建结果列表
     */
    public List<BuildResult> pollCompleted() {
        List<BuildResult> results = new ArrayList<>();
        BuildResult result;
        while ((result = completedResults.poll()) != null) {
            results.add(result);
        }

        // 检查 pending futures
        pendingFutures.removeIf(future -> {
            if (future.isDone()) {
                try {
                    completedResults.add(future.join());
                } catch (Exception e) {
                    // 忽略已失败的
                }
                return true;
            }
            return false;
        });

        // 再次收集
        while ((result = completedResults.poll()) != null) {
            results.add(result);
        }

        return results;
    }

    /**
     * 等待所有待处理任务完成
     */
    public void awaitCompletion() {
        List<CompletableFuture<BuildResult>> remaining = new ArrayList<>();
        pendingFutures.removeIf(f -> {
            remaining.add(f);
            return true;
        });

        CompletableFuture.allOf(remaining.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 关闭异步构建器
     */
    public void shutdown() {
        shutdown = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("AsyncMeshBuilder shutdown completed");
    }

    // ==================== 统计 ====================

    public int getTotalTasksSubmitted() { return totalTasksSubmitted.get(); }
    public int getTotalTasksCompleted() { return totalTasksCompleted.get(); }
    public int getTotalTasksFailed() { return totalTasksFailed.get(); }
    public int getPendingCount() { return pendingFutures.size(); }
    public int getParallelism() { return executor.getParallelism(); }

    public String getDiagnostics() {
        return String.format(
            "AsyncMeshBuilder{submitted=%d, completed=%d, failed=%d, pending=%d, workers=%d}",
            totalTasksSubmitted.get(), totalTasksCompleted.get(), totalTasksFailed.get(),
            pendingFutures.size(), executor.getParallelism());
    }
}
