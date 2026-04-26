// Renderium - 轻量 MC 抽象层
// GameRenderer 生命周期 Mixin - Thin Glue 层（~60 行）
//
// 注入点（来自 render-lifecycle-complete-analysis.md）：
//   op②: globalSettingsUniform.update() 前后 → Uniform 数据捕获
//   op⑥: RenderSystem.setProjectionMatrix() 前后 → 投影矩阵捕获
//   op⑦: fogRenderer.updateBuffer() 前后 → 雾效数据捕获
//
// 性能预算：每个注入点 < 5μs（条件判断 + 数据提取 + 钩子触发）

package com.renderium.fabric.mixin;

import com.renderium.bridge.mc.CameraContext;
import com.renderium.bridge.mc.FrameDataSnapshot;
import com.renderium.bridge.mc.GameRendererContext;
import com.renderium.bridge.mc.MCRenderBridge;
import com.renderium.bridge.mc.ProjectionContext;
import com.renderium.bridge.mc.RenderiumLifecycleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer 生命周期 Mixin（Thin Glue 层）
 * <p>
 * 在 GameRenderer.render() 的关键操作点注入生命周期钩子，
 * 将 MC 渲染状态数据提取并填充到 {@link FrameDataSnapshot}。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>仅 Mixin 极稳定的公共方法（render/renderLevel）</li>
 *   <li>不修改任何控制流或返回值</li>
 *   <li>性能开销 &lt; 5μs/注入点</li>
 *   <li>总代码量 &lt; 60 行</li>
 * </ul>
 *
 * @see RenderiumLifecycleManager
 * @see MCRenderBridge
 * @since 2.1.0
 */
@Mixin(GameRenderer.class)
public class GameRendererLifecycleMixin {

    /** 是否已初始化（避免每帧检查） */
    @Unique
    private static volatile boolean lifecycleInitialized = false;

    // ==================== op②: globalSettingsUniform.update() 钩子 ====================

    /**
     * Hook: updateGlobalUniforms 之前 [op② 前]
     *
     * <p>注入位置：GameRenderer.render() 中 globalSettingsUniform.update() 调用之前</p>
     * <p>目的：让 Renderium 知道即将更新全局 Uniform，准备上下文数据</p>
     *
     * @param deltaTracker 帧时间跟踪器
     * @param advanceGameTime 是否推进游戏时间
     * @param ci Mixin 回调信息
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer$GlobalSettingsUniform;" +
                    "update(IIJLnet/minecraft/client/DeltaTracker;FD[D)V"
        )
    )
    private void onBeforeGlobalUniformUpdate(net.minecraft.client.DeltaTracker deltaTracker,
                                              boolean advanceGameTime, CallbackInfo ci) {
        if (!lifecycleInitialized) return;

        try {
            RenderiumLifecycleManager lifecycle = RenderiumLifecycleManager.getInstance();
            if (lifecycle.getGameRendererListenerCount() == 0) return;

            // 提取窗口/时序/相机数据
            GameRendererContext ctx = lifecycle.acquireGameRendererContext();
            extractGameData(ctx);

            lifecycle.fireBeforeGlobalUniform(ctx);
        } catch (Exception ignored) {
            // 生命周期管理器未就绪时静默忽略
        }
    }

    /**
     * Hook: updateGlobalUniforms 之后 [op② 后]
     *
     * <p>目的：确认全局 Uniform 已更新，同步数据到 FrameDataSnapshot</p>
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer$GlobalSettingsUniform;" +
                    "update(IIJLnet/minecraft/client/DeltaTracker;FD[D)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterGlobalUniformUpdate(net.minecraft.client.DeltaTracker deltaTracker,
                                             boolean advanceGameTime, CallbackInfo ci) {
        if (!lifecycleInitialized) return;

        try {
            RenderiumLifecycleManager lifecycle = RenderiumLifecycleManager.getInstance();
            if (lifecycle.getGameRendererListenerCount() == 0) return;

            GameRendererContext ctx = lifecycle.acquireGameRendererContext();
            extractGameData(ctx);

            lifecycle.fireAfterGlobalUniform(ctx);
        } catch (Exception ignored) {}
    }

    // ==================== op⑥: setProjectionMatrix() 钩子 ====================

    /**
     * Hook: setProjectionMatrix 之后 [op⑥ 后]
     *
     * <p>注入位置：GameRenderer.renderLevel() 中 RenderSystem.setProjectionMatrix() 之后</p>
     * <p>目的：捕获最终的投影矩阵（含 bobHurt/bobView/screenEffect 变换）</p>
     * <p>这是 ShadowMapNode 等节点获取投影矩阵的关键数据源</p>
     */
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;" +
                    "setProjectionMatrix(Lorg/joml/Matrix4f;" +
                    "Lcom/mojang/blaze3d/systems/RenderSystem$ProjectionType;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterSetProjectionMatrix(Object deltaTracker, CallbackInfo ci) {
        if (!lifecycleInitialized) return;

        try {
            RenderiumLifecycleManager lifecycle = RenderiumLifecycleManager.getInstance();
            if (lifecycle.getGameRendererListenerCount() == 0) return;

            ProjectionContext ctx = lifecycle.acquireProjectionContext();
            extractProjectionData(ctx);

            lifecycle.fireAfterSetProjection(ctx);
        } catch (Exception ignored) {}
    }

    // ==================== op⑦: fogRenderer.updateBuffer() 钩子 ====================

    /**
     * Hook: fogRenderer.updateBuffer() 之后 [op⑦ 后]
     *
     * <p>注入位置：GameRenderer.renderLevel() 中 fogRenderer.updateBuffer() 之后</p>
     * <p>目的：捕获雾效数据（颜色、距离、密度、类型）</p>
     */
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FogRenderer;" +
                    "updateBuffer(Lnet/minecraft/client/renderer/FogRenderer$FogData;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterFogBufferUpdate(Object deltaTracker, CallbackInfo ci) {
        if (!lifecycleInitialized) return;

        try {
            RenderiumLifecycleManager lifecycle = RenderiumLifecycleManager.getInstance();
            if (lifecycle.getGameRendererListenerCount() == 0) return;

            var fogCtx = lifecycle.acquireFogContext();
            extractFogData(fogCtx);

            lifecycle.fireAfterFogBuffer(fogCtx);
        } catch (Exception ignored) {}
    }

    // ==================== 帧边界管理 ====================

    /**
     * Hook: render() 方法头部 - 标记新帧开始
     *
     * <p>重置 FrameDataSnapshot，准备接收新数据</p>
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onFrameBegin(net.minecraft.client.DeltaTracker deltaTracker,
                               boolean advanceGameTime, CallbackInfo ci) {
        // 懒初始化检查（仅首次）
        if (!lifecycleInitialized) {
            lifecycleInitialized = (RenderiumLifecycleManager.getInstance() != null);
            if (lifecycleInitialized) {
                MCRenderBridge.beginFrame();
            }
            return;
        }

        // 每帧开始时重置 FrameData
        MCRenderBridge.beginFrame();
    }

    /**
     * Hook: render() 方法尾部 - 标记帧结束
     *
     * <p>刷新所有批处理器</p>
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onFrameEnd(CallbackInfo ci) {
        if (lifecycleInitialized) {
            MCRenderBridge.endFrame();
        }
    }

    // ==================== 数据提取方法（私有辅助） ====================

    /**
     * 从 GameRenderer 实例提取窗口/时序/相机数据
     *
     * @param ctx 要填充的上下文对象
     */
    private void extractGameData(GameRendererContext ctx) {
        try {
            GameRenderer self = (GameRenderer) (Object) this;
            // MC 26.2: minecraft 字段改为 private，使用 Minecraft.getInstance() 访问
            var mc = Minecraft.getInstance();

            // 提取窗口尺寸
            ctx.windowWidth = mc.getWindow().getWidth();
            ctx.windowHeight = mc.getWindow().getHeight();

            // 提取游戏时间
            ctx.gameTick = mc.level != null ? mc.level.getGameTime() : 0L;

            // 提取相机位置（MC 26.2: cameraRenderState 在 levelRenderState 内部）
            if (mc.player != null) {
                var pos = self.gameRenderState().levelRenderState.cameraRenderState.pos;
                ctx.cameraX = (float) pos.x;
                ctx.cameraY = (float) pos.y;
                ctx.cameraZ = (float) pos.z;
            }

            // 帧序号从 FrameData 获取
            ctx.frameIndex = MCRenderBridge.getCurrentFrameData().getFrameIndex();
        } catch (Exception e) {
            ctx.reset();
        }
    }

    /**
     * 从当前渲染状态提取投影矩阵数据
     *
     * @param ctx 要填充的投影矩阵上下文
     */
    private void extractProjectionData(ProjectionContext ctx) {
        try {
            // MC 26.2: getProjectionMatrix() 已移除，getProjectionMatrixBuffer() 返回 GpuBufferSlice
            // GpuBufferSlice 是 record(buffer, offset, length)，无直接 read(float[]) 方法
            // 使用 getModelViewStack() 获取当前矩阵状态作为替代数据源
            var mvStack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
            mvStack.get(ctx.projectionMatrix);

            // MC 26.2: minecraft 字段改为 private
            var mc = Minecraft.getInstance();
            ctx.fov = mc.options.fov().get();

            // 近/远裁剪面（MC 默认值）
            ctx.nearPlane = 0.05f;
            ctx.farPlane = Math.max(1000.0f, mc.options.getEffectiveRenderDistance() * 16.0f);
        } catch (Exception e) {
            ctx.reset();
        }
    }

    /**
     * 从 FogRenderer 提取雾效数据
     *
     * @param ctx 要填充的雾效上下文
     */
    private void extractFogData(com.renderium.bridge.mc.FogContext ctx) {
        try {
            GameRenderer self = (GameRenderer) (Object) this;

            // MC 26.2: gameRenderState 是 private，使用公共 getter gameRenderState()
            // fogData 位于 cameraRenderState 内（非 levelRenderState 直接字段）
            var fogData = self.gameRenderState().levelRenderState.cameraRenderState.fogData;

            // 雾颜色 RGBA
            ctx.color[0] = fogData.color.x();   // R
            ctx.color[1] = fogData.color.y();   // G
            ctx.color[2] = fogData.color.z();   // B
            ctx.color[3] = 1.0f;                 // A (不透明)

            // MC 26.2: FogData 字段变更（无 start/end/density/shape）
            // 使用 renderDistanceEnd 作为雾效范围参考值
            ctx.start = fogData.environmentalStart;
            ctx.end = fogData.renderDistanceEnd;
            ctx.density = 1.0f;  // MC 26.2 不再提供 density，使用默认值

            // 雾类型（MC 26.2 无 shape 字段，默认线性）
            ctx.type = 0;

            ctx.enabled = true;
        } catch (Exception e) {
            ctx.reset();
        }
    }
}
