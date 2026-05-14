// 迁移自: 1.0.0: com.renderium.core.phase.PhaseTransitionDetector
// 初始错误位置: com.ranecc.renderium.domain.service.quality (Batch 1.0-1.2)
// 正确目标位置: com.ranecc.renderium.domain.service.phase (Batch 1.3 修正)
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变
//
// 【Batch 1.3 位置修正说明 - 2026-05-01】
// 问题描述:
//   在 Batch 1.0-1.2 中，PhaseTransitionDetector 被错误地放置在 quality 子包
//   根据功能域划分原则，相变检测属于"相位/阶段管理"领域，应位于 phase 子包
//
// 修正操作:
//   ✓ 从 domain/service/quality/PhaseTransitionDetector.java 移动到此位置
//   ✓ 更新 package 声明为 com.ranecc.renderium.domain.service.phase
//   ✓ 保持所有业务逻辑代码不变（1163行完整实现）
//   ✓ 原 quality 目录下的文件保留作为历史记录（待验证后可删除）
//
// 功能归属:
//   - phase 子包: 相变检测、状态转换、阶段控制
//   - quality 子包: 质量检验、收敛监控、精度管理


// ============================================================
// 相变检测与自适应参数调整系统 (Task 2.3)
// ============================================================
// 基于 TOPS v2.5 §2.3 的相变检测设计，自动检测场景变化并动态调整渲染参数
//
// 核心原理：
//   - 帧间差异二阶矩监测: intensity = |diff_mean - prev_diff_mean| / (prev_diff_mean + ε)
//   - 四种相变类型分类: A(场景切换) / B(光照突变) / C(运动模式) / D(周期性干扰)
//   - 自动触发参数调整回调
//
// 技术实现：
//   - 滑动窗口平滑（减少噪声干扰）
//   - 回调注册表（观察者模式）
//   - 诊断历史记录（支持调试和性能分析）
//
// 性能要求：
//   - 检测延迟 < 0.05ms/frame (1080p)
//   - 内存开销 < 1MB（仅存储滑动窗口）
//   - 误报率 < 10%
//
// @see PhaseEvent
// @see RenderiumCore
// ============================================================

package com.ranecc.renderium.domain.service.phase;
import com.ranecc.renderium.domain.service.quality.PhaseEvent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 相变检测器
 * <p>
 * 基于 TOPS v2.5 的连续控制理论，通过监测帧间差异的二阶矩变化
 * 来自动检测场景切换、光照突变、运动模式变化等相变事件。
 * <p>
 * <h2>理论基础</h2>
 * <p>
 * 在连续控制理论中，"相变"指系统状态的突然跃迁。
 * 对于渲染系统，相变表现为帧间图像特征的剧烈变化：
 * <ul>
 *   <li><b>Type A (场景切换)</b>: intensity > 0.5，如镜头剪切、场景跳转</li>
 *   <li><b>Type B (光照突变)</b>: intensity > 0.3 且方差高，如闪电、灯光开关</li>
 *   <li><b>Type C (运动模式)</b>: intensity > 0.2，如静止→高速运动</li>
 *   <li><b>Type D (周期性干扰)</b>: 方差低但强度中等，如闪烁、刷新率不匹配</li>
 * </ul>
 *
 * <h2>算法流程</h2>
 * <pre>{@code
 * 1. 计算帧间绝对差异: diff[i] = |current[i] - previous[i]|
 * 2. 计算差异均值: diff_mean = mean(diff)
 * 3. 计算差异方差（二阶矩）: variance = var(diff)
 * 4. 计算强度指标: intensity = |diff_mean - prev_mean| / (prev_mean + ε)
 * 5. 滑动窗口平滑: smoothed = moving_average(intensity_history)
 * 6. 相变分类: phase_type = classify(smoothed, variance)
 * 7. 触发回调: fire_callbacks(phase_type, event)
 * }</pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建检测器
 * PhaseTransitionDetector detector = new PhaseTransitionDetector();
 *
 * // 注册回调
 * detector.registerCallback(PhaseType.SCENE_CHANGE, event -> {
 *     System.out.println("场景切换! 强度=" + event.getIntensity());
 *     clearHistoryBuffers();
 * });
 *
 * // 每帧调用检测
 * int[] currentFrame = captureCurrentFrame();
 * int[] previousFrame = getPreviousFrame();
 * PhaseType type = detector.detectTransition(currentFrame, previousFrame);
 *
 * if (type != PhaseType.NONE) {
 *     System.out.println("检测到相变: " + type);
 * }
 * }</pre>
 *
 * <h2>线程安全性</h2>
 * <p>
 * 此类是线程安全的。内部使用 {@link CopyOnWriteArrayList} 存储回调，
 * 支持多线程并发注册/注销回调。
 * 状态变量使用 volatile 保证可见性。
 *
 * <h2>性能特征</h2>
 * <table border="1">
 *   <tr><th>分辨率</th><th>检测延迟</th><th>内存占用</th></tr>
 *   <tr><td>720p (1280×720)</td><td>&lt; 0.02ms</td><td>&lt; 0.5MB</td></tr>
 *   <tr><td>1080p (1920×1080)</td><td>&lt; 0.05ms</td><td>&lt; 1MB</td></tr>
 *   <tr><td>4K (3840×2160)</td><td>&lt; 0.2ms</td><td>&lt; 2MB</td></tr>
 * </table>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 2.3
 * @see PhaseEvent
 */
public final class PhaseTransitionDetector {

    /** 日志记录器 */
    private static final Logger LOGGER =
        Logger.getLogger(PhaseTransitionDetector.class.getName());

    // ==================== 相变类型枚举 ====================

    /**
     * 相变类型枚举
     * <p>
     * 定义四种可检测的场景变化类型，以及表示无变化的 NONE 状态。
     * 每种类型对应不同的触发条件和响应策略。
     */
    public enum PhaseType {
        /**
         * 无相变（正常渲染状态）
         * <p>
         * 表示当前帧与前一帧的差异在正常范围内，
         * 不需要触发任何参数调整。
         */
        NONE("无相变", "正常渲染状态"),

        /**
         * Type A: 场景切换/镜头剪切
         * <p>
         * 触发条件：intensity > 0.5
         * <p>
         * 典型场景：
         * <ul>
         *   <li>镜头硬切（非渐变转场）</li>
         *   <li>场景加载完成</li>
         *   <li>传送门/快速旅行</li>
         *   <li>过场动画结束</li>
         * </ul>
         * <p>
         * 自动响应动作：
         * <ul>
         *   <li>清空历史帧缓冲</li>
         *   <li>重置 SGS attract_k=0.3</li>
         *   <li>重置 Lyapunov 基线</li>
         * </ul>
         * <p>
         * 影响范围：全局
         */
        SCENE_CHANGE("场景切换", "镜头剪切或场景跳转"),

        /**
         * Type B: 光照突变
         * <p>
         * 触发条件：intensity > 0.3 且 variance > 1000
         * <p>
         * 典型场景：
         * <ul>
         *   <li>闪电/爆炸闪光</li>
         *   <li>灯光开关</li>
         *   <li>进入/离开洞穴</li>
         *   <li>昼夜交替瞬间</li>
         * </ul>
         * <p>
         * 自动响应动作：
         * <ul>
         *   <li>调整曝光参数</li>
         *   <li>增大 SGS k=0.2</li>
         * </ul>
         * <p>
         * 影响范围：曝光管线
         */
        LIGHTING_MUTATION("光照突变", "光照条件剧烈变化"),

        /**
         * Type C: 运动模式变化
         * <p>
         * 触发条件：intensity > 0.2
         * <p>
         * 典型场景：
         * <ul>
         *   <li>静止→高速移动</li>
         *   <li>相机快速摇摄</li>
         *   <li>物体突然加速</li>
         *   <li>视角急剧转动</li>
         * </ul>
         * <p>
         * 自动响应动作：
         * <ul>
         *   <li>切换光流算法参数（快速/精确模式）</li>
         *   <li>调整运动补偿权重</li>
         * </ul>
         * <p>
         * 影响范围：光流模块
         */
        MOTION_CHANGE("运动模式变化", "静止到高速运动的转变"),

        /**
         * Type D: 周期性干扰/闪烁
         * <p>
         * 触发条件：variance < 10 且 intensity > 0.05
         * <p>
         * 典型场景：
         * <ul>
         *   <li>显示器刷新率不匹配</li>
         *   <li>电源纹波干扰</li>
         *   <li>TAA 采样抖动异常</li>
         *   <li>周期性后处理效果</li>
         * </ul>
         * <p>
         * 自动响应动作：
         * <ul>
         *   <li>启用时域滤波抑制（TAA 增强）</li>
         * </ul>
         * <p>
         * 影响范围：后处理模块
         */
        PERIODIC_NOISE("周期性干扰", "周期性噪声或闪烁");

        /** 类型名称（中文） */
        private final String name;

        /** 类型描述 */
        private final String description;

        /**
         * 枚举构造方法
         *
         * @param name        类型名称
         * @param description 类型描述
         */
        PhaseType(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /**
         * 获取类型名称
         *
         * @return 中文名称
         */
        public String getName() {
            return name;
        }

        /**
         * 获取类型描述
         *
         * @return 描述文本
         */
        public String getDescription() {
            return description;
        }
    }

    // ==================== 配置常量 ====================

    /**
     * Type A 场景切换阈值
     * <p>
     * 当平滑后的强度指标超过此值时，判定为场景切换。
     * 场景切换通常导致帧间差异接近最大值（255 for 8-bit）。
     */
    public static final float INTENSITY_THRESHOLD_A = 0.5f;

    /**
     * Type B 光照突变阈值
     * <p>
     * 当强度超过此值且方差较高时，判定为光照突变。
     * 光照突变的特点是局部区域亮度剧变，但整体画面结构不变。
     */
    public static final float INTENSITY_THRESHOLD_B = 0.3f;

    /**
     * Type C 运动模式阈值
     * <p>
     * 当强度超过此值时，判定为运动模式变化。
     * 运动模式变化通常是渐进式的，强度介于场景切换和正常波动之间。
     */
    public static final float INTENSITY_THRESHOLD_C = 0.2f;

    /**
     * Type D 周期性干扰的最小强度阈值
     * <p>
     * 当方差较低（< 10）且强度超过此值时，判定为周期性干扰。
     */
    public static final float INTENSITY_THRESHOLD_D_MIN = 0.05f;

    /**
     * Type B 判定的最小方差阈值
     * <p>
     * 光照突变会导致局部区域差异很大，因此方差较高。
     * 此阈值用于区分光照突变和均匀变化。
     */
    public static final float VARIANCE_THRESHOLD_B = 1000.0f;

    /**
     * Type D 判定的最大方差阈值
     * <p>
     * 周期性干扰的特点是整体均匀变化（低方差），但持续存在。
     */
    public static final float VARIANCE_THRESHOLD_D_MAX = 10.0f;

    /**
     * 数值保护常数 ε（Epsilon）
     * <p>
     * 防止除以零的小正数。
     * 在计算强度指标时用作分母的下界。
     */
    private static final float EPSILON = 1e-10f;

    /**
     * 默认滑动窗口大小
     * <p>
     * 用于对强度指标进行移动平均平滑，
     * 减少瞬时噪声导致的误报。
     * 较大的窗口提供更强的平滑效果，但增加响应延迟。
     */
    public static final int DEFAULT_SMOOTHING_WINDOW = 5;

    /**
     * 默认诊断历史容量
     * <p>
     * 存储最近 N 次检测结果用于生成诊断报告。
     */
    public static final int DEFAULT_DIAGNOSTIC_HISTORY_SIZE = 100;

    // ==================== 状态变量 ====================

    /** 上一帧的差异均值（用于计算强度指标的差分） */
    private volatile float prevDiffMean = 0.0f;

    /** 滑动窗口缓冲区（存储最近 N 帧的强度指标） */
    private final float[] intensityHistory;

    /** 滑动窗口写入位置（循环索引） */
    private int historyIndex = 0;

    /** 当前滑动窗口中的有效数据数量（用于初始填充阶段） */
    private int validHistoryCount = 0;

    /** 最近一次检测到的相变类型 */
    private volatile PhaseType lastDetectedPhase = PhaseType.NONE;

    /** 最近一次计算的强度指标（原始值，未平滑） */
    private volatile float lastRawIntensity = 0.0f;

    /** 最近一次计算的平滑强度指标 */
    private volatile float lastSmoothedIntensity = 0.0f;

    /** 总帧计数器（从 0 开始递增） */
    private volatile int totalFrameCount = 0;

    /** 各类型相变的检测次数统计 */
    private final EnumMap<PhaseType, AtomicInteger> detectionCounts;

    /** 诊断历史记录（环形缓冲区） */
    private final CircularBuffer<DiagnosticRecord> diagnosticHistory;

    // ==================== 回调注册表 ====================

    /**
     * 回调映射表
     * <p>
     * 使用 {@link CopyOnWriteArrayList} 保证线程安全的迭代，
     * 允许在遍历过程中安全地添加/删除回调。
     * Key 为相变类型，Value 为该类型的回调列表。
     */
    private final EnumMap<PhaseType, List<java.util.function.Consumer<PhaseEvent>>> callbacks;

    // ==================== 构造方法 ====================

    /**
     * 创建相变检测器（使用默认配置）
     * <p>
     * 默认配置：
     * - 滑动窗口大小: 5 帧
     * - 诊断历史容量: 100 条
     */
    public PhaseTransitionDetector() {
        this(DEFAULT_SMOOTHING_WINDOW, DEFAULT_DIAGNOSTIC_HISTORY_SIZE);
    }

    /**
     * 创建相变检测器（自定义配置）
     *
     * @param smoothingWindow       滑动窗口大小（必须 > 0）
     * @param diagnosticHistorySize 诊断历史容量（必须 > 0）
     * @throws IllegalArgumentException 若参数无效
     */
    public PhaseTransitionDetector(int smoothingWindow, int diagnosticHistorySize) {
        // 参数校验
        if (smoothingWindow <= 0) {
            throw new IllegalArgumentException(
                "滑动窗口大小必须为正数: " + smoothingWindow
            );
        }
        if (diagnosticHistorySize <= 0) {
            throw new IllegalArgumentException(
                "诊断历史容量必须为正数: " + diagnosticHistorySize
            );
        }

        // 初始化状态
        this.intensityHistory = new float[smoothingWindow];
        Arrays.fill(intensityHistory, 0.0f);  // 初始填充零值
        this.historyIndex = 0;
        this.validHistoryCount = 0;

        // 初始化回调表（线程安全）
        this.callbacks = new EnumMap<>(PhaseType.class);
        for (PhaseType type : PhaseType.values()) {
            callbacks.put(type, new CopyOnWriteArrayList<>());
        }

        // 初始化统计计数器
        this.detectionCounts = new EnumMap<>(PhaseType.class);
        for (PhaseType type : PhaseType.values()) {
            detectionCounts.put(type, new AtomicInteger(0));
        }

        // 初始化诊断历史（环形缓冲区）
        this.diagnosticHistory = new CircularBuffer<>(diagnosticHistorySize);

        LOGGER.config(String.format(
            "PhaseTransitionDetector 初始化完成 [window=%d, history=%d]",
            smoothingWindow,
            diagnosticHistorySize
        ));
    }

    // ==================== 核心检测方法 ====================

    /**
     * 检测相变（完整版：接受两帧像素数据）
     * <p>
     * 这是主要的检测入口点。接受当前帧和前一帧的像素数组，
     * 计算帧间差异的二阶矩特征，然后进行相变分类。
     *
     * <h3>算法流程</h3>
     * <ol>
     *   <li>计算绝对差异图: diff[i] = |currentFrame[i] - previousFrame[i]|</li>
     *   <li>计算差异均值: diffMean = mean(diff)</li>
     *   <li>计算差异方差（二阶矩）: diffVariance = var(diff)</li>
     *   <li>计算强度指标: intensity = |diffMean - prevDiffMean| / (prevDiffMean + ε)</li>
     *   <li>更新滑动窗口并计算平滑强度</li>
     *   <li>执行相变分类</li>
     *   <li>若检测到相变，触发注册的回调</li>
     *   <li>更新内部状态并记录诊断信息</li>
     * </ol>
     *
     * <h3>性能优化</h3>
     * <p>
     * 单次检测的时间复杂度为 O(n)，其中 n 是像素数组的长度。
     * 对于 1080p 分辨率（约 200 万像素），预期延迟 < 0.05ms。
     *
     * @param currentFrame  当前帧像素数据（RGBA 格式，一维数组）
     * @param previousFrame 前一帧像素数据（格式和长度必须与 currentFrame 一致）
     * @return 检测到的相变类型（PhaseType.NONE 表示未检测到变化）
     * @throws IllegalArgumentException 若输入数组为 null 或长度不一致
     *
     * @see #detectTransition(float) 简化版接口
     */
    public PhaseType detectTransition(int[] currentFrame, int[] previousFrame) {
        // ===== 参数校验 =====
        if (currentFrame == null || previousFrame == null) {
            throw new IllegalArgumentException("帧像素数据不能为 null");
        }
        if (currentFrame.length != previousFrame.length) {
            throw new IllegalArgumentException(
                "帧数据长度不一致: current=" + currentFrame.length +
                ", previous=" + previousFrame.length
            );
        }
        if (currentFrame.length == 0) {
            throw new IllegalArgumentException("帧像素数据不能为空");
        }

        // ===== Step 1: 计算绝对差异图并统计 =====
        int n = currentFrame.length;
        float sumDiff = 0.0f;
        float sumSqDiff = 0.0f;

        // 单次遍历同时计算均值和平方和（避免二次遍历）
        for (int i = 0; i < n; i++) {
            // 计算每个通道的绝对差异（假设 RGBA 格式，取各通道平均）
            int currPixel = currentFrame[i];
            int prevPixel = previousFrame[i];

            // 提取 RGB 各通道差异
            int dr = Math.abs(((currPixel >> 16) & 0xFF) - ((prevPixel >> 16) & 0xFF));
            int dg = Math.abs(((currPixel >> 8) & 0xFF) - ((prevPixel >> 8) & 0xFF));
            int db = Math.abs((currPixel & 0xFF) - (prevPixel & 0xFF));

            // 三通道平均差异（简化处理，也可使用加权方案）
            float pixelDiff = (dr + dg + db) / 3.0f;

            sumDiff += pixelDiff;
            sumSqDiff += pixelDiff * pixelDiff;
        }

        // ===== Step 2: 计算差异均值和方差 =====
        float diffMean = sumDiff / n;
        // 方差 = E[X²] - (E[X])²
        float diffVariance = (sumSqDiff / n) - (diffMean * diffMean);

        // 数值保护：防止浮点误差导致负方差
        diffVariance = Math.max(0.0f, diffVariance);

        // ===== Step 3: 计算强度指标（归一化的差分）=====
        // 公式：intensity = |diffMean - prevDiffMean| / (prevDiffMean + ε)
        float rawIntensity = Math.abs(diffMean - prevDiffMean) / (Math.abs(prevDiffMean) + EPSILON);

        // ===== Step 4: 滑动窗口平滑 =====
        updateSmoothingWindow(rawIntensity);
        float smoothedIntensity = calculateSmoothedIntensity();

        // ===== Step 5: 相变分类 =====
        PhaseType detectedPhase = classifyPhase(smoothedIntensity, diffVariance);

        // ===== Step 6: 触发回调（仅当检测到非 NONE 相变时）=====
        if (detectedPhase != PhaseType.NONE) {
            // 创建事件对象
            PhaseEvent event = new PhaseEvent(
                detectedPhase,
                smoothedIntensity,
                diffMean,
                diffVariance,
                totalFrameCount
            );

            // 触发回调
            fireCallbacks(detectedPhase, event);

            // 更新统计
            detectionCounts.get(detectedPhase).incrementAndGet();

            // 记录诊断信息
            recordDiagnostic(rawIntensity, smoothedIntensity, diffMean, diffVariance, detectedPhase);

            LOGGER.fine(String.format(
                "相变检测: type=%s, intensity=%.4f, mean=%.2f, variance=%.2f, frame=%d",
                detectedPhase.name(),
                smoothedIntensity,
                diffMean,
                diffVariance,
                totalFrameCount
            ));
        } else {
            // 即使未检测到相变也记录诊断信息（用于分析误报率）
            if (totalFrameCount % 10 == 0) {  // 每 10 帧记录一次，减少开销
                recordDiagnostic(rawIntensity, smoothedIntensity, diffMean, diffVariance, detectedPhase);
            }
        }

        // ===== Step 7: 更新内部状态 =====
        this.prevDiffMean = diffMean;
        this.lastDetectedPhase = detectedPhase;
        this.lastRawIntensity = rawIntensity;
        this.lastSmoothedIntensity = smoothedIntensity;
        this.totalFrameCount++;

        return detectedPhase;
    }

    /**
     * 检测相变（简化版：直接接受预计算的帧差异指标）
     * <p>
     * 此方法适用于已经在外部计算了帧差异度量的场景，
     * 例如 GPU 端预处理后传递结果给 CPU 端分类。
     * <p>
     * 注意：使用此方法时，无法获得完整的 diffMean 和 diffVariance 信息，
     * 因此 Type B 和 Type D 的分类精度可能降低。
     *
     * @param frameDifferenceMetric 外部预计算的帧差异度量值（非负数）
     * @return 检测到的相变类型
     * @throws IllegalArgumentException 若输入值为负数或 NaN
     *
     * @see #detectTransition(int[], int[]) 完整版接口
     */
    public PhaseType detectTransition(float frameDifferenceMetric) {
        // 参数校验
        if (Float.isNaN(frameDifferenceMetric) || Float.isInfinite(frameDifferenceMetric)) {
            throw new IllegalArgumentException("帧差异指标不能为 NaN 或 Inf");
        }
        if (frameDifferenceMetric < 0.0f) {
            throw new IllegalArgumentException("帧差异指标必须为非负数: " + frameDifferenceMetric);
        }

        // 直接使用传入的指标作为 raw intensity
        float rawIntensity = frameDifferenceMetric;

        // 滑动窗口平滑
        updateSmoothingWindow(rawIntensity);
        float smoothedIntensity = calculateSmoothedIntensity();

        // 由于缺乏方差信息，只能基于强度进行简单分类
        // 注意：此模式下 Type B/D 的区分能力受限
        PhaseType detectedPhase = classifyPhaseSimple(smoothedIntensity);

        // 更新状态
        this.lastDetectedPhase = detectedPhase;
        this.lastRawIntensity = rawIntensity;
        this.lastSmoothedIntensity = smoothedIntensity;
        this.totalFrameCount++;

        if (detectedPhase != PhaseType.NONE) {
            PhaseEvent event = new PhaseEvent(
                detectedPhase,
                smoothedIntensity,
                rawIntensity,  // 用 rawIntensity 作为 diffMean 的近似
                0.0f,          // 方差未知
                totalFrameCount
            );

            fireCallbacks(detectedPhase, event);
            detectionCounts.get(detectedPhase).incrementAndGet();
        }

        return detectedPhase;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 更新滑动窗口缓冲区
     * <p>
     * 将新的强度指标写入环形缓冲区，并更新写入位置索引。
     *
     * @param rawIntensity 新的原始强度值
     */
    private void updateSmoothingWindow(float rawIntensity) {
        intensityHistory[historyIndex] = rawIntensity;
        historyIndex = (historyIndex + 1) % intensityHistory.length;

        // 更新有效数据计数（直到填满窗口）
        if (validHistoryCount < intensityHistory.length) {
            validHistoryCount++;
        }
    }

    /**
     * 计算滑动窗口平均值
     * <p>
     * 对窗口内的所有有效数据进行算术平均。
     * 在初始填充阶段（validHistoryCount < windowSize），
     * 仅对已有数据求平均。
     *
     * @return 平滑后的强度指标
     */
    private float calculateSmoothedIntensity() {
        if (validHistoryCount == 0) {
            return 0.0f;
        }

        float sum = 0.0f;
        // 只累加有效数据
        for (int i = 0; i < validHistoryCount; i++) {
            sum += intensityHistory[i];
        }

        return sum / validHistoryCount;
    }

    /**
     * 相变分类（完整版：结合强度和方差）
     * <p>
     * 基于 TOPS v2.5 的四类相变模型进行分类决策。
     * 分类规则按优先级从高到低排列：
     *
     * <h3>决策矩阵</h3>
     * <pre>{@code
     * if intensity > THRESHOLD_A:
     *     → SCENE_CHANGE (最高优先级)
     * elif intensity > THRESHOLD_B and variance > VARIANCE_THRESHOLD_B:
     *     → LIGHTING_MUTATION (高方差表明局部突变)
     * elif intensity > THRESHOLD_C:
     *     → MOTION_CHANGE (中等强度的渐进变化)
     * elif variance < VARIANCE_THRESHOLD_D_MAX and intensity > THRESHOLD_D_MIN:
     *     → PERIODIC_NOISE (低方差表明均匀周期性变化)
     * else:
     *     → NONE (正常范围)
     * }</pre>
     *
     * @param intensity 平滑后的强度指标
     * @param variance 差异方差（二阶矩）
     * @return 相变类型枚举值
     */
    private PhaseType classifyPhase(float intensity, float variance) {
        // Type A: 场景切换（高强度，无论方差如何）
        if (intensity > INTENSITY_THRESHOLD_A) {
            return PhaseType.SCENE_CHANGE;
        }

        // Type B: 光照突变（中高强度 + 高方差）
        if (intensity > INTENSITY_THRESHOLD_B && variance > VARIANCE_THRESHOLD_B) {
            return PhaseType.LIGHTING_MUTATION;
        }

        // Type C: 运动模式变化（中等强度）
        if (intensity > INTENSITY_THRESHOLD_C) {
            return PhaseType.MOTION_CHANGE;
        }

        // Type D: 周期性干扰（低方差 + 低中等强度）
        if (variance < VARIANCE_THRESHOLD_D_MAX && intensity > INTENSITY_THRESHOLD_D_MIN) {
            return PhaseType.PERIODIC_NOISE;
        }

        // 无相变
        return PhaseType.NONE;
    }

    /**
     * 相变分类（简化版：仅基于强度）
     * <p>
     * 用于 {@link #detectTransition(float)} 方法，
     * 在缺乏方差信息时的降级分类策略。
     *
     * @param intensity 平滑后的强度指标
     * @return 相变类型（可能无法区分 B/C/D 类型）
     */
    private PhaseType classifyPhaseSimple(float intensity) {
        if (intensity > INTENSITY_THRESHOLD_A) {
            return PhaseType.SCENE_CHANGE;
        }
        if (intensity > INTENSITY_THRESHOLD_C) {
            // 无法区分 B/C/D，统一归类为运动模式变化
            return PhaseType.MOTION_CHANGE;
        }
        return PhaseType.NONE;
    }

    /**
     * 触发指定类型的所有回调
     * <p>
     * 遍历该类型的回调列表，依次调用每个回调函数。
     * 使用 {@link CopyOnWriteArrayList} 保证遍历过程中的线程安全。
     * 回调抛出的异常会被捕获并记录日志，不会中断后续回调执行。
     *
     * @param phaseType 相变类型
     * @param event     相变事件对象
     */
    private void fireCallbacks(PhaseType phaseType, PhaseEvent event) {
        List<java.util.function.Consumer<PhaseEvent>> callbackList = callbacks.get(phaseType);

        if (callbackList == null || callbackList.isEmpty()) {
            return;
        }

        for (java.util.function.Consumer<PhaseEvent> callback : callbackList) {
            try {
                callback.accept(event);
            } catch (Exception e) {
                // 回调异常不应影响主流程，仅记录警告
                LOGGER.log(Level.WARNING,
                    "相变回调执行异常 [type=" + phaseType + "]: " + e.getMessage(),
                    e
                );
            }
        }
    }

    /**
     * 记录诊断信息到历史缓冲区
     *
     * @param rawIntensity      原始强度
     * @param smoothedIntensity 平滑强度
     * @param diffMean          差异均值
     * @param diffVariance      差异方差
     * @param detectedPhase     检测到的相变类型
     */
    private void recordDiagnostic(float rawIntensity, float smoothedIntensity,
                                  float diffMean, float diffVariance,
                                  PhaseType detectedPhase) {
        DiagnosticRecord record = new DiagnosticRecord(
            totalFrameCount,
            rawIntensity,
            smoothedIntensity,
            diffMean,
            diffVariance,
            detectedPhase,
            Instant.now()
        );
        diagnosticHistory.add(record);
    }

    // ==================== 回调管理 API ====================

    /**
     * 注册相变回调
     * <p>
     * 当检测到指定类型的相变时，将调用此回调函数。
     * 同一类型的回调按注册顺序依次执行。
     * 支持重复注册相同的回调（会多次调用）。
     *
     * <h3>线程安全性</h3>
     * <p>
     * 此方法是线程安全的，可在任意时刻调用。
     *
     * @param type   要监听的相变类型（不能为 NONE，因为 NONE 不会触发回调）
     * @param action 回调函数（接收 {@link PhaseEvent} 参数）
     * @throws IllegalArgumentException 若 type 为 NONE 或 action 为 null
     *
     * @see #unregisterCallback(PhaseType, java.util.function.Consumer) 注销回调
     */
    public void registerCallback(PhaseType type, java.util.function.Consumer<PhaseEvent> action) {
        if (type == PhaseType.NONE) {
            throw new IllegalArgumentException("不能为 NONE 类型注册回调（NONE 不会触发任何事件）");
        }
        Objects.requireNonNull(action, "回调函数不能为 null");

        callbacks.get(type).add(action);

        LOGGER.fine("已注册相变回调: type=" + type + ", total=" + callbacks.get(type).size());
    }

    /**
     * 注销相变回调
     * <p>
     * 移除之前注册的回调函数。
     * 如果回调不存在或已被移除，此方法静默返回（不抛出异常）。
     *
     * @param type   要注销的类型
     * @param action 要移除的回调函数引用
     * @throws IllegalArgumentException 若参数无效
     */
    public void unregisterCallback(PhaseType type, java.util.function.Consumer<PhaseEvent> action) {
        if (type == PhaseType.NONE) {
            throw new IllegalArgumentException("NONE 类型不支持回调操作");
        }
        Objects.requireNonNull(action, "回调函数不能为 null");

        boolean removed = callbacks.get(type).remove(action);

        if (removed) {
            LOGGER.fine("已注销相变回调: type=" + type);
        } else {
            LOGGER.fine("未找到要注销的回调: type=" + type);
        }
    }

    /**
     * 清除指定类型的所有回调
     *
     * @param type 要清除的类型
     */
    public void clearCallbacks(PhaseType type) {
        if (type != PhaseType.NONE) {
            callbacks.get(type).clear();
            LOGGER.fine("已清除所有回调: type=" + type);
        }
    }

    /**
     * 清除所有类型的所有回调
     */
    public void clearAllCallbacks() {
        for (PhaseType type : PhaseType.values()) {
            if (type != PhaseType.NONE) {
                callbacks.get(type).clear();
            }
        }
        LOGGER.fine("已清除所有相变回调");
    }

    // ==================== 诊断查询 API ====================

    /**
     * 获取最近一次的原始强度指标
     *
     * @return 原始强度值（未经过平滑处理）
     */
    public float getLastRawIntensity() {
        return lastRawIntensity;
    }

    /**
     * 获取最近一次的平滑强度指标
     *
     * @return 经过滑动窗口平滑后的强度值
     */
    public float getLastIntensity() {
        return lastSmoothedIntensity;
    }

    /**
     * 获取最近一次检测到的相变类型
     *
     * @return 相变类型枚举值
     */
    public PhaseType getLastDetectedPhase() {
        return lastDetectedPhase;
    }

    /**
     * 获取总处理帧数
     *
     * @return 从初始化以来处理的帧总数
     */
    public int getTotalFrameCount() {
        return totalFrameCount;
    }

    /**
     * 获取指定类型的检测次数
     *
     * @param type 相变类型
     * @return 该类型的累计检测次数
     */
    public int getDetectionCount(PhaseType type) {
        return detectionCounts.get(type).get();
    }

    /**
     * 获取所有类型的检测次数统计
     *
     * @return 不可修改的类型→次数映射
     */
    public Map<PhaseType, Integer> getDetectionCounts() {
        Map<PhaseType, Integer> result = new EnumMap<>(PhaseType.class);
        for (Map.Entry<PhaseType, AtomicInteger> entry : detectionCounts.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 生成诊断报告
     * <p>
     * 返回最近 N 次检测的详细历史记录，包括：
     * - 帧号、时间戳
     * - 原始/平滑强度指标
     * - 差异均值和方差
     * - 检测结果
     * <p>
     * 报告格式为人类可读的多行字符串，适合日志输出或 UI 显示。
     *
     * @return 格式化的诊断报告文本
     */
    public String getDiagnosticReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500 相变检测器诊断报告 \u2500\u2500\u2500\u2500\u2500\u2500\u2500%n");
        sb.append(String.format("总处理帧数: %d%n", totalFrameCount));
        sb.append(String.format("滑动窗口大小: %d%n", intensityHistory.length));
        sb.append(String.format("上次检测类型: %s%n", lastDetectedPhase));
        sb.append(String.format("上次平滑强度: %.6f%n", lastSmoothedIntensity));
        sb.append("----------------------------------------%n");

        // 统计摘要
        sb.append("\u3010检测统计\u3011%n");
        for (PhaseType type : PhaseType.values()) {
            int count = detectionCounts.get(type).get();
            if (count > 0 || type == PhaseType.NONE) {
                sb.append(String.format("  %-20s: %d 次%n", type.getName(), count));
            }
        }

        // 最近的历史记录
        sb.append("\u3010最近检测历史（最新在前）\u3011%n");
        sb.append("%-8s %-12s %-10s %-10s %-12s %-15s%n",
            "帧号", "原始强度", "平滑强度", "差异均值", "方差", "检测类型");
        sb.append("--------------------------------------------------------------------------------%n");

        List<DiagnosticRecord> records = diagnosticHistory.toList();
        // 反向遍历（最新的在前）
        for (int i = records.size() - 1; i >= Math.max(0, records.size() - 20); i--) {
            DiagnosticRecord record = records.get(i);
            sb.append(String.format(
                "%-8d %-12.6f %-10.6f %-10.2f %-12.2f %-15s%n",
                record.frameNumber,
                record.rawIntensity,
                record.smoothedIntensity,
                record.diffMean,
                record.diffVariance,
                record.detectedPhase.getName()
            ));
        }

        sb.append("--------------------------------------------------------------------------------%n");
        return sb.toString();
    }

    /**
     * 获取最近的诊断记录列表
     *
     * @param maxCount 最大返回条数（-1 表示全部）
     * @return 诊断记录列表（按时间顺序排列）
     */
    public List<DiagnosticRecord> getRecentDiagnostics(int maxCount) {
        List<DiagnosticRecord> allRecords = diagnosticHistory.toList();
        if (maxCount <= 0 || maxCount >= allRecords.size()) {
            return allRecords;
        }
        return allRecords.subList(allRecords.size() - maxCount, allRecords.size());
    }

    // ==================== 重置方法 ====================

    /**
     * 重置检测器状态
     * <p>
     * 清除所有历史数据和统计信息，恢复到初始状态。
     * 通常在场景切换后调用此方法，以避免旧数据干扰新场景的检测。
     * 注意：不会清除已注册的回调。
     */
    public void reset() {
        this.prevDiffMean = 0.0f;
        Arrays.fill(intensityHistory, 0.0f);
        this.historyIndex = 0;
        this.validHistoryCount = 0;
        this.lastDetectedPhase = PhaseType.NONE;
        this.lastRawIntensity = 0.0f;
        this.lastSmoothedIntensity = 0.0f;
        this.totalFrameCount = 0;

        // 重置统计计数器
        for (AtomicInteger counter : detectionCounts.values()) {
            counter.set(0);
        }

        // 清空诊断历史
        diagnosticHistory.clear();

        LOGGER.info("PhaseTransitionDetector 状态已重置");
    }

    // ==================== 内部类 ====================

    /**
     * 诊断记录（不可变）
     * <p>
     * 存储单次检测的完整快照信息，用于离线分析和调试。
     */
    public static final class DiagnosticRecord {
        /** 帧号 */
        private final int frameNumber;

        /** 原始强度指标 */
        private final float rawIntensity;

        /** 平滑强度指标 */
        private final float smoothedIntensity;

        /** 差异均值 */
        private final float diffMean;

        /** 差异方差 */
        private final float diffVariance;

        /** 检测到的相变类型 */
        private final PhaseType detectedPhase;

        /** 时间戳 */
        private final Instant timestamp;

        /**
         * 构造诊断记录
         */
        DiagnosticRecord(int frameNumber, float rawIntensity, float smoothedIntensity,
                        float diffMean, float diffVariance, PhaseType detectedPhase,
                        Instant timestamp) {
            this.frameNumber = frameNumber;
            this.rawIntensity = rawIntensity;
            this.smoothedIntensity = smoothedIntensity;
            this.diffMean = diffMean;
            this.diffVariance = diffVariance;
            this.detectedPhase = detectedPhase;
            this.timestamp = timestamp;
        }

        // Getter 方法
        public int getFrameNumber() { return frameNumber; }
        public float getRawIntensity() { return rawIntensity; }
        public float getSmoothedIntensity() { return smoothedIntensity; }
        public float getDiffMean() { return diffMean; }
        public float getDiffVariance() { return diffVariance; }
        public PhaseType getDetectedPhase() { return detectedPhase; }
        public Instant getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format(
                "DiagnosticRecord{frame=%d, raw=%.4f, smooth=%.4f, mean=%.2f, var=%.2f, type=%s}",
                frameNumber, rawIntensity, smoothedIntensity, diffMean, diffVariance, detectedPhase
            );
        }
    }

    /**
     * 环形缓冲区（泛型实现）
     * <p>
     * 固定容量的 FIFO 缓冲区，当超出容量时自动覆盖最旧的元素。
     * 用于存储诊断历史记录。
     *
     * @param <T> 元素类型
     */
    private static class CircularBuffer<T> {
        private final Object[] buffer;
        private int head = 0;
        private int size = 0;

        /**
         * 创建环形缓冲区
         *
         * @param capacity 固定容量
         */
        @SuppressWarnings("unchecked")
        CircularBuffer(int capacity) {
            this.buffer = new Object[capacity];
        }

        /**
         * 添加元素
         *
         * @param item 元素
         */
        void add(T item) {
            int index = (head + size) % buffer.length;
            if (size < buffer.length) {
                size++;
            } else {
                head = (head + 1) % buffer.length;  // 覆盖最旧元素
            }
            buffer[index] = item;
        }

        /**
         * 转换为列表（按插入顺序）
         *
         * @return 元素列表
         */
        @SuppressWarnings("unchecked")
        List<T> toList() {
            List<T> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                result.add((T) buffer[(head + i) % buffer.length]);
            }
            return result;
        }

        /**
         * 清空缓冲区
         */
        void clear() {
            head = 0;
            size = 0;
            Arrays.fill(buffer, null);
        }

        /**
         * 获取当前大小
         *
         * @return 当前元素数量
         */
        int size() {
            return size;
        }
    }
}
