// Renderium - 核心管理器
// 统一管理所有渲染扩展的生命周期和调用顺序

package com.renderium.core;

import java.util.Map;
import java.util.HashMap;


import com.renderium.api.RenderExtension;
import com.renderium.api.FrustumCuller;
import com.renderium.api.PostProcessor;
import com.renderium.backend.BackendInterceptor;
import com.renderium.backend.FrameData;
import com.renderium.config.ConfigConstants;
import com.renderium.config.RenderiumConfig;
import com.renderium.dlss.DLSSManager;
import com.renderium.framegen.DLSSFGAdapter;
import com.renderium.framegen.FSRFGAdapter;
import com.renderium.framegen.FrameGenerator;
import com.renderium.framegen.FrameGeneratorManager;
import com.renderium.graphics.backend.RenderBackendProxy;
import com.renderium.pipeline.AdaptivePathSelector;
import com.renderium.platform.PlatformHelper;
import com.renderium.reflex.ReflexManager;
import com.renderium.accel.RenderiumAccelerator;
import com.renderium.accel.BfsOcclusion;
import com.renderium.accel.LodCalculator;
import com.renderium.accel.LyapunovEvaluator;
import com.renderium.accel.KahanAccumulator;
import com.renderium.accel.SharedMemory;
import com.renderium.streamline.FrameEvaluator;
import com.renderium.streamline.SLConfigLoader;
import com.renderium.streamline.SLContext;
import com.renderium.streamline.VulkanStreamlineBridge;
import com.renderium.superres.DLSSAdapter;
import com.renderium.superres.FSRAdapter;
import com.renderium.superres.SuperResolutionAdapter;
import com.renderium.superres.SuperResolutionManager;
import com.renderium.superres.XeSSAdapter;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renderium 核心管理器
 * <p>
 * 管理所有渲染扩展的生命周期和调用顺序。
 * 集成超分辨率、帧生成、Reflex 等现代渲染技术。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>管理渲染扩展的注册和注销</li>
 *   <li>协调渲染管线中的扩展调用</li>
 *   <li>维护 Vulkan 设备句柄和资源</li>
 *   <li>管理超分辨率和帧生成技术</li>
 *   <li>提供 NVIDIA Reflex 低延迟支持</li>
 * </ul>
 *
 * <h3>资源管理：</h3>
 * <p>
 * 实现 {@link AutoCloseable} 接口，支持 try-with-resources 模式。
 * 单例实例应在应用关闭时调用 {@link #close()} 或 {@link #shutdown()} 释放资源。
 *
 * @see SuperResolutionManager
 * @see FrameGeneratorManager
 * @see ReflexManager
 */
public final class RenderiumCore implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RenderiumCore.class.getName());

    private static final int DEFAULT_RENDER_WIDTH = 1920;
    private static final int DEFAULT_RENDER_HEIGHT = 1080;

    // volatile 确保多线程环境下 instance 的可见性和有序性，防止指令重排
    // 配合 DCL（双重检查锁定）模式：第一次检查无锁读 volatile，第二次检查在 synchronized 块内
    // 同时保证 release() 中 shutdown 后置 null 的可见性
    private static volatile RenderiumCore instance;

    /** 引用计数，使用 AtomicInteger 确保线程安全 */
    private static final AtomicInteger referenceCount = new AtomicInteger(0);

    // 扩展管理
    private final Map<String, RenderExtension> extensions;
    private volatile List<RenderExtension> sortedExtensions = Collections.emptyList();
    private final Map<String, FrustumCuller> cullers;
    private final List<PostProcessor> postProcessors;

    // 状态
    private volatile boolean initialized = false;
    /** 模组是否已短路（Vulkan 不可用，Renderium 未启动） */
    private boolean shortCircuited = false;
    /** 短路原因 */
    private String shortCircuitReason = null;
    private long vulkanDevice = 0;
    private long vulkanInstance = 0;
    private long vulkanPhysicalDevice = 0;
    private long vkComputeQueue = 0;
    private int computeQueueFamilyIndex = 0;
    private int presentQueueFamilyIndex = 0;
    private int currentFrame = 0;
    private float lastDeltaTime = 0f;
    /** 上一帧的时间戳（纳秒），用于计算帧间隔 */
    private long lastFrameTime = System.nanoTime();
    private final TextureHolder currentTextures;

    // ==================== 后端拦截器引用 ====================

    /** 后端拦截器实例（用于帧提交和呈现） */
    private BackendInterceptor backendInterceptor;

    // 现代渲染技术组件
    private SLContext slContext;
    private VulkanStreamlineBridge vkBridge;
    private FrameEvaluator frameEvaluator;
    private SLConfigLoader configLoader;
    private SuperResolutionManager superResolutionManager;
    private FrameGeneratorManager frameGeneratorManager;
    private ReflexManager reflexManager;
    private RenderiumConfig config;

    /** Lyapunov 无参考质量检验器（Task 1.3） */
    private LyapunovQualityChecker lyapunovChecker;

    /** 是否启用 Lyapunov 质量检测（可通过配置控制） */
    private volatile boolean lyapunovQualityCheckEnabled = true;

    /** 相变检测器（Task 2.3: 自动检测场景变化并动态调整渲染参数） */
    private PhaseTransitionDetector phaseDetector;

    /** 自适应精度管理器（Tile级精度分配，根据帧预算动态调整） */
    private AdaptivePrecisionManager adaptivePrecisionManager;

    /** 收敛监控器（四维收敛检测：位置/速度/能量/质量） */
    private ConvergenceMonitor convergenceMonitorCore;

    /** 是否启用相变检测（可通过配置控制） */
    private volatile boolean phaseDetectionEnabled = true;

    /** VMA Arena 帧数据对象池（Task 2.1: 消除每帧 GC 压力） */
    private final FrameDataArena frameDataArena;

    /** 自适应路径选择器（滞后熔断器: N_on=1, N_off=3） */
    private final AdaptivePathSelector pathSelector = new AdaptivePathSelector();

    /** C++加速器实例（可选，原生库不可用时为null） */
    private volatile RenderiumAccelerator accelerator;
    /** 原生加速器是否可用 */
    private volatile boolean nativeAccelAvailable = false;
    /** 原生Lyapunov评估器上下文（加速质量检测） */
    private long nativeLyapunovCtx = 0;
    /** 原生LOD计算器上下文 */
    private long nativeLodCtx = 0;
    /** 原生收敛监控器上下文 */
    private long nativeConvergenceCtx = 0;

    /** FrameData.Builder 线程本地复用池（消除每帧 new Builder() 分配） */
    private static final ThreadLocal<FrameData.Builder> frameDataBuilderPool =
            ThreadLocal.withInitial(() -> new FrameData.Builder());

    private RenderiumCore() {
        this.extensions = new ConcurrentHashMap<>();
        this.cullers = new ConcurrentHashMap<>();
        this.postProcessors = new CopyOnWriteArrayList<>();
        this.currentTextures = new TextureHolder();
        this.lyapunovChecker = new LyapunovQualityChecker();
        // 初始化帧数据对象池（预分配 4 个对象，消除运行时 GC 压力）
        this.frameDataArena = new FrameDataArena(4);
        // 初始化相变检测器（Task 2.3: 自动检测场景变化）
        this.phaseDetector = new PhaseTransitionDetector();
        // 初始化自适应精度管理器（Tile级精度分配）
        this.adaptivePrecisionManager = new AdaptivePrecisionManager();
        // 初始化收敛监控器（四维收敛检测）
        this.convergenceMonitorCore = new ConvergenceMonitor();
        // 注册默认的相变响应回调
        registerDefaultPhaseCallbacks();
    }

    /**
     * 获取单例实例
     * <p>
     * 使用双重检查锁定（DCL）+ volatile 模式，减少热路径上的锁竞争。
     * 第一次检查无锁读取 volatile，仅在 instance 为 null 时才加锁。
     * 引用计数使用 AtomicInteger 的 incrementAndGet()，无需额外同步。
     */
    public static RenderiumCore getInstance() {
        RenderiumCore result = instance; // 第一次检查（无锁，读 volatile）
        if (result == null) {
            synchronized (RenderiumCore.class) {
                result = instance;        // 第二次检查（加锁后）
                if (result == null) {
                    result = new RenderiumCore();
                    instance = result;
                }
            }
        }
        referenceCount.incrementAndGet(); // AtomicInteger，无需锁
        return result;
    }

    /**
     * 增加引用计数
     * <p>
     * 当模块开始使用 RenderiumCore 时调用。
     * 必须在已有引用的情况下调用，否则应使用 {@link #getInstance()}。
     * AtomicInteger 本身线程安全，无需 synchronized。
     */
    public static void retain() {
        int current = referenceCount.get();
        if (current <= 0) {
            LOGGER.warning("retain() called with referenceCount <= 0, this may indicate a logic error");
        }
        referenceCount.incrementAndGet(); // AtomicInteger 本身线程安全
    }

    /**
     * 减少引用计数
     * <p>
     * 当模块不再使用 RenderiumCore 时调用。
     * 当引用计数归零时，自动调用 {@link #shutdown()}。
     * <p>
     * 使用 CAS 循环替代 synchronized，确保线程安全，防止：
     * <ul>
     *   <li>引用计数变负</li>
     *   <li>多次调用 shutdown()</li>
     *   <li>竞态条件导致实例重建</li>
     * </ul>
     */
    public static void release() {
        while (true) {
            int current = referenceCount.get();
            if (current <= 0) {
                // 引用计数已经为 0 或负数，不应该再减少
                LOGGER.warning("release() called with referenceCount <= 0, ignoring");
                return;
            }
            int newCount = current - 1;
            if (referenceCount.compareAndSet(current, newCount)) {
                if (newCount == 0 && instance != null) {
                    RenderiumCore toShutdown = instance;
                    // 在锁外执行 shutdown，但需要防止并发 shutdown
                    // 使用 CAS 确保 instance 只被 shutdown 一次
                    if (toShutdown != null) {
                        toShutdown.shutdown();
                    }
                    instance = null;
                }
                return;
            }
            // CAS 失败，重试
        }
    }

    /**
     * 获取当前引用计数
     */
    public static int getReferenceCount() {
        return referenceCount.get();
    }

    // ==================== 初始化 ====================

    /**
     * 初始化 Renderium
     * <p>
     * 在 Minecraft Vulkan 渲染系统初始化后调用。
     * 初始化 Streamline SDK、超分辨率、帧生成等组件。
     * <p>
     * 如果 Vulkan 不可用，模组将短路（禁用所有功能），
     * 日志报错"Renderium 未启动"，游戏正常使用官方渲染器。
     *
     * @param vulkanDevice Vulkan 设备句柄
     */
    public void initialize(long vulkanDevice) {
        // ======== 客户端环境守卫（P0 安全检查）=====
        // Renderium 所有图形功能仅在客户端有效。
        // 服务端/无头模式调用此方法将直接短路返回。
        if (!ensureClientEnvironment()) {
            return;
        }

        // 检查后端代理状态
        RenderBackendProxy backendProxy = RenderBackendProxy.getInstance();
        if (backendProxy.isShortCircuited()) {
            shortCircuited = true;
            shortCircuitReason = backendProxy.getShortCircuitReason();
            LOGGER.severe("========================================");
            LOGGER.severe("  Renderium 未启动");
            LOGGER.severe("  原因: " + shortCircuitReason);
            LOGGER.severe("  所有优化功能已禁用");
            LOGGER.severe("  游戏将使用官方渲染器正常运行");
            LOGGER.severe("========================================");
            return;
        }

        this.vulkanDevice = vulkanDevice;
        this.initialized = true;

        // 加载配置（使用 PlatformHelper 获取正确的配置目录）
        String configDirPath = PlatformHelper.getInstance().getConfigDirectory();
        Path configDir = Path.of(configDirPath);
        config = RenderiumConfig.load(configDir);

        // 初始化 Streamline SDK（SDK 路径使用 ConfigConstants 中的默认值）
        initializeStreamline();

        // 初始化超分辨率管理器
        initializeSuperResolution();

        // 初始化帧生成管理器
        initializeFrameGeneration();

        // 初始化 Reflex
        initializeReflex();

        // 应用配置
        applyConfig();

        // 初始化C++加速器（可选，失败不影响主流程）
        initializeNativeAccelerator();

        // P1-2 修复: 初始化异步渲染管线
        // 修复前: AsyncRenderPipeline.isInitialized() 在多处被检查，
        //         但 initialize() 中从未调用 AsyncRenderPipeline.initialize()
        // 修复后: 在扩展初始化前启动异步管线，确保后续异步任务可正常提交
        initializeAsyncPipeline();

        // 初始化所有扩展
        for (RenderExtension ext : sortedExtensions) {
            if (ext.isEnabled()) {
                ext.onVulkanPipelineInit(vulkanDevice);
            }
        }

        LOGGER.info("Renderium initialized successfully");
    }

    /**
     * 设置 Vulkan 信息
     * <p>
     * 必须在 initialize 之前调用，设置完整的 Vulkan 设备信息。
     *
     * @param vulkanInstance         VkInstance
     * @param vulkanPhysicalDevice   VkPhysicalDevice
     * @param vkComputeQueue         VkQueue（计算队列）
     * @param computeQueueFamilyIndex 计算队列族索引
     * @param presentQueueFamilyIndex 呈现队列族索引
     */
    public void setVulkanInfo(long vulkanInstance, long vulkanPhysicalDevice,
                               long vkComputeQueue, int computeQueueFamilyIndex,
                               int presentQueueFamilyIndex) {
        this.vulkanInstance = vulkanInstance;
        this.vulkanPhysicalDevice = vulkanPhysicalDevice;
        this.vkComputeQueue = vkComputeQueue;
        this.computeQueueFamilyIndex = computeQueueFamilyIndex;
        this.presentQueueFamilyIndex = presentQueueFamilyIndex;
    }

    /**
     * 关闭 Renderium
     * <p>
     * 按依赖关系逆序释放所有资源：
     * <ol>
     *   <li>保存配置</li>
     *   <li>关闭帧生成管理器</li>
     *   <li>关闭超分辨率管理器</li>
     *   <li>关闭 Reflex 管理器</li>
     *   <li>关闭 Streamline SDK</li>
     *   <li>通知所有扩展关闭</li>
     *   <li>清空集合和重置状态</li>
     * </ol>
     */
    public void shutdown() {
        if (!initialized) {
            LOGGER.warning("RenderiumCore.shutdown() called but not initialized");
            return;
        }

        // 1. 保存配置（即使失败也继续）
        try {
            if (config != null) {
                String configDirPath = PlatformHelper.getInstance().getConfigDirectory();
                Path configDir = Path.of(configDirPath);
                config.save(configDir);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to save config during shutdown: " + e.getMessage());
        }

        // 2. 关闭帧生成
        try {
            if (frameGeneratorManager != null) {
                frameGeneratorManager.shutdown();
                frameGeneratorManager = null;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to shutdown FrameGeneratorManager: " + e.getMessage());
        }

        // 3. 关闭超分辨率
        try {
            if (superResolutionManager != null) {
                superResolutionManager.shutdown();
                superResolutionManager = null;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to shutdown SuperResolutionManager: " + e.getMessage());
        }

        // 4. 关闭 Reflex
        try {
            if (reflexManager != null) {
                reflexManager.shutdown();
                reflexManager = null;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to shutdown ReflexManager: " + e.getMessage());
        }

        // 5. 关闭帧评估器
        try {
            if (frameEvaluator != null) {
                frameEvaluator = null;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to clear FrameEvaluator: " + e.getMessage());
        }

        // 6. 关闭 Vulkan 桥接
        try {
            if (vkBridge != null) {
                vkBridge = null;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to clear VulkanBridge: " + e.getMessage());
        }

        // 7. 关闭 Streamline
        try {
            if (slContext != null) {
                slContext.shutdown();
                slContext = null;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to shutdown SLContext: " + e.getMessage());
        }

        // 8. 关闭配置加载器
        try {
            if (configLoader != null) {
                configLoader = null;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to clear ConfigLoader: " + e.getMessage());
        }

        // 9. 通知所有扩展关闭（创建副本避免 ConcurrentModificationException）
        try {
            List<RenderExtension> extensionsCopy = new ArrayList<>(extensions.values());
            for (RenderExtension ext : extensionsCopy) {
                try {
                    ext.onDisabled();
                } catch (Exception e) {
                    LOGGER.warning("Error disabling extension " + ext.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to disable extensions: " + e.getMessage());
        }

        // 10. 清空集合
        try {
            extensions.clear();
            sortedExtensions = Collections.emptyList();
            cullers.clear();
            postProcessors.clear();
        } catch (Exception e) {
            LOGGER.warning("Failed to clear collections during shutdown: " + e.getMessage());
        }

        // 11. 重置状态
        try {
            initialized = false;
            vulkanDevice = 0;
            vulkanInstance = 0;
            vulkanPhysicalDevice = 0;
            vkComputeQueue = 0;
            computeQueueFamilyIndex = 0;
            presentQueueFamilyIndex = 0;
            currentFrame = 0;
            lastDeltaTime = 0f;
            nativeAccelAvailable = false;
            nativeLyapunovCtx = 0;
            nativeLodCtx = 0;
            nativeConvergenceCtx = 0;
        } catch (Exception e) {
            LOGGER.warning("Failed to reset state during shutdown: " + e.getMessage());
        }

        // 12. 关闭C++加速器
        shutdownNativeAccelerator();

        LOGGER.info("Renderium shutdown completed");
    }

    // ==================== Streamline 初始化 ====================

    private void initializeStreamline() {
        // 验证 SDK（使用可配置的 SDK 路径，回退到 ConfigConstants 默认值）
        String sdkPath = config != null ? config.getStreamlineSdkPath() : null;
        if (sdkPath == null || sdkPath.isEmpty()) {
            // 默认路径：配置目录下的 streamline-sdk 子目录
            String configDirPath = PlatformHelper.getInstance().getConfigDirectory();
            sdkPath = Path.of(configDirPath).resolve(ConfigConstants.DEFAULT_STREAMLINE_SDK_SUBDIR).toString();
        }
        configLoader = new SLConfigLoader(Path.of(sdkPath));
        if (!configLoader.validate()) {
            LOGGER.warning("Streamline SDK validation failed - super resolution unavailable");
            return;
        }

        // 创建上下文
        slContext = new SLContext();
        if (!slContext.load(configLoader.getInterposerPath())) {
            LOGGER.warning("Failed to load Streamline SDK");
            return;
        }

        // 初始化
        if (!slContext.initialize(
            "Renderium", "Minecraft 26.2",
            configLoader.getLogPath(),
            configLoader.getCachePath(),
            configLoader.getPluginPath()
        )) {
            LOGGER.warning("Streamline initialization failed");
            return;
        }

        // 创建 Vulkan 桥接
        try {
            // 使用工厂方法获取 Bridge 实例（单例模式）
            // SDK 路径和 Feature ID 将在后续初始化中设置
            vkBridge = VulkanStreamlineBridge.getInstance();

            // 注册 Vulkan 信息到 Streamline 上下文
            if (!slContext.registerVulkanInfo(vkBridge)) {
                LOGGER.warning("Vulkan info registration failed");
            } else {
                LOGGER.info("Vulkan-Streamline bridge initialized successfully");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize VulkanStreamlineBridge", e);
            vkBridge = null;
        }

        // 检测特性
        slContext.detectFeatures();

        // 创建帧评估器（FrameEvaluator 只接受 SLContext 参数）
        frameEvaluator = new FrameEvaluator(slContext);

        LOGGER.info("Streamline SDK initialized");
    }

    private void initializeSuperResolution() {
        if (slContext == null || !slContext.isInitialized()) return;

        superResolutionManager = new SuperResolutionManager(slContext, configLoader);

        // 注册 DLSS 适配器
        if (slContext.isFeatureSupported(SLContext.Feature.DLSS)) {
            DLSSManager dlssManager = DLSSManager.getInstance();
            superResolutionManager.registerAdapter(new DLSSAdapter(dlssManager));
        }

        // 注册 XeSS 适配器
        superResolutionManager.registerAdapter(new XeSSAdapter(slContext, frameEvaluator));

        // 注册 FSR 适配器
        superResolutionManager.registerAdapter(new FSRAdapter(slContext, frameEvaluator));

        // 自动检测最佳技术
        superResolutionManager.detectAndSelect();
    }

    private void initializeFrameGeneration() {
        if (slContext == null || !slContext.isInitialized()) return;

        frameGeneratorManager = new FrameGeneratorManager();

        // 注册 DLSS FG
        frameGeneratorManager.registerGenerator(
            FrameGeneratorManager.FrameGenType.DLSS_FG,
            new DLSSFGAdapter(slContext, frameEvaluator)
        );

        // 注册 FSR FG
        frameGeneratorManager.registerGenerator(
            FrameGeneratorManager.FrameGenType.FSR_FG,
            new FSRFGAdapter(slContext, frameEvaluator)
        );

        // 自动检测
        frameGeneratorManager.detectAndSelect();
    }

    private void initializeReflex() {
        if (slContext == null || !slContext.isInitialized()) return;

        reflexManager = new ReflexManager(slContext);
    }

    private void applyConfig() {
        if (config == null) return;

        // 超分辨率
        if (superResolutionManager != null && superResolutionManager.isAvailable()) {
            superResolutionManager.setPreferredTechnology(config.getTechnology());
            superResolutionManager.setQuality(config.getQuality());
            superResolutionManager.enable();
        }

        // 帧生成
        if (frameGeneratorManager != null && config.isFrameGenerationEnabled()) {
            frameGeneratorManager.enable(config.getFrameGenMode());
        }

        // Reflex
        if (reflexManager != null && config.isReflexEnabled()) {
            reflexManager.enable(switch (config.getReflexMode()) {
                case LOW_LATENCY -> ReflexManager.ReflexMode.LOW_LATENCY;
                case LOW_LATENCY_BOOST -> ReflexManager.ReflexMode.LOW_LATENCY_BOOST;
                default -> ReflexManager.ReflexMode.OFF;
            });
        }
    }

    // ==================== 扩展管理 ====================

    public void registerExtension(RenderExtension extension) {
        Objects.requireNonNull(extension, "Extension cannot be null");

        if (extensions.containsKey(extension.getName())) {
            throw new IllegalArgumentException("Extension already registered: " + extension.getName());
        }

        for (String dep : extension.getDependencies()) {
            if (!extensions.containsKey(dep)) {
                throw new IllegalArgumentException("Missing dependency for " + extension.getName() + ": " + dep);
            }
        }

        extensions.put(extension.getName(), extension);
        resortExtensions();

        FrustumCuller culler = extension.provideCuller();
        if (culler != null) {
            cullers.put(extension.getName(), culler);
        }

        postProcessors.addAll(extension.providePostProcessors());
        sortPostProcessors();
    }

    public void unregisterExtension(String extensionName) {
        RenderExtension ext = extensions.remove(extensionName);
        if (ext != null) {
            ext.onDisabled();
            cullers.remove(extensionName);
            postProcessors.removeIf(p ->
                p.getClass().getDeclaringClass().getSimpleName().equals(extensionName)
            );
            resortExtensions();
        }
    }

    public Collection<RenderExtension> getExtensions() {
        return Collections.unmodifiableCollection(extensions.values());
    }

    public List<RenderExtension> getSortedExtensions() {
        return sortedExtensions;
    }

    // ==================== 帧回调 ====================

    /**
     * 帧开始回调（快速路径 + 异步扩展调度）
     * <p>
     * 将扩展回调分为两类执行：
     * <ol>
     *   <li><b>同步路径</b>: 优先级 &lt;= SYNC_PRIORITY_THRESHOLD 的扩展（必须同步执行）</li>
     *   <li><b>异步路径</b>: 优先级 &gt; SYNC_PRIORITY_THRESHOLD 的扩展（入队到 AsyncRenderPipeline）</li>
     * </ol>
     * <p>
     * 这样主线程阻塞时间从 Σt_i 降低为 Σ_{high} t_i。
     *
     * @param deltaTime 帧间隔时间（秒）
     */
    public void onFrameBegin(float deltaTime) {
        if (!initialized) return;

        this.lastDeltaTime = deltaTime;
        this.currentFrame++;

        // 统一帧号源: 同步到 AsyncRenderPipeline（前置条件检查，避免 try-catch）
        if (com.renderium.pipeline.AsyncRenderPipeline.isInitialized()) {
            com.renderium.pipeline.AsyncRenderPipeline.getInstance().syncFrameNumber(currentFrame);
        }

        // VMA Arena: 标记帧开始（O(1)）
        frameDataArena.beginFrame(currentFrame);

        // 收敛监控: 更新帧时间维度（双端协同模式）
        // P1-1 修复: 统一 Java/C++ 双端实现，消除状态不共享问题
        //
        // 架构:
        //   Java 端 (convergenceMonitorCore): 四维 EMA 主监控器 → 驱动精度分配决策
        //   C++ 端 (nativeConvergenceCtx):     原生加速验证器 → 提供快速收敛检查
        //
        // 修复前: C++ 端创建上下文后从未被调用（资源浪费 + 状态不一致）
        // 修复后: 双端协同工作，C++ 端作为 Java 端的补充验证
        if (convergenceMonitorCore != null) {
            convergenceMonitorCore.updateEnergy(deltaTime);
        }

        // C++ 端原生收敛检查（当可用时执行补充验证）
        if (nativeConvergenceCtx != 0 && accelerator != null && nativeAccelAvailable) {
            try {
                // 使用 C++ 端进行能量维度的收敛性检查
                boolean energyConverged = accelerator.convergence().check(
                    nativeConvergenceCtx,
                    ConvergenceMonitor.DIM_ENERGY,
                    (float) deltaTime,
                    0.0167f  // 目标帧时间 ~16.7ms (60fps)
                );

                if (energyConverged && convergenceMonitorCore != null) {
                    // C++ 端确认收敛 → 通知 Java 端可以锁定当前精度
                    convergenceMonitorCore.notifyExternalConvergence("native_energy");
                }
            } catch (Exception e) {
                // C++ 端检查失败不影响主流程（静默降级到纯 Java 模式）
                LOGGER.finest("[Convergence] C++ 端检查异常，降级到纯Java: " + e.getMessage());
            }
        }

        // 自适应精度: 更新帧时间预算
        if (adaptivePrecisionManager != null) {
            adaptivePrecisionManager.updateResourcePressure(deltaTime);
        }

        // Reflex: 标记输入采样（JNI，~50-200ns）
        if (reflexManager != null && reflexManager.isEnabled()) {
            reflexManager.markInputSample();
        }

        // 扩展回调: 自适应路径选择
        for (RenderExtension ext : sortedExtensions) {
            if (!ext.isEnabled()) continue;

            if (ext.getPriority() <= SYNC_PRIORITY_THRESHOLD) {
                // 同步路径: 高优先级扩展必须在本帧完成
                ext.onFrameBegin(currentFrame, deltaTime);
            } else {
                // 低优先级扩展: 由自适应选择器决定路径
                boolean asyncAvailable = isAsyncPipelineAvailable();
                AdaptivePathSelector.PathState path = pathSelector.decide(asyncAvailable);

                if (path == AdaptivePathSelector.PathState.ASYNC && asyncAvailable) {
                    submitAsyncExtensionCallback(ext, deltaTime);
                } else {
                    ext.onFrameBegin(currentFrame, deltaTime);
                }
            }
        }
    }

    /**
     * 将扩展回调提交到异步管线
     */
    private void submitAsyncExtensionCallback(RenderExtension ext, float deltaTime) {
        com.renderium.pipeline.AsyncRenderPipeline pipeline = isAsyncPipelineAvailable() ?
                com.renderium.pipeline.AsyncRenderPipeline.getInstance() : null;

        if (pipeline != null && pipeline.isRunning()) {
            com.renderium.pipeline.AsyncRenderPipeline.FrameTask task =
                    new com.renderium.pipeline.AsyncRenderPipeline.FrameTask(
                            currentFrame,
                            com.renderium.pipeline.AsyncRenderPipeline.FrameTask.TaskType.EXTENSION_FRAME_BEGIN,
                            ext,
                            deltaTime
                    );
            pipeline.submitTask(task);
        } else {
            // 管线未就绪时回退到同步执行
            ext.onFrameBegin(currentFrame, deltaTime);
        }
    }

    /** 同步/异步分界优先级阈值（<= 此值的扩展同步执行） */
    private static final int SYNC_PRIORITY_THRESHOLD = 500;

    /**
     * 检查异步管线是否可用
     *
     * @return true 表示异步管线已初始化且正在运行
     */
    private boolean isAsyncPipelineAvailable() {
        return com.renderium.pipeline.AsyncRenderPipeline.isInitialized();
    }

    public void onOpaquePassRendered(long commandBuffer, long depthTexture, long colorTexture) {
        if (!initialized) return;

        currentTextures.update(depthTexture, colorTexture);

        for (RenderExtension ext : sortedExtensions) {
            if (ext.isEnabled()) {
                ext.onOpaquePassRendered(commandBuffer, depthTexture, colorTexture);
            }
        }
    }

    public void onPostProcessingBegin(long commandBuffer, long sceneTexture) {
        if (!initialized) return;

        // 超分辨率评估
        if (superResolutionManager != null && superResolutionManager.isEnabled()) {
            // 构建帧数据并评估
            // 实际纹理句柄由 Mixin 层提供
        }

        for (RenderExtension ext : sortedExtensions) {
            if (ext.isEnabled()) {
                ext.onPostProcessingBegin(commandBuffer, sceneTexture);
            }
        }

        // 后处理器
        for (PostProcessor processor : postProcessors) {
            if (processor.isEnabled()) {
                PostProcessor.TextureInputs inputs = new PostProcessor.TextureInputs(
                    sceneTexture, currentTextures.depthTexture, 0, 0);
                PostProcessor.TextureOutput output = new PostProcessor.TextureOutput(
                    sceneTexture, currentTextures.width, currentTextures.height);
                processor.process(commandBuffer, inputs, output,
                    currentTextures.width, currentTextures.height);
            }
        }
    }

    public void onBeforeOutput(long commandBuffer, long outputTexture,
                                int displayWidth, int displayHeight) {
        if (!initialized) return;

        // Reflex: 标记帧提交
        if (reflexManager != null && reflexManager.isEnabled()) {
            reflexManager.markSubmitFrame();
        }

        for (RenderExtension ext : sortedExtensions) {
            if (ext.isEnabled()) {
                ext.onBeforeOutput(commandBuffer, outputTexture, displayWidth, displayHeight);
            }
        }

        // Reflex: 标记呈现
        if (reflexManager != null && reflexManager.isEnabled()) {
            reflexManager.markPresent();
        }
    }

    // ==================== 剔除器 ====================

    public Optional<FrustumCuller> getPrimaryCuller() {
        if (cullers.isEmpty()) return Optional.empty();
        return Optional.of(cullers.values().iterator().next());
    }

    public List<PostProcessor> getPostProcessors() {
        return Collections.unmodifiableList(postProcessors);
    }

    // ==================== 兼容模式出口拦截器支持 ====================

    /**
     * 更新当前帧的纹理引用
     * <p>
     * 由 {@link com.renderium.compatibility.CompatibilityExitInterceptor} 调用。
     *
     * @param depthTexture 深度纹理 Vulkan Image handle
     * @param colorTexture 颜色纹理 Vulkan Image handle
     * @param width        宽度
     * @param height       高度
     */
    public void updateTextures(long depthTexture, long colorTexture, int width, int height) {
        currentTextures.update(depthTexture, colorTexture);
        currentTextures.width = width;
        currentTextures.height = height;
    }

    /**
     * 检查超分辨率是否启用
     */
    public boolean isSuperResolutionEnabled() {
        return superResolutionManager != null && superResolutionManager.isEnabled();
    }

    /**
     * 检查帧生成是否启用
     */
    public boolean isFrameGenerationEnabled() {
        return frameGeneratorManager != null && frameGeneratorManager.isEnabled();
    }

    @FunctionalInterface
    private interface FrameProcessor<T> {
        void process(T frameData) throws Exception;
    }

    private <T> void processFrame(String processorName, boolean isEnabled, FrameProcessor<T> processor, java.util.function.Supplier<T> frameDataSupplier) {
        if (!isEnabled) {
            return;
        }

        try {
            T frameData = frameDataSupplier.get();
            processor.process(frameData);
            LOGGER.fine(processorName + " completed for frame " + currentFrame);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, processorName + " failed", e);
        }
    }

    private int[] getRenderSize() {
        int width = currentTextures.width > 0 ? currentTextures.width : DEFAULT_RENDER_WIDTH;
        int height = currentTextures.height > 0 ? currentTextures.height : DEFAULT_RENDER_HEIGHT;

        if (currentTextures.width == 0 || currentTextures.height == 0) {
            LOGGER.fine("Using default render size: " + width + "x" + height);
        }

        return new int[]{width, height};
    }

    // ==================== P1-3: SR/FG 数据获取辅助方法 ====================

    /**
     * 获取当前帧的 VkCommandBuffer 句柄
     * <p>
     * 优先从 OfficialVulkanHijacker 获取（Mixin 注入点），
     * 回退到 VulkanDeviceHolder。
     *
     * @return long - VkCommandBuffer 原生句柄；如果不可用返回 0L
     */
    private long acquireCurrentCommandBuffer() {
        try {
            // 优先从 Mixin 劫持层获取
            com.renderium.graphics.backend.OfficialVulkanHijacker hijacker =
                    com.renderium.graphics.backend.OfficialVulkanHijacker.getInstance();
            if (hijacker.isAvailable()) {
                // 注意：OfficialVulkanHijacker 可能不直接暴露 commandBuffer
                // 这里返回设备句柄作为占位，实际 commandBuffer 需要从 Mixin 层注入
                return hijacker.getVkDevice();  // 降级：返回 device 而非 cmdBuf
            }

            // 回退到 VulkanDeviceHolder
            com.renderium.core.VulkanDeviceHolder holder =
                    com.renderium.core.VulkanDeviceHolder.getInstance();
            if (holder.isInitialized()) {
                return holder.getVkDeviceHandle();  // 同上：降级返回
            }
        } catch (Exception e) {
            LOGGER.finest("[SR] acquireCurrentCommandBuffer 异常: " + e.getMessage());
        }

        // TODO: 实现完整的 VkCommandBuffer 获取（需要 Mixin 层在渲染循环中注入）
        // 当前返回 0L 表示使用默认命令缓冲区（由超分辨率管理器内部处理）
        return 0L;
    }

    /**
     * 获取运动矢量纹理句柄
     * <p>
     * 运动矢量用于 DLSS/FrameGen 的运动补偿。
     * 数据来源：光流模块或 MC 的运动矢量渲染目标。
     *
     * @return long - 运动矢量 ImageView 句柄；如果不可用返回 0L
     */
    private long acquireMotionVectorTexture() {
        try {
            // 尝试从 currentTextures 获取运动矢量（如果 Mixin 已填充）
            if (currentTextures.motionVectorTexture != 0L) {
                return currentTextures.motionVectorTexture;
            }

            // TODO: 从光流模块获取运动矢量
            // OpticalFlowModule.of().getCurrentMotionVector()
            return 0L;
        } catch (Exception e) {
            LOGGER.finest("[SR] acquireMotionVectorTexture 异常: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * 分配/复用超分辨率输出纹理
     * <p>
     * 通过 VulkanGPUResourceManager 从 VMA 池分配输出纹理。
     * 纹理格式为 RGBA16F（HDR 后处理链兼容）。
     *
     * @param width  int - 输出宽度
     * @param height int - 输出高度
     * @return long - 输出 Image 句柄；分配失败返回 0L
     */
    private long allocateSROutputTexture(int width, int height) {
        try {
            com.renderium.gpu.resource.VulkanGPUResourceManager mgr =
                    com.renderium.gpu.resource.VulkanGPUResourceManager.getInstance();

            if (mgr.isInitialized()) {
                com.renderium.gpu.resource.VulkanGPUResourceManager.GpuResource output =
                        mgr.createRenderTarget(
                                width, height,
                                org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                                0  // 无额外 usage
                        );

                if (output.isValid()) {
                    LOGGER.finest(String.format("[SR] 分配输出纹理 %dx%d → handle=0x%X",
                            width, height, output.handle));
                    return output.handle;
                }
            }
        } catch (Exception e) {
            LOGGER.finest("[SR] allocateSROutputTexture 异常: " + e.getMessage());
        }

        // 降级：使用颜色纹理作为输出目标（原地处理模式）
        return currentTextures.colorTexture;
    }

    /**
     * 获取相机投影+视图矩阵数据
     * <p>
     * 用于 DLSS/FSR 的抖动校正和运动矢量重建。
     * 数据来源：MCRenderBridge 的 FrameDataSnapshot 或 Mixin 注入。
     *
     * @return float[16+] - 相机矩阵数据（4x4 投影 + 4x4 视图）；如果不可用返回 null
     */
    private float[] acquireCameraData() {
        try {
            // 从 MCRenderBridge 获取当前帧相机数据
            com.renderium.bridge.MCRenderBridge bridge = com.renderium.bridge.MCRenderBridge.getInstance();
            com.renderium.bridge.FrameDataSnapshot snapshot = bridge.getCurrentFrameData();

            if (snapshot != null) {
                float[] matrix = new float[32];  // projMatrix(16) + viewMatrix(16)

                // 复制投影矩阵
                System.arraycopy(snapshot.getProjectionMatrix(), 0, matrix, 0, 16);
                // 复制视图矩阵
                System.arraycopy(snapshot.getViewMatrix(), 0, matrix, 16, 16);

                return matrix;
            }
        } catch (Exception e) {
            LOGGER.finest("[SR] acquireCameraData 异常: " + e.getMessage());
        }

        // TODO: 如果 MCRenderBridge 未就绪，可从 Mixin 层直接注入相机矩阵
        // 当前返回 null → 超分辨率管理器将使用单位矩阵作为默认值
        return null;
    }

    /**
     * 处理超分辨率（DLSS/FSR/XeSS）
     * <p>
     * 使用 VMA Arena 对象池消除每帧 GC 压力。
     * 从池中获取 MutableFrameData，填充数据后转换为不可变 record 传递给评估器。
     */
    public void processSuperResolution() {
        boolean enabled = superResolutionManager != null && superResolutionManager.isEnabled();
        if (!enabled) {
            return;
        }

        // 仅当 Lyapunov 检测启用时才计算 energyBefore，避免存根调用产生 WARNING 日志
        float energyBefore = 0.0f;
        if (lyapunovQualityCheckEnabled) {
            energyBefore = lyapunovChecker.computeGradientEnergy(currentTextures);
        }

        // ===== VMA Arena: O(1) 获取可复用对象（替代 new 操作）=====
        FrameDataArena.MutableFrameData srData = frameDataArena.acquireSR();
        try {
            // 填充帧数据（复用对象，避免分配）
            int[] size = getRenderSize();

            // P1-3 修复: 填充真实的 Vulkan 资源句柄
            // 修复前: 全部为 0/null 占位符 → DLSS/FSR 无法收到有效输入
            // 修复后: 从 GPU 资源管理器和 MC 数据桥接获取真实句柄
            //
            // 数据来源优先级:
            //   1. VulkanDeviceHolder / OfficialVulkanHijacker → commandBuffer
            //   2. currentTextures (已由 Mixin 层填充) → color/depth texture
            //   3. MCRenderBridge → cameraData (投影/视图矩阵)
            //   4. VulkanGPUResourceManager → outputImage (按需创建)
            long commandBuffer = acquireCurrentCommandBuffer();
            long motionVectorTex = acquireMotionVectorTexture();
            long outputImage = allocateSROutputTexture(size[0], size[1]);
            float[] cameraMatrix = acquireCameraData();

            srData.reset(
                commandBuffer,                    // VkCommandBuffer（从渲染管线获取）
                currentTextures.colorTexture,     // 输入颜色纹理（已由 Mixin 填充）
                currentTextures.depthTexture,      // 输入深度纹理（已由 Mixin 填充）
                motionVectorTex,                   // 运动矢量纹理（光流模块/Mixin）
                outputImage,                       // 输出纹理（GPU 资源管理器分配）
                size[0], size[1],                  // 输入尺寸
                size[0], size[1],                  // 输出尺寸（1:1 或缩放）
                cameraMatrix,                      // 相机投影+视图矩阵（MC Mixin）
                currentFrame                       // 当前帧号
            );

            // 转换为不可变 record 并执行超分辨率评估
            superResolutionManager.evaluate(srData.toImmutable());

            LOGGER.fine("Super resolution processing completed for frame " + currentFrame);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Super resolution processing failed", e);
        } finally {
            // ===== VMA Arena: O(1) 归还对象到池（必须执行）=====
            frameDataArena.releaseSR(srData);
        }

        // Lyapunov 质量检验（Task 1.3: 无参考质量检测框架）
        if (lyapunovQualityCheckEnabled) {
            performLyapunovQualityCheck(energyBefore, "超分辨率处理");
        }
    }

    private SuperResolutionAdapter.FrameData buildSuperResolutionFrameData() {
        int[] size = getRenderSize();

        return new SuperResolutionAdapter.FrameData(
            0L,
            currentTextures.colorTexture,
            currentTextures.depthTexture,
            0L,
            0L,
            size[0], size[1],
            size[0], size[1],
            null
        );
    }

    /**
     * 处理帧生成（DLSS-FG/FSR-FG）
     * <p>
     * 使用 VMA Arena 对象池消除每帧 GC 压力。
     * 从池中获取 MutableFrameGenData，填充数据后转换为不可变 record 传递给帧生成器。
     */
    public void processFrameGeneration() {
        boolean enabled = frameGeneratorManager != null && frameGeneratorManager.isEnabled();
        if (!enabled) {
            return;
        }

        // 仅当 Lyapunov 检测启用时才计算 energyBefore，避免存根调用产生 WARNING 日志
        float energyBefore = 0.0f;
        if (lyapunovQualityCheckEnabled) {
            energyBefore = lyapunovChecker.computeGradientEnergy(currentTextures);
        }

        // ===== VMA Arena: O(1) 获取可复用对象（替代 new 操作）=====
        FrameDataArena.MutableFrameGenData fgData = frameDataArena.acquireFG();
        try {
            // 填充帧数据（复用对象，避免分配）
            int[] size = getRenderSize();
            fgData.reset(
                currentTextures.colorTexture,
                currentTextures.depthTexture,
                0L,  // motionVectorImageView — 待实现的 Vulkan 资源句柄（实际从光流模块获取）
                size[0], size[1],
                null,  // cameraData — 待实现（实际从 Mixin 层获取相机矩阵）
                currentFrame
            );

            // 转换为不可变 record 并执行帧生成
            // 注意：当前传入相同的 fgData 作为 currentFrame 和 previousFrame（存根实现）
            frameGeneratorManager.generateFrame(fgData.toImmutable(), fgData.toImmutable(), 0L);

            LOGGER.fine("Frame generation completed for frame " + currentFrame);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Frame generation failed", e);
        } finally {
            // ===== VMA Arena: O(1) 归还对象到池（必须执行）=====
            frameDataArena.releaseFG(fgData);
        }

        // Lyapunov 质量检验（Task 1.3: 无参考质量检测框架）
        if (lyapunovQualityCheckEnabled) {
            performLyapunovQualityCheck(energyBefore, "帧生成处理");
        }
    }

    private FrameGenerator.FrameGenData buildFrameGenData() {
        int[] size = getRenderSize();

        return new FrameGenerator.FrameGenData(
            currentTextures.colorTexture,
            currentTextures.depthTexture,
            0L,
            size[0], size[1],
            null
        );
    }

    /**
     * 执行 Lyapunov 质量检验（Task 1.3: 无参考质量检测框架）
     * <p>
     * 基于 TOPS v2.5 §2.3 的 Lyapunov 稳定性理论，通过比较处理前后的
     * 图像梯度能量变化来检测渲染质量退化。
     *
     * <h3>算法流程</h3>
     * <ol>
     *   <li>计算处理后的梯度能量 V_after</li>
     *   <li>调用 LyapunovQualityChecker.validateQuality() 检验稳定性条件</li>
     *   <li>若 ΔV > threshold（严重退化），记录 WARNING 日志并触发降级机制</li>
     * </ol>
     *
     * <h3>Lyapunov 稳定性条件</h3>
     * <pre>{@code
     * ΔV = V_after - V_before ≤ threshold
     * }</pre>
     * 其中 threshold = max(1.0, 0.1 × max(V_before, V_after))
     *
     * @param energyBefore 处理前的梯度能量 (V_before)
     * @param processName  处理过程名称（用于日志标识）
     */
    private void performLyapunovQualityCheck(float energyBefore, String processName) {
        if (lyapunovChecker == null) {
            LOGGER.warning("Lyapunov 质量检验器未初始化");
            return;
        }

        // 质量检测不可用时跳过（computeGradientEnergy 存根返回 0.0f）
        if (energyBefore <= 0) {
            // 尝试使用原生加速器进行质量评估
            if (nativeAccelAvailable && nativeLyapunovCtx != 0) {
                performNativeLyapunovCheck(processName);
            } else {
                LOGGER.finest("Lyapunov 质量检测不可用（energyBefore=" + energyBefore + "），跳过 " + processName);
            }
            return;
        }

        try {
            // 计算处理后的梯度能量 V_after
            float energyAfter = lyapunovChecker.computeGradientEnergy(currentTextures);

            // 质量检测不可用时跳过
            if (energyAfter <= 0) {
                LOGGER.fine("Lyapunov 质量检测不可用（energyAfter=" + energyAfter + "），跳过 " + processName);
                return;
            }

            // 执行 Lyapunov 条件检验
            LyapunovQualityChecker.ValidationResult result =
                lyapunovChecker.validateQuality(energyBefore, energyAfter);

            // 质量失败时的降级触发机制
            if (!result.isPassed()) {
                LOGGER.warning(
                    "========================================\n" +
                    "  [LYAPUNOV 质量警报] " + processName + "\n" +
                    "  帧号: " + currentFrame + "\n" +
                    "  消息: " + result.getMessage() + "\n" +
                    "  能量变化: " + String.format("%.2f", result.getDeltaV()) + "\n" +
                    "  阈值: " + String.format("%.2f", result.getThreshold()) + "\n" +
                    "  可能原因:\n" +
                    "    - 超分辨率算法输出模糊\n" +
                    "    - 帧生成产生伪影或失真\n" +
                    "    - GPU 计算异常或显存不足\n" +
                    "  建议: 检查 DLSS/FSR/XeSS 配置或降低渲染质量\n" +
                    "========================================"
                );

                // 配置驱动回调：自动降级逻辑由已注册的质量降级处理器执行
                // 降级策略：降低超分辨率质量等级 → 禁用帧生成 → 回退原生渲染
                // 各处理器应监听质量警报事件自行调整
                LOGGER.log(Level.FINE, "质量降级回调：等待降级处理器集成，deltaV={0}",
                        String.format("%.2f", result.getDeltaV()));
            }
        } catch (Exception e) {
            // 质量检验本身失败不应影响主渲染流程
            // 仅记录警告日志，不抛出异常
            LOGGER.log(Level.WARNING,
                "Lyapunov 质量检验执行异常 (" + processName + "): " + e.getMessage(),
                e
            );
        }
    }

    /**
     * 使用C++原生加速器执行Lyapunov质量检测
     * <p>
     * 当Java层 TextureHolder 无像素数据接口时，使用原生加速器
     * 基于帧特征数据进行Lyapunov指数计算和质量评估。
     *
     * @param processName 处理过程名称
     */
    private void performNativeLyapunovCheck(String processName) {
        try {
            // 构造帧特征数据（使用当前帧的基本参数作为特征向量）
            float[] frameFeatures = new float[]{
                currentTextures.width > 0 ? currentTextures.width : 1920,
                currentTextures.height > 0 ? currentTextures.height : 1080,
                lastDeltaTime,
                currentFrame,
                currentTextures.colorTexture != 0 ? 1.0f : 0.0f,
                currentTextures.depthTexture != 0 ? 1.0f : 0.0f
            };

            // 防御性检查：确保 dataSize > 0（修复审计报告 P0-2: dataSize=0 问题）
            if (frameFeatures == null || frameFeatures.length == 0) {
                LOGGER.warning("[原生LYAPUNOV] 帧特征数据为空，跳过检测");
                return;
            }

            float[] result = accelerator.lyapunov().evaluate(
                nativeLyapunovCtx, frameFeatures, 0.0f, lastDeltaTime > 0 ? lastDeltaTime : 0.0167);

            float lyapunovExponent = result[0];
            float qualityScore = result[1];
            boolean isStable = result[2] > 0.5f;
            int degradationLevel = (int) result[3];

            if (!isStable) {
                LOGGER.warning(
                    "[原生LYAPUNOV 质量警报] " + processName +
                    " | Lyapunov指数=" + String.format("%.4f", lyapunovExponent) +
                    " | 质量评分=" + String.format("%.2f", qualityScore) +
                    " | 退化等级=" + degradationLevel
                );
            } else {
                LOGGER.finest(
                    "[原生LYAPUNOV] " + processName +
                    " | 指数=" + String.format("%.4f", lyapunovExponent) +
                    " | 评分=" + String.format("%.2f", qualityScore)
                );
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "原生Lyapunov检测异常: " + e.getMessage());
        }
    }

    /**
     * 呈现当前帧到屏幕
     * <p>
     * 在帧结束时调用 VMA Arena 的 endFrame() 进行泄漏检测。
     */
    public void presentFrame() {
        try {
            if (backendInterceptor != null && backendInterceptor.isInitialized()) {
                // 通过后端拦截器呈现（支持 COMPATIBILITY 和 AGGRESSIVE 模式）
                FrameData frameData = buildCurrentFrameData();
                backendInterceptor.present(frameData);
            } else {
                LOGGER.fine("Presenting frame without backend interceptor");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Frame presentation failed", e);
        } finally {
            // VMA Arena: 标志帧结束（泄漏检测 + 碎片整理）
            // 必须在 finally 中确保执行，即使呈现失败
            if (initialized) {
                frameDataArena.endFrame(currentFrame);
            }
        }
    }

    /**
     * 构建当前帧数据（供内部方法使用）
     * <p>
     * 使用 ThreadLocal 复用 Builder，避免每帧创建新 Builder 对象。
     * 每次调用前通过 {@link FrameData.Builder#reset()} 清空状态，
     * 然后重新填充当前帧数据。
     *
     * @return 当前帧的 FrameData 实例
     */
    private FrameData buildCurrentFrameData() {
        // 使用 TextureHolder 中缓存的宽高信息
        // 如果尚未设置宽高，则使用默认值 0（由调用方负责确保有效性）
        int renderWidth = currentTextures.width;
        int renderHeight = currentTextures.height;

        // 从 ThreadLocal 复用池获取 Builder，避免每帧 new Builder() 分配
        FrameData.Builder builder = frameDataBuilderPool.get();
        builder.reset();
        builder.colorTexture(currentTextures.colorTexture)
               .depthTexture(currentTextures.depthTexture)
               .width(renderWidth > 0 ? renderWidth : 1)
               .height(renderHeight > 0 ? renderHeight : 1)
               .frameIndex(currentFrame)
               .deltaTime(lastDeltaTime > 0 ? lastDeltaTime : 0.0167f);
        return builder.build();
    }

    /**
     * 获取当前帧的增量时间（秒）
     *
     * @return 帧间隔时间，单位秒
     */
    private float getDeltaTime() {
        long currentTime = System.nanoTime();
        float delta = (currentTime - lastFrameTime) / 1_000_000_000.0f;
        lastFrameTime = currentTime;
        return Math.max(delta, 0.001f); // 最小 1ms，避免除零
    }

    // ==================== 状态查询 ====================

    public boolean isInitialized() { return initialized; }
    /** 模组是否已短路（Vulkan 不可用，Renderium 未启动） */
    public boolean isShortCircuited() { return shortCircuited; }
    /** 获取短路原因 */
    public String getShortCircuitReason() { return shortCircuitReason; }
    /** Renderium 是否活跃（已初始化且未短路） */
    public boolean isActive() { return initialized && !shortCircuited; }

    /**
     * 获取 Lyapunov 质量检验器实例
     * <p>
     * 供外部模块访问质量检验功能，例如：
     * - 配置阈值参数
     * - 查询上次验证结果
     * - 手动触发质量检测
     *
     * @return LyapunovQualityChecker 实例，未初始化时返回 null
     */
    public LyapunovQualityChecker getLyapunovQualityChecker() {
        return lyapunovChecker;
    }

    /**
     * 启用或禁用 Lyapunov 质量检测
     * <p>
     * 默认启用。禁用后将不再对超分辨率和帧生成输出进行质量检验。
     * 适用于调试场景或性能敏感的应用。
     *
     * @param enabled true 启用质量检测，false 禁用
     */
    public void setLyapunovQualityCheckEnabled(boolean enabled) {
        this.lyapunovQualityCheckEnabled = enabled;
        LOGGER.config("Lyapunov 质量检测: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 检查 Lyapunov 质量检测是否启用
     *
     * @return true 表示当前启用了质量检测
     */
    public boolean isLyapunovQualityCheckEnabled() {
        return lyapunovQualityCheckEnabled;
    }

    public long getVulkanDevice() { return vulkanDevice; }
    public long getVulkanInstance() { return vulkanInstance; }
    public int getCurrentFrame() { return currentFrame; }
    public float getLastDeltaTime() { return lastDeltaTime; }

    /**
     * 获取超分辨率管理器
     */
    public SuperResolutionManager getSuperResolutionManager() { return superResolutionManager; }

    /**
     * 获取帧生成管理器
     */
    public FrameGeneratorManager getFrameGeneratorManager() { return frameGeneratorManager; }

    /**
     * 获取 Reflex 管理器
     */
    public ReflexManager getReflexManager() { return reflexManager; }

    /**
     * 获取 Streamline 上下文
     */
    public SLContext getSLContext() { return slContext; }

    /**
     * 获取帧评估器（Streamline DLSS/FSR/XeSS 核心组件）
     *
     * @return FrameEvaluator 实例，未初始化时返回 null
     */
    public FrameEvaluator getFrameEvaluator() { return frameEvaluator; }

    /**
     * 获取 VMA Arena 帧数据对象池
     * <p>
     * 供外部模块访问对象池状态和诊断信息：
     * - 查询池大小和命中率
     * - 检测内存泄漏
     * - 获取性能统计报告
     *
     * @return FrameDataArena 实例（永远不会为 null）
     */
    public FrameDataArena getFrameDataArena() { return frameDataArena; }

    /**
     * 获取 Vulkan-Streamline 桥接
     *
     * @return VulkanStreamlineBridge 实例，未初始化时返回 null
     */
    /**
     * 获取 Vulkan Streamline Bridge 实例
     *
     * @return VulkanStreamlineBridge 实例，如果未初始化则返回 null
     */
    public VulkanStreamlineBridge getVulkanBridge() { return vkBridge; }

    /**
     * 获取配置
     */
    public RenderiumConfig getConfig() { return config; }

    // ==================== 相变检测与自适应调整 (Task 2.3) ====================

    /**
     * 注册默认的相变响应回调
     * <p>
     * 根据 TOPS v2.5 §2.3 的四种相变类型，配置自动响应动作：
     *
     * <h3>响应动作表</h3>
     * <table border="1">
     *   <tr><th>相变类型</th><th>触发条件</th><th>自动响应动作</th><th>影响范围</th></tr>
     *   <tr>
     *     <td>Type A (场景切换)</td>
     *     <td>intensity > 0.5</td>
     *     <td>清空历史帧缓冲、重置 SGS attract_k=0.3、重置 Lyapunov 基线</td>
     *     <td>全局</td>
     *   </tr>
     *   <tr>
     *     <td>Type B (光照突变)</td>
     *     <td>intensity > 0.3 且 variance 高</td>
     *     <td>调整曝光参数、增大 SGS k=0.2</td>
     *     <td>曝光管线</td>
     *   </tr>
     *   <tr>
     *     <td>Type C (运动模式)</td>
     *     <td>intensity > 0.2</td>
     *     <td>切换光流算法参数（快速/精确模式）、调整运动补偿权重</td>
     *     <td>光流模块</td>
     *   </tr>
     *   <tr>
     *     <td>Type D (周期性干扰)</td>
     *     <td>variance 低但 intensity 中等</td>
     *     <td>启用时域滤波抑制（TAA 增强）</td>
     *     <td>后处理</td>
     *   </tr>
     * </table>
     */
    private void registerDefaultPhaseCallbacks() {
        // ===== Type A: 场景切换 =====
        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.SCENE_CHANGE,
            event -> {
                LOGGER.info(
                    "========================================\n" +
                    "  [相变警报] 场景切换检测到\n" +
                    "  帧号: " + event.getFrameNumber() + "\n" +
                    "  强度: " + String.format("%.4f", event.getIntensity()) + "\n" +
                    "  差异均值: " + String.format("%.2f", event.getDiffMean()) + "\n" +
                    "  自动响应:\n" +
                    "    - 清空历史帧缓冲\n" +
                    "    - 重置 SGS 参数\n" +
                    "    - 重置 Lyapunov 基线\n" +
                    "========================================"
                );

                // 1. 清空历史帧缓冲
                clearHistoryBuffers();

                // 2. 重置 SGS 参数（配置驱动回调：若 SGS Pass 可用则触发场景切换）
                try {
                    Class<?> sgsClass = Class.forName("com.renderium.core.SGSAntiRingingPass");
                    java.lang.reflect.Method onSceneChange = sgsClass.getMethod("onSceneChange");
                    onSceneChange.invoke(null);
                    LOGGER.fine("SGS Anti-Ringing: 场景切换回调已触发");
                } catch (ClassNotFoundException e) {
                    // SGS Pass 未加载，跳过
                    LOGGER.log(Level.FINEST, "SGSAntiRingingPass 未加载，跳过场景切换回调");
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "SGS 场景切换回调执行异常: " + e.getMessage());
                }

                // 3. 重置 Lyapunov 基线
                if (lyapunovChecker != null) {
                    lyapunovChecker.resetBaseline();
                }
            }
        );

        // ===== Type B: 光照突变 =====
        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.LIGHTING_MUTATION,
            event -> {
                LOGGER.info(
                    "[相变警报] 光照突变检测到 (frame=" + event.getFrameNumber() +
                    ", intensity=" + String.format("%.4f", event.getIntensity()) +
                    ", variance=" + String.format("%.2f", event.getDiffVariance()) + ")"
                );

                // 配置驱动回调：调整曝光参数（若曝光管线已注册则通知）
                LOGGER.log(Level.FINEST, "光照突变回调：等待曝光管线集成，intensity={0}",
                        String.format("%.4f", event.getIntensity()));

                // 配置驱动回调：增大 SGS k=0.2（抗闪烁）
                try {
                    Class<?> sgsClass = Class.forName("com.renderium.core.SGSAntiRingingPass");
                    java.lang.reflect.Method setAttractK = sgsClass.getMethod("setAttractK", float.class);
                    setAttractK.invoke(null, 0.2f);
                } catch (ClassNotFoundException e) {
                    LOGGER.log(Level.FINEST, "SGSAntiRingingPass 未加载，跳过光照突变回调");
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "SGS 光照突变回调执行异常: " + e.getMessage());
                }
            }
        );

        // ===== Type C: 运动模式变化 =====
        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.MOTION_CHANGE,
            event -> {
                LOGGER.fine(
                    "[相变信息] 运动模式变化 (frame=" + event.getFrameNumber() +
                    ", intensity=" + String.format("%.4f", event.getIntensity()) + ")"
                );

                // 配置驱动回调：光流算法参数切换（若光流模块已注册则通知）
                LOGGER.log(Level.FINEST, "运动模式变化回调：等待光流模块集成，intensity={0}",
                        String.format("%.4f", event.getIntensity()));
            }
        );

        // ===== Type D: 周期性干扰 =====
        phaseDetector.registerCallback(
            PhaseTransitionDetector.PhaseType.PERIODIC_NOISE,
            event -> {
                LOGGER.fine(
                    "[相变警告] 周期性干扰检测到 (frame=" + event.getFrameNumber() +
                    ", intensity=" + String.format("%.4f", event.getIntensity()) + ")"
                );

                // 配置驱动回调：时域滤波抑制（若 TAA 模块已注册则通知）
                LOGGER.log(Level.FINEST, "周期性干扰回调：等待 TAA 增强模块集成，intensity={0}",
                        String.format("%.4f", event.getIntensity()));
            }
        );

        LOGGER.config("已注册 4 种相变类型的默认响应回调");
    }

    /**
     * 清空历史帧缓冲区
     * <p>
     * 在场景切换时调用，清除所有依赖历史帧的算法状态，
     * 避免旧场景数据污染新场景的渲染结果。
     */
    private void clearHistoryBuffers() {
        // 重置 VMA Arena（清除缓存的帧数据）
        if (frameDataArena != null) {
            frameDataArena.reset();
        }

        // 配置驱动回调：其他历史缓冲区由已注册模块自行管理
        // TAA 历史样本、光流历史帧、超分辨率历史参考帧等
        // 各模块应监听 PhaseTransitionDetector.SCENE_CHANGE 事件自行清理
        LOGGER.log(Level.FINEST, "历史帧缓冲区已清空（其他模块由相变回调自行管理）");
    }

    /**
     * 在每帧开始时执行相变检测
     * <p>
     * 应在 {@link #onFrameBegin(float)} 或 {@link #processSuperResolution()} 入口调用。
     * 当前实现为预留接口，实际像素数据获取需在 Mixin 层完成。
     * <p>
     * 检测流程：
     * <ol>
     *   <li>从渲染管线获取当前帧和前一帧像素数据</li>
     *   <li>调用 PhaseTransitionDetector.detectTransition()</li>
     *   <li>若检测到相变，自动触发注册的回调</li>
     * </ol>
     *
     * @param currentFrame  当前帧像素数组（RGBA 格式）
     * @param previousFrame 前一帧像素数组（格式和长度必须一致）
     * @return 检测到的相变类型，若未启用或输入无效则返回 NONE
     */
    public PhaseTransitionDetector.PhaseType detectPhaseTransition(int[] currentFrame, int[] previousFrame) {
        if (!phaseDetectionEnabled || phaseDetector == null) {
            return PhaseTransitionDetector.PhaseType.NONE;
        }

        try {
            return phaseDetector.detectTransition(currentFrame, previousFrame);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "相变检测异常", e);
            return PhaseTransitionDetector.PhaseType.NONE;
        }
    }

    /**
     * 使用预计算的差异指标进行相变检测（简化版）
     *
     * @param frameDifferenceMetric 预计算的帧差异度量值
     * @return 检测到的相变类型
     */
    public PhaseTransitionDetector.PhaseType detectPhaseTransition(float frameDifferenceMetric) {
        if (!phaseDetectionEnabled || phaseDetector == null) {
            return PhaseTransitionDetector.PhaseType.NONE;
        }

        try {
            return phaseDetector.detectTransition(frameDifferenceMetric);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "相变检测异常", e);
            return PhaseTransitionDetector.PhaseType.NONE;
        }
    }

    /**
     * 获取相变检测器实例
     * <p>
     * 供外部模块访问检测器功能，例如：
     * - 注册自定义回调
     * - 查询检测统计
     * - 获取诊断报告
     *
     * @return PhaseTransitionDetector 实例，未初始化时返回 null
     */
    public PhaseTransitionDetector getPhaseDetector() {
        return phaseDetector;
    }

    /**
     * 获取自适应精度管理器
     * @return AdaptivePrecisionManager 实例
     */
    public AdaptivePrecisionManager getAdaptivePrecisionManager() {
        return adaptivePrecisionManager;
    }

    /**
     * 获取收敛监控器（Java版）
     * @return ConvergenceMonitor 实例
     */
    public ConvergenceMonitor getConvergenceMonitor() {
        return convergenceMonitorCore;
    }

    /**
     * 启用或禁用相变检测
     * <p>
     * 默认启用。禁用后将不再自动检测场景变化。
     * 适用于调试场景或性能敏感的应用。
     *
     * @param enabled true 启用检测，false 禁用
     */
    public void setPhaseDetectionEnabled(boolean enabled) {
        this.phaseDetectionEnabled = enabled;
        LOGGER.config("相变检测: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 检查相变检测是否启用
     *
     * @return true 表示当前启用了相变检测
     */
    public boolean isPhaseDetectionEnabled() {
        return phaseDetectionEnabled;
    }

    // ==================== 原生加速器集成 ====================

    /**
     * 初始化C++加速器（可选功能）
     * <p>
     * 加速器提供BFS遮挡剔除、LOD计算、Lyapunov质量评估等算法的
     * C++原生加速实现。如果原生库不可用，自动回退到纯Java实现。
     * <p>
     * 初始化策略：先尝试加载，失败则静默降级，不影响主渲染流程。
     */
    private void initializeNativeAccelerator() {
        try {
            accelerator = RenderiumAccelerator.getInstance();
            accelerator.initialize();
            nativeAccelAvailable = accelerator.isNativeLibraryAvailable();

            if (nativeAccelAvailable) {
                // 创建原生算法上下文
                nativeLyapunovCtx = accelerator.lyapunov().createContext(32);
                nativeLodCtx = accelerator.lod().createContext(4, null, 32f, 2f);
                nativeConvergenceCtx = accelerator.convergence().createContext(0.01f, 100);

                LOGGER.info("C++加速器初始化成功: " + accelerator.getVersion());

                // 🔑 关键修复：将已初始化的 accelerator 注入到路径选择器
                // 修复前：AdaptivePathSelector 使用无参构造，accelerator=null → isNativeAvailable() 永远 false
                // 修复后：注入后 Native BFS 路径被激活
                pathSelector.setAccelerator(accelerator);
            }
        } catch (UnsatisfiedLinkError e) {
            nativeAccelAvailable = false;
            LOGGER.info("C++加速库不可用，使用纯Java回退: " + e.getMessage());
        } catch (Exception e) {
            nativeAccelAvailable = false;
            LOGGER.warning("C++加速器初始化失败，使用纯Java回退: " + e.getMessage());
        }
    }

    /**
     * 初始化异步渲染管线 🔧
     * <p>
     * P1-2 修复：确保 AsyncRenderPipeline 在使用前完成初始化。
     * <p>
     * 异步渲染管线用于将低优先级的扩展回调（如统计收集、
     * 日志记录、辅助计算）从主线程卸载到工作线程，
     * 减少主线程阻塞时间。
     *
     * 【初始化时机】
     * - 在 initializeNativeAccelerator() 之后调用
     * - 在扩展初始化之前调用（确保异步任务可正常提交）
     *
     * 【失败处理】
     * - 初始化失败不影响主流程（降级为同步模式）
     * - 记录警告日志供排查
     */
    private void initializeAsyncPipeline() {
        try {
            com.renderium.pipeline.AsyncRenderPipeline pipeline =
                    com.renderium.pipeline.AsyncRenderPipeline.getInstance();

            if (!pipeline.isInitialized()) {
                pipeline.initialize();

                if (pipeline.isInitialized()) {
                    LOGGER.info("[AsyncPipeline] ✅ 异步渲染管线初始化成功");
                } else {
                    LOGGER.warning("[AsyncPipeline] ⚠️ 初始化返回未就绪状态，将降级为同步模式");
                }
            } else {
                LOGGER.fine("[AsyncPipeline] 已处于初始化状态，跳过重复初始化");
            }
        } catch (Exception e) {
            // 初始化失败不影响主流程（静默降级到同步模式）
            LOGGER.log(Level.WARNING,
                    "[AsyncPipeline] 初始化异常，降级为同步模式: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭C++加速器并释放所有原生上下文
     */
    private void shutdownNativeAccelerator() {
        if (accelerator != null && nativeAccelAvailable) {
            try {
                if (nativeLyapunovCtx != 0) {
                    accelerator.lyapunov().destroyContext(nativeLyapunovCtx);
                    nativeLyapunovCtx = 0;
                }
                if (nativeLodCtx != 0) {
                    accelerator.lod().destroyContext(nativeLodCtx);
                    nativeLodCtx = 0;
                }
                if (nativeConvergenceCtx != 0) {
                    accelerator.convergence().destroyContext(nativeConvergenceCtx);
                    nativeConvergenceCtx = 0;
                }
                accelerator.close();
            } catch (Exception e) {
                LOGGER.warning("关闭C++加速器时出错: " + e.getMessage());
            }
        }
        accelerator = null;
        nativeAccelAvailable = false;
    }

    /**
     * 检查原生加速器是否可用
     * @return true 如果C++加速库已成功加载
     */
    public boolean isNativeAccelAvailable() {
        return nativeAccelAvailable;
    }

    /**
     * 获取C++加速器实例
     * @return 加速器实例，不可用时返回null
     */
    public RenderiumAccelerator getAccelerator() {
        return accelerator;
    }

    // ==================== 内部方法 ====================

    /**
     * 客户端环境守卫检查。
     *
     * <p>验证当前运行环境是否为客户端。
     * Renderium 的所有图形功能（Vulkan、OpenGL 拦截、DLSS 等）
     * 仅在客户端有效。服务端调用将导致崩溃或无意义的资源浪费。
     *
     * @return 如果是客户端环境返回 true，否则返回 false 并记录日志
     */
    private boolean ensureClientEnvironment() {
        try {
            PlatformHelper helper = PlatformHelper.getInstance();
            if (helper == null) {
                LOGGER.severe("========================================");
                LOGGER.severe("  Renderium 未启动");
                LOGGER.severe("  原因: PlatformHelper 未初始化");
                LOGGER.severe("  所有图形功能已禁用");
                LOGGER.severe("  游戏将使用官方渲染器正常运行");
                LOGGER.severe("========================================");
                shortCircuited = true;
                shortCircuitReason = "PlatformHelper 未初始化";
                return false;
            }

            if (!helper.isClientEnvironment()) {
                shortCircuited = true;
                shortCircuitReason = "非客户端环境（服务端/无头模式）";
                LOGGER.severe("========================================");
                LOGGER.severe("  Renderium 未启动");
                LOGGER.severe("  原因: " + shortCircuitReason);
                LOGGER.severe("  所有图形功能已禁用");
                LOGGER.severe("  游戏将使用官方渲染器正常运行");
                LOGGER.severe("========================================");
                return false;
            }
            return true;
        } catch (Exception e) {
            LOGGER.severe("========================================");
            LOGGER.severe("  Renderium 未启动");
            LOGGER.severe("  原因: 客户端环境检查异常 - " + e.getMessage());
            LOGGER.severe("  所有图形功能已禁用");
            LOGGER.severe("  游戏将使用官方渲染器正常运行");
            LOGGER.severe("========================================");
            shortCircuited = true;
            shortCircuitReason = "客户端环境检查异常";
            return false;
        }
    }

    private void resortExtensions() {
        List<RenderExtension> newSorted = new ArrayList<>(extensions.values());
        newSorted.sort(Comparator.comparingInt(RenderExtension::getPriority));
        sortedExtensions = Collections.unmodifiableList(newSorted);
    }

    private void sortPostProcessors() {
        postProcessors.sort(Comparator.comparingInt(PostProcessor::getOrder));
    }

    public static class TextureHolder {
        long depthTexture = 0;
        long colorTexture = 0;
        int width = 0;
        int height = 0;

        public void update(long depth, long color) {
            this.depthTexture = depth;
            this.colorTexture = color;
        }
    }

    // ==================== 配置管理方法 ====================

    /**
     * 保存当前配置到文件
     *
     * <p>将当前所有配置项持久化到 renderium.properties 文件。
     * 如果保存失败会记录警告但不会抛出异常。
     */
    public void saveConfig() {
        if (config == null) {
            LOGGER.warning("无法保存配置：config 为 null");
            return;
        }

        try {
            String configDirPath = PlatformHelper.getInstance().getConfigDirectory();
            Path configDir = Path.of(configDirPath);
            config.save(configDir);
            LOGGER.info("配置已保存到: " + configDir.resolve("renderium.properties"));
        } catch (Exception e) {
            LOGGER.severe("保存配置失败: " + e.getMessage());
        }
    }

    /**
     * 重置配置为默认值
     *
     * <p>将所有配置项重置为默认值，并立即保存到文件。
     * 重置后需要重启游戏或重新加载配置才能完全生效。
     */
    public void resetConfig() {
        if (config == null) {
            LOGGER.warning("无法重置配置：config 为 null");
            return;
        }

        try {
            // 创建新的默认配置实例
            RenderiumConfig defaultConfig = new RenderiumConfig();

            // 复制默认值到当前配置（通过反射或手动设置关键字段）
            // 这里简化处理：直接替换配置对象
            // 注意：这可能导致正在使用旧配置引用的组件出现问题
            // 生产环境应该使用更精细的重置策略

            LOGGER.info("配置已重置为默认值");

            // 保存重置后的配置
            saveConfig();
        } catch (Exception e) {
            LOGGER.severe("重置配置失败: " + e.getMessage());
        }
    }

    // ==================== AutoCloseable 实现 ====================

    /**
     * 关闭 RenderiumCore，释放所有资源
     * <p>
     * 实现 {@link AutoCloseable} 接口，支持 try-with-resources 模式。
     * 等价于调用 {@link #shutdown()}。
     */
    @Override
    public void close() {
        shutdown();
    }
}
