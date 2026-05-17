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
    private long vkCommandPool = 0L;
    private volatile boolean initialized;
    private volatile boolean deviceLost = false;

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
    public boolean isDeviceLost() { return deviceLost; }
    public long getVkCommandPool() { return vkCommandPool; }

    public static boolean isAvailable() { return INSTANCE.initialized && !INSTANCE.deviceLost; }

    public void markDeviceLost() {
        this.initialized = false;
        this.deviceLost = true;
        LOGGER.severe("VulkanDeviceHolder: 设备标记为丢失状态");
    }

    public void initialize(long device, long queue, int family) {
        this.vkDevice = device;
        this.vkQueue = queue;
        this.queueFamily = family;
        this.deviceLost = false;
        this.initialized = true;
    }

    public void initialize(Object vulkanDevice, long vkDeviceHandle, long vmaAllocator,
                           long graphicsQueue, long computeQueue) {
        this.vulkanDeviceObj = vulkanDevice;
        this.vkDevice = vkDeviceHandle;
        this.vmaAllocator = vmaAllocator;
        this.graphicsQueue = graphicsQueue;
        this.computeQueue = computeQueue;
        this.deviceLost = false;
        this.initialized = true;

        // 通过反射提取 s7 VulkanCommandPool 句柄
        extractVulkanCommandPool(vulkanDevice);

        LOGGER.info("VulkanDeviceHolder initialized with full device handles");
    }

    /**
     * 通过反射从 s7 VulkanDevice 提取 VulkanCommandPool 的底层句柄。
     * 路径: VulkanDevice.commandEncoder → VulkanCommandEncoder.commandPools[0] → VulkanCommandPool.commandPool
     * 失败时不中断初始化，CommandBatcher 将回退到直接分配。
     */
    private void extractVulkanCommandPool(Object vulkanDevice) {
        try {
            java.lang.reflect.Field encoderField = vulkanDevice.getClass().getDeclaredField("commandEncoder");
            encoderField.setAccessible(true);
            Object encoderObj = encoderField.get(vulkanDevice);
            if (encoderObj == null) return;

            java.lang.reflect.Field poolsField = encoderObj.getClass().getDeclaredField("commandPools");
            poolsField.setAccessible(true);
            Object[] pools = (Object[]) poolsField.get(encoderObj);
            if (pools == null || pools.length == 0 || pools[0] == null) return;

            java.lang.reflect.Field poolField = pools[0].getClass().getDeclaredField("commandPool");
            poolField.setAccessible(true);
            this.vkCommandPool = poolField.getLong(pools[0]);

            LOGGER.fine("VulkanCommandPool 句柄获取成功: 0x" + Long.toHexString(vkCommandPool));
        } catch (Exception e) {
            LOGGER.fine("无法获取 VulkanCommandPool 句柄 (非致命): " + e.getMessage());
        }
    }
}
