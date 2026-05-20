package com.ranecc.renderium.feature.pipeline.node.builtin;

/**
 * 着色器管线节点 TODO 清单
 * <p>
 * 对比 Blender Compositor / Unreal Material Editor / Unity URP，
 * Renderium 当前缺失的关键渲染节点。所有节点设计原则：
 * <ul>
 *   <li>不启用就短路（zero-cost when disabled）</li>
 *   <li>高性能实现（Compute Shader 优先）</li>
 *   <li>可组合（DAG 依赖图）</li>
 * </ul>
 *
 * <h3>优先级定义：</h3>
 * <ul>
 *   <li>P0 — 核心缺失，影响基础渲染质量</li>
 *   <li>P1 — 重要缺失，影响高级渲染效果</li>
 *   <li>P2 — 锦上添花，专业级功能</li>
 * </ul>
 *
 * @since 5.5.0
 */
public final class ShaderNodeTODO {

    private ShaderNodeTODO() {} // 工具类

    // ==================== P0: 核心缺失节点 ====================

    /**
     * TODO P0: 景深节点 (Depth of Field)
     * <p>
     * Blender: Defocus Node | Unreal: Depth of Field | Unity: Depth of Field
     * <p>
     * 实现：Compute Shader 散景模糊，基于深度缓冲区分离前景/背景
     * 预估 GPU 开销：1-3ms (1080p)
     * 短路条件：dofEnabled == false → 直接 pass-through
     */
    public static final String DEPTH_OF_FIELD = "TODO_P0_DepthOfField";

    /**
     * TODO P0: 运动模糊节点 (Motion Blur)
     * <p>
     * Blender: Vector Blur | Unreal: Motion Blur | Unity: Motion Blur
     * <p>
     * 实现：基于速度缓冲的逐像素运动模糊
     * 预估 GPU 开销：0.5-2ms
     * 短路条件：motionBlurEnabled == false
     */
    public static final String MOTION_BLUR = "TODO_P0_MotionBlur";

    /**
     * TODO P0: 抗锯齿节点 (TAA/FXAA/SMAA)
     * <p>
     * Blender: Anti-Aliasing | Unreal: TAA | Unity: TAA
     * <p>
     * 当前仅有 FXAA 节点注册，缺少 TAA (Temporal Anti-Aliasing)
     * TAA 需要历史帧缓冲 + 运动矢量 + 邻域夹紧
     * 预估 GPU 开销：0.5-1ms
     * 短路条件：taaEnabled == false
     */
    public static final String TEMPORAL_AA = "TODO_P0_TemporalAA";

    /**
     * TODO P0: 雾效节点 (Volumetric Fog)
     * <p>
     * Blender: Mist | Unreal: Exponential Height Fog | Unity: Volumetric Fog
     * <p>
     * 当前 MC 原版雾效是线性深度雾，缺少体积雾
     * 实现：Froxel 体积雾（16x16x64 网格 + Ray Marching）
     * 预估 GPU 开销：1-3ms
     * 短路条件：fogMode == LINEAR → 使用原版雾
     */
    public static final String VOLUMETRIC_FOG = "TODO_P0_VolumetricFog";

    // ==================== P1: 重要缺失节点 ====================

    /**
     * TODO P1: 屏幕空间反射 (SSR)
     * <p>
     * Blender: Screen Space Reflections | Unreal: SSR | Unity: SSR
     * <p>
     * 当前有 RT 反射但缺少 SSR 作为降级方案
     * RT 反射需要 RT Core，SSR 可在任何 GPU 上运行
     * 实现：Hi-Z Tracing + 半分辨率 + 时空复用
     * 预估 GPU 开销：2-4ms
     * 短路条件：ssrEnabled == false 或 rtReflections > 0
     */
    public static final String SCREEN_SPACE_REFLECTION = "TODO_P1_SSR";

    /**
     * TODO P1: 镜头光晕 (Lens Flare)
     * <p>
     * Blender: Lens Flare | Unreal: Lens Flare | Unity: Lens Flare
     * <p>
     * 实现：基于亮度的 Ghost + Streak 生成
     * 预估 GPU 开销：0.3-1ms
     * 短路条件：lensFlareEnabled == false
     */
    public static final String LENS_FLARE = "TODO_P1_LensFlare";

    /**
     * TODO P1: 色差/色散 (Chromatic Aberration)
     * <p>
     * Blender: Lens Distortion | Unreal: Chromatic Aberration
     * <p>
     * 实现：RGB 通道偏移采样
     * 预估 GPU 开销：< 0.3ms
     * 短路条件：chromaticAberrationStrength == 0
     */
    public static final String CHROMATIC_ABERRATION = "TODO_P1_ChromaticAberration";

    /**
     * TODO P1: 曝光/自动曝光 (Auto Exposure / Eye Adaptation)
     * <p>
     * Blender: Glare + Map Value | Unreal: Auto Exposure | Unity: Auto Exposure
     * <p>
     * 当前有 autoExposure 配置但缺少独立节点
     * 实现：亮度直方图 + 时空平滑 + 曝光补偿曲线
     * 预估 GPU 开销：< 0.5ms
     * 短路条件：autoExposureEnabled == false
     */
    public static final String AUTO_EXPOSURE = "TODO_P1_AutoExposure";

    /**
     * TODO P1: 胶片颗粒 (Film Grain)
     * <p>
     * Blender: Filter | Unreal: Film Grain | Unity: Film Grain
     * <p>
     * 实现：TAA 历史帧抖动 + 噪声纹理混合
     * 预估 GPU 开销：< 0.1ms
     * 短路条件：filmGrainStrength == 0
     */
    public static final String FILM_GRAIN = "TODO_P1_FilmGrain";

    // ==================== P2: 专业级缺失节点 ====================

    /**
     * TODO P2: 次表面散射 (Subsurface Scattering)
     * <p>
     * Blender: Subsurface Scattering | Unreal: Subsurface | Unity: SSS
     * <p>
     * MC 中主要用于树叶、皮肤、蜡烛等半透明材质
     * 实现：屏幕空间模糊 + 预积分皮肤 Profile
     * 预估 GPU 开销：1-2ms
     * 短路条件：sssEnabled == false
     */
    public static final String SUBSURFACE_SCATTERING = "TODO_P2_SSS";

    /**
     * TODO P2: 透射/折射 (Refraction)
     * <p>
     * Blender: Refraction BSDF | Unreal: Refraction | Unity: Refraction
     * <p>
     * MC 中用于水、玻璃等
     * 实现：屏幕空间折射 + 粗糙度偏移
     * 预估 GPU 开销：1-2ms
     * 短路条件：refractionEnabled == false
     */
    public static final String REFRACTION = "TODO_P2_Refraction";

    /**
     * TODO P2: 紫边去除 (Defringe / Purple Fringe Removal)
     * <p>
     * Blender: Defringe | Unreal: N/A
     * <p>
     * 预估 GPU 开销：< 0.2ms
     */
    public static final String DEFRINGE = "TODO_P2_Defringe";

    /**
     * TODO P2: 色调分离 (Posterize)
     * <p>
     * Blender: Posterize | Unreal: N/A
     * <p>
     * 预估 GPU 开销：< 0.1ms
     */
    public static final String POSTERIZE = "TODO_P2_Posterize";

    /**
     * TODO P2: 边缘检测 (Edge Detect / Sobel)
     * <p>
     * Blender: Edge Detect | Unreal: Outline | Unity: Outline
     * <p>
     * 用于卡通渲染/描边效果
     * 预估 GPU 开销：< 0.3ms
     */
    public static final String EDGE_DETECT = "TODO_P2_EdgeDetect";

    /**
     * TODO P2: 色调映射变体 (ToneMapping Variants)
     * <p>
     * 当前仅有 ACES，缺少：
     * - AgX (Blender 4.0+ 默认)
     * - Filmic (Blender 旧版)
     * - Reinhard (简单)
     * - Uncharted 2
     */
    public static final String TONEMAP_AGX = "TODO_P2_AgXToneMap";
    public static final String TONEMAP_UNCHARTED2 = "TODO_P2_Uncharted2ToneMap";
}
