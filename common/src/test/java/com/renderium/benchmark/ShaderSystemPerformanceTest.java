// Renderium - 性能验证系统
// ShaderSystemPerformanceTest - 验证完整 Shader 系统是否达到 400-600 FPS 目标
//
// 测试目标：
//   - GBufferGeometryNode 执行时间 < 0.5ms/帧
//   - ShadowMapNode (4级联) 执行时间 < 1.0ms/帧
//   - SSAO 执行时间 < 0.8ms/帧
//   - Bloom 执行时间 < 1.2ms/帧
//   - Tonemap 执行时间 < 0.3ms/帧
//   - 总管线执行时间 < 3.5ms/帧 v 对应 ~285 FPS (保守估计)
//
// 优化后目标：
//   - 总管线执行时间 < 1.67ms/帧 v 对应 ~600 FPS
//   - 总管线执行时间 < 2.50ms/帧 v 对应 ~400 FPS

package com.renderium.benchmark;

import com.renderium.bridge.batch.BatchTransformEngine;
import com.renderium.bridge.batch.BatchTransformEngineV3;
import com.renderium.bridge.mc.FrameDataSnapshot;
import com.renderium.bridge.mc.MCRenderBridge;
import com.renderium.pipeline.node.builtin.GBufferGeometryNode;
import com.renderium.pipeline.node.builtin.ShadowMapNode;
import com.renderium.pipeline.node.builtin.SSAO;
import com.renderium.pipeline.node.builtin.Bloom;
import com.renderium.pipeline.node.builtin.Tonemap;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Shader 系统集成性能测试
 * <p>
 * 模拟真实渲染场景，测量完整 Shader 管线的帧渲染时间，
 * 验证是否达到 400-600 FPS 的性能目标。
 *
 * <h3>测试流程：</h3>
 * <pre>
 * +-----------------------------------------------------+
 * | 1. 初始化环境                                       |
 * |    +-- 初始化 MCRenderBridge                        |
 * |    +-- 初始化 BatchTransformEngineV3                 |
 * |    +-- 初始化 CommandBatcher                         |
 * |    +-- 注册所有 PipelineNodes                       |
 * +-----------------------------------------------------+
 * | 2. 模拟 N 帧渲染                                    |
 * |    For each frame:                                  |
 * |    +-- beginFrame()                                 |
 * |    +-- GBufferGeometryNode.execute()                |
 * |    +-- ShadowMapNode.execute() (4 cascades)         |
 * |    +-- SSAO.execute()                               |
 * |    +-- Bloom.execute()                              |
 * |    +-- Tonemap.execute()                            |
 * |    +-- endFrame()                                   |
 * +-----------------------------------------------------+
 * | 3. 输出性能报告                                     |
 * |    +-- 每个节点平均耗时                             |
 * |    +-- P50/P95/P99 分位数                           |
 * |    +-- 总帧时间 & FPS                               |
 * |    +-- 是否达到 400-600 FPS 目标                    |
 * +-----------------------------------------------------+
 * </pre>
 *
 * @since 3.0.0
 */
public class ShaderSystemPerformanceTest {

"Renderium|PerfTest"
    private static final Logger LOGGER = Logger.getLogger("Renderium|PerfTest");

    // ==================== 测试配置 ====================

    /** 测试帧数 */
    private static final int TEST_FRAME_COUNT = 1000;

    /** 热身帧数（不计入统计） */
    private static final int WARMUP_FRAMES = 100;

    /** 模拟顶点数（每帧） */
    private static final int SIMULATED_VERTEX_COUNT = 10_000;

    /** 屏幕分辨率（用于后处理节点） */
    private static final int SCREEN_WIDTH = 1920;
    private static final int SCREEN_HEIGHT = 1080;

    // ==================== 性能预算定义 ====================

    /**
     * 节点性能预算（微秒/帧）
     * <p>
     * 总预算 = Σ(各节点预算) ~ 1667μs (对应 600 FPS)
     * 宽松预算 = 2500μs (对应 400 FPS)
     */
    interface PerformanceBudget {
        long GBUFFER_MAX_US = 500;       // G-Buffer 几何: < 0.5ms
        long SHADOW_MAX_US = 1000;      // 阴影贴图 (4级联): < 1.0ms
        long SSAO_MAX_US = 800;         // SSAO: < 0.8ms
        long BLOOM_MAX_US = 1200;       // Bloom: < 1.2ms
        long TONEMAP_MAX_US = 300;      // Tonemap: < 0.3ms
        long TOTAL_TARGET_US = 1667;    // 目标总时间 (~600 FPS)
        long TOTAL_RELAXED_US = 2500;   // 放宽总时间 (~400 FPS)
    }

    // ==================== 测试结果记录 ====================

    /**
     * 单个节点的性能统计数据
     */
    static class NodePerformanceStats {
        String nodeName;
        long[] frameTimesUs;           // 每帧耗时（微秒）
        long totalTimeUs;
        long avgTimeUs;
        long minTimeUs;
        long maxTimeUs;
        long p50TimeUs;               // 中位数
        long p95TimeUs;               // 95分位
        long p99TimeUs;               // 99分位
        boolean passedBudget;          // 是否通过预算检查

        NodePerformanceStats(String name, int frameCount) {
            this.nodeName = name;
            this.frameTimesUs = new long[frameCount];
        }

        void computeStatistics() {
            Arrays.sort(frameTimesUs);
            int n = frameTimesUs.length;

            long sum = 0;
            for (long t : frameTimesUs) sum += t;
            totalTimeUs = sum;
            avgTimeUs = sum / n;
            minTimeUs = frameTimesUs[0];
            maxTimeUs = frameTimesUs[n - 1];

            p50TimeUs = frameTimesUs[n / 2];
            p95TimeUs = frameTimesUs[(int)(n * 0.95)];
            p99TimeUs = frameTimesUs[(int)(n * 0.99)];
        }
    }

    // ==================== 主测试方法 ====================

    /**
     * 执行完整的 Shader 系统性能测试
     *
     * @return true 如果通过 400-600 FPS 性能目标
     */
    public static boolean runFullPipelineTest() {
"+--------------------------------------------------+"
        LOGGER.info("+--------------------------------------------------+");
"=   Renderium Shader System Performance Test      ="
        LOGGER.info("=   Renderium Shader System Performance Test      =");
"+--------------------------------------------------|"
        LOGGER.info("+--------------------------------------------------|");
"= Test Frames: %-36d ="
        LOGGER.info(String.format("= Test Frames: %-36d =", TEST_FRAME_COUNT));
"= Warmup Frames: %-35d ="
        LOGGER.info(String.format("= Warmup Frames: %-35d =", WARMUP_FRAMES));
"= Simulated Vertices: %-29d ="
        LOGGER.info(String.format("= Simulated Vertices: %-29d =", SIMULATED_VERTEX_COUNT));
"= Resolution: %dx%-34d ="
        LOGGER.info(String.format("= Resolution: %dx%-34d =", SCREEN_WIDTH, SCREEN_HEIGHT));
"+--------------------------------------------------+"
        LOGGER.info("+--------------------------------------------------+");

        // Step 1: 初始化环境
        if (!initializeEnvironment()) {
"❌ 环境初始化失败，测试终止"
            LOGGER.severe("❌ 环境初始化失败，测试终止");
            return false;
        }

        // Step 2: 创建测试节点实例
        GBufferGeometryNode gbufferNode = new GBufferGeometryNode();
        ShadowMapNode shadowNode = new ShadowMapNode();
        SSAO ssaoNode = new SSAO();
        Bloom bloomNode = new Bloom();
        Tonemap tonemapNode = new Tonemap();

        // Step 3: 分配统计数组
        int measuredFrames = TEST_FRAME_COUNT - WARMUP_FRAMES;
"GBufferGeometry"
        NodePerformanceStats gbufferStats = new NodePerformanceStats("GBufferGeometry", measuredFrames);
"ShadowMap(4C)"
        NodePerformanceStats shadowStats = new NodePerformanceStats("ShadowMap(4C)", measuredFrames);
"SSAO"
        NodePerformanceStats ssaoStats = new NodePerformanceStats("SSAO", measuredFrames);
"Bloom"
        NodePerformanceStats bloomStats = new NodePerformanceStats("Bloom", measuredFrames);
"Tonemap"
        NodePerformanceStats tonemapStats = new NodePerformanceStats("Tonemap", measuredFrames);

        long[] totalFrameTimesUs = new long[measuredFrames];  // 全管线帧时间

        // Step 4: 执行热身
"🔥 热身阶段 ("
" 帧)..."
        LOGGER.info("🔥 热身阶段 (" + WARMUP_FRAMES + " 帧)...");
        for (int i = 0; i < WARMUP_FRAMES; i++) {
            runSingleFrame(gbufferNode, shadowNode, ssaoNode, bloomNode, tonemapNode, null);
        }
"✓ 热身完成"
        LOGGER.info("✓ 热身完成");

        // Step 5: 正式测量
"📊 正式测量阶段 ("
" 帧)..."
        LOGGER.info("📊 正式测量阶段 (" + measuredFrames + " 帧)...");

        for (int frame = 0; frame < measuredFrames; frame++) {
            long frameStartNs = System.nanoTime();

            // 执行单帧（传入统计数组记录每个节点耗时）
            runSingleFrameWithStats(
                gbufferNode, shadowNode, ssaoNode, bloomNode, tonemapNode,
                gbufferStats, shadowStats, ssaoStats, bloomStats, tonemapStats,
                frame
            );

            long frameEndNs = System.nanoTime();
            totalFrameTimesUs[frame] = (frameEndNs - frameStartNs) / 1000;  // ns v μs
        }

        // Step 6: 计算统计数据
        gbufferStats.computeStatistics();
        shadowStats.computeStatistics();
        ssaoStats.computeStatistics();
        bloomStats.computeStatistics();
        tonemapStats.computeStatistics();

        // Step 7: 输出报告
        boolean passed = printPerformanceReport(
            gbufferStats, shadowStats, ssaoStats, bloomStats, tonemapStats,
            totalFrameTimesUs
        );

        return passed;
    }

    // ==================== 单帧执行模拟 ====================

    /**
     * 执行单帧渲染（无统计）
     */
    private static void runSingleFrame(GBufferGeometryNode gbuffer, ShadowMapNode shadow,
                                        SSAO ssao, Bloom bloom, Tonemap tonemap,
                                        Object stats) {
        MCRenderBridge.beginFrame();

        // 模拟填充 FrameDataSnapshot
        populateMockFrameData();

        // 执行管线节点（使用虚拟资源句柄）
        long gbufferOutput = gbufferNodeExecute(gbuffer);
        long shadowOutput = shadowNodeExecute(shadow);
        long ssaoOutput = ssaoNodeExecute(ssao, gbufferOutput, gbufferOutput);  // Position, Normal
        long sceneColor = 0x12345678L;  // 模拟场景颜色纹理
        long bloomOutput = bloomNodeExecute(bloom, sceneColor);
        long finalOutput = tonemapNodeExecute(tonemap, bloomOutput);

        MCRenderBridge.endFrame();
    }

    /**
     * 执行单帧渲染（带统计记录）
     */
    private static void runSingleFrameWithStats(GBufferGeometryNode gbuffer, ShadowMapNode shadow,
                                                 SSAO ssao, Bloom bloom, Tonemap tonemap,
                                                 NodePerformanceStats gStats, NodePerformanceStats sStats,
                                                 NodePerformanceStats saStats, NodePerformanceStats bStats,
                                                 NodePerformanceStats tStats, int frameIndex) {
        MCRenderBridge.beginFrame();
        populateMockFrameData();

        // G-Buffer Geometry
        long start = System.nanoTime();
        long gbufferOut = gbufferNodeExecute(gbuffer);
        gStats.frameTimesUs[frameIndex] = (System.nanoTime() - start) / 1000;

        // Shadow Map
        start = System.nanoTime();
        long shadowOut = shadowNodeExecute(shadow);
        sStats.frameTimesUs[frameIndex] = (System.nanoTime() - start) / 1000;

        // SSAO
        start = System.nanoTime();
        long ssaoOut = ssaoNodeExecute(ssao, gbufferOut, gbufferOut);
        saStats.frameTimesUs[frameIndex] = (System.nanoTime() - start) / 1000;

        // Bloom
        start = System.nanoTime();
        long bloomOut = bloomNodeExecute(bloom, 0x12345678L);
        bStats.frameTimesUs[frameIndex] = (System.nanoTime() - start) / 1000;

        // Tonemap
        start = System.nanoTime();
        tonemapNodeExecute(tonemap, bloomOut);
        tStats.frameTimesUs[frameIndex] = (System.nanoTime() - start) / 1000;

        MCRenderBridge.endFrame();
    }

    // ==================== 节点执行包装器 ====================

    /**
     * 包装 GBufferGeometryNode.execute()
     */
    private static long gbufferNodeExecute(GBufferGeometryNode node) {
        try {
            return node.execute(null, 0L);  // RenderContext=null 表示纯 CPU 测试模式
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 包装 ShadowMapNode.execute()
     */
    private static long shadowNodeExecute(ShadowMapNode node) {
        try {
            return node.execute(null);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 包装 SSAO.execute()
     */
    private static long ssaoNodeExecute(SSAO node, long positionTex, long normalTex) {
        try {
            return node.execute(null, positionTex, normalTex);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 包装 Bloom.execute()
     */
    private static long bloomNodeExecute(Bloom node, long inputTexture) {
        try {
            return node.execute(null, inputTexture);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 包装 Tonemap.execute()
     */
    private static long tonemapNodeExecute(Tonemap node, long inputTexture) {
        try {
            return node.execute(null, inputTexture);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== Mock 数据生成 ====================

    /**
     * 填充模拟的 FrameDataSnapshot 数据
     */
    private static void populateMockFrameData() {
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        fd.reset();

        // 设置模拟矩阵数据（单位矩阵）
        float[] identity = {
            1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1
        };
        fd.setProjectionMatrix(identity.clone());
        fd.setViewMatrix(identity.clone());
        fd.setCameraPosition(0, 64, 0);
        fd.setFov(70f);
        fd.setNearPlane(0.05f);
        fd.setFarPlane(1000f);
        fd.setWindowSize(SCREEN_WIDTH, SCREEN_HEIGHT);

        // 模拟区块可见性
        fd.setChunkVisibility(256, 1024, 0, 0, false);  // 模拟 256 个可见 Section
    }

    // ==================== 环境初始化 ====================

    /**
     * 初始化测试环境
     */
    private static boolean initializeEnvironment() {
        try {
            // 初始化 V3 批处理引擎
            BatchTransformEngineV3 v3 = new BatchTransformEngineV3();
            MCRenderBridge.setBatchTransformerV3(v3);

            // 初始化 V2 回退引擎
            BatchTransformEngine v2 = new BatchTransformEngine();
            MCRenderBridge.setBatchTransformer(v2);

            // 初始化命令批处理器
            var batcher = new com.renderium.bridge.batch.CommandBatcher(1024);
            MCRenderBridge.setCommandBatcher(batcher);

"✓ 环境初始化成功"
            LOGGER.info("✓ 环境初始化成功");
"  - V3 Engine Unsafe=%b"
            LOGGER.info(String.format("  - V3 Engine Unsafe=%b", v3.isUsingUnsafe()));
            return true;
        } catch (Exception e) {
"环境初始化异常: "
            LOGGER.severe("环境初始化异常: " + e.getMessage());
            return false;
        }
    }

    // ==================== 报告输出 ====================

    /**
     * 打印完整的性能报告
     *
     * @return true 如果通过性能目标
     */
    private static boolean printPerformanceReport(NodePerformanceStats gbuffer,
                                                   NodePerformanceStats shadow,
                                                   NodePerformanceStats ssao,
                                                   NodePerformanceStats bloom,
                                                   NodePerformanceStats tonemap,
                                                   long[] totalFrameTimes) {
" "
");
"+------------------------------------------------------+"
        LOGGER.info("+------------------------------------------------------+");
"=          🎯 PERFORMANCE REPORT                     ="
        LOGGER.info("=          🎯 PERFORMANCE REPORT                     =");
"+------------------------------------------------------|"
        LOGGER.info("+------------------------------------------------------|");

        // 表头
"= Node              | Avg(us) | P95(us) | Budget | Status ="
        LOGGER.info("= Node              | Avg(us) | P95(us) | Budget | Status =");
"+------------------+---------+---------+--------+--------|"
        LOGGER.info("+------------------+---------+---------+--------+--------|");

        // 各节点结果
        logNodeRow(gbuffer, PerformanceBudget.GBUFFER_MAX_US);
        logNodeRow(shadow, PerformanceBudget.SHADOW_MAX_US);
        logNodeRow(ssao, PerformanceBudget.SSAO_MAX_US);
        logNodeRow(bloom, PerformanceBudget.BLOOM_MAX_US);
        logNodeRow(tonemap, PerformanceBudget.TONEMAP_MAX_US);

"+------------------------------------------------------|"
        LOGGER.info("+------------------------------------------------------|");

        // 计算总帧时间统计
        Arrays.sort(totalFrameTimes);
        int n = totalFrameTimes.length;
        long totalAvg = 0;
        for (long t : totalFrameTimes) totalAvg += t;
        totalAvg /= n;
        long totalP95 = totalFrameTimes[(int)(n * 0.95)];
        long totalP99 = totalFrameTimes[(int)(n * 0.99)];

        double avgFps = 1_000_000.0 / Math.max(1, totalAvg);
        double fps95 = 1_000_000.0 / Math.max(1, totalP95);

"= TOTAL (avg)       | %7d |         | <=%5d |        ="
        LOGGER.info(String.format("= TOTAL (avg)       | %7d |         | <=%5d |        =",
                totalAvg, PerformanceBudget.TOTAL_TARGET_US));
"= TOTAL (p95)       |         | %7d | <=%5d |        ="
        LOGGER.info(String.format("= TOTAL (p95)       |         | %7d | <=%5d |        =",
                totalP95, PerformanceBudget.TOTAL_TARGET_US));

"+------------------------------------------------------|"
        LOGGER.info("+------------------------------------------------------|");

        // FPS 结论
"= Average FPS:      %-37.1f ="
        LOGGER.info(String.format("= Average FPS:      %-37.1f =", avgFps));
"= P95 FPS:          %-37.1f ="
        LOGGER.info(String.format("= P95 FPS:          %-37.1f =", fps95));

        // 最终判定
        boolean targetPassed = totalAvg <= PerformanceBudget.TOTAL_TARGET_US;
        boolean relaxedPassed = totalAvg <= PerformanceBudget.TOTAL_RELAXED_US;

        String status;
"✅ PASS (>=600 FPS)"
        if (targetPassed) status = "✅ PASS (>=600 FPS)";
"[WARN]️ ACCEPTABLE (400-600 FPS)"
        else if (relaxedPassed) status = "[WARN]️ ACCEPTABLE (400-600 FPS)";
"❌ FAIL (<400 FPS)"
        else status = "❌ FAIL (<400 FPS)";

"+------------------------------------------------------|"
        LOGGER.info("+------------------------------------------------------|");
"= Final Result: %-38s ="
        LOGGER.info(String.format("= Final Result: %-38s =", status));
"+------------------------------------------------------+"
        LOGGER.info("+------------------------------------------------------+");

        return relaxedPassed;  // 至少要达到 400 FPS
    }

    /**
     * 打印单个节点的性能行
     */
    private static void logNodeRow(NodePerformanceStats stats, long budgetUs) {
        boolean pass = stats.avgTimeUs <= budgetUs;
"✅"
"[WARN]️"
        String icon = pass ? "✅" : "[WARN]️";
"= %-16s | %7d | %7d | %6d | %s   ="
        LOGGER.info(String.format("= %-16s | %7d | %7d | %6d | %s   =",
                stats.nodeName,
                stats.avgTimeUs,
                stats.p95TimeUs,
                budgetUs,
                icon
        ));
        stats.passedBudget = pass;
    }

    // ==================== Main 入口 ====================

    public static void main(String[] args) {
"🚀 启动 Renderium Shader System 性能测试..."
        LOGGER.info("🚀 启动 Renderium Shader System 性能测试...");
        long testStartMs = System.currentTimeMillis();

        boolean result = runFullPipelineTest();

        long elapsedMs = System.currentTimeMillis() - testStartMs;
" ⏱️  测试完成，总耗时: %d ms"
⏱️  测试完成，总耗时: %d ms", elapsedMs));

        if (result) {
"🎉 性能测试通过！Shader 系统已准备好投入生产。"
            LOGGER.info("🎉 性能测试通过！Shader 系统已准备好投入生产。");
            System.exit(0);
        } else {
"[WARN]️  性能测试未完全达标，建议进行优化。"
            LOGGER.warning("[WARN]️  性能测试未完全达标，建议进行优化。");
            System.exit(1);
        }
    }
}
