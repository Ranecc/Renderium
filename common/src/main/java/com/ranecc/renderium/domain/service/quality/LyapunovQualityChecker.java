// 迁移自: snapshot-1.1.0: com\ranecc\renderium\domain\service\quality\LyapunovQualityChecker.java
// 迁移目标: com.ranecc.renderium.domain.service.quality
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变

package com.ranecc.renderium.domain.service.quality;
import com.ranecc.renderium.domain.model.FrameData;

import java.util.logging.Logger;

/**
 * Lyapunov 无参考质量检验器（Domain 层纯净版）
 * <p>
 * 从 core 层迁移，移除所有 FFI/MC/NanGuardShader 依赖，
 * 保留纯粹的梯度能量评估领域逻辑。
 *
 * <h3>理论基础</h3>
 * <pre>{@code
 * V(I) = ∫‖∇I‖² dz  (图像梯度能量)
 * ΔV = V_after - V_before ≤ threshold
 * }</pre>
 *
 * <h3>判定规则</h3>
 * <ul>
 *   <li>ΔV ≥ -threshold → PASS（质量可接受）</li>
 *   <li>ΔV &lt; -threshold → FAIL（严重质量退化）</li>
 * </ul>
 */
public final class LyapunovQualityChecker {

    private static final Logger LOGGER = Logger.getLogger(LyapunovQualityChecker.class.getName());

    /** 默认阈值系数 */
    public static final float DEFAULT_THRESHOLD_RATIO = 0.1f;

    /** 最小绝对阈值 */
    public static final float MIN_ABSOLUTE_THRESHOLD = 1.0f;

    /** Sobel X 方向卷积核 (3×3) */
    private static final int[] SOBEL_X = {-1, 0, 1, -2, 0, 2, -1, 0, 1};

    /** Sobel Y 方向卷积核 (3×3) */
    private static final int[] SOBEL_Y = {-1, -2, -1, 0, 0, 0, 1, 2, 1};

    /** Sobel 归一化因子 */
    private static final float SOBEL_NORM = 8.0f;

    /** 阈值系数（可配置） */
    private volatile float thresholdRatio = DEFAULT_THRESHOLD_RATIO;

    /** 上次验证结果缓存 */
    private volatile ValidationResult lastResult;

    /**
     * 创建质量检验器（使用默认配置）
     */
    public LyapunovQualityChecker() {
        this.lastResult = null;
    }

    /**
     * 创建自定义阈值的质量检验器
     *
     * @param thresholdRatio float - 阈值系数 (0.0 ~ 1.0)
     * @throws IllegalArgumentException 若参数无效
     */
    public LyapunovQualityChecker(float thresholdRatio) {
        setThreshold(thresholdRatio);
        this.lastResult = null;
    }

    /**
     * 计算图像帧的梯度能量 V = ∫‖∇I‖² dz
     * <p>
     * 当前为存根实现，返回 0.0f。
     * 实际集成时需接入像素数据源来计算真实梯度能量值。
     *
     * 【方法参数】
     * @param frameData Object - 帧数据对象（类型待集成确定）
     *
     * 【返回值】
     * @return float - 梯度能量值，当前固定返回 0.0f
     */
    public float computeGradientEnergy(Object frameData) {
        if (frameData == null) {
            LOGGER.warning("computeGradientEnergy: 帧数据为 null");
            return 0.0f;
        }

        if (frameData instanceof FrameData) {
            LOGGER.fine("computeGradientEnergy: FrameData 实例暂不支持像素数据提取，返回 0.0f");
        }
        return 0.0f;
    }

    /**
     * 计算梯度能量（完整 CPU 实现）
     * <p>
     * 接受原始像素数据，执行完整的 Sobel 卷积计算。
     *
     * @param pixelData int[] - RGBA 像素数组
     * @param width int - 图像宽度
     * @param height int - 图像高度
     * @return float - 梯度能量值
     * @throws IllegalArgumentException 若参数无效
     */
    public float computeGradientEnergy(int[] pixelData, int width, int height) {
        if (pixelData == null || pixelData.length == 0)
            throw new IllegalArgumentException("像素数据不能为空");
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("尺寸必须为正数: " + width + "x" + height);
        if (pixelData.length != width * height)
            throw new IllegalArgumentException("数据长度与尺寸不匹配");

        float[] gray = toGrayscale(pixelData);
        float energy = sobelEnergy(gray, width, height);

        return clamp(energy, 0.0f, Float.MAX_VALUE);
    }

    /**
     * Lyapunov 质量评估
     * <p>
     * 基于 ΔV 判定渲染输出质量是否可接受。
     *
     * 【方法参数】
     * @param energyBefore float - 处理前梯度能量
     * @param energyAfter float - 处理后梯度能量
     *
     * 【返回值】
     * @return ValidationResult - 包含通过/失败状态及详细信息
     */
    public ValidationResult evaluate(float energyBefore, float energyAfter) {
        energyBefore = clamp(energyBefore, 0.0f, Float.MAX_VALUE);
        energyAfter = clamp(energyAfter, 0.0f, Float.MAX_VALUE);

        float maxEnergy = Math.max(energyBefore, energyAfter);
        float adaptiveThreshold = Math.max(MIN_ABSOLUTE_THRESHOLD, thresholdRatio * maxEnergy);

        float deltaV = energyAfter - energyBefore;
        boolean passed = (deltaV >= -adaptiveThreshold);

        String message;
        if (passed) {
            message = deltaV > 0
                ? String.format("PASS: 能量提升 %.2f (%.2f->%.2f), 阈值=%.2f", deltaV, energyBefore, energyAfter, adaptiveThreshold)
                : String.format("PASS: 轻微衰减 %.2f 在容忍范围 (%.2f->%.2f)", deltaV, energyBefore, energyAfter);
        } else {
            message = String.format("FAIL: 退化 ΔV=%.2f (%.2f->%.2f), 阈值=%.2f, 降幅=%.1f%%",
                deltaV, energyBefore, energyAfter, adaptiveThreshold,
                energyBefore > 0 ? (deltaV / energyBefore) * 100 : 0);
        }

        ValidationResult result = new ValidationResult(passed, deltaV, adaptiveThreshold, energyBefore, energyAfter, message);
        this.lastResult = result;

        if (passed) LOGGER.fine(message); else LOGGER.warning(message);

        return result;
    }

    /**
     * 计算综合质量分数
     * <p>
     * 将 Lyapunov 判定结果映射到 [0, 1] 质量分数。
     *
     * 【方法参数】
     * @param energyBefore float - 处理前能量
     * @param energyAfter float - 处理后能量
     *
     * 【返回值】
     * @return float - 质量分数 [0.0, 1.0]，1.0 为最高质量
     */
    public float computeQuality(float energyBefore, float energyAfter) {
        if (energyBefore <= 0) return 0.5f;

        float deltaV = energyAfter - energyBefore;
        float ratio = deltaV / energyBefore;

        if (ratio >= 0) return Math.min(1.0f, 0.7f + ratio * 0.3f);
        if (ratio >= -thresholdRatio) return 0.7f + (ratio / thresholdRatio) * 0.4f;
        return Math.max(0.0f, 0.3f + (ratio / thresholdRatio) * 0.3f);
    }

    /**
     * 配置阈值系数
     *
     * @param threshold float - 阈值系数 (0.0, 1.0]
     * @throws IllegalArgumentException 若参数无效
     */
    public void setThreshold(float threshold) {
        if (Float.isNaN(threshold) || Float.isInfinite(threshold))
            throw new IllegalArgumentException("阈值不能为 NaN 或 Inf");
        if (threshold <= 0.0f || threshold > 1.0f)
            throw new IllegalArgumentException("阈值必须在 (0, 1] 范围内: " + threshold);
        this.thresholdRatio = threshold;
    }

    /** 获取当前阈值系数 */
    public float getThreshold() { return thresholdRatio; }

    /** 获取上次验证结果 */
    public ValidationResult getLastResult() { return lastResult; }

    /** 重置基线状态 */
    public void resetBaseline() { this.lastResult = null; }

    // ==================== 私有辅助方法 ====================

    /**
     * RGBA 转灰度图（ITU-R BT.601）
     */
    private float[] toGrayscale(int[] pixels) {
        float[] gray = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
        }
        return gray;
    }

    /**
     * Sobel 梯度能量计算
     */
    private float sobelEnergy(float[] gray, int w, int h) {
        float total = 0.0f;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float gx = applyKernel(gray, x, y, w, SOBEL_X);
                float gy = applyKernel(gray, x, y, w, SOBEL_Y);
                gx = clamp(gx, -255f, 255f);
                gy = clamp(gy, -255f, 255f);
                total += gx * gx + gy * gy;
            }
        }
        return total / (SOBEL_NORM * SOBEL_NORM);
    }

    /**
     * 应用 3×3 卷积核
     */
    private float applyKernel(float[] img, int x, int y, int w, int[] kernel) {
        float sum = 0.0f;
        for (int ky = -1; ky <= 1; ky++) {
            for (int kx = -1; kx <= 1; kx++) {
                sum += img[(y + ky) * w + (x + kx)] * kernel[(ky + 1) * 3 + (kx + 1)];
            }
        }
        return sum;
    }

    /**
     * 数值钳位
     */
    private float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    // ==================== 内部结果类 ====================

    /**
     * Lyapunov 质量验证结果
     */
    public static final class ValidationResult {
        private final boolean passed;
        private final float deltaV;
        private final float threshold;
        private final float energyBefore;
        private final float energyAfter;
        private final String message;

        ValidationResult(boolean passed, float deltaV, float threshold,
                         float energyBefore, float energyAfter, String message) {
            this.passed = passed;
            this.deltaV = deltaV;
            this.threshold = threshold;
            this.energyBefore = energyBefore;
            this.energyAfter = energyAfter;
            this.message = message;
        }

        /** 是否通过质量检验 */
        public boolean isPassed() { return passed; }

        /** 获取能量变化量 ΔV */
        public float getDeltaV() { return deltaV; }

        /** 获取判定阈值 */
        public float getThreshold() { return threshold; }

        /** 获取处理前能量 */
        public float getEnergyBefore() { return energyBefore; }

        /** 获取处理后能量 */
        public float getEnergyAfter() { return energyAfter; }

        /** 获取描述消息 */
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return String.format("ValidationResult{passed=%s, deltaV=%.2f, thresh=%.2f, energy=[%.2f->%.2f]}",
                passed, deltaV, threshold, energyBefore, energyAfter);
        }
    }
}
