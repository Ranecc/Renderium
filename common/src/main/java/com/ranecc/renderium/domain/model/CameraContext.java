package com.ranecc.renderium.domain.model;

public final class CameraContext {

    public float x, y, z;
    public float yaw, pitch;
    public boolean viewAreaChanged;

    public void reset() {
        x = 0; y = 0; z = 0;
        yaw = 0; pitch = 0;
        viewAreaChanged = false;
    }
}
