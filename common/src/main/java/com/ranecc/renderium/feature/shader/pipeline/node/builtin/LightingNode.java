package com.ranecc.renderium.feature.shader.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.*;
import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

public class LightingNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(LightingNode.class.getName());

    private static final String SHADER_DIRECT = "/shaders/lighting_direct.spv";
    private static final String SHADER_INDIRECT = "/shaders/lighting_indirect.spv";
    private static final String SHADER_COMPOSITE = "/shaders/lighting_composite.spv";

    private volatile long pipelineDirect=0L, layoutDirect=0L, setDirect=0L;
    private volatile long pipelineIndirect=0L, layoutIndirect=0L, setIndirect=0L;
    private volatile long pipelineComposite=0L, layoutComposite=0L, setComposite=0L;

    public enum ShadowQuality {
        OFF,
        LOW,
        MEDIUM,
        HIGH,
        ULTRA,
        PCSS
    }

    public static final int DEFAULT_LIGHT_COUNT = 4;
    public static final int MIN_LIGHT_COUNT = 1;
    public static final int MAX_LIGHT_COUNT = 16;

    public static final int DEFAULT_GI_BOUNCES = 1;

    private volatile int lightCount = DEFAULT_LIGHT_COUNT;
    private volatile ShadowQuality shadowQuality = ShadowQuality.HIGH;
    private volatile int giBounces = DEFAULT_GI_BOUNCES;
    private volatile boolean enableAO = true;

    private long outputTextureHandle = 0L;
    private long directLightTexture = 0L;
    private long indirectLightTexture = 0L;

    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    public LightingNode() {
        super(
                "lighting",
                "Lighting (Direct + Indirect)",
                PipelineNode.Category.LIGHTING,
                110,
                new String[]{"gbuffer_geometry", "shadow_map"}
        );
        LOGGER.fine("LightingNode 已创建");
    }

    @Override
    protected boolean onInitialize(RenderContext context) {
        outputTextureHandle = allocateOutputTexture(1920, 1080);
        directLightTexture = allocateOutputTexture(1920, 1080);
        indirectLightTexture = allocateOutputTexture(1920, 1080);

        boolean ok = prepareShaderPrograms(context);
        if (!ok) return false;

        LOGGER.info(String.format("LightingNode 初始化完成: lights=%d, shadow=%s, giBounces=%d",
                lightCount, shadowQuality.name(), giBounces));
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        long startTimeNanos = System.nanoTime();
        if (inputResources == null || inputResources.length == 0) return 0L;

        int currentLights = this.lightCount;
        ShadowQuality currentShadow = this.shadowQuality;
        int currentGiBounces = this.giBounces;
        boolean currentAO = this.enableAO;

        long shadowMapInput = inputResources.length > 1 ? inputResources[1] : 0L;

        executeDirectLighting(context, inputResources[0], shadowMapInput, currentLights, currentShadow);

        if (currentAO || currentGiBounces > 0) {
            executeIndirectLighting(context, inputResources[0], currentAO, currentGiBounces);
        }

        submitFullScreenDraw(context, "lighting_composite",
                new long[]{directLightTexture, indirectLightTexture}, outputTextureHandle,
                new float[]{
                        currentAO ? 1.0f : 0.0f,
                        Math.min(currentGiBounces, 4)
                });

        totalExecuteTimeNanos += System.nanoTime() - startTimeNanos;
        totalFrames++;
        return outputTextureHandle;
    }

    private void executeDirectLighting(RenderContext context, long gBufferInput, long shadowMapInput,
                                        int lightCount, ShadowQuality shadowQuality) {
        int shadowMapSize = getShadowMapSize(shadowQuality);
        int pcfSamples = getPcfSampleCount(shadowQuality);

        submitFullScreenDraw(context, "lighting_direct",
                new long[]{gBufferInput, shadowMapInput}, directLightTexture,
                new float[]{
                        lightCount,
                        shadowMapSize,
                        pcfSamples,
                        shadowQuality == ShadowQuality.PCSS ? 1.0f : 0.0f,
                        1.0f
                });
    }

    private void executeIndirectLighting(RenderContext context, long gBufferInput,
                                          boolean enableAO, int giBounces) {
        float aoIntensity = enableAO ? 1.0f : 0.0f;

        submitFullScreenDraw(context, "lighting_indirect", gBufferInput, indirectLightTexture,
                new float[]{
                        aoIntensity,
                        giBounces,
                        0.5f,
                        1.0f
                });
    }

    @Override
    protected void onDispose() {
        releaseTexture(outputTextureHandle);
        releaseTexture(directLightTexture);
        releaseTexture(indirectLightTexture);
        outputTextureHandle = 0L;
        directLightTexture = 0L;
        indirectLightTexture = 0L;
        releaseShaderPrograms();

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L) {
            if (pipelineDirect != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, pipelineDirect, 0L); } catch (Throwable ignored) {} pipelineDirect = 0L; }
            if (pipelineIndirect != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, pipelineIndirect, 0L); } catch (Throwable ignored) {} pipelineIndirect = 0L; }
            if (pipelineComposite != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, pipelineComposite, 0L); } catch (Throwable ignored) {} pipelineComposite = 0L; }
            if (layoutDirect != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, layoutDirect, 0L); } catch (Throwable ignored) {} layoutDirect = 0L; }
            if (layoutIndirect != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, layoutIndirect, 0L); } catch (Throwable ignored) {} layoutIndirect = 0L; }
            if (layoutComposite != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, layoutComposite, 0L); } catch (Throwable ignored) {} layoutComposite = 0L; }
        }
        LOGGER.fine("[LightingNode] resources released");
    }

    // ==================== 配置 API ====================

    public void setLightCount(int v) { this.lightCount = clamp(v, MIN_LIGHT_COUNT, MAX_LIGHT_COUNT); }
    public int getLightCount() { return lightCount; }

    public void setShadowQuality(ShadowQuality q) { if (q != null) this.shadowQuality = q; }
    public ShadowQuality getShadowQuality() { return shadowQuality; }

    public void setGiBounces(int v) { this.giBounces = clamp(v, 0, 4); }
    public int getGiBounces() { return giBounces; }

    public void setEnableAO(boolean v) { this.enableAO = v; }
    public boolean isEnableAO() { return enableAO; }

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }
    public void resetStats() { totalExecuteTimeNanos = 0L; totalFrames = 0L; }

    @Override
    public String toString() {
        return String.format("Lighting{lights=%d, shadow=%s, giBounces=%d, ao=%b}",
                lightCount, shadowQuality.name(), giBounces, enableAO);
    }

    // ==================== 私有辅助方法 ====================

    private int getShadowMapSize(ShadowQuality q) {
        switch (q) {
            case LOW: return 512;
            case MEDIUM: return 1024;
            case HIGH: return 2048;
            case ULTRA: return 4096;
            default: return 0;
        }
    }

    private int getPcfSampleCount(ShadowQuality q) {
        switch (q) {
            case LOW: return 9;
            case MEDIUM: return 16;
            case HIGH: return 32;
            case ULTRA: return 64;
            default: return 0;
        }
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private long allocateOutputTexture(int w, int h) { return 0xBB040000L | ((long)(w&0xFFFF)<<16)|(long)(h&0xFFFF); }

    private void releaseTexture(long h) {
        if (!VulkanGraphicsHelper.isAvailable() || h == 0L) return;
        VulkanGraphicsHelper.destroyImageView(VulkanGraphicsHelper.getDevice(), h);
    }

    private boolean prepareShaderPrograms(RenderContext ctx) {
        if (!VulkanGraphicsHelper.isAvailable()) return true;
        ensurePipelines();
        return true;
    }

    private void releaseShaderPrograms() {
        if (!VulkanGraphicsHelper.isAvailable()) return;
    }

    // ==================== Compute Pipeline 生命周期 ====================

    private void ensurePipelines() {
        if (pipelineDirect != 0L && pipelineIndirect != 0L && pipelineComposite != 0L) return;
        synchronized (this) {
            if (pipelineDirect != 0L && pipelineIndirect != 0L && pipelineComposite != 0L) return;
            if (!VulkanFFMBinding.isFfmLoaded()) return;
            long device = VulkanDeviceHolder.getInstance().getDevice();
            if (device == 0L) return;

            try {
                byte[] spirvDirect = loadSPIRV(SHADER_DIRECT);
                byte[] spirvIndirect = loadSPIRV(SHADER_INDIRECT);
                byte[] spirvComposite = loadSPIRV(SHADER_COMPOSITE);

                if (spirvDirect != null && spirvDirect.length > 0) {
                    ComputePipelineHelper.PipelineResources res = ComputePipelineHelper.createComputePipeline(
                            spirvDirect,
                            new ComputePipelineHelper.Binding[]{
                                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(4, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(5, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            },
                            new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT));
                    if (res != null) {
                        pipelineDirect = res.pipeline(); layoutDirect = res.pipelineLayout(); setDirect = res.descriptorSet();
                    }
                }

                if (spirvIndirect != null && spirvIndirect.length > 0) {
                    ComputePipelineHelper.PipelineResources res = ComputePipelineHelper.createComputePipeline(
                            spirvIndirect,
                            new ComputePipelineHelper.Binding[]{
                                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(4, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            },
                            new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT));
                    if (res != null) {
                        pipelineIndirect = res.pipeline(); layoutIndirect = res.pipelineLayout(); setIndirect = res.descriptorSet();
                    }
                }

                if (spirvComposite != null && spirvComposite.length > 0) {
                    ComputePipelineHelper.PipelineResources res = ComputePipelineHelper.createComputePipeline(
                            spirvComposite,
                            new ComputePipelineHelper.Binding[]{
                                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            },
                            new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT));
                    if (res != null) {
                        pipelineComposite = res.pipeline(); layoutComposite = res.pipelineLayout(); setComposite = res.descriptorSet();
                    }
                }

                LOGGER.info(String.format("[LightingNode] pipelines: direct=0x%x indirect=0x%x composite=0x%x",
                        pipelineDirect, pipelineIndirect, pipelineComposite));
            } catch (Throwable t) {
                LOGGER.warning("[LightingNode] ensurePipelines 异常: " + t.getMessage());
            }
        }
    }

    private static byte[] loadSPIRV(String path) {
        try (var is = LightingNode.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            LOGGER.fine("[LightingNode] loadSPIRV 失败: " + path + " " + e.getMessage());
            return null;
        }
    }

    // ==================== Fullscreen Compute Dispatch ====================

    private void submitFullScreenDraw(RenderContext ctx, String pass, long inTex, long outTex, float[] uniforms) {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        ensurePipelines();
        long pipeline, layout, set;
        int bindingCount;
        if ("lighting_indirect".equals(pass)) {
            pipeline = pipelineIndirect; layout = layoutIndirect; set = setIndirect;
            bindingCount = 5;
        } else {
            LOGGER.warning("[LightingNode] unknown single-input pass: " + pass);
            return;
        }
        if (pipeline == 0L) return;

        int width = ctx.getWidth();
        int height = ctx.getHeight();
        if (width <= 0 || height <= 0) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;

        try {
            PerFrameArena.beginFrame();

            long commandPool = LodCullingComputePass.getCommandPool();
            if (commandPool == 0L) { PerFrameArena.endFrame(); return; }

            MemorySegment allocInfo = Arena.global().allocate(ValueLayout.JAVA_LONG, 5);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 46L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, commandPool);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 1L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);
            MemorySegment cmdOut = Arena.global().allocate(ValueLayout.JAVA_LONG);
            VulkanAPIRegistry.invoke("vkAllocateCommandBuffers", device, (long) allocInfo.address(), (long) cmdOut.address());
            long cmdBuf = cmdOut.get(ValueLayout.JAVA_LONG, 0);
            if (cmdBuf == 0L) { PerFrameArena.endFrame(); return; }

            MemorySegment beginInfo = Arena.global().allocate(ValueLayout.JAVA_LONG, 3);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            VulkanAPIRegistry.invoke("vkBeginCommandBuffer", cmdBuf, (long) beginInfo.address());

            MemorySegment barrier = Arena.global().allocate(68L);
            barrier.set(ValueLayout.JAVA_INT, 0, 33);
            barrier.set(ValueLayout.JAVA_LONG, 8, 0L);
            barrier.set(ValueLayout.JAVA_INT, 16, 0);
            barrier.set(ValueLayout.JAVA_INT, 20, 0);
            barrier.set(ValueLayout.JAVA_INT, 24, 0);
            barrier.set(ValueLayout.JAVA_INT, 28, 1);
            barrier.set(ValueLayout.JAVA_INT, 32, 0);
            barrier.set(ValueLayout.JAVA_INT, 36, 0);
            barrier.set(ValueLayout.JAVA_LONG, 40, outTex);
            barrier.set(ValueLayout.JAVA_INT, 48, VulkanConst.IMAGE_ASPECT_COLOR_BIT);
            barrier.set(ValueLayout.JAVA_INT, 52, 0);
            barrier.set(ValueLayout.JAVA_INT, 56, 1);
            barrier.set(ValueLayout.JAVA_INT, 60, 0);
            barrier.set(ValueLayout.JAVA_INT, 64, 1);
            VulkanAPIRegistry.invoke("vkCmdPipelineBarrier", cmdBuf,
                    1, 1, 0, 0, 0L, 0, 0L, 1, (long) barrier.address());

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                    VulkanConst.PIPELINE_BIND_POINT_COMPUTE, pipeline);

            ComputePipelineHelper.updateImageDescriptor(device, set, 0, inTex, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
            ComputePipelineHelper.updateImageDescriptor(device, set, 1, inTex, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
            ComputePipelineHelper.updateImageDescriptor(device, set, 2, inTex, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
            ComputePipelineHelper.updateImageDescriptor(device, set, 3, inTex, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
            ComputePipelineHelper.updateStorageImageDescriptor(device, set, 4, outTex, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);

            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                    VulkanConst.PIPELINE_BIND_POINT_COMPUTE, layout,
                    0, 1, set, 0, 0L);

            pushConstants(cmdBuf, layout, uniforms);

            int groupsX = Math.max(1, (width + 7) / 8);
            int groupsY = Math.max(1, (height + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);

            VulkanAPIRegistry.invoke("vkEndCommandBuffer", cmdBuf);

            long queue = VulkanDeviceHolder.getInstance().getComputeQueue();
            if (queue == 0L) queue = VulkanDeviceHolder.getInstance().getVkQueue();
            if (queue == 0L) {
                VulkanAPIRegistry.invoke("vkFreeCommandBuffers", device, commandPool, 1, cmdBuf);
                PerFrameArena.endFrame();
                return;
            }

            long fence = LodCullingComputePass.getFence();
            if (fence != 0L) {
                VulkanAPIRegistry.invoke("vkResetFences", device, 1, fence);
            } else {
                MemorySegment fenceCI = Arena.global().allocate(ValueLayout.JAVA_LONG, 3);
                fenceCI.setAtIndex(ValueLayout.JAVA_LONG, 0, 8L);
                fenceCI.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
                fenceCI.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
                MemorySegment fenceOut = Arena.global().allocate(ValueLayout.JAVA_LONG);
                VulkanAPIRegistry.invoke("vkCreateFence", device, (long) fenceCI.address(), 0L, (long) fenceOut.address());
                fence = fenceOut.get(ValueLayout.JAVA_LONG, 0);
            }

            MemorySegment submitInfo = Arena.global().allocate(ValueLayout.JAVA_LONG, 7);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 4L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 1L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, cmdBuf);
            VulkanAPIRegistry.invoke("vkQueueSubmit", queue, 1, (long) submitInfo.address(), fence);
            VulkanAPIRegistry.invoke("vkWaitForFences", device, 1, fence, 1L, 1000000000L);

            VulkanAPIRegistry.invoke("vkFreeCommandBuffers", device, commandPool, 1, cmdBuf);
            PerFrameArena.endFrame();

            LOGGER.finest("[LightingNode] " + pass + " dispatch OK " + width + "x" + height);
        } catch (Throwable t) {
            LOGGER.warning("[LightingNode] " + pass + " dispatch 异常: " + t.getMessage());
        }
    }

    private void submitFullScreenDraw(RenderContext ctx, String pass, long[] inTexes, long outTex, float[] uniforms) {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        ensurePipelines();
        long pipeline, layout, set;
        if ("lighting_direct".equals(pass)) {
            pipeline = pipelineDirect; layout = layoutDirect; set = setDirect;
        } else if ("lighting_composite".equals(pass)) {
            pipeline = pipelineComposite; layout = layoutComposite; set = setComposite;
        } else {
            LOGGER.warning("[LightingNode] unknown multi-input pass: " + pass);
            return;
        }
        if (pipeline == 0L) return;

        int width = ctx.getWidth();
        int height = ctx.getHeight();
        if (width <= 0 || height <= 0) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;

        try {
            PerFrameArena.beginFrame();

            long commandPool = LodCullingComputePass.getCommandPool();
            if (commandPool == 0L) { PerFrameArena.endFrame(); return; }

            MemorySegment allocInfo = Arena.global().allocate(ValueLayout.JAVA_LONG, 5);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 46L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, commandPool);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 1L);
            allocInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);
            MemorySegment cmdOut = Arena.global().allocate(ValueLayout.JAVA_LONG);
            VulkanAPIRegistry.invoke("vkAllocateCommandBuffers", device, (long) allocInfo.address(), (long) cmdOut.address());
            long cmdBuf = cmdOut.get(ValueLayout.JAVA_LONG, 0);
            if (cmdBuf == 0L) { PerFrameArena.endFrame(); return; }

            MemorySegment beginInfo = Arena.global().allocate(ValueLayout.JAVA_LONG, 3);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 42L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            beginInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            VulkanAPIRegistry.invoke("vkBeginCommandBuffer", cmdBuf, (long) beginInfo.address());

            MemorySegment barrier = Arena.global().allocate(68L);
            barrier.set(ValueLayout.JAVA_INT, 0, 33);
            barrier.set(ValueLayout.JAVA_LONG, 8, 0L);
            barrier.set(ValueLayout.JAVA_INT, 16, 0);
            barrier.set(ValueLayout.JAVA_INT, 20, 0);
            barrier.set(ValueLayout.JAVA_INT, 24, 0);
            barrier.set(ValueLayout.JAVA_INT, 28, 1);
            barrier.set(ValueLayout.JAVA_INT, 32, 0);
            barrier.set(ValueLayout.JAVA_INT, 36, 0);
            barrier.set(ValueLayout.JAVA_LONG, 40, outTex);
            barrier.set(ValueLayout.JAVA_INT, 48, VulkanConst.IMAGE_ASPECT_COLOR_BIT);
            barrier.set(ValueLayout.JAVA_INT, 52, 0);
            barrier.set(ValueLayout.JAVA_INT, 56, 1);
            barrier.set(ValueLayout.JAVA_INT, 60, 0);
            barrier.set(ValueLayout.JAVA_INT, 64, 1);
            VulkanAPIRegistry.invoke("vkCmdPipelineBarrier", cmdBuf,
                    1, 1, 0, 0, 0L, 0, 0L, 1, (long) barrier.address());

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                    VulkanConst.PIPELINE_BIND_POINT_COMPUTE, pipeline);

            if ("lighting_direct".equals(pass)) {
                long gBufferInput = inTexes.length > 0 ? inTexes[0] : 0L;
                long shadowMapTex = inTexes.length > 1 ? inTexes[1] : 0L;
                ComputePipelineHelper.updateImageDescriptor(device, set, 0, gBufferInput, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
                ComputePipelineHelper.updateImageDescriptor(device, set, 1, gBufferInput, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
                ComputePipelineHelper.updateImageDescriptor(device, set, 2, gBufferInput, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
                ComputePipelineHelper.updateImageDescriptor(device, set, 3, gBufferInput, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
                ComputePipelineHelper.updateImageDescriptor(device, set, 4, shadowMapTex, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
                ComputePipelineHelper.updateStorageImageDescriptor(device, set, 5, outTex, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
            } else {
                long inTex0 = inTexes.length > 0 ? inTexes[0] : 0L;
                long inTex1 = inTexes.length > 1 ? inTexes[1] : 0L;
                ComputePipelineHelper.updateImageDescriptor(device, set, 0, inTex0, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
                ComputePipelineHelper.updateImageDescriptor(device, set, 1, inTex1, 0L, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
                ComputePipelineHelper.updateStorageImageDescriptor(device, set, 2, outTex, ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
            }

            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                    VulkanConst.PIPELINE_BIND_POINT_COMPUTE, layout,
                    0, 1, set, 0, 0L);

            pushConstants(cmdBuf, layout, uniforms);

            int groupsX = Math.max(1, (width + 7) / 8);
            int groupsY = Math.max(1, (height + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);

            VulkanAPIRegistry.invoke("vkEndCommandBuffer", cmdBuf);

            long queue = VulkanDeviceHolder.getInstance().getComputeQueue();
            if (queue == 0L) queue = VulkanDeviceHolder.getInstance().getVkQueue();
            if (queue == 0L) {
                VulkanAPIRegistry.invoke("vkFreeCommandBuffers", device, commandPool, 1, cmdBuf);
                PerFrameArena.endFrame();
                return;
            }

            long fence = LodCullingComputePass.getFence();
            if (fence != 0L) {
                VulkanAPIRegistry.invoke("vkResetFences", device, 1, fence);
            } else {
                MemorySegment fenceCI = Arena.global().allocate(ValueLayout.JAVA_LONG, 3);
                fenceCI.setAtIndex(ValueLayout.JAVA_LONG, 0, 8L);
                fenceCI.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
                fenceCI.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
                MemorySegment fenceOut = Arena.global().allocate(ValueLayout.JAVA_LONG);
                VulkanAPIRegistry.invoke("vkCreateFence", device, (long) fenceCI.address(), 0L, (long) fenceOut.address());
                fence = fenceOut.get(ValueLayout.JAVA_LONG, 0);
            }

            MemorySegment submitInfo = Arena.global().allocate(ValueLayout.JAVA_LONG, 7);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 4L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 5, 1L);
            submitInfo.setAtIndex(ValueLayout.JAVA_LONG, 6, cmdBuf);
            VulkanAPIRegistry.invoke("vkQueueSubmit", queue, 1, (long) submitInfo.address(), fence);
            VulkanAPIRegistry.invoke("vkWaitForFences", device, 1, fence, 1L, 1000000000L);

            VulkanAPIRegistry.invoke("vkFreeCommandBuffers", device, commandPool, 1, cmdBuf);
            PerFrameArena.endFrame();

            LOGGER.finest("[LightingNode] " + pass + " dispatch OK " + width + "x" + height);
        } catch (Throwable t) {
            LOGGER.warning("[LightingNode] " + pass + " dispatch 异常: " + t.getMessage());
        }
    }

    private void pushConstants(long cmdBuf, long layout, float[] uniforms) {
        if (uniforms == null || uniforms.length == 0) return;
        try {
            MemorySegment pcData = Arena.global().allocate(32L);
            int count = Math.min(uniforms.length, 8);
            for (int i = 0; i < count; i++) {
                pcData.set(ValueLayout.JAVA_FLOAT, i * 4L, uniforms[i]);
            }
            VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, layout,
                    ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT,
                    0, 32, (long) pcData.address());
        } catch (Throwable t) {
            LOGGER.warning("[LightingNode] pushConstants 失败: " + t.getMessage());
        }
    }
}
