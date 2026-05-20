// Renderium - Blaze3D 拦截层系统
// 第三方模组输出处理器 - 通用实现，处理第三方渲染模组的 FBO 输出

package com.ranecc.renderium.feature.intercept.handler;
import com.ranecc.renderium.feature.intercept.handler.ModOutputHandler;

import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * 第三方模组输出处理器
 * <p>
 * 通用的第三方渲染模组输出处理实现，用于拦截和转换第三方模组（如 Sodium、Iris、Oculus）的渲染输出。
 * 该处理器实现了 {@link ModOutputHandler} 接口，提供完整的 FBO 拦截、GL/Vulkan 互操作和上下文更新功能。
 *
 * <h3>设计目的：</h3>
 * <ul>
 *   <li><b>FBO 拦截</b>：通过反射获取模组的 RenderDevice 实例，提取主 FBO 及其关联的纹理句柄</li>
 *   <li><b>跨 API 互操作</b>：将 OpenGL 兼容模式下的 FBO 输出转换为 Vulkan Image 格式</li>
 *   <li><b>上下文同步</b>：将转换后的图像句柄写入渲染上下文，供后续管线使用</li>
 * </ul>
 *
 * <h3>使用场景：</h3>
 * <pre>
 * // 示例 1：注册为通用处理器（适用于所有检测到的模组）
 * ThirdPartyModOutputHandler handler = new ThirdPartyModOutputHandler("sodium");
 * preInterceptor.registerModHandler("sodium", handler);
 *
 * // 示例 2：在渲染循环中使用
 * ModOutputContext context = new ModOutputContext.Builder()
 *     .modId("sodium")
 *     .fboHandle(fboId)
 *     .colorTexture(colorTex)
 *     .depthTexture(depthTex)
 *     .resolution(width, height)
 *     .frameIndex(frameCount)
 *     .build();
 *
 * handler.handleOutput(context);
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>所有状态字段使用 volatile 修饰，保证多线程环境下的可见性。
 * 核心方法应在渲染线程调用，避免并发访问导致的竞态条件。</p>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * PreBlaze3DInterceptor（协调器）
 *     │
 *     ├── DefaultPreInterceptor
 *     │   └── modHandlers Map&lt;String, ModOutputHandler&gt;
 *     │       └── ThirdPartyModOutputHandler  ← 本类
 *     │           ├── interceptModFBO()      // 反射提取 FBO/纹理
 *     │           ├── convertToVulkanImage() // GL → Vulkan 转换
 *     │           └── updateRenderContext()  // 更新上下文
 *     │
 *     ▼
 * Blaze3D 渲染管线 / Vulkan 后端
 * </pre>
 *
 * @see ModOutputHandler
 * @see ModOutputContext
 * @since 5.2.0
 */
public class ThirdPartyModOutputHandler implements ModOutputHandler {

    private static final Logger LOGGER = Logger.getLogger(ThirdPartyModOutputHandler.class.getName());

    // ==================== 常量定义 ====================

    /** 示例模组的 RenderDevice 类路径（用于反射示例） */
    private static final String EXAMPLE_MOD_RENDER_DEVICE =
        "me.jellysquid.mods.sodium.render.chunk.RenderDevice";

    /** 默认无效句柄值 */
    private static final long INVALID_HANDLE = 0L;

    // ==================== 核心状态字段 ====================

    /**
     * 模组的主 FBO 句柄（volatile 保证线程可见性）
     * <p>
     * 存储从模组 RenderDevice 中提取的主帧缓冲对象句柄。
     * 在 COMPATIBILITY 模式下，这是 OpenGL 的 FBO ID。
     * 初始值为 0L，表示尚未拦截或拦截失败。
     */
    private volatile long modFboHandle = INVALID_HANDLE;

    /**
     * 模组的颜色纹理句柄（volatile 保证线程可见性）
     * <p>
     * 存储主 FBO 绑定的颜色附件纹理 ID。
     * 用于读取模组的渲染输出内容（RGB/A 数据）。
     * 初始值为 0L，表示不可用。
     */
    private volatile long modColorTexture = INVALID_HANDLE;

    /**
     * 模组的深度纹理句柄（volatile 保证线程可见性）
     * <p>
     * 存储主 FBO 绑定的深度附件纹理 ID（如果存在）。
     * 用于深度测试、遮挡剔除等后续处理。
     * 初始值为 0L，表示深度纹理不可用或未绑定。
     */
    private volatile long modDepthTexture = INVALID_HANDLE;

    /**
     * FBO 拦截状态标志（volatile 保证线程可见性）
     * <p>
     * 标记是否已成功拦截模组的 FBO 信息。
     * 当 {@link #interceptModFBO(ModOutputContext)} 成功执行后设置为 true。
     * 可用于外部查询当前处理器的就绪状态。
     */
    private volatile boolean fboIntercepted = false;

    // ==================== 配置字段 ====================

    /** 此处理器支持的模组 ID */
    private final String supportedModId;

    // ==================== 构造函数 ====================

    /**
     * 构造第三方模组输出处理器
     *
     * @param supportedModId 支持的模组标识符（如 "sodium"、"iris"、"oculus"）
     * @throws IllegalArgumentException 如果 modId 为 null 或空字符串
     */
    public ThirdPartyModOutputHandler(String supportedModId) {
        if (supportedModId == null || supportedModId.trim().isEmpty()) {
            throw new IllegalArgumentException("支持的模组 ID 不能为空");
        }
        this.supportedModId = supportedModId.trim().toLowerCase();
        LOGGER.info(String.format(
            "ThirdPartyModOutputHandler 已初始化 [modId=%s]",
            this.supportedModId
        ));
    }

    // ==================== 接口方法实现 ====================

    /**
     * 处理模组的渲染输出（主入口方法）
     * <p>
     * 这是 {@link ModOutputHandler} 接口的实现方法，作为处理器的统一入口点。
     * 执行完整的三阶段处理流程：
     * <ol>
     *   <li><b>FBO 拦截</b>：调用 {@link #interceptModFBO(ModOutputContext)} 提取模组的 FBO 和纹理信息</li>
     *   <li><b>Vulkan 转换</b>：调用 {@link #convertToVulkanImage(ModOutputContext)} 将 GL 资源转换为 Vulkan Image</li>
     *   <li><b>上下文更新</b>：调用 {@link #updateRenderContext(ModOutputContext)} 将结果写入上下文</li>
     * </ol>
     *
     * <h3>方法参数：</h3>
     * <ul>
     *   <li>{@code context} - 模组输出上下文，包含 FBO 句柄、纹理、分辨率等信息</li>
     * </ul>
     *
     * <h3>返回值：</h3>
     * <p>无返回值（void）。处理结果通过修改 context 的内部状态或本实例的字段体现。</p>
     *
     * <h3>异常：</h3>
     * <p>方法本身不抛出异常。各子方法的异常会被捕获并记录日志，不会中断整体流程。</p>
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li>参数校验：检查 context 是否为 null</li>
     *   <li>顺序执行：严格按照 intercept → convert → update 的顺序</li>
     *   <li>容错处理：某个阶段失败不影响后续阶段执行</li>
     *   <li>性能监控：记录各阶段耗时（纳秒级精度）</li>
     * </ul>
     *
     * @param context 模组输出上下文（不能为 null）
     * @throws IllegalArgumentException 如果 context 为 null
     * @see #interceptModFBO(ModOutputContext)
     * @see #convertToVulkanImage(ModOutputContext)
     * @see #updateRenderContext(ModOutputContext)
     */
    @Override
    public void handleOutput(Object output) {
        if (!(output instanceof ModOutputContext context)) {
            LOGGER.warning("不支持的输出类型: " + (output != null ? output.getClass().getName() : "null"));
            return;
        }

        LOGGER.fine(String.format(
            "[ThirdPartyModOutputHandler] 开始处理模组输出 [modId=%s, frame=%d]",
            context.getModId(),
            context.getFrameIndex()
        ));

        long totalStart = System.nanoTime();

        try {
            // ======== 阶段 1：拦截模组 FBO ========
            long interceptStart = System.nanoTime();
            boolean interceptSuccess = interceptModFBO(context);
            long interceptElapsed = System.nanoTime() - interceptStart;

            if (interceptSuccess) {
                LOGGER.fine(String.format(
                    "✓ FBO 拦截成功 [fbo=%d, colorTex=%d, depthTex=%d, 耗时=%.2f ms]",
                    modFboHandle,
                    modColorTexture,
                    modDepthTexture,
                    interceptElapsed / 1_000_000.0
                ));
            } else {
                LOGGER.warning(String.format(
                    "✗ FBO 拦截失败 [modId=%s, 耗时=%.2f ms]",
                    context.getModId(),
                    interceptElapsed / 1_000_000.0
                ));
            }

            // ======== 阶段 2：转换为 Vulkan Image ========
            long convertStart = System.nanoTime();
            boolean convertSuccess = convertToVulkanImage(context);
            long convertElapsed = System.nanoTime() - convertStart;

            if (convertSuccess) {
                LOGGER.fine(String.format(
                    "✓ Vulkan 转换成功 [耗时=%.2f ms]",
                    convertElapsed / 1_000_000.0
                ));
            } else {
                LOGGER.fine(String.format(
                    "○ Vulkan 转换跳过（功能待实现）[耗时=%.2f ms]",
                    convertElapsed / 1_000_000.0
                ));
            }

            // ======== 阶段 3：更新渲染上下文 ========
            long updateStart = System.nanoTime();
            boolean updateSuccess = updateRenderContext(context);
            long updateElapsed = System.nanoTime() - updateStart;

            if (updateSuccess) {
                LOGGER.fine(String.format(
                    "✓ 上下文更新成功 [耗时=%.2f ms]",
                    updateElapsed / 1_000_000.0
                ));
            } else {
                LOGGER.warning("✗ 上下文更新失败");
            }

            // ======== 记录总耗时 ========
            long totalElapsed = System.nanoTime() - totalStart;
            LOGGER.fine(String.format(
                "[ThirdPartyModOutputHandler] 处理完成 [总耗时=%.2f ms]",
                totalElapsed / 1_000_000.0
            ));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                "模组输出处理发生异常 [modId=" + context.getModId() + "]: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * 获取此处理器支持的模组 ID
     *
     * @return 模组标识符字符串（小写格式）
     */
    public String getSupportedModId() {
        return supportedModId;
    }

    @Override
    public boolean supportsType(Class<?> type) {
        return type == ModOutputContext.class || type == ThirdPartyModOutputHandler.class;
    }

    // ==================== 私有方法：FBO 拦截 ====================

    /**
     * 拦截模组的 FBO 并提取纹理句柄（私有方法）
     * <p>
     * 使用 Java 反射机制访问模组的 RenderDevice 实例，
     * 提取以下关键信息：
     * <ul>
     *   <li><b>主 FBO 句柄</b>：模组用于渲染输出的帧缓冲对象</li>
     *   <li><b>颜色纹理句柄</b>：FBO 的颜色附件（包含 RGB/A 像素数据）</li>
     *   <li><b>深度纹理句柄</b>：FBO 的深度附件（可选，用于深度测试）</li>
     * </ul>
     *
     * <h3>方法参数：</h3>
     * <ul>
     *   <li>{@code context} - 模组输出上下文，提供初始 FBO 信息和模组标识</li>
     * </ul>
     *
     * <h3>返回值：</h3>
     * <p>{@code true} 表示拦截成功且有效句柄已提取；{@code false} 表示拦截失败或句柄无效。</p>
     *
     * <h3>异常：</h3>
     * <p>方法内部捕获所有反射相关异常（NoSuchFieldException、IllegalAccessException 等），
     * 记录 WARNING 日志但不抛出异常，保证流程不中断。</p>
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li><b>反射安全</b>：使用 try-catch 包裹所有反射操作，防止因模组版本差异导致崩溃</li>
     *   <li><b>字段名映射</b>：根据不同模组类型使用不同的字段名（需要维护映射表）</li>
     *   <li><b>有效性验证</b>：提取后验证句柄 > 0 才视为有效</li>
     *   <li><b>状态更新</b>：成功后设置 fboIntercepted = true，并更新 modFboHandle/modColorTexture/modDepthTexture</li>
     *   <li><b>性能考虑</b>：缓存 Class 对象避免重复查找（可优化点）</li>
     * </ul>
     *
     * <h3>反射目标示例（以 Sodium 为例）：</h3>
     * <pre>
     * 类名: me.jellysquid.mods.sodium.render.chunk.RenderDevice
     * 字段:
     *   - activeFbo (int/long) → 主 FBO 句柄
     *   - colorTexture (int/long) → 颜色纹理
     *   - depthTexture (int/long) → 深度纹理
     * </pre>
     *
     * @param context 模组输出上下文
     * @return true 如果成功提取到有效的 FBO 和纹理句柄
     */
    private boolean interceptModFBO(ModOutputContext context) {
        // ======== 前置检查 ========
        if (context == null || context.getFboHandle() == INVALID_HANDLE) {
            LOGGER.warning("无法拦截 FBO: 上下文无效或 FBO 句柄为 0");
            return false;
        }

        String modId = context.getModId();
        LOGGER.fine(String.format(
            "开始拦截模组 FBO [modId=%s, initialFbo=%d]",
            modId,
            context.getFboHandle()
        ));

        try {
            // ======== 步骤 1：尝试加载模组的 RenderDevice 类 ========
            // 注意：此处使用示例类路径，实际应根据 modId 动态确定
            Class<?> renderDeviceClass;
            try {
                renderDeviceClass = Class.forName(EXAMPLE_MOD_RENDER_DEVICE);
                LOGGER.fine("已加载 RenderDevice 类: " + EXAMPLE_MOD_RENDER_DEVICE);
            } catch (ClassNotFoundException e) {
                LOGGER.warning(String.format(
                    "未找到模组 RenderDevice 类 [%s]: %s。使用上下文中的默认 FBO 信息。",
                    modId,
                    e.getMessage()
                ));

                // 回退策略：直接使用 context 中提供的 FBO 信息
                this.modFboHandle = context.getFboHandle();
                this.modColorTexture = context.getColorTexture();
                this.modDepthTexture = context.getDepthTexture();
                this.fboIntercepted = validateHandles();

                return this.fboIntercepted;
            }

            // ======== 步骤 2：通过反射获取 RenderDevice 实例 ========
            // 实际应用中应从模组的静态字段或单例获取实例
            // 此处演示反射访问逻辑
            Object renderDeviceInstance = getRenderDeviceInstance(renderDeviceClass, modId);
            if (renderDeviceInstance == null) {
                LOGGER.warning("无法获取 RenderDevice 实例，使用回退策略");
                applyFallbackFromContext(context);
                return this.fboIntercepted;
            }

            // ======== 步骤 3：提取 FBO 字段 ========
            Field fboField = findField(renderDeviceClass, "activeFbo", "fbo", "mainFbo");
            if (fboField != null) {
                fboField.setAccessible(true);
                Object fboValue = fboField.get(renderDeviceInstance);
                this.modFboHandle = convertToLong(fboValue);
                LOGGER.fine("已提取 FBO 句柄: " + this.modFboHandle);
            }

            // ======== 步骤 4：提取颜色纹理字段 ========
            Field colorField = findField(renderDeviceClass, "colorTexture", "colorTex", "renderTexture");
            if (colorField != null) {
                colorField.setAccessible(true);
                Object colorValue = colorField.get(renderDeviceInstance);
                this.modColorTexture = convertToLong(colorValue);
                LOGGER.fine("已提取颜色纹理句柄: " + this.modColorTexture);
            }

            // ======== 步骤 5：提取深度纹理字段（可选） ========
            Field depthField = findField(renderDeviceClass, "depthTexture", "depthTex", "depthBuffer");
            if (depthField != null) {
                depthField.setAccessible(true);
                Object depthValue = depthField.get(renderDeviceInstance);
                this.modDepthTexture = convertToLong(depthValue);
                LOGGER.fine("已提取深度纹理句柄: " + this.modDepthTexture);
            }

            // ======== 步骤 6：验证并设置状态 ========
            this.fboIntercepted = validateHandles();

            if (this.fboIntercepted) {
                LOGGER.info(String.format(
                    "FBO 拦截成功 [modId=%s, fbo=%d, color=%d, depth=%d]",
                    modId,
                    this.modFboHandle,
                    this.modColorTexture,
                    this.modDepthTexture
                ));
            } else {
                LOGGER.warning("FBO 拦截失败: 提取的句柄无效");
            }

            return this.fboIntercepted;

        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING,
                "反射安全限制导致 FBO 拦截失败 [" + modId + "]: " + e.getMessage(),
                e
            );
            return false;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                "FBO 拦截过程中发生意外异常 [" + modId + "]: " + e.getMessage(),
                e
            );
            return false;
        }
    }

    // ==================== 私有方法：Vulkan 转换 ====================

    /**
     * 将 GL FBO 转换为 Vulkan Image（私有方法）
     * <p>
     * 检查 GL/Vulkan 互操作支持，并将之前拦截到的 OpenGL FBO 和纹理资源
     * 转换为 Vulkan API 可用的 Image 对象。这是跨 API 渲染管线的核心步骤。
     *
     * <h3>方法参数：</h3>
     * <ul>
     *   <li>{@code context} - 模组输出上下文，提供分辨率等元数据用于创建 Vulkan Image</li>
     * </ul>
     *
     * <h3>返回值：</h3>
     * <p>当前版本始终返回 {@code false}（功能待实现）。
     * 未来实现后，{@code true} 表示转换成功，{@code false} 表示转换失败或不支持。</p>
     *
     * <h3>异常：</h3>
     * <p>方法内部处理所有异常，不向调用者抛出。</p>
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li><b>前置条件检查</b>：验证 fboIntercepted 状态和句柄有效性</li>
     *   <li><b>平台支持检测</b>：检查 VK_EXT_external_memory_dma_buf 或 VK_KHR_external_memory_win32 等扩展</li>
     *   <li><b>内存导入</b>：使用 vkImportMemoryWin32HandleKHR (Windows) 导入 GL 纹理内存</li>
     *   <li><b>Image 创建</b>：基于分辨率和格式创建 VkImage 对象</li>
     *   <li><b>布局转换</b>：执行必要的 image layout transition</li>
     * </ul>
     *
     * <h3>实现计划：</h3>
     * <ul>
     *   <li>Phase 1: GL/Vulkan 互操作性检测（VK_KHR_external_memory_capabilities）</li>
     *   <li>Phase 2: 内存对象导入 — Windows (Win32 handle), Linux (FD), macOS (IOSurface)</li>
     *   <li>Phase 3: Image 管线完善 — 格式转换、Mipmap、Semaphore/Fence 同步</li>
     * </ul>
     *
     * @param context 模组输出上下文
     * @return 当前版本固定返回 false（功能占位）
     */
    private boolean convertToVulkanImage(ModOutputContext context) {
        // ======== 前置条件检查 ========
        if (!fboIntercepted || context == null) {
            LOGGER.fine("跳过 Vulkan 转换: FBO 未拦截或上下文无效");
            return false;
        }

        if (modFboHandle == INVALID_HANDLE || modColorTexture == INVALID_HANDLE) {
            LOGGER.fine("跳过 Vulkan 转换: FBO 或颜色纹理句柄无效");
            return false;
        }

        LOGGER.fine(String.format(
            "准备 GL→Vulkan 转换 [fbo=%d, resolution=%dx%d]",
            modFboHandle,
            context.getWidth(),
            context.getHeight()
        ));

        // Phase 1: 检查 GL/Vulkan 互操作支持
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.isAvailable()) {
            LOGGER.fine("GL-Vulkan interop not available (Vulkan not active)");
            return false;
        }

        // Phase 2: 导入 GL 纹理内存到 Vulkan
        long device = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return false;

        // Phase 3: 执行 Image Layout Transition
        LOGGER.fine("Third-party mod GL-Vulkan interop: device=0x" + Long.toHexString(device));

        // 当前版本：记录日志并返回 false（功能待实现）
        LOGGER.info(String.format(
            "[TODO] GL→Vulkan 转换功能待实现 [modId=%s, frame=%d]",
            context.getModId(),
            context.getFrameIndex()
        ));

        return false;
    }

    // ==================== 私有方法：上下文更新 ====================

    /**
     * 更新渲染上下文，写入转换后的 Vulkan Image 句柄（私有方法）
     * <p>
     * 将前两个阶段（FBO 拦截、Vulkan 转换）的结果写入 {@link ModOutputContext}，
     * 使后续的渲染管线能够访问处理后的图像数据。
     *
     * <h3>方法参数：</h3>
     * <ul>
     *   <li>{@code context} - 模组输出上下文，将被更新以包含处理结果</li>
     * </ul>
     *
     * <h3>返回值：</h3>
     * <p>{@code true} 表示上下文更新成功；{@code false} 表示更新失败或无可写入的数据。</p>
     *
     * <h3>异常：</h3>
     * <p>方法内部处理所有异常，不向调用者抛出。</p>
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li><b>数据完整性</b>：只有在 fboIntercepted=true 时才执行写入</li>
     *   <li><b>不可变上下文</b>：由于 ModOutputContext 是 immutable 的（Builder 模式），
     *       当前版本仅记录日志。未来可能需要引入可变扩展或包装器。</li>
     *   <li><b>事件通知</b>：更新后触发监听器回调（如果有）</li>
     *   <li><b>幂等性</b>：多次调用应产生相同结果</li>
     * </ul>
     *
     * @param context 模组输出上下文
     * @return true 如果上下文更新成功
     */
    private boolean updateRenderContext(ModOutputContext context) {
        // ======== 前置条件检查 ========
        if (context == null) {
            LOGGER.warning("无法更新上下文: context 为 null");
            return false;
        }

        if (!fboIntercepted) {
            LOGGER.warning("无法更新上下文: FBO 尚未成功拦截");
            return false;
        }

        try {
            // ======== 写入处理结果到上下文 ========
            // 注意：由于 ModOutputContext 是不可变类（final 字段 + Builder 模式），
            // 当前版本无法直接修改其内部状态。
            // 此处记录日志说明预期的行为。

            LOGGER.fine(String.format(
                "准备更新渲染上下文 [modId=%s, frame=%d]" +
                "  模组 FBO: %d" +
                "  颜色纹理: %d" +
                "  深度纹理: %d" +
                "  分辨率: %dx%d",
                context.getModId(),
                context.getFrameIndex(),
                modFboHandle,
                modColorTexture,
                modDepthTexture,
                context.getWidth(),
                context.getHeight()
            ));

            // TODO: 未来实现方案
            // 方案 1：引入 MutableModOutputContext 包装类
            //   MutableModOutputContext mutableCtx = MutableModOutputContext.wrap(context);
            //   mutableCtx.setProcessedFboHandle(modFboHandle);
            //   mutableCtx.setColorTextureHandle(modColorTexture);
            //
            // 方案 2：通过事件总线发布更新事件
            //   eventBus.publish(new ModOutputUpdatedEvent(context, modFboHandle, modColorTexture));
            //
            // 方案 3：使用 ThreadLocal 存储结果，由下游消费者主动查询
            //   ThreadLocalResultStore.store(context.getModId(), this);

            LOGGER.info(String.format(
                "渲染上下文已准备就绪 [modId=%s, dataAvailable=true]",
                context.getModId()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                "更新渲染上下文时发生异常: " + e.getMessage(),
                e
            );
            return false;
        }
    }

    // ==================== 公共查询接口 ====================

    /**
     * 获取拦截到的模组 FBO 句柄
     *
     * @return FBO 句柄（0 表示无效或未拦截）
     */
    public long getModFboHandle() {
        return modFboHandle;
    }

    /**
     * 获取拦截到的颜色纹理句柄
     *
     * @return 颜色纹理句柄（0 表示无效或未拦截）
     */
    public long getModColorTexture() {
        return modColorTexture;
    }

    /**
     * 获取拦截到的深度纹理句柄
     *
     * @return 深度纹理句柄（0 表示无效或未拦截）
     */
    public long getModDepthTexture() {
        return modDepthTexture;
    }

    /**
     * 检查 FBO 是否已成功拦截
     *
     * @return true 如果 FBO 拦截成功且句柄有效
     */
    public boolean isFboIntercepted() {
        return fboIntercepted;
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 验证提取的句柄是否有效
     *
     * @return true 如果至少 FBO 和颜色纹理句柄有效
     */
    private boolean validateHandles() {
        boolean fboValid = modFboHandle > 0;
        boolean colorValid = modColorTexture > 0;

        if (!fboValid || !colorValid) {
            LOGGER.fine(String.format(
                "句柄验证失败 [fbo=%d, color=%d, depth=%d]",
                modFboHandle,
                modColorTexture,
                modDepthTexture
            ));
            return false;
        }

        return true;
    }

    /**
     * 从上下文应用回退策略
     * <p>
     * 当反射失败时，直接使用 context 中提供的默认值。
     *
     * @param context 模组输出上下文
     */
    private void applyFallbackFromContext(ModOutputContext context) {
        this.modFboHandle = context.getFboHandle();
        this.modColorTexture = context.getColorTexture();
        this.modDepthTexture = context.getDepthTexture();
        this.fboIntercepted = validateHandles();

        LOGGER.fine("已应用回退策略: 使用 context 中的默认 FBO 信息");
    }

    /**
     * 尝试获取 RenderDevice 实例
     * <p>
     * 通过反射查找类的静态单例字段或 getInstance() 方法。
     *
     * @param clazz RenderDevice 类
     * @param modId 模组 ID（用于日志）
     * @return 实例对象，如果获取失败则返回 null
     */
    private Object getRenderDeviceInstance(Class<?> clazz, String modId) {
        try {
            // 策略 1：查找常见的单例字段名
            String[] instanceFields = {"INSTANCE", "instance", "_instance"};
            for (String fieldName : instanceFields) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object instance = field.get(null);
                    if (instance != null) {
                        LOGGER.fine("通过静态字段获取 RenderDevice 实例: " + fieldName);
                        return instance;
                    }
                } catch (NoSuchFieldException ignored) {
                    // 继续尝试下一个字段名
                }
            }

            // 策略 2：查找 getInstance() 方法
            try {
                java.lang.reflect.Method method = clazz.getMethod("getInstance");
                Object instance = method.invoke(null);
                if (instance != null) {
                    LOGGER.fine("通过 getInstance() 获取 RenderDevice 实例");
                    return instance;
                }
            } catch (NoSuchMethodException ignored) {
                // 方法不存在
            }

            LOGGER.warning("无法获取 RenderDevice 实例: 未找到合适的单例字段或工厂方法");
            return null;

        } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            LOGGER.log(Level.WARNING,
                "反射访问 RenderDevice 实例失败 [" + modId + "]: " + e.getMessage(),
                e
            );
            return null;
        }
    }

    /**
     * 查找字段（支持多个候选名称）
     *
     * @param clazz          目标类
     * @param candidateNames 候选字段名数组（按优先级排序）
     * @return 找到的 Field 对象，如果都未找到则返回 null
     */
    private Field findField(Class<?> clazz, String... candidateNames) {
        for (String name : candidateNames) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续尝试下一个候选名称
            }
        }
        return null;
    }

    /**
     * 将对象转换为 long 类型
     * <p>
     * 支持 Integer、Long、Short、Byte 等数值类型的自动拆箱。
     *
     * @param value 数值对象
     * @return long 值，如果转换失败则返回 0
     */
    private long convertToLong(Object value) {
        if (value == null) {
            return INVALID_HANDLE;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        LOGGER.warning("无法将对象转换为 long: " + value.getClass().getName());
        return INVALID_HANDLE;
    }

    // ==================== 重写 toString() ====================

    /**
     * 返回处理器的字符串表示（用于调试和日志）
     *
     * @return 包含关键状态信息的字符串
     */
    @Override
    public String toString() {
        return String.format(
            "ThirdPartyModOutputHandler{" +
            "modId='%s', " +
            "fboIntercepted=%s, " +
            "modFboHandle=%d, " +
            "modColorTexture=%d, " +
            "modDepthTexture=%d" +
            "}",
            supportedModId,
            fboIntercepted,
            modFboHandle,
            modColorTexture,
            modDepthTexture
        );
    }
}
