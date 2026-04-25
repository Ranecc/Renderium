package com.renderium.config;

/**
 * Renderium 配置快照（热路径只读视图）
 *
 * <p>将渲染循环频繁访问的配置项提取为不可变快照，
 * 使用 volatile 引用实现无锁读取，确保：
 * <ul>
 *   <li><b>热路径</b>：渲染线程通过快照读取配置，O(1) 无锁访问</li>
 *   <li><b>冷路径</b>：UI/配置加载修改原始配置，按需更新快照</li>
 *   <li><b>一致性</b>：快照更新是原子性的，避免读到半更新状态</li>
 * </ul>
 *
 * <h2>性能特征</h2>
 * <ul>
 *   <li>单次读取：&lt; 5ns（volatile 读 + 字段访问）</li>
 *   <li>快照更新：~100ns（创建新对象 + volatile 写）</li>
 *   <li>内存开销：~200 bytes/快照（紧凑布局）</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <pre>{@code
 * // 热路径（渲染循环）
 * RenderiumConfigSnapshot snap = RenderiumConfig.getSnapshot();
 * if (snap.superResolutionEnabled) {
 *     // 执行超分辨率...
 * }
 *
 * // 冷路径（UI/配置）
 * config.setSuperResolutionEnabled(true);
 * config.commitSnapshot(); // 通知更新快照
 * }</pre>
 *
 * @author Renderium Team
 * @since 5.3.0
 */
public final class RenderiumConfigSnapshot {

    // ==================== 超分辨率配置 ====================

    /** 是否启用超分辨率 */
    public final boolean superResolutionEnabled;

    /** 超分辨率技术类型 */
    public final int technology; // SuperResolutionAdapter.Technique ordinal

    /** 超分辨率质量等级 */
    public final int quality; // SuperResolutionAdapter.Quality ordinal

    // ==================== 帧生成配置 ====================

    /** 是否启用帧生成 */
    public final boolean frameGenerationEnabled;

    /** 帧生成模式 */
    public final int frameGenMode; // FrameGenMode ordinal

    // ==================== Reflex 配置 ====================

    /** 是否启用 NVIDIA Reflex */
    public final boolean reflexEnabled;

    /** Reflex 模式 */
    public final int reflexMode; // ReflexMode ordinal

    // ==================== 剔除配置 ====================

    /** 是否启用相邻面剔除 */
    public final boolean neighborFaceCullingEnabled;

    /** 是否启用背面剔除 */
    public final boolean backfaceCullingEnabled;

    /** 是否启用视锥体剔除 */
    public final boolean frustumCullingEnabled;

    /** 是否启用遮挡剔除 */
    public final boolean occlusionCullingEnabled;

    // ==================== 批量渲染配置 ====================

    /** 是否启用批量渲染 */
    public final boolean batchingEnabled;

    /** 是否启用实例化渲染 */
    public final boolean instancingEnabled;

    // ==================== 后处理配置 ====================

    /** 后处理效果总开关 */
    public final boolean effectsEnabled;

    /** 锐化强度 (0.0 - 1.0) */
    public final float sharpening;

    // ==================== 动态分辨率 ====================

    /** 是否启用动态分辨率缩放 */
    public final boolean dynamicResolution;

    // ==================== 拦截层配置 ====================

    /** 是否启用 LOD 注入 */
    public final boolean lodInjectionEnabled;

    /** 是否启用剔除注入 */
    public final boolean cullingInjectionEnabled;

    // ==================== Blaze3D 优化配置 (Aggressive 模式) ====================

    /** 是否启用帧图优化 */
    public final boolean frameGraphOptimizationEnabled;

    /** 是否启用 Vulkan 命令缓冲区优化 */
    public final boolean vulkanCommandOptimizationEnabled;

    /** 是否启用内存优化 */
    public final boolean memoryOptimizationEnabled;

    /** 是否启用着色器管线优化 */
    public final boolean shaderPipelineOptimizationEnabled;

    /**
     * 私有构造函数（由 RenderiumConfig.createSnapshot() 调用）
     *
     * @param builder 快照构建器
     */
    private RenderiumConfigSnapshot(Builder builder) {
        this.superResolutionEnabled = builder.superResolutionEnabled;
        this.technology = builder.technology;
        this.quality = builder.quality;
        this.frameGenerationEnabled = builder.frameGenerationEnabled;
        this.frameGenMode = builder.frameGenMode;
        this.reflexEnabled = builder.reflexEnabled;
        this.reflexMode = builder.reflexMode;
        this.neighborFaceCullingEnabled = builder.neighborFaceCullingEnabled;
        this.backfaceCullingEnabled = builder.backfaceCullingEnabled;
        this.frustumCullingEnabled = builder.frustumCullingEnabled;
        this.occlusionCullingEnabled = builder.occlusionCullingEnabled;
        this.batchingEnabled = builder.batchingEnabled;
        this.instancingEnabled = builder.instancingEnabled;
        this.effectsEnabled = builder.effectsEnabled;
        this.sharpening = builder.sharpening;
        this.dynamicResolution = builder.dynamicResolution;
        this.lodInjectionEnabled = builder.lodInjectionEnabled;
        this.cullingInjectionEnabled = builder.cullingInjectionEnabled;
        this.frameGraphOptimizationEnabled = builder.frameGraphOptimizationEnabled;
        this.vulkanCommandOptimizationEnabled = builder.vulkanCommandOptimizationEnabled;
        this.memoryOptimizationEnabled = builder.memoryOptimizationEnabled;
        this.shaderPipelineOptimizationEnabled = builder.shaderPipelineOptimizationEnabled;
    }

    // ==================== 构建器模式 ====================

    /**
     * 快照构建器
     * <p>使用构建器模式避免构造函数参数过多，
     * 同时支持从 RenderiumConfig 高效复制字段。
     */
    public static final class Builder {

        // 默认值与 RenderiumConfig 一致
        private boolean superResolutionEnabled = false;
        private int technology = 0; // DLSS
        private int quality = 1; // BALANCED
        private boolean frameGenerationEnabled = false;
        private int frameGenMode = 0; // FIXED_2X
        private boolean reflexEnabled = false;
        private int reflexMode = 0; // LOW_LATENCY
        private boolean neighborFaceCullingEnabled = true;
        private boolean backfaceCullingEnabled = true;
        private boolean frustumCullingEnabled = true;
        private boolean occlusionCullingEnabled = true;
        private boolean batchingEnabled = true;
        private boolean instancingEnabled = true;
        private boolean effectsEnabled = true;
        private float sharpening = 0.0f;
        private boolean dynamicResolution = false;
        private boolean lodInjectionEnabled = false;
        private boolean cullingInjectionEnabled = false;
        private boolean frameGraphOptimizationEnabled = true;
        private boolean vulkanCommandOptimizationEnabled = true;
        private boolean memoryOptimizationEnabled = true;
        private boolean shaderPipelineOptimizationEnabled = true;

        public Builder superResolutionEnabled(boolean value) { this.superResolutionEnabled = value; return this; }
        public Builder technology(int value) { this.technology = value; return this; }
        public Builder quality(int value) { this.quality = value; return this; }
        public Builder frameGenerationEnabled(boolean value) { this.frameGenerationEnabled = value; return this; }
        public Builder frameGenMode(int value) { this.frameGenMode = value; return this; }
        public Builder reflexEnabled(boolean value) { this.reflexEnabled = value; return this; }
        public Builder reflexMode(int value) { this.reflexMode = value; return this; }
        public Builder neighborFaceCullingEnabled(boolean value) { this.neighborFaceCullingEnabled = value; return this; }
        public Builder backfaceCullingEnabled(boolean value) { this.backfaceCullingEnabled = value; return this; }
        public Builder frustumCullingEnabled(boolean value) { this.frustumCullingEnabled = value; return this; }
        public Builder occlusionCullingEnabled(boolean value) { this.occlusionCullingEnabled = value; return this; }
        public Builder batchingEnabled(boolean value) { this.batchingEnabled = value; return this; }
        public Builder instancingEnabled(boolean value) { this.instancingEnabled = value; return this; }
        public Builder effectsEnabled(boolean value) { this.effectsEnabled = value; return this; }
        public Builder sharpening(float value) { this.sharpening = value; return this; }
        public Builder dynamicResolution(boolean value) { this.dynamicResolution = value; return this; }
        public Builder lodInjectionEnabled(boolean value) { this.lodInjectionEnabled = value; return this; }
        public Builder cullingInjectionEnabled(boolean value) { this.cullingInjectionEnabled = value; return this; }
        public Builder frameGraphOptimizationEnabled(boolean value) { this.frameGraphOptimizationEnabled = value; return this; }
        public Builder vulkanCommandOptimizationEnabled(boolean value) { this.vulkanCommandOptimizationEnabled = value; return this; }
        public Builder memoryOptimizationEnabled(boolean value) { this.memoryOptimizationEnabled = value; return this; }
        public Builder shaderPipelineOptimizationEnabled(boolean value) { this.shaderPipelineOptimizationEnabled = value; return this; }

        /**
         * 从 RenderiumConfig 复制所有热路径字段
         *
         * @param config 配置源
         * @return this（链式调用）
         */
        public Builder fromConfig(RenderiumConfig config) {
            this.superResolutionEnabled = config.isSuperResolutionEnabled();
            this.technology = config.getTechnology().ordinal();
            this.quality = config.getQuality().ordinal();
            this.frameGenerationEnabled = config.isFrameGenerationEnabled();
            this.frameGenMode = config.getFrameGenMode().ordinal();
            this.reflexEnabled = config.isReflexEnabled();
            this.reflexMode = config.getReflexMode().ordinal();
            this.neighborFaceCullingEnabled = config.isNeighborFaceCullingEnabled();
            this.backfaceCullingEnabled = config.isBackfaceCullingEnabled();
            this.frustumCullingEnabled = config.isFrustumCullingEnabled();
            this.occlusionCullingEnabled = config.isOcclusionCullingEnabled();
            this.batchingEnabled = config.isBatchingEnabled();
            this.instancingEnabled = config.isInstancingEnabled();
            this.effectsEnabled = config.isEffectsEnabled();
            this.sharpening = config.getSharpening();
            this.dynamicResolution = config.isDynamicResolution();

            // 拦截层配置（安全访问）
            InterceptionConfig interception = config.getInterceptionConfig();
            if (interception != null) {
                // 通过 PreInterceptorConfig 获取 LOD 和剔除配置
                var preInterceptor = interception.getPreInterceptor();
                if (preInterceptor != null) {
                    LODConfig lod = preInterceptor.getLodInjection();
                    if (lod != null) {
                        this.lodInjectionEnabled = lod.isEnabled();
                    }
                    CullingInjectionConfig culling = preInterceptor.getCullingInjection();
                    if (culling != null) {
                        this.cullingInjectionEnabled = culling.isEnabled();
                    }
                }
            }

            // Blaze3D 优化配置
            this.frameGraphOptimizationEnabled = config.isFrameGraphOptimizationEnabled();
            this.vulkanCommandOptimizationEnabled = config.isVulkanCommandOptimizationEnabled();
            this.memoryOptimizationEnabled = config.isMemoryOptimizationEnabled();
            this.shaderPipelineOptimizationEnabled = config.isShaderPipelineOptimizationEnabled();

            return this;
        }

        /**
         * 构建不可变快照
         *
         * @return 新的配置快照实例
         */
        public RenderiumConfigSnapshot build() {
            return new RenderiumConfigSnapshot(this);
        }
    }
}
