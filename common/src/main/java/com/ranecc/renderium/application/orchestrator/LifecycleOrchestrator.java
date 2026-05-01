package com.ranecc.renderium.application.orchestrator;

import com.ranecc.renderium.application.usecase.ConfigureUseCase;
import com.ranecc.renderium.application.usecase.InitializeUseCase;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.infrastructure.config.ConfigManager;
import com.ranecc.renderium.platform.hook.HookManager;

import java.util.logging.Logger;

/**
 * 生命周期编排器（Application Layer - Orchestrator）
 *
 * <p>负责管理 Renderium 系统的完整生命周期，
 * 协调初始化、运行、关闭各阶段的组件创建和销毁。
 *
 * <h3>状态机</h3>
 * <pre>
 * CREATED → INITIALIZED → READY → RUNNING → SHUTDOWN
 *              ↑                           |
 *              └───────────────────────────┘
 *                  (错误恢复或重启)
 * </pre>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>init()</b>：调用 InitializeUseCase 完成系统初始化</li>
 *   <li><b>frameTick()</b>：委托 FrameProcessor 处理单帧</li>
 *   <li><b>shutdown()</b>：按逆序释放所有资源</li>
 *   <li><b>状态管理</b>：维护生命周期状态转换</li>
 * </ul>
 *
 * <h3>资源释放顺序（shutdown 时）</h3>
 * <ol>
 *   <li>hooks → 注销所有平台钩子</li>
 *   <li>pipeline → 停止异步渲染管线</li>
 *   <li>scheduler → 释放调度器资源</li>
 *   <li>accelerator → 关闭原生加速器</li>
 *   <li>config → 保存配置并释放引用</li>
 * </ol>
 *
 * @see InitializeUseCase
 * @see FrameProcessor
 * @see ConfigureUseCase
 * @since 1.1.0
 */
public class LifecycleOrchestrator {

    private static final Logger LOGGER = Logger.getLogger(LifecycleOrchestrator.class.getName());

    /** 生命周期状态枚举 */
    public enum LifecycleState {
        /** 已创建 - 对象刚实例化 */
        CREATED,
        /** 已初始化 - init() 成功完成 */
        INITIALIZED,
        /** 就绪 - 等待第一帧 */
        READY,
        /** 运行中 - 正在处理帧 */
        RUNNING,
        /** 已关闭 - shutdown() 完成 */
        SHUTDOWN
    }

    /** 当前状态 */
    private volatile LifecycleState state = LifecycleState.CREATED;

    /** 初始化用例 */
    private InitializeUseCase initializeUseCase;

    /** 帧处理器 */
    private FrameProcessor frameProcessor;

    /** 配置管理用例 */
    private ConfigureUseCase configureUseCase;

    /** 当前配置引用 */
    private RenderiumConfig config;

    /**
     * 默认构造函数
     */
    public LifecycleOrchestrator() {
        this.initializeUseCase = new InitializeUseCase();
        this.frameProcessor = new FrameProcessor();
        this.configureUseCase = new ConfigureUseCase();
    }

    /**
     * 初始化系统
     *
     * <p>执行完整的初始化流程，包括：
     * <ol>
     *   <li>加载/验证配置</li>
     *   <li>初始化原生加速器</li>
     *   <li>创建调度器和优化器</li>
     *   <li>注册平台钩子</li>
     * </ol>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>deviceHandle - 设备句柄（Object 类型）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>IllegalStateException - 如果当前状态不允许初始化</li>
     * </ul>
     *
     * @param deviceHandle 图形设备句柄
     * @throws IllegalStateException 如果系统已初始化或已关闭
     */
    public void init(Object deviceHandle) {
        if (state != LifecycleState.CREATED) {
            throw new IllegalStateException(
                "Cannot initialize in state: " + state + ". Expected: CREATED"
            );
        }

        setState(LifecycleState.INITIALIZED);

        try {
            LOGGER.info("=== LifecycleOrchestrator: Starting Initialization ===");

            // Step 1: 加载配置
            config = configureUseCase.load();
            LOGGER.info("Configuration loaded: mode=" + config.getMode()
                       + ", quality=" + config.getQualityLevel());

            // Step 2: 执行完整初始化（委托给 InitializeUseCase）
            initializeUseCase.execute(deviceHandle);

            // Step 3: 将调度器注入到 FrameProcessor
            frameProcessor.setScheduler(initializeUseCase.getScheduler());

            setState(LifecycleState.READY);

            LOGGER.info("=== LifecycleOrchestrator: Initialization Complete ===");

        } catch (Exception e) {
            setState(LifecycleState.SHUTDOWN);
            LOGGER.severe("Initialization failed: " + e.getMessage());
            throw new RuntimeException("LifecycleOrchestrator initialization failed", e);
        }
    }

    /**
     * 处理单帧（帧 tick）
     *
     * <p>委托给 {@link FrameProcessor#processFrame(float)} 执行。
     * 此方法应在渲染循环的每帧调用一次。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>deltaTime - 帧间隔时间（秒）（float 类型）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>IllegalStateException - 如果系统未就绪</li>
     * </ul>
     *
     * @param deltaTime 帧间隔时间（单位：秒）
     * @throws IllegalStateException 如果系统未处于 READY 或 RUNNING 状态
     */
    public void frameTick(float deltaTime) {
        if (state != LifecycleState.READY && state != LifecycleState.RUNNING) {
            throw new IllegalStateException(
                "Cannot process frame in state: " + state + ". Expected: READY or RUNNING"
            );
        }

        setState(LifecycleState.RUNNING);

        try {
            frameProcessor.processFrame(deltaTime);
        } finally {
            setState(LifecycleState.READY);
        }
    }

    /**
     * 关闭系统并释放所有资源
     *
     * <p>按逆序释放资源：
     * <ol>
     *   <li>停止帧处理器</li>
     *   <li>注销平台钩子</li>
     *   <li>释放调度器资源</li>
     *   <li>关闭原生加速器</li>
     *   <li>保存配置</li>
     * </ol>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>无（内部捕获所有异常）</li>
     * </ul>
     */
    public void shutdown() {
        if (state == LifecycleState.SHUTDOWN) {
            LOGGER.warning("Shutdown called but already in SHUTDOWN state");
            return;
        }

        try {
            LOGGER.info("=== LifecycleOrchestrator: Starting Shutdown ===");

            // Step 1: 停止帧处理器
            shutdownFrameProcessor();

            // Step 2: 注销平台钩子
            unregisterHooks();

            // Step 3: 释放调度器资源
            releaseScheduler();

            // Step 4: 关闭原生加速器
            shutdownAccelerator();

            // Step 5: 保存配置
            saveConfiguration();

            setState(LifecycleState.SHUTDOWN);

            LOGGER.info("=== LifecycleOrchestrator: Shutdown Complete ===");

        } catch (Exception e) {
            LOGGER.severe("Error during shutdown: " + e.getMessage());
            setState(LifecycleState.SHUTDOWN); // 强制进入关闭状态
        }
    }

    /**
     * 获取当前状态
     *
     * @return LifecycleState 当前生命周期状态
     */
    public LifecycleState getState() {
        return state;
    }

    /**
     * 获取当前配置
     *
     * @return RenderiumConfig 配置实例（可能为 null）
     */
    public RenderiumConfig getConfig() {
        return config;
    }

    /**
     * 获取帧处理器（用于监控和调试）
     *
     * @return FrameProcessor 帧处理器实例
     */
    public FrameProcessor getFrameProcessor() {
        return frameProcessor;
    }

    /**
     * 获取配置管理用例
     *
     * @return ConfigureUseCase 配置管理用例
     */
    public ConfigureUseCase getConfigureUseCase() {
        return configureUseCase;
    }

    // ==================== 私有资源释放方法 ====================

    /**
     * 关闭帧处理器
     */
    private void shutdownFrameProcessor() {
        if (frameProcessor != null) {
            try {
                frameProcessor.shutdown();
                LOGGER.info("Frame processor shut down");
            } catch (Exception e) {
                LOGGER.warning("Error shutting down frame processor: " + e.getMessage());
            }
        }
    }

    /**
     * 注销所有平台钩子
     */
    private void unregisterHooks() {
        try {
            HookManager.setGpuDeviceBufferHook(null);
            HookManager.setSetPipelineHook(null);
            HookManager.setBindTextureHook(null);
            HookManager.setDrawIndexedHook(null);
            HookManager.setCommandEncoderSubmitHook(null);
            HookManager.setPostChainHook(null);
            HookManager.setFrameGraphExecuteHook(null);
            HookManager.setRenderPassCloseHook(null);
            LOGGER.info("All platform hooks unregistered");
        } catch (Exception e) {
            LOGGER.warning("Error unregistering hooks: " + e.getMessage());
        }
    }

    /**
     * 释放调度器资源
     */
    private void releaseScheduler() {
        if (initializeUseCase != null && initializeUseCase.getScheduler() != null) {
            try {
                initializeUseCase.getScheduler().releaseResources();
                LOGGER.info("Scheduler resources released");
            } catch (Exception e) {
                LOGGER.warning("Error releasing scheduler: " + e.getMessage());
            }
        }
    }

    /**
     * 关闭原生加速器
     */
    private void shutdownAccelerator() {
        if (initializeUseCase != null && initializeUseCase.getAccelerator() != null) {
            try {
                initializeUseCase.getAccelerator().close();
                LOGGER.info("Native accelerator shut down");
            } catch (Exception e) {
                LOGGER.warning("Error shutting down accelerator: " + e.getMessage());
            }
        }
    }

    /**
     * 保存配置到文件
     */
    private void saveConfiguration() {
        if (configureUseCase != null && config != null) {
            try {
                configureUseCase.save();
                LOGGER.info("Configuration saved");
            } catch (Exception e) {
                LOGGER.warning("Error saving configuration: " + e.getMessage());
            }
        }
    }

    /**
     * 设置当前状态（带日志记录）
     *
     * @param newState 新状态
     */
    private void setState(LifecycleState newState) {
        LifecycleState oldState = this.state;
        this.state = newState;

        if (oldState != newState) {
            LOGGER.fine("Lifecycle state transition: " + oldState + " -> " + newState);
        }
    }
}
