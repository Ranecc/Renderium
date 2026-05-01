// Renderium - ARGB 颜色格式工具类
// 提供颜色空间转换功能

package com.ranecc.renderium.presentation.ui.ui;

/**
 * ARGB 颜色格式工具类。
 *
 * <p>提供 ARGB（Alpha-Red-Green-Blue）格式与 HSV
 * （Hue-Saturation-Value）色彩空间之间的转换功能。
 * 用于 {@link Colors} 类中的颜色调整操作。
 *
 * <h2>颜色格式说明</h2>
 * <ul>
 *   <li><b>ARGB</b>：0xAARRGGBB，32 位整数表示</li>
 *   <li><b>HSV</b>：色调(0-360)、饱和度(0-1)、明度(0-1)</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 将 ARGB 转换为 HSV
 * float[] hsv = ColorARGB.toHSV(0xFF4ECDC4);
 * // hsv[0] = 色调 (0-360)
 * // hsv[1] = 饱和度 (0-1)
 * // hsv[2] = 明度 (0-1)
 *
 * // 从 HSV 创建新颜色（保留原 Alpha）
 * int newColor = ColorARGB.transferAlpha(
 *     ColorARGB.fromHSV(hsv[0], 0.5f, 0.8f),
 *     originalColor
 * );
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class ColorARGB {

    /** 私有构造器防止实例化 */
    private ColorARGB() {
        throw new UnsupportedOperationException("ColorARGB is a utility class");
    }

    /**
     * 将 ARGB 颜色转换为 HSV 色彩空间。
     *
     * <p>返回包含三个元素的浮点数组：
     * <ul>
     *   <li>[0] - 色调 H（Hue）：0.0 ~ 360.0 度</li>
     *   <li>[1] - 饱和度 S（Saturation）：0.0 ~ 1.0</li>
     *   <li>[2] - 明度 V（Value/Brightness）：0.0 ~ 1.0</li>
     * </ul>
     *
     * @param color ARGB 格式颜色值（32 位整数）
     * @return 包含 H、S、V 三个分量的浮点数组（长度为 3）
     */
    public static float[] toHSV(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        float max = Math.max(r, Math.max(g, b)) / 255.0f;
        float min = Math.min(r, Math.min(g, b)) / 255.0f;
        float delta = max - min;

        float h = 0;
        float s = max == 0 ? 0 : delta / max;
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
     * 从 HSV 分量创建 ARGB 颜色。
     *
     * @param h 色调（Hue）：0.0 ~ 360.0 度
     * @param s 饱和度（Saturation）：0.0 ~ 1.0
     * @param v 明度（Value）：0.0 ~ 1.0
     * @return ARGB 格式颜色值（Alpha 通道为 0xFF，完全不透明）
     */
    public static int fromHSV(float h, float s, float v) {
        int r, g, b;

        if (s == 0) {
            r = g = b = (int) (v * 255);
        } else {
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

            r = (int) ((r1 + m) * 255);
            g = (int) ((g1 + m) * 255);
            b = (int) ((b1 + m) * 255);
        }

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 将源颜色的 Alpha 通道转移到目标颜色。
     *
     * <p>保留目标颜色的 RGB 分量，
     * 但使用源颜色的 Alpha 通道值。
     *
     * @param target 目标颜色（提供 RGB 分量）
     * @param source 源颜色（提供 Alpha 分量）
     * @return 合成后的颜色值
     */
    public static int transferAlpha(int target, int source) {
        return (source & 0xFF000000) | (target & 0x00FFFFFF);
    }

    /**
     * 将 ABGR 格式转换为 ARGB 格式。
     *
     * <p>用于兼容 OpenGL 的 ABGR 格式与 Java 的 ARGB 格式。
     *
     * @param a Alpha 通道（0-255）
     * @param r 红色通道（0-255）
     * @param g 绿色通道（0-255）
     * @param b 蓝色通道（0-255）
     * @return ARGB 格式的 32 位整数
     */
    public static int pack(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
