// Renderium - 按钮颜色主题定义
// 参考 Sodium 的 ButtonTheme 类，使用中性命名

package com.ranecc.renderium.presentation.ui.ui;

/**
 * 按钮专用颜色主题配置类。
 *
 * <p>继承 {@link ColorTheme} 并添加按钮特有的背景色配置，
 * 用于控制按钮在不同状态下的视觉效果。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code ButtonTheme} 设计，
 * 提供完整的按钮状态颜色配置。
 *
 * <h2>状态颜色映射</h2>
 * <table border="1">
 *   <tr><th>状态</th><th>字段</th><th>用途</th></tr>
 *   <tr><td>正常前景</td><td>theme</td><td>正常状态文本颜色</td></tr>
 *   <tr><td>悬停前景</td><td>themeLighter</td><td>悬停状态文本颜色</td></tr>
 *   <tr><td>禁用前景</td><td>themeDarker</td><td>禁用状态文本颜色</td></tr>
 *   <tr><td>悬停背景</td><td>bgHighlight</td><td>鼠标悬停时背景色</td></tr>
 *   <tr><td>正常背景</td><td>bgDefault</td><td>默认状态背景色</td></tr>
 *   <tr><td>禁用背景</td><td>bgInactive</td><td>禁用状态背景色</td></tr>
 * </table>
 *
 * <h3>使用示例</h4>
 * <pre>
 * // 使用默认主题
 * ButtonTheme theme = FlatButtonWidget.DEFAULT_THEME;
 *
 * // 自定义主题（基于现有 ColorTheme）
 * ButtonTheme custom = new ButtonTheme(
 *     baseTheme,                    // 继承前景色配置
 *     0xE0000000,                   // 悬停背景
 *     0x90000000,                   // 正常背景
 *     0xB0000000                    // 禁用背景
 * );
 *
 * // 完全自定义所有颜色
 * ButtonTheme fullCustom = new ButtonTheme(
 *     0xFF4ECDC4, 0xFF7FDED8, 0xFF2A9D8F,  // 前景色
 *     0xE0000000, 0x90000000, 0xB0000000    // 背景色
 * );
 * </pre>
 *
 * @param theme       从 ColorTheme 继承的主题色
 * @param themeLighter 从 ColorTheme 继承的主题浅色
 * @param themeDarker  从 ColorTheme 继承的主题深色
 * @param bgHighlight  悬停状态背景色（ARGB 格式）
 * @param bgDefault    正常状态背景色（ARGB 格式）
 * @param bgInactive   禁用状态背景色（ARGB 格式）
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see ColorTheme
 * @see Colors
 * @see FlatButtonWidget
 */
public class ButtonTheme extends ColorTheme {

    /** 悬停状态背景色 */
    public final int bgHighlight;

    /** 正常状态背景色 */
    public final int bgDefault;

    /** 禁用状态背景色 */
    public final int bgInactive;

    /**
     * 创建完整的按钮主题（指定所有参数）。
     *
     * @param theme       主主题色（ARGB 格式）
     * @param themeLighter 主题浅变体（ARGB 格式）
     * @param themeDarker  主题深变体（ARGB 格式）
     * @param bgHighlight  悬停背景色（ARGB 格式）
     * @param bgDefault    正常背景色（ARGB 格式）
     * @param bgInactive   禁用背景色（ARGB 格式）
     */
    public ButtonTheme(int theme, int themeLighter, int themeDarker,
                       int bgHighlight, int bgDefault, int bgInactive) {
        super(theme, themeLighter, themeDarker);
        this.bgHighlight = bgHighlight;
        this.bgDefault = bgDefault;
        this.bgInactive = bgInactive;
    }

    /**
     * 基于现有 ColorTheme 创建按钮主题。
     *
     * <p>前景色从传入的主题继承，只需指定背景色配置。
     *
     * @param theme       基础颜色主题（提供前景色配置）
     * @param bgHighlight 悬停背景色（ARGB 格式）
     * @param bgDefault   正常背景色（ARGB 格式）
     * @param bgInactive  禁用背景色（ARGB 格式）
     */
    public ButtonTheme(ColorTheme theme, int bgHighlight, int bgDefault, int bgInactive) {
        this(theme.theme, theme.themeLighter, theme.themeDarker,
             bgHighlight, bgDefault, bgInactive);
    }
}
