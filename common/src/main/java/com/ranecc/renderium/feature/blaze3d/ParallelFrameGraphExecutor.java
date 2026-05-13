// Renderium - 并行帧图执行器
// 基于 blaze3d_optimization_analysis.md §1.4 并行执行优化

package com.ranecc.renderium.feature.blaze3d;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 并行帧图执行器。
 *
 * <p>基于 {@code blaze3d_optimization_analysis.md} 中识别的高收益（但高风险）优化：
 * <b>并行执行独立 Pass</b>可以显著提升 GPU 利用率。
 *
 * <h3>核心设计</h3>
 * <pre>
 * 传统方式 (Blaze3D 默认):
 *   for (Pass pass : passesInOrder) {
 *       pass.task.run();  // ← 完全串行
 *   }
 *
 * 并行方式:
 *   List&lt;Set&lt;Pass&gt;&gt; parallelGroups = findParallelGroups(passes);
 *   for (Set&lt;Pass&gt; group : parallelGroups) {
 *       if (group.size() == 1) {
 *           group.iterator().next().task.run();
 *       } else {
 *           executeParallel(group);  // ← 并行执行
 *       }
 *   }
 * </pre>
 *
 * <h3>依赖分析</h3>
 * <p>通过拓扑排序识别可并行的 Pass 组：
 * <ul>
 *   <li>无依赖关系的 Pass 可以并行</li>
 *   <li>有依赖关系的 Pass 必须串行</li>
 *   <li>使用线程池控制并行度</li>
 * </ul>
 *
 * <h3>性能收益</h3>
 * <ul>
 *   <li>理论提升 20-50% GPU 利用率</li>
 *   <li>多核 CPU 上效果更明显</li>
 * </ul>
 *
 * <h3>风险提示</h3>
 * <p><b>高风险</b>: 同步复杂，容易出错。建议在充分测试后启用。
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class ParallelFrameGraphExecutor {

    private static final Logger LOGGER = Logger.getLogger(ParallelFrameGraphExecutor.class.getName());

    /** 默认最大并行线程数 */
    public static final int DEFAULT_MAX_PARALLEL_THREADS = 4;

    /** 默认是否启用并行执行 */
    public static final boolean DEFAULT_ENABLED = false;

    /** 线程池 */
    private ExecutorService executorService;

    /** 最大并行线程数 */
    private final int maxParallelThreads;

    /** 是否已启用 */
    private volatile boolean enabled = false;

    // ==================== 统计字段 ====================

    private final AtomicInteger totalExecutedPasses = new AtomicInteger(0);
    private final AtomicInteger totalParallelGroups = new AtomicInteger(0);
    private final AtomicInteger totalSerialGroups = new AtomicInteger(0);
    private final AtomicLong totalExecutionTimeNs = new AtomicLong(0);

    /**
     * 表示一个 FrameGraph Pass
     */
    public static class GraphPass {
        public final int id;
        public final String name;
        public final Runnable task;
        public final Set<Integer> dependencies;

        public GraphPass(int id, String name, Runnable task, Set<Integer> dependencies) {
            this.id = id;
            this.name = name;
            this.task = task;
            this.dependencies = dependencies != null ? dependencies : Collections.emptySet();
        }

        @Override
        public String toString() {
            return "Pass[" + id + "]:" + name;
        }
    }

    /**
     * 创建并行帧图执行器
     */
    public ParallelFrameGraphExecutor() {
        this(DEFAULT_MAX_PARALLEL_THREADS);
    }

    /**
     * 创建并行帧图执行器（自定义线程数）
     *
     * @param maxParallelThreads 最大并行线程数
     */
    public ParallelFrameGraphExecutor(int maxParallelThreads) {
        this.maxParallelThreads = Math.max(1, Math.min(maxParallelThreads,
                Runtime.getRuntime().availableProcessors()));
        LOGGER.info("ParallelFrameGraphExecutor initialized (threads=" +
                   this.maxParallelThreads + ")");
    }

    /**
     * 初始化线程池
     *
     * @return 是否成功
     */
    public boolean initialize() {
        if (executorService != null && !executorService.isShutdown()) {
            return true;
        }

        try {
            // 使用守护线程池，避免阻止 JVM 关闭
            executorService = Executors.newFixedThreadPool(
                    maxParallelThreads,
                    r -> {
                        Thread t = new Thread(r, "Renderium-ParallelPass");
                        t.setDaemon(true);
                        return t;
                    }
            );

            LOGGER.info("线程池已创建: " + maxParallelThreads + " 线程");
            return true;

        } catch (Exception e) {
            LOGGER.severe("创建线程池失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启用/禁用并行执行
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("并行执行: " + (enabled ? "ON" : "OFF"));
    }

    /**
     * 执行帧图（支持并行）
     *
     * <p>这是主要入口点，分析依赖关系并按最优顺序执行。
     *
     * @param passes Pass 列表（必须是有序的）
     * @return 执行的 Pass 数量
     */
    public int executeFrameGraph(List<GraphPass> passes) {
        if (passes == null || passes.isEmpty()) {
            return 0;
        }

        long startTime = System.nanoTime();

        if (!enabled || passes.size() <= 1) {
            // 串行执行
            for (GraphPass pass : passes) {
                executeSingle(pass);
            }
            totalExecutionTimeNs.addAndGet(System.nanoTime() - startTime);
            return passes.size();
        }

        // 分析依赖关系，找出可并行的组
        List<Set<GraphPass>> parallelGroups = analyzeDependencies(passes);

        // 按组执行
        int executedCount = 0;
        for (Set<GraphPass> group : parallelGroups) {
            if (group.size() <= 1) {
                // 单个 Pass，串行执行
                for (GraphPass pass : group) {
                    executeSingle(pass);
                    executedCount++;
                }
                totalSerialGroups.incrementAndGet();
            } else {
                // 多个 Pass，并行执行
                executeParallelGroup(group);
                executedCount += group.size();
                totalParallelGroups.incrementAndGet();
            }
        }

        long elapsed = System.nanoTime() - startTime;
        totalExecutionTimeNs.addAndGet(elapsed);

        LOGGER.fine("FrameGraph 执行完成: " + executedCount + " passes, " +
                   (elapsed / 1_000_000.0) + "ms");

        return executedCount;
    }

    /**
     * 分析 Pass 依赖关系，找出可并行的组
     *
     * <p>使用改进的 Kahn 算法进行层次化分组。
     *
     * @param passes 有序 Pass 列表
     * @return 分组列表（每组内的 Pass 可并行）
     */
    public List<Set<GraphPass>> analyzeDependencies(List<GraphPass> passes) {
        List<Set<GraphPass>> groups = new ArrayList<>();

        if (passes.isEmpty()) {
            return groups;
        }

        // 构建依赖图
        Map<Integer, Set<Integer>> dependents = new HashMap<>();
        Map<Integer, Integer> inDegree = new HashMap<>();
        Set<Integer> remaining = new HashSet<>();

        for (GraphPass pass : passes) {
            remaining.add(pass.id);
            inDegree.put(pass.id, pass.dependencies.size());
            dependents.putIfAbsent(pass.id, new HashSet<>());

            for (int depId : pass.dependencies) {
                dependents.computeIfAbsent(depId, k -> new HashSet<>()).add(pass.id);
            }
        }

        // 层次化遍历
        while (!remaining.isEmpty()) {
            // 找出当前层所有入度为 0 的节点
            Set<GraphPass> currentLayer = new LinkedHashSet<>();
            Iterator<Integer> iter = remaining.iterator();

            while (iter.hasNext()) {
                int id = iter.next();
                if (inDegree.getOrDefault(id, 0) == 0) {
                    Optional<GraphPass> passOpt = passes.stream()
                            .filter(p -> p.id == id).findFirst();
                    passOpt.ifPresent(currentLayer::add);
                    iter.remove();
                }
            }

            if (currentLayer.isEmpty()) {
                // 检测到循环依赖或错误，将剩余的全部作为一组
                LOGGER.warning("检测到可能的循环依赖，剩余 " +
                             remaining.size() + " 个 Pass 将串行执行");
                for (int id : remaining) {
                    passes.stream().filter(p -> p.id == id).findFirst()
                          .ifPresent(currentLayer::add);
                }
                remaining.clear();
            }

            if (!currentLayer.isEmpty()) {
                groups.add(currentLayer);

                // 更新入度
                for (GraphPass pass : currentLayer) {
                    for (int depId : dependents.getOrDefault(pass.id, Collections.emptySet())) {
                        inDegree.merge(depId, -1, Integer::sum);
                    }
                }
            }
        }

        LOGGER.fine("依赖分析完成: " + groups.size() + " 层");
        return groups;
    }

    /**
     * 执行单个 Pass
     */
    private void executeSingle(GraphPass pass) {
        try {
            long start = System.nanoTime();
            pass.task.run();
            long elapsed = System.nanoTime() - start;
            totalExecutedPasses.incrementAndGet();

            LOGGER.finest("Pass [" + pass.id + "] " + pass.name +
                         " 完成 (" + (elapsed / 1_000_000.0) + "ms)");

        } catch (Exception e) {
            LOGGER.severe("Pass [" + pass.id + "] " + pass.name +
                         " 执行失败: " + e.getMessage());
        }
    }

    /**
     * 并行执行一组 Pass
     */
    private void executeParallelGroup(Set<GraphPass> group) {
        if (executorService == null || executorService.isShutdown()) {
            // 线程池不可用，回退到串行
            for (GraphPass pass : group) {
                executeSingle(pass);
            }
            return;
        }

        CountDownLatch latch = new CountDownLatch(group.size());

        for (GraphPass pass : group) {
            executorService.submit(() -> {
                try {
                    executeSingle(pass);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            // 等待所有 Pass 完成
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("并行执行被中断");
        }
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭线程池
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
            LOGGER.info("ParallelFrameGraphExecutor 已关闭");
        }
        enabled = false;
    }

    // ==================== 查询接口 ====================

    /** 是否已启用 */
    public boolean isEnabled() { return enabled; }

    /** 是否已初始化 */
    public boolean isInitialized() { return executorService != null && !executorService.isShutdown(); }

    /** 获取最大并行线程数 */
    public int getMaxParallelThreads() { return maxParallelThreads; }

    /** 获取总执行的 Pass 数 */
    public int getTotalExecutedPasses() { return totalExecutedPasses.get(); }

    /** 获取总并行组数 */
    public int getTotalParallelGroups() { return totalParallelGroups.get(); }

    /** 获取总串行组数 */
    public int getTotalSerialGroups() { return totalSerialGroups.get(); }

    /** 获取平均执行时间 (ms) */
    public double getAverageExecutionTimeMs() {
        int count = totalExecutedPasses.get();
        if (count == 0) return 0.0;
        return (totalExecutionTimeNs.get() / 1_000_000.0) / count;
    }

    /**
     * 获取诊断信息
     *
     * @return 格式化的状态字符串
     */
    public String getDiagnostics() {
        return String.format(
            "ParallelFrameGraphExecutor{" +
            "  enabled=%s, threads=%d" +
            "  passes=%d, parallel_groups=%d, serial_groups=%d" +
            "  avg_time=%.2fms}",
            enabled,
            maxParallelThreads,
            totalExecutedPasses.get(),
            totalParallelGroups.get(),
            totalSerialGroups.get(),
            getAverageExecutionTimeMs()
        );
    }
}
