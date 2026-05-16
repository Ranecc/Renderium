package com.ranecc.renderium.feature.culling.optimization;

public class NeighborFaceCuller {

    public static final int NONE_VISIBLE = 0;

    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    public static final int MASK_DOWN = 1 << DOWN;
    public static final int MASK_UP = 1 << UP;
    public static final int MASK_NORTH = 1 << NORTH;
    public static final int MASK_SOUTH = 1 << SOUTH;
    public static final int MASK_WEST = 1 << WEST;
    public static final int MASK_EAST = 1 << EAST;

    private long totalFacesProcessed = 0;
    private long totalFacesCulled = 0;

    public NeighborFaceCuller() {}

    public boolean isFaceVisible(int face, Object neighborData) {
        totalFacesProcessed++;
        if (neighborData == null) {
            return true;
        }
        boolean visible = !Boolean.TRUE.equals(neighborData);
        if (!visible) {
            totalFacesCulled++;
        }
        return visible;
    }

    public boolean isFaceVisible(Object face) {
        totalFacesProcessed++;
        boolean visible = face != null;
        if (!visible) {
            totalFacesCulled++;
        }
        return visible;
    }

    public int computeSectionFaceVisibility(long sectionKey, int[] neighborVisibility) {
        if (neighborVisibility == null || neighborVisibility.length == 0) {
            totalFacesProcessed += 6;
            return 0x3F;
        }
        int bitmask = 0;
        int length = Math.min(neighborVisibility.length, 6);
        for (int i = 0; i < length; i++) {
            totalFacesProcessed++;
            if (neighborVisibility[i] == 0) {
                bitmask |= (1 << i);
            } else {
                totalFacesCulled++;
            }
        }
        for (int i = length; i < 6; i++) {
            totalFacesProcessed++;
            bitmask |= (1 << i);
        }
        return bitmask;
    }

    public byte[] computeSectionFaceVisibility(int[] blockStates, int sectionX, int sectionY, int sectionZ) {
        int size = 16;
        int volume = size * size * size;
        byte[] visibility = new byte[volume];
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    int index = (y << 8) | (z << 4) | x;
                    int stateId = blockStates[index];
                    if (stateId == 0) {
                        visibility[index] = NONE_VISIBLE;
                        continue;
                    }
                    byte mask = 0;
                    if (isExposed(blockStates, x, y, z, size)) {
                        if (!hasNeighbor(blockStates, x, y - 1, z, size)) mask |= MASK_DOWN;
                        if (!hasNeighbor(blockStates, x, y + 1, z, size)) mask |= MASK_UP;
                        if (!hasNeighbor(blockStates, x, y, z - 1, size)) mask |= MASK_NORTH;
                        if (!hasNeighbor(blockStates, x, y, z + 1, size)) mask |= MASK_SOUTH;
                        if (!hasNeighbor(blockStates, x - 1, y, z, size)) mask |= MASK_WEST;
                        if (!hasNeighbor(blockStates, x + 1, y, z, size)) mask |= MASK_EAST;
                    }
                    visibility[index] = mask;
                }
            }
        }
        return visibility;
    }

    private static boolean hasNeighbor(int[] blockStates, int x, int y, int z, int size) {
        if (x < 0 || x >= size || y < 0 || y >= size || z < 0 || z >= size) {
            return false;
        }
        return blockStates[(y << 8) | (z << 4) | x] != 0;
    }

    private static boolean isExposed(int[] blockStates, int x, int y, int z, int size) {
        return x == 0 || x == size - 1 ||
               y == 0 || y == size - 1 ||
               z == 0 || z == size - 1 ||
               !hasNeighbor(blockStates, x - 1, y, z, size) ||
               !hasNeighbor(blockStates, x + 1, y, z, size) ||
               !hasNeighbor(blockStates, x, y - 1, z, size) ||
               !hasNeighbor(blockStates, x, y + 1, z, size) ||
               !hasNeighbor(blockStates, x, y, z - 1, size) ||
               !hasNeighbor(blockStates, x, y, z + 1, size);
    }

    public double getCullRate() {
        if (totalFacesProcessed == 0) {
            return 0.0;
        }
        return (double) totalFacesCulled / totalFacesProcessed;
    }
}
