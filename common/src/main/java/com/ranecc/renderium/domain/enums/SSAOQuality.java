// Renderium - Shader 图形设置集成
// SSAOQuality - 环境光遮蔽质量枚举

package com.ranecc.renderium.domain.enums;

/**
 * 屏幕空间环境光遮蔽 (SSAO) 质量枚举
 * <p>
 * SSAO 模拟环境光在角落和缝隙中的遮挡效果，
 * 让场景看起来更有深度感和真实感。
 *
 * @since 3.0.0
 */
public enum SSAOQuality {

    /** 禁用 SSAO */
    OFF("renderium.shader.ssao.off"),

    /** 低质量：少量采样，轻微性能开销 */
    LOW("renderium.shader.ssao.low"),

    /** 高质量：更多采样，更精确的遮蔽效果 */
    HIGH("renderium.shader.ssao.high"),

    /** 超高质量：最高采样数 + 模糊后处理 */
    ULTRA("renderium.shader.ssao.ultra");

    private final String translationKey;

    SSAOQuality(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
