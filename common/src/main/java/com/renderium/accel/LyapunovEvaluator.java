// Renderium Accelerator - Lyapunov质量评估接口
package com.renderium.accel;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Lyapunov质量评估器 - 基于Lyapunov指数的帧质量评估
 *
 * <p>GPU亲和度: 低 (有状态序列分析)</p>
 * <p>使用方法:</p>
 * <pre>
 *   long ctx = lyapunov.createContext(32);
 *   float[] result = lyapunov.evaluate(ctx, frameData, prevLyap, dt);
 *   lyapunov.destroyContext(ctx);
 * </pre>
 */
public final class LyapunovEvaluator {

    private final NativeLibraryLoader loader;

    LyapunovEvaluator(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建Lyapunov评估器
     * @param windowSize 滑动窗口大小 (建议: 16-64)
     * @return 上下文句柄
     */
    public long createContext(int windowSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCtx = arena.allocate(ValueLayout.JAVA_LONG);

            MethodHandle mh = loader.get("accel_lyapunov_createContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(windowSize, outCtx);
            if (rc != 0) throw new IllegalStateException("Lyapunov createContext失败，错误码: " + rc);

            return outCtx.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("Lyapunov createContext失败", e);
        }
    }

    /**
     * 评估单帧质量
     * @param context 上下文句柄
     * @param frameData 帧特征数据
     * @param previousLyapunov 前一帧Lyapunov指数
     * @param deltaTimeSec 帧间隔(秒)
     * @return [lyapunovExponent, qualityScore, isStable(0/1), degradationLevel]
     */
    public float[] evaluate(long context, float[] frameData,
                             float previousLyapunov, double deltaTimeSec) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment frame = arena.allocateFrom(ValueLayout.JAVA_FLOAT, frameData);
            MemorySegment output = arena.allocate(16);

            MethodHandle mh = loader.get("accel_lyapunov_evaluate",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_FLOAT,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(context, frame, (long) frameData.length,
                previousLyapunov, deltaTimeSec, output);
            if (rc != 0) throw new IllegalStateException("Lyapunov evaluate失败，错误码: " + rc);

            return new float[]{
                output.get(ValueLayout.JAVA_FLOAT, 0),
                output.get(ValueLayout.JAVA_FLOAT, 4),
                output.get(ValueLayout.JAVA_INT, 8),
                output.get(ValueLayout.JAVA_INT, 12)
            };
        } catch (Throwable e) {
            throw new IllegalStateException("Lyapunov evaluate失败", e);
        }
    }

    /**
     * 重置评估器
     * @param context 上下文句柄
     */
    public void reset(long context) {
        try {
            MethodHandle mh = loader.get("accel_lyapunov_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理
        }
    }

    /**
     * 销毁评估器
     * @param context 上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_lyapunov_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理
        }
    }
}
