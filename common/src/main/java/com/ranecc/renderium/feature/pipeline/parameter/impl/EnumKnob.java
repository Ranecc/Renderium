// Renderium - 动态参数配置系统
// 枚举参数旋钮实现

package com.ranecc.renderium.feature.pipeline.parameter.impl;


import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import com.ranecc.renderium.feature.pipeline.parameter.ParameterKnob;

/**
 * 枚举参数旋钮 (Enum Knob)
 * <p>
 * {@link ParameterKnob} 的枚举类型实现，用于表示从预定义选项中选择一个值的参数。
 * 内部通过索引（int）存储当前选中项，支持运行时热更新。
 *
 * <h2>典型应用场景：</h2>
 * <ul>
 *   <li><b>阴影滤镜类型</b>：filterType {HARD, PCF, PCSS}</li>
 *   <li><b>色调映射算法</b>：tonemapMode {ACES, REINHARD, FILMIC, UNCHARTED2}</li>
 *   <li><b>下采样滤镜</b>：downsampleFilter {BOX, GAUSSIAN, TENT}</li>
 *   <li><b>质量等级</b>：qualityLevel {LOW, MEDIUM, HIGH, ULTRA}</li>
 * </ul>
 *
 * <h3>线程安全保证：</h3>
 * <p>
 * 使用 volatile int 存储当前选中索引，保证跨线程可见性。
 * 选项列表在构造后不可变，天然线程安全。
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 使用 Builder 创建枚举参数
 * ParameterKnob<String> filterType = new EnumKnob.Builder("shadow_filter_type")
 *     .displayName("阴影滤镜类型")
 *     .description("选择阴影边缘柔化算法")
 *     .options("HARD", "PCF", "PCSS")
 *     .defaultIndex(1)  // 默认选中 PCF
 *     .category(ParameterKnob.ParameterCategory.SHADOW)
 *     .build();
 *
 * // 通过索引设置
 * filterType.setValue("PCSS");  // 或 setByIndex(2)
 *
 * // 获取当前选中的值
 * String current = filterType.getValue();  // "PCSS"
 * }</pre>
 *
 * @see ParameterKnob
 * @since 6.0.0
 */
public final class EnumKnob implements ParameterKnob<String> {

    // ==================== 实例字段 ====================

    /** 参数唯一标识符 */
    private final String id;

    /** 显示名称 */
    private final String displayName;

    /** 参数描述信息 */
    private final String description;

    /**
     * 不可变选项列表
     * <p>
     * 构造后不再修改，多线程安全共享。
     */
    private final List<String> options;

    /** 默认选中的索引 */
    private final int defaultIndex;

    /** 参数分类 */
    private final ParameterKnob.ParameterCategory category;

    /**
     * 当前选中项的索引（volatile 保证可见性）
     */
    private volatile int currentIndex;

    /** 变更监听器列表 */
    private final List<ChangeListener<String>> listeners = new CopyOnWriteArrayList<>();

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private EnumKnob(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "参数 ID 不能为 null");
        this.displayName = builder.displayName != null ? builder.displayName : builder.id;
        this.description = builder.description != null ? builder.description : "";

        // 深拷贝选项列表并包装为不可变视图
        if (builder.options == null || builder.options.length == 0) {
            throw new IllegalArgumentException("枚举参数必须至少有一个选项");
        }
        this.options = Collections.unmodifiableList(List.of(builder.options));

        // 钳制默认索引到合法范围
        this.defaultIndex = clamp(builder.defaultIndex, 0, this.options.size() - 1);
        this.currentIndex = this.defaultIndex;
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
     * @return KnobType - 始终返回 ENUM
     */
    @Override
    public KnobType getType() {
        return KnobType.ENUM;
    }

    /**
     * 获取当前选中的选项文本
     * <p>
     * 返回 options 列表中 currentIndex 位置的文字。
     *
     * 【返回值】
     * @return String - 当前选中的选项文本
     */
    @Override
    public String getValue() {
        return options.get(currentIndex);
    }

    /**
     * 通过选项文本设置值
     * <p>
     * 在选项列表中查找匹配的文本（大小写敏感），
     * 找到则切换到对应索引，找不到则忽略。
     *
     * 【方法参数】
     * @param value String - 要选中的选项文本（不能为 null）
     *
     * @throws NullPointerException 如果 value 为 null
     */
    @Override
    public void setValue(String value) {
        Objects.requireNonNull(value, "参数值不能为 null");
        int newIndex = options.indexOf(value);
        if (newIndex >= 0) {
            setByIndex(newIndex);
        }
    }

    /**
     * 获取所有可用选项
     * <p>
     * 对于 ENUM 类型，getRange() 返回的是选项列表而非 [min, max]。
     *
     * 【返回值】
     * @return Object[] - 选项数组（不可变副本）
     */
    @Override
    public Object[] getRange() {
        return options.toArray(new String[0]);
    }

    /**
     * 获取默认值（默认选项的文本）
     *
     * 【返回值】
     * @return String - 默认选项文本
     */
    @Override
    public String getDefault() {
        return options.get(defaultIndex);
    }

    /**
     * 重置为默认选项
     */
    @Override
    public void resetToDefault() {
        setByIndex(defaultIndex);
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
     * 检查是否为默认选项
     *
     * 【返回值】
     * @return boolean - true 表示未修改
     */
    @Override
    public boolean isDefault() {
        return currentIndex == defaultIndex;
    }

    /**
     * 注册变更监听器
     *
     * @param listener ChangeListener&lt;String&gt; - 监听器
     */
    @Override
    public void addChangeListener(ChangeListener<String> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 移除变更监听器
     *
     * @param listener ChangeListener&lt;String&gt; - 要移除的监听器
     */
    @Override
    public void removeChangeListener(ChangeListener<String> listener) {
        listeners.remove(listener);
    }

    // ==================== 枚举特有方法 ====================

    /**
     * 获取所有选项的不可变列表
     *
     * 【返回值】
     * @return List&lt;String&gt; - 选项列表（不可变）
     */
    public List<String> getOptions() {
        return options;
    }

    /**
     * 获取当前选中项的索引
     *
     * 【返回值】
     * @return int - 当前索引（0-based）
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 通过索引设置选中项
     * <p>
     * 索引超出范围时自动钳制。
     *
     * 【方法参数】
     * @param index int - 目标索引（0 ~ options.size()-1）
     */
    public void setByIndex(int index) {
        int newIndex = clamp(index, 0, options.size() - 1);
        int oldIndex = this.currentIndex;

        if (oldIndex != newIndex) {
            this.currentIndex = newIndex;
            notifyListeners(options.get(oldIndex), options.get(newIndex));
        }
    }

    /**
     * 获取默认索引
     *
     * 【返回值】
     * @return int - 默认索引
     */
    public int getDefaultIndex() {
        return defaultIndex;
    }

    /**
     * 获取选项总数
     *
     * 【返回值】
     * @return int - 选项数量
     */
    public int getOptionCount() {
        return options.size();
    }

    /**
     * 检查指定文本是否为有效选项
     *
     * 【方法参数】
     * @param value String - 待检查的文本
     *
     * 【返回值】
     * @return boolean - true 表示该文本是有效选项之一
     */
    public boolean isValidOption(String value) {
        return value != null && options.contains(value);
    }

    /**
     * 选择下一个选项（循环）
     * <p>
     * 到达末尾时回到第一个选项。
     */
    public void selectNext() {
        int next = (currentIndex + 1) % options.size();
        setByIndex(next);
    }

    /**
     * 选择上一个选项（循环）
     * <p>
     * 到达第一个时跳到最后一个选项。
     */
    public void selectPrevious() {
        int prev = (currentIndex - 1 + options.size()) % options.size();
        setByIndex(prev);
    }

    // ==================== 内部工具方法 ====================

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 通知所有监听器
     */
    private void notifyListeners(String oldValue, String newValue) {
        for (ChangeListener<String> listener : listeners) {
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
        return String.format("EnumKnob{id='%s', name='%s', current='%s'[%d/%d], options=%s}",
                id, displayName, getValue(), currentIndex, options.size(), options);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof EnumKnob other)) return false;
        return id.equals(other.id) && currentIndex == other.currentIndex;
    }

    @Override
    public int hashCode() {
        return 31 * id.hashCode() + Integer.hashCode(currentIndex);
    }

    // ==================== Builder 模式 ====================

    /**
     * EnumKnob 构建器
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * EnumKnob knob = new EnumKnob.Builder("filter_type")
     *     .displayName("阴影滤镜类型")
     *     .options("HARD", "PCF", "PCSS")
     *     .defaultIndex(1)
     *     .category(ParameterCategory.SHADOW)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        private final String id;
        private String displayName;
        private String description = "";
        private String[] options;
        private int defaultIndex = 0;
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
         * 设置枚举选项列表（必填）
         *
         * @param opts String... - 可变参数，至少提供一个选项
         * @return this
         * @throws IllegalArgumentException 如果没有提供任何选项
         */
        public Builder options(String... opts) {
            if (opts == null || opts.length == 0) {
                throw new IllegalArgumentException("枚举参数必须至少有一个选项");
            }
            this.options = opts.clone();  // 防御性拷贝
            return this;
        }

        /**
         * 设置默认选中索引
         *
         * @param index int - 默认索引（0-based），构建时会自动钳制
         * @return this
         */
        public Builder defaultIndex(int index) {
            this.defaultIndex = index;
            return this;
        }

        /**
         * 通过值字符串设置默认选中项
         *
         * @param value String - 默认值字符串，会匹配 options 中的项
         * @return this
         */
        public Builder defaultValue(String value) {
            if (value != null && options != null) {
                for (int i = 0; i < options.length; i++) {
                    if (value.equals(options[i])) {
                        this.defaultIndex = i;
                        return this;
                    }
                }
            }
            // 未找到匹配项，保持默认索引 0
            return this;
        }

        public Builder category(ParameterKnob.ParameterCategory cat) { this.category = cat; return this; }

        /**
         * 构建 EnumKnob 实例
         *
         * @return EnumKnob - 构建好的实例
         * @throws IllegalStateException 如果未调用 options()
         */
        public EnumKnob build() {
            if (options == null) {
                throw new IllegalStateException("必须通过 options() 设置枚举选项");
            }
            return new EnumKnob(this);
        }
    }
}
