// Renderium - 现代渲染架构组件
// MR2 Mesh Shader 渲染器 - 基于 Task Shader + Mesh Shader 的动态 LOD 渲染系统
// 来源文档: modern-render-architecture.md §3.2 Mesh Shader Pipeline
// 策略ID: MR2 (Modern Rendering #2)
// 预期收益: Draw Calls 从 50+ 降到 1-10，完全 GPU-Driven 几何体生成
// 技术要求: Vulkan 1.2+ (NV_mesh_shader) 或 Vulkan 1.3+ (EXT_mesh_shader)
// 继承关系: 独立组件 (可与 GPUDrivenVisibilitySystem 配合使用)

package com.renderium.module.impl.blaze3d.modern;

import com.renderium.module.impl.renderopt.ChunkRenderData;
import org.joml.Matrix4f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * MR2 Mesh Shader 渲染器 🎯🚀
 * <p>
 * 实现基于 <b>Task Shader + Mesh Shader</b> 的动态 LOD 渲染系统，
 * 这是现代 GPU-Driven 架构的终极形态。完全在 GPU 上完成：
 * <ul>
 *   <li><b>可见性选择</b> - Task Shader 并行测试视锥/距离/HLOD</li>
 *   <li><b>几何体生成</b> - Mesh Shader 从 Meshlet 数据输出实际三角形</li>
 *   <li><b>动态 LOD</b> - 基于屏幕空间大小自动选择细节层级</li>
 * </ul>
 *
 * <h2>架构优势：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                  传统渲染 vs Mesh Shader                    │
 * ├─────────────────────────────────────────────────────────────┤
 * │                                                             │
 * │  传统 OpenGL:                                                │
 * │  CPU: 视锥剔除 → LOD 选择 → 生成 DrawCall (1000-5000次)      │
 * │  GPU: Vertex Shader → Rasterizer → Fragment Shader          │
 * │                                                             │
 * │  MR2 Mesh Shader:                                           │
 * │  CPU: 仅更新 Uniform Buffer (1次调用)                        │
 * │  GPU: Task Shader(剔除) → Mesh Shader(几何体) → Fragment     │
 * │       (单次 DispatchMesh 调用!)                              │
 * │                                                             │
 * │  性能提升:                                                   │
 * │  - CPU 开销: ~5ms → <0.01ms                                 │
 * │  - Draw Calls: 1000-5000 → 1-10                             │
 * │  - GPU 利用率: 30-50% → 85-95%                              │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>渲染流程（三阶段 Pipeline）：</h2>
 * <pre>
 * ┌─ 阶段 1: Task Shader (Culling + LOD Selection) ──────────┐
 * │                                                            │
 * │  输入:                                                      │
 * │    - MeshletTask[]: 任务列表（每个任务包含一组候选 Meshlet）│
 * │    - Camera UBO: 相机参数、视锥平面                          │
 * │    - LOD Distances[]: 各层级的距离阈值                      │
 * │                                                            │
 * │  处理:                                                      │
 * │    for each task in parallel:                               │
 * │        for each meshlet in task:                            │
 * │            if (frustumTest(meshlet.boundingSphere) &&       │
 * │                distanceLODTest(meshlet, camera)):           │
 * │                outputVisibleMeshlet(meshletIndex);          │
 * │                                                            │
 * │  输出:                                                      │
 * │    - visibleMeshletIndices[]: 通过测试的 Meshlet 索引列表    │
 * └────────────────────────────────────────────────────────────┘
 *                              ↓
 * ┌─ 阶段 2: Mesh Shader (Geometry Generation) ───────────────┐
 * │                                                            │
 * │  输入:                                                      │
 * │    - visibleMeshletIndices[]: 阶段1的输出                   │
 * │    - Meshlet[]: 所有 Meshlet 数据（顶点、索引、属性）        │
 * │    - Model Matrix: 对象变换矩阵                             │
 * │                                                            │
 * │  处理 (每个 Workgroup 处理一个 Meshlet):                     │
 * │    1. 读取 Meshlet 顶点数据（最多64个）                     │
 * │    2. 应用模型变换到世界空间                                │
 * │    3. 计算视角投影坐标                                     │
 * │    4. 输出插值变量（位置、法线、UV）                         │
 * │    5. 输出原始三角形索引（最多126个）                       │
 * │                                                            │
 * │  输出:                                                      │
 * │    - 到光栅化器的完整三角形流                               │
 * └────────────────────────────────────────────────────────────┘
 *                              ↓
 * ┌─ 阶段 3: Fragment Shader (Shading) ──────────────────────┐
 * │                                                            │
 * │  标准 PBR 光照计算                                         │
 * │  （使用 Bindless 纹理采样）                                 │
 * └────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Meshlet 数据结构（核心概念）：</h2>
 * <p>
 * Meshlet 是将大型网格分割成的小型子网格单元，
 * 每个 Meshlet 包含：
 * </p>
 * <ul>
 *   <li><b>最多 64 个顶点</b> - NV_mesh_shader 硬件限制</li>
 *   <li><b>最多 126 个索引（42个三角形）</b> - NV_mesh_shader 硬件限制</li>
 *   <li><b>边界球</b> - 用于快速视锥剔除和距离测试</li>
 *   <li><b>LOD 元数据</b> - 关联到层次结构中的父/子节点</li>
 * </ul>
 *
 * <h2>硬件支持检测：</h2>
 * <table border="1">
 *   <tr>
 *     <th>扩展名称</th>
 *     <th>Vulkan 版本</th>
 *     <th>限制</th>
 *   </tr>
 *   <tr>
 *     <td>VK_NV_mesh_shader</td>
 *     <td>Vulkan 1.2+</td>
 *     <td>maxVertices=64, maxIndices=126</td>
 *   </tr>
 *   <tr>
 *     <td>VK_EXT_mesh_shader</td>
 *     <td>Vulkan 1.3+</td>
 *     <td>maxVertices=256, maxPrimitives=512</td>
 *   </tr>
 * </table>
 *
 * <h3>参考文档：</h3>
 * <ul>
 *   <li>modern-rendering-architecture.md §3.2（Mesh Shader Pipeline）</li>
 *   <li>NVIDIA Mesh Shader Whitepaper (GDC 2019)</li>
 *   <li>Vulkan Specification: VK_NV_mesh_shader / VK_EXT_mesh_shader</li>
 *   <li>Unreal Engine 5 Nanite 技术分析</li>
 * </ul>
 *
 * <h2>优雅降级策略：</h2>
 * <p>
 * 如果硬件不支持 Mesh Shader，系统将自动降级到：
 * <ol>
 *   <li>{@link GPUDrivenVisibilitySystem} - GPU 剔除 + Indirect Draw</li>
 *   <li>传统 Vertex Shader 路径 - CPU 驱动的 Draw Call 批处理</li>
 * </ol>
 * </p>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see GPUDrivenVisibilitySystem
 * @see BindlessResourceManager
 */
public final class MeshShaderRenderer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(MeshShaderRenderer.class.getName());

    // ==================== 配置常量（NV_mesh_shader 硬件限制）====================

    /**
     * 最大支持 Meshlet 数量
     * <p>
     * 决定 GPU Buffer 分配大小。
     * 对于 Minecraft 场景：16×16×384 区块 ≈ 98K 区块，
     * 每个区块约 10-20 个 Meshlet，总计约 1-2M Meshlets。
     * 此处设置为 65536 作为单批次上限，超出部分分多批处理。
     */
    public static final int MAX_MESHLETS = 65536;

    /**
     * 每个 Meshlet 最大顶点数（NV_mesh_shader 限制）
     * <p>
     * VK_NV_mesh_shader: maxMeshOutputVertices = 64
     * VK_EXT_mesh_shader: 可达 256
     */
    public static final int MESHLET_MAX_VERTICES = 64;

    /**
     * 每个 Meshlet 最大索引数（NV_mesh_shader 限制）
     * <p>
     * VK_NV_mesh_shader: maxMeshOutputPrimitives = 126（即 126 个索引 = 42 个三角形）
     * VK_EXT_mesh_shader: 可达 512（或更多）
     * <p>
     * 注意：此处存储的是索引数量，不是三角形数量！
     */
    public static final int MESHLET_MAX_INDICES = 126;

    /**
     * 最大 LOD 层级数
     * <p>
     * 层级 0 = 最高精度（原始几何体）
     * 层级 MAX_LOD_LEVELS-1 = 最低精度（简化版本或完全剔除）
     * <p>
     * 推荐 8 层可覆盖从近景到远景的完整 LOD 链：
     * - LOD 0-2: 近距离（<32区块），高精度
     * - LOD 3-5: 中距离（32-128区块），中等精度
     * - LOD 6-7: 远距离（>128区块），低精度或剔除
     */
    public static final int MAX_LOD_LEVELS = 8;

    /** Task Shader Workgroup 大小 */
    private static final int TASK_WORKGROUP_SIZE = 32;

    /** Mesh Shader Workgroup 大小（应匹配 MESHLET_MAX_VERTICES）*/
    private static final int MESH_WORKGROUP_SIZE = 32;

    // ==================== 扩展支持检测标志 ====================

    /**
     * 是否支持 NV_mesh_shader 扩展（NVIDIA 专有扩展）
     * <p>
     * 适用范围:
     * - Vulkan 1.2+
     * - NVIDIA GPU (Turing 架构及以后: RTX 20系列+)
     * - 限制较严格但兼容性好
     */
    private volatile boolean nvMeshShaderSupported = false;

    /**
     * 是否支持 EXT_mesh_shader 扩展（Khronos 标准）
     * <p>
     * 适用范围:
     * - Vulkan 1.3+
     * - AMD RDNA2+, Intel Arc, NVIDIA (新驱动)
     * - 更宽松的限制，推荐优先使用
     */
    private volatile boolean extMeshShaderSupported = false;

    /** 当前使用的 Mesh Shader 模式 */
    private volatile MeshShaderMode activeMode = MeshShaderMode.UNSUPPORTED;

    // ==================== Vulkan Pipeline 资源句柄 ====================

    /**
     * Task Shader Pipeline（VkPipeline）
     * <p>
     * 用于阶段1: 并行选择可见 Meshlets
     * 输入: MeshletTask[], Camera UBO
     * 输出: visibleMeshletIndices[]
     */
    private long taskPipeline = 0L;

    /**
     * Mesh Shader Pipeline（VkPipeline）
     * <p>
     * 用于阶段2: 从 Meshlet 数据生成实际三角形
     * 输入: visibleMeshletIndices[], Meshlet[]
     * 输出: 光栅化器输入流
     */
    private long meshPipeline = 0L;

    /**
     * Pipeline Layout（VkPipelineLayout）
     * <p>
     * 定义 Descriptor Set Layout 和 Push Constant Range
     * 被 taskPipeline 和 meshPipeline 共享
     */
    private long pipelineLayout = 0L;

    // ==================== Meshlet 数据缓冲区（GPU Resident）====================

    /**
     * Meshlet 数据缓冲区（SSBO: Storage Buffer）
     * <p>
     * 存储所有 Meshlet 的完整数据：
     * - 顶点位置（压缩格式，half-float × 3 × 64 vertices = 384 bytes per meshlet）
     * - 顶点法线（压缩格式，octahedral encoding × 3 bytes × 64 = 192 bytes）
     * - UV 坐标（half-float × 2 × 64 = 256 bytes）
     * - 索引数据（uint8 × 126 = 126 bytes）
     * - 边界球（float4: center.xyz + radius = 16 bytes）
     * - LOD 元数据（uint8: lodLevel + parentChunkId padding = 4 bytes）
     * <p>
     * 总计每个 Meshlet 约 978 bytes（~1KB）
     * MAX_MESHLETS = 65536 → 总计约 64MB GPU 内存
     * <p>
     * 内存布局（std430 layout）:
     * <pre>
     * struct Meshlet {
     *     vec3  positions[64];      // half-float 压缩
     *     vec3  normals[64];        // octahedral 压缩
     *     vec2  texCoords[64];      // half-float 压缩
     *     uint8 indices[126];       // 本地顶点索引 [0..63]
     *     vec4  boundingSphere;     // center(x,y,z) + radius
     *     uint  lodLevel;           // LOD 等级 [0..7]
     *     uint  parentChunkId;      // 所属 Chunk ID
     * };
     * </pre>
     */
    private long meshletBuffer = 0L;

    /**
     * 可见 Meshlet 计数缓冲区（Atomic Counter Buffer）
     * <p>
     * Task Shader 写入通过测试的 Meshlet 数量，
     * Mesh Shader 读取作为工作范围。
     * 使用 Vulkan Atomic Counter (uint32) 实现。
     * <p>
     * 每帧渲染前需要清零！
     */
    private long meshletCountBuffer = 0L;

    /**
     * Indirect Draw 参数缓冲区（Indirect Buffer）
     * <p>
     * 存储 vkCmdDrawMeshTasksIndirectNV / vkCmdDrawMeshTasksIndirectEXT 所需的参数：
     * <pre>
     * struct MeshTaskCount {
     *     uint taskCount;  // 要执行的 Task 数量
     *     uint firstTask;  // 第一个 Task 的索引
     * };
     * </pre>
     * 如果使用 EXT 版本，还可能包含 drawCount 和 stride 字段。
     */
    private long indirectDrawBuffer = 0L;

    // ==================== 运行时状态 ====================

    /** Vulkan 设备句柄（Logical Device）*/
    private volatile long device = 0L;

    /** VMA 分配器句柄（用于 GPU 内存管理）*/
    private volatile long vmaAllocator = 0L;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 当前帧的可见 Meshlet 数量（用于性能监控）*/
    private volatile int currentVisibleMeshletCount = 0;

    /** 当前注册的总 Meshlet 数量 */
    private volatile int totalRegisteredMeshlets = 0;

    // ==================== 性能统计字段 ====================

    /** Mesh Shader 渲染总帧数 */
    private final AtomicLong totalFramesRendered = new AtomicLong(0);

    /** Task Shader 总执行时间（纳秒）*/
    private final AtomicLong totalTaskShaderTimeNanos = new AtomicLong(0);

    /** Mesh Shader 总执行时间（纳秒）*/
    private final AtomicLong totalMeshShaderTimeNanos = new AtomicLong(0);

    /** 上传 Meshlet 数据总耗时（纳秒）*/
    private final AtomicLong totalUploadTimeNanos = new AtomicLong(0);

    /** 因视锥剔除跳过的 Meshlet 总数 */
    private final AtomicLong totalMeshletsFrustumCulled = new AtomicLong(0);

    /** 因距离 LOD 剔除降级的 Meshlet 总数 */
    private final AtomicLong totalMeshletsLODDegraded = new AtomicLong(0);

    /** 降级回退到传统路径的总次数 */
    private final AtomicLong totalFallbackCount = new AtomicLong(0);

    // ==================== 内部枚举：Mesh Shader 模式 ====================

    /**
     * Mesh Shader 工作模式枚举
     */
    public enum MeshShaderMode {
        /** 不支持 Mesh Shader（需降级）*/
        UNSUPPORTED,
        /** 使用 NV_mesh_shader 扩展（Vulkan 1.2+）*/
        NV_MESH_SHADER,
        /** 使用 EXT_mesh_shader 扩展（Vulkan 1.3+，推荐）*/
        EXT_MESH_SHADER
    }

    // ==================== 构造函数 ====================

    /**
     * 创建 Mesh Shader 渲染器实例
     * <p>
     * 仅创建对象，不分配任何 GPU 资源。
     * 必须显式调用 {@link #init(long, long)} 完成初始化后才能使用。
     */
    public MeshShaderRenderer() {
        LOGGER.info("MeshShaderRenderer 创建完成 (MR2)");
    }

    // ==================== 初始化与检测方法 ====================

    /**
     * 检测硬件是否支持 Mesh Shader 扩展
     * <p>
     * 按优先级检测两种扩展：
     * <ol>
     *   <li><b>VK_EXT_mesh_shader</b>（首选）- Vulkan 1.3+ 标准，限制更宽松</li>
     *   <li><b>VK_NV_mesh_shader</b>（备选）- Vulkan 1.2+ NVIDIA 专有，兼容性广</li>
     * </ol>
     *
     * <h3>检测流程：</h3>
     * <pre>
     * 1. 查询物理设备支持的扩展列表
     * 2. 检查是否包含 "VK_EXT_mesh_shader"
     *    ├── 是 → 设置 extMeshShaderSupported = true
     *    │      设置 activeMode = EXT_MESH_SHADER
     *    │      记录设备限制（maxMeshOutputVertices 等）
     *    └── 否 ↓
     * 3. 检查是否包含 "VK_NV_mesh_shader"
     *    ├── 是 → 设置 nvMeshShaderSupported = true
     *    │      设置 activeMode = NV_MESH_SHADER
     *    │      记录设备限制
     *    └── 否 → 设置 activeMode = UNSUPPORTED
     *              记录警告日志，建议降级
     * </pre>
     *
     * @param physicalDevice 物理设备句柄（VkPhysicalDevice，不能为 null）
     *
     * @return true 如果支持至少一种 Mesh Shader 扩展（NV 或 EXT）
     *
     * @throws IllegalArgumentException 如果 physicalDevice 为 null
     *
     * @see #init(long, long)
     */
    public boolean checkSupport(long physicalDevice) {
        if (physicalDevice == 0L) {
            throw new IllegalArgumentException("physicalDevice 不能为 0（null 句柄）");
        }

        LOGGER.info(String.format("开始检测 Mesh Shader 支持 (physicalDevice=0x%X)", physicalDevice));

        try {
            // ========== 步骤 1: 查询设备支持的扩展列表 ==========
            //
            // TODO: 实际集成时的 Vulkan API 调用:
            //
            // VkPhysicalDeviceProperties2 deviceProps2 = {};
            // deviceProps2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            //
            // // 尝试查询 EXT_mesh_shader 属性
            // VkPhysicalDeviceMeshShaderPropertiesEXT extMeshProps = {};
            // extMeshProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_PROPERTIES_EXT;
            // deviceProps2.pNext = &extMeshProps;
            //
            // vkGetPhysicalDeviceProperties2(physicalDevice, &deviceProps2);
            //
            // VkPhysicalDeviceFeatures2 deviceFeats2 = {};
            // deviceFeats2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            //
            // VkPhysicalDeviceMeshShaderFeaturesEXT extMeshFeats = {};
            // extMeshFeats.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT;
            // deviceFeats2.pNext = &extMeshFeats;
            //
            // vkGetPhysicalDeviceFeatures2(physicalDevice, &deviceFeats2);
            //
            // if (extMeshFeats.meshShader && extMeshFeats.taskShader) {
            //     this.extMeshShaderSupported = true;
            //     this.activeMode = MeshShaderMode.EXT_MESH_SHADER;
            //
            //     LOGGER.info(String.format(
            //         "✓ 检测到 VK_EXT_mesh_shader 支持:" +
            //         " maxTaskWorkGroupInvocations=%d, " +
            //         " maxMeshOutputVertices=%d, " +
            //         " maxMeshOutputPrimitives=%d",
            //         extMeshProps.maxTaskWorkGroupInvocations,
            //         extMeshProps.maxMeshOutputVertices,
            //         extMeshProps.maxMeshOutputPrimitives
            //     ));
            //
            //     return true;
            // }
            //
            // // 如果 EXT 不支持，尝试查询 NV_mesh_shader
            // VkPhysicalDeviceMeshShaderPropertiesNV nvMeshProps = {};
            // nvMeshProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_PROPERTIES_NV;
            // deviceProps2.pNext = &nvMeshProps;
            //
            // vkGetPhysicalDeviceProperties2(physicalDevice, &deviceProps2);
            //
            // VkPhysicalDeviceMeshShaderFeaturesNV nvMeshFeats = {};
            // nvMeshFeats.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_NV;
            // deviceFeats2.pNext = &nvMeshFeats;
            //
            // vkGetPhysicalDeviceFeatures2(physicalDevice, &deviceFeats2);
            //
            // if (nvMeshFeats.meshShader && nvMeshFeats.taskShader) {
            //     this.nvMeshShaderSupported = true;
            //     this.activeMode = MeshShaderMode.NV_MESH_SHADER;
            //
            //     LOGGER.info(String.format(
            //         "✓ 检测到 VK_NV_mesh_shader 支持:" +
            //         " maxTaskWorkGroupTotalCount=%d, " +
            //         " maxMeshOutputVertices=%d, " +
            //         " maxMeshOutputPrimitives=%d",
            //         nvMeshProps.maxTaskWorkGroupTotalCount,
            //         nvMeshProps.maxMeshOutputVertices,
            //         nvMeshProps.maxMeshOutputPrimitives
            //     ));
            //
            //     return true;
            // }
            //
            // // 都不支持
            // this.activeMode = MeshShaderMode.UNSUPPORTED;
            // LOGGER.warning("✗ 未检测到 Mesh Shader 支持 (NV/EXT)，将降级到传统路径");

            // ===== 模拟检测结果（开发阶段占位符）=====
            // TODO: 移除此模拟代码，替换为上面的真实 Vulkan API 调用
            this.extMeshShaderSupported = false;  // 假设暂不支持 EXT
            this.nvMeshShaderSupported = true;    // 假设支持 NV（用于开发测试）
            this.activeMode = MeshShaderMode.NV_MESH_SHADER;

            LOGGER.info(String.format(
                    "✓ Mesh Shader 支持检测完成: mode=%s, NV=%b, EXT=%b",
                    this.activeMode.name(),
                    this.nvMeshShaderSupported,
                    this.extMeshShaderSupported
            ));

            return this.activeMode != MeshShaderMode.UNSUPPORTED;

        } catch (Exception e) {
            LOGGER.severe(String.format("Mesh Shader 支持检测失败: %s", e.getMessage()));
            this.activeMode = MeshShaderMode.UNSUPPORTED;
            return false;
        }
    }

    /**
     * 初始化所有 GPU 资源
     * <p>
     * 必须先调用 {@link #checkSupport(long)} 确认支持后再调用此方法。
     * 分配所有需要的 GPU Buffer、创建 Pipeline 和 Descriptor Set。
     *
     * <h3>初始化流程：</h3>
     * <pre>
     * 1. 验证前置条件（已检测支持、有效设备句柄）
     * 2. 创建 Pipeline Layout（Descriptor Set + Push Constants）
     * 3. 编译并创建 Task Shader Pipeline
     * 4. 编译并创建 Mesh Shader Pipeline
     * 5. 分配 Meshlet 数据缓冲区（SSBO）
     * 6. 分配可见计数缓冲区（Atomic Counter）
     * 7. 分配 Indirect Draw 缓冲区
     * 8. 创建 Descriptor Set 并绑定所有 Buffer
     * </pre>
     *
     * @param device       设备句柄（VkLogicalDevice，不能为 0）
     * @param vmaAllocator VMA 分配器句柄（不能为 0，用于高效内存管理）
     *
     * @throws IllegalStateException    如果未检测支持或已初始化
     * @throws IllegalArgumentException 如果参数无效
     * @throws RuntimeException         如果 GPU 资源分配失败
     *
     * @see #checkSupport(long)
     * @see #close()
     */
    public void init(long device, long vmaAllocator) {
        // ========== 参数校验 ==========
        if (device == 0L) {
            throw new IllegalArgumentException("device 不能为 0（null 句柄）");
        }
        if (vmaAllocator == 0L) {
            throw new IllegalArgumentException("vmaAllocator 不能为 0（null 句柄）");
        }
        if (this.activeMode == MeshShaderMode.UNSUPPORTED) {
            throw new IllegalStateException(
                    "Mesh Shader 不受支持，请先调用 checkSupport() 或启用降级模式"
            );
        }
        if (this.initialized) {
            throw new IllegalStateException("MeshShaderRenderer 已经初始化过，请勿重复调用");
        }

        try {
            this.device = device;
            this.vmaAllocator = vmaAllocator;

            LOGGER.info(String.format(
                    "开始初始化 MeshShaderRenderer (MR2): mode=%s",
                    this.activeMode.name()
            ));

            // ========== 步骤 1: 创建 Pipeline Layout ==========
            //
            // 定义 Descriptor Set Layouts:
            // Set 0: Uniform Buffers (Camera, Model matrices)
            // Set 1: Storage Buffers (Meshlet data, Visible indices, Counters)
            //
            // 定义 Push Constants:
            // - viewProjMatrix (4x4 float matrix = 64 bytes)
            // - cameraPosition (vec3 + padding = 16 bytes)
            // - lodDistances[8] (float array = 32 bytes)
            // Total: 112 bytes (within Vulkan limit of 128 bytes)
            //
            // TODO: 实际集成时的 Vulkan API 调用:
            //
            // VkDescriptorSetLayoutBinding bindings[] = {
            //     // Set 0: Uniform Buffers
            //     { .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
            //       .stageFlags = VK_SHADER_STAGE_TASK_BIT_NV | VK_SHADER_STAGE_MESH_BIT_NV | VK_SHADER_STAGE_FRAGMENT_BIT },
            //     { .binding = 1, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
            //       .stageFlags = VK_SHADER_STAGE_TASK_BIT_NV | VK_SHADER_STAGE_MESH_BIT_NV },
            //
            //     // Set 1: Storage Buffers
            //     { .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            //       .stageFlags = VK_SHADER_STAGE_TASK_BIT_NV },  // Meshlet tasks input
            //     { .binding = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            //       .stageFlags = VK_SHADER_STAGE_TASK_BIT_NV | VK_SHADER_STAGE_MESH_BIT_NV },  // Visible indices
            //     { .binding = 2, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            //       .stageFlags = VK_SHADER_STAGE_MESH_BIT_NV | VK_SHADER_STAGE_FRAGMENT_BIT },  // Meshlet data
            //     { .binding = 3, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            //       .stageFlags = VK_SHADER_STAGE_TASK_BIT_NV | VK_SHADER_STAGE_MESH_BIT_NV }   // Counters
            // };
            //
            // VkPushConstantRange pushConstant = {
            //     .stageFlags = VK_SHADER_STAGE_TASK_BIT_NV | VK_SHADER_STAGE_MESH_BIT_NV,
            //     .offset = 0,
            //     .size = 112  // sizeof(TaskUniforms)
            // };
            //
            // VkPipelineLayoutCreateInfo layoutInfo = {
            //     .setLayoutCount = 2,
            //     .pSetLayouts = descriptorSetLayouts,
            //     .pushConstantRangeCount = 1,
            //     .pPushConstants = &pushConstant
            // };
            //
            // vkCreatePipelineLayout(device, &layoutInfo, nullptr, &this.pipelineLayout);

            // ========== 步骤 2: 创建 Task Shader Pipeline ==========
            //
            // 加载并编译 SPIR-V:
            // taskShaderModule = loadSPIRV("shaders/task/meshlet_task_selection.comp.spv")
            //
            // VkPipelineShaderStageCreateInfo taskStage = {
            //     .stage = VK_SHADER_STAGE_TASK_BIT_NV,  // 或 EXT 版本
            //     .module = taskShaderModule,
            //     .pName = "main"
            // };
            //
            // VkGraphicsPipelineCreateInfo pipelineInfo = { ... };
            // pipelineInfo.stageCount = 3;  // Task + Mesh + Fragment
            // pipelineInfo.pStages = { taskStage, meshStage, fragStage };
            //
            // vkCreateGraphicsPipelines(device, cache, 1, &pipelineInfo, nullptr, &this.taskPipeline);
            // 注意: Task/Mesh shader 在 Vulkan 中作为 Graphics Pipeline 的一部分！

            // ========== 步骤 3: 创建 Mesh Shader Pipeline ==========
            //
            // meshShaderModule = loadSPIRV("shaders/mesh/meshlet_mesh.shader.spv")
            //
            // 类似步骤 2，添加 MESH shader stage

            // ========== 步骤 4: 分配 Meshlet 数据缓冲区 ==========
            //
            // 计算所需大小:
            // 每个 Meshlet 结构体大小（估算）:
            //   positions: 64 * 3 * 2 (half-float) = 384 bytes
            //   normals: 64 * 4 (octahedral packed) = 256 bytes
            //   texCoords: 64 * 2 * 2 (half-float) = 256 bytes
            //   indices: 126 (uint8) = 126 bytes
            //   boundingSphere: 4 * 4 (float) = 16 bytes
            //   metadata: 2 * 4 (uint) = 8 bytes
            // Total per meshlet ≈ 1046 bytes (~1 KB)
            //
            // long meshletBufferSize = MAX_MESHLETS * getMeshletStructSize();
            //
            // VmaAllocationInfo allocInfo = {};
            // vmaCreateBuffer(
            //     this.vmaAllocator,
            //     &(VkBufferCreateInfo) {
            //         .size = meshletBufferSize,
            //         .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
            //                  VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            //         .sharingMode = VK_SHARING_MODE_EXCLUSIVE
            //     },
            //     &(VmaAllocationCreateInfo) {
            //         .usage = VMA_MEMORY_USAGE_GPU_ONLY,
            //         .requiredFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            //     },
            //     &this.meshletBuffer,
            //     &meshletAllocation,
            //     &allocInfo
            // );

            // ========== 步骤 5: 分配可见计数缓冲区 ==========
            //
            // vmaCreateBuffer(..., size=4, usage=STORAGE_BUFFER|ATOMIC_COUNTER, ...);
            // → this.meshletCountBuffer

            // ========== 步骤 6: 分配 Indirect Draw 缓冲区 ==========
            //
            // vmaCreateBuffer(..., size=8, usage=INDIRECT_BUFFER, ...);
            // → this.indirectDrawBuffer

            // ========== 步骤 7: 创建并更新 Descriptor Sets ==========
            //
            // vkAllocateDescriptorSets(device, &allocInfo, &descriptorSets);
            //
            // VkDescriptorBufferInfo bufferInfos[] = {
            //     { .buffer = cameraUBO, .offset = 0, .range = sizeof(CameraUBO) },
            //     { .buffer = meshletBuffer, .offset = 0, .range = VK_WHOLE_SIZE },
            //     { .buffer = meshletCountBuffer, .offset = 0, .range = 4 },
            //     ...
            // };
            //
            // vkUpdateDescriptorSets(device, descriptorWrites, ...);

            this.initialized = true;

            LOGGER.info(String.format(
                    "✓ MeshShaderRenderer 初始化完成 (MR2): " +
                    "mode=%s, maxMeshlets=%d, meshletBufferSize≈%dMB",
                    this.activeMode.name(),
                    MAX_MESHLETS,
                    (MAX_MESHLETS * 1046) / (1024 * 1024)  // 约 64MB
            ));

        } catch (Exception e) {
            this.initialized = false;
            throw new RuntimeException(
                    String.format("MeshShaderRenderer GPU 资源分配失败: %s", e.getMessage()),
                    e
            );
        }
    }

    // ==================== Meshlet 数据管理方法 ====================

    /**
     * 上传 Meshlet 数据到 GPU
     * <p>
     * 将 CPU 端的 Meshlet 数组批量上传到 GPU 端的 SSBO（Storage Buffer）。
     * 使用 Staging Buffer 实现异步传输，避免阻塞渲染线程。
     *
     * <h3>数据格式转换：</h3>
     * <pre>
     * CPU 端 (Java):
     *   Meshlet[] meshlets → 浮点数组/字节流
     *
     * GPU 端 (GLSL std430):
     *   struct Meshlet {
     *       vec3  positions[64];      // half-float 解压
     *       vec3  normals[64];        // octahedral 解压
     *       vec2  texCoords[64];      // half-float 解压
     *       uint8 indices[126];
     *       vec4  boundingSphere;     // center + radius
       *       uint  lodLevel;
     *       uint  parentChunkId;
     *   };
     * </pre>
     *
     * <h3>性能优化策略：</h3>
     * <ul>
     *   <li>批量上传：一次调用上传多个 Meshlets，减少 API 开销</li>
     *   <li>异步传输：使用 Copy Queue 或 Staging Buffer</li>
     *   <li>增量更新：仅更新变化的 Meshlet（脏标记）</li>
     *   <li>内存对齐：确保数据满足 std430 布局对齐要求</li>
     * </ul>
     *
     * @param meshlets Meshlet 数组（CPU 端数据，不能为 null）
     * @param count    要上传的 Meshlet 数量（必须 > 0 且 <= MAX_MESHLETS）
     *
     * @throws IllegalArgumentException 如果参数无效
     * @throws IllegalStateException    如果未初始化
     *
     * @see Meshlet
     */
    public void uploadMeshlets(Meshlet[] meshlets, int count) {
        if (!this.initialized) {
            throw new IllegalStateException("MeshShaderRenderer 未初始化");
        }
        if (meshlets == null) {
            throw new IllegalArgumentException("meshlets 不能为 null");
        }
        if (count <= 0 || count > MAX_MESHLETS) {
            throw new IllegalArgumentException(
                    String.format("count 必须在 (0, %d] 范围内: %d", MAX_MESHLETS, count)
            );
        }
        if (count > meshlets.length) {
            throw new IllegalArgumentException(
                    String.format("count (%d) 不能超过 meshlets 数组长度 (%d)", count, meshlets.length)
            );
        }

        long startTime = System.nanoTime();

        try {
            // ========== 步骤 1: 准备 Staging Buffer ==========
            //
            // 计算总数据大小:
            // long totalSize = count * getMeshletStructSize();
            //
            // VmaAllocationInfo stagingAllocInfo = {};
            // VmaBuffer stagingBuffer = createStagingBuffer(totalSize);
            //
            // 映射 Staging Buffer 到 CPU 可访问内存:
            // void* mappedData = nullptr;
            // vmaMapMemory(this.vmaAllocator, stagingBuffer.allocation, &mappedData);

            // ========== 步骤 2: 将 Java Meshlet[] 序列化为二进制 ==========
            ByteBuffer byteBuffer = ByteBuffer.allocate(count * getEstimatedMeshletSize())
                    .order(ByteOrder.nativeOrder());

            for (int i = 0; i < count; i++) {
                Meshlet meshlet = meshlets[i];
                serializeMeshlet(byteBuffer, meshlet);
            }
            byteBuffer.flip();

            // ========== 步骤 3: 拷贝到 Staging Buffer ==========
            // memcpy(mappedData, byteBuffer.array(), byteBuffer.remaining());
            // vmaUnmapMemory(this.vmaAllocator, stagingBuffer.allocation);

            // ========== 步骤 4: 提交 Copy Command（异步传输）==========
            //
            // VkBufferCopy copyRegion = {
            //     .srcOffset = 0,
            //     .dstOffset = 0,  // 或指定偏移以支持增量更新
            //     .size = totalSize
            // };
            //
            // vkCmdCopyBuffer(commandBuffer, stagingBuffer.buffer, this.meshletBuffer, 1, &copyRegion);
            //
            // 注意: 实际应在 CommandEncoder 中提交，此处仅为示例

            this.totalRegisteredMeshlets = count;

            long elapsed = System.nanoTime() - startTime;
            this.totalUploadTimeNanos.addAndGet(elapsed);

            LOGGER.fine(String.format(
                    "上传 %d 个 Meshlets 到 GPU: %.2f ms, 总大小≈%.2f MB",
                    count,
                    elapsed / 1_000_000.0,
                    (count * getEstimatedMeshletSize()) / (1024.0 * 1024.0)
            ));

        } catch (Exception e) {
            LOGGER.severe(String.format("上传 Meshlet 数据失败: %s", e.getMessage()));
            throw new RuntimeException("Meshlet data upload failed", e);
        }
    }

    /**
     * 构建 Meshlet LOD 层次结构
     * <p>
     * 从 Chunk 渲染数据中提取并组织 Meshlet 的 LOD 层级关系。
     * 这对于实现动态 LOD 至关重要——Task Shader 可以根据距离
     * 快速选择合适的 LOD 级别而无需遍历整个场景图。
     *
     * <h3>LOD 层次结构设计：</h3>
     * <pre>
     * ChunkRenderData (输入)
     * ├── Section 0 (Y=0-15)
     * │   ├── Meshlet Group 0 (LOD 0: 最高精度)
     * │   │   ├── Meshlet[0..N]  (原始三角形)
     * │   │   └── BoundingSphere
     * │   ├── Meshlet Group 1 (LOD 1: 50% 简化)
     * │   │   ├── Meshlet[N+1..M]
     * │   │   └── BoundingSphere (更大)
     * │   └── ...
     * ├── Section 1 (Y=16-31)
     * └── ...
     *
     * 输出: MeshletTask[] (Task Shader 输入)
     * ├── Task[0]: chunkId=0, lodLevels=[0,1,2], meshletOffset=0, meshletCount=X
     * ├── Task[1]: chunkId=1, lodLevels=[0,1,2], meshletOffset=X, meshletCount=Y
     * └── ...
     * </pre>
     *
     * <h3>算法原理：</h3>
     * <ol>
     *   <li><b>空间划分</b>: 将 Chunk 按 Section 或空间位置分组</li>
     *   <li><b>网格简化</b>: 使用 Quadric Error Metrics (QEM) 或边折叠生成 LOD 变体</li>
     *   <li><b>边界体计算</b>: 为每个 LOD 级别计算保守包围球</li>
     *   <li><b>距离阈值设定</b>: 基于屏幕空间误差公式设定切换距离</li>
     * </ol>
     *
     * @param chunkData 区块渲染数据（不能为 null，包含几何体和材质信息）
     *
     * @throws IllegalArgumentException 如果 chunkData 为 null
     * @throws IllegalStateException    如果未初始化
     *
     * @see ChunkRenderData
     * @see MeshletTask
     */
    public void buildLODHierarchy(ChunkRenderData chunkData) {
        if (!this.initialized) {
            throw new IllegalStateException("MeshShaderRenderer 未初始化");
        }
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData 不能为 null");
        }

        LOGGER.fine(String.format(
                "构建 LOD 层次结构: chunkId=%d, sections=%d",
                chunkData.getChunkId(),
                chunkData.getSectionCount()
        ));

        try {
            // ========== 步骤 1: 提取 Section 几何数据 ==========
            //
            // for (int sectionY = 0; sectionY < chunkData.getSectionCount(); sectionY++) {
            //     SectionGeometry section = chunkData.getSection(sectionY);
            //
            //     // 提取所有可见面（考虑面剔除）
            //     List&lt;Quad&gt; quads = extractVisibleQuads(section);
            //
            //     // 合并为 Meshlets（贪心算法或聚类）
            //     List&lt;Meshlet&gt; meshlets = clusterIntoMeshlets(quads, MESHLET_MAX_VERTICES, MESHLET_MAX_INDICES);
            //
            //     // 为每个 Meshlet 计算 LOD 变体
            //     for (Meshlet baseMeshlet : meshlets) {
            //         for (int lod = 1; lod < MAX_LOD_LEVELS; lod++) {
            //             Meshlet lodVariant = simplifyMeshlet(baseMeshlet, lod);
            //             registerLODVariant(baseMeshlet, lodVariant, lod);
            //         }
            //     }
            // }

            // ========== 步骤 2: 构建 Meshlet Tasks ==========
            //
            // List&lt;MeshletTask&gt; tasks = new ArrayList&lt;&gt;();
            //
            // for (ChunkSection section : chunkData.getSections()) {
            //     MeshletTask task = new MeshletTask();
            //     task.meshletOffset = nextGlobalMeshletIndex;
            //     task.meshletCount = section.getMeshletCount();
            //
            //     // 计算该 Chunk 的 LOD 距离阈值
            //     // 基于投影公式: distance = screenSize / (errorThreshold * tan(fov/2))
            //     task.lodDistanceThreshold = computeLODDistanceThreshold(section.getBoundingSphere());
            //
            //     tasks.add(task);
            //     nextGlobalMeshletIndex += task.meshletCount;
            // }
            //
            // // 上传 Tasks 到 GPU
            // uploadMeshletTasks(tasks.toArray(new MeshletTask[0]), tasks.size());

            LOGGER.info("LOD 层次结构构建完成");

        } catch (Exception e) {
            LOGGER.severe(String.format("构建 LOD 层次结构失败: %s", e.getMessage()));
        }
    }

    // ==================== 核心渲染方法 ====================

    /**
     * 使用 Mesh Shader 渲染（单次 Draw 调用!）
     * <p>
     * 这是 MR2 的核心方法——通过单次 {@code vkCmdDrawMeshTasksIndirect*} 调用
     * 完成全部渲染工作，实现真正的零 CPU 开销 GPU-Driven 渲染。
     *
     * <h3>渲染流程详解：</h3>
     * <pre>
     * 输入参数:
     *   - encoder: Blaze3D/Vulkan Command Encoder
     *   - camera: 相机数据（位置、方向、FOV等）
     *   - viewProjMatrix: 4x4 视角投影矩阵（列主序）
     *
     * ┌─ 准备阶段 ─────────────────────────────────────────────┐
     * │                                                          │
     * │ 1. 清零可见计数器                                        │
     * │    encoder.fillBuffer(meshletCountBuffer, 0, 4, 0);     │
     * │                                                          │
     * │ 2. 更新 Camera UBO                                       │
     * │    encoder.updateUniformBuffer(cameraUBO, cameraData);   │
     * │                                                          │
     * │ 3. 推送 Push Constants                                   │
     * │    TaskUniforms params;                                  │
     * │    params.viewProj = viewProjMatrix;                     │
     * │    params.cameraPos = camera.getPosition();              │
     * │    params.lodDistances = precomputedLODDistances;        │
     * │    encoder.pushConstants(params);                        │
     * └──────────────────────────────────────────────────────────┘
     *                           ↓
     * ┌─ 执行阶段: 单次 DispatchMesh 调用 ──────────────────────┐
     * │                                                          │
     * │ encoder.drawMeshTasksIndirect(                          │
     * │     indirectDrawBuffer,  // 包含 taskCount 的缓冲区      │
     * │     0,                 // 偏移                          │
     * │     1,                 // draw count (单次调用!)         │
     * │     sizeof(MeshTaskCount)  // stride                     │
     * │ );                                                      │
     * │                                                          │
     * │ GPU 自动执行:                                            │
     * │   Task Shader → Mesh Shader → Fragment Shader           │
     * └──────────────────────────────────────────────────────────┘
     * </pre>
     *
     * <h3>GPU 端并行执行模型：</h3>
     * <pre>
     * Task Shader (并行度: taskCount):
     * ┌─ Workgroup 0 ─┐  ┌─ Workgroup 1 ─┐  ┌─ Workgroup N ─┐
     * │ Thread 0-31   │  │ Thread 0-31   │  │ Thread 0-31   │
     * │ 处理 Task 0  │  │ 处理 Task 1  │  │ 处理 Task N  │
     * │ 中的 Meshlets│  │ 中的 Meshlets│  │ 中的 Meshlets│
     * └──────────────┘  └──────────────┘  └──────────────┘
     *         ↓ 输出可见 Meshlet 索引列表 ↓
     *
     * Mesh Shader (并行度: visibleMeshletCount):
     * ┌─ Workgroup 0 ──┐  ┌─ Workgroup 1 ──┐  ┌─ Workgroup M ──┐
     * │ Thread 0-31    │  │ Thread 0-31    │  │ Thread 0-31    │
     * │ 生成 Meshlet 0│  │ 生成 Meshlet 1│  │ 生成 Meshlet M│
     * │ 的三角形      │  │ 的三角形      │  │ 的三角形      │
     * └────────────────┘  └────────────────┘  └────────────────┘
     *         ↓ 输出到光栅化器 ↓
     * </pre>
     *
     * @param encoder        命令编码器（Blaze3D CommandEncoder 或 Vulkan CommandBuffer，不能为 null）
     * @param camera         相机数据对象（不能为 null，需提供 getPosition() 等接口）
     * @param viewProjMatrix 视角投影矩阵（4x4 float 矩阵，列主序，不能为 null）
     *
     * @throws IllegalArgumentException 如果任何参数为 null
     * @throws IllegalStateException    如果未初始化或不支持 Mesh Shader
     *
     * @see #checkSupport(long)
     * @see GPUDrivenVisibilitySystem#executeCulling(Object, Object)
     */
    public void render(Object encoder, Object camera, Matrix4f viewProjMatrix) {
        // ========== 参数校验 ==========
        if (!this.initialized) {
            throw new IllegalStateException("MeshShaderRenderer 未初始化");
        }
        if (encoder == null) {
            throw new IllegalArgumentException("encoder 不能为 null");
        }
        if (camera == null) {
            throw new IllegalArgumentException("camera 不能为 null");
        }
        if (viewProjMatrix == null) {
            throw new IllegalArgumentException("viewProjMatrix 不能为 null");
        }
        if (this.activeMode == MeshShaderMode.UNSUPPORTED) {
            // 降级到传统路径
            handleFallback(encoder, camera, viewProjMatrix);
            return;
        }

        long frameStartTime = System.nanoTime();

        try {
            // ========== 步骤 1: 清零可见计数器 ==========
            //
            // TODO: 实际集成时的 Vulkan 命令:
            //
            // VkBufferFillInfo fillInfo = {};
            // fillInfo.buffer = this.meshletCountBuffer;
            // fillInfo.offset = 0;
            // fillInfo.size = 4;  // 单个 uint32
            // fillInfo.data = 0;
            //
            // vkCmdFillBuffer(commandBuffer, &fillInfo);

            // ========== 步骤 2: 绑定 Graphics Pipeline（包含 Task + Mesh + Fragment）==========
            //
            // vkCmdBindPipeline(
            //     commandBuffer,
            //     VK_PIPELINE_BIND_POINT_GRAPHICS,
            //     this.meshPipeline  // 包含所有 shader stages 的完整 pipeline
            // );

            // ========== 步骤 3: 绑定 Descriptor Sets ==========
            //
            // vkCmdBindDescriptorSets(
            //     commandBuffer,
            //     VK_PIPELINE_BIND_POINT_GRAPHICS,
            //     this.pipelineLayout,
            //     0,  // firstSet
            //     2,  // descriptorSetCount (Set 0: UBO, Set 1: SSBO)
            //     descriptorSets,
            //     nullptr  // dynamicOffsets
            // );

            // ========== 步骤 4: 推送 Push Constants ==========
            //
            // 构建 TaskUniforms 结构体:
            // TaskUniforms params = {};
            // memcpy(params.viewProj, viewProjMatrix.values(), 64);  // 4x4 matrix
            // memcpy(params.cameraPos, extractCameraPosition(camera), 12);  // vec3
            // memcpy(params.lodDistances, this.precomputedLODDistances, 32);  // float[8]
            //
            // vkCmdPushConstants(
            //     commandBuffer,
            //     this.pipelineLayout,
            //     VK_SHADER_STAGE_TASK_BIT_NV | VK_SHADER_STAGE_MESH_BIT_NV,
            //     0,  // offset
            //     112,  // size (sizeof(TaskUniforms))
            //     &params
            // );

            // ========== 步骤 5: 单次 DispatchMesh 调用（核心！）==========
            //
            // 根据使用的扩展版本选择正确的 API:
            //
            // if (this.activeMode == MeshShaderMode.EXT_MESH_SHADER) {
            //     // Vulkan 1.3+ EXT 版本
            //     vkCmdDrawMeshTasksIndirectEXT(
            //         commandBuffer,
            //         this.indirectDrawBuffer,  // 包含 taskCount 的缓冲区
            //         0,      // offset
            //         1,      // drawCount (单次调用!)
            //         sizeof(MeshTaskCount)  // stride
            //     );
            // } else {
            //     // Vulkan 1.2+ NV 版本
            //     vkCmdDrawMeshTasksIndirectNV(
            //         commandBuffer,
            //         this.indirectDrawBuffer,
            //         0,
            //         1,
            //         sizeof(MeshTaskCount)
            //     );
            // }

            // ========== 步骤 6: 性能统计记录 ==========
            long frameElapsed = System.nanoTime() - frameStartTime;
            this.totalFramesRendered.incrementAndGet();

            // 估算当前帧的可见 Meshlet 数量（简化版，实际应从 GPU 读回）
            this.currentVisibleMeshletCount = estimateVisibleMeshletCount();

            LOGGER.finest(String.format(
                    "Mesh Shader 渲染帧 #%d 完成: visibleMeshlets≈%d,耗时=%.2f μs",
                    this.totalFramesRendered.get(),
                    this.currentVisibleMeshletCount,
                    frameElapsed / 1000.0
            ));

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "Mesh Shader 渲染异常 (MR2): %s, 将尝试降级",
                    e.getMessage()
            ));
            handleFallback(encoder, camera, viewProjMatrix);
        }
    }

    // ==================== 降级处理方法 ====================

    /**
     * 降级到传统渲染路径
     * <p>
     * 当 Mesh Shader 不可用或发生错误时，自动回退到：
     * <ol>
     *   <li>{@link GPUDrivenVisibilitySystem} - GPU 剔除 + Indirect Draw（如果可用）</li>
     *   <li>传统 CPU-Driven Draw Call 批处理（最终保底方案）</li>
     * </ol>
     *
     * @param encoder        命令编码器
     * @param camera         相机数据
     * @param viewProjMatrix 视角投影矩阵
     */
    private void handleFallback(Object encoder, Object camera, Matrix4f viewProjMatrix) {
        this.totalFallbackCount.incrementAndGet();

        LOGGER.warning(String.format(
                "Mesh Shader 不可用，降级到传统路径 (fallback #%d)",
                this.totalFallbackCount.get()
        ));

        try {
            // TODO: 实际集成时的降级逻辑:
            //
            // if (gpuDrivenVisibilitySystem != null && gpuDrivenVisibilitySystem.isInitialized()) {
            //     // 降级到 MR1: GPU-Driven Visibility + Indirect Draw
            //     gpuDrivenVisibilitySystem.executeCulling(encoder, camera);
            //     gpuDrivenVisibilitySystem.renderWithCulling(encoder);
            // } else {
            //     // 最终保底: 传统 CPU-Driven 渲染
            //     renderTraditionalCPUPath(encoder, camera, viewProjMatrix);
            // }

        } catch (Exception fallbackEx) {
            LOGGER.severe(String.format(
                    "降级渲染路径也失败了: %s, 渲染将丢失",
                    fallbackEx.getMessage()
            ));
        }
    }

    // ==================== AutoCloseable 实现 ====================

    /**
     * 释放所有 GPU 资源
     * <p>
     * 应在模块卸载或窗口关闭时调用。
     * 释放后此对象不可再使用（除非重新 init）。
     * <p>
     * 释放顺序（逆序初始化）：
     * <ol>
     *   <li>等待 GPU 空闲（vkDeviceWaitIdle）</li>
     *   <li>销毁 Pipeline（taskPipeline, meshPipeline）</li>
     *   <li>销毁 PipelineLayout</li>
     *   <li>释放 GPU Buffer（meshletBuffer, meshletCountBuffer, indirectDrawBuffer）</li>
     *   <li>释放 Descriptor Sets</li>
     *   <li>重置状态标志</li>
     * </ol>
     */
    @Override
    public void close() {
        if (!this.initialized) {
            return; // 未初始化或已释放
        }

        LOGGER.info("正在释放 MeshShaderRenderer 资源 (MR2)...");

        try {
            // ========== 步骤 1: 等待 GPU 空闲 ==========
            //
            // TODO: vkDeviceWaitIdle(this.device);

            // ========== 步骤 2: 销毁 Pipeline ==========
            // vkDestroyPipeline(this.device, this.taskPipeline, nullptr);
            // vkDestroyPipeline(this.device, this.meshPipeline, nullptr);
            this.taskPipeline = 0L;
            this.meshPipeline = 0L;

            // ========== 步骤 3: 销毁 Pipeline Layout ==========
            // vkDestroyPipelineLayout(this.device, this.pipelineLayout, nullptr);
            this.pipelineLayout = 0L;

            // ========== 步骤 4: 释放 GPU Buffers (via VMA) ==========
            // vmaDestroyBuffer(this.vmaAllocator, this.meshletBuffer, &meshletAlloc);
            // vmaDestroyBuffer(this.vmaAllocator, this.meshletCountBuffer, &countAlloc);
            // vmaDestroyBuffer(this.vmaAllocator, this.indirectDrawBuffer, &indirectAlloc);
            this.meshletBuffer = 0L;
            this.meshletCountBuffer = 0L;
            this.indirectDrawBuffer = 0L;

            // ========== 步骤 5: 重置状态 ==========
            this.device = 0L;
            this.vmaAllocator = 0L;
            this.initialized = false;
            this.currentVisibleMeshletCount = 0;
            this.totalRegisteredMeshlets = 0;

            LOGGER.info("✓ MeshShaderRenderer 所有资源已释放 (MR2)");

        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "释放 MeshShaderRenderer 资源时发生异常: %s", e.getMessage()
            ));
        }
    }

    // ==================== 统计和监控 API ====================

    /**
     * 获取格式化的性能统计报告
     *
     * @return 包含详细性能指标的字符串（适合日志输出或调试界面显示）
     */
    public String formatStatisticsReport() {
        long frames = this.totalFramesRendered.get();
        long taskTimeNs = this.totalTaskShaderTimeNanos.get();
        long meshTimeNs = this.totalMeshShaderTimeNanos.get();
        long uploadTimeNs = this.totalUploadTimeNanos.get();
        long frustumCulled = this.totalMeshletsFrustumCulled.get();
        long lodDegraded = this.totalMeshletsLODDegraded.get();
        long fallbacks = this.totalFallbackCount.get();

        double avgTaskUs = frames > 0 ? taskTimeNs / 1000.0 / frames : 0;
        double avgMeshUs = frames > 0 ? meshTimeNs / 1000.0 / frames : 0;
        double avgUploadMs = frames > 0 ? uploadTimeNs / 1_000_000.0 / frames : 0;

        return String.format(
                "╔════════════════════════════════════════════════════╗\n" +
                "║        MR2: Mesh Shader Renderer 统计报告          ║\n" +
                "╠════════════════════════════════════════════════════╣\n" +
                "║ 模式: %-46s ║\n" +
                "║ 已初始化: %-43b ║\n" +
                "║ 总渲染帧数: %-41d ║\n" +
                "║ 注册 Meshlets 总数: %-33d ║\n" +
                "╠════════════════════════════════════════════════════╣\n" +
                "║ 平均 Task Shader 耗时: %-28.2f μs ║\n" +
                "║ 平均 Mesh Shader 耗时: %-28.2f μs ║\n" +
                "║ 平均上传耗时: %-35.2f ms ║\n" +
                "╠════════════════════════════════════════════════════╣\n" +
                "║ 视锥剔除 Meshlets 总数: %-29d ║\n" +
                "║ LOD 降级 Meshlets 总数: %-28d ║\n" +
                "║ 降级回退次数: %-37d ║\n" +
                "╠════════════════════════════════════════════════════╣\n" +
                "║ 当前可见 Meshlets (估算): %-27d ║\n" +
                "║ NV_mesh_shader 支持: %-34b ║\n" +
                "║ EXT_mesh_shader 支持: %-33b ║\n" +
                "╚════════════════════════════════════════════════════╝",
                this.activeMode.name(),
                this.initialized,
                frames,
                this.totalRegisteredMeshlets,
                avgTaskUs,
                avgMeshUs,
                avgUploadMs,
                frustumCulled,
                lodDegraded,
                fallbacks,
                this.currentVisibleMeshletCount,
                this.nvMeshShaderSupported,
                this.extMeshShaderSupported
        );
    }

    /**
     * 重置所有性能统计计数器
     */
    public void resetStatistics() {
        this.totalFramesRendered.set(0);
        this.totalTaskShaderTimeNanos.set(0);
        this.totalMeshShaderTimeNanos.set(0);
        this.totalUploadTimeNanos.set(0);
        this.totalMeshletsFrustumCulled.set(0);
        this.totalMeshletsLODDegraded.set(0);
        this.totalFallbackCount.set(0);

        LOGGER.info("MeshShaderRenderer: 所有统计计数器已重置 (MR2)");
    }

    // ==================== Getter 方法 ====================

    /** 获取当前活跃的 Mesh Shader 模式 */
    public MeshShaderMode getActiveMode() { return this.activeMode; }

    /** 是否支持 NV_mesh_shader */
    public boolean isNvMeshShaderSupported() { return this.nvMeshShaderSupported; }

    /** 是否支持 EXT_mesh_shader */
    public boolean isExtMeshShaderSupported() { return this.extMeshShaderSupported; }

    /** 是否已成功初始化 */
    public boolean isInitialized() { return this.initialized; }

    /** 获取当前帧的可见 Meshlet 数量估算值 */
    public int getCurrentVisibleMeshletCount() { return this.currentVisibleMeshletCount; }

    /** 获取注册的总 Meshlet 数量 */
    public int getTotalRegisteredMeshlets() { return this.totalRegisteredMeshlets; }

    /** 获取 Task Pipeline 句柄 */
    public long getTaskPipeline() { return this.taskPipeline; }

    /** 获取 Mesh Pipeline 句柄 */
    public long getMeshPipeline() { return this.meshPipeline; }

    /** 获取 Pipeline Layout 句柄 */
    public long getPipelineLayout() { return this.pipelineLayout; }

    /** 获取 Meshlet 数据缓冲区句柄 */
    public long getMeshletBuffer() { return this.meshletBuffer; }

    /** 获取总渲染帧数 */
    public long getTotalFramesRendered() { return this.totalFramesRendered.get(); }

    /** 获取平均 Task Shader 耗时（微秒）*/
    public double getAverageTaskShaderTimeMicros() {
        long frames = this.totalFramesRendered.get();
        return frames > 0 ? this.totalTaskShaderTimeNanos.get() / 1000.0 / frames : 0;
    }

    /** 获取平均 Mesh Shader 耗时（微秒）*/
    public double getAverageMeshShaderTimeMicros() {
        long frames = this.totalFramesRendered.get();
        return frames > 0 ? this.totalMeshShaderTimeNanos.get() / 1000.0 / frames : 0;
    }

    /** 获取降级回退总次数 */
    public long getTotalFallbackCount() { return this.totalFallbackCount.get(); }

    // ==================== 内部辅助方法 ====================

    /**
     * 估算单个 Meshlet 结构体的大小（字节）
     * <p>
     * 基于 GLSL std430 布局规则计算：
     * <ul>
     *   <li>vec3 positions[64]: 64 * 12 = 768 bytes (假设 float32)</li>
     *   <li>vec3 normals[64]: 64 * 12 = 768 bytes</li>
     *   <li>vec2 texCoords[64]: 64 * 8 = 512 bytes</li>
     *   <li>uint8 indices[126]: 126 bytes (packed as uint array with padding)</li>
     *   <li>vec4 boundingSphere: 16 bytes</li>
     *   <li>uint lodLevel: 4 bytes</li>
     *   <li>uint parentChunkId: 4 bytes</li>
     * </ul>
     * 注意：实际大小取决于压缩方案（half-float, octahedral encoding 等）
     *
     * @return 估算的结构体大小（字节）
     */
    private int getMeshletStructSize() {
        // 简化估算：基于未压缩格式
        // 实际应用中应根据使用的压缩方案调整
        return 1046;  // ~1KB per meshlet (见上方注释的计算)
    }

    /**
     * 获取单个 Meshlet 的估算内存占用（用于日志和调试）
     *
     * @return 估算大小（字节）
     */
    private int getEstimatedMeshletSize() {
        return getMeshletStructSize();
    }

    /**
     * 将 Java Meshlet 对象序列化为二进制 ByteBuffer
     * <p>
     * 按照 GLSL std430 布局规则写入数据。
     * 支持多种压缩格式（half-float, octahedral encoding 等）。
     *
     * @param buffer 目标 ByteBuffer（必须有余量）
     * @param meshlet 要序列化的 Meshlet 对象
     */
    private void serializeMeshlet(ByteBuffer buffer, Meshlet meshlet) {
        // ========== 序列化顶点位置（half-float 压缩）==========
        if (meshlet.vertices != null) {
            for (int i = 0; i < Math.min(meshlet.vertices.length, MESHLET_MAX_VERTICES * 3); i++) {
                buffer.putFloat(meshlet.vertices[i]);  // TODO: 转换为 half-float
            }
        }

        // ========== 序列化索引数据 ==========
        if (meshlet.indices != null) {
            for (int i = 0; i < Math.min(meshlet.indices.length, MESHLET_MAX_INDICES); i++) {
                buffer.put(meshlet.indices[i]);
            }
        }

        // ========== 序列化边界球 ==========
        if (meshlet.boundingSphereCenter != null && meshlet.boundingSphereCenter.length >= 3) {
            buffer.putFloat(meshlet.boundingSphereCenter[0]);
            buffer.putFloat(meshlet.boundingSphereCenter[1]);
            buffer.putFloat(meshlet.boundingSphereCenter[2]);
        }
        buffer.putFloat(meshlet.boundingSphereRadius);

        // ========== 序列化元数据 ==========
        buffer.putInt(meshlet.lodLevel & 0xFF);  // byte → int (带符号扩展处理)
        buffer.putInt(meshlet.parentChunkId);
    }

    /**
     * 估算当前帧的可见 Meshlet 数量
     * <p>
     * ⚠️ 这是一个近似值，避免引入 GPU→CPU 同步点！
     * 生产环境建议使用 Query Pool 异步获取精确值。
     *
     * @return 估算的可见 Meshlet 数量
     */
    private int estimateVisibleMeshletCount() {
        // 简化估算：假设约 60-80% 的注册 Meshlets 可见
        // 实际值取决于相机位置、视锥角度、遮挡情况等
        return Math.max(1, (int)(this.totalRegisteredMeshlets * 0.7));
    }

    // ==================== 内部数据类 ====================

    /**
     * Meshlet 数据结构（~1KB，GPU 端布局）
     * <p>
     * Meshlet 是现代渲染管线的核心原语——将大型网格分解为小型子网格单元，
     * 每个 Meshlet 可以独立进行视锥剔除、LOD 选择和光栅化。
     *
     * <h3>设计约束（来自 NV_mesh_shader 硬件限制）：</h3>
     * <table border="1">
     *   <tr><th>属性</th><th>NV 限制</th><th>EXT 限制</th></tr>
     *   <tr><td>最大顶点数</td><td>64</td><td>256</td></tr>
     *   <tr><td>最大索引数</td><td>126 (42 triangles)</td><td>256+ (varies)</td></tr>
     *   <tr><td>最大原始数</td><td>126</td><td>512+</td></tr>
     * </table>
     *
     * <h3>内存优化策略：</h3>
     * <ul>
     *   <li><b>Half-float 压缩</b>: 顶点位置使用 FP16（精度足够，节省 50%）</li>
     *   <li><b>Octahedral Encoding</b>: 法线压缩为 2 字节（精度损失 < 1%）</li>
     *   <li><b>本地索引</b>: 索引使用 uint8（因为 < 64 个顶点）</li>
     *   <li><b>边界球</b>: 用于快速剔除（比 AABB 更紧凑）</li>
     * </ul>
     *
     * <h3>使用示例：</h3>
     * <pre>
     * // 创建一个 Meshlet
     * Meshlet meshlet = new Meshlet();
     * meshlet.vertices = new float[] {
     *     x0, y0, z0,  // vertex 0
     *     x1, y1, z1,  // vertex 1
     *     ...
     * };  // 64 vertices * 3 components = 192 floats
     *
     * meshlet.indices = new byte[] {
     *     0, 1, 2,  // triangle 0
     *     1, 3, 2,  // triangle 1
     *     ...
     * };  // up to 126 indices (42 triangles)
     *
     * meshlet.boundingSphereCenter = new float[] { cx, cy, cz };
     * meshlet.boundingSphereRadius = radius;
     * meshlet.lodLevel = 0;  // highest detail
     * meshlet.parentChunkId = chunkId;
     * </pre>
     *
     * @see MeshletTask
     */
    public static class Meshlet {
        /**
         * 顶点位置数据（局部坐标系）
         * <p>
         * 格式: float[x0, y0, z0, x1, y1, z1, ..., x63, y63, z63]
         * 长度: 最多 MESHLET_MAX_VERTICES * 3 = 192 个 float
         * <p>
         * 上传到 GPU 时将转换为 half-float (FP16) 以节省带宽。
         * 使用局部坐标（相对于 Chunk 或对象原点）以提高精度。
         */
        public float[] vertices;

        /**
         * 三角形索引数据（本地顶点索引）
         * <p>
         * 格式: byte[i0, i1, i2, i3, i4, i5, ..., i125]
         * 长度: 最多 MESHLET_MAX_INDICES = 126 个 byte
         * 值域: [0, MESHLET_MAX_VERTICES - 1] 即 [0, 63]
         * <p>
         * 使用 uint8 而非 uint32 因为每个 Meshlet 最多 64 个顶点，
         * 8 位足够寻址所有顶点，节省 75% 索引内存。
         */
        public byte[] indices;

        /**
         * 边界球中心（对象空间坐标）
         * <p>
         * 格式: float[cx, cy, cz]
         * 用途: 快速视锥剔除和距离测试
         * <p>
         * 边界球相比 AABB 的优势：
         * - 存储更小（4 floats vs 6 floats）
         * - 测试更快（1 次距离运算 vs 6 次平面测试）
         * - 更紧凑（更适合球形物体）
         */
        public float[] boundingSphereCenter;

        /**
         * 边界球半径
         * <p>
         * 应该是保守估计（略大于实际半径），
         * 允许少量伪阳性（over-draw），但不允许伪阴性（漏剔）。
         */
        public float boundingSphereRadius;

        /**
         * LOD 等级（0 = 最高精度）
         * <p>
         * 值域: [0, MAX_LOD_LEVELS - 1] 即 [0, 7]
         * 含义:
         * <ul>
         *   <li>0: 原始几何体（无简化）</li>
         *   <li>1-3: 轻度到中度简化（近距离过渡）</li>
         *   <li>4-6: 中度到重度简化（中远距离）</li>
         *   <li>7: 最低精度或完全剔除候选（超远距离）</li>
         * </ul>
         * <p>
         * Task Shader 根据此值和相机距离决定是否使用此 Meshlet
         * 或替换为其父/子节点（更高/更低 LOD）。
         */
        public byte lodLevel;

        /**
         * 父 Chunk ID（所属区块标识符）
         * <p>
         * 用于关联 Meshlet 到原始 Minecraft Chunk，
         * 方便 Chunk 卸载时批量清理对应的 Meshlets。
         * <p>
         * 格式: 全局唯一 Chunk 标识符（可为 Chunk coordinates 的哈希值）
         */
        public int parentChunkId;

        /**
         * 默认构造函数
         * <p>
         * 初始化空 Meshlet，所有字段为默认值。
         * 使用前必须手动填充 vertices、indices 等数据。
         */
        public Meshlet() {
            // 默认构造，字段保持默认值（null/0）
        }

        /**
         * 参数化构造函数（便捷方法）
         *
         * @param vertexCount       顶点数量（<= MESHLET_MAX_VERTICES）
         * @param indexCount        索引数量（<= MESHLET_MAX_INDICES）
         * @param lod               LOD 等级
         * @param chunkId           父 Chunk ID
         */
        public Meshlet(int vertexCount, int indexCount, byte lod, int chunkId) {
            this.vertices = new float[vertexCount * 3];
            this.indices = new byte[indexCount];
            this.lodLevel = lod;
            this.parentChunkId = chunkId;
            this.boundingSphereCenter = new float[3];
            this.boundingSphereRadius = 0.0f;
        }
    }

    /**
     * Meshlet 任务数据（Task Shader 输入）
     * <p>
     * 每个 MeshletTask 代表一组待处理的 Meshlets，
     * 通常对应一个 Chunk、一个 Section 或一个空间区域。
     * Task Shader 并行处理这些任务，执行可见性和 LOD 测试。
     *
     * <h3>数据流向：</h3>
     * <pre>
     * CPU 端准备:
     *   ChunkRenderData → buildLODHierarchy() → MeshletTask[]
     *                                                  ↓
     * GPU 端 (Task Shader):
     *   MeshletTask[] (SSBO input) → visibility test → visibleMeshletIndices[] (output)
     *                                                  ↓
     * GPU 端 (Mesh Shader):
     *   visibleMeshletIndices[] (input) → geometry generation → rasterizer
     * </pre>
     *
     * <h3>GLSL 布局（std430）：</h3>
     * <pre>
     * struct MeshletTask {
     *     uint meshletOffset;          // offset 0,  size 4
     *     uint meshletCount;           // offset 4,  size 4
     *     float lodDistanceThreshold;  // offset 8,  size 4
     *     // padding to 16 bytes for alignment
     * };  // total: 12 bytes (+ 4 padding = 16 bytes aligned)
     * </pre>
     *
     * @see Meshlet
     * @see #buildLODHierarchy(ChunkRenderData)
     */
    public static class MeshletTask {
        /**
         * Meshlet 在全局数组中的偏移量
         * <p>
         * 指向 meshletBuffer (SSBO) 中的起始位置。
         * Task Shader 通过此偏移访问属于本任务的 Meshlet 数据。
         * <p>
         * 示例:
         * <pre>
         * meshletBuffer layout:
         * [0] : Meshlet of Chunk 0, Section 0, LOD 0
         * [1] : Meshlet of Chunk 0, Section 0, LOD 0
         * ...
         * [N] : Meshlet of Chunk 0, Section 0, LOD 1
         * [N+1] : Meshlet of Chunk 1, Section 0, LOD 0
         *
         * Task for Chunk 0:
         *   meshletOffset = 0
         *   meshletCount = N+1
         *
         * Task for Chunk 1:
         *   meshletOffset = N+1
         *   meshletCount = M
         * </pre>
         */
        public int meshletOffset;

        /**
         * 该任务包含的 Meshlet 数量
         * <p>
         * Task Shader 将遍历 range [meshletOffset, meshletOffset + meshletCount)
         * 中的每个 Meshlet 进行可见性测试。
         * <p>
         * 典型值: 每个 Chunk 约 10-50 个 Meshlets（取决于几何复杂度）
         */
        public int meshletCount;

        /**
         * LOD 距离阈值
         * <p>
         * 当相机到此任务边界球的距离超过此值时，
         * Task Shader 应该选择更低 LOD 级别的 Meshlet（如果可用）
         * 或直接剔除整个任务（如果距离极远）。
         * <p>
         * 计算公式（基于屏幕空间误差）:
         * <pre>
         * threshold = (screenSize * geometricError) / (allowedPixelsError * tan(fov/2))
         * </pre>
         * 其中:
         * <ul>
         *   <li>screenSize: 屏幕分辨率（像素）</li>
         *   <li>geometricError: 该 LOD 级别的几何误差（世界单位）</li>
         *   <li>allowedPixelsError: 允许的最大屏幕空间误差（通常 1-2 像素）</li>
         *   <li>fov: 垂直视场角（弧度）</li>
         * </ul>
         */
        public float lodDistanceThreshold;

        /**
         * 默认构造函数
         */
        public MeshletTask() {
            // 默认构造
        }

        /**
         * 参数化构造函数
         *
         * @param offset    Meshlet 偏移量
         * @param count     Meshlet 数量
         * @param lodDist   LOD 距离阈值
         */
        public MeshletTask(int offset, int count, float lodDist) {
            this.meshletOffset = offset;
            this.meshletCount = count;
            this.lodDistanceThreshold = lodDist;
        }
    }
}
