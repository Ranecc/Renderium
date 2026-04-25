// Renderium v6 Phase 3: 异步区块加载器系统
// MemoryBudgetManager.java - 内存预算管理器
// 功能: 跟踪内存使用量，LRU 淘汰策略，压力回调通知

package com.renderium.lod.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 内存预算管理器。
 *
 * <p>为异步区块加载器提供精细的内存预算控制，
 * 当内存使用接近上限时自动触发淘汰回调。</p>
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────┐
 * │              MemoryBudgetManager                │
 * ├─────────────────────────────────────────────────┤
 * │  allocate(size)  → 检查预算 → 批准/拒绝        │
 * │  release(size)   → 更新使用量                   │
 * │  checkAndEvict() → 使用率 > 阈值? → 触发回调   │
 * └─────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>水位线设计：</h2>
 * <pre>
 * ┌──────────┬────────┬────────────────────────────┐
 * │ 级别     │ 水位   │ 行为                       │
 * ├──────────┼────────┼────────────────────────────┤
 * │ SAFE     │ &lt;70%  │ 正常分配                   │
 * │ WARNING  │ 70-85% │ 触发软淘汰 (soft evict)    │
 * │ CRITICAL │ 85-95% │ 触发强制淘汰 (hard evict)  │
 * │ FULL     │ ≥95%   │ 拒绝新分配                 │
 * └──────────┴────────┴────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建管理器（2GB 预算）
 * MemoryBudgetManager manager = new MemoryBudgetManager(2L * 1024 * 1024 * 1024);
 *
 * // 注册淘汰回调
 * manager.registerCallback(suggestedCount -> {
 *     // 返回实际淘汰的数量
 *     return chunkCache.evictLRU(suggestedCount);
 * });
 *
 * // 分配前检查
 * if (manager.allocate(chunkSize)) {
 *     // 加载区块数据...
 * } else {
 *     // 内存不足，跳过或等待
 * }
 *
 * // 释放时通知
 * manager.release(chunkSize);
 * }</pre>
 *
 * @see AsyncChunkLoader 主使用者
 * @since 6.0.0
 */
public final class MemoryBudgetManager {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(MemoryBudgetManager.class.getName());

    /** 默认安全阈值 (70%) */
    public static final float DEFAULT_SAFE_THRESHOLD = 0.70f;

    /** 默认警告阈值 (85%) */
    public static final float DEFAULT_WARNING_THRESHOLD = 0.85f;

    /** 默认临界阈值 (95%) */
    public static final float DEFAULT_CRITICAL_THRESHOLD = 0.95f;

    /** 总内存预算（字节） */
    private final long budgetBytes;

    /** 当前已使用内存（原子变量保证线程安全） */
    private final AtomicLong currentUsage;

    /** 安全水位线比例 */
    private final float safeThreshold;

    /** 警告水位线比例 */
    private final float warningThreshold;

    /** 临界水位线比例 */
    private final float criticalThreshold;

    /** 淘汰回调列表（线程安全） */
    private final List<MemoryEvictionCallback> evictionCallbacks;

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    /**
     * 内存淘汰回调接口。
     *
     * <p>当内存压力达到阈值时，管理器会调用注册的回调，
     * 请求消费者释放资源。</p>
     */
    @FunctionalInterface
    public interface MemoryEvictionCallback {
        /**
         * 当需要释放内存时调用。
         *
         * @param suggestedEvictCount 建议淘汰的资源数量
         * @return 实际淘汰的资源数量（必须 ≥ 0）
         */
        int onMemoryPressure(int suggestedEvictCount);
    }

    /**
     * 创建内存预算管理器。
     *
     * @param budgetBytes 总内存预算（字节），必须 > 0
     * @throws IllegalArgumentException 如果 budgetBytes ≤ 0
     */
    public MemoryBudgetManager(long budgetBytes) {
        this(budgetBytes, DEFAULT_SAFE_THRESHOLD, DEFAULT_WARNING_THRESHOLD, DEFAULT_CRITICAL_THRESHOLD);
    }

    /**
     * 创建内存预算管理器（自定义阈值）。
     *
     * @param budgetBytes 总内存预算（字节），必须 > 0
     * @param safeThreshold 安全阈值 (0-1)，默认 0.70
     * @param warningThreshold 警告阈值 (0-1)，默认 0.85
     * @param criticalThreshold 临界阈值 (0-1)，默认 0.95
     * @throws IllegalArgumentException 如果参数无效
     */
    public MemoryBudgetManager(long budgetBytes, float safeThreshold,
                               float warningThreshold, float criticalThreshold) {
        // 参数校验
        if (budgetBytes <= 0) {
            throw new IllegalArgumentException("内存预算必须大于 0，当前值: " + budgetBytes);
        }
        if (safeThreshold <= 0 || safeThreshold >= 1) {
            throw new IllegalArgumentException("安全阈值必须在 (0, 1) 范围内，当前值: " + safeThreshold);
        }
        if (warningThreshold <= safeThreshold || warningThreshold >= 1) {
            throw new IllegalArgumentException(
                "警告阈值必须在 (safeThreshold, 1) 范围内，当前值: " + warningThreshold);
        }
        if (criticalThreshold <= warningThreshold || criticalThreshold > 1) {
            throw new IllegalArgumentException(
                "临界阈值必须在 (warningThreshold, 1] 范围内，当前值: " + criticalThreshold);
        }

        this.budgetBytes = budgetBytes;
        this.safeThreshold = safeThreshold;
        this.warningThreshold = warningThreshold;
        this.criticalThreshold = criticalThreshold;
        this.currentUsage = new AtomicLong(0);
        this.evictionCallbacks = new CopyOnWriteArrayList<>();

        LOGGER.info(String.format(
            "MemoryBudgetManager 初始化完成 - 预算: %d MB, 阈值: SAFE=%.0f%% WARNING=%.0f%% CRITICAL=%.0f%%",
            budgetBytes / (1024 * 1024), safeThreshold * 100, warningThreshold * 100, criticalThreshold * 100));
    }

    // ==================== 核心方法 ====================

    /**
     * 尝试分配指定大小的内存。
     *
     * <p>线程安全：使用 CAS 操作确保原子性。</p>
     *
     * @param sizeBytes 请求分配的字节数，必须 > 0
     * @return true 如果分配成功（未超预算），false 如果超出预算被拒绝
     * @throws IllegalArgumentException 如果 sizeBytes ≤ 0
     * @throws IllegalStateException 如果管理器已关闭
     */
    public boolean allocate(long sizeBytes) {
        if (shutdown) {
            throw new IllegalStateException("MemoryBudgetManager 已关闭，无法分配");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("分配大小必须大于 0，当前值: " + sizeBytes);
        }

        // CAS 循环：原子性地检查并更新使用量
        long oldValue, newValue;
        do {
            oldValue = currentUsage.get();
            newValue = oldValue + sizeBytes;

            // 检查是否超过预算
            if (newValue > budgetBytes) {
                LOGGER.warning(String.format(
                    "内存分配失败 - 请求: %d KB, 当前使用: %.1f%%, 预算: %d MB",
                    sizeBytes / 1024, getUsagePercent() * 100, budgetBytes / (1024 * 1024)));
                return false;
            }
        } while (!currentUsage.compareAndSet(oldValue, newValue));

        return true;
    }

    /**
     * 释放之前分配的内存。
     *
     * <p><b>重要：</b>每次 {@link #allocate(long)} 成功后都必须对应调用此方法，
     * 否则会导致内存泄漏。</p>
     *
     * @param sizeBytes 要释放的字节数，必须 > 0
     * @throws IllegalArgumentException 如果 sizeBytes ≤ 0
     * @throws IllegalStateException 如果导致使用量变为负数（说明释放了未分配的内存）
     */
    public void release(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("释放大小必须大于 0，当前值: " + sizeBytes);
        }

        long newValue = currentUsage.addAndGet(-sizeBytes);
        if (newValue < 0) {
            // 回滚并报错
            currentUsage.addAndGet(sizeBytes);
            throw new IllegalStateException(
                String.format("内存释放错误：释放量(%d)超过已使用量，可能存在重复释放", sizeBytes));
        }
    }

    /**
     * 检查内存压力并触发必要的淘汰。
     *
     * <p>应根据以下时机定期调用：</p>
     * <ul>
     *   <li>每帧结束时</li>
     *   <li>大批量加载完成后</li>
     *   <li>内存分配失败时</li>
     * </ul>
     *
     * @return 实际淘汰的资源总数
     */
    public int checkAndEvict() {
        if (shutdown || evictionCallbacks.isEmpty()) {
            return 0;
        }

        float usagePercent = getUsagePercent();
        int totalEvicted = 0;

        if (usagePercent >= criticalThreshold) {
            // 临界状态：强制淘汰 20%
            int suggestedCount = Math.max(10, (int)(getCurrentUsage() / (1024 * 1024) * 0.20));
            totalEvicted = triggerEviction(suggestedCount, "CRITICAL");
            LOGGER.warning(String.format(
                "[CRITICAL] 内存压力过高 (%.1f%%)，触发强制淘汰，释放 %d 个资源",
                usagePercent * 100, totalEvicted));

        } else if (usagePercent >= warningThreshold) {
            // 警告状态：软淘汰 10%
            int suggestedCount = Math.max(5, (int)(getCurrentUsage() / (1024 * 1024) * 0.10));
            totalEvicted = triggerEviction(suggestedCount, "WARNING");
            LOGGER.info(String.format(
                "[WARNING] 内存压力警告 (%.1f%%)，触发软淘汰，释放 %d 个资源",
                usagePercent * 100, totalEvicted));
        }

        return totalEvicted;
    }

    // ==================== 回调管理 ====================

    /**
     * 注册内存淘汰回调。
     *
     * <p>可以注册多个回调，按注册顺序依次调用。</p>
     *
     * @param callback 淘汰回调接口实现
     * @throws NullPointerException 如果 callback 为 null
     */
    public void registerCallback(MemoryEvictionCallback callback) {
        if (callback == null) {
            throw new NullPointerException("回调不能为 null");
        }
        evictionCallbacks.add(callback);
        LOGGER.fine("注册新的内存淘汰回调，当前回调数: " + evictionCallbacks.size());
    }

    /**
     * 移除指定的淘汰回调。
     *
     * @param callback 要移除的回调
     * @return true 如果成功移除
     */
    public boolean unregisterCallback(MemoryEvictionCallback callback) {
        return evictionCallbacks.remove(callback);
    }

    // ==================== 状态查询 ====================

    /**
     * 获取当前内存使用量（字节）。
     *
     * @return 当前已使用的字节数
     */
    public long getCurrentUsage() {
        return currentUsage.get();
    }

    /**
     * 获取总预算（字节）。
     *
     * @return 总预算字节数
     */
    public long getBudget() {
        return budgetBytes;
    }

    /**
     * 获取当前内存使用率。
     *
     * @return 使用率 (0.0 ~ 1.0+)
     */
    public float getUsagePercent() {
        return (float) currentUsage.get() / budgetBytes;
    }

    /**
     * 获取剩余可用内存（字节）。
     *
     * @return 剩余可分配的字节数
     */
    public long getAvailableMemory() {
        return budgetBytes - currentUsage.get();
    }

    /**
     * 判断是否可以分配指定大小。
     *
     * @param sizeBytes 请求的大小
     * @return true 如果有足够空间
     */
    public boolean canAllocate(long sizeBytes) {
        return (currentUsage.get() + sizeBytes) <= budgetBytes;
    }

    /**
     * 获取当前内存压力级别。
     *
     * @return 压力级别字符串
     */
    public String getPressureLevel() {
        float percent = getUsagePercent();
        if (percent < safeThreshold) return "SAFE";
        if (percent < warningThreshold) return "WARNING";
        if (percent < criticalThreshold) return "CRITICAL";
        return "FULL";
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭管理器。
     *
     * <p>关闭后将拒绝所有新的分配请求。</p>
     */
    public void shutdown() {
        this.shutdown = true;
        LOGGER.info("MemoryBudgetManager 已关闭 - 最终使用率: " + String.format("%.1f%%", getUsagePercent() * 100));
    }

    /**
     * 重置使用量为零（仅用于测试）。
     */
    public void reset() {
        currentUsage.set(0);
    }

    // ==================== 内部方法 ====================

    /**
     * 触发所有注册的淘汰回调。
     *
     * @param suggestedCount 建议淘汰数量
     * @param level 日志级别标识
     * @return 实际淘汰的总数
     */
    private int triggerEviction(int suggestedCount, String level) {
        int totalEvicted = 0;

        for (MemoryEvictionCallback callback : evictionCallbacks) {
            try {
                int evicted = callback.onMemoryPressure(suggestedCount);
                if (evicted > 0) {
                    totalEvicted += evicted;
                    LOGGER.fine(String.format("[%s] 回调淘汰了 %d 个资源", level, evicted));
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "淘汰回调执行失败", e);
            }
        }

        return totalEvicted;
    }

    /**
     * 生成状态报告字符串。
     *
     * @return 格式化的状态信息
     */
    public String getStatusReport() {
        return String.format(
            "MemoryBudgetManager [预算: %d MB | 已用: %d MB (%.1f%%) | 剩余: %d MB | 级别: %s | 回调数: %d]",
            budgetBytes / (1024 * 1024),
            getCurrentUsage() / (1024 * 1024),
            getUsagePercent() * 100,
            getAvailableMemory() / (1024 * 1024),
            getPressureLevel(),
            evictionCallbacks.size());
    }

    @Override
    public String toString() {
        return getStatusReport();
    }
}
