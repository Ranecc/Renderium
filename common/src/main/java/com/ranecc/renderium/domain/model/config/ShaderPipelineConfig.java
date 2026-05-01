package com.ranecc.renderium.domain.model.config;

import java.util.Properties;

/**
 * 着色器管线优化配置
 *
 * <p>控制 SPIR-V 着色器的编译缓存、管线变体管理、特化常量优化等。
 * 仅在狂暴模式（Aggressive）下生效。
 *
 * <h3>主要功能</h3>
 * <ul>
 *   <li>SPIR-V 缓存：缓存已编译的着色器，避免重复编译</li>
 *   <li>管线缓存管理：限制缓存条目数，防止内存泄漏</li>
 *   <li>特化常量优化：在编译期固化常量值，提高运行时性能</li>
 *   <li>并行编译：使用多线程编译着色器，减少初始化时间</li>
 *   <li>热更新支持：开发调试时可动态重新加载着色器</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 5.0
 */
public final class ShaderPipelineConfig {

    /** 是否启用 SPIR-V 缓存 */
    private boolean spirvCacheEnabled = true;

    /** 管线缓存最大条目数 */
    private int maxPipelineCacheEntries = 1024;

    /** 是否启用着色器特化常量优化 */
    private boolean specializationConstantsEnabled = true;

    /** 并行编译线程数 (0=自动检测CPU核心数) */
    private int parallelCompileThreads = 0;

    /** 热更新支持（开发调试用） */
    private boolean hotReloadSupported = false;

    /**
     * 默认构造函数
     */
    public ShaderPipelineConfig() {}

    /**
     * 从 Properties 对象加载配置
     *
     * @param props 属性集合，键前缀为 "blaze3d.shaderPipeline."
     * @return ShaderPipelineConfig 实例
     */
    public static ShaderPipelineConfig fromProperties(Properties props) {
        ShaderPipelineConfig config = new ShaderPipelineConfig();
        config.spirvCacheEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.shaderPipeline.spirvCache", "true")
        );
        config.maxPipelineCacheEntries = Integer.parseInt(
            props.getProperty("blaze3d.shaderPipeline.maxCacheEntries", "1024")
        );
        config.specializationConstantsEnabled = Boolean.parseBoolean(
            props.getProperty("blaze3d.shaderPipeline.specializationConstants", "true")
        );
        config.parallelCompileThreads = Integer.parseInt(
            props.getProperty("blaze3d.shaderPipeline.compileThreads", "0")
        );
        config.hotReloadSupported = Boolean.parseBoolean(
            props.getProperty("blaze3d.shaderPipeline.hotReload", "false")
        );
        return config;
    }

    /**
     * 将配置写入 Properties 对象
     *
     * @param props 属性集合
     */
    public void toProperties(Properties props) {
        props.setProperty("blaze3d.shaderPipeline.spirvCache", String.valueOf(spirvCacheEnabled));
        props.setProperty("blaze3d.shaderPipeline.maxCacheEntries", String.valueOf(maxPipelineCacheEntries));
        props.setProperty("blaze3d.shaderPipeline.specializationConstants", String.valueOf(specializationConstantsEnabled));
        props.setProperty("blaze3d.shaderPipeline.compileThreads", String.valueOf(parallelCompileThreads));
        props.setProperty("blaze3d.shaderPipeline.hotReload", String.valueOf(hotReloadSupported));
    }

    /**
     * 是否启用 SPIR-V 缓存
     *
     * @return true 如果启用 SPIR-V 缓存
     */
    public boolean isSpirvCacheEnabled() { return spirvCacheEnabled; }

    /**
     * 设置是否启用 SPIR-V 缓存
     *
     * @param enabled 是否启用
     */
    public void setSpirvCacheEnabled(boolean enabled) { this.spirvCacheEnabled = enabled; }

    /**
     * 获取管线缓存最大条目数
     *
     * @return 最大条目数
     */
    public int getMaxPipelineCacheEntries() { return maxPipelineCacheEntries; }

    /**
     * 设置管线缓存最大条目数
     *
     * @param max 最大条目数
     */
    public void setMaxPipelineCacheEntries(int max) { this.maxPipelineCacheEntries = max; }

    /**
     * 是否启用着色器特化常量优化
     *
     * @return true 如果启用特化常量
     */
    public boolean isSpecializationConstantsEnabled() { return specializationConstantsEnabled; }

    /**
     * 设置是否启用着色器特化常量优化
     *
     * @param enabled 是否启用
     */
    public void setSpecializationConstantsEnabled(boolean enabled) { this.specializationConstantsEnabled = enabled; }

    /**
     * 获取并行编译线程数
     *
     * @return 线程数 (0=自动检测)
     */
    public int getParallelCompileThreads() { return parallelCompileThreads; }

    /**
     * 设置并行编译线程数
     *
     * @param threads 线程数 (0=自动检测)
     */
    public void setParallelCompileThreads(int threads) { this.parallelCompileThreads = threads; }

    /**
     * 是否支持热更新
     *
     * @return true 如果支持热更新
     */
    public boolean isHotReloadSupported() { return hotReloadSupported; }

    /**
     * 设置是否支持热更新
     *
     * @param supported 是否支持
     */
    public void setHotReloadSupported(boolean supported) { this.hotReloadSupported = supported; }
}
