package com.ranecc.renderium.infrastructure.config;

import com.ranecc.renderium.domain.constant.ConfigConstants;
import com.ranecc.renderium.domain.enums.RenderiumMode;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 配置管理器（Infrastructure Layer）
 *
 * <p>负责 {@link RenderiumConfig} 聚合根的持久化操作，
 * 包括加载、保存、迁移和默认配置创建。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>加载配置</b>：从文件系统读取 Properties 并构建 RenderiumConfig 实例</li>
 *   <li><b>保存配置</b>：将 RenderiumConfig 序列化为 Properties 格式并写入文件</li>
 *   <li><b>配置迁移</b>：处理旧版本配置格式到新版本的兼容性转换</li>
 *   <li><b>默认配置</b>：基于 ConfigConstants 创建标准默认配置</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 加载配置
 * RenderiumConfig config = ConfigManager.load("/path/to/config");
 *
 * // 保存配置
 * ConfigManager.save(config, "/path/to/config");
 *
 * // 获取默认配置
 * RenderiumConfig defaults = ConfigManager.getDefault();
 * }</pre>
 *
 * @see RenderiumConfig
 * @see ConfigValidator
 * @see ConfigConstants
 * @since 1.1.0
 */
public final class ConfigManager {

    private static final Logger LOGGER = Logger.getLogger(ConfigManager.class.getName());

    /** 私有构造函数 - 工具类，不允许实例化 */
    private ConfigManager() {}

    /**
     * 从指定路径加载配置
     *
     * <p>如果配置文件不存在，返回默认配置实例。
     * 加载后会自动执行验证，记录警告但不抛出异常。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>configPath - 配置目录路径（String 类型）</li>
     *   <li><b>返回值：</b>RenderiumConfig - 加载的配置实例（非 null）</li>
     *   <li><b>异常：</b>无（内部捕获所有 IO 异常并降级为默认配置）</li>
     * </ul>
     *
     * @param configPath 配置目录路径（例如 "/home/user/.renderium"）
     * @return RenderiumConfig 配置实例（如果文件不存在或加载失败则返回默认配置）
     */
    public static RenderiumConfig load(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            LOGGER.warning("configPath is null or empty, using default config");
            return getDefault();
        }

        Path configFile = Path.of(configPath).resolve(RenderiumConfig.CONFIG_FILE);

        if (!Files.isRegularFile(configFile)) {
            LOGGER.info("Config file not found at " + configFile + ", using defaults");
            return getDefault();
        }

        RenderiumConfig config = new RenderiumConfig();

        try (Reader reader = Files.newBufferedReader(configFile)) {
            Properties props = new Properties();
            props.load(reader);
            config.loadFromProperties(props);

            LOGGER.info("Config loaded successfully from " + configFile);

        } catch (IOException e) {
            LOGGER.warning("Failed to load config from " + configFile +
                         ": " + e.getMessage() + ", using defaults");
            return getDefault();
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid numeric value in config file " + configFile +
                         ": " + e.getMessage() + ", using defaults");
            return getDefault();
        }

        // 验证配置
        RenderiumConfig.ValidationResult result = config.validate();
        if (result.hasErrors()) {
            LOGGER.severe("Config validation failed: " + result.getErrorMessage());
        } else if (result.hasWarnings()) {
            LOGGER.warning("Config validation warnings: " + result.getWarningMessage());
        }

        return config;
    }

    /**
     * 保存配置到指定路径
     *
     * <p>将 RenderiumConfig 序列化为 Properties 格式并写入文件。
     * 如果目录不存在会自动创建。保存前会先验证配置合法性。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>config - 要保存的配置实例（RenderiumConfig 类型）</li>
     *   <li><b>参数：</b>configPath - 配置目录路径（String 类型）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>无（内部捕获所有 IO 异常并记录日志）</li>
     * </ul>
     *
     * @param config     要保存的配置实例（不能为 null）
     * @param configPath 配置目录路径
     * @throws IllegalArgumentException 如果 config 为 null
     */
    public static void save(RenderiumConfig config, String configPath) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalArgumentException("configPath cannot be null or blank");
        }

        Path configFile = Path.of(configPath).resolve(RenderiumConfig.CONFIG_FILE);

        // 保存前验证配置
        RenderiumConfig.ValidationResult validation = config.validate();
        if (validation.hasErrors()) {
            LOGGER.severe("Cannot save invalid config: " + validation.getErrorMessage());
            return;
        }
        if (validation.hasWarnings()) {
            LOGGER.warning("Saving config with warnings: " + validation.getWarningMessage());
        }

        try {
            Files.createDirectories(configFile.getParent());
        } catch (IOException e) {
            LOGGER.severe("Failed to create config directory: " + e.getMessage());
            return;
        }

        Properties props = new Properties();
        config.saveToProperties(props);

        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "Renderium Configuration v1.1.0");
            LOGGER.info("Config saved successfully to " + configFile);
        } catch (IOException e) {
            LOGGER.severe("Failed to save config to " + configFile + ": " + e.getMessage());
        }
    }

    /**
     * 迁移旧版本配置到新版本
     *
     * <p>处理旧版本配置格式的兼容性转换：
     * <ul>
     *   <li>v1.0 → v1.1：添加算法配置字段、重命名部分属性</li>
     *   <li>未来版本可在此添加更多迁移逻辑</li>
     * </ul>
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>oldConfigPath - 旧版本配置文件路径（String 类型）</li>
     *   <li><b>返回值：</b>RenderiumConfig - 迁移后的新版本配置实例</li>
     *   <li><b>异常：</b>无（迁移失败时返回默认配置）</li>
     * </ul>
     *
     * @param oldConfigPath 旧版本配置文件路径
     * @return 迁移后的 RenderiumConfig 实例（失败时返回默认配置）
     */
    public static RenderiumConfig migrate(String oldConfigPath) {
        if (oldConfigPath == null || oldConfigPath.isBlank()) {
            LOGGER.warning("oldConfigPath is null or empty, returning default config");
            return getDefault();
        }

        Path oldFile = Path.of(oldConfigPath);

        if (!Files.isRegularFile(oldFile)) {
            LOGGER.warning("Old config file not found: " + oldFile + ", returning default config");
            return getDefault();
        }

        try {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(oldFile)) {
                props.load(reader);
            }

            // 检测旧版本标记
            String version = props.getProperty("renderium.version", "1.0");

            LOGGER.info("Migrating config from version " + version + " to 1.1.0");

            // 创建新配置并应用迁移规则
            RenderiumConfig newConfig = new RenderiumConfig();

            // v1.0 → v1.1 迁移规则
            if ("1.0".equals(version) || !props.containsKey("renderium.version")) {
                migrateV10ToV11(props, newConfig);
            }

            // 通用属性加载（覆盖迁移后的默认值）
            newConfig.loadFromProperties(props);

            // 验证迁移后的配置
            RenderiumConfig.ValidationResult result = newConfig.validate();
            if (result.hasErrors()) {
                LOGGER.severe("Migration produced invalid config: " + result.getErrorMessage());
                return getDefault();
            }

            LOGGER.info("Config migration completed successfully");
            return newConfig;

        } catch (IOException e) {
            LOGGER.severe("Failed to read old config for migration: " + e.getMessage());
            return getDefault();
        }
    }

    /**
     * 获取默认配置实例
     *
     * <p>基于 {@link ConfigConstants} 中的常量创建标准默认配置，
     * 所有字段都使用预定义的合理默认值。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>无</li>
     *   <li><b>返回值：</b>RenderiumConfig - 默认配置实例</li>
     * </ul>
     *
     * @return 使用所有默认值的 RenderiumConfig 实例
     */
    public static RenderiumConfig getDefault() {
        return new RenderiumConfig();
    }

    /**
     * 加载或返回默认配置
     *
     * <p>便捷方法，尝试从指定路径加载配置，
     * 如果加载失败则返回默认配置。
     *
     * @param configPath 配置目录路径
     * @return 加载的配置或默认配置
     */
    public static RenderiumConfig loadOrDefault(String configPath) {
        try {
            return load(configPath);
        } catch (Exception e) {
            LOGGER.warning("Failed to load config from " + configPath +
                         ": " + e.getMessage() + ", using defaults");
            return getDefault();
        }
    }

    /**
     * 执行 v1.0 → v1.1 版本迁移
     *
     * <p>迁移规则：
     * <ul>
     *   <li>gpu.threshold → algorithm.gpuUsageThreshold</li>
     *   <li>bfs.enabled → algorithm.bfs.enabled</li>
     *   <li>lod.maxLevels → algorithm.lod.maxLevels</li>
     *   <li>添加缺失的默认字段</li>
     * </ul>
     *
     * @param oldProps 旧版本 Properties
     * @param newConfig 新配置实例（将被填充迁移后的值）
     */
    private static void migrateV10ToV11(Properties oldProps, RenderiumConfig newConfig) {
        // 迁移 GPU 阈值
        if (oldProps.containsKey("gpu.threshold")) {
            try {
                float threshold = Float.parseFloat(oldProps.getProperty("gpu.threshold"));
                newConfig.setGpuUsageThreshold(threshold);
                newConfig.getAlgorithmConfig().setGpuUsageThreshold(threshold);
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid gpu.threshold value, using default");
            }
        }

        // 运行模式迁移
        if (oldProps.containsKey("mode")) {
            String modeStr = oldProps.getProperty("mode");
            try {
                RenderiumMode mode = RenderiumMode.valueOf(modeStr.toUpperCase());
                newConfig.setMode(mode);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Invalid mode value: " + modeStr + ", using default");
            }
        }

        LOGGER.info("Applied v1.0 → v1.1 migration rules");
    }
}
