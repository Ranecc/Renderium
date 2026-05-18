// Renderium v6 Phase 2 - Voxel-based 超视距 LOD 系统核心架构
// HiZThresholdAnalyzer.java - Hi-Z 深度金字塔阈值与剔除率基准测试
// 功能: 分析不同 Mip 级别选择策略的假阳性/假阴性率


package com.ranecc.renderium.feature.lod.compute;

import java.util.logging.Logger;

/**
 * Hi-Z (Hierarchical Z-Buffer) 深度金字塔阈值与剔除率分析器
 *
 * <p>分析不同 Mip 级别选择策略对遮挡剔除效果的影响。
 * 不依赖实际 GPU，通过模拟 Hi-Z 金字塔查询评估三种策略的质量</p>
 *
 *
 * <h2>三种 Mip 选择策略</h2>
 * <pre>
 * ┌────────────┬──────────────────────────────────────────────┐
 * │ 策略       │ Mip 级别计算公式                             │
 * ├────────────┼──────────────────────────────────────────────┤
 * │ CONSERVATIVE│ ceil(log2(max(bboxW, bboxH)))               │
 * │ (保守)     │ 使用精确匹配 mip，覆盖面积恰好 ≥ 包围盒     │
 * │            │ → 假剔除率: 低      假可见率: 高             │
 * ├────────────┼──────────────────────────────────────────────┤
 * │ BALANCED   │ ceil(log2(max(bboxW, bboxH))) + 1            │
 * │ (平衡)     │ 使用上一个更粗 mip，覆盖更大面积            │
 * │            │ → 假剔除率: 中等    假可见率: 中等           │
 * ├────────────┼──────────────────────────────────────────────┤
 * │ AGGRESSIVE │ ceil(log2(max(bboxW, bboxH))) + 2            │
 * │ (激进)     │ 使用两个更粗 mip，快速但可能过度剔除         │
 * │            │ → 假剔除率: 高      假可见率: 低             │
 * └────────────┴──────────────────────────────────────────────┘
 * </pre>
 *
 *
 * <h2>Unreal Engine Hi-Z 参考参数</h2>
 * <pre>
 * - Mip 0 尺寸: 2^floor(log2(min(width, height)))  — 取最小维度的下取 2 的幂
 * - Reduction:   max（储存最远深度）用于遮挡剔除
 * - 深度格式:    R32_SFLOAT
 * - Float 舍入:  round(depth × 255) / 255 消除精度误差
 * - 批量生成:    一次 Dispatch 处理多个 mip（使用 groupshared + wave）
 * - Morton Z:    重排采样顺序提升缓存局部性
 * </pre>
 *
 *
 * <h2>假剔除率与假可见率</h2>
 * <pre>
 * 假剔除率 (False Occlusion Rate):
 *   本该可见的对象被错误地标记为遮挡。
 *   → 用户看到物体消失（pop out），严重视觉问题。
 *   → 必须为 0%（或无限接近 0%）。
 *
 * 假可见率 (False Visible Rate):
 *   本该被遮挡的对象被错误地标记为可见。
 *   → 多余渲染，但视觉上不可见。
 *   → 影响 GPU 性能但用户不可见。
 *   → 可接受的范围: <10%。
 * </pre>
 *
 *
 * <h2>性能预算</h2>
 * <ul>
 *   <li>单次策略评估: O(n), n = 测试对象数量</li>
 *   <li>无 GPU 依赖，纯 CPU 模拟分析</li>
 * </ul>
 *
 *
 * @see HiZComputePipeline
 * @author Renderium Team
 * @since 7.0.0
 */
public final class HiZThresholdAnalyzer {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(HiZThresholdAnalyzer.class.getName());

    // ==================== 常量定义 ====================

    /** Mip 选择策略 */
    public enum MipStrategy {
        /** 保守策略：无偏移，精确匹配 */
        CONSERVATIVE(0),
        /** 平衡策略：偏移 +1，使用更粗 mip */
        BALANCED(1),
        /** 激进策略：偏移 +2，使用两个更粗 mip */
        AGGRESSIVE(2);

        final int mipOffset;

        MipStrategy(int mipOffset) {
            this.mipOffset = mipOffset;
        }
    }

    /** 最大模拟 Mip 级别 */
    private static final int MAX_MIP_LEVELS = 12;

    /** 屏幕分辨率（用于模拟） */
    private static final int SCREEN_WIDTH = 1920;
    private static final int SCREEN_HEIGHT = 1080;

    /** 单例实例 */
    private static final HiZThresholdAnalyzer INSTANCE = new HiZThresholdAnalyzer();

    /**
     * 获取单例实例
     *
     * @return 全局唯一的 HiZThresholdAnalyzer 实例
     */
    public static HiZThresholdAnalyzer getInstance() {
        return INSTANCE;
    }

    // ==================== 配置字段 ====================

    /** 当前 Mip 选择策略 */
    private volatile MipStrategy currentStrategy = MipStrategy.BALANCED;

    /** 屏幕宽度 */
    private volatile int screenWidth = SCREEN_WIDTH;
    /** 屏幕高度 */
    private volatile int screenHeight = SCREEN_HEIGHT;

    // ==================== 私有构造函数 ====================

    private HiZThresholdAnalyzer() {}

    // ==================== 核心 API：Mip 级别计算 ====================

    /**
     * 根据包围盒在屏幕上的投影大小选择 Hi-Z 金字塔的 Mip 级别
     *
     * <h3>Unreal Engine 公式</h3>
     * <pre>
     * mip = ceil(log2(max(bboxScreenWidth, bboxScreenHeight))) + offset
     *
     * 其中:
     * - bboxScreenWidth/Height: AABB 在屏幕空间的投影尺寸（像素）
     * - offset: 策略偏移量 (CONSERVATIVE=0, BALANCED=1, AGGRESSIVE=2)
     * </pre>
     *
     * <p>边界情况:
     * <ul>
     *   <li>bboxSize=0 → 无面积物体 → 返回 0（逐像素查询）</li>
     *   <li>bboxSize>=MAX_MIP_LEVELS → 使用最粗 mip</li>
     * </ul>
     *
     * @param bboxScreenWidth  AABB 屏幕空间宽度（像素）
     * @param bboxScreenHeight AABB 屏幕空间高度（像素）
     * @param strategy         Mip 选择策略
     * @return Hi-Z Mip 级别 [0, MAX_MIP_LEVELS]
     */
    public int selectMipLevel(float bboxScreenWidth, float bboxScreenHeight,
                               MipStrategy strategy) {
        if (bboxScreenWidth <= 0 || bboxScreenHeight <= 0) {
            return 0;
        }

        float maxDim = Math.max(bboxScreenWidth, bboxScreenHeight);

        double log2Size = Math.log(maxDim) / Math.log(2.0);
        int baseMip = Math.max(0, (int) Math.ceil(log2Size));
        int selectedMip = baseMip + strategy.mipOffset;

        return Math.min(selectedMip, MAX_MIP_LEVELS);
    }

    /**
     * 使用当前策略选择 Mip 级别
     *
     * @param bboxScreenWidth  AABB 屏幕空间宽度（像素）
     * @param bboxScreenHeight AABB 屏幕空间高度（像素）
     * @return Hi-Z Mip 级别
     */
    public int selectMipLevel(float bboxScreenWidth, float bboxScreenHeight) {
        return selectMipLevel(bboxScreenWidth, bboxScreenHeight, currentStrategy);
    }

    // ==================== 核心 API：HZB 尺寸计算（Unreal 方式）====================

    /**
     * 计算 HZB 的 mip 0 尺寸（Unreal Engine 方式）
     *
     * <p>HZB 的 mip 0 不使用原始深度缓冲尺寸，而是取
     * ≤原始尺寸的最大 2 的幂，以保证所有 mip 级别尺寸均为 2 的幂
     *
     * <h3>Unreal 公式</h3>
     * <pre>
     * mip0Size = 2^floor(log2(min(width, height)))
     *
     * 示例:
     *   850×850  → mip0 = 512×512   (2^9 = 512)
     *   1920×1080 → mip0 = 512×512  (min=1080, 2^10=1024>1080, 2^9=512)
     *   3840×2160 → mip0 = 1024×1024 (min=2160, 2^11=2048)
     * </pre>
     *
     * @param width  原始深度缓冲宽度
     * @param height 原始深度缓冲高度
     * @return HZB mip 0 的边长（正方形，2 的幂）
     */
    public int calculateHzbMip0Size(int width, int height) {
        int minDim = Math.min(width, height);
        int powerOfTwo = Integer.highestOneBit(minDim);
        return powerOfTwo;
    }

    /**
     * 计算完整的 HZB Mip 链尺寸数组
     *
     * @param width  原始深度缓冲宽度
     * @param height 原始深度缓冲高度
     * @return 各 Mip 级别的尺寸数组，index 0 为 mip 0 尺寸
     */
    public int[] calculateHzbMipChain(int width, int height) {
        int mip0Size = calculateHzbMip0Size(width, height);
        int levels = (int) (Math.log(mip0Size) / Math.log(2.0)) + 1;
        int[] chain = new int[levels];

        for (int i = 0; i < levels; i++) {
            chain[i] = mip0Size >> i;
            if (chain[i] < 1) {
                chain[i] = 1;
            }
        }

        return chain;
    }

    // ==================== 核心 API：策略对比分析 ====================

    /**
     * 单一策略的模拟剔除分析结果
     */
    public static final class StrategyResult {
        /** 策略名称 */
        public final String strategyName;
        /** 测试对象总数 */
        public final int totalObjects;
        /** 正确分类为可见的数量（真阳性） */
        public final int trueVisible;
        /** 正确分类为遮挡的数量（真阴性） */
        public final int trueOccluded;
        /** 错误分类为可见的数量（假阳性：本应遮挡但判为可见） */
        public final int falseVisible;
        /** 错误分类为遮挡的数量（假阴性：本应可见但判为遮挡） */
        public final int falseOccluded;
        /** 假可见率 = falseVisible / totalObjects */
        public final float falseVisibleRate;
        /** 假剔除率 = falseOccluded / totalObjects */
        public final float falseOcclusionRate;
        /** 剔除率 = (trueOccluded + falseOccluded) / totalObjects */
        public final float cullingRate;

        StrategyResult(String strategyName, int totalObjects,
                       int trueVisible, int trueOccluded,
                       int falseVisible, int falseOccluded) {
            this.strategyName = strategyName;
            this.totalObjects = totalObjects;
            this.trueVisible = trueVisible;
            this.trueOccluded = trueOccluded;
            this.falseVisible = falseVisible;
            this.falseOccluded = falseOccluded;
            this.falseVisibleRate = (float) falseVisible / totalObjects;
            this.falseOcclusionRate = (float) falseOccluded / totalObjects;
            this.cullingRate = (float) (trueOccluded + falseOccluded) / totalObjects;
        }

        @Override
        public String toString() {
            return String.format(
                "%s: 总=%d 真可见=%d 真遮挡=%d 假可见=%d(%.1f%%) 假剔除=%d(%.1f%%) 剔除率=%.1f%%",
                strategyName, totalObjects, trueVisible, trueOccluded,
                falseVisible, falseVisibleRate * 100f,
                falseOccluded, falseOcclusionRate * 100f,
                cullingRate * 100f
            );
        }
    }

    /**
     * 模拟三种策略在同一场景下的剔除效果
     *
     * <p>使用模拟数据对比 CONSERVATIVE、BALANCED、AGGRESSIVE 三种策略的
     * 假剔除率（应 ≈0%）和假可见率（可接受 <10%）
     *
     * <h3>模拟场景</h3>
     * <pre>
     * 假设: 1024 个 chunk，均匀分布在不同距离
     * - 近景 (0-32 blocks): 128 chunks，大部分可见 (70%)
     * - 中景 (32-128 blocks): 256 chunks，一半遮挡 (50%)
     * - 远景 (128-512 blocks): 384 chunks，大部分遮挡 (30% 可见)
     * - 超远景 (512+ blocks): 256 chunks，严重遮挡 (10% 可见)
     * </pre>
     *
     * @return 三种策略的分析结果数组 [CONSERVATIVE, BALANCED, AGGRESSIVE]
     */
    public StrategyResult[] compareStrategies() {
        int totalChunks = 1024;
        int screenW = screenWidth;
        int screenH = screenHeight;

        MipStrategy[] strategies = MipStrategy.values();
        StrategyResult[] results = new StrategyResult[strategies.length];

        for (int s = 0; s < strategies.length; s++) {
            MipStrategy strategy = strategies[s];
            int trueVisible = 0;
            int trueOccluded = 0;
            int falseVisible = 0;
            int falseOccluded = 0;

            for (int i = 0; i < totalChunks; i++) {
                float bboxSize = computeSimulatedBBoxSize(i, screenW, screenH);
                int selectedMip = selectMipLevel(bboxSize, bboxSize, strategy);

                boolean isActuallyVisible = computeSimulatedGroundTruth(i);

                boolean isCulledByHZB = testHZBOcclusion(selectedMip, bboxSize);

                if (isActuallyVisible && isCulledByHZB) {
                    falseOccluded++;
                } else if (!isActuallyVisible && !isCulledByHZB) {
                    falseVisible++;
                } else if (isActuallyVisible) {
                    trueVisible++;
                } else {
                    trueOccluded++;
                }
            }

            results[s] = new StrategyResult(
                strategy.name(),
                totalChunks,
                trueVisible,
                trueOccluded,
                falseVisible,
                falseOccluded
            );
        }

        return results;
    }

    /**
     * 运行策略对比分析并输出结果到日志
     *
     * @return 分析结果数组
     */
    public StrategyResult[] runBenchmark() {
        StrategyResult[] results = compareStrategies();

        LOGGER.info("========== Hi-Z 策略对比基准测试 ==========");
        LOGGER.info(String.format("屏幕分辨率: %d×%d, 模拟 Chunk 数: 1024", screenWidth, screenHeight));
        LOGGER.info("────────────────────────────────────────");

        for (StrategyResult result : results) {
            if (result.falseOcclusionRate > 0.001f) {
                LOGGER.warning("⚠ " + result.toString() + "  [假剔除率过高!]");
            } else {
                LOGGER.info("✓ " + result.toString());
            }
        }

        LOGGER.info("===================================================");
        LOGGER.info("推荐策略: " + recommendStrategy(results).strategyName);
        LOGGER.info("===================================================");

        return results;
    }

    /**
     * 基于分析结果推荐最优策略
     *
     * <p>选择标准:
     * <ol>
     *   <li>假剔除率必须 ≤ 0.1%（避免可见对象消失）</li>
     *   <li>在满足条件 1 的策略中选择剔除率最高的</li>
     *   <li>如果三个策略都不满足 → 回退 CONSERVATIVE</li>
     * </ol>
     *
     * @param results 策略分析结果数组
     * @return 推荐的最优策略结果
     */
    public StrategyResult recommendStrategy(StrategyResult[] results) {
        StrategyResult best = results[0];

        for (StrategyResult result : results) {
            if (result.falseOcclusionRate <= 0.001f
                && result.cullingRate > best.cullingRate) {
                best = result;
            }
        }

        return best;
    }

    // ==================== 模拟辅助方法 ====================

    /**
     * 模拟计算屏幕空间 AABB 投影大小
     *
     * <p>距离越远 → 投影越小 → Mip 级别越高
     *
     * @param chunkIndex chunk 编号 [0, 1023]
     * @param screenW    屏幕宽度
     * @param screenH    屏幕高度
     * @return 模拟的屏幕空间尺寸（像素）
     */
    private static float computeSimulatedBBoxSize(int chunkIndex, int screenW, int screenH) {
        float distance = 1.0f + (float) chunkIndex / 1023f * 2048f;

        float baseSize = Math.min(screenW, screenH) * 0.1f;

        return baseSize / (1.0f + distance * 0.01f);
    }

    /**
     * 模拟真值：该对象是否实际可见
     *
     * <p>远距离 → 被遮挡概率高；近距离 → 大部分可见
     *
     * @param chunkIndex chunk 编号
     * @return true 如果实际上可见
     */
    private static boolean computeSimulatedGroundTruth(int chunkIndex) {
        float distance = 1.0f + (float) chunkIndex / 1023f * 2048f;

        float occlusionProbability;
        if (distance < 256f) {
            occlusionProbability = 0.1f;
        } else if (distance < 512f) {
            occlusionProbability = 0.3f;
        } else if (distance < 1024f) {
            occlusionProbability = 0.5f;
        } else {
            occlusionProbability = 0.7f;
        }

        int hash = (chunkIndex * 0x9E3779B9 + 0x85EBCA6B);
        hash ^= hash >>> 16;
        float random = ((hash & 0x7FFFFFFF) / (float) 0x7FFFFFFF);

        return random > occlusionProbability;
    }

    /**
     * 模拟 Hi-Z 遮挡测试
     *
     * <p>根据 Mip 级别计算遮挡概率:
     * <ul>
     *   <li>Mip 低 → 几乎不产生假遮挡</li>
     *   <li>Mip 中等 → 适度遮挡概率</li>
     *   <li>Mip 高 → 高遮挡概率（可能产生假剔除）</li>
     * </ul>
     *
     * @param selectedMip 选择的 Mip 级别
     * @param bboxSize    AABB 屏幕空间尺寸
     * @return true 如果被 Hi-Z 判定为遮挡
     */
    private static boolean testHZBOcclusion(int selectedMip, float bboxSize) {
        if (selectedMip >= 10) {
            return bboxSize < 8.0f || selectedMip >= 11;
        }
        if (selectedMip >= 8) {
            return bboxSize < 4.0f;
        }
        if (selectedMip >= 5) {
            return bboxSize < 1.5f;
        }
        return false;
    }

    // ==================== 配置 API ====================

    /**
     * 设置当前 Mip 选择策略
     *
     * @param strategy 策略
     */
    public void setStrategy(MipStrategy strategy) {
        this.currentStrategy = strategy;
        LOGGER.fine("Hi-Z Mip 策略设置为: " + strategy.name());
    }

    /**
     * 设置屏幕分辨率（用于模拟分析）
     *
     * @param width  屏幕宽度（像素）
     * @param height 屏幕高度（像素）
     */
    public void setScreenResolution(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("屏幕分辨率必须为正数");
        }
        this.screenWidth = width;
        this.screenHeight = height;
    }

    // ==================== 查询 API ====================

    /**
     * @return 当前 Mip 选择策略
     */
    public MipStrategy getCurrentStrategy() {
        return currentStrategy;
    }

    /**
     * @return 当前屏幕宽度
     */
    public int getScreenWidth() {
        return screenWidth;
    }

    /**
     * @return 当前屏幕高度
     */
    public int getScreenHeight() {
        return screenHeight;
    }
}
