package com.ranecc.renderium.tech.stub.renderbackendproxy;

/**
 * Blaze3D 渲染后端代理桩类。
 * 待 Blaze3D 迁移完成后替换为真实实现。
 */
public class RenderBackendProxy {

    private static final RenderBackendProxy INSTANCE = new RenderBackendProxy();

    private RenderBackendProxy() {}

    public static RenderBackendProxy getInstance() {
        return INSTANCE;
    }

    public boolean isInitialized() {
        return false;
    }
}
