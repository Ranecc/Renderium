package com.ranecc.renderium.feature.shader.pipeline;

import java.util.EnumSet;
import java.util.Set;

public class RenderExtension {

    private String name;
    private boolean enabled;

    public enum Capability {
        ADVANCED_CULLING,
        DEFERRED_SHADING,
        COMPUTE_BASED_LIGHTING
    }

    private final EnumSet<Capability> supportedCapabilities = EnumSet.noneOf(Capability.class);

    public RenderExtension(String name) {
        this.name = name;
        this.enabled = true;
    }

    public boolean isSupported(Capability capability) {
        return supportedCapabilities.contains(capability);
    }

    public void setCapability(Capability capability, boolean supported) {
        if (supported) {
            supportedCapabilities.add(capability);
        } else {
            supportedCapabilities.remove(capability);
        }
    }

    public Set<Capability> getSupportedCapabilities() {
        return EnumSet.copyOf(supportedCapabilities);
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

    public void initialize() {
        supportedCapabilities.clear();
    }

    public boolean onFrameBegin(int frameNumber, float deltaTime) { return true; }
    public void onFrameEnd() {}
}
