// Renderium - 性能诊断 Profiler
// 在 Mixin 热路径上注入 NanoTime 计时，精确定位瓶颈
//
// 启用方式（任选一种）:
//   方式 A: JVM 系统属性: -Drenderium.profiler=true
//   方式 B: 文件开关: 在 MC 运行目录创建 .renderium-profiler 文件
//   方式 C: 环境变量: RENDERIUM_PROFILER=true
//   方式 D: 代码强制: RenderiumProfiler.forceEnable()
//
// PowerShell 启用:
//   $env:RENDERIUM_PROFILER="true"
//   gradle :fabric:runClient --no-daemon

package com.renderium.fabric.profiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量级性能诊断器
 */
public final class RenderiumProfiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-Profiler");

    private static volatile boolean enabled;
    private static long reportInterval;

    static {
        enabled = detectEnabled();
        reportInterval = detectInterval();
        if (enabled) {
            LOGGER.info("[Renderium] Profiler ENABLED (interval={} frames)", reportInterval);
        }
    }

    private static boolean detectEnabled() {
        if (Boolean.getBoolean("renderium.profiler")) return true;

        String env = System.getenv().get("RENDERIUM_PROFILER");
        if (env != null && ("true".equalsIgnoreCase(env) || "1".equals(env))) return true;

        // 搜索多个可能的工作目录
        String[] searchPaths = {".", "run", "fabric/run", "../run"};
        for (String path : searchPaths) {
            if (new File(path, ".renderium-profiler").exists()) return true;
        }

        // 也检查 user.dir
        String userDir = System.getProperty("user.dir");
        if (userDir != null && new File(userDir, ".renderium-profiler").exists()) return true;

        return false;
    }

    private static long detectInterval() {
        String prop = System.getProperty("renderium.profiler.interval");
        if (prop != null) {
            try { return Long.parseLong(prop); } catch (NumberFormatException ignored) {}
        }
        String env = System.getenv().get("RENDERIUM_PROFILER_INTERVAL");
        if (env != null) {
            try { return Long.parseLong(env); } catch (NumberFormatException ignored) {}
        }
        return 10000L;
    }

    /** 强制启用（代码调用） */
    public static void forceEnable() {
        enabled = true;
        LOGGER.info("[Renderium] Profiler FORCE ENABLED");
    }

    /** 强制启用并设置间隔 */
    public static void forceEnable(long interval) {
        enabled = true;
        reportInterval = interval;
        LOGGER.info("[Renderium] Profiler FORCE ENABLED (interval={} frames)", interval);
    }

    public static boolean isEnabled() { return enabled; }

    private static final class TimingData {
        AtomicLong totalTimeNs = new AtomicLong(0);
        AtomicLong callCount = new AtomicLong(0);
        AtomicLong lastStartNs = new AtomicLong(0);
    }

    private static final Map<String, TimingData> TIMINGS = new ConcurrentHashMap<>();
    private static final AtomicLong FRAME_COUNT = new AtomicLong(0);
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private RenderiumProfiler() {}

    public static void begin(String label) {
        if (!enabled) return;
        TimingData data = TIMINGS.computeIfAbsent(label, k -> new TimingData());
        data.lastStartNs.set(System.nanoTime());
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void end(String label) {
        if (!enabled) return;

        int depth = DEPTH.get();
        DEPTH.set(depth - 1);

        TimingData data = TIMINGS.get(label);
        if (data == null) return;

        long elapsed = System.nanoTime() - data.lastStartNs.get();
        data.totalTimeNs.addAndGet(elapsed);
        data.callCount.incrementAndGet();

        long frame = FRAME_COUNT.incrementAndGet();
        if (frame % reportInterval == 0 && depth == 0) {
            report();
        }
    }

    public static void report() {
        if (!enabled || TIMINGS.isEmpty()) return;

        LOGGER.info("=== Renderium Profiler Report (frame={}) ===", FRAME_COUNT.get());
        TIMINGS.forEach((label, data) -> {
            long calls = data.callCount.get();
            long totalNs = data.totalTimeNs.get();
            double avgMs = calls > 0 ? (double) totalNs / calls / 1_000_000.0 : 0;
            double totalMs = (double) totalNs / 1_000_000.0;

            String level;
            if (avgMs > 1.0) level = "[SLOW]";
            else if (avgMs > 0.1) level = "[OK]";
            else level = "[FAST]";

            String avgStr = String.format("%.3f", avgMs);
            String totalStr = String.format("%.1f", totalMs);

            LOGGER.info("  {} {}: avg={}ms, calls={}, total={}ms",
                    level, label, avgStr, calls, totalStr);
        });
        LOGGER.info("==========================================");
    }

    public static void reset() {
        TIMINGS.clear();
        FRAME_COUNT.set(0);
    }

    public static long getFrameCount() { return FRAME_COUNT.get(); }
}
