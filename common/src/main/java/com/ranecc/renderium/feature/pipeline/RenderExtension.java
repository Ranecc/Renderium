package com.ranecc.renderium.feature.pipeline;

/**
 * 渲染扩展桩类。
 * 待集成完成后再替换为真实实现。
 */
public class RenderExtension {

    public enum Capability {
        ADVANCED_CULLING,
        DEFERRED_SHADING,
        COMPUTE_BASED_LIGHTING
    }

    public boolean isSupported(Capability capability) {
        return false;
    }

    public void initialize() {}
}
