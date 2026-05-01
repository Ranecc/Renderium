// Renderium - Blaze3D 优化器插件系统
// 插件上下文 - 提供运行时资源和配置访问

package com.ranecc.renderium.presentation.plugin.plugin;

import com.ranecc.renderium.None;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 插件上下文
 * <p>
 * 为插件提供运行时所需的资源和服务访问接口。
 * 插件通过此对象获取配置、日志、文件路径等信息。
 *
 * <h2>提供的功能：</h2>
 * <ul>
 *   <li>访问全局配置</li>
 *   <li>获取插件专属配置</li>
 *   <li>记录日志</li>
 *   <li>访问游戏目录路径</li>
 *   <li>获取原始分配器引用（用于资源池化等优化）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class PluginContext {

    // ==================== 日志器 ====================

    private static final Logger LOGGER = Logger.getLogger(PluginContext.class.getName());

    // ==================== 核心服务引用 ====================

    /** 全局配置实例 */
    private final RenderiumConfig globalConfig;

    /** 游戏运行目录（.minecraft 或服务器根目录） */
    private final Path gameDirectory;

    /** 配置目录（config/） */
    private final Path configDirectory;

    /** 插件数据目录（config/renderium/plugins/{pluginId}/） */
    private final Path pluginDataDir;

    /** 原始图形资源分配器引用（可能为 null） */
    private final Object originalAllocator;

    /** 插件 ID（用于加载插件专属配置） */
    private final String pluginId;

    /** 插件专属配置（惰性加载） */
    private Properties pluginConfig;

    // ==================== 构造函数 ====================

    /**
     * 创建插件上下文
     *
     * @param globalConfig      全局 Renderium 配置
     * @param gameDirectory     游戏运行目录
     * @param configDirectory   配置目录
     * @param pluginDataDir     插件数据存储目录
     * @param originalAllocator  原始分配器（用于替换优化）
     * @param pluginId          插件唯一标识符（用于加载插件配置）
     */
    public PluginContext(RenderiumConfig globalConfig, Path gameDirectory,
                         Path configDirectory, Path pluginDataDir,
                         Object originalAllocator, String pluginId) {
        this.globalConfig = globalConfig;
        this.gameDirectory = gameDirectory;
        this.configDirectory = configDirectory;
        this.pluginDataDir = pluginDataDir;
        this.originalAllocator = originalAllocator;
        this.pluginId = pluginId;
    }

    // ==================== 配置访问 ====================

    /**
     * 获取全局配置
     *
     * @return RenderiumConfig 实例，不会为 null
     */
    public RenderiumConfig getConfig() {
        return globalConfig;
    }

    /**
     * 从插件专属配置读取布尔值
     * <p>
     * 配置读取顺序：
     * <ol>
     *   <li>插件专属配置（config/renderium/plugins/{pluginId}/config.properties）</li>
     *   <li>默认值（如果未找到）</li>
     * </ol>
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getPluginConfig().getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    /**
     * 从插件专属配置读取整数值
     * <p>
     * 配置读取顺序：
     * <ol>
     *   <li>插件专属配置</li>
     *   <li>默认值（如果未找到或解析失败）</li>
     * </ol>
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public int getInt(String key, int defaultValue) {
        String value = getPluginConfig().getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LOGGER.warning("配置值解析失败: " + key + "=" + value + ", 使用默认值: " + defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 从插件专属配置读取浮点数
     * <p>
     * 配置读取顺序：
     * <ol>
     *   <li>插件专属配置</li>
     *   <li>默认值（如果未找到或解析失败）</li>
     * </ol>
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public float getFloat(String key, float defaultValue) {
        String value = getPluginConfig().getProperty(key);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                LOGGER.warning("配置值解析失败: " + key + "=" + value + ", 使用默认值: " + defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 加载并返回插件专属配置
     * <p>
     * 采用惰性加载模式，首次访问时从配置文件读取。
     *
     * @return 插件配置 Properties 对象
     */
    private Properties getPluginConfig() {
        if (pluginConfig == null) {
            pluginConfig = new Properties();
            if (pluginId != null && configDirectory != null) {
                Path pluginConfigPath = configDirectory.resolve("renderium")
                    .resolve("plugins")
                    .resolve(pluginId)
                    .resolve("config.properties");
                if (Files.isRegularFile(pluginConfigPath)) {
                    try (Reader reader = Files.newBufferedReader(pluginConfigPath)) {
                        pluginConfig.load(reader);
                    } catch (Exception e) {
                        LOGGER.warning("加载插件配置失败: " + pluginConfigPath + ": " + e.getMessage());
                    }
                }
            }
        }
        return pluginConfig;
    }

    /**
     * 获取字符串配置值
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public String getString(String key, String defaultValue) {
        String value = getPluginConfig().getProperty(key);
        return value != null ? value : defaultValue;
    }

    // ==================== 路径访问 ====================

    /**
     * 获取游戏运行目录
     *
     * @return .minecraft 目录路径
     */
    public Path getGameDirectory() {
        return gameDirectory;
    }

    /**
     * 获取配置目录
     *
     * @return config/ 目录路径
     */
    public Path getConfigDirectory() {
        return configDirectory;
    }

    /**
     * 获取插件数据目录
     * <p>每个插件有独立的子目录用于存储状态和缓存。
     *
     * @return 插件专用数据目录
     */
    public Path getPluginDataDir() {
        return pluginDataDir;
    }

    // ==================== 原始组件访问 ====================

    /**
     * 获取原始图形资源分配器
     * <p>用于实现资源池化等优化时替换原始分配器。
     *
     * @return 分配器实例，可能为 null（如果未初始化）
     */
    public Object getOriginalAllocator() {
        return originalAllocator;
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format("PluginContext{gameDir=%s, pluginDataDir=%s}",
                gameDirectory, pluginDataDir);
    }
}
