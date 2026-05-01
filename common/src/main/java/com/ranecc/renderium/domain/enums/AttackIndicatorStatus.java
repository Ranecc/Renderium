package com.ranecc.renderium.domain.enums;

import net.minecraft.network.chat.Component;

/**
 * 攻击指示器状态枚举（MC 26.2 兼容层）
 * <p>
 * 替代 net.minecraft.world.entity.player.AttackIndicatorStatus
 * 用于选项系统中的攻击指示器设置
 */
public enum AttackIndicatorStatus {
    /** 十字准星 */
    CROSSHAIR,
    /** 关闭 */
    OFF;

    /**
     * 获取显示名称
     *
     * @return Component - 本地化组件
     */
    public Component caption() {
        return switch (this) {
            case CROSSHAIR -> Component.translatable("options.attack.crosshair");
            case OFF -> Component.translatable("options.attack.off");
        };
    }
}
