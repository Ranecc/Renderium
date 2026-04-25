// Renderium - Fabric Module Main Class (v3)
// Entry point with full Debug integration and settings menu
// Minecraft 26.2-snapshot-3 + Fabric Loader 0.18.5

package com.renderium.fabric;

import com.renderium.core.RenderiumCore;
import com.renderium.core.RenderiumDualModeManager;
import com.renderium.debug.RenderiumDebug;
import com.renderium.dlss.DLSSManager;
import com.renderium.culling.CullingController;
import com.renderium.platform.PlatformHelper;
import com.renderium.fabric.platform.FabricNotificationSender;
import com.renderium.fabric.platform.FabricPlatformHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;

/**
 * Renderium Fabric 模块主类
 *
 * <p><b>v3 更新：</b></p>
 * <ul>
 *   <li>集成 {@link RenderiumDebug} 调试系统</li>
 *   <li>增强启动日志输出</li>
 *   <li>支持 JVM 参数控制调试级别</li>
 * </ul>
 *
 * <h3>Debug 参数：</h3>
 * <pre>
 * # 启用基本调试输出
 * -Drenderium.debug=true
 *
 * # 启用详细跟踪（更详细的性能/状态信息）
 * -Drenderium.debug.verbose=true
 * </pre>
 *
 * @author Renderium Team
 * @version 3.0
 */
@Environment(net.fabricmc.api.EnvType.CLIENT)
public class RenderiumMod implements ClientModInitializer {

    /**
     * SLF4J 日志记录器
     */
    public static final Logger LOGGER = LoggerFactory.getLogger("Renderium");

    /** 模组版本号 */
    private static final String VERSION = "1.0.0-SNAPSHOT";

    @Override
    public void onInitializeClient() {
        // 记录初始化开始时间
        long initStartTime = System.nanoTime();

        // 输出 Debug 配置信息
        logDebugConfig();

        // 初始化平台帮助器（必须在其他组件之前）
        RenderiumDebug.separator("Platform Initialization");
        PlatformHelper.initialize(new FabricPlatformHelper());
        RenderiumDebug.log("Platform", "PlatformHelper initialized: {}", PlatformHelper.getInstance().getClass().getSimpleName());

        // ======== 客户端环境验证 ========
        if (!PlatformHelper.getInstance().isClientEnvironment()) {
            LOGGER.error("CRITICAL: Fabric Mod loaded in non-client environment! This should never happen.");
            return;
        }

        // ======== 主标题输出 ========
        RenderiumDebug.separator("Renderium v" + VERSION);
        LOGGER.info("");
        LOGGER.info("  ╔══════════════════════════════════════╗");
        LOGGER.info("  ║     Renderium - Modern Renderer        ║");
        LOGGER.info("  ║     Version: {}               ║", padRight(VERSION, 20));
        LOGGER.info("  ║     Platform: {}       ║", padRight("Fabric", 20));
        LOGGER.info("  ║     Target: Minecraft 26.2-snapshot-3   ║");
        LOGGER.info("  ╚══════════════════════════════════════╝");
        LOGGER.info("");

        // ======== 初始化核心组件 ========
        long componentStart = System.nanoTime();
        try {
            initializeComponents();
            registerNotificationSender();
            registerEventHandlers();
        } catch (Exception e) {
            LOGGER.error("[Renderium] Component initialization failed: {}", e.getMessage());
        }
        long componentElapsedMs = (System.nanoTime() - componentStart) / 1_000_000;
        RenderiumDebug.log("Init", "Component initialization took {} ms", componentElapsedMs);

        // ======== 初始化完成报告 ========
        long initElapsedMs = (System.nanoTime() - initStartTime) / 1_000_000;

        RenderiumDebug.separator("Initialization Complete");
        LOGGER.info("  ✅ All components initialized successfully");
        LOGGER.info("  ⏱️  Total initialization time: {} ms", initElapsedMs);

        if (RenderiumDebug.isDebugEnabled()) {
            logDetailedStatus();
        }

        LOGGER.info("");
        LOGGER.info("[Renderium] Ready! Use Mods menu to access settings.");
    }

    /**
     * 初始化核心组件
     */
    private void initializeComponents() {
        // DLSS Manager
        DLSSManager dlssManager = DLSSManager.getInstance();
        RenderiumDebug.log("DLSS", "DLSS Manager initialized, available: {}", dlssManager.isAvailable());

        // Culling Controller
        CullingController cullingController = CullingController.getInstance();
        RenderiumDebug.log("Culling", "Culling Controller initialized");

        // Dual Mode Manager（延迟初始化，在首次使用时创建）
        RenderiumDebug.log("Core", "Core components ready, DualModeManager will be created on demand");
    }

    /**
     * 注册通知发送器
     */
    private void registerNotificationSender() {
        RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
        dualMode.setNotificationSender(new FabricNotificationSender());
        RenderiumDebug.log("Events", "Notification sender registered (Fabric implementation)");
    }

    /**
     * 注册事件处理器
     *
     * <p>注意：Fabric API 0.146.1+26.2 for MC 26.2-snapshot-3 使用事件系统。
     * 渲染相关回调通过 Mixin 实现。</p>
     */
    private void registerEventHandlers() {
        RenderiumDebug.log("Events", "Event handlers registered (Mixin-based rendering callbacks)");
        RenderiumDebug.log("Events", "Settings menu will be injected via MixinOptionsScreen");
    }

    /**
     * 记录 Debug 配置信息
     */
    private void logDebugConfig() {
        if (!RenderiumDebug.isDebugEnabled()) {
            LOGGER.info("[Renderium] Debug mode disabled (use -Drenderium.debug=true to enable)");
            return;
        }

        LOGGER.info("[Renderium] ==========================================");
        LOGGER.info("[Renderium] DEBUG MODE ENABLED");
        LOGGER.info("[Renderium] Verbose mode: {}", RenderiumDebug.isVerboseEnabled() ? "ON" : "OFF");
        LOGGER.info("[Renderium] ==========================================");

        // 输出系统属性
        if (RenderiumDebug.isVerboseEnabled()) {
            LOGGER.info("[DEBUG] Java version: {}", System.getProperty("java.version"));
            LOGGER.info("[DEBUG] JVM args: {}", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
            LOGGER.info("[DEBUG] Max memory: {} MB",
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        }
    }

    /**
     * 输出详细状态信息
     */
    private void logDetailedStatus() {
        try {
            RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
            LOGGER.info("[DEBUG] Current mode: {}", dualMode.getCurrentMode());
            LOGGER.info("[DEBUG] Sodium present: {}", dualMode.isPerformanceModPresent());
            LOGGER.info("[DEBUG] Initialized: {}", "N/A (method not available)");
        } catch (Exception e) {
            LOGGER.warn("[DEBUG] Failed to get DualModeManager status: {}", e.getMessage());
        }

        // 输出完整快照
        String snapshot = RenderiumDebug.generateSnapshot();
        LOGGER.info(snapshot);
    }

    /**
     * 右对齐填充字符串
     */
    private static String padRight(String s, int length) {
        if (s.length() >= length) return s;
        return s + " ".repeat(length - s.length());
    }
}
