// Renderium - Blaze3D 优化器插件系统
// 插件加载器 - 负责发现、加载、管理插件

package com.ranecc.renderium.presentation.plugin;
import com.ranecc.renderium.presentation.plugin.streamline.StreamlineFrameData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Blaze3D 优化器插件加载器
 * <p>
 * 负责插件的完整生命周期管理，包括发现、版本匹配、安全检查、初始化和应用。
 * 是 Renderium Core 与具体插件实现之间的桥梁。
 *
 * <h2>核心职责：</h2>
 * <ul>
 *   <li>扫描并发现可用插件</li>
 *   <li>根据 MC 版本选择合适的插件</li>
 *   <li>协调安全管理系统进行预检</li>
 *   <li>管理插件的启用/禁用状态</li>
 *   <li>提供统一的优化应用接口</li>
 * </ul>
 *
 * <h2>线程安全：</h2>
 * 此类使用 ConcurrentHashMap 存储已加载插件，
 * 但主要操作应在渲染线程执行以确保一致性。
 *
 * @see Blaze3DOptimizerPlugin
 * @see PluginSafetyManager
 * @author Renderium Team
 * @since 1.0.0
 */
public final class Blaze3DOptimizerPluginLoader {

    private static final Logger LOGGER = Logger.getLogger(Blaze3DOptimizerPluginLoader.class.getName());

    // ==================== 状态字段 ====================

    /** 已加载的插件映射 (pluginId → plugin instance) */
    private final Map<String, Blaze3DOptimizerPlugin> loadedPlugins = new ConcurrentHashMap<>();

    /** 当前活跃的插件（通过版本匹配选中） */
    private volatile Blaze3DOptimizerPlugin activePlugin = null;

    /** 安全管理器引用 */
    private final PluginSafetyManager safetyManager;

    /** 运行时环境信息 */
    private Environment environment;

    /** 插件基础目录 */
    private Path pluginsBaseDir;

    /** 是否已初始化 */
    private boolean initialized = false;

    // ==================== 构造函数 ====================

    /**
     * 创建插件加载器实例
     *
     * @param safetyManager 安全管理器（不能为 null）
     * @throws IllegalArgumentException 如果 safetyManager 为 null
     */
    public Blaze3DOptimizerPluginLoader(PluginSafetyManager safetyManager) {
        this.safetyManager = Objects.requireNonNull(safetyManager, "SafetyManager cannot be null");
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化加载器
     * <p>设置环境信息和插件目录，
     * 准备好进行插件扫描和加载。
     *
     * @param env         当前运行时环境
     * @param pluginsDir  插件存储根目录
     * @return 初始化成功返回 true
     */
    public boolean initialize(Environment env, Path pluginsDir) {
        if (initialized) {
            LOGGER.warning("PluginLoader already initialized");
            return true;
        }

        this.environment = Objects.requireNonNull(env, "Environment cannot be null");
        this.pluginsBaseDir = pluginsDir;

        // 确保插件目录存在
        if (!Files.exists(pluginsDir)) {
            try {
                Files.createDirectories(pluginsDir);
                LOGGER.info("Created plugins directory: " + pluginsDir);
            } catch (Exception e) {
                LOGGER.severe("Failed to create plugins directory: " + e.getMessage());
                return false;
            }
        }

        this.initialized = true;
        LOGGER.info("PluginLoader initialized with env: " + env.getMinecraftVersion());
        return true;
    }

    // ==================== 核心加载流程 ====================

    /**
     * 为当前 MC 版本加载最佳匹配插件
     * <p>这是主要的插件加载入口，执行完整流程：
     * <ol>
     *   <li>扫描可用插件</li>
     *   <li>版本匹配筛选</li>
     *   <li>环境兼容性检查</li>
     *   <li>安全预检</li>
     *   <li>初始化插件</li>
     * </ol>
     *
     * @param context 插件上下文（配置、路径等）
     * @return 加载成功返回插件实例，失败返回 null
     */
    public Blaze3DOptimizerPlugin loadPluginForVersion(PluginContext context) {
        ensureInitialized();

        String mcVersion = environment.getMinecraftVersion();
        LOGGER.info("Loading optimizer plugin for MC version: " + mcVersion);

        // Step 1: 扫描可用插件
        List<Blaze3DOptimizerPlugin> candidates = discoverPlugins();

        if (candidates.isEmpty()) {
            LOGGER.info("No optimizer plugins found");
            return null;
        }

        LOGGER.info("Found " + candidates.size() + " candidate plugin(s)");

        // Step 2: 版本匹配和环境检查
        Blaze3DOptimizerPlugin selected = selectBestPlugin(candidates);

        if (selected == null) {
            LOGGER.warning("No suitable plugin found for MC " + mcVersion);
            return null;
        }

        // Step 3: 安全预检
        if (!safetyManager.preFlightCheck(selected)) {
            LOGGER.warning("Plugin '" + selected.getMetadata().id() + "' failed safety check");
            return null;
        }

        // Step 4: 初始化插件
        if (!selected.initialize(context)) {
            LOGGER.severe("Plugin '" + selected.getMetadata().id() + "' initialization failed");
            return null;
        }

        // Step 5: 注册到加载器
        loadedPlugins.put(selected.getMetadata().id(), selected);
        this.activePlugin = selected;

        PluginMetadata meta = selected.getMetadata();
        LOGGER.info(String.format("✓ Loaded plugin: %s v%s [%s]",
                meta.name(), meta.pluginVersion(), meta.stability().getDisplayName()));

        return selected;
    }

    /**
     * 手动注册插件实例（用于测试或硬编码插件）
     *
     * @param plugin 要注册的插件实例
     * @return 注册成功返回 true
     */
    public boolean registerPlugin(Blaze3DOptimizerPlugin plugin) {
        ensureInitialized();

        Objects.requireNonNull(plugin, "Plugin cannot be null");

        String id = plugin.getMetadata().id();
        if (loadedPlugins.containsKey(id)) {
            LOGGER.warning("Plugin already registered: " + id);
            return false;
        }

        loadedPlugins.put(id, plugin);

        if (activePlugin == null) {
            activePlugin = plugin;
        }

        LOGGER.info("Manually registered plugin: " + id);
        return true;
    }

    // ==================== 优化应用方法 ====================

    /**
     * 安全地应用所有优化
     * <p>创建检查点后尝试应用优化，
     * 失败时自动回滚。
     *
     * @return 应用成功返回 true
     */
    public boolean safelyApplyOptimizations() {
        if (activePlugin == null) {
            LOGGER.warning("No active plugin to apply optimizations");
            return false;
        }

        LOGGER.info("Applying optimizations from: " + activePlugin.getMetadata().name());

        // 创建回滚检查点
        safetyManager.createCheckpoint();

        try {
            // 尝试应用优化
            boolean success = activePlugin.applyOptimizations();

            if (success) {
                // 验证系统稳定性
                if (safetyManager.verifyStability()) {
                    LOGGER.info("✓ Optimizations applied and verified successfully");
                    return true;
                } else {
                    LOGGER.warning("⚠ Optimizations applied but stability verification failed");
                    performRollback();
                    return false;
                }
            } else {
                LOGGER.warning("Plugin reported optimization failure");
                performRollback();
                return false;
            }
        } catch (Exception e) {
            LOGGER.severe("Exception during optimization application: " + e.getMessage());
            performRollback();
            return false;
        }
    }

    /**
     * 使用帧数据评估当前帧（针对 Streamline 集成）
     *
     * @param frameData 帧数据
     * @return 评估成功返回 true
     */
    public boolean evaluateFrame(StreamlineFrameData frameData) {
        if (frameData == null) return false;
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.isAvailable()) return false;
        long device = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return false;
        LOGGER.fine("evaluateFrame: device=0x" + Long.toHexString(device));
        return true;
    }

    // ==================== 卸载与清理 ====================

    /**
     * 卸载指定插件
     *
     * @param pluginId 插件 ID
     * @return 卸载成功返回 true
     */
    public boolean unloadPlugin(String pluginId) {
        Blaze3DOptimizerPlugin plugin = loadedPlugins.remove(pluginId);

        if (plugin == null) {
            LOGGER.warning("Plugin not found for unloading: " + pluginId);
            return false;
        }

        try {
            plugin.shutdown();

            if (activePlugin == plugin) {
                activePlugin = null;
            }

            LOGGER.info("Unloaded plugin: " + pluginId);
            return true;
        } catch (Exception e) {
            LOGGER.severe("Error shutting down plugin " + pluginId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 卸载所有已加载的插件
     */
    public void unloadAll() {
        LOGGER.info("Unloading all plugins (" + loadedPlugins.size() + " total)");

        for (String pluginId : new ArrayList<>(loadedPlugins.keySet())) {
            unloadPlugin(pluginId);
        }

        loadedPlugins.clear();
        activePlugin = null;

        LOGGER.info("All plugins unloaded");
    }

    /**
     * 关闭加载器，释放资源
     */
    public void shutdown() {
        unloadAll();
        this.initialized = false;
        this.environment = null;
        LOGGER.info("PluginLoader shutdown complete");
    }

    // ==================== 查询方法 ====================

    /**
     * 获取当前活跃插件
     *
     * @return 活跃插件实例，可能为 null
     */
    public Blaze3DOptimizerPlugin getActivePlugin() { return activePlugin; }

    /**
     * 获取指定插件
     *
     * @param pluginId 插件 ID
     * @return 插件实例，不存在返回 null
     */
    public Blaze3DOptimizerPlugin getPlugin(String pluginId) { return loadedPlugins.get(pluginId); }

    /**
     * 获取所有已加载插件
     *
     * @return 插件集合的不可变视图
     */
    public Collection<Blaze3DOptimizerPlugin> getAllPlugins() {
        return Collections.unmodifiableCollection(loadedPlugins.values());
    }

    /**
     * 获取已加载插件数量
     *
     * @return 数量
     */
    public int getLoadedCount() { return loadedPlugins.size(); }

    /**
     * 检查是否有活跃插件
     *
     * @return true 如果存在活跃插件
     */
    public boolean hasActivePlugin() { return activePlugin != null; }

    /**
     * 是否已初始化
     *
     * @return 初始化状态
     */
    public boolean isInitialized() { return initialized; }

    // ==================== 内部方法 ====================

    /**
     * 发现可用插件列表
     * <p>通过 Java ServiceLoader 机制 + plugins/ 目录 JAR 扫描发现插件。
     *
     * @return 候选插件列表
     */
    private List<Blaze3DOptimizerPlugin> discoverPlugins() {
        List<Blaze3DOptimizerPlugin> candidates = new ArrayList<>();

        // 方式1: 通过 ServiceLoader 发现已注册的插件实现
        try {
            java.util.ServiceLoader<Blaze3DOptimizerPlugin> serviceLoader =
                    java.util.ServiceLoader.load(Blaze3DOptimizerPlugin.class);
            for (Blaze3DOptimizerPlugin plugin : serviceLoader) {
                candidates.add(plugin);
                LOGGER.fine("[PluginLoader] ServiceLoader 发现插件: " + plugin.getMetadata().id());
            }
        } catch (Exception e) {
            LOGGER.fine("[PluginLoader] ServiceLoader 插件发现失败: " + e.getMessage());
        }

        // 方式2: 扫描 pluginsBaseDir 目录下的 JAR 文件
        if (pluginsBaseDir != null && java.nio.file.Files.exists(pluginsBaseDir)) {
            try (java.util.stream.Stream<java.nio.file.Path> paths =
                         java.nio.file.Files.list(pluginsBaseDir)) {
                paths.filter(p -> p.toString().endsWith(".jar"))
                     .forEach(jarPath -> {
                         try (java.net.URLClassLoader jarLoader =
                                      new java.net.URLClassLoader(
                                              new java.net.URL[]{jarPath.toUri().toURL()},
                                              getClass().getClassLoader())) {
                             java.util.ServiceLoader<Blaze3DOptimizerPlugin> jarServices =
                                     java.util.ServiceLoader.load(
                                             Blaze3DOptimizerPlugin.class, jarLoader);
                             for (Blaze3DOptimizerPlugin plugin : jarServices) {
                                 candidates.add(plugin);
                                 LOGGER.fine("[PluginLoader] JAR 发现插件: " + plugin.getMetadata().id() + " from " + jarPath);
                             }
                         } catch (Exception e) {
                             LOGGER.fine("[PluginLoader] 加载插件 JAR 失败: " + jarPath + " - " + e.getMessage());
                         }
                     });
            } catch (Exception e) {
                LOGGER.fine("[PluginLoader] 扫描插件目录失败: " + e.getMessage());
            }
        }

        LOGGER.fine("[PluginLoader] 插件发现完成: 共 " + candidates.size() + " 个候选");
        return candidates;
    }

    /**
     * 选择最适合当前环境的插件
     * <p>基于版本号和优先级选择。
     *
     * @param candidates 候选列表
     * @return 最佳匹配，无匹配返回 null
     */
    private Blaze3DOptimizerPlugin selectBestPlugin(List<Blaze3DOptimizerPlugin> candidates) {
        return candidates.stream()
                .filter(plugin -> {
                    // 检查环境兼容性
                    if (!plugin.isSupported(environment)) {
                        LOGGER.fine("Plugin " + plugin.getMetadata().id() + " not supported in current environment");
                        return false;
                    }
                    return true;
                })
                .max(Comparator.comparing(p -> p.getMetadata().pluginVersion()))
                .orElse(null);
    }

    /**
     * 执行回滚操作
     */
    private void performRollback() {
        LOGGER.warning("Performing optimization rollback...");
        try {
            safetyManager.rollback();
            LOGGER.info("Rollback completed successfully");
        } catch (Exception e) {
            LOGGER.severe("Rollback failed: " + e.getMessage());
        }
    }

    /**
     * 确保已初始化
     *
     * @throws IllegalStateException 如果未初始化
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("PluginLoader not initialized. Call initialize() first.");
        }
    }
}
