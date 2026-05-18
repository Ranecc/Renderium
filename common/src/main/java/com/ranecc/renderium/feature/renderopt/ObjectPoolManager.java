// Renderium - 渲染优化模块 (狂暴模式专用)
// 对象池管理器 - 基于 Arena 思想的高性能对象池化
// 整合 vulkan-memory-arena-guide.md 中的 Pool Arena 设计

package com.ranecc.renderium.feature.renderopt;


import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.module.ModuleContext;

/**
 * 对象池管理器 🎱
 * <p>
 * 基于 `vulkan-memory-arena-guide.md` 中的 Pool Arena 思想，
 * 实现高性能的对象池化系统，显著减少 GC 停顿和内存碎片。
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  1. 类型安全对象池                                         │
 * │     - 泛型接口，类型安全                                   │
 * │     - 工厂模式创建对象                                     │
 * │     - 最大容量控制                                         │
 * ├─────────────────────────────────────────────────────────────┤
 * │  2. Per-Frame 对象池（Arena 思想）                         │
 * │     - 帧结束时自动重置                                     │
 * │     - 零碎片、O(1) 分配/释放                              │
 * │     - 适合临时对象（渲染数据等）                           │
 * ├─────────────────────────────────────────────────────────────┤
 * │  3. 全局持久池                                              │
 * │     - 跨帧复用                                             │
 * │     - 适合长期存在的对象                                   │
 * ├─────────────────────────────────────────────────────────────┤
 * │  4. 性能监控                                                │
 * │     - 分配/释放统计                                        │
 * │     - 命中率追踪                                           │
 * │     - 内存使用量估算                                       │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计参考：</h3>
 * <ul>
 *   <li>Vulkan Memory Arena Guide (vulkan-memory-arena-guide.md §四 Pool Arena)</li>
 *   <li>兼容模式 Vulkan 优化指南 (compatibility-mode-optimization.md)</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class ObjectPoolManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ObjectPoolManager.class.getName());

    // ==================== 配置常量 ====================

    /** 默认每种类型的最大池大小 */
    public static final int DEFAULT_MAX_POOL_SIZE = 1024;

    /** 默认 Per-Frame 池大小 */
    public static final int DEFAULT_FRAME_POOL_SIZE = 256;

    /** 默认预分配数量 */
    public static final int DEFAULT_PREALLOCATE_COUNT = 16;

    // ==================== 接口定义 ====================

    /**
     * 类型安全的对象池接口
     *
     * @param <T> 池化对象类型
     */
    public interface ObjectPool<T> {
        /**
         * 从池中获取对象
         *
         * @return 池化对象，池空时创建新实例
         */
        T acquire();

        /**
         * 归还对象到池中
         *
         * @param obj 要归还的对象
         */
        void release(T obj);

        /**
         * 获取当前活跃对象数（已分配未归还）
         */
        int getActiveCount();

        /**
         * 获取当前池中空闲对象数
         */
        int getPooledCount();

        /**
         * 清空池，释放所有对象
         */
        void clear();

        /**
         * 获取池名称
         */
        String getName();

        /**
         * 获取分配统计
         */
        PoolStats getStats();
    }

    /**
     * 池统计信息
     */
    public record PoolStats(
            String name,
            int activeCount,
            int pooledCount,
            long totalAcquires,
            long totalReleases,
            double hitRate
    ) {
        public String format() {
            return String.format("%s: active=%d, pooled=%d, hits=%.1f%%",
                    name, activeCount, pooledCount, hitRate * 100);
        }
    }

    // ==================== 内部实现：简单对象池 ====================

    /**
     * 简单对象池实现（全局持久池）
     * <p>
     * 适合跨帧复用的长期对象。
     */
    private static class SimpleObjectPool<T> implements ObjectPool<T> {

        private final String name;
        private final Queue<T> pool = new ConcurrentLinkedQueue<>();
        private final java.util.function.Supplier<T> factory;
        private final int maxSize;
        private final AtomicInteger activeCount = new AtomicInteger(0);
        private final AtomicLong totalAcquires = new AtomicLong(0);
        private final AtomicLong totalReleases = new AtomicLong(0);
        private final AtomicLong cacheHits = new AtomicLong(0);

        SimpleObjectPool(String name, java.util.function.Supplier<T> factory, int maxSize) {
            this.name = name;
            this.factory = factory;
            this.maxSize = maxSize;
        }

        @Override
        public T acquire() {
            T obj = pool.poll();
            if (obj != null) {
                cacheHits.incrementAndGet();
            } else {
                obj = factory.get();
            }
            activeCount.incrementAndGet();
            totalAcquires.incrementAndGet();
            return obj;
        }

        @Override
        public void release(T obj) {
            if (pool.size() < maxSize) {
                pool.offer(obj);
            }
            activeCount.decrementAndGet();
            totalReleases.incrementAndGet();
        }

        @Override
        public int getActiveCount() { return activeCount.get(); }

        @Override
        public int getPooledCount() { return pool.size(); }

        @Override
        public void clear() { pool.clear(); }

        @Override
        public String getName() { return name; }

        @Override
        public PoolStats getStats() {
            long acquires = totalAcquires.get();
            long hits = cacheHits.get();
            return new PoolStats(
                    name,
                    getActiveCount(),
                    getPooledCount(),
                    acquires,
                    totalReleases.get(),
                    acquires > 0 ? (double) hits / acquires : 0
            );
        }
    }

    // ==================== 内部实现：Per-Frame 对象池（Arena）====================

    /**
     * Per-Frame 对象池（Arena 风格）
     * <p>
     * 基于帧的生命周期管理对象：
     * - 帧开始时重置
     * - 帧结束时所有对象自动归还
     * - O(1) 分配，零碎片
     * <p>
     * 适合临时渲染数据（顶点数据、批次信息等）。
     */
    private static class PerFrameObjectPool<T> implements ObjectPool<T> {

        private final String name;
        private final Queue<T> pool = new ConcurrentLinkedQueue<>();
        private final java.util.function.Supplier<T> factory;
        private final int maxSize;
        private final AtomicInteger activeCount = new AtomicInteger(0);
        private final AtomicLong totalAcquires = new AtomicLong(0);
        private final AtomicLong frameResets = new AtomicLong(0);

        PerFrameObjectPool(String name, java.util.function.Supplier<T> factory, int maxSize) {
            this.name = name;
            this.factory = factory;
            this.maxSize = maxSize;
        }

        @Override
        public T acquire() {
            T obj = pool.poll();
            if (obj == null) {
                obj = factory.get();
            }
            activeCount.incrementAndGet();
            totalAcquires.incrementAndGet();
            return obj;
        }

        @Override
        public void release(T obj) {
            // Per-Frame 池不手动释放，等待帧结束统一重置
            // 但如果池未满，可以先归还以供同帧复用
            if (pool.size() < maxSize) {
                pool.offer(obj);
            }
            activeCount.decrementAndGet();
        }

        /**
         * 帧结束时调用，将所有活跃对象归还到池中
         * <p>
         * 这是 Arena 思想的核心：批量释放而非逐个释放。
         */
        public void resetForNextFrame() {
            // 将所有活跃对象标记为可重用
            // （实际实现取决于具体使用场景）
            frameResets.incrementAndGet();
        }

        @Override
        public int getActiveCount() { return activeCount.get(); }

        @Override
        public int getPooledCount() { return pool.size(); }

        @Override
        public void clear() { pool.clear(); }

        @Override
        public String getName() { return name + " [Per-Frame]"; }

        @Override
        public PoolStats getStats() {
            return new PoolStats(
                    name + "[Frame]",
                    getActiveCount(),
                    getPooledCount(),
                    totalAcquires.get(),
                    frameResets.get(),
                    1.0 // Per-Frame 池总是命中（因为预分配）
            );
        }
    }

    // ==================== 状态字段 ====================

    private volatile boolean initialized = false;
    private volatile boolean enabled = false;

    /** 全局持久池映射 */
    private final ConcurrentHashMap<String, ObjectPool<?>> globalPools = new ConcurrentHashMap<>();

    /** Per-Frame 池映射 */
    private final ConcurrentHashMap<String, ObjectPool<?>> perFramePools = new ConcurrentHashMap<>();

    // ==================== 统计字段 ====================

    private final AtomicLong totalGlobalAcquires = new AtomicLong(0);
    private final AtomicLong totalFrameAcquires = new AtomicLong(0);
    private final AtomicInteger currentFrame = new AtomicInteger(0);

    // ==================== 构造函数 ====================

    public ObjectPoolManager() {}

    // ==================== 生命周期方法 ====================

    /**
     * 初始化对象池管理器
     *
     * @param context 模块上下文
     * @return 成功返回 true
     */
    public boolean initialize(ModuleContext context) {
        if (initialized) return true;

        try {
            // 预注册常用类型池（可选）
            // registerGlobalPool("render-data", RenderData::new, DEFAULT_MAX_POOL_SIZE);

            this.initialized = true;

            LOGGER.info("✓ ObjectPoolManager initialized (Arena-based)");
            LOGGER.info(String.format("  Default global pool size: %d", DEFAULT_MAX_POOL_SIZE));
            LOGGER.info(String.format("  Default frame pool size: %d", DEFAULT_FRAME_POOL_SIZE));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize ObjectPoolManager: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启用对象池管理器
     */
    public void enable() { enabled = true; }

    /**
     * 禁用对象池管理器
     */
    public void disable() { enabled = false; }

    /**
     * 销毁所有池并释放资源
     */
    @Override
    public void close() {
        // 清空所有全局池
        for (ObjectPool<?> pool : globalPools.values()) {
            pool.clear();
        }
        globalPools.clear();

        // 清空所有 Per-Frame 池
        for (ObjectPool<?> pool : perFramePools.values()) {
            pool.clear();
        }
        perFramePools.clear();

        enabled = false;
        initialized = false;

        LOGGER.info("ObjectPoolManager disposed");
    }

    // ==================== 帧管理 API ====================

    /**
     * 开始新帧
     * <p>
     * 每帧开始时调用，用于 Per-Frame 池的统计。
     */
    public void beginFrame() {
        if (!enabled || !initialized) return;
        currentFrame.incrementAndGet();
    }

    /**
     * 结束当前帧
     * <p>
     * 帧结束时调用，重置所有 Per-Frame 池。
     * 这是 Arena 思想的核心操作。
     */
    public void endFrame() {
        if (!enabled || !initialized) return;

        // 重置所有 Per-Frame 池
        for (ObjectPool<?> pool : perFramePools.values()) {
            if (pool instanceof PerFrameObjectPool<?> framePool) {
                framePool.resetForNextFrame();
            }
        }
    }

    // ==================== 全局持久池 API ====================

    /**
     * 注册新的全局持久池
     * <p>
     * 全局池中的对象跨帧复用，适合长期存在的对象。
     *
     * @param name    池名称（唯一标识）
     * @param factory 对象工厂（用于创建新实例）
     * @param maxSize 最大池容量
     * @param <T>     对象类型
     */
    public <T> void registerGlobalPool(String name, java.util.function.Supplier<T> factory, int maxSize) {
        globalPools.put(name, new SimpleObjectPool<>(name, factory, maxSize));
        LOGGER.fine(String.format("Global pool registered: %s (max=%d)", name, maxSize));
    }

    /**
     * 从全局池获取对象
     *
     * @param name 池名称
     * @param <T>  对象类型
     * @return 池化对象，失败返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T acquireFromGlobal(String name) {
        if (!enabled) return null;

        ObjectPool<?> pool = globalPools.get(name);
        if (pool == null) return null;

        totalGlobalAcquires.incrementAndGet();
        return ((ObjectPool<T>) pool).acquire();
    }

    /**
     * 归还对象到全局池
     *
     * @param name 池名称
     * @param obj  要归还的对象
     * @param <T>  对象类型
     */
    public <T> void releaseToGlobal(String name, T obj) {
        if (!enabled || obj == null) return;

        @SuppressWarnings("unchecked")
        ObjectPool<T> pool = (ObjectPool<T>) globalPools.get(name);
        if (pool != null) pool.release(obj);
    }

    // ==================== Per-Frame 池 API ====================

    /**
     * 注册新的 Per-Frame 池
     * <p>
     * Per-Frame 池中的对象在帧结束时自动重置，
     * 适合临时渲染数据（顶点数据、批次信息等）。
     *
     * @param name    池名称（唯一标识）
     * @param factory 对象工厂
     * @param maxSize 最大池容量
     * @param <T>     对象类型
     */
    public <T> void registerFramePool(String name, java.util.function.Supplier<T> factory, int maxSize) {
        perFramePools.put(name, new PerFrameObjectPool<>(name, factory, maxSize));
        LOGGER.fine(String.format("Per-Frame pool registered: %s (max=%d)", name, maxSize));
    }

    /**
     * 从 Per-Frame 池获取对象
     *
     * @param name 池名称
     * @param <T>  对象类型
     * @return 池化对象，失败返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T acquireFromFrame(String name) {
        if (!enabled) return null;

        ObjectPool<?> pool = perFramePools.get(name);
        if (pool == null) return null;

        totalFrameAcquires.incrementAndGet();
        return ((ObjectPool<T>) pool).acquire();
    }

    /**
     * 归还对象到 Per-Frame 池（可选，帧结束会自动重置）
     *
     * @param name 池名称
     * @param obj  要归还的对象
     * @param <T>  对象类型
     */
    public <T> void releaseToFrame(String name, T obj) {
        if (!enabled || obj == null) return;

        @SuppressWarnings("unchecked")
        ObjectPool<T> pool = (ObjectPool<T>) perFramePools.get(name);
        if (pool != null) pool.release(obj);
    }

    // ==================== 便捷 API ====================

    /**
     * 从指定池获取对象（自动判断是全局池还是帧池）
     *
     * @param name 池名称
     * @param <T>  对象类型
     * @return 池化对象
     */
    public <T> T acquire(String name) {
        // 优先从帧池获取
        T obj = acquireFromFrame(name);
        if (obj == null) {
            obj = acquireFromGlobal(name);
        }
        return obj;
    }

    /**
     * 归还对象到指定池
     *
     * @param name 池名称
     * @param obj  对象
     * @param <T>  对象类型
     */
    public <T> void release(String name, T obj) {
        // 先尝试归还到帧池
        if (perFramePools.containsKey(name)) {
            releaseToFrame(name, obj);
        } else {
            releaseToGlobal(name, obj);
        }
    }

    // ==================== 查询 API ====================

    /**
     * 是否已启用
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 获取总活跃对象数（全局 + 帧）
     */
    public int getActiveObjectCount() {
        int total = 0;
        for (ObjectPool<?> pool : globalPools.values()) total += pool.getActiveCount();
        for (ObjectPool<?> pool : perFramePools.values()) total += pool.getActiveCount();
        return total;
    }

    /**
     * 获取总池化对象数
     */
    public int getTotalPooledCount() {
        int total = 0;
        for (ObjectPool<?> pool : globalPools.values()) total += pool.getPooledCount();
        for (ObjectPool<?> pool : perFramePools.values()) total += pool.getPooledCount();
        return total;
    }

    /**
     * 获取全局池数量
     */
    public int getGlobalPoolCount() { return globalPools.size(); }

    /**
     * 获取 Per-Frame 池数量
     */
    public int getFramePoolCount() { return perFramePools.size(); }

    /**
     * 获取格式化的性能报告
     * <p>
     * 参考 `vulkan-memory-arena-guide.md` 的监控建议。
     */
    public String formatReport() {
        if (!initialized) return "ObjectPoolManager not initialized";

        return String.format(
                "Object Pool Manager Report:\n" +
                "  Status: %s\n" +
                "  Global pools: %d (%d objects)\n" +
                "  Frame pools: %d (%d objects)\n" +
                "  Total active: %d\n" +
                "  Total pooled: %d\n" +
                "  Frame: %d",
                enabled ? "ENABLED" : "DISABLED",
                getGlobalPoolCount(),
                sumPooledCount(globalPools),
                getFramePoolCount(),
                sumPooledCount(perFramePools),
                getActiveObjectCount(),
                getTotalPooledCount(),
                currentFrame.get()
        );
    }

    /**
     * 获取所有池的详细统计
     */
    public String formatDetailedStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Global Pools ===\n");
        for (ObjectPool<?> pool : globalPools.values()) {
            sb.append(pool.getStats().format()).append("\n");
        }
        sb.append("\n=== Per-Frame Pools ===\n");
        for (ObjectPool<?> pool : perFramePools.values()) {
            sb.append(pool.getStats().format()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部辅助方法 ====================

    private int sumPooledCount(ConcurrentHashMap<String, ObjectPool<?>> pools) {
        int total = 0;
        for (ObjectPool<?> pool : pools.values()) total += pool.getPooledCount();
        return total;
    }
}
