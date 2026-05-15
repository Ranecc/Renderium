package com.ranecc.renderium.infrastructure.gpu;

import java.util.logging.Logger;

public class VulkanDeviceHolder {
    private static final Logger LOGGER = Logger.getLogger(VulkanDeviceHolder.class.getName());
    private static final VulkanDeviceHolder INSTANCE = new VulkanDeviceHolder();

    private VulkanDeviceHolder() {}

    public static VulkanDeviceHolder getInstance() {
        return INSTANCE;
    }

    public long getVkDevice() { return 0L; }
    public long getVkQueue() { return 0L; }
    public int getQueueFamily() { return 0; }
    public boolean isInitialized() { return false; }

    public static boolean isAvailable() { return false; }
}
