package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * Vulkan 内存分配器 — 统一管理 Buffer/Image/Memory 的创建与销毁。
 *
 * <p>合并 {@link VulkanBufferHelper} 的 Buffer 创建、{@code HiZBufferManager} 的 Image 创建
 * 和散落在各处的 Memory 分配逻辑。当 VMA 可用时自动路由到 {@link VmaMemoryPools} 专用池。
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 创建 Buffer + 分配内存 + 绑定
 * long[] result = VulkanMemoryAllocator.createBuffer(device, size, usage, memoryProperties);
 * // result[0] = VkBuffer, result[1] = VkDeviceMemory (FFM 路径) 或 VmaAllocation (VMA 路径)
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

    // ==================== VMA 桥接模式 ====================

    /** VMA 分配器句柄（0L = VMA 未启用） */
    private static volatile long vmaAllocatorHandle = 0L;

    /** VMA 专用池管理器引用 */
    private static volatile VmaMemoryPools vmaPools = null;

    /** 由 VMA 分配的 Buffer 句柄集合，供 destroy 时路由 */
    private static final Set<Long> vmaManagedBuffers = ConcurrentHashMap.newKeySet();

    /** 由 VMA 分配的 Image 句柄集合 */
    private static final Set<Long> vmaManagedImages = ConcurrentHashMap.newKeySet();

    /**
     * 启用 VMA 路由模式。启用后 createBuffer/createImage 会优先通过
     * {@link VmaMemoryPools} 的专用池分配，仅在不匹配池类型时回退到 FFM 路径。
     */
    public static void setVmaMode(long vmaAllocatorHandle, VmaMemoryPools pools) {
        VulkanMemoryAllocator.vmaAllocatorHandle = vmaAllocatorHandle;
        VulkanMemoryAllocator.vmaPools = pools;
        LOGGER.info("VulkanMemoryAllocator VMA 模式已启用");
    }

    /** 检查 VMA 路由是否可用 */
    public static boolean isVmaMode() {
        return vmaAllocatorHandle != 0L && vmaPools != null && vmaPools.isInitialized();
    }

    // ==================== Buffer 创建 ====================

    /**
     * 创建 Buffer + 分配内存 + 绑定。
     *
     * @param device          VkDevice
     * @param size            Buffer 大小（字节）
     * @param usageBits       VkBufferUsageFlags 位掩码
     * @param memoryPropsBits VkMemoryPropertyFlags 位掩码
     * @return [VkBuffer, VkDeviceMemory/VmaAllocation]，失败返回 [0L, 0L]
     */
    public static long[] createBuffer(long device, long size, int usageBits, int memoryPropsBits) {
        if (device == 0L || size == 0L) return new long[]{0L, 0L};

        if (isVmaMode()) {
            VmaMemoryPools.PoolType poolType = usageToPoolType(usageBits);
            if (poolType != null) {
                VmaMemoryPools.PoolAllocation alloc = vmaPools.allocateFromPool(poolType, size);
                if (alloc != null) {
                    vmaManagedBuffers.add(alloc.buffer);
                    LOGGER.finest("VMA allocate buffer: type=" + poolType + " size=" + size);
                    return new long[]{alloc.buffer, alloc.allocation};
                }
            }
        }

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
            int typeFilter = memReqs.get(ValueLayout.JAVA_INT, 16);
            int memType = findMemoryType(device, typeFilter, memoryPropsBits);

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

    // ==================== Image 创建 ====================

    /**
     * 创建 Image + 分配内存 + 绑定。
     */
    public static long[] createImage(long device, long imageCreateInfoAddr) {
        if (device == 0L || imageCreateInfoAddr == 0L) return new long[]{0L, 0L};

        if (isVmaMode()) {
            VmaMemoryPools.PoolAllocation alloc = vmaPools.allocateFromPool(
                VmaMemoryPools.PoolType.TEXTURE, 0L);
            if (alloc != null) {
                vmaManagedImages.add(alloc.buffer);
                return new long[]{alloc.buffer, alloc.allocation};
            }
        }

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
                int typeFilter = memReqs.get(ValueLayout.JAVA_INT, 16);

                int memType = findMemoryType(device, typeFilter, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                return new long[]{vkImage, allocateAndBindImageMemory(device, vkImage, memSize, memType)};
            }
        } catch (Throwable t) {
            LOGGER.warning("createImage failed: " + t.getMessage());
            return new long[]{0L, 0L};
        }
    }

    // ==================== Buffer 销毁 ====================

    /**
     * 销毁 Buffer + 释放内存。自动判断走 VMA 路径或 FFM 路径。
     */
    public static void destroyBuffer(long device, long vkBuffer, long vkMemoryOrAllocation) {
        if (device == 0L) return;
        if (vmaManagedBuffers.remove(vkBuffer)) {
            VmaMemoryPools.deallocateBuffer(vmaAllocatorHandle, vkBuffer, vkMemoryOrAllocation);
            return;
        }
        try {
            if (vkBuffer != 0L) VulkanAPIRegistry.invoke("vkDestroyBuffer", device, vkBuffer, 0L);
            if (vkMemoryOrAllocation != 0L) VulkanAPIRegistry.invoke("vkFreeMemory", device, vkMemoryOrAllocation, 0L);
        } catch (Throwable t) {
            LOGGER.fine("destroyBuffer 异常: " + t.getMessage());
        }
    }

    // ==================== Image 销毁 ====================

    /**
     * 销毁 Image + 释放内存。自动判断走 VMA 路径或 FFM 路径。
     */
    public static void destroyImage(long device, long vkImage, long vkMemoryOrAllocation) {
        if (device == 0L) return;
        if (vmaManagedImages.remove(vkImage)) {
            VmaMemoryPools.deallocateImage(vmaAllocatorHandle, vkImage, vkMemoryOrAllocation);
            return;
        }
        try {
            if (vkImage != 0L) VulkanAPIRegistry.invoke("vkDestroyImage", device, vkImage, 0L);
            if (vkMemoryOrAllocation != 0L) VulkanAPIRegistry.invoke("vkFreeMemory", device, vkMemoryOrAllocation, 0L);
        } catch (Throwable t) {
            LOGGER.fine("destroyImage 异常: " + t.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private static VmaMemoryPools.PoolType usageToPoolType(int usageBits) {
        if ((usageBits & 0x0080) != 0) return VmaMemoryPools.PoolType.VERTEX_BUFFER;
        if ((usageBits & 0x0040) != 0) return VmaMemoryPools.PoolType.INDEX_BUFFER;
        if ((usageBits & 0x0010) != 0) return VmaMemoryPools.PoolType.UNIFORM_BUFFER;
        if ((usageBits & 0x0001) != 0) return VmaMemoryPools.PoolType.STAGING_BUFFER;
        if ((usageBits & 0x0020) != 0) return VmaMemoryPools.PoolType.RENDER_TARGET;
        return null;
    }

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

    private static int findMemoryType(long device, int typeFilter, int requiredProperties) {
        long physicalDevice = VulkanDeviceHolder.getInstance().getPhysicalDevice();
        if (physicalDevice == 0L || device == 0L) return 0;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment memProps = arena.allocate(1024);
            VulkanAPIRegistry.invoke("vkGetPhysicalDeviceMemoryProperties", physicalDevice, memProps.address());

            int memoryTypeCount = memProps.get(ValueLayout.JAVA_INT, 0);
            int count = Math.min(memoryTypeCount, 32);

            for (int i = 0; i < count; i++) {
                long typeOffset = 4 + (long) i * 8;
                int flags = memProps.get(ValueLayout.JAVA_INT, typeOffset);
                if ((typeFilter & (1 << i)) != 0 && (flags & requiredProperties) == requiredProperties) {
                    return i;
                }
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
