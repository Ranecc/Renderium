package com.ranecc.renderium.platform.lifecycle;

public interface GameRendererListener {

    void onRenderBegin(float deltaTime);

    void onRenderEnd(float deltaTime);
}
