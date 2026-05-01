package com.ranecc.renderium.domain.model.config;

import java.util.Properties;

/**
 * 现代 Render 架构配置
 *
 * <p>控制现代渲染架构的高级特性，包括：
 * <ul>
 *   <li>Hi-Z（层次化 Z 缓冲）用于高效遮挡剔除</li>
 *   <li>Bindless Texturing 无绑定纹理访问</li>
 *   <li>ECS（实体组件系统）场景图管理</li>
 *   <li>GPU Driven Culling GPU 驱动的视锥和遮挡剔除</li>
 * </ul>
 *
 * <p>仅适用于支持 Compute Shader 的现代 GPU，
 * 仅在狂暴模式（Aggressive）下生效。
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class ModernConfig {

    /** Hi-Z 缓冲区尺寸（像素），默认 1024x1024 */
    private int hiZBufferSize = 1024;

    /** Bindless 纹理最大数量，默认 4096 */
    private int bindlessMaxTextures = 4096;

    /** 是否启用 ECS 场景图架构，默认 true */
    private boolean ecsSceneGraphEnabled = true;

    /** 是否启用 GPU 驱动剔除（Compute Shader 实现），默认 true */
    private boolean gpuDrivenCullingEnabled = true;

    /** 视锥剔除计算着色器工作组大小，默认 256 */
    private int frustumCullWorkgroupSize = 256;

    /** Hi-Z 遮挡剔除计算着色器工作组大小，默认 64 */
    private int hizOcclusionWorkgroupSize = 64;

    /**
     * 默认构造函数 - 启用所有现代特性
     */
    public ModernConfig() {}

    /**
     * 从 Properties 对象加载现代架构配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.modern."
     * @return ModernConfig 实例
     */
    public static ModernConfig fromProperties(Properties props) {
        ModernConfig config = new ModernConfig();
        config.hiZBufferSize = Integer.parseInt(
            props.getProperty("blaze3d.modern.hizBufferSize", "1024")
        );
        config.bindlessMaxTextures = Integer.parseInt(
            props.getProperty("blaze3d.modern.bindlessMaxTextures", "4096")
        );
        config.ecsSceneGraphEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.modern.ecsSceneGraph", "true")
        );
        config.gpuDrivenCullingEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.modern.gpuDrivenCulling", "true")
        );
        config.frustumCullWorkgroupSize = Integer.parseInt(
            props.getProperty("blaze3d.modern.frustumWorkgroupSize", "256")
        );
        config.hizOcclusionWorkgroupSize = Integer.parseInt(
            props.getProperty("blaze3d.modern.hizOcclusionWorkgroupSize", "64")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.modern.hizBufferSize", String.valueOf(hiZBufferSize));
        props.setProperty("blaze3d.modern.bindlessMaxTextures", String.valueOf(bindlessMaxTextures));
        props.setProperty("blaze3d.modern.ecsSceneGraph", String.valueOf(ecsSceneGraphEnabled));
        props.setProperty("blaze3d.modern.gpuDrivenCulling", String.valueOf(gpuDrivenCullingEnabled));
        props.setProperty("blaze3d.modern.frustumWorkgroupSize", String.valueOf(frustumCullWorkgroupSize));
        props.setProperty("blaze3d.modern.hizOcclusionWorkgroupSize", String.valueOf(hizOcclusionWorkgroupSize));
    }

    /**
     * 获取 Hi-Z 缓冲区尺寸
     *
     * @return 缓冲区尺寸（像素）
     */
    public int getHiZBufferSize() { return hiZBufferSize; }

    /**
     * 设置 Hi-Z 缓冲区尺寸
     *
     * @param size 缓冲区尺寸（像素）
     */
    public void setHiZBufferSize(int size) { this.hiZBufferSize = size; }

    /**
     * 获取 Bindless 纹理最大数量
     *
     * @return 最大纹理数量
     */
    public int getBindlessMaxTextures() { return bindlessMaxTextures; }

    /**
     * 设置 Bindless 纹理最大数量
     *
     * @param max 最大纹理数量
     */
    public void setBindlessMaxTextures(int max) { this.bindlessMaxTextures = max; }

    /**
     * 是否启用 ECS 场景图架构
     *
     * @return true 如果启用 ECS
     */
    public boolean isEcsSceneGraphEnabled() { return ecsSceneGraphEnabled; }

    /**
     * 设置是否启用 ECS 场景图架构
     *
     * @param enabled 是否启用
     */
    public void setEcsSceneGraphEnabled(boolean enabled) { this.ecsSceneGraphEnabled = enabled; }

    /**
     * 是否启用 GPU 驱动剔除
     *
     * @return true 如果启用 GPU 驱动剔除
     */
    public boolean isGpuDrivenCullingEnabled() { return gpuDrivenCullingEnabled; }

    /**
     * 设置是否启用 GPU 驱动剔除
     *
     * @param enabled 是否启用
     */
    public void setGpuDrivenCullingEnabled(boolean enabled) { this.gpuDrivenCullingEnabled = enabled; }

    /**
     * 获取视锥剔除计算着色器工作组大小
     *
     * @return 工作组大小
     */
    public int getFrustumCullWorkgroupSize() { return frustumCullWorkgroupSize; }

    /**
     * 设置视锥剔除计算着色器工作组大小
     *
     * @param size 工作组大小
     */
    public void setFrustumCullWorkgroupSize(int size) { this.frustumCullWorkgroupSize = size; }

    /**
     * 获取 Hi-Z 遮挡剔除计算着色器工作组大小
     *
     * @return 工作组大小
     */
    public int getHizOcclusionWorkgroupSize() { return hizOcclusionWorkgroupSize; }

    /**
     * 设置 Hi-Z 遮挡剔除计算着色器工作组大小
     *
     * @param size 工作组大小
     */
    public void setHizOcclusionWorkgroupSize(int size) { this.hizOcclusionWorkgroupSize = size; }
}
