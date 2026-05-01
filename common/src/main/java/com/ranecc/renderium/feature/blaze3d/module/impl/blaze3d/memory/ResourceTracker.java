// Renderium - Blaze3D VMA 渐进式清理模块
// LRU 资源追踪器 - 追踪资源使用频率，优先清理冷数据

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * LRU 资源追踪器。
 * <p>
 * 追踪 GPU 资源的访问频率和最后访问时间，
 * 为 {@link GradualCleanupStrategy} 提供清理候选列表。
 * 核心思想: <b>优先清理最近最少使用(LRU)的冷数据</b></p>
 *
 * <h2>核心功能：</h2>
 * <ul>
 *   <li>记录每次资源访问 (时间戳 + 计数)</li>
 *   <li>维护 LRU 排序队列</li>
 *   <li>区分热数据和冷数据</li>
 *   <li>按类型分类统计</li>
 * </ul>
 *
 * <h2>热数据判定规则：</h2>
 * <ol>
 *   <li>最近 5 秒内访问过 → 热数据</li>
 *   <li>访问次数 &gt; 100 且 30 秒内有访问 → 高频热数据</li>
 *   <li>其他 → 冷数据 (可被清理)</li>
 * </ol>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * ResourceTracker tracker = ResourceTracker.getInstance();
 *
 * // 记录资源访问
 * tracker.recordAccess(textureId, ResourceType.CACHED_TEXTURE);
 * tracker.recordAccess(meshId, ResourceType.DISTANT_CHUNK_MESH);
 *
 * // 获取清理候选 (LRU 顺序, 目标 50MB)
 * List<CleanupCandidate> candidates = tracker.getCleanupCandidates(
 *     50 * 1024 * 1024,
 *     ResourceType.CACHED_TEXTURE,
 *     ResourceType.DISTANT_CHUNK_MESH
 * );
 *
 * // 清理后通知追踪器
 * tracker.onResourceEvicted(evictedId);
 * }</pre>
 *
 * @see ResourceType 资源类型定义
 * @see GradualCleanupStrategy 使用本类获取清理候选
 * @since 2.0.0
 */
public final class ResourceTracker {

    private static final Logger LOGGER = Logger.getLogger("Renderium-ResourceTracker");

    /** 单例实例 */
    private static volatile ResourceTracker instance;

    /** 资源访问记录表 (resourceId → 访问信息) */
    private final ConcurrentHashMap<Long, ResourceAccessInfo> accessRecords = new ConcurrentHashMap<>();

    /** LRU 排序集合 (按最后访问时间升序排列，头部最久未用) */
    private final TreeSet<ResourceAccessInfo> lruQueue = new TreeSet<>(
            Comparator.comparingLong(ResourceAccessInfo::getLastAccessTime)
                    .thenComparingLong(ResourceAccessInfo::getResourceId)
    );

    /** 按资源类型分组的 ID 集合 */
    private final EnumMap<ResourceType, Set<Long>> resourcesByType =
            new EnumMap<>(ResourceType.class);

    /** 全局访问计数器 (用于生成唯一排序键) */
    private final AtomicLong globalSequence = new AtomicLong(0);

    /** 热数据阈值: 最近 N 毫秒内访问 = 热 */
    private volatile long hotDataThresholdMs = 5000L;

    /** 高频访问阈值: 访问次数超过此值且近期有访问 = 高频热数据 */
    private volatile int highFrequencyThreshold = 100;

    /** 高频热数据的近期窗口 (毫秒) */
    private volatile long highFrequencyWindowMs = 30_000L;

    private ResourceTracker() {
        for (ResourceType type : ResourceType.values()) {
            resourcesByType.put(type, ConcurrentHashMap.newKeySet());
        }
    }

    public static synchronized ResourceTracker getInstance() {
        if (instance == null) {
            instance = new ResourceTracker();
        }
        return instance;
    }

    // ==================== 公共 API: 访问记录 ====================

    /**
     * 记录资源访问。
     * 更新最后访问时间和访问计数，调整 LRU 队列位置。
     *
     * @param resourceId 资源唯一标识符 (非零)
     * @param type       资源类型
     */
    public void recordAccess(long resourceId, ResourceType type) {
        if (resourceId == 0L) return;

        ResourceAccessInfo info = accessRecords.compute(resourceId, (id, existing) -> {
            if (existing != null) {
                // 已存在: 从 LRU 队列移除 (后续重新插入到尾部)
                lruQueue.remove(existing);
                existing.touch();
                return existing;
            }
            // 新记录
            ResourceAccessInfo newInfo = new ResourceAccessInfo(id, type, globalSequence.incrementAndGet());
            resourcesByType.get(type).add(id);
            return newInfo;
        });

        // 重新插入到 LRU 队列尾部 (最近访问)
        lruQueue.add(info);
    }

    /**
     * 批量记录多个资源的访问
     *
     * @param resourceIds 资源 ID 数组
     * @param type        资源类型
     */
    public void recordBatchAccess(long[] resourceIds, ResourceType type) {
        if (resourceIds == null) return;
        for (long id : resourceIds) {
            recordAccess(id, type);
        }
    }

    // ==================== 公共 API: 清理查询 ====================

    /**
     * 获取清理候选列表 (LRU 顺序)。
     * 只返回冷数据（非热数据），按 LRU 顺序排列。
     *
     * @param targetBytes 目标释放字节数 (达到此量即停止)
     * @param types      限定搜索的资源类型 (不指定则搜索所有)
     * @return 清理候选列表 (有序, 累计大小可能略超 targetBytes)
     */
    public List<CleanupCandidate> getCleanupCandidates(long targetBytes, ResourceType... types) {
        List<CleanupCandidate> candidates = new ArrayList<>();
        long accumulatedBytes = 0;
        Set<ResourceType> typeFilter = types.length > 0 ? new HashSet<>(Arrays.asList(types)) : null;
        long now = System.currentTimeMillis();

        synchronized (lruQueue) {
            for (ResourceAccessInfo info : lruQueue) {
                // 类型过滤
                if (typeFilter != null && !typeFilter.contains(info.type)) {
                    continue;
                }

                // 跳过热数据
                if (isHotData(info, now)) {
                    continue; // 热数据跳过
                }

                long size = estimateResourceSize(info.resourceId);
                candidates.add(new CleanupCandidate(
                        info.resourceId, info.type, size,
                        info.lastAccessTime, info.accessCount
                ));

                accumulatedBytes += size;
                if (accumulatedBytes >= targetBytes && candidates.size() >= 5) {
                    break; // 达到目标且有足够数量
                }
            }
        }

        if (!candidates.isEmpty()) {
            LOGGER.fine(String.format("生成 %d 个清理候选, 目标 %d KB, 累计 %d KB",
                    candidates.size(), targetBytes / 1024, accumulatedBytes / 1024));
        }

        return candidates;
    }

    /**
     * 判断指定资源是否为热数据 (最近刚被访问过)
     *
     * @param resourceId 资源 ID
     * @return true 如果是热数据 (不应被清理)
     */
    public boolean isRecentlyAccessed(long resourceId) {
        ResourceAccessInfo info = accessRecords.get(resourceId);
        if (info == null) return false;
        return isHotData(info, System.currentTimeMillis());
    }

    /**
     * 资源被清理后的回调 (从追踪器中移除)
     *
     * @param resourceId 被清理的资源 ID
     */
    public void onResourceEvicted(long resourceId) {
        ResourceAccessInfo removed = accessRecords.remove(resourceId);
        if (removed != null) {
            lruQueue.remove(removed);
            resourcesByType.get(removed.type).remove(resourceId);
        }
    }

    // ==================== 统计查询 ====================

    /** 获取当前追踪的资源总数 */
    public int getTrackedCount() {
        return accessRecords.size();
    }

    /** 获取指定类型的资源数量 */
    public int getCountByType(ResourceType type) {
        Set<Long> ids = resourcesByType.get(type);
        return ids != null ? ids.size() : 0;
    }

    /** 获取指定类型的估算总大小 (字节) */
    public long getBytesByType(ResourceType type) {
        Set<Long> ids = resourcesByType.get(type);
        if (ids == null || ids.isEmpty()) return 0;

        long total = 0;
        for (Long id : ids) {
            total += estimateResourceSize(id);
        }
        return total;
    }

    /**
     * 获取完整状态报告 (用于调试/监控)
     */
    public String getStatusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Resource Tracker Status ===\n");
        sb.append(String.format("Tracked Resources: %d\n", accessRecords.size()));
        sb.append(String.format("LRU Queue Size: %d\n", lruQueue.size()));

        sb.append("\nBy Type:\n");
        for (ResourceType type : ResourceType.values()) {
            int count = getCountByType(type);
            long bytes = getBytesByType(type);
            if (count > 0) {
                sb.append(String.format("  %-20s %6d items %8d KB\n",
                        type.description, count, bytes / 1024));
            }
        }

        // 热数据统计
        long now = System.currentTimeMillis();
        int hotCount = 0;
        int coldCount = 0;
        for (ResourceAccessInfo info : accessRecords.values()) {
            if (isHotData(info, now)) hotCount++;
            else coldCount++;
        }
        sb.append(String.format("\nHot Data: %d, Cold Data: %d\n", hotCount, coldCount));

        return sb.toString();
    }

    /**
     * 清除所有追踪记录 (重置状态)
     */
    public void clear() {
        accessRecords.clear();
        lruQueue.clear();
        for (Set<Long> set : resourcesByType.values()) {
            set.clear();
        }
        LOGGER.info("Resource Tracker 已清除所有记录");
    }

    // ==================== 内部实现 ====================

    /** 判断是否为热数据 */
    private boolean isHotData(ResourceAccessInfo info, long now) {
        long timeSinceLastAccess = now - info.lastAccessTime;

        // 规则1: 最近 N 毫秒内访问过
        if (timeSinceLastAccess < hotDataThresholdMs) {
            return true;
        }

        // 规则2: 高频访问 + 近期有活动
        if (info.accessCount > highFrequencyThreshold &&
                timeSinceLastAccess < highFrequencyWindowMs) {
            return true;
        }

        return false;
    }

    /**
     * 估算资源大小 (占位实现 — 实际应从 VmaAllocationInfo 获取)
     * 返回一个合理的默认值供排序参考
     */
    private long estimateResourceSize(long resourceId) {
        // TODO: 从 VMA 分配信息中获取真实大小
        // 这里返回基于 resourceId 哈希的伪随机值作为近似
        // 实际实现应维护 resourceId → VmaAllocation 的映射
        return ((resourceId * 2654435769L) >>> 16) % (1024 * 1024) + 4096; // 4KB ~ 1MB 之间
    }

    // ==================== 配置 API ====================

    /** 设置热数据时间阈值 (默认 5000ms) */
    public void setHotDataThresholdMs(long ms) {
        this.hotDataThresholdMs = Math.max(1000, ms); // 最少 1 秒
    }

    /** 设置高频访问次数阈值 (默认 100) */
    public void setHighFrequencyThreshold(int threshold) {
        this.highFrequencyThreshold = Math.max(10, threshold);
    }

    // ==================== 内部数据结构 ====================

    /**
     * 资源访问信息
     *
     * @param resourceId     资源唯一标识
     * @param type           资源类型
     * @param creationSeq   创建序号 (用于 LRU 排序稳定性)
     */
    public static class ResourceAccessInfo {
        public final long resourceId;
        public final ResourceType type;
        public final long creationSeq; // 创建时的全局序号
        public volatile long lastAccessTime;
        public volatile long accessCount;
        public volatile long totalBytesAccessed;

        ResourceAccessInfo(long resourceId, ResourceType type, long creationSeq) {
            this.resourceId = resourceId;
            this.type = type;
            this.creationSeq = creationSeq;
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount = 1;
            this.totalBytesAccessed = 0;
        }

        /** 更新访问时间和计数 */
        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount++;
        }

        /** 获取最后访问时间 */
        public long getLastAccessTime() { return lastAccessTime; }

        /** 获取资源 ID */
        public long getResourceId() { return resourceId; }
    }

    /**
     * 清理候选
     *
     * @param resourceId   资源 ID
     * @param type         资源类型
     * @param size         估算大小 (字节)
     * @param lastAccess   最后访问时间戳
     * @param accessCount  总访问次数
     */
    public record CleanupCandidate(
            long resourceId,
            ResourceType type,
            long size,
            long lastAccess,
            long accessCount
    ) {}
}
