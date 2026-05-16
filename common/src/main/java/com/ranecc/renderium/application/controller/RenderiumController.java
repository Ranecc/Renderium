package com.ranecc.renderium.application.controller;

import com.ranecc.renderium.application.core.RenderiumCore;
import com.ranecc.renderium.application.core.CoreState;

import java.util.logging.Logger;

/**
 * Renderium Mod 主控制器
 *
 * <p>作为 Mod 的公共 API 入口点，管理生命周期并提供状态查询接口。
 * 采用<strong>薄控制器模式</strong>，所有业务逻辑委托给 {@link RenderiumCore}。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>门面模式</b>：对外提供简化的统一入口</li>
 *   <li><b>单例模式</b>：全局唯一实例，线程安全访问</li>
 *   <li><b>委托模式</b>：零业务逻辑，全部转发给 Core</li>
 *   <li><b>平台无关</b>：不依赖 Fabric/NeoForge 特定 API</li>
 * </ul>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>Mod 生命周期管理（initialize / shutdown）</li>
 *   <li>配置加载协调</li>
 *   <li>事件总线注册代理</li>
 *   <li>公共 API 暴露</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 平台适配器中调用
 * RenderiumController.initialize();
 *
 * // 获取状态
 * RenderState state = RenderiumController.getInstance().getState();
 *
 * // 关闭
 * RenderiumController.shutdown();
 * }</pre>
 *
 * @see RenderiumCore
 * @since 1.1.0
 */
public final class RenderiumController {

    private static final Logger LOGGER = Logger.getLogger(RenderiumController.class.getName());

    /** 单例实例（volatile 保证多线程可见性） */
    private static volatile RenderiumController instance;

    /** 核心门面引用（委托目标） */
    private final RenderiumCore core;

    /**
     * 私有构造函数
     *
     * @param core RenderiumCore 门面实例（非 null）
     */
    private RenderiumController(RenderiumCore core) {
        this.core = core;
        LOGGER.fine("RenderiumController instance created");
    }

    /**
     * 获取控制器单例
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
     *   <li><b>返回值：</b>RenderiumController - 全局单例实例（非 null）</li>
     *   <li><b>异常：</b>IllegalStateException - 如果未初始化</li>
     * </ul>
     *
     * @return RenderiumController 全局唯一实例
     * @throws IllegalStateException 如果尚未调用 initialize()
     */
    public static RenderiumController getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                "RenderiumController not initialized. Call initialize() first."
            );
        }
        return instance;
    }

    /**
     * 初始化 Mod（在 Mod 加载时调用）
     *
     * <p>必须在使用任何其他方法之前调用。此方法会：
     * <ol>
     *   <li>创建 Controller 和 Core 单例</li>
     *   <li>委托给 Core 执行完整初始化流程</li>
     * </ol>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>IllegalStateException - 如果已初始化或初始化失败</li>
     * </ul>
     *
     * @throws IllegalStateException 如果系统已初始化或正在初始化中
     */
    public static synchronized void initialize() {
        if (instance != null) {
            LOGGER.warning("RenderiumController already initialized");
            return;
        }

        LOGGER.info("Initializing RenderiumController...");

        try {
            // 获取 Core 单例
            RenderiumCore core = RenderiumCore.getInstance();

            // 创建 Controller 实例
            instance = new RenderiumController(core);

            // 委托给 Core 初始化（传入 null 设备句柄，由平台层后续设置）
            core.initialize(null);

            LOGGER.info("RenderiumController initialized successfully");

        } catch (Exception e) {
            instance = null;
            LOGGER.severe("Failed to initialize RenderiumController: " + e.getMessage());
            throw new IllegalStateException("Renderium initialization failed", e);
        }
    }

    /**
     * 关闭 Mod（在 Mod 卸载时调用）
     *
     * <p>清理资源、保存配置。此方法会：
     * <ol>
     *   <li>委托给 Core 执行关闭流程</li>
     *   <li>释放 Controller 引用</li>
     * </ol>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>void</li>
     * </ul>
     */
    public static synchronized void shutdown() {
        if (instance == null) {
            LOGGER.warning("Shutdown called but RenderiumController not initialized");
            return;
        }

        LOGGER.info("Shutting down RenderiumController...");

        try {
            // 委托给 Core 关闭
            instance.core.shutdown();

        } catch (Exception e) {
            LOGGER.warning("Error during shutdown: " + e.getMessage());

        } finally {
            // 释放引用
            instance = null;
            LOGGER.info("RenderiumController shut down");
        }
    }

    /**
     * 获取核心门面
     *
     * <p>用于高级用户直接访问 Core API。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>RenderiumCore - 核心门面实例（非 null）</li>
     * </ul>
     *
     * @return RenderiumCore 核心门面实例
     */
    public RenderiumCore core() {
        return core;
    }

    /**
     * 查询当前运行状态
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
    public RenderiumCore.CoreState getState() {
        return core.getState();
    }
}
