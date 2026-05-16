package com.ranecc.renderium.infrastructure.config.structure;

import net.minecraft.network.chat.Component;
import java.util.function.Function;

public final class IntegerOption implements RendererOption {
    private final String name;
    private final Range range;
    private final Function<Integer, Component> valueFormatter;
    private int value;

    public IntegerOption(String name, Range range, int defaultValue, Function<Integer, Component> valueFormatter) {
        this.name = name;
        this.range = range;
        this.value = defaultValue;
        this.valueFormatter = valueFormatter;
    }

    @Override
    public Component getName() {
        return Component.literal(name);
    }

    public Range getRange() {
        return range;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = clamp(value);
    }

    public int getValidatedValue() {
        return clamp(value);
    }

    public Function<Integer, Component> getValueFormatter() {
        return valueFormatter;
    }

    public Component formatValue(int v) {
        if (valueFormatter != null) {
            return valueFormatter.apply(v);
        }
        return Component.literal(String.valueOf(v));
    }

    public String getId() {
        return name.toLowerCase().replace(' ', '_');
    }

    public boolean isEnabled(Object context) {
        return true;
    }

    private int clamp(int v) {
        return Math.max(range.getMin(), Math.min(range.getMax(), v));
    }
}
