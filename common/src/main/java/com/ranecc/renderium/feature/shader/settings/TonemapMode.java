// Renderium - Shader 图形设置集成
// TonemapMode - 色调映射模式枚举

package com.ranecc.renderium.feature.shader.settings;

/**
 * 色调映射 (Tonemap) 模式枚举
 * <p>
 * 控制高动态范围 (HDR) 到显示器色彩空间的映射方式。
 * 不同算法产生不同的视觉风格。
 *
 * <h3>各模式视觉效果：</h3>
 * <ul>
 *   <li><b>OFF</b> - 无色调映射，可能导致过曝区域细节丢失</li>
 *   <li><b>ACES</b> - 电影工业标准，自然且富有电影感</li>
 *   <li><b>FILMIC</b> - 胶片风格，高对比度 + 轻微去饱和</li>
 *   <li><b>REINHARD</b> - 经典算法，平滑过渡但可能偏灰</li>
 * </ul>
 *
 * @since 3.0.0
 */
public enum TonemapMode {

    /** 禁用色调映射 */
    OFF("renderium.shader.tonemap.off"),

    /** ACES 电影曲线 - 推荐用于日常游戏 */
    ACES("renderium.shader.tonemap.aces"),

    /** 胶片风格 - 高对比度，适合截图 */
    FILMIC("renderium.shader.tonemap.filmic"),

    /** Reinhard 经典算法 - 平滑过渡 */
    REINHARD("renderium.shader.tonemap.reinhard");

    private final String translationKey;

    TonemapMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
