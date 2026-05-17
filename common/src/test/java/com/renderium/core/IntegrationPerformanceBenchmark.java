// ============================================================
// IntegrationPerformanceBenchmark - 集成性能基准测试
// ============================================================
// 测试热路径帧处理性能，验证 1000-2000 FPS 目标（0.5-1ms/帧）
//
// 测试组件：
//   1. RenderiumCore.onFrameBegin() 热路径循环（模拟）
//   2. DynamicPrecisionManager.decidePrecision() 决策延迟
//   3. PhaseTransitionDetector.detectTransition(float) 检测延迟
//   4. BfsOcclusionEngine.findVisibleSections() BFS 剔除延迟
//   5. KahanAccumulator.add() 累加延迟
//   6. ConvergenceMonitor.updateEnergy() 收敛更新延迟
//   7. LyapunovQualityChecker 冷路径不阻塞热路径验证
//
// 计时方式：System.nanoTime() 高精度计时
// 预热阶段：1000 次迭代消除 JIT 影响
// 测量阶段：10000 次迭代采集延迟数据
// 输出格式：P50/P95/P99 延迟 + 等效 FPS + 目标达标判定
//
// @see DynamicPrecisionManager
// @see PhaseTransitionDetector
// @see BfsOcclusionEngine
// @see KahanAccumulator
// @see ConvergenceMonitor
// @see LyapunovQualityChecker
// ============================================================

package com.renderium.core;
import com.ranecc.renderium.feature.pipeline.BfsOcclusionEngine;

import com.renderium.core.quality.LyapunovQualityChecker;

import com.renderium.core.phase.PhaseTransitionDetector;
import com.renderium.core.quality.ConvergenceMonitor;
import com.renderium.core.math.KahanAccumulator;
import com.renderium.pipeline.BfsOcclusionEngine;
import com.renderium.pipeline.BfsOcclusionEngine.OcclusionTask;
import com.renderium.pipeline.BfsOcclusionEngine.CameraView;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集成性能基准测试
 * <p>
 * 测试热路径帧处理各组件的延迟，验证是否达到 1000 FPS 目标。
 * 不使用 JMH，纯 Java 实现基于 System.nanoTime() 的高精度计时。
 *
 * <h2>测试架构</h2>
 * <pre>
 * +-----------------------------------------------------+
 * |              IntegrationPerformanceBenchmark         |
 * +-----------------------------------------------------+
 * |  热路径组件延迟测试                                    |
 * |  +-----------------------------------------------+  |
 * |  | onFrameBegin 循环（组合热路径）                  |  |
 * |  |   +- DynamicPrecisionManager.updateFrameTime  |  |
 * |  |   +- DynamicPrecisionManager.decidePrecision  |  |
 * |  |   +- PhaseTransitionDetector.detectTransition |  |
 * |  |   +- ConvergenceMonitor.updateEnergy          |  |
 * |  |   +- KahanAccumulator.add                     |  |
 * |  +-----------------------------------------------+  |
 * |  | 单组件延迟测试                                  |  |
 * |  |   +- decidePrecision 决策延迟                  |  |
 * |  |   +- detectTransition 检测延迟                 |  |
 * |  |   +- findVisibleSections BFS 剔除延迟          |  |
 * |  |   +- KahanAccumulator.add 累加延迟             |  |
 * |  |   +- updateEnergy 收敛更新延迟                 |  |
 * |  +-----------------------------------------------+  |
 * |  | 冷路径不阻塞验证                                |  |
 * |  |   +- LyapunovQualityChecker 并行执行           |  |
 * |  +-----------------------------------------------+  |
 * +-----------------------------------------------------+
 * </pre>
 *
 * <h2>运行方式</h2>
 * <pre>{@code
 * // 直接运行 main 方法
 * java com.renderium.core.IntegrationPerformanceBenchmark
 * }</pre>
 */
public final class IntegrationPerformanceBenchmark {

    // ==================== 常量定义 ====================

    /** 预热迭代次数（消除 JIT 编译和类加载影响） */
    private static final int WARMUP_ITERATIONS = 1000;

    /** 测量迭代次数（采集延迟样本） */
    private static final int MEASURE_ITERATIONS = 10000;

    /** 目标 FPS（1000 FPS = 每帧 1ms 预算） */
    private static final int TARGET_FPS = 1000;

    /** 目标帧时间（毫秒），1000 FPS 对应 1ms/帧 */
    private static final double TARGET_FRAME_TIME_MS = 1.0;

    /** 目标帧时间（纳秒），用于纳秒级比较 */
    private static final double TARGET_FRAME_TIME_NS = TARGET_FRAME_TIME_MS * 1_000_000.0;

    /** BFS 测试图网格大小（7x7x7 = 343 个区块） */
    private static final int BFS_GRID_SIZE = 7;

    /** BFS 测试渲染距离 */
    private static final float BFS_RENDER_DISTANCE = 128.0f;

    /** Lyapunov 冷路径验证迭代次数 */
    private static final int LYAPUNOV_STRESS_ITERATIONS = 50000;

    /** 纳秒到微秒的转换系数 */
    private static final double NS_TO_US = 1_000.0;

    /** 纳秒到毫秒的转换系数 */
    private static final double NS_TO_MS = 1_000_000.0;

    // ==================== 延迟统计内部类 ====================

    /**
     * 延迟统计数据
     * <p>
     * 存储原始纳秒级延迟样本，提供百分位数计算和格式化输出。
     * 排序后通过索引直接定位百分位数位置。
     */
    private static final class LatencyStats {

        /** 原始延迟样本（纳秒） */
        private final long[] samples;

        /** 排序后的延迟样本（纳秒） */
        private final long[] sorted;

        /** 组件名称 */
        private final String componentName;

        /** P50 延迟（纳秒） */
        private final long p50Ns;

        /** P95 延迟（纳秒） */
        private final long p95Ns;

        /** P99 延迟（纳秒） */
        private final long p99Ns;

        /** 最小延迟（纳秒） */
        private final long minNs;

        /** 最大延迟（纳秒） */
        private final long maxNs;

        /** 平均延迟（纳秒） */
        private final double avgNs;

        /**
         * 构造延迟统计
         *
         * @param componentName 组件名称
         * @param samples       原始延迟样本数组（纳秒），不会被修改
         */
        LatencyStats(String componentName, long[] samples) {
            this.componentName = componentName;
            this.samples = samples;
            // 复制数组避免修改原始数据
            this.sorted = new long[samples.length];
            System.arraycopy(samples, 0, this.sorted, 0, samples.length);
            Arrays.sort(this.sorted);

            int n = this.sorted.length;
            this.p50Ns = this.sorted[(int) (n * 0.50)];
            this.p95Ns = this.sorted[(int) (n * 0.95)];
            this.p99Ns = this.sorted[(int) (n * 0.99)];
            this.minNs = this.sorted[0];
            this.maxNs = this.sorted[n - 1];

            // 计算平均值
            long sum = 0;
            for (long s : samples) {
                sum += s;
            }
            this.avgNs = (double) sum / n;
        }

        /**
         * 获取等效 FPS（基于 P95 延迟）
         * <p>
         * 计算公式: 1000ms / P95延迟(ms)
         *
         * @return 等效 FPS 值
         */
        double getEquivalentFps() {
            double p95Ms = p95Ns / NS_TO_MS;
            return p95Ms > 0 ? 1000.0 / p95Ms : Double.POSITIVE_INFINITY;
        }

        /**
         * 判断是否达到 1000 FPS 目标
         * <p>
         * 判定标准: P95 延迟 <= 1ms（1000 FPS 对应的帧预算）
         *
         * @return true 表示达标
         */
        boolean isTargetMet() {
            return p95Ns <= TARGET_FRAME_TIME_NS;
        }

        /**
         * 生成格式化的统计报告行
         *
         * @return 格式化字符串
         */
        String formatReport() {
            double eqFps = getEquivalentFps();
            String fpsStr = eqFps == Double.POSITIVE_INFINITY ? "INF" : String.format("%.0f", eqFps);
            String status = isTargetMet() ? "PASS" : "FAIL";

            return String.format(
                "  %-35s | %8.1f | %8.1f | %8.1f | %7s | %s",
                componentName,
                p50Ns / NS_TO_US,
                p95Ns / NS_TO_US,
                p99Ns / NS_TO_US,
                fpsStr,
                status
            );
        }
    }

    // ==================== 主入口 ====================

    /**
     * 基准测试主入口
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>预热阶段（1000 次迭代）</li>
     *   <li>各组件延迟测量（10000 次迭代）</li>
     *   <li>冷路径不阻塞验证</li>
     *   <li>汇总报告输出</li>
     * </ol>
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        printBanner();

        // 存储各组件的测试结果
        LatencyStats[] allStats = new LatencyStats[7];
        int statIndex = 0;

        // ===== 测试1: onFrameBegin 热路径循环 =====
        System.out.println("[1/7] 测试 onFrameBegin 热路径循环...");
        allStats[statIndex++] = benchmarkOnFrameBeginLoop();

        // ===== 测试2: DynamicPrecisionManager.decidePrecision =====
        System.out.println("[2/7] 测试 DynamicPrecisionManager.decidePrecision...");
        allStats[statIndex++] = benchmarkDecidePrecision();

        // ===== 测试3: PhaseTransitionDetector.detectTransition =====
        System.out.println("[3/7] 测试 PhaseTransitionDetector.detectTransition...");
        allStats[statIndex++] = benchmarkDetectTransition();

        // ===== 测试4: BfsOcclusionEngine.findVisibleSections =====
        System.out.println("[4/7] 测试 BfsOcclusionEngine.findVisibleSections...");
        allStats[statIndex++] = benchmarkBfsOcclusion();

        // ===== 测试5: KahanAccumulator.add =====
        System.out.println("[5/7] 测试 KahanAccumulator.add...");
        allStats[statIndex++] = benchmarkKahanAdd();

        // ===== 测试6: ConvergenceMonitor.updateEnergy =====
        System.out.println("[6/7] 测试 ConvergenceMonitor.updateEnergy...");
        allStats[statIndex++] = benchmarkConvergenceUpdate();

        // ===== 测试7: Lyapunov 冷路径不阻塞验证 =====
        System.out.println("[7/7] 测试 Lyapunov 冷路径不阻塞热路径...");
        allStats[statIndex++] = benchmarkLyapunovNonBlocking();

        // ===== 输出汇总报告 =====
        printReport(allStats);
    }

    // ==================== 测试1: onFrameBegin 热路径循环 ====================

    /**
     * 测试 onFrameBegin 热路径循环延迟
     * <p>
     * 模拟 RenderiumCore.onFrameBegin() 的热路径执行流程，
     * 组合调用以下组件（与实际 onFrameBegin 内部调用一致）：
     * <ol>
     *   <li>DynamicPrecisionManager.updateFrameTime() - 更新帧时间</li>
     *   <li>DynamicPrecisionManager.decidePrecision() - 决策精度</li>
     *   <li>PhaseTransitionDetector.detectTransition() - 检测相变</li>
     *   <li>ConvergenceMonitor.updateEnergy() - 更新收敛指标</li>
     *   <li>KahanAccumulator.add() - 累加帧统计</li>
     * </ol>
     *
     * @return 延迟统计数据
     */
    private static LatencyStats benchmarkOnFrameBeginLoop() {
        // 初始化热路径组件
        DynamicPrecisionManager precisionMgr = new DynamicPrecisionManager(PrecisionConfig.DEFAULT_1000FPS);
        PhaseTransitionDetector phaseDetector = new PhaseTransitionDetector();
        ConvergenceMonitor convergenceMonitor = new ConvergenceMonitor();
        KahanAccumulator frameAccumulator = new KahanAccumulator();

        // 预填充帧时间数据（decidePrecision 需要足够的历史数据）
        for (int i = 0; i < 50; i++) {
            precisionMgr.updateFrameTime(0.8 + Math.random() * 0.3);
        }

        // 预热阶段
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            simulateOnFrameBegin(precisionMgr, phaseDetector, convergenceMonitor, frameAccumulator, i);
        }

        // 测量阶段
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            simulateOnFrameBegin(precisionMgr, phaseDetector, convergenceMonitor, frameAccumulator, i);
            latencies[i] = System.nanoTime() - start;
        }

        return new LatencyStats("onFrameBegin 循环(组合)", latencies);
    }

    /**
     * 模拟 onFrameBegin 热路径执行
     * <p>
     * 按照实际 RenderiumCore.onFrameBegin() 的调用顺序执行各组件，
     * 模拟真实帧处理流程。
     *
     * @param precisionMgr      精度管理器
     * @param phaseDetector     相变检测器
     * @param convergenceMonitor 收敛监控器
     * @param accumulator       Kahan 累加器
     * @param frameIndex        帧索引
     */
    private static void simulateOnFrameBegin(
            DynamicPrecisionManager precisionMgr,
            PhaseTransitionDetector phaseDetector,
            ConvergenceMonitor convergenceMonitor,
            KahanAccumulator accumulator,
            int frameIndex) {

        // 模拟帧时间（0.5ms ~ 1.5ms 之间波动，符合 1000FPS 目标场景）
        double frameTimeMs = 0.5 + (frameIndex % 17) * 0.06;

        // 步骤1: 更新帧时间（每帧调用）
        precisionMgr.updateFrameTime(frameTimeMs);

        // 步骤2: 查询热路径精度（每帧调用，O(1) 决策）
        DynamicPrecisionManager.PrecisionLevel level =
            precisionMgr.decidePrecision(DynamicPrecisionManager.OperationCategory.HOT_PATH_PER_PIXEL);

        // 步骤3: 检测相变（每帧调用，使用简化接口）
        float diffMetric = (frameIndex % 100 == 0) ? 0.6f : 0.05f;
        phaseDetector.detectTransition(diffMetric);

        // 步骤4: 更新收敛指标（每帧调用）
        double energy = 100.0 + Math.sin(frameIndex * 0.01) * 5.0;
        convergenceMonitor.updateEnergy(energy);

        // 步骤5: 累加帧统计（每帧调用）
        accumulator.add((float) frameTimeMs);
    }

    // ==================== 测试2: DynamicPrecisionManager.decidePrecision ====================

    /**
     * 测试 DynamicPrecisionManager.decidePrecision() 决策延迟
     * <p>
     * 方法签名: {@code PrecisionLevel decidePrecision(OperationCategory category)}
     * <ul>
     *   <li>参数: OperationCategory 枚举（HOT_PATH_PER_PIXEL, TILE_LEVEL_STATS 等）</li>
     *   <li>返回: PrecisionLevel 枚举（SKIP, INT8_FAST, FP16_MEDIUM, FP32_FULL, KAHAN_PRECISE）</li>
     *   <li>性能保证: 决策延迟 < 1μs</li>
     * </ul>
     *
     * @return 延迟统计数据
     */
    private static LatencyStats benchmarkDecidePrecision() {
        DynamicPrecisionManager precisionMgr = new DynamicPrecisionManager(PrecisionConfig.DEFAULT_1000FPS);

        // 预填充帧时间数据（decidePrecision 依赖滑动平均窗口）
        for (int i = 0; i < 50; i++) {
            precisionMgr.updateFrameTime(0.8 + Math.random() * 0.3);
        }

        // 操作类别轮转数组（测试不同类别的决策路径）
        DynamicPrecisionManager.OperationCategory[] categories =
            DynamicPrecisionManager.OperationCategory.values();

        // 预热阶段
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            precisionMgr.updateFrameTime(0.9);
            precisionMgr.decidePrecision(categories[i % categories.length]);
        }

        // 测量阶段：updateFrameTime + decidePrecision 组合延迟
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            precisionMgr.updateFrameTime(0.9);
            precisionMgr.decidePrecision(categories[i % categories.length]);
            latencies[i] = System.nanoTime() - start;
        }

        return new LatencyStats("decidePrecision 决策", latencies);
    }

    // ==================== 测试3: PhaseTransitionDetector.detectTransition ====================

    /**
     * 测试 PhaseTransitionDetector.detectTransition(float) 检测延迟
     * <p>
     * 方法签名: {@code PhaseType detectTransition(float frameDifferenceMetric)}
     * <ul>
     *   <li>参数: frameDifferenceMetric - 帧差异度量值（非负浮点数）</li>
     *   <li>返回: PhaseType 枚举（NONE, SCENE_CHANGE, LIGHTING_MUTATION, MOTION_CHANGE, PERIODIC_NOISE）</li>
     *   <li>性能保证: 检测延迟 < 0.05ms</li>
     * </ul>
     *
     * @return 延迟统计数据
     */
    private static LatencyStats benchmarkDetectTransition() {
        PhaseTransitionDetector detector = new PhaseTransitionDetector();

        // 预生成测试数据（模拟不同场景的帧差异值）
        float[] testMetrics = new float[100];
        for (int i = 0; i < testMetrics.length; i++) {
            // 大部分帧差异很小（稳定状态），偶尔出现突变
            testMetrics[i] = (i % 20 == 0) ? 0.5f + (float) Math.random() * 0.3f
                                           : 0.01f + (float) Math.random() * 0.05f;
        }

        // 预热阶段
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            detector.detectTransition(testMetrics[i % testMetrics.length]);
        }

        // 测量阶段
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            float metric = testMetrics[i % testMetrics.length];
            long start = System.nanoTime();
            detector.detectTransition(metric);
            latencies[i] = System.nanoTime() - start;
        }

        return new LatencyStats("detectTransition 检测", latencies);
    }

    // ==================== 测试4: BfsOcclusionEngine.findVisibleSections ====================

    /**
     * 测试 BfsOcclusionEngine.findVisibleSections() BFS 剔除延迟
     * <p>
     * 方法签名: {@code CullResult findVisibleSections(OcclusionTask rootSection,
     *              CameraView cameraView, boolean useOcclusion, int frame)}
     * <ul>
     *   <li>参数 rootSection: 根区块（相机所在区块），类型 OcclusionTask</li>
     *   <li>参数 cameraView: 相机视锥体信息，类型 CameraView</li>
     *   <li>参数 useOcclusion: 是否启用遮挡剔除</li>
     *   <li>参数 frame: 当前帧号（用于去重）</li>
     *   <li>返回: CullResult（包含可见区块列表、剔除率等）</li>
     * </ul>
     *
     * @return 延迟统计数据
     */
    private static LatencyStats benchmarkBfsOcclusion() {
        // 构建 BFS 测试图（7x7x7 网格，343 个区块）
        OcclusionTask rootSection = buildBfsTestGraph();

        // 构造相机视图（位于网格中心）
        CameraView cameraView = new CameraView(
            BFS_GRID_SIZE / 2,   // originChunkX
            BFS_GRID_SIZE / 2,   // originChunkY
            BFS_GRID_SIZE / 2,   // originChunkZ
            (BFS_GRID_SIZE / 2) * 16.0f + 8.0f,  // cameraX（世界坐标）
            (BFS_GRID_SIZE / 2) * 16.0f + 8.0f,  // cameraY
            (BFS_GRID_SIZE / 2) * 16.0f + 8.0f,  // cameraZ
            BFS_RENDER_DISTANCE  // renderDistance
        );

        BfsOcclusionEngine engine = new BfsOcclusionEngine();

        // 预热阶段
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            engine.findVisibleSections(rootSection, cameraView, true, i);
        }

        // 测量阶段
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            engine.findVisibleSections(rootSection, cameraView, true, i + WARMUP_ITERATIONS);
            latencies[i] = System.nanoTime() - start;
        }

        return new LatencyStats("findVisibleSections BFS", latencies);
    }

    /**
     * 构建 BFS 测试图
     * <p>
     * 创建一个 7x7x7 的三维区块网格，每个区块与相邻区块建立连接，
     * 使用 BfsOcclusionEngine.VISIBILITY_FULL 设置全方向可见性（模拟无遮挡的开放场景）。
     *
     * @return 根区块（网格中心位置的区块）
     */
    private static OcclusionTask buildBfsTestGraph() {
        int size = BFS_GRID_SIZE;
        OcclusionTask[][][] grid = new OcclusionTask[size][size][size];

        // 使用引擎预定义的全方向可见性常量（完全透明/空心区块）
        long fullVisibility = BfsOcclusionEngine.VISIBILITY_FULL;

        // 创建所有区块节点
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    OcclusionTask task = new OcclusionTask(x, y, z);
                    task.visibilityData = fullVisibility;
                    grid[x][y][z] = task;
                }
            }
        }

        // 建立相邻区块连接（6 方向）
        // 方向映射: DOWN(0,-Y), UP(1,+Y), NORTH(2,-Z), SOUTH(3,+Z), WEST(4,-X), EAST(5,+X)
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    // DOWN (y-1)
                    if (y > 0) {
                        grid[x][y][z].setNeighbor(
                            BfsOcclusionEngine.Direction.DOWN, grid[x][y - 1][z]);
                    }
                    // UP (y+1)
                    if (y < size - 1) {
                        grid[x][y][z].setNeighbor(
                            BfsOcclusionEngine.Direction.UP, grid[x][y + 1][z]);
                    }
                    // NORTH (z-1)
                    if (z > 0) {
                        grid[x][y][z].setNeighbor(
                            BfsOcclusionEngine.Direction.NORTH, grid[x][y][z - 1]);
                    }
                    // SOUTH (z+1)
                    if (z < size - 1) {
                        grid[x][y][z].setNeighbor(
                            BfsOcclusionEngine.Direction.SOUTH, grid[x][y][z + 1]);
                    }
                    // WEST (x-1)
                    if (x > 0) {
                        grid[x][y][z].setNeighbor(
                            BfsOcclusionEngine.Direction.WEST, grid[x - 1][y][z]);
                    }
                    // EAST (x+1)
                    if (x < size - 1) {
                        grid[x][y][z].setNeighbor(
                            BfsOcclusionEngine.Direction.EAST, grid[x + 1][y][z]);
                    }
                }
            }
        }

        // 返回中心区块作为根节点
        int center = size / 2;
        return grid[center][center][center];
    }

    // ==================== 测试5: KahanAccumulator.add ====================

    /**
     * 测试 KahanAccumulator.add() 累加延迟
     * <p>
     * 方法签名: {@code float add(float value)}
     * <ul>
     *   <li>参数: value - 要累加的浮点值</li>
     *   <li>返回: 更新后的累加和</li>
     *   <li>线程安全: 使用 CAS 循环保证原子性</li>
     * </ul>
     *
     * @return 延迟统计数据
     */
    private static LatencyStats benchmarkKahanAdd() {
        KahanAccumulator accumulator = new KahanAccumulator();

        // 预生成测试值（模拟帧统计数据的累加）
        float[] testValues = new float[100];
        for (int i = 0; i < testValues.length; i++) {
            testValues[i] = 0.1f + (float) Math.random() * 0.9f;
        }

        // 预热阶段
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            accumulator.add(testValues[i % testValues.length]);
        }

        // 测量阶段
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            float value = testValues[i % testValues.length];
            long start = System.nanoTime();
            accumulator.add(value);
            latencies[i] = System.nanoTime() - start;
        }

        return new LatencyStats("KahanAccumulator.add", latencies);
    }

    // ==================== 测试6: ConvergenceMonitor.updateEnergy ====================

    /**
     * 测试 ConvergenceMonitor.updateEnergy() 收敛更新延迟
     * <p>
     * 方法签名: {@code void updateEnergy(double energy)}
     * <ul>
     *   <li>参数: energy - 当前帧的图像能量（必须 >= 0）</li>
     *   <li>返回: void</li>
     *   <li>性能保证: 单次更新 < 0.05ms</li>
     * </ul>
     *
     * @return 延迟统计数据
     */
    private static LatencyStats benchmarkConvergenceUpdate() {
        ConvergenceMonitor monitor = new ConvergenceMonitor();

        // 预生成测试数据（模拟收敛过程中的能量值递减）
        double[] testEnergies = new double[100];
        for (int i = 0; i < testEnergies.length; i++) {
            testEnergies[i] = 100.0 - i * 0.5 + Math.random() * 2.0;
        }

        // 预热阶段
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            monitor.updateEnergy(testEnergies[i % testEnergies.length]);
        }

        // 测量阶段
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            double energy = testEnergies[i % testEnergies.length];
            long start = System.nanoTime();
            monitor.updateEnergy(energy);
            latencies[i] = System.nanoTime() - start;
        }

        return new LatencyStats("updateEnergy 收敛更新", latencies);
    }

    // ==================== 测试7: Lyapunov 冷路径不阻塞验证 ====================

    /**
     * 测试 LyapunovQualityChecker 冷路径不阻塞热路径
     * <p>
     * 验证方案：
     * <ol>
     *   <li>测量热路径单独运行的基准延迟</li>
     *   <li>启动后台线程持续执行 LyapunovQualityChecker.validateQuality()</li>
     *   <li>同时测量热路径延迟</li>
     *   <li>比较两者差异，验证冷路径不影响热路径</li>
     * </ol>
     *
     * 判定标准: 热路径 P95 延迟增幅 < 20%（允许少量调度抖动）
     *
     * @return 延迟统计数据（热路径在 Lyapunov 并行运行时的延迟）
     */
    private static LatencyStats benchmarkLyapunovNonBlocking() {
        // ===== 阶段A: 测量热路径基准延迟（无 Lyapunov 干扰） =====
        DynamicPrecisionManager precisionMgr = new DynamicPrecisionManager(PrecisionConfig.DEFAULT_1000FPS);
        PhaseTransitionDetector phaseDetector = new PhaseTransitionDetector();
        ConvergenceMonitor convergenceMonitor = new ConvergenceMonitor();
        KahanAccumulator accumulator = new KahanAccumulator();

        // 预填充帧时间数据
        for (int i = 0; i < 50; i++) {
            precisionMgr.updateFrameTime(0.8 + Math.random() * 0.3);
        }

        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            simulateOnFrameBegin(precisionMgr, phaseDetector, convergenceMonitor, accumulator, i);
        }

        // 测量基准延迟
        long[] baselineLatencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            simulateOnFrameBegin(precisionMgr, phaseDetector, convergenceMonitor, accumulator, i);
            baselineLatencies[i] = System.nanoTime() - start;
        }

        LatencyStats baselineStats = new LatencyStats("热路径基准(无Lyapunov)", baselineLatencies);

        // ===== 阶段B: 启动 Lyapunov 后台线程，同时测量热路径延迟 =====
        LyapunovQualityChecker lyapunovChecker = new LyapunovQualityChecker();
        AtomicBoolean lyapunovRunning = new AtomicBoolean(true);
        CountDownLatch lyapunovStarted = new CountDownLatch(1);
        AtomicLong lyapunovIterationCount = new AtomicLong(0);

        // Lyapunov 后台压力线程：持续执行 validateQuality
        Thread lyapunovThread = new Thread(() -> {
            lyapunovStarted.countDown();
            float energyBefore = 50000.0f;
            float energyAfter = 49500.0f;
            while (lyapunovRunning.get()) {
                // 模拟 Lyapunov 质量检测（CPU 密集型 Sobel 卷积计算）
                lyapunovChecker.validateQuality(energyBefore, energyAfter);
                lyapunovIterationCount.incrementAndGet();
                // 微调能量值模拟不同帧
                energyBefore += 10.0f;
                energyAfter += 9.5f;
            }
        }, "Lyapunov-Stress-Thread");
        lyapunovThread.setDaemon(true);

        // 启动 Lyapunov 压力线程
        lyapunovThread.start();

        // 等待 Lyapunov 线程启动
        try {
            lyapunovStarted.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return baselineStats;
        }

        // 让 Lyapunov 线程运行一小段时间建立稳定状态
        spinWait(100_000L); // 等待 100 微秒

        // 测量热路径在 Lyapunov 并行运行时的延迟
        long[] concurrentLatencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            simulateOnFrameBegin(precisionMgr, phaseDetector, convergenceMonitor, accumulator, i);
            concurrentLatencies[i] = System.nanoTime() - start;
        }

        // 停止 Lyapunov 线程
        lyapunovRunning.set(false);

        LatencyStats concurrentStats = new LatencyStats("热路径(Lyapunov并行)", concurrentLatencies);

        // 输出冷路径不阻塞验证结果
        printLyapunovVerification(baselineStats, concurrentStats, lyapunovIterationCount.get());

        return concurrentStats;
    }

    /**
     * 自旋等待（不使用 Thread.sleep，避免精度问题）
     *
     * @param waitNanos 等待时间（纳秒）
     */
    private static void spinWait(long waitNanos) {
        long end = System.nanoTime() + waitNanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    // ==================== 报告输出 ====================

    /**
     * 输出测试横幅
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("====================================================================");
        System.out.println("  Renderium 集成性能基准测试");
        System.out.println("  目标: 1000-2000 FPS (0.5-1ms/帧)");
        System.out.println("  预热: " + WARMUP_ITERATIONS + " 次迭代");
        System.out.println("  测量: " + MEASURE_ITERATIONS + " 次迭代");
        System.out.println("  计时: System.nanoTime()");
        System.out.println("====================================================================");
        System.out.println();
    }

    /**
     * 输出汇总报告
     *
     * @param allStats 所有组件的延迟统计数据
     */
    private static void printReport(LatencyStats[] allStats) {
        System.out.println();
        System.out.println("====================================================================");
        System.out.println("  性能基准测试结果");
        System.out.println("====================================================================");
        System.out.println();
        System.out.printf("  %-35s | %8s | %8s | %8s | %7s | %s%n",
            "组件", "P50(μs)", "P95(μs)", "P99(μs)", "FPS", "达标");
        System.out.println("  " + "-".repeat(37) + "-+-" + "-".repeat(8) + "-+-"
            + "-".repeat(8) + "-+-" + "-".repeat(8) + "-+-" + "-".repeat(7) + "-+-" + "-".repeat(4));

        int passCount = 0;
        int failCount = 0;
        for (LatencyStats stats : allStats) {
            if (stats != null) {
                System.out.println(stats.formatReport());
                if (stats.isTargetMet()) {
                    passCount++;
                } else {
                    failCount++;
                }
            }
        }

        System.out.println();
        System.out.println("  达标标准: P95 延迟 <= " + String.format("%.1f", TARGET_FRAME_TIME_MS) + "ms ("
            + TARGET_FPS + " FPS)");
        System.out.println("  等效FPS: 1000ms / P95延迟(ms)");
        System.out.println();

        // 汇总判定
        System.out.println("  +------------------------------------+");
        if (failCount == 0) {
            System.out.println("  |  ALL PASSED - " + passCount + "/" + (passCount + failCount)
                + " 组件达标  |");
        } else {
            System.out.println("  |  " + failCount + " FAILED - "
                + passCount + "/" + (passCount + failCount) + " 组件达标     |");
        }
        System.out.println("  +------------------------------------+");
        System.out.println();
    }

    /**
     * 输出 Lyapunov 冷路径不阻塞验证结果
     *
     * @param baselineStats   基准延迟（无 Lyapunov）
     * @param concurrentStats 并行延迟（Lyapunov 运行中）
     * @param lyapunovIterations Lyapunov 执行的迭代次数
     */
    private static void printLyapunovVerification(
            LatencyStats baselineStats,
            LatencyStats concurrentStats,
            long lyapunovIterations) {

        System.out.println();
        System.out.println("  +-- Lyapunov 冷路径不阻塞验证 ------------------+");
        System.out.printf("  |  基准 P95:    %8.1f μs (无 Lyapunov)       |%n",
            baselineStats.p95Ns / NS_TO_US);
        System.out.printf("  |  并行 P95:    %8.1f μs (Lyapunov 运行中)    |%n",
            concurrentStats.p95Ns / NS_TO_US);

        // 计算延迟增幅百分比
        double increasePercent = 0.0;
        if (baselineStats.p95Ns > 0) {
            increasePercent = ((double) (concurrentStats.p95Ns - baselineStats.p95Ns)
                / baselineStats.p95Ns) * 100.0;
        }
        System.out.printf("  |  延迟增幅:    %+.1f%%                           |%n", increasePercent);

        // 判定：增幅 < 20% 视为不阻塞
        boolean nonBlocking = increasePercent < 20.0;
        String verdict = nonBlocking ? "PASS (不阻塞)" : "FAIL (存在阻塞)";
        System.out.printf("  |  判定:        %-30s |%n", verdict);
        System.out.printf("  |  Lyapunov 迭代: %-28d |%n", lyapunovIterations);
        System.out.println("  +------------------------------------------------+");
    }
}
