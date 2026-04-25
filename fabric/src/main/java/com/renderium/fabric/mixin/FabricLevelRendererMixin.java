// Renderium Fabric - LevelRenderer Mixin (v7)
// 极简版：移除热路径上的所有操作
// MC 26.2-snapshot-3 compatible
//
// 变更记录:
// v6 -> v7: 移除 logPeriodicStatus() 和 AtomicLong 计数器
//          世界加载检测改为一次性标志位

package com.renderium.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LevelRenderer Mixin (v7) - 极简版
 *
 * <p><b>v7 设计原则：</b></p>
 * <ul>
 *   <li>热路径零操作：不在 renderLevel 中做任何工作</li>
 *   <li>世界加载检测：使用一次性标志位，不做日志</li>
 *   <li>无计数器、无定期日志、无字符串格式化</li>
 * </ul>
 *
 * @since 1.0.0
 * @version 7.0 (zero-overhead)
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public class FabricLevelRendererMixin {

    /** 世界是否已加载（一次性标志） */
    @Unique
    private static volatile boolean worldLogged = false;

    /**
     * 拦截 renderLevel 方法（世界渲染入口）
     *
     * <p>v7 版本：完全空实现。</p>
     * <p>原版 v6 在此处做了：</p>
     * <ul>
     *   <li>AtomicLong 递增（已移除）</li>
     *   <li>每 600 帧输出日志（已移除）</li>
     *   <li>String.format + Minecraft.getInstance()（已移除）</li>
     * </ul>
     */
    @Inject(method = "renderLevel", at = @At("HEAD"), remap = false)
    private void onRenderLevelHead(CallbackInfo ci) {
        // 完全空实现 —— 不做任何操作
        // 世界加载检测已移至 endFrame（非热路径）
    }

    /**
     * 在 endFrame 中检测世界加载（每帧末尾调用，非热路径）
     */
    @Inject(method = "endFrame", at = @At("TAIL"), remap = false)
    private void onEndFrame(CallbackInfo ci) {
        if (!worldLogged) {
            worldLogged = true;
            // 可选：启动时只输出一次（SLF4J 异步写入）
            // org.slf4j.LoggerFactory.getLogger("Renderium-LevelRenderer")
            //     .info("[Renderium] LevelRenderer active");
        }
    }
}
