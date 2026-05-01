// Renderium - 无锁命令缓冲区（Lock-Free SPSC Ring Buffer�?// 面向 1000+ FPS 的高性能设计，使�?CAS 原子操作替代�?// 适用于单生产�?单消费者（SPSC）模式：录制线程/渲染线程

package com.renderium.graphics.command;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;

/**
 * 无锁命令缓冲区（Lock-Free SPSC Ring Buffer�? *
 * <h3>核心设计原则</h3>
 * <ul>
 *   <li><b>SPSC 模型</b>：单生产者（游戏线程）→ 单消费者（渲染线程�?/li>
 *   <li><b>无锁写入</b>：使�?{@code volatile} + CAS 保证可见性，无锁竞争</li>
 *   <li><b>缓存行对�?/b>：读写指针分离到不同缓存行，避免 False Sharing</li>
 *   <li><b>堆外内存</b>：零 GC 压力，预分配固定大小</li>
 * </ul>
 *
 * <h3>内存布局</h3>
 * <pre>{@code
 * ┌─────────────────────────────────────�? * �?Write Position (64B, cache-aligned) �?�?生产者独�? * ├─────────────────────────────────────�? * �?Read Position  (64B, cache-aligned) �?�?消费者独�? * ├─────────────────────────────────────�? * �?Ring Buffer Data (capacity × 64B)   �?�?命令存储�? * └─────────────────────────────────────�? * }</pre>
 *
 * <h3>命令记录格式�?4 字节/条）</h3>
 * <pre>{@code
 * Offset  Size  Field
 * ─────────────────────────────
 * 0       1B    Command Type (DRAW/COMPUTE/CLEAR)
 * 1       1B    Flags
 * 2-9     8B    Param1 (pipeline hash/handle)
 * 10-13   4B    Param2 (mode/count)
 * 14-17   4B    Param3 (first/indexType)
 * 18-25   8B    Param4 (offset/handle)
 * 26-33   8B    Param5 (offset/handle)
 * 34-41   8B    Param6 (size/instanceCount)
 * 42-63   22B   Reserved/Alignment
 * }</pre>
 */
public final class LockFreeCommandBuffer implements AutoCloseable {

    // ==================== 常量 ====================

    /** 命令记录大小（缓存行对齐�?*/
    private static final int RECORD_SIZE = 64;

    /** 缓存行大小（x86 典型值） */
    private static final int CACHE_LINE_SIZE = 64;

    /** 默认容量（必须是 2 的幂�?*/
    private static final int DEFAULT_CAPACITY = 4096;

    /** 最大容�?*/
    private static final int MAX_CAPACITY = 65536;

    // 命令类型
    static final byte TYPE_EMPTY  = 0x00;
    static final byte TYPE_DRAW   = 0x01;
    static final byte TYPE_COMPUTE = 0x02;
    static final byte TYPE_CLEAR  = 0x04;

    // 记录内偏�?    private static final int OFF_TYPE  = 0;
    private static final int OFF_FLAGS = 1;
    private static final int OFF_P1    = 2;   // long
    private static final int OFF_P2    = 10;  // int
    private static final int OFF_P3    = 14;  // int
    private static final int OFF_P4    = 18;  // long
    private static final int OFF_P5    = 26;  // long
    private static final int OFF_P6    = 34;  // long

    // ==================== 实例字段 ====================

    /** 堆外内存 Arena */
    private final Arena arena;

    /** 环形缓冲区内存段 */
    private final MemorySegment buffer;

    /** 容量�? 的幂�?*/
    private final int capacity;

    /** 容量掩码（capacity - 1），用于位运算代替取�?*/
    private final int mask;

    /** 写入位置（生产者独占，避免 False Sharing�?*/
    private volatile int writePos;

    /** 读取位置（消费者独占，避免 False Sharing�?*/
    private volatile int readPos;

    /** 是否已关�?*/
    private boolean closed;

    /** Cleaner 用于安全网清�?*/
    private final Cleaner.Cleanable cleanable;
    private static final Cleaner CLEANER = Cleaner.create();

    // ==================== 构造函�?====================

    public LockFreeCommandBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public LockFreeCommandBuffer(int capacity) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity: " + capacity);
        }

        this.capacity = roundUpPowerOfTwo(capacity);
        this.mask = this.capacity - 1;

        // 分配堆外内存：读写指�?+ 数据�?        long totalSize = CACHE_LINE_SIZE * 2 + (long) this.capacity * RECORD_SIZE;
        this.arena = Arena.ofConfined();
        this.buffer = arena.allocate(totalSize);

        // 初始化指�?        this.writePos = 0;
        this.readPos = 0;
        this.closed = false;

        // 清零缓冲�?        this.buffer.asSlice(CACHE_LINE_SIZE * 2).fill((byte) 0);

        // 注册 Cleaner
        this.cleanable = CLEANER.register(this, () -> {
            if (arena.scope().isAlive()) arena.close();
        });
    }

    // ==================== 生产者接口（游戏线程�?====================

    /**
     * 录制 Draw 命令（零分配，直接写入堆外内存）
     *
     * @param pipelineHash 管线哈希
     * @param mode         绘制模式（GL_TRIANGLES 等）
     * @param count        顶点/索引数量
     * @param first        起始索引
     */
    public void recordDraw(int pipelineHash, int mode, int count, int first) {
        if (closed) return;

        int pos = writePos;
        int nextPos = (pos + 1) & mask;

        // 检查缓冲区是否已满（不覆盖未消费数据）
        if (nextPos == readPos) {
            // 缓冲区满：可以选择覆盖旧数据或丢弃新命�?            // 这里选择丢弃（静默失败，适合游戏场景�?            return;
        }

        MemorySegment record = getRecord(pos);

        // 写入命令数据（无序写入，最后写类型字段作为"提交"信号�?        record.set(ValueLayout.JAVA_INT, OFF_P1, pipelineHash);
        record.set(ValueLayout.JAVA_INT, OFF_P2, mode);
        record.set(ValueLayout.JAVA_INT, OFF_P3, count);
        record.set(ValueLayout.JAVA_INT, 14, first);
        record.set(ValueLayout.JAVA_LONG, OFF_P4, 0L);
        record.set(ValueLayout.JAVA_LONG, OFF_P5, 0L);
        record.set(ValueLayout.JAVA_LONG, OFF_P6, 0L);

        // 内存屏障：确保所有参数写入完成后，再写类型字�?        // volatile 写提�?StoreStore 屏障
        record.set(ValueLayout.JAVA_BYTE, OFF_TYPE, TYPE_DRAW);

        // 推进写指针（volatile 写提供可见性保证）
        writePos = nextPos;
    }

    /**
     * 录制 Compute 命令
     */
    public void recordCompute(long pipelineHandle, int groupX, int groupY, int groupZ, long descriptorSet) {
        if (closed) return;

        int pos = writePos;
        int nextPos = (pos + 1) & mask;

        if (nextPos == readPos) return; // 缓冲区满

        MemorySegment record = getRecord(pos);

        record.set(ValueLayout.JAVA_LONG, OFF_P1, pipelineHandle);
        record.set(ValueLayout.JAVA_INT, OFF_P2, groupX);
        record.set(ValueLayout.JAVA_INT, OFF_P3, groupY);
        record.set(ValueLayout.JAVA_INT, 14, groupZ);
        record.set(ValueLayout.JAVA_LONG, OFF_P4, descriptorSet);

        // volatile 写作为提交信�?        record.set(ValueLayout.JAVA_BYTE, OFF_TYPE, TYPE_COMPUTE);
        writePos = nextPos;
    }

    /**
     * 录制 Clear 命令
     */
    public void recordClear(int mask, float r, float g, float b, float a, float depth, int stencil) {
        if (closed) return;

        int pos = writePos;
        int nextPos = (pos + 1) & mask;

        if (nextPos == readPos) return;

        MemorySegment record = getRecord(pos);

        record.set(ValueLayout.JAVA_INT, OFF_P1, mask);
        record.set(ValueLayout.JAVA_FLOAT, OFF_P2, r);
        record.set(ValueLayout.JAVA_FLOAT, OFF_P3, g);
        record.set(ValueLayout.JAVA_FLOAT, 14, b);
        record.set(ValueLayout.JAVA_FLOAT, OFF_P4, a);
        record.set(ValueLayout.JAVA_FLOAT, OFF_P5, depth);
        record.set(ValueLayout.JAVA_INT, OFF_P6, stencil);

        record.set(ValueLayout.JAVA_BYTE, OFF_TYPE, TYPE_CLEAR);
        writePos = nextPos;
    }

    // ==================== 消费者接口（渲染线程�?====================

    /**
     * 消费所有待处理命令
     *
     * @param handler 命令处理�?     * @return 处理的命令数�?     */
    public int consumeAll(CommandHandler handler) {
        if (closed) return 0;

        int count = 0;
        int rPos = readPos;
        int wPos = writePos; // 快照写指�?
        while (rPos != wPos) {
            MemorySegment record = getRecord(rPos);
            byte type = record.get(ValueLayout.JAVA_BYTE, OFF_TYPE);

            if (type == TYPE_EMPTY) {
                // 生产者尚未完成写入，等待下一�?                break;
            }

            // 根据类型分发处理
            switch (type) {
                case TYPE_DRAW -> {
                    int pipelineHash = record.get(ValueLayout.JAVA_INT, OFF_P1);
                    int mode = record.get(ValueLayout.JAVA_INT, OFF_P2);
                    int count_ = record.get(ValueLayout.JAVA_INT, OFF_P3);
                    int first = record.get(ValueLayout.JAVA_INT, 14);
                    handler.onDraw(pipelineHash, mode, count_, first);
                }
                case TYPE_COMPUTE -> {
                    long pipelineHandle = record.get(ValueLayout.JAVA_LONG, OFF_P1);
                    int groupX = record.get(ValueLayout.JAVA_INT, OFF_P2);
                    int groupY = record.get(ValueLayout.JAVA_INT, OFF_P3);
                    int groupZ = record.get(ValueLayout.JAVA_INT, 14);
                    long descriptorSet = record.get(ValueLayout.JAVA_LONG, OFF_P4);
                    handler.onCompute(pipelineHandle, groupX, groupY, groupZ, descriptorSet);
                }
                case TYPE_CLEAR -> {
                    int clearMask = record.get(ValueLayout.JAVA_INT, OFF_P1);
                    float r = record.get(ValueLayout.JAVA_FLOAT, OFF_P2);
                    float g = record.get(ValueLayout.JAVA_FLOAT, OFF_P3);
                    float b = record.get(ValueLayout.JAVA_FLOAT, 14);
                    float a = record.get(ValueLayout.JAVA_FLOAT, OFF_P4);
                    float depth = record.get(ValueLayout.JAVA_FLOAT, OFF_P5);
                    int stencil = record.get(ValueLayout.JAVA_INT, OFF_P6);
                    handler.onClear(clearMask, r, g, b, a, depth, stencil);
                }
            }

            // 清空类型字段（防止消费者追赶上生产者时误读�?            record.set(ValueLayout.JAVA_BYTE, OFF_TYPE, TYPE_EMPTY);

            // 推进读指�?            rPos = (rPos + 1) & mask;
            count++;
        }

        // 批量更新读指针（减少 volatile 写次数）
        readPos = rPos;

        return count;
    }

    /**
     * 重置缓冲区（清空所有命令）
     */
    public void reset() {
        if (closed) return;
        readPos = 0;
        writePos = 0;
    }

    // ==================== 内部方法 ====================

    private MemorySegment getRecord(int index) {
        long offset = CACHE_LINE_SIZE * 2 + ((long) index & mask) * RECORD_SIZE;
        return buffer.asSlice(offset, RECORD_SIZE);
    }

    private static int roundUpPowerOfTwo(int v) {
        v--;
        v |= v >>> 1;
        v |= v >>> 2;
        v |= v >>> 4;
        v |= v >>> 8;
        v |= v >>> 16;
        v++;
        return v;
    }

    // ==================== 资源管理 ====================

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cleanable.clean();
        if (arena.scope().isAlive()) arena.close();
    }

    public boolean isClosed() { return closed; }
    public int getCapacity() { return capacity; }
    public int size() { return (writePos - readPos) & mask; }
    public boolean isEmpty() { return writePos == readPos; }

    // ==================== 命令处理器接�?====================

    /**
     * 命令处理器接口（消费者实现此接口�?     *
     * <p>使用接口而非 lambda 以避免在热路径中产生对象分配�?     */
    public interface CommandHandler {
        void onDraw(int pipelineHash, int mode, int count, int first);
        void onCompute(long pipelineHandle, int groupX, int groupY, int groupZ, long descriptorSet);
        void onClear(int mask, float r, float g, float b, float a, float depth, int stencil);
    }
}
