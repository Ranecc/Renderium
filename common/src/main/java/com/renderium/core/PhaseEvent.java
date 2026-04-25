// ============================================================
// PhaseEvent - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.phase.PhaseEvent
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (phase)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.PhaseEvent;
//     PhaseEvent event = new PhaseEvent(type, intensity, ...);
//
//   新代码（推荐迁移）:
//     import com.renderium.core.phase.PhaseEvent;
//     PhaseEvent event = new PhaseEvent(type, intensity, ...);
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.phase.PhaseEvent as NewPhaseEvent;
import com.renderium.core.phase.PhaseTransitionDetector;
import java.time.Instant;

/**
 * 相变事件数据类（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有调用均委托给
 * {@link com.renderium.core.phase.PhaseEvent}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.phase.PhaseEvent}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 2.3
 * @see com.renderium.core.phase.PhaseEvent
 */
@Deprecated(since = "6.0", forRemoval = true)
public final class PhaseEvent {

    /** 委托目标实例（新位置的实现类） */
    private final NewPhaseEvent delegate;

    /**
     * 创建相变事件实例（委托）
     *
     * @param phaseType    检测到的相变类型
     * @param intensity    强度指标值
     * @param diffMean     帧间差异均值
     * @param diffVariance 帧间差异方差
     * @param frameNumber  当前帧号
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public PhaseEvent(PhaseTransitionDetector.PhaseType phaseType,
                      float intensity,
                      float diffMean,
                      float diffVariance,
                      int frameNumber) {
        this.delegate = new NewPhaseEvent(phaseType, intensity, diffMean, diffVariance, frameNumber);
    }

    // ==================== 委托 Getter 方法 ====================

    /** 获取相变类型（委托） */
    @Deprecated
    public PhaseTransitionDetector.PhaseType getPhaseType() { return delegate.getPhaseType(); }

    /** 获取强度指标（委托） */
    @Deprecated
    public float getIntensity() { return delegate.getIntensity(); }

    /** 获取帧间差异均值（委托） */
    @Deprecated
    public float getDiffMean() { return delegate.getDiffMean(); }

    /** 获取帧间差异方差（委托） */
    @Deprecated
    public float getDiffVariance() { return delegate.getDiffVariance(); }

    /** 获取检测时间戳（委托） */
    @Deprecated
    public Instant getTimestamp() { return delegate.getTimestamp(); }

    /** 获取帧号（委托） */
    @Deprecated
    public int getFrameNumber() { return delegate.getFrameNumber(); }

    /** 判断是否为严重相变（委托） */
    @Deprecated
    public boolean isSevere() { return delegate.isSevere(); }

    /** 获取相变类型的中文描述（委托） */
    @Deprecated
    public String getTypeDescription() { return delegate.getTypeDescription(); }

    // ==================== 委托 Object 方法 ====================

    /** 判断两个事件是否相等（委托） */
    @Override
    @Deprecated
    public boolean equals(Object obj) { return delegate.equals(obj); }

    /** 计算哈希码（委托） */
    @Override
    @Deprecated
    public int hashCode() { return delegate.hashCode(); }

    /** 返回事件的字符串表示（委托） */
    @Override
    @Deprecated
    public String toString() { return delegate.toString(); }
}
