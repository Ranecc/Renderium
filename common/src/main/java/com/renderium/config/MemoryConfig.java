package com.renderium.config;

import java.util.Properties;

/**
 * 内存优化配置
 *
 * <p>控制 Panama 堆外内存的使用策略，包括 SoA 存储布局、内存池管理、
 * GC 压力控制等高级内存优化技术。
 * 仅在狂暴模式（Aggressive）下生效。
 *
 * <h3>主要功能</h3>
 * <ul>
 *   <li>Panama 堆外存储：绕过 Java 堆内存，直接管理原生内存</li>
 *   <li>SoA (Structure of Arrays) 布局：提高 CPU 缓存命中率和 SIMD 向量化效率</li>
 *   <li>内存池管理：预分配内存池，减少运行时分配开销</li>
 *   <li>GC 压力控制：监控内存使用，防止 GC 频繁触发</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class MemoryConfig {

    /** 是否启用 Panama 堆外存储 */
    private boolean offHeapStorageEnabled = true;

    /** SoA (Structure of Arrays) 布局模式 */
    private boolean soaLayoutEnabled = true;

    /** 内存池初始大小 (MB) */
    private int initialPoolSizeMB = 256;

    /** 内存池最大大小 (MB) */
    private int maxPoolSizeMB = 1024;

    /** GC 压力阈值 (0.0 - 1.0，超过此值触发内存回收) */
    private float gcPressureThreshold = 0.8f;

    /**
     * 默认构造函数
     */
    public MemoryConfig() {}

    /**
     * 从 Properties 对象加载配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.memory."
     * @return MemoryConfig 实例
     */
    public static MemoryConfig fromProperties(Properties props) {
        MemoryConfig config = new MemoryConfig();
        config.offHeapStorageEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.memory.offHeapStorage", "true")
        );
        config.soaLayoutEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.memory.soaLayout", "true")
        );
        config.initialPoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.memory.initialPoolMB", "256")
        );
        config.maxPoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.memory.maxPoolMB", "1024")
        );
        config.gcPressureThreshold = Float.parseFloat(
            props.getProperty("blaze3d.memory.gcPressureThreshold", "0.8")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.memory.offHeapStorage", String.valueOf(offHeapStorageEnabled));
        props.setProperty("blaze3d.memory.soaLayout", String.valueOf(soaLayoutEnabled));
        props.setProperty("blaze3d.memory.initialPoolMB", String.valueOf(initialPoolSizeMB));
        props.setProperty("blaze3d.memory.maxPoolMB", String.valueOf(maxPoolSizeMB));
        props.setProperty("blaze3d.memory.gcPressureThreshold", String.valueOf(gcPressureThreshold));
    }

    /**
     * 是否启用 Panama 堆外存储
     *
     * @return true 如果启用堆外存储
     */
    public boolean isOffHeapStorageEnabled() { return offHeapStorageEnabled; }

    /**
     * 设置是否启用 Panama 堆外存储
     *
     * @param enabled 是否启用
     */
    public void setOffHeapStorageEnabled(boolean enabled) { this.offHeapStorageEnabled = enabled; }

    /**
     * 是否启用 SoA 布局模式
     *
     * @return true 如果启用 SoA 布局
     */
    public boolean isSoaLayoutEnabled() { return soaLayoutEnabled; }

    /**
     * 设置是否启用 SoA 布局模式
     *
     * @param enabled 是否启用
     */
    public void setSoaLayoutEnabled(boolean enabled) { this.soaLayoutEnabled = enabled; }

    /**
     * 获取内存池初始大小
     *
     * @return 初始大小 (MB)
     */
    public int getInitialPoolSizeMB() { return initialPoolSizeMB; }

    /**
     * 设置内存池初始大小
     *
     * @param sizeMB 初始大小 (MB)
     */
    public void setInitialPoolSizeMB(int sizeMB) { this.initialPoolSizeMB = sizeMB; }

    /**
     * 获取内存池最大大小
     *
     * @return 最大大小 (MB)
     */
    public int getMaxPoolSizeMB() { return maxPoolSizeMB; }

    /**
     * 设置内存池最大大小
     *
     * @param sizeMB 最大大小 (MB)
     */
    public void setMaxPoolSizeMB(int sizeMB) { this.maxPoolSizeMB = sizeMB; }

    /**
     * 获取 GC 压力阈值
     *
     * @return GC 压力阈值 (0.0 - 1.0)
     */
    public float getGcPressureThreshold() { return gcPressureThreshold; }

    /**
     * 设置 GC 压力阈值
     *
     * @param threshold GC 压力阈值 (0.0 - 1.0)
     */
    public void setGcPressureThreshold(float threshold) { this.gcPressureThreshold = threshold; }
}
