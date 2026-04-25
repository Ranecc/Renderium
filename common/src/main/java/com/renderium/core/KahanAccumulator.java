// ============================================================
// KahanAccumulator - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.math.KahanAccumulator
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (math)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.KahanAccumulator;
//     KahanAccumulator acc = new KahanAccumulator();
//
//   新代码（推荐迁移）:
//     import com.renderium.core.math.KahanAccumulator;
//     KahanAccumulator acc = new KahanAccumulator();
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.math.KahanAccumulator as NewKahanAccumulator;

/**
 * Kahan 补偿求和累加器（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有调用均委托给
 * {@link com.renderium.core.math.KahanAccumulator}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.math.KahanAccumulator}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 1.0
 * @see com.renderium.core.math.KahanAccumulator
 */
@Deprecated(since = "6.0", forRemoval = true)
public final class KahanAccumulator {

    /** 委托目标实例（新位置的实现类） */
    private final NewKahanAccumulator delegate;

    /**
     * @deprecated 使用 {@code new com.renderium.core.math.KahanAccumulator()} 替代
     */
    @Deprecated
    public KahanAccumulator() {
        this.delegate = new NewKahanAccumulator();
    }

    /**
     * @deprecated 使用 {@code new com.renderium.core.math.KahanAccumulator(initialValue)} 替代
     * @param initialValue 初始总和值
     */
    @Deprecated
    public KahanAccumulator(double initialValue) {
        this.delegate = new NewKahanAccumulator(initialValue);
    }

    // ==================== 核心累加 API 委托 ====================

    /**
     * 将一个值添加到累加器中（使用 Kahan 补偿算法，委托）
     *
     * @param value 要添加的值
     * @return this（支持链式调用）
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public KahanAccumulator add(double value) {
        delegate.add(value);
        return this;
    }

    /**
     * 将另一个 Kahan 累加器的结果合并到此累加器（委托）
     *
     * @param other 要合并的另一个累加器实例
     * @return this（支持链式调用）
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public KahanAccumulator add(KahanAccumulator other) {
        delegate.add(other.delegate);
        return this;
    }

    // ==================== 结果查询 API 委托 ====================

    /** 获取当前的累加总和（委托） */
    @Deprecated
    public double getSum() { return delegate.getSum(); }

    /** 获取已添加元素的个数（委托） */
    @Deprecated
    public long getCount() { return delegate.getCount(); }

    /** 计算当前的平均值（委托） */
    @Deprecated
    public double getAverage() { return delegate.getAverage(); }

    /** 获取当前的补偿值（委托） */
    @Deprecated
    public double getCompensation() { return delegate.getCompensation(); }

    // ==================== 状态管理 API 委托 ====================

    /** 重置累加器到初始状态（委托） */
    @Deprecated
    public void reset() { delegate.reset(); }

    /** 查询累加器是否为空（委托） */
    @Deprecated
    public boolean isEmpty() { return delegate.isEmpty(); }

    // ==================== Object 方法委托 ====================

    /** 生成累加器状态的字符串表示（委托） */
    @Override
    @Deprecated
    public String toString() { return delegate.toString(); }

    /** 判断两个累加器是否相等（委托） */
    @Override
    @Deprecated
    public boolean equals(Object obj) { return delegate.equals(obj); }

    /** 计算哈希码（委托） */
    @Override
    @Deprecated
    public int hashCode() { return delegate.hashCode(); }

    // ==================== 静态工厂方法委托 ====================

    /**
     * 使用 Kahan 算法对数组进行高精度求和（静态便捷方法，委托）
     *
     * @param values 要求和的数组
     * @return 高精度的总和
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static double sum(double[] values) {
        return NewKahanAccumulator.sum(values);
    }

    /**
     * 使用 Kahan 算法计算数组的高精度平均值（静态便捷方法，委托）
     *
     * @param values 要求平均值的数组
     * @return 高精度的算术平均值
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static double average(double[] values) {
        return NewKahanAccumulator.average(values);
    }
}
