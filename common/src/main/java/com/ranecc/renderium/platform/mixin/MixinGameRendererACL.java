// Renderium - GameRenderer 防腐层 Mixin (Thin Glue)
// 透明排序截获 — 委托 RenderiumACL

package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.platform.bridge.acl.RenderiumACL;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer 防腐层 Mixin — 透明排序截获。
 *
 * <p>Thin Glue 约束:
 * <ul>
 *   <li>方法体 <= 10 行</li>
 *   <li>仅调用 RenderiumACL</li>
 *   <li>不 @Shadow 内部字段</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererACL {

    /**
     * 渲染帧结束 —— 透明排序入口。
     * 实际透明四边形提取需要 snapshot7 Blaze3D API 配合，
     * 当前仅做帧同步标记，具体排序逻辑在 RenderiumACL 内由 TranslucentSortEngine 执行。
     */
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void onRenderFrameEnd(CallbackInfo ci) {
        // 透明排序由 RenderiumACL.onRenderFrame() 内部的
        // RenderiumCullingScheduler 统一调度。此处不重复触发。
    }
}
