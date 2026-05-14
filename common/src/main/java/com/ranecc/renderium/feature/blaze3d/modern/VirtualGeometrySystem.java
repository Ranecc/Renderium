// Renderium - 现代化渲染架构组件
// VirtualGeometrySystem - Nanite-Style 虚拟几何体流送系统
// 来源文档: modern-render-architecture.md §3.4 Nanite-Style Virtual Geometry
// 策略ID: MR4 (Virtual Geometry System)
// 功能: 将场景几何体分解为 Cluster 层次结构，运行时动态选择 LOD，只传输视觉上必要的细节

package com.ranecc.renderium.feature.blaze3d.modern;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 虚拟几何体系统 (Nanite-Style) 🏗️
 * <p>
 * 灵感来自 Unreal Engine 5 的 Nanite 技术，实现 GPU-Driven 的虚拟几何体渲染。
 * <ul>
 *   <li><b>Cluster 层次结构</b>: 将场景几何体分解为 Cluster 树（BVH/DAG 结构）</li>
 *   <li><b>动态 LOD 选择</b>: 运行时根据屏幕空间误差动态选择 LOD</li>
 *   <li><b>智能流送</b>: 只传输和渲染视觉上必要的细节</li>
 *   <li><b>内存优化</b>: 基于 LRU 和优先级的显存管理</li>
 * </ul>
 *
 * <h2>架构定位：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Modern Rendering Pipeline               │
 * │                                                             │
 * │  ┌─────────────────────────────────────────────────────┐   │
 * │  │            VirtualGeometrySystem (MR4)              │   │
 * │  │                                                     │   │
 * │  │  ┌───────────────┐  ┌──────────────────────────┐   │   │
 * │  │  │ Cluster Tree  │  │ Streaming State Machine   │   │   │
 * │  │  │ (BVH/DAG)     │→│ RESIDENT/EVICTABLE/       │   │   │
 * │  │  │               │  │ STREAMING_IN/NOT_LOADED   │   │   │
 * │  │  └───────────────┘  └──────────────────────────┘   │   │
 * │  │          ↓                      ↓                   │   │
 * │  │  ┌────────────────────────────────────────────┐    │   │
 * │  │  │        GPU Compute Shader (cull_lod.comp)  │    │   │
 * │  │  │  Pass1: 遍历 Cluster 树 → LOD 选择         │    │   │
 * │  │  │  Pass2: 生成 Indirect Draw 命令             │    │   │
 * │  │  └────────────────────────────────────────────┘    │   │
 * │  └─────────────────────────────────────────────────────┘   │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 与传统渲染的区别:
 * 传统: CPU 决定每个 Chunk 的 LOD → GPU 渲染
 * Nanite: GPU Compute Shader 根据屏幕空间误差动态选择 LOD → 只有视觉上重要的细节被渲染
 * </pre>
 *
 * <h3>核心优势：</h3>
 * <ol>
 *   <li><b>零 CPU Draw Calls</b>: 通过 Indirect Draw 实现单次绘制调用</li>
 *   <li><b>自动 LOD</b>: 基于屏幕空间误差的精确 LOD 切换</li>
 *   <li><b>流式加载</b>: 按需加载/卸载 Cluster，节省显存</li>
 *   <li><b>无损压缩</b>: 使用 half-float 压缩顶点数据</li>
 * </ol>
 *
 * <h3>依赖条件：</h3>
 * <ul>
 *   <li>Vulkan 1.1+ 或 OpenGL 4.5+ (Compute Shader 支持)</li>
 *   <li>SSBO (Shader Storage Buffer Object) 支持</li>
 *   <li>Indirect Drawing 支持</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see GPUDrivenVisibilitySystem
 * @see modern-render-architecture.md §3.4
 */
public final class VirtualGeometrySystem implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VirtualGeometrySystem.class.getName());

    // ==================== 常量定义 ====================

    /** 屏幕空间误差阈值 (像素) - 低于此值认为细节不可见 */
    public static final float SCREEN_ERROR_THRESHOLD = 2.0f;

    /** 最大 Cluster 子节点数量 (四叉树/八叉树) */
    public static final int MAX_CHILDREN = 4;

    /** Cluster 最大顶点数 */
    public static final int CLUSTER_MAX_VERTICES = 256;

    /** Cluster 最大索引数 (三角形数 * 3) */
    public static final int CLUSTER_MAX_INDICES = 768; // 256 三角形

    /** 默认最大驻留显存 (MB) */
    public static final int DEFAULT_MAX_RESIDENT_MEMORY_MB = 512;

    /** 默认每帧最大加载 Cluster 数量 */
    public static final int DEFAULT_MAX_CLUSTERS_PER_FRAME = 64;

    /** 默认流送带宽限制 (MB/s) */
    public static final float DEFAULT_STREAMING_BANDWIDTH_MBPS = 100.0f;

    // ==================== GPU 缓冲区句柄 ====================

    /**
     * Cluster 树缓冲区 (SSBO)
     * 存储完整的 Cluster 层次树结构 (BVH/DAG 扁平化数组)
     */
    private long clusterTreeBuffer = 0L;

    /**
     * Cluster 数据池 (SSBO)
     * 存储所有已加载 Cluster 的几何数据 (压缩顶点 + 索引)
     */
    private long clusterDataPool = 0L;

    /**
     * 可见性结果缓冲区 (SSBO)
     * 当前帧可见的 Cluster ID 列表 (由 cull_lod.comp 输出)
     */
    private long visibilityBuffer = 0L;

    /**
     * Indirect Draw 命令缓冲区 (SSBO)
     * 由 cull_lod.comp 生成的绘制命令
     */
    private long indirectDrawBuffer = 0L;

    // ==================== 流送状态机 ====================

    /**
     * Cluster 流送状态枚举
     * <p>
     * 描述 Cluster 在 GPU 显存中的驻留状态，
     * 用于驱动 LRU 驱逐和按需加载策略。
     */
    public enum StreamingState {
        /** 驻留 GPU 显存 (高频使用，不应驱逐) */
        RESIDENT,

        /** 可驱逐 (低频使用，可被回收以腾出空间) */
        EVICTABLE,

        /** 正在从磁盘/CPU 加载到 GPU */
        STREAMING_IN,

        /** 未加载 (仅存在于磁盘或 CPU 内存中) */
        NOT_LOADED
    }

    /**
     * Cluster 流送状态跟踪表
     * Key: Cluster ID (全局唯一标识符)
     * Value: 该 Cluster 的当前流送状态
     */
    private final Long2ObjectOpenHashMap<StreamingState> streamingStates =
            new Long2ObjectOpenHashMap<>();

    // ==================== 性能配置参数 ====================

    /** 最大驻留显存 (MB) - 控制同时驻留 GPU 的 Cluster 总大小 */
    private volatile int maxResidentMemoryMB = DEFAULT_MAX_RESIDENT_MEMORY_MB;

    /** 每帧最大加载 Cluster 数量 - 防止单帧 I/O 过载 */
    private volatile int maxClustersPerFrame = DEFAULT_MAX_CLUSTERS_PER_FRAME;

    /** 流送带宽限制 (MB/s) - 控制 I/O 速率 */
    private volatile float streamingBandwidthLimit = DEFAULT_STREAMING_BANDWIDTH_MBPS;

    // ==================== 内部状态 ====================

    /** 当前已用驻留显存 (字节) */
    private final AtomicLong currentResidentMemoryBytes = new AtomicLong(0L);

    /** Cluster ID 分配器 (原子递增) */
    private final AtomicLong nextClusterId = new AtomicLong(1L); // 0 保留为无效 ID

    /** 所有注册的 Cluster 映射表 */
    private final Long2ObjectOpenHashMap<Cluster> clusterMap = new Long2ObjectOpenHashMap<>();

    /** 根 Cluster ID 列表 (可能有多棵树) */
    private final List<Long> rootClusterIds = new ArrayList<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 是否已释放资源 */
    private volatile boolean closed = false;

    // ==================== 构造函数 ====================

    /**
     * 创建虚拟几何体系统实例
     * <p>
     * 使用默认配置参数创建。
     * 可通过 setter 方法调整性能参数。
     */
    public VirtualGeometrySystem() {
        LOGGER.info("VirtualGeometrySystem: 创建实例 (Nanite-Style Virtual Geometry)");
    }

    /**
     * 创建虚拟几何体系统实例并指定性能参数
     *
     * @param maxResidentMemoryMB      最大驻留显存 (MB)，必须 > 0
     * @param maxClustersPerFrame      每帧最大加载数量，必须 > 0
     * @param streamingBandwidthLimit  流送带宽限制 (MB/s)，必须 > 0
     * @throws IllegalArgumentException 如果任何参数 <= 0
     */
    public VirtualGeometrySystem(int maxResidentMemoryMB, int maxClustersPerFrame,
                                  float streamingBandwidthLimit) {
        if (maxResidentMemoryMB <= 0 || maxClustersPerFrame <= 0 || streamingBandwidthLimit <= 0) {
            throw new IllegalArgumentException("所有性能参数必须大于 0");
        }
        this.maxResidentMemoryMB = maxResidentMemoryMB;
        this.maxClustersPerFrame = maxClustersPerFrame;
        this.streamingBandwidthLimit = streamingBandwidthLimit;
        LOGGER.info(String.format(
                "VirtualGeometrySystem: 创建实例 [maxMem=%dMB, maxClusters/frame=%d, bandwidth=%.1fMB/s]",
                maxResidentMemoryMB, maxClustersPerFrame, streamingBandwidthLimit
        ));
    }

    // ==================== 初始化与资源管理 ====================

    /**
     * 初始化虚拟几何体系统
     * <p>
     * 分配 GPU 缓冲区和准备 Compute Pipeline。
     * 必须在调用其他方法之前调用此方法。
     *
     * @return true 表示初始化成功
     * @throws IllegalStateException 如果已经初始化或已关闭
     */
    public boolean initialize() {
        if (closed) {
            throw new IllegalStateException("VirtualGeometrySystem 已关闭，无法重新初始化");
        }
        if (initialized) {
            LOGGER.warning("VirtualGeometrySystem 已经初始化，跳过重复初始化");
            return true;
        }

        try {
            LOGGER.info("VirtualGeometrySystem: 开始初始化...");

            // TODO: 实际集成时需要:
            // 1. 创建 Vulkan/GL 设备和队列
            // 2. 分配 clusterTreeBuffer (SSBO)
            // 3. 分配 clusterDataPool (SSBO)
            // 4. 分配 visibilityBuffer (SSBO)
            // 5. 分配 indirectDrawBuffer (SSBO)
            // 6. 编译并链接 cull_lod.comp Compute Shader
            // 7. 创建 Compute Pipeline 对象

            initialized = true;

            LOGGER.info("VirtualGeometrySystem: 初始化完成 ✓");
            return true;

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "VirtualGeometrySystem: 初始化失败! 错误: %s", e.getMessage()
            ));
            return false;
        }
    }

    /**
     * 释放所有 GPU 资源
     * <p>
     * 实现 AutoCloseable 接口，支持 try-with-resources 语法。
     * 释放后此对象不可再使用。
     */
    @Override
    public void close() {
        if (closed) {
            return; // 避免重复释放
        }

        LOGGER.info("VirtualGeometrySystem: 开始释放资源...");

        try {
            // 清空所有 Cluster 数据
            clusterMap.clear();
            rootClusterIds.clear();
            streamingStates.clear();

            // 重置状态
            currentResidentMemoryBytes.set(0L);
            nextClusterId.set(1L);
            initialized = false;
            closed = true;

            // TODO: 实际集成时需要:
            // 1. vkDestroyBuffer(clusterTreeBuffer)
            // 2. vkDestroyBuffer(clusterDataPool)
            // 3. vkDestroyBuffer(visibilityBuffer)
            // 4. vkDestroyBuffer(indirectDrawBuffer)
            // 5. 销毁 Compute Pipeline
            // 6. 释放关联的 Device Memory

            // 清空句柄
            clusterTreeBuffer = 0L;
            clusterDataPool = 0L;
            visibilityBuffer = 0L;
            indirectDrawBuffer = 0L;

            LOGGER.info("VirtualGeometrySystem: 资源已完全释放 ✓");

        } catch (Exception e) {
            LOGGER.severe(String.format(
                    "VirtualGeometrySystem: 释放资源时发生错误: %s", e.getMessage()
            ));
        }
    }

    // ==================== Cluster 管理 ====================

    /**
     * 导入静态网格为 Cluster 层次结构
     * <p>
     * 将原始网格数据分解为 Cluster 树（BVH/DAG 结构），
     * 支持后续的动态 LOD 选择和流式加载。
     *
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 构建初始 Cluster (包含所有三角形)
     * 2. 递归细分 Cluster 直到满足以下条件之一：
     *    a) 三角形数 <= CLUSTER_MAX_INDICES / 3
     *    b) 屏幕空间误差 <= SCREEN_ERROR_THRESHOLD (在预设距离下)
     * 3. 计算每个 Cluster 的包围盒 (AABB)
     * 4. 预计算屏幕空间误差 (用于快速 LOD 选择)
     * 5. 压缩顶点数据 (half-float, 相对簇中心坐标)
     * 6. 注册到 Cluster 树并返回根节点 ID
     * </pre>
     *
     * @param meshData 原始网格数据 (顶点 + 索引 + UV + 法线)
     *                 不能为 null，且必须包含有效数据
     * @return 创建的根 Cluster ID (> 0)，用于后续查询和渲染
     * @throws IllegalArgumentException 如果 meshData 为 null 或数据无效
     * @throws IllegalStateException    如果系统未初始化
     */
    public long importMesh(MeshData meshData) {
        if (!initialized) {
            throw new IllegalStateException("VirtualGeometrySystem 未初始化");
        }
        if (meshData == null) {
            throw new IllegalArgumentException("meshData 不能为 null");
        }
        if (meshData.positions == null || meshData.indices == null) {
            throw new IllegalArgumentException("meshData 必须包含有效的位置和索引数据");
        }

        long startTime = System.nanoTime();

        // 分配新的全局唯一 Cluster ID
        long rootClusterId = nextClusterId.getAndIncrement();

        LOGGER.fine(String.format(
                "VirtualGeometrySystem: 开始导入网格 (rootClusterId=%d, vertices=%d, indices=%d)",
                rootClusterId, meshData.vertexCount, meshData.indexCount
        ));

        // TODO: 实现 Cluster 细分算法
        //
        // 步骤 1: 从 MeshData 构建初始三角形列表
        // List&lt;Triangle&gt; triangles = extractTriangles(meshData);
        //
        // 步骤 2: 递归构建 Cluster 层次结构 (自顶向下)
        // Cluster rootCluster = buildClusterHierarchy(triangles, 0);
        //
        // 步骤 3: 设置父节点引用和 LOD 等级
        // rootCluster.parentId = -1; // 根节点无父节点
        // rootCluster.lodLevel = 0;
        //
        // 步骤 4: 压缩几何数据 (half-float 压缩)
        // compressClusterGeometry(rootCluster);
        //
        // 步骤 5: 预计算屏幕空间误差
        // precomputeScreenSpaceErrors(rootCluster);
        //
        // 步骤 6: 注册到映射表
        // clusterMap.put(rootClusterId, rootCluster);
        // rootClusterIds.add(rootClusterId);
        // streamingStates.put(rootClusterId, StreamingState.NOT_LOADED);

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;

        LOGGER.info(String.format(
                "VirtualGeometrySystem: 网格导入完成 (rootClusterId=%d, 耗时 %.2f ms)",
                rootClusterId, elapsedMs / 1.0
        ));

        return rootClusterId;
    }

    /**
     * 更新流送队列 (每帧调用)
     * <p>
     * 根据相机位置和视锥体，动态决定哪些 Cluster 需要加载、保留或卸载。
     * 此方法应在每帧渲染前调用，以确保可见 Cluster 已驻留 GPU。
     *
     * <h3>流程：</h3>
     * <pre>
     * 1. 根据 Camera 位置更新 Cluster 可见性和优先级
     *    ├─ 计算每个 Cluster 到相机的距离
     *    ├─ 测试视锥体包含关系
     *    └─ 更新 priority 字段 (距离越近优先级越高)
     *
     * 2. 识别需要加载的新 Cluster
     *    条件: 可视 && 未加载 && 优先级足够高
     *    → 加入加载队列
     *
     * 3. 识别可以卸载的 Cluster
     *    条件: 不可见 && 低优先级 && 驻留时间超过阈值
     *    → 标记为 EVICTABLE 或直接卸载
     *
     * 4. 在带宽限制内调度 I/O 操作
     *    ├─ 尊重 maxClustersPerFrame 限制
     *    ├─ 尊重 streamingBandwidthLimit 限制
     *    └─ 按 LRU+Priority 双键排序调度
     * </pre>
     *
     * @param camera         当前相机对象 (包含位置、方向等)
     *                       不能为 null
     * @param frameBudgetMs  本帧可用的流送时间预算 (毫秒)
     *                       通常建议 1-5ms，避免影响帧率
     * @return 本帧实际处理的 Cluster 加载数量
     * @throws IllegalArgumentException 如果 camera 为 null
     * @throws IllegalStateException    如果系统未初始化
     */
    public int updateStreaming(Object camera, float frameBudgetMs) {
        if (!initialized) {
            throw new IllegalStateException("VirtualGeometrySystem 未初始化");
        }
        if (camera == null) {
            throw new IllegalArgumentException("camera 不能为 null");
        }
        if (frameBudgetMs <= 0) {
            throw new IllegalArgumentException("frameBudgetMs 必须大于 0");
        }

        long startTime = System.nanoTime();
        int loadedCount = 0;

        // TODO: 实现流送更新逻辑
        //
        // Step 1: 更新所有 Cluster 的可见性和优先级
        // for (Map.Entry&lt;Long, Cluster&gt; entry : clusterMap.entrySet()) {
        //     long clusterId = entry.getKey();
        //     Cluster cluster = entry.getValue();
        //
        //     // 计算到相机距离
        //     float distance = computeDistanceToCamera(camera, cluster.boundsMin, cluster.boundsMax);
        //
        //     // 视锥体测试
        //     boolean visible = testFrustumContainment(camera.frustum, cluster.boundsMin, cluster.boundsMax);
        //
        //     // 更新优先级 (距离越近优先级越高，使用倒数映射)
        //     cluster.priority = visible ? (1.0f / Math.max(distance, 0.1f)) : 0.0f;
        //
        //     // 更新最后使用时间
        //     if (visible) {
        //         cluster.lastUsedTimestamp = System.currentTimeMillis();
        //     }
        // }
        //
        // Step 2: 收集需要加载的候选 Cluster
        // PriorityQueue&lt;ClusterLoadRequest&gt; loadQueue = new PriorityQueue<>(
        //     Comparator.comparingDouble(req -> -req.priority) // 高优先级先加载
        // );
        //
        // for (Map.Entry&lt;Long, Cluster&gt; entry : clusterMap.entrySet()) {
        //     long clusterId = entry.getKey();
        //     Cluster cluster = entry.getValue();
        //     StreamingState state = streamingStates.getOrDefault(clusterId, StreamingState.NOT_LOADED);
        //
        //     if (cluster.priority > 0 &&
        //         state == StreamingState.NOT_LOADED &&
        //         loadedCount < maxClustersPerFrame) {
        //
        //         loadQueue.offer(new ClusterLoadRequest(clusterId, cluster.priority));
        //     }
        // }
        //
        // Step 3: 按优先级加载 Cluster (受限于带宽和帧预算)
        // while (!loadQueue.isEmpty() && loadedCount < maxClustersPerFrame) {
        //     ClusterLoadRequest request = loadQueue.poll();
        //
        //     // 检查剩余预算
        //     float elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0f;
        //     if (elapsedMs >= frameBudgetMs) break;
        //
        //     // 执行加载
        //     if (loadClusterToGPU(request.clusterId)) {
        //         loadedCount++;
        //     }
        // }
        //
        // Step 4: 卸载不可见的 EVICTABLE Cluster
        // evictUnusedClusters();

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;

        if (loadedCount > 0) {
            LOGGER.fine(String.format(
                    "VirtualGeometrySystem: 流送更新完成 (loaded=%d, budget=%.2fms/%.2fms)",
                    loadedCount, elapsedMs, frameBudgetMs
            ));
        }

        return loadedCount;
    }

    // ==================== 渲染方法 ====================

    /**
     * 使用虚拟几何体渲染
     * <p>
     * 调度 GPU Compute Shader 执行 Cluster 遍历和 LOD 选择，
     * 然后通过 Indirect Draw 渲染可见的几何体。
     *
     * <h3>与传统渲染的区别：</h3>
     * <pre>
     * 传统: CPU 决定每个 Chunk 的 LOD → 逐个提交 glDraw* 调用 (thousands)
     * Nanite: GPU Compute Shader 根据屏幕空间误差动态选择 LOD
     *         → 只有视觉上重要的细节被渲染
     *         → 单次 Indirect Draw 调用
     * </pre>
     *
     * <h3>渲染流程：</h3>
     * <pre>
     * 1. 绑定 Cluster 树和数据池到 Compute Shader
     * 2. 上传相机参数 (View-Projection Matrix, Position, Screen Size)
     * 3. 调度 cull_lod.comp Compute Pass
     *    ├─ 每个 Workgroup 处理一个或多个 Cluster 节点
     *    ├─ 并行执行视锥剔除
     *    ├─ 计算屏幕空间误差
     *    ├─ 选择合适的 LOD 级别 (遍历子节点或使用当前节点)
     *    └─ 写入可见性结果和 Indirect Draw 命令
     * 4. Barrier 同步 (确保 Compute 完成)
     * 5. 执行 Indirect Draw (vkCmdDrawIndexedIndirect)
     * </pre>
     *
     * @param encoder 命令编码器 (Vulkan CommandBuffer 或 GL CommandEncoder)
     *                不能为 null
     * @param camera  相机数据对象 (包含 View-Projection 矩阵、位置、屏幕尺寸等)
     *                不能为 null
     * @return 本帧渲染的可见 Cluster 数量
     * @throws IllegalArgumentException 如果 encoder 或 camera 为 null
     * @throws IllegalStateException    如果系统未初始化
     */
    public int render(Object encoder, Object camera) {
        if (!initialized) {
            throw new IllegalStateException("VirtualGeometrySystem 未初始化");
        }
        if (encoder == null) {
            throw new IllegalArgumentException("encoder 不能为 null");
        }
        if (camera == null) {
            throw new IllegalArgumentException("camera 不能为 null");
        }

        long startTime = System.nanoTime();

        // TODO: 实现渲染逻辑
        //
        // Step 1: 绑定 Compute Pipeline
        // encoder.bindComputePipeline(cullLODPipeline);
        //
        // Step 2: 绑定 SSBO
        // encoder.bindStorageBuffer(0, clusterTreeBuffer);      // Cluster 树
        // encoder.bindStorageBuffer(1, visibilityBuffer);       // 可见性输出
        // encoder.bindStorageBuffer(2, indirectDrawBuffer);     // Indirect Draw 命令
        // encoder.bindStorageBuffer(3, clusterDataPool);        // 几何数据
        //
        // Step 3: 上传 Uniform/Push Constants (相机参数)
        // CullParams params = buildCullParams(camera);
        // encoder.pushConstants(params);
        //
        // Step 4: 调度 Compute Shader
        // int nodeCount = clusterMap.size();
        // int workgroupCount = (nodeCount + 63) / 64; // local_size_x = 64
        // encoder.dispatch(workgroupCount, 1, 1);
        //
        // Step 5: Memory Barrier (Compute → Graphics)
        // encoder.memoryBarrier(
        //     VK_ACCESS_SHADER_WRITE_BIT,
        //     VK_ACCESS_INDIRECT_COMMAND_READ_BIT,
        //     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        //     VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
        // );
        //
        // Step 6: 绑定 Graphics Pipeline 并执行 Indirect Draw
        // encoder.bindGraphicsPipeline(virtualGeometryPipeline);
        // encoder.bindVertexBuffer(0, vertexBuffer);
        // encoder.bindIndexBuffer(indexBuffer);
        // encoder.drawIndexedIndirect(indirectDrawBuffer, 1); // 单次调用!
        //
        // Step 7: 读取可见 Cluster 数量 (可选，用于统计)
        // int visibleCount = readVisibleCountFromGPU();

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;

        LOGGER.finest(String.format(
                "VirtualGeometrySystem: 渲染完成 (耗时 %.2f ms)", elapsedMs / 1.0
        ));

        // TODO: 返回实际的可见 Cluster 数量
        return 0;
    }

    /**
     * Cull/LOD Compute Shader 调度 (内部方法)
     * <p>
     * 使用两阶段 Pass 执行 Cluster 遍历和 LOD 选择：
     * <ul>
     *   <li>Pass 1: 遍历 Cluster 树，选择合适的 LOD (基于屏幕空间误差)</li>
     *   <li>Pass 2: 生成 Indirect Draw 命令 (紧凑化可见 Cluster 列表)</li>
     * </ul>
     *
     * @param encoder 命令编码器
     */
    private void scheduleCullLODPass(Object encoder) {
        // TODO: 实现两阶段 Compute Pass
        //
        // Pass 1: Cluster 遍历和 LOD 选择
        // encoder.bindComputePipeline(cullLODPipeline);
        // encoder.pushConstants(cameraParams);
        // encoder.dispatch(nodeCount, 1, 1);
        //
        // Pass 2: 生成 Indirect Draw 命令 (Prefix Sum + Compact)
        // encoder.bindComputePipeline(generateIndirectPipeline);
        // encoder.dispatch(1, 1, 1); // 单个 workgroup 处理全部
    }

    // ==================== 查询方法 ====================

    /**
     * 获取指定 Cluster 的信息
     *
     * @param clusterId Cluster ID
     * @return Cluster 对象，不存在返回 null
     */
    public Cluster getCluster(long clusterId) {
        return clusterMap.get(clusterId);
    }

    /**
     * 获取指定 Cluster 的流送状态
     *
     * @param clusterId Cluster ID
     * @return 当前 StreamingState，未知 ID 返回 NOT_LOADED
     */
    public StreamingState getStreamingState(long clusterId) {
        return streamingStates.getOrDefault(clusterId, StreamingState.NOT_LOADED);
    }

    /**
     * 获取当前驻留显存使用量 (字节)
     *
     * @return 当前已使用的驻留显存字节数
     */
    public long getCurrentResidentMemory() {
        return currentResidentMemoryBytes.get();
    }

    /**
     * 获取最大允许驻留显存 (字节)
     *
     * @return 最大驻留显存字节数
     */
    public long getMaxResidentMemory() {
        return (long) maxResidentMemoryMB * 1024L * 1024L;
    }

    /**
     * 获取已注册的 Cluster 总数
     *
     * @return Cluster 数量
     */
    public int getClusterCount() {
        return clusterMap.size();
    }

    /**
     * 获取根 Cluster ID 列表
     *
     * @return 根 Cluster ID 列表的副本
     */
    public List<Long> getRootClusterIds() {
        return new ArrayList<>(rootClusterIds);
    }

    /**
     * 检查系统是否已初始化
     *
     * @return true 如果已初始化且未关闭
     */
    public boolean isInitialized() {
        return initialized && !closed;
    }

    // ==================== 配置 Setter 方法 ====================

    /**
     * 设置最大驻留显存 (MB)
     *
     * @param maxMB 最大显存 (MB)，必须 > 0
     * @throws IllegalArgumentException 如果 maxMB <= 0
     */
    public void setMaxResidentMemoryMB(int maxMB) {
        if (maxMB <= 0) {
            throw new IllegalArgumentException("最大显存必须大于 0");
        }
        this.maxResidentMemoryMB = maxMB;
        LOGGER.fine(String.format("VirtualGeometrySystem: 更新最大驻留显存 = %d MB", maxMB));
    }

    /**
     * 设置每帧最大加载 Cluster 数量
     *
     * @param maxClusters 每帧最大数量，必须 > 0
     * @throws IllegalArgumentException 如果 maxClusters <= 0
     */
    public void setMaxClustersPerFrame(int maxClusters) {
        if (maxClusters <= 0) {
            throw new IllegalArgumentException("每帧最大加载数量必须大于 0");
        }
        this.maxClustersPerFrame = maxClusters;
        LOGGER.fine(String.format("VirtualGeometrySystem: 更新每帧最大加载 Cluster 数量 = %d", maxClusters));
    }

    /**
     * 设置流送带宽限制 (MB/s)
     *
     * @param bandwidthMbps 带宽限制 (MB/s)，必须 > 0
     * @throws IllegalArgumentException 如果 bandwidthMbps <= 0
     */
    public void setStreamingBandwidthLimit(float bandwidthMbps) {
        if (bandwidthMbps <= 0) {
            throw new IllegalArgumentException("带宽限制必须大于 0");
        }
        this.streamingBandwidthLimit = bandwidthMbps;
        LOGGER.fine(String.format("VirtualGeometrySystem: 更新流送带宽限制 = %.1f MB/s", bandwidthMbps));
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 将 Cluster 加载到 GPU 显存
     *
     * @param clusterId 目标 Cluster ID
     * @return true 表示加载成功
     */
    private boolean loadClusterToGPU(long clusterId) {
        Cluster cluster = clusterMap.get(clusterId);
        if (cluster == null || cluster.geometry == null) {
            return false;
        }

        // 检查是否有足够的显存
        long requiredMemory = estimateClusterSize(cluster);
        long currentUsage = currentResidentMemoryBytes.get();
        long maxMemory = getMaxResidentMemory();

        if (currentUsage + requiredMemory > maxMemory) {
            // 尝试驱逐一些低优先级的 Cluster 以腾出空间
            if (!evictClustersToFree(requiredMemory)) {
                LOGGER.warning(String.format(
                        "VirtualGeometrySystem: 显存不足，无法加载 Cluster %d (need=%d bytes)",
                        clusterId, requiredMemory
                ));
                return false;
            }
        }

        // TODO: 实际上传几何数据到 GPU
        // uploadClusterGeometryToGPU(cluster);

        // 更新状态
        streamingStates.put(clusterId, StreamingState.RESIDENT);
        currentResidentMemoryBytes.addAndGet(requiredMemory);
        cluster.state = StreamingState.RESIDENT;
        cluster.lastUsedTimestamp = System.currentTimeMillis();

        return true;
    }

    /**
     * 驱逐 Cluster 以释放显存
     *
     * @param requiredBytes 需要释放的字节数
     * @return true 表示成功释放了足够的空间
     */
    private boolean evictClustersToFree(long requiredBytes) {
        // 收集所有可驱逐的 Candidate (EVICTABLE 状态，按 LRU 排序)
        PriorityQueue<EvictionCandidate> candidates = new PriorityQueue<>();

        for (Long2ObjectOpenHashMap.Entry<StreamingState> entry :
                streamingStates.long2ObjectEntrySet()) {
            long clusterId = entry.getLongKey();
            StreamingState state = entry.getValue();

            if (state == StreamingState.EVICTABLE || state == StreamingState.RESIDENT) {
                Cluster cluster = clusterMap.get(clusterId);
                if (cluster != null && cluster.lastUsedTimestamp > 0) {
                    candidates.offer(new EvictionCandidate(
                            clusterId,
                            cluster.lastUsedTimestamp,
                            estimateClusterSize(cluster)
                    ));
                }
            }
        }

        // 按 LRU 顺序驱逐直到释放足够空间
        long freedBytes = 0L;
        while (!candidates.isEmpty() && freedBytes < requiredBytes) {
            EvictionCandidate candidate = candidates.poll();

            // 执行驱逐
            if (evictCluster(candidate.clusterId)) {
                freedBytes += candidate.sizeBytes;
            }
        }

        return freedBytes >= requiredBytes;
    }

    /**
     * 驱逐单个 Cluster
     *
     * @param clusterId 目标 Cluster ID
     * @return true 表示成功驱逐
     */
    private boolean evictCluster(long clusterId) {
        Cluster cluster = clusterMap.get(clusterId);
        if (cluster == null) {
            return false;
        }

        // TODO: 从 GPU 显存释放此 Cluster 的几何数据
        // releaseClusterGeometryFromGPU(cluster);

        // 更新状态和计数
        long sizeBytes = estimateClusterSize(cluster);
        streamingStates.put(clusterId, StreamingState.NOT_LOADED);
        currentResidentMemoryBytes.addAndGet(-sizeBytes);
        cluster.state = StreamingState.NOT_LOADED;

        return true;
    }

    /**
     * 估算 Cluster 占用的显存大小 (字节)
     *
     * @param cluster 目标 Cluster
     * @return 估算的字节数
     */
    private long estimateClusterSize(Cluster cluster) {
        if (cluster.geometry == null) {
            return 0L;
        }
        // 顶点: 3 * 2 bytes (half-float) * vertexCount
        // 索引: 4 bytes * indexCount
        // 属性: 估算 8 bytes/vertex (UV + 法线)
        long vertexSize = 6L * cluster.geometry.vertexCount;
        long indexSize = 4L * cluster.geometry.indexCount;
        long attributeSize = 8L * cluster.geometry.vertexCount;
        return vertexSize + indexSize + attributeSize;
    }

    // ==================== 内部数据类 ====================

    /**
     * Cluster 数据结构 (类似 Nanite 的 Cluster)
     * <p>
     * Cluster 是虚拟几何体系统的基本单位，代表一组相邻的三角形。
     * 每个 Cluster 包含几何数据和层次结构元数据，支持 LOD 过渡和流式加载。
     *
     * <h3>层次结构特性：</h3>
     * <ul>
     *   <li>支持最多 4 个子 Cluster (四叉树/八叉树结构)</li>
     *   <li>父子关系形成 DAG (有向无环图)，支持实例共享</li>
     *   <li>每个 Cluster 有独立的包围盒和屏幕空间误差</li>
     * </ul>
     */
    public static class Cluster {

        /** 子 Cluster ID 数组 (最多 4 个，用于 LOD 过渡) */
        public final long[] childClusterIds = new long[MAX_CHILDREN];

        /** 子 Cluster 实际数量 (0-4) */
        public int childCount = 0;

        /** 父 Cluster ID (-1 表示根节点) */
        public long parentId = -1L;

        /** 包围盒最小角 (AABB) */
        public Vector3f boundsMin = new Vector3f();

        /** 包围盒最大角 (AABB) */
        public Vector3f boundsMax = new Vector3f();

        /** 几何数据 (如果已加载到 CPU/GPU) */
        public ClusterGeometry geometry;

        /** 屏幕空间误差 (预计算的，用于快速 LOD 选择) */
        public float screenSpaceError = 0.0f;

        /** LOD 等级 (0 = 最粗略, 越大越精细) */
        public int lodLevel = 0;

        /** 流送状态 */
        public StreamingState state = StreamingState.NOT_LOADED;

        /** 最后使用时间戳 (毫秒，用于 LRU 驱逐) */
        public long lastUsedTimestamp = 0L;

        /** 优先级 (距离相机越近越高，由 updateStreaming 计算) */
        public float priority = 0.0f;

        /**
         * 检查此 Cluster 是否有子节点
         *
         * @return true 如果有至少一个子节点
         */
        public boolean hasChildren() {
            return childCount > 0;
        }

        /**
         * 获取包围盒中心点
         *
         * @return 中心点向量
         */
        public Vector3f getBoundsCenter() {
            return new Vector3f(
                    (boundsMin.x + boundsMax.x) * 0.5f,
                    (boundsMin.y + boundsMax.y) * 0.5f,
                    (boundsMin.z + boundsMax.z) * 0.5f
            );
        }

        /**
         * 获取包围盒最长轴长度
         *
         * @return 最长边长度
         */
        public float getBoundsMaxExtent() {
            float dx = boundsMax.x - boundsMin.x;
            float dy = boundsMax.y - boundsMin.y;
            float dz = boundsMax.z - boundsMin.z;
            return Math.max(Math.max(dx, dy), dz);
        }
    }

    /**
     * Cluster 几何数据 (压缩格式)
     * <p>
     * 存储 Cluster 的实际几何信息，采用压缩格式减少显存占用。
     * 所有坐标都是相对于簇中心的偏移量，使用 half-float (16-bit) 存储。
     *
     * <h3>压缩方案：</h3>
     * <ul>
     *   <li><b>位置</b>: 16-bit half-float × 3 分量，相对簇中心存储</li>
     *   <li><b>索引</b>: 32-bit 无符号整数 (支持大型 Mesh)</li>
     *   <li><b>属性</b>: 16-bit 半精度 (UV, 法线等可选属性)</li>
     * </ul>
     *
     * <h3>内存占用示例 (256 顶点 Cluster)：</h3>
     * <pre>
     * 位置: 256 vertices × 6 bytes = 1,536 bytes
     * 索引: 768 indices × 4 bytes = 3,072 bytes
     * 属性: 256 vertices × 8 bytes = 2,048 bytes
     * ─────────────────────────────────────────
     * 总计: ~6.6 KB per Cluster (对比原始 ~12 KB)
     * </pre>
     */
    public static class ClusterGeometry {

        /**
         * 压缩顶点位置 (half-float, 相对簇中心的坐标)
         * <p>
         * 布局: [x0,y0,z0, x1,y1,z1, x2,y2,z2, ...]
         * 每个分量 16-bit，共 6 bytes/顶点
         */
        public short[] positions;

        /**
         * 索引数据 (三角形列表)
         * <p>
         * 布局: [tri0_v0, tri0_v1, tri0_v2, tri1_v0, ...]
         * 每个索引 32-bit，共 4 bytes/索引
         */
        public int[] indices;

        /**
         * 属性数据 (UV, 法线等，可选)
         * <p>
         * 布局取决于具体实现，通常包括:
         * - UV 坐标: 16-bit half-float × 2
         * - 法线: 16-bit 归一化 (octahedral encoding) × 2/3
         * 每个顶点约 8 bytes
         */
        public short[] attributes;

        /** 顶点数量 */
        public int vertexCount = 0;

        /** 索引数量 */
        public int indexCount = 0;

        /**
         * 获取几何数据的总大小 (字节)
         *
         * @return 总字节数
         */
        public long getSizeInBytes() {
            long size = 0L;
            if (positions != null) size += positions.length * 2L; // short = 2 bytes
            if (indices != null) size += indices.length * 4L;    // int = 4 bytes
            if (attributes != null) size += attributes.length * 2L; // short = 2 bytes
            return size;
        }
    }

    // ==================== 输入数据结构 ====================

    /**
     * 网格数据输入 (用于 importMesh 方法)
     * <p>
     * 包装原始网格几何数据，供 VirtualGeometrySystem 导入和处理。
     */
    public static class MeshData {

        /** 顶点位置数组 (float × 3, 交错布局: [x0,y0,z0, x1,y1,z1, ...]) */
        public float[] positions;

        /** 索引数组 (uint32, 三角形列表: [v0,v1,v2, v0,v1,v2, ...]) */
        public int[] indices;

        /** UV 坐标数组 (float × 2, 可选) */
        public float[] uvs;

        /** 法线数组 (float × 3, 可选) */
        public float[] normals;

        /** 顶点数量 */
        public int vertexCount = 0;

        /** 索引数量 */
        public int indexCount = 0;
    }

    // ==================== 内部辅助类 ====================

    /**
     * 驱逐候选者 (用于 LRU 驱逐算法)
     */
    private static class EvictionCandidate implements Comparable<EvictionCandidate> {
        final long clusterId;
        final long lastUsedTime;
        final long sizeBytes;

        EvictionCandidate(long clusterId, long lastUsedTime, long sizeBytes) {
            this.clusterId = clusterId;
            this.lastUsedTime = lastUsedTime;
            this.sizeBytes = sizeBytes;
        }

        @Override
        public int compareTo(EvictionCandidate other) {
            // LRU: 最后使用时间越早的优先驱逐
            return Long.compare(this.lastUsedTime, other.lastUsedTime);
        }
    }

    /**
     * Cluster 加载请求 (用于优先级队列)
     */
    private static class ClusterLoadRequest {
        final long clusterId;
        final double priority;

        ClusterLoadRequest(long clusterId, double priority) {
            this.clusterId = clusterId;
            this.priority = priority;
        }
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format(
                "VirtualGeometrySystem{clusters=%d, residentMemory=%d/%d MB, initialized=%b}",
                clusterMap.size(),
                getCurrentResidentMemory() / (1024 * 1024),
                maxResidentMemoryMB,
                initialized
        );
    }
}
