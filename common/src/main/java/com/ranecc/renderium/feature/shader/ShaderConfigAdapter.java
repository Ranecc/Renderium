package com.ranecc.renderium.feature.shader;

/**
 * Shader 系统配置适配器
 *
 * <p>作为视频设置系统与 Shader 渲染管线之间的桥梁，
 * 将渲染配置的高层设置转换为 Shader 系统需要的底层参数。
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li><b>单向数据流</b>：配置 → 适配器 → Shader 系统，避免反向耦合</li>
 *   <li><b>冷路径更新</b>：仅在配置变更时重新计算，不影响热路径</li>
 *   <li><b>缓存友好</b>：输出结果可被 Shader 系统缓存</li>
 * </ul>
 *
 * <h2>映射关系</h2>
 * <pre>
 * 视频设置                          Shader 参数
 * ──────────────────────────────────────────────
 * 超分辨率启用    →    shaderDefines["SR_ENABLED"]
 * 帧生成启用      →    shaderDefines["FG_ENABLED"]
 * 遮挡剔除启用    →    shaderDefines["HIZ_CULLING"]
 * LOD 启用        →    shaderDefines["GPU_LOD_ENABLED"]
 * 后处理启用      →    shaderDefines["POST_PROCESSING_ENABLED"]
 * </pre>
 */
public final class ShaderConfigAdapter {

    /** 单例实例 */
    private static volatile ShaderConfigAdapter INSTANCE;

    /** 私有构造器 */
    private ShaderConfigAdapter() {}

    /**
     * 获取全局单例实例
     *
     * @return ShaderConfigAdapter 实例
     */
    public static ShaderConfigAdapter getInstance() {
        if (INSTANCE == null) {
            synchronized (ShaderConfigAdapter.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ShaderConfigAdapter();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 将配置适配为 Shader 参数
     *
     * <p>此方法从配置中提取所有影响 Shader 行为的设置，
     * 转换为 Shader 系统可以使用的参数对象。
     *
     * <b>调用时机：</b>配置变更后、渲染器重载前
     *
     * @param superResolutionEnabled 是否启用超分辨率
     * @param frameGenerationEnabled 是否启用帧生成
     * @param cullingEnabled 是否启用遮挡剔除
     * @param lodEnabled 是否启用 LOD
     * @param postProcessingEnabled 是否启用后处理
     * @return 适配后的 Shader 参数对象
     */
    public ShaderParams adapt(boolean superResolutionEnabled,
                              boolean frameGenerationEnabled,
                              boolean cullingEnabled,
                              boolean lodEnabled,
                              boolean postProcessingEnabled) {
        ShaderParams params = new ShaderParams();

        // ==================== 超分辨率相关 ====================
        adaptSuperResolution(superResolutionEnabled, params);

        // ==================== 帧生成相关 ====================
        adaptFrameGeneration(frameGenerationEnabled, params);

        // ==================== 遮挡剔除相关 ====================
        adaptCulling(cullingEnabled, params);

        // ==================== LOD 相关 ====================
        adaptLOD(lodEnabled, params);

        // ==================== 后处理相关 ====================
        adaptPostProcessing(postProcessingEnabled, params);

        // ==================== 通用渲染设置 ====================
        params.addDefine("BATCHING_ENABLED", "1");
        params.addDefine("INSTANCING_ENABLED", "1");

        return params;
    }

    private void adaptSuperResolution(boolean srEnabled, ShaderParams params) {
        params.addDefine("SR_ENABLED", srEnabled ? "1" : "0");
        if (srEnabled) {
            params.addDefine("SR_TECH_NONE", "1");
            params.addDefine("SR_QUALITY", "1");
            params.setUniform("u_resolutionScale", 0.67f);
        }
    }

    private void adaptFrameGeneration(boolean fgEnabled, ShaderParams params) {
        params.addDefine("FG_ENABLED", fgEnabled ? "1" : "0");
        if (fgEnabled) {
            params.setUniform("u_frameMultiplier", 2.0f);
            params.addDefine("FG_MULTIPLIER_2X", "1");
        }
    }

    private void adaptCulling(boolean hizEnabled, ShaderParams params) {
        params.addDefine("HIZ_CULLING_ENABLED", hizEnabled ? "1" : "0");
        if (hizEnabled) {
            params.addDefine("BACKFACE_CULLING", "1");
            params.addDefine("NEIGHBOR_FACE_CULLING", "1");
        }
    }

    private void adaptLOD(boolean gpuLodEnabled, ShaderParams params) {
        params.addComputeDefine("GPU_LOD_ENABLED", gpuLodEnabled ? "1" : "0");
        if (gpuLodEnabled) {
            params.setPushConstant("lodBias", 0.0f);
        }
    }

    private void adaptPostProcessing(boolean ppEnabled, ShaderParams params) {
        params.addDefine("POST_PROCESSING_ENABLED", ppEnabled ? "1" : "0");
        if (ppEnabled) {
            params.setUniform("u_sharpness", 0.5f);
        }
    }

    /**
     * 根据质量等级获取分辨率缩放因子
     *
     * @param quality 质量等级 ordinal
     * @return 缩放因子（0.0-1.0）
     */
    private float getScaleFactorFromQuality(int quality) {
        return switch (quality) {
            case 0 -> 0.50f;  // Performance: 50%
            case 1 -> 0.67f;  // Balanced: 67%
            case 2 -> 0.77f;  // Quality: 77%
            case 3 -> 0.89f;  // Ultra: 89%
            default -> 0.67f; // 默认 Balanced
        };
    }

    // ==================== 内部参数容器类 ====================

    /**
     * Shader 参数容器
     *
     * <p>存储适配后的所有 Shader 相关参数，
     * 包括宏定义、Uniform 值和 Push Constants。
     */
    public static final class ShaderParams {

        /** 着色器宏定义（顶点/片段着色器） */
        private final java.util.Map<String, String> shaderDefines = new java.util.HashMap<>();

        /** 计算着色器宏定义 */
        private final java.util.Map<String, String> computeDefines = new java.util.HashMap<>();

        /** Uniform 变量值 */
        private final java.util.Map<String, Float> uniformValues = new java.util.HashMap<>();

        /** Push Constant 值 */
        private final java.util.Map<String, Float> pushConstants = new java.util.HashMap<>();

        /**
         * 添加顶点/片段着色器宏定义
         */
        public void addDefine(String key, String value) {
            shaderDefines.put(key, value);
        }

        /**
         * 添加计算着色器宏定义
         */
        public void addComputeDefine(String key, String value) {
            computeDefines.put(key, value);
        }

        /**
         * 设置 Uniform 变量值
         */
        public void setUniform(String name, float value) {
            uniformValues.put(name, value);
        }

        /**
         * 设置 Push Constant 值
         */
        public void setPushConstant(String name, float value) {
            pushConstants.put(name, value);
        }

        // Getter 方法

        /** 获取所有着色器宏定义（不可修改） */
        public java.util.Map<String, String> getShaderDefines() {
            return java.util.Collections.unmodifiableMap(shaderDefines);
        }

        /** 获取所有计算着色器宏定义（不可修改） */
        public java.util.Map<String, String> getComputeDefines() {
            return java.util.Collections.unmodifiableMap(computeDefines);
        }

        /** 获取所有 Uniform 值（不可修改） */
        public java.util.Map<String, Float> getUniformValues() {
            return java.util.Collections.unmodifiableMap(uniformValues);
        }

        /** 获取所有 Push Constant 值（不可修改） */
        public java.util.Map<String, Float> getPushConstants() {
            return java.util.Collections.unmodifiableMap(pushConstants);
        }

        /**
         * 检查是否有任何超分辨率相关的定义
         */
        public boolean hasSuperResolution() {
            return "1".equals(shaderDefines.get("SR_ENABLED"));
        }

        /**
         * 检查是否有帧生成相关的定义
         */
        public boolean hasFrameGeneration() {
            return "1".equals(shaderDefines.get("FG_ENABLED"));
        }

        /**
         * 调试输出（开发用）
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ShaderParams{\n");
            sb.append("  defines=").append(shaderDefines).append('\n');
            sb.append("  computeDefines=").append(computeDefines).append('\n');
            sb.append("  uniforms=").append(uniformValues).append('\n');
            sb.append("  pushConstants=").append(pushConstants).append('\n');
            sb.append('}');
            return sb.toString();
        }
    }
}
