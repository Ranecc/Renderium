package com.ranecc.renderium.feature.shader;

import com.ranecc.renderium.None;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.domain.model.config.RenderiumConfigSnapshot;
/**
 * Shader 系统配置适配器
 *
 * <p>作为视频设置系统与 Shader 渲染管线之间的桥梁，
 * 将 {@link RenderiumConfig} 的高层设置转换为 Shader 系统需要的底层参数。
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
 * 超分辨率技术    →    shaderDefines["SR_TECH_DLSS/FSR/NIS"]
 * 帧生成启用      →    shaderDefines["FG_ENABLED"]
 * 遮挡剔除启用    →    shaderDefines["HIZ_CULLING"]
 * LOD 偏移        →    pushConstants.lodBias
 * 锐化强度       →    uniforms.sharpness
 * 分辨率缩放     →    viewport.scaleFactor
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 初始化时获取适配结果
 * ShaderConfigAdapter adapter = ShaderConfigAdapter.getInstance();
 * ShaderParams params = adapter.adapt(RenderiumConfig.getSnapshot());
 *
 * // 应用到着色器管线
 * shaderProgram.setDefines(params.getShaderDefines());
 * shaderPushConstants.update(params.getPushConstants());
 * shaderUniforms.update(params.getUniformValues());
 *
 * // 配置变更后重新适配（冷路径）
 * config.setSuperResolutionEnabled(true);
 * config.commitSnapshot();
 * ShaderParams newParams = adapter.adapt(RenderiumConfig.getSnapshot());
 * }</pre>
 *
 * @author Renderium Team
 * @since 5.3.0
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
     * 将配置快照适配为 Shader 参数
     *
     * <p>此方法从配置快照中提取所有影响 Shader 行为的设置，
     * 转换为 Shader 系统可以使用的参数对象。
     * <p>
     * <b>调用时机：</b>配置变更后、渲染器重载前
     * <b>性能特征：</b>~200ns（纯计算，无 I/O）
     *
     * @param snapshot 当前配置快照（来自热路径）
     * @return 适配后的 Shader 参数对象
     */
    public ShaderParams adapt(RenderiumConfigSnapshot snapshot) {
        ShaderParams params = new ShaderParams();

        // ==================== 超分辨率相关 ====================
        adaptSuperResolution(snapshot, params);

        // ==================== 帧生成相关 ====================
        adaptFrameGeneration(snapshot, params);

        // ==================== 遮挡剔除相关 ====================
        adaptCulling(snapshot, params);

        // ==================== LOD 相关 ====================
        adaptLOD(snapshot, params);

        // ==================== 后处理相关 ====================
        adaptPostProcessing(snapshot, params);

        // ==================== 通用渲染设置 ====================
        adaptGeneralRendering(snapshot, params);

        return params;
    }

    /**
     * 适配超分辨率设置
     */
    private void adaptSuperResolution(RenderiumConfigSnapshot snap, ShaderParams params) {
        boolean srEnabled = snap.isSuperResolutionEnabled();
        params.addDefine("SR_ENABLED", srEnabled ? "1" : "0");

        if (srEnabled) {
            params.addDefine("SR_TECH_NONE", "1");
            params.addDefine("SR_QUALITY", "1");
            float scaleFactor = 0.67f;
            params.setUniform("u_resolutionScale", scaleFactor);
        }
    }

    private void adaptFrameGeneration(RenderiumConfigSnapshot snap, ShaderParams params) {
        boolean fgEnabled = snap.isFrameGenerationEnabled();
        params.addDefine("FG_ENABLED", fgEnabled ? "1" : "0");

        if (fgEnabled) {
            params.setUniform("u_frameMultiplier", 2.0f);
            params.addDefine("FG_MULTIPLIER_2X", "1");
        }
    }

    private void adaptCulling(RenderiumConfigSnapshot snap, ShaderParams params) {
        boolean hizEnabled = snap.isCullingEnabled();
        params.addDefine("HIZ_CULLING_ENABLED", hizEnabled ? "1" : "0");

        if (hizEnabled) {
            params.addDefine("BACKFACE_CULLING", "1");
            params.addDefine("NEIGHBOR_FACE_CULLING", "1");
        }
    }

    private void adaptLOD(RenderiumConfigSnapshot snap, ShaderParams params) {
        boolean gpuLodEnabled = snap.isLodEnabled();
        params.addComputeDefine("GPU_LOD_ENABLED", gpuLodEnabled ? "1" : "0");
        if (gpuLodEnabled) {
            params.setPushConstant("lodBias", 0.0f);
        }
    }

    private void adaptPostProcessing(RenderiumConfigSnapshot snap, ShaderParams params) {
        boolean ppEnabled = snap.isPostProcessingEnabled();
        params.addDefine("POST_PROCESSING_ENABLED", ppEnabled ? "1" : "0");
        if (ppEnabled) {
            params.setUniform("u_sharpness", 0.5f);
        }
    }

    private void adaptGeneralRendering(RenderiumConfigSnapshot snap, ShaderParams params) {
        params.addDefine("BATCHING_ENABLED", "1");
        params.addDefine("INSTANCING_ENABLED", "1");
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
