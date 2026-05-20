package com.ranecc.renderium.feature.renderopt.mesh;

import java.util.logging.Logger;

/**
 * 相邻面剔除器
 *
 * <p>判断方块的面是否被相邻方块遮挡而无需渲染。
 * 六个方向的面索引: 0=向下(-Y), 1=向上(+Y), 2=向北(-Z),
 * 3=向南(+Z), 4=向西(-X), 5=向东(+X)。
 *
 * <h2>面方向偏移对照：</h2>
 * <pre>
 * face=0 (DOWN):  (0, -1, 0)
 * face=1 (UP):    (0, +1, 0)
 * face=2 (NORTH): (0, 0, -1)
 * face=3 (SOUTH): (0, 0, +1)
 * face=4 (WEST):  (-1, 0, 0)
 * face=5 (EAST):  (+1, 0, 0)
 * </pre>
 */
public class NeighborFaceCuller {

    private static final Logger LOGGER = Logger.getLogger(NeighborFaceCuller.class.getName());

    /** 各面方向对应的邻居偏移量 */
    private static final int[][] FACE_OFFSETS = {
        {0, -1, 0},  // 0: DOWN
        {0, +1, 0},  // 1: UP
        {0, 0, -1},  // 2: NORTH
        {0, 0, +1},  // 3: SOUTH
        {-1, 0, 0},  // 4: WEST
        {+1, 0, 0},  // 5: EAST
    };

    /** 有效面索引范围 */
    private static final int MIN_FACE = 0;
    private static final int MAX_FACE = 5;

    /** 各面在 chunk 数据中的索引 */
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    public NeighborFaceCuller() {
    }

    /**
     * 检查指定位置的面是否被相邻方块遮挡。
     *
     * <p>算法：计算邻居坐标，检查该位置是否有不透明的方块。
     * 如果邻居不透明 → 此面被遮挡 → true（可剔除）。
     * 如果邻居不存在/透明/边界外 → false（需渲染）。
     *
     * @param x 方块 X 坐标
     * @param y 方块 Y 坐标
     * @param z 方块 Z 坐标
     * @param face 面索引 (0-5)
     * @return true 表示该面被遮挡可剔除
     */
    public boolean isCulled(int x, int y, int z, int face) {
        if (face < MIN_FACE || face > MAX_FACE) return false;

        int nx = x + FACE_OFFSETS[face][0];
        int ny = y + FACE_OFFSETS[face][1];
        int nz = z + FACE_OFFSETS[face][2];

        return isSolidBlock(nx, ny, nz);
    }

    /**
     * 判断指定坐标是否有不透明（固体）方块。
     *
     * <p>子类可以覆盖此方法以集成真实的方块注册表查询。
     * 默认实现：假设世界边界内的所有非零坐标都是固体。
     *
     * @param x 世界坐标 X
     * @param y 世界坐标 Y
     * @param z 世界坐标 Z
     * @return true 如果该位置有固体方块
     */
    protected boolean isSolidBlock(int x, int y, int z) {
        if (!isInWorldBounds(x, y, z)) return false;
        return true;
    }

    /**
     * 检查坐标是否在世界范围内。
     * Minecraft 高度范围: -64 ~ 320, 水平范围 ~ ±30M
     */
    private static boolean isInWorldBounds(int x, int y, int z) {
        if (y < -64 || y > 320) return false;
        long absX = x < 0 ? -(long) x : (long) x;
        long absZ = z < 0 ? -(long) z : (long) z;
        return absX <= 30_000_000L && absZ <= 30_000_000L;
    }

    /**
     * 获取面的法线方向名称
     */
    public static String getFaceName(int face) {
        return switch (face) {
            case DOWN -> "DOWN";
            case UP -> "UP";
            case NORTH -> "NORTH";
            case SOUTH -> "SOUTH";
            case WEST -> "WEST";
            case EAST -> "EAST";
            default -> "UNKNOWN(" + face + ")";
        };
    }
}
