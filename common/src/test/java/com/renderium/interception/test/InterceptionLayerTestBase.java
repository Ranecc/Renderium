// Renderium - Blaze3D 拦截层系统 Phase 6
// 双模式验收测试套件 - 测试基类
// 提供共享的 setup/teardown 逻辑和通用工具方法

package com.renderium.interception.test;

import com.renderium.interception.base.InterceptionCallback;
import com.renderium.interception.base.InterceptionPermission;
import com.renderium.interception.base.InterceptionResult;
import com.renderium.interception.context.CullingContext;
import com.renderium.interception.context.FrameCaptureContext;
import com.renderium.interception.context.FrameGenContext;
import com.renderium.interception.context.FrameGenContext.FrameGenMultiplier;
import com.renderium.interception.context.LODContext;
import com.renderium.interception.context.OutputContext;
import com.renderium.interception.context.RenderContext;
import com.renderium.interception.context.SuperResolutionContext;
import com.renderium.interception.context.SuperResolutionContext.QualityPreset;
import com.renderium.core.RenderiumMode;
import com.renderium.backend.FrameData;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;

/**
 * 拦截层测试基类
 * <p>
 * 提供所有拦截层测试的共享基础设施：
 * <ul>
 *   <li><b>日志捕获</b>：自动捕获测试期间的日志输出，用于验证</li>
 *   <li><b>性能计时</b>：统一的性能测量工具</li>
 *   <li><b>Mock 对象工厂</b>：创建各种 Mock 数据对象</li>
 *   <li><b>资源管理</b>：临时目录和资源的生命周期管理</li>
 *   <li><b>断言工具</b>：常用的自定义断言方法</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * class MyTest extends InterceptionLayerTestBase {
 *     &#64;Test
 *     void testSomething() {
 *         RenderContext ctx = createTestRenderContext();
 *         // ... 测试逻辑
 *     }
 * }
 * </pre>
 *
 * @author Renderium Team
 * @since 5.6.0 (Phase 6)
 */
"拦截层测试基类"
@DisplayName("拦截层测试基类")
public abstract class InterceptionLayerTestBase {

    // ==================== 常量定义 ====================

    /** 默认测试分辨率宽度 */
    protected static final int TEST_WIDTH = 1920;

    /** 默认测试分辨率高度 */
    protected static final int TEST_HEIGHT = 1080;

    /** 默认测试帧率（FPS） */
    protected static final double TARGET_FPS = 60.0;

    /** 性能预算：初始化时间上限（毫秒） */
    protected static final long INIT_TIME_BUDGET_MS = 100L;

    /** 性能预算：重定向延迟上限（毫秒） */
    protected static final long REDIRECT_LATENCY_BUDGET_MS = 2L;

    /** 质量阈值：SSIM 最低可接受值 */
    protected static final double SSIM_THRESHOLD = 0.95;

    /** 稳定性阈值：FPS 方差上限（百分比） */
    protected static final double FPS_VARIANCE_THRESHOLD = 0.10;

    /** 稳定性阈值：帧时间尖峰上限（毫秒） */
    protected static final double FRAME_TIME_SPIKE_THRESHOLD_MS = 20.0;

    /** 内存泄漏检测阈值（MB） */
    protected static final long MEMORY_LEAK_THRESHOLD_MB = 50L;

    // ==================== 测试资源 ====================

    /** JUnit 5 临时目录，每个测试方法独立 */
    @TempDir
    protected Path tempDir;

    /** 日志处理器 - 用于捕获测试期间的日志输出 */
    protected TestLogHandler logHandler;

    /** 测试期间记录的所有性能数据点 */
    protected final List<PerformanceDataPoint> performanceDataPoints = new CopyOnWriteArrayList<>();

    /** 测试开始时间戳 */
    protected Instant testStartTime;

    // ==================== 生命周期方法 ====================

    /**
     * 每个测试方法执行前的初始化
     * <p>
     * 设置日志捕获、初始化性能计数器、记录起始时间戳。
     */
    @BeforeEach
    void baseSetUp() {
        // 初始化日志捕获器
        logHandler = new TestLogHandler();
"com.renderium"
        Logger rootLogger = Logger.getLogger("com.renderium");
        rootLogger.addHandler(logHandler);
        rootLogger.setLevel(Level.FINEST);

        // 清空性能数据点
        performanceDataPoints.clear();

        // 记录测试开始时间
        testStartTime = Instant.now();

"--- 测试初始化完成 [%s] ---"
        LOGGER.info(String.format("--- 测试初始化完成 [%s] ---",
                this.getClass().getSimpleName()));
    }

    /**
     * 每个测试方法执行后的清理
     * <p>
     * 移除日志处理器、输出性能摘要、清理资源。
     */
    @AfterEach
    void baseTearDown() {
        // 移除日志处理器
"com.renderium"
        Logger rootLogger = Logger.getLogger("com.renderium");
        rootLogger.removeHandler(logHandler);

        // 输出测试摘要
        Duration testDuration = Duration.between(testStartTime, Instant.now());
"--- 测试清理完成 [耗时: %dms] ---"
        LOGGER.info(String.format("--- 测试清理完成 [耗时: %dms] ---",
                testDuration.toMillis()));
    }

    // ==================== Mock 对象工厂方法 ====================

    /**
     * 创建标准的测试用 RenderContext（兼容模式）
     *
     * @param frameIndex 帧序号
     * @return 配置好的 RenderContext 实例
     */
    protected RenderContext createCompatibilityRenderContext(int frameIndex) {
        return new RenderContext.Builder()
                .cameraPosition(100.0f, 64.0f, -200.0f)
                .cameraRotation(0.0f, 45.0f)
                .fov(70.0f)
                .fboHandle(0xDEADBEEFL)  // Mock FBO 句柄
                .colorTexture(0xCAFEBABEL)  // Mock 颜色纹理句柄
                .depthTexture(0x12345678L)   // Mock 深度纹理句柄
                .resolution(TEST_WIDTH, TEST_HEIGHT)
                .frameIndex(frameIndex)
                .deltaTime(1.0f / (float) TARGET_FPS)
"COMPATIBILITY"
                .modeName("COMPATIBILITY")
"sodium"
                .loadedMods(new String[]{"sodium"})
                .build();
    }

    /**
     * 创建标准的测试用 RenderContext（狂暴模式）
     *
     * @param frameIndex 帧序号
     * @return 配置好的 RenderContext 实例
     */
    protected RenderContext createAggressiveRenderContext(int frameIndex) {
        return new RenderContext.Builder()
                .cameraPosition(100.0f, 64.0f, -200.0f)
                .cameraRotation(0.0f, 45.0f)
                .fov(70.0f)
                .swapChainImage(0xFEEDFACEL)  // Mock Swapchain Image handle
                .colorTexture(0xCAFEBABEL)
                .depthTexture(0x87654321L)
                .resolution(TEST_WIDTH, TEST_HEIGHT)
                .frameIndex(frameIndex)
                .deltaTime(1.0f / (float) TARGET_FPS)
"AGGRESSIVE"
                .modeName("AGGRESSIVE")
                .build();
    }

    /**
     * 创建标准的测试用 FrameData
     *
     * @param frameIndex 帧序号
     * @return 配置好的 FrameData 实例
     */
    protected FrameData createTestFrameData(int frameIndex) {
        return new FrameData.Builder()
                .colorTexture(0xCAFEBABEL)
                .depthTexture(0x12345678L)
                .width(TEST_WIDTH)
                .height(TEST_HEIGHT)
                .frameIndex(frameIndex)
                .deltaTime(1.0f / (float) TARGET_FPS)
                .build();
    }

    /**
     * 创建标准的测试用 LODContext
     *
     * @return 配置好的 LODContext 实例
     */
    protected LODContext createTestLODContext() {
        return new LODContext.Builder()
                .maxDistance(128)
                .transitionRange(24, 32)
                .billboardEnabled(true)
                .atmosphericPerspectiveEnabled(true)
                .build();
    }

    /**
     * 创建标准的测试用 CullingContext
     *
     * @return 配置好的 CullingContext 实例
     */
    protected CullingContext createTestCullingContext() {
        return new CullingContext.Builder()
                .frustumCullingEnabled(true)
                .occlusionCullingEnabled(true)
                .backfaceCullingEnabled(true)
                .neighborFaceCullingEnabled(true)
                .maxDrawDistance(32)
                .hizMipmapLevels(6)
                .build();
    }

    /**
     * 创建标准的测试用 FrameCaptureContext（兼容模式）
     *
     * @param frameIndex 帧序号
     * @return 配置好的 FrameCaptureContext 实例
     */
    protected FrameCaptureContext createCompatibilityFrameCaptureContext(int frameIndex) {
        return new FrameCaptureContext.Builder()
                .colorTexture(0xCAFEBABEL)
                .depthTexture(0x12345678L)
                .resolution(TEST_WIDTH, TEST_HEIGHT)
                .compatibilityMode(true)
                .frameIndex(frameIndex)
                .build();
    }

    /**
     * 创建标准的测试用 SuperResolutionContext
     *
"QUALITY"
"BALANCED"
"PERFORMANCE"
     * @param qualityPreset 质量预设（如 "QUALITY", "BALANCED", "PERFORMANCE"）
     * @return 配置好的 SuperResolutionContext 实例
     */
    protected SuperResolutionContext createTestSuperResolutionContext(QualityPreset qualityPreset) {
        int inputWidth = TEST_WIDTH / 2;
        int inputHeight = TEST_HEIGHT / 2;
        return new SuperResolutionContext.Builder()
                .inputResolution(inputWidth, inputHeight)
                .outputResolution(TEST_WIDTH, TEST_HEIGHT)
                .qualityPreset(qualityPreset)
                .motionVectorTexture(1L)
                .build();
    }

    /**
     * 创建标准的测试用 FrameGenContext
     *
     * @param multiplier 帧生成倍率（如 2 表示插帧）
     * @return 配置好的 FrameGenContext 实例
     */
    protected FrameGenContext createTestFrameGenContext(FrameGenMultiplier multiplier) {
        return new FrameGenContext.Builder()
                .resolution(TEST_WIDTH, TEST_HEIGHT)
                .multiplier(multiplier)
                .build();
    }

    /**
     * 创建标准的测试用 OutputContext（兼容模式）
     *
     * @return 配置好的 OutputContext 实例
     */
    protected OutputContext createCompatibilityOutputContext() {
        return new OutputContext.Builder()
                .outputTexture(0xCAFEBABEL)
                .displayRegion(0, 0, TEST_WIDTH, TEST_HEIGHT)
                .compatibilityMode(true)
                .build();
    }

    /**
     * 创建纯色测试图像（用于 SSIM 对比等场景）
     *
     * @param width     图像宽度
     * @param height    图像高度
     * @param rgbColor  RGB 颜色值（0xRRGGBB 格式）
     * @return BufferedImage 实例
     */
    protected BufferedImage createSolidTestImage(int width, int height, int rgbColor) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgbColor | 0xFF000000);  // 设置完全不透明
            }
        }
        return image;
    }

    /**
     * 创建渐变测试图像（用于验证图像处理效果）
     *
     * @param width  图像宽度
     * @param height 图像高度
     * @return 渐变 BufferedImage 实例
     */
    protected BufferedImage createGradientTestImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 创建水平渐变 + 垂直渐变
                int r = (int) ((x / (double) width) * 255);
                int g = (int) ((y / (double) height) * 255);
                int b = 128;
                image.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    // ==================== 性能测量工具 ====================

    /**
     * 记录一个性能数据点
     *
     * @param name      数据点名称
     * @param valueNs   耗时（纳秒）
     * @param frameIndex 关联的帧序号
     */
    protected void recordPerformancePoint(String name, long valueNs, int frameIndex) {
        performanceDataPoints.add(new PerformanceDataPoint(name, valueNs, frameIndex));
    }

    /**
     * 计算一组帧时间的 FPS 方差系数（CV）
     * <p>
     * CV = 标准差 / 均值，用于衡量帧率稳定性
     *
     * @param frameTimesMs 帧时间列表（毫秒）
     * @return 方差系数（0-1 范围，越小越稳定）
     */
    protected double calculateFPSVarianceCoefficient(List<Double> frameTimesMs) {
        if (frameTimesMs == null || frameTimesMs.isEmpty()) {
            return 0.0;
        }

        // 计算均值
        double sum = 0.0;
        for (Double ft : frameTimesMs) {
            sum += ft;
        }
        double mean = sum / frameTimesMs.size();

        // 计算标准差
        double varianceSum = 0.0;
        for (Double ft : frameTimesMs) {
            double diff = ft - mean;
            varianceSum += diff * diff;
        }
        double stdDev = Math.sqrt(varianceSum / frameTimesMs.size());

        // 返回 CV
        return mean > 0 ? stdDev / mean : 0.0;
    }

    /**
     * 检查是否有帧时间尖峰
     *
     * @param frameTimesMs       帧时间列表
     * @param spikeThresholdMs   尖峰阈值（毫秒）
     * @return 尖峰数量
     */
    protected int countFrameTimeSpikes(List<Double> frameTimesMs, double spikeThresholdMs) {
        if (frameTimesMs == null) {
            return 0;
        }

        int spikes = 0;
        for (Double ft : frameTimesMs) {
            if (ft > spikeThresholdMs) {
                spikes++;
            }
        }
        return spikes;
    }

    // ==================== 日志验证工具 ====================

    /**
     * 检查是否捕获到包含指定文本的日志
     *
     * @param text 要搜索的文本
     * @return true 如果找到匹配的日志
     */
    protected boolean hasLogContaining(String text) {
        return logHandler.contains(text);
    }

    /**
     * 获取包含指定文本的日志条目数量
     *
     * @param text 要搜索的文本
     * @return 匹配数量
     */
    protected int getLogCountContaining(String text) {
        return logHandler.countContaining(text);
    }

    /**
     * 获取指定级别的日志条目列表
     *
     * @param level 日志级别
     * @return 匹配的日志消息列表
     */
    protected List<String> getLogsAtLevel(Level level) {
        return logHandler.getLogsAtLevel(level);
    }

    // ==================== 内部类 ====================

    /**
     * 性能数据点记录
     *
     * @param name       数据点名称
     * @param valueNs    耗时（纳秒）
     * @param frameIndex 关联帧序号
     */
    protected record PerformanceDataPoint(
            String name,
            long valueNs,
            int frameIndex
    ) {
        /**
         * 获取耗时（毫秒）
         *
         * @return 耗时毫秒数
         */
        public double getValueMs() {
            return valueNs / 1_000_000.0;
        }
    }

    /**
     * 测试日志处理器
     * <p>
     * 用于捕获测试期间的日志输出，
     * 支持按级别和内容过滤查询。
     */
    protected static class TestLogHandler extends Handler {

        /** 存储的所有日志记录 */
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        /** 构造函数 - 使用简单格式化器 */
        public TestLogHandler() {
            super.setFormatter(new SimpleFormatter());
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // 无操作 - 内存中的日志不需要刷新
        }

        @Override
        public void close() {
            records.clear();
        }

        /**
         * 检查是否存在包含指定文本的日志
         *
         * @param text 搜索文本
         * @return true 如果存在匹配
         */
        public boolean contains(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            for (LogRecord record : records) {
                if (record.getMessage() != null && record.getMessage().contains(text)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 统计包含指定文本的日志条目数
         *
         * @param text 搜索文本
         * @return 匹配数量
         */
        public int countContaining(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (LogRecord record : records) {
                if (record.getMessage() != null && record.getMessage().contains(text)) {
                    count++;
                }
            }
            return count;
        }

        /**
         * 获取指定级别的日志消息列表
         *
         * @param level 目标级别
         * @return 消息列表
         */
        public List<String> getLogsAtLevel(Level level) {
            List<String> messages = new java.util.ArrayList<>();
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= level.intValue()) {
"[%s] %s"
                    messages.add(String.format("[%s] %s",
                            record.getLevel().getName(),
                            record.getMessage()));
                }
            }
            return messages;
        }

        /**
         * 获取所有存储的日志记录
         *
         * @return 日志记录列表（不可修改副本）
         */
        public List<LogRecord> getAllRecords() {
            return List.copyOf(records);
        }

        /**
         * 获取日志记录总数
         *
         * @return 总数
         */
        public int size() {
            return records.size();
        }
    }

    // ==================== 日志记录器 ====================
    private static final Logger LOGGER = Logger.getLogger(
            InterceptionLayerTestBase.class.getName());

    // ==================== TestInfrastructure 内部类 ====================

    /**
     * 测试基础设施工具集合
     * <p>
     * 提供测试报告生成、性能基准分析、SSIM 对比和内存泄漏检测等功能。
     * 作为 InterceptionLayerTestBase 的嵌套工具类供子类使用。
     */
    protected static class TestInfrastructure {

        /**
         * 单个测试结果记录
         *
         * @param testName 测试名称
         * @param passed   是否通过
         * @param duration 执行时长
         * @param message  结果描述消息
         * @param metrics  关键指标映射
         */
        public record TestResult(
                String testName,
                boolean passed,
                Duration duration,
                String message,
                Map<String, Object> metrics
        ) {}

        /**
         * 测试套件摘要信息
         *
         * @param suiteName      套件名称
         * @param totalTests     总测试数
         * @param passedCount    通过数
         * @param failedCount    失败数
         * @param skippedCount   跳过数
         * @param totalDuration  总耗时
         * @param results        所有测试结果列表
         */
        public record TestSuiteSummary(
                String suiteName,
                int totalTests,
                int passedCount,
                int failedCount,
                int skippedCount,
                Duration totalDuration,
                List<TestResult> results
        ) {}

        /**
         * 测试报告生成器
         * <p>
         * 负责收集测试结果并生成 Markdown 格式的摘要报告。
         */
        public static class TestReportGenerator {

            /**
             * 生成 Markdown 格式的测试套件摘要报告
             *
             * @param summary 测试套件摘要数据
             * @return 格式化的 Markdown 报告字符串
             */
            public static String generateMarkdownSummary(TestSuiteSummary summary) {
                StringBuilder sb = new StringBuilder();
"# %s 测试报告%n"
                sb.append(String.format("# %s 测试报告%n", summary.suiteName()));
"- **总测试数**: %d%n"
                sb.append(String.format("- **总测试数**: %d%n", summary.totalTests()));
"- **通过**: %d | **失败**: %d | **跳过**: %d%n"
                sb.append(String.format("- **通过**: %d | **失败**: %d | **跳过**: %d%n",
                        summary.passedCount(), summary.failedCount(), summary.skippedCount()));
"- **总耗时**: %dms%n"
                sb.append(String.format("- **总耗时**: %dms%n", summary.totalDuration().toMillis()));
"%n## 详细结果%n%n"
                sb.append("%n## 详细结果%n%n");
                for (TestResult result : summary.results()) {
"PASS"
"FAIL"
                    String status = result.passed() ? "PASS" : "FAIL";
"### [%s] %s%n"
                    sb.append(String.format("### [%s] %s%n", status, result.testName()));
"- 状态: %s | 耗时: %dms%n"
                    sb.append(String.format("- 状态: %s | 耗时: %dms%n",
                            status, result.duration().toMillis()));
"- 说明: %s%n"
                    sb.append(String.format("- 说明: %s%n", result.message()));
                    if (!result.metrics().isEmpty()) {
"- 指标:%n"
                        sb.append("- 指标:%n");
                        for (Map.Entry<String, Object> entry : result.metrics().entrySet()) {
"  - `%s`: `%s`%n"
                            sb.append(String.format("  - `%s`: `%s`%n",
                                    entry.getKey(), entry.getValue()));
                        }
                    }
"%n"
                    sb.append("%n");
                }
                return sb.toString();
            }
        }

        /**
         * 性能基准分析工具
         * <p>
         * 分析帧时间数据，计算 FPS 统计信息和方差系数。
         */
        public static class PerformanceBenchmark {

            /**
             * 性能分析结果
             *
             * @param avgFPS      平均 FPS
             * @param minFPS      最小 FPS
             * @param maxFPS      最大 FPS
             * @param fpsVariance FPS 方差系数 (CV)
             * @param avgFrameTimeMs 平均帧时间（毫秒）
             */
            public record BenchmarkResult(
                    double avgFPS,
                    double minFPS,
                    double maxFPS,
                    double fpsVariance,
                    double avgFrameTimeMs
            ) {}

            /**
             * 分析帧时间数据并计算性能指标
             *
             * @param frameTimesMs    帧时间列表（毫秒）
             * @param warmupFrames    预热帧数（从开头跳过）
             * @return 包含各项性能指标的 BenchmarkResult
             */
            public static BenchmarkResult analyzeResults(List<Double> frameTimesMs, long warmupFrames) {
                if (frameTimesMs == null || frameTimesMs.isEmpty()) {
                    return new BenchmarkResult(0, 0, 0, 0, 0);
                }

                // 跳过预热帧
                List<Double> measured = frameTimesMs;
                if (warmupFrames > 0 && frameTimesMs.size() > warmupFrames) {
                    measured = frameTimesMs.subList((int) warmupFrames, frameTimesMs.size());
                }

                // 计算平均帧时间
                double sum = 0;
                double minFT = Double.MAX_VALUE;
                double maxFT = Double.MIN_VALUE;
                for (Double ft : measured) {
                    sum += ft;
                    if (ft < minFT) minFT = ft;
                    if (ft > maxFT) maxFT = ft;
                }
                double avgFrameTimeMs = sum / measured.size();

                // 计算 FPS
                double avgFPS = avgFrameTimeMs > 0 ? 1000.0 / avgFrameTimeMs : 0;
                double minFPS = minFT > 0 ? 1000.0 / minFT : 0;
                double maxFPS = maxFT > 0 ? 1000.0 / maxFT : 0;

                // 计算方差系数 (CV)
                double varianceSum = 0;
                for (Double ft : measured) {
                    double diff = ft - avgFrameTimeMs;
                    varianceSum += diff * diff;
                }
                double stdDev = Math.sqrt(varianceSum / measured.size());
                double fpsVariance = avgFrameTimeMs > 0 ? stdDev / avgFrameTimeMs : 0;

                return new BenchmarkResult(avgFPS, minFPS, maxFPS, fpsVariance, avgFrameTimeMs);
            }
        }

        /**
         * SSIM (结构相似性) 图像对比工具
         * <p>
             * 用于计算两张图像之间的结构相似性指数，
             * 值范围 [-1, 1]，1.0 表示完全相同。
             */
        public static class SSIMComparator {

            /** SSIM 常量 K1 (避免除零) */
            private static final double C1 = 6.5025;   // (0.01 * 255)^2

            /** SSIM 常量 K2 (避免除零) */
            private static final double C2 = 58.5225;  // (0.03 * 255)^2

            /**
             * 计算两张图像的 SSIM 值
             *
             * @param img1 第一张图像
             * @param img2 第二张图像
             * @return SSIM 值，范围 [-1, 1]，1.0 表示完全相同
             */
            public static double calculateSSIM(BufferedImage img1, BufferedImage img2) {
                if (img1 == null || img2 == null) return -1.0;
                if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
                    return -1.0;  // 尺寸不匹配
                }

                int width = img1.getWidth();
                int height = img1.getHeight();

                // 使用 8x8 滑动窗口计算局部 SSIM 后取平均
                int windowSize = 8;
                double totalSSIM = 0;
                int windowCount = 0;

                for (int y = 0; y <= height - windowSize; y += windowSize / 2) {
                    for (int x = 0; x <= width - windowSize; x += windowSize / 2) {
                        double ssim = computeWindowSSIM(img1, img2, x, y, windowSize);
                        totalSSIM += ssim;
                        windowCount++;
                    }
                }

                return windowCount > 0 ? totalSSIM / windowCount : -1.0;
            }

            /**
             * 计算单个窗口内的局部 SSIM
             *
             * @param img1       第一张图像
             * @param img2       第二张图像
             * @param startX     窗口起始 X
             * @param startY     窗口起始 Y
             * @param windowSize 窗口大小
             * @return 局部 SSIM 值
             */
            private static double computeWindowSSIM(BufferedImage img1, BufferedImage img2,
                                                     int startX, int startY, int windowSize) {
                double sum1 = 0, sum2 = 0;
                double sum1Sq = 0, sum2Sq = 0, sum12 = 0;
                int n = windowSize * windowSize;

                for (int dy = 0; dy < windowSize; dy++) {
                    for (int dx = 0; dx < windowSize; dx++) {
                        int px = startX + dx;
                        int py = startY + dy;
                        int v1 = img1.getRGB(px, py) & 0xFF;  // 取亮度分量
                        int v2 = img2.getRGB(px, py) & 0xFF;
                        sum1 += v1;
                        sum2 += v2;
                        sum1Sq += (double) v1 * v1;
                        sum2Sq += (double) v2 * v2;
                        sum12 += (double) v1 * v2;
                    }
                }

                double mean1 = sum1 / n;
                double mean2 = sum2 / n;
                double var1 = sum1Sq / n - mean1 * mean1;
                double var2 = sum2Sq / n - mean2 * mean2;
                double covar = sum12 / n - mean1 * mean2;

                double numerator = (2 * mean1 * mean2 + C1) * (2 * covar + C2);
                double denominator = (mean1 * mean1 + mean2 * mean2 + C1) * (var1 + var2 + C2);

                return denominator > 0 ? numerator / denominator : 0;
            }

            /**
             * 根据 SSIM 值获取质量等级描述
             *
             * @param ssimValue SSIM 值
             * @return 质量等级字符串
             */
            public static String getQualityGrade(double ssimValue) {
"优秀"
                if (ssimValue >= 0.99) return "优秀";
"良好"
                if (ssimValue >= 0.95) return "良好";
"可接受"
                if (ssimValue >= 0.90) return "可接受";
"较差"
                if (ssimValue >= 0.75) return "较差";
"不可接受"
                return "不可接受";
            }
        }

        /**
         * 内存泄漏检测工具
         * <p>
         * 通过周期性堆快照检测内存增长趋势，
         * 判断是否存在潜在内存泄漏。
         */
        public static class MemoryLeakDetector {

            /**
             * 内存检测结果报告
             *
             * @param heapBeforeMB  检测前堆内存使用 (MB)
             * @param heapAfterMB   检测后堆内存使用 (MB)
             * @param heapGrowthMB  内存增长量 (MB)
             * @param snapshots     快照列表
             */
            public record MemoryReport(
                    long heapBeforeMB,
                    long heapAfterMB,
                    long heapGrowthMB,
                    List<Long> snapshots
            ) {
                /**
                 * 获取内存增长率百分比
                 *
                 * @return 增长百分比 (0-100+)
                 */
                public double getGrowthPercent() {
                    return heapBeforeMB > 0 ? (double) heapGrowthMB / heapBeforeMB * 100 : 0;
                }

                /**
                 * 是否存在潜在内存泄漏
                 *
                 * @return true 如果内存增长超过阈值
                 */
                public boolean hasPotentialLeak() {
                    return heapGrowthMB > 0;  // 简化判断：任何正增长都标记为潜在泄漏
                }
            }

            /**
             * 在指定时间内执行工作负载并检测内存泄漏
             *
             * @param testDuration 检测持续时间
             * @param workload     要执行的工作负载
             * @return 内存检测报告
             */
            public static MemoryReport detectLeaks(Duration testDuration, Runnable workload) {
                Runtime runtime = Runtime.getRuntime();
                List<Long> snapshots = new ArrayList<>();

                // 初始快照
                System.gc();
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                long before = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                snapshots.add(before);

                // 执行工作负载
                long endTime = System.currentTimeMillis() + testDuration.toMillis();
                while (System.currentTimeMillis() < endTime) {
                    workload.run();
                    // 定期采样
                    long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                    snapshots.add(used);
                }

                // 最终快照
                System.gc();
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                long after = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                snapshots.add(after);

                return new MemoryReport(before, after, Math.max(0, after - before), snapshots);
            }

            /**
             * 快速内存检测模式（短时间运行）
             *
             * @param workload 要执行的工作负载
             * @return 内存检测报告
             */
            public static MemoryReport quickDetect(Runnable workload) {
                return detectLeaks(Duration.ofSeconds(3), workload);
            }
        }
    }
}
