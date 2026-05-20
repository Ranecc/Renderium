package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.logging.Logger;

/**
 * Vulkan Buffer 创建与销毁辅助
 *
 * <p>委托给 {@link VulkanMemoryAllocator} 实现，保留与调用方兼容的 API 签名。
 * 上传路径使用持久映射缓存，避免每帧 vkMapMemory/vkUnmapMemory。
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

    /** 持久映射缓存：vkMemory → MemorySegment，一次映射终身使用 */
    private static final HashMap<Long, MemorySegment> persistentMaps = new HashMap<>();

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
        // 清理持久映射缓存
        MemorySegment seg = persistentMaps.remove(vkMemory);
        if (seg != null && seg.isMapped()) {
            // MemorySegment 由 Arena 管理，不手动 unmap
        }
        VulkanMemoryAllocator.destroyBuffer(device, vkBuffer, vkMemory);
    }

    /**
     * 上传数据到 Buffer（持久映射，零分配）。
     * 首次调用时 vkMapMemory 一次，后续复用到 destroyBuffer 或 shutdown。
     */
    public static boolean uploadData(long device, long vkMemory, byte[] data, long offset) {
        if (!isAvailable() || device == 0L || vkMemory == 0L || data == null) return false;

        try {
            MemorySegment mapped = persistentMaps.get(vkMemory);
            if (mapped == null) {
                // 首次调用：持久映射
                try (Arena arena = Arena.ofConfined()) {
                    var ppData = arena.allocate(8);
                    int result = (int) VulkanAPIRegistry.invoke(
                        "vkMapMemory", device, vkMemory, 0L, VK_WHOLE_SIZE, 0, ppData.address());
                    if (result != VK_SUCCESS) return false;
                    long ptr = ppData.get(ValueLayout.JAVA_LONG, 0);
                    if (ptr == 0L) return false;
                    mapped = MemorySegment.ofAddress(ptr).reinterpret(VK_WHOLE_SIZE);
                    persistentMaps.put(vkMemory, mapped);
                }
            }

            // 批量拷贝到持久映射段（仅 2 次边界检查，内部走优化 memcpy）
            MemorySegment source = MemorySegment.ofArray(data);
            mapped.asSlice(offset, data.length).copyFrom(source);

            // Host Coherent 内存不需要 vkFlushMappedMemoryRanges
            // 如果分配时未使用 HOST_COHERENT，在这里加 flush
            return true;
        } catch (Throwable t) {
            LOGGER.warning("uploadData failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 重置持久映射缓存（shutdown 时调用）。
     */
    public static synchronized void resetPersistentMaps() {
        persistentMaps.clear();
    }
}
