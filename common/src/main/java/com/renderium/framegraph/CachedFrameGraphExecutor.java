package com.renderium.framegraph;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.logging.Logger;

/**
 * FrameGraph 执行顺序缓存 (P1 优化)
 * <p>
 * 解决 {@code FrameGraphBuilder.execute()} 每帧都重新计算 Pass 依赖顺序的问题。
 * 缓存 {@code identifyPassesToKeep()} 和 {@code resolvePassOrder()} 的结果，
 * 仅在 Pass 结构变化时重新计算。
 * </p>
 *
 * <h3>解决的问题：</h3>
 * <ul>
 *   <li>每帧 new BitSet() / new ArrayList() 分配</li>
 *   <li>每帧 O(P+E) 的依赖图遍历（P=Pass数, E=边数）</li>
 *   <li>FrameGraph 结构通常不变，重复计算浪费</li>
 * </ul>
 *
 * <h3>优化原理：</h3>
 * <pre>
 * 优化前（每帧）:
 *   passesToKeep = identifyPassesToKeep()    // BitSet 分配 + 遍历
 *   passOrder = resolvePassOrder(...)        // ArrayList 分配 + DFS
 *   assignResourceLifetimes(passOrder)       // 资源生命周期计算
 *
 * 优化后（仅在变化时）:
 *   if (passCountChanged) {
 *       // 重新计算并缓存
 *       cachedPassesToKeep = identifyPassesToKeep()
 *       cachedPassOrder = resolvePassOrder(...)
 *   }
 *   // 直接使用缓存
 * </pre>
 *
 * @since 5.2.0
 */
public final class CachedFrameGraphExecutor {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|CachedFG");

    /** 缓存的 Pass 保留标记 */
    private BitSet cachedPassesToKeep;

    /** 缓存的 Pass 执行顺序列表 */
    private List<Object> cachedPassOrder;

    /** 上次缓存的 Pass 总数（用于检测结构变化） */
    private int lastCachedPassCount;

    /** 缓存命中次数（统计） */
    private long cacheHitCount;

    /** 缓存未命中次数（统计） */
    private long cacheMissCount;

    /**
     * 使用缓存的执行顺序执行 FrameGraph
     * <p>
     * 如果 Pass 结构未变则使用缓存结果，
     * 否则调用 recomputationFunc 重新计算。
     * </p>
     *
     * 【方法参数】
     * @param allocator      GraphicsResourceAllocator - 资源分配器
     * @param inspector      FrameGraphBuilder - 帧图检查器
     * @param currentPassCount int - 当前 Pass 总数
     * @param recomputeFunc  RecomputationFunction - 重新计算的回调函数
     *
     * 【返回值】
     * @return boolean - true 表示使用了缓存（hit），false 表示重新计算了（miss）
     */
    public boolean executeWithCache(
            GraphicsResourceAllocator allocator,
            FrameGraphBuilder.Inspector inspector,
            int currentPassCount,
            RecomputationFunction recomputeFunc) {

        // 检查是否需要重新计算
        boolean needsRecompute = (cachedPassesToKeep == null)
            || (currentPassCount != lastCachedPassCount);

        if (needsRecompute) {
            cacheMissCount++;
            LOGGER.fine("FrameGraph 缓存未命中 (miss #" + cacheMissCount +
                "), 重新计算 " + currentPassCount + " 个 Pass 的执行顺序");

            // 调用回调进行实际计算
            RecomputeResult result = recomputeFunc.recompute(allocator, inspector);

            // 更新缓存
            this.cachedPassesToKeep = result.passesToKeep;
            this.cachedPassOrder = result.passOrder;
            this.lastCachedPassCount = currentPassCount;

            return false;  // miss

        } else {
            cacheHitCount++;
            LOGGER.fine("FrameGraph 缓存命中 (hit #" + cacheHitCount +
                "), 复用 " + cachedPassOrder.size() + " 个 Pass 的执行顺序");

            return true;  // hit
        }
    }

    /**
     * 强制清除缓存（在 Pass 结构确实改变时调用）
     */
    public void invalidateCache() {
        cachedPassesToKeep = null;
        cachedPassOrder = null;
        lastCachedPassCount = -1;
        LOGGER.fine("FrameGraph 缓存已强制清除");
    }

    /** 获取缓存命中率 (0.0 ~ 1.0) */
    public double getCacheHitRate() {
        long total = cacheHitCount + cacheMissCount;
        if (total == 0) return 0.0;
        return (double) cacheHitCount / total;
    }

    /** 获取缓存统计信息 */
    public String getStats() {
        long total = cacheHitCount + cacheMissCount;
        return String.format("hits=%d, misses=%d, rate=%.1f%%",
            cacheHitCount, cacheMissCount, getCacheHitRate() * 100);
    }

    /** 获取缓存的 Pass 执行顺序（调试用） */
    public List<Object> getCachedPassOrder() {
        return cachedPassOrder != null
            ? java.util.Collections.unmodifiableList(cachedPassOrder)
            : null;
    }

    /**
     * 重新计算回调接口
     * <p>
     * 封装原始的 identifyPassesToKeep + resolvePassOrder 逻辑。
     * </p>
     */
    @FunctionalInterface
    public interface RecomputationFunction {
        /**
         * 重新计算 Pass 执行顺序
         *
         * 【方法参数】
         * @param allocator GraphicsResourceAllocator - 资源分配器
         * @param inspector  FrameGraphBuilder.Inspector - 检查器
         *
         * 【返回值】
         * @return RecomputeResult - 计算结果（包含 passesToKeep 和 passOrder）
         */
        RecomputeResult recompute(GraphicsResourceAllocator allocator,
                                  FrameGraphBuilder.Inspector inspector);
    }

    /**
     * 重新计算结果
     */
    public static final class RecomputeResult {
        /** Pass 保留标记 */
        public final BitSet passesToKeep;
        /** Pass 执行顺序列表 */
        public final List<Object> passOrder;

        public RecomputeResult(BitSet passesToKeep, List<Object> passOrder) {
            this.passesToKeep = passesToKeep;
            this.passOrder = passOrder;
        }
    }
}
