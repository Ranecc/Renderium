// Renderium - GameRenderer Mixin (v10)
// 高性能版本 + Profiler + Debug HUD 集成
// Minecraft 26.2-snapshot-3 compatible
//
// v10 变更:
//   - 集成 RenderiumDebugHUD 渲染
//   - 异步管线初始化
//   - 优化热路径：仅在需要时执行操作

package com.renderium.fabric.mixin;

import com.renderium.core.RenderiumCore;
import com.renderium.fabric.hud.RenderiumDebugHUD;
import com.renderium.fabric.profiler.RenderiumProfiler;
import com.renderium.pipeline.AsyncChunkBuildPipeline;
import com.renderium.pipeline.AsyncRenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer Mixin (v10) - Profiler + Debug HUD 集成版
 *
 * <p>热路径操作（每帧）：</p>
 * <ul>
 *   <li>首次调用时一次性初始化（含异步管线启动）</li>
 *   <li>通知 RenderiumCore.onFrameBegin()</li>
 *   <li>渲染 RenderiumDebugHUD（如果启用）</li>
 *   <li>可选：RenderiumProfiler 计时</li>
 * </ul>
 *
 * <h3>Debug HUD 启用方式：</h3>
 * <ul>
 *   <li>F3 + R 键切换（运行时）</li>
 *   <li>配置文件: debugOverlayEnabled=true</li>
 *   <li>JVM: -Drenderium.debug=true</li>
 * </ul>
 *
 * @since 1.0.0
 * @version 10.0 (debug HUD integrated)
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-GameRenderer");

    @Unique
    private static boolean initialized = false;

    @Unique
    private static boolean startupLogged = false;

    /** 异步管线是否已启动 */
    @Unique
    private static volatile boolean pipelineStarted = false;

    // ==================== 渲染方法注入 ====================

    /**
     * 渲染方法头部注入
     * <p>
     * 执行顺序：
     * 1. Profiler 计时开始
     * 2. 首次初始化（异步管线等）
     * 3. onFrameBegin() 通知
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(DeltaTracker deltaTracker, boolean advanceGameTime,
                             CallbackInfo ci) {
        RenderiumProfiler.begin("GameRenderer.render");

        if (!initialized) {
            performInitialization();
            initialized = true;
        }

        try {
            // ====== L0 规范: 通过 LifecycleManager 委托帧开始通知 ======
            float deltaTime = deltaTracker.getGameTimeDeltaPartialTick(true);
            notifyLifecycleFrameBegin(deltaTime);
        } catch (Exception e) {
            // 核心未就绪时静默忽略
        }
    }

    /**
     * 渲染方法尾部注入
     * <p>
     * 执行顺序：
     * 1. 渲染 Debug HUD（如果可见）
     * 2. Profiler 计时结束
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderReturn(CallbackInfo ci) {
        // Debug HUD: 节流输出性能报告到日志
        try {
            RenderiumDebugHUD.render(null);  // 文本模式，不需要 graphics 参数
        } catch (Exception ignored) {
            // HUD 输出失败不应影响主流程
        }

        RenderiumProfiler.end("GameRenderer.render");
    }

    // ==================== 初始化逻辑 ====================

    /**
     * 执行一次性初始化
     * <p>
     * 包括：
     * <ul>
     *   <li>日志输出</li>
     *   <li>Profiler 检测与启用</li>
     *   <li>异步渲染管线启动</li>
     *   <li>Debug HUD 初始状态设置</li>
     * </ul>
     */
    private void performInitialization() {
        try {
            if (!startupLogged) {
                startupLogged = true;

                LOGGER.info("╔════════════════════════════════════════════╗");
                LOGGER.info("║      Renderium GameRenderer v10           ║");
                LOGGER.info("╠════════════════════════════════════════════╣");

                LOGGER.info("║ Core active={}, initialized={}{}",
                        queryCoreStatus("active"),
                        queryCoreStatus("initialized"),
                        queryCoreStatus("shortCircuited")
                                ? " | Shorted: " + queryCoreStatus("shortCircuitReason") : "");

                LOGGER.info("╚════════════════════════════════════════════╝");

                // Profiler: 多种启用方式检测
                detectAndEnableProfiler();

                // 异步管线: 延迟启动（等待核心完全初始化后再启动）
                schedulePipelineStartup();

                // Debug HUD: 检查配置和环境变量
                detectAndInitDebugHUD();
            }
        } catch (Exception e) {
            LOGGER.debug("[Renderium] GameRenderer init failed (non-fatal)", e);
        }
    }

    /**
     * 检测并启用 Profiler
     */
    private void detectAndEnableProfiler() {
        // 方式 A: JVM 属性
        if (Boolean.getBoolean("renderium.profiler")) {
            RenderiumProfiler.forceEnable(3000);
            return;
        }

        // 方式 B: 环境变量
        String envProfiler = System.getenv().get("RENDERIUM_PROFILER");
        if (envProfiler != null && !RenderiumProfiler.isEnabled()) {
            long interval = 3000L;
            try { interval = Long.parseLong(envProfiler); } catch (NumberFormatException ignored) {}
            RenderiumProfiler.forceEnable(interval);
            return;
        }

        // 方式 C: 文件开关
        String[] searchPaths = {".", "run", "fabric/run", "../run"};
        for (String path : searchPaths) {
            java.io.File f = new java.io.File(path, ".renderium-profiler");
            if (f.exists()) {
                RenderiumProfiler.forceEnable(3000);
                return;
            }
        }

        if (RenderiumProfiler.isEnabled()) {
            LOGGER.info("[Renderium] Profiler ENABLED (interval=active frames)");
        }
    }

    /**
     * 延迟启动异步渲染管线
     * <p>
     * 使用延迟启动策略，确保：
     * <ul>
     *   <li>RenderiumCore 已完全初始化</li>
     *   <li>Vulkan 设备已就绪</li>
     *   <li>不阻塞首次渲染</li>
     * </ul>
     */
    private void schedulePipelineStartup() {
        if (pipelineStarted) return;

        // 在后台线程中延迟启动（给核心留出初始化时间）
        Thread startupThread = new Thread(() -> {
            try {
                // 等待 2 秒让核心完成初始化
                Thread.sleep(2000);

                AsyncRenderPipeline pipeline = AsyncRenderPipeline.getInstance();
                pipeline.initialize();

                AsyncChunkBuildPipeline chunkPipeline = AsyncChunkBuildPipeline.getInstance();
                chunkPipeline.initialize();

                pipelineStarted = true;

                LOGGER.info("[Renderium] 异步渲染管线已启动");
                LOGGER.info("  ├─ AsyncRenderPipeline: OK");
                LOGGER.info("  └─ AsyncChunkBuildPipeline: OK");
                LOGGER.info("");
                LOGGER.info("[Renderium] Debug HUD: 按 F3+R 切换显示");
                LOGGER.info("[Renderium] 性能日志: 控制台搜索 [Renderium-Profiler]");

            } catch (Exception e) {
                LOGGER.debug("[Renderium] 异步管线启动失败 (非致命)", e);
            }
        }, "Renderium-PipelineStartup");

        startupThread.setDaemon(true);
        startupThread.setPriority(Thread.NORM_PRIORITY - 1);
        startupThread.start();
    }

    /**
     * 检测并初始化 Debug HUD
     */
    private void detectAndInitDebugHUD() {
        // 方式 1: JVM 参数
        if (Boolean.getBoolean("renderium.debug")) {
            RenderiumDebugHUD.setVisible(true);
            LOGGER.info("[Renderium] Debug HUD: 强制启用 (JVM 参数)");
            return;
        }

        // 方式 2: 环境变量
        String envDebug = System.getenv().get("RENDERIUM_DEBUG");
        if (envDebug != null && ("true".equalsIgnoreCase(envDebug) || "1".equals(envDebug))) {
            RenderiumDebugHUD.setVisible(true);
            LOGGER.info("[Renderium] Debug HUD: 强制启用 (环境变量)");
            return;
        }

        // 方式 3: 配置文件（默认关闭，用户手动开启）
        try {
            // ====== L0 规范: 通过反射查询配置（禁止直接调用 Core）======
            boolean debugEnabled = queryConfigDebugOverlay();
            if (debugEnabled) {
                RenderiumDebugHUD.setVisible(true);
                LOGGER.info("[Renderium] Debug HUD: 通过配置文件启用");
            }
        } catch (Exception ignored) {
            // 配置不可用时保持默认关闭
        }

        LOGGER.info("[Renderium] Debug HUD: 默认关闭 (按 F3+R 切换)");
    }

    // ==================== L0 辅助方法（反射委托，禁止直接 Core 调用）====================

    /**
     * 通过 LifecycleManager 通知帧开始
     *
     * @param deltaTime 帧间隔时间（秒）
     */
    private static void notifyLifecycleFrameBegin(float deltaTime) {
        try {
            Class<?> lifecycleClass = Class.forName(
                    "com.renderium.lifecycle.RenderiumLifecycleManager");
            java.lang.reflect.Method method = lifecycleClass.getMethod("notifyFrameBegin", float.class);
            method.invoke(null, deltaTime);
        } catch (ClassNotFoundException e) {
            // 静默跳过
        } catch (Exception e) {
            LOGGER.debug("[L0] notifyLifecycleFrameBegin 异常: " + e.getMessage());
        }
    }

    /**
     * 查询 Core 状态属性（通过反射，只读）
     *
     * @param property 属性名 (active/initialized/shortCircuited/shortCircuitReason)
     * @return 属性值字符串，查询失败返回 "N/A"
     */
    private static String queryCoreStatus(String property) {
        try {
            Class<?> coreClass = Class.forName("com.renderium.core.RenderiumCore");
            Object coreInstance = coreClass.getMethod("getInstance").invoke(null);
            return switch (property) {
                case "active" -> String.valueOf(coreClass.getMethod("isActive").invoke(coreInstance));
                case "initialized" -> String.valueOf(coreClass.getMethod("isInitialized").invoke(coreInstance));
                case "shortCircuited" -> String.valueOf(coreClass.getMethod("isShortCircuited").invoke(coreInstance));
                case "shortCircuitReason" -> {
                    Object reason = coreClass.getMethod("getShortCircuitReason").invoke(coreInstance);
                    return reason != null ? reason.toString() : "";
                }
                default -> "unknown";
            };
        } catch (Exception e) {
            return "N/A";
        }
    }

    /**
     * 查询配置中的 DebugOverlay 设置（通过反射）
     *
     * @return true 如果启用了调试覆盖层
     */
    private static boolean queryConfigDebugOverlay() {
        try {
            Class<?> coreClass = Class.forName("com.renderium.core.RenderiumCore");
            Object coreInstance = coreClass.getMethod("getInstance").invoke(null);
            Object config = coreClass.getMethod("getConfig").invoke(coreInstance);
            if (config == null) return false;
            return (Boolean) config.getClass().getMethod("isDebugOverlayEnabled").invoke(config);
        } catch (Exception e) {
            return false;
        }
    }
}
