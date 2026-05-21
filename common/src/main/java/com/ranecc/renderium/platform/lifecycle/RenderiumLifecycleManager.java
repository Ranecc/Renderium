// Renderium - 轻量 MC 抽象层
// 渲染生命周期管理器 - 覆盖 100% 渲染操作，零锁设计

package com.ranecc.renderium.platform.lifecycle;

import java.util.Arrays;
import java.util.logging.Logger;
import com.ranecc.renderium.platform.bridge.mc.MCRenderBridge;
import com.ranecc.renderium.platform.bridge.mc.FrameDataSnapshot;
import com.ranecc.renderium.platform.bridge.mc.GameRendererContext;
import com.ranecc.renderium.platform.bridge.mc.ProjectionContext;
import com.ranecc.renderium.platform.bridge.mc.FogContext;
import com.ranecc.renderium.platform.bridge.mc.CameraContext;
import com.ranecc.renderium.platform.bridge.mc.MatrixContext;
import com.ranecc.renderium.platform.bridge.mc.ChunkContext;
import com.ranecc.renderium.feature.pipeline.core.PipelineExecutor;

/**
 * 渲染生命周期管理器
 * <p>
 * 统一管理 Minecraft 渲染管线的 12 个生命周期钩子，
 * 覆盖 GameRenderer（6 个）和 LevelRenderer（6 个）的关键操作。
 * <p>
 * 设计原则：
 * <ul>
 *   <li><b>零锁设计</b>：volatile 快照模式替代 CopyOnWriteArrayList</li>
 *   <li><b>零分配</b>：上下文对象预分配，ThreadLocal 复用</li>
 *   <li><b>零异常</b>：热路径无 try-catch，异常仅在注册时校验</li>
 *   <li><b>性能预算</b>：每个 fire 方法 &lt; 0.5μs</li>
 * </ul>
 *
 * <h3>12 个钩子方法：</h3>
 * <pre>
 * GameRenderer 钩子（6 个）：
 *   ① fireBeforeGlobalUniform / fireAfterGlobalUniform    [op②]
 *   ② fireBeforeSetProjection / fireAfterSetProjection    [op⑥]
 *   ③ fireBeforeFogBuffer / fireAfterFogBuffer            [op⑦]
 *
 * LevelRenderer 钩子（6 个）：
 *   ④ fireBeforeCameraRepos / fireAfterCameraRepos        [op⑨]
 *   ⑤ fireBeforeModelViewSet / fireAfterModelViewSet      [op⑩]
 *   ⑥ fireBeforeChunkPrepare / fireAfterChunkPrepare      [op⑭]
 * </pre>
 *
 * <h3>Volatile 快照模式：</h3>
 * <pre>
 * // 写入（注册/注销，冷路径）
 * volatile Listener[] listeners = new Listener[0];
 * void addListener(Listener l) {
 *     listeners = Arrays.copyOf(listeners, listeners.length + 1);
 *     listeners[listeners.length - 1] = l;
 * }
 *
 * // 读取（fire，热路径）
 * void fireXxx(Context ctx) {
 *     Listener[] snap = listeners; // volatile 读取，&lt; 5ns
 *     for (int i = 0; i &lt; snap.length; i++) {
 *         snap[i].onXxx(ctx);      // 无锁迭代
 *     }
 * }
 * </pre>
 *
 * @see MCRenderBridge
 * @see FrameDataSnapshot
 * @since 1.0.0
 */
public final class RenderiumLifecycleManager {

    private static final Logger LOGGER = Logger.getLogger("Renderium|LifecycleManager");

    // ==================== 监听器接口 ====================

    /**
     * GameRenderer 生命周期监听器
     * <p>
     * 所有方法有默认空实现，监听器只需覆盖感兴趣的方法。
     */
    public interface GameRendererHooks {

        /** op②: updateGlobalUniforms 之前 */
        default void onBeforeGlobalUniform(GameRendererContext ctx) {}

        /** op②: updateGlobalUniforms 之后 */
        default void onAfterGlobalUniform(GameRendererContext ctx) {}

        /** op⑥: setProjectionMatrix 之前 */
        default void onBeforeSetProjection(ProjectionContext ctx) {}

        /** op⑥: setProjectionMatrix 之后 */
        default void onAfterSetProjection(ProjectionContext ctx) {}

        /** op⑦: updateFogBuffer 之前 */
        default void onBeforeFogBuffer(FogContext ctx) {}

        /** op⑦: updateFogBuffer 之后 */
        default void onAfterFogBuffer(FogContext ctx) {}
    }

    /**
     * LevelRenderer 生命周期监听器
     * <p>
     * 所有方法有默认空实现，监听器只需覆盖感兴趣的方法。
     */
    public interface LevelRendererHooks {

        /** op⑨: repositionCamera 之前 */
        default void onBeforeCameraRepos(CameraContext ctx) {}

        /** op⑨: repositionCamera 之后 */
        default void onAfterCameraRepos(CameraContext ctx) {}

        /** op⑩: setModelViewMatrix 之前 */
        default void onBeforeModelViewSet(MatrixContext ctx) {}

        /** op⑩: setModelViewMatrix 之后 */
        default void onAfterModelViewSet(MatrixContext ctx) {}

        /** op⑭: prepareChunkRenders 之前 */
        default void onBeforeChunkPrepare(ChunkContext ctx) {}

        /** op⑭: prepareChunkRenders 之后 */
        default void onAfterChunkPrepare(ChunkContext ctx) {}
    }

    // ==================== Volatile 快照监听器数组 ====================

    /** GameRenderer 监听器快照（volatile 发布，无锁读取） */
    private volatile GameRendererHooks[] gameRendererListeners = new GameRendererHooks[0];

    /** LevelRenderer 监听器快照（volatile 发布，无锁读取） */
    private volatile LevelRendererHooks[] levelRendererListeners = new LevelRendererHooks[0];

    // ==================== 预分配上下文对象（ThreadLocal 复用） ====================

    private final ThreadLocal<GameRendererContext> tlGameRendererCtx =
            ThreadLocal.withInitial(GameRendererContext::new);

    private final ThreadLocal<ProjectionContext> tlProjectionCtx =
            ThreadLocal.withInitial(ProjectionContext::new);

    private final ThreadLocal<FogContext> tlFogCtx =
            ThreadLocal.withInitial(FogContext::new);

    private final ThreadLocal<CameraContext> tlCameraCtx =
            ThreadLocal.withInitial(CameraContext::new);

    private final ThreadLocal<MatrixContext> tlMatrixCtx =
            ThreadLocal.withInitial(MatrixContext::new);

    private final ThreadLocal<ChunkContext> tlChunkCtx =
            ThreadLocal.withInitial(ChunkContext::new);

    // ==================== 单例 ====================

    private static volatile RenderiumLifecycleManager instance;

    public static RenderiumLifecycleManager getInstance() {
        RenderiumLifecycleManager result = instance;
        if (result == null) {
            synchronized (RenderiumLifecycleManager.class) {
                result = instance;
                if (result == null) {
                    result = new RenderiumLifecycleManager();
                    instance = result;
                }
            }
        }
        return result;
    }

    private RenderiumLifecycleManager() {}

    public void beginFrame() {
        PipelineExecutor executor = PipelineExecutor.getInstance();
        if (executor != null) {
            executor.initialize();
        }
    }

    public void endFrame() {
    }

    // ==================== 监听器注册 API（冷路径） ====================

    /**
     * 注册 GameRenderer 生命周期监听器
     *
     * @param listener 监听器实例（不能为 null）
     * @throws IllegalArgumentException 如果 listener 为 null
     */
    public void addGameRendererListener(GameRendererHooks listener) {
        if (listener == null) {
            throw new IllegalArgumentException("监听器不能为 null");
        }
        GameRendererHooks[] current = gameRendererListeners;
        GameRendererHooks[] updated = Arrays.copyOf(current, current.length + 1);
        updated[current.length] = listener;
        gameRendererListeners = updated;
    }

    /**
     * 注销 GameRenderer 生命周期监听器
     *
     * @param listener 要移除的监听器
     * @return true 如果成功移除
     */
    public boolean removeGameRendererListener(GameRendererHooks listener) {
        GameRendererHooks[] current = gameRendererListeners;
        int index = -1;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == listener) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;

        GameRendererHooks[] updated = new GameRendererHooks[current.length - 1];
        System.arraycopy(current, 0, updated, 0, index);
        System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
        gameRendererListeners = updated;
        return true;
    }

    /**
     * 注册 LevelRenderer 生命周期监听器
     *
     * @param listener 监听器实例（不能为 null）
     * @throws IllegalArgumentException 如果 listener 为 null
     */
    public void addLevelRendererListener(LevelRendererHooks listener) {
        if (listener == null) {
            throw new IllegalArgumentException("监听器不能为 null");
        }
        LevelRendererHooks[] current = levelRendererListeners;
        LevelRendererHooks[] updated = Arrays.copyOf(current, current.length + 1);
        updated[current.length] = listener;
        levelRendererListeners = updated;
    }

    /**
     * 注销 LevelRenderer 生命周期监听器
     *
     * @param listener 要移除的监听器
     * @return true 如果成功移除
     */
    public boolean removeLevelRendererListener(LevelRendererHooks listener) {
        LevelRendererHooks[] current = levelRendererListeners;
        int index = -1;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == listener) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;

        LevelRendererHooks[] updated = new LevelRendererHooks[current.length - 1];
        System.arraycopy(current, 0, updated, 0, index);
        System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
        levelRendererListeners = updated;
        return true;
    }

    // ==================== GameRenderer 钩子 fire 方法（热路径） ====================

    /**
     * 触发 op② updateGlobalUniforms 之前钩子
     * <p>
     * 由 Mixin 在 globalSettingsUniform.update() 之前调用。
     * 性能预算 &lt; 0.5μs。
     *
     * @param ctx 全局 Uniform 上下文
     */
    public void fireBeforeGlobalUniform(GameRendererContext ctx) {
        GameRendererHooks[] snap = gameRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onBeforeGlobalUniform(ctx);
        }
    }

    /**
     * 触发 op② updateGlobalUniforms 之后钩子
     * <p>
     * 由 Mixin 在 globalSettingsUniform.update() 之后调用。
     * 同时更新 MCRenderBridge.FrameData 中的窗口/时序数据。
     *
     * @param ctx 全局 Uniform 上下文
     */
    public void fireAfterGlobalUniform(GameRendererContext ctx) {
        GameRendererHooks[] snap = gameRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onAfterGlobalUniform(ctx);
        }

        // 同步到 FrameDataSnapshot
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        fd.setWindowSize(ctx.windowWidth, ctx.windowHeight);
        fd.setGameTick(ctx.gameTick);
        fd.setCameraPosition(ctx.cameraX, ctx.cameraY, ctx.cameraZ);
        fd.setFrameIndex(ctx.frameIndex);
    }

    /**
     * 触发 op⑥ setProjectionMatrix 之前钩子
     *
     * @param ctx 投影矩阵上下文
     */
    public void fireBeforeSetProjection(ProjectionContext ctx) {
        GameRendererHooks[] snap = gameRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onBeforeSetProjection(ctx);
        }
    }

    /**
     * 触发 op⑥ setProjectionMatrix 之后钩子
     * <p>
     * 同时更新 MCRenderBridge.FrameData 中的投影矩阵数据。
     *
     * @param ctx 投影矩阵上下文
     */
    public void fireAfterSetProjection(ProjectionContext ctx) {
        GameRendererHooks[] snap = gameRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onAfterSetProjection(ctx);
        }

        // 同步到 FrameDataSnapshot
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        fd.setProjectionMatrix(ctx.projectionMatrix);
        fd.setFov(ctx.fov);
        fd.setNearPlane(ctx.nearPlane);
        fd.setFarPlane(ctx.farPlane);
        fd.recomputeVPMatrix();
    }

    /**
     * 触发 op⑦ updateFogBuffer 之前钩子
     *
     * @param ctx 雾效上下文
     */
    public void fireBeforeFogBuffer(FogContext ctx) {
        GameRendererHooks[] snap = gameRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onBeforeFogBuffer(ctx);
        }
    }

    /**
     * 触发 op⑦ updateFogBuffer 之后钩子
     * <p>
     * 同时更新 MCRenderBridge.FrameData 中的雾效数据。
     *
     * @param ctx 雾效上下文
     */
    public void fireAfterFogBuffer(FogContext ctx) {
        GameRendererHooks[] snap = gameRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onAfterFogBuffer(ctx);
        }

        // 同步到 FrameDataSnapshot
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        fd.setFogData(ctx.color[0], ctx.color[1], ctx.color[2], ctx.color[3],
                ctx.start, ctx.end, ctx.density, ctx.type, ctx.enabled);
    }

    // ==================== LevelRenderer 钩子 fire 方法（热路径） ====================

    /**
     * 触发 op⑨ repositionCamera 之前钩子
     *
     * @param ctx 相机上下文
     */
    public void fireBeforeCameraRepos(CameraContext ctx) {
        LevelRendererHooks[] snap = levelRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onBeforeCameraRepos(ctx);
        }
    }

    /**
     * 触发 op⑨ repositionCamera 之后钩子
     * <p>
     * 同时更新 MCRenderBridge.FrameData 中的相机位置数据。
     *
     * @param ctx 相机上下文
     */
    public void fireAfterCameraRepos(CameraContext ctx) {
        LevelRendererHooks[] snap = levelRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onAfterCameraRepos(ctx);
        }

        // 同步到 FrameDataSnapshot
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        fd.setCameraPosition(ctx.x, ctx.y, ctx.z);
        fd.setCameraRotation(ctx.yaw, ctx.pitch);
    }

    /**
     * 触发 op⑩ setModelViewMatrix 之前钩子
     *
     * @param ctx 矩阵上下文
     */
    public void fireBeforeModelViewSet(MatrixContext ctx) {
        LevelRendererHooks[] snap = levelRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onBeforeModelViewSet(ctx);
        }
    }

    /**
     * 触发 op⑩ setModelViewMatrix 之后钩子
     * <p>
     * 同时更新 MCRenderBridge.FrameData 中的视图矩阵数据。
     *
     * @param ctx 矩阵上下文
     */
    public void fireAfterModelViewSet(MatrixContext ctx) {
        LevelRendererHooks[] snap = levelRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onAfterModelViewSet(ctx);
        }

        // 同步到 FrameDataSnapshot
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        fd.setViewMatrix(ctx.modelViewMatrix);
        fd.recomputeVPMatrix();
    }

    /**
     * 触发 op⑭ prepareChunkRenders 之前钩子
     *
     * @param ctx 区块上下文
     */
    public void fireBeforeChunkPrepare(ChunkContext ctx) {
        LevelRendererHooks[] snap = levelRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onBeforeChunkPrepare(ctx);
        }
    }

    /**
     * 触发 op⑭ prepareChunkRenders 之后钩子
     * <p>
     * 同时更新 MCRenderBridge.FrameData 中的区块可见性数据。
     *
     * @param ctx 区块上下文
     */
    public void fireAfterChunkPrepare(ChunkContext ctx) {
        LevelRendererHooks[] snap = levelRendererListeners;
        for (int i = 0; i < snap.length; i++) {
            snap[i].onAfterChunkPrepare(ctx);
        }

        // 同步到 FrameDataSnapshot
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        fd.setChunkVisibility(ctx.visibleSectionCount, ctx.totalSectionCount,
                ctx.opaqueDrawCallCount, ctx.translucentDrawCallCount, ctx.viewAreaChanged);
    }

    // ==================== 上下文获取 API（供 Mixin 使用） ====================

    /**
     * 获取当前线程的 GameRendererContext（预分配，ThreadLocal 复用）
     *
     * @return GameRendererContext 实例
     */
    public GameRendererContext acquireGameRendererContext() {
        GameRendererContext ctx = tlGameRendererCtx.get();
        ctx.reset();
        return ctx;
    }

    /**
     * 获取当前线程的 ProjectionContext（预分配，ThreadLocal 复用）
     *
     * @return ProjectionContext 实例
     */
    public ProjectionContext acquireProjectionContext() {
        ProjectionContext ctx = tlProjectionCtx.get();
        ctx.reset();
        return ctx;
    }

    /**
     * 获取当前线程的 FogContext（预分配，ThreadLocal 复用）
     *
     * @return FogContext 实例
     */
    public FogContext acquireFogContext() {
        FogContext ctx = tlFogCtx.get();
        ctx.reset();
        return ctx;
    }

    /**
     * 获取当前线程的 CameraContext（预分配，ThreadLocal 复用）
     *
     * @return CameraContext 实例
     */
    public CameraContext acquireCameraContext() {
        CameraContext ctx = tlCameraCtx.get();
        ctx.reset();
        return ctx;
    }

    /**
     * 获取当前线程的 MatrixContext（预分配，ThreadLocal 复用）
     *
     * @return MatrixContext 实例
     */
    public MatrixContext acquireMatrixContext() {
        MatrixContext ctx = tlMatrixCtx.get();
        ctx.reset();
        return ctx;
    }

    /**
     * 获取当前线程的 ChunkContext（预分配，ThreadLocal 复用）
     *
     * @return ChunkContext 实例
     */
    public ChunkContext acquireChunkContext() {
        ChunkContext ctx = tlChunkCtx.get();
        ctx.reset();
        return ctx;
    }

    // ==================== 诊断 API ====================

    /**
     * 获取 GameRenderer 监听器数量
     *
     * @return 监听器数量
     */
    public int getGameRendererListenerCount() {
        return gameRendererListeners.length;
    }

    /**
     * 获取 LevelRenderer 监听器数量
     *
     * @return 监听器数量
     */
    public int getLevelRendererListenerCount() {
        return levelRendererListeners.length;
    }

    /**
     * 清除所有监听器（用于测试或模块卸载）
     */
    public void clearAllListeners() {
        gameRendererListeners = new GameRendererHooks[0];
        levelRendererListeners = new LevelRendererHooks[0];
    }
}
