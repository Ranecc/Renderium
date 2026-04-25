// Renderium - 邻居面剔除器
// 判断方块面是否被相邻不透明方块完全遮挡
// 使用纯 int ID 操作，不直接依赖 MC 类

package com.renderium.optimization.culling;

/**
 * 邻居面剔除算法实现。
 *
 * <p>算法思路参考了 CaffeineMC 的 Sodium 项目 (LGPL-3.0) 中的 OcclusionCuller 和
 * 网格构建流程。本代码为 Renderium 团队完全独立重写，以适配 Vulkan CommandBuffer 管线。
 *
 * <h2>核心原理</h2>
 * <p>在体素渲染中，两个相邻方块之间的"内面"永远不会被玩家看到。
 * 如果一个方块的不透明形状在某个方向上完全覆盖了相邻方块的对应面，
 * 则该面的顶点数据无需生成，从而减少约 30-50% 的几何数据量。
 *
 * <p><b>重要</b>：本类不直接引用 Minecraft 的 Block/BlockState/Direction 类。
 * 所有操作使用 int 类型的 BlockState ID 和方向常量，
 * 由平台模块负责 ID 与对象的转换。
 *
 * @see <a href="https://github.com/CaffeineMC/sodium">Sodium Repository</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RenderiumNeighborFaceCuller {

    private static final int SECTION_SIZE = 16;
    private static final int DIRECTION_COUNT = 6;

    // 方向常量（对应 MC 的 Direction 枚举）
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    // 方向偏移量 [direction][dx, dy, dz]
    private static final int[][] DIRECTION_OFFSETS = {
        {0, -1, 0},  // DOWN
        {0, 1, 0},   // UP
        {0, 0, -1},  // NORTH
        {0, 0, 1},   // SOUTH
        {-1, 0, 0},  // WEST
        {1, 0, 0}    // EAST
    };

    private RenderiumNeighborFaceCuller() {
        throw new AssertionError("工具类不可实例化");
    }

    // ==================== 核心接口 ====================

    /**
     * 检查指定位置的方块在给定方向上的面是否应该被剔除
     *
     * @param blockStateId  当前方块的 BlockState ID
     * @param neighborStateId 相邻方向的邻居 BlockState ID（0 = 空气/不存在）
     * @param direction     方向常量 (DOWN/UP/NORTH/SOUTH/WEST/EAST)
     * @param occlusionData 遮挡查询接口
     * @return true 如果该面应被剔除（不可见）
     */
    public static boolean shouldCullFace(
            int blockStateId,
            int neighborStateId,
            int direction,
            OcclusionDataProvider occlusionData) {

        // 空气或不存在 → 保守保留
        if (neighborStateId == 0) {
            return false;
        }

        // 如果当前方块本身不是实心的，不剔除
        if (!occlusionData.isSolid(blockStateId)) {
            return false;
        }

        // 核心判断：邻居是否完全遮挡
        return occlusionData.isFullyOccluded(blockStateId, neighborStateId, direction);
    }

    /**
     * 批量计算一个区块所有方块的面可见性掩码
     *
     * @param sectionData    区块数据访问接口
     * @param occlusionData  遮挡查询接口
     * @return 三维数组 [4096]，每个元素是 6-bit 掩码
     *         bit 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST
     *         1 表示可见（需渲染），0 表示被剔除
     */
    public static byte[] computeFaceVisibilityMask(
            SectionDataProvider sectionData,
            OcclusionDataProvider occlusionData) {

        byte[] visibilityMask = new byte[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];

        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = (y << 8) | (z << 4) | x;
                    int stateId = sectionData.getBlockStateId(x, y, z);

                    // 空气方块：全部标记为不可见
                    if (stateId == 0) {
                        visibilityMask[index] = 0;
                        continue;
                    }

                    int mask = computeSingleBlockVisibility(stateId, x, y, z, sectionData, occlusionData);
                    visibilityMask[index] = (byte) mask;
                }
            }
        }

        return visibilityMask;
    }

    // ==================== 内部实现 ====================

    private static int computeSingleBlockVisibility(
            int stateId,
            int localX, int localY, int localZ,
            SectionDataProvider sectionData,
            OcclusionDataProvider occlusionData) {

        int mask = 0x3F; // 默认全部可见

        for (int dir = 0; dir < DIRECTION_COUNT; dir++) {
            int[] offset = DIRECTION_OFFSETS[dir];
            int nx = localX + offset[0];
            int ny = localY + offset[1];
            int nz = localZ + offset[2];

            int neighborId;
            if (nx < 0 || nx >= SECTION_SIZE ||
                ny < 0 || ny >= SECTION_SIZE ||
                nz < 0 || nz >= SECTION_SIZE) {
                neighborId = sectionData.getNeighborBlockStateId(nx, ny, nz, dir);
            } else {
                neighborId = sectionData.getBlockStateId(nx, ny, nz);
            }

            if (shouldCullFace(stateId, neighborId, dir, occlusionData)) {
                mask &= ~(1 << dir);
            }
        }

        return mask;
    }

    // ==================== 数据提供者接口 ====================

    /**
     * 区块数据提供者接口（基于 int ID）
     */
    public interface SectionDataProvider {

        /**
         * 获取指定局部坐标的 BlockState ID
         */
        int getBlockStateId(int x, int y, int z);

        /**
         * 获取区块边界外的邻居 BlockState ID
         *
         * @return 邻居 BlockState ID，0 表示无法获取/空气
         */
        int getNeighborBlockStateId(int localX, int localY, int localZ, int direction);
    }

    /**
     * 遮挡查询接口
     * <p>
     * 由平台模块实现，封装 MC 的遮挡判断逻辑
     * （如 BlockState.isSolidRender(), isFaceSturdy() 等）
     */
    public interface OcclusionDataProvider {

        /**
         * 检查指定 BlockState ID 是否为实心方块
         */
        boolean isSolid(int stateId);

        /**
         * 检查邻居是否完全遮挡了当前方块在指定方向上的面
         *
         * @param currentId  当前方块 BlockState ID
         * @param neighborId 邻居方块 BlockState ID
         * @param direction  方向常量
         * @return true 如果完全遮挡
         */
        boolean isFullyOccluded(int currentId, int neighborId, int direction);
    }
}
