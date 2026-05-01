// Renderium - 零分配命令环形缓冲区
// 使用堆外内存存储命令数据，避免每帧产生大量短生命周期对象
// 符合"冷酷微内核"设计理念：零GC、纯二进制、紧凑数据

package com.renderium.graphics.command;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.logging.Logger;

/**
 * 零分配命令环形缓冲区（Off-Heap Ring Buffer）。
 *
 * <p>这是 {@link CommandBuffer} 的底层存储引擎，使用 Panama MemorySegment
 * 在堆外内存中预分配固定大小的命令槽位，完全消除录制过程中的对象分配。
 *
 * <h3>核心设计理念</h3>
 * <ul>
 *   <li><b>零 GC 压力</b>：所有命令数据存储在堆外 MemorySegment 中，
 *       录制过程不产生任何 Java 对象</li>
 *   <li><b>紧凑二进制格式</b>：每个命令打包为固定大小的记录，
 *       包含类型标识 + 参数载荷</li>
 *   <li><b>环形缓冲</b>：预分配固定容量，写入指针循环复用，
 *       无需动态扩容</li>
 *   <li><b>无锁读取</b>：读取端通过快照机制安全遍历，
 *       不需要读写锁同步</li>
 * </ul>
 *
 * <h3>命令记录布局（每个记录 64 字节）</h3>
 * <pre>{@code
 * ┌──────────┬──────────┬──────────────────────────────────────┐
 * │ 类型(1B) │ 标志(1B) │         载荷数据(62 bytes)           │
 * └──────────┴──────────┴──────────────────────────────────────┘
 *
 * 载据根据类型有不同的解释：
 * - DRAW:      pipelineHash(8) + mode(4) + count(4) + first(4) +
 *              indexType(4) + indexOffset(8) + instanceCount(4) + baseInstance(4) + padding
 * - COMPUTE:   pipelineHandle(8) + groupX(4) + groupY(4) + groupZ(4) + descriptorSet(8) + padding
 * - COPY:      copyType(4) + srcHandle(8) + dstHandle(8) + srcOffset(8) + dstOffset(8) + size(8) + padding
 * - CLEAR:     mask(4) + r(4) + g(4) + b(4) + a(4) + depth(4) + stencil(4) + padding
 * }</pre>
 *
 * @see CommandBuffer
 */
public final class CommandRingBuffer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(CommandRingBuffer.class.getName());

    /** Cleaner 实例（用于资源释放安全网） */
    private static final Cleaner CLEANER = Cleaner.create();

    /** 资源清理动作类 */
    private static class CleanupAction implements Runnable {
        private final Arena arena;
        private final String bufferInfo;

        CleanupAction(Arena arena, String bufferInfo) {
            this.arena = arena;
            this.bufferInfo = bufferInfo;
        }

        @Override
        public void run() {
            // Cleaner 线程中执行，仅作为安全网
            if (arena != null && arena.scope().isAlive()) {
                arena.close();
                LOGGER.warning("CommandRingBuffer cleaned up by Cleaner (possible resource leak): " + bufferInfo);
            }
        }
    }

    // ==================== 常量定义 ====================

    /** 每个命令记录的字节数（64字节，缓存行对齐） */
    public static final int RECORD_SIZE = 64;

    /** 默认容量（可存储的命令数量） */
    public static final int DEFAULT_CAPACITY = 4096;

    /** 最大容量限制 */
    public static final int MAX_CAPACITY = 65536;

    // ==================== 命令类型常量 ====================

    /** 命令类型：绘制 */
    public static final byte TYPE_DRAW = 0x01;

    /** 命令类型：计算 */
    public static final byte TYPE_COMPUTE = 0x02;

    /** 命令类型：拷贝 */
    public static final byte TYPE_COPY = 0x03;

    /** 命令类型：清除 */
    public static final byte TYPE_CLEAR = 0x04;

    /** 空槽位标记 */
    public static final byte TYPE_EMPTY = 0x00;

    // ==================== 记录内偏移量 ====================

    /** 类型字段偏移 */
    private static final int OFFSET_TYPE = 0;

    /** 标志字段偏移 */
    private static final int OFFSET_FLAGS = 1;

    /** 载荷起始偏移 */
    private static final int OFFSET_PAYLOAD = 2;

    // DRAW 命令载荷偏移
    private static final int DRAW_PIPELINE_HASH = OFFSET_PAYLOAD;
    private static final int DRAW_MODE = OFFSET_PAYLOAD + 8;
    private static final int DRAW_COUNT = DRAW_MODE + 4;
    private static final int DRAW_FIRST = DRAW_COUNT + 4;
    private static final int DRAW_INDEX_TYPE = DRAW_FIRST + 4;
    private static final int DRAW_INDEX_OFFSET = DRAW_INDEX_TYPE + 4;
    private static final int DRAW_INSTANCE_COUNT = DRAW_INDEX_OFFSET + 8;
    private static final int DRAW_BASE_INSTANCE = DRAW_INSTANCE_COUNT + 4;

    // COMPUTE 命令载荷偏移
    private static final int COMPUTE_PIPELINE_HANDLE = OFFSET_PAYLOAD;
    private static final int COMPUTE_GROUP_X = OFFSET_PAYLOAD + 8;
    private static final int COMPUTE_GROUP_Y = COMPUTE_GROUP_X + 4;
    private static final int COMPUTE_GROUP_Z = COMPUTE_GROUP_Y + 4;
    private static final int COMPUTE_DESCRIPTOR_SET = COMPUTE_GROUP_Z + 4;

    // COPY 命令载荷偏移
    private static final int COPY_TYPE = OFFSET_PAYLOAD;
    private static final int COPY_SRC_HANDLE = OFFSET_PAYLOAD + 4;
    private static final int COPY_DST_HANDLE = COPY_SRC_HANDLE + 8;
    private static final int COPY_SRC_OFFSET = COPY_DST_HANDLE + 8;
    private static final int COPY_DST_OFFSET = COPY_SRC_OFFSET + 8;
    private static final int COPY_SIZE = COPY_DST_OFFSET + 8;

    // CLEAR 命令载荷偏移
    private static final int CLEAR_MASK = OFFSET_PAYLOAD;
    private static final int CLEAR_R = OFFSET_PAYLOAD + 4;
    private static final int CLEAR_G = CLEAR_R + 4;
    private static final int CLEAR_B = CLEAR_G + 4;
    private static final int CLEAR_A = CLEAR_B + 4;
    private static final int CLEAR_DEPTH = CLEAR_A + 4;
    private static final int CLEAR_STENCIL = CLEAR_DEPTH + 4;

    // ==================== 实例字段 ====================

    /** 堆外内存 Arena */
    private final Arena arena;

    /** 环形缓冲区内存段 */
    private final MemorySegment buffer;

    /** 缓冲区容量（命令数量） */
    private final int capacity;

    /** Cleaner 注册对象（用于 GC 时的安全网清理） */
    private final Cleaner.Cleanable cleanable;

    /** 是否已关闭（防止重复关闭和访问已释放资源） */
    private volatile boolean closed;

    /** 写入位置（下一个要写入的槽位索引） */
    private volatile int writePos;

    /** 当前已提交的命令数量（用于读取边界） */
    private volatile int committedCount;

    /** 录制开始时的写位置（用于回滚） */
    private int recordingStartPos;

    /** 本轮录制的命令数 */
    private int recordingCount;

    /** 是否正在录制 */
    private volatile boolean recording;

    /** 统计：总写入次数 */
    private long totalWrites;

    /** 统计：总回绕次数 */
    private long totalWraps;

    // ==================== 构造函数 ====================

    /**
     * 创建默认容量的环形缓冲区
     */
    public CommandRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 创建指定容量的环形缓冲区
     *
     * @param capacity 最大命令数量（必须是 2 的幂次方以优化取模运算）
     */
    public CommandRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        }
        if (capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity exceeds max: " + capacity);
        }

        // 向上取整到最近的 2 的幂次方（优化取模为位与操作）
        this.capacity = roundUpToPowerOfTwo(capacity);

        // 分配堆外内存：capacity * RECORD_SIZE 字节
        this.arena = Arena.ofConfined();
        long totalBytes = (long) this.capacity * RECORD_SIZE;
        this.buffer = arena.allocate(totalBytes);

        // 初始化所有槽位为空
        this.buffer.fill((byte) 0);

        this.writePos = 0;
        this.committedCount = 0;
        this.recordingStartPos = 0;
        this.recordingCount = 0;
        this.recording = false;
        this.closed = false;

        // 注册 Cleaner 安全网（防止用户忘记调用 close）
        String info = String.format("capacity=%d, totalBytes=%d", this.capacity, totalBytes);
        this.cleanable = CLEANER.register(this, new CleanupAction(this.arena, info));

        LOGGER.fine(String.format(
            "CommandRingBuffer created: capacity=%d, bufferSize=%d bytes (%.1f KB)",
            this.capacity, totalBytes, totalBytes / 1024.0
        ));
    }

    // ==================== 录制生命周期 ====================

    /**
     * 检查是否已关闭
     *
     * @return true 如果资源已释放
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 确保缓冲区未关闭
     *
     * @throws IllegalStateException 如果已关闭
     */
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("CommandRingBuffer is already closed");
        }
    }

    /**
     * 开始录制命令
     *
     * <p>记录当前写入位置，后续 reset() 可回滚到此状态。
     */
    public void beginRecording() {
        ensureNotClosed();
        if (recording) {
            throw new IllegalStateException("Already recording");
        }
        this.recordingStartPos = writePos;
        this.recordingCount = 0;
        this.recording = true;
    }

    /**
     * 结束录制并提交命令
     *
     * @return 本次录制的命令数量
     */
    public int endRecording() {
        ensureNotClosed();
        if (!recording) {
            throw new IllegalStateException("Not recording");
        }
        this.recording = false;
        this.committedCount += this.recordingCount;
        return this.recordingCount;
    }

    /**
     * 回滚到录制开始状态（丢弃本次录制）
     */
    public void rollbackRecording() {
        ensureNotClosed();
        if (!recording) {
            throw new IllegalStateException("Not recording");
        }
        this.writePos = this.recordingStartPos;
        this.recordingCount = 0;
        this.recording = false;
    }

    /**
     * 重置整个缓冲区（清空所有已提交的命令）
     */
    public void reset() {
        ensureNotClosed();
        this.writePos = 0;
        this.committedCount = 0;
        this.recordingStartPos = 0;
        this.recordingCount = 0;
        this.recording = false;
        this.buffer.fill((byte) 0);
    }

    /**
     * 是否正在录制
     */
    public boolean isRecording() {
        ensureNotClosed();
        return recording;
    }

    // ==================== 命令录制方法（零分配）====================

    /**
     * 录制 Draw 命令（DrawArrays 模式）
     *
     * <p>将 DrawCommand 数据直接写入堆外内存，不创建任何 Java 对象。
     *
     * @param pipelineHash RenderPipeline 的 hashCode（用于管线查找）
     * @param drawMode     绘制模式 (GL_TRIANGLES=4, GL_LINES=1 等)
     * @param firstVertex  起始顶点索引
     * @param vertexCount  顶点数量
     * @throws IllegalStateException 如果不在录制状态或缓冲区已满
     */
    public void recordDraw(int pipelineHash, int drawMode, int firstVertex, int vertexCount) {
        ensureNotClosed();
        ensureRecording();
        ensureCapacity();

        int pos = writePos;
        MemorySegment record = getRecord(pos);

        // 写入类型和标志
        record.set(ValueLayout.JAVA_BYTE, OFFSET_TYPE, TYPE_DRAW);
        record.set(ValueLayout.JAVA_BYTE, OFFSET_FLAGS, (byte) 0x00); // 非索引模式

        // 写入 DRAW 载荷
        record.set(ValueLayout.JAVA_INT, DRAW_PIPELINE_HASH, pipelineHash);
        record.set(ValueLayout.JAVA_INT, DRAW_MODE, drawMode);
        record.set(ValueLayout.JAVA_INT, DRAW_COUNT, vertexCount);
        record.set(ValueLayout.JAVA_INT, DRAW_FIRST, firstVertex);
        record.set(ValueLayout.JAVA_INT, DRAW_INDEX_TYPE, 0);          // 无索引
        record.set(ValueLayout.JAVA_LONG, DRAW_INDEX_OFFSET, 0L);
        record.set(ValueLayout.JAVA_INT, DRAW_INSTANCE_COUNT, 1);       // 非实例化
        record.set(ValueLayout.JAVA_INT, DRAW_BASE_INSTANCE, 0);

        advanceWrite();
    }

    /**
     * 录制 Draw 命令（DrawElements 模式）
     *
     * @param pipelineHash      RenderPipeline 的 hashCode
     * @param drawMode          绘制模式
     * @param indexCount        索引数量
     * @param indexType         索引类型 (1=BYTE, 2=SHORT, 3=INT)
     * @param indexBufferOffset 索引缓冲区字节偏移
     */
    public void recordDrawIndexed(int pipelineHash, int drawMode,
                                    int indexCount, int indexType,
                                    long indexBufferOffset) {
        ensureNotClosed();
        ensureRecording();
        ensureCapacity();

        int pos = writePos;
        MemorySegment record = getRecord(pos);

        record.set(ValueLayout.JAVA_BYTE, OFFSET_TYPE, TYPE_DRAW);
        record.set(ValueLayout.JAVA_BYTE, OFFSET_FLAGS, (byte) 0x01); // 索引模式标志

        record.set(ValueLayout.JAVA_INT, DRAW_PIPELINE_HASH, pipelineHash);
        record.set(ValueLayout.JAVA_INT, DRAW_MODE, drawMode);
        record.set(ValueLayout.JAVA_INT, DRAW_COUNT, indexCount);
        record.set(ValueLayout.JAVA_INT, DRAW_FIRST, 0);             // DrawElements 无 firstVertex
        record.set(ValueLayout.JAVA_INT, DRAW_INDEX_TYPE, indexType);
        record.set(ValueLayout.JAVA_LONG, DRAW_INDEX_OFFSET, indexBufferOffset);
        record.set(ValueLayout.JAVA_INT, DRAW_INSTANCE_COUNT, 1);
        record.set(ValueLayout.JAVA_INT, DRAW_BASE_INSTANCE, 0);

        advanceWrite();
    }

    /**
     * 录制 Compute 命令
     *
     * @param pipelineHandle 计算着色器 Pipeline handle
     * @param groupCountX X 维度工作组数量
     * @param groupCountY Y 维度工作组数量
     * @param groupCountZ Z 维度工作组数量
     * @param descriptorSet 描述符集 handle
     */
    public void recordCompute(long pipelineHandle, int groupCountX,
                                int groupCountY, int groupCountZ,
                                long descriptorSet) {
        ensureNotClosed();
        ensureRecording();
        ensureCapacity();

        int pos = writePos;
        MemorySegment record = getRecord(pos);

        record.set(ValueLayout.JAVA_BYTE, OFFSET_TYPE, TYPE_COMPUTE);
        record.set(ValueLayout.JAVA_BYTE, OFFSET_FLAGS, (byte) 0x00);

        record.set(ValueLayout.JAVA_LONG, COMPUTE_PIPELINE_HANDLE, pipelineHandle);
        record.set(ValueLayout.JAVA_INT, COMPUTE_GROUP_X, groupCountX);
        record.set(ValueLayout.JAVA_INT, COMPUTE_GROUP_Y, groupCountY);
        record.set(ValueLayout.JAVA_INT, COMPUTE_GROUP_Z, groupCountZ);
        record.set(ValueLayout.JAVA_LONG, COMPUTE_DESCRIPTOR_SET, descriptorSet);

        advanceWrite();
    }

    /**
     * 录制 Copy 命令
     *
     * @param copyType 拷贝类型 (0=BUFFER_TO_BUFFER, 1=BUFFER_TO_IMAGE, etc.)
     * @param srcHandle 源资源 handle
     * @param dstHandle 目标资源 handle
     * @param srcOffset 源偏移量（字节）
     * @param dstOffset 目标偏移量（字节）
     * @param size 拷贝大小（字节）
     */
    public void recordCopy(int copyType, long srcHandle, long dstHandle,
                            long srcOffset, long dstOffset, long size) {
        ensureNotClosed();
        ensureRecording();
        ensureCapacity();

        int pos = writePos;
        MemorySegment record = getRecord(pos);

        record.set(ValueLayout.JAVA_BYTE, OFFSET_TYPE, TYPE_COPY);
        record.set(ValueLayout.JAVA_BYTE, OFFSET_FLAGS, (byte) copyType);

        record.set(ValueLayout.JAVA_LONG, COPY_SRC_HANDLE, srcHandle);
        record.set(ValueLayout.JAVA_LONG, COPY_DST_HANDLE, dstHandle);
        record.set(ValueLayout.JAVA_LONG, COPY_SRC_OFFSET, srcOffset);
        record.set(ValueLayout.JAVA_LONG, COPY_DST_OFFSET, dstOffset);
        record.set(ValueLayout.JAVA_LONG, COPY_SIZE, size);

        advanceWrite();
    }

    /**
     * 录制 Clear 命令
     *
     * @param clearMask 清除掩码 (COLOR=1, DEPTH=2, STENCIL=4)
     * @param r 清除颜色 R
     * @param g 清除颜色 G
     * @param b 清除颜色 B
     * @param a 清除颜色 A
     * @param depth 清除深度值
     * @param stencil 清除模板值
     */
    public void recordClear(int clearMask, float r, float g, float b, float a,
                              float depth, int stencil) {
        ensureNotClosed();
        ensureRecording();
        ensureCapacity();

        int pos = writePos;
        MemorySegment record = getRecord(pos);

        record.set(ValueLayout.JAVA_BYTE, OFFSET_TYPE, TYPE_CLEAR);
        record.set(ValueLayout.JAVA_BYTE, OFFSET_FLAGS, (byte) 0x00);

        record.set(ValueLayout.JAVA_INT, CLEAR_MASK, clearMask);
        record.set(ValueLayout.JAVA_FLOAT, CLEAR_R, r);
        record.set(ValueLayout.JAVA_FLOAT, CLEAR_G, g);
        record.set(ValueLayout.JAVA_FLOAT, CLEAR_B, b);
        record.set(ValueLayout.JAVA_FLOAT, CLEAR_A, a);
        record.set(ValueLayout.JAVA_FLOAT, CLEAR_DEPTH, depth);
        record.set(ValueLayout.JAVA_INT, CLEAR_STENCIL, stencil);

        advanceWrite();
    }

    // ==================== 读取/迭代接口 ====================

    /**
     * 获取已提交的命令总数
     */
    public int getCommittedCount() {
        ensureNotClosed();
        return committedCount;
    }

    /**
     * 获取当前缓冲区中的命令总数（包括未提交的）
     */
    public int getTotalRecordedCount() {
        ensureNotClosed();
        return committedCount + recordingCount;
    }

    /**
     * 缓冲区是否为空
     */
    public boolean isEmpty() {
        if (closed) {
            return true;
        }
        return committedCount == 0 && !recording;
    }

    /**
     * 获取原始内存段（供高性能消费者直接访问）
     *
     * <p>返回底层 MemorySegment，高级用户可直接按 RECORD_SIZE 步进遍历。
     * 注意：此操作不安全，调用者需自行处理并发。
     *
     * @return 底层堆外内存段
     */
    public MemorySegment getRawBuffer() {
        ensureNotClosed();
        return buffer;
    }

    /**
     * 获取指定位置的命令记录
     *
     * @param index 命令索引（0 到 committedCount-1）
     * @return 该索引处的原始记录内存段（RECORD_SIZE 字节）
     */
    public MemorySegment getRecord(int index) {
        ensureNotClosed();
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException("index=" + index + ", capacity=" + capacity);
        }
        long offset = ((long) index & (capacity - 1)) * RECORD_SIZE; // 位与代替取模，加括号修正优先级
        return buffer.asSlice(offset, RECORD_SIZE);
    }

    /**
     * 获取指定位置的命令类型
     *
     * @param index 命令索引
     * @return 命令类型字节，TYPE_EMPTY 表示空槽位
     */
    public byte getRecordType(int index) {
        ensureNotClosed();
        return getRecord(index).get(ValueLayout.JAVA_BYTE, OFFSET_TYPE);
    }

    // ==================== 命令读取辅助方法 ====================

    /**
     * 从记录中读取 Draw 命令参数（零分配版本）
     *
     * <p>将二进制数据解析到预分配数组中，避免每次调用产生 {@code new long[8]} 分配。
     * 适用于高频调用路径（如每帧遍历命令缓冲区）。
     *
     * <h3>输出数组布局</h3>
     * <pre>
     * output[0] = pipelineHash (unsigned int → long)
     * output[1] = mode         (unsigned int → long)
     * output[2] = count        (unsigned int → long)
     * output[3] = firstVertex  (unsigned int → long)
     * output[4] = indexType    (unsigned int → long)
     * output[5] = indexOffset  (long)
     * output[6] = instanceCount(unsigned int → long)
     * output[7] = baseInstance  (unsigned int → long)
     * </pre>
     *
     * @param record 命令记录内存段
     * @param output 预分配数组，长度必须 >= 8
     * @throws IllegalArgumentException 如果 output 为 null 或长度 < 8
     */
    public static void parseDrawParams(MemorySegment record, long[] output) {
        if (output == null || output.length < 8) {
            throw new IllegalArgumentException("output array must be at least 8 elements, got: "
                + (output == null ? "null" : output.length));
        }
        output[0] = record.get(ValueLayout.JAVA_INT, DRAW_PIPELINE_HASH) & 0xFFFFFFFFL;
        output[1] = record.get(ValueLayout.JAVA_INT, DRAW_MODE) & 0xFFFFFFFFL;
        output[2] = record.get(ValueLayout.JAVA_INT, DRAW_COUNT) & 0xFFFFFFFFL;
        output[3] = record.get(ValueLayout.JAVA_INT, DRAW_FIRST) & 0xFFFFFFFFL;
        output[4] = record.get(ValueLayout.JAVA_INT, DRAW_INDEX_TYPE) & 0xFFFFFFFFL;
        output[5] = record.get(ValueLayout.JAVA_LONG, DRAW_INDEX_OFFSET);
        output[6] = record.get(ValueLayout.JAVA_INT, DRAW_INSTANCE_COUNT) & 0xFFFFFFFFL;
        output[7] = record.get(ValueLayout.JAVA_INT, DRAW_BASE_INSTANCE) & 0xFFFFFFFFL;
    }

    /**
     * 从记录中读取 Draw 命令参数
     *
     * <p>将二进制数据解析为参数数组，避免创建 DrawCommand 对象。
     * 返回数组: [pipelineHash, mode, count, first, indexType, indexOffset, instanceCount, baseInstance]
     *
     * @param record 命令记录内存段
     * @return 解析后的参数数组（长度 8）
     * @deprecated 使用 {@link #parseDrawParams(MemorySegment, long[])} 避免每次数组分配
     */
    @Deprecated
    public static long[] parseDrawParams(MemorySegment record) {
        long[] params = new long[8];
        parseDrawParams(record, params);
        return params;
    }

    /**
     * 从记录中读取 Compute 命令参数（零分配版本）
     *
     * <p>将二进制数据解析到预分配数组中，避免每次调用产生 {@code new long[5]} 分配。
     *
     * <h3>输出数组布局</h3>
     * <pre>
     * output[0] = pipelineHandle (long)
     * output[1] = groupX         (unsigned int → long)
     * output[2] = groupY         (unsigned int → long)
     * output[3] = groupZ         (unsigned int → long)
     * output[4] = descriptorSet  (long)
     * </pre>
     *
     * @param record 命令记录内存段
     * @param output 预分配数组，长度必须 >= 5
     * @throws IllegalArgumentException 如果 output 为 null 或长度 < 5
     */
    public static void parseComputeParams(MemorySegment record, long[] output) {
        if (output == null || output.length < 5) {
            throw new IllegalArgumentException("output array must be at least 5 elements, got: "
                + (output == null ? "null" : output.length));
        }
        output[0] = record.get(ValueLayout.JAVA_LONG, COMPUTE_PIPELINE_HANDLE);
        output[1] = record.get(ValueLayout.JAVA_INT, COMPUTE_GROUP_X) & 0xFFFFFFFFL;
        output[2] = record.get(ValueLayout.JAVA_INT, COMPUTE_GROUP_Y) & 0xFFFFFFFFL;
        output[3] = record.get(ValueLayout.JAVA_INT, COMPUTE_GROUP_Z) & 0xFFFFFFFFL;
        output[4] = record.get(ValueLayout.JAVA_LONG, COMPUTE_DESCRIPTOR_SET);
    }

    /**
     * 从记录中读取 Compute 命令参数
     *
     * 返回数组: [pipelineHandle, groupX, groupY, groupZ, descriptorSet]
     *
     * @deprecated 使用 {@link #parseComputeParams(MemorySegment, long[])} 避免每次数组分配
     */
    @Deprecated
    public static long[] parseComputeParams(MemorySegment record) {
        long[] params = new long[5];
        parseComputeParams(record, params);
        return params;
    }

    /**
     * 从记录中读取 Clear 命令参数（零分配版本）
     *
     * <p>将二进制数据解析到预分配 long 数组中，避免每次调用产生 {@code new Object[7]} 分配。
     * float 值通过 {@link Float#floatToRawIntBits(float)} 编码为 long 存储，
     * 使用 {@link #decodeFloat(long)} 和 {@link #decodeInt(long)} 解码。
     *
     * <h3>输出数组布局</h3>
     * <pre>
     * output[0] = mask    (unsigned int → long, 使用 decodeInt() 解码)
     * output[1] = r       (float → int bits → long, 使用 decodeFloat() 解码)
     * output[2] = g       (float → int bits → long, 使用 decodeFloat() 解码)
     * output[3] = b       (float → int bits → long, 使用 decodeFloat() 解码)
     * output[4] = a       (float → int bits → long, 使用 decodeFloat() 解码)
     * output[5] = depth   (float → int bits → long, 使用 decodeFloat() 解码)
     * output[6] = stencil (unsigned int → long, 使用 decodeInt() 解码)
     * </pre>
     *
     * @param record 命令记录内存段
     * @param output 预分配数组，长度必须 >= 7
     * @throws IllegalArgumentException 如果 output 为 null 或长度 < 7
     */
    public static void parseClearParams(MemorySegment record, long[] output) {
        if (output == null || output.length < 7) {
            throw new IllegalArgumentException("output array must be at least 7 elements, got: "
                + (output == null ? "null" : output.length));
        }
        output[0] = record.get(ValueLayout.JAVA_INT, CLEAR_MASK) & 0xFFFFFFFFL;
        output[1] = Float.floatToRawIntBits(record.get(ValueLayout.JAVA_FLOAT, CLEAR_R)) & 0xFFFFFFFFL;
        output[2] = Float.floatToRawIntBits(record.get(ValueLayout.JAVA_FLOAT, CLEAR_G)) & 0xFFFFFFFFL;
        output[3] = Float.floatToRawIntBits(record.get(ValueLayout.JAVA_FLOAT, CLEAR_B)) & 0xFFFFFFFFL;
        output[4] = Float.floatToRawIntBits(record.get(ValueLayout.JAVA_FLOAT, CLEAR_A)) & 0xFFFFFFFFL;
        output[5] = Float.floatToRawIntBits(record.get(ValueLayout.JAVA_FLOAT, CLEAR_DEPTH)) & 0xFFFFFFFFL;
        output[6] = record.get(ValueLayout.JAVA_INT, CLEAR_STENCIL) & 0xFFFFFFFFL;
    }

    /**
     * 从记录中读取 Clear 命令参数
     *
     * 返回 Object 数组: [mask(int), r(float), g(float), b(float), a(float), depth(float), stencil(int)]
     *
     * @deprecated 使用 {@link #parseClearParams(MemorySegment, long[])} 避免每次数组分配。
     *             float 值编码为 long，使用 {@link #decodeFloat(long)} 解码。
     */
    @Deprecated
    public static Object[] parseClearParams(MemorySegment record) {
        return new Object[]{
            record.get(ValueLayout.JAVA_INT, CLEAR_MASK),
            record.get(ValueLayout.JAVA_FLOAT, CLEAR_R),
            record.get(ValueLayout.JAVA_FLOAT, CLEAR_G),
            record.get(ValueLayout.JAVA_FLOAT, CLEAR_B),
            record.get(ValueLayout.JAVA_FLOAT, CLEAR_A),
            record.get(ValueLayout.JAVA_FLOAT, CLEAR_DEPTH),
            record.get(ValueLayout.JAVA_INT, CLEAR_STENCIL)
        };
    }

    /**
     * 将 long 解码为 int（用于 parseClearParams 零分配版本的 int 字段）
     *
     * @param encoded 编码后的 long 值
     * @return 解码后的 int 值
     */
    public static int decodeInt(long encoded) {
        return (int) encoded;
    }

    /**
     * 将 long 解码为 float（用于 parseClearParams 零分配版本的 float 字段）
     *
     * @param encoded 通过 {@link Float#floatToRawIntBits(float)} 编码的 long 值
     * @return 解码后的 float 值
     */
    public static float decodeFloat(long encoded) {
        return Float.intBitsToFloat((int) encoded);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 推进写入位置
     */
    private void advanceWrite() {
        writePos = (writePos + 1) & (capacity - 1);
        recordingCount++;
        totalWrites++;

        if (writePos == 0) {
            totalWraps++;
        }
    }

    /**
     * 确保正在录制
     */
    private void ensureRecording() {
        if (!recording) {
            throw new IllegalStateException("Not in recording state - call beginRecording() first");
        }
    }

    /**
     * 确保有可用空间
     */
    private void ensureCapacity() {
        if (recordingCount >= capacity) {
            throw new IllegalStateException(
                "CommandRingBuffer full: recorded " + recordingCount + " of " + capacity
            );
        }
    }

    /**
     * 向上取整到 2 的幂次方
     */
    private static int roundUpToPowerOfTwo(int value) {
        if (value <= 0) return 1;
        if ((value & (value - 1)) == 0) return value; // 已经是 2 的幂
        int highestBit = Integer.highestOneBit(value);
        return highestBit << 1;
    }

    // ==================== 诊断信息 ====================

    /**
     * 获取缓冲区容量
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 获取当前写入位置
     */
    public int getWritePosition() {
        ensureNotClosed();
        return writePos;
    }

    /**
     * 获取统计信息字符串
     */
    public String getStatistics() {
        ensureNotClosed();
        return String.format(
            "CommandRingBuffer{capacity=%d, committed=%d, recording=%d, " +
            "writes=%d, wraps=%d, utilization=%.1f%%}",
            capacity, committedCount, recordingCount,
            totalWrites, totalWraps,
            (double) getTotalRecordedCount() / capacity * 100.0
        );
    }

    @Override
    public String toString() {
        if (closed) {
            return "CommandRingBuffer[closed]";
        }
        return String.format(
            "CommandRingBuffer[%d/%d commands]",
            getTotalRecordedCount(), capacity
        );
    }

    // ==================== AutoCloseable ====================

    /**
     * 关闭并释放堆外内存
     *
     * <p>此方法会：
     * <ol>
     *   <li>检查是否已关闭，防止重复释放</li>
     *   <li>关闭 Arena，释放堆外内存</li>
     *   <li>标记 closed 状态</li>
     *   <li>记录资源释放日志</li>
     * </ol>
     *
     * <p>支持 try-with-resources 语法：
     * <pre>{@code
     * try (CommandRingBuffer buffer = new CommandRingBuffer(4096)) {
     *     // 使用 buffer
     * } // 自动释放
     * }</pre>
     */
    @Override
    public void close() {
        if (closed) {
            LOGGER.fine("CommandRingBuffer already closed, ignoring duplicate close call");
            return;
        }

        closed = true;

        // 取消 Cleaner 注册（已手动关闭，不需要安全网）
        cleanable.clean();

        try {
            if (arena.scope().isAlive()) {
                arena.close();
            }
        } catch (IllegalStateException e) {
            // Arena 可能已经被关闭（Cleaner 先执行），忽略
            LOGGER.fine("Arena was already cleaned up when close() was called");
        }

        LOGGER.info(String.format(
            "CommandRingBuffer released: capacity=%d, totalWrites=%d, wraps=%d",
            capacity, totalWrites, totalWraps
        ));
    }
}
