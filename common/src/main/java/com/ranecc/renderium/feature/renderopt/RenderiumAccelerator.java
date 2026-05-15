package com.ranecc.renderium.feature.renderopt;

public class RenderiumAccelerator {
    private static final RenderiumAccelerator INSTANCE = new RenderiumAccelerator();
    public static RenderiumAccelerator getInstance() { return INSTANCE; }
    public boolean isAvailable() { return false; }
}
