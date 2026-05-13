// Renderium - 可扩展 Shader 节点系统
// ShaderNodeRegistry - 可扩展节点注册表
//
// 核心功能：
//   1. 插件式节点发现机制（扫描 classpath 或指定目录）
//   2. 运行时动态注册/注销节点
//   3. 节点分类索引（按 Category 快速查询）
//   4. 依赖解析（DAG 构建 + 循环检测）
//   5. 节点版本管理（支持多版本共存）
//
// 架构设计：
//   ┌─────────────────────────────────────────────┐
//   │            ShaderNodeRegistry               │
//   │  ┌─────────────┬────────────────────────┐   │
//   │  │ descriptorMap│  CompDescriptor 索引    │   │
//   │  │ instanceMap  │  PipelineNode 实例缓存  │   │
//   │  │ categoryIndex│  分类快速查询           │   │
//   │  │ versionIndex │  多版本管理             │   │
//   │  │ dependencyDAG│  依赖图 (邻接表)        │   │
//   │  └─────────────┴────────────────────────┘   │
//   └─────────────────────────────────────────────┘

package com.ranecc.renderium.feature.shader.registry;

import com.ranecc.renderium.None;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 可扩展 Shader 节点注册表（单例）
 * <p>
 * 管理 Shader 节点的完整生命周期，包括 .comp 文件的加载、解析、依赖分析和实例化。
 * 支持运行时热插拔、多版本共存和 DAG 拓扑排序。
 *
 * <h2>与 PipelineNodeRegistry 的关系：</h2>
 * <pre>
 * PipelineNodeRegistry          ShaderNodeRegistry
 * ┌──────────────────┐         ┌──────────────────────┐
 * │ 运行时节点实例     │  ←创建── │ .comp 描述符 + 工厂     │
 * │ 状态管理 (启用/禁用)│         │ 分类 / 版本 / 依赖     │
 * │ 拓扑排序执行顺序   │         │ 插件发现 / 热重载      │
 * └──────────────────┘         └──────────────────────┘
 *       ↑ 实例化                       ↑ 加载 .comp
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 获取全局单例
 * ShaderNodeRegistry registry = ShaderNodeRegistry.getInstance();
 *
 * // 从目录扫描并加载所有 .comp 文件
 * registry.scanDirectory(Paths.get("shader_nodes/"));
 *
 * // 动态加载单个 .comp 文件
 * ShaderCompDescriptor desc = registry.loadCompFile(Paths.get("nodes/pbr.comp"));
 *
 * // 按分类查询
 * List&lt;ShaderCompDescriptor&gt; lightingNodes = registry.getDescriptorsByCategory(
 *     PipelineNode.Category.LIGHTING);
 *
 * // 按标签搜索
 * List&lt;ShaderCompDescriptor&gt; pbrNodes = registry.searchByTag("#pbr");
 *
 * // 解析执行顺序（含循环检测）
 * ExecutionPlan plan = registry.resolveExecutionOrder();
 * if (plan.hasCycles()) {
 *     LOGGER.warning("检测到循环依赖: " + plan.getCycleInfo());
 * }
 * </pre>
 *
 * @see ShaderCompDescriptor
 * @see com.renderium.pipeline.node.PipelineNodeRegistry
 * @since 7.0.0
 */
public final class ShaderNodeRegistry {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ShaderNodeRegistry");

    /** 单例实例 */
    private static volatile ShaderNodeRegistry INSTANCE;

    // ==================== 核心存储结构 ====================

    /**
     * 描述符映射（节点 ID -> CompDescriptor）
     * <p>
     * 存储所有已加载的 .comp 文件解析结果。
     * 使用 ConcurrentHashMap 保证线程安全的读写。
     */
    private final ConcurrentHashMap<String, ShaderCompDescriptor> descriptorMap = new ConcurrentHashMap<>();

    /**
     * 已实例化的节点缓存（节点 ID -> PipelineNode）
     * <p>
     * 缓存工厂创建的节点实例，支持单例模式复用。
     */
    private final ConcurrentHashMap<String, PipelineNode> instanceCache = new ConcurrentHashMap<>();

    /**
     * 分类索引（Category -> 节点 ID 列表）
     * <p>
     * 用于按分类快速查询节点，避免全量遍历。
     */
    private final EnumMap<PipelineNode.Category, CopyOnWriteArrayList<String>> categoryIndex =
            new EnumMap<>(PipelineNode.Category.class);

    /**
     * 版本索引（节点 ID -> 版本列表）
     * <p>
     * 支持同一节点的多个版本共存，
     * 格式：ID -> [(version, descriptor), ...]
     */
    private final ConcurrentHashMap<String, NavigableMap<String, ShaderCompDescriptor>> versionIndex = new ConcurrentHashMap<>();

    /**
     * 标签索引（标签 -> 节点 ID 集合）
     * <p>
     * 用于按标签快速搜索节点。
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> tagIndex = new ConcurrentHashMap<>();

    /**
     * 依赖图邻接表（节点 ID -> 依赖的节点 ID 列表）
     * <p>
     * 用于构建 DAG 和拓扑排序。
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> dependencyGraph = new ConcurrentHashMap<>();

    /**
     * 反向依赖图（节点 ID -> 被哪些节点依赖）
     * <p>
     * 用于注销节点时级联通知依赖者。
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> reverseDependencyGraph = new ConcurrentHashMap<>();

    // ==================== 状态与版本控制 ====================

    /** 注册表变更版本号（每次增删操作递增） */
    private final AtomicLong registryVersion = new AtomicLong(0);

    /** 是否已完成内置节点扫描 */
    private volatile boolean builtinScanned = false;

    /** 扫描路径集合 */
    private final Set<Path> scanPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 监听器列表（用于节点变更通知） */
    private final CopyOnWriteArrayList<RegistryChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    // ==================== 内部数据类 ====================

    /**
     * 执行计划（拓扑排序结果）
     * <p>
     * 包含排序后的节点列表和循环依赖检测结果。
     */
    public static final class ExecutionPlan {
        /** 排序后的节点 ID 列表（按执行顺序） */
        private final List<String> orderedNodes;

        /** 检测到的环路信息（空表示无环路） */
        private final List<List<String>> cycles;

        /** 未解析的依赖（依赖的节点不存在） */
        private final Map<String, List<String>> unresolvedDependencies;

        /**
         * 构造执行计划
         *
         * 【参数说明】
         * @param orderedNodes            List&lt;String&gt; - 排序后的节点列表
         * @param cycles                  List&lt;List&lt;String&gt;&gt; - 环路列表
         * @param unresolvedDependencies  Map&lt;String,List&lt;String&gt;&gt; - 未解析依赖
         */
        public ExecutionPlan(List<String> orderedNodes,
                              List<List<String>> cycles,
                              Map<String, List<String>> unresolvedDependencies) {
            this.orderedNodes = orderedNodes != null ? Collections.unmodifiableList(orderedNodes) : Collections.emptyList();
            this.cycles = cycles != null ? Collections.unmodifiableList(cycles) : Collections.emptyList();
            this.unresolvedDependencies = unresolvedDependencies != null ? Collections.unmodifiableMap(unresolvedDependencies) : Collections.emptyMap();
        }

        /** @return List - 排序后的节点 ID 列表 */
        public List<String> getOrderedNodes() { return orderedNodes; }

        /** @return boolean - 是否存在循环依赖 */
        public boolean hasCycles() { return !cycles.isEmpty(); }

        /** @return List - 检测到的环路列表 */
        public List<List<String>> getCycles() { return cycles; }

        /** @return boolean - 是否有未解析的依赖 */
        public boolean hasUnresolved() { return !unresolvedDependencies.isEmpty(); }

        /** @return Map - 未解析的依赖映射 */
        public Map<String, List<String>> getUnresolvedDependencies() { return unresolvedDependencies; }

        /** @return int - 可执行的节点数量 */
        public int getExecutableCount() { return orderedNodes.size(); }

        @Override
        public String toString() {
            return String.format("ExecutionPlan{nodes=%d, cycles=%d, unresolved=%d}",
                    orderedNodes.size(), cycles.size(), unresolvedDependencies.size());
        }
    }

    /**
     * 注册表变更监听器接口
     */
    public interface RegistryChangeListener {
        /**
         * 节点注册回调
         *
         * @param descriptor ShaderCompDescriptor - 新注册的描述符
         */
        void onNodeRegistered(ShaderCompDescriptor descriptor);

        /**
         * 节点注销回调
         *
         * @param nodeId String - 被注销的节点 ID
         */
        void onNodeUnregistered(String nodeId);

        /**
         * 节点更新回调（热重载）
         *
         * @param oldDesc ShaderCompDescriptor - 旧描述符
         * @param newDesc ShaderCompDescriptor - 新描述符
         */
        void onNodeUpdated(ShaderCompDescriptor oldDesc, ShaderCompDescriptor newDesc);
    }

    // ==================== 单例模式 ====================

    /**
     * 私有构造函数
     */
    private ShaderNodeRegistry() {
        // 初始化所有分类索引
        for (PipelineNode.Category cat : PipelineNode.Category.values()) {
            categoryIndex.put(cat, new CopyOnWriteArrayList<>());
        }
    }

    /**
     * 获取全局单例实例
     *
     * 【返回值】
     * @return ShaderNodeRegistry - 全局唯一实例
     */
    public static ShaderNodeRegistry getInstance() {
        if (INSTANCE == null) {
            synchronized (ShaderNodeRegistry.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ShaderNodeRegistry();
                    LOGGER.info("ShaderNodeRegistry 单例已初始化");
                }
            }
        }
        return INSTANCE;
    }

    // ==================== 核心 API：注册与注销 ====================

    /**
     * 注册 Shader 节点描述符
     * <p>
     * 将 .comp 文件解析后的描述符添加到注册表。
     * 自动更新分类索引、版本索引、标签索引和依赖图。
     *
     * <h3>处理流程：</h3>
     * <ol>
     *   <li>验证描述符完整性</li>
     *   <li>检查版本冲突（同一 ID + version 只保留一份）</li>
     *   <li>更新所有索引</li>
     *   <li>构建/更新依赖图</li>
     *   <li>通知监听器</li>
     * </ol>
     *
     * 【方法参数】
     * @param descriptor ShaderCompDescriptor - 要注册的节点描述符（不能为 null）
     *
     * @throws IllegalArgumentException 如果描述符验证失败或为 null
     */
    public void register(ShaderCompDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor 不能为 null");

        // 1. 验证描述符
        List<String> errors = descriptor.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("描述符验证失败: " + String.join(", ", errors));
        }

        String nodeId = descriptor.getMetadata().getId();

        // 2. 检查是否已存在同版本
        ShaderCompDescriptor existing = descriptorMap.get(nodeId);
        if (existing != null && existing.getMetadata().getVersion().equals(descriptor.getMetadata().getVersion())) {
            LOGGER.warning(String.format("节点 %s v%s 已存在，将被覆盖", nodeId, descriptor.getMetadata().getVersion()));
        }

        // 3. 存储描述符
        descriptorMap.put(nodeId, descriptor);

        // 4. 更新分类索引
        updateCategoryIndex(nodeId, descriptor.getMetadata().getCategory());

        // 5. 更新版本索引
        updateVersionIndex(nodeId, descriptor);

        // 6. 更新标签索引
        updateTagIndex(nodeId, descriptor.getTags());

        // 7. 更新依赖图
        updateDependencyGraph(nodeId, descriptor.getDependencies());

        // 8. 递增版本号
        long newVersion = registryVersion.incrementAndGet();

        // 9. 通知监听器
        notifyRegistered(descriptor);

        LOGGER.fine(String.format("注册节点: %s [%s] v%s (registry v%d)",
                nodeId, descriptor.getMetadata().getDisplayName(),
                descriptor.getMetadata().getVersion(), newVersion));
    }

    /**
     * 注销 Shader 节点
     * <p>
     * 从注册表中移除指定节点及其所有版本的描述符。
     * 自动清理所有索引和依赖关系。
     * 如果有其他节点依赖此节点，会记录警告但不会阻止注销。
     *
     * 【方法参数】
     * @param nodeId String - 要移除的节点 ID
     *
     * @return boolean - 是否成功移除（false 表示节点不存在）
     */
    public boolean unregister(String nodeId) {
        ShaderCompDescriptor removed = descriptorMap.remove(nodeId);
        if (removed == null) {
            return false;
        }

        // 清理实例缓存
        instanceCache.remove(nodeId);

        // 清理分类索引
        removeFromCategoryIndex(nodeId, removed.getMetadata().getCategory());

        // 清理版本索引
        versionIndex.remove(nodeId);

        // 清理标签索引
        removeFromTagIndex(nodeId, removed.getTags());

        // 清理依赖图
        removeFromDependencyGraph(nodeId);

        // 递增版本号
        registryVersion.incrementAndGet();

        // 通知监听器
        notifyUnregistered(nodeId);

        LOGGER.fine(String.format("注销节点: %s", nodeId));
        return true;
    }

    /**
     * 更新节点（热重载支持）
     * <p>
     * 用新的描述符替换旧描述符（通常用于 .comp 文件修改后重新加载）。
     * 保持节点 ID 不变，自动清理旧的索引条目并重建。
     *
     * 【方法参数】
     * @param newDescriptor ShaderCompDescriptor - 更新后的描述符
     *
     * @return ShaderCompDescriptor - 被替换的旧描述符，如果不存在返回 null
     */
    public ShaderCompDescriptor update(ShaderCompDescriptor newDescriptor) {
        Objects.requireNonNull(newDescriptor);

        String nodeId = newDescriptor.getMetadata().getId();
        ShaderCompDescriptor oldDescriptor = descriptorMap.get(nodeId);

        // 先注销旧的
        if (oldDescriptor != null) {
            unregister(nodeId);
        }

        // 再注册新的
        register(newDescriptor);

        // 通知更新事件
        if (oldDescriptor != null) {
            notifyUpdated(oldDescriptor, newDescriptor);
        }

        return oldDescriptor;
    }

    // ==================== 核心 API：文件加载 ====================

    /**
     * 从 .comp 文件加载并注册节点
     * <p>
     * 支持 JSON 格式的 .comp 文件。加载后自动调用 register()。
     *
     * <h3>.comp 文件格式：</h3>
     * <p>
     * 文件应为有效的 JSON 格式，符合 {@link ShaderCompDescriptor} 定义的 schema。
     *
     * 【方法参数】
     * @param compPath Path - .comp 文件路径
     *
     * @return ShaderCompDescriptor - 加载并注册后的描述符
     *
     * @throws IOException              如果文件读取失败
     * @throws IllegalArgumentException 如果 JSON 解析失败或内容无效
     */
    public ShaderCompDescriptor loadCompFile(Path compPath) throws IOException {
        if (compPath == null || !Files.exists(compPath)) {
            throw new IllegalArgumentException(".comp 文件不存在: " + compPath);
        }

        if (!compPath.toString().endsWith(".comp")) {
            throw new IllegalArgumentException("文件扩展名必须为 .comp: " + compPath);
        }

        LOGGER.fine(String.format("正在加载 .comp 文件: %s", compPath));

        // 读取文件内容
        String jsonContent = new String(Files.readAllBytes(compPath), "UTF-8");

        // 解析 JSON 为 ShaderCompDescriptor
        ShaderCompDescriptor descriptor = parseJsonToDescriptor(jsonContent, compPath);

        // 设置源文件路径（用于热重载定位）
        // 注意：实际实现中需要通过 Builder 设置 sourcePath

        // 注册到注册表
        register(descriptor);

        LOGGER.info(String.format("成功加载 .comp 文件: %s -> 节点 %s [%s]",
                compPath.getFileName(), descriptor.getMetadata().getId(),
                descriptor.getMetadata().getDisplayName()));

        return descriptor;
    }

    /**
     * 扫描目录中的所有 .comp 文件并批量加载
     * <p>
     * 递归扫描指定目录（包括子目录），查找所有 *.comp 文件并逐一加载。
     * 加载失败的文件会被记录警告但不中断整体流程。
     *
     * 【方法参数】
     * @param directory Path - 要扫描的目录路径
     *
     * @return int - 成功加载的 .comp 文件数量
     *
     * @throws IOException 如果目录读取失败
     */
    public int scanDirectory(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("扫描路径必须是有效目录: " + directory);
        }

        scanPaths.add(directory.toAbsolutePath());

        LOGGER.info(String.format("开始扫描目录: %s", directory));

        int loadedCount = 0;
        int failedCount = 0;

        // 使用 Files.find 递归查找 .comp 文件
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.comp")) {
            for (Path entry : stream) {
                try {
                    loadCompFile(entry);
                    loadedCount++;
                } catch (Exception e) {
                    failedCount++;
                    LOGGER.warning(String.format("加载 .comp 文件失败: %s - %s", entry, e.getMessage()));
                }
            }
        }

        // 递归扫描子目录（限制深度为 3 层，防止过深递归）
        try (DirectoryStream<Path> subDirs = Files.newDirectoryStream(directory, entry ->
                Files.isDirectory(entry) && !Files.isHidden(entry))) {
            for (Path subDir : subDirs) {
                try {
                    loadedCount += scanDirectory(subDir);
                } catch (Exception e) {
                    LOGGER.warning(String.format("扫描子目录失败: %s - %s", subDir, e.getMessage()));
                }
            }
        }

        LOGGER.info(String.format("目录扫描完成: %s (成功=%d, 失败=%d)", directory, loadedCount, failedCount));
        return loadedCount;
    }

    // ==================== 查询 API ====================

    /**
     * 根据 ID 获取节点描述符
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     *
     * @return ShaderCompDescriptor - 描述符，不存在返回 null
     */
    public ShaderCompDescriptor getDescriptor(String nodeId) {
        return descriptorMap.get(nodeId);
    }

    /**
     * 获取指定节点的最新版本描述符
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     *
     * @return ShaderCompDescriptor - 最新版本描述符，不存在返回 null
     */
    public ShaderCompDescriptor getLatestVersion(String nodeId) {
        NavigableMap<String, ShaderCompDescriptor> versions = versionIndex.get(nodeId);
        if (versions == null || versions.isEmpty()) return null;
        return versions.lastEntry().getValue();
    }

    /**
     * 获取指定节点的所有版本
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     *
     * @return Collection - 该节点所有版本的描述符（按版本号升序）
     */
    public Collection<ShaderCompDescriptor> getAllVersions(String nodeId) {
        NavigableMap<String, ShaderCompDescriptor> versions = versionIndex.get(nodeId);
        if (versions == null) return Collections.emptyList();
        return Collections.unmodifiableCollection(versions.values());
    }

    /**
     * 按分类获取所有节点描述符
     *
     * 【方法参数】
     * @param category Category - 节点分类
     *
     * @return List - 该分类下的描述符列表
     */
    public List<ShaderCompDescriptor> getDescriptorsByCategory(PipelineNode.Category category) {
        CopyOnWriteArrayList<String> ids = categoryIndex.get(category);
        if (ids == null || ids.isEmpty()) return Collections.emptyList();

        List<ShaderCompDescriptor> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            ShaderCompDescriptor desc = descriptorMap.get(id);
            if (desc != null) result.add(desc);
        }
        return result;
    }

    /**
     * 按标签搜索节点
     * <p>
     * 支持模糊匹配（不区分大小写）和前缀匹配。
     *
     * 【方法参数】
     * @param tag String - 搜索标签（如 "#pbr", "#shadow"）
     *
     * @return List - 匹配的描述符列表
     */
    public List<ShaderCompDescriptor> searchByTag(String tag) {
        if (tag == null || tag.isEmpty()) return Collections.emptyList();

        String normalizedTag = tag.startsWith("#") ? tag.toLowerCase() : ("#" + tag).toLowerCase();
        Set<ShaderCompDescriptor> results = new LinkedHashSet<>();

        // 精确匹配
        CopyOnWriteArrayList<String> exactMatches = tagIndex.get(normalizedTag);
        if (exactMatches != null) {
            for (String id : exactMatches) {
                ShaderCompDescriptor desc = descriptorMap.get(id);
                if (desc != null) results.add(desc);
            }
        }

        // 模糊匹配（遍历所有标签）
        if (results.isEmpty()) {
            for (Map.Entry<String, ShaderCompDescriptor> entry : descriptorMap.entrySet()) {
                for (String t : entry.getValue().getTags()) {
                    if (t.toLowerCase().contains(normalizedTag.substring(1))) {
                        results.add(entry.getValue());
                        break;
                    }
                }
            }
        }

        return new ArrayList<>(results);
    }

    /**
     * 按关键词搜索节点（搜索 ID、显示名称、描述、标签）
     *
     * 【方法参数】
     * @param keyword String - 搜索关键词
     *
     * @return List - 匹配的描述符列表
     */
    public List<ShaderCompDescriptor> searchByKeyword(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>(descriptorMap.values());
        }

        String lowerKeyword = keyword.toLowerCase();
        List<ShaderCompDescriptor> results = new ArrayList<>();

        for (ShaderCompDescriptor desc : descriptorMap.values()) {
            // 搜索 ID
            if (desc.getMetadata().getId().toLowerCase().contains(lowerKeyword)) {
                results.add(desc);
                continue;
            }
            // 搜索显示名称
            if (desc.getMetadata().getDisplayName().toLowerCase().contains(lowerKeyword)) {
                results.add(desc);
                continue;
            }
            // 搜索描述
            if (desc.getMetadata().getDescription().toLowerCase().contains(lowerKeyword)) {
                results.add(desc);
                continue;
            }
            // 搜索标签
            for (String tag : desc.getTags()) {
                if (tag.toLowerCase().contains(lowerKeyword)) {
                    results.add(desc);
                    break;
                }
            }
        }

        return results;
    }

    /**
     * 获取所有已注册的节点 ID
     *
     * @return Set - 所有节点 ID 的不可变集合
     */
    public Set<String> getAllNodeIds() {
        return Collections.unmodifiableSet(descriptorMap.keySet());
    }

    /**
     * 获取已注册节点总数
     *
     * @return int - 节点总数
     */
    public int getTotalCount() {
        return descriptorMap.size();
    }

    /**
     * 获取当前注册表版本号
     *
     * @return long - 版本号（每次变更递增）
     */
    public long getRegistryVersion() {
        return registryVersion.get();
    }

    // ==================== 依赖分析与执行计划 ====================

    /**
     * 解析执行顺序（基于 DAG 的拓扑排序）
     * <p>
     * 使用 Kahn's algorithm 进行拓扑排序，同时检测循环依赖。
     * 仅包含已注册且存在的节点。
     *
     * <h3>算法复杂度：</h3>
     * <ul>
     *   <li>时间: O(V + E)，V=节点数，E=依赖边数</li>
     *   <li>空间: O(V)</li>
     * </ul>
     *
     * 【返回值】
     * @return ExecutionPlan - 包含排序结果和环路检测信息
     */
    public ExecutionPlan resolveExecutionOrder() {
        List<String> orderedNodes = new ArrayList<>();
        List<List<String>> cycles = new ArrayList<>();
        Map<String, List<String>> unresolved = new LinkedHashMap<>();

        // 构建本地入度表
        Map<String, Integer> inDegree = new HashMap<>();
        Queue<String> queue = new LinkedList<>();

        // 初始化所有节点的入度为 0
        for (String id : descriptorMap.keySet()) {
            inDegree.put(id, 0);
        }

        // 计算每个节点的入度（只统计存在于注册表中的依赖）
        for (Map.Entry<String, ShaderCompDescriptor> entry : descriptorMap.entrySet()) {
            String id = entry.getKey();
            for (String dep : entry.getValue().getDependencies()) {
                if (descriptorMap.containsKey(dep)) {
                    inDegree.merge(id, 1, Integer::sum);
                } else {
                    // 记录未解析的依赖
                    unresolved.computeIfAbsent(id, k -> new ArrayList<>()).add(dep);
                }
            }
        }

        // 入度为 0 的节点入队
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // Kahn's algorithm
        while (!queue.isEmpty()) {
            String id = queue.poll();
            orderedNodes.add(id);

            // 找出所有依赖此节点的节点，减少其入度
            CopyOnWriteArrayList<String> dependents = reverseDependencyGraph.get(id);
            if (dependents != null) {
                for (String dependent : dependents) {
                    if (inDegree.containsKey(dependent)) {
                        int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                        if (newDegree == 0) {
                            queue.offer(dependent);
                        }
                    }
                }
            }
        }

        // 检测环路：如果排序完成的节点数 < 总节点数，说明存在环路
        if (orderedNodes.size() < descriptorMap.size()) {
            // 收集未排序的节点（这些节点可能在环路中）
            Set<String> unsorted = new HashSet<>(descriptorMap.keySet());
            unsorted.removeAll(orderedNodes);

            // 尝试找出具体的环路（DFS 方法）
            cycles = detectCycles(unsorted);
        }

        return new ExecutionPlan(orderedNodes, cycles, unresolved);
    }

    /**
     * 使用 DFS 检测环路
     *
     * @param nodes Set - 可能包含在环路中的节点集合
     * @return List - 检测到的环路列表
     */
    private List<List<String>> detectCycles(Set<String> nodes) {
        List<List<String>> foundCycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        Map<String, String> parent = new HashMap<>();

        for (String node : nodes) {
            if (!visited.contains(node)) {
                List<String> cycle = new ArrayList<>();
                if (dfsDetectCycle(node, visited, recursionStack, parent, cycle)) {
                    foundCycles.add(cycle);
                }
            }
        }

        return foundCycles;
    }

    /**
     * DFS 环路检测辅助方法
     *
     * @param node          String - 当前访问节点
     * @param visited       Set - 已访问集合
     * @param recursionStack Set - 递归栈（当前路径上的节点）
     * @param parent        Map - 父节点映射
     * @param cycle         List - 输出的环路路径
     * @return boolean - 是否检测到环路
     */
    private boolean dfsDetectCycle(String node, Set<String> visited,
                                    Set<String> recursionStack,
                                    Map<String, String> parent,
                                    List<String> cycle) {
        visited.add(node);
        recursionStack.add(node);

        // 遍历邻居（此节点依赖的其他节点）
        CopyOnWriteArrayList<String> deps = dependencyGraph.get(node);
        if (deps != null) {
            for (String neighbor : deps) {
                // 只检查可能存在环路的节点集合内的邻居
                if (!descriptorMap.containsKey(neighbor)) continue;

                if (!visited.contains(neighbor)) {
                    parent.put(neighbor, node);
                    if (dfsDetectCycle(neighbor, visited, recursionStack, parent, cycle)) {
                        return true;
                    }
                } else if (recursionStack.contains(neighbor)) {
                    // 发现环路！回溯构建环路路径
                    cycle.add(neighbor);
                    String current = node;
                    while (current != null && !current.equals(neighbor)) {
                        cycle.add(current);
                        current = parent.get(current);
                    }
                    cycle.add(neighbor);  // 闭合环路
                    Collections.reverse(cycle);
                    return true;
                }
            }
        }

        recursionStack.remove(node);
        return false;
    }

    // ==================== 实例管理 API ====================

    /**
     * 获取或创建节点实例
     * <p>
     * 如果实例缓存中已有该节点的实例则直接返回，
     * 否则需要通过 {@link com.renderium.shader.factory.ShaderNodeFactory} 创建。
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     *
     * @return PipelineNode - 节点实例，不存在返回 null
     */
    public PipelineNode getNodeInstance(String nodeId) {
        // 先从缓存查找
        PipelineNode cached = instanceCache.get(nodeId);
        if (cached != null) {
            return cached;
        }

        // 缓存未命中，返回 null（需要外部通过 Factory 创建）
        return null;
    }

    /**
     * 缓存节点实例
     * <p>
     * 通常由 ShaderNodeFactory 在创建实例后调用。
     *
     * 【方法参数】
     * @param instance PipelineNode - 要缓存的节点实例
     */
    public void cacheInstance(PipelineNode instance) {
        if (instance == null) return;
        instanceCache.put(instance.getId(), instance);
    }

    /**
     * 移除缓存的节点实例
     *
     * @param nodeId String - 节点 ID
     */
    public void removeCachedInstance(String nodeId) {
        PipelineNode removed = instanceCache.remove(nodeId);
        if (removed != null) {
            try {
                removed.dispose();
            } catch (Exception e) {
                LOGGER.warning(String.format("释放节点 %s 实例时出错: %s", nodeId, e.getMessage()));
            }
        }
    }

    // ==================== 监听器管理 ====================

    /**
     * 注册变更监听器
     *
     * @param listener RegistryChangeListener - 监听器实例
     */
    public void addChangeListener(RegistryChangeListener listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    /**
     * 移除变更监听器
     *
     * @param listener RegistryChangeListener - 要移除的监听器
     */
    public void removeChangeListener(RegistryChangeListener listener) {
        changeListeners.remove(listener);
    }

    // ==================== 内部索引维护方法 ====================

    /**
     * 更新分类索引
     */
    private void updateCategoryIndex(String nodeId, PipelineNode.Category category) {
        CopyOnWriteArrayList<String> list = categoryIndex.get(category);
        if (list != null && !list.contains(nodeId)) {
            list.add(nodeId);
        }
    }

    /**
     * 从分类索引中移除
     */
    private void removeFromCategoryIndex(String nodeId, PipelineNode.Category category) {
        CopyOnWriteArrayList<String> list = categoryIndex.get(category);
        if (list != null) {
            list.remove(nodeId);
        }
    }

    /**
     * 更新版本索引
     */
    private void updateVersionIndex(String nodeId, ShaderCompDescriptor descriptor) {
        versionIndex.computeIfAbsent(nodeId, k -> new TreeMap<>())
                .put(descriptor.getMetadata().getVersion(), descriptor);
    }

    /**
     * 更新标签索引
     */
    private void updateTagIndex(String nodeId, List<String> tags) {
        for (String tag : tags) {
            if (tag == null || tag.isEmpty()) continue;
            String normalizedTag = tag.toLowerCase();
            tagIndex.computeIfAbsent(normalizedTag, k -> new CopyOnWriteArrayList<>())
                    .addIfAbsent(nodeId);
        }
    }

    /**
     * 从标签索引中移除
     */
    private void removeFromTagIndex(String nodeId, List<String> tags) {
        for (String tag : tags) {
            if (tag == null || tag.isEmpty()) continue;
            String normalizedTag = tag.toLowerCase();
            CopyOnWriteArrayList<String> list = tagIndex.get(normalizedTag);
            if (list != null) {
                list.remove(nodeId);
                if (list.isEmpty()) {
                    tagIndex.remove(normalizedTag);
                }
            }
        }
    }

    /**
     * 更新依赖图（正向 + 反向）
     */
    private void updateDependencyGraph(String nodeId, List<String> dependencies) {
        // 正向依赖：nodeId -> [dep1, dep2, ...]
        CopyOnWriteArrayList<String> deps = new CopyOnWriteArrayList<>(dependencies);
        dependencyGraph.put(nodeId, deps);

        // 反向依赖：dep -> [nodeId]
        for (String dep : dependencies) {
            reverseDependencyGraph.computeIfAbsent(dep, k -> new CopyOnWriteArrayList<>())
                    .addIfAbsent(nodeId);
        }
    }

    /**
     * 从依赖图中移除
     */
    private void removeFromDependencyGraph(String nodeId) {
        // 移除正向依赖
        dependencyGraph.remove(nodeId);

        // 移除反向依赖
        CopyOnWriteArrayList<String> reverseDeps = reverseDependencyGraph.remove(nodeId);
        if (reverseDeps != null) {
            // 对于每个依赖此节点的节点，从其正向依赖列表中移除此节点
            for (String dependent : reverseDeps) {
                CopyOnWriteArrayList<String> deps = dependencyGraph.get(dependent);
                if (deps != null) {
                    deps.remove(nodeId);
                }
            }
        }
    }

    // ==================== 通知方法 ====================

    private void notifyRegistered(ShaderCompDescriptor descriptor) {
        for (RegistryChangeListener listener : changeListeners) {
            try {
                listener.onNodeRegistered(descriptor);
            } catch (Exception e) {
                LOGGER.warning(String.format("监听器异常 (onNodeRegistered): %s", e.getMessage()));
            }
        }
    }

    private void notifyUnregistered(String nodeId) {
        for (RegistryChangeListener listener : changeListeners) {
            try {
                listener.onNodeUnregistered(nodeId);
            } catch (Exception e) {
                LOGGER.warning(String.format("监听器异常 (onNodeUnregistered): %s", e.getMessage()));
            }
        }
    }

    private void notifyUpdated(ShaderCompDescriptor oldDesc, ShaderCompDescriptor newDesc) {
        for (RegistryChangeListener listener : changeListeners) {
            try {
                listener.onNodeUpdated(oldDesc, newDesc);
            } catch (Exception e) {
                LOGGER.warning(String.format("监听器异常 (onNodeUpdated): %s", e.getMessage()));
            }
        }
    }

    // ==================== JSON 解析占位符 ====================

    /**
     * 将 JSON 字符串解析为 ShaderCompDescriptor
     * <p>
     * 注意：这是一个简化实现。在生产环境中应使用 Gson/Jackson 等成熟库。
     * 此处提供基本的手动解析能力以避免引入额外依赖。
     *
     * 【方法参数】
     * @param jsonContent String - JSON 内容
     * @param sourcePath  Path - 源文件路径
     *
     * @return ShaderCompDescriptor - 解析后的描述符
     */
    private ShaderCompDescriptor parseJsonToDescriptor(String jsonContent, Path sourcePath) {
        // TODO: 在生产环境中替换为 Gson/Jackson 解析
        // 此处提供最小化实现作为占位符
        //
        // 实际实现步骤:
        //   1. 解析 metadata 对象
        //   2. 解析 spirv 对象
        //   3. 解析 parameters 数组
        //   4. 解析 inputs 数组
        //   5. 解析 outputs 数组
        //   6. 解析 dependencies 数组
        //   7. 解析 performanceHints 对象
        //   8. 解析 tags 数组
        //   9. 通过 Builder 组装最终对象

        throw new UnsupportedOperationException(
                "JSON 解析尚未实现，请集成 Gson/Jackson 库后完成 parseJsonToDescriptor() 方法。" +
                "源文件: " + sourcePath);
    }

    // ==================== 诊断与调试 API ====================

    /**
     * 获取注册表统计信息
     *
     * @return String - 格式化的统计字符串
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("ShaderNodeRegistry{total=%d, instances=%d, version=%d",
                descriptorMap.size(), instanceCache.size(), registryVersion.get()));

        // 各分类统计
        sb.append(", categories=[");
        for (PipelineNode.Category cat : PipelineNode.Category.values()) {
            List<String> ids = categoryIndex.get(cat);
            sb.append(String.format("%s:%d", cat.name(), ids != null ? ids.size() : 0));
        }
        sb.append("]");

        // 标签统计
        sb.append(", tags=").append(tagIndex.size());

        // 依赖边统计
        int edgeCount = 0;
        for (CopyOnWriteArrayList<String> deps : dependencyGraph.values()) {
            edgeCount += deps.size();
        }
        sb.append(", depEdges=").append(edgeCount);

        sb.append(", scanPaths=").append(scanPaths.size());

        sb.append("}");
        return sb.toString();
    }

    /**
     * 导出所有描述符的摘要信息（用于调试）
     *
     * @return String - 格式化的摘要文本
     */
    public String dumpDescriptorsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ShaderNodeRegistry Descriptors ==="
        for (Map.Entry<String, ShaderCompDescriptor> entry : descriptorMap.entrySet()) {
            ShaderCompDescriptor d = entry.getValue();
            sb.append(String.format("  [%s] %s v%s | cat=%s pri=%d | params=%d in=%d out=%d deps=%d
",";
                    d.getMetadata().getId(),
                    d.getMetadata().getDisplayName(),
                    d.getMetadata().getVersion(),
                    d.getMetadata().getCategory(),
                    d.getMetadata().getPriority(),
                    d.getParameters().size(),
                    d.getInputs().size(),
                    d.getOutputs().size(),
                    d.getDependencies().size()
            ));
        }
        sb.append("====================================="
        return sb.toString();
    }

    /**
     * 释放所有资源
     * <p>
     * 清空所有缓存、索引和监听器。
     * 在系统关闭时调用。
     */
    public void dispose() {
        LOGGER.info("正在释放 ShaderNodeRegistry 资源...");

        // 释放所有缓存的节点实例
        for (Map.Entry<String, PipelineNode> entry : instanceCache.entrySet()) {
            try {
                entry.getValue().dispose();
            } catch (Exception e) {
                LOGGER.warning(String.format("释放节点 %s 时出错: %s", entry.getKey(), e.getMessage()));
            }
        }

        // 清空所有存储
        descriptorMap.clear();
        instanceCache.clear();
        versionIndex.clear();
        tagIndex.clear();
        dependencyGraph.clear();
        reverseDependencyGraph.clear();
        scanPaths.clear();
        changeListeners.clear();

        for (CopyOnWriteArrayList<String> list : categoryIndex.values()) {
            list.clear();
        }

        registryVersion.set(0);
        builtinScanned = false;

        LOGGER.info("ShaderNodeRegistry 资源已全部释放");
    }
}
