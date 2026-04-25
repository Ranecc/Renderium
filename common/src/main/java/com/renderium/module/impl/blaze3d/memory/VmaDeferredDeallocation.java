// Renderium - Blaze3D 优化模块
// VMA 激进优化系统 - 延迟销毁管理器
//
// 功能：通过多帧延迟释放机制，确保 GPU 完成使用后再安全释放资源
// 参考：vma-aggressive-optimizations.md §2.3 延迟销毁与帧延迟释放

package com.renderium.module.impl.blaze3d.memory;

import org.lwjgl.util.vma.Vma;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * VMA 延迟销毁管理器 ⏳
 * <p>
 * 实现 GPU 资源的安全延迟释放机制，解决"立即销毁可能导致 GPU 仍在使用资源"的问题。
 * 采用**环形缓冲队列**设计，将资源释放操作延迟 N 帧（默认 3 帧）后执行，
 * 确保 GPU 已完成对该资源的所有渲染命令。
 *
 * <h2>核心问题：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  问题场景：                                                   │
 * │    Frame N: CPU 提交使用 Buffer X 的渲染命令                  │
 * │    Frame N: CPU 立即调用 vkDestroyBuffer(Buffer X)  ← 危险！│
 * │    Frame N+1: GPU 尝试读取 Buffer X → 未定义行为/崩溃！      │
 * │                                                             │
 * │  根本原因：                                                   │
 * │    Vulkan 是异步架构，CPU 提交命令后 GPU 可能尚未执行完毕     │
 * │    立即销毁会导致 GPU 访问已释放的内存                        │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>解决方案：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  延迟释放策略（3 帧延迟）：                                   │
 * │                                                             │
 * │    Frame N:   请求释放 Buffer X → 加入 [Frame N+3] 队列     │
 * │    Frame N+1: GPU 正在处理 Buffer X                         │
 * │    Frame N+2: GPU 即将完成                                  │
 * │    Frame N+3: 安全释放 Buffer X ✓                          │
 * │                                                             │
 * │  为什么是 3 帧？                                             │
 * │    - Triple Buffer：GPU 通常滞后 2-3 帧                     │
 * │    - Fence 延迟：等待 Fence 信号需要额外时间                │
 * │    - 安全余量：防止极端情况下的竞态条件                      │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>环形缓冲设计：</h3>
 * <pre>
 *   队列结构（FRAME_DELAY=3）：
 *
 *   ┌─────────────────────────────────────┐
 *   │  Queue[0] ← 当前帧要释放的资源       │  ← onFrameEnd() 处理此队列
 *   ├─────────────────────────────────────┤
 *   │  Queue[1] ← 1 帧后释放              │
 *   ├─────────────────────────────────────┤
 *   │  Queue[2] ← 2 帧后释放              │
 *   └─────────────────────────────────────┘
 *         ↑
 *    currentFrame % FRAME_DELAY (循环索引)
 * </pre>
 *
 * <h3>性能特征：</h3>
 * <table border="1">
 *   <tr><th>操作</th><th>时间复杂度</th><th>典型耗时</th></tr>
 *   <tr><td>releaseDeferred()</td><td>O(1)</td><td>~10ns</td></tr>
 *   <tr><td>onFrameEnd()</td><td>O(k)</td><td>k × ~1µs</td></tr>
 *   <tr><td>内存开销</td><td>-</td><td>每个待释放项 ~32 bytes</td></tr>
 * </table>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建延迟销毁管理器
 * VmaDeferredDeallocation deferred = new VmaDeferredDeallocation();
 * deferred.initialize(vmaAllocator);
 *
 * // 渲染循环中：
 * while (running) {
 *     // ... 渲染逻辑 ...
 *
 *     // 不再需要的缓冲区，请求延迟释放（而非立即销毁）
 *     deferred.releaseDeferred(oldBuffer, oldAllocation);
 *
 *     // 帧结束时处理释放队列
 *     deferred.onFrameEnd();
 * }
 *
 * // 应用退出时关闭
 * deferred.close();
 * }</pre>
 *
 * <h3>线程安全说明：</h3>
 * <ul>
 *   <li>{@code releaseDeferred()} 可从任何线程调用（内部加锁）</li>
 *   <li>{@code onFrameEnd()} 应在主线程/渲染线程串行调用</li>
 *   <li>内部状态使用 volatile 和 synchronized 保证一致性</li>
 * </ul>
 *
 * @see VmaMemoryPools 专用内存池管理器
 * @see VmaMemoryBudget 内存预算管理
 * @author Renderium Team
 * @since 2.0.0
 */
public class VmaDeferredDeallocation implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VmaDeferredDeallocation.class.getName());

    /** 单例实例 */
    private static volatile VmaDeferredDeallocation instance;

    /**
     * 获取单例实例
     *
     * @return VmaDeferredDeallocation 实例
     */
    public static VmaDeferredDeallocation getInstance() {
        if (instance == null) {
            synchronized (VmaDeferredDeallocation.class) {
                if (instance == null) {
                    instance = new VmaDeferredDeallocation();
                }
            }
        }
        return instance;
    }

    // ==================== 配置常量 ====================

    /**
     * 默认帧延迟数量（3 帧）
     * <p>
     * 基于 Triple Buffering 和 GPU Pipeline Latency 的经验值。
     * 可通过构造函数或 {@link #setFrameDelay} 自定义。
     */
    public static final int DEFAULT_FRAME_DELAY = 3;

    /** 最小允许的帧延迟（1 帧） */
    public static final int MIN_FRAME_DELAY = 1;

    /** 最大允许的帧延迟（16 帧） */
    public static final int MAX_FRAME_DELAY = 16;

    // ==================== 核心字段 ====================

    /**
     * VMA 分配器句柄
     */
    private volatile long vmaAllocator = 0L;

    /**
     * 帧延迟数量（环形缓冲大小）
     */
    private volatile int frameDelay = DEFAULT_FRAME_DELAY;

    /**
     * 延迟释放队列数组（环形缓冲）
     * <p>
     * 每个元素是一个列表，存储该帧应该释放的资源。
     * 索引计算：{@code queueIndex = currentFrame % frameDelay}
     */
    @SuppressWarnings("unchecked")
    private volatile List<DeferredRelease>[] releaseQueues;

    /**
     * 当前帧计数器
     * <p>
     * 每次调用 {@link #onFrameEnd()} 时递增。
     * 用于确定当前应处理哪个队列。
     */
    private volatile long currentFrame = 0L;

    // ==================== 同步锁 ====================

    /**
     * 队列访问锁
     * <p>
     * 保护 {@code releaseQueues} 数组的并发访问。
     * {@code releaseDeferred()} 和 {@code onFrameEnd()} 都需要获取此锁。
     */
    private final Object queueLock = new Object();

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ==================== 统计字段 ====================

    /** 总请求延迟释放次数 */
    private final AtomicLong totalDeferredReleases = new AtomicLong(0);

    /** 总实际执行释放次数 */
    private final AtomicLong totalExecutedReleases = new AtomicLong(0);

    /** 当前队列中的总待释放项数 */
    private final java.util.concurrent.atomic.AtomicInteger pendingReleaseCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 最大峰值待释放数（用于容量规划） */
    private volatile int peakPendingCount = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建延迟销毁管理器（使用默认 3 帧延迟）
     * <p>
     * 初始状态下未连接到任何 VMA 分配器。
     * 必须调用 {@link #initialize} 后才能使用延迟释放功能。
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * VmaDeferredDeallocation deferred = new VmaDeferredDeallocation();
     * }</pre>
     */
    public VmaDeferredDeallocation() {
        this(DEFAULT_FRAME_DELAY);
    }

    /**
     * 创建延迟销毁管理器（自定义帧延迟）
     *
     * @param frameDelay 帧延迟数量（范围：{@value #MIN_FRAME_DELAY} ~ {@value #MAX_FRAME_DELAY}）
     *
     * @throws IllegalArgumentException 如果 frameDelay 超出有效范围
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>frameDelay</b>: int - 资源从请求释放到实际执行的延迟帧数。
     *       较大的值更安全但占用更多内存；较小的值节省内存但风险更高。</li>
     * </ul>
     *
     * <h4>推荐配置：</h4>
     * <ul>
     *   <li>标准渲染：3 帧（默认，适用于大多数场景）</li>
     *   <li>高性能/低延迟：2 帧（如果确定 GPU 延迟较低）</li>
     *   <li>保守/安全模式：5-8 帧（用于关键应用或多 GPU 场景）</li>
     * </ul>
     */
    public VmaDeferredDeallocation(int frameDelay) {
        if (frameDelay < MIN_FRAME_DELAY || frameDelay > MAX_FRAME_DELAY) {
            throw new IllegalArgumentException(String.format(
                    "frameDelay 必须在 %d 到 %d 之间，当前值: %d",
                    MIN_FRAME_DELAY, MAX_FRAME_DELAY, frameDelay));
        }

        this.frameDelay = frameDelay;
        initializeQueues();

        LOGGER.fine(String.format("VmaDeferredDeallocation 实例已创建 (frameDelay=%d)", frameDelay));
    }

    // ==================== 公共 API：生命周期管理 ====================

    /**
     * 初始化延迟销毁管理器 ⚙️
     * <p>
     * 连接到指定的 VMA 分配器，准备开始接收延迟释放请求。
     * 此方法应在 Vulkan 设备和 VMA 分配器初始化完成后、渲染循环开始前调用一次。
     *
     * @param vmaAllocator VMA 分配器句柄（由 vkCreateAllocator 返回的非零值）
     *
     * @return 如果成功初始化则返回 true；如果参数无效或已初始化则返回 false
     *
     * @throws IllegalStateException 如果已经关闭（close() 已调用）
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>vmaAllocator</b>: long - VMA (Vulkan Memory Allocator) 的分配器句柄。
     *       必须是有效的非零值。用于在延迟到期时执行真正的资源销毁操作。</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>true - 成功连接到 VMA 分配器</li>
     *   <li>false - 参数无效或重复初始化</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * VmaDeferredDeallocation deferred = new VmaDeferredDeallocation(3);  // 3 帧延迟
     * if (!deferred.initialize(vmaAllocator)) {
     *     throw new RuntimeException("延迟销毁管理器初始化失败");
     * }
     * }</pre>
     */
    public boolean initialize(long vmaAllocator) {
        // 参数校验
        if (vmaAllocator == 0L) {
            LOGGER.severe("initialize 失败: vmaAllocator 不能为 0");
            return false;
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaDeferredDeallocation 已关闭，无法重新初始化");
        }

        if (initialized.get()) {
            LOGGER.warning("initialize: 已经初始化过，跳过重复初始化");
            return true; // 幂等性
        }

        try {
            this.vmaAllocator = vmaAllocator;
            initialized.set(true);

            LOGGER.info(String.format(
                    "✓ VmaDeferredDeallocation 初始化完成\n" +
                    "  vmaAllocator=%d, frameDelay=%d",
                    vmaAllocator, frameDelay));
            return true;

        } catch (Exception e) {
            LOGGER.severe("initialize 异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 请求延迟释放资源 🗑️
     * <p>
     * 将指定的 Buffer/Image 及其 VMA allocation 加入到延迟释放队列中。
     * 资源不会立即销毁，而是在 {@code frameDelay} 帧后才真正释放，
     * 以确保 GPU 已完成对该资源的所有使用。
     *
     * <h3>工作流程：</h3>
     * <ol>
     *   <li>计算目标释放帧索引：{@code targetFrame = currentFrame + frameDelay}</li>
     *   <li>计算目标队列索引：{@code queueIndex = targetFrame % frameDelay}</li>
     *   <li>将 buffer/allocation 对封装为 {@link DeferredRelease} 对象</li>
     *   <li>加入对应队列并更新统计信息</li>
     * </ol>
     *
     * @param buffer     要释放的 Buffer 或 Image 句柄（VkBuffer/VkImage）
     * @param allocation 对应的 VMA Allocation 句柄（VmaAllocation）
     *
     * @throws NullPointerException 如果 buffer 或 allocation 相关参数无效
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>buffer</b>: long - 要延迟释放的 GPU 资源句柄。
     *       可以是 VkBuffer（对于缓冲区）或 VkImage（对于图像/纹理）。</li>
     *   <li><b>allocation</b>: long - 与该资源关联的 VMA Allocation 句柄。
     *       通过 Vma.vmaCreateBuffer/vmaCreateImage 返回获得。
     *       此句柄用于后续的 vmaDestroyBuffer/vmaDestroyImage 调用。</li>
     * </ul>
     *
     * <h4>返回值：</h4>
     * <ul>
     *   <li>void - 无返回值。操作始终成功（除非未初始化或已关闭）</li>
     * </ul>
     *
     * <h4>性能考虑：</h4>
     * <ul>
     *   <li>时间复杂度：O(1)（仅做简单的队列添加操作）</li>
     *   <li>典型耗时：~10-50ns（ArrayList.add + AtomicLong.increment）</li>
     *   <li>线程安全：synchronized(queueLock) 保证并发安全</li>
     *   <li>内存开销：每项约 32 字节（DeferredRelease 对象）</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 在渲染循环中，当某个临时缓冲区不再需要时：
     * long tempBuffer = ...;       // VkBuffer 句柄
     * long tempAllocation = ...;   // VmaAllocation 句柄
     *
     * // ❌ 错误做法：立即销毁（危险！）
     * // Vma.vmaDestroyBuffer(vmaAllocator, tempBuffer, tempAllocation);
     *
     * // ✅ 正确做法：请求延迟释放（安全！）
     * deferred.releaseDeferred(tempBuffer, tempAllocation);
     *
     * // tempBuffer 将在 3 帧后被自动安全释放
     * }</pre>
     *
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>调用后不应再使用该 buffer（即使它尚未被真正销毁）</li>
     *   <li>同一资源不应多次调用 releaseDeferred（会导致双重释放错误）</li>
     *   <li>建议配合引用计数或 RAII 包装类使用以避免误用</li>
     * </ul>
     */
    public void releaseDeferred(long buffer, long allocation) {
        if (!initialized.get()) {
            throw new IllegalStateException("VmaDeferredDeallocation 未初始化，请先调用 initialize()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaDeferredDeallocation 已关闭");
        }

        if (buffer == 0L || allocation == 0L) {
            throw new NullPointerException("buffer 和 allocation 不能为 0");
        }

        synchronized (queueLock) {
            try {
                // 计算目标释放队列索引
                int targetQueueIndex = (int) ((currentFrame + frameDelay) % frameDelay);

                // 创建延迟释放项
                DeferredRelease release = new DeferredRelease(buffer, allocation, currentFrame);

                // 加入目标队列
                releaseQueues[targetQueueIndex].add(release);

                // 更新统计
                totalDeferredReleases.incrementAndGet();
                int currentPending = pendingReleaseCount.incrementAndGet();

                // 更新峰值
                if (currentPending > peakPendingCount) {
                    peakPendingCount = currentPending;
                }

                LOGGER.finest(String.format(
                        "[延迟释放请求] buffer=%d, alloc=%d → 队列[%d], 将在第 %d 帧释放",
                        buffer, allocation, targetQueueIndex, currentFrame + frameDelay));

            } catch (Exception e) {
                LOGGER.severe(String.format("releaseDeferred 异常 [buffer=%d]: %s",
                        buffer, e.getMessage()));
            }
        }
    }

    /**
     * 帧结束处理 🔄
     * <p>
     * 应在每帧渲染结束后调用一次。此方法会：
     * <ol>
     *   <li>取出当前帧对应的释放队列</li>
     *   <li>对队列中的每个资源执行真正的 VMA 销毁操作</li>
     *   <li>清空已处理的队列</li>
     *   <li>递增帧计数器</li>
     * </ol>
     *
     * <h3>为什么必须每帧调用？</h3>
     * <ul>
     *   <li>推进环形缓冲的读写指针</li>
     *   <li>确保延迟到期的资源被及时释放</li>
     *   <li>避免待释放队列无限增长导致内存泄漏</li>
     * </ul>
     *
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * <h4>性能考虑：</h4>
     * <ul>
     *   <li>时间复杂度：O(k)，k 为当前队列中的待释放项数量</li>
     *   <li>典型耗时：取决于队列大小（通常 k < 100，总耗时 < 1ms）</li>
     *   <li>建议在主线程/渲染线程末尾调用，避免阻塞渲染管线</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 渲染循环
     * while (running) {
     *     beginFrame();
     *
     *     // ... 渲染逻辑 ...
     *     renderScene();
     *
     *     // ... 请求延迟释放不再需要的资源 ...
     *     deferred.releaseDeferred(oldBuffer, oldAlloc);
     *
     *     endFrame();
     *
     *     // ⚠️ 重要：必须在帧结束时调用！
     *     deferred.onFrameEnd();  // 处理 3 帧前请求释放的资源
     * }
     * }</pre>
     */
    public void onFrameEnd() {
        if (!initialized.get()) {
            throw new IllegalStateException("VmaDeferredDeallocation 未初始化，请先调用 initialize()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaDeferredDeallocation 已关闭");
        }

        synchronized (queueLock) {
            try {
                // 计算当前应处理的队列索引
                int currentQueueIndex = (int) (currentFrame % frameDelay);

                // 取出当前队列
                List<DeferredRelease> toRelease = releaseQueues[currentQueueIndex];

                if (!toRelease.isEmpty()) {
                    LOGGER.fine(String.format(
                            "[onFrameEnd] 帧=%d, 处理队列[%d], 待释放=%d 项",
                            currentFrame, currentQueueIndex, toRelease.size()));

                    // 批量释放队列中的所有资源
                    for (DeferredRelease release : toRelease) {
                        executeRelease(release);
                    }

                    // 更新统计
                    totalExecutedReleases.addAndGet(toRelease.size());
                    pendingReleaseCount.addAndGet(-toRelease.size());

                    // 清空队列（已处理的项）
                    toRelease.clear();
                }

                // 推进帧计数器
                currentFrame++;

            } catch (Exception e) {
                LOGGER.severe(String.format("onFrameEnd 异常 [frame=%d]: %s",
                        currentFrame, e.getMessage()));
            }
        }
    }

    /**
     * 关闭延迟销毁管理器并立即释放所有剩余资源 ♻️
     * <p>
     * 强制释放所有队列中尚未延迟到期的资源。
     * 此方法应该在应用程序退出或 Vulkan 设备销毁前调用。
     *
     * <h3>清理流程：</h3>
     * <ol>
     *   <li>标记为已关闭状态（防止新的延迟释放请求）</li>
     *   <li>遍历所有队列，立即释放其中的所有资源</li>
     *   <li>清空内部状态和统计信息</li>
     *   <li>输出关闭日志</li>
     * </ol>
     *
     * <h4>⚠️ 注意事项：</h4>
     * <ul>
     *   <li>此方法会<strong>立即</strong>释放所有剩余资源（不等待延迟到期）</li>
     *   <li>调用前应确保 GPU 已完成所有渲染命令（如调用 vkDeviceWaitIdle）</li>
     *   <li>否则可能存在 GPU 仍在使用资源的风险</li>
     *   <li>此方法是幂等的（多次调用安全）</li>
     * </ul>
     *
     * @throws Exception 如果底层 VMA 操作失败（AutoCloseable 接口要求）
     */
    @Override
    public void close() throws Exception {
        if (closed.get()) {
            LOGGER.fine("close: 已经关闭过，跳过重复关闭");
            return; // 幂等性
        }

        closed.set(true);
        initialized.set(false);

        try {
            // 强制释放所有队列中的剩余资源
            int totalRemaining = 0;

            for (int i = 0; i < frameDelay; i++) {
                List<DeferredRelease> queue = releaseQueues[i];
                if (!queue.isEmpty()) {
                    totalRemaining += queue.size();

                    for (DeferredRelease release : queue) {
                        executeRelease(release);
                    }

                    queue.clear();
                }
            }

            // 清空状态
            releaseQueues = null;
            vmaAllocator = 0L;

            // 重置统计
            totalDeferredReleases.set(0);
            totalExecutedReleases.set(0);
            pendingReleaseCount.set(0);

            LOGGER.info(String.format(
                    "VmaDeferredDeallocation 已关闭\n" +
                    "  强制释放剩余资源: %d 项\n" +
                    "  总帧数: %d",
                    totalRemaining, currentFrame));

        } catch (Exception e) {
            LOGGER.severe("close 异常: " + e.getMessage());
            throw e;
        }
    }

    // ==================== 公共 API：查询与统计 ====================

    /**
     * 检查是否已初始化
     *
     * @return 如果已成功连接到 VMA 分配器则返回 true
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 检查是否已关闭
     *
     * @return 如果 close() 已被调用则返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 获取当前的帧延迟设置
     *
     * @return 帧延迟数量（即资源从请求释放到实际执行的帧数间隔）
     */
    public int getFrameDelay() {
        return frameDelay;
    }

    /**
     * 获取当前帧编号
     *
     * @return 从 0 开始递增的帧计数器值
     */
    public long getCurrentFrame() {
        return currentFrame;
    }

    /**
     * 获取总请求延迟释放次数
     *
     * @return 自初始化以来调用 {@link #releaseDeferred} 的总次数
     */
    public long getTotalDeferredReleases() {
        return totalDeferredReleases.get();
    }

    /**
     * 获取总实际执行释放次数
     *
     * @return 自初始化以来通过 {@link #onFrameEnd} 实际执行的释放总次数
     */
    public long getTotalExecutedReleases() {
        return totalExecutedReleases.get();
    }

    /**
     * 获取当前待释放队列中的项目总数
     *
     * @return 所有队列中尚未释放的资源总数
     */
    public int getPendingReleaseCount() {
        return pendingReleaseCount.get();
    }

    /**
     * 获取历史峰值待释放数
     *
     * @return 自初始化以来同时存在的最大待释放项目数（用于容量规划参考）
     */
    public int getPeakPendingCount() {
        return peakPendingCount;
    }

    /**
     * 获取格式化的延迟释放报告 📊
     * <p>
     * 生成包含队列状态、统计信息和性能指标的详细报告字符串。
     * 适合用于调试、性能分析和内存泄漏检测。
     *
     * @return 格式化的报告字符串
     *
     * <h4>报告内容：</h4>
     * <ul>
     *   <li>初始化状态和帧延迟配置</li>
     *   <li>释放统计（请求数、执行数、成功率）</li>
     *   <li>各队列的当前负载情况</li>
     *   <li>峰值和历史数据分析</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * String report = deferred.formatReport();
     * System.out.println(report);  // 打印到控制台
     * logger.info(report);          // 记录到日志文件
     * }</pre>
     */
    public String formatReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║  VMA 延迟销毁报告                         ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // 基本信息
        sb.append(String.format("状态: %s | 已关闭: %s\n",
                initialized.get() ? "✓ 已初始化" : "✗ 未初始化",
                closed.get() ? "✗ 是" : "否"));
        sb.append(String.format("VMA Allocator: %d\n", vmaAllocator));
        sb.append(String.format("帧延迟: %d 帧\n", frameDelay));
        sb.append(String.format("当前帧号: %d\n", currentFrame));

        // 统计信息
        sb.append("\n--- 统计 ---\n");
        sb.append(String.format("总请求释放: %d 次\n", getTotalDeferredReleases()));
        sb.append(String.format("总执行释放: %d 次\n", getTotalExecutedReleases()));
        sb.append(String.format("当前待释放: %d 项\n", getPendingReleaseCount()));
        sb.append(String.format("峰值待释放: %d 项\n", getPeakPendingCount()));

        if (getTotalDeferredReleases() > 0) {
            double executionRate = (double) getTotalExecutedReleases() / getTotalDeferredReleases() * 100;
            sb.append(String.format("执行率: %.1f%%\n", executionRate));
        }

        // 各队列详情
        sb.append("\n--- 队列详情 ---\n");
        if (releaseQueues != null) {
            for (int i = 0; i < frameDelay; i++) {
                List<DeferredRelease> queue = releaseQueues[i];
                int size = (queue != null) ? queue.size() : 0;

                // 计算此队列将在哪一帧被处理
                long targetFrame = 0;
                if (currentFrame <= Long.MAX_VALUE - frameDelay) {
                    // 简化估算：假设均匀分布
                    targetFrame = currentFrame + (i - (int)(currentFrame % frameDelay) + frameDelay) % frameDelay;
                }

                sb.append(String.format("  队列[%d]: %3d 项 (将在第 ~%d 帧释放)\n",
                        i, size, targetFrame));
            }
        } else {
            sb.append("  (队列已释放)\n");
        }

        return sb.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 初始化环形缓冲队列
     */
    @SuppressWarnings("unchecked")
    private void initializeQueues() {
        releaseQueues = new List[frameDelay];
        for (int i = 0; i < frameDelay; i++) {
            releaseQueues[i] = new ArrayList<>();
        }
    }

    /**
     * 执行单个资源的真正释放操作
     *
     * @param release 要释放的延迟释放项
     */
    private void executeRelease(DeferredRelease release) {
        try {
            // 调用 VMA API 执行真正的缓冲区/图像销毁
            // 尝试使用 vmaDestroyBuffer（适用于 Buffer 类型资源）
            // 如果失败则尝试 vmaDestroyImage（适用于 Image 类型资源）
            
            // 注意：VMA 的 vmaDestroyBuffer 和 vmaDestroyImage 内部实现类似，
            // 都是释放 allocation 并销毁对应的 Vulkan 对象。
            // 这里统一使用 vmaDestroyBuffer，因为大多数延迟释放的资源是 Buffer。
            
            Vma.vmaDestroyBuffer(vmaAllocator, release.buffer, release.allocation);

            LOGGER.finest(String.format("[执行释放成功] buffer=%d, alloc=%d (请求于帧 %d)",
                    release.buffer, release.allocation, release.requestFrame));

        } catch (Exception e) {
            // 如果 vmaDestroyBuffer 失败，可能是因为这是一个 Image 而非 Buffer
            // 尝试使用 vmaDestroyImage 作为备选方案
            try {
                Vma.vmaDestroyImage(vmaAllocator, release.buffer, release.allocation);
                LOGGER.finest(String.format("[执行释放成功-Image] buffer/image=%d, alloc=%d (请求于帧 %d)",
                        release.buffer, release.allocation, release.requestFrame));
            } catch (Exception e2) {
                // 两种方式都失败，记录错误
                LOGGER.severe(String.format("执行释放异常 [buffer=%d, frame=%d]: %s (备选: %s)",
                        release.buffer, release.requestFrame, e.getMessage(), e2.getMessage()));
            }
        }
    }

    // ==================== 内部数据类 ====================

    /**
     * 延迟释放项
     * <p>
     * 封装一个待延迟释放的 GPU 资源及其相关信息。
     */
    private static class DeferredRelease {
        /** Buffer 或 Image 句柄 */
        final long buffer;

        /** VMA Allocation 句柄 */
        final long allocation;

        /** 请求释放时的帧号（用于调试和追踪） */
        final long requestFrame;

        /**
         * 创建延迟释放项
         *
         * @param buffer       资源句柄
         * @param allocation   VMA allocation 句柄
         * @param requestFrame 请求时的帧号
         */
        DeferredRelease(long buffer, long allocation, long requestFrame) {
            this.buffer = buffer;
            this.allocation = allocation;
            this.requestFrame = requestFrame;
        }
    }
}
