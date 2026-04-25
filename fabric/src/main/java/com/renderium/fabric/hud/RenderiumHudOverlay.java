// Renderium - HUD 状态指示器 (v3)
// 集成 Debug HUD 渲染 + 性能状态查询
//
// v3 变更:
//   - 集成 RenderiumDebugHUD 渲染调用
//   - 提供性能日志汇总方法
//   - 保持零 I/O 设计原则（仅通过 Logger 输出）

package com.renderium.fabric.hud;

import com.renderium.core.RenderiumCore;
import com.renderium.pipeline.AsyncChunkBuildPipeline;
import com.renderium.pipeline.AsyncRenderPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renderium HUD 覆盖层管理器 (v3)
 *
 * <p><b>设计原则：</b></p>
 * <ul>
 *   <li><b>零 I/O</b>: 不使用 System.out/err</li>
 *   <li><b>零日志热路径</b>: 运行时不输出日志</li>
 *   <li><b>纯查询 + 委托渲染</b>: 查询委托给 DebugHUD</li>
 * </ul>
 *
 * <h3>功能：</h3>
 * <ol>
 *   <li>查询 Renderium 核心状态</li>
 *   <li>委托 Debug HUD 渲染到 {@link RenderiumDebugHUD}</li>
 *   <li>提供性能日志汇总（按需调用）</li>
 * </ol>
 *
 * @since 1.0.0
 * @version 3.0 (集成 Debug HUD)
 */
public class RenderiumHudOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-HUD");

    // ==================== 渲染入口 ====================

    /**
     * 主渲染入口（每帧调用）
     * <p>
     * 如果 Debug HUD 可见，则委托给 {@link RenderiumDebugHUD#render(GuiGraphics)}。
     * 否则无操作（零开销）。
     *
     * @param graphics Minecraft 图形上下文
     */
    public static void render(Object graphics) {
        if (!RenderiumDebugHUD.isVisible()) {
            return;  // 快速路径：HUD 不可见时立即返回
        }

        try {
            RenderiumDebugHUD.render(graphics);
        } catch (Exception e) {
            // HUD 渲染失败不应影响游戏
        }
    }

    /**
     * 兼容旧接口的渲染方法
     *
     * @param tickCount tick 数（忽略，保留兼容性）
     */
    public static void render(long tickCount) {
        // 旧接口：不做任何操作（避免不必要的开销）
        // 新的 render(GuiGraphics) 由 MixinGameRenderer 或事件系统调用
    }

    // ==================== 状态查询 ====================

    /**
     * 检查是否已完全激活
     *
     * @return 是否激活
     */
    public static boolean isFullyActive() {
        try {
            RenderiumCore core = RenderiumCore.getInstance();
            return core.isActive() && core.isInitialized();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前状态文本（用于调试，不自动输出）
     *
     * @return 状态描述字符串
     */
    public static String getStatusText() {
        try {
            RenderiumCore core = RenderiumCore.getInstance();
            return String.format("Renderium v1.0 | Active=%s | Init=%s",
                    core.isActive() ? "YES" : "NO",
                    core.isInitialized() ? "YES" : "NO"
            );
        } catch (Exception e) {
            return "Renderium [ERROR]: " + e.getMessage();
        }
    }

    /**
     * 切换 Debug HUD 显示状态
     *
     * @return 切换后的可见状态
     */
    public static boolean toggleDebugHUD() {
        boolean newState = RenderiumDebugHUD.toggle();

        if (newState) {
            LOGGER.info("[Renderium] Debug HUD 已启用");
        } else {
            LOGGER.info("[Renderium] Debug HUD 已关闭");
        }

        return newState;
    }

    // ==================== 性能日志汇总 ====================

    /**
     * 获取完整性能报告（用于控制台输出或文件记录）
     *
     * @return 格式化的多行性能报告
     */
    public static String getPerformanceReport() {
        StringBuilder sb = new StringBuilder(1024);

        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════╗\n");
        sb.append("║          Renderium 性能报告                      ║\n");
        sb.append("╠══════════════════════════════════════════════════╣\n");

        // ====== 核心状态 ======
        appendSection(sb, "核心状态");
        try {
            RenderiumCore core = RenderiumCore.getInstance();
            appendLine(sb, "激活状态", core.isActive() ? "✓ ACTIVE" : "✗ INACTIVE");
            appendLine(sb, "初始化", core.isInitialized() ? "✓" : "✗");
            appendLine(sb, "当前帧", String.valueOf(core.getCurrentFrame()));
            appendLine(sb, "帧间隔", String.format("%.4f ms", core.getLastDeltaTime() * 1000f));
            appendLine(sb, "超分辨率", core.isSuperResolutionEnabled() ? "ON" : "OFF");
            appendLine(sb, "帧生成", core.isFrameGenerationEnabled() ? "ON" : "OFF");

            if (core.isShortCircuited()) {
                appendLine(sb, "短路原因", core.getShortCircuitReason());
            }
        } catch (Exception e) {
            appendLine(sb, "错误", e.getMessage());
        }

        sb.append("├──────────────────────────────────────────────────┤\n");

        // ====== 异步管线状态 ======
        appendSection(sb, "异步管线");
        try {
            AsyncRenderPipeline pipeline = AsyncRenderPipeline.getInstance();
            appendLine(sb, "运行状态", pipeline.isRunning() ? "运行中" : "已停止");
            appendLine(sb, "总处理帧数", String.valueOf(pipeline.getTotalFramesProcessed()));
            appendLine(sb, "onFrameBegin 平均耗时",
                    String.format("%.2f µs", pipeline.getAverageOnFrameBeginTimeUs()));
            appendLine(sb, "遮挡剔除平均耗时",
                    String.format("%.3f ms", pipeline.getAverageCullTimeMs()));
            appendLine(sb, "可见区块总数", String.valueOf(pipeline.getTotalVisibleSections()));
            appendLine(sb, "剔除区块总数", String.valueOf(pipeline.getTotalCulledSections()));
        } catch (Exception e) {
            appendLine(sb, "错误", "异步管线未初始化");
        }

        sb.append("├──────────────────────────────────────────────────┤\n");

        // ====== Chunk 构建管道 ======
        appendSection(sb, "Chunk 构建");
        try {
            AsyncChunkBuildPipeline chunkPipeline = AsyncChunkBuildPipeline.getInstance();
            appendLine(sb, "已提交任务", String.valueOf(chunkPipeline.getTotalSubmittedTasks()));
            appendLine(sb, "已完成任务", String.valueOf(chunkPipeline.getTotalCompletedTasks()));
            appendLine(sb, "已取消任务", String.valueOf(chunkPipeline.getTotalCancelledTasks()));
            appendLine(sb, "延迟任务数", String.valueOf(chunkPipeline.getTotalDeferredTasks()));
            appendLine(sb, "当前活跃", String.valueOf(chunkPipeline.getActiveTaskCount()));
            appendLine(sb, "待处理队列", String.valueOf(chunkPipeline.getPendingQueueSize()));
            appendLine(sb, "已上传数据",
                    String.format("%.1f KB", chunkPipeline.getTotalUploadedBytes() / 1024.0));
        } catch (Exception e) {
            appendLine(sb, "错误", "构建管道未初始化");
        }

        sb.append("╚══════════════════════════════════════════════════╝\n");

        return sb.toString();
    }

    /**
     * 将性能报告输出到日志
     */
    public static void logPerformanceReport() {
        LOGGER.info(getPerformanceReport());
    }

    // ==================== 私有辅助方法 ====================

    private static void appendSection(StringBuilder sb, String title) {
        sb.append(String.format("│  %s:\n", title));
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        sb.append(String.format("│    %-20s %s\n", label + ":", value));
    }
}
