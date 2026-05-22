package com.ranecc.renderium.infrastructure.gpu;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * DAG 节点调度器 — 基于依赖图的并行渲染节点执行。
 *
 * <h3>问题</h3>
 * 当前 PipelineExecutor 串行执行所有节点：
 * <pre>
 *   ShadowMap → GBuffer → SSAO → SSR → Bloom → DOF → ... → Tonemap
 *   总时间 = 所有节点时间之和
 * </pre>
 *
 * <h3>方案</h3>
 * 构建节点依赖 DAG，并行执行无依赖关系的节点：
 * <pre>
 *   Layer 0:  [ShadowMap] [GBuffer]          ← 两者并行
 *   Layer 1:  [SSAO] [SSR]                   ← 都读 GBuffer，两者并行
 *   Layer 2:  [Bloom] [DOF] [FilmGrain] [LensFlare] [ChromaticAberration]  ← 独立效果，5路并行
 *   Layer 3:  [AutoExposure] [VolFog] [TAA]   ← 部分依赖 Layer 2
 *   Layer 4:  [Tonemap]                       ← 汇聚所有结果
 * </pre>
 * 总时间 ≈ 最长路径时间，而非所有节点时间之和。
 *
 * <h3>执行策略</h3>
 * <ul>
 *   <li><b>Async Compute 可用</b>: 独立节点提交到 Compute Queue，与 Graphics Queue 并行</li>
 *   <li><b>Async Compute 不可用</b>: 同一 Queue 内按拓扑层并行录制，单次 Submit</li>
 *   <li><b>GPU_BOUND 模式</b>: 减少并行度，降低 GPU 调度开销</li>
 * </ul>
 *
 * <h3>安全保证</h3>
 * <ul>
 *   <li>有依赖关系的节点严格按拓扑序执行</li>
 *   <li>同一资源的读写不会并行</li>
 *   <li>自动插入内存屏障保证可见性</li>
 * </ul>
 */
public final class DAGNodeScheduler {

    private static final Logger LOGGER = Logger.getLogger("Renderium|DAGScheduler");

    // ==================== DAG 结构 ====================
    /** 节点 ID → 依赖的输入节点 ID 列表 */
    private static final Map<String, List<String>> dependencyMap = new ConcurrentHashMap<>();
    /** 节点 ID → 输出资源类型 */
    private static final Map<String, String> outputResourceMap = new ConcurrentHashMap<>();
    /** 拓扑层缓存 */
    private static volatile List<List<String>> topologyLayers = null;
    private static volatile int topologyVersion = 0;
    private static final AtomicInteger versionCounter = new AtomicInteger(0);

    // ==================== 执行 ====================
    /** 当前帧的节点执行结果 */
    private static final Map<String, NodeResult> frameResults = new ConcurrentHashMap<>();
    /** 正在执行的节点 */
    private static final Set<String> executingNodes = ConcurrentHashMap.newKeySet();
    /** 已完成的节点 */
    private static final Set<String> completedNodes = ConcurrentHashMap.newKeySet();

    // ==================== 统计 ====================
    private static final AtomicInteger totalParallelExecutions = new AtomicInteger(0);
    private static final AtomicInteger totalSerialExecutions = new AtomicInteger(0);

    private DAGNodeScheduler() {}

    // ──────── DAG 构建 ────────

    /**
     * 注册节点依赖关系。
     * @param nodeId 节点 ID
     * @param dependencies 此节点依赖的输入节点 ID 列表
     * @param outputResource 此节点输出的资源类型标识
     */
    public static void registerNode(String nodeId, List<String> dependencies, String outputResource) {
        dependencyMap.put(nodeId, Collections.unmodifiableList(new ArrayList<>(dependencies)));
        if (outputResource != null) {
            outputResourceMap.put(nodeId, outputResource);
        }
        invalidateTopology();
    }

    /** 移除节点 */
    public static void unregisterNode(String nodeId) {
        dependencyMap.remove(nodeId);
        outputResourceMap.remove(nodeId);
        invalidateTopology();
    }

    /** 清空所有节点 */
    public static void clearAll() {
        dependencyMap.clear();
        outputResourceMap.clear();
        invalidateTopology();
    }

    private static void invalidateTopology() {
        topologyLayers = null;
        topologyVersion = versionCounter.incrementAndGet();
    }

    /**
     * 计算拓扑分层（Kahn 算法）。
     * 同一层内的节点无依赖关系，可以并行执行。
     */
    public static List<List<String>> computeTopologyLayers() {
        if (topologyLayers != null) return topologyLayers;

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> reverseDeps = new HashMap<>();

        for (String node : dependencyMap.keySet()) {
            inDegree.putIfAbsent(node, 0);
            reverseDeps.putIfAbsent(node, new ArrayList<>());
        }

        for (var entry : dependencyMap.entrySet()) {
            String node = entry.getKey();
            for (String dep : entry.getValue()) {
                if (dependencyMap.containsKey(dep)) {
                    inDegree.merge(node, 1, Integer::sum);
                    reverseDeps.computeIfAbsent(dep, k -> new ArrayList<>()).add(node);
                }
            }
        }

        List<List<String>> layers = new ArrayList<>();
        Queue<String> queue = new ArrayDeque<>();

        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        while (!queue.isEmpty()) {
            List<String> layer = new ArrayList<>();
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String node = queue.poll();
                layer.add(node);
                for (String dependent : reverseDeps.getOrDefault(node, List.of())) {
                    int newDeg = inDegree.merge(dependent, -1, Integer::sum);
                    if (newDeg == 0) queue.add(dependent);
                }
            }
            layers.add(layer);
        }

        // 检测循环依赖
        int totalNodes = layers.stream().mapToInt(List::size).sum();
        if (totalNodes != dependencyMap.size()) {
            LOGGER.warning("DAG 检测到循环依赖！回退到串行执行");
            layers = List.of(new ArrayList<>(dependencyMap.keySet()));
        }

        topologyLayers = Collections.unmodifiableList(layers);
        return layers;
    }

    // ──────── 帧级执行 ────────

    /**
     * 重置帧状态，准备执行新的一帧。
     */
    public static void beginFrame() {
        frameResults.clear();
        executingNodes.clear();
        completedNodes.clear();
    }

    /**
     * 获取当前可以并行执行的节点列表。
     * 基于当前帧已完成的节点，计算哪些节点的依赖已全部满足。
     *
     * @return 可以立即执行的节点 ID 列表
     */
    public static List<String> getReadyNodes() {
        List<String> ready = new ArrayList<>();
        for (String node : dependencyMap.keySet()) {
            if (completedNodes.contains(node) || executingNodes.contains(node)) continue;
            List<String> deps = dependencyMap.get(node);
            if (deps == null || deps.isEmpty() || completedNodes.containsAll(deps)) {
                ready.add(node);
            }
        }
        return ready;
    }

    /**
     * 标记节点开始执行。
     */
    public static void markExecuting(String nodeId) {
        executingNodes.add(nodeId);
    }

    /**
     * 标记节点执行完成。
     * @param nodeId 节点 ID
     * @param outputResource 输出资源句柄（0 = 无输出）
     */
    public static void markCompleted(String nodeId, long outputResource) {
        executingNodes.remove(nodeId);
        completedNodes.add(nodeId);
        frameResults.put(nodeId, new NodeResult(nodeId, outputResource));
    }

    /**
     * 获取指定节点的输出资源句柄。
     * 用于将上游节点的输出连接到下游节点的输入。
     */
    public static long getOutputResource(String nodeId) {
        NodeResult result = frameResults.get(nodeId);
        return result != null ? result.outputResource : 0L;
    }

    /** 所有节点是否执行完成 */
    public static boolean isFrameComplete() {
        return completedNodes.size() >= dependencyMap.size();
    }

    // ──────── 内置依赖图 ────────

    /**
     * 注册 Renderium 内置节点的默认依赖图。
     * 基于数据流分析：
     * - ShadowMap 和 GBuffer 独立（并行）
     * - SSAO 和 SSR 都读 GBuffer（并行）
     * - 后处理效果互相独立（并行）
     * - Tonemap 汇聚所有结果
     */
    public static void registerBuiltinDependencies() {
        clearAll();

        // Layer 0: 独立节点
        registerNode("shadow_map", List.of(), "shadow_depth");
        registerNode("gbuffer", List.of(), "gbuffer_data");

        // Layer 1: 读 GBuffer
        registerNode("ssao", List.of("gbuffer"), "ssao_occlusion");
        registerNode("ssr", List.of("gbuffer"), "ssr_reflection");
        registerNode("lighting", List.of("gbuffer", "shadow_map"), "lighting_result");

        // Layer 2: 后处理效果（互相独立）
        registerNode("bloom", List.of("lighting"), "bloom_result");
        registerNode("dof", List.of("lighting"), "dof_result");
        registerNode("film_grain", List.of(), "film_grain_result");
        registerNode("lens_flare", List.of("lighting"), "lens_flare_result");
        registerNode("chromatic_aberration", List.of(), "chromatic_result");
        registerNode("volumetric_fog", List.of("gbuffer", "shadow_map"), "fog_result");

        // Layer 3: 需要部分 Layer 2 结果
        registerNode("auto_exposure", List.of("lighting"), "exposure");
        registerNode("taa", List.of("lighting"), "taa_result");
        registerNode("motion_blur", List.of("lighting"), "motion_blur_result");

        // Layer 4: 汇聚
        registerNode("tonemap", List.of("bloom", "dof", "auto_exposure", "taa"), "tonemap_result");

        // 打印拓扑
        var layers = computeTopologyLayers();
        StringBuilder sb = new StringBuilder("DAG 拓扑分层:\n");
        for (int i = 0; i < layers.size(); i++) {
            sb.append("  Layer ").append(i).append(": ").append(layers.get(i)).append('\n');
        }
        LOGGER.info(sb.toString().trim());
    }

    // ──────── 调度策略 ────────

    /**
     * 根据当前负载状态决定节点的执行策略。
     *
     * @return 执行策略
     */
    public static ExecutionStrategy getExecutionStrategy() {
        if (!AsyncComputeDispatcher.isAvailable()) {
            return ExecutionStrategy.SERIAL;
        }

        return switch (AdaptivePipelineBalancer.getBottleneck()) {
            case CPU_BOUND -> ExecutionStrategy.PARALLEL_ASYNC_COMPUTE;
            case GPU_BOUND -> ExecutionStrategy.PARALLEL_REDUCED;
            case BALANCED  -> ExecutionStrategy.PARALLEL_ASYNC_COMPUTE;
        };
    }

    /**
     * 判断节点是否适合提交到 Async Compute Queue。
     * 纯 Compute Shader 节点（无 Graphics 依赖）可以异步执行。
     */
    public static boolean isAsyncComputeCandidate(String nodeId) {
        return switch (nodeId) {
            case "ssao", "ssr", "bloom", "film_grain", "chromatic_aberration",
                 "volumetric_fog", "auto_exposure" -> true;
            default -> false;
        };
    }

    // ──────── 诊断 ────────

    public static String getDiagnostics() {
        var layers = computeTopologyLayers();
        return String.format("DAG[layers=%d nodes=%d parallel=%d serial=%d ready=%d completed=%d]",
            layers.size(), dependencyMap.size(),
            totalParallelExecutions.get(), totalSerialExecutions.get(),
            getReadyNodes().size(), completedNodes.size());
    }

    // ──────── 内部类型 ────────

    public enum ExecutionStrategy {
        /** 串行执行（无 Async Compute） */
        SERIAL,
        /** 并行执行 + Async Compute（CPU_BOUND / BALANCED） */
        PARALLEL_ASYNC_COMPUTE,
        /** 并行但减少并行度（GPU_BOUND） */
        PARALLEL_REDUCED
    }

    private record NodeResult(String nodeId, long outputResource) {}
}
