// Renderium - Shader 图形设置集成
// ShaderPreset - 光影预设定义
//
// 功能：
//   1. 定义 5 个预设级别：关闭 → 极致
//   2. 每个预设对应一组默认参数值
//   3. 支持一键切换所有 Shader 参数
//
// 预设说明：
//   OFF        - 完全禁用 Shader 后处理（仅 MC 原生渲染）
//   MINIMAL    - 最小化开销：仅基础色调映射
//   BALANCED   - 平衡模式：阴影 + SSAO + 泛光（推荐大多数用户）
//   CINEMATIC  - 电影级：高质量 PBR + 体积光 + 反射
//   ULTRA      - 极致画质：全功能开启（需要高端 GPU）

package com.ranecc.renderium.domain.enums;

/**
 * 光影预设枚举
 * <p>
 * 提供一键式画质切换，每个预设对应一组经过调优的默认参数。
 * 用户选择预设后，所有相关参数会自动更新为该预设的推荐值。
 *
 * <h3>性能影响参考（RTX 3070 @ 1080p）：</h3>
 * <table border="1">
 *   <tr><th>预设</th><th>预期 FPS</th><th>显存占用</th></tr>
 *   <tr><td>OFF</td><td>> 500</td><td>~800 MB</td></tr>
 *   <tr><td>MINIMAL</td><td>300-400</td><td>~900 MB</td></tr>
 *   <tr><td>BALANCED</td><td>120-200</td><td>~1.2 GB</td></tr>
 *   <tr><td>CINEMATIC</td><td>60-100</td><td>~1.8 GB</td></tr>
 *   <tr><td>ULTRA</td><td>30-60</td><td>~2.5 GB</td></tr>
 * </table>
 *
 * @since 3.0.0
 */
public enum ShaderPreset {

    /** 关闭所有 Shader 后处理，使用 MC 原生渲染 */
    OFF("renderium.shader.preset.off"),

    /** 最轻量模式：仅启用基础色调映射，几乎无性能损失 */
    MINIMAL("renderium.shader.preset.minimal"),

    /** 推荐模式：阴影 + 环境遮蔽 + 泛光，画质与性能的最佳平衡点 */
    BALANCED("renderium.shader.preset.balanced"),

    /** 电影级画质：PBR 材质 + 体积光 + 屏幕反射，适合截图/录像 */
    CINEMATIC("renderium.shader.preset.cinematic"),

    /** 极致画质：开启所有特效包括光线追踪，需要高端 GPU */
    ULTRA("renderium.shader.preset.ultra");

    private final String translationKey;

    ShaderPreset(String translationKey) {
        this.translationKey = translationKey;
    }

    /**
     * @return String - i18n 翻译键名
     */
    public String getTranslationKey() {
        return translationKey;
    }
}
