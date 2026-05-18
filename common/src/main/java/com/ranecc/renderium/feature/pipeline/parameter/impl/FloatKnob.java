// Renderium - 动态参数配置系统
// 浮点数参数旋钮实现

package com.ranecc.renderium.feature.pipeline.parameter.impl;


import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import com.ranecc.renderium.feature.pipeline.parameter.ParameterKnob;

/**
 * 浮点数参数旋钮 (Float Knob)
 * <p>
 * {@link ParameterKnob} 的浮点数类型实现，用于表示连续可调的数值参数。
 * 支持范围限制 [min, max]、步进值（step）和运行时热更新。
 *
 * <h2>典型应用场景：</h2>
 * <ul>
 *   <li><b>光照强度</b>：环境光强度 0.0~2.0</li>
 *   <li><b>法线强度</b>：normalStrength 0.1~2.0, step=0.1</li>
 *   <li><b>泛光强度</b>：bloomIntensity 0.0~3.0</li>
 *   <li><b>曝光值</b>：exposure 0.1~5.0</li>
 *   <li><b>雾密度</b>：fogDensity 0.0~0.05</li>
 * </ul>
 *
 * <h3>线程安全保证：</h3>
 * <p>
 * 使用 volatile 字段存储当前值，确保多线程间的可见性。
 * 读操作无锁，写操作通过 volatile 写保证原子性。
 * 适用于渲染线程读取 + UI 线程写入的典型场景。
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 使用 Builder 创建浮点参数
 * ParameterKnob<Float> normalStrength = new FloatKnob.Builder("normal_strength")
 *     .displayName("法线贴图强度")
 *     .description("控制法线贴图对表面细节的影响程度")
 *     .range(0.1f, 2.0f)
 *     .step(0.1f)
 *     .defaultValue(1.0f)
 *     .category(ParameterKnob.ParameterCategory.SHADER)
 *     .build();
 *
 * // 运行时修改（立即生效）
 * normalStrength.setValue(1.5f);
 *
 * // 获取当前值
 * float value = normalStrength.getValue();  // 1.5f
 *
 * // 重置为默认值
 * normalStrength.resetToDefault();
 * }</pre>
 *
 * @see ParameterKnob
 * @see IntKnob
 * @since 6.0.0
 */
public final class FloatKnob implements ParameterKnob<Float> {

    // ==================== 实例字段 ====================

    /** 参数唯一标识符 */
    private final String id;

    /** 显示名称 */
    private final String displayName;

    /** 参数描述信息 */
    private final String description;

    /** 最小允许值 */
    private final float minValue;

    /** 最大允许值 */
    private final float maxValue;

    /** 调整步进值（UI 滑块每次变化的增量） */
    private final float step;

    /** 默认值 */
    private final float defaultValue;

    /** 参数分类 */
    private final ParameterKnob.ParameterCategory category;

    /**
     * 当前运行时值（volatile 保证跨线程可见性）
     * <p>
     * 渲染线程读取此值时总能看到 UI 线程的最新写入。
     */
    private volatile float currentValue;

    /** 变更监听器列表（CopyOnWriteArrayList 保证遍历安全） */
    private final List<ChangeListener<Float>> listeners = new CopyOnWriteArrayList<>();

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     * <p>
     * 通过 Builder 模式创建实例。
     *
     * @param builder 构建器实例
     */
    private FloatKnob(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "参数 ID 不能为 null");
        this.displayName = builder.displayName != null ? builder.id : builder.displayName;
        this.description = builder.description != null ? builder.description : "";
        this.minValue = builder.minValue;
        this.maxValue = builder.maxValue;
        this.step = Math.max(builder.step, 0.0001f);  // 步进值最小为 0.0001
        this.defaultValue = clamp(builder.defaultValue, this.minValue, this.maxValue);
        this.currentValue = this.defaultValue;  // 初始化为默认值
        this.category = builder.category != null ? builder.category : ParameterKnob.ParameterCategory.SHADER;
    }

    // ==================== ParameterKnob 接口实现 ====================

    /**
     * {@inheritDoc}
     *
     * 【返回值】
     * @return String - 唯一标识符（kebab-case 格式）
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * {@inheritDoc}
     *
     * 【返回值】
     * @return String - 显示名称
     */
    @Override
    public String getDisplayName() {
        return displayName;
    }

    /**
     * {@inheritDoc}
     *
     * 【返回值】
     * @return KnobType - 始终返回 FLOAT
     */
    @Override
    public KnobType getType() {
        return KnobType.FLOAT;
    }

    /**
     * 获取当前浮点值
     * <p>
     * volatile 读，无锁，高性能。渲染线程可安全调用。
     *
     * 【返回值】
     * @return Float - 当前值（已限制在 [min, max] 范围内）
     */
    @Override
    public Float getValue() {
        return currentValue;
    }

    /**
     * 设置新的浮点值
     * <p>
     * 值会被自动钳制到 [minValue, maxValue] 范围内。
     * 设置成功后会通知所有注册的变更监听器。
     * volatile 写保证渲染线程立即可见。
     *
     * 【方法参数】
     * @param value Float - 新的参数值（不能为 null）
     *
     * @throws NullPointerException 如果 value 为 null
     */
    @Override
    public void setValue(Float value) {
        Objects.requireNonNull(value, "参数值不能为 null");
        float oldValue = this.currentValue;
        float newValue = clamp(value, this.minValue, this.maxValue);

        // 仅在值实际发生变化时更新并通知
        if (Float.compare(oldValue, newValue) != 0) {
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
     * @return Float - 构造时指定的默认值
     */
    @Override
    public Float getDefault() {
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
     * 检查当前值是否为默认值
     *
     * 【返回值】
     * @return boolean - true 表示未修改
     */
    @Override
    public boolean isDefault() {
        return Float.compare(currentValue, defaultValue) == 0;
    }

    /**
     * 注册变更监听器
     *
     * 【方法参数】
     * @param listener ChangeListener&lt;Float&gt; - 监听器
     */
    @Override
    public void addChangeListener(ChangeListener<Float> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 移除变更监听器
     *
     * 【方法参数】
     * @param listener ChangeListener&lt;Float&gt; - 要移除的监听器
     */
    @Override
    public void removeChangeListener(ChangeListener<Float> listener) {
        listeners.remove(listener);
    }

    // ==================== 浮点数特有方法 ====================

    /**
     * 获取最小允许值
     *
     * 【返回值】
     * @return float - 最小值
     */
    public float getMinValue() {
        return minValue;
    }

    /**
     * 获取最大允许值
     *
     * 【返回值】
     * @return float - 最大值
     */
    public float getMaxValue() {
        return maxValue;
    }

    /**
     * 获取步进值
     *
     * 【返回值】
     * @return float - 每次调整的最小增量
     */
    public float getStep() {
        return step;
    }

    /**
     * 获取原始 float 值（避免自动装箱开销）
     * <p>
     * 性能敏感路径应优先使用此方法而非 getValue()，
     * 避免每帧产生 Float 对象装箱。
     *
     * 【返回值】
     * @return float - 原始 float 值
     */
    public float getRawValue() {
        return currentValue;
    }

    /**
     * 将值按步进值对齐（量化到最近的合法步进点）
     * <p>
     * 用于 UI 滑块的离散化调整。
     *
     * 【方法参数】
     * @param value float - 输入值
     *
     * 【返回值】
     * @return float - 对齐后的值
     */
    public float snapToStep(float value) {
        float snapped = Math.round((value - minValue) / step) * step + minValue;
        return clamp(snapped, minValue, maxValue);
    }

    /**
     * 计算当前值在范围中的归一化位置 [0.0, 1.0]
     * <p>
     * 用于 UI 滑块的位置映射。
     *
     * 【返回值】
     * @return float - 归一化位置，0.0=min, 1.0=max
     */
    public float getNormalizedValue() {
        float range = maxValue - minValue;
        if (range == 0.0f) return 0.5f;
        return (currentValue - minValue) / range;
    }

    /**
     * 从归一化位置设置值
     *
     * 【方法参数】
     * @param normalized float - 归一化位置 [0.0, 1.0]
     */
    public void setFromNormalized(float normalized) {
        float clampedNorm = Math.max(0.0f, Math.min(1.0f, normalized));
        float value = minValue + clampedNorm * (maxValue - minValue);
        setValue(snapToStep(value));
    }

    // ==================== 内部工具方法 ====================

    /**
     * 值钳制到有效范围
     *
     * 【方法参数】
     * @param value float - 输入值
     * @param min   float - 最小值
     * @param max   float - 最大值
     *
     * 【返回值】
     * @return float - 钳制后的值
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 通知所有监听器值发生了变化
     *
     * 【方法参数】
     * @param oldValue float - 变更前的旧值
     * @param newValue float - 变更后的新值
     */
    private void notifyListeners(float oldValue, float newValue) {
        for (ChangeListener<Float> listener : listeners) {
            try {
                listener.onValueChanged(this, oldValue, newValue);
            } catch (Exception e) {
                // 监听器异常不应影响主流程，静默忽略
                Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), e);
            }
        }
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format("FloatKnob{id='%s', name='%s', value=%.4f, range=[%.4f, %.4f], step=%.4f}",
                id, displayName, currentValue, minValue, maxValue, step);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FloatKnob other)) return false;
        return id.equals(other.id) && Float.compare(currentValue, other.currentValue) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * id.hashCode() + Float.hashCode(currentValue);
    }

    // ==================== Builder 模式 ====================

    /**
     * FloatKnob 构建器
     * <p>
     * 使用 Builder 模式提供灵活的对象构造方式，
     * 所有字段都有合理的默认值或必填校验。
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * FloatKnob knob = new FloatKnob.Builder("normal_strength")
     *     .displayName("法线强度")
     *     .range(0.1f, 2.0f)
     *     .step(0.1f)
     *     .defaultValue(1.0f)
     *     .category(ParameterCategory.LIGHTING)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        // ---------- 必填字段 ----------
        /** 参数 ID（必填） */
        private String id;

        // ---------- 可选字段（带默认值） ----------
        private String displayName;
        private String description = "";
        private float minValue = 0.0f;
        private float maxValue = 1.0f;
        private float step = 0.01f;
        private float defaultValue = 0.5f;
        private ParameterKnob.ParameterCategory category = ParameterKnob.ParameterCategory.SHADER;

        /**
         * 构造函数 - 设置参数 ID（必填）
         *
         * 【方法参数】
         * @param id String - 唯一标识符（kebab-case 格式）
         *
         * @throws NullPointerException 如果 id 为 null
         */
        public Builder(String id) {
            this.id = Objects.requireNonNull(id, "参数 ID 不能为 null");
        }

        /**
         * 设置显示名称
         *
         * 【方法参数】
         * @param name String - 显示名称
         *
         * @return this（链式调用）
         */
        public Builder displayName(String name) {
            this.displayName = name;
            return this;
        }

        /**
         * 设置描述文本
         *
         * 【方法参数】
         * @param desc String - 描述信息
         *
         * @return this（链式调用）
         */
        public Builder description(String desc) {
            this.description = desc;
            return this;
        }

        /**
         * 设置取值范围
         *
         * 【方法参数】
         * @param min float - 最小值
         * @param max float - 最大值
         *
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果 min > max
         */
        public Builder range(float min, float max) {
            if (min > max) {
                throw new IllegalArgumentException(
                        String.format("最小值(%.4f)不能大于最大值(%.4f)", min, max));
            }
            this.minValue = min;
            this.maxValue = max;
            return this;
        }

        /**
         * 设置步进值
         *
         * 【方法参数】
         * @param step float - 调整步长（必须 > 0）
         *
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果 step <= 0
         */
        public Builder step(float step) {
            if (step <= 0.0f) {
                throw new IllegalArgumentException("步进值必须大于 0: " + step);
            }
            this.step = step;
            return this;
        }

        /**
         * 设置默认值
         * <p>
         * 如果默认值超出范围，构建时会自动钳制到范围内。
         *
         * 【方法参数】
         * @param value float - 默认值
         *
         * @return this（链式调用）
         */
        public Builder defaultValue(float value) {
            this.defaultValue = value;
            return this;
        }

        /**
         * 设置参数分类
         *
         * 【方法参数】
         * @param category ParameterCategory - 分类枚举
         *
         * @return this（链式调用）
         */
        public Builder category(ParameterKnob.ParameterCategory category) {
            this.category = category;
            return this;
        }

        /**
         * 构建 FloatKnob 实例
         *
         * 【返回值】
         * @return FloatKnob - 构建好的不可变配置对象
         */
        public FloatKnob build() {
            return new FloatKnob(this);
        }
    }
}
