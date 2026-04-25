// Renderium - 轻量 MC 抽象层
// LevelRenderer 生命周期 Mixin - Thin Glue 层（~70 行）
//
// 注入点（来自 render-lifecycle-complete-analysis.md）：
//   op⑨: repositionCamera() 前后 → 相机位置/朝向捕获
//   op⑩: setModelViewMatrix() 前后 → 视图矩阵捕获
//   op⑭: prepareChunkRenders() 前后 → 区块可见性数据捕获
//
// 性能预算：每个注入点 < 5μs

package com.renderium.fabric.mixin;

import com.renderium.bridge.mc.CameraContext;
import com.renderium.bridge.mc.ChunkContext;
import com.renderium.bridge.mc.MatrixContext;
import com.renderium.bridge.mc.RenderiumLifecycleManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LevelRenderer 生命周期 Mixin（Thin Glue 层）
 * <p>
 * 在 LevelRenderer.render() 的关键操作点注入生命周期钩子，
 * 捕获相机位置、视图矩阵、区块可见性等渲染状态。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>仅 Mixin 极稳定的公共方法（render）</li>
 *   <li>不修改控制流或返回值</li>
 *   <li>性能开销 &lt; 5μs/注入点</li>
 *   <li>总代码量 &lt; 70 行</li>
 * </ul>
 *
 * @see RenderiumLifecycleManager
 * @since 2.1.0
 */
@Mixin(LevelRenderer.class)
public class LevelRendererLifecycleMixin {

    /** 是否已初始化 */
    @Unique
    private static volatile boolean lifecycleInitialized = false;

    // ==================== op⑨: repositionCamera() 钩子 ====================

    /**
     * Hook: repositionCamera() 之后 [op⑨ 后]
     *
     * <p>注入位置：LevelRenderer.render() 中 repositionCamera() 调用之后</p>
     * <p>目的：捕获相机位置和朝向，这是 CSM 级联分割的关键数据源</p>
     *
     * @param resourceAllocator 图形资源分配器
     * @param deltaTracker 帧时间跟踪器
     * @param renderOutline 是否渲染轮廓线
     * @param cameraState 相机渲染状态
     * @param modelViewMatrix 模型视图矩阵
     * @param terrainFog 地形雾效缓冲区
     * @param fogColor 雾颜色
     * @param shouldCreateBossFog 是否创建 Boss 雾效
     * @param ci Mixin 回调信息
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;" +
                    "repositionCamera(Lnet/minecraft/client/renderer/CameraRenderState;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterCameraReposition(Object resourceAllocator,
                                         Object deltaTracker,
                                         boolean renderOutline,
                                         Object cameraState,
                                         Object modelViewMatrix,
                                         Object terrainFog,
                                         Object fogColor,
                                         boolean shouldCreateBossFog,
                                         CallbackInfo ci) {
        if (!lifecycleInitialized) return;

        try {
            RenderiumLifecycleManager lifecycle = RenderiumLifecycleManager.getInstance();
            if (lifecycle.getLevelRendererListenerCount() == 0) return;

            CameraContext ctx = lifecycle.acquireCameraContext();
            extractCameraData(ctx, cameraState);

            lifecycle.fireAfterCameraRepos(ctx);
        } catch (Exception ignored) {}
    }

    // ==================== op⑩: setModelViewMatrix() 钩子 ====================

    /**
     * Hook: setModelViewMatrix() 之后 [op⑩ 后]
     *
     * <p>注入位置：LevelRenderer.render() 中模型视图矩阵压栈之后</p>
     * <p>目的：捕获视图矩阵（世界空间 → 观察空间变换）</p>
     * <p>这是 G-Buffer Position/Normal 输出的关键数据源</p>
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem$ModelViewMatrixStack;" +
                    "mul(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterModelViewSet(Object resourceAllocator,
                                     Object deltaTracker,
                                     boolean renderOutline,
                                     Object cameraState,
                                     Object modelViewMatrix,
                                     Object terrainFog,
                                     Object fogColor,
                                     boolean shouldCreateBossFog,
                                     CallbackInfo ci) {
        if (!lifecycleInitialized) return;

        try {
            RenderiumLifecycleManager lifecycle = RenderiumLifecycleManager.getInstance();
            if (lifecycle.getLevelRendererListenerCount() == 0) return;

            MatrixContext ctx = lifecycle.acquireMatrixContext();
            extractViewMatrix(ctx);

            lifecycle.fireAfterModelViewSet(ctx);
        } catch (Exception ignored) {}
    }

    // ==================== op⑭: prepareChunkRenders() 钩子 ====================

    /**
     * Hook: prepareChunkRenders() 之后 [op⑭ 后]
     *
     * <p>注入位置：LevelRenderer.render() 中 prepareChunkRenders() 调用之后</p>
     * <p>目的：捕获区块可见性数据和 Draw Call 统计</p>
     * <p>这是优化剔除策略的关键数据源</p>
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;" +
                    "prepareChunkRenders(Lorg/joml/Matrix4fc;)" +
                    "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterPrepareChunkRenders(Object resourceAllocator,
                                            Object deltaTracker,
                                            boolean renderOutline,
                                            Object cameraState,
                                            Object modelViewMatrix,
                                            Object terrainFog,
                                            Object fogColor,
                                            boolean shouldCreateBossFog,
                                            CallbackInfo ci) {
        if (!lifecycleInitialized) return;

        try {
            RenderiumLifecycleManager lifecycle = RenderiumLifecycleManager.getInstance();
            if (lifecycle.getLevelRendererListenerCount() == 0) return;

            ChunkContext ctx = lifecycle.acquireChunkContext();
            extractChunkVisibilityData(ctx);

            lifecycle.fireAfterChunkPrepare(ctx);
        } catch (Exception ignored) {}
    }

    // ==================== 初始化钩子 ====================

    /**
     * Hook: render() 方法头部 - 初始化检查
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(Object resourceAllocator,
                             Object deltaTracker,
                             boolean renderOutline,
                             Object cameraState,
                             Object modelViewMatrix,
                             Object terrainFog,
                             Object fogColor,
                             boolean shouldCreateBossFog,
                             CallbackInfo ci) {
        // 懒初始化（仅首次）
        if (!lifecycleInitialized) {
            lifecycleInitialized = (RenderiumLifecycleManager.getInstance() != null);
        }
    }

    // ==================== 数据提取方法（私有辅助） ====================

    /**
     * 从 CameraRenderState 提取相机位置和朝向数据
     *
     * @param ctx 要填充的相机上下文
     * @param cameraState MC 的 CameraRenderState 实例
     */
    private void extractCameraData(CameraContext ctx, Object cameraState) {
        try {
            LevelRenderer self = (LevelRenderer) (Object) this;

            // 通过反射或直接访问获取相机位置
            var pos = self.minecraft.gameRenderState.cameraRenderState.pos;
            ctx.x = (float) pos.x;
            ctx.y = (float) pos.y;
            ctx.z = (float) pos.z;

            // 提取相机朝向（Yaw/Pitch）
            var rotation = self.minecraft.gameRenderState.cameraRenderState.viewRotationMatrix;
            ctx.yaw = (float) Math.toDegrees(Math.atan2(rotation.m02, rotation.m22));
            ctx.pitch = (float) Math.toDegrees(Math.asin(-Math.clamp(rotation.m12, -1.0, 1.0)));

            // 视锥体区域是否变化（简单启发式：位置变化 > 0.1 块）
            FrameDataSnapshot fd = com.renderium.bridge.mc.MCRenderBridge.getCurrentFrameData();
            float dx = Math.abs(fd.getCameraX() - ctx.x);
            float dy = Math.abs(fd.getCameraY() - ctx.y);
            float dz = Math.abs(fd.getCameraZ() - ctx.z);
            ctx.viewAreaChanged = (dx + dy + dz) > 0.1f;
        } catch (Exception e) {
            ctx.reset();
        }
    }

    /**
     * 从 RenderSystem 提取当前视图矩阵
     *
     * @param ctx 要填充的矩阵上下文
     */
    private void extractViewMatrix(MatrixContext ctx) {
        try {
            var mvMatrix = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix();
            mvMatrix.get(ctx.modelViewMatrix);
        } catch (Exception e) {
            ctx.reset();
        }
    }

    /**
     * 从 LevelRenderer 提取区块可见性数据
     *
     * @param ctx 要填充的区块上下文
     */
    private void extractChunkVisibilityData(ChunkContext ctx) {
        try {
            LevelRenderer self = (LevelRenderer) (Object) this;

            // 可见区块段数量（从 SectionRenderDispatcher 获取）
            int visibleSections = self.sectionRenderDispatcher != null ?
                self.sectionRenderDispatcher.getVisibleSectionCount() : 0;

            // 总区块段数量
            int totalSections = self.sectionRenderDispatcher != null ?
                self.sectionRenderDispatcher.getTotalSectionCount() : 0;

            // Draw Call 统计（估算：每个可见 Section 至少 1 个 Opaque Draw Call）
            int opaqueDrawCalls = visibleSections;  // 简化估算
            int translucentDrawCalls = visibleSections / 4;  // 半透明通常较少

            ctx.visibleSectionCount = visibleSections;
            ctx.totalSectionCount = totalSections;
            ctx.opaqueDrawCallCount = opaqueDrawCalls;
            ctx.translucentDrawCallCount = translucentDrawCalls;
            ctx.viewAreaChanged = false;  // 已在 CameraContext 中设置
        } catch (Exception e) {
            ctx.reset();
        }
    }
}
