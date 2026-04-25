// Renderium - Mixin 基础设施
// 性能监控类 - 记录每个 Pass 的执行时间

package com.renderium.module.impl.blaze3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 性能监控器 ⏱️
 * <p>
 * 提供轻量级的 Pass 级别性能监控能力，用于追踪渲染管线各阶段的执行时间。
 * 采用线程本地存储（ThreadLocal）实现无锁的嵌套计时，支持多线程并发使用。
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │  1. Pass 计时                                            │
 * │     - beginPass(name) / endPass() 配对调用               │
 * │     - 支持嵌套 Pass（如 Frame → Render → Draw）          │
 * │     - 自动记录执行时间（纳秒精度）                         │
 * ├─────────────────────────────────────────────────────────┤
 * │  2. 统计聚合                                             │
 * │     - 每个 Pass 的总时间、平均时间、最大/最小时间          │
 * │     - 调用次数统计                                        │
 * │     - 帧级别和全局级别的统计数据                           │
 * ├─────────────────────────────────────────────────────────┤
 * │  3. 报告生成                                             │
 * │     - 格式化的性能报告输出                                 │
 * │     - 支持按时间排序的瓶颈分析                             │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 开始一个 Pass
 * RenderiumProfiler.beginPass("FrameGraph.Execute");
 *
 * try {
 *     // ... 执行帧图逻辑 ...
 *
 *     RenderiumProfiler.beginPass("FrameGraph.ResourceBarrier");
 *     // ... 资源屏障操作 ...
 *     RenderiumProfiler.endPass();
 *
 * } finally {
 *     RenderiumProfiler.endPass();
 * }
 * }</pre>
 *
 * <h3>线程安全设计：</h3>
 * <ul>
 *   <li>每个线程维护独立的 Pass 栈（ThreadLocal）</li>
 *   <li>统计数据使用 ConcurrentHashMap 和 AtomicLong</li>
 *   <li>启用/禁用状态使用 AtomicBoolean 保证可见性</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public final class RenderiumProfiler {

    private static final Logger LOGGER = Logger.getLogger(RenderiumProfiler.class.getName());

    // ==================== 配置常量 ====================

    /** 默认最大记录 Pass 数量 */
    public static final int MAX_RECORDED_PASSES = 256;

    /** 统计报告中的最大显示条目数 */
    public static final int MAX_REPORT_ENTRIES = 50;

    /** 默认是否启用（可通过 setEnabled 修改） */
    private static final boolean DEFAULT_ENABLED = true;

    // ==================== 内部数据结构 ====================

    /**
     * Pass 条目 - 表示一次 begin/end 配对的时间记录
     *
     * <p>Optimized: Uses ThreadLocal object pool to avoid allocation on hot path.
     * Pool size is bounded to prevent memory leaks.</p>
     */
    private static class PassEntry {
        /** Pass 名称 */
        String name;

        /** 开始时间（纳秒） */
        long startTimeNanos;

        /** 子 Pass 的累计时间（用于计算自身耗时） */
        long childTimeNanos;

        /** Thread-local pool for reusing PassEntry instances (avoids GC pressure) */
        private static final ThreadLocal<ArrayDeque<PassEntry>> entryPool =
            ThreadLocal.withInitial(() -> new ArrayDeque<>(16));

        /** Maximum pool size per thread (prevents unbounded growth) */
        private static final int MAX_POOL_SIZE = 32;

        /** Acquire a PassEntry from pool or create new one */
        static PassEntry acquire(String name, long startTimeNanos) {
            ArrayDeque<PassEntry> pool = entryPool.get();
            PassEntry entry = pool.poll();
            if (entry == null) {
                entry = new PassEntry();
            }
            entry.name = name;
            entry.startTimeNanos = startTimeNanos;
            entry.childTimeNanos = 0;
            return entry;
        }

        /** Release a PassEntry back to the pool for reuse */
        void release() {
            name = null; // Help GC
            ArrayDeque<PassEntry> pool = entryPool.get();
            if (pool.size() < MAX_POOL_SIZE) {
                pool.push(this);
            }
        }

        private PassEntry() {} // Private constructor, use acquire()
    }

    /**
     * Pass 统计数据 - 聚合某个 Pass 的历史执行信息
     */
    private static class PassStatistics {
        /** 总调用次数 */
        final AtomicLong callCount = new AtomicLong(0);

        /** 总执行时间（纳秒） */
        final AtomicLong totalTimeNanos = new AtomicLong(0);

        /** 最小执行时间（纳秒） */
        final AtomicLong minTimeNanos = new AtomicLong(Long.MAX_VALUE);

        /** 最大执行时间（纳秒） */
        final AtomicLong maxTimeNanos = new AtomicLong(0);

        /**
         * 更新统计数据
         *
         * @param elapsedNanos 本次执行时间（纳秒）
         */
        void update(long elapsedNanos) {
            callCount.incrementAndGet();
            totalTimeNanos.addAndGet(elapsedNanos);

            // CAS 循环更新最小值
            long currentMin;
            do {
                currentMin = minTimeNanos.get();
                if (elapsedNanos >= currentMin) break;
            } while (!minTimeNanos.compareAndSet(currentMin, elapsedNanos));

            // CAS 循环更新最大值
            long currentMax;
            do {
                currentMax = maxTimeNanos.get();
                if (elapsedNanos <= currentMax) break;
            } while (!maxTimeNanos.compareAndSet(currentMax, elapsedNanos));
        }

        /**
         * 重置所有统计字段
         */
        void reset() {
            callCount.set(0);
            totalTimeNanos.set(0);
            minTimeNanos.set(Long.MAX_VALUE);
            maxTimeNanos.set(0);
        }
    }

    // ==================== 全局状态 ====================

    /** 是否启用性能监控 */
    private static final AtomicBoolean enabled = new AtomicBoolean(DEFAULT_ENABLED);

    /** 全局 Pass 统计表 (passName → statistics) */
    private static final ConcurrentHashMap<String, PassStatistics> globalStatistics =
            new ConcurrentHashMap<>();

    /** 当前帧的 Pass 时间记录（用于帧级别报告） */
    private static final ConcurrentHashMap<String, Long> framePassTimes =
            new ConcurrentHashMap<>();

    /** 当前帧号 */
    private static volatile int currentFrameNumber = 0;

    // ==================== 线程本地状态 ====================

    /**
     * 每个线程的 Pass 栈
     * <p>支持嵌套 Pass 计时，每个线程独立。
     */
    private static final ThreadLocal<Deque<PassEntry>> passStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    // ==================== 私有构造函数 ====================

    private RenderiumProfiler() {
        throw new UnsupportedOperationException("RenderiumProfiler 是工具类，不允许实例化");
    }

    // ==================== 公共 API：Pass 计时 ====================

    /**
     * 开始一个性能监控 Pass
     * <p>
     * 记录当前时间并压入线程本地的 Pass 栈。必须与 {@link #endPass()} 配对调用。
     *
     * @param name Pass 名称（建议使用层次化命名，如 "FrameGraph.Render.Solid"）
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>name</b>: String 类型，Pass 的唯一标识名称</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值</li>
     * </ul>
     *
     * @throws IllegalStateException 如果 Pass 栈已超过最大深度（防止内存泄漏）
     */
    public static void beginPass(String name) {
        if (!enabled.get()) return;

        if (name == null || name.isBlank()) {
            LOGGER.warning("RenderiumProfiler.beginPass: Pass 名称不能为空");
            return;
        }

        Deque<PassEntry> stack = passStack.get();

        // 防止栈溢出（异常情况保护）
        if (stack.size() >= MAX_RECORDED_PASSES) {
            throw new IllegalStateException(
                    String.format("Pass 栈溢出: 当前深度 %d 已超过最大限制 %d",
                            stack.size(), MAX_RECORDED_PASSES)
            );
        }

        stack.push(PassEntry.acquire(name, System.nanoTime()));
    }

    /**
     * 结束当前性能监控 Pass
     * <p>
     * 弹出 Pass 栈顶条目，计算执行时间并更新统计数据。
     * 如果当前 Pass 有子 Pass，会自动扣除子 Pass 的执行时间。
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li>无参数</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>long - 本次 Pass 的自身执行时间（纳秒），不含子 Pass 时间；如果未启用的栈为空则返回 0</li>
     * </ul>
     *
     * @return 本次 Pass 的净执行时间（纳秒）
     * @throws IllegalStateException 如果 Pass 栈为空（begin/end 不匹配）
     */
    public static long endPass() {
        if (!enabled.get()) return 0;

        Deque<PassEntry> stack = passStack.get();

        if (stack.isEmpty()) {
            LOGGER.warning("RenderiumProfiler.endPass: Pass 栈为空，可能存在 begin/end 不匹配");
            return 0;
        }

        PassEntry entry = stack.pop();
        long endTime = System.nanoTime();
        long totalElapsed = endTime - entry.startTimeNanos;
        long selfElapsed = totalElapsed - entry.childTimeNanos;

        // Update parent pass child time accumulation
        if (!stack.isEmpty()) {
            stack.peek().childTimeNanos += totalElapsed;
        }

        // Record statistics
        recordPassTime(entry.name, selfElapsed);
        framePassTimes.put(entry.name, selfElapsed);

        // Release entry back to pool for reuse (avoid GC)
        entry.release();

        return selfElapsed;
    }

    /**
     * 执行带计时的代码块
     * <p>
     * 便捷方法，自动处理 begin/end 配对，支持 lambda 表达式。
     *
     * @param name   Pass 名称
     * @param action 要执行的代码块
     * @param <T>    返回值类型（可为 Void）
     * @return 代码块的返回值
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>name</b>: String - Pass 名称</li>
     *   <li><b>action</b>: ProfilerAction&lt;T&gt; - 要执行的操作接口</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>T - 泛型，由 action 决定具体类型</li>
     * </ul>
     *
     * @throws Exception 如果执行过程中抛出异常
     */
    public static <T> T profile(String name, ProfilerAction<T> action) throws Exception {
        beginPass(name);
        try {
            return action.execute();
        } finally {
            endPass();
        }
    }

    // ==================== 公共 API：状态查询 ====================

    /**
     * 检查性能监控是否已启用
     *
     * @return 如果已启用则返回 true
     */
    public static boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 设置性能监控启用状态
     *
     * @param value 是否启用
     */
    public static void setEnabled(boolean value) {
        enabled.set(value);
        LOGGER.info(String.format("RenderiumProfiler: %s", value ? "已启用" : "已禁用"));
    }

    /**
     * 获取当前线程的 Pass 栈深度
     *
     * @return 栈深度（0 表示没有活跃的 Pass）
     */
    public static int getStackDepth() {
        return passStack.get().size();
    }

    /**
     * 获取当前活跃的 Pass 名称（栈顶）
     *
     * @return 当前 Pass 名称，如果没有活跃 Pass 则返回 null
     */
    public static String getCurrentPassName() {
        Deque<PassEntry> stack = passStack.get();
        PassEntry top = stack.peek();
        return top != null ? top.name : null;
    }

    // ==================== 公共 API：统计查询 ====================

    /**
     * 获取指定 Pass 的调用次数
     *
     * @param passName Pass 名称
     * @return 调用次数，未找到则返回 0
     */
    public static long getCallCount(String passName) {
        PassStatistics stats = globalStatistics.get(passName);
        return stats != null ? stats.callCount.get() : 0;
    }

    /**
     * 获取指定 Pass 的平均执行时间（毫秒）
     *
     * @param passName Pass 名称
     * @return 平均执行时间（毫秒），未找到或无数据则返回 0.0
     */
    public static double getAverageTimeMs(String passName) {
        PassStatistics stats = globalStatistics.get(passName);
        if (stats == null || stats.callCount.get() == 0) {
            return 0.0;
        }
        return nanosToMillis(stats.totalTimeNanos.get()) / stats.callCount.get();
    }

    /**
     * 获取指定 Pass 的总执行时间（毫秒）
     *
     * @param passName Pass 名称
     * @return 总执行时间（毫秒）
     */
    public static double getTotalTimeMs(String passName) {
        PassStatistics stats = globalStatistics.get(passName);
        return stats != null ? nanosToMillis(stats.totalTimeNanos.get()) : 0.0;
    }

    /**
     * 获取指定 Pass 的最小/最大执行时间（毫秒）
     *
     * @param passName Pass 名称
     * @return double[2] 数组，[0]=最小时间，[1]=最大时间（毫秒）
     */
    public static double[] getMinMaxTimeMs(String passName) {
        PassStatistics stats = globalStatistics.get(passName);
        if (stats == null || stats.callCount.get() == 0) {
            return new double[]{0.0, 0.0};
        }
        long min = stats.minTimeNanos.get();
        return new double[]{
                nanosToMillis(min == Long.MAX_VALUE ? 0 : min),
                nanosToMillis(stats.maxTimeNanos.get())
        };
    }

    /**
     * 获取所有已记录的 Pass 名称
     *
     * @return Pass 名称数组
     */
    public static String[] getRecordedPassNames() {
        return globalStatistics.keySet().toArray(new String[0]);
    }

    // ==================== 公共 API：帧管理 ====================

    /**
     * 开始新的一帧
     * <p>
     * 清除上一帧的 Pass 时间记录，递增帧计数器。
     * 应在每帧开始时调用。
     */
    public static void beginFrame() {
        framePassTimes.clear();
        currentFrameNumber++;
    }

    /**
     * 获取当前帧号
     *
     * @return 当前帧号（从 1 开始）
     */
    public static int getCurrentFrameNumber() {
        return currentFrameNumber;
    }

    /**
     * 获取当前帧的所有 Pass 执行时间
     *
     * @return Map of (passName → elapsedNanos)
     */
    public static ConcurrentHashMap<String, Long> getFramePassTimes() {
        return framePassTimes;
    }

    // ==================== 公共 API：重置与报告 ====================

    /**
     * 重置所有统计数据
     * <p>
     * 清空全局统计和当前帧数据。
     */
    public static void reset() {
        for (PassStatistics stats : globalStatistics.values()) {
            stats.reset();
        }
        framePassTimes.clear();
        currentFrameNumber = 0;

        LOGGER.info("RenderiumProfiler: 所有统计数据已重置");
    }

    /**
     * 生成格式化的性能报告
     * <p>
     * 包含所有 Pass 的统计信息和排序后的瓶颈分析。
     *
     * @return 格式化的报告字符串
     */
    public static String formatReport() {
        StringBuilder report = new StringBuilder();

        report.append("╔══════════════════════════════════════════╗\n");
        report.append("║       Renderium Performance Report       ║\n");
        report.append("╚══════════════════════════════════════════╝\n\n");

        report.append(String.format("Status: %s | Frame: %d | Recorded Passes: %d\n\n",
                enabled.get() ? "ENABLED" : "DISABLED",
                currentFrameNumber,
                globalStatistics.size()));

        if (globalStatistics.isEmpty()) {
            report.append("(No data recorded yet)\n");
            return report.toString();
        }

        // 按总时间降序排列
        var sortedEntries = globalStatistics.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(
                        e2.getValue().totalTimeNanos.get(),
                        e1.getValue().totalTimeNanos.get()
                ))
                .limit(MAX_REPORT_ENTRIES)
                .toList();

        report.append("┌──────────────────────────────────────────────────────────────────────┐\n");
        report.append("│ Pass Name                     Calls    Total(ms)  Avg(ms)   Min/Max  │\n");
        report.append("├──────────────────────────────────────────────────────────────────────┤\n");

        for (var entry : sortedEntries) {
            PassStatistics stats = entry.getValue();
            double[] minMax = getMinMaxTimeMs(entry.getKey());

            report.append(String.format(
                    "│ %-28s %8d  %9.3f  %8.3f  %6.2f/%-6.2f │\n",
                    truncateString(entry.getKey(), 28),
                    stats.callCount.get(),
                    nanosToMillis(stats.totalTimeNanos.get()),
                    getAverageTimeMs(entry.getKey()),
                    minMax[0],
                    minMax[1]
            ));
        }

        report.append("└──────────────────────────────────────────────────────────────────────┘\n");

        return report.toString();
    }

    // ==================== 函数式接口 ====================

    /**
     * 性能分析动作接口
     * <p>用于 {@link #profile(String, ProfilerAction)} 方法。
     *
     * @param <T> 返回值类型
     */
    @FunctionalInterface
    public interface ProfilerAction<T> {
        /**
         * 执行需要被分析的动作
         *
         * @return 动作结果
         * @throws Exception 执行过程中的异常
         */
        T execute() throws Exception;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 记录 Pass 执行时间到统计表
     *
     * @param passName      Pass 名称
     * @param elapsedNanos  执行时间（纳秒）
     */
    private static void recordPassTime(String passName, long elapsedNanos) {
        if (elapsedNanos < 0) {
            LOGGER.warning(String.format("负数执行时间: %s = %d ns", passName, elapsedNanos));
            return;
        }

        globalStatistics.computeIfAbsent(passName, k -> new PassStatistics())
                .update(elapsedNanos);
    }

    /**
     * 将纳秒转换为毫秒
     *
     * @param nanos 纳秒值
     * @return 毫秒值
     */
    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * 截断字符串到指定长度
     *
     * @param str   原始字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private static String truncateString(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
}
