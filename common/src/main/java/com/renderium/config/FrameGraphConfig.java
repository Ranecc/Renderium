package com.renderium.config;

import java.util.Properties;

/**
 * 帧图优化（Frame Graph Optimization）配置
 *
 * <p>控制渲染帧图的重建与优化策略，包括资源复用、Pass 合并、依赖分析等。
 * 仅在狂暴模式（Aggressive）下生效。
 *
 * <h3>主要功能</h3>
 * <ul>
 *   <li>Pass 合并优化：减少渲染通道切换开销</li>
 *   <li>并行 Pass 执行：利用 GPU 并行性提高渲染效率</li>
 *   <li>资源复用策略：减少内存分配和释放频率</li>
 *   <li>异步资源传输：与渲染计算重叠进行数据传输</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class FrameGraphConfig {

    /** 是否启用 Pass 合并优化 */
    private boolean passMergingEnabled = true;

    /** 最大并行 Pass 数量 */
    private int maxParallelPasses = 4;

    /** 资源复用策略 (0=保守, 1=平衡, 2=激进) */
    private int resourceReuseStrategy = 1;

    /** 是否启用异步资源传输 */
    private boolean asyncTransferEnabled = true;

    /**
     * 默认构造函数
     */
    public FrameGraphConfig() {}

    /**
     * 从 Properties 对象加载配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.frameGraph."
     * @return FrameGraphConfig 实例
     */
    public static FrameGraphConfig fromProperties(Properties props) {
        FrameGraphConfig config = new FrameGraphConfig();
        config.passMergingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.frameGraph.passMerging", "true")
        );
        config.maxParallelPasses = Integer.parseInt(
            props.getProperty("blaze3d.frameGraph.maxParallelPasses", "4")
        );
        config.resourceReuseStrategy = Integer.parseInt(
            props.getProperty("blaze3d.frameGraph.resourceReuseStrategy", "1")
        );
        config.asyncTransferEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.frameGraph.asyncTransfer", "true")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.frameGraph.passMerging", String.valueOf(passMergingEnabled));
        props.setProperty("blaze3d.frameGraph.maxParallelPasses", String.valueOf(maxParallelPasses));
        props.setProperty("blaze3d.frameGraph.resourceReuseStrategy", String.valueOf(resourceReuseStrategy));
        props.setProperty("blaze3d.frameGraph.asyncTransfer", String.valueOf(asyncTransferEnabled));
    }

    /**
     * 是否启用 Pass 合并优化
     *
     * @return true 如果启用 Pass 合并
     */
    public boolean isPassMergingEnabled() { return passMergingEnabled; }

    /**
     * 设置是否启用 Pass 合并优化
     *
     * @param enabled 是否启用
     */
    public void setPassMergingEnabled(boolean enabled) { this.passMergingEnabled = enabled; }

    /**
     * 获取最大并行 Pass 数量
     *
     * @return 最大并行 Pass 数
     */
    public int getMaxParallelPasses() { return maxParallelPasses; }

    /**
     * 设置最大并行 Pass 数量
     *
     * @param max 最大并行 Pass 数
     */
    public void setMaxParallelPasses(int max) { this.maxParallelPasses = max; }

    /**
     * 获取资源复用策略
     *
     * @return 策略值 (0=保守, 1=平衡, 2=激进)
     */
    public int getResourceReuseStrategy() { return resourceReuseStrategy; }

    /**
     * 设置资源复用策略
     *
     * @param strategy 策略值 (0=保守, 1=平衡, 2=激进)
     */
    public void setResourceReuseStrategy(int strategy) { this.resourceReuseStrategy = strategy; }

    /**
     * 是否启用异步资源传输
     *
     * @return true 如果启用异步传输
     */
    public boolean isAsyncTransferEnabled() { return asyncTransferEnabled; }

    /**
     * 设置是否启用异步资源传输
     *
     * @param enabled 是否启用
     */
    public void setAsyncTransferEnabled(boolean enabled) { this.asyncTransferEnabled = enabled; }
}
