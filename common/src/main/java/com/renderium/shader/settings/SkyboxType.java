// Renderium - Shader 图形设置集成
// SkyboxType - 天空盒类型枚举

package com.renderium.shader.settings;

/**
 * 天空盒类型枚举
 * <p>
 * 定义天空背景的渲染方式。
 *
 * @since 3.0.0
 */
public enum SkyboxType {

    /** 程序化生成天空（基于时间/天气自动计算） */
    PROCEDURAL("renderium.shader.skybox.procedural"),

    /** HDR 全景天空（需要 HDR 纹理资源） */
    HDR("renderium.shader.skybox.hdr"),

    /** 自定义天空盒（使用用户指定的立方体贴图） */
    CUSTOM("renderium.shader.skybox.custom");

    private final String translationKey;

    SkyboxType(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
