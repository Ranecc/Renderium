package com.renderium.vulkan.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.logging.Logger;

/**
 * Window Mixin - 设置 GLFW 窗口提示以支持 Vulkan 渲染
 *
 * <p>该 Mixin 目标为 Minecraft 的 Window 类，用于在 GLFW 窗口创建之前
 * 配置 Vulkan 所需的窗口提示。关键操作是将 GLFW 客户端 API 设置为 NO_API，
 * 因为 Vulkan 管理自己的图形 API，不需要 OpenGL 上下文。</p>
 *
 * <h3>注入策略:</h3>
 * <ul>
 *   <li>目标方法: Window 构造函数 (&lt;init&gt;)</li>
 *   <li>注入时机: 方法头部 (HEAD) - 确保在 Minecraft 任何窗口创建代码之前执行</li>
 * </ul>
 *
 * <h3>Vulkan 与 GLFW 集成要点:</h3>
 * <ul>
 *   <li><b>GLFW_CLIENT_API = GLFW_NO_API</b>: 告诉 GLFW 不要创建 OpenGL/ES 上下文</li>
 *   <li><b>Vulkan Surface 创建</b>: 后续通过 vkCreateWin32SurfaceKHR/XcbSurfaceKHR 等 API 创建</li>
 *   <li><b>降级策略</b>: 如果设置失败，记录警告并允许 OpenGL 正常初始化</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 1.0
 * @see <a href="https://www.glfw.org/docs/latest/group__window.html#ga4bd8013e01a4fbab06941d8d337f1797">GLFW Window Hints</a>
 */
@Mixin(targets = "net.minecraft.client.util.Window")
public class MixinVulkanWindow {

    /** 日志记录器，用于输出 GLFW 窗口配置状态和错误信息 */
    private static final Logger LOGGER = Logger.getLogger("VulkanBackport");

    /**
     * 在 Window 构造函数头部注入 GLFW Vulkan 窗口提示设置逻辑
     *
     * <p>该方法在 Minecraft 创建游戏窗口时触发（通常在 MinecraftClient 初始化阶段），
     * 执行以下关键操作:</p>
     * <ol>
     *   <li><b>禁用 OpenGL 上下文</b>: 设置 GLFW_CLIENT_API 为 GLFW_NO_API (值为 0)</li>
     *   <li><b>日志确认</b>: 记录 Vulkan 提示设置成功，便于调试和问题追踪</li>
     *   <li><b>异常保护</b>: 捕获任何 LWJGL/GLFW 异常并输出警告日志</li>
     * </ol>
     *
     * <h3>技术细节:</h3>
     * <p>Vulkan 不使用 GLFW 提供的上下文管理机制。相反，Vulkan 通过以下方式与窗口系统集成:
     * <ul>
     *   <li>Windows: 使用 VK_KHR_win32_surface 扩展创建 VkSurfaceKHR</li>
     *   <li>Linux: 使用 VK_KHR_xcb_surface 或 VK_KHR_xlib_surface 扩展</li>
     *   <li>macOS: 使用 VK_MVK_metal_surface 或 VK_EXT_metal_surface 扩展</li>
     * </ul></p>
     *
     * @param ci Mixin 回调信息对象，用于控制原始方法的执行流程
     */
    @Inject(method = "<init>", at = @At("HEAD"))
    private void onWindowInit(CallbackInfo ci) {
        try {
            // 关键步骤：设置 GLFW 客户端 API 为"无"
            // 值说明：
            // - GLFW_NO_API (0): 不创建任何图形 API 上下文（Vulkan 模式）
            // - GLFW_OPENGL_API (1): 创建 OpenGL 上下文（默认模式）
            // - GLFW_OPENGL_ES_API (2): 创建 OpenGL ES 上下文（移动端）
            //
            // Vulkan 要求此设置为 NO_API，原因：
            // 1. Vulkan 有自己独立的实例和设备创建流程
            // 2. 不需要 GLFW 管理 OpenGL 上下文的创建/销毁/切换
            // 3. 避免 OpenGL 和 Vulkan 之间的资源冲突
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);

            // 记录详细日志：确认 Vulkan 窗口提示已成功设置
            // 日志级别使用 FINE 而非 INFO，避免在正常启动时产生过多输出
            // 开发者可通过调整 logging.properties 启用 FINE 级别查看详细信息
            LOGGER.fine("[VulkanBackport] Set GLFW_CLIENT_API to NO_API for Vulkan support");

        } catch (Exception e) {
            // 异常处理：GLFW 窗口提示设置失败不应阻止游戏启动
            // 可能的失败原因：
            // - GLFW 未正确初始化 (glfwInit() 未调用或失败)
            // - LWJGL Native 库加载失败 (缺少 lwjgl-glfw-native)
            // - 平台特定的 GLFW 实现问题
            //
            // 降级策略：允许原始方法继续执行，Minecraft 将使用默认的 OpenGL 后端
            LOGGER.warning("[VulkanBackport] Failed to set GLFW window hints: " + e.getMessage());
        }
    }
}
