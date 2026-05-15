// Renderium - 可扩展 Shader 节点系统
// ShadowStyleNode - 阴影风格控制节点
//
// 功能：
//   1. 真实主义阴影（物理正确）
//   2. 风格化阴影（卡通/动漫风格）
//   3. Toon / Cel-Shading 轮廓阴影
//   4. 可自定义阴影颜色
//
// 参数：
//   style:         enum { REALISTIC, STYLIZED, TOON }
//   shadowColor:   rgb [0,1]^3    - 阴影色调
//   edgeSoftness:  float [0, 1]     - 边缘柔化程度

package com.ranecc.renderium.feature.pipeline.node.builtin;

import com.ranecc.renderium.None;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import java.util.logging.Logger;

/**
 * 阴影风格控制节点
 * <p>
 * 在标准阴影计算之后，对阴影效果进行艺术化处理。
 * 支持真实、风格化和卡通三种模式，
 * 允许自定义阴影颜色和边缘柔和度。
 *
 * <h2>风格算法：</h2>
 *
 * <h3>REALISTIC 模式：</h3>
 * <pre>
 * // 标准物理阴影，保持原始深度信息
 * float shadow = texture(shadowMap, uv).r;  // [0, 1]
 * color *= mix(shadowColor, vec3(1.0), shadow);
 * </pre>
 *
 * <h3>STYLIZED 模式：</h3>
 * <pre>
 * // 带颜色调制的软阴影
 * float shadow = smoothstep(0.2, 0.8, rawShadow);  // 软边缘
 * vec3 tintedShadow = shadowColor * (1.0 - shadow);
 * color = mix(tintedShadow, color, shadow);
 * </pre>
 *
 * <h3>TOON 模式：</h3>
 * <pre>
 * // 二值化硬边阴影（Cel-Shading）
 * float toonShadow = step(0.5, rawShadow);  // 硬阈值
 * color = mix(shadowColor * 0.5, color, toonShadow);
 * </pre>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0
 */
public class ShadowStyleNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ShadowStyleNode.class.getName());

    /** 阴影风格枚举 */
    public enum ShadowStyle {
        /** 真实主义（物理正确） */
        REALISTIC,
        /** 风格化（带颜色调制） */
        STYLIZED,
        /** 卡通/动漫（Cel-Shading 二值化） */
        TOON
    }

    public static final float DEFAULT_EDGE_SOFTNESS = 0.5f;

    private volatile ShadowStyle style = ShadowStyle.REALISTIC;
    private volatile float[] shadowColor = {0.05f, 0.05f, 0.08f};  // 深蓝灰色
    private volatile float edgeSoftness = DEFAULT_EDGE_SOFTNESS;

    private long outputTextureHandle = 0L;

    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    public ShadowStyleNode() {
        super(
                "shadow_style",
                "Shadow Style (Realistic/Stylized/Toon)",
                PipelineNode.Category.PRE_RENDER,
                55,
                new String[]{"shadow_map"}
        );
        LOGGER.fine("ShadowStyleNode 已创建");
    }

    @Override
    protected boolean onInitialize(RenderContext context) {
        outputTextureHandle = allocateOutputTexture(1920, 1080);

        boolean ok = prepareShaderPrograms(context);
        if (!ok) return false;

        LOGGER.info(String.format("ShadowStyleNode 初始化完成: style=%s", style.name()));
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        long startTimeNanos = System.nanoTime();
        if (inputResources == null || inputResources.length == 0) return 0L;

        ShadowStyle currentStyle = this.style;
        float currentEdgeSoftness = this.edgeSoftness;

        // 安全拷贝颜色数组
        float[] currentColor;
        synchronized (this) {
            currentColor = this.shadowColor.clone();
        }

        int styleFlag;
        switch (currentStyle) {
            case REALISTIC: styleFlag = 0; break;
            case STYLIZED:  styleFlag = 1; break;
            case TOON:      styleFlag = 2; break;
            default:        styleFlag = 0;
        }

        submitFullScreenDraw(context, "shadow_style", inputResources[0], outputTextureHandle,
                new float[]{
                        styleFlag,
                        currentEdgeSoftness,
                        currentColor[0], currentColor[1], currentColor[2]
                });

        totalExecuteTimeNanos += System.nanoTime() - startTimeNanos;
        totalFrames++;
        return outputTextureHandle;
    }

    @Override
    protected void onDispose() {
        releaseTexture(outputTextureHandle);
        outputTextureHandle = 0L;
        releaseShaderPrograms();
    }

    // ==================== 配置 API ====================

    public void setStyle(ShadowStyle s) { if (s != null) this.style = s; }
    public ShadowStyle getStyle() { return style; }

    /**
     * 设置阴影颜色
     *
     * @param color float[] - RGB 三分量，范围 [0, 1]
     */
    public void setShadowColor(float[] color) {
        if (color == null || color.length != 3) return;
        synchronized (this) {
            this.shadowColor = new float[]{
                    clamp01(color[0]), clamp01(color[1]), clamp01(color[2])
            };
        }
    }

    /**
     * 获取阴影颜色副本
     *
     * @return float[] - RGB 数组副本
     */
    public float[] getShadowColor() {
        synchronized (this) {
            return this.shadowColor.clone();
        }
    }

    public void setEdgeSoftness(float v) { this.edgeSoftness = clamp01(v); }
    public float getEdgeSoftness() { return edgeSoftness; }

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }
    public void resetStats() { totalExecuteTimeNanos = 0L; totalFrames = 0L; }

    @Override
    public String toString() {
        return String.format("ShadowStyle{style=%s, color=[%.2f,%.2f,%.2f], soft=%.2f}",
                style.name(), shadowColor[0], shadowColor[1], shadowColor[2], edgeSoftness);
    }

    private float clamp01(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }
    private long allocateOutputTexture(int w, int h) { return 0xBB060000L | ((long)(w&0xFFFF)<<16)|(long)(h&0xFFFF); }
    private void releaseTexture(long h) { /* TODO */ }
    private boolean prepareShaderPrograms(RenderContext ctx) { /* TODO */ return true; }
    private void releaseShaderPrograms() { /* TODO */ }
    private void submitFullScreenDraw(RenderContext ctx, String pass, long inTex, long outTex, float[] uniforms) { /* TODO */ }
}
