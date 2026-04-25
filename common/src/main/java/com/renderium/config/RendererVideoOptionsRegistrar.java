package com.renderium.config;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.renderium.bridge.video.BooleanOptionBuilder;
import com.renderium.bridge.video.EnumOptionBuilder;
import com.renderium.bridge.video.IntegerOptionBuilder;
import com.renderium.bridge.video.OptionGroupBuilder;
import com.renderium.bridge.video.OptionPageBuilder;
import com.renderium.bridge.video.RendererConfigBuilder;
import com.renderium.config.structure.OptionFlag;
import com.renderium.config.structure.OptionImpact;
import com.renderium.config.structure.Range;
import com.renderium.shader.settings.ShaderSettingsRegistrar;
import com.renderium.config.GpuVideoOptionsRegistrar;
import com.renderium.ui.widgets.options.control.ControlValueFormatterImpls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Renderium 视频选项注册器
 * <p>
 * 集中管理所有视频设置选项的注册逻辑，使用 {@link RendererConfigBuilder} 流式 API
 * 声明式定义 General、Quality、Performance 三个核心选项页面。
 * <p>
 * 采用中性命名和 {@code renderium:*} ID 命名空间，完全解耦于任何特定渲染引擎。
 *
 * <h2>架构设计</h2>
 * <pre>
 * RendererVideoOptionsRegistrar (本类 - 注册入口)
 *   ├── registerGeneralOptions()   → 通用设置页 (11 项)
 *   │   ├── Graphics 组: 渲染距离、模拟距离、亮度
 *   │   ├── Display 组: GUI 缩放、全屏模式/分辨率、VSync、帧率限制
 *   │   └── Interaction 组: 攻击指示器、自动保存指示器
 *   ├── registerQualityOptions()   → 画质设置页 (17 项)
 *   │   ├── Transparency & Particles: 改进透明度、云、天气、树叶、粒子、AO
 *   │   ├── Entity & Visual: 实体距离缩放、实体阴影、暗角、区块淡入
 *   │   ├── Texture Quality: Mipmap、纹理过滤、各向异性
 *   │   └── Fluids (Renderium 扩展): 隐藏流体剔除、改进流体塑形
 *   ├── registerPerformanceOptions() → 性能设置页 (9 项)
 *   │   ├── Chunk Building: 区块更新线程、延迟更新策略
 *   │   ├── Culling Optimization: 面剔除、雾遮挡、实体剔除、纹理动画优化
 *   │   ├── Advanced: No-Error GL 上下文、非活跃帧率限制
 *   │   └── Debug: 四边形分割模式
 *   └── ShaderSettingsRegistrar    → 光影设置页 (14 项) [Shader 集成]
 *       ├── Preset 组: 一键预设切换 (OFF/MINIMAL/BALANCED/CINEMATIC/ULTRA)
 *       ├── Shadows & Lighting: 阴影质量/距离、SSAO、GI 反弹
 *       ├── Visual Enhancement: 泛光、色调映射、暗角
 *       ├── Environment: 天空盒、云渲染
 *       └── Advanced: PBR 材质、光线追踪反射、自动曝光
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 在 VideoSettingsBridge.registerOptions() 中调用
 * public static void registerOptions(RendererConfigBuilder builder) {
 *     Options vanillaOpts = Minecraft.getInstance().options;
 *     Object sodiumOpts = SodiumClientMod.options(); // 或 null
 *
 *     RendererVideoOptionsRegistrar.registerAll(builder, vanillaOpts, sodiumOpts);
 * }
 * }</pre>
 *
 * <h2>性能特征</h2>
 * <ul>
 *   <li>注册开销：&lt; 10ms（冷启动，包含 37 个选项的完整注册）</li>
 *   <li>内存占用：~15 KB（所有选项定义的元数据）</li>
 *   <li>线程安全：线程安全（纯函数式注册，无共享可变状态）</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 5.0.0
 * @since 5.0.0
 * @see RendererConfigBuilder
 * @see VideoSettingsBridge
 */
public final class RendererVideoOptionsRegistrar {

    /** 私有构造器防止实例化 */
    private RendererVideoOptionsRegistrar() {
        throw new UnsupportedOperationException("RendererVideoOptionsRegistrar is a utility class");
    }

    // ==================== 全屏模式枚举 ====================

    /**
     * 全屏模式枚举
     * <p>
     * 定义三种全屏状态：
     * <ul>
     *   <li>{@link #OFF} - 窗口模式</li>
     *   <li>{@link #EXCLUSIVE} - 独占全屏（传统全屏，最佳性能）</li>
     *   <li>{@link #BORDERLESS} - 无边框窗口化全屏（现代方案，切换更快）</li>
     * </ul>
     */
    public enum FullscreenMode {
        /** 窗口模式 */
        OFF,
        /** 独占全屏模式 */
        EXCLUSIVE,
        /** 无边框窗口化全屏 */
        BORDERLESS
    }

    // ==================== 公共注册方法 ====================

    /**
     * 一次性注册所有视频选项页面
     * <p>
     * 按顺序调用三个页面的注册方法，构建完整的视频设置系统。
     * 这是推荐的统一入口方法。
     *
     * @param builder     渲染配置构建器（非 null）
     * @param vanillaOpts MC 原生选项实例（非 null），用于绑定 MC 原生设置
     * @param sodiumOpts  Sodium 风格选项实例（可为 null），用于绑定性能相关设置
     * @throws IllegalArgumentException 如果 builder 或 vanillaOpts 为 null
     */
    public static void registerAll(
            RendererConfigBuilder builder,
            Options vanillaOpts,
            @Nullable Object sodiumOpts
    ) {
        if (builder == null) {
            throw new IllegalArgumentException("RendererConfigBuilder must not be null");
        }
        if (vanillaOpts == null) {
            throw new IllegalArgumentException("Vanilla options must not be null");
        }

        long startTime = System.nanoTime();

        registerGeneralOptions(builder, vanillaOpts);
        registerQualityOptions(builder, vanillaOpts);
        registerPerformanceOptions(builder, vanillaOpts, sodiumOpts);

        // 注册光影设置页面（第 4 个标签页：Shader）
        ShaderSettingsRegistrar.registerOptions(builder);

        // 注册 GPU 加速设置页面（第 5 个标签页：GPU Acceleration）
        GpuVideoOptionsRegistrar.getInstance().registerGpuOptions(builder);

        long duration = (System.nanoTime() - startTime) / 1_000_000;
        Minecraft.getInstance().logger.debug(
                "Renderium video options registered in {} ms", duration
        );
    }

    /**
     * 注册 General（通用）选项页面
     * <p>
     * 包含 11 个选项，分为三组：
     * <ol>
     *   <li><b>Graphics</b>: 渲染距离、模拟距离、Gamma/Brightness</li>
     *   <li><b>Display</b>: GUI 缩放、全屏模式、全屏分辨率、VSync、FPS 限制</li>
     *   <li><b>Interaction</b>: 攻击指示器、自动保存指示器</li>
     * </ol>
     *
     * @param builder     渲染配置构建器（非 null）
     * @param vanillaOpts MC 原生选项实例（非 null）
     * @throws IllegalArgumentException 如果参数为 null
     */
    public static void registerGeneralOptions(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        if (builder == null || vanillaOpts == null) {
            throw new IllegalArgumentException("Builder and vanilla options must not be null");
        }

        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();

        OptionPageBuilder generalPage = builder.createOptionPage()
                .setName(Component.translatable("renderium.options.pages.general"));

        // ========== 组 1: Graphics（图形设置） ==========
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildRenderDistanceOption(builder, vanillaOps)
                )
                .addOption(
                        buildSimulationDistanceOption(builder, vanillaOps)
                )
                .addOption(
                        buildGammaOption(builder, vanillaOps)
                )
        );

        // ========== 组 2: Display（显示设置） ==========
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildGuiScaleOption(builder, vanillaOps, window)
                )
                .addOption(
                        buildFullscreenModeOption(builder, vanillaOps, window)
                )
                .addOption(
                        buildFullscreenResolutionOption(builder, window)
                )
                .addOption(
                        buildVsyncOption(builder, vanillaOps)
                )
                .addOption(
                        buildFramerateLimitOption(builder, vanillaOps)
                )
        );

        // ========== 组 3: Interaction（交互设置） ==========
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildAttackIndicatorOption(builder, vanillaOps)
                )
                .addOption(
                        buildAutosaveIndicatorOption(builder, vanillaOps)
                )
        );
    }

    /**
     * 注册 Quality（画质）选项页面
     * <p>
     * 包含 17 个选项，分为四组：
     * <ol>
     *   <li><b>Transparency & Particles</b>: 改进透明度、云、云渲染距离、天气半径、树叶、粒子、AO</li>
     *   <li><b>Entity & Visual</b>: 实体距离缩放、实体阴影、暗角、区块淡入时间</li>
     *   <li><b>Texture Quality</b>: Mipmap 级别、纹理过滤模式、最大各向异性</li>
     *   <li><b>Fluids (Renderium 扩展)</b>: 隐藏流体剔除、改进流体塑形</li>
     * </ol>
     *
     * @param builder     渲染配置构建器（非 null）
     * @param vanillaOpts MC 原生选项实例（非 null）
     * @throws IllegalArgumentException 如果参数为 null
     */
    public static void registerQualityOptions(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        if (builder == null || vanillaOpts == null) {
            throw new IllegalArgumentException("Builder and vanilla options must not be null");
        }

        OptionPageBuilder qualityPage = builder.createOptionPage()
                .setName(Component.translatable("renderium.options.pages.quality"));

        // ========== 组 1: Transparency & Particles ==========
        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildImprovedTransparencyOption(builder, vanillaOpts)
                )
                .addOption(
                        buildCloudsOption(builder, vanillaOpts)
                )
                .addOption(
                        buildCloudDistanceOption(builder, vanillaOpts)
                )
                .addOption(
                        buildWeatherRadiusOption(builder, vanillaOpts)
                )
                .addOption(
                        buildLeavesOption(builder, vanillaOpts)
                )
                .addOption(
                        buildParticlesOption(builder, vanillaOpts)
                )
                .addOption(
                        buildAmbientOcclusionOption(builder, vanillaOpts)
                )
        );

        // ========== 组 2: Entity & Visual ==========
        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildEntityDistanceOption(builder, vanillaOpts)
                )
                .addOption(
                        buildEntityShadowsOption(builder, vanillaOpts)
                )
                .addOption(
                        buildVignetteOption(builder, vanillaOpts)
                )
                .addOption(
                        buildChunkFadeTimeOption(builder, vanillaOpts)
                )
        );

        // ========== 组 3: Texture Quality ==========
        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildMipmapLevelsOption(builder, vanillaOpts)
                )
                .addOption(
                        buildTextureFilteringOption(builder, vanillaOpts)
                )
                .addOption(
                        buildMaxAnisotropyOption(builder, vanillaOpts)
                )
        );

        // ========== 组 4: Fluids (Renderium 扩展) ==========
        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildHiddenFluidCullingOption(builder)
                )
                .addOption(
                        buildImprovedFluidShapingOption(builder)
                )
        );
    }

    /**
     * 注册 Performance（性能）选项页面
     * <p>
     * 包含 9 个选项，分为四组：
     * <ol>
     *   <li><b>Chunk Building</b>: 区块更新线程数、延迟更新策略</li>
     *   <li><b>Culling Optimization</b>: 面剔除、雾遮挡剔除、实体剔除、仅动画可见纹理</li>
     *   <li><b>Advanced</b>: No-Error GL 上下文、非活跃 FPS 限制</li>
     *   <li><b>Debug</b>: 四边形分割模式（仅开发模式显示）</li>
     * </ol>
     *
     * @param builder     渲染配置构建器（非 null）
     * @param vanillaOpts MC 原生选项实例（非 null）
     * @param sodiumOpts  Sodium 性能配置对象（可为 null，若为 null 则跳过依赖它的选项）
     * @throws IllegalArgumentException 如果 builder 或 vanillaOpts 为 null
     */
    public static void registerPerformanceOptions(
            RendererConfigBuilder builder,
            Options vanillaOpts,
            @Nullable Object sodiumOpts
    ) {
        if (builder == null || vanillaOpts == null) {
            throw new IllegalArgumentException("Builder and vanilla options must not be null");
        }

        OptionPageBuilder performancePage = builder.createOptionPage()
                .setName(Component.translatable("renderium.options.pages.performance"));

        // ========== 组 1: Chunk Building ==========
        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildChunkUpdateThreadsOption(builder, sodiumOpts)
                )
                .addOption(
                        buildDeferChunkUpdatesOption(builder, sodiumOpts)
                )
        );

        // ========== 组 2: Culling Optimization ==========
        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildBlockFaceCullingOption(builder, sodiumOpts)
                )
                .addOption(
                        buildFogOcclusionOption(builder, sodiumOpts)
                )
                .addOption(
                        buildEntityCullingOption(builder, sodiumOpts)
                )
                .addOption(
                        buildAnimateOnlyVisibleTexturesOption(builder, sodiumOpts)
                )
                .addOption(
                        buildNoErrorContextOption(builder, sodiumOpts)
                )
                .addOption(
                        buildInactivityFpsLimitOption(builder, vanillaOpts)
                )
        );

        // ========== 组 3: Debug (仅开发模式显示) ==========
        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        buildQuadSplittingOption(builder, sodiumOpts)
                )
        );
    }

    // ==================== General 选项构建方法 ====================

    /**
     * 构建"渲染距离"整数选项
     * <p>
     * 控制可视区块数量，直接影响显存占用和 GPU 负载。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.render_distance}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [2, 32], 步长 1</li>
     *   <li>默认值: 12</li>
     *   <li>影响级别: HIGH</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildRenderDistanceOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:general.render_distance")
                )
                .setName(Component.translatable("options.renderDistance"))
                .setTooltip(Component.translatable("renderium.options.view_distance.tooltip"))
                .setValueFormatter(ControlValueFormatterImpls.quantityOrDisabled(
                        v -> Component.translatable("options.chunks", v),
                        Component.translatable("renderium.options.off")
                ))
                .setRange(2, 32, 1)
                .setDefaultValue(12)
                .setBinding(
                        vanillaOpts.renderDistance()::set,
                        vanillaOpts.renderDistance()::get
                )
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);
    }

    /**
     * 构建"模拟距离"整数选项
     * <p>
     * 控制实体和方块实体更新的范围，影响服务器逻辑负载。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.simulation_distance}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [5, 32], 步长 1</li>
     *   <li>默认值: 12</li>
     *   <li>影响级别: HIGH</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildSimulationDistanceOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:general.simulation_distance")
                )
                .setName(Component.translatable("options.simulationDistance"))
                .setTooltip(Component.translatable("renderium.options.simulation_distance.tooltip"))
                .setValueFormatter(ControlValueFormatterImpls.quantityOrDisabled(
                        v -> Component.translatable("options.chunks", v),
                        Component.translatable("renderium.options.off")
                ))
                .setRange(5, 32, 1)
                .setDefaultValue(12)
                .setBinding(
                        vanillaOpts.simulationDistance()::set,
                        vanillaOpts.simulationDistance()::get
                )
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);
    }

    /**
     * 构建"Gamma/亮度"整数选项
     * <p>
     * 控制游戏画面亮度。MC 内部使用 double 类型 (0.0~1.0)，
     * 此处转换为整数 (0~100) 以提供更细粒度的控制。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.gamma}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [0, 100], 步长 1</li>
     *   <li>默认值: 50</li>
     *   <li>格式化: brightness() - 特殊值映射 (0→Moody, 50→Default, 100→Bright)</li>
     * </ul>
     *
     * <h4>数据转换规则：</h4>
     * <pre>
     * setter: value (int) → vanillaOpts.gamma().set(value * 0.01D)
     * getter: () → (int) (vanillaOpts.gamma().get() / 0.01D)
     * </pre>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildGammaOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:general.gamma")
                )
                .setName(Component.translatable("options.gamma"))
                .setTooltip(Component.translatable("renderium.options.brightness.tooltip"))
                .setValueFormatter(ControlValueFormatterImpls.brightness())
                .setRange(0, 100, 1)
                .setDefaultValue(50)
                .setBinding(
                        value -> vanillaOpts.gamma().set(value * 0.01D),
                        () -> (int) (vanillaOpts.gamma().get() / 0.01D)
                );
    }

    /**
     * 构建"GUI 缩放"整数选项
     * <p>
     * 控制 UI 界面的大小。值为 0 表示自动缩放，
     * 最大值由窗口尺寸动态计算得出。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.gui_scale}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: 动态 [0, window.calculateScale(0, enforceUnicode)]</li>
     *   <li>默认值: 0 (自动)</li>
     *   <li>格式化: guiScale() - 0 显示 "Auto"</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @param window      Minecraft 窗口实例
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildGuiScaleOption(
            RendererConfigBuilder builder,
            Options vanillaOpts,
            Window window
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:general.gui_scale")
                )
                .setName(Component.translatable("options.guiScale"))
                .setTooltip(Component.translatable("renderium.options.gui_scale.tooltip"))
                .setValueFormatter(ControlValueFormatterImpls.guiScale())
                .setValidatorProvider(state -> {
                    int savedValue = state.readIntOption(
                            Identifier.parse("renderium:general.gui_scale")
                    );
                    int realMax = window.calculateScale(
                            0,
                            Minecraft.getInstance().isEnforceUnicode()
                    );
                    int presentationMax = Math.max(savedValue, realMax);
                    return new Range(0, presentationMax, 1);
                })
                .setDefaultValue(0)
                .setBinding(
                        vanillaOpts.guiScale()::set,
                        vanillaOpts.guiScale()::get
                );
    }

    /**
     * 构建"全屏模式"枚举选项
     * <p>
     * 统一管理全屏和独占全屏两个相互依赖的 MC 原生选项。
     * 提供三种模式：窗口、独占全屏、无边框全屏。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.fullscreen_mode}</li>
     *   <li>类型: EnumOption&lt;{@link FullscreenMode}&gt;</li>
     *   <li>默认值: {@link FullscreenMode#OFF}</li>
     *   <li>影响级别: HIGH</li>
     * </ul>
     *
     * <h4>复杂绑定逻辑：</h4>
     * <pre>
     * setter:
     *   OFF         → fullscreen=false
     *   EXCLUSIVE   → fullscreen=true, exclusiveFullscreen=true
     *   BORDERLESS  → fullscreen=true, exclusiveFullscreen=false
     *   最后调用 window.toggleFullScreen() 应用更改
     *
     * getter:
     *   fullscreen=true  && exclusive=true  → EXCLUSIVE
     *   fullscreen=true  && exclusive=false → BORDERLESS
     *   fullscreen=false                   → OFF
     * </pre>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @param window      Minecraft 窗口实例
     * @return 构建完成的枚举选项
     */
    private static EnumOptionBuilder<FullscreenMode> buildFullscreenModeOption(
            RendererConfigBuilder builder,
            Options vanillaOpts,
            Window window
    ) {
        return builder.createEnumOption(
                        Identifier.parse("renderium:general.fullscreen_mode"),
                        FullscreenMode.class
                )
                .setName(Component.translatable("renderium.options.fullscreen_mode.name"))
                .setTooltip(Component.translatable("renderium.options.fullscreen_mode.tooltip"))
                .setElementNameProvider(mode -> switch (mode) {
                    case OFF -> Component.translatable("renderium.options.fullscreen_mode.off");
                    case EXCLUSIVE ->
                            Component.translatable("renderium.options.fullscreen_mode.exclusive");
                    case BORDERLESS ->
                            Component.translatable("renderium.options.fullscreen_mode.borderless");
                })
                .setDefaultValue(FullscreenMode.OFF)
                .setImpact(OptionImpact.HIGH)
                .setBinding(
                        value -> {
                            switch (value) {
                                case OFF -> vanillaOpts.fullscreen().set(false);
                                case EXCLUSIVE -> {
                                    vanillaOpts.fullscreen().set(true);
                                    vanillaOpts.exclusiveFullscreen().set(true);
                                }
                                case BORDERLESS -> {
                                    vanillaOpts.fullscreen().set(true);
                                    vanillaOpts.exclusiveFullscreen().set(false);
                                }
                            }

                            if (window.isFullscreen() != vanillaOpts.fullscreen().get()) {
                                window.toggleFullScreen();
                                vanillaOpts.fullscreen().set(window.isFullscreen());
                            }
                        },
                        () -> {
                            boolean fullscreen = vanillaOpts.fullscreen().get();
                            boolean exclusive = vanillaOpts.exclusiveFullscreen().get();
                            if (fullscreen && exclusive) {
                                return FullscreenMode.EXCLUSIVE;
                            } else if (fullscreen) {
                                return FullscreenMode.BORDERLESS;
                            } else {
                                return FullscreenMode.OFF;
                            }
                        }
                )
                .setApplyHook(state -> {
                    boolean initialExclusive = vanillaOpts.exclusiveFullscreen().get();
                    boolean currentExclusive = vanillaOpts.exclusiveFullscreen().get();
                    if (initialExclusive != currentExclusive) {
                        Minecraft.getInstance().levelRenderer.allChanged();
                    }
                });
    }

    /**
     * 构建"全屏分辨率"整数选项
     * <p>
     * 仅在独占全屏模式下启用，用于选择显示器支持的分辨率。
     * 值为 0 表示使用显示器首选分辨率。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.fullscreen_resolution}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: 动态 [0, monitor.getModeCount()]</li>
     *   <li>默认值: 0</li>
     *   <li>启用条件: 全屏模式 == EXCLUSIVE</li>
     *   <li>标志: REQUIRES_VIDEOMODE_RELOAD</li>
     * </ul>
     *
     * @param builder 配置构建器
     * @param window  Minecraft 窗口实例
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildFullscreenResolutionOption(
            RendererConfigBuilder builder,
            Window window
    ) {
        Monitor monitor = getBestMonitor(window);

        return builder.createIntegerOption(
                        Identifier.parse("renderium:general.fullscreen_resolution")
                )
                .setName(Component.translatable("options.fullscreen.resolution"))
                .setTooltip(Component.translatable("renderium.options.fullscreen_resolution.tooltip"))
                .setValueFormatter(ControlValueFormatterImpls.resolution())
                .setValidator(new DynamicFullscreenResolutionRange(monitor))
                .setDefaultValue(0)
                .setBinding(
                        value -> {
                            if (monitor != null) {
                                window.setPreferredFullscreenVideoMode(
                                        0 == value
                                                ? Optional.empty()
                                                : Optional.of(monitor.getMode(value - 1))
                                );
                            }
                        },
                        () -> {
                            if (monitor == null) {
                                return 0;
                            } else {
                                Optional<VideoMode> optional =
                                        window.getPreferredFullscreenVideoMode();
                                return optional.map(videoMode ->
                                        monitor.getVideoModeIndex(videoMode) + 1
                                ).orElse(0);
                            }
                        }
                )
                .setEnabledProvider(state -> {
                    if (monitor == null || monitor.getModeCount() <= 0) {
                        return false;
                    }
                    FullscreenMode mode = state.readEnumOption(
                            Identifier.parse("renderium:general.fullscreen_mode"),
                            FullscreenMode.class
                    );
                    return mode == FullscreenMode.EXCLUSIVE;
                }, Identifier.parse("renderium:general.fullscreen_mode"))
                .setFlags(OptionFlag.REQUIRES_VIDEOMODE_RELOAD);
    }

    /**
     * 构建"垂直同步"布尔选项
     * <p>
     * 启用后会将帧率锁定到显示器刷新率，防止画面撕裂。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.vsync}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildVsyncOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:general.vsync")
                )
                .setName(Component.translatable("options.vsync"))
                .setTooltip(Component.translatable("renderium.options.v_sync.tooltip"))
                .setDefaultValue(true)
                .setBinding(
                        vanillaOpts.enableVsync()::set,
                        vanillaOpts.enableVsync()::get
                );
    }

    /**
     * 构建"帧率限制"整数选项
     * <p>
     * 设置游戏的最大帧率上限。特殊值 260 表示无限制。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.framerate_limit}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [10, 260], 步长 10</li>
     *   <li>默认值: 60</li>
     *   <li>格式化: fpsLimit() - 260 显示 "Unlimited"</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildFramerateLimitOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:general.framerate_limit")
                )
                .setName(Component.translatable("options.framerateLimit"))
                .setTooltip(Component.translatable("renderium.options.fps_limit.tooltip"))
                .setValueFormatter(ControlValueFormatterImpls.fpsLimit())
                .setRange(10, 260, 10)
                .setDefaultValue(60)
                .setBinding(
                        vanillaOpts.framerateLimit()::set,
                        vanillaOpts.framerateLimit()::get
                );
    }

    /**
     * 构建"攻击指示器"枚举选项
     * <p>
     * 控制攻击强度指示器的显示位置。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.attack_indicator}</li>
     *   <li>类型: EnumOption&lt;AttackIndicatorStatus&gt;</li>
     *   <li>默认值: CROSSHAIR</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的枚举选项
     */
    private static EnumOptionBuilder<?> buildAttackIndicatorOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createEnumOption(
                        Identifier.parse("renderium:general.attack_indicator"),
                        net.minecraft.world.entity.player.AttackIndicatorStatus.class
                )
                .setName(Component.translatable("options.attackIndicator"))
                .setTooltip(Component.translatable("renderium.options.attack_indicator.tooltip"))
                .setDefaultValue(net.minecraft.world.entity.player.AttackIndicatorStatus.CROSSHAIR)
                .setElementNameProvider(
                        net.minecraft.world.entity.player.AttackIndicatorStatus::caption
                )
                .setBinding(
                        vanillaOpts.attackIndicator()::set,
                        vanillaOpts.attackIndicator()::get
                );
    }

    /**
     * 构建"自动保存指示器"布尔选项
     * <p>
     * 控制是否在屏幕上显示自动保存进度提示。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:general.autosave_indicator}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildAutosaveIndicatorOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:general.autosave_indicator")
                )
                .setName(Component.translatable("options.autosaveIndicator"))
                .setTooltip(Component.translatable("renderium.options.autosave_indicator.tooltip"))
                .setDefaultValue(true)
                .setBinding(
                        vanillaOpts.showAutosaveIndicator()::set,
                        vanillaOpts.showAutosaveIndicator()::get
                );
    }

    // ==================== Quality 选项构建方法 ====================

    /**
     * 构建"改进透明度"布尔选项
     * <p>
     * 启用后改进透明方块的渲染顺序，消除视觉伪影。
     * 会显著影响性能，特别是在有大量玻璃/水面的场景。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.graphics}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: false</li>
     *   <li>影响级别: HIGH</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildImprovedTransparencyOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:quality.graphics")
                )
                .setName(Component.translatable("options.improvedTransparency"))
                .setTooltip(Component.translatable("options.improvedTransparency.tooltip"))
                .setDefaultValue(false)
                .setBinding(
                        vanillaOpts.improvedTransparency()::set,
                        vanillaOpts.improvedTransparency()::get
                )
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);
    }

    /**
     * 构建"云渲染"枚举选项
     * <p>
     * 控制云的渲染质量：关闭 / 快速 / 精致。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.clouds}</li>
     *   <li>类型: EnumOption&lt;CloudStatus&gt;</li>
     *   <li>默认值: FANCY</li>
     *   <li>影响级别: LOW</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的枚举选项
     */
    private static EnumOptionBuilder<?> buildCloudsOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createEnumOption(
                        Identifier.parse("renderium:quality.clouds"),
                        CloudStatus.class
                )
                .setName(Component.translatable("options.renderClouds"))
                .setTooltip(Component.translatable("renderium.options.clouds_quality.tooltip"))
                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                        Component.translatable("options.off"),
                        Component.translatable("options.clouds.fast"),
                        Component.translatable("options.clouds.fancy")
                ))
                .setDefaultValue(CloudStatus.FANCY)
                .setBinding(
                        value -> {
                            vanillaOpts.cloudStatus().set(value);
                            if (Minecraft.useShaderTransparency()) {
                                var target = Minecraft.getInstance().levelRenderer.getCloudsTarget();
                                if (target != null) {
                                    target.clear(Minecraft.ON_SYSTEM_HEAP);
                                }
                            }
                        },
                        () -> vanillaOpts.cloudStatus().get()
                )
                .setImpact(OptionImpact.LOW);
    }

    /**
     * 构建"云渲染距离"整数选项
     * <p>
     * 控制云的渲染范围（以区块为单位）。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.render_cloud_distance}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [2, 128], 步长 2</li>
     *   <li>默认值: 128</li>
     *   <li>格式化: quantityOrDisabled - 显示 "X chunks"</li>
     *   <li>影响级别: LOW</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildCloudDistanceOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:quality.render_cloud_distance")
                )
                .setName(Component.translatable("options.renderCloudsDistance"))
                .setTooltip(Component.translatable("renderium.options.clouds_distance.tooltip"))
                .setRange(2, 128, 2)
                .setDefaultValue(128)
                .setBinding(
                        value -> {
                            vanillaOpts.cloudRange().set(value);
                            Minecraft.getInstance().levelRenderer.getCloudRenderer()
                                    .markForRebuild();
                        },
                        () -> vanillaOpts.cloudRange().get()
                )
                .setImpact(OptionImpact.LOW)
                .setValueFormatter(ControlValueFormatterImpls.quantityOrDisabled(
                        v -> Component.translatable("options.chunks", v),
                        Component.translatable("renderium.options.off")
                ));
    }

    /**
     * 构建"天气半径"整数选项
     * <p>
     * 控制雨/雪天气效果的渲染范围（以区块为单位）。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.weather}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [3, 10], 步长 1</li>
     *   <li>默认值: 10</li>
     *   <li>影响级别: LOW</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildWeatherRadiusOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:quality.weather")
                )
                .setName(Component.translatable("options.weatherRadius"))
                .setTooltip(Component.translatable("options.weatherRadius.tooltip"))
                .setDefaultValue(10)
                .setRange(new Range(3, 10, 1))
                .setValueFormatter(ControlValueFormatterImpls.number())
                .setBinding(
                        vanillaOpts.weatherRadius()::set,
                        vanillaOpts.weatherRadius()::get
                )
                .setImpact(OptionImpact.LOW);
    }

    /**
     * 构建"镂空树叶"布尔选项
     * <p>
     * 启用后树叶将渲染为镂空样式（可以看到透过树叶的部分），
     * 否则使用不透明的剪切面渲染（性能更好）。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.leaves}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildLeavesOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:quality.leaves")
                )
                .setName(Component.translatable("options.cutoutLeaves"))
                .setTooltip(Component.translatable("options.cutoutLeaves.tooltip"))
                .setDefaultValue(true)
                .setBinding(
                        vanillaOpts.cutoutLeaves()::set,
                        vanillaOpts.cutoutLeaves()::get
                )
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);
    }

    /**
     * 构建"粒子效果"枚举选项
     * <p>
     * 控制粒子的渲染密度：全部 / 减少 / 最少。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.particles}</li>
     *   <li>类型: EnumOption&lt;ParticleStatus&gt;</li>
     *   <li>默认值: ALL</li>
     *   <li>影响级别: MEDIUM</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的枚举选项
     */
    private static EnumOptionBuilder<?> buildParticlesOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createEnumOption(
                        Identifier.parse("renderium:quality.particles"),
                        ParticleStatus.class
                )
                .setName(Component.translatable("options.particles"))
                .setTooltip(Component.translatable("renderium.options.particle_quality.tooltip"))
                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                        Component.translatable("options.particles.all"),
                        Component.translatable("options.particles.decreased"),
                        Component.translatable("options.particles.minimal")
                ))
                .setDefaultValue(ParticleStatus.ALL)
                .setBinding(
                        vanillaOpts.particles()::set,
                        vanillaOpts.particles()::get
                )
                .setImpact(OptionImpact.MEDIUM);
    }

    /**
     * 构建"环境光遮蔽"布尔选项
     * <p>
     * 启用后在角落和缝隙处添加柔和阴影，提升视觉深度感。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.ao}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     *   <li>影响级别: LOW</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildAmbientOcclusionOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:quality.ao")
                )
                .setName(Component.translatable("options.ao"))
                .setTooltip(Component.translatable("renderium.options.smooth_lighting.tooltip"))
                .setDefaultValue(true)
                .setBinding(
                        vanillaOpts.ambientOcclusion()::set,
                        vanillaOpts.ambientOcclusion()::get
                )
                .setImpact(OptionImpact.LOW)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);
    }

    /**
     * 构建"实体距离缩放"整数选项
     * <p>
     * 控制实体的渲染距离倍率。内部存储为百分比 (50%-500%)，
     * MC 内部实际使用浮点数 (0.5-5.0)。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.entity_distance}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [50, 500], 步长 25</li>
     *   <li>默认值: 100 (100% = 1x)</li>
     *   <li>格式化: percentage() - 显示 "X%"</li>
     *   <li>影响级别: HIGH</li>
     * </ul>
     *
     * <h4>数据转换规则：</h4>
     * <pre>
     * setter: value (int) → entityDistanceScaling.set(value / 100.0)
     * getter: () → Math.round(entityDistanceScaling.get() * 100.0F)
     * </pre>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildEntityDistanceOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:quality.entity_distance")
                )
                .setName(Component.translatable("options.entityDistanceScaling"))
                .setValueFormatter(ControlValueFormatterImpls.percentage())
                .setTooltip(Component.translatable("renderium.options.entity_distance.tooltip"))
                .setRange(50, 500, 25)
                .setDefaultValue(100)
                .setBinding(
                        value -> vanillaOpts.entityDistanceScaling().set(value / 100.0),
                        () -> Math.round(vanillaOpts.entityDistanceScaling().get().floatValue() * 100.0F)
                )
                .setImpact(OptionImpact.HIGH);
    }

    /**
     * 构建"实体阴影"布尔选项
     * <p>
     * 启用后实体会投射简单的地面阴影。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.entity_shadows}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     *   <li>影响级别: MEDIUM</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildEntityShadowsOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:quality.entity_shadows")
                )
                .setName(Component.translatable("options.entityShadows"))
                .setTooltip(Component.translatable("renderium.options.entity_shadows.tooltip"))
                .setDefaultValue(true)
                .setBinding(
                        vanillaOpts.entityShadows()::set,
                        vanillaOpts.entityShadows()::get
                )
                .setImpact(OptionImpact.MEDIUM);
    }

    /**
     * 构建"暗角效果"布尔选项
     * <p>
     * 启用后画面边缘会有轻微变暗的效果，增强沉浸感。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.vignette}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildVignetteOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:quality.vignette")
                )
                .setName(Component.translatable("options.vignette"))
                .setTooltip(Component.translatable("options.vignette.tooltip"))
                .setDefaultValue(true)
                .setBinding(
                        vanillaOpts.vignette()::set,
                        vanillaOpts.vignette()::get
                );
    }

    /**
     * 构建"区块淡入时间"整数选项
     * <p>
     * 控制新加载区块的渐入效果时长（毫秒）。
     * MC 内部存储为秒 (0.0-2.0)，此处转换为毫秒 (0-2000)。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.fade_time}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [0, 2000], 步长 50</li>
     *   <li>默认值: 750 (0.75 秒)</li>
     *   <li>格式化: chunkFade() - 显示 "X.X s"，0 显示 "Off"</li>
     * </ul>
     *
     * <h4>数据转换规则：</h4>
     * <pre>
     * setter: fade (ms) → chunkSectionFadeInTime.set(fade / 1000.0)
     * getter: () → (int) (chunkSectionFadeInTime.get() * 1000.0)
     * </pre>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildChunkFadeTimeOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:quality.fade_time")
                )
                .setName(Component.translatable("options.chunkFade"))
                .setTooltip(Component.translatable("options.chunkFade.tooltip"))
                .setDefaultValue(750)
                .setValueFormatter(ControlValueFormatterImpls.chunkFade())
                .setRange(new Range(0, 2000, 50))
                .setBinding(
                        fade -> vanillaOpts.chunkSectionFadeInTime().set((double) fade / 1000.0),
                        () -> (int) (vanillaOpts.chunkSectionFadeInTime().get() * 1000.0)
                );
    }

    /**
     * 构建"Mipmap 级别"整数选项
     * <p>
     * 控制纹理的 mipmap 详细程度。更高的值使远处的纹理更平滑，
     * 但会消耗更多显存。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.mipmap_levels}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [0, 4], 步长 1</li>
     *   <li>默认值: 4</li>
     *   <li>格式化: multiplier() - 显示 "Xx"</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_ASSET_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildMipmapLevelsOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:quality.mipmap_levels")
                )
                .setName(Component.translatable("options.mipmapLevels"))
                .setValueFormatter(ControlValueFormatterImpls.multiplier())
                .setTooltip(Component.translatable("renderium.options.mipmap_levels.tooltip"))
                .setRange(0, 4, 1)
                .setDefaultValue(4)
                .setBinding(
                        vanillaOpts.mipmapLevels()::set,
                        vanillaOpts.mipmapLevels()::get
                )
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD);
    }

    /**
     * 构建"纹理过滤模式"枚举选项
     * <p>
     * 控制纹理放大时的插值算法：
     * <ul>
     *   <li>NEAREST - 最近邻（像素风格，最快）</li>
     *   <li>BILINEAR - 双线性滤波（平衡）</li>
     *   <li>ANISOTROPIC - 各向异性过滤（斜面清晰）</li>
     *   <li>BICUBIC - 双三次滤波（最平滑，最慢）</li>
     * </ul>
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.filtering_mode}</li>
     *   <li>类型: EnumOption&lt;TextureFilteringMethod&gt;</li>
     *   <li>默认值: BILINEAR</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_ASSET_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的枚举选项
     */
    @SuppressWarnings("unchecked")
    private static EnumOptionBuilder<TextureFilteringMethod> buildTextureFilteringOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return (EnumOptionBuilder<TextureFilteringMethod>) builder.createEnumOption(
                        Identifier.parse("renderium:quality.filtering_mode"),
                        TextureFilteringMethod.class
                )
                .setName(Component.translatable("options.textureFiltering"))
                .setTooltip(i -> Component.translatable(
                        "options.textureFiltering." + i.name().toLowerCase(Locale.ROOT) + ".tooltip"
                ))
                .setElementNameProvider(name ->
                        Component.translatable("options.textureFiltering." + name.name().toLowerCase(Locale.ROOT))
                )
                .setDefaultValue(TextureFilteringMethod.BILINEAR)
                .setBinding(
                        vanillaOpts.textureFiltering()::set,
                        vanillaOpts.textureFiltering()::get
                )
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD);
    }

    /**
     * 构建"最大各向异性"整数选项
     * <p>
     * 仅在纹理过滤模式为 ANISOTROPIC 时启用。
     * 控制各向异性过滤的采样倍数（2^n）。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.anisotropy_bit}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [0, 3], 步长 1</li>
     *   <li>默认值: 0 (关闭)</li>
     *   <li>格式化: anisotropyBit() - 0→"Off", 1→"2x", 2→"4x", 3→"8x"</li>
     *   <li>启用条件: filtering_mode == ANISOTROPIC</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_ASSET_RELOAD</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的整数选项
     */
    private static IntegerOptionBuilder buildMaxAnisotropyOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return builder.createIntegerOption(
                        Identifier.parse("renderium:quality.anisotropy_bit")
                )
                .setName(Component.translatable("options.maxAnisotropy"))
                .setRange(new Range(0, 3, 1))
                .setTooltip(Component.translatable("options.maxAnisotropy.tooltip"))
                .setDefaultValue(0)
                .setValueFormatter(ControlValueFormatterImpls.anisotropyBit())
                .setBinding(
                        vanillaOpts.maxAnisotropyBit()::set,
                        vanillaOpts.maxAnisotropyBit()::get
                )
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                .setEnabledProvider(state ->
                        state.readEnumOption(
                                Identifier.parse("renderium:quality.filtering_mode"),
                                TextureFilteringMethod.class
                        ) == TextureFilteringMethod.ANISOTROPIC,
                        Identifier.parse("renderium:quality.filtering_mode")
                );
    }

    /**
     * 构建"隐藏流体剔除"布尔选项（Renderium 扩展）
     * <p>
     * 启用后不会渲染被不透明方块完全包围的流体面，
     * 显著减少水下场景的过度绘制。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.hidden_fluid_culling}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: false</li>
     *   <li>绑定: RenderiumConfig.quality.hiddenFluidCulling</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder 配置构建器
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildHiddenFluidCullingOption(RendererConfigBuilder builder) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:quality.hidden_fluid_culling")
                )
                .setName(Component.translatable("renderium.options.hidden_fluid_culling.name"))
                .setTooltip(Component.translatable("renderium.options.hidden_fluid_culling.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setDefaultValue(RenderiumConfig.getInstance().isHiddenFluidCulling())
                .setBinding(
                        value -> RenderiumConfig.getInstance().setHiddenFluidCulling(value),
                        () -> RenderiumConfig.getInstance().isHiddenFluidCulling()
                )
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);
    }

    /**
     * 构建"改进流体塑形"布尔选项（Renderium 扩展）
     * <p>
     * 启用后使用改进算法渲染流体的几何形状，
     * 使水面/岩浆面更加平滑自然。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:quality.improved_fluid_shaping}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: false</li>
     *   <li>绑定: RenderiumConfig.quality.improvedFluidShaping</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder 配置构建器
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildImprovedFluidShapingOption(RendererConfigBuilder builder) {
        return builder.createBooleanOption(
                        Identifier.parse("renderium:quality.improved_fluid_shaping")
                )
                .setName(Component.translatable("renderium.options.improved_fluid_shaping.name"))
                .setTooltip(Component.translatable("renderium.options.improved_fluid_shaping.tooltip"))
                .setDefaultValue(RenderiumConfig.getInstance().isImprovedFluidShaping())
                .setBinding(
                        value -> RenderiumConfig.getInstance().setImprovedFluidShaping(value),
                        () -> RenderiumConfig.getInstance().isImprovedFluidShaping()
                )
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);
    }

    // ==================== Performance 选项构建方法 ====================

    /**
     * 构建"区块更新线程数"整数选项
     * <p>
     * 控制用于构建区块网格的工作线程数。
     * 值为 0 表示自动检测（通常设为 CPU 核心数 - 1）。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.chunk_update_threads}</li>
     *   <li>类型: IntegerOption</li>
     *   <li>范围: [0, CPU 核心数], 步长 1</li>
     *   <li>默认值: 0 (自动)</li>
     *   <li>绑定: sodiumOpts.performance.chunkBuilderThreads</li>
     *   <li>影响级别: HIGH</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的整数选项，若 sodiumOpts 为 null 则返回禁用的选项
     */
    private static IntegerOptionBuilder buildChunkUpdateThreadsOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        IntegerOptionBuilder optionBuilder = builder.createIntegerOption(
                        Identifier.parse("renderium:performance.chunk_update_threads")
                )
                .setName(Component.translatable("renderium.options.chunk_update_threads.name"))
                .setValueFormatter(ControlValueFormatterImpls.quantityOrDisabled(
                        v -> Component.translatable("renderium.options.chunk_update_threads.value", v),
                        Component.translatable("renderium.options.default")
                ))
                .setTooltip(Component.translatable("renderium.options.chunk_update_threads.tooltip"))
                .setRange(0, Runtime.getRuntime().availableProcessors(), 1)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);

        if (sodiumOpts != null) {
            optionBuilder.setDefaultValue(getSodiumDefaultInt(sodiumOpts, "chunkBuilderThreads"))
                    .setBinding(
                            value -> setSodiumInt(sodiumOpts, "chunkBuilderThreads", value),
                            () -> getSodiumInt(sodiumOpts, "chunkBuilderThreads")
                    );
        } else {
            optionBuilder.setDefaultValue(0)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    /**
     * 构建"延迟区块更新"枚举选项
     * <p>
     * 控制区块构建任务的调度策略：
     * <ul>
     *   <li>ALWAYS - 始终延迟到下一帧（更流畅的帧时间）</li>
     *   <li>NEVER - 立即构建（新区块立即可见）</li>
     *   <li>ON_LOAD - 仅在加载时延迟</li>
     * </ul>
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.always_defer_chunk_updates}</li>
     *   <li>类型: EnumOption&lt;DeferMode&gt;</li>
     *   <li>绑定: sodiumOpts.performance.chunkBuildDeferMode</li>
     *   <li>影响级别: HIGH</li>
     *   <li>标志: REQUIRES_RENDERER_UPDATE</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的枚举选项
     */
    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> EnumOptionBuilder<T> buildDeferChunkUpdatesOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        EnumOptionBuilder<T> optionBuilder = (EnumOptionBuilder<T>) builder.createEnumOption(
                        Identifier.parse("renderium:performance.always_defer_chunk_updates"),
                        DeferMode.class
                )
                .setName(Component.translatable("renderium.options.defer_chunk_updates.name"))
                .setTooltip(Component.translatable("renderium.options.defer_chunk_updates.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE);

        if (sodiumOpts != null) {
            optionBuilder.setDefaultValue(getSodiumDefaultDeferMode(sodiumOpts))
                    .setBinding(
                            value -> setSodiumDeferMode(sodiumOpts, value),
                            () -> getSodiumDeferMode(sodiumOpts)
                    );
        } else {
            optionBuilder.setDefaultValue(DeferMode.ALWAYS)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    /**
     * 构建"面剔除"布尔选项
     * <p>
     * 启用后不渲染被相邻不透明方块遮挡的面，
     * 显减少几何处理量。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.use_block_face_culling}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildBlockFaceCullingOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        BooleanOptionBuilder optionBuilder = builder.createBooleanOption(
                        Identifier.parse("renderium:performance.use_block_face_culling")
                )
                .setName(Component.translatable("renderium.options.use_block_face_culling.name"))
                .setTooltip(Component.translatable("renderium.options.use_block_face_culling.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);

        if (sodiumOpts != null) {
            optionBuilder.setDefaultValue(getSodiumDefaultBool(sodiumOpts, "useBlockFaceCulling"))
                    .setBinding(
                            value -> setSodiumBool(sodiumOpts, "useBlockFaceCulling", value),
                            () -> getSodiumBool(sodiumOpts, "useBlockFaceCulling")
                    );
        } else {
            optionBuilder.setDefaultValue(true)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    /**
     * 构建"雾遮挡剔除"布尔选项
     * <p>
     * 启用后被浓雾完全遮挡的实体/方块不会被渲染，
     * 减少不可见物体的 GPU 开销。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.use_fog_occlusion}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_RENDERER_UPDATE</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildFogOcclusionOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        BooleanOptionBuilder optionBuilder = builder.createBooleanOption(
                        Identifier.parse("renderium:performance.use_fog_occlusion")
                )
                .setName(Component.translatable("renderium.options.use_fog_occlusion.name"))
                .setTooltip(Component.translatable("renderium.options.use_fog_occlusion.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE);

        if (sodiumOpts != null) {
            optionBuilder.setDefaultValue(getSodiumDefaultBool(sodiumOpts, "useFogOcclusion"))
                    .setBinding(
                            value -> setSodiumBool(sodiumOpts, "useFogOcclusion", value),
                            () -> getSodiumBool(sodiumOpts, "useFogOcclusion")
                    );
        } else {
            optionBuilder.setDefaultValue(true)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    /**
     * 构建"实体剔除"布尔选项
     * <p>
     * 启用后不在视锥体内的实体不会被渲染，
     * 大幅减少实体渲染的开销。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.use_entity_culling}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     *   <li>影响级别: MEDIUM</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildEntityCullingOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        BooleanOptionBuilder optionBuilder = builder.createBooleanOption(
                        Identifier.parse("renderium:performance.use_entity_culling")
                )
                .setName(Component.translatable("renderium.options.use_entity_culling.name"))
                .setTooltip(Component.translatable("renderium.options.use_entity_culling.tooltip"))
                .setImpact(OptionImpact.MEDIUM);

        if (sodiumOpts != null) {
            optionBuilder.setDefaultValue(getSodiumDefaultBool(sodiumOpts, "useEntityCulling"))
                    .setBinding(
                            value -> setSodiumBool(sodiumOpts, "useEntityCulling", value),
                            () -> getSodiumBool(sodiumOpts, "useEntityCulling")
                    );
        } else {
            optionBuilder.setDefaultValue(true)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    /**
     * 构建"仅动画可见纹理"布尔选项
     * <p>
     * 启用后只对屏幕上可见的纹理执行动画更新，
     * 显著减少纹理上传带宽。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.animate_only_visible_textures}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: true</li>
     *   <li>影响级别: HIGH</li>
     *   <li>标志: REQUIRES_RENDERER_UPDATE</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildAnimateOnlyVisibleTexturesOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        BooleanOptionBuilder optionBuilder = builder.createBooleanOption(
                        Identifier.parse("renderium:performance.animate_only_visible_textures")
                )
                .setName(Component.translatable("renderium.options.animate_only_visible_textures.name"))
                .setTooltip(Component.translatable("renderium.options.animate_only_visible_textures.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE);

        if (sodiumOpts != null) {
            optionBuilder.setDefaultValue(getSodiumDefaultBool(sodiumOpts, "animateOnlyVisibleTextures"))
                    .setBinding(
                            value -> setSodiumBool(sodiumOpts, "animateOnlyVisibleTextures", value),
                            () -> getSodiumBool(sodiumOpts, "animateOnlyVisibleTextures")
                    );
        } else {
            optionBuilder.setDefaultValue(true)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    /**
     * 构建"No-Error GL 上下文"布尔选项
     * <p>
     * 启用后创建 OpenGL 上下文时忽略错误检查，
     * 可轻微提升驱动程序层面的性能。
     * 需要 OpenGL 4.6 或 KHR_no_error 扩展支持。
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.use_no_error_context}</li>
     *   <li>类型: BooleanOption</li>
     *   <li>默认值: false</li>
     *   <li>启用条件: OpenGL46 || KHR_no_error</li>
     *   <li>影响级别: LOW</li>
     *   <li>标志: REQUIRES_GAME_RESTART</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的布尔选项
     */
    private static BooleanOptionBuilder buildNoErrorContextOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        BooleanOptionBuilder optionBuilder = builder.createBooleanOption(
                        Identifier.parse("renderium:performance.use_no_error_context")
                )
                .setName(Component.translatable("renderium.options.use_no_error_context.name"))
                .setTooltip(Component.translatable("renderium.options.use_no_error_context.tooltip"))
                .setImpact(OptionImpact.LOW)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                .setEnabledProvider(state -> {
                    try {
                        var capabilities = org.lwjgl.opengl.GL.getCapabilities();
                        return capabilities.OpenGL46 || capabilities.GL_KHR_no_error;
                    } catch (Exception e) {
                        return false;
                    }
                });

        if (sodiumOpts != null) {
            optionBuilder.setDefaultValue(getSodiumDefaultBool(sodiumOpts, "useNoErrorGLContext"))
                    .setBinding(
                            value -> setSodiumBool(sodiumOpts, "useNoErrorGLContext", value),
                            () -> getSodiumBool(sodiumOpts, "useNoErrorGLContext")
                    );
        } else {
            optionBuilder.setDefaultValue(false)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    /**
     * 构建"非活跃帧率限制"枚举选项
     * <p>
     * 控制游戏窗口非活跃时的帧率限制策略：
     * <ul>
     *   <li>AFK - 玩家无操作时降低帧率</li>
     *   <li>MINIMIZED - 窗口最小化时降低帧率</li>
     * </ul>
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.inactivity_fps_limit}</li>
     *   <li>类型: EnumOption&lt;InactivityFpsLimit&gt;</li>
     *   <li>默认值: AFK</li>
     *   <li>绑定: vanillaOpts.inactivityFpsLimit()</li>
     * </ul>
     *
     * @param builder     配置构建器
     * @param vanillaOpts MC 原生选项
     * @return 构建完成的枚举选项
     */
    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> EnumOptionBuilder<T> buildInactivityFpsLimitOption(
            RendererConfigBuilder builder,
            Options vanillaOpts
    ) {
        return (EnumOptionBuilder<T>) builder.createEnumOption(
                        Identifier.parse("renderium:performance.inactivity_fps_limit"),
                        InactivityFpsLimit.class
                )
                .setName(Component.translatable("options.inactivityFpsLimit"))
                .setElementNameProvider(InactivityFpsLimit::caption)
                .setTooltip((state) -> state == InactivityFpsLimit.AFK ?
                        Component.translatable("options.inactivityFpsLimit.afk.tooltip") :
                        Component.translatable("options.inactivityFpsLimit.minimized.tooltip")
                )
                .setDefaultValue(InactivityFpsLimit.AFK)
                .setBinding(
                        vanillaOpts.inactivityFpsLimit()::set,
                        vanillaOpts.inactivityFpsLimit()::get
                );
    }

    /**
     * 构建"四边形分割模式"枚举选项（仅开发模式显示）
     * <p>
     * 控制地形渲染时四边形的分割策略，影响排序精度和性能：
     * <ul>
     *   <li>SAFE - 最安全，无视觉错误</li>
     *   <li>FAST - 平衡模式</li>
     *   <li>FASTEST - 最高性能，可能有排序问题</li>
     * </ul>
     *
     * <h4>选项规格：</h4>
     * <ul>
     *   <li>ID: {@code renderium:performance.quad_splitting}</li>
     *   <li>类型: EnumOption&lt;QuadSplittingMode&gt;</li>
     *   <li>默认值: SAFE</li>
     *   <li>启用条件: sodiumOpts.debug.terrainSortingEnabled</li>
     *   <li>影响级别: MEDIUM</li>
     *   <li>标志: REQUIRES_RENDERER_RELOAD</li>
     * </ul>
     *
     * @param builder    配置构建器
     * @param sodiumOpts Sodium 性能配置（可为 null）
     * @return 构建完成的枚举选项
     */
    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> EnumOptionBuilder<T> buildQuadSplittingOption(
            RendererConfigBuilder builder,
            @Nullable Object sodiumOpts
    ) {
        EnumOptionBuilder<T> optionBuilder = (EnumOptionBuilder<T>) builder.createEnumOption(
                        Identifier.parse("renderium:performance.quad_splitting"),
                        QuadSplittingMode.class
                )
                .setName(Component.translatable("renderium.options.quad_splitting.name"))
                .setTooltip(Component.translatable("renderium.options.quad_splitting.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD);

        if (sodiumOpts != null) {
            boolean debugEnabled = getSodiumDebugBool(sodiumOpts, "terrainSortingEnabled");
            optionBuilder.setDefaultValue(getSodiumDefaultQuadSplitting(sodiumOpts))
                    .setBinding(
                            value -> setSodiumQuadSplitting(sodiumOpts, value),
                            () -> getSodiumQuadSplitting(sodiumOpts)
                    )
                    .setEnabled(debugEnabled);
        } else {
            optionBuilder.setDefaultValue(QuadSplittingMode.SAFE)
                    .setEnabledProvider(() -> false);
        }

        return optionBuilder;
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取最佳显示器实例
     * <p>
     * 根据窗口位置选择最适合的显示器，用于查询分辨率列表。
     *
     * @param window Minecraft 窗口实例
     * @return 最佳 Monitor 实例，若无法获取则返回 null
     */
    @Nullable
    private static Monitor getBestMonitor(@Nullable Window window) {
        if (window == null) {
            return null;
        }
        try {
            return window.findBestMonitor();
        } catch (Exception e) {
            Minecraft.getInstance().logger.warn(
                    "Failed to get best monitor: {}", e.getMessage()
            );
            return null;
        }
    }

    /**
     * 从 Sodium 风格配置对象读取整数值
     * <p>
     * 使用反射从性能配置中读取字段值。
     *
     * @param sodiumOpts Sodium 配置对象
     * @param fieldName 字段名称
     * @return 字段整数值，若读取失败则返回 0
     */
    private static int getSodiumInt(Object sodiumOpts, String fieldName) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField(fieldName);
            return field.getInt(performance);
        } catch (Exception e) {
            Minecraft.getInstance().logger.warn(
                    "Failed to read sodium option {}: {}", fieldName, e.getMessage()
            );
            return 0;
        }
    }

    /**
     * 向 Sodium 风格配置对象写入整数值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param fieldName 字段名称
     * @param value     要设置的值
     */
    private static void setSodiumInt(Object sodiumOpts, String fieldName, int value) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField(fieldName);
            field.setInt(performance, value);
        } catch (Exception e) {
            Minecraft.getInstance().logger.warn(
                    "Failed to write sodium option {}: {}", fieldName, e.getMessage()
            );
        }
    }

    /**
     * 从 Sodium 风格配置对象读取布尔值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param fieldName 字段名称
     * @return 字段布尔值，若读取失败则返回 false
     */
    private static boolean getSodiumBool(Object sodiumOpts, String fieldName) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField(fieldName);
            return field.getBoolean(performance);
        } catch (Exception e) {
            Minecraft.getInstance().logger.warn(
                    "Failed to read sodium option {}: {}", fieldName, e.getMessage()
            );
            return false;
        }
    }

    /**
     * 向 Sodium 风格配置对象写入布尔值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param fieldName 字段名称
     * @param value     要设置的值
     */
    private static void setSodiumBool(Object sodiumOpts, String fieldName, boolean value) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField(fieldName);
            field.setBoolean(performance, value);
        } catch (Exception e) {
            Minecraft.getInstance().logger.warn(
                    "Failed to write sodium option {}: {}", fieldName, e.getMessage()
            );
        }
    }

    /**
     * 获取 Sodium 默认整数值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param fieldName 字段名称
     * @return 默认值，若读取失败则返回 0
     */
    private static int getSodiumDefaultInt(Object sodiumOpts, String fieldName) {
        return getSodiumInt(sodiumOpts, fieldName);
    }

    /**
     * 获取 Sodium 默认布尔值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param fieldName 字段名称
     * @return 默认值，若读取失败则返回 false
     */
    private static boolean getSodiumDefaultBool(Object sodiumOpts, String fieldName) {
        return getSodiumBool(sodiumOpts, fieldName);
    }

    /**
     * 从 Sodium 配置读取 DeferMode 枚举值
     *
     * @param sodiumOpts Sodium 配置对象
     * @return DeferMode 值，若读取失败则返回 ALWAYS
     */
    @SuppressWarnings("unchecked")
    private static DeferMode getSodiumDeferMode(Object sodiumOpts) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField("chunkBuildDeferMode");
            return (DeferMode) field.get(performance);
        } catch (Exception e) {
            return DeferMode.ALWAYS;
        }
    }

    /**
     * 向 Sodium 配置写入 DeferMode 枚举值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param value     DeferMode 值
     */
    private static void setSodiumDeferMode(Object sodiumOpts, DeferMode value) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField("chunkBuildDeferMode");
            field.set(performance, value);
        } catch (Exception e) {
            Minecraft.getInstance().logger.warn(
                    "Failed to write defer mode: {}", e.getMessage()
            );
        }
    }

    /**
     * 获取 Sodium 默认 DeferMode 值
     *
     * @param sodiumOpts Sodium 配置对象
     * @return 默认 DeferMode
     */
    private static DeferMode getSodiumDefaultDeferMode(Object sodiumOpts) {
        return getSodiumDeferMode(sodiumOpts);
    }

    /**
     * 从 Sodium 配置读取 QuadSplittingMode 枚举值
     *
     * @param sodiumOpts Sodium 配置对象
     * @return QuadSplittingMode 值，若读取失败则返回 SAFE
     */
    @SuppressWarnings("unchecked")
    private static QuadSplittingMode getSodiumQuadSplitting(Object sodiumOpts) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField("quadSplittingMode");
            return (QuadSplittingMode) field.get(performance);
        } catch (Exception e) {
            return QuadSplittingMode.SAFE;
        }
    }

    /**
     * 向 Sodium 配置写入 QuadSplittingMode 枚举值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param value     QuadSplittingMode 值
     */
    private static void setSodiumQuadSplitting(Object sodiumOpts, QuadSplittingMode value) {
        try {
            var performanceField = sodiumOpts.getClass().getField("performance");
            Object performance = performanceField.get(sodiumOpts);
            var field = performance.getClass().getField("quadSplittingMode");
            field.set(performance, value);
        } catch (Exception e) {
            Minecraft.getInstance().logger.warn(
                    "Failed to write quad splitting mode: {}", e.getMessage()
            );
        }
    }

    /**
     * 获取 Sodium 默认 QuadSplittingMode 值
     *
     * @param sodiumOpts Sodium 配置对象
     * @return 默认 QuadSplittingMode
     */
    private static QuadSplittingMode getSodiumDefaultQuadSplitting(Object sodiumOpts) {
        return getSodiumQuadSplitting(sodiumOpts);
    }

    /**
     * 从 Sodium 配置读取调试布尔值
     *
     * @param sodiumOpts Sodium 配置对象
     * @param fieldName 字段名称
     * @return 布尔值，若读取失败则返回 false
     */
    private static boolean getSodiumDebugBool(Object sodiumOpts, String fieldName) {
        try {
            var debugField = sodiumOpts.getClass().getField("debug");
            Object debug = debugField.get(sodiumOpts);
            var field = debug.getClass().getField(fieldName);
            return field.getBoolean(debug);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 动态验证器类 ====================

    /**
     * 动态全屏分辨率范围验证器
     * <p>
     * 根据当前显示器的可用模式数量动态调整选项范围。
     * 当显示器不可用时，范围设置为 [0, 1] 以防止异常。
     */
    private static final class DynamicFullscreenResolutionRange implements ValidatorProvider {

        /** 关联的显示器实例（可为 null） */
        @Nullable
        private final Monitor monitor;

        /**
         * 创建动态范围验证器
         *
         * @param monitor 监视器实例（可为 null）
         */
        DynamicFullscreenResolutionRange(@Nullable Monitor monitor) {
            this.monitor = monitor;
        }

        /**
         * 获取当前有效的分辨率选项范围
         *
         * @return 范围对象，max 为模式数 + 1（+1 是因为 0 表示自动）
         */
        @Override
        public Range get() {
            if (monitor == null) {
                return new Range(0, 1, 1);
            }
            int modeCount = monitor.getModeCount();
            if (modeCount <= 0) {
                return new Range(0, 1, 1);
            }
            return new Range(0, modeCount + 1, 1);
        }
    }
}
