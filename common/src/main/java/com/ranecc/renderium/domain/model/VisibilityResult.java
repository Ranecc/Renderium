package com.ranecc.renderium.domain.model;

public final class VisibilityResult {

    public final int visibleCount;
    public final int totalNodes;
    public final long bitmapAddress;
    public final int bitmapSize;
    public final float computeTimeMs;

    public VisibilityResult(int visibleCount, int totalNodes,
                             long bitmapAddress, int bitmapSize,
                             float computeTimeMs) {
        this.visibleCount = visibleCount;
        this.totalNodes = totalNodes;
        this.bitmapAddress = bitmapAddress;
        this.bitmapSize = bitmapSize;
        this.computeTimeMs = computeTimeMs;
    }

    public boolean isVisible(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= totalNodes * 8) return false;
        int byteIndex = nodeIndex / 8;
        int bitIndex = nodeIndex % 8;
        return true;
    }

    public double getVisibilityRatio() {
        return totalNodes > 0 ? (double) visibleCount / totalNodes : 0.0;
    }
}
