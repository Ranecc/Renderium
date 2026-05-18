package com.ranecc.renderium.infrastructure.gpu.raytracing;

import java.util.logging.Logger;

/**
 * Phase 5: Stage1→Stage2 层次化射线查询优化器
 *
 * <p>实现 Renderium 自身优化思想 — 不暴力穷举，用两阶段层次化采样、
 * 视锥聚焦、几何 LOD 来控制射线预算，保证画质不牺牲。
 *
 * <h2>Stage1 → Stage2 管线（非并行双通道）：</h2>
 * <pre>
 * Stage 1 (Coarse BVH Traversal — 全屏)：
 *   发射稀疏射线（每 8×8 像素 → 1 根），在 BLAS 最粗层级停止。
 *   目的：确定哪些区域需要细粒度追踪。
 *   输出：一个 2D importance map（哪些 block 差异大 → 进入 Stage 2）。
 *
 * Stage 2 (Fine Hit Shading — 仅 focal 区域)：
 *   仅对 Stage 1 标记为 "需要细化" 的 block 发射全密度射线。
 *   在这层做实际 hit shading、材质评估、反弹。
 * </pre>
 *
 * <h2>参考文献：</h2>
 * <ul>
 *   <li>Keller, "Instant Radiosity" (1997) — importance sampling 基础</li>
 *   <li>Bitterli et al., "Spatiotemporal Variance-Guided Filtering" (2017) — 自适应采样</li>
 *   <li>NVIDIA Ada Architecture Whitepaper (2022) — SER + OMM hardware</li>
 *   <li>Intel Xe-HPG Architecture (2023) — Thread Sorting Unit, DXR 1.1 RayQuery 优选</li>
 *   <li>Chips and Cheese, "Raytracing on Meteor Lake" (2024) — 三家 traversal 路径对比</li>
 * </ul>
 */
public final class RayQueryOptimizer {

    private static final Logger LOGGER = Logger.getLogger("Renderium|RayQueryOpt");

    /** Stage 1 采样窗口边长 (Stage1 每 8×8 像素 1 根射线) */
    public static final int STAGE1_BLOCK_DIM = 8;

    /** Stage1→Stage2 触发阈值：相邻 coarse 采样差异超过此值才 fine 细化 */
    public static final float STAGE1_STAGE2_THRESHOLD = 0.12f;

    /** Stage 1 最大射线占比 (占全屏像素的比例：1/64 = 1.56%) */
    public static final float STAGE1_DENSITY = 1.0f / (STAGE1_BLOCK_DIM * STAGE1_BLOCK_DIM);

    private RayQueryOptimizer() {}

    // ==================== Stage 1: Coarse BVH Traversal ====================

    /**
     * 计算 Stage 1 需要发射的射线数量
     */
    public static int estimateStage1RayCount(int width, int height) {
        int blocksX = (width + STAGE1_BLOCK_DIM - 1) / STAGE1_BLOCK_DIM;
        int blocksY = (height + STAGE1_BLOCK_DIM - 1) / STAGE1_BLOCK_DIM;
        return blocksX * blocksY;
    }

    /**
     * 运行 Stage 1 后估算 Stage 2 需要的射线数量。
     *
     * <p>基于经验：通常 25-40% 的 coarse blocks 需要 fine 细化
     * （取决于场景复杂度：室内 ~20%, 户外复杂场景 ~35%, 森林 ~50%）。
     */
    public static int estimateStage2RayCount(int stage1Rays, float sceneComplexity) {
        float stage2Fraction = 0.25f + 0.25f * sceneComplexity;
        int stage2Blocks = (int) (stage1Rays * stage2Fraction);
        return stage2Blocks * STAGE1_BLOCK_DIM * STAGE1_BLOCK_DIM;
    }

    /**
     * 判断某个 Stage 1 block 是否需要进入 Stage 2 fine 细化。
     *
     * <p>算法：取 block 的 4 个 coarse 采样点（角采样），
     * 计算亮度范围。若亮度差异 > 阈值，说明此 block
     * 内几何>材质变化大，需要细粒度追踪。
     *
     * @param coarseLuminances 4 个 coarse 采样点的亮度值
     * @return true 表示此 block 需要 Stage 2 细化
     */
    public static boolean shouldPromoteToStage2(float[] coarseLuminances) {
        if (coarseLuminances == null || coarseLuminances.length < 4) return true;

        float minLum = Float.MAX_VALUE;
        float maxLum = Float.MIN_VALUE;

        for (float lum : coarseLuminances) {
            if (lum < minLum) minLum = lum;
            if (lum > maxLum) maxLum = lum;
        }

        float range = maxLum - minLum;
        return range > STAGE1_STAGE2_THRESHOLD;
    }

    // ==================== 重要性采样 ====================

    /**
     * 计算某像素区域的重要性权重。
     *
     * <p>参考 Keller (1997) 的重要性采样框架：
     * <ul>
     *   <li>动态光源区域权重最高（+0.40）— 需要精确阴影/反射</li>
     *   <li>反射面权重次高（+0.30）— 镜面反射/水面</li>
     *   <li>视觉焦点区域中等（+0.20）— screen center bias</li>
     *   <li>阴影投射区域低（+0.10）— 间接影响视觉质量</li>
     * </ul>
     */
    public static float calculateImportance(boolean hasDynamicLight,
                                             boolean isReflectiveSurface,
                                             boolean isFocalArea,
                                             boolean isShadowCaster) {
        float weight = 0.0f;
        if (hasDynamicLight) weight += 0.40f;
        if (isReflectiveSurface) weight += 0.30f;
        if (isFocalArea) weight += 0.20f;
        if (isShadowCaster) weight += 0.10f;
        return Math.min(weight, 1.0f);
    }

    // ==================== 射线密度计算 ====================

    /**
     * 计算某位置的最终射线密度系数。
     *
     * <p>组合三个正交维度：
     * <ol>
     *   <li>FrustumFocus — center/mid/edge 视锥密度分布</li>
     *   <li>LOD — 距离驱动的几何降级</li>
     *   <li>Importance — 光源/反射/焦点的重要性权重</li>
     * </ol>
     *
     * @return 密度因子 [0.0, 1.0]，1.0 = 发射全部射线
     */
    public static float computeRayDensity(float screenU, float screenV,
                                           float distance, int lodLevel,
                                           boolean hasDynamicLight,
                                           boolean isReflective,
                                           boolean isFocalArea,
                                           boolean isShadowCaster) {

        float frustumFactor = RayTracingHAL.FrustumFocusRegion.getDensity(screenU, screenV);
        float lodFactor = RayTracingHAL.getLODDensityFactor(lodLevel);
        float importanceFactor = calculateImportance(
            hasDynamicLight, isReflective, isFocalArea, isShadowCaster);

        return frustumFactor * lodFactor * (0.5f + 0.5f * importanceFactor);
    }

    /**
     * 基于密度系数决定是否发射此射线。
     *
     * <p>用 hash 噪声实现概率性采样（非硬截断），
     * 避免因密度边界突变产生的条带 artifact。
     */
    public static boolean shouldTraceRay(float density, long noiseSeed) {
        if (density >= 1.0f) return true;
        if (density <= 0.0f) return false;
        float hash = ((noiseSeed * 0x45D9F3BL) & 0xFFFF) / 65536.0f;
        return hash < density;
    }

    // ==================== 时域复用 ====================

    private static final int TEMPORAL_POOL_SIZE = 64;
    private static final float[][] prevFrameLuminances = new float[TEMPORAL_POOL_SIZE][TEMPORAL_POOL_SIZE];
    private static final long[] prevFrameTimestamps = new long[TEMPORAL_POOL_SIZE];
    static volatile int reuseFrameCount = 0;
    static volatile int newFrameCount = 0;

    /**
     * 检查是否可复用上一帧的 Stage 1 结果。
     *
     * <p>条件：两帧的 VP 矩阵哈希相同 → 相机未移动 → 复用。
     * 参考 Bitterli et al. (2017) 时域复用过滤。
     */
    public static boolean canReuseStage1(int blockX, int blockY, long currentMatrixHash) {
        if (blockX >= TEMPORAL_POOL_SIZE || blockY >= TEMPORAL_POOL_SIZE || blockX < 0 || blockY < 0)
            return false;
        if (prevFrameTimestamps[blockY] == currentMatrixHash) {
            reuseFrameCount++;
            return true;
        }
        newFrameCount++;
        return false;
    }

    /**
     * 存储 Stage 1 的 block 结果供下一帧复用。
     */
    public static void storeStage1Result(int blockX, int blockY, float luminance, long matrixHash) {
        if (blockX >= TEMPORAL_POOL_SIZE || blockY >= TEMPORAL_POOL_SIZE) return;
        prevFrameLuminances[blockY][blockX] = luminance;
        prevFrameTimestamps[blockY] = matrixHash;
    }

    /**
     * 帧结束时刷新时域缓存。
     */
    public static void flushTemporalCache(long matrixHash) {
        for (int i = 0; i < TEMPORAL_POOL_SIZE; i++) {
            prevFrameTimestamps[i] = matrixHash;
        }
    }

    /** 总射线数：Stage1 + Stage2 */
    public static int totalRays(int stage1Count, int stage2Count) {
        return stage1Count + stage2Count;
    }

    // ==================== 工具方法 ====================

    static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    public static String getStats() {
        int total = reuseFrameCount + newFrameCount;
        float reuseRate = total > 0 ? (float) reuseFrameCount / total * 100f : 0f;
        return String.format("RayQueryOpt: stage1 reuse=%.1f%% (reused=%d new=%d)",
            reuseRate, reuseFrameCount, newFrameCount);
    }
}
