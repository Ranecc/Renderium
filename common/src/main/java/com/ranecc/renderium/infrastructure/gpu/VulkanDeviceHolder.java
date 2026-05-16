package com.ranecc.renderium.infrastructure.gpu;

import java.util.logging.Logger;

public class VulkanDeviceHolder {
    private static final Logger LOGGER = Logger.getLogger(VulkanDeviceHolder.class.getName());
    private static final VulkanDeviceHolder INSTANCE = new VulkanDeviceHolder();

    private Object vulkanDeviceObj;
    private long vkDevice = 0L;
    private long vmaAllocator = 0L;
    private long graphicsQueue = 0L;
    private long computeQueue = 0L;
    private long vkQueue = 0L;
    private int queueFamily = -1;
    private boolean initialized;

    private VulkanDeviceHolder() {}

    public static VulkanDeviceHolder getInstance() {
        return INSTANCE;
    }

    public Object getVulkanDevice() { return vulkanDeviceObj; }
    public long getVkDeviceHandle() { return vkDevice; }
    public long getVmaAllocator() { return vmaAllocator; }
    public long getGraphicsQueue() { return graphicsQueue; }
    public long getComputeQueue() { return computeQueue; }
    public long getVkDevice() { return vkDevice; }
    public long getVkQueue() { return vkQueue; }
    public int getQueueFamily() { return queueFamily; }
    public boolean isInitialized() { return initialized; }

    public static boolean isAvailable() { return INSTANCE.initialized; }

    public void initialize(long device, long queue, int family) {
        this.vkDevice = device;
        this.vkQueue = queue;
        this.queueFamily = family;
        this.initialized = true;
    }

    public void initialize(Object vulkanDevice, long vkDeviceHandle, long vmaAllocator,
                           long graphicsQueue, long computeQueue) {
        this.vulkanDeviceObj = vulkanDevice;
        this.vkDevice = vkDeviceHandle;
        this.vmaAllocator = vmaAllocator;
        this.graphicsQueue = graphicsQueue;
        this.computeQueue = computeQueue;
        this.initialized = true;
        LOGGER.info("VulkanDeviceHolder initialized with full device handles");
    }
}
