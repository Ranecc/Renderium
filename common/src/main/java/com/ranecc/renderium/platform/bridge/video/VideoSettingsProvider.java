package com.ranecc.renderium.platform.bridge.video;

import net.minecraft.client.gui.screens.Screen;

/**
 * 视频设置提供者策略接口
 * <p>
 * 采用策略模式（Strategy Pattern），定义视频设置界面的不同实现策略。
 * 通过此接口，Renderium 可以在运行时根据环境自动切换提供者：
 * <ul>
 *   <li>{@link StandaloneProvider}: 独立模式，完全替换原版 VideoSettingsScreen</li>
 *   <li>{@link SodiumCompatibleProvider}: 兼容模式，追加到第三方模组界面</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>零 Mixin 依赖：此接口及所有实现类禁止 import 任何 Mixin 类</li>
 *   <li>线程安全：所有实现必须保证线程安全</li>
 *   <li>延迟初始化：界面创建应推迟到实际调用时</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │  VideoSettingsBridge (Static Facade)         │
 * │       ↓ getActiveProvider()                  │
 * ├─────────────────────────────────────────────┤
 * │  VideoSettingsProvider (Strategy Interface)  │
 * │       ├─ StandaloneProvider                  │
 * │       └─ SodiumCompatibleProvider           │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * @see VideoSettingsBridge
 * @see StandaloneProvider
 * @see SodiumCompatibleProvider
 * @since 1.0.0
 */
public interface VideoSettingsProvider {

    /**
     * 打开视频设置界面
     * <p>
     * 创建并返回一个 {@link Screen} 实例作为视频设置界面。
     * 不同提供者返回的界面类型和行为不同：
     * <ul>
     *   <li>Standalone: 返回完整的 {@code AbstractRendererSettingsScreen}</li>
     *   <li>Compatible: 返回追加标签页后的第三方界面</li>
     * </ul>
     *
     * @param parent 父级 Screen（通常为 OptionsScreen），用于界面层级管理
     * @return 视频设置界面 Screen 实例；如果当前环境不支持则返回 null
     */
    Screen openSettings(Screen parent);

    /**
     * 检查此提供者是否在当前环境中可用
     * <p>
     * 用于运行时环境检测。例如：
     * <ul>
     *   <li>{@link StandaloneProvider#isAvailable()} 始终返回 true</li>
     *   <li>{@link SodiumCompatibleProvider#isAvailable()} 检测 Sodium 是否已加载</li>
     * </ul>
     *
     * @return true 如果此提供者在当前环境中可用
     */
    boolean isAvailable();

    /**
     * 获取提供者名称
     * <p>
     * 返回用于日志和调试的人类可读名称。
     * 常见值："Standalone"、"Sodium Compatible" 等。
     *
     * @return 提供者名称（非 null，非空字符串）
     */
    String getName();
}
