package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.platform.hook.FrameContext;
import com.ranecc.renderium.platform.hook.HookDispatcher;
import com.ranecc.renderium.platform.lifecycle.LifecycleManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer Mixin - 防腐层标准实现 (MC 26.2)
 * <p>
 * Thin Glue 约束:
 * <ul>
 *   <li>方法体 <= 10 行</li>
 *   <li>不 import 非 L1 的 Renderium 类（仅限 platform.hook.* 和 platform.lifecycle.*）</li>
 *   <li>不做控制流（除 null 检查）</li>
 *   <li>不 new 对象</li>
 *   <li>不 @Shadow Minecraft 内部字段</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    /**
     * 帧开始 - HEAD 注入
     * <p>
     * 仅做: 捕获 deltaTime -> 更新 FrameContext -> 委托 LifecycleManager -> 重置 PerFrameArena
     * 热路径性能预算: < 200ns
     *
     * @param deltaTracker   MC 帧时间追踪器
     * @param advanceGameTime 是否推进游戏时间
     * @param ci             Mixin 回调信息
     */
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void onFrameBegin(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        PerFrameArena.beginFrame();
        FrameContext ctx = FrameContext.get();
        ctx.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        HookDispatcher.syncCache();
        LifecycleManager lifecycle = HookDispatcher.getLifecycleManager();
        if (lifecycle != null) lifecycle.beginFrame();
    }

    /**
     * 帧结束 - RETURN 注入
     * <p>
     * 仅做: 委托 LifecycleManager.endFrame() + 关闭 PerFrameArena
     *
     * @param ci Mixin 回调信息
     */
    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void onFrameEnd(CallbackInfo ci) {
        FrameContext ctx = FrameContext.get();
        if (ctx != null) ctx.endFrame();
        LifecycleManager lifecycle = HookDispatcher.getLifecycleManager();
        if (lifecycle != null) lifecycle.endFrame();
        PerFrameArena.endFrame();
    }
}
