package com.ranecc.renderium.feature.renderopt.mesh;

public class CompactVertexFormat {
    public static final CompactVertexFormat CHUNK_MESH = new CompactVertexFormat();

    private int strideBytes;
    private boolean hasPosition = true;
    private boolean hasTexCoord = true;
    private boolean hasColor = false;
    private boolean hasNormal = true;
    private boolean hasLight = false;
    private boolean compressedTexCoord = true;

    public CompactVertexFormat() {}

    public int getStrideBytes() { return strideBytes; }
    public void setStrideBytes(int v) { this.strideBytes = v; }

    public boolean hasPosition() { return hasPosition; }
    public void setHasPosition(boolean v) { this.hasPosition = v; }

    public boolean hasTexCoord() { return hasTexCoord; }
    public void setHasTexCoord(boolean v) { this.hasTexCoord = v; }

    public boolean isCompressedTexCoord() { return compressedTexCoord; }
    public void setCompressedTexCoord(boolean v) { this.compressedTexCoord = v; }

    public boolean hasColor() { return hasColor; }
    public void setHasColor(boolean v) { this.hasColor = v; }

    public boolean hasNormal() { return hasNormal; }
    public void setHasNormal(boolean v) { this.hasNormal = v; }

    public boolean hasLight() { return hasLight; }
    public void setHasLight(boolean v) { this.hasLight = v; }

    public int getStride() { return strideBytes; }

    public static int packTexCoord(float u, float v) {
        return (Float.floatToRawIntBits(u) & 0xFFFF) | (Float.floatToRawIntBits(v) << 16);
    }

    public static int packColor(float r, float g, float b, float a) {
        return ((int)(r * 255) & 0xFF)
             | (((int)(g * 255) & 0xFF) << 8)
             | (((int)(b * 255) & 0xFF) << 16)
             | (((int)(a * 255) & 0xFF) << 24);
    }

    public static int packNormal(float x, float y, float z) {
        return ((int)(x * 127) & 0xFF)
             | (((int)(y * 127) & 0xFF) << 8)
             | (((int)(z * 127) & 0xFF) << 16);
    }

    public static short packLight(int packedLight) {
        return (short)(packedLight & 0xFFFF);
    }
}
