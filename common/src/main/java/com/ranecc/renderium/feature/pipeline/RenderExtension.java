package com.ranecc.renderium.feature.pipeline;

public class RenderExtension {

    private String name;
    private boolean enabled;

    public RenderExtension(String name) {
        this.name = name;
        this.enabled = true;
    }

    public enum Capability {
        ADVANCED_CULLING,
        DEFERRED_SHADING,
        COMPUTE_BASED_LIGHTING
    }

    public boolean isSupported(Capability capability) {
        return false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getName() {
        return name;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void initialize() {}

    public boolean onFrameBegin(int frameNumber, float deltaTime) { return true; }
    public void onFrameEnd() {}
}
