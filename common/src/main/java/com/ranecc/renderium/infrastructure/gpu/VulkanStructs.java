package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Vulkan C 结构体集中定义与构建器。
 *
 * <p>统一管理所有 Vulkan 结构体的内存布局偏移和构建方法，
 * 消除分散在 10+ 个文件中的重复硬编码偏移。
 *
 * <p>所有方法以 {@code createXxx(Arena, ...)} 命名，
 * 通过传入的 Arena 分配内存并写入字段值，返回 {@code MemorySegment}。
 *
 * <p>两条布局原则：
 * <ul>
 *   <li><b>节省内存</b>：精确的 int(4B)/long(8B)/address(8B) 混合布局（用于高频分配的结构体）</li>
 *   <li><b>对齐布局</b>：使用 JAVA_LONG 数组（8B 对齐，用于低频创建、偏移安全优先的结构体）</li>
 * </ul>
 *
 * @see #createSimpleBufferCreateInfo(Arena, long, int) 快速创建 VkBufferCreateInfo
 * @see #createDescriptorPoolCreateInfoAligned(Arena, int, MemorySegment, int) 对齐版 DescriptorPool
 * @see #createSemaphoreCreateInfo(Arena) 二进制信号量
 * @see #createTimelineSemaphoreCreateInfo(Arena, long) 时间线信号量
 */
public final class VulkanStructs {

    private VulkanStructs() {}

    // ==================== sType 常量 ====================

    public static final int VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO = 1;
    public static final int VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO = 4;
    public static final int VK_STRUCTURE_TYPE_FENCE_CREATE_INFO = 9;
    public static final int VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO = 10;
    public static final int VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO = 12;
    public static final int VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO = 24;
    public static final int VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO = 27;
    public static final int VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO = 35;  // 紧凑版
    public static final int VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO_ALIGNED = 48;  // 对齐版
    public static final int VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO = 44;
    public static final int VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO = 1000203001;
    public static final int VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO = 1000203002;
    public static final int VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO = 1000203003;

    // ==================== 共享 Vulkan 常量 ====================

    public static final int VK_SHARING_MODE_EXCLUSIVE = 0;
    public static final int VK_COMMAND_BUFFER_LEVEL_PRIMARY = 0;
    public static final int VK_COMMAND_POOL_CREATE_TRANSIENT_BIT = 2;
    public static final int VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT = 4;
    public static final int VK_FENCE_CREATE_SIGNALED_BIT = 1;
    public static final int VK_PIPELINE_BIND_POINT_COMPUTE = 1;
    public static final int VK_SHADER_STAGE_COMPUTE_BIT = 0x00000020;
    public static final int VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;
    public static final int VK_DESCRIPTOR_TYPE_STORAGE_IMAGE = 2;
    public static final int VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 3;
    public static final int VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;

    // ============================
    //  1. VkBufferCreateInfo — 紧凑布局 (56B)
    // ============================
    // [int sType] + [ptr pNext] + [int flags] + [long size] + [int usage] + [int sharingMode] + [int qfCount] + [ptr pQueueFamilyIndices]
    private static final int BUF_STYPE = 0;
    private static final int BUF_PNEXT = 8;
    private static final int BUF_FLAGS = 16;
    private static final int BUF_SIZE = 24;
    private static final int BUF_USAGE = 32;
    private static final int BUF_SHARING = 36;
    private static final int BUF_QFCOUNT = 40;
    private static final int BUF_PQFINDICES = 48;
    private static final int BUF_TOTAL = 56;

    /**
     * 创建 VkBufferCreateInfo（紧凑布局，56B，精确字段放置）
     */
    public static MemorySegment createBufferCreateInfo(Arena arena, long size, int usage) {
        MemorySegment s = arena.allocate(BUF_TOTAL);
        s.set(ValueLayout.JAVA_INT, BUF_STYPE, VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
        s.set(ValueLayout.JAVA_LONG, BUF_PNEXT, 0L);
        s.set(ValueLayout.JAVA_INT, BUF_FLAGS, 0);
        s.set(ValueLayout.JAVA_LONG, BUF_SIZE, size);
        s.set(ValueLayout.JAVA_INT, BUF_USAGE, usage);
        s.set(ValueLayout.JAVA_INT, BUF_SHARING, VK_SHARING_MODE_EXCLUSIVE);
        s.set(ValueLayout.JAVA_INT, BUF_QFCOUNT, 0);
        s.set(ValueLayout.ADDRESS, BUF_PQFINDICES, MemorySegment.NULL);
        return s;
    }

    // ============================
    //  2. VkDescriptorPoolCreateInfo — 紧凑布局 (32B)
    // ============================
    // [int sType] + [int flags] + [int maxSets] + [int poolSizeCount] + [ptr pPoolSizes]
    private static final int DPC_STYPE = 0;
    private static final int DPC_FLAGS = 4;
    private static final int DPC_MAXSETS = 8;
    private static final int DPC_POOLSIZECOUNT = 12;
    private static final int DPC_PPOOLSIZES = 16;
    private static final int DPC_TOTAL = 32;

    public static MemorySegment createDescriptorPoolCreateInfoCompact(
            Arena arena, int flags, int maxSets, int poolSizeCount, MemorySegment pPoolSizes) {
        MemorySegment s = arena.allocate(DPC_TOTAL);
        s.set(ValueLayout.JAVA_INT, DPC_STYPE, VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
        s.set(ValueLayout.JAVA_INT, DPC_FLAGS, flags);
        s.set(ValueLayout.JAVA_INT, DPC_MAXSETS, maxSets);
        s.set(ValueLayout.JAVA_INT, DPC_POOLSIZECOUNT, poolSizeCount);
        s.set(ValueLayout.ADDRESS, DPC_PPOOLSIZES, pPoolSizes);
        return s;
    }

    /**
     * VkDescriptorPoolCreateInfo — 对齐布局 (6×JAVA_LONG, 48B)
     */
    public static MemorySegment createDescriptorPoolCreateInfoAligned(
            Arena arena, int maxSets, int poolSizeCount, MemorySegment pPoolSizes) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 6);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // flags
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) maxSets);
        s.setAtIndex(ValueLayout.JAVA_LONG, 4, (long) poolSizeCount);
        s.setAtIndex(ValueLayout.JAVA_LONG, 5, pPoolSizes.address());
        return s;
    }

    // ============================
    //  3. VkDescriptorPoolSize (4B+4B=8B per entry)
    // ============================
    public static MemorySegment createPoolSizes(Arena arena, int[][] typeCounts) {
        MemorySegment s = arena.allocate(
                java.lang.foreign.MemoryLayout.sequenceLayout(typeCounts.length,
                        java.lang.foreign.MemoryLayout.structLayout(
                                ValueLayout.JAVA_INT.withName("type"),
                                ValueLayout.JAVA_INT.withName("descriptorCount"))));
        for (int i = 0; i < typeCounts.length; i++) {
            s.setAtIndex(ValueLayout.JAVA_INT, i * 2L, typeCounts[i][0]);
            s.setAtIndex(ValueLayout.JAVA_INT, i * 2L + 1, typeCounts[i][1]);
        }
        return s;
    }

    // ============================
    //  4. VkSemaphoreCreateInfo
    // ============================
    // 紧凑布局 (16B): [int sType] + [ptr pNext]
    public static MemorySegment createSemaphoreCreateInfo(Arena arena) {
        MemorySegment s = arena.allocate(16);
        s.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        s.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
        return s;
    }

    // 对齐布局 (24B): [long sType] + [long pNext] + [int flags] + padding
    public static MemorySegment createBinarySemaphoreCreateInfoAligned(Arena arena) {
        MemorySegment s = arena.allocate(24);
        s.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        s.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
        s.set(ValueLayout.JAVA_INT, 20, 0); // flags
        return s;
    }

    // ============================
    //  5. VkSemaphoreTypeCreateInfo — 时间线信号量类型
    // ============================
    // 24B: [int sType(0)] + [long pNext(8)] + [int semaphoreType(16)] + [long initialValue(24)]
    private static final int STCI_SEMAPHORETYPE = 16;
    private static final int STCI_INITIALVALUE = 24;
    private static final int STCI_TOTAL = 32;

    public static MemorySegment createTimelineSemaphoreCreateInfo(Arena arena, long initialValue) {
        MemorySegment s = arena.allocate(STCI_TOTAL);
        s.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO);
        s.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
        s.set(ValueLayout.JAVA_INT, STCI_SEMAPHORETYPE, 1); // VK_SEMAPHORE_TYPE_TIMELINE
        s.set(ValueLayout.JAVA_LONG, STCI_INITIALVALUE, initialValue);
        return s;
    }

    /**
     * 创建含时间线类型的完整 VkSemaphoreCreateInfo（pNext 链入 VkSemaphoreTypeCreateInfo）
     */
    public static MemorySegment createSemaphoreWithTimeline(Arena arena, long initialValue) {
        MemorySegment typeInfo = createTimelineSemaphoreCreateInfo(arena, initialValue);
        MemorySegment s = arena.allocate(16);
        s.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        s.set(ValueLayout.ADDRESS, 8, typeInfo);
        return s;
    }

    // ============================
    //  6. VkCommandPoolCreateInfo — 对齐布局 (4×JAVA_LONG, 32B)
    // ============================
    public static MemorySegment createCommandPoolCreateInfo(
            Arena arena, int flags, int queueFamilyIndex) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 4);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, (long) flags);
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) queueFamilyIndex);
        return s;
    }

    // ============================
    //  7. VkFenceCreateInfo — 对齐布局 (3×JAVA_LONG, 24B)
    // ============================
    public static MemorySegment createFenceCreateInfo(Arena arena, int flags) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 3);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, (long) flags);
        return s;
    }

    // ============================
    //  8. VkCommandBufferAllocateInfo — 对齐布局 (5×JAVA_LONG, 40B)
    // ============================
    public static MemorySegment createCommandBufferAllocateInfo(
            Arena arena, long commandPool, int level, int count) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 5);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, commandPool);
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) level);
        s.setAtIndex(ValueLayout.JAVA_LONG, 4, (long) count);
        return s;
    }

    // ============================
    //  9. VkMemoryAllocateInfo — 紧凑布局 (24B)
    // ============================
    // [long sType+pNext 复用] → 实际: [int sType(0)] + [ptr pNext(8)] + [long allocSize(16)] + [int typeIdx(24)]
    // 标准: [int sType(0)] + [ptr pNext(8)] + [long allocationSize(16)] + [int memoryTypeIndex(24)]
    private static final int MAI_PNEXT = 8;
    private static final int MAI_ALLOCSIZE = 16;
    private static final int MAI_TYPEINDEX = 24;
    private static final int MAI_TOTAL = 32;

    public static MemorySegment createMemoryAllocateInfo(Arena arena, long allocationSize, int memoryTypeIndex) {
        MemorySegment s = arena.allocate(MAI_TOTAL);
        s.set(ValueLayout.JAVA_INT, 0, 0); // sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
        s.set(ValueLayout.ADDRESS, MAI_PNEXT, MemorySegment.NULL);
        s.set(ValueLayout.JAVA_LONG, MAI_ALLOCSIZE, allocationSize);
        s.set(ValueLayout.JAVA_INT, MAI_TYPEINDEX, memoryTypeIndex);
        return s;
    }

    // ============================
    //  10. VkPipelineShaderStageCreateInfo — 对齐布局 (7×JAVA_LONG, 56B)
    // ============================
    public static MemorySegment createShaderStageCreateInfo(
            Arena arena, int stage, long module, MemorySegment entryPointName) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 7);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // flags
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) stage);
        s.setAtIndex(ValueLayout.JAVA_LONG, 4, module);
        s.setAtIndex(ValueLayout.JAVA_LONG, 6, 0L); // pSpecializationInfo
        if (entryPointName != null) {
            s.setAtIndex(ValueLayout.JAVA_LONG, 5, entryPointName.address());
        }
        return s;
    }

    /** 便捷方法：自动构建 "main\0" 入口点名称 */
    public static MemorySegment createShaderStageCreateInfo(
            Arena arena, int stage, long module) {
        byte[] mainBytes = "main\0".getBytes(StandardCharsets.UTF_8);
        MemorySegment mainName = arena.allocate(mainBytes.length, 1);
        mainName.asByteBuffer().put(mainBytes);
        return createShaderStageCreateInfo(arena, stage, module, mainName);
    }

    // ============================
    //  11. VkComputePipelineCreateInfo — 对齐布局 (7×JAVA_LONG, 56B)
    // ============================
    public static MemorySegment createComputePipelineCreateInfo(
            Arena arena, MemorySegment stageInfo, long layout) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 7);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // flags
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, stageInfo.address());
        s.setAtIndex(ValueLayout.JAVA_LONG, 4, layout);
        s.setAtIndex(ValueLayout.JAVA_LONG, 5, 0L); // basePipelineHandle
        s.setAtIndex(ValueLayout.JAVA_LONG, 6, 0xFFFFFFFFL); // basePipelineIndex = -1
        return s;
    }

    // ============================
    //  12. VkPipelineLayoutCreateInfo — 对齐布局 (7×JAVA_LONG, 56B)
    // ============================
    public static MemorySegment createPipelineLayoutCreateInfo(
            Arena arena, int setLayoutCount, MemorySegment pSetLayouts,
            int pushConstantRangeCount, MemorySegment pPushConstantRanges) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 7);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // flags
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) setLayoutCount);
        s.setAtIndex(ValueLayout.JAVA_LONG, 4, pSetLayouts.address());
        s.setAtIndex(ValueLayout.JAVA_LONG, 5, (long) pushConstantRangeCount);
        s.setAtIndex(ValueLayout.JAVA_LONG, 6, pPushConstantRanges.address());
        return s;
    }

    // ============================
    //  13. VkDescriptorSetLayoutBinding — 5-long 对齐 (40B per binding)
    // ============================
    public static MemorySegment createDescriptorSetLayoutBindings(
            Arena arena, int[][] bindings) {
        // bindings[i] = {binding, descriptorType, descriptorCount, stageFlags, pImmutableSamplers}
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 5L * bindings.length);
        for (int i = 0; i < bindings.length; i++) {
            long base = i * 5L;
            s.setAtIndex(ValueLayout.JAVA_LONG, base, (long) bindings[i][0]);     // binding
            s.setAtIndex(ValueLayout.JAVA_LONG, base + 1, (long) bindings[i][1]); // descriptorType
            s.setAtIndex(ValueLayout.JAVA_LONG, base + 2, (long) bindings[i][2]); // descriptorCount
            s.setAtIndex(ValueLayout.JAVA_LONG, base + 3, (long) bindings[i][3]); // stageFlags
            s.setAtIndex(ValueLayout.JAVA_LONG, base + 4, (long) bindings[i][4]); // pImmutableSamplers
        }
        return s;
    }

    // ============================
    //  14. VkDescriptorSetLayoutCreateInfo — 对齐布局 (5×JAVA_LONG, 40B)
    // ============================
    public static MemorySegment createDescriptorSetLayoutCreateInfo(
            Arena arena, int bindingCount, MemorySegment pBindings, int flags) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 5);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L); // sType (caller fills)
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, (long) flags);
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) bindingCount);
        s.setAtIndex(ValueLayout.JAVA_LONG, 4, pBindings.address());
        return s;
    }

    // ============================
    //  15. VkDescriptorSetAllocateInfo — 紧凑布局 (32B)
    // ============================
    // [int sType] + [long pool] + [int setCount] + [long pSetLayouts]
    private static final int DSAI_POOL = 8;
    private static final int DSAI_SETCOUNT = 16;
    private static final int DSAI_PSETLAYOUTS = 24;
    private static final int DSAI_TOTAL = 32;

    public static MemorySegment createDescriptorSetAllocateInfoCompact(
            Arena arena, long descriptorPool, int setCount, long setLayoutHandle) {
        MemorySegment s = arena.allocate(DSAI_TOTAL);
        s.set(ValueLayout.JAVA_INT, 0, 0); // sType (caller fills)
        s.set(ValueLayout.JAVA_LONG, DSAI_POOL, descriptorPool);
        s.set(ValueLayout.JAVA_INT, DSAI_SETCOUNT, setCount);
        s.set(ValueLayout.JAVA_LONG, DSAI_PSETLAYOUTS, setLayoutHandle);
        return s;
    }

    // ============================
    //  16. VkSubmitInfo — 对齐布局 (6×JAVA_LONG, 48B)
    // ============================
    public static MemorySegment createSubmitInfo(Arena arena, long commandBuffer) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 6);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L); // sType=VK_STRUCTURE_TYPE_SUBMIT_INFO
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // waitSemaphoreCount
        s.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L); // pWaitSemaphores
        s.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L); // commandBufferCount
        // cmdBuffer address → caller must set
        s.setAtIndex(ValueLayout.JAVA_LONG, 5, commandBuffer);
        return s;
    }

    // ============================
    //  17. VkSignalSemaphoreInfo — 32B
    // ============================
    // [int sType(0)] + [ptr pNext(8)] + [long semaphore(16)] + [long value(24)]
    public static MemorySegment createSignalSemaphoreInfo(Arena arena, long semaphore, long value) {
        MemorySegment s = arena.allocate(32);
        s.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO);
        s.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
        s.set(ValueLayout.JAVA_LONG, 16, semaphore);
        s.set(ValueLayout.JAVA_LONG, 24, value);
        return s;
    }

    // ============================
    //  18. VkSemaphoreWaitInfo — 40B (指针数组结构)
    // ============================
    // [int sType(0)] + [ptr pNext(8)] + [int flags(16)] + [int semCount(20)]
    // + [ptr pSemaphores(24)] + [ptr pValues(32)]
    public static MemorySegment createSemaphoreWaitInfo(
            Arena arena, long semaphore, long value) {
        MemorySegment semArray = arena.allocate(ValueLayout.JAVA_LONG, 1);
        semArray.setAtIndex(ValueLayout.JAVA_LONG, 0, semaphore);
        MemorySegment valArray = arena.allocate(ValueLayout.JAVA_LONG, 1);
        valArray.setAtIndex(ValueLayout.JAVA_LONG, 0, value);

        MemorySegment s = arena.allocate(40);
        s.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO);
        s.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
        s.set(ValueLayout.JAVA_INT, 16, 0);  // flags
        s.set(ValueLayout.JAVA_INT, 20, 1);  // semaphoreCount
        s.set(ValueLayout.ADDRESS, 24, semArray);
        s.set(ValueLayout.ADDRESS, 32, valArray);
        return s;
    }

    // ============================
    //  19. PushConstantRange — 3×JAVA_LONG, 24B
    // ============================
    public static MemorySegment createPushConstantRange(Arena arena, int stageFlags, int offset, int size) {
        MemorySegment s = arena.allocate(ValueLayout.JAVA_LONG, 3);
        s.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) stageFlags);
        s.setAtIndex(ValueLayout.JAVA_LONG, 1, (long) offset);
        s.setAtIndex(ValueLayout.JAVA_LONG, 2, (long) size);
        return s;
    }
}
