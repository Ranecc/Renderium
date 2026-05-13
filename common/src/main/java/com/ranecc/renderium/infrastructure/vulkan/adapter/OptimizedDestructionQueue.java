package com.ranecc.renderium.infrastructure.vulkan.adapter;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 优化的销毁队列 (P1 优化)
 * <p>
 * 解决原版 {@code DestructionQueue.rotate()} 每次都 {@code new ReferenceArrayList()} 的性能问题。
 * 改为复用已有容器，通过 clear() 清空后等待下次使用。
 * </p>
 *
 * <h3>解决的问题：</h3>
 * <ul>
 *   <li>每次 rotate() 分配新容器 → GC 压力</li>
 *   <li>双缓冲场景下每帧 2 次对象分配</li>
 * </ul>
 *
 * <h3>优化原理：</h3>
 * <pre>
 * 优化前: queues.set(index, new ReferenceArrayList&lt;&gt;())   // 每次分配
 * 优化后: queue.clear()                                  // 复用容器，零分配
 * </pre>
 *
 * @param &lt;T&gt; 资源类型（需实现 Destroyable 接口）
 * @see com.renderium.vulkan.adapter.Destroyable
 * @since 5.2.0
 */
public final class OptimizedDestructionQueue<T> {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|OptDestructionQ");

    /** 销毁回调接口 */
    public interface DestroyCallback<T> {
        void begin(int count);
        void destroy(T resource);
        void end();
    }

    /** 双缓冲队列 */
    private final List<T>[] queues;

    /** 当前队列索引 */
    private int currentIndex;

    /** 销毁回调 */
    private final DestroyCallback<T> destroyCallback;

    /** 累计销毁的资源总数（统计） */
    private long totalDestroyed;

    /**
     * 创建优化的双缓冲销毁队列
     *
     * 【方法参数】
     * @param queueCount      int - 队列数量（通常为 2 或 3）
     * @param destroyCallback DestroyCallback - 资源销毁回调
     *
     * 【异常】
     * @throws IllegalArgumentException 如果参数无效
     */
    @SuppressWarnings("unchecked")
    public OptimizedDestructionQueue(int queueCount, DestroyCallback<T> destroyCallback) {
        if (queueCount < 1) throw new IllegalArgumentException("queueCount >= 1");
        if (destroyCallback == null) throw new IllegalArgumentException("destroyCallback 不能为 null");

        this.queues = new List[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.queues[i] = new ReferenceArrayList<>();
        }
        this.currentIndex = 0;
        this.destroyCallback = destroyCallback;
        this.totalDestroyed = 0L;
    }

    /**
     * 入队待销毁资源
     *
     * 【方法参数】
     * @param resource T - 待销毁的 Vulkan 资源
     */
    public void enqueue(T resource) {
        if (resource == null) return;
        queues[currentIndex].add(resource);
    }

    /**
     * 执行轮转并销毁资源（容器复用版本）
     * <p>
     * 与原版的区别：不再 new ReferenceArrayList()，
     * 而是获取现有容器、执行销毁、clear() 复用。
     * </p>
     *
     * 【返回值】boolean - true 表示有资源被销毁
     */
    public boolean rotate() {
        // 切换到下一个队列
        ++currentIndex;
        currentIndex %= queues.length;

        // 获取当前队列（复用已有容器，不分配新的）
        List<T> currentQueue = queues[currentIndex];

        if (currentQueue.isEmpty()) {
            return false;
        }

        // 批量销毁
        int count = currentQueue.size();
        destroyCallback.begin(count);

        for (T resource : currentQueue) {
            try {
                destroyCallback.destroy(resource);
                totalDestroyed++;
            } catch (Exception e) {
                LOGGER.warning("资源销毁异常: " + e.getMessage());
            }
        }

        destroyCallback.end();

        // 关键优化：清空容器而非创建新的（零 GC 开销）
        currentQueue.clear();

        return true;
    }

    /** 获取当前队列中的待销毁资源数 */
    public int getCurrentQueueSize() {
        return queues[currentIndex].size();
    }

    /** 获取所有队列的总待销毁资源数 */
    public int getTotalPendingSize() {
        int total = 0;
        for (List<T> q : queues) {
            total += q.size();
        }
        return total;
    }

    /** 获取累计已销毁的资源数 */
    public long getTotalDestroyed() { return totalDestroyed; }
}
