package com.ranecc.renderium.infrastructure.config.structure;

public final class Range {
    private final int min;
    private final int max;
    private final int step;

    public Range(int min, int max, int step) {
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int getStep() {
        return step;
    }
}
