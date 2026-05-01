package com.ranecc.renderium.platform.bridge;

import java.util.logging.Logger;

public final class VideoSettingsBridge {

    private static final Logger LOGGER = Logger.getLogger(VideoSettingsBridge.class.getName());

    private static volatile VideoSettingsProvider activeProvider;
    private static volatile int screenOpenCount = 0;

    private VideoSettingsBridge() {}

    public static void initialize() {
        if (activeProvider != null) {
            throw new IllegalStateException(
                    "VideoSettingsBridge already initialized. Current provider: "
                            + activeProvider.getName());
        }

        if (SodiumCompatibleProvider.INSTANCE.isAvailable()) {
            activeProvider = SodiumCompatibleProvider.INSTANCE;
            LOGGER.info(() -> String.format("Video settings provider: %s (Sodium detected)",
                    activeProvider.getName()));
        } else {
            activeProvider = StandaloneProvider.INSTANCE;
            LOGGER.info(() -> String.format("Video settings provider: %s (standalone mode)",
                    activeProvider.getName()));
        }
    }

    public static Object openSettings(Object parent) {
        VideoSettingsProvider provider = activeProvider;
        if (provider == null) {
            LOGGER.warning(() -> "openSettings() called before initialization");
            return null;
        }
        return provider.openSettings(parent);
    }

    public static VideoSettingsProvider getActiveProvider() {
        return activeProvider;
    }

    public static boolean isCompatibleMode() {
        VideoSettingsProvider provider = activeProvider;
        return provider != null && !(provider instanceof StandaloneProvider);
    }

    public static boolean isInitialized() {
        return activeProvider != null;
    }

    public static void reset() {
        activeProvider = null;
        screenOpenCount = 0;
    }

    public static void onVanillaScreenOpen(Object vanillaScreen) {
        screenOpenCount++;
        LOGGER.fine(() -> String.format("Vanilla VideoSettingsScreen opened (count=%d)", screenOpenCount));
    }

    public static void appendSodiumPage(Object sodiumScreen) {
        if (!isInitialized()) {
            throw new IllegalStateException(
                    "VideoSettingsBridge not initialized. Call initialize() first.");
        }
        if (activeProvider instanceof SodiumCompatibleProvider provider) {
            provider.appendToSodiumScreen(sodiumScreen);
        } else {
            LOGGER.warning(() -> String.format(
                    "appendSodiumPage() called but activeProvider is not SodiumCompatibleProvider. "
                    + "Current: %s", activeProvider != null ? activeProvider.getName() : "null"));
        }
    }
}
