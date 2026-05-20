package com.ranecc.renderium.platform.bridge.video;

import net.minecraft.client.gui.screens.Screen;

/**
 * 独立模式视频设置提供者
 * <p>
 * 作为 {@link VideoSettingsProvider} 的默认实现，提供完全独立的视频设置界面。
 * 在没有第三方渲染模组（如 Sodium）的环境中使用此提供者。
 * <p>
 * 行为特征：
 * <ul>
 *   <li>完全替换原版 VideoSettingsScreen</li>
 *   <li>通过 OptionsScreenRedirectMixin 拦截"视频设置"按钮</li>
 *   <li>返回完整的 AbstractRendererSettingsScreen 实例</li>
 * </ul>
 *
 * <h3>使用场景：</h3>
 * <pre>
 * 环境检测流程：
 * 1. 检测 Sodium 是否存在
 *    ├── 存在 → 使用 SodiumCompatibleProvider
 *    └── 不存在（默认）→ 使用 StandaloneProvider（本类）
 * </pre>
 *
 * @see VideoSettingsProvider
 * @see VideoSettingsBridge#getActiveProvider()
 * @since 1.0.0
 */
public final class StandaloneProvider implements VideoSettingsProvider {

    /** 单例实例（无状态，线程安全） */
    public static final StandaloneProvider INSTANCE = new StandaloneProvider();

    private StandaloneProvider() {}

    /**
     * 打开独立模式的视频设置界面
     * <p>
     * 创建并返回完整的 {@code AbstractRendererSettingsScreen} 实例。
     * 此界面将完全替换原版 VideoSettingsScreen，包含：
     * <ul>
     *   <li>左侧页面导航栏（PageListWidget）</li>
     *   <li>顶部搜索栏（SearchWidget）</li>
     *   <li>主区域选项列表（OptionListWidget）</li>
     *   <li>底部操作按钮（Apply/Undo/Done）</li>
     * </ul>
     *
     * @param parent 父级 Screen（通常为 OptionsScreen）
     * @return AbstractRendererSettingsScreen 实例；当前阶段返回 null（Task 3.1 实现）
     */
    @Override
    public Screen openSettings(Screen parent) {
        try {
            var screenClass = Class.forName("com.ranecc.renderium.presentation.ui.RenderiumSettingsScreen");
            var constructor = screenClass.getConstructor(Screen.class);
            return (Screen) constructor.newInstance(parent);
        } catch (Exception e) {
            return parent;
        }
    }

    /**
     * 检查独立模式是否可用
     * <p>
     * 独立模式始终可用，作为所有其他提供者的降级选项。
     *
     * @return 始终返回 true
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * 获取提供者名称
     *
     * @return "Standalone"（固定值）
     */
    @Override
    public String getName() {
        return "Standalone";
    }
}
