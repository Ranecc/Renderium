package com.ranecc.renderium.infrastructure.gpu;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

/**
 * GPU → CPU 回读工具
 *
 * <p>用于从 GPU Buffer 读取数据回 CPU（如可见性计数、剔除结果）。
 * 使用 vkMapMemory + vkInvalidateMappedMemoryRanges + fence 同步。
 */
public final class VulkanReadbackHelper {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanReadback");

    static final int VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 2;
    static final int VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 4;
    public static final int VK_BUFFER_USAGE_TRANSFER_DST_BIT = 2;
    public static final int VK_BUFFER_USAGE_STORAGE_BUFFER_BIT = 8;

    public static final int VK_SHARING_MODE_EXCLUSIVE = 0;
    private static final int VK_SUCCESS = 0;

    private VulkanReadbackHelper() {}

    public static boolean isAvailable() {
        return VulkanDeviceHolder.isAvailable()
            && VulkanFFMBinding.isFfmLoaded()
            && VulkanFFMBinding.getVkMapMemory() != null;
    }

    /**
     * 从设备内存读取 int 值（如 visibleChunkCount）
     *
     * @param device VkDevice
     * @param memory VkDeviceMemory
     * @param offset byte offset
     * @return int value, -1 on failure
     */
    public static int readInt(long device, long memory, long offset) {
        if (!isAvailable() || device == 0L || memory == 0L) return -1;
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var ppData = arena.allocate(8);
            int result = (int) VulkanFFMBinding.getVkMapMemory()
                .invoke(device, memory, offset, 4L, 0, ppData);
            if (result != VK_SUCCESS) return -1;

            var range = arena.allocate(32);
            range.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, memory);
            range.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, offset);
            range.set(java.lang.foreign.ValueLayout.JAVA_LONG, 16, 4L);
            VulkanFFMBinding.getVkInvalidateMappedMemoryRanges()
                .invoke(device, 1, range.address());

            long ptr = ppData.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
            if (ptr == 0L) return -1;
            int value = java.lang.foreign.MemorySegment.ofAddress(ptr)
                .reinterpret(4)
                .get(java.lang.foreign.ValueLayout.JAVA_INT, 0);

            VulkanFFMBinding.getVkUnmapMemory().invoke(device, memory);
            return value;
        } catch (Throwable t) {
            LOGGER.warning("readInt failed: " + t.getMessage());
            return -1;
        }
    }

    /**
     * 从设备内存读取 long 值
     */
    public static long readLong(long device, long memory, long offset) {
        if (!isAvailable() || device == 0L || memory == 0L) return -1L;
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var ppData = arena.allocate(8);
            int result = (int) VulkanFFMBinding.getVkMapMemory()
                .invoke(device, memory, offset, 8L, 0, ppData);
            if (result != VK_SUCCESS) return -1L;

            var range = arena.allocate(32);
            range.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, memory);
            range.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, offset);
            range.set(java.lang.foreign.ValueLayout.JAVA_LONG, 16, 8L);
            VulkanFFMBinding.getVkInvalidateMappedMemoryRanges()
                .invoke(device, 1, range.address());

            long ptr = ppData.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
            if (ptr == 0L) return -1L;
            long value = java.lang.foreign.MemorySegment.ofAddress(ptr)
                .reinterpret(8)
                .get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);

            VulkanFFMBinding.getVkUnmapMemory().invoke(device, memory);
            return value;
        } catch (Throwable t) {
            LOGGER.warning("readLong failed: " + t.getMessage());
            return -1L;
        }
    }
}
