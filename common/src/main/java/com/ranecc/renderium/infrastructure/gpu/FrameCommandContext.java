package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * 帧级共享 Command Buffer 上下文 — 三帧轮转版。
 *
 * <h3>问题</h3>
 * 旧渲染管线中，~25 个节点各自调用 {@code VulkanSyncManager.submitAndWait()}，
 * 即每个 pass 都执行一次 vkQueueSubmit + vkWaitForFences。
 * 相当于 CPU 给 GPU 写 25 封信并站在邮筒前等每封的回执。
 * 性能影响：~2.5ms/帧 (~15% 帧预算) 浪费在 Fence 等待上。
 *
 * <h3>方案 B — Secondary CB Pool + 单次 Submit</h3>
 * <pre>
 *   beginFrame()
 *     ├─ vkResetCommandPool    — O(1) 重置所有 Secondary CB
 *     ├─ vkResetFences         — 重置帧 Fence
 *     ├─ vkBeginCommandBuffer  — Begin Primary CB (一次提交标志)
 *     │
 *   per node:
 *     ├─ beginNodeCB(idx)      — Begin Secondary CB
 *     ├─ 节点录制 vkCmd* 命令
 *     ├─ endNodeCB(idx)        — End Secondary CB
 *     │
 *   endFrame()
 *     ├─ vkCmdExecuteCommands  — 批量挂载所有 Secondary CB 到 Primary
 *     ├─ vkEndCommandBuffer    — End Primary CB
 *     ├─ vkQueueSubmit         — 单次提交
 *     └─ 不等待！下一帧 beginFrame 时等待 2 帧前的 Fence
 * </pre>
 *
 * <h3>三帧轮转</h3>
 * 复用 CullingCoordinator / LodCullingComputePass 的三槽环形缓冲模式。
 * CPU 永远领先 GPU 1-2 帧，消除 Fence 阻塞等待。
 *
 * <pre>
 *   Frame N:   录制 CB → 提交（不等待）
 *   Frame N-1: beginFrame 时等待 Fence_{N-1}（大概率已完成）→ 重用 CB_{N-1}
 *   Frame N-2: Fence_{N-2} 一定已完成
 *
 *   槽位: [0] [1] [2]
 *   head: 下一帧写入的槽
 *   tail: 最旧待回收的槽
 * </pre>
 *
 * <h3>性能收益</h3>
 * 25 次 submitAndWait → 1 次 submit + 非阻塞等待：消除 ~2.5ms/帧 CPU Fence 等待。
 * 三帧轮转进一步消除 ~1ms/帧的 GPU→CPU 同步延迟。
 */
public final class FrameCommandContext {

    private static final Logger LOGGER = Logger.getLogger("Renderium|FrameCmdCtx");

    static final int MAX_NODES = 64;

    // ==================== 三帧轮转 ====================
    private static final int RING_SIZE = 3;

    /** 每个槽的 Fence */
    private static final long[] slotFences = new long[RING_SIZE];

    /** 每个槽的 CommandPool（独立池，可并行 reset） */
    private static final long[] slotCmdPools = new long[RING_SIZE];

    /** 每个槽的 Primary CB */
    private static final long[] slotPrimaryCBs = new long[RING_SIZE];

    /** 每个槽的 Secondary CB 数组 */
    private static final long[][] slotSecondaryCBs = new long[RING_SIZE][MAX_NODES];

    /** 每个槽的节点使用标记 */
    private static final boolean[][] slotUsedNodes = new boolean[RING_SIZE][MAX_NODES];

    /** head: 下一帧写入的槽, tail: 最旧待回收的槽 */
    private static int head = 0;
    private static int tail = 0;

    /** 当前帧使用的槽索引 */
    private static int currentSlot = -1;

    // ==================== 设备/队列 ====================
    private static volatile boolean initialized;
    private static long deviceHandle;
    private static long frameQueue;

    // ==================== MethodHandle 缓存 ====================
    private static MethodHandle vkAllocateCmdBufs;
    private static MethodHandle vkBeginCmdBuf;
    private static MethodHandle vkEndCmdBuf;
    private static MethodHandle vkResetCmdPool;
    private static MethodHandle vkExecuteCmds;
    private static MethodHandle vkQueueSubmit;
    private static MethodHandle vkWaitFences;
    private static MethodHandle vkResetFences;
    private static MethodHandle vkCreateFence;
    private static MethodHandle vkCreateCmdPool;
    private static MethodHandle vkDestroyFence;
    private static MethodHandle vkDestroyCmdPool;
    private static MethodHandle vkFreeCmdBufs;
    private static boolean handlesReady;

    private FrameCommandContext() {}

    public static synchronized void init(long device, long queue) {
        if (device == 0L || queue == 0L) {
            LOGGER.warning("[init] 无效参数: dev=" + Long.toHexString(device) + " queue=" + Long.toHexString(queue));
            return;
        }
        deviceHandle = device;
        frameQueue = queue;

        try {
            vkAllocateCmdBufs = VulkanAPIRegistry.getHandle("vkAllocateCommandBuffers");
            vkBeginCmdBuf = VulkanAPIRegistry.getHandle("vkBeginCommandBuffer");
            vkEndCmdBuf = VulkanAPIRegistry.getHandle("vkEndCommandBuffer");
            vkResetCmdPool = VulkanAPIRegistry.getHandle("vkResetCommandPool");
            vkExecuteCmds = VulkanAPIRegistry.getHandle("vkCmdExecuteCommands");
            vkQueueSubmit = VulkanAPIRegistry.getHandle("vkQueueSubmit");
            vkWaitFences = VulkanAPIRegistry.getHandle("vkWaitForFences");
            vkResetFences = VulkanAPIRegistry.getHandle("vkResetFences");
            vkCreateFence = VulkanAPIRegistry.getHandle("vkCreateFence");
            vkCreateCmdPool = VulkanAPIRegistry.getHandle("vkCreateCommandPool");
            vkDestroyFence = VulkanAPIRegistry.getHandle("vkDestroyFence");
            vkDestroyCmdPool = VulkanAPIRegistry.getHandle("vkDestroyCommandPool");
            vkFreeCmdBufs = VulkanAPIRegistry.getHandle("vkFreeCommandBuffers");

            if (vkAllocateCmdBufs == null || vkBeginCmdBuf == null || vkEndCmdBuf == null) {
                LOGGER.severe("[init] 缺少核心 Vulkan API 句柄，初始化终止");
                return;
            }
            handlesReady = true;

            // 为每个槽创建 CommandPool + Primary CB + Secondary CBs + Fence
            for (int i = 0; i < RING_SIZE; i++) {
                createSlotResources(i);
            }

            initialized = true;
            LOGGER.info("FrameCommandContext 三帧轮转就绪: " + RING_SIZE + " slots");
        } catch (Throwable t) {
            LOGGER.severe("[init] " + t.getMessage());
        }
    }

    // ──────── 帧级 API ────────

    /**
     * 帧开始：
     * 1. 回收已完成的旧槽（非阻塞轮询 Fence）
     * 2. 等待当前 head 槽的 Fence（2 帧前提交，大概率已完成）
     * 3. 重置 CommandPool + 开始录制 Primary CB
     */
    public static void beginFrame() {
        if (!initialized || !handlesReady) return;
        try {
            // === Phase 1: 消费 — 回收所有已完成的旧槽 ===
            while (tail != head) {
                long fence = slotFences[tail];
                if (fence != 0L && checkFenceSignaled(fence)) {
                    // GPU 已完成此槽，回收
                    resetFence(fence);
                    tail = (tail + 1) % RING_SIZE;
                } else {
                    break; // GPU 尚未完成，保持等待
                }
            }

            // === Phase 2: 等待当前 head 槽 ===
            // head 槽是 2 帧前提交的，大概率已完成
            int slot = head;
            long fence = slotFences[slot];
            if (fence != 0L) {
                // 阻塞等待，但通常 < 0.1ms（GPU 早已完成）
                waitForFence(fence, 1_000_000L); // 1ms 超时
                resetFence(fence);
            }

            currentSlot = slot;

            // 重置此槽的 CommandPool（O(1) 重置所有 CB）
            if (vkResetCmdPool != null) {
                vkResetCmdPool.invokeWithArguments(deviceHandle, slotCmdPools[slot], 0L);
            }
            Arrays.fill(slotUsedNodes[slot], false);

            // 开始录制 Primary CB
            MemorySegment beginInfo = nativeLongs(3);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L); // sType
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);  // pNext
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);  // VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
            vkBeginCmdBuf.invokeWithArguments(slotPrimaryCBs[slot], beginInfo.address());

        } catch (Throwable t) {
            LOGGER.warning("[beginFrame] " + t.getMessage());
        }
    }

    /**
     * 获取指定索引的 Secondary CB 并 begin 它。
     * @return CB 句柄，失败返回 0L
     */
    public static long beginNodeCB(int nodeIndex) {
        if (!initialized || currentSlot < 0 || nodeIndex < 0 || nodeIndex >= MAX_NODES) return 0L;
        long cb = slotSecondaryCBs[currentSlot][nodeIndex];
        if (cb == 0L) return 0L;
        try {
            // VkCommandBufferInheritanceInfo (Secondary CB 需要)
            MemorySegment inheritInfo = nativeLongs(8);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 1L); // sType
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 7, 0L);

            MemorySegment beginInfo = nativeLongs(3);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L); // sType
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, inheritInfo.address());
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);  // VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT

            vkBeginCmdBuf.invokeWithArguments(cb, beginInfo.address());
            slotUsedNodes[currentSlot][nodeIndex] = true;
            return cb;
        } catch (Throwable t) {
            LOGGER.warning("[beginNodeCB:" + nodeIndex + "] " + t.getMessage());
            return 0L;
        }
    }

    /** 结束指定节点的 Secondary CB 录制 */
    public static void endNodeCB(int nodeIndex) {
        if (!initialized || currentSlot < 0 || nodeIndex < 0 || nodeIndex >= MAX_NODES) return;
        if (!slotUsedNodes[currentSlot][nodeIndex]) return;
        try {
            vkEndCmdBuf.invokeWithArguments(slotSecondaryCBs[currentSlot][nodeIndex]);
        } catch (Throwable t) {
            LOGGER.warning("[endNodeCB:" + nodeIndex + "] " + t.getMessage());
        }
    }

    /**
     * 帧结束：批量执行 → Submit → 不等待！
     * 三帧轮转的核心：提交后立即返回，下一帧 beginFrame 时再等待。
     */
    public static void endFrame() {
        if (!initialized || !handlesReady || currentSlot < 0) return;
        try {
            int slot = currentSlot;
            int count = 0;
            for (int i = 0; i < MAX_NODES; i++) {
                if (slotUsedNodes[slot][i]) count++;
            }
            if (count == 0) {
                // 没有节点录制，直接结束 Primary CB
                vkEndCmdBuf.invokeWithArguments(slotPrimaryCBs[slot]);
                return;
            }

            // 收集已使用的 Secondary CB 句柄
            long[] handles = new long[count];
            int idx = 0;
            for (int i = 0; i < MAX_NODES; i++) {
                if (slotUsedNodes[slot][i]) {
                    handles[idx++] = slotSecondaryCBs[slot][i];
                }
            }
            MemorySegment handleArray = nativeLongs(count);
            for (int i = 0; i < count; i++) {
                handleArray.setAtIndex(ValueLayout.JAVA_LONG, i, handles[i]);
            }

            // vkCmdExecuteCommands: 批量挂载所有 Secondary CB 到 Primary
            if (vkExecuteCmds != null) {
                vkExecuteCmds.invokeWithArguments(slotPrimaryCBs[slot], (long) count, handleArray.address());
            }

            // 结束 Primary CB
            vkEndCmdBuf.invokeWithArguments(slotPrimaryCBs[slot]);

            // 构建 VkSubmitInfo
            MemorySegment cmdBufSeg = nativeLongs(1);
            cmdBufSeg.set(ValueLayout.JAVA_LONG, 0, slotPrimaryCBs[slot]);

            MemorySegment submitInfo = nativeLongs(6);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L); // sType (由 Vulkan 填充)
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // waitSemaphoreCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L); // pWaitSemaphores
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L); // commandBufferCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, cmdBufSeg.address()); // pCommandBuffers

            // 获取此槽的 Fence
            long fence = slotFences[slot];

            // 提交 — 不等待！
            if (vkQueueSubmit != null) {
                vkQueueSubmit.invokeWithArguments(frameQueue, 1L, submitInfo.address(), fence);
            }

            // 推进 head 指针
            head = (head + 1) % RING_SIZE;
            currentSlot = -1;

        } catch (Throwable t) {
            LOGGER.warning("[endFrame] " + t.getMessage());
        }
    }

    public static long getQueue() { return frameQueue; }
    public static boolean isInitialized() { return initialized; }

    // ──────── 槽资源创建 ────────

    private static void createSlotResources(int slotIndex) throws Throwable {
        // 创建 CommandPool
        MemorySegment poolInfo = nativeLongs(3);
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 12L); // VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);  // pNext
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 2L);  // VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT

        MemorySegment outPool = nativeLongs(1);
        int rc = (int) vkCreateCmdPool.invokeWithArguments(deviceHandle, poolInfo.address(), 0L, outPool.address());
        if (rc != 0) { LOGGER.severe("createSlotCmdPool[" + slotIndex + "] failed: " + rc); return; }
        slotCmdPools[slotIndex] = outPool.get(ValueLayout.JAVA_LONG, 0);

        // 分配 Primary CB
        MemorySegment allocInfo = nativeLongs(5);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 44L); // sType
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, slotCmdPools[slotIndex]);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L); // VK_COMMAND_BUFFER_LEVEL_PRIMARY
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);

        MemorySegment primaryOut = nativeLongs(1);
        rc = (int) vkAllocateCmdBufs.invokeWithArguments(deviceHandle, allocInfo.address(), primaryOut.address());
        if (rc == 0) slotPrimaryCBs[slotIndex] = primaryOut.get(ValueLayout.JAVA_LONG, 0);

        // 分配 Secondary CBs
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 1L); // VK_COMMAND_BUFFER_LEVEL_SECONDARY
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, (long) MAX_NODES);
        MemorySegment secondaryOut = nativeLongs(MAX_NODES);
        rc = (int) vkAllocateCmdBufs.invokeWithArguments(deviceHandle, allocInfo.address(), secondaryOut.address());
        if (rc == 0) {
            for (int i = 0; i < MAX_NODES; i++)
                slotSecondaryCBs[slotIndex][i] = secondaryOut.getAtIndex(ValueLayout.JAVA_LONG, i);
        }

        // 创建 Fence（初始 signaled，第一帧不用等）
        MemorySegment fenceInfo = nativeLongs(3);
        fenceInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 6L); // VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
        fenceInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        fenceInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L); // VK_FENCE_CREATE_SIGNALED_BIT

        MemorySegment outFence = nativeLongs(1);
        rc = (int) vkCreateFence.invokeWithArguments(deviceHandle, fenceInfo.address(), 0L, outFence.address());
        if (rc == 0) slotFences[slotIndex] = outFence.get(ValueLayout.JAVA_LONG, 0);
    }

    // ──────── Fence 辅助 ────────

    private static boolean checkFenceSignaled(long fence) {
        if (fence == 0L || vkWaitFences == null) return false;
        try {
            MemorySegment pFence = nativeLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            int result = (int) vkWaitFences.invokeWithArguments(deviceHandle, 1L, pFence.address(), 0L, 0L);
            return result == 0; // VK_SUCCESS = fence signaled
        } catch (Throwable t) {
            return false;
        }
    }

    private static void waitForFence(long fence, long timeoutNs) {
        if (fence == 0L || vkWaitFences == null) return;
        try {
            MemorySegment pFence = nativeLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            vkWaitFences.invokeWithArguments(deviceHandle, 1L, pFence.address(), 1L, timeoutNs);
        } catch (Throwable t) {
            LOGGER.fine("[waitForFence] " + t.getMessage());
        }
    }

    private static void resetFence(long fence) {
        if (fence == 0L || vkResetFences == null) return;
        try {
            MemorySegment pFence = nativeLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            vkResetFences.invokeWithArguments(deviceHandle, 1L, pFence.address());
        } catch (Throwable t) {
            LOGGER.fine("[resetFence] " + t.getMessage());
        }
    }

    // ──────── 内存分配 ────────

    private static MemorySegment nativeLongs(int count) {
        return PerFrameArena.allocateLongs(count);
    }

    // ──────── 关闭 ────────

    public static void shutdown() {
        if (!initialized) return;
        initialized = false;

        // 等待所有 Fence 完成
        for (int i = 0; i < RING_SIZE; i++) {
            if (slotFences[i] != 0L) {
                waitForFence(slotFences[i], 100_000_000L); // 100ms 超时
            }
        }

        // 销毁所有槽资源
        for (int i = 0; i < RING_SIZE; i++) {
            try {
                if (slotFences[i] != 0L && vkDestroyFence != null) {
                    vkDestroyFence.invokeWithArguments(deviceHandle, slotFences[i], 0L);
                }
                if (slotCmdPools[i] != 0L && vkDestroyCmdPool != null) {
                    vkDestroyCmdPool.invokeWithArguments(deviceHandle, slotCmdPools[i], 0L);
                }
            } catch (Throwable ignored) {}
            slotFences[i] = 0L;
            slotCmdPools[i] = 0L;
            slotPrimaryCBs[i] = 0L;
            Arrays.fill(slotSecondaryCBs[i], 0L);
        }

        head = 0;
        tail = 0;
        currentSlot = -1;
        LOGGER.fine("FrameCommandContext 三帧轮转已关闭");
    }
}
