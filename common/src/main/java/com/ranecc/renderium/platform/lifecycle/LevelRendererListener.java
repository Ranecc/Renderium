package com.ranecc.renderium.platform.lifecycle;

public interface LevelRendererListener {

    void onRenderBegin();

    void onRenderEnd(boolean worldLoaded);
}
