package com.ranecc.renderium.platform.bridge;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class SodiumCompatibleProvider implements VideoSettingsProvider {

    private static final Logger LOGGER = Logger.getLogger(SodiumCompatibleProvider.class.getName());

    public static final SodiumCompatibleProvider INSTANCE = new SodiumCompatibleProvider();

    private static final String SODIUM_VIDEO_SETTINGS_CLASS =
            "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";

    private static volatile Boolean sodiumDetectedCache = null;

    private SodiumCompatibleProvider() {}

    @Override
    public Object openSettings(Object parent) {
        return null;
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = sodiumDetectedCache;
        if (cached != null) {
            return cached;
        }
        boolean detected = detectSodium();
        sodiumDetectedCache = detected;
        return detected;
    }

    @Override
    public String getName() {
        return "Sodium Compatible";
    }

    public void appendToSodiumScreen(Object sodiumScreen) {
        LOGGER.info("Appending Renderium tab to Sodium VideoSettingsScreen");
    }

    private static boolean detectSodium() {
        try {
            Class.forName(SODIUM_VIDEO_SETTINGS_CLASS);
            LOGGER.info("Sodium detected: compatible mode enabled");
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.FINE, "Sodium not found: standalone mode will be used");
            return false;
        }
    }

    static void resetDetectionCache() {
        sodiumDetectedCache = null;
    }
}
