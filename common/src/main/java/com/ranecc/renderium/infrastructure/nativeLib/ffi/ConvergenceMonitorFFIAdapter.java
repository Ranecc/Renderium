// Renderium Accelerator - 收敛监控器 FFI适配器
// 四维收敛检测 (位置/速度/能量/质量)
//
// 使用方法:
//   long ctx = convergence.createContext(0.01f, 100);
//   boolean posOk = convergence.check(ctx, DIM_POSITION, current, target);
//   convergence.destroyContext(ctx);
package com.ranecc.renderium.infrastructure.nativeLib.ffi;

import com.ranecc.renderium.infrastructure.nativeLib.NativeLibraryLoader;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * 收敛监控器 FFI适配器
 *
 * <p>四维收敛检测，监控位置/速度/能量/质量四个维度的收敛状态。</p>
 * <p>GPU亲和度: 低 (有状态顺序检查)</p>
 * <p>使用EMA(指数移动平均)平滑残差避免噪声误判</p>
 *
 * <h3>收敛维度:</h3>
 * <ul>
 *   <li>{@link #DIM_POSITION} - 位置收敛</li>
 *   <li>{@link #DIM_VELOCITY} - 速度收敛</li>
 *   <li>{@link #DIM_ENERGY} - 能量收敛</li>
 *   <li>{@link #DIM_QUALITY} - 质量收敛</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.1.0
 */
public final class ConvergenceMonitorFFIAdapter {

    private final NativeLibraryLoader loader;

    /** 收敛维度: 位置 */
    public static final int DIM_POSITION = 0;
    /** 收敛维度: 速度 */
    public static final int DIM_VELOCITY = 1;
    /** 收敛维度: 能量 */
    public static final int DIM_ENERGY = 2;
    /** 收敛维度: 质量 */
    public static final int DIM_QUALITY = 3;

    public ConvergenceMonitorFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建收敛监控器上下文
     * @param tolerance 容差阈值 (小于此值认为已收敛)
     * @param maxIterations 最大迭代次数
     * @return 上下文句柄 (long类型)
     */
    public long createContext(float tolerance, int maxIterations) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCtx = arena.allocate(ValueLayout.JAVA_LONG);

            MethodHandle mh = loader.get("accel_convergence_createContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(tolerance, maxIterations, outCtx);
            if (rc != 0) throw new IllegalStateException("Convergence createContext失败，错误码: " + rc);

            return outCtx.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("Convergence createContext失败", e);
        }
    }

    /**
     * 检查单维度收敛性
     * @param context 上下文句柄
     * @param dimension 维度标识 (DIM_POSITION/DIM_VELOCITY/DIM_ENERGY/DIM_QUALITY)
     * @param currentValue 当前值
     * @param targetValue 目标值
     * @return true 如果该维度已收敛（当前值在容差范围内）
     */
    public boolean check(long context, int dimension,
                          float currentValue, float targetValue) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment isConverged = arena.allocate(ValueLayout.JAVA_INT);

            MethodHandle mh = loader.get("accel_convergence_check",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(context, dimension,
                currentValue, targetValue, isConverged);
            if (rc != 0) return false;

            return isConverged.get(ValueLayout.JAVA_INT, 0) != 0;
        } catch (Throwable e) {
            throw new IllegalStateException("Convergence check失败", e);
        }
    }

    /**
     * 获取监控器全部状态
     * @param context 上下文句柄
     * @return float[8] 数组:
     *         [0-3] emaResidual - 各维度EMA平滑后的残差值
     *         [4-7] iterationCount - 各维度累计迭代次数
     */
    public float[] getState(long context) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(32);

            MethodHandle mh = loader.get("accel_convergence_getState",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(context, buf);
            if (rc != 0) return new float[8];

            float[] result = new float[8];
            for (int i = 0; i < 4; i++) {
                result[i] = buf.get(ValueLayout.JAVA_FLOAT, i * 4);
            }
            for (int i = 4; i < 8; i++) {
                result[i] = (float) buf.get(ValueLayout.JAVA_INT, i * 4);
            }
            return result;
        } catch (Throwable e) {
            return new float[8];
        }
    }

    /**
     * 重置监控器状态（清除所有维度的历史数据）
     * @param context 上下文句柄
     */
    public void reset(long context) {
        try {
            MethodHandle mh = loader.get("accel_convergence_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {}
    }

    /**
     * 销毁监控器上下文并释放资源
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_convergence_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {}
    }
}
