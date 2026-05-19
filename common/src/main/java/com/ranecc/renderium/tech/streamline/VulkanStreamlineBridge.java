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

    /**
     * 检查 sl.interposer.dll 是否可加载
     * 通过尝试加载 SDK 的核心 DLL 来确认运行时是否存在
     */
    private boolean verifySdkAvailable() {
        try {
            // 尝试通过 Java 系统属性或默认路径定位 SDK
            String nativePath = System.getProperty("renderium.streamline.path", "");
            if (nativePath.isEmpty()) {
                // 尝试 classpath 原生库加载
                System.loadLibrary("sl.interposer");
            } else {
                System.load(nativePath + "/sl.interposer.dll");
            }
            LOGGER.fine("VulkanStreamlineBridge: sl.interposer.dll 加载验证成功");
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.fine("VulkanStreamlineBridge: sl.interposer.dll 不可用: " + e.getMessage());
            return false;
        } catch (SecurityException e) {
            LOGGER.warning("VulkanStreamlineBridge: 安全检查阻止了原生库加载: " + e.getMessage());
            return false;
        }
    }

    public boolean initialize(String pluginPath) {
        if (initialized.get()) {
            LOGGER.fine("VulkanStreamlineBridge already initialized");
            return true;
        }

        if (pluginPath == null || pluginPath.isEmpty()) {
            LOGGER.warning("VulkanStreamlineBridge: pluginPath is null or empty");
            this.available.set(false);
            return false;
        }

        // 验证 Streamline SDK DLL 是否实际可加载
        boolean sdkAvailable = verifySdkAvailable();
        this.available.set(sdkAvailable);
        this.initialized.set(true);

        if (sdkAvailable) {
            LOGGER.info("VulkanStreamlineBridge initialized with plugin path: " + pluginPath);
        } else {
            LOGGER.warning("VulkanStreamlineBridge initialized (stub mode): sl.interposer.dll not found at " + pluginPath);
        }
        return true;
    }

    public boolean setVulkanInfo(long instance, long physicalDevice, long device, long queue,
                                  int queueFamilyIndex, int queueIndex) {
        boolean valid = device != 0L && instance != 0L && physicalDevice != 0L && queue != 0L;
        if (!valid) {
            LOGGER.warning("VulkanStreamlineBridge.setVulkanInfo: invalid handle(s) provided");
            return false;
        }

        this.instanceHandle.set(instance);
        this.physicalDeviceHandle.set(physicalDevice);
        this.deviceHandle.set(device);
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
