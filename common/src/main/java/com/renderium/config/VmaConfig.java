package com.renderium.config;

import java.util.Properties;

/**
 * VMA（Vulkan Memory Allocator）激进优化配置
 *
 * <p>控制 Vulkan 内存分配器的激进优化策略，包括内存池预分配、
 * 资源别名、延迟释放等高级特性。
 * 仅在狂暴模式（Aggressive）下生效。
 *
 * <h3>主要特性</h3>
 * <ul>
 *   <li>内存池分类管理：顶点/索引/Uniform/暂存/纹理/渲染目标</li>
 *   <li>水位线监控：高水位线(85%)和临界水位线(95%)告警</li>
 *   <li>资源别名技术：复用相同大小的内存块</li>
 *   <li>持久化映射：减少 Map/Unmap 开销</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class VmaConfig {

    /** VMA 内存预算上限 (字节)，默认 6GB */
    private long memoryBudgetBytes = 6L * 1024 * 1024 * 1024;

    /** 顶点缓冲区池大小 (MB)，默认 512MB */
    private int vertexPoolSizeMB = 512;

    /** 索引缓冲区池大小 (MB)，默认 256MB */
    private int indexPoolSizeMB = 256;

    /** Uniform 缓冲区池大小 (MB)，默认 128MB */
    private int uniformPoolSizeMB = 128;

    /** 暂存缓冲区池大小 (MB)，默认 256MB */
    private int stagingPoolSizeMB = 256;

    /** 纹理缓冲区池大小 (MB)，默认 1024MB (1GB) */
    private int texturePoolSizeMB = 1024;

    /** 渲染目标池大小 (MB)，默认 512MB */
    private int renderTargetPoolSizeMB = 512;

    /** 延迟释放帧数，默认 3 帧（避免 GPU 仍在使用时释放） */
    private int deferredReleaseFrames = 3;

    /** 高水位线阈值 (0.0 - 1.0)，默认 0.85，超过此值触发内存回收 */
    private float highWaterMark = 0.85f;

    /** 临界水位线阈值 (0.0 - 1.0)，默认 0.95，超过此值强制释放 */
    private float criticalMark = 0.95f;

    /** 是否启用资源别名（内存块复用），默认 true */
    private boolean aliasingEnabled = true;

    /** 是否启用持久化映射（Persistent Mapping），默认 false */
    private boolean persistentMappingEnabled = false;

    /**
     * 默认构造函数 - 使用合理的默认值
     */
    public VmaConfig() {}

    /**
     * 从 Properties 对象加载 VMA 配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.vma."
     * @return VmaConfig 实例
     */
    public static VmaConfig fromProperties(Properties props) {
        VmaConfig config = new VmaConfig();
        config.memoryBudgetBytes = Long.parseLong(
            props.getProperty("blaze3d.vma.memoryBudgetBytes", String.valueOf(6L * 1024 * 1024 * 1024))
        );
        config.vertexPoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.vma.vertexPoolMB", "512")
        );
        config.indexPoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.vma.indexPoolMB", "256")
        );
        config.uniformPoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.vma.uniformPoolMB", "128")
        );
        config.stagingPoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.vma.stagingPoolMB", "256")
        );
        config.texturePoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.vma.texturePoolMB", "1024")
        );
        config.renderTargetPoolSizeMB = Integer.parseInt(
            props.getProperty("blaze3d.vma.renderTargetPoolMB", "512")
        );
        config.deferredReleaseFrames = Integer.parseInt(
            props.getProperty("blaze3d.vma.deferredReleaseFrames", "3")
        );
        config.highWaterMark = Float.parseFloat(
            props.getProperty("blaze3d.vma.highWaterMark", "0.85")
        );
        config.criticalMark = Float.parseFloat(
            props.getProperty("blaze3d.vma.criticalMark", "0.95")
        );
        config.aliasingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.vma.aliasing", "true")
        );
        config.persistentMappingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.vma.persistentMapping", "false")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.vma.memoryBudgetBytes", String.valueOf(memoryBudgetBytes));
        props.setProperty("blaze3d.vma.vertexPoolMB", String.valueOf(vertexPoolSizeMB));
        props.setProperty("blaze3d.vma.indexPoolMB", String.valueOf(indexPoolSizeMB));
        props.setProperty("blaze3d.vma.uniformPoolMB", String.valueOf(uniformPoolSizeMB));
        props.setProperty("blaze3d.vma.stagingPoolMB", String.valueOf(stagingPoolSizeMB));
        props.setProperty("blaze3d.vma.texturePoolMB", String.valueOf(texturePoolSizeMB));
        props.setProperty("blaze3d.vma.renderTargetPoolMB", String.valueOf(renderTargetPoolSizeMB));
        props.setProperty("blaze3d.vma.deferredReleaseFrames", String.valueOf(deferredReleaseFrames));
        props.setProperty("blaze3d.vma.highWaterMark", String.valueOf(highWaterMark));
        props.setProperty("blaze3d.vma.criticalMark", String.valueOf(criticalMark));
        props.setProperty("blaze3d.vma.aliasing", String.valueOf(aliasingEnabled));
        props.setProperty("blaze3d.vma.persistentMapping", String.valueOf(persistentMappingEnabled));
    }

    /**
     * 获取内存预算上限
     *
     * @return 预算上限 (字节)
     */
    public long getMemoryBudgetBytes() { return memoryBudgetBytes; }

    /**
     * 设置内存预算上限
     *
     * @param bytes 预算上限 (字节)
     */
    public void setMemoryBudgetBytes(long bytes) { this.memoryBudgetBytes = bytes; }

    /**
     * 获取顶点缓冲区池大小
     *
     * @return 池大小 (MB)
     */
    public int getVertexPoolSizeMB() { return vertexPoolSizeMB; }

    /**
     * 设置顶点缓冲区池大小
     *
     * @param sizeMB 池大小 (MB)
     */
    public void setVertexPoolSizeMB(int sizeMB) { this.vertexPoolSizeMB = sizeMB; }

    /**
     * 获取索引缓冲区池大小
     *
     * @return 池大小 (MB)
     */
    public int getIndexPoolSizeMB() { return indexPoolSizeMB; }

    /**
     * 设置索引缓冲区池大小
     *
     * @param sizeMB 池大小 (MB)
     */
    public void setIndexPoolSizeMB(int sizeMB) { this.indexPoolSizeMB = sizeMB; }

    /**
     * 获取 Uniform 缓冲区池大小
     *
     * @return 池大小 (MB)
     */
    public int getUniformPoolSizeMB() { return uniformPoolSizeMB; }

    /**
     * 设置 Uniform 缓冲区池大小
     *
     * @param sizeMB 池大小 (MB)
     */
    public void setUniformPoolSizeMB(int sizeMB) { this.uniformPoolSizeMB = sizeMB; }

    /**
     * 获取暂存缓冲区池大小
     *
     * @return 池大小 (MB)
     */
    public int getStagingPoolSizeMB() { return stagingPoolSizeMB; }

    /**
     * 设置暂存缓冲区池大小
     *
     * @param sizeMB 池大小 (MB)
     */
    public void setStagingPoolSizeMB(int sizeMB) { this.stagingPoolSizeMB = sizeMB; }

    /**
     * 获取纹理缓冲区池大小
     *
     * @return 池大小 (MB)
     */
    public int getTexturePoolSizeMB() { return texturePoolSizeMB; }

    /**
     * 设置纹理缓冲区池大小
     *
     * @param sizeMB 池大小 (MB)
     */
    public void setTexturePoolSizeMB(int sizeMB) { this.texturePoolSizeMB = sizeMB; }

    /**
     * 获取渲染目标池大小
     *
     * @return 池大小 (MB)
     */
    public int getRenderTargetPoolSizeMB() { return renderTargetPoolSizeMB; }

    /**
     * 设置渲染目标池大小
     *
     * @param sizeMB 池大小 (MB)
     */
    public void setRenderTargetPoolSizeMB(int sizeMB) { this.renderTargetPoolSizeMB = sizeMB; }

    /**
     * 获取延迟释放帧数
     *
     * @return 延迟帧数
     */
    public int getDeferredReleaseFrames() { return deferredReleaseFrames; }

    /**
     * 设置延迟释放帧数
     *
     * @param frames 延迟帧数
     */
    public void setDeferredReleaseFrames(int frames) { this.deferredReleaseFrames = frames; }

    /**
     * 获取高水位线阈值
     *
     * @return 高水位线 (0.0 - 1.0)
     */
    public float getHighWaterMark() { return highWaterMark; }

    /**
     * 设置高水位线阈值
     *
     * @param mark 高水位线 (0.0 - 1.0)
     */
    public void setHighWaterMark(float mark) { this.highWaterMark = mark; }

    /**
     * 获取临界水位线阈值
     *
     * @return 临界水位线 (0.0 - 1.0)
     */
    public float getCriticalMark() { return criticalMark; }

    /**
     * 设置临界水位线阈值
     *
     * @param mark 临界水位线 (0.0 - 1.0)
     */
    public void setCriticalMark(float mark) { this.criticalMark = mark; }

    /**
     * 是否启用资源别名
     *
     * @return true 如果启用资源别名
     */
    public boolean isAliasingEnabled() { return aliasingEnabled; }

    /**
     * 设置是否启用资源别名
     *
     * @param enabled 是否启用
     */
    public void setAliasingEnabled(boolean enabled) { this.aliasingEnabled = enabled; }

    /**
     * 是否启用持久化映射
     *
     * @return true 如果启用持久化映射
     */
    public boolean isPersistentMappingEnabled() { return persistentMappingEnabled; }

    /**
     * 设置是否启用持久化映射
     *
     * @param enabled 是否启用
     */
    public void setPersistentMappingEnabled(boolean enabled) { this.persistentMappingEnabled = enabled; }
}
