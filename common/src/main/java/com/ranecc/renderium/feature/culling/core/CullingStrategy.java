package com.ranecc.renderium.feature.culling.core;

import java.util.List;
import java.util.Set;

/**
 * Culling strategy interface for visibility determination.
 */
public interface CullingStrategy {

    /**
     * Execute culling and return visible chunk indices.
     *
     * @param chunks   all chunks to evaluate
     * @param cameraX  camera X position
     * @param cameraY  camera Y position
     * @param cameraZ  camera Z position
     * @param pitch    camera pitch
     * @param yaw      camera yaw
     * @param fov      camera field of view
     * @return set of visible chunk indices
     */
    Set<Integer> cull(List<ChunkData> chunks, float cameraX, float cameraY,
                      float cameraZ, float pitch, float yaw, float fov);

    /**
     * Whether this strategy is additive (union) or reductive (intersection).
     */
    boolean isAdditive();

    /**
     * Execution priority (lower = earlier).
     */
    int getPriority();

    /**
     * Chunk data record used for culling.
     */
    record ChunkData(
        int x, int y, int z,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ
    ) {}
}
