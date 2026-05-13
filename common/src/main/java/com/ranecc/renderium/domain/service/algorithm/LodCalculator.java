package com.ranecc.renderium.domain.service.algorithm;
import com.ranecc.renderium.feature.lod.GPULODDataManager;

import com.ranecc.renderium.domain.model.LodResult;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * LOD（Level of Detail）距离计算领域服务
 * <p>
 * 从 com.renderium.gpu.lod.GPULODDataManager 和
 * com.renderium.interception.lod.LODCalculator 迁移并重构。
 * 提取核心 LOD 距离计算逻辑，移除 GPU/FFI/平台依赖。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>根据距离阈值数组计算 LOD 等级</li>
 *   <li>支持视角依赖的 LOD 调整</li>
 *   <li>支持超视距（8级）LOD 计算</li>
 *   <li>计算 LOD 缩放因子和过渡混合系数</li>
 * </ul>
 *
 * <h3>LOD 等级定义</h3>
 * <pre>
 * ┌───────┬──────────┬────────────┬────────────┐
 * │ Level │ 名称     │ 距离范围   │ 多边形比例 │
 * ├───────┼──────────┼────────────┼────────────┤
 * │ 0     │ FULL     │ &lt;32 blocks │ 100%       │
 * │ 1     │ HIGH     │ 32-64      │ 50%        │
 * │ 2     │ MEDIUM   │ 64-128     │ 25%        │
 * │ 3     │ LOW      │ 128-256    │ 12.5%      │
 * │ 4     │ FAR      │ 256-512    │ 6.25%      │
 * │ 5     │ EXTREME  │ 512-1024   │ 3.125%     │
 * │ 6     │ LIMIT    │ 1024-2048  │ 1.56%      │
 * │ 7     │ HORIZON  │ &gt;2048      │ &lt;1%        │
 * └───────┴──────────┴────────────┴────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * LodCalculator calculator = new LodCalculator();
 * int lodLevel = calculator.calculateLODLevel(48.0, 70.0);
 * float scale = calculator.getLODScaleFactor(lodLevel);
 * }</pre>
 *
 * @see com.ranecc.renderium.domain.model.LodResult
 */
public final class LodCalculator {

    private static final Logger LOGGER = Logger.getLogger(LodCalculator.class.getName());

    // ==================== LOD 等级常量 ====================

    /** LOD 等级 0：完整细节 */
    public static final int LOD_FULL_DETAIL = 0;

    /** LOD 等级 1：高细节 */
    public static final int LOD_HIGH = 1;

    /** LOD 等级 2：中等细节 */
    public static final int LOD_MEDIUM = 2;

    /** LOD 等级 3：低细节 */
    public static final int LOD_LOW = 3;

    /** 最大支持的 LOD 级别数 */
    public static final int MAX_LOD_LEVELS = 8;

    /** 默认距离阈值（区块数）：[32, 64, 128] */
    private static final double[] DEFAULT_THRESHOLDS = {32.0, 64.0, 128.0};

    /** 超视距距离阈值（支持最大 1024 chunks = 16384 blocks） */
    public static final float[] ULTRA_DISTANCE_THRESHOLDS = {
        32f, 64f, 128f, 256f, 512f, 1024f, 2048f, Float.MAX_VALUE
    };

    /** 默认最大有效角度（弧度）：π/3 ≈ 60° */
    private static final double DEFAULT_MAX_ANGLE = Math.PI / 3.0;

    /** 默认衰减因子 */
    private static final double DEFAULT_FALLOFF_FACTOR = 0.5;

    /** Minecraft 区块尺寸（方块数） */
    private static final float CHUNK_BLOCK_SIZE = 16.0f;

    // ==================== 配置字段 ====================

    /** 距离阈值数组（升序排列），长度 = maxLevels - 1 */
    private volatile double[] distanceThresholds;

    /** 最大 LOD 级别数（含等级 0） */
    private volatile int maxLevels;

    /** 视角依赖：最大有效角度（弧度） */
    private volatile double maxAngle;

    /** 视角依赖：衰减因子 [0.0, 1.0] */
    private volatile double falloffFactor;

    /** LOD 缩放因子表（预计算缓存） */
    private volatile float[] lodScaleFactors;

    // ==================== 构造方法 ====================

    /**
     * 创建 LOD 计算器（使用默认配置）
     */
    public LodCalculator() {
        this.distanceThresholds = DEFAULT_THRESHOLDS.clone();
        this.maxLevels = DEFAULT_THRESHOLDS.length + 1;
        this.maxAngle = DEFAULT_MAX_ANGLE;
        this.falloffFactor = DEFAULT_FALLOFF_FACTOR;
        this.lodScaleFactors = precomputeScaleFactors(this.maxLevels);
    }

    /**
     * 创建自定义距离阈值的 LOD 计算器
     *
     * @param thresholds 距离阈值数组（区块数，升序）
     * @throws IllegalArgumentException 若参数无效
     */
    public LodCalculator(double[] thresholds) {
        setDistanceThresholds(thresholds);
        this.maxAngle = DEFAULT_MAX_ANGLE;
        this.falloffFactor = DEFAULT_FALLOFF_FACTOR;
    }

    // ==================== 核心 API ====================

    /**
     * 根据距离和 FOV 计算 LOD 等级
     *
     * @param distance 到相机/观察点的距离（区块数，必须 >= 0）
     * @param fov      当前视野角度（度数）
     * @return LOD 等级 [0, maxLevels-1]
     * @throws IllegalArgumentException 如果 distance 为负数
     */
    public int calculateLODLevel(double distance, double fov) {
        if (distance < 0) {
            throw new IllegalArgumentException("距离不能为负数: " + distance);
        }

        double[] thresholds = this.distanceThresholds;
        for (int i = 0; i < thresholds.length; i++) {
            if (distance < thresholds[i]) {
                return i;
            }
        }
        return Math.min(maxLevels - 1, thresholds.length);
    }

    /**
     * 从世界坐标平方距离计算 LOD 等级（GPU 友好，无 sqrt）
     *
     * @param distSqWorld 世界坐标下的平方距离
     * @param fov         视野角度（度数）
     * @return LOD 等级 [0, maxLevels-1]
     */
    public int calculateLODLevelFromWorldDistSq(double distSqWorld, double fov) {
        if (distSqWorld < 0) {
            distSqWorld = 0;
        }

        double[] thresholds = this.distanceThresholds;
        for (int i = 0; i < thresholds.length; i++) {
            double thresholdWorld = thresholds[i] * CHUNK_BLOCK_SIZE;
            if (distSqWorld < thresholdWorld * thresholdWorld) {
                return i;
            }
        }
        return Math.min(maxLevels - 1, thresholds.length);
    }

    /**
     * 从区块坐标计算 LOD 等级
     *
     * @param chunkX  区块 X 坐标
     * @param chunkZ  区块 Z 坐标
     * @param cameraX 相机 X 坐标（世界空间）
     * @param cameraZ 相机 Z 坐标（世界空间）
     * @param fov     视野角度（度数）
     * @return LOD 等级 [0, maxLevels-1]
     */
    public int calculateChunkLOD(int chunkX, int chunkZ,
                                  double cameraX, double cameraZ, double fov) {
        double worldX = chunkX * CHUNK_BLOCK_SIZE + 8.0;
        double worldZ = chunkZ * CHUNK_BLOCK_SIZE + 8.0;
        double dx = worldX - cameraX;
        double dz = worldZ - cameraZ;
        double distSq = dx * dx + dz * dz;
        return calculateLODLevelFromWorldDistSq(distSq, fov);
    }

    /**
     * 使用超视距阈值数组计算 LOD 等级
     *
     * @param distanceBlocks 距离（区块数，必须 >= 0）
     * @return LOD 等级 [0, 7]
     */
    public int calculateLODLevelUltra(double distanceBlocks) {
        if (distanceBlocks < 0) {
            throw new IllegalArgumentException("距离不能为负数: " + distanceBlocks);
        }

        float[] thresholds = ULTRA_DISTANCE_THRESHOLDS;
        for (int i = 0; i < thresholds.length; i++) {
            if (distanceBlocks < thresholds[i]) {
                return i;
            }
        }
        return thresholds.length - 1;
    }

    /**
     * 计算视角依赖的 LOD 调整权重
     *
     * @param angleFromCenter 与视线的夹角（弧度）
     * @return 调整权重 [falloffFactor 最小值, 1.0]
     */
    public double getViewAngleAdjustment(double angleFromCenter) {
        double absAngle = Math.abs(angleFromCenter);
        if (absAngle >= maxAngle) {
            return 1.0 - falloffFactor;
        }
        double normalizedAngle = absAngle / maxAngle;
        return 1.0 - normalizedAngle * falloffFactor;
    }

    /**
     * 综合版 LOD 计算：结合距离和视角因素
     *
     * @param distance        距离（区块数）
     * @param fov             视野角度（度数）
     * @param angleFromCenter 与视线中心的夹角（弧度）
     * @return 最终 LOD 等级 [0, maxLevels-1]
     */
    public int calculateLODLevelWithViewAdjustment(double distance, double fov,
                                                    double angleFromCenter) {
        int baseLOD = calculateLODLevel(distance, fov);
        double adjustment = getViewAngleAdjustment(angleFromCenter);
        if (adjustment < 0.75 && baseLOD < maxLevels - 1) {
            return baseLOD + 1;
        }
        return baseLOD;
    }

    /**
     * 获取指定 LOD 等级的缩放因子
     *
     * @param lodLevel LOD 等级 [0, maxLevels-1]
     * @return 缩放因子 (0.0, 1.0]
     * @throws IllegalArgumentException 如果 lodLevel 超出范围
     */
    public float getLODScaleFactor(int lodLevel) {
        if (lodLevel < 0 || lodLevel >= maxLevels) {
            throw new IllegalArgumentException(
                "LOD 等级超出范围 [0, " + (maxLevels - 1) + "]: " + lodLevel);
        }
        return lodScaleFactors[lodLevel];
    }

    /**
     * 计算两个相邻 LOD 等级之间的过渡混合系数
     *
     * @param currentLOD 当前 LOD 等级
     * @param targetLOD  目标 LOD 等级
     * @param distance   当前距离（区块数）
     * @return 混合系数 [0.0, 1.0]
     */
    public double getTransitionAlpha(int currentLOD, int targetLOD, double distance) {
        double[] thresholds = this.distanceThresholds;
        if (currentLOD < 0 || currentLOD >= thresholds.length) {
            return 0.0;
        }
        if (targetLOD != currentLOD + 1) {
            return targetLOD > currentLOD ? 1.0 : 0.0;
        }
        double lowerBound = (currentLOD == 0) ? 0.0 : thresholds[currentLOD - 1];
        double upperBound = thresholds[currentLOD];
        double range = upperBound - lowerBound;
        if (range <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, (distance - lowerBound) / range));
    }

    // ==================== 配置 API ====================

    /**
     * 设置自定义距离阈值数组
     *
     * @param thresholds 距离阈值数组（区块数，升序，每个元素 > 0）
     * @throws IllegalArgumentException 若参数无效
     */
    public void setDistanceThresholds(double[] thresholds) {
        if (thresholds == null || thresholds.length == 0) {
            throw new IllegalArgumentException("距离阈值数组不能为空");
        }
        for (int i = 0; i < thresholds.length; i++) {
            if (thresholds[i] <= 0) {
                throw new IllegalArgumentException("距离阈值必须为正数: thresholds[" + i + "]=" + thresholds[i]);
            }
            if (i > 0 && thresholds[i] <= thresholds[i - 1]) {
                throw new IllegalArgumentException(
                    "距离阈值必须严格递升: thresholds[" + (i - 1) + "]=" + thresholds[i - 1]"
                    + " >= thresholds[" + i + "]=" + thresholds[i]);
            }
        }
        this.distanceThresholds = thresholds.clone();
        this.maxLevels = thresholds.length + 1;
        this.lodScaleFactors = precomputeScaleFactors(this.maxLevels);
    }

    /**
     * 设置视角依赖参数
     *
     * @param maxAngle      最大有效角度（弧度）
     * @param falloffFactor 衰减因子 [0.0, 1.0]
     * @throws IllegalArgumentException 若参数不合法
     */
    public void setViewAngleParams(double maxAngle, double falloffFactor) {
        if (maxAngle <= 0 || maxAngle > Math.PI) {
            throw new IllegalArgumentException("最大角度必须在 (0, π] 范围内: " + maxAngle);
        }
        if (falloffFactor < 0.0 || falloffFactor > 1.0) {
            throw new IllegalArgumentException("衰减因子必须在 [0.0, 1.0] 范围内: " + falloffFactor);
        }
        this.maxAngle = maxAngle;
        this.falloffFactor = falloffFactor;
    }

    // ==================== 查询 API ====================

    /** 获取距离阈值数组副本 */
    public double[] getDistanceThresholds() { return distanceThresholds.clone(); }

    /** 获取最大 LOD 级别数 */
    public int getMaxLevels() { return maxLevels; }

    /** 获取最大有效角度（弧度） */
    public double getMaxAngle() { return maxAngle; }

    /** 获取衰减因子 */
    public double getFalloffFactor() { return falloffFactor; }

    /**
     * 重置为默认配置
     */
    public void resetToDefaults() {
        this.distanceThresholds = DEFAULT_THRESHOLDS.clone();
        this.maxLevels = DEFAULT_THRESHOLDS.length + 1;
        this.maxAngle = DEFAULT_MAX_ANGLE;
        this.falloffFactor = DEFAULT_FALLOFF_FACTOR;
        this.lodScaleFactors = precomputeScaleFactors(this.maxLevels);
    }

    // ==================== 内部方法 ====================

    /**
     * 预计算各 LOD 等级的缩放因子表
     * 公式：scaleFactor[lod] = 1.0 / (2^lod)
     */
    private static float[] precomputeScaleFactors(int count) {
        float[] factors = new float[count];
        for (int i = 0; i < count; i++) {
            factors[i] = 1.0f / (float)(1 << i);
        }
        return factors;
    }
}
