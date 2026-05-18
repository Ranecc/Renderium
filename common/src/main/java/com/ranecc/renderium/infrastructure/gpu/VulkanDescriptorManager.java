package com.ranecc.renderium.infrastructure.gpu;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

/**
 * 全局 Vulkan DescriptorPool / DescriptorSet 管理器
 *
 * <p>启动时创建一次，全系统共用。
 * 受 {@link VulkanOperationGuard} 保护。
 */
public final class VulkanDescriptorManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanDescMgr");

    private static final int VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE = 0;
    private static final int VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 3;
    private static final int VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;
    static final int VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;
    public static final int VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT = 1;
    public static final int VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT = 2;

    private static final AtomicLong descriptorPool = new AtomicLong(0L);
    private static final AtomicBoolean created = new AtomicBoolean(false);

    private VulkanDescriptorManager() {}

    public static long getDescriptorPool() {
        if (!created.get()) createGlobalPool();
        return descriptorPool.get();
    }

    public static long getPool() { return getDescriptorPool(); }

    public static boolean isAvailable() {
        return VulkanDeviceHolder.isAvailable() && VulkanFFMBinding.isFfmLoaded();
    }

    private static void createGlobalPool() {
        if (!created.compareAndSet(false, true)) return;
        if (!isAvailable()) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var sizeStruct = arena.allocate(32);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, 100000);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 12, 16384);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 16, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 20, 16384);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 24, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            sizeStruct.set(java.lang.foreign.ValueLayout.JAVA_INT, 28, 100000);

            var createInfo = arena.allocate(16);
            createInfo.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
            createInfo.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT);
            createInfo.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 200000);
            createInfo.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, sizeStruct.address());
            createInfo.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 200000);
            createInfo.set(java.lang.foreign.ValueLayout.ADDRESS, 8, sizeStruct);

            long[] outPool = new long[1];
            int result = (int) VulkanFFMBinding.getVkCreateDescriptorPool()
                .invoke(device, createInfo.address(), 0L, outPool);
            if (result == 0) {
                descriptorPool.set(outPool[0]);
                LOGGER.info("Global DescriptorPool created: 0x" + Long.toHexString(outPool[0]));
            } else {
                LOGGER.warning("vkCreateDescriptorPool failed with VkResult=" + result);
                created.set(false);
            }
        } catch (Throwable t) {
            LOGGER.warning("DescriptorPool creation failed: " + t.getMessage());
            created.set(false);
        }
    }

    public static void destroy() {
        if (descriptorPool.get() == 0L) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        try {
            VulkanFFMBinding.getVkDestroyDescriptorPool()
                .invoke(device, descriptorPool.get(), 0L);
            descriptorPool.set(0L);
            created.set(false);
        } catch (Throwable ignored) {}
    }
}
