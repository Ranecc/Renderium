// Renderium - 动态参数配置系统
// 整数参数旋钮实现

package com.ranecc.renderium.feature.pipeline.pipeline.parameter.impl;

import com.ranecc.renderium.None;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 整数参数旋钮 (Int Knob)
 * <p>
 * {@link ParameterKnob} 的整数类型实现，用于表示离散可调的数值参数。
 * 支持范围限制 [min, max] 和运行时热更新。
 *
 * <h2>典型应用场景：</h2>
 * <ul>
 *   <li><b>PCF 采样数</b>：pcfSamples 1~64, default=16</li>
 *   <li><b>AO 采样数</b>：aoSamples 4~32, default=16</li>
 *   <li><b>阴影图分辨率</b>：shadowResolution 512~8192</li>
 *   <li><b>最大迭代次数</b>：maxIterations 1~100</li>
 *   <li><b>级联数量</b>：cascadeCount 1~4</li>
 * </ul>
 *
 * <h3>线程安全保证：</h3>
 * <p>
 * 使用 volatile int 字段存储当前值，保证跨线程可见性。
 * 整数的读写在 Java 中是原子的（JLS 17.7），
 * 配合 volatile 关键字确保内存可见性。
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 使用 Builder 创建整数参数
 * ParameterKnob<Integer> pcfSamples = new IntKnob.Builder("pcf_samples")
 *     .displayName("PCF 采样数")
 *     .description("阴影边缘柔化采样的样本数量，越高越平滑但性能开销越大")
 *     .range(1, 64)
 *     .defaultValue(16)
 *     .category(ParameterKnob.ParameterCategory.SHADOW)
 *     .build();
 *
 * // 运行时修改
 * pcfSamples.setValue(32);
 *
 * // 获取值
 * int samples = pcfSamples.getValue();  // 32
 * }</pre>
 *
 * @see ParameterKnob
 * @see FloatKnob
 * @since 6.0.0
 */
public final class IntKnob implements ParameterKnob<Integer> {

    // ==================== 实例字段 ====================

    /** 参数唯一标识符 */
    private final String id;

    /** 显示名称 */
    private final String displayName;

    /** 参数描述信息 */
    private final String description;

    /** 最小允许值 */
    private final int minValue;

    /** 最大允许值 */
    private final int maxValue;

    /** 默认值 */
    private final int defaultValue;

    /** 参数分类 */
    private final ParameterKnob.ParameterCategory category;

    /**
     * 当前运行时值（volatile 保证跨线程可见性）
     */
    private volatile int currentValue;

    /** 变更监听器列表 */
    private final List<ChangeListener<Integer>> listeners = new CopyOnWriteArrayList<>();

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private IntKnob(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "参数 ID 不能为 null");
        this.displayName = builder.displayName != null ? builder.displayName : builder.id;
        this.description = builder.description != null ? builder.description : "";
        this.minValue = builder.minValue;
        this.maxValue = builder.maxValue;
        this.defaultValue = clamp(builder.defaultValue, this.minValue, this.maxValue);
        this.currentValue = this.defaultValue;
        this.category = builder.category != null ? builder.category : ParameterKnob.ParameterCategory.SHADER;
    }

    // ==================== ParameterKnob 接口实现 ====================

    /**
     * {@inheritDoc}
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
        return displayName;
    }

    /**
     * {@inheritDoc}
     *
     * 【返回值】
     * @return KnobType - 始终返回 INT
     */
    @Override
    public KnobType getType() {
        return KnobType.INT;
    }

    /**
     * 获取当前整数值
     * <p>
     * volatile 读，无锁，高性能。
     *
     * 【返回值】
     * @return Integer - 当前值
     */
    @Override
    public Integer getValue() {
        return currentValue;
    }

    /**
     * 设置新的整数值
     * <p>
     * 值会被自动钳制到 [minValue, maxValue] 范围内。
     *
     * 【方法参数】
     * @param value Integer - 新的参数值（不能为 null）
     *
     * @throws NullPointerException 如果 value 为 null
     */
    @Override
    public void setValue(Integer value) {
        Objects.requireNonNull(value, "参数值不能为 null");
        int oldValue = this.currentValue;
        int newValue = clamp(value, this.minValue, this.maxValue);

        if (oldValue != newValue) {
            this.currentValue = newValue;
            notifyListeners(oldValue, newValue);
        }
    }

    /**
     * 获取有效范围 [min, max]
     *
     * 【返回值】
     * @return Object[] - 长度为 2 的数组 [minValue, maxValue]
     */
    @Override
    public Object[] getRange() {
        return new Object[]{minValue, maxValue};
    }

    /**
     * 获取默认值
     *
     * 【返回值】
     * @return Integer - 默认值
     */
    @Override
    public Integer getDefault() {
        return defaultValue;
    }

    /**
     * 重置为默认值
     */
    @Override
    public void resetToDefault() {
        setValue(defaultValue);
    }

    /**
     * 获取参数分类
     *
     * 【返回值】
     * @return ParameterCategory - 所属分类
     */
    @Override
    public ParameterKnob.ParameterCategory getCategory() {
        return category;
    }

    /**
     * 获取描述信息
     *
     * 【返回值】
     * @return String - 描述文本
     */
    @Override
    public String getDescription() {
        return description;
    }

    /**
     * 检查是否为默认值
     *
     * 【返回值】
     * @return boolean - true 表示未修改
     */
    @Override
    public boolean isDefault() {
        return currentValue == defaultValue;
    }

    /**
     * 注册变更监听器
     *
     * 【方法参数】
     * @param listener ChangeListener&lt;Integer&gt; - 监听器
     */
    @Override
    public void addChangeListener(ChangeListener<Integer> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 移除变更监听器
     *
     * 【方法参数】
     * @param listener ChangeListener&lt;Integer&gt; - 要移除的监听器
     */
    @Override
    public void removeChangeListener(ChangeListener<Integer> listener) {
        listeners.remove(listener);
    }

    // ==================== 整数特有方法 ====================

    /**
     * 获取最小允许值
     *
     * 【返回值】
     * @return int - 最小值
     */
    public int getMinValue() {
        return minValue;
    }

    /**
     * 获取最大允许值
     *
     * 【返回值】
     * @return int - 最大值
     */
    public int getMaxValue() {
        return maxValue;
    }

    /**
     * 获取原始 int 值（避免自动装箱）
     * <p>
     * 性能敏感路径应优先使用此方法。
     *
     * 【返回值】
     * @return int - 原始 int 值
     */
    public int getRawValue() {
        return currentValue;
    }

    /**
     * 值增加指定步进
     * <p>
     * 常用于 UI 的增减按钮操作。
     *
     * 【方法参数】
     * @param delta int - 增量（可为负数）
     */
    public void increment(int delta) {
        setValue(currentValue + delta);
    }

    /**
     * 值 +1
     */
    public void increment() {
        increment(1);
    }

    /**
     * 值 -1
     */
    public void decrement() {
        increment(-1);
    }

    /**
     * 检查是否已达到最大值
     *
     * 【返回值】
     * @return boolean - true 表示当前值为最大值
     */
    public boolean isAtMax() {
        return currentValue >= maxValue;
    }

    /**
     * 检查是否已达到最小值
     *
     * 【返回值】
     * @return boolean - true 表示当前值为最小值
     */
    public boolean isAtMin() {
        return currentValue <= minValue;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 值钳制到有效范围
     *
     * 【方法参数】
     * @param value int - 输入值
     * @param min   int - 最小值
     * @param max   int - 最大值
     *
     * 【返回值】
     * @return int - 钳制后的值
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 通知所有监听器
     *
     * 【方法参数】
     * @param oldValue int - 旧值
     * @param newValue int - 新值
     */
    private void notifyListeners(int oldValue, int newValue) {
        for (ChangeListener<Integer> listener : listeners) {
            try {
                listener.onValueChanged(this, oldValue, newValue);
            } catch (Exception e) {
                Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), e);
            }
        }
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format("IntKnob{id='%s', name='%s', value=%d, range=[%d, %d]}",
                id, displayName, currentValue, minValue, maxValue);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof IntKnob other)) return false;
        return id.equals(other.id) && currentValue == other.currentValue;
    }

    @Override
    public int hashCode() {
        return 31 * id.hashCode() + Integer.hashCode(currentValue);
    }

    // ==================== Builder 模式 ====================

    /**
     * IntKnob 构建器
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * IntKnob knob = new IntKnob.Builder("pcf_samples")
     *     .displayName("PCF 采样数")
     *     .range(1, 64)
     *     .defaultValue(16)
     *     .category(ParameterCategory.SHADOW)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        private final String id;
        private String displayName;
        private String description = "";
        private int minValue = 0;
        private int maxValue = 100;
        private int defaultValue = 50;
        private ParameterKnob.ParameterCategory category = ParameterKnob.ParameterCategory.SHADER;

        /**
         * 构造函数 - 设置参数 ID（必填）
         *
         * @param id String - 唯一标识符
         */
        public Builder(String id) {
            this.id = Objects.requireNonNull(id, "参数 ID 不能为 null");
        }

        public Builder displayName(String name) { this.displayName = name; return this; }
        public Builder description(String desc) { this.description = desc; return this; }

        /**
         * 设置取值范围
         *
         * @param min int - 最小值
         * @param max int - 最大值
         * @return this
         * @throws IllegalArgumentException 如果 min > max
         */
        public Builder range(int min, int max) {
            if (min > max) {
                throw new IllegalArgumentException(
                        String.format("最小值(%d)不能大于最大值(%d)", min, max));
            }
            this.minValue = min;
            this.maxValue = max;
            return this;
        }

        public Builder defaultValue(int value) { this.defaultValue = value; return this; }
        public Builder category(ParameterKnob.ParameterCategory cat) { this.category = cat; return this; }

        /**
         * 构建 IntKnob 实例
         *
         * @return IntKnob - 构建好的实例
         */
        public IntKnob build() {
            return new IntKnob(this);
        }
    }
}
