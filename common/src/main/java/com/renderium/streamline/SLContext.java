// Renderium - Streamline Context Manager
// 管理 Streamline SDK 的生命周期和状态

package com.renderium.streamline;

import com.renderium.streamline.ffm.SLFFMBindings;
import com.renderium.streamline.ffm.SLFFMBindings.SLException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Streamline 上下文管理器
 * <p>
 * 管理 Streamline SDK 的完整生命周期：
 * <ol>
 *   <li>加载 SDK（sl.interposer.dll）</li>
 *   <li>初始化（slInit）</li>
 *   <li>注册 Vulkan 信息（slSetVulkanInfo）</li>
 *   <li>查询特性支持（slIsFeatureSupported）</li>
 *   <li>解析特性函数（slGetFeatureFunction）</li>
 *   <li>关闭（slShutdown）</li>
 * </ol>
 * <p>
 * 使用方式：
 * <pre>
 * SLContext context = new SLContext();
 * context.load(libraryPath);
 * context.initialize(preferencesPath, logPath);
 * context.registerVulkanInfo(bridge);
 * context.detectFeatures();
 * // ... 使用特性 ...
 * context.shutdown();
 * </pre>
 *
 * @see SLFFMBindings
 * @see VulkanStreamlineBridge
 */
public final class SLContext {

    private static final Logger LOGGER = Logger.getLogger(SLContext.class.getName());

    /**
     * sl::Preferences 结构布局
     * <p>
     * 基于 sl_struct.h:
     * <pre>
     * struct Preferences {
     *     StructureType sType;                // 4 bytes
     *     const BaseStructure* next;          // 8 bytes
     *     uint64_t flags;                     // 8 bytes
     *     bool disableConsoleLogging;         // 1 byte
     *     bool logDeprecated;                 // 1 byte
     *     bool enableVerboseLogging;          // 1 byte
     *     char applicationId[256];            // 256 bytes
     *     char engineId[256];                 // 256 bytes
     *     uint32_t engineVersion;             // 4 bytes
     *     uint32_t applicationVersion;        // 4 bytes
     *     Path logPath;                       // 260 bytes (MAX_PATH)
     *     Path cachePath;                     // 260 bytes
     *     Path pluginPath;                    // 260 bytes
     *     Path rendererHookLibraryPath;       // 260 bytes
     *     uint32_t numDevicesToMask;          // 4 bytes
     *     uint32_t* devicesToMask;            // 8 bytes
     *     uint32_t numPluginsToLoad;          // 4 bytes
     *     Feature* pluginsToLoad;             // 8 bytes
     *     uint32_t numPluginsToSkip;          // 4 bytes
     *     Feature* pluginsToSkip;             // 8 bytes
     * };
     * </pre>
     */
    private static final long PREFERENCES_SIZE = 1348L;

    private boolean loaded = false;
    private boolean initialized = false;
    private boolean vulkanInfoSet = false;
    private final Set<Feature> supportedFeatures = EnumSet.noneOf(Feature.class);
    private final Set<Feature> loadedFeatures = EnumSet.noneOf(Feature.class);

    private VulkanStreamlineBridge bridge;

    /**
     * Streamline 支持的特性枚举
     */
    public enum Feature {
        DLSS(SLFFMBindings.FEATURE_DLSS, "DLSS Super Resolution"),
        NIS(SLFFMBindings.FEATURE_NIS, "NVIDIA Image Scaling"),
        REFLEX(SLFFMBindings.FEATURE_REFLEX, "NVIDIA Reflex"),
        PCL(SLFFMBindings.FEATURE_PCL, "PCL"),
        DEEP_DVC(SLFFMBindings.FEATURE_DEEP_DVC, "Deep DVC"),
        LATEWARP(SLFFMBindings.FEATURE_LATEWARP, "Latewarp"),
        DLSS_G(SLFFMBindings.FEATURE_DLSS_G, "DLSS Frame Generation"),
        DLSS_RR(SLFFMBindings.FEATURE_DLSS_RR, "DLSS Ray Reconstruction"),
        NVPERF(SLFFMBindings.FEATURE_NVPERF, "NVPerf"),
        DIRECT_SR(SLFFMBindings.FEATURE_DIRECT_SR, "DirectSR");

        public final int id;
        public final String description;

        Feature(int id, String description) {
            this.id = id;
            this.description = description;
        }
    }

    public SLContext() {}

    /**
     * 加载 Streamline SDK
     *
     * @param libraryPath sl.interposer.dll 的完整路径
     * @return 是否成功
     */
    public boolean load(String libraryPath) {
        if (loaded) return true;

        loaded = SLFFMBindings.load(libraryPath);
        if (!loaded) {
            LOGGER.severe("Failed to load Streamline SDK from: " + libraryPath);
        }
        return loaded;
    }

    /**
     * 初始化 Streamline SDK
     * <p>
     * 对应 slInit，必须在 load 之后调用。
     *
     * @param applicationId 应用标识（如 "Renderium"）
     * @param engineId      引擎标识（如 "Minecraft 26.2"）
     * @param logPath       日志路径
     * @param cachePath     缓存路径
     * @param pluginPath    插件路径（sl.dlss.dll 等所在目录）
     * @return 是否成功
     */
    public boolean initialize(String applicationId, String engineId,
                               String logPath, String cachePath, String pluginPath) {
        if (!loaded) {
            LOGGER.severe("Streamline SDK not loaded - call load() first");
            return false;
        }
        if (initialized) return true;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pref = arena.allocate(PREFERENCES_SIZE);

            // sType = SL_STRUCT_TYPE_PREFERENCES
            pref.set(ValueLayout.JAVA_INT, 0, 0x01);

            // next = nullptr
            pref.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);

            // flags - 使用帧资源标记
            long flags = SLFFMBindings.PREFERENCE_FLAG_USE_FRAME_BASED_RESOURCE_TAGGING;
            pref.set(ValueLayout.JAVA_LONG, 16, flags);

            // disableConsoleLogging = false
            pref.set(ValueLayout.JAVA_BOOLEAN, 24, false);

            // logDeprecated = false
            pref.set(ValueLayout.JAVA_BOOLEAN, 25, false);

            // enableVerboseLogging = false
            pref.set(ValueLayout.JAVA_BOOLEAN, 26, false);

            // applicationId (offset 27, 256 bytes)
            MemorySegment appIdSeg = pref.asSlice(27, 256);
            appIdSeg.copyFrom(arena.allocateFrom(applicationId));

            // engineId (offset 283, 256 bytes)
            MemorySegment engineIdSeg = pref.asSlice(283, 256);
            engineIdSeg.copyFrom(arena.allocateFrom(engineId));

            // applicationVersion (offset 539)
            pref.set(ValueLayout.JAVA_INT, 539, 1);

            // engineVersion (offset 543)
            pref.set(ValueLayout.JAVA_INT, 543, 262);

            // logPath (offset 547, 260 bytes)
            if (logPath != null) {
                MemorySegment logPathSeg = pref.asSlice(547, 260);
                logPathSeg.copyFrom(arena.allocateFrom(logPath));
            }

            // cachePath (offset 807, 260 bytes)
            if (cachePath != null) {
                MemorySegment cachePathSeg = pref.asSlice(807, 260);
                cachePathSeg.copyFrom(arena.allocateFrom(cachePath));
            }

            // pluginPath (offset 1067, 260 bytes)
            if (pluginPath != null) {
                MemorySegment pluginPathSeg = pref.asSlice(1067, 260);
                pluginPathSeg.copyFrom(arena.allocateFrom(pluginPath));
            }

            int result = SLFFMBindings.slInit(pref);
            if (!SLFFMBindings.isOk(result)) {
                LOGGER.severe("slInit failed: " + SLFFMBindings.getResultDescription(result));
                return false;
            }

            initialized = true;
            LOGGER.info("Streamline SDK initialized successfully");
            return true;
        } catch (SLException e) {
            LOGGER.severe("Streamline initialization error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 注册 Vulkan 信息
     *
     * @param bridge Vulkan-Streamline 桥接（Object 类型，待模块重构后恢复强类型）
     * @return 是否成功
     */
    public boolean registerVulkanInfo(VulkanStreamlineBridge bridge) {
        if (!initialized) {
            LOGGER.severe("Streamline not initialized");
            return false;
        }

        this.bridge = bridge;

        /**
         * 通过反射调用 bridge.registerVulkanInfo() 方法
         * <p>
         * 由于 bridge 类型为 Object（模块化解耦设计），需要使用反射调用。
         * 这种方式允许在运行时动态绑定不同的 VulkanStreamlineBridge 实现，
         * 避免编译时依赖特定实现类。
         * <p>
         * 性能说明：反射调用仅在初始化阶段执行一次，不影响运行时性能。
         *
         * @param bridge 桥接对象实例
         * @return 是否成功注册 Vulkan 信息
         */
        try {
            java.lang.reflect.Method method = bridge.getClass().getMethod("registerVulkanInfo");
            this.vulkanInfoSet = (Boolean) method.invoke(bridge);
        } catch (NoSuchMethodException e) {
            LOGGER.severe("Bridge 对象缺少 registerVulkanInfo() 方法: " + e.getMessage());
            this.vulkanInfoSet = false;
        } catch (IllegalAccessException e) {
            LOGGER.severe("无法访问 Bridge 的 registerVulkanInfo() 方法: " + e.getMessage());
            this.vulkanInfoSet = false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            LOGGER.severe("调用 Bridge.registerVulkanInfo() 时发生异常: " +
                    (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            this.vulkanInfoSet = false;
        }
        return vulkanInfoSet;
    }

    /**
     * 检测支持的特性
     * <p>
     * 遍历所有特性，检查 GPU 是否支持。
     *
     * @return 支持的特性集合
     */
    public Set<Feature> detectFeatures() {
        if (!initialized) {
            LOGGER.severe("Streamline not initialized");
            return supportedFeatures;
        }

        supportedFeatures.clear();

        for (Feature feature : Feature.values()) {
            int result = SLFFMBindings.slIsFeatureSupported(feature.id, MemorySegment.NULL);
            if (SLFFMBindings.isOk(result)) {
                supportedFeatures.add(feature);
                LOGGER.info("Feature supported: " + feature.description);
            }
        }

        LOGGER.info("Detected " + supportedFeatures.size() + " supported features");
        return supportedFeatures;
    }

    /**
     * 检查特性是否支持
     *
     * @param feature 特性
     * @return 是否支持
     */
    public boolean isFeatureSupported(Feature feature) {
        return supportedFeatures.contains(feature);
    }

    /**
     * 解析 DLSS 特性函数
     *
     * @return 是否成功
     */
    public boolean resolveDLSSFunctions() {
        if (!isFeatureSupported(Feature.DLSS)) {
            LOGGER.warning("DLSS not supported, skipping function resolution");
            return false;
        }

        boolean success = SLFFMBindings.resolveDLSSFunctions();
        if (success) {
            loadedFeatures.add(Feature.DLSS);
        }
        return success;
    }

    /**
     * 解析 DLSS-G 特性函数
     *
     * @return 是否成功
     */
    public boolean resolveDLSSGFunctions() {
        if (!isFeatureSupported(Feature.DLSS_G)) {
            LOGGER.warning("DLSS-G not supported, skipping function resolution");
            return false;
        }

        boolean success = SLFFMBindings.resolveDLSSGFunctions();
        if (success) {
            loadedFeatures.add(Feature.DLSS_G);
        }
        return success;
    }

    /**
     * 关闭 Streamline SDK
     */
    public void shutdown() {
        if (!initialized) return;

        int result = SLFFMBindings.slShutdown();
        if (!SLFFMBindings.isOk(result)) {
            LOGGER.warning("slShutdown failed: " + SLFFMBindings.getResultDescription(result));
        }

        initialized = false;
        vulkanInfoSet = false;
        supportedFeatures.clear();
        loadedFeatures.clear();
        LOGGER.info("Streamline SDK shut down");
    }

    /**
     * SDK 是否已加载
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * SDK 是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Vulkan 信息是否已注册
     */
    public boolean isVulkanInfoSet() {
        return vulkanInfoSet;
    }

    /**
     * 获取 Vulkan-Streamline 桥接
     *
     * @return 桥接对象（VulkanStreamlineBridge 类型）
     */
    public VulkanStreamlineBridge getBridge() {
        return bridge;
    }

    /**
     * 获取支持的特性集合
     */
    public Set<Feature> getSupportedFeatures() {
        return Set.copyOf(supportedFeatures);
    }

    /**
     * 获取已加载的特性集合
     */
    public Set<Feature> getLoadedFeatures() {
        return Set.copyOf(loadedFeatures);
    }
}
