// Renderium - Blaze3D 优化模块
// Descriptor Set 管理器 - 实现兼容模式 Descriptor Pool 复用策略

package com.ranecc.renderium.feature.blaze3d;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Descriptor Set 管理器
 * <p>
 * 实现 {@code compatibility-mode-optimization.md} §2.3 中的 Descriptor Set 复用策略：
 * <ul>
 *   <li>预分配 Descriptor Pool</li>
 *   <li>组合键匹配，避免重复分配</li>
 *   <li>每帧重置，零碎片</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class DescriptorSetManager {

    private static final Logger LOGGER = Logger.getLogger(DescriptorSetManager.class.getName());

    /** 默认最大 Descriptor Set 数量 */
    public static final int MAX_DESCRIPTOR_SETS = 32;

    // ==================== 状态标志 ====================

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== 内部数据结构 ====================

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

    private final AtomicLong descriptorSetHits = new AtomicLong(0);
    private final AtomicLong descriptorSetMisses = new AtomicLong(0);
    private final AtomicLong totalAllocatedDescriptorSets = new AtomicLong(0);

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
        descriptorSetHits.set(0);
        descriptorSetMisses.set(0);
        totalAllocatedDescriptorSets.set(0);
    }

    /**
     * 清空缓存
     */
    public void clear() {
        descriptorSetCache.clear();
    }

    // ==================== 核心 API ====================

    /**
     * 获取或分配 Descriptor Set（带复用）
     * <p>
     * 这是核心方法，实现 {@code compatibility-mode-optimization.md} §2.3 中的 Descriptor Set 复用策略：
     * <pre>
     * 1. 构建组合键（textureView ^ sampler ^ uniformBuffer）
     * 2. 检查缓存是否命中
     * 3. 命中 → 直接返回缓存的 Descriptor Set
     * 4. 未命中 → 从 Pool 中分配新的 Descriptor Set
     * </pre>
     *
     * @param vkDevice      Vulkan 设备句柄
     * @param textureView   纹理视图句柄
     * @param sampler       采样器句柄
     * @param uniformBuffer Uniform Buffer 句柄
     * @param currentFrame  当前帧号（用于调试）
     * @return Descriptor Set 句柄，失败返回 0
     */
    public long getOrCreateDescriptorSet(long vkDevice,
                                         long textureView, long sampler,
                                         long uniformBuffer, int currentFrame) {
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
                    newDescriptorSet, compositeKey, currentFrame
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
     * 根据 {@code compatibility-mode-optimization.md} §2.3 中的建议：
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

    // ==================== 查询 API ====================

    /**
     * 获取当前 Descriptor Set 缓存命中率
     *
     * @return 命中率 (0.0 ~ 1.0)
     */
    public double getHitRate() {
        long hits = descriptorSetHits.get();
        long misses = descriptorSetMisses.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取当前 Descriptor Set 缓存大小
     *
     * @return 缓存条目数
     */
    public int getCacheSize() {
        return descriptorSetCache.size();
    }

    /**
     * 获取总分配 Descriptor Set 数量
     *
     * @return 分配总数
     */
    public long getTotalAllocatedDescriptorSets() {
        return totalAllocatedDescriptorSets.get();
    }

    /**
     * 获取缓存命中次数
     *
     * @return 命中次数
     */
    public long getCacheHits() {
        return descriptorSetHits.get();
    }

    /**
     * 获取缓存未命中次数
     *
     * @return 未命中次数
     */
    public long getCacheMisses() {
        return descriptorSetMisses.get();
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
    public void evictAllCachedDescriptorSets() {
        if (descriptorSetCache.isEmpty()) return;

        int count = descriptorSetCache.size();
        descriptorSetCache.clear();

        LOGGER.info(String.format(
                "Evicted all %d cached descriptor sets", count
        ));
    }

    // ==================== Vulkan 资源管理内部方法 ====================

    /**
     * 销毁 Descriptor Pool（内部方法）
     * <p>
     * 封装 vkDestroyDescriptorPool 调用，用于在优化器关闭时清理资源。
     */
    public void destroyDescriptorPoolInternal() {
        if (descriptorPool == 0) return;
        // 实际集成时调用：vkDestroyDescriptorPool(vkDevice, descriptorPool, null)
        // 当前为存根实现，仅记录日志
        LOGGER.finer(String.format("Destroying descriptor pool: 0x%X", descriptorPool));
        descriptorPool = 0;
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

    /**
     * 获取 Descriptor Pool 句柄
     *
     * @return descriptorPool 句柄
     */
    public long getDescriptorPool() {
        return descriptorPool;
    }

    /**
     * 设置 Descriptor Pool 句柄
     *
     * @param descriptorPool Vulkan Descriptor Pool 句柄
     */
    public void setDescriptorPool(long descriptorPool) {
        this.descriptorPool = descriptorPool;
    }
}
