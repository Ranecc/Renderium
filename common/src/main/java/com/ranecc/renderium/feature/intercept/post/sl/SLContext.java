package com.ranecc.renderium.feature.intercept.post.sl;

/**
 * NVIDIA Streamline SDK Context
 * 封装 NVIDIA Streamline 的初始化状态和上下文
 */
public class SLContext {
    private volatile boolean initialized = false;

    /** 支持的 Super Resolution 特性枚举 */
    public enum Feature {
        DLSS, DLSS_G, REFLEX, XESS, FSR, NIS
    }

    public SLContext() {
    }

    /** 检查是否已成功初始化 */
    public boolean isInitialized() { return initialized; }

    /** 初始化 Streamline 上下文 */
    public void initialize() { this.initialized = true; }

    /** 关闭并释放资源 */
    public void shutdown() { this.initialized = false; }

    /**
     * 检查指定特性是否被当前平台支持
     * @param feature 要检查的特性
     * @return 如果已初始化则返回 true，否则返回 false
     */
    public boolean isFeatureSupported(Feature feature) {
        return initialized;
    }

    /**
     * 检查指定特性是否已启用
     * @param feature 要检查的特性
     * @return 如果已初始化则返回 true，否则返回 false
     */
    public boolean isFeatureEnabled(Feature feature) {
        return initialized;
    }
}
