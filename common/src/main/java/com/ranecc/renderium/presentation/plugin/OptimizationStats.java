// Renderium - Blaze3D 优化器插件系统
// 优化统计数据

package com.ranecc.renderium.presentation.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 优化效果统计数据
 * <p>
 * 记录单个优化的运行时性能指标，
 * 包括资源使用情况、帧时间改善等量化数据。
 *
 * <h2>使用方式：</h2>
 * <pre>{@code
 * OptimizationStats stats = new OptimizationStats();
 * stats.setMetric("poolHitRate", 0.85f);
 * stats.setMetric("savedDrawCalls", 234);
 * }</pre>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class OptimizationStats {

    /** 存储各项指标的有序映射 */
    private final Map<String, Object> metrics = new LinkedHashMap<>();

    /** 统计开始时间戳（毫秒） */
    private long startTimeMs = System.currentTimeMillis();

    /** 统计结束时间戳（毫秒） */
    private long endTimeMs = 0;

    /** 是否已标记为完成 */
    private boolean finalized = false;

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数
     */
    public OptimizationStats() {}

    // ==================== 指标操作 ====================

    /**
     * 设置指标值
     *
     * @param key   指标名称（推荐使用 camelCase）
     * @param value 指标值（支持 Number/String/Boolean）
     * @throws IllegalStateException 如果统计已完成
     */
    public void setMetric(String key, Object value) {
        checkNotFinalized();
        metrics.put(Objects.requireNonNull(key), value);
    }

    /**
     * 获取指标值
     *
     * @param key          指标名称
     * @param defaultValue 默认值（当指标不存在时返回）
     * @return 指标值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetric(String key, T defaultValue) {
        Object value = metrics.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 获取浮点型指标
     *
     * @param key          指标名称
     * @param defaultValue 默认值
     * @return 浮点数值或默认值
     */
    public float getFloatMetric(String key, float defaultValue) {
        Object value = metrics.get(key);
        if (value instanceof Number num) {
            return num.floatValue();
        }
        return defaultValue;
    }

    /**
     * 获取整型指标
     *
     * @param key          指标名称
     * @param defaultValue 默认值
     * @return 整数值或默认值
     */
    public int getIntMetric(String key, int defaultValue) {
        Object value = metrics.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        return defaultValue;
    }

    /**
     * 合并另一个统计对象的数据
     *
     * @param other 另一个 OptimizationStats 实例
     * @throws IllegalStateException 如果当前统计已完成
     */
    public void add(OptimizationStats other) {
        checkNotFinalized();
        this.metrics.putAll(other.metrics);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 标记统计完成，锁定数据
     * <p>调用后不能再修改指标数据。
     */
    public void finalizeStats() {
        this.endTimeMs = System.currentTimeMillis();
        this.finalized = true;
    }

    /**
     * 重置统计，清空所有数据
     */
    public void reset() {
        metrics.clear();
        startTimeMs = System.currentTimeMillis();
        endTimeMs = 0;
        finalized = false;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取所有指标的不可变视图
     *
     * @return 指标映射的不可修改视图
     */
    public Map<String, Object> getAllMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    /**
     * 获取指标数量
     *
     * @return 已记录的指标总数
     */
    public int getMetricCount() {
        return metrics.size();
    }

    /**
     * 获取统计持续时间（毫秒）
     *
     * @return 从创建到 finalize 的时长，未结束时返回当前时长
     */
    public long getDurationMs() {
        long end = endTimeMs > 0 ? endTimeMs : System.currentTimeMillis();
        return end - startTimeMs;
    }

    /**
     * 检查是否包含指定指标
     *
     * @param key 指标名称
     * @return 如果存在返回 true
     */
    public boolean hasMetric(String key) {
        return metrics.containsKey(key);
    }

    // ==================== 内部方法 ====================

    /**
     * 检查是否未完成（可修改状态）
     *
     * @throws IllegalStateException 如果已最终化
     */
    private void checkNotFinalized() {
        if (finalized) {
            throw new IllegalStateException(
                    "Cannot modify finalized OptimizationStats. Call reset() to clear."
            );
        }
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("OptimizationStats{");
        sb.append("duration=").append(getDurationMs()).append("ms");
        sb.append(", metrics=").append(metrics);
        sb.append('}');
        return sb.toString();
    }
}
