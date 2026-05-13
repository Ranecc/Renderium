package com.ranecc.renderium.domain.model.config;

/**
 * TODO [REVIEW] 桩类 - Renderium 配置快照
 * 某一时间点所有渲染配置的不可变快照
 */
public class RenderiumConfigSnapshot {
    private final boolean superResolutionEnabled;
    private final boolean frameGenerationEnabled;
    private final boolean cullingEnabled;
    private final boolean lodEnabled;
    private final boolean postProcessingEnabled;

    public RenderiumConfigSnapshot(
            boolean superResolutionEnabled,
            boolean frameGenerationEnabled,
            boolean cullingEnabled,
            boolean lodEnabled,
            boolean postProcessingEnabled) {
        this.superResolutionEnabled = superResolutionEnabled;
        this.frameGenerationEnabled = frameGenerationEnabled;
        this.cullingEnabled = cullingEnabled;
        this.lodEnabled = lodEnabled;
        this.postProcessingEnabled = postProcessingEnabled;
    }

    /** 是否启用超分辨率 */
    public boolean isSuperResolutionEnabled() { return superResolutionEnabled; }

    /** 是否启用帧生成 */
    public boolean isFrameGenerationEnabled() { return frameGenerationEnabled; }

    /** 是否启用遮挡剔除 */
    public boolean isCullingEnabled() { return cullingEnabled; }

    /** 是否启用LOD */
    public boolean isLodEnabled() { return lodEnabled; }

    /** 是否启用后处理 */
    public boolean isPostProcessingEnabled() { return postProcessingEnabled; }
}
