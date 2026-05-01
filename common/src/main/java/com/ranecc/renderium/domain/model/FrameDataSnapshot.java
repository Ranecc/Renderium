package com.ranecc.renderium.domain.model;

public final class FrameDataSnapshot {

    private final float[] projectionMatrix;
    private final float[] viewMatrix;
    private final float[] viewProjectionMatrix;
    private final float[] invViewMatrix;
    private final float[][] previousViewMatrices;
    private int historyWriteIndex;

    private float cameraX, cameraY, cameraZ;
    private float yaw, pitch, fov;
    private float nearPlane, farPlane;

    private int windowWidth, windowHeight;
    private long gameTick;

    private final float[] fogColor;
    private float fogStart, fogEnd, fogDensity;
    private int fogType;
    private boolean fogEnabled;

    private int visibleSectionCount, totalSectionCount;
    private int opaqueDrawCallCount, translucentDrawCallCount;
    private boolean viewAreaChanged;

    private int frameIndex;

    public FrameDataSnapshot() {
        this.projectionMatrix = new float[16];
        this.viewMatrix = new float[16];
        this.viewProjectionMatrix = new float[16];
        this.invViewMatrix = new float[16];
        this.previousViewMatrices = new float[16][16];
        this.fogColor = new float[4];
        identity(projectionMatrix);
        identity(viewMatrix);
        identity(viewProjectionMatrix);
        identity(invViewMatrix);
        for (float[] mat : previousViewMatrices) {
            identity(mat);
        }
    }

    public float[] getProjectionMatrix() { return projectionMatrix; }
    public float[] getViewMatrix() { return viewMatrix; }
    public float[] getViewProjectionMatrix() { return viewProjectionMatrix; }
    public float[] getInvViewMatrix() { return invViewMatrix; }
    public float[][] getPreviousViewMatrices() { return previousViewMatrices; }

    public float getCameraX() { return cameraX; }
    public float getCameraY() { return cameraY; }
    public float getCameraZ() { return cameraZ; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getFov() { return fov; }
    public float getNearPlane() { return nearPlane; }
    public float getFarPlane() { return farPlane; }

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public long getGameTick() { return gameTick; }

    public float[] getFogColor() { return fogColor; }
    public float getFogStart() { return fogStart; }
    public float getFogEnd() { return fogEnd; }
    public float getFogDensity() { return fogDensity; }
    public int getFogType() { return fogType; }
    public boolean isFogEnabled() { return fogEnabled; }

    public int getVisibleSectionCount() { return visibleSectionCount; }
    public int getTotalSectionCount() { return totalSectionCount; }
    public int getOpaqueDrawCallCount() { return opaqueDrawCallCount; }
    public int getTranslucentDrawCallCount() { return translucentDrawCallCount; }
    public boolean isViewAreaChanged() { return viewAreaChanged; }

    public int getFrameIndex() { return frameIndex; }

    public void setProjectionMatrix(float[] src) {
        System.arraycopy(src, 0, projectionMatrix, 0, 16);
    }

    public void setViewMatrix(float[] src) {
        float[] slot = previousViewMatrices[historyWriteIndex];
        System.arraycopy(viewMatrix, 0, slot, 0, 16);
        historyWriteIndex = (historyWriteIndex + 1) & 0x0F;
        System.arraycopy(src, 0, viewMatrix, 0, 16);
    }

    public void setInvViewMatrix(float[] src) {
        System.arraycopy(src, 0, invViewMatrix, 0, 16);
    }

    public void recomputeVPMatrix() {
        mul4x4(viewMatrix, projectionMatrix, viewProjectionMatrix);
    }

    public void setCameraPosition(float x, float y, float z) {
        this.cameraX = x; this.cameraY = y; this.cameraZ = z;
    }

    public void setCameraRotation(float yaw, float pitch) {
        this.yaw = yaw; this.pitch = pitch;
    }

    public void setFov(float fov) { this.fov = fov; }
    public void setNearPlane(float near) { this.nearPlane = near; }
    public void setFarPlane(float far) { this.farPlane = far; }

    public void setWindowSize(int width, int height) {
        this.windowWidth = width; this.windowHeight = height;
    }

    public void setGameTick(long tick) { this.gameTick = tick; }

    public void setFogData(float r, float g, float b, float a,
                           float start, float end, float density, int type, boolean enabled) {
        fogColor[0] = r; fogColor[1] = g; fogColor[2] = b; fogColor[3] = a;
        fogStart = start; fogEnd = end; fogDensity = density;
        fogType = type; fogEnabled = enabled;
    }

    public void setChunkVisibility(int visible, int total,
                                    int opaqueDraws, int translucentDraws, boolean changed) {
        visibleSectionCount = visible; totalSectionCount = total;
        opaqueDrawCallCount = opaqueDraws; translucentDrawCallCount = translucentDraws;
        viewAreaChanged = changed;
    }

    public void setFrameIndex(int index) { this.frameIndex = index; }

    public void reset() {
        identity(projectionMatrix); identity(viewMatrix);
        identity(viewProjectionMatrix); identity(invViewMatrix);
        cameraX = 0; cameraY = 0; cameraZ = 0;
        yaw = 0; pitch = 0; fov = 70.0f;
        nearPlane = 0.05f; farPlane = 1000.0f;
        windowWidth = 0; windowHeight = 0; gameTick = 0;
        fogColor[0] = 0; fogColor[1] = 0; fogColor[2] = 0; fogColor[3] = 1;
        fogStart = 0; fogEnd = 1000; fogDensity = 0;
        fogType = 0; fogEnabled = false;
        visibleSectionCount = 0; totalSectionCount = 0;
        opaqueDrawCallCount = 0; translucentDrawCallCount = 0;
        viewAreaChanged = false;
        frameIndex++;
    }

    private static void identity(float[] m) {
        m[0]=1;m[4]=0;m[8]=0;m[12]=0;
        m[1]=0;m[5]=1;m[9]=0;m[13]=0;
        m[2]=0;m[6]=0;m[10]=1;m[14]=0;
        m[3]=0;m[7]=0;m[11]=0;m[15]=1;
    }

    private static void mul4x4(float[] a, float[] b, float[] r) {
        r[0]=a[0]*b[0]+a[4]*b[1]+a[8]*b[2]+a[12]*b[3];
        r[1]=a[1]*b[0]+a[5]*b[1]+a[9]*b[2]+a[13]*b[3];
        r[2]=a[2]*b[0]+a[6]*b[1]+a[10]*b[2]+a[14]*b[3];
        r[3]=a[3]*b[0]+a[7]*b[1]+a[11]*b[2]+a[15]*b[3];
        r[4]=a[0]*b[4]+a[4]*b[5]+a[8]*b[6]+a[12]*b[7];
        r[5]=a[1]*b[4]+a[5]*b[5]+a[9]*b[6]+a[13]*b[7];
        r[6]=a[2]*b[4]+a[6]*b[5]+a[10]*b[6]+a[14]*b[7];
        r[7]=a[3]*b[4]+a[7]*b[5]+a[11]*b[6]+a[15]*b[7];
        r[8]=a[0]*b[8]+a[4]*b[9]+a[8]*b[10]+a[12]*b[11];
        r[9]=a[1]*b[8]+a[5]*b[9]+a[9]*b[10]+a[13]*b[11];
        r[10]=a[2]*b[8]+a[6]*b[9]+a[10]*b[10]+a[14]*b[11];
        r[11]=a[3]*b[8]+a[7]*b[9]+a[11]*b[10]+a[15]*b[11];
        r[12]=a[0]*b[12]+a[4]*b[13]+a[8]*b[14]+a[12]*b[15];
        r[13]=a[1]*b[12]+a[5]*b[13]+a[9]*b[14]+a[13]*b[15];
        r[14]=a[2]*b[12]+a[6]*b[13]+a[10]*b[14]+a[14]*b[15];
        r[15]=a[3]*b[12]+a[7]*b[13]+a[11]*b[14]+a[15]*b[15];
    }

    @Override
    public String toString() {
        return String.format(
            "FrameDataSnapshot{camera=(%.1f,%.1f,%.1f), fov=%.1f, window=%dx%d, " +
            "tick=%d, visibleSections=%d/%d, frame=%d}",
            cameraX, cameraY, cameraZ, fov,
            windowWidth, windowHeight, gameTick,
            visibleSectionCount, totalSectionCount, frameIndex
        );
    }
}
