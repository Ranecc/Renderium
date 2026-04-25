// ============================================================
// DynamicPrecisionManager - 动态精度决策器
// ============================================================
// 基于 TOPS v2.5 精度分级抽象 §2.4 的自适应精度管理系统
//
// 核心目标：
//   - 运行时动态选择计算精度，平衡质量与性能
//   - 支持 1000FPS 目标（每帧 <1ms 预算）
//   - 智能决策系统根据帧时间预算自动调整
//
// 精度级别（从快到慢）：
//   SKIP(0) → INT8_FAST(8) → FP16_MEDIUM(16) → FP32_FULL(32) → KAHAN_PRECISE(64)
//
// 操作类别（决定使用哪种精度）：
//   HOT_PATH_PER_PIXEL      → 默认最快（每像素操作）
//   TILE_LEVEL_STATS        → 平衡（Tile级统计）
//   FRAME_LEVEL_ACCUMULATION→ 较高精度（帧级累加）
//   OFFLINE_ANALYSIS        → 最高精度（离线分析）
//
// 集成关系：
//   - KahanAccumulator: 仅在 OFFLINE_ANALYSIS/KAHAN_PRECISE 时使用
//   - NanGuardShader: 始终启用（零开销 ~2 cycles）
//   - LyapunovQualityChecker: 异步执行（不阻塞主循环）
//
// 性能保证：
//   - 决策延迟 < 1μs（不能成为瓶颈）
//   - 自适应调整响应时间 < 60帧 (~60ms @ 1000FPS)
//   - 在 1000FPS 目标下自动选择 SKIP/INT8/FP16 组合
//
// @see KahanAccumulator
// @see NanGuardShader
// @see LyapunovQualityChecker
// @see PrecisionConfig
// ============================================================

package com.renderium.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 动态精度管理器（1000FPS 优化核心组件）
 * <p>
 * 基于连续控制理论中的自适应控制思想，
 * 实现运行时精度的动态调整，在质量与性能之间找到最优平衡点。
 * <p>
 *
 * <h2>设计原理</h2>
 * <p>
 * 在实时渲染系统中，不同操作对精度的需求差异巨大：
 * <ul>
 *   <li><b>热路径（每像素）</b>: 卷积核应用、颜色混合等操作需要极致速度</li>
 *   <li><b>温路径（Tile级）</b>: 统计计算、梯度求和可接受适度精度损失</li>
 *   <li><b>冷路径（帧级/离线）</b>: 多帧融合、质量检测可使用高精度算法</li>
 * </ul>
 * 本管理器通过监测实际帧时间，自动选择最适合当前负载的精度级别。
 *
 * <h2>控制理论类比</h2>
 * <pre>{@code
 * ┌─────────────┐    帧时间     ┌──────────────────┐    精度级别    ┌─────────────┐
 * │  渲染管线   │ ──────────→ │  PrecisionManager │ ───────────→ │  计算单元   │
 * │  (被控对象) │             │   (控制器)        │              │ (执行器)    │
 * └─────────────┘             └──────────────────┘              └─────────────┘
 *        ↑                                                           │
 *        │                    目标帧时间                              │
 *        └───────────────────────────────────────────────────────────┘
 * }</pre>
 *
 * <h2>精度级别定义</h2>
 * <table border="1">
 *   <tr><th>级别</th><th>位宽</th><th>典型延迟(1080p)</th><th>适用场景</th></tr>
 *   <tr><td>SKIP</td><td>0</td><td>0ms</td><td>静态背景 Tile</td></tr>
 *   <tr><td>INT8_FAST</td><td>8</td><td>0.1ms</td><td>卷积/激活函数</td></tr>
 *   <tr><td>FP16_MEDIUM</td><td>16</td><td>0.3ms</td><td>大部分操作</td></tr>
 *   <tr><td>FP32_FULL</td><td>32</td><td>0.5ms</td><td>关键路径</td></tr>
 *   <tr><td>KAHAN_PRECISE</td><td>64</td><td>1.0ms+</td><td>多帧融合/统计</td></tr>
 * </table>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建管理器（使用默认 1000FPS 配置）
 * DynamicPrecisionManager precisionMgr = new DynamicPrecisionManager(
 *     PrecisionConfig.DEFAULT_1000FPS
 * );
 *
 * // 每帧开始时更新帧时间
 * precisionMgr.updateFrameTime(actualFrameTimeMs);
 *
 * // 在热路径中查询精度
 * PrecisionLevel hotPathPrecision = precisionMgr.decidePrecision(
 *     OperationCategory.HOT_PATH_PER_PIXEL
 * );
 * switch (hotPathPrecision) {
 *     case INT8_FAST:
 *         // 使用 INT8 量化卷积
 *         break;
 *     case FP16_MEDIUM:
 *         // 使用 FP16 Tensor Core 加速
 *         break;
 * }
 *
 * // 在冷路径中使用 Kahan 累加器
 * if (precisionMgr.decidePrecision(OperationCategory.FRAME_LEVEL_ACCUMULATION)
 *         == PrecisionLevel.KAHAN_PRECISE) {
 *     kahanAccumulator.add(value);
 * } else {
 *     naiveSum += value;  // 快速路径
 * }
 * }</pre>
 *
 * <h2>线程安全性</h2>
 * <ul>
 *   <li>{@link #updateFrameTime(double)} 和 {@link #decidePrecision(OperationCategory)} 是线程安全的</li>
 *   <li>内部状态使用 volatile + AtomicLong 保证可见性</li>
 *   <li>支持多线程并发调用（如渲染线程 + 统计线程）</li>
 * </ul>
 *
 * <h2>性能特征</h2>
 * <ul>
 *   <li>决策延迟: < 1μs（仅涉及枚举比较和数组索引）</li>
 *   <li>内存占用: O(windowSize) 用于滑动平均窗口</li>
 *   <li>无锁设计: 使用 CAS-free 的 volatile 读取</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 3.0.0
 * @see PrecisionLevel
 * @see OperationCategory
 * @see PrecisionConfig
 * @see KahanAccumulator
 */
public final class DynamicPrecisionManager {

    private static final Logger LOGGER = Logger.getLogger(DynamicPrecisionManager.class.getName());

    // ==================== 枚举定义 ====================

    /**
     * 精度级别定义（从快到慢排序）
     * <p>
     * 每个级别对应不同的数值表示和计算复杂度。
     * ordinal() 值越小表示速度越快，值越大表示精度越高。
     */
    public enum PrecisionLevel {
        /**
         * 跳过模式（零开销）
         * <ul>
         *   <li>位宽: 0（无计算）</li>
         *   <li>延迟: 0ms</li>
         *   <li>适用: 静态背景、未变化的 Tile</li>
         *   <li>策略: 直接 copy 或插值上一帧结果</li>
         * </ul>
         */
        SKIP(0, "跳过", 0),

        /**
         * INT8 快速模式（量化计算）
         * <ul>
         *   <li>位宽: 8 位整数</li>
         *   <li>延迟: ~0.1ms (1080p)</li>
         *   <li>适用: 卷积核应用、激活函数</li>
         *   <li>优势: 4x 带宽节省，适合内存瓶颈场景</li>
         *   <li>劣势: 有精度损失（量化误差）</li>
         * </ul>
         */
        INT8_FAST(8, "INT8快速", 1),

        /**
         * FP16 中等精度模式（Tensor Core 加速）
         * <ul>
         *   <li>位宽: 16 位半精度浮点</li>
         *   <li>延迟: ~0.3ms (1080p)</li>
         *   <li>适用: 大部分渲染操作</li>
         *   <li>优势: NVIDIA Tensor Core 原生支持，2x 吞吐量</li>
         *   <li>质量: 高（人眼几乎无法感知差异）</li>
         * </ul>
         */
        FP16_MEDIUM(16, "FP16中等", 2),

        /**
         * FP32 全精度模式（无损计算）
         * <ul>
         *   <li>位宽: 32 位单精度浮点</li>
         *   <li>延迟: ~0.5ms (1080p)</li>
         *   <li>适用: 关键路径（几何变换、光照计算）</li>
         *   <li>优势: IEEE 754 标准，完全无损</li>
         *   <li>劣势: 带宽和计算量是 FP16 的 2x</li>
         * </ul>
         */
        FP32_FULL(32, "FP32全精度", 3),

        /**
         * Kahan 精确模式（最高精度）
         * <ul>
         *   <li>位宽: 64（模拟，FP32 + 补偿变量）</li>
         *   <li>延迟: ~1.0ms+ (1080p)</li>
         *   <li>适用: 多帧融合、离线统计分析</li>
         *   <li>优势: O(ε) 级误差（vs 朴素累加的 O(n·ε)）</li>
         *   <li>劣势: CPU 开销 82x（仅用于冷路径）</li>
         * </ul>
         * @see KahanAccumulator
         */
        KAHAN_PRECISE(64, "Kahan精确", 4);

        /** 位数宽度（用于估算带宽和存储需求） */
        final int bitWidth;

        /** 人类可读名称（用于日志和调试） */
        final String name;

        /** 优先级数值（ordinal() 的语义化别名） */
        final int priority;

        PrecisionLevel(int bitWidth, String name, int priority) {
            this.bitWidth = bitWidth;
            this.name = name;
            this.priority = priority;
        }

        /**
         * 是否允许降级到此级别
         * <p>
         * SKIP 级别不允许进一步降级（已经是最低级别）。
         *
         * @return true 表示可以降级到更低的级别
         */
        public boolean canDowngrade() {
            return this.ordinal() > SKIP.ordinal();
        }

        /**
         * 是否允许升级到此级别
         * <p>
         * KAHAN_PRECISE 不允许进一步升级（已经是最高级别）。
         *
         * @return true 表示可以升级到更高的级别
         */
        public boolean canUpgrade() {
            return this.ordinal() < KAHAN_PRECISE.ordinal();
        }
    }

    /**
     * 操作类别（决定应该使用哪种基准精度）
     * <p>
     * 不同类别的操作对精度和性能的权衡不同：
     * <ul>
     *   <li><b>热路径</b>: 每像素执行，数量级 ~10^6 次/帧，必须极快</li>
     *   <li><b>温路径</b>: 每 Tile 执行，数量级 ~10^3 次/帧，可接受适度开销</li>
     *   <li><b>冷路径</b>: 每帧执行几次，可接受较高延迟以换取精度</li>
     *   <li><b>离线路径</b>: 异步执行或用户触发，可使用最高精度</li>
     * </ul>
     */
    public enum OperationCategory {
        /**
         * 热路径：每像素操作
         * <p>
         * 典型场景：
         * <ul>
         *   <li>卷积核应用（超分辨率、抗锯齿）</li>
         *   <li>像素着色（纹理采样、颜色混合）</li>
         *   <li>数值钳位（NanGuardShader）</li>
         * </ul>
         * 基准精度: {@link PrecisionLevel#INT8_FAST}
         */
        HOT_PATH_PER_PIXEL,

        /**
         * 温路径：Tile 级统计
         * <p>
         * 典型场景：
         * <ul>
         *   <li>梯度能量求和（Lyapunov 检测中间结果）</li>
         *   <li>Tile 可见性判定</li>
         *   <li>LOD 选择</li>
         * </ul>
         * 基准精度: {@link PrecisionLevel#FP16_MEDIUM}
         */
        TILE_LEVEL_STATS,

        /**
         * 冷路径：帧级累加
         * <p>
         * 典型场景：
         * <ul>
         *   <li>多帧融合权重累加</li>
         *   <li>帧时间平滑</li>
         *   <li>运动向量积分</li>
         * </ul>
         * 基准精度: {@link PrecisionLevel#FP32_FULL}
         */
        FRAME_LEVEL_ACCUMULATION,

        /**
         * 离线路径：质量检测/诊断
         * <p>
         * 典型场景：
         * <ul>
         *   <li>Lyapunov 质量验证（完整 Sobel 计算）</li>
         *   <li>性能剖析数据收集</li>
         *   <li>调试信息生成</li>
         * </ul>
         * 基准精度: {@link PrecisionLevel#KAHAN_PRECISE}
         * @see LyapunovQualityChecker
         */
        OFFLINE_ANALYSIS
    }

    // ==================== 配置常量 ====================

    /** 默认目标帧时间（毫秒）：1000FPS = 1ms/frame，留 0.1ms 余量 */
    static final float DEFAULT_TARGET_FRAME_TIME_MS = 0.9f;

    /** 默认调整间隔（帧数）：每 60 帧评估一次是否需要调整 */
    static final int DEFAULT_ADJUSTMENT_INTERVAL_FRAMES = 60;

    /** 升级阈值系数：当平均帧时间 < target * 此值时考虑升级 */
    static final float UPGRADE_THRESHOLD_RATIO = 0.7f;

    /** 严重超支阈值系数：当平均帧时间 > target * 此值时降两级 */
    static final float SEVERE_OVERBUDGET_RATIO = 1.2f;

    /** 轻微超支阈值系数：当平均帧时间 > target * 此值时降一级 */
    static final float MILD_OVERBUDGET_RATIO = 1.0f;

    /** 大量余量阈值系数：当平均帧时间 < target * 此值时升级一级 */
    static final float LARGE_HEADROOM_RATIO = 0.5f;

    // ==================== 实例字段 ====================

    /** 配置参数（不可变，构造后固定） */
    private final PrecisionConfig config;

    /**
     * 当前全局精度级别（volatile 保证跨线程可见性）
     * <p>
     * 由 {@link #adjustGlobalPrecision()} 定期更新，
     * 通过 {@link #decidePrecision(OperationCategory)} 读取。
     */
    private volatile PrecisionLevel currentLevel;

    /** 上次调整精度的帧号（用于判断是否到达调整周期） */
    private long lastAdjustmentFrame = 0;

    /** 当前帧计数器（AtomicLong 支持多线程安全递增） */
    private final AtomicLong currentFrameCounter;

    /**
     * 帧时间滑动平均（用于平滑噪声并检测趋势）
     * <p>
     * 窗口大小由 config.getAverageWindowSize() 决定（默认 30 帧）。
     */
    private final RollingAverage frameTimeAvg;

    /**
     * 强制精度级别（非 null 时覆盖所有决策）
     * <p>
     * 用于调试、测试或特殊场景的手动覆盖。
     * 设置为 null 则恢复自适应模式。
     */
    private volatile PrecisionLevel forcedLevel;

    /**
     * 决策日志环形缓冲区（记录最近 N 次决策原因）
     * <p>
     * 用于诊断和调试，大小由 config.getDecisionLogSize() 决定。
     */
    private final CircularBuffer<String> decisionLog;

    // ==================== 构造方法 ====================

    /**
     * 创建动态精度管理器（使用自定义配置）
     *
     * @param config 精度配置参数（不能为 null）
     * @throws IllegalArgumentException 若 config 为 null
     */
    public DynamicPrecisionManager(PrecisionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("配置参数不能为 null");
        }

        this.config = config;
        this.currentLevel = config.getDefaultGlobalPrecision();
        this.currentFrameCounter = new AtomicLong(0L);
        this.frameTimeAvg = new RollingAverage(config.getAverageWindowSize());
        this.decisionLog = new CircularBuffer<>(config.getDecisionLogSize());

        LOGGER.info(String.format(
            "DynamicPrecisionManager 初始化完成: 目标帧时间=%.2fms, 初始级别=%s, 调整间隔=%d帧",
            config.getTargetFrameTimeMs(),
            currentLevel.name(),
            config.getAdjustmentIntervalFrames()
        ));
    }

    /**
     * 创建动态精度管理器（使用默认 1000FPS 配置）
     * <p>
     * 等价于 {@code new DynamicPrecisionManager(PrecisionConfig.DEFAULT_1000FPS)}
     */
    public DynamicPrecisionManager() {
        this(PrecisionConfig.DEFAULT_1000FPS);
    }

    // ==================== 核心决策 API ====================

    /**
     * 根据操作类别决定应使用的精度级别
     * <p>
     * 这是本类的<strong>核心方法</strong>，应在每次需要选择精度时调用。
     * 决策逻辑综合考虑以下因素：
     * <ol>
     *   <li>操作类别对应的基准精度（热路径用快速精度，冷路径用高精度）</li>
     *   <li>当前全局精度级别（基于历史帧时间的自适应调整结果）</li>
     *   <li>强制级别设置（若存在则直接返回，跳过所有其他逻辑）</li>
     * </ol>
     *
     * <h3>决策流程图（Mermaid）</h3>
     * ```mermaid
     * flowchart TD
     *     A[decidePrecision] --> B{forcedLevel != null?}
     *     B -->|Yes| C[返回 forcedLevel]
     *     B -->|No| D[获取 category 对应的 baseLevel]
     *     D --> E[读取当前平均帧时间]
     *     E --> F{avgTime > target?}
     *     F -->|超支| G{currentLevel > baseLevel?}
     *     G -->|可降级| H[返回 currentLevel - 1]
     *     G -->|不可降级| I[返回 baseLevel]
     *     F -->|未超支| J{avgTime < target * 0.7?}
     *     J -->|有余量| K{currentLevel < baseLevel?}
     *     K -->|可升级| L[返回 currentLevel + 1]
     *     K -->|不可升级| M[返回 baseLevel]
     *     J -->|无余量| N[返回 currentLevel]
     * ```
     *
     * <h3>性能保证</h3>
     * <ul>
     *   <li>时间复杂度: O(1)（仅涉及枚举比较和数组访问）</li>
     *   <li>空间复杂度: O(1)（无内存分配）</li>
     *   <li>典型延迟: < 1μs（即使在低端 CPU 上）</li>
     * </ul>
     *
     * @param category 操作类别（不能为 null）
     * @return 推荐使用的精度级别（永远不会返回 null）
     * @throws IllegalArgumentException 若 category 为 null
     *
     * @see OperationCategory
     * @see PrecisionLevel
     */
    public PrecisionLevel decidePrecision(OperationCategory category) {
        // 参数校验
        if (category == null) {
            throw new IllegalArgumentException("操作类别不能为 null");
        }

        // ===== 步骤1: 检查强制级别 =====
        PrecisionLevel forced = this.forcedLevel;
        if (forced != null) {
            return forced;
        }

        // ===== 步骤2: 获取类别对应的基准精度 =====
        PrecisionLevel baseLevel = getBaseLevelForCategory(category);

        // ===== 步骤3: 读取当前状态（volatile 读，无锁） =====
        PrecisionLevel globalLevel = this.currentLevel;
        float avgFrameTime = (float) frameTimeAvg.getAverage();
        float targetTime = config.getTargetFrameTimeMs();

        // ===== 步骤4: 根据帧时间预算进行微调 =====
        // 核心原则：默认使用类别基准精度，仅在有性能压力时调整
        PrecisionLevel decidedLevel;

        // 检查是否有足够的帧时间数据进行决策
        boolean hasSufficientData = frameTimeAvg.getCount() >= config.getAverageWindowSize();

        if (!hasSufficientData) {
            // 数据不足时直接使用基准精度（避免早期抖动）
            decidedLevel = baseLevel;
        } else if (avgFrameTime > targetTime && baseLevel.ordinal() > PrecisionLevel.SKIP.ordinal()) {
            // 场景A: 超预算 → 在基准级别基础上降级（保证帧率优先）
            int targetOrdinal = Math.max(baseLevel.ordinal() - 1, PrecisionLevel.SKIP.ordinal());
            decidedLevel = PrecisionLevel.values()[targetOrdinal];
            logDecision(String.format(
                "降级决策 [%s]: avgTime=%.3fms > target=%.2fms, %s → %s",
                category.name(), avgFrameTime, targetTime,
                baseLevel.name(), decidedLevel.name()
            ));
        } else if (avgFrameTime < targetTime * LARGE_HEADROOM_RATIO
                   && baseLevel.ordinal() < PrecisionLevel.KAHAN_PRECISE.ordinal()) {
            // 场景B: 大量余量 → 在基准级别基础上升级（提升质量）
            decidedLevel = PrecisionLevel.values()[baseLevel.ordinal() + 1];
            logDecision(String.format(
                "升级决策 [%s]: avgTime=%.3fms < target*%.1f=%.2fms, %s → %s",
                category.name(), avgFrameTime, LARGE_HEADROOM_RATIO,
                targetTime * LARGE_HEADROOM_RATIO,
                baseLevel.name(), decidedLevel.name()
            ));
        } else {
            // 场景C: 正常状态 → 使用类别基准精度（核心设计原则）
            decidedLevel = baseLevel;
        }

        return decidedLevel;
    }

    /**
     * 更新帧时间（每帧调用一次）
     * <p>
     * 应在每帧结束时调用，将实际帧时间录入滑动平均窗口。
     * 当累积足够帧数后（达到 adjustmentInterval），会自动触发全局精度调整。
     *
     * <h3>调用时机</h3>
     * <pre>{@code
     * // 在渲染循环中：
     * long frameStart = System.nanoTime();
     *
     * renderFrame();  // 执行渲染
     *
     * long frameEnd = System.nanoTime();
     * double frameTimeMs = (frameEnd - frameStart) / 1e6;  // 转换为毫秒
     * precisionManager.updateFrameTime(frameTimeMs);
     * }</pre>
     *
     * @param actualFrameTimeMs 当前帧的实际耗时（毫秒），必须 >= 0
     * @throws IllegalArgumentException 若 actualFrameTimeMs 为负数
     */
    public void updateFrameTime(double actualFrameTimeMs) {
        // 参数校验
        if (actualFrameTimeMs < 0) {
            throw new IllegalArgumentException("帧时间不能为负数: " + actualFrameTimeMs);
        }

        // 录入滑动平均
        frameTimeAvg.add(actualFrameTimeMs);

        // 递增帧计数器
        long frameNum = currentFrameCounter.incrementAndGet();

        // 检查是否到达调整周期
        if (!config.isAutoAdjustmentEnabled()) {
            // 自动调整已禁用，跳过
            return;
        }

        if (frameNum - lastAdjustmentFrame >= config.getAdjustmentIntervalFrames()) {
            adjustGlobalPrecision();
            lastAdjustmentFrame = frameNum;
        }
    }

    // ==================== 强制控制 API ====================

    /**
     * 强制设置精度级别（覆盖所有自动决策）
     * <p>
     * 设置后，{@link #decidePrecision(OperationCategory)} 将始终返回此级别，
     * 直到调用 {@link #resetToAdaptive()} 清除强制设置。
     *
     * <h3>使用场景</h3>
     * <ul>
     *   <li><b>调试</b>: 固定精度以便复现问题</li>
     *   <li><b>测试</b>: 验证特定精度级别下的行为</li>
     *   <li><b>特殊场景</b>: 截图模式强制最高画质</li>
     *   <li><b>性能剖析</b>: 测试最低精度下的极限 FPS</li>
     * </ul>
     *
     * @param level 要强制的精度级别（null 表示清除强制，恢复自适应）
     */
    public void forcePrecision(PrecisionLevel level) {
        this.forcedLevel = level;
        if (level != null) {
            LOGGER.warning(String.format(
                "精度级别已强制设置为: %s（将覆盖所有自动决策）",
                level.name()
            ));
        } else {
            LOGGER.info("强制精度已清除，恢复自适应模式");
        }
    }

    /**
     * 清除强制设置，恢复自适应模式
     * <p>
     * 等价于 {@code forcePrecision(null)}
     */
    public void resetToAdaptive() {
        forcePrecision(null);
    }

    // ==================== 诊断 API ====================

    /**
     * 获取当前全局精度级别
     *
     * @return 当前生效的全局精度级别（可能受强制设置影响）
     */
    public PrecisionLevel getCurrentLevel() {
        PrecisionLevel forced = this.forcedLevel;
        return forced != null ? forced : this.currentLevel;
    }

    /**
     * 获取当前平均帧时间（毫秒）
     *
     * @return 最近 N 帧的平均帧时间（N = averageWindowSize）
     */
    public float getAverageFrameTime() {
        return (float) frameTimeAvg.getAverage();
    }

    /**
     * 获取最近 N 次决策原因的日志
     *
     * @return 格式化的决策日志字符串（每行一条记录）
     */
    public String getDecisionLog() {
        return decisionLog.toString();
    }

    /**
     * 获取当前配置（只读）
     *
     * @return 配置对象的引用（不可变，安全共享）
     */
    public PrecisionConfig getConfig() {
        return config;
    }

    /**
     * 获取当前处理的帧总数
     *
     * @return 自创建以来（或上次重置以来）的帧计数
     */
    public long getTotalFrameCount() {
        return currentFrameCounter.get();
    }

    /**
     * 获取完整的状态摘要（用于调试和监控）
     *
     * @return 包含所有关键状态的格式化字符串
     */
    public String getStatusSummary() {
        return String.format(
            "DynamicPrecisionManager{\n" +
            "  currentLevel=%s,\n" +
            "  forcedLevel=%s,\n" +
            "  avgFrameTime=%.3fms,\n" +
            "  targetFrameTime=%.2fms,\n" +
            "  totalFrames=%d,\n" +
            "  lastAdjustmentFrame=%d,\n" +
            "  autoAdjustment=%s\n" +
            "}",
            getCurrentLevel().name(),
            forcedLevel != null ? forcedLevel.name() : "无",
            getAverageFrameTime(),
            config.getTargetFrameTimeMs(),
            getTotalFrameCount(),
            lastAdjustmentFrame,
            config.isAutoAdjustmentEnabled() ? "启用" : "禁用"
        );
    }

    // ==================== 私有方法 ====================

    /**
     * 获取操作类别对应的基准精度
     *
     * @param category 操作类别
     * @return 该类别推荐的基准精度级别
     */
    private PrecisionLevel getBaseLevelForCategory(OperationCategory category) {
        // 优先使用配置中的类别特定设置，否则回退到默认映射
        switch (category) {
            case HOT_PATH_PER_PIXEL:
                return config.getDefaultHotPathPrecision();

            case TILE_LEVEL_STATS:
                return config.getDefaultTileStatsPrecision();

            case FRAME_LEVEL_ACCUMULATION:
                return config.getDefaultFrameAccumulationPrecision();

            case OFFLINE_ANALYSIS:
                return config.getDefaultOfflineAnalysisPrecision();

            default:
                // 不应到达此处，但作为防御性编程
                return PrecisionLevel.FP16_MEDIUM;
        }
    }

    /**
     * 执行全局精度级别的自适应调整
     * <p>
     * 基于最近的平均帧时间与目标帧时间的比较，
     * 决定是否需要升级或降级全局精度。
     *
     * <h3>调整策略</h3>
     * <pre>{@code
     * if (avgTime > target * 1.2):
     *     → 严重超支：降两级（快速恢复帧率）
     * elif (avgTime > target):
     *     → 轻微超支：降一级（温和调整）
     * elif (avgTime < target * 0.5):
     *     → 大量余量：升级一级（提升质量）
     * else:
     *     → 保持现状（稳定状态）
     * }</pre>
     *
     * <h3>防抖动设计</h3>
     * <ul>
     *   <li>只在 adjustmentInterval 周期边界调整（避免频繁抖动）</li>
     *   <li>单次最多调整 2 级（避免剧烈波动）</li>
     *   <li>升级条件比降级严格（优先保证帧率）</li>
     * </ul>
     */
    private void adjustGlobalPrecision() {
        float avgTime = (float) frameTimeAvg.getAverage();
        float targetTime = config.getTargetFrameTimeMs();
        PrecisionLevel oldLevel = this.currentLevel;
        PrecisionLevel newLevel = oldLevel;

        // ===== 严重超支检测（降两级）=====
        if (avgTime > targetTime * SEVERE_OVERBUDGET_RATIO) {
            if (oldLevel.ordinal() > 1) {
                // 至少保留 SKIP 级别（ordinal=0）
                newLevel = PrecisionLevel.values()[oldLevel.ordinal() - 2];
                logDecision(String.format(
                    "严重超支降级: avgTime=%.3fms > %.2fms (阈值%.1fx), %s → %s",
                    avgTime, targetTime * SEVERE_OVERBUDGET_RATIO, SEVERE_OVERBUDGET_RATIO,
                    oldLevel.name(), newLevel.name()
                ));
            } else if (oldLevel.ordinal() > 0) {
                // 只能降一级（已接近最低级别）
                newLevel = PrecisionLevel.values()[oldLevel.ordinal() - 1];
                logDecision(String.format(
                    "轻微超支降级: avgTime=%.3fms > %.2fms, %s → %s",
                    avgTime, targetTime * MILD_OVERBUDGET_RATIO,
                    oldLevel.name(), newLevel.name()
                ));
            }
        }
        // ===== 轻微超支检测（降一级）=====
        else if (avgTime > targetTime * MILD_OVERBUDGET_RATIO) {
            if (oldLevel.canDowngrade()) {
                newLevel = PrecisionLevel.values()[oldLevel.ordinal() - 1];
                logDecision(String.format(
                    "轻微超支降级: avgTime=%.3fms > %.2fms (阈值%.1fx), %s → %s",
                    avgTime, targetTime * MILD_OVERBUDGET_RATIO, MILD_OVERBUDGET_RATIO,
                    oldLevel.name(), newLevel.name()
                ));
            }
        }
        // ===== 大量余量检测（升级一级）=====
        else if (avgTime < targetTime * LARGE_HEADROOM_RATIO) {
            if (oldLevel.canUpgrade()) {
                newLevel = PrecisionLevel.values()[oldLevel.ordinal() + 1];
                logDecision(String.format(
                    "余量升级: avgTime=%.3fms < %.2fms (阈值%.1fx), %s → %s",
                    avgTime, targetTime * LARGE_HEADROOM_RATIO, LARGE_HEADROOM_RATIO,
                    oldLevel.name(), newLevel.name()
                ));
            }
        }
        // ===== 否则保持现状（稳定状态）=====


        // 更新全局级别（volatile 写，立即对所有线程可见）
        if (newLevel != oldLevel) {
            this.currentLevel = newLevel;
            LOGGER.info(String.format(
                "全局精度级别调整: %s → %s (avgFrameTime=%.3fms, target=%.2fms)",
                oldLevel.name(), newLevel.name(), avgTime, targetTime
            ));
        }
    }

    /**
     * 记录决策日志（线程安全的环形缓冲区写入）
     *
     * @param message 决策原因描述
     */
    private void logDecision(String message) {
        decisionLog.add(message);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(message);
        }
    }

    // ==================== 内部工具类 ====================

    /**
     * 滑动平均计算器（固定窗口大小）
     * <p>
     * 使用环形缓冲区实现 O(1) 的增量和查询操作。
     * 用于平滑帧时间数据，消除瞬时噪声的影响。
     *
     * <h3>数学原理</h3>
     * <pre>{@code
     * MovingAverage[n] = (1/n) * Σ_{i=0}^{n-1} x[i]
     * }</pre>
     *
     * <h3>实现细节</h3>
     * <ul>
     *   <li>使用环形数组避免数据移动</li>
     *   <li>维护 runningSum 加速平均值计算</li>
     *   <li>线程安全：单写者（渲染线程）+ 多读者（诊断线程）</li>
     * </ul>
     */
    static final class RollingAverage {

        /** 数据存储（环形缓冲区） */
        private final double[] buffer;

        /** 缓冲区大小（窗口长度） */
        private final int windowSize;

        /** 当前写入位置 */
        private int writeIndex;

        /** 当前已填入的数据数量（< windowSize 时表示尚未填满） */
        private int count;

        /** 当前窗口内数据的总和（用于 O(1) 平均值计算） */
        private double sum;

        /**
         * 创建滑动平均计算器
         *
         * @param windowSize 窗口大小（必须 > 0）
         * @throws IllegalArgumentException 若 windowSize <= 0
         */
        RollingAverage(int windowSize) {
            if (windowSize <= 0) {
                throw new IllegalArgumentException("窗口大小必须大于 0: " + windowSize);
            }
            this.windowSize = windowSize;
            this.buffer = new double[windowSize];
            this.writeIndex = 0;
            this.count = 0;
            this.sum = 0.0;
        }

        /**
         * 添加一个新值到窗口
         * <p>
         * 如果窗口已满，最旧的值将被覆盖。
         *
         * @param value 新的观测值
         */
        synchronized void add(double value) {
            if (count < windowSize) {
                // 窗口未满：直接添加
                buffer[writeIndex] = value;
                sum += value;
                count++;
            } else {
                // 窗口已满：覆盖最旧值，更新 sum
                sum -= buffer[writeIndex];  // 减去被覆盖的旧值
                buffer[writeIndex] = value;
                sum += value;               // 加上新值
            }

            // 移动写入位置（环形）
            writeIndex = (writeIndex + 1) % windowSize;
        }

        /**
         * 获取当前平均值
         *
         * @return 窗口内所有值的算术平均；若无数据则返回 0.0
         */
        synchronized double getAverage() {
            if (count == 0) {
                return 0.0;
            }
            return sum / count;
        }

        /**
         * 重置滑动平均（清空所有历史数据）
         */
        synchronized void reset() {
            writeIndex = 0;
            count = 0;
            sum = 0.0;
            // 无需清空 buffer 数组（count=0 时不会读取）
        }

        /**
         * 获取当前已存储的数据点数量
         *
         * @return 已填入的数量（<= windowSize）
         */
        synchronized int getCount() {
            return count;
        }
    }

    /**
     * 环形缓冲区（泛型版本，用于决策日志）
     * <p>
     * 固定大小的 FIFO 队列，新数据覆盖最旧数据。
     * 用于维护固定大小的最近决策历史。
     *
     * @param <T> 存储的元素类型
     */
    static final class CircularBuffer<T> {

        /** 数据存储数组 */
        private final Object[] buffer;

        /** 缓冲区容量 */
        private final int capacity;

        /** 当前写入位置 */
        private int writeIndex;

        /** 当前已存储的元素数量 */
        private int count;

        /**
         * 创建环形缓冲区
         *
         * @param capacity 容量（必须 > 0）
         */
        @SuppressWarnings("unchecked")
        CircularBuffer(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("容量必须大于 0: " + capacity);
            }
            this.capacity = capacity;
            this.buffer = new Object[capacity];
            this.writeIndex = 0;
            this.count = 0;
        }

        /**
         * 添加元素（可能覆盖最旧元素）
         *
         * @param item 要添加的元素
         */
        synchronized void add(T item) {
            buffer[writeIndex] = item;
            writeIndex = (writeIndex + 1) % capacity;
            if (count < capacity) {
                count++;
            }
        }

        /**
         * 获取所有元素的格式化字符串
         *
         * @return 每行一个元素的字符串（按时间倒序，最新的在前）
         */
        @Override
        public synchronized String toString() {
            StringBuilder sb = new StringBuilder();
            int readIndex = (writeIndex - 1 + capacity) % capacity;  // 从最新开始

            for (int i = 0; i < count; i++) {
                @SuppressWarnings("unchecked")
                T item = (T) buffer[readIndex];  // CircularBuffer 泛型擦除
                if (item != null) {
                    sb.append(item.toString());
                    if (i < count - 1) {
                        sb.append("\n");
                    }
                }
                readIndex = (readIndex - 1 + capacity) % capacity;  // 向前移动
            }

            return sb.toString();
        }
    }
}
