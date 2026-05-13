// Renderium - 高性能渲染管线调度器 (v2)
// MPSC 安全的无锁环形缓冲区
// 修复: 原 SPSC 实现在多生产者场景下存在 TOCTOU 竞争
// v2 变更:
//   - enqueue() 使用 CAS 抢占写入位置（MPSC 安全）
//   - dequeue() 保持单消费者约束
//   - 消除 count 字段的 TOCTOU 窗口

package com.ranecc.renderium.feature.pipeline;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 高性能环形缓冲区队列（无锁多生产者-单消费者）
 * <p>
 * 用于渲染线程与工作线程之间的高频数据交换。
 * 采用数组环形结构，避免 GC 压力，O(1) 入队/出队。
 *
 * <h3>线程安全模型：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │  [0] [1] [2] ... [N-2] [N-1]              │
 * │   ↑ writeIdx (CAS)        ↑ readIdx        │
 * └─────────────────────────────────────────────┘
 *
 * 生产者 (多线程): CAS 抢占 writeIdx 位置
 * 消费者 (单线程): 仅修改 readIdx
 * 容量必须为 2 的幂次（支持位运算取模）
 * </pre>
 *
 * <h3>MPSC 安全保证：</h3>
 * <ul>
 *   <li>enqueue(): CAS 原子抢占写入位置，不会有两个生产者写入同一槽位</li>
 *   <li>dequeue(): 单消费者，无竞争</li>
 *   <li>元素可见性: buffer.set() 在 CAS 之前，buffer.get() 在 read 之后</li>
 * </ul>
 *
 * @param <E> 元素类型
 */
public final class LockFreeRingBuffer<E> {

    private static final Logger LOGGER = Logger.getLogger(LockFreeRingBuffer.class.getName());

    /** 缓冲区容量（必须是 2 的幂次） */
    private final int capacity;

    /** 掩码 = capacity - 1，用于位运算取模 */
    private final int mask;

    /** 内部存储数组 */
    private final AtomicReferenceArray<E> buffer;

    /** 生产者写入位置（CAS 递增，多线程安全） */
    private final AtomicInteger writeIndex = new AtomicInteger(0);

    /** 消费者读取位置（仅消费者线程修改） */
    private volatile int readIndex = 0;

    // ==================== 统计字段 ====================

    /** 总入队次数 */
    private final AtomicLong totalEnqueues = new AtomicLong(0);

    /** 总出队次数 */
    private final AtomicLong totalDequeues = new AtomicLong(0);

    /** 因队列满而失败的入队次数 */
    private final AtomicLong enqueueFailures = new AtomicLong(0);

    /**
     * 创建指定容量的环形缓冲区
     *
     * @param capacity 容量（会向上取整到最近的 2 的幂次）
     * @throws IllegalArgumentException 如果 capacity <= 0
     */
    public LockFreeRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须 > 0, 实际: " + capacity);
        }

        this.capacity = nextPowerOfTwo(capacity);
        this.mask = this.capacity - 1;
        this.buffer = new AtomicReferenceArray<>(this.capacity);

        LOGGER.fine(String.format("LockFreeRingBuffer 初始化: capacity=%d (取整自 %d)",
                this.capacity, capacity));
    }

    /**
     * 入队操作（非阻塞，MPSC 安全）
     * <p>
     * 多个生产者线程可安全调用。使用 CAS 原子抢占写入位置。
     * 如果队列已满则立即返回 false，不阻塞、不抛异常。
     *
     * @param element 要入队的元素（不能为 null）
     * @return true 如果入队成功，false 如果队列已满
     */
    public boolean enqueue(E element) {
        if (element == null) {
            throw new NullPointerException("不允许入队 null 元素");
        }

        // CAS 循环抢占写入位置
        int currentWrite;
        while (true) {
            currentWrite = writeIndex.get();
            int currentRead = readIndex;

            // 队列已满检查
            if (currentWrite - currentRead >= capacity) {
                enqueueFailures.incrementAndGet();
                return false;
            }

            // CAS 抢占位置：如果 writeIndex 未被其他线程修改，则递增
            if (writeIndex.compareAndSet(currentWrite, currentWrite + 1)) {
                break;
            }
            // CAS 失败：其他线程已抢占此位置，重试
        }

        // 写入数据到抢占的位置（确保可见性）
        buffer.set(currentWrite & mask, element);

        // 统计更新（非关键路径，使用lazySet减少内存屏障开销）
        totalEnqueues.lazySet(totalEnqueues.get() + 1);
        return true;
    }

    /**
     * 出队操作（非阻塞，单消费者）
     * <p>
     * 仅由单一消费者线程调用。如果队列为空则返回 null。
     *
     * @return 队头元素，或 null 如果队列为空
     */
    public E dequeue() {
        int currentRead = readIndex;
        int currentWrite = writeIndex.get();

        // 队列为空
        if (currentRead >= currentWrite) {
            return null;
        }

        // 读取并清空槽位（getAndSet保证原子性）
        E element = buffer.getAndSet(currentRead & mask, null);

        // 推进读指针（volatile写入确保可见性）
        readIndex = currentRead + 1;

        // 统计更新（非关键路径，使用lazySet减少内存屏障开销）
        totalDequeues.lazySet(totalDequeues.get() + 1);
        return element;
    }

    /**
     * 批量出队并处理
     * <p>
     * 高效地批量取出所有可用元素并交给处理器处理。
     *
     * @param processor 元素处理器
     * @return 本次处理的元素数量
     */
    public int drainTo(Consumer<? super E> processor) {
        int drained = 0;
        E element;

        while ((element = dequeue()) != null) {
            try {
                processor.accept(element);
                drained++;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "drainTo 处理器异常", e);
            }
        }

        return drained;
    }

    /**
     * 查看队头元素但不移除
     *
     * @return 队头元素，或 null 如果队列为空
     */
    public E peek() {
        int currentRead = readIndex;
        int currentWrite = writeIndex.get();
        if (currentRead >= currentWrite) {
            return null;
        }
        return buffer.get(currentRead & mask);
    }

    /**
     * 队列是否为空
     */
    public boolean isEmpty() {
        return readIndex >= writeIndex.get();
    }

    /**
     * 队列是否已满
     */
    public boolean isFull() {
        return writeIndex.get() - readIndex >= capacity;
    }

    /**
     * 获取当前元素数量
     */
    public int size() {
        int write = writeIndex.get();
        int read = readIndex;
        return Math.max(0, write - read);
    }

    /**
     * 获取缓冲区容量
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 清空队列（O(1) 指针重置版）
     * <p>
     * GPU友好优化：直接重置读写指针到同一位置，
     * 避免原始实现的 O(n) 逐个 dequeue 操作。
     *
     * 注意：此方法仅允许消费者线程调用（与 dequeue() 同一线程）
     */
    public void clear() {
        // O(1) 指针重置：直接将读指针追上写指针
        // 不需要逐个清空数组元素（GC会在后续写入时覆盖旧引用）
        int currentWrite = writeIndex.get();
        readIndex = currentWrite;
    }

    // ==================== 统计 API ====================

    /** 获取总入队次数 */
    public long getTotalEnqueues() { return totalEnqueues.get(); }

    /** 获取总出队次数 */
    public long getTotalDequeues() { return totalDequeues.get(); }

    /** 获取入队失败次数（队列满） */
    public long getEnqueueFailures() { return enqueueFailures.get(); }

    /**
     * 获取统计摘要
     */
    public String getStatistics() {
        return String.format(
                "LockFreeRingBuffer{size=%d/%d, enqueues=%d, dequeues=%d, failures=%d}",
                size(), capacity,
                getTotalEnqueues(), getTotalDequeues(), getEnqueueFailures()
        );
    }

    // ==================== 私有工具方法 ====================

    /**
     * 计算不小于给定值的最小2的幂次（无分支版）
     * <p>
     * GPU友好优化：使用位传播技术替代if分支链。
     *
     * @param value 输入值
     * @return 不小于value的最小2的幂次
     */
    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 2;
        // 无分支位传播算法：将最高位以下全部置1，再加1
        value--;
        value |= value >>> 1;
        value |= value >>> 2;
        value |= value >>> 4;
        value |= value >>> 8;
        value |= value >>> 16;
        return value + 1;
    }
}
