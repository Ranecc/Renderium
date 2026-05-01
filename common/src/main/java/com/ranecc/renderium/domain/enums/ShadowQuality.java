// Renderium - Shader 图形设置集成
// ShadowQuality - 阴影质量枚举
//
// 功能：
//   1. 定义 5 级阴影质量（对应不同级联数和分辨率）
//   2. 每级有清晰的性能/画质权衡说明

package com.ranecc.renderium.domain.enums;

/**
 * 阴影贴图质量枚举
 * <p>
 * 控制阴影贴图的分辨率和级联数量。
 * 更高质量 = 更多 GPU 显存 + 更多计算开销，但阴影边缘更柔和、距离更远。
 *
 * <h3>各级别技术参数：</h3>
 * <table border="1">
 *   <tr><th>级别</th><th>分辨率</th><th>级联数</th><th>显存</th></tr>
 *   <tr><td>OFF</td><td>-</td><td>0</td><td>0 MB</td></tr>
 *   <tr><td>LOW</td><td>1024</td><td>2</td><td>~8 MB</td></tr>
 *   <tr><td>MEDIUM</td><td>2048</td><td>3</td><td>~48 MB</td></tr>
 *   <tr><td>HIGH</td><td>4096</td><td>4</td><td>~192 MB</td></tr>
 *   <tr><td>ULTRA</td><td>8192</td><td>4</td><td>~768 MB</td></tr>
 * </table>
 *
 * @since 3.0.0
 */
public enum ShadowQuality {

    /** 完全禁用阴影 */
    OFF("renderium.shader.shadow.off"),

    /** 低质量：1024px, 2 级联，适合低端显卡 */
    LOW("renderium.shader.shadow.low"),

    /** 中等质量：2048px, 3 级联，推荐大多数用户 */
    MEDIUM("renderium.shader.shadow.medium"),

    /** 高质量：4096px, 4 级联，适合中高端显卡 */
    HIGH("renderium.shader.shadow.high"),

    /** 极致质量：8192px, 4 级联，需要 8GB+ 显存 */
    ULTRA("renderium.shader.shadow.ultra");

    private final String translationKey;

    ShadowQuality(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    /**
     * @return int - 阴影贴图边长（像素），OFF 返回 0
     */
    public int getResolution() {
        return switch (this) {
            case OFF -> 0;
            case LOW -> 1024;
            case MEDIUM -> 2048;
            case HIGH -> 4096;
            case ULTRA -> 8192;
        };
    }

    /**
     * @return int - 级联数量
     */
    public int getCascadeCount() {
        return switch (this) {
            case OFF -> 0;
            case LOW -> 2;
            default -> 4; // MEDIUM/HIGH/ULTRA 均使用 4 级联
        };
    }
}
