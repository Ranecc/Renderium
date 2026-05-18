// Renderium - 动态参数配置系统
// 布尔参数旋钮实现

package com.ranecc.renderium.feature.pipeline.parameter.impl;


import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import com.ranecc.renderium.feature.pipeline.parameter.ParameterKnob;

/**
 * 布尔参数旋钮 (Bool Knob)
 * <p>
 * {@link ParameterKnob} 的布尔类型实现，用于表示开关类型的渲染参数。
 * 内部使用 volatile boolean 存储状态，支持运行时热更新。
 *
 * <h2>典型应用场景：</h2>
 * <ul>
 *   <li><b>法线映射开关</b>：enableNormalMapping</li>
 *   <li><b>SSAO 开关</b>：enableSSAO</li>
 *   <li><b>TAA 开关</b>：enableTAA</li>
 *   <li><b>阴影开关</b>：enableShadows</li>
 *   <li><b>泛光开关</b>：enableBloom</li>
 *   <li><b>VSync 开关</b>：enableVSync</li>
 * </ul>
 *
 * <h3>线程安全保证：</h3>
 * <p>
 * 使用 volatile boolean 字段存储当前值。
 * boolean 的读写在 Java 中是原子的（JLS 17.7），
 * volatile 保证跨线程的内存可见性。
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建布尔参数
 * ParameterKnob<Boolean> enableNormal = new BoolKnob.Builder("enable_normal_mapping")
 *     .displayName("启用法线映射")
 *     .description("是否使用法线贴图增强表面细节")
 *     .defaultValue(true)
 *     .category(ParameterKnob.ParameterCategory.SHADER)
 *     .build();
 *
 * // 切换状态
 * enableNormal.setValue(false);
 *
 * // 获取状态
 * boolean enabled = enableNormal.getValue();  // false
 *
 * // 快捷切换
 * enableNormal.toggle();
 * }</pre>
 *
 * @see ParameterKnob
 * @since 6.0.0
 */
public final class BoolKnob implements ParameterKnob<Boolean> {

    // ==================== 实例字段 ====================

    /** 参数唯一标识符 */
    private final String id;

    /** 显示名称 */
    private final String displayName;

    /** 参数描述信息 */
    private final String description;

    /** 默认值 */
    private final boolean defaultValue;

    /** 参数分类 */
    private final ParameterKnob.ParameterCategory category;

    /**
     * 当前运行时值（volatile 保证跨线程可见性）
     */
    private volatile boolean currentValue;

    /** 变更监听器列表 */
    private final List<ChangeListener<Boolean>> listeners = new CopyOnWriteArrayList<>();

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private BoolKnob(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "参数 ID 不能为 null");
        this.displayName = builder.displayName != null ? builder.displayName : builder.id;
        this.description = builder.description != null ? builder.description : "";
        this.defaultValue = builder.defaultValue;
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
     * @return KnobType - 始终返回 BOOL
     */
    @Override
    public KnobType getType() {
        return KnobType.BOOL;
    }

    /**
     * 获取当前布尔值
     * <p>
     * volatile 读，无锁，高性能。
     *
     * 【返回值】
     * @return Boolean - 当前值
     */
    @Override
    public Boolean getValue() {
        return currentValue;
    }

    /**
     * 设置新的布尔值
     *
     * 【方法参数】
     * @param value Boolean - 新的参数值（不能为 null）
     *
     * @throws NullPointerException 如果 value 为 null
     */
    @Override
    public void setValue(Boolean value) {
        Objects.requireNonNull(value, "参数值不能为 null");
        boolean oldValue = this.currentValue;

        if (oldValue != value) {
            this.currentValue = value;
            notifyListeners(oldValue, value);
        }
    }

    /**
     * 布尔类型无范围概念，返回空数组
     *
     * 【返回值】
     * @return Object[] - 空数组
     */
    @Override
    public Object[] getRange() {
        return new Object[0];
    }

    /**
     * 获取默认值
     *
     * 【返回值】
     * @return Boolean - 默认值
     */
    @Override
    public Boolean getDefault() {
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
     * @param listener ChangeListener&lt;Boolean&gt; - 监听器
     */
    @Override
    public void addChangeListener(ChangeListener<Boolean> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 移除变更监听器
     *
     * @param listener ChangeListener&lt;Boolean&gt; - 要移除的监听器
     */
    @Override
    public void removeChangeListener(ChangeListener<Boolean> listener) {
        listeners.remove(listener);
    }

    // ==================== 布尔特有方法 ====================

    /**
     * 获取原始 boolean 值（避免自动装箱）
     * <p>
     * 性能敏感路径应优先使用此方法。
     *
     * 【返回值】
     * @return boolean - 原始 boolean 值
     */
    public boolean getRawValue() {
        return currentValue;
    }

    /**
     * 检查当前是否为开启状态
     * <p>
     * 语义等价于 getRawValue()，但语义更清晰。
     *
     * 【返回值】
     * @return boolean - true 表示已开启
     */
    public boolean isEnabled() {
        return currentValue;
    }

    /**
     * 切换布尔状态（开->关 或 关->开）
     * <p>
     * 原子操作，不会出现竞态条件。
     */
    public void toggle() {
        setValue(!currentValue);
    }

    /**
     * 启用（设为 true）
     */
    public void enable() {
        setValue(true);
    }

    /**
     * 禁用（设为 false）
     */
    public void disable() {
        setValue(false);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 通知所有监听器
     *
     * @param oldValue boolean - 旧值
     * @param newValue boolean - 新值
     */
    private void notifyListeners(boolean oldValue, boolean newValue) {
        for (ChangeListener<Boolean> listener : listeners) {
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
        return String.format("BoolKnob{id='%s', name='%s', value=%s, default=%s}",
                id, displayName, currentValue, defaultValue);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BoolKnob other)) return false;
        return id.equals(other.id) && currentValue == other.currentValue;
    }

    @Override
    public int hashCode() {
        return 31 * id.hashCode() + Boolean.hashCode(currentValue);
    }

    // ==================== Builder 模式 ====================

    /**
     * BoolKnob 构建器
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * BoolKnob knob = new BoolKnob.Builder("enable_normal_mapping")
     *     .displayName("启用法线映射")
     *     .description("控制法线贴图是否生效")
     *     .defaultValue(true)
     *     .category(ParameterCategory.SHADER)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        private final String id;
        private String displayName;
        private String description = "";
        private boolean defaultValue = false;
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
         * 设置默认值
         *
         * @param value boolean - 默认开关状态
         * @return this
         */
        public Builder defaultValue(boolean value) {
            this.defaultValue = value;
            return this;
        }

        public Builder category(ParameterKnob.ParameterCategory cat) { this.category = cat; return this; }

        /**
         * 构建 BoolKnob 实例
         *
         * @return BoolKnob - 构建好的实例
         */
        public BoolKnob build() {
            return new BoolKnob(this);
        }
    }
}
