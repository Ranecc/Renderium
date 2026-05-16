package com.ranecc.renderium.feature.blaze3d.aggressive;

public final class ChunkRenderData {
    public int chunkX, chunkY, chunkZ;
    public long vertexArrayHandle;
    public int indexCount;
    public int vertexCount;
    public int materialId;
    public float distanceFromCamera;

    public ChunkRenderData() {}

    public long getChunkId() {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }
}
