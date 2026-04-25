// ============================================================
// RenderiumCore - 精简后的核心管理器
// ============================================================
// 重构版本：从 2054 行超级类拆分为 6 个职责组件
//
// 当前职责（仅保留核心协调）：
//   ✅ 单例管理（DCL + CAS 无锁化）
//   ✅ 扩展注册/注销与优先级排序
//   ✅ 帧回调分发（同步/异步路径选择）
//   ✅ 组件生命周期协调
//
// 已拆分到独立组件：
//   ❌ Streamline 初始化 → StreamlineInitializer
//   ❌ SR/FG/Reflex 管理 → ModernTechManager
//   ❌ C++加速器集成 → NativeAcceleratorIntegration
//   ❌ Lyapunov/相变检测 → QualityAssuranceManager
//   ❌ 帧数据处理 → FrameProcessor (待实现)
//
// @see com.renderium.core.component.StreamlineInitializer
// @see com.renderium.core.component.ModernTechManager
// @see com.renderium.core.component.NativeAcceleratorIntegration
// @see com.renderium.core.component.QualityAssuranceManager
// ============================================================

package com.renderium.core;

import com.renderium.api.RenderExtension;
import com.renderium.api.FrustumCuller;
import com.renderium.api.PostProcessor;
import com.renderium.backend.BackendInterceptor;
import com.renderium.backend.FrameData;
import com.renderium.config.RenderiumConfig;
import com.renderium.component.*;
import com.renderium.core.memory.FrameDataArena;
import com.renderium.core.phase.PhaseTransitionDetector;
import com.renderium.core.quality.LyapunovQualityChecker;
import com.renderium.core.precision.AdaptivePrecisionManager;
import com.renderium.core.quality.ConvergenceMonitor;
import com.renderium.pipeline.AdaptivePathSelector;
import com.renderium.platform.PlatformHelper;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renderium 核心管理器（重构版）
 * <p>
 * <b>设计原则变更</b>：
 * <ul>
 *   <li>重构前: 2054行超级类，承担 8+ 个职责</li>
 *   <li>重构后: ~400行核心协调器，仅负责扩展管理和帧分发</li>
 * </ul>
 *
 * <h2>当前保留的核心职责</h2>
 * <ol>
 *   <li>单例模式管理（DCL 双重检查锁定 + CAS 引用计数）</li>
 *   <li>渲染扩展注册/注销与优先级排序</li>
 *   <li>帧回调分发（同步/异步自适应路径）</li>
 *   <li>子组件生命周期协调</li>
 * </ol>
 *
 * <h2>已拆分的子组件</h2>
 * <table border="1">
 *   <tr><th>原职责</th><th>新组件类</th></tr>
 *   <tr><td>Streamline SDK 初始化</td><td>{@link StreamlineInitializer}</td></tr>
 *   <tr><td>SR/FG/Reflex 管理</td><td>{@link ModernTechManager}</td></tr>
 *   <tr><td>C++ 加速器集成</td><td>{@link NativeAcceleratorIntegration}</td></tr>
 *   <tr><td>质量保障系统</td><td>{@link QualityAssuranceManager}</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 获取单例
 * RenderiumCore core = RenderiumCore.getInstance();
 *
 * // 初始化（自动初始化所有子组件）
 * core.initialize(vulkanDevice);
 *
 * // 注册扩展
 * core.registerExtension(new MyExtension());
 *
 * // 帧循环中调用
 * core.onFrameBegin(deltaTime);
 * // ... 渲染 ...
 * core.onOpaquePassRendered(cmdBuf, depthTex, colorTex);
 * core.onPostProcessingBegin(cmdBuf, sceneTex);
 * core.onBeforeOutput(cmdBuf, outputTex, width, height);
 *
 * // 关闭时释放资源
 * RenderiumCore.release();
 * }</pre>
 *
 * @author Renderium Team
 * @version 6.0 (重构版)
 * @since 1.0 (原始版本), 6.0 (重大重构)
 */
public final class RenderiumCore implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RenderiumCore.class.getName());

    private static final int DEFAULT_RENDER_WIDTH = 1920;
    private static final int DEFAULT_RENDER_HEIGHT = 1080;

    // ==================== 单例管理 ====================

    /** volatile 确保多线程可见性（DCL 模式） */
    private static volatile RenderiumCore instance;

    /** 引用计数（CAS 无锁化） */
    private static final AtomicInteger referenceCount = new AtomicInteger(0);

    // ==================== 扩展管理 ====================

    /** 扩展注册表 */
    private final Map<String, RenderExtension> extensions;

    /** 按优先级排序的扩展列表（不可变快照） */
    private volatile List<RenderExtension> sortedExtensions = Collections.emptyList();

    /** 剔除器注册表 */
    private final Map<String, FrustumCuller> cullers;

    /** 后处理器列表 */
    private final List<PostProcessor> postProcessors;

    // ==================== 状态字段 ====================

    private volatile boolean initialized = false;
    private boolean shortCircuited = false;
    private String shortCircuitReason = null;
    private long vulkanDevice = 0;
    private int currentFrame = 0;
    private float lastDeltaTime = 0f;
    private final TextureHolder currentTextures;

    // ==================== 子组件引用 ====================

    /** Streamline 初始化器 */
    private StreamlineInitializer streamlineInit;

    /** 现代渲染技术管理器（SR/FG/Reflex） */
    private ModernTechManager techManager;

    /** C++ 加速器集成 */
    private NativeAcceleratorIntegration nativeAccel;

    /** 质量保障管理器（Lyapunov/相变/精度/收敛） */
    private QualityAssuranceManager qualityAssurance;

    /** 配置实例 */
    private RenderiumConfig config;

    /** 后端拦截器 */
    private BackendInterceptor backendInterceptor;

    /** VMA Arena 对象池 */
    private final FrameDataArena frameDataArena;

    /** 自适应路径选择器 */
    private final AdaptivePathSelector pathSelector = new AdaptivePathSelector();

    // ==================== 构造方法 ====================

    private RenderiumCore() {
        this.extensions = new ConcurrentHashMap<>();
        this.cullers = new ConcurrentHashMap<>();
        this.postProcessors = new CopyOnWriteArrayList<>();
        this.currentTextures = new TextureHolder();
        this.frameDataArena = new FrameDataArena(4);
        this.qualityAssurance = new QualityAssuranceManager();
    }

    // ==================== 单例 API ====================

    /**
     * 获取单例实例（DCL 双重检查锁定，线程安全）
     * <p>
     * 性能: uncontended 场景 ~20-40ns/调用
     *
     * @return RenderiumCore 单例，永不为 null
     */
    public static RenderiumCore getInstance() {
        RenderiumCore result = instance; // 第一次检查（无锁）
        if (result == null) {
            synchronized (RenderiumCore.class) {
                result = instance;        // 第二次检查（加锁）
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
     */
    public static void retain() {
        int current = referenceCount.get();
        if (current <= 0) {
            LOGGER.warning("retain() called with referenceCount <= 0");
        }
        referenceCount.incrementAndGet();
    }

    /**
     * 减少引用计数（归零时自动 shutdown）
     * <p>
     * 使用 CAS 自旋循环替代 synchronized
     */
    public static void release() {
        while (true) {
            int current = referenceCount.get();
            if (current <= 0) {
                LOGGER.warning("release() called with referenceCount <= 0, ignoring");
                return;
            }
            int newCount = current - 1;
            if (referenceCount.compareAndSet(current, newCount)) {
                if (newCount == 0 && instance != null) {
                    RenderiumCore toShutdown = instance;
                    if (toShutdown != null) {
                        toShutdown.shutdown();
                    }
                    instance = null;
                }
                return;
            }
        }
    }

    public static int getReferenceCount() {
        return referenceCount.get();
    }

    // ==================== 初始化流程 ====================

    /**
     * 初始化 Renderium（协调所有子组件）
     *
     * @param vulkanDevice Vulkan 设备句柄
     */
    public void initialize(long vulkanDevice) {
        if (!ensureClientEnvironment()) return;

        // 检查后端代理状态
        if (checkBackendShortCircuit()) return;

        this.vulkanDevice = vulkanDevice;
        this.initialized = true;

        // 加载配置
        loadConfig();

        // 初始化子组件（按依赖顺序）
        initializeStreamline();
        initializeModernTech();
        initializeNativeAccelerator();
        applyConfigToTech();

        // 初始化异步管线
        initializeAsyncPipeline();

        // 初始化所有扩展
        notifyExtensionsVulkanReady();

        LOGGER.info("Renderium initialized successfully (v6.0 refactored)");
    }

    /**
     * 关闭 Renderium（逆序释放所有资源）
     */
    public void shutdown() {
        if (!initialized) {
            LOGGER.warning("RenderiumCore.shutdown() called but not initialized");
            return;
        }

        // 保存配置
        saveConfigSafely();

        // 关闭子组件
        if (techManager != null) {
            techManager.shutdown();
            techManager = null;
        }

        if (streamlineInit != null) {
            streamlineInit.shutdown();
            streamlineInit = null;
        }

        if (nativeAccel != null) {
            nativeAccel.shutdown();
            nativeAccel = null;
        }

        // 通知所有扩展关闭
        disableAllExtensions();

        // 清空集合
        clearCollections();

        // 重置状态
        resetState();

        LOGGER.info("Renderium shutdown completed (v6.0 refactored)");
    }

    // ==================== 扩展管理 API ====================

    /**
     * 注册渲染扩展（自动按优先级排序）
     *
     * @param extension 扩展实例（不能为 null）
     * @throws IllegalArgumentException 如果扩展为 null 或名称重复
     */
    public void registerExtension(RenderExtension extension) {
        Objects.requireNonNull(extension, "Extension cannot be null");

        if (extensions.containsKey(extension.getName())) {
            throw new IllegalArgumentException("Extension already registered: " + extension.getName());
        }

        for (String dep : extension.getDependencies()) {
            if (!extensions.containsKey(dep)) {
                throw new IllegalArgumentException("Missing dependency: " + dep);
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

    /**
     * 注销渲染扩展
     *
     * @param extensionName 要注销的扩展名称
     * @return 是否成功注销
     */
    public boolean unregisterExtension(String extensionName) {
        RenderExtension ext = extensions.remove(extensionName);
        if (ext != null) {
            ext.onDisabled();
            cullers.remove(extensionName);
            postProcessors.removeIf(p ->
                p.getClass().getDeclaringClass().getSimpleName().equals(extensionName)
            );
            resortExtensions();
            return true;
        }
        return false;
    }

    public Collection<RenderExtension> getExtensions() {
        return Collections.unmodifiableCollection(extensions.values());
    }

    public List<RenderExtension> getSortedExtensions() {
        return sortedExtensions;
    }

    // ==================== 帧回调 API ====================

    /**
     * 帧开始回调（快速路径 + 异步调度）
     */
    public void onFrameBegin(float deltaTime) {
        if (!initialized) return;

        this.lastDeltaTime = deltaTime;
        this.currentFrame++;

        // 同步帧号到异步管线
        syncFrameNumberToPipeline();

        // 更新质量保障系统
        qualityAssurance.onFrameBegin(deltaTime);

        // Reflex: 输入采样
        markReflexInputSample();

        // 分发扩展回调（同步/异步自适应）
        dispatchExtensionFrameBegin(deltaTime);
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

        // 超分辨率评估（委托给 ModernTechManager）
        evaluateSuperResolutionIfNeeded();

        for (RenderExtension ext : sortedExtensions) {
            if (ext.isEnabled()) {
                ext.onPostProcessingBegin(commandBuffer, sceneTexture);
            }
        }

        // 后处理器链
        executePostProcessors(commandBuffer, sceneTexture);
    }

    public void onBeforeOutput(long commandBuffer, long outputTexture,
                                int displayWidth, int displayHeight) {
        if (!initialized) return;

        // Reflex: 帧提交和呈现
        markReflexSubmitAndPresent();

        for (RenderExtension ext : sortedExtensions) {
            if (ext.isEnabled()) {
                ext.onBeforeOutput(commandBuffer, outputTexture, displayWidth, displayHeight);
            }
        }
    }

    // ==================== 高级功能 API ====================

    /**
     * 处理超分辨率（DLSS/FSR/XeSS）
     * <p>
     * 委托给 ModernTechManager 和 QualityAssuranceManager
     */
    public void processSuperResolution() {
        if (techManager == null || !techManager.isSuperResolutionEnabled()) return;

        // Lyapunov 质量检验前能量采集
        float energyBefore = collectEnergyBeforeProcessing();

        // 执行超分辨率评估（TODO: 实现完整的帧数据处理）
        // techManager.getSuperResolutionManager().evaluate(frameData);

        // Lyapunov 质量检验
        qualityAssurance.performLyapunovQualityCheck(energyBefore, "超分辨率处理");
    }

    /**
     * 处理帧生成（DLSS-FG/FSR-FG）
     */
    public void processFrameGeneration() {
        if (techManager == null || !techManager.isFrameGenerationEnabled()) return;

        float energyBefore = collectEnergyBeforeProcessing();

        // 执行帧生成（TODO: 实现完整的帧数据处理）
        // techManager.getFrameGeneratorManager().generateFrame(...);

        qualityAssurance.performLyapunovQualityCheck(energyBefore, "帧生成处理");
    }

    /**
     * 相变检测便捷方法
     *
     * @param frameDifferenceMetric 帧差异度量值
     * @return 相变类型
     */
    public PhaseTransitionDetector.PhaseType detectPhaseTransition(float frameDifferenceMetric) {
        return qualityAssurance.detectPhaseTransition(frameDifferenceMetric);
    }

    // ==================== Getter 方法（保持向后兼容）====================

    public boolean isInitialized() { return initialized; }
    public boolean isShortCircuited() { return shortCircuited; }
    public String getShortCircuitReason() { return shortCircuitReason; }
    public boolean isActive() { return initialized && !shortCircuited; }
    public long getVulkanDevice() { return vulkanDevice; }
    public int getCurrentFrame() { return currentFrame; }
    public float getLastDeltaTime() { return lastDeltaTime; }

    public SuperResolutionManager getSuperResolutionManager() {
        return techManager != null ? techManager.getSuperResolutionManager() : null;
    }

    public FrameGeneratorManager getFrameGeneratorManager() {
        return techManager != null ? techManager.getFrameGeneratorManager() : null;
    }

    public ReflexManager getReflexManager() {
        return techManager != null ? techManager.getReflexManager() : null;
    }

    public SLContext getSLContext() {
        return streamlineInit != null ? streamlineInit.getSLContext() : null;
    }

    public VulkanStreamlineBridge getVulkanBridge() {
        return streamlineInit != null ? streamlineInit.getBridge() : null;
    }

    public FrameEvaluator getFrameEvaluator() {
        return streamlineInit != null ? streamlineInit.getFrameEvaluator() : null;
    }

    public FrameDataArena getFrameDataArena() { return frameDataArena; }
    public RenderiumConfig getConfig() { return config; }

    public LyapunovQualityChecker getLyapunovQualityChecker() {
        return qualityAssurance.getLyapunovChecker();
    }

    public PhaseTransitionDetector getPhaseDetector() {
        return qualityAssurance.getPhaseDetector();
    }

    public AdaptivePrecisionManager getAdaptivePrecisionManager() {
        return qualityAssurance.getAdaptivePrecisionManager();
    }

    public ConvergenceMonitor getConvergenceMonitor() {
        return qualityAssurance.getConvergenceMonitor();
    }

    public boolean isNativeAccelAvailable() {
        return nativeAccel != null && nativeAccel.isNativeAccelAvailable();
    }

    public RenderiumAccelerator getAccelerator() {
        return nativeAccel != null ? nativeAccel.getAccelerator() : null;
    }

    public Optional<FrustumCuller> getPrimaryCuller() {
        if (cullers.isEmpty()) return Optional.empty();
        return Optional.of(cullers.values().iterator().next());
    }

    public List<PostProcessor> getPostProcessors() {
        return Collections.unmodifiableList(postProcessors);
    }

    // ==================== 内部实现方法 ====================

    private boolean ensureClientEnvironment() {
        try {
            PlatformHelper helper = PlatformHelper.getInstance();
            if (helper == null || !helper.isClientEnvironment()) {
                shortCircuited = true;
                shortCircuitReason = helper == null ?
                    "PlatformHelper 未初始化" : "非客户端环境";
                logShortCircuit();
                return false;
            }
            return true;
        } catch (Exception e) {
            shortCircuited = true;
            shortCircuitReason = "客户端环境检查异常";
            logShortCircuit();
            return false;
        }
    }

    private boolean checkBackendShortCircuit() {
        // TODO: 实现后端短路检查
        return false;
    }

    private void loadConfig() {
        try {
            String configDirPath = PlatformHelper.getInstance().getConfigDirectory();
            Path configDir = Path.of(configDirPath);
            config = RenderiumConfig.load(configDir);
        } catch (Exception e) {
            LOGGER.warning("Failed to load config: " + e.getMessage());
        }
    }

    private void initializeStreamline() {
        streamlineInit = new StreamlineInitializer();
        StreamlineInitializationResult result = streamlineInit.initialize(null);
        if (!result.isSuccess()) {
            LOGGER.warning("Streamline initialization failed: " + result.getErrorMessage());
        }
    }

    private void initializeModernTech() {
        techManager = new ModernTechManager();

        SLContext slContext = streamlineInit != null ? streamlineInit.getSLContext() : null;
        SLConfigLoader configLoader = streamlineInit != null ? streamlineInit.getConfigLoader() : null;
        FrameEvaluator evaluator = streamlineInit != null ? streamlineInit.getFrameEvaluator() : null;

        techManager.initializeSuperResolution(slContext, configLoader);
        techManager.initializeFrameGeneration(slContext, evaluator);
        techManager.initializeReflex(slContext);
    }

    private void initializeNativeAccelerator() {
        nativeAccel = new NativeAcceleratorIntegration();
        nativeAccel.initialize();

        // 将加速器注入到路径选择器
        if (nativeAccel.isNativeAccelAvailable()) {
            pathSelector.setAccelerator(nativeAccel.getAccelerator());
        }
    }

    private void applyConfigToTech() {
        if (techManager != null && config != null) {
            techManager.applyConfig(config);
        }
    }

    private void initializeAsyncPipeline() {
        try {
            AsyncRenderPipeline pipeline = AsyncRenderPipeline.getInstance();
            if (!pipeline.isInitialized()) {
                pipeline.initialize();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AsyncPipeline] 初始化异常", e);
        }
    }

    private void notifyExtensionsVulkanReady() {
        for (RenderExtension ext : sortedExtensions) {
            if (ext.isEnabled()) {
                try {
                    ext.onVulkanPipelineInit(vulkanDevice);
                } catch (Exception e) {
                    LOGGER.warning("Extension init failed: " + ext.getName() + " - " + e.getMessage());
                }
            }
        }
    }

    private void saveConfigSafely() {
        try {
            if (config != null) {
                String configDirPath = PlatformHelper.getInstance().getConfigDirectory();
                config.save(Path.of(configDirPath));
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to save config: " + e.getMessage());
        }
    }

    private void disableAllExtensions() {
        List<RenderExtension> copy = new ArrayList<>(extensions.values());
        for (RenderExtension ext : copy) {
            try {
                ext.onDisabled();
            } catch (Exception e) {
                LOGGER.warning("Error disabling " + ext.getName() + ": " + e.getMessage());
            }
        }
    }

    private void clearCollections() {
        extensions.clear();
        sortedExtensions = Collections.emptyList();
        cullers.clear();
        postProcessors.clear();
    }

    private void resetState() {
        initialized = false;
        vulkanDevice = 0;
        currentFrame = 0;
        lastDeltaTime = 0f;
        qualityAssurance = null;
    }

    private void syncFrameNumberToPipeline() {
        if (AsyncRenderPipeline.isInitialized()) {
            AsyncRenderPipeline.getInstance().syncFrameNumber(currentFrame);
        }
        frameDataArena.beginFrame(currentFrame);
    }

    private void markReflexInputSample() {
        ReflexManager reflex = techManager != null ? techManager.getReflexManager() : null;
        if (reflex != null && reflex.isEnabled()) {
            reflex.markInputSample();
        }
    }

    private void dispatchExtensionFrameBegin(float deltaTime) {
        for (RenderExtension ext : sortedExtensions) {
            if (!ext.isEnabled()) continue;

            if (ext.getPriority() <= SYNC_PRIORITY_THRESHOLD) {
                ext.onFrameBegin(currentFrame, deltaTime);
            } else {
                boolean asyncAvailable = AsyncRenderPipeline.isInitialized();
                AdaptivePathSelector.PathState path = pathSelector.decide(asyncAvailable);

                if (path == AdaptivePathSelector.PathState.ASYNC && asyncAvailable) {
                    submitAsyncTask(ext, deltaTime);
                } else {
                    ext.onFrameBegin(currentFrame, deltaTime);
                }
            }
        }
    }

    private void submitAsyncTask(RenderExtension ext, float deltaTime) {
        AsyncRenderPipeline pipeline = AsyncRenderPipeline.getInstance();
        if (pipeline != null && pipeline.isRunning()) {
            AsyncRenderPipeline.FrameTask task =
                new AsyncRenderPipeline.FrameTask(
                    currentFrame,
                    AsyncRenderPipeline.FrameTask.TaskType.EXTENSION_FRAME_BEGIN,
                    ext,
                    deltaTime
                );
            pipeline.submitTask(task);
        } else {
            ext.onFrameBegin(currentFrame, deltaTime);
        }
    }

    private void evaluateSuperResolutionIfNeeded() {
        // TODO: 实现超分辨率评估逻辑
    }

    private void executePostProcessors(long commandBuffer, long sceneTexture) {
        for (PostProcessor processor : postProcessors) {
            if (processor.isEnabled()) {
                PostProcessor.TextureInputs inputs = new PostProcessor.TextureInputs(
                    sceneTexture, currentTextures.depthTexture, 0, 0
                );
                PostProcessor.TextureOutput output = new PostProcessor.TextureOutput(
                    sceneTexture, currentTextures.width, currentTextures.height
                );
                processor.process(commandBuffer, inputs, output,
                    currentTextures.width, currentTextures.height);
            }
        }
    }

    private void markReflexSubmitAndPresent() {
        ReflexManager reflex = techManager != null ? techManager.getReflexManager() : null;
        if (reflex != null && reflex.isEnabled()) {
            reflex.markSubmitFrame();
            reflex.markPresent();
        }
    }

    private float collectEnergyBeforeProcessing() {
        if (!qualityAssurance.isLyapunovQualityCheckEnabled()) return 0.0f;
        // TODO: 实现梯度能量计算
        return 0.0f;
    }

    private void resortExtensions() {
        List<RenderExtension> newSorted = new ArrayList<>(extensions.values());
        newSorted.sort(Comparator.comparingInt(RenderExtension::getPriority));
        sortedExtensions = Collections.unmodifiableList(newSorted);
    }

    private void sortPostProcessors() {
        postProcessors.sort(Comparator.comparingInt(PostProcessor::getOrder));
    }

    private void logShortCircuit() {
        LOGGER.severe("========================================");
        LOGGER.severe("  Renderium 未启动");
        LOGGER.severe("  原因: " + shortCircuitReason);
        LOGGER.severe("  所有优化功能已禁用");
        LOGGER.severe("  游戏将使用官方渲染器正常运行");
        LOGGER.severe("========================================");
    }

    /** 同步/异步分界优先级阈值 */
    private static final int SYNC_PRIORITY_THRESHOLD = 500;

    // ==================== 兼容性内部类 ====================

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

    // ==================== AutoCloseable ====================

    @Override
    public void close() {
        shutdown();
    }
}
