// Renderium - 剔除设置标签页
// 提供高级剔除优化配置
// 使用 MCAbstract 抽象层隔离 MC 版本差异

package com.ranecc.renderium.presentation.ui.tabs;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.presentation.ui.MCAbstract;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * 剔除设置标签页
 *
 * <p>提供以下配置项：
 * <ul>
 *   <li>遮挡剔除开关</li>
 *   <li>视锥剔除开关</li>
 *   <li>邻居面剔除开关</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public class CullingSettingsTab extends Screen {

    private final Screen parent;
    private final RenderiumConfig config;
    private Button occlusionButton;
    private Button frustumButton;
    private Button backfaceButton;

    public CullingSettingsTab(Screen parent, RenderiumConfig config) {
        super(MCAbstract.text("Culling Settings"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;
        int startY = 40;
        int spacing = 30;

        // 遮挡剔除开关
        occlusionButton = MCAbstract.buttonBuilder(centerX - 100, startY, 200, 20)
                .text(config.isOcclusionCullingEnabled() ? "Occlusion: ON" : "Occlusion: OFF")
                .onClick(btn -> toggleOcclusion())
                .build();
        addRenderableWidget(occlusionButton);

        // 视锥剔除开关
        frustumButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing, 200, 20)
                .text(config.isFrustumCullingEnabled() ? "Frustum: ON" : "Frustum: OFF")
                .onClick(btn -> toggleFrustum())
                .build();
        addRenderableWidget(frustumButton);

        // 背面剔除开关
        backfaceButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 2, 200, 20)
                .text(config.isBackfaceCullingEnabled() ? "Backface: ON" : "Backface: OFF")
                .onClick(btn -> toggleBackface())
                .build();
        addRenderableWidget(backfaceButton);
    }

    private void toggleOcclusion() {
        boolean newState = !config.isOcclusionCullingEnabled();
        config.setOcclusionCullingEnabled(newState);
        occlusionButton.setMessage(MCAbstract.text(newState ? "Occlusion: ON" : "Occlusion: OFF"));
    }

    private void toggleFrustum() {
        boolean newState = !config.isFrustumCullingEnabled();
        config.setFrustumCullingEnabled(newState);
        frustumButton.setMessage(MCAbstract.text(newState ? "Frustum: ON" : "Frustum: OFF"));
    }

    private void toggleBackface() {
        boolean newState = !config.isBackfaceCullingEnabled();
        config.setBackfaceCullingEnabled(newState);
        backfaceButton.setMessage(MCAbstract.text(newState ? "Backface: ON" : "Backface: OFF"));
    }
}
