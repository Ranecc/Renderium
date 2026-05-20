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

/**
 * RenderSystem Mixin — Vulkan 设备句柄提取（零反射版 v3）
 *
 * <p>使用 {@link GpuDeviceAccessor}（Mixin @Accessor）安全提取 Vulkan 句柄。
 * 不依赖 LWJGL Pointer.getParent() 反射（Java 25+ 模块系统禁止）。
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>只提取 VkDevice/VMA/Queue 这三个必须句柄</li>
 *   <li>vkInstance/vkPhysicalDevice 通过 VulkanAPIRegistry 按需查询</li>
 *   <li>队列句柄兼容 null（部分 GPU 无专用 compute queue）</li>
 *   <li>句柄提取失败 → {@link VulkanOperationGuard#markFailed}（不崩溃游戏）</li>
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

            VulkanDeviceHolder.getInstance().setVulkanDeviceObj(vkDevice);

            long vkDeviceHandle = vkDevice.vkDevice().address();
            long vmaAllocator   = vkDevice.vma();

            // 部分 GPU 无独立 compute queue — null 安全处理
            var gQueueObj = vkDevice.graphicsQueue();
            long gQueue = gQueueObj != null ? gQueueObj.vkQueue().address() : 0L;
            var cQueueObj = vkDevice.computeQueue();
            long cQueue = cQueueObj != null ? cQueueObj.vkQueue().address() : 0L;

            VulkanDeviceHolder.getInstance().set(vkDeviceHandle, vmaAllocator, gQueue, cQueue);

            // vkInstance/vkPhysicalDevice 通过 VulkanAPIRegistry 按需查询，
            // 不在 Mixin 中反射提取（Java 25+ 禁止 LWJGL 内部字段反射）。
            // 这里只设 0L，下游代码在需要时通过 vkGetInstanceProcAddr 获取。
            VulkanDeviceHolder.getInstance().setVkInstance(0L);

            LOGGER.info(String.format(
                "Renderium: Vulkan 句柄提取成功 [device=0x%X, vma=0x%X, gQ=0x%X, compQ=0x%X]",
                vkDeviceHandle, vmaAllocator, gQueue, cQueue));

        } catch (GpuDeviceLossException e) {
            LOGGER.log(Level.SEVERE, "Vulkan 设备丢失", e);
            VulkanOperationGuard.markFailed(e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "VulkanDevice 句柄提取失败", e);
            VulkanOperationGuard.markFailed(e);
        }
    }
}
