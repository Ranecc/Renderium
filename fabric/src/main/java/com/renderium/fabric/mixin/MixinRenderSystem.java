// Renderium - RenderSystem Mixin (Fabric) [DEPRECATED]
//
// ⚠️ L0 安全修复: 此文件已弃用！
//   原因: 与 common 层的 com.renderium.mixin.MixinRenderSystem 冲突
//        两个 Mixin 都注入 RenderSystem.initRenderer() @TAIL，
//        导致 RenderiumCore.initialize() 被调用两次。
//
// ✅ 替代方案:
//   common 层的 MixinRenderSystem 已包含完整功能：
//     1. VulkanDevice 句柄提取（跨平台通用）
//     2. VulkanDeviceHolder 初始化
//     3. Streamline SDK 初始化
//     4. RenderiumCore.initialize() 调用
//
// 此文件保留仅为向后兼容，实际不会加载
// （应在 mixin 配置中排除此 Mixin）

package com.renderium.fabric.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @deprecated 使用 {@code com.renderium.mixin.MixinRenderSystem} (common 层) 替代。
 *             此 Mixin 已被废弃以防止双重初始化问题。
 * <p>
 * 历史原因：此 Mixin 在 common 层 MixinRenderSystem 创建之前存在，
 * 用于 Fabric 平台的渲染系统初始化。现在 common 层版本已覆盖其所有功能。
 */
@Deprecated(since = "6.0", forRemoval = true)
@Mixin(RenderSystem.class)
public class MixinRenderSystem {

    /**
     * 空实现 — 所有初始化逻辑已迁移到 common 层
     *
     * @param device GpuDevice 实例
     * @param ci     回调信息
     */
    @Inject(method = "initRenderer", at = @At("TAIL"))
    private static void onInitRenderer(GpuDevice device, CallbackInfo ci) {
        // NO-OP: 由 common 层 MixinRenderSystem 处理
    }
}
