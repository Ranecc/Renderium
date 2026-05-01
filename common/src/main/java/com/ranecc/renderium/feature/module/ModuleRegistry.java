// Renderium - 模块系统
// 模块注册表 - 管理所有功能模块的生命周期

package com.ranecc.renderium.feature.module;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 模块注册表
 * <p>
 * Renderium 模块系统的核心管理器，负责：
 * <ul>
 *   <li>模块注册与发现</li>
 *   <li>依赖解析与加载顺序</li>
 *   <li>生命周期管理</li>
 *   <li>模块间通信协调</li>
 * </ul>
 *
 * <h2>设计参考：</h2>
 * <ul>
 *   <li>Sodium: 静态单例 + 延迟初始化</li>
 *   <li>Fabric Mod API: ModContainer 管理</li>
 *   <li>OSGi: 服务注册表</li>
 * </ul>
 *
 * <h2>使用示例：</h2>
 * <pre>{@code
 * // 1. 创建注册表
 * ModuleRegistry registry = new ModuleRegistry();
 *
 * // 2. 注册所有模块
 * registry.register(new SodiumLikeModule());
 * registry.register(new Blaze3DOptimizerModule());
 * registry.register(new RGBShaderModule());
 *
 * // 3. 初始化并启用
 * registry.initializeAll(context);
 * registry.enableAll();
 *
 * // 4. 运行时查询
 * Optional<RenderiumModule> module = registry.getModule("sodium-like-renderer");
 * }</pre>
 *
 * @see RenderiumModule
 * @author Renderium Team
 * @since 2.0.0
 */
public final class ModuleRegistry {

    private static final Logger LOGGER = Logger.getLogger(ModuleRegistry.class.getName());

    // ==================== 单例支持 ====================

    /** 全局唯一实例 */
    private static volatile ModuleRegistry instance;

    /**
     * 获取全局注册表实例
     *
     * @return ModuleRegistry 单例
     */
    public static synchronized ModuleRegistry getInstance() {
        if (instance == null) {
            instance = new ModuleRegistry();
        }
        return instance;
    }

    // ==================== 状态字段 ====================

    /** 已注册的模块映射 (id → module) */
    private final Map<String, RenderiumModule> modules = new ConcurrentHashMap<>();

    /** 模块状态映射 (id → lifecycle) */
    private final Map<String, ModuleLifecycle> states = new ConcurrentHashMap<>();

    /** 上下文实例（initialize 后设置） */
    private volatile ModuleContext context;

    /** 是否已完成初始化 */
    private volatile boolean initialized = false;

    // ==================== 构造函数 ====================

    /**
     * 创建新的模块注册表
     */
    public ModuleRegistry() {
        LOGGER.info("ModuleRegistry created");
    }

    // ==================== 注册方法 ====================

    /**
     * 注册模块
     * <p>将模块添加到注册表中，但不会自动初始化。
     * 必须在 {@link #initializeAll(ModuleContext)} 之前调用。
     *
     * @param module 要注册的模块实例
     * @throws IllegalArgumentException 如果参数为 null 或 ID 冲突
     */
    public void register(RenderiumModule module) {
        Objects.requireNonNull(module, "Module cannot be null");

        String id = module.getMetadata().id();

        if (modules.containsKey(id)) {
            throw new IllegalArgumentException("Module already registered: " + id);
        }

        modules.put(id, module);
        states.put(id, ModuleLifecycle.REGISTERED);

        LOGGER.info(String.format("✓ Registered module: %s (%s) [%s]",
                id,
                module.getMetadata().name(),
                module.getMetadata().category().getDisplayName()
        ));
    }

    /**
     * 批量注册多个模块
     *
     * @param modulesToRegister 模块数组
     */
    public void registerAll(RenderiumModule... modulesToRegister) {
        for (RenderiumModule module : modulesToRegister) {
            register(module);
        }
    }

    /**
     * 注销模块
     * <p>如果模块已初始化或启用，会先执行 dispose。
     *
     * @param moduleId 模块 ID
     * @return 是否成功注销
     */
    public boolean unregister(String moduleId) {
        RenderiumModule module = modules.remove(moduleId);

        if (module == null) {
            LOGGER.warning("Module not found for unregistration: " + moduleId);
            return false;
        }

        // 如果已初始化，先释放资源
        ModuleLifecycle state = states.get(moduleId);
        if (state != null && state.isInitialized()) {
            try {
                module.dispose();
            } catch (Exception e) {
                LOGGER.severe("Error disposing module " + moduleId + ": " + e.getMessage());
            }
        }

        states.remove(moduleId);
        LOGGER.info("Unregistered module: " + moduleId);
        return true;
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化所有已注册模块
     * <p>按照依赖顺序和优先级依次初始化每个模块。
     * 此方法应在游戏早期（post-init）调用。
     *
     * @param ctx 模块上下文
     * @return 成功初始化的模块数量
     */
    public int initializeAll(ModuleContext ctx) {
        this.context = Objects.requireNonNull(ctx, "Context cannot be null");

        if (initialized) {
            LOGGER.warning("ModuleRegistry already initialized");
            return 0;
        }

        List<String> order = resolveLoadOrder();
        int successCount = 0;

        for (String moduleId : order) {
            RenderiumModule module = modules.get(moduleId);
            if (module == null) continue;

            try {
                if (initializeModule(module)) {
                    successCount++;
                }
            } catch (Exception e) {
                LOGGER.severe("Failed to initialize module " + moduleId + ": " + e.getMessage());
                states.put(moduleId, ModuleLifecycle.REGISTERED); // 回退状态
            }
        }

        this.initialized = true;
        LOGGER.info(String.format("Module initialization complete: %d/%d modules ready",
                successCount, order.size()));

        return successCount;
    }

    /**
     * 初始化单个模块
     *
     * @param module 目标模块
     * @return 是否成功
     */
    private boolean initializeModule(RenderiumModule module) {
        String id = module.getMetadata().id();

        // 检查是否可以加载
        if (!module.canLoad(context)) {
            LOGGER.info("Module " + id + " skipped (canLoad returned false)");
            states.put(id, ModuleLifecycle.REGISTERED);
            return false;
        }

        // 执行初始化
        LOGGER.fine("Initializing module: " + id);
        boolean success = module.initialize(context);

        if (success) {
            states.put(id, ModuleLifecycle.INITIALIZED);
            LOGGER.info("✓ Initialized: " + id);
        } else {
            states.put(id, ModuleLifecycle.REGISTERED);
            LOGGER.warning("✗ Initialization failed: " + id);
        }

        return success;
    }

    // ==================== 启用/禁用方法 ====================

    /**
     * 启用所有已初始化的模块
     *
     * @return 成功启用的模块数量
     */
    public int enableAll() {
        ensureInitialized();

        int count = 0;
        for (Map.Entry<String, RenderiumModule> entry : modules.entrySet()) {
            String id = entry.getKey();
            RenderiumModule module = entry.getValue();

            if (states.get(id) == ModuleLifecycle.INITIALIZED) {
                try {
                    if (enableModule(module)) {
                        count++;
                    }
                } catch (Exception e) {
                    LOGGER.severe("Error enabling module " + id + ": " + e.getMessage());
                }
            }
        }

        LOGGER.info("Enabled " + count + " module(s)");
        return count;
    }

    /**
     * 启用指定模块
     *
     * @param moduleId 模块 ID
     * @return 是否成功
     */
    public boolean enableModule(String moduleId) {
        RenderiumModule module = modules.get(moduleId);
        if (module == null) {
            LOGGER.warning("Cannot enable: module not found: " + moduleId);
            return false;
        }
        return enableModule(module);
    }

    /**
     * 内部启用逻辑
     */
    private boolean enableModule(RenderiumModule module) {
        String id = module.getMetadata().id();
        ModuleLifecycle current = states.get(id);

        if (!current.canTransitionTo(ModuleLifecycle.ENABLED)) {
            LOGGER.warning("Cannot enable module " + id + ": invalid state transition from " + current);
            return false;
        }

        boolean success = module.enable();

        if (success) {
            states.put(id, ModuleLifecycle.ENABLED);
            LOGGER.info("✓ Enabled: " + id);
        } else {
            LOGGER.warning("✗ Enable failed: " + id);
        }

        return success;
    }

    /**
     * 禁用指定模块
     *
     * @param moduleId 模块 ID
     * @return 是否成功
     */
    public boolean disableModule(String moduleId) {
        RenderiumModule module = modules.get(moduleId);
        if (module == null) return false;

        ModuleLifecycle current = states.get(moduleId);
        if (!current.canTransitionTo(ModuleLifecycle.DISABLED)) {
            LOGGER.warning("Cannot disable module " + moduleId + ": protected or wrong state");
            return false;
        }

        try {
            module.disable();
            states.put(moduleId, ModuleLifecycle.DISABLED);
            LOGGER.info("Disabled: " + moduleId);
            return true;
        } catch (Exception e) {
            LOGGER.severe("Error disabling module " + moduleId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 禁用所有非核心模块
     */
    public void disableAllNonCore() {
        for (Map.Entry<String, RenderiumModule> entry : modules.entrySet()) {
            if (!entry.getValue().getMetadata().isCoreModule()) {
                disableModule(entry.getKey());
            }
        }
    }

    // ==================== 清理方法 ====================

    /**
     * 销毁所有模块并清空注册表
     */
    public void shutdown() {
        LOGGER.info("Shutting down ModuleRegistry (" + modules.size() + " modules)...");

        // 按逆序销毁
        List<String> order = resolveLoadOrder();
        Collections.reverse(order);

        for (String moduleId : order) {
            RenderiumModule module = modules.get(moduleId);
            if (module != null && states.get(moduleId).isInitialized()) {
                try {
                    module.dispose();
                    LOGGER.fine("Disposed: " + moduleId);
                } catch (Exception e) {
                    LOGGER.severe("Error disposing " + moduleId + ": " + e.getMessage());
                }
            }
        }

        modules.clear();
        states.clear();
        context = null;
        initialized = false;

        LOGGER.info("ModuleRegistry shutdown complete");
    }

    // ==================== 查询方法 ====================

    /**
     * 获取指定模块
     *
     * @param moduleId 模块 ID
     * @return Optional 包含模块实例
     */
    public Optional<RenderiumModule> getModule(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    /**
     * 获取指定模块的状态
     *
     * @param moduleId 模块 ID
     * @return 生命周期状态，未注册返回 UNREGISTERED
     */
    public ModuleLifecycle getModuleState(String moduleId) {
        return states.getOrDefault(moduleId, ModuleLifecycle.UNREGISTERED);
    }

    /**
     * 检查模块是否已启用
     *
     * @param moduleId 模块 ID
     * @return true 如果处于 ENABLED 状态
     */
    public boolean isModuleEnabled(String moduleId) {
        return states.get(moduleId) == ModuleLifecycle.ENABLED;
    }

    /**
     * 获取所有已注册模块
     *
     * @return 不可修改的集合视图
     */
    public Collection<RenderiumModule> getAllModules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    /**
     * 获取所有已启用模块
     *
     * @return 处于 ENABLED 状态的模块列表
     */
    public List<RenderiumModule> getEnabledModules() {
        List<RenderiumModule> enabled = new ArrayList<>();
        for (Map.Entry<String, RenderiumModule> entry : modules.entrySet()) {
            if (states.get(entry.getKey()) == ModuleLifecycle.ENABLED) {
                enabled.add(entry.getValue());
            }
        }
        return enabled;
    }

    /**
     * 获取已注册模块总数
     */
    public int getModuleCount() { return modules.size(); }

    /**
     * 获取已启用模块总数
     */
    public int getEnabledCount() {
        int count = 0;
        for (ModuleLifecycle state : states.values()) {
            if (state == ModuleLifecycle.ENABLED) count++;
        }
        return count;
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() { return initialized; }

    /**
     * 获取上下文实例
     */
    public ModuleContext getContext() { return context; }

    // ==================== 帧回调分发 ====================

    /**
     * 分发帧开始事件给所有已启用模块
     *
     * @param deltaTime 帧间隔（秒）
     */
    public void dispatchFrameBegin(float deltaTime) {
        for (RenderiumModule module : getEnabledModules()) {
            try {
                module.onFrameBegin(deltaTime);
            } catch (Exception e) {
                LOGGER.severe("Error in onFrameBegin for " +
                        module.getMetadata().id() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 分发帧结束事件给所有已启用模块
     */
    public void dispatchFrameEnd() {
        for (RenderiumModule module : getEnabledModules()) {
            try {
                module.onFrameEnd();
            } catch (Exception e) {
                LOGGER.severe("Error in onFrameEnd for " +
                        module.getMetadata().id() + ": " + e.getMessage());
            }
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 模块依赖关系解析异常
     * <p>当模块依赖图存在循环依赖时抛出。
     */
    private static class CircularDependencyException extends RuntimeException {
        /**
         * 创建循环依赖异常
         *
         * @param message 异常信息
         */
        CircularDependencyException(String message) {
            super(message);
        }
    }

    /**
     * 解析模块加载顺序（拓扑排序）
     * <p>基于依赖关系和优先级确定正确的初始化顺序。
     * 使用 Kahn 算法进行拓扑排序，确保依赖模块先于依赖方加载。
     *
     * @return 有序的模块 ID 列表，按依赖顺序排列
     * @throws CircularDependencyException 当检测到循环依赖时
     * @throws IllegalArgumentException 当依赖的模块未注册时
     */
    private List<String> resolveLoadOrder() {
        // 模块数量为 0 时直接返回
        if (modules.isEmpty()) {
            return new ArrayList<>();
        }

        // 构建依赖图：节点 -> 入度计数
        Map<String, Integer> inDegree = new HashMap<>();
        // 构建邻接表：节点 -> 它指向的节点列表（依赖它的模块）
        Map<String, List<String>> adjacencyList = new HashMap<>();

        // 初始化所有节点的入度为 0
        for (String moduleId : modules.keySet()) {
            inDegree.put(moduleId, 0);
            adjacencyList.put(moduleId, new ArrayList<>());
        }

        // 构建图的边：依赖关系 A -> B 表示 B 依赖 A，A 应该先加载
        // 边方向：被依赖者 -> 依赖者
        for (Map.Entry<String, RenderiumModule> entry : modules.entrySet()) {
            String moduleId = entry.getKey();
            RenderiumModule module = entry.getValue();
            List<String> dependencies = module.getMetadata().dependencies();

            for (String dependency : dependencies) {
                // 验证依赖的模块是否已注册
                if (!modules.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            String.format("Module '%s' depends on '%s', but '%s' is not registered",
                                    moduleId, dependency, dependency));
                }

                // 添加边：dependency -> moduleId
                adjacencyList.get(dependency).add(moduleId);
                // 增加 moduleId 的入度
                inDegree.put(moduleId, inDegree.get(moduleId) + 1);
            }
        }

        // Kahn 算法：将所有入度为 0 的节点加入队列
        // 使用优先级队列，入度相同时按优先级排序
        Queue<String> queue = new PriorityQueue<>((a, b) -> {
            int priorityA = modules.get(a).getMetadata().category().getLoadPriority();
            int priorityB = modules.get(b).getMetadata().category().getLoadPriority();
            // 降序排列，高优先级先处理
            return Integer.compare(priorityB, priorityA);
        });

        // 初始化队列：添加入度为 0 的所有节点
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // 存储拓扑排序结果
        List<String> result = new ArrayList<>();

        // 处理队列中的节点
        while (!queue.isEmpty()) {
            String currentModule = queue.poll();
            result.add(currentModule);

            // 遍历所有依赖当前模块的节点
            List<String> dependents = adjacencyList.get(currentModule);
            for (String dependent : dependents) {
                // 减少入度
                int newInDegree = inDegree.get(dependent) - 1;
                inDegree.put(dependent, newInDegree);

                // 如果入度变为 0，加入队列
                if (newInDegree == 0) {
                    queue.offer(dependent);
                }
            }
        }

        // 检测结果：如果结果数量不等于模块总数，说明存在循环依赖
        if (result.size() != modules.size()) {
            // 找出循环依赖的模块
            List<String> cyclicModules = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() > 0) {
                    cyclicModules.add(entry.getKey());
                }
            }

            throw new CircularDependencyException(
                    String.format("Circular dependency detected among modules: %s. " +
                            "Please remove the circular dependency to continue.",
                            String.join(" -> ", cyclicModules)));
        }

        // 记录排序结果
        LOGGER.info(String.format("Module load order resolved (%d modules): %s",
                result.size(), String.join(", ", result)));

        return result;
    }

    /**
     * 确保已初始化
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                    "ModuleRegistry not initialized. Call initializeAll(context) first."
            );
        }
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format("ModuleRegistry{modules=%d, enabled=%d, initialized=%b}",
                modules.size(), getEnabledCount(), initialized);
    }
}
