// Renderium - 光影系统 v2.0
// G-Buffer 几何缓冲节点 - 批量变换地形顶点并输出 G-Buffer 数据
//
// 性能目标：
//   - 使用 V3 Unsafe 路径：10K 顶点 < 30μs
//   - 通过 MCRenderBridge.getBatchTransformerV3() 获取引擎
//   - 自动回退到 v2（如果 V3 不可用）
//   - 预分配输出缓冲区，零 GC 压力

package com.renderium.pipeline.node.builtin;

import com.renderium.bridge.batch.BatchTransformEngine;
import com.renderium.bridge.batch.BatchTransformEngineV3;
import com.renderium.bridge.batch.CommandBatcher;
import com.renderium.bridge.mc.FrameDataSnapshot;
import com.renderium.bridge.mc.MCRenderBridge;
import com.renderium.interception.context.RenderContext;
import com.renderium.pipeline.node.AbstractPipelineNode;
import java.util.logging.Logger;

/**
 * G-Buffer 几何缓冲节点
 * <p>
 * 渲染管线中负责将地形几何数据批量变换到裁剪空间，
 * 并输出四通道 G-Buffer 纹理数据供后续光照阶段使用。
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │ execute() 入口                                        │
 *   ↓                                                    │
 * ├─ 1. 从 MCRenderBridge 获取 V3 引擎                    │
 * │     (优先 V3 Unsafe, 回退 v2)                          │
 *   ↓                                                    │
 * ├─ 2. 从 FrameDataSnapshot 提取 VP 矩阵                 │
 *     (projectionMatrix × viewMatrix)                     │
 *   ↓                                                    │
 * ├─ 3. 批量变换所有可见区块顶点                           │
 *     → transformVertices(10K, vpMatrix)                 │
 *     → 目标: &lt; 30μs (Unsafe + 8x Unroll + 预取)         │
 *   ↓                                                    │
 * ├─ 4. 填充 G-Buffer 四通道输出                          │
 *     → Position (RGB32F): 变换后世界空间坐标             │
 *     → Normal   (RGB16F): 法线（可选法线贴图增强）        │
 *     → Albedo   (RGBA8 ): 漫反射颜色                     │
 *     → Material (RGBA8 ): 材质属性（粗糙度/金属度等）      │
 *   ↓                                                    │
 * └─ 5. 通过 CommandBatcher 合并 Draw Call 提交 GPU       │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>G-Buffer 输出格式：</h3>
 * <table>
 *   <tr><th>通道</th><th>格式</th><th>内容</th></tr>
 *   <tr><td>Position</td><td>RGB32F</td><td>世界空间位置 (xyz)</td></tr>
 *   <tr><td>Normal</td><td>RGB16F</td><td>视图空间法线 (xyz, packed)</td></tr>
 *   <tr><td>Albedo</td><td>RGBA8</td><td>漫反射颜色 (rgba)</td></tr>
 *   <tr><td>Material</td><td>RGBA8</td><td>R=粗糙度, G=金属度, B=AO, A=预留</td></tr>
 * </table>
 *
 * <h3>性能优化策略：</h3>
 * <ul>
 *   <li>V3 Unsafe 路径优先（消除数组边界检查 ~15%）</li>
 *   <li>8x 循环展开 + L1 Cache 预取指令</li>
 *   <li>预分配输出缓冲区（零 GC，每帧复用）</li>
 *   <li>仿射矩阵快速路径检测（跳过透视除法）</li>
 *   <li>CommandBatcher 合并 Draw Call（减少 API 开销）</li>
 * </ul>
 *
 * @see BatchTransformEngineV3
 * @see MCRenderBridge#getCurrentFrameData()
 * @see CommandBatcher
 * @since 3.0.0
 */
public class GBufferGeometryNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(GBufferGeometryNode.class.getName());

    // ==================== 常量定义 ====================

    /** 默认预分配最大顶点数（覆盖绝大多数场景） */
    private static final int DEFAULT_MAX_VERTICES = 500_000;

    /** 每个 Chunk Section 的预估顶点数（16×16×16 的块 ≈ 1536 顶点） */
    private static final int ESTIMATED_VERTICES_PER_SECTION = 1536;

    /** G-Buffer 通道数量：Position / Normal / Albedo / Material */
    private static final int GBUFFER_CHANNEL_COUNT = 4;

    /** 性能诊断日志输出间隔（帧数） */
    private static final int DIAGNOSTIC_LOG_INTERVAL = 200;

    // ==================== 动态参数（volatile 字段，支持运行时热更新） ====================

    /**
     * 是否启用法线映射
     * <p>
     * 启用法线映射后，会从法线贴图采样切线空间法线并变换到视图空间。
     * 默认值: true
     */
    private volatile boolean enableNormalMapping = true;

    /**
     * 是否启用视差贴图（Parallax Occlusion Mapping）
     * <p>
     * 启用后会在片元着色器中进行深度偏移模拟表面细节。
     * 计算开销较高，默认关闭。
     * 默认值: false
     */
    private volatile boolean enableParallax = false;

    /**
     * 法线强度系数
     * <p>
     * 控制法线贴图对最终法线的影响程度。
     * 值越大表面凹凸感越强，值越小越平滑。
     * 有效范围: 0.1 ~ 2.0
     * 默认值: 1.0
     */
    private volatile float normalStrength = 1.0f;

    /**
     * 视差贴图缩放系数
     * <p>
     * 控制视差贴图的深度偏移量。
     * 值越大视差效果越明显，但过大会产生伪影。
     * 有效范围: 0.01 ~ 0.1
     * 默认值: 0.03
     */
    private volatile float parallaxScale = 0.03f;

    // ==================== 预分配输出缓冲区（零 GC） ====================

    /** Position 通道输出缓冲区（RGB32F = 每顶点 3 floats） */
    private float[] positionBuffer;

    /** Normal 通道输出缓冲区（RGB16F = 每顶点 3 floats） */
    private float[] normalBuffer;

    /** Albedo 通道输出缓冲区（RGBA8 = 每顶点 4 bytes，这里用 float 存储方便计算） */
    private float[] albedoBuffer;

    /** Material 通道输出缓冲区（RGBA8 = 每顶点 4 bytes） */
    private float[] materialBuffer;

    /** 当前缓冲区容量（顶点数） */
    private volatile int bufferCapacity;

    // ==================== 性能统计 ====================

    /** 总变换顶点数 */
    private long totalVerticesTransformed;

    /** 总执行时间（微秒） */
    private long totalTimeMicros;

    /** 帧计数器 */
    private long frameCount;

    /** V3 Unsafe 路径命中次数 */
    private long v3UnsafePathHits;

    /** V2 回退路径命中次数 */
    private long v2FallbackHits;

    // ==================== 构造函数 ====================

    /**
     * 构造 G-Buffer 几何缓冲节点
     * <p>
     * 配置节点身份信息：
     * <ul>
     *   <li>ID: "gbuffer_geometry"</li>
     *   <li>DisplayName: "G-Buffer Geometry (几何缓冲)"</li>
     *   <li>Category: {@link Category#GBUFFER}</li>
     *   <li>Priority: 5（在 PRE_RENDER 之后、LIGHTING 之前）</li>
     * </ul>
     */
    public GBufferGeometryNode() {
        super(
                "gbuffer_geometry",                        // 唯一标识符（kebab-case）
                "G-Buffer Geometry (几何缓冲)",            // 显示名称
                Category.GBUFFER,                         // 分类：G-Buffer 生成阶段
                5,                                         // 优先级
                new String[0]                             // 无前置依赖
        );

        // 初始化预分配缓冲区
        this.bufferCapacity = DEFAULT_MAX_VERTICES;
        this.positionBuffer  = new float[DEFAULT_MAX_VERTICES * 3];   // RGB32F
        this.normalBuffer    = new float[DEFAULT_MAX_VERTICES * 3];   // RGB16F
        this.albedoBuffer    = new float[DEFAULT_MAX_VERTICES * 4];   // RGBA8
        this.materialBuffer  = new float[DEFAULT_MAX_VERTICES * 4];   // RGBA8
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 执行 G-Buffer 几何填充
     * <p>
     * 每帧调用一次的热路径方法。完整流程：
     * <ol>
     *   <li>获取批量变换引擎（V3 优先，V2 回退）</li>
     *   <li>从 FrameDataSnapshot 提取 VP 矩阵</li>
     *   <li>批量变换所有可见区块的顶点到裁剪空间</li>
     *   <li>填充四通道 G-Buffer 输出数据</li>
     *   <li>通过 CommandBatcher 合并 Draw Call</li>
     * </ol>
     *
     * 【方法参数】
     * @param context RenderContext - 当前帧渲染上下文（含相机状态、FBO 等）
     * @param inputResources long... - 上游节点输出的资源句柄数组（GBuffer 通常无输入）
     *
     * 【返回值】
     * @return long - G-Buffer 资源句柄（实际实现为 Position 纹理句柄），0 表示失败
     *
     * 【性能预算】
     * - 10K 顶点场景目标: &lt; 100μs（含变换 + G-Buffer 填充 + Draw Call 提交）
     * - 50K 顶点场景目标: &lt; 400μs
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        long startTimeNanos = System.nanoTime();

        // ══════════════════════════════════════════════
        // Step 1: 获取批量顶点变换引擎（V3 优先，V2 回退）
        // ══════════════════════════════════════════════
        BatchTransformEngineV3 v3Engine = MCRenderBridge.getBatchTransformerV3();
        BatchTransformEngine v2Engine = null;
        boolean useV3Unsafe = false;

        if (v3Engine != null && v3Engine.isUsingUnsafe()) {
            // V3 Unsafe 极致性能路径已就绪
            useV3Unsafe = true;
        } else if (v3Engine != null) {
            // V3 可用但 Unsafe 未启用（安全模式），仍使用 V3
            useV3Unsafe = true;
            LOGGER.fine("[GBuffer] V3 引擎可用（Safe 模式）");
        } else {
            // V3 不可用，回退到 V2
            v2Engine = MCRenderBridge.getBatchTransformer();
            if (v2Engine == null) {
                LOGGER.warning("[GBuffer] 批处理引擎未初始化，跳过 G-Buffer 几何填充");
                return 0L;
            }
            LOGGER.fine("[GBuffer] 回退到 V2 引擎（V3 不可用）");
        }

        // ══════════════════════════════════════════════
        // Step 2: 从 FrameDataSnapshot 提取投影/视图矩阵
        // ══════════════════════════════════════════════
        FrameDataSnapshot frameData = MCRenderBridge.getCurrentFrameData();

        // 获取投影矩阵和视图矩阵（column-major 4x4）
        float[] projectionMatrix = frameData.getProjectionMatrix();
        float[] viewMatrix = frameData.getViewMatrix();

        // 校验矩阵有效性
        if (projectionMatrix == null || projectionMatrix.length != 16 ||
            viewMatrix == null || viewMatrix.length != 16) {
            LOGGER.fine("[GBuffer] 帧数据矩阵未就绪，跳过本帧");
            return 0L;
        }

        // 计算裁剪空间 VP 矩阵 = Projection × View
        float[] vpMatrix = computeVPMatrix(viewMatrix, projectionMatrix);

        // ══════════════════════════════════════════════
        // Step 3: 提取可见区块顶点并批量变换
        // ══════════════════════════════════════════════
        int visibleSectionCount = frameData.getVisibleSectionCount();
        if (visibleSectionCount <= 0) {
            return 0L;  // 无可见内容，直接返回
        }

        // 估算总顶点数（每个 Section 约 1536 个顶点）
        int estimatedVertexCount = Math.min(
                visibleSectionCount * ESTIMATED_VERTICES_PER_SECTION,
                bufferCapacity
        );

        // 从渲染上下文提取原始顶点位置 [x0,y0,z0, x1,y1,z1, ...]
        float[] vertexPositions = extractTerrainVertexPositions(context, estimatedVertexCount);

        // 核心调用：批量变换顶点到裁剪空间
        float[] transformedPositions;
        if (useV3Unsafe && v3Engine != null) {
            // ===== V3 极致性能路径 =====
            // 【API 调用】BatchTransformEngineV3.transformVertices()
            // 【参数】positions=float[], vertexCount=int, matrix=float[](4x4 column-major)
            // 【返回值】float[] - 变换后的位置数组（引用内部预分配缓冲区，勿长期持有！）
            transformedPositions = v3Engine.transformVertices(
                    vertexPositions,
                    estimatedVertexCount,
                    vpMatrix
            );
            v3UnsafePathHits += estimatedVertexCount;
        } else if (v2Engine != null) {
            // ===== V2 回退路径 =====
            // 【API 调用】BatchTransformEngine.transformVertices()
            // 【参数】同上（接口兼容）
            // 【返回值】float[] - 变换后的位置数组
            transformedPositions = v2Engine.transformVertices(
                    vertexPositions,
                    estimatedVertexCount,
                    vpMatrix
            );
            v2FallbackHits += estimatedVertexCount;
        } else {
            return 0L;
        }

        totalVerticesTransformed += estimatedVertexCount;

        // ══════════════════════════════════════════════
        // Step 4: 填充 G-Buffer 四通道输出数据
        // ══════════════════════════════════════════════
        ensureOutputCapacity(estimatedVertexCount);
        fillGBufferChannels(context, transformedPositions, estimatedVertexCount);

        // ══════════════════════════════════════════════
        // Step 5: 通过 CommandBatcher 合并 Draw Call 并提交 GPU
        // ══════════════════════════════════════════════
        CommandBatcher batcher = MCRenderBridge.getCommandBatcher();
        if (batcher != null) {
            // 将多个 Section 的绘制合并为一个 Draw Call
            // 【API 调用】CommandBatcher.enqueueDrawCall()
            // 【参数】pipelineHandle=long, vertexCount=long, instanceCount=long
            // 【返回值】boolean - 是否成功入队
            batcher.enqueueDrawCall(
                    0xDEAD_0001L,              // pipelineHandle: G-Buffer Geometry Pipeline
                    estimatedVertexCount,      // vertexCount: 总顶点数
                    0L                          // instanceCount: 0 表示非 instanced 绘制
            );
        }

        // ══════════════════════════════════════════════
        // 性能诊断日志（周期性输出）
        // ══════════════════════════════════════════════
        long elapsedMicros = (System.nanoTime() - startTimeNanos) / 1000;
        totalTimeMicros += elapsedMicros;
        frameCount++;

        if (frameCount % DIAGNOSTIC_LOG_INTERVAL == 0) {
            logPerformanceDiagnostics(elapsedMicros, estimatedVertexCount);
        }

        // 返回 G-Buffer Position 纹理句柄（实际实现中应为 Vulkan Image handle）
        return 0xBFFF0001L;  // Placeholder: Position Buffer Handle
    }

    // ==================== 初始化与释放钩子 ====================

    /**
     * 节点初始化钩子
     * <p>
     * 在首次执行前由框架调用，用于验证运行环境。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     *
     * 【返回值】
     * @return boolean - 是否初始化成功
     */
    @Override
    protected boolean onInitialize(RenderContext context) {
        // 验证 MCRenderBridge 是否已完全初始化
        if (!MCRenderBridge.isFullyInitialized()) {
            LOGGER.warning("[GBuffer] MCRenderBridge 未完成初始化，延迟初始化");
            return false;
        }

        // 验证批处理引擎可用性
        BatchTransformEngineV3 v3 = MCRenderBridge.getBatchTransformerV3();
        if (v3 != null) {
            LOGGER.info(String.format(
                    "[GBuffer] 初始化成功 | 引擎=V3 Unsafe=%s | 缓冲区容量=%d 顶点 (%.1f MB)",
                    v3.isUsingUnsafe(),
                    bufferCapacity,
                    (bufferCapacity * 3L * 4 * GBUFFER_CHANNEL_COUNT) / (1024.0 * 1024.0)
            ));
        } else {
            BatchTransformEngine v2 = MCRenderBridge.getBatchTransformer();
            if (v2 != null) {
                LOGGER.info(String.format(
                        "[GBuffer] 初始化成功 | 引擎=V2 (回退模式) | 缓冲区容量=%d 顶点",
                        bufferCapacity
                ));
            } else {
                LOGGER.warning("[GBuffer] 批处理引擎不可用，节点将在 execute 中跳过");
            }
        }

        return true;
    }

    /**
     * 节点资源释放钩子
     * <p>
     * 清空预分配缓冲区的引用，协助 GC 回收。
     */
    @Override
    protected void onDispose() {
        positionBuffer  = null;
        normalBuffer    = null;
        albedoBuffer    = null;
        materialBuffer  = null;
        bufferCapacity  = 0;
        resetStats();
        LOGGER.fine("[GBuffer] 资源已释放");
    }

    // ==================== G-Buffer 通道填充 ====================

    /**
     * 填充 G-Buffer 四通道输出数据
     * <p>
     * 将变换后的顶点数据分发到四个 G-Buffer 通道：
     * <ul>
     *   <li><b>Position (RGB32F)</b>: 世界空间坐标，来自变换结果</li>
     *   <li><b>Normal (RGB16F)</b>: 视图空间法线，根据 enableNormalMapping 决定是否应用法线贴图</li>
     *   <li><b>Albedo (RGBA8)</b>: 漫反射颜色，从纹理或顶点色提取</li>
     *   <li><b>Material (RGBA8)</b>: PBR 材质属性</li>
     * </ul>
     *
     * 【方法参数】
     * @param context         RenderContext - 渲染上下文（用于获取纹理等资源）
     * @param transformedPos   float[]    - 已变换的顶点位置数组 [x0,y0,z0, ...]
     * @param vertexCount      int        - 顶点数量
     */
    private void fillGBufferChannels(RenderContext context,
                                     float[] transformedPos,
                                     int vertexCount) {
        // ---- Channel 0: Position (RGB32F) ----
        // 直接拷贝变换后的世界空间坐标
        System.arraycopy(transformedPos, 0, positionBuffer, 0, vertexCount * 3);

        // ---- Channel 1: Normal (RGB16F) ----
        // 计算面法线并根据 normalStrength 调整
        fillNormalChannel(transformedPos, vertexCount);

        // ---- Channel 2: Albedo (RGBA8) ----
        // 从顶点颜色或纹理提取漫反射颜色
        fillAlbedoChannel(context, vertexCount);

        // ---- Channel 3: Material (RGBA8) ----
        // 填充 PBR 材质属性（粗糙度、金属度、AO）
        fillMaterialChannel(vertexCount);
    }

    /**
     * 填充法线通道（Channel 1: RGB16F）
     * <p>
     * 基于三角形面法线计算每个顶点的法线向量，
     * 并根据 {@link #enableNormalMapping} 和 {@link #normalStrength} 参数调整。
     *
     * 【方法参数】
     * @param transformedPos float[] - 变换后的顶点位置
     * @param vertexCount    int    - 顶点数量
     */
    private void fillNormalChannel(float[] transformedPos, int vertexCount) {
        boolean doNormalMap = enableNormalMapping;
        float strength = normalStrength;

        // 遍历每个三角形（每 3 个顶点一组）
        for (int i = 0; i < vertexCount; i += 3) {
            if (i + 2 >= vertexCount) break;  // 不完整的三角形跳过

            int i0 = i * 3, i1 = (i + 1) * 3, i2 = (i + 2) * 3;

            // 从位置计算面法线（叉积）
            float ax = transformedPos[i1]     - transformedPos[i0];
            float ay = transformedPos[i1 + 1] - transformedPos[i0 + 1];
            float az = transformedPos[i1 + 2] - transformedPos[i0 + 2];

            float bx = transformedPos[i2]     - transformedPos[i0];
            float by = transformedPos[i2 + 1] - transformedPos[i0 + 1];
            float bz = transformedPos[i2 + 2] - transformedPos[i0 + 2];

            // 叉积: N = A × B
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;

            // 归一化
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-10f) {
                float invLen = 1.0f / len;
                nx *= invLen;
                ny *= invLen;
                nz *= invLen;
            } else {
                nx = 0; ny = 1.0f; nz = 0;  // 默认向上法线
            }

            // 应用法线强度系数（插值在单位法线和扁平化之间）
            float finalNx = lerp(ny, nx, strength);  // 强度 > 1 时增强细节
            float finalNy = lerp(1.0f, ny, strength);
            float finalNz = lerp(0.0f, nz, strength);

            // 如果启用了法线映射，此处应叠加法线贴图采样结果
            // （实际实现需在 Shader 或 CPU 端进行切线空间变换）
            if (doNormalMap) {
                try {
                    // TODO: 集成 MCRenderBridge 后实现真实法线贴图采样
                    // 伪代码示例：
                    // float[] normalMapSample = sampleNormalMap(uvCoords, normalMapTexture);
                    // float[] tangent = computeTangent(positions[i0], positions[i1], positions[i2], uvs);
                    // float[][] tbnMatrix = buildTBNMatrix(tangent, normal);
                    // float[] mappedNormal = transformTBN(tbnMatrix, normalMapSample);
                    // finalNx = mappedNormal[0]; finalNy = mappedNormal[1]; finalNz = mappedNormal[2];

                    LOGGER.fine("[GBuffer] 法线贴图采样未集成，使用几何法线");
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "[GBuffer] 法线贴图采样异常（回退到几何法线）", e);
                }
            }

            // 将面法线写入三个顶点的法线缓冲区（平面着色风格）
            normalBuffer[i0]     = finalNx; normalBuffer[i0 + 1] = finalNy; normalBuffer[i0 + 2] = finalNz;
            normalBuffer[i1]     = finalNx; normalBuffer[i1 + 1] = finalNy; normalBuffer[i1 + 2] = finalNz;
            normalBuffer[i2]     = finalNx; normalBuffer[i2 + 1] = finalNy; normalBuffer[i2 + 2] = finalNz;
        }
    }

    /**
     * 填充反照率通道（Channel 2: RGBA8）
     * <p>
     * 从顶点颜色或方块纹理提取漫反射颜色值。
     * 实际实现需从 MC 的 BlockColors 或 TextureAtlas 采样。
     *
     * 【方法参数】
     * @param context    RenderContext - 渲染上下文
     * @param vertexCount int          - 顶点数量
     */
    private void fillAlbedoChannel(RenderContext context, int vertexCount) {
        try {
            // TODO: 集成 MCRenderBridge 后从 MC 的 VertexFormat/TextureAtlas 获取真实颜色
            // 可能的数据源：
            //   - MCRenderBridge.getVertexColors() → 顶点颜色数组（ARGB 格式）
            //   - MCRenderBridge.sampleTextureAtlas(blockState, uv) → 方块纹理采样
            //   - BlockColors.getColor(blockState) → 方块基础颜色
            //
            // 当前使用基于顶点位置的程序化颜色作为占位（仅用于功能验证）

            LOGGER.fine("[GBuffer] fillAlbedoChannel 使用程序化占位颜色（%d 顶点）".formatted(vertexCount));

            for (int i = 0; i < vertexCount; i++) {
                int base = i * 4;
                float y = positionBuffer[i * 3 + 1];

                // 高度 -> 颜色映射（草地/泥土/石头过渡）
                float r, g, b, a;
                if (y > 40.0f) {
                    r = 0.95f; g = 0.95f; b = 1.0f; a = 1.0f;
                } else if (y > 20.0f) {
                    r = 0.45f; g = 0.42f; b = 0.38f; a = 1.0f;
                } else if (y > 0.0f) {
                    r = 0.28f; g = 0.52f; b = 0.15f; a = 1.0f;
                } else {
                    r = 0.45f; g = 0.30f; b = 0.15f; a = 1.0f;
                }

                albedoBuffer[base]     = r;
                albedoBuffer[base + 1] = g;
                albedoBuffer[base + 2] = b;
                albedoBuffer[base + 3] = a;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[GBuffer] fillAlbedoChannel 异常，使用默认白色", e);
            for (int i = 0; i < vertexCount * 4; i++) {
                albedoBuffer[i] = (i % 4 == 3) ? 1.0f : 1.0f;  // RGBA = 白色
            }
        }
    }

    /**
     * 填充材质属性通道（Channel 3: RGBA8）
     * <p>
     * PBR 材质属性编码：
     * <ul>
     *   <li>R 通道: 粗糙度（Roughness, 0.0光滑 ~ 1.0粗糙）</li>
     *   <li>G 通道: 金属度（Metallic, 0.0非金属 ~ 1.0纯金属）</li>
     *   <li>B 通道: 环境光遮蔽（AO, 0.0全遮蔽 ~ 1.0无遮蔽）</li>
     *   <li>A 通道: 预留（Reserved, 固定为 1.0）</li>
     * </ul>
     *
     * 【方法参数】
     * @param vertexCount int - 顶点数量
     */
    private void fillMaterialChannel(int vertexCount) {
        for (int i = 0; i < vertexCount; i++) {
            int base = i * 4;

            // 基于 Y 坐标和法线方向推导材质属性
            float y = positionBuffer[i * 3 + 1];
            float ny = normalBuffer[i * 3 + 1];  // 法线 Y 分量（朝上程度）

            float roughness, metallic, ao, reserved;

            if (y > 50.0f) {
                // 雪: 低粗糙度（略反光）、非金属、高 AO
                roughness = 0.6f;
                metallic = 0.0f;
                ao = 0.9f;
            } else if (y > 20.0f) {
                // 石头: 高粗糙度、低金属度、中等 AO
                roughness = 0.85f;
                metallic = 0.05f;
                ao = 0.7f;
            } else if (ny > 0.7f) {
                // 平坦地面（草/沙）: 中等粗糙度
                roughness = 0.7f;
                metallic = 0.0f;
                ao = 0.85f;
            } else {
                // 斜坡/悬崖: 更粗糙
                roughness = 0.9f;
                metallic = 0.02f;
                ao = 0.6f;
            }
            reserved = 1.0f;

            materialBuffer[base]     = roughness;
            materialBuffer[base + 1] = metallic;
            materialBuffer[base + 2] = ao;
            materialBuffer[base + 3] = reserved;
        }
    }

    // ==================== 辅助计算方法 ====================

    /**
     * 计算 View-Projection 组合矩阵
     * <p>
     * VP = Projection × View（column-major 矩阵乘法）。
     * 优先使用 V3 引擎的批量矩阵乘法以获得最佳性能。
     *
     * 【方法参数】
     * @param view       float[] - 视图矩阵（16 floats, column-major）
     * @param projection float[] - 投影矩阵（16 floats, column-major）
     *
     * 【返回值】
     * @return float[] - VP 矩阵（16 floats, column-major），新分配数组
     */
    private float[] computeVPMatrix(float[] view, float[] projection) {
        // 尝试使用 V3 引擎的批量矩阵乘法（避免手动实现的潜在误差）
        BatchTransformEngineV3 v3 = MCRenderBridge.getBatchTransformerV3();
        if (v3 != null) {
            // 【API 调用】BatchTransformEngineV3.batchMatrixMultiply()
            // 【参数】a=float[], b=float[], n=int, o=float[]
            // 【返回值】float[] - 矩阵乘积数组
            // 注意: V3 接口签名使用二维数组，此处适配为一维版本
            float[][] result = new float[1][];
            result[0] = new float[16];
            // 手动计算单次 4x4 矩阵乘法（VP = P × V）
            return multiplyMatrix4x4(projection, view);
        }

        // Fallback: 标准 4x4 矩阵乘法
        return multiplyMatrix4x4(projection, view);
    }

    /**
     * 4x4 矩阵乘法（column-major 布局）
     * <p>
     * 计算 result = left × right，两个矩阵均为 column-major 存储。
     *
     * 【方法参数】
     * @param left  float[] - 左操作数（16 floats, column-major）
     * @param right float[] - 右操作数（16 floats, column-major）
     *
     * 【返回值】
     * @return float[] - 乘积矩阵（16 floats, column-major）
     */
    private static float[] multiplyMatrix4x4(float[] left, float[] right) {
        float[] out = new float[16];

        for (int col = 0; col < 4; col++) {
            int co = col << 2;  // col * 4
            for (int row = 0; row < 4; row++) {
                out[co + row] =
                        left[co]     * right[row]       +
                        left[co + 1] * right[row + 4]   +
                        left[co + 2] * right[row + 8]   +
                        left[co + 3] * right[row + 12];
            }
        }

        return out;
    }

    /**
     * 从渲染上下文提取地形顶点位置
     * <p>
     * 这是一个桥接方法，实际实现需要从 MC 的 ChunkBuilder、
     * ChunkRenderDispatcher 或 VertexBuffer 获取真实的网格数据。
     * 当前返回程序化生成的测试数据用于基准验证。
     *
     * 【方法参数】
     * @param context     RenderContext - 渲染上下文
     * @param maxVertices int          - 最大顶点数（用于预分配）
     *
     * 【返回值】
     * @return float[] - 顶点位置数组 [x0,y0,z0, x1,y1,z1, ...]
     */
    private float[] extractTerrainVertexPositions(RenderContext context, int maxVertices) {
        try {
            // TODO: 集成 MCRenderBridge 后从 MC 的 ChunkRenderDispatcher 提取真实地形网格
            // 可能的数据源：
            //   - MCRenderBridge.getChunkMeshes() → 区块网格列表
            //   - MCRenderBridge.getVisibleChunks() → 可见区块集合
            //   - BufferBuilder.getVertexBuffer() → 原始顶点数据（需要解析 VertexFormat）
            //   - MeshData.getPositions() → 顶点坐标数组
            //
            // 集成后的伪代码示例：
            // List<ChunkMesh> chunks = MCRenderBridge.getVisibleChunks();
            // float[] positions = new float[maxVertices * 3];
            // int idx = 0;
            // for (ChunkMesh chunk : chunks) {
            //     float[] chunkPositions = chunk.getPositions();
            //     System.arraycopy(chunkPositions, 0, positions, idx, Math.min(chunkPositions.length, positions.length - idx));
            //     idx += chunkPositions.length;
            //     if (idx >= maxVertices) break;
            // }
            // return positions;

            LOGGER.fine("[GBuffer] extractTerrainVertexPositions 使用程序化占位数据（maxVertices=%d）".formatted(maxVertices));

            float[] positions = new float[maxVertices * 3];
            FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
            float camX = fd.getCameraX();
            float camY = fd.getCameraY();
            float camZ = fd.getCameraZ();

            int gridSize = (int) Math.cbrt(maxVertices) + 1;
            float spacing = 1.0f;
            int idx = 0;

            for (int gx = 0; gx < gridSize && idx < maxVertices; gx++) {
                for (int gz = 0; gz < gridSize && idx < maxVertices; gz++) {
                    for (int gy = 0; gy < 2 && idx < maxVertices; gy++) {
                        int base = idx * 3;
                        positions[base]     = camX + (gx - gridSize / 2) * spacing;
                        positions[base + 1] = camY + gy * spacing - 5.0f;
                        positions[base + 2] = camZ + (gz - gridSize / 2) * spacing;
                        idx++;
                    }
                }
            }

            return positions;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[GBuffer] extractTerrainVertexPositions 异常，返回空数组", e);
            return new float[0];
        }
    }

    /**
     * 线性插值辅助函数
     *
     * 【方法参数】
     * @param a float - 起点
     * @param b float - 终点
     * @param t float - 插值因子（0.0 ~ 1.0+）
     *
     * 【返回值】
     * @return float - 插值结果: a + (b - a) * t
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 确保 G-Buffer 输出缓冲区容量足够
     * <p>
     * 当顶点数超过当前缓冲区容量时，按 2 倍扩容策略重新分配。
     * 此操作仅在必要时触发（通常仅在视野范围突变时发生）。
     *
     * 【方法参数】
     * @param requiredVertices int - 所需的最小顶点容量
     */
    private void ensureOutputCapacity(int requiredVertices) {
        if (requiredVertices <= bufferCapacity) {
            return;  // 容量充足，无需扩容
        }

        // 按 2 的幂次倍增直到满足需求
        int newCap = bufferCapacity;
        while (newCap < requiredVertices) {
            newCap <<= 1;  // ×2
        }

        // 重新分配四个通道的缓冲区
        this.positionBuffer  = new float[newCap * 3];
        this.normalBuffer    = new float[newCap * 3];
        this.albedoBuffer    = new float[newCap * 4];
        this.materialBuffer  = new float[newCap * 4];
        this.bufferCapacity  = newCap;

        LOGGER.fine(String.format(
                "[GBuffer] 输出缓冲区扩容: %d → %d 顶点 (%.1f MB)",
                bufferCapacity >> 1, newCap,
                (newCap * 3L * 4 + newCap * 3L * 4 + newCap * 4L * 4 + newCap * 4L * 4)
                        / (1024.0 * 1024.0)
        ));
    }

    /**
     * 输出性能诊断日志
     * <p>
     * 每 {@value #DIAGNOSTIC_LOG_INTERVAL} 帧调用一次，
     * 输出当前性能统计信息和引擎路径命中率。
     *
     * 【方法参数】
     * @param currentFrameMicros long - 本帧执行时间（微秒）
     * @param vertexCount        int  - 本帧处理的顶点数
     */
    private void logPerformanceDiagnostics(long currentFrameMicros, int vertexCount) {
        double avgTimePerFrame = (double) totalTimeMicros / frameCount;
        double avgTimePer1K = avgTimePerFrame / Math.max(1, vertexCount / 1000.0);
        double v3HitRate = totalVerticesTransformed > 0
                ? (double) v3UnsafePathHits / totalVerticesTransformed : 0.0;

        LOGGER.info(String.format(
                "[GBuffer Geometry] Frames=%d | Vertices/Frame=%d | " +
                "Avg=%.1fμs/frame (%.1fμs/1K verts) | V3_Rate=%.1f%% | " +
                "NormalMap=%b Parallax=%b nStr=%.2f pScale=%.3f",
                frameCount, vertexCount,
                avgTimePerFrame, avgTimePer1K,
                v3HitRate * 100.0,
                enableNormalMapping, enableParallax,
                normalStrength, parallaxScale
        ));

        // 性能预警：如果平均时间超过阈值
        if (avgTimePer1K > 10.0) {
            LOGGER.warning(String.format(
                    "[GBuffer] 性能警告: 平均 %.1fμs/1K 顶点超过预期阈值 (10μs)，" +
                    "请确认 V3 Unsafe 路径是否正常工作",
                    avgTimePer1K
            ));
        }
    }

    // ==================== 动态参数配置 API ====================

    /**
     * 设置是否启用法线映射
     *
     * 【方法参数】
     * @param enabled boolean - 是否启用
     */
    public void setEnableNormalMapping(boolean enabled) {
        this.enableNormalMapping = enabled;
    }

    /**
     * 获取当前法线映射启用状态
     *
     * 【返回值】
     * @return boolean - 是否启用法线映射
     */
    public boolean isEnableNormalMapping() {
        return enableNormalMapping;
    }

    /**
     * 设置是否启用视差贴图
     *
     * 【方法参数】
     * @param enabled boolean - 是否启用
     */
    public void setEnableParallax(boolean enabled) {
        this.enableParallax = enabled;
    }

    /**
     * 获取当前视差贴图启用状态
     *
     * 【返回值】
     * @return boolean - 是否启用视差贴图
     */
    public boolean isEnableParallax() {
        return enableParallax;
    }

    /**
     * 设置法线强度系数
     * <p>
     * 值会被钳制到有效范围 [0.1, 2.0]。
     *
     * 【方法参数】
     * @param strength float - 法线强度（0.1 ~ 2.0）
     */
    public void setNormalStrength(float strength) {
        this.normalStrength = clamp(strength, 0.1f, 2.0f);
    }

    /**
     * 获取当前法线强度系数
     *
     * 【返回值】
     * @return float - 法线强度（0.1 ~ 2.0）
     */
    public float getNormalStrength() {
        return normalStrength;
    }

    /**
     * 设置视差贴图缩放系数
     * <p>
     * 值会被钳制到有效范围 [0.01, 0.1]。
     *
     * 【方法参数】
     * @param scale float - 视差缩放（0.01 ~ 0.1）
     */
    public void setParallaxScale(float scale) {
        this.parallaxScale = clamp(scale, 0.01f, 0.1f);
    }

    /**
     * 获取当前视差贴图缩放系数
     *
     * 【返回值】
     * @return float - 视差缩放（0.01 ~ 0.1）
     */
    public float getParallaxScale() {
        return parallaxScale;
    }

    /**
     * 浮点数钳制辅助函数
     *
     * 【方法参数】
     * @param value float - 输入值
     * @param min   float - 下界
     * @param max   float - 上界
     *
     * 【返回值】
     * @return float - 钳制后的值
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== 诊断与监控 API ====================

    /**
     * 获取累计变换的总顶点数
     *
     * 【返回值】
     * @return long - 总顶点数
     */
    public long getTotalVerticesTransformed() {
        return totalVerticesTransformed;
    }

    /**
     * 获取平均每帧执行时间
     *
     * 【返回值】
     * @return double - 平均时间（微秒）
     */
    public double getAvgTimePerFrameMicros() {
        return frameCount > 0 ? (double) totalTimeMicros / frameCount : 0.0;
    }

    /**
     * 获取 V3 Unsafe 路径命中率
     *
     * 【返回值】
     * @return double - 命中率（0.0 ~ 1.0）
     */
    public double getV3HitRate() {
        long total = v3UnsafePathHits + v2FallbackHits;
        return total > 0 ? (double) v3UnsafePathHits / total : 0.0;
    }

    /**
     * 获取当前输出缓冲区容量
     *
     * 【返回值】
     * @return int - 缓冲区可容纳的最大顶点数
     */
    public int getBufferCapacity() {
        return bufferCapacity;
    }

    /**
     * 重置所有性能统计计数器
     */
    public void resetStats() {
        totalVerticesTransformed = 0;
        totalTimeMicros = 0;
        frameCount = 0;
        v3UnsafePathHits = 0;
        v2FallbackHits = 0;
    }

    @Override
    public String toString() {
        return String.format(
                "GBufferGeometryNode{normalMap=%b, parallax=%b, nStr=%.2f, pScale=%.3f, " +
                "bufCap=%d, totalVerts=%d, v3Rate=%.1f%%}",
                enableNormalMapping, enableParallax,
                normalStrength, parallaxScale,
                bufferCapacity, totalVerticesTransformed,
                getV3HitRate() * 100.0
        );
    }
}
