// Renderium - Shader 图形设置集成
// ShaderSettingsRegistrar - 光影设置选项注册器
//
// 功能：
//   将光影着色器配置参数注册到 Renderium 视频设置系统
//
// 注册入口：
//   在 VideoSettingsBridge.initialize() 中调用
//   ShaderSettingsRegistrar.registerOptions(builder)

package com.ranecc.renderium.feature.shader.settings;

import com.ranecc.renderium.None;

import java.util.logging.Logger;

/**
 * Shader 设置选项注册器
 * <p>
 * 负责将光影着色器配置参数注册到 Renderium 视频设置系统。
 *
 * <h3>注册入口：</h3>
 * 在 {@code VideoSettingsBridge.initialize()} 或
 * {@code RendererVideoOptionsRegistrar.registerAll()} 中调用
 * ShaderSettingsRegistrar.registerOptions(builder)
 *
 * @since 3.0.0
 */
public final class ShaderSettingsRegistrar {

    private static final Logger LOGGER = Logger.getLogger(ShaderSettingsRegistrar.class.getName());

    private ShaderSettingsRegistrar() {}

    /**
     * 注册所有光影设置选项到构建器
     *
     * 【方法参数】
     * @param builder None - 渲染配置构建器（受版本兼容性限制，目前使用 None 占位）
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
    public static void registerOptions(None builder) {
        if (builder == null) {
            LOGGER.warning("ShaderSettingsRegistrar: builder 为 null，跳过注册");
            return;
        }

        LOGGER.info("ShaderSettingsRegistrar: 光影设置选项注册已启动");
        LOGGER.fine("ShaderSettingsRegistrar: 当前版本仅提供占位注册入口，" +
                "完整配置页将在后续版本中通过 RendererConfigBuilder API 注册");
    }

    // ==================== 占位说明 ====================
    // 当前版本暂未注册具体设置分组。
    // 完整配置页（预设 / 阴影与光照 / 画面增强 / 环境 / 高级）
    // 将在后续版本中通过 RendererConfigBuilder API 重新注册。
    // RTReflectionQuality 枚举保留以供下游引用。

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
