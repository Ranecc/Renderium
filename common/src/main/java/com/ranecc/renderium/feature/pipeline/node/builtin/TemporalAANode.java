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

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

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

    /** SPIR-V 着色器资源路径 */
    private static final String SHADER_PATH = "/shaders/taa_resolve.spv";

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

    /** Pipeline 是否已创建 */
    private volatile boolean pipelineCreated = false;

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
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[TAA] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float   curSharpness   = this.sharpness;
        float   curBlendWeight = this.blendWeight;
        boolean curClamp       = this.neighborhoodClamp;
        boolean curVelReject   = this.velocityRejection;

        MemorySegment params = PerFrameArena.allocate(24L);
        params.set(ValueLayout.JAVA_FLOAT, 0, curBlendWeight);
        params.set(ValueLayout.JAVA_FLOAT, 4, curSharpness);
        params.set(ValueLayout.JAVA_INT, 8, curClamp ? 1 : 0);
        params.set(ValueLayout.JAVA_INT, 12, curVelReject ? 1 : 0);
        params.set(ValueLayout.JAVA_INT, 16, context.getWidth());
        params.set(ValueLayout.JAVA_INT, 20, context.getHeight());

        // 确保 Compute Pipeline 已创建（加载 SPIR-V 着色器）
        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        // TODO: 实际 Vulkan Compute Shader 调度
        // 1. vkCmdBindPipeline(cmdBuf, COMPUTE, computePipeline)
        // 2. vkCmdBindDescriptorSets(cmdBuf, COMPUTE, pipelineLayout, 0, descriptorSet)
        //    - 绑定输入纹理: inputResources[0] (当前帧颜色), inputResources[1] (历史帧颜色)
        //    - 绑定输出纹理: inputResources[0]
        // 3. vkCmdPushConstants(cmdBuf, pipelineLayout, COMPUTE, 0, pushConstantData)
        //    - blendWeight, sharpness, clampMode, invScreenSize, jitterOffset
        // 4. vkCmdDispatch(cmdBuf, (width + 7) / 8, (height + 7) / 8, 1)

        this.historyColorBuffer = inputResources[0];

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[TAA] 完成 | blend=%.2f sharpness=%.2f clamp=%b velReject=%b | pipeline=0x%X | %.1fμs",
                curBlendWeight, curSharpness, curClamp, curVelReject, computePipeline, elapsedMicros
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
        this.historyColorBuffer = 0L;
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
     * 线程安全：volatile 字段保证可见性，重复创建幂等。
     */
    private void ensurePipeline() {
        if (pipelineCreated) return;
        try {
            byte[] spirv = loadSPIRVResource(SHADER_PATH);
            if (spirv == null || spirv.length == 0) {
                LOGGER.warning("SPIR-V 着色器加载失败: " + SHADER_PATH);
                return;
            }
            // TODO: 创建 Vulkan Compute Pipeline
            // 1. vkCreateShaderModule(spirv)
            // 2. vkCreatePipelineLayout(pushConstantRange)
            //    - PushConstants: blendWeight, sharpness, clampMode, invScreenSize, jitterOffset
            // 3. vkCreateComputePipelines(shaderModule, pipelineLayout)
            computePipeline = 1L; // placeholder: 非 0 表示已创建
            pipelineCreated = true;
            LOGGER.fine("Compute Pipeline 创建成功: " + SHADER_PATH);
        } catch (Exception e) {
            LOGGER.warning("Pipeline 创建异常: " + e.getMessage());
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * @param path 资源路径（如 "/shaders/taa_resolve.spv"）
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
