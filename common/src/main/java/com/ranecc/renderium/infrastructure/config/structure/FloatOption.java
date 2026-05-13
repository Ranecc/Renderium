package com.ranecc.renderium.infrastructure.config.structure;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.ranecc.renderium.None;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import com.ranecc.renderium.infrastructure.config.structure.OptionImpact;
public final class FloatOption implements SearchableOption {

    private final Identifier id;
    private final Component name;
    private final Component tooltip;
    private volatile float value;
    private final float defaultValue;
    private final FloatRange range;
    private final Function<Float, Component> valueFormatter;
    private final Consumer<Float> setter;
    private final Supplier<Float> getter;
    private final OptionImpact impact;
    private final EnumSet<OptionFlag> flags;
    private final Supplier<Boolean> enabledProvider;
    private final Supplier<FloatRange> validatorProvider;

    public FloatOption(
            Identifier id,
            Component name,
            @Nullable Component tooltip,
            float defaultValue,
            @Nullable FloatRange range,
            @Nullable Function<Float, Component> valueFormatter,
            @Nullable Consumer<Float> setter,
            @Nullable Supplier<Float> getter,
            OptionImpact impact,
            EnumSet<OptionFlag> flags,
            Supplier<Boolean> enabledProvider,
            @Nullable Supplier<FloatRange> validatorProvider
    ) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.tooltip = tooltip;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.range = range;
        this.valueFormatter = valueFormatter;
        this.setter = setter;
        this.getter = getter;
        this.impact = Objects.requireNonNull(impact);
        this.flags = Objects.requireNonNull(flags);
        this.enabledProvider = Objects.requireNonNull(enabledProvider);
        this.validatorProvider = validatorProvider;
    }

    @Override
    public Identifier getId() { return id; }

    @Override
    public Component getName() { return name; }

    public void resetToDefault() { setValue(defaultValue); }

    public Component getTooltip() { return tooltip; }

    public float getDefaultValue() { return defaultValue; }

    public float getValue() {
        if (getter != null) return getter.get();
        return value;
    }

    public void setValue(float newValue) {
        float validated = validateValue(newValue);
        this.value = validated;
        if (setter != null) {
            setter.accept(validated);
        }
    }

    public void reset() { setValue(defaultValue); }

    /**
     * 检查此选项是否启用（无状态版本）
     *
     * @return true 表示选项当前启用
     */
    public boolean isEnabled() { return enabledProvider.get(); }

    /**
     * 检查此选项在给定配置状态下是否应该启用
     * <p>
     * 实现 {@link RendererOption#isEnabled(ConfigState)} 接口契约。
     * 对于 FloatOption，当前实现忽略 ConfigState 参数，
     * 直接使用 enabledProvider 判断启用状态。
     * <p>
     * 如果将来需要支持基于其他选项的依赖关系检查，
     * 可在此方法中查询 ConfigState 来实现条件启用逻辑。
     *
     * @param state 配置状态上下文（当前未使用，保留用于未来扩展）
     * @return true 表示选项在给定状态下可编辑；false 表示应禁用
     */
    @Override
    public boolean isEnabled(ConfigState state) {
        // FloatOption 当前不使用 ConfigState 进行依赖检查
        // 直接委托给 enabledProvider
        return enabledProvider.get();
    }

    public OptionImpact getImpact() { return impact; }

    public EnumSet<OptionFlag> getFlags() { return flags; }

    @Nullable
    public FloatRange getRange() { return range; }

    private float validateValue(float value) {
        if (range != null) value = range.clamp(value);
        if (validatorProvider != null) {
            FloatRange dynamicRange = validatorProvider.get();
            if (dynamicRange != null) value = dynamicRange.clamp(value);
        }
        return value;
    }

    public Component formatValue(float v) {
        if (valueFormatter != null) return valueFormatter.apply(v);
        return Component.literal(String.format("%.2f", v));
    }

    public void syncFromGetter() {
        if (getter != null) this.value = validateValue(getter.get());
    }

    @Override
    public void registerTextSources(SearchIndex index, Object modOptions, RendererOptionGroup optionGroup) {
        index.register(new OptionTextSource(this, optionGroup));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FloatOption that)) return false;
        return Float.compare(that.value, value) == 0 && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id, value); }

    @Override
    public String toString() {
        return String.format("FloatOption[id=%s, value=%.2f, default=%.2f]", id, value, defaultValue);
    }

    public static final class FloatRange {
        private final float min;
        private final float max;
        private final float step;

        public FloatRange(float min, float max, float step) {
            if (min > max) throw new IllegalArgumentException("min > max");
            if (step <= 0) throw new IllegalArgumentException("step must be positive");
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public float getMin() { return min; }
        public float getMax() { return max; }
        public float getStep() { return step; }

        public float clamp(float value) { return Math.max(min, Math.min(max, value)); }
        public boolean contains(float value) { return value >= min && value <= max; }
        public boolean isValid(float value) { return contains(value); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FloatRange that)) return false;
                return Float.compare(that.min, min) == 0 &&
                       Float.compare(that.max, max) == 0 &&
                       Float.compare(that.step, step) == 0;
        }

        @Override
        public int hashCode() { return Objects.hash(min, max, step); }

        @Override
        public String toString() {
            return String.format("FloatRange[min=%.2f, max=%.2f, step=%.4f]", min, max, step);
        }
    }
}
