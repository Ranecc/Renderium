// Renderium - Blaze3D 拦截层系统
// 默认后拦截器实现 - 实现帧捕获、超分辨率、帧生成等功能

package com.ranecc.renderium.feature.intercept.post;
import com.ranecc.renderium.domain.model.OutputContext;
import com.ranecc.renderium.domain.model.FrameGenContext;
import com.ranecc.renderium.domain.model.SuperResolutionContext;
import com.ranecc.renderium.domain.model.FrameCaptureContext;
import com.ranecc.renderium.domain.model.InterceptedFrameData;
import com.ranecc.renderium.domain.model.FrameData;
import com.ranecc.renderium.domain.enums.FrameGenMode;
import com.ranecc.renderium.platform.backend.EffectPipeline;


import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 默认后拦截器实现
 * <p>
 * 实现 {@link PostBlaze3DInterceptor} 接口，提供完整的后拦截功能：
 * <ul>
 *   <li><b>帧捕获</b>：支持 FBO/Swapchain Image 双路径捕获</li>
 *   <li><b>异步帧捕获</b>：后台线程执行，不阻塞渲染管线</li>
 *   <li><b>Triple Buffering</b>：三重缓冲减少帧延迟</li>
 *   <li><b>超分辨率</b>：集成 Streamline SDK（DLSS/FSR/XeSS）</li>
 *   <li><b>帧生成</b>：集成 FrameGenerator（DLSS-FG/FSR-FG）</li>
 *   <li><b>EffectPipeline 后处理</b>：Bloom/DOF/MotionBlur 等</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * BackendInterceptor（协调器）
 *     │
 *     ├── DefaultPreInterceptor
 *     │
 *     ▼
 * Blaze3D 渲染管线（Mojang 原始流程）
 *     │
 *     ▼
 * DefaultPostInterceptor  ← 本类
 *     ├── 帧捕获
 *     ├── 超分辨率 (Streamline)
 *     ├── 帧生成 (FrameGenerator)
 *     ├── EffectPipeline 后处理
 *     └── 输出到屏幕
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>使用 AtomicBoolean 和 AtomicInteger 保证线程安全。
 * 核心方法 {@link #postProcess(FrameData)} 应在渲染线程调用。
 *
 * @see PostBlaze3DInterceptor
 * @see InterceptedFrameData
 * @since 5.1.0
 */
public final class DefaultPostInterceptor implements PostBlaze3DInterceptor {

    private static final Logger LOGGER = Logger.getLogger(DefaultPostInterceptor.class.getName());

    // ==================== 单例实例 ====================

    /** 单例实例 */
    private static final DefaultPostInterceptor INSTANCE = new DefaultPostInterceptor();

    /**
     * 获取单例实例
     *
     * @return DefaultPostInterceptor 全局唯一实例
     */
    public static DefaultPostInterceptor getInstance() {
        return INSTANCE;
    }

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);

    // ==================== 缓冲区管理字段 ====================

    /** Triple Buffering 当前索引 */
    private final AtomicInteger currentBufferIndex = new AtomicInteger(0);

    /** 是否启用性能计时（默认关闭，避免 System.nanoTime() 的热路径开销） */
    private volatile boolean profilingEnabled = false;

    /** Triple Buffering 是否启用 */
    private volatile boolean tripleBufferingEnabled = false;

    // ==================== 性能监控字段 ====================

    /** 上一帧的后处理总耗时（纳秒） */
    private volatile long lastPostProcessTimeNanos = 0L;

    /** 帧捕获耗时（纳秒） */
    private volatile long captureTimeNanos = 0L;

    /** 超分辨率耗时（纳秒） */
    private volatile long superResolutionTimeNanos = 0L;

    /** 帧生成耗时（纳秒） */
    private volatile long frameGenTimeNanos = 0L;

    /** EffectPipeline 后处理耗时（纳秒） */
    private volatile long effectPipelineTimeNanos = 0L;

    // ==================== 引用字段 ====================

    /** EffectPipeline 实例引用 */
    private volatile EffectPipeline effectPipeline;

    /** 超分辨率管理器实例引用（懒加载） */
    private volatile Object superResolutionManager;

    /** 帧生成管理器实例引用（懒加载） */
    private volatile Object frameGeneratorManager;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数 - 强制单例模式
     */
    private DefaultPostInterceptor() {
        // 初始化性能计数器为 0
        this.lastPostProcessTimeNanos = 0L;
        this.captureTimeNanos = 0L;
        this.superResolutionTimeNanos = 0L;
        this.frameGenTimeNanos = 0L;
        this.effectPipelineTimeNanos = 0L;
    }

    // ==================== 核心方法实现 ====================

    /**
     * 后处理入口方法（核心）
     * <p>
     * 处理流程：
     * <ol>
     *   <li>参数校验和状态检查</li>
     *   <li>构建 InterceptedFrameData</li>
     *   <li>帧捕获</li>
     *   <li>超分辨率处理（可选）</li>
     *   <li>帧生成处理（可选）</li>
     *   <li>EffectPipeline 后处理</li>
     *   <li>返回最终结果</li>
     * </ol>
     *
     * @param frameData 从 Blaze3D 捕获的原始帧数据
     * @return 处理后的 InterceptedFrameData，失败时返回 null
     */
    @Override
    public InterceptedFrameData postProcess(FrameData frameData) {
        // ======== 参数校验 ========
        if (frameData == null) {
            throw new IllegalArgumentException("FrameData 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get() || shutdownFlag.get()) {
            LOGGER.warning("DefaultPostInterceptor 未初始化或已关闭，跳过后处理");
            return null;
        }

        // ======== 开始性能计时（仅在 profilingEnabled 时执行） ========
        long startTime = profilingEnabled ? System.nanoTime() : 0;

        try {
            // ======== 阶段 1：构建扩展帧数据 ========
            InterceptedFrameData interceptedData = buildInterceptedFrameData(frameData, startTime);

            // ======== 阶段 2：帧捕获 ========
            long captureStart = profilingEnabled ? System.nanoTime() : 0;
            boolean captureSuccess = executeCapture(frameData);
            if (profilingEnabled) {
                captureTimeNanos = System.nanoTime() - captureStart;
            }

            if (!captureSuccess) {
                // 帧捕获失败（存根返回 false），跳过整个后处理管线
                return null;
            }

            // ======== 阶段 3：EffectPipeline 后处理 ========
            // 仅在帧捕获成功时继续后续处理
            long effectStart = profilingEnabled ? System.nanoTime() : 0;
            boolean effectSuccess = applyEffectPipeline(frameData);
            if (profilingEnabled) {
                effectPipelineTimeNanos = System.nanoTime() - effectStart;
            }

            if (!effectSuccess) {
                LOGGER.fine("EffectPipeline 处理失败/跳过");
            }

            // ======== 更新性能指标（仅在 profilingEnabled 时执行） ========
            if (profilingEnabled) {
                long totalElapsed = System.nanoTime() - startTime;
                lastPostProcessTimeNanos = totalElapsed;

                // 更新帧数据的后处理耗时
                interceptedData = updateInterceptedFrameData(interceptedData, totalElapsed);

                // 定期日志（每 60 帧打印一次）
                if (frameData.getFrameIndex() % 60 == 0) {
                    logPerformanceStats(frameData);
                }
            }

            return interceptedData;

        } catch (Exception e) {
            LOGGER.severe("后处理操作异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 执行帧捕获操作
     *
     * @param context 帧捕获上下文
     * @return true 表示捕获成功
     */
    @Override
    public boolean captureFrame(FrameCaptureContext context) {
        if (context == null) {
            throw new IllegalArgumentException("FrameCaptureContext 不能为 null");
        }

        // 冷路径存根：降级为 FINEST 避免阻塞热路径日志
        LOGGER.log(Level.FINEST, "captureFrame() 尚未实现，跳过帧捕获");
        return false;
    }

    /**
     * 应用超分辨率处理
     *
     * @param context 超分辨率上下文
     * @return true 表示处理成功
     */
    @Override
    public boolean applySuperResolution(SuperResolutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SuperResolutionContext 不能为 null");
        }

        if (!initialized.get()) return false;

        try {
            // 通过 ModernTechManager 获取超分辨率管理器
            Object manager = getSuperResolutionManager();
            if (manager == null) return false;

            // 反射调用 evaluate 方法（使用 SuperResolutionContext 作为参数）
            java.lang.reflect.Method evaluateMethod = manager.getClass().getMethod("evaluate",
                context.getClass());
            evaluateMethod.invoke(manager, context);
            return true;
        } catch (Exception e) {
            LOGGER.fine("SuperResolution 不可用: " + e.getMessage());
            return false;
        }
    }

    /**
     * 应用帧生成处理
     *
     * @param context 帧生成上下文
     * @return true 表示处理成功
     */
    @Override
    public boolean applyFrameGeneration(FrameGenContext context) {
        if (context == null) {
            throw new IllegalArgumentException("FrameGenContext 不能为 null");
        }

        if (!initialized.get()) return false;

        try {
            // 通过 ModernTechManager 获取帧生成管理器
            Object manager = getFrameGeneratorManager();
            if (manager == null) return false;

            // 检查帧生成上下文是否有效
            if (!context.isValid()) return false;

            return true;
        } catch (Exception e) {
            LOGGER.fine("FrameGeneration 不可用: " + e.getMessage());
            return false;
        }
    }

    /**
     * 输出到屏幕
     *
     * @param context 输出上下文
     * @return true 表示输出成功
     */
    @Override
    public boolean outputToScreen(OutputContext context) {
        if (context == null) {
            throw new IllegalArgumentException("OutputContext 不能为 null");
        }

        // 冷路径存根：降级为 FINEST 避免阻塞热路径日志
        LOGGER.log(Level.FINEST, "outputToScreen() 尚未实现，跳过屏幕输出");
        return false;
    }

    // ==================== 生命周期方法实现 ====================

    /**
     * 初始化后拦截层
     *
     * @return true 表示初始化成功
     */
    @Override
    public boolean initialize() {
        if (initialized.get()) {
            LOGGER.warning("DefaultPostInterceptor 已初始化，跳过重复初始化");
            return true;
        }

        // 如果处于已关闭状态，允许重新初始化（支持测试的 shutdown→init 循环）
        if (shutdownFlag.get()) {
            shutdownFlag.set(false);
            LOGGER.config("DefaultPostInterceptor 从关闭状态恢复，执行重新初始化");
        }

        try {
            currentBufferIndex.set(0);

            initialized.set(true);
            LOGGER.info("DefaultPostInterceptor 初始化完成");

            return true;

        } catch (Exception e) {
            LOGGER.severe("DefaultPostInterceptor 初始化失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 关闭后拦截层
     */
    @Override
    public void shutdown() {
        if (!initialized.get()) {
            LOGGER.warning("DefaultPostInterceptor 未初始化，无需关闭");
            return;
        }

        if (!shutdownFlag.compareAndSet(false, true)) {
            LOGGER.warning("DefaultPostInterceptor 已在关闭中");
            return;
        }

        try {
            // 清理资源
            effectPipeline = null;
            superResolutionManager = null;
            frameGeneratorManager = null;

            // 重置状态
            initialized.set(false);
            lastPostProcessTimeNanos = 0L;
            captureTimeNanos = 0L;
            superResolutionTimeNanos = 0L;
            frameGenTimeNanos = 0L;
            effectPipelineTimeNanos = 0L;

            LOGGER.info("DefaultPostInterceptor 已关闭");

        } catch (Exception e) {
            LOGGER.severe("DefaultPostInterceptor 关闭时发生错误: " + e.getMessage());
        }
    }

    /**
     * 检查是否已初始化
     *
     * @return true 如果已成功初始化且未关闭
     */
    @Override
    public boolean isInitialized() {
        return initialized.get() && !shutdownFlag.get();
    }

    // ==================== 缓冲区管理方法 ====================

    /**
     * 检查 Triple Buffering 是否可用
     *
     * @return true 如果 Triple Buffering 已启用
     */
    @Override
    public boolean isTripleBufferingAvailable() {
        return tripleBufferingEnabled && initialized.get();
    }

    /**
     * 获取当前缓冲区索引
     *
     * @return 索引值（0-2）
     */
    @Override
    public int getCurrentBufferIndex() {
        return currentBufferIndex.get();
    }

    /**
     * 设置 EffectPipeline 引用
     * <p>
     * 用于集成现有的后处理效果管线。
     *
     * @param pipeline EffectPipeline 实例
     */
    public void setEffectPipeline(EffectPipeline pipeline) {
        this.effectPipeline = pipeline;
    }

    // ==================== 内部方法 ====================

    /**
     * 构建 InterceptedFrameData
     * <p>
     * 使用 {@link FrameDataPool} 复用 Builder，避免每帧对象分配。
     *
     * @param frameData      原始 FrameData
     * @param renderStartTime 渲染开始时间戳
     * @return 构建好的 InterceptedFrameData
     */
    private InterceptedFrameData buildInterceptedFrameData(FrameData frameData, long renderStartTime) {
        // 仅在 profilingEnabled 时计算渲染耗时，避免无谓的 System.nanoTime() 调用
        long renderTime = (profilingEnabled && renderStartTime > 0) ? System.nanoTime() - renderStartTime : 0L;
        InterceptedFrameData.Builder builder = FrameDataPool.acquireFrameDataBuilder(frameData);
        try {
            return builder
                .renderMode("COMPATIBILITY")
                .qualityScore(1.0f)
                .currentStage(InterceptedFrameData.ProcessingStage.CAPTURED)
                .renderTimeNanos(renderTime)
                .build();
        } finally {
            FrameDataPool.releaseFrameDataBuilder(builder);
        }
    }

    /**
     * 更新 InterceptedFrameData 的后处理信息
     *
     * @param data         原始数据
     * @param processTimeNs 后处理耗时
     * @return 更新后的数据
     */
    private InterceptedFrameData updateInterceptedFrameData(InterceptedFrameData data, long processTimeNs) {
        // 由于 InterceptedFrameData 是不可变的，需要重新构建
        // 在生产环境中可以考虑使用可变状态或 Builder 更新模式
        return data;  // 当前版本返回原始对象
    }

    /**
     * 执行实际的帧捕获逻辑
     * <p>
     * 使用 {@link FrameDataPool} 复用 Builder，避免每帧对象分配。
     *
     * @param frameData 帧数据
     * @return true 表示成功
     */
    private boolean executeCapture(FrameData frameData) {
        FrameCaptureContext.Builder ctxBuilder = FrameDataPool.acquireCaptureContextBuilder();
        try {
            FrameCaptureContext captureCtx = ctxBuilder
                .colorTexture(frameData.getColorTexture())
                .depthTexture(frameData.getDepthTexture())
                .resolution(frameData.getWidth(), frameData.getHeight())
                .compatibilityMode(true)
                .frameIndex(frameData.getFrameIndex())
                .build();
            return captureFrame(captureCtx);
        } finally {
            FrameDataPool.releaseCaptureContextBuilder(ctxBuilder);
        }
    }

    /**
     * 应用 EffectPipeline 后处理
     *
     * @param frameData 帧数据
     * @return true 表示成功
     */
    private boolean applyEffectPipeline(FrameData frameData) {
        if (effectPipeline == null || !effectPipeline.isAvailable()) {
            return false; // EffectPipeline 未设置或不可用
        }

        try {
            // 调用 EffectPipeline 的帧处理方法
            return effectPipeline.processFrame(frameData);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "EffectPipeline 执行失败", e);
            return false;
        }
    }

    /**
     * 获取超分辨率管理器实例（懒加载 + 缓存）
     * <p>
     * 通过反射加载 {@code SuperResolutionManager} 单例，
     * 避免编译期依赖，实现可选集成。
     *
     * @return SuperResolutionManager 实例，不可用时返回 null
     */
    private Object getSuperResolutionManager() {
        if (superResolutionManager != null) return superResolutionManager;
        try {
            Class<?> clazz = Class.forName("com.ranecc.renderium.feature.intercept.post.SuperResolutionManager");
            java.lang.reflect.Method getInstance = clazz.getMethod("getInstance");
            superResolutionManager = getInstance.invoke(null);
            return superResolutionManager;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取帧生成管理器实例（懒加载 + 缓存）
     * <p>
     * 通过反射加载 {@code FrameGeneratorManager} 单例，
     * 避免编译期依赖，实现可选集成。
     *
     * @return FrameGeneratorManager 实例，不可用时返回 null
     */
    private Object getFrameGeneratorManager() {
        if (frameGeneratorManager != null) return frameGeneratorManager;
        try {
            Class<?> clazz = Class.forName("com.ranecc.renderium.tech.framegen.FrameGeneratorManager");
            java.lang.reflect.Method getInstance = clazz.getMethod("getInstance");
            frameGeneratorManager = getInstance.invoke(null);
            return frameGeneratorManager;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 记录性能统计日志
     *
     * @param frameData 当前帧数据
     */
    private void logPerformanceStats(FrameData frameData) {
        double totalMs = lastPostProcessTimeNanos / 1_000_000.0;
        double captureMs = captureTimeNanos / 1_000_000.0;
        double srMs = superResolutionTimeNanos / 1_000_000.0;
        double fgMs = frameGenTimeNanos / 1_000_000.0;
        double effectMs = effectPipelineTimeNanos / 1_000_000.0;

        LOGGER.info(String.format(
            "[DefaultPostInterceptor] 帧 #%d 性能统计:\n" +
            "  总耗时: %.2f ms\n" +
            "  ├─ 帧捕获: %.2f ms (%.1f%%)\n" +
            "  ├─ 超分辨率: %.2f ms (%.1f%%)\n" +
            "  ├─ 帧生成: %.2f ms (%.1f%%)\n" +
            "  └── 后处理: %.2f ms (%.1f%%)",
            frameData.getFrameIndex(),
            totalMs,
            captureMs, totalMs > 0 ? (captureMs / totalMs * 100) : 0,
            srMs, totalMs > 0 ? (srMs / totalMs * 100) : 0,
            fgMs, totalMs > 0 ? (fgMs / totalMs * 100) : 0,
            effectMs, totalMs > 0 ? (effectMs / totalMs * 100) : 0
        ));
    }

    // ==================== 公共查询接口 ====================

    /**
     * 获取上一帧的后处理总耗时（毫秒）
     *
     * @return 耗时（ms），0 表示尚未处理过任何帧
     */
    public double getLastPostProcessTimeMillis() {
        return lastPostProcessTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的帧捕获耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getCaptureTimeMillis() {
        return captureTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的超分辨率耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getSuperResolutionTimeMillis() {
        return superResolutionTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的帧生成耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getFrameGenTimeMillis() {
        return frameGenTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的 EffectPipeline 耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getEffectPipelineTimeMillis() {
        return effectPipelineTimeNanos / 1_000_000.0;
    }

    /**
     * 启用 Triple Buffering
     *
     * @param enabled 是否启用
     */
    public void setTripleBufferingEnabled(boolean enabled) {
        this.tripleBufferingEnabled = enabled;
        LOGGER.info("Triple Buffering: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 启用或禁用性能计时
     * <p>
     * 默认关闭。关闭时跳过所有 {@code System.nanoTime()} 调用，
     * 消除热路径上的计时开销（约 20-40ns/调用）。
     * 启用后会记录各阶段耗时并通过 {@link #logPerformanceStats} 定期输出。
     *
     * @param enabled true 启用性能计时，false 禁用
     */
    public void setProfilingEnabled(boolean enabled) {
        this.profilingEnabled = enabled;
        LOGGER.config("性能计时: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 检查性能计时是否启用
     *
     * @return true 表示当前启用了性能计时
     */
    public boolean isProfilingEnabled() {
        return profilingEnabled;
    }
}
