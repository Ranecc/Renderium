// Renderium - 光影系统 v2.0
// 渲染管线节点注册表 - 管理所有渲染节点的生命周期和执行顺序

package com.ranecc.renderium.feature.pipeline.node;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

/**
 * 渲染管线节点注册表（单例）
 * <p>
 * 管理所有可用的渲染节点实例，支持：
 * <ul>
 *   <li>动态注册/注销节点</li>
 *   <li>运行时启用/禁用</li>
 *   <li>基于依赖关系的拓扑排序</li>
 *   <li>分类索引快速查询</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <ul>
 *   <li>使用 ConcurrentHashMap 存储节点和状态</li>
 *   <li>使用 CopyOnWriteArrayList 存储分类索引</li>
 *   <li>读操作无锁，写操作有少量开销</li>
 * </ul>
 *
 * <h3>调用示例：</h3>
 * <pre>
 * PipelineNodeRegistry registry = PipelineNodeRegistry.getInstance();
 *
 * // 注册自定义节点
 * registry.register(new MyCustomNode());
 *
 * // 启用/禁用节点
 * registry.setNodeState("bloom", PipelineNode.State.ENABLED);
 * registry.setNodeState("motion_blur", PipelineNode.State.DISABLED);
 *
 * // 获取拓扑排序后的执行列表
 * List&lt;String&gt; order = registry.getExecutionOrder();
 *
 * // 按分类遍历
 * for (String nodeId : registry.getNodesByCategory(PipelineNode.Category.POST_PROCESS)) {
 *     PipelineNode node = registry.getNode(nodeId);
 *     // ...
 * }
 * </pre>
 *
 * @see PipelineNode
 * @since 2.1.0
 */
public final class PipelineNodeRegistry {

    private static final Logger LOGGER = Logger.getLogger("Renderium|PipelineRegistry");

    /** 单例实例 */
    private static volatile PipelineNodeRegistry INSTANCE;

    /** 所有已注册的节点（按 ID 索引） */
    private final ConcurrentHashMap<String, PipelineNode> nodes = new ConcurrentHashMap<>();

    /** 节点状态映射（独立于节点实例，支持热切换） */
    private final ConcurrentHashMap<String, PipelineNode.State> states = new ConcurrentHashMap<>();

    /** 分类索引（用于快速遍历同类节点） */
    private final EnumMap<PipelineNode.Category, CopyOnWriteArrayList<String>> categoryIndex =
            new EnumMap<>(PipelineNode.Category.class);

    /** 是否已初始化内置节点 */
    private volatile boolean builtinNodesRegistered = false;

    /** 拓扑排序缓存（节点状态不变时复用） */
    private volatile List<String> cachedExecutionOrder = null;

    /** 拓扑排序缓存版本号（状态变更时递增使缓存失效） */
    private volatile long topologyVersion = 0;

    /** 上次缓存时的版本号 */
    private volatile long cachedTopologyVersion = -1;

    /**
     * 私有构造函数
     */
    private PipelineNodeRegistry() {
        // 初始化所有分类的列表
        for (PipelineNode.Category cat : PipelineNode.Category.values()) {
            categoryIndex.put(cat, new CopyOnWriteArrayList<>());
        }
    }

    /**
     * 获取全局单例实例
     *
     * 【返回值】
     * @return PipelineNodeRegistry - 全局唯一实例
     */
    public static synchronized PipelineNodeRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PipelineNodeRegistry();
        }
        return INSTANCE;
    }

    // ==================== 节点注册 API ====================

    /**
     * 注册渲染节点
     * <p>
     * 将节点添加到注册表，默认状态为 ENABLED。
     * 如果已存在相同 ID 的节点，将覆盖旧节点。
     *
     * 【方法参数】
     * @param node PipelineNode - 要注册的节点实例（不能为 null）
     *
     * 【异常】
     * @throws IllegalArgumentException 如果 node 为 null
     */
    public void register(PipelineNode node) {
        if (node == null) {
            throw new IllegalArgumentException("PipelineNode 不能为 null");
        }

        String id = node.getId();
        PipelineNode existing = nodes.put(id, node);
        topologyVersion++;

        // 初始化状态（如果不存在）
        states.computeIfAbsent(id, k -> PipelineNode.State.ENABLED);

        // 更新分类索引
        PipelineNode.Category category = node.getCategory();
        CopyOnWriteArrayList<String> list = categoryIndex.get(category);
        if (list != null && !list.contains(id)) {
            list.add(id);
        }

        if (existing != null) {
            LOGGER.warning(String.format("节点 %s 已被覆盖", id));
        } else {
            LOGGER.fine(String.format("注册节点: %s [%s]", id, node.getDisplayName()));
        }
    }

    /**
     * 注销节点
     * <p>
     * 从注册表中移除节点，并自动调用 dispose() 释放资源。
     *
     * 【方法参数】
     * @param nodeId String - 要移除的节点 ID
     *
     * 【返回值】
     * @return boolean - 是否成功移除（false 表示节点不存在）
     */
    public boolean unregister(String nodeId) {
        PipelineNode node = nodes.remove(nodeId);
        if (node == null) {
            return false;
        }

        states.remove(nodeId);
        topologyVersion++;

        // 从分类索引中移除
        CopyOnWriteArrayList<String> list = categoryIndex.get(node.getCategory());
        if (list != null) {
            list.remove(nodeId);
        }

        // 安全释放资源
        try {
            node.dispose();
        } catch (Exception e) {
            LOGGER.warning(String.format("释放节点 %s 资源时出错: %s", nodeId, e.getMessage()));
        }

        LOGGER.fine(String.format("注销节点: %s", nodeId));
        return true;
    }

    /**
     * 注册所有内置渲染节点
     * <p>
     * 在系统初始化时调用一次。
     * 注册 Renderium 提供的所有原生特效节点。
     */
    public synchronized void registerBuiltinNodes() {
        if (builtinNodesRegistered) {
            return;
        }

        LOGGER.info("开始注册内置渲染节点...");

        // ===== 预计算阶段 (PRE_RENDER) =====
        // 使用反射创建 builtin 包中的节点实例
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.ShadowMapNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.ShadowFilterNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.CascadeSplitNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.HiZBuildNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.OcclusionCullNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.LodCullingNode");

        // ===== G-Buffer 阶段 (GBUFFER) =====
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.GBufferGeometryNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.MaterialNode");

        // ===== 光照阶段 (LIGHTING) =====
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.DirectLightNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.IndirectLightNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.SSAONode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.VolumetricLightNode");

        // ===== 后处理阶段 (POST_PROCESS) =====
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.BloomNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.TonemapNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.ColorCorrectionNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.MotionBlurNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.DepthOfFieldNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.FilmGrainNode");
        registerBuiltinNode("com.ranecc.renderium.feature.pipeline.node.builtin.FXAANode");

        builtinNodesRegistered = true;
        LOGGER.info(String.format("✓ 内置节点注册完成 (共 %d 个)", nodes.size()));
    }

    /**
     * 通过反射创建并注册内置节点
     * <p>
     * 用于从 builtin 子包中加载包级私有的节点类。
     * 如果类不存在或创建失败，记录警告但不中断注册流程。
     *
     * @param className 完整类名（如 "com.ranecc.renderium.feature.pipeline.node.builtin.ShadowMapNode"）
     */
    private void registerBuiltinNode(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            PipelineNode node = (PipelineNode) clazz.getDeclaredConstructor().newInstance();
            register(node);
        } catch (ClassNotFoundException e) {
            LOGGER.warning(String.format("内置节点类未找到: %s (可能尚未实现)", className));
        } catch (Exception e) {
            LOGGER.warning(String.format("无法实例化内置节点: %s - %s", className, e.getMessage()));
        }
    }

    // ==================== 状态管理 API ====================

    /**
     * 设置节点状态
     * <p>
     * 动态切换节点的启用/禁用状态。
     * 可以在运行时调用，无需重启。
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     * @param state PipelineNode.State - 目标状态
     *
     * 【返回值】
     * @return boolean - 是否成功设置（false 表示节点不存在）
     */
    public boolean setNodeState(String nodeId, PipelineNode.State state) {
        if (!nodes.containsKey(nodeId)) {
            LOGGER.warning(String.format("无法设置状态: 节点 %s 不存在", nodeId));
            return false;
        }

        states.put(nodeId, state);
        topologyVersion++;
        LOGGER.config(String.format("节点状态变更: %s → %s", nodeId, state.name()));
        return true;
    }

    /**
     * 获取节点当前状态
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     *
     * 【返回值】
     * @return PipelineNode.State - 当前状态，如果节点不存在返回 DISABLED
     */
    public PipelineNode.State getNodeState(String nodeId) {
        return states.getOrDefault(nodeId, PipelineNode.State.DISABLED);
    }

    /**
     * 检查节点是否已启用
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     *
     * 【返回值】
     * @return boolean - 如果节点存在且状态为 ENABLED 返回 true
     */
    public boolean isEnabled(String nodeId) {
        PipelineNode.State state = states.get(nodeId);
        return state != null && state == PipelineNode.State.ENABLED;
    }

    /**
     * 批量设置节点状态（从配置文件加载时使用）
     *
     * 【方法参数】
     * @param stateMap Map&lt;String, State&gt; - 节点 ID 到状态的映射
     */
    public void batchSetStates(Map<String, PipelineNode.State> stateMap) {
        if (stateMap == null) return;

        stateMap.forEach((id, state) -> {
            if (nodes.containsKey(id)) {
                states.put(id, state);
            }
        });

        topologyVersion++;
        LOGGER.config(String.format("批量更新 %d 个节点状态", stateMap.size()));
    }

    // ==================== 查询 API ====================

    /**
     * 根据ID获取节点实例
     *
     * 【方法参数】
     * @param nodeId String - 节点 ID
     *
     * 【返回值】
     * @return PipelineNode - 节点实例，不存在返回 null
     */
    public PipelineNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * 获取指定分类的所有节点 ID
     *
     * 【方法参数】
     * @param category PipelineNode.Category - 节点分类
     *
     * 【返回值】
     * @return List&lt;String&gt; - 该分类下的节点 ID 列表（不可变副本）
     */
    public List<String> getNodesByCategory(PipelineNode.Category category) {
        CopyOnWriteArrayList<String> list = categoryIndex.get(category);
        return list != null ? List.copyOf(list) : Collections.emptyList();
    }

    /**
     * 获取所有已注册节点的 ID
     *
     * 【返回值】
     * @return Set&lt;String&gt; - 所有节点 ID 的集合
     */
    public Set<String> getAllNodeIds() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * 获取已启用的节点数量
     *
     * 【返回值】
     * @return int - 状态为 ENABLED 的节点数量
     */
    public int getEnabledCount() {
        int count = 0;
        for (PipelineNode.State state : states.values()) {
            if (state == PipelineNode.State.ENABLED) count++;
        }
        return count;
    }

    /**
     * 获取总节点数量
     *
     * 【返回值】
     * @return int - 已注册的节点总数
     */
    public int getTotalCount() {
        return nodes.size();
    }

    // ==================== 执行顺序 API ====================

    /**
     * 按拓扑排序获取可执行节点列表
     * <p>
     * 基于 DAG 依赖关系进行拓扑排序（Kahn 算法），
     * 确保依赖节点先于被依赖节点执行。
     * 同一级别的节点按优先级升序排列。
     *
     * 【返回值】
     * @return List&lt;String&gt; - 排序后的可执行节点 ID 列表
     *
     * 【算法复杂度】
     * - 时间: O(V + E)，V=节点数，E=依赖边数
     * - 空间: O(V)
     *
     * 【注意事项】
     * - 仅包含状态为 ENABLED 的节点
     * - 如果检测到循环依赖，会记录警告并跳过相关节点
     */
    public List<String> getExecutionOrder() {
        // 拓扑排序缓存：节点状态不变时复用执行顺序
        long currentVersion = topologyVersion;
        if (cachedExecutionOrder != null && cachedTopologyVersion == currentVersion) {
            return cachedExecutionOrder;
        }

        // 构建入度表
        Map<String, Integer> inDegree = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        List<String> result = new ArrayList<>();

        // 初始化入度为 0
        for (String id : nodes.keySet()) {
            inDegree.put(id, 0);
        }

        // 计算每个节点的入度（只统计已启用且存在的依赖）
        for (PipelineNode node : nodes.values()) {
            String id = node.getId();
            if (!isEnabled(id)) continue;

            for (String dep : node.getDependencies()) {
                // 只统计存在于注册表中的依赖
                if (nodes.containsKey(dep) && isEnabled(dep)) {
                    inDegree.merge(id, 1, Integer::sum);
                }
            }
        }

        // 入度为 0 的节点入队
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0 && isEnabled(entry.getKey())) {
                queue.offer(entry.getKey());
            }
        }

        // Kahn's algorithm
        while (!queue.isEmpty()) {
            String id = queue.poll();
            result.add(id);

            // 更新依赖此节点的其他节点的入度
            for (PipelineNode other : nodes.values()) {
                String otherId = other.getId();
                if (!isEnabled(otherId)) continue;

                for (String dep : other.getDependencies()) {
                    if (dep.equals(id)) {
                        int newDegree = inDegree.merge(otherId, -1, Integer::sum);
                        if (newDegree == 0) {
                            queue.offer(otherId);
                        }
                        break;
                    }
                }
            }
        }

        // 检查是否所有启用的节点都已排序
        int enabledCount = getEnabledCount();
        if (result.size() < enabledCount) {
            LOGGER.warning(String.format(
                    "检测到可能的循环依赖！已排序: %d/%d 节点",
                    result.size(), enabledCount));
        }

        // 更新缓存
        cachedExecutionOrder = result;
        cachedTopologyVersion = currentVersion;

        return result;
    }

    /**
     * 获取按优先级排序的指定分类节点
     * <p>
     * 在同一分类内按优先级排序（数值越小越先执行）。
     *
     * 【方法参数】
     * @param category PipelineNode.Category - 节点分类
     *
     * 【返回值】
     * @return List&lt;PipelineNode&gt; - 排序后的节点列表
     */
    public List<PipelineNode> getSortedNodesByCategory(PipelineNode.Category category) {
        List<String> ids = getNodesByCategory(category);
        List<PipelineNode> sorted = new ArrayList<>(ids.size());

        for (String id : ids) {
            PipelineNode node = nodes.get(id);
            if (node != null && isEnabled(id)) {
                sorted.add(node);
            }
        }

        // 按优先级排序
        sorted.sort(Comparator.comparingInt(PipelineNode::getPriority));
        return sorted;
    }

    // ==================== 诊断与调试 API ====================

    /**
     * 获取注册表统计信息
     *
     * 【返回值】
     * @return String - 格式化的统计信息字符串
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("PipelineNodeRegistry{total=%d, enabled=%d",
                nodes.size(), getEnabledCount()));

        // 各分类统计
        sb.append(", categories=[");
        for (PipelineNode.Category cat : PipelineNode.Category.values()) {
            List<String> ids = categoryIndex.get(cat);
            long enabledInCat = ids.stream().filter(this::isEnabled).count();
            sb.append(String.format("%s:%d/%d", cat.name(), enabledInCat, ids.size()));
        }
        sb.append("]}");

        return sb.toString();
    }

    /**
     * 导出当前配置（用于保存到文件）
     *
     * 【返回值】
     * @return Map&lt;String, String&gt; - 节点 ID 到状态名称的映射
     */
    public Map<String, String> exportConfiguration() {
        Map<String, String> config = new LinkedHashMap<>();
        states.forEach((id, state) -> config.put(id, state.name()));
        return config;
    }

    /**
     * 重置所有节点到默认状态（全部 ENABLED）
     */
    public void resetToDefaults() {
        for (String id : nodes.keySet()) {
            states.put(id, PipelineNode.State.ENABLED);
        }
        topologyVersion++;
        LOGGER.info("所有节点状态已重置为默认值 (ENABLED)");
    }

    /**
     * 释放所有节点资源并清空注册表
     * <p>
     * 在系统关闭时调用。
     */
    public void disposeAll() {
        LOGGER.info("正在释放所有渲染节点资源...");

        for (Map.Entry<String, PipelineNode> entry : nodes.entrySet()) {
            try {
                entry.getValue().dispose();
            } catch (Exception e) {
                LOGGER.warning(String.format("释放节点 %s 时出错: %s",
                        entry.getKey(), e.getMessage()));
            }
        }

        nodes.clear();
        states.clear();

        for (CopyOnWriteArrayList<String> list : categoryIndex.values()) {
            list.clear();
        }

        builtinNodesRegistered = false;
        LOGGER.info("✓ 所有渲染节点资源已释放");
    }
}
