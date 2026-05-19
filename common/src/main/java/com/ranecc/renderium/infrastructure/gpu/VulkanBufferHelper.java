package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

/**
 * Vulkan Buffer 创建与销毁辅助
 *
 * <p>委托给 {@link VulkanMemoryAllocator} 实现，保留与调用方兼容的 API 签名。
 */
public final class VulkanBufferHelper {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanBuf");

    static final int VK_BUFFER_USAGE_VERTEX_BUFFER_BIT = 1;
    static final int VK_BUFFER_USAGE_INDEX_BUFFER_BIT = 2;
    static final int VK_BUFFER_USAGE_TRANSFER_SRC_BIT = 0x00000010;
    static final int VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 1;
    static final int VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 2;
    static final int VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 4;
    static final int VK_SUCCESS = 0;
    public static final int VK_WHOLE_SIZE = 0x7FFFFFFF;

    private VulkanBufferHelper() {}

    public static boolean isAvailable() {
        return VulkanDeviceHolder.isAvailable();
    }

    public static long getDevice() { return VulkanDeviceHolder.getInstance().getDevice(); }

    /**
     * 创建 GPU 本地 Buffer + 分配设备内存并绑定。
     * 委托给 {@link VulkanMemoryAllocator}。
     */
    public static long[] createBuffer(long size, int usageBits) {
        long device = getDevice();
        if (device == 0L || size == 0L) return new long[]{0L, 0L};
        return VulkanMemoryAllocator.createDeviceLocalBuffer(device, size, usageBits);
    }

    /**
     * 销毁 Buffer + Free Memory。委托给 {@link VulkanMemoryAllocator}。
     */
    public static void destroyBuffer(long vkBuffer, long vkMemory) {
        long device = getDevice();
        if (device == 0L) return;
        VulkanMemoryAllocator.destroyBuffer(device, vkBuffer, vkMemory);
    }

    /**
     * 上传数据到 Buffer（映射→拷贝→刷新→解映射）
     */
    public static boolean uploadData(long device, long vkMemory, byte[] data, long offset) {
        if (!isAvailable() || device == 0L || vkMemory == 0L || data == null) return false;
        try (Arena arena = Arena.ofConfined()) {
            var ppData = arena.allocate(8);
            int result = (int) VulkanFFMBinding.getVkMapMemory()
                .invoke(device, vkMemory, offset, (long) data.length, 0, ppData);
            if (result != VK_SUCCESS) return false;

            long ptr = ppData.get(ValueLayout.JAVA_LONG, 0);
            if (ptr == 0L) return false;
            var seg = java.lang.foreign.MemorySegment.ofAddress(ptr)
                .reinterpret(data.length);
            for (int i = 0; i < data.length; i++) seg.set(ValueLayout.JAVA_BYTE, i, data[i]);
            VulkanFFMBinding.getVkFlushMappedMemoryRanges()
                .invoke(device, 1, arena.allocate(32).address());
            VulkanFFMBinding.getVkUnmapMemory().invoke(device, vkMemory);
            return true;
        } catch (Throwable t) {
            LOGGER.warning("uploadData failed: " + t.getMessage());
            return false;
        }
    }
}
