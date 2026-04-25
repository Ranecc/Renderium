// Renderium - 颜色转换工具类
// 提供 ARGB/HSV 颜色空间转换功能

package com.renderium.ui.widgets.options;

/**
 * 颜色转换辅助工具。
 *
 * <p>提供 ARGB 和 HSV 颜色空间之间的相互转换功能，
 * 用于 UI 主题系统的动态颜色计算。
 *
 * <h2>支持的格式</h2>
 * <ul>
 *   <li><b>ARGB</b>：0xAARRGGBB（32 位整数）</li>
 *   <li><b>HSV</b>：[H, S, V] 浮点数组（H: 0-360, S: 0-1, V: 0-1）</li>
 * </ul>
 *
 * <h2>线程安全性</h2>
 * <p>所有方法均为纯函数，无状态，天然线程安全。
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class ColorHelper {

    /** 私有构造器 */
    private ColorHelper() {
        throw new UnsupportedOperationException("ColorHelper is a utility class");
    }

    /**
     * 将 ARGB 颜色转换为 HSV 数组。
     *
     * @param argb ARGB 格式颜色值
     * @return     float[3] 数组：[H(0-360), S(0-1), V(0-1)]
     */
    public static float[] toHSV(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        float max = Math.max(r, Math.max(g, b)) / 255.0f;
        float min = Math.min(r, Math.min(g, b)) / 255.0f;
        float delta = max - min;

        float h = 0;
        float s = (max == 0) ? 0 : delta / max;
        float v = max;

        if (delta != 0) {
            if (max == r / 255.0f) {
                h = ((g / 255.0f - b / 255.0f) / delta) % 6;
            } else if (max == g / 255.0f) {
                h = (b / 255.0f - r / 255.0f) / delta + 2;
            } else {
                h = (r / 255.0f - g / 255.0f) / delta + 4;
            }
            h *= 60;
            if (h < 0) h += 360;
        }

        return new float[]{h, s, v};
    }

    /**
     * 将 HSV 值转换为 ARGB 颜色。
     *
     * @param h 色相（0-360）
     * @param s 饱和度（0-1）
     * @param v 明度（0-1）
     * @return  ARGB 格式颜色值（Alpha=0xFF）
     */
    public static int fromHSV(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = v - c;

        float r1, g1, b1;
        if (h < 60) { r1 = c; g1 = x; b1 = 0; }
        else if (h < 120) { r1 = x; g1 = c; b1 = 0; }
        else if (h < 180) { r1 = 0; g1 = c; b1 = x; }
        else if (h < 240) { r1 = 0; g1 = x; b1 = c; }
        else if (h < 300) { r1 = x; g1 = 0; b1 = c; }
        else { r1 = c; g1 = 0; b1 = x; }

        int r = Math.round((r1 + m) * 255);
        int g = Math.round((g1 + m) * 255);
        int b = Math.round((b1 + m) * 255);

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 将源颜色的 Alpha 通道转移到目标颜色。
     *
     * @param target 目标颜色（RGB 分量保留）
     * @param source 源颜色（Alpha 分量提取）
     * @return       合并后的颜色
     */
    public static int transferAlpha(int target, int source) {
        return (source & 0xFF000000) | (target & 0x00FFFFFF);
    }
}
