package com.ranecc.renderium.infrastructure.gpu;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 全局 Vulkan 句柄容器（零反射版 v2）
 *
 * <p>句柄由 MixinRenderSystem（@Accessor 编译期生成）写入。
 * 所有下游代码通过此单例获取 VkDevice/VMA/Queue 句柄。
 *
 * <h2>与 v1 的区别</h2>
 * <ul>
 *   <li>移除 {@code extractVulkanCommandPool} 反射链</li>
 *   <li>与 {@link VulkanOperationGuard} 联动</li>
 *   <li>新增 degraded 状态（guard 触发后的降级标志）</li>
 * </ul>
 */
public final class VulkanDeviceHolder {
    private static final Logger LOGGER = Logger.getLogger(VulkanDeviceHolder.class.getName());

    private static final VulkanDeviceHolder INSTANCE = new VulkanDeviceHolder();

    private final AtomicLong vkDevice = new AtomicLong(0L);
    private final AtomicLong vmaAllocator = new AtomicLong(0L);
    private final AtomicLong graphicsQueue = new AtomicLong(0L);
    private final AtomicLong computeQueue = new AtomicLong(0L);
    private final AtomicLong vkQueue = new AtomicLong(0L);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    private VulkanDeviceHolder() {}

    public static VulkanDeviceHolder getInstance() { return INSTANCE; }

    public long getDevice() { return vkDevice.get(); }
    public long getVma() { return vmaAllocator.get(); }
    public long getGraphicsQueue() { return graphicsQueue.get(); }
    public long getComputeQueue() { return computeQueue.get(); }
    public long getVkQueue() { return vkQueue.get(); }

    public long getVkDevice() { return vkDevice.get(); }
    public long getVkDeviceHandle() { return vkDevice.get(); }
    public long getVmaAllocator() { return vmaAllocator.get(); }
    public Object getVulkanDevice() { return null; }

    public int getQueueFamily() { return 0; }
    public long getVkCommandPool() { return 0L; }

    public boolean isInitialized() { return initialized.get(); }
    public boolean isDegraded() { return degraded.get(); }
    public boolean isDeviceLost() { return degraded.get(); }

    public static boolean isAvailable() {
        return INSTANCE.initialized.get() && !INSTANCE.degraded.get()
            && VulkanOperationGuard.isOk();
    }

    public void set(long device, long vma, long gQueue, long cQueue) {
        this.vkDevice.set(device);
        this.vmaAllocator.set(vma);
        this.graphicsQueue.set(gQueue);
        this.computeQueue.set(cQueue);
        this.initialized.set(true);
        this.degraded.set(false);
        LOGGER.info("VulkanDeviceHolder set: device=0x" + Long.toHexString(device)
            + " vma=0x" + Long.toHexString(vma));
    }

    public void setQueue(long queue, int family) {
        this.vkQueue.set(queue);
        this.vkDevice.set(queue);
        this.initialized.set(true);
        this.degraded.set(false);
    }

    @Deprecated
    public void initialize(long device, long queue, int family) {
        setQueue(queue, family);
    }

    /**
     * 降级标记 — 与 VulkanOperationGuard 联动
     */
    public void markDegraded() {
        if (degraded.compareAndSet(false, true)) {
            LOGGER.warning("VulkanDeviceHolder: 进入降级模式");
        }
    }

    public void markDeviceLost() {
        markDegraded();
    }
}
