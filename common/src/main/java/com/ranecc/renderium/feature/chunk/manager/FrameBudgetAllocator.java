// Renderium - 帧时间预算分配器
// 按相机运动状态动态切片预算，所有子系统共享同一预算池

package com.ranecc.renderium.feature.chunk.manager;

import com.ranecc.renderium.feature.chunk.build.ProgressiveMeshRefiner;

/**
 * 跨子系统帧时间预算分配器。
 *
 * <p>预算是"一个全局池，四个子系统按策略瓜分"，而非"四个独立预算"。
 *
 * <h2>预算切片策略</h2>
 * <pre>
 * ┌─────────────┬───────────┬───────────┬───────────┬───────────┬───────────┐
 * │ 模式 (omega)│ Chunk构建  │  剔除     │  排序     │ 实体变换  │   总计    │
 * ├─────────────┼───────────┼───────────┼───────────┼───────────┼───────────┤
 * │ EXPLORE     │  0.5ms    │  0.3ms    │  0.1ms    │  0.1ms    │  1.0ms    │
 * │ (ω < 30°)  │           │ L1+L2+L3  │ topo sort │ instance  │           │
 * ├─────────────┼───────────┼───────────┼───────────┼───────────┼───────────┤
 * │ TRAVEL      │  0.3ms    │  0.2ms    │  0.1ms    │  0.1ms    │  0.7ms    │
 * │ (30≤ω<60°) │ aggressive│ L1 only   │ fast sort │ instance  │           │
 * ├─────────────┼───────────┼───────────┼───────────┼───────────┼───────────┤
 * │ COMBAT      │  0.6ms    │  0.1ms    │  0.05ms   │  0.1ms    │  0.85ms   │
 * │ (ω ≥ 60°)  │ imm full  │ frust only │ fast sort │ instance  │           │
 * └─────────────┴───────────┴───────────┴───────────┴───────────┴───────────┘
 * </pre>
 *
 * <p>缓存机制：同一帧内 repeat calls 返回相同预算，避免重复计算。
 */
public class FrameBudgetAllocator {

    // ==================== 预算基础 (纳秒) ====================

    /** 总帧预算 (1ms @ 1000FPS) */
    static final long TOTAL_FRAME_BUDGET_NS = 1_000_000L;

    /** 爆炸场景临时膨胀倍数 */
    private static final double EXPLOSION_BUDGET_MULTIPLIER = 2.0;

    // ==================== EMA 反馈循环 ====================

    /** EMA 平滑因子：α=0.1 表示新观测占 10%，历史占 90% */
    private static final double EMA_ALPHA = 0.1;

    /** Chunk 构建实际耗时 EMA（纳秒），初始估计 400μs */
    private double emaChunkTimeNs = 400_000.0;

    /** 剔除实际耗时 EMA（纳秒），初始估计 200μs */
    private double emaCullTimeNs = 200_000.0;

    /** 帧号缓存 (避免同帧重复算) */
    private long cachedFrame = -1;

    /** 缓存的预算切片 */
    private long cachedChunkBudget;
    private long cachedCullBudget;
    private long cachedSortBudget;
    private long cachedEntityBudget;
    private boolean cachedSkipL2;

    // ==================== 公共 API ====================

    /**
     * 记录本帧实际耗时，更新 EMA 估计值。
     *
     * @param chunkTimeNs Chunk 构建实际耗时（纳秒）
     * @param cullTimeNs  剔除实际耗时（纳秒）
     */
    public void recordActualTimes(long chunkTimeNs, long cullTimeNs) {
        emaChunkTimeNs = EMA_ALPHA * chunkTimeNs + (1 - EMA_ALPHA) * emaChunkTimeNs;
        emaCullTimeNs = EMA_ALPHA * cullTimeNs + (1 - EMA_ALPHA) * emaCullTimeNs;
    }

    /**
     * 按相机状态分配预算。
     * 同一帧内重复调用返回缓存结果（避免重复计算）。
     */
    public Slice allocate(CameraMotionDetector detector, long currentFrame) {
        if (currentFrame == cachedFrame) {
            return new Slice(cachedChunkBudget, cachedCullBudget,
                cachedSortBudget, cachedEntityBudget, cachedSkipL2);
        }

        double omega = detector.getOmega();
        ProgressiveMeshRefiner.RefinementStrategy strat = detector.getStrategy();

        long chunk, cull, sort, entity;
        boolean skipL2;

        if (omega >= 60.0) {
            // COMBAT: chunk 全精炼（需要最多预算），cull 只做 L1
            chunk  = 600_000L;
            cull   = 100_000L;
            sort   = 50_000L;
            entity = 100_000L;
            skipL2 = true;
        } else if (omega >= 30.0) {
            // TRAVEL: chunk 加速精炼，cull 只做 L1+L3（省 L2）
            chunk  = 300_000L;
            cull   = 200_000L;
            sort   = 100_000L;
            entity = 100_000L;
            skipL2 = true;
        } else {
            // EXPLORE: 全功能
            chunk  = 500_000L;
            cull   = 300_000L;
            sort   = 100_000L;
            entity = 100_000L;
            skipL2 = false;
        }

        // EMA 反馈调整：根据历史实际耗时动态重新分配 chunk/cull 预算
        if (emaCullTimeNs > cull * 1.3) {
            // 剔除持续超支：从 chunk 预算中偷取 20% 给 cull
            long steal = (long) (chunk * 0.2);
            chunk -= steal;
            cull += steal;
        } else if (emaCullTimeNs < cull * 0.5) {
            // 剔除持续节余：将 cull 预算的 10% 还给 chunk
            long giveback = (long) (cull * 0.1);
            cull -= giveback;
            chunk += giveback;
        }

        // 缓存
        cachedFrame = currentFrame;
        cachedChunkBudget = chunk; cachedCullBudget = cull;
        cachedSortBudget = sort; cachedEntityBudget = entity;
        cachedSkipL2 = skipL2;

        return new Slice(chunk, cull, sort, entity, skipL2);
    }

    // ==================== 场景特殊分配 ====================

    /**
     * 爆炸场景：chunk 预算临时膨胀至 2x，cull/sort 归零。
     */
    public Slice allocateExplosion(CameraMotionDetector detector, long currentFrame) {
        return new Slice(
            (long)(400_000L * EXPLOSION_BUDGET_MULTIPLIER),
            50_000L,   // 最少 cull
            0L,        // sort 暂缓
            0L,        // entity 暂缓
            false      // 相机未转
        );
    }

    // ==================== 数据类 ====================

    /**
     * 单帧预算切片。
     */
    public record Slice(
        long chunkBuildBudgetNs,   // Chunk 构建管线
        long cullingBudgetNs,     // GPU 剔除管线
        long sortBudgetNs,        // 透明面排序
        long entityBudgetNs,      // 实体变换提交
        boolean skipL2Occlusion   // 相机急转跳过 L2
    ) {}
}
