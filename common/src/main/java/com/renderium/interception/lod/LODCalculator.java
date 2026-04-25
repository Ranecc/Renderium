// Renderium - Blaze3D 拦截层系统 Phase 3: LOD 多细节层次系统
// LODCalculator.java - 核心 LOD 计算器
// 功能: 根据距离、视角计算 LOD 等级，支持平滑过渡

package com.renderium.interception.lod;

import com.renderium.data.RealDataProvider;
import com.renderium.data.MockMinecraft;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * LOD（Level of Detail，多细节层次）核心计算器。
 *
 * <p>负责根据相机到区块/实体的距离动态分配 LOD 等级，
 * 支持视角依赖的细节调整和平滑过渡算法。
 *
 * <h2>LOD 等级定义：</h2>
 * <pre>
 * ┌───────┬──────────┬────────────┬────────────┬──────────────────┐
 * │ Level │ 名称     │ 距离范围   │ 多边形比例 │ 用途              │
 * ├───────┼──────────┼────────────┼────────────┼──────────────────┤
 * │ 0     │ FULL     │ &lt;32 blocks │ 100%       │ 近距离完整细节    │
 * │ 1     │ HIGH     │ 32-64 blocks│ 50%        │ 中近距离          │
 * │ 2     │ MEDIUM   │ 64-128 blocks│ 25%       │ 中远距离          │
 * │ 3     │ LOW      │ &gt;128 blocks│ 12.5%      │ 远距离极简        │
 * └───────┴──────────┴────────────┴────────────┴──────────────────┘
 * </pre>
 *
 * <h2>视角依赖 LOD 算法：</h2>
 * <pre>
 * 视角调整公式:
 * adjustment = 1.0 - (angleFromCenter / maxAngle) * falloffFactor
 *
 * 其中:
 * - angleFromCenter: 与视线中心的夹角（弧度）
 * - maxAngle: 最大有效角度（通常 π/3 = 60°）
 * - falloffFactor: 衰减因子（默认 0.5）
 *
 * 效果:
 * - 中心区域 (0-30°): 完整细节 (adjustment ≈ 1.0)
 * - 边缘区域 (30-60°): 降低细节 (adjustment ≈ 0.5-1.0)
 * - 外围区域 (&gt;60°): 最低细节 (adjustment ≈ 0.5)
 * </pre>
 *
 * <h2>性能预算：</h2>
 * <ul>
 *   <li>单次 calculateLODLevel 调用: O(1)，&lt; 0.01ms</li>
 *   <li>无内存分配（纯计算）</li>
 *   <li>线程安全：只读操作，无锁竞争</li>
 * </ul>
 *
 * @see LODTransitionHandler
 * @see LODDataManager
 * @see RenderiumLODSystem
 * @author Renderium Team
 * @since 5.3.0
 */
public final class LODCalculator {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(LODCalculator.class.getName());

    // ==================== 常量定义：LOD 等级 ====================

    /**
     * LOD 等级 0：完整细节（FULL_DETAIL）
     * <p>距离 &lt; 32 区块，100% 多边形渲染
     */
    public static final int LOD_FULL_DETAIL = 0;

    /**
     * LOD 等级 1：高细节（HIGH）
     * <p>距离 32-64 区块，50% 多边形渲染
     */
    public static final int LOD_HIGH = 1;

    /**
     * LOD 等级 2：中等细节（MEDIUM）
     * <p>距离 64-128 区块，25% 多边形渲染
     */
    public static final int LOD_MEDIUM = 2;

    /**
     * LOD 等级 3：低细节（LOW）
     * <p>距离 &gt; 128 区块，12.5% 多边形渲染
     */
    public static final int LOD_LOW = 3;

    /** 默认最大 LOD 级别数 */
    private static final int DEFAULT_MAX_LEVELS = 4;

    /** 默认距离阈值（区块数）：[32, 64, 128] */
    private static final double[] DEFAULT_THRESHOLDS = {32.0, 64.0, 128.0};

    /**
     * v6: 超视距距离阈值（支持最大 1024 chunks = 16384 blocks）
     * <p>用于 RealDataProvider 集成后的超远距离 LOD 计算
     */
    public static final float[] ULTRA_DISTANCE_THRESHOLDS = {
        32f,     // LOD0: <32 blocks (近景)
        64f,     // LOD1: 32-64 (中近)
        128f,    // LOD2: 64-128 (中远)
        256f,    // LOD3: 128-256 (远)
        512f,    // LOD4: 256-512 (极远)
        1024f,   // LOD5: 512-1024 (超远)
        2048f,   // LOD6: 1024-2048 (极限)
        Float.MAX_VALUE  // LOD7: >2048 (地平线)
    };

    /** 默认最大有效角度（弧度）：π/3 ≈ 60°（预计算常量） */
    private static final double DEFAULT_MAX_ANGLE = 1.0471975511965976;

    /** 默认衰减因子 */
    private static final double DEFAULT_FALLOFF_FACTOR = 0.5;

    // ==================== 单例实例 ====================

    /** 全局唯一实例 */
    private static final LODCalculator INSTANCE = new LODCalculator();

    /**
     * 获取 LODCalculator 单例实例
     *
     * @return 全局唯一的 LODCalculator 实例
     */
    public static LODCalculator getInstance() {
        return INSTANCE;
    }

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

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数 - 初始化默认配置
     */
    private LODCalculator() {
        this.distanceThresholds = DEFAULT_THRESHOLDS.clone();
        this.maxLevels = DEFAULT_MAX_LEVELS;
        this.maxAngle = DEFAULT_MAX_ANGLE;
        this.falloffFactor = DEFAULT_FALLOFF_FACTOR;
        this.lodScaleFactors = precomputeScaleFactors(DEFAULT_MAX_LEVELS);
    }

    // ==================== 核心 API：LOD 计算 ====================

    /**
     * 根据距离和 FOV 计算 LOD 等级。
     *
     * <h3>算法流程：</h3>
     * <pre>
     * 1. 将距离与配置的距离阈值数组进行比较
     * 2. 找到第一个超过阈值的索引 → 对应 LOD 等级
     * 3. 如果所有阈值都未超过 → 返回最高 LOD 等级（最远/最低细节）
     * </pre>
     *
     * <p><b>v6 增强：</b>此方法现在支持从 RealDataProvider 获取真实距离数据，
     * 通过 {@link #calculateLODLevelFromProvider(RealDataProvider, int, int)} 方法实现。
     * 当使用 RealDataProvider 时，将自动使用 ULTRA_DISTANCE_THRESHOLDS 支持超视距（最大 1024 chunks）。
     *
     * <h3>时间复杂度：</h3>O(n)，其中 n = thresholds.length（通常为 3-4）
     *
     * @param distance 到相机/观察点的距离（区块数，必须 >= 0）
     * @param fov      当前视野角度（度数，用于未来扩展的透视校正）
     * @return LOD 等级 [0, maxLevels-1]，数值越大表示越远的距离/越低的细节
     * @throws IllegalArgumentException 如果 distance 为负数
     */
    // ==================== GPU优化：平方距离比较方法 ====================

    /**
     * 从世界坐标平方距离计算LOD等级（GPU友好，无sqrt）。
     *
     * <p>等价于 {@code calculateLODLevel(sqrt(distSqWorld) >> 4, fov)}，
     * 但通过平方距离比较避免昂贵的sqrt运算。
     *
     * <p>数学等价性：{@code sqrt(d)/16 < t} ⟺ {@code d < (t*16)²}
     *
     * @param distSqWorld 世界坐标下的平方距离（dx² + dy² + dz²）
     * @param fov         视野角度（度数，保留用于未来扩展）
     * @return LOD 等级 [0, maxLevels-1]
     */
    private int calculateLODLevelFromDistSq(double distSqWorld, double fov) {
        if (distSqWorld < 0) {
            LOGGER.warning("接收到负数平方距离，钳位到 0: " + distSqWorld);
            distSqWorld = 0;
        }

        double[] thresholds = this.distanceThresholds;
        int levels = this.maxLevels;

        // 将阈值（区块单位）转换为世界坐标平方距离阈值
        // distanceBlocks < thresholds[i] ⟺ distSqWorld < (thresholds[i] * 16.0)²
        for (int i = 0; i < thresholds.length; i++) {
            double thresholdWorld = thresholds[i] * 16.0;
            if (distSqWorld < thresholdWorld * thresholdWorld) {
                return i;
            }
        }

        return Math.min(levels - 1, thresholds.length);
    }

    /**
     * 从世界坐标平方距离计算超视距LOD等级（GPU友好，无sqrt）。
     *
     * <p>等价于 {@code calculateLODLevelUltra(sqrt(distSqWorld) >> 4)}，
     * 但通过平方距离比较避免昂贵的sqrt运算。
     *
     * @param distSqWorld 世界坐标下的平方距离（dx² + dy² + dz²）
     * @return LOD 等级 [0, 7]
     */
    private int calculateLODLevelUltraFromDistSq(double distSqWorld) {
        if (distSqWorld < 0) {
            LOGGER.warning("接收到负数平方距离，钳位到 0: " + distSqWorld);
            distSqWorld = 0;
        }

        float[] thresholds = ULTRA_DISTANCE_THRESHOLDS;

        for (int i = 0; i < thresholds.length; i++) {
            double thresholdWorld = (double) thresholds[i] * 16.0;
            if (distSqWorld < thresholdWorld * thresholdWorld) {
                return i;
            }
        }

        return thresholds.length - 1;
    }

    /**
     * 从世界坐标平方距离计算LOD等级（公开API，供外部调用者使用）。
     *
     * <p>等价于 {@code calculateLODLevel(sqrt(distSqWorld) >> 4, fov)}，
     * 但通过平方距离比较避免昂贵的sqrt运算。
     *
     * @param distSqWorld 世界坐标下的平方距离（dx² + dy² + dz²）
     * @param fov         视野角度（度数）
     * @return LOD 等级 [0, maxLevels-1]
     */
    public int calculateLODLevelFromWorldDistSq(double distSqWorld, double fov) {
        return calculateLODLevelFromDistSq(distSqWorld, fov);
    }

    public int calculateLODLevel(double distance, double fov) {
        if (distance < 0) {
            LOGGER.warning("接收到负数距离，钳位到 0: " + distance);
            distance = 0;
        }

        double[] thresholds = this.distanceThresholds;
        int levels = this.maxLevels;

        // 遍历阈值数组，找到对应的 LOD 等级
        for (int i = 0; i < thresholds.length; i++) {
            if (distance < thresholds[i]) {
                return i; // 在第 i 个阈值内 → LOD 等级 = i
            }
        }

        // 超过所有阈值 → 返回最高（最低细节）等级
        return Math.min(levels - 1, thresholds.length);
    }

    /**
     * 根据 chunk 坐标和相机状态计算 LOD 等级（便捷方法）。
     *
     * <p>自动计算欧几里得距离后调用 {@link #calculateLODLevel(double, double)}。
     *
     * @param chunkX    区块 X 坐标
     * @param chunkZ    区块 Z 坐标
     * @param cameraX   相机 X 坐标（世界空间）
     * @param cameraZ   相机 Z 坐标（世界空间）
     * @param fov       视野角度（度数）
     * @return LOD 等级 [0, maxLevels-1]
     */
    public int calculateChunkLOD(int chunkX, int chunkZ,
                                  double cameraX, double cameraZ, double fov) {
        // 将 chunk 坐标转换为世界坐标（1 chunk = 16 blocks）
        double worldX = chunkX * 16.0 + 8.0; // chunk 中心点
        double worldZ = chunkZ * 16.0 + 8.0;

        // GPU优化：使用平方距离比较，避免昂贵的sqrt运算
        double dx = worldX - cameraX;
        double dz = worldZ - cameraZ;
        double distSq = dx * dx + dz * dz;
        return calculateLODLevelFromDistSq(distSq, fov);
    }

    // ==================== v6: RealDataProvider 集成 API ====================

    /**
     * 从 RealDataProvider 计算真实区块的 LOD 等级。
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - provider: 数据提供者实例（必须已初始化且 isReady() == true）
     *              类型: com.renderium.data.RealDataProvider
     *   - chunkX:   目标区块的 X 坐标（区块坐标系，非世界坐标）
     *              类型: int
     *   - chunkZ:   目标区块的 Z 坐标（区块坐标系，非世界坐标）
     *              类型: int
     *
     * 返回值：
     *   - int: LOD 等级 [0, 7+]
     *     * 使用 ULTRA_DISTANCE_THRESHOLDS 进行计算
     *     * 支持最大 1024 chunks (16384 blocks) 的超视距范围
     *     * 如果 provider 未就绪返回 LOD_FULL_DETAIL (0)
     *
     * 性能说明：
     *   - 典型耗时: &lt; 0.01ms（缓存命中时）
     *   - 首次调用可能触发反射: ~0.5ms
     *   - 无内存分配（纯计算）
     *
     * 使用示例：
     *   RealDataProvider provider = RealDataProvider.getInstance();
     *   if (provider.isReady()) {
     *       int lod = calculator.calculateLODLevelFromProvider(provider, chunkX, chunkZ);
     *       // lod 范围: 0 (FULL) ~ 7+ (HORIZON)
     *   }
     * </pre>
     *
     * <h3>算法流程：</h3>
     * <ol>
     *   <li>从 provider 获取真实相机位置（Vec3）</li>
     *   <li>将区块坐标转换为世界坐标（chunk × 16 + 8）</li>
     *   <li>计算欧几里得距离并转换为区块单位</li>
     *   <li>使用 ULTRA_DISTANCE_THRESHOLDS 进行 8 级 LOD 判定</li>
     * </ol>
     *
     * @param provider 数据提供者（必须已初始化，不能为 null）
     * @param chunkX   区块 X 坐标
     * @param chunkZ   区块 Z 坐标
     * @return LOD 等级 [0, 7+]，使用超视距阈值计算
     * @throws IllegalArgumentException 如果 provider 为 null
     */
    public int calculateLODLevelFromProvider(RealDataProvider provider, int chunkX, int chunkZ) {
        // ======== 参数校验 ========
        if (provider == null) {
            throw new IllegalArgumentException("RealDataProvider 不能为 null");
        }

        // ======== 检查 provider 就绪状态 ========
        if (!provider.isReady()) {
            LOGGER.warning("RealDataProvider 未就绪，返回默认 LOD_FULL_DETAIL");
            return LOD_FULL_DETAIL;
        }

        // ======== 获取真实相机位置 ========
        MockMinecraft.Vec3 cameraPos = provider.getCameraPosition();
        if (cameraPos == null) {
            LOGGER.warning("无法获取相机位置，使用默认 LOD_FULL_DETAIL");
            return LOD_FULL_DETAIL;
        }

        // ======== 将区块坐标转换为世界坐标 ========
        // 区块中心点 = chunkPos * 16 + 8（每个区块 16x16 方块）
        double worldX = chunkX * 16.0 + 8.0;
        double worldZ = chunkZ * 16.0 + 8.0;

        // GPU优化：使用平方距离比较，避免昂贵的sqrt运算
        double dx = worldX - cameraPos.x;
        double dy = 0.0;  // 暂不考虑 Y 方向（可扩展）
        double dz = worldZ - cameraPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        return calculateLODLevelUltraFromDistSq(distSq);
    }

    /**
     * 使用超视距阈值数组计算 LOD 等级（v6 新增）。
     *
     * <p>支持最大 1024 chunks (16384 blocks) 的超远距离渲染，
     * 使用 {@link #ULTRA_DISTANCE_THRESHOLDS} 进行 8 级 LOD 判定。
     *
     * <h3>LOD 等级定义（超视距模式）：</h3>
     * <pre>
     * ┌───────┬──────────┬─────────────────────┬────────────┐
     * │ Level │ 名称     │ 距离范围 (blocks)   │ 多边形比例  │
     * ├───────┼──────────┼─────────────────────┼────────────┤
     * │ 0     │ FULL     │ &lt;32                │ 100%       │
     * │ 1     │ HIGH     │ 32-64               │ 50%        │
     * │ 2     │ MEDIUM   │ 64-128              │ 25%        │
     * │ 3     │ LOW      │ 128-256             │ 12.5%      │
     * │ 4     │ FAR      │ 256-512             │ 6.25%      │
     * │ 5     │ EXTREME  │ 512-1024            │ 3.125%     │
     * │ 6     │ LIMIT    │ 1024-2048           │ 1.56%      │
     * │ 7     │ HORIZON  │ &gt;2048              │ &lt;1%       │
     * └───────┴──────────┴─────────────────────┴────────────┘
     * </pre>
     *
     * @param distanceBlocks 距离（区块数，必须 >= 0）
     * @return LOD 等级 [0, 7]，数值越大表示越远的距离/越低的细节
     */
    public int calculateLODLevelUltra(double distanceBlocks) {
        if (distanceBlocks < 0) {
            LOGGER.warning("接收到负数距离，钳位到 0: " + distanceBlocks);
            distanceBlocks = 0;
        }

        // 使用超视距阈值数组（8 级 LOD）
        float[] thresholds = ULTRA_DISTANCE_THRESHOLDS;

        for (int i = 0; i < thresholds.length; i++) {
            if (distanceBlocks < thresholds[i]) {
                return i;
            }
        }

        // 超过所有阈值 → 返回最高等级（地平线）
        return thresholds.length - 1;
    }

    // ==================== 核心 API：缩放因子 ====================

    /**
     * 获取指定 LOD 等级的缩放因子。
     *
     * <p>缩放因子用于控制该级别下的几何体精度：
     * <ul>
     *   <li>LOD 0 → 1.0 （100%，完整精度）</li>
     *   <li>LOD 1 → 0.5 （50%）</li>
     *   <li>LOD 2 → 0.25 （25%）</li>
     *   <li>LOD 3 → 0.125 （12.5%）</li>
     * </ul>
     *
     * <p>公式：scaleFactor = 1.0 / (2^lodLevel)
     *
     * @param lodLevel LOD 等级 [0, maxLevels-1]
     * @return 缩放因子 (0.0, 1.0]，等级越高值越小
     * @throws IllegalArgumentException 如果 lodLevel 超出范围
     */
    public float getLODScaleFactor(int lodLevel) {
        if (lodLevel < 0 || lodLevel >= maxLevels) {
            throw new IllegalArgumentException(
                "LOD 等级超出范围 [" + 0 + ", " + (maxLevels - 1) + "]: " + lodLevel
            );
        }
        return lodScaleFactors[lodLevel];
    }

    // ==================== 核心 API：过渡 Alpha ====================

    /**
     * 计算两个相邻 LOD 等级之间的过渡混合系数（Alpha）。
     *
     * <p>用于 dithering 或 crossfade 过渡效果：
     * <ul>
     *   <li>Alpha ≈ 0.0：主要使用 currentLOD</li>
     *   <li>Alpha ≈ 1.0：主要使用 targetLOD</li>
     *   <li>Alpha = 0.5：两个 LOD 各占 50%</li>
     * </ul>
     *
     * <h3>算法原理（线性插值）：</h3>
     * <pre>
     * alpha = (distance - threshold[current]) / (threshold[current+1] - threshold[current])
     * </pre>
     *
     * @param currentLOD 当前 LOD 等级
     * @param targetLOD  目标 LOD 等级（通常 = currentLOD + 1）
     * @param distance   当前距离（区块数）
     * @return 混合系数 [0.0, 1.0]，如果不在过渡区间则返回边界值
     */
    public double getTransitionAlpha(int currentLOD, int targetLOD, double distance) {
        double[] thresholds = this.distanceThresholds;

        // 参数校验
        if (currentLOD < 0 || currentLOD >= thresholds.length) {
            return 0.0; // 无效的当前等级，不进行过渡
        }
        if (targetLOD != currentLOD + 1) {
            return targetLOD > currentLOD ? 1.0 : 0.0; // 非相邻等级，直接切换
        }

        // 获取当前等级的上下界
        double lowerBound = (currentLOD == 0) ? 0.0 : thresholds[currentLOD - 1];
        double upperBound = thresholds[currentLOD];

        // 计算在过渡区间的位置比例
        double range = upperBound - lowerBound;
        if (range <= 0) {
            return 0.0; // 零宽度区间
        }

        double alpha = (distance - lowerBound) / range;

        // Clamp 到 [0, 1]
        return Math.max(0.0, Math.min(1.0, alpha));
    }

    // ==================== 核心 API：视角依赖调整 ====================

    /**
     * 计算视角依赖的 LOD 调整权重。
     *
     * <h3>算法公式：</h3>
     * <pre>
     * adjustment = 1.0 - (angleFromCenter / maxAngle) * falloffFactor
     *
     * 其中 angleFromCenter 是与视线中心的夹角（弧度），
     * maxAngle 是最大有效角度（通常 π/3），
     * falloffFactor 控制衰减强度。
     * </pre>
     *
     * <h3>效果说明：</h3>
     * <ul>
     *   <li>中心区域 (0°-30°): adjustment ≈ 1.0（完整细节）</li>
     *   <li>边缘区域 (30°-60°): adjustment ≈ 0.5~1.0（降低细节）</li>
     *   <li>外围区域 (&gt;60°): adjustment ≈ 0.5（最低细节）</li>
     * </ul>
     *
     * @param angleFromCenter 与视线的夹角（弧度，0 = 正对相机方向）
     * @return 调整权重 [falloffFactor 的最小值, 1.0]，用于乘以原始 LOD 决策
     */
    public double getViewAngleAdjustment(double angleFromCenter) {
        // 取绝对值（左右对称）
        double absAngle = Math.abs(angleFromCenter);

        // 如果超出最大角度，直接返回最小权重
        if (absAngle >= maxAngle) {
            return 1.0 - falloffFactor;
        }

        // 线性插值计算调整权重
        double normalizedAngle = absAngle / maxAngle;
        return 1.0 - normalizedAngle * falloffFactor;
    }

    /**
     * 综合版 LOD 计算：结合距离和视角因素。
     *
     * <p>先根据距离计算出基础 LOD 等级，
     * 再根据视角调整因子决定是否提升一个等级（降低细节）。
     *
     * @param distance         距离（区块数）
     * @param fov              视野角度（度数）
     * @param angleFromCenter  与视线中心的夹角（弧度）
     * @return 最终 LOD 等级 [0, maxLevels-1]
     */
    public int calculateLODLevelWithViewAdjustment(double distance, double fov,
                                                    double angleFromCenter) {
        // Step 1: 基于距离的基础 LOD 计算
        int baseLOD = calculateLODLevel(distance, fov);

        // Step 2: 获取视角调整权重
        double adjustment = getViewAngleAdjustment(angleFromCenter);

        // Step 3: 如果调整权重低于阈值，提升 LOD 等级（降低细节）
        // 阈值设为 0.75，即当 adjustment &lt; 0.75 时提升一级
        if (adjustment < 0.75 && baseLOD < maxLevels - 1) {
            return baseLOD + 1;
        }

        return baseLOD;
    }

    // ==================== 配置 API ====================

    /**
     * 设置自定义距离阈值数组。
     *
     * <p>阈值数组必须严格递增（升序排列），
     * 长度决定了 LOD 等级数减一（n 个阈值 → n+1 个等级）。
     *
     * @param thresholds 距离阈值数组（区块数，升序，每个元素 > 0）
     * @throws IllegalArgumentException 如果数组为空、非递增或包含非正数
     */
    public void setDistanceThresholds(double[] thresholds) {
        if (thresholds == null || thresholds.length == 0) {
            throw new IllegalArgumentException("距离阈值数组不能为空");
        }
        if (thresholds.length > 16) {
            throw new IllegalArgumentException("距离阈值数量不能超过 16: " + thresholds.length);
        }

        // 验证严格递增且全为正数
        for (int i = 0; i < thresholds.length; i++) {
            if (thresholds[i] <= 0) {
                throw new IllegalArgumentException("距离阈值必须为正数: thresholds[" + i + "]=" + thresholds[i]);
            }
            if (i > 0 && thresholds[i] <= thresholds[i - 1]) {
                throw new IllegalArgumentException(
                    "距离阈值必须严格递升: thresholds[" + (i - 1) + "]=" +
                    thresholds[i - 1] + " >= thresholds[" + i + "]=" + thresholds[i]
                );
            }
        }

        this.distanceThresholds = thresholds.clone();
        this.maxLevels = thresholds.length + 1;
        this.lodScaleFactors = precomputeScaleFactors(this.maxLevels);

        LOGGER.info(String.format(
            "LOD 距离阈值已更新: %s (%d 级)",
            Arrays.toString(thresholds), this.maxLevels
        ));
    }

    /**
     * 设置最大 LOD 级别数。
     *
     * <p>注意：此方法会基于默认阈值自动重新生成阈值数组。
     * 如需精确控制阈值，请使用 {@link #setDistanceThresholds(double[])}。
     *
     * @param maxLevels 最大级别数（必须 >= 2 且 <= 16）
     * @throws IllegalArgumentException 如果 maxLevels 不在合法范围内
     */
    public void setMaxLevels(int maxLevels) {
        if (maxLevels < 2 || maxLevels > 16) {
            throw new IllegalArgumentException("最大 LOD 级别数必须在 [2, 16] 范围内: " + maxLevels);
        }

        // 基于默认比例生成新的阈值数组
        // 使用等比数列：32, 64, 128, 256, ...
        double[] newThresholds = new double[maxLevels - 1];
        double base = 32.0;
        for (int i = 0; i < maxLevels - 1; i++) {
            // GPU优化：使用位移运算替代Math.pow，2^i == 1 << i
            newThresholds[i] = base * (1 << i);
        }

        this.distanceThresholds = newThresholds;
        this.maxLevels = maxLevels;
        this.lodScaleFactors = precomputeScaleFactors(maxLevels);

        LOGGER.info(String.format(
            "LOD 最大级别数已更新: %d, 阈值: %s",
            maxLevels, Arrays.toString(newThresholds)
        ));
    }

    /**
     * 设置视角依赖参数。
     *
     * @param maxAngle      最大有效角度（弧度，推荐 π/3）
     * @param falloffFactor 衰减因子 [0.0, 1.0]，控制边缘区域细节下降速度
     * @throws IllegalArgumentException 如果参数不合法
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

        LOGGER.fine(String.format(
            "视角参数已更新: maxAngle=%.2f°, falloffFactor=%.2f",
            Math.toDegrees(maxAngle), falloffFactor
        ));
    }

    // ==================== 查询 API ====================

    /**
     * 获取当前配置的距离阈值数组副本。
     *
     * @return 距离阈值数组（区块数，升序）
     */
    public double[] getDistanceThresholds() {
        return distanceThresholds.clone();
    }

    /**
     * 获取当前最大 LOD 级别数。
     *
     * @return 最大级别数（含等级 0）
     */
    public int getMaxLevels() {
        return maxLevels;
    }

    /**
     * 获取当前最大有效角度（弧度）。
     *
     * @return 最大角度（弧度）
     */
    public double getMaxAngle() {
        return maxAngle;
    }

    /**
     * 获取当前衰减因子。
     *
     * @return 衰减因子 [0.0, 1.0]
     */
    public double getFalloffFactor() {
        return falloffFactor;
    }

    /**
     * 获取指定距离对应的最大 LOD 等级名称。
     *
     * @param distance 距离（区块数）
     * @param fov      视野角度（度数）
     * @return LOD 等级名称字符串
     */
    public String getLODLevelName(double distance, double fov) {
        int level = calculateLODLevel(distance, fov);
        return switch (level) {
            case LOD_FULL_DETAIL -> "FULL";
            case LOD_HIGH -> "HIGH";
            case LOD_MEDIUM -> "MEDIUM";
            case LOD_LOW -> "LOW";
            default -> "UNKNOWN_" + level;
        };
    }

    // ==================== 内部工具方法 ====================

    /**
     * 预计算各 LOD 等级的缩放因子表。
     *
     * <p>公式：scaleFactor[lod] = 1.0 / (2^lod)
     *
     * @param count 级别数量
     * @return 缓存后的缩放因子数组
     */
    private static float[] precomputeScaleFactors(int count) {
        float[] factors = new float[count];
        for (int i = 0; i < count; i++) {
            factors[i] = 1.0f / (float) (1 << i); // 2^i 的倒数
        }
        return factors;
    }

    /**
     * 重置为默认配置。
     */
    public void resetToDefaults() {
        this.distanceThresholds = DEFAULT_THRESHOLDS.clone();
        this.maxLevels = DEFAULT_MAX_LEVELS;
        this.maxAngle = DEFAULT_MAX_ANGLE;
        this.falloffFactor = DEFAULT_FALLOFF_FACTOR;
        this.lodScaleFactors = precomputeScaleFactors(DEFAULT_MAX_LEVELS);

        LOGGER.info("LODCalculator 已重置为默认配置");
    }
}
