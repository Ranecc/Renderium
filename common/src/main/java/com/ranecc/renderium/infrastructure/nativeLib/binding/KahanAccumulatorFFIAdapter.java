// Renderium Accelerator - Kahan/Neumaier高精度累加器接口 (FFI适配器)
// 迁移自: com.renderium.accel.KahanAccumulator
// 目标包: com.ranecc.renderium.infrastructure.nativeLib.binding
package com.ranecc.renderium.infrastructure.nativeLib.binding;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import com.ranecc.renderium.infrastructure.gpu.NativeLibraryLoader;

/**
 * Kahan/Neumaier高精度累加器 FFI适配器 - 数值精度接近f80
 *
 * <p>职责: 作为Java与C++原生库之间的FFI桥接层</p>
 * <p>GPU亲和度: 中 (批量可并行，单次CPU更优)</p>
 */
public final class KahanAccumulatorFFIAdapter {

    private final NativeLibraryLoader loader;

    public KahanAccumulatorFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    public long createContext(double initialSum) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCtx = arena.allocate(ValueLayout.JAVA_LONG);
            MethodHandle mh = loader.get("accel_kahan_createContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS));
            int rc = (int) mh.invokeExact(initialSum, outCtx);
            if (rc != 0) throw new IllegalStateException("Kahan createContext失败，错误码: " + rc);
            return outCtx.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan createContext失败", e);
        }
    }

    public void add(long context, double value) {
        try {
            MethodHandle mh = loader.get("accel_kahan_add",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));
            mh.invokeExact(context, value);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan add失败", e);
        }
    }

    public void batchAdd(long context, double[] values) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment valSeg = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, values);
            MethodHandle mh = loader.get("accel_kahan_batchAdd",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            mh.invokeExact(context, valSeg, (long) values.length);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan batchAdd失败", e);
        }
    }

    public double[] getState(long context) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(24);
            MethodHandle mh = loader.get("accel_kahan_getState",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            int rc = (int) mh.invokeExact(context, buf);
            if (rc != 0) throw new IllegalStateException("Kahan getState失败，错误码: " + rc);
            return new double[]{buf.get(ValueLayout.JAVA_DOUBLE, 0), buf.get(ValueLayout.JAVA_DOUBLE, 8), buf.get(ValueLayout.JAVA_LONG, 16)};
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan getState失败", e);
        }
    }

    public void reset(long context, double newInitialSum) {
        try {
            MethodHandle mh = loader.get("accel_kahan_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));
            int rc = (int) mh.invokeExact(context, newInitialSum);
            if (rc != 0) throw new IllegalStateException("Kahan reset失败，错误码: " + rc);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan reset失败", e);
        }
    }

    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_kahan_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            int rc = (int) mh.invokeExact(context);
            if (rc != 0) throw new IllegalStateException("Kahan destroyContext失败，错误码: " + rc);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan destroyContext失败", e);
        }
    }
}
