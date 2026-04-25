// Renderium Fabric - CommandEncoder Mixin Implementation
// MC 26.2-snapshot-3 compatible (API verified from decompile)
// Target: com.mojang.blaze3d.systems.CommandEncoder

package com.renderium.fabric.mixin;

import com.renderium.mixin.abstracts.HookManager;
import com.renderium.mixin.abstracts.hooks.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin targeting Blaze3D CommandEncoder for command optimization.
 *
 * <p><b>Target Class:</b> {@code com.mojang.blaze3d.systems.CommandEncoder}</p>
 *
 * <h3>MC 26.2 Verified API (from decompile):</h3>
 * <pre>
 * public class CommandEncoder {
 *     public void submit()                                    // No params!
 *     public RenderPass createRenderPass(Supplier&lt;String&gt; label,
 *                                       GpuTextureView colorTexture,
 *                                       OptionalInt clearColor)
 *     public void writeToBuffer(GpuBufferSlice destination,     // Not (Object,Object,long,int)!
 *                              ByteBuffer data)
 *     // ... more methods
 * }
 * </pre>
 *
 * @since 3.0.0
 */
@Mixin(targets = "com.mojang.blaze3d.systems.CommandEncoder")
public class FabricCommandEncoderMixin {

    /** Cached hooks for hot path access */
    private static final CommandEncoderSubmitHook SUBMIT_HOOK =
        HookManager.getCommandEncoderSubmitHook();

    /**
     * Intercept command buffer submission for batch optimization.
     *
     * <p><b>MC 26.2 Signature:</b> {@code void submit()} - NO parameters!</p>
     *
     * <p>S4/S5: Batch merging + multi-queue scheduling.</p>
     *
     * @param ci CallbackInfo for cancellation support
     */
    @Inject(method = "submit",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void onSubmit(CallbackInfo ci) {
        if (SUBMIT_HOOK == null) return;

        try {
            if (SUBMIT_HOOK.onSubmit(null)) { // submit() has no params in 26.2
                ci.cancel();
            }
        } catch (Exception e) {
            System.err.println("[Renderium] Command submit hook failed: " + e.getMessage());
        }
    }

    // Note: createRenderPass and writeToBuffer have complex signatures with
    // MC-internal types (GpuBufferSlice, GpuTextureView, etc.) that are tricky
    // to mixin with remap=false. These can be added later when needed.
    // For now, the critical submit() hook is working.
}
