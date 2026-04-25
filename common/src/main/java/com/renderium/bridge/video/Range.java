package com.renderium.bridge.video;

/**
 * 整数范围验证器（不可变记录类）
 * <p>
 * 定义整数选项的有效取值范围，包括最小值、最大值和步进值。
 * 当验证失败时（值超出范围或不满足步进要求），将使用默认值替代。
 *
 * <h3>使用场景：</h3>
 * <ul>
 *   <li>渲染距离：Range(2, 32, 1) - 范围 2-32，步进 1</li>
 *   <li>视场角：Range(60, 120, 1) - 范围 60-120，步进 1</li>
 *   <li>粒子倍率：Range(0, 200, 10) - 范围 0-200%，步进 10%</li>
 * </ul>
 *
 * <h4>构造约束：</h4>
 * <ul>
 *   <li>min ≤ max（最小值不能大于最大值）</li>
 *   <li>step > 0（步进值必须为正数）</li>
 * </ul>
 *
 * @param min  最小值（包含）
 * @param max  最大值（包含）
 * @param step 步进增量（有效值的间隔）
 *
 * @see IntegerOptionBuilder#setRange(int, int, int)
 * @see IntegerOptionBuilder#setRange(Range)
 * @since 1.0.0
 */
public record Range(int min, int max, int step) {

    /**
     * 规范化构造函数（带验证逻辑）
     * <p>
     * 确保创建的 Range 实例满足基本约束条件。
     * 如果参数不合法，抛出 {@link IllegalArgumentException}。
     *
     * @param min  最小值（必须 ≤ max）
     * @param max  最大值（必须 ≥ min）
     * @param step 步进值（必须 > 0）
     * @throws IllegalArgumentException 如果 min > max 或 step ≤ 0
     */
    public Range {
        if (min > max) {
            throw new IllegalArgumentException(
                    String.format("Min (%d) must be less than or equal to max (%d)", min, max)
            );
        }
        if (step <= 0) {
            throw new IllegalArgumentException(
                    String.format("Step (%d) must be greater than 0", step)
            );
        }
    }

    /**
     * 验证给定值是否在此范围内且符合步进要求
     *
     * @param value 待验证的整数值
     * @return true 如果值有效（在范围内且符合步进），false 否则
     */
    public boolean isValid(int value) {
        if (value < min || value > max) {
            return false;
        }
        // 检查是否符合步进：(value - min) % step == 0
        return (value - min) % step == 0;
    }

    /**
     * 将给定值规范化到最近的合法值
     * <p>
     * 如果值超出范围，则钳位到 [min, max]；
     * 如果不符合步进，则向下舍入到最近的有效值。
     *
     * @param value 待规范化的值
     * @return 规范化后的合法值
     */
    public int clamp(int value) {
        // 先钳位到范围
        value = Math.max(min, Math.min(max, value));
        // 再调整到符合步进
        int offset = (value - min) % step;
        if (offset != 0) {
            value -= offset;
        }
        return value;
    }

    /**
     * 获取范围内的有效值数量
     *
     * @return 有效值的总数
     */
    public int size() {
        return (max - min) / step + 1;
    }
}
