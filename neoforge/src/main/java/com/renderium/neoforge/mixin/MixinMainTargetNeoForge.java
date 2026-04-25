// Renderium - Main Target Mixin for NeoForge
// Gets the main render target textures

package com.renderium.neoforge.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 到 MainTarget（NeoForge 版本）
 */
@Mixin(MainTarget.class)
public class MixinMainTargetNeoForge {

    @Inject(method = "getColorTexture", at = @At("RETURN"))
    private void onGetColorTexture(CallbackInfoReturnable<RenderTarget> cir) {
        // 存储颜色纹理信息
    }

    @Inject(method = "getDepthTexture", at = @At("RETURN"))
    private void onGetDepthTexture(CallbackInfoReturnable<RenderTarget> cir) {
        // 存储深度纹理信息
    }
}
