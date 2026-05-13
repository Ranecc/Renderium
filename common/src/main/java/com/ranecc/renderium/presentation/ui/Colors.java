// Renderium - UI 颜色常量定义
// 参考 Sodium 的 Colors 类，使用中性命名

package com.ranecc.renderium.presentation.ui;

import net.minecraft.util.Mth;

/**
 * Renderium UI 颜色常量定义。
 *
 * <p>集中管理所有界面组件使用的颜色值，
 * 采用 ARGB 格式（0xAARRGGBB），确保视觉一致性。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code Colors} 常量类设计，
 * 使用 Renderium 主题色（青色调）替换 Sodium 原有的粉紫色调。
 *
 * <h2>颜色分类</h2>
 * <ul>
 *   <li><b>主题色</b>：THEME, THEME_LIGHTER, THEME_DARKER（用于强调和选中状态）</li>
 *   <li><b>前景色</b>：FOREGROUND, FOREGROUND_DISABLED（文本颜色）</li>
 *   <li><b>背景色</b>：BACKGROUND_* 系列（各种透明度的黑色背景）</li>
 *   <li><b>边框色</b>：BUTTON_BORDER（组件边框）</li>
 * </ul>
 *
 * <h3>颜色调整工具方法</h3>
 * <p>提供 {@link #darken(int)}、{@link #lighten(int)} 等方法，
 * 用于动态生成颜色的深浅变体。
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class Colors {

    /** 私有构造器防止实例化（纯常量类） */
    private Colors() {
        throw new UnsupportedOperationException("Colors is a utility class and cannot be instantiated");
    }

    // ==================== 主题色（青色调） ====================

    /**
     * 主主题色（青绿色）。
     * <p>用于选中状态指示器、高亮边框等强调元素。
     */
    public static final int THEME = 0xFF4ECDC4;

    /**
     * 主题色浅变体。
     * <p>用于悬停状态、次要强调元素。
     */
    public static final int THEME_LIGHTER = 0xFF7FDED8;

    /**
     * 主题色深变体。
     * <p>用于禁用状态的强调元素。
     */
    public static final int THEME_DARKER = 0xFF2A9D8F;

    // ==================== 前景色（文本） ====================

    /**
     * 默认前景色（白色）。
     * <p>用于正常状态的文本和图标。
     */
    public static final int FOREGROUND = 0xFFFFFFFF;

    /**
     * 禁用状态前景色（灰色）。
     * <p>用于禁用组件的文本显示。
     */
    public static final int FOREGROUND_DISABLED = 0xFFAAAAAA;

    // ==================== 背景色（半透明黑色） ====================

    /**
     * 浅背景色（低不透明度）。
     * <p>用于轻微的背景区分。
     */
    public static final int BACKGROUND_LIGHT = 0x40000000;

    /**
     * 中等背景色（中等不透明度）。
     * <p>用于分组标题背景等。
     */
    public static final int BACKGROUND_MEDIUM = 0x60000000;

    /**
     * 悬停背景色。
     * <p>用于鼠标悬停时的背景高亮。
     */
    public static final int BACKGROUND_HOVER = 0xE0000000;

    /**
     * 覆盖层背景色（高不透明度）。
     * <p>用于模态对话框、下拉菜单等覆盖层。
     */
    public static final int BACKGROUND_OVERLAY = 0xEA000000;

    /**
     * 默认背景色。
     * <p>用于普通面板、容器等标准背景。
     */
    public static final int BACKGROUND_DEFAULT = 0x90000000;

    /**
     * 深色背景色。
     * <p>用于需要更强对比的区域。
     */
    public static final int BACKGROUND_DARKER = 0xB0000000;

    /**
     * 高亮背景色（极淡的白色）。
     * <p>用于按钮悬停等微妙的高亮效果。
     */
    public static final int BACKGROUND_HIGHLIGHT = 0x08FFFFFF;

    // ==================== 边框色 ====================

    /**
     * 按钮边框色（半透明青色）。
     * <p>用于聚焦状态或需要边框的组件。
     */
    public static final int BUTTON_BORDER = 0x804ECDC4;

    // ==================== 颜色调整常量 ====================

    /** 变亮因子（正值增加亮度） */
    private static final float LIGHTEN_FACTOR = 0.3f;

    /** 变暗因子（负值降低亮度） */
    private static final float DARKEN_FACTOR = -0.23f;

    // ==================== 颜色调整工具方法 ====================

    /**
     * 将指定颜色变暗。
     *
     * <p>通过降低 HSV 亮度通道来实现，保持色相和饱和度不变。
     *
     * @param color 原始颜色（ARGB 格式）
     * @return 变暗后的新颜色值
     *
     * @see #adjust(int, float)
     */
    public static int darken(int color) {
        return adjust(color, DARKEN_FACTOR);
    }

    /**
     * 将指定颜色变亮。
     *
     * <p>通过提高 HSV 亮度通道来实现，保持色相和饱和度不变。
     *
     * @param color 原始颜色（ARGB 格式）
     * @return 变亮后的新颜色值
     *
     * @see #adjust(int, float)
     */
    public static int lighten(int color) {
        return adjust(color, LIGHTEN_FACTOR);
    }

    /**
     * 根据指定因子调整颜色亮度。
     *
     * <p>在 HSV 色彩空间中操作：
     * <ul>
     *   <li>正因子 → 提高亮度（变亮）</li>
     *   <li>负因子 → 降低亮度（变暗）</li>
     * </ul>
     * 同时会适当调整饱和度以避免过饱和或完全去饱和。
     *
     * <h4>算法说明</h4>
     * <pre>
     * 1. 将 ARGB 转换为 HSV
     * 2. 调整饱和度：S' = clamp(S × (1 - |factor|), 0, 1)
     * 3. 调整亮度：V' = clamp(V × (1 + factor), 0, 1)
     * 4. 转换回 ARGB，保留原始 Alpha 通道
     * </pre>
     *
     * @param color  原始颜色（ARGB 格式）
     * @param factor 亮度调整因子（-1.0 ~ 1.0）
     * @return 调整后的新颜色值
     */
    public static int adjust(int color, float factor) {
        float[] hsv = ColorARGB.toHSV(color);
        var s = Mth.clamp(hsv[1] * (1 - Math.abs(factor)), 0, 1);
        var v = Mth.clamp(hsv[2] * (1 + factor), 0, 1);
        return ColorARGB.transferAlpha(ColorARGB.fromHSV(hsv[0], s, v), color);
    }

    /**
     * 约束颜色的饱和度和亮度最小值。
     *
     * <p>确保颜色在暗背景下仍然可见，
     * 避免因过度变暗而导致的可读性问题。
     *
     * @param color          原始颜色（ARGB 格式）
     * @param minSaturation 最小饱和度（0.0 ~ 1.0）
     * @param minBrightness  最小亮度（0.0 ~ 1.0）
     * @return 约束后的颜色值
     */
    public static int constrainColorHSV(int color, float minSaturation, float minBrightness) {
        float[] hsv = ColorARGB.toHSV(color);
        hsv[1] = Math.max(hsv[1], minSaturation);
        hsv[2] = Math.max(hsv[2], minBrightness);
        return ColorARGB.fromHSV(hsv[0], hsv[1], hsv[2]);
    }
}
