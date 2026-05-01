// Renderium Accelerator - LOD距离计算接口 (FFI适配器)
// 迁移自: com.renderium.accel.LodCalculator
// 目标包: com.ranecc.renderium.infrastructure.nativeLib.binding
package com.ranecc.renderium.infrastructure.nativeLib.binding;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * LOD距离计算器 FFI适配器 - 批量计算区块LOD等级
 *
 * <p>职责: 作为Java与C++原生库之间的FFI桥接层</p>
 * <p>GPU亲和度: 高 (独立距离计算，适合Vulkan Compute)</p>
 * <p>使用方法:</p>
 * <pre>
 *   long ctx = lod.createContext(4, distances, 32f, 2f);
 *   byte[] result = lod.batchCompute(ctx, inputs, count);
 *   lod.destroyContext(ctx);
 * </pre>
 */
public final class LodCalculatorFFIAdapter {

    /** NativeLibraryLoader实例，用于获取C++函数句柄 */
    private final NativeLibraryLoader loader;

    /**
     * 构造函数 - 通过NativeLibraryLoader初始化FFI适配器
     * @param loader Native库加载器实例
     */
    LodCalculatorFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建LOD计算器上下文
     *
     * @param maxLevels LOD等级数 (范围: 1-8)
     * @param distances 距离阈值数组 (可为null，使用默认值)
     * @param baseDistance 基础距离
     * @param falloffFactor 衰减因子
     * @return long 上下文句柄，用于后续操作
     * @throws IllegalStateException 创建失败时抛出
     */
    public long createContext(int maxLevels, float[] distances,
                               float baseDistance, float falloffFactor) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCtx = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment distSeg = (distances != null)
                ? arena.allocateFrom(ValueLayout.JAVA_FLOAT, distances)
                : MemorySegment.NULL;

            MethodHandle mh = loader.get("accel_lod_createContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(maxLevels, distSeg, baseDistance, falloffFactor, outCtx);
            if (rc != 0) throw new IllegalStateException("LOD createContext失败，错误码: " + rc);

            return outCtx.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("LOD createContext失败", e);
        }
    }

    /**
     * 批量计算多个区块的LOD等级
     *
     * @param context 上下文句柄 (由createContext返回)
     * @param inputData 输入数据数组，格式: [posX,posY,posZ,camX,camY,camZ,fov, ...] 每区块7个float
     * @param count 区块数量
     * @return byte[] 每区块12字节: [lodLevel(1), pad(3), distance(4), screenCoverage(4)]
     * @throws IllegalStateException 计算失败时抛出
     */
    public byte[] batchCompute(long context, float[] inputData, int count) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(ValueLayout.JAVA_FLOAT, inputData);
            MemorySegment output = arena.allocate(count * 12L);

            MethodHandle mh = loader.get("accel_lod_batchCompute",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            int rc = (int) mh.invokeExact(context, input, output, count);
            if (rc != 0) throw new IllegalStateException("LOD batchCompute失败，错误码: " + rc);

            byte[] result = new byte[count * 12];
            MemorySegment.copy(output, ValueLayout.JAVA_BYTE, 0, result, 0, count * 12);
            return result;
        } catch (Throwable e) {
            throw new IllegalStateException("LOD batchCompute失败", e);
        }
    }

    /**
     * 动态更新距离阈值
     *
     * @param context 上下文句柄
     * @param newThresholds 新的距离阈值数组
     */
    public void updateThresholds(long context, float[] newThresholds) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment thresh = arena.allocateFrom(ValueLayout.JAVA_FLOAT, newThresholds);

            MethodHandle mh = loader.get("accel_lod_updateThresholds",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            mh.invokeExact(context, thresh, newThresholds.length);
        } catch (Throwable e) {
            // 静默处理更新错误
        }
    }

    /**
     * 销毁LOD计算器并释放资源
     *
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_lod_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理销毁错误
        }
    }
}
