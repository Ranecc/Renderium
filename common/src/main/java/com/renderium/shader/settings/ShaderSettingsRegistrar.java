// Renderium - Shader 图形设置集成
// ShaderSettingsRegistrar - 光影设置选项注册器
//
// 功能：
//   1. 将 ShaderGraphicsConfig 的所有参数注册为视频选项
//   2. 使用 RendererConfigBuilder 流式 API 构建页面结构
//   3. 数据双向绑定：UI ↔ Config ↔ ParameterRegistry
//   4. 按用户友好方式分组（预设/阴影/画面/环境/高级）
//
// 注册入口：
//   在 RendererVideoOptionsRegistrar.registerAll() 中调用
//   ShaderSettingsRegistrar.registerOptions(builder)

package com.renderium.shader.settings;

import com.renderium.bridge.video.BooleanOptionBuilder;
import com.renderium.bridge.video.EnumOptionBuilder;
import com.renderium.bridge.video.IntegerOptionBuilder;
import com.renderium.bridge.video.OptionGroupBuilder;
import com.renderium.bridge.video.OptionPageBuilder;
import com.renderium.bridge.video.RendererConfigBuilder;
import com.renderium.config.structure.OptionFlag;
import com.renderium.config.structure.OptionImpact;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Shader 设置选项注册器
 * <p>
 * 负责将 {@link ShaderGraphicsConfig} 中的所有参数注册到 Renderium 视频设置系统。
 * 生成的 UI 页面与 General / Quality / Performance 并列，作为第 4 个标签页。
 *
 * <h3>页面结构：</h3>
 * <pre>
 * ┌─ 光影设置 (Shader Settings) ─────────────────────┐
 * │                                                       │
 * │ ├─ 预设 (Preset)                                     │
 * │ │   ├── 光影预设: [OFF/MINIMAL/BALANCED/CINEMATIC/ULTRA]│
 * │ │   └── (切换预设自动调整下方所有参数)                  │
 * │                                                       │
 * │ ├─ 阴影与光照 (Shadows & Lighting)                    │
 * │ │   ├── 阴影质量: [OFF/LOW/MEDIUM/HIGH/ULTRA]        │
 * │ │   ├── 阴影距离: [8 ───●── 128] 方块               │
 * │ │   ├── 环境光遮蔽: [OFF/LOW/HIGH/ULTRA]             │
 * │ │   └── 光照反弹: [0 ───●── 4] 次                   │
 * │                                                       │
 * │ ├─ 画面增强 (Visual Enhancement)                     │
 * │ │   ├── 泛光强度: [0% ───●── 100%]                  │
 * │ │   ├── 色调映射: [OFF/ACES/FILMIC/REINHARD]        │
 * │ │   └── 暗角效果: [0% ───●── 100%]                 │
 * │                                                       │
 * │ ├─ 环境 (Environment)                                │
 * │ │   ├── 天空盒: [程序化/HDR/自定义]                   │
 * │ │   └── 云质量: [OFF/SIMPLE/VOLUMETRIC]              │
 * │                                                       │
 * │ └─ 高级 (Advanced)                                    │
 * │     ├── PBR 材质: [✓/✗]                              │
 * │     ├── 光线追踪反射: [OFF/LOW/MEDIUM/HIGH]          │
 * │     └── 自动曝光: [✓/✗]                              │
 * └───────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>性能影响标注：</h3>
 * <ul>
 *   <li>🟢 LOW - 几乎无性能影响（如暗角、色调映射）</li>
 *   <li>🟡 MEDIUM - 中等影响（如 SSAO、泛光）</li>
 *   <li>🔴 HIGH - 显著影响（如阴影质量、PBR、光线追踪）</li>
 * </ul>
 *
 * @since 3.0.0
 */
public final class ShaderSettingsRegistrar {

    private ShaderSettingsRegistrar() {}

    // ==================== 公共注册入口 ====================

    /**
     * 注册所有光影设置选项到构建器
     *
     * 【方法参数】
     * @param builder RendererConfigBuilder - 渲染配置构建器（非 null）
     *
     * 【调用时机】
     * 在 VideoSettingsBridge.initialize() 或 RendererVideoOptionsRegistrar.registerAll() 中调用
     *
     * 【示例】
     * <pre>
     * RendererVideoOptionsRegistrar.registerAll(builder, vanillaOpts, sodiumOpts);
     * ShaderSettingsRegistrar.registerOptions(builder);  // 添加光影页
     * </pre>
     */
    public static void registerOptions(RendererConfigBuilder builder) {
        if (builder == null) {
            throw new IllegalArgumentException("RendererConfigBuilder 不能为 null");
        }

        ShaderGraphicsConfig config = ShaderGraphicsConfig.getInstance();

        OptionPageBuilder shaderPage = builder.createOptionPage()
                .setName(Component.translatable("renderium.options.pages.shader"));

        // ====== 组 1: 预设 ======
        shaderPage.addOptionGroup(buildPresetGroup(builder, config));

        // ====== 组 2: 阴影与光照 ======
        shaderPage.addOptionGroup(buildShadowsLightingGroup(builder, config));

        // ====== 组 3: 画面增强 ======
        shaderPage.addOptionGroup(buildVisualEnhancementGroup(builder, config));

        // ====== 组 4: 环境 ======
        shaderPage.addOptionGroup(buildEnvironmentGroup(builder, config));

        // ====== 组 5: 高级 ======
        shaderPage.addOptionGroup(buildAdvancedGroup(builder, config));
    }

    // ==================== 组 1: 预设 ====================

    private static OptionGroupBuilder buildPresetGroup(
            RendererConfigBuilder builder, ShaderGraphicsConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("renderium.options.groups.shader_preset"))
                .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:shader.preset"),
                                ShaderPreset.class)
                                .setName(Component.translatable("renderium.shader.preset"))
                                .setTooltip(Component.translatable("renderium.shader.preset.tooltip"))
                                .setElementNameProvider(p -> Component.translatable(p.getTranslationKey()))
                                .setDefaultValue(ShaderPreset.BALANCED)
                                .setBinding(
                                        v -> { config.setPreset(v); config.applyPreset(v); },
                                        () -> config.getPreset()
                                )
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .build()
                );
    }

    // ==================== 组 2: 阴影与光照 ====================

    private static OptionGroupBuilder buildShadowsLightingGroup(
            RendererConfigBuilder builder, ShaderGraphicsConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("renderium.options.groups.shader_shadows"))

                // 阴影质量
                .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:shader.shadow_quality"),
                                ShadowQuality.class)
                                .setName(Component.translatable("renderium.shader.shadow_quality"))
                                .setTooltip(Component.translatable("renderium.shader.shadow_quality.tooltip"))
                                .setElementNameProvider(q -> Component.translatable(q.getTranslationKey()))
                                .setDefaultValue(ShadowQuality.MEDIUM)
                                .setBinding(
                                        v -> config.setShadowQuality(v),
                                        () -> config.getShadowQuality()
                                )
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .build()
                )

                // 阴影距离
                .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:shader.shadow_distance"))
                                .setName(Component.translatable("renderium.shader.shadow_distance"))
                                .setTooltip(Component.translatable("renderium.shader.shadow_distance.tooltip"))
                                .setRange(8, 128, 4)
                                .setDefaultValue(64)
                                .setValueFormatter(v ->
                                        Component.translatable("renderium.shader.chunks", v))
                                .setBinding(
                                        v -> config.setShadowDistance(v),
                                        () -> config.getShadowDistance()
                                )
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .build()
                )

                // 环境光遮蔽 (SSAO)
                .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:shader.ssao_quality"),
                                SSAOQuality.class)
                                .setName(Component.translatable("renderium.shader.ssao_quality"))
                                .setTooltip(Component.translatable("renderium.shader.ssao_quality.tooltip"))
                                .setElementNameProvider(q -> Component.translatable(q.getTranslationKey()))
                                .setDefaultValue(SSAOQuality.HIGH)
                                .setBinding(
                                        v -> config.setSsaoQuality(v),
                                        () -> config.getSsaoQuality()
                                )
                                .setImpact(OptionImpact.MEDIUM)
                                .build()
                )

                // 光照反弹次数
                .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:shader.gi_bounces"))
                                .setName(Component.translatable("renderium.shader.gi_bounces"))
                                .setTooltip(Component.translatable("renderium.shader.gi_bounces.tooltip"))
                                .setRange(0, 4, 1)
                                .setDefaultValue(1)
                                .setValueFormatter(v ->
                                        Component.translatable("renderium.shader.bounce_count", v))
                                .setBinding(
                                        v -> config.setGiBounces(v),
                                        () -> config.getGiBounces()
                                )
                                .setImpact(OptionImpact.HIGH)
                                .setEnabledProvider(() -> config.getPreset() != ShaderPreset.OFF)
                                .build()
                );
    }

    // ==================== 组 3: 画面增强 ====================

    private static OptionGroupBuilder buildVisualEnhancementGroup(
            RendererConfigBuilder builder, ShaderGraphicsConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("renderium.options.groups.shader_visual"))

                // 泛光强度
                .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:shader.bloom_intensity"))
                                .setName(Component.translatable("renderium.shader.bloom_intensity"))
                                .setTooltip(Component.translatable("renderium.shader.bloom_intensity.tooltip"))
                                .setRange(0, 100, 5)
                                .setDefaultValue(30)
                                .setValueFormatter(v ->
                                        Component.translatable("renderium.shader.percent", v))
                                .setBinding(
                                        v -> config.setBloomIntensity((float) v),
                                        () -> (int) config.getBloomIntensity()
                                )
                                .setImpact(OptionImpact.MEDIUM)
                                .setEnabledProvider(() -> config.getPreset() != ShaderPreset.OFF)
                                .build()
                )

                // 色调映射
                .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:shader.tonemap_mode"),
                                TonemapMode.class)
                                .setName(Component.translatable("renderium.shader.tonemap_mode"))
                                .setTooltip(Component.translatable("renderium.shader.tonemap_mode.tooltip"))
                                .setElementNameProvider(m -> Component.translatable(m.getTranslationKey()))
                                .setDefaultValue(TonemapMode.ACES)
                                .setBinding(
                                        v -> config.setTonemapMode(v),
                                        () -> config.getTonemapMode()
                                )
                                .setImpact(OptionImpact.LOW)
                                .build()
                )

                // 暗角强度
                .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:shader.vignette"))
                                .setName(Component.translatable("renderium.shader.vignette"))
                                .setTooltip(Component.translatable("renderium.shader.vignette.tooltip"))
                                .setRange(0, 100, 5)
                                .setDefaultValue(25)
                                .setValueFormatter(v ->
                                        Component.translatable("renderium.shader.percent", v))
                                .setBinding(
                                        v -> config.setVignetteIntensity((float) v),
                                        () -> (int) config.getVignetteIntensity()
                                )
                                .setImpact(OptionImpact.LOW)
                                .build()
                );
    }

    // ==================== 组 4: 环境 ====================

    private static OptionGroupBuilder buildEnvironmentGroup(
            RendererConfigBuilder builder, ShaderGraphicsConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("renderium.options.groups.shader_environment"))

                // 天空盒类型
                .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:shader.skybox_type"),
                                SkyboxType.class)
                                .setName(Component.translatable("renderium.shader.skybox_type"))
                                .setTooltip(Component.translatable("renderium.shader.skybox_type.tooltip"))
                                .setElementNameProvider(s -> Component.translatable(s.getTranslationKey()))
                                .setDefaultValue(SkyboxType.PROCEDURAL)
                                .setBinding(
                                        v -> config.setSkyboxType(v),
                                        () -> config.getSkyboxType()
                                )
                                .setImpact(OptionImpact.LOW)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .build()
                )

                // 云渲染质量
                .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:shader.cloud_quality"),
                                CloudQuality.class)
                                .setName(Component.translatable("renderium.shader.cloud_quality"))
                                .setTooltip(Component.translatable("renderium.shader.cloud_quality.tooltip"))
                                .setElementNameProvider(c -> Component.translatable(c.getTranslationKey()))
                                .setDefaultValue(CloudQuality.SIMPLE)
                                .setBinding(
                                        v -> config.setCloudQuality(v),
                                        () -> config.getCloudQuality()
                                )
                                .setImpact(OptionImpact.MEDIUM)
                                .build()
                );
    }

    // ==================== 组 5: 高级 ====================

    private static OptionGroupBuilder buildAdvancedGroup(
            RendererConfigBuilder builder, ShaderGraphicsConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("renderium.options.groups.shader_advanced"))

                // PBR 材质
                .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:shader.pbr_enabled"))
                                .setName(Component.translatable("renderium.shader.pbr_enabled"))
                                .setTooltip(Component.translatable("renderium.shader.pbr_enabled.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(
                                        v -> config.setPbrEnabled(v),
                                        () -> config.isPbrEnabled()
                                )
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .setEnabledProvider(() -> config.getPreset().ordinal() >= ShaderPreset.BALANCED.ordinal())
                                .build()
                )

                // 光线追踪反射
                .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:shader.rt_reflections"),
                                RTReflectionQuality.class)
                                .setName(Component.translatable("renderium.shader.rt_reflections"))
                                .setTooltip(Component.translatable("renderium.shader.rt_reflections.tooltip"))
                                .setElementNameProvider(r -> Component.translatable(r.getTranslationKey()))
                                .setDefaultValue(RTReflectionQuality.OFF)
                                .setBinding(
                                        v -> config.setRtReflections(v.ordinal()),
                                        () -> RTReflectionQuality.fromInt(config.getRtReflections())
                                )
                                .setImpact(OptionImpact.HIGH)
                                .setEnabledProvider(() -> config.getPreset().ordinal() >= ShaderPreset.CINEMATIC.ordinal())
                                .build()
                )

                // 自动曝光
                .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:shader.auto_exposure"))
                                .setName(Component.translatable("renderium.shader.auto_exposure"))
                                .setTooltip(Component.translatable("renderium.shader.auto_exposure.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(
                                        v -> config.setAutoExposure(v),
                                        () -> config.isAutoExposure()
                                )
                                .setImpact(OptionImpact.LOW)
                                .build()
                );
    }

    // ==================== 内部枚举：RT 反射质量 ====================

    /**
     * 光线追踪反射质量（UI 专用，内部映射到 int）
     */
    public enum RTReflectionQuality {
        OFF("renderium.shader.rt.off"),
        LOW("renderium.shader.rt.low"),
        MEDIUM("renderium.shader.rt.medium"),
        HIGH("renderium.shader.rt.high");

        private final String translationKey;
        RTReflectionQuality(String k) { this.translationKey = k; }
        public String getTranslationKey() { return translationKey; }
        public static RTReflectionQuality fromInt(int v) { return values()[Math.clamp(v, 0, 3)]; }
    }
}
