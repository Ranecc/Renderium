package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.platform.hook.HookDispatcher;
import com.ranecc.renderium.platform.lifecycle.LifecycleManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LevelRenderer Mixin - 防腐层标准实现 (MC 26.2)
 * <p>
 * Thin Glue 约束:
 * <ul>
 *   <li>方法体 <= 10 行</li>
 *   <li>不 import 非 L1 的 Renderium 类</li>
 *   <li>不做控制流（除 null 检查）</li>
 *   <li>不 new 对象</li>
 *   <li>不 @Shadow Minecraft 内部字段</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {

    /**
     * 世界渲染开始 - HEAD 注入
     * <p>
     * 仅做: 委托 LifecycleManager.beginWorld()
     *
     * @param ci Mixin 回调信息
     */
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void onWorldBegin(CallbackInfo ci) {
        LifecycleManager lifecycle = HookDispatcher.getLifecycleManager();
        if (lifecycle != null) lifecycle.onBeforeGlobalUniform();
    }

    /**
     * 世界渲染结束 - TAIL 注入
     * <p>
     * 仅做: 委托 LifecycleManager.endWorld()
     *
     * @param ci Mixin 回调信息
     */
    @Inject(method = "renderLevel", at = @At("TAIL"), require = 0)
    private void onWorldEnd(CallbackInfo ci) {
        LifecycleManager lifecycle = HookDispatcher.getLifecycleManager();
        if (lifecycle != null) lifecycle.onAfterGlobalUniform();
    }
}
