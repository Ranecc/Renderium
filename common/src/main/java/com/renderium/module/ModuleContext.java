// Renderium - 模块系统
// 模块上下文 - 提供运行时资源和API访问

package com.renderium.module;

import com.renderium.config.RenderiumConfig;

import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 模块上下文
 * <p>
 * 为模块提供运行时所需的资源和服务访问接口。
 * 每个模块在初始化时接收此对象，用于：
 * <ul>
 *   <li>访问全局配置</li>
 *   <li>获取文件路径</li>
 *   <li>查询其他模块状态</li>
 *   <li>记录日志</li>
 * </ul>
 *
 * <h2>设计参考：</h2>
 * Sodium 使用 `SodiumClientMod.options()` 静态方法访问配置，
 * Renderium 采用更规范的依赖注入方式。
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public final class ModuleContext {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(ModuleContext.class.getName());

    // ==================== 核心服务引用 ====================

    /** 全局配置实例 */
    private final RenderiumConfig config;

    /** 模块注册表引用（用于查询其他模块） */
    private final ModuleRegistry registry;

    /** 游戏运行目录（.minecraft 或服务器根目录） */
    private final Path gameDirectory;

    /** 配置目录（config/） */
    private final Path configDirectory;

    /** 当前运行模式（兼容/狂暴） */
    private final String mode;

    // ==================== 构造函数 ====================

    /**
     * 创建模块上下文
     *
     * @param config         全局配置
     * @param registry       模块注册表
     * @param gameDirectory  游戏运行目录
     * @param configDirectory 配置目录
     * @param mode           运行模式 ("compatible" / "aggressive")
     */
    public ModuleContext(RenderiumConfig config, ModuleRegistry registry,
                          Path gameDirectory, Path configDirectory, String mode) {
        this.config = config;
        this.registry = registry;
        this.gameDirectory = gameDirectory;
        this.configDirectory = configDirectory;
        this.mode = mode;
    }

    // ==================== 配置访问 ====================

    /**
     * 获取全局配置实例
     *
     * @return RenderiumConfig，不会为 null
     */
    public RenderiumConfig getConfig() { return config; }

    /**
     * 从全局配置读取布尔值
     *
     * @param key          配置键（如 "sodiumLike.enabled"）
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        try {
            String value = config.getProperty(key, null);
            if (value != null) {
                return Boolean.parseBoolean(value);
            }
        } catch (Exception e) {
            LOGGER.warning("配置值读取失败: " + key + ", 使用默认值: " + defaultValue + ", 原因: " + e.getMessage());
        }
        return defaultValue;
    }

    /**
     * 从全局配置读取整数值
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public int getInt(String key, int defaultValue) {
        try {
            String value = config.getProperty(key, null);
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("配置值解析失败: " + key + ", 使用默认值: " + defaultValue);
        } catch (Exception e) {
            LOGGER.warning("配置值读取失败: " + key + ", 使用默认值: " + defaultValue + ", 原因: " + e.getMessage());
        }
        return defaultValue;
    }

    /**
     * 从全局配置读取浮点数
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public float getFloat(String key, float defaultValue) {
        try {
            String value = config.getProperty(key, null);
            if (value != null) {
                return Float.parseFloat(value);
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("配置值解析失败: " + key + ", 使用默认值: " + defaultValue);
        } catch (Exception e) {
            LOGGER.warning("配置值读取失败: " + key + ", 使用默认值: " + defaultValue + ", 原因: " + e.getMessage());
        }
        return defaultValue;
    }

    /**
     * 从全局配置读取字符串值
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public String getString(String key, String defaultValue) {
        try {
            return config.getProperty(key, defaultValue);
        } catch (Exception e) {
            LOGGER.warning("配置值读取失败: " + key + ", 使用默认值: " + defaultValue + ", 原因: " + e.getMessage());
            return defaultValue;
        }
    }

    // ==================== 模块间通信 ====================

    /**
     * 获取已注册的其他模块
     *
     * @param moduleId 目标模块 ID
     * @return Optional 包含模块实例，不存在返回空
     */
    public Optional<RenderiumModule> getModule(String moduleId) {
        return registry.getModule(moduleId);
    }

    /**
     * 检查指定模块是否已启用
     *
     * @param moduleId 模块 ID
     * @return true 如果模块存在且处于 ENABLED 状态
     */
    public boolean isModuleEnabled(String moduleId) {
        return registry.isModuleEnabled(moduleId);
    }

    /**
     * 检查当前是否为狂暴模式
     *
     * @return true 如果是 "aggressive" 模式
     */
    public boolean isAggressiveMode() { return "aggressive".equals(mode); }

    /**
     * 检查当前是否为兼容模式
     *
     * @return true 如果是 "compatible" 模式
     */
    public boolean isCompatibleMode() { return "compatible".equals(mode); }

    // ==================== 路径访问 ====================

    /**
     * 获取游戏运行目录
     *
     * @return .minecraft 目录路径
     */
    public Path getGameDirectory() { return gameDirectory; }

    /**
     * 获取配置目录
     *
     * @return config/ 目录路径
     */
    public Path getConfigDirectory() { return configDirectory; }

    // ==================== Vulkan 设备访问 ====================

    /** Vulkan VMA 分配器句柄 (由后端初始化时注入) */
    private volatile long vmaAllocator = 0L;

    /** 物理设备内存属性 (由后端初始化时注入) */
    private volatile Object physicalDeviceMemoryProperties = null;

    /** VkDevice 句柄 (由后端初始化时注入) */
    private volatile long vkDevice = 0L;

    /** GpuDevice 实例 (由后端初始化时注入) */
    private volatile Object gpuDevice = null;

    /**
     * 获取 VMA 分配器句柄
     *
     * @return VmaAllocator handle, 或 0L 表示未初始化
     */
    public long getVmaAllocator() { return vmaAllocator; }

    /**
     * 设置 VMA 分配器句柄
     *
     * @param allocator VMA 分配器句柄
     */
    public void setVmaAllocator(long allocator) { this.vmaAllocator = allocator; }

    /**
     * 获取物理设备内存属性
     *
     * @return 物理设备内存属性对象
     */
    public Object getPhysicalDeviceMemoryProperties() { return physicalDeviceMemoryProperties; }

    /**
     * 设置物理设备内存属性
     *
     * @param memProps 内存属性对象
     */
    public void setPhysicalDeviceMemoryProperties(Object memProps) { this.physicalDeviceMemoryProperties = memProps; }

    /**
     * 获取 VkDevice 句柄
     *
     * @return Vulkan 设备句柄
     */
    public long getVkDevice() { return vkDevice; }

    /**
     * 设置 VkDevice 句柄
     *
     * @param device Vulkan 设备句柄
     */
    public void setVkDevice(long device) { this.vkDevice = device; }

    /**
     * 获取 GpuDevice 实例
     *
     * @return Blaze3D GpuDevice 对象
     */
    public Object getGpuDevice() { return gpuDevice; }

    /**
     * 设置 GpuDevice 实例
     *
     * @param device GpuDevice 对象
     */
    public void setGpuDevice(Object device) { this.gpuDevice = device; }

    /**
     * 获取模块专属数据目录
     * <p>每个模块有独立的子目录用于存储缓存和状态。
     *
     * @param moduleId 模块 ID
     * @return 模块数据目录路径（如 config/renderium/modules/sodium-like/）
     */
    public Path getModuleDataDir(String moduleId) {
        return configDirectory.resolve("renderium").resolve("modules").resolve(moduleId);
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format("ModuleContext{mode=%s, gameDir=%s}", mode, gameDirectory);
    }
}
