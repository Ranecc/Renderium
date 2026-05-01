// Renderium - 布尔型渲染器选项实现
// 用于开/关类型的设置项（如 VSync、调试信息显示等）

package com.renderium.config.structure;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 布尔型渲染器选项。
 *
 * <p>表示一个二值开关选项（true/false），适用于：
 * <ul>
 *   <li><b>功能开关</b>：VSync、垂直同步、平滑光照等</li>
 *   <li><b>显示控制</b>：FPS 显示、坐标显示、调试信息等</li>
 *   <li><b>模式切换</b>：高级模式、实验性功能启用等</li>
 *   <li><b>特性激活</b>：光线追踪、DLSS/FSR 等</li>
 * </ul>
 *
 * <h2>UI 控件</h2>
 * <p>通常使用复选框（Checkbox）或开关按钮（Toggle Button）展示，
 * 用户点击即可切换状态。
 *
 * <h2>数据流</h2>
 * <pre>
 * ┌──────────┐    setValue()    ┌────────────┐    save()    ┌──────────┐
 * │  UI 控件  │ ──────────────→ │ BooleanOpt │ ──────────→ │ Binding  │
 * │ (CheckBox)│ ←────────────── │   ion      │ ←────────── │ (目标对象) │
 * └──────────┘    getValue()    └────────────┘    load()    └──────────┘
 *                                  │
 *                                  ├── defaultValue: Boolean
 *                                  ├── enabledProvider: EnabledProvider
 *                                  └── applyHook: ApplyHook (可选)
 * </pre>
 *
 * <h2>线程安全性</h2>
 * <p>{@code value} 字段使用 {@code volatile} 保证跨线程可见性，
 * 适用于 UI 线程和渲染线程并发访问的场景。
 *
 * <h2>参考实现</h2>
 * <p>参考 Sodium 的 {@code BooleanOption} 设计，
 * 使用 Renderium 中性命名并增强 Builder 模式支持。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererOption
 * @see IntegerOption
 * @see EnumOption
 */
public final class BooleanOption implements SearchableOption {

    /** 选项唯一标识符 */
    private final Identifier id;

    /** 显示名称 */
    private final Component name;

    /** 工具提示文本（可选） */
    @Nullable
    private final Component tooltip;

    /** 默认值 */
    private final boolean defaultValue;

    /** 性能影响级别 */
    private final OptionImpact impact;

    /** 特性标记集合 */
    private final EnumSet<OptionFlag> flags;

    /** 值写入绑定（Setter）：将新值同步到目标对象 */
    @Nullable
    private final Consumer<Boolean> setter;

    /** 值读取绑定（Getter）：从目标对象读取当前值 */
    @Nullable
    private final Supplier<Boolean> getter;

    /** 存储处理器（可选，用于配置持久化事件通知） */
    @Nullable
    private final Object storageHandler;

    /** 启用状态提供者（可选，用于动态禁用逻辑） */
    @Nullable
    private final EnabledProvider enabledProvider;

    /** 应用钩子（可选，值变更时触发副作用） */
    @Nullable
    private final ApplyHook applyHook;

    /** 当前值（volatile 保证线程安全） */
    private volatile boolean value;

    /**
     * 创建新的布尔选项实例。
     *
     * <p>建议使用 {@link #builder()} 进行构建，以获得更好的可读性。
     *
     * @param id              选项唯一标识符
     * @param name            显示名称
     * @param tooltip         工具提示（可为 null）
     * @param defaultValue    默认值
     * @param impact          性能影响级别
     * @param flags           特性标记集合
     * @param setter          值写入绑定（可选）
     * @param getter          值读取绑定（可选）
     * @param storageHandler  存储处理器（可选）
     * @param enabledProvider 启用状态提供者（可选）
     * @param applyHook       应用钩子（可选）
     */
    public BooleanOption(
            Identifier id,
            Component name,
            @Nullable Component tooltip,
            boolean defaultValue,
            OptionImpact impact,
            EnumSet<OptionFlag> flags,
            @Nullable Consumer<Boolean> setter,
            @Nullable Supplier<Boolean> getter,
            @Nullable Object storageHandler,
            @Nullable EnabledProvider enabledProvider,
            @Nullable ApplyHook applyHook) {
        this.id = Objects.requireNonNull(id, "Option id cannot be null");
        this.name = Objects.requireNonNull(name, "Option name cannot be null");
        this.tooltip = tooltip;
        this.defaultValue = defaultValue;
        this.impact = Objects.requireNonNull(impact, "Impact cannot be null");
        this.flags = Objects.requireNonNull(flags, "Flags cannot be null");
        this.setter = setter;
        this.getter = getter;
        this.storageHandler = storageHandler;
        this.enabledProvider = enabledProvider;
        this.applyHook = applyHook;
        this.value = defaultValue;
    }

    // ==================== RendererOption 接口实现 ====================

    /**
     * {@inheritDoc}
     *
     * @return 此选项的 Identifier 实例
     */
    @Override
    public Identifier getId() {
        return this.id;
    }

    /**
     * {@inheritDoc}
     *
     * @return 显示名称 Component
     */
    @Override
    public Component getName() {
        return this.name;
    }

    /**
     * {@inheritDoc}
     *
     * @return 性能影响级别枚举
     */
    @Override
    public OptionImpact getImpact() {
        return this.impact;
    }

    /**
     * {@inheritDoc}
     *
     * @return 不可变的特性标记集合
     */
    @Override
    public EnumSet<OptionFlag> getFlags() {
        return this.flags;  // EnumSet 本身不可变（如果通过 EnumSet.of 创建）
    }

    /**
     * 判断此选项在给定状态下是否可用。
     *
     * <p>如果配置了 {@link EnabledProvider}，则委托给其进行动态判断；
     * 否则默认返回 true（始终可用）。
     *
     * @param state 配置状态上下文
     * @return      true 表示可用；false 表示应禁用
     */
    @Override
    public boolean isEnabled(ConfigState state) {
        if (this.enabledProvider != null) {
            return this.enabledProvider.isEnabled(state);
        }
        return true;
    }

    /**
     * 将此选项重置为默认值。
     *
     * <p>将内部值恢复为构造时指定的 defaultValue，
     * 并同步更新绑定目标（如果配置了 setter）。
     */
    @Override
    public void resetToDefault() {
        this.value = this.defaultValue;
        if (this.setter != null) {
            this.setter.accept(this.defaultValue);
        }
    }

    // ==================== SearchableOption 接口实现 ====================

    /**
     * 注册此选项到搜索索引。
     *
     * <p>创建包含选项名称的文本源并注册到索引中，
     * 支持用户通过关键词搜索定位此选项。
     *
     * @param index       搜索索引
     * @param modOptions   模块选项容器
     * @param optionGroup  所属分组
     */
    @Override
    public void registerTextSources(SearchIndex index, Object modOptions, RendererOptionGroup optionGroup) {
        index.register(new OptionTextSource(this, optionGroup));
    }

    // ==================== 值访问方法 ====================

    /**
     * 获取当前值。
     *
     * <p>如果配置了 getter 绑定，优先从绑定目标读取实时值；
     * 否则返回内部缓存的值。
     *
     * <p><b>线程安全</b>：getter 调用可能涉及外部对象访问，
     * 需确保绑定目标的线程安全性。
     *
     * @return 当前布尔值
     *
     * <h4>读取策略</h4>
     * <pre>
     * // 有 getter 时：实时读取
     * boolean current = this.getter.get();
     *
     * // 无 getter 时：返回缓存
     * return this.value;
     * </pre>
     */
    public boolean getValue() {
        if (this.getter != null) {
            return this.getter.get();
        }
        return this.value;
    }

    /**
     * 设置新值。
     *
     * <p>更新内部缓存值，并通过 setter 同步到绑定目标（如果已配置）。
     * 新值会立即生效，但实际的"应用"操作（如触发 ApplyHook）
     * 应由外部调用者显式触发。
     *
     * @param newValue 要设置的布尔值
     *
     * <h4>调用示例</h4>
     * <pre>
     * // 用户点击复选框
     * boolean currentValue = option.getValue();
     * option.setValue(!currentValue);  // 切换状态
     * </pre>
     */
    public void setValue(boolean newValue) {
        this.value = newValue;
        if (this.setter != null) {
            this.setter.accept(newValue);
        }
    }

    // ==================== 元数据访问方法 ====================

    /**
     * 获取工具提示文本。
     *
     * @return 工具提示 Component，若未设置则返回 null
     */
    @Nullable
    public Component getTooltip() {
        return this.tooltip;
    }

    /**
     * 获取默认值。
     *
     * @return 构造时指定的默认布尔值
     */
    public boolean getDefaultValue() {
        return this.defaultValue;
    }

    /**
     * 获取存储处理器。
     *
     * @return 存储处理器对象，若未配置则返回 null
     */
    @Nullable
    public Object getStorageHandler() {
        return this.storageHandler;
    }

    /**
     * 获取启用状态提供者。
     *
     * @return EnabledProvider 实例，若未配置则返回 null
     */
    @Nullable
    public EnabledProvider getEnabledProvider() {
        return this.enabledProvider;
    }

    /**
     * 获取应用钩子。
     *
     * @return ApplyHook 实例，若未配置则返回 null
     */
    @Nullable
    public ApplyHook getApplyHook() {
        return this.applyHook;
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

    // ==================== 序列化支持（占位） ====================

    /**
     * 将此选项序列化为 JSON 字符串（占位方法）。
     *
     * <p><b>注意</b>：完整序列化实现将在后续 Task 中提供，
     * 基于 Gson 或 Jackson 实现 TypeAdapter。
     *
     * @return JSON 格式的字符串表示
     */
    public String toJson() {
        return String.format(
            "{\"id\":\"%s\",\"value\":%b}",
            this.id, this.value
        );
    }

    /**
     * 从 JSON 字符串反序列化创建 BooleanOption（静态工厂占位）。
     *
     * @param json JSON 字符串
     * @return     新的 BooleanOption 实例
     * @throws UnsupportedOperationException 当前为占位实现
     */
    public static BooleanOption fromJson(String json) {
        throw new UnsupportedOperationException(
            "BooleanOption.fromJson() will be implemented in a future task"
        );
    }

    // ==================== Builder 模式 ====================

    /**
     * 创建 BooleanOption 的构建器。
     *
     * <p>推荐使用 Builder 模式创建实例，提供流畅的 API 和编译时检查。
     *
     * @return 新的 Builder 实例
     *
     * <h4>完整构建示例</h4>
     * <pre>
     * BooleanOption vsync = BooleanOption.builder()
     *     .id(Identifier.parse("renderium:vsync_enabled"))
     *     .name(Component.translatable("renderium.option.vsync"))
     *     .tooltip(Component.translatable("renderium.option.vsync.tooltip"))
     *     .defaultValue(true)
     *     .impact(OptionImpact.LOW)
     *     .flags(EnumSet.of(OptionFlag.APPLY_AND_CONTINUE))
     *     .setter(value -> GraphicsConfig.setVsync(value))
     *     .getter(() -> GraphicsConfig.isVsync())
     *     .enabledProvider(state ->
     *         !state.readBoolOption(Identifier.parse("renderium:exclusive_fullscreen"))
     *     )
     *     .build();
     * </pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * BooleanOption 的构建器。
     *
     * <p>所有字段均为可选，除了必填的 id 和 name。
     * 未设置的字段将使用合理的默认值：
     * <ul>
     *   <li>defaultValue: false</li>
     *   <li>impact: {@link OptionImpact#LOW}</li>
     *   <li>flags: 空集</li>
     *   <li>其余字段: null</li>
     * </ul>
     */
    public static final class Builder {

        private Identifier id;
        private Component name;
        private Component tooltip;
        private boolean defaultValue;
        private OptionImpact impact = OptionImpact.LOW;
        private EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        private Consumer<Boolean> setter;
        private Supplier<Boolean> getter;
        private Object storageHandler;
        private EnabledProvider enabledProvider;
        private ApplyHook applyHook;

        Builder() {}

        /**
         * 设置选项标识符（必填）。
         *
         * @param id 标识符，格式如 "renderium:option_name"
         * @return   this
         */
        public Builder id(Identifier id) {
            this.id = Objects.requireNonNull(id);
            return this;
        }

        /**
         * 设置显示名称（必填）。
         *
         * @param name 显示名称 Component
         * @return     this
         */
        public Builder name(Component name) {
            this.name = Objects.requireNonNull(name);
            return this;
        }

        /**
         * 设置工具提示文本（可选）。
         *
         * @param tooltip 工具提示 Component
         * @return        this
         */
        public Builder tooltip(@Nullable Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        /**
         * 设置默认值（可选，默认 false）。
         *
         * @param defaultValue 默认布尔值
         * @return             this
         */
        public Builder defaultValue(boolean defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        /**
         * 设置性能影响级别（可选，默认 LOW）。
         *
         * @param impact 影响级别枚举
         * @return       this
         */
        public Builder impact(OptionImpact impact) {
            this.impact = Objects.requireNonNull(impact);
            return this;
        }

        /**
         * 设置特性标记（可选，默认空集）。
         *
         * @param flags 标记集合
         * @return      this
         */
        public Builder flags(EnumSet<OptionFlag> flags) {
            this.flags = Objects.requireNonNull(flags);
            return this;
        }

        /**
         * 添加单个特性标记。
         *
         * @param flag 要添加的标记
         * @return     this
         */
        public Builder addFlag(OptionFlag flag) {
            this.flags.add(Objects.requireNonNull(flag));
            return this;
        }

        /**
         * 设置值写入绑定（可选）。
         *
         * <p>当选项值变更时，setter 会被调用来同步到目标对象。
         *
         * @param setter 布尔值消费者
         * @return       this
         */
        public Builder setter(@Nullable Consumer<Boolean> setter) {
            this.setter = setter;
            return this;
        }

        /**
         * 设置值读取绑定（可选）。
         *
         * <p>当需要获取当前值时，优先从 getter 读取而非内部缓存。
         *
         * @param getter 布尔值供应者
         * @return       this
         */
        public Builder getter(@Nullable Supplier<Boolean> getter) {
            this.getter = getter;
            return this;
        }

        /**
         * 设置存储处理器（可选）。
         *
         * @param handler 存储处理器对象
         * @return        this
         */
        public Builder storageHandler(@Nullable Object handler) {
            this.storageHandler = handler;
            return this;
        }

        /**
         * 设置启用状态提供者（可选）。
         *
         * <p>用于根据运行时条件动态决定选项是否可用。
         *
         * @param provider 启用状态判断函数
         * @return         this
         */
        public Builder enabledProvider(@Nullable EnabledProvider provider) {
            this.enabledProvider = provider;
            return this;
        }

        /**
         * 设置应用钩子（可选）。
         *
         * <p>值被应用到绑定目标后触发的回调。
         *
         * @param hook 应用钩子
         * @return     this
         */
        public Builder applyHook(@Nullable ApplyHook hook) {
            this.applyHook = hook;
            return this;
        }

        /**
         * 构建不可变的 BooleanOption 实例。
         *
         * @return 新的 BooleanOption 实例
         * @throws IllegalStateException 若未设置 id 或 name
         */
        public BooleanOption build() {
            if (this.id == null) {
                throw new IllegalStateException("Option id is required");
            }
            if (this.name == null) {
                throw new IllegalStateException("Option name is required");
            }
            return new BooleanOption(
                this.id,
                this.name,
                this.tooltip,
                this.defaultValue,
                this.impact,
                this.flags,
                this.setter,
                this.getter,
                this.storageHandler,
                this.enabledProvider,
                this.applyHook
            );
        }
    }

    // ==================== 内部辅助类 ====================

    /**
     * 选项文本搜索源。
     *
     * <p>包装 BooleanOption 实例，提供搜索索引所需的文本内容。
     * 后续 Task 中将扩展为完整的 TextSource 实现。
     */
    private static final class OptionTextSource {
        private final BooleanOption option;
        private final RendererOptionGroup group;

        OptionTextSource(BooleanOption option, RendererOptionGroup group) {
            this.option = option;
            this.group = group;
        }

        @Override
        public String toString() {
            return String.format(
                "BooleanOption[id=%s, name=%s, group=%s]",
                this.option.getId(),
                this.option.getName(),
                this.group.hasTitle() ? this.group.name() : "(untitled)"
            );
        }
    }
}
