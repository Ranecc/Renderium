package com.ranecc.renderium.platform.mixin;

/**
 * L0 Thin Glue 占位类 for window lifecycle.
 * <p>
 * 注意：此类是占位符，不是 Mixin。在 MC 26.2 中 Window 是内部类，
 * 此前使用 @Mixin(Object.class) 作为占位目标，但会造成 Mixin 处理器
 * 不必要的开销（Mixin 会扫描 Object 类）。因此移除 @Mixin 注解，
 * 改为普通工具类，等待 MC 暴露 Window 类后重新启用真正的 Mixin 注入。
 * <p>
 * 当前的 window-ready 通知由 MixinRenderSystem 的回调处理。
 */
public final class MixinWindow {
    private MixinWindow() {
        // 工具类，禁止实例化
    }

    // No-op in MC 26.2 - Window initialization handled by MixinRenderSystem
}
