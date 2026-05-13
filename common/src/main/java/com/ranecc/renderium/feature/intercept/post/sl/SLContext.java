package com.ranecc.renderium.feature.intercept.post.sl;

/**
 * TODO [REVIEW] 桩类 - NVIDIA Streamline SDK Context
 * 封装 NVIDIA Streamline 的初始化状态和上下文
 */
public class SLContext {
    private volatile boolean initialized = false;

    public SLContext() {
    }

    /** 检查是否已成功初始化 */
    public boolean isInitialized() { return initialized; }

    /** 初始化 Streamline 上下文 */
    public void initialize() { this.initialized = true; }

    /** 关闭并释放资源 */
    public void shutdown() { this.initialized = false; }
}
