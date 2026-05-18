package com.ranecc.renderium.platform.mixin;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard;
import com.ranecc.renderium.feature.blaze3d.stylizedrt.StreamlineIntegration;

/**
 * RenderSystem Mixin — Vulkan 设备句柄提取（零反射版 v2）
 *
 * <p>使用 {@link GpuDeviceAccessor} + {@link RenderSystemAccessor}（编译期生成的
 * Mixin @Accessor）替代 java.lang.reflect。
 *
 * <h2>与 v1 的区别</h2>
 * <ul>
 *   <li>{@code GpuDevice.backend} → {@link GpuDeviceAccessor#getBackend()}（零反射）</li>
 *   <li>句柄提取失败 → {@link VulkanOperationGuard#markFailed}（不崩溃游戏）</li>
 *   <li>字段名变更 → 编译期报错（NoSuchFieldError）→ 改一行 @Accessor</li>
 * </ul>
 */
@Mixin(RenderSystem.class)
public abstract class MixinRenderSystem {

    private static final Logger LOGGER = Logger.getLogger("Renderium|MixinRenderSystem");

    @Inject(method = "initRenderer", at = @At("TAIL"), cancellable = false)
    private static void onInitRendererTail(GpuDevice device, CallbackInfo ci) {
        Object backendObj;
        try {
            backendObj = ((GpuDeviceAccessor) (Object) device).getBackend();
        } catch (Exception e) {
            LOGGER.warning("无法访问 GpuDevice.backend 字段: " + e.getMessage());
            return;
        }

        if (backendObj == null
            || !backendObj.getClass().getName().contains("VulkanDevice")) {
            LOGGER.warning(
                "RenderSystem 后端不是 VulkanDevice (实际: " +
                (backendObj != null ? backendObj.getClass().getSimpleName() : "null") +
                ")，跳过 Renderium Vulkan 初始化"
            );
            return;
        }

        try {
            VulkanDevice vkDevice = (VulkanDevice) backendObj;

            long vkDeviceHandle = vkDevice.vkDevice().address();
            long vmaAllocator   = vkDevice.vma();
            long gQueue = vkDevice.graphicsQueue().vkQueue().address();
            long cQueue = vkDevice.computeQueue().vkQueue().address();

            VulkanDeviceHolder.getInstance().set(vkDeviceHandle, vmaAllocator, gQueue, cQueue);

            try {
                StreamlineIntegration streamline = new StreamlineIntegration();
                streamline.initialize(vkDeviceHandle, vmaAllocator, gQueue);
                LOGGER.info(String.format(
                    "Renderium: VulkanDevice 句柄提取成功 [device=0x%X, vma=0x%X, gQ=0x%X, compQ=0x%X]",
                    vkDeviceHandle, vmaAllocator, gQueue, cQueue));
            } catch (Exception slEx) {
                LOGGER.warning("Streamline SDK 初始化失败: " + slEx.getMessage());
            }

        } catch (GpuDeviceLossException e) {
            LOGGER.log(Level.SEVERE, "Vulkan 设备丢失", e);
            VulkanOperationGuard.markFailed(e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "VulkanDevice 句柄提取失败", e);
            VulkanOperationGuard.markFailed(e);
        }
    }
}
