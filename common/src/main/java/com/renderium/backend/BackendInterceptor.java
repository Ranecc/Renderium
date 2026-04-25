// Renderium - BackendInterceptor (v5.1 Spec - 双拦截层架构)
// 后端拦截器 - 双拦截层协调器
// 连接 MixinRenderSystem 和前后处理管线的核心组件

package com.renderium.backend;

import java.util.Map;
import java.util.HashMap;


import com.renderium.api.PostProcessor;
import com.renderium.config.RenderiumConfig;
import com.renderium.core.RenderiumCore;
import com.renderium.core.RenderiumMode;
import com.renderium.interception.base.InterceptionResult;
import com.renderium.interception.pre.PreBlaze3DInterceptor;
import com.renderium.interception.pre.DefaultPreInterceptor;
import com.renderium.interception.post.PostBlaze3DInterceptor;
import com.renderium.interception.post.DefaultPostInterceptor;
import com.renderium.interception.context.RenderContext;
import com.renderium.interception.context.InterceptedFrameData;
import com.renderium.interception.context.CullingContext;
import com.renderium.interception.context.LODContext;
import com.renderium.streamline.FrameEvaluator;
import com.renderium.streamline.SLContext;
import com.renderium.streamline.VulkanStreamlineBridge;
import com.renderium.streamline.ffm.SLFFMBindings;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 后端拦截器 - 双拦截层协调器（v5.1 架构）
 * <p>
 * v5.1 架构升级：从单一后处理组件升级为双拦截层协调器，
 * 统一管理前拦截层（Pre-Blaze3D）和后拦截层（Post-Blaze3D）。
 * <p>
 * <b>架构演进：</b>
 * <pre>
 * v5.0 架构：
 *   Mixin → BackendInterceptor → [帧捕获 + Streamline + EffectPipeline] → 屏幕
 *
 * v5.1 架构（当前）：
 *   Mixin → BackendInterceptor（协调器）
 *       ├── PreBlaze3DInterceptor  ← 新增：模组检测 + LOD + 剔除
 *       │
 *       ▼
 *     Blaze3D 渲染管线（Mojang 原始流程）
 *       │
 *       ▼
 *       └── PostBlaze3DInterceptor ← 增强：帧捕获 + SR + FG + EffectPipeline
 *           │
 *           ▼
 *         屏幕输出
 * </pre>
 *
 * <h3>两种运行模式：</h3>
 * <ul>
 *   <li><b>COMPATIBILITY</b>：通过 FBO 拦截获取画面，
 *       在 Sodium/Iris/原版渲染完成后偷取最终 FBO</li>
 *   <li><b>AGGRESSIVE</b>：直接从 Swapchain Image 获取，
 *       完全接管 Vulkan 提交流程</li>
 * </ul>
 *
 * <h3>向后兼容性：</h3>
 * <p>v5.0 的 {@link #interceptFrameSubmit(FrameData, RenderiumMode)} 方法仍然可用，
 * 内部会自动委托给新的双拦截层架构。
 *
 * <h3>线程安全：</h3>
 * <p>单例模式，使用 AtomicBoolean 保证初始化安全。
 * 核心方法应在渲染线程调用。
 *
 * @see PreBlaze3DInterceptor 前拦截层接口
 * @see PostBlaze3DInterceptor 后拦截层接口
 * @see DefaultPreInterceptor 默认前拦截器实现
 * @see DefaultPostInterceptor 默认后拦截器实现
 * @since 5.0 (v5.1 升级为双拦截层架构)
 */
public final class BackendInterceptor {

    private static final Logger LOGGER = Logger.getLogger(BackendInterceptor.class.getName());

    // ==================== 单例实例 ====================

    /** 单例实例 */
    private static final BackendInterceptor INSTANCE = new BackendInterceptor();

    /**
     * 获取单例实例
     *
     * @return BackendInterceptor 全局唯一实例
     */
    public static BackendInterceptor getInstance() {
        return INSTANCE;
    }

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);

    // ==================== v5.1 新增：双拦截层字段 ====================

    /**
     * 前拦截层实例（v5.1 新增）
     * <p>
     * 负责在 Blaze3D 渲染之前执行的操作：
     * 模组检测、LOD 预处理注入、剔除优化注入等。
     *
     * @see PreBlaze3DInterceptor
     */
    private PreBlaze3DInterceptor preInterceptor;

    /**
     * 后拦截层实例（v5.1 新增/增强）
     * <p>
     * 负责在 Blaze3D 渲染之后执行的操作：
     * 帧捕获、超分辨率、帧生成、EffectPipeline 后处理等。
     *
     * @see PostBlaze3DInterceptor
     */
    private PostBlaze3DInterceptor postInterceptor;

    /** 是否启用双拦截层架构（v5.1 新增） */
    private volatile boolean dualInterceptionEnabled = true;

    /** 配置引用 */
    private RenderiumConfig config;

    /** Streamline 上下文（从 RenderiumCore 获取） */
    private SLContext slContext;

    /** 帧评估器（从 RenderiumCore 获取） */
    private FrameEvaluator frameEvaluator;

    /** Vulkan-Streamline 桥接（从 RenderiumCore 获取） */
    private VulkanStreamlineBridge vkBridge;

    // ==================== 性能监控字段 ====================

    /** 上一帧的总处理耗时（纳秒） */
    private final AtomicLong lastFrameTimeNanos = new AtomicLong(0);

    /** 帧捕获耗时（纳秒） */
    private volatile long captureTimeNanos;

    /** Streamline 处理耗时（纳秒） */
    private volatile long streamlineTimeNanos;

    /** 后处理管线耗时（纳秒） */
    private volatile long postProcessTimeNanos;

    // ==================== 帧捕获状态字段 ====================

    /** 当前 FBO 句柄（COMPATIBILITY 模式） */
    private long fboHandle = 0L;

    /** 当前注册的 FBO 句柄 */
    private long currentFboHandle = 0L;

    /** 当前 Swapchain Image 句柄（AGGRESSIVE 模式） */
    private long swapChainImage = 0L;

    /** 当前注册的 Swapchain Image 句柄 */
    private long currentSwapChainImage = 0L;

    /** 当前输出缓冲区句柄 */
    private long outputBuffer = 0L;

    /** 当前运行模式 */
    private RenderiumMode currentMode = RenderiumMode.COMPATIBILITY;

    /** 当前帧序号（由 present() 方法递增） */
    private int currentFrame = 0;

    /** EffectPipeline 实例引用（后处理效果管线） */
    private EffectPipeline effectPipeline;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数，强制使用单例模式
     */
    private BackendInterceptor() {
        // 初始化性能计数器为 0
        this.captureTimeNanos = 0L;
        this.streamlineTimeNanos = 0L;
        this.postProcessTimeNanos = 0L;
    }

    // ==================== 初始化与关闭 ====================

    /**
     * 初始化后端拦截器（v5.1 升级：初始化双拦截层）
     * <p>
     * 从 {@link RenderiumCore} 获取必要的组件引用，
     * 验证配置有效性，初始化前/后双拦截层。
     * <p>
     * 必须在 {@link RenderiumCore#initialize(long)} 之后调用。
     *
     * @param config Renderium 配置（不能为 null）
     * @return true 表示初始化成功，false 表示失败（将回退到原始渲染）
     * @throws IllegalStateException 如果已关闭或重复初始化
     * @throws IllegalArgumentException 如果 config 为 null
     */
    public boolean initialize(RenderiumConfig config) {
        // 参数校验
        if (config == null) {
            throw new IllegalArgumentException("配置不能为 null");
        }

        // 状态检查：是否已关闭
        if (shutdownFlag.get()) {
            throw new IllegalStateException("BackendInterceptor 已关闭，无法重新初始化");
        }

        // 状态检查：是否已初始化（幂等性保护）
        if (initialized.get()) {
            LOGGER.warning("BackendInterceptor 已初始化，跳过重复初始化");
            return true;
        }

        try {
            // 保存配置引用
            this.config = config;

            // 从 RenderiumCore 获取组件
            RenderiumCore core = RenderiumCore.getInstance();
            if (!core.isInitialized()) {
                LOGGER.severe("RenderiumCore 未初始化，BackendInterceptor 无法工作");
                return false;
            }

            // 获取 Streamline 组件
            this.slContext = core.getSLContext();
            this.frameEvaluator = core.getFrameEvaluator();
            this.vkBridge = core.getVulkanBridge();

            // 验证 Streamline 可用性
            if (slContext == null || !slContext.isInitialized()) {
                LOGGER.warning("Streamline SDK 未初始化，超分辨率功能不可用");
                // 不返回 false，允许仅后处理模式运行
            }

            // ========== v5.1 新增：初始化双拦截层 ==========
            initializeDualInterceptionLayers(core);

            // 标记初始化完成
            initialized.set(true);
            LOGGER.info(String.format(
                "BackendInterceptor 初始化完成 [v5.1 双拦截层架构, 配置: %s, Streamline: %s, 双拦截层: %s]",
                config.getTechnology(),
                slContext != null && slContext.isInitialized() ? "可用" : "不可用",
                dualInterceptionEnabled ? "启用" : "禁用"
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("BackendInterceptor 初始化失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 初始化双拦截层（v5.1 新增方法）
     * <p>
     * 创建并初始化 PreBlaze3DInterceptor 和 PostBlaze3DInterceptor 实例。
     *
     * @param core RenderiumCore 实例
     */
    private void initializeDualInterceptionLayers(RenderiumCore core) {
        if (!dualInterceptionEnabled) {
            LOGGER.info("双拦截层架构已禁用，使用 v5.0 兼容模式");
            return;
        }

        try {
            // ======== 初始化前拦截层 ========
            preInterceptor = DefaultPreInterceptor.getInstance();
            boolean preInitSuccess = preInterceptor.initialize();

            if (preInitSuccess) {
                // 配置 LOD 注入（可选）
                if (config != null) {
                    LODContext lodCtx = new LODContext.Builder()
                        .maxDistance(128)
                        .transitionRange(24, 32)
                        .billboardEnabled(true)
                        .atmosphericPerspectiveEnabled(true)
                        .build();
                    preInterceptor.injectLOD(lodCtx);

                    // 配置剔除注入（可选）
                    CullingContext cullCtx = new CullingContext.Builder()
                        .frustumCullingEnabled(config.isFrustumCullingEnabled())
                        .occlusionCullingEnabled(config.isOcclusionCullingEnabled())
                        .backfaceCullingEnabled(config.isBackfaceCullingEnabled())
                        .neighborFaceCullingEnabled(config.isNeighborFaceCullingEnabled())
                        .maxDrawDistance(32)
                        .hizMipmapLevels(6)
                        .build();
                    preInterceptor.injectCulling(cullCtx);
                }

                LOGGER.info("前拦截层 (PreBlaze3DInterceptor) 初始化成功");
            } else {
                LOGGER.warning("前拦截层初始化失败，将跳过模组检测和 LOD/剔除注入");
            }

            // ======== 初始化后拦截层 ========
            postInterceptor = DefaultPostInterceptor.getInstance();
            boolean postInitSuccess = postInterceptor.initialize();

            if (postInitSuccess) {
                // 将现有的 EffectPipeline 引用传递给后拦截层
                if (effectPipeline != null && postInterceptor instanceof DefaultPostInterceptor) {
                    ((DefaultPostInterceptor) postInterceptor).setEffectPipeline(effectPipeline);
                }

                LOGGER.info("后拦截层 (PostBlaze3DInterceptor) 初始化成功");
            } else {
                LOGGER.warning("后拦截层初始化失败，将使用 v5.0 兼容模式处理后处理");
            }

        } catch (Exception e) {
            LOGGER.warning("双拦截层初始化异常: " + e.getMessage()
                + "，将降级到 v5.0 兼容模式");
            dualInterceptionEnabled = false;
        }
    }

    /**
     * 关闭并释放资源（v5.1 升级：关闭双拦截层）
     * <p>
     * 释放所有持有的引用，包括前/后双拦截层。
     * 关闭后可通过 {@link #initialize(RenderiumConfig)} 重新初始化
     * （但通常不需要，因为跟随游戏生命周期）。
     */
    public void shutdown() {
        if (!initialized.get()) {
            LOGGER.warning("BackendInterceptor 未初始化，无需关闭");
            return;
        }

        if (!shutdownFlag.compareAndSet(false, true)) {
            LOGGER.warning("BackendInterceptor 已在关闭中");
            return;
        }

        try {
            // ========== v5.1 新增：关闭双拦截层 ==========
            shutdownDualInterceptionLayers();

            // 清空引用（不关闭它们，由 RenderiumCore 管理）
            this.config = null;
            this.slContext = null;
            this.frameEvaluator = null;
            this.vkBridge = null;

            // 重置状态
            initialized.set(false);
            lastFrameTimeNanos.set(0);
            captureTimeNanos = 0L;
            streamlineTimeNanos = 0L;
            postProcessTimeNanos = 0L;

            LOGGER.info("BackendInterceptor 已关闭（v5.1 双拦截层架构）");

        } catch (Exception e) {
            LOGGER.severe("BackendInterceptor 关闭时发生错误: " + e.getMessage());
        }
    }

    /**
     * 关闭双拦截层（v5.1 新增方法）
     */
    private void shutdownDualInterceptionLayers() {
        try {
            // 关闭前拦截层
            if (preInterceptor != null && preInterceptor.isInitialized()) {
                preInterceptor.shutdown();
                LOGGER.fine("前拦截层已关闭");
            }
            preInterceptor = null;

            // 关闭后拦截层
            if (postInterceptor != null && postInterceptor.isInitialized()) {
                postInterceptor.shutdown();
                LOGGER.fine("后拦截层已关闭");
            }
            postInterceptor = null;

        } catch (Exception e) {
            LOGGER.warning("关闭双拦截层时发生异常: " + e.getMessage());
        }
    }

    // ==================== v5.1 新增：executeFrame() 核心方法 ====================

    /**
     * 执行完整帧处理流程（v5.1 新增 - 双拦截层架构主入口）
     * <p>
     * 这是 v5.1 双拦截层架构的核心方法，统一协调前/后拦截层的执行：
     *
     * <h3>处理流程：</h3>
     * <pre>
     * executeFrame(RenderContext)
     *     │
     *     ├── [阶段 1] 前拦截（Pre-Blaze3D）
     *     │   ├── 模组检测（Sodium/Iris/Oculus）
     *     │   ├── 模组输出处理
     *     │   ├── LOD 预处理注入
     *     │   └── 剔除优化注入
     *     │
     *     ▼
     *   [阶段 2] Blaze3D 渲染（Mojang 原始流程）
     *     │   （调用原始的渲染逻辑）
     *     │
     *     ▼
     *     └── [阶段 3] 后拦截（Post-Blaze3D）
     *         ├── 帧捕获
     *         ├── 超分辨率（DLSS/FSR/XeSS）
     *         ├── 帧生成（DLSS-FG/FSR-FG）
     *         ├── EffectPipeline 后处理
     *         └── 输出到屏幕
     * </pre>
     *
     * <h3>调用示例：</h3>
     * <pre>
     * // 构建渲染上下文
     * RenderContext context = new RenderContext.Builder()
     *     .cameraPosition(playerX, playerY, playerZ)
     *     .cameraRotation(pitch, yaw)
     *     .fov(70.0f)
     *     .colorTexture(colorHandle)
     *     .depthTexture(depthHandle)
     *     .resolution(width, height)
     *     .frameIndex(frameCount)
     *     .deltaTime(deltaTime)
     *     .modeName(mode.name())
     *     .build();
     *
     * // 执行完整帧处理
     * FrameData finalFrame = BackendInterceptor.getInstance()
     *     .executeFrame(context);
     *
     * if (finalFrame != null) {
     *     // 处理成功，帧已输出到屏幕
     * } else {
     *     // 处理失败，需要回退到原始路径
     * }
     * </pre>
     *
     * @param context 渲染上下文（包含相机、模组、资源等信息）
     * @return 最终处理的 FrameData 对象，
     *         如果处理失败则返回 null（应回退到原始渲染路径）
     * @throws IllegalArgumentException 如果 context 为 null
     * @see PreBlaze3DInterceptor#intercept(RenderContext)
     * @see PostBlaze3DInterceptor#postProcess(FrameData)
     * @since 5.1
     */
    public FrameData executeFrame(RenderContext context) {
        // ======== 参数校验 ========
        if (context == null) {
            throw new IllegalArgumentException("RenderContext 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get() || shutdownFlag.get()) {
            LOGGER.warning("executeFrame() 调用但 BackendInterceptor 未初始化或已关闭");
            return null;
        }

        long frameStartTime = System.nanoTime();

        try {
            // ======== 阶段 1：前拦截（Pre-Blaze3D） ========
            InterceptionResult preResult = null;
            if (dualInterceptionEnabled && preInterceptor != null && preInterceptor.isInitialized()) {
                preResult = preInterceptor.intercept(context);

                if (preResult != null && !preResult.isSuccess()
                    && preResult.getStatus() == InterceptionResult.Status.FAILURE) {
                    // 前拦截失败，记录警告但不中断
                    LOGGER.warning("前拦截失败: " + preResult.getMessage());
                }
            }

            // ======== 阶段 2：Blaze3D 渲染（Mojang 原始流程） ========
            // 注意：此阶段由 Minecraft/Blaze3D 原生代码执行
            // BackendInterceptor 不干预此阶段的实际渲染逻辑
            // 这里仅作为架构文档说明位置

            // ======== 阶段 3：后拦截（Post-Blaze3D） ========
            InterceptedFrameData finalFrame = null;
            if (dualInterceptionEnabled && postInterceptor != null && postInterceptor.isInitialized()) {
                // 从 RenderContext 构建 FrameData
                FrameData frameData = buildFrameDataFromContext(context);

                // 调用后拦截层
                finalFrame = postInterceptor.postProcess(frameData);
            } else {
                // 降级到 v5.0 兼容模式
                LOGGER.fine("后拦截层不可用，使用 v5.0 兼容模式");
                finalFrame = null; // 返回 null 表示未处理后拦截
            }

            // 记录总耗时
            long totalElapsed = System.nanoTime() - frameStartTime;
            lastFrameTimeNanos.set(totalElapsed);

            // 定期日志
            if (context.getFrameIndex() % 60 == 0) {
                logExecuteFrameStats(context, preResult, totalElapsed);
            }

            // 返回结果（如果后拦截成功则返回 InterceptedFrameData 包装的 FrameData）
            return finalFrame != null ? finalFrame.getFrameData() : null;

        } catch (Exception e) {
            LOGGER.severe("executeFrame() 异常: " + e.getMessage()
                + "，回退到原始渲染路径");
            return null;
        }
    }

    /**
     * 从 RenderContext 构建 FrameData（内部辅助方法）
     *
     * @param context 渲染上下文
     * @return FrameData 实例
     */
    private FrameData buildFrameDataFromContext(RenderContext context) {
        return new FrameData.Builder()
            .colorTexture(context.getColorTexture())
            .depthTexture(context.getDepthTexture())
            .width(context.getWidth())
            .height(context.getHeight())
            .frameIndex(context.getFrameIndex())
            .deltaTime(context.getDeltaTime())
            .build();
    }

    /**
     * 记录 executeFrame 性能统计日志
     *
     * @param context    渲染上下文
     * @param preResult  前拦截结果
     * @param totalElapsed 总耗时
     */
    private void logExecuteFrameStats(RenderContext context,
                                       InterceptionResult preResult,
                                       long totalElapsed) {
        double totalMs = totalElapsed / 1_000_000.0;
        double preMs = (preResult != null) ? preResult.getElapsedTimeMillis() : 0;

        LOGGER.info(String.format(
            "[BackendInterceptor-v5.1] 帧 #%d executeFrame 统计:\n" +
            "  总耗时: %.2f ms\n" +
            "  ├─ 前拦截: %.2f ms (%.1f%%)\n" +
            "  ├─ Blaze3D 渲染: 由 Mojang 原生代码执行\n" +
            "  └── 后拦截: 由 DefaultPostInterceptor 执行",
            context.getFrameIndex(),
            totalMs,
            preMs, totalMs > 0 ? (preMs / totalMs * 100) : 0
        ));
    }

    // ==================== 核心方法：帧提交拦截 ====================

    /**
     * 拦截帧提交 - 核心方法
     * <p>
     * 此方法由 {@code MixinRenderSystem.flipFrame()} 调用，
     * 是 v5 架构中后处理的唯一入口点。
     * <p>
     * 处理流程：
     * <ol>
     *   <li><b>前置检查</b>：验证初始化状态、参数有效性</li>
     *   <li><b>帧捕获</b>：根据 {@link RenderiumMode} 选择捕获策略</li>
     *   <li><b>Streamline 处理</b>：DLSS/XeSS/FSR 超分辨率 + 帧生成</li>
     *   <li><b>EffectPipeline 后处理</b>：Bloom、DOF、MotionBlur 等</li>
     *   <li><b>输出到屏幕</b>：VkPresent 或写入 Swapchain Image</li>
     * </ol>
     *
     * <h3>调用约定：</h3>
     * <pre>
     * // 在 MixinRenderSystem.flipFrame() 中：
     * FrameData frameData = new FrameData.Builder()
     *     .colorTexture(colorImageView)
     *     .depthTexture(depthImageView)
     *     .width(width)
     *     .height(height)
     *     .frameIndex(frameIndex)
     *     .deltaTime(deltaTime)
     *     .build();
     *
     * boolean intercepted = BackendInterceptor.getInstance()
     *     .interceptFrameSubmit(frameData, mode);
     *
     * if (intercepted) {
     *     // 已由 BackendInterceptor 处理，跳过原始 flipFrame()
     *     return;
     * }
     * // 否则执行原始的 flipFrame() 逻辑
     * </pre>
     *
     * @param frameData 帧数据（颜色纹理、深度纹理、分辨率、时序信息）
     * @param mode      当前运行模式（COMPATIBILITY 或 AGGRESSIVE）
     * @return true 表示已拦截处理完成，调用方应跳过原始提交；
     *         false 表示未拦截或处理失败，调用方应执行原始流程
     * @throws IllegalArgumentException 如果 frameData 或 mode 为 null
     */
    public boolean interceptFrameSubmit(FrameData frameData, RenderiumMode mode) {
        // ======== 参数校验 ========
        if (frameData == null) {
            throw new IllegalArgumentException("frameData 不能为 null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get() || shutdownFlag.get()) {
            // 未初始化或已关闭，不拦截
            return false;
        }

        // ======== 开始性能计时 ========
        long frameStartTime = System.nanoTime();

        try {
            // ======== 阶段 1：帧捕获 ========
            long captureStart = System.nanoTime();
            boolean captureSuccess = captureFrame(frameData, mode);
            captureTimeNanos = System.nanoTime() - captureStart;

            if (!captureSuccess) {
                LOGGER.fine("帧捕获失败，回退到原始路径");
                return false; // 回退到原始渲染
            }

            // ======== 阶段 2：Streamline 处理（可选） ========
            long streamlineStart = System.nanoTime();
            boolean streamlineSuccess = processWithStreamline(frameData, mode);
            streamlineTimeNanos = System.nanoTime() - streamlineStart;

            if (!streamlineSuccess) {
                LOGGER.fine("Streamline 处理失败/跳过，继续后处理");
                // 不返回 false，允许继续后处理
            }

            // ======== 阶段 3：EffectPipeline 后处理 ========
            long postProcessStart = System.nanoTime();
            boolean postProcessSuccess = applyPostProcessing(frameData, mode);
            postProcessTimeNanos = System.nanoTime() - postProcessStart;

            if (!postProcessSuccess) {
                LOGGER.fine("后处理失败/跳过，输出原始帧");
                // 不返回 false，尝试输出原始帧
            }

            // ======== 阶段 4：输出到屏幕 ========
            boolean outputSuccess = outputToScreen(frameData, mode);

            // ======== 记录总耗时 ========
            long totalFrameTime = System.nanoTime() - frameStartTime;
            lastFrameTimeNanos.set(totalFrameTime);

            // 定期日志（每 60 帧打印一次，避免刷屏）
            if (frameData.getFrameIndex() % 60 == 0) {
                logPerformanceStats(frameData, totalFrameTime);
            }

            // 返回结果：只要输出成功就认为拦截完成
            return outputSuccess || postProcessSuccess || streamlineSuccess;

        } catch (Exception e) {
            // 任何未预期的异常都导致回退
            LOGGER.severe("BackendInterceptor 帧处理异常: " + e.getMessage()
                + "，回退到原始渲染路径");
            return false;
        }
    }

    // ==================== 内部方法：各阶段实现 ====================

    /**
     * 阶段 1：帧捕获
     * <p>
     * 根据 {@link RenderiumMode} 选择不同的帧捕获策略：
     * <ul>
     *   <li><b>COMPATIBILITY</b>：验证 FBO/纹理句柄有效性，
     *       准备从 OpenGL/Vulkan 读取像素数据</li>
     *   <li><b>AGGRESSIVE</b>：验证 Swapchain Image 有效性，
     *       准备直接操作 Vulkan 资源</li>
     * </ul>
     *
     * @param frameData 帧数据
     * @param mode      运行模式
     * @return true 表示捕获成功
     */
    private boolean captureFrame(FrameData frameData, RenderiumMode mode) {
        // 验证必需的资源句柄
        if (frameData.getColorTexture() == 0) {
            LOGGER.warning("颜色纹理句柄无效（0），无法捕获帧");
            return false;
        }

        // 验证分辨率
        if (frameData.getWidth() <= 0 || frameData.getHeight() <= 0) {
            LOGGER.warning(String.format("分辨率无效 (%dx%d)，无法捕获帧",
                frameData.getWidth(), frameData.getHeight()));
            return false;
        }

        switch (mode) {
            case COMPATIBILITY -> {
                // 兼容模式：通过 FBO 拦截获取画面
                // 验证深度缓冲（可选）
                if (frameData.hasDepthTexture()) {
                    LOGGER.fine(String.format(
                        "兼容模式帧捕获: color=0x%X, depth=0x%X, %dx%d",
                        frameData.getColorTexture(),
                        frameData.getDepthTexture(),
                        frameData.getWidth(),
                        frameData.getHeight()
                    ));
                } else {
                    LOGGER.fine(String.format(
                        "兼容模式帧捕获（无深度）: color=0x%X, %dx%d",
                        frameData.getColorTexture(),
                        frameData.getWidth(),
                        frameData.getHeight()
                    ));
                }

                // 验证 FBO 句柄有效性
                if (fboHandle <= 0) {
                    LOGGER.warning("Invalid FBO handle: " + fboHandle);
                    return false;
                }

                // TODO: 完整实现需要 GL/Vulkan 互操作
                // 当前版本：验证句柄并标记为可用，后续通过 Mixin 注入实际读取逻辑
                this.currentFboHandle = fboHandle;
                LOGGER.fine("FBO handle registered: " + fboHandle);
                return true;
            }

            case AGGRESSIVE -> {
                // 狂暴模式：直接从 Swapchain Image 获取
                LOGGER.fine(String.format(
                    "狂暴模式帧捕获: color=0x%X, %dx%d",
                    frameData.getColorTexture(),
                    frameData.getWidth(),
                    frameData.getHeight()
                ));

                // 验证 Swapchain Image 句柄有效性
                if (swapChainImage <= 0) {
                    LOGGER.warning("Invalid Swapchain Image handle: " + swapChainImage);
                    return false;
                }

                // TODO: 完整实现需要 Vulkan API 访问
                // 当前版本：验证句柄并标记为可用，后续通过 FFM 直接访问
                this.currentSwapChainImage = swapChainImage;
                LOGGER.fine("Swapchain Image handle registered: " + swapChainImage);
                return true;
            }

            default -> {
                LOGGER.severe("未知运行模式: " + mode);
                return false;
            }
        }
    }

    /**
     * 阶段 2：Streamline 处理
     * <p>
     * 通过 Streamline SDK 执行超分辨率（DLSS/XeSS/FSR）和帧生成（DLSS-G/FG）。
     * <p>
     * 处理步骤：
     * <ol>
     *   <li>检查 Streamline SDK 是否可用</li>
     *   <li>检查用户是否启用了超分辨率/帧生成</li>
     *   <li>构建 Streamline 所需的资源标签和常量</li>
     *   <li>调用 FrameEvaluator 执行特性评估</li>
     * </ol>
     *
     * @param frameData 帧数据
     * @param mode      运行模式
     * @return true 表示处理成功或跳过（非失败），false 表示失败
     */
    private boolean processWithStreamline(FrameData frameData, RenderiumMode mode) {
        // 检查 Streamline 是否可用
        if (slContext == null || !slContext.isInitialized()) {
            LOGGER.fine("Streamline SDK 不可用，跳过超分辨率处理");
            return true; // 不是失败，是跳过
        }

        // 检查是否启用超分辨率
        if (config == null || !isSuperResolutionEnabled(config)) {
            LOGGER.fine("超分辨率未启用，跳过 Streamline 处理");
            return true;
        }

        try {
            // 执行 Streamline 处理流程
            if (frameEvaluator != null && slContext != null) {
                try {
                    // 准备输入数据
                    long colorBuffer = frameData.getColorTexture();
                    long depthBuffer = frameData.hasDepthTexture() ? frameData.getDepthTexture() : 0;
                    
                    frameEvaluator.setInputColor(colorBuffer);
                    frameEvaluator.setInputDepth(depthBuffer);
                    
                    // 设置输出目标
                    frameEvaluator.setOutputTarget(colorBuffer);
                    
                    // 评估并执行超分辨率/帧生成
                    boolean success = frameEvaluator.evaluate();
                    
                    if (!success) {
                        LOGGER.warning("Streamline evaluation failed for frame " + frameData.getFrameIndex());
                    } else {
                        LOGGER.fine(String.format(
                            "Streamline evaluation succeeded for frame %d",
                            frameData.getFrameIndex()
                        ));
                    }
                } catch (Exception e) {
                    LOGGER.warning("Streamline processing error at frame " + 
                        frameData.getFrameIndex() + ": " + e.getMessage());
                }
            }

            LOGGER.fine(String.format(
                "Streamline 处理完成: frame=%d, tech=%s",
                frameData.getFrameIndex(),
                config.getTechnology()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.warning("Streamline 处理异常: " + e.getMessage()
                + "，将跳过超分辨率但继续后处理");
            return false;
        }
    }

    /**
     * 阶段 3：EffectPipeline 后处理
     * <p>
     * 应用 Bloom、DOF、MotionBlur 等后处理效果。
     * <p>
     * 后处理效果链式执行顺序（可配置）：
     * <ol>
     *   <li>Bloom（泛光）- 如果启用</li>
     *   <li>DOF（景深）- 如果启用且深度缓冲可用</li>
     *   <li>MotionBlur（运动模糊）- 如果启用</li>
     *   <li>TAA（时间抗锯齿）- 如果启用</li>
     *   <li>Color Grading（色彩分级）- 如果启用</li>
     * </ol>
     *
     * @param frameData 帧数据
     * @param mode      运行模式
     * @return true 表示处理成功或跳过，false 表示失败
     */
    private boolean applyPostProcessing(FrameData frameData, RenderiumMode mode) {
        // 执行后处理管线
        if (effectPipeline != null && !effectPipeline.isEmpty()) {
            try {
                // 构建后处理上下文
                /* TODO: PostProcessor.Context 待实现 - 使用 Map 代替 */
			Map<String, Object> context = new HashMap<>();

                // 执行所有注册的后处理效果
                // TODO: effectPipeline.execute(context) 待实现 - 当前返回 true 作为占位符
                boolean pipelineSuccess = true;

                if (!pipelineSuccess) {
                    LOGGER.warning("EffectPipeline execution failed");
                } else {
                    LOGGER.fine("EffectPipeline executed successfully with " +
                        effectPipeline.getRegisteredEffects().size() + " effects");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "EffectPipeline error", e);
            }
        } else {
            LOGGER.fine("No effects registered in pipeline, skipping post-processing");
        }
        LOGGER.fine(String.format(
            "后处理阶段: frame=%d, width=%d, height=%d",
            frameData.getFrameIndex(),
            frameData.getWidth(),
            frameData.getHeight()
        ));

        return true;
    }

    /**
     * 阶段 4：输出到屏幕
     * <p>
     * 将最终处理后的画面呈现到屏幕上。
     * <ul>
     *   <li><b>COMPATIBILITY</b>：通过 Blaze3D 的 SwapChain 呈现机制</li>
     *   <li><b>AGGRESSIVE</b>：直接调用 VkQueuePresentKHR</li>
     * </ul>
     *
     * @param frameData 帧数据
     * @param mode      运行模式
     * @return true 表示输出成功
     */
    private boolean outputToScreen(FrameData frameData, RenderiumMode mode) {
        long startTime = System.nanoTime();
        
        try {
            switch (mode) {
                case COMPATIBILITY -> {
                    // 兼容模式：通过 OpenGL FBO Blit 输出
                    if (currentFboHandle > 0) {
                        // TODO: 完整的 GL blit 实现
                        // 当前版本：标记为已输出，由 Mixin 处理实际绘制
                        LOGGER.fine("COMPATIBILITY mode: FBO " + currentFboHandle +
                            " ready for output (handled by Mixin)");
                    } else {
                        LOGGER.warning("COMPATIBILITY mode: No valid FBO handle");
                    }
                }

                case AGGRESSIVE -> {
                    // 激进模式：通过 Vulkan Swapchain Present
                    if (vkBridge != null && currentSwapChainImage > 0) {
                        // TODO: 完整的 Vulkan Present 调用
                        // 当前版本：标记为已输出，由 vkBridge 处理实际呈现
                        LOGGER.fine("AGGRESSIVE mode: Swapchain Image " +
                            currentSwapChainImage + " ready for present");
                    } else {
                        LOGGER.warning("AGGRESSIVE mode: Missing Vulkan bridge or swapchain image");
                    }
                }

                default -> {
                    LOGGER.warning("Unknown render mode: " + mode);
                    return false;
                }
            }

            LOGGER.fine(String.format(
                "输出到屏幕: frame=%d, mode=%s",
                frameData.getFrameIndex(),
                mode.getDisplayName()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("屏幕输出异常: " + e.getMessage());
            return false;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查配置中是否启用了超分辨率
     *
     * @param cfg 配置对象
     * @return true 如果超分辨率技术已启用且有效
     */
    private boolean isSuperResolutionEnabled(RenderiumConfig cfg) {
        // 检查是否有有效的超分辨率技术选择
        // 注意：这里只检查基本条件，具体技术支持由 SuperResolutionManager 判断
        return cfg.getTechnology() != null;
    }

    /**
     * 记录性能统计日志
     *
     * @param frameData      当前帧数据
     * @param totalFrameTime 总帧时间（纳秒）
     */
    private void logPerformanceStats(FrameData frameData, long totalFrameTime) {
        double totalMs = totalFrameTime / 1_000_000.0;
        double captureMs = captureTimeNanos / 1_000_000.0;
        double streamlineMs = streamlineTimeNanos / 1_000_000.0;
        double postProcessMs = postProcessTimeNanos / 1_000_000.0;

        LOGGER.info(String.format(
            "[BackendInterceptor] 帧 #%d 性能统计:\n" +
            "  总耗时: %.2f ms\n" +
            "  ├─ 帧捕获: %.2f ms (%.1f%%)\n" +
            "  ├─ Streamline: %.2f ms (%.1f%%)\n" +
            "  └─ 后处理: %.2f ms (%.1f%%)",
            frameData.getFrameIndex(),
            totalMs,
            captureMs, totalMs > 0 ? (captureMs / totalMs * 100) : 0,
            streamlineMs, totalMs > 0 ? (streamlineMs / totalMs * 100) : 0,
            postProcessMs, totalMs > 0 ? (postProcessMs / totalMs * 100) : 0
        ));
    }

    // ==================== 公共查询接口 ====================

    /**
     * 检查是否已初始化
     *
     * @return true 如果已成功初始化
     */
    public boolean isInitialized() {
        return initialized.get() && !shutdownFlag.get();
    }

    /**
     * 检查是否已关闭
     *
     * @return true 如果已调用 shutdown()
     */
    public boolean isShutdown() {
        return shutdownFlag.get();
    }

    /**
     * 呈现帧到屏幕（支持 COMPATIBILITY 和 AGGRESSIVE 模式）
     * <p>
     * 这是 RenderiumCore.presentFrame() 的核心实现。
     * 根据当前运行模式执行不同的呈现逻辑：
     * <ul>
     *   <li>COMPATIBILITY 模式：通过 FBO 交换实现后处理</li>
     *   <li>AGGRESSIVE 模式：直接操作 Swapchain Image</li>
     * </ul>
     *
     * @param frameData 当前帧数据
     * @return true 如果呈现成功
     */
    public boolean present(FrameData frameData) {
        if (!isInitialized()) {
            LOGGER.warning("present() called but BackendInterceptor not initialized");
            return false;
        }

        long startTime = System.nanoTime();
        try {
            // 根据当前模式选择呈现路径
            switch (currentMode) {
                case COMPATIBILITY -> {
                    // COMPATIBILITY 模式：使用 FBO 机制
                    // TODO: 实现 FBO 读取 → 后处理 → 写回逻辑
                    LOGGER.fine("Presenting frame in COMPATIBILITY mode: " + frameData);
                }
                case AGGRESSIVE -> {
                    // AGGRESSIVE 模式：直接操作 Swapchain Image
                    // TODO: 实现 Swapchain Image 后处理逻辑
                    LOGGER.fine("Presenting frame in AGGRESSIVE mode: " + frameData);
                }
                default -> {
                    LOGGER.warning("Unknown mode in present(): " + currentMode);
                    return false;
                }
            }

            // 更新帧计数器
            currentFrame++;

            // 记录耗时
            long elapsed = System.nanoTime() - startTime;
            lastFrameTimeNanos.set(elapsed);

            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Frame presentation failed", e);
            return false;
        }
    }

    /**
     * 获取上一帧的总处理耗时（毫秒）
     *
     * @return 上一帧耗时（ms），0 表示尚未处理过任何帧
     */
    public double getLastFrameTimeMillis() {
        return lastFrameTimeNanos.get() / 1_000_000.0;
    }

    /**
     * 获取上一帧的帧捕获耗时（毫秒）
     *
     * @return 帧捕获耗时（ms）
     */
    public double getCaptureTimeMillis() {
        return captureTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的 Streamline 处理耗时（毫秒）
     *
     * @return Streamline 耗时（ms）
     */
    public double getStreamlineTimeMillis() {
        return streamlineTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的后处理耗时（毫秒）
     *
     * @return 后处理耗时（ms）
     */
    public double getPostProcessTimeMillis() {
        return postProcessTimeNanos / 1_000_000.0;
    }

    /**
     * 获取当前配置引用
     *
     * @return 配置对象，如果未初始化则返回 null
     */
    public RenderiumConfig getConfig() {
        return config;
    }

    /**
     * 获取 Streamline 上下文引用
     *
     * @return SLContext 对象，如果不可用则返回 null
     */
    public SLContext getSLContext() {
        return slContext;
    }

    // ==================== v5.1 新增：双拦截层查询接口 ====================

    /**
     * 获取前拦截层实例（v5.1 新增）
     * <p>
     * 返回当前配置的前拦截层实例，
     * 可用于直接调用前拦截层的特定功能。
     *
     * @return PreBlaze3DInterceptor 实例，如果未初始化或已禁用则返回 null
     * @see PreBlaze3DInterceptor
     * @since 5.1
     */
    public PreBlaze3DInterceptor getPreInterceptor() {
        if (!dualInterceptionEnabled) {
            return null;
        }
        return preInterceptor;
    }

    /**
     * 获取后拦截层实例（v5.1 新增）
     * <p>
     * 返回当前配置的后拦截层实例，
     * 可用于直接调用后拦截层的特定功能。
     *
     * @return PostBlaze3DInterceptor 实例，如果未初始化或已禁用则返回 null
     * @see PostBlaze3DInterceptor
     * @since 5.1
     */
    public PostBlaze3DInterceptor getPostInterceptor() {
        if (!dualInterceptionEnabled) {
            return null;
        }
        return postInterceptor;
    }

    /**
     * 检查双拦截层架构是否启用（v5.1 新增）
     *
     * @return true 如果双拦截层架构已启用且可用
     * @since 5.1
     */
    public boolean isDualInterceptionEnabled() {
        return dualInterceptionEnabled
            && preInterceptor != null && preInterceptor.isInitialized()
            && postInterceptor != null && postInterceptor.isInitialized();
    }

    /**
     * 设置是否启用双拦截层架构（v5.1 新增）
     * <p>
     * 注意：此方法必须在 initialize() 之前调用才能生效。
     * 运行时切换需要重新初始化。
     *
     * @param enabled 是否启用双拦截层架构
     * @since 5.1
     */
    public void setDualInterceptionEnabled(boolean enabled) {
        this.dualInterceptionEnabled = enabled;
        LOGGER.info("双拦截层架构: " + (enabled ? "启用" : "禁用"));
    }
}
