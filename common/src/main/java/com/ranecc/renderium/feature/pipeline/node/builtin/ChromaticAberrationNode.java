// Renderium - 光影系统 v2.0
// 色差 (Chromatic Aberration) 后处理节点
//
// 核心算法:
//   1. 计算每通道偏移：R 偏移 = -strength, B 偏移 = +strength, G = 0
//   2. 若 radial：偏移按距中心距离缩放
//   3. 分别采样 R, G, B 通道，使用不同 UV 偏移
//   4. 合成：output = vec3(sampleR, sampleG, sampleB)
//
// 性能预算:
//   - 采样 + 合成: < 0.3ms/帧
//   - 总计: < 0.3ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
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
 * 色差节点 (Chromatic Aberration)
 * <p>
 * 模拟真实镜头的色差效果，通过 RGB 通道分离采样实现。
 * 需要色调映射后的场景纹理。
 * <p>
 * Blender: Lens Distortion | Unreal: Chromatic Aberration | Unity: Chromatic Aberration
 * <p>
 * GPU 开销：<0.3ms (1080p)
 * 短路条件：enabled == false OR strength == 0 → 直接返回输入纹理
 *
 * <h2>算法概述：</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ execute() 入口                                                │
 *     ↓                                                          │
 * ├─ Step 1: 短路检查（enabled == false OR strength == 0）        │
 *     ↓                                                          │
 * ├─ Step 2: 输入校验（至少需要颜色纹理 1 张）                     │
 *     ↓                                                          │
 * ├─ Step 3: 计算每通道 UV 偏移                                   │
 * │   R 偏移 = -strength, B 偏移 = +strength, G = 0              │
 * │   若 radial：偏移按距中心距离缩放                               │
 *     ↓                                                          │
 * ├─ Step 4: 分通道采样                                          │
 * │   sampleR = texture(input, uv + offsetR)                     │
 * │   sampleG = texture(input, uv)                               │
 * │   sampleB = texture(input, uv + offsetB)                     │
 *     ↓                                                          │
 * ├─ Step 5: 合成输出                                            │
 * │   output = vec3(sampleR.r, sampleG.g, sampleB.b)             │
 *     ↓                                                          │
 * └─ Step 6: 返回处理后的纹理句柄                                │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>strength</td><td>0.003</td><td>色差强度，0.0-0.05，越大越明显</td></tr>
 *   <tr><td>radial</td><td>true</td><td>径向模式，偏移按距中心距离缩放</td></tr>
 *   <tr><td>centerOffsetX</td><td>0.0</td><td>中心点 X 偏移，-1.0~1.0</td></tr>
 *   <tr><td>centerOffsetY</td><td>0.0</td><td>中心点 Y 偏移，-1.0~1.0</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 5.5.0
 */
public class ChromaticAberrationNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ChromaticAberrationNode.class.getName());

    /** SPIR-V 着色器资源路径 */
    private static final String SHADER_PATH = "/shaders/chromatic_aberration.spv";

    // ==================== 参数边界 ====================

    /** 色差强度下界 */
    private static final float STRENGTH_MIN = 0.0f;

    /** 色差强度上界 */
    private static final float STRENGTH_MAX = 0.05f;

    /** 色差强度默认值 */
    private static final float STRENGTH_DEFAULT = 0.003f;

    /** 中心点偏移下界 */
    private static final float CENTER_OFFSET_MIN = -1.0f;

    /** 中心点偏移上界 */
    private static final float CENTER_OFFSET_MAX = 1.0f;

    /** 中心点偏移默认值 */
    private static final float CENTER_OFFSET_DEFAULT = 0.0f;

    // ==================== 可调参数 ====================

    /** 是否启用色差 */
    private volatile boolean enabled = false;

    /** 色差强度，控制 RGB 通道分离程度 */
    private volatile float strength = STRENGTH_DEFAULT;

    /** 是否使用径向模式（偏移按距中心距离缩放） */
    private volatile boolean radial = true;

    /** 中心点 X 偏移 */
    private volatile float centerOffsetX = CENTER_OFFSET_DEFAULT;

    /** 中心点 Y 偏移 */
    private volatile float centerOffsetY = CENTER_OFFSET_DEFAULT;

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    /** 输出 Image / ImageView（独立于输入，供 STORAGE_IMAGE binding=1 写入） */
    private volatile long outputImage = 0L;
    private volatile long outputImageView = 0L;
    private volatile int lastOutputWidth = 0;
    private volatile int lastOutputHeight = 0;
    private volatile VulkanGPUResourceManager.GpuResource outputGpuResource = null;

    // ==================== 构造函数 ====================

    /**
     * 构造色差节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "chromatic_aberration"</li>
     *   <li>DisplayName: "Chromatic Aberration (色差)"</li>
     *   <li>Category: {@link PipelineNode.Category#POST_PROCESS}</li>
     *   <li>Priority: 180（后处理阶段，色调映射之后）</li>
     *   <li>依赖: ["tonemap"]</li>
     * </ul>
     */
    public ChromaticAberrationNode() {
        super(
                "chromatic_aberration",                         // 唯一标识符（kebab-case）
                "Chromatic Aberration (色差)",                  // 显示名称
                PipelineNode.Category.POST_PROCESS,            // 分类：后处理阶段
                180,                                           // 优先级
                new String[]{"tonemap"}                        // 依赖：色调映射节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行色差计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 或 strength == 0 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>计算每通道 UV 偏移</li>
     *   <li>分通道采样 R, G, B</li>
     *   <li>合成输出</li>
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
     * - 1080p 目标: < 0.3ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 短路：强度为 0 时无效果
        if (strength == 0.0f) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[ChromaticAberration] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curStrength = this.strength;
        boolean curRadial = this.radial;
        float curCenterOffsetX = this.centerOffsetX;
        float curCenterOffsetY = this.centerOffsetY;

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

            // 构建 PushConstants: float strength, int screenWidth, int screenHeight, float radial, float centerX, float centerY (32 bytes)
            MemorySegment params = PerFrameArena.allocate(32L);
            params.set(ValueLayout.JAVA_FLOAT, 0, curStrength);
            params.set(ValueLayout.JAVA_INT, 4, context.getWidth());
            params.set(ValueLayout.JAVA_INT, 8, context.getHeight());
            params.set(ValueLayout.JAVA_FLOAT, 12, curRadial ? 1.0f : 0.0f);
            params.set(ValueLayout.JAVA_FLOAT, 16, curCenterOffsetX);
            params.set(ValueLayout.JAVA_FLOAT, 20, curCenterOffsetY);

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
            LOGGER.fine("[ChromaticAberration] dispatch 失败: " + t.getMessage());
        }

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[ChromaticAberration] 完成 | strength=%.4f radial=%b center=(%.2f,%.2f) | %.1fμs",
                curStrength, curRadial, curCenterOffsetX, curCenterOffsetY, elapsedMicros
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
                "[ChromaticAberration] 初始化成功 | strength=%.4f radial=%b center=(%.2f,%.2f)",
                this.strength, this.radial, this.centerOffsetX, this.centerOffsetY
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
            if (outputGpuResource != null) {
                try { VulkanGPUResourceManager.getInstance().releaseResource(outputGpuResource); } catch (Throwable ignored) {}
                outputGpuResource = null;
                outputImage = 0L;
                outputImageView = 0L;
                lastOutputWidth = 0;
                lastOutputHeight = 0;
            }
        }
        LOGGER.fine("[ChromaticAberration] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用色差
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
     * 设置色差强度
     * <p>
     * 值会被钳制到有效范围 [{@value #STRENGTH_MIN}, {@value #STRENGTH_MAX}]。
     *
     * @param v 色差强度（0.0 ~ 0.05）
     */
    public void setStrength(float v) {
        this.strength = Math.max(STRENGTH_MIN, Math.min(STRENGTH_MAX, v));
    }

    /**
     * 设置是否使用径向模式
     *
     * @param v 是否使用径向模式（偏移按距中心距离缩放）
     */
    public void setRadial(boolean v) { this.radial = v; }

    /**
     * 设置中心点 X 偏移
     * <p>
     * 值会被钳制到有效范围 [{@value #CENTER_OFFSET_MIN}, {@value #CENTER_OFFSET_MAX}]。
     *
     * @param v 中心点 X 偏移（-1.0 ~ 1.0）
     */
    public void setCenterOffsetX(float v) {
        this.centerOffsetX = Math.max(CENTER_OFFSET_MIN, Math.min(CENTER_OFFSET_MAX, v));
    }

    /**
     * 设置中心点 Y 偏移
     * <p>
     * 值会被钳制到有效范围 [{@value #CENTER_OFFSET_MIN}, {@value #CENTER_OFFSET_MAX}]。
     *
     * @param v 中心点 Y 偏移（-1.0 ~ 1.0）
     */
    public void setCenterOffsetY(float v) {
        this.centerOffsetY = Math.max(CENTER_OFFSET_MIN, Math.min(CENTER_OFFSET_MAX, v));
    }

    // ==================== 辅助方法 ====================

    /**
     * 确保 Compute Pipeline 已创建
     * <p>
     * 懒加载模式：首次 execute() 时加载 SPIR-V 并创建 Pipeline。
     * 线程安全：双重检查锁定（DCL），synchronized 确保单次创建。
     * 使用 {@link ComputePipelineHelper#createComputePipeline} 创建真实管线。
     *
     * ChromaticAberration Binding 配置:
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

    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();
        if (outputImage != 0L && lastOutputWidth == w && lastOutputHeight == h) return;
        synchronized (this) {
            if (outputImage != 0L && lastOutputWidth == w && lastOutputHeight == h) return;
            VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
            int format = 87;
            int usageFlags = 0x20 | 0x10;
            var res = mgr.createImage(w, h, format, usageFlags, VmaMemoryPools.PoolType.RENDER_TARGET);
            if (res != null && res.isValid()) {
                long newImage = res.handle;
                long newView = mgr.createView(newImage, format, 1);
                var oldRes = outputGpuResource;
                outputImage = newImage;
                outputImageView = newView;
                outputGpuResource = res;
                lastOutputWidth = w;
                lastOutputHeight = h;
                if (oldRes != null) {
                    try { mgr.releaseResource(oldRes); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * 【方法参数】
     * @param path String - 资源路径（如 "/shaders/chromatic_aberration.spv"）
     *
     * 【返回值】
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = ChromaticAberrationNode.class.getResourceAsStream(path)) {
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
                "ChromaticAberrationNode{enabled=%b strength=%.4f radial=%b center=(%.2f,%.2f)}",
                enabled, strength, radial, centerOffsetX, centerOffsetY
        );
    }
}
