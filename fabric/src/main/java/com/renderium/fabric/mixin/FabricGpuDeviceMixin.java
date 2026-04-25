// Renderium Fabric - GpuDevice Mixin Implementation
// MC 26.2-snapshot-3 compatible
// Target: com.mojang.blaze3d.systems.GpuDevice

package com.renderium.fabric.mixin;

import com.renderium.mixin.abstracts.HookManager;
import com.renderium.mixin.abstracts.hooks.GpuDeviceBufferHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Mixin targeting Blaze3D GpuDevice for memory optimization.
 *
 * <p><b>Target Class (MC 26.2):</b> {@code com.mojang.blaze3d.systems.GpuDevice}</p>
 *
 * <h3>MC 26.2 API:</h3>
 * <pre>
 * createCommandEncoder() → CommandEncoder
 * submit(CommandBuffer)
 * createComputePipeline(ShaderSource) → ComputePipeline
 * createBuffer(Supplier&lt;String&gt; label, int usage, long size) → Buffer
 * </pre>
 *
 * @since 3.0.0
 */
@Mixin(targets = "com.mojang.blaze3d.systems.GpuDevice")
public class FabricGpuDeviceMixin {

    /** Cache hook reference for hot path access */
    private static final ThreadLocal<GpuDeviceBufferHook> CACHED_HOOK = ThreadLocal.withInitial(
        () -> HookManager.getGpuDeviceBufferHook()
    );

    /**
     * Intercept buffer creation for Arena-based allocation.
     *
     * <p>Optimization strategies: A1 (Per-Frame), A2 (Ring), A3 (Pool)</p>
     */
    @Inject(method = "createBuffer",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void onCreateBuffer(Supplier<String> label, int usage, long size,
                                 CallbackInfoReturnable<Object> ci) {
        GpuDeviceBufferHook hook = CACHED_HOOK.get();
        if (hook == null) return;

        try {
            Object result = hook.onCreateBuffer(label, usage, size);
            if (result != null) ci.setReturnValue(result);
        } catch (Exception e) {
            System.err.println("[Renderium] GpuDevice buffer hook failed: " + e.getMessage());
        }
    }

    // Note: Static utility methods removed from mixin class.
    // Mixin requires all static methods to be private.
    // Use GpuDeviceMixin directly for initialization status management.
}
