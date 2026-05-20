package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;

/**
 * 全局 Vulkan DescriptorPool / Bindless DescriptorSet 管理器
 *
 * <p>启动时创建全局 Pool。所有 Compute Pass 共用一个 Bindless DescriptorSet，
 * 支持 VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND + PARTIALLY_BOUND。
 *
 * <h2>Phase 3: Bindless 架构</h2>
 * <pre>
 * 传统（每 Pass 一次绑定）:
 *   vkCmdBindDescriptorSets(cmdBuf, pipelineLayout, 0, 1, &perPassDS, ...)
 *
 * Bindless（全局静态绑定）:
 *   vkCmdBindDescriptorSets(cmdBuf, globalPipelineLayout, 0, 1, &bindlessDS, ...)
 *   // 着色器中按 descriptorIndex 索引 textures/buffers
 * </pre>
 */
public final class VulkanDescriptorManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanDescMgr");

    static final int VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE = 0;
    static final int VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 3;
    static final int VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;
    static final int VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;
    public static final int VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT = 1;
    public static final int VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT = 2;

    private static final int VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT = 0x00000004;
    private static final int VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT = 0x00000008;
    private static final int VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO = 27;
    private static final int VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO = 28;
    private static final int VK_SUCCESS = 0;

    private static final AtomicLong descriptorPool = new AtomicLong(0L);
    private static final AtomicLong bindlessSetLayout = new AtomicLong(0L);
    private static final AtomicLong bindlessDescriptorSet = new AtomicLong(0L);
    private static final AtomicBoolean created = new AtomicBoolean(false);
    private static final AtomicBoolean bindlessInit = new AtomicBoolean(false);

    public static final int BINDLESS_TARGET_SLOTS = 200_000;

    private VulkanDescriptorManager() {}

    public static long getPool() { return descriptorPool.get(); }
    public static long getBindlessSet() { return bindlessDescriptorSet.get(); }
    public static long getBindlessLayout() { return bindlessSetLayout.get(); }

    public static boolean isAvailable() {
        return VulkanDeviceHolder.isAvailable() && VulkanAPIRegistry.isAvailable("vkCreateDescriptorPool");
    }

    public static boolean isBindlessReady() {
        return bindlessDescriptorSet.get() != 0L && bindlessSetLayout.get() != 0L;
    }

    // ==================== 全局 Pool 创建 ====================

    private static void createGlobalPool() {
        if (!created.compareAndSet(false, true)) return;
        if (!isAvailable()) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        try (var arena = Arena.ofConfined()) {
            var sizes = com.ranecc.renderium.infrastructure.gpu.VulkanStructs.createPoolSizes(arena, new int[][]{
                {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 100000},
                {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 16384},
                {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 16384},
                {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 100000},
            });

            var createInfo = com.ranecc.renderium.infrastructure.gpu.VulkanStructs.createDescriptorPoolCreateInfoCompact(
                arena, VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT, BINDLESS_TARGET_SLOTS, 4, sizes);

            var outPool = arena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) VulkanAPIRegistry.invoke("vkCreateDescriptorPool",
                device, createInfo.address(), 0L, outPool.address());
            long poolHandle = outPool.get(ValueLayout.JAVA_LONG, 0);
            if (result == VK_SUCCESS) {
                descriptorPool.set(poolHandle);
                LOGGER.info("Global DescriptorPool created: 0x" + Long.toHexString(poolHandle));
            } else {
                LOGGER.warning("vkCreateDescriptorPool failed VkResult=" + result);
                created.set(false);
            }
        } catch (Throwable t) {
            LOGGER.warning("DescriptorPool creation failed: " + t.getMessage());
            created.set(false);
        }
    }

    // ==================== Phase 3: Bindless 分配 ====================

    /**
     * 创建 Bindless DescriptorSetLayout + 从全局 Pool 分配 1 个 DescriptorSet。
     *
     * <p>Layout 包含 3 个 binding:
     * <ol>
     *   <li>binding=0: StorageBuffer (SSBO 数据)</li>
     *   <li>binding=1: SampledImage (纹理 10K 槽)</li>
     *   <li>binding=2: UniformBuffer (UBO 数据)</li>
     * </ol>
     * 全部带 UPDATE_AFTER_BIND + PARTIALLY_BOUND。
     */
    public static boolean allocateBindlessSet() {
        if (bindlessInit.get()) return true;
        if (!bindlessInit.compareAndSet(false, true)) return bindlessDescriptorSet.get() != 0L;
        if (!isAvailable()) return false;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        long pool = descriptorPool.get();
        if (pool == 0L) {
            createGlobalPool();
            pool = descriptorPool.get();
        }
        if (pool == 0L) return false;

        try (var arena = Arena.ofConfined()) {
            var bindings = arena.allocate(72);

            bindings.set(ValueLayout.JAVA_INT, 0, 0);
            bindings.set(ValueLayout.JAVA_INT, 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            bindings.set(ValueLayout.JAVA_INT, 8, 16384);
            bindings.set(ValueLayout.JAVA_INT, 12, VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT
                | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);

            bindings.set(ValueLayout.JAVA_INT, 24, 1);
            bindings.set(ValueLayout.JAVA_INT, 28, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
            bindings.set(ValueLayout.JAVA_INT, 32, 100000);
            bindings.set(ValueLayout.JAVA_INT, 36, VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT
                | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);

            bindings.set(ValueLayout.JAVA_INT, 48, 2);
            bindings.set(ValueLayout.JAVA_INT, 52, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            bindings.set(ValueLayout.JAVA_INT, 56, 16384);
            bindings.set(ValueLayout.JAVA_INT, 60, VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT
                | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);

            var layoutInfo = arena.allocate(24);
            layoutInfo.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.set(ValueLayout.JAVA_INT, 8, 3);
            layoutInfo.set(ValueLayout.ADDRESS, 16, bindings);

            var outLayout = arena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) VulkanAPIRegistry.invoke("vkCreateDescriptorSetLayout",
                device, layoutInfo.address(), 0L, outLayout.address());
            long layoutHandle = outLayout.get(ValueLayout.JAVA_LONG, 0);
            if (result != VK_SUCCESS) return false;
            bindlessSetLayout.set(layoutHandle);

            var allocInfo = com.ranecc.renderium.infrastructure.gpu.VulkanStructs.createDescriptorSetAllocateInfoCompact(
                arena, pool, 1, layoutHandle);
            allocInfo.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);

            var outSet = arena.allocate(ValueLayout.JAVA_LONG);
            result = (int) VulkanAPIRegistry.invoke("vkAllocateDescriptorSets",
                device, allocInfo.address(), outSet.address());
            long setHandle = outSet.get(ValueLayout.JAVA_LONG, 0);
            if (result == VK_SUCCESS) {
                bindlessDescriptorSet.set(setHandle);
                LOGGER.info("Bindless DescriptorSet allocated: layout=0x"
                    + Long.toHexString(layoutHandle)
                    + " set=0x" + Long.toHexString(setHandle));
                return true;
            }
            return false;
        } catch (Throwable t) {
            LOGGER.warning("allocateBindlessSet failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 更新 Bindless DescriptorSet 中的 StorageBuffer binding (binding=0)
     *
     * @param buffers   长整型数组 [handle, offset, range, handle, offset, range, ...]
     * @param count     要写入的 buffer 数量
     */
    public static boolean updateBindlessStorageBuffers(long[] buffers, int count) {
        long set = bindlessDescriptorSet.get();
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (set == 0L || device == 0L || buffers == null || count == 0) return false;

        try (var arena = Arena.ofConfined()) {
            int structSize = 40;
            var writes = arena.allocate((long) count * structSize);
            for (int i = 0; i < count; i++) {
                long offset = (long) i * structSize;
                writes.set(ValueLayout.JAVA_LONG, offset, set);
                writes.set(ValueLayout.JAVA_INT, offset + 8, 0);
                writes.set(ValueLayout.JAVA_INT, offset + 12, 0);
                writes.set(ValueLayout.JAVA_INT, offset + 16, 1);
                writes.set(ValueLayout.JAVA_INT, offset + 20, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                writes.set(ValueLayout.JAVA_LONG, offset + 24, buffers[i * 3]);
                writes.set(ValueLayout.JAVA_LONG, offset + 32, buffers[i * 3 + 1]);
            }
            VulkanAPIRegistry.invoke("vkUpdateDescriptorSets", device, count, writes.address(), 0, 0L);
            return true;
        } catch (Throwable t) {
            LOGGER.warning("updateBindlessStorageBuffers failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 绑定 Bindless DescriptorSet 到命令缓冲区
     */
    public static boolean bindBindlessSet(long cmdBuf, long pipelineLayout) {
        long set = bindlessDescriptorSet.get();
        if (set == 0L || cmdBuf == 0L || pipelineLayout == 0L) return false;
        try {
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                cmdBuf, 0, pipelineLayout, 0, 1, set, 0, 0L);
            return true;
        } catch (Throwable t) {
            LOGGER.warning("bindBindlessSet failed: " + t.getMessage());
            return false;
        }
    }

    // ==================== 清理 ====================

    public static void destroy() {
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;

        long layout = bindlessSetLayout.getAndSet(0L);
        if (layout != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyDescriptorSetLayout", device, layout, 0L);
            } catch (Throwable ignored) {}
        }

        long pool = descriptorPool.getAndSet(0L);
        if (pool != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyDescriptorPool", device, pool, 0L);
            } catch (Throwable ignored) {}
        }
        created.set(false);
        bindlessInit.set(false);
    }
}
