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

    /** SPIR-V 着色器资源路径 */
    private static final String SHADER_PATH = "/shaders/motion_blur.spv";

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

    /** Pipeline 是否已创建 */
    private volatile boolean pipelineCreated = false;

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
        // 短路：禁用时直接传递输入
        if (!enabled) return passThrough(inputResources);

        // 输入校验
        if (inputResources == null || inputResources.length < 1) {
            LOGGER.warning("[MotionBlur] 输入资源不足: 需要颜色纹理 1 张, "
                    + "实际收到 " + (inputResources == null ? 0 : inputResources.length) + " 张");
            return 0L;
        }

        long startTimeNanos = System.nanoTime();

        // 快照读取 volatile 参数（一次读取，避免多次读不一致）
        float curIntensity = this.intensity;
        int   curSamples   = this.samples;
        float curMaxVel    = this.maxVelocity;

        MemorySegment params = PerFrameArena.allocate(24L);
        params.set(ValueLayout.JAVA_FLOAT, 0, curIntensity);
        params.set(ValueLayout.JAVA_INT, 4, curSamples);
        params.set(ValueLayout.JAVA_FLOAT, 8, curMaxVel);
        params.set(ValueLayout.JAVA_INT, 12, context.getWidth());
        params.set(ValueLayout.JAVA_INT, 16, context.getHeight());
        params.set(ValueLayout.JAVA_INT, 20, objectMotion ? 1 : 0);

        // 确保 Compute Pipeline 已创建（加载 SPIR-V 着色器）
        ensurePipeline();
        if (computePipeline == 0L) return passThrough(inputResources);

        // TODO: 实际 Vulkan Compute Shader 调度
        // 1. vkCmdBindPipeline(cmdBuf, COMPUTE, computePipeline)
        // 2. vkCmdBindDescriptorSets(cmdBuf, COMPUTE, pipelineLayout, 0, descriptorSet)
        //    - 绑定输入纹理: inputResources[0] (颜色), inputResources[1] (速度)
        //    - 绑定输出纹理: inputResources[0]
        // 3. vkCmdPushConstants(cmdBuf, pipelineLayout, COMPUTE, 0, pushConstantData)
        //    - intensity, samples, maxVelocity, invScreenSize
        // 4. vkCmdDispatch(cmdBuf, (width + 7) / 8, (height + 7) / 8, 1)

        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        LOGGER.fine(String.format(
                "[MotionBlur] 完成 | intensity=%.2f samples=%d maxVel=%.1f | pipeline=0x%X | %.1fμs",
                curIntensity, curSamples, curMaxVel, computePipeline, elapsedMicros
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
            //    - PushConstants: intensity, samples, maxVelocity, invScreenSize
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
     * @param path 资源路径（如 "/shaders/motion_blur.spv"）
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
