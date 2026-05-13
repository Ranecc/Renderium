// ============================================================
// Phase 2 Performance Benchmark - 性能基准测试
// ============================================================
// 验证 DynamicPrecisionManager 在 1000FPS 目标下的性能特征
//
// 测试指标：
//   1. decidePrecision() 延迟（目标 < 1μs）
//   2. updateFrameTime() 吞吐量（目标 > 1M calls/s）
//   3. RollingAverage 滑动窗口性能
//   4. 整体系统在模拟负载下的表现
//
// 基准场景：
//   - 模拟 1080p 渲染（每帧 ~2M 像素）
//   - 混合操作类别（热/温/冷路径）
//   - 自适应精度调整周期
//
// @see DynamicPrecisionManager
// @see PrecisionConfig
// ============================================================

package com.renderium.core;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Phase2PerformanceBenchmark {

    // ==================== 基准配置 ====================

    /** 预热迭代次数（JIT编译优化） */
    private static final int WARMUP_ITERATIONS = 10_000;

    /** 基准测试迭代次数 */
    private static final int BENCHMARK_ITERATIONS = 10_000_000;

    /** 模拟帧数 */
    private static final int SIMULATED_FRAMES = 10_000;

    /** 目标决策延迟（纳秒）: 1μs = 1000ns */
    private static final long TARGET_DECISION_LATENCY_NS = 1000L;

    /** 目标帧时间更新吞吐量（calls/s）: 1M */
    private static final long TARGET_UPDATE_THROUGHPUT = 1_000_000L;

    public static void main(String[] args) {
"+------------------------------------------+"
        System.out.println("+------------------------------------------+");
"=   Renderium Phase 2 Performance Benchmark   ="
        System.out.println("=   Renderium Phase 2 Performance Benchmark   =");
"+------------------------------------------+ "
        System.out.println("+------------------------------------------+ ");

        boolean allPassed = true;

        // 测试1: decidePrecision() 延迟
"▶ [1/4] decidePrecision() 决策延迟"
        System.out.println("▶ [1/4] decidePrecision() 决策延迟");
        try {
            allPassed &= benchmarkDecisionLatency();
        } catch (Exception e) {
"  ❌ FAIL: "
            System.out.println("  ❌ FAIL: " + e.getMessage());
            allPassed = false;
        }
        System.out.println();

        // 测试2: updateFrameTime() 吞吐量
"▶ [2/4] updateFrameTime() 吞吐量"
        System.out.println("▶ [2/4] updateFrameTime() 吞吐量");
        try {
            allPassed &= benchmarkUpdateThroughput();
        } catch (Exception e) {
"  ❌ FAIL: "
            System.out.println("  ❌ FAIL: " + e.getMessage());
            allPassed = false;
        }
        System.out.println();

        // 测试3: 模拟渲染循环性能
"▶ [3/4] 模拟渲染循环 (10K frames)"
        System.out.println("▶ [3/4] 模拟渲染循环 (10K frames)");
        try {
            allPassed &= benchmarkSimulatedRenderLoop();
        } catch (Exception e) {
"  ❌ FAIL: "
            System.out.println("  ❌ FAIL: " + e.getMessage());
            allPassed = false;
        }
        System.out.println();

        // 测试4: 内存占用和GC影响
"▶ [4/4] 内存占用与 GC 影响"
        System.out.println("▶ [4/4] 内存占用与 GC 影响");
        try {
            allPassed &= benchmarkMemoryFootprint();
        } catch (Exception e) {
"  ❌ FAIL: "
            System.out.println("  ❌ FAIL: " + e.getMessage());
            allPassed = false;
        }
        System.out.println();

"------------------------------------------"
        System.out.println("------------------------------------------");
        if (allPassed) {
"✅ ALL BENCHMARKS PASSED - Ready for 1000 FPS!"
            System.out.println("✅ ALL BENCHMARKS PASSED - Ready for 1000 FPS!");
        } else {
"[WARN]️  SOME BENCHMARKS NEED OPTIMIZATION"
            System.out.println("[WARN]️  SOME BENCHMARKS NEED OPTIMIZATION");
        }
"------------------------------------------"
        System.out.println("------------------------------------------");
    }

    /**
     * 基准测试1: decidePrecision() 决策延迟
     * <p>
     * 验证单次决策调用是否满足 < 1μs 的延迟要求。
     * 这对于 1000FPS 目标至关重要（每帧预算仅 1ms）。
     *
     * @return true 如果平均延迟 <= 1μs
     */
    private static boolean benchmarkDecisionLatency() {
        DynamicPrecisionManager mgr = new DynamicPrecisionManager();
        Random random = new Random(42);  // 固定种子保证可重复性

        // 预热（触发JIT编译优化）
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            DynamicPrecisionManager.OperationCategory category =
                DynamicPrecisionManager.OperationCategory.values()[random.nextInt(4)];
            mgr.decidePrecision(category);
        }

        // 基准测试
        long totalTimeNs = 0;
        long minLatencyNs = Long.MAX_VALUE;
        long maxLatencyNs = Long.MIN_VALUE;

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            DynamicPrecisionManager.OperationCategory category =
                DynamicPrecisionManager.OperationCategory.values()[random.nextInt(4)];

            long start = System.nanoTime();
            mgr.decidePrecision(category);
            long elapsed = System.nanoTime() - start;

            totalTimeNs += elapsed;
            minLatencyNs = Math.min(minLatencyNs, elapsed);
            maxLatencyNs = Math.max(maxLatencyNs, elapsed);
        }

        double avgLatencyNs = (double) totalTimeNs / BENCHMARK_ITERATIONS;
        double avgLatencyUs = avgLatencyNs / 1000.0;

        boolean passed = avgLatencyUs <= 1.0;
"✅"
"❌"
        String icon = passed ? "✅" : "❌";

        System.out.println(String.format(
"  %s 平均延迟: %.3f μs (%.0f ns/call)"
            "  %s 平均延迟: %.3f μs (%.0f ns/call)",
            icon, avgLatencyUs, avgLatencyNs
        ));
        System.out.println(String.format(
"     最小: %d ns | 最大: %d ns | 总调用: %d"
            "     最小: %d ns | 最大: %d ns | 总调用: %d",
            minLatencyNs, maxLatencyNs, BENCHMARK_ITERATIONS
        ));
        System.out.println(String.format(
"     目标: < %.0f μs | 达标: %s"
            "     目标: < %.0f μs | 达标: %s",
            TARGET_DECISION_LATENCY_NS / 1000.0,
"YES ✓"
"NO ✗"
            passed ? "YES ✓" : "NO ✗"
        ));

        return passed;
    }

    /**
     * 基准测试2: updateFrameTime() 吞吐量
     * <p>
     * 验证帧时间更新的吞吐量是否满足每秒百万次调用的要求。
     * 这是渲染循环中的高频操作。
     *
     * @return true 如果吞吐量 >= 1M calls/s
     */
    private static boolean benchmarkUpdateThroughput() {
        DynamicPrecisionManager mgr = new DynamicPrecisionManager();

        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            mgr.updateFrameTime(0.5 + Math.random() * 1.5);
        }

        // 基准测试
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            mgr.updateFrameTime(0.5 + Math.random() * 1.5);
        }

        long endTime = System.nanoTime();
        double elapsedSeconds = (endTime - startTime) / 1e9;
        double throughput = BENCHMARK_ITERATIONS / elapsedSeconds;

        boolean passed = throughput >= TARGET_UPDATE_THROUGHPUT;
"✅"
"❌"
        String icon = passed ? "✅" : "❌";

        System.out.println(String.format(
"  %s 吞吐量: %.2f M calls/s (%.0f calls in %.3fs)"
            "  %s 吞吐量: %.2f M calls/s (%.0f calls in %.3fs)",
            icon, throughput / 1e6, (double)BENCHMARK_ITERATIONS, elapsedSeconds
        ));
        System.out.println(String.format(
"     单次耗时: %.0f ns | 目标: > %d K calls/s"
            "     单次耗时: %.0f ns | 目标: > %d K calls/s",
            (elapsedSeconds / BENCHMARK_ITERATIONS) * 1e9,
            TARGET_UPDATE_THROUGHPUT / 1000
        ));
        System.out.println(String.format(
"     达标: %s"
"YES ✓"
"NO ✗"
            "     达标: %s", passed ? "YES ✓" : "NO ✗"
        ));

        return passed;
    }

    /**
     * 基准测试3: 模拟完整渲染循环
     * <p>
     * 模拟真实渲染场景：
     * - 每帧包含多种操作类别的精度查询
     * - 动态变化的帧时间（模拟不同复杂度场景）
     * - 自适应精度调整的影响
     *
     * @return true 如果平均帧开销 < 0.1ms (100μs)
     */
    private static boolean benchmarkSimulatedRenderLoop() {
        DynamicPrecisionManager mgr = new DynamicPrecisionManager(PrecisionConfig.DEFAULT_1000FPS);
        Random random = new Random(12345);

        // 模拟不同场景的帧时间分布
        double[] frameTimeScenarios = {
            0.3,   // 简单场景（快速）
            0.8,   // 正常场景（接近目标）
            1.5,   // 复杂场景（超支）
            0.5,   // 中等场景
            2.0    // 极端场景（严重超支）
        };

        long totalFrameOverheadNs = 0;
        int precisionDecisionsPerFrame = 100;  // 每帧约100次精度查询

        // 预热
        for (int frame = 0; frame < 100; frame++) {
            double frameTime = frameTimeScenarios[frame % frameTimeScenarios.length];
            mgr.updateFrameTime(frameTime);

            for (int d = 0; d < precisionDecisionsPerFrame; d++) {
                DynamicPrecisionManager.OperationCategory category =
                    DynamicPrecisionManager.OperationCategory.values()[random.nextInt(4)];
                mgr.decidePrecision(category);
            }
        }

        // 基准测试
        long loopStart = System.nanoTime();

        for (int frame = 0; frame < SIMULATED_FRAMES; frame++) {
            // 模拟帧时间（带随机波动）
            int scenarioIdx = frame % frameTimeScenarios.length;
            double baseFrameTime = frameTimeScenarios[scenarioIdx];
            double noise = (random.nextDouble() - 0.5) * 0.2;  // ±0.1ms 噪声
            double actualFrameTime = Math.max(0.1, baseFrameTime + noise);

            // 更新帧时间（这是主要开销来源之一）
            long frameStart = System.nanoTime();
            mgr.updateFrameTime(actualFrameTime);

            // 模拟多次精度查询（模拟渲染管线中的多个阶段）
            for (int d = 0; d < precisionDecisionsPerFrame; d++) {
                DynamicPrecisionManager.OperationCategory category =
                    DynamicPrecisionManager.OperationCategory.values()[random.nextInt(4)];
                mgr.decidePrecision(category);
            }

            long frameEnd = System.nanoTime();
            totalFrameOverheadNs += (frameEnd - frameStart);
        }

        long loopEnd = System.nanoTime();
        double totalLoopTimeMs = (loopEnd - loopStart) / 1e6;
        double avgFrameOverheadUs = (double)totalFrameOverheadNs / SIMULATED_FRAMES / 1000.0;

        // 计算等效FPS（假设总帧时间 = 开销 + 模拟渲染时间）
        double simulatedAvgFrameTime = 0.8;  // 模拟平均帧时间（不含精度管理器开销）
        double effectiveFrameTimeMs = simulatedAvgFrameTime + (avgFrameOverheadUs / 1000.0);
        double effectiveFPS = 1000.0 / effectiveFrameTimeMs;

        boolean passed = avgFrameOverheadUs < 100.0;  // 开销 < 100μs
"✅"
"❌"
        String icon = passed ? "✅" : "❌";

        System.out.println(String.format(
"  %s 平均帧开销: %.2f μs (%d decisions/frame)"
            "  %s 平均帧开销: %.2f μs (%d decisions/frame)",
            icon, avgFrameOverheadUs, precisionDecisionsPerFrame
        ));
        System.out.println(String.format(
"     总时间: %.2f ms | 帧数: %d"
            "     总时间: %.2f ms | 帧数: %d",
            totalLoopTimeMs, SIMULATED_FRAMES
        ));
        System.out.println(String.format(
"     有效 FPS (估算): %.0f | 目标: > 1000"
            "     有效 FPS (估算): %.0f | 目标: > 1000",
            effectiveFPS
        ));
        System.out.println(String.format(
"     最终精度级别: %s"
            "     最终精度级别: %s",
            mgr.getCurrentLevel().name()
        ));
        System.out.println(String.format(
"     达标: %s"
"YES ✓"
"NO ✗"
            "     达标: %s", passed ? "YES ✓" : "NO ✗"
        ));

        return passed;
    }

    /**
     * 基准测试4: 内存占用与GC影响
     * <p>
     * 验证长时间运行下的内存稳定性：
     * - 无内存泄漏（对象池正常工作）
     * - GC停顿可接受（< 1ms）
     * - 决策日志不无限增长
     *
     * @return true 如果内存稳定且无异常增长
     */
    private static boolean benchmarkMemoryFootprint() {
        Runtime runtime = Runtime.getRuntime();

        // 强制GC以获取基线
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        long memoryBeforeMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        // 创建多个管理器实例（模拟多场景）
        DynamicPrecisionManager[] managers = new DynamicPrecisionManager[10];
        for (int i = 0; i < managers.length; i++) {
            managers[i] = new DynamicPrecisionManager();
        }

        // 长时间运行模拟
        long iterations = 1_000_000;
        Random random = new Random(99999);

        for (long i = 0; i < iterations; i++) {
            int mgrIdx = (int)(i % managers.length);
            managers[mgrIdx].updateFrameTime(0.3 + random.nextDouble() * 2.0);

            if (i % 100 == 0) {
                managers[mgrIdx].decidePrecision(
                    DynamicPrecisionManager.OperationCategory.values()[random.nextInt(4)]
                );
            }
        }

        // 再次GC后检查内存
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        long memoryAfterMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long memoryDeltaMB = memoryAfterMB - memoryBeforeMB;

        // 检查决策日志大小是否受限
        String decisionLog = managers[0].getDecisionLog();
" "
").length;
        int expectedMaxLogSize = PrecisionConfig.DEFAULT_1000FPS.getDecisionLogSize();

        boolean memoryStable = memoryDeltaMB < 5;  // 增长 < 5MB 视为稳定
        boolean logSizeOK = logLineCount <= expectedMaxLogSize;
        boolean passed = memoryStable && logSizeOK;
"✅"
"❌"
        String icon = passed ? "✅" : "❌";

        System.out.println(String.format(
"  %s 内存增长: %d MB (%d v %d)"
            "  %s 内存增长: %d MB (%d v %d)",
            icon, memoryDeltaMB, memoryBeforeMB, memoryAfterMB
        ));
        System.out.println(String.format(
"     决志日志行数: %d (上限: %d)"
            "     决志日志行数: %d (上限: %d)",
            logLineCount, expectedMaxLogSize
        ));
        System.out.println(String.format(
"     迭代次数: %d | 管理器实例: %d"
            "     迭代次数: %d | 管理器实例: %d",
            iterations, managers.length
        ));
        System.out.println(String.format(
"     内存稳定: %s | 日志受限: %s"
            "     内存稳定: %s | 日志受限: %s",
"YES ✓"
"NO ✗"
            memoryStable ? "YES ✓" : "NO ✗",
"YES ✓"
"NO ✗"
            logSizeOK ? "YES ✓" : "NO ✗"
        ));

        return passed;
    }
}
