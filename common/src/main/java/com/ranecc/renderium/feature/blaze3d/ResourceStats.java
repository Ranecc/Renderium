// Renderium - Mixin 基础设施
// 资源统计类 - 记录 GPU 资源的获取、释放和池化命中情况

package com.ranecc.renderium.feature.blaze3d;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 资源统计器 📊
 * <p>
 * 提供细粒度的 GPU 资源生命周期追踪能力，用于监控和分析资源分配行为。
 * 支持记录资源的获取、释放、池化命中等事件，帮助识别资源泄漏和优化池策略。
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  1. 资源获取记录                                             │
 * │     - recordAcquire(descriptor, elapsedNanos)               │
 * │     - 追踪每次资源获取的耗时                                  │
 * ├─────────────────────────────────────────────────────────────┤
 * │  2. 资源释放记录                                             │
 * │     - recordRelease(descriptor, pooled)                     │
 * │     - 区分真实释放和回收到池                                  │
 * ├─────────────────────────────────────────────────────────────┤
 * │  3. 池化命中记录                                             │
 * │     - recordPoolHit(descriptor, reuseCount)                 │
 * │     - 追踪对象池的复用效率                                    │
 * ├─────────────────────────────────────────────────────────────┤
 * │  4. 统计聚合与报告                                           │
 * │     - 按资源类型汇总统计数据                                   │
 * │     - 检测潜在的资源泄漏                                     │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>支持的资源类型：</h3>
 * <ul>
 *   <li>Vulkan Buffer（缓冲区）</li>
 *   <li>Vulkan Image（图像/纹理）</li>
 *   <li>Vulkan Pipeline（管线）</li>
 *   <li>Descriptor Set（描述符集）</li>
 *   <li>Command Buffer（命令缓冲区）</li>
 *   <li>自定义资源类型（可扩展）</li>
 * </ul>
 *
 * <h3>线程安全设计：</h3>
 * <ul>
 *   <li>所有计数器使用 AtomicLong / AtomicInteger</li>
 *   <li>统计表使用 ConcurrentHashMap</li>
 *   <li>无锁读取，支持高并发场景</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public final class ResourceStats {

    private static final Logger LOGGER = Logger.getLogger(ResourceStats.class.getName());

    // ==================== 配置常量 ====================

    /** 默认最大跟踪资源类型数 */
    public static final int MAX_RESOURCE_TYPES = 64;

    /** 泄露检测阈值（毫秒）：资源存活超过此时间且未释放则警告 */
    public static final long LEAK_DETECTION_THRESHOLD_MS = 10_000; // 10 秒

    // ==================== 内部数据结构 ====================

    /**
     * 资源描述符 📋
     * <p>
     * 唯一标识一个资源实例的元信息。
     * 采用 Record 实现，保证不可变性和线程安全。
     *
     * @param resourceType  资源类型（如 "Buffer", "Image", "Pipeline"）
     * @param resourceName  资源名称或标识符（可选，用于调试）
     * @param sizeBytes     资源大小（字节），0 表示未知或不适用
     * @param poolName      所属对象池名称，null 表示非池化资源
     */
    public record ResourceDescriptor(
            /** 资源类型名称 */
            String resourceType,

            /** 资源标识名称 */
            String resourceName,

            /** 资源大小（字节） */
            long sizeBytes,

            /** 对象池名称 */
            String poolName
    ) {
        /**
         * 创建资源描述符
         *
         * @throws IllegalArgumentException 如果 resourceType 为空
         */
        public ResourceDescriptor {
            Objects.requireNonNull(resourceType, "resourceType 不能为 null");
            if (resourceType.isBlank()) {
                throw new IllegalArgumentException("resourceType 不能为空字符串");
            }
        }

        /**
         * 获取简短描述
         *
         * @return 格式化的描述字符串
         */
        public String getShortDescription() {
            return String.format("%s[%s]", resourceType,
                    resourceName != null ? resourceName : "unnamed");
        }

        /**
         * 获取完整描述（包含大小和池信息）
         *
         * @return 格式化的完整描述字符串
         */
        public String getFullDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(getShortDescription());

            if (sizeBytes > 0) {
                sb.append(String.format(" (%s)", formatBytes(sizeBytes)));
            }
            if (poolName != null) {
                sb.append(String.format(" @%s", poolName));
            }

            return sb.toString();
        }
    }

    /**
     * 单个资源类型的统计快照
     * <p>
     * 聚合某一类资源的所有操作统计。
     */
    private static class ResourceTypeStatistics {
        /** 总获取次数 */
        final AtomicLong acquireCount = new AtomicLong(0);

        /** 总释放次数 */
        final AtomicLong releaseCount = new AtomicLong(0);

        /** 当前活跃数量（acquire - release） */
        final AtomicInteger activeCount = new AtomicInteger(0);

        /** 池化命中次数 */
        final AtomicLong poolHitCount = new AtomicLong(0);

        /** 池化未命中次数（需要新建） */
        final AtomicLong poolMissCount = new AtomicLong(0);

        /** 回收到池的次数 */
        final AtomicLong returnToPoolCount = new AtomicLong(0);

        /** 真实销毁次数 */
        final AtomicLong destroyCount = new AtomicLong(0);

        /** 总获取耗时（纳秒） */
        final AtomicLong totalAcquireTimeNanos = new AtomicLong(0);

        /** 总分配大小（字节） */
        final AtomicLong totalAllocatedBytes = new AtomicLong(0);

        /** 当前峰值活跃数 */
        volatile int peakActiveCount = 0;

        /**
         * 重置所有统计字段
         */
        void reset() {
            acquireCount.set(0);
            releaseCount.set(0);
            activeCount.set(0);
            poolHitCount.set(0);
            poolMissCount.set(0);
            returnToPoolCount.set(0);
            destroyCount.set(0);
            totalAcquireTimeNanos.set(0);
            totalAllocatedBytes.set(0);
            peakActiveCount = 0;
        }
    }

    // ==================== 全局状态 ====================

    /** 是否启用资源统计 */
    private static final AtomicBoolean enabled = new AtomicBoolean(true);

    /** 按资源类型分组的统计表 (resourceType → statistics) */
    private static final ConcurrentHashMap<String, ResourceTypeStatistics> typeStatistics =
            new ConcurrentHashMap<>();

    /** 活跃资源追踪表 (用于泄露检测） */
    private static final ConcurrentHashMap<Long, ActiveResourceEntry> activeResources =
            new ConcurrentHashMap<>();

    /** 全局资源 ID 生成器 */
    private static final AtomicLong globalResourceIdGenerator = new AtomicLong(0);

    // ==================== 内部数据结构（活跃资源追踪）====================

    /**
     * 活跃资源条目
     * <p>
     * 用于追踪已获取但尚未释放的资源，支持泄露检测。
     */
    private static class ActiveResourceEntry {
        /** 资源唯一 ID */
        final long resourceId;

        /** 资源描述符 */
        final ResourceDescriptor descriptor;

        /** 获取时间戳（毫秒） */
        final long acquireTimestampMs;

        /** 获取耗时（纳秒） */
        final long acquireElapsedNanos;

        ActiveResourceEntry(long resourceId, ResourceDescriptor descriptor,
                            long acquireTimestampMs, long acquireElapsedNanos) {
            this.resourceId = resourceId;
            this.descriptor = descriptor;
            this.acquireTimestampMs = acquireTimestampMs;
            this.acquireElapsedNanos = acquireElapsedNanos;
        }
    }

    // ==================== 私有构造函数 ====================

    private ResourceStats() {
        throw new UnsupportedOperationException("ResourceStats 是工具类，不允许实例化");
    }

    // ==================== 公共 API：资源获取记录 ====================

    /**
     * 记录一次资源获取事件
     * <p>
     * 当从系统（或对象池）获取一个新资源时调用此方法。
     * 会更新对应资源类型的统计信息并注册到活跃资源追踪表。
     *
     * @param descriptor      资源描述符，包含类型、名称、大小等信息
     * @param elapsedNanos    本次获取操作的耗时（纳秒）
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>descriptor</b>: {@link ResourceDescriptor} - 资源描述符对象，包含：
     *     <ul>
     *       <li>resourceType: 资源类型（如 "Buffer"、"Image"）</li>
     *       <li>resourceName: 资源名称（可为 null）</li>
     *       <li>sizeBytes: 资源大小字节（0 表示未知）</li>
     *       <li>poolName: 所属池名（null 表示非池化）</li>
     *     </ul>
     *   </li>
     *   <li><b>elapsedNanos</b>: long - 获取操作耗时（纳秒精度）</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>long - 分配的资源唯一 ID，用于后续调用 recordRelease 时匹配</li>
     * </ul>
     *
     * @throws IllegalArgumentException 如果 descriptor 为 null
     */
    public static long recordAcquire(ResourceDescriptor descriptor, long elapsedNanos) {
        if (!enabled.get()) return -1;
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor 不能为 null");
        }

        if (elapsedNanos < 0) {
            LOGGER.warning(String.format("负数获取耗时: %s = %d ns",
                    descriptor.getShortDescription(), elapsedNanos));
            elapsedNanos = 0;
        }

        // 生成全局唯一的资源 ID
        long resourceId = globalResourceIdGenerator.incrementAndGet();

        // 更新类型统计
        ResourceTypeStatistics stats = getTypeStatistics(descriptor.resourceType());
        stats.acquireCount.incrementAndGet();
        stats.totalAcquireTimeNanos.addAndGet(elapsedNanos);
        stats.totalAllocatedBytes.addAndGet(descriptor.sizeBytes());

        int currentActive = stats.activeCount.incrementAndGet();
        updatePeakActive(stats, currentActive);

        // 注册到活跃资源追踪表
        activeResources.put(resourceId, new ActiveResourceEntry(
                resourceId, descriptor, System.currentTimeMillis(), elapsedNanos
        ));

        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine(String.format("[ACQUIRE] id=%d %s elapsed=%.3f µs active=%d",
                    resourceId, descriptor.getFullDescription(),
                    elapsedNanos / 1_000.0, currentActive));
        }

        return resourceId;
    }

    // ==================== 公共 API：资源释放记录 ====================

    /**
     * 记录一次资源释放事件
     * <p>
     * 当资源被释放时调用。区分两种释放模式：
     * <ul>
     *   <li><b>pooled=true</b>: 资源回收到对象池（可复用）</li>
     *   <li><b>pooled=false</b>: 资源被真正销毁（如 vkDestroyBuffer）</li>
     * </ul>
     *
     * @param resourceId  资源唯一 ID（由 {@link #recordAcquire} 返回）
     * @param pooled      是否回收到对象池（true=回收复用, false=销毁）
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>resourceId</b>: long - 资源的唯一标识符</li>
     *   <li><b>pooled</b>: boolean - 是否为池化回收（true=返回池中, false=彻底销毁）</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>boolean - 是否成功记录（false 表示未找到对应的活跃资源）</li>
     * </ul>
     */
    public static boolean recordRelease(long resourceId, boolean pooled) {
        if (!enabled.get()) return false;

        // 从活跃资源表中移除
        ActiveResourceEntry entry = activeResources.remove(resourceId);

        if (entry == null) {
            LOGGER.warning(String.format("[RELEASE] 未找到资源 ID=%d，可能已释放或从未获取", resourceId));
            return false;
        }

        // 更新类型统计
        ResourceTypeStatistics stats = getTypeStatistics(entry.descriptor.resourceType());
        stats.releaseCount.incrementAndGet();
        stats.activeCount.decrementAndGet();

        if (pooled) {
            stats.returnToPoolCount.incrementAndGet();
        } else {
            stats.destroyCount.incrementAndGet();
        }

        // 计算资源存活时间用于泄露检测分析
        long lifetimeMs = System.currentTimeMillis() - entry.acquireTimestampMs;

        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine(String.format("[%s] id=%d %s lifetime=%.1f ms remaining_active=%d",
                    pooled ? "POOL" : "DESTROY",
                    resourceId,
                    entry.descriptor.getShortDescription(),
                    lifetimeMs,
                    stats.activeCount.get()));
        }

        // 泄露检测预警
        if (!pooled && lifetimeMs > LEAK_DETECTION_THRESHOLD_MS) {
            LOGGER.warning(String.format(
                    "[LEAK WARNING] 长生命周期资源释放: id=%d %s 存活 %.1f ms",
                    resourceId, entry.descriptor.getFullDescription(), lifetimeMs));
        }

        return true;
    }

    // ==================== 公共 API：池化命中记录 ====================

    /**
     * 记录一次对象池命中事件
     * <p>
     * 当从对象池成功复用一个已有资源时调用。
     * 与 {@link #recordAcquire} 不同，池化命中表示无需创建新资源。
     *
     * @param descriptor   资源描述符
     * @param reuseCount   该资源被复用的累计次数（从 1 开始，首次分配时为 0 或不调用此方法）
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>descriptor</b>: {@link ResourceDescriptor} - 被复用的资源描述符</li>
     *   <li><b>reuseCount</b>: int - 该资源的累计复用次数（>=1）</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值</li>
     * </ul>
     *
     * @throws IllegalArgumentException 如果 descriptor 为 null 或 reuseCount < 1
     */
    public static void recordPoolHit(ResourceDescriptor descriptor, int reuseCount) {
        if (!enabled.get()) return;

        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor 不能为 null");
        }
        if (reuseCount < 1) {
            throw new IllegalArgumentException("reuseCount 必须 >= 1，当前值: " + reuseCount);
        }

        // 更新类型统计
        ResourceTypeStatistics stats = getTypeStatistics(descriptor.resourceType());
        stats.poolHitCount.incrementAndGet();

        // 同时增加活跃计数（因为复用的资源也是活跃的）
        int currentActive = stats.activeCount.incrementAndGet();
        updatePeakActive(stats, currentActive);

        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine(String.format("[POOL HIT] %s reuse_count=%d active=%d",
                    descriptor.getShortDescription(), reuseCount, currentActive));
        }
    }

    /**
     * 记录一次对象池未命中事件
     * <p>
     * 当对象池中没有可用资源、需要创建新资源时调用。
     * 通常在调用 {@link #recordAcquire} 之前调用。
     *
     * @param resourceType 资源类型名称
     */
    public static void recordPoolMiss(String resourceType) {
        if (!enabled.get()) return;
        if (resourceType == null || resourceType.isBlank()) return;

        ResourceTypeStatistics stats = getTypeStatistics(resourceType);
        stats.poolMissCount.incrementAndGet();

        LOGGER.fine(String.format("[POOL MISS] type=%s", resourceType));
    }

    // ==================== 公共 API：状态查询 ====================

    /**
     * 检查资源统计是否已启用
     *
     * @return 如果已启用则返回 true
     */
    public static boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 设置资源统计启用状态
     *
     * @param value 是否启用
     */
    public static void setEnabled(boolean value) {
        enabled.set(value);
        LOGGER.info(String.format("ResourceStats: %s", value ? "已启用" : "已禁用"));
    }

    /**
     * 获取当前活跃资源总数
     *
     * @return 所有类型的活跃资源总和
     */
    public static int getTotalActiveResources() {
        return activeResources.size();
    }

    /**
     * 获取指定类型的当前活跃资源数量
     *
     * @param resourceType 资源类型
     * @return 活跃数量，未找到该类型则返回 0
     */
    public static int getActiveCount(String resourceType) {
        ResourceTypeStatistics stats = typeStatistics.get(resourceType);
        return stats != null ? stats.activeCount.get() : 0;
    }

    /**
     * 获取指定类型的池化命中率
     *
     * @param resourceType 资源类型
     * @return 命中率 (0.0 ~ 1.0)，无数据返回 0.0
     */
    public static double getPoolHitRate(String resourceType) {
        ResourceTypeStatistics stats = typeStatistics.get(resourceType);
        if (stats == null) return 0.0;

        long hits = stats.poolHitCount.get();
        long misses = stats.poolMissCount.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取指定类型的平均获取耗时（微秒）
     *
     * @param resourceType 资源类型
     * @return 平均耗时（微秒），无数据返回 0.0
     */
    public static double getAverageAcquireTimeUs(String resourceType) {
        ResourceTypeStatistics stats = typeStatistics.get(resourceType);
        if (stats == null || stats.acquireCount.get() == 0) return 0.0;

        return (stats.totalAcquireTimeNanos.get() / 1_000.0) / stats.acquireCount.get();
    }

    /**
     * 获取指定类型的总分配内存量
     *
     * @param resourceType 资源类型
     * @return 总字节数
     */
    public static long getTotalAllocatedBytes(String resourceType) {
        ResourceTypeStatistics stats = typeStatistics.get(resourceType);
        return stats != null ? stats.totalAllocatedBytes.get() : 0;
    }

    /**
     * 获取所有已注册的资源类型名称
     *
     * @return 资源类型名称数组
     */
    public static String[] getRegisteredResourceTypes() {
        return typeStatistics.keySet().toArray(new String[0]);
    }

    // ==================== 公共 API：泄露检测 ====================

    /**
     * 检测可能存在泄露的长生命周期资源
     * <p>
     * 扫描所有活跃资源，找出存活时间超过阈值的条目。
     *
     * @param thresholdMs 时间阈值（毫秒）
     * @return 可能泄露的资源描述列表
     */
    public static java.util.List<String> detectPotentialLeaks(long thresholdMs) {
        java.util.List<String> leaks = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();

        for (var entry : activeResources.values()) {
            long lifetime = now - entry.acquireTimestampMs;
            if (lifetime > thresholdMs) {
                leaks.add(String.format("[id=%d] %s 存活 %.1f ms (阈值 %d ms)",
                        entry.resourceId,
                        entry.descriptor.getFullDescription(),
                        lifetime,
                        thresholdMs));
            }
        }

        return leaks;
    }

    // ==================== 公共 API：重置与报告 ====================

    /**
     * 重置所有统计数据
     * <p>
     * 清空所有统计信息和活跃资源追踪。
     */
    public static void reset() {
        for (ResourceTypeStatistics stats : typeStatistics.values()) {
            stats.reset();
        }
        activeResources.clear();

        LOGGER.info("ResourceStats: 所有统计数据已重置");
    }

    /**
     * 生成格式化的资源统计报告
     * <p>
     * 包含各资源类型的详细统计信息和潜在的泄露警告。
     *
     * @return 格式化的报告字符串
     */
    public static String formatReport() {
        StringBuilder report = new StringBuilder();

        report.append("╔══════════════════════════════════════════╗
");
        report.append("║        Resource Statistics Report        ║
");
        report.append("╚══════════════════════════════════════════╝

");

        report.append(String.format("Status: %s | Active Resources: %d | Types: %d

",
                enabled.get() ? "ENABLED" : "DISABLED",
                activeResources.size(),
                typeStatistics.size()));

        if (typeStatistics.isEmpty()) {
            report.append("(No data recorded yet)
");
            return report.toString();
        }

        // 表头
        report.append("┌──────────────────────────────────────────────────────────────────────────────────┐
");
        report.append("│ Type          Acquire  Release  Active  Peak  PoolHit  Miss  HitRate  AvgAcq    Total│
");
        report.append("├──────────────────────────────────────────────────────────────────────────────────┤
");

        for (var entry : typeStatistics.entrySet()) {
            ResourceTypeStatistics stats = entry.getValue();
            double hitRate = getPoolHitRate(entry.getKey());
            double avgAcq = getAverageAcquireTimeUs(entry.getKey());

            report.append(String.format(
                    "│ %-12s %7d  %7d  %6d  %5d  %7d  %5d  %6.1f%%  %7.1fµs  %8s │"
",
                    truncateString(entry.getKey(), 12),
                    stats.acquireCount.get(),
                    stats.releaseCount.get(),
                    stats.activeCount.get(),
                    stats.peakActiveCount,
                    stats.poolHitCount.get(),
                    stats.poolMissCount.get(),
                    hitRate * 100,
                    avgAcq,
                    formatBytes(stats.totalAllocatedBytes.get())
            ));
        }

        report.append("└──────────────────────────────────────────────────────────────────────────────────┘
");

        // 泄露检测
        var leaks = detectPotentialLeaks(LEAK_DETECTION_THRESHOLD_MS);
        if (!leaks.isEmpty()) {
            "\",\nleaks.size(), LEAK_DETECTION_THRESHOLD_MS));
            for (String leak : leaks) {
                report.append(String.format("  - %s
", leak));
            }
        }

        return report.toString();
    }

    // ==================== 内部工具方法 ====================

    /**
     * 获取或创建指定资源类型的统计对象
     *
     * @param resourceType 资源类型名称
     * @return 统计对象
     */
    private static ResourceTypeStatistics getTypeStatistics(String resourceType) {
        return typeStatistics.computeIfAbsent(resourceType, k -> {
            if (typeStatistics.size() >= MAX_RESOURCE_TYPES) {
                throw new IllegalStateException(
                        String.format("资源类型数量超过上限: %d/%d",
                                typeStatistics.size(), MAX_RESOURCE_TYPES)
                );
            }
            return new ResourceTypeStatistics();
        });
    }

    /**
     * 更新峰值活跃数
     *
     * @param stats      统计对象
     * @param current 当前活跃数
     */
    private static void updatePeakActive(ResourceTypeStatistics stats, int current) {
        // 简单的单线程写入即可（峰值不需要精确同步）
        if (current > stats.peakActiveCount) {
            stats.peakActiveCount = current;
        }
    }

    /**
     * 将字节数格式化为人类可读的字符串
     *
     * @param bytes 字节数
     * @return 格式化字符串（如 "1.5 MB", "256 KB"）
     */
    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";

        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double value = bytes;

        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }

        if (unitIndex == 0) {
            return String.format("%d %s", bytes, units[unitIndex]);
        }

        return String.format("%.1f %s", value, units[unitIndex]);
    }

    /**
     * 截断字符串到指定长度
     *
     * @param str        原始字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private static String truncateString(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
}
