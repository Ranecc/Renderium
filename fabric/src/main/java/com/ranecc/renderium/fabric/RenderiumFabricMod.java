package com.ranecc.renderium.fabric;

import com.ranecc.renderium.application.controller.RenderiumController;
import com.ranecc.renderium.platform.bridge.video.VideoSettingsBridge;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric Mod Entry Point - Thin Adapter (DDD Adapter Layer)
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Receive Fabric lifecycle events from Loader</li>
 *   <li>Delegate all logic to Application Layer ({@link RenderiumController})</li>
 *   <li>Catch exceptions to prevent crashing other mods</li>
 * </ul>
 *
 * <h4>Architecture Position</h4>
 * <pre>
 * Fabric Loader → onInitialize() → RenderiumController.initialize()
 *                                  → [Domain + Infrastructure Layers]
 * </pre>
 *
 * <h4>Design Principles</h4>
 * <ul>
 *   <li><b>Zero Business Logic</b>: No rendering, no initialization logic here</li>
 *   <li><b>Thin Glue Code</b>: Total lines &lt; 50</li>
 *   <li><b>Platform Agnostic</b>: Controller handles platform differences</li>
 * </ul>
 *
 * @see RenderiumController
 * @since 1.1.0 (Clean Architecture Refactor)
 */
public class RenderiumFabricMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium");

    private static RenderiumController controller;

    @Override
    public void onInitialize() {
        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║  Renderium v1.1.0 - Clean Arch      ║");
        LOGGER.info("║  Platform: Fabric                   ║");
        LOGGER.info("╚══════════════════════════════════════╝");

        try {
            // 1) 静态初始化（创建 Core + Controller 并执行完整初始化流程）
            RenderiumController.initialize();
            // 2) 获取已初始化的实例
            controller = RenderiumController.getInstance();

            // 3) 初始化视频设置桥接（注册选项、检测环境）
            VideoSettingsBridge.initialize();

            logSuccessState();

        } catch (Exception e) {
            LOGGER.error("[Renderium] ✗ Initialization failed", e);
        }
    }

    /**
     * Shutdown hook - Resource cleanup
     */
    public void onShutdown() {
        if (controller != null) {
            try {
                controller.shutdown();
                LOGGER.info("[Renderium] Shutdown complete");
            } catch (Exception e) {
                LOGGER.error("[Renderium] Shutdown error", e);
            }
        }
    }

    /**
     * Get Controller instance for external access
     *
     * @return Initialized RenderiumController, or null if initialization failed
     */
    public static RenderiumController getController() {
        return controller;
    }

    private void logSuccessState() {
        LOGGER.info("[Renderium] ✓ Ready - DDD Architecture Loaded");
        if (controller != null) {
            try {
                LOGGER.info("[Renderium]   State: {}", controller.getState());
                LOGGER.info("[Renderium]   Mode: {}", controller.getCurrentMode());
            } catch (Exception e) {
                LOGGER.warn("[Renderium]   State query failed (degraded mode): {}", e.getMessage());
            }
        }
    }
}
