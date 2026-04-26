package com.renderium.bridge.video;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.renderium.config.structure.FloatOption;
import com.renderium.config.structure.OptionImpact;
import com.renderium.config.structure.OptionFlag;
import com.renderium.config.structure.RendererOption;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class FloatOptionBuilder {

    private final Identifier id;
    private Component name = Component.empty();
    private Component tooltip = null;
    private float defaultValue = 0f;
    private FloatOption.FloatRange range = null;
    private Function<Float, Component> valueFormatter = null;
    private Consumer<Float> setter = null;
    private Supplier<Float> getter = null;
    private OptionImpact impact = OptionImpact.LOW;
    private EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
    private Supplier<Boolean> enabledProvider = () -> true;
    private Supplier<FloatOption.FloatRange> validatorProvider = null;

    FloatOptionBuilder(Identifier id) {
        if (id == null) throw new IllegalArgumentException("Option id must not be null");
        this.id = id;
    }

    public FloatOptionBuilder setName(Component name) {
        if (name == null) throw new IllegalArgumentException("Option name must not be null");
        this.name = name;
        return this;
    }

    public FloatOptionBuilder displayName(String name) {
        return setName(Component.literal(name));
    }

    public FloatOptionBuilder setTooltip(Component tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public FloatOptionBuilder description(String desc) {
        return setTooltip(Component.literal(desc));
    }

    public FloatOptionBuilder setDefaultValue(float value) {
        this.defaultValue = value;
        return this;
    }

    public FloatOptionBuilder defaultValue(float value) {
        return setDefaultValue(value);
    }

    public FloatOptionBuilder setBinding(Consumer<Float> setter, Supplier<Float> getter) {
        if (setter == null || getter == null) throw new IllegalArgumentException("Setter and getter must not be null");
        this.setter = setter;
        this.getter = getter;
        return this;
    }

    public FloatOptionBuilder setImpact(OptionImpact impact) {
        if (impact == null) throw new IllegalArgumentException("Option impact must not be null");
        this.impact = impact;
        return this;
    }

    public FloatOptionBuilder setFlags(OptionFlag... flags) {
        if (flags == null) throw new IllegalArgumentException("Flags array must not be null");
        for (OptionFlag flag : flags) {
            if (flag == null) throw new IllegalArgumentException("Flag must not be null");
        }
        this.flags = EnumSet.copyOf(Arrays.asList(flags));
        return this;
    }

    public FloatOptionBuilder setRange(float min, float max, float step) {
        this.range = new FloatOption.FloatRange(min, max, step);
        return this;
    }

    public FloatOptionBuilder range(float min, float max, float step) {
        return setRange(min, max, step);
    }

    public FloatOptionBuilder setRange(FloatOption.FloatRange range) {
        if (range == null) throw new IllegalArgumentException("Range must not be null");
        this.range = range;
        return this;
    }

    public FloatOptionBuilder unit(String unit) {
        return this;
    }

    public FloatOptionBuilder setValueFormatter(Function<Float, Component> formatter) {
        this.valueFormatter = formatter;
        return this;
    }

    public FloatOptionBuilder setEnabledProvider(Supplier<Boolean> enabledProvider) {
        if (enabledProvider == null) throw new IllegalArgumentException("Enabled provider must not be null");
        this.enabledProvider = enabledProvider;
        return this;
    }

    public FloatOptionBuilder flag(OptionFlag flag) {
        this.flags = EnumSet.of(flag);
        return this;
    }

    public FloatOptionBuilder suffix(String suffix) {
        return this;
    }

    public FloatOptionBuilder advanced() {
        return this;
    }

    public FloatOptionBuilder setValidatorProvider(Supplier<FloatOption.FloatRange> validatorProvider) {
        this.validatorProvider = validatorProvider;
        return this;
    }

    public FloatOptionBuilder onChange(java.util.function.BiConsumer<RendererOption, Object> handler) { return this; }

    public FloatOption build() {
        if (this.name == Component.empty()) {
            throw new IllegalStateException(String.format("Float option '%s' must have a name", this.id));
        }
        if (this.range == null) {
            throw new IllegalStateException(String.format("Float option '%s' must have a range", this.id));
        }
        if (!this.range.isValid(this.defaultValue)) {
            throw new IllegalStateException(
                String.format("Default value %f for option '%s' out of range [%.2f, %.2f] step %.4f",
                    this.defaultValue, this.id, this.range.getMin(), this.range.getMax(), this.range.getStep())
            );
        }
        return new FloatOption(
            this.id, this.name, this.tooltip,
            this.defaultValue, this.range,
            this.valueFormatter, this.setter, this.getter,
            this.impact, this.flags,
            this.enabledProvider, this.validatorProvider
        );
    }
}
