// ============================================================
// PhaseTransitionDetector - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.phase.PhaseTransitionDetector
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (phase)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.PhaseTransitionDetector;
//     PhaseTransitionDetector detector = new PhaseTransitionDetector();
//
//   新代码（推荐迁移）:
//     import com.renderium.core.phase.PhaseTransitionDetector;
//     PhaseTransitionDetector detector = new PhaseTransitionDetector();
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.phase.PhaseTransitionDetector as NewPhaseTransitionDetector;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 相变检测器（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有调用均委托给
 * {@link com.renderium.core.phase.PhaseTransitionDetector}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.phase.PhaseTransitionDetector}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 2.3
 * @see com.renderium.core.phase.PhaseTransitionDetector
 */
@Deprecated(since = "6.0", forRemoval = true)
public class PhaseTransitionDetector {

    /** 委托目标实例（新位置的实现类） */
    private final NewPhaseTransitionDetector delegate;

    // ==================== 常量委托 ====================

    /** Type A 场景切换阈值（委托） */
    public static final float INTENSITY_THRESHOLD_A =
        NewPhaseTransitionDetector.INTENSITY_THRESHOLD_A;

    /** Type B 光照突变阈值（委托） */
    public static final float INTENSITY_THRESHOLD_B =
        NewPhaseTransitionDetector.INTENSITY_THRESHOLD_B;

    /** Type C 运动模式阈值（委托） */
    public static final float INTENSITY_THRESHOLD_C =
        NewPhaseTransitionDetector.INTENSITY_THRESHOLD_C;

    /** Type D 周期性干扰的最小强度阈值（委托） */
    public static final float INTENSITY_THRESHOLD_D_MIN =
        NewPhaseTransitionDetector.INTENSITY_THRESHOLD_D_MIN;

    /** 默认滑动窗口大小（委托） */
    public static final int DEFAULT_SMOOTHING_WINDOW =
        NewPhaseTransitionDetector.DEFAULT_SMOOTHING_WINDOW;

    /** 默认诊断历史容量（委托） */
    public static final int DEFAULT_DIAGNOSTIC_HISTORY_SIZE =
        NewPhaseTransitionDetector.DEFAULT_DIAGNOSTIC_HISTORY_SIZE;

    /**
     * @deprecated 使用 {@code new com.renderium.core.phase.PhaseTransitionDetector()} 替代
     */
    @Deprecated
    public PhaseTransitionDetector() {
        this.delegate = new NewPhaseTransitionDetector();
    }

    /**
     * @deprecated 使用 {@code new com.renderium.core.phase.PhaseTransitionDetector(...)} 替代
     */
    @Deprecated
    public PhaseTransitionDetector(int smoothingWindow, int diagnosticHistorySize) {
        this.delegate = new NewPhaseTransitionDetector(smoothingWindow, diagnosticHistorySize);
    }

    // ==================== 委托公共 API ====================

    /** 检测相变（完整版：接受两帧像素数据，委托） */
    @Deprecated
    public NewPhaseTransitionDetector.PhaseType detectTransition(int[] currentFrame, int[] previousFrame) {
        return delegate.detectTransition(currentFrame, previousFrame);
    }

    /** 检测相变（简化版：直接接受预计算的帧差异指标，委托） */
    @Deprecated
    public NewPhaseTransitionDetector.PhaseType detectTransition(float frameDifferenceMetric) {
        return delegate.detectTransition(frameDifferenceMetric);
    }

    /** 注册相变回调（委托） */
    @Deprecated
    public void registerCallback(NewPhaseTransitionDetector.PhaseType type,
                                  Consumer<com.renderium.core.phase.PhaseEvent> action) {
        delegate.registerCallback(type, action);
    }

    /** 注销相变回调（委托） */
    @Deprecated
    public void unregisterCallback(NewPhaseTransitionDetector.PhaseType type,
                                    Consumer<com.renderium.core.phase.PhaseEvent> action) {
        delegate.unregisterCallback(type, action);
    }

    /** 清除指定类型的所有回调（委托） */
    @Deprecated
    public void clearCallbacks(NewPhaseTransitionDetector.PhaseType type) {
        delegate.clearCallbacks(type);
    }

    /** 清除所有类型的所有回调（委托） */
    @Deprecated
    public void clearAllCallbacks() {
        delegate.clearAllCallbacks();
    }

    /** 获取最近一次的原始强度指标（委托） */
    @Deprecated
    public float getLastRawIntensity() { return delegate.getLastRawIntensity(); }

    /** 获取最近一次的平滑强度指标（委托） */
    @Deprecated
    public float getLastIntensity() { return delegate.getLastIntensity(); }

    /** 获取最近一次检测到的相变类型（委托） */
    @Deprecated
    public NewPhaseTransitionDetector.PhaseType getLastDetectedPhase() {
        return delegate.getLastDetectedPhase();
    }

    /** 获取总处理帧数（委托） */
    @Deprecated
    public int getTotalFrameCount() { return delegate.getTotalFrameCount(); }

    /** 获取指定类型的检测次数（委托） */
    @Deprecated
    public int getDetectionCount(NewPhaseTransitionDetector.PhaseType type) {
        return delegate.getDetectionCount(type);
    }

    /** 获取所有类型的检测次数统计（委托） */
    @Deprecated
    public Map<NewPhaseTransitionDetector.PhaseType, Integer> getDetectionCounts() {
        return delegate.getDetectionCounts();
    }

    /** 生成诊断报告（委托） */
    @Deprecated
    public String getDiagnosticReport() { return delegate.getDiagnosticReport(); }

    /** 获取最近的诊断记录列表（委托） */
    @Deprecated
    public List<NewPhaseTransitionDetector.DiagnosticRecord> getRecentDiagnostics(int maxCount) {
        return delegate.getRecentDiagnostics(maxCount);
    }

    /** 重置检测器状态（委托） */
    @Deprecated
    public void reset() { delegate.reset(); }
}
