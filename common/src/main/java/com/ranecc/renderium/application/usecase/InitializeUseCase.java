package com.ranecc.renderium.application.usecase;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.domain.service.scheduling.AdaptivePathSelector;
import com.ranecc.renderium.feature.blaze3d.Blaze3DOptimizerModule;
import com.ranecc.renderium.feature.module.ModuleContext;
import com.ranecc.renderium.feature.module.ModuleRegistry;
import com.ranecc.renderium.infrastructure.config.ConfigManager;
import com.ranecc.renderium.infrastructure.diagnostics.StubModeHealthCheck;
import com.ranecc.renderium.infrastructure.nativeLib.RenderiumAccelerator;
import com.ranecc.renderium.platform.hook.HookManager;
import com.ranecc.renderium.platform.hook.OptimizerRegistry;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 初始化用例（Application Layer - Use Case）
 *
 * <p>负责 Renderium 系统的完整初始化流程，
 * 按正确顺序协调各组件的创建和配置。
 *
 * <h3>初始化流程</h3>
 * <ol>
 *   <li><b>加载配置</b>：通过 ConfigManager 加载或创建默认配置</li>
 *   <li><b>初始化加速器</b>：创建 RenderiumAccelerator 实例并初始化原生库</li>
 *   <li><b>创建调度器</b>：实例化 AdaptivePathSelector 并注入加速器</li>
 *   <li><b>注册优化器</b>：通过 HookManager 注册平台钩子</li>
 *   <li><b>桥接 Blaze3D 优化器</b>：构建 ModuleContext 并初始化 Blaze3DOptimizerModule</li>
 * </ol>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>零 FFI/JNI 直接调用（通过 RenderiumAccelerator 间接访问）</li>
 *   <li>零 net.minecraft.* 依赖（使用 Object 类型）</li>
 *   <li>所有错误记录日志但不抛出异常（允许部分降级）</li>
 * </ul>
 *
 * @see ConfigManager
 * @see RenderiumAccelerator
 * @see AdaptivePathSelector
 * @see HookManager
 * @since 1.1.0
 */
public class InitializeUseCase {

    private static final Logger LOGGER = Logger.getLogger(InitializeUseCase.class.getName());

    /** 加载的配置实例（初始化后可用） */
    private RenderiumConfig config;

    /** 原生加速器实例 */
    private RenderiumAccelerator accelerator;

    /** 自适应路径选择器 */
    private AdaptivePathSelector scheduler;

    /**
     * 执行初始化流程
     *
     * <p>按顺序执行所有初始化步骤，每步失败会记录警告但继续执行
     * （除非是关键步骤如配置加载）。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>deviceHandle - 设备句柄（Object 类型）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>RuntimeException - 如果关键步骤失败</li>
     * </ul>
     *
     * @param deviceHandle 图形设备句柄（Vulkan Device、OpenGL Context 等）
     * @throws RuntimeException 如果配置加载或加速器初始化失败
     */
    public void execute(Object deviceHandle) {
        LOGGER.info("=== Starting Renderium Initialization ===");

        // Step 1: 加载配置（关键步骤）
        config = loadConfiguration();
        if (config == null) {
            throw new RuntimeException("Failed to load configuration, aborting initialization");
        }
        LOGGER.info("Step 1/5: Configuration loaded successfully");

        // Step 2: 初始化原生加速器（关键步骤）
        accelerator = initializeAccelerator(deviceHandle);
        if (accelerator == null) {
            LOGGER.warning("Native accelerator not available, using Java fallback");
            // 不抛出异常，允许纯 Java 模式运行
        } else {
            LOGGER.info("Step 2/5: Native accelerator initialized: " + accelerator.getVersion());
        }

        // Step 3: 创建自适应路径选择器
        scheduler = createScheduler(accelerator);
        LOGGER.info("Step 3/5: Adaptive path selector created");

        // Step 4: 注册平台优化器钩子
        registerOptimizers(config);
        LOGGER.info("Step 4/5: Platform optimizers registered");

        // Step 5: 桥接 Blaze3D 优化器模块初始化
        initializeBlazeOptimizer(config, deviceHandle);
        LOGGER.info("Step 5/5: Blaze3D optimizer module initialized");

        // Step 6: 存根模式健康检查（诊断哪些功能在降级/存根模式下运行）
        int stubCount = StubModeHealthCheck.getInstance().reportAllStubs();
        if (stubCount > 0) {
            LOGGER.warning("Renderium 初始化完成，但 " + stubCount
                + " 个功能处于存根/降级模式（详见上方健康检查报告）");
        }

        LOGGER.info("=== Renderium Initialization Complete ===");
    }

    /**
     * 获取初始化后的配置实例
     *
     * @return RenderiumConfig 配置实例（execute() 调用后才可用）
     */
    public RenderiumConfig getConfig() {
        return config;
    }

    /**
     * 获取初始化后的加速器实例
     *
     * @return RenderiumAccelerator 加速器实例（可能为 null）
     */
    public RenderiumAccelerator getAccelerator() {
        return accelerator;
    }

    /**
     * 获取初始化后的调度器实例
     *
     * @return AdaptivePathSelector 调度器实例
     */
    public AdaptivePathSelector getScheduler() {
        return scheduler;
    }

    // ==================== 私有初始化步骤 ====================

    /**
     * Step 1: 加载配置
     *
     * <p>优先从默认路径加载配置文件，如果不存在则使用默认配置。
     * 使用 ConfigManager 进行加载和验证。
     *
     * @return RenderiumConfig 配置实例，失败返回 null
     */
    private RenderiumConfig loadConfiguration() {
        try {
            String configPath = System.getProperty("renderium.config.path", "");
            if (configPath.isEmpty()) {
                configPath = getDefaultConfigPath();
            }

            return ConfigManager.loadOrDefault(configPath);

        } catch (Exception e) {
            LOGGER.severe("Failed to load configuration: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取默认配置路径
     *
     * @return 默认配置目录路径字符串
     */
    private String getDefaultConfigPath() {
        String userHome = System.getProperty("user.home", ".");
        return userHome + "/.renderium";
    }

    /**
     * Step 2: 初始化原生加速器
     *
     * <p>尝试创建和初始化 RenderiumAccelerator 实例。
     * 失败时返回 null（允许 Java 回退模式）。
     *
     * @param deviceHandle 设备句柄
     * @return RenderiumAccelerator 实例，失败返回 null
     */
    private RenderiumAccelerator initializeAccelerator(Object deviceHandle) {
        try {
            RenderiumAccelerator accel = RenderiumAccelerator.getInstance();
            accel.initialize(deviceHandle);
            return accel;

        } catch (UnsatisfiedLinkError e) {
            LOGGER.warning("Native library not found: " + e.getMessage()
                         + ", falling back to pure Java mode");
            return null;

        } catch (Exception e) {
            LOGGER.warning("Failed to initialize native accelerator: " + e.getMessage());
            return null;
        }
    }

    /**
     * Step 3: 创建自适应路径选择器
     *
     * <p>实例化 AdaptivePathSelector 并注入加速器（如果可用）。
     * 选择器负责在运行时决定使用 Java 还是 Native 路径。
     *
     * @param accelerator 原生加速器（可为 null）
     * @return AdaptivePathSelector 实例
     */
    private AdaptivePathSelector createScheduler(RenderiumAccelerator accelerator) {
        AdaptivePathSelector selector = new AdaptivePathSelector();

        if (accelerator != null && accelerator.isNativeLibraryAvailable()) {
            selector.setAccelerator(accelerator);
            LOGGER.info("Scheduler configured with native acceleration support");
        } else {
            LOGGER.info("Scheduler configured in Java-only mode");
        }

        return selector;
    }

    /**
     * Step 4: 注册平台优化器钩子
     *
     * <p>通过 HookManager 注册各种渲染优化钩子，
     * 包括 GPU 缓冲区管理、纹理绑定、绘制调用等。
     *
     * @param config 当前配置（用于确定启用哪些优化器）
     */
    private void registerOptimizers(RenderiumConfig config) {
        try {
            // 根据配置注册不同的优化器集合
            if (config.isBatchingEnabled()) {
                HookManager.setDrawIndexedHook(new OptimizerRegistry.BatchDrawOptimizer());
                LOGGER.info("Registered batch draw optimizer");
            }

            if (config.isOcclusionCullingEnabled()) {
                HookManager.setGpuDeviceBufferHook(new OptimizerRegistry.OcclusionCullOptimizer());
                LOGGER.info("Registered occlusion culling optimizer");
            }

            // TODO: 根据需要注册更多优化器
            // HookManager.setBindTextureHook(...);
            // HookManager.setSetPipelineHook(...);

        } catch (Exception e) {
            LOGGER.warning("Failed to register some optimizers: " + e.getMessage());
            // 不抛出异常，允许系统在降级模式下运行
        }
    }

    /**
     * Step 5: 桥接 Blaze3D 优化器模块初始化
     *
     * <p>构建 {@link ModuleContext} 上下文，并按标准生命周期
     * {@code canLoad → load → initialize → enable} 完成
     * {@link Blaze3DOptimizerModule} 的初始化和激活。
     *
     * <p>该模块负责 Blaze3D 渲染引擎内部的 FrameGraph、
     * Vulkan Command、Memory 和 Shader Pipeline 优化。
     * 初始化失败不会中断整体流程（允许降级运行）。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>config - 已加载的全局配置；deviceHandle - 图形设备句柄</li>
     *   <li><b>返回值：</b>void</li>
     * </ul>
     *
     * @param config       已加载的全局配置实例
     * @param deviceHandle 图形设备句柄（Vulkan Device 等，可为 null）
     */
    private void initializeBlazeOptimizer(RenderiumConfig config, Object deviceHandle) {
        try {
            // 构建 ModuleContext：为 Blaze3DOptimizerModule 提供运行时依赖注入
            Path gameDir = Path.of(System.getProperty("user.dir", "."));
            Path configDir = gameDir.resolve(".renderium");

            ModuleContext moduleContext = new ModuleContext(
                    config,                             // 全局配置
                    ModuleRegistry.getInstance(),       // 模块注册表单例
                    gameDir,                            // 游戏运行目录
                    configDir,                          // 配置目录
                    "compatible"                        // 默认兼容模式
            );

            // 将设备句柄注入到 ModuleContext，供 Vulkan 相关子系统使用
            if (deviceHandle != null) {
                moduleContext.setGpuDevice(deviceHandle);
            }

            // 创建 Blaze3DOptimizerModule 实例并按生命周期执行
            Blaze3DOptimizerModule blazeModule = new Blaze3DOptimizerModule();

            if (!blazeModule.canLoad(moduleContext)) {
                LOGGER.warning("Blaze3DOptimizerModule cannot load in current environment");
                return;
            }

            if (!blazeModule.load(moduleContext)) {
                LOGGER.warning("Blaze3DOptimizerModule load phase failed");
                return;
            }

            if (!blazeModule.initialize(moduleContext)) {
                LOGGER.warning("Blaze3DOptimizerModule initialize phase failed");
                return;
            }

            if (!blazeModule.enable()) {
                LOGGER.warning("Blaze3DOptimizerModule enable phase failed");
                return;
            }

            LOGGER.info("Blaze3DOptimizerModule bridge initialized successfully");

        } catch (Exception e) {
            LOGGER.warning("Failed to bridge Blaze3D optimizer initialization: " + e.getMessage());
            // 不抛出异常，允许系统在降级模式下运行
        }
    }
}
