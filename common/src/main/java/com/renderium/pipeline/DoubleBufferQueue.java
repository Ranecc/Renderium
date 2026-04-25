// Renderium - 双缓冲渲染队列 (v1)
// 用于 BFS 遮挡剔除的无锁双缓冲队列
// 参考: sodium-dev DoubleBufferedQueue 改写
// 设计目标: 零 GC 压力、无锁 flip、O(1) 操作

package com.renderium.pipeline;

import java.util.Arrays;

/**
 * 双缓冲渲染队列
 * <p>
 * 专门为 BFS 遮挡剔除设计的双缓冲数据结构。
 * 生产者写入 write 缓冲区，消费者从 read 缓冲区读取，
 * 通过 flip() 原子交换两个缓冲区的角色。
 *
 * <h3>使用场景：</h3>
 * <pre>
 * 帧 N:
 *   写入线程 → [write buffer] → 入队可见区块 → flip()
 *   读取线程 ← [read buffer]  ← 出队区块并渲染
 *
 * 帧 N+1:
 *   角色互换，继续...
 * </pre>
 *
 * <h3>与 LockFreeRingBuffer 的区别：</h3>
 * <ul>
 *   <li>LockFreeRingBuffer: SPSC（单生产者-单消费者），适合持续流式数据</li>
 *   <li>DoubleBufferQueue: 批量生产-批量消费，适合每帧一次性处理</li>
 * </ul>
 *
 * @param <E> 元素类型（通常为 RenderSection 或 ChunkRenderTask）
 */
public final class DoubleBufferQueue<E> {

    /** 默认初始容量 */
    private static final int DEFAULT_CAPACITY = 256;

    /** 读缓冲区（volatile 保证跨线程可见性） */
    private volatile Buffer<E> readBuffer;

    /** 写缓冲区（volatile 保证跨线程可见性） */
    private volatile Buffer<E> writeBuffer;

    /**
     * 创建默认容量的双缓冲队列
     */
    public DoubleBufferQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 创建指定初始容量的双缓冲队列
     *
     * @param initialCapacity 初始容量
     */
    public DoubleBufferQueue(int initialCapacity) {
        this.readBuffer = new Buffer<>(initialCapacity);
        this.writeBuffer = new Buffer<>(initialCapacity);
    }

    /**
     * 交换读写缓冲区
     * <p>
     * 调用后：
     * <ul>
     *   <li>原 write 变成 read（供消费者读取）</li>
     *   <li>原 read 变成 write（清空后供生产者写入）</li>
     * </ul>
     *
     * @return true 如果写缓冲区有数据，false 如果写缓冲区为空
     */
    public boolean flip() {
        if (writeBuffer.size() == 0) {
            return false;
        }

        // 交换引用（原子操作）
        Buffer<E> temp = readBuffer;
        readBuffer = writeBuffer;
        writeBuffer = temp;

        // 清空新的写缓冲区
        writeBuffer.clear();

        return true;
    }

    /**
     * 重置两个缓冲区
     */
    public void reset() {
        readBuffer.clear();
        writeBuffer.clear();
    }

    /**
     * 获取读缓冲区（消费者使用）
     *
     * @return 读缓冲区接口
     */
    public ReadView<E> read() {
        return readBuffer;
    }

    /**
     * 获取写缓冲区（生产者使用）
     *
     * @return 写缓冲区接口
     */
    public WriteView<E> write() {
        return writeBuffer;
    }

    // ==================== 内部缓冲区实现 ====================

    /**
     * 内部缓冲区实现（同时提供读/写视图）
     *
     * @param <E> 元素类型
     */
    private static final class Buffer<E> implements ReadView<E>, WriteView<E> {

        /** 存储数组 */
        private E[] elements;

        /** 读指针 */
        private int readIndex;

        /** 写指针 */
        private int writeIndex;

        @SuppressWarnings("unchecked")
        Buffer(int capacity) {
            this.elements = (E[]) new Object[capacity];
            this.readIndex = 0;
            this.writeIndex = 0;
        }

        // ========== ReadView 实现 ==========

        @Override
        public E dequeue() {
            if (readIndex == writeIndex) {
                return null;
            }
            E element = elements[readIndex];
            elements[readIndex] = null;  // 助手 GC
            readIndex++;
            return element;
        }

        @Override
        public int size() {
            return writeIndex - readIndex;
        }

        @Override
        public boolean isEmpty() {
            return readIndex == writeIndex;
        }

        // ========== WriteView 实现 ==========

        @Override
        public void ensureCapacity(int numElements) {
            int requiredSize = writeIndex + numElements;
            if (requiredSize > elements.length) {
                grow(requiredSize);
            }
        }

        @Override
        public void enqueue(E element) {
            if (writeIndex >= elements.length) {
                grow(writeIndex + 1);
            }
            elements[writeIndex++] = element;
        }

        @Override
        public void clear() {
            if (writeIndex != 0) {
                Arrays.fill(elements, 0, writeIndex, null);
            }
            readIndex = 0;
            writeIndex = 0;
        }

        // ========== 私有方法 ==========

        private void grow(int minimumSize) {
            int newSize = nextPowerOfTwo(minimumSize);
            @SuppressWarnings("unchecked")
            E[] newElements = (E[]) new Object[newSize];
            System.arraycopy(elements, 0, newElements, 0, writeIndex);
            elements = newElements;
        }

        /**
         * 计算不小于给定值的最小2的幂次（无分支版）
         * <p>
         * GPU友好优化：使用位传播技术替代if分支链。
         * 算法原理：将最高位以下的位全部置1，再加1得到2的幂次。
         *
         * 示例：
         *   value = 5 (0b0101)
         *   step1: 5 | (5 >> 1) = 0b0111
         *   step2: 7 | (7 >> 2) = 0b0111
         *   step3: 7 | (7 >> 4) = 0b0111
         *   step4: 7 | (7 >> 8) = 0b0111
         *   step5: 7 | (7 >> 16) = 0b0111
         *   result: 7 + 1 = 8 (0b1000)
         *
         * @param value 输入值（必须 > 0）
         * @return 不小于value的最小2的幂次
         */
        private static int nextPowerOfTwo(int value) {
            if (value <= 1) return 2;
            // 无分支位传播算法
            value--;
            value |= value >>> 1;
            value |= value >>> 2;
            value |= value >>> 4;
            value |= value >>> 8;
            value |= value >>> 16;
            return value + 1;
        }
    }

    // ==================== 接口定义 ====================

    /**
     * 只读视图（消费者使用）
     *
     * @param <E> 元素类型
     */
    public interface ReadView<E> {
        /** 出队一个元素，或返回 null */
        E dequeue();

        /** 当前元素数量 */
        int size();

        /** 是否为空 */
        boolean isEmpty();
    }

    /**
     * 只写视图（生产者使用）
     *
     * @param <E> 元素类型
     */
    public interface WriteView<E> {
        /** 确保容量足够容纳指定数量的元素 */
        void ensureCapacity(int numElements);

        /** 入队一个元素 */
        void enqueue(E element);

        /** 清空所有元素 */
        void clear();
    }
}
