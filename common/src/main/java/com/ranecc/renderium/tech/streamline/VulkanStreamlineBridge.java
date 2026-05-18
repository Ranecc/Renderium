package com.ranecc.renderium.tech.streamline;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Vulkan-Streamline 桥接器 — 连接 Vulkan API 与 Streamline SDK
 */
public class VulkanStreamlineBridge {
    private static final Logger LOGGER = Logger.getLogger(VulkanStreamlineBridge.class.getName());

    private static volatile VulkanStreamlineBridge instance;

    private final AtomicLong deviceHandle = new AtomicLong(0L);
    private final AtomicLong instanceHandle = new AtomicLong(0L);
    private final AtomicLong physicalDeviceHandle = new AtomicLong(0L);
    private final AtomicLong queueHandle = new AtomicLong(0L);
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private volatile ResourceTagData[] taggedResources;
    private volatile int resourceCount;

    public VulkanStreamlineBridge() {
        this.taggedResources = new ResourceTagData[0];
        this.resourceCount = 0;
    }

    public static VulkanStreamlineBridge getInstance() {
        if (instance == null) {
            synchronized (VulkanStreamlineBridge.class) {
                if (instance == null) {
                    instance = new VulkanStreamlineBridge();
                }
            }
        }
        return instance;
    }

    public long getDeviceHandle() { return deviceHandle.get(); }

    public boolean isAvailable() { return available.get(); }

    public boolean isInitialized() { return initialized.get(); }

    public boolean initialize(String pluginPath) {
        if (initialized.get()) {
            LOGGER.fine("VulkanStreamlineBridge already initialized");
            return true;
        }

        if (pluginPath == null || pluginPath.isEmpty()) {
            LOGGER.warning("VulkanStreamlineBridge: pluginPath is null or empty");
            return false;
        }

        this.available.set(true);
        this.initialized.set(true);
        LOGGER.info("VulkanStreamlineBridge initialized with plugin path: " + pluginPath);
        return true;
    }

    public boolean setVulkanInfo(long device, long instance, long physicalDevice, long queue,
                                  int queueFamilyIndex, int queueIndex) {
        boolean valid = device != 0L && instance != 0L && physicalDevice != 0L && queue != 0L;
        if (!valid) {
            LOGGER.warning("VulkanStreamlineBridge.setVulkanInfo: invalid handle(s) provided");
            return false;
        }

        this.deviceHandle.set(device);
        this.instanceHandle.set(instance);
        this.physicalDeviceHandle.set(physicalDevice);
        this.queueHandle.set(queue);

        LOGGER.info("VulkanStreamlineBridge: Vulkan info registered (device=0x"
            + Long.toHexString(device) + ")");
        return true;
    }

    public boolean tagResources(ResourceTagData[] resources) {
        if (resources == null || resources.length == 0) {
            LOGGER.fine("tagResources: empty resources, skipping");
            return true;
        }

        synchronized (this) {
            this.taggedResources = resources.clone();
            this.resourceCount = resources.length;
        }
        LOGGER.fine("tagResources: tagged " + resources.length + " resources");
        return true;
    }

    public ResourceTagData[] getTaggedResources() {
        synchronized (this) {
            return taggedResources.clone();
        }
    }

    public int getResourceCount() {
        return resourceCount;
    }

    public void shutdown() {
        this.initialized.set(false);
        this.available.set(false);
        synchronized (this) {
            this.taggedResources = new ResourceTagData[0];
            this.resourceCount = 0;
        }
        LOGGER.info("VulkanStreamlineBridge shutdown");
    }
}
