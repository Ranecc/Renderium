package com.ranecc.renderium.feature.pipeline.strategy;

public class BfsOcclusionEngine {
    public static final class CullResult {
        public final boolean visible;
        public CullResult(boolean visible) { this.visible = visible; }
    }

    private static final BfsOcclusionEngine INSTANCE = new BfsOcclusionEngine();
    public static BfsOcclusionEngine getInstance() { return INSTANCE; }
    public CullResult cull(long sceneView, long frustumData) { return new CullResult(true); }
    public boolean isInitialized() { return false; }
}
