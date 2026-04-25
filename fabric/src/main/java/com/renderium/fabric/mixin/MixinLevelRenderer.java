// Renderium - LevelRenderer Mixin (v6)
// 极简版：COMPATIBILITY 模式下完全零操作
// Minecraft 26.2 (unobfuscated) - 基于 FrameGraph 的新渲染管线
//
// v5 -> v6: 移除 renderLevel HEAD/TAIL 注入（10 参数开销太大）
//          保留 endFrame 注入（0 参数，仅更新模式标志）
//
// 性能说明：
// - renderLevel 是每帧调用的热路径方法
// - MC 26.2 中该方法有 10 个参数，Mixin 参数捕获开销显著
// - COMPATIBILITY 模式下此 Mixin 不做任何有用工作
// - 因此完全移除 renderLevel 注入，只保留 endFrame

package com.renderium.fabric.mixin;

import com.renderium.core.RenderiumCore;
import com.renderium.core.RenderiumDualModeManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LevelRenderer Mixin (v6) - 零开销版
 *
 * <p><b>v6 变更：</b>移除 {@code renderLevel} 方法的 HEAD/TAIL 注入。
 * 原 v5 版本在 renderLevel（10 参数）上做了 Mixin，
 * 即使在 COMPATIBILITY 模式下快速返回，Mixin 框架仍需处理参数捕获。</p>
 *
 * <p><b>保留的功能：</b></p>
 * <ul>
 *   <li>{@code endFrame} TAIL 注入：0 参数，仅更新 isOptimizationEnabled 标志</li>
 * </ul>
 *
 * @see RenderiumDualModeManager
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-LevelRenderer");

    @Unique
    private static volatile boolean isOptimizationEnabled = false;

    /**
     * 在 endFrame 方法尾部注入（0 参数，极低开销）
     *
     * <p>每帧结束时重新评估优化状态缓存。</p>
     */
    @Inject(method = "endFrame", at = @At("TAIL"))
    private void onEndFrame(CallbackInfo ci) {
        try {
            RenderiumDualModeManager modeManager = RenderiumDualModeManager.getInstance();
            // ====== L0 规范: 通过反射查询 Core 状态（禁止直接调用）======
            boolean coreActive = queryCoreActive();

            isOptimizationEnabled = coreActive && modeManager.isAggressiveMode();
        } catch (Exception e) {
            isOptimizationEnabled = false;
        }
    }

    /**
     * 查询 Core 是否激活（通过反射）
     */
    private static boolean queryCoreActive() {
        try {
            Class<?> coreClass = Class.forName("com.renderium.core.RenderiumCore");
            Object coreInstance = coreClass.getMethod("getInstance").invoke(null);
            return (Boolean) coreClass.getMethod("isActive").invoke(coreInstance);
        } catch (Exception e) {
            return false;
        }
    }
}
