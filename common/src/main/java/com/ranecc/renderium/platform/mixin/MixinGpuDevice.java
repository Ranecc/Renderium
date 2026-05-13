package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.platform.hook.HookDispatcher;
import com.mojang.blaze3d.systems.GpuDevice;
import org.spongepowered.asm.mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GpuDevice Mixin - 防腐层标准实现 (MC 26.2)
 * <p>
 * Thin Glue 约束:
 * <ul>
 *   <li>方法体 <= 10 行</li>
 *   <li>不 import 非 L1 的 Renderium 类</li>
 *   <li>不做控制流（除 null 检查）</li>
 *   <li>不 new 对象</li>
 *   <li>不 @Shadow Minecraft 内部字段</li>
 * </ul>
 * <p>
 * 注入频率: ~200 次/帧, 热路径性能预算: < 100ns/次
 */
@Mixin(GpuDevice.class)
public abstract class MixinGpuDevice {

    /**
     * Buffer 创建 - HEAD 注入
     * <p>
     * 仅做: 委托 HookDispatcher.dispatchGpuDeviceBuffer()
     * 用于 GPU 后端的 Buffer 分配优化。
     *
     * @param ci Mixin 回调信息
     */
    @Inject(method = "createBuffer", at = @At("HEAD"))
    private void onBufferCreate(CallbackInfo ci) {
        HookDispatcher.dispatchGpuDeviceBuffer();
    }
}
