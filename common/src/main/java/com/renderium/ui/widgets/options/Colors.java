// Renderium - UI 颜色常量定义
// 参考 Sodium 的 Colors 类，使用中性命名

package com.renderium.ui.widgets.options;

/**
 * UI 颜色常量定义。
 *
 * <p>集中管理渲染器设置界面中使用的所有颜色值，
 * 采用 ARGB 格式（0xAARRGGBB），确保与 Minecraft GUI 系统兼容。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code Colors} 类设计，
 * 使用 Renderium 中性命名，调整主题色以匹配项目品牌。
 *
 * <h2>颜色分类</h2>
 * <ul>
 *   <li><b>主题色</b>：THEME, THEME_LIGHTER, THEME_DARKER - 品牌主色调</li>
 *   <li><b>前景色</b>：FOREGROUND, FOREGROUND_DISABLED - 文本和控件颜色</li>
 *   <li><b>背景色</b>：BACKGROUND_* 系列 - 各状态背景</li>
 *   <li><b>按钮色</b>：BUTTON_BORDER - 按钮边框</li>
 * </ul>
 *
 * <h2>线程安全性</h2>
 * <p>所有字段均为 static final，天然线程安全。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see ColorTheme
 */
public final class Colors {

    /** 私有构造器防止实例化 */
    private Colors() {
        throw new UnsupportedOperationException("Colors is a utility class");
    }

    // ==================== 主题色 ====================

    /**
     * 主主题色（青绿色）。
     * <p>用于控件激活状态、滑块拇指、选中标记等主要视觉元素。
     */
    public static final int THEME = 0xFF94E4D3;

    /**
     * 亮主题色。
     * <p>用于悬停状态、高亮背景等辅助元素。
     */
    public static final int THEME_LIGHTER = 0xFFCCFDEE;

    /**
     * 暗主题色。
     * <p>用于按下状态、深色背景等辅助元素。
     */
    public static final int THEME_DARKER = 0xFF7A9E9E;

    // ==================== 前景色 ====================

    /**
     * 标准前景色（白色）。
     * <p>用于正常状态的文本、图标和边框。
     */
    public static final int FOREGROUND = 0xFFFFFFFF;

    /**
     * 禁用状态前景色（灰色）。
     * <p>用于禁用控件的文本显示。
     */
    public static final int FOREGROUND_DISABLED = 0xFFAAAAAA;

    // ==================== 背景色 ====================

    /**
     * 浅色背景。
     * <p>用于选项行的默认背景。
     */
    public static final int BACKGROUND_LIGHT = 0x40000000;

    /**
     * 中等深度背景。
     * <p>用于面板、弹出框等容器背景。
     */
    public static final int BACKGROUND_MEDIUM = 0x60000000;

    /**
     * 悬停状态背景。
     * <p>用于鼠标悬停时的行高亮。
     */
    public static final int BACKGROUND_HOVER = 0xE0000000;

    /**
     * 覆盖层背景。
     * <p>用于模态对话框、提示框的半透明遮罩。
     */
    public static final int BACKGROUND_OVERLAY = 0xEA000000;

    /**
     * 默认背景。
     * <p>用于通用容器的标准背景。
     */
    public static final int BACKGROUND_DEFAULT = 0x90000000;

    /**
     * 深色背景。
     * <p>用于需要更强对比的区域。
     */
    public static final int BACKGROUND_DARKER = 0xB0000000;

    /**
     * 高亮背景。
     * <p>用于选中项、拖拽目标等高亮效果。
     */
    public static final int BACKGROUND_HIGHLIGHT = 0x08FFFFFF;

    // ==================== 按钮色 ====================

    /**
     * 按钮边框颜色。
     * <p>用于按钮的边框描边。
     */
    public static final int BUTTON_BORDER = 0x8000FFEE;

    // ==================== 颜色调整工具方法 ====================

    /** 亮度提升因子 */
    private static final float LIGHTEN_FACTOR = 0.3f;

    /** 暗度调整因子 */
    private static final float DARKEN_FACTOR = -0.23f;

    /**
     * 使颜色变暗。
     *
     * <p>降低颜色的亮度（Value 分量），保持色相和饱和度不变。
     *
     * @param color 原始颜色（ARGB 格式）
     * @return      变暗后的颜色
     */
    public static int darken(int color) {
        return adjust(color, DARKEN_FACTOR);
    }

    /**
     * 使颜色变亮。
     *
     * <p>提高颜色的亮度（Value 分量），保持色相和饱和度不变。
     *
     * @param color 原始颜色（ARGB 格式）
     * @return      变亮后的颜色
     */
    public static int lighten(int color) {
        return adjust(color, LIGHTEN_FACTOR);
    }

    /**
     * 调整颜色亮度。
     *
     * <p>将 RGB 颜色转换到 HSV 空间，调整 Value 分量后转回 RGB。
     * Alpha 通道保持不变。
     *
     * @param color  原始颜色（ARGB 格式）
     * @param factor 调整因子（正数变亮，负数变暗）
     * @return       调整后的颜色
     */
    public static int adjust(int color, float factor) {
        float[] hsv = ColorHelper.toHSV(color);
        float s = clamp(hsv[1] * (1 - Math.abs(factor)), 0, 1);
        float b = clamp(hsv[2] * (1 + factor), 0, 1);
        return transferAlpha(fromHSV(hsv[0], s, b), color);
    }

    /**
     * 将值限制在指定范围内。
     *
     * @param value 待限制的值
     * @param min   最小值
     * @param max   最大值
     * @return      限制后的值
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
