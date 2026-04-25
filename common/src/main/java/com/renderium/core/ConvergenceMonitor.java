// ============================================================
// ConvergenceMonitor - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.quality.ConvergenceMonitor
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (quality)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.ConvergenceMonitor;
//     ConvergenceMonitor monitor = new ConvergenceMonitor();
//
//   新代码（推荐迁移）:
//     import com.renderium.core.quality.ConvergenceMonitor;
//     ConvergenceMonitor monitor = new ConvergenceMonitor();
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.quality.ConvergenceMonitor as NewConvergenceMonitor;

/**
 * 四维迭代式渲染收敛监控器（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有调用均委托给
 * {@link com.renderium.core.quality.ConvergenceMonitor}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.quality.ConvergenceMonitor}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 3.0.0
 * @see com.renderium.core.quality.ConvergenceMonitor
 */
@Deprecated(since = "6.0", forRemoval = true)
public class ConvergenceMonitor {

    /** 委托目标实例（新位置的实现类） */
    private final NewConvergenceMonitor delegate;

    /**
     * @deprecated 使用 {@code new com.renderium.core.quality.ConvergenceMonitor()} 替代
     */
    @Deprecated
    public ConvergenceMonitor() {
        this.delegate = new NewConvergenceMonitor();
    }

    /**
     * @deprecated 使用 {@code new com.renderium.core.quality.ConvergenceMonitor(...)} 替代
     */
    @Deprecated
    public ConvergenceMonitor(
        float convergenceThreshold,
        int windowSize,
        double epsilonEnergy,
        double epsilonMotion,
        double epsilonGradient,
        double sigmaMax
    ) {
        this.delegate = new NewConvergenceMonitor(
            convergenceThreshold, windowSize, epsilonEnergy,
            epsilonMotion, epsilonGradient, sigmaMax
        );
    }

    // ==================== 委托公共 API ====================

    /** 更新图像能量指标（委托） */
    @Deprecated
    public void updateEnergy(double energy) { delegate.updateEnergy(energy); }

    /** 更新光流/位移幅度指标（委托） */
    @Deprecated
    public void updateMotion(double motionMagnitude) { delegate.updateMotion(motionMagnitude); }

    /** 更新梯度范数指标（委托） */
    @Deprecated
    public void updateGradient(double gradientNorm) { delegate.updateGradient(gradientNorm); }

    /** 更新残差噪声标准差指标（委托） */
    @Deprecated
    public void updateResidual(double residualStddev) { delegate.updateResidual(residualStddev); }

    /** 检查是否已达到收敛条件（委托） */
    @Deprecated
    public boolean hasConverged() { return delegate.hasConverged(); }

    /** 获取当前综合收敛置信度（委托） */
    @Deprecated
    public float getConfidence() { return delegate.getConfidence(); }

    /** 获取各维度的独立收敛指数（委托） */
    @Deprecated
    public double[] getDimensionIndices() { return delegate.getDimensionIndices(); }

    /** 获取触发收敛时的迭代编号（委托） */
    @Deprecated
    public long getConvergenceIteration() { return delegate.getConvergenceIteration(); }

    /** 重置所有状态（委托） */
    @Deprecated
    public void reset() { delegate.reset(); }

    /** 接收外部收敛验证通知（委托） */
    @Deprecated
    public void notifyExternalConvergence(String source) { delegate.notifyExternalConvergence(source); }

    /** 获取总更新次数（委托） */
    @Deprecated
    public long getTotalUpdates() { return delegate.getTotalUpdates(); }

    /** 获取完整的状态摘要（委托） */
    @Deprecated
    public String getStatusSummary() { return delegate.getStatusSummary(); }
}
