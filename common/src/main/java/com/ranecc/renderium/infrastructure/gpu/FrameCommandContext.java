package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.logging.Logger;

/**
 * 帧级共享 Command Buffer 上下文。
 *
 * <h3>问题</h3>
 * 旧渲染管线中，~25 个节点各自调用 {@code VulkanSyncManager.submitAndWait()}，
 * 即每个 pass 都执行一次 vkQueueSubmit + vkWaitForFences。
 * 相当于 CPU 给 GPU 写 25 封信并站在邮筒前等每封的回执。
 * 性能影响：~2.5ms/帧 (~15% 帧预算) 浪费在 Fence 等待上。
 *
 * <h3>方案（方案 B — Secondary CB Pool）</h3>
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
 *     └─ vkWaitForFences       — 单次等待（1 个 Fence 替代 25 个）
 * </pre>
 *
 * <h3>性能收益</h3>
 * 25 次 submitAndWait → 1 次：消除 ~2.5ms/帧 CPU Fence 等待。
 *
 * <h3>API 使用</h3>
 * 由 HookDispatcher 调用 init/beginFrame/endFrame，
 * 节点只需调用 beginNodeCB/endNodeCB 替代旧的 allocate/begin/end/submitAndWait。
 */
public final class FrameCommandContext {

    private static final Logger LOGGER = Logger.getLogger("Renderium|FrameCmdCtx");

    static final int MAX_NODES = 64;

    private static volatile boolean initialized;
    private static long deviceHandle;
    private static long frameQueue;
    private static long commandPool;
    private static long primaryCmdBuf;
    private static long frameFence;
    private static final long[] secondaryCBs = new long[MAX_NODES];
    private static final boolean[] usedNodes = new boolean[MAX_NODES];

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

            if (vkAllocateCmdBufs == null || vkBeginCmdBuf == null || vkEndCmdBuf == null) {
                LOGGER.severe("[init] 缺少核心 Vulkan API 句柄，初始化终止");
                return;
            }
            handlesReady = true;

            createPoolAndAllocateCBs();
            createFrameFence();
            initialized = true;
            LOGGER.info("FrameCommandContext 就绪: pool=" + Long.toHexString(commandPool)
                + " cbCount=" + countValidCBs() + " fence=" + Long.toHexString(frameFence));
        } catch (Throwable t) {
            LOGGER.severe("[init] " + t.getMessage());
        }
    }

    // ──────── 帧级 API ────────

    public static void beginFrame() {
        if (!initialized || !handlesReady) return;
        try {
            if (vkResetCmdPool != null) {
                vkResetCmdPool.invokeWithArguments(deviceHandle, commandPool, 0L);
            }
            if (vkResetFences != null) {
                vkResetFences.invokeWithArguments(deviceHandle, 1L, frameFence);
            }
            java.util.Arrays.fill(usedNodes, false);

            MemorySegment beginInfo = nativeLongs(3);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);
            vkBeginCmdBuf.invokeWithArguments(primaryCmdBuf, beginInfo.address());

        } catch (Throwable t) {
            LOGGER.warning("[beginFrame] " + t.getMessage());
        }
    }

    /**
     * 获取指定索引的 Secondary CB 并 begin 它。
     * @return CB 句柄，失败返回 0L
     */
    public static long beginNodeCB(int nodeIndex) {
        if (!initialized || nodeIndex < 0 || nodeIndex >= MAX_NODES) return 0L;
        long cb = secondaryCBs[nodeIndex];
        if (cb == 0L) return 0L;
        try {
            MemorySegment inheritInfo = nativeLongs(8);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 1L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 0L);
            inheritInfo.setAtIndex(ValueLayout.JAVA_LONG, 7, 0L);

            MemorySegment beginInfo = nativeLongs(3);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, inheritInfo.address());
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);

            vkBeginCmdBuf.invokeWithArguments(cb, beginInfo.address());
            usedNodes[nodeIndex] = true;
            return cb;
        } catch (Throwable t) {
            LOGGER.warning("[beginNodeCB:" + nodeIndex + "] " + t.getMessage());
            return 0L;
        }
    }

    /** 结束指定节点的 Secondary CB 录制 */
    public static void endNodeCB(int nodeIndex) {
        if (!initialized || nodeIndex < 0 || nodeIndex >= MAX_NODES) return;
        if (!usedNodes[nodeIndex]) return;
        try {
            vkEndCmdBuf.invokeWithArguments(secondaryCBs[nodeIndex]);
        } catch (Throwable t) {
            LOGGER.warning("[endNodeCB:" + nodeIndex + "] " + t.getMessage());
        }
    }

    /** 帧结束：批量执行 → Submit → Wait */
    public static void endFrame() {
        if (!initialized || !handlesReady) return;
        try {
            int count = 0;
            for (int i = 0; i < MAX_NODES; i++) {
                if (usedNodes[i]) count++;
            }
            if (count == 0) return;

            long[] handles = new long[count];
            int idx = 0;
            for (int i = 0; i < MAX_NODES; i++) {
                if (usedNodes[i]) {
                    handles[idx++] = secondaryCBs[i];
                }
            }
            MemorySegment handleArray = nativeLongs(count);
            for (int i = 0; i < count; i++) {
                handleArray.setAtIndex(ValueLayout.JAVA_LONG, i, handles[i]);
            }

            if (vkExecuteCmds != null) {
                vkExecuteCmds.invokeWithArguments(primaryCmdBuf, (long)count, handleArray.address());
            }
            vkEndCmdBuf.invokeWithArguments(primaryCmdBuf);

            MemorySegment submitInfo = nativeLongs(8);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, 1L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 7, frameFence);

            if (vkQueueSubmit != null) {
                vkQueueSubmit.invokeWithArguments(frameQueue, 1L, submitInfo.address(), frameFence);
            }
            if (vkWaitFences != null) {
                vkWaitFences.invokeWithArguments(deviceHandle, 1L, frameFence, 0L, 0xFFFFFFFFFFFFFFFFL);
            }
        } catch (Throwable t) {
            LOGGER.warning("[endFrame] " + t.getMessage());
        }
    }

    public static long getQueue() { return frameQueue; }
    public static boolean isInitialized() { return initialized; }

    // ──────── internal ────────

    private static void createPoolAndAllocateCBs() throws Throwable {
        if (vkCreateCmdPool == null) return;

        MemorySegment poolInfo = nativeLongs(3);
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 12L);
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        poolInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 2L);

        MemorySegment out = nativeLongs(1);
        int rc = (int) vkCreateCmdPool.invokeWithArguments(deviceHandle, poolInfo.address(), 0L, out.address());
        if (rc != 0) { LOGGER.severe("createPool failed: " + rc); return; }
        commandPool = out.get(ValueLayout.JAVA_LONG, 0);

        if (vkAllocateCmdBufs == null) return;
        MemorySegment allocInfo = nativeLongs(5);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 44L);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, commandPool);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);

        MemorySegment primaryOut = nativeLongs(1);
        rc = (int) vkAllocateCmdBufs.invokeWithArguments(deviceHandle, allocInfo.address(), primaryOut.address());
        if (rc == 0) primaryCmdBuf = primaryOut.get(ValueLayout.JAVA_LONG, 0);

        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 1L);
        allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, (long)MAX_NODES);
        MemorySegment secondaryOut = nativeLongs(MAX_NODES);
        rc = (int) vkAllocateCmdBufs.invokeWithArguments(deviceHandle, allocInfo.address(), secondaryOut.address());
        if (rc == 0) {
            for (int i = 0; i < MAX_NODES; i++)
                secondaryCBs[i] = secondaryOut.getAtIndex(ValueLayout.JAVA_LONG, i);
        }
    }

    private static void createFrameFence() throws Throwable {
        if (vkCreateFence == null) return;
        MemorySegment info = nativeLongs(3);
        info.setAtIndex(ValueLayout.JAVA_LONG, 0, 6L);
        info.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        info.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);

        MemorySegment out = nativeLongs(1);
        int rc = (int) vkCreateFence.invokeWithArguments(deviceHandle, info.address(), 0L, out.address());
        if (rc == 0) frameFence = out.get(ValueLayout.JAVA_LONG, 0);
    }

    private static int countValidCBs() {
        int n = 0;
        for (long cb : secondaryCBs) if (cb != 0L) n++;
        return n;
    }

    private static MemorySegment nativeLongs(int count) {
        return java.lang.foreign.Arena.global().allocate(ValueLayout.JAVA_LONG, count);
    }

    public static void shutdown() {
        if (!initialized) return;
        initialized = false;
        java.util.Arrays.fill(secondaryCBs, 0L);
        primaryCmdBuf = 0L;
        frameFence = 0L;
        LOGGER.fine("FrameCommandContext 已关闭");
    }
}
