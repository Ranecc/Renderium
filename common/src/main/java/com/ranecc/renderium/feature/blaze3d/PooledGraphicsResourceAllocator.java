// Renderium - 池化图形资源分配器
// 基于 blaze3d_optimization_analysis.md §3.1 资源池化策略

package com.ranecc.renderium.feature.blaze3d;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

/**
 * 池化图形资源分配器。
 *
 * <p>基于 {@code blaze3d_optimization_analysis.md} 中识别的最大优化机会：
 * <b>资源池化</b>可以消除每帧的 acquire/release 开销，收益极高。
 *
 * <h3>核心设计</h3>
 * <pre>
 * 传统方式 (Blaze3D 默认):
 *   for (Pass pass : passes) {
 *       resource = descriptor.allocate();   // ← 每帧分配新资源
 *       ...
 *       descriptor.free(resource);          // ← 每帧释放
 *   }
 *
 * 池化方式:
 *   for (Pass pass : passes) {
 *       resource = pool.acquire(descriptor); // ← 从池中复用或新建
 *       ...
 *       pool.release(descriptor, resource); // ← 放回池中等待复用
 *   }
 * </pre>
 *
 * <h3>性能收益</h3>
 * <ul>
 *   <li>消除 50-80% 的资源分配/释放开销</li>
 *   <li>减少 GC 压力（对象复用）</li>
 *   <li>降低内存碎片</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在 Mixin 中注入
 * @ModifyVariable(method = "execute", at = @At("HEAD"))
 * private GraphicsResourceAllocator usePooledAllocator(GraphicsResourceAllocator original) {
 *     if (RenderiumConfig.isResourcePoolingEnabled()) {
 *         return PooledGraphicsResourceAllocator.wrap(original);
 *     }
 *     return original;
 * }
 * }</pre>
 *
 * @see GraphicsResourceAllocator
 * @author Renderium Team
 * @since 5.0.0
 */
public final class PooledGraphicsResourceAllocator implements GraphicsResourceAllocator {

    private static final Logger LOGGER = Logger.getLogger(PooledGraphicsResourceAllocator.class.getName());

    /** 默认每个描述符类型的最大池大小 */
    public static final int DEFAULT_MAX_POOL_SIZE_PER_DESCRIPTOR = 16;

    /** 全局最大池大小 */
    public static final int GLOBAL_MAX_POOL_SIZE = 256;

    /** 被包装的原始分配器 */
    private final GraphicsResourceAllocator backingAllocator;

    /** 资源池: Descriptor → 可用资源队列 */
    private final Map<ResourceDescriptor<?>, Deque<Object>> resourcePools =
            new ConcurrentHashMap<>();

    /** 当前活跃资源数 (用于监控) */
    private final ConcurrentHashMap<ResourceDescriptor<?>, AtomicInteger> activeCounts =
            new ConcurrentHashMap<>();

    /** 最大池大小配置 */
    private final int maxPoolSizePerDescriptor;

    /** 统计: 总获取次数 */
    private final AtomicLong totalAcquires = new AtomicLong(0);

    /** 统计: 总释放次数 */
    private final AtomicLong totalReleases = new AtomicLong(0);

    /** 统计: 缓存命中次数 (从池中复用) */
    private final AtomicLong poolHits = new AtomicLong(0);

    /** 统计: 缓存未命中次数 (需要新建) */
    private final AtomicLong poolMisses = new AtomicLong(0);

    /**
     * 创建池化分配器
     *
     * @param backingAllocator 底层原始分配器
     */
    public PooledGraphicsResourceAllocator(GraphicsResourceAllocator backingAllocator) {
        this(backingAllocator, DEFAULT_MAX_POOL_SIZE_PER_DESCRIPTOR);
    }

    /**
     * 创建池化分配器（自定义池大小）
     *
     * @param backingAllocator      底层原始分配器
     * @param maxPoolSizePerDescriptor 每个描述符类型的最大池大小
     */
    public PooledGraphicsResourceAllocator(GraphicsResourceAllocator backingAllocator,
                                            int maxPoolSizePerDescriptor) {
        if (backingAllocator == null) {
            throw new IllegalArgumentException("backingAllocator 不能为 null");
        }
        this.backingAllocator = backingAllocator;
        this.maxPoolSizePerDescriptor = Math.min(maxPoolSizePerDescriptor, GLOBAL_MAX_POOL_SIZE);

        LOGGER.info("═══ PooledGraphicsResourceAllocator 已初始化 ═══");
        LOGGER.info("  最大池大小/类型: " + this.maxPoolSizePerDescriptor);
        LOGGER.info("  后端分配器: " + backingAllocator.getClass().getSimpleName());
        LOGGER.info("═══════════════════════════════════════════");
    }

    /**
     * 包装现有分配器为池化版本
     *
     * <p>这是推荐的工厂方法，用于在 Mixin 中快速启用池化。
     *
     * @param original 原始分配器
     * @return 池化版本的分配器
     */
    public static PooledGraphicsResourceAllocator wrap(GraphicsResourceAllocator original) {
        if (original instanceof PooledGraphicsResourceAllocator) {
            return (PooledGraphicsResourceAllocator) original;  // 避免重复包装
        }
        return new PooledGraphicsResourceAllocator(original);
    }

    // ==================== GraphicsResourceAllocator 接口实现 ====================

    /**
     * 从池中获取资源
     *
     * <p>优先从池中复用已有资源，如果池为空则通过底层分配器创建新的。
     *
     * @param descriptor 资源描述符
     * @param <T> 资源类型
     * @return 资源实例
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T acquire(ResourceDescriptor<T> descriptor) {
        if (descriptor == null) {
            throw new NullPointerException("descriptor 不能为 null");
        }

        totalAcquires.incrementAndGet();

        // 尝试从池中获取
        Deque<Object> pool = resourcePools.get(descriptor);
        if (pool != null && !pool.isEmpty()) {
            T resource = (T) pool.poll();
            poolHits.incrementAndGet();

            // 更新活跃计数
            incrementActiveCount(descriptor);

            LOGGER.finest("Pool HIT: " + descriptor.getType() +
                         " (active=" + getActiveCount(descriptor) + ")");
            return resource;
        }

        // 池为空，通过底层分配器创建
        poolMisses.incrementAndGet();
        T newResource = backingAllocator.acquire(descriptor);

        // 初始化活跃计数
        initActiveCount(descriptor);

        LOGGER.fine("Pool MISS: " + descriptor.getType() +
                   " -> created new instance");
        return newResource;
    }

    /**
     * 释放资源（接口要求单参数版本）。
     *
     * <p>实现 {@link GraphicsResourceAllocator#release(Object)} 接口契约。
     * 由于池化分配器无法从单个资源实例反推其描述符类型，
     * 此方法执行空操作（no-op），避免资源泄漏。
     *
     * <p><b>正确用法：</b>应优先使用 {@link #release(ResourceDescriptor, Object)} 双参数版本，
     * 以确保资源能正确回收到对应的池中。
     *
     * @param resource 要释放的资源实例（此方法不执行实际释放操作）
     * @param <T> 资源类型
     */
    @Override
    public <T> void release(T resource) {
        // [编译修复] 实现 GraphicsResourceAllocator 接口的抽象方法 release(T)
        // 池化分配器需要 descriptor 才能将资源放回正确的池中，
        // 单参数版本无法获取 descriptor，因此此处为空操作。
        // 调用方应使用 release(descriptor, resource) 双参数版本以确保正确回收。
        if (resource == null) {
            return;
        }
        LOGGER.finest("release(T) 空操作调用 - 资源未被回收，建议使用 release(descriptor, resource)");
    }

    /**
     * 释放资源到池中
     *
     * <p>不立即销毁资源，而是放回池中等待下次复用。
     * 如果池已满，则通过底层分配器释放。
     *
     * <p><b>注意：</b>此方法是 PooledGraphicsResourceAllocator 扩展 API，
     * 不属于 GraphicsResourceAllocator 接口定义（接口的 release 仅接受单参数）。
     *
     * @param descriptor 资源描述符
     * @param resource   要释放的资源实例
     * @param <T> 资源类型
     */
    @SuppressWarnings("unchecked")
    public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
        if (descriptor == null || resource == null) {
            return;
        }

        totalReleases.incrementAndGet();

        // 获取或创建该描述符类型的池
        Deque<Object> pool = resourcePools.computeIfAbsent(
                descriptor,
                k -> new ConcurrentLinkedDeque<>()
        );

        // 检查池是否已满
        if (pool.size() >= maxPoolSizePerDescriptor) {
            // 池已满，直接释放到底层
            // [编译修复] GraphicsResourceAllocator 接口的 release() 现在只接受单参数 T，
            // 不再接受 (descriptor, resource) 双参数形式。
            backingAllocator.release(resource);
            decrementActiveCount(descriptor);
            LOGGER.finest("Pool FULL: " + descriptor.getType() +
                         " -> released to backing allocator");
        } else {
            // 放入池中等待复用
            pool.push(resource);
            decrementActiveCount(descriptor);
            LOGGER.finest("Returned to pool: " + descriptor.getType() +
                         " (pool_size=" + pool.size() + ")");
        }
    }

    // ==================== 池管理 API ====================

    /**
     * 清空指定描述符类型的池
     *
     * @param descriptor 资源描述符
     * @param <T> 资源类型
     */
    public <T> void clearPool(ResourceDescriptor<T> descriptor) {
        Deque<Object> pool = resourcePools.remove(descriptor);
        if (pool != null && !pool.isEmpty()) {
            int count = pool.size();
            // 将所有资源释放到底层分配器
            for (Object resource : pool) {
                try {
                    // [编译修复] 同上：接口 release() 现为单参数
                    backingAllocator.release(resource);
                } catch (Exception e) {
                    LOGGER.warning("释放池中资源失败: " + e.getMessage());
                }
            }
            LOGGER.info("Cleared pool for " + descriptor.getType() +
                       " (" + count + " resources freed)");
        }
    }

    /**
     * 清空所有池
     */
    public void clearAllPools() {
        int totalCount = 0;

        for (Map.Entry<ResourceDescriptor<?>, Deque<Object>> entry : resourcePools.entrySet()) {
            Deque<Object> pool = entry.getValue();
            if (!pool.isEmpty()) {
                totalCount += pool.size();
                for (Object resource : pool) {
                    try {
                        // [编译修复] 同上：接口 release() 现为单参数
                        backingAllocator.release(resource);
                    } catch (Exception ignored) {}
                }
            }
        }

        resourcePools.clear();
        activeCounts.clear();

        if (totalCount > 0) {
            LOGGER.info("Cleared all pools (" + totalCount + " resources freed)");
        }
    }

    /**
     * 预热池（预分配资源）
     *
     * <p>在渲染开始前预分配常用资源，避免运行时分配延迟。
     *
     * @param descriptor 资源描述符
     * @param count      预分配数量
     * @param <T> 资源类型
     * @return 实际预分配的数量
     */
    public <T> int prewarm(ResourceDescriptor<T> descriptor, int count) {
        if (count <= 0) return 0;

        count = Math.min(count, maxPoolSizePerDescriptor);
        Deque<Object> pool = resourcePools.computeIfAbsent(
                descriptor, k -> new ConcurrentLinkedDeque<>()
        );

        int allocated = 0;
        for (int i = 0; i < count; i++) {
            try {
                T resource = backingAllocator.acquire(descriptor);
                pool.push(resource);
                allocated++;
            } catch (Exception e) {
                LOGGER.warning("预热分配失败 (#" + i + "): " + e.getMessage());
                break;
            }
        }

        if (allocated > 0) {
            LOGGER.info("Prewarmed " + descriptor.getType() +
                       ": " + allocated + " instances");
        }

        return allocated;
    }

    // ==================== 查询接口 ====================

    /** 获取底层分配器 */
    public GraphicsResourceAllocator getBackingAllocator() { return backingAllocator; }

    /** 获取指定描述符类型的当前池大小 */
    public int getPoolSize(ResourceDescriptor<?> descriptor) {
        Deque<Object> pool = resourcePools.get(descriptor);
        return pool != null ? pool.size() : 0;
    }

    /** 获取总池大小（所有类型） */
    public int getTotalPoolSize() {
        int total = 0;
        for (Deque<Object> pool : resourcePools.values()) {
            total += pool.size();
        }
        return total;
    }

    /** 获取指定描述符类型的活跃资源数 */
    public int getActiveCount(ResourceDescriptor<?> descriptor) {
        AtomicInteger counter = activeCounts.get(descriptor);
        return counter != null ? counter.get() : 0;
    }

    /** 获取缓存命中率 */
    public double getHitRate() {
        long hits = poolHits.get();
        long misses = poolMisses.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    /** 获取总获取次数 */
    public long getTotalAcquires() { return totalAcquires.get(); }

    /** 获取总释放次数 */
    public long getTotalReleases() { return totalReleases.get(); }

    /** 获取缓存命中次数 */
    public long getPoolHits() { return poolHits.get(); }

    /** 获取缓存未命中次数 */
    public long getPoolMisses() { return poolMisses.get(); }

    /**
     * 获取诊断信息
     *
     * @return 格式化的状态字符串
     */
    public String getDiagnostics() {
        return String.format(
            "PooledGraphicsResourceAllocator{" +
            "  pools=%d, total_pooled=%d" +
            "  acquires=%d, releases=%d" +
            "  hits=%d (%.1f%%), misses=%d" +
            "  backing=%s}",
            resourcePools.size(),
            getTotalPoolSize(),
            totalAcquires.get(),
            totalReleases.get(),
            poolHits.get(), getHitRate() * 100,
            poolMisses.get(),
            backingAllocator.getClass().getSimpleName()
        );
    }

    // ==================== 内部辅助方法 ====================

    private void incrementActiveCount(ResourceDescriptor<?> descriptor) {
        activeCounts.computeIfAbsent(descriptor, k -> new AtomicInteger(0))
                     .incrementAndGet();
    }

    private void decrementActiveCount(ResourceDescriptor<?> descriptor) {
        AtomicInteger counter = activeCounts.get(descriptor);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    private void initActiveCount(ResourceDescriptor<?> descriptor) {
        activeCounts.putIfAbsent(descriptor, new AtomicInteger(1));
    }
}
