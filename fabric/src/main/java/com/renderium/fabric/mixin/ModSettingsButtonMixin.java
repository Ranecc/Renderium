// Renderium - 第三方模组设置界面按钮注入 Mixin
// 在兼容模式下，将 Renderium 设置按钮追加到第三方模组（如性能优化模组）的设置界面

package com.renderium.fabric.mixin;

import com.renderium.config.RenderiumConfig;
import com.renderium.core.RenderiumCore;
import com.renderium.core.RenderiumDualModeManager;
import com.renderium.core.RenderiumMode;
import com.renderium.ui.RenderiumSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第三方模组设置界面 Mixin
 *
 * <p>在兼容模式下，将 Renderium 设置按钮追加到第三方模组的设置界面底部。
 * 这样用户可以直接从第三方模组设置界面访问 Renderium 配置。
 *
 * <h3>注入目标</h3>
 * <ul>
 *   <li><b>性能优化模组</b>：{@code net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI}</li>
 *   <li>其他提供视频设置界面的模组也可类似注入</li>
 * </ul>
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>检测目标设置界面类</li>
 *   <li>在 init() 方法尾部注入代码</li>
 *   <li>添加 "Renderium Settings" 按钮</li>
 *   <li>点击按钮打开 RenderiumSettingsScreen</li>
 * </ol>
 *
 * <h3>兼容模式行为</h3>
 * <ul>
 *   <li>当 Renderium 处于 COMPATIBILITY 模式时显示按钮</li>
 *   <li>当处于 AGGRESSIVE 模式时不显示（因为会替换原版菜单）</li>
 * </ul>
 *
 * @see RenderiumSettingsScreen
 * @author Renderium Team
 * @since 1.0.0
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI", remap = false)
public abstract class ModSettingsButtonMixin extends Screen {

    protected ModSettingsButtonMixin(Component title) {
        super(title);
    }

    /**
     * 在第三方模组设置界面初始化后注入 Renderium 设置按钮
     *
     * @param ci Mixin 回调信息
     */
    @Inject(
            method = "init",
            at = @At("TAIL")
    )
    private void onInit(CallbackInfo ci) {
        // 检查是否处于兼容模式
        RenderiumDualModeManager modeManager = RenderiumDualModeManager.getInstance();
        if (modeManager.getCurrentMode() != RenderiumMode.COMPATIBILITY) {
            // 狂暴模式下不追加按钮（会替换原版菜单）
            return;
        }

        // 检查 Renderium 是否激活
        // ====== L0 规范: 通过反射查询（禁止直接调用 Core）======
        if (!queryCoreActive()) {
            return;
        }

        // 添加 Renderium 设置按钮
        int buttonWidth = 200;
        int buttonHeight = 20;
        int buttonX = width / 2 - buttonWidth / 2;
        int buttonY = height - 50;

        Button renderiumButton = Button.builder(
                Component.translatable("renderium.config.open_settings"),
                btn -> openRenderiumSettings()
        )
        .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
        .build();

        addRenderableWidget(renderiumButton);
    }

    /**
     * 打开 Renderium 设置界面
     */
    private void openRenderiumSettings() {
        // ====== L0 规范: 通过反射获取配置（禁止直接调用 Core）======
        Object config = queryConfig();
        // MC 26.2: setScreen() 方法已移至 gui 子对象
        minecraft.gui.setScreen(new RenderiumSettingsScreen(this,
                (com.renderium.config.RenderiumConfig) config));
    }

    /**
     * 查询 Core 配置（通过反射）
     *
     * @return RenderiumConfig 实例或 null
     */
    private static Object queryConfig() {
        try {
            Class<?> coreClass = Class.forName("com.renderium.core.RenderiumCore");
            Object coreInstance = coreClass.getMethod("getInstance").invoke(null);
            return coreClass.getMethod("getConfig").invoke(coreInstance);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查询 Core 是否激活（通过反射）
     */
    private static boolean queryCoreActive() {
        try {
            Class<?> coreClass = Class.forName("com.renderium.core.RenderiumCore");
            Object coreInstance = coreClass.getMethod("getInstance").invoke(null);
            return (Boolean) coreClass.getMethod("isActive").invoke(coreInstance);
        } catch (Exception e) {
            return false;
        }
    }
}
