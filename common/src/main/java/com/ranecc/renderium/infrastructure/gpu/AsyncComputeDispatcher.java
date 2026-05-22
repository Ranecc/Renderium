package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Async Compute 调度器 — 在独立 Compute Queue 上提交 GPU Compute 任务。
 *
 * <h3>核心价值</h3>
 * 将 Culling、后处理等 Compute Shader 工作从 Graphics Queue 分离到 Compute Queue，
 * 使 GPU 的 Compute 单元和 Graphics 单元并行工作，消除串行等待。
 *
 * <h3>时序对比</h3>
 * <pre>
 * 无 Async Compute（串行）:
 *   Graphics: [Culling] → [Render] → [PostProcess]
 *   Compute:  ...........空闲...........空闲...........
 *
 * 有 Async Compute（并行）:
 *   Graphics: ...........[Render]...........[Composite]
 *   Compute:  [Culling]...........[PostProcess].......
 *              ↑ 不占 Graphics 时间   ↑ 与下一帧 Culling 重叠
 * </pre>
 *
 * <h3>同步机制</h3>
 * 使用 Timeline Semaphore 实现跨队列细粒度同步：
 * <ul>
 *   <li>Compute Queue 完成后 signal timeline value</li>
 *   <li>Graphics Queue 在需要结果时 wait timeline value</li>
 *   <li>无需 Fence，无需 CPU 介入，GPU 自行同步</li>
 * </ul>
 *
 * <h3>三帧轮转</h3>
 * 复用 FrameCommandContext 的三槽环形缓冲模式：
 * <ul>
 *   <li>3 个 Command Buffer 槽位</li>
 *   <li>每帧提交一个槽，不等待</li>
 *   <li>下一帧 begin 时回收旧槽</li>
 * </ul>
 */
public final class AsyncComputeDispatcher {

    private static final Logger LOGGER = Logger.getLogger("Renderium|AsyncCompute");

    private static final int RING_SIZE = 3;
    private static final int MAX_COMMANDS_PER_FRAME = 32;

    // ==================== 队列 ====================
    private static volatile long computeQueue = 0L;
    private static volatile long deviceHandle = 0L;
    private static volatile boolean available = false;

    // ==================== 三帧轮转 ====================
    private static final long[] slotCmdPools = new long[RING_SIZE];
    private static final long[] slotPrimaryCBs = new long[RING_SIZE];
    private static final long[] slotFences = new long[RING_SIZE];
    private static int head = 0;
    private static int tail = 0;
    private static int currentSlot = -1;

    // ==================== Timeline Semaphore ====================
    private static long timelineSemaphore = 0L;
    private static final AtomicLong timelineValue = new AtomicLong(0);
    /** 当前帧 Compute 完成后 signal 的 timeline value */
    private static volatile long currentFrameSignalValue = 0L;

    // ==================== 命令录制 ====================
    private static final AtomicInteger commandCount = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<ComputeCommand> pendingCommands = new ConcurrentLinkedQueue<>();

    // ==================== MethodHandle ====================
    private static MethodHandle vkAllocateCmdBufs;
    private static MethodHandle vkBeginCmdBuf;
    private static MethodHandle vkEndCmdBuf;
    private static MethodHandle vkResetCmdPool;
    private static MethodHandle vkQueueSubmit;
    private static MethodHandle vkWaitFences;
    private static MethodHandle vkResetFences;
    private static MethodHandle vkCreateFence;
    private static MethodHandle vkCreateCmdPool;
    private static MethodHandle vkDestroyFence;
    private static MethodHandle vkDestroyCmdPool;
    private static boolean handlesReady = false;

    // ==================== 统计 ====================
    private static final AtomicLong totalDispatches = new AtomicLong(0);
    private static final AtomicLong totalFrames = new AtomicLong(0);

    private AsyncComputeDispatcher() {}

    // ──────── 初始化 ────────

    public static synchronized boolean initialize() {
        if (available) return true;

        long cQueue = VulkanDeviceHolder.getInstance().getComputeQueue();
        long dev = VulkanDeviceHolder.getInstance().getDevice();

        if (cQueue == 0L) {
            LOGGER.info("AsyncCompute: 无独立 Compute Queue，回退到 Graphics Queue");
            return false;
        }

        computeQueue = cQueue;
        deviceHandle = dev;

        try {
            initMethodHandles();
            if (!handlesReady) return false;

            // 创建三帧轮转资源
            for (int i = 0; i < RING_SIZE; i++) {
                createSlotResources(i);
            }

            // 获取或创建 Timeline Semaphore
            timelineSemaphore = VulkanSyncManager.getOrCreateTimelineSemaphore();

            available = true;
            LOGGER.info("AsyncComputeDispatcher 就绪 [queue=" + Long.toHexString(cQueue)
                + " timeline=" + Long.toHexString(timelineSemaphore) + "]");
            return true;
        } catch (Throwable t) {
            LOGGER.warning("AsyncComputeDispatcher 初始化失败: " + t.getMessage());
            return false;
        }
    }

    private static void initMethodHandles() {
        vkAllocateCmdBufs = VulkanAPIRegistry.getHandle("vkAllocateCommandBuffers");
        vkBeginCmdBuf = VulkanAPIRegistry.getHandle("vkBeginCommandBuffer");
        vkEndCmdBuf = VulkanAPIRegistry.getHandle("vkEndCommandBuffer");
        vkResetCmdPool = VulkanAPIRegistry.getHandle("vkResetCommandPool");
        vkQueueSubmit = VulkanAPIRegistry.getHandle("vkQueueSubmit");
        vkWaitFences = VulkanAPIRegistry.getHandle("vkWaitForFences");
        vkResetFences = VulkanAPIRegistry.getHandle("vkResetFences");
        vkCreateFence = VulkanAPIRegistry.getHandle("vkCreateFence");
        vkCreateCmdPool = VulkanAPIRegistry.getHandle("vkCreateCommandPool");
        vkDestroyFence = VulkanAPIRegistry.getHandle("vkDestroyFence");
        vkDestroyCmdPool = VulkanAPIRegistry.getHandle("vkDestroyCommandPool");

        handlesReady = vkAllocateCmdBufs != null && vkBeginCmdBuf != null
            && vkEndCmdBuf != null && vkResetCmdPool != null && vkQueueSubmit != null;
    }

    // ──────── 帧级 API ────────

    /**
     * 帧开始：回收旧槽 + 重置 CommandPool + 开始录制。
     * @return true 如果本帧可以使用 Async Compute
     */
    public static boolean beginFrame() {
        if (!available || !AdaptivePipelineBalancer.shouldUseAsyncCompute()) return false;

        try {
            // Phase 1: 消费 — 回收已完成的旧槽
            while (tail != head) {
                long fence = slotFences[tail];
                if (fence != 0L && checkFenceSignaled(fence)) {
                    resetFence(fence);
                    tail = (tail + 1) % RING_SIZE;
                } else {
                    break;
                }
            }

            // Phase 2: 等待当前 head 槽
            int slot = head;
            long fence = slotFences[slot];
            if (fence != 0L) {
                waitForFence(fence, 1_000_000L);
                resetFence(fence);
            }

            currentSlot = slot;
            commandCount.set(0);
            pendingCommands.clear();

            // 重置 CommandPool
            if (vkResetCmdPool != null) {
                vkResetCmdPool.invokeWithArguments(deviceHandle, slotCmdPools[slot], 0L);
            }

            // 开始录制 Primary CB
            MemorySegment beginInfo = PerFrameArena.allocateLongs(3);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L); // sType
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L); // ONE_TIME_SUBMIT
            vkBeginCmdBuf.invokeWithArguments(slotPrimaryCBs[slot], beginInfo.address());

            return true;
        } catch (Throwable t) {
            LOGGER.fine("AsyncCompute beginFrame 失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 提交一个 Compute 任务到当前帧的 Async Compute 队列。
     * 任务将在 endFrame() 时批量提交到 Compute Queue。
     *
     * @param pipeline    Compute Pipeline 句柄
     * @param layout      Pipeline Layout 句柄
     * @param descriptorSet Descriptor Set 句柄（0 = 无）
     * @param dispatchX   X 维度工作组数
     * @param dispatchY   Y 维度工作组数
     * @param dispatchZ   Z 维度工作组数
     */
    public static void submitCompute(long pipeline, long layout, long descriptorSet,
                                      int dispatchX, int dispatchY, int dispatchZ) {
        if (!available || currentSlot < 0) return;
        if (commandCount.get() >= MAX_COMMANDS_PER_FRAME) return;

        pendingCommands.offer(new ComputeCommand(pipeline, layout, descriptorSet,
            dispatchX, dispatchY, dispatchZ));
        commandCount.incrementAndGet();
        totalDispatches.incrementAndGet();
    }

    /**
     * 帧结束：录制所有待执行命令 → 提交到 Compute Queue → 不等待。
     * @return 本帧 signal 的 timeline value（Graphics Queue 可等待此值）
     */
    public static long endFrame() {
        if (!available || currentSlot < 0) return 0L;

        try {
            int slot = currentSlot;
            long cb = slotPrimaryCBs[slot];

            // 录制所有待执行命令
            ComputeCommand cmd;
            while ((cmd = pendingCommands.poll()) != null) {
                // vkCmdBindPipeline
                VulkanAPIRegistry.invoke("vkCmdBindPipeline", cb, 0x00000020L /* COMPUTE */,
                    cmd.pipeline);

                // vkCmdBindDescriptorSets（如果有）
                if (cmd.descriptorSet != 0L && cmd.layout != 0L) {
                    MemorySegment dsArray = PerFrameArena.allocateLongs(1);
                    dsArray.set(ValueLayout.JAVA_LONG, 0, cmd.descriptorSet);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cb,
                        0x00000020L /* COMPUTE */, cmd.layout, 0, 1L,
                        dsArray.address(), 0L);
                }

                // vkCmdDispatch
                VulkanAPIRegistry.invoke("vkCmdDispatch", cb,
                    cmd.dispatchX, cmd.dispatchY, cmd.dispatchZ);
            }

            // 结束录制
            vkEndCmdBuf.invokeWithArguments(cb);

            // 构建 VkSubmitInfo（含 Timeline Semaphore signal）
            long signalValue = timelineValue.incrementAndGet();
            currentFrameSignalValue = signalValue;

            // 提交到 Compute Queue
            MemorySegment cmdBufSeg = PerFrameArena.allocateLongs(1);
            cmdBufSeg.set(ValueLayout.JAVA_LONG, 0, cb);

            // VkSubmitInfo: commandBuffers
            MemorySegment submitInfo = PerFrameArena.allocateLongs(6);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L); // sType
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // waitSemaphoreCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L); // pWaitSemaphores
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L); // commandBufferCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, cmdBufSeg.address());

            // Signal timeline semaphore
            if (timelineSemaphore != 0L) {
                VulkanSyncManager.signalTimelineOnSubmit(computeQueue, submitInfo.address(),
                    timelineSemaphore, signalValue);
            } else {
                // 回退: 使用 Fence
                long fence = slotFences[slot];
                vkQueueSubmit.invokeWithArguments(computeQueue, 1L, submitInfo.address(), fence);
            }

            // 推进 head
            head = (head + 1) % RING_SIZE;
            currentSlot = -1;
            totalFrames.incrementAndGet();

            return signalValue;
        } catch (Throwable t) {
            LOGGER.warning("AsyncCompute endFrame 失败: " + t.getMessage());
            currentSlot = -1;
            return 0L;
        }
    }

    // ──────── 跨队列同步 ────────

    /**
     * 获取当前帧 Compute 完成的 Timeline Signal Value。
     * Graphics Queue 可以在渲染时等待此值，确保 Compute 结果可用。
     */
    public static long getCurrentFrameSignalValue() {
        return currentFrameSignalValue;
    }

    /**
     * 获取 Timeline Semaphore 句柄（供 Graphics Queue 端 wait 使用）。
     */
    public static long getTimelineSemaphore() {
        return timelineSemaphore;
    }

    /** Async Compute 是否可用 */
    public static boolean isAvailable() { return available; }

    // ──────── 槽资源创建 ────────

    private static void createSlotResources(int slotIndex) throws Throwable {
        // CommandPool
        MemorySegment poolInfo = PerFrameArena.allocateLongs(3);
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 12L); // sType
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 2L); // RESET_COMMAND_BUFFER_BIT

        MemorySegment outPool = PerFrameArena.allocateLongs(1);
        int rc = (int) vkCreateCmdPool.invokeWithArguments(deviceHandle, poolInfo.address(), 0L, outPool.address());
        if (rc != 0) return;
        slotCmdPools[slotIndex] = outPool.get(ValueLayout.JAVA_LONG, 0);

        // Primary CB
        MemorySegment allocInfo = PerFrameArena.allocateLongs(5);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 44L); // sType
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, slotCmdPools[slotIndex]);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L); // PRIMARY
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);

        MemorySegment primaryOut = PerFrameArena.allocateLongs(1);
        rc = (int) vkAllocateCmdBufs.invokeWithArguments(deviceHandle, allocInfo.address(), primaryOut.address());
        if (rc == 0) slotPrimaryCBs[slotIndex] = primaryOut.get(ValueLayout.JAVA_LONG, 0);

        // Fence (初始 signaled)
        MemorySegment fenceInfo = PerFrameArena.allocateLongs(3);
        fenceInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 6L); // sType
        fenceInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        fenceInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L); // SIGNALED_BIT

        MemorySegment outFence = PerFrameArena.allocateLongs(1);
        rc = (int) vkCreateFence.invokeWithArguments(deviceHandle, fenceInfo.address(), 0L, outFence.address());
        if (rc == 0) slotFences[slotIndex] = outFence.get(ValueLayout.JAVA_LONG, 0);
    }

    // ──────── Fence 辅助 ────────

    private static boolean checkFenceSignaled(long fence) {
        if (fence == 0L || vkWaitFences == null) return false;
        try {
            MemorySegment pFence = PerFrameArena.allocateLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            int result = (int) vkWaitFences.invokeWithArguments(deviceHandle, 1L, pFence.address(), 0L, 0L);
            return result == 0;
        } catch (Throwable t) { return false; }
    }

    private static void waitForFence(long fence, long timeoutNs) {
        if (fence == 0L || vkWaitFences == null) return;
        try {
            MemorySegment pFence = PerFrameArena.allocateLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            vkWaitFences.invokeWithArguments(deviceHandle, 1L, pFence.address(), 1L, timeoutNs);
        } catch (Throwable t) { /* ignore */ }
    }

    private static void resetFence(long fence) {
        if (fence == 0L || vkResetFences == null) return;
        try {
            MemorySegment pFence = PerFrameArena.allocateLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            vkResetFences.invokeWithArguments(deviceHandle, 1L, pFence.address());
        } catch (Throwable t) { /* ignore */ }
    }

    // ──────── 关闭 ────────

    public static void shutdown() {
        if (!available) return;
        available = false;

        for (int i = 0; i < RING_SIZE; i++) {
            if (slotFences[i] != 0L) waitForFence(slotFences[i], 100_000_000L);
            try {
                if (slotFences[i] != 0L && vkDestroyFence != null)
                    vkDestroyFence.invokeWithArguments(deviceHandle, slotFences[i], 0L);
                if (slotCmdPools[i] != 0L && vkDestroyCmdPool != null)
                    vkDestroyCmdPool.invokeWithArguments(deviceHandle, slotCmdPools[i], 0L);
            } catch (Throwable ignored) {}
            slotFences[i] = 0L;
            slotCmdPools[i] = 0L;
            slotPrimaryCBs[i] = 0L;
        }
        head = 0; tail = 0; currentSlot = -1;
    }

    // ──────── 诊断 ────────

    public static String getDiagnostics() {
        return String.format("AsyncCompute[available=%s queue=%s timeline=%s dispatches=%d frames=%d]",
            available, Long.toHexString(computeQueue), Long.toHexString(timelineSemaphore),
            totalDispatches.get(), totalFrames.get());
    }

    // ──────── 内部数据 ────────

    private record ComputeCommand(long pipeline, long layout, long descriptorSet,
                                   int dispatchX, int dispatchY, int dispatchZ) {}
}
