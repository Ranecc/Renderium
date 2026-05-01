// Renderium - Blaze3D VMA 渐进式清理模块
// 内存压力级别枚举 - 定义多级预警水位线

package com.ranecc.renderium.domain.enums;

/**
 * 内存压力级别。
 * <p>
 * 对应 {@link GradualMemoryManager} 的四级预警系统，
 * 每个级别有不同的清理速率和响应策略。</p>
 *
 * <h2>水位线配置：</h2>
 * <pre>
 * ┌──────────┬────────┬─────────────┬────────────────────────────────┐
 * │ 级别      │ 水位线  │ 清理速率     │ 响应策略                        │
 * ├──────────┼────────┼─────────────┼────────────────────────────────┤
 * │ NORMAL   │ &lt;75%   │ 0           │ 正常运行，无清理                 │
 * │ WARNING  │ 75-85% │ 预警监控     │ 准备清理候选，过期资源驱逐       │
 * │ SOFT     │ 85-92% │ 10MB/帧     │ 渐进清理冷数据                   │
 * │ HARD     │ 92-97% │ 30MB/帧     │ 加速清理，扩大范围               │
 * │ CRITICAL │ ≥97%   │ 全量释放     │ 强制清理 + 分批 GC              │
 * └──────────┴────────┴─────────────┴────────────────────────────────┘
 * </pre>
 *
 * @see GradualMemoryManager 使用本枚举驱动清理策略选择
 * @since 2.0.0
 */
public enum MemoryPressureLevel {

    /** 正常状态 (&lt;75%) — 无需清理 */
    NORMAL(0, "正常", 0),

    /** 预警状态 (75%-85%) — 开始监控，准备清理候选 */
    WARNING(1, "预警", 10 * 1024 * 1024),

    /** 软限制 (85%-92%) — 开始渐进清理，每帧 10MB */
    SOFT(2, "软限制", 10 * 1024 * 1024),

    /** 硬限制 (92%-97%) — 加速清理，每帧 30MB */
    HARD(3, "硬限制", 30 * 1024 * 1024),

    /** 临界状态 (≥97%) — 强制清理，最后的手段 */
    CRITICAL(4, "临界", Long.MAX_VALUE);

    /** 优先级数值 (用于比较) */
    public final int priority;

    /** 可读名称 */
    public final String description;

    /** 该级别下每帧目标清理字节数 */
    public final long cleanupRatePerFrame;

    MemoryPressureLevel(int priority, String description, long cleanupRate) {
        this.priority = priority;
        this.description = description;
        this.cleanupRatePerFrame = cleanupRate;
    }

    /**
     * 获取描述信息
     *
     * @return 描述字符串
     */
    public String description() {
        return this.description;
    }

    /**
     * 根据使用率比例判断压力级别
     *
     * @param usageRatio 当前显存使用率 (0.0 - 1.0)
     * @return 对应的压力级别
     */
    public static MemoryPressureLevel fromUsageRatio(float usageRatio) {
        if (usageRatio >= 0.97f) return CRITICAL;
        if (usageRatio >= 0.92f) return HARD;
        if (usageRatio >= 0.85f) return SOFT;
        if (usageRatio >= 0.75f) return WARNING;
        return NORMAL;
    }
}
