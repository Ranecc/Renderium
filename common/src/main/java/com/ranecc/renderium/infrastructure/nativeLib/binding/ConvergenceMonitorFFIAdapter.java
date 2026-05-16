// Renderium Accelerator - 收敛监控器接口 (FFI适配器)
// 迁移自: com.renderium.accel.ConvergenceMonitor
// 目标包: com.ranecc.renderium.infrastructure.nativeLib.binding
package com.ranecc.renderium.infrastructure.nativeLib.binding;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import com.ranecc.renderium.infrastructure.gpu.NativeLibraryLoader;

/**
 * 收敛监控器 FFI适配器 - 四维收敛检测 (位置/速度/能量/质量)
 *
 * <p>职责: 作为Java与C++原生库之间的FFI桥接层</p>
 * <p>GPU亲和度: 低 (有状态顺序检查)</p>
 * <p>使用EMA平滑残差避免噪声误判</p>
 * <p>使用方法:</p>
 * <pre>
 *   long ctx = convergence.createContext(0.01f, 100);
 *   boolean posOk = convergence.check(ctx, CONV_DIM_POSITION, current, target);
 *   convergence.destroyContext(ctx);
 * </pre>
 */
public final class ConvergenceMonitorFFIAdapter {

    /** NativeLibraryLoader实例，用于获取C++函数句柄 */
    private final NativeLibraryLoader loader;

    /** 收敛维度常量: 位置 */
    public static final int DIM_POSITION = 0;
    /** 收敛维度常量: 速度 */
    public static final int DIM_VELOCITY = 1;
    /** 收敛维度常量: 能量 */
    public static final int DIM_ENERGY = 2;
    /** 收敛维度常量: 质量 */
    public static final int DIM_QUALITY = 3;

    /**
     * 构造函数 - 通过NativeLibraryLoader初始化FFI适配器
     * @param loader Native库加载器实例
     */
    ConvergenceMonitorFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建收敛监控器上下文
     *
     * @param tolerance 容差阈值 (建议: 0.001-0.1)
     * @param maxIterations 最大迭代次数 (建议: 50-500)
     * @return long 上下文句柄，用于后续操作
     * @throws IllegalStateException 创建失败时抛出
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
     *
     * @param context 上下文句柄 (由createContext返回)
     * @param dimension 维度标识 (使用DIM_POSITION等常量)
     * @param currentValue 当前值
     * @param targetValue 目标值
     * @return boolean true表示该维度已收敛，false表示未收敛或出错
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
     * 获取监控器当前状态
     *
     * @param context 上下文句柄
     * @return float[] 包含8个元素:
     *         [emaResidual_DIM0, emaResidual_DIM1, emaResidual_DIM2, emaResidual_DIM3,
     *          iterationCount_DIM0, iterationCount_DIM1, iterationCount_DIM2, iterationCount_DIM3]
     *         失败时返回空数组
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
     * 重置监控器状态
     *
     * @param context 要重置的上下文句柄
     */
    public void reset(long context) {
        try {
            MethodHandle mh = loader.get("accel_convergence_reset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理重置错误
        }
    }

    /**
     * 销毁监控器并释放资源
     *
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_convergence_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理销毁错误
        }
    }
}
