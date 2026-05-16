// Renderium - Reflex 设置标签页
// 提供 NVIDIA Reflex 低延迟配置
// 使用 MCAbstract 抽象层隔离 MC 版本差异

package com.ranecc.renderium.presentation.ui.tabs;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.presentation.ui.MCAbstract;

import com.ranecc.renderium.None;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * Reflex 设置标签页
 *
 * <p>提供以下配置项：
 * <ul>
 *   <li>Reflex 启用/禁用开关</li>
 *   <li>Reflex 模式选择（低延迟/低延迟增强）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public class ReflexSettingsTab extends Screen {

    private final Screen parent;
    private final RenderiumConfig config;
    private Button enableButton;
    private Button modeButton; // 使用 Button 替代 CycleButton

    /** 当前模式索引（用于循环切换）*/
    private int currentModeIndex = 0;

    public ReflexSettingsTab(Screen parent, RenderiumConfig config) {
        super(MCAbstract.text("Reflex Settings"));
        this.parent = parent;
        this.config = config;
        // 初始化模式索引（使用 RenderiumConfig.ReflexMode）
        this.currentModeIndex = getModeIndex(config.getReflexMode());
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;
        int startY = 40;
        int spacing = 30;

        // 启用开关
        enableButton = MCAbstract.buttonBuilder(centerX - 100, startY, 200, 20)
                .text(config.isReflexEnabled() ? "Enabled" : "Disabled")
                .onClick(btn -> toggleEnabled())
                .build();
        addRenderableWidget(enableButton);

        // 模式选择（使用 Button + 循环切换）
        modeButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing, 200, 20)
                .text("Mode: " + getModeName(config.getReflexMode()))
                .onClick(btn -> cycleMode())
                .build();
        addRenderableWidget(modeButton);
    }

    /**
     * 获取模式的显示名称（RenderiumConfig.ReflexMode）
     */
    private String getModeName(RenderiumConfig.ReflexMode mode) {
        return switch (mode) {
            case LOW_LATENCY -> "Low Latency";
            case LOW_LATENCY_BOOST -> "Low Latency Boost";
            case OFF -> "Off";
        };
    }

    /**
     * 获取模式对应的索引
     */
    private int getModeIndex(RenderiumConfig.ReflexMode mode) {
        return switch (mode) {
            case OFF -> 0;
            case LOW_LATENCY -> 1;
            case LOW_LATENCY_BOOST -> 2;
        };
    }

    /**
     * 根据索引获取模式枚举
     */
    private RenderiumConfig.ReflexMode getModeFromIndex(int index) {
        return switch (index % 3) {
            case 0 -> RenderiumConfig.ReflexMode.OFF;
            case 1 -> RenderiumConfig.ReflexMode.LOW_LATENCY;
            default -> RenderiumConfig.ReflexMode.LOW_LATENCY_BOOST;
        };
    }

    /**
     * 循环切换模式（替代 CycleButton 功能）
     */
    private void cycleMode() {
        currentModeIndex = (currentModeIndex + 1) % 3;
        RenderiumConfig.ReflexMode newMode = getModeFromIndex(currentModeIndex);
        config.setReflexMode(newMode);
        if (modeButton != null) {
            modeButton.setMessage(MCAbstract.text("Mode: " + getModeName(newMode)));
        }
    }

    private void toggleEnabled() {
        boolean newState = !config.isReflexEnabled();
        config.setReflexEnabled(newState);
        enableButton.setMessage(MCAbstract.text(newState ? "Enabled" : "Disabled"));
    }
}
