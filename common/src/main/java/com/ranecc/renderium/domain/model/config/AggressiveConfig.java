package com.ranecc.renderium.domain.model.config;

import java.util.Properties;

/**
 * 激进 Minecraft 优化配置
 *
 * <p>控制针对 Minecraft 渲染管线的激进优化策略，包括顶点压缩、
 * 批量 DrawCall 合并、异步上传等。
 * 仅在狂暴模式（Aggressive）下生效。
 *
 * <h3>优化模块</h3>
 * <ul>
 *   <li><b>AG1 - 顶点压缩</b>：使用量化算法减少顶点数据大小</li>
 *   <li><b>AG2 - 激进批量合并</b>：将多个 Chunk 合并为单次 DrawCall</li>
 *   <li><b>AG3 - 异步上传</b>：使用独立线程进行缓冲区数据上传</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class AggressiveConfig {

    /** 是否启用顶点压缩 (AG1)，默认 false */
    private boolean vertexCompressionEnabled = false;

    /** 是否启用激进批量合并 (AG2)，默认 false */
    private boolean aggressiveBatchingEnabled = false;

    /** 是否启用异步上传 (AG3)，默认 false */
    private boolean asyncUploadEnabled = false;

    /** 单批次最大 Chunk 数量，默认 256 */
    private int maxChunksPerBatch = 256;

    /** 异步上传阈值 (KB)，超过此大小才使用异步上传，默认 16KB */
    private int asyncUploadThresholdKB = 16;

    /** 每帧最大异步上传次数，默认 4 次 */
    private int maxUploadsPerFrame = 4;

    /** 暂存缓冲区大小 (MB)，默认 128MB（双缓冲设计） */
    private int stagingBufferSizeMB = 128;

    /**
     * 默认构造函数 - 使用保守默认值（所有激进优化默认关闭）
     */
    public AggressiveConfig() {}

    /**
     * 从 Properties 对象加载激进优化配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.aggressive."
     * @return AggressiveConfig 实例
     */
    public static AggressiveConfig fromProperties(Properties props) {
        AggressiveConfig config = new AggressiveConfig();
        config.vertexCompressionEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.aggressive.vertexCompression", "false")
        );
        config.aggressiveBatchingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.aggressive.batchMerging", "false")
        );
        config.asyncUploadEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.aggressive.asyncUpload", "false")
        );
        config.maxChunksPerBatch = Integer.parseInt(
            props.getProperty("blaze3d.aggressive.maxChunksPerBatch", "256")
        );
        config.asyncUploadThresholdKB = Integer.parseInt(
            props.getProperty("blaze3d.aggressive.uploadThresholdKB", "16")
        );
        config.maxUploadsPerFrame = Integer.parseInt(
            props.getProperty("blaze3d.aggressive.maxUploadsPerFrame", "4")
        );
        config.stagingBufferSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.aggressive.stagingBufferMB", "128")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.aggressive.vertexCompression", String.valueOf(vertexCompressionEnabled));
        props.setProperty("blaze3d.aggressive.batchMerging", String.valueOf(aggressiveBatchingEnabled));
        props.setProperty("blaze3d.aggressive.asyncUpload", String.valueOf(asyncUploadEnabled));
        props.setProperty("blaze3d.aggressive.maxChunksPerBatch", String.valueOf(maxChunksPerBatch));
        props.setProperty("blaze3d.aggressive.uploadThresholdKB", String.valueOf(asyncUploadThresholdKB));
        props.setProperty("blaze3d.aggressive.maxUploadsPerFrame", String.valueOf(maxUploadsPerFrame));
        props.setProperty("blaze3d.aggressive.stagingBufferMB", String.valueOf(stagingBufferSizeMB));
    }

    /**
     * 是否启用顶点压缩
     *
     * @return true 如果启用顶点压缩
     */
    public boolean isVertexCompressionEnabled() { return vertexCompressionEnabled; }

    /**
     * 设置是否启用顶点压缩
     *
     * @param enabled 是否启用
     */
    public void setVertexCompressionEnabled(boolean enabled) { this.vertexCompressionEnabled = enabled; }

    /**
     * 是否启用激进批量合并
     *
     * @return true 如果启用激进批量合并
     */
    public boolean isAggressiveBatchingEnabled() { return aggressiveBatchingEnabled; }

    /**
     * 设置是否启用激进批量合并
     *
     * @param enabled 是否启用
     */
    public void setAggressiveBatchingEnabled(boolean enabled) { this.aggressiveBatchingEnabled = enabled; }

    /**
     * 是否启用异步上传
     *
     * @return true 如果启用异步上传
     */
    public boolean isAsyncUploadEnabled() { return asyncUploadEnabled; }

    /**
     * 设置是否启用异步上传
     *
     * @param enabled 是否启用
     */
    public void setAsyncUploadEnabled(boolean enabled) { this.asyncUploadEnabled = enabled; }

    /**
     * 获取单批次最大 Chunk 数量
     *
     * @return 最大 Chunk 数
     */
    public int getMaxChunksPerBatch() { return maxChunksPerBatch; }

    /**
     * 设置单批次最大 Chunk 数量
     *
     * @param max 最大 Chunk 数
     */
    public void setMaxChunksPerBatch(int max) { this.maxChunksPerBatch = max; }

    /**
     * 获取异步上传阈值
     *
     * @return 阈值 (KB)
     */
    public int getAsyncUploadThresholdKB() { return asyncUploadThresholdKB; }

    /**
     * 设置异步上传阈值
     *
     * @param thresholdKB 阈值 (KB)
     */
    public void setAsyncUploadThresholdKB(int thresholdKB) { this.asyncUploadThresholdKB = thresholdKB; }

    /**
     * 获取每帧最大异步上传次数
     *
     * @return 最大上传次数
     */
    public int getMaxUploadsPerFrame() { return maxUploadsPerFrame; }

    /**
     * 设置每帧最大异步上传次数
     *
     * @param max 最大上传次数
     */
    public void setMaxUploadsPerFrame(int max) { this.maxUploadsPerFrame = max; }

    /**
     * 获取暂存缓冲区大小
     *
     * @return 缓冲区大小 (MB)
     */
    public int getStagingBufferSizeMB() { return stagingBufferSizeMB; }

    /**
     * 设置暂存缓冲区大小
     *
     * @param sizeMB 缓冲区大小 (MB)
     */
    public void setStagingBufferSizeMB(int sizeMB) { this.stagingBufferSizeMB = sizeMB; }
}
