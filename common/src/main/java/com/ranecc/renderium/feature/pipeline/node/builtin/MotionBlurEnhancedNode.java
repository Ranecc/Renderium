// Renderium - 光影系统 v2.0
// 增强运动模糊 (Enhanced Motion Blur) 后处理节点
//
// 核心算法:
//   1. 从速度缓冲读取逐像素运动矢量
//   2. 速度钳制：length(velocity) > maxVelocity → normalize * maxVelocity
//   3. 沿速度方向采样并累加
//   4. TileMax 优化：先在 32x32 tile 内取最大速度，减少采样次数
//   5. 分离物体/相机运动：objectMotion=false 时仅模糊相机运动
//
// 性能预算:
//   - 速度缓冲读取: < 0.1ms/帧
//   - TileMax: < 0.2ms/帧
//   - 运动模糊采样: < 1.5ms/帧（取决于 samples 和速度分布）
//   - 总计: < 2.0ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

import com.ranecc.renderium.feature.config.RenderiumConfigLoader;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * 增强运动模糊节点 (Enhanced Motion Blur)
 * <p>
 * 基于速度缓冲的逐像素运动模糊，替代简单的后处理模糊。
 * 支持物体运动和相机运动分离，避免静态物体被错误模糊。
 * <p>
 * Blender: Vector Blur | Unreal: Motion Blur | Unity: Motion Blur
 * <p>
 * GPU 开销：0.5-2ms (1080p)
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
 * ├─ Step 3: 从速度缓冲读取逐像素运动矢量                          │
 * │   velocity = (currentPos - prevPos) * intensity              │
 *     ↓                                                          │
 * ├─ Step 4: 速度钳制                                             │
 * │   length(velocity) > maxVelocity → normalize * maxVelocity   │
 *     ↓                                                          │
 * ├─ Step 5: TileMax 优化（32x32 tile 内取最大速度）               │
 *     ↓                                                          │
 * ├─ Step 6: 沿速度方向采样                                       │
 * │   for (i = -samples/2; i < samples/2; i++) {                 │
 * │       offset = velocity * (i / samples);                     │
 * │       color += texture(inputTex, uv + offset);               │
 * │   }                                                          │
 * │   color /= samples;                                          │
 *     ↓                                                          │
 * ├─ Step 7: 分离物体/相机运动（可选）                             │
 *     ↓                                                          │
 * └─ Step 8: 返回处理后的纹理句柄                                  │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>intensity</td><td>0.5</td><td>模糊强度，越高越模糊（0.0~2.0）</td></tr>
 *   <tr><td>samples</td><td>8</td><td>采样数量，越高越平滑（2~32）</td></tr>
 *   <tr><td>maxVelocity</td><td>50.0</td><td>最大速度（像素/帧），防止过度模糊</td></tr>
 *   <tr><td>objectMotion</td><td>true</td><td>是否包含物体运动模糊</td></tr>
 *   <tr><td>cameraMotion</td><td>true</td><td>是否包含相机运动模糊</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 5.5.0
 */
public class MotionBlurEnhancedNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(MotionBlurEnhancedNode.class.getName());

    /** SPIR-V 着色器资源路径（DDD分层：pipeline/postprocess/effects） */
    private static final String SHADER_PATH = "/shaders/pipeline/postprocess/effects/motion_blur.spv";

    // ==================== 参数边界 ====================

    /** 模糊强度下界 */
    private static final float INTENSITY_MIN = 0.0f;

    /** 模糊强度上界 */
    private static final float INTENSITY_MAX = 2.0f;

    /** 模糊强度默认值 */
    private static final float INTENSITY_DEFAULT = 0.5f;

    /** 采样数量下界 */
    private static final int SAMPLES_MIN = 2;

    /** 采样数量上界 */
    private static final int SAMPLES_MAX = 32;

    /** 采样数量默认值 */
    private static final int SAMPLES_DEFAULT = 8;

    /** 最大速度下界（像素/帧） */
    private static final float MAX_VELOCITY_MIN = 1.0f;

    /** 最大速度默认值（像素/帧） */
    private static final float MAX_VELOCITY_DEFAULT = 50.0f;

    // ==================== 可调参数 ====================

    /** 是否启用增强运动模糊 */
    private volatile boolean enabled = false;

    /** 模糊强度系数 */
    private volatile float intensity = INTENSITY_DEFAULT;

    /** 沿速度方向的采样数量 */
    private volatile int samples = SAMPLES_DEFAULT;

    /** 最大速度（像素/帧），超过此值的速度会被钳制 */
    private volatile float maxVelocity = MAX_VELOCITY_DEFAULT;

    /** 是否包含物体运动模糊 */
    private volatile boolean objectMotion = true;

    /** 是否包含相机运动模糊 */
    private volatile boolean cameraMotion = true;

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    /** Pipeline 是否已创建 */
    private volatile boolean pipelineCreated = false;

    /** 输出 Image 句柄（Compute Shader 写入目标） */
    private volatile long outputImage = 0L;

    /** 输出 ImageView 句柄（绑定到 STORAGE_IMAGE descriptor） */
    private volatile long outputImageView = 0L;

    /** 上次创建输出 Image 时的宽度（用于检测尺寸变化） */
    private volatile int lastOutputWidth = 0;

    /** 上次创建输出 Image 时的高度（用于检测尺寸变化） */
    private volatile int lastOutputHeight = 0;

    // ==================== 构造函数 ====================

    /**
     * 构造增强运动模糊节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "motion_blur_enhanced"</li>
     *   <li>DisplayName: "Motion Blur Enhanced (增强运动模糊)"</li>
     *   <li>Category: {@link PipelineNode.Category#POST_PROCESS}</li>
     *   <li>Priority: 170（在景深之后、TAA 之前）</li>
     *   <li>依赖: ["gbuffer_geometry"]</li>
     * </ul>
     */
    public MotionBlurEnhancedNode() {
        super(
                "motion_blur_enhanced",                         // 唯一标识符（kebab-case）
                "Motion Blur Enhanced (增强运动模糊)",          // 显示名称
                PipelineNode.Category.POST_PROCESS,            // 分类：后处理阶段
                170,                                           // 优先级
                new String[]{"gbuffer_geometry"}               // 依赖：G-Buffer 几何节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行增强运动模糊计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>从速度缓冲读取逐像素运动矢量</li>
     *   <li>速度钳制和 TileMax 优化</li>
     *   <li>沿速度方向采样并累加</li>
     *   <li>分离物体/相机运动（可选）</li>
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
     * - 1080p / 8 samples 目标: < 2ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        var cfg = com.ranecc.renderium.feature.config.RenderiumConfigLoader.getInstance();
        var mbCfg = cfg.section("motion_blur");
        float curStrength = mbCfg.getFloat("strength", this.intensity);
        int curSampleCount = mbCfg.getInt("sample_count", this.samples);
        float curVelocityScale = mbCfg.getFloat("velocity_scale", 1.0f);
        boolean nodeEnabled = mbCfg.getBoolean("enabled", this.enabled) && cfg.getBoolean("renderium.enabled", true);
        if (!nodeEnabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[MotionBlur] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        MemorySegment params = PerFrameArena.allocate(32L);
        params.set(ValueLayout.JAVA_FLOAT, 0, curStrength);
        params.set(ValueLayout.JAVA_INT, 4, curSampleCount);
        params.set(ValueLayout.JAVA_FLOAT, 8, this.maxVelocity * curVelocityScale);
        params.set(ValueLayout.JAVA_INT, 12, context.getWidth());
        params.set(ValueLayout.JAVA_INT, 16, context.getHeight());
        params.set(ValueLayout.JAVA_INT, 20, objectMotion ? 1 : 0);

        // 确保 Compute Pipeline 已创建（加载 SPIR-V 着色器）
        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        // 确保输出 Image 已创建（独立于输入纹理的写入目标）
        ensureOutputImage(context);
        if (outputImageView == 0L) return passThrough(inputResources);

        // 实际 Vulkan Compute Shader 调度
        // 1. 更新 descriptor set 绑定输入/输出纹理
        long dev = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L && descriptorSet != 0L) {
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 0, inputResources[0], 0L, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 1, outputImage, outputImageView);
        }
        try {
            long cmdBuf = com.ranecc.renderium.feature.lod.compute.LodCullingComputePass.allocateCommandBuffer(dev);
            if (cmdBuf != 0L) {
                com.ranecc.renderium.feature.lod.compute.LodCullingComputePass.beginCommandBuffer(cmdBuf);
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);
                MemorySegment dsPtr = com.ranecc.renderium.infrastructure.gpu.PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf, 1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, pipelineLayout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, params.address());
                int w = (context.getWidth() + 7) / 8;
                int h = (context.getHeight() + 7) / 8;
                com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);
                com.ranecc.renderium.feature.lod.compute.LodCullingComputePass.endCommandBuffer(cmdBuf);
                long queue = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getGraphicsQueue();
                if (queue != 0L) {
                    com.ranecc.renderium.infrastructure.gpu.VulkanSyncManager.submitAndWait(queue, cmdBuf);
                }
            }
        } catch (Throwable t) {
            LOGGER.fine("[MotionBlur] dispatch 失败: " + t.getMessage());
        }

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[MotionBlur] 完成 | strength=%.2f sampleCount=%d velocityScale=%.2f | pipeline=0x%X | %.1fμs",
                curStrength, curSampleCount, curVelocityScale, computePipeline, elapsedMicros
        ));

        // 返回 Compute Shader 写入的输出纹理（outputImageView）
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
                "[MotionBlur] 初始化成功 | intensity=%.2f samples=%d maxVel=%.1f objMotion=%b camMotion=%b",
                this.intensity, this.samples, this.maxVelocity, this.objectMotion, this.cameraMotion
        ));
        return true;
    }

    /**
     * 节点资源释放钩子
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
        if (outputImage != 0L || outputImageView != 0L) {
            if (outputImageView != 0L && device != 0L) {
                try { com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry.invoke("vkDestroyImageView", device, outputImageView, 0L); } catch (Throwable ignored) {}
            }
            var mgr2 = com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.getInstance();
            if (outputImage != 0L) {
                try { mgr2.releaseResource(new com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastOutputWidth, lastOutputHeight, 87, com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {}
            }
            LOGGER.fine(String.format("[MotionBlur] 释放输出资源 image=0x%X view=0x%X",
                    outputImage, outputImageView));
            outputImage = 0L;
            outputImageView = 0L;
            lastOutputWidth = 0;
            lastOutputHeight = 0;
        }
        LOGGER.fine("[MotionBlur] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用增强运动模糊
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
     * 设置模糊强度
     * <p>
     * 值会被钳制到有效范围 [{@value #INTENSITY_MIN}, {@value #INTENSITY_MAX}]。
     *
     * @param v 模糊强度（0.0 ~ 2.0）
     */
    public void setIntensity(float v) {
        this.intensity = Math.max(INTENSITY_MIN, Math.min(INTENSITY_MAX, v));
    }

    /**
     * 设置采样数量
     * <p>
     * 值会被钳制到有效范围 [{@value #SAMPLES_MIN}, {@value #SAMPLES_MAX}]。
     *
     * @param v 采样数量（2 ~ 32）
     */
    public void setSamples(int v) {
        this.samples = Math.max(SAMPLES_MIN, Math.min(SAMPLES_MAX, v));
    }

    /**
     * 设置最大速度（像素/帧）
     * <p>
     * 值会被钳制到 ≥ 1.0，防止除零和无效值。
     *
     * @param v 最大速度（≥ 1.0 像素/帧）
     */
    public void setMaxVelocity(float v) { this.maxVelocity = Math.max(MAX_VELOCITY_MIN, v); }

    /**
     * 设置是否包含物体运动模糊
     *
     * @param v 是否包含物体运动
     */
    public void setObjectMotion(boolean v) { this.objectMotion = v; }

    /**
     * 设置是否包含相机运动模糊
     *
     * @param v 是否包含相机运动
     */
    public void setCameraMotion(boolean v) { this.cameraMotion = v; }

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
     *       intensity, samples, maxVelocity, screenSize, objectMotion</li>
     *   <li>vkCreateComputePipelines(shaderModule, pipelineLayout) — 创建 Compute Pipeline</li>
     * </ol>
     *
     * <h3>依赖模块：</h3>
     * <ul>
     *   <li>{@link com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding} — 提供 vkCreate* 方法句柄</li>
     *   <li>{@link com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder} — vkDevice 句柄</li>
     *   <li>{@link com.ranecc.renderium.infrastructure.gpu.PostProcessComputeHelper} — Pipeline 缓存与复用</li>
     * </ul>
     */
    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        synchronized (this) {
            if (computePipeline != 0L) return;
            try {
                byte[] spirv = loadSPIRVResource(SHADER_PATH);
                if (spirv == null || spirv.length == 0) {
                    LOGGER.warning("SPIR-V 着色器加载失败: " + SHADER_PATH);
                    return;
                }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    computePipeline = r.pipeline();
                    pipelineLayout = r.pipelineLayout();
                    descriptorSet = r.descriptorSet();
                    pipelineCreated = true;
                    LOGGER.fine("Compute Pipeline 创建成功: " + SHADER_PATH);
                }
            } catch (Exception e) {
                LOGGER.warning("Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    /**
     * 确保输出 Image 已创建且尺寸匹配
     * <p>
     * 当 outputImage 尚未创建或渲染尺寸发生变化时，
     * 通过 VulkanGPUResourceManager 创建新的 VK_FORMAT_R8G8B8A8_UNORM 存储 Image，
     * 并生成对应的 ImageView 绑定到 STORAGE_IMAGE descriptor。
     *
     * 【方法参数】
     * @param context RenderContext - 当前帧渲染上下文（提供 width/height）
     *
     * 【副作用】
     * - 若尺寸变化，旧资源由 VMA 延迟销毁队列管理
     * - 更新 outputImage / outputImageView / lastOutputWidth / lastOutputHeight 字段
     */
    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();

        if (outputImage != 0L && w == lastOutputWidth && h == lastOutputHeight) {
            return;
        }

        synchronized (this) {
            if (outputImage != 0L && w == lastOutputWidth && h == lastOutputHeight) {
                return;
            }

            com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager mgr =
                    com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.getInstance();

            if (outputImageView != 0L) {
                try { mgr.destroyView(outputImageView); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            int format = 87;
            if (outputImage != 0L) {
                try { mgr.releaseResource(new com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastOutputWidth, lastOutputHeight, format, com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {}
                outputImage = 0L;
            }

            int usageFlags = 0x20 | 0x10;

            com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager.GpuResource resource =
                    mgr.createImage(w, h, format, usageFlags,
                            com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools.PoolType.RENDER_TARGET);

            if (resource == null || !resource.isValid() || resource.handle == 0L) {
                LOGGER.warning("[MotionBlur] 输出 Image 创建失败 [" + w + "x" + h + "]");
                return;
            }

            long view = mgr.createView(resource.handle, format,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT);

            this.outputImage = resource.handle;
            this.outputImageView = view;
            this.lastOutputWidth = w;
            this.lastOutputHeight = h;

            LOGGER.fine(String.format("[MotionBlur] 输出 Image 已创建 [%dx%d] image=0x%X view=0x%X",
                    w, h, resource.handle, view));
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * @param path 资源路径（如 "/shaders/pipeline/postprocess/effects/motion_blur.spv"）
     * @return SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = MotionBlurEnhancedNode.class.getResourceAsStream(path)) {
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
                "MotionBlurEnhancedNode{enabled=%b intensity=%.2f samples=%d maxVel=%.1f objMotion=%b camMotion=%b}",
                enabled, intensity, samples, maxVelocity, objectMotion, cameraMotion
        );
    }
}
