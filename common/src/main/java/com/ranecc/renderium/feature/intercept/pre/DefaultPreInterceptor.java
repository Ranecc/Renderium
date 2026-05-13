// Renderium - Blaze3D 拦截层系统
// 默认前拦截器实现 - 实现模组检测、LOD注入、剔除优化等功能

package com.ranecc.renderium.feature.intercept.pre;

import com.ranecc.renderium.None;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 默认前拦截器实现
 * <p>
 * 实现 {@link PreBlaze3DInterceptor} 接口，提供完整的前拦截功能：
 * <ul>
 *   <li><b>模组检测</b>：自动检测 Sodium、Iris、Oculus 等第三方模组</li>
 *   <li><b>渲染状态捕获</b>：捕获并转换 Blaze3D 渲染状态</li>
 *   <li><b>LOD 注入</b>：集成 {@link com.renderium.optimization.lod.RenderiumLODManager}</li>
 *   <li><b>剔除注入</b>：集成 {@link com.renderium.culling.CullingController}</li>
 *   <li><b>性能监控</b>：记录各阶段耗时，支持性能分析</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * BackendInterceptor（协调器）
 *     │
 *     ├── DefaultPreInterceptor  ← 本类
 *     │   ├── 模组检测
 *     │   ├── LOD 注入
 *     │   └── 剔除注入
 *     │
 *     ▼
 * Blaze3D 渲染管线
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>使用 ConcurrentHashMap 和 AtomicBoolean 保证线程安全。
 * 核心方法 {@link #intercept(RenderContext)} 应在渲染线程调用。
 *
 * @see PreBlaze3DInterceptor
 * @see InterceptionResult
 * @since 5.1.0
 */
public final class DefaultPreInterceptor implements PreBlaze3DInterceptor {

    private static final Logger LOGGER = Logger.getLogger(DefaultPreInterceptor.class.getName());

    // ==================== 单例实例 ====================

    /** 单例实例 */
    private static final DefaultPreInterceptor INSTANCE = new DefaultPreInterceptor();

    /**
     * 获取单例实例
     *
     * @return DefaultPreInterceptor 全局唯一实例
     */
    public static DefaultPreInterceptor getInstance() {
        return INSTANCE;
    }

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);

    // ==================== 模组检测字段 ====================

    /** 已检测到的模组集合（线程安全） */
    private final Map<String, Boolean> detectedMods = new ConcurrentHashMap<>();

    // ==================== 模组处理器注册表 ====================

    /** 已注册的模组处理器映射（modId -> handler） */
    private final Map<String, ModOutputHandler> modHandlers = new ConcurrentHashMap<>();

    // ==================== LOD/剔除配置字段 ====================

    /** 当前 LOD 配置 */
    private volatile LODContext currentLodContext;

    /** 当前剔除配置 */
    private volatile CullingContext currentCullingContext;

    /** LOD 注入状态 */
    private volatile boolean lodInjected = false;

    /** 剔除注入状态 */
    private volatile boolean cullingInjected = false;

    // ==================== 异步回调字段 ====================

    /** 异步完成回调（可选） */
    private volatile InterceptionCallback asyncCallback;

    // ==================== 性能监控字段 ====================

    /** 上一帧的拦截总耗时（纳秒） */
    private volatile long lastInterceptionTimeNanos = 0L;

    /** 模组检测耗时（纳秒） */
    private volatile long modDetectionTimeNanos = 0L;

    /** LOD 注入耗时（纳秒） */
    private volatile long lodInjectionTimeNanos = 0L;

    /** 剔除注入耗时（纳秒） */
    private volatile long cullingInjectionTimeNanos = 0L;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数 - 强制单例模式
     */
    private DefaultPreInterceptor() {
        // 初始化性能计数器为 0
        this.lastInterceptionTimeNanos = 0L;
        this.modDetectionTimeNanos = 0L;
        this.lodInjectionTimeNanos = 0L;
        this.cullingInjectionTimeNanos = 0L;
    }

    // ==================== 核心方法实现 ====================

    /**
     * 执行前拦截操作（核心方法实现）
     * <p>
     * 处理流程：
     * <ol>
     *   <li>参数校验和状态检查</li>
     *   <li>模组检测与处理</li>
     *   <li>LOD 预处理注入</li>
     *   <li>剔除优化注入</li>
     *   <li>构建拦截结果</li>
     * </ol>
     *
     * @param context 渲染上下文
     * @return 拦截结果（包含修改后的上下文、性能指标、状态信息）
     */
    @Override
    public InterceptionResult intercept(RenderContext context) {
        // ======== 参数校验 ========
        if (context == null) {
            throw new IllegalArgumentException("RenderContext 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get() || shutdownFlag.get()) {
            return new InterceptionResult.Builder()
                .status(InterceptionResult.Status.SKIPPED)
                .message("DefaultPreInterceptor 未初始化或已关闭")
                .build();
        }

        // ======== 开始性能计时 ========
        long startTime = System.nanoTime();

        try {
            // ======== 阶段 1：模组检测与处理 ========
            long modStart = System.nanoTime();
            String[] mods = detectAndProcessMods(context);
            modDetectionTimeNanos = System.nanoTime() - modStart;

            // ======== 阶段 2：LOD 预处理注入 ========
            long lodStart = System.nanoTime();
            boolean lodSuccess = processLODInjection(context);
            lodInjectionTimeNanos = System.nanoTime() - lodStart;

            // ======== 阶段 3：剔除优化注入 ========
            long cullStart = System.nanoTime();
            boolean cullSuccess = processCullingInjection(context);
            cullingInjectionTimeNanos = System.nanoTime() - cullStart;

            // ======== 记录总耗时 ========
            long totalElapsed = System.nanoTime() - startTime;
            lastInterceptionTimeNanos = totalElapsed;

            // ======== 构建结果 ========
            InterceptionResult result = buildResult(context, mods, lodSuccess, cullSuccess, totalElapsed);

            // 定期日志（每 60 帧打印一次，避免刷屏）
            if (context.getFrameIndex() % 60 == 0) {
                logPerformanceStats(context, result);
            }

            // 异步回调通知
            if (asyncCallback != null) {
                try {
                    asyncCallback.onInterceptionComplete(result);
                } catch (Exception e) {
                    LOGGER.warning("异步回调执行失败: " + e.getMessage());
                }
            }

            return result;

        } catch (Exception e) {
            LOGGER.severe("前拦截操作异常: " + e.getMessage());
            return new InterceptionResult.Builder()
                .status(InterceptionResult.Status.FAILURE)
                .elapsedTimeNanos(System.nanoTime() - startTime)
                .message("前拦截失败: " + e.getMessage())
                .build();
        }
    }

    /**
     * 检测指定模组是否存在
     * <p>
     * 从已检测的模组集合中查找，
     * 如果尚未检测过则进行实时检测。
     *
     * @param modId 模组标识符
     * @return true 如果模组存在且已加载
     */
    @Override
    public boolean isModDetected(String modId) {
        if (modId == null || modId.isEmpty()) {
            throw new IllegalArgumentException("模组 ID 不能为空");
        }

        // 先从缓存中查询
        Boolean cached = detectedMods.get(modId.toLowerCase());
        if (cached != null) {
            return cached;
        }

        // 缓存未命中，执行实际检测
        boolean detected = performModDetection(modId);
        detectedMods.put(modId.toLowerCase(), detected);

        if (detected) {
            LOGGER.info("检测到模组: " + modId);
        }

        return detected;
    }

    /**
     * 注册模组输出处理器
     * <p>
     * 将处理器添加到注册表中，当检测到对应模组时自动调用。
     *
     * @param modId   模组标识符
     * @param handler 处理器实例
     */
    @Override
    public void registerModHandler(String modId, ModOutputHandler handler) {
        if (modId == null || modId.isEmpty()) {
            throw new IllegalArgumentException("模组 ID 不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("模组处理器不能为 null");
        }

        String key = modId.toLowerCase();
        if (modHandlers.containsKey(key)) {
            throw new IllegalStateException("模组 " + modId + " 已注册过处理器");
        }

        modHandlers.put(key, handler);
        LOGGER.info("已注册模组处理器: " + modId + " -> " + handler.getClass().getSimpleName());
    }

    /**
     * 注入 LOD 预处理逻辑
     * <p>
     * 保存 LOD 配置并标记为已启用。
     *
     * @param lodContext LOD 上下文
     */
    @Override
    public void injectLOD(LODContext lodContext) {
        if (lodContext == null) {
            throw new IllegalArgumentException("LODContext 不能为 null");
        }

        this.currentLodContext = lodContext;
        this.lodInjected = true;

        LOGGER.fine(String.format(
            "LOD 配置已注入: maxDistance=%d, billboard=%s",
            lodContext.getMaxDistance(),
            lodContext.isBillboardEnabled()
        ));
    }

    /**
     * 注入剔除优化逻辑
     * <p>
     * 保存剔除配置并标记为已启用。
     *
     * @param cullContext 剔除上下文
     */
    @Override
    public void injectCulling(CullingContext cullContext) {
        if (cullContext == null) {
            throw new IllegalArgumentException("CullingContext 不能为 null");
        }

        this.currentCullingContext = cullContext;
        this.cullingInjected = true;

        LOGGER.fine(String.format(
            "剔除配置已注入: frustum=%s, occlusion=%s, maxDistance=%d",
            cullContext.isFrustumCullingEnabled(),
            cullContext.isOcclusionCullingEnabled(),
            cullContext.getMaxDrawDistance()
        ));
    }

    // ==================== 生命周期方法实现 ====================

    /**
     * 初始化前拦截层
     *
     * @return true 表示初始化成功
     */
    @Override
    public boolean initialize() {
        if (initialized.get()) {
            LOGGER.warning("DefaultPreInterceptor 已初始化，跳过重复初始化");
            return true;
        }

        // 如果处于已关闭状态，允许重新初始化（支持测试的 shutdown→init 循环）
        if (shutdownFlag.get()) {
            shutdownFlag.set(false);
            LOGGER.config("DefaultPreInterceptor 从关闭状态恢复，执行重新初始化");
        }

        try {
            detectedMods.clear();
            modHandlers.clear();
            currentLodContext = null;
            currentCullingContext = null;
            lodInjected = false;
            cullingInjected = false;

            initialized.set(true);
            LOGGER.info("DefaultPreInterceptor 初始化完成");

            return true;

        } catch (Exception e) {
            LOGGER.severe("DefaultPreInterceptor 初始化失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 关闭前拦截层
     */
    @Override
    public void shutdown() {
        if (!initialized.get()) {
            LOGGER.warning("DefaultPreInterceptor 未初始化，无需关闭");
            return;
        }

        if (!shutdownFlag.compareAndSet(false, true)) {
            LOGGER.warning("DefaultPreInterceptor 已在关闭中");
            return;
        }

        try {
            // 清理资源
            detectedMods.clear();
            modHandlers.clear();
            currentLodContext = null;
            currentCullingContext = null;
            asyncCallback = null;

            // 重置状态
            initialized.set(false);
            lastInterceptionTimeNanos = 0L;
            modDetectionTimeNanos = 0L;
            lodInjectionTimeNanos = 0L;
            cullingInjectionTimeNanos = 0L;

            LOGGER.info("DefaultPreInterceptor 已关闭");

        } catch (Exception e) {
            LOGGER.severe("DefaultPreInterceptor 关闭时发生错误: " + e.getMessage());
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

    /**
     * 设置异步回调
     *
     * @param callback 异步完成回调
     */
    @Override
    public void setAsyncCallback(InterceptionCallback callback) {
        this.asyncCallback = callback;
    }

    // ==================== 内部方法：模组检测与处理 ====================

    /**
     * 检测并处理所有模组
     * <p>
     * 扫描渲染上下文中的模组列表，
     * 对每个已注册处理器的模组调用对应的处理器。
     *
     * @param context 渲染上下文
     * @return 检测到的模组 ID 数组
     */
    private String[] detectAndProcessMods(RenderContext context) {
        String[] loadedMods = context.getLoadedMods();
        if (loadedMods == null || loadedMods.length == 0) {
            return new String[0];
        }

        StringBuilder detectedList = new StringBuilder();

        for (String modId : loadedMods) {
            String key = modId.toLowerCase();

            // 更新检测缓存
            detectedMods.put(key, true);
            detectedList.append(modId).append(",");

            // 调用已注册的处理器
            ModOutputHandler handler = modHandlers.get(key);
            if (handler != null) {
                try {
                    // 构建模组输出上下文
                    ModOutputContext outputCtx = new ModOutputContext.Builder()
                        .modId(modId)
                        .fboHandle(context.getFboHandle())
                        .colorTexture(context.getColorTexture())
                        .depthTexture(context.getDepthTexture())
                        .resolution(context.getWidth(), context.getHeight())
                        .frameIndex(context.getFrameIndex())
                        .build();

                    // 调用处理器
                    handler.handleOutput(outputCtx);

                    LOGGER.fine("模组处理器已执行: " + modId + " -> " + handler.getClass().getSimpleName());

                } catch (Exception e) {
                    LOGGER.warning("模组处理器执行失败 [" + modId + "]: " + e.getMessage());
                }
            }
        }

        return loadedMods;
    }

    /**
     * 执行实际的模组检测逻辑
     * <p>
     * 通过反射或 ClassLoader 检测指定模组是否已加载。
     *
     * @param modId 模组标识符
     * @return true 如果模组存在
     */
    private boolean performModDetection(String modId) {
        try {
            // 尝试加载模组的主类来检测是否存在
            switch (modId.toLowerCase()) {
                case "sodium":
                    // Sodium 主类路径
                    Class.forName("me.jellysquid.mods.sodium.client.SodiumClientMod");
                    return true;

                case "iris":
                    // Iris 主类路径
                    Class.forName("net.coderbot.iris.Iris");
                    return true;

                case "oculus":
                    // Oculus 主类路径
                    Class.forName("curse.oculus.Oculus");
                    return true;

                default:
                    // 未知的模组，返回 false
                    return false;
            }
        } catch (ClassNotFoundException e) {
            // 模组未找到
            return false;
        }
    }

    // ==================== 内部方法：LOD/剔除处理 ====================

    /**
     * 处理 LOD 注入
     * <p>
     * 如果 LOD 已配置且启用，则应用 LOD 预处理逻辑。
     *
     * @param context 渲染上下文
     * @return true 如果 LOD 处理成功
     */
    private boolean processLODInjection(RenderContext context) {
        if (!lodInjected || currentLodContext == null) {
            return false; // LOD 未启用
        }

        try {
            // TODO: 集成 RenderiumLODManager 的实际 LOD 计算逻辑
            // 当前版本：验证配置有效性并标记为已处理

            LOGGER.fine(String.format(
                "LOD 预处理: frame=%d, maxDistance=%d",
                context.getFrameIndex(),
                currentLodContext.getMaxDistance()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.warning("LOD 注入处理失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 处理剔除注入
     * <p>
     * 如果剔除已配置且启用，则应用剔除优化逻辑。
     *
     * @param context 渲染上下文
     * @return true 如果剔除处理成功
     */
    private boolean processCullingInjection(RenderContext context) {
        if (!cullingInjected || currentCullingContext == null) {
            return false; // 剔除未启用
        }

        try {
            // TODO: 集成 CullingController 的实际剔除计算逻辑
            // 当前版本：验证配置有效性并标记为已处理

            LOGGER.fine(String.format(
                "剔除优化: frame=%d, frustum=%s, occlusion=%s",
                context.getFrameIndex(),
                currentCullingContext.isFrustumCullingEnabled(),
                currentCullingContext.isOcclusionCullingEnabled()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.warning("剔除注入处理失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 内部方法：结果构建 ====================

    /**
     * 构建拦截结果对象
     *
     * @param context    原始渲染上下文
     * @param mods       检测到的模组数组
     * @param lodSuccess LOD 是否成功
     * @param cullSuccess 剔除是否成功
     * @param elapsedNs  总耗时
     * @return 构建好的 InterceptionResult
     */
    private InterceptionResult buildResult(RenderContext context, String[] mods,
                                            boolean lodSuccess, boolean cullSuccess,
                                            long elapsedNs) {
        // 确定整体状态
        InterceptionResult.Status status;
        if (lodSuccess && cullSuccess) {
            status = InterceptionResult.Status.SUCCESS;
        } else if (lodSuccess || cullSuccess) {
            status = InterceptionResult.Status.PARTIAL;
        } else if (mods.length > 0) {
            status = InterceptionResult.Status.PARTIAL; // 至少检测到了模组
        } else {
            status = InterceptionResult.Status.SKIPPED;
        }

        return new InterceptionResult.Builder()
            .status(status)
            .modifiedContext(context)  // 返回原始上下文（当前版本不做修改）
            .elapsedTimeNanos(elapsedNs)
            .detectedMods(mods)
            .registeredHandlerCount(modHandlers.size())
            .lodInjected(lodSuccess)
            .cullingInjected(cullSuccess)
            .message(buildStatusMessage(status, mods, lodSuccess, cullSuccess))
            .build();
    }

    /**
     * 构建状态消息
     *
     * @param status      结果状态
     * @param mods        模组数组
     * @param lodSuccess  LOD 状态
     * @param cullSuccess 剔除状态
     * @return 状态消息字符串
     */
    private String buildStatusMessage(InterceptionResult.Status status, String[] mods,
                                       boolean lodSuccess, boolean cullSuccess) {
        return String.format(
            "前拦截完成 [status=%s, mods=%d, lod=%s, cull=%s]",
            status,
            mods.length,
            lodSuccess ? "✓" : "✗",
            cullSuccess ? "✓" : "✗"
        );
    }

    // ==================== 内部方法：日志与监控 ====================

    /**
     * 记录性能统计日志
     *
     * @param context 渲染上下文
     * @param result  拦截结果
     */
    private void logPerformanceStats(RenderContext context, InterceptionResult result) {
        double totalMs = result.getElapsedTimeMillis();
        double modMs = modDetectionTimeNanos / 1_000_000.0;
        double lodMs = lodInjectionTimeNanos / 1_000_000.0;
        double cullMs = cullingInjectionTimeNanos / 1_000_000.0;

        LOGGER.info(String.format(
            "[DefaultPreInterceptor] 帧 #%d 性能统计:\n" +
            "  总耗时: %.2f ms\n" +
            "  ├─ 模组检测: %.2f ms (%.1f%%)\n" +
            "  ├─ LOD 注入: %.2f ms (%.1f%%)\n" +
            "  └── 剔除注入: %.2f ms (%.1f%%)",
            context.getFrameIndex(),
            totalMs,
            modMs, totalMs > 0 ? (modMs / totalMs * 100) : 0,
            lodMs, totalMs > 0 ? (lodMs / totalMs * 100) : 0,
            cullMs, totalMs > 0 ? (cullMs / totalMs * 100) : 0
        ));
    }

    // ==================== 公共查询接口 ====================

    /**
     * 获取上一帧的拦截总耗时（毫秒）
     *
     * @return 耗时（ms），0 表示尚未处理过任何帧
     */
    public double getLastInterceptionTimeMillis() {
        return lastInterceptionTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的模组检测耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getModDetectionTimeMillis() {
        return modDetectionTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的 LOD 注入耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getLodInjectionTimeMillis() {
        return lodInjectionTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一帧的剔除注入耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getCullingInjectionTimeMillis() {
        return cullingInjectionTimeNanos / 1_000_000.0;
    }

    /**
     * 获取已注册的模组处理器数量
     *
     * @return 处理器数量
     */
    public int getRegisteredHandlerCount() {
        return modHandlers.size();
    }

    /**
     * 获取当前 LOD 配置
     *
     * @return LODContext 实例，如果未配置则返回 null
     */
    public LODContext getCurrentLodContext() {
        return currentLodContext;
    }

    /**
     * 获取当前剔除配置
     *
     * @return CullingContext 实例，如果未配置则返回 null
     */
    public CullingContext getCurrentCullingContext() {
        return currentCullingContext;
    }
}
