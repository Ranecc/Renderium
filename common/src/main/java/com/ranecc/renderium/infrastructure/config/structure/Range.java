// Renderium - 数值范围约束类
// 用于 IntegerOption 的值域验证和滑块控制

package com.renderium.config.structure;

import java.util.Objects;

/**
 * 整数范围约束。
 *
 * <p>定义一个整数值的有效区间，用于：
 * <ul>
 *   <li>{@link IntegerOption} 的静态值域限制</li>
 *   <li>UI 滑块控件的最小/最大步进配置</li>
 *   <li>动态验证器的基础约束</li>
 * </ul>
 *
 * <h2>不变性</h2>
 * <p>此类是不可变的，所有字段在构造后无法修改。
 * 可安全地在多线程间共享实例。
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 渲染距离：2-32 区块，步进 1
 * Range renderDistance = new Range(2, 32, 1);
 *
 * // 最大帧率：10-360 FPS，步进 10
 * Range fpsLimit = new Range(10, 360, 10);
 *
 * // LOD 偏移：-50 到 +50，步进 1（允许负值）
 * Range lodBias = new Range(-50, 50, 1);
 * </pre>
 *
 * <h3>验证行为</h3>
 * <ul>
 *   <li>{@link #clamp(int)} - 将值裁剪到 [min, max] 范围内</li>
 *   <li>{@link #validate(int)} - 验证值是否在范围内并对齐到步进</li>
 *   <li>{@link #snapToStep(int)} - 将值对齐到最近的步进倍数</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see IntegerOption
 * @see ValidatorProvider
 */
public final class Range {

    /** 允许的最小值（包含） */
    private final int min;

    /** 允许的最大值（包含） */
    private final int max;

    /** 步进值（相邻两个有效值的差值） */
    private final int step;

    /**
     * 创建一个新的范围约束。
     *
     * @param min  最小值（必须 ≤ max）
     * @param max  最大值（必须 ≥ min）
     * @param step 步进值（必须 > 0，且 (max-min) 必须能被 step 整除或接近整除）
     * @throws IllegalArgumentException 若参数不合法
     *
     * <h4>构造函数验证规则</h4>
     * <pre>
     * // 合法构造
     * new Range(0, 100, 1)      ✓ 标准范围
     * new Range(10, 360, 10)    ✓ 固定步进
     * new Range(-50, 50, 1)     ✓ 允许负数
     *
     * // 非法构造
     * new Range(100, 0, 1)      ✗ min > max
     * new Range(0, 100, 0)      ✗ step 为 0
     * new Range(0, 100, -5)     ✗ step 为负
     * </pre>
     */
    public Range(int min, int max, int step) {
        if (min > max) {
            throw new IllegalArgumentException(
                String.format("Range min (%d) cannot be greater than max (%d)", min, max)
            );
        }
        if (step <= 0) {
            throw new IllegalArgumentException(
                String.format("Range step must be positive, got: %d", step)
            );
        }

        this.min = min;
        this.max = max;
        this.step = step;
    }

    /**
     * 获取范围的最小值。
     *
     * @return 最小值（包含）
     */
    public int getMin() {
        return this.min;
    }

    /**
     * 获取范围的最大值。
     *
     * @return 最大值（包含）
     */
    public int getMax() {
        return this.max;
    }

    /**
     * 获取范围的步进值。
     *
     * @return 步进值（始终 > 0）
     */
    public int getStep() {
        return this.step;
    }

    /**
     * 将给定值裁剪（clamp）到 [min, max] 范围内。
     *
     * <p>不进行步进对齐，只确保值不超出边界。
     * 典型用途：用户输入原始值后的初步校验。
     *
     * @param value 待裁剪的值
     * @return      裁剪后的值，保证 min ≤ result ≤ max
     *
     * <h4>示例</h4>
     * <pre>
     * Range r = new Range(0, 100, 10);
     * r.clamp(-5)   → 0      （低于下界，返回 min）
     * r.clamp(150)  → 100    （高于上界，返回 max）
     * r.clamp(55)   → 55     （在范围内，原样返回）
     * </pre>
     */
    public int clamp(int value) {
        return Math.max(this.min, Math.min(this.max, value));
    }

    /**
     * 验证并规范化给定的值。
     *
     * <p>执行两步操作：
     * <ol>
     *   <li>先裁剪到 [min, max] 范围</li>
     *   <li>再对齐到最近的步进倍数</li>
     * </ol>
     *
     * @param value 待验证的值
     * @return      验证并规范化后的有效值
     *
     * <h4>示例</h4>
     * <pre>
     * Range r = new Range(0, 100, 10);
     * r.validate(-5)   → 0      （裁剪到 min=0）
     * r.validate(55)   → 60     （对齐到 60，最接近 55 的步进倍数）
     * r.validate(97)   → 100    （对齐到 100，不超过 max）
     * r.validate(94)   → 90     （对齐到 90，向下取整）
     * </pre>
     */
    public int validate(int value) {
        int clamped = this.clamp(value);
        return this.snapToStep(clamped);
    }

    /**
     * 将值对齐到最近的步进倍数。
     *
     * <p>采用"四舍五入"策略：选择距离最近的步进倍数；
     * 如果正好在中间（如 step=10 时值为 45），向上取整。
     *
     * <p><b>注意</b>：此方法不进行范围裁剪，调用者应先调用 {@link #clamp(int)}。
     *
     * @param value 已在 [min, max] 范围内的值
     * @return      对齐到步进倍数的值
     */
    int snapToStep(int value) {
        if (this.step == 1) {
            return value;  // 步进为 1 时无需对齐
        }

        int remainder = (value - this.min) % this.step;
        if (remainder == 0) {
            return value;  // 已经对齐
        }

        // 四舍五入：余数超过步进的一半则向上取整
        if (remainder * 2 >= this.step) {
            value += (this.step - remainder);
        } else {
            value -= remainder;
        }

        // 二次检查：确保不超过上界
        return Math.min(value, this.max);
    }

    /**
     * 检查给定值是否在此范围内（不考虑步进）。
     *
     * @param value 待检查的值
     * @return      true 表示 min ≤ value ≤ max
     */
    public boolean contains(int value) {
        return value >= this.min && value <= this.max;
    }

    /**
     * 获取范围内的有效值数量。
     *
     * @return 有效值的个数，公式：(max - min) / step + 1
     */
    public int size() {
        return (this.max - this.min) / this.step + 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Range range)) return false;
        return this.min == range.min &&
               this.max == range.max &&
               this.step == range.step;
    }

    @Override
    public int hashCode() {
        return Objects.hash(min, max, step);
    }

    @Override
    public String toString() {
        return String.format("Range[min=%d, max=%d, step=%d]", this.min, this.max, this.step);
    }
}
