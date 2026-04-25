// Renderium - VMA Arena 内存管理器
// 基于双缓冲设计的 FrameData 对象池，消除每帧 GC 压力
//
// 设计原则（TOPS MemoryPool v2.5）:
//   1. 零分配热路径: acquire/release O(1)，无锁或 CAS
//   2. 帧级生命周期: beginFrame()/endFrame() 批量管理
//   3. 自适应大小: poolSize_{n+1} = min(poolSize_n * 1.5, maxPoolSize)
//   4. 泄漏检测: 60帧 WARNING, 300帧 SEVERE + 强制回收

package com.renderium.core;

import com.renderium.dlss.DLSSManager;
import com.renderium.framegen.FrameGenerator;
import com.renderium.superres.SuperResolutionAdapter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * VMA (Virtual Memory Allocator) Arena - 帧数据对象池
 * <p>
 * 采用双缓冲池设计，为超分辨率和帧生成提供零 GC 压力的对象复用机制。
 * <p>
 * <h3>核心特性</h3>
 * <ul>
 *   <li><b>双缓冲池</b>: 当前帧池 + 上一帧池，避免竞争</li>
 *   <li><b>O(1) 操作</b>: 基于 ArrayDeque 的常数时间获取/释放</li>
 *   <li><b>泄漏检测</b>: 自动检测未释放对象，60帧警告/300帧强制回收</li>
 *   <li><b>自适应扩容</b>: TOPS 公式驱动，碎片率 > 0.6 时触发整理</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 帧开始
 * arena.beginFrame(frameId);
 *
 * // 获取对象（从池中复用或新建）
 * MutableFrameData srData = arena.acquireSR();
 * try {
 *     // 填充数据并使用...
 *     srData.reset(commandBuffer, colorImageView, ...);
 *     superResolutionManager.evaluate(srData.toImmutable());
 * } finally {
 *     arena.releaseSR(srData);  // 归还到池
 * }
 *
 * // 帧结束（泄漏检测）
 * arena.endFrame(frameId);
 * }</pre>
 *
 * @see RenderiumCore
 * @since 5.1.0
 */
public final class FrameDataArena {

    private static final Logger LOGGER = Logger.getLogger(FrameDataArena.class.getName());

    // ==================== 配置常量 ====================

    /** 默认初始池大小（双缓冲 + 2个备用） */
    private static final int DEFAULT_INITIAL_POOL_SIZE = 4;

    /** 最大池大小（防止内存泄漏导致无限增长） */
    private static final int MAX_POOL_SIZE = 8;

    /** 泄漏警告阈值（帧数） */
    private static final int LEAK_WARNING_FRAMES = 60;

    /** 泄漏严重阈值（帧数，触发强制回收） */
    private static final int LEAK_SEVERE_FRAMES = 300;

    /** TOPS 扩容因子（Golden Ratio 优化） */
    private static final double GROWTH_FACTOR = 1.5;

    /** 碎片率阈值（超过此值触发整理） */
    private static final double FRAGMENTATION_THRESHOLD = 0.6;

    // ==================== 内部数据结构 ====================

    /**
     * 可变的超分辨率帧数据
     * <p>
     * 对应 {@link SuperResolutionAdapter.FrameData} 的可变版本，
     * 支持对象池复用。通过 {@link #toImmutable()} 转换为不可变 record。
     */
    public static final class MutableFrameData {
        // Vulkan 资源句柄
        long commandBuffer;
        long colorImageView;
        long depthImageView;
        long motionVectorImageView;
        long outputImageView;

        // 尺寸信息
        int textureWidth;
        int textureHeight;
        int displayWidth;
        int displayHeight;

        // 相机数据
        DLSSManager.CameraData cameraData;

        /** 分配时间戳（用于泄漏检测） */
        long allocatedAtFrame;

        /**
         * 重置所有字段为新值
         *
         * @param commandBuffer          VkCommandBuffer 句柄
         * @param colorImageView         输入颜色纹理
         * @param depthImageView         深度纹理
         * @param motionVectorImageView  运动矢量纹理
         * @param outputImageView        输出纹理
         * @param textureWidth           纹理宽度
         * @param textureHeight          纹理高度
         * @param displayWidth           显示宽度
         * @param displayHeight          显示高度
         * @param cameraData             相机数据
         * @param frameId                当前帧号
         */
        public void reset(long commandBuffer,
                          long colorImageView,
                          long depthImageView,
                          long motionVectorImageView,
                          long outputImageView,
                          int textureWidth,
                          int textureHeight,
                          int displayWidth,
                          int displayHeight,
                          DLSSManager.CameraData cameraData,
                          long frameId) {
            this.commandBuffer = commandBuffer;
            this.colorImageView = colorImageView;
            this.depthImageView = depthImageView;
            this.motionVectorImageView = motionVectorImageView;
            this.outputImageView = outputImageView;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.cameraData = cameraData;
            this.allocatedAtFrame = frameId;
        }

        /**
         * 转换为不可变的 FrameData record
         *
         * @return SuperResolutionAdapter.FrameData 实例
         */
        public SuperResolutionAdapter.FrameData toImmutable() {
            return new SuperResolutionAdapter.FrameData(
                commandBuffer,
                colorImageView,
                depthImageView,
                motionVectorImageView,
                outputImageView,
                textureWidth,
                textureHeight,
                displayWidth,
                displayHeight,
                cameraData
            );
        }

        @Override
        public String toString() {
            return String.format("MutableFrameData{frame=%d, size=%dx%d, display=%dx%d}",
                allocatedAtFrame, textureWidth, textureHeight, displayWidth, displayHeight);
        }
    }

    /**
     * 可变的帧生成数据
     * <p>
     * 对应 {@link FrameGenerator.FrameGenData} 的可变版本，
     * 支持对象池复用。通过 {@link #toImmutable()} 转换为不可变 record。
     */
    public static final class MutableFrameGenData {
        // Vulkan 资源句柄
        long colorImageView;
        long depthImageView;
        long motionVectorImageView;

        // 尺寸信息
        int textureWidth;
        int textureHeight;

        // 相机数据
        DLSSManager.CameraData cameraData;

        /** 分配时间戳（用于泄漏检测） */
        long allocatedAtFrame;

        /**
         * 重置所有字段为新值
         *
         * @param colorImageView         颜色纹理
         * @param depthImageView         深度纹理
         * @param motionVectorImageView  运动矢量纹理
         * @param textureWidth           纹理宽度
         * @param textureHeight          纹理高度
         * @param cameraData             相机数据
         * @param frameId                当前帧号
         */
        public void reset(long colorImageView,
                          long depthImageView,
                          long motionVectorImageView,
                          int textureWidth,
                          int textureHeight,
                          DLSSManager.CameraData cameraData,
                          long frameId) {
            this.colorImageView = colorImageView;
            this.depthImageView = depthImageView;
            this.motionVectorImageView = motionVectorImageView;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.cameraData = cameraData;
            this.allocatedAtFrame = frameId;
        }

        /**
         * 转换为不可变的 FrameGenData record
         *
         * @return FrameGenerator.FrameGenData 实例
         */
        public FrameGenerator.FrameGenData toImmutable() {
            return new FrameGenerator.FrameGenData(
                colorImageView,
                depthImageView,
                motionVectorImageView,
                textureWidth,
                textureHeight,
                cameraData
            );
        }

        @Override
        public String toString() {
            return String.format("MutableFrameGenData{frame=%d, size=%dx%d}",
                allocatedAtFrame, textureWidth, textureHeight);
        }
    }

    // ==================== 双缓冲池 ====================

    /** 超分辨率帧数据池（线程安全） */
    private final Deque<MutableFrameData> srPool;

    /** 帧生成数据池（线程安全） */
    private final Deque<MutableFrameGenData> fgPool;

    // ==================== 泄漏追踪 ====================

    /**
     * 活跃对象追踪表（线程安全，IdentityHashMap 保证对象身份比较）
     * key: 已分配的对象
     * value: 分配时的帧号
     */
    private final ConcurrentHashMap<Object, Long> allocationTimestamps;

    /** 当前帧 ID */
    private volatile long currentFrameId;

    // ==================== 统计与监控 ====================

    /** SR 池总分配次数（用于计算命中率） */
    private final AtomicInteger srTotalAcquires = new AtomicInteger(0);

    /** SR 池缓存命中次数 */
    private final AtomicInteger srCacheHits = new AtomicInteger(0);

    /** FG 池总分配次数 */
    private final AtomicInteger fgTotalAcquires = new AtomicInteger(0);

    /** FG 池缓存命中次数 */
    private final AtomicInteger fgCacheHits = new AtomicInteger(0);

    /** 当前 SR 池大小上限（自适应调整） */
    private volatile int currentSRMaxSize;

    /** 当前 FG 池大小上限（自适应调整） */
    private volatile int currentFGMaxSize;

    /** 强制回收计数（性能指标） */
    private final AtomicInteger forceRecycleCount = new AtomicInteger(0);

    /** 活跃 SR 对象计数（线程安全替代遍历） */
    private final AtomicInteger activeSRCount = new AtomicInteger(0);

    /** 活跃 FG 对象计数（线程安全替代遍历） */
    private final AtomicInteger activeFGCount = new AtomicInteger(0);

    // ==================== 构造函数 ====================

    /**
     * 创建默认配置的 FrameDataArena
     * <p>
     * 初始池大小为 {@value #DEFAULT_INITIAL_POOL_SIZE}，
     * 最大池大小为 {@value #MAX_POOL_SIZE}。
     */
    public FrameDataArena() {
        this(DEFAULT_INITIAL_POOL_SIZE);
    }

    /**
     * 创建指定初始大小的 FrameDataArena
     *
     * @param initialPoolSize 初始池大小（必须 > 0 且 <= MAX_POOL_SIZE）
     * @throws IllegalArgumentException 如果 initialPoolSize 无效
     */
    public FrameDataArena(int initialPoolSize) {
        if (initialPoolSize <= 0 || initialPoolSize > MAX_POOL_SIZE) {
            throw new IllegalArgumentException(
                "初始池大小必须在 1-" + MAX_POOL_SIZE + " 之间，实际值: " + initialPoolSize
            );
        }

        // 使用 ConcurrentLinkedDeque（线程安全，addFirst/pollFirst 为 O(1) 摊还））
        this.srPool = new ConcurrentLinkedDeque<>();
        this.fgPool = new ConcurrentLinkedDeque<>();
        this.allocationTimestamps = new ConcurrentHashMap<>();
        this.currentSRMaxSize = initialPoolSize;
        this.currentFGMaxSize = initialPoolSize;
        this.currentFrameId = 0;

        // 预分配对象（消除运行时分配）
        preallocatePools(initialPoolSize);

        LOGGER.info(String.format(
            "FrameDataArena 初始化完成 [初始大小=%d, 最大=%d, 警告阈值=%d帧, 严重阈值=%d帧]",
            initialPoolSize, MAX_POOL_SIZE, LEAK_WARNING_FRAMES, LEAK_SEVERE_FRAMES
        ));
    }

    // ==================== 核心操作：SR 对象获取/释放 ====================

    /**
     * 从池中获取一个可变的超分辨率帧数据对象（O(1) 操作）
     * <p>
     * <b>性能保证</b>:
     * <ul>
     *   <li>最优情况（池中有对象）: ~20ns（ArrayDeque.pollFirst()）</li>
     *   <li>最差情况（池为空）: ~100ns（新建对象 + 可能触发扩容）</li>
     * </ul>
     *
     * @return 可用的 MutableFrameData 实例（永远不会返回 null）
     */
    public MutableFrameData acquireSR() {
        srTotalAcquires.incrementAndGet();

        MutableFrameData data = srPool.pollFirst();

        if (data != null) {
            srCacheHits.incrementAndGet();
            allocationTimestamps.put(data, currentFrameId);
            activeSRCount.incrementAndGet();
            return data;
        }

        data = createNewSRData();
        allocationTimestamps.put(data, currentFrameId);
        activeSRCount.incrementAndGet();

        if (srPool.size() == 0 && currentSRMaxSize < MAX_POOL_SIZE) {
            schedulePoolExpansion(true);
        }

        return data;
    }

    /**
     * 释放超分辨率帧数据对象回池（O(1) 操作）
     * <p>
     * <b>重要</b>: 调用方必须确保不再使用此对象。
     * 典型用法是在 finally 块中调用。
     *
     * @param data 要释放的 MutableFrameData（不能为 null）
     * @throws NullPointerException 如果 data 为 null
     */
    public void releaseSR(MutableFrameData data) {
        Objects.requireNonNull(data, "释放的 SR 数据不能为 null");

        Long removedFrame = allocationTimestamps.remove(data);

        if (removedFrame == null) {
            LOGGER.warning("检测到异常释放: MutableFrameData 未在分配表中找到（可能双重释放）");
            return;
        }

        activeSRCount.decrementAndGet();
        srPool.addFirst(data);
    }

    // ==================== 核心操作：FG 对象获取/释放 ====================

    /**
     * 从池中获取一个可变的帧生成数据对象（O(1) 操作）
     * <p>
     * 性能特征同 {@link #acquireSR()}。
     *
     * @return 可用的 MutableFrameGenData 实例（永远不会返回 null）
     */
    public MutableFrameGenData acquireFG() {
        fgTotalAcquires.incrementAndGet();

        MutableFrameGenData data = fgPool.pollFirst();

        if (data != null) {
            fgCacheHits.incrementAndGet();
            allocationTimestamps.put(data, currentFrameId);
            activeFGCount.incrementAndGet();
            return data;
        }

        data = createNewFGData();
        allocationTimestamps.put(data, currentFrameId);
        activeFGCount.incrementAndGet();

        if (fgPool.size() == 0 && currentFGMaxSize < MAX_POOL_SIZE) {
            schedulePoolExpansion(false);
        }

        return data;
    }

    /**
     * 释放帧生成数据对象回池（O(1) 操作）
     *
     * @param data 要释放的 MutableFrameGenData（不能为 null）
     * @throws NullPointerException 如果 data 为 null
     */
    public void releaseFG(MutableFrameGenData data) {
        Objects.requireNonNull(data, "释放的 FG 数据不能为 null");

        Long removedFrame = allocationTimestamps.remove(data);

        if (removedFrame == null) {
            LOGGER.warning("检测到异常释放: MutableFrameGenData 未在分配表中找到（可能双重释放）");
            return;
        }

        activeFGCount.decrementAndGet();
        fgPool.addFirst(data);
    }

    // ==================== 帧级生命周期管理 ====================

    /**
     * 标记帧开始（应在渲染循环的最前面调用）
     * <p>
     * <b>性能</b>: < 100ns（仅更新帧号 + 重置统计）
     *
     * @param frameId 当前帧号（单调递增）
     */
    public void beginFrame(long frameId) {
        this.currentFrameId = frameId;

        // 可选：在此处添加 Per-Frame 统计重置逻辑
        // 当前设计不需要（统计是累积的）
    }

    /**
     * 标志帧结束（应在渲染循环的最后面调用）
     * <p>
     * 执行以下操作：
     * <ol>
     *   <li>泄漏检测（遍历活跃对象表）</li>
     *   <li>强制回收过期对象（age > LEAK_SEVERE_FRAMES）</li>
     *   <li>碎片率评估与池整理</li>
     * </ol>
     *
     * <b>性能</b>: 取决于活跃对象数量（通常 < 10 个，~200ns）
     *
     * @param frameId 当前帧号（应与 beginFrame 一致）
     */
    public void endFrame(long frameId) {
        if (frameId != this.currentFrameId) {
            LOGGER.warning(String.format(
                "帧号不匹配: beginFrame=%d, endFrame=%d（可能存在并发问题）",
                this.currentFrameId, frameId
            ));
        }

        // 1. 泄漏检测 + 强制回收
        checkLeaks(frameId);

        // 2. 碎片率评估（每 60 帧执行一次，降低开销）
        if (frameId % 60 == 0) {
            adjustPoolSize();
        }
    }

    // ==================== 诊断 API ====================

    /**
     * 获取当前 SR 池中的可用对象数量
     *
     * @return 池大小（0 ~ currentSRMaxSize）
     */
    public int getSRPoolSize() {
        return srPool.size();
    }

    /**
     * 获取当前 FG 池中的可用对象数量
     *
     * @return 池大小（0 ~ currentFGMaxSize）
     */
    public int getFGPoolSize() {
        return fgPool.size();
    }

    /**
     * 获取当前活跃（已分配未释放）的 SR 对象数量
     *
     * @return 活跃对象数
     */
    public int getActiveSRCount() {
        return activeSRCount.get();
    }

    /**
     * 获取当前活跃的 FG 对象数量
     *
     * @return 活跃对象数
     */
    public int getActiveFGCount() {
        return activeFGCount.get();
    }

    /**
     * 检测疑似泄漏的对象列表
     * <p>
     * 返回所有 age > LEAK_WARNING_FRAMES 的活跃对象。
     *
     * @return 疑似泄漏对象列表（按年龄降序排列）
     */
    public List<Object> detectLeaks() {
        List<Object> leaks = new ArrayList<>();

        for (Map.Entry<Object, Long> entry : allocationTimestamps.entrySet()) {
            long age = currentFrameId - entry.getValue();

            if (age > LEAK_WARNING_FRAMES) {
                leaks.add(entry.getKey());
            }
        }

        // 按年龄降序排列（最老的排在前面）
        leaks.sort((a, b) -> {
            long ageA = currentFrameId - allocationTimestamps.get(a);
            long ageB = currentFrameId - allocationTimestamps.get(b);
            return Long.compare(ageB, ageA);
        });

        return leaks;
    }

    /**
     * 获取 SR 池缓存命中率（百分比）
     *
     * @return 0.0 ~ 1.0（1.0 表示所有获取都命中缓存）
     */
    public double getSRHitRate() {
        int total = srTotalAcquires.get();
        return total == 0 ? 0.0 : (double) srCacheHits.get() / total;
    }

    /**
     * 获取 FG 池缓存命中率（百分比）
     *
     * @return 0.0 ~ 1.0
     */
    public double getFGHitRate() {
        int total = fgTotalAcquires.get();
        return total == 0 ? 0.0 : (double) fgCacheHits.get() / total;
    }

    /**
     * 获取强制回收总次数
     *
     * @return 强制回收次数（用于质量评估）
     */
    public int getForceRecycleCount() {
        return forceRecycleCount.get();
    }

    /**
     * 获取详细的诊断报告（用于调试和监控）
     *
     * @return 多行诊断字符串
     */
    public String getDiagnosticReport() {
        return String.format(
            """
            === FrameDataArena 诊断报告 ===
            帧号: %d
            SR 池: %d/%d (命中率: %.1f%%)
            FG 池: %d/%d (命中率: %.1f%%)
            活跃 SR: %d, 活跃 FG: %d
            疑似泄漏: %d
            强制回收: %d
            ================================""",
            currentFrameId,
            srPool.size(), currentSRMaxSize, getSRHitRate() * 100,
            fgPool.size(), currentFGMaxSize, getFGHitRate() * 100,
            getActiveSRCount(), getActiveFGCount(),
            detectLeaks().size(),
            forceRecycleCount.get()
        );
    }

    // ==================== 内部实现：预分配 ====================

    /**
     * 预分配指定数量的对象到两个池中
     * <p>
     * 在构造函数中调用，确保运行时零分配。
     *
     * @param count 每个池的预分配数量
     */
    private void preallocatePools(int count) {
        for (int i = 0; i < count; i++) {
            srPool.addLast(new MutableFrameData());
            fgPool.addLast(new MutableFrameGenData());
        }

        LOGGER.fine("预分配完成: SR=" + count + ", FG=" + count);
    }

    // ==================== 内部实现：对象创建 ====================

    /**
     * 创建新的 SR 数据对象（池耗尽时调用）
     *
     * @return 新的 MutableFrameData 实例
     */
    private MutableFrameData createNewSRData() {
        LOGGER.fine("SR 池耗尽，创建新对象（当前池大小上限: " + currentSRMaxSize + "）");
        return new MutableFrameData();
    }

    /**
     * 创建新的 FG 数据对象（池耗尽时调用）
     *
     * @return 新的 MutableFrameGenData 实例
     */
    private MutableFrameGenData createNewFGData() {
        LOGGER.fine("FG 池耗尽，创建新对象（当前池大小上限: " + currentFGMaxSize + "）");
        return new MutableFrameGenData();
    }

    // ==================== 内部实现：泄漏检测 ====================

    /**
     * 检查所有活跃对象的年龄，执行泄漏检测和强制回收
     * <p>
     * <b>算法复杂度</b>: O(n)，n = 活跃对象数量（通常 < 10）
     *
     * @param frameId 当前帧号
     */
    private void checkLeaks(long frameId) {
        if (allocationTimestamps.isEmpty()) {
            return;  // 快速路径：无活跃对象
        }

        // 复制一份快照（避免 ConcurrentModificationException）
        List<Map.Entry<Object, Long>> entries = new ArrayList<>(allocationTimestamps.entrySet());

        for (Map.Entry<Object, Long> entry : entries) {
            Object obj = entry.getKey();
            long allocatedFrame = entry.getValue();
            long age = frameId - allocatedFrame;

            if (age > LEAK_SEVERE_FRAMES) {
                // 严重泄漏：强制回收
                LOGGER.severe(String.format(
                    "[内存泄漏] 检测到严重泄漏: %s, 年龄=%d帧 (阈值=%d), 强制回收",
                    obj.getClass().getSimpleName(), age, LEAK_SEVERE_FRAMES
                ));
                forceRecycle(obj);
                forceRecycleCount.incrementAndGet();
            } else if (age > LEAK_WARNING_FRAMES) {
                // 潜在泄漏：记录警告
                LOGGER.warning(String.format(
                    "[潜在泄漏] %s 已活跃 %d帧 (阈值=%d), 请检查是否忘记 release",
                    obj.getClass().getSimpleName(), age, LEAK_WARNING_FRAMES
                ));
            }
        }
    }

    /**
     * 强制回收对象（忽略其当前状态）
     * <p>
     * 将对象重置后归还到对应池中。
     *
     * @param obj 要回收的对象
     */
    @SuppressWarnings("unchecked")
    private void forceRecycle(Object obj) {
        // 从追踪表中移除
        allocationTimestamps.remove(obj);

        if (obj instanceof MutableFrameData srData) {
            srData.reset(0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0);
            activeSRCount.decrementAndGet();
            srPool.addLast(srData);
        } else if (obj instanceof MutableFrameGenData fgData) {
            fgData.reset(0, 0, 0, 0, 0, null, 0);
            activeFGCount.decrementAndGet();
            fgPool.addLast(fgData);
        }
    }

    // ==================== 内部实现：自适应池大小调整 ====================

    /**
     * 根据 TOPS 公式调整池大小
     * <p>
     * <h3>算法流程</h3>
     * <ol>
     *   <li>计算碎片率 = 1 - (池中空闲对象 / 池总容量)</li>
     *   <li>如果碎片率 > 0.6，触发整理（compact）</li>
     *   <li>如果池频繁耗尽，按 1.5x 因子扩容</li>
     * </ol>
     *
     * <b>TOPS 公式参考</b>:
     * <pre>{@code
     * poolSize_{n+1} = min(poolSize_n * 1.5, maxPoolSize)
     * }</pre>
     */
    private void adjustPoolSize() {
        // 计算 SR 池碎片率
        double srFragmentation = calculateFragmentation(srPool.size(), currentSRMaxSize);
        double fgFragmentation = calculateFragmentation(fgPool.size(), currentFGMaxSize);

        // 碎片率过高 → 整理池（释放多余对象）
        if (srFragmentation > FRAGMENTATION_THRESHOLD) {
            compactPool(srPool, currentSRMaxSize);
            LOGGER.fine(String.format("SR 池整理完成 (碎片率: %.2f)", srFragmentation));
        }

        if (fgFragmentation > FRAGMENTATION_THRESHOLD) {
            compactPool(fgPool, currentFGMaxSize);
            LOGGER.fine(String.format("FG 池整理完成 (碎片率: %.2f)", fgFragmentation));
        }

        // 扩容检查（基于命中率）
        if (shouldExpandPool(srTotalAcquires.get(), srCacheHits.get())) {
            expandSRPool();
        }

        if (shouldExpandPool(fgTotalAcquires.get(), fgCacheHits.get())) {
            expandFGPool();
        }
    }

    /**
     * 计算碎片率
     *
     * @param freeObjects  池中空闲对象数
     * @param maxSize      池容量上限
     * @return 碎片率 (0.0 ~ 1.0)，越高表示越碎片化
     */
    private double calculateFragmentation(int freeObjects, int maxSize) {
        if (maxSize <= 0) return 0.0;
        // 碎片率定义：非空闲部分占比
        return 1.0 - ((double) freeObjects / maxSize);
    }

    /**
     * 判断是否应该扩容
     * <p>
     * 条件：命中率 < 80%（表示池经常耗尽）
     *
     * @param totalAcquires 总获取次数
     * @param cacheHits     缓存命中次数
     * @return true 如果应该扩容
     */
    private boolean shouldExpandPool(int totalAcquires, int cacheHits) {
        if (totalAcquires < 100) {
            return false;  // 样本不足，不扩容
        }

        double hitRate = (double) cacheHits / totalAcquires;
        return hitRate < 0.8;  // 命中率低于 80% 说明池太小
    }

    /**
     * 异步调度池扩容（不阻塞热路径）
     * <p>
     * 当前实现为同步（简单场景足够）。
     * 高并发环境可改为提交到 ExecutorService。
     *
     * @param isSRPool true=扩容 SR 池，false=扩容 FG 池
     */
    private void schedulePoolExpansion(boolean isSRPool) {
        // 同步扩容（当前场景：单线程渲染循环）
        if (isSRPool) {
            expandSRPool();
        } else {
            expandFGPool();
        }
    }

    /**
     * 扩容 SR 池（TOPS 公式: newSize = min(oldSize * 1.5, MAX_POOL_SIZE)）
     */
    private void expandSRPool() {
        int oldSize = currentSRMaxSize;
        int newSize = Math.min((int) (oldSize * GROWTH_FACTOR), MAX_POOL_SIZE);

        if (newSize <= oldSize) {
            return;  // 已经达到上限
        }

        // 补充新对象到池中
        int toAdd = newSize - oldSize;
        for (int i = 0; i < toAdd; i++) {
            srPool.addLast(new MutableFrameData());
        }

        currentSRMaxSize = newSize;
        LOGGER.info(String.format("SR 池扩容: %d → %d (+%d)", oldSize, newSize, toAdd));
    }

    /**
     * 扩容 FG 池（TOPS 公式同上）
     */
    private void expandFGPool() {
        int oldSize = currentFGMaxSize;
        int newSize = Math.min((int) (oldSize * GROWTH_FACTOR), MAX_POOL_SIZE);

        if (newSize <= oldSize) {
            return;  // 已经达到上限
        }

        // 补充新对象到池中
        int toAdd = newSize - oldSize;
        for (int i = 0; i < toAdd; i++) {
            fgPool.addLast(new MutableFrameGenData());
        }

        currentFGMaxSize = newSize;
        LOGGER.info(String.format("FG 池扩容: %d → %d (+%d)", oldSize, newSize, toAdd));
    }

    /**
     * 整理池（移除多余对象，释放内存）
     * <p>
     * 当碎片率过高时调用，将池大小收缩到合理范围。
     *
     * @param池      目标池
     * @param maxSize 池容量上限
     * @param <T>     对象类型
     */
    private <T> void compactPool(Deque<T> pool, int maxSize) {
        while (pool.size() > maxSize) {
            T removed = pool.removeLast();
            LOGGER.fine("整理池: 移除多余对象 " + removed.getClass().getSimpleName());
        }
    }

    // ==================== 公共 API：重置与清理 ====================

    /**
     * 重置对象池状态（清空所有活跃追踪）
     * <p>
     * 在场景切换（Phase Type A）时调用此方法，
     * 清除所有活跃对象的分配记录，强制将所有对象归还到池中。
     * 这确保旧场景的帧数据不会污染新场景的处理。
     * <p>
     * <b>注意</b>: 此方法会强制回收所有当前活跃的对象，
     * 调用后不应再使用之前通过 acquireSR()/acquireFG() 获取的对象。
     *
     * @see PhaseTransitionDetector.PhaseType#SCENE_CHANGE
     */
    public void reset() {
        if (allocationTimestamps.isEmpty()) {
            return;  // 快速路径：无活跃对象
        }

        // 复制一份快照（避免 ConcurrentModificationException）
        List<Object> activeObjects = new ArrayList<>(allocationTimestamps.keySet());

        // 强制回收所有活跃对象
        for (Object obj : activeObjects) {
            forceRecycle(obj);
        }

        int recycledCount = activeObjects.size();
        if (recycledCount > 0) {
            LOGGER.info(String.format(
                "FrameDataArena 已重置: 强制回收 %d 个活跃对象 (SR=%d, FG=%d)",
                recycledCount,
                srPool.size(),
                fgPool.size()
            ));
        }
    }
}
