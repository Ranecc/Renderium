// Renderium - Streamline Context Manager
// 管理 Streamline SDK 的生命周期和状态

package com.ranecc.renderium.tech.streamline;

import com.ranecc.renderium.tech.streamline.ffm.SLFFMBindings;
import com.ranecc.renderium.tech.streamline.ffm.SLFFMBindings.SLException;

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
     * sl::Preferences 结构布局 (Streamline SDK 2.10.3)
     * <p>
     * 基于 sl_core_types.h 中的定义:
     * <pre>
     * struct Preferences {
     *     StructureType sType;                    // 16 bytes (GUID)
     *     uint32_t structVersion;                 // 4 bytes
     *     bool showConsole;                       // 1 byte
     *     LogLevel logLevel;                      // 4 bytes (enum)
     *     const wchar_t** pathsToPlugins;         // 8 bytes
     *     uint32_t numPathsToPlugins;             // 4 bytes
     *     const wchar_t* pathToLogsAndData;       // 8 bytes
     *     PFun_ResourceAllocateCallback* ...      // 8 bytes
     *     PFun_ResourceReleaseCallback* ...       // 8 bytes
     *     PFun_LogMessageCallback* ...            // 8 bytes
     *     PreferenceFlags flags;                  // 8 bytes (64-bit flag)
     *     const Feature* featuresToLoad;          // 8 bytes
     *     uint32_t numFeaturesToLoad;             // 4 bytes
     *     uint32_t applicationId;                 // 4 bytes
     *     EngineType engine;                      // 4 bytes
     *     const char* engineVersion;              // 8 bytes
     *     const char* projectId;                  // 8 bytes
     *     RenderAPI renderAPI;                    // 4 bytes
     * };
     * </pre>
     */
    private static final long PREFERENCES_SIZE = 132L;

    /** EngineType::eCustom */
    private static final int ENGINE_TYPE_CUSTOM = 2;
    /** RenderAPI::eVulkan */
    private static final int RENDER_API_VULKAN = 2;

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

            // sType (BaseStructure GUID) = 16 bytes, 留空（SDK 通过 structVersion 识别）
            // structVersion = 1 @ offset 16
            pref.set(ValueLayout.JAVA_INT, 16, 1);

            // 设置路径: pathsToPlugins = null, pathToLogsAndData = 插件路径
            if (pluginPath != null) {
                MemorySegment pathSeg = arena.allocateFrom(pluginPath);
                // pathToLogsAndData @ offset 48
                pref.set(ValueLayout.ADDRESS, 48, pathSeg);
            }

            // flags: 启用基于帧的资源标记
            long flags = SLFFMBindings.PREFERENCE_FLAG_USE_FRAME_BASED_RESOURCE_TAGGING;
            pref.set(ValueLayout.JAVA_LONG, 80, flags);

            // featuresToLoad = null, numFeaturesToLoad = 0（按需加载）
            pref.set(ValueLayout.ADDRESS, 88, MemorySegment.NULL);
            pref.set(ValueLayout.JAVA_INT, 96, 0);

            // applicationId = 0 (使用 engine + engineVersion 替代)
            pref.set(ValueLayout.JAVA_INT, 100, 0);

            // engine = eCustom
            pref.set(ValueLayout.JAVA_INT, 104, ENGINE_TYPE_CUSTOM);

            // engineVersion = engineId 字符串指针
            if (engineId != null) {
                MemorySegment engVerSeg = arena.allocateFrom(engineId);
                pref.set(ValueLayout.ADDRESS, 108, engVerSeg);
            }

            // projectId = null
            pref.set(ValueLayout.ADDRESS, 116, MemorySegment.NULL);

            // renderAPI = eVulkan
            pref.set(ValueLayout.JAVA_INT, 124, RENDER_API_VULKAN);

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

        if (bridge == null) {
            LOGGER.severe("Bridge 对象为 null");
            this.vulkanInfoSet = false;
            return false;
        }

        this.vulkanInfoSet = bridge.registerVulkanInfo();
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
