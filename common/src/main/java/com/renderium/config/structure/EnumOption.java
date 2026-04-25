// Renderium - 枚举型渲染器选项实现（泛型）
// 用于从预定义列表中选择一个值的设置项

package com.renderium.config.structure;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 枚举型渲染器选项（泛型实现）。
 *
 * <p>表示一个从有限候选集中选择单一值的选项，
 * 候选集由 Java {@link Enum} 类型定义。适用于：
 * <ul>
 *   <li><b>模式选择</b>：全屏/窗口化、画质预设、抗锯齿模式</li>
 *   <li><b>算法切换</b>：LOD 算法、剔除策略、超分辨率技术</li>
 *   <li><b>质量等级</b>：阴影质量、纹理过滤、各向异性过滤级别</li>
 *   <li><b>特性选择</b>：帧生成技术（DLSS/FSR/XeSS）、后处理效果</li>
 * </ul>
 *
 * <h2>UI 控件</h2>
 * <p>通常使用下拉框（ComboBox）或循环按钮（Cycling Button）展示。
 * 每个枚举值通过 {@link Function} 映射为用户友好的显示名称。
 *
 * <h2>泛型约束</h2>
 * <pre>
 * public class EnumOption&lt;T extends Enum&lt;T&gt;&gt;
 *                              ──────────────────
 *                              T 必须是枚举类型
 *
 * // 合法使用
 * EnumOption&lt;FullscreenMode&gt; fullscreenOption = ...
 * EnumOption&lt;AntiAliasingMode&gt; aaOption = ...
 * EnumOption&lt;QualityPreset&gt; qualityOption = ...
 *
 * // 非法编译错误
 * EnumOption&lt;String&gt; strOption = ...  // ✗ String 不是 Enum
 * </pre>
 *
 * <h2>数据流</h2>
 * <pre>
 * ┌──────────┐  setValue(T)   ┌────────────┐  save(T)   ┌──────────┐
 * │ 下拉框    │ ────────────→ │ EnumOption │ ─────────→ │ Binding  │
 * │ (ComboBox)│ ←──────────── │   &lt;T&gt;      │ ←───────── │ (目标对象) │
 * └──────────┘  getValue()    └────────────┘  load(T)   └──────────┘
 *                                  │
 *                                  ├── enumClass: Class&lt;T&gt;
 *                                  ├── defaultValue: T;
 *                                  ├── elementNameProvider: Function&lt;T, Component&gt;
 *                                  └── allowedValues: Set&lt;T&gt; (可选)
 * </pre>
 *
 * <h2>线程安全性</h2>
 * <p>{@code value} 字段使用 {@code volatile} 保证可见性。
 * 枚举类型本身是不可变的，天然线程安全。
 *
 * <h2>参考实现</h2>
 * <p>参考 Sodium 的 {@code EnumOption<E>} 设计，
 * 使用 Renderium 中性命名并增强文档和 Builder 支持。
 *
 * @param <T> 枚举类型参数，必须满足 {@code T extends Enum<T>}
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererOption
 * @see BooleanOption
 * @see IntegerOption
 */
public final class EnumOption<T extends Enum<T>> implements SearchableOption {

    /** 选项唯一标识符 */
    private final Identifier id;

    /** 显示名称 */
    private final Component name;

    /** 工具提示文本（可选） */
    @Nullable
    private final Component tooltip;

    /** 默认值 */
    private final T defaultValue;

    /** 性能影响级别 */
    private final OptionImpact impact;

    /** 特性标记集合 */
    private final EnumSet<OptionFlag> flags;

    /**
     * 枚举类型的 Class 对象。
     *
     * <p>用于在运行时获取所有枚举常量、执行反射操作等。
     * 通过 {@code T.class} 在构造时捕获（Java 泛型擦除的 workaround）。
     */
    private final Class<T> enumClass;

    /** 值写入绑定（Setter）：将新枚举值同步到目标对象 */
    @Nullable
    private final Consumer<T> setter;

    /** 值读取绑定（Getter）：从目标对象读取当前枚举值 */
    @Nullable
    private final Supplier<T> getter;

    /** 存储处理器（可选） */
    @Nullable
    private final Object storageHandler;

    /** 启用状态提供者（可选） */
    @Nullable
    private final EnabledProvider enabledProvider;

    /** 应用钩子（可选） */
    @Nullable
    private final ApplyHook applyHook;

    /**
     * 枚举元素显示名称提供者。
     *
     * <p>将每个枚举常量映射为用户友好的 Component 文本。
     * 通常返回国际化翻译键或动态生成的描述文本。
     *
     * <p>示例：
     * <pre>
     * FullscreenMode.FULLSCREEN → "全屏模式"
     * FullscreenMode.WINDOWED   → "窗口模式"
     * </pre>
     */
    private final Function<T, Component> elementNameProvider;

    /**
     * 当前允许选择的枚举值集合（可选）。
     *
     * <p>如果为 null 或空集，则允许 enumClass 的所有枚举常量；
     * 如果非空，则只允许集合内的值（用于动态限制可选范围）。
     *
     * <p>典型应用：某些枚举值仅在特定条件下可选（如硬件支持检测）。
     */
    @Nullable
    private final Set<T> allowedValues;

    /** 当前值（volatile 保证线程安全） */
    private volatile T value;

    /** 缓存的所有枚举常量数组（按声明顺序） */
    private final T[] enumConstants;

    /**
     * 创建新的枚举选项实例。
     *
     * <p>建议使用 {@link #builder(Class)} 进行构建。
     *
     * @param <E>                 枚举类型
     * @param id                  选项标识符
     * @param name                显示名称
     * @param tooltip             工具提示（可为 null）
     * @param defaultValue        默认值（必须是 enumClass 的有效枚举常量）
     * @param impact              性能影响级别
     * @param flags               特性标记集合
     * @param enumClass           枚举类型的 Class 对象
     * @param setter              值写入绑定（可选）
     * @param getter              值读取绑定（可选）
     * @param storageHandler      存储处理器（可选）
     * @param enabledProvider     启用状态提供者（可选）
     * @param applyHook           应用钩子（可选）
     * @param elementNameProvider 枚举值显示名称函数
     * @param allowedValues       允许的值集合（null 表示全部允许）
     * @throws NullPointerException     若 id, name, impact, flags, enumClass, elementNameProvider 为 null
     * @throws IllegalArgumentException 若 defaultValue 不属于 enumClass
     */
    public <E extends Enum<E>> EnumOption(
            Identifier id,
            Component name,
            @Nullable Component tooltip,
            T defaultValue,
            OptionImpact impact,
            EnumSet<OptionFlag> flags,
            Class<T> enumClass,
            @Nullable Consumer<T> setter,
            @Nullable Supplier<T> getter,
            @Nullable Object storageHandler,
            @Nullable EnabledProvider enabledProvider,
            @Nullable ApplyHook applyHook,
            Function<T, Component> elementNameProvider,
            @Nullable Set<T> allowedValues) {
        this.id = Objects.requireNonNull(id, "Option id cannot be null");
        this.name = Objects.requireNonNull(name, "Option name cannot be null");
        this.impact = Objects.requireNonNull(impact, "Impact cannot be null");
        this.flags = Objects.requireNonNull(flags, "Flags cannot be null");
        this.enumClass = Objects.requireNonNull(enumClass, "Enum class cannot be null");
        this.elementNameProvider = Objects.requireNonNull(
            elementNameProvider, "Element name provider cannot be null"
        );

        this.enumConstants = enumClass.getEnumConstants();
        if (this.enumConstants == null || this.enumConstants.length == 0) {
            throw new IllegalArgumentException(
                "Enum class " + enumClass.getSimpleName() + " has no constants"
            );
        }

        if (!Arrays.asList(this.enumConstants).contains(defaultValue)) {
            throw new IllegalArgumentException(
                "Default value " + defaultValue + " is not a valid constant of " + enumClass.getSimpleName()
            );
        }
        this.defaultValue = defaultValue;

        this.tooltip = tooltip;
        this.setter = setter;
        this.getter = getter;
        this.storageHandler = storageHandler;
        this.enabledProvider = enabledProvider;
        this.applyHook = applyHook;
        this.allowedValues = allowedValues;
        this.value = this.validateValue(defaultValue);
    }

    // ==================== RendererOption 接口实现 ====================

    /** {@inheritDoc} */
    @Override
    public Identifier getId() {
        return this.id;
    }

    /** {@inheritDoc} */
    @Override
    public Component getName() {
        return this.name;
    }

    /** {@inheritDoc} */
    @Override
    public OptionImpact getImpact() {
        return this.impact;
    }

    /** {@inheritDoc} */
    @Override
    public EnumSet<OptionFlag> getFlags() {
        return this.flags;
    }

    /**
     * 判断此选项是否可用。
     *
     * @param state 配置状态上下文
     * @return      true 表示可用
     */
    @Override
    public boolean isEnabled(ConfigState state) {
        if (this.enabledProvider != null) {
            return this.enabledProvider.isEnabled(state);
        }
        return true;
    }

    /**
     * 重置为默认值。
     */
    @Override
    public void resetToDefault() {
        this.value = this.defaultValue;
        if (this.setter != null) {
            this.setter.accept(this.defaultValue);
        }
    }

    // ==================== SearchableOption 接口实现 ====================

    /** {@inheritDoc} */
    @Override
    public void registerTextSources(SearchIndex index, Object modOptions, RendererOptionGroup optionGroup) {
        index.register(new OptionTextSource<>(this, optionGroup));
    }

    // ==================== 核心值操作 ====================

    /**
     * 获取当前值。
     *
     * <p>优先从 getter 绑定读取实时值，否则返回内部缓存。
     *
     * @return 当前枚举值
     */
    public T getValue() {
        if (this.getter != null) {
            return this.getter.get();
        }
        return this.value;
    }

    /**
     * 设置新值。
     *
     * <p>新值会经过验证流程（检查是否在允许集合内）后存储。
     * 如果值不在允许集合中，会自动回退为默认值。
     *
     * @param newValue 要设置的枚举值
     *
     * <h4>示例</h4>
     * <pre>
     * EnumOption&lt;FullscreenMode&gt; option = ...;
     * option.setValue(FullscreenMode.WINDOWED);  // 直接设值
     * </pre>
     */
    public void setValue(T newValue) {
        T validated = this.validateValue(newValue);
        this.value = validated;
        if (this.setter != null) {
            this.setter.accept(validated);
        }
    }

    /**
     * 循环切换到下一个枚举值。
     *
     * <p>按照枚举声明的顺序，将当前值切换到下一个有效值。
     * 到达最后一个值后会循环回到第一个值。
     * 只在允许值集合内循环（如果配置了 allowedValues）。
     *
     * <p><b>典型用途</b>：UI 中的循环按钮点击事件处理。
     *
     * <h4>循环逻辑</h4>
     * <pre>
     * // 假设枚举 [A, B, C, D]，当前值为 B
     * cycleValue() → C
     * cycleValue() → D
     * cycleValue() → A  （循环回第一个）
     * cycleValue() → B
     * </pre>
     *
     * @return 切换后的新值
     */
    public T cycleValue() {
        List<T> candidates = this.getAllowedValuesList();
        if (candidates.isEmpty()) {
            return this.value;
        }

        int currentIndex = candidates.indexOf(this.value);
        if (currentIndex < 0) {
            currentIndex = 0;
        }

        int nextIndex = (currentIndex + 1) % candidates.size();
        T nextValue = candidates.get(nextIndex);

        this.setValue(nextValue);
        return nextValue;
    }

    // ==================== 验证与查询 ====================

    /**
     * 验证给定值是否有效。
     *
     * <p>检查值是否在允许的候选集中：
     * <ul>
     *   <li>若配置了 allowedValues 且非空：必须在集合内</li>
     *   <li>否则：必须是 enumClass 的有效常量</li>
     * </ul>
     * 无效值会回退为默认值。
     *
     * @param value 待验证的值
     * @return      验证后的有效值（输入值本身或默认值）
     */
    private T validateValue(T value) {
        if (this.isValueAllowed(value)) {
            return value;
        }
        return this.defaultValue;
    }

    /**
     * 判断给定值是否在允许的选择范围内。
     *
     * @param value 待检查的枚举值
     * @return      true 表示该值可选择
     */
    public boolean isValueAllowed(T value) {
        if (this.allowedValues != null && !this.allowedValues.isEmpty()) {
            return this.allowedValues.contains(value);
        }
        return true;
    }

    /**
     * 获取当前允许选择的所有枚举值列表。
     *
     * <p>如果配置了 allowedValues 则返回其副本；
     * 否则返回 enumClass 的全部常量列表。
     *
     * @return 不可变的候选值列表（按声明顺序）
     */
    public List<T> getAllowedValuesList() {
        if (this.allowedValues != null && !this.allowedValues.isEmpty()) {
            List<T> result = new ArrayList<>(this.allowedValues);
            result.sort(Comparator.comparingInt(Enum::ordinal));
            return Collections.unmodifiableList(result);
        }
        return Collections.unmodifiableList(Arrays.asList(this.enumConstants));
    }

    /**
     * 获取指定枚举值的显示名称。
     *
     * <p>委托给 elementNameProvider 函数进行转换。
     *
     * @param element 枚举值
     * @return        显示名称 Component
     */
    public Component getElementName(T element) {
        return this.elementNameProvider.apply(element);
    }

    // ==================== 元数据访问 ====================

    /**
     * 获取工具提示文本。
     *
     * @return 工具提示 Component，可能为 null
     */
    @Nullable
    public Component getTooltip() {
        return this.tooltip;
    }

    /**
     * 获取默认值。
     *
     * @return 默认枚举值
     */
    public T getDefaultValue() {
        return this.defaultValue;
    }

    /**
     * 获取枚举类型的 Class 对象。
     *
     * @return 枚举类引用
     */
    public Class<T> getEnumClass() {
        return this.enumClass;
    }

    /**
     * 获取所有枚举常量数组。
     *
     * @return 按声明顺序排列的枚举常量数组
     */
    public T[] getEnumConstants() {
        return this.enumConstants.clone();  // 返回防御性拷贝
    }

    /**
     * 获取存储处理器。
     *
     * @return 存储处理器对象，可能为 null
     */
    @Nullable
    public Object getStorageHandler() {
        return this.storageHandler;
    }

    /**
     * 获取启用状态提供者。
     *
     * @return EnabledProvider 实例，可能为 null
     */
    @Nullable
    public EnabledProvider getEnabledProvider() {
        return this.enabledProvider;
    }

    /**
     * 获取应用钩子。
     *
     * @return ApplyHook 实例，可能为 null
     */
    @Nullable
    public ApplyHook getApplyHook() {
        return this.applyHook;
    }

    /**
     * 获取枚举值显示名称提供者。
     *
     * @return 名称映射函数
     */
    public Function<T, Component> getElementNameProvider() {
        return this.elementNameProvider;
    }

    /**
     * 获取允许值集合。
     *
     * @return 允许值集合的不可变视图，可能为 null
     */
    @Nullable
    public Set<T> getAllowedValues() {
        if (this.allowedValues == null) {
            return null;
        }
        return Collections.unmodifiableSet(this.allowedValues);
    }

    /**
     * 判断是否配置了写入绑定。
     */
    public boolean hasSetter() {
        return this.setter != null;
    }

    /**
     * 判断是否配置了读取绑定。
     */
    public boolean hasGetter() {
        return this.getter != null;
    }

    /**
     * 判断是否限制了可选值范围。
     *
     * @return true 表示 allowedValues 不为空
     */
    public boolean hasRestrictedValues() {
        return this.allowedValues != null && !this.allowedValues.isEmpty();
    }

    // ==================== 序列化支持（占位） ====================

    /**
     * 序列化为 JSON（占位实现）。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return String.format(
            "{\"id\":\"%s\",\"value\":\"%s\",\"enum\":\"%s\"}",
            this.id, this.value.name(), this.enumClass.getSimpleName()
        );
    }

    /**
     * 从 JSON 反序列化（占位实现）。
     *
     * @param json JSON 字符串
     * @param <E>  枚举类型
     * @return     EnumOption 实例
     * @throws UnsupportedOperationException 当前为占位
     */
    public static <E extends Enum<E>> EnumOption<E> fromJson(String json) {
        throw new UnsupportedOperationException(
            "EnumOption.fromJson() will be implemented in a future task"
        );
    }

    // ==================== Builder 模式 ====================

    /**
     * 创建 EnumOption 的构建器。
     *
     * @param <E>      枚举类型
     * @param enumClass 枚举类型的 Class 对象（必填，用于捕获泛型类型）
     * @return         新的 Builder 实例
     *
     * <h4>完整构建示例</h4>
     * <pre>
     * EnumOption&lt;FullscreenMode&gt; fullscreen = EnumOption.builder(FullscreenMode.class)
     *     .id(Identifier.parse("renderium:fullscreen_mode"))
     *     .name(Component.translatable("renderium.option.fullscreen_mode"))
     *     .tooltip(Component.translatable("renderium.option.fullscreen_mode.tooltip"))
     *     .defaultValue(FullscreenMode WINDOWED)
     *     .impact(OptionImpact.LOW)
     *     .flags(EnumSet.of(OptionFlag.APPLY_AND_CONTINUE))
     *     .setter(mode -> GraphicsConfig.setFullscreenMode(mode))
     *     .getter(() -> GraphicsConfig.getFullscreenMode())
     *     .elementNameProvider(mode -&gt; Component.translatable(
     *         "renderium.enum.fullscreen_mode." + mode.name().toLowerCase()
     *     ))
     *     .allowedValues(EnumSet.of(
     *         FullscreenMode.FULLSCREEN,
     *         FullscreenMode.WINDOWED_BORDERLESS,
     *         FullscreenMode.WINDOWED
     *     ))
     *     .build();
     * </pre>
     */
    public static <E extends Enum<E>> Builder<E> builder(Class<E> enumClass) {
        return new Builder<>(enumClass);
    }

    /**
     * EnumOption 的构建器（泛型）。
     *
     * @param <E> 枚举类型参数
     */
    public static final class Builder<E extends Enum<E>> {

        private final Class<E> enumClass;
        private Identifier id;
        private Component name;
        private Component tooltip;
        private E defaultValue;
        private OptionImpact impact = OptionImpact.LOW;
        private EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        private Consumer<E> setter;
        private Supplier<E> getter;
        private Object storageHandler;
        private EnabledProvider enabledProvider;
        private ApplyHook applyHook;
        private Function<E, Component> elementNameProvider;
        private Set<E> allowedValues;

        /**
         * 创建构建器并捕获枚举类型。
         *
         * @param enumClass 枚举类的 Class 对象
         */
        Builder(Class<E> enumClass) {
            this.enumClass = Objects.requireNonNull(enumClass);
        }

        /**
         * 设置选项标识符（必填）。
         */
        public Builder<E> id(Identifier id) {
            this.id = Objects.requireNonNull(id);
            return this;
        }

        /**
         * 设置显示名称（必填）。
         */
        public Builder<E> name(Component name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }

        /**
         * 设置工具提示（可选）。
         */
        public Builder<E> tooltip(@Nullable Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        /**
         * 设置默认值（必填）。
         *
         * @param defaultValue 默认枚举常量
         */
        public Builder<E> defaultValue(E defaultValue) {
            this.defaultValue = Objects.requireNonNull(defaultValue);
            return this;
        }

        /**
         * 设置性能影响级别（可选，默认 LOW）。
         */
        public Builder<E> impact(OptionImpact impact) {
            this.impact = Objects.requireNonNull(impact);
            return this;
        }

        /**
         * 设置特性标记（可选，默认空集）。
         */
        public Builder<E> flags(EnumSet<OptionFlag> flags) {
            this.flags = Objects.requireNonNull(flags);
            return this;
        }

        /**
         * 添加单个特性标记。
         */
        public Builder<E> addFlag(OptionFlag flag) {
            this.flags.add(Objects.requireNonNull(flag));
            return this;
        }

        /**
         * 设置值写入绑定（可选）。
         */
        public Builder<E> setter(@Nullable Consumer<E> setter) {
            this.setter = setter;
            return this;
        }

        /**
         * 设置值读取绑定（可选）。
         */
        public Builder<E> getter(@Nullable Supplier<E> getter) {
            this.getter = getter;
            return this;
        }

        /**
         * 设置存储处理器（可选）。
         */
        public Builder<E> storageHandler(@Nullable Object handler) {
            this.storageHandler = handler;
            return this;
        }

        /**
         * 设置启用状态提供者（可选）。
         */
        public Builder<E> enabledProvider(@Nullable EnabledProvider provider) {
            this.enabledProvider = provider;
            return this;
        }

        /**
         * 设置应用钩子（可选）。
         */
        public Builder<E> applyHook(@Nullable ApplyHook hook) {
            this.applyHook = hook;
            return this;
        }

        /**
         * 设置枚举值显示名称提供者（必填）。
         *
         * <p>将每个枚举常量转换为用户友好的显示文本。
         *
         * @param provider 名称映射函数
         * @return        this
         *
         * <h4>典型实现</h4>
         * <pre>
         * .elementNameProvider(mode -&gt;
         *     Component.translatable("renderium.enum.mode." + mode.name().toLowerCase())
         * )
         * </pre>
         */
        public Builder<E> elementNameProvider(Function<E, Component> provider) {
            this.elementNameProvider = Objects.requireNonNull(provider);
            return this;
        }

        /**
         * 设置允许的枚举值集合（可选）。
         *
         * <p>限制用户只能选择集合内的值。
         * 传 null 或空集合表示允许所有枚举常量。
         *
         * @param values 允许的值集合
         * @return       this
         */
        public Builder<E> allowedValues(@Nullable Set<E> values) {
            this.allowedValues = values;
            return this;
        }

        /**
         * 构建不可变的 EnumOption 实例。
         *
         * @return 新的 EnumOption 实例
         * @throws IllegalStateException 若缺少必填字段
         */
        @SuppressWarnings("unchecked")
        public EnumOption<E> build() {
            if (this.id == null) {
                throw new IllegalStateException("Option id is required");
            }
            if (this.name == null) {
                throw new IllegalStateException("Option name is required");
            }
            if (this.defaultValue == null) {
                throw new IllegalStateException("Default value is required for EnumOption");
            }
            if (this.elementNameProvider == null) {
                throw new IllegalStateException("Element name provider is required for EnumOption");
            }

            return new EnumOption<>(
                this.id,
                this.name,
                this.tooltip,
                this.defaultValue,
                this.impact,
                this.flags,
                this.enumClass,
                this.setter,
                this.getter,
                this.storageHandler,
                this.enabledProvider,
                this.applyHook,
                (Function<T, Component>) this.elementNameProvider,
                this.allowedValues
            );
        }
    }

    // ==================== 内部辅助类 ====================

    /**
     * 选项文本搜索源（泛型版本）。
     */
    private static final class OptionTextSource<T extends Enum<T>> {
        private final EnumOption<T> option;
        private final RendererOptionGroup group;

        OptionTextSource(EnumOption<T> option, RendererOptionGroup group) {
            this.option = option;
            this.group = group;
        }

        @Override
        public String toString() {
            return String.format(
                "EnumOption[id=%s, name=%s, value=%s, enum=%s]",
                this.option.getId(),
                this.option.getName(),
                this.option.getValue().name(),
                this.option.getEnumClass().getSimpleName()
            );
        }
    }
}
