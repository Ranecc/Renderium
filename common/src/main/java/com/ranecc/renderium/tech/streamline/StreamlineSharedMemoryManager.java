// Renderium - Streamline 模块
// 共享内存管理器 - 基于 Arena 思想的高性能零拷贝数据传输

package com.ranecc.renderium.tech.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Streamline 共享内存管理器 🚀
 * <p>
 * 基于 `vulkan-memory-arena-guide.md` 中的 Arena 内存管理思想和
 * `compatibility-mode-optimization.md` 中的零拷贝传输策略，
 * 实现高性能的 Java ↔ C++ 共享内存数据交换。
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  1. Ring Buffer 共享内存                                    │
 * │     - 双缓冲/三缓冲设计                                     │
 * │     - CPU 写入 / GPU 读取并行                               │
 * │     - 零拷贝数据传输                                        │
 * ├─────────────────────────────────────────────────────────────┤
 * │  2. 帧数据 Arena                                            │
 * │     - 每帧独立的内存区域                                    │
 * │     - 帧结束时整体重置                                      │
 * │     - 避免碎片和 GC 压力                                   │
 * ├─────────────────────────────────────────────────────────────┤
 * │  3. Resource Tag 缓存                                       │
 * │     - 复用频繁创建的 ResourceTag 结构                       │
 * │     - 减少对象分配开销                                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │  4. 性能监控                                                │
 * │     - 传输延迟统计                                          │
 * │     - 内存使用量监控                                        │
 * │     - 帧同步状态追踪                                        │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计参考：</h3>
 * <ul>
 *   <li>Vulkan Memory Arena Guide (vulkan-memory-arena-guide.md)</li>
 *   <li>兼容模式 Vulkan 优化指南 (compatibility-mode-optimization.md §2.1)</li>
 *   <li>Streamline C++ Bridge 设计 (abstract-layer-design.md)</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class StreamlineSharedMemoryManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(StreamlineSharedMemoryManager.class.getName());

    // ==================== 配置常量 ====================

    /** 默认帧缓冲数量（双缓冲） */
    public static final int DEFAULT_FRAME_BUFFER_COUNT = 2;

    /** 默认每帧数据区域大小 (4MB - 足够存储颜色深度 + 运动矢量等) */
    public static final long DEFAULT_FRAME_DATA_SIZE = 4L * 1024 * 1024;

    /** 默认控制信号区域大小 (1KB) */
    public static final long DEFAULT_CONTROL_SIGNAL_SIZE = 1024;

    /** 默认统计信息区域大小 (256 bytes) */
    public static final long DEFAULT_STATS_SIZE = 256;

    // ==================== 帧数据布局偏移（与 C++ 端对齐）====================

    /** 颜色缓冲区偏移（RGBA8） */
    public static final long OFFSET_COLOR_BUFFER = 0L;

    /** 深度缓冲区偏移 */
    public static final long OFFSET_DEPTH_BUFFER = DEFAULT_FRAME_DATA_SIZE / 4; // 1MB

    /** 运动矢量缓冲区偏移 */
    public static final long OFFSET_MOTION_VECTORS = DEFAULT_FRAME_DATA_SIZE / 2; // 2MB

    /** 相机数据偏移 */
    public static final long OFFSET_CAMERA_DATA = DEFAULT_FRAME_DATA_SIZE - 4096; // 最后 4KB

    // ==================== 控制信号定义 ====================

    /** 帧就绪标志位 */
    public static final int SIGNAL_FRAME_READY = 0x01;

    /** 输入数据已写入标志位 */
    public static final int SIGNAL_INPUT_WRITTEN = 0x02;

    /** 输出数据已读取标志位 */
    public static final int SIGNAL_OUTPUT_READ = 0x04;

    /** 帧重置请求标志位 */
    public static final int SIGNAL_RESET_HISTORY = 0x08;

    // ==================== 内部组件 ====================

    /**
     * 帧数据缓冲区
     * <p>每个帧的独立数据区域，采用 Ring Buffer 方式循环使用。
     */
    private static class FrameBuffer {
        /** 帧索引 */
        final int frameIndex;

        /** 数据区域的 MemorySegment */
        MemorySegment dataSegment;

        /** 控制信号区域的 MemorySegment */
        MemorySegment controlSegment;

        /** 是否正在被 GPU 使用 */
        boolean inUse;

        /** 分配时间戳（用于调试） */
        long allocateTimestamp;

        FrameBuffer(int frameIndex) {
            this.frameIndex = frameIndex;
            this.inUse = false;
            this.allocateTimestamp = 0;
        }
    }

    /** Java Foreign Arena（用于管理 native 内存） */
    private final Arena arena;

    /** 帧缓冲区数组（Ring Buffer） */
    private final FrameBuffer[] frameBuffers;

    /** 当前写入帧索引 */
    private volatile int currentWriteIndex = 0;

    /** 统计信息区域 */
    private final MemorySegment statsSegment;

    // ==================== 状态字段 ====================

    private volatile boolean initialized = false;
    private volatile boolean enabled = false;

    // ==================== 统计字段 ====================

    private final AtomicLong totalBytesTransferred = new AtomicLong(0);
    private final AtomicLong totalFramesProcessed = new AtomicLong(0);
    private final AtomicInteger currentFrameNumber = new AtomicInteger(0);
    private final AtomicLong peakMemoryUsage = new AtomicLong(0);

    // ==================== 构造函数 ====================

    /**
     * 创建共享内存管理器（使用默认配置）
     */
    public StreamlineSharedMemoryManager() {
        this(DEFAULT_FRAME_DATA_SIZE, DEFAULT_FRAME_BUFFER_COUNT);
    }

    /**
     * 创建共享内存管理器
     *
     * @param frameDataSize      每帧数据区域大小（字节）
     * @param frameBufferCount   帧缓冲区数量（建议 2 或 3）
     */
    public StreamlineSharedMemoryManager(long frameDataSize, int frameBufferCount) {
        // 使用全局 Arena 管理 native 内存（生命周期跟随整个应用）
        this.arena = Arena.global();

        // 初始化帧缓冲区数组
        this.frameBuffers = new FrameBuffer[frameBufferCount];
        for (int i = 0; i < frameBufferCount; i++) {
            frameBuffers[i] = new FrameBuffer(i);
        }

        // 分配统计信息区域
        this.statsSegment = arena.allocate(DEFAULT_STATS_SIZE);
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化共享内存管理器
     * <p>
     * 为每个帧缓冲区分配 native 内存。
     *
     * @return 成功返回 true
     */
    public boolean initialize() {
        if (initialized) return true;

        try {
            // 为每个帧缓冲区分配内存
            for (FrameBuffer fb : frameBuffers) {
                // 分配数据区域
                fb.dataSegment = arena.allocate(DEFAULT_FRAME_DATA_SIZE);

                // 分配控制信号区域
                fb.controlSegment = arena.allocate(DEFAULT_CONTROL_SIGNAL_SIZE);

                // 初始化控制信号为 0
                fb.controlSegment.set(ValueLayout.JAVA_INT, 0, 0);

                fb.allocateTimestamp = System.nanoTime();
            }

            // 初始化统计信息区域
            initStatsSegment();

            this.initialized = true;

            LOGGER.info(String.format(
                    "✓ StreamlineSharedMemoryManager initialized" +
                    "  Frame buffers: %d × %.1f MB" +
                    "  Control signals: %d bytes each" +
                    "  Stats: %d bytes" +
                    "  Total allocated: %.1f MB",
                    frameBuffers.length,
                    DEFAULT_FRAME_DATA_SIZE / 1024.0 / 1024.0,
                    DEFAULT_CONTROL_SIGNAL_SIZE,
                    DEFAULT_STATS_SIZE,
                    (long)(frameBuffers.length * DEFAULT_FRAME_DATA_SIZE + frameBuffers.length * DEFAULT_CONTROL_SIGNAL_SIZE + DEFAULT_STATS_SIZE) / 1024.0 / 1024.0
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize StreamlineSharedMemoryManager: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启用共享内存管理器
     */
    public void enable() {
        if (!initialized) {
            if (!initialize()) {
                LOGGER.severe("StreamlineSharedMemoryManager: 初始化失败，无法启用");
                return;
            }
        }

        enabled = true;
        LOGGER.info("✓ StreamlineSharedMemoryManager enabled");
    }

    /**
     * 禁用共享内存管理器
     */
    public void disable() { enabled = false; }

    /**
     * 销毁并释放所有资源
     */
    @Override
    public void close() {
        if (!initialized) return;

        // 注意：Arena.global() 不需要手动释放
        // 如果使用 Arena.ofConfined() 或 Arena.ofShared()，这里需要调用 arena.close()

        enabled = false;
        initialized = false;

        // 重置统计
        totalBytesTransferred.set(0);
        totalFramesProcessed.set(0);
        currentFrameNumber.set(0);
        peakMemoryUsage.set(0);

        LOGGER.info("StreamlineSharedMemoryManager disposed");
    }

    // ==================== 核心 API：帧数据写入 ====================

    /**
     * 开始新帧，获取可写的帧缓冲区
     * <p>
     * 基于 Ring Buffer 策略：
     * <ol>
     *   <li>检查下一帧缓冲区是否可用（GPU 已完成读取）</li>
     *   <li>标记为使用中</li>
     *   <li>返回可写的内存区域</li>
     * </ol>
     *
     * @return 帧数据句柄（包含 MemorySegment 和元数据），失败返回 null
     */
    public FrameDataHandle beginFrame() {
        if (!enabled || !initialized) return null;

        // 获取下一个可用的帧缓冲区
        int nextIndex = (currentWriteIndex + 1) % frameBuffers.length;
        FrameBuffer nextBuffer = frameBuffers[nextIndex];

        // TODO: 检查 GPU 是否已完成读取（通过 Fence 或控制信号）
        // if (nextBuffer.inUse) {
        //     LOGGER.warning("Frame buffer busy, skipping frame");
        //     return null;
        // }

        // 更新当前写入索引
        currentWriteIndex = nextIndex;
        nextBuffer.inUse = true;
        nextBuffer.allocateTimestamp = System.nanoTime();

        // 递增帧号
        int frameNum = currentFrameNumber.incrementAndGet();

        // 清空控制信号
        nextBuffer.controlSegment.set(ValueLayout.JAVA_INT, 0, 0);

        // 更新统计
        totalFramesProcessed.incrementAndGet();
        updatePeakMemoryUsage();

        LOGGER.fine(String.format(
                "Frame %d started, buffer index=%d", frameNum, nextIndex"
        ));

        return new FrameDataHandle(
                nextBuffer.dataSegment,
                nextBuffer.controlSegment,
                nextIndex,
                frameNum,
                DEFAULT_FRAME_DATA_SIZE
        );
    }

    /**
     * 结束当前帧，标记输入数据已就绪
     *
     * @param handle 帧数据句柄（来自 beginFrame）
     * @return 是否成功
     */
    public boolean endFrame(FrameDataHandle handle) {
        if (handle == null || !enabled || !initialized) return false;

        try {
            // 设置控制信号：输入已写入 + 帧就绪
            int signal = SIGNAL_INPUT_WRITTEN | SIGNAL_FRAME_READY;
            handle.controlSegment().set(ValueLayout.JAVA_INT, 0, signal);

            // 记录传输字节数
            totalBytesTransferred.addAndGet(handle.dataSize());

            LOGGER.fine(String.format(
                    "Frame %d ended, data ready for processing", handle.frameNumber()"
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to end frame: " + e.getMessage());
            return false;
        }
    }

    /**
     * 将颜色数据写入帧缓冲区
     * <p>
     * 高性能零拷贝写入（直接操作 native 内存）。
     *
     * @param handle 帧数据句柄
     * @param source 源数据（RGBA 格式）
     * @param offset 目标偏移（相对于颜色缓冲区起始位置）
     * @param length 写入长度（字节）
     */
    public void writeColorData(FrameDataHandle handle, MemorySegment source,
                                long offset, long length) {
        if (handle == null || source == null) return;

        // 计算目标地址：颜色缓冲区起始 + 偏移
        long targetOffset = OFFSET_COLOR_BUFFER + offset;

        // 边界检查
        if (targetOffset + length > handle.dataSize()) {
            LOGGER.warning("Color data write out of bounds");
            return;
        }

        // 零拷贝写入（直接内存复制）
        MemorySegment target = handle.dataSegment().asSlice(targetOffset, length);
        target.copyFrom(source);
    }

    /**
     * 将深度数据写入帧缓冲区
     *
     * @param handle 帧数据句柄
     * @param source 源数据
     * @param offset 目标偏移
     * @param length 写入长度
     */
    public void writeDepthData(FrameDataHandle handle, MemorySegment source,
                                long offset, long length) {
        if (handle == null || source == null) return;

        long targetOffset = OFFSET_DEPTH_BUFFER + offset;

        if (targetOffset + length > handle.dataSize()) {
            LOGGER.warning("Depth data write out of bounds");
            return;
        }

        MemorySegment target = handle.dataSegment().asSlice(targetOffset, length);
        target.copyFrom(source);
    }

    /**
     * 将运动矢量数据写入帧缓冲区
     *
     * @param handle 帧数据句柄
     * @param source 源数据
     * @param offset 目标偏移
     * @param length 写入长度
     */
    public void writeMotionVectors(FrameDataHandle handle, MemorySegment source,
                                   long offset, long length) {
        if (handle == null || source == null) return;

        long targetOffset = OFFSET_MOTION_VECTORS + offset;

        if (targetOffset + length > handle.dataSize()) {
            LOGGER.warning("Motion vectors write out of bounds");
            return;
        }

        MemorySegment target = handle.dataSegment().asSlice(targetOffset, length);
        target.copyFrom(source);
    }

    // ==================== 查询 API ====================

    /**
     * 是否已启用
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 是否已初始化
     */
    public boolean isInitialized() { return initialized; }

    /**
     * 获取总传输字节数
     */
    public long getTotalBytesTransferred() { return totalBytesTransferred.get(); }

    /**
     * 获取总处理帧数
     */
    public long getTotalFramesProcessed() { return totalFramesProcessed.get(); }

    /**
     * 获取当前帧号
     */
    public int getCurrentFrameNumber() { return currentFrameNumber.get(); }

    /**
     * 获取峰值内存使用量
     */
    public long getPeakMemoryUsage() { return peakMemoryUsage.get(); }

    /**
     * 获取格式化的性能报告
     * <p>
     * 参考 `compatibility-mode-optimization.md` §5.3 和 `vulkan-memory-arena-guide.md` 的监控仪表盘。
     */
    public String formatReport() {
        if (!initialized) return "StreamlineSharedMemoryManager not initialized";

        long transferred = getTotalBytesTransferred();
        long frames = getTotalFramesProcessed();
        double avgTransferPerFrame = frames > 0 ? (double) transferred / frames : 0;

        return String.format(
                "Streamline Shared Memory Report:" +
                "  Status: %s" +
                "  Frames processed: %d" +
                "  Total transferred: %.1f MB" +
                "  Avg transfer/frame: %.1f KB" +
                "  Peak memory: %.1f MB" +
                "  Current frame: %d",
                enabled ? "ENABLED" : "DISABLED",
                frames,
                transferred / 1024.0 / 1024.0,
                avgTransferPerFrame / 1024.0,
                getPeakMemoryUsage() / 1024.0 / 1024.0,
                getCurrentFrameNumber()
        );
    }

    // ==================== 内部实现方法 ====================

    /**
     * 初始化统计信息区域
     */
    private void initStatsSegment() {
        // 清零
        statsSegment.fill((byte) 0);
    }

    /**
     * 更新峰值内存使用量
     */
    private void updatePeakMemoryUsage() {
        long currentUsage = calculateCurrentMemoryUsage();
        long peak = peakMemoryUsage.get();
        if (currentUsage > peak) {
            peakMemoryUsage.set(currentUsage);
        }
    }

    /**
     * 计算当前内存使用量
     */
    private long calculateCurrentMemoryUsage() {
        long total = 0;
        for (FrameBuffer fb : frameBuffers) {
            if (fb.dataSegment != null) {
                total += DEFAULT_FRAME_DATA_SIZE;
            }
            if (fb.controlSegment != null) {
                total += DEFAULT_CONTROL_SIGNAL_SIZE;
            }
        }
        total += DEFAULT_STATS_SIZE;
        return total;
    }

    // ==================== 公共内部类 ====================

    /**
     * 帧数据句柄
     * <p>
     * 代表一帧的可写数据区域。
     * 包含数据段、控制信号段和元数据。
     */
    public static final class FrameDataHandle {
        /** 数据区域的 MemorySegment */
        private final MemorySegment dataSegment;

        /** 控制信号区域的 MemorySegment */
        private final MemorySegment controlSegment;

        /** 帧缓冲区索引 */
        private final int bufferIndex;

        /** 帧序号 */
        private final int frameNumber;

        /** 数据区域大小 */
        private final long dataSize;

        FrameDataHandle(MemorySegment dataSegment, MemorySegment controlSegment,
                         int bufferIndex, int frameNumber, long dataSize) {
            this.dataSegment = dataSegment;
            this.controlSegment = controlSegment;
            this.bufferIndex = bufferIndex;
            this.frameNumber = frameNumber;
            this.dataSize = dataSize;
        }

        /**
         * 获取数据区域
         */
        public MemorySegment dataSegment() { return dataSegment; }

        /**
         * 获取控制信号区域
         */
        public MemorySegment controlSegment() { return controlSegment; }

        /**
         * 获取帧缓冲区索引
         */
        public int bufferIndex() { return bufferIndex; }

        /**
         * 获取帧序号
         */
        public int frameNumber() { return frameNumber; }

        /**
         * 获取数据区域大小
         */
        public long dataSize() { return dataSize; }

        /**
         * 获取颜色缓冲区的 MemorySegment 视图
         */
        public MemorySegment colorBufferView() {
            return dataSegment.asSlice(OFFSET_COLOR_BUFFER, DEFAULT_FRAME_DATA_SIZE / 4);
        }

        /**
         * 获取深度缓冲区的 MemorySegment 视图
         */
        public MemorySegment depthBufferView() {
            return dataSegment.asSlice(OFFSET_DEPTH_BUFFER, DEFAULT_FRAME_DATA_SIZE / 4);
        }

        /**
         * 获取运动矢量缓冲区的 MemorySegment 视图
         */
        public MemorySegment motionVectorView() {
            return dataSegment.asSlice(OFFSET_MOTION_VECTORS, DEFAULT_FRAME_DATA_SIZE / 4);
        }

        /**
         * 获取相机数据的 MemorySegment 视图
         */
        public MemorySegment cameraDataView() {
            return dataSegment.asSlice(OFFSET_CAMERA_DATA, 4096);
        }
    }
}
