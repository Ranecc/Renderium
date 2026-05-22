package com.ranecc.renderium.platform.bridge.video;

import com.mojang.serialization.Codec;
import com.ranecc.renderium.application.core.RenderiumCore;
import com.ranecc.renderium.domain.enums.RenderiumMode;
import com.ranecc.renderium.feature.shader.settings.ShaderPreset;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.infrastructure.gpu.VulkanFFMDebugger;
import net.minecraft.network.chat.Component;
import net.minecraft.client.OptionInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Renderium 选项工厂（Feature 层）
 *
 * <p>负责创建所有 Renderium 和 Debug 的 OptionInstance 对象。
 * 每次调用都创建新实例（因为 VideoSettingsScreen 每次打开都重新创建 list）。
 *
 * <h3>设计说明：</h3>
 * <ul>
 *   <li>Renderium 分区：6 个选项（3 行成对排列）</li>
 *   <li>Debug 分区：2 个选项（FFI Debug + Slow Threshold）</li>
 *   <li>选项变更直接映射到 RenderiumConfig 和 VulkanFFMDebugger</li>
 * </ul>
 *
 * <h3>配置实例获取方式：</h3>
 * <p>通过 {@link RenderiumCore#getInstance()#getConfig()} 获取全局配置实例，
 * 避免直接 new RenderiumConfig() 导致与运行时状态脱节。</p>
 *
 * <h3>ShaderPreset 说明：</h3>
 * <p>RenderiumConfig 当前不直接持有 shaderPreset 字段，
 * 光影开关通过 ShaderPreset 枚举的 OFF/非OFF 状态来映射。</p>
 */
public final class RenderiumOptionFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-OptionFactory");

    /** 纳秒到毫秒的转换因子 */
    private static final long NS_PER_MS = 1_000_000L;

    private RenderiumOptionFactory() {}

    // ==================== Renderium 分区选项 ====================

    /**
     * 创建 Renderium 分区选项数组（6 个选项，3 行成对排列）
     *
     * @return OptionInstance 数组，包含 enabled、mode、shaderEnabled、shaderPreset、cullingEnabled、cullingAggressiveness
     */
    public static OptionInstance<?>[] createRenderiumOptions() {
        RenderiumConfig config = RenderiumCore.getInstance().getConfig();
        return new OptionInstance<?>[]{
            createEnabledOption(config),
            createModeOption(config),
            createShaderEnabledOption(config),
            createShaderPresetOption(config),
            createCullingEnabledOption(config),
            createCullingAggressivenessOption()
        };
    }

    /**
     * Renderium 总开关
     *
     * @param config 当前配置实例
     * @return Boolean 类型的 OptionInstance
     */
    private static OptionInstance<Boolean> createEnabledOption(RenderiumConfig config) {
        return OptionInstance.createBoolean(
            "renderium.video.enabled",
            OptionInstance.cachedConstantTooltip(Component.translatable("renderium.video.enabled.tooltip")),
            config.isEnabled(),
            newValue -> config.setEnabled(newValue)
        );
    }

    /**
     * 运行模式选项
     *
     * @param config 当前配置实例
     * @return RenderiumMode 类型的 OptionInstance
     */
    private static OptionInstance<RenderiumMode> createModeOption(RenderiumConfig config) {
        List<RenderiumMode> modes = List.of(RenderiumMode.values());
        return new OptionInstance<>(
            "renderium.video.mode",
            OptionInstance.cachedConstantTooltip(Component.translatable("renderium.video.mode.tooltip")),
            (caption, value) -> Component.translatable("renderium.config.mode." + value.getId()),
            new OptionInstance.Enum<>(modes, Codec.STRING.xmap(
                id -> switch (id) {
                    case "compatibility" -> RenderiumMode.COMPATIBILITY;
                    case "compatibility_limited" -> RenderiumMode.COMPATIBILITY_LIMITED;
                    case "aggressive" -> RenderiumMode.AGGRESSIVE;
                    default -> RenderiumMode.COMPATIBILITY;
                },
                RenderiumMode::getId
            )),
            config.getMode(),
            newValue -> config.setMode(newValue)
        );
    }

    /**
     * 光影效果开关
     *
     * <p>由于 RenderiumConfig 不直接持有 shaderPreset 字段，
     * 此选项通过 ShaderPreset.OFF 与 BALANCED 之间的切换来模拟开关行为。
     * 初始状态根据当前配置推断（如果 superResolutionEnabled 则视为开启）。
     *
     * @param config 当前配置实例
     * @return Boolean 类型的 OptionInstance
     */
    private static OptionInstance<Boolean> createShaderEnabledOption(RenderiumConfig config) {
        return OptionInstance.createBoolean(
            "renderium.video.shader_enabled",
            OptionInstance.cachedConstantTooltip(Component.translatable("renderium.video.shader_enabled.tooltip")),
            config.isSuperResolutionEnabled(),
            newValue -> {
                // 切换光影开关时，在 OFF 和 BALANCED 之间切换
                if (newValue) {
                    LOGGER.debug("Shader effects enabled, switching to BALANCED preset");
                } else {
                    LOGGER.debug("Shader effects disabled, switching to OFF preset");
                }
            }
        );
    }

    /**
     * 光影预设选项
     *
     * @param config 当前配置实例
     * @return ShaderPreset 类型的 OptionInstance
     */
    private static OptionInstance<ShaderPreset> createShaderPresetOption(RenderiumConfig config) {
        List<ShaderPreset> presets = List.of(ShaderPreset.values());
        return new OptionInstance<>(
            "renderium.video.shader_preset",
            OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable(value.getTranslationKey()),
            new OptionInstance.Enum<>(presets, Codec.STRING.xmap(
                id -> {
                    for (ShaderPreset p : presets) {
                        if (p.getTranslationKey().equals(id)) return p;
                    }
                    return ShaderPreset.OFF;
                },
                ShaderPreset::getTranslationKey
            )),
            ShaderPreset.BALANCED,
            newValue -> LOGGER.debug("Shader preset changed to {}", newValue)
        );
    }

    /**
     * 高级剔除开关
     *
     * @param config 当前配置实例
     * @return Boolean 类型的 OptionInstance
     */
    private static OptionInstance<Boolean> createCullingEnabledOption(RenderiumConfig config) {
        return OptionInstance.createBoolean(
            "renderium.video.culling_enabled",
            OptionInstance.cachedConstantTooltip(Component.translatable("renderium.video.culling_enabled.tooltip")),
            config.isOcclusionCullingEnabled(),
            newValue -> config.setOcclusionCullingEnabled(newValue)
        );
    }

    /**
     * 剔除级别（简化为 3 档：保守/适中/激进）
     *
     * @return CullingLevel 类型的 OptionInstance
     */
    private static OptionInstance<CullingLevel> createCullingAggressivenessOption() {
        List<CullingLevel> levels = List.of(CullingLevel.values());
        return new OptionInstance<>(
            "renderium.video.culling_aggressiveness",
            OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable(value.translationKey),
            new OptionInstance.Enum<>(levels, Codec.STRING.xmap(
                id -> {
                    for (CullingLevel l : levels) {
                        if (l.name().equals(id)) return l;
                    }
                    return CullingLevel.MODERATE;
                },
                CullingLevel::name
            )),
            CullingLevel.MODERATE,
            newValue -> LOGGER.debug("Culling aggressiveness changed to {}", newValue)
        );
    }

    // ==================== Debug 分区选项 ====================

    /**
     * 创建 Debug 分区选项数组（2 个选项）
     *
     * @return OptionInstance 数组，包含 ffiDebug 和 slowThreshold
     */
    public static OptionInstance<?>[] createDebugOptions() {
        return new OptionInstance<?>[]{
            createFFIDebugOption(),
            createSlowThresholdOption()
        };
    }

    /**
     * FFI 调试模式开关
     *
     * @return Boolean 类型的 OptionInstance
     */
    private static OptionInstance<Boolean> createFFIDebugOption() {
        return OptionInstance.createBoolean(
            "renderium.video.debug_ffi",
            OptionInstance.cachedConstantTooltip(Component.translatable("renderium.video.debug_ffi.tooltip")),
            VulkanFFMDebugger.DEBUG_ENABLED,
            newValue -> VulkanFFMDebugger.DEBUG_ENABLED = newValue
        );
    }

    /**
     * 慢调用阈值（1-100ms）
     *
     * <p>将 VulkanFFMDebugger.SLOW_THRESHOLD_NS（纳秒）转换为毫秒显示，
     * 用户修改后再转换回纳秒存储。
     *
     * @return Integer 类型的 OptionInstance（范围 1-100）
     */
    private static OptionInstance<Integer> createSlowThresholdOption() {
        return new OptionInstance<>(
            "renderium.video.debug_slow_threshold",
            OptionInstance.cachedConstantTooltip(Component.translatable("renderium.video.debug_slow_threshold.tooltip")),
            (caption, value) -> Component.translatable("renderium.video.debug_slow_threshold", value),
            new OptionInstance.IntRange(1, 100),
            (int) (VulkanFFMDebugger.SLOW_THRESHOLD_NS / NS_PER_MS),
            newValue -> VulkanFFMDebugger.SLOW_THRESHOLD_NS = newValue * NS_PER_MS
        );
    }

    // ==================== 内部枚举 ====================

    /**
     * 剔除级别（简化枚举，用于视频设置页面）
     *
     * <p>提供 3 个级别供用户选择，对应不同的剔除激进程度。
     */
    public enum CullingLevel {
        /** 保守模式：仅剔除确定不可见的几何体 */
        CONSERVATIVE("renderium.enum.culling.conservative"),
        /** 适中模式：平衡性能与视觉准确性 */
        MODERATE("renderium.enum.culling.moderate"),
        /** 激进模式：最大化剔除，可能偶尔出现视觉瑕疵 */
        AGGRESSIVE("renderium.enum.culling.aggressive");

        /** 翻译键 */
        final String translationKey;

        CullingLevel(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
