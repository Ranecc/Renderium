// Renderium - 帧生成设置标签页
// 提供 DLSS-FG/FSR-FG 配置
// 使用 MCAbstract 抽象层隔离 MC 版本差异

package com.ranecc.renderium.presentation.ui.tabs;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.tech.framegen.FrameGenMode;
import com.ranecc.renderium.presentation.ui.MCAbstract;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * 帧生成设置标签页
 *
 * <p>提供以下配置项：
 * <ul>
 *   <li>帧生成启用/禁用</li>
 *   <li>帧生成模式（DLSS/FSR）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public class FrameGenerationSettingsTab extends Screen {

    private final Screen parent;
    private final RenderiumConfig config;
    private Button enableButton;
    private Button modeButton; // Button 替代 CycleButton

    /** 当前模式索引（2 种：DLSS/FSR）*/
    private int currentModeIndex = 0;

    public FrameGenerationSettingsTab(Screen parent, RenderiumConfig config) {
        super(MCAbstract.text("Frame Generation"));
        this.parent = parent;
        this.config = config;
        this.currentModeIndex = (config.getFrameGenMode() == FrameGenMode.DLSS) ? 0 : 1;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;
        int startY = 40;
        int spacing = 30;

        // 启用开关
        enableButton = MCAbstract.buttonBuilder(centerX - 100, startY, 200, 20)
                .text(config.isFrameGenerationEnabled() ? "Enabled" : "Disabled")
                .onClick(btn -> toggleEnabled())
                .build();
        addRenderableWidget(enableButton);

        // 模式选择（Button + 循环切换）
        modeButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing, 200, 20)
                .text("Mode: " + getModeName(config.getFrameGenMode()))
                .onClick(btn -> cycleMode())
                .build();
        addRenderableWidget(modeButton);
    }

    private String getModeName(FrameGenMode mode) {
        return switch (mode) {
            case DLSS -> "DLSS";
            case FSR -> "FSR";
            default -> mode.name();
        };
    }

    /**
     * 循环切换帧生成模式（替代 CycleButton 功能）
     */
    private void cycleMode() {
        currentModeIndex = (currentModeIndex + 1) % 2;
        FrameGenMode newMode = (currentModeIndex == 0) ? FrameGenMode.DLSS : FrameGenMode.FSR;
        config.setFrameGenMode(newMode);
        if (modeButton != null) {
            modeButton.setMessage(MCAbstract.text("Mode: " + getModeName(newMode)));
        }
    }

    private void toggleEnabled() {
        boolean newState = !config.isFrameGenerationEnabled();
        config.setFrameGenerationEnabled(newState);
        enableButton.setMessage(MCAbstract.text(newState ? "Enabled" : "Disabled"));
    }
}
