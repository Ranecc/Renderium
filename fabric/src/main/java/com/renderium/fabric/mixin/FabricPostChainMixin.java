// Renderium Fabric - PostChain Mixin Implementation
// MC 26.2-snapshot-3 compatible (API verified from decompile)
//
// MC 26.2 addToFrame() Signature (from crash report):
//   void addToFrame(FrameGraphBuilder frameGraphBuilder,
//                  int width,
//                  int height,
//                  PostChain.TargetBundle targetBundle)

package com.renderium.fabric.mixin;

import com.renderium.mixin.abstracts.HookManager;
import com.renderium.mixin.abstracts.hooks.PostChainHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin targeting Minecraft PostChain for Streamline integration.
 *
 * <p><b>Target:</b> {@code net.minecraft.client.renderer.PostChain}</p>
 *
 * <h3>MC 26.2 addToFrame() API:</h3>
 * <pre>
 * void addToFrame(
 *     FrameGraphBuilder frameGraphBuilder,
 *     int width,
 *     int height,
 *     TargetBundle targetBundle
 * )
 * </pre>
 *
 * @since 3.0.0
 */
@Mixin(targets = "net.minecraft.client.renderer.PostChain")
public class FabricPostChainMixin {

    private static final PostChainHook POST_CHAIN_HOOK = HookManager.getPostChainHook();

    /**
     * Intercept post-processing addition to frame for Streamline data preparation.
     *
     * <p>Called when post-processing chain is added to a frame.
     * Used for DLSS/FSR motion vector generation.</p>
     *
     * @param frameGraphBuilder The FrameGraphBuilder for this frame
     * @param width             Frame width in pixels
     * @param height            Frame height in pixels
     * @param targetBundle      Post-processing target bundle
     * @param ci                CallbackInfo for cancellation
     */
    @Inject(method = "addToFrame",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void onAddToFrame(Object frameGraphBuilder,  // FrameGraphBuilder (use Object for remap=false)
                              int width,
                              int height,
                              Object targetBundle,       // TargetBundle (use Object for remap=false)
                              CallbackInfo ci) {
        if (POST_CHAIN_HOOK == null) return;

        try {
            // Pass all parameters to hook for processing
            if (POST_CHAIN_HOOK.onAddToFrame(this, frameGraphBuilder)) {
                ci.cancel();
            }
        } catch (Exception e) {
            System.err.println("[Renderium] PostChain hook failed: " + e.getMessage());
        }
    }
}
