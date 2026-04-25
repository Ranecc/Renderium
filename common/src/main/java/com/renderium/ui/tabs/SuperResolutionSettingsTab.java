// Renderium - 超分辨率设置标签页
// 提供 DLSS/FSR/XeSS 技术和画质配置
// 使用 MCAbstract 抽象层隔离 MC 版本差异

package com.renderium.ui.tabs;

import com.renderium.config.RenderiumConfig;
import com.renderium.platform.MCAbstract;
import com.renderium.superres.SuperResolutionAdapter;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * 超分辨率设置标签页
 *
 * <p>提供以下配置项：
 * <ul>
 *   <li>超分辨率启用/禁用</li>
 *   <li>技术选择（DLSS/FSR/XeSS/自动）</li>
 *   <li>画质等级（性能/平衡/画质/超高性能）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public class SuperResolutionSettingsTab extends Screen {

    private final Screen parent;
    private final RenderiumConfig config;
    private Button enableButton;
    private Button technologyButton; // Button 替代 CycleButton
    private Button qualityButton;    // Button 替代 CycleButton

    /** 当前技术索引（4 种：AUTO/DLSS/FSR/XESS）*/
    private int currentTechIndex = 0;
    /** 当前画质索引（4 种：PERFORMANCE/BALANCED/QUALITY/ULTRA_PERFORMANCE）*/
    private int currentQualityIndex = 0;

    public SuperResolutionSettingsTab(Screen parent, RenderiumConfig config) {
        super(MCAbstract.text("Super Resolution"));
        this.parent = parent;
        this.config = config;
        this.currentTechIndex = getTechIndex(config.getTechnology());
        this.currentQualityIndex = getQualityIndex(config.getQuality());
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2 + 60;
        int startY = 40;
        int spacing = 30;

        // 启用开关
        enableButton = MCAbstract.buttonBuilder(centerX - 100, startY, 200, 20)
                .text(config.isSuperResolutionEnabled() ? "Enabled" : "Disabled")
                .onClick(btn -> toggleEnabled())
                .build();
        addRenderableWidget(enableButton);

        // 技术选择（Button + 循环切换）
        technologyButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing, 200, 20)
                .text("Tech: " + getTechName(config.getTechnology()))
                .onClick(btn -> cycleTechnology())
                .build();
        addRenderableWidget(technologyButton);

        // 画质选择（Button + 循环切换）
        qualityButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 2, 200, 20)
                .text("Quality: " + getQualityName(config.getQuality()))
                .onClick(btn -> cycleQuality())
                .build();
        addRenderableWidget(qualityButton);
    }

    // ==================== 技术相关方法 ====================

    private String getTechName(SuperResolutionAdapter.Technology tech) {
        return switch (tech) {
            case AUTO -> "Auto";
            case DLSS -> "DLSS";
            case FSR -> "FSR";
            case XESS -> "XeSS";
            default -> tech.name(); // 覆盖所有可能的枚举值（包括 NATIVE）
        };
    }

    private int getTechIndex(SuperResolutionAdapter.Technology tech) {
        return switch (tech) {
            case AUTO -> 0;
            case DLSS -> 1;
            case FSR -> 2;
            case XESS -> 3;
            default -> 0; // 默认使用 AUTO
        };
    }

    private SuperResolutionAdapter.Technology getTechFromIndex(int index) {
        return switch (index % 4) {
            case 0 -> SuperResolutionAdapter.Technology.AUTO;
            case 1 -> SuperResolutionAdapter.Technology.DLSS;
            case 2 -> SuperResolutionAdapter.Technology.FSR;
            default -> SuperResolutionAdapter.Technology.XESS;
        };
    }

    private void cycleTechnology() {
        currentTechIndex = (currentTechIndex + 1) % 4;
        SuperResolutionAdapter.Technology newTech = getTechFromIndex(currentTechIndex);
        config.setTechnology(newTech);
        if (technologyButton != null) {
            technologyButton.setMessage(MCAbstract.text("Tech: " + getTechName(newTech)));
        }
    }

    // ==================== 画质相关方法 ====================

    private String getQualityName(SuperResolutionAdapter.Quality quality) {
        return switch (quality) {
            case PERFORMANCE -> "Performance";
            case BALANCED -> "Balanced";
            case QUALITY -> "Quality";
            case ULTRA_PERFORMANCE -> "Ultra Perf";
            default -> quality.name(); // 覆盖所有可能的枚举值
        };
    }

    private int getQualityIndex(SuperResolutionAdapter.Quality quality) {
        return switch (quality) {
            case PERFORMANCE -> 0;
            case BALANCED -> 1;
            case QUALITY -> 2;
            case ULTRA_PERFORMANCE -> 3;
            default -> 0; // 默认使用 PERFORMANCE
        };
    }

    private SuperResolutionAdapter.Quality getQualityFromIndex(int index) {
        return switch (index % 4) {
            case 0 -> SuperResolutionAdapter.Quality.PERFORMANCE;
            case 1 -> SuperResolutionAdapter.Quality.BALANCED;
            case 2 -> SuperResolutionAdapter.Quality.QUALITY;
            default -> SuperResolutionAdapter.Quality.ULTRA_PERFORMANCE;
        };
    }

    private void cycleQuality() {
        currentQualityIndex = (currentQualityIndex + 1) % 4;
        SuperResolutionAdapter.Quality newQuality = getQualityFromIndex(currentQualityIndex);
        config.setQuality(newQuality);
        if (qualityButton != null) {
            qualityButton.setMessage(MCAbstract.text("Quality: " + getQualityName(newQuality)));
        }
    }

    private void toggleEnabled() {
        boolean newState = !config.isSuperResolutionEnabled();
        config.setSuperResolutionEnabled(newState);
        enableButton.setMessage(MCAbstract.text(newState ? "Enabled" : "Disabled"));
    }
}
