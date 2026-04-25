// Renderium - Vulkan Backend Mixin (v3 - 简化版)
// 由于 Vulkan 后端已剥离到独立项目，此 Mixin 仅做日志记录
//
// 注意：完整的 Vulkan 初始化逻辑将在 vulkan-backport 模组中实现

package com.renderium.vulkan.mixin;

import com.renderium.debug.RenderiumDebug;
import org.lwjgl.glfw.GLFWVulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.logging.Logger;

/**
 * RenderSystem Mixin - Vulkan 后端检测
 *
 * <p><b>v3 更新：</b>由于 Vulkan 后端代码已移至独立项目，
 * 此 Mixin 现在仅负责检测 Vulkan 运行时可用性并输出日志。</p>
 *
 * <h3>完整流程（将在 vulkan-backport 模组中实现）：</h3>
 * <pre>
 * sequenceDiagram
 *     participant Game as Minecraft 26.1.2
 *     participant VulkanMod as vulkan-backport 模组
 *     participant Proxy as RenderBackendProxy
 *     participant Event as VulkanActivationEvent
 *     participant FBO as FBOInteropHandler
 *
 *     Game->>Proxy: initialize()
 *     Note over Proxy: Vulkan 不可用<br/>进入 SHORT_CIRCUITED 状态
 *     Proxy->>Event: fireActivationEvent(false, false)
 *
 *     Game->>FBO: initialize(true)
 *     Note over FBO: Vulkan 未激活<br/>进入最小监视器模式
 *     FBO->>Event: addListener()
 *
 *     Note over VulkanMod: 用户加载 vulkan-backport 模组
 *     VulkanMod->>Proxy: initializeWithVulkanHandles(device, queue)
 *     Note over Proxy: Vulkan 激活<br/>状态变为 VULKAN_ACTIVE
 *     Proxy->>Event: fireActivationEvent(false, true)
 *
 *     Event->>FBO: onActivationChanged(false, true)
 *     Note over FBO: 检测到 Vulkan 激活<br/>执行实际初始化
 *     FBO->>FBO: performInitialization()
 *     Note over FBO: 拦截器启用成功
 * </pre>
 *
 * @author Renderium Team
 * @version 3.0
 */
@Mixin(targets = "net.minecraft.client.render.RenderSystem")
public class MixinVulkanBackend {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("VulkanBackport");

    /**
     * 检测 Vulkan 运行时可用性
     */
    @Inject(method = "initBackend", at = @At("HEAD"), cancellable = false)
    private static void onInitBackend(CallbackInfo ci) {
        try {
            boolean vulkanSupported = GLFWVulkan.glfwVulkanSupported();

            if (vulkanSupported) {
                LOGGER.info("[VulkanBackport] ✓ Vulkan runtime available");
                RenderiumDebug.log("Vulkan", "Runtime detected - backend pending mod load");
            } else {
                LOGGER.info("[VulkanBackport] ✗ Vulkan not supported");
                RenderiumDebug.log("Vulkan", "Not supported - using OpenGL fallback");
            }

            // 注意：不取消原始方法，保留 OpenGL 作为后备路径
            // 完整的 Vulkan 后端切换将由 vulkan-backport 模组通过事件系统实现

        } catch (Exception e) {
            LOGGER.warning("[VulkanBackport] Detection failed: " + e.getMessage());
            RenderiumDebug.warn("Vulkan", "Detection error: {}", e.getMessage());
        }
    }
}
