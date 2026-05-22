package com.ranecc.renderium.infrastructure.gpu;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 可开关的渲染管线性能分析器。
 *
 * <h3>使用方式</h3>
 * 启用（通过系统属性或环境变量）：
 * <pre>
 *   -Drenderium.profile=true
 * </pre>
 * 也支持运行时动态切换：
 * <pre>
 *   RenderiumProfiler.setEnabled(true);
 * </pre>
 *
 * <h3>节点集成</h3>
 * <pre>
 *   // execute() 方法开头
 *   RenderiumProfiler.recordStart(NODE_ID);
 *   // ... GPU dispatch ...
 *   RenderiumProfiler.recordEnd(NODE_ID);
 * </pre>
 *
 * <h3>性能特征</h3>
 * 禁用时每个 recordStart/recordEnd 开销 ~2ns（单次 volatile 读 + branch）。
 * 启用时单次 ~30ns（System.nanoTime 调用）。
 */
public final class RenderiumProfiler {

    static final int MAX_NODES = 64;

    private static volatile boolean enabled;
    static {
        enabled = Boolean.parseBoolean(System.getProperty("renderium.profile", "false"));
    }

    private static final long[] nodeStartTimes = new long[MAX_NODES];
    private static final long[] nodeTimes = new long[MAX_NODES];
    private static final int[] nodeFrameCounts = new int[MAX_NODES];
    private static volatile long frameStartNanos;
    private static volatile long frameEndNanos;
    private static volatile long lastFrameElapsed;

    private RenderiumProfiler() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }

    public static void beginFrame() {
        if (!enabled) return;
        frameStartNanos = System.nanoTime();
        for (int i = 0; i < MAX_NODES; i++) {
            nodeTimes[i] = 0L;
            nodeFrameCounts[i] = 0;
        }
    }

    public static void endFrame() {
        if (!enabled) return;
        frameEndNanos = System.nanoTime();
        lastFrameElapsed = frameEndNanos - frameStartNanos;
    }

    public static void recordStart(int nodeId) {
        if (!enabled || nodeId < 0 || nodeId >= MAX_NODES) return;
        nodeStartTimes[nodeId] = System.nanoTime();
    }

    public static void recordEnd(int nodeId) {
        if (!enabled || nodeId < 0 || nodeId >= MAX_NODES) return;
        nodeTimes[nodeId] += System.nanoTime() - nodeStartTimes[nodeId];
        nodeFrameCounts[nodeId]++;
    }

    public static long getNodeTime(int nodeId) {
        if (nodeId < 0 || nodeId >= MAX_NODES) return 0L;
        return nodeTimes[nodeId];
    }

    public static long getFrameTime() { return lastFrameElapsed; }

    public static String getReport() {
        if (!enabled) return "RenderiumProfiler: disabled";
        StringBuilder sb = new StringBuilder();
        sb.append("RenderiumProfiler report:\n");
        sb.append(String.format("  Total frame time: %.3f ms\n", lastFrameElapsed / 1_000_000.0));
        for (int i = 0; i < MAX_NODES; i++) {
            if (nodeTimes[i] > 0L) {
                double avgUs = nodeTimes[i] / (double) nodeFrameCounts[i] / 1000.0;
                sb.append(String.format("  Node #%d: %.1f μs (avg, %d frames)\n", i, avgUs, nodeFrameCounts[i]));
            }
        }
        return sb.toString();
    }
}
