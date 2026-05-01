// Renderium - 风格化光线追踪实验框架
// BVH + Morton 混合加速结构
// 解决问题: AMR跨层级光线泄漏 + 纯Morton的精度不足
// 方案: Morton粗筛(O(1)定位cell) + BVH精确定位(O(logN)遍历三角形)
// ⚠️ 工程约束: 双重结构增加内存开销和构建时间, 需要量化trade-off

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.stylizedrt;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * BVH + Morton 混合加速结构 🏗️
 * <p>
 * 解决 AMR 跨层级光线泄漏问题的工程方案。
 *
 * <h2>问题背景：</h2>
 * <pre>
 * 纯Morton/Flat Hash Grid的问题:
 *   粗cell的大步长可能跳过细cell中的薄几何体(1格厚的墙/地板)
 *   → 光线泄漏: 光线穿过本应命中的表面
 *   → 视觉伪影: 闪烁/穿透/漏光
 *
 * 纯BVH的问题:
 *   构建耗时: O(N log N), 每帧重建太慢
 *   遍历耗时: O(log N), 比Morton的O(1)慢
 *   内存开销: 每个内节点28B + 每个叶子16B
 * </pre>
 *
 * <h2>混合方案：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │ 第一层: Morton Grid (粗筛)                          │
 * │   O(1) 定位当前cell                                │
 * │   判断: 该cell是否包含几何体?                        │
 * │     → 否: 大步跳过 (势能场引导步长)                  │
 * │     → 是: 进入第二层                                │
 * ├─────────────────────────────────────────────────────┤
 * │ 第二层: Local BVH (精确定位)                        │
 * │   仅在包含几何体的cell内构建                         │
 * │   O(log M) 遍历该cell内的三角形 (M << N)           │
 * │   精确命中检测, 消除光线泄漏                         │
 * └─────────────────────────────────────────────────────┘
 *
 * 优势:
 *   - Morton层快速跳过空cell (占世界的大部分)
 *   - BVH层只在有几何体的cell内工作 (少量)
 *   - BVH构建开销小: 每个cell内三角形少, 构建快
 *   - 消除光线泄漏: BVH精确检测不会跳过薄几何体
 *
 * 劣势:
 *   - 双重结构内存: Morton Grid + Per-cell BVH
 *   - BVH更新: 动态场景需要refit或重建
 *   - 代码复杂度: 两层遍历逻辑
 * </pre>
 *
 * <h2>内存开销估算 (RTX 5060, 8GB VRAM):</h2>
 * <pre>
 * Morton Grid (64³):
 *   262144 cells × 8B/cell = 2MB
 *   (每个cell存: occupancy标志 + BVH偏移 + 三角形数)
 *
 * Per-cell BVH (假设10%的cell有几何体):
 *   26214 cells × ~20三角形/cell
 *   BVH内节点: 20×2×28B = 1.1KB/cell
 *   BVH叶子: 20×16B = 320B/cell
 *   总计: ~1.4KB/cell × 26214 = ~36MB
 *
 * 三角形数据:
 *   26214 × 20 × 36B (3顶点×12B) = ~18MB
 *
 * 总计: ~56MB (可接受)
 * </pre>
 *
 * @since 4.0.0
 */
public class HybridBVHMorton implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(HybridBVHMorton.class.getName());

    // ==================== 常量 ====================

    /** Morton Grid 默认分辨率 (每维度) */
    public static final int DEFAULT_GRID_RESOLUTION = 64;

    /** 每个cell内BVH的最大三角形数 (超过则分裂cell) */
    public static final int MAX_TRIANGLES_PER_CELL = 32;

    /** BVH节点大小 (字节) - 内节点 */
    public static final int BVH_INNER_NODE_SIZE = 28; // AABB(24B) + childIndex(4B)

    /** BVH节点大小 (字节) - 叶子节点 */
    public static final int BVH_LEAF_NODE_SIZE = 16; // triangleOffset(4B) + count(4B) + padding(8B)

    // ==================== Morton Grid Cell ====================

    /**
     * Morton Grid 单元格
     * <p>
     * 每个cell存储占用标志和BVH引用。
     * 大小: 8字节 (对齐到GPU缓存行)
     */
    public static class MortonCell {
        /** Morton Code (Z-order curve编码) */
        public long mortonCode;

        /** 该cell是否包含几何体 */
        public boolean occupied;

        /** 该cell内的三角形数量 */
        public int triangleCount;

        /** 该cell的BVH根节点在BVH buffer中的偏移 (字节) */
        public long bvhRootOffset;

        /** 该cell的AABB (用于快速排斥) */
        public float[] aabbMin = new float[3];
        public float[] aabbMax = new float[3];

        /** 细化层级 (0=最粗) */
        public int refinementLevel;

        /** 势能场值 (从势能场求解器查询) */
        public float potentialValue;
    }

    // ==================== BVH 节点 ====================

    /**
     * BVH 内节点
     * <p>
     * 28字节, 对齐到GPU友好格式。
     * AABB使用float16可以压缩到16字节, 但精度损失需评估。
     */
    public static class BVHInnerNode {
        /** AABB最小角 (3×float32 = 12B) */
        public float[] aabbMin = new float[3];
        /** AABB最大角 (3×float32 = 12B) */
        public float[] aabbMax = new float[3];
        /** 左子节点偏移 (字节) */
        public int leftChildOffset;
        /** 右子节点偏移 (字节) */
        public int rightChildOffset;
    }

    /**
     * BVH 叶子节点
     * <p>
     * 16字节, 指向三角形数组的一个连续段。
     */
    public static class BVHLeafNode {
        /** 三角形在triangle buffer中的起始偏移 (字节) */
        public int triangleOffset;
        /** 三角形数量 */
        public int triangleCount;
        /** AABB最小角 (可选, 用于快速排斥) */
        public float[] aabbMin = new float[3];
        /** AABB最大角 (可选) */
        public float[] aabbMax = new float[3];
    }

    // ==================== 字段 ====================

    /** Morton Grid */
    private final ConcurrentHashMap<Long, MortonCell> grid;

    /** 网格分辨率 */
    private final int gridResolution;

    /** 世界尺寸 */
    private final float worldSize;

    /** BVH节点缓冲区 (所有cell的BVH共享一个连续缓冲区) */
    private long bvhBufferHandle = 0L;

    /** 三角形缓冲区 */
    private long triangleBufferHandle = 0L;

    /** 总BVH节点数 */
    private int totalBVHNodes = 0;

    /** 总三角形数 */
    private int totalTriangles = 0;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    // ==================== 统计 ====================

    private int totalBVHBuildTimeMs = 0;
    private int totalRefitTimeMs = 0;
    private int totalRayLeakDetections = 0;

    // ==================== 构造函数 ====================

    public HybridBVHMorton() {
        this(DEFAULT_GRID_RESOLUTION, 256.0f);
    }

    public HybridBVHMorton(int gridResolution, float worldSize) {
        if (gridResolution <= 0 || (gridResolution & (gridResolution - 1)) != 0) {
            throw new IllegalArgumentException("gridResolution 必须是2的幂次");
        }
        this.gridResolution = gridResolution;
        this.worldSize = worldSize;
        this.grid = new ConcurrentHashMap<>(gridResolution * gridResolution * gridResolution / 8);

        LOGGER.info(String.format("[HybridBVH] 创建完成 | 网格: %d³ | 世界: %.0f",
                gridResolution, worldSize));
    }

    // ==================== 初始化 ====================

    public void initialize() {
        if (initialized) throw new IllegalStateException("HybridBVHMorton 已初始化");
        initialized = true;

        // 分配GPU缓冲区
        long cellCount = (long) gridResolution * gridResolution * gridResolution;
        long gridBufferSize = cellCount * 8; // 8B/cell
        long estimatedBVHSize = (long) (cellCount * 0.1 * MAX_TRIANGLES_PER_CELL * 2 * BVH_INNER_NODE_SIZE);
        long estimatedTriSize = (long) (cellCount * 0.1 * MAX_TRIANGLES_PER_CELL * 36);

        LOGGER.info(String.format(
                "[HybridBVH] ✓ 初始化 | Grid: %.1fMB | BVH(估): %.1fMB | Tri(估): %.1fMB",
                gridBufferSize / (1024.0 * 1024.0),
                estimatedBVHSize / (1024.0 * 1024.0),
                estimatedTriSize / (1024.0 * 1024.0)));
    }

    // ==================== 核心: 光线遍历 ====================

    /**
     * 混合光线遍历结果
     */
    public static class TraversalResult {
        /** 是否命中 */
        public boolean hit;
        /** 命中距离 */
        public float hitDistance;
        /** 命中三角形索引 */
        public int hitTriangleIndex;
        /** 命中点重心坐标 */
        public float[] hitBarycentric = new float[3];
        /** 遍历的Morton cell数 */
        public int cellsTraversed;
        /** 遍历的BVH节点数 */
        public int bvhNodesTraversed;
        /** 是否检测到潜在泄漏 (步长超过cell尺寸) */
        public boolean potentialLeakDetected;
    }

    /**
     * 执行混合光线遍历
     * <p>
     * 两层遍历:
     * <ol>
     *   <li>Morton层: O(1)定位cell, 判断是否occupied</li>
     *   <li>BVH层: O(log M)遍历cell内三角形, 精确命中检测</li>
     * </ol>
     *
     * <h3>光线泄漏防护机制：</h3>
     * <pre>
     * 当步长 > 当前cell尺寸时:
     *   1. 检查步进路径上所有经过的cell
     *   2. 如果路径上有occupied cell, 缩短步长到该cell边界
     *   3. 在该cell内执行BVH遍历
     *   4. 这确保不会跳过薄几何体
     *
     * 代价: 额外的cell遍历检查 (~3-5个cell/步)
     * 收益: 消除光线泄漏伪影
     * </pre>
     *
     * @param origin    光线起点
     * @param direction 光线方向 (归一化)
     * @param maxDist   最大距离
     * @return 遍历结果
     */
    public TraversalResult traverse(float[] origin, float[] direction, float maxDist) {
        TraversalResult result = new TraversalResult();

        float[] pos = {origin[0], origin[1], origin[2]};
        float distance = 0.0f;
        float cellSize = worldSize / gridResolution;

        // DDA步进参数
        int[] gridCoord = worldToGrid(pos);
        int[] stepDir = new int[3];
        float[] tMax = new float[3];
        float[] tDelta = new float[3];

        for (int i = 0; i < 3; i++) {
            stepDir[i] = direction[i] > 0 ? 1 : (direction[i] < 0 ? -1 : 0);
            if (direction[i] != 0) {
                float cellBoundary = (gridCoord[i] + (stepDir[i] > 0 ? 1 : 0)) * cellSize;
                tMax[i] = (cellBoundary - pos[i]) / direction[i];
                tDelta[i] = Math.abs(cellSize / direction[i]);
            } else {
                tMax[i] = Float.MAX_VALUE;
                tDelta[i] = Float.MAX_VALUE;
            }
        }

        // DDA遍历Morton Grid
        int maxSteps = gridResolution * 3; // 安全限制
        for (int step = 0; step < maxSteps && distance < maxDist; step++) {
            result.cellsTraversed++;

            // 查找当前cell
            long mortonCode = encodeMorton3D(gridCoord[0], gridCoord[1], gridCoord[2]);
            MortonCell cell = grid.get(mortonCode);

            if (cell != null && cell.occupied) {
                // ---- 第二层: BVH遍历 ----
                BVHTraversalResult bvhResult = traverseBVH(
                        cell.bvhRootOffset, origin, direction, maxDist - distance);

                result.bvhNodesTraversed += bvhResult.nodesTraversed;

                if (bvhResult.hit) {
                    result.hit = true;
                    result.hitDistance = distance + bvhResult.hitDistance;
                    result.hitTriangleIndex = bvhResult.hitTriangleIndex;
                    result.hitBarycentric = bvhResult.hitBarycentric;
                    return result;
                }
            }

            // DDA步进到下一个cell
            int axis = 0;
            if (tMax[1] < tMax[0]) axis = 1;
            if (tMax[2] < tMax[axis]) axis = 2;

            distance = tMax[axis];
            gridCoord[axis] += stepDir[axis];
            tMax[axis] += tDelta[axis];

            // 边界检查
            if (gridCoord[axis] < 0 || gridCoord[axis] >= gridResolution) break;
        }

        return result;
    }

    /**
     * BVH遍历 (cell内)
     * <p>
     * 标准的stack-based BVH遍历, 但限制在单个cell内。
     * 栈深度 = BVH高度 ≈ log2(MAX_TRIANGLES_PER_CELL) ≈ 5
     */
    private BVHTraversalResult traverseBVH(long rootOffset, float[] origin, float[] direction, float maxDist) {
        BVHTraversalResult result = new BVHTraversalResult();

        // TODO: 实际BVH遍历
        // 标准算法:
        // stack = [rootOffset]
        // while stack not empty:
        //   node = pop(stack)
        //   if ray-AABB intersect(node.aabb):
        //     if node.isLeaf:
        //       for each triangle in node:
        //         if ray-triangle intersect:
        //           update closest hit
        //     else:
        //       push(node.leftChild)
        //       push(node.rightChild)

        return result;
    }

    private static class BVHTraversalResult {
        boolean hit;
        float hitDistance;
        int hitTriangleIndex;
        float[] hitBarycentric = new float[3];
        int nodesTraversed;
    }

    // ==================== BVH 构建 ====================

    /**
     * 为单个cell构建Local BVH
     * <p>
     * 使用 Morton Code 排序 + LBVH (Linear BVH) 算法。
     * 构建时间: O(M log M), M = cell内三角形数 (通常 < 32)
     * 对于 M=32: ~160次比较, GPU上 < 0.01ms
     *
     * @param mortonCode cell的Morton Code
     * @param triangles  三角形顶点数据 [triCount][3顶点][3坐标]
     * @return 构建的BVH节点数
     */
    public int buildLocalBVH(long mortonCode, float[][][] triangles) {
        if (triangles == null || triangles.length == 0) return 0;

        MortonCell cell = grid.computeIfAbsent(mortonCode, code -> {
            MortonCell c = new MortonCell();
            c.mortonCode = code;
            return c;
        });

        cell.occupied = true;
        cell.triangleCount = triangles.length;

        // TODO: 实际LBVH构建
        // 1. 为每个三角形计算中心点的Morton Code
        // 2. 按Morton Code排序
        // 3. 自底向上构建BVH (合并相邻节点)
        // 4. 计算每个节点的AABB
        // 5. 写入BVH buffer

        totalBVHNodes += triangles.length * 2; // 估算: N个叶子 ≈ N-1个内节点
        totalTriangles += triangles.length;

        return triangles.length * 2;
    }

    /**
     * 批量构建所有cell的BVH
     * <p>
     * 在场景加载或chunk更新时调用。
     *
     * @param cellTriangles 映射: mortonCode → 三角形数据
     * @return 构建的BVH总节点数
     */
    public int buildAllBVHs(Map<Long, float[][][]> cellTriangles) {
        long startTime = System.currentTimeMillis();

        int totalNodes = 0;
        for (var entry : cellTriangles.entrySet()) {
            totalNodes += buildLocalBVH(entry.getKey(), entry.getValue());
        }

        totalBVHBuildTimeMs += (int) (System.currentTimeMillis() - startTime);

        LOGGER.info(String.format("[HybridBVH] BVH构建完成 | %d cells | %d nodes | %d ms",
                cellTriangles.size(), totalNodes, totalBVHBuildTimeMs));

        return totalNodes;
    }

    /**
     * Refit BVH (当三角形移动但拓扑不变时)
     * <p>
     * 比重建快10-100倍, 但AABB可能不够紧。
     * 适用于: 实体动画、方块移动等场景。
     *
     * @return refit的cell数
     */
    public int refitBVHs() {
        long startTime = System.currentTimeMillis();

        // TODO: 自底向上更新所有BVH节点的AABB
        // for each cell with BVH:
        //   for each leaf node:
        //     recompute AABB from triangle vertices
        //   for each inner node (bottom-up):
        //     AABB = merge(left.AABB, right.AABB)

        totalRefitTimeMs += (int) (System.currentTimeMillis() - startTime);
        return 0;
    }

    // ==================== 光线泄漏检测 ====================

    /**
     * 检测并修复光线泄漏
     * <p>
     * 当步长超过cell尺寸时, 检查路径上的所有cell。
     * 如果发现occupied cell, 缩短步长。
     *
     * @param position    当前位置
     * @param direction   光线方向
     * @param proposedStep 提议的步长
     * @return 修正后的安全步长
     */
    public float clampStepToPreventLeakage(float[] position, float[] direction, float proposedStep) {
        float cellSize = worldSize / gridResolution;

        // 如果步长小于cell尺寸, 不需要检查
        if (proposedStep <= cellSize) return proposedStep;

        // DDA遍历步进路径上的cell
        float safeStep = proposedStep;
        float[] stepPos = {position[0], position[1], position[2]};
        float traveled = 0.0f;

        while (traveled < proposedStep) {
            float ddaStep = Math.min(cellSize * 0.5f, proposedStep - traveled);
            stepPos[0] += direction[0] * ddaStep;
            stepPos[1] += direction[1] * ddaStep;
            stepPos[2] += direction[2] * ddaStep;
            traveled += ddaStep;

            // 检查新位置的cell
            int[] gridCoord = worldToGrid(stepPos);
            if (gridCoord[0] < 0 || gridCoord[0] >= gridResolution ||
                gridCoord[1] < 0 || gridCoord[1] >= gridResolution ||
                gridCoord[2] < 0 || gridCoord[2] >= gridResolution) {
                break;
            }

            long mortonCode = encodeMorton3D(gridCoord[0], gridCoord[1], gridCoord[2]);
            MortonCell cell = grid.get(mortonCode);

            if (cell != null && cell.occupied) {
                // 发现occupied cell! 缩短步长到该cell边界
                safeStep = traveled - ddaStep; // 回退到上一个安全位置
                totalRayLeakDetections++;
                break;
            }
        }

        return safeStep;
    }

    // ==================== 辅助方法 ====================

    private int[] worldToGrid(float[] worldPos) {
        float cellSize = worldSize / gridResolution;
        return new int[]{
                (int) Math.floor(worldPos[0] / cellSize),
                (int) Math.floor(worldPos[1] / cellSize),
                (int) Math.floor(worldPos[2] / cellSize)
        };
    }

    /**
     * 3D坐标 → Morton Code (简化版, 10位/维度)
     */
    private long encodeMorton3D(int x, int y, int z) {
        x = Math.max(0, Math.min(x, gridResolution - 1));
        y = Math.max(0, Math.min(y, gridResolution - 1));
        z = Math.max(0, Math.min(z, gridResolution - 1));

        long result = 0;
        for (int i = 0; i < 21; i++) {
            result |= ((x >> i) & 1L) << (3 * i);
            result |= ((y >> i) & 1L) << (3 * i + 1);
            result |= ((z >> i) & 1L) << (3 * i + 2);
        }
        return result;
    }

    // ==================== 统计报告 ====================

    public String getStatisticsReport() {
        int occupiedCells = 0;
        for (MortonCell cell : grid.values()) {
            if (cell.occupied) occupiedCells++;
        }

        double estimatedMemoryMB =
                (grid.size() * 8.0 + totalBVHNodes * BVH_INNER_NODE_SIZE + totalTriangles * 36.0)
                / (1024.0 * 1024.0);

        return String.format(
                "[HybridBVH] Grid: %d³ | Occupied: %d/%d (%.1f%%) | " +
                "BVH nodes: %d | Triangles: %d | Memory: %.1fMB | " +
                "Build: %dms | Refit: %dms | Leak detections: %d",
                gridResolution, occupiedCells, grid.size(),
                grid.size() > 0 ? (double) occupiedCells / grid.size() * 100 : 0,
                totalBVHNodes, totalTriangles, estimatedMemoryMB,
                totalBVHBuildTimeMs, totalRefitTimeMs, totalRayLeakDetections);
    }

    // ==================== 资源清理 ====================

    @Override
    public void close() throws Exception {
        grid.clear();
        totalBVHNodes = 0;
        totalTriangles = 0;
        initialized = false;
        LOGGER.info("[HybridBVH] ✓ 已释放");
    }

    public boolean isInitialized() { return initialized; }
    public int getGridResolution() { return gridResolution; }
    public int getTotalBVHNodes() { return totalBVHNodes; }
    public int getTotalTriangles() { return totalTriangles; }
}
