// ============================================================
// Renderium Algorithm Strategy - 算法策略统一接口
// ============================================================
// 解决"算法双轨制"问题: Java/C++ 并行实现未统一调度
// 通过 Strategy Pattern 实现运行时动态选择最优路径
//
// 设计原则:
//   1. 接口抽象: 统一 Java 和 C++ (Native) 的调用方式
//   2. 性能透明: getImplementationType() 允许监控和调试
//   3. 零开销抽象: 接口方法内联优化（JIT 友好）
//   4. 优雅降级: Native 路径不可用时自动回退 Java
//
// 使用示例:
//   AlgorithmStrategy<BfsInput, BfsResult> strategy =
//       adaptiveSelector.selectBfsStrategy(gpuUsage, preferNative);
//   BfsResult result = strategy.execute(bfsInput);
//   LOGGER.info("使用 " + strategy.getImplementationType() + " 路径");
// ============================================================

package com.renderium.pipeline.strategy;

/**
 * 算法策略接口 - 统一 Java/C++ 双路径
 * <p>
 * 将算法的输入/输出类型参数化，允许同一算法有多种实现
 * （Java 纯实现、C++ 原生加速、GPU 计算等）。
 * <p>
 * <b>性能保证</b>:
 * <ul>
 *   <li>接口方法会被 JIT 内联（-XX:+Inline）</li>
 *   <li>虚方法调用开销 ~2-5ns，远小于算法本身耗时</li>
 *   <li>避免反射和动态代理</li>
 * </ul>
 *
 * @param <T> 输入数据类型
 * @param <R> 计算结果类型
 *
 * @since 1.0.0
 */
public interface AlgorithmStrategy<T, R> {

    /**
     * 执行算法计算
     * <p>
     * 核心计算方法，不同实现可能使用不同的后端：
     * <ul>
     *   <li>"java" - 纯 Java 实现（安全但较慢）</li>
     *   <li>"native" - C++ 原生库（通过 Panama FFM 调用，高性能）</li>
     *   <li>"gpu" - GPU Compute Shader（未来扩展）</li>
     * </ul>
     *
     * 【方法参数】
     * @param input T - 算法输入数据（封装所有必需的参数）
     *
     * 【返回值】
     * @return R - 计算结果
     *
     * @throws IllegalStateException 如果底层资源未初始化
     * @throws IllegalArgumentException 如果输入数据无效
     */
    R execute(T input);

    /**
     * 获取算法实现类型标识
     * <p>
     * 用于：
     * <ul>
     *   <li>性能监控和日志记录</li>
     *   <li>调试时确认当前使用的路径</li>
     *   <li>A/B 测试对比不同实现的性能</li>
     * </ul>
     *
     * 【返回值】
     * @return String - 实现类型标识符:
     *         <ul>
     *           <li>"java" - Java 纯实现</li>
     *           <li>"native" - C++ 原生实现</li>
     *           <li>"gpu" - GPU 加速实现</li>
     *           <li>"hybrid" - 混合实现</li>
     *         </ul>
     */
    String getImplementationType();

    /**
     * 检查此策略是否可用
     * <p>
     * 某些策略可能在运行时变为不可用（例如原生库卸载、GPU 丢失等）。
     * 在调用 execute() 之前应先检查此方法。
     *
     * 【返回值】
     * @return boolean - true 表示策略可用且可以执行计算
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 获取策略的性能预估（可选）
     * <p>
     * 返回此策略在典型输入下的预估执行时间（纳秒）。
     * 用于 AdaptivePathSelector 的决策参考。
     * <p>
     * 默认实现返回 -1（未知），表示需要实际测量。
     *
     * 【返回值】
     * @return long - 预估执行时间（纳秒），-1 表示未知
     */
    default long getEstimatedCostNanos() {
        return -1;
    }
}
