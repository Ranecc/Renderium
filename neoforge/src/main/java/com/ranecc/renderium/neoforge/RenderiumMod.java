package com.ranecc.renderium.neoforge;

import com.ranecc.renderium.application.controller.RenderiumController;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NeoForge Mod Entry Point - Thin Adapter (DDD Adapter Layer)
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Receive NeoForge lifecycle events from Loader</li>
 *   <li>Delegate all logic to Application Layer ({@link RenderiumController})</li>
 *   <li>Register minimal event listeners (platform-specific only)</li>
 * </ul>
 *
 * <h4>Architecture Position</h4>
 * <pre>
 * NeoForge Loader → Constructor → RenderiumController.initialize()
 *                                  → [Domain + Infrastructure Layers]
 *
 * Event Bus → onClientSetup() → Controller.handleClientSetup()
 * </pre>
 *
 * <h4>Design Principles</h4>
 * <ul>
 *   <li><b>Zero Business Logic</b>: No DLSS/Culling/Mode init here</li>
 *   <li><b>Thin Glue Code</b>: Total lines &lt; 80</li>
 *   <li><b>Platform Agnostic Core</b>: Controller handles platform differences</li>
 *   <li><b>Minimal Event Registration</b>: Only platform-specific lifecycle</li>
 * </ul>
 *
 * @see RenderiumController
 * @since 1.1.0 (Clean Architecture Refactor)
 */
@Mod(value = "renderium", dist = Dist.CLIENT)
public class RenderiumMod {

    private static final Logger LOGGER = LogManager.getLogger("Renderium");

    private static RenderiumController controller;

    public RenderiumMod(IEventBus modEventBus) {
        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║  Renderium v1.1.0 - Clean Arch      ║");
        LOGGER.info("║  Platform: NeoForge                  ║");
        LOGGER.info("╚══════════════════════════════════════╝");

        try {
            controller = RenderiumController.getInstance();
            controller.initialize();

            registerPlatformEvents(modEventBus);

            logSuccessState();

        } catch (Exception e) {
            LOGGER.error("[Renderium] ✗ Initialization failed", e);
        }
    }

    /**
     * Register platform-specific event listeners
     * <p>
     * Only NeoForge-specific lifecycle events are registered here.
     * All business logic is delegated to Controller.
     *
     * @param modEventBus NeoForge mod event bus
     */
    private void registerPlatformEvents(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        LOGGER.debug("[Renderium] Platform events registered");
    }

    /**
     * Client setup event handler
     * <p>
     * Delegates to Application Layer for client-specific initialization.
     *
     * @param event FML client setup event
     */
    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        if (controller != null) {
            try {
                controller.handleClientSetup();
                LOGGER.info("[Renderium] Client setup complete");
            } catch (Exception e) {
                LOGGER.error("[Renderium] Client setup error", e);
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
            LOGGER.info("[Renderium]   State: {}", controller.getState());
            LOGGER.info("[Renderium]   Mode: {}", controller.getCurrentMode());
        }
    }
}
