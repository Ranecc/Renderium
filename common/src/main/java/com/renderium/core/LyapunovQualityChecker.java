// ============================================================
// LyapunovQualityChecker - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.quality.LyapunovQualityChecker
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (quality)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.LyapunovQualityChecker;
//     LyapunovQualityChecker checker = new LyapunovQualityChecker();
//
//   新代码（推荐迁移）:
//     import com.renderium.core.quality.LyapunovQualityChecker;
//     LyapunovQualityChecker checker = new LyapunovQualityChecker();
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.quality.LyapunovQualityChecker as NewLyapunovQualityChecker;

/**
 * Lyapunov 无参考质量检验器（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有调用均委托给
 * {@link com.renderium.core.quality.LyapunovQualityChecker}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.quality.LyapunovQualityChecker}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 1.3
 * @see com.renderium.core.quality.LyapunovQualityChecker
 */
@Deprecated(since = "6.0", forRemoval = true)
public class LyapunovQualityChecker {

    /** 委托目标实例（新位置的实现类） */
    private final NewLyapunovQualityChecker delegate;

    /**
     * 默认阈值系数常量（委托）
     *
     * @see NewLyapunovQualityChecker#DEFAULT_THRESHOLD_RATIO
     */
    public static final float DEFAULT_THRESHOLD_RATIO = NewLyapunovQualityChecker.DEFAULT_THRESHOLD_RATIO;

    /**
     * 最小绝对阈值常量（委托）
     *
     * @see NewLyapunovQualityChecker#MIN_ABSOLUTE_THRESHOLD
     */
    public static final float MIN_ABSOLUTE_THRESHOLD = NewLyapunovQualityChecker.MIN_ABSOLUTE_THRESHOLD;

    /**
     * @deprecated 使用 {@code new com.renderium.core.quality.LyapunovQualityChecker()} 替代
     */
    @Deprecated
    public LyapunovQualityChecker() {
        this.delegate = new NewLyapunovQualityChecker();
    }

    /**
     * @deprecated 使用 {@code new com.renderium.core.quality.LyapunovQualityChecker(thresholdRatio)} 替代
     * @param thresholdRatio 阈值系数 (0.0 ~ 1.0)
     */
    @Deprecated
    public LyapunovQualityChecker(float thresholdRatio) {
        this.delegate = new NewLyapunovQualityChecker(thresholdRatio);
    }

    // ==================== 委托公共 API ====================

    /**
     * 计算图像帧的梯度能量（委托）
     *
     * @param frame 帧数据
     * @return 梯度能量值
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public float computeGradientEnergy(RenderiumCore.TextureHolder frame) {
        return delegate.computeGradientEnergy(frame);
    }

    /**
     * 计算梯度能量（完整实现版本，委托）
     *
     * @param pixelData RGBA 像素数据
     * @param width      图像宽度
     * @param height     图像高度
     * @return 梯度能量值
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public float computeGradientEnergy(int[] pixelData, int width, int height) {
        return delegate.computeGradientEnergy(pixelData, width, height);
    }

    /**
     * Lyapunov 条件检验（委托）
     *
     * @param energyBefore 处理前的梯度能量
     * @param energyAfter  处理后的梯度能量
     * @return 验证结果对象
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public NewLyapunovQualityChecker.ValidationResult validateQuality(float energyBefore, float energyAfter) {
        return delegate.validateQuality(energyBefore, energyAfter);
    }

    /**
     * 配置阈值系数（委托）
     *
     * @param threshold 阈值系数
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public void setThreshold(float threshold) {
        delegate.setThreshold(threshold);
    }

    /**
     * 获取当前阈值系数（委托）
     *
     * @return 当前阈值系数
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public float getThreshold() {
        return delegate.getThreshold();
    }

    /**
     * 获取上次验证结果（委托）
     *
     * @return 上次的验证结果
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public NewLyapunovQualityChecker.ValidationResult getLastValidationResult() {
        return delegate.getLastValidationResult();
    }

    /**
     * 重置质量检验器基线状态（委托）
     *
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public void resetBaseline() {
        delegate.resetBaseline();
    }
}
