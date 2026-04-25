// Renderium Fabric - FrameGraphBuilder Mixin Implementation
// High-performance: dual-mode execution with Inspector wrapping

package com.renderium.fabric.mixin;

import com.renderium.mixin.abstracts.HookManager;
import com.renderium.mixin.abstracts.hooks.FrameGraphExecuteHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin targeting Blaze3D FrameGraphBuilder for execution optimization.
 *
 * <p><b>Injection Points:</b></p>
 * <ul>
 *   <li>{@code execute()} → P1: Dual-mode with Inspector wrapping</li>
 * </ul>
 *
 * @since 3.0.0
 */
@Mixin(targets = "com.mojang.blaze3d.framegraph.FrameGraphBuilder")
public class FabricFrameGraphBuilderMixin {

    private static final FrameGraphExecuteHook EXECUTE_HOOK =
        HookManager.getFrameGraphExecuteHook();

    /**
     * Intercept FrameGraph execution for optimization mode selection.
     */
    @Inject(method = "execute",
            at = @At("HEAD"),
            remap = false)
    private void onExecute(CallbackInfo ci) {
        // Hook is called at HEAD, can modify behavior via Inspector replacement
        if (EXECUTE_HOOK == null) return;

        try {
            // The actual inspector modification happens in @ModifyVariable below
            // This injection is for pre-execution setup/logging if needed
        } catch (Exception e) {
            System.err.println("[Renderium] FrameGraph execute hook failed: " + e.getMessage());
        }
    }

    /**
     * Replace Inspector parameter with Renderium-wrapped version.
     *
     * <p>Wraps original Inspector to add performance monitoring,
     * pass reordering, and resource tracking.</p>
     */
    @ModifyVariable(method = "execute",
                   at = @At("HEAD"),
                   argsOnly = true,
                   remap = false)
    private Object onModifyInspector(Object inspector) {
        if (EXECUTE_HOOK == null) return inspector;

        try {
            Object result = EXECUTE_HOOK.onExecute(this, inspector);
            return result != null ? result : inspector;
        } catch (Exception e) {
            System.err.println("[Renderium] Inspector modification failed: " + e.getMessage());
            return inspector; // Fall back to original
        }
    }
}
