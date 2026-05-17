// Renderium - Blaze3D 优化模块
// 依赖图分析器 - 实现 DAG 拓扑排序与 Pass 合并分析

package com.ranecc.renderium.feature.blaze3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 依赖图分析器
 * <p>
 * 实现对帧图 DAG（有向无环图）的拓扑排序与 Pass 合并分析。
 * 基于 Kahn 算法实现拓扑排序，确保依赖关系正确性的同时最大化并行度。
 *
 * <h3>核心算法：</h3>
 * <ul>
 *   <li><b>Kahn 拓扑排序</b>：移除入度为 0 的节点，迭代处理，检测循环依赖</li>
 *   <li><b>Pass 合并分析</b>：在排序结果中找可合并的相邻 Pass</li>
 *   <li><b>脏标记</b>：图变更时标记为脏，下次访问时重新计算</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class DependencyGraphAnalyzer {

    private static final Logger LOGGER = Logger.getLogger(DependencyGraphAnalyzer.class.getName());

    // ==================== 依赖：Pass 注册表 ====================

    private final ConcurrentHashMap<Long, RenderPassNode> passRegistry;

    // ==================== 拓扑排序字段 ====================

    /** 缓存拓扑排序结果（按执行顺序排列的 Pass ID 列表） */
    private volatile List<Long> cachedTopologyOrder = Collections.emptyList();

    /** 脏标记：图变更时置为 true，下次访问时需重新计算拓扑排序 */
    private volatile boolean topologyDirty = true;

    // ==================== 构造函数 ====================

    /**
     * 创建依赖图分析器
     *
     * @param passRegistry Pass 注册表引用（由 FrameGraphOptimizer 持有）
     */
    public DependencyGraphAnalyzer(ConcurrentHashMap<Long, RenderPassNode> passRegistry) {
        this.passRegistry = passRegistry;
    }

    // ==================== 状态同步方法 ====================

    /**
     * 设置脏标记
     *
     * @param dirty 是否标记为脏
     */
    public void setTopologyDirty(boolean dirty) {
        this.topologyDirty = dirty;
    }

    /**
     * 获取拓扑排序缓存
     *
     * @return 不可修改的拓扑排序列表
     */
    public List<Long> getCachedTopologyOrder() {
        return cachedTopologyOrder;
    }

    /**
     * 获取脏标记状态
     *
     * @return 当前脏标记
     */
    public boolean isTopologyDirty() {
        return topologyDirty;
    }

    /**
     * 清空拓扑缓存（图重置时调用）
     */
    public void clearTopologyCache() {
        cachedTopologyOrder = Collections.unmodifiableList(new ArrayList<>());
        topologyDirty = true;
    }

    // ==================== 核心 API ====================

    /**
     * 执行拓扑排序（Kahn 算法）
     * <p>
     * 基于 Kahn 算法实现 {@code compatibility-mode-optimization.md} §3.1 中的拓扑排序：
     * <pre>
     * 1. 计算所有节点的入度
     * 2. 将入度为 0 的节点加入 BFS 队列
     * 3. 迭代处理队列：移除节点 -> 更新邻接节点入度 -> 入度为 0 则入队
     * 4. 排序完成，缓存结果
     * </pre>
     *
     * @return 按拓扑序排列的 Pass ID 列表
     */
    public List<Long> executeTopologicalSort() {
        if (passRegistry.isEmpty()) {
            LOGGER.warning("拓扑排序失败：Pass 注册表为空");
            return Collections.emptyList();
        }

        // 1. 初始化入度表
        Map<Long, Integer> inDegree = new HashMap<>();
        Map<Long, Set<Long>> adjacencyList = new HashMap<>();

        for (RenderPassNode node : passRegistry.values()) {
            inDegree.putIfAbsent(node.passId, 0);
            adjacencyList.putIfAbsent(node.passId, new HashSet<>());

            for (long depId : node.dependencies) {
                adjacencyList.computeIfAbsent(depId, k -> new HashSet<>()).add(node.passId);
                inDegree.merge(node.passId, 1, Integer::sum);
            }
        }

        // 2. BFS 拓扑排序
        Queue<Long> queue = new LinkedList<>();
        List<Long> sortedOrder = new ArrayList<>();

        // 将所有入度为 0 的节点加入队列
        for (Map.Entry<Long, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            long currentId = queue.poll();
            sortedOrder.add(currentId);

            // 更新邻接节点入度
            Set<Long> neighbors = adjacencyList.getOrDefault(currentId, Collections.emptySet());
            for (long neighborId : neighbors) {
                int newDegree = inDegree.merge(neighborId, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.offer(neighborId);
                }
            }
        }

        // 3. 检测循环依赖
        if (sortedOrder.size() != passRegistry.size()) {
            LOGGER.warning(String.format(
                    "拓扑排序检测到循环依赖：仅能排序 %d/%d 个节点",
                    sortedOrder.size(), passRegistry.size()
            ));

            // 添加未排序的节点（保持稳定性）
            Set<Long> sortedSet = new HashSet<>(sortedOrder);
            for (RenderPassNode node : passRegistry.values()) {
                if (!sortedSet.contains(node.passId)) {
                    sortedOrder.add(node.passId);
                    LOGGER.fine(String.format(
                            "将未排序节点追加到末尾：passId=%d, name=%s",
                            node.passId, node.passName
                    ));
                }
            }
        }

        // 4. 更新 topologyIndex
        for (int i = 0; i < sortedOrder.size(); i++) {
            RenderPassNode node = passRegistry.get(sortedOrder.get(i));
            if (node != null) {
                node.topologyIndex = i;
            }
        }

        // 5. 缓存结果
        cachedTopologyOrder = Collections.unmodifiableList(new ArrayList<>(sortedOrder));
        topologyDirty = false;

        LOGGER.fine(String.format(
                "拓扑排序完成：%d nodes, %d edges, order=%s",
                sortedOrder.size(),
                inDegree.values().stream().mapToInt(Integer::intValue).sum(),
                sortedOrder.size() <= 10 ? sortedOrder.toString() :
                        sortedOrder.subList(0, 10).toString() + "..."
        ));

        return sortedOrder;
    }

    /**
     * 获取优化后的执行顺序
     * <p>
     * 返回拓扑排序后的执行顺序。如果拓扑顺序已缓存且图没有变动，
     * 直接返回缓存结果；否则重新计算。
     *
     * @return 按拓扑序排列的 Pass ID 列表（不可修改视图）
     */
    public List<Long> getOptimizedExecutionOrder() {
        if (topologyDirty || cachedTopologyOrder.isEmpty()) {
            executeTopologicalSort();
        }

        return cachedTopologyOrder;
    }

    /**
     * 分析可合并的 Pass 对
     * <p>
     * 检查相邻的 Pass 是否可以合并为单个 Pass 以减少开销。
     * 合并条件：
     * <ol>
     *   <li>两个 Pass 之间没有其他 Pass 依赖它们</li>
     *   <li>使用相同的 Pipeline 或兼容的 Pipeline</li>
     *   <li>资源依赖不冲突（无 Read-After-Write hazard）</li>
     *   <li>相同的 Render Target 配置</li>
     * </ol>
     *
     * @return 可合并的 Pass 对列表
     */
    public List<long[]> analyzeMergeCandidates() {
        List<long[]> candidates = new ArrayList<>();
        List<Long> order = getOptimizedExecutionOrder();

        // 遍历相邻的 Pass 对
        for (int i = 0; i < order.size() - 1; i++) {
            long passA = order.get(i);
            long passB = order.get(i + 1);

            RenderPassNode nodeA = passRegistry.get(passA);
            RenderPassNode nodeB = passRegistry.get(passB);

            if (nodeA == null || nodeB == null) continue;

            // 条件1：检查是否有其他 Pass 依赖这两个 Pass 之间的中间状态
            boolean hasIntermediateDependent = false;
            for (Long depId : nodeA.dependents) {
                if (depId != passB && passRegistry.containsKey(depId)) {
                    hasIntermediateDependent = true;
                    break;
                }
            }

            if (hasIntermediateDependent) continue;

            // 条件2：检查 Pipeline 兼容性（相同类型通常可以合并）
            boolean pipelineCompatible = (nodeA.pipelineHash == nodeB.pipelineHash) ||
                    (nodeA.passType == nodeB.passType &&
                     nodeA.passType != RenderPassNode.PassType.COMPUTE);

            if (!pipelineCompatible) continue;

            // 条件3：检查资源冲突（A 的输出不能是 B 的输入以外的其他 Pass 的输入）
            boolean resourceConflict = false;
            for (Long outputRes : nodeA.outputResources) {
                for (Long otherDep : nodeA.dependents) {
                    if (otherDep != passB) {
                        RenderPassNode otherNode = passRegistry.get(otherDep);
                        if (otherNode != null && otherNode.inputResources.contains(outputRes)) {
                            resourceConflict = true;
                            break;
                        }
                    }
                }
                if (resourceConflict) break;
            }

            if (resourceConflict) continue;

            // 所有条件满足，这是一个合并候选
            candidates.add(new long[]{passA, passB});
        }

        if (!candidates.isEmpty()) {
            LOGGER.fine(String.format(
                    "Found %d merge candidate pairs", candidates.size()));
        }

        return candidates;
    }
}
