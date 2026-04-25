// Renderium Accelerator - Kahan/Neumaier高精度累加器接口
package com.renderium.accel;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Kahan/Neumaier高精度累加器 - 数值精度接近f80
 *
 * <p>GPU亲和度: 中 (批量可并行，单次CPU更优)</p>
 * <p>使用方法:</p>
 * <pre>
 *   long ctx = kahan.createContext(0.0);
 *   kahan.add(ctx, 1.0);
 *   kahan.add(ctx, 1e100);
 *   kahan.add(ctx, 1.0);
 *   kahan.add(ctx, -1e100);
 *   double[] state = kahan.getState(ctx);
 *   double total = state[0] + state[1]; // = 2.0 (补偿消除大数吃小数)
 *   kahan.destroyContext(ctx);
 * </pre>
 */
public final class KahanAccumulator {

    private final NativeLibraryLoader loader;

    KahanAccumulator(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建Kahan累加器
     * @param initialSum 初始值
     * @return 上下文句柄
     */
    public long createContext(double initialSum) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCtx = arena.allocate(ValueLayout.JAVA_LONG);

            MethodHandle mh = loader.get("accel_kahan_createContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(initialSum, outCtx);
            if (rc != 0) throw new IllegalStateException("Kahan createContext失败，错误码: " + rc);

            return outCtx.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan createContext失败", e);
        }
    }

    /**
     * 累加单个值
     * @param context 上下文句柄
     * @param value 要累加的值
     */
    public void add(long context, double value) {
        try {
            MethodHandle mh = loader.get("accel_kahan_add",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));
            mh.invokeExact(context, value);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan add失败", e);
        }
    }

    /**
     * 批量累加
     * @param context 上下文句柄
     * @param values 数组
     */
    public void batchAdd(long context, double[] values) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment valSeg = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, values);

            MethodHandle mh = loader.get("accel_kahan_batchAdd",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            mh.invokeExact(context, valSeg, (long) values.length);
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan batchAdd失败", e);
        }
    }

    /**
     * 获取累加器状态
     * @param context 上下文句柄
     * @return [sum, compensation, elementCount] (sum+compensation=真实结果)
     */
    public double[] getState(long context) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(24);

            MethodHandle mh = loader.get("accel_kahan_getState",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(context, buf);
            if (rc != 0) throw new IllegalStateException("Kahan getState失败，错误码: " + rc);

            return new double[]{
                buf.get(ValueLayout.JAVA_DOUBLE, 0),
                buf.get(ValueLayout.JAVA_DOUBLE, 8),
                buf.get(ValueLayout.JAVA_LONG, 16)
            };
        } catch (Throwable e) {
            throw new IllegalStateException("Kahan getState失败", e);
        }
    }

    /**
     * 重置累加器
     * @param context 上下文句柄
     * @param newInitialSum 新初始值
     */
    public void reset(long context, double newInitialSum) {
        try {
            MethodHandle mh = loader.get("accel_kahan_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));
            mh.invokeExact(context, newInitialSum);
        } catch (Throwable e) {
            // 静默处理
        }
    }

    /**
     * 销毁累加器
     * @param context 上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_kahan_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理
        }
    }
}
