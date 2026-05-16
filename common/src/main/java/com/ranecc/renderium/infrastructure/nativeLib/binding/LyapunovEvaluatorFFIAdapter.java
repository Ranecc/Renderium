// Renderium Accelerator - Lyapunov质量评估接口 (FFI适配器)
// 迁移自: com.renderium.accel.LyapunovEvaluator
// 目标包: com.ranecc.renderium.infrastructure.nativeLib.binding
package com.ranecc.renderium.infrastructure.nativeLib.binding;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import com.ranecc.renderium.infrastructure.gpu.NativeLibraryLoader;

/**
 * Lyapunov质量评估器 FFI适配器 - 基于Lyapunov指数的帧质量评估
 *
 * <p>职责: 作为Java与C++原生库之间的FFI桥接层</p>
 * <p>GPU亲和度: 低 (有状态序列分析)</p>
 * <p>使用方法:</p>
 * <pre>
 *   long ctx = lyapunov.createContext(32);
 *   float[] result = lyapunov.evaluate(ctx, frameData, prevLyap, dt);
 *   lyapunov.destroyContext(ctx);
 * </pre>
 */
public final class LyapunovEvaluatorFFIAdapter {

    /** NativeLibraryLoader实例，用于获取C++函数句柄 */
    private final NativeLibraryLoader loader;

    /**
     * 构造函数 - 通过NativeLibraryLoader初始化FFI适配器
     * @param loader Native库加载器实例
     */
    LyapunovEvaluatorFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建Lyapunov评估器上下文
     *
     * @param windowSize 滑动窗口大小 (建议: 16-64)
     * @return long 上下文句柄，用于后续操作
     * @throws IllegalStateException 创建失败时抛出
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
     * 评估单帧渲染质量
     *
     * @param context 上下文句柄 (由createContext返回)
     * @param frameData 帧特征数据数组
     * @param previousLyapunov 前一帧的Lyapunov指数
     * @param deltaTimeSec 帧间隔时间(秒)
     * @return float[] 包含4个元素:
     *         [lyapunovExponent, qualityScore, isStable(0/1), degradationLevel]
     * @throws IllegalStateException 评估失败时抛出
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
     * 重置评估器状态
     *
     * @param context 要重置的上下文句柄
     */
    public void reset(long context) {
        try {
            MethodHandle mh = loader.get("accel_lyapunov_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理重置错误
        }
    }

    /**
     * 销毁评估器并释放资源
     *
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_lyapunov_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理销毁错误
        }
    }
}
