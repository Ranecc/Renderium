package com.ranecc.renderium.application.orchestrator;

import com.ranecc.renderium.application.usecase.ProcessFrameUseCase;
import com.ranecc.renderium.domain.service.scheduling.AdaptivePathSelector;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 帧处理器（Application Layer - Orchestrator）
 *
 * <p>负责单帧的处理逻辑封装，作为 LifecycleOrchestrator 和 ProcessFrameUseCase 之间的桥梁。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>processFrame(dt)</b>：调用 ProcessFrameUseCase 执行帧处理</li>
 *   <li><b>性能统计</b>：跟踪帧率和处理耗时</li>
 *   <li><b>资源管理</b>：管理帧处理器的生命周期</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li><b>轻量级</b>：仅作为薄封装层，不包含业务逻辑</li>
 *   <li><b>可观测性</b>：提供详细的性能统计接口</li>
     *   <li><b>线程安全</b>：使用原子变量进行统计计数</li>
 * </ul>
 *
 * @see ProcessFrameUseCase
 * @see LifecycleOrchestrator
 * @since 1.1.0
 */
public class FrameProcessor {

    private static final Logger LOGGER = Logger.getLogger(FrameProcessor.class.getName());

    /** 帧处理用例 */
    private ProcessFrameUseCase processFrameUseCase;

    /** 自适应路径选择器（由外部注入） */
    private AdaptivePathSelector scheduler;

    /** 总处理帧数（原子计数） */
    private final AtomicLong totalFramesProcessed = new AtomicLong(0);

    /** 总处理时间（纳秒，原子累加） */
    private final AtomicLong totalProcessingTimeNs = new AtomicLong(0);

    /** 最大帧处理时间（纳秒） */
    private volatile long maxFrameTimeNs = 0;

    /** 最小帧处理时间（纳秒） */
    private volatile long minFrameTimeNs = Long.MAX_VALUE;

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    /**
     * 默认构造函数
     */
    public FrameProcessor() {
        this.processFrameUseCase = new ProcessFrameUseCase();
    }

    /**
     * 设置调度器（由 LifecycleOrchestrator 在 init() 时注入）
     *
     * @param scheduler AdaptivePathSelector 实例
     */
    public void setScheduler(AdaptivePathSelector scheduler) {
        this.scheduler = scheduler;
        if (this.processFrameUseCase != null) {
            this.processFrameUseCase.setScheduler(scheduler);
        }
    }

    /**
     * 处理单帧
     *
     * <p>委托给 {@link ProcessFrameUseCase#execute(float)} 执行实际逻辑，
     * 并收集性能统计数据。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>dt - 帧间隔时间（秒）（float 类型）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>IllegalStateException - 如果已关闭</li>
     * </ul>
     *
     * @param dt 帧间隔时间（单位：秒）
     * @throws IllegalStateException 如果帧处理器已关闭
     */
    public void processFrame(float dt) {
        if (shutdown) {
            throw new IllegalStateException("FrameProcessor is already shut down");
        }

        long startTime = System.nanoTime();

        try {
            processFrameUseCase.execute(dt);

            long elapsed = System.nanoTime() - startTime;

            updateStatistics(elapsed);

        } catch (Exception e) {
            long elapsed = System.nanoTime() - startTime;
            updateStatistics(elapsed);

            LOGGER.severe("Unhandled exception in frame processing: " + e.getMessage());
            throw new RuntimeException("Frame processing failed", e);
        }
    }

    /**
     * 关闭帧处理器
     *
     * <p>释放资源并输出最终性能统计。
     */
    public void shutdown() {
        if (shutdown) {
            return;
        }

        shutdown = true;

        logFinalStatistics();

        LOGGER.info("FrameProcessor shut down. Total frames processed: "
                   + totalFramesProcessed.get());
    }

    /**
     * 获取总处理帧数
     *
     * @return long 总帧数
     */
    public long getTotalFramesProcessed() {
        return totalFramesProcessed.get();
    }

    /**
     * 获取平均帧处理时间（毫秒）
     *
     * @return double 平均耗时（毫秒），如果没有处理过帧返回 0.0
     */
    public double getAverageFrameTimeMs() {
        long frames = totalFramesProcessed.get();
        if (frames == 0) {
            return 0.0;
        }
        long totalTime = totalProcessingTimeNs.get();
        return (totalTime / 1_000_000.0) / frames;
    }

    /**
     * 获取最大帧处理时间（毫秒）
     *
     * @return double 最大耗时（毫秒）
     */
    public double getMaxFrameTimeMs() {
        return maxFrameTimeNs / 1_000_000.0;
    }

    /**
     * 获取最小帧处理时间（毫秒）
     *
     * @return double 最小耗时（毫秒）
     */
    public double getMinFrameTimeMs() {
        if (minFrameTimeNs == Long.MAX_VALUE) {
            return 0.0;
        }
        return minFrameTimeNs / 1_000_000.0;
    }

    /**
     * 检查是否已关闭
     *
     * @return true 如果已关闭
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * 重置性能统计
     *
     * <p>用于开始新的统计周期。
     */
    public void resetStatistics() {
        totalFramesProcessed.set(0);
        totalProcessingTimeNs.set(0);
        maxFrameTimeNs = 0;
        minFrameTimeNs = Long.MAX_VALUE;
        LOGGER.info("FrameProcessor statistics reset");
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 更新性能统计数据
     *
     * @param frameTimeNs 本帧处理耗时（纳秒）
     */
    private void updateStatistics(long frameTimeNs) {
        totalFramesProcessed.incrementAndGet();
        totalProcessingTimeNs.addAndGet(frameTimeNs);

        // 更新最大值（CAS 循环保证线程安全）
        long currentMax;
        do {
            currentMax = maxFrameTimeNs;
            if (frameTimeNs <= currentMax) {
                break;
            }
        } while (!compareAndSetMax(frameTimeNs, currentMax));

        // 更新最小值（CAS 循环保证线程安全）
        long currentMin;
        do {
            currentMin = minFrameTimeNs;
            if (frameTimeNs >= currentMin) {
                break;
            }
        } while (!compareAndSetMin(frameTimeNs, currentMin));
    }

    /**
     * CAS 更新最大值
     */
    private boolean compareAndSetMax(long newValue, long expected) {
        synchronized (this) {
            if (maxFrameTimeNs == expected) {
                maxFrameTimeNs = newValue;
                return true;
            }
            return false;
        }
    }

    /**
     * CAS 更新最小值
     */
    private boolean compareAndSetMin(long newValue, long expected) {
        synchronized (this) {
            if (minFrameTimeNs == expected) {
                minFrameTimeNs = newValue;
                return true;
            }
            return false;
        }
    }

    /**
     * 输出最终性能统计日志
     */
    private void logFinalStatistics() {
        long frames = totalFramesProcessed.get();
        if (frames == 0) {
            LOGGER.info("No frames processed during this session");
            return;
        }

        long totalTimeMs = totalProcessingTimeNs.get() / 1_000_000L;
        double avgMs = getAverageFrameTimeMs();
        double maxMs = getMaxFrameTimeMs();
        double minMs = getMinFrameTimeMs();

        LOGGER.info(String.format(
            "=== FrameProcessor Final Statistics ===%n" +
            "Total frames processed: %d%n" +
            "Total processing time: %d ms%n" +
            "Average frame time: %.3f ms%n" +
            "Max frame time: %.3f ms%n" +
            "Min frame time: %.3f ms",
            frames, totalTimeMs, avgMs, maxMs, minMs
        ));
    }
}
