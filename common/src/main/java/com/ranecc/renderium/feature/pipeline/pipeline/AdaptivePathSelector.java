// ============================================================
// Renderium Adaptive Path Selector (Enhanced)
// ============================================================
// 原有功能: 异步/同步路径切换（滞后熔断器）
// 新增功能: Java/C++ 算法路径动态调度
//
// 调度策略:
//   1. GPU 占用率 < 70% → 优先使用 C++ Native 路径
//   2. GPU 占用率 >= 70% → 使用 Java 路径（避免 GPU 过载）
//   3. Native 不可用 → 自动降级到 Java
//
// 性能开销: ~50ns/帧（GPU 查询 + 策略选择）
// ============================================================

package com.ranecc.renderium.feature.pipeline.pipeline;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 自适应路径选择器（增强版）
 * <p>
 * 在原有异步/同步路径切换基础上，
 * 新增 **Java/C++ 算法路径动态调度** 功能。
 *
 * <h3>双维度决策</h3>
 * <pre>
 * 维度 1: 执行模式 (原有)
 *   ASYNC → 低优先级扩展在工作线程执行
 *   SYNC  → 所有扩展在主线程执行
 *
 * 维度 2: 算法实现 (新增)
 *   java   → Java 纯实现（安全但较慢）
 *   native → C++ 原生加速（高性能）
 * </pre>
 *
 * <h3>调度规则</h3>
 * <ul>
 *   <li>GPU 占用率 &lt; 70% 且 Native 可用 → 选择 native</li>
 *   <li>GPU 占用率 &gt;= 70% 或 Native 不可用 → 选择 java</li>
 *   <li>可配置强制使用某一路径（调试/测试）</li>
 * </ul>
 *
 * @since 1.0.0 (enhanced)
 */
public final class AdaptivePathSelector {

    private static final Logger LOGGER = Logger.getLogger(AdaptivePathSelector.class.getName());

    // ==================== 原有状态（保持兼容）====================

    /** 路径状态 */
    public enum PathState {
        ASYNC,
        SYNC
    }

    private static final int N_ON = 1;
    private static final int N_OFF = 3;

    private volatile PathState currentState = PathState.ASYNC;
    private final AtomicInteger consecutiveSuccess = new AtomicInteger(0);
    private final AtomicInteger consecutiveFailure = new AtomicInteger(0);
    private final AtomicInteger totalTransitions = new AtomicInteger(0);

    // ==================== 新增：算法调度状态 ====================

    /** 算法实现类型 */
    public enum AlgorithmImpl {
        JAVA,
        NATIVE
    }

    /** GPU 占用率阈值（超过此值时避免使用 Native 路径） */
    private static final float GPU_USAGE_THRESHOLD = 0.7f;

    /** 原生加速器引用（可为 null，可通过 setAccelerator() 注入） */
    private volatile RenderiumAccelerator accelerator;

    /** 强制使用 Java 路径（用于调试） */
    private volatile boolean forceJava = false;

    /** 强制使用 Native 路径（用于性能测试） */
    private volatile boolean forceNative = false;

    /** 统计: Native 路径选择次数 */
    private final AtomicInteger nativeSelectionCount = new AtomicInteger(0);

    /** 统计: Java 路径选择次数 */
    private final AtomicInteger javaSelectionCount = new AtomicInteger(0);

    // ==================== 构造函数 ====================

    /**
     * 创建自适应路径选择器（无原生加速器）
     * <p>
     * 此构造函数创建的选择器将始终选择 Java 路径。
     */
    public AdaptivePathSelector() {
        this.accelerator = null;
    }

    /**
     * 创建自适应路径选择器（带原生加速器支持）
     *
     * @param accelerator 原生加速器（可为 null）
     */
    public AdaptivePathSelector(RenderiumAccelerator accelerator) {
        this.accelerator = accelerator;
    }

    // ==================== 原有方法（保持不变）====================

    public PathState decide(boolean asyncAvailable) {
        if (currentState == PathState.ASYNC) {
            if (asyncAvailable) {
                consecutiveFailure.set(0);
                consecutiveSuccess.incrementAndGet();
                return PathState.ASYNC;
            } else {
                int failures = consecutiveFailure.incrementAndGet();
                if (failures >= N_OFF) {
                    transitionTo(PathState.SYNC);
                }
                return PathState.ASYNC;
            }
        } else {
            if (asyncAvailable) {
                int successes = consecutiveSuccess.incrementAndGet();
                consecutiveFailure.set(0);
                if (successes >= N_ON) {
                    transitionTo(PathState.ASYNC);
                    return PathState.ASYNC;
                }
                return PathState.SYNC;
            } else {
                consecutiveSuccess.set(0);
                return PathState.SYNC;
            }
        }
    }

    public PathState getCurrentState() { return currentState; }
    public boolean shouldUseAsync() { return currentState == PathState.ASYNC; }
    public int getTotalTransitions() { return totalTransitions.get(); }
    public int getConsecutiveSuccess() { return consecutiveSuccess.get(); }
    public int getConsecutiveFailure() { return consecutiveFailure.get(); }

    private void transitionTo(PathState newState) {
        if (currentState != newState) {
            currentState = newState;
            consecutiveSuccess.set(0);
            consecutiveFailure.set(0);
            totalTransitions.incrementAndGet();
            LOGGER.fine(String.format("[AdaptivePathSelector] 路径切换 → %s (总切换: %d)",
                    newState.name(), totalTransitions.get()));
        }
    }

    // ==================== 新增：算法调度方法 ====================

    /**
     * 选择 BFS 遮挡剔除的算法实现
     * <p>
     * 根据当前系统状态（GPU 占用率、Native 可用性）
     * 动态选择最优的算法实现路径。
     *
     * 【方法参数】
     * @param gpuUsage float - 当前 GPU 占用率 (0.0-1.0)
     * @param preferNative boolean - 是否优先考虑 Native 路径
     *
     * 【返回值】
     * @return AlgorithmImpl - 选定的算法实现类型:
     *         <ul>
     *           <li>NATIVE - C++ 原生加速</li>
     *           <li>JAVA - Java 纯实现</li>
     *         </ul>
     */
    public AlgorithmImpl selectBfsImplementation(float gpuUsage, boolean preferNative) {
        if (forceNative && isNativeAvailable()) {
            nativeSelectionCount.incrementAndGet();
            return AlgorithmImpl.NATIVE;
        }

        if (forceJava || !isNativeAvailable()) {
            javaSelectionCount.incrementAndGet();
            return AlgorithmImpl.JAVA;
        }

        // 核心调度逻辑: GPU 占用率低于阈值且用户偏好 Native
        if (preferNative && gpuUsage < GPU_USAGE_THRESHOLD) {
            nativeSelectionCount.incrementAndGet();
            LOGGER.fine(String.format(
                "[AdaptivePathSelector] 选择 Native BFS (gpuUsage=%.2f%%)",
                gpuUsage * 100));
            return AlgorithmImpl.NATIVE;
        }

        javaSelectionCount.incrementAndGet();
        return AlgorithmImpl.JAVA;
    }

    /**
     * 创建 BFS 算法策略实例（工厂方法）
     * <p>
     * 根据当前状态自动选择 Java 或 Native 实现，
     * 返回统一的 {@link AlgorithmStrategy} 接口。
     *
     * 【方法参数】
     * @param gpuUsage float - GPU 占用率 (0.0-1.0)
     * @param preferNative boolean - 是否优先 Native
     * @param javaEngine BfsOcclusionEngine - Java 引擎实例（不能为 null）
     * @param maxSections int - 最大区块数（仅 Native 需要）
     *
     * 【返回值】
     * @return AlgorithmStrategy&lt;BfsInput, CullResult&gt; - 选定的策略实例
     */
    public AlgorithmStrategy<BfsInput, BfsOcclusionEngine.CullResult> createBfsStrategy(
            float gpuUsage,
            boolean preferNative,
            BfsOcclusionEngine javaEngine,
            int maxSections) {

        AlgorithmImpl impl = selectBfsImplementation(gpuUsage, preferNative);

        switch (impl) {
            case NATIVE:
                NativeBfsStrategy nativeStrategy = new NativeBfsStrategy(accelerator, maxSections);
                if (nativeStrategy.initialize()) {
                    return nativeStrategy;
                }
                LOGGER.warning("Native BFS 初始化失败，降级到 Java");
                // 故意 fall-through 到 Java

            case JAVA:
            default:
                return new JavaBfsStrategy(javaEngine);
        }
    }

    // ==================== 配置方法 ====================

    /**
     * 设置强制使用 Java 路径
     *
     * @param force true 表示强制 Java
     */
    public void setForceJava(boolean force) {
        this.forceJava = force;
        if (force) this.forceNative = false;
    }

    /**
     * 设置强制使用 Native 路径
     *
     * @param force true 表示强制 Native
     */
    public void setForceNative(boolean force) {
        this.forceNative = force;
        if (force) this.forceJava = false;
    }

    // ==================== 查询方法 ====================

    /**
     * 检查 Native 加速器是否可用
     *
     * 【返回值】
     * @return boolean - Native 路径可用时返回 true
     */
    public boolean isNativeAvailable() {
        return accelerator != null
            && accelerator.isInitialized()
            && accelerator.bfs() != null;
    }

    /**
     * 注入/更新原生加速器引用 🔧
     * <p>
     * 用于在 RenderiumCore 初始化完成后，
     * 将已初始化的 accelerator 实例注入到路径选择器中。
     * <p>
     * 此方法解决了以下问题：
     * - AdaptivePathSelector 在字段声明时创建（无参构造）
     * - 此时 accelerator 尚未初始化，导致 isNativeAvailable() 永远返回 false
     * - Native BFS 路径永远不会被选择
     *
     * 【方法参数】
     * @param newAccelerator RenderiumAccelerator - 已初始化的原生加速器实例（可为 null）
     *
     * 【线程安全】
     * 使用 volatile 字段保证跨线程可见性
     *
     * 【使用示例】
     * <pre>{@code
     * // 在 RenderiumCore.initializeNativeAccelerator() 中：
     * accelerator = RenderiumAccelerator.getInstance();
     * accelerator.initialize();
     * pathSelector.setAccelerator(accelerator);  // ← 关键：注入后 Native 路径激活
     * }</pre>
     */
    public void setAccelerator(RenderiumAccelerator newAccelerator) {
        this.accelerator = newAccelerator;

        if (newAccelerator != null && newAccelerator.isInitialized()) {
            LOGGER.info(String.format(
                    "[AdaptivePathSelector] ✅ 原生加速器已注入 (version=%s, nativeBFS=%s)",
                    newAccelerator.getVersion(),
                    newAccelerator.bfs() != null ? "可用" : "不可用"
            ));
        } else {
            LOGGER.fine("[AdaptivePathSelector] 注入的加速器为 null 或未初始化");
        }
    }

    /** 获取当前注入的原生加速器引用（用于调试） */
    public RenderiumAccelerator getAccelerator() {
        return this.accelerator;
    }

    /** 获取 Native 选择次数统计 */
    public int getNativeSelectionCount() { return nativeSelectionCount.get(); }

    /** 获取 Java 选择次数统计 */
    public int getJavaSelectionCount() { return javaSelectionCount.get(); }

    /**
     * 获取格式化的调度统计报告
     *
     * 【返回值】
     * @return String - 统计信息
     */
    public String getSchedulingStatistics() {
        int total = nativeSelectionCount.get() + javaSelectionCount.get();
        double nativePct = total > 0 ? (100.0 * nativeSelectionCount.get() / total) : 0;

        return String.format(
            "AdaptivePathSelector{transitions=%d, native=%.0f%% (%d), java=%.0f%% (%d), available=%s}",
            getTotalTransitions(),
            nativePct, nativeSelectionCount.get(),
            100 - nativePct, javaSelectionCount.get(),
            isNativeAvailable()
        );
    }
}
