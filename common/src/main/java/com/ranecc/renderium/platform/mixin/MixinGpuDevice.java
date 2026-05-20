package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.platform.hook.HookDispatcher;
import com.mojang.blaze3d.systems.GpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

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
     * MC 26.2（含 snapshot-3 起）createBuffer 签名:
     * createBuffer(Supplier label, int usage, long size) → GpuBuffer
     * 捕获 size 参数传递给 HookDispatcher 用于 GPU 后端的 Buffer 分配优化。
     * <p>
     * 方法签名: createBuffer(Supplier<String> label, int usage, long size) → GpuBuffer
     *
     * @param label 缓冲区标签（Supplier<String>，lazy evaluation）
     * @param usage 缓冲区用途标志位
     * @param size  缓冲区大小（字节）
     * @param cir   Mixin 回调信息（createBuffer 有返回值，使用 CallbackInfoReturnable）
     */
    @Inject(method = "createBuffer", at = @At("HEAD"))
    private void onBufferCreate(Supplier<?> label, int usage, long size, CallbackInfoReturnable<?> cir) {
        if (VulkanDeviceHolder.isAvailable()) {
            HookDispatcher.dispatchGpuDeviceBuffer(size);
        }
    }
}
