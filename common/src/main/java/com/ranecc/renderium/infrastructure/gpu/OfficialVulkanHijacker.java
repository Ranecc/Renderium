package com.ranecc.renderium.infrastructure.gpu;

import java.util.logging.Logger;

public final class OfficialVulkanHijacker {

    private static final Logger LOGGER = Logger.getLogger(OfficialVulkanHijacker.class.getName());

    private static final OfficialVulkanHijacker INSTANCE = new OfficialVulkanHijacker();

    private long vkDevice;
    private long graphicsQueue;
    private long computeQueue;
    private boolean available;

    private OfficialVulkanHijacker() {}

    public static OfficialVulkanHijacker getInstance() {
        return INSTANCE;
    }

    public boolean isAvailable() {
        return available;
    }

    public long getVkDevice() {
        return vkDevice;
    }

    public long getGraphicsQueue() {
        return graphicsQueue;
    }

    public long getComputeQueue() {
        return computeQueue;
    }

    public void initialize(long vkDevice, long graphicsQueue, long computeQueue) {
        this.vkDevice = vkDevice;
        this.graphicsQueue = graphicsQueue;
        this.computeQueue = computeQueue;
        this.available = (vkDevice != 0L);
        LOGGER.info("OfficialVulkanHijacker initialized, vkDevice=" + vkDevice);
    }

    public void shutdown() {
        this.vkDevice = 0L;
        this.graphicsQueue = 0L;
        this.computeQueue = 0L;
        this.available = false;
        LOGGER.info("OfficialVulkanHijacker shut down");
    }
}
