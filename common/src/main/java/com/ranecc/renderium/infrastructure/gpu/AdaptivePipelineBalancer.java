package com.ranecc.renderium.infrastructure.gpu;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 自适应 CPU-GPU 管线负载均衡器。
 *
 * <h3>核心思想</h3>
 * CPU 和 GPU 是两条流水线，任何一方的空闲都是浪费。
 * 本均衡器通过实时监测 CPU/GPU 帧时间，动态调整工作分配，
 * 使两条流水线始终满载运行。
 *
 * <h3>反馈回路</h3>
 * <pre>
 *   测量帧时间 → 判定瓶颈 → 调整工作分配 → 下一帧生效
 *        ↑                                        |
 *        └────────────────────────────────────────┘
 * </pre>
 *
 * <h3>三种状态</h3>
 * <ul>
 *   <li><b>CPU_BOUND</b>: CPU 帧时间 > GPU 帧时间 → 向 GPU 卸载更多工作</li>
 *   <li><b>GPU_BOUND</b>: GPU 帧时间 > CPU 帧时间 → 向 CPU 卸载更多工作</li>
 *   <li><b>BALANCED</b>: 两者接近 → 维持当前分配</li>
 * </ul>
 *
 * <h3>调节手段</h3>
 * <table>
 *   <tr><th>状态</th><th>GPU 端调节</th><th>CPU 端调节</th></tr>
 *   <tr><td>CPU_BOUND</td><td>增加 Compute Shader 工作量（更细粒度剔除、更多后处理）</td><td>减少 CPU 侧剔除、跳过 CPU 预过滤</td></tr>
 *   <tr><td>GPU_BOUND</td><td>降低分辨率、减少后处理 Pass、简化着色器</td><td>增加 CPU 侧预剔除、CPU 端 LOD 选择</td></tr>
 *   <tr><td>BALANCED</td><td>维持</td><td>维持</td></tr>
 * </table>
 *
 * <h3>防抖设计</h3>
 * <ul>
 *   <li>指数移动平均（EMA）平滑帧时间，避免单帧抖动触发误调</li>
 *   <li>迟滞带（hysteresis）：只在差异超过 15% 时才切换状态</li>
 *   <li>渐进调整：每帧最多调整 10%，避免剧烈波动</li>
 *   <li>冷却期：状态切换后 30 帧内不再切换</li>
 * </ul>
 */
public final class AdaptivePipelineBalancer {

    private static final Logger LOGGER = Logger.getLogger("Renderium|Balancer");

    // ==================== 瓶颈状态 ====================
    public enum Bottleneck {
        CPU_BOUND,   // CPU 是瓶颈，GPU 有空闲
        GPU_BOUND,   // GPU 是瓶颈，CPU 有空闲
        BALANCED     // 两者接近平衡
    }

    // ==================== 配置常量 ====================
    /** EMA 平滑系数 (0~1)，越小越平滑 */
    private static final float EMA_ALPHA = 0.1f;
    /** 迟滞带阈值：CPU/GPU 时间差异超过此比例才切换状态 */
    private static final float HYSTERESIS_RATIO = 0.15f;
    /** 每帧最大调整比例 */
    private static final float MAX_ADJUSTMENT_PER_FRAME = 0.10f;
    /** 状态切换冷却帧数 */
    private static final int COOLDOWN_FRAMES = 30;
    /** 目标帧时间 (16.67ms = 60fps) */
    private static final float TARGET_FRAME_TIME_MS = 16.667f;

    // ==================== 帧时间测量 ====================
    /** CPU 帧时间 EMA (ms) */
    private static volatile float cpuFrameTimeEma = TARGET_FRAME_TIME_MS;
    /** GPU 帧时间 EMA (ms) */
    private static volatile float gpuFrameTimeEma = TARGET_FRAME_TIME_MS;
    /** 上一帧 CPU 开始时间戳 (ns) */
    private static final AtomicLong cpuFrameStartNs = new AtomicLong(0);
    /** 上一帧 CPU 结束时间戳 (ns) */
    private static final AtomicLong cpuFrameEndNs = new AtomicLong(0);

    // ==================== 状态机 ====================
    private static volatile Bottleneck currentBottleneck = Bottleneck.BALANCED;
    private static final AtomicInteger cooldownCounter = new AtomicInteger(0);

    // ==================== 自适应参数 ====================
    /** GPU 工作负载因子 (0.0~1.0)，1.0 = 全量 GPU 工作 */
    private static volatile float gpuWorkFactor = 1.0f;
    /** CPU 工作负载因子 (0.0~1.0)，1.0 = 全量 CPU 工作 */
    private static volatile float cpuWorkFactor = 1.0f;
    /** Async Compute 使用比例 (0.0~1.0) */
    private static volatile float asyncComputeRatio = 0.5f;
    /** 后处理质量缩放 (0.0~1.0) */
    private static volatile float postProcessScale = 1.0f;
    /** 剔除粒度：0=CPU全做, 1=GPU全做 */
    private static volatile float cullingGpuRatio = 0.8f;

    // ==================== 统计 ====================
    private static final AtomicLong totalFrames = new AtomicLong(0);
    private static final AtomicLong cpuBoundFrames = new AtomicLong(0);
    private static final AtomicLong gpuBoundFrames = new AtomicLong(0);
    private static final AtomicLong balancedFrames = new AtomicLong(0);

    private AdaptivePipelineBalancer() {}

    // ──────── 帧级 API ────────

    /** 帧开始：记录 CPU 时间戳 */
    public static void onFrameBegin() {
        cpuFrameStartNs.set(System.nanoTime());
    }

    /**
     * 帧结束：记录 CPU 时间戳 + 更新 GPU 时间 + 执行均衡逻辑。
     * @param gpuFrameTimeMs GPU 帧时间（从 timestamp query 获取），若不可用传 -1
     */
    public static void onFrameEnd(float gpuFrameTimeMs) {
        long endNs = System.nanoTime();
        cpuFrameEndNs.set(endNs);

        float cpuTimeMs = (endNs - cpuFrameStartNs.get()) / 1_000_000f;
        updateEma(cpuTimeMs, gpuFrameTimeMs);
        detectBottleneck();
        adjustWorkDistribution();

        totalFrames.incrementAndGet();
    }

    /** 简化版：无 GPU 时间数据时调用 */
    public static void onFrameEnd() {
        onFrameEnd(-1f);
    }

    // ──────── EMA 更新 ────────

    private static void updateEma(float cpuTimeMs, float gpuTimeMs) {
        cpuFrameTimeEma = ema(cpuFrameTimeEma, cpuTimeMs);

        if (gpuTimeMs > 0) {
            gpuFrameTimeEma = ema(gpuFrameTimeEma, gpuTimeMs);
        } else {
            // 无 GPU 时间数据时，用 CPU 时间估算（GPU 通常略慢于 CPU 提交速度）
            gpuFrameTimeEma = ema(gpuFrameTimeEma, cpuTimeMs * 0.9f);
        }
    }

    private static float ema(float prev, float current) {
        return prev + EMA_ALPHA * (current - prev);
    }

    // ──────── 瓶颈检测 ────────

    private static void detectBottleneck() {
        if (cooldownCounter.get() > 0) {
            cooldownCounter.decrementAndGet();
            return;
        }

        float cpu = cpuFrameTimeEma;
        float gpu = gpuFrameTimeEma;
        float diff = Math.abs(cpu - gpu);
        float avg = (cpu + gpu) / 2f;

        Bottleneck detected;
        if (diff / avg < HYSTERESIS_RATIO) {
            detected = Bottleneck.BALANCED;
        } else if (cpu > gpu) {
            detected = Bottleneck.CPU_BOUND;
        } else {
            detected = Bottleneck.GPU_BOUND;
        }

        if (detected != currentBottleneck) {
            Bottleneck prev = currentBottleneck;
            currentBottleneck = detected;
            cooldownCounter.set(COOLDOWN_FRAMES);
            LOGGER.info(String.format("瓶颈切换: %s → %s (CPU=%.2fms GPU=%.2fms)",
                prev, detected, cpu, gpu));
        }

        // 统计
        switch (currentBottleneck) {
            case CPU_BOUND -> cpuBoundFrames.incrementAndGet();
            case GPU_BOUND -> gpuBoundFrames.incrementAndGet();
            case BALANCED -> balancedFrames.incrementAndGet();
        }
    }

    // ──────── 工作分配调整 ────────

    private static void adjustWorkDistribution() {
        switch (currentBottleneck) {
            case CPU_BOUND -> adjustForCpuBound();
            case GPU_BOUND -> adjustForGpuBound();
            case BALANCED -> maintainBalance();
        }
    }

    /** CPU 是瓶颈 → 向 GPU 卸载 */
    private static void adjustForCpuBound() {
        // 增加 GPU 承担的剔除比例
        cullingGpuRatio = adjustToward(cullingGpuRatio, 1.0f);
        // 增加 Async Compute 使用
        asyncComputeRatio = adjustToward(asyncComputeRatio, 0.9f);
        // CPU 可以跳过一些预过滤
        cpuWorkFactor = adjustToward(cpuWorkFactor, 0.7f);
        // GPU 有余力，后处理保持高质量
        postProcessScale = adjustToward(postProcessScale, 1.0f);
        gpuWorkFactor = adjustToward(gpuWorkFactor, 1.0f);
    }

    /** GPU 是瓶颈 → 向 CPU 卸载 + 降低 GPU 负载 */
    private static void adjustForGpuBound() {
        // CPU 承担更多剔除
        cullingGpuRatio = adjustToward(cullingGpuRatio, 0.3f);
        // 减少 Async Compute（让 GPU 专注渲染）
        asyncComputeRatio = adjustToward(asyncComputeRatio, 0.2f);
        // CPU 做更多预过滤
        cpuWorkFactor = adjustToward(cpuWorkFactor, 1.0f);
        // 降低后处理质量
        postProcessScale = adjustToward(postProcessScale, 0.6f);
        gpuWorkFactor = adjustToward(gpuWorkFactor, 0.7f);
    }

    /** 平衡状态 → 维持 */
    private static void maintainBalance() {
        // 缓慢回归默认值
        cullingGpuRatio = adjustToward(cullingGpuRatio, 0.8f);
        asyncComputeRatio = adjustToward(asyncComputeRatio, 0.5f);
        cpuWorkFactor = adjustToward(cpuWorkFactor, 1.0f);
        postProcessScale = adjustToward(postProcessScale, 1.0f);
        gpuWorkFactor = adjustToward(gpuWorkFactor, 1.0f);
    }

    /** 渐进调整：每帧最多移动 MAX_ADJUSTMENT_PER_FRAME */
    private static float adjustToward(float current, float target) {
        float delta = target - current;
        float clampedDelta = Math.max(-MAX_ADJUSTMENT_PER_FRAME,
            Math.min(MAX_ADJUSTMENT_PER_FRAME, delta));
        return current + clampedDelta;
    }

    // ──────── 外部查询 API ────────

    /** 当前瓶颈状态 */
    public static Bottleneck getBottleneck() { return currentBottleneck; }

    /** CPU 帧时间 EMA (ms) */
    public static float getCpuFrameTime() { return cpuFrameTimeEma; }

    /** GPU 帧时间 EMA (ms) */
    public static float getGpuFrameTime() { return gpuFrameTimeEma; }

    /** GPU 工作负载因子 (0.0~1.0) */
    public static float getGpuWorkFactor() { return gpuWorkFactor; }

    /** CPU 工作负载因子 (0.0~1.0) */
    public static float getCpuWorkFactor() { return cpuWorkFactor; }

    /** Async Compute 使用比例 (0.0~1.0) */
    public static float getAsyncComputeRatio() { return asyncComputeRatio; }

    /** 后处理质量缩放 (0.0~1.0) */
    public static float getPostProcessScale() { return postProcessScale; }

    /** 剔除 GPU 比例 (0.0~1.0) */
    public static float getCullingGpuRatio() { return cullingGpuRatio; }

    /** 是否应该使用 Async Compute Queue（基于当前负载） */
    public static boolean shouldUseAsyncCompute() {
        return asyncComputeRatio > 0.3f
            && VulkanDeviceHolder.getInstance().getComputeQueue() != 0L;
    }

    /** 是否应该跳过 CPU 侧预剔除（GPU 全权处理） */
    public static boolean shouldSkipCpuCulling() {
        return cullingGpuRatio > 0.9f;
    }

    /** 是否应该降低后处理质量 */
    public static boolean shouldReducePostProcess() {
        return postProcessScale < 0.8f;
    }

    /** 获取当前应启用的后处理 Pass 数量 */
    public static int getActivePostProcessPassCount(int totalPasses) {
        return Math.max(1, Math.round(totalPasses * postProcessScale));
    }

    // ──────── 手动覆盖 ────────

    /** 手动设置瓶颈状态（调试用） */
    public static void forceBottleneck(Bottleneck b) {
        currentBottleneck = b;
        cooldownCounter.set(0);
    }

    /** 重置所有参数到默认值 */
    public static void reset() {
        cpuFrameTimeEma = TARGET_FRAME_TIME_MS;
        gpuFrameTimeEma = TARGET_FRAME_TIME_MS;
        currentBottleneck = Bottleneck.BALANCED;
        cooldownCounter.set(0);
        gpuWorkFactor = 1.0f;
        cpuWorkFactor = 1.0f;
        asyncComputeRatio = 0.5f;
        postProcessScale = 1.0f;
        cullingGpuRatio = 0.8f;
    }

    // ──────── 诊断 ────────

    public static String getDiagnostics() {
        return String.format(
            "Balancer[%s] CPU=%.2fms GPU=%.2fms gpuFactor=%.2f cpuFactor=%.2f async=%.2f postScale=%.2f cullGpu=%.2f frames=%d(cpu=%d gpu=%d bal=%d)",
            currentBottleneck,
            cpuFrameTimeEma, gpuFrameTimeEma,
            gpuWorkFactor, cpuWorkFactor,
            asyncComputeRatio, postProcessScale, cullingGpuRatio,
            totalFrames.get(), cpuBoundFrames.get(), gpuBoundFrames.get(), balancedFrames.get()
        );
    }
}
