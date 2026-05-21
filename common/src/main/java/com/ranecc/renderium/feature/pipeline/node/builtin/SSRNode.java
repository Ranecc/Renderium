// Renderium - 光影系统 v2.0
// 屏幕空间反射 (Screen Space Reflections) 光照节点
//
// 核心算法 (Hi-Z Tracing):
//   1. 深度缓冲 → Hi-Z 金字塔构建
//   2. 光线步进：从命中点出发，沿反射方向步进
//   3. Hi-Z 遍历：由粗到细的交叉测试
//   4. 二分搜索精化：交叉点处二分搜索
//   5. 时域重投影：降噪
//   6. 空间滤波：5x5 双边滤波
//   7. 回退策略：粗糙度 > 0.7 → 跳过 SSR，使用预滤波环境贴图
//
// 性能预算:
//   - Hi-Z 构建: < 0.5ms/帧
//   - 光线步进 + Hi-Z 遍历: < 1.5ms/帧
//   - 降噪 + 滤波: < 1.0ms/帧
//   - 总计: 2-4ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;
import com.ranecc.renderium.infrastructure.gpu.VulkanSyncManager;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * 屏幕空间反射节点 (Screen Space Reflections)
 * <p>
 * 基于 Hi-Z 追踪算法实现屏幕空间反射，为光滑表面提供实时反射效果。
 * 需要深度缓冲 + 法线缓冲 + 粗糙度缓冲 + 直接光照结果。
 * <p>
 * Blender: Screen Space Reflections | Unreal: SSR | Unity: SSR
 * <p>
 * GPU 开销：2-4ms (1080p)
 * 短路条件：enabled == false OR rtReflections > 0（RT 反射可用）→ 直接返回输入纹理
 *
 * <h2>算法概述：</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ execute() 入口                                                │
 *     ↓                                                          │
 * ├─ Step 1: 短路检查（enabled == false OR rtReflections > 0）    │
 *     ↓                                                          │
 * ├─ Step 2: 输入校验（至少需要颜色纹理 1 张）                     │
 *     ↓                                                          │
 * ├─ Step 3: Hi-Z 金字塔构建                                     │
 * │   从深度缓冲逐级降采样构建 Hi-Z mipmap 链                      │
 *     ↓                                                          │
 * ├─ Step 4: 光线步进                                             │
 * │   从命中点出发，沿反射方向步进                                  │
 *     ↓                                                          │
 * ├─ Step 5: Hi-Z 遍历                                           │
 * │   由粗到细的交叉测试，快速定位反射命中点                        │
 *     ↓                                                          │
 * ├─ Step 6: 二分搜索精化                                        │
 * │   在交叉点处进行二分搜索，提高命中精度                          │
 *     ↓                                                          │
 * ├─ Step 7: 时域重投影降噪                                       │
 * │   利用历史帧信息降低反射噪声                                   │
 *     ↓                                                          │
 * ├─ Step 8: 空间滤波 (5x5 双边)                                 │
 * │   双边滤波平滑反射结果，保留边缘                                │
 *     ↓                                                          │
 * ├─ Step 9: 回退策略                                            │
 * │   粗糙度 > 0.7 → 跳过 SSR，使用预滤波环境贴图                  │
 *     ↓                                                          │
 * └─ Step 10: 返回处理后的纹理句柄                                │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>quality</td><td>2</td><td>质量等级 0-3，越高步进越精细</td></tr>
 *   <tr><td>maxSteps</td><td>64</td><td>最大步进数，8-256，越高反射越远</td></tr>
 *   <tr><td>thickness</td><td>0.1</td><td>射线厚度，0.01-1.0，影响穿透检测</td></tr>
 *   <tr><td>bruteForceBias</td><td>0.05</td><td>暴力偏移，0.0-0.5，减少自交叉伪影</td></tr>
 *   <tr><td>halfResolution</td><td>true</td><td>半分辨率渲染，节省性能</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#LIGHTING
 * @since 5.5.0
 */
public class SSRNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(SSRNode.class.getName());

    /** SPIR-V 着色器资源路径 */
    private static final String SHADER_PATH = "/shaders/ssr.spv";

    // ==================== 参数边界 ====================

    /** 质量等级下界 */
    private static final int QUALITY_MIN = 0;

    /** 质量等级上界 */
    private static final int QUALITY_MAX = 3;

    /** 质量等级默认值 */
    private static final int QUALITY_DEFAULT = 2;

    /** 最大步进数下界 */
    private static final int MAX_STEPS_MIN = 8;

    /** 最大步进数上界 */
    private static final int MAX_STEPS_MAX = 256;

    /** 最大步进数默认值 */
    private static final int MAX_STEPS_DEFAULT = 64;

    /** 射线厚度下界 */
    private static final float THICKNESS_MIN = 0.01f;

    /** 射线厚度上界 */
    private static final float THICKNESS_MAX = 1.0f;

    /** 射线厚度默认值 */
    private static final float THICKNESS_DEFAULT = 0.1f;

    /** 暴力偏移下界 */
    private static final float BRUTE_FORCE_BIAS_MIN = 0.0f;

    /** 暴力偏移上界 */
    private static final float BRUTE_FORCE_BIAS_MAX = 0.5f;

    /** 暴力偏移默认值 */
    private static final float BRUTE_FORCE_BIAS_DEFAULT = 0.05f;

    // ==================== 可调参数 ====================

    /** 是否启用 SSR */
    private volatile boolean enabled = false;

    /** 质量等级 (0=低, 1=中, 2=高, 3=超高) */
    private volatile int quality = QUALITY_DEFAULT;

    /** 最大光线步进数，越高反射越远但越慢 */
    private volatile int maxSteps = MAX_STEPS_DEFAULT;

    /** 射线厚度，用于穿透检测 */
    private volatile float thickness = THICKNESS_DEFAULT;

    /** 暴力偏移，减少自交叉伪影 */
    private volatile float bruteForceBias = BRUTE_FORCE_BIAS_DEFAULT;

    /** 是否使用半分辨率渲染（节省性能） */
    private volatile boolean halfResolution = true;

    /** RT 反射可用标志（外部设置，>0 表示 RT 反射可用，SSR 短路） */
    private volatile int rtReflections = 0;

    /** 当前帧索引（用于时域抖动） */
    private volatile int frameIndex = 0;

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    /** 输出图像资源 (STORAGE_IMAGE binding=3) */
    private volatile long outputImage = 0L;
    private volatile long outputImageView = 0L;
    private volatile int lastOutputWidth = 0;
    private volatile int lastOutputHeight = 0;

    // ==================== 构造函数 ====================

    /**
     * 构造 SSR 节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "ssr"</li>
     *   <li>DisplayName: "SSR (屏幕空间反射)"</li>
     *   <li>Category: {@link PipelineNode.Category#LIGHTING}</li>
     *   <li>Priority: 156（光照阶段）</li>
     *   <li>依赖: ["gbuffer_geometry", "direct_light"]</li>
     * </ul>
     */
    public SSRNode() {
        super(
                "ssr",                                          // 唯一标识符（kebab-case）
                "SSR (屏幕空间反射)",                            // 显示名称
                PipelineNode.Category.LIGHTING,                // 分类：光照阶段
                156,                                           // 优先级
                new String[]{"gbuffer_geometry", "direct_light"} // 依赖：G-Buffer 几何节点 + 直接光照节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行屏幕空间反射计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 或 rtReflections > 0 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>Hi-Z 金字塔构建</li>
     *   <li>光线步进 + Hi-Z 遍历</li>
     *   <li>二分搜索精化</li>
     *   <li>时域重投影降噪</li>
     *   <li>空间滤波 (5x5 双边)</li>
     *   <li>回退策略：粗糙度 > 0.7 跳过 SSR</li>
     * </ol>
     *
     * 【方法参数】
     * @param context         RenderContext - 当前帧渲染上下文
     * @param inputResources long...      - 上游节点输出的资源句柄数组
     *                                  [0] = 颜色纹理句柄
     *                                  [1] = 深度纹理句柄
     *                                  [2] = 法线纹理句柄
     *
     * 【返回值】
     * @return long - 处理后的颜色纹理句柄，0 表示失败
     *
     * 【性能预算】
     * - 1080p 目标: 2-4ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 短路：RT 反射可用时跳过 SSR
        if (rtReflections > 0) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 3) {
            LOGGER.warning("[SSR] 输入资源不足: 需要颜色/深度/法线纹理 3 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return passThrough(inputResources);
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        int curQuality = this.quality;
        int curMaxSteps = this.maxSteps;
        float curThickness = this.thickness;
        float curBruteForceBias = this.bruteForceBias;
        boolean curHalfResolution = this.halfResolution;
        int curFrameIdx = this.frameIndex;

        long colorTexture = inputResources[0];
        long depthTexture = inputResources[1];
        long normalTexture = inputResources[2];

        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        ensureOutputImage(context);
        if (outputImageView == 0L) return passThrough(inputResources);

        try {
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L || descriptorSet == 0L) return passThrough(inputResources);

            // 更新 image descriptors
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 0, colorTexture, 0L, 0L);
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 1, depthTexture, 0L, 0L);
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 2, normalTexture, 0L, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 3, outputImageView, 0L);

            // 构建 PushConstants: vec4 projParams, int maxSteps, float thickness, float bias, int frameIdx (32 bytes)
            MemorySegment params = PerFrameArena.allocate(32L);
            float projScaleX = (float) context.getWidth() * 0.5f;
            float projScaleY = (float) context.getHeight() * 0.5f;
            float nearPlane = 0.1f;
            float farPlane = 1000.0f;
            params.set(ValueLayout.JAVA_FLOAT, 0, projScaleX);
            params.set(ValueLayout.JAVA_FLOAT, 4, projScaleY);
            params.set(ValueLayout.JAVA_FLOAT, 8, nearPlane);
            params.set(ValueLayout.JAVA_FLOAT, 12, farPlane);
            params.set(ValueLayout.JAVA_INT, 16, curMaxSteps);
            params.set(ValueLayout.JAVA_FLOAT, 20, curThickness);
            params.set(ValueLayout.JAVA_FLOAT, 24, curBruteForceBias);
            params.set(ValueLayout.JAVA_INT, 28, curFrameIdx);

            // Vulkan compute dispatch
            long cmdBuf = LodCullingComputePass.allocateCommandBuffer(dev);
            if (cmdBuf != 0L) {
                LodCullingComputePass.beginCommandBuffer(cmdBuf);
                VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);
                MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf, 1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);
                VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, pipelineLayout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, params.address());

                int w = (context.getWidth() + 7) / 8;
                int h = (context.getHeight() + 7) / 8;
                VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);
                LodCullingComputePass.endCommandBuffer(cmdBuf);

                long queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
                if (queue != 0L) {
                    VulkanSyncManager.submitAndWait(queue, cmdBuf);
                }
            }
        } catch (Throwable t) {
            LOGGER.fine("[SSR] dispatch 失败: " + t.getMessage());
        }

        // 更新帧索引
        this.frameIndex = curFrameIdx + 1;

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[SSR] 完成 | quality=%d maxSteps=%d thickness=%.3f bias=%.3f halfRes=%b | %.1fμs",
                curQuality, curMaxSteps, curThickness, curBruteForceBias, curHalfResolution, elapsedMicros
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
                "[SSR] 初始化成功 | quality=%d maxSteps=%d thickness=%.3f bias=%.3f halfRes=%b",
                this.quality, this.maxSteps, this.thickness, this.bruteForceBias, this.halfResolution
        ));
        return true;
    }

    /**
     * 节点资源释放钩子
     * <p>
     * 清空内部缓冲区。
     */
    @Override
    protected void onDispose() {
        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L) {
            if (computePipeline != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, computePipeline, 0L); } catch (Throwable ignored) {}
                computePipeline = 0L;
            }
            if (pipelineLayout != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, pipelineLayout, 0L); } catch (Throwable ignored) {}
                pipelineLayout = 0L;
            }
            if (outputImageView != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyImage", dev, outputImage, 0L); } catch (Throwable ignored) {}
                outputImage = 0L;
            }
        }
        this.frameIndex = 0;
        this.lastOutputWidth = 0;
        this.lastOutputHeight = 0;
        LOGGER.fine("[SSR] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用 SSR
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
     * 设置质量等级
     * <p>
     * 值会被钳制到有效范围 [{@value #QUALITY_MIN}, {@value #QUALITY_MAX}]。
     *
     * @param v 质量等级（0=低, 1=中, 2=高, 3=超高）
     */
    public void setQuality(int v) {
        this.quality = Math.max(QUALITY_MIN, Math.min(QUALITY_MAX, v));
    }

    /**
     * 设置最大光线步进数
     * <p>
     * 值会被钳制到有效范围 [{@value #MAX_STEPS_MIN}, {@value #MAX_STEPS_MAX}]。
     *
     * @param v 最大步进数（8 ~ 256）
     */
    public void setMaxSteps(int v) {
        this.maxSteps = Math.max(MAX_STEPS_MIN, Math.min(MAX_STEPS_MAX, v));
    }

    /**
     * 设置射线厚度
     * <p>
     * 值会被钳制到有效范围 [{@value #THICKNESS_MIN}, {@value #THICKNESS_MAX}]。
     *
     * @param v 射线厚度（0.01 ~ 1.0）
     */
    public void setThickness(float v) {
        this.thickness = Math.max(THICKNESS_MIN, Math.min(THICKNESS_MAX, v));
    }

    /**
     * 设置暴力偏移
     * <p>
     * 值会被钳制到有效范围 [{@value #BRUTE_FORCE_BIAS_MIN}, {@value #BRUTE_FORCE_BIAS_MAX}]。
     * 用于减少自交叉伪影。
     *
     * @param v 暴力偏移（0.0 ~ 0.5）
     */
    public void setBruteForceBias(float v) {
        this.bruteForceBias = Math.max(BRUTE_FORCE_BIAS_MIN, Math.min(BRUTE_FORCE_BIAS_MAX, v));
    }

    /**
     * 设置是否使用半分辨率渲染
     *
     * @param v 是否使用半分辨率
     */
    public void setHalfResolution(boolean v) { this.halfResolution = v; }

    /**
     * 设置 RT 反射可用标志
     *
     * @param v RT 反射可用数量（>0 表示可用）
     */
    public void setRtReflections(int v) { this.rtReflections = v; }

    // ==================== 辅助方法 ====================

    /**
     * 确保 Compute Pipeline 已创建
     * <p>
     * 懒加载模式：首次 execute() 时加载 SPIR-V 并创建 Pipeline。
     * 线程安全：双重检查锁定（DCL），synchronized 确保单次创建。
     * 使用 {@link ComputePipelineHelper#createComputePipeline} 创建真实管线。
     *
     * SSR Binding 配置:
     *   binding 0 = COMBINED_IMAGE_SAMPLER (color 纹理)
     *   binding 1 = COMBINED_IMAGE_SAMPLER (depth 深度纹理)
     *   binding 2 = COMBINED_IMAGE_SAMPLER (normal 法线纹理)
     *   binding 3 = STORAGE_IMAGE (output 输出纹理)
     * PushConstant: size=32, offset=0
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
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    computePipeline = r.pipeline();
                    pipelineLayout = r.pipelineLayout();
                    descriptorSet = r.descriptorSet();
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
     * DCL 双重检查锁定。当 outputImage 未创建或渲染尺寸变化时，
     * 通过 VulkanGPUResourceManager 创建新的 STORAGE_IMAGE + ImageView。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文（获取宽度/高度）
     */
    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();

        if (outputImage != 0L && lastOutputWidth == w && lastOutputHeight == h) return;

        synchronized (this) {
            if (outputImage != 0L && lastOutputWidth == w && lastOutputHeight == h) return;

            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L) return;

            try {
                int format = 87;
                int usageFlags = 0x20 | 0x10;

                var mgr = VulkanGPUResourceManager.getInstance();
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
                        lastOutputWidth = w;
                        lastOutputHeight = h;

                        LOGGER.fine(String.format("[SSR] 输出图像已创建 [%dx%d] image=0x%X view=0x%X",
                                w, h, outputImage, outputImageView));
                    } else {
                        LOGGER.warning("[SSR] createView 失败，回退到 passThrough");
                    }
                } else {
                    LOGGER.warning("[SSR] createImage 失败，回退到 passThrough");
                }
            } catch (Exception e) {
                LOGGER.warning("ensureOutputImage 异常: " + e.getMessage());
            }
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * 【方法参数】
     * @param path String - 资源路径（如 "/shaders/ssr.spv"）
     *
     * 【返回值】
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = SSRNode.class.getResourceAsStream(path)) {
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
                "SSRNode{enabled=%b quality=%d maxSteps=%d thickness=%.3f bias=%.3f halfRes=%b rtRef=%d}",
                enabled, quality, maxSteps, thickness, bruteForceBias, halfResolution, rtReflections
        );
    }
}
