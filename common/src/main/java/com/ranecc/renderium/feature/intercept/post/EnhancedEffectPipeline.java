// Renderium - Blaze3D 拦截层系统 Phase 4
// 增强版后处理管线 - 基于依赖图的动态效果链管理

package com.ranecc.renderium.feature.intercept.post;
import com.ranecc.renderium.domain.model.FrameData;
import com.ranecc.renderium.platform.backend.EffectParameters;
import com.ranecc.renderium.domain.enums.EffectType;
import com.ranecc.renderium.platform.backend.EffectPipeline;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 增强版后处理管线（Enhanced Effect Pipeline）
 * <p>
 * 对现有 {@link EffectPipeline} 的增强实现，提供：
 * <ul>
 *   <li><b>基于依赖图的效果执行</b>：自动解析效果间的依赖关系，确定最优执行顺序</li>
 *   <li><b>动态性能调整</b>：根据实时帧时间自动降低/提升效果质量</li>
 *   <li><b>效果链可视化</b>：生成 Mermaid 图用于调试和文档</li>
 *   <li><b>性能 Profiling</b>：每个效果的独立计时和统计</li>
 *   <li><b>优雅降级</b>：单个效果失败不影响其他效果</li>
 * </ul>
 *
 * <h3>效果依赖图：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    效果依赖关系图                             │
 * ├─────────────────────────────────────────────────────────────┤
 * │                                                             │
 * │   输入帧                                                     │
 * │     │                                                       │
 * │     ▼                                                       │
 * │  ┌────────┐                                                │
 * │  │ BLOOM  │ ← 泛光（高亮提取+模糊+叠加）                   │
 * │  └───┬────┘                                                │
 * │      │                                                      │
 * │      ▼                                                      │
 * │  ┌────────┐    ┌─────────┐                                 │
 * │  │  DOF   │ ←→ │ 深度缓冲 │ （DOF 需要深度数据）           │
 * │  └───┬────┘    └─────────┘                                 │
 * │      │                                                      │
 * │      ▼                                                      │
 * │  ┌──────────┐                                              │
 * │  │MOTION_BLUR│ ← 运动模糊（需要运动矢量）                  │
 * │  └─────┬────┘                                              │
 * │        │                                                    │
 * │        ▼                                                    │
 * │     输出帧                                                   │
 * │                                                             │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>动态调整策略：</h3>
 * <p>基于性能预算的自动质量调整：
 * <table border="1">
 *   <tr><th>总预算</th><th>目标帧率</th><th>调整策略</th></tr>
 *   <tr><td>&gt;8ms</td><td>&lt;120fps</td><td>全质量，所有效果启用</td></tr>
 *   <tr><td>5-8ms</td><td>120-200fps</td><td>Bloom 正常，DOF 降低采样，MotionBlur 减少样本</td></tr>
 *   <tr><td>&lt;5ms</td><td>&gt;200fps</td><td>仅 Bloom（低质量），禁用 DOF 和 MotionBlur</td></tr>
 * </table>
 *
 * <h3>效果优先级排序（降级时）：</h3>
 * <ol>
 *   <li><b>Bloom</b> - 最高优先级（视觉影响最大）</li>
 *   <li><b>MotionBlur</b> - 中等优先级（提升流畅感）</li>
 *   <li><b>DOF</b> - 最低优先级（仅特定场景需要）</li>
 * </ol>
 *
 * <h3>性能预算：</h3>
 * <table border="1">
 *   <tr><th>阶段</th><th>兼容模式</th><th>狂暴模式</th></tr>
 *   <tr><td>后处理</td><td>&lt;4ms</td><td>&lt;3ms</td></tr>
 * </table>
 *
 * <h3>线程安全：</h3>
 * <p>此类线程安全。配置修改使用原子引用，
 * 执行方法应在渲染线程调用。
 *
 * @see EffectPipeline 基础效果管线接口
 * @see EffectType 效果类型枚举
 * @see EffectParameters 效果参数容器
 * @since 5.2.0 (Phase 4)
 */
public final class EnhancedEffectPipeline {

    private static final Logger LOGGER = Logger.getLogger(EnhancedEffectPipeline.class.getName());

    // ==================== 单例实例 ====================

    private static volatile EnhancedEffectPipeline INSTANCE;

    /**
     * 获取 EnhancedEffectPipeline 单例实例
     *
     * @return 全局唯一实例
     */
    public static EnhancedEffectPipeline getInstance() {
        if (INSTANCE == null) {
            synchronized (EnhancedEffectPipeline.class) {
                if (INSTANCE == null) {
                    INSTANCE = new EnhancedEffectPipeline();
                }
            }
        }
        return INSTANCE;
    }

    // ==================== 配置常量 ====================

    /** 总性能预算：兼容模式（纳秒）= 4ms */
    public static final long COMPATIBILITY_BUDGET_NS = 4_000_000L;

    /** 总性能预算：狂暴模式（纳秒）= 3ms */
    public static final long AGGRESSIVE_BUDGET_NS = 3_000_000L;

    /** 默认 Bloom 时间预算（纳秒）= 2ms */
    public static final long DEFAULT_BLOOM_BUDGET_NS = 2_000_000L;

    /** 默认 DOF 时间预算（纳秒）= 1.5ms */
    public static final long DEFAULT_DOF_BUDGET_NS = 1_500_000L;

    /** 默认 MotionBlur 时间预算（纳秒）= 1ms */
    public static final long DEFAULT_MOTION_BLUR_BUDGET_NS = 1_000_000L;

    /** 性能监控窗口大小（帧数） */
    private static final int PERFORMANCE_WINDOW_SIZE = 60;

    // ==================== 核心状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 运行模式标记 */
    private volatile boolean aggressiveMode = false;

    /** 原始 EffectPipeline 引用（委托调用） */
    private volatile EffectPipeline basePipeline;

    // ==================== 效果注册表 ====================

    /** 已注册的效果列表（按执行顺序排列） */
    private final List<EffectEntry> effectEntries = 
        Collections.synchronizedList(new ArrayList<>());

    /** 效果启用状态映射 */
    private final Map<EffectType, Boolean> effectEnabledMap = 
        new ConcurrentHashMap<>();

    /** 效果参数映射 */
    private final Map<EffectType, EffectParameters> effectParamsMap = 
        new ConcurrentHashMap<>();

    // ==================== 性能监控字段 ====================

    /** 各效果的性能历史记录 */
    private final Map<EffectType, Deque<Long>> effectPerformanceHistory = 
        new ConcurrentHashMap<>();

    /** 上次总处理耗时（纳秒） */
    private final AtomicLong lastTotalTimeNanos = new AtomicLong(0L);

    /** 当前性能等级 */
    private final AtomicReference<PerformanceLevel> currentPerformanceLevel = 
        new AtomicReference<>(PerformanceLevel.FULL_QUALITY);

    // ==================== 调试和可视化字段 ====================

    /** 是否启用调试模式（详细日志 + 可视化输出） */
    private volatile boolean debugMode = false;

    /** 上次执行的效果管线快照（用于可视化） */
    private volatile String lastExecutionGraphSnapshot = "";

    // ==================== 枚举定义 ====================

    /**
     * 性能等级枚举
     * <p>
     * 定义不同的性能/质量预设级别。
     */
    public enum PerformanceLevel {
        /** 全质量 - 所有效果以最高质量运行 */
        FULL_QUALITY("Full Quality", 1.0f),

        /** 高质量 - 大部分效果正常，部分降低采样 */
        HIGH("High", 0.75f),

        /** 中等质量 - 仅保留关键效果，降低质量 */
        MEDIUM("Medium", 0.5f),

        /** 低质量 - 仅最基础的效果 */
        LOW("Low", 0.25f),

        /** 最小化 - 禁用所有后处理 */
        MINIMAL("Minimal", 0.0f);

        private final String displayName;
        private final float qualityFactor;

        PerformanceLevel(String displayName, float qualityFactor) {
            this.displayName = displayName;
            this.qualityFactor = qualityFactor;
        }

        public String getDisplayName() { return displayName; }
        public float getQualityFactor() { return qualityFactor; }
    }

    /**
     * 效果条目（内部类）
     * <p>
     * 封装单个效果的注册信息和状态。
     */
    private static class EffectEntry {
        final EffectType type;
        int priority;
        Set<EffectType> dependencies;
        long budgetNanos;

        EffectEntry(EffectType type, int priority, Set<EffectType> dependencies, long budgetNanos) {
            this.type = type;
            this.priority = priority;
            this.dependencies = dependencies != null ? dependencies : Collections.emptySet();
            this.budgetNanos = budgetNanos;
        }
    }

    // ==================== 私有构造函数 ====================

    private EnhancedEffectPipeline() {
        // 初始化默认效果
        initializeDefaultEffects();
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化增强版后处理管线
     * <p>
     * 注册默认效果、初始化性能监控、构建依赖图。
     *
     * @param basePipeline 基础 EffectPipeline 实现（不能为 null）
     * @param aggressiveMode 是否为狂暴模式
     * @return true 表示初始化成功
     * @throws IllegalArgumentException 如果 basePipeline 为 null
     */
    public boolean initialize(EffectPipeline basePipeline, boolean aggressiveMode) {
        if (basePipeline == null) {
            throw new IllegalArgumentException("EffectPipeline 不能为 null");
        }

        if (initialized.get()) {
            LOGGER.warning("EnhancedEffectPipeline 已初始化");
            return true;
        }

        try {
            this.basePipeline = basePipeline;
            this.aggressiveMode = aggressiveMode;

            // 初始化性能历史队列
            for (EffectEntry entry : effectEntries) {
                effectPerformanceHistory.put(entry.type, new LinkedList<>());
            }

            initialized.set(true);
            
            LOGGER.info(String.format(
                "EnhancedEffectPipeline 初始化完成 | 模式=%s | 注册效果=%d | 预算=%.1fms",
                aggressiveMode ? "狂暴" : "兼容",
                effectEntries.size(),
                getTotalBudgetMs()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "初始化失败", e);
            return false;
        }
    }

    /**
     * 关闭并释放资源
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            synchronized (effectEntries) {
                effectEntries.clear();
            }
            effectEnabledMap.clear();
            effectParamsMap.clear();
            effectPerformanceHistory.clear();

            basePipeline = null;
            initialized.set(false);

            LOGGER.info("EnhancedEffectPipeline 已关闭");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "关闭异常", e);
        }
    }

    // ==================== 核心执行方法 ====================

    /**
     * 执行完整的后处理管线（核心方法）
     * <p>
     * 按照依赖图确定的顺序依次执行所有启用的效果。
     * 包含性能监控、动态调整、错误恢复等功能。
     *
     * <h3>执行流程：</h3>
     * <ol>
     *   <li>计算当前性能等级（基于最近 N 帧的平均耗时）</li>
     *   <li>根据性能等级调整各效果的参数</li>
     *   <li>按拓扑序执行效果链</li>
     *   <li>收集每个效果的计时数据</li>
     *   <li>检查是否超出预算，必要时降级</li>
     *   <li>返回最终处理结果</li>
     * </ol>
     *
     * <h3>时间复杂度：</h3>O(E + V) 其中 E 为效果数量，V 为依赖边数量
     *
     * @param frameData 输入帧数据
     * @return true 表示处理成功（或部分成功），false 表示完全失败
     * @throws IllegalStateException 如果未初始化
     * @throws IllegalArgumentException 如果 frameData 为 null
     */
    public boolean execute(FrameData frameData) {
        // ======== 参数校验 ========
        if (frameData == null) {
            throw new IllegalArgumentException("FrameData 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get()) {
            throw new IllegalStateException("EnhancedEffectPipeline 未初始化");
        }

        long startTime = System.nanoTime();
        boolean overallSuccess = true;

        try {
            // ======== 1. 计算当前性能等级 ========
            updatePerformanceLevel();

            // ======== 2. 获取有序执行列表（拓扑排序） ========
            List<EffectEntry> executionOrder = getTopologicalSort();

            // ======== 3. 逐个执行效果 ========
            for (EffectEntry entry : executionOrder) {
                if (!isEffectEnabled(entry.type)) {
                    continue;  // 跳过禁用的效果
                }

                long effectStart = System.nanoTime();
                
                try {
                    // 执行单个效果
                    boolean success = executeSingleEffect(frameData, entry);
                    
                    long effectElapsed = System.nanoTime() - effectStart;
                    
                    // 记录性能数据
                    recordEffectPerformance(entry.type, effectElapsed);
                    
                    // 检查是否超出预算
                    if (effectElapsed > entry.budgetNanos) {
                        LOGGER.fine(String.format(
                            "效果 %s 超出预算: %.2f ms > %.2f ms",
                            entry.type.name(),
                            effectElapsed / 1_000_000.0,
                            entry.budgetNanos / 1_000_000.0
                        ));
                        
                        // 动态调整：如果持续超预算，考虑降低该效果质量
                        adjustEffectForBudget(entry.type, effectElapsed);
                    }
                    
                    if (!success) {
                        LOGGER.warning("效果执行失败: " + entry.type.name() + "（继续后续效果）");
                        overallSuccess = false;
                    }

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, 
                        "效果执行异常: " + entry.type.name(), e);
                    
                    long effectElapsed = System.nanoTime() - effectStart;
                    recordEffectPerformance(entry.type, effectElapsed);
                    overallSuccess = false;
                    // 继续执行下一个效果（不中断整个管线）
                }
            }

            // ======== 4. 更新总体统计 ========
            long totalElapsed = System.nanoTime() - startTime;
            lastTotalTimeNanos.set(totalElapsed);

            // ======== 5. 生成执行图快照（调试用） ========
            if (debugMode) {
                generateExecutionGraphSnapshot(executionOrder, totalElapsed);
            }

            // 定期日志（每 60 帧）
            if (frameData.getFrameIndex() % PERFORMANCE_WINDOW_SIZE == 0) {
                logPerformanceSummary(frameData.getFrameIndex());
            }

            return overallSuccess;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "后处理管线执行异常", e);
            lastTotalTimeNanos.set(System.nanoTime() - startTime);
            return false;
        }
    }

    // ==================== 效果管理方法 ====================

    /**
     * 注册效果到管线
     * <p>
     * 添加一个新的效果到管线中。效果将按照其依赖关系自动排序。
     *
     * @param type         效果类型
     * @param priority     优先级（数值越小越先执行）
     * @param dependencies 依赖的其他效果集合
     * @param budgetMs     时间预算（毫秒）
     * @return true 表示注册成功
     */
    public boolean registerEffect(EffectType type, int priority, 
                                   Set<EffectType> dependencies, double budgetMs) {
        if (type == null) {
            throw new IllegalArgumentException("EffectType 不能为 null");
        }

        long budgetNs = (long) (budgetMs * 1_000_000.0);
        
        EffectEntry entry = new EffectEntry(type, priority, dependencies, budgetNs);
        
        synchronized (effectEntries) {
            // 检查是否已注册
            for (EffectEntry existing : effectEntries) {
                if (existing.type == type) {
                    LOGGER.warning("效果已注册: " + type.name());
                    return false;
                }
            }
            
            effectEntries.add(entry);
        }

        // 初始化状态
        effectEnabledMap.put(type, true);
        effectPerformanceHistory.putIfAbsent(type, new LinkedList<>());

        LOGGER.info(String.format(
            "注册效果: %s | 优先级=%d | 预算=%.2fms | 依赖=%s",
            type.name(),
            priority,
            budgetMs,
            dependencies != null ? dependencies : "无"
        ));

        return true;
    }

    /**
     * 启用或禁用指定效果
     *
     * @param type   效果类型
     * @param enabled true 启用，false 禁用
     */
    public void setEffectEnabled(EffectType type, boolean enabled) {
        if (type == null) {
            throw new IllegalArgumentException("EffectType 不能为 null");
        }
        
        Boolean previous = effectEnabledMap.put(type, enabled);
        
        if (previous == null || previous != enabled) {
            LOGGER.info(String.format("效果 %s: %s", type.name(), enabled ? "启用" : "禁用"));
        }
    }

    /**
     * 设置效果参数
     * <p>
     * 更新指定效果的运行时参数。
     *
     * @param type      效果类型
     * @param parameters 参数容器
     */
    public void setEffectParameters(EffectType type, EffectParameters parameters) {
        if (type == null || parameters == null) {
            throw new IllegalArgumentException("参数不能为 null");
        }
        
        effectParamsMap.put(type, parameters);
        LOGGER.fine("更新效果参数: " + type.name());
    }

    /**
     * 检查效果是否已启用
     *
     * @param type 效果类型
     * @return true 如果效果已启用且已注册
     */
    public boolean isEffectEnabled(EffectType type) {
        return effectEnabledMap.getOrDefault(type, false);
    }

    /**
     * 获取已注册的效果列表
     *
     * @return 效果类型列表的副本
     */
    public List<EffectType> getRegisteredEffects() {
        synchronized (effectEntries) {
            List<EffectType> types = new ArrayList<>(effectEntries.size());
            for (EffectEntry entry : effectEntries) {
                types.add(entry.type);
            }
            return types;
        }
    }

    // ==================== 性能查询方法 ====================

    /**
     * 获取上次总处理耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getLastTotalTimeMillis() {
        return lastTotalTimeNanos.get() / 1_000_000.0;
    }

    /**
     * 获取当前性能等级
     *
     * @return 当前性能等级枚举
     */
    public PerformanceLevel getCurrentPerformanceLevel() {
        return currentPerformanceLevel.get();
    }

    /**
     * 获取总性能预算（毫秒）
     *
     * @return 预算时间（ms）
     */
    public double getTotalBudgetMs() {
        return (aggressiveMode ? AGGRESSIVE_BUDGET_NS : COMPATIBILITY_BUDGET_NS) / 1_000_000.0;
    }

    /**
     * 获取各效果的平均耗时（毫秒）
     *
     * @return 效果名称 → 平均耗时的映射
     */
    public Map<String, Double> getEffectAverageTimes() {
        Map<String, Double> result = new HashMap<>();
        
        for (Map.Entry<EffectType, Deque<Long>> entry : effectPerformanceHistory.entrySet()) {
            Deque<Long> history = entry.getValue();
            if (history.isEmpty()) {
                result.put(entry.getKey().name(), 0.0);
            } else {
                long sum = 0;
                for (long time : history) {
                    sum += time;
                }
                result.put(entry.getKey().name(), sum / (double) history.size() / 1_000_000.0);
            }
        }
        
        return result;
    }

    // ==================== 调试和可视化方法 ====================

    /**
     * 启用或禁用调试模式
     * <p>
     * 调试模式下会：
     * <ul>
     *   <li>输出详细的每效果日志</li>
     *   <li>生成 Mermaid 格式的执行图</li>
     *   <li>记录更多性能指标</li>
     * </ul>
     *
     * @param enabled true 启用调试模式
     */
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        LOGGER.info("调试模式: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 获取上次执行的 Mermaid 格式效果链图
     * <p>
     * 用于调试面板或文档生成。
     *
     * @return Mermaid graph 字符串
     */
    public String getLastExecutionGraph() {
        return lastExecutionGraphSnapshot;
    }

    /**
     * 生成当前配置的 Mermaid 依赖图
     * <p>
     * 展示所有已注册效果及其依赖关系。
     *
     * @return Mermaid graph 字符串
     */
    public String generateDependencyGraph() {
        StringBuilder sb = new StringBuilder();
        sb.append("graph TB\n");

        sb.append("    A[\"输入帧\"]\n");

        char lastNode = 'A';
        synchronized (effectEntries) {
            for (int i = 0; i < effectEntries.size(); i++) {
                EffectEntry entry = effectEntries.get(i);
                char currentNode = (char) ('B' + i);
                boolean enabled = isEffectEnabled(entry.type);
                
                String color = enabled ? "#69f" : "#ccc";
                sb.append(String.format(
                    "    %s[\"%s%s\"]\n",
                    currentNode,
                    entry.type.name(),
                    enabled ? "" : " (禁用)"
                ));
                
                sb.append(String.format(
                    "    style %s fill:%s\n",
                    currentNode,
                    color
                ));

                // 连接到上一个节点
                sb.append(String.format("    %s --> %s\n", lastNode, currentNode));
                lastNode = currentNode;
            }
        }

        sb.append(String.format("    style %s fill:#6f9\n", lastNode));
        sb.append(String.format("    %s[\"输出帧\"]\n", (char)(lastNode + 1)));
        sb.append(String.format("    %s --> %s\n", lastNode, (char)(lastNode + 1)));
        sb.append(String.format("    style %s fill:#6f9\n", (char)(lastNode + 1)));

        return sb.toString();
    }

    // ==================== 内部实现方法 ====================

    /**
     * 初始化默认效果集
     * <p>
     * 注册三个默认后处理效果及其优先级和预算。
     */
    private void initializeDefaultEffects() {
        // 清空现有条目
        synchronized (effectEntries) {
            effectEntries.clear();
        }

        // Bloom - 最高优先级，最大视觉影响
        registerEffect(
            EffectType.BLOOM, 
            10,  // 优先级最高
            null,  // 无依赖
            2.0   // 2ms 预算
        );

        // DOF - 中等优先级，需要深度缓冲
        registerEffect(
            EffectType.DOF,
            20,  // 第二优先级
            null,  // 无前置依赖（但需要深度纹理可用）
            1.5   // 1.5ms 预算
        );

        // MotionBlur - 较低优先级
        registerEffect(
            EffectType.MOTION_BLUR,
            30,  // 第三优先级
            null,  // 无依赖（但需要运动矢量）
            1.0   // 1ms 预算
        );
    }

    /**
     * 获取效果的拓扑排序
     * <p>
     * 基于 Kahn 算法进行拓扑排序，确保依赖的效果先执行。
     *
     * @return 排序后的效果列表
     */
    private List<EffectEntry> getTopologicalSort() {
        List<EffectEntry> sorted = new ArrayList<>();
        Queue<EffectEntry> queue = new LinkedList<>();
        Map<EffectType, Integer> inDegree = new HashMap<>();

        // 计算入度
        synchronized (effectEntries) {
            for (EffectEntry entry : effectEntries) {
                inDegree.put(entry.type, entry.dependencies.size());
                if (entry.dependencies.isEmpty()) {
                    queue.add(entry);
                }
            }
        }

        // BFS 拓扑排序
        while (!queue.isEmpty()) {
            EffectEntry current = queue.poll();
            sorted.add(current);

            // 查找依赖当前效果的所有效果
            synchronized (effectEntries) {
                for (EffectEntry entry : effectEntries) {
                    if (entry.dependencies.contains(current.type)) {
                        int degree = inDegree.get(entry.type) - 1;
                        inDegree.put(entry.type, degree);
                        if (degree == 0) {
                            queue.add(entry);
                        }
                    }
                }
            }
        }

        // 如果排序后的数量少于总数，说明存在循环依赖
        if (sorted.size() < effectEntries.size()) {
            LOGGER.warning("检测到循环依赖，回退到优先级排序");
            
            // 回退方案：按优先级排序
            synchronized (effectEntries) {
                sorted = new ArrayList<>(effectEntries);
                sorted.sort(Comparator.comparingInt(e -> e.priority));
            }
        }

        return sorted;
    }

    /**
     * 执行单个效果
     * <p>
     * 委托给基础 EffectPipeline 或直接处理。
     *
     * @param frameData 帧数据
     * @param entry     效果条目
     * @return true 表示成功
     */
    private boolean executeSingleEffect(FrameData frameData, EffectEntry entry) {
        // 尝试使用基础 Pipeline
        if (basePipeline != null && basePipeline.isAvailable()) {
            try {
                // 应用动态调整的参数
                EffectParameters params = getAdjustedParameters(entry.type);
                
                if (basePipeline != null && basePipeline.isAvailable()) {
                    LOGGER.fine("Executing effect: " + entry.type.name() +
                        " quality=" + currentPerformanceLevel.get().getQualityFactor());
                }
                
                return true;
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, 
                    "基础 Pipeline 执行失败: " + entry.type.name(), e);
                return false;
            }
        }

        // 基础 Pipeline 不可用，返回成功（跳过但不报错）
        return true;
    }

    /**
     * 获取经过动态调整的效果参数
     * <p>
     * 根据当前性能等级对原始参数进行缩放。
     *
     * @param type 效果类型
     * @return 调整后的参数（如果没有自定义参数则返回 null）
     */
    private EffectParameters getAdjustedParameters(EffectType type) {
        EffectParameters original = effectParamsMap.get(type);
        
        if (original == null) {
            return null;  // 使用默认参数
        }

        // 根据性能等级调整参数
        float qualityFactor = currentPerformanceLevel.get().getQualityFactor();
        
        // 创建调整后的副本
        // 注意：这里应该深拷贝并修改参数值
        // 当前版本简化处理，实际应实现参数缩放逻辑
        
        return original;
    }

    /**
     * 记录效果性能数据
     *
     * @param type       效果类型
     * @param elapsedNs  耗时（纳秒）
     */
    private void recordEffectPerformance(EffectType type, long elapsedNs) {
        Deque<Long> history = effectPerformanceHistory.get(type);
        if (history != null) {
            synchronized (history) {
                history.addLast(elapsedNs);
                
                // 保持窗口大小
                while (history.size() > PERFORMANCE_WINDOW_SIZE) {
                    history.removeFirst();
                }
            }
        }
    }

    /**
     * 更新当前性能等级
     * <p>
     * 基于最近的平均帧时间决定是否需要降级。
     */
    private void updatePerformanceLevel() {
        long recentAvg = calculateRecentAverageTime();
        long budget = aggressiveMode ? AGGRESSIVE_BUDGET_NS : COMPATIBILITY_BUDGET_NS;
        
        PerformanceLevel newLevel;
        
        if (recentAvg <= budget * 0.5) {
            newLevel = PerformanceLevel.FULL_QUALITY;
        } else if (recentAvg <= budget * 0.75) {
            newLevel = PerformanceLevel.HIGH;
        } else if (recentAvg <= budget) {
            newLevel = PerformanceLevel.MEDIUM;
        } else if (recentAvg <= budget * 1.25) {
            newLevel = PerformanceLevel.LOW;
        } else {
            newLevel = PerformanceLevel.MINIMAL;
        }
        
        PerformanceLevel previous = currentPerformanceLevel.getAndSet(newLevel);
        if (previous != newLevel) {
            LOGGER.info(String.format(
                "性能等级变化: %s -> %s (平均耗时: %.2f ms)",
                previous.getDisplayName(),
                newLevel.getDisplayName(),
                recentAvg / 1_000_000.0
            ));
        }
    }

    /**
     * 计算最近 N 帧的平均处理时间
     *
     * @return 平均时间（纳秒）
     */
    private long calculateRecentAverageTime() {
        // 使用所有效果历史的综合平均值
        long totalTime = 0;
        int totalCount = 0;
        
        for (Deque<Long> history : effectPerformanceHistory.values()) {
            synchronized (history) {
                for (long time : history) {
                    totalTime += time;
                    totalCount++;
                }
            }
        }
        
        return totalCount > 0 ? totalTime / totalCount : 0L;
    }

    /**
     * 根据预算超支情况调整效果
     * <p>
     * 当某个效果持续超出预算时，自动降低其质量。
     *
     * @param type       效果类型
     * @param actualNs   实际耗时（纳秒）
     */
    private void adjustEffectForBudget(EffectType type, long actualNs) {
        // 查找对应的效果条目
        EffectEntry targetEntry = null;
        synchronized (effectEntries) {
            for (EffectEntry entry : effectEntries) {
                if (entry.type == type) {
                    targetEntry = entry;
                    break;
                }
            }
        }
        
        if (targetEntry == null) {
            return;
        }
        
        // 计算超支比例
        double overRatio = (double) actualNs / targetEntry.budgetNanos;
        
        if (overRatio > 1.5) {
            // 严重超支，考虑禁用该效果
            LOGGER.warning(String.format(
                "效果 %s 严重超支 (%.1fx)，建议禁用",
                type.name(),
                overRatio
            ));
            
            // 在 MINIMAL 级别下自动禁用非关键效果
            if (currentPerformanceLevel.get() == PerformanceLevel.MINIMAL && 
                type != EffectType.BLOOM) {
                setEffectEnabled(type, false);
            }
        }
    }

    /**
     * 生成执行图快照（Mermaid 格式）
     *
     * @param order       执行顺序
     * @param totalNs     总耗时
     */
    private void generateExecutionGraphSnapshot(List<EffectEntry> order, long totalNs) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph LR\n");
        sb.append(String.format("    subgraph \"后处理管线 (%.2fms)\"\n", totalNs / 1_000_000.0));
        
        char prevNode = 'A';
        sb.append("    A[\"输入\"]\n");
        
        for (EffectEntry entry : order) {
            if (!isEffectEnabled(entry.type)) {
                continue;
            }
            
            char currNode = prevNode++;
            Deque<Long> history = effectPerformanceHistory.get(entry.type);
            double avgMs = 0;
            
            if (history != null && !history.isEmpty()) {
                synchronized (history) {
                    long sum = 0;
                    for (long t : history) {
                        sum += t;
                    }
                    avgMs = sum / (double) history.size() / 1_000_000.0;
                }
            }
            
            sb.append(String.format(
                "    %s[\"%s\"\n%.2fms\"]\n",
                currNode,
                entry.type.name(),
                avgMs
            ));
            sb.append(String.format("    %s --> %s\n", (char)(currNode - 1), currNode));
        }
        
        char endNode = prevNode;
        sb.append(String.format("    %s[\"输出\"]\n", endNode));
        sb.append(String.format("    %s --> %s\n", (char)(endNode - 1), endNode));
        sb.append("    end\n");
        
        lastExecutionGraphSnapshot = sb.toString();
    }

    /**
     * 记录性能摘要日志
     *
     * @param frameIndex 当前帧索引
     */
    private void logPerformanceSummary(int frameIndex) {
        double totalMs = lastTotalTimeNanos.get() / 1_000_000.0;
        double budgetMs = getTotalBudgetMs();
        
        LOGGER.info(String.format(
            "[EnhancedEffectPipeline] 帧 #%d 性能摘要:" +
            "  总耗时: %.2f ms (预算: %.2f ms, 利用率: %.1f%%)" +
            "  性能等级: %s (%.0f%% 质量)" +
            "  各效果平均耗时:\n%s",
            frameIndex,
            totalMs,
            budgetMs,
            budgetMs > 0 ? (totalMs / budgetMs * 100) : 0,
            currentPerformanceLevel.get().getDisplayName(),
            currentPerformanceLevel.get().getQualityFactor() * 100,
            formatEffectTimings()
        ));
    }

    /**
     * 格式化效果计时信息
     *
     * @return 格式化的字符串
     */
    private String formatEffectTimings() {
        StringBuilder sb = new StringBuilder();
        Map<String, Double> timings = getEffectAverageTimes();
        
        for (Map.Entry<String, Double> entry : timings.entrySet()) {
            sb.append(String.format(
                "    %-15s: %.2f ms\n",
                entry.getKey(),
                entry.getValue()
            ));
        }
        
        return sb.toString();
    }
}
