// Renderium - Debug HUD 覆盖层 (v2)
// 实时性能监控显示系统（纯文本报告 + 可选屏幕叠加）
// 显示: 帧率、管线耗时、遮挡剔除统计、批量渲染统计、内存状态
//
// v2 变更:
//   - 移除 GuiGraphicsExtractor 渲染依赖（MC 26.2 API 不稳定）
//   - 改为纯文本报告模式（控制台输出 / 文件写入）
//   - 保留 render() 接口用于未来集成
//
// 启用方式:
//   1. 配置文件: renderium.properties 中 debugOverlayEnabled=true
//   2. 运行时: 按 F3 + R (Renderium Debug 切换)
//   3. JVM 参数: -Drenderium.debug=true

package com.renderium.fabric.hud;

import com.renderium.core.RenderiumCore;
import com.renderium.pipeline.AsyncChunkBuildPipeline;
import com.renderium.pipeline.AsyncRenderPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renderium Debug HUD 覆盖层 (v2)
 * <p>
 * 纯文本性能报告系统。由于 MC 26.2-snapshot-3 的 {@code GuiGraphicsExtractor}
 * API 尚不稳定（现有代码中也为空实现），本版本采用纯文本输出模式。
 *
 * <h3>输出方式：</h3>
 * <ul>
 *   <li><b>控制台</b>: 调用 {@link #logReport()} 输出格式化报告</li>
 *   <li><b>程序化访问</b>: 调用 {@link #getReport()} 获取字符串</li>
 *   <li><b>屏幕叠加</b>: 预留 {@link #render(Object)} 接口，待 API 稳定后实现</li>
 * </ul>
 *
 * <h3>显示内容（分区域）：</h3>
 * <pre>
 * ╔═══════════════════════════════════════════╗
 * ║ [Renderium] Debug HUD                    ║
 * ╠──────────────────────────────────────────╣
 * ║ FPS: 950 ✓  Frame: 12345  Δt=1.05ms     ║
 * ╠──────────────────────────────────────────╣
 * ║ Pipeline:                                ║
 * ║   onFrameBegin: 0.012ms                  ║
 * ║   Occlusion: 0.342ms (45% culled)        ║
 * ║   ChunkBuild: 2 active, 5 pending         ║
 * ╠──────────────────────────────────────────╣
 * ║ Core:                                    ║
 * ║   Mode: ACTIVE | SR: DLSS | FG: OFF      ║
 * ╚═══════════════════════════════════════════╝
 * </pre>
 */
public final class RenderiumDebugHUD {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-DebugHUD");

    private static final String PREFIX = "[Renderium] ";

    /** 是否启用显示（控制是否生成报告） */
    private static volatile boolean visible = false;

    /** 上次报告输出时间（纳秒），用于节流 */
    private static volatile long lastReportTimeNs = 0;

    /** 报告输出间隔（毫秒）：避免刷屏 */
    private static final long REPORT_INTERVAL_MS = 5000;

    // ==================== 单例控制 ====================

    private RenderiumDebugHUD() {
        // 私有构造，纯静态工具类
    }

    /**
     * 切换 Debug HUD 显示状态
     *
     * @return 切换后的可见状态
     */
    public static boolean toggle() {
        visible = !visible;
        if (visible) {
            LOGGER.info(PREFIX + "Debug HUD 已启用 (文本报告模式)");
            logReport();  // 立即输出一次
        } else {
            LOGGER.info(PREFIX + "Debug HUD 已关闭");
        }
        return visible;
    }

    /**
     * 设置可见状态
     *
     * @param v 是否可见
     */
    public static void setVisible(boolean v) {
        visible = v;
        if (v) {
            LOGGER.info(PREFIX + "Debug HUD 已启用");
        }
    }

    /**
     * 获取当前是否可见
     */
    public static boolean isVisible() {
        return visible;
    }

    // ==================== 渲染接口（预留） ====================

    /**
     * 渲染 Debug HUD（预留接口，当前为空实现）
     * <p>
     * 待 MC 26.2 的 GuiGraphicsExtractor API 稳定后实现屏幕叠加渲染。
     * 当前版本仅做节流检查：如果启用且超过间隔则输出日志报告。
     *
     * @param graphics 图形上下文（Object 类型以兼容不同 MC 版本）
     */
    public static void render(Object graphics) {
        if (!visible) return;

        long now = System.nanoTime();

        // 节流：每 REPORT_INTERVAL_MS 才输出一次到日志
        if ((now - lastReportTimeNs) > REPORT_INTERVAL_MS * 1_000_000L) {
            logReport();
            lastReportTimeNs = now;
        }
    }

    // ==================== 报告构建 ====================

    /**
     * 构建完整的性能报告文本
     *
     * @return 格式化的多行报告字符串
     */
    public static String getReport() {
        StringBuilder sb = new StringBuilder(1024);

        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════╗\n");
        sb.append("║          Renderium Debug HUD 报告              ║\n");
        sb.append("╠══════════════════════════════════════════════════╣\n");

        try {
            appendFrameInfo(sb);
            sb.append("├──────────────────────────────────────────────────┤\n");
            appendPipelineStatus(sb);
            sb.append("├──────────────────────────────────────────────────┤\n");
            appendCoreStatus(sb);
        } catch (Exception e) {
            sb.append("║  §c[错误] ").append(e.getMessage()).append("\n");
        }

        sb.append("╚══════════════════════════════════════════════════╝\n");

        return sb.toString();
    }

    /**
     * 将性能报告输出到日志
     */
    public static void logReport() {
        LOGGER.info(getReport());
    }

    // ==================== 分区构建方法 ====================

    /**
     * 追加帧率信息区
     */
    private static void appendFrameInfo(StringBuilder sb) {
        sb.append("│  帧率状态:\n");

        RenderiumCore core = null;
        try {
            core = RenderiumCore.getInstance();
        } catch (Exception ignored) {}

        int fps = 0;
        float deltaMs = 0f;
        int frameNum = 0;
        if (core != null) {
            fps = estimateFPS(core.getLastDeltaTime());
            deltaMs = core.getLastDeltaTime() * 1000f;
            frameNum = core.getCurrentFrame();
        }

        String status = fps >= 800 ? "优秀" : fps >= 400 ? "良好" : "需优化";
        sb.append(String.format("│    FPS=%d (%s)  Frame=%d  Δt=%.3fms\n",
                fps, status, frameNum, deltaMs));
    }

    /**
     * 追加异步管线状态区
     */
    private static void appendPipelineStatus(StringBuilder sb) {
        sb.append("│  异步管线:\n");

        AsyncRenderPipeline pipeline = null;
        try {
            pipeline = AsyncRenderPipeline.getInstance();
        } catch (Exception ignored) {}

        if (pipeline != null && pipeline.isRunning()) {
            double ofbUs = pipeline.getAverageOnFrameBeginTimeUs();
            double cullMs = pipeline.getAverageCullTimeMs();
            long visibleSections = pipeline.getTotalVisibleSections();
            long culledSections = pipeline.getTotalCulledSections();

            sb.append(String.format("│    onFrameBegin: %.1f µs\n", ofbUs));

            double cullRate = (visibleSections + culledSections) > 0
                    ? 100.0 * culledSections / (visibleSections + culledSections) : 0;
            sb.append(String.format("│    Occlusion: %.3f ms (剔除 %.1f%%)\n", cullMs, cullRate));
            sb.append(String.format("│    Visible=%d  Culled=%d\n", visibleSections, culledSections));
        } else {
            sb.append("│    状态: 未启动 (同步模式)\n");
        }

        // Chunk 构建管道
        AsyncChunkBuildPipeline chunkPipeline = null;
        try {
            chunkPipeline = AsyncChunkBuildPipeline.getInstance();
        } catch (Exception ignored) {}

        if (chunkPipeline != null) {
            int active = chunkPipeline.getActiveTaskCount();
            int pending = chunkPipeline.getPendingQueueSize();
            long uploadedKB = chunkPipeline.getTotalUploadedBytes() / 1024;
            sb.append(String.format("│    ChunkBuild: %d 活跃, %d 待处理, %.1f KB\n",
                    active, pending, uploadedKB));
        }
    }

    /**
     * 追加核心状态区
     */
    private static void appendCoreStatus(StringBuilder sb) {
        sb.append("│  核心状态:\n");

        RenderiumCore core = null;
        try {
            core = RenderiumCore.getInstance();
        } catch (Exception ignored) {}

        if (core != null) {
            String modeStr = "UNKNOWN";
            boolean srEnabled = false;
            boolean fgEnabled = false;
            String srTech = "NONE";

            try {
                modeStr = core.isActive() ? "ACTIVE" : (core.isShortCircuited()
                        ? "SHORTED: " + core.getShortCircuitReason() : "INACTIVE");
                srEnabled = core.isSuperResolutionEnabled();
                fgEnabled = core.isFrameGenerationEnabled();

                var srMgr = core.getSuperResolutionManager();
                if (srMgr != null && srMgr.isAvailable()) {
                    srTech = "DLSS";
                }
            } catch (Exception ignored) {}

            sb.append(String.format("│    Mode=%s  SR=%s(%s)  FG=%s\n",
                    modeStr,
                    srEnabled ? "ON" : "OFF", srTech,
                    fgEnabled ? "ON" : "OFF"));

            var reflex = core.getReflexManager();
            if (reflex != null && reflex.isEnabled()) {
                sb.append("│    Reflex: ENABLED\n");
            }
        } else {
            sb.append("│    Core: 未初始化\n");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 估算当前帧率（基于 deltaTime）
     *
     * @param deltaTimeSec 帧间隔（秒）
     * @return 估算的 FPS
     */
    private static int estimateFPS(float deltaTimeSec) {
        if (deltaTimeSec <= 0.001f) return 9999;
        return (int) Math.round(1.0 / Math.max(deltaTimeSec, 0.001f));
    }

    /**
     * 获取键绑定提示
     */
    public static String getKeyBindHint() {
        return "F3 + R (切换 Debug HUD)";
    }
}
