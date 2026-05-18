package com.ranecc.renderium.platform.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin @Accessor — 编译期生成 GpuDevice 私有字段的 getter。
 *
 * <p>替代 java.lang.reflect。字段名变化 → 编译报错 → 改一行 @Accessor 注解。
 */
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessor {

    @Accessor("backend")
    Object getBackend();
}
