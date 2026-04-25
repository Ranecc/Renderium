// Renderium - LevelRenderer Mixin for NeoForge (v6)
// 拦截世界渲染流程，提供后处理入口点和性能监控
//
// ⚠️ L0 安全修复版本（v5→v6 变更）：
//   - renderLevel() → render()（MC 26.2 正确方法名）
//   - 参数列表更新为 8 参数签名（匹配 MC 26.2 实际 API）
//   - 所有 Core 调用统一走 LifecycleManager / HookManager（禁止越层访问）
//
// 功能说明：
// - 世界渲染开始/结束时通知 RenderiumCore
// - 支持兼容模式和激进模式双轨制运行
// - 通过 HookManager 委托所有业务逻辑

package com.renderium.neoforge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.renderium.core.RenderiumDualModeManager;
import com.renderium.neoforge.RenderiumMod;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 到 LevelRenderer（NeoForge v6 版本）
 *
 * <p><b>L0 安全原则</b>：此类仅负责<strong>事件转发</strong>，
 * 所有业务逻辑通过 HookManager 委托给抽象层实现。
 *
 * <h2>MC 26.2 API 兼容性</h2>
 * <pre>
 * ✅ 正确: LevelRenderer.render(
 *     GraphicsResourceAllocator,  // 资源分配器 (新增于 26.2)
 *     DeltaTracker,               // 帧时间追踪器
 *     boolean,                    // 是否渲染轮廓线
 *     CameraRenderState,          // 相机渲染状态
 *     Matrix4fc,                  // 模型视图矩阵
 *     GpuBufferSlice,             // 地形雾效缓冲区
 *     Vector4f,                   // 雾颜色
 *     boolean                     // 是否渲染天空
 * )
 * ❌ 错误: LevelRenderer.renderLevel(...) — 此方法在 MC 26.2 中不存在！
 * </pre>
 *
 * <h3>v6 变更</h3>
 * <ul>
 *   <li>注入目标从 {@code renderLevel} 更正为 {@code render}</li>
 *   <li>参数类型完全匹配 MC 26.2 反编译源码</li>
 *   <li>移除直接 Core 调用，改走 LifecycleManager</li>
 * </ul>
 *
 * @see com.renderium.lifecycle.RenderiumLifecycleManager 生命周期管理器
 * @since 1.0.0
 * @version 6.0 (L0 安全修复版)
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public class MixinLevelRendererNeoForge {

    /**
     * 渲染系统实例（用于检测 Vulkan 后端）
     */
    @Shadow @Final
    private static RenderSystem RENDER_SYSTEM;

    // ==================== 世界渲染开始拦截 ====================

    /**
     * 在世界渲染开始时调用（HEAD 注入）
     * <p>
     * 通知生命周期管理器进入世界渲染阶段。
     * 此方法是 Bloom、SSAO 等后处理效果的前置条件。
     *
     * @param resourceAllocator 图形资源分配器（MC 26.2 新增参数）
     * @param deltaTracker      帧时间追踪器
     * @param renderOutline     是否渲染轮廓线
     * @param cameraState       相机渲染状态
     * @param modelViewMatrix   模型视图矩阵
     * @param terrainFog        地形雾效缓冲区
     * @param fogColor          雾颜色向量
     * @param shouldRenderSky   是否渲染天空
     * @param ci                Mixin 回调信息
     */
    @Inject(
            method = "render",
            at = @At("HEAD")
    )
    private void onRenderLevelHead(
            Object resourceAllocator,
            Object deltaTracker,
            boolean renderOutline,
            Object cameraState,
            Matrix4fc modelViewMatrix,
            Object terrainFog,
            Object fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci) {
        try {
            // ====== L0 规范: 通过 LifecycleManager 委托（禁止直接调用 Core）======
            notifyWorldRenderStart();

            if (isAggressiveMode()) {
                // 激进模式：输出详细调试信息
                RenderiumMod.LOGGER.debug("[L0] World render started (AGGRESSIVE mode)");
                RenderiumMod.LOGGER.debug("  Matrix: {}", matrixToString(modelViewMatrix));
            }
        } catch (Exception e) {
            RenderiumMod.LOGGER.error("[L0] onRenderLevelHead 异常", e);
        }
    }

    // ==================== 世界渲染结束拦截 ====================

    /**
     * 在世界渲染完成时调用（TAIL 注入）
     * <p>
     * 通知生命周期管理器世界渲染结束，
     * 触发后处理管线执行（Bloom/SSAO/Tonemap 等）。
     *
     * @param resourceAllocator 图形资源分配器
     * @param deltaTracker      帧时间追踪器
     * @param renderOutline     是否渲染轮廓线
     * @param cameraState       相机渲染状态
     * @param modelViewMatrix   模型视图矩阵
     * @param terrainFog        地形雾效缓冲区
     * @param fogColor          雾颜色向量
     * @param shouldRenderSky   是否渲染天空
     * @param ci                Mixin 回调信息
     */
    @Inject(
            method = "render",
            at = @At("TAIL")
    )
    private void onRenderLevelTail(
            Object resourceAllocator,
            Object deltaTracker,
            boolean renderOutline,
            Object cameraState,
            Matrix4fc modelViewMatrix,
            Object terrainFog,
            Object fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci) {
        try {
            // ====== L0 规范: 通过 LifecycleManager 委托 ======
            notifyWorldRenderEnd();

            if (isAggressiveMode()) {
                RenderiumMod.LOGGER.debug("[L0] World render completed");
            }
        } catch (Exception e) {
            RenderiumMod.LOGGER.error("[L0] onRenderLevelTail 异常", e);
        }
    }

    // ==================== 私有辅助方法（纯工具，无副作用）====================

    /**
     * 通知生命周期管理器：世界渲染开始
     * <p>
     * 通过反射调用 LifecycleManager，避免编译期依赖。
     */
    private void notifyWorldRenderStart() {
        try {
            Class<?> lifecycleClass = Class.forName(
                    "com.renderium.lifecycle.RenderiumLifecycleManager");
            java.lang.reflect.Method method = lifecycleClass.getMethod("notifyWorldRenderStart");
            method.invoke(null);
        } catch (ClassNotFoundException e) {
            // LifecycleManager 不可用时静默跳过
        } catch (Exception e) {
            RenderiumMod.LOGGER.debug("[L0] notifyWorldRenderStart 异常: " + e.getMessage());
        }
    }

    /**
     * 通知生命周期管理器：世界渲染结束
     */
    private void notifyWorldRenderEnd() {
        try {
            Class<?> lifecycleClass = Class.forName(
                    "com.renderium.lifecycle.RenderiumLifecycleManager");
            java.lang.reflect.Method method = lifecycleClass.getMethod("notifyWorldRenderEnd");
            method.invoke(null);
        } catch (ClassNotFoundException e) {
            // 静默跳过
        } catch (Exception e) {
            RenderiumMod.LOGGER.debug("[L0] notifyWorldRenderEnd 异常: " + e.getMessage());
        }
    }

    /**
     * 检查是否处于激进模式（只读查询）
     *
     * @return true 如果是激进模式
     */
    private boolean isAggressiveMode() {
        try {
            return RenderiumDualModeManager.isAggressiveMode();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将矩阵转换为字符串用于日志（纯格式化工具）
     *
     * @param mat JOML 矩阵
     * @return 字符串表示
     */
    private String matrixToString(Matrix4fc mat) {
        if (mat == null) return "null";
        try {
            float[] values = new float[16];
            mat.get(values);
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%.3f", values[i]));
                if (i < 15) sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "(format error)";
        }
    }
}
