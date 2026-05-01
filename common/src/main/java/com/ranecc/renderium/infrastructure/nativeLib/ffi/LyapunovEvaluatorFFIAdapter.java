// Renderium Accelerator - Lyapunov质量评估 FFI适配器
// 基于Lyapunov指数的帧质量评估
//
// 使用方法:
//   long ctx = lyapunov.createContext(32);
//   float[] result = lyapunov.evaluate(ctx, frameData, prevLyap, dt);
//   lyapunov.destroyContext(ctx);
package com.ranecc.renderium.infrastructure.nativeLib.ffi;

import com.ranecc.renderium.infrastructure.nativeLib.NativeLibraryLoader;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

/**
 * Lyapunov质量评估器 FFI适配器
 *
 * <p>基于Lyapunov指数分析渲染帧序列的混沌特性，评估画面质量。</p>
 * <p>GPU亲和度: 低 (有状态序列分析)</p>
 *
 * @author Renderium Team
 * @since 1.1.0
 */
public final class LyapunovEvaluatorFFIAdapter {

    private final NativeLibraryLoader loader;

    public LyapunovEvaluatorFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建Lyapunov评估器上下文
     * @param windowSize 滑动窗口大小 (建议: 16-64)
     * @return 上下文句柄 (long类型)
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
     * 评估单帧质量（基础版）
     * @param context 上下文句柄
     * @param frameData 帧特征数据数组 (如: FPS、GPU利用率、三角形数等)
     * @param previousLyapunov 前一帧的Lyapunov指数 (首次传0.0f)
     * @param deltaTimeSec 帧间隔时间(秒)
     * @return float[4] 数组:
     *         [0] lyapunovExponent - Lyapunov指数值
     *         [1] qualityScore - 质量分数 (0.0-1.0)
     *         [2] isStable - 是否稳定 (0=不稳定, 1=稳定)
     *         [3] degradationLevel - 退化等级 (0-3)
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
     * 完整帧评估（带纹理数据）
     * @param context 上下文句柄
     * @param textureData 纹理像素数据 (ByteBuffer，如RGBA格式)
     * @param deltaTimeSec 帧间隔时间(秒)
     * @return float[4] 数组，同 evaluate()
     */
    public float[] evaluateFrame(long context, ByteBuffer textureData, double deltaTimeSec) {
        try (Arena arena = Arena.ofConfined()) {
            int dataLen = textureData.remaining();
            byte[] bytes = new byte[dataLen];
            textureData.get(bytes);
            MemorySegment texSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            MemorySegment output = arena.allocate(16);

            MethodHandle mh = loader.get("accel_lyapunov_evaluateFrame",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS));

            long dataSize = textureData.remaining();
            int rc = (int) mh.invokeExact(context, texSeg, dataSize, deltaTimeSec, output);
            if (rc != 0) throw new IllegalStateException("Lyapunov evaluateFrame失败，错误码: " + rc);

            return new float[]{
                output.get(ValueLayout.JAVA_FLOAT, 0),
                output.get(ValueLayout.JAVA_FLOAT, 4),
                output.get(ValueLayout.JAVA_INT, 8),
                output.get(ValueLayout.JAVA_INT, 12)
            };
        } catch (Throwable e) {
            throw new IllegalStateException("Lyapunov evaluateFrame失败", e);
        }
    }

    /**
     * 计算Lyapunov指数（核心算法）
     * @param context 上下文句柄
     * @param timeSeries 时间序列数据
     * @param samplingRate 采样率
     * @return Lyapunov指数值 (正值=混沌，负值=稳定收敛)
     */
    public float computeLyapunovExponent(long context, float[] timeSeries, float samplingRate) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment series = arena.allocateFrom(ValueLayout.JAVA_FLOAT, timeSeries); // 修复: 原代码用了MemorySeries（不存在）
            MemorySegment outExp = arena.allocate(ValueLayout.JAVA_FLOAT);

            MethodHandle mh = loader.get("accel_lyapunov_computeExponent",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(context, series, (long) timeSeries.length, samplingRate, outExp);
            if (rc != 0) throw new IllegalStateException("Lyapunov computeExponent失败，错误码: " + rc);

            return outExp.get(ValueLayout.JAVA_FLOAT, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("Lyapunov computeExponent失败", e);
        }
    }

    /**
     * 从Lyapunov指数推导质量分数
     * @param context 上下文句柄
     * @param lyapunovValue Lyapunov指数值
     * @return 质量分数 (0.0=最差, 1.0=最佳)
     */
    public float deriveQualityScore(long context, float lyapunovValue) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outScore = arena.allocate(ValueLayout.JAVA_FLOAT);

            MethodHandle mh = loader.get("accel_lyapunov_deriveQuality",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(context, lyapunovValue, outScore);
            if (rc != 0) throw new IllegalStateException("Lyapunov deriveQuality失败，错误码: " + rc);

            return outScore.get(ValueLayout.JAVA_FLOAT, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("Lyapunov deriveQuality失败", e);
        }
    }

    /**
     * 重置评估器状态（清除历史窗口）
     * @param context 上下文句柄
     */
    public void reset(long context) {
        try {
            MethodHandle mh = loader.get("accel_lyapunov_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {}
    }

    /**
     * 销毁评估器上下文并释放资源
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_lyapunov_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {}
    }
}
