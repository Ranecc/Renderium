package com.renderium.config;

import net.minecraft.network.chat.Component;

/**
 * 非活动状态 FPS 限制枚举（MC 26.2 兼容层）
 * <p>
 * 控制窗口非活动时的帧率限制策略
 */
public enum InactivityFpsLimit {
    /** 降低到 AFK 阈值（通常为 10 FPS） */
    AFK,
    /** 限制到 30 FPS */
    THIRTY,
    /** 限制到 60 FPS */
    SIXTY,
    /** 限制到 120 FPS */
    ONE_TWENTY;

    /**
     * 获取显示名称
     *
     * @return Component - 本地化组件
     */
    public Component caption() {
        return switch (this) {
            case AFK -> Component.translatable("options.inactivityFpsLimit.afk");
            case THIRTY -> Component.translatable("options.inactivityFpsLimit.thirty");
            case SIXTY -> Component.translatable("options.inactivityFpsLimit.sixty");
            case ONE_TWENTY -> Component.translatable("options.inactivityFpsLimit.oneTwenty");
        };
    }
}
