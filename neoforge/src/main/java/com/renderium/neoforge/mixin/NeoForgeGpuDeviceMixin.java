// Renderium NeoForge - GpuDevice Mixin Implementation
// High-performance: delegates to HookManager (same logic as Fabric)
// Platform-specific: uses NeoForge Mixin annotations

package com.renderium.neoforge.mixin;

import com.renderium.buffers.GpuBuffer;
import com.renderium.mixin.abstracts.HookManager;
import com.renderium.mixin.abstracts.hooks.GpuDeviceBufferHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * NeoForge implementation of GpuDevice Mixin.
 *
 * <p>Functionally identical to FabricGpuDeviceMixin but compiled
 * against NeoForge's Mixin infrastructure. Both delegate to the same
 * {@link HookManager} and {@link OptimizerRegistry} for shared logic.</p>
 *
 * @see com.renderium.fabric.mixin.FabricGpuDeviceMixin
 * @since 3.0.0
 */
@Mixin(targets = "com.mojang.blaze3d.platform.GpuDevice")
public class NeoForgeGpuDeviceMixin {

    private static final ThreadLocal<GpuDeviceBufferHook> CACHED_HOOK = ThreadLocal.withInitial(
        () -> HookManager.getGpuDeviceBufferHook()
    );

    @Inject(method = "createBuffer",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void onCreateBuffer(Supplier<String> label, int usage, long size,
                                 CallbackInfoReturnable<GpuBuffer> ci) {
        GpuDeviceBufferHook hook = CACHED_HOOK.get();
        if (hook == null) return;

        try {
            Object result = hook.onCreateBuffer(label, usage, size);
            if (result != null) {
                ci.setReturnValue((GpuBuffer) result);
            }
        } catch (Exception e) {
            System.err.println("[Renderium-NeoForge] GpuDevice buffer hook failed: " + e.getMessage());
        }
    }

    @Inject(method = "precompilePipeline",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void onPrecompilePipeline(Object pipeline, Object shaderSource,
                                       CallbackInfoReturnable<Object> ci) {
        var optimizer = com.renderium.mixin.abstracts.OptimizerRegistry.getPipelineOptimizer();
        if (optimizer == null || !optimizer.isEnabled()) return;

        try {
            long configHash = 31L * 17L + (pipeline != null ? pipeline.hashCode() : 0);
            configHash = 31L * configHash + (shaderSource != null ? shaderSource.hashCode() : 0);

            Object cached = optimizer.getCachedPipeline(configHash);
            if (cached != null) {
                ci.setReturnValue(cached);
            }
        } catch (Exception e) {
            System.err.println("[Renderium-NeoForge] Pipeline cache failed: " + e.getMessage());
        }
    }
}
