package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.platform.hook.HookDispatcher;
import com.mojang.blaze3d.systems.GpuDevice;
import org.spongepowered.asm.mixin.Mixin;
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
     * 捕获 createBuffer(int, long) 的参数，将缓冲区大小传递给 HookDispatcher
     * 用于 GPU 后端的 Buffer 分配优化和内存追踪。
     * <p>
     * 方法签名: createBuffer(int usage, long size) → GpuBuffer
     *
     * @param usage 缓冲区用途标志位（从 createBuffer 的第一个 int 参数捕获）
     * @param size  缓冲区大小（字节，从 createBuffer 的第二个 long 参数捕获）
     * @param ci    Mixin 回调信息
     */
    @Inject(method = "createBuffer", at = @At("HEAD"))
    private void onBufferCreate(int usage, long size, CallbackInfo ci) {
        if (VulkanDeviceHolder.isAvailable()) {
            HookDispatcher.dispatchGpuDeviceBuffer(size);
        }
    }
}
