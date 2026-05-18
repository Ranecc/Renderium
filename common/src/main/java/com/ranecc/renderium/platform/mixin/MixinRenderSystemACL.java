// Renderium - RenderSystem 防腐层 Mixin (Thin Glue)
// 统计 Draw Call 数量，供预算分配器使用

package com.ranecc.renderium.platform.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ranecc.renderium.platform.bridge.acl.RenderiumACL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RenderSystem 防腐层 Mixin — 统计 Draw Call 数量。
 *
 * <p>Thin Glue 约束:
 * <ul>
 *   <li>方法体 <= 10 行</li>
 *   <li>仅调用 RenderiumACL</li>
 *   <li>不 @Shadow 内部字段</li>
 * </ul>
 */
@Mixin(RenderSystem.class)
public abstract class MixinRenderSystemACL {

    @Inject(method = "draw", at = @At("HEAD"))
    private static void onDraw(CallbackInfo ci) {
        // Draw call 计数由 FrameBudgetAllocator 的 cullingBudget 隐式控制
        // RenderiumACL 内已集成了剔除分配的预算管理
        // 此处为占位——精确的 draw call 追踪需要 snapshot7 Blaze3D API 配合
    }
}
