// Renderium - 光影系统 v2.0
// 镜头光晕 (Lens Flare) 后处理节点
//
// 核心算法:
//   1. 亮度提取：阈值过滤 → 仅保留高亮像素
//   2. 鬼影生成：沿像素到光源中心连线采样，带偏移
//   3. 条纹生成：4 方向（十字）条纹从高亮特征扩散
//   4. 色散：每个鬼影轻微 RGB 偏移
//   5. 合成：加法混合叠加到场景
//
// 性能预算:
//   - 亮度提取: < 0.1ms/帧
//   - 鬼影 + 条纹: < 0.5ms/帧
//   - 合成: < 0.1ms/帧
//   - 总计: 0.3-1ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * 镜头光晕节点 (Lens Flare)
 * <p>
 * 模拟真实镜头的光晕效果，包括鬼影、条纹和色散。
 * 需要色调映射后的场景纹理。
 * <p>
 * Blender: Lens Flare (Compositor) | Unreal: Lens Flare | Unity: Lens Flare
 * <p>
 * GPU 开销：0.3-1ms (1080p)
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
 * ├─ Step 3: 亮度提取                                            │
 * │   阈值过滤，仅保留亮度 > threshold 的像素                      │
 *     ↓                                                          │
 * ├─ Step 4: 鬼影生成                                            │
 * │   对每个鬼影，沿像素到光源中心连线采样，带偏移                  │
 *     ↓                                                          │
 * ├─ Step 5: 条纹生成                                            │
 * │   4 方向（十字）条纹从高亮特征扩散                             │
 *     ↓                                                          │
 * ├─ Step 6: 色散                                               │
 * │   每个鬼影轻微 RGB 偏移，模拟色散效果                          │
 *     ↓                                                          │
 * ├─ Step 7: 合成                                               │
 * │   加法混合叠加到场景                                          │
 *     ↓                                                          │
 * └─ Step 8: 返回处理后的纹理句柄                                │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>intensity</td><td>0.5</td><td>光晕强度，0.0-2.0</td></tr>
 *   <tr><td>ghostCount</td><td>4</td><td>鬼影数量，1-8</td></tr>
 *   <tr><td>streakLength</td><td>0.5</td><td>条纹长度，0.1-2.0</td></tr>
 *   <tr><td>threshold</td><td>0.85</td><td>亮度阈值，0.5-1.0</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 5.5.0
 */
public class LensFlareNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(LensFlareNode.class.getName());

    /** SPIR-V 着色器资源路径 */
    private static final String SHADER_PATH = "/shaders/lens_flare.spv";

    // ==================== 参数边界 ====================

    /** 光晕强度下界 */
    private static final float INTENSITY_MIN = 0.0f;

    /** 光晕强度上界 */
    private static final float INTENSITY_MAX = 2.0f;

    /** 光晕强度默认值 */
    private static final float INTENSITY_DEFAULT = 0.5f;

    /** 鬼影数量下界 */
    private static final int GHOST_COUNT_MIN = 1;

    /** 鬼影数量上界 */
    private static final int GHOST_COUNT_MAX = 8;

    /** 鬼影数量默认值 */
    private static final int GHOST_COUNT_DEFAULT = 4;

    /** 条纹长度下界 */
    private static final float STREAK_LENGTH_MIN = 0.1f;

    /** 条纹长度上界 */
    private static final float STREAK_LENGTH_MAX = 2.0f;

    /** 条纹长度默认值 */
    private static final float STREAK_LENGTH_DEFAULT = 0.5f;

    /** 亮度阈值下界 */
    private static final float THRESHOLD_MIN = 0.5f;

    /** 亮度阈值上界 */
    private static final float THRESHOLD_MAX = 1.0f;

    /** 亮度阈值默认值 */
    private static final float THRESHOLD_DEFAULT = 0.85f;

    // ==================== 可调参数 ====================

    /** 是否启用镜头光晕 */
    private volatile boolean enabled = false;

    /** 光晕强度 */
    private volatile float intensity = INTENSITY_DEFAULT;

    /** 鬼影数量 */
    private volatile int ghostCount = GHOST_COUNT_DEFAULT;

    /** 条纹长度 */
    private volatile float streakLength = STREAK_LENGTH_DEFAULT;

    /** 亮度阈值，仅亮度高于此值的像素参与光晕计算 */
    private volatile float threshold = THRESHOLD_DEFAULT;

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    /** 输出 Image / ImageView（compute shader 写入目标） */
    private volatile long outputImage = 0L;
    private volatile long outputImageView = 0L;
    private volatile int lastOutputWidth = 0;
    private volatile int lastOutputHeight = 0;

    // ==================== 构造函数 ====================

    /**
     * 构造镜头光晕节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "lens_flare"</li>
     *   <li>DisplayName: "Lens Flare (镜头光晕)"</li>
     *   <li>Category: {@link PipelineNode.Category#POST_PROCESS}</li>
     *   <li>Priority: 175（后处理阶段，色调映射之后）</li>
     *   <li>依赖: ["tonemap"]</li>
     * </ul>
     */
    public LensFlareNode() {
        super(
                "lens_flare",                                   // 唯一标识符（kebab-case）
                "Lens Flare (镜头光晕)",                        // 显示名称
                PipelineNode.Category.POST_PROCESS,            // 分类：后处理阶段
                175,                                           // 优先级
                new String[]{"tonemap"}                        // 依赖：色调映射节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行镜头光晕计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>亮度提取：阈值过滤高亮像素</li>
     *   <li>鬼影生成：沿像素到光源中心连线采样</li>
     *   <li>条纹生成：4 方向十字条纹</li>
     *   <li>色散：轻微 RGB 偏移</li>
     *   <li>合成：加法混合叠加到场景</li>
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
     * - 1080p 目标: 0.3-1ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[LensFlare] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curIntensity = this.intensity;
        int curGhostCount = this.ghostCount;
        float curStreakLength = this.streakLength;
        float curThreshold = this.threshold;

        long colorTexture = inputResources[0];

        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        ensureOutputImage(context);
        if (outputImageView == 0L) return passThrough(inputResources);

        try {
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L || descriptorSet == 0L) return passThrough(inputResources);

            // 更新 image descriptors
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 0, colorTexture, 0L, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 1, outputImageView, 0L);

            // 构建 PushConstants: vec2 screenCenter, int ghostCount, float strength, float threshold (32 bytes)
            MemorySegment params = PerFrameArena.allocate(32L);
            params.set(ValueLayout.JAVA_FLOAT, 0, 0.5f);  // screenCenter.x
            params.set(ValueLayout.JAVA_FLOAT, 4, 0.5f);  // screenCenter.y
            params.set(ValueLayout.JAVA_INT, 8, curGhostCount);
            params.set(ValueLayout.JAVA_FLOAT, 12, curIntensity);
            params.set(ValueLayout.JAVA_FLOAT, 16, curThreshold);
            params.set(ValueLayout.JAVA_FLOAT, 20, 0.0f); // padding

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
            LOGGER.fine("[LensFlare] dispatch 失败: " + t.getMessage());
        }

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[LensFlare] 完成 | intensity=%.2f ghosts=%d streak=%.2f threshold=%.2f | %.1fμs",
                curIntensity, curGhostCount, curStreakLength, curThreshold, elapsedMicros
        ));

        return (outputImageView != 0L) ? outputImageView : passThrough(inputResources);
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
                "[LensFlare] 初始化成功 | intensity=%.2f ghosts=%d streak=%.2f threshold=%.2f",
                this.intensity, this.ghostCount, this.streakLength, this.threshold
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
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device != 0L) {
            if (computePipeline != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipeline", device, computePipeline, 0L); } catch (Throwable ignored) {}
                computePipeline = 0L;
            }
            if (pipelineLayout != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", device, pipelineLayout, 0L); } catch (Throwable ignored) {}
                pipelineLayout = 0L;
            }
        }
        VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();

        if (outputImageView != 0L) {
            mgr.destroyView(outputImageView);
            outputImageView = 0L;
        }
        if (outputImage != 0L) {
            mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(outputImage, 0, lastOutputWidth, lastOutputHeight, 87, VulkanGPUResourceManager.ResourceType.IMAGE));
            outputImage = 0L;
        }
        lastOutputWidth = 0;
        lastOutputHeight = 0;

        LOGGER.fine("[LensFlare] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用镜头光晕
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
     * 设置光晕强度
     * <p>
     * 值会被钳制到有效范围 [{@value #INTENSITY_MIN}, {@value #INTENSITY_MAX}]。
     *
     * @param v 光晕强度（0.0 ~ 2.0）
     */
    public void setIntensity(float v) {
        this.intensity = Math.max(INTENSITY_MIN, Math.min(INTENSITY_MAX, v));
    }

    /**
     * 设置鬼影数量
     * <p>
     * 值会被钳制到有效范围 [{@value #GHOST_COUNT_MIN}, {@value #GHOST_COUNT_MAX}]。
     *
     * @param v 鬼影数量（1 ~ 8）
     */
    public void setGhostCount(int v) {
        this.ghostCount = Math.max(GHOST_COUNT_MIN, Math.min(GHOST_COUNT_MAX, v));
    }

    /**
     * 设置条纹长度
     * <p>
     * 值会被钳制到有效范围 [{@value #STREAK_LENGTH_MIN}, {@value #STREAK_LENGTH_MAX}]。
     *
     * @param v 条纹长度（0.1 ~ 2.0）
     */
    public void setStreakLength(float v) {
        this.streakLength = Math.max(STREAK_LENGTH_MIN, Math.min(STREAK_LENGTH_MAX, v));
    }

    /**
     * 设置亮度阈值
     * <p>
     * 值会被钳制到有效范围 [{@value #THRESHOLD_MIN}, {@value #THRESHOLD_MAX}]。
     * 仅亮度高于此值的像素参与光晕计算。
     *
     * @param v 亮度阈值（0.5 ~ 1.0）
     */
    public void setThreshold(float v) {
        this.threshold = Math.max(THRESHOLD_MIN, Math.min(THRESHOLD_MAX, v));
    }

    // ==================== 辅助方法 ====================

    /**
     * 确保 Compute Pipeline 已创建
     * <p>
     * 懒加载模式：首次 execute() 时加载 SPIR-V 并创建 Pipeline。
     * 线程安全：双重检查锁定（DCL），synchronized 确保单次创建。
     * 使用 {@link ComputePipelineHelper#createComputePipeline} 创建真实管线。
     *
     * LensFlare Binding 配置:
     *   binding 0 = COMBINED_IMAGE_SAMPLER (color 纹理)
     *   binding 1 = STORAGE_IMAGE (output 输出纹理)
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
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
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
     * 【方法参数】
     * @param context RenderContext - 渲染上下文（用于获取输出尺寸）
     *
     * 【方法签名】
     * void ensureOutputImage(RenderContext context)
     */
    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();

        if (outputImage != 0L && w == lastOutputWidth && h == lastOutputHeight) return;

        synchronized (this) {
            if (outputImage != 0L && w == lastOutputWidth && h == lastOutputHeight) return;

            VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
            long dev = VulkanDeviceHolder.getInstance().getDevice();

            if (outputImageView != 0L) {
                try { mgr.destroyView(outputImageView); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                try { mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastOutputWidth, lastOutputHeight, 87, VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {}
                outputImage = 0L;
            }

            int format = 87;
            int usageFlags = 0x20 | 0x10;

            try {
                VulkanGPUResourceManager.GpuResource imgRes = mgr.createImage(w, h, format, usageFlags,
                        VmaMemoryPools.PoolType.RENDER_TARGET);
                if (imgRes != null && imgRes.handle != 0L) {
                    outputImage = imgRes.handle;
                    outputImageView = mgr.createView(outputImage, format, 1);
                    lastOutputWidth = w;
                    lastOutputHeight = h;
                    LOGGER.fine(String.format("[LensFlare] 输出 Image 创建成功 [%dx%d] image=0x%X view=0x%X",
                            w, h, outputImage, outputImageView));
                } else {
                    LOGGER.warning("[LensFlare] createImage 返回无效资源");
                }
            } catch (Exception e) {
                LOGGER.warning("[LensFlare] ensureOutputImage 异常: " + e.getMessage());
            }
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * 【方法参数】
     * @param path String - 资源路径（如 "/shaders/lens_flare.spv"）
     *
     * 【返回值】
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = LensFlareNode.class.getResourceAsStream(path)) {
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
                "LensFlareNode{enabled=%b intensity=%.2f ghosts=%d streak=%.2f threshold=%.2f}",
                enabled, intensity, ghostCount, streakLength, threshold
        );
    }
}
