// Renderium - 光影系统 v2.0
// 时域抗锯齿 (Temporal Anti-Aliasing) 后处理节点
//
// 核心算法:
//   1. 速度缓冲重投影：根据运动矢量采样历史帧
//   2. 邻域夹紧 (Variance Clip)：3x3 邻域 min/max 夹紧历史帧颜色
//   3. 速度拒绝：运动矢量 > 阈值时降低历史帧权重
//   4. 混合：output = lerp(currentColor, clampedHistory, blendWeight)
//   5. 更新历史帧缓冲
//
// 性能预算:
//   - 重投影采样: < 0.2ms/帧
//   - 邻域夹紧: < 0.3ms/帧
//   - 混合输出: < 0.2ms/帧
//   - 总计: < 1.0ms/帧

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.postprocess.antialiasing;

import com.ranecc.renderium.feature.config.RenderiumConfigLoader;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.PostProcessComputeHelper;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;

/**
 * 时域抗锯齿节点 (Temporal Anti-Aliasing)
 * <p>
 * 利用历史帧信息实现高质量抗锯齿，比 FXAA 质量高得多。
 * 需要运动矢量缓冲 + 历史帧颜色缓冲 + 邻域夹紧。
 * <p>
 * Blender: Anti-Aliasing | Unreal: TAA | Unity: TAA
 * <p>
 * GPU 开销：0.5-1ms (1080p)
 * 短路条件：enabled == false → 直接返回输入纹理
 *
 * <h2>算法概述：</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ execute() 入口                                                │
 *     ↓                                                          │
 * ├─ Step 1: 短路检查（enabled == false → pass-through）          │
 *     ↓                                                          │
 * ├─ Step 2: 输入校验（至少需要颜色纹理 1 张）                     │
 *     ↓                                                          │
 * ├─ Step 3: 速度缓冲重投影                                       │
 * │   根据运动矢量采样历史帧颜色                                    │
 *     ↓                                                          │
 * ├─ Step 4: 邻域夹紧 (Variance Clip)                             │
 * │   3x3 邻域 min/max 夹紧历史帧颜色，防止鬼影                     │
 *     ↓                                                          │
 * ├─ Step 5: 速度拒绝                                             │
 * │   运动矢量 > 阈值时降低历史帧权重                               │
 *     ↓                                                          │
 * ├─ Step 6: 混合输出                                             │
 * │   output = lerp(currentColor, clampedHistory, blendWeight)    │
 *     ↓                                                          │
 * ├─ Step 7: 更新历史帧缓冲                                       │
 *     ↓                                                          │
 * └─ Step 8: 返回处理后的纹理句柄                                  │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>sharpness</td><td>0.5</td><td>锐化强度，越高越锐利（0.0~1.0）</td></tr>
 *   <tr><td>blendWeight</td><td>0.1</td><td>历史帧混合权重，越低越响应（0.01~0.5）</td></tr>
 *   <tr><td>neighborhoodClamp</td><td>true</td><td>邻域夹紧，防止鬼影</td></tr>
 *   <tr><td>velocityRejection</td><td>true</td><td>速度拒绝，快速运动时降低历史帧权重</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 5.5.0
 */
public class TemporalAANode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(TemporalAANode.class.getName());

    /**
     * Shader key，镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/postprocess/antialiasing/taa_resolve";
    }

    // ==================== 参数边界 ====================

    /** 锐化强度下界 */
    private static final float SHARPNESS_MIN = 0.0f;

    /** 锐化强度上界 */
    private static final float SHARPNESS_MAX = 1.0f;

    /** 锐化强度默认值 */
    private static final float SHARPNESS_DEFAULT = 0.5f;

    /** 历史帧混合权重下界 */
    private static final float BLEND_WEIGHT_MIN = 0.01f;

    /** 历史帧混合权重上界 */
    private static final float BLEND_WEIGHT_MAX = 0.5f;

    /** 历史帧混合权重默认值 */
    private static final float BLEND_WEIGHT_DEFAULT = 0.1f;

    /** FrameCommandContext 节点索引 */
    private static final int NODE_ID = 11;

    // ==================== 可调参数 ====================

    /** 是否启用 TAA */
    private volatile boolean enabled = false;

    /** 锐化强度，控制最终输出的锐化程度 */
    private volatile float sharpness = SHARPNESS_DEFAULT;

    /** 历史帧混合权重，越低越响应当前帧（减少拖影但增加闪烁） */
    private volatile float blendWeight = BLEND_WEIGHT_DEFAULT;

    /** 是否启用邻域夹紧（防止鬼影） */
    private volatile boolean neighborhoodClamp = true;

    /** 是否启用速度拒绝（快速运动时降低历史帧权重） */
    private volatile boolean velocityRejection = true;

    /** 历史帧颜色缓冲（上一帧输出） */
    private volatile long historyColorBuffer = 0L;

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    /** Pipeline 是否已创建 */
    private volatile boolean pipelineCreated = false;

    /** TAA 输出 Image 句柄（独立 STORAGE_IMAGE，compute shader 写入目标） */
    private volatile long outputImage = 0L;

    /** TAA 输出 ImageView 句柄（绑定到 descriptor set binding 3） */
    private volatile long outputImageView = 0L;

    /** 上次输出 Image 的宽度（用于检测尺寸变化并重建） */
    private volatile int lastOutputWidth = 0;

    /** 上次输出 Image 的高度（用于检测尺寸变化并重建） */
    private volatile int lastOutputHeight = 0;

    // ==================== 构造函数 ====================

    /**
     * 构造 TAA 节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "temporal_aa"</li>
     *   <li>DisplayName: "TAA (时域抗锯齿)"</li>
     *   <li>Category: {@link PipelineNode.Category#POST_PROCESS}</li>
     *   <li>Priority: 190（在后处理最末，确保所有效果都经过抗锯齿）</li>
     *   <li>依赖: ["gbuffer_geometry"]</li>
     * </ul>
     */
    public TemporalAANode() {
        super(
                "temporal_aa",                                  // 唯一标识符（kebab-case）
                "TAA (时域抗锯齿)",                             // 显示名称
                PipelineNode.Category.POST_PROCESS,            // 分类：后处理阶段
                190,                                           // 优先级
                new String[]{"gbuffer_geometry"}               // 依赖：G-Buffer 几何节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行时域抗锯齿计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>速度缓冲重投影：根据运动矢量采样历史帧</li>
     *   <li>邻域夹紧：3x3 邻域 min/max 夹紧历史帧颜色</li>
     *   <li>速度拒绝：运动矢量 > 阈值时降低历史帧权重</li>
     *   <li>混合输出并更新历史帧缓冲</li>
     * </ol>
     *
     * 【方法参数】
     * @param context         RenderContext - 当前帧渲染上下文
     * @param inputResources long...      - 上游节点输出的资源句柄数组
     *                                  [0] = 颜色纹理句柄
     *
     * 【返回值】
     * @return long - 处理后的颜色纹理句柄，0 表示失败
     *
     * 【性能预算】
     * - 1080p 目标: < 1ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        var cfg = RenderiumConfigLoader.getInstance();
        var sectionCfg = cfg.section("temporal_aa");
        float curJitterStrength = sectionCfg.getFloat("jitter_strength", this.sharpness);
        float curBlendFactor = sectionCfg.getFloat("blend_factor", this.blendWeight);
        float curHistoryDecay = sectionCfg.getFloat("history_decay", 0.95f);
        boolean nodeEnabled = sectionCfg.getBoolean("enabled", this.enabled) && cfg.getBoolean("renderium.enabled", true);
        if (!nodeEnabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[TAA] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        RenderiumProfiler.recordStart(11);

        float curSharpness = curJitterStrength;
        float curBlendWeight = curBlendFactor;
        boolean curClamp       = this.neighborhoodClamp;
        boolean curVelReject   = this.velocityRejection;

        MemorySegment params = PerFrameArena.allocate(64L);
        params.set(ValueLayout.JAVA_FLOAT, 0, curBlendWeight);
        params.set(ValueLayout.JAVA_FLOAT, 4, curSharpness);
        params.set(ValueLayout.JAVA_INT, 8, curClamp ? 1 : 0);
        params.set(ValueLayout.JAVA_INT, 12, curVelReject ? 1 : 0);
        params.set(ValueLayout.JAVA_INT, 16, context.getWidth());
        params.set(ValueLayout.JAVA_INT, 20, context.getHeight());

        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        ensureOutputImage(context);

        long dev = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L && descriptorSet != 0L) {
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 0, inputResources[0], 0L, 0L);
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 1, historyColorBuffer, 0L, 0L);
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 2, inputResources.length > 1 ? inputResources[1] : 0L, 0L, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 3, outputImageView, 0L);
        }
        try {
            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf != 0L) {
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);
                MemorySegment dsPtr = com.ranecc.renderium.infrastructure.gpu.PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf, 1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, pipelineLayout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 64, params.address());
                int w = (context.getWidth() + 7) / 8;
                int h = (context.getHeight() + 7) / 8;
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);
                FrameCommandContext.endNodeCB(NODE_ID);
            }
        } catch (Throwable t) {
            LOGGER.fine("[TAA] dispatch 失败: " + t.getMessage());
        }

        long previousHistory = this.historyColorBuffer;
        this.historyColorBuffer = outputImageView;

        RenderiumProfiler.recordEnd(11);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format(
                    "[TAA] 完成 | blend=%.2f sharpness=%.2f clamp=%b velReject=%b | outputView=0x%X prevHistory=0x%X | %.1f\u00b5s",%.1fμs",
                curBlendWeight, curSharpness, curClamp, curVelReject, outputImageView, previousHistory, elapsedMicros
        ));

        return outputImageView != 0L ? outputImageView : passThrough(inputResources);
    }

    // ==================== 初始化与释放钩子 ====================

    /**
     * 节点初始化钩子
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     *
     * 【返回值】
     * @return boolean - 是否成功初始化
     */
    @Override
    protected boolean onInitialize(RenderContext context) {
        LOGGER.info(String.format(
                "[TAA] 初始化成功 | blend=%.2f sharpness=%.2f clamp=%b velReject=%b",
                this.blendWeight, this.sharpness, this.neighborhoodClamp, this.velocityRejection
        ));
        return true;
    }

    /**
     * 节点资源释放钩子
     * <p>
     * 清空历史帧颜色缓冲句柄。
     */
    @Override
    protected void onDispose() {
        long device = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (device != 0L) {
            if (computePipeline != 0L) {
                try { com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkDestroyPipeline", device, computePipeline, 0L); } catch (Throwable ignored) {}
                computePipeline = 0L;
            }
            if (pipelineLayout != 0L) {
                try { com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", device, pipelineLayout, 0L); } catch (Throwable ignored) {}
                pipelineLayout = 0L;
            }
        }
        long dev = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (outputImageView != 0L && dev != 0L) {
            com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.destroyImageView(dev, outputImageView);
            outputImageView = 0L;
        }
        if (outputImage != 0L) {
            var mgr = com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.getInstance();
            mgr.releaseResource(new com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.GpuResource(
                    outputImage, 0L, lastOutputWidth, lastOutputHeight, 87,
                    com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.ResourceType.IMAGE));
            outputImage = 0L;
        }
        this.historyColorBuffer = 0L;
        this.lastOutputWidth = 0;
        this.lastOutputHeight = 0;
        LOGGER.fine("[TAA] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用 TAA
     *
     * @param v 是否启用
     */
    public void setEnabled(boolean v) { this.enabled = v; }

    /**
     * 获取当前启用状态
     *
     * @return boolean - 是否启用
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置锐化强度
     * <p>
     * 值会被钳制到有效范围 [{@value #SHARPNESS_MIN}, {@value #SHARPNESS_MAX}]。
     *
     * @param v 锐化强度（0.0 ~ 1.0）
     */
    public void setSharpness(float v) {
        this.sharpness = Math.max(SHARPNESS_MIN, Math.min(SHARPNESS_MAX, v));
    }

    /**
     * 设置历史帧混合权重
     * <p>
     * 值会被钳制到有效范围 [{@value #BLEND_WEIGHT_MIN}, {@value #BLEND_WEIGHT_MAX}]。
     * 越低越响应当前帧（减少拖影但增加闪烁）。
     *
     * @param v 混合权重（0.01 ~ 0.5）
     */
    public void setBlendWeight(float v) {
        this.blendWeight = Math.max(BLEND_WEIGHT_MIN, Math.min(BLEND_WEIGHT_MAX, v));
    }

    /**
     * 设置是否启用邻域夹紧
     *
     * @param v 是否启用邻域夹紧（防止鬼影）
     */
    public void setNeighborhoodClamp(boolean v) { this.neighborhoodClamp = v; }

    /**
     * 设置是否启用速度拒绝
     *
     * @param v 是否启用速度拒绝（快速运动时降低历史帧权重）
     */
    public void setVelocityRejection(boolean v) { this.velocityRejection = v; }

    // ==================== 辅助方法 ====================

    /**
     * 确保 Compute Pipeline 已创建
     * <p>
     * 懒加载模式：首次 execute() 时加载 SPIR-V 并创建 Pipeline。
     * 线程安全：双重检查锁定（DCL），synchronized 确保单次创建。
     *
     * <h3>Pipeline 创建流程（由 Vulkan 渲染线程调用）：</h3>
     * <ol>
     *   <li>vkCreateShaderModule(device, spirv) — 加载 SPIR-V 字节码</li>
     *   <li>vkCreatePipelineLayout(pushConstantRange) — PushConstants:
     *       blendWeight, sharpness, clampMode, screenSize, jitterOffset</li>
     *   <li>vkCreateComputePipelines(shaderModule, pipelineLayout) — 创建 Compute Pipeline</li>
     * </ol>
     *
     * <h3>Binding 配置（与 shader taa_resolve.comp 一致）：</h3>
     * <ul>
     *   <li>binding 0 = STORAGE_IMAGE (uCurrentFrame, 当前帧颜色)</li>
     *   <li>binding 1 = STORAGE_IMAGE (uDepthBuffer, 帧深度)</li>
     *   <li>binding 2 = STORAGE_IMAGE (uOutputImage, TAA 输出)</li>
     *   <li>binding 3 = UNIFORM_BUFFER (TAAParams, 混合参数)</li>
     *   <li>binding 4 = STORAGE_IMAGE (uHistoryFrame, 历史帧, ping-pong)</li>
     * </ul>
     * 历史帧由 historyColorBuffer 字段管理，每帧在 execute() 中更新。
     *
     * <h3>依赖模块：</h3>
     * <ul>
     *   <li>{@link com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding} — 提供 vkCreate* 方法句柄</li>
     *   <li>{@link com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder} — vkDevice 句柄</li>
     *   <li>{@link com.ranecc.renderium.infrastructure.gpu.PostProcessComputeHelper} — Pipeline 缓存与复用</li>
     * </ul>
     */
    private void ensurePipeline() {
        if (pipelineCreated) return;
        synchronized (this) {
            if (computePipeline != 0L) return;
            try {
                byte[] spirv = resolveSPIRV();
                if (spirv == null || spirv.length == 0) {
                    LOGGER.warning("SPIR-V 着色器加载失败: " + shaderKey());
                    return;
                }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 64, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    computePipeline = r.pipeline();
                    pipelineLayout = r.pipelineLayout();
                    descriptorSet = r.descriptorSet();
                    pipelineCreated = true;
                    LOGGER.fine("Compute Pipeline 创建成功: " + shaderKey());
                }
            } catch (Exception e) {
                LOGGER.warning("Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();
        if (w <= 0 || h <= 0) return;

        if (outputImage != 0L && w == lastOutputWidth && h == lastOutputHeight) return;

        var mgr = com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.getInstance();
        if (outputImageView != 0L) {
            try { mgr.destroyView(outputImageView); } catch (Throwable ignored) {}
            outputImageView = 0L;
        }
        int format = 87;
        if (outputImage != 0L) {
            try { mgr.releaseResource(new com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastOutputWidth, lastOutputHeight, format, com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {}
            outputImage = 0L;
        }
        int usage = 0x20 | 0x10;
        var res = mgr.createImage(w, h, format, usage,
                com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools.PoolType.RENDER_TARGET);
        if (!res.isValid()) {
            LOGGER.warning("[TAA] ensureOutputImage: createImage 失败 [" + w + "x" + h + "]");
            return;
        }

        long newView = mgr.createView(res.handle, format, 1);
        if (newView == 0L) {
            LOGGER.warning("[TAA] ensureOutputImage: createView 失败");
            mgr.releaseResource(res);
            return;
        }

        long oldImage = this.outputImage;
        long oldView = this.outputImageView;
        this.outputImage = res.handle;
        this.outputImageView = newView;
        this.lastOutputWidth = w;
        this.lastOutputHeight = h;

        if (oldView != 0L) {
            long dev = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
            if (dev != 0L) {
                com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.destroyImageView(dev, oldView);
            }
        }
        if (oldImage != 0L) {
            mgr.releaseResource(new com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.GpuResource(
                    oldImage, 0L, lastOutputWidth, lastOutputHeight, format,
                    com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.ResourceType.IMAGE));
        }

        LOGGER.fine("[TAA] outputImage 重建: 0x" + Long.toHexString(outputImage)
                + " view=0x" + Long.toHexString(outputImageView) + " [" + w + "x" + h + "]");
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * @param path 资源路径（如 "/shaders/pipeline/postprocess/antialiasing/taa_resolve.spv"）
     * @return SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = TemporalAANode.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 短路：禁用时直接传递输入
     *
     * @param inputs 输入资源句柄数组
     * @return long - 第一个输入资源句柄，无输入时返回 0
     */
    private long passThrough(long[] inputs) {
        return (inputs != null && inputs.length > 0) ? inputs[0] : 0L;
    }

    @Override
    public String toString() {
        return String.format(
                "TemporalAANode{enabled=%b blend=%.2f sharpness=%.2f clamp=%b velReject=%b}",
                enabled, blendWeight, sharpness, neighborhoodClamp, velocityRejection
        );
    }
}
