// 迁移自: 1.0.0: com.renderium.core.phase.PhaseEvent
// 迁移目标: com.ranecc.renderium.domain.service.quality
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变


// ============================================================
// 相变事件数据类
// ============================================================
// 封装相变检测结果的完整信息，供回调函数和诊断系统使用
//
// 设计原则：
//   - 不可变对象（Immutable Record），线程安全
//   - 包含检测到的相变类型、强度指标、帧差异统计量
//   - 支持序列化以供日志记录和离线分析
//
// 使用场景：
//   - PhaseTransitionDetector 检测到相变后创建此对象
//   - 通过回调机制传递给注册的监听器
//   - 存储在检测历史中用于诊断报告生成
//
// @see PhaseTransitionDetector
// @see PhaseTransitionDetector.PhaseType
// ============================================================

package com.ranecc.renderium.domain.service.quality;

import com.ranecc.renderium.domain.service.phase.PhaseTransitionDetector;
import java.time.Instant;
import java.util.Objects;

/**
 * 相变事件数据类
 * <p>
 * 不可变记录类，封装单次相变检测的完整结果信息。
 * 当 {@link PhaseTransitionDetector} 检测到场景变化时，
 * 创建此实例并传递给注册的回调监听器。
 *
 * <h2>包含信息</h2>
 * <ul>
 *   <li><b>相变类型</b>: 检测到的四种相变类型之一</li>
 *   <li><b>强度指标</b>: 帧间差异的二阶矩归一化值</li>
 *   <li><b>差异均值</b>: 当前帧与前一帧的像素差异平均值</li>
 *   <li><b>差异方差</b>: 帧间差异的二阶矩（分布离散程度）</li>
 *   <li><b>时间戳</b>: 检测发生的精确时间</li>
 *   <li><b>帧号</b>: 发生相变的帧索引</li>
 * </ul>
 *
 * <h2>线程安全性</h2>
 * <p>
 * 此类为不可变对象（所有字段均为 final），
 * 可安全地在多线程环境中共享和传递。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 在回调中接收事件
 * phaseDetector.registerCallback(PhaseType.SCENE_CHANGE, event -> {
 *     System.out.println("检测到: " + event.getPhaseType());
 *     System.out.println("强度: " + event.getIntensity());
 *     System.out.println("时间: " + event.getTimestamp());
 * });
 * }</pre>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 2.3
 * @see PhaseTransitionDetector
 */
public final class PhaseEvent {

    // ==================== 字段定义 ====================

    /** 检测到的相变类型（枚举值，永不 null） */
    private final PhaseTransitionDetector.PhaseType phaseType;

    /**
     * 相变强度指标（归一化值）
     * <p>
     * 计算公式：intensity = |diff_mean - prev_diff_mean| / (prev_diff_mean + ε)
     * <ul>
     *   <li>范围: [0.0, +∞)</li>
     *   <li>越大表示变化越剧烈</li>
     *   <li>典型阈值：A=0.5, B=0.3, C=0.2</li>
     * </ul>
     */
    private final float intensity;

    /**
     * 帧间差异均值
     * <p>
     * 当前帧与前一帧的绝对像素差异的平均值。
     * 用于判断整体画面变化的幅度。
     */
    private final float diffMean;

    /**
     * 帧间差异方差（二阶矩）
     * <p>
     * 衡量帧间差异分布的离散程度。
     * 高方差表示变化不均匀（如局部光照突变），
     * 低方差表示均匀变化（如全局亮度调整）。
     */
    private final float diffVariance;

    /** 检测发生的时间戳（UTC） */
    private final Instant timestamp;

    /** 发生相变的帧号（从 0 开始递增） */
    private final int frameNumber;

    // ==================== 构造方法 ====================

    /**
     * 创建相变事件实例
     *
     * @param phaseType    检测到的相变类型（不能为 null）
     * @param intensity    强度指标值（非负数）
     * @param diffMean     帧间差异均值
     * @param diffVariance 帧间差异方差
     * @param frameNumber  当前帧号
     * @throws IllegalArgumentException 若参数无效
     */
    public PhaseEvent(PhaseTransitionDetector.PhaseType phaseType,
                      float intensity,
                      float diffMean,
                      float diffVariance,
                      int frameNumber) {
        // 参数校验
        Objects.requireNonNull(phaseType, "相变类型不能为 null");

        if (Float.isNaN(intensity) || Float.isInfinite(intensity)) {
            throw new IllegalArgumentException("强度指标不能为 NaN 或 Inf");
        }
        if (intensity < 0.0f) {
            throw new IllegalArgumentException("强度指标必须为非负数: " + intensity);
        }

        if (Float.isNaN(diffMean) || Float.isInfinite(diffMean)) {
            throw new IllegalArgumentException("差异均值不能为 NaN 或 Inf");
        }

        if (Float.isNaN(diffVariance) || diffVariance < 0.0f) {
            throw new IllegalArgumentException("差异方差必须为非负有限数: " + diffVariance);
        }

        if (frameNumber < 0) {
            throw new IllegalArgumentException("帧号必须为非负数: " + frameNumber);
        }

        // 赋值
        this.phaseType = phaseType;
        this.intensity = intensity;
        this.diffMean = diffMean;
        this.diffVariance = diffVariance;
        this.timestamp = Instant.now();  // 自动记录当前时间
        this.frameNumber = frameNumber;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取相变类型
     *
     * @return 相变类型枚举值（永不 null）
     */
    public PhaseTransitionDetector.PhaseType getPhaseType() {
        return phaseType;
    }

    /**
     * 获取强度指标
     *
     * @return 归一化强度值 [0.0, +∞)
     */
    public float getIntensity() {
        return intensity;
    }

    /**
     * 获取帧间差异均值
     *
     * @return 平均像素差异值
     */
    public float getDiffMean() {
        return diffMean;
    }

    /**
     * 获取帧间差异方差
     *
     * @return 差异分布的方差值（二阶矩）
     */
    public float getDiffVariance() {
        return diffVariance;
    }

    /**
     * 获取检测时间戳
     *
     * @return UTC 时间戳
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * 获取帧号
     *
     * @return 帧索引（从 0 开始）
     */
    public int getFrameNumber() {
        return frameNumber;
    }

    // ==================== Object 方法重写 ====================

    /**
     * 判断两个事件是否相等
     * <p>
     * 基于相变类型、强度、帧号进行相等性判定。
     * 时间戳不参与相等性比较（因为同一事件可能在不同时间被复制）。
     *
     * @param obj 要比较的对象
     * @return true 表示相等
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PhaseEvent that = (PhaseEvent) obj;
        return Float.compare(that.intensity, intensity) == 0 &&
               Float.compare(that.diffMean, diffMean) == 0 &&
               Float.compare(that.diffVariance, diffVariance) == 0 &&
               frameNumber == that.frameNumber &&
               phaseType == that.phaseType;
    }

    /**
     * 计算哈希码
     *
     * @return 对象哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(phaseType, intensity, diffMean, diffVariance, frameNumber);
    }

    /**
     * 返回事件的字符串表示
     * <p>
     * 格式示例：
     * <pre>PhaseEvent{type=SCENE_CHANGE, intensity=0.65, mean=128.5, variance=2500.3, frame=1234}</pre>
     *
     * @return 格式化的字符串表示
     */
    @Override
    public String toString() {
        return String.format(
            "PhaseEvent{type=%s, intensity=%.4f, mean=%.2f, variance=%.2f, frame=%d, time=%s}",
            phaseType,
            intensity,
            diffMean,
            diffVariance,
            frameNumber,
            timestamp.toString()
        );
    }

    // ==================== 辅助方法 ====================

    /**
     * 判断是否为严重相变
     * <p>
     * 严重相变定义为 Type A（场景切换）或高强度事件。
     *
     * @return true 如果是严重相变
     */
    public boolean isSevere() {
        return phaseType == PhaseTransitionDetector.PhaseType.SCENE_CHANGE ||
               intensity > 0.5f;
    }

    /**
     * 获取相变类型的中文描述
     *
     * @return 人类可读的类型名称
     */
    public String getTypeDescription() {
        switch (phaseType) {
            case SCENE_CHANGE:
                return "场景切换/镜头剪切";
            case LIGHTING_MUTATION:
                return "光照突变";
            case MOTION_CHANGE:
                return "运动模式变化";
            case PERIODIC_NOISE:
                return "周期性干扰/闪烁";
            case NONE:
                return "无相变";
            default:
                return "未知类型";
        }
    }
}
