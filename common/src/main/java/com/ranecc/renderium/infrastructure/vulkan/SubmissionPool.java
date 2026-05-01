package com.renderium.vulkan;

import java.util.logging.Logger;

/**
 * Vulkan Submission 对象�?(P1 优化)
 * <p>
 * 解决 {@code VulkanQueue.close()} 中每次都分配 VkSubmitInfo2.Buffer�? * SemaphoreSubmitInfo.Buffer 等栈内存的问题�? * 通过对象池复�?Submission 对象，减�?GC 压力�? * </p>
 *
 * <h3>解决的问题：</h3>
 * <ul>
 *   <li>每帧多次 MemoryStack 栈分配（beginSubmit/close�?/li>
 *   <li>每个 SemaphoreOp / SubmitStage 都是新对�?/li>
 *   <li>@1000+ FPS 下每秒数千次短生命周期对象分�?/li>
 * </ul>
 *
 * @since 5.2.0
 */
public final class SubmissionPool {

    /** 日志记录�?*/
    private static final Logger LOGGER = Logger.getLogger("Renderium|SubmissionPool");

    /** 默认池大�?*/
    private static final int DEFAULT_POOL_SIZE = 8;

    /** 池数�?*/
    private final PooledSubmission[] pool;

    /** 当前可用索引（SPSC：单生产者单消费者） */
    private int availableCount;

    /**
     * 创建 Submission 对象�?     *
     * 【方法参数�?     * @param size int - 池大小（必须 > 0�?     */
    public SubmissionPool(int size) {
        if (size <= 0) throw new IllegalArgumentException("size 必须 > 0");
        this.pool = new PooledSubmission[size];
        this.availableCount = size;

        // 预创建所有对�?        for (int i = 0; i < size; i++) {
            pool[i] = new PooledSubmission();
        }
    }

    /** 使用默认池大�?(8) 创建 */
    public SubmissionPool() {
        this(DEFAULT_POOL_SIZE);
    }

    /**
     * 获取一个可用的 Submission 对象
     * <p>
     * 如果池中有可用对象则复用，否则创建新的�?     * </p>
     *
     * 【返回值�?     * @return PooledSubmission - 可用�?Submission 实例（已重置状态）
     */
    public PooledSubmission acquire() {
        if (availableCount > 0) {
            return pool[--availableCount];
        }
        // 池耗尽时创建新对象（不应频繁发生）
        return new PooledSubmission();
    }

    /**
     * 归还 Submission 对象到池�?     * <p>
     * 重置对象状态后放回池中供下次复用�?     * 如果池满则丢弃（不扩容，避免内存无限增长）�?     * </p>
     *
     * 【方法参数�?     * @param submission PooledSubmission - 要归还的 Submission
     */
    public void release(PooledSubmission submission) {
        if (submission == null) return;

        submission.reset();

        if (availableCount < pool.length) {
            pool[availableCount++] = submission;
        }
        // 池满时丢弃，�?GC 回收
    }

    /** 获取当前可用对象�?*/
    public int getAvailableCount() { return availableCount; }

    /** 获取池总容�?*/
    public int getCapacity() { return pool.length; }

    /**
     * 池化�?Submission 数据结构
     * <p>
     * 包含一�?vkQueueSubmit 所需的所有数据：
     * waitSemaphores / commandBuffers / signalSemaphores / fences
     * </p>
     */
    public static final class PooledSubmission {
        /** 等待信号量列�?*/
        public long[] waitSemaphores;
        /** 等待的信号量值列�?*/
        public long[] waitValues;
        /** 命令缓冲区列�?*/
        public long[] commandBuffers;
        /** 信号量列�?*/
        public long[] signalSemaphores;
        /** Fence 句柄 */
        public long fence;

        /** 各数组的实际长度 */
        public int waitCount;
        public int cmdCount;
        public int signalCount;

        /** 默认容量 */
        private static final int CAPACITY = 8;

        public PooledSubmission() {
            this.waitSemaphores = new long[CAPACITY];
            this.waitValues = new long[CAPACITY];
            this.commandBuffers = new long[CAPACITY];
            this.signalSemaphores = new long[CAPACITY];
            reset();
        }

        /**
         * 重置为初始状态（归还到池前调用）
         */
        public void reset() {
            java.util.Arrays.fill(waitSemaphores, 0L);
            java.util.Arrays.fill(waitValues, 0L);
            java.util.Arrays.fill(commandBuffers, 0L);
            java.util.Arrays.fill(signalSemaphores, 0L);
            fence = 0L;
            waitCount = 0;
            cmdCount = 0;
            signalCount = 0;
        }
    }
}
