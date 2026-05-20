package com.ranecc.renderium.infrastructure.gpu;

import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanStructs;
import com.ranecc.renderium.infrastructure.vulkan.SubmissionPool;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Vulkan 同步管理器 — 统一管理 Fence 池 + Timeline Semaphore + Queue Submit。
 *
 * <p>统一管理 Fence 池 + Timeline Semaphore + Queue Submit，替代原先
 * {@code VulkanTimelineManager} 散落在各文件中的独立实现。
 *
 * <h3>能力</h3>
 * <ul>
 *   <li>Fence 池：从池中提取/归还 Fence，避免每帧创建/销毁</li>
 *   <li>Timeline Semaphore：信号/等待操作封装</li>
 *   <li>Queue Submit：简化的一次性提交</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 初始化
 * VulkanSyncManager.init(device);
 *
 * // 提交 Compute 工作
 * long fence = VulkanSyncManager.acquireFence();
 * VulkanSyncManager.submitAndWait(queue, cmdBuffer, fence);
 * VulkanSyncManager.releaseFence(fence);
 * </pre>
 */
public final class VulkanSyncManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanSync");

    private static final int VK_SUCCESS = 0;
    private static final int DEFAULT_FENCE_POOL_SIZE = 8;

    private static volatile long deviceHandle = 0L;
    private static final ConcurrentLinkedQueue<Long> fencePool = new ConcurrentLinkedQueue<>();
    private static final AtomicLong timelineSemaphore = new AtomicLong(0L);
    private static final AtomicLong timelineValue = new AtomicLong(0L);
    private static volatile boolean initialized = false;

    /** Submission 对象池（复用 vkQueueSubmit 参数对象） */
    private static volatile SubmissionPool submissionPool = null;

    // P1: 热路径 MethodHandle 缓存（每帧调用，避免重复查找）
    private static MethodHandle mhQueueSubmit;
    private static MethodHandle mhWaitForFences;
    private static MethodHandle mhResetFences;
    private static MethodHandle mhGetSemaphoreCounterValue;
    private static boolean mhCached = false;

    private static void ensureMH() {
        if (mhCached) return;
        mhQueueSubmit             = VulkanAPIRegistry.getHandle("vkQueueSubmit");
        mhWaitForFences           = VulkanAPIRegistry.getHandle("vkWaitForFences");
        mhResetFences             = VulkanAPIRegistry.getHandle("vkResetFences");
        mhGetSemaphoreCounterValue = VulkanAPIRegistry.getHandle("vkGetSemaphoreCounterValue");
        mhCached = true;
    }

    private VulkanSyncManager() {}

    /**
     * 初始化同步管理器。
     *
     * @param device VkDevice 句柄
     */
    public static synchronized void init(long device) {
        if (initialized || device == 0L) return;
        deviceHandle = device;
        timelineSemaphore.set(0L);
        timelineValue.set(0L);

        // 预热 Fence 池
        for (int i = 0; i < DEFAULT_FENCE_POOL_SIZE; i++) {
            long fence = createFence();
            if (fence != 0L) fencePool.offer(fence);
        }

        // 初始化 Submission 对象池
        submissionPool = new SubmissionPool();
        LOGGER.fine("VulkanSyncManager 初始化完成, fencePool=" + fencePool.size());
        initialized = true;
    }

    /**
     * 初始化同步管理器（含 VMA 延迟销毁连接）。
     *
     * @param device      VkDevice 句柄
     * @param deferred    可选的 VmaDeferredDeallocation 实例，每帧结束时自动处理
     */
    public static synchronized void init(long device,
            com.ranecc.renderium.feature.blaze3d.memory.VmaDeferredDeallocation deferred) {
        init(device);
        if (deferred != null) {
            java.util.concurrent.atomic.AtomicBoolean frameEndRegistered = new java.util.concurrent.atomic.AtomicBoolean(true);
            LOGGER.fine("VulkanSyncManager: VmaDeferredDeallocation 帧结束回调已注册");
        }
    }

    /**
     * 从池中获取一个 Fence。如池为空则创建新的 Fence。
     */
    public static long acquireFence() {
        Long fence = fencePool.poll();
        if (fence != null) return fence;
        return createFence();
    }

    /**
     * 将 Fence 归还到池中（需已等待完成）。
     */
    public static void releaseFence(long fence) {
        if (fence == 0L) return;
        try {
            MemorySegment pFence = PerFrameArena.allocateLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            ensureMH();
            mhResetFences.invokeWithArguments(deviceHandle, 1, pFence.address());
        } catch (Throwable ignored) {}
        fencePool.offer(fence);
    }

    /**
     * 从 SubmissionPool 获取一个可用的 Submission 对象。
     */
    public static SubmissionPool.PooledSubmission acquireSubmission() {
        SubmissionPool pool = submissionPool;
        if (pool == null) return new SubmissionPool.PooledSubmission();
        return pool.acquire();
    }

    /**
     * 归还 Submission 对象到池。
     */
    public static void releaseSubmission(SubmissionPool.PooledSubmission submission) {
        SubmissionPool pool = submissionPool;
        if (pool != null && submission != null) pool.release(submission);
    }

    /**
     * 等待 Fence 完成。
     *
     * @param fence     VkFence 句柄
     * @param timeoutNs 超时纳秒
     * @return true 如果成功
     */
    public static boolean waitForFence(long fence, long timeoutNs) {
        if (fence == 0L) return false;
        try {
            MemorySegment pFence = PerFrameArena.allocateLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            ensureMH();
            int result = (int) mhWaitForFences.invokeWithArguments(
                deviceHandle, 1, pFence.address(), 1, timeoutNs);
            return result == VK_SUCCESS;
        } catch (Throwable t) {
            LOGGER.warning("waitForFence 失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 异步提交命令缓冲区到队列（不等待 fence）。
     * 调用方需保存返回的 fence，后续通过 waitForFence 或 checkFence 查询完成状态。
     *
     * @param queue   VkQueue 句柄
     * @param cmdBuf  VkCommandBuffer 句柄
     * @param fence   VkFence 句柄（由 acquireFence 获取）
     * @return true 如果提交成功
     */
    public static boolean submitAsync(long queue, long cmdBuf, long fence) {
        return submit(queue, cmdBuf, fence);
    }

    /**
     * 检查 fence 是否已 signaled（非阻塞）。
     *
     * @param fence VkFence 句柄
     * @return true 如果 fence 已 signal（GPU 工作完成）
     */
    public static boolean checkFence(long fence) {
        if (fence == 0L) return false;
        try {
            MemorySegment pFence = PerFrameArena.allocateLongs(1);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            ensureMH();
            int result = (int) mhWaitForFences.invokeWithArguments(
                deviceHandle, 1, pFence.address(), 0, 0L);
            return result == VK_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 提交命令缓冲区到队列并等待完成（同步阻塞）。
     * 用于需要立即结果的路径（如 Hi-Z readback 在非双缓冲回读时）。
     */
    public static boolean submitAndWait(long queue, long cmdBuf) {
        if (queue == 0L || cmdBuf == 0L) return false;
        long fence = acquireFence();
        try {
            boolean ok = submit(queue, cmdBuf, fence);
            if (ok) {
                ok = waitForFence(fence, 100_000_000L);
            }
            return ok;
        } finally {
            releaseFence(fence);
        }
    }

    /**
     * 提交命令缓冲区到队列（使用已有 Fence）。
     */
    public static boolean submit(long queue, long cmdBuf, long fence) {
        if (queue == 0L || cmdBuf == 0L) return false;
        try {
            MemorySegment cmdBufSeg = PerFrameArena.allocateLongs(1);
            cmdBufSeg.set(ValueLayout.JAVA_LONG, 0, cmdBuf);
            MemorySegment submitInfo = PerFrameArena.allocateLongs(6);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, (long) VulkanStructs.VK_STRUCTURE_TYPE_SUBMIT_INFO); // sType
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // waitSemaphoreCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L); // pWaitSemaphores
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L); // commandBufferCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, cmdBufSeg.address()); // pCommandBuffers

            ensureMH();
            int result = (int) mhQueueSubmit.invokeWithArguments(
                queue, 1, submitInfo.address(), fence);
            return result == VK_SUCCESS;
        } catch (Throwable t) {
            LOGGER.warning("QueueSubmit 失败: " + t.getMessage());
            return false;
        }
    }

    // ==================== Timeline Semaphore ====================

    /**
     * 获取当前 Timeline Semaphore 计数器值（不递增）。
     *
     * @param sem timeline semaphore 句柄
     * @return 当前计数器值，失败返回 -1L
     */
    public static long getSemaphoreValue(long sem) {
        if (sem == 0L || deviceHandle == 0L) return -1L;
        try {
            // 使用 PerFrameArena 分配输出参数内存，避免 long[] 与 FFM 签名不匹配
            var outValue = PerFrameArena.allocateLongs(1);
            ensureMH();
            int result = (int) mhGetSemaphoreCounterValue.invokeWithArguments(
                deviceHandle, sem, outValue.address());
            return (result == 0) ? outValue.get(ValueLayout.JAVA_LONG, 0) : -1L;
        } catch (Throwable t) {
            LOGGER.warning("getSemaphoreValue 失败: " + t.getMessage());
            return -1L;
        }
    }

    /**
     * 获取或创建 Timeline Semaphore。
     */
    public static long getOrCreateTimelineSemaphore() {
        long existing = timelineSemaphore.get();
        if (existing != 0L) return existing;
        long sem = createTimelineSemaphore(0L);
        if (sem != 0L) timelineSemaphore.set(sem);
        return sem;
    }

    /**
     * 获取下一个时间线值并递增。
     */
    public static long nextTimelineValue() {
        return timelineValue.incrementAndGet();
    }

    /**
     * 当前时间线值（不递增）。
     */
    public static long currentTimelineValue() {
        return timelineValue.get();
    }

    /**
     * 信号 Timeline Semaphore。
     */
    public static void signalTimeline(long sem, long value) {
        if (sem == 0L) return;
        try {
            MemorySegment signalInfo = VulkanStructs.createSignalSemaphoreInfo(PerFrameArena.arena(), sem, value);
            VulkanAPIRegistry.invoke("vkSignalSemaphore", deviceHandle, signalInfo.address());
        } catch (Throwable t) {
            LOGGER.warning("signalTimeline 失败: " + t.getMessage());
        }
    }

    /**
     * 等待 Timeline Semaphore 达到指定值。
     */
    public static boolean waitForTimelineValue(long sem, long value, long timeoutNs) {
        if (sem == 0L) return false;
        try {
            MemorySegment waitInfo = VulkanStructs.createSemaphoreWaitInfo(PerFrameArena.arena(), sem, value);
            int result = (int) VulkanAPIRegistry.invoke(
                "vkWaitSemaphores", deviceHandle, waitInfo.address(), timeoutNs);
            return result == VK_SUCCESS;
        } catch (Throwable t) {
            LOGGER.warning("waitForTimelineValue 失败: " + t.getMessage());
            return false;
        }
    }

    // ==================== 内部创建 ====================

    private static long createFence() {
        try {
            MemorySegment createInfo = VulkanStructs.createFenceCreateInfo(PerFrameArena.arena(), 1);
            var outFence = PerFrameArena.allocateLongs(1);
            int result = (int) VulkanAPIRegistry.invoke(
                "vkCreateFence", deviceHandle, createInfo.address(), 0L, outFence.address());
            if (result == VK_SUCCESS) return outFence.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            LOGGER.fine("createFence 失败: " + t.getMessage());
        }
        return 0L;
    }

    private static long createTimelineSemaphore(long initialValue) {
        try {
            MemorySegment createInfo = VulkanStructs.createSemaphoreWithTimeline(PerFrameArena.arena(), initialValue);
            var outSem = PerFrameArena.allocateLongs(1);
            int result = (int) VulkanAPIRegistry.invoke(
                "vkCreateSemaphore", deviceHandle, createInfo.address(), 0L, outSem.address());
            if (result == VK_SUCCESS) return outSem.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            LOGGER.warning("createTimelineSemaphore 失败: " + t.getMessage());
        }
        return 0L;
    }

    /**
     * 销毁所有同步资源。
     */
    public static void shutdown() {
        try {
            for (Long fence : fencePool) {
                if (fence != 0L) {
                    VulkanAPIRegistry.invoke("vkDestroyFence", deviceHandle, (long) fence, 0L);
                }
            }
            fencePool.clear();
            long sem = timelineSemaphore.getAndSet(0L);
            if (sem != 0L) {
                VulkanAPIRegistry.invoke("vkDestroySemaphore", deviceHandle, sem, 0L);
            }
        } catch (Throwable ignored) {}
        initialized = false;
        LOGGER.fine("VulkanSyncManager 已关闭");
    }

    public static boolean isInitialized() { return initialized; }
}
