package com.ranecc.renderium.feature.culling.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Distance culling strategy - culls chunks beyond max draw distance.
 */
public final class DistanceCullingStrategy implements CullingStrategy {

    private final int maxDrawDistance;

    public DistanceCullingStrategy(int maxDrawDistance) {
        this.maxDrawDistance = maxDrawDistance;
    }

    @Override
    public Set<Integer> cull(List<ChunkData> chunks, float cameraX, float cameraY,
                              float cameraZ, float pitch, float yaw, float fov) {
        Set<Integer> visible = new HashSet<>();
        float maxDistSq = (float) maxDrawDistance * maxDrawDistance;
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            float centerX = chunk.minX() + (chunk.maxX() - chunk.minX()) * 0.5f;
            float centerZ = chunk.minZ() + (chunk.maxZ() - chunk.minZ()) * 0.5f;
            float dx = centerX - cameraX;
            float dz = centerZ - cameraZ;
            if (dx * dx + dz * dz <= maxDistSq) {
                visible.add(i);
            }
        }
        return visible;
    }

    @Override
    public boolean isAdditive() {
        return true;
    }

    @Override
    public int getPriority() {
        return 1;
    }
}
