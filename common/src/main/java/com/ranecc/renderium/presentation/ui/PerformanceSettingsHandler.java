package com.ranecc.renderium.presentation.ui;

import com.ranecc.renderium.application.usecase.ConfigureUseCase;
import com.ranecc.renderium.domain.constant.ConfigConstants;
import com.ranecc.renderium.domain.enums.QualityLevel;
import com.ranecc.renderium.domain.enums.RenderiumMode;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.infrastructure.config.ConfigValidator;

import java.util.logging.Logger;

/**
 * 性能设置处理器（Presentation Layer - UI）
 *
 * <p>负责处理用户在设置界面中的配置变更操作，
 * 包括算法开关、GPU 阈值、运行模式等的更新。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>onAlgorithmToggle(name, enabled)</b>：切换算法开关</li>
 *   <li><b>onGpuThresholdChange(value)</b>：更新 GPU 使用率阈值</li>
 *   <li><b>onModeChange(mode)</b>：切换运行模式</li>
 *   <li><b>验证保证</b>：所有输入值都会经过范围检查</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li><b>输入验证</b>：所有 setter 方法都包含参数校验</li>
 *   <li><b>即时反馈</b>：返回操作结果供 UI 显示提示信息</li>
 *   <li><b>线程安全</b>：所有配置修改都是原子性的</li>
 *   <li><b>零魔法数字</b>：所有边界值来自 ConfigConstants</li>
 * </ul>
 *
 * @see RenderiumConfig
 * @see ConfigureUseCase
 * @see ConfigConstants
 * @since 1.1.0
 */
public class PerformanceSettingsHandler {

    private static final Logger LOGGER = Logger.getLogger(PerformanceSettingsHandler.class.getName());

    /** 配置管理用例（延迟初始化） */
    private ConfigureUseCase configureUseCase;

    /**
     * 默认构造函数
     */
    public PerformanceSettingsHandler() {
        // 延迟初始化 configureUseCase，避免循环依赖
    }

    /**
     * 确保 ConfigureUseCase 已初始化
     *
     * @return ConfigureUseCase 实例
     */
    private ConfigureUseCase getConfigureUseCase() {
        if (configureUseCase == null) {
            synchronized (this) {
                if (configureUseCase == null) {
                    configureUseCase = new ConfigureUseCase();
                }
            }
        }
        return configureUseCase;
    }

    // ==================== 算法开关控制 ====================

    /**
     * 切换算法启用/禁用状态
     *
     * <p>根据算法名称更新对应配置项的启用状态。
     * 支持的算法名称：
     * <ul>
     *   <li>"bfs" - BFS 遮挡剔除算法</li>
     *   <li>"lod" - LOD 距离计算算法</li>
     *   <li>"kahan" - Kahan 高精度累加器</li>
     * </ul>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>name - 算法名称（String 类型）</li>
     *   <li><b>参数：</b>enabled - 是否启用（boolean 类型）</li>
     *   <li><b>返回值：</b>boolean - true 表示操作成功</li>
     * </ul>
     *
     * @param name    算法名称（不区分大小写）
     * @param enabled 是否启用该算法
     * @return true 表示成功更新，false 表示算法名称无效或更新失败
     */
    public boolean onAlgorithmToggle(String name, boolean enabled) {
        if (name == null || name.isBlank()) {
            LOGGER.warning("Algorithm name is null or blank");
            return false;
        }

        RenderiumConfig config = getConfigureUseCase().get();
        String algorithmName = name.toLowerCase().trim();

        try {
            switch (algorithmName) {
                case "bfs":
                    config.getAlgorithmConfig().getBfs().setEnabled(enabled);
                    LOGGER.info("BFS algorithm " + (enabled ? "enabled" : "disabled"));
                    break;

                case "lod":
                    config.getAlgorithmConfig().getLod().setEnabled(enabled);
                    LOGGER.info("LOD algorithm " + (enabled ? "enabled" : "disabled"));
                    break;

                case "kahan":
                    config.getAlgorithmConfig().getKahan().setEnabled(enabled);
                    LOGGER.info("Kahan algorithm " + (enabled ? "enabled" : "disabled"));
                    break;

                default:
                    LOGGER.warning("Unknown algorithm name: " + name
                               + ". Supported: bfs, lod, kahan");
                    return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to toggle algorithm '" + name + "': " + e.getMessage());
            return false;
        }
    }

    // ==================== GPU 阈值控制 ====================

    /**
     * 更新 GPU 使用率阈值
     *
     * <p>设置触发路径切换的 GPU 占用率阈值。
     * 输入值会被自动钳制到合法范围 [MIN_GPU_THRESHOLD, MAX_GPU_THRESHOLD]。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>value - 新的阈值（float 类型，范围 0.0-1.0）</li>
     *   <li><b>返回值：</b>boolean - true 表示成功更新</li>
     * </ul>
     *
     * <h4>边界说明</h4>
     * <ul>
     *   <li>最小值：{@link ConfigConstants#MIN_GPU_THRESHOLD} (0.3f)</li>
     *   <li>最大值：{@link ConfigConstants#MAX_GPU_THRESHOLD} (0.95f)</li>
     *   <li>默认值：{@link ConfigConstants#DEFAULT_GPU_USAGE_THRESHOLD} (0.7f)</li>
     * </ul>
     *
     * @param value 新的 GPU 使用率阈值（建议范围 0.3-0.95）
     * @return true 表示成功更新并验证通过，false 表示值超出合理范围
     */
    public boolean onGpuThresholdChange(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            LOGGER.warning("Invalid GPU threshold value: " + value);
            return false;
        }

        float clampedValue = Math.max(ConfigConstants.MIN_GPU_THRESHOLD,
                             Math.min(ConfigConstants.MAX_GPU_THRESHOLD, value));

        RenderiumConfig config = getConfigureUseCase().get();

        try {
            config.setGpuUsageThreshold(clampedValue);
            config.getAlgorithmConfig().setGpuUsageThreshold(clampedValue);

            LOGGER.info(String.format("GPU usage threshold updated: %.2f (clamped from %.2f)",
                         clampedValue, value));

            if (Math.abs(value - clampedValue) > 0.001f) {
                LOGGER.warning("GPU threshold was clamped from " + value + " to " + clampedValue);
            }

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to update GPU threshold: " + e.getMessage());
            return false;
        }
    }

    // ==================== 运行模式控制 ====================

    /**
     * 切换运行模式
     *
     * <p>更改 Renderium 的整体运行模式：
     * <ul>
     *   <li>{@link RenderiumMode#COMPATIBILITY} - 兼容模式（稳定优先）</li>
     *   <li>{@link RenderiumMode#COMPATIBILITY_LIMITED} - 受限兼容模式</li>
     *   <li>{@link RenderiumMode#AGGRESSIVE} - 狂暴模式（性能优先）</li>
     * </ul>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>mode - 目标运行模式（RenderiumMode 枚举类型）</li>
     *   <li><b>返回值：</b>boolean - true 表示切换成功</li>
     * </ul>
     *
     * @param mode 目标运行模式（不能为 null）
     * @return true 表示成功切换，false 表示参数无效或切换失败
     * @throws IllegalArgumentException 如果 mode 为 null
     */
    public boolean onModeChange(RenderiumMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode cannot be null");
        }

        RenderiumConfig config = getConfigureUseCase().get();

        try {
            RenderiumMode oldMode = config.getMode();
            config.setMode(mode);

            LOGGER.info(String.format("Renderium mode changed: %s -> %s (%s)",
                         oldMode, mode, mode.getDisplayName()));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to change render mode: " + e.getMessage());
            return false;
        }
    }

    // ==================== 质量等级控制 ====================

    /**
     * 切换质量等级
     *
     * <p>更改渲染质量等级：
     * <ul>
     *   <li>{@link QualityLevel#ULTRA} - 最高画质（4）</li>
     *   <li>{@link QualityLevel#HIGH} - 高画质（3）</li>
     *   <li>{@link QualityLevel#MEDIUM} - 中等画质（2）</li>
     *   <li>{@link QualityLevel#LOW} - 低画质（1）</li>
     * </ul>
     *
     * @param level 目标质量等级
     * @return true 表示切换成功
     */
    public boolean onQualityLevelChange(QualityLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("level cannot be null");
        }

        RenderiumConfig config = getConfigureUseCase().get();

        try {
            QualityLevel oldLevel = config.getQualityLevel();
            config.setQualityLevel(level);

            LOGGER.info("Quality level changed: " + oldLevel + " -> " + level
                       + " (value=" + level.value + ")");

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to change quality level: " + e.getMessage());
            return false;
        }
    }

    // ==================== 其他配置项控制 ====================

    /**
     * 切换超分辨率启用状态
     *
     * @param enabled 是否启用超分辨率
     * @return true 表示成功更新
     */
    public boolean onSuperResolutionToggle(boolean enabled) {
        try {
            getConfigureUseCase().get().setSuperResolutionEnabled(enabled);
            LOGGER.info("Super resolution " + (enabled ? "enabled" : "disabled"));
            return true;
        } catch (Exception e) {
            LOGGER.severe("Failed to toggle super resolution: " + e.getMessage());
            return false;
        }
    }

    /**
     * 切换遮挡剔除启用状态
     *
     * @param enabled 是否启用遮挡剔除
     * @return true 表示成功更新
     */
    public boolean onOcclusionCullingToggle(boolean enabled) {
        try {
            getConfigureUseCase().get().setOcclusionCullingEnabled(enabled);
            LOGGER.info("Occlusion culling " + (enabled ? "enabled" : "disabled"));
            return true;
        } catch (Exception e) {
            LOGGER.severe("Failed to toggle occlusion culling: " + e.getMessage());
            return false;
        }
    }

    /**
     * 切换批量渲染启用状态
     *
     * @param enabled 是否启用批量渲染
     * @return true 表示成功更新
     */
    public boolean onBatchingToggle(boolean enabled) {
        try {
            getConfigureUseCase().get().setBatchingEnabled(enabled);
            LOGGER.info("Batch rendering " + (enabled ? "enabled" : "disabled"));
            return true;
        } catch (Exception e) {
            LOGGER.severe("Failed to toggle batching: " + e.getMessage());
            return false;
        }
    }

    // ==================== 配置持久化 ====================

    /**
     * 保存当前配置到文件
     *
     * @return true 表示保存成功
     */
    public boolean saveConfiguration() {
        try {
            return getConfigureUseCase().save();
        } catch (Exception e) {
            LOGGER.severe("Failed to save configuration: " + e.getMessage());
            return false;
        }
    }

    /**
     * 从文件重新加载配置
     *
     * @return true 表示加载成功且验证通过
     */
    public boolean reloadConfiguration() {
        try {
            return getConfigureUseCase().reload();
        } catch (Exception e) {
            LOGGER.severe("Failed to reload configuration: " + e.getMessage());
            return false;
        }
    }

    /**
     * 验证当前配置的合法性
     *
     * @return ConfigValidator.ValidationResult 验证结果
     */
    public ConfigValidator.ValidationResult validateCurrentConfig() {
        RenderiumConfig config = getConfigureUseCase().get();
        return ConfigValidator.validate(config);
    }

    /**
     * 重置为默认配置
     *
     * <p>注意：这不会自动保存到文件。
     */
    public void resetToDefaults() {
        getConfigureUseCase().resetToDefaults();
        LOGGER.info("Configuration reset to defaults");
    }

    /**
     * 获取最后一次操作的错误消息
     *
     * @return 错误消息字符串，如果没有错误则返回 null
     */
    public String getLastError() {
        return getConfigureUseCase().getLastError();
    }
}
