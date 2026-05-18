// Renderium - LevelRenderer 防腐层 Mixin (Thin Glue)
// 仅调用 RenderiumACL，不做任何逻辑，<=10行/方法
// Camera API 使用 snapshot7 的 record 风格访问器

package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.platform.bridge.acl.RenderiumACL;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * LevelRenderer 防腐层 Mixin — 截获渲染帧入口，委托 ACL。
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererACL {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderFrame(PoseStack poseStack, float tickDelta,
                                long limitTime, boolean renderBlockOutline,
                                Camera camera, CallbackInfo ci) {
        RenderiumACL acl = RenderiumACL.getInstance();
        if (!acl.isReady()) return;

        Vec3 pos = camera.position();
        acl.onRenderFrame(pos.x(), pos.y(), pos.z(),
            camera.yRot(), camera.xRot(),
            0.05f,
            (float) Minecraft.getInstance().options.fov().get(),
            256.0);
    }
}
