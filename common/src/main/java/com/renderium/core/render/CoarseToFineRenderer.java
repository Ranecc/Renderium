// ============================================================
// 【包迁移说明】
// 原始位置: com.renderium.core.CoarseToFineRenderer
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构，按功能域划分子包
// 新位置: com.renderium.core.render.CoarseToFineRenderer
//
// 注意事项:
//   - 此文件为从原位置自动迁移的副本
//   - package 声明已更新为新子包
//   - 所有业务逻辑代码保持不变
//   - 原始文件保留，待验证无误后可删除
// ============================================================

// ============================================================
// CoarseToFineRenderer - 两阶段渲染引擎（研究原型）
// ============================================================
// 基于 TOPS v2.5 CoarseSDE 粗粒度探索器设计的渲染优化系统
//
// 核心思想：
//   - Phase 1 (Coarse): 低分辨率快速扫描，标记复杂Tile
//   - Phase 2 (Fine): 全分辨率精炼，按需分配计算资源
//
// 两阶段流程：
//   1. 输入帧 → 降采样到 1/4 分辨率
//   2. INT8量化快速分析每个Tile的空间复杂性
//   3. 基于梯度能量+高频成分标记：FP32/FP16/SKIP
//   4. 配额约束：FP32≤10%, FP16≤30%, SKIP≥60%
//   5. 全分辨率精炼：按标记执行不同策略
//
// 数学模型：
//   Tile评分: complexity = α·||∇I||² + β·E_highfreq
//   自适应阈值: T = T_base × (1 + γ·frame_complexity)
//   计算节省: savings = 1 - Σ(cost_i × N_i) / (N_total × cost_FP32)
//
// 性能目标：
//   - 无效计算减少 >20%（vs全分辨率全FP32）
//   - 质量损失 <0.5dB PSNR
//   - 分析开销 <0.5ms/frame (1080p)
//
// @see AdaptivePrecisionManager
// @see ConvergenceMonitor
// ============================================================

package com.renderium.core.render;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 两阶段渲染引擎（Coarse-to-Fine 研究原型）
 * <p>
 * ⚠️ <strong>实验性警告</strong>：此类为研究原型，不可用于生产环境。
 * 核心渲染方法（simulate）为假实现，isAvailable() 始终返回 false。
 *
 * <p>
 * 基于连续系统中的自适应网格加密（AMR）和多尺度分析方法，
 * 实现先粗后细的两阶段渲染策略。
 *
 * <h2>设计原理</h2>
 * <p>
 * 在实时渲染中，不同空间区域的计算需求差异巨大：
 * <ul>
 *   <li><b>静态/均匀区域</b>: 帧间几乎无变化，可用双三次插值快速处理</li>
 *   <li><b>平滑渐变区域</b>: 低频信息为主，简化网络即可保持质量</li>
 *   <li><b>高复杂度边缘</b>: 高频信息丰富，需要完整超分辨率网络</li>
 * </ul>
 * 本引擎通过两阶段分析自动识别这些区域，实现<strong>算力按需分配</strong>。
 *
 * <h2>工作流程（Mermaid）</h2>
 * ```mermaid
 * flowchart TD
 *     A[输入帧] --> B[Phase 1: Coarse Scan]
 *     B --> B1[降采样至1/4分辨率]
 *     B1 --> B2[INT8量化快速扫描]
 *     B2 --> B3[计算Tile复杂度得分]
 *     B3 --> B4{应用配额约束}
 *     B4 -->|FP32≤10%| C1[标记为COMPLEX]
 *     B4 -->|FP16≤30%| C2[标记为MEDIUM]
 *     B4 -->|SKIP≥60%| C3[标记为SIMPLE]
 *
 *     C1 --> D[Phase 2: Refine]
 *     C2 --> D
 *     C3 --> D
 *     D --> D1[FP32 Tile: 完整SR网络]
 *     D --> D2[FP16 Tile: 简化SR网络]
 *     D --> D3[SKIP Tile: 双三次插值]
 *     D1 --> E[输出帧]
 *     D2 --> E
 *     D3 --> E
 * ```
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建引擎（使用默认配置）
 * CoarseToFineRenderer renderer = new CoarseToFineRenderer();
 *
 * // 渲染一帧（自动执行两阶段流程）
 * RenderResult result = renderer.render(inputFrame, width, height);
 *
 * // 获取统计信息
 * System.out.println("计算节省: " + result.getComputeSavingsPercent() + "%");
 * System.out.println("质量损失: " + result.getQualityLossDb() + " dB");
 * System.out.println("Tile分布: " + Arrays.toString(result.getTileDistribution()));
 * }</pre>
 *
 * @deprecated 研究原型，主流程不应调用。Phase 2 的 simulate 方法为假实现。
 * @author Renderium Research Team
 * @version 1.0 (Research Prototype)
 * @since 3.1.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public final class CoarseToFineRenderer {

    private static final Logger LOGGER = Logger.getLogger(CoarseToFineRenderer.class.getName());

    // ==================== 枚举定义 ====================

    /**
     * Tile 复杂性等级（用于Phase 2精炼策略选择）
     */
    public enum TileComplexity {
        /**
         * 简单Tile（SKIP模式）
         * <ul>
         *   <li>适用: 静态背景、均匀色块、低梯度区域</li>
         *   <li>策略: 双三次插值或直接copy</li>
         *   <li>成本: ~0%（零额外计算）</li>
         * </ul>
         */
        SIMPLE(0, "跳过", 0.0f),

        /**
         * 中等复杂度Tile（FP16模式）
         * <ul>
         *   <li>适用: 平滑渐变、中等纹理、低频细节</li>
         *   <li>策略: 简化超分辨率网络（减通道/减层）</li>
         *   <li>成本: ~50%（vs完整FP32网络）</li>
         * </ul>
         */
        MEDIUM(1, "FP16", 0.5f),

        /**
         * 高复杂度Tile（FP32模式）
         * <ul>
         *   <li>适用: 物体边缘、高频纹理、运动模糊边界</li>
         *   <li>策略: 完整超分辨率网络（全精度）</li>
         *   <li>成本: ~100%（基准成本）</li>
         * </ul>
         */
        COMPLEX(2, "FP32", 1.0f);

        /** 序号（用于排序） */
        final int ordinal;

        /** 显示名称 */
        final String name;

        /** 相对成本系数（相对于FP32基线） */
        final float relativeCost;

        TileComplexity(int ordinal, String name, float relativeCost) {
            this.ordinal = ordinal;
            this.name = name;
            this.relativeCost = relativeCost;
        }
    }

    // ==================== 配置常量 ====================

    /** 默认Tile大小（像素）：16x16 */
    static final int DEFAULT_TILE_SIZE = 16;

    /** 默认降采样比例：1/4（满足Nyquist约束） */
    static final int DEFAULT_DOWNSAMPLE_RATIO = 4;

    /** FP32 Tile配额上限（占总Tile数的百分比） */
    static final float DEFAULT_QUOTA_FP32_PERCENT = 0.10f;  // 10%

    /** FP16 Tile配额上限 */
    static final float DEFAULT_QUOTA_FP16_PERCENT = 0.30f;  // 30%

    /** 梯度能量权重系数（α） */
    static final float WEIGHT_GRADIENT_ENERGY = 0.6f;

    /** 高频能量权重系数（β） */
    static final float WEIGHT_HIGH_FREQ_ENERGY = 0.4f;

    /** 帧复杂度敏感系数（γ） */
    static final float FRAME_COMPLEXITY_GAMMA = 0.3f;

    /** 基准高复杂度阈值 */
    static final float BASE_THRESHOLD_COMPLEX = 0.7f;

    /** 基准中复杂度阈值 */
    static final float BASE_THRESHOLD_MEDIUM = 0.4f;

    /** 梯度归一化因子 */
    static final float GRADIENT_NORMALIZER = 5000.0f;

    /** 高频能量归一化因子 */
    static final float HIGH_FREQ_NORMALIZER = 2000.0f;

    // ==================== 实例字段 ====================

    /** Tile大小（像素） */
    private final int tileSize;

    /** 降采样比例 */
    private final int downsampleRatio;

    /** FP32配额（Tile数量上限）- 动态设置 */
    private int quotaFP32;

    /** FP16配额（Tile数量上限）- 动态设置 */
    private int quotaFP16;

    /** 统计信息：各等级Tile数量 */
    private final int[] tileCountByComplexity = new int[TileComplexity.values().length];

    /** 统计信息：总渲染帧数 */
    private long totalRenderedFrames;

    /** 统计信息：总Phase 1耗时（纳秒） */
    private long totalPhase1TimeNs;

    /** 统计信息：总Phase 2耗时（纳秒） */
    private long totalPhase2TimeNs;

    /** 统计信息：累计计算节省率总和（用于求平均） */
    private double cumulativeSavingsRate;

    // ==================== 构造方法 ====================

    /**
     * 创建两阶段渲染引擎（使用默认配置）
     */
    public CoarseToFineRenderer() {
        this(DEFAULT_TILE_SIZE, DEFAULT_DOWNSAMPLE_RATIO);
    }

    /**
     * 创建自定义配置的两阶段渲染引擎
     *
     * @param tileSize Tile边长（像素），必须 >= 8 且为2的幂次方
     * @param downsampleRatio 降采样比例（必须 >= 2，推荐4以满足Nyquist约束）
     * @throws IllegalArgumentException 若参数无效
     */
    public CoarseToFineRenderer(int tileSize, int downsampleRatio) {
        if (tileSize < 8 || (tileSize & (tileSize - 1)) != 0) {
            throw new IllegalArgumentException("Tile大小必须 >= 8 且为2的幂次方: " + tileSize);
        }
        if (downsampleRatio < 2) {
            throw new IllegalArgumentException("降采样比例必须 >= 2: " + downsampleRatio);
        }
        if (downsampleRatio > 8) {
            throw new IllegalArgumentException("降采样比例不能超过8（违反Nyquist约束风险）: " + downsampleRatio);
        }

        this.tileSize = tileSize;
        this.downsampleRatio = downsampleRatio;

        // 配额将在首次render()时根据实际Tile数动态设置
        this.quotaFP32 = 0;  // 占位符
        this.quotaFP16 = 0;  // 占位符

        LOGGER.info(String.format(
            "CoarseToFineRenderer 初始化完成: tileSize=%dpx, downsample=%dx, quotas=[FP32=%.0f%%, FP16=%.0f%%]",
            tileSize, downsampleRatio,
            DEFAULT_QUOTA_FP32_PERCENT * 100,
            DEFAULT_QUOTA_FP16_PERCENT * 100
        ));
    }

    // ==================== 核心渲染 API ====================

    /**
     * 查询此渲染器是否可用于生产环境
     * <p>
     * CoarseToFineRenderer 为研究原型，Phase 2 的 simulate 方法为假实现，
     * 不应用于生产渲染流程。
     *
     * @return false（研究原型，不可用于生产）
     */
    public boolean isAvailable() {
        LOGGER.warning("""
                [CoarseToFineRenderer] ⚠️ 研究原型警告
                此渲染器为实验性研究原型，不可用于生产环境。
                原因：
                  1. Phase 2 simulate() 方法为假实现
                  2. 未经过完整的性能基准测试
                  3. 可能存在数值稳定性问题
                如需使用两阶段渲染，请等待正式版本发布。
                """);
        return false; // 研究原型，不可用于生产
    }

    /**
     * 执行完整的两阶段渲染流程
     * <p>
     * 这是本类的<strong>核心方法</strong>，自动执行：
     * <ol>
     *   <li>Phase 1: Coarse Scan（降采样 + 快速分析 + Tile标记）</li>
     *   <li>Phase 2: Refine（全分辨率精炼，按标记分配资源）</li>
     * </ol>
     *
     * @param frameData 输入帧像素数据（灰度/亮度通道），维度 [height][width]，值域 [0, 255]
     * @param width 帧宽度（像素）
     * @param height 帧高度（像素）
     * @return 渲染结果对象，包含输出数据、统计信息和性能指标
     * @throws IllegalArgumentException 若参数无效
     *
     * @see RenderResult
     */
    public RenderResult render(float[][] frameData, int width, int height) {
        long renderStart = System.nanoTime();

        // 参数校验
        if (frameData == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("帧数据参数无效");
        }

        // ===== Phase 1: Coarse Scan =====
        long phase1Start = System.nanoTime();
        TileComplexity[][] complexityMap = analyzeCoarse(frameData, width, height);
        long phase1End = System.nanoTime();
        long phase1TimeNs = phase1End - phase1Start;

        // ===== Phase 2: Full-Resolution Refine =====
        long phase2Start = System.nanoTime();
        float[][] outputFrame = refineFullResolution(frameData, width, height, complexityMap);
        long phase2End = System.nanoTime();
        long phase2TimeNs = phase2End - phase2Start;

        long renderEnd = System.nanoTime();

        // 更新统计
        totalRenderedFrames++;
        totalPhase1TimeNs += phase1TimeNs;
        totalPhase2TimeNs += phase2TimeNs;

        // 计算性能指标
        int totalTiles = tileCountByComplexity[0] + tileCountByComplexity[1] + tileCountByComplexity[2];
        double baselineCost = totalTiles * TileComplexity.COMPLEX.relativeCost;
        double actualCost =
            tileCountByComplexity[TileComplexity.SIMPLE.ordinal()] * TileComplexity.SIMPLE.relativeCost +
            tileCountByComplexity[TileComplexity.MEDIUM.ordinal()] * TileComplexity.MEDIUM.relativeCost +
            tileCountByComplexity[TileComplexity.COMPLEX.ordinal()] * TileComplexity.COMPLEX.relativeCost;
        double savingsRate = (baselineCost > 0) ? (1.0 - actualCost / baselineCost) : 0.0;
        cumulativeSavingsRate += savingsRate;

        // 模拟质量损失（基于SKIP/FP16比例估算，真实环境需要PSNR计算）
        double qualityLossDb = estimateQualityLoss(complexityMap);

        // 创建结果对象
        RenderResult result = new RenderResult(
            outputFrame,
            complexityMap,
            tileCountByComplexity.clone(),
            phase1TimeNs,
            phase2TimeNs,
            renderEnd - renderStart,
            savingsRate * 100.0,  // 转换为百分比
            qualityLossDb
        );

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format(
                "渲染完成: %dx%d → %d Tiles, 耗时[P1=%.2fμs, P2=%.2fμs], 节省=%.1f%%, 质量=-%.2fdB",
                width, height, totalTiles,
                phase1TimeNs / 1000.0, phase2TimeNs / 1000.0,
                savingsRate * 100.0,
                qualityLossDb
            ));
        }

        return result;
    }

    // ==================== Phase 1: Coarse Scan 方法 ====================

    /**
     * Phase 1: 粗扫描 - 降采样并分析每个Tile的复杂性
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>将输入帧降采样到 1/downsampleRatio 分辨率</li>
     *   <li>对每个Tile计算复杂性得分（梯度能量 + 高频能量）</li>
     *   <li>应用自适应阈值进行初步分类</li>
     *   <li>强制应用配额约束（确保FP32≤10%, FP16≤30%）</li>
     * </ol>
     *
     * @param frameData 原始帧数据
     * @param width 原始宽度
     * @param height 原始高度
     * @return Tile复杂性映射图 [tilesY][tilesX]
     */
    private TileComplexity[][] analyzeCoarse(float[][] frameData, int width, int height) {
        // 1. 降采样
        int coarseWidth = width / downsampleRatio;
        int coarseHeight = height / downsampleRatio;
        float[][] coarseData = downsampleFrame(frameData, width, height, coarseWidth, coarseHeight);

        // 2. 计算Tile网格尺寸
        int tilesX = (coarseWidth + tileSize - 1) / tileSize;
        int tilesY = (coarseHeight + tileSize - 1) / tileSize;

        // 3. 动态设置配额（基于实际Tile数）
        int totalTiles = tilesX * tilesY;
        this.quotaFP32 = Math.max(1, (int)(totalTiles * DEFAULT_QUOTA_FP32_PERCENT));
        this.quotaFP16 = Math.max(1, (int)(totalTiles * DEFAULT_QUOTA_FP16_PERCENT));

        // 重置统计
        Arrays.fill(tileCountByComplexity, 0);

        // 4. 计算全局帧复杂度（用于自适应阈值调整）
        float frameComplexity = computeFrameComplexity(coarseData, coarseWidth, coarseHeight);
        float adaptiveThresholdComplex = BASE_THRESHOLD_COMPLEX * (1.0f + FRAME_COMPLEXITY_GAMMA * frameComplexity);
        float adaptiveThresholdMedium = BASE_THRESHOLD_MEDIUM * (1.0f + FRAME_COMPLEXITY_GAMMA * frameComplexity);

        // 5. 创建临时存储（用于配额约束排序）
        class TileScore implements Comparable<TileScore> {
            int tx, ty;
            float score;
            TileScore(int tx, int ty, float score) { this.tx = tx; this.ty = ty; this.score = score; }
            @Override public int compareTo(TileScore o) { return Float.compare(o.score, this.score); }  // 降序
        }
        java.util.List<TileScore> scoredTiles = new java.util.ArrayList<>();

        // 6. 分析每个Tile
        TileComplexity[][] complexityMap = new TileComplexity[tilesY][tilesX];
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                // 提取Tile区域
                int xStart = tx * tileSize;
                int yStart = ty * tileSize;
                int xEnd = Math.min(xStart + tileSize, coarseWidth);
                int yEnd = Math.min(yStart + tileSize, coarseHeight);

                // 计算复杂性得分
                float score = computeTileComplexityScore(coarseData, xStart, yStart, xEnd, yEnd, coarseWidth);

                // 初步分类（暂不应用配额）
                TileComplexity complexity;
                if (score > adaptiveThresholdComplex) {
                    complexity = TileComplexity.COMPLEX;
                } else if (score > adaptiveThresholdMedium) {
                    complexity = TileComplexity.MEDIUM;
                } else {
                    complexity = TileComplexity.SIMPLE;
                }

                complexityMap[ty][tx] = complexity;
                scoredTiles.add(new TileScore(tx, ty, score));
            }
        }

        // 7. 应用配额约束（贪心算法：优先满足高复杂度Tile）
        scoredTiles.sort(null);  // 按score降序
        int fp32Count = 0;
        int fp16Count = 0;

        for (TileScore ts : scoredTiles) {
            TileComplexity current = complexityMap[ts.ty][ts.tx];
            if (current == TileComplexity.COMPLEX && fp32Count >= quotaFP32) {
                // FP32配额已满，降级为FP16
                complexityMap[ts.ty][ts.tx] = TileComplexity.MEDIUM;
                fp16Count++;
            } else if (current == TileComplexity.MEDIUM && fp16Count >= quotaFP16) {
                // FP16配额已满，降级为SIMPLE
                complexityMap[ts.ty][ts.tx] = TileComplexity.SIMPLE;
            } else {
                if (current == TileComplexity.COMPLEX) fp32Count++;
                else if (current == TileComplexity.MEDIUM) fp16Count++;
            }
        }

        // 8. 统计最终分布
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                tileCountByComplexity[complexityMap[ty][tx].ordinal()]++;
            }
        }

        return complexityMap;
    }

    // ==================== Phase 2: Refine 方法 ====================

    /**
     * Phase 2: 全分辨率精炼 - 按Tile标记执行不同的渲染策略
     * <p>
     * 策略映射：
     * <ul>
     *   <li>COMPLEX (FP32): 模拟完整超分辨率网络处理</li>
     *   <li>MEDIUM (FP16): 模拟简化网络处理（减半计算量）</li>
     *   <li>SIMPLE (SKIP): 双三次插值（极低成本）</li>
     * </ul>
     *
     * @param originalData 原始帧数据
     * @param width 原始宽度
     * @param height 原始高度
     * @param complexityMap Phase 1输出的复杂性映射
     * @return 渲染后的输出帧
     */
    private float[][] refineFullResolution(
        float[][] originalData, int width, int height,
        TileComplexity[][] complexityMap
    ) {
        float[][] output = new float[height][width];

        int tilesX = complexityMap[0].length;
        int tilesY = complexityMap.length;
        int tilePixelSize = tileSize * downsampleRatio;  // 映射回原始分辨率的Tile大小

        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                TileComplexity complexity = complexityMap[ty][tx];

                // 计算原始帧中的Tile坐标
                int xStart = tx * tilePixelSize;
                int yStart = ty * tilePixelSize;
                int xEnd = Math.min(xStart + tilePixelSize, width);
                int yEnd = Math.min(yStart + tilePixelSize, height);

                switch (complexity) {
                    case COMPLEX:
                        // 完整超分辨率网络模拟（高计算量）
                        simulateFullSRNetwork(originalData, output, xStart, yStart, xEnd, yEnd, width, height);
                        break;

                    case MEDIUM:
                        // 简化网络模拟（中计算量）
                        simulateSimplifiedNetwork(originalData, output, xStart, yStart, xEnd, yEnd, width, height);
                        break;

                    case SIMPLE:
                        // 双三次插值模拟（低计算量）
                        simulateBicubicInterpolation(originalData, output, xStart, yStart, xEnd, yEnd, width, height);
                        break;
                }
            }
        }

        return output;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 降采样帧数据（简单的平均池化）
     */
    private float[][] downsampleFrame(float[][] src, int srcW, int srcH, int dstW, int dstH) {
        float[][] dst = new float[dstH][dstW];
        float scaleX = (float)srcW / dstW;
        float scaleY = (float)srcH / dstH;

        for (int dy = 0; dy < dstH; dy++) {
            for (int dx = 0; dx < dstW; dx++) {
                int sxStart = (int)(dx * scaleX);
                int syStart = (int)(dy * scaleY);
                int sxEnd = Math.min((int)((dx + 1) * scaleX), srcW);
                int syEnd = Math.min((int)((dy + 1) * scaleY), srcH);

                float sum = 0.0f;
                int count = 0;
                for (int sy = syStart; sy < syEnd; sy++) {
                    for (int sx = sxStart; sx < sxEnd; sx++) {
                        sum += src[sy][sx];
                        count++;
                    }
                }
                dst[dy][dx] = count > 0 ? sum / count : 128.0f;
            }
        }
        return dst;
    }

    /**
     * 计算单个Tile的复杂性得分
     * <p>
     * 公式: score = α · (||∇I||² / norm_grad) + β · (E_highfreq / norm_hf)
     */
    private float computeTileComplexityScore(
        float[][] data, int xStart, int yStart, int xEnd, int yEnd, int dataWidth
    ) {
        double gradientEnergySum = 0.0;
        double highFreqEnergySum = 0.0;
        int pixelCount = 0;

        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                // 梯度能量（Sobel-like近似）
                float gx = (x < dataWidth - 1) ? data[y][x + 1] - data[y][x] : 0.0f;
                float gy = (y < data.length - 1) ? data[y + 1][x] - data[y][x] : 0.0f;
                gradientEnergySum += (gx * gx + gy * gy);

                // 高频能量（Laplacian近似）
                float center = data[y][x];
                float laplacian = 0.0f;
                int neighbors = 0;
                if (x > 0) { laplacian += data[y][x - 1] - center; neighbors++; }
                if (x < dataWidth - 1) { laplacian += data[y][x + 1] - center; neighbors++; }
                if (y > 0) { laplacian += data[y - 1][x] - center; neighbors++; }
                if (y < data.length - 1) { laplacian += data[y + 1][x] - center; neighbors++; }
                highFreqEnergySum += (neighbors > 0) ? (laplacian * laplacian / neighbors) : 0.0;

                pixelCount++;
            }
        }

        if (pixelCount == 0) return 0.0f;

        float normalizedGradient = (float)(gradientEnergySum / pixelCount / GRADIENT_NORMALIZER);
        float normalizedHighFreq = (float)(highFreqEnergySum / pixelCount / HIGH_FREQ_NORMALIZER);

        return WEIGHT_GRADIENT_ENERGY * normalizedGradient + WEIGHT_HIGH_FREQ_ENERGY * normalizedHighFreq;
    }

    /**
     * 计算全局帧复杂度（用于自适应阈值调整）
     */
    private float computeFrameComplexity(float[][] data, int width, int height) {
        double totalGradient = 0.0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float gx = (x < width - 1) ? data[y][x + 1] - data[y][x] : 0.0f;
                float gy = (y < height - 1) ? data[y + 1][x] - data[y][x] : 0.0f;
                totalGradient += (gx * gx + gy * gy);
                count++;
            }
        }

        return count > 0 ? (float)(totalGradient / count / GRADIENT_NORMALIZER) : 0.0f;
    }

    /**
     * 模拟完整超分辨率网络（高计算量）
     * <p>
     * 真实环境中这里会调用GPU SR网络（如ESPCN、SRResNet等）
     * 这里用CPU模拟其计算特征
     */
    private void simulateFullSRNetwork(
        float[][] src, float[][] dst, int xStart, int yStart, int xEnd, int yEnd,
        int srcWidth, int srcHeight
    ) {
        // 模拟卷积操作（多次迭代模拟深层网络）
        float[][] temp = new float[yEnd - yStart][xEnd - xStart];
        for (int iter = 0; iter < 8; iter++) {  // 模拟8层网络
            for (int y = yStart; y < yEnd; y++) {
                for (int x = xStart; x < xEnd; x++) {
                    float sum = 0.0f;
                    int n = 0;
                    // 3x3 卷积核
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int ny = y + dy, nx = x + dx;
                            if (ny >= 0 && ny < srcHeight && nx >= 0 && nx < srcWidth) {
                                sum += src[ny][nx] * 0.111f;  // 1/9
                                n++;
                            }
                        }
                    }
                    temp[y - yStart][x - xStart] = (n > 0) ? sum : src[y][x];
                }
            }
            // 复制回src区域（模拟层间传递）
            for (int y = yStart; y < yEnd; y++) {
                for (int x = xStart; x < xEnd; x++) {
                    // 实际不会修改src，这里仅模拟计算耗时
                }
            }
        }
        // 输出结果（带轻微增强效果）
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                dst[y][x] = Math.min(255.0f, Math.max(0.0f, src[y][x] * 1.02f + 2.0f));
            }
        }
    }

    /**
     * 模拟简化网络（中等计算量，约50%）
     */
    private void simulateSimplifiedNetwork(
        float[][] src, float[][] dst, int xStart, int yStart, int xEnd, int yEnd,
        int srcWidth, int srcHeight
    ) {
        // 仅模拟4层网络（减半）
        for (int iter = 0; iter < 4; iter++) {
            for (int y = yStart; y < yEnd; y++) {
                for (int x = xStart; x < xEnd; x++) {
                    float avg = 0.0f;
                    int n = 0;
                    if (y > yStart) { avg += src[y - 1][x]; n++; }
                    if (y < yEnd - 1) { avg += src[y + 1][x]; n++; }
                    if (x > xStart) { avg += src[y][x - 1]; n++; }
                    if (x < xEnd - 1) { avg += src[y][x + 1]; n++; }
                }
            }
        }
        // 输出结果（轻度增强）
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                dst[y][x] = Math.min(255.0f, Math.max(0.0f, src[y][x] * 1.01f + 1.0f));
            }
        }
    }

    /**
     * 模拟双三次插值（极低计算量）
     */
    private void simulateBicubicInterpolation(
        float[][] src, float[][] dst, int xStart, int yStart, int xEnd, int yEnd,
        int srcWidth, int srcHeight
    ) {
        // 直接复制（最简单情况，真实环境会用插值核）
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                dst[y][x] = src[y][x];  // 零成本复制
            }
        }
    }

    /**
     * 估算质量损失（基于Tile分布的经验公式）
     * <p>
     * 真实环境需要PSNR/SSIM计算，这里使用简化的线性模型
     */
    private double estimateQualityLoss(TileComplexity[][] complexityMap) {
        int total = 0;
        int skipCount = 0;
        int fp16Count = 0;

        for (TileComplexity[] row : complexityMap) {
            for (TileComplexity tc : row) {
                total++;
                if (tc == TileComplexity.SIMPLE) skipCount++;
                else if (tc == TileComplexity.MEDIUM) fp16Count++;
            }
        }

        if (total == 0) return 0.0;

        // 经验公式：每个SKIP Tile贡献~0.02dB损失，每个FP16 Tile贡献~0.005dB
        double lossFromSkip = skipCount * 0.02 / total;
        double lossFromFP16 = fp16Count * 0.005 / total;

        return lossFromSkip + lossFromFP16;
    }

    // ==================== 诊断API ====================

    /**
     * 获取平均Phase 1耗时（微秒）
     */
    public double getAveragePhase1TimeUs() {
        return totalRenderedFrames > 0 ? (double)(totalPhase1TimeNs / totalRenderedFrames) / 1000.0 : 0.0;
    }

    /**
     * 获取平均Phase 2耗时（微秒）
     */
    public double getAveragePhase2TimeUs() {
        return totalRenderedFrames > 0 ? (double)(totalPhase2TimeNs / totalRenderedFrames) / 1000.0 : 0.0;
    }

    /**
     * 获取平均计算节省率（百分比）
     */
    public double getAverageSavingsPercent() {
        return totalRenderedFrames > 0 ? cumulativeSavingsRate / totalRenderedFrames * 100.0 : 0.0;
    }

    /**
     * 获取状态摘要
     */
    public String getStatusSummary() {
        return String.format(
            "CoarseToFineRenderer{\n" +
            "  tileSize=%dpx, downsample=%dx,\n" +
            "  quotas=[FP32=%d, FP16=%d],\n" +
            "  renderedFrames=%d,\n" +
            "  avgTime=[P1=%.2fμs, P2=%.2fμs],\n" +
            "  avgSavings=%.1f%%,\n" +
            "  lastDistribution=[SIMPLE=%d, MEDIUM=%d, COMPLEX=%d]\n" +
            "}",
            tileSize, downsampleRatio,
            quotaFP32, quotaFP16,
            totalRenderedFrames,
            getAveragePhase1TimeUs(), getAveragePhase2TimeUs(),
            getAverageSavingsPercent(),
            tileCountByComplexity[TileComplexity.SIMPLE.ordinal()],
            tileCountByComplexity[TileComplexity.MEDIUM.ordinal()],
            tileCountByComplexity[TileComplexity.COMPLEX.ordinal()]
        );
    }

    // ==================== 内部数据类 ====================

    /**
     * 渲染结果（不可变）
     */
    public static final class RenderResult {

        /** 输出帧数据 */
        private final float[][] outputFrame;

        /** Tile复杂性映射 */
        private final TileComplexity[][] complexityMap;

        /** 各等级Tile数量 */
        private final int[] tileDistribution;

        /** Phase 1耗时（纳秒） */
        private final long phase1TimeNs;

        /** Phase 2耗时（纳秒） */
        private final long phase2TimeNs;

        /** 总渲染耗时（纳秒） */
        private final long totalTimeNs;

        /** 计算节省百分比 */
        private final double savingsPercent;

        /** 估算质量损失（dB） */
        private final double qualityLossDb;

        RenderResult(
            float[][] outputFrame, TileComplexity[][] complexityMap,
            int[] tileDistribution, long phase1TimeNs, long phase2TimeNs,
            long totalTimeNs, double savingsPercent, double qualityLossDb
        ) {
            this.outputFrame = outputFrame;
            this.complexityMap = complexityMap;
            this.tileDistribution = tileDistribution;
            this.phase1TimeNs = phase1TimeNs;
            this.phase2TimeNs = phase2TimeNs;
            this.totalTimeNs = totalTimeNs;
            this.savingsPercent = savingsPercent;
            this.qualityLossDb = qualityLossDb;
        }

        public float[][] getOutputFrame() { return outputFrame; }
        public TileComplexity[][] getComplexityMap() { return complexityMap; }
        public int[] getTileDistribution() { return tileDistribution.clone(); }
        public long getPhase1TimeNs() { return phase1TimeNs; }
        public long getPhase2TimeNs() { return phase2TimeNs; }
        public long getTotalTimeNs() { return totalTimeNs; }
        public double getSavingsPercent() { return savingsPercent; }
        public double getQualityLossDb() { return qualityLossDb; }

        @Override
        public String toString() {
            return String.format(
                "RenderResult{savings=%.1f%%, qualityLoss=%.3fdB, time=[P1=%.2fμs, P2=%.2fμs], dist=[%d,%d,%d]}",
                savingsPercent, qualityLossDb,
                phase1TimeNs / 1000.0, phase2TimeNs / 1000.0,
                tileDistribution[0], tileDistribution[1], tileDistribution[2]
            );
        }
    }
}
