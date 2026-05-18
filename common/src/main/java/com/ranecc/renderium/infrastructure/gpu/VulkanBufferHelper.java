package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

/**
 * Vulkan Buffer 创建与销毁辅助
 *
 * <p>通过已有的 FFM 绑定创建/销毁 VkBuffer + VkDeviceMemory，
 * 封装 vkCreateBuffer → vkGetBufferMemoryRequirements → vkAllocateMemory → vkBindBufferMemory 完整链路。
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
        return VulkanDeviceHolder.isAvailable() && VulkanFFMBinding.isFfmLoaded();
    }

    public static long getDevice() { return VulkanDeviceHolder.getInstance().getDevice(); }

    /**
     * 创建 GPU 本地 Buffer + 分配设备内存并绑定
     */
    public static long[] createBuffer(long size, int usageBits) {
        long device = getDevice();
        if (device == 0L || size == 0L) return new long[]{0L, 0L};
        try (Arena arena = Arena.ofConfined()) {
            var createInfo = arena.allocate(56);
            createInfo.set(ValueLayout.JAVA_INT,  0, 1);   // sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
            createInfo.set(ValueLayout.ADDRESS,   8, MemorySegment.NULL); // pNext
            createInfo.set(ValueLayout.JAVA_INT, 16, 0);    // flags
            createInfo.set(ValueLayout.JAVA_LONG, 24, size); // size
            createInfo.set(ValueLayout.JAVA_INT, 32, usageBits); // usage
            createInfo.set(ValueLayout.JAVA_INT, 36, 0);    // sharingMode = VK_SHARING_MODE_EXCLUSIVE
            createInfo.set(ValueLayout.JAVA_INT, 40, 0);    // queueFamilyIndexCount
            createInfo.set(ValueLayout.ADDRESS,  48, MemorySegment.NULL); // pQueueFamilyIndices

            long[] outBuf = new long[1];
            int result = (int) VulkanFFMBinding.getVkCreateBuffer()
                .invoke(device, createInfo.address(), 0L, outBuf);
            if (result != VK_SUCCESS) return new long[]{0L, 0L};

            long vkBuffer = outBuf[0];
            var memReqs = arena.allocate(32);
            VulkanFFMBinding.getVkGetBufferMemoryRequirements()
                .invoke(device, vkBuffer, memReqs.address());
            long memSize = memReqs.get(ValueLayout.JAVA_LONG, 0);

            int memPropBits = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            var allocInfo = arena.allocate(24);
            allocInfo.set(ValueLayout.JAVA_LONG, 0, memSize);
            allocInfo.set(ValueLayout.JAVA_INT, 8, 0);

            long[] outMem = new long[1];
            result = (int) VulkanFFMBinding.getVkAllocateMemory()
                .invoke(device, allocInfo.address(), 0L, outMem);
            if (result != VK_SUCCESS) {
                VulkanFFMBinding.getVkDestroyBuffer().invoke(device, vkBuffer, 0L);
                return new long[]{0L, 0L};
            }

            VulkanFFMBinding.getVkBindBufferMemory()
                .invoke(device, vkBuffer, outMem[0], 0L);
            return new long[]{vkBuffer, outMem[0]};
        } catch (Throwable t) {
            LOGGER.warning("createBuffer failed: " + t.getMessage());
            return new long[]{0L, 0L};
        }
    }

    /**
     * 销毁 Buffer + Free Memory
     */
    public static void destroyBuffer(long vkBuffer, long vkMemory) {
        long device = getDevice();
        if (device == 0L) return;
        try {
            if (vkBuffer != 0L) VulkanFFMBinding.getVkDestroyBuffer().invoke(device, vkBuffer, 0L);
            if (vkMemory != 0L) VulkanFFMBinding.getVkFreeMemory().invoke(device, vkMemory, 0L);
        } catch (Throwable t) {
            LOGGER.warning("destroyBuffer failed: " + t.getMessage());
        }
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
