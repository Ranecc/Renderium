package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

/**
 * Phase 4: Vulkan Timeline Semaphore 管理器
 *
 * <p>使用 Vulkan 1.2 VK_SEMAPHORE_TYPE_TIMELINE 实现
 * Graphics Queue 和 Compute Queue 之间的异步并行调度。
 *
 * <h2>双队列并行模式：</h2>
 * <pre>
 * Frame N:
 *   Compute Queue:  DepthPyramid → Culling dispatch → Signal(sem, N+1) → Bloom compute
 *   Graphics Queue: Wait(sem, N) → G-Buffer → Shading → Transparent
 *
 * Timeline:
 *   Graphics帧N发出 Culling结果 后，Compute帧N+1 无需等待 Graphics
 *   Graphics帧N 在 Wait(sem, N) 处等待 Compute帧N 的剔除结果
 * </pre>
 *
 * <h2>API 使用：</h2>
 * <pre>{@code
 * long sem = VulkanTimelineManager.getOrCreateSemaphore();
 * long value = VulkanTimelineManager.getNextValue();
 *
 * // Compute Queue side — 剔除完成后
 * VulkanTimelineManager.signal(sem, value);
 *
 * // Graphics Queue side — 渲染前等待
 * VulkanTimelineManager.waitForValue(sem, value);
 * }</pre>
 */
public final class VulkanTimelineManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanTLMgr");

    private static final int VK_SEMAPHORE_TYPE_TIMELINE = 1;
    private static final int VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO = 1000203001;
    private static final int VK_SUCCESS = 0;

    private static final AtomicLong timelineSemaphore = new AtomicLong(0L);
    private static final AtomicLong currentValue = new AtomicLong(0L);
    private static volatile boolean dualQueueAvailable = false;
    private static volatile boolean initialized = false;

    private VulkanTimelineManager() {}

    public static boolean isDualQueueAvailable() { return dualQueueAvailable; }

    public static boolean isAvailable() {
        return VulkanDeviceHolder.isAvailable()
            && VulkanFFMBinding.isFfmLoaded()
            && VulkanFFMBinding.getVkCreateSemaphore() != null;
    }

    /**
     * 获取或创建全局 Timeline Semaphore
     */
    public static long getOrCreateSemaphore() {
        if (timelineSemaphore.get() != 0L) return timelineSemaphore.get();
        init();
        return timelineSemaphore.get();
    }

    /**
     * 获取下一个 time value
     */
    public static long getNextValue() {
        return currentValue.incrementAndGet();
    }

    /**
     * 获取当前 time value（不递增）
     */
    public static long getCurrentValue() {
        return currentValue.get();
    }

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        if (!isAvailable()) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        long gQueue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
        long cQueue = VulkanDeviceHolder.getInstance().getComputeQueue();

        dualQueueAvailable = (gQueue != 0L && cQueue != 0L && gQueue != cQueue);

        try (Arena arena = Arena.ofConfined()) {
            var typeCreateInfo = arena.allocate(24);
            typeCreateInfo.set(ValueLayout.JAVA_INT, 0, VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO);
            typeCreateInfo.set(ValueLayout.JAVA_LONG, 8, 0L);
            typeCreateInfo.set(ValueLayout.JAVA_INT, 16, VK_SEMAPHORE_TYPE_TIMELINE);
            typeCreateInfo.set(ValueLayout.JAVA_LONG, 16, 0L);

            var createInfo = arena.allocate(16);
            createInfo.set(ValueLayout.JAVA_INT, 0, 0);
            createInfo.set(ValueLayout.ADDRESS, 8, typeCreateInfo);

            long[] outSem = new long[1];
            int result = (int) VulkanFFMBinding.getVkCreateSemaphore()
                .invoke(device, createInfo.address(), 0L, outSem);
            if (result == VK_SUCCESS) {
                timelineSemaphore.set(outSem[0]);
                LOGGER.info("Timeline Semaphore created: 0x" + Long.toHexString(outSem[0])
                    + " dualQueue=" + dualQueueAvailable);
            } else {
                LOGGER.warning("vkCreateSemaphore(TIMELINE) failed VkResult=" + result);
            }
        } catch (Throwable t) {
            LOGGER.warning("Timeline Semaphore creation failed: " + t.getMessage());
        }
    }

    /**
     * 从队列侧 signal timeline semaphore
     *
     * @param sem   timeline semaphore handle
     * @param value 要 signal 的值（即帧序号 N）
     */
    public static void signal(long sem, long value) {
        long cQueue = VulkanDeviceHolder.getInstance().getComputeQueue();
        if (cQueue == 0L || sem == 0L) return;
        try (Arena arena = Arena.ofConfined()) {
            var signalInfo = arena.allocate(32);
            signalInfo.set(ValueLayout.JAVA_LONG, 0, sem);
            signalInfo.set(ValueLayout.JAVA_LONG, 8, value);
            long submitInfo = arena.allocate(16).address();
            int result = (int) VulkanFFMBinding.getVkSignalSemaphore()
                .invoke(cQueue, submitInfo);
            if (result != VK_SUCCESS) {
                LOGGER.warning("Signal failed: sem=0x" + Long.toHexString(sem)
                    + " value=" + value + " result=" + result);
            }
        } catch (Throwable t) {
            LOGGER.warning("Signal exception: " + t.getMessage());
        }
    }

    /**
     * 从队列侧 wait timeline semaphore
     *
     * @param sem   timeline semaphore handle
     * @param value 要等待的值
     * @param timeoutNs 超时（纳秒），0 = 不超时
     * @return true 如果成功
     */
    public static boolean waitForValue(long sem, long value, long timeoutNs) {
        long gQueue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
        if (gQueue == 0L || sem == 0L) return false;
        try (Arena arena = Arena.ofConfined()) {
            var waitInfo = arena.allocate(24);
            waitInfo.set(ValueLayout.JAVA_LONG, 0, sem);
            waitInfo.set(ValueLayout.JAVA_LONG, 8, value);
            long submitInfo = arena.allocate(16).address();
            int result = (int) VulkanFFMBinding.getVkWaitSemaphores()
                .invoke(gQueue, submitInfo, timeoutNs);
            return result == VK_SUCCESS;
        } catch (Throwable t) {
            LOGGER.warning("Wait exception: " + t.getMessage());
            return false;
        }
    }

    /**
     * 查询 timeline semaphore 的当前计数器值
     */
    public static long getSemaphoreValue(long sem) {
        if (sem == 0L) return -1L;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return -1L;
        try {
            long[] outValue = new long[1];
            int result = (int) VulkanFFMBinding.getVkGetSemaphoreCounterValue()
                .invoke(device, sem, 0L, outValue);
            if (result == VK_SUCCESS) return outValue[0];
            return -1L;
        } catch (Throwable t) {
            LOGGER.warning("getSemaphoreValue exception: " + t.getMessage());
            return -1L;
        }
    }

    /**
     * 安全销毁 timeline semaphore
     */
    public static void destroy() {
        long sem = timelineSemaphore.getAndSet(0L);
        if (sem == 0L) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        try {
            VulkanFFMBinding.getVkDestroySemaphore().invoke(device, sem, 0L);
            LOGGER.info("Timeline Semaphore destroyed");
        } catch (Throwable ignored) {}
    }
}
