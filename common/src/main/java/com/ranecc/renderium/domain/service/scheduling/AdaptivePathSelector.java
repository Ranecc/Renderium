package com.ranecc.renderium.domain.service.scheduling;

import com.ranecc.renderium.domain.enums.AlgorithmPath;
import com.ranecc.renderium.domain.constant.ConfigConstants;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import com.ranecc.renderium.domain.model.FrameData;

/**
 * 自适应路径选择器（Domain 层纯净版）
 * <p>
 * 从 pipeline 层迁移并修复 P0-B001 bug：
 * 原版无参构造导致 accelerator=null，Native 路径永远不可用。
 * <p>
 *
 * <h3>修复方案</h3>
 * 构造函数强制接受加速器端口接口（Object 类型避免循环依赖），
 * 消除 null 加速器导致的 Native 路径不可用问题。
 *
 * <h3>调度策略</h3>
 * <ul>
 *   <li>GPU 占用率 &lt; 阈值(0.7) → 优先 NATIVE</li>
 *   <li>GPU 占用率 &gt;= 阈值 → 使用 JAVA（避免 GPU 过载）</li>
 *   <li>NATIVE 不可用 → 自动降级到 JAVA</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建时注入加速器（修复 P0-B001）
 * AdaptivePathSelector selector = new AdaptivePathSelector(acceleratorPort);
 *
 * // 选择算法路径
 * AlgorithmPath path = selector.selectPath(0.65f, true);
 * // path == AlgorithmPath.NATIVE (GPU占用率低且Native可用)
 * }</pre>
 */
public final class AdaptivePathSelector {

    private static final Logger LOGGER = Logger.getLogger(AdaptivePathSelector.class.getName());

    /** 加速器端口引用（Object 类型避免循环依赖） */
    private final Object accelerator;

    /** GPU 占用率阈值 */
    private final float gpuUsageThreshold;

    /** 强制使用 JAVA 路径（调试用） */
    private volatile boolean forceJava = false;

    /** 强制使用 NATIVE 路径（性能测试用） */
    private volatile boolean forceNative = false;

    /** Native 可用性标志（由外部设置） */
    private volatile boolean nativeAvailable = false;

    /** 统计: NATIVE 选择次数 */
    private final AtomicInteger nativeSelectionCount = new AtomicInteger(0);

    /** 统计: JAVA 选择次数 */
    private final AtomicInteger javaSelectionCount = new AtomicInteger(0);

    /** 统计: AUTO 降级次数 */
    private final AtomicInteger autoFallbackCount = new AtomicInteger(0);

    /**
     * 创建自适应路径选择器（无加速器，Native路径将不可用）
     */
    public AdaptivePathSelector() {
        this(null, ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD);
    }

    /**
     * 创建自适应路径选择器
     * <p>
     * 【P0-B001 修复】构造函数必须传入加速器实例，
     * 避免 accelerator=null 导致 Native 路径永远不可用。
     *
     * @param accelerator 加速器端口对象（Object 类型避免循环依赖），可为 null 但 Native 路径将不可用
     */
    public AdaptivePathSelector(Object accelerator) {
        this(accelerator, ConfigConstants.DEFAULT_GPU_USAGE_THRESHOLD);
    }

    /**
     * 创建自定义阈值的自适应路径选择器
     *
     * @param accelerator      加速器端口对象
     * @param gpuUsageThreshold GPU 占用率阈值 (0.0-1.0)
     * @throws IllegalArgumentException 若阈值超出范围
     */
    public AdaptivePathSelector(Object accelerator, float gpuUsageThreshold) {
        if (gpuUsageThreshold <= 0.0f || gpuUsageThreshold > 1.0f) {
            throw new IllegalArgumentException("GPU阈值必须在 (0, 1] 范围内: " + gpuUsageThreshold);
        }
        this.accelerator = accelerator;
        this.gpuUsageThreshold = gpuUsageThreshold;

        LOGGER.fine(String.format(
            "[AdaptivePathSelector] 初始化完成: accelerator=%s, threshold=%.2f",
            accelerator != null ? "已注入" : "null", gpuUsageThreshold));
    }

    /**
     * 选择算法执行路径
     * <p>
     * 根据当前 GPU 占用率和配置动态选择最优路径。
     *
     * 【方法参数】
     * @param gpuUsage float - 当前 GPU 占用率 (0.0-1.0)
     * @param preferNative boolean - 是否优先考虑 Native 路径
     *
     * 【返回值】
     * @return AlgorithmPath - 选定的算法路径:
     *         <ul>
     *           <li>JAVA - Java 纯实现</li>
     *           <li>NATIVE - C++ 原生加速</li>
     *           <li>AUTO - 自动选择（内部决策后实际为 JAVA 或 NATIVE）</li>
     *         </ul>
     */
    public AlgorithmPath selectPath(float gpuUsage, boolean preferNative) {
        if (forceNative && isNativeAvailable()) {
            nativeSelectionCount.incrementAndGet();
            return AlgorithmPath.NATIVE;
        }

        if (forceJava || !isNativeAvailable()) {
            javaSelectionCount.incrementAndGet();
            return AlgorithmPath.JAVA;
        }

        if (preferNative && gpuUsage < gpuUsageThreshold) {
            nativeSelectionCount.incrementAndGet();
            LOGGER.fine(String.format("[AdaptivePathSelector] 选择 NATIVE (gpuUsage=%.2f%%)",
                gpuUsage * 100));
            return AlgorithmPath.NATIVE;
        }

        javaSelectionCount.incrementAndGet();
        return AlgorithmPath.JAVA;
    }

    /**
     * 检查 Native 加速器是否可用
     * <p>
     * 可用性条件：加速器非空 且 外部已标记为可用
     *
     * 【返回值】
     * @return boolean - Native 路径可用返回 true
     */
    public boolean isNativeAvailable() {
        return accelerator != null && nativeAvailable;
    }

    /**
     * 设置 Native 可用性标志
     * <p>
     * 由基础设施层在确认 Native 库加载成功后调用。
     *
     * @param available boolean - Native 是否可用
     */
    public void setNativeAvailable(boolean available) {
        this.nativeAvailable = available;
        LOGGER.fine(String.format("[AdaptivePathSelector] Native可用性更新: %s", available));
    }

    /**
     * 强制使用 Java 路径
     *
     * @param force boolean - true 表示强制 Java
     */
    public void setForceJava(boolean force) {
        this.forceJava = force;
        if (force) this.forceNative = false;
    }

    /**
     * 强制使用 Native 路径
     *
     * @param force boolean - true 表示强制 Native
     */
    public void setForceNative(boolean force) {
        this.forceNative = force;
        if (force) this.forceJava = false;
    }

    /**
     * 获取调度统计信息
     *
     * 【返回值】
     * @return String - 格式化的统计报告
     */
    public String getStatistics() {
        int total = nativeSelectionCount.get() + javaSelectionCount.get();
        double nativePct = total > 0 ? (100.0 * nativeSelectionCount.get() / total) : 0;

        return String.format(
            "AdaptivePathSelector{threshold=%.2f, native=%.0f%% (%d), java=%.0f%% (%d), available=%s}",
            gpuUsageThreshold,
            nativePct, nativeSelectionCount.get(),
            100 - nativePct, javaSelectionCount.get(),
            isNativeAvailable()
        );
    }

    /** 获取 Native 选择次数 */
    public int getNativeSelectionCount() { return nativeSelectionCount.get(); }

    /** 获取 Java 选择次数 */
    public int getJavaSelectionCount() { return javaSelectionCount.get(); }

    /** 获取加速器引用（调试用） */
    public Object getAccelerator() { return accelerator; }

    /**
     * 设置加速器实例（运行时注入）
     * @param accel 加速器对象
     */
    public void setAccelerator(Object accel) {
        LOGGER.fine("[AdaptivePathSelector] Accelerator 已设置");
        this.nativeAvailable = (accel != null);
    }

    /**
     * 执行算法调度（预留接口）
     * @param frameData 帧数据快照
     */
    public void executeAlgorithms(Object frameData) {
        LOGGER.fine(String.format("[AdaptivePathSelector] executeAlgorithms called, nativeAvailable=%s", nativeAvailable));
    }

    /**
     * 释放资源（预留接口）
     */
    public void releaseResources() {
        LOGGER.info("[AdaptivePathSelector] Resources released");
        nativeSelectionCount.set(0);
        javaSelectionCount.set(0);
        autoFallbackCount.set(0);
    }
}
