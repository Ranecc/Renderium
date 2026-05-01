// Renderium - Blaze3D 优化模块
// 显存 Arena 管理器 - 整合 Per-Frame/Ring/Pool 三种 Arena 模式 + VMA 渐进式清理

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import java.util.BitSet;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 显存 Arena 管理器
 * <p>
 * 基于 Arena 内存管理思想，实现零碎片、高性能的显存分配。
 * 参考 `vulkan-memory-arena-guide.md` 中的三种 Arena 模式：
 *
 * <h2>Arena 类型：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 1. Per-Frame Arena - 临时数据（Staging/Uniform）        │
 * │    特点：帧结束时整体重置，零碎片                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │ 2. Ring Buffer Arena - 动态 Uniform/Vertex Buffer      │
 * │    特点：循环使用，GPU 滞后读取                        │
 * ├─────────────────────────────────────────────────────────────┤
 * │ 3. Pool Arena - 固定大小块（Descriptor Set/小型Buffer）│
 * │    特点：O(1) 分配释放，位图管理                       │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计参考：</h3>
 * <ul>
 *   <li>Vulkan Memory Arena Guide (vulkan-memory-arena-guide.md)</li>
 *   <li>兼容模式 Vulkan 优化指南 (compatibility-mode-optimization.md)</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class MemoryOptimizer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(MemoryOptimizer.class.getName());

    // ==================== 配置常量 ====================

    /** 默认 Per-Frame Arena 大小 (64MB) */
    public static final long DEFAULT_FRAME_ARENA_SIZE = 64L * 1024 * 1024;

    /** 默认帧缓冲数量 */
    public static final int DEFAULT_FRAME_COUNT = 3;

    /** 默认 Ring Buffer 大小 (16MB) */
    public static final long DEFAULT_RING_BUFFER_SIZE = 16L * 1024 * 1024;

    /** 默认 Pool 块大小 (64KB) */
    public static final long DEFAULT_POOL_BLOCK_SIZE = 64 * 1024;

    /** 默认 Pool 块数量 */
    public static final int DEFAULT_POOL_BLOCK_COUNT = 256;

    // ==================== 子 Arena 组件 ====================

    /** Per-Frame Arena: 用于临时 Staging Buffer 和临时 Uniform */
    private volatile FrameArena frameArena;

    /** Ring Buffer Arena: 用于动态 Uniform/Vertex Buffer */
    private volatile RingBufferArena ringBufferArena;

    /** Pool Arena: 用于 Descriptor Set 和小型固定 Buffer */
    private volatile PoolArena poolArena;

    // ==================== VMA 渐进式清理组件 ====================

    /**
     * VMA 渐进式内存管理器
     * <p>负责多级预警、LRU资源追踪、渐进式清理</p>
     */
    private volatile GradualMemoryManager gradualMemoryManager;

    /** 是否启用渐进式清理 (默认启用) */
    private volatile boolean gradualCleanupEnabled = true;

    // ==================== 配置引用 ====================

    private final MemoryConfig config;

    // ==================== 状态字段 ====================

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== 全局统计 ====================

    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);
    private final AtomicLong peakUsageBytes = new AtomicLong(0);
    private final AtomicLong allocationCount = new AtomicLong(0);

    // ==================== 构造函数 ====================

    /**
     * 创建显存 Arena 管理器
     *
     * @param config RenderiumConfig 配置（自动提取 MemoryConfig）
     */
    public MemoryOptimizer(RenderiumConfig config) {
        this.config = config.getMemoryConfig();
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化所有 Arena
     * <p>
     * 按照 compatibility-mode-optimization.md 中的分级策略创建：
     * <ol>
     *   <li>Per-Frame Arena (DEVICE_LOCAL + HOST_VISIBLE)</li>
     *   <li>Ring Buffer Arena (HOST_VISIBLE | HOST_COHERENT)</li>
     *   <li>Pool Arena (HOST_VISIBLE | HOST_COHERENT)</li>
     * </ol>
     *
     * @return 成功返回 true
     */
    public boolean initialize() {
        if (initialized) return true;

        try {
            long frameArenaSize = config.getInitialPoolSizeMB() * 1024L * 1024L;
            if (frameArenaSize <= 0) {
                frameArenaSize = DEFAULT_FRAME_ARENA_SIZE;
            }

            // 1. 创建 Per-Frame Arena
            this.frameArena = new FrameArena(
                    frameArenaSize,
                    DEFAULT_FRAME_COUNT
            );
            this.frameArena.initialize();
            totalAllocatedBytes.addAndGet(frameArenaSize * DEFAULT_FRAME_COUNT);

            // 2. 创建 Ring Buffer Arena
            this.ringBufferArena = new RingBufferArena(
                    DEFAULT_RING_BUFFER_SIZE,
                    DEFAULT_FRAME_COUNT
            );
            this.ringBufferArena.initialize();
            totalAllocatedBytes.addAndGet(DEFAULT_RING_BUFFER_SIZE);

            // 3. 创建 Pool Arena
            this.poolArena = new PoolArena(
                    DEFAULT_POOL_BLOCK_SIZE,
                    DEFAULT_POOL_BLOCK_COUNT
            );
            this.poolArena.initialize();
            totalAllocatedBytes.addAndGet(DEFAULT_POOL_BLOCK_SIZE * DEFAULT_POOL_BLOCK_COUNT);

            this.initialized = true;

            // 4. 初始化 VMA 渐进式内存管理器
            if (gradualCleanupEnabled) {
                this.gradualMemoryManager = GradualMemoryManager.getInstance();
                this.gradualMemoryManager.initialize();
                LOGGER.info("✓ GradualMemoryManager (VMA 渐进清理) 已启用");
            }

            LOGGER.info(String.format(
                    "✓ MemoryOptimizer (Arena) initialized\n" +
                    "  FrameArena: %.1f MB × %d frames\n" +
                    "  RingBuffer: %.1f MB\n" +
                    "  Pool: %d blocks × %d bytes",
                    frameArenaSize / 1024.0 / 1024.0,
                    DEFAULT_FRAME_COUNT,
                    DEFAULT_RING_BUFFER_SIZE / 1024.0 / 1024.0,
                    DEFAULT_POOL_BLOCK_COUNT,
                    DEFAULT_POOL_BLOCK_SIZE
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize MemoryOptimizer: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启用所有 Arena
     */
    public void enable() { enabled = true; }

    /**
     * 禁用所有 Arena
     */
    public void disable() { enabled = false; }

    /**
     * 从 Pool Arena 分配内存
     *
     * @param label   分配标签（用于调试）
     * @param usage   使用类型
     * @param size    分配大小
     * @return ArenaAllocation 或 null
     */
    public ArenaAllocation allocateFromPoolArena(java.util.function.Supplier<String> label,
                                                  int usage, long size) {
        if (poolArena == null || !enabled) return null;
        try {
            BlockAllocation alloc = poolArena.allocate();
            if (alloc == null) return null;
            return new ArenaAllocation(alloc.blockIndex, alloc.offset, alloc.size);
        } catch (Exception e) {
            LOGGER.warning("PoolArena allocation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 Per-Frame Arena 分配内存
     *
     * @param label   分配标签（用于调试）
     * @param usage   使用类型
     * @param size    分配大小
     * @return ArenaAllocation 或 null
     */
    public ArenaAllocation allocateFromPerFrameArena(java.util.function.Supplier<String> label,
                                                      int usage, long size) {
        if (frameArena == null || !enabled) return null;
        try {
            return frameArena.allocate(size, 1L);
        } catch (Exception e) {
            LOGGER.warning("PerFrameArena allocation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 Ring Buffer Arena 分配内存
     *
     * @param label   分配标签（用于调试）
     * @param usage   使用类型
     * @param size    分配大小
     * @return ArenaAllocation 或 null
     */
    public ArenaAllocation allocateFromRingBufferArena(java.util.function.Supplier<String> label,
                                                        int usage, long size) {
        if (ringBufferArena == null || !enabled) return null;
        try {
            RingAllocation alloc = ringBufferArena.allocate(size, 1L);
            return new ArenaAllocation(0, alloc.offset, alloc.size);
        } catch (Exception e) {
            LOGGER.warning("RingBufferArena allocation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 安全销毁 AutoCloseable 资源
     *
     * @param resource 要销毁的资源
     */
    private static void safeDestroy(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception e) {
            Logger.getLogger(MemoryOptimizer.class.getName())
                  .warning("Failed to destroy " + resource.getClass().getSimpleName() +
                          ": " + e.getMessage());
        }
    }

    /**
     * 销毁所有 Arena 并释放显存
     */
    @Override
    public void close() {
        if (!initialized) return;

        // 关闭 VMA 渐进式内存管理器
        if (gradualMemoryManager != null) {
            gradualMemoryManager.shutdown();
            gradualMemoryManager = null;
            LOGGER.info("GradualMemoryManager 已关闭");
        }

        safeDestroy(poolArena);
        safeDestroy(ringBufferArena);
        safeDestroy(frameArena);

        poolArena = null;
        ringBufferArena = null;
        frameArena = null;

        enabled = false;
        initialized = false;

        totalAllocatedBytes.set(0);
        peakUsageBytes.set(0);
        allocationCount.set(0);

        LOGGER.info("MemoryOptimizer disposed");
    }

    // ==================== 帧管理 API ====================

    /**
     * 开始新帧
     * <p>
     * 调用此方法：
     * <ul>
     *   <li>重置当前帧的 Per-Frame Arena</li>
     *   <li>推进 Ring Buffer 写指针</li>
     *   <li>检查已完成的 Fence</li>
     * </ul>
     *
     * @return 当前帧索引
     */
    public int beginFrame() {
        if (!enabled || !initialized) return 0;

        int frameIndex = frameArena.beginFrame();
        ringBufferArena.beginFrame();

        return frameIndex;
    }

    /**
     * 结束当前帧
     *
     * @param fence GPU 提交的 Fence 句柄
     */
    public void endFrame(long fence) {
        if (!enabled || !initialized) return;

        frameArena.endFrame(fence);
        ringBufferArena.endFrame(fence);

        // 更新峰值统计
        long currentUsage = getCurrentTotalUsage();
        long peak = peakUsageBytes.get();
        if (currentUsage > peak) {
            peakUsageBytes.set(currentUsage);
        }

        // 执行 VMA 渐进式内存清理
        if (gradualCleanupEnabled && gradualMemoryManager != null) {
            gradualMemoryManager.updateMemoryPressure();

            // 当处于 WARNING 及以上级别时记录日志
            if (gradualMemoryManager.needsAttention()) {
                MemoryPressureLevel level = gradualMemoryManager.getCurrentLevel();
                LOGGER.fine(String.format("📊 VMA 内存压力: %s (%.1f%%)",
                        level.name(),
                        gradualMemoryManager.getConfig().softLimit * 100));
            }
        }
    }

    // ==================== 分配 API ====================

    /**
     * 从 Per-Frame Arena 分配临时内存
     * <p>
     * 适用场景：Staging Buffer、临时 Uniform、帧内临时纹理
     *
     * @param size      需要的大小（字节）
     * @param alignment 对齐要求（必须是 2 的幂）
     * @return ArenaAllocation，空间不足返回 null
     */
    public ArenaAllocation allocateFromFrame(long size, long alignment) {
        if (!enabled || frameArena == null) return null;

        ArenaAllocation alloc = frameArena.allocate(size, alignment);
        if (alloc != null) {
            allocationCount.incrementAndGet();
        }

        return alloc;
    }

    /**
     * 从 Ring Buffer Arena 分配动态 Uniform/Vertex Buffer
     * <p>
     * 适用场景：每帧更新的 Uniform Buffer、动态 Vertex 数据
     *
     * @param size      需要的大小
     * @param alignment 对齐要求
     * @return RingAllocation，空间不足抛出异常
     */
    public RingAllocation allocateFromRing(long size, long alignment) {
        if (!enabled || ringBufferArena == null) {
            throw new IllegalStateException("RingBufferArena not available");
        }

        allocationCount.incrementAndGet();
        return ringBufferArena.allocate(size, alignment);
    }

    /**
     * 从 Pool Arena 分配固定大小的块
     * <p>
     * 适用场景：Descriptor Set、小型固定 Buffer
     *
     * @return BlockAllocation，池满返回 null
     */
    public BlockAllocation allocateFromPool() {
        if (!enabled || poolArena == null) return null;

        BlockAllocation alloc = poolArena.allocate();
        if (alloc != null) {
            allocationCount.incrementAndGet();
        }

        return alloc;
    }

    /**
     * 释放 Pool Arena 中的块
     *
     * @param blockIndex 块索引
     */
    public void freePoolBlock(int blockIndex) {
        if (poolArena != null) {
            poolArena.free(blockIndex);
        }
    }

    // ==================== 查询 API ====================

    /**
     * 获取当前总使用量（字节）
     */
    public long getCurrentTotalUsage() {
        long total = 0;
        if (frameArena != null) total += frameArena.getCurrentUsage();
        if (ringBufferArena != null) total += ringBufferArena.getCurrentUsage();
        if (poolArena != null) total += poolArena.getCurrentUsage();
        return total;
    }

    /**
     * 获取总分配量（字节）
     */
    public long getTotalAllocated() { return totalAllocatedBytes.get(); }

    /**
     * 获取峰值使用量（字节）
     */
    public long getPeakUsage() { return peakUsageBytes.get(); }

    /**
     * 获取总分配次数
     */
    public long getAllocationCount() { return allocationCount.get(); }

    /**
     * 获取格式化的内存报告
     * <p>
     * 参考 vulkan-memory-arena-guide.md 中的监控仪表盘格式。
     * 包含 Arena 使用情况和 VMA 渐进式清理状态。
     */
    public String formatReport() {
        if (!initialized) return "MemoryOptimizer not initialized";

        long used = getCurrentTotalUsage();
        long allocated = getTotalAllocated();
        long peak = getPeakUsage();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Arena Memory Report:\n" +
                "  Total: %s / %s (%.1f%%)\n" +
                "  Peak: %s\n" +
                "  Allocations: %d\n" +
                "  [Frame] %s\n" +
                "  [Ring] %s\n" +
                "  [Pool] %s",
                formatSize(used),
                formatSize(allocated),
                allocated > 0 ? (used * 100.0 / allocated) : 0,
                formatSize(peak),
                getAllocationCount(),
                frameArena != null ? frameArena.formatStatus() : "N/A",
                ringBufferArena != null ? ringBufferArena.formatStatus() : "N/A",
                poolArena != null ? poolArena.formatStatus() : "N/A"
        ));

        // 添加 VMA 渐进式管理状态
        if (gradualMemoryManager != null && gradualCleanupEnabled) {
            sb.append("\n\n--- VMA Gradual Cleanup ---\n");
            sb.append(String.format("  Status: %s\n", gradualMemoryManager.isEnabled() ? "Enabled" : "Disabled"));
            sb.append(String.format("  Pressure Level: %s\n",
                    gradualMemoryManager.getCurrentLevel().name()));
            sb.append(String.format("  Needs Attention: %s\n",
                    gradualMemoryManager.needsAttention() ? "Yes" : "No"));

            // 当处于非正常状态时显示详细报告
            if (!gradualMemoryManager.isNormal()) {
                sb.append("\n").append(gradualMemoryManager.getStatusReport());
            }
        }

        return sb.toString();
    }

    /**
     * 是否已启用
     */
    public boolean isEnabled() { return enabled; }

    // ==================== VMA 渐进式管理 API ====================

    /**
     * 获取 VMA 渐进式内存管理器实例
     *
     * @return GradualMemoryManager 实例，未启用返回 null
     */
    public GradualMemoryManager getGradualMemoryManager() {
        return gradualMemoryManager;
    }

    /**
     * 是否启用了 VMA 渐进式清理
     */
    public boolean isGradualCleanupEnabled() { return gradualCleanupEnabled; }

    /**
     * 设置是否启用 VMA 渐进式清理
     *
     * @param enabled true 启用, false 禁用
     */
    public void setGradualCleanupEnabled(boolean enabled) {
        this.gradualCleanupEnabled = enabled;
        if (gradualMemoryManager != null) {
            gradualMemoryManager.setEnabled(enabled);
        }
    }

    /**
     * 获取当前内存压力级别
     *
     * @return MemoryPressureLevel，未初始化返回 null
     */
    public MemoryPressureLevel getCurrentPressureLevel() {
        if (gradualMemoryManager == null) return null;
        return gradualMemoryManager.getCurrentLevel();
    }

    /**
     * 手动触发一次强制清理
     *
     * @return 清理的资源数量
     */
    public int forceGradualCleanup() {
        if (gradualMemoryManager == null) return 0;
        return gradualMemoryManager.forceCleanup();
    }

    // ==================== 内部辅助类 ====================

    /**
     * 格式化字节数为可读字符串
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    private void safeDispose(AutoCloseable obj) {
        if (obj != null) {
            try { obj.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== 内部 Arena 实现 ====================

    /**
     * Per-Frame Arena 内部实现
     * <p>每帧独立的内存区域，帧结束时整体重置。
     */
    private static class FrameArena implements AutoCloseable {

        private final long arenaSize;
        private final int frameCount;
        private final long[] offsets;       // 每帧的当前偏移
        private final long[] fences;         // 每帧的 Fence
        private int currentFrame = 0;
        private boolean initialized = false;

        FrameArena(long arenaSize, int frameCount) {
            this.arenaSize = arenaSize;
            this.frameCount = frameCount;
            this.offsets = new long[frameCount];
            this.fences = new long[frameCount];
        }

        void initialize() {
            for (int i = 0; i < frameCount; i++) {
                offsets[i] = 0;
                fences[i] = 0;
            }
            initialized = true;
        }

        int beginFrame() {
            // TODO: vkWaitForFences(offsets[currentFrame])
            offsets[currentFrame] = 0;
            return currentFrame;
        }

        void endFrame(long fence) {
            fences[currentFrame] = fence;
            currentFrame = (currentFrame + 1) % frameCount;
        }

        synchronized ArenaAllocation allocate(long size, long alignment) {
            long alignedOffset = alignUp(offsets[currentFrame], alignment);
            long newOffset = alignedOffset + size;

            if (newOffset > arenaSize) {
                return null; // 空间不足
            }

            offsets[currentFrame] = newOffset;
            return new ArenaAllocation(currentFrame, alignedOffset, size);
        }

        long getCurrentUsage() { return offsets[currentFrame]; }

        String formatStatus() {
            return String.format("%.1f/%.1f MB (%d%%)",
                    getCurrentUsage() / 1024.0 / 1024.0,
                    arenaSize / 1024.0 / 1024.0,
                    arenaSize > 0 ? (int)(getCurrentUsage() * 100 / arenaSize) : 0);
        }

        @Override
        public void close() { initialized = false; }
    }

    /**
     * Ring Buffer Arena 内部实现
     * <p>循环使用的缓冲区，支持 CPU 写入/GPU 读取并行。
     */
    private static class RingBufferArena implements AutoCloseable {

        private final long bufferSize;
        private final int maxFramesInFlight;
        private long writeOffset = 0;
        private long readOffset = 0;
        private long currentFrameStart = 0;
        private int currentFrame = 0;
        private final Deque<PendingFrame> pendingFrames = new ArrayDeque<>();
        private boolean initialized = false;

        RingBufferArena(long bufferSize, int maxFramesInFlight) {
            this.bufferSize = bufferSize;
            this.maxFramesInFlight = maxFramesInFlight;
        }

        void initialize() { initialized = true; }

        synchronized void beginFrame() {
            while (!pendingFrames.isEmpty()) {
                PendingFrame p = pendingFrames.peek();
                // TODO: vkGetFenceStatus(p.fence)
                readOffset = p.end;
                pendingFrames.poll();
            }
            currentFrameStart = writeOffset;
        }

        void endFrame(long fence) {
            pendingFrames.add(new PendingFrame(currentFrame, currentFrameStart, writeOffset, fence));
            currentFrame++;
        }

        synchronized RingAllocation allocate(long size, long alignment) {
            long alignedOffset = alignUp(writeOffset, alignment);
            long newOffset = alignedOffset + size;

            if (newOffset > bufferSize) {
                // 回绕
                alignedOffset = 0;
                newOffset = size;
                if (size > readOffset && readOffset > 0) {
                    throw new OutOfMemoryError("Ring buffer exhausted");
                }
            }

            writeOffset = newOffset;
            return new RingAllocation(alignedOffset, size);
        }

        long getCurrentUsage() { return writeOffset >= readOffset ? writeOffset - readOffset : bufferSize - readOffset + writeOffset; }

        String formatStatus() {
            float utilization = bufferSize > 0 ? (float) getCurrentUsage() / bufferSize : 0;
            return String.format("%.1f/%.1f MB (%.0f%%)",
                    getCurrentUsage() / 1024.0 / 1024.0,
                    bufferSize / 1024.0 / 1024.0,
                    utilization * 100);
        }

        @Override
        public void close() { initialized = false; }

        record PendingFrame(int frame, long start, long end, long fence) {}
    }

    /**
     * Pool Arena 内部实现
     * <p>固定大小块的内存池，使用位图管理空闲块。
     */
    private static class PoolArena implements AutoCloseable {

        private final long blockSize;
        private final int blockCount;
        private final BitSet freeBlocks;
        private int allocatedCount = 0;
        private int peakUsage = 0;
        private boolean initialized = false;

        PoolArena(long blockSize, int blockCount) {
            this.blockSize = blockSize;
            this.blockCount = blockCount;
            this.freeBlocks = new BitSet(blockCount);
        }

        void initialize() {
            freeBlocks.set(0, blockCount); // 所有块初始空闲
            initialized = true;
        }

        synchronized BlockAllocation allocate() {
            int idx = freeBlocks.nextSetBit(0);
            if (idx < 0 || idx >= blockCount) return null;

            freeBlocks.clear(idx);
            allocatedCount++;
            peakUsage = Math.max(peakUsage, allocatedCount);

            long offset = (long) idx * blockSize;
            return new BlockAllocation(idx, offset, blockSize);
        }

        synchronized void free(int blockIndex) {
            if (blockIndex >= 0 && blockIndex < blockCount) {
                freeBlocks.set(blockIndex);
                allocatedCount--;
            }
        }

        long getCurrentUsage() { return (long) allocatedCount * blockSize; }

        String formatStatus() {
            float utilization = blockCount > 0 ? (float) allocatedCount / blockCount : 0;
            return String.format("%d/%d blocks (%.1f%%)",
                    allocatedCount, blockCount, utilization * 100);
        }

        @Override
        public void close() { initialized = false; }
    }

    // ==================== 分配结果类型 ====================

    /**
     * Per-Frame Arena 分配结果
     */
    public static class ArenaAllocation {
        public final int frameIndex;
        public final long offset;
        public final long size;

        ArenaAllocation(int frameIndex, long offset, long size) {
            this.frameIndex = frameIndex;
            this.offset = offset;
            this.size = size;
        }
    }

    /**
     * Ring Buffer Arena 分配结果
     */
    public static class RingAllocation {
        public final long offset;
        public final long size;

        RingAllocation(long offset, long size) {
            this.offset = offset;
            this.size = size;
        }
    }

    /**
     * Pool Arena 分配结果
     */
    public static class BlockAllocation {
        public final int blockIndex;
        public final long offset;
        public final long size;

        BlockAllocation(int blockIndex, long offset, long size) {
            this.blockIndex = blockIndex;
            this.offset = offset;
            this.size = size;
        }
    }

    // ==================== 辅助方法 ====================

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }
}
