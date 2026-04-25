// Renderium - Fabric 平台模式通知发送器
// 向玩家发送 Renderium 运行模式通知

package com.renderium.fabric.platform;

import com.renderium.core.RenderiumDualModeManager;
import com.renderium.core.RenderiumMode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Fabric 平台的模式通知发送器实现。
 *
 * <p>实现 {@link RenderiumDualModeManager.ModeNotificationSender} 接口，
 * 使用 Fabric API 向玩家发送模式切换通知。
 */
public final class FabricNotificationSender implements RenderiumDualModeManager.ModeNotificationSender {

    @Override
    public void sendNotification(RenderiumMode mode, Optional<String> sodiumVersion,
                                  String shaderModName, boolean shaderModPresent) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("§6[Renderium]§f 运行模式: §e").append(mode.getDisplayName()).append("§f\n");

        switch (mode) {
            case COMPATIBILITY -> {
                message.append("§a✓ 兼容模式§f - 检测到 Sodium，正复用其渲染优化\n");
                sodiumVersion.ifPresent(v -> message.append("   Sodium 版本: ").append(v).append("\n"));
                message.append("§e   提示: 画面将在 Vulkan 管线中经过 DLSS/FSR 超分输出\n");
            }
            case AGGRESSIVE -> {
                message.append("§c⚡ 狂暴模式§f - 全特效优化，底层夺舍渲染管线\n");
                message.append("§e   警告: 旧版 OpenGL 光影不兼容，请使用 Vulkan 光影\n");
            }
        }

        if (shaderModPresent && shaderModName != null) {
            message.append("\n§d   检测到光影: ").append(shaderModName).append("\n");
            message.append("   光影将在兼容模式下正常运行\n");
        }

        Component component = Component.literal(message.toString());
        mc.player.sendSystemMessage(component);
    }

    @Override
    public boolean isPlayerReady() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null;
    }

    @Override
    public void scheduleOnMainThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }
}