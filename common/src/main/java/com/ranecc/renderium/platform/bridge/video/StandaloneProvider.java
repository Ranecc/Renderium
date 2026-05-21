package com.ranecc.renderium.platform.bridge.video;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;

/**
 * 独立模式视频设置提供者
 *
 * <p>在没有 Sodium 的环境中使用。直接打开原版 VideoSettingsScreen，
 * Mixin 会自动追加 Renderium 选项到原版界面底部。
 *
 * <h3>设计说明：</h3>
 * <ul>
 *   <li>不再创建独立的 RenderiumVideoOptionsScreen</li>
 *   <li>通过 Mixin 注入方式在原版界面追加 Renderium 选项</li>
 *   <li>减少了维护独立 UI 的复杂度</li>
 * </ul>
 */
public final class StandaloneProvider implements VideoSettingsProvider {

    /** 单例实例（无状态，线程安全） */
    public static final StandaloneProvider INSTANCE = new StandaloneProvider();

    private StandaloneProvider() {}

    /**
     * 打开原版视频设置界面
     *
     * <p>直接打开原版 VideoSettingsScreen，
     * Mixin 会自动追加 Renderium 选项到原版界面底部。
     *
     * @param parent 父级 Screen（通常为 OptionsScreen）
     * @return 原版 VideoSettingsScreen 实例
     */
    @Override
    public Screen openSettings(Screen parent) {
        // 直接打开原版 VideoSettingsScreen
        // Mixin 会自动追加 Renderium 选项
        return new VideoSettingsScreen(parent, Minecraft.getInstance(), Minecraft.getInstance().options);
    }

    /**
     * 检查独立模式是否可用
     *
     * <p>独立模式始终可用，作为所有其他提供者的降级选项。
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
