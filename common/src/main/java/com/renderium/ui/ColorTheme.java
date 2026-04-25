// Renderium - UI 颜色主题定义
// 参考 Sodium 的 ColorTheme 类，使用中性命名

package com.renderium.ui;

import java.util.stream.Stream;

/**
 * UI 颜色主题配置类。
 *
 * <p>定义一套完整的颜色方案，包含主题色及其浅/深变体。
 * 用于统一管理不同组件的颜色风格，支持预设主题和自定义主题。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code ColorTheme} 设计，
 * 但使用 Renderium 的青色调作为默认主题。
 *
 * <h2>主题组成</h2>
 * <pre>
 * ColorTheme
 * ├── theme: int         （主主题色）
 * ├── themeLighter: int  （浅变体 - 悬停/高亮状态）
 * └── themeDarker: int   （深变体 - 按下/选中状态）
 * </pre>
 *
 * <h3>预设主题</h3>
 * <p>提供多种预设配色方案供选择：
 * <ul>
 *   <li>粉色系（原 Sodium 风格）</li>
 *   <li>紫色系</li>
 *   <li>黄绿色系</li>
 *   <li>淡紫色系</li>
 *   <li>金黄色系</li>
 * </ul>
 *
 * <h4>使用示例</h4>
 * <pre>
 * // 使用默认主题（自动计算浅/深变体）
 * ColorTheme theme = new ColorTheme(0xFF4ECDC4);
 *
 * // 完全自定义主题
 * ColorTheme custom = new ColorTheme(0xFF4ECDC4, 0xFF7FDED8, 0xFF2A9D8F);
 *
 * // 使用预设主题
 * ColorTheme preset = ColorTheme.PRESETS[0];
 * </pre>
 *
 * @param theme        主主题色（ARGB 格式）
 * @param themeLighter 主题色的浅变体（ARGB 格式）
 * @param themeDarker  主题色的深变体（ARGB 格式）
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see ButtonTheme
 * @see Colors
 */
public record ColorTheme(int theme, int themeLighter, int themeDarker) {

    /**
     * 预设主题数组。
     * <p>提供多种现成的配色方案，可通过索引选择。
     * 每个预设只指定主色调，浅/深变体会自动计算。
     */
    public static final ColorTheme[] PRESETS = Stream.of(
            0xFFE494A5,  // 粉色
            0xFFAB94E4,  // 紫色
            0xFFCDE494,  // 黄绿
            0xFFD394E4,  // 淡紫
            0xFFE4D394   // 金黄
    ).map(ColorTheme::new).toArray(ColorTheme[]::new);

    /**
     * 创建完整指定的颜色主题。
     *
     * @param theme        主主题色（ARGB 格式），不能为 null
     * @param themeLighter 浅变体（ARGB 格式），不能为 null
     * @param themeDarker  深变体（ARGB 格式），不能为 null
     */
    public ColorTheme {
        // Record 构造器，无需额外验证
    }

    /**
     * 根据主色调自动创建颜色主题。
     *
     * <p>浅变体和深变体会根据 {@link Colors#lighten(int)} 和
     * {@link Colors#darken(int)} 自动计算。
     *
     * @param theme 主主题色（ARGB 格式）
     */
    public ColorTheme(int theme) {
        this(theme, Colors.lighten(theme), Colors.darken(theme));
    }
}
