package com.ranecc.renderium.presentation.ui;

import com.ranecc.renderium.platform.bridge.video.VideoSettingsBridge;

import java.util.logging.Logger;

/**
 * 设置协调器（Presentation Layer - UI）
 *
 * <p>负责协调 Renderium 设置界面与底层系统之间的交互，
 * 作为 UI 层和 Application 层之间的桥梁。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>openSettings(parent)</b>：通过 VideoSettingsBridge 打开设置界面</li>
 *   <li><b>isAvailable()</b>：检查 VideoSettingsBridge 是否已初始化</li>
 *   <li><b>状态查询</b>：提供当前可用性和模式信息</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li><b>薄封装层</b>：仅委托给 VideoSettingsBridge，不包含业务逻辑</li>
 *   <li><b>平台无关</b>：使用 Object 类型避免依赖特定 GUI 框架</li>
 *   <li><b>容错性</b>：所有操作都有完善的错误处理和日志记录</li>
 * </ul>
 *
 * @see VideoSettingsBridge
 * @see PerformanceSettingsHandler
 * @since 1.1.0
 */
public final class SettingsCoordinator {

    private static final Logger LOGGER = Logger.getLogger(SettingsCoordinator.class.getName());

    /** 单例实例（volatile 保证线程安全） */
    private static volatile SettingsCoordinator instance;

    /** 性能设置处理器 */
    private final PerformanceSettingsHandler settingsHandler;

    /**
     * 私有构造函数 - 使用 getInstance() 获取实例
     */
    private SettingsCoordinator() {
        this.settingsHandler = new PerformanceSettingsHandler();
        LOGGER.fine("SettingsCoordinator initialized");
    }

    /**
     * 获取全局单例实例
     *
     * <p>使用双重检查锁定（DCL）+ volatile 模式。
     *
     * @return SettingsCoordinator 全局唯一实例（非 null）
     */
    public static SettingsCoordinator getInstance() {
        if (instance == null) {
            synchronized (SettingsCoordinator.class) {
                if (instance == null) {
                    instance = new SettingsCoordinator();
                }
            }
        }
        return instance;
    }

    /**
     * 打开设置界面
     *
     * <p>通过 {@link VideoSettingsBridge#openSettings(Object)} 协调打开设置界面。
     * 支持原版 Minecraft 视频设置页面或 Sodium 的自定义设置页面。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>parentScreen - 父屏幕对象（Object 类型）</li>
     *   <li><b>返回值：</b>Object - 打开的设置屏幕对象（可能为 null）</li>
     *   <li><b>异常：</b>IllegalStateException - 如果 VideoSettingsBridge 未初始化</li>
     * </ul>
     *
     * @param parentScreen 父屏幕对象（Minecraft Screen 实例或其他 GUI 容器）
     * @return Object 打开的设置屏幕对象，失败返回 null
     * @throws IllegalStateException 如果 VideoSettingsBridge 未初始化
     */
    public Object openSettings(Object parentScreen) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                "VideoSettingsBridge is not initialized. Call VideoSettingsBridge.initialize() first."
            );
        }

        try {
            LOGGER.info("Opening Renderium settings screen...");

            Object settingsScreen = VideoSettingsBridge.openSettings(parentScreen);

            if (settingsScreen != null) {
                LOGGER.info("Settings screen opened successfully");
            } else {
                LOGGER.warning("VideoSettingsBridge.openSettings() returned null");
            }

            return settingsScreen;

        } catch (Exception e) {
            LOGGER.severe("Failed to open settings screen: " + e.getMessage());
            return null;
        }
    }

    /**
     * 检查 VideoSettingsBridge 是否已初始化且可用
     *
     * <p>在调用 openSettings() 前应先调用此方法检查状态。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>boolean - true 表示可用</li>
     * </ul>
     *
     * @return true 如果 VideoSettingsBridge 已初始化且可用，false 否则
     */
    public boolean isAvailable() {
        return VideoSettingsBridge.isInitialized();
    }

    /**
     * 检查是否处于兼容模式
     *
     * <p>兼容模式下会使用原版视频设置的扩展方式，
     * 而非 Sodium 的独立设置页面。
     *
     * @return true 如果当前是兼容模式
     */
    public boolean isCompatibleMode() {
        return VideoSettingsBridge.isCompatibleMode();
    }

    /**
     * 获取性能设置处理器
     *
     * <p>用于处理具体的配置项变更事件，
     * 如算法开关、GPU 阈值、运行模式等。
     *
     * @return PerformanceSettingsHandler 性能设置处理器实例
     */
    public PerformanceSettingsHandler getSettingsHandler() {
        return settingsHandler;
    }

    /**
     * 初始化 SettingsCoordinator
     *
     * <p>确保 VideoSettingsBridge 已初始化。
     * 通常在游戏启动时调用一次。
     *
     * @throws IllegalStateException 如果 VideoSettingsBridge 已经初始化过
     */
    public void initialize() {
        if (!VideoSettingsBridge.isInitialized()) {
            try {
                VideoSettingsBridge.initialize();
                LOGGER.info("VideoSettingsBridge initialized via SettingsCoordinator");
            } catch (Exception e) {
                LOGGER.severe("Failed to initialize VideoSettingsBridge: " + e.getMessage());
                throw new RuntimeException("SettingsCoordinator initialization failed", e);
            }
        } else {
            LOGGER.warning("VideoSettingsBridge already initialized, skipping");
        }
    }

    /**
     * 重置 SettingsCoordinator 状态
     *
     * <p>主要用于测试场景，重置单例和内部状态。
     */
    public static void reset() {
        synchronized (SettingsCoordinator.class) {
            instance = null;
        }
        VideoSettingsBridge.reset();
        LOGGER.info("SettingsCoordinator reset complete");
    }
}
