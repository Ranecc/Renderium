package com.renderium.bridge.video;

import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * 选项性能影响级别枚举
 * <p>
 * 用于标识修改某个视频选项对游戏性能的影响程度。
 * 影响级别会在设置界面中以不同颜色显示，帮助用户理解选项的重要性。
 *
 * <h3>影响级别说明：</h3>
 * <ul>
 *   <li>{@link #LOW} - 低影响：修改后几乎不影响性能</li>
 *   <li>{@link #MEDIUM} - 中等影响：在某些场景下可能影响性能</li>
 *   <li>{@link #HIGH} - 高影响：在大多数场景下会显著影响性能</li>
 *   <li>{@link #VERY_HIGH} - 极高影响：对性能有重大影响</li>
 * </ul>
 *
 * @see BooleanOptionBuilder#setImpact(OptionImpact)
 * @see IntegerOptionBuilder#setImpact(OptionImpact)
 * @see EnumOptionBuilder#setImpact(OptionImpact)
 * @since 1.0.0
 */
public enum OptionImpact {

    /**
     * 低性能影响
     * <p>
     * 修改此选项不会对性能产生可测量的或明显的影响。
     * 示例：UI 显示选项、提示文字开关等。
     */
    LOW(ChatFormatting.GREEN, "renderium.option_impact.low"),

    /**
     * 中等性能影响
     * <p>
     * 修改此选项可能在某些场景和系统上产生明显的性能影响。
     * 示例：粒子效果质量、云渲染开关等。
     */
    MEDIUM(ChatFormatting.YELLOW, "renderium.option_impact.medium"),

    /**
     * 高性能影响
     * <p>
     * 修改此选项很可能在大多数场景下对性能产生显著影响。
     * 示例：渲染距离、实体渲染距离等。
     */
    HIGH(ChatFormatting.GOLD, "renderium.option_impact.high"),

    /**
     * 极高性能影响
     * <p>
     * 修改此选项会对性能产生重大影响，可能导致帧率大幅波动。
     * 示例：光线追踪、全局光照等高级功能。
     */
    VERY_HIGH(ChatFormatting.RED, "renderium.option_impact.very_high");

    /** 带样式的显示文本组件 */
    private final Component text;

    /**
     * 构造选项影响级别枚举值
     *
     * @param formatting 文本颜色格式（用于在 UI 中区分影响级别）
     * @param translationKey 翻译键（用于国际化显示）
     */
    OptionImpact(ChatFormatting formatting, String translationKey) {
        this.text = Component.translatable(translationKey)
                .withStyle(formatting);
    }

    /**
     * 获取此影响级别的显示文本
     * <p>
     * 返回带有颜色样式的本地化文本组件，
     * 可直接用于 UI 渲染。
     *
     * @return 带格式的 Component 文本
     */
    public Component getText() {
        return this.text;
    }
}
