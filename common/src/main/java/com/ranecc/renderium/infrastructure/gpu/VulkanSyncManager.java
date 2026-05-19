package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Vulkan 同步管理器 — 统一管理 Fence 池 + Timeline Semaphore + Queue Submit。
 *
 * <p>合并 {@link VulkanTimelineManager} 的时间线信号量管理与散落在
 * {@code LodCullingComputePass} / {@code GPUCullingPipeline} 中的 Fence 创建/提交逻辑。
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
        LOGGER.fine("VulkanSyncManager 初始化完成, fencePool=" + fencePool.size());
        initialized = true;
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pFence = arena.allocate(ValueLayout.JAVA_LONG);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            VulkanAPIRegistry.invoke("vkResetFences", deviceHandle, 1, pFence.address());
        } catch (Throwable ignored) {}
        fencePool.offer(fence);
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pFence = arena.allocate(ValueLayout.JAVA_LONG);
            pFence.set(ValueLayout.JAVA_LONG, 0, fence);
            int result = (int) VulkanAPIRegistry.invoke(
                "vkWaitForFences", deviceHandle, 1, pFence.address(), 1, timeoutNs);
            return result == VK_SUCCESS;
        } catch (Throwable t) {
            LOGGER.warning("waitForFence 失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 提交命令缓冲区到队列并等待完成。
     *
     * @param queue   VkQueue 句柄
     * @param cmdBuf  VkCommandBuffer 句柄
     * @return true 如果提交成功
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cmdBufSeg = arena.allocate(ValueLayout.JAVA_LONG);
            cmdBufSeg.set(ValueLayout.JAVA_LONG, 0, cmdBuf);
            MemorySegment submitInfo = arena.allocate(ValueLayout.JAVA_LONG, 6);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L); // sType
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L); // pNext
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L); // waitSemaphoreCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L); // pWaitSemaphores
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L); // commandBufferCount
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, cmdBufSeg.address()); // pCommandBuffers

            int result = (int) VulkanAPIRegistry.invoke(
                "vkQueueSubmit", queue, 1, submitInfo.address(), fence);
            return result == VK_SUCCESS;
        } catch (Throwable t) {
            LOGGER.warning("QueueSubmit 失败: " + t.getMessage());
            return false;
        }
    }

    // ==================== Timeline Semaphore ====================

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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment signalInfo = VulkanStructs.createSignalSemaphoreInfo(arena, sem, value);
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment waitInfo = VulkanStructs.createSemaphoreWaitInfo(arena, sem, value);
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment createInfo = VulkanStructs.createFenceCreateInfo(arena, 1);
            long[] outFence = new long[1];
            int result = (int) VulkanAPIRegistry.invoke(
                "vkCreateFence", deviceHandle, createInfo.address(), 0L, outFence);
            if (result == VK_SUCCESS) return outFence[0];
        } catch (Throwable t) {
            LOGGER.fine("createFence 失败: " + t.getMessage());
        }
        return 0L;
    }

    private static long createTimelineSemaphore(long initialValue) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment createInfo = VulkanStructs.createSemaphoreWithTimeline(arena, initialValue);
            long[] outSem = new long[1];
            int result = (int) VulkanAPIRegistry.invoke(
                "vkCreateSemaphore", deviceHandle, createInfo.address(), 0L, outSem);
            if (result == VK_SUCCESS) return outSem[0];
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
