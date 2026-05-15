package com.ranecc.renderium.platform.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.ranecc.renderium.None;
import org.spongepowered.asm.mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * RenderSystem Mixin - Vulkan 设备句柄提取
 * <p>
 * 在 {@link RenderSystem#initRenderer(GpuDevice)} 完成后拦截，
 * 提取 26.2-snapshot-3 官方 {@link VulkanDevice} 的底层原生句柄，
 * 并初始化 {@link VulkanDeviceHolder} 全局单例和 Streamline SDK。
 * </p>
 *
 * <h3>注入点定义：</h3>
 * <pre>
 * 目标类：com.mojang.blaze3d.systems.RenderSystem
 * 目标方法：initRenderer(GpuDevice device)
 * 注入时机：@At("TAIL") - 在原始方法执行完毕后
 * </pre>
 *
 * <h3>执行流程：</h3>
 * <ol>
 *   <li>检查 GpuDevice.backend 是否为 VulkanDevice 实例</li>
 *   <li>如果是，提取 vkDevice / vma / graphicsQueue / computeQueue 原生句柄</li>
 *   <li>调用 VulkanDeviceHolder.initialize() 缓存所有句柄</li>
 *   <li>调用 StreamlineIntegration.initialize() 初始化 Streamline SDK</li>
 *   <li>记录初始化结果日志</li>
 * </ol>
 *
 * <h3>兼容性处理：</h3>
 * <ul>
 *   <li>如果 backend 不是 VulkanDevice（如 OpenGL 后端），记录 WARNING 并跳过</li>
 *   <li>不会中断或修改原始 initRenderer 的执行流程</li>
 *   <li>cancellable = false，确保不取消原始方法</li>
 * </ul>
 *
 * @see com.renderium.core.VulkanDeviceHolder
 * @see com.renderium.streamline.StreamlineIntegration
 * @see com.mojang.blaze3d.vulkan.VulkanDevice
 * @since 5.2.0
 */
@Mixin(RenderSystem.class)
public abstract class MixinRenderSystem {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|MixinRenderSystem");

    /**
     * 在 RenderSystem.initRenderer() 完成后拦截
     * <p>
     * 提取 VulkanDevice 句柄并初始化 Renderium 核心系统。
     * 此注入点在 Mojang 的 initRenderer 逻辑完全执行完毕后触发，
     * 确保 GpuDevice 和 VulkanDevice 已完全初始化。
     * </p>
     *
     * 【方法参数】
     * @param device  GpuDevice - Minecraft 传递的 GPU 设备实例（由 Blaze3D 创建）
     * @param ci      CallbackInfo - Mixin 回调信息（不使用，但必须声明）
     *
     * 【返回值】void
     *
     * 【实现要点】
     * 1. instanceof 检查 device.backend 是否为 VulkanDevice
     * 2. 类型转换并提取底层原生句柄：
     *    - vkDevice.address() → VkDevice 长整型地址
     *    - vkDevice.vma() → VMA 分配器句柄
     *    - vkDevice.graphicsQueue().vkQueue().address() → 图形队列
     *    - vkDevice.computeQueue().vkQueue().address() → 计算队列
     * 3. 调用 VulkanDeviceHolder.initialize() 缓存句柄
     * 4. 调用 StreamlineIntegration.initialize() 初始化 DLSS/FSR/XeSS
     *
     * 【异常处理】
     * - 非 Vulkan 后端：仅记录 WARNING，不抛异常
     * - 句柄提取失败：记录 SEVERE + 堆栈跟踪，但不崩溃
     * - Streamline 初始化失败：记录 WARNING，后续 Pass 会跳过 SR/FG
     *
     * 【性能特征】
     * - 仅在游戏启动时调用一次
     * - instanceof 检查开销极小（~1ns）
     * - 无热路径影响
     */
    @Inject(
            method = "initRenderer",
            at = @At("TAIL"),
            cancellable = false  // 不取消原始 initRenderer
    )
    private static void onInitRendererTail(GpuDevice device, CallbackInfo ci) {
        // 通过反射获取 GpuDevice 的私有 backend 字段（避免 @Accessor 静态上下文问题）
        Object backendObj;
        try {
            java.lang.reflect.Field backendField = GpuDevice.class.getDeclaredField("backend");
            backendField.setAccessible(true);
            backendObj = backendField.get(device);
        } catch (Exception e) {
            LOGGER.warning("无法访问 GpuDevice.backend 字段: " + e.getMessage());
            return;
        }

        if (!backendObj.getClass().getName().contains("VulkanDevice")) {
            LOGGER.warning(
                "RenderSystem 后端不是 VulkanDevice (实际: " +
                (backendObj != null ? backendObj.getClass().getSimpleName() : "null") +
                ")，跳过 Renderium Vulkan 初始化"
            );
            return;
        }

        try {
            // 将 backendObj 强制转换为 VulkanDevice
            com.mojang.blaze3d.vulkan.VulkanDevice vkDevice =
                (com.mojang.blaze3d.vulkan.VulkanDevice) backendObj;

            // 提取关键原生句柄
            long vkDeviceHandle = vkDevice.vkDevice().address();
            long vmaAllocator = vkDevice.vma();
            long graphicsQueue = vkDevice.graphicsQueue().vkQueue().address();
            long computeQueue = vkDevice.computeQueue().vkQueue().address();

            // 初始化 VulkanDeviceHolder（全局单例）
            VulkanDeviceHolder.getInstance().initialize(
                vkDevice,
                vkDeviceHandle,
                vmaAllocator,
                graphicsQueue,
                computeQueue
            );

            // 初始化 Streamline SDK（DLSS/FSR/XeSS/FG）
            try {
                StreamlineIntegration streamline = new StreamlineIntegration();
                streamline.initialize(
                    vkDeviceHandle,
                    vmaAllocator,
                    graphicsQueue
                );

                LOGGER.info(String.format(
                    "Renderium: VulkanDevice 句柄获取成功 [vkDevice=0x%X, vma=0x%X, gfxQ=0x%X, compQ=0x%X] | Streamline: 已初始化",
                    vkDeviceHandle, vmaAllocator, graphicsQueue, computeQueue
                ));
            } catch (Exception slEx) {
                LOGGER.warning(String.format(
                    "Renderium: VulkanDevice 句柄获取成功 [vkDevice=0x%X] 但 Streamline SDK 初始化失败: %s",
                    vkDeviceHandle,
                    slEx.getMessage()
                ));
            }

        } catch (Exception e) {
            // 记录严重错误但不崩溃游戏
            LOGGER.log(Level.SEVERE, "Renderium: VulkanDevice 句柄提取失败", e);
        }
    }
}
