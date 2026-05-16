package com.ranecc.renderium.feature.pipeline.strategy;

public interface BfsOcclusion {
    long createContext(int maxSections);
    int[] findVisible(long context, float cameraX, float cameraY, float cameraZ,
                      float fov, float renderDistance, int frameNumber);
    void destroyContext(long context);
}
