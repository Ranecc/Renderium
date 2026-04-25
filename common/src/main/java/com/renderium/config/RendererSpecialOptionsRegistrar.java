// Renderium - 特有选项注册器
// 注册超分辨率、帧生成、Reflex、Hi-Z 遮挡剔除、GPU LOD 等高级功能

package com.renderium.config;

import com.renderium.bridge.video.BooleanOptionBuilder;
import com.renderium.bridge.video.EnumOptionBuilder;
import com.renderium.bridge.video.IntegerOptionBuilder;
import com.renderium.bridge.video.OptionGroupBuilder;
import com.renderium.bridge.video.OptionPageBuilder;
import com.renderium.bridge.video.RendererConfigBuilder;
import com.renderium.config.structure.OptionFlag;
import com.renderium.config.structure.OptionImpact;
import com.renderium.config.structure.Range;
import com.renderium.ui.widgets.options.control.ControlValueFormatterImpls;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Renderium 特有视频选项注册器
 *
 * <p>负责注册所有 Renderium 独有的 GPU 加速功能选项，包括：
 * <ul>
 *   <li><b>超分辨率</b>: DLSS/FSR/NIS/XeSS 技术选择与质量配置</li>
 *   <li><b>帧生成</b>: DLSS-FG/FG-X 帧倍增设置</li>
 *   <li><b>NVIDIA Reflex</b>: 低延迟模式与性能标记器</li>
 *   <li><b>Hi-Z 遮挡剔除</b>: Z-Buffer 层级遮挡检测优化</li>
 *   <li><b>GPU Compute Shader LOD</b>: GPU 驱动的细节层次选择</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 在 VideoSettingsBridge.registerOptions() 中调用
 * public static void registerOptions(RendererConfigBuilder builder) {
 *     RenderiumConfig config = RenderiumConfig.getInstance();
 *     RendererSpecialOptionsRegistrar.registerAllSpecial(builder, config);
 * }
 * }</pre>
 *
 * @author Renderium Team
 * @since 5.3.0
 */
public final class RendererSpecialOptionsRegistrar {

    /** 日志记录器 */
    private static final java.util.logging.Logger LOGGER =
        java.util.logging.Logger.getLogger(RendererSpecialOptionsRegistrar.class.getName());

    /** 私有构造器（工具类） */
    private RendererSpecialOptionsRegistrar() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 一次性注册所有 Renderium 特有选项页面
     *
     * @param builder 渲染配置构建器实例
     * @param config  Renderium 配置实例（用于绑定）
     */
    public static void registerAllSpecial(RendererConfigBuilder builder, RenderiumConfig config) {
        registerSuperResolutionOptions(builder, config);
        registerFrameGenerationOptions(builder, config);
        registerReflexOptions(builder, config);
        registerCullingOptions(builder, config);
        registerLodOptions(builder, config);

        LOGGER.fine("[Renderium] 已注册所有特有选项页面 (5 个页面, ~25 个选项)");
    }

    // ==================== Task 5.4a: 超分辨率选项页 ====================

    /**
     * 注册超分辨率（Super Resolution）选项页面
     *
     * <p>包含技术选择、质量模式、分辨率缩放和锐化等 6 个选项。
     *
     * @param builder 配置构建器
     * @param config  Renderium 配置实例
     */
    public static void registerSuperResolutionOptions(RendererConfigBuilder builder, RenderiumConfig config) {
        builder.createOptionPage()
            .setName(Component.translatable("renderium.options.pages.super_resolution"))
            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.sr_core"))

                    // 启用超分辨率开关
                    .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:sr.enabled"))
                            .setName(Component.translatable("renderium.options.sr.enabled"))
                            .setTooltip(Component.translatable("renderium.options.sr.enabled.tooltip"))
                            .setDefaultValue(false)
                            .setBinding(
                                value -> config.setSuperResolutionEnabled(value),
                                () -> config.isSuperResolutionEnabled()
                            )
                            .setImpact(OptionImpact.VERY_HIGH)
                            .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                            .build()
                    )

                    // 技术选择（DLSS/FSR/NIS/XeSS）
                    .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:sr.technique"),
                                SuperResolutionTechnique.class)
                            .setName(Component.translatable("renderium.options.sr.technique"))
                            .setTooltip(Component.translatable("renderium.options.sr.technique.tooltip"))
                            .setDefaultValue(SuperResolutionTechnique.NONE)
                            .setElementNameProvider(tech ->
                                tech == SuperResolutionTechnique.NONE
                                    ? Component.translatable("renderium.options.off")
                                    : Component.literal(tech.name())
                            )
                            .setBinding(
                                value -> config.setTechnology(
                                    switch (value) {
                                        case NONE -> SuperResolutionAdapter.Technology.DLSS;  // 默认回退
                                        case DLSS -> SuperResolutionAdapter.Technology.DLSS;
                                        case FSR -> SuperResolutionAdapter.Technology.FSR;
                                        case NIS -> SuperResolutionAdapter.Technology.NIS;
                                        case XeSS -> SuperResolutionAdapter.Technology.DLSS;  // 暂不支持
                                    }
                                ),
                                () -> switch (config.getTechnology()) {
                                    case DLSS -> SuperResolutionTechnique.DLSS;
                                    case FSR -> SuperResolutionTechnique.FSR;
                                    case NIS -> SuperResolutionTechnique.NIS;
                                    default -> SuperResolutionTechnique.NONE;
                                }
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(
                                    Identifier.parse("renderium:sr.enabled"))
                            )
                            .build()
                    )

                    // 质量预设（Performance/Balanced/Quality/Ultra）
                    .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:sr.quality"),
                                QualityPreset.class)
                            .setName(Component.translatable("renderium.options.sr.quality"))
                            .setTooltip(Component.translatable("renderium.options.sr.quality.tooltip"))
                            .setDefaultValue(QualityPreset.BALANCED)
                            .setElementNameProvider(preset ->
                                Component.translatable("renderium.enum.quality." + preset.name().toLowerCase())
                            )
                            .setBinding(
                                value -> config.setQuality(
                                    switch (value) {
                                        case PERFORMANCE -> SuperResolutionAdapter.Quality.PERFORMANCE;
                                        case BALANCED -> SuperResolutionAdapter.Quality.BALANCED;
                                        case QUALITY -> SuperResolutionAdapter.Quality.QUALITY;
                                        case ULTRA -> SuperResolutionAdapter.Quality.ULTRA;
                                    }
                                ),
                                () -> switch (config.getQuality()) {
                                    case PERFORMANCE -> QualityPreset.PERFORMANCE;
                                    case BALANCED -> QualityPreset.BALANCED;
                                    case QUALITY -> QualityPreset.QUALITY;
                                    case ULTRA -> QualityPreset.ULTRA;
                                    default -> QualityPreset.BALANCED;
                                }
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:sr.enabled"))
                                && state.readEnumOption(
                                    Identifier.parse("renderium:sr.technique"),
                                    SuperResolutionTechnique.class)
                                   != SuperResolutionTechnique.NONE
                            )
                            .build()
                    )
            )

            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.sr_scaling"))

                    // 分辨率缩放百分比（50% - 200%）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:sr.resolution_scale"))
                            .setName(Component.translatable("renderium.options.sr.resolution_scale"))
                            .setTooltip(Component.translatable("renderium.options.sr.resolution_scale.tooltip"))
                            .setRange(new Range(50, 200, 5))
                            .setDefaultValue(100)
                            .setValueFormatter(ControlValueFormatterImpls.percentage())
                            .setBinding(
                                value -> { /* TODO: 绑定到 dynamicResolution 缩放因子 */ },
                                () -> 100  // TODO: 从 config 读取实际值
                            )
                            .setEnabledProvider(state ->
                                state.readEnumOption(
                                    Identifier.parse("renderium:sr.technique"),
                                    SuperResolutionTechnique.class)
                                   == SuperResolutionTechnique.FSR
                                || state.readEnumOption(
                                    Identifier.parse("renderium:sr.technique"),
                                    SuperResolutionTechnique.class)
                                   == SuperResolutionTechnique.NIS
                            )
                            .build()
                    )

                    // 锐化强度（0 - 100）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:sr.sharpness"))
                            .setName(Component.translatable("renderium.options.sr.sharpness"))
                            .setTooltip(Component.translatable("renderium.options.sr.sharpness.tooltip"))
                            .setRange(new Range(0, 100, 1))
                            .setDefaultValue(50)
                            .setBinding(
                                value -> config.setSharpening(value / 100.0f),
                                () -> Math.round(config.getSharpening() * 100.0f)
                            )
                            .setEnabledProvider(state ->
                                state.readEnumOption(
                                    Identifier.parse("renderium:sr.technique"),
                                    SuperResolutionTechnique.class)
                                   == SuperResolutionTechnique.FSR
                            )
                            .build()
                    )
            );

        LOGGER.info("[Renderium] 已注册超分辨率选项页面 (6 个选项)");
    }

    // ==================== Task 5.4b: 帧生成选项页 ====================

    /**
     * 注册帧生成（Frame Generation）选项页面
     *
     * <p>包含启用开关、模式选择、帧倍增因子和质量等级等 5 个选项。
     * <p><b>注意</b>：帧生成需要 RTX 40+ 系列 GPU 支持。
     *
     * @param builder 配置构建器
     * @param config  Renderium 配置实例
     */
    public static void registerFrameGenerationOptions(RendererConfigBuilder builder, RenderiumConfig config) {
        builder.createOptionPage()
            .setName(Component.translatable("renderium.options.pages.frame_generation"))
            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.fg_core"))

                    // 启用帧生成开关
                    .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:fg.enabled"))
                            .setName(Component.translatable("renderium.options.fg.enabled"))
                            .setTooltip(Component.translatable("renderium.options.fg.enabled.tooltip"))
                            .setDefaultValue(false)
                            .setBinding(
                                value -> config.setFrameGenerationEnabled(value),
                                () -> config.isFrameGenerationEnabled()
                            )
                            .setImpact(OptionImpact.VERY_HIGH)
                            .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD,
                                      OptionFlag.REQUIRES_GAME_RESTART)
                            .build()
                    )

                    // 帧生成模式（DLSS-FG / FG-X）
                    .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:fg.mode"),
                                FrameGenModeOption.class)
                            .setName(Component.translatable("renderium.options.fg.mode"))
                            .setTooltip(Component.translatable("renderium.options.fg.mode.tooltip"))
                            .setDefaultValue(FrameGenModeOption.NONE)
                            .setElementNameProvider(mode ->
                                mode == FrameGenModeOption.NONE
                                    ? Component.translatable("renderium.options.off")
                                    : Component.literal(mode.name())
                            )
                            .setBinding(
                                value -> config.setFrameGenMode(
                                    switch (value) {
                                        case NONE -> FrameGenMode.FIXED_2X;  // 默认
                                        case DLSS_FG -> FrameGenMode.DLSS_FG;
                                        case FG_X -> FrameGenMode.FG_X;
                                    }
                                ),
                                () -> switch (config.getFrameGenMode()) {
                                    case DLSS_FG -> FrameGenModeOption.DLSS_FG;
                                    case FG_X -> FrameGenModeOption.FG_X;
                                    default -> FrameGenModeOption.NONE;
                                }
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:fg.enabled"))
                            )
                            .build()
                    )
            )

            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.fg_multiplier"))

                    // 帧倍增因子（X2 / X3 / X4）
                    .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:fg.frame_multiplier"),
                                FrameMultiplier.class)
                            .setName(Component.translatable("renderium.options.fg.frame_multiplier"))
                            .setTooltip(Component.translatable("renderium.options.fg.frame_multiplier.tooltip"))
                            .setDefaultValue(FrameMultiplier.X2)
                            .setElementNameProvider(multiplier ->
                                Component.literal(multiplier.name())
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:fg.enabled"))
                                && state.readEnumOption(
                                    Identifier.parse("renderium:fg.mode"),
                                    FrameGenModeOption.class)
                                   != FrameGenModeOption.NONE
                            )
                            .build()
                    )

                    // 帧生成质量（Balanced / Quality / Ultra Quality）
                    .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:fg.quality"),
                                FrameGenQuality.class)
                            .setName(Component.translatable("renderium.options.fg.quality"))
                            .setTooltip(Component.translatable("renderium.options.fg.quality.tooltip"))
                            .setDefaultValue(FrameGenQuality.BALANCED)
                            .setElementNameProvider(quality ->
                                Component.translatable("renderium.enum.framegen_quality."
                                    + quality.name().toLowerCase().replace("_", ""))
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:fg.enabled"))
                                && state.readEnumOption(
                                    Identifier.parse("renderium:fg.mode"),
                                    FrameGenModeOption.class)
                                   != FrameGenModeOption.NONE
                            )
                            .build()
                    )
            );

        LOGGER.info("[Renderium] 已注册帧生成选项页面 (5 个选项)");
    }

    // ==================== Task 5.4c: Reflex 选项页 ====================

    /**
     * 注册 NVIDIA Reflex 低延迟选项页面
     *
     * <p>包含启用开关、延迟模式、PCL 标记器和闪烁指示器等 4 个选项。
     *
     * @param builder 配置构建器
     * @param config  Renderium 配置实例
     */
    public static void registerReflexOptions(RendererConfigBuilder builder, RenderiumConfig config) {
        builder.createOptionPage()
            .setName(Component.translatable("renderium.options.pages.reflex"))
            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.reflex_latency"))

                    // 启用 Reflex 开关
                    .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:reflex.enabled"))
                            .setName(Component.translatable("renderium.options.reflex.enabled"))
                            .setTooltip(Component.translatable("renderium.options.reflex.enabled.tooltip"))
                            .setDefaultValue(false)
                            .setBinding(
                                value -> config.setReflexEnabled(value),
                                () -> config.isReflexEnabled()
                            )
                            .setImpact(OptionImpact.LOW)
                            .build()
                    )

                    // 延迟模式（Off / Low / Ultra）
                    .addOption(
                        builder.createEnumOption(
                                Identifier.parse("renderium:reflex.latency_mode"),
                                LatencyModeOption.class)
                            .setName(Component.translatable("renderium.options.reflex.latency_mode"))
                            .setTooltip(Component.translatable("renderium.options.reflex.latency_mode.tooltip"))
                            .setDefaultValue(LatencyModeOption.OFF)
                            .setElementNameProvider(mode ->
                                mode == LatencyModeOption.OFF
                                    ? Component.translatable("renderium.options.off")
                                    : Component.literal(mode.name())
                            )
                            .setBinding(
                                value -> config.setReflexMode(
                                    switch (value) {
                                        case OFF -> ReflexMode.LOW_LATENCY;  // 默认
                                        case LOW -> ReflexMode.LOW_LATENCY;
                                        case ULTRA -> ReflexMode.ULTRA_LOW_LATENCY;
                                    }
                                ),
                                () -> switch (config.getReflexMode()) {
                                    case LOW_LATENCY -> LatencyModeOption.LOW;
                                    case ULTRA_LOW_LATENCY -> LatencyModeOption.ULTRA;
                                    default -> LatencyModeOption.OFF;
                                }
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:reflex.enabled"))
                            )
                            .build()
                    )
            )

            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.reflex_markers"))

                    // 启用 PCL 性能标记器
                    .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:reflex.enable_markers"))
                            .setName(Component.translatable("renderium.options.reflex.enable_markers"))
                            .setTooltip(Component.translatable("renderium.options.reflex.enable_markers.tooltip"))
                            .setDefaultValue(true)
                            .setBinding(
                                value -> { /* TODO: 绑定到 ReflexManagerImpl.enableMarkers */ },
                                () -> true  // TODO: 从 ReflexManagerImpl 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:reflex.enabled"))
                            )
                            .build()
                    )

                    // 闪烁指示器
                    .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:reflex.flash_indicator"))
                            .setName(Component.translatable("renderium.options.reflex.flash_indicator"))
                            .setTooltip(Component.translatable("renderium.options.reflex.flash_indicator.tooltip"))
                            .setDefaultValue(false)
                            .setBinding(
                                value -> { /* TODO: 绑定到 ReflexManagerImpl.flashIndicator */ },
                                () -> false  // TODO: 从 ReflexManagerImpl 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:reflex.enabled"))
                                && state.readEnumOption(
                                    Identifier.parse("renderium:reflex.latency_mode"),
                                    LatencyModeOption.class)
                                   != LatencyModeOption.OFF
                            )
                            .build()
                    )
            );

        LOGGER.info("[Renderium] 已注册 Reflex 选项页面 (4 个选项)");
    }

    // ==================== Task 5.4d: Hi-Z 遮挡剔除选项页 ====================

    /**
     * 注册 Hi-Z Occlusion Culling 选项页面
     *
     * <p>基于 Z-Buffer 层级金字塔的遮挡剔除系统配置。
     * 可显著减少不可见几何体的渲染开销。
     *
     * @param builder 配置构建器
     * @param config  Renderium 配置实例
     */
    public static void registerCullingOptions(RendererConfigBuilder builder, RenderiumConfig config) {
        builder.createOptionPage()
            .setName(Component.translatable("renderium.options.pages.culling"))
            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.culling_core"))

                    // 启用 Hi-Z 遮挡剔除
                    .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:culling.hiz_enabled"))
                            .setName(Component.translatable("renderium.options.culling.hiz_enabled"))
                            .setTooltip(Component.translatable("renderium.options.culling.hiz_enabled.tooltip"))
                            .setDefaultValue(true)
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.culling.hizOcclusionEnabled */ },
                                () -> true  // TODO: 从 culling config 读取
                            )
                            .setImpact(OptionImpact.HIGH)
                            .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                            .build()
                    )

                    // Z-Buffer 金字塔层数（2 - 8）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:culling.z_buffer_layers"))
                            .setName(Component.translatable("renderium.options.culling.z_buffer_layers"))
                            .setTooltip(Component.translatable("renderium.options.culling.z_buffer_layers.tooltip"))
                            .setRange(new Range(2, 8, 1))
                            .setDefaultValue(4)
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.culling.zBufferLayers */ },
                                () -> 4  // TODO: 从 culling config 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:culling.hiz_enabled"))
                            )
                            .build()
                    )

                    // 最大遮挡体数量（64 - 4096）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:culling.max_occluders"))
                            .setName(Component.translatable("renderium.options.culling.max_occluders"))
                            .setTooltip(Component.translatable("renderium.options.culling.max_occluders.tooltip"))
                            .setRange(new Range(64, 4096, 64))
                            .setDefaultValue(512)
                            .setValueFormatter(ControlValueFormatterImpls.number())
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.culling.maxOccluders */ },
                                () -> 512  // TODO: 从 culling config 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:culling.hiz_enabled"))
                            )
                            .build()
                    )
            )

            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.culling_advanced"))

                    // 单次查询最大像素区域（8x8 到 64x64）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:culling.max_query_size"))
                            .setName(Component.translatable("renderium.options.culling.max_query_size"))
                            .setTooltip(Component.translatable("renderium.options.culling.max_query_size.tooltip"))
                            .setRange(new Range(8, 64, 8))
                            .setDefaultValue(16)
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.culling.maxQuerySize */ },
                                () -> 16  // TODO: 从 culling config 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:culling.hiz_enabled"))
                            )
                            .build()
                    )
            );

        LOGGER.info("[Renderium] 已注册 Hi-Z 遮挡剔除选项页面 (5 个选项)");
    }

    // ==================== Task 5.4e: GPU Compute Shader LOD 选项页 ====================

    /**
     * 注册 GPU Compute Shader LOD 选项页面
     *
     * <p>GPU 驱动的细节层次（LOD）选择系统，
     * 使用计算着色器根据距离和屏幕覆盖率动态调整模型细节。
     *
     * @param builder 配置构建器
     * @param config  Renderium 配置实例
     */
    public static void registerLodOptions(RendererConfigBuilder builder, RenderiumConfig config) {
        builder.createOptionPage()
            .setName(Component.translatable("renderium.options.pages.lod"))
            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.lod_core"))

                    // 启用 GPU LOD
                    .addOption(
                        builder.createBooleanOption(
                                Identifier.parse("renderium:lod.gpu_enabled"))
                            .setName(Component.translatable("renderium.options.lod.gpu_enabled"))
                            .setTooltip(Component.translatable("renderium.options.lod.gpu_enabled.tooltip"))
                            .setDefaultValue(true)
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.gpuLod.enabled */ },
                                () -> true  // TODO: 从 gpuLod config 读取
                            )
                            .setImpact(OptionImpact.HIGH)
                            .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                            .build()
                    )

                    // 全局 LOD 偏移（-20 到 +20）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:lod.lod_bias"))
                            .setName(Component.translatable("renderium.options.lod.lod_bias"))
                            .setTooltip(Component.translatable("renderium.options.lod.lod_bias.tooltip"))
                            .setRange(new Range(-20, 20, 1))
                            .setDefaultValue(0)
                            .setValueFormatter(value ->
                                value >= 0
                                    ? Component.literal("+" + value)
                                    : Component.literal(String.valueOf(value))
                            )
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.gpuLod.lodBias */ },
                                () -> 0  // TODO: 从 gpuLod config 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:lod.gpu_enabled"))
                            )
                            .build()
                    )

                    // 最大 LOD 级别数（1 - 8）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:lod.max_levels"))
                            .setName(Component.translatable("renderium.options.lod.max_levels"))
                            .setTooltip(Component.translatable("renderium.options.lod.max_levels.tooltip"))
                            .setRange(new Range(1, 8, 1))
                            .setDefaultValue(4)
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.gpuLod.maxLevels */ },
                                () -> 4  // TODO: 从 gpuLod config 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:lod.gpu_enabled"))
                            )
                            .build()
                    )
            )

            .addOptionGroup(
                builder.createOptionGroup()
                    .setName(Component.translatable("renderium.options.groups.lod_thresholds"))

                    // LOD1 开始距离（区块数，8 - 64）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:lod.distance_lod1_start"))
                            .setName(Component.translatable("renderium.options.lod.distance_lod1_start"))
                            .setTooltip(Component.translatable("renderium.options.lod.distance_lod1_start.tooltip"))
                            .setRange(new Range(8, 64, 2))
                            .setDefaultValue(16)
                            .setValueFormatter(value ->
                                Component.translatable("options.chunks", value)
                            )
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.gpuLod.distanceLod1Start */ },
                                () -> 16  // TODO: 从 gpuLod config 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:lod.gpu_enabled"))
                            )
                            .build()
                    )

                    // LOD1 屏幕覆盖率阈值（百分比，1% - 20%）
                    .addOption(
                        builder.createIntegerOption(
                                Identifier.parse("renderium:lod.screen_coverage_lod1"))
                            .setName(Component.translatable("renderium.options.lod.screen_coverage_lod1"))
                            .setTooltip(Component.translatable("renderium.options.lod.screen_coverage_lod1.tooltip"))
                            .setRange(new Range(1, 20, 1))
                            .setDefaultValue(5)
                            .setValueFormatter(value ->
                                Component.literal(value + "% " +
                                    Component.translatable("renderium.unit.of_screen").getString())
                            )
                            .setBinding(
                                value -> { /* TODO: 绑定到 config.gpuLod.screenCoverageLod1 */ },
                                () -> 5  // TODO: 从 gpuLod config 读取
                            )
                            .setEnabledProvider(state ->
                                state.readBoolOption(Identifier.parse("renderium:lod.gpu_enabled"))
                            )
                            .build()
                    )
            );

        LOGGER.info("[Renderium] 已注册 GPU LOD 选项页面 (5 个选项)");
    }

    // ==================== 辅助枚举类型定义 ====================

    /**
     * 超分辨率技术枚举
     *
     * <p>支持的超分辨率算法列表：
     * <ul>
     *   <li>{@link #NONE} - 不使用超分辨率</li>
     *   <li>{@link #DLSS} - NVIDIA Deep Learning Super Sampling</li>
     *   <li>{@link #FSR} - AMD FidelityFX Super Resolution</li>
     *   <li>{@link #NIS} - NVIDIA Image Scaler</li>
     *   <li>{@link #XeSS} - Intel Xe Super Sampling（未来支持）</li>
     * </ul>
     */
    public enum SuperResolutionTechnique {
        /** 不使用超分辨率 */
        NONE,
        /** NVIDIA Deep Learning Super Sampling (RTX GPU) */
        DLSS,
        /** AMD FidelityFX Super Resolution (通用) */
        FSR,
        /** NVIDIA Image Scaling (通用) */
        NIS,
        /** Intel Xe Super Sampling (Arc GPU, 未来) */
        XeSS
    }

    /**
     * 质量预设枚举
     *
     * <p>从性能优先到画质优先的质量级别：
     * PERFORMANCE → BALANCED → QUALITY → ULTRA
     */
    public enum QualityPreset {
        /** 性能优先（最低画质，最高帧率） */
        PERFORMANCE,
        /** 平衡模式（默认推荐） */
        BALANCED,
        /** 画质优先（较高画质） */
        QUALITY,
        /** 极致画质（最高画质，最低帧率） */
        ULTRA
    }

    /**
     * 帧生成模式枚举（UI 显示用）
     *
     * <p>注意：此枚举用于 UI 选项显示，
     * 实际的帧生成模式由 {@link com.renderium.framegen.FrameGenMode} 管理。
     */
    public enum FrameGenModeOption {
        /** 不使用帧生成 */
        NONE,
        /** NVIDIA DLSS Frame Generation (RTX 40+) */
        DLSS_FG,
        /** 通用帧生成方案 (FG-X) */
        FG_X
    }

    /**
     * 帧倍增因子枚举
     *
     * <p>每渲染 1 帧，实际显示的帧数：
     * <ul>
     *   <li>X2 = 渲染 1 帧，显示 2 帧（默认）</li>
     *   <li>X3 = 渲染 1 帧，显示 3 帧</li>
     *   <li>X4 = 渲染 1 帧，显示 4 帧（实验性）</li>
     * </ul>
     */
    public enum FrameMultiplier {
        /** 2 倍帧率 */
        X2,
        /** 3 倍帧率 */
        X3,
        /** 4 倍帧率（实验性，可能影响画质） */
        X4
    }

    /**
     * 帧生成质量枚举
     *
     * <p>帧生成的插值/预测质量级别：
     * BALANCED → QUALITY → ULTRA_QUALITY
     */
    public enum FrameGenQuality {
        /** 平衡模式（默认，低延迟） */
        BALANCED,
        /** 高质量模式（更好插值） */
        QUALITY,
        /** 极致高质量模式（最佳插值，更高延迟） */
        ULTRA_QUALITY
    }

    /**
     * Reflex 延迟模式枚举（UI 显示用）
     *
     * <p>注意：此枚举用于 UI 选项显示，
     * 实际的 Reflex 模式由 {@link ReflexMode} 管理。
     */
    public enum LatencyModeOption {
        /** 关闭 Reflex（不降低延迟） */
        OFF,
        /** 低延迟模式（适度降低延迟） */
        LOW,
        /** 超低延迟模式（最大程度降低延迟） */
        ULTRA
    }
}
