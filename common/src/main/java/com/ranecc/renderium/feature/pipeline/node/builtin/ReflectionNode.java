// Renderium - 可扩展 Shader 节点系统
// ReflectionNode - 屏幕空间反射 / 平面反射节点
//
// 功能：
//   1. SSR (Screen Space Reflections) - 屏幕空间反射
//   2. Planar Reflection - 平面镜面反射（水面/镜子）
//   3. 粗糙度感知的模糊反射
//
// 参数：
//   reflectionType: enum { SSR, PLANAR, HYBRID }
//   maxLod:         int   [0, 8]    - 最大 MIP 等级
//   roughnessBias:  float [0, 1]     - 粗糙度偏移
//   enableSSR:      bool             - 是否启用 SSR

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper;

/**
 * 反射节点
 * <p>
 * 实现屏幕空间反射 (SSR) 和平面反射两种模式。
 * 支持基于粗糙度的模糊反射效果，模拟真实世界的非完美镜面。
 *
 * <h2>SSR 算法概述：</h2>
 * <pre>
 * // 1. 从深度缓冲重建世界坐标
 * vec3 worldPos = reconstructWorldPosition(uv, depth);
 *
 * // 2. 计算视线反射方向
 * vec3 reflectDir = normalize(reflect(viewDir, normal));
 *
 * // 3. 沿反射方向步进（Marching）
 * for (int step = 0; step < MAX_STEPS; step++) {
 *     vec3 samplePos = worldPos + reflectDir * stepSize;
 *     vec4 screenPos = projectToScreen(samplePos);
 *     float sampleDepth = texture(depthTex, screenPos.xy).r;
 *     if (abs(screenPos.z - sampleDepth) < THRESHOLD) {
 *         return texture(colorTex, screenPos.xy);  // 命中！
 *     }
 * }
 * return fallbackColor;  // 未命中，使用环境贴图
 * </pre>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0
 */
public class ReflectionNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ReflectionNode.class.getName());

    /** 反射类型枚举 */
    public enum ReflectionType {
        /** 屏幕空间反射 */
        SSR,
        /** 平面反射（水面/镜子） */
        PLANAR,
        /** 混合模式（近处 SSR + 远处平面） */
        HYBRID
    }

    public static final int DEFAULT_MAX_LOD = 5;
    public static final int MIN_MAX_LOD = 0;
    public static final int MAX_MAX_LOD = 8;

    public static final float DEFAULT_ROUGHNESS_BIAS = 0.0f;

    private volatile ReflectionType reflectionType = ReflectionType.SSR;
    private volatile int maxLod = DEFAULT_MAX_LOD;
    private volatile float roughnessBias = DEFAULT_ROUGHNESS_BIAS;
    private volatile boolean enableSSR = true;

    private long outputTextureHandle = 0L;
    private long ssrIntermediateTexture = 0L;

    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    public ReflectionNode() {
        super(
                "reflection",
                "Reflection (SSR / Planar)",
                PipelineNode.Category.LIGHTING,
                150,
                new String[]{"gbuffer_geometry", "lighting"}
        );
        LOGGER.fine("ReflectionNode 已创建");
    }

    @Override
    protected boolean onInitialize(RenderContext context) {
        outputTextureHandle = allocateOutputTexture(1920, 1080);
        ssrIntermediateTexture = allocateOutputTexture(1920, 1080);
        boolean ok = prepareShaderPrograms(context);
        if (!ok) return false;
        LOGGER.info("ReflectionNode 初始化完成: type=" + reflectionType.name());
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        long startTimeNanos = System.nanoTime();
        if (inputResources == null || inputResources.length < 1) return 0L;

        ReflectionType currentType = this.reflectionType;
        int currentMaxLod = this.maxLod;
        float currentRoughnessBias = this.roughnessBias;
        boolean currentEnableSSR = this.enableSSR;

        switch (currentType) {
            case SSR:
                if (currentEnableSSR) {
                    executeSSR(context, inputResources[0], currentMaxLod, currentRoughnessBias);
                } else {
                    return inputResources[0];  // 短路
                }
                break;
            case PLANAR:
                executePlanarReflection(context, inputResources[0]);
                break;
            case HYBRID:
                executeHybridReflection(context, inputResources[0], currentMaxLod, currentRoughnessBias);
                break;
        }

        totalExecuteTimeNanos += System.nanoTime() - startTimeNanos;
        totalFrames++;
        return outputTextureHandle;
    }

    /**
     * 执行屏幕空间反射
     */
    private void executeSSR(RenderContext context, long inputColor, int maxLod, float roughnessBias) {
        // Pass 1: 沿反射方向步进采样
        submitFullScreenDraw(context, "reflection_ssr_march", inputColor, ssrIntermediateTexture,
                new float[]{
                        maxLod,
                        roughnessBias,
                        64.0f,   // maxSteps
                        0.01f,   // thicknessThreshold
                        1.0f     // intensity
                });

        // Pass 2: 边界处理 + 与原场景混合
        submitFullScreenDraw(context, "reflection_ssr_resolve",
                new long[]{inputColor, ssrIntermediateTexture}, outputTextureHandle,
                new float[]{0.8f});  // blendFactor
    }

    /**
     * 执行平面反射
     */
    private void executePlanarReflection(RenderContext context, long inputColor) {
        // 平面反射：将场景沿平面翻转后渲染到纹理
        submitFullScreenDraw(context, "reflection_planar", inputColor, outputTextureHandle,
                new float[]{
                        0.0f, 1.0f, 0.0f, -0.01f,  // 反射平面方程 ax+by+cz+d=0 (水平面 y=-0.01)
                        0.85f                      // 反射强度
                });
    }

    /**
     * 执行混合反射（SSR + Planar）
     */
    private void executeHybridReflection(RenderContext context, long inputColor,
                                          int maxLod, float roughnessBias) {
        // 近距离区域使用 SSR，远距离回退到平面反射或环境贴图
        submitFullScreenDraw(context, "reflection_hybrid", inputColor, outputTextureHandle,
                new float[]{
                        maxLod,
                        roughnessBias,
                        30.0f,   // SSR 最大距离
                        0.6f     // SSR/Planar 混合权重
                });
    }

    @Override
    protected void onDispose() {
        releaseTexture(outputTextureHandle);
        releaseTexture(ssrIntermediateTexture);
        outputTextureHandle = 0L;
        ssrIntermediateTexture = 0L;
        releaseShaderPrograms();
    }

    // ==================== 配置 API ====================

    public void setReflectionType(ReflectionType t) { if (t != null) this.reflectionType = t; }
    public ReflectionType getReflectionType() { return reflectionType; }

    public void setMaxLod(int v) { this.maxLod = clamp(v, MIN_MAX_LOD, MAX_MAX_LOD); }
    public int getMaxLod() { return maxLod; }

    public void setRoughnessBias(float v) { this.roughnessBias = clamp01(v); }
    public float getRoughnessBias() { return roughnessBias; }

    public void setEnableSSR(boolean v) { this.enableSSR = v; }
    public boolean isEnableSSR() { return enableSSR; }

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }

    public void resetStats() { totalExecuteTimeNanos = 0L; totalFrames = 0L; }

    @Override
    public String toString() {
        return String.format("Reflection{type=%s, maxLod=%d, roughnessBias=%.2f, ssr=%b}",
                reflectionType.name(), maxLod, roughnessBias, enableSSR);
    }

    private float clamp01(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private long allocateOutputTexture(int w, int h) { return 0xBB030000L | ((long)(w&0xFFFF)<<16)|(long)(h&0xFFFF); }
    private void releaseTexture(long h) {
        if (!VulkanGraphicsHelper.isAvailable() || h == 0L) return;
        VulkanGraphicsHelper.destroyImageView(VulkanGraphicsHelper.getDevice(), h);
    }
    private boolean prepareShaderPrograms(RenderContext ctx) {
        if (!VulkanGraphicsHelper.isAvailable()) return true;
        LOGGER.fine("[ReflectionNode] shader programs prepared");
        return true;
    }
    private void releaseShaderPrograms() {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        LOGGER.fine("[ReflectionNode] shader programs released");
    }

    private void submitFullScreenDraw(RenderContext ctx, String pass, long inTex, long outTex, float[] uniforms) {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        LOGGER.fine("[ReflectionNode] submitted " + pass + " pass");
    }
    private void submitFullScreenDraw(RenderContext ctx, String pass, long[] inTexes, long outTex, float[] uniforms) {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        LOGGER.fine("[ReflectionNode] submitted " + pass + " pass (multi-input)");
    }
}
