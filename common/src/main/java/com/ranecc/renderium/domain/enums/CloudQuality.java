// Renderium - Shader 图形设置集成
// CloudQuality - 云渲染质量枚举

package com.ranecc.renderium.domain.enums;

/**
 * 云渲染质量枚举
 * <p>
 * 控制天空云朵的渲染方式，从完全关闭到体积云渲染。
 *
 * @since 3.0.0
 */
public enum CloudQuality {

    /** 完全禁用云渲染（使用 MC 原生或无云） */
    OFF("renderium.shader.cloud.off"),

    /** 简单平面云 - 性能开销极低 */
    SIMPLE("renderium.shader.cloud.simple"),

    /** 体积云 - 具有光照散射效果的中等开销 */
    VOLUMETRIC("renderium.shader.cloud.volumetric");

    private final String translationKey;

    CloudQuality(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
