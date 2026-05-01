// Renderium - Centralized Mixin Hook Manager
// Single entry point for all platform-specific Mixin implementations
// High-performance: static final fields, no virtual dispatch on hot paths

package com.ranecc.renderium.platform.hook;

import com.renderium.mixin.abstracts.hooks.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central manager for all Mixin hook implementations.
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Singleton pattern with eager initialization</li>
 *   <li>All hook fields are {@code volatile} for thread-safe publication</li>
 *   <li>Null-check pattern allows graceful degradation when hooks not set</li>
 *   <li>Platform modules (Fabric/NeoForge) register their implementations here</li>
 * </ul>
 *
 * <h3>Performance Characteristics:</h3>
 * <ul>
 *   <li>Hook invocation: 1 null-check + 1 interface call (~2-5ns)</li>
 *   <li>No hook registered: 1 null-check (~0.5ns) - effectively free</li>
 *   <li>JIT will inline the null-check and potentially the hook call</li>
 * </ul>
 *
 * <h3>Usage in Mixin (Fabric example):</h3>
 * <pre>{@code
 * @Mixin(GpuDevice.class)
 * public class GpuDeviceMixin {
 *     @Inject(method = "createBuffer", at = @At("HEAD"), cancellable = true)
 *     private void onCreateBuffer(Supplier<String> label, int usage, long size,
 *                                  CallbackInfoReturnable<GpuBuffer> ci) {
 *         var hook = HookManager.getGpuDeviceBufferHook();
 *         if (hook != null) {
 *             Object result = hook.onCreateBuffer(label, usage, size);
 *             if (result != null) {
 *                 ci.setReturnValue(result);
 *             }
 *         }
 *     }
 * }
 * }</pre>
 *
 * @since 3.0.0
 */
public final class HookManager {

    /** Prevent instantiation */
    private HookManager() {}

    /** Initialization flag */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // ==================== GpuDevice Hooks ====================

    private static volatile GpuDeviceBufferHook gpuDeviceBufferHook;

    public static GpuDeviceBufferHook getGpuDeviceBufferHook() {
        return gpuDeviceBufferHook;
    }

    public static void setGpuDeviceBufferHook(GpuDeviceBufferHook hook) {
        gpuDeviceBufferHook = hook;
    }

    // ==================== CommandEncoder Hooks ====================

    private static volatile CommandEncoderSubmitHook commandEncoderSubmitHook;
    private static volatile CreateRenderPassHook createRenderPassHook;
    private static volatile WriteToBufferHook writeToBufferHook;

    public static CommandEncoderSubmitHook getCommandEncoderSubmitHook() {
        return commandEncoderSubmitHook;
    }

    public static void setCommandEncoderSubmitHook(CommandEncoderSubmitHook hook) {
        commandEncoderSubmitHook = hook;
    }

    public static CreateRenderPassHook getCreateRenderPassHook() {
        return createRenderPassHook;
    }

    public static void setCreateRenderPassHook(CreateRenderPassHook hook) {
        createRenderPassHook = hook;
    }

    public static WriteToBufferHook getWriteToBufferHook() {
        return writeToBufferHook;
    }

    public static void setWriteToBufferHook(WriteToBufferHook hook) {
        writeToBufferHook = hook;
    }

    // ==================== RenderPass Hooks ====================

    private static volatile SetPipelineHook setPipelineHook;
    private static volatile BindTextureHook bindTextureHook;
    private static volatile DrawIndexedHook drawIndexedHook;
    private static volatile RenderPassCloseHook renderPassCloseHook;

    public static SetPipelineHook getSetPipelineHook() { return setPipelineHook; }
    public static void SetSetPipelineHook(SetPipelineHook hook) { setPipelineHook = hook; }

    public static BindTextureHook getBindTextureHook() { return bindTextureHook; }
    public static void SetBindTextureHook(BindTextureHook hook) { bindTextureHook = hook; }

    public static DrawIndexedHook getDrawIndexedHook() { return drawIndexedHook; }
    public static void SetDrawIndexedHook(DrawIndexedHook hook) { drawIndexedHook = hook; }

    public static RenderPassCloseHook getRenderPassCloseHook() { return renderPassCloseHook; }
    public static void SetRenderPassCloseHook(RenderPassCloseHook hook) { renderPassCloseHook = hook; }

    // ==================== LevelRenderer Hooks ====================

    private static volatile RenderLevelHook renderLevelHook;
    private static volatile RenderChunkSectionHook renderChunkSectionHook;
    private static volatile PostChainHook postChainHook;

    public static RenderLevelHook getRenderLevelHook() { return renderLevelHook; }
    public static void SetRenderLevelHook(RenderLevelHook hook) { renderLevelHook = hook; }

    public static RenderChunkSectionHook getRenderChunkSectionHook() { return renderChunkSectionHook; }
    public static void SetRenderChunkSectionHook(RenderChunkSectionHook hook) { renderChunkSectionHook = hook; }

    public static PostChainHook getPostChainHook() { return postChainHook; }
    public static void SetPostChainHook(PostChainHook hook) { postChainHook = hook; }

    // ==================== ChunkRenderDispatcher Hook (P1-5: MC 数据源接入) ====================

    private static volatile ChunkRenderDispatcherHook chunkDispatcherHook;

    /**
     * 获取 ChunkRenderDispatcher 拦截 Hook
     * <p>
     * 用于从 Minecraft 渲染管线中提取地形网格数据。
     * 这是 GBufferGeometryNode / ShadowMapNode / Hi-Z Builder 的数据源。
     *
     * @return ChunkRenderDispatcherHook - Hook 实例或 null
     */
    public static ChunkRenderDispatcherHook getChunkDispatcherHook() {
        return chunkDispatcherHook;
    }

    /**
     * 设置 ChunkRenderDispatcher 拦截 Hook
     *
     * @param hook Hook 实现对象（通常由平台模块注册）
     */
    public static void setChunkDispatcherHook(ChunkRenderDispatcherHook hook) {
        chunkDispatcherHook = hook;
    }

    // ==================== FrameGraph Hooks ====================

    private static volatile FrameGraphExecuteHook frameGraphExecuteHook;

    public static FrameGraphExecuteHook getFrameGraphExecuteHook() { return frameGraphExecuteHook; }
    public static void SetFrameGraphExecuteHook(FrameGraphExecuteHook hook) { frameGraphExecuteHook = hook; }

    // ==================== Aggressive Mode Hooks (Optional) ====================

    private static volatile AsyncUploadHook asyncUploadHook;
    private static volatile VertexFormatCompressionHook vertexFormatCompressionHook;

    public static AsyncUploadHook getAsyncUploadHook() { return asyncUploadHook; }
    public static void SetAsyncUploadHook(AsyncUploadHook hook) { asyncUploadHook = hook; }

    public static VertexFormatCompressionHook getVertexFormatCompressionHook() { return vertexFormatCompressionHook; }
    public static void SetVertexFormatCompressionHook(VertexFormatCompressionHook hook) { vertexFormatCompressionHook = hook; }

    // ==================== Lifecycle Management ====================

    /**
     * Initialize HookManager with default implementations.
     *
     * <p>Called during module load phase. Sets up all hooks to point to
     * their default implementations that delegate to OptimizerRegistry.</p>
     */
    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return; // Already initialized
        }

        // Register default implementations that use OptimizerRegistry
        setGpuDeviceBufferHook(new GpuDeviceBufferHook.Default());

        // Log initialization
        System.out.println("[Renderium] HookManager initialized successfully");
    }

    /**
     * Reset all hooks to null (for testing or module unload).
     */
    public static void reset() {
        gpuDeviceBufferHook = null;
        commandEncoderSubmitHook = null;
        createRenderPassHook = null;
        writeToBufferHook = null;
        setPipelineHook = null;
        bindTextureHook = null;
        drawIndexedHook = null;
        renderPassCloseHook = null;
        renderLevelHook = null;
        renderChunkSectionHook = null;
        postChainHook = null;
        frameGraphExecuteHook = null;
        asyncUploadHook = null;
        vertexFormatCompressionHook = null;
        initialized.set(false);
    }

    /**
     * Check if HookManager has been initialized.
     */
    public static boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Get count of registered hooks (for diagnostics).
     */
    public static int getRegisteredHookCount() {
        int count = 0;
        if (gpuDeviceBufferHook != null) count++;
        if (commandEncoderSubmitHook != null) count++;
        if (createRenderPassHook != null) count++;
        if (writeToBufferHook != null) count++;
        if (setPipelineHook != null) count++;
        if (bindTextureHook != null) count++;
        if (drawIndexedHook != null) count++;
        if (renderPassCloseHook != null) count++;
        if (renderLevelHook != null) count++;
        if (renderChunkSectionHook != null) count++;
        if (postChainHook != null) count++;
        if (frameGraphExecuteHook != null) count++;
        if (asyncUploadHook != null) count++;
        if (vertexFormatCompressionHook != null) count++;
        return count;
    }
}
