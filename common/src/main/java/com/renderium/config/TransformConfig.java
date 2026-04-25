package com.renderium.config;

import java.util.Properties;

/**
 * GPU 变换与合并优化配置
 *
 * <p>控制 GPU 端的变换矩阵计算、实例化渲染合并、静态几何体缓存等高级优化策略。
 * 仅适用于支持 Instancing 和 SSBO 的现代 GPU，仅在狂暴模式（Aggressive）下生效。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>全局顶点缓冲区：统一管理所有 Chunk 的顶点数据</li>
 *   <li>实例变换缓冲：GPU 驱动的实例化渲染</li>
 *   <li>静态几何体缓存：对不变 geometry 跳过重复上传</li>
 *   <li>层级批量合并：按 Y 层级和材质类型合并 DrawCall</li>
 *   <li>材质合并渲染：相同材质的 Mesh 合并为单次 DrawCall</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class TransformConfig {

    /** 最大可管理的 Chunk 数量，默认 65536 */
    private int maxChunks = 65536;

    /** 全局顶点缓冲区大小 (MB)，默认 512MB */
    private int globalVertexBufferSizeMB = 512;

    /** 实例变换缓冲区大小 (MB)，默认 64MB */
    private int instanceTransformBufferSizeMB = 64;

    /** 是否启用静态几何体缓存（不变 geometry 跳过重复上传），默认 true */
    private boolean staticGeometryCacheEnabled = true;

    /** 提升到静态缓存的帧数阈值（连续 N 帧未变化则视为静态），默认 60 帧 */
    private int staticPromoteFrames = 60;

    /** 是否启用层级批量合并（按 Y 层级分组），默认 true */
    private boolean layerBatchingEnabled = true;

    /** 是否启用材质合并渲染（相同材质合并为单次 DrawCall），默认 true */
    private boolean materialMergingEnabled = true;

    /**
     * 默认构造函数 - 启用所有变换优化特性
     */
    public TransformConfig() {}

    /**
     * 从 Properties 对象加载变换合并配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.transform."
     * @return TransformConfig 实例
     */
    public static TransformConfig fromProperties(Properties props) {
        TransformConfig config = new TransformConfig();
        config.maxChunks = Integer.parseInt(
            props.getProperty("blaze3d.transform.maxChunks", "65536")
        );
        config.globalVertexBufferSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.transform.globalVertexBufferMB", "512")
        );
        config.instanceTransformBufferSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.transform.instanceTransformBufferMB", "64")
        );
        config.staticGeometryCacheEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.transform.staticGeometryCache", "true")
        );
        config.staticPromoteFrames = Integer.parseInt(
            props.getProperty("blaze3d.transform.staticPromoteFrames", "60")
        );
        config.layerBatchingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.transform.layerBatching", "true")
        );
        config.materialMergingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.transform.materialMerging", "true")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.transform.maxChunks", String.valueOf(maxChunks));
        props.setProperty("blaze3d.transform.globalVertexBufferMB", String.valueOf(globalVertexBufferSizeMB));
        props.setProperty("blaze3d.transform.instanceTransformBufferMB", String.valueOf(instanceTransformBufferSizeMB));
        props.setProperty("blaze3d.transform.staticGeometryCache", String.valueOf(staticGeometryCacheEnabled));
        props.setProperty("blaze3d.transform.staticPromoteFrames", String.valueOf(staticPromoteFrames));
        props.setProperty("blaze3d.transform.layerBatching", String.valueOf(layerBatchingEnabled));
        props.setProperty("blaze3d.transform.materialMerging", String.valueOf(materialMergingEnabled));
    }

    /**
     * 获取最大可管理的 Chunk 数量
     *
     * @return 最大 Chunk 数
     */
    public int getMaxChunks() { return maxChunks; }

    /**
     * 设置最大可管理的 Chunk 数量
     *
     * @param max 最大 Chunk 数
     */
    public void setMaxChunks(int max) { this.maxChunks = max; }

    /**
     * 获取全局顶点缓冲区大小
     *
     * @return 缓冲区大小 (MB)
     */
    public int getGlobalVertexBufferSizeMB() { return globalVertexBufferSizeMB; }

    /**
     * 设置全局顶点缓冲区大小
     *
     * @param sizeMB 缓冲区大小 (MB)
     */
    public void setGlobalVertexBufferSizeMB(int sizeMB) { this.globalVertexBufferSizeMB = sizeMB; }

    /**
     * 获取实例变换缓冲区大小
     *
     * @return 缓冲区大小 (MB)
     */
    public int getInstanceTransformBufferSizeMB() { return instanceTransformBufferSizeMB; }

    /**
     * 设置实例变换缓冲区大小
     *
     * @param sizeMB 缓冲区大小 (MB)
     */
    public void setInstanceTransformBufferSizeMB(int sizeMB) { this.instanceTransformBufferSizeMB = sizeMB; }

    /**
     * 是否启用静态几何体缓存
     *
     * @return true 如果启用静态缓存
     */
    public boolean isStaticGeometryCacheEnabled() { return staticGeometryCacheEnabled; }

    /**
     * 设置是否启用静态几何体缓存
     *
     * @param enabled 是否启用
     */
    public void setStaticGeometryCacheEnabled(boolean enabled) { this.staticGeometryCacheEnabled = enabled; }

    /**
     * 获取提升到静态缓存的帧数阈值
     *
     * @return 帧数阈值
     */
    public int getStaticPromoteFrames() { return staticPromoteFrames; }

    /**
     * 设置提升到静态缓存的帧数阈值
     *
     * @param frames 帧数阈值
     */
    public void setStaticPromoteFrames(int frames) { this.staticPromoteFrames = frames; }

    /**
     * 是否启用层级批量合并
     *
     * @return true 如果启用层级合并
     */
    public boolean isLayerBatchingEnabled() { return layerBatchingEnabled; }

    /**
     * 设置是否启用层级批量合并
     *
     * @param enabled 是否启用
     */
    public void setLayerBatchingEnabled(boolean enabled) { this.layerBatchingEnabled = enabled; }

    /**
     * 是否启用材质合并渲染
     *
     * @return true 如果启用材质合并
     */
    public boolean isMaterialMergingEnabled() { return materialMergingEnabled; }

    /**
     * 设置是否启用材质合并渲染
     *
     * @param enabled 是否启用
     */
    public void setMaterialMergingEnabled(boolean enabled) { this.materialMergingEnabled = enabled; }
}
