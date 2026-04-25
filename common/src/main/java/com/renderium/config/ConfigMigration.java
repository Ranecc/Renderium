// Renderium - 配置迁移工具
// 处理从 v5 到 v5.1 的配置格式迁移，确保向后兼容性

package com.renderium.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 配置迁移工具 (Config Migration Utility)
 *
 * <p>负责处理 Renderium 配置文件的版本迁移，
 * 确保从旧版本升级时能够自动适配新的配置格式。
 *
 * <h3>主要功能：</h3>
 * <ul>
 *   <li><b>版本检测</b>：识别当前配置文件的版本</li>
 *   <li><b>自动迁移</b>：将旧版配置转换为新版格式</li>
 *   <li><b>默认值填充</b>：为新增字段提供合理的默认值</li>
 *   <li><b>废弃字段警告</b>：标记已废弃的配置项并记录日志</li>
 * </ul>
 *
 * <h3>支持的迁移路径：</h3>
 * <pre>
 * v5.0 → v5.1: 添加 interception 配置节
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 在应用启动时调用
 * Path configDir = Paths.get("config/renderium");
 * ConfigMigration.migrate(configDir);
 *
 * // 迁移完成后正常加载配置
 * RenderiumConfig config = RenderiumConfig.load(configDir);
 * </pre>
 *
 * @author Renderium Team
 * @since 5.1.0
 * @version 1.0
 */
public final class ConfigMigration {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(ConfigMigration.class.getName());

    /** 当前配置版本 */
    public static final String CURRENT_VERSION = "5.1";

    /** 上一版本号 */
    private static final String PREVIOUS_VERSION = "5.0";

    /** 配置文件名 */
    private static final String CONFIG_FILE = "renderium.properties";

    /** 版本号配置键 */
    private static final String VERSION_KEY = "config.version";

    /**
     * 私有构造函数 - 工具类不允许实例化
     */
    private ConfigMigration() {}

    /**
     * 执行配置迁移（主入口方法）
     *
     * <p>此方法会：
     * <ol>
     *   <li>检查配置文件是否存在</li>
     *   <li>读取当前配置版本</li>
     *   <li>根据版本执行相应的迁移逻辑</li>
     *   <li>更新版本号标记</li>
     *   <li>保存迁移后的配置</li>
     * </ol>
     *
     * @param configDir 配置目录路径
     * @return 迁移结果（包含成功/失败状态和详细信息）
     */
    public static MigrationResult migrate(Path configDir) {
        LOGGER.info("开始配置迁移检查...");

        Path configFile = configDir.resolve(CONFIG_FILE);

        // 场景 1：配置文件不存在，无需迁移
        if (!Files.isRegularFile(configFile)) {
            LOGGER.info("配置文件不存在，跳过迁移（首次运行）");
            return new MigrationResult(true, "No config file found (first run)", false);
        }

        try {
            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(configFile)) {
                props.load(reader);
            }

            String currentVersion = props.getProperty(VERSION_KEY, "5.0");

            LOGGER.info("当前配置版本: " + currentVersion + ", 目标版本: " + CURRENT_VERSION);

            // 场景 2：版本相同，无需迁移
            if (CURRENT_VERSION.equals(currentVersion)) {
                LOGGER.info("配置已是最新版本，跳过迁移");
                return new MigrationResult(true, "Already at latest version", false);
            }

            // 场景 3：需要执行迁移
            boolean migrated = false;
            StringBuilder migrationLog = new StringBuilder();

            // 执行 v5.0 → v5.1 迁移
            if (PREVIOUS_VERSION.equals(currentVersion) || isVersionOlder(currentVersion, PREVIOUS_VERSION)) {
                LOGGER.info("执行 v5.0 → v5.1 迁移...");
                MigrationResult result = migrateV50ToV51(props, migrationLog);
                migrated = result.wasMigrated();
                migrationLog.append(result.getMessage()).append("\n");
            }

            // 更新版本号
            if (migrated) {
                props.setProperty(VERSION_KEY, CURRENT_VERSION);

                // 备份原配置文件
                backupConfigFile(configFile);

                // 保存迁移后的配置
                try (var writer = Files.newBufferedWriter(configFile)) {
                    props.store(writer, "Renderium Configuration (migrated to v" + CURRENT_VERSION + ")");
                }

                LOGGER.info("配置迁移完成:\n" + migrationLog);
                return new MigrationResult(true, migrationLog.toString(), true);
            } else {
                LOGGER.warning("配置迁移未执行任何操作");
                return new MigrationResult(false, "Migration not needed or failed", false);
            }

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "配置迁移失败: " + e.getMessage(), e);
            return new MigrationResult(false, "Migration failed: " + e.getMessage(), false);
        }
    }

    /**
     * 执行 v5.0 → v5.1 的配置迁移
     *
     * <p>主要变更：
     * <ul>
     *   <li>添加 interception 配置节（拦截层配置）</li>
     *   <li>迁移超分辨率相关配置到后拦截层</li>
     *   <li>添加默认值以保持向后兼容</li>
     * </ul>
     *
     * @param props 原始配置属性
     * @param log 迁移日志（追加模式）
     * @return 迁移结果
     */
    private static MigrationResult migrateV50ToV51(Properties props, StringBuilder log) {
        boolean migrated = false;

        // ========== 1. 添加拦截层前拦截层配置 ==========
        if (!props.containsKey("interception.preInterceptor.enabled")) {
            props.setProperty("interception.preInterceptor.enabled", "true");
            log.append("[ADD] interception.preInterceptor.enabled = true\n");
            migrated = true;
        }

        if (!props.containsKey("interception.preInterceptor.sodiumDetection")) {
            props.setProperty("interception.preInterceptor.sodiumDetection", "true");
            log.append("[ADD] interception.preInterceptor.sodiumDetection = true\n");
            migrated = true;
        }

        // LOD 注入配置
        addIfAbsent(props, log, "interception.preInterceptor.lodInjection.enabled", "true");
        addIfAbsent(props, log, "interception.preInterceptor.lodInjection.maxLevels", "4");
        addIfAbsent(props, log, "interception.preInterceptor.lodInjection.distanceThresholds", "32,64,128");
        addIfAbsent(props, log, "interception.preInterceptor.lodInjection.transitionMode", "dithering");

        // 剔除注入配置
        addIfAbsent(props, log, "interception.preInterceptor.cullingInjection.enabled", "true");
        addIfAbsent(props, log, "interception.preInterceptor.cullingInjection.frustumCulling", "true");
        addIfAbsent(props, log, "interception.preInterceptor.cullingInjection.occlusionCulling", "true");
        addIfAbsent(props, log, "interception.preInterceptor.cullingInjection.distanceCulling", "true");
        addIfAbsent(props, log, "interception.preInterceptor.cullingInjection.strategy", "balanced");

        // ========== 2. 添加拦截层后拦截层配置 ==========
        if (!props.containsKey("interception.postInterceptor.enabled")) {
            props.setProperty("interception.postInterceptor.enabled", "true");
            log.append("[ADD] interception.postInterceptor.enabled = true\n");
            migrated = true;
        }

        // 帧捕获配置
        addIfAbsent(props, log, "interception.postInterceptor.frameCapture.method", "auto");
        addIfAbsent(props, log, "interception.postInterceptor.frameCapture.format", "RGBA16F");
        addIfAbsent(props, log, "interception.postInterceptor.frameCapture.msaa", "4");
        addIfAbsent(props, log, "interception.postInterceptor.frameCapture.async", "false");

        // 超分辨率配置（从主配置迁移）
        migrateSuperResolutionConfig(props, log);

        // 帧生成配置
        addIfAbsent(props, log, "interception.postInterceptor.frameGeneration.enabled", "false");
        addIfAbsent(props, log, "interception.postInterceptor.frameGeneration.mode", "dlss-fg");
        addIfAbsent(props, log, "interception.postInterceptor.frameGeneration.targetFPS", "120");

        // 后处理配置
        addIfAbsent(props, log, "interception.postInterceptor.postProcessing.bloom.enabled",
                   props.getProperty("effects.bloom.enabled", "true"));
        addIfAbsent(props, log, "interception.postInterceptor.postProcessing.bloom.intensity",
                   props.getProperty("effects.bloom.intensity", "0.5"));
        addIfAbsent(props, log, "interception.postInterceptor.postProcessing.dof.enabled",
                   props.getProperty("effects.dof.enabled", "false"));
        addIfAbsent(props, log, "interception.postInterceptor.postProcessing.motionBlur.enabled",
                   props.getProperty("effects.motionBlur.enabled", "true"));
        addIfAbsent(props, log, "interception.postInterceptor.postProcessing.motionBlur.intensity",
                   props.getProperty("effects.motionBlur.strength", "0.3"));

        // ========== 3. 添加模组输出重定向器配置 ==========
        // 向后兼容迁移: sodiumRedirector.* → modOutputRedirect.*
        migrateModOutputRedirectConfig(props, log);

        // 使用新键名添加默认值（仅在迁移后仍缺失时）
        addIfAbsent(props, log, "interception.modOutputRedirect.enabled", "true");
        addIfAbsent(props, log, "interception.modOutputRedirect.supportedVersions", "0.5.x,0.6.x");
        addIfAbsent(props, log, "interception.modOutputRedirect.fallbackMode", "safe");

        // ========== 4. 标记废弃字段 ==========
        markDeprecatedFields(props, log);

        if (migrated) {
            return new MigrationResult(true, log.toString(), true);
        } else {
            return new MigrationResult(true, "No new fields to add", false);
        }
    }

    /**
     * 迁移超分辨率配置（从 v5.0 主配置迁移到 v5.1 后拦截层配置）
     *
     * @param props 配置属性
     * @param log 迁移日志
     */
    private static void migrateSuperResolutionConfig(Properties props, StringBuilder log) {
        // 如果后拦截层的超分辨率配置不存在，尝试从主配置迁移
        if (!props.containsKey("interception.postInterceptor.superResolution.enabled")) {
            // 从旧的 superResolution.* 键迁移
            String oldEnabled = props.getProperty("superResolution.enabled");
            if (oldEnabled != null) {
                props.setProperty("interception.postInterceptor.superResolution.enabled", oldEnabled);
                log.append("[MIGRATE] superResolution.enabled → interception.postInterceptor.superResolution.enabled\n");
            } else {
                props.setProperty("interception.postInterceptor.superResolution.enabled", "true");
            }
        }

        if (!props.containsKey("interception.postInterceptor.superResolution.preferredTechnology")) {
            String oldTech = props.getProperty("superResolution.technology");
            if (oldTech != null) {
                // 转换枚举名称（DLSS → dlss）
                props.setProperty("interception.postInterceptor.superResolution.preferredTechnology",
                               oldTech.toLowerCase());
                log.append("[MIGRATE] superResolution.technology → interception.postInterceptor.superResolution.preferredTechnology\n");
            } else {
                props.setProperty("interception.postInterceptor.superResolution.preferredTechnology", "auto");
            }
        }

        if (!props.containsKey("interception.postInterceptor.superResolution.qualityMode")) {
            String oldQuality = props.getProperty("superResolution.quality");
            if (oldQuality != null) {
                props.setProperty("interception.postInterceptor.superResolution.qualityMode",
                               oldQuality.toLowerCase());
                log.append("[MIGRATE] superResolution.quality → interception.postInterceptor.superResolution.qualityMode\n");
            } else {
                props.setProperty("interception.postInterceptor.superResolution.qualityMode", "balanced");
            }
        }

        // 渲染比例和锐化强度使用默认值（v5.0 中没有这些配置）
        addIfAbsent(props, log, "interception.postInterceptor.superResolution.renderScale", "0.667");
        addIfAbsent(props, log, "interception.postInterceptor.superResolution.sharpening", "0.3");
    }

    /**
     * 迁移模组输出重定向器配置（从 sodiumRedirector.* 到 modOutputRedirect.*）
     *
     * <p>在 v5.1 重构中，sodiumRedirector 被重命名为 modOutputRedirect 以支持
     * 更通用的第三方模组输出重定向。此方法确保旧配置文件能够自动迁移。
     *
     * @param props 配置属性
     * @param log 迁移日志
     */
    private static void migrateModOutputRedirectConfig(Properties props, StringBuilder log) {
        // 旧键名 → 新键名 映射表
        String[][] keyMappings = {
            {"interception.sodiumRedirector.enabled",         "interception.modOutputRedirect.enabled"},
            {"interception.sodiumRedirector.supportedVersions", "interception.modOutputRedirect.supportedVersions"},
            {"interception.sodiumRedirector.fallbackMode",      "interception.modOutputRedirect.fallbackMode"}
        };

        for (String[] mapping : keyMappings) {
            String oldKey = mapping[0];
            String newKey = mapping[1];

            // 如果新键名已存在，跳过迁移（用户可能已手动更新）
            if (props.containsKey(newKey)) {
                continue;
            }

            // 检查是否存在旧键名
            String oldValue = props.getProperty(oldKey);
            if (oldValue != null) {
                // 迁移旧值到新键名
                props.setProperty(newKey, oldValue);
                // 移除旧键名（可选，保留以便回滚）
                props.remove(oldKey);
                log.append("[MIGRATE] ").append(oldKey)
                   .append(" → ").append(newKey)
                   .append(" (value: ").append(oldValue).append(")\n");
            }
        }

        // 将 sodiumRedirector 相关键标记为废弃
        String[] deprecatedSodiumKeys = {
            "interception.sodiumRedirector.enabled",
            "interception.sodiumRedirector.supportedVersions",
            "interception.sodiumRedirector.fallbackMode"
        };
        for (String key : deprecatedSodiumKeys) {
            if (props.containsKey(key)) {
                log.append("[DEPRECATED] ").append(key)
                   .append(" is renamed to modOutputRedirect.* in v5.2\n");
            }
        }
    }

    /**
     * 标记废弃字段并输出警告日志
     *
     * @param props 配置属性
     * @param log 迁移日志
     */
    private static void markDeprecatedFields(Properties props, StringBuilder log) {
        // v5.1 废弃的字段列表
        String[] deprecatedKeys = {
            "superResolution.technology",      // 已迁移到 interception.postInterceptor.superResolution
            "superResolution.quality",         // 已迁移到 interception.postInterceptor.superResolution
            "superResolution.enabled"          // 已迁移到 interception.postInterceptor.superResolution
        };

        for (String key : deprecatedKeys) {
            if (props.containsKey(key)) {
                log.append("[DEPRECATED] " + key + " is deprecated in v5.1, ")
                   .append("use interception.postInterceptor.superResolution.* instead\n");
                LOGGER.warning("Deprecated config key detected: " + key);
            }
        }
    }

    /**
     * 如果键不存在则添加默认值
     *
     * @param props 配置属性
     * @param log 迁移日志
     * @param key 配置键
     * @param defaultValue 默认值
     */
    private static void addIfAbsent(Properties props, StringBuilder log, String key, String defaultValue) {
        if (!props.containsKey(key)) {
            props.setProperty(key, defaultValue);
            log.append("[ADD] ").append(key).append(" = ").append(defaultValue).append("\n");
        }
    }

    /**
     * 备份原配置文件
     *
     * @param configFile 原配置文件路径
     */
    private static void backupConfigFile(Path configFile) {
        try {
            Path backupFile = Path.of(configFile.toString() + ".bak." + System.currentTimeMillis());
            Files.copy(configFile, backupFile);
            LOGGER.info("已备份原配置文件到: " + backupFile.getFileName());
        } catch (IOException e) {
            LOGGER.warning("无法备份配置文件: " + e.getMessage());
        }
    }

    /**
     * 比较版本号（简单实现）
     *
     * @param version1 版本1
     * @param version2 版本2
     * @return 如果 version1 比 version2 旧则返回 true
     */
    private static boolean isVersionOlder(String version1, String version2) {
        try {
            double v1 = Double.parseDouble(version1);
            double v2 = Double.parseDouble(version2);
            return v1 < v2;
        } catch (NumberFormatException e) {
            // 无法解析版本号，假设需要迁移
            return true;
        }
    }

    /**
     * 迁移结果数据类
     *
     * <p>封装迁移操作的执行结果，包括成功/失败状态、详细信息和是否实际执行了迁移。
     */
    public static final class MigrationResult {

        /** 是否成功 */
        private final boolean success;

        /** 详细信息或错误消息 */
        private final String message;

        /** 是否实际执行了迁移操作 */
        private final boolean migrated;

        /**
         * 创建迁移结果
         *
         * @param success 是否成功
         * @param message 详细信息
         * @param migrated 是否执行了迁移
         */
        public MigrationResult(boolean success, String message, boolean migrated) {
            this.success = success;
            this.message = message;
            this.migrated = migrated;
        }

        /**
         * 检查迁移是否成功
         *
         * @return true 如果迁移过程没有发生错误
         */
        public boolean isSuccess() { return success; }

        /**
         * 获取迁移详细信息
         *
         * @return 包含所有迁移操作的日志信息
         */
        public String getMessage() { return message; }

        /**
         * 检查是否实际执行了迁移
         *
         * @return true 如果对配置文件进行了修改
         */
        public boolean wasMigrated() { return migrated; }

        @Override
        public String toString() {
            return "MigrationResult{" +
                   "success=" + success +
                   ", migrated=" + migrated +
                   ", message='" + message + '\'' +
                   '}';
        }
    }
}
