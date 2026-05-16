package com.ranecc.renderium.application.core;

import com.ranecc.renderium.application.orchestrator.LifecycleOrchestrator;
import com.ranecc.renderium.application.usecase.InitializeUseCase;
import com.ranecc.renderium.application.usecase.ProcessFrameUseCase;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

import java.util.logging.Logger;

/**
 * Renderium 核心门面类（Application Layer）
 *
 * <p>作为系统的唯一入口点和协调者，提供简洁的公共 API，
 * 将所有复杂逻辑委托给 UseCase 和 Orchestrator 层。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>门面模式</b>：对外提供简化的统一接口</li>
 *   <li><b>单例模式</b>：全局唯一实例，线程安全访问</li>
 *   <li><b>委托模式</b>：所有业务逻辑委托给 UseCase 类</li>
 *   <li><b>零依赖原则</b>：不直接依赖 FFI/JNI/Minecraft</li>
 * </ul>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>管理生命周期状态转换</li>
 *   <li>提供初始化/关闭/帧处理入口</li>
 *   <li>暴露当前系统状态查询</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 获取实例
 * RenderiumCore core = RenderiumCore.getInstance();
 *
 * // 初始化
 * core.initialize(deviceHandle);
 *
 * // 帧循环
 * core.processFrame(deltaTime);
 *
 * // 关闭
 * core.shutdown();
 * }</pre>
 *
 * @see InitializeUseCase
 * @see ProcessFrameUseCase
 * @see LifecycleOrchestrator
 * @since 1.1.0
 */
public final class RenderiumCore {

    private static final Logger LOGGER = Logger.getLogger(RenderiumCore.class.getName());

    /** 单例实例（volatile 保证多线程可见性） */
    private static volatile RenderiumCore instance;

    /** 当前生命周期状态 */
    private volatile CoreState state = CoreState.NOT_INITIALIZED;

    /** 初始化用例（延迟初始化） */
    private InitializeUseCase initializeUseCase;

    /** 帧处理用例（延迟初始化） */
    private ProcessFrameUseCase processFrameUseCase;

    /** 生命周期编排器（延迟初始化） */
    private LifecycleOrchestrator orchestrator;

    /**
     * 私有构造函数 - 使用 getInstance() 获取实例
     */
    private RenderiumCore() {
        LOGGER.fine("RenderiumCore instance created");
    }

    /**
     * 获取全局单例实例
     *
     * <p>使用双重检查锁定（DCL）+ volatile 模式，保证：
     * <ul>
     *   <li>线程安全：多线程环境下返回同一实例</li>
     *   <li>高性能：首次检查无锁读取（热路径优化）</li>
     *   <li>有序性：volatile 防止指令重排</li>
     * </ul>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>RenderiumCore - 全局单例实例（非 null）</li>
     * </ul>
     *
     * @return RenderiumCore 全局唯一实例
     */
    public static RenderiumCore getInstance() {
        if (instance == null) {
            synchronized (RenderiumCore.class) {
                if (instance == null) {
                    instance = new RenderiumCore();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化 Renderium 系统
     *
     * <p>执行完整的初始化流程，包括：
     * <ol>
     *   <li>加载配置</li>
     *   <li>初始化原生加速器</li>
     *   <li>创建调度器和优化器</li>
     *   <li>注册平台钩子</li>
     * </ol>
     *
     * <p>此方法会委托给 {@link InitializeUseCase} 执行具体逻辑。
     * 设备句柄使用 Object 类型以避免对特定平台的依赖。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>deviceHandle - 设备句柄（Object 类型，可为 Vulkan Device/OpenGL Context 等）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>IllegalStateException - 如果当前状态不允许初始化</li>
     * </ul>
     *
     * @param deviceHandle 图形设备句柄（Vulkan Device、OpenGL Context 等）
     * @throws IllegalStateException 如果系统已初始化或正在初始化中
     */
    public void initialize(Object deviceHandle) {
        if (state != CoreState.NOT_INITIALIZED && state != CoreState.ERROR) {
            throw new IllegalStateException(
                "Cannot initialize in current state: " + state +
                ". Expected: NOT_INITIALIZED or ERROR"
            );
        }

        setState(CoreState.INITIALIZING);

        try {
            LOGGER.info("Initializing Renderium system...");

            // 延迟初始化 UseCase 和 Orchestrator
            ensureComponentsInitialized();

            // 委托给 InitializeUseCase 执行初始化逻辑
            initializeUseCase.execute(deviceHandle);

            setState(CoreState.READY);

            LOGGER.info("Renderium system initialized successfully");

        } catch (Exception e) {
            setState(CoreState.ERROR);
            LOGGER.severe("Failed to initialize Renderium system: " + e.getMessage());
            throw new RuntimeException("Renderium initialization failed", e);
        }
    }

    /**
     * 处理单帧渲染
     *
     * <p>在每帧调用时执行，包括：
     * <ol>
     *   <li>收集帧数据（从平台桥接层获取相机、矩阵等）</li>
     *   <li>运行算法（BFS 遮挡剔除、LOD 计算等）</li>
     *   <li>分发结果到平台钩子</li>
     * </ol>
     *
     * <p>此方法会委托给 {@link ProcessFrameUseCase} 执行具体逻辑。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>deltaTime - 帧间隔时间（秒）（float 类型）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>IllegalStateException - 如果系统未就绪</li>
     * </ul>
     *
     * @param deltaTime 帧间隔时间（单位：秒，例如 0.0167 表示 60 FPS）
     * @throws IllegalStateException 如果系统未处于 READY 或 RUNNING 状态
     */
    public void processFrame(float deltaTime) {
        if (state != CoreState.READY && state != CoreState.RUNNING) {
            throw new IllegalStateException(
                "Cannot process frame in current state: " + state +
                ". Expected: READY or RUNNING"
            );
        }

        setState(CoreState.RUNNING);

        try {
            // 委托给 ProcessFrameUseCase 执行帧处理逻辑
            processFrameUseCase.execute(deltaTime);

        } catch (Exception e) {
            LOGGER.warning("Error processing frame: " + e.getMessage());
            // 帧处理失败不改变系统状态，允许下一帧继续尝试
        }

        // 帧处理完成后回到 READY 状态（等待下一帧）
        setState(CoreState.READY);
    }

    /**
     * 关闭 Renderium 系统
     *
     * <p>按逆序释放所有资源：
     * <ol>
     *   <li>停止帧处理</li>
     *   <li>注销平台钩子</li>
     *   <li>释放调度器资源</li>
     *   <li>关闭原生加速器</li>
     *   <li>保存配置</li>
     * </ol>
     *
     * <p>此方法会委托给 {@link LifecycleOrchestrator#shutdown()} 执行具体逻辑。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>IllegalStateException - 如果系统未初始化</li>
     * </ul>
     *
     * @throws IllegalStateException 如果系统未处于可关闭状态
     */
    public void shutdown() {
        if (state == CoreState.NOT_INITIALIZED || state == CoreState.SHUTDOWN) {
            LOGGER.warning("Shutdown called but system is already " + state);
            return;
        }

        try {
            LOGGER.info("Shutting down Renderium system...");

            // 委托给 LifecycleOrchestrator 执行关闭逻辑
            if (orchestrator != null) {
                orchestrator.shutdown();
            }

            setState(CoreState.SHUTDOWN);

            LOGGER.info("Renderium system shut down successfully");

        } catch (Exception e) {
            setState(CoreState.ERROR);
            LOGGER.severe("Error during shutdown: " + e.getMessage());
        }
    }

    /**
     * 获取当前系统状态
     *
     * <p>用于监控和调试，返回当前的 lifecycle 状态。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>CoreState - 当前生命周期状态枚举</li>
     * </ul>
     *
     * @return CoreState 当前系统状态（NOT_INITIALIZED/INITIALIZING/READY/RUNNING/ERROR/SHUTDOWN）
     */
    public CoreState getState() {
        return state;
    }

    /**
     * 检查系统是否已完成初始化
     *
     * <p>当系统处于 READY 或 RUNNING 状态时返回 true。
     * 用于外部组件（如 ShaderWorkbench）判断核心是否可用。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>boolean - 系统是否已初始化就绪</li>
     * </ul>
     *
     * @return true 如果系统已初始化（READY 或 RUNNING 状态）
     */
    public boolean isInitialized() {
        return state == CoreState.READY || state == CoreState.RUNNING;
    }

    /**
     * 获取当前配置快照
     *
     * <p>返回不可变的配置副本，用于 UI 显示和调试。
     *
     * @return RenderiumConfig 当前配置实例（可能为 null 如果未初始化）
     */
    public RenderiumConfig getConfig() {
        if (orchestrator == null) {
            return null;
        }
        return orchestrator.getConfig();
    }

    /**
     * 保存当前配置到文件
     *
     * <p>委托给 {@link ConfigureUseCase#save()} 执行持久化。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>boolean - true 表示保存成功</li>
     * </ul>
     *
     * @return true 如果配置保存成功，false 表示失败
     */
    public boolean saveConfig() {
        if (orchestrator != null) {
            return orchestrator.saveConfig();
        }
        LOGGER.warning("Cannot save config: orchestrator not initialized");
        return false;
    }

    /**
     * 重置配置为默认值
     *
     * <p>将当前配置替换为全新的默认配置实例。
     * 注意：这不会自动保存到文件，需要显式调用 {@link #saveConfig()}。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>void</li>
     * </ul>
     */
    public void resetConfig() {
        if (orchestrator != null) {
            orchestrator.resetConfig();
            LOGGER.info("Configuration reset to defaults");
        } else {
            LOGGER.warning("Cannot reset config: orchestrator not initialized");
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 确保 UseCase 和 Orchestrator 已初始化
     *
     * <p>采用延迟初始化策略，避免在构造函数中创建重量级对象。
     */
    private void ensureComponentsInitialized() {
        if (initializeUseCase == null) {
            initializeUseCase = new InitializeUseCase();
        }
        if (processFrameUseCase == null) {
            processFrameUseCase = new ProcessFrameUseCase();
        }
        if (orchestrator == null) {
            orchestrator = new LifecycleOrchestrator();
        }
    }

    /**
     * 设置当前状态（带日志记录）
     *
     * @param newState 新状态
     */
    private void setState(CoreState newState) {
        CoreState oldState = this.state;
        this.state = newState;

        if (oldState != newState) {
            LOGGER.fine("State transition: " + oldState + " -> " + newState);
        }
    }

    // ==================== CoreState 枚举 ====================

    /**
     * 核心系统生命周期状态枚举
     *
     * <p>定义 Renderium 系统的所有可能状态，
     * 用于状态机管理和并发控制。
     *
     * <h3>状态转换图</h3>
     * <pre>
     * NOT_INITIALIZED → INITIALIZING → READY ↔ RUNNING
     *                      ↓               ↓
     *                   ERROR ← -------- SHUTDOWN
     *                      ↑                 ↑
     *                      └-----------------┘
     * </pre>
     *
     * @since 1.1.0
     */
    public enum CoreState {

        /** 未初始化 - 系统刚创建，尚未调用 initialize() */
        NOT_INITIALIZED,

        /** 正在初始化 - initialize() 正在执行 */
        INITIALIZING,

        /** 就绪 - 初始化完成，等待第一帧 */
        READY,

        /** 运行中 - 正在处理某一帧 */
        RUNNING,

        /** 错误状态 - 初始化或运行时发生错误 */
        ERROR,

        /** 已关闭 - shutdown() 已完成，资源已释放 */
        SHUTDOWN
    }
}
