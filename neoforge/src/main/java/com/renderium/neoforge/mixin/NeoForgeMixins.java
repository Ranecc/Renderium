// Renderium NeoForge - Complete Mixin Implementations
// High-performance: all major injection points with HookManager delegation

package com.renderium.neoforge.mixin;

import com.renderium.mixin.abstracts.HookManager;
import com.renderium.mixin.abstracts.hooks.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge CommandEncoder Mixin - batch submission and async transfer.
 */
@Mixin(targets = "com.mojang.blaze3d.systems.CommandEncoder")
public class NeoForgeCommandEncoderMixin {

    private static final CommandEncoderSubmitHook SUBMIT_HOOK =
        HookManager.getCommandEncoderSubmitHook();
    private static final CreateRenderPassHook RENDER_PASS_HOOK =
        HookManager.getCreateRenderPassHook();
    private static final WriteToBufferHook WRITE_HOOK =
        HookManager.getWriteToBufferHook();

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true, remap = false)
    private void onSubmit(Object commandBuffer, CallbackInfo ci) {
        if (SUBMIT_HOOK == null) return;
        try {
            if (SUBMIT_HOOK.onSubmit(commandBuffer)) ci.cancel();
        } catch (Exception e) {
            System.err.println("[Renderium-NF] Submit hook failed: " + e.getMessage());
        }
    }

    @Inject(method = "createRenderPass", at = @At("HEAD"), cancellable = true, remap = false)
    private void onCreateRenderPass(Object config, CallbackInfoReturnable<Object> ci) {
        if (RENDER_PASS_HOOK == null) return;
        try {
            Object result = RENDER_PASS_HOOK.onCreateRenderPass(config);
            if (result != null) ci.setReturnValue(result);
        } catch (Exception e) {
            System.err.println("[Renderium-NF] RenderPass hook failed: " + e.getMessage());
        }
    }

    @Inject(method = "writeToBuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private void onWriteToBuffer(Object buffer, Object data, long offset, int length, CallbackInfo ci) {
        if (WRITE_HOOK == null || length < 262144) return;
        try {
            if (WRITE_HOOK.onWriteToBuffer(buffer, data, offset, length)) ci.cancel();
        } catch (Exception e) {
            System.err.println("[Renderium-NF] Write hook failed: " + e.getMessage());
        }
    }
}

/**
 * NeoForge RenderPass Mixin - pipeline tracking and draw call merging.
 */
@Mixin(targets = "com.mojang.blaze3d.systems.RenderPass")
public class NeoForgeRenderPassMixin {

    private static final SetPipelineHook PIPELINE_HOOK = HookManager.getSetPipelineHook();
    private static final BindTextureHook TEXTURE_HOOK = HookManager.getBindTextureHook();
    private static final DrawIndexedHook DRAW_HOOK = HookManager.getDrawIndexedHook();
    private static final RenderPassCloseHook CLOSE_HOOK = HookManager.getRenderPassCloseHook();
    private static final ThreadLocal<Long> PASS_START_TIME = new ThreadLocal<>();

    @Inject(method = "setPipeline", at = @At("HEAD"), remap = false)
    private void onSetPipeline(Object pipeline, CallbackInfo ci) {
        if (PIPELINE_HOOK != null) {
            try { PIPELINE_HOOK.onSetPipeline(pipeline, this); } catch (Exception ignored) {}
        }
    }

    @Inject(method = "bindTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private void onBindTexture(Object texture, int unit, CallbackInfo ci) {
        if (TEXTURE_HOOK == null) return;
        try {
            if (TEXTURE_HOOK.onBindTexture(texture, unit)) ci.cancel();
        } catch (Exception e) {
            System.err.println("[Renderium-NF] Texture bind failed: " + e.getMessage());
        }
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"), cancellable = true, remap = false)
    private void onDrawIndexed(int indexCount, int instanceCount, int firstIndex,
                                int vertexOffset, int firstInstance, CallbackInfo ci) {
        if (DRAW_HOOK == null) return;
        try {
            if (DRAW_HOOK.onDrawIndexed(indexCount, instanceCount, firstIndex,
                                         vertexOffset, firstInstance)) ci.cancel();
        } catch (Exception e) {
            System.err.println("[Renderium-NF] Draw failed: " + e.getMessage());
        }
    }

    @Inject(method = "open", at = @At("TAIL"), remap = false)
    private void onOpen(CallbackInfo ci) {
        if (CLOSE_HOOK != null) PASS_START_TIME.set(System.nanoTime());
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void onClose(CallbackInfo ci) {
        if (CLOSE_HOOK == null) return;
        Long startTime = PASS_START_TIME.get();
        if (startTime == null) return;
        try {
            CLOSE_HOOK.onRenderPassClose(this, startTime);
        } catch (Exception e) {
            System.err.println("[Renderium-NF] Pass close failed: " + e.getMessage());
        } finally {
            PASS_START_TIME.remove();
        }
    }
}

/**
 * NeoForge LevelRenderer Mixin - dual-mode rendering and object pooling.
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public class NeoForgeLevelRendererMixin {

    private static final RenderLevelHook RENDER_HOOK = HookManager.getRenderLevelHook();
    private static final RenderChunkSectionHook CHUNK_HOOK = HookManager.getRenderChunkSectionHook();

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private void onRenderLevel(Object camera, Object frustum,
                               long startTimeNanos, boolean renderOutline,
                               CallbackInfo ci) {
        if (RENDER_HOOK == null) return;
        try {
            if (RENDER_HOOK.onRenderLevel(this, camera, frustum)) ci.cancel();
        } catch (Exception e) {
            System.err.println("[Renderium-NF] Level render failed: " + e.getMessage());
        }
    }

    @Inject(method = "renderChunkSection", at = @At("HEAD"), cancellable = true, remap = false)
    private void onRenderChunkSection(Object section, Object builder,
                                     CallbackInfoReturnable<Object> ci) {
        if (CHUNK_HOOK == null) return;
        try {
            Object pooled = CHUNK_HOOK.onRenderChunkSection(section, builder);
            if (pooled != null) ci.setReturnValue(pooled);
        } catch (Exception e) {
            System.err.println("[Renderium-NF] Chunk section failed: " + e.getMessage());
        }
    }
}

/**
 * NeoForge FrameGraphBuilder Mixin - execution optimization.
 */
@Mixin(targets = "com.mojang.blaze3d.framegraph.FrameGraphBuilder")
public class NeoForgeFrameGraphBuilderMixin {

    private static final FrameGraphExecuteHook EXECUTE_HOOK =
        HookManager.getFrameGraphExecuteHook();

    @ModifyVariable(method = "execute", at = @At("HEAD"), argsOnly = true, remap = false)
    private Object onModifyInspector(Object inspector) {
        if (EXECUTE_HOOK == null) return inspector;
        try {
            Object result = EXECUTE_HOOK.onExecute(this, inspector);
            return result != null ? result : inspector;
        } catch (Exception e) {
            System.err.println("[Renderium-NF] FG execute failed: " + e.getMessage());
            return inspector;
        }
    }
}

/**
 * NeoForge PostChain Mixin - Streamline integration.
 */
@Mixin(targets = "net.minecraft.client.renderer.PostChain")
public class NeoForgePostChainMixin {

    private static final PostChainHook POST_CHAIN_HOOK = HookManager.getPostChainHook();

    @Inject(method = "addToFrame", at = @At("HEAD"), cancellable = true, remap = false)
    private void onAddToFrame(Object frameData, CallbackInfo ci) {
        if (POST_CHAIN_HOOK == null) return;
        try {
            if (POST_CHAIN_HOOK.onAddToFrame(this, frameData)) ci.cancel();
        } catch (Exception e) {
            System.err.println("[Renderium-NF] PostChain failed: " + e.getMessage());
        }
    }
}
