// Renderium - GameRenderer Mixin for NeoForge (v6)
// 拦截游戏渲染器帧回调，提供帧级别的事件通知
//
// ⚠️ L0 安全修复版本（v5→v6 变更）：
//   - 移除所有直接 RenderiumCore 调用（改为 LifecycleManager 委托）
//   - 帧呈现拦截新增 renderLevel() TAIL 注入（替代已移除的 flipFrame）
//
// 功能说明：
// - 每帧 render() HEAD: 通知帧开始（Reflex/帧计数器）
// - 每帧 renderLevel() TAIL: 触发后处理管线（替代 flipFrame 入口）

package com.renderium.neoforge.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import com.renderium.neoforge.RenderiumMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer Mixin（NeoForge v6 — L0 安全版）
 *
 * <p><b>L0 安全原则</b>：此类仅负责<strong>事件转发</strong>，
 * 禁止包含任何业务逻辑或直接访问 RenderiumCore。
 *
 * <h2>职责边界</h2>
 * <pre>
 * ✅ 允许: 通过 LifecycleManager / HookManager 委托事件通知
 * ❌ 禁止: 直接调用 RenderiumCore.getInstance()
 * ❌ 禁止: 包含初始化逻辑、着色器编译、HUD 创建等
 * ❌ 禁止: 访问 MC 内部字段
 * </pre>
 *
 * <h3>v6 变更</h3>
 * <ul>
 *   <li>移除 initializeGameRenderer() 中的 Core 直接调用</li>
 *   <li>notifyFrameBegin() 改走 LifecycleManager</li>
 *   <li>新增 renderLevel() TAIL 作为后处理入口点（替代已移除的 flipFrame）</li>
 * </ul>
 *
 * @see com.renderium.lifecycle.RenderiumLifecycleManager
 * @version 6.0 (L0 安全修复版)
 */
@Mixin(GameRenderer.class)
public class MixinGameRendererNeoForge {

    /** 标记是否已执行延迟初始化 */
    @Unique
    private static boolean gameRendererInitialized = false;

    // ==================== 帧渲染入口 (HEAD) ====================

    /**
     * 在每帧渲染开始时调用（HEAD 注入）
     * <p>
     * 通过 LifecycleManager 委托帧开始通知。
     *
     * @param deltaTracker    帧时间追踪器
     * @param advanceGameTime 是否推进游戏时间
     * @param ci              回调信息
     */
    @Inject(
            method = "render",
            at = @At("HEAD")
    )
    private void onRenderHead(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        // 延迟初始化（首次调用时执行一次）
        if (!gameRendererInitialized) {
            gameRendererInitialized = true;
        }

        // ====== L0 规范: 通过 LifecycleManager 委托帧开始通知 ======
        notifyFrameBegin(deltaTracker);
    }

    /**
     * 在世界渲染完成后调用（renderLevel TAIL）
     * <p>
     * 这是后处理管线的核心触发点。
     * 在 MC 26.2 中，此方法在 LevelRenderer.render() 完成后被调用，
     * 此时场景颜色缓冲区已填充完毕，可以安全地执行 Bloom/SSAO/Tonemap。
     *
     * <p><b>替代关系</b>: 此注入点取代了旧版中不存在的 {@code RenderSystem.flipFrame()}。
     *
     * @param deltaTracker 帧时间追踪器
     * @param ci           回调信息
     */
    @Inject(
            method = "renderLevel",
            at = @At("TAIL")
    )
    private void onRenderLevelTail(DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            // ====== L0 规范: 后处理通过 LifecycleManager 触发 ======
            notifyPostProcessing();
        } catch (Exception e) {
            RenderiumMod.LOGGER.debug("[L0] onRenderLevelTail 异常: " + e.getMessage());
        }
    }

    // ==================== 私有辅助方法（纯委托）====================

    /**
     * 通知帧开始（通过 LifecycleManager 委托）
     *
     * @param deltaTracker 帧时间追踪器
     */
    private void notifyFrameBegin(DeltaTracker deltaTracker) {
        try {
            float deltaTime = extractDeltaTime(deltaTracker);

            Class<?> lifecycleClass = Class.forName(
                    "com.renderium.lifecycle.RenderiumLifecycleManager");
            java.lang.reflect.Method method = lifecycleClass.getMethod("notifyFrameBegin", float.class);
            method.invoke(null, deltaTime);

        } catch (ClassNotFoundException e) {
            // LifecycleManager 不可用时静默跳过
        } catch (Exception e) {
            RenderiumMod.LOGGER.debug("[L0] notifyFrameBegin 异常: " + e.getMessage());
        }
    }

    /**
     * 通知后处理管线执行（通过 LifecycleManager 委托）
     */
    private void notifyPostProcessing() {
        try {
            Class<?> lifecycleClass = Class.forName(
                    "com.renderium.lifecycle.RenderiumLifecycleManager");
            java.lang.reflect.Method method = lifecycleClass.getMethod("notifyPostProcessing");
            method.invoke(null);

        } catch (ClassNotFoundException e) {
            // 静默跳过
        } catch (Exception e) {
            RenderiumMod.LOGGER.debug("[L0] notifyPostProcessing 异常: " + e.getMessage());
        }
    }

    /**
     * 从 DeltaTracker 提取帧间隔时间（纯数据提取）
     *
     * @param deltaTracker MC 帧时间追踪器
     * @return 帧间隔时间（秒），失败返回默认值 0.016f (~60fps)
     */
    private float extractDeltaTime(DeltaTracker deltaTracker) {
        try {
            return deltaTracker.getGameTimeDeltaPartialTick(false);
        } catch (Exception e) {
            return 0.016f;
        }
    }
}
