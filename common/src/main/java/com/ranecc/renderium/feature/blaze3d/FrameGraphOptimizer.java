// Renderium - Blaze3D 优化模块
// 帧图 (FrameGraph) 优化器 - 整合 Pipeline Cache / Descriptor Set 复用 + Arena 内存管理
// 实现 DAG 依赖图构建、拓扑排序、资源生命周期分析、内存别名系统

package com.ranecc.renderium.feature.blaze3d;

import com.ranecc.renderium.domain.model.config.FrameGraphConfig;

import com.ranecc.renderium.domain.model.config.RenderiumConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 帧图优化器 🎯
 * <p>
 * 基于 `compatibility-mode-optimization.md` 中的 Pipeline Cache 和 Descriptor Set 复用策略，
 * 结合 `vulkan-memory-arena-guide.md` 中的 Arena 内存管理思想，
 * 实现高性能的 FrameGraph 资源管理。
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  1. Pipeline Cache 优化                                     │
 * │     - 配置哈希匹配，避免重复创建管线                        │
 * │     - LRU 淘汰策略，控制缓存大小                            │
 * ├─────────────────────────────────────────────────────────────┤
 * │  2. Descriptor Set 复用                                     │
 * │     - 预分配 Descriptor Pool                                │
 * │     - 组合键匹配，避免重复分配                              │
 * │     - 每帧重置，零碎片                                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │  3. Pass 合并优化                                           │
 * │     - 相邻 Pass 的资源依赖分析                              │
 * │     - 自动合并兼容的 Pass                                   │
 * ├─────────────────────────────────────────────────────────────┤
 * │  4. 资源生命周期管理（基于 Arena）                           │
 * │     - 渲染目标复用                                         │
 * │     - 帧延迟释放队列                                        │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计参考：</h3>
 * <ul>
 *   <li>兼容模式 Vulkan 优化指南 (compatibility-mode-optimization.md §2.2, §2.3)</li>
 *   <li>Vulkan Memory Arena Guide (vulkan-memory-arena-guide.md)</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class FrameGraphOptimizer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(FrameGraphOptimizer.class.getName());

    // ==================== 配置常量 ====================

    /** 默认最大缓存 Pipeline 数量 */
    public static final int MAX_CACHED_PIPELINES = 64;

    /** 默认最大 Descriptor Set 数量 */
    public static final int MAX_DESCRIPTOR_SETS = 32;

    /** 缓存淘汰帧阈值（5秒 @ 60fps） */
    public static final int CACHE_EVICTION_FRAME_THRESHOLD = 300;

    /** 默认最大并行 Pass 数量 */
    public static final int DEFAULT_MAX_PARALLEL_PASSES = 4;

    // ==================== 配置引用 ====================

    private final FrameGraphConfig config;

    // ==================== 状态字段 ====================

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== Pipeline Cache 系统 ====================

    /**
     * Pipeline 缓存条目
     * <p>存储缓存的 Pipeline 及其关联元数据。
     */
    private static class CachedPipeline {
        /** Vulkan Pipeline 句柄 */
        final long pipeline;

        /** 配置哈希值（用于快速匹配） */
        final long configHash;

        /** 最后使用帧号（用于 LRU 淘汰） */
        final AtomicLong lastUsedFrame;

        /** 引用计数（用于追踪复用情况） */
        final AtomicInteger referenceCount;

        CachedPipeline(long pipeline, long configHash, long frame) {
            this.pipeline = pipeline;
            this.configHash = configHash;
            this.lastUsedFrame = new AtomicLong(frame);
            this.referenceCount = new AtomicInteger(0);
        }
    }

    /** Pipeline 缓存表 (configHash → CachedPipeline) */
    private final ConcurrentHashMap<Long, CachedPipeline> pipelineCache = new ConcurrentHashMap<>();

    // ==================== Descriptor Set 复用系统 ====================

    /**
     * Descriptor Set 缓存条目
     * <p>存储预分配的 Descriptor Set 及其组合键。
     */
    private static class CachedDescriptorSet {
        /** Vulkan Descriptor Set 句柄 */
        final long descriptorSet;

        /** 组合键：textureView ^ sampler ^ uniformBuffer */
        final long compositeKey;

        /** 分配帧号（用于调试） */
        long allocatedFrame;

        CachedDescriptorSet(long descriptorSet, long compositeKey, long frame) {
            this.descriptorSet = descriptorSet;
            this.compositeKey = compositeKey;
            this.allocatedFrame = frame;
        }
    }

    /** Descriptor Set 缓存表 (compositeKey → CachedDescriptorSet) */
    private final ConcurrentHashMap<Long, CachedDescriptorSet> descriptorSetCache = new ConcurrentHashMap<>();

    /** Descriptor Pool 句柄（实际实现时使用） */
    private volatile long descriptorPool = 0;

    // ==================== 统计字段 ====================

    private final AtomicLong pipelineCacheHits = new AtomicLong(0);
    private final AtomicLong pipelineCacheMisses = new AtomicLong(0);
    private final AtomicLong totalCreatedPipelines = new AtomicLong(0);
    private final AtomicLong descriptorSetHits = new AtomicLong(0);
    private final AtomicLong descriptorSetMisses = new AtomicLong(0);
    private final AtomicLong totalAllocatedDescriptorSets = new AtomicLong(0);
    private final AtomicInteger currentFrame = new AtomicInteger(0);

    // ==================== DAG 依赖图系统 ====================

    /**
     * 渲染 Pass 节点
     * <p>
     * 表示帧图中的一个渲染通道，包含其资源依赖关系和执行元数据。
     * 用于构建 DAG（有向无环图）进行依赖分析和拓扑排序。
     *
     * <h3>字段说明：</h3>
     * <ul>
     *   <li>passId: 唯一标识符，由 RenderPassMixin 分配</li>
     *   <li>passName: 人类可读名称，用于调试和日志</li>
     *   <li>pipelineHash: 当前使用的 Pipeline 配置哈希</li>
     *   <li>inputResources: 此 Pass 读取的资源集合（纹理、Buffer 等）</li>
     *   <li>outputResources: 此 Pass 写入的资源集合（渲染目标等）</li>
     *   <li>dependencies: 此 Pass 依赖的其他 Pass ID 集合</li>
     *   <li>dependents: 依赖此 Pass 的其他 Pass ID 集合</li>
     * </ul>
     */
    public static class RenderPassNode {
        /** 唯一标识符（由 RenderPassMixin 分配） */
        final long passId;

        /** 人类可读的 Pass 名称 */
        final String passName;

        /** 当前绑定的 Pipeline 配置哈希 */
        volatile long pipelineHash;

        /** 输入资源集合：此 Pass 读取的资源（只读依赖） */
        final Set<Long> inputResources;

        /** 输出资源集合：此 Pass 写入的资源（写依赖） */
        final Set<Long> outputResources;

        /** 依赖的 Pass ID 集合（必须在当前 Pass 之前执行） */
        final Set<Long> dependencies;

        /** 被依赖的 Pass ID 集合（必须在当前 Pass 之后执行） */
        final Set<Long> dependents;

        /** 拓扑排序后的执行顺序索引（-1 表示未分配） */
        int topologyIndex;

        /** Pass 类型分类（用于状态切换优化） */
        PassType passType;

        /**
         * Pass 类型枚举
         * <p>用于状态切换优化：相同类型的 Pass 相邻时可减少切换开销
         */
        enum PassType {
            /** 几何渲染（不透明物体） */
            GEOMETRY_OPAQUE,
            /** 几何渲染（透明/半透明物体） */
            GEOMETRY_TRANSLUCENT,
            /** 后处理效果 */
            POST_PROCESS,
            /** 计算着色器 Pass */
            COMPUTE,
            /** 复制/Blit 操作 */
            COPY,
            /** 其他未分类 */
            UNKNOWN
        }

        /**
         * 创建渲染 Pass 节点
         *
         * @param passId      唯一标识符
         * @param passName    人类可读名称
         * @param pipelineHash 初始 Pipeline 哈希
         */
        RenderPassNode(long passId, String passName, long pipelineHash) {
            this.passId = passId;
            this.passName = passName;
            this.pipelineHash = pipelineHash;
            this.inputResources = ConcurrentHashMap.newKeySet();
            this.outputResources = ConcurrentHashMap.newKeySet();
            this.dependencies = ConcurrentHashMap.newKeySet();
            this.dependents = ConcurrentHashMap.newKeySet();
            this.topologyIndex = -1;
            this.passType = PassType.UNKNOWN;
        }

        /**
         * 添加输入资源（只读依赖）
         *
         * @param resourceId 资源句柄或唯一标识
         */
        void addInputResource(long resourceId) {
            inputResources.add(resourceId);
        }

        /**
         * 添加输出资源（写依赖）
         *
         * @param resourceId 资源句柄或唯一标识
         */
        void addOutputResource(long resourceId) {
            outputResources.add(resourceId);
        }

        /**
         * 添加对另一个 Pass 的依赖
         *
         * @param dependentPassId 被依赖的 Pass ID
         */
        void addDependency(long dependentPassId) {
            dependencies.add(dependentPassId);
        }

        /**
         * 获取此节点的总度数（入度 + 出度）
         *
         * @return 总边数
         */
        int getTotalDegree() {
            return dependencies.size() + dependents.size();
        }
    }

    /**
     * 资源生命周期条目
     * <p>
     * 追踪一个资源在帧图中的使用区间 [firstUse, lastUse]，
     * 用于内存别名分析：生命周期不重叠的资源可以共享同一块显存。
     */
    private static class ResourceLifetime {
        /** 资源唯一标识符 */
        final long resourceId;

        /** 资源格式（如 VK_FORMAT_R8G8B8A8_UNORM） */
        int format;

        /** 资源大小（字节） */
        long sizeBytes;

        /** 首次使用此资源的 Pass 拓扑序号 */
        int firstUseIndex;

        /** 最后使用此资源的 Pass 拓扑序号 */
        int lastUseIndex;

        /** 是否已被别名分配 */
        boolean aliased;

        /** 别名分配到的内存块 ID（-1 表示未分配） */
        long aliasMemoryBlockId;

        ResourceLifetime(long resourceId, int format, long sizeBytes) {
            this.resourceId = resourceId;
            this.format = format;
            this.sizeBytes = sizeBytes;
            this.firstUseIndex = Integer.MAX_VALUE;
            this.lastUseIndex = -1;
            this.aliased = false;
            this.aliasMemoryBlockId = -1;
        }

        /**
         * 检查两个资源的生命周期是否重叠
         *
         * @param other 另一个资源生命周期
         * @return true 如果生命周期有重叠（不能共享内存）
         */
        boolean overlaps(ResourceLifetime other) {
            return !(this.lastUseIndex < other.firstUseIndex || 
                     this.firstUseIndex > other.lastUseIndex);
        }
    }

    /**
     * Pass 级别性能统计
     * <p>
     * 收集每个 Pass 执行期间的优化指标，
     * 用于生成详细的性能报告和优化建议。
     */
    private static class PassPerformanceStats {
        /** 关联的 Pass ID */
        final long passId;

        /** Pipeline 切换次数 */
        final AtomicInteger pipelineSwitchCount = new AtomicInteger(0);

        /** 纹理绑定次数 */
        final AtomicInteger textureBindCount = new AtomicInteger(0);

        /** Uniform 更新次数 */
        final AtomicInteger uniformUpdateCount = new AtomicInteger(0);

        /** 冗余 Uniform 更新次数（检测到的重复更新） */
        final AtomicInteger redundantUniformUpdates = new AtomicInteger(0);

        /** 当前使用的 Pipeline 句柄 */
        volatile long currentPipeline = 0;

        /** 上一次绑定的纹理组合键 */
        volatile long lastTextureCompositeKey = 0;

        /** 上一次更新的 Uniform 快照（位置 → 数据哈希） */
        final Map<Integer, Long> lastUniformSnapshot = new ConcurrentHashMap<>();

        /** Pass 开始时间（纳秒） */
        volatile long passStartTimeNanos = 0;

        /** Pass 总执行时间（纳秒，累加） */
        final AtomicLong totalExecutionTimeNanos = new AtomicLong(0);

        /** Pass 执行次数 */
        final AtomicInteger executionCount = new AtomicInteger(0);

        PassPerformanceStats(long passId) {
            this.passId = passId;
        }
    }

    /** Pass 节点注册表 (passId → RenderPassNode) */
    private final ConcurrentHashMap<Long, RenderPassNode> passRegistry = new ConcurrentHashMap<>();

    /** 资源生命周期表 (resourceId → ResourceLifetime) */
    private final ConcurrentHashMap<Long, ResourceLifetime> resourceLifetimes = new ConcurrentHashMap<>();

    /** Pass 性能统计表 (passId → PassPerformanceStats) */
    private final ConcurrentHashMap<Long, PassPerformanceStats> passStatsMap = new ConcurrentHashMap<>();

    /** 拓扑排序后的 Pass 执行顺序（缓存结果） */
    private volatile List<Long> cachedTopologyOrder = Collections.unmodifiableList(new ArrayList<>());

    /** 拓扑排序脏标记：当图变更时设为 true */
    private volatile boolean topologyDirty = true;

    /** 状态切换计数器：记录每帧的总切换次数 */
    private final AtomicLong stateSwitchCount = new AtomicLong(0);

    /** 内存别名节省的字节数统计 */
    private final AtomicLong memoryAliasSavedBytes = new AtomicLong(0);

    /** Pass 合并候选对数量 */
    private final AtomicInteger mergeCandidateCount = new AtomicInteger(0);

    // ==================== 构造函数 ====================

    /**
     * 创建帧图优化器
     *
     * @param config RenderiumConfig 的帧图配置
     */
    public FrameGraphOptimizer(RenderiumConfig config) {
        this.config = config.getFrameGraphConfig();
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化帧图优化器
     * <p>
     * 初始化 Pipeline Cache 和 Descriptor Set 缓存系统。
     *
     * @return 成功返回 true
     */
    public boolean initialize() {
        if (initialized) return true;

        try {
            // 清空缓存（防止重复初始化）
            pipelineCache.clear();
            descriptorSetCache.clear();

            // 清空 DAG 依赖图系统
            passRegistry.clear();
            resourceLifetimes.clear();
            passStatsMap.clear();
            cachedTopologyOrder = Collections.unmodifiableList(new ArrayList<>());
            topologyDirty = true;

            // 重置统计
            pipelineCacheHits.set(0);
            pipelineCacheMisses.set(0);
            totalCreatedPipelines.set(0);
            descriptorSetHits.set(0);
            descriptorSetMisses.set(0);
            totalAllocatedDescriptorSets.set(0);
            currentFrame.set(0);
            stateSwitchCount.set(0);
            memoryAliasSavedBytes.set(0);
            mergeCandidateCount.set(0);

            this.initialized = true;

            LOGGER.info(String.format(
                    "FrameGraphOptimizer initialized" +
                    "  Max cached pipelines: %d" +
                    "  Max descriptor sets: %d" +
                    "  Pass merging: %s" +
                    "  Max parallel passes: %d" +
                    "  Async transfer: %s" +
                    "  DAG dependency analysis: ENABLED" +
                    "  Topological sorting: ENABLED" +
                    "  Resource lifetime tracking: ENABLED",
                    MAX_CACHED_PIPELINES,
                    MAX_DESCRIPTOR_SETS,
                    config.isPassMergingEnabled() ? "ON" : "OFF",
                    config.getMaxParallelPasses(),
                    config.isAsyncTransferEnabled() ? "ON" : "OFF"
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize FrameGraphOptimizer: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启用帧图优化器
     * <p>
     * 根据 `compatibility-mode-optimization.md` §2.2 和 §2.3 中的策略：
     * <ol>
     *   <li>启用 Pipeline Cache</li>
     *   <li>启用 Descriptor Set 复用</li>
     *   <li>启用 Pass 合并（如果配置允许）</li>
     * </ol>
     */
    public void enable() {
        if (!config.isPassMergingEnabled()) {
            LOGGER.warning("FrameGraphOptimizer: Pass 合并未启用，跳过");
            return;
        }

        if (!initialized) {
            if (!initialize()) {
                LOGGER.severe("FrameGraphOptimizer: 初始化失败，无法启用");
                return;
            }
        }

        enabled = true;

        LOGGER.info(String.format(
                "✓ FrameGraphOptimizer enabled" +
                "  [Pipeline Cache] Active (max %d pipelines)" +
                "  [Descriptor Reuse] Active (max %d sets)" +
                "  [Pass Merging] ON" +
                "  [Async Transfer] %s",
                MAX_CACHED_PIPELINES,
                MAX_DESCRIPTOR_SETS,
                config.isAsyncTransferEnabled() ? "ON" : "OFF"
        ));
    }

    /**
     * 禁用帧图优化器
     */
    public void disable() { enabled = false; }

    /**
     * 销毁所有缓存并释放资源
     */
    @Override
    public void close() {
        if (!initialized) return;

        // 清空所有缓存
        evictAllCachedPipelines();
        evictAllCachedDescriptorSets();

        pipelineCache.clear();
        descriptorSetCache.clear();

        // 销毁 Descriptor Pool（Vulkan 资源清理）
        // 注意：实际集成时需要调用 vkDestroyDescriptorPool
        destroyDescriptorPoolInternal();
        descriptorPool = 0;

        enabled = false;
        initialized = false;

        // 重置统计
        pipelineCacheHits.set(0);
        pipelineCacheMisses.set(0);
        totalCreatedPipelines.set(0);
        descriptorSetHits.set(0);
        descriptorSetMisses.set(0);
        totalAllocatedDescriptorSets.set(0);

        LOGGER.info("FrameGraphOptimizer disposed");
    }

    // ==================== DAG 依赖图 API：动态 Pass 管理 ====================

    /**
     * 注册一个新的渲染 Pass 到依赖图中
     * <p>
     * 由 RenderPassMixin 在 Pass 开始时调用，将 Pass 注册到帧图优化器。
     * 注册后可进行依赖分析、拓扑排序和资源生命周期追踪。
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 的唯一标识符（由 Mixin 分配）</li>
     *   <li><b>passName</b>: String - 人类可读的名称（如 "OpaqueGeometry"、"Skybox"）</li>
     *   <li><b>pipelineHash</b>: long - 此 Pass 使用的 Pipeline 配置哈希值</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>{@link RenderPassNode} - 新创建或已存在的 Pass 节点引用</li>
     * </ul>
     *
     * @param passId       唯一标识符
     * @param passName     人类可读名称
     * @param pipelineHash Pipeline 配置哈希
     * @return Pass 节点实例
     */
    public RenderPassNode registerPass(long passId, String passName, long pipelineHash) {
        if (!initialized) {
            LOGGER.warning("registerPass() called before initialization, pass=" + passName);
            return null;
        }

        // 使用 computeIfAbsent 保证线程安全的单次创建
        RenderPassNode node = passRegistry.computeIfAbsent(passId, id -> {
            LOGGER.fine(String.format("Registering new pass: id=0x%X, name=%s", id, passName));
            return new RenderPassNode(id, passName, pipelineHash);
        });

        // 更新 Pipeline 哈希（可能因状态变化而改变）
        node.pipelineHash = pipelineHash;

        // 标记拓扑排序需要重新计算
        topologyDirty = true;

        // 初始化此 Pass 的性能统计（如果不存在）
        passStatsMap.computeIfAbsent(passId, PassPerformanceStats::new);

        return node;
    }

    /**
     * 从依赖图中移除一个渲染 Pass
     * <p>
     * 当 Pass 被销毁或帧图重建时调用。
     * 会自动清理相关的依赖边和资源生命周期记录。
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - 要移除的 Pass ID</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>boolean - true 表示成功移除，false 表示 Pass 不存在</li>
     * </ul>
     *
     * @param passId 要移除的 Pass ID
     * @return 是否成功移除
     */
    public boolean removePass(long passId) {
        if (!initialized) return false;

        RenderPassNode removed = passRegistry.remove(passId);

        if (removed != null) {
            // 清理被移除节点的依赖关系
            for (Long depId : removed.dependencies) {
                RenderPassNode depNode = passRegistry.get(depId);
                if (depNode != null) {
                    depNode.dependents.remove(passId);
                }
            }
            for (Long depId : removed.dependents) {
                RenderPassNode depNode = passRegistry.get(depId);
                if (depNode != null) {
                    depNode.dependencies.remove(passId);
                }
            }

            // 清理性能统计
            passStatsMap.remove(passId);

            // 标记拓扑排序需要重新计算
            topologyDirty = true;

            LOGGER.fine(String.format("Removed pass: id=0x%X, name=%s", passId, removed.passName));
            return true;
        }

        return false;
    }

    /**
     * 在两个 Pass 之间建立资源依赖关系
     * <p>
     * 当 Pass A 写入的资源被 Pass B 读取时，建立 A → B 的依赖边。
     * 这确保了正确的执行顺序和资源屏障设置。
     *
     * <h3>依赖规则：</h3>
     * <pre>
     * ┌─────────────────────────────────────────────┐
     * │  Pass A (写入资源 R)                        │
     * │      ↓ R (Write-After-Read hazard)         │
     * │  Pass B (读取资源 R)  → 建立 A → B 依赖     │
     * └─────────────────────────────────────────────┘
     * </pre>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>producerPassId</b>: long - 生产者 Pass（写入资源的 Pass）</li>
     *   <li><b>consumerPassId</b>: long - 消费者 Pass（读取资源的 Pass）</li>
     *   <li><b>resourceId</b>: long - 引起依赖的资源唯一标识符</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>boolean - true 表示依赖成功建立</li>
     * </ul>
     *
     * @param producerPassId 生产者 Pass ID
     * @param consumerPassId 消费者 Pass ID
     * @param resourceId     引起依赖的资源 ID
     * @return 是否成功建立依赖
     */
    public boolean addResourceDependency(long producerPassId, long consumerPassId, long resourceId) {
        if (!initialized) return false;

        RenderPassNode producer = passRegistry.get(producerPassId);
        RenderPassNode consumer = passRegistry.get(consumerPassId);

        if (producer == null || consumer == null) {
            LOGGER.warning(String.format(
                    "Cannot add dependency: producer(0x%X) or consumer(0x%X) not registered",
                    producerPassId, consumerPassId));
            return false;
        }

        // 避免自环
        if (producerPassId == consumerPassId) {
            LOGGER.warning("Self-dependency detected, ignoring: pass=0x" + Long.toHexString(producerPassId));
            return false;
        }

        // 检查是否已存在此依赖（避免重复边）
        if (consumer.dependencies.contains(producerPassId)) {
            return true; // 已存在，无需重复添加
        }

        // 建立依赖边：consumer 依赖于 producer
        consumer.addDependency(producerPassId);
        producer.dependents.add(consumerPassId);

        // 记录资源关联
        producer.addOutputResource(resourceId);
        consumer.addInputResource(resourceId);

        // 初始化/更新资源生命周期条目
        resourceLifetimes.computeIfAbsent(resourceId, id -> new ResourceLifetime(id, 0, 0));

        // 标记拓扑排序需要重新计算
        topologyDirty = true;

        LOGGER.fine(String.format(
                "Dependency added: %s(0x%X) -> %s(0x%X) via resource 0x%X",
                producer.passName, producerPassId,
                consumer.passName, consumerPassId,
                resourceId
        ));

        return true;
    }

    /**
     * 设置 Pass 类型分类（用于状态切换优化）
     * <p>
     * 相同类型的 Pass 在拓扑排序后会被尽量安排在一起，
     * 以减少 GPU 状态切换开销（Pipeline、Blend Mode 等）。
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - Pass 唯一标识符</li>
     *   <li><b>passType</b>: {@link RenderPassNode.PassType} - Pass 类型枚举</li>
     * </ul>
     *
     * @param passId   Pass ID
     * @param passType Pass 类型
     */
    public void setPassType(long passId, RenderPassNode.PassType passType) {
        if (!initialized) return;

        RenderPassNode node = passRegistry.get(passId);
        if (node != null) {
            node.passType = passType;
            topologyDirty = true; // 类型变更可能影响重排序结果
            LOGGER.fine(String.format("Pass type set: %s(0x%X) -> %s", node.passName, passId, passType));
        }
    }

    /**
     * 执行拓扑排序并返回优化后的 Pass 执行顺序
     * <p>
     * 使用 Kahn 算法（BFS 版本）进行拓扑排序：
     * <ol>
     *   <li>计算所有节点的入度（依赖数）</li>
     *   <li>将入度为 0 的节点加入队列</li>
     *   <li>依次取出节点，减少其邻居的入度</li>
     *   <li>若邻居入度变为 0，则加入队列</li>
     *   <li>对同层节点按 Pass 类型分组以减少状态切换</li>
     * </ol>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>List&lt;Long&gt; - 拓扑排序后的 Pass ID 列表（按执行顺序排列）</li>
     * </ul>
     *
     * @return 排序后的 Pass ID 列表；如果检测到循环依赖则返回空列表
     */
    public List<Long> getOptimizedExecutionOrder() {
        if (!initialized || !topologyDirty) {
            return cachedTopologyOrder; // 返回缓存结果
        }

        List<Long> sortedOrder = executeTopologicalSort();

        if (!sortedOrder.isEmpty()) {
            cachedTopologyOrder = Collections.unmodifiableList(sortedOrder);
            topologyDirty = false;

            // 更新所有节点的拓扑索引
            for (int i = 0; i < sortedOrder.size(); i++) {
                RenderPassNode node = passRegistry.get(sortedOrder.get(i));
                if (node != null) {
                    node.topologyIndex = i;
                }
            }

            LOGGER.fine(String.format(
                    "Topological sort completed: %d passes ordered", sortedOrder.size()));
        } else {
            LOGGER.warning("Topological sort failed: cycle detected in dependency graph");
        }

        return cachedTopologyOrder;
    }

    /**
     * 内部方法：执行 Kahn 算法拓扑排序
     * <p>
     * 算法复杂度: O(V + E)，其中 V 是节点数，E 是边数
     *
     * @return 排序后的 Pass ID 列表
     */
    private List<Long> executeTopologicalSort() {
        if (passRegistry.isEmpty()) {
            return new ArrayList<>();
        }

        // ---- 第一步：计算每个节点的入度 ----//
        Map<Long, Integer> inDegree = new HashMap<>();
        Queue<Long> zeroInDegreeQueue = new LinkedList<>();

        // 初始化所有节点的入度
        for (RenderPassNode node : passRegistry.values()) {
            int degree = 0;
            // 只统计仍在注册表中的依赖（避免脏数据）
            for (Long depId : node.dependencies) {
                if (passRegistry.containsKey(depId)) {
                    degree++;
                }
            }
            inDegree.put(node.passId, degree);
            if (degree == 0) {
                zeroInDegreeQueue.offer(node.passId);
            }
        }

        // ---- 第二步：Kahn 算法主循环 ----//
        List<Long> result = new ArrayList<>(passRegistry.size());
        
        // 同层收集器：用于对相同入度的节点按类型分组
        List<Long> currentLayer = new ArrayList<>();

        while (!zeroInDegreeQueue.isEmpty()) {
            currentLayer.clear();
            
            // 收集当前层的所有零入度节点
            while (!zeroInDegreeQueue.isEmpty()) {
                currentLayer.add(zeroInDegreeQueue.poll());
            }

            // 对当前层按 Pass 类型分组排序（减少状态切换）
            // 策略：GEOMETRY_OPAQUE → GEOMETRY_TRANSLUCENT → COMPUTE → POST_PROCESS → COPY
            currentLayer.sort((a, b) -> {
                RenderPassNode nodeA = passRegistry.get(a);
                RenderPassNode nodeB = passRegistry.get(b);
                int typeA = (nodeA != null) ? nodeA.passType.ordinal() : Integer.MAX_VALUE;
                int typeB = (nodeB != null) ? nodeB.passType.ordinal() : Integer.MAX_VALUE;
                return Integer.compare(typeA, typeB);
            });

            // 将排序后的当前层加入结果
            result.addAll(currentLayer);

            // 处理当前层节点的出边
            for (Long passId : currentLayer) {
                RenderPassNode node = passRegistry.get(passId);
                if (node == null) continue;

                for (Long dependentId : node.dependents) {
                    // 减少依赖者的入度
                    Integer newDegree = inDegree.compute(dependentId, (k, v) -> (v == null ? 0 : v) - 1);
                    if (newDegree != null && newDegree == 0) {
                        zeroInDegreeQueue.offer(dependentId);
                    }
                }
            }
        }

        // ---- 第三步：检测循环依赖 ----//
        if (result.size() != passRegistry.size()) {
            // 存在循环依赖，记录警告并返回部分结果
            Set<Long> sortedSet = new HashSet<>(result);
            StringBuilder cycleNodes = new StringBuilder();
            for (Long id : passRegistry.keySet()) {
                if (!sortedSet.contains(id)) {
                    RenderPassNode node = passRegistry.get(id);
                    cycleNodes.append(node != null ? node.passName : "0x" + Long.toHexString(id)).append(", ");
                }
            }
            LOGGER.warning(String.format(
                    "Cycle detected! Unsorted passes: [%s]", cycleNodes.toString()));
        }

        return result;
    }

    /**
     * 分析 Pass 合并候选
     * <p>
     * 检查相邻的 Pass 是否可以合并为单个 Pass 以减少开销。
     * 合并条件：
     * <ol>
     *   <li>两个 Pass 之间没有其他 Pass 依赖它们</li>
     *   <li>使用相同的 Pipeline 或兼容的 Pipeline</li>
     *   <li>资源依赖不冲突（无 Read-After-Write hazard）</li>
     *   <li>相同的 Render Target 配置</li>
     * </ol>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>List&lt;long[]&gt; - 可合并的 Pass ID 对列表，每项为 [passA, passB]</li>
     * </ul>
     *
     * @return 可合并的 Pass 对列表
     */
    public List<long[]> analyzeMergeCandidates() {
        if (!initialized) return Collections.emptyList();

        List<long[]> candidates = new ArrayList<>();
        List<Long> order = getOptimizedExecutionOrder();

        // 遍历相邻的 Pass 对
        for (int i = 0; i < order.size() - 1; i++) {
            long passA = order.get(i);
            long passB = order.get(i + 1);

            RenderPassNode nodeA = passRegistry.get(passA);
            RenderPassNode nodeB = passRegistry.get(passB);

            if (nodeA == null || nodeB == null) continue;

            // 条件1：检查是否有其他 Pass 依赖这两个 Pass 之间的中间状态
            boolean hasIntermediateDependent = false;
            for (Long depId : nodeA.dependents) {
                if (depId != passB && passRegistry.containsKey(depId)) {
                    hasIntermediateDependent = true;
                    break;
                }
            }

            if (hasIntermediateDependent) continue;

            // 条件2：检查 Pipeline 兼容性（相同类型通常可以合并）
            boolean pipelineCompatible = (nodeA.pipelineHash == nodeB.pipelineHash) ||
                    (nodeA.passType == nodeB.passType && 
                     nodeA.passType != RenderPassNode.PassType.COMPUTE);

            if (!pipelineCompatible) continue;

            // 条件3：检查资源冲突（A 的输出不能是 B 的输入以外的其他 Pass 的输入）
            boolean resourceConflict = false;
            for (Long outputRes : nodeA.outputResources) {
                for (Long otherDep : nodeA.dependents) {
                    if (otherDep != passB) {
                        RenderPassNode otherNode = passRegistry.get(otherDep);
                        if (otherNode != null && otherNode.inputResources.contains(outputRes)) {
                            resourceConflict = true;
                            break;
                        }
                    }
                }
                if (resourceConflict) break;
            }

            if (resourceConflict) continue;

            // 所有条件满足，这是一个合并候选
            candidates.add(new long[]{passA, passB});
        }

        mergeCandidateCount.set(candidates.size());

        if (!candidates.isEmpty()) {
            LOGGER.fine(String.format(
                    "Found %d merge candidate pairs", candidates.size()));
        }

        return candidates;
    }

    // ==================== 资源生命周期分析与内存别名系统 ====================

    /**
     * 注册资源及其属性到生命周期追踪系统
     * <p>
     * 在分配渲染目标、纹理等 GPU 资源时调用，
     * 记录资源的格式和大小，用于后续的内存别名分析。
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>resourceId</b>: long - 资源唯一标识符（如 Vulkan Image 句柄）</li>
     *   <li><b>format</b>: int - 资源格式（如 Vulkan VK_FORMAT 枚举值）</li>
     *   <li><b>sizeBytes</b>: long - 资源占用的显存大小（字节）</li>
     * </ul>
     *
     * @param resourceId 资源唯一标识
     * @param format     资源格式
     * @param sizeBytes  资源大小（字节）
     */
    public void registerResource(long resourceId, int format, long sizeBytes) {
        if (!initialized) return;

        resourceLifetimes.computeIfAbsent(resourceId, 
                id -> new ResourceLifetime(id, format, sizeBytes));

        // 如果资源已存在，更新其大小信息
        ResourceLifetime existing = resourceLifetimes.get(resourceId);
        if (existing != null && existing.sizeBytes == 0) {
            existing.sizeBytes = sizeBytes;
            existing.format = format;
        }

        LOGGER.finer(String.format(
                "Resource registered: id=0x%X, format=%d, size=%d bytes",
                resourceId, format, sizeBytes));
    }

    /**
     * 分析所有资源的生命周期并执行内存别名优化
     * <p>
     * 基于拓扑排序结果，计算每个资源的首次使用和最后使用位置，
     * 然后对生命周期不重叠的资源进行内存别名分组。
     *
     * <h3>别名算法：</h3>
     * <pre>
     * ┌──────────────────────────────────────────────────────┐
     * │  Pass0   Pass1   Pass2   Pass3   Pass4   Pass5       │
     * │  ┌───┐  ┌───┐                                  ┌───┐ │
     * │  │RT_A│  │RT_B│                          ┌───┐  │RT_A│ │ ← RT_A 复用 (生命周期不重叠)
     * │  └───┘  └───┘                          │RT_C│  └───┘ │
     * │                                          └───┘         │
     * │  [Block1: RT_A]         [Block2: RT_B/RT_C]          │
     * │  内存块1: Pass0-4 复用   内存块2: Pass1-4 复用        │
     * └──────────────────────────────────────────────────────┘
     * </pre>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>Map&lt;Long, Long&gt; - 别名映射表：resourceId → aliasBlockId</li>
     * </ul>
     *
     * @return 资源到内存块的别名映射；未找到可别名的资源时返回空映射
     */
    public Map<Long, Long> analyzeResourceAliasing() {
        if (!initialized || passRegistry.isEmpty()) return Collections.emptyMap();

        // 确保拓扑排序已完成
        List<Long> order = getOptimizedExecutionOrder();
        if (order.isEmpty()) return Collections.emptyMap();

        // ---- 第一步：计算每个资源的生命周期区间 ----//
        
        // 重置所有资源的生命周期区间
        for (ResourceLifetime lifetime : resourceLifetimes.values()) {
            lifetime.firstUseIndex = Integer.MAX_VALUE;
            lifetime.lastUseIndex = -1;
            lifetime.aliased = false;
            lifetime.aliasMemoryBlockId = -1;
        }

        // 遍历排序后的 Pass，记录每个资源的使用位置
        for (int i = 0; i < order.size(); i++) {
            RenderPassNode node = passRegistry.get(order.get(i));
            if (node == null) continue;

            // 更新输入资源的 lastUseIndex
            for (Long resId : node.inputResources) {
                ResourceLifetime lt = resourceLifetimes.get(resId);
                if (lt != null) {
                    lt.firstUseIndex = Math.min(lt.firstUseIndex, i);
                    lt.lastUseIndex = Math.max(lt.lastUseIndex, i);
                }
            }

            // 更新输出资源的 firstUseIndex 和 lastUseIndex
            for (Long resId : node.outputResources) {
                ResourceLifetime lt = resourceLifetimes.get(resId);
                if (lt != null) {
                    lt.firstUseIndex = Math.min(lt.firstUseIndex, i);
                    lt.lastUseIndex = Math.max(lt.lastUseIndex, i);
                }
            }
        }

        // ---- 第二步：贪心别名分配算法 ----//
        //
        // 策略：按资源大小降序排列，优先为大的资源寻找别名伙伴
        // 条件：相同格式 + 生命周期不重叠 + 大小兼容
        Map<Long, Long> aliasMap = new HashMap<>();
        List<ResourceLifetime> sortedBySize = new ArrayList<>(resourceLifetimes.values());
        
        // 按大小降序排列（大资源优先匹配）
        sortedBySize.sort((a, b) -> Long.compare(b.sizeBytes, a.sizeBytes));

        // 分配的内存块 ID 计数器
        long blockIdCounter = 0;

        for (ResourceLifetime resource : sortedBySize) {
            // 跳过已分配或大小为 0 的资源
            if (resource.aliased || resource.sizeBytes == 0) continue;
            // 跳过未使用过的资源
            if (resource.firstUseIndex > resource.lastUseIndex) continue;

            // 创建新的内存块用于此资源
            long currentBlockId = blockIdCounter++;
            resource.aliased = true;
            resource.aliasMemoryBlockId = currentBlockId;
            aliasMap.put(resource.resourceId, currentBlockId);

            // 尝试将其他不重叠的资源也放入此内存块
            for (ResourceLifetime candidate : sortedBySize) {
                if (candidate.aliased || candidate.resourceId == resource.resourceId) continue;
                if (candidate.sizeBytes == 0) continue;
                if (candidate.firstUseIndex > candidate.lastUseIndex) continue;

                // 检查别名条件：
                // 1. 格式相同（确保 Vulkan 兼容性）
                // 2. 生命周期不重叠
                // 3. 候选资源大小不超过基础资源（可以放入同一块）
                boolean formatCompatible = (candidate.format == resource.format);
                boolean noOverlap = !resource.overlaps(candidate);
                boolean sizeFits = (candidate.sizeBytes <= resource.sizeBytes);

                if (formatCompatible && noOverlap && sizeFits) {
                    candidate.aliased = true;
                    candidate.aliasMemoryBlockId = currentBlockId;
                    aliasMap.put(candidate.resourceId, currentBlockId);

                    // 统计节省的内存量
                    memoryAliasSavedBytes.addAndGet(candidate.sizeBytes);
                }
            }
        }

        // 输出别名分析结果
        if (!aliasMap.isEmpty()) {
            // 统计内存块数量
            Set<Long> uniqueBlocks = new HashSet<>(aliasMap.values());
            LOGGER.info(String.format(
                    "Resource aliasing analysis completed:/n" +
                    "  Total resources tracked: %d" +
                    "  Aliased resources: %d" +
                    "  Memory blocks needed: %d" +
                    "  Estimated memory saved: %.2f MB",
                    resourceLifetimes.size(),
                    aliasMap.size(),
                    uniqueBlocks.size(),
                    memoryAliasSavedBytes.get() / (1024.0 * 1024.0)
            ));
        }

        return aliasMap;
    }

    /**
     * 获取指定资源的生命周期信息
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>resourceId</b>: long - 资源唯一标识符</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>{@link ResourceLifetime} - 资源生命周期对象，不存在则返回 null</li>
     * </ul>
     *
     * @param resourceId 资源 ID
     * @return 资源生命周期信息
     */
    public ResourceLifetime getResourceLifetime(long resourceId) {
        return resourceLifetimes.get(resourceId);
    }

    /**
     * 获取当前帧的内存别名节省统计
     *
     * @return 节省的字节数
     */
    public long getMemoryAliasSavedBytes() {
        return memoryAliasSavedBytes.get();
    }

    // ==================== 核心 API：Pipeline Cache ====================

    /**
     * 获取或创建 Pipeline（带缓存）
     * <p>
     * 这是核心方法，实现 `compatibility-mode-optimization.md` §2.2 中的 Pipeline Cache 策略：
     * <pre>
     * 1. 计算配置哈希
     * 2. 检查缓存是否命中
     * 3. 命中 → 直接返回缓存的 Pipeline
     * 4. 未命中 → 创建新 Pipeline 并加入缓存
     * </pre>
     *
     * @param vkDevice      Vulkan 设备句柄
     * @param pipelineCache Vulkan Pipeline Cache 句柄
     * @param configHash    管线配置的哈希值
     * @return Pipeline 句柄，失败返回 0
     */
    public long getOrCreatePipeline(long vkDevice, long pipelineCacheHandle,
                                    long configHash) {
        if (!enabled || !initialized) return 0;

        // 1. 尝试从缓存获取
        CachedPipeline cached = pipelineCache.get(configHash);

        if (cached != null) {
            // 缓存命中
            cached.lastUsedFrame.set(currentFrame.get());
            cached.referenceCount.incrementAndGet();
            pipelineCacheHits.incrementAndGet();

            LOGGER.fine(String.format(
                    "Pipeline cache HIT: hash=0x%016X, refs=%d",
                    configHash, cached.referenceCount.get()
            ));

            return cached.pipeline;
        }

        // 2. 缓存未命中，需要创建新的 Pipeline
        pipelineCacheMisses.incrementAndGet();

        // 检查缓存大小限制
        if (pipelineCache.size() >= MAX_CACHED_PIPELINES) {
            evictOldestPipelines();
        }

        // 创建新 Pipeline
        long newPipeline = createNewPipeline(vkDevice, pipelineCacheHandle, configHash);

        if (newPipeline != 0) {
            // 加入缓存
            CachedPipeline entry = new CachedPipeline(
                    newPipeline, configHash, currentFrame.get()
            );
            pipelineCache.put(configHash, entry);
            totalCreatedPipelines.incrementAndGet();

            LOGGER.fine(String.format(
                    "Pipeline created: hash=0x%016X, total_cached=%d",
                    configHash, pipelineCache.size()
            ));
        }

        return newPipeline;
    }

    /**
     * 强制刷新指定配置的 Pipeline
     * <p>
     * 当着色器或管线状态发生变化时调用此方法使缓存失效。
     *
     * @param configHash 需要失效的配置哈希
     * @return 是否成功移除
     */
    public boolean invalidatePipeline(long configHash) {
        if (!initialized) return false;

        CachedPipeline removed = pipelineCache.remove(configHash);

        if (removed != null) {
            // 销毁 Pipeline（Vulkan 资源清理）
            destroyPipelineInternal(removed.pipeline);
            LOGGER.fine(String.format(
                    "Pipeline invalidated: hash=0x%016X", configHash
            ));
            return true;
        }

        return false;
    }

    // ==================== 核心 API：Descriptor Set 复用 ====================

    /**
     * 获取或分配 Descriptor Set（带复用）
     * <p>
     * 这是核心方法，实现 `compatibility-mode-optimization.md` §2.3 中的 Descriptor Set 复用策略：
     * <pre>
     * 1. 构建组合键（textureView ^ sampler ^ uniformBuffer）
     * 2. 检查缓存是否命中
     * 3. 命中 → 直接返回缓存的 Descriptor Set
     * 4. 未命中 → 从 Pool 中分配新的 Descriptor Set
     * </pre>
     *
     * @param vkDevice       Vulkan 设备句柄
     * @param textureView    纹理视图句柄
     * @param sampler        采样器句柄
     * @param uniformBuffer  Uniform Buffer 句柄
     * @return Descriptor Set 句柄，失败返回 0
     */
    public long getOrCreateDescriptorSet(long vkDevice,
                                         long textureView, long sampler,
                                         long uniformBuffer) {
        if (!enabled || !initialized) return 0;

        // 构建组合键
        long compositeKey = buildDescriptorCompositeKey(textureView, sampler, uniformBuffer);

        // 1. 尝试从缓存获取
        CachedDescriptorSet cached = descriptorSetCache.get(compositeKey);

        if (cached != null) {
            // 缓存命中
            descriptorSetHits.incrementAndGet();

            LOGGER.fine(String.format(
                    "Descriptor set cache HIT: key=0x%016X",
                    compositeKey
            ));

            return cached.descriptorSet;
        }

        // 2. 缓存未命中，需要分配新的 Descriptor Set
        descriptorSetMisses.incrementAndGet();

        // 检查缓存大小限制
        if (descriptorSetCache.size() >= MAX_DESCRIPTOR_SETS) {
            evictOldestDescriptorSets();
        }

        // 分配新的 Descriptor Set
        long newDescriptorSet = allocateNewDescriptorSet(
                vkDevice, textureView, sampler, uniformBuffer
        );

        if (newDescriptorSet != 0) {
            // 加入缓存
            CachedDescriptorSet entry = new CachedDescriptorSet(
                    newDescriptorSet, compositeKey, currentFrame.get()
            );
            descriptorSetCache.put(compositeKey, entry);
            totalAllocatedDescriptorSets.incrementAndGet();

            LOGGER.fine(String.format(
                    "Descriptor set allocated: key=0x%016X, total_cached=%d",
                    compositeKey, descriptorSetCache.size()
            ));
        }

        return newDescriptorSet;
    }

    /**
     * 重置 Descriptor Pool（每帧开始时调用）
     * <p>
     * 根据 `compatibility-mode-optimization.md` §2.3 中的建议：
     * 每帧开始时重置 Pool 以释放上一帧的 Descriptor Set。
     */
    public void resetDescriptorPool() {
        if (!enabled || !initialized) return;

        // 清空 Descriptor Set 缓存
        descriptorSetCache.clear();

        // 重置 Descriptor Pool（每帧重置以复用资源）
        resetDescriptorPoolInternal();

        LOGGER.fine("Descriptor pool reset");
    }

    // ==================== 帧管理 API ====================

    /**
     * 开始新帧
     * <p>
     * 每帧开始时调用：
     * <ul>
     *   <li>递增帧计数器</li>
     *   <li>重置 Descriptor Pool</li>
     *   <li>定期执行缓存清理</li>
     * </ul>
     */
    public void beginFrame() {
        if (!enabled || !initialized) return;

        int frame = currentFrame.incrementAndGet();

        // 重置 Descriptor Pool
        resetDescriptorPool();

        // 每 300 帧（约 5 秒）清理一次缓存
        if (frame % CACHE_EVICTION_FRAME_THRESHOLD == 0) {
            evictOldPipelines(frame - CACHE_EVICTION_FRAME_THRESHOLD);
        }
    }

    /**
     * 结束当前帧
     * <p>
     * 帧结束时调用，可用于统计和日志输出。
     */
    public void endFrame() {
        if (!enabled || !initialized) return;
        // 可在此处添加帧结束时的逻辑
    }

    // ==================== RenderPass Mixin 回调 API ====================

    /**
     * 记录 Pipeline 切换事件（由 RenderPassMixin 调用）
     * <p>
     * 策略 S2: Pipeline 切换记录
     * <p>
     * 实现功能：
     * <ol>
     *   <li>更新 Pass 级别的 Pipeline 切换计数</li>
     *   <li>检测实际状态切换（旧 Pipeline != 新 Pipeline 时才计数）</li>
     *   <li>更新全局状态切换统计</li>
     *   <li>识别高频切换的 Pass 用于后续优化</li>
     * </ol>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 的唯一标识符</li>
     *   <li><b>oldPipeline</b>: long - 切换前的 Pipeline 句柄</li>
     *   <li><b>newPipeline</b>: long - 切换后的 Pipeline 句柄</li>
     *   <li><b>configHash</b>: long - 新 Pipeline 的配置哈希值</li>
     * </ul>
     *
     * @param passId      RenderPass 的唯一标识符
     * @param oldPipeline 旧的 Pipeline 句柄
     * @param newPipeline 新的 Pipeline 句柄
     * @param configHash  配置哈希值
     */
    public void recordPipelineSwitch(long passId, long oldPipeline,
                                     long newPipeline, long configHash) {
        if (!enabled || !initialized) return;

        // 获取或创建此 Pass 的性能统计
        PassPerformanceStats stats = passStatsMap.computeIfAbsent(passId, PassPerformanceStats::new);

        // 检测是否为真实的状态切换（不同 Pipeline 之间）
        boolean isRealSwitch = (oldPipeline != newPipeline && oldPipeline != 0);
        
        if (isRealSwitch) {
            // 更新 Pass 级别统计
            stats.pipelineSwitchCount.incrementAndGet();
            stats.currentPipeline = newPipeline;

            // 更新全局状态切换计数
            stateSwitchCount.incrementAndGet();

            LOGGER.fine(String.format(
                    "Pipeline switch recorded: pass=0x%X, old=0x%X, new=0x%X, hash=0x%016X [REAL SWITCH #%d]",
                    passId, oldPipeline, newPipeline, configHash,
                    stats.pipelineSwitchCount.get()
            ));
        } else {
            LOGGER.finer(String.format(
                    "Pipeline switch recorded: pass=0x%X, old=0x%X, new=0x%X, hash=0x%016X [SAME/INIT]",
                    passId, oldPipeline, newPipeline, configHash
            ));
        }

        // 同步更新关联的 Pass 节点的 Pipeline 哈希
        RenderPassNode node = passRegistry.get(passId);
        if (node != null) {
            node.pipelineHash = configHash;
        }
    }

    /**
     * 记录纹理绑定事件（由 RenderPassMixin 调用）
     * <p>
     * 策略 S3: Descriptor Set 复用 - 纹理绑定监控
     * <p>
     * 实现功能：
     * <ol>
     *   <li>追踪每个 Pass 的纹理绑定模式（哪些纹理组合频繁出现）</li>
     *   <li>检测重复绑定相同的纹理组合（可优化掉）</li>
     *   <li>构建热门 Descriptor Set 预分配建议列表</li>
     * </ol>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 的唯一标识符</li>
     *   <li><b>textureView</b>: long - 纹理视图句柄</li>
     *   <li><b>sampler</b>: long - 采样器句柄</li>
     *   <li><b>descriptorKey</b>: long - Descriptor 组合键</li>
     * </ul>
     *
     * @param passId       RenderPass 的唯一标识符
     * @param textureView  纹理视图句柄
     * @param sampler      采样器句柄
     * @param descriptorKey Descriptor 组合键
     */
    public void recordTextureBind(long passId, long textureView,
                                   long sampler, long descriptorKey) {
        if (!enabled || !initialized) return;

        // 获取此 Pass 的性能统计
        PassPerformanceStats stats = passStatsMap.get(passId);
        if (stats == null) return;

        // 增加纹理绑定计数
        stats.textureBindCount.incrementAndGet();

        // 检测重复绑定：如果与上次绑定的相同，可能是冗余操作
        boolean isRedundantBind = (stats.lastTextureCompositeKey == descriptorKey);
        
        if (isRedundantBind) {
            LOGGER.finest(String.format(
                    "Redundant texture bind detected: pass=0x%X, key=0x%016X (same as previous)",
                    passId, descriptorKey
            ));
        } else {
            stats.lastTextureCompositeKey = descriptorKey;
            LOGGER.finer(String.format(
                    "Texture bind recorded: pass=0x%X, texture=0x%X, sampler=0x%X, key=0x%016X",
                    passId, textureView, sampler, descriptorKey
            ));
        }
    }

    /**
     * 记录 Uniform 绑定事件（由 RenderPassMixin 调用）
     * <p>
     * 策略 S3: Uniform 优化 - 监控 Uniform 更新频率
     * <p>
     * 实现功能：
     * <ol>
     *   <li>追踪每个 Uniform location 的更新历史</li>
     *   <li>基于 (location + offset + length) 生成数据指纹</li>
     *   <li>检测冗余更新：相同位置写入相同数据时标记为冗余</li>
     *   <li>提供合并批量更新的优化建议</li>
     * </ol>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 的唯一标识符</li>
     *   <li><b>location</b>: int - Uniform 位置索引（GLSL layout location）</li>
     *   <li><b>offset</b>: int - 数据偏移量（字节）</li>
     *   <li><b>length</b>: int - 数据长度（字节）</li>
     * </ul>
     *
     * @param passId   RenderPass 的唯一标识符
     * @param location Uniform 位置索引
     * @param offset   数据偏移量
     * @param length   数据长度
     */
    public void recordUniformBind(long passId, int location,
                                   int offset, int length) {
        if (!enabled || !initialized) return;

        // 获取此 Pass 的性能统计
        PassPerformanceStats stats = passStatsMap.get(passId);
        if (stats == null) return;

        // 增加 Uniform 更新计数
        stats.uniformUpdateCount.incrementAndGet();

        // 生成数据指纹：基于 location、offset、length 的简单哈希
        // 注意：这里不包含实际数据内容（需要调用方提供才能精确检测）
        // 当前使用参数组合作为近似指纹
        long dataFingerprint = ((long) location << 32) | ((long) offset << 16) | length;

        // 检查是否为冗余更新（相同 location 的连续相同参数更新）
        Long lastFingerprint = stats.lastUniformSnapshot.get(location);
        boolean isRedundant = (lastFingerprint != null && lastFingerprint.equals(dataFingerprint));

        if (isRedundant) {
            stats.redundantUniformUpdates.incrementAndGet();
            LOGGER.finest(String.format(
                    "Redundant uniform update detected: pass=0x%X, loc=%d, off=%d, len=%d [REDUNDANT #%d]",
                    passId, location, offset, length,
                    stats.redundantUniformUpdates.get()
            ));
        } else {
            // 更新快照
            stats.lastUniformSnapshot.put(location, dataFingerprint);
            LOGGER.finer(String.format(
                    "Uniform bind recorded: pass=0x%X, location=%d, offset=%d, length=%d",
                    passId, location, offset, length
            ));
        }
    }

    /**
     * 结束 RenderPass（由 RenderPassMixin.close() 调用）
     * <p>
     * 执行 Pass 结束时的清理和统计工作：
     * <ol>
     *   <li>计算此 Pass 的执行时间</li>
     *   <li>汇总性能指标到帧级统计</li>
     *   <li>重置 Pass 临时状态</li>
     *   <li>输出详细日志（在 FINE 级别）</li>
     * </ol>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 的唯一标识符</li>
     * </ul>
     *
     * @param passId RenderPass 的唯一标识符
     */
    public void endPass(long passId) {
        if (!enabled || !initialized) return;

        // 获取 Pass 性能统计
        PassPerformanceStats stats = passStatsMap.get(passId);
        if (stats == null) return;

        // 计算 Pass 执行时间
        long nowNanos = System.nanoTime();
        if (stats.passStartTimeNanos > 0) {
            long executionTime = nowNanos - stats.passStartTimeNanos;
            stats.totalExecutionTimeNanos.addAndGet(executionTime);
        }
        stats.executionCount.incrementAndGet();

        // 获取 Pass 节点信息用于日志
        RenderPassNode node = passRegistry.get(passId);
        String passName = (node != null) ? node.passName : ("0x" + Long.toHexString(passId));

        LOGGER.fine(String.format(
                "RenderPass ended: %s(0x%X)" +
                "  Stats: pipeline_switches=%d, texture_binds=%d, uniform_updates=%d" +
                "         redundant_uniforms=%d, exec_time_us=%d, exec_count=%d",
                passName, passId,
                stats.pipelineSwitchCount.get(),
                stats.textureBindCount.get(),
                stats.uniformUpdateCount.get(),
                stats.redundantUniformUpdates.get(),
                stats.totalExecutionTimeNanos.get() / 1000,  // 转换为微秒
                stats.executionCount.get()
        ));

        // 提交此 Pass 的所有优化数据并更新帧级统计
        submitPassOptimizationData(passId);
    }

    /**
     * 开始 Pass 计时（由 RenderPassMixin 在 Pass 开始时调用）
     *
     * @param passId RenderPass 的唯一标识符
     */
    public void beginPass(long passId) {
        if (!enabled || !initialized) return;

        PassPerformanceStats stats = passStatsMap.computeIfAbsent(passId, PassPerformanceStats::new);
        stats.passStartTimeNanos = System.nanoTime();
    }

    // ==================== 查询 API ====================

    /**
     * 是否已启用
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 是否已初始化
     */
    public boolean isInitialized() { return initialized; }

    /**
     * 获取当前 Pipeline 缓存命中率
     *
     * @return 命中率 (0.0 ~ 1.0)
     */
    public double getPipelineCacheHitRate() {
        long hits = pipelineCacheHits.get();
        long misses = pipelineCacheMisses.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取当前 Descriptor Set 缓存命中率
     *
     * @return 命中率 (0.0 ~ 1.0)
     */
    public double getDescriptorSetHitRate() {
        long hits = descriptorSetHits.get();
        long misses = descriptorSetMisses.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取当前 Pipeline 缓存大小
     */
    public int getPipelineCacheSize() { return pipelineCache.size(); }

    /**
     * 获取当前 Descriptor Set 缓存大小
     */
    public int getDescriptorSetCacheSize() { return descriptorSetCache.size(); }

    /**
     * 获取总创建 Pipeline 数量
     */
    public long getTotalCreatedPipelines() { return totalCreatedPipelines.get(); }

    /**
     * 获取总分配 Descriptor Set 数量
     */
    public long getTotalAllocatedDescriptorSets() { return totalAllocatedDescriptorSets.get(); }

    /**
     * 获取当前注册的 Pass 数量
     *
     * @return 注册的 Pass 节点数
     */
    public int getRegisteredPassCount() { return passRegistry.size(); }

    /**
     * 获取当前追踪的资源数量
     *
     * @return 追踪的资源数
     */
    public int getTrackedResourceCount() { return resourceLifetimes.size(); }

    /**
     * 获取状态切换总次数
     *
     * @return 本帧的状态切换次数
     */
    public long getStateSwitchCount() { return stateSwitchCount.get(); }

    /**
     * 获取 Pass 合并候选对数量
     *
     * @return 可合并的 Pass 对数
     */
    public int getMergeCandidateCount() { return mergeCandidateCount.get(); }

    /**
     * 获取指定 Pass 的性能统计
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - Pass 唯一标识符</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>{@link PassPerformanceStats} - 性能统计对象，不存在则返回 null</li>
     * </ul>
     *
     * @param passId Pass ID
     * @return Pass 性能统计，不存在返回 null
     */
    public PassPerformanceStats getPassStats(long passId) {
        return passStatsMap.get(passId);
    }

    /**
     * 获取指定 Pass 的节点信息
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - Pass 唯一标识符</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>{@link RenderPassNode} - Pass 节点对象，不存在则返回 null</li>
     * </ul>
     *
     * @param passId Pass ID
     * @return Pass 节点信息，不存在返回 null
     */
    public RenderPassNode getPassNode(long passId) {
        return passRegistry.get(passId);
    }

    /**
     * 获取格式化的完整性能报告
     * <p>
     * 包含 Pipeline Cache、Descriptor Set、DAG 依赖图、资源别名等所有统计信息。
     * 参考 `compatibility-mode-optimization.md` §5.3 性能指标。
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>String - 多行格式化的性能报告字符串</li>
     * </ul>
     *
     * @return 格式化的性能报告
     */
    public String formatReport() {
        if (!initialized) return "FrameGraphOptimizer not initialized";

        double pipelineHitRate = getPipelineCacheHitRate();
        double descSetHitRate = getDescriptorSetHitRate();

        // 计算 Pass 级别汇总统计
        long totalPipelineSwitches = 0;
        long totalTextureBinds = 0;
        long totalUniformUpdates = 0;
        long totalRedundantUniforms = 0;
        long totalExecTimeNanos = 0;
        long totalExecCount = 0;

        for (PassPerformanceStats stats : passStatsMap.values()) {
            totalPipelineSwitches += stats.pipelineSwitchCount.get();
            totalTextureBinds += stats.textureBindCount.get();
            totalUniformUpdates += stats.uniformUpdateCount.get();
            totalRedundantUniforms += stats.redundantUniformUpdates.get();
            totalExecTimeNanos += stats.totalExecutionTimeNanos.get();
            totalExecCount += stats.executionCount.get();
        }

        // 计算平均执行时间（微秒）
        double avgExecTimeUs = (totalExecCount > 0) ? 
                (totalExecTimeNanos / 1000.0 / totalExecCount) : 0.0;

        // 构建报告
        StringBuilder report = new StringBuilder();
        report.append("=================================================================\n");
        report.append("           Frame Graph Optimizer - Full Performance Report          \n");
        report.append("=================================================================\n");

        report.append(String.format(
                "  Enabled: %s | Initialized: %s | Frame: %d\n",
                enabled ? "YES" : "NO",
                initialized ? "YES" : "NO",
                currentFrame.get()
        ));

        report.append("[Pipeline Cache]\n");
        report.append(String.format(
                "  Size: %d/%d | Hit Rate: %.1f%% | Created: %d\n",
                getPipelineCacheSize(), MAX_CACHED_PIPELINES,
                pipelineHitRate * 100,
                getTotalCreatedPipelines()
        ));

        report.append("[Descriptor Set Cache]\n");
        report.append(String.format(
                "  Size: %d/%d | Hit Rate: %.1f%% | Allocated: %d\n",
                getDescriptorSetCacheSize(), MAX_DESCRIPTOR_SETS,
                descSetHitRate * 100,
                getTotalAllocatedDescriptorSets()
        ));

        report.append(String.format(
                "  Registered Passes: %d | Tracked Resources: %d\n" +
                "  Topology Dirty: %s | Merge Candidates: %d\n" +
                "  State Switches (frame): %d\n",
                getRegisteredPassCount(),
                getTrackedResourceCount(),
                topologyDirty ? "YES" : "NO (cached)",
                getMergeCandidateCount(),
                getStateSwitchCount()
        ));

        report.append(String.format(
                "  Total Pipeline Switches: %d\n" +
                "  Total Texture Binds: %d\n" +
                "  Total Uniform Updates: %d\n" +
                "  Redundant Uniform Updates: %d (%.1f%%)\n" +
                "  Avg Pass Execution Time: %.2f us\n" +
                "  Total Pass Executions: %d\n",
                totalPipelineSwitches,
                totalTextureBinds,
                totalUniformUpdates,
                totalRedundantUniforms,
                (totalUniformUpdates > 0) ? (100.0 * totalRedundantUniforms / totalUniformUpdates) : 0.0,
                avgExecTimeUs,
                totalExecCount
        ));

        report.append(String.format(
                "  Estimated Memory Saved: %.2f MB (%d bytes)\n",
                getMemoryAliasSavedBytes() / (1024.0 * 1024.0),
                getMemoryAliasSavedBytes()
        ));

        return report.toString();
    }

    // ==================== 内部实现方法 ====================

    /**
     * 构建 Descriptor Set 组合键
     *
     * @param textureView   纹理视图句柄
     * @param sampler       采样器句柄
     * @param uniformBuffer Uniform Buffer 句柄
     * @return 组合键
     */
    private long buildDescriptorCompositeKey(long textureView, long sampler, long uniformBuffer) {
        // 使用位运算组合三个值，尽量减少冲突
        return textureView ^ sampler ^ uniformBuffer;
    }

    /**
     * 创建新的 Pipeline
     * <p>
     * 这是实际创建逻辑的占位符。
     * 在集成时需要替换为真正的 Vulkan API 调用。
     *
     * @param vkDevice           设备句柄
     * @param pipelineCacheHandle Pipeline Cache 句柄
     * @param configHash         配置哈希（用于调试日志）
     * @return 新创建的 Pipeline 句柄，失败返回 0
     */
    private long createNewPipeline(long vkDevice, long pipelineCacheHandle,
                                   long configHash) {
        // 创建新的 Vulkan Pipeline
        // 实际集成时需要根据 configHash 还原管线配置并调用 vkCreateGraphicsPipelines
        //
        // 参数说明：
        // - vkDevice: Vulkan 设备句柄
        // - pipelineCacheHandle: Pipeline Cache 句柄（用于加速管线创建）
        // - configHash: 管线配置哈希值（用于日志记录）
        //
        // 返回值：新创建的 Pipeline 句柄（非零表示成功），失败返回 0

        LOGGER.warning("createNewPipeline() 未实现：返回 0");
        return 0L;
    }

    /**
     * 分配新的 Descriptor Set
     * <p>
     * 这是实际分配逻辑的占位符。
     * 在集成时需要替换为真正的 Vulkan API 调用。
     *
     * @param vkDevice      设备句柄
     * @param textureView   纹理视图句柄
     * @param sampler       采样器句柄
     * @param uniformBuffer Uniform Buffer 句柄
     * @return 新分配的 Descriptor Set 句柄，失败返回 0
     */
    private long allocateNewDescriptorSet(long vkDevice,
                                          long textureView, long sampler,
                                          long uniformBuffer) {
        // 分配新的 Vulkan Descriptor Set
        // 实际集成时需要调用 vkAllocateDescriptorSets 和 vkUpdateDescriptorSets
        //
        // 参数说明：
        // - vkDevice: Vulkan 设备句柄
        // - textureView: 纹理视图句柄
        // - sampler: 采样器句柄
        // - uniformBuffer: Uniform Buffer 句柄
        //
        // 返回值：新分配的 Descriptor Set 句柄（非零表示成功），失败返回 0

        LOGGER.warning("allocateNewDescriptorSet() 未实现：返回 0");
        return 0L;
    }

    // ==================== 缓存管理方法 ====================

    /**
     * 淘汰最老的 Pipeline 缓存条目（LRU 策略）
     * <p>
     * 当缓存达到上限时调用。
     */
    private void evictOldestPipelines() {
        if (pipelineCache.isEmpty()) return;

        long oldestFrame = Long.MAX_VALUE;
        Long oldestKey = null;

        // 找到最老的条目
        for (var entry : pipelineCache.entrySet()) {
            if (entry.getValue().lastUsedFrame.get() < oldestFrame) {
                oldestFrame = entry.getValue().lastUsedFrame.get();
                oldestKey = entry.getKey();
            }
        }

        // 淘汰最老的条目
        if (oldestKey != null) {
            CachedPipeline evicted = pipelineCache.remove(oldestKey);
            if (evicted != null) {
                // 销毁被淘汰的 Pipeline（Vulkan 资源清理）
                destroyPipelineInternal(evicted.pipeline);
                LOGGER.fine(String.format(
                        "Evicted oldest pipeline: last_used_frame=%d, current_cache_size=%d",
                        evicted.lastUsedFrame.get(), pipelineCache.size()
                ));
            }
        }
    }

    /**
     * 淘汰超过帧阈值的旧 Pipeline 条目
     *
     * @param thresholdFrame 帧阈值
     */
    private void evictOldPipelines(long thresholdFrame) {
        if (pipelineCache.isEmpty()) return;

        int evictedCount = 0;

        var iterator = pipelineCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().lastUsedFrame.get() < thresholdFrame) {
                // 销毁超过阈值的旧 Pipeline（Vulkan 资源清理）
                destroyPipelineInternal(entry.getValue().pipeline);
                iterator.remove();
                evictedCount++;
            }
        }

        if (evictedCount > 0) {
            LOGGER.fine(String.format(
                    "Evicted %d old pipelines (threshold_frame=%d), remaining=%d",
                    evictedCount, thresholdFrame, pipelineCache.size()
            ));
        }
    }

    /**
     * 清空所有缓存的 Pipeline
     */
    private void evictAllCachedPipelines() {
        if (pipelineCache.isEmpty()) return;

        int count = 0;
        for (CachedPipeline cached : pipelineCache.values()) {
            // 销毁所有缓存的 Pipeline（Vulkan 资源清理）
            destroyPipelineInternal(cached.pipeline);
            count++;
        }

        LOGGER.info(String.format(
                "Evicted all %d cached pipelines", count
        ));
    }

    /**
     * 淘汰最老的 Descriptor Set 缓存条目（LRU 策略）
     * <p>
     * 当缓存达到上限时调用。
     */
    private void evictOldestDescriptorSets() {
        if (descriptorSetCache.isEmpty()) return;

        // Descriptor Set 是每帧重置的，所以直接清空即可
        // 这里保留接口以备将来扩展
        descriptorSetCache.clear();

        LOGGER.fine("Evicted all descriptor sets (pool reset)");
    }

    /**
     * 清空所有缓存的 Descriptor Set
     */
    private void evictAllCachedDescriptorSets() {
        if (descriptorSetCache.isEmpty()) return;

        int count = descriptorSetCache.size();
        descriptorSetCache.clear();

        LOGGER.info(String.format(
                "Evicted all %d cached descriptor sets", count
        ));
    }

    // ==================== Vulkan 资源管理内部方法 ====================

    /**
     * 销毁 Pipeline（内部方法）
     * <p>
     * 封装 vkDestroyPipeline 调用，用于统一管理 Pipeline 资源销毁。
     *
     * @param pipeline 需要销毁的 Pipeline 句柄
     */
    private void destroyPipelineInternal(long pipeline) {
        if (pipeline == 0) return;
        // 实际集成时调用：vkDestroyPipeline(vkDevice, pipeline, null)
        // 当前为存根实现，仅记录日志
        LOGGER.finer(String.format("Destroying pipeline: 0x%X", pipeline));
    }

    /**
     * 销毁 Descriptor Pool（内部方法）
     * <p>
     * 封装 vkDestroyDescriptorPool 调用，用于在优化器关闭时清理资源。
     */
    private void destroyDescriptorPoolInternal() {
        if (descriptorPool == 0) return;
        // 实际集成时调用：vkDestroyDescriptorPool(vkDevice, descriptorPool, null)
        // 当前为存根实现，仅记录日志
        LOGGER.finer(String.format("Destroying descriptor pool: 0x%X", descriptorPool));
    }

    /**
     * 重置 Descriptor Pool（内部方法）
     * <p>
     * 封装 vkResetDescriptorPool 调用，每帧开始时重置以复用资源。
     */
    private void resetDescriptorPoolInternal() {
        if (descriptorPool == 0) return;
        // 实际集成时调用：vkResetDescriptorPool(vkDevice, descriptorPool, 0)
        // 当前为存根实现，仅记录日志
        LOGGER.finer("Resetting descriptor pool for frame reuse");
    }

    // ==================== 统计和监控内部方法 ====================

    /**
     * 记录 Pipeline 切换到 Pass 级别统计（内部方法）
     * <p>
     * 已被 recordPipelineSwitch() 内联实现，此方法保留用于向后兼容。
     * 用于分析 Pipeline 切换模式，识别频繁切换的 Pass。
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 标识符</li>
     *   <li><b>oldPipeline</b>: long - 旧 Pipeline 句柄</li>
     *   <li><b>newPipeline</b>: long - 新 Pipeline 句柄</li>
     *   <li><b>configHash</b>: long - 配置哈希值</li>
     * </ul>
     *
     * @param passId      RenderPass 标识符
     * @param oldPipeline 旧 Pipeline 句柄
     * @param newPipeline 新 Pipeline 句柄
     * @param configHash  配置哈希值
     */
    private void recordPipelineSwitchToPassStats(long passId, long oldPipeline,
                                                 long newPipeline, long configHash) {
        // 此方法的逻辑已内联到 recordPipelineSwitch() 中
        // 保留此方法仅为了向后兼容（旧代码可能调用它）
        
        // 获取或创建 Pass 统计
        PassPerformanceStats stats = passStatsMap.computeIfAbsent(passId, PassPerformanceStats::new);
        
        // 检测真实切换并更新计数
        if (oldPipeline != newPipeline && oldPipeline != 0) {
            stats.pipelineSwitchCount.incrementAndGet();
            stats.currentPipeline = newPipeline;
            stateSwitchCount.incrementAndGet();
        }

        LOGGER.finest(String.format(
                "[PassStats] Pipeline switch: pass=0x%X, hash=0x%016X, total_switches=%d",
                passId, configHash,
                stats.pipelineSwitchCount.get()
        ));
    }

    /**
     * 追踪纹理绑定模式（内部方法）
     * <p>
     * 已被 recordTextureBind() 内联实现，此方法保留用于向后兼容。
     * 用于优化 Descriptor Set 分配策略，识别高频纹理组合。
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 标识符</li>
     *   <li><b>textureView</b>: long - 纹理视图句柄</li>
     *   <li><b>sampler</b>: long - 采样器句柄</li>
     *   <li><b>descriptorKey</b>: long - Descriptor 组合键</li>
     * </ul>
     *
     * @param passId       RenderPass 标识符
     * @param textureView  纹理视图句柄
     * @param sampler      采样器句柄
     * @param descriptorKey Descriptor 组合键
     */
    private void trackTextureBindingPattern(long passId, long textureView,
                                            long sampler, long descriptorKey) {
        // 此方法的逻辑已内联到 recordTextureBind() 中
        // 保留此方法仅为了向后兼容

        PassPerformanceStats stats = passStatsMap.get(passId);
        if (stats == null) return;

        // 更新纹理绑定计数和模式追踪
        stats.textureBindCount.incrementAndGet();

        if (stats.lastTextureCompositeKey != descriptorKey) {
            stats.lastTextureCompositeKey = descriptorKey;
        }

        LOGGER.finest(String.format(
                "[TexturePattern] Bind: pass=0x%X, key=0x%016X, total_binds=%d",
                passId, descriptorKey,
                stats.textureBindCount.get()
        ));
    }

    /**
     * 检测冗余 Uniform 更新（内部方法）
     * <p>
     * 已被 recordUniformBind() 内联实现，此方法保留用于向后兼容。
     * 监控 Uniform Buffer 更新，检测重复或无效更新。
     *
     * <h3>检测策略：</h3>
     * <pre>
     * ┌─────────────────────────────────────────────┐
     * │  Uniform Update 检测流程：                   │
     * │  1. 提取 (location + offset + length) 指纹   │
     * │  2. 与上次同 location 的指纹比较             │
     * │  3. 相同 → 标记为冗余                        │
     * │  4. 不同 → 更新快照，记录为有效更新          │
     * └─────────────────────────────────────────────┘
     * </pre>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 标识符</li>
     *   <li><b>location</b>: int - Uniform 位置索引</li>
     *   <li><b>offset</b>: int - 数据偏移量</li>
     *   <li><b>length</b>: int - 数据长度</li>
     * </ul>
     *
     * @param passId   RenderPass 标识符
     * @param location Uniform 位置索引
     * @param offset   数据偏移量
     * @param length   数据长度
     */
    private void detectRedundantUniformUpdates(long passId, int location,
                                               int offset, int length) {
        // 此方法的逻辑已内联到 recordUniformBind() 中
        // 保留此方法仅为了向后兼容

        PassPerformanceStats stats = passStatsMap.get(passId);
        if (stats == null) return;

        // 生成数据指纹
        long dataFingerprint = ((long) location << 32) | ((long) offset << 16) | length;

        // 检查冗余
        Long lastFingerprint = stats.lastUniformSnapshot.get(location);
        boolean isRedundant = (lastFingerprint != null && lastFingerprint.equals(dataFingerprint));

        stats.uniformUpdateCount.incrementAndGet();

        if (isRedundant) {
            stats.redundantUniformUpdates.incrementAndGet();
        } else {
            stats.lastUniformSnapshot.put(location, dataFingerprint);
        }

        LOGGER.finest(String.format(
                "[UniformCheck] Update: pass=0x%X, loc=%d, off=%d, len=%d, redundant=%b",
                passId, location, offset, length, isRedundant
        ));
    }

    /**
     * 提交 Pass 优化数据并更新帧级统计（内部方法）
     * <p>
     * 在每个 Pass 结束时汇总该 Pass 的优化指标到帧级统计。
     * 可用于：
     * <ul>
     *   <li>帧结束时生成性能报告</li>
     *   <li>跨帧比较分析性能趋势</li>
     *   <li>触发自适应优化策略调整</li>
     * </ul>
     *
     * <h3>方法参数说明：</h3>
     * <ul>
     *   <li><b>passId</b>: long - RenderPass 标识符</li>
     * </ul>
     *
     * @param passId RenderPass 标识符
     */
    private void submitPassOptimizationData(long passId) {
        PassPerformanceStats stats = passStatsMap.get(passId);
        if (stats == null) return;

        // 计算此 Pass 的冗余率（用于判断是否需要优化）
        int updates = stats.uniformUpdateCount.get();
        int redundant = stats.redundantUniformUpdates.get();
        double redundancyRate = (updates > 0) ? (100.0 * redundant / updates) : 0.0;

        // 如果冗余率超过阈值，输出警告建议
        if (redundancyRate > 30.0 && updates > 10) {
            LOGGER.warning(String.format(
                    "High redundancy rate detected in pass=0x%X: %.1f%% (%d/%d updates redundant)" +
                    "  Suggestion: Consider batching uniform updates or using push constants",
                    passId, redundancyRate, redundant, updates
            ));
        }

        // 如果 Pipeline 切换过于频繁，输出警告
        int switches = stats.pipelineSwitchCount.get();
        if (switches > 5) {
            LOGGER.warning(String.format(
                    "High pipeline switch count in pass=0x%X: %d switches" +
                    "  Suggestion: Consider reordering passes or merging compatible pipelines",
                    passId, switches
            ));
        }

        LOGGER.finest(String.format(
                "[PassData] Submitted optimization data for pass=0x%X:/n" +
                "  pipeline_switches=%d, texture_binds=%d, uniform_updates=%d" +
                "  redundant_rate=%.1f%%, exec_count=%d",
                passId,
                switches,
                stats.textureBindCount.get(),
                updates,
                redundancyRate,
                stats.executionCount.get()
        ));
    }
}
