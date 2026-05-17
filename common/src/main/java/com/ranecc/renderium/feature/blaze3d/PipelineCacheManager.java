// Renderium - Blaze3D 优化模块
// Pipeline Cache 管理器 - 实现兼容模式 Pipeline 缓存与 LRU 淘汰策略

package com.ranecc.renderium.feature.blaze3d;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Pipeline Cache 管理器
 * <p>
 * 实现 {@code compatibility-mode-optimization.md} §2.2 中的 Pipeline Cache 策略：
 * <ul>
 *   <li>配置哈希匹配，避免重复创建管线</li>
 *   <li>LRU 淘汰策略，控制缓存大小</li>
 *   <li>引用计数追踪复用情况</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class PipelineCacheManager {

    private static final Logger LOGGER = Logger.getLogger(PipelineCacheManager.class.getName());

    /** 默认最大缓存 Pipeline 数量 */
    public static final int MAX_CACHED_PIPELINES = 64;

    // ==================== 状态标志 ====================

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== 内部数据结构 ====================

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

    // ==================== 统计字段 ====================

    private final AtomicLong pipelineCacheHits = new AtomicLong(0);
    private final AtomicLong pipelineCacheMisses = new AtomicLong(0);
    private final AtomicLong totalCreatedPipelines = new AtomicLong(0);

    // ==================== 状态同步方法 ====================

    /**
     * 设置启用状态
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 设置初始化状态
     *
     * @param initialized 是否已初始化
     */
    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    /**
     * 清空所有统计计数
     */
    public void resetStats() {
        pipelineCacheHits.set(0);
        pipelineCacheMisses.set(0);
        totalCreatedPipelines.set(0);
    }

    /**
     * 清空缓存
     */
    public void clear() {
        pipelineCache.clear();
    }

    // ==================== 核心 API ====================

    /**
     * 获取或创建 Pipeline（带缓存）
     * <p>
     * 这是核心方法，实现 {@code compatibility-mode-optimization.md} §2.2 中的 Pipeline Cache 策略：
     * <pre>
     * 1. 计算配置哈希
     * 2. 检查缓存是否命中
     * 3. 命中 → 直接返回缓存的 Pipeline
     * 4. 未命中 → 创建新 Pipeline 并加入缓存
     * </pre>
     *
     * @param vkDevice            Vulkan 设备句柄
     * @param pipelineCacheHandle Vulkan Pipeline Cache 句柄
     * @param configHash          管线配置的哈希值
     * @param currentFrame        当前帧号（用于 LRU 更新）
     * @return Pipeline 句柄，失败返回 0
     */
    public long getOrCreatePipeline(long vkDevice, long pipelineCacheHandle,
                                    long configHash, int currentFrame) {
        if (!enabled || !initialized) return 0;

        // 1. 尝试从缓存获取
        CachedPipeline cached = pipelineCache.get(configHash);

        if (cached != null) {
            // 缓存命中
            cached.lastUsedFrame.set(currentFrame);
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
                    newPipeline, configHash, currentFrame
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

    // ==================== 查询 API ====================

    /**
     * 获取当前 Pipeline 缓存命中率
     *
     * @return 命中率 (0.0 ~ 1.0)
     */
    public double getHitRate() {
        long hits = pipelineCacheHits.get();
        long misses = pipelineCacheMisses.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取当前 Pipeline 缓存大小
     *
     * @return 缓存条目数
     */
    public int getCacheSize() {
        return pipelineCache.size();
    }

    /**
     * 获取总创建 Pipeline 数量
     *
     * @return 创建总数
     */
    public long getTotalCreatedPipelines() {
        return totalCreatedPipelines.get();
    }

    /**
     * 获取缓存命中次数
     *
     * @return 命中次数
     */
    public long getCacheHits() {
        return pipelineCacheHits.get();
    }

    /**
     * 获取缓存未命中次数
     *
     * @return 未命中次数
     */
    public long getCacheMisses() {
        return pipelineCacheMisses.get();
    }

    // ==================== 内部实现方法 ====================

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
    public void evictOldPipelines(long thresholdFrame) {
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
    public void evictAllCachedPipelines() {
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
}
