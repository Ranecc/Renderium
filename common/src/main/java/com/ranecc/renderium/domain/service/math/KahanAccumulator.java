// 迁移自: 1.0.0: com.renderium.core.math.KahanAccumulator
// 迁移目标: com.ranecc.renderium.domain.service.math
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变

package com.ranecc.renderium.domain.service.math;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

public final class KahanAccumulator {

    private static final class AccumulatorState {
        final float sum;
        final float compensation;

        AccumulatorState() {
            this.sum = 0.0f;
            this.compensation = 0.0f;
        }

        AccumulatorState(float sum, float compensation) {
            this.sum = sum;
            this.compensation = compensation;
        }
    }

    private final AtomicReference<AccumulatorState> state;
    private final AtomicLong operationCount;

    public KahanAccumulator() {
        this.state = new AtomicReference<>(new AccumulatorState());
        this.operationCount = new AtomicLong(0L);
    }

    public float add(final float value) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException("KahanAccumulator does not support NaN input");
        }
        while (true) {
            AccumulatorState current = state.get();
            final float y = value - current.compensation;
            final float t = current.sum + y;
            final float newCompensation = (t - current.sum) - y;
            AccumulatorState newState = new AccumulatorState(t, newCompensation);
            if (state.compareAndSet(current, newState)) {
                operationCount.incrementAndGet();
                return t;
            }
        }
    }

    public float getSum() { return state.get().sum; }

    public float getCompensation() { return state.get().compensation; }

    public void reset() {
        state.set(new AccumulatorState());
        operationCount.set(0L);
    }

    public long getOperationCount() { return operationCount.get(); }

    @Override
    public String toString() {
        AccumulatorState s = state.get();
        return String.format("KahanAccumulator{sum=%.16e, compensation=%.16e, operations=%d}",
            s.sum, s.compensation, operationCount.get());
    }

    public static float accumulate(final float[] values) {
        if (values == null) throw new NullPointerException("Input array cannot be null");
        KahanAccumulator acc = new KahanAccumulator();
        for (float v : values) acc.add(v);
        return acc.getSum();
    }

    public static float naiveSum(final float[] values) {
        if (values == null) throw new NullPointerException("Input array cannot be null");
        float sum = 0.0f;
        for (float v : values) sum += v;
        return sum;
    }
}
