// Renderium Accelerator - LOD距离计算 FFI适配器
// 批量计算区块LOD等级
//
// 使用方法:
//   long ctx = lod.createContext(4, distances, 32f, 2f);
//   byte[] result = lod.batchCompute(ctx, inputs, count);
//   lod.destroyContext(ctx);
package com.ranecc.renderium.infrastructure.nativeLib.ffi;

import com.ranecc.renderium.infrastructure.nativeLib.NativeLibraryLoader;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * LOD距离计算器 FFI适配器
 *
 * <p>批量计算区块的LOD (Level of Detail) 等级，根据距离相机远近决定渲染精度。</p>
 * <p>GPU亲和度: 高 (独立距离计算，适合Vulkan Compute)</p>
 *
 * @author Renderium Team
 * @since 1.1.0
 */
public final class LodCalculatorFFIAdapter {

    private final NativeLibraryLoader loader;

    public LodCalculatorFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建LOD计算器上下文
     * @param maxLevels LOD等级数 (1-8)
     * @param distances 距离阈值数组 (可为null，使用默认值)
     * @param baseDistance 基础距离
     * @param falloffFactor 衰减因子
     * @return 上下文句柄 (long类型)
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
     * 批量计算LOD等级
     * @param context 上下文句柄
     * @param inputData 输入数据数组 [posX,posY,posZ,camX,camY,camZ,fov, ...] 每区块7个float
     * @param count 区块数量
     * @return byte数组，每区块12字节: [lodLevel(1byte), pad(3bytes), distance(4bytes), screenCoverage(4bytes)]
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
     * 更新距离阈值
     * @param context 上下文句柄
     * @param newThresholds 新阈值数组
     */
    public void updateThresholds(long context, float[] newThresholds) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment thresh = arena.allocateFrom(ValueLayout.JAVA_FLOAT, newThresholds);

            MethodHandle mh = loader.get("accel_lod_updateThresholds",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            mh.invokeExact(context, thresh, newThresholds.length);
        } catch (Throwable e) {}
    }

    /**
     * 销毁LOD计算器上下文并释放资源
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_lod_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {}
    }
}
