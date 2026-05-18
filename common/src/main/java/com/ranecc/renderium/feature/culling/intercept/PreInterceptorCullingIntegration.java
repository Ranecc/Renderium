// Renderium - Blaze3D 拦截层系统 Phase 5
// 前拦截层剔除集成核心 - 在 PreBlaze3DInterceptor 中注入剔除调用点
// 实现剔除结果传递给渲染管线，收集统计信息，评估性能影响

package com.ranecc.renderium.feature.culling.intercept;
import com.ranecc.renderium.feature.culling.core.CullingContext;
import com.ranecc.renderium.feature.culling.core.CullingContext;
import com.ranecc.renderium.feature.culling.optimization.NeighborFaceCuller;
import com.ranecc.renderium.feature.culling.optimization.HeightmapOcclusionCuller;
import com.ranecc.renderium.feature.culling.optimization.AsyncComputeCuller;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 前拦截层剔除集成核心 ⚙️
 *
 * <p>作为 DefaultPreInterceptor 和底层剔除系统之间的桥梁，
 * 负责协调、调度和监控所有剔除操作。
 *
 * <h2>核心职责：</h2>
 * <ul>
 *   <li><b>剔除调度</b>：根据策略选择并执行合适的剔除器组合</li>
 *   <li><b>结果整合</b>：将多个剔除器的输出合并为最终可见集</li>
 *   <li><b>统计监控</b>：记录每帧的剔除率、耗时等关键指标</li>
 *   <li><b>性能管理</b>：动态调整剔除策略以适应性能预算</li>
 *   <li><b>错误恢复</b>：单个剔除器失败时自动降级</li>
 * </ul>
 *
 * <h2>架构位置：</h2>
 * <pre>
 * ┌─────────────────────────────────────┐
 * │     DefaultPreInterceptor           │
 * │  ┌───────────────────────────────┐  │
 * │  │ processCullingInjection()     │  │
 * │  └──────────────┬────────────────┘  │
 * └─────────────────┼───────────────────┘
 *                   │
 *                   ▼
 * ┌─────────────────────────────────────┐
 * │ PreInterceptorCullingIntegration    │ ← 本类
 * │  ┌───────────────────────────────┐  │
 * │  │ executeCulling()              │  │
 * │  │   ├─ AsyncComputeCuller       │  │
 * │  │   ├─ HeightmapOcclusionCuller │  │
 * │  │   ├─ NeighborFaceCuller       │  │
 * │  │   └─ CullingAdapter.merge()   │  │
 * │  └───────────────────────────────┘  │
 * └──────────────────┬──────────────────┘
 *                    │
 *                    ▼
 * ┌─────────────────────────────────────┐
 * │  CullingIntegrationResult            │ → 返回给渲染管线
 * │  { finalVisibleMask, statistics }    │
 * └─────────────────────────────────────┘
 * </pre>
 *
 * <h2>剔除策略说明：</h2>
 * <pre>
 * ┌────────────────────────────────────────────────────────────┐
 * │ 策略          │ AsyncCompute │ Heightmap │ NeighborFace │ 预期剔除率 │
 * ├────────────────────────────────────────────────────────────┤
 * │ CONSERVATIVE  │      ✓       │     ✗     │      ✗      │  10-20%   │
 * │ BALANCED      │      ✓       │     ✓     │      ✗      │  30-40%   │
 * │ AGGRESSIVE    │      ✓       │     ✓     │      ✓      │  40-60%   │
 * └────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>线程安全：</h3>
 * <p>此类使用 AtomicLong 保证统计字段的线程安全。
 * 核心方法 {@link #executeCulling(InterceptionCullingContext)} 应在渲染线程调用。
 *
 * <h2>性能目标：</h2>
 * <table border="1">
 *   <tr><th>模式</th><th>CPU 开销</th><th>GPU 开销</th><th>总剔除率</th></tr>
 *   <tr><td>兼容模式</td><td>&lt;1ms</td><td>N/A</td><td>20-30%</td></tr>
 *   <tr><td>狂暴模式</td><td>&lt;0.5ms</td><td>&lt;1ms</td><td>40-60%</td></tr>
 * </table>
 *
 * @author Renderium Team
 * @version 5.1.0 (Phase 5)
 * @since 5.1.0
 * @see CullingAdapter
 * @see InterceptionCullingContext
 * @see com.renderium.interception.DefaultPreInterceptor
 */
public final class PreInterceptorCullingIntegration {

    private static final Logger LOGGER = Logger.getLogger(PreInterceptorCullingIntegration.class.getName());

    // ==================== 性能预算常量 ====================

    /** 兼容模式最大 CPU 时间（纳秒）= 1ms */
    private static final long COMPATIBILITY_MODE_BUDGET_NS = 1_000_000L;

    /** 狂暴模式最大 CPU 时间（纳秒）= 0.5ms */
    private static final long AGGRESSIVE_MODE_BUDGET_NS = 500_000L;

    // ==================== 单例实例 ====================

    /** 单例实例（volatile 保证可见性） */
    private static volatile PreInterceptorCullingIntegration instance;

    // ==================== 配置字段 ====================

    /** 当前剔除策略 */
    private volatile CullingStrategy strategy = CullingStrategy.BALANCED;

    /** 是否启用异步计算剔除 */
    private volatile boolean enableAsyncCompute = true;

    /** 是否启用高度图遮挡剔除 */
    private volatile boolean enableOcclusion = true;

    /** 是否启用邻居面剔除 */
    private volatile boolean enableNeighborFace = false; // 默认关闭（需要方块状态数据）

    // ==================== 剔除器引用 ====================

    /** 异步计算剔除器（延迟初始化） */
    private volatile AsyncComputeCuller asyncComputeCuller;

    /** 高度图遮挡剔除器（延迟初始化） */
    private volatile HeightmapOcclusionCuller heightmapOcclusionCuller;

    /** 邻居面剔除器（延迟初始化） */
    private volatile NeighborFaceCuller neighborFaceCuller;

    // ==================== 统计字段（原子操作保证线程安全） ====================

    /** 总处理帧数 */
    private final AtomicLong totalFramesProcessed = new AtomicLong(0);

    /** 累计剔除对象总数 */
    private final AtomicLong totalCulledObjects = new AtomicLong(0);

    /** 累计处理对象总数 */
    private final AtomicLong totalProcessedObjects = new AtomicLong(0);

    /** 累计处理时间（纳秒） */
    private final AtomicLong totalProcessTimeNanos = new AtomicLong(0);

    /** 最大单帧剔除率（百分比，0-100） */
    private volatile int maxCullRate = 0;

    /** 最小单帧剔除率（百分比，0-100） */
    private volatile int minCullRate = 100;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数 - 强制单例模式
     */
    private PreInterceptorCullingIntegration() {
        LOGGER.info("PreInterceptorCullingIntegration 初始化完成");
        LOGGER.info(String.format(
                "默认配置: strategy=%s, asyncCompute=%s, occlusion=%s, neighborFace=%s",
                strategy, enableAsyncCompute, enableOcclusion, enableNeighborFace
        ));
    }

    /**
     * 获取单例实例
     *
     * <p>使用双重检查锁定（DCL）模式确保线程安全的延迟初始化。
     *
     * @return PreInterceptorCullingIntegration 全局唯一实例
     */
    public static PreInterceptorCullingIntegration getInstance() {
        if (instance == null) {
            synchronized (PreInterceptorCullingIntegration.class) {
                if (instance == null) {
                    instance = new PreInterceptorCullingIntegration();
                }
            }
        }
        return instance;
    }

    // ==================== 核心方法：执行剔除 ====================

    /**
     * 执行完整的剔除流程
     *
     * <p>这是前拦截层剔除系统的主入口点，
     * 由 {@link com.renderium.interception.DefaultPreInterceptor#processCullingInjection}
     * 在每帧渲染时调用。
     *
     * <h3>完整流程：</h3>
     * <ol>
     *   <li>参数校验与状态检查</li>
     *   <li>根据当前策略确定启用的剔除器列表</li>
     *   <li>依次调用各剔除器适配器</li>
     *   <li>合并所有剔除结果（AND 保守策略）</li>
     *   <li>更新统计信息</li>
     *   <li>性能预算检查与自动降级</li>
     *   <li>返回最终结果</li>
     * </ol>
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - context: 拦截层剔除上下文（包含相机、区段数据等）
     *             不能为 null，应通过 InterceptionCullingContext.Builder 构建
     *
     * 返回值：
     *   - CullingIntegrationResult: 包含以下信息：
     *     - success: 是否成功完成（即使部分剔除器失败也可能返回 true）
     *     - finalVisibleMask: 最终可见性掩码（BitSet）
     *     - totalObjects: 原始对象总数
     *     - visibleAfterCulling: 剔除后可见的对象数
     *     - cullPercentage: 剔除率百分比 (0-100)
     *     - totalTimeNanos: 总处理耗时（纳秒）
     *     - timeBreakdown: 各剔除器的耗时明细（Map）
     *
     * 异常处理：
     *   - 单个剔除器失败：记录警告，继续使用其他剔除器
     *   - 所有剔除器失败：返回全可见的保守结果
     *   - 性能超标：自动降级到更简单的策略
     * </pre>
     *
     * @param context 拦截层剔除上下文（不能为 null）
     * @return 剔除集成结果（包含最终可见集和统计信息）
     * @throws IllegalArgumentException 如果 context 为 null
     */
    public CullingIntegrationResult executeCulling(InterceptionCullingContext context) {

        // ======== 参数校验 ========
        if (context == null) {
            throw new IllegalArgumentException("InterceptionCullingContext 不能为 null");
        }

        long frameStartTime = System.nanoTime();

        try {
            int objectCount = context.getObjectCount();

            // ======== 边界情况：无对象 ========
            if (objectCount <= 0) {
                LOGGER.fine("无对象需要剔除");
                return createEmptyResult();
            }

            // ======== 获取适配器实例 ========
            CullingAdapter adapter = CullingAdapter.getInstance();

            // ======== 根据策略确定要执行的剔除器 ========
            List<CullingAdapter.CullingResult> results = new ArrayList<>();
            Map<String, Double> timeBreakdown = new LinkedHashMap<>();

            // ----- 1. 异步计算剔除器（视锥体 + GPU 遮挡） -----
            if (enableAsyncCompute && shouldRunAsyncCompute()) {
                long cullStart = System.nanoTime();

                try {
                    AsyncComputeCuller culler = getOrCreateAsyncComputeCuller();
                    CullingAdapter.CullingResult result = adapter.adaptAsyncComputeCuller(culler, context);
                    results.add(result);

                    long cullElapsed = System.nanoTime() - cullStart;
                    timeBreakdown.put("async_compute", cullElapsed / 1_000_000.0);

                    LOGGER.fine(String.format("异步计算剔除完成: %s", result));

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "异步计算剔除异常", e);
                    timeBreakdown.put("async_compute_error", 0.0);
                }
            }

            // ----- 2. 高度图遮挡剔除器 -----
            if (enableOcclusion && strategy != CullingStrategy.CONSERVATIVE) {
                long cullStart = System.nanoTime();

                try {
                    HeightmapOcclusionCuller culler = getOrCreateHeightmapOcclusionCuller(objectCount);
                    CullingAdapter.CullingResult result = adapter.adaptHeightmapOcclusionCuller(culler, context);
                    results.add(result);

                    long cullElapsed = System.nanoTime() - cullStart;
                    timeBreakdown.put("heightmap_occlusion", cullElapsed / 1_000_000.0);

                    LOGGER.fine(String.format("高度图遮挡剔除完成: %s", result));

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "高度图遮挡剔除异常", e);
                    timeBreakdown.put("heightmap_occlusion_error", 0.0);
                }
            }

            // ----- 3. 邻居面剔除器 -----
            if (enableNeighborFace && strategy == CullingStrategy.AGGRESSIVE) {
                long cullStart = System.nanoTime();

                try {
                    NeighborFaceCuller culler = getOrCreateNeighborFaceCuller();
                    CullingAdapter.CullingResult result = adapter.adaptNeighborFaceCuller(culler, context);
                    results.add(result);

                    long cullElapsed = System.nanoTime() - cullStart;
                    timeBreakdown.put("neighbor_face", cullElapsed / 1_000_000.0);

                    LOGGER.fine(String.format("邻居面剔除完成: %s", result));

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "邻居面剔除异常", e);
                    timeBreakdown.put("neighbor_face_error", 0.0);
                }
            }

            // ======== 合并所有剔除结果 ========
            CullingAdapter.CullingResult mergedResult;
            if (results.isEmpty()) {
                // 无任何剔除器执行，返回全可见
                LOGGER.fine("无剔除器执行，返回全可见");
                mergedResult = adapter.mergeResults(List.of(
                        createAllVisibleCullingResult(objectCount)
                ));
            } else {
                mergedResult = adapter.mergeResults(results);
            }

            // ======== 计算总耗时 ========
            long totalElapsed = System.nanoTime() - frameStartTime;
            timeBreakdown.put("total_merge_overhead",
                    (totalElapsed - timeBreakdown.values().stream().mapToDouble(d -> d).sum()) / 1_000_000.0);

            // ======== 更新统计信息 ========
            updateStatistics(
                    objectCount,
                    mergedResult.visibleCount(),
                    mergedResult.culledCount(),
                    totalElapsed
            );

            // ======== 性能预算检查与自动降级 ========
            checkPerformanceBudget(totalElapsed);

            // ======== 构建最终结果 ========
            CullingIntegrationResult integrationResult = new CullingIntegrationResult(
                    true,                                          // success
                    mergedResult.visibleMask(),                     // finalVisibleMask
                    objectCount,                                    // totalObjects
                    mergedResult.visibleCount(),                    // visibleAfterCulling
                    mergedResult.getCullPercentage(),               // cullPercentage
                    totalElapsed,                                   // totalTimeNanos
                    timeBreakdown                                   // timeBreakdown
            );

            LOGGER.fine(String.format(
                    "剔除集成完成: %d/%d 可见 (%.1f%% 剔除), 总耗时 %.2f ms",
                    mergedResult.visibleCount(),
                    objectCount,
                    mergedResult.getCullPercentage(),
                    totalElapsed / 1_000_000.0
            ));

            return integrationResult;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "剔除集成过程发生未预期异常", e);

            // 返回保守结果（全可见）
            return new CullingIntegrationResult(
                    false,
                    createAllVisibleBitSet(context.getObjectCount()),
                    context.getObjectCount(),
                    context.getObjectCount(),
                    0.0,
                    System.nanoTime() - frameStartTime,
                    Map.of("error", (double) (System.nanoTime() - frameStartTime) / 1_000_000.0)
            );
        }
    }

    // ==================== 配置方法 ====================

    /**
     * 设置剔除策略
     *
     * <p>不同策略会启用不同级别的剔除器组合：
     * <ul>
     *   <li>{@link CullingStrategy#CONSERVATIVE}：仅视锥体剔除（最快，最低剔除率）</li>
     *   <li>{@link CullingStrategy#BALANCED}：视锥体 + 遮挡剔除（平衡性能和质量）</li>
     *   <li>{@link CullingStrategy#AGGRESSIVE}：全部启用（最高剔除率，较高开销）</li>
     * </ul>
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - strategy: 剔除策略枚举值（不能为 null）
     *
     * 返回值：
     *   - void（无返回值）
     * </pre>
     *
     * @param strategy 剔除策略（不能为 null）
     * @throws IllegalArgumentException 如果 strategy 为 null
     */
    public void setCullingStrategy(CullingStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("剔除策略不能为 null");
        }

        this.strategy = strategy;

        // 根据策略自动调整各剔除器开关
        switch (strategy) {
            case CONSERVATIVE:
                this.enableAsyncCompute = true;
                this.enableOcclusion = false;
                this.enableNeighborFace = false;
                break;
            case BALANCED:
                this.enableAsyncCompute = true;
                this.enableOcclusion = true;
                this.enableNeighborFace = false;
                break;
            case AGGRESSIVE:
                this.enableAsyncCompute = true;
                this.enableOcclusion = true;
                this.enableNeighborFace = true;
                break;
        }

        LOGGER.info(String.format("剔除策略已更新: %s [async=%s, occlusion=%s, neighbor=%s]",
                strategy, enableAsyncCompute, enableOcclusion, enableNeighborFace));
    }

    /**
     * 设置是否启用异步计算剔除（GPU Compute Shader）
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - enable: 是否启用（true/false）
     *
     * 返回值：
     *   - void（无返回值）
     * </pre>
     *
     * @param enable 是否启用
     */
    public void setEnableAsyncCompute(boolean enable) {
        this.enableAsyncCompute = enable;
        LOGGER.fine("异步计算剔除已" + (enable ? "启用" : "禁用"));
    }

    /**
     * 设置是否启用高度图遮挡剔除
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - enable: 是否启用（true/false）
     *
     * 返回值：
     *   - void（无返回值）
     * </pre>
     *
     * @param enable 是否启用
     */
    public void setEnableOcclusion(boolean enable) {
        this.enableOcclusion = enable;
        LOGGER.fine("高度图遮挡剔除已" + (enable ? "启用" : "禁用"));
    }

    /**
     * 设置是否启用邻居面剔除
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - enable: 是否启用（true/false）
     *
     * 返回值：
     *   - void（无返回值）
     * </pre>
     *
     * @param enable 是否启用
     */
    public void setEnableNeighborFace(boolean enable) {
        this.enableNeighborFace = enable;
        LOGGER.fine("邻居面剔除已" + (enable ? "启用" : "禁用"));
    }

    // ==================== 统计查询方法 ====================

    /**
     * 获取剔除统计信息
     *
     * <p>返回从上次重置以来的累计统计数据。
     *
     * <h3>返回值说明：</h3>
     * <pre>
     * CullingStatistics 包含：
     *   - totalFramesProcessed: 总处理帧数
     *   - avgCullRate: 平均剔除率 (0.0-1.0)
     *   - avgCullTimeMs: 平均剔除耗时（毫秒）
     *   - maxCullRate: 最大单帧剔除率 (0-100)
     *   - minCullRate: 最小单帧剔除率 (0-100)
     * </pre>
     *
     * @return 统计信息快照
     */
    public CullingStatistics getStatistics() {
        long frames = totalFramesProcessed.get();
        long processed = totalProcessedObjects.get();

        double avgCullRate = processed > 0 ?
                (double) totalCulledObjects.get() / processed : 0.0;
        double avgCullTimeMs = frames > 0 ?
                (double) totalProcessTimeNanos.get() / frames / 1_000_000.0 : 0.0;

        return new CullingStatistics(
                frames,
                avgCullRate,
                avgCullTimeMs,
                maxCullRate,
                minCullRate
        );
    }

    /**
     * 重置所有统计信息
     *
     * <p>通常在切换场景或进行性能测试时调用。
     */
    public void resetStatistics() {
        totalFramesProcessed.set(0);
        totalCulledObjects.set(0);
        totalProcessedObjects.set(0);
        totalProcessTimeNanos.set(0);
        maxCullRate = 0;
        minCullRate = 100;

        LOGGER.info("剔除统计信息已重置");
    }

    // ==================== 从 CullingContext 加载配置 ====================

    /**
     * 从拦截层的 CullingContext 加载配置
     *
     * <p>此方法用于将用户/模组配置同步到剔除集成系统中。
     *
     * <h3>映射关系：</h3>
     * <pre>
     * CullingContext 字段                  → 本类字段
     * ──────────────────────────────────────────────────
     * frustumCullingEnabled              → enableAsyncCompute
     * occlusionCullingEnabled            → enableOcclusion
     * neighborFaceCullingEnabled         → enableNeighborFace
     * maxDrawDistance                     → （传递给 InterceptionCullingContext）
     * </pre>
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - ctx: 拦截层剔除上下文配置（不能为 null）
     *
     * 返回值：
     *   - void（无返回值）
     * </pre>
     *
     * @param ctx CullingContext 配置实例（不能为 null）
     * @throws IllegalArgumentException 如果 ctx 为 null
     */
    public void loadConfigurationFromContext(CullingContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("CullingContext 不能为 null");
        }

        // 映射配置字段
        this.enableAsyncCompute = ctx.isFrustumCullingEnabled();
        this.enableOcclusion = ctx.isOcclusionCullingEnabled();
        this.enableNeighborFace = ctx.isNeighborFaceCullingEnabled();

        LOGGER.fine(String.format(
                "配置已从 CullingContext 加载: frustum=%s, occlusion=%s, neighborFace=%s, maxDistance=%d",
                enableAsyncCompute, enableOcclusion, enableNeighborFace,
                ctx.getMaxDrawDistance()
        ));
    }

    // ==================== 内部方法：剔除器懒加载 ====================

    /**
     * 获取或创建异步计算剔除器
     *
     * @return AsyncComputeCuller 实例（可能未初始化）
     */
    private AsyncComputeCuller getOrCreateAsyncComputeCuller() {
        if (asyncComputeCuller == null) {
            synchronized (this) {
                if (asyncComputeCuller == null) {
                    asyncComputeCuller = AsyncComputeCuller.getInstance();
                    LOGGER.fine("AsyncComputeCuller 已创建（可能尚未初始化 Vulkan 资源）");
                }
            }
        }
        return asyncComputeCuller;
    }

    /**
     * 获取或创建高度图遮挡剔除器
     *
     * @param estimatedObjectCount 预估对象数量（用于设置覆盖半径）
     * @return HeightmapOcclusionCuller 实例
     */
    private HeightmapOcclusionCuller getOrCreateHeightmapOcclusionCuller(int estimatedObjectCount) {
        if (heightmapOcclusionCuller == null) {
            synchronized (this) {
                if (heightmapOcclusionCuller == null) {
                    // GPU优化：使用位运算整数平方根近似，避免sqrt
                    // 原理：n的最高有效位位置的一半 ≈ log2(sqrt(n))
                    int bitPos = Integer.SIZE - Integer.numberOfLeadingZeros(estimatedObjectCount) - 1;
                    int sqrtApprox = estimatedObjectCount > 0 ? 1 << ((bitPos + 1) >> 1) : 0;
                    int radius = Math.max(32, sqrtApprox / 2);
                    heightmapOcclusionCuller = new HeightmapOcclusionCuller(radius);
                    LOGGER.fine(String.format("HeightmapOcclusionCuller 已创建 (radius=%d)", radius));
                }
            }
        }
        return heightmapOcclusionCuller;
    }

    /**
     * 获取或创建邻居面剔除器
     *
     * @return NeighborFaceCuller 实例
     */
    private NeighborFaceCuller getOrCreateNeighborFaceCuller() {
        if (neighborFaceCuller == null) {
            synchronized (this) {
                if (neighborFaceCuller == null) {
                    neighborFaceCuller = new NeighborFaceCuller();
                    LOGGER.fine("NeighborFaceCuller 已创建");
                }
            }
        }
        return neighborFaceCuller;
    }

    // ==================== 内部方法：条件判断 ====================

    /**
     * 检查是否应该运行异步计算剔除器
     *
     * @return true 如果应该运行
     */
    private boolean shouldRunAsyncCompute() {
        AsyncComputeCuller culler = asyncComputeCuller;
        return culler != null && culler.isInitialized();
    }

    // ==================== 内部方法：统计更新 ====================

    /**
     * 更新统计计数器
     *
     * @param totalObjects    对象总数
     * @param visibleCount    可见数
     * @param culledCount     剔除数
     * @param processTimeNs   处理耗时
     */
    private void updateStatistics(int totalObjects, int visibleCount,
                                  int culledCount, long processTimeNs) {
        totalFramesProcessed.incrementAndGet();
        totalProcessedObjects.addAndGet(totalObjects);
        totalCulledObjects.addAndGet(culledCount);
        totalProcessTimeNanos.addAndGet(processTimeNs);

        // 更新最大/最小剔除率
        if (totalObjects > 0) {
            int currentCullRate = (int) (culledCount * 100.0 / totalObjects);
            if (currentCullRate > maxCullRate) {
                maxCullRate = currentCullRate;
            }
            if (currentCullRate < minCullRate) {
                minCullRate = currentCullRate;
            }
        }
    }

    // ==================== 内部方法：性能预算检查 ====================

    /**
     * 检查是否超出性能预算，必要时自动降级
     *
     * @param actualTimeNs 实际耗时（纳秒）
     */
    private void checkPerformanceBudget(long actualTimeNs) {
        long budget = (strategy == CullingStrategy.AGGRESSIVE) ?
                AGGRESSIVE_MODE_BUDGET_NS : COMPATIBILITY_MODE_BUDGET_NS;

        if (actualTimeNs > budget) {
            LOGGER.warning(String.format(
                    "性能超标! 实际耗时 %.2f ms > 预算 %.2f ms, 策略: %s",
                    actualTimeNs / 1_000_000.0,
                    budget / 1_000_000.0,
                    strategy
            ));

            // 自动降级到更保守的策略
            if (strategy == CullingStrategy.AGGRESSIVE) {
                LOGGER.warning("自动降级: AGGRESSIVE → BALANCED");
                setCullingStrategy(CullingStrategy.BALANCED);
            } else if (strategy == CullingStrategy.BALANCED) {
                LOGGER.warning("自动降级: BALANCED → CONSERVATIVE");
                setCullingStrategy(CullingStrategy.CONSERVATIVE);
            }
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 创建空结果（对象数为 0）
     */
    private CullingIntegrationResult createEmptyResult() {
        return new CullingIntegrationResult(
                true,
                new BitSet(0),
                0,
                0,
                0.0,
                0L,
                Map.of("empty", 0.0)
        );
    }

    /**
     * 创建全可见的 CullingResult（用于合并）
     */
    private CullingAdapter.CullingResult createAllVisibleCullingResult(int count) {
        BitSet allVisible = new BitSet(count);
        allVisible.set(0, count);

        return new CullingAdapter.CullingResult(
                allVisible,
                count,
                count,
                0,
                0.0,
                0L,
                Map.of("fallback_all_visible", 0)
        );
    }

    /**
     * 创建全可见的 BitSet
     */
    private BitSet createAllVisibleBitSet(int count) {
        BitSet allVisible = new BitSet(count);
        allVisible.set(0, count);
        return allVisible;
    }

    // ==================== 内部枚举与数据类 ====================

    /**
     * 剔除策略枚举
     *
     * <p>定义不同的剔除强度级别，
     * 平衡渲染性能和视觉质量。
     */
    public enum CullingStrategy {
        /**
         * 保守策略
         *
         * <p>仅启用视锥体剔除（必须项）。
         * <ul>
         *   <li><b>CPU 开销</b>：&lt;0.5ms</li>
         *   <li><b>剔除率</b>：10-20%</li>
         *   <li><b>适用场景</b>：低配机器、CPU 密集型场景</li>
         * </ul>
         */
        CONSERVATIVE,

        /**
         * 平衡策略（默认）
         *
         * <p>启用视锥体剔除 + 高度图遮挡剔除。
         * <ul>
         *   <li><b>CPU 开销</b>：&lt;1ms</li>
         *   <li><b>剔除率</b>：30-40%</li>
         *   <li><b>适用场景</b>：大多数情况下的推荐配置</li>
         * </ul>
         */
        BALANCED,

        /**
         * 激进策略
         *
         * <p>启用所有剔除器（视锥体 + 遮挡 + 邻居面）。
         * <ul>
         *   <li><b>CPU 开销</b>：&lt;1.5ms</li>
         *   <li><b>GPU 开销</b>：&lt;1ms（如果有 GPU Compute）</li>
         *   <li><b>剔除率</b>：40-60%</li>
         *   <li><b>适用场景</b>：高配机器、追求极致性能</li>
         * </ul>
         */
        AGGRESSIVE
    }

    /**
     * 剔除集成结果
     *
     * <p>作为 {@link #executeCulling(InterceptionCullingContext)} 的返回值，
     * 包含完整的剔除结果和性能指标。
     *
     * @param success           是否成功完成
     * @param finalVisibleMask  最终可见性掩码（BitSet，bit=1 表示可见）
     * @param totalObjects      原始对象总数
     * @param visibleAfterCulling 剔除后可见的对象数
     * @param cullPercentage    剔除率百分比（0-100）
     * @param totalTimeNanos    总处理耗时（纳秒）
     * @param timeBreakdown     各阶段耗时明细（名称 → 毫秒数）
     */
    public record CullingIntegrationResult(
            boolean success,
            BitSet finalVisibleMask,
            int totalObjects,
            int visibleAfterCulling,
            double cullPercentage,
            long totalTimeNanos,
            Map<String, Double> timeBreakdown
    ) {
        /**
         * 获取总耗时（毫秒）
         */
        public double getTotalTimeMillis() {
            return totalTimeNanos / 1_000_000.0;
        }

        /**
         * 检查是否有实际剔除效果
         */
        public boolean hasCullingEffect() {
            return cullPercentage > 0.0 && visibleAfterCulling < totalObjects;
        }

        @Override
        public String toString() {
            return String.format(
                    "CullingIntegrationResult{success=%s, visible=%d/%d (%.1f%% culled), time=%.2fms}",
                    success, visibleAfterCulling, totalObjects,
                    cullPercentage, getTotalTimeMillis()
            );
        }
    }

    /**
     * 剔除统计信息
     *
     * <p>包含从上次重置以来的累计统计数据。
     *
     * @param totalFramesProcessed 总处理帧数
     * @param avgCullRate          平均剔除率（0.0-1.0）
     * @param avgCullTimeMs        平均剔除耗时（毫秒）
     * @param maxCullRate          最大单帧剔除率（0-100）
     * @param minCullRate          最小单帧剔除率（0-100）
     */
    public record CullingStatistics(
            long totalFramesProcessed,
            double avgCullRate,
            double avgCullTimeMs,
            int maxCullRate,
            int minCullRate
    ) {
        /**
         * 获取平均剔除率百分比
         */
        public double getAvgCullPercentage() {
            return avgCullRate * 100.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "CullingStatistics{frames=%d, avgCull=%.1f%%, avgTime=%.2fms, range=[%d%%-%d%%]}",
                    totalFramesProcessed, getAvgCullPercentage(),
                    avgCullTimeMs, minCullRate, maxCullRate
            );
        }
    }
}
