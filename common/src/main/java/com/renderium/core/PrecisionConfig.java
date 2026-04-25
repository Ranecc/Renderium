// ============================================================
// PrecisionConfig - 动态精度管理器配置
// ============================================================
// 提供 DynamicPrecisionManager 所需的所有可配置参数
//
// 设计原则：
//   - 不可变对象（Immutable）：构造后状态固定，线程安全
//   - Builder 模式：支持流畅的链式 API
//   - 预设配置：提供常用场景的快速配置（1000FPS、质量优先等）
//
// 可配置项：
//   1. 目标帧时间（决定精度调整的阈值）
//   2. 各操作类别的默认基准精度
//   3. 自动调整参数（间隔、开关）
//   4. 诊断参数（窗口大小、日志容量）
//
// 使用示例：
//   PrecisionConfig config = new PrecisionConfig.Builder()
//       .targetFrameTimeMs(0.9f)
//       .defaultHotPathPrecision(PrecisionLevel.INT8_FAST)
//       .enableAutoAdjustment(true)
//       .adjustmentIntervalFrames(60)
//       .build();
//
// @see DynamicPrecisionManager
// ============================================================

package com.renderium.core;

/**
 * 动态精度管理器配置（不可变）
 * <p>
 * 封装 {@link DynamicPrecisionManager} 的所有可配置参数。
 * 使用 Builder 模式创建实例，创建后所有属性不可修改。
 * <p>
 *
 * <h2>设计理念</h2>
 * <p>
 * 配置对象的不可变性带来以下优势：
 * <ul>
 *   <li><b>线程安全</b>: 无需同步即可在多线程间共享</li>
 *   <li><b>逻辑简单</b>: 不存在状态不一致的风险</li>
 *   <li><b>易于测试</b>: 可以安全地传递和比较</li>
 * </ul>
 *
 * <h2>预设配置</h2>
 * <p>
 * 提供多种预设配置以覆盖常见使用场景：
 * <table border="1">
 *   <tr><th>预设名称</th><th>目标帧率</th><th>热路径</th><th>冷路径</th><th>适用场景</th></tr>
 *   <tr><td>DEFAULT_1000FPS</td><td>~1111 FPS</td><td>INT8</td><td>FP32</td><td>电竞/VR</td></tr>
 *   <tr><td>QUALITY_PRIORITY</td><td>500 FPS</td><td>FP16</td><td>Kahan</td><td>3A 大作/截图</td></tr>
 *   <tr><td>BALANCED</td><td>60 FPS</td><td>FP16</td><td>FP32</td><td>主流游戏</td></tr>
 *   <tr><td>DEBUG_PERFORMANCE</td><td>N/A</td><td>SKIP</td><td>SKIP</td><td>性能剖析</td></tr>
 * </table>
 *
 * <h2>Builder 使用示例</h2>
 * <pre>{@code
 * // 示例1: 自定义 144FPS 配置
 * PrecisionConfig config = new PrecisionConfig.Builder()
 *     .targetFrameTimeMs(6.5f)                    // 1000ms/144 ≈ 6.94ms，留余量
 *     .defaultHotPathPrecision(PrecisionLevel.FP16_MEDIUM)  // 热路径用 FP16
 *     .defaultTileStatsPrecision(PrecisionLevel.FP16_MEDIUM)
 *     .defaultFrameAccumulationPrecision(PrecisionLevel.FP32_FULL)
 *     .defaultOfflineAnalysisPrecision(PrecisionLevel.KAHAN_PRECISE)
 *     .enableAutoAdjustment(true)
 *     .adjustmentIntervalFrames(30)                // 30 帧调整一次（更快响应）
 *     .averageWindowSize(20)                       // 20 帧滑动窗口
 *     .decisionLogSize(50)                         // 保留最近 50 条决策日志
 *     .build();
 *
 * DynamicPrecisionManager manager = new DynamicPrecisionManager(config);
 * }</pre>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 3.0.0
 * @see DynamicPrecisionManager
 */
public final class PrecisionConfig {

    // ==================== 预设配置常量 ====================

    /**
     * 默认 1000FPS 配置（性能优先）
     * <p>
     * 目标：每帧 < 0.9ms，支持 ~1100 FPS
     * <ul>
     *   <li>热路径: INT8（极致速度）</li>
     *   <li>温路径: FP16（Tensor Core 加速）</li>
     *   <li>冷路径: FP32（无损累加）</li>
     *   <li>离线: Kahan（最高精度）</li>
     * </ul>
     * 适用场景：电竞游戏、VR 应用、实时渲染演示
     */
    public static final PrecisionConfig DEFAULT_1000FPS = new Builder()
        .targetFrameTimeMs(DynamicPrecisionManager.DEFAULT_TARGET_FRAME_TIME_MS)
        .defaultHotPathPrecision(DynamicPrecisionManager.PrecisionLevel.INT8_FAST)
        .defaultTileStatsPrecision(DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM)
        .defaultFrameAccumulationPrecision(DynamicPrecisionManager.PrecisionLevel.FP32_FULL)
        .defaultOfflineAnalysisPrecision(DynamicPrecisionManager.PrecisionLevel.KAHAN_PRECISE)
        .enableAutoAdjustment(true)
        .adjustmentIntervalFrames(DynamicPrecisionManager.DEFAULT_ADJUSTMENT_INTERVAL_FRAMES)
        .averageWindowSize(30)
        .decisionLogSize(50)
        .build();

    /**
     * 质量优先配置（500FPS 目标）
     * <p>
     * 目标：每帧 < 2.0ms，支持 ~500 FPS
     * <ul>
     *   <li>热路径: FP16（高质量 + Tensor Core）</li>
     *   <li>温路径: FP32（无损统计）</li>
     *   <li>冷路径: Kahan（完美累加）</li>
     *   <li>离线: Kahan（最高精度）</li>
     * </ul>
     * 适用场景：3A 大作、截图模式、视频渲染
     */
    public static final PrecisionConfig QUALITY_PRIORITY = new Builder()
        .targetFrameTimeMs(2.0f)
        .defaultHotPathPrecision(DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM)
        .defaultTileStatsPrecision(DynamicPrecisionManager.PrecisionLevel.FP32_FULL)
        .defaultFrameAccumulationPrecision(DynamicPrecisionManager.PrecisionLevel.KAHAN_PRECISE)
        .defaultOfflineAnalysisPrecision(DynamicPrecisionManager.PrecisionLevel.KAHAN_PRECISE)
        .enableAutoAdjustment(true)
        .adjustmentIntervalFrames(60)
        .averageWindowSize(30)
        .decisionLogSize(50)
        .build();

    /**
     * 平衡配置（60FPS 目标）
     * <p>
     * 目标：每帧 < 15.0ms，支持 ~66 FPS
     * <ul>
     *   <li>热路径: FP16（平衡质量和速度）</li>
     *   <li>温路径: FP32（精确统计）</li>
     *   <li>冷路径: FP32（标准精度）</li>
     *   <li>离线: Kahan（诊断用高精度）</li>
     * </ul>
     * 适用场景：主流游戏、桌面应用
     */
    public static final PrecisionConfig BALANCED = new Builder()
        .targetFrameTimeMs(15.0f)
        .defaultHotPathPrecision(DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM)
        .defaultTileStatsPrecision(DynamicPrecisionManager.PrecisionLevel.FP32_FULL)
        .defaultFrameAccumulationPrecision(DynamicPrecisionManager.PrecisionLevel.FP32_FULL)
        .defaultOfflineAnalysisPrecision(DynamicPrecisionManager.PrecisionLevel.KAHAN_PRECISE)
        .enableAutoAdjustment(true)
        .adjustmentIntervalFrames(30)
        .averageWindowSize(20)
        .decisionLogSize(30)
        .build();

    /**
     * 性能调试配置（强制最低精度）
     * <p>
     * 所有操作都使用 SKIP 或 INT8，用于性能剖析和极限测试。
     * 自动调整禁用，保持用户设置的级别不变。
     */
    public static final PrecisionConfig DEBUG_PERFORMANCE = new Builder()
        .targetFrameTimeMs(0.5f)
        .defaultGlobalPrecision(DynamicPrecisionManager.PrecisionLevel.SKIP)
        .defaultHotPathPrecision(DynamicPrecisionManager.PrecisionLevel.SKIP)
        .defaultTileStatsPrecision(DynamicPrecisionManager.PrecisionLevel.INT8_FAST)
        .defaultFrameAccumulationPrecision(DynamicPrecisionManager.PrecisionLevel.INT8_FAST)
        .defaultOfflineAnalysisPrecision(DynamicPrecisionManager.PrecisionLevel.INT8_FAST)
        .enableAutoAdjustment(false)
        .adjustmentIntervalFrames(Integer.MAX_VALUE)
        .averageWindowSize(10)
        .decisionLogSize(20)
        .build();

    // ==================== 不可变字段 ====================

    /** 目标帧时间（毫秒）：精度调整的目标阈值 */
    private final float targetFrameTimeMs;

    /** 全局默认精度级别（当没有类别特定设置时的回退值） */
    private final DynamicPrecisionManager.PrecisionLevel defaultGlobalPrecision;

    /** 热路径（每像素操作）默认精度 */
    private final DynamicPrecisionManager.PrecisionLevel defaultHotPathPrecision;

    /** 温路径（Tile级统计）默认精度 */
    private final DynamicPrecisionManager.PrecisionLevel defaultTileStatsPrecision;

    /** 冷路径（帧级累加）默认精度 */
    private final DynamicPrecisionManager.PrecisionLevel defaultFrameAccumulationPrecision;

    /** 离线路径（质量检测）默认精度 */
    private final DynamicPrecisionManager.PrecisionLevel defaultOfflineAnalysisPrecision;

    /** 是否启用自动调整 */
    private final boolean autoAdjustmentEnabled;

    /** 自动调整间隔（帧数） */
    private final int adjustmentIntervalFrames;

    /** 帧时间滑动平均窗口大小 */
    private final int averageWindowSize;

    /** 决策日志环形缓冲区大小 */
    private final int decisionLogSize;

    // ==================== 私有构造方法 ====================

    /**
     * 私有构造方法（仅通过 Builder 创建）
     *
     * @param builder 包含所有配置参数的 Builder 对象
     */
    private PrecisionConfig(Builder builder) {
        this.targetFrameTimeMs = builder.targetFrameTimeMs;
        this.defaultGlobalPrecision = builder.defaultGlobalPrecision;
        this.defaultHotPathPrecision = builder.defaultHotPathPrecision;
        this.defaultTileStatsPrecision = builder.defaultTileStatsPrecision;
        this.defaultFrameAccumulationPrecision = builder.defaultFrameAccumulationPrecision;
        this.defaultOfflineAnalysisPrecision = builder.defaultOfflineAnalysisPrecision;
        this.autoAdjustmentEnabled = builder.autoAdjustmentEnabled;
        this.adjustmentIntervalFrames = builder.adjustmentIntervalFrames;
        this.averageWindowSize = builder.averageWindowSize;
        this.decisionLogSize = builder.decisionLogSize;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取目标帧时间（毫秒）
     *
     * @return 目标帧时间（> 0），例如 0.9f 表示 900μs
     */
    public float getTargetFrameTimeMs() {
        return targetFrameTimeMs;
    }

    /**
     * 获取全局默认精度级别
     *
     * @return 默认的全局精度级别（非 null）
     */
    public DynamicPrecisionManager.PrecisionLevel getDefaultGlobalPrecision() {
        return defaultGlobalPrecision;
    }

    /**
     * 获取热路径默认精度级别
     *
     * @return HOT_PATH_PER_PIXEL 类别的推荐精度
     */
    public DynamicPrecisionManager.PrecisionLevel getDefaultHotPathPrecision() {
        return defaultHotPathPrecision;
    }

    /**
     * 获取温路径默认精度级别
     *
     * @return TILE_LEVEL_STATS 类别的推荐精度
     */
    public DynamicPrecisionManager.PrecisionLevel getDefaultTileStatsPrecision() {
        return defaultTileStatsPrecision;
    }

    /**
     * 获取冷路径默认精度级别
     *
     * @return FRAME_LEVEL_ACCUMULATION 类别的推荐精度
     */
    public DynamicPrecisionManager.PrecisionLevel getDefaultFrameAccumulationPrecision() {
        return defaultFrameAccumulationPrecision;
    }

    /**
     * 获取离线路径默认精度级别
     *
     * @return OFFLINE_ANALYSIS 类别的推荐精度
     */
    public DynamicPrecisionManager.PrecisionLevel getDefaultOfflineAnalysisPrecision() {
        return defaultOfflineAnalysisPrecision;
    }

    /**
     * 是否启用自动调整
     *
     * @return true 表示管理器会根据帧时间自动调整精度级别
     */
    public boolean isAutoAdjustmentEnabled() {
        return autoAdjustmentEnabled;
    }

    /**
     * 获取自动调整间隔（帧数）
     *
     * @return 每 N 帧评估一次是否需要调整（>= 1）
     */
    public int getAdjustmentIntervalFrames() {
        return adjustmentIntervalFrames;
    }

    /**
     * 获取帧时间滑动平均窗口大小
     *
     * @return 用于计算平均帧时间的窗口大小（>= 1）
     */
    public int getAverageWindowSize() {
        return averageWindowSize;
    }

    /**
     * 获取决策日志缓冲区大小
     *
     * @return 保留的最近决策记录数量（>= 1）
     */
    public int getDecisionLogSize() {
        return decisionLogSize;
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
            "PrecisionConfig{\n" +
            "  targetFrameTime=%.2fms,\n" +
            "  globalDefault=%s,\n" +
            "  hotPath=%s, tileStats=%s, frameAcc=%s, offline=%s,\n" +
            "  autoAdjustment=%s, interval=%dframes,\n" +
            "  avgWindowSize=%d, logSize=%d\n" +
            "}",
            targetFrameTimeMs,
            defaultGlobalPrecision.name(),
            defaultHotPathPrecision.name(),
            defaultTileStatsPrecision.name(),
            defaultFrameAccumulationPrecision.name(),
            defaultOfflineAnalysisPrecision.name(),
            autoAdjustmentEnabled ? "启用" : "禁用",
            adjustmentIntervalFrames,
            averageWindowSize,
            decisionLogSize
        );
    }

    // ==================== Builder 内部类 ====================

    /**
     * PrecisionConfig 的 Builder
     * <p>
     * 支持链式调用，所有参数都有合理的默认值。
     * 未显式设置的参数将使用默认值。
     *
     * <h3>默认值表</h3>
     * <table border="1">
     *   <tr><th>参数</th><th>默认值</th><th>说明</th></tr>
     *   <tr><td>targetFrameTimeMs</td><td>0.9ms</td><td>~1100 FPS 目标</td></tr>
     *   <tr><td>defaultGlobalPrecision</td><td>FP16_MEDIUM</td><td>平衡选择</td></tr>
     *   <tr><td>defaultHotPathPrecision</td><td>INT8_FAST</td><td>热路径快速</td></tr>
     *   <tr><td>defaultTileStatsPrecision</td><td>FP16_MEDIUM</td><td>温路径平衡</td></tr>
     *   <tr><td>defaultFrameAccumulationPrecision</td><td>FP32_FULL</td><td>冷路径精确</td></tr>
     *   <tr><td>defaultOfflineAnalysisPrecision</td><td>KAHAN_PRECISE</td><td>离线最高精度</td></tr>
     *   <tr><td>autoAdjustmentEnabled</td><td>true</td><td>启用自适应</td></tr>
     *   <tr><td>adjustmentIntervalFrames</td><td>60</td><td>每秒调整一次@60FPS</td></tr>
     *   <tr><td>averageWindowSize</td><td>30</td><td>30帧滑动平均</td></tr>
     *   <tr><td>decisionLogSize</td><td>50</td><td>保留50条日志</td></tr>
     * </table>
     *
     * <h3>使用示例</h3>
     * <pre>{@code
     * PrecisionConfig config = new PrecisionConfig.Builder()
     *     .targetFrameTimeMs(1.0f)
     *     .defaultHotPathPrecision(PrecisionLevel.FP16_MEDIUM)
     *     .enableAutoAdjustment(true)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        // ===== 可配置参数（带默认值） =====

        /** 目标帧时间（毫秒）: 默认 0.9ms (~1100 FPS) */
        private float targetFrameTimeMs = DynamicPrecisionManager.DEFAULT_TARGET_FRAME_TIME_MS;

        /** 全局默认精度: 默认 FP16_MEDIUM */
        private DynamicPrecisionManager.PrecisionLevel defaultGlobalPrecision =
            DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM;

        /** 热路径默认精度: 默认 INT8_FAST */
        private DynamicPrecisionManager.PrecisionLevel defaultHotPathPrecision =
            DynamicPrecisionManager.PrecisionLevel.INT8_FAST;

        /** 温路径默认精度: 默认 FP16_MEDIUM */
        private DynamicPrecisionManager.PrecisionLevel defaultTileStatsPrecision =
            DynamicPrecisionManager.PrecisionLevel.FP16_MEDIUM;

        /** 冷路径默认精度: 默认 FP32_FULL */
        private DynamicPrecisionManager.PrecisionLevel defaultFrameAccumulationPrecision =
            DynamicPrecisionManager.PrecisionLevel.FP32_FULL;

        /** 离线路径默认精度: 默认 KAHAN_PRECISE */
        private DynamicPrecisionManager.PrecisionLevel defaultOfflineAnalysisPrecision =
            DynamicPrecisionManager.PrecisionLevel.KAHAN_PRECISE;

        /** 自动调整开关: 默认启用 */
        private boolean autoAdjustmentEnabled = true;

        /** 调整间隔（帧数）: 默认 60 帧 */
        private int adjustmentIntervalFrames = DynamicPrecisionManager.DEFAULT_ADJUSTMENT_INTERVAL_FRAMES;

        /** 滑动平均窗口大小: 默认 30 帧 */
        private int averageWindowSize = 30;

        /** 决策日志大小: 默认 50 条 */
        private int decisionLogSize = 50;

        /**
         * 创建新的 Builder（所有参数使用默认值）
         */
        public Builder() {
            // 默认构造，使用上面定义的默认值
        }

        /**
         * 设置目标帧时间（毫秒）
         *
         * @param targetFrameTimeMs 目标帧时间（必须 > 0），例如:
         *                           <ul>
         *                             <li>0.9f → ~1100 FPS（电竞）</li>
         *                             <li>2.0f → 500 FPS（高质量）</li>
         *                             <li>16.6f → 60 FPS（标准）</li>
         *                           </ul>
         * @return this（支持链式调用）
         * @throws IllegalArgumentException 若 targetFrameTimeMs <= 0
         */
        public Builder targetFrameTimeMs(float targetFrameTimeMs) {
            if (targetFrameTimeMs <= 0) {
                throw new IllegalArgumentException("目标帧时间必须大于 0: " + targetFrameTimeMs);
            }
            this.targetFrameTimeMs = targetFrameTimeMs;
            return this;
        }

        /**
         * 设置全局默认精度级别
         *
         * @param precision 全局默认精度（不能为 null）
         * @return this（支持链式调用）
         */
        public Builder defaultGlobalPrecision(DynamicPrecisionManager.PrecisionLevel precision) {
            if (precision == null) {
                throw new IllegalArgumentException("全局默认精度不能为 null");
            }
            this.defaultGlobalPrecision = precision;
            return this;
        }

        /**
         * 设置热路径（每像素操作）的默认精度
         *
         * @param precision 热路径推荐精度（不能为 null）
         * @return this（支持链式调用）
         */
        public Builder defaultHotPathPrecision(DynamicPrecisionManager.PrecisionLevel precision) {
            if (precision == null) {
                throw new IllegalArgumentException("热路径精度不能为 null");
            }
            this.defaultHotPathPrecision = precision;
            return this;
        }

        /**
         * 设置温路径（Tile级统计）的默认精度
         *
         * @param precision 温路径推荐精度（不能为 null）
         * @return this（支持链式调用）
         */
        public Builder defaultTileStatsPrecision(DynamicPrecisionManager.PrecisionLevel precision) {
            if (precision == null) {
                throw new IllegalArgumentException("温路径精度不能为 null");
            }
            this.defaultTileStatsPrecision = precision;
            return this;
        }

        /**
         * 设置冷路径（帧级累加）的默认精度
         *
         * @param precision 冷路径推荐精度（不能为 null）
         * @return this（支持链式调用）
         */
        public Builder defaultFrameAccumulationPrecision(DynamicPrecisionManager.PrecisionLevel precision) {
            if (precision == null) {
                throw new IllegalArgumentException("冷路径精度不能为 null");
            }
            this.defaultFrameAccumulationPrecision = precision;
            return this;
        }

        /**
         * 设置离线路径（质量检测/诊断）的默认精度
         *
         * @param precision 离线路径推荐精度（不能为 null）
         * @return this（支持链式调用）
         */
        public Builder defaultOfflineAnalysisPrecision(DynamicPrecisionManager.PrecisionLevel precision) {
            if (precision == null) {
                throw new IllegalArgumentException("离线路径精度不能为 null");
            }
            this.defaultOfflineAnalysisPrecision = precision;
            return this;
        }

        /**
         * 启用或禁用自动调整功能
         *
         * @param enabled true 表示启用自动调整，false 表示手动控制
         * @return this（支持链式调用）
         */
        public Builder enableAutoAdjustment(boolean enabled) {
            this.autoAdjustmentEnabled = enabled;
            return this;
        }

        /**
         * 设置自动调整间隔（帧数）
         *
         * @param frames 每 N 帧评估一次是否需要调整（必须 >= 1）
         *               推荐值：
         *               <ul>
         *                 <li>30 → 快速响应（~33ms @ 1000FPS）</li>
         *                 <li>60 → 标准响应（~60ms @ 1000FPS）</li>
         *                 <li>120 → 缓慢响应（避免抖动）</li>
         *               </ul>
         * @return this（支持链式调用）
         * @throws IllegalArgumentException 若 frames < 1
         */
        public Builder adjustmentIntervalFrames(int frames) {
            if (frames < 1) {
                throw new IllegalArgumentException("调整间隔必须 >= 1: " + frames);
            }
            this.adjustmentIntervalFrames = frames;
            return this;
        }

        /**
         * 设置帧时间滑动平均窗口大小
         *
         * @param size 窗口大小（必须 >= 1），影响平滑程度：
         *             <ul>
         *               <li>10 → 快速反应但噪声大</li>
         *               <li>30 → 平衡（推荐）</li>
         *               <li>60 → 非常平滑但响应慢</li>
         *             </ul>
         * @return this（支持链式调用）
         * @throws IllegalArgumentException 若 size < 1
         */
        public Builder averageWindowSize(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("窗口大小必须 >= 1: " + size);
            }
            this.averageWindowSize = size;
            return this;
        }

        /**
         * 设置决策日志缓冲区大小
         *
         * @param size 日志条目数量（必须 >= 1），用于调试和诊断
         * @return this（支持链式调用）
         * @throws IllegalArgumentException 若 size < 1
         */
        public Builder decisionLogSize(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("日志大小必须 >= 1: " + size);
            }
            this.decisionLogSize = size;
            return this;
        }

        /**
         * 构建不可变的 PrecisionConfig 实例
         * <p>
         * 验证所有参数的一致性后创建配置对象。
         *
         * @return 新的 PrecisionConfig 实例（不可变，线程安全）
         * @throws IllegalStateException 若参数组合不一致
         */
        public PrecisionConfig build() {
            // 参数一致性校验
            if (autoAdjustmentEnabled && adjustmentIntervalFrames < 1) {
                throw new IllegalStateException(
                    "自动调整已启用但调整间隔无效: " + adjustmentIntervalFrames
                );
            }

            if (averageWindowSize > adjustmentIntervalFrames && autoAdjustmentEnabled) {
                // 这是一个警告而非错误：窗口大于调整间隔意味着每次调整时数据可能未填满
                java.util.logging.Logger.getLogger(PrecisionConfig.class.getName())
                    .warning(String.format(
                        "滑动平均窗口(%d)大于调整间隔(%d)，可能导致早期数据波动",
                        averageWindowSize, adjustmentIntervalFrames
                    ));
            }

            return new PrecisionConfig(this);
        }
    }
}
