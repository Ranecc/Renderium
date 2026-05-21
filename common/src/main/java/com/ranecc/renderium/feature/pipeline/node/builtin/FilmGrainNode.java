// Renderium - 光影系统 v2.0
// 胶片颗粒 (Film Grain) 后处理节点
//
// 核心算法:
//   1. 每像素根据屏幕位置 + 种子生成伪随机数 (hash)
//   2. noise = (hash - 0.5) * strength
//   3. output = input + noise
//
// 性能预算:
//   - 噪声生成 + 叠加: < 0.1ms/帧
//   - 总计: < 0.1ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanSyncManager;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * 胶片颗粒节点 (Film Grain)
 * <p>
 * 模拟胶片颗粒感，通过伪随机噪声叠加到场景纹理上。
 * 需要色调映射后的场景纹理。
 * <p>
 * Blender: Add Noise (Film) | Unreal: Film Grain | Unity: Film Grain
 * <p>
 * GPU 开销：<0.1ms (1080p)
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
 * ├─ Step 3: 生成伪随机噪声                                       │
 * │   hash = hash(vec3(screenPos, seed))                         │
 * │   noise = (hash - 0.5) * strength                            │
 *     ↓                                                          │
 * ├─ Step 4: 叠加到场景                                          │
 * │   output = input + noise                                     │
 *     ↓                                                          │
 * └─ Step 5: 返回处理后的纹理句柄                                │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>strength</td><td>0.05</td><td>颗粒强度，0.0-0.5，越大颗粒感越强</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#POST_PROCESS
 * @since 5.5.0
 */
public class FilmGrainNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(FilmGrainNode.class.getName());

    /** SPIR-V 着色器资源路径 */
    private static final String SHADER_PATH = "/shaders/film_grain.spv";

    // ==================== 参数边界 ====================

    /** 颗粒强度下界 */
    private static final float STRENGTH_MIN = 0.0f;

    /** 颗粒强度上界 */
    private static final float STRENGTH_MAX = 0.5f;

    /** 颗粒强度默认值 */
    private static final float STRENGTH_DEFAULT = 0.05f;

    // ==================== 可调参数 ====================

    /** 是否启用胶片颗粒 */
    private volatile boolean enabled = true;

    /** 颗粒强度，控制噪声幅度 */
    private volatile float strength = STRENGTH_DEFAULT;

    /** 随机种子（每帧变化产生动态颗粒） */
    private volatile float seed = 0.0f;

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    // ==================== 构造函数 ====================

    /**
     * 构造胶片颗粒节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "film_grain"</li>
     *   <li>DisplayName: "Film Grain (胶片颗粒)"</li>
     *   <li>Category: {@link PipelineNode.Category#POST_PROCESS}</li>
     *   <li>Priority: 240（后处理最末阶段）</li>
     *   <li>依赖: ["tonemap"]</li>
     * </ul>
     */
    public FilmGrainNode() {
        super(
                "film_grain",                                   // 唯一标识符（kebab-case）
                "Film Grain (胶片颗粒)",                        // 显示名称
                PipelineNode.Category.POST_PROCESS,            // 分类：后处理阶段
                240,                                           // 优先级（后处理最末）
                new String[]{"tonemap"}                        // 依赖：色调映射节点
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行胶片颗粒计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>生成伪随机噪声</li>
     *   <li>叠加到场景颜色</li>
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
     * - 1080p 目标: < 0.1ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[FilmGrain] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curStrength = this.strength;
        // 每帧更新种子，产生动态颗粒效果
        float curSeed = this.seed + (float) Math.random() * 100.0f;
        this.seed = curSeed;

        long colorTexture = inputResources[0];
        long outputTexture = colorTexture; // 就地写入

        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        try {
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L || descriptorSet == 0L) return passThrough(inputResources);

            // 更新 image descriptors
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 0, colorTexture, 0L, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 1, outputTexture, 0L);

            // 构建 PushConstants: float strength, float seed (32 bytes)
            MemorySegment params = PerFrameArena.allocate(32L);
            params.set(ValueLayout.JAVA_FLOAT, 0, curStrength);
            params.set(ValueLayout.JAVA_FLOAT, 4, curSeed);
            // 剩余 24 bytes 保持 0

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
            LOGGER.fine("[FilmGrain] dispatch 失败: " + t.getMessage());
        }

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[FilmGrain] 完成 | strength=%.4f seed=%.2f | %.1fμs",
                curStrength, curSeed, elapsedMicros
        ));

        return outputTexture;
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
                "[FilmGrain] 初始化成功 | strength=%.4f",
                this.strength
        ));
        return true;
    }

    /**
     * 节点资源释放钩子
     * <p>
     * 重置种子值。
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
        this.seed = 0.0f;
        LOGGER.fine("[FilmGrain] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用胶片颗粒
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
     * 设置颗粒强度
     * <p>
     * 值会被钳制到有效范围 [{@value #STRENGTH_MIN}, {@value #STRENGTH_MAX}]。
     *
     * @param v 颗粒强度（0.0 ~ 0.5）
     */
    public void setStrength(float v) {
        this.strength = Math.max(STRENGTH_MIN, Math.min(STRENGTH_MAX, v));
    }

    // ==================== 辅助方法 ====================

    /**
     * 确保 Compute Pipeline 已创建
     * <p>
     * 懒加载模式：首次 execute() 时加载 SPIR-V 并创建 Pipeline。
     * 线程安全：双重检查锁定（DCL），synchronized 确保单次创建。
     * 使用 {@link ComputePipelineHelper#createComputePipeline} 创建真实管线。
     *
     * FilmGrain Binding 配置:
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
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * 【方法参数】
     * @param path String - 资源路径（如 "/shaders/film_grain.spv"）
     *
     * 【返回值】
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = FilmGrainNode.class.getResourceAsStream(path)) {
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
                "FilmGrainNode{enabled=%b strength=%.4f}",
                enabled, strength
        );
    }
}
