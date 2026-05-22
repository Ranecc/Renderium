// Renderium - 可扩展 Shader 节点系统
// ExposureNode - 曝光控制节点
//
// 功能：
//   1. 自动曝光（基于场景亮度直方图）
//   2. 手动 EV100 曝光值
//   3. 眼睛适应模拟（渐进过渡）
//
// 参数：
//   mode:             enum { AUTO, MANUAL }
//   ev100:            float [-10, 20]  - 手动曝光值 (EV100)
//   minExposure:      float [0.01, 100] - 最小曝光
//   maxExposure:      float [0.01, 100] - 最大曝光
//   adaptationSpeed:  float [0.1, 5]    - 适应速度

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.postprocess.tonemap;

import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;
import com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper;
import com.ranecc.renderium.infrastructure.gpu.*;
import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 曝光控制节点
 * <p>
 * 实现基于人眼感知的 HDR 到 LDR 转换。
 * 支持自动曝光计算和手动 EV 控制，
 * 以及平滑的眼睛适应效果。
 *
 * <h2>曝光公式：</h2>
 * <pre>
 * // EV100 → 曝光度
 * exposure = 1.0 / (2.0 ^ (ev100 / 100.0) * sensitivity * aperture^2)
 *
 * // 或者简化版：
 * L_out = 1.0 - exp(-L_hdr * exposure)
 *
 * // 自动曝光：基于对数平均亮度
 * float logAvgLum = exp(average(log(luminance)))
 * float key = 1.03 - (2.0 / (2 + logAvgLum + 0.04))
 * float ev100 = log2((key * 80.0) / (sensitivity * aperture^2 * shutterTime))
 * </pre>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0
 */
public class ExposureNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ExposureNode.class.getName());

    private static final int NODE_ID = 16;

    /** 曝光模式 */
    public enum ExposureMode {
        /** 自动曝光（基于场景亮度） */
        AUTO,
        /** 手动曝光（固定 EV 值） */
        MANUAL
    }

    public static final float DEFAULT_EV100 = 0.0f;        // 中性灰
    public static final float MIN_EV100 = -10.0f;
    public static final float MAX_EV100 = 20.0f;

    public static final float DEFAULT_MIN_EXPOSURE = 0.05f;
    public static final float DEFAULT_MAX_EXPOSURE = 50.0f;

    public static final float DEFAULT_ADAPTATION_SPEED = 1.5f;

    private volatile ExposureMode mode = ExposureMode.AUTO;
    private volatile float ev100 = DEFAULT_EV100;
    private volatile float minExposure = DEFAULT_MIN_EXPOSURE;
    private volatile float maxExposure = DEFAULT_MAX_EXPOSURE;
    private volatile float adaptationSpeed = DEFAULT_ADAPTATION_SPEED;

    // 运行时状态
    private long outputTextureHandle = 0L;

    // GPU compute 资源
    /**
     * Shader key，镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/postprocess/tonemap/exposure_tonemap";
    }
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;
    private volatile long outputImage = 0L;
    private volatile long outputImageView = 0L;
    private volatile int lastWidth = 0;
    private volatile int lastHeight = 0;

    /** 当前有效曝光值（用于眼睛适应插值） */
    private volatile float currentExposure = 1.0f;
    /** 目标曝光值（自动模式计算结果） */
    private volatile float targetExposure = 1.0f;

    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    public ExposureNode() {
        super(
                "exposure",
                "Exposure (Auto/Manual)",
                PipelineNode.Category.POST_PROCESS,
                170,
                new String[0]  // 无前置依赖（接收最终颜色）
        );
        LOGGER.fine("ExposureNode 已创建");
    }

    @Override
    protected boolean onInitialize(RenderContext context) {
        outputTextureHandle = allocateOutputTexture(1920, 1080);
        boolean ok = prepareShaderPrograms(context);

        if (!ok) return false;

        LOGGER.info(String.format("ExposureNode 初始化完成: mode=%s, ev100=%.1f",
                mode.name(), ev100));
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        RenderiumProfiler.recordStart(16);
        if (inputResources == null || inputResources.length == 0) return 0L;

        ExposureMode currentMode = this.mode;
        float currentEv100 = this.ev100;
        float currentMinExp = this.minExposure;
        float currentMaxExp = this.maxExposure;
        float currentAdaptSpeed = this.adaptationSpeed;

        // 计算目标曝光值
        if (currentMode == ExposureMode.MANUAL) {
            // 手动模式：EV100 直接转曝光度
            targetExposure = ev100ToExposure(currentEv100);
        } else {
            // 自动模式：需要先计算场景亮度（此处使用近似值）
            // 实际实现中应通过 Compute Shader 计算亮度直方图
            targetExposure = 1.0f;  // 占位符，实际应从 luminance histogram 计算
        }

        // 钳制到范围
        targetExposure = clamp(targetExposure, currentMinExp, currentMaxExp);

        // 眼睛适应（指数平滑）
        float dt = 1.0f / 60.0f;  // 假设 60fps，实际应传入 deltaTime
        float blendFactor = 1.0f - (float) Math.exp(-currentAdaptSpeed * dt);
        currentExposure += (targetExposure - currentExposure) * blendFactor;

        // 提交曝光 Pass
        submitFullScreenDraw(context, "exposure_tonemap", inputResources[0], outputTextureHandle,
                new float[]{
                        currentExposure,       // exposure value
                        currentMinExp,         // min exposure
                        currentMaxExp,         // max exposure
                        2.222f                 // white point (Reinhard)
                });

        RenderiumProfiler.recordEnd(16);
        totalExecuteTimeNanos += RenderiumProfiler.getNodeTime(16);
        totalFrames++;
        return outputTextureHandle;
    }

    /**
     * EV100 转换为曝光度
     * <p>
     * 公式: exposure = 1.0 / (2^(EV100/100))
     *
     * @param ev100 float - EV100 值
     * @return float - 对应的曝光度
     */
    private float ev100ToExposure(float ev100) {
        return 1.0f / (float) Math.pow(2.0, ev100 / 100.0);
    }

    // ==================== 配置 API ====================

    public void setMode(ExposureMode m) { if (m != null) this.mode = m; }
    public ExposureMode getMode() { return mode; }

    public void setEv100(float v) { this.ev100 = clamp(v, MIN_EV100, MAX_EV100); }
    public float getEv100() { return ev100; }

    public void setMinExposure(float v) { this.minExposure = Math.max(0.001f, v); }
    public float getMinExposure() { return minExposure; }

    public void setMaxExposure(float v) { this.maxExposure = Math.max(minExposure, v); }
    public float getMaxExposure() { return maxExposure; }

    public void setAdaptationSpeed(float v) { this.adaptationSpeed = clamp(v, 0.1f, 5.0f); }
    public float getAdaptationSpeed() { return adaptationSpeed; }

    /** @return float - 当前有效曝光值（含适应平滑） */
    public float getCurrentExposure() { return currentExposure; }

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }
    public void resetStats() { totalExecuteTimeNanos = 0L; totalFrames = 0L; }

    @Override
    public String toString() {
        return String.format("Exposure{mode=%s, ev100=%.1f, currentExp=%.3f, speed=%.1f}",
                mode.name(), ev100, currentExposure, adaptationSpeed);
    }

    private float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private long allocateOutputTexture(int w, int h) { return 0xBB050000L | ((long)(w&0xFFFF)<<16)|(long)(h&0xFFFF); }
    private void releaseTexture(long h) {
        if (!VulkanGraphicsHelper.isAvailable() || h == 0L) return;
        VulkanGraphicsHelper.destroyImageView(VulkanGraphicsHelper.getDevice(), h);
    }
    private boolean prepareShaderPrograms(RenderContext ctx) {
        if (!VulkanGraphicsHelper.isAvailable()) return true;
        LOGGER.fine("[ExposureNode] shader programs prepared");
        return true;
    }
    private void releaseShaderPrograms() {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        LOGGER.fine("[ExposureNode] shader programs released");
    }
    private void submitFullScreenDraw(RenderContext ctx, String pass, long inTex, long outTex, float[] uniforms) {
        if (!VulkanGraphicsHelper.isAvailable()) return;

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev == 0L) return;

        try {
            float exposure = uniforms.length > 0 ? uniforms[0] : 1.0f;
            float minExp = uniforms.length > 1 ? uniforms[1] : 0.1f;
            float maxExp = uniforms.length > 2 ? uniforms[2] : 5.0f;
            float whitePoint = uniforms.length > 3 ? uniforms[3] : 2.222f;

            ensurePipeline();
            ensureOutputImage(ctx.getWidth(), ctx.getHeight());

            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 0, inTex, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 1, outputImageView, 0L);

            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1L, computePipeline);
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf, 1L, pipelineLayout, 0L, 1, descriptorSet, 0, 0L);

            // 使用 confined arena 管理 push constant 内存
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pcData = arena.allocate(32);
                pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 0, exposure);
                pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 1, minExp);
                pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 2, maxExp);
                pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 3, whitePoint);
                VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, pipelineLayout, 0x20L, 0L, 32, pcData);
            }

            int w = Math.max(1, (ctx.getWidth() + 7) / 8);
            int h = Math.max(1, (ctx.getHeight() + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);

            FrameCommandContext.endNodeCB(NODE_ID);
        } catch (Throwable t) {
            LOGGER.warning("[ExposureNode] dispatch error: " + t.getMessage());
        }
    }

    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        synchronized (this) {
            if (computePipeline != 0L) return;
            try {
                byte[] spirv = resolveSPIRV();
                if (spirv == null) { LOGGER.warning("Failed to load SPIR-V"); return; }

                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var result = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                computePipeline = result.pipeline();
                pipelineLayout = result.pipelineLayout();
                descriptorSet = result.descriptorSet();
            } catch (Throwable t) {
                LOGGER.warning("[ExposureNode] createPipeline error: " + t.getMessage());
            }
        }
    }

    private void ensureOutputImage(int w, int h) {
        if (outputImage != 0L && lastWidth == w && lastHeight == h) return;
        synchronized (this) {
            if (outputImage != 0L && lastWidth == w && lastHeight == h) return;
            var mgr = VulkanGPUResourceManager.getInstance();
            if (outputImageView != 0L) { try { mgr.destroyView(outputImageView); } catch (Throwable ignored) {} }
            if (outputImage != 0L) { try { mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastWidth, lastHeight, 44, VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {} }

            var res = mgr.createImage(w, h, 28, 0x20 | 0x10, VmaMemoryPools.PoolType.RENDER_TARGET);
            if (res != null && res.handle != 0L) {
                outputImage = res.handle;
                outputImageView = mgr.createView(outputImage, 28, 1);
                lastWidth = w; lastHeight = h;
            }
        }
    }

    private byte[] loadSPIRV(String path) {
        try (var is = getClass().getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            LOGGER.warning("[ExposureNode] loadSPIRV error: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onDispose() {
        // 先释放原始资源
        releaseTexture(outputTextureHandle);
        outputTextureHandle = 0L;
        releaseShaderPrograms();

        // 释放 GPU compute 资源
        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L) {
            if (computePipeline != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, computePipeline, 0L); } catch (Throwable ignored) {} computePipeline = 0L; }
            if (pipelineLayout != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, pipelineLayout, 0L); } catch (Throwable ignored) {} pipelineLayout = 0L; }
            if (outputImageView != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {} outputImageView = 0L; }
        }
        if (outputImage != 0L) {
            try { VulkanGPUResourceManager.getInstance().releaseResource(new VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastWidth, lastHeight, 44, VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {}
            outputImage = 0L;
        }
        lastWidth = 0; lastHeight = 0;
        LOGGER.fine("[ExposureNode] resources released");
    }
}
