package com.ranecc.renderium.feature.lod;

public class LODChunk {
    public final int x, y, z;
    public final int lodLevel;
    public LODChunk(int x, int y, int z, int lodLevel) {
        this.x = x; this.y = y; this.z = z; this.lodLevel = lodLevel;
    }
}
