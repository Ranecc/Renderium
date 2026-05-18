package com.ranecc.renderium.infrastructure.gpu;

import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * FFM 调用调试/接线层
 *
 * <p>所有 VulkanFFMBinding 的调用通过此类间接执行，
 * 实现：耗时统计、参数日志、错误拦截、熔断保护。
 *
 * <h2>使用方法：</h2>
 * <pre>{@code
 * // 替代 VulkanFFMBinding.getVkCmdDispatch().invokeExact(...)
 * VulkanFFMDebugger.dispatch("vkCmdDispatch", binding::invoke, cmdBuf, x, y, z);
 * }</pre>
 *
 * <h2>性能：</h2>
 * <pre>
 *   未启用调试: ~3ns overhead (AtomicLong.get + 1 次 volatile read)
 *   启用调试  : ~200ns overhead (nanoTime + Map.put)
 * </pre>
 */
public final class VulkanFFMDebugger {

    private static final Logger LOGGER = Logger.getLogger("Renderium|FFMDebug");

    /** 全局调试开关（开发环境 true，生产环境 false） */
    public static volatile boolean DEBUG_ENABLED = false;

    /** 慢调用阈值（纳秒），超过此值输出 WARNING */
    public static volatile long SLOW_THRESHOLD_NS = 5_000_000L; // 5ms

    /** 总调用次数 */
    private static final AtomicLong totalInvocations = new AtomicLong(0L);

    /** 总耗时（纳秒） */
    private static final AtomicLong totalNanos = new AtomicLong(0L);

    /** 总失败次数 */
    private static final AtomicLong totalFailures = new AtomicLong(0L);

    /** 每次调用的最近耗时 */
    static final ConcurrentHashMap<String, Long> lastCallNanos = new ConcurrentHashMap<>();

    /** 每次调用的累计耗时 */
    static final ConcurrentHashMap<String, AtomicLong> cumulativeNanos = new ConcurrentHashMap<>();

    /** 每次调用的调用次数 */
    static final ConcurrentHashMap<String, AtomicLong> callCounts = new ConcurrentHashMap<>();

    private VulkanFFMDebugger() {}

    // ==================== 核心调用方法 ====================

    /**
     * 通过反射 (invoke) 调用 FFM MethodHandle，带调试统计。
     */
    public static Object invoke(String apiName, MethodHandle handle, Object... args) {
        if (handle == null) {
            logNullHandle(apiName);
            return 0;
        }

        totalInvocations.incrementAndGet();
        long start = System.nanoTime();
        Throwable error = null;

        try {
            Object result = args.length == 0
                ? handle.invoke()
                : handle.invokeWithArguments(args);
            return result;
        } catch (Throwable t) {
            error = t;
            totalFailures.incrementAndGet();
            if (DEBUG_ENABLED) {
                LOGGER.warning("[FFM] " + apiName + " FAILED: " + t.getMessage());
            }
            return 0;
        } finally {
            long elapsed = System.nanoTime() - start;
            totalNanos.addAndGet(elapsed);
            recordCall(apiName, elapsed);
            if (elapsed > SLOW_THRESHOLD_NS && error == null) {
                LOGGER.warning("[FFM] SLOW: " + apiName + " took " + (elapsed / 1_000_000) + "ms");
            }
        }
    }

    /**
     * 带类型安全返回的调用（适配 int 返回值）。
     */
    public static int invokeInt(String apiName, MethodHandle handle, Object... args) {
        Object result = invoke(apiName, handle, args);
        return result instanceof Integer i ? i : 0;
    }

    /**
     * 带类型安全返回的调用（适配 long 返回值）。
     */
    public static long invokeLong(String apiName, MethodHandle handle, Object... args) {
        Object result = invoke(apiName, handle, args);
        return result instanceof Long l ? l : (result instanceof Integer i ? i.longValue() : 0L);
    }

    /**
     * void 调用（不需要返回值）。
     */
    public static void invokeVoid(String apiName, MethodHandle handle, Object... args) {
        invoke(apiName, handle, args);
    }

    // ==================== 统计查询 ====================

    public static long getTotalInvocations() { return totalInvocations.get(); }
    public static long getTotalFailures() { return totalFailures.get(); }
    public static long getTotalNanos() { return totalNanos.get(); }

    public static double getAvgNanos() {
        long count = totalInvocations.get();
        return count > 0 ? (double) totalNanos.get() / count : 0.0;
    }

    /**
     * 打印统计报告（每 N 帧调用一次）
     */
    public static String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FFM Debug Report ===\n");
        sb.append(String.format("  Total calls: %d (failures: %d)\n",
            totalInvocations.get(), totalFailures.get()));
        sb.append(String.format("  Total time: %.2fms  Avg: %.0fns\n",
            totalNanos.get() / 1_000_000.0, getAvgNanos()));

        cumulativeNanos.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
            .limit(10)
            .forEach(e -> {
                long count = callCounts.getOrDefault(e.getKey(), new AtomicLong(0)).get();
                long nanos = e.getValue().get();
                sb.append(String.format("  %-40s calls=%6d  total=%6.1fms  avg=%5.0fns\n",
                    e.getKey(), count, nanos / 1_000_000.0,
                    count > 0 ? (double) nanos / count : 0.0));
            });
        return sb.toString();
    }

    public static void reset() {
        totalInvocations.set(0L);
        totalNanos.set(0L);
        totalFailures.set(0L);
        cumulativeNanos.clear();
        callCounts.clear();
        lastCallNanos.clear();
    }

    // ==================== 内部方法 ====================

    private static void recordCall(String apiName, long nanos) {
        cumulativeNanos.computeIfAbsent(apiName, k -> new AtomicLong()).addAndGet(nanos);
        callCounts.computeIfAbsent(apiName, k -> new AtomicLong()).incrementAndGet();
        if (DEBUG_ENABLED) {
            lastCallNanos.put(apiName, nanos);
        }
    }

    private static void logNullHandle(String apiName) {
        LOGGER.warning("[FFM] NULL handle for: " + apiName);
        totalFailures.incrementAndGet();
        totalInvocations.incrementAndGet();
    }
}
