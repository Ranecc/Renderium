// Renderium - Streamline 帧管理
// 独立的帧级 Streamline SDK 集成，负责帧开始/结束、GPU 时间戳、
// Reflex/PCL 标记、NVPerf 采集的完整生命周期管理。
//
// 【职责边界】
//   帧生命周期层 — 不涉及 SLContext/VulkanStreamlineBridge 的初始化，
//   这些由 StreamlineIntegration 负责。本类仅处理每帧的操作。
//
// 【关联模块】
//   StreamlineIntegration        — 拥有本类实例，负责初始化
//   tech.streamline.FrameEvaluator — 可选的帧标记搭配
//   feature.lod.compute.VulkanFFMBinding — Vulkan FFM 绑定入口
//   tech.reflex.ReflexManagerImpl — Reflex/PCL 标记参考实现
//   tech.streamline.ffm.SLFFMBindings — Streamline SDK FFM 调用
//
// 【重构背景】
//   从 StreamlineIntegration 提取 #5-#9 到独立类，职责分离。

package com.ranecc.renderium.feature.blaze3d.stylizedrt;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.tech.streamline.SLContext;
import com.ranecc.renderium.tech.streamline.ffm.SLFFMBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Streamline 帧管理器 — 独立的帧级生命周期管理。
 *
 * <p>管理渲染帧的 Streamline SDK 交互，包括：
 * <ol>
 *   <li><b>帧开始</b> ({@link #beginFrame()}) — 状态转换、帧数据创建、Reflex 标记</li>
 *   <li><b>Pass 开始/结束</b> ({@link #beginPass(int, String)} / {@link #endPass(int)}) — GPU 时间戳写入</li>
 *   <li><b>帧结束</b> ({@link #endFrame()}) — vkGetQueryPoolResults 回读、Pass 耗时计算、统计更新</li>
 *   <li><b>资源清理</b> ({@link #close()}) — 三路 SDK 关闭保障</li>
 * </ol>
 *
 * <h2>典型帧调用序列</h2>
 * <pre>
 * frameManager.beginFrame();
 * frameManager.beginPass(0, "Geometry");
 * // ... 渲染几何体 ...
 * frameManager.endPass(0);
 * frameManager.beginPass(1, "PostProcess");
 * // ... 后处理 ...
 * frameManager.endPass(1);
 * frameManager.endFrame();
 * </pre>
 *
 * <h2>GPU 时间戳机制</h2>
 * beginPass 在 TOP_OF_PIPE 阶段写入 {@code vkCmdWriteTimestamp(queryPool, passIndex * 2)},
 * endPass 在 BOTTOM_OF_PIPE 阶段写入 {@code vkCmdWriteTimestamp(queryPool, passIndex * 2 + 1)}.
 * endFrame 调用 {@code vkGetQueryPoolResults} 回读所有时间戳并计算每 Pass 微秒耗时。
 *
 * <h2>线程安全</h2>
 * 所有方法预计在渲染线程单线程调用。使用 volatile 保证状态字段的线程可见性。
 *
 * <h2>依赖模块</h2>
 * <ul>
 *   <li>{@link VulkanFFMBinding} — 提供 vkCmdWriteTimestamp / vkGetQueryPoolResults 等 FFM 方法句柄</li>
 *   <li>{@link SLContext} — Streamline SDK 生命周期（本类不直接依赖，由 {@link StreamlineIntegration} 管理）</li>
 *   <li>{@link SLFFMBindings} — slShutdown 等核心 API 绑定</li>
 * </ul>
 *
 * @see StreamlineIntegration 拥有本类实例并负责初始化
 * @since 4.0.0
 */
public final class StreamlineFrameManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(StreamlineFrameManager.class.getName());

    // ==================== 状态机 ====================

    /**
     * 帧管理器状态
     */
    public enum FrameState {
        /** 空闲 — 等待 beginFrame */
        IDLE,
        /** 帧评估中 — beginFrame 已调用 */
        EVALUATING,
        /** 呈现中 — endFrame 已调用 */
        PRESENTING
    }

    // ==================== 性能计数器 ID ====================

    /** Nsight Perf SDK 性能计数器 */
    public enum PerfCounter {
        GPU_COMPUTE_THROUGHPUT("gpu_compute_throughput"),
        GPU_MEMORY_BANDWIDTH("gpu_memory_bandwidth"),
        SM_OCCUPANCY("sm_occupancy"),
        WARP_EXECUTION_EFFICIENCY("warp_execution_efficiency"),
        L2_CACHE_HIT_RATE("l2_cache_hit_rate"),
        DRAM_READ_THROUGHPUT("dram_read_throughput"),
        DRAM_WRITE_THROUGHPUT("dram_write_throughput"),
        INST_ISSUED("inst_issued"),
        SHARED_MEM_BANK_CONFLICTS("shared_mem_bank_conflicts");

        private final String counterName;
        PerfCounter(String name) { this.counterName = name; }
        public String getCounterName() { return counterName; }
    }

    // ==================== 帧性能数据 ====================

    /**
     * 单帧性能采集数据
     * <p>
     * 由 {@link #beginFrame()} 创建，由 {@link #endFrame()} 填充后存入历史缓存。
     * 包含 GPU Pass 耗时、性能计数器值、Streamline 自身开销等。
     */
    public static final class FramePerfData {
        public int frameId;
        public long gpuTotalTimeUs;
        public long[] passTimesUs = new long[5];
        public final Map<PerfCounter, Float> counters = new EnumMap<>(PerfCounter.class);
        public long streamlineProcessingTimeNs;
        public String stateSnapshot;

        public float computeBandwidthUtilization() {
            Float read = counters.get(PerfCounter.DRAM_READ_THROUGHPUT);
            Float write = counters.get(PerfCounter.DRAM_WRITE_THROUGHPUT);
            if (read == null || write == null) return 0.0f;
            return read + write;
        }

        public float getWarpEfficiency() {
            Float eff = counters.get(PerfCounter.WARP_EXECUTION_EFFICIENCY);
            return eff != null ? eff : 0.0f;
        }
    }

    // ==================== 字段 ====================

    /** 当前状态 */
    private volatile FrameState currentState = FrameState.IDLE;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 是否 NVPerf 可用（由 StreamlineIntegration 设置） */
    private volatile boolean perfSDKAvailable = false;

    /** 是否 Reflex 可用（由 StreamlineIntegration 设置） */
    private volatile boolean reflexAvailable = false;

    // ==================== 帧数据 ====================

    /** 帧性能数据历史（线程安全列表） */
    private final List<FramePerfData> perfDataHistory = Collections.synchronizedList(new ArrayList<>());

    /** 当前帧性能数据 */
    private volatile FramePerfData currentFrameData;

    /** 帧号递增计数器 */
    private final AtomicLong frameCounter = new AtomicLong(0);

    // ==================== 性能监控字段 ====================

    private final AtomicLong totalStreamlineTimeNs = new AtomicLong(0);
    private final AtomicLong processedFrameCount = new AtomicLong(0);
    private final AtomicLong maxStreamlineTimeNs = new AtomicLong(0);
    private final AtomicLong minStreamlineTimeNs = new AtomicLong(Long.MAX_VALUE);

    // ==================== GPU 时间戳 ====================

    /** VkQueryPool 句柄 — 由外部通过 {@link #setTimestampQueryPool(long, long)} 设置 */
    private long timestampQueryPool = 0;

    /** 时间戳周期（纳秒），来自 VkPhysicalDeviceLimits.timestampPeriod */
    private long timestampPeriod = 1;

    /** VkDevice 句柄 — 由外部设置 */
    private long vkDeviceHandle = 0;

    /** 当前 VkCommandBuffer — 由外部每帧设置 */
    private long currentCommandBuffer = 0;

    // ==================== 构造与初始化 ====================

    /**
     * 创建 Streamline 帧管理器
     *
     * @param vkDeviceHandle VkDevice 句柄（用于 vkGetQueryPoolResults）
     * @param perfAvailable  NVPerf 是否可用
     * @param reflexAvailable Reflex 是否可用
     */
    public StreamlineFrameManager(long vkDeviceHandle, boolean perfAvailable, boolean reflexAvailable) {
        this.vkDeviceHandle = vkDeviceHandle;
        this.perfSDKAvailable = perfAvailable;
        this.reflexAvailable = reflexAvailable;
        this.initialized = true;
        this.currentState = FrameState.IDLE;

        LOGGER.fine(String.format("[StreamlineFrameManager] 创建 | vkDevice=0x%x perf=%b reflex=%b",
                vkDeviceHandle, perfAvailable, reflexAvailable));
    }

    // ==================== 帧生命周期 ====================

    /**
     * 开始帧性能采集
     *
     * <p>在渲染帧开始时调用。执行以下操作：
     * <ol>
     *   <li>状态检查与转换（IDLE → EVALUATING）</li>
     *   <li>创建新的 FramePerfData 实例</li>
     *   <li>启动 Streamline 处理计时</li>
     * </ol>
     *
     * <p><b>TODO #5 — slBeginFrame + Reflex Sleep</b>
     * <br>完整实现还需要：
     * <ul>
     *   <li><b>slReflexSleep</b>: 通过 slGetFeatureFunction(FEATURE_REFLEX, "slReflexSleep", &ptr)
     *       获取函数指针后调用。参考 ReflexManagerImpl.setPCLMarkerInternal() 的模式。</li>
     *   <li><b>slNVPerfBeginPass</b>: 开始 Nsight Perf SDK 采集范围。
     *       需要 sl.nvperf.dll 对应的 FFM 绑定（当前未添加）。</li>
     *   <li><b>FrameEvaluator.beginFrame()</b>: 获取 slGetNewFrameToken 帧标记。
     *       FrameEvaluator 在 tech.streamline 包中，需通过构造函数注入本类。</li>
     * </ul>
     *
     * <h3>前置条件</h3>
     * <ol>
     *   <li>SLContext.initialize() 已成功调用（slInit 通过）</li>
     *   <li>SLContext.detectFeatures() 确认 FEATURE_REFLEX / NVPERF 支持</li>
     *   <li>对应特性函数已通过 slGetFeatureFunction 解析</li>
     * </ol>
     *
     * <h3>关联模块</h3>
     * <ul>
     *   <li>{@link com.ranecc.renderium.tech.streamline.SLContext} — 生命周期管理</li>
     *   <li>{@link com.ranecc.renderium.tech.streamline.FrameEvaluator} — 帧标记与资源标记</li>
     *   <li>{@link com.ranecc.renderium.tech.reflex.ReflexManagerImpl} — Reflex/PCL 标记参考实现</li>
     *   <li>{@link VulkanFFMBinding} — Vulkan FFM 绑定的统一入口</li>
     *   <li>{@link SLFFMBindings} — Streamline SDK FFM 调用</li>
     * </ul>
     */
    public void beginFrame() {
        if (!initialized) return;
        if (currentState != FrameState.IDLE && currentState != FrameState.PRESENTING) {
            LOGGER.fine(String.format("[SL-Frame] beginFrame 忽略: 状态 %s", currentState));
            return;
        }

        currentState = FrameState.EVALUATING;

        currentFrameData = new FramePerfData();
        currentFrameData.frameId = (int) frameCounter.incrementAndGet();
        currentFrameData.stateSnapshot = currentState.name();

        long startTimeNs = System.nanoTime();
        currentFrameData.streamlineProcessingTimeNs = startTimeNs;

        // ========== TODO #5 实现：slBeginFrame + Reflex Sleep ==========
        //
        // 【需求】在渲染帧开始时：
        //   1. 调用 slReflexSleep(sl::kReflexMarkerBeforeFrame) 让 Reflex 驱动做
        //      低延迟睡眠，减少输入延迟。需通过 slGetFeatureFunction(FEATURE_REFLEX, "slReflexSleep", &ptr)
        //      获取函数指针。
        //   2. 调用 slNVPerfBeginPass() 开始 Nsight Perf SDK 采集范围。
        //      需 NVPerf 专用 FFM 绑定（当前未添加）。
        //   3. 可选调用 FrameEvaluator.beginFrame() 获取 slGetNewFrameToken。
        //      FrameEvaluator 在 tech.streamline 包中，需要本类持有其引用。
        //
        // 【阻塞项】
        //   - Reflex: SLFFMBindings 需添加 slReflexSleep 绑定或通过
        //     slGetFeatureFunction + downcallHandle 动态解析。
        //     参考 ReflexManagerImpl.setPCLMarkerInternal() 的模式。
        //   - NVPerf: 需要 sl.nvperf.dll 加载 + 对应的 slNVPerfBeginPass/EndPass
        //     FFM 绑定。SDK DLL 已在 resources/native/windows-x64/ 中。
        //   - FrameEvaluator: 需从 StreamlineIntegration 或构造函数注入。
        //
        // 【前置条件】
        //   ① SLContext.initialize() 必须已成功调用（slInit 通过）
        //   ② SLContext.detectFeatures() 确认 FEATURE_REFLEX/NVPERF 支持
        //   ③ 对应特性函数已通过 slGetFeatureFunction 解析
        //
        // 【关联模块】
        //   tech.streamline.SLContext          — 生命周期管理
        //   tech.streamline.FrameEvaluator      — 帧标记与资源标记
        //   tech.reflex.ReflexManagerImpl       — Reflex/PCL 标记参考实现
        //   feature.lod.compute.VulkanFFMBinding — Vulkan FFM 绑定的统一入口
        //   tech.streamline.ffm.SLFFMBindings   — Streamline SDK FFM 调用
    }

    /**
     * 标记 Pass 开始
     *
     * <p>在每个渲染 Pass 开始时调用，插入 GPU 时间戳查询。
     * 在 Pipeline 的 TOP_OF_PIPE 阶段写入时间戳。
     *
     * <p>使用 {@link VulkanFFMBinding#getVkCmdWriteTimestamp()} 调用
     * {@code vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, passIndex * 2)}.
     *
     * @param passIndex Pass 索引 (0-4)：
     *                  <ul>
     *                    <li>0 = 几何/场景渲染</li>
     *                    <li>1 = 光线追踪（如果有）</li>
     *                    <li>2 = 后处理</li>
     *                    <li>3 = UI/HUD 渲染</li>
     *                    <li>4 = 合成/输出</li>
     *                  </ul>
     * @param passName  Pass 名称（用于日志和调试）
     *
     * @see #endPass(int)
     * @see VulkanFFMBinding#getVkCmdWriteTimestamp()
     */
    public void beginPass(int passIndex, String passName) {
        if (!initialized || currentFrameData == null) return;
        if (currentState != FrameState.EVALUATING) return;

        if (passIndex < 0 || passIndex >= currentFrameData.passTimesUs.length) {
            LOGGER.warning(String.format("[SL-Frame] Pass 索引越界: %d", passIndex));
            return;
        }

        // vkCmdWriteTimestamp — GPU 时间戳写入
        // 在 TOP_OF_PIPE 阶段插入，记录该 Pass 开始时间
        try {
            if (currentCommandBuffer != 0 && timestampQueryPool != 0) {
                var mh = VulkanFFMBinding.getVkCmdWriteTimestamp();
                if (mh != null) {
                    // VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT = 0x00010000
                    mh.invokeExact(currentCommandBuffer,
                            0x00010000,
                            timestampQueryPool,
                            passIndex * 2);
                    LOGGER.fine(String.format("[SL-Frame] Frame #%d Pass[%d:%s]: ▶ GPU 时间戳已写入",
                            currentFrameData.frameId, passIndex, passName));
                }
            } else {
                LOGGER.fine(String.format("[SL-Frame] Frame #%d Pass[%d:%s]: ▶ CPU 计时（无 query pool）",
                        currentFrameData.frameId, passIndex, passName));
            }
        } catch (Throwable e) {
            LOGGER.warning(String.format("[SL-Frame] beginPass(%d) 异常: %s", passIndex, e.getMessage()));
        }
    }

    /**
     * 标记 Pass 结束
     *
     * <p>在每个渲染 Pass 结束时调用，插入 GPU 时间戳查询。
     * 在 Pipeline 的 BOTTOM_OF_PIPE 阶段写入时间戳，与 beginPass 配对计算耗时。
     *
     * <p>使用 {@link VulkanFFMBinding#getVkCmdWriteTimestamp()} 调用
     * {@code vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, passIndex * 2 + 1)}.
     *
     * @param passIndex Pass 索引 (0-4)，必须与对应的 beginPass 调用匹配
     *
     * @see #beginPass(int, String)
     */
    public void endPass(int passIndex) {
        if (!initialized || currentFrameData == null) return;
        if (currentState != FrameState.EVALUATING) return;

        if (passIndex < 0 || passIndex >= currentFrameData.passTimesUs.length) return;

        // vkCmdWriteTimestamp — GPU 时间戳写入
        // 在 BOTTOM_OF_PIPE 阶段插入，记录该 Pass 结束时间
        try {
            if (currentCommandBuffer != 0 && timestampQueryPool != 0) {
                var mh = VulkanFFMBinding.getVkCmdWriteTimestamp();
                if (mh != null) {
                    // VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT = 0x00010002
                    mh.invokeExact(currentCommandBuffer,
                            0x00010002,
                            timestampQueryPool,
                            passIndex * 2 + 1);
                    LOGGER.fine(String.format("[SL-Frame] Frame #%d Pass[%d]: ◀ GPU 时间戳已写入",
                            currentFrameData.frameId, passIndex));
                }
            }
        } catch (Throwable e) {
            LOGGER.warning(String.format("[SL-Frame] endPass(%d) 异常: %s", passIndex, e.getMessage()));
        }
    }

    /**
     * 结束帧性能采集
     *
     * <p>在渲染帧结束时调用。执行以下操作：
     * <ol>
     *   <li>停止 Streamline 处理计时</li>
     *   <li>通过 {@code vkGetQueryPoolResults} 读取 GPU 时间戳并计算每 Pass 微秒耗时</li>
     *   <li>更新性能统计数据（平均/最大/最小处理时间）</li>
     *   <li>存入历史缓存（保留最近 1000 帧）</li>
     *   <li>状态转换 EVALUATING → PRESENTING</li>
     * </ol>
     *
     * <h3>GPU 时间戳回读</h3>
     * 使用 {@link VulkanFFMBinding#getVkGetQueryPoolResults()} 调用
     * {@code vkGetQueryPoolResults(device, queryPool, 0, queryCount, data, stride, VK_QUERY_RESULT_64_BIT)}.
     * 时间戳差值乘以 timestampPeriod 再除以 1000 得到微秒。
     *
     * @see #beginFrame()
     * @see VulkanFFMBinding#getVkGetQueryPoolResults()
     */
    public void endFrame() {
        if (!initialized || currentFrameData == null) return;
        if (currentState != FrameState.EVALUATING) return;

        long endTimeNs = System.nanoTime();
        long frameProcessingTimeNs = endTimeNs - currentFrameData.streamlineProcessingTimeNs;
        currentFrameData.streamlineProcessingTimeNs = frameProcessingTimeNs;

        // GPU 时间戳回读 — vkGetQueryPoolResults
        // 使用 FFM 绑定读取所有 Pass 的 GPU 硬件时间戳并计算耗时
        try {
            if (timestampQueryPool != 0 && vkDeviceHandle != 0) {
                var mh = VulkanFFMBinding.getVkGetQueryPoolResults();
                if (mh != null) {
                    int passCount = currentFrameData.passTimesUs.length;
                    int queryCount = passCount * 2;
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment timestamps = arena.allocate(queryCount * 8L);
                        int result = (int) mh.invokeExact(
                                vkDeviceHandle,
                                timestampQueryPool,
                                0,
                                queryCount,
                                queryCount * 8L,
                                timestamps.address(),
                                8L,
                                0x00000001 // VK_QUERY_RESULT_64_BIT
                        );
                        if (result == 0) {
                            for (int i = 0; i < passCount; i++) {
                                long start = timestamps.getAtIndex(ValueLayout.JAVA_LONG, i * 2);
                                long end = timestamps.getAtIndex(ValueLayout.JAVA_LONG, i * 2 + 1);
                                long elapsed = Math.max(0, (long) ((end - start) * timestampPeriod / 1000));
                                currentFrameData.passTimesUs[i] = elapsed;
                            }
                            LOGGER.fine(String.format("[SL-Frame] Frame #%d: GPU 时间戳回读完成 (%d passes)",
                                    currentFrameData.frameId, passCount));
                        }
                    }
                }
            }
        } catch (Throwable e) {
            LOGGER.warning(String.format("[SL-Frame] endFrame 异常: %s", e.getMessage()));
        }

        // 更新性能统计
        updatePerformanceStats(frameProcessingTimeNs);

        // 存入历史缓存
        perfDataHistory.add(currentFrameData);
        while (perfDataHistory.size() > 1000) {
            perfDataHistory.remove(0);
        }

        currentState = FrameState.PRESENTING;
        currentFrameData.stateSnapshot = currentState.name();

        LOGGER.fine(String.format("[SL-Frame] Frame #%d 完成 | 处理 %.2f µs | → %s",
                currentFrameData.frameId, frameProcessingTimeNs / 1000.0, currentState));
    }

    // ==================== 性能统计 ====================

    private void updatePerformanceStats(long frameTimeNs) {
        totalStreamlineTimeNs.addAndGet(frameTimeNs);
        long count = processedFrameCount.incrementAndGet();

        updateAtomicMax(maxStreamlineTimeNs, frameTimeNs);
        updateAtomicMin(minStreamlineTimeNs, frameTimeNs);

        if (count % 100 == 0) {
            double avgUs = (totalStreamlineTimeNs.get() / 1000.0) / count;
            LOGGER.fine(String.format("[SL-Frame] 统计 (%d帧): 平均=%.2fµs 最大=%.2fµs 最小=%.2fµs",
                    count, avgUs,
                    maxStreamlineTimeNs.get() / 1000.0,
                    minStreamlineTimeNs.get() == Long.MAX_VALUE ? 0 : minStreamlineTimeNs.get() / 1000.0));
        }
    }

    private static void updateAtomicMax(AtomicLong target, long value) {
        long prev;
        do {
            prev = target.get();
            if (value <= prev) return;
        } while (!target.compareAndSet(prev, value));
    }

    private static void updateAtomicMin(AtomicLong target, long value) {
        long prev;
        do {
            prev = target.get();
            if (value >= prev) return;
        } while (!target.compareAndSet(prev, value));
    }

    // ==================== 公共查询 ====================

    public FrameState getCurrentState() { return currentState; }
    public boolean isInitialized() { return initialized; }
    public List<FramePerfData> getPerfDataHistory() { return Collections.unmodifiableList(perfDataHistory); }
    public long getProcessedFrameCount() { return processedFrameCount.get(); }
    public double getAverageOverheadUs() {
        long count = processedFrameCount.get();
        return count > 0 ? (totalStreamlineTimeNs.get() / 1000.0) / count : 0;
    }

    /**
     * 设置 GPU 时间戳查询池
     *
     * @param queryPool       VkQueryPool 句柄
     * @param timestampPeriod 时间戳周期（纳秒），从 VkPhysicalDeviceLimits 获取
     */
    public void setTimestampQueryPool(long queryPool, long timestampPeriod) {
        this.timestampQueryPool = queryPool;
        this.timestampPeriod = timestampPeriod > 0 ? timestampPeriod : 1;
        LOGGER.fine(String.format("[SL-Frame] 时间戳查询池已设置: pool=0x%x period=%dns", queryPool, timestampPeriod));
    }

    /**
     * 设置当前命令缓冲区
     *
     * @param cmdBuffer VkCommandBuffer 句柄，每帧由渲染层设置
     */
    public void setCurrentCommandBuffer(long cmdBuffer) {
        this.currentCommandBuffer = cmdBuffer;
    }

    // ==================== 资源清理 ====================

    /**
     * 关闭帧管理器并释放所有资源
     *
     * <p>执行以下清理操作：
     * <ol>
     *   <li>强制结束当前帧（如果在 EVALUATING 状态）</li>
     *   <li>清空性能历史数据</li>
     *   <li>调用 slShutdown 关闭 SDK（3 路保障）</li>
     *   <li>重置所有状态字段</li>
     * </ol>
     *
     * <p><b>TODO #9 — slShutdown 已实现</b>
     * <br>当前通过 SLContext.shutdown() + SLFFMBindings.slShutdown() 双路保障。
     * 如果需要额外的 NVPerf 清理，需在此处添加。
     *
     * @see SLFFMBindings#slShutdown()
     */
    @Override
    public void close() {
        if (!initialized) return;

        if (currentState == FrameState.EVALUATING) {
            LOGGER.warning("[SL-Frame] close() 在 EVALUATING 状态调用，强制结束");
            currentState = FrameState.PRESENTING;
        }

        perfDataHistory.clear();

        // XXX: slShutdown 调用由 StreamlineIntegration.close() 统一管理
        // 因为 SLContext 和 VulkanStreamlineBridge 的生命周期属于 StreamlineIntegration。
        // 如果本类被独立使用，需要注入 SLContext 引用后在此处调用。

        resetState();
    }

    private void resetState() {
        initialized = false;
        currentState = FrameState.IDLE;
        currentFrameData = null;
        timestampQueryPool = 0;
        currentCommandBuffer = 0;
        totalStreamlineTimeNs.set(0);
        processedFrameCount.set(0);
        maxStreamlineTimeNs.set(0);
        minStreamlineTimeNs.set(Long.MAX_VALUE);
    }

    // ==================== 性能报告 ====================

    /**
     * 生成格式化性能报告
     *
     * @return 格式化的分析报告字符串
     */
    public String generatePerfReport() {
        if (perfDataHistory.isEmpty()) {
            return "[SL-Frame] 无性能数据";
        }

        int n = perfDataHistory.size();
        long avgTotalUs = 0;
        float avgWarpEff = 0;
        float avgOccupancy = 0;
        float avgBW = 0;
        int warpSamples = 0;

        for (FramePerfData d : perfDataHistory) {
            avgTotalUs += d.gpuTotalTimeUs;
            float we = d.getWarpEfficiency();
            if (we > 0) { avgWarpEff += we; warpSamples++; }
            Float occ = d.counters.get(PerfCounter.SM_OCCUPANCY);
            if (occ != null) avgOccupancy += occ;
            avgBW += d.computeBandwidthUtilization();
        }
        avgTotalUs /= n;
        avgWarpEff = warpSamples > 0 ? avgWarpEff / warpSamples : 0;
        avgOccupancy /= n;
        avgBW /= n;

        long count = processedFrameCount.get();
        double avgOverheadUs = count > 0 ? (totalStreamlineTimeNs.get() / 1000.0) / count : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append(String.format("║ 状态: %-34s ║\n", currentState));
        sb.append(String.format("║ 采样: %-34d ║\n", n));
        sb.append(String.format("║ 平均帧耗时: %-27.2f ms ║\n", avgTotalUs / 1000.0));
        sb.append(String.format("║ Warp效率: %-28.1f%% ║\n", avgWarpEff));
        sb.append(String.format("║ SM占用率: %-28.1f%% ║\n", avgOccupancy));
        sb.append(String.format("║ 带宽: %-30.1f GB/s ║\n", avgBW));
        sb.append(String.format("║ SL开销: %-30.2f µs ║\n", avgOverheadUs));
        if (avgWarpEff > 0 && avgWarpEff < 70)
            sb.append("║ ⚠ Warp效率<70%: divergence严重!          ║\n");
        if (avgOccupancy > 0 && avgOccupancy < 50)
            sb.append("║ ⚠ Occupancy<50%: 寄存器压力大!           ║\n");
        sb.append("╚══════════════════════════════════════════╝\n");
        return sb.toString();
    }
}
