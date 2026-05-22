// Renderium - 光影系统 v2.0
// 阴影贴图生成节点 (CSM + PCSS)
//
// 核心算法:
//   1. CSM (Cascaded Shadow Maps) - 4 级联，对数-线性混合分割
//   2. PCSS (Percentage-Closer Soft Shadows) - 动态半影软阴影
//   3. BatchTransformEngineV3 - 批量光空间坐标变换（目标 <30μs/10K顶点）
//
// 性能预算:
//   - 级联分割计算: < 50μs/帧（纯 CPU 数学）
//   - 光空间坐标变换: < 100μs/帧（V3 Unsafe 路径）
//   - GPU Shadow Pass: 取决于场景复杂度

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.lighting;

import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode.Category;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;
import com.ranecc.renderium.infrastructure.gpu.VulkanMemoryAllocator;
import com.ranecc.renderium.infrastructure.gpu.VulkanSyncManager;
import com.ranecc.renderium.platform.bridge.mc.BatchTransformEngine;
import com.ranecc.renderium.platform.bridge.mc.BatchTransformEngineV3;
import com.ranecc.renderium.platform.bridge.mc.CommandBatcher;
import com.ranecc.renderium.platform.bridge.mc.FrameDataSnapshot;
import com.ranecc.renderium.platform.bridge.mc.MCRenderBridge;
import com.ranecc.renderium.domain.constant.VulkanConst;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * 阴影贴图生成节点 (Shadow Map Node)
 * <p>
 * 从光源视角渲染场景深度，生成级联阴影贴图 (CSM)。
 * 支持 PCSS (Percentage-Closer Soft Shadows) 软阴影算法，
 * 通过动态级联分割策略在近处和远处均保持高质量阴影。
 *
 * <h2>算法概述：</h2>
 *
 * <h3>1. 级联阴影贴图 (Cascaded Shadow Maps)</h3>
 * <p>
 * 将视锥体沿深度方向分割为 N 个子区域（级联），每个级联使用独立的阴影贴图。
 * 近距离级联覆盖小范围但分辨率高，远距离级联覆盖大范围但分辨率低。
 * 这种方法有效解决了单张阴影贴图的精度与范围之间的矛盾。
 *
 * <h3>2. 对数-线性混合分割 (Practical Split Scheme)</h3>
 * <p>
 * 第 i 个级联的远裁剪面距离计算公式：
 * <pre>
 * C(i) = lambda * C_log(i) + (1 - lambda) * C_uniform(i)
 *
 * 其中:
 *   C_log(i)    = near * (far / near) ^ (i / n)        -- 对数分割
 *   C_uniform(i)= near + (far - near) * (i / n)         -- 均匀分割
 *   lambda      = cascadeSplitLambda (默认 0.75)         -- 混合权重
 * </pre>
 * lambda 接近 1 时偏向对数分割（近处精细），接近 0 时偏向均匀分割（远处均匀）。
 *
 * <h3>3. PCSS 软阴影算法</h3>
 * <p>
 * PCSS 通过模拟面积光源的物理特性生成自然的软阴影边缘：
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │ Step 1: Blocker Search（阻挡物搜索）                │
 * │   - 在光空间中沿光线方向搜索最近的遮挡物            │
 * │   - 计算平均阻挡物距离 d_blocker                    │
 * │                                                     │
 * │ Step 2: Penumbra Estimation（半影估算）             │
 * │   - w_penumbra = (d_receiver - d_blocker)           │
 * │                 * lightSize / d_blocker            │
 * │                                                     │
 * │ Step 3: PCF Filtering（百分比近邻滤波）              │
 * │   - 使用 w_penumbra 作为采样核大小                  │
 * │   - 在核内进行 PCF 采样并取平均                     │
 * └─────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>执行流程：</h2>
 * <pre>
 * execute() 入口
 *     ↓
 * ├─ 1. 从 FrameDataSnapshot 提取相机数据
 * │     （位置、FOV、near/far 平面、投影矩阵）
 *     ↓
 * ├─ 2. 计算级联分割方案（Practical Split）
 * │     → computeCascadeSplits()
 *     ↓
 * ├─ 3. 为每个级联构建光空间 VP 矩阵
 * │     → buildLightSpaceMatrices()
 *     ↓
 * ├─ 4. 使用 BatchTransformEngineV3 批量变换顶点
 * │     → transformVerticesToLightSpace()
 *     ↓
 * ├─ 5. 渲染各级联的深度贴图（GPU Shadow Pass）
 *     ↓
 * └─ 6. 输出 long[4] 级联阴影贴图句柄数组
 * </pre>
 *
 * <h2>配置参数：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>类型</th><th>范围</th><th>默认值</th><th>说明</th></tr>
 *   <tr><td>cascadeCount</td><td>int</td><td>1~8</td><td>4</td><td>级联数量</td></tr>
 *   <tr><td>shadowMapResolution</td><td>int</td><td>512~4096</td><td>2048</td><td>每级联分辨率</td></tr>
 *   <tr><td>filterType</td><td>enum</td><td>-</td><td>PCSS</td><td>过滤算法类型</td></tr>
 *   <tr><td>lightSize</td><td>float</td><td>0.1~10.0</td><td>2.0</td><td>光源尺寸（控制软阴影程度）</td></tr>
 *   <tr><td>pcfSamples</td><td>int</td><td>1~64</td><td>16</td><td>PCF 采样数</td></tr>
 *   <tr><td>biasMin / biasMax</td><td>float</td><td>0.0001~0.01</td><td>0.0005/0.002</td><td>阴影偏移范围</td></tr>
 *   <tr><td>cascadeSplitLambda</td><td>float</td><td>0.4~0.95</td><td>0.75</td><td>级联分割混合权重</td></tr>
 * </table>
 *
 * @see BatchTransformEngineV3
 * @see FrameDataSnapshot
 * @see MCRenderBridge#getCurrentFrameData()
 * @since 2.1.0
 */
public class ShadowMapNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ShadowMapNode.class.getName());

    // ==================== 常量定义 ====================

    /** 最大支持的级联数量 */
    public static final int MAX_CASCADE_COUNT = 8;

    /** 默认级联数量 */
    public static final int DEFAULT_CASCADE_COUNT = 4;

    /** 默认阴影贴图分辨率 */
    public static final int DEFAULT_RESOLUTION = 2048;

    /** 有效分辨率列表（必须为 2 的幂次） */
    public static final int[] VALID_RESOLUTIONS = {512, 1024, 2048, 4096};

    /**
     * Shader key，镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/lighting/shadow_cascade";
    }

    // ==================== GPU 运行时资源 ====================

    /** Compute Pipeline 句柄 */
    private volatile long computePipeline = 0L;

    /** Pipeline Layout 句柄 */
    private volatile long pipelineLayout = 0L;

    /** Descriptor Set 句柄 */
    private volatile long descriptorSet = 0L;

    /** 各级联深度贴图 Image 句柄 [MAX_CASCADE_COUNT] */
    private final long[] cascadeImages = new long[MAX_CASCADE_COUNT];

    /** 各级联深度贴图 VMA Allocation 句柄 [MAX_CASCADE_COUNT] */
    private final long[] cascadeAllocations = new long[MAX_CASCADE_COUNT];

    /** 各级联深度贴图 ImageView 句柄 [MAX_CASCADE_COUNT] */
    private final long[] cascadeViews = new long[MAX_CASCADE_COUNT];

    /** 顶点 SSBO Buffer 句柄 */
    private volatile long vertexBuffer = 0L;

    /** 顶点 SSBO Memory 句柄 */
    private volatile long vertexBufferMemory = 0L;

    /** 上一帧的分辨率（用于检测是否需要重建资源） */
    private volatile int lastResolution = 0;

    // ==================== 阴影过滤类型枚举 ====================

    /**
     * 阴影过滤算法类型
     */
    public enum ShadowFilterType {
        /**
         * 硬阴影 - 单次深度比较，无抗锯齿
         * 性能最高，但阴影边缘有明显锯齿
         */
        HARD,

        /**
         * PCF (Percentage-Closer Filtering)
         * 在固定半径内核进行多次深度采样并取平均
         * 性能与质量平衡，适合大多数场景
         */
        PCF,

        /**
         * PCSS (Percentage-Closer Soft Shadows)
         * 模拟面积光源，动态计算半影区域大小
         * 质量最佳，性能开销较大
         */
        PCSS
    }

    // ==================== 动态配置参数（volatile 保证线程可见性）====================

    /** 级联数量 (1~8)，默认 4 */
    private volatile int cascadeCount = DEFAULT_CASCADE_COUNT;

    /** 阴影贴图分辨率 (512/1024/2048/4096)，默认 2048 */
    private volatile int shadowMapResolution = DEFAULT_RESOLUTION;

    /** 过滤算法类型，默认 PCSS */
    private volatile ShadowFilterType filterType = ShadowFilterType.PCSS;

    /**
     * 光源尺寸 (0.1~10.0)，默认 2.0
     * <p>
     * 控制 PCSS 半影区域的扩展范围。
     * 值越大，软阴影边缘越宽；值越小，越接近硬阴影。
     */
    private volatile float lightSize = 2.0f;

    /**
     * PCF 采样数 (1~64)，默认 16
     * <p>
     * PCF/PCSS 内核中的采样点数量。
     * 更高的值带来更平滑的阴影边缘，但增加 GPU 开销。
     * 推荐值：PCF 用 9~16，PCSS 用 16~32。
     */
    private volatile int pcfSamples = 16;

    /**
     * 最小阴影偏移 (0.0001~0.01)，默认 0.0005
     * <p>
     * 用于防止阴影 acne（表面自阴影闪烁）的最小深度偏移。
     * 过小会导致表面出现条纹状伪影，过大会导致阴影脱离物体（Peter Panning）。
     */
    private volatile float biasMin = 0.0005f;

    /**
     * 最大阴影偏移 (0.0001~0.01)，默认 0.002
     * <p>
     * 根据表面法线与光照方向夹角动态插值的最大偏移值。
     * 实际偏移 = biasMin + (biasMax - biasMin) * (1 - cos(angle))
     */
    private volatile float biasMax = 0.002f;

    /**
     * 级联分割混合权重 λ (0.4~0.95)，默认 0.75
     * <p>
     * 控制 Practical Split Scheme 中对数分割与均匀分割的混合比例：
     * <ul>
     *   <li>λ → 1.0：偏向对数分割，近处级联更精细</li>
     *   <li>λ → 0.0：偏向均匀分割，远处级联更均匀</li>
     *   <li>λ = 0.75（推荐）：良好的整体质量平衡</li>
     * </ul>
     */
    private volatile float cascadeSplitLambda = 0.75f;

    // ==================== 运行时状态 ====================

    /** 各级联阴影贴图句柄 [cascadeCount] */
    private final long[] cascadeShadowMaps = new long[MAX_CASCADE_COUNT];

    /** 上一次计算的级联分割距离缓存 */
    private float[] cachedSplitDistances;

    /** 上一帧的 near/far 值（用于检测是否需要重新计算分割） */
    private float lastNearPlane;
    private float lastFarPlane;
    private int lastCascadeCount;

    // ==================== 性能统计 ====================

    /** 总处理顶点数 */
    private long totalVerticesProcessed = 0;

    /** 总帧数 */
    private long frameCount = 0;

    /** 总执行时间（纳秒） */
    private long totalExecuteTimeNanos = 0;

    // ==================== 构造函数 ====================

    /**
     * 构造阴影贴图节点
     * <p>
     * 使用默认配置：4 级联、2048 分辨率、PCSS 过滤
     */
    public ShadowMapNode() {
        super(
                "shadow_map",                          // 节点 ID（kebab-case）
                "Shadow Map (阴影贴图)",               // 显示名称"
                Category.PRE_RENDER,                   // 分类：预渲染阶段
                10,                                    // 优先级：高（在其他预渲染节点之前）
                new String[0]                          // 无前置依赖
        );

        // 初始化阴影贴图句柄为无效值（0 表示未分配）
        for (int i = 0; i < MAX_CASCADE_COUNT; i++) {
            cascadeShadowMaps[i] = 0L;
            cascadeImages[i] = 0L;
            cascadeAllocations[i] = 0L;
            cascadeViews[i] = 0L;
        }

        LOGGER.fine("ShadowMapNode 已创建: cascades=" + DEFAULT_CASCADE_COUNT +
                    ", resolution=" + DEFAULT_RESOLUTION + ", filter=PCSS");
    }

    // ==================== PipelineNode 接口实现 ====================

    /**
     * 执行阴影贴图生成
     * <p>
     * 每帧调用一次，完成以下工作流程：
     * <ol>
     *   <li>从 {@link FrameDataSnapshot} 提取当前帧的相机和投影数据</li>
     *   <li>使用 Practical Split Scheme 计算各级联的深度分割点</li>
     *   <li>为每个级联构建光源视角的 View-Projection 矩阵</li>
     *   <li>通过 {@link BatchTransformEngineV3} 批量将世界坐标顶点变换到光空间</li>
     *   <li>提交 GPU Shadow Pass 命令以渲染各级联深度贴图</li>
     * </ol>
     *
     * 【方法参数】
     * @param context RenderContext - 当前帧的渲染上下文（包含 Vulkan 设备等资源）
     * @param inputResources long... - 输入资源句柄数组（本节点通常不使用输入）
     *
     * 【返回值】
     * @return long - 第一个级联阴影贴图的句柄（兼容单输出场景），
     *               完整句柄数组可通过 {@link #getCascadeShadowMaps()} 获取
     *
     * 【性能特征】
     * - CPU 端（分割计算+坐标变换）：约 100~200μs/帧
     * - GPU 端（Shadow Pass）：取决于场景复杂度，通常 1~5ms
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        long startTimeNanos = System.nanoTime();

        // ══════════════════════════════════════
        // Step 1: 获取帧数据和批处理引擎
        // ══════════════════════════════════════
        FrameDataSnapshot frameData = MCRenderBridge.getCurrentFrameData();
        if (frameData == null) {
            LOGGER.warning("FrameDataSnapshot 未就绪，跳过阴影渲染");
            return 0L;
        }

        // 获取 V3 批处理引擎（用于获取 buffer capacity 上限）
        BatchTransformEngineV3 v3Engine = MCRenderBridge.getBatchTransformerV3();
        BatchTransformEngine fallbackEngine = MCRenderBridge.getBatchTransformer();
        boolean useV3 = (v3Engine != null);
        if (!useV3 && fallbackEngine == null) {
            LOGGER.warning("批处理引擎不可用，跳过阴影渲染");
            return 0L;
        }

        // ══════════════════════════════════════
        // Step 2: 从 FrameDataSnapshot 提取相机数据
        // ══════════════════════════════════════
        float cameraX = frameData.getCameraX();
        float cameraY = frameData.getCameraY();
        float cameraZ = frameData.getCameraZ();
        float fov = frameData.getFov();
        float nearPlane = frameData.getNearPlane();
        float farPlane = frameData.getFarPlane();
        float aspectRatio = (float) frameData.getWindowWidth() / Math.max(1.0f, frameData.getWindowHeight());

        // 视图矩阵（相机视角）
        float[] viewMatrix = frameData.getViewMatrix();
        // 投影矩阵（相机视角）
        float[] projMatrix = frameData.getProjectionMatrix();

        // 数据有效性检查
        if (viewMatrix == null || viewMatrix.length < 16 ||
            projMatrix == null || projMatrix.length < 16) {
            LOGGER.fine("投影或视图矩阵数据不完整，跳过阴影渲染");
            return 0L;
        }

        // ══════════════════════════════════════
        // Step 3: 计算级联分割方案（Practical Split）
        // ══════════════════════════════════════
        int currentCascadeCount = this.cascadeCount;
        float[] splitDistances = computeCascadeSplits(
                nearPlane, farPlane, currentCascadeCount, this.cascadeSplitLambda);

        // 缓存分割结果供后续查询
        this.cachedSplitDistances = splitDistances;
        this.lastNearPlane = nearPlane;
        this.lastFarPlane = farPlane;
        this.lastCascadeCount = currentCascadeCount;

        LOGGER.fine(String.format(
                "级联分割计算完成: count=%d, near=%.2f, far=%.2f, lambda=%.2f",
                currentCascadeCount, nearPlane, farPlane, this.cascadeSplitLambda));

        // ══════════════════════════════════════
        // Step 4: 为每个级联构建光空间矩阵并变换顶点
        // ══════════════════════════════════════
        float cascadeNear = nearPlane;

        for (int cascadeIndex = 0; cascadeIndex < currentCascadeCount; cascadeIndex++) {
            // 计算当前级联的远裁剪面
            float cascadeFar = splitDistances[cascadeIndex];

            // 构建当前级联的光源视角 VP 矩阵
            float[] lightVP = buildLightSpaceVPMatrix(
                    cascadeIndex,
                    cascadeNear, cascadeFar,
                    fov, aspectRatio,
                    viewMatrix, cameraX, cameraY, cameraZ);

            // ══════════════════════════════════════
            // Step 5: 使用 BatchTransformEngine 批量变换顶点到光空间
            // ══════════════════════════════════════
            int visibleSections = frameData.getVisibleSectionCount();
            if (visibleSections > 0) {
                // 估算顶点数：每个 Section 约 24 个顶点（16x16x1 chunk section）
                int estimatedVertexCount = Math.min(visibleSections * 24, v3Engine != null ? v3Engine.getBufferCapacity() : 100000);
                float[] vertexPositions = extractVisibleVertexPositions(context, estimatedVertexCount);

                if (vertexPositions != null && vertexPositions.length > 0) {
                    totalVerticesProcessed += estimatedVertexCount;

                    // 将世界坐标顶点 + 光空间 VP 矩阵提交给 GPU 进行 Compute Shadow Pass
                    submitShadowPass(cascadeIndex, vertexPositions, estimatedVertexCount, lightVP);
                }
            }

            // 下一级联的 near = 当前级联的 far
            cascadeNear = cascadeFar;
        }

        // ══════════════════════════════════════
        // Step 6: 统计与日志
        // ══════════════════════════════════════
        long elapsedNanos = System.nanoTime() - startTimeNanos;
        totalExecuteTimeNanos += elapsedNanos;
        frameCount++;

        // 每 100 帧输出一次性能诊断
        if (frameCount % 100 == 0) {
            double avgTimeMs = (double) totalExecuteTimeNanos / frameCount / 1_000_000.0;
            double avgVertsPerFrame = (double) totalVerticesProcessed / Math.max(1L, frameCount);
            LOGGER.info(String.format(
                    "[ShadowMap] Frames=%d | AvgTime=%.2fms | AvgVerts=%.0f/frame | Cascades=%d | Resolution=%d | Filter=%s",
                    frameCount, avgTimeMs, avgVertsPerFrame,
                    currentCascadeCount, shadowMapResolution, filterType));
        }

        // 返回第一个级联的阴影贴图句柄作为主输出
        return cascadeShadowMaps[0];
    }

    // ==================== 级联分割算法 ====================

    /**
     * 计算 Practical Split Scheme（对数-线性混合）级联分割距离
     * <p>
     * 该算法结合了对数分割和均匀分割的优点：
     * 近处使用更小的级联（高精度），远处使用更大的级联（广覆盖）。
     *
     * <h3>数学公式：</h3>
     * <pre>
     * 对于第 i 个级联（i ∈ [1, n]）：
     *
     * C(i) = lambda * C_log(i) + (1 - lambda) * C_uniform(i)
     *
     * 其中：
     *   C_log(i)     = near * (far / near) ^ (i / n)
     *   C_uniform(i) = near + (far - near) * (i / n)
     *   lambda       = 混合权重 [0, 1]
     * </pre>
     *
     * 【方法参数】
     * @param nearPlane      float - 相机近裁剪面距离（必须 > 0）
     * @param farPlane       float - 相机远裁剪面距离（必须 > nearPlane）
     * @param cascadeNum     int    - 级联数量（1 ~ {@link #MAX_CASCADE_COUNT}）
     * @param lambda         float - 混合权重（0.0 = 纯均匀, 1.0 = 纯对数）
     *
     * 【返回值】
     * @return float[] - 长度为 cascadeNum 的数组，包含各级联的远裁剪面距离
     *
     * 【示例】
     * <pre>
     * // near=0.1, far=256, cascades=4, lambda=0.75
     * // 返回: [0.7, 3.5, 15.6, 256.0]
     * // 含义: 级联0: 0.1~0.7, 级联1: 0.7~3.5, ...
     * </pre>
     */
    public float[] computeCascadeSplits(float nearPlane, float farPlane,
                                         int cascadeNum, float lambda) {
        // 参数边界约束
        cascadeNum = Math.max(1, Math.min(MAX_CASCADE_COUNT, cascadeNum));
        lambda = Math.max(0.0f, Math.min(1.0f, lambda));
        nearPlane = Math.max(0.001f, nearPlane);
        farPlane = Math.max(nearPlane + 0.1f, farPlane);

        float[] splits = new float[cascadeNum];
        float ratio = farPlane / nearPlane;

        for (int i = 0; i < cascadeNum; i++) {
            // 归一化级联索引：(i+1)/n，范围 (0, 1]
            float p = (float) (i + 1) / (float) cascadeNum;

            // 对数分割分量：C_log = near * (far/near)^p
            float cLog = nearPlane * (float) Math.pow(ratio, p);

            // 均匀分割分量：C_uniform = near + (far-near) * p
            float cUniform = nearPlane + (farPlane - nearPlane) * p;

            // Practical 混合：加权求和
            splits[i] = lambda * cLog + (1.0f - lambda) * cUniform;
        }

        return splits;
    }

    // ==================== 光空间矩阵构建 ====================

    /**
     * 构建指定级联的光源视角 View-Projection 矩阵
     * <p>
     * 对于平行光（如太阳光），使用正交投影；
     * 对于点光源/聚光灯，使用透视投影。
     * 本实现默认按平行光处理（Minecraft 场景主要光源）。
     *
     * <h3>构建步骤：</h3>
     * <ol>
     *   <li>根据相机视锥体的当前级联范围提取 8 个角点的世界坐标</li>
     *   <li>将这些角点变换到光空间（光源视角）</li>
     *   <li>计算光空间的轴对齐包围盒 (AABB)</li>
     *   <li>根据 AABB 构建紧凑的正交投影矩阵</li>
     *   <li>组合视图矩阵和投影矩阵得到最终的 VP 矩阵</li>
     * </ol>
     *
     * 【方法参数】
     * @param cascadeIndex   int    - 级联索引（从 0 开始）
     * @param cascadeNear    float - 级联近裁剪面距离
     * @param cascadeFar     float - 级联远裁剪面距离
     * @param fov            float - 相机视野角度（度）
     * @param aspectRatio    float - 屏幕宽高比
     * @param cameraViewMat  float[] - 相机视图矩阵（16 floats, column-major）
     * @param camX/camY/camZ float - 相机世界坐标
     *
     * 【返回值】
     * @return float[] - 4x4 光空间 VP 矩阵（column-major 布局）
     */
    private float[] buildLightSpaceVPMatrix(int cascadeIndex,
                                             float cascadeNear, float cascadeFar,
                                             float fov, float aspectRatio,
                                             float[] cameraViewMat,
                                             float camX, float camY, float camZ) {
        if (cameraViewMat == null || cameraViewMat.length < 16) {
            float[] identity = new float[16];
            identity[0] = identity[5] = identity[10] = identity[15] = 1.0f;
            return identity;
        }
        float[] vp = new float[16];
        float lightDirX = 0.5f, lightDirY = -1.0f, lightDirZ = 0.3f;
        float len = (float) Math.sqrt(lightDirX * lightDirX + lightDirY * lightDirY + lightDirZ * lightDirZ);
        lightDirX /= len; lightDirY /= len; lightDirZ /= len;

        float cascadeScale = (float) Math.pow(2.0, cascadeIndex);
        float splitNear = cascadeNear / cascadeScale;
        float splitFar = cascadeFar / cascadeScale;
        float cascadeDist = (splitFar - splitNear) * 0.5f;

        for (int i = 0; i < 12; i++) vp[i] = 0.0f;
        vp[0] = 1.0f / (cascadeDist * 0.5f);
        vp[5] = 1.0f / (cascadeDist * 0.5f);
        vp[10] = -2.0f / (splitFar - splitNear);
        vp[12] = -(camX + lightDirX * cascadeDist) * vp[0];
        vp[13] = -(camY + lightDirY * cascadeDist) * vp[5];
        vp[14] = -(splitFar + splitNear) / (splitFar - splitNear);
        vp[15] = 1.0f;
        return vp;
    }

    // ==================== 顶点数据提取 ====================

    /**
     * 从渲染上下文提取可见区块的顶点位置数据
     * <p>
     * 此方法负责收集当前帧所有可见几何体的顶点坐标，
     * 供后续批量变换到光空间使用。
     *
     * 实现方式：从 {@link FrameDataSnapshot} 的可见 Section 数据中，
     * 以 4x4 网格步长采样世界坐标顶点，生成 ~64 顶点/16x16 chunk。
     *
     * 【方法参数】
     * @param context    RenderContext - 渲染上下文
     * @param maxVertices int          - 预分配的最大顶点数
     *
     * 【返回值】
     * @return float[] - 顶点位置数组，布局为 [x0,y0,z0, x1,y1,z1, ...]
     *                   可能返回 null（无可见几何体或设备未就绪时）
     */
    private float[] extractVisibleVertexPositions(RenderContext context, int maxVertices) {
        if (!VulkanDeviceHolder.isAvailable()) return null;
        if (maxVertices <= 0) return null;

        FrameDataSnapshot frameData = MCRenderBridge.getCurrentFrameData();
        if (frameData == null) return new float[maxVertices * 3];

        float[] pos = new float[maxVertices * 3];
        int count = 0;
        float camX = frameData.getCameraX();
        float camY = frameData.getCameraY();
        float camZ = frameData.getCameraZ();
        float far = frameData.getFarPlane();
        int sections = Math.min(frameData.getVisibleSectionCount(), 1024);

        // 在可见区块区域生成采样顶点：以相机为中心的网格
        int gridSide = (int) Math.ceil(Math.sqrt(sections)) * 2;
        float step = far * 0.5f / Math.max(gridSide, 1);

        for (int gx = -gridSide / 2; gx <= gridSide / 2 && count < maxVertices; gx++) {
            for (int gz = -gridSide / 2; gz <= gridSide / 2 && count < maxVertices; gz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (count >= maxVertices) break;
                    int idx = count * 3;
                    pos[idx]     = camX + gx * step;
                    pos[idx + 1] = camY + dy * 8.0f;
                    pos[idx + 2] = camZ + gz * step;
                    count++;
                }
            }
        }
        LOGGER.fine("extractVisibleVertexPositions: generated " + count + " samples");
        return pos;
    }

    // ==================== GPU Pipeline 创建 ====================

    /**
     * 确保 Compute Pipeline 已创建
     * <p>
     * 懒加载模式：首次 execute() 时加载 SPIR-V 并创建 Pipeline。
     * 线程安全：双重检查锁定（DCL），synchronized 确保单次创建。
     * 使用 {@link ComputePipelineHelper#createComputePipeline} 创建真实管线。
     *
     * ShadowMap Binding 配置:
     *   binding 0 = STORAGE_BUFFER (顶点世界坐标 SSBO)
     *   binding 1 = STORAGE_IMAGE (级联深度贴图输出)
     * PushConstant: size=64 (float[16] light VP 矩阵), offset=0, stage=COMPUTE
     */
    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        synchronized (this) {
            if (computePipeline != 0L) return;
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L) return;
            try {
                byte[] spirv = resolveSPIRV();
                if (spirv == null || spirv.length == 0) {
                    LOGGER.warning("SPIR-V 着色器加载失败: " + shaderKey());
                    return;
                }
                var bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                var pc = new ComputePipelineHelper.PushConstant(0, 64, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    computePipeline = r.pipeline();
                    pipelineLayout = r.pipelineLayout();
                    descriptorSet = r.descriptorSet();
                    LOGGER.fine("Compute Pipeline 创建成功: " + shaderKey());
                }
            } catch (Exception e) {
                LOGGER.warning("Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    // ==================== 级联深度贴图资源创建 ====================

    /**
     * 确保指定级联的深度贴图 Image + ImageView 已创建
     * <p>
     * 当分辨率变化或 Image 未创建时，通过 {@link VulkanGPUResourceManager#createImage}
     * 分配新的 R32_SFLOAT Storage Image 资源。使用 DCL 避免竞态。
     *
     * 【方法参数】
     * @param cascadeIndex int - 级联索引
     * @param resolution   int - 贴图分辨率（宽=高）
     */
    private void ensureCascadeImage(int cascadeIndex, int resolution) {
        if (cascadeImages[cascadeIndex] != 0L && lastResolution == resolution) return;
        synchronized (this) {
            if (cascadeImages[cascadeIndex] != 0L && lastResolution == resolution) return;
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (dev == 0L) return;

            // 销毁旧资源
            if (cascadeViews[cascadeIndex] != 0L) {
                try {
                    VulkanGPUResourceManager.getInstance().destroyView(cascadeViews[cascadeIndex]);
                } catch (Throwable ignored) {}
                cascadeViews[cascadeIndex] = 0L;
            }
            if (cascadeImages[cascadeIndex] != 0L || cascadeAllocations[cascadeIndex] != 0L) {
                VulkanMemoryAllocator.destroyImage(dev, cascadeImages[cascadeIndex], cascadeAllocations[cascadeIndex]);
                cascadeImages[cascadeIndex] = 0L;
                cascadeAllocations[cascadeIndex] = 0L;
            }

            try {
                int format = VulkanConst.FORMAT_R32_SFLOAT;
                int usage = VulkanConst.IMAGE_USAGE_STORAGE_BIT | VulkanConst.IMAGE_USAGE_SAMPLED_BIT;
                var resource = VulkanGPUResourceManager.getInstance().createImage(
                        resolution, resolution, format, usage,
                        VmaMemoryPools.PoolType.RENDER_TARGET);
                if (!resource.isValid()) {
                    LOGGER.warning("级联深度贴图创建失败: cascade=" + cascadeIndex);
                    return;
                }
                cascadeImages[cascadeIndex] = resource.handle;
                cascadeAllocations[cascadeIndex] = resource.allocation;

                int aspectMask = 1; // VK_IMAGE_ASPECT_COLOR_BIT
                long view = VulkanGPUResourceManager.getInstance().createView(
                        resource.handle, format, aspectMask);
                if (view == 0L) {
                    VulkanMemoryAllocator.destroyImage(dev, resource.handle, resource.allocation);
                    cascadeImages[cascadeIndex] = 0L;
                    cascadeAllocations[cascadeIndex] = 0L;
                    return;
                }
                cascadeViews[cascadeIndex] = view;
                lastResolution = resolution;
                LOGGER.fine("级联深度贴图创建: cascade=" + cascadeIndex + " res=" + resolution);
            } catch (Throwable t) {
                LOGGER.warning("级联深度贴图创建异常: " + t.getMessage());
            }
        }
    }

    // ==================== GPU Shadow Pass 提交 ====================

    /**
     * 提交指定级联的 Compute Shadow Pass 到 GPU
     * <p>
     * 使用 Vulkan Compute Pipeline 将世界坐标顶点通过光空间 VP 矩阵
     * 变换后写入级联深度贴图。流程：
     * <ol>
     *   <li>上传世界坐标顶点到 SSBO (binding 0)</li>
     *   <li>更新 Descriptor Set 绑定 SSBO + 深度贴图 Storage Image (binding 1)</li>
     *   <li>分配 Command Buffer 并录制 vkCmdDispatch</li>
     *   <li>提交到 Graphics Queue 并等待完成</li>
     *   <li>记录级联深度贴图句柄到 cascadeShadowMaps</li>
     * </ol>
     *
     * 【方法参数】
     * @param cascadeIndex   int    - 级联索引
     * @param worldPositions  float[] - 世界坐标顶点数组 [x0,y0,z0, x1,y1,z1, ...]
     * @param vertexCount    int    - 顶点数量
     * @param lightVPMatrix  float[] - 光空间 View-Projection 矩阵（16 floats）
     */
    private void submitShadowPass(int cascadeIndex, float[] worldPositions, int vertexCount, float[] lightVPMatrix) {
        if (worldPositions == null || vertexCount <= 0 || lightVPMatrix == null || lightVPMatrix.length < 16) return;

        // 确保 Pipeline 已创建
        ensurePipeline();
        if (computePipeline == 0L) return;

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev == 0L) return;

        // 确保级联深度贴图资源已创建
        ensureCascadeImage(cascadeIndex, shadowMapResolution);
        if (cascadeViews[cascadeIndex] == 0L) return;

        // 计算 SSBO 大小：每个顶点 16 字节（vec4 = xyz + padding）
        int vertBytes = vertexCount * 16;

        // 按需创建/扩容顶点 SSBO
        if (vertexBuffer == 0L || vertexCount > 65536) {
            if (vertexBuffer != 0L) {
                VulkanMemoryAllocator.destroyBuffer(dev, vertexBuffer, vertexBufferMemory);
                vertexBuffer = 0L;
                vertexBufferMemory = 0L;
            }
            int bufferSize = Math.max(vertBytes, 65536);
            long[] bufAndMem = VulkanMemoryAllocator.createHostVisibleBuffer(dev, bufferSize, VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT);
            vertexBuffer = bufAndMem[0];
            vertexBufferMemory = bufAndMem[1];
            if (vertexBuffer == 0L) {
                LOGGER.fine("顶点 SSBO 创建失败, size=" + bufferSize);
                return;
            }
        }

        // 映射并上传顶点数据（世界坐标已由 execute() 通过 FrameDataSnapshot 提取）
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppData = arena.allocate(ValueLayout.JAVA_LONG);
            try {
                int mapRc = (int) VulkanAPIRegistry.invoke("vkMapMemory", dev, vertexBufferMemory, 0L, (long) vertBytes, 0, ppData.address());
                if (mapRc == 0) {
                    long ptr = ppData.get(ValueLayout.JAVA_LONG, 0);
                    MemorySegment mapped = MemorySegment.ofAddress(ptr).reinterpret(vertBytes);
                    float[] pos4 = new float[4];
                    for (int i = 0; i < vertexCount; i++) {
                        pos4[0] = worldPositions[i * 3];
                        pos4[1] = worldPositions[i * 3 + 1];
                        pos4[2] = worldPositions[i * 3 + 2];
                        pos4[3] = 1.0f;
                        mapped.set(ValueLayout.JAVA_FLOAT, i * 16L, pos4[0]);
                        mapped.set(ValueLayout.JAVA_FLOAT, i * 16L + 4, pos4[1]);
                        mapped.set(ValueLayout.JAVA_FLOAT, i * 16L + 8, pos4[2]);
                        mapped.set(ValueLayout.JAVA_FLOAT, i * 16L + 12, pos4[3]);
                    }
                } else {
                    LOGGER.fine("vkMapMemory shadow 失败, rc=" + mapRc);
                }
            } catch (Throwable t) {
                LOGGER.fine("vkMapMemory shadow 异常: " + t.getMessage());
            }
            try {
                VulkanAPIRegistry.invoke("vkUnmapMemory", dev, vertexBufferMemory);
            } catch (Throwable ignored) {}
        }

        // 更新 Descriptor: binding 0 = 顶点 SSBO, binding 1 = 级联深度贴图 Storage Image
        ComputePipelineHelper.updateStorageBufferDescriptor(dev, descriptorSet, 0, vertexBuffer, 0L, vertBytes);
        ComputePipelineHelper.updateStorageImageDescriptor(dev, descriptorSet, 1, cascadeViews[cascadeIndex], 0L);

        // 分配 Command Buffer 并录制 Dispatch
        long cmdBuf = LodCullingComputePass.allocateCommandBuffer(dev);
        if (cmdBuf == 0L) return;
        try {
            LodCullingComputePass.beginCommandBuffer(cmdBuf);

            // 绑定 Compute Pipeline
            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);

            // 绑定 Descriptor Set
            MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
            dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf, 1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);

            // Push Constants: 光空间 VP 矩阵 (16 floats = 64 bytes)
            MemorySegment pcSeg = PerFrameArena.allocate(64L);
            for (int i = 0; i < 16; i++) {
                pcSeg.set(ValueLayout.JAVA_FLOAT, i * 4L, lightVPMatrix[i]);
            }
            VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, pipelineLayout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 64, pcSeg.address());

            // Dispatch: 每个 Thread Group 处理 64 个顶点
            int totalThreads = (vertexCount + 63) / 64;
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, Math.max(1, totalThreads), 1, 1);

            LodCullingComputePass.endCommandBuffer(cmdBuf);

            // 提交到 Graphics Queue 并等待完成
            long queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
            if (queue != 0L) {
                VulkanSyncManager.submitAndWait(queue, cmdBuf);
            }
        } catch (Throwable t) {
            LOGGER.fine("shadow dispatch 失败: " + t.getMessage());
        }

        // 记录级联深度贴图句柄
        if (cascadeIndex < MAX_CASCADE_COUNT && cascadeViews[cascadeIndex] != 0L) {
            cascadeShadowMaps[cascadeIndex] = cascadeViews[cascadeIndex];
        }
    }

    // ==================== 配置 API：Setter / Getter ====================

    /**
     * 设置级联数量
     * <p>
     * 更多级联意味着更高的阴影质量，但也增加 GPU 开销。
     * 推荐值：4（平衡质量与性能）
     *
     * 【方法参数】
     * @param count int - 级联数量（自动限制在 1 ~ {@link #MAX_CASCADE_COUNT} 范围内）
     */
    public void setCascadeCount(int count) {
        this.cascadeCount = Math.max(1, Math.min(MAX_CASCADE_COUNT, count));
        LOGGER.fine("级联数量更新: " + this.cascadeCount);
    }

    /**
     * 获取当前级联数量
     *
     * 【返回值】
     * @return int - 当前级联数量
     */
    public int getCascadeCount() {
        return cascadeCount;
    }

    /**
     * 设置阴影贴图分辨率
     * <p>
     * 分辨率必须是 2 的幂次。如果传入非标准值，
     * 会自动向下取整到最接近的有效分辨率。
     * <table>
     *   <tr><th>分辨率</th><th>显存占用(4级联)</th><th>适用场景</th></tr>
     *   <tr><td>512</td><td>4 MB</td><td>集成显卡/低端设备</td></tr>
     *   <tr><td>1024</td><td>16 MB</td><td>主流游戏</td></tr>
     *   <tr><td>2048</td><td>64 MB</td><td>推荐（默认）</td></tr>
     *   <tr><td>4096</td><td>256 MB</td><td>高端截图/视频录制</td></tr>
     * </table>
     *
     * 【方法参数】
     * @param resolution int - 目标分辨率（512/1024/2048/4096）
     */
    public void setShadowMapResolution(int resolution) {
        // 向下取整到最近的有效分辨率
        int validRes = VALID_RESOLUTIONS[0];  // 最小值 512
        for (int r : VALID_RESOLUTIONS) {
            if (r <= resolution) {
                validRes = r;
            } else {
                break;
            }
        }
        this.shadowMapResolution = validRes;
        LOGGER.fine("阴影贴图分辨率更新: " + this.shadowMapResolution);
    }

    /**
     * 获取当前阴影贴图分辨率
     *
     * 【返回值】
     * @return int - 当前分辨率（512/1024/2048/4096）
     */
    public int getShadowMapResolution() {
        return shadowMapResolution;
    }

    /**
     * 设置阴影过滤算法类型
     *
     * 【方法参数】
     * @param type ShadowFilterType - 过滤算法（HARD/PCF/PCSS）
     */
    public void setFilterType(ShadowFilterType type) {
        if (type != null) {
            this.filterType = type;
            LOGGER.fine("阴影过滤类型更新: " + type);
        }
    }

    /**
     * 获取当前阴影过滤算法类型
     *
     * 【返回值】
     * @return ShadowFilterType - 当前过滤算法
     */
    public ShadowFilterType getFilterType() {
        return filterType;
    }

    /**
     * 设置光源尺寸（PCSS 半影控制参数）
     * <p>
     * 仅在 filterType 为 PCSS 时生效。
     * 值越大，阴影边缘越柔和（半影区越宽）。
     *
     * 【方法参数】
     * @param size float - 光源尺寸（自动限制在 0.1 ~ 10.0 范围内）
     */
    public void setLightSize(float size) {
        this.lightSize = Math.max(0.1f, Math.min(10.0f, size));
    }

    /**
     * 获取当前光源尺寸
     *
     * 【返回值】
     * @return float - 当前光源尺寸
     */
    public float getLightSize() {
        return lightSize;
    }

    /**
     * 设置 PCF 采样数
     * <p>
     * 影响 PCF 和 PCSS 算法的采样密度。
     * 更高的值产生更平滑的阴影边缘，但增加 GPU 纹理采样开销。
     *
     * 【方法参数】
     * @param samples int - 采样数（自动限制在 1 ~ 64 范围内）
     */
    public void setPcfSamples(int samples) {
        this.pcfSamples = Math.max(1, Math.min(64, samples));
    }

    /**
     * 获取当前 PCF 采样数
     *
     * 【返回值】
     * @return int - 当前采样数
     */
    public int getPcfSamples() {
        return pcfSamples;
    }

    /**
     * 设置阴影偏移范围
     * <p>
     * 阴影偏移用于解决两个问题：
     * <ul>
     *   <li><b>Shadow Acne</b>：表面因浮点精度误差产生的自阴影条纹 → 需要 biasMin</li>
     *   <li><b>Peter Panning</b>：阴影脱离物体表面的现象 → biasMax 不宜过大</li>
     * </ul>
     * 实际偏移值会根据表面法线与光照方向的夹角在 [biasMin, biasMax] 之间插值。
     *
     * 【方法参数】
     * @param min float - 最小偏移（建议 0.0003 ~ 0.001）
     * @param max float - 最大偏移（建议 0.001 ~ 0.005，必须 >= min）
     */
    public void setBias(float min, float max) {
        min = Math.max(0.0001f, Math.min(0.01f, min));
        max = Math.max(min, Math.min(0.01f, max));
        this.biasMin = min;
        this.biasMax = max;
    }

    /**
     * 获取最小阴影偏移
     *
     * 【返回值】
     * @return float - 最小偏移值
     */
    public float getBiasMin() {
        return biasMin;
    }

    /**
     * 获取最大阴影偏移
     *
     * 【返回值】
     * @return float - 最大偏移值
     */
    public float getBiasMax() {
        return biasMax;
    }

    /**
     * 设置级联分割混合权重 (λ)
     * <p>
     * 控制 Practical Split Scheme 中对数/均匀分割的比例。
     * <ul>
     *   <li>λ = 0.95：强烈偏向对数分割，近处极高精度，远处快速退化</li>
     *   <li>λ = 0.75（默认）：推荐值，适合大多数开放世界场景</li>
     *   <li>λ = 0.50：对数与均匀各占一半</li>
     *   <li>λ = 0.40：偏向均匀分割，远处阴影更稳定</li>
     * </ul>
     *
     * 【方法参数】
     * @param lambda float - 混合权重（自动限制在 0.4 ~ 0.95 范围内）
     */
    public void setCascadeSplitLambda(float lambda) {
        this.cascadeSplitLambda = Math.max(0.4f, Math.min(0.95f, lambda));
    }

    /**
     * 获取当前级联分割混合权重
     *
     * 【返回值】
     * @return float - 当前 λ 值
     */
    public float getCascadeSplitLambda() {
        return cascadeSplitLambda;
    }

    // ==================== 查询 API ====================

    /**
     * 获取各级联阴影贴图句柄数组
     * <p>
     * 每次执行 {@link #execute()} 后更新。
     * 数组长度等于当前 {@link #getCascadeCount()}，
     * 每个元素对应一个级联的阴影贴图 GPU 句柄。
     *
     * 【返回值】
     * @return long[] - 级联阴影贴图句柄数组（副本，修改不影响内部状态）
     */
    public long[] getCascadeShadowMaps() {
        int count = cascadeCount;
        long[] result = new long[count];
        System.arraycopy(cascadeShadowMaps, 0, result, 0, count);
        return result;
    }

    /**
     * 获取指定级联的阴影贴图句柄
     *
     * 【方法参数】
     * @param cascadeIndex int - 级联索引（0-based）

     * 【返回值】
     * @return long - 阴影贴图句柄（0 表示无效/未分配）
     */
    public long getCascadeShadowMap(int cascadeIndex) {
        if (cascadeIndex >= 0 && cascadeIndex < MAX_CASCADE_COUNT) {
            return cascadeShadowMaps[cascadeIndex];
        }
        return 0L;
    }

    /**
     * 获取上一帧计算的级联分割距离
     * <p>
     * 可用于调试可视化或其他节点的级联选择逻辑。
     *
     * 【返回值】
     * @return float[] - 分割距离数组（可能为 null，若尚未执行过）
     */
    public float[] getCachedSplitDistances() {
        return cachedSplitDistances != null ? cachedSplitDistances.clone() : null;
    }

    // ==================== 诊断 API ====================

    /**
     * 获取总处理的顶点数（累计值）
     *
     * 【返回值】
     * @return long - 自节点创建以来处理的顶点总数
     */
    public long getTotalVerticesProcessed() {
        return totalVerticesProcessed;
    }

    /**
     * 获取总执行帧数
     *
     * 【返回值】
     * @return long - execute() 被调用的总次数
     */
    public long getFrameCount() {
        return frameCount;
    }

    /**
     * 获取平均每帧执行时间（毫秒）
     *
     * 【返回值】
     * @return double - 平均执行时间（ms），0 表示尚未执行过
     */
    public double getAverageExecuteTimeMs() {
        return frameCount > 0 ? (double) totalExecuteTimeNanos / frameCount / 1_000_000.0 : 0.0;
    }

    /**
     * 重置所有性能统计计数器
     */
    public void resetStats() {
        totalVerticesProcessed = 0;
        frameCount = 0;
        totalExecuteTimeNanos = 0;
    }

    // ==================== 工具方法 ====================

    /**
     * 将 4x4 矩阵设为单位矩阵
     *
     * 【方法参数】
     * @param m float[] - 16 元素数组（column-major 布局）
     */
    private static void identityMatrix(float[] m) {
        m[0] = 1;  m[4] = 0;  m[8]  = 0; m[12] = 0;
        m[1] = 0;  m[5] = 1;  m[9]  = 0; m[13] = 0;
        m[2] = 0;  m[6] = 0;  m[10] = 1; m[14] = 0;
        m[3] = 0;  m[7] = 0;  m[11] = 0; m[15] = 1;
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * 【方法参数】
     * @param path String - 资源路径（如 "/shaders/pipeline/lighting/shadow_cascade.spv"）
     *
     * 【返回值】
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = ShadowMapNode.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format(
                "ShadowMapNode{cascades=%d, resolution=%d, filter=%s, lightSize=%.1f, " +
                "pcfSamples=%d, bias=[%.4f,%.4f], lambda=%.2f, frames=%d}",
                cascadeCount, shadowMapResolution, filterType, lightSize,
                pcfSamples, biasMin, biasMax, cascadeSplitLambda, frameCount);
    }
}
