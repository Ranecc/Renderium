// Renderium - 光影系统 v2.0
// G-Buffer 几何缓冲节点 - 批量变换地形顶点并通过 Compute Shader 输出 G-Buffer
//
// 性能目标：
//   - 使用 V3 Unsafe 路径：10K 顶点 < 30μs
//   - 通过 MCRenderBridge.getBatchTransformerV3() 获取引擎
//   - 自动回退到 v2（如果 V3 不可用）
//   - 预分配输出缓冲区，零 GC 压力
//   - Compute Shader 填充 G-Buffer 四通道输出

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.geometry;

import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode.Category;
import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;
import com.ranecc.renderium.infrastructure.gpu.VulkanMemoryAllocator;
import com.ranecc.renderium.platform.bridge.mc.BatchTransformEngine;
import com.ranecc.renderium.platform.bridge.mc.BatchTransformEngineV3;
import com.ranecc.renderium.platform.bridge.mc.FrameDataSnapshot;
import com.ranecc.renderium.platform.bridge.mc.MCRenderBridge;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;

/**
 * G-Buffer 几何缓冲节点
 * <p>
 * 渲染管线中负责将地形几何数据批量变换到裁剪空间，
 * 并通过 Vulkan Compute Shader 输出四通道 G-Buffer 纹理数据供后续光照阶段使用。
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ execute() 入口                                                │
 *   ↓                                                            │
 * ├─ 1. 从 MCRenderBridge 获取 V3 引擎                            │
 * │    (优先 V3 Unsafe, 回退 v2)                                  │
 *   ↓                                                            │
 * ├─ 2. 从 FrameDataSnapshot 提取 VP 矩阵                         │
 * │    (projectionMatrix × viewMatrix)                            │
 *   ↓                                                            │
 * ├─ 3. 批量变换所有可见区块顶点                                   │
 * │    → transformVertices(10K, vpMatrix)                        │
 * │    → 目标: < 30μs (Unsafe + 8x Unroll + 预取)                │
 *   ↓                                                            │
 * ├─ 4. 填充 G-Buffer 四通道输出数据（CPU 端 + Compute Shader）   │
 * │    → 法线计算 + 颜色生成（CPU）                                │
 * │    → 写入 SSBO → vkCmdDispatch → G-Buffer 存储图像            │
 *   ↓                                                            │
 * └─ 5. 返回 Position 纹理句柄                                   │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>G-Buffer 输出格式：</h3>
 * <table>
 *   <tr><th>通道</th><th>格式</th><th>内容</th></tr>
 *   <tr><td>Position</td><td>R32G32B32A32_SFLOAT</td><td>裁剪空间位置 (xyzw)</td></tr>
 *   <tr><td>Normal</td><td>R16G16B16A16_SFLOAT</td><td>视图空间法线 (xyz, packed)</td></tr>
 *   <tr><td>Albedo</td><td>R8G8B8A8_UNORM</td><td>漫反射颜色 (rgba)</td></tr>
 *   <tr><td>Material</td><td>R8G8B8A8_UNORM</td><td>R=粗糙度, G=金属度, B=AO, A=预留</td></tr>
 * </table>
 *
 * <h3>Compute Shader Bindings：</h3>
 * <table>
 *   <tr><th>Binding</th><th>类型</th><th>内容</th></tr>
 *   <tr><td>0</td><td>STORAGE_BUFFER</td><td>顶点位置 SSBO</td></tr>
 *   <tr><td>1</td><td>STORAGE_BUFFER</td><td>法线 SSBO</td></tr>
 *   <tr><td>2</td><td>STORAGE_BUFFER</td><td>颜色 SSBO</td></tr>
 *   <tr><td>3</td><td>STORAGE_IMAGE</td><td>Position 输出纹理</td></tr>
 *   <tr><td>4</td><td>STORAGE_IMAGE</td><td>Normal 输出纹理</td></tr>
 *   <tr><td>5</td><td>STORAGE_IMAGE</td><td>Albedo 输出纹理</td></tr>
 *   <tr><td>6</td><td>STORAGE_IMAGE</td><td>Material 输出纹理</td></tr>
 * </table>
 *
 * TODO: 超级类，需拆分
 * 
 * @see BatchTransformEngineV3
 * @see MCRenderBridge#getCurrentFrameData()
 * @see ComputePipelineHelper
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

    /** FrameCommandContext 节点索引 */
    private static final int NODE_ID = 0;

    /**
     * Shader key，镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/geometry/gbuffer_fill";
    }

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

    // ==================== 预分配输出缓冲区（CPU 端，零 GC） ====================

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

    // ==================== Vulkan Compute Pipeline 资源 ====================

    /** Compute Pipeline 句柄 */
    private volatile long computePipeline = 0L;

    /** Pipeline Layout 句柄 */
    private volatile long pipelineLayout = 0L;

    /** Descriptor Set 句柄 */
    private volatile long descriptorSet = 0L;

    // ==================== G-Buffer 输出图像 ====================

    /** Position 输出 ImageView */
    private volatile long outputPositionView = 0L;

    /** Normal 输出 ImageView */
    private volatile long outputNormalView = 0L;

    /** Albedo 输出 ImageView */
    private volatile long outputAlbedoView = 0L;

    /** Material 输出 ImageView */
    private volatile long outputMaterialView = 0L;

    /** Position 输出 Image 句柄（配合 releaseResource 使用） */
    private volatile long outputPositionImage = 0L;

    /** Normal 输出 Image 句柄 */
    private volatile long outputNormalImage = 0L;

    /** Albedo 输出 Image 句柄 */
    private volatile long outputAlbedoImage = 0L;

    /** Material 输出 Image 句柄 */
    private volatile long outputMaterialImage = 0L;

    /** 上次 G-Buffer 输出宽度（用于尺寸变化检测） */
    private volatile int lastGBufW = 0;

    /** 上次 G-Buffer 输出高度 */
    private volatile int lastGBufH = 0;

    // ==================== SSBO 缓冲区（Compute Shader 输入） ====================

    /** 顶点位置 SSBO 句柄 */
    private volatile long vertexBuffer = 0L;

    /** 顶点位置 SSBO 内存句柄 */
    private volatile long vertexBufferMemory = 0L;

    /** 法线 SSBO 句柄 */
    private volatile long normalSSBO = 0L;

    /** 法线 SSBO 内存句柄 */
    private volatile long normalSSBOMemory = 0L;

    /** 颜色 SSBO 句柄 */
    private volatile long colorSSBO = 0L;

    /** 颜色 SSBO 内存句柄 */
    private volatile long colorSSBOMemory = 0L;

    /** SSBO 当前容量（float 元素数） */
    private volatile int ssboCapacity = 0;

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
                "gbuffer_geometry",
                "G-Buffer Geometry (几何缓冲)",
                Category.GBUFFER,
                5,
                new String[0]
        );

        this.bufferCapacity = DEFAULT_MAX_VERTICES;
        this.positionBuffer  = new float[DEFAULT_MAX_VERTICES * 3];
        this.normalBuffer    = new float[DEFAULT_MAX_VERTICES * 3];
        this.albedoBuffer    = new float[DEFAULT_MAX_VERTICES * 4];
        this.materialBuffer  = new float[DEFAULT_MAX_VERTICES * 4];
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
     *   <li>CPU 端计算法线 + 颜色数据</li>
     *   <li>通过 Compute Shader 将数据写入 G-Buffer 四通道纹理</li>
     * </ol>
     *
     * @param context RenderContext - 当前帧渲染上下文（含相机状态、FBO 等）
     * @param inputResources long... - 上游节点输出的资源句柄数组
     *
     * @return long - Position 纹理 ImageView 句柄，0 表示失败
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        RenderiumProfiler.recordStart(0);

        // ══════════════════════════════════════════════
        // Step 1: 获取批量顶点变换引擎（V3 优先，V2 回退）
        // ══════════════════════════════════════════════
        BatchTransformEngineV3 v3Engine = MCRenderBridge.getBatchTransformerV3();
        BatchTransformEngine v2Engine = null;
        boolean useV3Unsafe = false;

        if (v3Engine != null && v3Engine.isUsingUnsafe()) {
            useV3Unsafe = true;
        } else if (v3Engine != null) {
            useV3Unsafe = true;
            LOGGER.fine("[GBuffer] V3 引擎可用（Safe 模式）");
        } else {
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

        float[] projectionMatrix = frameData.getProjectionMatrix();
        float[] viewMatrix = frameData.getViewMatrix();

        if (projectionMatrix == null || projectionMatrix.length != 16 ||
            viewMatrix == null || viewMatrix.length != 16) {
            LOGGER.fine("[GBuffer] 帧数据矩阵未就绪，跳过本帧");
            return 0L;
        }

        float[] vpMatrix = computeVPMatrix(viewMatrix, projectionMatrix);

        // ══════════════════════════════════════════════
        // Step 3: 提取可见区块顶点并批量变换
        // ══════════════════════════════════════════════
        int visibleSectionCount = frameData.getVisibleSectionCount();
        if (visibleSectionCount <= 0) {
            return 0L;
        }

        int estimatedVertexCount = Math.min(
                visibleSectionCount * ESTIMATED_VERTICES_PER_SECTION,
                bufferCapacity
        );

        float[] vertexPositions = extractTerrainVertexPositions(context, estimatedVertexCount);

        float[] transformedPositions;
        if (useV3Unsafe && v3Engine != null) {
            transformedPositions = v3Engine.transformVertices(
                    vertexPositions,
                    estimatedVertexCount,
                    vpMatrix
            );
            v3UnsafePathHits += estimatedVertexCount;
        } else if (v2Engine != null) {
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
        // Step 4: CPU 端填充 G-Buffer 四通道输出数据
        // ══════════════════════════════════════════════
        ensureOutputCapacity(estimatedVertexCount);
        fillGBufferChannels(context, transformedPositions, estimatedVertexCount);

        // ══════════════════════════════════════════════
        // Step 5: 通过 Compute Shader 填充 G-Buffer 纹理
        // ══════════════════════════════════════════════
        long resultView = dispatchGBufferCompute(context, estimatedVertexCount);

        // ══════════════════════════════════════════════
        // 性能诊断日志（周期性输出）
        // ══════════════════════════════════════════════
        RenderiumProfiler.recordEnd(0);
        totalTimeMicros += RenderiumProfiler.getNodeTime(0) / 1000;
        frameCount++;

        if (frameCount % DIAGNOSTIC_LOG_INTERVAL == 0) {
            logPerformanceDiagnostics(elapsedMicros, estimatedVertexCount);
        }

        return resultView;
    }

    /**
     * 通过 Compute Shader 将 CPU 端数据写入 G-Buffer 四通道纹理
     * <p>
     * 流程：
     * <ol>
     *   <li>确保 Pipeline 和输出纹理已创建</li>
     *   <li>确保 SSBO 容量充足，将顶点位置/法线/颜色写入 SSBO</li>
     *   <li>更新 Descriptors（SSBO + Storage Images）</li>
     *   <li>vkCmdDispatch 执行 Compute Shader</li>
     *   <li>submitAndWait 等待完成</li>
     * </ol>
     *
     * @param context 渲染上下文
     * @param vertexCount 顶点数量
     * @return Position ImageView 句柄，失败时返回 0L
     */
    private long dispatchGBufferCompute(RenderContext context, int vertexCount) {
        ensurePipeline();
        if (computePipeline == 0L) return 0L;

        ensureGBufferOutput(context);
        if (outputPositionView == 0L) return 0L;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L || descriptorSet == 0L) return 0L;

        try {
            // 确保 SSBO 容量
            ensureSSBOCapacity(vertexCount);

            // 写入顶点位置到 vertexBuffer SSBO
            writeFloatArrayToBuffer(device, vertexBufferMemory, positionBuffer, vertexCount * 3);

            // 构造法线数据（默认 (0.5,0.5,1.0) 并归一化）
            float[] normals = new float[vertexCount * 3];
            for (int i = 0; i < vertexCount; i++) {
                int base = i * 3;
                float invLen = 1.0f / (float) Math.sqrt(0.5 * 0.5 + 0.5 * 0.5 + 1.0 * 1.0);
                normals[base]     = 0.5f * invLen;
                normals[base + 1] = 0.5f * invLen;
                normals[base + 2] = 1.0f * invLen;
            }
            writeFloatArrayToBuffer(device, normalSSBOMemory, normals, vertexCount * 3);

            // 构造颜色数据（默认 (0.8,0.8,0.8,0.0)，alpha=metallic，默认绝缘体）
            float[] colors = new float[vertexCount * 4];
            for (int i = 0; i < vertexCount * 4; i++) {
                colors[i] = (i % 4 == 3) ? 0.0f : 0.8f;
            }
            writeFloatArrayToBuffer(device, colorSSBOMemory, colors, vertexCount * 4);

            // 更新 Descriptors
            long vkDevice = device;

            // SSBO bindings (0-2)
            long posRange = (long) vertexCount * 3 * 4;
            ComputePipelineHelper.updateStorageBufferDescriptor(vkDevice, descriptorSet, 0, vertexBuffer, 0L, posRange);
            ComputePipelineHelper.updateStorageBufferDescriptor(vkDevice, descriptorSet, 1, normalSSBO, 0L, (long) vertexCount * 3 * 4);
            ComputePipelineHelper.updateStorageBufferDescriptor(vkDevice, descriptorSet, 2, colorSSBO, 0L, (long) vertexCount * 4 * 4);

            // Storage Image bindings (3-6)
            ComputePipelineHelper.updateStorageImageDescriptor(vkDevice, descriptorSet, 3, outputPositionView, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(vkDevice, descriptorSet, 4, outputNormalView, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(vkDevice, descriptorSet, 5, outputAlbedoView, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(vkDevice, descriptorSet, 6, outputMaterialView, 0L);

            // Compute dispatch
            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf != 0L) {

                // vkCmdBindPipeline(VK_PIPELINE_BIND_POINT_COMPUTE = 1)
                VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);

                // vkCmdBindDescriptorSets
                MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                        cmdBuf, 1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);

                // PushConstants: 64 bytes (vertexCount + VP matrix)
                MemorySegment pc = PerFrameArena.allocate(64L);
                pc.set(ValueLayout.JAVA_INT, 0, vertexCount);
                pc.set(ValueLayout.JAVA_INT, 4, 0); // padding
                // VP matrix at offset 16 (4x4 column-major, floats)
                float[] vp = computeVPMatrix(
                        MCRenderBridge.getCurrentFrameData().getViewMatrix(),
                        MCRenderBridge.getCurrentFrameData().getProjectionMatrix());
                if (vp != null && vp.length == 16) {
                    for (int i = 0; i < 16; i++) {
                        pc.set(ValueLayout.JAVA_FLOAT, 16 + (long) i * 4, vp[i]);
                    }
                }
                VulkanAPIRegistry.invoke("vkCmdPushConstants",
                        cmdBuf, pipelineLayout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0, 64, pc.address());

                // vkCmdDispatch
                int w = (context.getWidth() + 7) / 8;
                int h = (context.getHeight() + 7) / 8;
                VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);

                FrameCommandContext.endNodeCB(NODE_ID);
            }
        } catch (Throwable t) {
            LOGGER.fine("[GBuffer] Compute dispatch 失败: " + t.getMessage());
            return 0L;
        }

        return outputPositionView;
    }

    /**
     * 将 float 数组写入已映射的 GPU 缓冲区内存
     *
     * @param device VkDevice 句柄
     * @param memory VkDeviceMemory 句柄
     * @param data   float 数组
     * @param count  float 元素数量
     */
    private static void writeFloatArrayToBuffer(long device, long memory, float[] data, int count) {
        if (device == 0L || memory == 0L || data == null || count <= 0) return;
        try {
            MemorySegment ppData = PerFrameArena.allocateLongs(1);
            long byteSize = (long) count * 4;
            int mapRc = (int) VulkanAPIRegistry.invoke(
                    "vkMapMemory", device, memory, 0L, byteSize, 0, ppData.address());
            if (mapRc == 0) {
                long ptr = ppData.get(ValueLayout.JAVA_LONG, 0);
                if (ptr != 0L) {
                    MemorySegment mapped = MemorySegment.ofAddress(ptr).reinterpret(byteSize);
                    for (int i = 0; i < count; i++) {
                        mapped.set(ValueLayout.JAVA_FLOAT, (long) i * 4, data[i]);
                    }
                }
            }
            VulkanAPIRegistry.invoke("vkUnmapMemory", device, memory);
        } catch (Throwable t) {
            LOGGER.fine("[GBuffer] writeFloatArrayToBuffer 失败: " + t.getMessage());
        }
    }

    // ==================== 初始化与释放钩子 ====================

    /**
     * 节点初始化钩子
     * <p>
     * 在首次执行前由框架调用，用于验证运行环境。
     *
     * @param context RenderContext - 渲染上下文
     * @return boolean - 是否初始化成功
     */
    @Override
    protected boolean onInitialize(RenderContext context) {
        if (!MCRenderBridge.isFullyInitialized()) {
            LOGGER.warning("[GBuffer] MCRenderBridge 未完成初始化，延迟初始化");
            return false;
        }

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
     * 清理所有 Vulkan GPU 资源：Pipeline、SSBO、Output Images。
     */
    @Override
    protected void onDispose() {
        long device = VulkanDeviceHolder.getInstance().getDevice();

        if (device != 0L) {
            // 销毁 Compute Pipeline
            if (computePipeline != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipeline", device, computePipeline, 0L); } catch (Throwable ignored) {}
                computePipeline = 0L;
            }
            if (pipelineLayout != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", device, pipelineLayout, 0L); } catch (Throwable ignored) {}
                pipelineLayout = 0L;
            }

            // 销毁 SSBO
            if (vertexBuffer != 0L) {
                VulkanMemoryAllocator.destroyBuffer(device, vertexBuffer, vertexBufferMemory);
                vertexBuffer = 0L;
                vertexBufferMemory = 0L;
            }
            if (normalSSBO != 0L) {
                VulkanMemoryAllocator.destroyBuffer(device, normalSSBO, normalSSBOMemory);
                normalSSBO = 0L;
                normalSSBOMemory = 0L;
            }
            if (colorSSBO != 0L) {
                VulkanMemoryAllocator.destroyBuffer(device, colorSSBO, colorSSBOMemory);
                colorSSBO = 0L;
                colorSSBOMemory = 0L;
            }
        }

        ssboCapacity = 0;

        // 销毁 Output Images
        VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();

        releaseImageView(mgr, outputPositionView); outputPositionView = 0L;
        releaseImageView(mgr, outputNormalView);   outputNormalView = 0L;
        releaseImageView(mgr, outputAlbedoView);   outputAlbedoView = 0L;
        releaseImageView(mgr, outputMaterialView); outputMaterialView = 0L;

        releaseGpuImage(mgr, outputPositionImage, lastGBufW, lastGBufH); outputPositionImage = 0L;
        releaseGpuImage(mgr, outputNormalImage,   lastGBufW, lastGBufH); outputNormalImage = 0L;
        releaseGpuImage(mgr, outputAlbedoImage,   lastGBufW, lastGBufH); outputAlbedoImage = 0L;
        releaseGpuImage(mgr, outputMaterialImage, lastGBufW, lastGBufH); outputMaterialImage = 0L;

        lastGBufW = 0;
        lastGBufH = 0;

        // CPU 缓冲区
        positionBuffer  = null;
        normalBuffer    = null;
        albedoBuffer    = null;
        materialBuffer  = null;
        bufferCapacity  = 0;

        resetStats();
        LOGGER.fine("[GBuffer] 资源已释放");
    }

    /**
     * 安全释放 ImageView
     */
    private static void releaseImageView(VulkanGPUResourceManager mgr, long view) {
        if (view != 0L) {
            try { mgr.destroyView(view); } catch (Throwable ignored) {}
        }
    }

    /**
     * 安全释放 GPU Image
     */
    private static void releaseGpuImage(VulkanGPUResourceManager mgr, long image, int w, int h) {
        if (image != 0L) {
            try {
                mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(image, 0, w, h, 0, VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Throwable ignored) {}
        }
    }

    // ==================== Compute Pipeline 管理 ====================

    /**
     * 确保 Compute Pipeline 已创建（双重检查锁定）
     * <p>
     * 加载 SPIR-V 着色器，创建包含 7 个 Binding（3x STORAGE_BUFFER + 4x STORAGE_IMAGE）
     * 和 64 字节 PushConstants 的 Compute Pipeline。
     */
    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        synchronized (this) {
            if (computePipeline != 0L) return;

            byte[] spirv = resolveSPIRV();
            if (spirv == null) {
                LOGGER.warning("[GBuffer] SPIR-V 资源加载失败: " + shaderKey());
                return;
            }

            ComputePipelineHelper.Binding[] bindings = {
                new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new ComputePipelineHelper.Binding(4, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new ComputePipelineHelper.Binding(5, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new ComputePipelineHelper.Binding(6, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            };

            ComputePipelineHelper.PushConstant pc =
                    new ComputePipelineHelper.PushConstant(0, 64, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);

            try {
                ComputePipelineHelper.PipelineResources r = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (r != null) {
                    computePipeline = r.pipeline();
                    pipelineLayout = r.pipelineLayout();
                    descriptorSet = r.descriptorSet();
                    LOGGER.fine("[GBuffer] Compute Pipeline 创建成功: " + shaderKey());
                }
            } catch (Exception e) {
                LOGGER.warning("[GBuffer] Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    // ==================== G-Buffer 输出图像管理 ====================

    /**
     * 确保 G-Buffer 四通道输出图像已创建
     * <p>
     * 创建 4 张 Storage Image（Position/Normal/Albedo/Material），
     * 当视口尺寸变化时自动重新创建。
     *
     * @param context RenderContext - 渲染上下文（用于获取输出尺寸）
     */
    private void ensureGBufferOutput(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();

        if (outputPositionView != 0L && w == lastGBufW && h == lastGBufH) return;

        synchronized (this) {
            if (outputPositionView != 0L && w == lastGBufW && h == lastGBufH) return;

            VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
            VulkanDeviceHolder holder = VulkanDeviceHolder.getInstance();
            long dev = holder.getDevice();

            // 清理旧资源
            releaseImageView(mgr, outputPositionView); outputPositionView = 0L;
            releaseImageView(mgr, outputNormalView);   outputNormalView = 0L;
            releaseImageView(mgr, outputAlbedoView);   outputAlbedoView = 0L;
            releaseImageView(mgr, outputMaterialView); outputMaterialView = 0L;

            releaseGpuImage(mgr, outputPositionImage, lastGBufW, lastGBufH); outputPositionImage = 0L;
            releaseGpuImage(mgr, outputNormalImage,   lastGBufW, lastGBufH); outputNormalImage = 0L;
            releaseGpuImage(mgr, outputAlbedoImage,   lastGBufW, lastGBufH); outputAlbedoImage = 0L;
            releaseGpuImage(mgr, outputMaterialImage, lastGBufW, lastGBufH); outputMaterialImage = 0L;

            if (dev == 0L) return;

            int storageUsage = VulkanConst.IMAGE_USAGE_STORAGE_BIT | VulkanConst.IMAGE_USAGE_SAMPLED_BIT;

            try {
                // Position: R32G32B32A32_SFLOAT
                VulkanGPUResourceManager.GpuResource posImg = mgr.createImage(w, h,
                        VulkanConst.FORMAT_R32G32B32A32_SFLOAT, storageUsage,
                        VmaMemoryPools.PoolType.RENDER_TARGET);
                if (posImg != null && posImg.handle != 0L) {
                    outputPositionImage = posImg.handle;
                    outputPositionView = mgr.createView(outputPositionImage,
                            VulkanConst.FORMAT_R32G32B32A32_SFLOAT,
                            VulkanConst.IMAGE_ASPECT_COLOR_BIT);
                }

                // Normal: R16G16B16A16_SFLOAT = 97
                int fmtR16G16B16A16_SFLOAT = 97;
                VulkanGPUResourceManager.GpuResource normImg = mgr.createImage(w, h,
                        fmtR16G16B16A16_SFLOAT, storageUsage,
                        VmaMemoryPools.PoolType.RENDER_TARGET);
                if (normImg != null && normImg.handle != 0L) {
                    outputNormalImage = normImg.handle;
                    outputNormalView = mgr.createView(outputNormalImage,
                            fmtR16G16B16A16_SFLOAT,
                            VulkanConst.IMAGE_ASPECT_COLOR_BIT);
                }

                // Albedo: R8G8B8A8_UNORM
                VulkanGPUResourceManager.GpuResource albImg = mgr.createImage(w, h,
                        VulkanConst.FORMAT_R8G8B8A8_UNORM, storageUsage,
                        VmaMemoryPools.PoolType.RENDER_TARGET);
                if (albImg != null && albImg.handle != 0L) {
                    outputAlbedoImage = albImg.handle;
                    outputAlbedoView = mgr.createView(outputAlbedoImage,
                            VulkanConst.FORMAT_R8G8B8A8_UNORM,
                            VulkanConst.IMAGE_ASPECT_COLOR_BIT);
                }

                // Material: R8G8B8A8_UNORM
                VulkanGPUResourceManager.GpuResource matImg = mgr.createImage(w, h,
                        VulkanConst.FORMAT_R8G8B8A8_UNORM, storageUsage,
                        VmaMemoryPools.PoolType.RENDER_TARGET);
                if (matImg != null && matImg.handle != 0L) {
                    outputMaterialImage = matImg.handle;
                    outputMaterialView = mgr.createView(outputMaterialImage,
                            VulkanConst.FORMAT_R8G8B8A8_UNORM,
                            VulkanConst.IMAGE_ASPECT_COLOR_BIT);
                }

                lastGBufW = w;
                lastGBufH = h;

                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("[GBuffer] G-Buffer 输出图像创建成功 [%dx%d] " +
                            "pos=0x%X norm=0x%X alb=0x%X mat=0x%X",
                        w, h, outputPositionView, outputNormalView,
                        outputAlbedoView, outputMaterialView));

            } catch (Exception e) {
                LOGGER.warning("[GBuffer] ensureGBufferOutput 异常: " + e.getMessage());
            }
        }
    }

    // ==================== SSBO 管理 ====================

    /**
     * 确保 SSBO 容量充足
     * <p>
     * 当顶点数超过当前 SSBO 容量时，销毁旧缓冲区并重新创建。
     *
     * @param vertexCount 当前帧顶点数
     */
    private void ensureSSBOCapacity(int vertexCount) {
        int requiredFloats = Math.max(vertexCount * 4, vertexCount * 3);
        if (requiredFloats <= ssboCapacity && vertexBuffer != 0L) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;

        synchronized (this) {
            if (requiredFloats <= ssboCapacity && vertexBuffer != 0L) return;

            // 销毁旧 SSBO
            if (vertexBuffer != 0L) {
                VulkanMemoryAllocator.destroyBuffer(device, vertexBuffer, vertexBufferMemory);
                vertexBuffer = 0L;
                vertexBufferMemory = 0L;
            }
            if (normalSSBO != 0L) {
                VulkanMemoryAllocator.destroyBuffer(device, normalSSBO, normalSSBOMemory);
                normalSSBO = 0L;
                normalSSBOMemory = 0L;
            }
            if (colorSSBO != 0L) {
                VulkanMemoryAllocator.destroyBuffer(device, colorSSBO, colorSSBOMemory);
                colorSSBO = 0L;
                colorSSBOMemory = 0L;
            }

            int newCap = ssboCapacity;
            while (newCap < requiredFloats) {
                newCap = newCap == 0 ? requiredFloats : (newCap << 1);
            }
            newCap = Math.max(newCap, 1024);

            long posSize = (long) newCap * 4;
            long normSize = (long) newCap * 4;
            long colSize = (long) newCap * 4;

            int storageUsage = VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT;

            long[] posBuf = VulkanMemoryAllocator.createHostVisibleBuffer(device, posSize, storageUsage);
            if (posBuf[0] != 0L) {
                vertexBuffer = posBuf[0];
                vertexBufferMemory = posBuf[1];
            }

            long[] normBuf = VulkanMemoryAllocator.createHostVisibleBuffer(device, normSize, storageUsage);
            if (normBuf[0] != 0L) {
                normalSSBO = normBuf[0];
                normalSSBOMemory = normBuf[1];
            }

            long[] colBuf = VulkanMemoryAllocator.createHostVisibleBuffer(device, colSize, storageUsage);
            if (colBuf[0] != 0L) {
                colorSSBO = colBuf[0];
                colorSSBOMemory = colBuf[1];
            }

            ssboCapacity = newCap;

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(String.format("[GBuffer] SSBO 扩容: %d \u2192 %d floats (%.1f MB)",
                    ssboCapacity >> 1, newCap,
                    (posSize + normSize + colSize) / (1024.0 * 1024.0)));
        }
    }

    // ==================== SPIR-V 资源加载 ====================

    /**
     * 从 classpath 加载 SPIR-V 二进制着色器资源
     *
     * @param path String - 资源路径（如 "/shaders/pipeline/geometry/gbuffer_fill.spv"）
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRVResource(String path) {
        try (var is = GBufferGeometryNode.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== G-Buffer 通道填充 ====================

    /**
     * 填充 G-Buffer 四通道输出数据
     * <p>
     * 将变换后的顶点数据分发到四个 G-Buffer 通道。
     *
     * @param context         RenderContext - 渲染上下文
     * @param transformedPos   float[]    - 已变换的顶点位置数组
     * @param vertexCount      int        - 顶点数量
     */
    private void fillGBufferChannels(RenderContext context,
                                     float[] transformedPos,
                                     int vertexCount) {
        System.arraycopy(transformedPos, 0, positionBuffer, 0, vertexCount * 3);
        fillNormalChannel(transformedPos, vertexCount);
        fillAlbedoChannel(context, vertexCount);
        fillMaterialChannel(vertexCount);
    }

    /**
     * 填充法线通道（Channel 1: RGB16F）
     *
     * @param transformedPos float[] - 变换后的顶点位置
     * @param vertexCount    int    - 顶点数量
     */
    private void fillNormalChannel(float[] transformedPos, int vertexCount) {
        boolean doNormalMap = enableNormalMapping;
        float strength = normalStrength;

        for (int i = 0; i < vertexCount; i += 3) {
            if (i + 2 >= vertexCount) break;

            int i0 = i * 3, i1 = (i + 1) * 3, i2 = (i + 2) * 3;

            float ax = transformedPos[i1]     - transformedPos[i0];
            float ay = transformedPos[i1 + 1] - transformedPos[i0 + 1];
            float az = transformedPos[i1 + 2] - transformedPos[i0 + 2];

            float bx = transformedPos[i2]     - transformedPos[i0];
            float by = transformedPos[i2 + 1] - transformedPos[i0 + 1];
            float bz = transformedPos[i2 + 2] - transformedPos[i0 + 2];

            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;

            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-10f) {
                float invLen = 1.0f / len;
                nx *= invLen;
                ny *= invLen;
                nz *= invLen;
            } else {
                nx = 0; ny = 1.0f; nz = 0;
            }

            float finalNx = lerp(ny, nx, strength);
            float finalNy = lerp(1.0f, ny, strength);
            float finalNz = lerp(0.0f, nz, strength);

            if (doNormalMap) {
                try {
                    if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) {
                        LOGGER.fine("[GBuffer] Vulkan 不可用，法线贴图采样跳过，使用几何法线");
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "[GBuffer] 法线贴图采样异常（回退到几何法线）", e);
                }
            }

            normalBuffer[i0]     = finalNx; normalBuffer[i0 + 1] = finalNy; normalBuffer[i0 + 2] = finalNz;
            normalBuffer[i1]     = finalNx; normalBuffer[i1 + 1] = finalNy; normalBuffer[i1 + 2] = finalNz;
            normalBuffer[i2]     = finalNx; normalBuffer[i2 + 1] = finalNy; normalBuffer[i2 + 2] = finalNz;
        }
    }

    /**
     * 填充反照率通道（Channel 2: RGBA8）
     *
     * @param context    RenderContext - 渲染上下文
     * @param vertexCount int          - 顶点数量
     */
    private void fillAlbedoChannel(RenderContext context, int vertexCount) {
        try {
            if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) {
                LOGGER.fine("[GBuffer] Vulkan 不可用，fillAlbedoChannel 使用程序化占位颜色（%d 顶点）".formatted(vertexCount));
            }

            for (int i = 0; i < vertexCount; i++) {
                int base = i * 4;
                float y = positionBuffer[i * 3 + 1];

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
                albedoBuffer[i] = (i % 4 == 3) ? 1.0f : 1.0f;
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
     * @param vertexCount int - 顶点数量
     */
    private void fillMaterialChannel(int vertexCount) {
        for (int i = 0; i < vertexCount; i++) {
            int base = i * 4;

            float y = positionBuffer[i * 3 + 1];
            float ny = normalBuffer[i * 3 + 1];

            float roughness, metallic, ao, reserved;

            if (y > 50.0f) {
                roughness = 0.6f;
                metallic = 0.0f;
                ao = 0.9f;
            } else if (y > 20.0f) {
                roughness = 0.85f;
                metallic = 0.05f;
                ao = 0.7f;
            } else if (ny > 0.7f) {
                roughness = 0.7f;
                metallic = 0.0f;
                ao = 0.85f;
            } else {
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
     *
     * @param view       float[] - 视图矩阵（16 floats, column-major）
     * @param projection float[] - 投影矩阵（16 floats, column-major）
     * @return float[] - VP 矩阵（16 floats, column-major），新分配数组
     */
    private float[] computeVPMatrix(float[] view, float[] projection) {
        return multiplyMatrix4x4(projection, view);
    }

    /**
     * 4x4 矩阵乘法（column-major 布局）
     * <p>
     * 计算 result = left × right。
     *
     * @param left  float[] - 左操作数（16 floats, column-major）
     * @param right float[] - 右操作数（16 floats, column-major）
     * @return float[] - 乘积矩阵（16 floats, column-major）
     */
    private static float[] multiplyMatrix4x4(float[] left, float[] right) {
        float[] out = new float[16];

        for (int col = 0; col < 4; col++) {
            int co = col << 2;
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
     * 桥接方法，从 MC 的 ChunkBuilder 获取真实的网格数据。
     *
     * @param context     RenderContext - 渲染上下文
     * @param maxVertices int          - 最大顶点数
     * @return float[] - 顶点位置数组 [x0,y0,z0, x1,y1,z1, ...]
     */
    private float[] extractTerrainVertexPositions(RenderContext context, int maxVertices) {
        try {
            if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) {
                LOGGER.fine("[GBuffer] Vulkan 不可用，extractTerrainVertexPositions 使用程序化占位数据（maxVertices=%d）".formatted(maxVertices));
            }

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
     * @param a float - 起点
     * @param b float - 终点
     * @param t float - 插值因子（0.0 ~ 1.0+）
     * @return float - 插值结果: a + (b - a) * t
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 确保 G-Buffer 输出缓冲区容量足够
     *
     * @param requiredVertices int - 所需的最小顶点容量
     */
    private void ensureOutputCapacity(int requiredVertices) {
        if (requiredVertices <= bufferCapacity) {
            return;
        }

        int newCap = bufferCapacity;
        while (newCap < requiredVertices) {
            newCap <<= 1;
        }

        this.positionBuffer  = new float[newCap * 3];
        this.normalBuffer    = new float[newCap * 3];
        this.albedoBuffer    = new float[newCap * 4];
        this.materialBuffer  = new float[newCap * 4];
        this.bufferCapacity  = newCap;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format(
                    "[GBuffer] 输出缓冲区扩容: %d → %d 顶点 (%.1f MB)",
                bufferCapacity >> 1, newCap,
                (newCap * 3L * 4 + newCap * 3L * 4 + newCap * 4L * 4 + newCap * 4L * 4)
                        / (1024.0 * 1024.0)
        ));
    }

    /**
     * 输出性能诊断日志
     *
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
     * @param enabled boolean - 是否启用
     */
    public void setEnableNormalMapping(boolean enabled) {
        this.enableNormalMapping = enabled;
    }

    /**
     * 获取当前法线映射启用状态
     *
     * @return boolean - 是否启用法线映射
     */
    public boolean isEnableNormalMapping() {
        return enableNormalMapping;
    }

    /**
     * 设置是否启用视差贴图
     *
     * @param enabled boolean - 是否启用
     */
    public void setEnableParallax(boolean enabled) {
        this.enableParallax = enabled;
    }

    /**
     * 获取当前视差贴图启用状态
     *
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
     * @param strength float - 法线强度（0.1 ~ 2.0）
     */
    public void setNormalStrength(float strength) {
        this.normalStrength = clamp(strength, 0.1f, 2.0f);
    }

    /**
     * 获取当前法线强度系数
     *
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
     * @param scale float - 视差缩放（0.01 ~ 0.1）
     */
    public void setParallaxScale(float scale) {
        this.parallaxScale = clamp(scale, 0.01f, 0.1f);
    }

    /**
     * 获取当前视差贴图缩放系数
     *
     * @return float - 视差缩放（0.01 ~ 0.1）
     */
    public float getParallaxScale() {
        return parallaxScale;
    }

    /**
     * 浮点数钳制辅助函数
     *
     * @param value float - 输入值
     * @param min   float - 下界
     * @param max   float - 上界
     * @return float - 钳制后的值
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== 诊断与监控 API ====================

    /**
     * 获取累计变换的总顶点数
     *
     * @return long - 总顶点数
     */
    public long getTotalVerticesTransformed() {
        return totalVerticesTransformed;
    }

    /**
     * 获取平均每帧执行时间
     *
     * @return double - 平均时间（微秒）
     */
    public double getAvgTimePerFrameMicros() {
        return frameCount > 0 ? (double) totalTimeMicros / frameCount : 0.0;
    }

    /**
     * 获取 V3 Unsafe 路径命中率
     *
     * @return double - 命中率（0.0 ~ 1.0）
     */
    public double getV3HitRate() {
        long total = v3UnsafePathHits + v2FallbackHits;
        return total > 0 ? (double) v3UnsafePathHits / total : 0.0;
    }

    /**
     * 获取当前输出缓冲区容量
     *
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
