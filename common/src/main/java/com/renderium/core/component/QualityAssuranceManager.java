// ============================================================
// QualityAssuranceManager - 质量保障管理器
// ============================================================
// 从 RenderiumCore 中提取的独立组件
// 职责：Lyapunov质量检测 + 相变检测 + 自适应精度 + 收敛监控
//
// 功能模块：
//   - LyapunovQualityChecker: 无参考质量检测
//   - PhaseTransitionDetector: 场景切换/光照突变检测
//   - AdaptivePrecisionManager: 四级精度自适应分配
//   - ConvergenceMonitor: 四维收敛状态监控
//
// @see com.renderium.core.quality.LyapunovQualityChecker
// @see com.renderium.core.phase.PhaseTransitionDetector
// @see com.renderium.core.precision.AdaptivePrecisionManager
// @see com.renderium.core.quality.ConvergenceMonitor
// ============================================================

package com.renderium.core.component;

import com.renderium.core.quality.*;
import com.renderium.core.phase.*;
import com.renderium.core.precision.*;

/**
 * 质量保障管理器
 * <p>
 * 统一管理渲染质量相关的所有检测和自适应系统，
 * 包括 Lyapunov 质量检验、相变检测、精度自适应和收敛监控。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>✅ Lyapunov 无参考质量检测</li>
 *   <li>✅ 相变事件检测与回调管理</li>
 *   <li>✅ 自适应精度分配策略</li>
 *   <li>✅ 四维收敛状态监控</li>
 *   <li>❌ 不负责实际帧处理（由 FrameProcessor 处理）</li>
 * </ul>
 *
 * <h3>工作流程</h3>
 * <pre>{@code
 * 每帧循环:
 *   1. onFrameBegin() → 更新收敛监控 + 精度压力
 *   2. processSuperResolution() → Lyapunov 质量检验
 *   3. detectPhaseTransition() → 相变检测
 *   4. 相变回调触发 → 重置历史缓冲/调整参数
 * }</pre>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 5.0 (从 RenderiumCore 拆分)
 */
public final class QualityAssuranceManager {

    /** Lyapunov 无参考质量检验器 */
    private final LyapunovQualityChecker lyapunovChecker;

    /** 相变检测器 */
    private final PhaseTransitionDetector phaseDetector;

    /** 自适应精度管理器 */
    private final AdaptivePrecisionManager adaptivePrecisionManager;

    /** 收敛监控器 */
    private final ConvergenceMonitor convergenceMonitor;

    /** 是否启用 Lyapunov 质量检测 */
    private volatile boolean lyapunovQualityCheckEnabled = true;

    /** 是否启用相变检测 */
    private volatile boolean phaseDetectionEnabled = true;

    /**
     * 创建质量保障管理器（自动初始化所有子组件）
     */
    public QualityAssuranceManager() {
        this.lyapunovChecker = new LyapunovQualityChecker();
        this.phaseDetector = new PhaseTransitionDetector();
        this.adaptivePrecisionManager = new AdaptivePrecisionManager();
        this.convergenceMonitor = new ConvergenceMonitor();

        // 注册默认相变响应回调
        registerDefaultPhaseCallbacks();
    }

    /**
     * 每帧开始时调用（更新监控状态）
     *
     * @param deltaTime 帧间隔时间（秒）
     */
    public void onFrameBegin(float deltaTime) {
        // 更新收敛监控的能量维度
        if (convergenceMonitor != null) {
            convergenceMonitor.updateEnergy(deltaTime);
        }

        // 更新精度管理的资源压力
        if (adaptivePrecisionManager != null) {
            adaptivePrecisionManager.updateResourcePressure(deltaTime);
        }
    }

    /**
     * 执行 Lyapunov 质量检验
     *
     * @param energyBefore 处理前的梯度能量
     * @param processName 处理过程名称
     * @return 是否通过质量检验
     */
    public boolean performLyapunovQualityCheck(float energyBefore, String processName) {
        if (!lyapunovQualityCheckEnabled || lyapunovChecker == null) {
            return true; // 未启用则默认通过
        }

        if (energyBefore <= 0) {
            return true; // 数据无效则跳过
        }

        try {
            float energyAfter = lyapunovChecker.computeGradientEnergy(null); // TODO: 传入纹理数据
            if (energyAfter <= 0) return true;

            LyapunovQualityChecker.ValidationResult result =
                lyapunovChecker.validateQuality(energyBefore, energyAfter);

            if (!result.isPassed()) {
                // 记录警告日志（实际降级逻辑由外部处理器执行）
                System.getLogger(QualityAssuranceManager.class.getName())
                    .log(System.Logger.Level.WARNING,
                        "[LYAPUNOV 质量警报] {0}: deltaV={1}, threshold={2}",
                        processName, result.getDeltaV(), result.getThreshold());
                return false;
            }
            return true;
        } catch (Exception e) {
            return true; // 异常时默认通过，不影响主流程
        }
    }

    /**
     * 执行相变检测
     *
     * @param frameDifferenceMetric 帧差异度量值
     * @return 检测到的相变类型
     */
    public PhaseTransitionDetector.PhaseType detectPhaseTransition(float frameDifferenceMetric) {
        if (!phaseDetectionEnabled || phaseDetector == null) {
            return PhaseTransitionDetector.PhaseType.NONE;
        }

        try {
            return phaseDetector.detectTransition(frameDifferenceMetric);
        } catch (Exception e) {
            return PhaseTransitionDetector.PhaseType.NONE;
        }
    }

    /**
     * 注册默认的相变响应回调
     * <p>
     * 包含四种相变类型的自动响应逻辑：
     * - Type A (场景切换): 清空历史缓冲、重置 SGS/Lyapunov
     * - Type B (光照突变): 调整曝光参数、增大 SGS k 值
     * - Type C (运动模式): 切换光流算法参数
     * - Type D (周期性干扰): 启用时域滤波抑制
     */
    private void registerDefaultPhaseCallbacks() {
        // Type A: 场景切换
        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.SCENE_CHANGE,
            event -> {
                System.getLogger(QualityAssuranceManager.class.getName())
                    .log(System.Logger.Level.INFO,
                        "[相变警报] 场景切换检测到 (frame={0}, intensity={1})",
                        event.getFrameNumber(), event.getIntensity());

                // 重置 Lyapunov 基线
                if (lyapunovChecker != null) {
                    lyapunovChecker.resetBaseline();
                }
            }
        );

        // Type B-D: 其他相变类型的简化处理
        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.LIGHTING_MUTATION,
            event -> System.getLogger(QualityAssuranceManager.class.getName())
                .log(System.Logger.Level.INFO,
                    "[相变信息] 光照突变 (intensity={0})",
                    event.getIntensity())
        );

        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.MOTION_CHANGE,
            event -> {}  // 待集成光流模块
        );

        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.PERIODIC_NOISE,
            event -> {}  // 待集成 TAA 模块
        );
    }

    // ==================== 配置方法 ====================

    public void setLyapunovQualityCheckEnabled(boolean enabled) {
        this.lyapunovQualityCheckEnabled = enabled;
    }

    public void setPhaseDetectionEnabled(boolean enabled) {
        this.phaseDetectionEnabled = enabled;
    }

    public boolean isLyapunovQualityCheckEnabled() {
        return lyapunovQualityCheckEnabled;
    }

    public boolean isPhaseDetectionEnabled() {
        return phaseDetectionEnabled;
    }

    // ==================== Getter 方法 ====================

    public LyapunovQualityChecker getLyapunovChecker() { return lyapunovChecker; }
    public PhaseTransitionDetector getPhaseDetector() { return phaseDetector; }
    public AdaptivePrecisionManager getAdaptivePrecisionManager() { return adaptivePrecisionManager; }
    public ConvergenceMonitor getConvergenceMonitor() { return convergenceMonitor; }
}
