package com.ranecc.renderium.platform.bridge;

public final class StandaloneProvider implements VideoSettingsProvider {

    public static final StandaloneProvider INSTANCE = new StandaloneProvider();

    private StandaloneProvider() {}

    @Override
    public Object openSettings(Object parent) {
        return null;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getName() {
        return "Standalone";
    }
}
