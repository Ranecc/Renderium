package com.ranecc.renderium.feature.culling.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Frustum culling strategy - culls chunks outside the camera view frustum.
 */
public final class FrustumCullingStrategy implements CullingStrategy {

    @Override
    public Set<Integer> cull(List<ChunkData> chunks, float cameraX, float cameraY,
                              float cameraZ, float pitch, float yaw, float fov) {
        Set<Integer> visible = new HashSet<>();
        // Minimal stub: all chunks within max distance are considered visible
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            float dx = chunk.minX() + (chunk.maxX() - chunk.minX()) * 0.5f - cameraX;
            float dz = chunk.minZ() + (chunk.maxZ() - chunk.minZ()) * 0.5f - cameraZ;
            float distSq = dx * dx + dz * dz;
            if (distSq < 1024.0f * 1024.0f) {
                visible.add(i);
            }
        }
        return visible;
    }

    @Override
    public boolean isAdditive() {
        return false;
    }

    @Override
    public int getPriority() {
        return 0;
    }
}
