package com.ranecc.renderium.domain.enums;
import com.ranecc.renderium.domain.enums.RenderiumMode;

public enum RenderiumMode {

    COMPATIBILITY("compatibility", true, false),
    COMPATIBILITY_LIMITED("compatibility_limited", false, false),
    AGGRESSIVE("aggressive", false, true);

    private final String id;
    private final boolean fullCompatible;
    private final boolean aggressive;

    RenderiumMode(String id, boolean fullCompatible, boolean aggressive) {
        this.id = id;
        this.fullCompatible = fullCompatible;
        this.aggressive = aggressive;
    }

    public boolean isCompatible() {
        return this == COMPATIBILITY || this == COMPATIBILITY_LIMITED;
    }

    public boolean isFullCompatible() { return fullCompatible; }
    public boolean isCompatibilityLimited() { return this == COMPATIBILITY_LIMITED; }
    public boolean isAggressive() { return aggressive; }

    public boolean supportsPostProcessing() { return this != COMPATIBILITY_LIMITED; }
    public boolean supportsFrameGraphOptimization() { return this == AGGRESSIVE; }
    public boolean supportsVulkanCommandOptimization() { return this == AGGRESSIVE; }
    public boolean supportsMemoryOptimization() { return this == AGGRESSIVE; }

    public String getDisplayName() {
        return switch (this) {
            case COMPATIBILITY -> "兼容模式";
            case COMPATIBILITY_LIMITED -> "受限兼容模式";
            case AGGRESSIVE -> "狂暴模式";
        };
    }

    public String getId() { return id; }
}
