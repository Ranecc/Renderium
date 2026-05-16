package com.ranecc.renderium.domain.model.config;

import java.util.Arrays;
import java.util.List;

public class InterceptionConfig {

    private final PreInterceptorConfig preInterceptor = new PreInterceptorConfig();
    private final PostInterceptorConfig postInterceptor = new PostInterceptorConfig();
    private final ModOutputRedirectConfig modOutputRedirect = new ModOutputRedirectConfig();

    public PreInterceptorConfig getPreInterceptor() { return preInterceptor; }
    public PostInterceptorConfig getPostInterceptor() { return postInterceptor; }
    public ModOutputRedirectConfig getModOutputRedirect() { return modOutputRedirect; }

    public static class PreInterceptorConfig {
        private boolean enabled = false;
        private boolean performanceModDetection = true;
        private final LODConfig lodInjection = new LODConfig();
        private final CullingInjectionConfig cullingInjection = new CullingInjectionConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isPerformanceModDetection() { return performanceModDetection; }
        public void setPerformanceModDetection(boolean v) { this.performanceModDetection = v; }
        public LODConfig getLodInjection() { return lodInjection; }
        public CullingInjectionConfig getCullingInjection() { return cullingInjection; }
    }

    public static class LODConfig {
        private boolean enabled = true;
        private int maxLevels = 4;
        private String transitionMode = "dithering";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxLevels() { return maxLevels; }
        public void setMaxLevels(int v) { this.maxLevels = v; }
        public String getTransitionMode() { return transitionMode; }
        public void setTransitionMode(String v) { this.transitionMode = v; }
    }

    public static class CullingInjectionConfig {
        private boolean enabled = true;
        private boolean frustumCulling = true;
        private boolean occlusionCulling = true;
        private boolean distanceCulling = false;
        private String strategy = "balanced";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isFrustumCulling() { return frustumCulling; }
        public void setFrustumCulling(boolean v) { this.frustumCulling = v; }
        public boolean isOcclusionCulling() { return occlusionCulling; }
        public void setOcclusionCulling(boolean v) { this.occlusionCulling = v; }
        public boolean isDistanceCulling() { return distanceCulling; }
        public void setDistanceCulling(boolean v) { this.distanceCulling = v; }
        public String getStrategy() { return strategy; }
        public void setStrategy(String v) { this.strategy = v; }
    }

    public static class PostInterceptorConfig {
        private boolean enabled = false;
        private final FrameCaptureConfig frameCapture = new FrameCaptureConfig();
        private final SuperResolutionConfig superRes = new SuperResolutionConfig();
        private final FrameGenerationConfig frameGen = new FrameGenerationConfig();
        private final PostProcessingConfig postProcessing = new PostProcessingConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public FrameCaptureConfig getFrameCapture() { return frameCapture; }
        public SuperResolutionConfig getSuperResolution() { return superRes; }
        public FrameGenerationConfig getFrameGeneration() { return frameGen; }
        public PostProcessingConfig getPostProcessing() { return postProcessing; }
    }

    public static class FrameCaptureConfig {
        private boolean async = false;
        private String method = "auto";
        private int msaa = 0;

        public boolean isAsync() { return async; }
        public void setAsync(boolean v) { this.async = v; }
        public String getMethod() { return method; }
        public void setMethod(String v) { this.method = v; }
        public int getMsaa() { return msaa; }
        public void setMsaa(int v) { this.msaa = v; }
    }

    public static class SuperResolutionConfig {
        private boolean enabled = false;
        private String preferredTechnology = "auto";
        private String qualityMode = "quality";
        private float renderScale = 1.0f;
        private float sharpening = 0.0f;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getPreferredTechnology() { return preferredTechnology; }
        public void setPreferredTechnology(String v) { this.preferredTechnology = v; }
        public String getQualityMode() { return qualityMode; }
        public void setQualityMode(String v) { this.qualityMode = v; }
        public float getRenderScale() { return renderScale; }
        public void setRenderScale(float v) { this.renderScale = v; }
        public float getSharpening() { return sharpening; }
        public void setSharpening(float v) { this.sharpening = v; }
    }

    public static class FrameGenerationConfig {
        private boolean enabled = false;
        private String mode = "dlss-fg";
        private int targetFPS = 120;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }
        public int getTargetFPS() { return targetFPS; }
        public void setTargetFPS(int v) { this.targetFPS = v; }
    }

    public static class PostProcessingConfig {
        private final EffectConfig bloom = new EffectConfig();
        private final EffectConfig dof = new EffectConfig();
        private final EffectConfig motionBlur = new EffectConfig();

        public EffectConfig getBloom() { return bloom; }
        public EffectConfig getDof() { return dof; }
        public EffectConfig getMotionBlur() { return motionBlur; }
    }

    public static class EffectConfig {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }

    public static class ModOutputRedirectConfig {
        private boolean enabled = false;
        private final List<String> supportedVersions = Arrays.asList("1.21", "1.21.1");
        private String fallbackMode = "compatibility";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public List<String> getSupportedVersions() { return supportedVersions; }
        public String getFallbackMode() { return fallbackMode; }
    }
}
