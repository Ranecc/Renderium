// Renderium - Vulkan Backend Mixin (MC 26.2-snapshot-3)
// Target: com.mojang.blaze3d.vulkan.VulkanBackend
//
// MC 26.2 Actual API (from decompile):
//   public class VulkanBackend implements GpuBackend {
//       public String getName()
//       public void setWindowHints()
//       public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions)
//       // Note: getGraphicsQueue/getComputeQueue are on GpuDevice, NOT VulkanBackend!
//   }

package com.renderium.fabric.mixin;

import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.logging.Logger;

/**
 * Vulkan Backend Mixin for MC 26.2-snapshot-3.
 *
 * <p><b>Target:</b> {@code com.mojang.blaze3d.vulkan.VulkanBackend}</p>
 *
 * <h3>Purpose:</h3>
 * <ul>
 *   <li>Log when Vulkan device is created (for debugging)</li>
 *   <li>Future: Hook into device creation for advanced features</li>
 * </ul>
 *
 * <h3>MC 26.2 createDevice() Signature:</h3>
 * <pre>
 * GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions)
 * </pre>
 *
 * @since 3.0.0
 */
@Mixin(VulkanBackend.class)
public abstract class MixinVulkanBackend {

    private static final Logger LOGGER = Logger.getLogger("Renderium-VulkanBackend");

    /**
     * Intercept Vulkan device creation.
     *
     * <p>Called when Minecraft creates the Vulkan device.
     * Logs device creation for debugging purposes.
     *
     * @param window            GLFW window handle
     * @param defaultShaderSource Default shader source for compilation
     * @param debugOptions      Debug options (validation layers, labels, etc.)
     * @param cir               Callback with created GpuDevice
     */
    @Inject(method = "createDevice",
            at = @At("RETURN"),
            remap = false)
    private void onDeviceCreated(long window,
                                  ShaderSource defaultShaderSource,
                                  GpuDebugOptions debugOptions,
                                  CallbackInfoReturnable<GpuDevice> cir) {
        GpuDevice device = cir.getReturnValue();

        if (device == null) {
            LOGGER.warning("[MixinVulkanBackend] createDevice() returned null!");
            return;
        }

        // Log successful device creation with useful info
        LOGGER.info("[MixinVulkanBackend] Vulkan device created successfully");
        LOGGER.fine("  Window handle: 0x" + Long.toHexString(window));
        LOGGER.fine("  Debug options: logLevel=" + debugOptions.logLevel() +
                     ", useLabels=" + debugOptions.useLabels() +
                     ", useValidation=" + debugOptions.useValidationLayers());

        // Future: Here we can hook into the device for:
        // - Custom memory allocator (VMA configuration)
        // - Additional extensions
        // - Performance monitoring hooks
        // - Streamline SDK initialization
    }
}
