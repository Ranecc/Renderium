// Renderium - 高度图遮挡剔除器
// 使用区块高度图快速判断远处区段是否被近处地形遮挡
// 适用于平原、丘陵等开阔地形的远景优化

package com.renderium.optimization.culling;

import java.util.logging.Logger;

/**
 * 高度图遮挡剔除器。
 *
 * <p>使用区块列的高度图快速判断远处区段是否被近处地形完全遮挡。
 * 这是一种低精度但高效率的遮挡剔除方法，特别适用于：
 * <ul>
 *   <li>平原地形：远处的低矮区段被近处山丘遮挡</li>
 *   <li>地下场景：地表以上的区段遮挡地下区段</li>
 *   <li>城市/建筑：大型建筑遮挡后方的区段</li>
 * </ul>
 *
 * <h2>算法原理</h2>
 * <p>对于每个 (X, Z) 列，维护该列中最高不透明方块的 Y 坐标。
 * 当判断远处区段是否可见时：
 * <ol>
 *   <li>从相机位置向目标区段投射一条水平线</li>
 *   <li>检查线路上所有中间列的高度</li>
 *   <li>如果任何中间列的高度 >= 目标区段的顶部，则目标被遮挡</li>
 * </ol>
 *
 * <h2>性能特征</h2>
 * <ul>
 *   <li><b>构建</b>：O(N) 其中 N 为区块数量</li>
 *   <li><b>查询</b>：O(D) 其中 D 为相机到目标的水平距离</li>
 *   <li><b>内存</b>：每个区块列 1 个 short（2 bytes）</li>
 * </ul>
 *
 * <h2>局限性</h2>
 * <p>高度图是列级的粗粒度遮挡，无法处理：
 * <ul>
 *   <li>部分遮挡（只遮挡了区段的一部分）</li>
 *   <li>透明方块（玻璃、水等）</li>
 *   <li>非完整方块（台阶、栅栏等）</li>
 * </ul>
 * <p>因此高度图遮挡剔除应作为保守剔除使用：
 * 只有"确定被遮挡"的区段才跳过，"不确定"的区段仍然渲染。
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class HeightmapOcclusionCuller {

    private static final Logger LOGGER = Logger.getLogger(HeightmapOcclusionCuller.class.getName());

    /** 区块尺寸 */
    private static final int CHUNK_SIZE = 16;

    /** 高度图数据：heightMap[chunkX][chunkZ] = 该列最高不透明方块的 Y 坐标 */
    private short[][] heightMap;

    /** 高度图覆盖的区块范围（半径） */
    private int radius;

    /** 高度图中心 X（区块坐标） */
    private int centerChunkX;

    /** 高度图中心 Z（区块坐标） */
    private int centerChunkZ;

    /** 上次更新的帧号 */
    private long lastUpdateFrame = -1;

    // ==================== 统计 ====================

    /** 查询次数 */
    private long queryCount = 0;

    /** 遮挡次数 */
    private long occludedCount = 0;

    // ==================== 构造 ====================

    /**
     * 创建高度图遮挡剔除器
     *
     * @param radius 覆盖半径（区块数）
     */
    public HeightmapOcclusionCuller(int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive: " + radius);
        }
        this.radius = radius;
        int size = radius * 2 + 1;
        this.heightMap = new short[size][size];
    }

    // ==================== 高度图更新 ====================

    /**
     * 更新高度图中心
     *
     * <p>当玩家移动到新区块时调用。如果中心未变化则跳过。
     *
     * @param chunkX 新中心 X（区块坐标）
     * @param chunkZ 新中心 Z（区块坐标）
     * @param frame 当前帧号
     */
    public void updateCenter(int chunkX, int chunkZ, long frame) {
        if (chunkX == centerChunkX && chunkZ == centerChunkZ) return;

        this.centerChunkX = chunkX;
        this.centerChunkZ = chunkZ;
        this.lastUpdateFrame = frame;

        // 清空高度图（新区块数据需要重新填充）
        int size = radius * 2 + 1;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                heightMap[x][z] = Short.MIN_VALUE;
            }
        }
    }

    /**
     * 设置指定区块列的高度
     *
     * @param chunkX 区块 X 坐标（世界）
     * @param chunkZ 区块 Z 坐标（世界）
     * @param height 最高不透明方块的 Y 坐标
     */
    public void setHeight(int chunkX, int chunkZ, short height) {
        int lx = chunkX - centerChunkX + radius;
        int lz = chunkZ - centerChunkZ + radius;

        if (lx < 0 || lx >= heightMap.length || lz < 0 || lz >= heightMap[0].length) {
            return; // 超出覆盖范围
        }

        heightMap[lx][lz] = height;
    }

    /**
     * 获取指定区块列的高度
     *
     * @param chunkX 区块 X 坐标（世界）
     * @param chunkZ 区块 Z 坐标（世界）
     * @return 最高不透明方块的 Y 坐标，Short.MIN_VALUE 表示未知
     */
    public short getHeight(int chunkX, int chunkZ) {
        int lx = chunkX - centerChunkX + radius;
        int lz = chunkZ - centerChunkZ + radius;

        if (lx < 0 || lx >= heightMap.length || lz < 0 || lz >= heightMap[0].length) {
            return Short.MIN_VALUE; // 超出范围
        }

        return heightMap[lx][lz];
    }

    // ==================== 遮挡查询 ====================

    /**
     * 判断目标区段是否被高度图遮挡
     *
     * <p>从相机位置向目标区段投射水平线，检查中间列的高度。
     * 使用 Bresenham 直线算法遍历中间列。
     *
     * @param cameraX 相机 X 坐标（世界）
     * @param cameraY 相机 Y 坐标（世界）
     * @param cameraZ 相机 Z 坐标（世界）
     * @param targetChunkX 目标区块 X 坐标
     * @param targetMinY 目标区段最低 Y 坐标
     * @param targetMaxY 目标区段最高 Y 坐标
     * @param targetChunkZ 目标区块 Z 坐标
     * @return true 如果目标被高度图遮挡（保守判断：不确定时返回 false）
     */
    public boolean isOccluded(float cameraX, float cameraY, float cameraZ,
                               int targetChunkX, int targetMinY, int targetMaxY,
                               int targetChunkZ) {
        queryCount++;

        int cameraChunkX = (int) Math.floor(cameraX / CHUNK_SIZE);
        int cameraChunkZ = (int) Math.floor(cameraZ / CHUNK_SIZE);

        // 同一区块列不可能被遮挡
        if (cameraChunkX == targetChunkX && cameraChunkZ == targetChunkZ) {
            return false;
        }

        // 使用 Bresenham 算法遍历从相机到目标之间的区块列
        int dx = Math.abs(targetChunkX - cameraChunkX);
        int dz = Math.abs(targetChunkZ - cameraChunkZ);
        int sx = cameraChunkX < targetChunkX ? 1 : -1;
        int sz = cameraChunkZ < targetChunkZ ? 1 : -1;
        int err = dx - dz;

        // Pre-compute constant denominator outside loop (was called every iteration)
        float totalDistance = distance2D(cameraChunkX, cameraChunkZ, targetChunkX, targetChunkZ);
        // Avoid division by zero
        if (totalDistance < 1e-6f) {
            return false;
        }
        float invTotalDistance = 1.0f / totalDistance;

        int x = cameraChunkX;
        int z = cameraChunkZ;

        while (true) {
            // Skip camera column and target column
            if (x != cameraChunkX || z != cameraChunkZ) {
                if (x == targetChunkX && z == targetChunkZ) break;

                short colHeight = getHeight(x, z);

                // If height data unknown, conservatively assume not occluded
                if (colHeight == Short.MIN_VALUE) {
                    return false;
                }

                // Calculate line-of-sight projection height at this column
                // Optimized: use precomputed inverse distance to avoid division in loop
                float t = distance2D(cameraChunkX, cameraChunkZ, x, z) * invTotalDistance;

                float interpolatedCameraY = cameraY;
                float lineYAtCol = interpolatedCameraY + (targetMaxY - interpolatedCameraY) * t;

                // 如果该列的高度 >= 视线在该列的高度，则遮挡
                if (colHeight >= lineYAtCol) {
                    occludedCount++;
                    return true;
                }
            }

            // Bresenham 步进
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }

        return false;
    }

    /**
     * 批量遮挡查询
     *
     * @param cameraX 相机 X
     * @param cameraY 相机 Y
     * @param cameraZ 相机 Z
     * @param targets 目标数组 [chunkX, minY, maxY, chunkZ] × N
     * @return 遮挡结果数组，true=被遮挡
     */
    public boolean[] batchQuery(float cameraX, float cameraY, float cameraZ,
                                 int[][] targets) {
        boolean[] results = new boolean[targets.length];
        for (int i = 0; i < targets.length; i++) {
            results[i] = isOccluded(cameraX, cameraY, cameraZ,
                    targets[i][0], targets[i][1], targets[i][2], targets[i][3]);
        }
        return results;
    }

    // ==================== 辅助方法 ====================

    /**
     * 2D 距离计算（区块坐标）
     */
    private float distance2D(int x1, int z1, int x2, int z2) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    // ==================== 统计 ====================

    /**
     * 获取遮挡率
     */
    public float getOcclusionRate() {
        if (queryCount == 0) return 0f;
        return (float) occludedCount / queryCount;
    }

    public long getQueryCount() { return queryCount; }
    public long getOccludedCount() { return occludedCount; }
    public int getRadius() { return radius; }
    public long getLastUpdateFrame() { return lastUpdateFrame; }

    /**
     * 重置统计
     */
    public void resetStats() {
        queryCount = 0;
        occludedCount = 0;
    }

    public String getDiagnostics() {
        return String.format(
            "HeightmapOcclusionCuller{radius=%d, center=(%d,%d), queries=%d, occluded=%d, rate=%.1f%%}",
            radius, centerChunkX, centerChunkZ, queryCount, occludedCount, getOcclusionRate() * 100);
    }
}
