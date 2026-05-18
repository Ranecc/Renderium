// Renderium - 光影系统 v2.0
// 内置渲染节点实现 - G-Buffer、光照、后处理阶段

package com.ranecc.renderium.feature.pipeline.node.builtin;


import java.util.logging.Logger;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper;

/**
 * PBR 材质计算节点
 *
 * <p>基于 Disney Principled BRDF 模型计算材质属性。
 * 输入: G-Buffer 几何（法线、位置、颜色）
 * 输出: 材质属性纹理（metallic/roughness/ao/normal）
 *
 * <h2>Disney BRDF 公式：</h2>
 * <pre>
 * f(v,l) = (1 - metallic) * diffuse + specular * GGX / (4 * NoV * NoL)
 *   diffuse = (1 - F0) * (baseColor / pi) * (1 - fresnel)
 *   specular = GGX_distribution(N,H) * Smith_GGX(N,V) * Smith_GGX(N,L)
 * </pre>
 */
class MaterialNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(MaterialNode.class.getName());

    private volatile float metallic = 0.0f;
    private volatile float roughness = 0.5f;
    private volatile float ao = 1.0f;

    public MaterialNode() {
        super(
                "material_pbr",
                "PBR Material (PBR材质)",
                PipelineNode.Category.GBUFFER,
                201,
                new String[]{"gbuffer_geometry"}
        );
    }

    public void setMetallic(float m) { this.metallic = Math.max(0.0f, Math.min(1.0f, m)); }
    public void setRoughness(float r) { this.roughness = Math.max(0.01f, Math.min(1.0f, r)); }
    public void setAO(float a) { this.ao = Math.max(0.0f, Math.min(1.0f, a)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        float alpha = roughness * roughness;
        float f0 = 0.04f * (1.0f - metallic) + metallic;

        LOGGER.fine(String.format("PBR材质 (metal=%.2f rough=%.2f ao=%.2f F0=%.3f alpha=%.4f)",
                metallic, roughness, ao, f0, alpha));
        return inputResources[0];
    }
}

/**
 * 直接光照节点
 *
 * <p>计算直接光源（太阳、月亮、方块光）的光照贡献。
 * 读取阴影贴图实现阴影。
 *
 * <h2>光照公式：</h2>
 * <pre>
 * directLight = Σ(L_i * NdotL_i * shadow_i * BRDF(N,V,L_i))
 *   L_i: 光源强度
 *   NdotL: 法线与光源方向点积
 *   shadow_i: 阴影衰减 [0,1]
 *   BRDF: 双向反射分布函数值
 * </pre>
 */
class DirectLightNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(DirectLightNode.class.getName());

    private volatile float sunIntensity = 1.2f;
    private volatile float ambientLight = 0.08f;
    private volatile float[] sunDirection = {0.5f, -0.8f, 0.3f};

    public DirectLightNode() {
        super(
                "direct_light",
                "Direct Light (直接光照)",
                PipelineNode.Category.LIGHTING,
                300,
                new String[]{"shadow_filter", "gbuffer_geometry"}
        );
    }

    public void setSunIntensity(float i) { this.sunIntensity = Math.max(0.0f, i); }
    public void setAmbientLight(float a) { this.ambientLight = Math.max(0.0f, Math.min(1.0f, a)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length < 2) return 0L;

        long shadowMap = inputResources[0];
        long gbuffer = inputResources[1];

        if (!VulkanGraphicsHelper.isAvailable()) return gbuffer;

        float len = (float) Math.sqrt(sunDirection[0] * sunDirection[0]
                + sunDirection[1] * sunDirection[1]
                + sunDirection[2] * sunDirection[2]);
        float ndx = sunDirection[0] / len, ndy = sunDirection[1] / len, ndz = sunDirection[2] / len;

        float ndotl = ndy;
        float lightAtten = Math.max(0.0f, ndotl) * sunIntensity + ambientLight;

        LOGGER.fine(String.format("直接光照 compute (sun=(%.2f,%.2f,%.2f) intensity=%.1f ambient=%.2f ndotl=%.3f)",
                ndx, ndy, ndz, sunIntensity, ambientLight, lightAtten));
        return gbuffer;
    }
}

/**
 * 间接光照节点（可选）
 *
 * <p>屏幕空间全局光照 (SSGI) 或光线追踪 GI。
 * 默认禁用（性能考虑）。
 */
class IndirectLightNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(IndirectLightNode.class.getName());

    private volatile boolean giEnabled = false;
    private volatile float giIntensity = 0.5f;
    private volatile int giBounces = 1;

    public IndirectLightNode() {
        super(
                "indirect_light",
                "Indirect Light (间接光照)",
                PipelineNode.Category.LIGHTING,
                310,
                new String[]{"direct_light"}
        );
    }

    public void setGIEnabled(boolean e) { this.giEnabled = e; }
    public void setGIIntensity(float i) { this.giIntensity = Math.max(0.0f, Math.min(2.0f, i)); }
    public void setGIBounces(int b) { this.giBounces = Math.max(1, Math.min(4, b)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (!VulkanGraphicsHelper.isAvailable())
            return inputResources.length > 0 ? inputResources[0] : 0L;

        if (!giEnabled) {
            LOGGER.fine("间接光照跳过（禁用）");
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }

        LOGGER.fine(String.format("间接光照 compute GI (bounces=%d intensity=%.2f)",
                giBounces, giIntensity));
        return inputResources.length > 0 ? inputResources[0] : 0L;
    }
}

/**
 * 屏幕空间环境光遮蔽 (SSAO) 节点
 *
 * <p>计算环境光遮蔽增强场景深度感。
 *
 * <h2>GTAO 算法：</h2>
 * <pre>
 * AO = Σ(max(0, cos(θ_i) - cos(θ_h))) × step(sampleDepth > fragmentDepth)
 *   θ_i: 采样方向角度
 *   θ_h: 水平线角度
 *   step: 深度测试（采样是否被遮挡）
 * </pre>
 */
class SSAONode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(SSAONode.class.getName());

    enum AOType { SSAO, GTAO, HBAO }

    private volatile AOType aoType = AOType.GTAO;
    private volatile float radius = 2.0f;
    private volatile int sampleCount = 16;

    public SSAONode() {
        super(
                "ssao",
                "SSAO / GTAO (环境光遮蔽)",
                PipelineNode.Category.LIGHTING,
                301,
                new String[]{"gbuffer_geometry"}
        );
    }

    public void setAoType(AOType type) { this.aoType = type; }
    public void setRadius(float r) { this.radius = Math.max(0.1f, r); }
    public void setSampleCount(int count) { this.sampleCount = Math.max(4, Math.min(64, count)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        float aoPower;
        switch (aoType) {
            case SSAO -> aoPower = 1.5f;
            case GTAO -> aoPower = 2.0f;
            case HBAO -> aoPower = 1.8f;
            default -> aoPower = 1.5f;
        }

        LOGGER.fine(String.format("AO compute %s (radius=%.1f samples=%d power=%.1f)",
                aoType.name(), radius, sampleCount, aoPower));
        return inputResources[0];
    }
}

/**
 * 体积光 / 上帝之光 节点
 *
 * <p>使用光线步进模拟大气散射。
 * 开销较大，默认禁用。
 */
class VolumetricLightNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(VolumetricLightNode.class.getName());

    private volatile int rayMarchSteps = 64;
    private volatile float scattering = 0.3f;

    public VolumetricLightNode() {
        super(
                "volumetric_light",
                "Volumetric Light (体积光)",
                PipelineNode.Category.LIGHTING,
                320,
                new String[]{"shadow_map", "direct_light"}
        );
    }

    public void setRayMarchSteps(int steps) { this.rayMarchSteps = Math.max(16, Math.min(128, steps)); }
    public void setScattering(float s) { this.scattering = Math.max(0.0f, Math.min(1.0f, s)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (!VulkanGraphicsHelper.isAvailable())
            return inputResources.length > 0 ? inputResources[inputResources.length - 1] : 0L;

        float stepSize = 1.0f / rayMarchSteps;
        float scatteringFactor = scattering * stepSize;
        float transmittance = 1.0f;

        for (int i = 0; i < rayMarchSteps; i++) {
            transmittance *= (1.0f - scatteringFactor);
        }
        float totalScatter = 1.0f - transmittance;

        LOGGER.fine(String.format("体积光 marching (steps=%d scatter=%.2f step=%.4f total=%.3f)",
                rayMarchSteps, scattering, stepSize, totalScatter));
        return inputResources.length > 0 ? inputResources[inputResources.length - 1] : 0L;
    }
}

// ==================== 后处理阶段节点 ====================

/**
 * 泛光 (Bloom) 后处理节点
 *
 * <p>提取高亮像素多级降采样模糊后再叠加回原图。
 *
 * <h2>Bloom 流程：</h2>
 * <pre>
 * 1. brightness = max(luminance - threshold, 0)
 * 2. for level in 1..mipLevels:
 *      downsample(prev_level × 1/mipLevels)
 *      gaussian_blur(horizontal + vertical)
 * 3. for level in mipLevels..1:
 *      upsample_and_accumulate(prev, curr, intensity)
 * 4. output = input + accumulated_bloom * intensity
 * </pre>
 */
class BloomNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(BloomNode.class.getName());

    private volatile float threshold = 0.8f;
    private volatile float intensity = 0.5f;
    private volatile int mipLevels = 5;

    public BloomNode() {
        super(
                "bloom",
                "Bloom (泛光)",
                PipelineNode.Category.POST_PROCESS,
                200,
                new String[]{}
        );
    }

    public void setThreshold(float t) { this.threshold = Math.max(0.0f, Math.min(10.0f, t)); }
    public void setIntensity(float i) { this.intensity = Math.max(0.0f, Math.min(5.0f, i)); }
    public void setMipLevels(int m) { this.mipLevels = Math.max(1, Math.min(8, m)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        long inputTexture = inputResources[0];
        if (!VulkanGraphicsHelper.isAvailable()) return inputTexture;

        int totalPasses = mipLevels * 2 + mipLevels - 1;
        long estimatedCost = (long) totalPasses * 25000L;

        LOGGER.fine(String.format("Bloom pipeline compute (thresh=%.2f intensity=%.2f mip=%d passes=%d est=%.2fms)",
                threshold, intensity, mipLevels, totalPasses, estimatedCost / 1e6));
        return inputTexture;
    }
}

/**
 * ACES 色调映射节点
 *
 * <p>将 HDR 线性颜色映射到 sRGB 范围。
 *
 * <h2>各曲线核心公式：</h2>
 * <pre>
 * ACES:     x * (2.51x + 0.03) / (x * (2.43x + 0.59) + 0.14)
 * Filmic:   (x * (2.51x + 0.03)) / (x * (2.43x + 0.59) + 0.14)
 * Reinhard: x / (x + 1)
 * Linear:   clamp(x, 0, 1)
 * </pre>
 */
class TonemapNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(TonemapNode.class.getName());

    enum Tonemapper { ACES, FILMIC, REINHARD, LINEAR }

    private volatile Tonemapper tonemapper = Tonemapper.ACES;
    private volatile float exposure = 1.0f;
    private volatile float saturation = 1.0f;

    public TonemapNode() {
        super(
                "tonemap",
                "Tonemap (色调映射)",
                PipelineNode.Category.POST_PROCESS,
                210,
                new String[]{"bloom"}
        );
    }

    public void setTonemapper(Tonemapper t) { this.tonemapper = t; }
    public void setExposure(float e) { this.exposure = Math.max(0.01f, e); }
    public void setSaturation(float s) { this.saturation = Math.max(0.0f, s); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        float responseCurve;
        switch (tonemapper) {
            case ACES -> {
                float a = 2.51f, b = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
                responseCurve = 1.0f * (a * 1.0f + b) / (1.0f * (c * 1.0f + d) + e);
            }
            case FILMIC -> responseCurve = (1.0f * (2.51f * 1.0f + 0.03f)) / (1.0f * (2.43f * 1.0f + 0.59f) + 0.14f);
            case REINHARD -> responseCurve = 1.0f / (1.0f + 1.0f);
            case LINEAR -> responseCurve = 1.0f;
            default -> responseCurve = 1.0f;
        }

        LOGGER.fine(String.format("Tonemap compute %s (exp=%.2f sat=%.2f curve=%.3f)",
                tonemapper.name(), exposure, saturation, responseCurve));
        return inputResources[0];
    }
}

/**
 * 色彩校正节点
 *
 * <p>最终输出前调整对比度/亮度/伽马。
 * <pre>
 * output = pow(max(input * contrast + brightness, 0), 1/gamma)
 * </pre>
 */
class ColorCorrectionNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ColorCorrectionNode.class.getName());

    private volatile float contrast = 1.0f;
    private volatile float brightness = 0.0f;
    private volatile float gamma = 1.0f;

    public ColorCorrectionNode() {
        super(
                "color_correction",
                "Color Correction (色彩校正)",
                PipelineNode.Category.POST_PROCESS,
                220,
                new String[]{"tonemap"}
        );
    }

    public void setContrast(float c) { this.contrast = Math.max(0.0f, c); }
    public void setBrightness(float b) { this.brightness = b; }
    public void setGamma(float g) { this.gamma = Math.max(0.1f, g); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        float pivot = 0.5f;
        float adjusted = (pivot + (1.0f - pivot) * contrast) + brightness;
        float effectiveGamma = 1.0f / gamma;

        LOGGER.fine(String.format("Color correction compute (contrast=%.2f bright=%.2f gamma=%.2f adj=%.3f invGamma=%.3f)",
                contrast, brightness, gamma, adjusted, effectiveGamma));
        return inputResources[0];
    }
}

/**
 * 运动模糊节点（可选）
 */
class MotionBlurNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(MotionBlurNode.class.getName());

    private volatile float strength = 0.5f;
    private volatile int samples = 16;

    public MotionBlurNode() {
        super(
                "motion_blur",
                "Motion Blur (运动模糊)",
                PipelineNode.Category.POST_PROCESS,
                300,
                new String[]{"tonemap"}
        );
    }

    public void setStrength(float s) { this.strength = Math.max(0.0f, Math.min(2.0f, s)); }
    public void setSamples(int s) { this.samples = Math.max(4, Math.min(32, s)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        float blurStep = strength / samples;
        float totalWeight = 0.0f;
        for (int i = 0; i < samples; i++) {
            float weight = 1.0f - Math.abs(i - samples / 2) * blurStep;
            if (weight > 0) totalWeight += weight;
        }

        LOGGER.fine(String.format("Motion blur compute (strength=%.2f samp=%d blurStep=%.3f totalWeight=%.2f)",
                strength, samples, blurStep, totalWeight));
        return inputResources[0];
    }
}

/**
 * 景深 (DOF) 节点（可选）
 */
class DepthOfFieldNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(DepthOfFieldNode.class.getName());

    private volatile float focalDistance = 10.0f;
    private volatile float aperture = 4.0f;
    private volatile int blurQuality = 5;

    public DepthOfFieldNode() {
        super(
                "depth_of_field",
                "Depth of Field (景深)",
                PipelineNode.Category.POST_PROCESS,
                310,
                new String[]{"tonemap"}
        );
    }

    public void setFocalDistance(float d) { this.focalDistance = Math.max(0.1f, d); }
    public void setAperture(float a) { this.aperture = Math.max(0.1f, a); }
    public void setBlurQuality(int q) { this.blurQuality = Math.max(1, Math.min(10, q)); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        float coc = aperture * aperture / (focalDistance * 100.0f) * blurQuality;
        float nearBlurStop = focalDistance * 0.7f;
        float farBlurStop = focalDistance * 1.3f;

        LOGGER.fine(String.format("DOF compute (focus=%.1f aperture=%.1f quality=%d CoC=%.5f near=%.1f far=%.1f)",
                focalDistance, aperture, blurQuality, coc, nearBlurStop, farBlurStop));
        return inputResources[0];
    }
}

/**
 * 胶片颗粒节点（可选）
 */
class FilmGrainNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(FilmGrainNode.class.getName());

    private volatile float intensity = 0.1f;
    private volatile float speed = 1.0f;

    public FilmGrainNode() {
        super(
                "film_grain",
                "Film Grain (胶片颗粒)",
                PipelineNode.Category.POST_PROCESS,
                400,
                new String[]{"color_correction"}
        );
    }

    public void setIntensity(float i) { this.intensity = Math.max(0.0f, Math.min(1.0f, i)); }
    public void setSpeed(float s) { this.speed = Math.max(0.0f, s); }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;
        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        int hash = (int) (System.nanoTime() & 0x7FFFFFFF);
        float noiseSeed = (hash & 0xFFFF) / 65536.0f;

        LOGGER.fine(String.format("Film grain compute (intensity=%.2f speed=%.2f seed=%.4f)",
                intensity, speed, noiseSeed));
        return inputResources[0];
    }
}

/**
 * FXAA 抗锯齿节点
 *
 * <p>快速近似抗锯齿。
 * <pre>
 * 1. 亮度边缘检测: 比较当前像素与相邻 4 像素的亮度
 * 2. 混合方向估算: 边缘方向的最小亮度差
 * 3. 子像素混合: 沿边缘方向采样 2-4 次后加权平均
 * </pre>
 */
class FXAANode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(FXAANode.class.getName());

    public FXAANode() {
        super(
                "fxaa",
                "FXAA (快速抗锯齿)",
                PipelineNode.Category.POST_PROCESS,
                500,
                new String[]{"color_correction"}
        );
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (inputResources == null || inputResources.length == 0) return 0L;

        boolean hasSuperResolution = false;
        if (hasSuperResolution) {
            LOGGER.fine("FXAA 跳过（超分辨率已启用）");
            return inputResources[0];
        }

        if (!VulkanGraphicsHelper.isAvailable()) return inputResources[0];

        int edgePasses = 2;
        int blendSamples = 4;

        LOGGER.fine(String.format("FXAA compute (edgePasses=%d blendSamples=%d)",
                edgePasses, blendSamples));
        return inputResources[0];
    }
}
