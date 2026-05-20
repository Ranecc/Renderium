package com.ranecc.renderium.tech.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.tech.streamline.ffm.SLFFMBindings;

/**
 * Vulkan-Streamline 桥接器 — 连接 Vulkan API 与 Streamline SDK
 */
public class VulkanStreamlineBridge {
    private static final Logger LOGGER = Logger.getLogger(VulkanStreamlineBridge.class.getName());

    private static volatile VulkanStreamlineBridge instance;

    // sl::VulkanInfo 结构体总大小（88 字节，包含 BaseStructure 和对齐填充）
    private static final long VULKAN_INFO_SIZE = 88L;

    // Streamline SDK 2.10.3 VulkanInfo 结构版本号（kStructVersion3）
    private static final int K_STRUCT_VERSION_VULKAN_INFO = 3;

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

    /**
     * 构建真实的 sl::VulkanInfo 结构体并调用 SLFFMBindings.slSetVulkanInfo()
     * <p>
     * sl::VulkanInfo 结构体布局（Streamline SDK 2.10.3, kStructVersion3, 88 字节）：
     * <pre>
     * Offset  Size  Field
     *  0       16   BaseStructure.sType (GUID)
     *  16       4   BaseStructure.structVersion (uint32)
     *  20       4   padding
     *  24       8   device (VkDevice)
     *  32       8   instance (VkInstance)
     *  40       8   physicalDevice (VkPhysicalDevice)
     *  48       4   computeQueueIndex (uint32)
     *  52       4   computeQueueFamily (uint32)
     *  56       4   graphicsQueueIndex (uint32)
     *  60       4   graphicsQueueFamily (uint32)
     *  64       4   opticalFlowQueueIndex (uint32)
     *  68       4   opticalFlowQueueFamily (uint32)
     *  72       1   useNativeOpticalFlowMode (bool)
     *  73       3   padding
     *  76       4   computeQueueCreateFlags (uint32)
     *  80       4   graphicsQueueCreateFlags (uint32)
     *  84       4   opticalFlowQueueCreateFlags (uint32)
     * </pre>
     * <p>
     * 此方法在 Streamline 初始化后、特性评估前调用。
     * 必须确保 {@link #setVulkanInfo} 已先被调用以设置 Vulkan 句柄。
     *
     * @return true 如果 slSetVulkanInfo 调用成功
     */
    public boolean registerVulkanInfo() {
        // 确保桥接器已初始化
        if (!initialized.get()) {
            LOGGER.severe("VulkanStreamlineBridge not initialized - call initialize() first");
            return false;
        }

        long devHandle = deviceHandle.get();
        long instHandle = instanceHandle.get();
        long physDevHandle = physicalDeviceHandle.get();

        // 验证 Vulkan 句柄已通过 setVulkanInfo 设置
        if (devHandle == 0L || instHandle == 0L || physDevHandle == 0L) {
            LOGGER.warning("Vulkan handles not set - call setVulkanInfo() first");
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {
            // sl::VulkanInfo 结构体总大小 88 字节（含 BaseStructure 和对齐填充）
            MemorySegment vkInfo = arena.allocate(VULKAN_INFO_SIZE);

            // 设置 structVersion = 3（kStructVersion3），位于 BaseStructure 之后
            vkInfo.set(ValueLayout.JAVA_INT, 16, K_STRUCT_VERSION_VULKAN_INFO);

            // 设置 Vulkan 设备句柄（VkDevice, 8 字节）
            vkInfo.set(ValueLayout.JAVA_LONG, 24, devHandle);
            // 设置 Vulkan 实例句柄（VkInstance, 8 字节）
            vkInfo.set(ValueLayout.JAVA_LONG, 32, instHandle);
            // 设置 Vulkan 物理设备句柄（VkPhysicalDevice, 8 字节）
            vkInfo.set(ValueLayout.JAVA_LONG, 40, physDevHandle);

            // 队列索引默认 0 —— 若需要特定队列可扩展 setVulkanInfo 存储后设置
            // computeQueueIndex @48: 默认 0
            // computeQueueFamily @52: 默认 0
            // graphicsQueueIndex @56: 默认 0
            // graphicsQueueFamily @60: 默认 0

            int result = SLFFMBindings.slSetVulkanInfo(vkInfo);
            boolean ok = SLFFMBindings.isOk(result);
            if (ok) {
                LOGGER.info("VulkanStreamlineBridge: slSetVulkanInfo 成功 (device=0x"
                    + Long.toHexString(devHandle) + ")");
            } else {
                LOGGER.warning("VulkanStreamlineBridge: slSetVulkanInfo 失败: "
                    + SLFFMBindings.getResultDescription(result));
            }
            return ok;
        } catch (SLFFMBindings.SLException e) {
            LOGGER.severe("VulkanStreamlineBridge: slSetVulkanInfo 异常: " + e.getMessage());
            return false;
        }
    }

    public boolean tagResources(ResourceTagData[] resources) {
        if (resources == null || resources.length == 0) {
            LOGGER.fine("tagResources: empty resources, skipping");
            return true;
        }

        // 存根模式：Streamline SDK 不可用，记录警告并返回 false
        if (!available.get()) {
            LOGGER.warning("tagResources: Streamline SDK 不可用（存根模式），"
                + resources.length + " 个资源未被标记。DLSS/FSR/XeSS 功能将不会生效。");
            // 仍然缓存资源数据以备后续 SDK 可用时使用
            synchronized (this) {
                this.taggedResources = resources.clone();
                this.resourceCount = resources.length;
            }
            return false;
        }

        // 正常模式：缓存数据并通过 FrameEvaluator 调用 slSetTagForFrame
        synchronized (this) {
            this.taggedResources = resources.clone();
            this.resourceCount = resources.length;
        }
        LOGGER.fine("tagResources: cached " + resources.length
            + " resources for frame tagging via FrameEvaluator");
        return true;
    }

    public ResourceTagData[] getTaggedResources() {
        synchronized (this) {
            return taggedResources.clone();
        }
    }

    /**
     * 获取已标记的资源数量
     * <p>
     * 注意：在存根模式下（Streamline SDK 不可用），此值仅反映通过 tagResources()
     * 缓存的资源数，不代表实际已向 SDK 注册的资源。
     * 可通过 {@link #isAvailable()} 判断当前是否为存根模式。
     *
     * @return 已缓存的资源数量
     */
    public int getResourceCount() {
        return resourceCount;
    }

    /**
     * 检查是否处于存根模式（Streamline SDK 未加载）
     *
     * @return true 表示 Streamline DLL 不可用，所有操作均为模拟/缓存
     */
    public boolean isStubMode() {
        return !available.get();
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
