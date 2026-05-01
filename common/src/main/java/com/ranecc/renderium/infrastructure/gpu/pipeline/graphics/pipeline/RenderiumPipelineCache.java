// Renderium - Vulkan Pipeline 缓存
// 借鉴 Zink 的 pipeline cache 设计，避免重复创建 VkPipeline

package com.renderium.graphics.pipeline;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vulkan Pipeline 缓存管理器。
 *
 * <p>设计思路借鉴了 Mesa Zink 驱动 (MIT) 的 pipeline cache 机制。
 * Zink 的核心优化之一是：将 OpenGL 状态哈希为唯一键，缓存对应的 VkPipeline 对象，
 * 避免在每帧的每次 Draw 调用时都重新创建昂贵的 Vulkan Pipeline。
 *
 * <h2>Zink 的 Cache 策略（我们采用的变体）</h2>
 * <ol>
 *   <li><b>Hash → Pipeline 映射</b>：RenderPipeline.hashCode() 作为键，VkPipeline handle 作为值</li>
 *   <li><b>延迟创建</b>：首次遇到某个状态组合时才创建 VkPipeline，后续直接复用</li>
 *   <li><b>LRU 淘汰</b>：当缓存超过容量上限时，淘汰最久未使用的 Pipeline</li>
 *   <li><b>降级策略</b>：当 Pipeline 创建失败时返回"通用降级 Pipeline"</li>
 * </ol>
 *
 * <h2>性能影响</h2>
 * <p>Minecraft 中最常见的场景是大量方块使用相同的渲染状态：
 * 相同的 blend mode、相同的 depth test、相同的 cull mode...
 * 如果不缓存，每帧可能创建数千个重复的 VkPipeline（每个耗时数毫秒）。
 * 使用缓存后，这些重复创建完全消除。
 *
 * @see <a href="https://gitlab.freedesktop.org/mesa/mesa/-/tree/main/src/gallium/drivers/zink/zink_pipeline.c">Zink Pipeline Cache Implementation</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RenderiumPipelineCache {

    /** 默认最大缓存条目数 */
    private static final int DEFAULT_MAX_CACHE_SIZE = 4096;

    /** 通用降级 Pipeline 的 handle（当创建失败时使用） */
    public static final long FALLBACK_PIPELINE_HANDLE = 0xFFFFFFFFFFFFFFFFL;

    // ==================== 核心存储 ====================

    /**
     * 主缓存表：RenderPipeline 哈希 → VkPipeline handle
     *
     * <p>使用 ConcurrentHashMap 保证线程安全：
     * 渲染线程读取/写入，后台线程可能执行清理
     */
    private final ConcurrentMap<Integer, Long> pipelineCache;

    /**
     * 访问频率计数器（用于 LRU 淘汰策略）
     *
     * <p>key: RenderPipeline.hashCode(), value: 最后访问时间戳
     */
    private final ConcurrentMap<Integer, AtomicLong> accessTimestamps;

    /**
     * 最大缓存容量
     */
    private final int maxCacheSize;

    /**
     * 缓存命中计数器（性能统计）
     */
    private final AtomicInteger hitCount = new AtomicInteger(0);

    /**
     * 缓存未命中计数器（性能统计）
     */
    private final AtomicInteger missCount = new AtomicInteger(0);

    /**
     * 创建的总 Pipeline 数量（包括被淘汰的）
     */
    private final AtomicInteger totalCreatedCount = new AtomicInteger(0);

    /**
     * 当前缓存中的 Pipeline 数量
     */
    private final AtomicInteger currentSize = new AtomicInteger(0);

    // ==================== 构造与初始化 ====================

    /**
     * 创建默认容量的 Pipeline 缓存
     */
    public RenderiumPipelineCache() {
        this(DEFAULT_MAX_CACHE_SIZE);
    }

    /**
     * 创建指定容量的 Pipeline 缓存
     *
     * @param maxCacheSize 最大缓存条目数
     */
    public RenderiumPipelineCache(int maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
        this.pipelineCache = new ConcurrentHashMap<>(maxCacheSize);
        this.accessTimestamps = new ConcurrentHashMap<>(maxCacheSize);
    }

    // ==================== 核心接口 ====================

    /**
     * 获取或创建对应 RenderPipeline 的 VkPipeline handle
     *
     * <p>这是最核心的方法。调用流程：
     * <ol>
     *   <li>计算 RenderPipeline 的 hashCode()</li>
     *   <li>在缓存中查找</li>
     *   <li>如果命中 → 更新访问时间戳并返回 handle</li>
     *   <li>如果未命中 → 调用 creator 创建新 Pipeline → 存入缓存 → 返回</li>
     * </ol>
     *
     * @param pipeline 渲染管线状态对象（不可变）
     * @param creator 当缓存未命中时的 Pipeline 创建器
     * @return VkPipeline handle（永远不为 null）
     */
    public long getOrCreate(RenderPipeline pipeline, PipelineCreator creator) {
        int hash = pipeline.hashCode();

        // 快速路径：尝试从缓存获取
        Long cachedHandle = pipelineCache.get(hash);
        if (cachedHandle != null) {
            // 缓存命中
            hitCount.incrementAndGet();
            updateAccessTime(hash);
            return cachedHandle;
        }

        // 缓存未命中：需要创建新 Pipeline
        missCount.incrementAndGet();

        // 使用 computeIfAbsent 保证原子性（防止并发创建重复 Pipeline）
        return pipelineCache.computeIfAbsent(hash, k -> {
            // 检查是否需要淘汰旧条目以腾出空间
            evictIfNeeded();

            // 调用创建器生成新的 VkPipeline handle
            long newHandle = createPipelineSafe(pipeline, creator);

            if (newHandle != FALLBACK_PIPELINE_HANDLE) {
                totalCreatedCount.incrementAndGet();
                currentSize.incrementAndGet();
                accessTimestamps.put(hash, new AtomicLong(System.nanoTime()));
            }

            return newHandle;
        });
    }

    /**
     * 仅查找缓存（不触发创建）
     *
     * @param pipeline 渲染管线状态
     * @return VkPipeline handle，如果不存在则返回 -1
     */
    public long lookupOnly(RenderPipeline pipeline) {
        int hash = pipeline.hashCode();
        Long handle = pipelineCache.get(hash);
        if (handle != null) {
            hitCount.incrementAndGet();
            updateAccessTime(hash);
            return handle;
        }
        return -1L; // 未找到
    }

    /**
     * 手动将一个 Pipeline 加入缓存（预加载用）
     *
     * @param pipeline 渲染管线状态
     * @param handle 已创建的 VkPipeline handle
     * @return true 如果成功加入，false 如果已存在
     */
    public boolean preload(RenderPipeline pipeline, long handle) {
        int hash = pipeline.hashCode();
        Long existing = pipelineCache.putIfAbsent(hash, handle);
        if (existing == null) {
            accessTimestamps.put(hash, new AtomicLong(System.nanoTime()));
            currentSize.incrementAndGet();
            totalCreatedCount.incrementAndGet();
            return true;
        }
        return false; // 已存在
    }

    // ==================== 淘汰策略 ====================

    /**
     * 检查并淘汰旧条目（LRU 策略）
     *
     * <p>当缓存接近满时，淘汰最久未访问的条目。
     * 实际淘汰操作在 getOrCreate 内部调用，保证不会在空缓存时浪费 CPU。
     */
    private void evictIfNeeded() {
        if (currentSize.get() < maxCacheSize) {
            return; // 还有空间，不需要淘汰
        }

        // 找到最久未访问的条目
        long oldestTime = Long.MAX_VALUE;
        int oldestKey = -1;

        for (var entry : accessTimestamps.entrySet()) {
            long lastAccess = entry.getValue().get();
            if (lastAccess < oldestTime) {
                oldestTime = lastAccess;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey >= 0) {
            evictEntry(oldestKey);
        }
    }

    /**
     * 淘汰指定条目
     *
     * @param hash 要淘汰的 RenderPipeline 哈希
     */
    private void evictEntry(int hash) {
        pipelineCache.remove(hash);
        accessTimestamps.remove(hash);
        currentSize.decrementAndGet();
        // 注意：这里不销毁 VkPipeline，由外部统一管理生命周期
    }

    /**
     * 清空整个缓存
     *
     * <p>通常在切换后端或重建资源时调用
     */
    public void clear() {
        pipelineCache.clear();
        accessTimestamps.clear();
        currentSize.set(0);
        hitCount.set(0);
        missCount.set(0);
    }

    /**
     * 按需清理已过期的条目
     *
     * @param maxAgeNanos 最大存活时间（纳秒），超过此时间的条目将被淘汰
     * @return 淘汰的条目数量
     */
    public int expireEntries(long maxAgeNanos) {
        long now = System.nanoTime();
        int evicted = 0;

        var iterator = accessTimestamps.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if ((now - entry.getValue().get()) > maxAgeNanos) {
                pipelineCache.remove(entry.getKey());
                iterator.remove();
                currentSize.decrementAndGet();
                evicted++;
            }
        }

        return evicted;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 安全地创建 Pipeline（捕获异常并返回降级 handle）
     */
    private long createPipelineSafe(RenderPipeline pipeline, PipelineCreator creator) {
        try {
            return creator.create(pipeline);
        } catch (Exception e) {
            // 创建失败：返回通用降级 Pipeline
            // 这可能是由于 Vulkan 设备限制、内存不足等原因
            System.err.println("[Renderium] Pipeline 创建失败，使用降级模式: " + e.getMessage());
            return FALLBACK_PIPELINE_HANDLE;
        }
    }

    /**
     * 更新访问时间戳
     */
    private void updateAccessTime(int hash) {
        AtomicLong timestamp = accessTimestamps.get(hash);
        if (timestamp != null) {
            timestamp.set(System.nanoTime());
        }
    }

    // ==================== 统计信息 ====================

    /**
     * 获取缓存命中率（百分比，0-100）
     */
    public double getHitRate() {
        int hits = hitCount.get();
        int misses = missCount.get();
        int total = hits + misses;
        if (total == 0) return 0.0;
        return (double) hits / total * 100.0;
    }

    /**
     * 获取当前缓存大小
     */
    public int size() {
        return currentSize.get();
    }

    /**
     * 获取最大缓存容量
     */
    public int getMaxSize() {
        return maxCacheSize;
    }

    /**
     * 获取总创建次数
     */
    public int getTotalCreatedCount() {
        return totalCreatedCount.get();
    }

    /**
     * 获取详细统计信息
     */
    public String getStatistics() {
        return String.format(
                "PipelineCache{size=%d/%d, hits=%d, misses=%d, hitRate=%.1f%%, created=%d}",
                size(), getMaxSize(),
                hitCount.get(), missCount.get(),
                getHitRate(),
                getTotalCreatedCount()
        );
    }

    // ==================== 接口定义 ====================

    /**
     * Pipeline 创建器函数式接口
     *
     * <p>由 VulkanBackend 实现，负责将 RenderPipeline 状态翻译为实际的 VkPipeline 创建调用。
     */
    @FunctionalInterface
    public interface PipelineCreator {

        /**
         * 根据 RenderPipeline 状态创建 VkPipeline
         *
         * @param pipeline 不可变的渲染管线状态
         * @return 新创建的 VkPipeline handle（非零表示有效）
         * @throws Exception 如果创建失败
         */
        long create(RenderPipeline pipeline) throws Exception;
    }

    @Override
    public String toString() {
        return getStatistics();
    }
}
