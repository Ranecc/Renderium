// Renderium - 兼容模式出口拦截器
// 在 Sodium/Iris 完成渲染后截胡最终 FBO 画面，移入 Vulkan 管线

package com.renderium.compatibility;

import com.renderium.core.RenderiumCore;
import com.renderium.core.RenderiumDualModeManager;
import com.renderium.core.RenderiumMode;
import com.renderium.graphics.backend.PassRouter;

import java.util.logging.Logger;

/**
 * 兼容模式出口拦截器。
 *
 * <p>设计思路来自"双轨制.md §一（兼容模式）"和"总体概念设计.md §四"：
 * <b>铁律：零 Import，只守出口。</b>
 * 不去实现 Sodium 的 API，也不去管它的内部面剔除逻辑。
 * 当 Sodium 辛辛苦苦算完紧凑顶点，准备调用底层方法上传显存（如 upload 或 draw）
 * 的那一微秒，用 Mixin 拦截。偷走它的顶点数组/最终画面，转化格式，
 * 扔进 CommandBuffer 走 Vulkan。
 *
 * <h2>拦截时机（来自 总体概念设计.md §四）</h2>
 * <ol>
 *   <li><b>Sodium 出口</b>：Sodium 的 {@code ChunkRenderBackend} 或 {@code GlFramebuffer}
 *       在最终提交绘制时（swap 之前）</li>
 *   <li><b>Iris 出口</b>：Iris 的 {@code CompositeRenderer} 在完成所有 Pass 后、
 *       SwapChain 呈现前</li>
 *   <li><b>原版出口</b>：{@code LevelRenderer} 的 {@code renderLevel()} 返回前</li>
 * </ol>
 *
 * <h2>OpenGL 光影裁决（总体概念设计 §五）</h2>
 * <p>在兼容模式下：
 * <ul>
 *   <li>Iris/Oculus 正常运行 OpenGL 光影管线</li>
 *   <li>Renderium 在 flip 前一刻截胡最终画面</li>
 *   <li>将画面作为纹理输入 Vulkan 管线做 DLSS/FSR 后处理</li>
 *   <li>处理后的结果呈现到屏幕</li>
 * </ul>
 *
 * <h2>出口 Hook 白嫖策略（来自 智能短路.md §第一类红利）</h2>
 * <p>不读底层结构，Hook 官方的"出口函数"。官方再怎么改内部的数据结构，
 * 它最终总要生成一个"准备交给 GPU 的顶点数组"。只需要在官方生成这个数组的
 * 最后一微秒拦截，偷走这个数组。官方花三个月把区块构建速度提升了 30%，
 * 你一行代码不用改，你的 Mesh 构建器自动就快了 30%。
 * 通过 {@link PassRouter} 实现对官方 Pass 的选择性放行。
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class CompatibilityExitInterceptor {

    private static final Logger LOGGER = Logger.getLogger("Renderium-Intercept");

    /** 单例实例 */
    private static volatile CompatibilityExitInterceptor instance;

    /** 是否启用拦截（仅在兼容模式下启用） */
    private volatile boolean enabled = false;

    /** 当前帧的输出纹理 handle (Vulkan Image) */
    private long currentFrameOutputTexture = 0L;

    /** 当前帧的深度纹理 handle (Vulkan Image) */
    private long currentFrameDepthTexture = 0L;

    /** 上一帧的输出纹理（用于帧生成 motion vector 计算） */
    private long previousFrameOutputTexture = 0L;

    /** 帧计数器（用于调试和性能监控） */
    private int frameCount = 0;

    /** 统计：成功拦截次数 */
    private int successfulIntercepts = 0;

    /** 统计：失败拦截次数 */
    private int failedIntercepts = 0;

    /**
     * 私有构造函数
     */
    private CompatibilityExitInterceptor() {}

    /**
     * 获取单例实例（线程安全懒加载）
     *
     * @return CompatibilityExitInterceptor 唯一实例
     */
    public static synchronized CompatibilityExitInterceptor getInstance() {
        if (instance == null) {
            instance = new CompatibilityExitInterceptor();
        }
        return instance;
    }

    // ==================== 初始化 ====================

    /**
     * 初始化拦截器
     *
     * <p>必须在 RenderiumCore 初始化之后调用。
     * 检查当前运行模式，仅在兼容模式下启用拦截功能。
     */
    public void initialize() {
        RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();

        // 只有兼容模式才启用拦截器
        if (!dualMode.isCompatibleMode()) {
            this.enabled = false;
            LOGGER.info("非兼容模式（当前: " + dualMode.getCurrentMode().getDisplayName() +
                    "），禁用出口拦截器");
            return;
        }

        this.enabled = true;
        LOGGER.info("═══ 兼容模式出口拦截器已初始化 ═══");
        LOGGER.info("  当前模式:     " + dualMode.getCurrentMode().getDisplayName());
        LOGGER.info("  拦截目标:");
        LOGGER.info("    • Sodium 最终绘制输出 (ChunkRenderBackend)");
        LOGGER.info("    • Iris 合成输出 (CompositeRenderer)");
        LOGGER.info("    • 原版渲染输出 (LevelRenderer)");
        LOGGER.info("  拦截行为: 截取 FBO → Vulkan 后处理 → 屏幕呈现");
        LOGGER.info("  光影支持: OpenGL 光影由 Iris/Oculus 处理");
        LOGGER.info("════════════════════════════════");
    }

    // ==================== 核心拦截接口 ====================

    /**
     * 拦截 Sodium 的区块渲染完成事件
     *
     * <p>Mixin 注入点：Sodium 的渲染循环结束时（在 swap 之前）。
     * 此时 Sodium 已经完成了所有 chunk 的光栅化，最终画面在 FBO 中。
     *
     * <h3>调用方式（Mixin 示例）</h3>
     * <pre>{@code
     * @Inject(method = "render", at = @At("RETURN"), remap = false)
     * private void renderium_interceptSodium(CallbackInfo ci) {
     *     CompatibilityExitInterceptor.getInstance().interceptSodiumOutput(
     *         this.colorFboId, this.depthFboId, screenWidth, screenHeight);
     * }
     * }</pre>
     *
     * @param colorFboId Sodium 最终输出的颜色 FBO ID (OpenGL)
     * @param depthFboId Sodium 最终输出的深度 FBO ID (OpenGL)
     * @param screenWidth 屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     * @return true 如果成功拦截并转交 Vulkan 管线
     */
    public boolean interceptSodiumOutput(int colorFboId, int depthFboId, int screenWidth, int screenHeight) {
        if (!enabled) return false;

        frameCount++;
        LOGGER.fine(String.format("[%d] 拦截 Sodium 输出: colorFbo=%d, depthFbo=%d, size=%dx%d",
                frameCount, colorFboId, depthFboId, screenWidth, screenHeight));

        // Step 1: 将 OpenGL FBO 内容转换为 Vulkan 纹理
        // （通过 GL→Vulkan 互操作桥接层实现）
        long vulkanColorTexture = importGLFramebufferToVulkan(colorFboId, screenWidth, screenHeight);
        long vulkanDepthTexture = importGLFramebufferToVulkan(depthFboId, screenWidth, screenHeight);

        if (vulkanColorTexture == 0L) {
            LOGGER.warning("无法导入 OpenGL 颜色 FBO 到 Vulkan，跳过后处理");
            failedIntercepts++;
            return false;
        }

        // Step 2: 保存纹理引用（当前帧和上一帧）
        this.previousFrameOutputTexture = this.currentFrameOutputTexture;
        this.currentFrameOutputTexture = vulkanColorTexture;
        this.currentFrameDepthTexture = vulkanDepthTexture;

        // Step 3: 提交给 Vulkan 后端做超分辨率/帧生成处理
        submitToVulkanPipeline(screenWidth, screenHeight);

        successfulIntercepts++;
        return true;
    }

    /**
     * 拦截 Iris 的合成渲染完成事件
     *
     * <p>Mixin 注入点：Iris 的 {@code CompositeRenderer.render()} 返回前。
     * Iris 完成了所有光影 Pass（几何、延迟、合成等），最终画面在主 FBO 中。
     *
     * <h3>特殊处理</h3>
     * <p>Iris 可能使用多个 FBO 进行链式后处理，
     * 我们需要拦截的是**最终合成结果**（通常是 main FBO 或 output FBO）。
     *
     * @param irisMainFbo Iris 主 FBO 的 OpenGL ID（最终合成结果）
     * @param screenWidth 屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     * @param shaderPackName 当前加载的光影包名称（用于诊断日志）
     * @return true 如果成功拦截并转交 Vulkan 管线
     */
    public boolean interceptIrisComposite(int irisMainFbo, int screenWidth, int screenHeight, String shaderPackName) {
        if (!enabled) return false;

        frameCount++;
        LOGGER.fine(String.format("[%d] 拦截 Iris 合成输出: fbo=%d, shader=%s, size=%dx%d",
                frameCount, irisMainFbo,
                shaderPackName != null ? shaderPackName : "unknown",
                screenWidth, screenHeight));

        // 与 Sodium 相同的处理流程
        long vulkanTexture = importGLFramebufferToVulkan(irisMainFbo, screenWidth, screenHeight);
        if (vulkanTexture == 0L) {
            LOGGER.warning("无法导入 Iris FBO 到 Vulkan，跳过后处理");
            failedIntercepts++;
            return false;
        }

        // 更新帧纹理引用
        this.previousFrameOutputTexture = this.currentFrameOutputTexture;
        this.currentFrameOutputTexture = vulkanTexture;

        // 提交到 Vulkan 管线
        submitToVulkanPipeline(screenWidth, screenHeight);

        successfulIntercepts++;
        return true;
    }

    /**
     * 拦截原版 LevelRenderer 的渲染完成事件
     *
     * <p>这是兜底拦截点：当没有 Sodium 也没有 Iris 时，
     * 原版的渲染结果也会被这里捕获。
     *
     * <h3>注意</h3>
     * <p>原版模式下可能没有独立的 FBO（直接渲染到屏幕），
     * 此时需要从屏幕缓冲区读取（glReadPixels 或类似机制）。
     *
     * @param defaultFbo 默认 FBO（通常 0 = 屏幕 FBO / 默认 framebuffer）
     * @param screenWidth 屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     * @return true 如果成功拦截
     */
    public boolean interceptVanillaOutput(int defaultFbo, int screenWidth, int screenHeight) {
        if (!enabled) return false;

        // 原版模式可能没有独立 FBO，直接从屏幕缓冲区读取
        long vulkanTexture = readScreenBufferToVulkan(screenWidth, screenHeight);
        if (vulkanTexture == 0L) {
            failedIntercepts++;
            return false;
        }

        this.previousFrameOutputTexture = this.currentFrameOutputTexture;
        this.currentFrameOutputTexture = vulkanTexture;

        submitToVulkanPipeline(screenWidth, screenHeight);

        successfulIntercepts++;
        return true;
    }

    // ==================== 内部实现方法 ====================

    /**
     * 将 OpenGL Framebuffer 导入为 Vulkan 纹理
     *
     * <p>这需要通过一个 GL→Vulkan 的互操作桥接层来实现。
     * 可能的实现方式：
     * <ol>
     *   <li>VK_EXT_external_memory_opengl 扩展（最佳性能，但需驱动支持）</li>
     *   <li>VK_EXT_external_memory_host + glReadPixels（通用方案）</li>
     *   <li>VkSemaphore GL/Vulkan 同步（避免竞争条件）</li>
     *   <li>回退方案：glReadPixels → memcpy → vkCreateImage + 上传</li>
     * </ol>
     *
     * @param glFboId OpenGL Framebuffer Object ID
     * @param width 纹理宽度（像素）
     * @param height 纹理高度（像素）
     * @return Vulkan Image handle（非零表示成功），失败返回 0
     */
    private long importGLFramebufferToVulkan(int glFboId, int width, int height) {
        // TODO: 实现 GL FBO → VkImage 的高效转换
        // 这需要用到 LWJGL 的 GLFWOpenGLContext 或自定义 JNI 桥接

        // 临时返回模拟 handle（实际实现时替换为真正的 VkImage）
        // 格式：(glFboId << 32) | (width << 16) | height
        return ((long) glFboId << 32) | ((long) width << 16) | height;
    }

    /**
     * 从屏幕缓冲区读取数据并转换为 Vulkan 纹理
     *
     * <p>当没有独立的 FBO 时使用此方法（原版渲染模式）。
     * 使用 glReadPixels 读取屏幕内容，然后上传到 Vulkan。
     *
     * @param width 屏幕宽度（像素）
     * @param height 屏幕高度（像素）
     * @return Vulkan Image handle，失败返回 0
     */
    private long readScreenBufferToVulkan(int width, int height) {
        // TODO: 实现屏幕缓冲区 → Vulkan Image 的转换
        // 1. glBindFramebuffer(GL_FRAMEBUFFER, 0)
        // 2. glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        // 3. memcpy 到 staging buffer → vkCmdCopyBufferToImage

        // 临时返回模拟 handle
        return System.identityHashCode(new Object()) & 0x7FFFFFFFL;
    }

    /**
     * 提交给 Vulkan 后端进行后处理
     *
     * <p>完整的后处理流水线：
     * <ol>
     *   <li>更新 RenderiumCore 的纹理引用（颜色+深度）</li>
     *   <li>通过 PassRouter 检查各 Pass 的路由决策</li>
     *   <li>执行超分辨率处理（DLSS/XeSS/FSR）</li>
     *   <li>执行帧生成（如果启用）</li>
     *   <li>执行 Vulkan 光影后期（如果有活跃的 RGB 包）</li>
     *   <li>呈现到屏幕（present）</li>
     * </ol>
     *
     * @param width 输出宽度（像素）
     * @param height 输出高度（像素）
     */
    private void submitToVulkanPipeline(int width, int height) {
        RenderiumCore core = RenderiumCore.getInstance();

        // Step 1: 更新核心管理器的纹理引用
        core.updateTextures(
                currentFrameDepthTexture,      // 深度纹理
                currentFrameOutputTexture,     // 颜色纹理
                width,                         // 宽度
                height                         // 高度
        );

        // Step 2: 通过 PassRouter 检查路由决策（智能短路.md §第三类红利）
        PassRouter passRouter = PassRouter.getInstance();

        // Step 3: 执行超分辨率处理（DLSS/XeSS/FSR）
        if (core.isSuperResolutionEnabled() &&
                passRouter.isRenderiumOwned("SuperResolution")) {
            core.processSuperResolution();
        }

        // Step 4: 执行帧生成（DLSS-G / FSR-FG）
        if (core.isFrameGenerationEnabled() &&
                passRouter.isRenderiumOwned("FrameGeneration")) {
            core.processFrameGeneration();
        }

        // Step 5: 执行 Vulkan 光影后期（如果存在活跃的 .rgb 光影包）
        // TODO: 调用 ShaderWorkbench.executePostProcess()

        // Step 6: 呈现到屏幕
        core.presentFrame();
    }

    // ==================== 控制与查询接口 ====================

    /**
     * 启用或禁用拦截器
     *
     * @param enabled true 启用，false 禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("出口拦截器 " + (enabled ? "已启用" : "已禁用"));
    }

    /** 是否已启用 */
    public boolean isEnabled() { return enabled; }

    /** 获取当前帧的输出纹理 (Vulkan Image) */
    public long getCurrentFrameOutputTexture() { return currentFrameOutputTexture; }

    /** 获取当前帧的深度纹理 (Vulkan Image) */
    public long getCurrentFrameDepthTexture() { return currentFrameDepthTexture; }

    /** 获取上一帧的输出纹理（用于运动向量计算） */
    public long getPreviousFrameOutputTexture() { return previousFrameOutputTexture; }

    /** 获取当前帧号（单调递增） */
    public int getFrameCount() { return frameCount; }

    /** 获取成功拦截次数 */
    public int getSuccessfulIntercepts() { return successfulIntercepts; }

    /** 获取失败拦截次数 */
    public int getFailedIntercepts() { return failedIntercepts; }

    /** 重置统计计数器 */
    public void resetStats() {
        successfulIntercepts = 0;
        failedIntercepts = 0;
    }

    @Override
    public String toString() {
        return String.format(
                "CompatibilityExitInterceptor{enabled=%s, frames=%d, success=%d, fail=%d, tex=0x%X}",
                enabled, frameCount, successfulIntercepts, failedIntercepts,
                currentFrameOutputTexture
        );
    }
}
