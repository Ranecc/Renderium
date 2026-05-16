// Renderium - 超分辨率设置标签页
// 提供 DLSS/FSR/XeSS 技术和画质配置
// 使用 MCAbstract 抽象层隔离 MC 版本差异

package com.ranecc.renderium.presentation.ui.tabs;

import com.ranecc.renderium.domain.enums.QualityLevel;
import com.ranecc.renderium.domain.enums.SRTechnology;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.presentation.ui.MCAbstract;

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
        this.currentTechIndex = getTechIndex(config.getSrTechnology());
        this.currentQualityIndex = getQualityIndex(config.getQualityLevel());
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
                .text("Tech: " + getTechName(config.getSrTechnology()))
                .onClick(btn -> cycleTechnology())
                .build();
        addRenderableWidget(technologyButton);

        // 画质选择（Button + 循环切换）
        qualityButton = MCAbstract.buttonBuilder(centerX - 100, startY + spacing * 2, 200, 20)
                .text("Quality: " + getQualityName(config.getQualityLevel()))
                .onClick(btn -> cycleQuality())
                .build();
        addRenderableWidget(qualityButton);
    }

    // ==================== 技术相关方法 ====================

    private String getTechName(SRTechnology tech) {
        return switch (tech) {
            case AUTO -> "Auto";
            case DLSS -> "DLSS";
            case FSR -> "FSR";
            case CAS -> "CAS";
            case IESMGU -> "Intel ESG";
            case NATIVE -> "Native";
        };
    }

    private int getTechIndex(SRTechnology tech) {
        return switch (tech) {
            case AUTO -> 0;
            case DLSS -> 1;
            case FSR -> 2;
            case CAS -> 3;
            case IESMGU -> 3;
            case NATIVE -> 0;
        };
    }

    private SRTechnology getTechFromIndex(int index) {
        return switch (index % 4) {
            case 0 -> SRTechnology.AUTO;
            case 1 -> SRTechnology.DLSS;
            case 2 -> SRTechnology.FSR;
            default -> SRTechnology.CAS;
        };
    }

    private void cycleTechnology() {
        currentTechIndex = (currentTechIndex + 1) % 4;
        SRTechnology newTech = getTechFromIndex(currentTechIndex);
        config.setSrTechnology(newTech);
        if (technologyButton != null) {
            technologyButton.setMessage(MCAbstract.text("Tech: " + getTechName(newTech)));
        }
    }

    // ==================== 画质相关方法 ====================

    private String getQualityName(QualityLevel quality) {
        return switch (quality) {
            case LOW -> "Low";
            case MEDIUM -> "Medium";
            case HIGH -> "High";
            case ULTRA -> "Ultra";
        };
    }

    private int getQualityIndex(QualityLevel quality) {
        return switch (quality) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case ULTRA -> 3;
        };
    }

    private QualityLevel getQualityFromIndex(int index) {
        QualityLevel[] values = QualityLevel.values();
        return values[index % values.length];
    }

    private void cycleQuality() {
        QualityLevel[] values = QualityLevel.values();
        currentQualityIndex = (currentQualityIndex + 1) % values.length;
        QualityLevel newQuality = getQualityFromIndex(currentQualityIndex);
        config.setQualityLevel(newQuality);
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
