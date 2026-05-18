package com.ranecc.renderium.platform.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin @Accessor — 编译期生成 RenderSystem 私有字段的 getter。
 */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {

    @Accessor("device")
    Object getDevice();
}
