package com.ranecc.renderium.platform.lifecycle;

public interface LifecycleManager {

    void beginFrame();

    void endFrame();

    void onBeforeGlobalUniform();

    void onAfterGlobalUniform();

    void onAfterSetProjection();

    void onAfterFogBuffer();

    void onAfterCameraRepos();

    void onAfterModelViewSet();

    void onAfterChunkPrepare();
}
