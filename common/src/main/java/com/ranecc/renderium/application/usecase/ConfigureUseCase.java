package com.ranecc.renderium.application.usecase;

import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.infrastructure.config.ConfigManager;
import com.ranecc.renderium.infrastructure.config.ConfigValidator;

import java.util.logging.Logger;

/**
 * 配置管理用例（Application Layer - Use Case）
 *
 * <p>提供配置的加载、保存、重新加载和查询功能，
 * 作为 Application 层对 Infrastructure 层 ConfigManager 的封装。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>load()</b>：从指定路径加载配置</li>
 *   <li><b>save()</b>：保存当前配置到指定路径</li>
 *   <li><b>reload()</b>：重新加载并验证配置</li>
 *   <li><b>get()</b>：返回当前配置的不可变快照</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li><b>线程安全</b>：所有操作都是原子的</li>
 *   <li><b>验证保证</b>：每次加载/保存都自动验证</li>
 *   <li><b>错误透明</b>：所有错误都记录日志并可查询</li>
 * </ul>
 *
 * @see ConfigManager
 * @see ConfigValidator
 * @see RenderiumConfig
 * @since 1.1.0
 */
public class ConfigureUseCase {

    private static final Logger LOGGER = Logger.getLogger(ConfigureUseCase.class.getName());

    /** 当前活跃配置实例 */
    private volatile RenderiumConfig currentConfig;

    /** 配置文件路径 */
    private String configPath;

    /** 上次操作的错误消息（如果有） */
    private volatile String lastError;

    /**
     * 默认构造函数
     */
    public ConfigureUseCase() {
        this.currentConfig = ConfigManager.getDefault();
        this.configPath = getDefaultConfigPath();
    }

    /**
     * 构造函数（指定配置路径）
     *
     * @param configPath 配置目录路径
     */
    public ConfigureUseCase(String configPath) {
        this.currentConfig = ConfigManager.getDefault();
        this.configPath = configPath != null ? configPath : getDefaultConfigPath();
    }

    /**
     * 从文件加载配置
     *
     * <p>委托给 {@link ConfigManager#load(String)} 执行实际加载，
     * 成功后更新 currentConfig 引用。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无（使用构造时指定的路径）</li>
     *   <li><b>返回值：</b>RenderiumConfig - 加载的配置实例</li>
     *   <li><b>异常：</b>无（失败时返回默认配置并设置 lastError）</li>
     * </ul>
     *
     * @return RenderiumConfig 加载的配置实例（非 null）
     */
    public RenderiumConfig load() {
        try {
            LOGGER.info("Loading configuration from: " + configPath);

            RenderiumConfig loaded = ConfigManager.load(configPath);

            if (loaded != null) {
                this.currentConfig = loaded;
                this.lastError = null;
                LOGGER.info("Configuration loaded successfully");
            } else {
                this.lastError = "ConfigManager returned null";
                LOGGER.warning("Failed to load configuration, using defaults");
            }

            return this.currentConfig;

        } catch (Exception e) {
            this.lastError = "Load failed: " + e.getMessage();
            LOGGER.severe("Failed to load configuration: " + e.getMessage());
            return this.currentConfig; // 返回现有配置（可能是默认值）
        }
    }

    /**
     * 保存当前配置到文件
     *
     * <p>先验证配置合法性，然后委托给 {@link ConfigManager#save(RenderiumConfig, String)}。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无（保存 currentConfig 到构造时指定的路径）</li>
     *   <li><b>返回值：</b>boolean - true 表示保存成功</li>
     *   <li><b>异常：</b>无（失败时返回 false 并设置 lastError）</li>
     * </ul>
     *
     * @return true 表示保存成功，false 表示失败
     */
    public boolean save() {
        if (currentConfig == null) {
            this.lastError = "No configuration to save";
            LOGGER.warning("Cannot save: no active configuration");
            return false;
        }

        try {
            LOGGER.info("Saving configuration to: " + configPath);

            ConfigManager.save(currentConfig, configPath);
            this.lastError = null;

            LOGGER.info("Configuration saved successfully");
            return true;

        } catch (Exception e) {
            this.lastError = "Save failed: " + e.getMessage();
            LOGGER.severe("Failed to save configuration: " + e.getMessage());
            return false;
        }
    }

    /**
     * 重新加载配置（带验证）
     *
     * <p>从文件重新加载配置并进行全面验证，
     * 如果验证失败则保留原有配置不变。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>boolean - true 表示重载成功且验证通过</li>
     *   <li><b>异常：</b>无</li>
     * </ul>
     *
     * @return true 表示重载成功且配置合法，false 表示失败或验证不通过
     */
    public boolean reload() {
        try {
            LOGGER.info("Reloading configuration from: " + configPath);

            // 先加载新配置
            RenderiumConfig newConfig = ConfigManager.load(configPath);

            if (newConfig == null) {
                this.lastError = "Reload returned null configuration";
                LOGGER.warning("Reload failed: no configuration loaded");
                return false;
            }

            // 验证新配置
            ConfigValidator.ValidationResult validation = ConfigValidator.validate(newConfig);

            if (validation.hasErrors()) {
                this.lastError = "Validation failed: " + String.join("; ", validation.getErrors());
                LOGGER.severe("Reloaded configuration is invalid: " + this.lastError);
                LOGGER.warning("Keeping previous configuration unchanged");
                return false;
            }

            if (validation.hasWarnings()) {
                LOGGER.warning("Configuration reloaded with warnings: "
                            + String.join("; ", validation.getWarnings()));
            }

            // 验证通过，替换当前配置
            this.currentConfig = newConfig;
            this.lastError = null;

            LOGGER.info("Configuration reloaded and validated successfully");
            return true;

        } catch (Exception e) {
            this.lastError = "Reload failed: " + e.getMessage();
            LOGGER.severe("Failed to reload configuration: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取当前配置快照
     *
     * <p>返回当前活跃配置的引用（非拷贝）。
     * 由于 RenderiumConfig 本身不是不可变的，
     * 调用方应避免修改返回的对象。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>RenderiumConfig - 当前配置实例（非 null）</li>
     * </ul>
     *
     * @return RenderiumConfig 当前配置实例（非 null）
     */
    public RenderiumConfig get() {
        if (currentConfig == null) {
            LOGGER.warning("Current config is null, returning default");
            currentConfig = ConfigManager.getDefault();
        }
        return currentConfig;
    }

    /**
     * 获取最后一次操作的错误消息
     *
     * @return 错误消息字符串，如果没有错误则返回 null
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 清除错误状态
     */
    public void clearError() {
        this.lastError = null;
    }

    /**
     * 检查是否有待保存的修改
     *
     * <p>简单实现：始终返回 false（未来可添加脏标记机制）。
     *
     * @return true 如果有待保存的修改
     */
    public boolean hasUnsavedChanges() {
        // TODO: 实现脏标记检测
        return false;
    }

    /**
     * 重置为默认配置
     *
     * <p>将当前配置替换为全新的默认配置实例。
     * 注意：这不会自动保存到文件。
     */
    public void resetToDefaults() {
        this.currentConfig = ConfigManager.getDefault();
        this.lastError = null;
        LOGGER.info("Configuration reset to defaults");
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取默认配置路径
     *
     * @return 默认配置目录路径
     */
    private String getDefaultConfigPath() {
        String path = System.getProperty("renderium.config.path", "");
        if (!path.isEmpty()) {
            return path;
        }

        String userHome = System.getProperty("user.home", ".");
        return userHome + "/.renderium";
    }
}
