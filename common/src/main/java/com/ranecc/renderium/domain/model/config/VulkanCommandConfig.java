package com.ranecc.renderium.domain.model.config;

import java.util.Properties;

/**
 * Vulkan 命令缓冲区优化配置
 *
 * <p>控制 Vulkan 命令的批量提交、异步执行、多队列调度等高级优化策略。
 * 仅在狂暴模式（Aggressive）下生效。
 *
 * <h3>主要功能</h3>
 * <ul>
 *   <li>命令批量合并：减少 API 调用开销</li>
 *   <li>多队列并行提交：利用图形、计算、传输队列的并行性</li>
 *   <li>Command Buffer 预编译：减少帧间编译开销</li>
 *   <li>计算队列优先级控制：确保计算任务的调度优先级</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class VulkanCommandConfig {

    /** 是否启用命令批量合并 */
    private boolean batchMergingEnabled = true;

    /** 单批次最大命令数 */
    private int maxCommandsPerBatch = 64;

    /** 是否启用多队列并行提交 */
    private boolean multiQueueSubmission = true;

    /** 计算队列优先级 (低/中/高) */
    private String computeQueuePriority = "high";

    /** 是否预编译 Command Buffer */
    private boolean precompileCommandBuffers = true;

    /**
     * 默认构造函数
     */
    public VulkanCommandConfig() {}

    /**
     * 从 Properties 对象加载配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.vulkanCommand."
     * @return VulkanCommandConfig 实例
     */
    public static VulkanCommandConfig fromProperties(Properties props) {
        VulkanCommandConfig config = new VulkanCommandConfig();
        config.batchMergingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.vulkanCommand.batchMerging", "true")
        );
        config.maxCommandsPerBatch = Integer.parseInt(
            props.getProperty("blaze3d.vulkanCommand.maxBatchSize", "64")
        );
        config.multiQueueSubmission = Boolean.parseBoolean(
            props.getProperty("blaze3d.vulkanCommand.multiQueue", "true")
        );
        config.computeQueuePriority = props.getProperty("blaze3d.vulkanCommand.computePriority", "high");
        config.precompileCommandBuffers = Boolean.parseBoolean(
            props.getProperty("blaze3d.vulkanCommand.precompile", "true")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.vulkanCommand.batchMerging", String.valueOf(batchMergingEnabled));
        props.setProperty("blaze3d.vulkanCommand.maxBatchSize", String.valueOf(maxCommandsPerBatch));
        props.setProperty("blaze3d.vulkanCommand.multiQueue", String.valueOf(multiQueueSubmission));
        props.setProperty("blaze3d.vulkanCommand.computePriority", computeQueuePriority);
        props.setProperty("blaze3d.vulkanCommand.precompile", String.valueOf(precompileCommandBuffers));
    }

    /**
     * 是否启用命令批量合并
     *
     * @return true 如果启用命令批量合并
     */
    public boolean isBatchMergingEnabled() { return batchMergingEnabled; }

    /**
     * 设置是否启用命令批量合并
     *
     * @param enabled 是否启用
     */
    public void setBatchMergingEnabled(boolean enabled) { this.batchMergingEnabled = enabled; }

    /**
     * 获取单批次最大命令数
     *
     * @return 最大命令数
     */
    public int getMaxCommandsPerBatch() { return maxCommandsPerBatch; }

    /**
     * 设置单批次最大命令数
     *
     * @param max 最大命令数
     */
    public void setMaxCommandsPerBatch(int max) { this.maxCommandsPerBatch = max; }

    /**
     * 是否启用多队列并行提交
     *
     * @return true 如果启用多队列提交
     */
    public boolean isMultiQueueSubmission() { return multiQueueSubmission; }

    /**
     * 设置是否启用多队列并行提交
     *
     * @param enabled 是否启用
     */
    public void setMultiQueueSubmission(boolean enabled) { this.multiQueueSubmission = enabled; }

    /**
     * 获取计算队列优先级
     *
     * @return 优先级 (低/中/高)
     */
    public String getComputeQueuePriority() { return computeQueuePriority; }

    /**
     * 设置计算队列优先级
     *
     * @param priority 优先级 (低/中/高)
     */
    public void setComputeQueuePriority(String priority) { this.computeQueuePriority = priority; }

    /**
     * 是否预编译 Command Buffer
     *
     * @return true 如果启用预编译
     */
    public boolean isPrecompileCommandBuffers() { return precompileCommandBuffers; }

    /**
     * 设置是否预编译 Command Buffer
     *
     * @param enabled 是否启用
     */
    public void setPrecompileCommandBuffers(boolean enabled) { this.precompileCommandBuffers = enabled; }
}
