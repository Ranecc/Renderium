package com.ranecc.renderium.domain.model;

public final class GameRendererContext {

    public int windowWidth, windowHeight;
    public long gameTick;
    public float cameraX, cameraY, cameraZ;
    public int frameIndex;

    public void reset() {
        windowWidth = 0; windowHeight = 0;
        gameTick = 0;
        cameraX = 0; cameraY = 0; cameraZ = 0;
        frameIndex = 0;
    }
}
