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
 * <h3>输出</h3>
 * 启用后每 60 帧自动输出报告到 System.out。
 * 可通过 {@link #getReport()} 手动获取报告字符串。
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
    private static final String[] nodeNames = new String[MAX_NODES];

    private static volatile long frameStartNanos;
    private static volatile long frameEndNanos;
    private static volatile long lastFrameElapsed;
    private static volatile long frameCount = 0;

    /** 自动输出间隔帧数（0=禁用自动输出） */
    private static volatile int reportInterval = 60;

    private RenderiumProfiler() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }

    /** 设置自动报告间隔。每 N 帧输出一次。0 表示不输出。 */
    public static void setReportInterval(int frames) { reportInterval = frames; }

    /**
     * 为节点 ID 注册可读名称，报告中使用名称代替数字。
     */
    public static void registerNodeName(int nodeId, String name) {
        if (nodeId >= 0 && nodeId < MAX_NODES && name != null) {
            nodeNames[nodeId] = name;
        }
    }

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
        frameCount++;

        if (reportInterval > 0 && frameCount % reportInterval == 0) {
            System.out.println(getReport());
        }
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
        sb.append("\n===== RenderiumProfiler report (frame ").append(frameCount).append(") =====\n");
        sb.append(String.format("Total frame time: %.3f ms\n", lastFrameElapsed / 1_000_000.0));
        for (int i = 0; i < MAX_NODES; i++) {
            if (nodeTimes[i] > 0L) {
                double avgUs = nodeTimes[i] / (double) nodeFrameCounts[i] / 1000.0;
                String label = nodeNames[i] != null ? nodeNames[i] : ("#" + i);
                sb.append(String.format("  %s: %.1f μs (avg, %d frames)\n", label, avgUs, nodeFrameCounts[i]));
            }
        }
        sb.append("========================================\n");
        return sb.toString();
    }
}
