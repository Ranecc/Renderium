package com.ranecc.renderium.platform.bridge;

public interface VideoSettingsProvider {

    Object openSettings(Object parent);

    boolean isAvailable();

    String getName();
}
