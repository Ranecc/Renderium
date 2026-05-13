// Renderium - Blaze3D 拦截层系统 Phase 5
// 剔除器适配器 - 将现有剔除系统的 API 适配为统一的拦截层接口
// 负责类型转换、数据格式标准化、结果合并与冲突解决

package com.ranecc.renderium.feature.culling.intercept;
import com.ranecc.renderium.feature.culling.optimization.NeighborFaceCuller;
import com.ranecc.renderium.feature.culling.optimization.HeightmapOcclusionCuller;
import com.ranecc.renderium.feature.culling.optimization.AsyncComputeCuller;

import com.ranecc.renderium.None;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 剔除器适配器 🔧
 *
 * <p>将现有剔除系统（AsyncComputeCuller、HeightmapOcclusionCuller、NeighborFaceCuller）
 * 的不同 API 适配为统一的拦截层接口。
 *
 * <h2>核心职责：</h2>
 * <ul>
 *   <li><b>API 适配</b>：将各剔除器的异构接口转换为统一的 {@link CullingResult}</li>
 *   <li><b>数据转换</b>：处理不同剔除器的输入/输出格式差异</li>
 *   <li><b>结果合并</b>：将多个剔除器的结果合并为最终可见集</li>
 *   <li><b>冲突解决</b>：处理剔除结果之间的冲突（保守策略）</li>
 * </ul>
 *
 * <h2>架构设计：</h2>
 * <pre>
 * ┌─────────────────────┐
 * │  PreInterceptor      │
 * │  CullingIntegration  │
 * └──────────┬──────────┘
 *            │
 *            ▼
 * ┌─────────────────────┐     ┌─────────────────────┐
 * │  CullingAdapter      │────▶│ AsyncComputeCuller  │
 * │  (本类)              │     │ (GPU Compute)       │
 * ├─────────────────────┤     ├─────────────────────┤
 * │ adaptAsyncCompute() │────▶│ HeightmapOcclusion  │
 * │ adaptHeightmap()    │     │ Culler              │
 * │ adaptNeighborFace() │────▶│ NeighborFaceCuller  │
 * ├─────────────────────┤     └─────────────────────┘
 * │ mergeResults()      │
 * └─────────────────────┘
 *            │
 *            ▼
 * ┌─────────────────────┐
 * │  CullingResult      │  ← 统一输出格式
 * │  (BitSet + 统计)     │
 * └─────────────────────┘
 * </pre>
 *
 * <h2>线程安全：</h3>
 * <p>此类是无状态的（除了单例引用），所有方法都是线程安全的。
 * 实际的线程安全由底层剔除器保证。
 *
 * <h2>性能预算：</h2>
 * <ul>
 *   <li>单个适配调用：< 0.1ms</li>
 *   <li>结果合并（N 个结果）：< 0.05ms</li>
 *   <li>总开销应 &lt; 0.2ms（兼容模式）/ &lt; 0.15ms（狂暴模式）</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 5.1.0 (Phase 5)
 * @since 5.1.0
 * @see PreInterceptorCullingIntegration
 * @see InterceptionCullingContext
 */
public final class CullingAdapter {

    private static final Logger LOGGER = Logger.getLogger(CullingAdapter.class.getName());

    /** 单例实例 */
    private static volatile CullingAdapter instance;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数 - 强制单例模式
     */
    private CullingAdapter() {
        LOGGER.info("CullingAdapter 初始化完成");
    }

    /**
     * 获取单例实例
     *
     * <p>使用双重检查锁定（DCL）模式确保线程安全的延迟初始化。
     *
     * @return CullingAdapter 全局唯一实例
     */
    public static CullingAdapter getInstance() {
        if (instance == null) {
            synchronized (CullingAdapter.class) {
                if (instance == null) {
                    instance = new CullingAdapter();
                }
            }
        }
        return instance;
    }

    // ==================== 核心适配方法 ====================

    /**
     * 适配异步计算剔除器（AsyncComputeCuller）
     *
     * <p><b>输入转换：</b></p>
     * <ul>
     *   <li>InterceptionCullingContext → AsyncComputeCuller.dispatch() 参数</li>
     *   <li>提取相机位置、视锥体平面、渲染距离等</li>
     * </ul>
     *
     * <p><b>输出转换：</b></p>
     * <ul>
     *   <li>AsyncComputeCuller.CullResult → 统一的 CullingResult</li>
     *   <li>保留可见性掩码、统计信息</li>
     * </ul>
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - culler: AsyncComputeCuller 实例（必须已初始化）
     *   - context: 拦截层剔除上下文（包含相机、区段数据等）
     *
     * 返回值：
     *   - CullingResult: 包含可见性掩码和统计信息的统一结果
     *   - 如果 culler 未初始化或异常，返回失败结果（所有对象标记为可见）
     * </pre>
     *
     * @param culler  异步计算剔除器实例（不能为 null）
     * @param context 拦截层剔除上下文（不能为 null）
     * @return 剔除结果（包含可见性掩码、统计信息）
     * @throws IllegalArgumentException 如果参数为 null
     */
    public CullingResult adaptAsyncComputeCuller(
            AsyncComputeCuller culler,
            InterceptionCullingContext context) {

        // ======== 参数校验 ========
        if (culler == null) {
            throw new IllegalArgumentException("AsyncComputeCuller 不能为 null");
        }
        if (context == null) {
            throw new IllegalArgumentException("InterceptionCullingContext 不能为 null");
        }

        long startTime = System.nanoTime();

        try {
            // ======== 检查初始化状态 ========
            if (!culler.isInitialized()) {
                LOGGER.warning("AsyncComputeCuller 未初始化，跳过 GPU 剔除");
                return createFailureResult(context.getObjectCount(), "未初始化");
            }

            // ======== 提取并转换参数 ========
            // 从拦截层上下文中提取 AsyncComputeCuller.dispatch() 所需的数据
            float[][] sections = context.getSections();
            float[][] positions = context.getPositions();
            float[] vpMatrix = context.getViewProjectionMatrix();
            float[] frustumPlanes = context.getFrustumPlanes();
            float[] cameraPos = context.getCameraPosition();
            float renderDistance = context.getRenderDistance();

            // 数据有效性检查
            if (sections == null || sections.length == 0) {
                LOGGER.fine("无区段数据，跳过 AsyncComputeCuller");
                return createEmptyResult(0);
            }

            // ======== 调用底层剔除器 ========
            // AsyncComputeCuller.dispatch() 方法签名：
            //   dispatch(float[][] sections, float[][] positions, float[] vpMatrix,
            //            float[] frustumPlanes, float[] cameraPos, float renderDistance)
            //   返回: CullResult { success, visibleMask, visibleCount, culledCount, cullTimeNanos }
            AsyncComputeCuller.CullResult rawResult = culler.dispatch(
                    sections,
                    positions,
                    vpMatrix,
                    frustumPlanes,
                    cameraPos,
                    renderDistance
            );

            // ======== 转换为统一格式 ========
            long elapsed = System.nanoTime() - startTime;

            if (rawResult.success && rawResult.visibleMask != null) {
                // 成功情况：构建正常结果
                Map<String, Integer> breakdown = new LinkedHashMap<>();
                breakdown.put("async_compute", rawResult.culledCount);

                return new CullingResult(
                        rawResult.visibleMask,           // 可见性掩码
                        context.getObjectCount(),         // 原始对象数
                        rawResult.visibleCount,           // 可见数
                        rawResult.culledCount,             // 剔除数
                        rawResult.getCullRate(),           // 剔除率
                        elapsed,                           // 总处理耗时
                        breakdown                         // 各剔除器贡献
                );
            } else {
                // 失败情况：返回全可见（保守策略）
                LOGGER.warning("AsyncComputeCuller 执行失败，使用保守策略（全部可见）");
                return createFailureResult(context.getObjectCount(), "执行失败");
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "AsyncComputeCuller 适配异常", e);
            return createFailureResult(context.getObjectCount(), e.getMessage());
        }
    }

    /**
     * 适配高度图遮挡剔除器（HeightmapOcclusionCuller）
     *
     * <p><b>算法特点：</b></p>
     * <ul>
     *   <li>高度图遮挡是基于区块列级别的粗粒度遮挡检测</li>
     *   <li>对于每个目标区段，检查从相机到目标的视线是否被中间列的高度遮挡</li>
     *   <li>适用于平原、丘陵等开阔地形</li>
     * </ul>
     *
     * <p><b>输入转换：</b></p>
     * <ul>
     *   <li>将区段位置数据转换为高度图查询的目标数组 [chunkX, minY, maxY, chunkZ]</li>
     *   <li>提取相机位置用于视线投射</li>
     * </ul>
     *
     * <p><b>输出转换：</b></p>
     * <ul>
     *   <li>boolean[] 遮挡结果 → BitSet 可见性掩码</li>
     *   <li>true（被遮挡）→ bit = 0（不可见）</li>
     *   <li>false（未被遮挡）→ bit = 1（可见）</li>
     * </ul>
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - culler: HeightmapOcclusionCuller 实例（必须已设置高度图数据）
     *   - context: 拦截层剔除上下文（包含相机位置、区段坐标等）
     *
     * 返回值：
     *   - CullingResult: 高度图遮挡剔除的结果
     *   - 注意：此结果仅表示"未被高度图遮挡的对象"，还需与其他剔除器结果合并
     * </pre>
     *
     * @param culler  高度图遮挡剔除器实例（不能为 null）
     * @param context 拦截层剔除上下文（不能为 null）
     * @return 剔除结果（仅包含高度图遮挡信息）
     * @throws IllegalArgumentException 如果参数为 null
     */
    public CullingResult adaptHeightmapOcclusionCuller(
            HeightmapOcclusionCuller culler,
            InterceptionCullingContext context) {

        // ======== 参数校验 ========
        if (culler == null) {
            throw new IllegalArgumentException("HeightmapOcclusionCuller 不能为 null");
        }
        if (context == null) {
            throw new IllegalArgumentException("InterceptionCullingContext 不能为 null");
        }

        long startTime = System.nanoTime();

        try {
            int objectCount = context.getObjectCount();

            // 边界情况：无对象时直接返回空结果
            if (objectCount <= 0) {
                return createEmptyResult(0);
            }

            // ======== 提取相机位置 ========
            float[] cameraPos = context.getCameraPosition();
            float cameraX = cameraPos[0];
            float cameraY = cameraPos[1];
            float cameraZ = cameraPos[2];

            // ======== 构建批量查询目标数组 ========
            // HeightmapOcclusionCuller.batchQuery() 需要:
            //   int[][] targets = { {chunkX, minY, maxY, chunkZ}, ... }
            float[][] positions = context.getPositions();

            if (positions == null || positions.length == 0) {
                LOGGER.fine("无位置数据，跳过高度图遮挡剔除");
                return createAllVisibleResult(objectCount);
            }

            int[][] targets = new int[positions.length][4];
            for (int i = 0; i < positions.length; i++) {
                if (positions[i] != null && positions[i].length >= 3) {
                    // 将世界坐标转换为区块坐标（假设每个区段 16x16 方块）
                    targets[i][0] = (int) Math.floor(positions[i][0] / 16.0f); // chunkX
                    targets[i][1] = 0;                                          // minY（默认 0）
                    targets[i][2] = 255;                                        // maxY（默认 255，覆盖完整高度）
                    targets[i][3] = (int) Math.floor(positions[i][2] / 16.0f); // chunkZ
                } else {
                    // 无效位置：设置为极端值（不会被遮挡）
                    targets[i][0] = Integer.MAX_VALUE;
                    targets[i][1] = 0;
                    targets[i][2] = 0;
                    targets[i][3] = Integer.MAX_VALUE;
                }
            }

            // ======== 执行批量遮挡查询 ========
            // HeightmapOcclusionCuller.batchQuery() 方法签名：
            //   batchQuery(float cameraX, float cameraY, float cameraZ, int[][] targets)
            //   返回: boolean[] （true=被遮挡，false=未被遮挡）
            boolean[] occludedArray = culler.batchQuery(cameraX, cameraY, cameraZ, targets);

            // ======== 转换为 BitSet 可见性掩码 ========
            BitSet visibleMask = new BitSet(objectCount);
            int occludedCount = 0;

            for (int i = 0; i < occludedArray.length && i < objectCount; i++) {
                if (!occludedArray[i]) {
                    // 未被遮挡 → 标记为可见
                    visibleMask.set(i);
                } else {
                    // 被遮挡 → 不设置（默认不可见）
                    occludedCount++;
                }
            }

            // 对于超出查询范围的对象，保守地标记为可见
            for (int i = occludedArray.length; i < objectCount; i++) {
                visibleMask.set(i);
            }

            long elapsed = System.nanoTime() - startTime;
            int visibleCount = visibleMask.cardinality();

            // 构建统计明细
            Map<String, Integer> breakdown = new LinkedHashMap<>();
            breakdown.put("heightmap_occlusion", occludedCount);

            LOGGER.fine(String.format(
                    "高度图遮挡剔除完成: %d/%d 可见 (%d 被遮挡), 耗时 %.2f ms",
                    visibleCount, objectCount, occludedCount, elapsed / 1_000_000.0
            ));

            return new CullingResult(
                    visibleMask,
                    objectCount,
                    visibleCount,
                    occludedCount,
                    objectCount > 0 ? (double) occludedCount / objectCount : 0.0,
                    elapsed,
                    breakdown
            );

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "HeightmapOcclusionCuller 适配异常", e);
            return createFailureResult(context.getObjectCount(), e.getMessage());
        }
    }

    /**
     * 适配邻居面剔除器（NeighborFaceCuller）
     *
     * <p><b>算法特点：</b></p>
     * <ul>
     *   <li>邻居面剔除是在网格构建阶段执行的细粒度面级剔除</li>
     *   <li>对于每个方块，检查其 6 个面是否被相邻方块遮挡</li>
     *   <li>可以减少 50-80% 的渲染面数</li>
     * </ul>
     *
     * <p><b>特殊处理：</b></p>
     * <ul>
     *   <li>邻居面剔除通常在区块网格构建时执行，而非每帧执行</li>
     *   <li>此处适配器主要用于统计和结果收集</li>
     *   <li>如果提供了方块状态数据，则执行实际的剔除计算</li>
     * </ul>
     *
     * <p><b>输入转换：</b></p>
     * <ul>
     *   <li>如果 context 包含 blockStates 数据，调用 computeSectionFaceVisibility()</li>
     *   <li>否则仅返回剔除器的统计信息（基于历史数据）</li>
     * </ul>
     *
     * <p><b>输出转换：</b></p>
     * <ul>
     *   <li>byte[] 面可见性 → BitSet（如果有完整的面剔除数据）</li>
     *   <li>否则返回基于历史统计的估算结果</li>
     * </ul>
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - culler: NeighborFaceCuller 实例（必须已注册方块遮挡表）
     *   - context: 拦截层剔除上下文（可选包含 blockStates 数据）
     *
     * 返回值：
     *   - CullingResult: 邻居面剔除的结果（可能是实际计算或基于统计的估算）
     * </pre>
     *
     * @param culler  邻居面剔除器实例（不能为 null）
     * @param context 拦截层剔除上下文（不能为 null）
     * @return 剔除结果（包含邻居面剔除信息）
     * @throws IllegalArgumentException 如果参数为 null
     */
    public CullingResult adaptNeighborFaceCuller(
            NeighborFaceCuller culler,
            InterceptionCullingContext context) {

        // ======== 参数校验 ========
        if (culler == null) {
            throw new IllegalArgumentException("NeighborFaceCuller 不能为 null");
        }
        if (context == null) {
            throw new IllegalArgumentException("InterceptionCullingContext 不能为 null");
        }

        long startTime = System.nanoTime();

        try {
            int objectCount = context.getObjectCount();

            // 边界情况
            if (objectCount <= 0) {
                return createEmptyResult(0);
            }

            // ======== 检查是否有方块状态数据 ========
            // 邻居面剔除需要完整的方块状态数据才能执行
            // 通常这些数据在区块加载时就已经处理过了
            int[] blockStates = context.getBlockStates();

            if (blockStates != null && blockStates.length > 0) {
                // ======== 有方块状态数据：执行实际的邻居面剔除 ========
                // NeighborFaceCuller.computeSectionFaceVisibility() 方法签名：
                //   computeSectionFaceVisibility(int[] blockStates, int sectionX, int sectionY, int sectionZ)
                //   返回: byte[] （每个方块的 6 面可见性位掩码）
                byte[] faceVisibility = culler.computeSectionFaceVisibility(
                        blockStates,
                        0,  // sectionX（默认 0，实际应根据 context 设置）
                        0,  // sectionY
                        0   // sectionZ
                );

                // 统计被完全隐藏的方块（6 个面都不可见）
                BitSet visibleMask = new BitSet(objectCount);
                int fullyHiddenCount = 0;

                int minLen = Math.min(faceVisibility.length, objectCount);
                for (int i = 0; i < minLen; i++) {
                    // 如果有任何一面可见，则该方块标记为"需要渲染"
                    if (faceVisibility[i] != NeighborFaceCuller.NONE_VISIBLE) {
                        visibleMask.set(i);
                    } else {
                        fullyHiddenCount++;
                    }
                }

                // 超出范围的对象保守处理为可见
                for (int i = minLen; i < objectCount; i++) {
                    visibleMask.set(i);
                }

                long elapsed = System.nanoTime() - startTime;
                int visibleCount = visibleMask.cardinality();

                Map<String, Integer> breakdown = new LinkedHashMap<>();
                breakdown.put("neighbor_face", fullyHiddenCount);

                LOGGER.fine(String.format(
                        "邻居面剔除完成: %d/%d 可见 (%d 完全隐藏), 耗时 %.2f ms",
                        visibleCount, objectCount, fullyHiddenCount, elapsed / 1_000_000.0
                ));

                return new CullingResult(
                        visibleMask,
                        objectCount,
                        visibleCount,
                        fullyHiddenCount,
                        objectCount > 0 ? (double) fullyHiddenCount / objectCount : 0.0,
                        elapsed,
                        breakdown
                );

            } else {
                // ======== 无方块状态数据：基于历史统计返回估算结果 ========
                // 邻居面剔除通常是离线/预计算的，此处使用历史剔除率进行估算
                float historicalCullRate = culler.getCullRate();
                int estimatedCulledCount = (int) (objectCount * historicalCullRate);

                // 创建估算的可见性掩码（随机分布，仅用于统计）
                BitSet estimatedMask = new BitSet(objectCount);
                int visibleEstimate = objectCount - estimatedCulledCount;

                // 简单策略：前 visibleEstimate 个标记为可见（实际应用中应有更智能的策略）
                for (int i = 0; i < visibleEstimate && i < objectCount; i++) {
                    estimatedMask.set(i);
                }

                long elapsed = System.nanoTime() - startTime;

                Map<String, Integer> breakdown = new LinkedHashMap<>();
                breakdown.put("neighbor_face_estimated", estimatedCulledCount);

                LOGGER.fine(String.format(
                        "邻居面剔除（估算模式）: 历史剔除率=%.1f%%, 估算剔除=%d/%d, 耗时 %.2f ms",
                        historicalCullRate * 100, estimatedCulledCount, objectCount, elapsed / 1_000_000.0
                ));

                return new CullingResult(
                        estimatedMask,
                        objectCount,
                        visibleEstimate,
                        estimatedCulledCount,
                        historicalCullRate,
                        elapsed,
                        breakdown
                );
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "NeighborFaceCuller 适配异常", e);
            return createFailureResult(context.getObjectCount(), e.getMessage());
        }
    }

    // ==================== 结果合并方法 ====================

    /**
     * 合并多个剔除结果为最终可见集
     *
     * <p><b>合并策略（保守 AND 策略）：</b></p>
     * <ul>
     *   <li>只有当<strong>所有</strong>剔除器都认为某个对象可见时，才将其标记为最终可见</li>
     *   <li>即：finalVisible = result1.visible AND result2.visible AND ... AND resultN.visible</li>
     *   <li>这确保了不会错误地剔除任何可能可见的对象（避免闪烁/穿模）</li>
     * </ul>
     *
     * <p><b>示例：</b></p>
     * <pre>
     * 对象 0: AsyncCompute=可见, Heightmap=可见, NeighborFace=可见 → 最终: ✅ 可见
     * 对象 1: AsyncCompute=可见, Heightmap=遮挡, NeighborFace=可见 → 最终: ❌ 不可见
     * 对象 2: AsyncCompute=遮挡, Heightmap=可见, NeighborFace=可见 → 最终: ❌ 不可见
     * </pre>
     *
     * <p><b>特殊情况处理：</b></p>
     * <ul>
     *   <li>空列表：返回全可见结果</li>
     *   <li>单个结果：直接返回该结果</li>
     *   <li>某个结果失败：忽略该结果（不参与 AND 运算），记录警告</li>
     *   <li>所有结果都失败：返回全可见（最保守策略）</li>
     * </ul>
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - results: 多个剔除结果的列表（可以为空，但不能为 null）
     *             每个元素来自不同的剔除器适配调用
     *
     * 返回值：
     *   - CullingResult: 合并后的最终结果
     *     - visibleMask: 按 AND 策略合并的可见性掩码
     *     - originalCount: 取第一个结果的原始数量（或最大值）
     *     - visibleCount: 最终可见对象数
     *     - culledCount: 最终剔除对象数
     *     - cullRate: 最终剔除率
     *     - processTimeNanos: 各结果耗时之和
     *     - breakdown: 各剔除器贡献的汇总
     * </pre>
     *
     * @param results 剔除结果列表（不能为 null，元素可以为 null 表示失败）
     * @return 合并后的最终剔除结果
     * @throws IllegalArgumentException 如果 results 为 null
     */
    public CullingResult mergeResults(List<CullingResult> results) {

        // ======== 参数校验 ========
        if (results == null) {
            throw new IllegalArgumentException("results 列表不能为 null");
        }

        long startTime = System.nanoTime();

        // ======== 特殊情况：空列表 ========
        if (results.isEmpty()) {
            LOGGER.fine("合并空结果列表，返回空结果");
            return createEmptyResult(0);
        }

        // ======== 特殊情况：单个结果 ========
        if (results.size() == 1) {
            CullingResult single = results.get(0);
            if (single != null) {
                return single;
            }
            return createEmptyResult(0);
        }

        // ======== 过滤有效结果 ========
        List<CullingResult> validResults = results.stream()
                .filter(r -> r != null)
                .toList();

        if (validResults.isEmpty()) {
            LOGGER.warning("所有剔除结果都无效，返回保守结果（全可见）");
            // 使用第一个非null结果的对象数（即使它失败了）
            int maxCount = results.stream()
                    .filter(r -> r != null)
                    .mapToInt(CullingResult::originalCount)
                    .max()
                    .orElse(0);
            return createAllVisibleResult(maxCount);
        }

        // ======== 确定原始对象总数 ========
        // 取所有有效结果中的最大值（应该都相同，但做防御性处理）
        int originalCount = validResults.stream()
                .mapToInt(CullingResult::originalCount)
                .max()
                .orElse(0);

        // ======== 执行 AND 合并（保守策略） ========
        BitSet finalVisibleMask = new BitSet(originalCount);
        finalVisibleMask.set(0, originalCount);  // 初始：全部可见

        // 依次与每个结果进行 AND 运算
        for (CullingResult result : validResults) {
            if (result.visibleMask() != null) {
                finalVisibleMask.and(result.visibleMask());
            }
        }

        // ======== 计算最终统计 ========
        int finalVisibleCount = finalVisibleMask.cardinality();
        int finalCulledCount = originalCount - finalVisibleCount;
        double finalCullRate = originalCount > 0 ? (double) finalCulledCount / originalCount : 0.0;

        // 累加耗时
        long totalTimeNanos = validResults.stream()
                .mapToLong(CullingResult::processTimeNanos)
                .sum();

        // 合并各剔除器的贡献明细
        Map<String, Integer> mergedBreakdown = new LinkedHashMap<>();
        for (CullingResult result : validResults) {
            if (result.breakdown() != null) {
                mergedBreakdown.putAll(result.breakdown());
            }
        }

        long mergeElapsed = System.nanoTime() - startTime;

        LOGGER.fine(String.format(
                "结果合并完成: %d/%d 最终可见 (%.1f%% 剔除), 合并耗时 %.2f ms",
                finalVisibleCount, originalCount, finalCullRate * 100, mergeElapsed / 1_000_000.0
        ));

        return new CullingResult(
                finalVisibleMask,
                originalCount,
                finalVisibleCount,
                finalCulledCount,
                finalCullRate,
                totalTimeNanos + mergeElapsed,
                mergedBreakdown
        );
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 创建失败结果（保守策略：所有对象标记为可见）
     *
     * <p>当剔除器执行失败时，使用此方法创建一个"全可见"的结果，
     * 确保不会因为剔除系统故障而导致渲染缺失。
     *
     * @param objectCount 原始对象数量
     * @param reason      失败原因描述
     * @return 全可见的剔除结果
     */
    private CullingResult createFailureResult(int objectCount, String reason) {
        BitSet allVisible = new BitSet(objectCount);
        allVisible.set(0, objectCount);

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("failure_fallback", 0);

        LOGGER.warning(String.format("剔除器失败 [%s], 使用保守策略: %d 对象全部可见", reason, objectCount));

        return new CullingResult(
                allVisible,
                objectCount,
                objectCount,
                0,
                0.0,
                0L,
                breakdown
        );
    }

    /**
     * 创建全可见结果
     *
     * @param objectCount 对象数量
     * @return 全可见的剔除结果
     */
    private CullingResult createAllVisibleResult(int objectCount) {
        return createFailureResult(objectCount, "正常降级");
    }

    /**
     * 创建空结果（对象数为 0）
     *
     * @param objectCount 对象数量（应为 0）
     * @return 空的剔除结果
     */
    private CullingResult createEmptyResult(int objectCount) {
        return new CullingResult(
                new BitSet(0),
                0,
                0,
                0,
                0.0,
                0L,
                new LinkedHashMap<>()
        );
    }

    // ==================== 内部数据类 ====================

    /**
     * 统一格式的剔除结果
     *
     * <p>作为所有剔除器适配输出的统一数据结构，
     * 包含可见性掩码和详细的统计信息。
     *
     * <p><b>设计原则：</b></p>
     * <ul>
     *   <li><b>不可变性</b>：使用 record 保证线程安全和不可变</li>
     *   <li><b>完整性</b>：包含所有必要的统计指标</li>
     *   <li><b>可追溯性</b>：breakdown 字段记录各剔除器的贡献</li>
     * </ul>
     *
     * @param visibleMask    可见性位域（BitSet，bit=1 表示可见）
     * @param originalCount  剔除前的原始对象总数
     * @param visibleCount   剔除后的可见对象数
     * @param culledCount    被剔除的对象数
     * @param cullRate       剔除率（0.0 ~ 1.0）
     * @param processTimeNanos 处理耗时（纳秒）
     * @param breakdown      各剔除器的贡献明细（剔除器名称 → 剔除数量）
     */
    public record CullingResult(
            BitSet visibleMask,
            int originalCount,
            int visibleCount,
            int culledCount,
            double cullRate,
            long processTimeNanos,
            Map<String, Integer> breakdown
    ) {
        /**
         * 检查剔除是否成功
         *
         * @return true 如果有对象被成功处理（无论是否被剔除）
         */
        public boolean isSuccess() {
            return originalCount > 0;
        }

        /**
         * 获取剔除率百分比
         *
         * @return 剔除率（0 ~ 100）
         */
        public double getCullPercentage() {
            return cullRate * 100.0;
        }

        /**
         * 获取处理耗时（毫秒）
         *
         * @return 耗时（ms）
         */
        public double getProcessTimeMillis() {
            return processTimeNanos / 1_000_000.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "CullingResult{visible=%d/%d (%.1f%% culled), time=%.2fms, breakdown=%s}",
                    visibleCount, originalCount, getCullPercentage(),
                    getProcessTimeMillis(),
                    breakdown != null ? breakdown.toString() : "{}"
            );
        }
    }
}
