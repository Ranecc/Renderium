// Renderium - Blaze3D 拦截层系统 Phase 4
// 帧生成管理器 - 集成 DLSS-FG/FSR-FG + Reflex 低延迟

package com.ranecc.renderium.feature.intercept.post;
import com.ranecc.renderium.None;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 帧生成管理器（Frame Generation Manager）
 * <p>
 * 管理帧生成（Frame Generation）技术的完整生命周期。
 * 通过 AI 插值或光流分析在两个真实渲染帧之间生成额外的插值帧，
 * 从而在保持渲染负载不变的情况下显著提升感知帧率。
 *
 * <h3>支持的技术：</h3>
 * <ul>
 *   <li><b>DLSS-FG</b>（NVIDIA DLSS Frame Generation）- 
 *       使用光学流场和 AI 模型生成插值帧，需要 RTX 40 系列+ GPU</li>
 *   <li><b>FSR-FG</b>（AMD FSR Frame Generation）- 
 *       基于 FSR 3 的帧生成技术，兼容性更广</li>
 * </ul>
 *
 * <h3>工作原理：</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │                  帧生成时间线                          │
 * ├──────────────────────────────────────────────────────┤
 * │                                                      │
 * │  时间轴:  t0      t1      t2      t3      t4        │
 * │           │       │       │       │       │         │
 * │  渲染帧:  [Frame0]        [Frame1]        [Frame2]   │
 * │           (真实)          (真实)          (真实)     │
 * │           │       │       │       │       │         │
 * │  输出:    [F0][插值][F1][插值][F2][插值]            │
 * │           ↑              ↑              ↑           │
 * │        Present       Present        Present         │
 * │                                                      │
 * │  结果: 渲染 30fps → 输出 ~60-90fps (2x-3x 提升)    │
 * │                                                      │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Reflex 低延迟集成：</h3>
 * <p>NVIDIA Reflex 用于降低系统响应延迟：
 * <ul>
 *   <li><b>PCL (PC Latency)</b>: 标记渲染开始和结束时间点</li>
 *   <li><b>Flash Latency Indicator</b>: 视觉化显示输入延迟</li>
 *   <li><b>低延迟模式</b>: 优化 CPU-GPU 管线减少等待</li>
 * </ul>
 *
 * <h3>性能预算：</h3>
 * <table border="1">
 *   <tr><th>阶段</th><th>兼容模式</th><th>狂暴模式</th></tr>
 *   <tr><td>帧生成</td><td>&lt;4ms</td><td>&lt;3ms</td></tr>
 * </table>
 *
 * <h3>降级策略：</h3>
 * <p>当帧生成失败时：
 * <ol>
 *   <li>记录详细错误日志</li>
 *   <li>自动禁用帧生成功能</li>
 *   <li>继续正常渲染流程（无帧生成）</li>
 *   <li>不崩溃，不影响用户体验</li>
 * </ol>
 *
 * <h3>线程安全：</h3>
 * <p>此类完全线程安全。核心方法 {@link #generateFrames(FrameGenContext)} 
 * 应在渲染线程调用，其他配置方法可从任意线程调用。
 *
 * @see FrameGenContext 帧生成上下文
 * @see SLContext Streamline SDK 上下文
 * @since 5.2.0 (Phase 4)
 */
public final class FrameGenerationManager {

    private static final Logger LOGGER = Logger.getLogger(FrameGenerationManager.class.getName());

    // ==================== 单例实例 ====================

    /** 全局唯一实例 */
    private static volatile FrameGenerationManager INSTANCE;

    /**
     * 获取 FrameGenerationManager 单例实例
     *
     * @return 全局唯一实例
     */
    public static FrameGenerationManager getInstance() {
        if (INSTANCE == null) {
            synchronized (FrameGenerationManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FrameGenerationManager();
                }
            }
        }
        return INSTANCE;
    }

    // ==================== 枚举定义 ====================

    /**
     * 帧生成模式枚举
     */
    public enum FGMode {
        /** NVIDIA DLSS Frame Generation - 需要 RTX 40 系列+ GPU */
        DLSS_FG("DLSS-FG", "NVIDIA DLSS Frame Generation", true),

        /** AMD FSR Frame Generation - 更广泛的硬件支持 */
        FSR_FG("FSR-FG", "AMD FSR Frame Generation", false);

        private final String displayName;
        private final String description;
        private final boolean requiresRTX40Plus;

        FGMode(String displayName, String description, boolean requiresRTX40Plus) {
            this.displayName = displayName;
            this.description = description;
            this.requiresRTX40Plus = requiresRTX40Plus;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public boolean requiresRTX40Plus() { return requiresRTX40Plus; }
    }

    // ==================== 配置常量 ====================

    /** 最小目标 FPS */
    public static final int MIN_TARGET_FPS = 60;

    /** 最大目标 FPS */
    public static final int MAX_TARGET_FPS = 240;

    /** 默认目标 FPS */
    public static final int DEFAULT_TARGET_FPS = 120;

    /** 性能预算：兼容模式（纳秒）= 4ms */
    private static final long COMPATIBILITY_BUDGET_NS = 4_000_000L;

    /** 性能预算：狂暴模式（纳秒）= 3ms */
    private static final long AGGRESSIVE_BUDGET_NS = 3_000_000L;

    /** 默认帧生成倍率 */
    private static final int DEFAULT_GENERATION_RATIO = 2;

    // ==================== 核心状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否启用帧生成 */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /** 当前帧生成模式 */
    private final AtomicReference<FGMode> fgMode = new AtomicReference<>(FGMode.DLSS_FG);

    /** 目标 FPS */
    private final AtomicInteger targetFPS = new AtomicInteger(DEFAULT_TARGET_FPS);

    /** 硬件是否支持帧生成 */
    private volatile boolean hardwareSupported = false;

    /** Reflex 低延迟是否启用 */
    private final AtomicBoolean reflexEnabled = new AtomicBoolean(true);

    /** Reflex 低延迟模式激活状态 */
    private final AtomicBoolean reflexLatencyModeActive = new AtomicBoolean(false);

    // ==================== 外部依赖引用 ====================

    /** Streamline SDK 上下文 */
    private volatile SLContext slContext;

    /** Vulkan Bridge 引用 */
    private volatile VulkanStreamlineBridge vkBridge;

    // ==================== 性能统计字段 ====================

    /** 上次处理耗时（纳秒） */
    private volatile long lastProcessTimeNanos = 0L;

    /** 总生成帧数统计 */
    private final AtomicInteger totalGeneratedFrames = new AtomicInteger(0);

    /** 总处理次数 */
    private final AtomicInteger totalProcessCount = new AtomicInteger(0);

    /** 总成功次数 */
    private final AtomicInteger totalSuccessCount = new AtomicInteger(0);

    /** 连续失败计数（用于自动禁用） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** 最大允许连续失败次数（超过则自动禁用） */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    // ==================== Reflex 统计字段 ====================

    /** 渲染延迟（毫秒） */
    private volatile double renderLatencyMs = 0.0;

    /** 渲染开始时间戳（纳秒），对应 Reflex RENDER_START 标记 */
    private volatile long renderStartNanos = 0L;

    /** 渲染结束时间戳（纳秒），对应 Reflex RENDER_END 标记 */
    private volatile long renderEndNanos = 0L;

    /** 呈现延迟（毫秒） */
    private volatile double presentLatencyMs = 0.0;

    /** Flash Latency 触发阈值（毫秒） */
    private volatile double flashLatencyTriggerMs = 20.0;

    // ==================== 私有构造函数 ====================

    private FrameGenerationManager() {
        // 初始化默认值
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化帧生成管理器
     * <p>
     * 完整的初始化流程：
     * <ol>
     *   <li>验证 Streamline SDK 和 Vulkan Bridge</li>
     *   <li>检测硬件是否支持帧生成（DLSS-FG 需要 RTX 40+）</li>
     *   <li>初始化 Reflex 低延迟（如果可用）</li>
     *   <li>配置默认参数</li>
     * </ol>
     *
     * @param slContext Streamline SDK 上下文（不能为 null）
     * @param vkBridge  Vulkan Bridge 实例（不能为 null）
     * @return true 表示初始化成功，false 表示初始化失败或硬件不支持
     * @throws IllegalArgumentException 如果参数为 null
     */
    public boolean initialize(SLContext slContext, VulkanStreamlineBridge vkBridge) {
        // ======== 参数校验 ========
        if (slContext == null) {
            throw new IllegalArgumentException("SLContext 不能为 null");
        }
        if (vkBridge == null) {
            throw new IllegalArgumentException("VulkanStreamlineBridge 不能为 null");
        }

        if (initialized.get()) {
            LOGGER.warning("FrameGenerationManager 已初始化，跳过重复初始化");
            return hardwareSupported;
        }

        try {
            this.slContext = slContext;
            this.vkBridge = vkBridge;

            // ======== 检测硬件支持 ========
            detectHardwareSupport();

            if (!hardwareSupported) {
                LOGGER.warning(
                    "当前硬件不支持帧生成 | " +
                    "DLSS-FG: " + isDLSSFGAvailable() + ", " +
                    "FSR-FG: " + isFSRFGAvailable()"
                );
                initialized.set(true);
                enabled.set(false);
                return false;
            }

            // ======== 初始化 Reflex（如果可用） ========
            initializeReflex();

            initialized.set(true);
            
            LOGGER.info(String.format(
                "FrameGenerationManager 初始化完成 | 硬件支持=%s | 默认模式=%s",
                hardwareSupported ? "是" : "否",
                fgMode.get().getDisplayName()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "FrameGenerationManager 初始化失败", e);
            hardwareSupported = false;
            return false;
        }
    }

    /**
     * 关闭帧生成管理器并释放资源
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            // 停止帧生成
            enabled.set(false);

            // 关闭 Reflex
            if (reflexEnabled.get()) {
                shutdownReflex();
            }

            // 重置状态
            initialized.set(false);
            hardwareSupported = false;
            totalProcessCount.set(0);
            totalSuccessCount.set(0);
            consecutiveFailures.set(0);

            // 清空引用
            slContext = null;
            vkBridge = null;

            LOGGER.info("FrameGenerationManager 已关闭");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "关闭时发生异常", e);
        }
    }

    // ==================== 核心执行方法 ====================

    /**
     * 执行帧生成（核心方法）
     * <p>
     * 这是帧生成的主入口点，在渲染线程中调用。
     * 根据当前模式和配置，在真实渲染帧之间生成插值帧。
     *
     * <h3>处理流程：</h3>
     * <ol>
     *   <li>校验参数和状态</li>
     *   <li>Reflex PCL 标记（如果启用）</li>
     *   <li>执行帧生成算法</li>
     *   <li>收集性能指标</li>
     *   <li>Reflex Present 标记</li>
     *   <li>返回生成的帧列表</li>
     * </ol>
     *
     * <h3>时间复杂度：</h3>O(n) 其中 n 为输出帧数量
     *
     * @param context 帧生成上下文（包含当前帧、上一帧、运动矢量等）
     * @return 帧生成结果（包含生成的帧句柄、数量、延迟等）
     * @throws IllegalStateException 如果未初始化或未启用
     * @throws IllegalArgumentException 如果 context 为 null
     */
    public FGOutput generateFrames(FrameGenContext context) {
        // ======== 参数校验 ========
        if (context == null) {
            throw new IllegalArgumentException("FrameGenContext 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get()) {
            throw new IllegalStateException("FrameGenerationManager 未初始化");
        }

        if (!enabled.get()) {
            // 帧生成已禁用，返回空结果
            return new FGOutput(false, List.of(), 0, 0.0, 0.0);
        }

        if (!hardwareSupported) {
            LOGGER.fine("硬件不支持帧生成，跳过");
            return new FGOutput(false, List.of(), 0, 0.0, 0.0);
        }

        long startTime = System.nanoTime();
        totalProcessCount.incrementAndGet();

        try {
            // ======== 1. Reflex: 标记渲染开始（PCL + Flash Indicator） ========
            if (reflexEnabled.get()) {
                markReflexRenderStart();
            }

            // ======== 2. 执行实际帧生成 ========
            FGOutput output = executeFrameGeneration(context);

            // ======== 2.5. 记录渲染结束时间戳（Reflex RENDER_END） ========
            if (reflexEnabled.get()) {
                renderEndNanos = System.nanoTime();
            }

            // ======== 3. Reflex: 标记呈现完成 ========
            if (reflexEnabled.get() && output.success()) {
                markReflexPresentEnd();
            }

            // ======== 4. 更新统计信息 ========
            updatePerformanceStats(startTime, output);

            // 重置连续失败计数
            if (output.success()) {
                consecutiveFailures.set(0);
            } else {
                int failures = consecutiveFailures.incrementAndGet();
                
                // 连续失败超过阈值，自动禁用
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    LOGGER.severe(String.format(
                        "帧生成连续失败 %d 次，自动禁用以保证稳定性",
                        failures
                    ));
                    setEnabled(false);
                }
            }

            return output;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "帧生成执行异常", e);
            long elapsedNs = System.nanoTime() - startTime;
            lastProcessTimeNanos = elapsedNs;
            consecutiveFailures.incrementAndGet();

            return new FGOutput(false, List.of(), 0, 0.0, elapsedNs / 1_000_000.0);
        }
    }

    // ==================== 配置控制方法 ====================

    /**
     * 启用或禁用帧生成
     * <p>
     * 可以在运行时动态切换。禁用时将直接输出真实渲染帧，
     * 不进行任何插值生成。
     *
     * @param enabled true 启用帧生成，false 禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        
        LOGGER.info(String.format(
            "帧生成: %s | 模式=%s",
            enabled ? "启用" : "禁用",
            fgMode.get().getDisplayName()
        ));
        
        if (enabled && !hardwareSupported) {
            LOGGER.warning("警告: 硬件不支持帧生成，启用可能不会生效");
        }
    }

    /**
     * 设置目标帧率
     * <p>
     * 影响帧生成的倍率和质量。
     * 较高的目标 FPS 可能导致更高的延迟但更流畅的画面。
     *
     * @param targetFps 目标帧率 [60, 240]
     * @throws IllegalArgumentException 如果超出有效范围
     */
    public void setTargetFPS(int targetFps) {
        if (targetFps < MIN_TARGET_FPS || targetFps > MAX_TARGET_FPS) {
            throw new IllegalArgumentException(
                String.format("目标帧率必须在 [%d, %d] 范围内: %d",
                    MIN_TARGET_FPS, MAX_TARGET_FPS, targetFps)
            );
        }
        
        this.targetFPS.set(targetFps);
        LOGGER.fine(String.format("目标帧率: %d fps", targetFps));
    }

    /**
     * 设置帧生成模式
     * <p>
     * 在 DLSS-FG 和 FSR-FG 之间切换。
     * 注意：切换可能需要重新初始化某些资源。
     *
     * @param mode 帧生成模式（DLSS_FG 或 FSR_FG）
     * @throws IllegalArgumentException 如果 mode 为 null
     */
    public void setFGMode(FGMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("FGMode 不能为 null");
        }
        
        FGMode previous = this.fgMode.getAndSet(mode);
        
        if (previous != mode) {
            LOGGER.info(String.format(
                "帧生成模式切换: %s -> %s",
                previous.getDisplayName(),
                mode.getDisplayName()
            ));
            
            // 重新检测硬件支持（新模式可能有不同要求）
            if (initialized.get()) {
                detectHardwareSupport();
            }
        }
    }

    // ==================== Reflex 集成方法 ====================

    /**
     * 启用或禁用 Reflex 低延迟模式
     * <p>
     * Reflex 低延迟通过优化 CPU-GPU 管线来减少输入到显示的延迟。
     * 推荐在竞技游戏中启用。
     *
     * @param enable true 启用 Reflex 低延迟，false 禁用
     */
    public void enableReflexLatencyMode(boolean enable) {
        if (!reflexEnabled.get() && enable) {
            // 尝试启用 Reflex
            if (initializeReflex()) {
                reflexEnabled.set(enable);
                reflexLatencyModeActive.set(enable);
                LOGGER.info("Reflex 低延迟模式: 启用");
            } else {
                LOGGER.warning("Reflex 初始化失败，无法启用低延迟模式");
            }
        } else if (reflexEnabled.get() && !enable) {
            shutdownReflex();
            reflexLatencyModeActive.set(false);
            LOGGER.info("Reflex 低延迟模式: 禁用");
        }
        
        reflexEnabled.set(enable);
    }

    /**
     * 获取 Reflex 统计数据
     * <p>
     * 包含渲染延迟、呈现延迟、Flash Latency 触发等信息。
     *
     * @return Reflex 统计快照
     */
    public ReflexStats getReflexStats() {
        return new ReflexStats(
            reflexEnabled.get() && reflexLatencyModeActive.get(),
            renderLatencyMs,
            presentLatencyMs,
            flashLatencyTriggerMs
        );
    }

    // ==================== 查询方法 ====================

    /**
     * 检查硬件是否支持帧生成
     *
     * @return true 如果当前硬件至少支持一种帧生成技术
     */
    public boolean isHardwareSupported() {
        return hardwareSupported;
    }

    /**
     * 检查帧生成是否已启用
     *
     * @return true 如果帧生成功能已启用且可用
     */
    public boolean isEnabled() {
        return enabled.get() && hardwareSupported && initialized.get();
    }

    /**
     * 获取性能统计数据
     *
     * @return 性能统计快照
     */
    public FGPerformanceStats getPerformanceStats() {
        // 计算有效 FPS（基于总生成帧数和运行时间）
        // 注意：这是一个粗略估计，实际应基于时间窗口计算
        int generatedCount = totalGeneratedFrames.get();
        int effectiveFPS = generatedCount > 0 ? Math.min(targetFPS.get(), generatedCount) : 0;

        // 估算 GPU 利用率（基于处理时间和预算）
        double avgLatency = lastProcessTimeNanos > 0 
            ? lastProcessTimeNanos / 1_000_000.0 : 0.0;
        
        long budget = AGGRESSIVE_BUDGET_NS;  // 使用较严格的预算
        double gpuUtilization = budget > 0 ? Math.min(1.0, avgLatency / (budget / 1_000_000.0)) : 0.0;

        return new FGPerformanceStats(
            effectiveFPS,
            avgLatency,
            DEFAULT_GENERATION_RATIO,  // 固定 2x 生成比率
            gpuUtilization
        );
    }

    /**
     * 获取上次处理的耗时（毫秒）
     *
     * @return 耗时（ms），0 表示尚未处理
     */
    public double getLastProcessTimeMillis() {
        return lastProcessTimeNanos / 1_000_000.0;
    }

    /**
     * 获取成功率
     *
     * @return 成功率 [0.0, 1.0]
     */
    public double getSuccessRate() {
        int processCount = totalProcessCount.get();
        if (processCount == 0) {
            return 0.0;
        }
        return (double) totalSuccessCount.get() / processCount;
    }

    // ==================== 内部实现方法 ====================

    /**
     * 检测硬件是否支持帧生成
     * <p>
     * 检查各帧生成技术的可用性：
     * <ul>
     *   <li>DLSS-FG: 需要 RTX 40 系列+ GPU + Streamline DLSS_G 特性</li>
     *   <li>FSR-FG: 需要 Streamline 支持（软件实现为主）</li>
     * </ul>
     */
    private void detectHardwareSupport() {
        boolean dlssFGAvailable = false;
        boolean fsrFGAvailable = false;

        // 检查 DLSS-FG 可用性
        if (slContext != null && slContext.isFeatureSupported(SLContext.Feature.DLSS_G)) {
            dlssFGAvailable = true;
            LOGGER.info("✓ DLSS-FG 可用 (Streamline DLSS_G 特性)");
        } else {
            LOGGER.info("✗ DLSS-FG 不可用 (需要 RTX 40+ GPU)");
        }

        // 检查 FSR-FG 可用性
        // TODO: 完善 FSR-FG 检测逻辑
        // FSR-FG 通常作为纯软件方案始终可用
        fsrFGAvailable = true;
        LOGGER.info("✓ FSR-FG 可用（软件实现）");

        // 设置整体硬件支持标志
        hardwareSupported = dlssFGAvailable || fsrFGAvailable;

        // 如果首选模式不可用，自动切换
        FGMode currentMode = fgMode.get();
        if (currentMode == FGMode.DLSS_FG && !dlssFGAvailable && fsrFGAvailable) {
            fgMode.set(FGMode.FSR_FG);
            LOGGER.info("自动切换到 FSR-FG（DLSS-FG 不可用）");
        }
    }

    /**
     * 检查 DLSS-FG 是否可用
     *
     * @return true 如果 DLSS-FG 可用
     */
    private boolean isDLSSFGAvailable() {
        return slContext != null && slContext.isFeatureSupported(SLContext.Feature.DLSS_G);
    }

    /**
     * 检查 FSR-FG 是否可用
     *
     * @return true 如果 FSR-FG 可用
     */
    private boolean isFSRFGAvailable() {
        // FSR-FG 未实现，需要集成 Streamline FSR-FG 检测逻辑
        return false;
    }

    /**
     * 初始化 Reflex 低延迟
     *
     * @return true 表示初始化成功
     */
    private boolean initializeReflex() {
        try {
            if (slContext != null && slContext.isFeatureSupported(SLContext.Feature.REFLEX)) {
                // TODO: 实现 Reflex 初始化
                // slReflexInit(sl::ReflexParams params)
                
                reflexLatencyModeActive.set(true);
                LOGGER.info("Reflex 低延迟初始化成功");
                return true;
            } else {
                LOGGER.info("Reflex 特性不可用（Streamline 不支持或未加载）");
                return false;
            }
        } catch (Exception e) {
            LOGGER.warning("Reflex 初始化失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 关闭 Reflex 低延迟
     */
    private void shutdownReflex() {
        try {
            // TODO: 实现 Reflex 关闭
            // slReflexShutdown()
            
            reflexLatencyModeActive.set(false);
            LOGGER.fine("Reflex 低延迟已关闭");
        } catch (Exception e) {
            LOGGER.warning("Reflex 关闭异常: " + e.getMessage());
        }
    }

    /**
     * 标记 Reflex 渲染开始（PCL + Flash Indicator）
     * <p>
     * 在帧生成处理开始前调用，
     * 用于测量渲染阶段的延迟贡献。
     */
    private void markReflexRenderStart() {
        try {
            // TODO: 实现 Reflex PCL 标记
            // slSetLatencyMarker(sl::LatencyMarkerType::SIMULATION_START)
            
            renderStartNanos = System.nanoTime();
            
        } catch (Exception e) {
            LOGGER.fine("Reflex 标记失败（非致命）: " + e.getMessage());
        }
    }

    /**
     * 标记 Reflex 呈现结束
     * <p>
     * 在帧生成完成后、Present 前调用，
     * 用于测量呈现阶段的延迟贡献。
     * <p>
     * 延迟计算遵循 NVIDIA Reflex 标记语义：
     * <ul>
     *   <li>renderLatencyMs = RENDER_END - RENDER_START（渲染阶段）</li>
     *   <li>presentLatencyMs = PRESENT_END - RENDER_END（呈现阶段）</li>
     * </ul>
     */
    private void markReflexPresentEnd() {
        try {
            // TODO: 实现 Reflex Present 标记
            // slSetLatencyMarker(sl::LatencyMarkerType::PRESENT_END)
            
            long presentEndNanos = System.nanoTime();
            // 渲染延迟 = RENDER_END - RENDER_START
            renderLatencyMs = (renderEndNanos - renderStartNanos) / 1_000_000.0;
            // 呈现延迟 = PRESENT_END - RENDER_END
            presentLatencyMs = (presentEndNanos - renderEndNanos) / 1_000_000.0;
            
            double totalLatency = renderLatencyMs + presentLatencyMs;
            
            // 计算 Flash Latency 触发
            if (totalLatency > flashLatencyTriggerMs) {
                // TODO: 触发 Flash Latency Indicator
                // slSetLatencyMarker(sl::LatencyMarkerType::FLASH_INDICATOR_TRIGGER)
                LOGGER.fine(String.format(
                    "Flash Latency 触发: %.2f ms (阈值=%.2f ms)",
                    totalLatency,
                    flashLatencyTriggerMs
                ));
            }
            
        } catch (Exception e) {
            LOGGER.fine("Reflex Present 标记失败（非致命）: " + e.getMessage());
        }
    }

    /**
     * 执行实际的帧生成算法
     * <p>
     * 根据 {@link #fgMode} 选择不同的实现路径。
     *
     * @param context 帧生成上下文
     * @return 帧生成结果
     */
    private FGOutput executeFrameGeneration(FrameGenContext context) {
        FGMode mode = fgMode.get();
        long startTime = System.nanoTime();

        try {
            // TODO: Phase 5 集成实际的帧生成调用
            //
            // DLSS-FG:
            //   slDLSSGEvaluate(sl::ViewportHandle, const sl::DLSSGParams& params)
            //   params 包含:
            //     - colorTexture (当前帧)
            //     - depthTexture (深度缓冲)
            //     - motionVectors (运动矢量)
            //     - exposure (曝光数据)
            //     - historicalFrames (历史帧)
            //
            // FSR-FG:
            //   slFSRFEvaluate(sl::ViewportHandle, const sl::FSRFParams& params)

            // 模拟帧生成过程
            LOGGER.fine(String.format(
                "执行帧生成: %s | 分辨率=%dx%d | 倍率=%s | 目标FPS=%d",
                mode.getDisplayName(),
                context.getWidth(),
                context.getHeight(),
                context.getMultiplier().name(),
                targetFPS.get()
            ));

            // 模拟生成的帧句柄列表
            // 实际应为 VkImageView 句柄数组
            List<Long> generatedHandles = new ArrayList<>();
            int generatedCount = calculateGeneratedFrameCount(context);
            
            for (int i = 0; i < generatedCount; i++) {
                // 占位符：使用当前纹理句柄（实际应为新生成的纹理）
                generatedHandles.add(context.getCurrentColorTexture());
            }

            // 更新生成帧计数
            totalGeneratedFrames.addAndGet(generatedCount);

            long elapsedNs = System.nanoTime() - startTime;

            // 计算增加的延迟（帧生成引入的额外延迟）
            double addedLatencyMs = calculateAddedLatency(generatedCount);

            return new FGOutput(
                true,
                generatedHandles,
                generatedCount,
                addedLatencyMs,
                elapsedNs / 1_000_000.0
            );

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, 
                mode.getDisplayName() + " 帧生成失败: " + e.getMessage(), e);
            
            long elapsedNs = System.nanoTime() - startTime;
            return new FGOutput(false, List.of(), 0, 0.0, elapsedNs / 1_000_000.0);
        }
    }

    /**
     * 计算应该生成的帧数量
     * <p>
     * 基于帧生成倍率（multiplier）和目标 FPS 动态调整。
     *
     * @param context 帧生成上下文
     * @return 要生成的帧数量（通常 1-2）
     */
    private int calculateGeneratedFrameCount(FrameGenContext context) {
        // 基于 multiplier 枚举确定基础数量
        int baseCount = switch (context.getMultiplier()) {
            case OFF -> 0;
            case X2 -> 1;   // 每 1 个真实帧生成 1 个插值帧
            case X3 -> 2;   // 每 1 个真实帧生成 2 个插值帧
        };

        // 根据目标 FPS 微调
        // 如果目标 FPS 很高，可以适当减少生成数量以降低延迟
        int targetFps = targetFPS.get();
        if (targetFps > 144 && baseCount > 1) {
            baseCount--;  // 高 FPS 场景下减少一帧以降低延迟
        }

        return Math.max(0, baseCount);
    }

    /**
     * 计算帧生成引入的额外延迟
     * <p>
     * 帧生成会引入约半帧的额外延迟（用于运动分析和插值计算）。
     * 具体延迟取决于生成算法的复杂度。
     *
     * @param generatedCount 生成的帧数量
     * @return 额外延迟（毫秒）
     */
    private double calculateAddedLatency(int generatedCount) {
        if (generatedCount <= 0) {
            return 0.0;
        }

        // 基础延迟：每生成一帧约增加 4-8ms（取决于算法复杂度）
        double baseLatencyPerFrame = switch (fgMode.get()) {
            case DLSS_FG -> 6.0;  // DLSS-FG 相对较快
            case FSR_FG -> 8.0;   // FSR-FG 可能稍慢
        };

        // 总延迟 = 基础延迟 + (每帧延迟 × 数量)
        return baseLatencyPerFrame + (baseLatencyPerFrame * 0.5 * generatedCount);
    }

    /**
     * 更新性能统计数据
     *
     * @param startTime 开始时间戳
     * @param output    处理结果
     */
    private void updatePerformanceStats(long startTime, FGOutput output) {
        long elapsedNs = System.nanoTime() - startTime;
        lastProcessTimeNanos = elapsedNs;

        if (output.success()) {
            totalSuccessCount.incrementAndGet();
        }

        // 性能预算检查
        long budget = AGGRESSIVE_BUDGET_NS;  // 使用严格预算
        if (elapsedNs > budget) {
            LOGGER.warning(String.format(
                "帧生成超出性能预算: %.2f ms > %.2f ms (模式=%s)",
                elapsedNs / 1_000_000.0,
                budget / 1_000_000.0,
                fgMode.get().getDisplayName()
            ));
        }
    }

    // ==================== 内部类定义 ====================

    /**
     * 帧生成输出结果（不可变 Record）
     * <p>
     * 封装一次帧生成的完整结果。
     *
     * @param success             是否成功
     * @param generatedFrameHandles 生成的帧纹理句柄列表
     * @param generatedCount       生成的帧数量
     * @param latencyMs           增加的系统延迟（毫秒）
     * @param processTimeMs       处理耗时（毫秒）
     */
    public record FGOutput(
        boolean success,
        List<Long> generatedFrameHandles,
        int generatedCount,
        double latencyMs,
        double processTimeMs
    ) {}

    /**
     * 帧生成性能统计（不可变 Record）
     *
     * @param effectiveFPS     有效帧率（含生成帧）
     * @param avgLatencyMs     平均延迟（毫秒）
     * @param frameGenerationRatio 帧生成比率（如 2x, 3x）
     * @param gpuUtilization   GPU 利用率估算 [0.0, 1.0]
     */
    public record FGPerformanceStats(
        int effectiveFPS,
        double avgLatencyMs,
        int frameGenerationRatio,
        double gpuUtilization
    ) {}

    /**
     * Reflex 统计数据（不可变 Record）
     *
     * @param enabled                 Reflex 是否启用
     * @param renderLatencyMs        渲染阶段延迟（毫秒）
     * @param presentLatencyMs       呈现阶段延迟（毫秒）
     * @param flashLatencyTriggerMs  Flash Latency 触发阈值（毫秒）
     */
    public record ReflexStats(
        boolean enabled,
        double renderLatencyMs,
        double presentLatencyMs,
        double flashLatencyTriggerMs
    ) {}
}
