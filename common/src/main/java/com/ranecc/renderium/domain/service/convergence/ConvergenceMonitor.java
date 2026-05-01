// 迁移自: snapshot-1.1.0: com\ranecc\renderium\domain\service\convergence\ConvergenceMonitor.java
// 迁移目标: com.ranecc.renderium.domain.service.convergence
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变
//
// 【Batch 1.3 去重评估 - 2026-05-01】
// 源文件: com.renderium.core.quality.ConvergenceMonitor (594行, Core层完整版)
// 目标文件: com.ranecc.renderium.domain.service.convergence.ConvergenceMonitor (315行, Domain层简化版)
//
// 版本差异:
//   ✅ Domain版采用4维EMA模型: [deltaEnergy, gradientNorm, residual, stepSize]
//   ⚠️ Core版采用4维模型:      [energy(I_U), motion(I_z), gradient(I_g), residual(I_σ)]
//   ✅ Domain版API更简洁: check(), updateDeltaEnergy(), updateStepSize()
//   ⚠️ Core版API更详细:     hasConverged(), updateEnergy(), updateMotion()
//
// 去重决策:
//   ✓ 保留Domain层简化版作为主要实现（无外部依赖，纯算法）
//   ✓ Core层完整版保留在原位置作为fallback（包含更多文档和诊断功能）
//   ✓ 两版本可共存，通过import路径区分使用场景
//
package com.ranecc.renderium.domain.service.convergence;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 四维迭代式渲染收敛监控器（Domain 层纯净版）
 * <p>
 * 从 core 层迁移，移除所有 FFI/MC 依赖，
 * 保留纯粹的领域收敛判定逻辑。
 *
 * <h3>四维 EMA 平滑模型</h3>
 * <table border="1">
 *   <tr><th>维度</th><th>物理意义</th><th>默认灵敏度 ε</th></tr>
 *   <tr><td>deltaEnergy</td><td>图像能量变化率</td><td>1e-4</td></tr>
 *   <tr><td>gradientNorm</td><td>梯度结构稳定性</td><td>1e-3</td></tr>
 *   <tr><td>residual</td><td>残差噪声水平</td><td>0.05</td></tr>
 *   <tr><td>stepSize</td><td>迭代步长变化</td><td>1e-4</td></tr>
 * </table>
 *
 * <h3>综合置信度公式</h3>
 * <pre>{@code
 * C_conf = (I_ΔE + I_g + I_σ + I_s) / 4
 * }</pre>
 *
 * @see com.ranecc.renderium.domain.service.quality.LyapunovQualityChecker
 */
public final class ConvergenceMonitor {

    private static final Logger LOGGER = Logger.getLogger(ConvergenceMonitor.class.getName());

    /** 默认收敛置信度阈值 */
    public static final float DEFAULT_THRESHOLD = 0.95f;

    /** 默认滑动窗口大小 */
    public static final int DEFAULT_WINDOW_SIZE = 10;

    /** DeltaEnergy 灵敏度 */
    private static final double EPSILON_ENERGY = 1e-4;

    /** GradientNorm 灵敏度 */
    private static final double EPSILON_GRADIENT = 1e-3;

    /** Residual 灵敏度 */
    private static final double EPSILON_RESIDUAL = 0.05;

    /** StepSize 灵敏度 */
    private static final double EPSILON_STEP = 1e-4;

    /**
     * 单维度 EMA 跟踪器
     */
    private static final class EmaTracker {
        private double currentValue;
        private double previousValue;
        private double referenceValue;
        private final double[] deltaWindow;
        private int writeIndex;
        private int count;
        private final double epsilon;

        EmaTracker(int windowSize, double epsilon) {
            this.deltaWindow = new double[windowSize];
            this.writeIndex = 0;
            this.count = 0;
            this.currentValue = 0.0;
            this.previousValue = 0.0;
            this.referenceValue = 0.0;
            this.epsilon = epsilon;
        }

        synchronized void update(double value) {
            this.previousValue = this.currentValue;
            this.currentValue = value;

            if (this.count == 0) {
                this.referenceValue = Math.max(Math.abs(value), 1e-10);
            }

            double delta = Math.abs(value - this.previousValue);
            double normalizedDelta = delta / (epsilon * Math.max(this.referenceValue, 1e-10));

            this.deltaWindow[this.writeIndex] = normalizedDelta;
            this.writeIndex = (this.writeIndex + 1) % this.deltaWindow.length;
            if (this.count < this.deltaWindow.length) {
                this.count++;
            }
        }

        synchronized double getConvergenceIndex() {
            if (this.count == 0) return 0.0;

            double sumDelta = 0.0;
            for (int i = 0; i < this.count; i++) {
                sumDelta += this.deltaWindow[i];
            }
            double avgDelta = sumDelta / this.count;

            return Math.exp(-avgDelta);
        }

        synchronized void reset() {
            this.currentValue = 0.0;
            this.previousValue = 0.0;
            this.referenceValue = 0.0;
            this.writeIndex = 0;
            this.count = 0;
        }

        synchronized double getCurrentValue() { return currentValue; }
    }

    // ==================== 实例字段 ====================

    private final EmaTracker energyTracker;
    private final EmaTracker gradientTracker;
    private final EmaTracker residualTracker;
    private final EmaTracker stepTracker;

    private final float convergenceThreshold;
    private long totalUpdates;
    private volatile boolean convergedFlag;
    private long convergenceIteration;

    /**
     * 创建收敛监控器（使用默认配置）
     */
    public ConvergenceMonitor() {
        this(DEFAULT_THRESHOLD, DEFAULT_WINDOW_SIZE);
    }

    /**
     * 创建自定义配置的收敛监控器
     *
     * @param threshold 收敛阈值 (0, 1]
     * @param windowSize 滑动窗口大小（必须 >= 2）
     * @throws IllegalArgumentException 若参数无效
     */
    public ConvergenceMonitor(float threshold, int windowSize) {
        if (threshold <= 0.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("阈值必须在 (0, 1] 范围内: " + threshold);
        }
        if (windowSize < 2) {
            throw new IllegalArgumentException("窗口大小必须 >= 2: " + windowSize);
        }

        this.convergenceThreshold = threshold;
        this.energyTracker = new EmaTracker(windowSize, EPSILON_ENERGY);
        this.gradientTracker = new EmaTracker(windowSize, EPSILON_GRADIENT);
        this.residualTracker = new EmaTracker(windowSize, EPSILON_RESIDUAL);
        this.stepTracker = new EmaTracker(windowSize, EPSILON_STEP);
        this.totalUpdates = 0;
        this.convergedFlag = false;
        this.convergenceIteration = -1;
    }

    /**
     * 更新 deltaEnergy 维度
     *
     * 【方法参数】
     * @param value double - 当前能量变化值（必须 >= 0）
     */
    public void updateDeltaEnergy(double value) {
        if (value < 0) throw new IllegalArgumentException("能量不能为负数: " + value);
        energyTracker.update(value);
        checkConvergence();
        totalUpdates++;
    }

    /**
     * 更新 gradientNorm 维度
     *
     * @param value double - 当前梯度范数（必须 >= 0）
     */
    public void updateGradientNorm(double value) {
        if (value < 0) throw new IllegalArgumentException("梯度不能为负数: " + value);
        gradientTracker.update(value);
    }

    /**
     * 更新 residual 维度
     *
     * @param value double - 当前残差标准差（必须 >= 0）
     */
    public void updateResidual(double value) {
        if (value < 0) throw new IllegalArgumentException("残差不能为负数: " + value);
        residualTracker.update(value);
    }

    /**
     * 更新 stepSize 维度
     *
     * @param value double - 当前步长（必须 >= 0）
     */
    public void updateStepSize(double value) {
        if (value < 0) throw new IllegalArgumentException("步长不能为负数: " + value);
        stepTracker.update(value);
    }

    /**
     * 检查是否已收敛
     *
     * 【返回值】
     * @return boolean - 已收敛返回 true
     */
    public boolean check() {
        return convergedFlag;
    }

    /**
     * 获取当前综合收敛置信度
     *
     * 【返回值】
     * @return float - 置信度 [0.0, 1.0]
     */
    public float getConfidence() {
        double ie = energyTracker.getConvergenceIndex();
        double ig = gradientTracker.getConvergenceIndex();
        double ir = residualTracker.getConvergenceIndex();
        double is = stepTracker.getConvergenceIndex();

        return (float)((ie + ig + ir + is) / 4.0);
    }

    /**
     * 获取各维度独立收敛指数
     *
     * 【返回值】
     * @return double[] - [I_ΔE, I_g, I_σ, I_s]
     */
    public double[] getDimensionIndices() {
        return new double[] {
            energyTracker.getConvergenceIndex(),
            gradientTracker.getConvergenceIndex(),
            residualTracker.getConvergenceIndex(),
            stepTracker.getConvergenceIndex()
        };
    }

    /**
     * 重置所有状态
     */
    public void reset() {
        energyTracker.reset();
        gradientTracker.reset();
        residualTracker.reset();
        stepTracker.reset();
        totalUpdates = 0;
        convergedFlag = false;
        convergenceIteration = -1;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("ConvergenceMonitor 已重置");
        }
    }

    /**
     * 接收外部收敛通知
     *
     * @param source String - 来源标识
     */
    public void notifyExternalConvergence(String source) {
        this.convergedFlag = true;
        this.convergenceIteration = this.totalUpdates;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("[Convergence] 外部确认: source=%s, iter=%d", source, this.convergenceIteration));
        }
    }

    /** 获取总更新次数 */
    public long getTotalUpdates() { return totalUpdates; }

    /** 获取收敛迭代编号 */
    public long getConvergenceIteration() { return convergenceIteration; }

    /**
     * 获取状态摘要
     *
     * 【返回值】
     * @return String - 格式化状态信息
     */
    public String getStatusSummary() {
        double[] indices = getDimensionIndices();
        return String.format(
            "ConvergenceMonitor{converged=%s(iter=%d), conf=%.4f(th=%.2f), dims=[%.4f,%.4f,%.4f,%.4f], updates=%d}",
            convergedFlag ? "YES" : "NO",
            convergenceIteration,
            getConfidence(), convergenceThreshold,
            indices[0], indices[1], indices[2], indices[3],
            totalUpdates
        );
    }

    /**
     * 内部收敛检查
     */
    private void checkConvergence() {
        if (convergedFlag) return;
        if (totalUpdates < DEFAULT_WINDOW_SIZE) return;

        float confidence = getConfidence();
        if (confidence > convergenceThreshold) {
            convergedFlag = true;
            convergenceIteration = totalUpdates;

            LOGGER.info(String.format("收敛触发: iter=%d, conf=%.4f(>%.2f)",
                convergenceIteration, confidence, convergenceThreshold));
        }
    }
}
