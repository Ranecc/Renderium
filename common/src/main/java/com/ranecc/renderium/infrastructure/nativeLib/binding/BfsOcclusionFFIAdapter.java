// Renderium Accelerator - BFS遮挡剔除接口 (FFI适配器)
// 迁移自: com.renderium.accel.BfsOcclusion
// 目标包: com.ranecc.renderium.infrastructure.nativeLib.binding
package com.ranecc.renderium.infrastructure.nativeLib.binding;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import com.ranecc.renderium.infrastructure.gpu.NativeLibraryLoader;

/**
 * BFS遮挡剔除 FFI适配器 - 通过图遍历确定可见区块
 *
 * <p>职责: 作为Java与C++原生库之间的FFI桥接层</p>
 * <p>使用方法:</p>
 * <pre>
 *   long ctx = bfs.createContext(8192);
 *   bfs.initGraph(ctx, sectionData, count);
 *   int[] result = bfs.findVisible(ctx, x, y, z, fov, dist, frame);
 *   bfs.destroyContext(ctx);
 * </pre>
 */
public final class BfsOcclusionFFIAdapter implements com.ranecc.renderium.feature.pipeline.strategy.BfsOcclusion {

    /** NativeLibraryLoader实例，用于获取C++函数句柄 */
    private final NativeLibraryLoader loader;

    /**
     * 构造函数 - 通过NativeLibraryLoader初始化FFI适配器
     * @param loader Native库加载器实例
     */
    public BfsOcclusionFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建BFS遮挡剔除上下文
     *
     * @param maxSections 最大区块数 (建议: 4096-16384)
     * @return long 上下文句柄，用于后续操作
     * @throws IllegalStateException 创建失败时抛出
     */
    public long createContext(int maxSections) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCtx = arena.allocate(ValueLayout.JAVA_LONG);

            MethodHandle mh = loader.get("accel_bfs_createContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(maxSections, outCtx);
            if (rc != 0) throw new IllegalStateException("BFS createContext失败，错误码: " + rc);

            return outCtx.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("BFS createContext失败", e);
        }
    }

    /**
     * 初始化区块图数据
     *
     * @param context 上下文句柄 (由createContext返回)
     * @param sectionData 坐标数组 [x0,y0,z0, x1,y1,z1, ...]
     * @param sectionCount 区块数量
     * @throws IllegalStateException 初始化失败时抛出
     */
    public void initGraph(long context, int[] sectionData, int sectionCount) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_INT, sectionData);

            MethodHandle mh = loader.get("accel_bfs_initGraph",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            int rc = (int) mh.invokeExact(context, data, sectionCount);
            if (rc != 0) throw new IllegalStateException("BFS initGraph失败，错误码: " + rc);
        } catch (Throwable e) {
            throw new IllegalStateException("BFS initGraph失败", e);
        }
    }

    /**
     * 设置区块的邻居连接
     *
     * @param context 上下文句柄
     * @param sectionIndex 区块索引
     * @param neighborData 邻居索引数组
     * @param neighborCount 邻居数量
     * @throws IllegalStateException 设置失败时抛出
     */
    public void setNeighbors(long context, int sectionIndex,
                              int[] neighborData, int neighborCount) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_INT, neighborData);

            MethodHandle mh = loader.get("accel_bfs_setNeighbors",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            int rc = (int) mh.invokeExact(context, sectionIndex, data, neighborCount);
            if (rc != 0) throw new IllegalStateException("BFS setNeighbors失败，错误码: " + rc);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("BFS setNeighbors失败", e);
        }
    }

    /**
     * 执行BFS可见性查询
     *
     * @param context 上下文句柄
     * @param eyeX 相机X坐标
     * @param eyeY 相机Y坐标
     * @param eyeZ 相机Z坐标
     * @param fov 视野角度(度)
     * @param renderDistance 渲染距离
     * @param frameNumber 帧号
     * @return int[] 包含3个元素: [visibleCount, totalProcessed, timeNs_low]
     * @throws IllegalStateException 查询失败时抛出
     */
    public int[] findVisible(long context,
                              float eyeX, float eyeY, float eyeZ,
                              float fov, float renderDistance,
                              int frameNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cam = arena.allocateFrom(ValueLayout.JAVA_FLOAT,
                new float[]{eyeX, eyeY, eyeZ, 0f, 0f, 0f, fov, renderDistance});

            // 动态计算结果缓冲区大小，避免硬编码导致的缓冲区溢出
            // C++端公式: requiredSize = sizeof(VisibilityResult) + bitmapSize
            // 其中 bitmapSize = (nodeCount + 31) / 32 * sizeof(uint32)
            int estimatedNodeCount = Math.max(8192, (int)(renderDistance * renderDistance * 4));
            int bitmapSizeWords = Math.toIntExact((estimatedNodeCount + 31L) / 32);
            int resultSize = 16 + Math.toIntExact((long) bitmapSizeWords * 4);

            MemorySegment result = arena.allocate(resultSize);

            MethodHandle mh = loader.get("accel_bfs_findVisible",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            int rc = (int) mh.invokeExact(context, cam, frameNumber, result, (long) resultSize);
            if (rc != 0) throw new IllegalStateException("BFS findVisible失败，错误码: " + rc);

            return new int[]{
                result.get(ValueLayout.JAVA_INT, 0),
                result.get(ValueLayout.JAVA_INT, 4),
                (int) (result.get(ValueLayout.JAVA_LONG, 8) & 0xFFFFFFFFL)
            };
        } catch (Throwable e) {
            throw new IllegalStateException("BFS findVisible失败", e);
        }
    }

    /**
     * 销毁BFS上下文并释放资源
     *
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_bfs_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理销毁错误，避免资源泄漏时的异常传播
        }
    }
}
