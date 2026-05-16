package com.ranecc.renderium.feature.culling.core;

/**
 * Frustum culler utility class providing edge-related types.
 */
public final class FrustumCuller {

    private FrustumCuller() {}

    /**
     * Chunk edge record representing a boundary between visible and hidden chunks.
     */
    public record ChunkEdge(
        int cx, int cy, int cz,
        int nx, int ny, int nz,
        Direction direction
    ) {
        /**
         * Direction enum for chunk edges.
         */
        public enum Direction {
            NORTH, SOUTH, EAST, WEST, UP, DOWN
        }
    }
}
