// Renderium - 颜色主题配置
// 参考 Sodium 的 ColorTheme 类，使用中性命名

package com.ranecc.renderium.presentation.ui.widgets.options;

import com.ranecc.renderium.None;

/**
 * 颜色主题配置。
 *
 * <p>封装一组协调的颜色方案，用于渲染器设置界面的视觉呈现。
 * 支持预设主题和自定义主题两种模式。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code ColorTheme} 设计，
 * 提供 5 种预设主题色供用户选择。
 *
 * <h2>预设主题</h2>
 * <pre>
 * PRESETS[0] = 粉红色系 (0xFFE494A5)
 * PRESETS[1] = 紫色系   (0xFFAB94E4)
 * PRESETS[2] = 黄绿色系 (0xFFCDE494)
 * PRESETS[3] = 浅紫色系 (0xFFD394E4)
 * PRESETS[4] = 橙黄色系 (0xFFE4D394)
 * </pre>
 *
 * <h2>不可变性</h2>
 * <p>所有字段均为 final，实例创建后不可修改，保证线程安全。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see Colors
 */
public record ColorTheme(int theme, int themeLighter, int themeDarker) {

    /**
     * 预设主题数组。
     *
     * <p>包含 5 种不同的颜色主题，用户可在设置中切换。
     * 每个主题自动计算对应的亮色和暗色变体。
     */
    public static final ColorTheme[] PRESETS = new ColorTheme[] {
        new ColorTheme(0xFFE494A5),
        new ColorTheme(0xFFAB94E4),
        new ColorTheme(0xFFCDE494),
        new ColorTheme(0xFFD394E4),
        new ColorTheme(0xFFE4D394)
    };

    /**
     * 创建单参数主题（自动计算亮色和暗色变体）。
     *
     * @param theme 基础主题色（ARGB 格式）
     */
    public ColorTheme(int theme) {
        this(theme, Colors.lighten(theme), Colors.darken(theme));
    }
}
