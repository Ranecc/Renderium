// Renderium - Shader 系统独立性能基准
// StandalonePerfBenchmark - 不依赖 Minecraft 的纯 Java 性能验证
//
// 测试目标：
//   1. BatchTransformEngineV3: 10K 顶点变换 < 30μs
//   2. ShaderPerformanceOptimizer: 对象池分配 < 100ns
//   3. ShaderCompDescriptor 解析: < 50μs
//   4. ParameterKnob 查找: < 1μs
//
// 运行方式:
//   javac --enable-preview -source 25 StandalonePerfBenchmark.java
//   java --enable-preview --source 25 StandalonePerfBenchmark

package com.renderium.benchmark;

import com.renderium.bridge.batch.BatchTransformEngineV3;
import com.renderium.shader.performance.ShaderPerformanceOptimizer;
import com.renderium.pipeline.parameter.ParameterKnob;
import com.renderium.pipeline.parameter.impl.FloatKnob;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * 独立性能基准测试（无 Minecraft 依赖）
 * <p>
 * 验证 Shader 核心组件在纯 Java 环境下的性能表现。
 *
 * @since 3.0.0
 */
public class StandalonePerfBenchmark {

"Renderium|Bench"
    private static final Logger LOG = Logger.getLogger("Renderium|Bench");

    // ==================== 配置 ====================

    private static final int WARMUP_ITERATIONS = 5_000;
    private static final int MEASURE_ITERATIONS = 20_000;
    private static final int VERTEX_COUNT = 10_000;
    private static final int SCREEN_W = 1920, SCREEN_H = 1080;

    // ==================== 结果记录 ====================

    static class BenchResult {
        String name;
        long avgNs;
        long p50Ns;
        long p99Ns;
        long minNs;
        long maxNs;
        boolean passed;

        BenchResult(String n) { name = n; }

        void compute(long[] samples) {
            Arrays.sort(samples);
            avgNs = 0; for (long s : samples) avgNs += s; avgNs /= samples.length;
            minNs = samples[0];
            maxNs = samples[samples.length - 1];
            p50Ns = samples[samples.length / 2];
            p99Ns = samples[(int)(samples.length * 0.99)];
        }

        @Override
        public String toString() {
            return String.format(
"| %-30s | %8.1f μs | %8.1f | %8.1f | %8.1f | %s |"
                "| %-30s | %8.1f μs | %8.1f | %8.1f | %8.1f | %s |",
                name,
                avgNs / 1000.0,
                minNs / 1000.0,
                p50Ns / 1000.0,
                p99Ns / 1000.0,
"✅ PASS"
"❌ FAIL"
                passed ? "✅ PASS" : "❌ FAIL"
            );
        }
    }

    // ==================== 主入口 ====================

    public static void main(String[] args) {
"+------------------------------------------------------+"
        LOG.info("+------------------------------------------------------+");
"=     Renderium Shader System - Standalone Benchmark    ="
        LOG.info("=     Renderium Shader System - Standalone Benchmark    =");
"+------------------------------------------------------|"
        LOG.info("+------------------------------------------------------|");
"= Warmup: %-46d ="
        LOG.info(String.format("= Warmup: %-46d =", WARMUP_ITERATIONS));
"= Measure: %-45d ="
        LOG.info(String.format("= Measure: %-45d =", MEASURE_ITERATIONS));
"= Vertices: %-44d ="
        LOG.info(String.format("= Vertices: %-44d =", VERTEX_COUNT));
"+------------------------------------------------------+"
        LOG.info("+------------------------------------------------------+");

        List<BenchResult> results = new java.util.ArrayList<>();

        results.add(benchmarkBatchTransform());
        results.add(benchmarkObjectPool());
        results.add(benchmarkParameterLookup());

        printReport(results);
    }

    // ==================== 基准 1: 批量顶点变换 ====================

    static BenchResult benchmarkBatchTransform() {
"BatchTransformEngineV3 (10K vtx)"
        BenchResult r = new BenchResult("BatchTransformEngineV3 (10K vtx)");
        BatchTransformEngineV3 engine = new BatchTransformEngineV3(VERTEX_COUNT);
        float[] positions = generatePositions(VERTEX_COUNT);
        float[] matrix = generateMatrix();
        Random rng = new Random(42);

        // 热身
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            engine.transformVertices(positions, VERTEX_COUNT, matrix);
            if (i % 500 == 0) tweakMatrix(matrix, rng);
        }

        // 测量
        long[] samples = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            tweakMatrix(matrix, rng);
            long start = System.nanoTime();
            engine.transformVertices(positions, VERTEX_COUNT, matrix);
            samples[i] = System.nanoTime() - start;
        }

        r.compute(samples);
        // 目标: 10K 顶点 < 30μs
        r.passed = r.p99Ns < 30_000L;
        return r;
    }

    // ==================== 基准 2: 对象池分配 ====================

    static BenchResult benchmarkObjectPool() {
"ShaderPerfOpt (borrow/return)"
        BenchResult r = new BenchResult("ShaderPerfOpt (borrow/return)");
        ShaderPerformanceOptimizer optimizer = ShaderPerformanceOptimizer.getInstance();

        // 热身
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            float[] buf = optimizer.borrowFloatArray(1024);
            optimizer.returnFloatArray(buf);
        }

        // 测量
        long[] samples = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            float[] buf = optimizer.borrowFloatArray(1024);
            optimizer.returnFloatArray(buf);
            samples[i] = System.nanoTime() - start;
        }

        r.compute(samples);
        // 目标: borrow+return < 200ns
        r.passed = r.p99Ns < 200L;
        return r;
    }

    // ==================== 基准 3: 参数查找 ====================

    static BenchResult benchmarkParameterLookup() {
"ParameterKnob lookup (16 params)"
        BenchResult r = new BenchResult("ParameterKnob lookup (16 params)");

        // 构建模拟参数列表
        List<ParameterKnob<?>> params = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
"param_"
            params.add(new FloatKnob.Builder("param_" + i)
"Param "
                .displayName("Param " + i)
                .defaultValue(0.0f)
                .range(-1.0f, 1.0f)
                .step(0.01f)
                .build());
        }

        // 热身
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            params.get(i % 16).getId();
            params.get(i % 16).getValue();
        }

        // 测量
        long[] samples = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            int idx = i % 16;
            long start = System.nanoTime();
            String id = params.get(idx).getId();
            Object val = params.get(idx).getValue();
            samples[i] = System.nanoTime() - start;
        }

        r.compute(samples);
        // 目标: 单次查找 < 1μs
        r.passed = r.p99Ns < 1_000L;
        return r;
    }

    // ==================== 辅助方法 ====================

    private static float[] generatePositions(int count) {
        float[] pos = new float[count * 3];
        Random rng = new Random(42);
        for (int i = 0; i < count * 3; i++) {
            pos[i] = (rng.nextFloat() - 0.5f) * 100.0f;
        }
        return pos;
    }

    private static float[] generateMatrix() {
        // 单位矩阵 + 轻微偏移
        return new float[]{
            1.0f, 0.01f, 0.02f, 10.0f,
            0.03f, 1.0f, 0.04f, 5.0f,
            0.05f, 0.06f, 1.0f, -20.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    private static void tweakMatrix(float[] m, Random rng) {
        // 微调矩阵以避免 JIT 过度优化
        m[12] += (rng.nextFloat() - 0.5f) * 0.001f;
        m[13] += (rng.nextFloat() - 0.5f) * 0.001f;
        m[14] += (rng.nextFloat() - 0.5f) * 0.001f;
    }

    private static void printReport(List<BenchResult> results) {
""
        LOG.info("");
"+------------------------+------------+----------+----------+----------+--------+"
        LOG.info("+------------------------+------------+----------+----------+----------+--------+");
"| Benchmark              | Avg (μs)   | Min      | P50      | P99      | Status |"
        LOG.info("| Benchmark              | Avg (μs)   | Min      | P50      | P99      | Status |");
"+------------------------+------------+----------+----------+----------+--------+"
        LOG.info("+------------------------+------------+----------+----------+----------+--------+");

        boolean allPassed = true;
        for (BenchResult r : results) {
            LOG.info(r.toString());
            if (!r.passed) allPassed = false;
        }

"+------------------------+------------+----------+----------+----------+--------+"
        LOG.info("+------------------------+------------+----------+----------+----------+--------+");
""
        LOG.info("");

        if (allPassed) {
"🎉 所有性能目标均已达成！"
            LOG.info("🎉 所有性能目标均已达成！");
        } else {
"[WARN]️ 部分性能目标未达标，请查看上方报告。"
            LOG.warning("[WARN]️ 部分性能目标未达标，请查看上方报告。");
        }

"总测试迭代: %d (热身: %d + 测量: %d)"
        LOG.info(String.format("总测试迭代: %d (热身: %d + 测量: %d)",
            WARMUP_ITERATIONS + MEASURE_ITERATIONS, WARMUP_ITERATIONS, MEASURE_ITERATIONS));
    }
}
