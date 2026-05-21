// Renderium - 光影系统 v2.0
// 体积雾 (Volumetric Fog) 光照节点
//
// 核心算法:
//   1. 构建 Froxel 网格 (16x16x64)
//   2. 光线步进：每个 Froxel 沿视线方向采样
//   3. 密度计算：baseDensity * exp(-heightFalloff * (worldY - heightBase))
//   4. 散射计算：Henyey-Greenstein(cos(theta), g)
//   5. 光照积分：累加散射贡献
//   6. 双边滤波：2x2x2 Froxel 模糊（消除锯齿）
//   7. 合成：fogContribution = 1 - exp(-opticalDepth)
//
// 性能预算:
//   - Froxel 构建: < 0.3ms/帧
//   - 光线步进: < 1.5ms/帧
//   - 双边滤波: < 0.5ms/帧
//   - 合成: < 0.3ms/帧

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * 体积雾节点 (Volumetric Fog)
 * <p>
 * Froxel 体积雾实现，支持光线步进和体积光散射。
 * 替代 MC 原版线性深度雾，提供物理正确的体积雾效果。
 * <p>
 * Blender: Mist | Unreal: Exponential Height Fog | Unity: Volumetric Fog
 * <p>
 * GPU 开销：1-3ms (1080p)
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
 * ├─ Step 3: 构建 Froxel 网格 (16x16x64)                         │
 * │   每个 Froxel 存储深度区间 [near, far]                        │
 *     ↓                                                          │
 * ├─ Step 4: 光线步进                                             │
 * │   密度 = baseDensity * exp(-heightFalloff * (worldY - base)) │
 * │   散射 = Henyey-Greenstein(cos(theta), g)                    │
 *     ↓                                                          │
 * ├─ Step 5: 光照积分（累加散射贡献）                              │
 *     ↓                                                          │
 * ├─ Step 6: 双边滤波（2x2x2 Froxel 模糊，消除锯齿）              │
 *     ↓                                                          │
 * ├─ Step 7: 合成                                                 │
 * │   fogContribution = 1 - exp(-opticalDepth)                   │
 * │   finalColor = lerp(sceneColor, fogColor, fogContribution)   │
 *     ↓                                                          │
 * └─ Step 8: 返回处理后的纹理句柄                                  │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>参数调优建议：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>默认值</th><th>效果</th></tr>
 *   <tr><td>fogDensity</td><td>0.5</td><td>基础雾密度，越高越浓（0.0~10.0）</td></tr>
 *   <tr><td>fogHeightFalloff</td><td>0.01</td><td>高度衰减率，越高雾越集中在低处</td></tr>
 *   <tr><td>raySteps</td><td>32</td><td>光线步进数，越高越精细（8~128）</td></tr>
 *   <tr><td>scatteringAnisotropy</td><td>0.7</td><td>HG g 参数，前向散射强度（-1~1）</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @see PipelineNode.Category#LIGHTING
 * @since 5.5.0
 */
public class VolumetricFogNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(VolumetricFogNode.class.getName());

    /** SPIR-V 着色器资源路径 */
    private static final String SHADER_PATH = "/shaders/volumetric_fog.spv";

    // ==================== Froxel 配置 ====================

    /** Froxel X 轴分辨率 */
    private static final int FROXEL_X = 16;

    /** Froxel Y 轴分辨率 */
    private static final int FROXEL_Y = 16;

    /** Froxel Z 轴分辨率（深度方向） */
    private static final int FROXEL_Z = 64;

    /** Froxel 总数 */
    private static final int FROXEL_TOTAL = FROXEL_X * FROXEL_Y * FROXEL_Z;

    // ==================== 参数边界 ====================

    /** 雾密度下界 */
    private static final float FOG_DENSITY_MIN = 0.0f;

    /** 雾密度上界 */
    private static final float FOG_DENSITY_MAX = 10.0f;

    /** 雾密度默认值 */
    private static final float FOG_DENSITY_DEFAULT = 0.5f;

    /** 雾高度基准下界 */
    private static final float FOG_HEIGHT_MIN = -256.0f;

    /** 雾高度基准上界 */
    private static final float FOG_HEIGHT_MAX = 512.0f;

    /** 雾高度基准默认值 */
    private static final float FOG_HEIGHT_DEFAULT = 64.0f;

    /** 光线步进数下界 */
    private static final int RAY_STEPS_MIN = 8;

    /** 光线步进数上界 */
    private static final int RAY_STEPS_MAX = 128;

    /** 光线步进数默认值 */
    private static final int RAY_STEPS_DEFAULT = 32;

    // ==================== 可调参数 ====================

    /** 是否启用体积雾 */
    private volatile boolean enabled = false;

    /** 基础雾密度 */
    private volatile float fogDensity = FOG_DENSITY_DEFAULT;

    /** 高度衰减率，越高雾越集中在低处 */
    private volatile float fogHeightFalloff = 0.01f;

    /** 高度基准，雾密度最大的高度 */
    private volatile float fogHeightBase = FOG_HEIGHT_DEFAULT;

    /** 光线步进数量，越高越精细但越慢 */
    private volatile int raySteps = RAY_STEPS_DEFAULT;

    /** 散射各向异性参数（Henyey-Greenstein g 参数），-1 后向散射，0 各向同性，1 前向散射 */
    private volatile float scatteringAnisotropy = 0.7f;

    /** 雾颜色 R 分量 */
    private volatile float fogColorR = 0.8f;

    /** 雾颜色 G 分量 */
    private volatile float fogColorG = 0.85f;

    /** 雾颜色 B 分量 */
    private volatile float fogColorB = 0.9f;

    /** Vulkan Compute Pipeline 句柄，0 表示未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    /** Pipeline 是否已创建 */
    private volatile boolean pipelineCreated = false;

    // ==================== 构造函数 ====================

    /**
     * 构造体积雾节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "volumetric_fog"</li>
     *   <li>DisplayName: "Volumetric Fog (体积雾)"</li>
     *   <li>Category: {@link PipelineNode.Category#LIGHTING}</li>
     *   <li>Priority: 155（在直接光照之后、后处理之前）</li>
     *   <li>依赖: ["gbuffer_geometry", "direct_light"]</li>
     * </ul>
     */
    public VolumetricFogNode() {
        super(
                "volumetric_fog",                               // 唯一标识符（kebab-case）
                "Volumetric Fog (体积雾)",                      // 显示名称
                PipelineNode.Category.LIGHTING,                // 分类：光照阶段
                155,                                           // 优先级
                new String[]{"gbuffer_geometry", "direct_light"} // 依赖：G-Buffer + 直接光照
        );
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行体积雾计算
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>短路检查：enabled == false 时直接返回输入纹理</li>
     *   <li>输入校验：至少需要颜色纹理 1 张</li>
     *   <li>构建 Froxel 网格</li>
     *   <li>光线步进计算密度和散射</li>
     *   <li>光照积分累加散射贡献</li>
     *   <li>双边滤波消除锯齿</li>
     *   <li>合成最终颜色</li>
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
     * - 1080p / 32 steps 目标: < 3ms (Compute Shader)
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[VolFog] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curDensity = this.fogDensity;
        float curFalloff = this.fogHeightFalloff;
        int   curSteps   = this.raySteps;
        float curAniso   = this.scatteringAnisotropy;

        MemorySegment params = PerFrameArena.allocate(32L);
        params.set(ValueLayout.JAVA_FLOAT, 0, curDensity);
        params.set(ValueLayout.JAVA_FLOAT, 4, curFalloff);
        params.set(ValueLayout.JAVA_INT, 8, curSteps);
        params.set(ValueLayout.JAVA_FLOAT, 12, curAniso);
        params.set(ValueLayout.JAVA_INT, 16, context.getWidth());
        params.set(ValueLayout.JAVA_INT, 20, context.getHeight());

        // 确保 Compute Pipeline 已创建（加载 SPIR-V 着色器）
        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        // 实际 Vulkan Compute Shader 调度
        // 1. 更新 descriptor set 绑定输入/输出纹理
        long dev = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L && descriptorSet != 0L) {
            ComputePipelineHelper.updateImageDescriptor(dev, descriptorSet, 0, inputResources.length > 1 ? inputResources[1] : 0L, 0L, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 1, inputResources[0], 0L);
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
            LOGGER.fine("[VolFog] dispatch 失败: " + t.getMessage());
        }

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[VolFog] 完成 | density=%.2f falloff=%.4f steps=%d aniso=%.2f | pipeline=0x%X | %.1fμs",
                curDensity, curFalloff, curSteps, curAniso, computePipeline, elapsedMicros
        ));

        // 当前返回输入纹理（待 Vulkan 命令缓冲区集成后替换）
        return inputResources[0];
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
                "[VolFog] 初始化成功 | density=%.2f falloff=%.4f steps=%d aniso=%.2f froxel=%d",
                this.fogDensity, this.fogHeightFalloff, this.raySteps,
                this.scatteringAnisotropy, FROXEL_TOTAL
        ));
        return true;
    }

    /**
     * 节点资源释放钩子
     */
    @Override
    protected void onDispose() {
        LOGGER.fine("[VolFog] 资源已释放");
    }

    // ==================== 参数 Setter ====================

    /**
     * 设置是否启用体积雾
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
     * 设置基础雾密度
     * <p>
     * 值会被钳制到有效范围 [{@value #FOG_DENSITY_MIN}, {@value #FOG_DENSITY_MAX}]。
     *
     * @param v 雾密度（0.0 ~ 10.0）
     */
    public void setFogDensity(float v) {
        this.fogDensity = Math.max(FOG_DENSITY_MIN, Math.min(FOG_DENSITY_MAX, v));
    }

    /**
     * 设置高度衰减率
     * <p>
     * 值会被钳制到 ≥ 0。
     *
     * @param v 高度衰减率
     */
    public void setFogHeightFalloff(float v) { this.fogHeightFalloff = Math.max(0.0f, v); }

    /**
     * 设置高度基准
     * <p>
     * 无钳制，允许任意世界坐标高度。
     *
     * @param v 高度基准（世界坐标 Y）
     */
    public void setFogHeightBase(float v) { this.fogHeightBase = v; }

    /**
     * 设置光线步进数量
     * <p>
     * 值会被钳制到有效范围 [{@value #RAY_STEPS_MIN}, {@value #RAY_STEPS_MAX}]。
     *
     * @param v 步进数量（8 ~ 128）
     */
    public void setRaySteps(int v) {
        this.raySteps = Math.max(RAY_STEPS_MIN, Math.min(RAY_STEPS_MAX, v));
    }

    /**
     * 设置散射各向异性参数（Henyey-Greenstein g 参数）
     * <p>
     * 值会被钳制到有效范围 [-1.0, 1.0]。
     * -1 = 后向散射，0 = 各向同性，1 = 前向散射。
     *
     * @param v 各向异性参数（-1.0 ~ 1.0）
     */
    public void setScatteringAnisotropy(float v) {
        this.scatteringAnisotropy = Math.max(-1.0f, Math.min(1.0f, v));
    }

    /**
     * 设置雾颜色
     * <p>
     * 各分量会被钳制到有效范围 [0.0, 1.0]。
     *
     * @param r 红色分量（0.0 ~ 1.0）
     * @param g 绿色分量（0.0 ~ 1.0）
     * @param b 蓝色分量（0.0 ~ 1.0）
     */
    public void setFogColor(float r, float g, float b) {
        this.fogColorR = Math.max(0.0f, Math.min(1.0f, r));
        this.fogColorG = Math.max(0.0f, Math.min(1.0f, g));
        this.fogColorB = Math.max(0.0f, Math.min(1.0f, b));
    }

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
     *       fogDensity, heightFalloff, raySteps, anisotropyG, screenSize</li>
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
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * @param path 资源路径（如 "/shaders/volumetric_fog.spv"）
     * @return SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = VolumetricFogNode.class.getResourceAsStream(path)) {
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
                "VolumetricFogNode{enabled=%b density=%.2f falloff=%.4f steps=%d aniso=%.2f}",
                enabled, fogDensity, fogHeightFalloff, raySteps, scatteringAnisotropy
        );
    }
}
