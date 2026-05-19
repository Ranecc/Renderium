package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * Vulkan 内存分配器 — 统一管理 Buffer/Image/Memory 的创建与销毁。
 *
 * <p>合并 {@link VulkanBufferHelper} 的 Buffer 创建、{@code HiZBufferManager} 的 Image 创建
 * 和散落在各处的 Memory 分配逻辑。
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 创建 Buffer + 分配内存 + 绑定
 * long[] result = VulkanMemoryAllocator.createBuffer(device, size, usage, memoryProperties);
 * // result[0] = VkBuffer, result[1] = VkDeviceMemory
 *
 * // 销毁
 * VulkanMemoryAllocator.destroyBuffer(device, result[0], result[1]);
 * </pre>
 */
public final class VulkanMemoryAllocator {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanMem");

    private static final int VK_SUCCESS = 0;
    private static final int VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 1;
    private static final int VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 2;
    private static final int VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 4;

    private VulkanMemoryAllocator() {}

    /**
     * 创建 Buffer + 分配内存 + 绑定。
     *
     * @param device          VkDevice
     * @param size            Buffer 大小（字节）
     * @param usageBits       VkBufferUsageFlags 位掩码
     * @param memoryPropsBits VkMemoryPropertyFlags 位掩码
     * @return [VkBuffer, VkDeviceMemory]，失败返回 [0L, 0L]
     */
    public static long[] createBuffer(long device, long size, int usageBits, int memoryPropsBits) {
        if (device == 0L || size == 0L) return new long[]{0L, 0L};
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment createInfo = VulkanStructs.createBufferCreateInfo(arena, size, usageBits);
            long[] outBuf = new long[1];
            int result = (int) VulkanAPIRegistry.invoke(
                "vkCreateBuffer", device, createInfo.address(), 0L, outBuf);
            if (result != VK_SUCCESS) return new long[]{0L, 0L};

            long vkBuffer = outBuf[0];
            MemorySegment memReqs = arena.allocate(32);
            VulkanAPIRegistry.invoke("vkGetBufferMemoryRequirements", device, vkBuffer, memReqs.address());
            long memSize = memReqs.get(ValueLayout.JAVA_LONG, 0);
            int memType = findMemoryType(device, memSize, memoryPropsBits);

            return new long[]{vkBuffer, allocateAndBindMemory(device, vkBuffer, memSize, memType)};
        } catch (Throwable t) {
            LOGGER.warning("createBuffer failed: " + t.getMessage());
            return new long[]{0L, 0L};
        }
    }

    /**
     * 创建 Buffer（使用 DEVICE_LOCAL 内存属性）。
     */
    public static long[] createDeviceLocalBuffer(long device, long size, int usageBits) {
        return createBuffer(device, size, usageBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    }

    /**
     * 创建 Buffer（使用 HOST_VISIBLE | HOST_COHERENT 内存属性）。
     */
    public static long[] createHostVisibleBuffer(long device, long size, int usageBits) {
        return createBuffer(device, size, usageBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    /**
     * 创建 Image + 分配内存 + 绑定。
     */
    public static long[] createImage(long device, long imageCreateInfoAddr) {
        if (device == 0L || imageCreateInfoAddr == 0L) return new long[]{0L, 0L};
        try {
            long[] outImg = new long[1];
            int result = (int) VulkanAPIRegistry.invoke(
                "vkCreateImage", device, imageCreateInfoAddr, 0L, outImg);
            if (result != VK_SUCCESS) return new long[]{0L, 0L};

            long vkImage = outImg[0];
            long[] outMem = new long[1];
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment memReqs = arena.allocate(32);
                VulkanAPIRegistry.invoke("vkGetImageMemoryRequirements", device, vkImage, memReqs.address());
                long memSize = memReqs.get(ValueLayout.JAVA_LONG, 0);

                int memType = findMemoryType(device, memSize, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                return new long[]{vkImage, allocateAndBindImageMemory(device, vkImage, memSize, memType)};
            }
        } catch (Throwable t) {
            LOGGER.warning("createImage failed: " + t.getMessage());
            return new long[]{0L, 0L};
        }
    }

    /**
     * 销毁 Buffer + 释放内存。
     */
    public static void destroyBuffer(long device, long vkBuffer, long vkMemory) {
        if (device == 0L) return;
        try {
            if (vkBuffer != 0L) VulkanAPIRegistry.invoke("vkDestroyBuffer", device, vkBuffer, 0L);
            if (vkMemory != 0L) VulkanAPIRegistry.invoke("vkFreeMemory", device, vkMemory, 0L);
        } catch (Throwable t) {
            LOGGER.fine("destroyBuffer 异常: " + t.getMessage());
        }
    }

    /**
     * 销毁 Image + 释放内存。
     */
    public static void destroyImage(long device, long vkImage, long vkMemory) {
        if (device == 0L) return;
        try {
            if (vkImage != 0L) VulkanAPIRegistry.invoke("vkDestroyImage", device, vkImage, 0L);
            if (vkMemory != 0L) VulkanAPIRegistry.invoke("vkFreeMemory", device, vkMemory, 0L);
        } catch (Throwable t) {
            LOGGER.fine("destroyImage 异常: " + t.getMessage());
        }
    }

    // ==================== 内部实现 ====================

    private static long allocateAndBindMemory(long device, long vkBuffer, long size, int memType) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment allocInfo = VulkanStructs.createMemoryAllocateInfo(arena, size, memType);
            long[] outMem = new long[1];
            int result = (int) VulkanAPIRegistry.invoke(
                "vkAllocateMemory", device, allocInfo.address(), 0L, outMem);
            if (result != VK_SUCCESS) return 0L;

            VulkanAPIRegistry.invoke("vkBindBufferMemory", device, vkBuffer, outMem[0], 0L);
            return outMem[0];
        } catch (Throwable t) {
            LOGGER.fine("allocateAndBindMemory 失败: " + t.getMessage());
            return 0L;
        }
    }

    private static long allocateAndBindImageMemory(long device, long vkImage, long size, int memType) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment allocInfo = VulkanStructs.createMemoryAllocateInfo(arena, size, memType);
            long[] outMem = new long[1];
            int result = (int) VulkanAPIRegistry.invoke(
                "vkAllocateMemory", device, allocInfo.address(), 0L, outMem);
            if (result != VK_SUCCESS) return 0L;

            VulkanAPIRegistry.invoke("vkBindImageMemory", device, vkImage, outMem[0], 0L);
            return outMem[0];
        } catch (Throwable t) {
            LOGGER.fine("allocateAndBindImageMemory 失败: " + t.getMessage());
            return 0L;
        }
    }

    private static int findMemoryType(long device, long allocationSize, int requiredProperties) {
        long physicalDevice = VulkanDeviceHolder.getInstance().getPhysicalDevice();
        if (physicalDevice == 0L || device == 0L) return 0;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment memProps = arena.allocate(1024);
            VulkanAPIRegistry.invoke("vkGetPhysicalDeviceMemoryProperties", physicalDevice, memProps.address());

            int memoryTypeCount = memProps.get(ValueLayout.JAVA_INT, 0);
            int count = Math.min(memoryTypeCount, 32);

            for (int i = 0; i < count; i++) {
                long typeOffset = 8 + (long) i * 8;
                int flags = memProps.get(ValueLayout.JAVA_INT, typeOffset);
                if ((flags & requiredProperties) == requiredProperties) {
                    return i;
                }
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
