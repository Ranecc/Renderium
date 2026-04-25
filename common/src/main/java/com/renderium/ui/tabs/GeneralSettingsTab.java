// Renderium - 常规设置标签页
// 提供运行模式切换、基础开关等常规配置
// 使用 MCAbstract 抽象层隔离 MC 版本差异

package com.renderium.ui.tabs;

import com.renderium.config.RenderiumConfig;
import com.renderium.core.RenderiumDualModeManager;
import com.renderium.core.RenderiumMode;
import com.renderium.platform.MCAbstract;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * 常规设置标签页
 *
 * <p>提供以下配置项：
 * <ul>
 *   <li>运行模式切换（兼容模式/狂暴模式）</li>
 *   <li>Renderium 启用/禁用开关</li>
 *   <li>调试模式开关</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public class GeneralSettingsTab extends Screen {

    /** 父屏幕引用 */
    private final Screen parent;

    /** 配置实例 */
    private final RenderiumConfig config;

    /** 双模式管理器 */
    private final RenderiumDualModeManager modeManager;

    /** 运行模式选择按钮（使用 Button 替代 CycleButton）*/
    private Button modeButton;

    /** 启用开关按钮 */
    private Button enableButton;

    /** 调试模式开关按钮 */
    private Button debugButton;

    /**
     * 创建常规设置标签页
     *
     * @param parent 父屏幕
     * @param config 配置实例
     */
    public GeneralSettingsTab(Screen parent, RenderiumConfig config) {
        super(MCAbstract.text("General Settings"));
        this.parent = parent;
        this.config = config;
        this.modeManager = RenderiumDualModeManager.getInstance();
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60; // 偏移以补偿左侧标签页
        int startY = 40;
        int spacing = 30;

        // 运行模式选择（使用 MCAbstract.buttonBuilder 替代 CycleButton）
        // 通过按钮文本显示当前模式，点击循环切换
        String currentModeName = modeManager.getCurrentMode().getDisplayName();
        modeButton = MCAbstract.buttonBuilder(centerX - 100, startY, 200, 20)
                .text("Mode: " + currentModeName)
                .onClick(btn -> cycleMode())
                .build();
        addRenderableWidget(modeButton);

        // 启用开关
        enableButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing, 200, 20)
                .text(config.isEnabled() ? "Enabled" : "Disabled")
                .onClick(btn -> toggleEnabled())
                .build();
        addRenderableWidget(enableButton);

        // 调试模式开关
        debugButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 2, 200, 20)
                .text(config.isDebugMode() ? "Debug: ON" : "Debug: OFF")
                .onClick(btn -> toggleDebugMode())
                .build();
        addRenderableWidget(debugButton);
    }

    /**
     * 循环切换运行模式（替代 CycleButton 的功能）
     */
    private void cycleMode() {
        RenderiumMode current = modeManager.getCurrentMode();
        RenderiumMode next = (current == RenderiumMode.COMPATIBILITY)
                ? RenderiumMode.AGGRESSIVE
                : RenderiumMode.COMPATIBILITY;
        onModeChanged(next);
    }

    /**
     * 运行模式切换回调
     *
     * @param newMode 新的运行模式
     */
    private void onModeChanged(RenderiumMode newMode) {
        // modeManager.setMode(newMode) // TODO: implement;
        config.setMode(newMode);
        // 更新按钮文本显示新模式
        if (modeButton != null) {
            modeButton.setMessage(MCAbstract.text("Mode: " + newMode.getDisplayName()));
        }
    }

    /**
     * 切换启用状态
     */
    private void toggleEnabled() {
        boolean newState = !config.isEnabled();
        config.setEnabled(newState);
        enableButton.setMessage(MCAbstract.text(newState ? "Enabled" : "Disabled"));
    }

    /**
     * 切换调试模式
     */
    private void toggleDebugMode() {
        boolean newState = !config.isDebugMode();
        config.setDebugMode(newState);
        debugButton.setMessage(MCAbstract.text(newState ? "Debug: ON" : "Debug: OFF"));
    }
}
