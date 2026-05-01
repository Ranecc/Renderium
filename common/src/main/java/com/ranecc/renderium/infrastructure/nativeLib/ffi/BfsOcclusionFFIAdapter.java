// Renderium Accelerator - BFS视锥剔除 FFI适配器
// 基于BFS算法的视锥体剔除，支持动态分块和LOD感知
//
// 使用方法:
//   long ctx = bfs.createContext(64, 16, 256.0f);
//   int[] visible = bfs.compute(ctx, cameraPos, viewProj, chunkData);
//   bfs.destroyContext(ctx);
package com.ranecc.renderium.infrastructure.nativeLib.ffi;

import com.ranecc.renderium.infrastructure.nativeLib.NativeLibraryLoader;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * BFS视锥剔除 FFI适配器
 *
 * <p>通过原生C++实现高性能BFS视锥剔除算法，支持动态分块、LOD感知和距离排序。</p>
 * <p>GPU亲和度: 高 (结果直接用于DrawCall剔除)</p>
 *
 * <h3>BFS算法优势:</h3>
 * <ul>
 *   <li>O(n) 空间复杂度 (vs 递归O(log n)栈空间)</li>
 *   <li>天然适合动态分块 (每层可独立调度)</li>
 *   <li>距离排序内置 (近优先渲染)</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.1.0
 */
public final class BfsOcclusionFFIAdapter {

    private final NativeLibraryLoader loader;

    /**
     * 构造函数
     * @param loader 原生库加载器实例
     */
    public BfsOcclusionFFIAdapter(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建BFS上下文
     *
     * @param maxChunks 最大分块数 (建议: 64-256)
     * @param maxDepth 最大递归深度 (建议: 8-16)
     * @param cullDistance 剔除距离 (世界单位)
     * @return 上下文句柄 (long类型)
     */
    public long createContext(int maxChunks, int maxDepth, float cullDistance) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCtx = arena.allocate(ValueLayout.JAVA_LONG);

            MethodHandle mh = loader.get("accel_bfs_createContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(maxChunks, maxDepth, cullDistance, outCtx);
            if (rc != 0) throw new IllegalStateException("BFS createContext失败，错误码: " + rc);

            return outCtx.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("BFS createContext失败", e);
        }
    }

    /**
     * 执行BFS视锥剔除计算
     *
     * @param context 上下文句柄
     * @param cameraPos 相机位置 [x,y,z]
     * @param viewProj 视图投影矩阵 (4x4, column-major)
     * @param chunkData 分块数据数组
     * @return 可见分块索引数组 (按距离排序)
     */
    public int[] compute(long context, float[] cameraPos, float[] viewProj, float[] chunkData) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment camSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, cameraPos);
            MemorySegment vpSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, viewProj);
            MemorySegment dataSeg = arena.allocateFrom(ValueLayout.JAVA_FLOAT, chunkData);
            MemorySegment outCount = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outIndices = arena.allocate(ValueLayout.JAVA_INT, 1024);

            MethodHandle mh = loader.get("accel_bfs_compute",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(context, camSeg, vpSeg, dataSeg, outCount, outIndices);
            if (rc != 0) return new int[0];

            int count = outCount.get(ValueLayout.JAVA_INT, 0);
            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                result[i] = outIndices.get(ValueLayout.JAVA_INT, i * 4);
            }
            return result;
        } catch (Throwable e) {
            return new int[0];
        }
    }

    /**
     * 销毁BFS上下文并释放资源
     * @param context 要销毁的上下文句柄
     */
    public void destroyContext(long context) {
        try {
            MethodHandle mh = loader.get("accel_bfs_destroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(context);
        } catch (Throwable e) {
            // 静默处理销毁错误
        }
    }
}
