// Renderium - 调试设置标签页
// 提供警告输出级别控制、FFM 调试面板等开发期工具

package com.ranecc.renderium.presentation.ui.tabs;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.infrastructure.gpu.VulkanFFMDebugger;
import com.ranecc.renderium.presentation.ui.MCAbstract;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 调试设置标签页
 *
 * <p>提供以下配置项：
 * <ul>
 *   <li>警告输出级别（ALL/FINE/FINER/WARNING/SEVERE/OFF）</li>
 *   <li>FFM 调试开关（VulkanFFMDebugger.DEBUG_ENABLED）</li>
 *   <li>FFM 慢调用阈值调节</li>
 *   <li>FFM 调试报告查看</li>
 *   <li>调试计数器重置</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.6.0
 */
public class DebugSettingsTab extends Screen {

    private final Screen parent;
    private final RenderiumConfig config;

    private Button ffmDebugButton;
    private Button logLevelButton;
    private Button slowThresholdButton;
    private Button resetButton;
    private Button refreshReportButton;
    private Button vkInfoButton;
    private Button exportAllButton;
    private Button saveToFileButton;

    private static final String[] LOG_LEVELS = {"ALL", "FINE", "FINER", "WARNING", "SEVERE", "OFF"};
    private int currentLogLevelIndex = 3;

    public DebugSettingsTab(Screen parent, RenderiumConfig config) {
        super(MCAbstract.text("Debug Settings"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;
        int startY = 40;
        int spacing = 25;

        // FFM 调试开关
        ffmDebugButton = MCAbstract.buttonBuilder(centerX - 100, startY, 200, 20)
                .text(VulkanFFMDebugger.DEBUG_ENABLED ? "FFM Debug: ON" : "FFM Debug: OFF")
                .onClick(btn -> toggleFfmDebug())
                .build();
        addRenderableWidget(ffmDebugButton);

        // 日志输出级别
        logLevelButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing, 200, 20)
                .text("Log Level: " + getCurrentLogLevel())
                .onClick(btn -> cycleLogLevel())
                .build();
        addRenderableWidget(logLevelButton);

        // 慢调用阈值
        slowThresholdButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 2, 200, 20)
                .text("Slow Threshold: " + (VulkanFFMDebugger.SLOW_THRESHOLD_NS / 1_000_000) + "ms")
                .onClick(btn -> cycleSlowThreshold())
                .build();
        addRenderableWidget(slowThresholdButton);

        // FFM 统计重置
        resetButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 3, 200, 20)
                .text("Reset FFM Stats")
                .onClick(btn -> resetFfmStats())
                .build();
        addRenderableWidget(resetButton);

        // Vulkan 信息按钮
        vkInfoButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 4, 200, 20)
                .text("Vulkan Info")
                .onClick(btn -> logVulkanInfo())
                .build();
        addRenderableWidget(vkInfoButton);

        // 报告刷新按钮
        refreshReportButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 5, 200, 20)
                .text("Print FFM Report")
                .onClick(btn -> printReport())
                .build();
        addRenderableWidget(refreshReportButton);

        // Export All — 一键导出全部诊断信息到日志（回传给开发者的标准格式）
        exportAllButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 6, 200, 20)
                .text("★ Export All to Log")
                .onClick(btn -> exportFullReport())
                .build();
        addRenderableWidget(exportAllButton);

        // Save to File — 写入游戏目录下的 renderium-debug.txt
        saveToFileButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 7, 200, 20)
                .text("Save to renderium-debug.txt")
                .onClick(btn -> saveReportToFile())
                .build();
        addRenderableWidget(saveToFileButton);
    }

    private void toggleFfmDebug() {
        VulkanFFMDebugger.DEBUG_ENABLED = !VulkanFFMDebugger.DEBUG_ENABLED;
        ffmDebugButton.setMessage(MCAbstract.text(
            VulkanFFMDebugger.DEBUG_ENABLED ? "FFM Debug: ON" : "FFM Debug: OFF"));
    }

    private void cycleLogLevel() {
        currentLogLevelIndex = (currentLogLevelIndex + 1) % LOG_LEVELS.length;
        String levelName = LOG_LEVELS[currentLogLevelIndex];

        java.util.logging.Logger.getLogger("com.ranecc.renderium")
            .setLevel(java.util.logging.Level.parse(levelName));

        logLevelButton.setMessage(MCAbstract.text("Log Level: " + levelName));
    }

    private String getCurrentLogLevel() {
        var level = java.util.logging.Logger.getLogger("com.ranecc.renderium").getLevel();
        return level != null ? level.getName() : "INHERITED";
    }

    private void cycleSlowThreshold() {
        long[] options = {1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 50_000_000L, 100_000_000L};
        long current = VulkanFFMDebugger.SLOW_THRESHOLD_NS;
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) { idx = i; break; }
        }
        long next = options[(idx + 1) % options.length];
        VulkanFFMDebugger.SLOW_THRESHOLD_NS = next;

        slowThresholdButton.setMessage(MCAbstract.text("Slow Threshold: " + (next / 1_000_000) + "ms"));
    }

    private void resetFfmStats() {
        VulkanFFMDebugger.reset();
        printReport();
    }

    private void logVulkanInfo() {
        var logger = java.util.logging.Logger.getLogger("Renderium|Debug");
        logger.info("=== Vulkan FFMDebugger Status ===");
        logger.info("  DEBUG_ENABLED: " + VulkanFFMDebugger.DEBUG_ENABLED);
        logger.info("  SLOW_THRESHOLD_NS: " + VulkanFFMDebugger.SLOW_THRESHOLD_NS);
        logger.info("  Total calls: " + VulkanFFMDebugger.getTotalInvocations());
        logger.info("  Total failures: " + VulkanFFMDebugger.getTotalFailures());
        logger.info("  Total time: " + (VulkanFFMDebugger.getTotalNanos() / 1_000_000) + "ms");
        logger.info("  Avg: " + String.format("%.0f", VulkanFFMDebugger.getAvgNanos()) + "ns");
    }

    private void printReport() {
        var report = VulkanFFMDebugger.getReport();
        var logger = java.util.logging.Logger.getLogger("Renderium|FFMDebug");
        for (String line : report.split("\n")) {
            logger.info(line);
        }
    }

    /**
     * 一键导出完整诊断报告到日志
     *
     * <p>输出格式为剪贴板友好的纯文本块，
     * 可以直接拷贝回传给开发者进行分析。</p>
     */
    private void exportFullReport() {
        var logger = java.util.logging.Logger.getLogger("Renderium|Export");
        var report = buildFullReport();
        // 使用连续的 info 调用确保每行都有 RENDERIUM-EXPORT 前缀便于过滤
        logger.info("╔══════════════════════════════════════════════╗");
        logger.info("║  Renderium Full Diagnostic Export           ║");
        logger.info("║  Copy this entire block to the developer    ║");
        logger.info("╚══════════════════════════════════════════════╝");
        for (String line : report.split("\n")) {
            logger.info(line);
        }
        logger.info("╔══════════════════════════════════════════════╗");
        logger.info("║  End of Renderium Diagnostic Export         ║");
        logger.info("╚══════════════════════════════════════════════╝");
    }

    /**
     * 构建完整的诊断报告字符串。
     *
     * <p>包含：摘要信息、环境信息、FFM 调试器状态、FFM 详细报表、运行时配置。</p>
     */
    private String buildFullReport() {
        var sb = new StringBuilder();
        var br = System.lineSeparator();

        sb.append("=".repeat(72)).append(br);
        sb.append("  Renderium Full Diagnostic Report").append(br);
        sb.append("=".repeat(72)).append(br);
        sb.append(br);

        // 1. 时间戳 & 环境
        sb.append("--- Timestamp & Environment ---").append(br);
        sb.append("  Timestamp: ").append(java.time.LocalDateTime.now()).append(br);
        try {
            var mx = java.lang.management.ManagementFactory.getRuntimeMXBean();
            sb.append("  JVM: ").append(System.getProperty("java.version")).append(" (")
              .append(System.getProperty("java.vendor")).append(")").append(br);
            sb.append("  OS: ").append(System.getProperty("os.name")).append(" ")
              .append(System.getProperty("os.version")).append(br);
        } catch (Exception ignored) {}
        sb.append(br);

        // 2. VulkanFFMDebugger 状态
        sb.append("--- VulkanFFMDebugger Status ---").append(br);
        sb.append("  DEBUG_ENABLED: ").append(VulkanFFMDebugger.DEBUG_ENABLED).append(br);
        sb.append("  SLOW_THRESHOLD_NS: ").append(VulkanFFMDebugger.SLOW_THRESHOLD_NS)
          .append(" (").append(VulkanFFMDebugger.SLOW_THRESHOLD_NS / 1_000_000).append("ms)").append(br);
        sb.append("  Total calls: ").append(VulkanFFMDebugger.getTotalInvocations()).append(br);
        sb.append("  Total failures: ").append(VulkanFFMDebugger.getTotalFailures()).append(br);
        sb.append("  Total time: ").append(VulkanFFMDebugger.getTotalNanos() / 1_000_000).append("ms").append(br);
        sb.append("  Avg: ").append(String.format("%.0f", VulkanFFMDebugger.getAvgNanos())).append("ns").append(br);
        sb.append(br);

        // 3. FFM Detailed Report
        sb.append("--- FFM Detailed Report ---").append(br);
        sb.append(VulkanFFMDebugger.getReport()).append(br);

        // 4. Log Level
        sb.append("--- Log Level ---").append(br);
        sb.append("  Renderium Logger Level: ").append(getCurrentLogLevel()).append(br);
        sb.append("  JUL Level names available: ALL=ALL FINE=300 FINER=400 FINEST=500 WARNING=900 SEVERE=1000 OFF=2147483647").append(br);
        sb.append(br);

        // 5. Config snapshot (key flags)
        sb.append("--- Config Key Flags ---").append(br);
        sb.append("  Mode: ").append(config.getMode() != null ? config.getMode().name() : "null").append(br);
        sb.append("  Enabled: ").append(config.isEnabled()).append(br);
        sb.append("  SuperRes: ").append(config.isSuperResolutionEnabled()).append(br);
        sb.append("  FrameGen: ").append(config.isFrameGenerationEnabled()).append(br);
        sb.append("  Reflex: ").append(config.isReflexEnabled()).append(br);
        sb.append("  Frustum: ").append(config.isFrustumCullingEnabled()).append(br);
        sb.append("  Occlusion: ").append(config.isOcclusionCullingEnabled()).append(br);
        sb.append("  Backface: ").append(config.isBackfaceCullingEnabled()).append(br);
        sb.append(br);

        sb.append("=".repeat(72)).append(br);
        sb.append("  End of Report").append(br);
        sb.append("=".repeat(72)).append(br);

        return sb.toString();
    }

    /**
     * 将完整诊断报告保存到游戏目录下的 renderium-debug.txt。
     *
     * <p>方便在无法直接拷贝日志时通过文件方式导出。</p>
     */
    private void saveReportToFile() {
        var report = buildFullReport();
        var logger = java.util.logging.Logger.getLogger("Renderium|Export");
        try {
            var gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory;
            var file = new java.io.File(gameDir, "renderium-debug.txt");
            java.nio.file.Files.writeString(file.toPath(), report,
                java.nio.charset.StandardCharsets.UTF_8);
            logger.info("Report saved to: " + file.getAbsolutePath());
        } catch (Exception e) {
            logger.warning("Failed to save report: " + e.getMessage());
            // fallback: try current working directory
            try {
                var file = new java.io.File("renderium-debug.txt");
                java.nio.file.Files.writeString(file.toPath(), report,
                    java.nio.charset.StandardCharsets.UTF_8);
                logger.info("Report saved to fallback: " + file.getAbsolutePath());
            } catch (Exception e2) {
                logger.severe("Failed to save report (fallback): " + e2.getMessage());
            }
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            try {
                minecraft.setScreenAndShow(parent);
            } catch (Exception e) {
                super.onClose();
            }
        }
    }
}
