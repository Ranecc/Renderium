// Renderium - Blaze3D 优化器插件系统
// 运动向量统计数据

package com.ranecc.renderium.presentation.plugin.plugin.streamline;

/**
 * 运动向量生成统计
 * <p>
 * 记录运动向量生成的性能和质量指标。
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class MotionVectorStats {

    /** 总生成次数 */
    private long totalGenerations = 0L;

    /** 累计生成时间（毫秒） */
    private long totalTimeMs = 0L;

    /** 上次生成时间（毫秒） */
    private long lastGenerationTimeMs = 0L;

    /** 最小生成时间（毫秒） */
    private long minTimeMs = Long.MAX_VALUE;

    /** 最大生成时间（毫秒） */
    private long maxTimeMs = 0L;

    /** 平均生成时间（毫秒），滚动计算 */
    private float avgTimeMs = 0f;

    /** 运动向量覆盖率 (0.0 - 1.0) */
    private float coverage = 1.0f;

    // ==================== 统计更新方法 ====================

    /**
     * 记录一次生成完成
     *
     * @param timeMs 本次生成耗时（毫秒）
     */
    public void recordGeneration(long timeMs) {
        this.totalGenerations++;
        this.totalTimeMs += timeMs;
        this.lastGenerationTimeMs = timeMs;

        // 更新最值
        if (timeMs < minTimeMs) {
            minTimeMs = timeMs;
        }
        if (timeMs > maxTimeMs) {
            maxTimeMs = timeMs;
        }

        // 滚动平均
        this.avgTimeMs += (timeMs - avgTimeMs) / totalGenerations;
    }

    /**
     * 设置覆盖率
     *
     * @param coverage 覆盖率 (0.0 - 1.0)
     */
    public void setCoverage(float coverage) {
        this.coverage = Math.clamp(coverage, 0f, 1f);
    }

    // ==================== Getter 方法 ====================

    /** 获取总生成次数 */
    public long getTotalGenerations() { return totalGenerations; }

    /** 获取累计耗时 */
    public long getTotalTimeMs() { return totalTimeMs; }

    /** 获取上次生成耗时 */
    public long getLastGenerationTimeMs() { return lastGenerationTimeMs; }

    /** 获取最小耗时 */
    public long getMinTimeMs() { return minTimeMs == Long.MAX_VALUE ? 0 : minTimeMs; }

    /** 获取最大耗时 */
    public long getMaxTimeMs() { return maxTimeMs; }

    /** 获取平均耗时 */
    public float getAvgTimeMs() { return avgTimeMs; }

    /** 获取覆盖率 */
    public float getCoverage() { return coverage; }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format(
                "MotionVectorStats{generations=%d, avg=%.2fms, min=%dms, max=%dms, coverage=%.0f%%}",
                totalGenerations, avgTimeMs, getMinTimeMs(), maxTimeMs, coverage * 100
        );
    }
}
