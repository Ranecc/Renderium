package com.ranecc.renderium.infrastructure.nativeLib;

public class RenderiumAccelerator {

    private static volatile RenderiumAccelerator instance;
    private boolean initialized = false;
    private com.ranecc.renderium.feature.pipeline.strategy.BfsOcclusion bfsModule;

    private RenderiumAccelerator() {}

    public static RenderiumAccelerator getInstance() {
        if (instance == null) {
            synchronized (RenderiumAccelerator.class) {
                if (instance == null) {
                    instance = new RenderiumAccelerator();
                }
            }
        }
        return instance;
    }

    public boolean initialize(Object deviceHandle) {
        this.initialized = true;
        return true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getVersion() { return "0.1.0"; }

    public boolean isNativeLibraryAvailable() { return false; }

    public void close() { this.initialized = false; }

    public com.ranecc.renderium.feature.pipeline.strategy.BfsOcclusion bfs() {
        if (bfsModule == null) {
            bfsModule = new com.ranecc.renderium.feature.pipeline.strategy.BfsOcclusion() {
                private long nativeCtx;

                @Override
                public long createContext(int maxSections) {
                    nativeCtx = 1L;
                    return nativeCtx;
                }

                @Override
                public int[] findVisible(long context, float cameraX, float cameraY, float cameraZ,
                                          float fov, float renderDistance, int frameNumber) {
                    return new int[0];
                }

                @Override
                public void destroyContext(long context) {
                    nativeCtx = 0;
                }
            };
        }
        return bfsModule;
    }
}
