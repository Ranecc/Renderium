package com.ranecc.renderium.feature.renderopt;

public class GLStateSnapshot {

    // ==================== Capability Indices ====================
    public static final int CAP_BLEND = 0;
    public static final int CAP_DEPTH_TEST = 1;
    public static final int CAP_CULL_FACE = 2;
    public static final int CAP_STENCIL_TEST = 3;
    public static final int CAP_SCISSOR_TEST = 4;
    public static final int CAP_DITHER = 5;
    public static final int CAP_MULTISAMPLE = 6;
    private static final int CAP_COUNT = 32;

    // ==================== State Fields ====================
    private final long[] capabilityBits = new long[1];
    private int texture2DBinding;
    private int activeTextureUnit;
    private int blendEquationRgb;
    private int blendEquationAlpha;
    private int blendFuncSrcRgb;
    private int blendFuncDstRgb;
    private int blendFuncSrcAlpha;
    private int blendFuncDstAlpha;
    private int depthFunc;
    private boolean depthMask;
    private int cullFaceMode;
    private int frontFaceMode;
    private int polygonMode;
    private float colorR, colorG, colorB, colorA;
    private float clearColorR, clearColorG, clearColorB, clearColorA;
    private boolean colorWriteR, colorWriteG, colorWriteB, colorWriteA;
    private float depthRangeNear, depthRangeFar;
    private int programId;
    private boolean dirty;
    private int frameCounter;

    public GLStateSnapshot() {
        clearDirty();
    }

    // ==================== Capabilities ====================

    public boolean isCapabilityEnabled(int index) {
        if (index < 0 || index >= CAP_COUNT) return false;
        return (capabilityBits[0] & (1L << index)) != 0;
    }

    public void setCapability(int index, boolean enabled) {
        if (index < 0 || index >= CAP_COUNT) return;
        if (enabled) {
            capabilityBits[0] |= (1L << index);
        } else {
            capabilityBits[0] &= ~(1L << index);
        }
        dirty = true;
    }

    // ==================== Texture State ====================

    public int getTexture2DBinding() { return texture2DBinding; }

    public void bindTexture2D(int textureId) {
        this.texture2DBinding = textureId;
        dirty = true;
    }

    public void setActiveTexture(int unit) {
        this.activeTextureUnit = unit;
        dirty = true;
    }

    // ==================== Blend State ====================

    public void setBlendEquation(int mode) {
        this.blendEquationRgb = mode;
        this.blendEquationAlpha = mode;
        dirty = true;
    }

    public void setBlendFuncSrcRGB(int factor) {
        this.blendFuncSrcRgb = factor;
        dirty = true;
    }

    public void setBlendFuncDstRGB(int factor) {
        this.blendFuncDstRgb = factor;
        dirty = true;
    }

    // ==================== Depth State ====================

    public void setDepthFunc(int func) {
        this.depthFunc = func;
        dirty = true;
    }

    public void setDepthMask(boolean flag) {
        this.depthMask = flag;
        dirty = true;
    }

    // ==================== Cull/Face State ====================

    public void setCullFaceMode(int mode) {
        this.cullFaceMode = mode;
        dirty = true;
    }

    public void setFrontFace(int mode) {
        this.frontFaceMode = mode;
        dirty = true;
    }

    public void setPolygonMode(int mode) {
        this.polygonMode = mode;
        dirty = true;
    }

    // ==================== Color State ====================

    public void setColor(float r, float g, float b, float a) {
        this.colorR = r; this.colorG = g; this.colorB = b; this.colorA = a;
        dirty = true;
    }

    public void setClearColor(float r, float g, float b, float a) {
        this.clearColorR = r; this.clearColorG = g; this.clearColorB = b; this.clearColorA = a;
        dirty = true;
    }

    public void setColorWriteMask(boolean r, boolean g, boolean b, boolean a) {
        this.colorWriteR = r; this.colorWriteG = g; this.colorWriteB = b; this.colorWriteA = a;
        dirty = true;
    }

    // ==================== Depth Range ====================

    public void setDepthRange(float nearVal, float farVal) {
        this.depthRangeNear = nearVal;
        this.depthRangeFar = farVal;
        dirty = true;
    }

    // ==================== Program State ====================

    public void useProgram(int program) {
        this.programId = program;
        dirty = true;
    }

    // ==================== Dirty Flag ====================

    public boolean isDirty() { return dirty; }

    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public void clearDirty() { this.dirty = false; }

    public void forceDirty() { this.dirty = true; }

    // ==================== Frame Counter ====================

    public int getFrameCounter() { return frameCounter; }

    public void incrementFrameCounter() { this.frameCounter++; }

    // ==================== Query Convenience ====================

    public boolean isBlendEnabled() { return isCapabilityEnabled(CAP_BLEND); }

    public boolean isDepthTestEnabled() { return isCapabilityEnabled(CAP_DEPTH_TEST); }

    public boolean isCullFaceEnabled() { return isCapabilityEnabled(CAP_CULL_FACE); }

    // ==================== Pipeline ID (composite hash) ====================

    public int getPipelineId() {
        int hash = 1;
        hash = 31 * hash + (int)(capabilityBits[0] & 0xFFFFFFFFL);
        hash = 31 * hash + texture2DBinding;
        hash = 31 * hash + programId;
        hash = 31 * hash + blendEquationRgb;
        hash = 31 * hash + blendFuncSrcRgb;
        hash = 31 * hash + blendFuncDstRgb;
        hash = 31 * hash + depthFunc;
        hash = 31 * hash + (depthMask ? 1 : 0);
        hash = 31 * hash + cullFaceMode;
        hash = 31 * hash + frontFaceMode;
        hash = 31 * hash + polygonMode;
        return hash;
    }

    // ==================== State Capture/Restore ====================

    public long captureState() { return 0L; }

    public void restoreState(long state) {}
}
