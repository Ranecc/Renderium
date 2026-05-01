package com.renderium.vulkan.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * DestructionQueue - 异步资源销毁队�?(26.2-snapshot-3 兼容)
 *
 * <p>管理 GPU 资源的延迟销毁，解决 Vulkan 规范�?正在使用的资源不能立即删�?
 * 的限制。工作原�?</p>
 * <ol>
 *   <li>渲染线程调用 enqueueForDestruction() 将资源入�?/li>
 *   <li>在帧结束时（或特定同步点）调�?processPendingDestructions()</li>
 *   <li>队列中的资源在命令缓冲区完成后被安全销�?/li>
 * </ol>
 *
 * <h3>线程安全:</h3>
 * <ul>
 *   <li>enqueueForDestruction(): 多线程安全（使用 ConcurrentLinkedQueue�?/li>
 *   <li>processPendingDestructions(): 应在单线程（渲染主线程）调用</li>
 * </ul>
 *
 * <h3>使用示例:</h3>
 * <pre>{@code
 * // 创建销毁队�? * DestructionQueue queue = new DestructionQueue();
 *
 * // 在渲染线程中入队待销毁资�? * queue.enqueueForDestruction(oldBuffer);
 * queue.enqueueForDestruction(oldTexture);
 *
 * // 在帧结束时统一销�? * int destroyed = queue.processPendingDestructions(commandBufferHandle);
 *
 * // 设备关闭前清理所有剩余资�? * queue.shutdown();
 * }</pre>
 *
 * @see Destroyable
 */
public final class DestructionQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-DestructionQueue");

    /** 待销毁资源队列（线程安全�?*/
    private final ConcurrentLinkedQueue<Destroyable> pendingDestruction = new ConcurrentLinkedQueue<>();

    /** 当前批次待处理列表（用于批量处理�?*/
    private final List<Destroyable> batchList = new ArrayList<>(64);

    /** 是否已关�?*/
    private volatile boolean shutdown = false;

    /**
     * 构造函�?- 初始化销毁队�?     *
     * <p>创建一个空的销毁队列实例。队列初始化后即可接受资源入队请求，
     * 但实际销毁操作需要显式调�?processPendingDestructions() 触发�?/p>
     */
    public DestructionQueue() {
        LOGGER.info("DestructionQueue initialized");
    }

    /**
     * 将资源加入待销毁队�?     *
     * <p>此方法可在任意线程安全调用。资源不会立即销毁，
     * 而是在下�?processPendingDestructions() 调用时统一处理�?/p>
     *
     * <p><b>线程安全:</b> 使用 ConcurrentLinkedQueue 保证多线程并发安全�?/p>
     *
     * @param resource 需要销毁的可销毁资源（不能�?null�?     *                实现�?{@link Destroyable} 接口�?GPU 资源对象
     * @throws IllegalArgumentException 如果 resource �?null
     *
     * @see Destroyable
     */
    public void enqueueForDestruction(Destroyable resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Resource cannot be null");
        }
        if (shutdown) {
            LOGGER.warn("Attempted to enqueue resource after shutdown, destroying immediately");
            resource.destroy();
            return;
        }
        pendingDestruction.add(resource);
    }

    /**
     * 处理所有待销毁资�?     *
     * <p>应在每帧结束或命令缓冲区提交后调用�?     * 将队列中的所有资源取出并执行 destroy()�?/p>
     *
     * <p><b>注意:</b> 此方法不是线程安全的，应在单线程（渲染主线程）调用�?     * 多线程并发调用可能导致资源重复销毁或遗漏�?/p>
     *
     * <p><b>性能说明:</b> 使用批量处理模式减少锁竞争：
     * 先将 ConcurrentLinkedQueue 中的所有元素转移到本地 ArrayList�?     * 然后逐个销毁，避免频繁操作并发队列�?/p>
     *
     * @param commandBuffer 当前�?Vulkan 命令缓冲区句柄（用于同步�?     *                      当前版本保留参数接口以兼容未来扩展，
     *                      实际实现中未使用此参数进行同步检�?     * @return int 本批次成功销毁的资源数量（已销毁的资源不计入）
     *         返回值范�? [0, 队列当前大小]
     *
     * @see Destroyable#destroy()
     */
    public int processPendingDestructions(long commandBuffer) {
        if (shutdown) {
            return 0;
        }

        // 批量取出所有待销毁资源到本地列表
        batchList.clear();
        Destroyable resource;
        while ((resource = pendingDestruction.poll()) != null) {
            batchList.add(resource);
        }

        // 统一销毁所有取出的资源
        int count = 0;
        for (Destroyable r : batchList) {
            try {
                if (!r.isDestroyed()) {
                    r.destroy();
                    count++;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to destroy resource: {}", e.getMessage());
            }
        }

        if (count > 0 && LOGGER.isTraceEnabled()) {
            LOGGER.trace("Destroyed {} resources in this batch", count);
        }

        return count;
    }

    /**
     * 关闭销毁队列并强制销毁所有剩余资�?     *
     * <p>应在设备关闭前调用，确保没有资源泄漏。此方法�?</p>
     * <ol>
     *   <li>标记队列为关闭状态，拒绝新的入队请求</li>
     *   <li>强制取出并销毁队列中所有剩余资�?/li>
     *   <li>清空内部状�?/li>
     * </ol>
     *
     * <p><b>幂等性保�?</b> 多次调用不会产生副作用，第二次及后续调用会直接返回�?/p>
     *
     * <p><b>异常处理:</b> 单个资源销毁失败不会中断整体流程，
     * 会记录警告日志并继续处理剩余资源�?/p>
     *
     * @see #isShutdown()
     */
    public void shutdown() {
        if (shutdown) {
            return;  // 幂等性：多次调用安全返回
        }
        shutdown = true;

        int remaining = pendingDestruction.size();
        if (remaining > 0) {
            LOGGER.info("Shutting down DestructionQueue, force-destroying {} remaining resources", remaining);

            // 强制取出所有剩余资源并销�?            List<Destroyable> allRemaining = new ArrayList<>(pendingDestruction);
            for (Destroyable r : allRemaining) {
                try {
                    if (!r.isDestroyed()) {
                        r.destroy();
                    }
                } catch (Exception e) {
                    LOGGER.warn("Error during shutdown destruction: {}", e.getMessage());
                }
            }
            pendingDestruction.clear();
        }

        LOGGER.info("DestructionQueue shut down successfully");
    }

    /**
     * 获取当前待销毁资源数量（用于调试/监控�?     *
     * <p>此方法可用于监控队列堆积情况，判断是否存在资源泄漏风险�?     * 正常情况下，此数值应在每�?processPendingDestructions() 后归零�?/p>
     *
     * <p><b>注意:</b> 返回值为近似值，由于多线程环境，实际数量可能在获取后立即变化�?/p>
     *
     * @return int 队列中当前等待销毁的资源数量
     *         返回值范�? [0, +�?
     */
    public int getPendingCount() {
        return pendingDestruction.size();
    }

    /**
     * 检查队列是否已关闭
     *
     * <p>用于外部组件判断是否还可以向队列提交销毁请求�?     * 一旦关闭，后续�?enqueueForDestruction() 调用会立即销毁资源而非入队�?/p>
     *
     * @return boolean true 如果 shutdown() 已被调用且队列处于关闭状态；
     *                  false 如果队列仍正常运�?     *
     * @see #shutdown()
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
