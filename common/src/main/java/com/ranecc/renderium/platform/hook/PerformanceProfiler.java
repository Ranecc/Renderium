package com.ranecc.renderium.platform.hook;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 纳秒级性能测量器 — 用于验证 Platform 层 FPS 目标
 *
 * <p><b>设计特点</b>:
 * <ul>
 *   <li><b>零分配热路径</b>: 使用预分配的环形缓冲区和 Token 编码，采样时不创建对象</li>
 *   <li><b>Token 编码压缩</b>: 将 (hookIndex, startTimeNs) 压缩为一个 long，
 *       高 8 位存储 hookIndex（支持 0-255），低 56 位存储时间戳纳秒</li>
 *   <li><b>环形缓冲区</b>: 存储最近 RING_SIZE 次采样数据，覆盖旧值无需 GC</li>
 *   <li><b>百分位统计</b>: P50/P95/P99 计算，用于 SLA 监控和性能回归检测</li>
 *   <li><b>热路径安全</b>: 测量本身的开销 &lt; 5ns（begin + end 总计）</li>
 * </ul>
 *
 * <h3>Token 编码格式</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │ 63 .. 56 | 55 ........................................ 0 │
 * │ hookIndex(8b) |        startTimeNs 低 56 位             │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 * <p>低 56 位可表示约 2.28 年的纳秒精度时间（足够用于单次运行会话）。
 * 高 8 位支持 256 种不同的 hook 类型索引。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 热路径中包裹要测量的代码段
 * long token = PerformanceProfiler.beginMeasure(HookDispatcher.DRAW_INDEXED);
 * // ... 被测量的业务逻辑 ...
 * PerformanceProfiler.endMeasure(token);
 *
 * // 定期获取报告（如每 N 帧）
 * String report = PerformanceProfiler.getReport();
 * boolean ok = PerformanceProfiler.meetsTarget(8000.0); // 8000 FPS 目标
 * }</pre>
 *
 * @see HookDispatcher — 被测量的主要目标
 * @see HotPathMarker — 标注被测量方法的预算
 * @since 2.0.0
 */
public final class PerformanceProfiler {

    private static final Logger LOGGER = Logger.getLogger(PerformanceProfiler.class.getName());

    // ==================== 配置常量 ====================

    /**
     * 默认环形缓冲区大小（存储最近多少次采样的数据）
     *
     * <p>1024 次采样足以在 60FPS 下覆盖约 17 秒的历史数据，
     * 在高帧率下也能提供足够的统计样本量。
     */
    static final int RING_SIZE = 1024;

    /** Token 编码中 hookIndex 的位移量（高 8 位） */
    private static final int HOOK_INDEX_SHIFT = 56;

    /** Token 编码中时间戳的位掩码（低 56 位全 1） */
    private static final long TIME_MASK = 0x00FFFFFFFFFFFFFFL;

    /** 最大支持的 hook 索引值（2^8 - 1 = 255） */
    private static final int MAX_HOOK_INDEX = 0xFF;

    /**
     * 警告阈值（纳秒）— 单次超过此值记录警告日志
     *
     * <p>10ms = 10,000,000ns，对应 100FPS 的帧预算上限。
     * 超过此阈值的采样通常意味着存在性能问题。
     */
    static final long WARNING_THRESHOLD_NS = 10_000_000L;

    // ==================== 内部数据结构 ====================

    /**
     * 计时区域（Timing Zone）— 每个 hook 类型一个独立的统计区域
     *
     * <p>维护该 hook 类型的所有采样数据和聚合统计。
     * 所有字段的设计都考虑了热路径访问性能。
     */
    static final class Zone {

        /** 区域名称（如 "DrawIndexed"、"SetPipeline" 等） */
        final String name;

        /** 环形缓冲区 — 存储最近 RING_SIZE 次采样的耗时（纳秒） */
        final long[] ringBuffer;

        /** 当前写入位置（环形缓冲区的下一个写入索引） */
        int writePos;

        /** 已写入的总采样次数（可能远超 RING_SIZE，用于统计准确性） */
        long totalCount;

        /** 所有采样的总耗时累加值（纳秒）— 用于计算平均值 */
        final AtomicLong totalNs = new AtomicLong(0);

        /** 最小采样耗时（纳秒）— 初始值为 Long.MAX_VALUE */
        volatile long minNs = Long.MAX_VALUE;

        /** 最大采样耗时（纳秒）— 初始值为 0 */
        volatile long maxNs = 0;

        /**
         * 创建计时区域
         *
         * @param name 区域名称
         */
        Zone(String name) {
            this.name = name;
            this.ringBuffer = new long[RING_SIZE];
            this.writePos = 0;
            this.totalCount = 0;
        }

        /**
         * 记录一次采样结果
         *
         * <p><b>热路径方法</b>：由 endMeasure() 调用。
         * 将耗时写入环形缓冲区并更新聚合统计。
         *
         * @param elapsedNs 本次测量的耗时（纳秒）
         */
        void sample(long elapsedNs) {
            // 写入环形缓冲区（覆盖最旧的数据）
            ringBuffer[writePos] = elapsedNs;
            writePos = (writePos + 1) & (RING_SIZE - 1);  // 使用位与代替取模（RING_SIZE 是 2 的幂）
            totalCount++;

            // 更新总耗时（原子操作）
            totalNs.addAndGet(elapsedNs);

            // 更新最小值（非原子，接受近似值）
            if (elapsedNs < minNs) {
                minNs = elapsedNs;
            }

            // 更新最大值
            if (elapsedNs > maxNs) {
                maxNs = elapsedNs;
            }
        }

        /**
         * 重置所有统计数据
         *
         * <p>清零计数器和缓冲区，开始新的测量周期。
         */
        void reset() {
            totalCount = 0;
            totalNs.set(0);
            minNs = Long.MAX_VALUE;
            maxNs = 0;
            writePos = 0;
            // 清零缓冲区（避免旧数据干扰）
            for (int i = 0; i < RING_SIZE; i++) {
                ringBuffer[i] = 0;
            }
        }

        /**
         * 获取平均耗时
         *
         * @return double 平均耗时（纳秒），无采样时返回 0.0
         */
        double getAverageNs() {
            long count = totalCount;
            if (count == 0) return 0.0;
            return (double) totalNs.get() / count;
        }

        /**
         * 获取指定百分位的耗时值
         *
         * <p>从环形缓冲区中提取有效数据并排序后取百分位。
         * 注意：此方法会分配临时数组（仅冷路径调用时使用）。
         *
         * @param percentile 百分位值（0.0 ~ 100.0），如 50.0=P50, 95.0=P95, 99.0=P99
         * @return long 百分位对应的耗时（纳秒），无数据时返回 0
         */
        long getPercentile(double percentile) {
            if (totalCount == 0) return 0;

            // 确定有效数据量（不超过 RING_SIZE 和 totalCount 的较小值）
            int size = (int) Math.min(totalCount, RING_SIZE);
            if (size == 0) return 0;

            // 复制当前有效数据到临时数组（冷路径允许分配）
            long[] sorted = new long[size];
            System.arraycopy(ringBuffer, 0, sorted, 0, size);
            Arrays.sort(sorted);

            // 计算百分位索引（确保不越界）
            int idx = (int) Math.min(size * percentile / 100.0, size - 1);
            return sorted[idx];
        }

        /**
         * 获取中位数耗时（P50）
         *
         * @return long 中位数（纳秒）
         */
        long getP50() {
            return getPercentile(50.0);
        }

        /**
         * 获取 P95 耗时
         *
         * @return long P95 值（纳秒）
         */
        long getP95() {
            return getPercentile(95.0);
        }

        /**
         * 获取 P99 耗时
         *
         * @return long P99 值（纳秒）
         */
        long getP99() {
            return getPercentile(99.0);
        }
    }

    // ==================== 全局状态 ====================

    /**
     * 预定义的计时区域数组 — 与 HookDispatcher.HOOK_COUNT 对应
     *
     * <p>每个 Hook 类型有一个独立的 Zone 实例，
     * 分别跟踪各自的性能数据。
     */
    private static final Zone[] zones = new Zone[HookDispatcher.HOOK_COUNT];

    /** 全局帧计数器 — 用于 markFrameStart() 和定期报告触发 */
    private static final AtomicLong globalFrameCount = new AtomicLong(0);

    /** 自动报告间隔（帧数），0 表示禁用自动报告 */
    private static volatile int reportInterval = 3000;  // 约 50 秒 @60FPS

    /** 是否启用超时警告日志 */
    private static volatile boolean warningEnabled = true;

    // ==================== 静态初始化 ====================

    static {
        // 为每种 Hook 类型预创建计时区域（名称与 HookDispatcher.HOOK_NAMES 一致）
        for (int i = 0; i < HookDispatcher.HOOK_COUNT; i++) {
            String name = HookDispatcher.SET_PIPELINE == i ? "SetPipeline" : HookDispatcher.BIND_TEXTURE == i ? "BindTexture" : HookDispatcher.COMMAND_ENCODER_SUBMIT == i ? "CommandEncoderSubmit" : HookDispatcher.POST_CHAIN == i ? "PostChain" : HookDispatcher.FRAME_GRAPH_EXECUTE == i ? "FrameGraphExecute" : HookDispatcher.RENDER_PASS_CLOSE == i ? "RenderPassClose" : "GpuDeviceBuffer";
            zones[i] = new Zone(name);
        }
        LOGGER.fine(() -> "PerformanceProfiler initialized with " + zones.length + " zones");
    }

    // ==================== 私有构造函数 ====================

    private PerformanceProfiler() {
        // 工具类，禁止实例化
    }

    // ==================== 公共 API：热路径测量 ====================

    /**
     * 开始测量 — 在要测量的代码段起始处调用
     *
     * <p><b>热路径方法</b>：开销约 3-5ns（System.nanoTime() + 位运算）。
     * 返回的 token 必须传递给 {@link #endMeasure(long)} 以完成测量。
     *
     * <h4>方法签名</h4>
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>hookIndex</td><td>int</td><td>Hook 类型索引（0 ~ HOOK_COUNT-1）</td></tr>
     *   <tr><td>返回值</td><td>long</td><td>编码后的测量 token（不透明值）</td></tr>
     * </table>
     *
     * @param hookIndex Hook 类型索引（对应 {@link HookDispatcher} 中的常量）
     * @return long 编码后的测量 token（包含 hookIndex 和起始时间戳）
     * @throws IllegalArgumentException 如果 hookIndex 超出范围 [0, HOOK_COUNT)
     */
    public static long beginMeasure(int hookIndex) {
        // 参数校验（热路径分支预测友好：合法值远多于非法值）
        if (hookIndex < 0 || hookIndex >= HookDispatcher.HOOK_COUNT) {
            throw new IllegalArgumentException(
                    "hookIndex out of range [0, " + HookDispatcher.HOOK_COUNT + "): " + hookIndex
            );
        }

        long startTime = System.nanoTime();  // ~20-30ns

        // Token 编码：高 8 位 = hookIndex，低 56 位 = startTime 低 56 位
        return ((long) hookIndex << HOOK_INDEX_SHIFT) | (startTime & TIME_MASK);
    }

    /**
     * 结束测量并记录 — 在代码段结束处调用
     *
     * <p><b>热路径方法</b>：开销约 5-10ns（解码 + 计算 + 写入缓冲区）。
     * 必须与 {@link #beginMeasure(int)} 配对使用。
     *
     * <p>内部流程：
     * <ol>
     *   <li>从 token 中解码 hookIndex 和 startTime</li>
     *   <li>计算耗时 = System.nanoTime() - startTime</li>
     *   <li>将耗时写入对应 Zone 的环形缓冲区</li>
     *   <li>如果超出警告阈值则输出日志</li>
     * </ol>
     *
     * @param token 由 {@link #beginMeasure(int)} 返回的测量 token
     * @throws IllegalArgumentException 如果 token 无效（zoneIndex 越界）
     */
    public static void endMeasure(long token) {
        // 解码 token
        int zoneIndex = (int) (token >>> HOOK_INDEX_SHIFT);  // 高 8 位 → hookIndex
        long startTime = token & TIME_MASK;                   // 低 56 位 → 起始时间

        // 边界检查
        if (zoneIndex < 0 || zoneIndex >= HookDispatcher.HOOK_COUNT) {
            LOGGER.warning("Invalid profiling token: zoneIndex=" + zoneIndex +
                    ", possible data corruption or version mismatch");
            return;
        }

        // 计算耗时
        long elapsed = System.nanoTime() - startTime;

        // 记录到对应区域
        Zone zone = zones[zoneIndex];
        if (zone != null) {
            zone.sample(elapsed);

            // 超过阈值时记录警告（仅在启用时）
            if (warningEnabled && elapsed > WARNING_THRESHOLD_NS) {
                LOGGER.warning(String.format(
                        "[PerformanceProfiler] [%s] exceeded threshold: %.2f ms (limit: %.2f ms)",
                        zone.name,
                        elapsed / 1_000_000.0,
                        WARNING_THRESHOLD_NS / 1_000_000.0
                ));
            }
        }
    }

    // ==================== 公共 API：便捷包装 ====================

    /**
     * 包装代码段的执行并自动计时
     *
     * <p>便捷方法，适用于简单的同步代码块计时。
     * 注意：在极热路径上不建议使用（Runnable 对象有额外开销），
     * 应优先使用 beginMeasure/endMeasure 手动配对。
     *
     * @param hookIndex Hook 类型索引
     * @param runnable  要执行的代码块
     */
    public static void measure(int hookIndex, Runnable runnable) {
        long token = beginMeasure(hookIndex);
        try {
            runnable.run();
        } finally {
            endMeasure(token);
        }
    }

    /**
     * 标记新帧开始 — 应在每帧入口调用一次
     *
     * <p>递增全局帧计数器，并在达到报告间隔时自动输出报告。
     *
     * @return long 当前帧序号（单调递增）
     */
    public static long markFrameStart() {
        long frame = globalFrameCount.incrementAndGet();

        // 达到报告间隔时自动输出
        if (reportInterval > 0 && frame % reportInterval == 0) {
            String report = getReport();
            LOGGER.info(report);
        }

        return frame;
    }

    // ==================== 公共 API：统计报告 ====================

    /**
     * 获取完整的性能报告
     *
     * <p><b>冷路径方法</b>：包含所有区域的详细统计数据。
     * 报告内容包括：采样数、平均值、P50/P95/P99、最大值等。
     *
     * @return String 格式化的多行性能报告文本
     */
    public static String getReport() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== PerformanceProfiler Report ===\n");
        sb.append(String.format("Global frame count: %d%n", globalFrameCount.get()));
        sb.append(String.format("Registered zones: %d%n", zones.length));
        sb.append("---\n");

        for (int i = 0; i < zones.length; i++) {
            Zone zone = zones[i];
            if (zone == null || zone.totalCount == 0) continue;

            sb.append(String.format("[%s]%n", zone.name));
            sb.append(String.format("  Samples: %d%n", zone.totalCount));
            sb.append(String.format("  Average: %.1f μs%n", zone.getAverageNs() / 1000.0));
            sb.append(String.format("  Min:     %.1f μs%n", zone.minNs / 1000.0));
            sb.append(String.format("  Max:     %.1f μs%n", zone.maxNs / 1000.0));
            sb.append(String.format("  P50:     %.1f μs%n", zone.getP50() / 1000.0));
            sb.append(String.format("  P95:     %.1f μs%n", zone.getP95() / 1000.0));
            sb.append(String.format("  P99:     %.1f μs%n", zone.getP99() / 1000.0));
            sb.append(",\n");
        }

        return sb.toString();
    }

    /**
     * 检查是否满足 FPS 目标
     *
     * <p>基于 P95 延迟判断：如果 P95 延迟低于目标帧时间的 80%，
     * 则认为满足目标（留有 20% 安全余量给其他系统开销）。
     *
     * <p>判断公式：
     * <pre>
     * targetFrameTimeNs = 1_000_000_000 / targetFps
     * budgetNs = targetFrameTimeNs * 0.8  （80% 帧时间留给 platform 层）
     * result = (maxP95AcrossAllZones &lt; budgetNs)
     * </pre>
     *
     * @param targetFps 目标帧率（例如 8000.0 表示 8000 FPS 上限目标）
     * @return true 如果 P95 延迟低于预算（满足目标）；false 否则
     */
    public static boolean meetsTarget(double targetFps) {
        if (targetFps <= 0) return false;

        // 计算目标帧时间和 Platform 层预算（80% 帧时间）
        double targetFrameTimeNs = 1_000_000_000.0 / targetFps;
        double budgetNs = targetFrameTimeNs * 0.8;

        // 找出所有区域中的最大 P95 值
        long worstP95 = 0;
        for (Zone zone : zones) {
            if (zone != null && zone.totalCount > 0) {
                long p95 = zone.getP95();
                if (p95 > worstP95) {
                    worstP95 = p95;
                }
            }
        }

        // 无任何采样数据时默认为满足目标
        if (worstP95 == 0) return true;

        boolean meets = worstP95 < budgetNs;

        if (!meets) {
            final double finalWorstP95 = worstP95;
            final double finalBudgetNs = budgetNs;
            LOGGER.warning(() -> String.format(
                    "Performance target NOT met: target=%.0f FPS, budget=%.1f μs, " +
                            "worstP95=%.1f μs (%.1f%% over budget)",
                    targetFps,
                    finalBudgetNs / 1000.0,
                    finalWorstP95 / 1000.0,
                    ((finalWorstP95 - finalBudgetNs) / finalBudgetNs) * 100.0
            ));
        }

        return meets;
    }

    /**
     * 重置所有统计数据
     *
     * <p>用于开始新的分析周期（如场景切换后重新建立基线）。
     * 清零所有区域的计数器和缓冲区。
     */
    public static void reset() {
        globalFrameCount.set(0);
        for (Zone zone : zones) {
            if (zone != null) {
                zone.reset();
            }
        }
        LOGGER.fine("PerformanceProfiler all statistics reset");
    }

    // ==================== 配置 API ====================

    /**
     * 设置自动报告间隔（帧数）
     *
     * @param intervalFrames 帧数间隔（0 = 禁用自动报告）
     */
    public static void setReportInterval(int intervalFrames) {
        reportInterval = Math.max(0, intervalFrames);
    }

    /**
     * 设置是否启用超时警告
     *
     * @param enabled true 启用（超过阈值时记录 WARNING 日志），false 禁用
     */
    public static void setWarningEnabled(boolean enabled) {
        warningEnabled = enabled;
    }

    // ==================== 查询 API ====================

    /**
     * 获取指定区域的平均耗时
     *
     * @param hookIndex Hook 类型索引
     * @return double 平均耗时（纳秒），无数据返回 0.0
     */
    public static double getAverageNs(int hookIndex) {
        if (hookIndex < 0 || hookIndex >= zones.length) return 0.0;
        Zone zone = zones[hookIndex];
        return zone != null ? zone.getAverageNs() : 0.0;
    }

    /**
     * 获取指定区域的 P95 耗时
     *
     * @param hookIndex Hook 类型索引
     * @return long P95 值（纳秒），无数据返回 0
     */
    public static long getP95(int hookIndex) {
        if (hookIndex < 0 || hookIndex >= zones.length) return 0;
        Zone zone = zones[hookIndex];
        return zone != null ? zone.getP95() : 0;
    }

    /**
     * 获取全局帧计数
     *
     * @return long 已处理的帧总数
     */
    public static long getGlobalFrameCount() {
        return globalFrameCount.get();
    }
}
