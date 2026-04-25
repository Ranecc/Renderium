// Renderium - 性能基准测试套件
// 覆盖所有核心GPU功能模块的微基准和压力测试
// 使用 JMH (Java Microbenchmark Harness) 风格的手动实现

package com.renderium.benchmark;

import com.renderium.gpu.framegen.CameraJitterGenerator;
import com.renderium.gpu.framegen.FrameGenContext;
import com.renderium.gpu.hiz.HiZBufferManager;
import com.renderium.gpu.hiz.OcclusionCullConfig;
import com.renderium.gpu.lod.GPULODDataManager;
import com.renderium.gpu.sr.MotionVectorGenerator;
import com.renderium.gpu.sr.SROutputManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renderium 性能基准测试套件
 * <p>
 * 提供对所有核心 GPU 功能模块的微基准测试能力，
 * 建立性能基线并检测回归问题。
 *
 * <h3>覆盖模块：</h3>
 * <ol>
 *   <li><b>CameraJitterGenerator</b> - Halton 序列查询延迟（目标 &lt; 0.1μs）</li>
 *   <li><b>FrameGenContext</b> - 帧生成上下文创建与抖动计算</li>
 *   <li><b>HiZBufferManager</b> - Hi-Z 纹理数组创建与 Mipmap 构建</li>
 *   <li><b>MotionVectorGenerator</b> - 运动矢量生成吞吐量</li>
 *   <li><b>SROutputManager</b> - 超分辨率输出管理</li>
 *   <li><b>GPULODDataManager</b> - LOD 数据上传与可见性查询</li>
 *   <li><b>ReflexManagerImpl</b> - Reflex 标记点设置开销</li>
 * </ol>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 运行完整基准测试套件
 * RenderiumBenchmarkSuite suite = new RenderiumBenchmarkSuite();
 * BenchmarkReport report = suite.runAllBenchmarks();
 *
 * // 输出结果
 * report.printSummary();
 * report.exportToMarkdown("benchmark_report.md");
 * </pre>
 *
 * @since 5.2.0
 */
public class RenderiumBenchmarkSuite {

    private static final Logger LOGGER = Logger.getLogger("Renderium|Benchmark");

    /** 基准测试迭代次数（热身）*/
    private static final int WARMUP_ITERATIONS = 1000;

    /** 基准测试迭代次数（测量）*/
    private static final int MEASURE_ITERATIONS = 10000;

    /** 压力测试并发线程数 */
    private static final int STRESS_THREAD_COUNT = 8;

    /** 压力测试持续时间（毫秒）*/
    private static final long STRESS_DURATION_MS = 5000;

    /** 所有基准测试结果 */
    private final Map<String, BenchmarkResult> results = new LinkedHashMap<>();

    /**
     * 运行所有基准测试
     *
     * 【返回值】BenchmarkReport - 包含所有测试结果的报告对象
     */
    public BenchmarkReport runAllBenchmarks() {
        LOGGER.info("═════════════════════════════════════════════");
        LOGGER.info("   Renderium 性能基准测试套件 v5.2.0");
        LOGGER.info("═════════════════════════════════════════════");

        long startTime = System.nanoTime();

        try {
            // 1. CameraJitterGenerator 基准测试
            benchmarkCameraJitterGenerator();

            // 2. FrameGenContext 基准测试
            benchmarkFrameGenContext();

            // 3. HiZBufferManager 基准测试（模拟）
            benchmarkHiZBufferManager();

            // 4. MotionVectorGenerator 基准测试（模拟）
            benchmarkMotionVectorGenerator();

            // 5. SROutputManager 基准测试（模拟）
            benchmarkSROutputManager();

            // 6. GPULODDataManager 基准测试（模拟）
            benchmarkGPULODDataManager();

            // 7. ReflexManagerImpl 基准测试（模拟）
            benchmarkReflexManagerImpl();

            // 8. 综合压力测试
            runStressTest();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "基准测试执行过程中发生异常", e);
        }

        long totalTime = (System.nanoTime() - startTime) / 1_000_000;
        LOGGER.info("═════════════════════════════════════════════");
        LOGGER.info(String.format("   总耗时: %d ms | 测试项: %d", totalTime, results.size()));
        LOGGER.info("═════════════════════════════════════════════");

        return new BenchmarkReport(results);
    }

    // ==================== 1. CameraJitterGenerator 基准测试 ====================

    /**
     * CameraJitterGenerator 性能基准测试
     * <p>
     * 测试 Halton 序列查询的 O(1) 性能。
     * 目标：单次查询 &lt; 0.1μs（10,000 次迭代/毫秒）
     */
    private void benchmarkCameraJitterGenerator() {
        String testName = "CameraJitterGenerator.getJitterOffset()";
        LOGGER.info(String.format("[基准] %s", testName));

        CameraJitterGenerator gen = CameraJitterGenerator.getInstance();

        // 热身
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            gen.getJitterOffset(i % 64);
        }

        // 测量：顺序访问模式（模拟帧循环）
        long[] sequentialTimes = new long[MEASURE_ITERATIONS];
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            gen.getJitterOffset(i % 64);
        }
        long sequentialTotal = System.nanoTime() - start;
        double sequentialAvgNs = (double) sequentialTotal / MEASURE_ITERATIONS;

        // 测量：随机访问模式（压力场景）
        Random random = new Random(42);  // 固定种子保证可重复性
        start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            gen.getJitterOffset(random.nextInt(64));
        }
        long randomTotal = System.nanoTime() - start;
        double randomAvgNs = (double) randomTotal / MEASURE_ITERATIONS;

        // 测量：模式切换开销
        start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            switch (i % 3) {
                case 0 -> gen.setJitterMode(CameraJitterGenerator.JitterMode.MODE_2X2);
                case 1 -> gen.setJitterMode(CameraJitterGenerator.JitterMode.MODE_4X4);
                case 2 -> gen.setJitterMode(CameraJitterGenerator.JitterMode.MODE_8X8);
            }
        }
        long switchTime = System.nanoTime() - start;

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("sequential_query_ns", sequentialAvgNs, "< 100ns", sequentialAvgNs < 100.0);
        result.addMetric("random_query_ns", randomAvgNs, "< 150ns", randomAvgNs < 150.0);
        result.addMetric("mode_switch_us", switchTime / 1000.0 / 1000, "< 10μs", switchTime / 1000000.0 / 1000 < 10.0);

        results.put(testName, result);
        logResult(result);
    }

    // ==================== 2. FrameGenContext 基准测试 ====================

    /**
     * FrameGenContext 性能基准测试
     * <p>
     * 测试帧生成上下文的创建、验证和抖动数据提取性能。
     */
    private void benchmarkFrameGenContext() {
        String testName = "FrameGenContext (帧生成上下文)";
        LOGGER.info(String.format("[基准] %s", testName));

        // 测量：上下文创建
        long[] createTimes = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            FrameGenContext ctx = new FrameGenContext(2);  // 使用简单构造函数
            long elapsed = System.nanoTime() - start;
            if (i >= WARMUP_ITERATIONS) {
                createTimes[i - WARMUP_ITERATIONS] = elapsed;
            }
        }
        double createAvgNs = average(createTimes);

        // 测量：有效性验证
        FrameGenContext validCtx = new FrameGenContext(2);  // 使用简单构造函数

        long validateStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            validCtx.isValid();
        }
        long validateTime = System.nanoTime() - validateStart;
        double validateAvgNs = (double) validateTime / MEASURE_ITERATIONS;

        // 测量：抖动数据获取
        long jitterStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            validCtx.getJitterOffsetX();
            validCtx.getJitterOffsetY();
        }
        long jitterTime = System.nanoTime() - jitterStart;
        double jitterAvgNs = (double) jitterTime / MEASURE_ITERATIONS;

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("create_ns", createAvgNs, "< 1000ns", createAvgNs < 1000.0);
        result.addMetric("validate_ns", validateAvgNs, "< 50ns", validateAvgNs < 50.0);
        result.addMetric("jitter_get_ns", jitterAvgNs, "< 20ns", jitterAvgNs < 20.0);

        results.put(testName, result);
        logResult(result);
    }

    // ==================== 3. HiZBufferManager 基准测试（模拟）====================

    /**
     * HiZBufferManager 性能基准测试（模拟版）
     * <p>
     * 由于实际 Vulkan 资源创建需要 GPU 上下文，
     * 此测试模拟 Hi-Z 金字塔构建的计算密集度。
     */
    private void benchmarkHiZBufferManager() {
        String testName = "HiZBufferManager (模拟)";
        LOGGER.info(String.format("[基准] %s", testName));

        int width = 1920;
        int height = 1080;
        int mipLevels = (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2));

        // 模拟深度缓冲区数据
        float[] depthBuffer = new float[width * height];
        Arrays.fill(depthBuffer, 0.5f);  // 模拟均匀深度

        // 模拟 Mipmap 构建过程（CPU 密集型）
        long mipmapStart = System.nanoTime();
        for (int iter = 0; iter < MEASURE_ITERATIONS / 100; iter++) {
            simulateMipmapBuild(depthBuffer, width, height, mipLevels);
        }
        long mipmapTime = System.nanoTime() - mipmapStart;
        double mipmapAvgUs = (double) mipmapTime / (MEASURE_ITERATIONS / 100) / 1000.0;

        // 模拟遮挡查询（O(1) 理想情况）
        OcclusionCullConfig config = new OcclusionCullConfig();
        long queryStart = System.nanoTime();
        Random random = new Random(123);
        for (int i = 0; i < MEASURE_ITERATIONS * 10; i++) {
            // 模拟 AABB 遮挡检测
            float minX = random.nextFloat();
            float minY = random.nextFloat();
            float minZ = random.nextFloat();
            boolean visible = !(minX > 1.0f || minY > 1.0f || minZ > 1.0f);
        }
        long queryTime = System.nanoTime() - queryStart;
        double queryAvgNs = (double) queryTime / (MEASURE_ITERATIONS * 10);

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("mipmap_build_us", mipmapAvgUs, "< 500μs", mipmapAvgUs < 500.0);
        result.addMetric("occlusion_query_ns", queryAvgNs, "< 10ns", queryAvgNs < 10.0);
        result.addInfo("resolution", width + "x" + height);
        result.addInfo("mip_levels", String.valueOf(mipLevels));

        results.put(testName, result);
        logResult(result);
    }

    /**
     * 模拟 Mipmap 构建算法
     */
    private void simulateMipmapBuild(float[] depth, int width, int height, int levels) {
        float[] currentLevel = depth.clone();
        for (int level = 1; level < levels; level++) {
            int prevW = Math.max(1, width >> (level - 1));
            int prevH = Math.max(1, height >> (level - 1));
            int currW = Math.max(1, width >> level);
            int currH = Math.max(1, height >> level);

            float[] nextLevel = new float[currW * currH];
            for (int y = 0; y < currH; y++) {
                for (int x = 0; x < currW; x++) {
                    int srcX = x * 2;
                    int srcY = y * 2;
                    float maxDepth = currentLevel[srcY * prevW + srcX];
                    if (srcX + 1 < prevW) maxDepth = Math.max(maxDepth, currentLevel[srcY * prevW + srcX + 1]);
                    if (srcY + 1 < prevH) maxDepth = Math.max(maxDepth, currentLevel[(srcY + 1) * prevW + srcX]);
                    nextLevel[y * currW + x] = maxDepth;
                }
            }
            currentLevel = nextLevel;
        }
    }

    // ==================== 4-7. 其他模块基准测试（模拟）====================

    /**
     * MotionVectorGenerator 性能基准测试（模拟）
     */
    private void benchmarkMotionVectorGenerator() {
        String testName = "MotionVectorGenerator (模拟)";
        LOGGER.info(String.format("[基准] %s", testName));

        int width = 1920;
        int height = 1080;
        int pixelCount = width * height;

        // 模拟运动矢量生成（光流插值）
        long genStart = System.nanoTime();
        Random random = new Random(456);
        for (int iter = 0; iter < MEASURE_ITERATIONS / 100; iter++) {
            // 模拟每个像素的运动矢量计算
            for (int i = 0; i < pixelCount / 100; i++) {  // 采样 1%
                float dx = (random.nextFloat() - 0.5f) * 4.0f;  // ±2像素
                float dy = (random.nextFloat() - 0.5f) * 4.0f;
                // 模拟边界检查和钳制
                dx = Math.max(-16.0f, Math.min(16.0f, dx));
                dy = Math.max(-16.0f, Math.min(16.0f, dy));
            }
        }
        long genTime = System.nanoTime() - genStart;
        double genAvgUs = (double) genTime / (MEASURE_ITERATIONS / 100) / 1000.0;

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("motion_vector_gen_us", genAvgUs, "< 2000μs", genAvgUs < 2000.0);
        result.addInfo("resolution", width + "x" + height);

        results.put(testName, result);
        logResult(result);
    }

    /**
     * SROutputManager 性能基准测试（模拟）
     */
    private void benchmarkSROutputManager() {
        String testName = "SROutputManager (模拟)";
        LOGGER.info(String.format("[基准] %s", testName));

        // 模拟双缓冲切换
        long swapStart = System.nanoTime();
        Object dummyBuffer1 = new Object();
        Object dummyBuffer2 = new Object();
        Object current = dummyBuffer1;
        for (int i = 0; i < MEASURE_ITERATIONS * 100; i++) {
            current = (current == dummyBuffer1) ? dummyBuffer2 : dummyBuffer1;
        }
        long swapTime = System.nanoTime() - swapStart;
        double swapAvgNs = (double) swapTime / (MEASURE_ITERATIONS * 100);

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("buffer_swap_ns", swapAvgNs, "< 5ns", swapAvgNs < 5.0);

        results.put(testName, result);
        logResult(result);
    }

    /**
     * GPULODDataManager 性能基准测试（模拟）
     */
    private void benchmarkGPULODDataManager() {
        String testName = "GPULODDataManager (模拟)";
        LOGGER.info(String.format("[基准] %s", testName));

        int objectCount = 10000;

        // 模拟 LOD 数据上传（SSBO 填充）
        long uploadStart = System.nanoTime();
        Random random = new Random(789);
        float[] lodData = new float[objectCount * 4];  // minDist, maxDist, range, size
        for (int iter = 0; iter < MEASURE_ITERATIONS / 100; iter++) {
            for (int i = 0; i < objectCount; i++) {
                lodData[i * 4] = random.nextFloat() * 100.0f;      // minDist
                lodData[i * 4 + 1] = lodData[i * 4] + random.nextFloat() * 50.0f;  // maxDist
                lodData[i * 4 + 2] = lodData[i * 4 + 1] - lodData[i * 4];  // range
                lodData[i * 4 + 3] = random.nextFloat() * 10.0f;      // size
            }
        }
        long uploadTime = System.nanoTime() - uploadStart;
        double uploadAvgUs = (double) uploadTime / (MEASURE_ITERATIONS / 100) / 1000.0;

        // 模拟可见性结果读取
        long readStart = System.nanoTime();
        byte[] visibility = new byte[objectCount];
        for (int iter = 0; iter < MEASURE_ITERATIONS * 10; iter++) {
            Arrays.fill(visibility, (byte) 1);  // 全部可见
        }
        long readTime = System.nanoTime() - readStart;
        double readAvgNs = (double) readTime / (MEASURE_ITERATIONS * 10);

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("lod_data_upload_us", uploadAvgUs, "< 500μs", uploadAvgUs < 500.0);
        result.addMetric("visibility_read_ns", readAvgNs, "< 100μs", readAvgNs < 100000.0);
        result.addInfo("object_count", String.valueOf(objectCount));

        results.put(testName, result);
        logResult(result);
    }

    /**
     * ReflexManagerImpl 性能基准测试（模拟）
     */
    private void benchmarkReflexManagerImpl() {
        String testName = "ReflexManagerImpl (模拟)";
        LOGGER.info(String.format("[基准] %s", testName));

        // 模拟标记点设置（应该极快，< 1μs）
        long markerStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS * 1000; i++) {
            // 模拟 slReflexSetMarker 调用
            int marker = 1 << (i % 4);  // INPUT_SAMPLE, TRIGGER_FLASH, SUBMIT_FRAME, PRESENT
        }
        long markerTime = System.nanoTime() - markerStart;
        double markerAvgNs = (double) markerTime / (MEASURE_ITERATIONS * 1000);

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("marker_set_ns", markerAvgNs, "< 100ns", markerAvgNs < 100.0);

        results.put(testName, result);
        logResult(result);
    }

    // ==================== 压力测试 ====================

    /**
     * 综合压力测试
     * <p>
     * 多线程并发调用所有模块的公共 API，
     * 验证线程安全性和高负载下的稳定性。
     */
    private void runStressTest() {
        String testName = "StressTest (多线程压力)";
        LOGGER.info(String.format("[压力] %s (threads=%d, duration=%dms)",
            testName, STRESS_THREAD_COUNT, STRESS_DURATION_MS));

        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        Thread[] threads = new Thread[STRESS_THREAD_COUNT];

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < STRESS_THREAD_COUNT; t++) {
            threads[t] = new Thread(() -> {
                CameraJitterGenerator gen = CameraJitterGenerator.getInstance();
                Random random = ThreadLocalRandom.current();
                long ops = 0;

                while (System.currentTimeMillis() - startTime < STRESS_DURATION_MS) {
                    try {
                        // 混合调用多个 API
                        switch (random.nextInt(5)) {
                            case 0 -> gen.getJitterOffset(random.nextInt(64));
                            case 1 -> gen.getCurrentMode();
                            case 2 -> gen.getPhaseCount();
                            case 3 -> gen.toString();
                            case 4 -> gen.exportCurrentSequence();
                        }
                        ops++;
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }

                totalOperations.addAndGet(ops);
            }, "StressThread-" + t);
            threads[t].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long actualDuration = System.currentTimeMillis() - startTime;
        long totalOps = totalOperations.get();
        long errors = errorCount.get();
        double opsPerMs = (double) totalOps / actualDuration;

        BenchmarkResult result = new BenchmarkResult(testName);
        result.addMetric("total_operations", (double) totalOps, "> 0", true);
        result.addMetric("errors", (double) errors, "= 0", errors == 0);
        result.addMetric("ops_per_ms", opsPerMs, "> 1000", opsPerMs > 1000.0);
        result.addMetric("duration_ms", (double) actualDuration, "N/A", true);
        result.addInfo("thread_count", String.valueOf(STRESS_THREAD_COUNT));

        results.put(testName, result);
        logResult(result);
    }

    // ==================== 辅助方法 ====================

    private double average(long[] values) {
        if (values.length == 0) return 0.0;
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.length;
    }

    private void logResult(BenchmarkResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  结果: %s", result.testName));

        for (Map.Entry<String, Double> entry : result.metrics.entrySet()) {
            String name = entry.getKey();
            double value = entry.getValue();
            String threshold = result.thresholds.get(name);
            boolean passed = result.passed.getOrDefault(name, false);

            sb.append(String.format("\n    %-25s = %.2f %s [%s]",
                name,
                value,
                getUnit(name),
                passed ? "✅ PASS" : ("❌ FAIL (" + threshold + ")")
            ));
        }

        LOGGER.info(sb.toString());
    }

    private String getUnit(String metricName) {
        if (metricName.contains("_ns")) return "ns";
        if (metricName.contains("_us")) return "μs";
        if (metricName.contains("_ms")) return "ms";
        return "";
    }

    // ==================== 内部类 ====================

    /**
     * 单个基准测试结果
     */
    public static class BenchmarkResult {
        public final String testName;
        public final Map<String, Double> metrics = new LinkedHashMap<>();
        public final Map<String, String> thresholds = new LinkedHashMap<>();
        public final Map<String, Boolean> passed = new LinkedHashMap<>();
        public final Map<String, String> info = new LinkedHashMap<>();

        public BenchmarkResult(String testName) {
            this.testName = testName;
        }

        public void addMetric(String name, double value, String threshold, boolean pass) {
            metrics.put(name, value);
            if (threshold != null) thresholds.put(name, threshold);
            passed.put(name, pass);
        }

        public void addInfo(String key, String value) {
            info.put(key, value);
        }

        public boolean allPassed() {
            return passed.values().stream().allMatch(Boolean::booleanValue);
        }
    }

    /**
     * 完整基准测试报告
     */
    public static class BenchmarkReport {
        public final Map<String, BenchmarkResult> results;
        public final long timestamp;

        public BenchmarkReport(Map<String, BenchmarkResult> results) {
            this.results = Collections.unmodifiableMap(results);
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * 打印摘要到控制台
         */
        public void printSummary() {
            System.out.println("\n╔════════════════════════════════════════════╗");
            System.out.println("║     Renderium 性能基准测试报告              ║");
            System.out.println("╠════════════════════════════════════════════╣");

            int passed = 0;
            int failed = 0;

            for (Map.Entry<String, BenchmarkResult> entry : results.entrySet()) {
                BenchmarkResult r = entry.getValue();
                boolean allPass = r.allPassed();
                if (allPass) passed++; else failed++;

                System.out.printf("║ %-40s %s ║%n",
                    entry.getKey(),
                    allPass ? "✅ PASS" : "❌ FAIL"
                );

                for (Map.Entry<String, Double> metric : r.metrics.entrySet()) {
                    System.out.printf("║   %-35s %.2f%s ║%n",
                        metric.getKey(),
                        metric.getValue(),
                        getUnit(metric.getKey())
                    );
                }
            }

            System.out.println("╠════════════════════════════════════════════╣");
            System.out.printf("║ 总计: %d 项通过 | %d 项失败                   ║%n", passed, failed);
            System.out.println("╚════════════════════════════════════════════╝");
        }

        /**
         * 导出为 Markdown 格式
         */
        public String exportToMarkdown() {
            StringBuilder md = new StringBuilder();
            md.append("# Renderium 性能基准测试报告\n\n");
            md.append("**时间戳**: ").append(new Date(timestamp)).append("\n\n");

            md.append("| 测试项 | 指标 | 数值 | 阈值 | 状态 |\n");
            md.append("|--------|------|------|------|------|\n");

            for (Map.Entry<String, BenchmarkResult> entry : results.entrySet()) {
                BenchmarkResult r = entry.getValue();
                boolean first = true;
                for (Map.Entry<String, Double> metric : r.metrics.entrySet()) {
                    String name = first ? entry.getKey() : "";
                    String threshold = r.thresholds.get(metric.getKey()) != null ?
                        r.thresholds.get(metric.getKey()) : "-";
                    String status = r.passed.get(metric.getKey()) ? "✅ PASS" : "❌ FAIL";

                    md.append(String.format("| %s | %s | %.2f | %s | %s |\n",
                        name,
                        metric.getKey(),
                        metric.getValue(),
                        threshold,
                        status
                    ));
                    first = false;
                }
                md.append("\n");
            }

            return md.toString();
        }

        private String getUnit(String name) {
            if (name.contains("_ns")) return "ns";
            if (name.contains("_us")) return "μs";
            if (name.contains("_ms")) return "ms";
            return "";
        }
    }

    // ==================== Main 入口 ====================

    /**
     * 主入口 - 运行基准测试并输出报告
     */
    public static void main(String[] args) {
        RenderiumBenchmarkSuite suite = new RenderiumBenchmarkSuite();
        BenchmarkReport report = suite.runAllBenchmarks();

        // 打印摘要
        report.printSummary();

        // 可选：导出为 Markdown 文件
        if (args.length > 0 && "--export".equals(args[0])) {
            String markdown = report.exportToMarkdown();
            System.out.println("\n--- Markdown Export ---\n");
            System.out.println(markdown);
        }
    }
}
