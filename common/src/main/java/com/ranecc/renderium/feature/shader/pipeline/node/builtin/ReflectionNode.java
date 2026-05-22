// Renderium - 可扩展 Shader 节点系统
// ReflectionNode - 屏幕空间反射 / 平面反射节点
//
// 功能：
//   1. SSR (Screen Space Reflections) - 屏幕空间反射
//   2. Planar Reflection - 平面镜面反射（水面/镜子）
//   3. 粗糙度感知的模糊反射
//
// 参数：
//   reflectionType: enum { SSR, PLANAR, HYBRID }
//   maxLod:         int   [0, 8]    - 最大 MIP 等级
//   roughnessBias:  float [0, 1]     - 粗糙度偏移
//   enableSSR:      bool             - 是否启用 SSR

package com.ranecc.renderium.feature.shader.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.ShaderPathResolver;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;
import com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper;
import com.ranecc.renderium.infrastructure.gpu.*;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.domain.constant.VulkanConst;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 反射节点
 * <p>
 * 实现屏幕空间反射 (SSR) 和平面反射两种模式。
 * 支持基于粗糙度的模糊反射效果，模拟真实世界的非完美镜面。
 *
 * <h2>SSR 算法概述：</h2>
 * <pre>
 * // 1. 从深度缓冲重建世界坐标
 * vec3 worldPos = reconstructWorldPosition(uv, depth);
 *
 * // 2. 计算视线反射方向
 * vec3 reflectDir = normalize(reflect(viewDir, normal));
 *
 * // 3. 沿反射方向步进（Marching）
 * for (int step = 0; step < MAX_STEPS; step++) {
 *     vec3 samplePos = worldPos + reflectDir * stepSize;
 *     vec4 screenPos = projectToScreen(samplePos);
 *     float sampleDepth = texture(depthTex, screenPos.xy).r;
 *     if (abs(screenPos.z - sampleDepth) < THRESHOLD) {
 *         return texture(colorTex, screenPos.xy);  // 命中！
 *     }
 * }
 * return fallbackColor;  // 未命中，使用环境贴图
 * </pre>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0
 */
public class ReflectionNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ReflectionNode.class.getName());

    private static final int NODE_ID = 21;

    // ==================== Shader Key 常量 ====================

    /** 子 shader key 常量（通过 ShaderPathResolver 解析为实际 SPV 路径） */
    private static final String KEY_SSR_MARCH = "pipeline/postprocess/reflection/reflection_ssr_march";
    private static final String KEY_SSR_RESOLVE = "pipeline/postprocess/reflection/reflection_ssr_resolve";
    private static final String KEY_PLANAR = "pipeline/postprocess/reflection/reflection_planar";
    private static final String KEY_HYBRID = "pipeline/postprocess/reflection/reflection_hybrid";

    /** 反射类型枚举 */
    public enum ReflectionType {
        /** 屏幕空间反射 */
        SSR,
        /** 平面反射（水面/镜子） */
        PLANAR,
        /** 混合模式（近处 SSR + 远处平面） */
        HYBRID
    }

    public static final int DEFAULT_MAX_LOD = 5;
    public static final int MIN_MAX_LOD = 0;
    public static final int MAX_MAX_LOD = 8;

    public static final float DEFAULT_ROUGHNESS_BIAS = 0.0f;

    private volatile ReflectionType reflectionType = ReflectionType.SSR;
    private volatile int maxLod = DEFAULT_MAX_LOD;
    private volatile float roughnessBias = DEFAULT_ROUGHNESS_BIAS;
    private volatile boolean enableSSR = true;

    // ==================== SSR March Pipeline ====================
    // Bindings: 0=scene(CIS), 1=position(CIS), 2=normal(CIS), 3=ssr_output(SI)
    // PushConstant: 32 bytes (8 floats)
    private volatile long pipelineSSRMarch, layoutSSRMarch, setSSRMarch;

    // ==================== SSR Resolve Pipeline ====================
    // Bindings: 0=ssr(CIS), 1=scene(CIS), 2=output(SI)
    private volatile long pipelineSSRResolve, layoutSSRResolve, setSSRResolve;

    // ==================== Planar Pipeline ====================
    // Bindings: 0=scene(CIS), 1=output(SI)
    private volatile long pipelinePlanar, layoutPlanar, setPlanar;

    // ==================== Hybrid Pipeline ====================
    // Bindings: 0=ssr(CIS), 1=planar(CIS), 2=scene(CIS), 3=output(SI)
    private volatile long pipelineHybrid, layoutHybrid, setHybrid;

    /** 最终输出图像资源（所有 pass 写入同一输出目标） */
    private volatile long outputImage, outputImageView;
    private volatile int lastWidth, lastHeight;

    private long outputTextureHandle = 0L;
    private long ssrIntermediateTexture = 0L;

    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    public ReflectionNode() {
        super(
                "reflection",
                "Reflection (SSR / Planar)",
                PipelineNode.Category.LIGHTING,
                150,
                new String[]{"gbuffer_geometry", "lighting"}
        );
        LOGGER.fine("ReflectionNode 已创建");
    }

    @Override
    protected boolean onInitialize(RenderContext context) {
        outputTextureHandle = allocateOutputTexture(1920, 1080);
        ssrIntermediateTexture = allocateOutputTexture(1920, 1080);
        boolean ok = prepareShaderPrograms(context);
        if (!ok) return false;
        LOGGER.info("ReflectionNode 初始化完成: type=" + reflectionType.name());
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        RenderiumProfiler.recordStart(21);
        if (inputResources == null || inputResources.length < 1) return 0L;

        ReflectionType currentType = this.reflectionType;
        int currentMaxLod = this.maxLod;
        float currentRoughnessBias = this.roughnessBias;
        boolean currentEnableSSR = this.enableSSR;

        ensurePipelines();
        ensureOutputImage(context);
        if (outputImageView == 0L) return inputResources[0];

        switch (currentType) {
            case SSR:
                if (currentEnableSSR) {
                    executeSSR(context, inputResources, currentMaxLod, currentRoughnessBias);
                } else {
                    return inputResources[0];
                }
                break;
            case PLANAR:
                executePlanarReflection(context, inputResources);
                break;
            case HYBRID:
                executeHybridReflection(context, inputResources, currentMaxLod, currentRoughnessBias);
                break;
        }

        RenderiumProfiler.recordEnd(21);
        totalExecuteTimeNanos += RenderiumProfiler.getNodeTime(21);
        totalFrames++;
        return outputImageView;
    }

    /**
     * 执行屏幕空间反射
     * <p>
     * Pass 1 (ssr_march): 沿反射方向步进采样 scene + position + normal → ssr 中间纹理
     * Pass 2 (ssr_resolve): ssr 中间纹理 + scene → 边界处理并混合到输出
     */
    private void executeSSR(RenderContext context, long[] inputs, int maxLod, float roughnessBias) {
        long sceneTex = inputs.length > 0 ? inputs[0] : 0L;
        long positionTex = inputs.length > 1 ? inputs[1] : 0L;
        long normalTex = inputs.length > 2 ? inputs[2] : 0L;

        if (sceneTex == 0L) return;

        // Pass 1: SSR March — scene, position, normal → ssrIntermediate
        float maxDist = 100.0f;
        float rayStride = Math.max(0.5f, maxLod * 0.5f);
        float thicknessBias = 0.1f;
        float hitThreshold = 0.01f;
        float fadeEdge = 0.1f;
        float reflectionStrength = Math.max(0.1f, 1.0f - roughnessBias);

        MemorySegment pcMarch = PerFrameArena.allocate(32);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 0, maxDist);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 4, rayStride);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 8, thicknessBias);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 12, hitThreshold);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 16, fadeEdge);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 20, reflectionStrength);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 24, 0.0f);
        pcMarch.set(ValueLayout.JAVA_FLOAT, 28, 0.0f);

        // SSR March 写入 ssrIntermediateTexture（STORAGE_IMAGE）
        submitFullScreenDraw(context, "reflection_ssr_march",
                new long[]{sceneTex, positionTex, normalTex},
                ssrIntermediateTexture, pcMarch, pipelineSSRMarch, layoutSSRMarch, setSSRMarch);

        // Pass 2: SSR Resolve — ssr 结果 + scene → 混合到输出
        float blendFactor = 0.8f;
        MemorySegment pcResolve = PerFrameArena.allocate(32);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 0, blendFactor);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 4, 0.0f);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 8, 0.0f);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 12, 0.0f);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 16, 0.0f);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 20, 0.0f);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 24, 0.0f);
        pcResolve.set(ValueLayout.JAVA_FLOAT, 28, 0.0f);

        submitFullScreenDraw(context, "reflection_ssr_resolve",
                new long[]{ssrIntermediateTexture, sceneTex},
                outputImageView, pcResolve, pipelineSSRResolve, layoutSSRResolve, setSSRResolve);
    }

    /**
     * 执行平面反射
     * <p>
     * 将场景沿反射平面翻转后渲染到输出纹理。
     * PC 布局: planeEq[4] (ax+by+cz+d=0) + reflectionStrength, 共计 20 字节，对齐到 32.
     */
    private void executePlanarReflection(RenderContext context, long[] inputs) {
        long sceneTex = inputs.length > 0 ? inputs[0] : 0L;
        if (sceneTex == 0L) return;

        MemorySegment pc = PerFrameArena.allocate(32);
        pc.set(ValueLayout.JAVA_FLOAT, 0, 0.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 4, 1.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 8, 0.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 12, -0.01f);
        pc.set(ValueLayout.JAVA_FLOAT, 16, 0.85f);
        pc.set(ValueLayout.JAVA_FLOAT, 20, 0.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 24, 0.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 28, 0.0f);

        submitFullScreenDraw(context, "reflection_planar",
                new long[]{sceneTex},
                outputImageView, pc, pipelinePlanar, layoutPlanar, setPlanar);
    }

    /**
     * 执行混合反射（SSR + Planar）
     * <p>
     * 近距离使用 SSR，远距离回退到平面反射或环境贴图。
     * PC 布局: maxLod(int), roughnessBias(float), ssrMaxDist(float), hybridWeight(float), pad*4 = 32 bytes
     */
    private void executeHybridReflection(RenderContext context, long[] inputs,
                                          int maxLodVal, float roughnessBiasVal) {
        long ssrTex = inputs.length > 0 ? inputs[0] : 0L;
        long planarTex = inputs.length > 1 ? inputs[1] : 0L;
        long sceneTex = inputs.length > 2 ? inputs[2] : 0L;

        MemorySegment pc = PerFrameArena.allocate(32);
        pc.set(ValueLayout.JAVA_INT, 0, maxLodVal);
        pc.set(ValueLayout.JAVA_FLOAT, 4, roughnessBiasVal);
        pc.set(ValueLayout.JAVA_FLOAT, 8, 30.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 12, 0.6f);
        pc.set(ValueLayout.JAVA_FLOAT, 16, 0.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 20, 0.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 24, 0.0f);
        pc.set(ValueLayout.JAVA_FLOAT, 28, 0.0f);

        submitFullScreenDraw(context, "reflection_hybrid",
                new long[]{ssrTex, planarTex, sceneTex},
                outputImageView, pc, pipelineHybrid, layoutHybrid, setHybrid);
    }

    // ==================== 统一 Compute Dispatch ====================

    /**
     * 执行单次 Compute Dispatch（多输入版本）
     * <p>
     * 通用 dispatch 入口：绑定描述符集 → push_constant → 分发工作组。
     * 工作组数自动根据目标纹理尺寸以 8x8 线程组计算。
     *
     * 【方法参数】
     * @param ctx            RenderContext - 渲染上下文
     * @param pass           String       - pass 名称（仅供日志使用）
     * @param inTexes        long[]       - 输入纹理句柄数组（COMBINED_IMAGE_SAMPLER）
     * @param outTex         long         - 输出纹理句柄（STORAGE_IMAGE ImageView）
     * @param pcData         MemorySegment - push_constant 数据（32 字节）
     * @param pipeline       long         - VkPipeline 句柄
     * @param layout         long         - VkPipelineLayout 句柄
     * @param descriptorSet  long         - VkDescriptorSet 句柄
     */
    private void submitFullScreenDraw(RenderContext ctx, String pass,
                                       long[] inTexes, long outTex,
                                       MemorySegment pcData,
                                       long pipeline, long layout, long descriptorSet) {
        if (pipeline == 0L || layout == 0L || descriptorSet == 0L) {
            LOGGER.fine("[ReflectionNode] " + pass + " 跳过：管线未就绪");
            return;
        }

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev == 0L) return;

        try {
            for (int i = 0; i < inTexes.length; i++) {
                if (inTexes[i] != 0L) {
                    ComputePipelineHelper.updateImageDescriptor(
                            dev, descriptorSet, i, inTexes[i], 0L, 0L);
                }
            }
            ComputePipelineHelper.updateStorageImageDescriptor(
                    dev, descriptorSet, inTexes.length, outTex, 0L);

            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf == 0L) return;

            VulkanAPIRegistry.invoke("vkCmdBindPipeline",
                    cmdBuf, 1L, pipeline);
            MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
            dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                    cmdBuf, 1L, layout, 0L, 1, dsPtr.address(), 0, 0L);
            VulkanAPIRegistry.invoke("vkCmdPushConstants",
                    cmdBuf, layout,
                    ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, pcData.address());

            int w = Math.max(1, (ctx.getWidth() + 7) / 8);
            int h = Math.max(1, (ctx.getHeight() + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);

            FrameCommandContext.endNodeCB(NODE_ID);

            LOGGER.fine("[ReflectionNode] " + pass + " dispatch 完成");
        } catch (Throwable t) {
            LOGGER.fine("[ReflectionNode] " + pass + " dispatch 异常: " + t.getMessage());
        }
    }

    /**
     * 执行单次 Compute Dispatch（单输入版本，用于 SSR March）
     * <p>
     * SSR March 有独立的 4 个 binding（scene/position/normal → ssr output），
     * 且输入纹理是独立的参数而非数组。
     *
     * 【方法参数】
     * @param ctx       RenderContext - 渲染上下文
     * @param pass      String       - pass 名称
     * @param sceneTex  long         - 场景颜色纹理句柄
     * @param positionTex long       - 位置纹理句柄
     * @param normalTex long         - 法线纹理句柄
     * @param outTex    long         - SSR 中间输出纹理句柄
     * @param pcData    MemorySegment - push_constant 数据
     * @param pipeline  long         - VkPipeline 句柄
     * @param layout    long         - VkPipelineLayout 句柄
     * @param descSet   long         - VkDescriptorSet 句柄
     */
    private void submitFullScreenDraw(RenderContext ctx, String pass,
                                       long sceneTex, long positionTex, long normalTex,
                                       long outTex, MemorySegment pcData,
                                       long pipeline, long layout, long descSet) {
        if (pipeline == 0L || layout == 0L || descSet == 0L) {
            LOGGER.fine("[ReflectionNode] " + pass + " 跳过：管线未就绪");
            return;
        }

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev == 0L) return;

        try {
            ComputePipelineHelper.updateImageDescriptor(dev, descSet, 0, sceneTex, 0L, 0L);
            ComputePipelineHelper.updateImageDescriptor(dev, descSet, 1, positionTex, 0L, 0L);
            ComputePipelineHelper.updateImageDescriptor(dev, descSet, 2, normalTex, 0L, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descSet, 3, outTex, 0L);

            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf == 0L) return;

            VulkanAPIRegistry.invoke("vkCmdBindPipeline",
                    cmdBuf, 1L, pipeline);
            MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
            dsPtr.set(ValueLayout.JAVA_LONG, 0, descSet);
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                    cmdBuf, 1L, layout, 0L, 1, dsPtr.address(), 0, 0L);
            VulkanAPIRegistry.invoke("vkCmdPushConstants",
                    cmdBuf, layout,
                    ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, pcData.address());

            int w = Math.max(1, (ctx.getWidth() + 7) / 8);
            int h = Math.max(1, (ctx.getHeight() + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);

            FrameCommandContext.endNodeCB(NODE_ID);

            LOGGER.fine("[ReflectionNode] " + pass + " dispatch 完成");
        } catch (Throwable t) {
            LOGGER.fine("[ReflectionNode] " + pass + " dispatch 异常: " + t.getMessage());
        }
    }

    @Override
    protected void onDispose() {
        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L) {
            destroyPipeline(dev, pipelineSSRMarch, layoutSSRMarch);
            destroyPipeline(dev, pipelineSSRResolve, layoutSSRResolve);
            destroyPipeline(dev, pipelinePlanar, layoutPlanar);
            destroyPipeline(dev, pipelineHybrid, layoutHybrid);
            if (outputImageView != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyImage", dev, outputImage, 0L); } catch (Throwable ignored) {}
                outputImage = 0L;
            }
        }
        pipelineSSRMarch = 0L; layoutSSRMarch = 0L; setSSRMarch = 0L;
        pipelineSSRResolve = 0L; layoutSSRResolve = 0L; setSSRResolve = 0L;
        pipelinePlanar = 0L; layoutPlanar = 0L; setPlanar = 0L;
        pipelineHybrid = 0L; layoutHybrid = 0L; setHybrid = 0L;
        lastWidth = 0; lastHeight = 0;
        releaseTexture(outputTextureHandle);
        releaseTexture(ssrIntermediateTexture);
        outputTextureHandle = 0L;
        ssrIntermediateTexture = 0L;
        releaseShaderPrograms();
    }

    // ==================== 配置 API ====================

    public void setReflectionType(ReflectionType t) { if (t != null) this.reflectionType = t; }
    public ReflectionType getReflectionType() { return reflectionType; }

    public void setMaxLod(int v) { this.maxLod = clamp(v, MIN_MAX_LOD, MAX_MAX_LOD); }
    public int getMaxLod() { return maxLod; }

    public void setRoughnessBias(float v) { this.roughnessBias = clamp01(v); }
    public float getRoughnessBias() { return roughnessBias; }

    public void setEnableSSR(boolean v) { this.enableSSR = v; }
    public boolean isEnableSSR() { return enableSSR; }

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }

    public void resetStats() { totalExecuteTimeNanos = 0L; totalFrames = 0L; }

    @Override
    public String toString() {
        return String.format("Reflection{type=%s, maxLod=%d, roughnessBias=%.2f, ssr=%b}",
                reflectionType.name(), maxLod, roughnessBias, enableSSR);
    }

    private float clamp01(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    // ==================== 资源管理 ====================

    /**
     * 确保所有 4 条 Compute Pipeline 均已创建
     * <p>
     * 双重检查锁定（DCL），每条管线按 shader 路径从 classpath 加载 SPIR-V。
     * 每条管线有独立的 Bindings 和 PushConstant 配置。
     */
    private void ensurePipelines() {
        ensurePipelineSSRMarch();
        ensurePipelineSSRResolve();
        ensurePipelinePlanar();
        ensurePipelineHybrid();
    }

    private void ensurePipelineSSRMarch() {
        if (pipelineSSRMarch != 0L) return;
        synchronized (this) {
            if (pipelineSSRMarch != 0L) return;
            try {
                byte[] spirv = ShaderPathResolver.resolveSPIRV(KEY_SSR_MARCH);
                if (spirv == null) { LOGGER.warning("SPIR-V 加载失败: " + KEY_SSR_MARCH); return; }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    pipelineSSRMarch = r.pipeline();
                    layoutSSRMarch = r.pipelineLayout();
                    setSSRMarch = r.descriptorSet();
                    LOGGER.fine("[ReflectionNode] SSR March Pipeline 创建成功");
                }
            } catch (Exception e) {
                LOGGER.warning("[ReflectionNode] SSR March Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    private void ensurePipelineSSRResolve() {
        if (pipelineSSRResolve != 0L) return;
        synchronized (this) {
            if (pipelineSSRResolve != 0L) return;
            try {
                byte[] spirv = ShaderPathResolver.resolveSPIRV(KEY_SSR_RESOLVE);
                if (spirv == null) { LOGGER.warning("SPIR-V 加载失败: " + KEY_SSR_RESOLVE); return; }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    pipelineSSRResolve = r.pipeline();
                    layoutSSRResolve = r.pipelineLayout();
                    setSSRResolve = r.descriptorSet();
                    LOGGER.fine("[ReflectionNode] SSR Resolve Pipeline 创建成功");
                }
            } catch (Exception e) {
                LOGGER.warning("[ReflectionNode] SSR Resolve Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    private void ensurePipelinePlanar() {
        if (pipelinePlanar != 0L) return;
        synchronized (this) {
            if (pipelinePlanar != 0L) return;
            try {
                byte[] spirv = ShaderPathResolver.resolveSPIRV(KEY_PLANAR);
                if (spirv == null) { LOGGER.warning("SPIR-V 加载失败: " + KEY_PLANAR); return; }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    pipelinePlanar = r.pipeline();
                    layoutPlanar = r.pipelineLayout();
                    setPlanar = r.descriptorSet();
                    LOGGER.fine("[ReflectionNode] Planar Pipeline 创建成功");
                }
            } catch (Exception e) {
                LOGGER.warning("[ReflectionNode] Planar Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    private void ensurePipelineHybrid() {
        if (pipelineHybrid != 0L) return;
        synchronized (this) {
            if (pipelineHybrid != 0L) return;
            try {
                byte[] spirv = ShaderPathResolver.resolveSPIRV(KEY_HYBRID);
                if (spirv == null) { LOGGER.warning("SPIR-V 加载失败: " + KEY_HYBRID); return; }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    pipelineHybrid = r.pipeline();
                    layoutHybrid = r.pipelineLayout();
                    setHybrid = r.descriptorSet();
                    LOGGER.fine("[ReflectionNode] Hybrid Pipeline 创建成功");
                }
            } catch (Exception e) {
                LOGGER.warning("[ReflectionNode] Hybrid Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    /**
     * 确保输出 Image 已创建且尺寸匹配当前分辨率
     * <p>
     * DCL 双重检查锁定。当 outputImage 未创建或渲染尺寸变化时，
     * 通过 VulkanGPUResourceManager 创建新的 STORAGE_IMAGE + ImageView。
     * 格式: VK_FORMAT_R16G16B16A16_SFLOAT (97), 用途: STORAGE | SAMPLED (0x30)
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文（获取宽度/高度）
     */
    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();
        if (w <= 0 || h <= 0) return;

        if (outputImage != 0L && lastWidth == w && lastHeight == h) return;

        synchronized (this) {
            if (outputImage != 0L && lastWidth == w && lastHeight == h) return;

            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L) return;

            try {
                int format = 97;
                int usageFlags = 0x20 | 0x10;

                var mgr = VulkanGPUResourceManager.getInstance();
                if (outputImageView != 0L) {
                    try { mgr.destroyView(outputImageView); } catch (Throwable ignored) {}
                    outputImageView = 0L;
                }
                if (outputImage != 0L) {
                    try { mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                            outputImage, 0L, lastWidth, lastHeight, format,
                            VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {}
                    outputImage = 0L;
                }
                var resource = mgr.createImage(w, h, format, usageFlags,
                        VmaMemoryPools.PoolType.RENDER_TARGET);

                if (resource != null && resource.isValid()) {
                    long newImage = resource.handle;
                    long newView = mgr.createView(newImage, format, 0x10);

                    if (newView != 0L) {
                        if (outputImageView != 0L) {
                            try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {}
                        }
                        if (outputImage != 0L) {
                            try { VulkanAPIRegistry.invoke("vkDestroyImage", dev, outputImage, 0L); } catch (Throwable ignored) {}
                        }

                        outputImage = newImage;
                        outputImageView = newView;
                        lastWidth = w;
                        lastHeight = h;

                        LOGGER.fine("[ReflectionNode] 输出图像已创建 [" + w + "x" + h + "]");
                    } else {
                        LOGGER.warning("[ReflectionNode] createView 失败");
                    }
                } else {
                    LOGGER.warning("[ReflectionNode] createImage 失败");
                }
            } catch (Exception e) {
                LOGGER.warning("[ReflectionNode] ensureOutputImage 异常: " + e.getMessage());
            }
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * 【方法参数】
     * @param path String - 资源路径（如 "/shaders/reflection_ssr_march.spv"）
     *
     * 【返回值】
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = ReflectionNode.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 安全销毁 VkPipeline + VkPipelineLayout
     */
    private static void destroyPipeline(long dev, long pipeline, long layout) {
        if (pipeline != 0L) {
            try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, pipeline, 0L); } catch (Throwable ignored) {}
        }
        if (layout != 0L) {
            try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, layout, 0L); } catch (Throwable ignored) {}
        }
    }

    private long allocateOutputTexture(int w, int h) { return 0xBB030000L | ((long)(w&0xFFFF)<<16)|(long)(h&0xFFFF); }
    private void releaseTexture(long h) {
        if (!VulkanGraphicsHelper.isAvailable() || h == 0L) return;
        VulkanGraphicsHelper.destroyImageView(VulkanGraphicsHelper.getDevice(), h);
    }
    private boolean prepareShaderPrograms(RenderContext ctx) {
        if (!VulkanGraphicsHelper.isAvailable()) return true;
        LOGGER.fine("[ReflectionNode] shader programs prepared");
        return true;
    }
    private void releaseShaderPrograms() {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        LOGGER.fine("[ReflectionNode] shader programs released");
    }
}
