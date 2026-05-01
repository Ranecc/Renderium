package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.platform.hook.HookDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/**
 * L0 Thin Glue Mixin for window lifecycle.
 * <p>
 * In MC 26.2, Window is internal. This mixin is kept as a placeholder
 * for future hook points. The actual window-ready notification
 * is handled via RenderSystem.initRenderer() callback instead.
 */
@Mixin(Object.class) // Placeholder target - will be re-enabled when MC exposes Window
public abstract class MixinWindow {
    // No-op in MC 26.2 - Window initialization handled by MixinRenderSystem
}
