// Renderium - 整数型渲染器选项实现
// 用于数值范围的设置项（如渲染距离、帧率限制、LOD 级别等）

package com.renderium.config.structure;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 整数型渲染器选项。
 *
 * <p>表示一个有范围约束的整数值选项，适用于：
 * <ul>
 *   <li><b>数量配置</b>：渲染距离（区块数）、最大粒子数等</li>
 *   <li><b>级别设置</b>：LOD 级别、阴影质量等级、纹理分辨率</li>
 *   <li><b>阈值控制</b>：FPS 上限、内存预算（MB）等</li>
 *   <li><b>偏移调整</b>：亮度偏移、对比度调节、Mip 偏移量</li>
 * </ul>
 *
 * <h2>UI 控件</h2>
 * <p>通常使用滑块（Slider）控件展示，
 * 支持拖拽、点击定位、步进按钮等多种交互方式。
 * 滑块的范围和步进由 {@link Range} 对象定义。
 *
 * <h2>验证体系</h2>
 * <pre>
 * 用户输入值
 *     │
 *     ▼
 * ┌─────────────┐     超出范围？
 * │  Range.clamp│ ──Yes──→ 裁剪到 [min, max]
 * └──────┬──────┘
 *        │ 在范围内
 *        ▼
 * ┌─────────────┐     未对齐步进？
 * │Range.snapToStep│ ──Yes──→ 四舍五入到最近步进倍数
 * └──────┬──────┘
 *        │ 已对齐
 *        ▼
 * ┌─────────────┐     配置了动态验证器？
 * │ Validator    │ ──Yes──→ 委托给 validator.validate()
 * │ .get(state)  │              （可能进一步收紧范围）
 * └──────┬──────┘
 *        │ 无动态验证 / 验证通过
 *        ▼
 *    最终有效值 ✓
 * </pre>
 *
 * <h2>线程安全性</h2>
 * <p>{@code value} 字段使用 {@code volatile} 保证跨线程可见性。
 * 验证方法（validateValue, getValidatedValue）本身是纯函数，天然线程安全。
 *
 * <h2>参考实现</h2>
 * <p>参考 Sodium 的 {@code IntegerOption} 设计，
 * 使用 Renderium 中性命名并增强 Builder 模式和文档。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererOption
 * @see BooleanOption
 * @see EnumOption
 * @see Range
 * @see ValidatorProvider
 */
public final class IntegerOption implements SearchableOption {

    /** 选项唯一标识符 */
    private final Identifier id;

    /** 显示名称 */
    private final Component name;

    /** 工具提示文本（可选） */
    @Nullable
    private final Component tooltip;

    /** 默认值 */
    private final int defaultValue;

    /** 性能影响级别 */
    private final OptionImpact impact;

    /** 特性标记集合 */
    private final EnumSet<OptionFlag> flags;

    /** 静态范围约束（基本值域） */
    private final Range range;

    /** 值写入绑定（Setter）：将新值同步到目标对象 */
    @Nullable
    private final Consumer<Integer> setter;

    /** 值读取绑定（Getter）：从目标对象读取当前值 */
    @Nullable
    private final Supplier<Integer> getter;

    /** 存储处理器（可选） */
    @Nullable
    private final Object storageHandler;

    /** 启用状态提供者（可选） */
    @Nullable
    private final EnabledProvider enabledProvider;

    /** 应用钩子（可选） */
    @Nullable
    private final ApplyHook applyHook;

    /** 值格式化器（可选，用于将数值转换为显示文本） */
    @Nullable
    private final Function<Integer, Component> valueFormatter;

    /**
     * 动态验证器提供者（可选）。
     *
     * <p>根据运行时状态返回额外的约束规则，
     * 可在静态 Range 的基础上进一步收紧有效范围。
     * 典型应用：渲染距离的最大值取决于已分配的内存量。
     */
    @Nullable
    private final ValidatorProvider<Range> validatorProvider;

    /** 当前值（volatile 保证线程安全） */
    private volatile int value;

    /**
     * 创建新的整数选项实例。
     *
     * <p>建议使用 {@link #builder()} 进行构建。
     *
     * @param id               选项标识符
     * @param name             显示名称
     * @param tooltip          工具提示（可为 null）
     * @param defaultValue     默认值（必须在 range 范围内）
     * @param impact           性能影响级别
     * @param flags            特性标记集合
     * @param range            静态范围约束（必填）
     * @param setter           值写入绑定（可选）
     * @param getter           值读取绑定（可选）
     * @param storageHandler   存储处理器（可选）
     * @param enabledProvider  启用状态提供者（可选）
     * @param applyHook        应用钩子（可选）
     * @param valueFormatter   值格式化器（可选）
     * @param validatorProvider 动态验证器提供者（可选）
     * @throws IllegalArgumentException 若 defaultValue 不在 range 范围内
     */
    public IntegerOption(
            Identifier id,
            Component name,
            @Nullable Component tooltip,
            int defaultValue,
            OptionImpact impact,
            EnumSet<OptionFlag> flags,
            Range range,
            @Nullable Consumer<Integer> setter,
            @Nullable Supplier<Integer> getter,
            @Nullable Object storageHandler,
            @Nullable EnabledProvider enabledProvider,
            @Nullable ApplyHook applyHook,
            @Nullable Function<Integer, Component> valueFormatter,
            @Nullable ValidatorProvider<Range> validatorProvider) {
        this.id = Objects.requireNonNull(id, "Option id cannot be null");
        this.name = Objects.requireNonNull(name, "Option name cannot be null");
        this.range = Objects.requireNonNull(range, "Range cannot be null");
        this.impact = Objects.requireNonNull(impact, "Impact cannot be null");
        this.flags = Objects.requireNonNull(flags, "Flags cannot be null");

        if (!range.contains(defaultValue)) {
            throw new IllegalArgumentException(
                String.format("Default value %d is out of range %s", defaultValue, range)
            );
        }
        this.defaultValue = defaultValue;

        this.tooltip = tooltip;
        this.setter = setter;
        this.getter = getter;
        this.storageHandler = storageHandler;
        this.enabledProvider = enabledProvider;
        this.applyHook = applyHook;
        this.valueFormatter = valueFormatter;
        this.validatorProvider = validatorProvider;
        this.value = range.validate(defaultValue);
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
     * <p>委托给 EnabledProvider（如果已配置），否则默认可用。
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
     *
     * <p>恢复为构造时的默认值，并通过 setter 同步到绑定目标。
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
        index.register(new OptionTextSource(this, optionGroup));
    }

    // ==================== 核心值操作 ====================

    /**
     * 获取当前值。
     *
     * <p>优先从 getter 绑定读取实时值，否则返回内部缓存。
     *
     * @return 当前整数值
     */
    public int getValue() {
        if (this.getter != null) {
            return this.getter.get();
        }
        return this.value;
    }

    /**
     * 设置新值。
     *
     * <p>新值会经过 {@link #getValidatedValue()} 流程进行验证和规范化后存储。
     * 实际存储的值可能与输入值不同（被裁剪或对齐到步进）。
     *
     * @param newValue 原始输入值（可能超出范围或未对齐）
     *
     * <h4>示例</h4>
     * <pre>
     * // Range(0, 100, 10), 输入 55 → 实际存储 60
     * option.setValue(55);
     * assert option.getValue() == 60;
     * </pre>
     */
    public void setValue(int newValue) {
        int validated = this.validateValue(newValue);
        this.value = validated;
        if (this.setter != null) {
            this.setter.accept(validated);
        }
    }

    /**
     * 获取经过完整验证的当前值。
     *
     * <p>对内部缓存的值执行完整的验证流程：
     * 范围裁剪 → 步进对齐 → 动态验证器约束。
     * 如果验证导致值变更，会自动更新内部缓存。
     *
     * <p><b>典型用途</b>：
     * <ul>
     *   <li>UI 控件读取显示值前调用</li>
     *   <li>渲染逻辑读取参数前调用</li>
     *   <li>序列化输出前调用</li>
     * </ul>
     *
     * @return 经过验证和规范化的有效值
     *
     * <h4>验证流程详解</h4>
     * <pre>
     * 1. 获取当前原始值（this.value）
     * 2. 通过 Range.validate() 进行静态验证（范围+步进）
     * 3. 如果配置了 ValidatorProvider，获取动态 Range 并再次验证
     * 4. 返回最终有效值
     * </pre>
     */
    public int getValidatedValue() {
        int validated = this.validateValue(this.value);
        if (validated != this.value) {
            this.value = validated;  // 更新缓存
        }
        return validated;
    }

    /**
     * 按指定增量调整当前值。
     *
     * <p>在当前值基础上增加 delta，然后执行验证流程。
     * 主要用于 UI 中的步进按钮（+/-）操作。
     *
     * <p>调整后的值会被自动 clamp 到范围内，
     * 即使 delta 导致超出边界也不会抛出异常。
     *
     * @param delta 调整量（正数增加，负数减小）
     *
     * <h4>使用场景</h4>
     * <pre>
     * // 点击 "+" 按钮：增加值
     * option.adjustValue(1);       // 步进 +1
     * option.adjustValue(step);    // 使用自定义步进
     *
     * // 点击 "-" 按钮：减少值
     * option.adjustValue(-1);
     *
     * // Page Up/Page Down：大步进
     * option.adjustValue(10);
     * option.adjustValue(-10);
     * </pre>
     */
    public void adjustValue(int delta) {
        int newValue = this.value + delta;
        this.setValue(newValue);
    }

    // ==================== 内部验证逻辑 ====================

    /**
     * 对给定值执行完整的验证流程。
     *
     * <p>这是核心验证方法，被 setValue() 和 getValidatedValue() 共用。
     * 验证顺序：
     * <ol>
     *   <li>通过 {@link Range#validate(int)} 进行静态验证（范围+步进）</li>
     *   <li>如果配置了 {@link ValidatorProvider}，获取动态 Range 并二次验证</li>
     * </ol>
     *
     * @param rawValue 待验证的原始值
     * @return         验证后的有效值（保证在有效范围内且对齐步进）
     */
    private int validateValue(int rawValue) {
        int staticValidated = this.range.validate(rawValue);

        if (this.validatorProvider != null && ConfigStateAccessor.getCurrentState() != null) {
            Range dynamicRange = this.validatorProvider.getValidator(ConfigStateAccessor.getCurrentState());
            if (dynamicRange != null) {
                return dynamicRange.validate(staticValidated);
            }
        }

        return staticValidated;
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
     * @return 构造时指定的默认整数值
     */
    public int getDefaultValue() {
        return this.defaultValue;
    }

    /**
     * 获取静态范围约束。
     *
     * @return 不可变的 Range 实例
     */
    public Range getRange() {
        return this.range;
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
     * 获取值格式化器。
     *
     * @return 格式化函数，可能为 null（未设置时直接使用 toString）
     */
    @Nullable
    public Function<Integer, Component> getValueFormatter() {
        return this.valueFormatter;
    }

    /**
     * 获取动态验证器提供者。
     *
     * @return ValidatorProvider 实例，可能为 null
     */
    @Nullable
    public ValidatorProvider<Range> getValidatorProvider() {
        return this.validatorProvider;
    }

    /**
     * 将值格式化为可显示的 Component。
     *
     * <p>如果配置了 valueFormatter，委托给它；
     * 否则使用默认格式：{@code Component.literal(String.valueOf(value))}。
     *
     * @param value 要格式化的值
     * @return      格式化后的显示文本
     */
    public Component formatValue(int value) {
        if (this.valueFormatter != null) {
            return this.valueFormatter.apply(value);
        }
        return Component.literal(String.valueOf(value));
    }

    /**
     * 判断是否配置了写入绑定。
     *
     * @return true 表示 setter 不为 null
     */
    public boolean hasSetter() {
        return this.setter != null;
    }

    /**
     * 判断是否配置了读取绑定。
     *
     * @return true 表示 getter 不为 null
     */
    public boolean hasGetter() {
        return this.getter != null;
    }

    /**
     * 判断是否配置了动态验证器。
     *
     * @return true 表示 validatorProvider 不为 null
     */
    public boolean hasDynamicValidator() {
        return this.validatorProvider != null;
    }

    // ==================== 序列化支持（占位） ====================

    /**
     * 序列化为 JSON（占位实现）。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return String.format(
            "{\"id\":\"%s\",\"value\":%d,\"range\":%s}",
            this.id, this.value, this.range
        );
    }

    /**
     * 从 JSON 反序列化（占位实现）。
     *
     * @param json JSON 字符串
     * @return     IntegerOption 实例
     * @throws UnsupportedOperationException 当前为占位
     */
    public static IntegerOption fromJson(String json) {
        throw new UnsupportedOperationException(
            "IntegerOption.fromJson() will be implemented in a future task"
        );
    }

    // ==================== Builder 模式 ====================

    /**
     * 创建构建器。
     *
     * @return 新的 Builder 实例
     *
     * <h4>完整构建示例</h4>
     * <pre>
     * IntegerOption renderDistance = IntegerOption.builder()
     *     .id(Identifier.parse("renderium:render_distance"))
     *     .name(Component.translatable("renderium.option.render_distance"))
     *     .tooltip(Component.translatable("renderium.option.render_distance.tooltip"))
     *     .defaultValue(12)
     *     .impact(OptionImpact.HIGH)
     *     .flags(EnumSet.of(OptionFlag.APPLY_AND_CONTINUE))
     *     .range(new Range(2, 32, 1))
     *     .setter(value -> RenderSystem.setRenderDistance(value))
     *     .getter(() -> RenderSystem.getRenderDistance())
     *     .valueFormatter(value ->
     *         Component.translatable("renderium.unit.chunks", value)
     *     )
     *     .validatorProvider(state -&gt; {
     *         int memMB = state.readIntOption(Identifier.parse("renderium:allocated_memory"));
     *         return new Range(2, Math.min(32, memMB / 64), 1);
     *     })
     *     .build();
     * </pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * IntegerOption 的构建器。
     *
     * <p>必填字段：id, name, range
     * <p>重要字段：defaultValue（必须在 range 内）
     * <p>可选字段：其余所有
     */
    public static final class Builder {

        private Identifier id;
        private Component name;
        private Component tooltip;
        private int defaultValue;
        private OptionImpact impact = OptionImpact.LOW;
        private EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        private Range range;
        private Consumer<Integer> setter;
        private Supplier<Integer> getter;
        private Object storageHandler;
        private EnabledProvider enabledProvider;
        private ApplyHook applyHook;
        private Function<Integer, Component> valueFormatter;
        private ValidatorProvider<Range> validatorProvider;

        Builder() {}

        public Builder id(Identifier id) {
            this.id = Objects.requireNonNull(id);
            return this;
        }

        public Builder name(Component name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }

        public Builder tooltip(@Nullable Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder defaultValue(int defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder impact(OptionImpact impact) {
            this.impact = Objects.requireNonNull(impact);
            return this;
        }

        public Builder flags(EnumSet<OptionFlag> flags) {
            this.flags = Objects.requireNonNull(flags);
            return this;
        }

        public Builder addFlag(OptionFlag flag) {
            this.flags.add(Objects.requireNonNull(flag));
            return this;
        }

        /**
         * 设置静态范围约束（必填）。
         *
         * @param range Range 实例，定义 min/max/step
         * @return      this
         */
        public Builder range(Range range) {
            this.range = Objects.requireNonNull(range);
            return this;
        }

        public Builder setter(@Nullable Consumer<Integer> setter) {
            this.setter = setter;
            return this;
        }

        public Builder getter(@Nullable Supplier<Integer> getter) {
            this.getter = getter;
            return this;
        }

        public Builder storageHandler(@Nullable Object handler) {
            this.storageHandler = handler;
            return this;
        }

        public Builder enabledProvider(@Nullable EnabledProvider provider) {
            this.enabledProvider = provider;
            return this;
        }

        public Builder applyHook(@Nullable ApplyHook hook) {
            this.applyHook = hook;
            return this;
        }

        /**
         * 设置值格式化器。
         *
         * @param formatter 格式化函数，接收整数返回 Component
         * @return          this
         */
        public Builder valueFormatter(@Nullable Function<Integer, Component> formatter) {
            this.valueFormatter = formatter;
            return this;
        }

        /**
         * 设置动态验证器提供者。
         *
         * @param provider 根据 ConfigState 返回动态 Range 的函数
         * @return         this
         */
        public Builder validatorProvider(@Nullable ValidatorProvider<Range> provider) {
            this.validatorProvider = provider;
            return this;
        }

        public IntegerOption build() {
            if (this.id == null) {
                throw new IllegalStateException("Option id is required");
            }
            if (this.name == null) {
                throw new IllegalStateException("Option name is required");
            }
            if (this.range == null) {
                throw new IllegalStateException("Range is required for IntegerOption");
            }
            return new IntegerOption(
                this.id,
                this.name,
                this.tooltip,
                this.defaultValue,
                this.impact,
                this.flags,
                this.range,
                this.setter,
                this.getter,
                this.storageHandler,
                this.enabledProvider,
                this.applyHook,
                this.valueFormatter,
                this.validatorProvider
            );
        }
    }

    // ==================== 内部辅助类 ====================

    /** ConfigState 线程本地访问器（简化版，后续 Task 完善） */
    private static final class ConfigStateAccessor {
        private static final ThreadLocal<ConfigState> STATE = new ThreadLocal<>();

        static ConfigState getCurrentState() {
            return STATE.get();
        }

        static void setCurrentState(ConfigState state) {
            STATE.set(state);
        }
    }

    /** 选项文本搜索源 */
    private static final class OptionTextSource {
        private final IntegerOption option;
        private final RendererOptionGroup group;

        OptionTextSource(IntegerOption option, RendererOptionGroup group) {
            this.option = option;
            this.group = group;
        }

        @Override
        public String toString() {
            return String.format(
                "IntegerOption[id=%s, name=%s, value=%d, range=%s]",
                this.option.getId(),
                this.option.getName(),
                this.option.getValue(),
                this.option.getRange()
            );
        }
    }
}
