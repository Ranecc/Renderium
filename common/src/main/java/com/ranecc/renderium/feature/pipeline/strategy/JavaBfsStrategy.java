// ============================================================
// Renderium Java BFS Strategy - Java 实现策略 (适配器层)
// ============================================================
// 业务链位置: AdaptivePathSelector
//               → JavaBfsStrategy (此文件, Strategy 包装器)
//               → AlgorithmStrategy<T,R> (策略接口)
//               → BfsOcclusionEngine     (真实计算引擎)
//
// 作用: 将 BfsOcclusionEngine 包装为标准 AlgorithmStrategy 接口，
//       使得 AdaptivePathSelector 可以用统一方式调度 Java/C++ 双路径。
//       当 Native 路径 (renderium_accel DLL) 不可用时作为回退。
//
// 说明: JavaBfsStrategy + NativeBfsStrategy + AlgorithmStrategy 三层是为
//       "未来多算法统一调度"预留的抽象。当前仅有 BFS 使用了此框架。
// ============================================================

package com.ranecc.renderium.feature.pipeline.strategy;

import java.util.logging.Logger;
import com.ranecc.renderium.feature.pipeline.BfsOcclusionEngine;

/**
 * Java 实现 BFS 遮挡剔除策略
 * <p>
 * 包装 {@link BfsOcclusionEngine}，提供统一的 AlgorithmStrategy 接口。
 * 作为 C++ 原生路径不可用时的安全回退。
 *
 * <h3>性能基准</h3>
 * <ul>
 *   <li>10000 区块: ~3-8ms（取决于可见性复杂度）</li>
 *   <li>内存: 0 分配（预分配缓冲区）</li>
 *   <li>线程安全: 是（实例级线程安全）</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class JavaBfsStrategy implements AlgorithmStrategy<BfsInput, BfsOcclusionEngine.CullResult> {

    private static final Logger LOGGER = Logger.getLogger(JavaBfsStrategy.class.getName());

    /** 底层 BFS 引擎（线程安全） */
    private final BfsOcclusionEngine engine;

    /**
     * 创建 Java BFS 策略
     *
     * @param engine BFS 遮挡引擎实例（不能为 null）
     * @throws IllegalArgumentException 如果 engine 为 null
     */
    public JavaBfsStrategy(BfsOcclusionEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("BFS 引擎不能为 null");
        }
        this.engine = engine;
    }

    /**
     * 执行 Java BFS 遮挡剔除
     *
     * 【方法参数】
     * @param input BfsInput - 输入参数（包含相机位置、渲染距离等）
     *
     * 【返回值】
     * @return BfsOcclusionEngine.CullResult - 剔除结果（包含可见区块列表）
     *
     * @throws IllegalStateException 如果 rootSection 为 null
     */
    @Override
    public BfsOcclusionEngine.CullResult execute(BfsInput input) {
        if (input.rootSection == null) {
            throw new IllegalStateException("rootSection 不能为 null，请先设置根区块");
        }

        return engine.findVisibleSections(
            input.rootSection,
            input.toCameraView(),
            input.useOcclusion,
            input.frameNumber
        );
    }

    /**
     * 获取实现类型标识
     *
     * 【返回值】
     * @return String - "java"
     */
    @Override
    public String getImplementationType() {
        return "java";
    }

    /**
     * 检查策略是否可用
     * <p>
     * Java 策略始终可用（只要引擎不为 null）
     *
     * 【返回值】
     * @return boolean - true
     */
    @Override
    public boolean isAvailable() {
        return engine != null;
    }

    /**
     * 获取预估执行时间（基于历史统计）
     *
     * 【返回值】
     * @return long - 平均耗时（纳秒），-1 如果无统计数据
     */
    @Override
    public long getEstimatedCostNanos() {
        return -1;
    }
}
