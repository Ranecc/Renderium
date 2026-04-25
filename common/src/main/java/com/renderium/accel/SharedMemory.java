// Renderium Accelerator - 共享内存零拷贝接口
package com.renderium.accel;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * 共享内存管理器 - Java/C++零拷贝数据交换
 *
 * <p>用于渲染管线与C++加速库之间的高带宽数据传递</p>
 * <p>使用方法:</p>
 * <pre>
 *   long handle = shm.create("renderium_frame", 4 * 1024 * 1024, true);
 *   MemorySegment addr = shm.getAddress(handle);
 *   // 读写共享内存...
 *   shm.destroy(handle);
 * </pre>
 */
public final class SharedMemory {

    private final NativeLibraryLoader loader;

    SharedMemory(NativeLibraryLoader loader) {
        this.loader = loader;
    }

    /**
     * 创建共享内存区域
     * @param name 区域唯一名称
     * @param sizeBytes 大小(字节)
     * @param createExclusive true=独占创建, false=打开已有
     * @return 共享内存句柄
     */
    public long create(String name, long sizeBytes, boolean createExclusive) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSeg = arena.allocateFrom(name);
            MemorySegment outHandle = arena.allocate(ValueLayout.JAVA_LONG);

            MethodHandle mh = loader.get("accel_sharedMemory_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(nameSeg, sizeBytes,
                createExclusive ? 1 : 0, outHandle);
            if (rc != 0) throw new IllegalStateException("SharedMemory create失败，错误码: " + rc);

            return outHandle.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("SharedMemory create失败", e);
        }
    }

    /**
     * 获取共享内存地址和大小
     * @param handle 共享内存句柄
     * @return [address, size]
     */
    public long[] getAddress(long handle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outAddr = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment outSize = arena.allocate(ValueLayout.JAVA_LONG);

            MethodHandle mh = loader.get("accel_sharedMemory_getAddress",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            int rc = (int) mh.invokeExact(handle, outAddr, outSize);
            if (rc != 0) throw new IllegalStateException("SharedMemory getAddress失败，错误码: " + rc);

            return new long[]{
                outAddr.get(ValueLayout.JAVA_LONG, 0),
                outSize.get(ValueLayout.JAVA_LONG, 0)
            };
        } catch (Throwable e) {
            throw new IllegalStateException("SharedMemory getAddress失败", e);
        }
    }

    /**
     * 销毁共享内存区域
     * @param handle 共享内存句柄
     */
    public void destroy(long handle) {
        try {
            MethodHandle mh = loader.get("accel_sharedMemory_destroy",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            mh.invokeExact(handle);
        } catch (Throwable e) {
            // 静默处理销毁错误
        }
    }
}
