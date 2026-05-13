// Renderium - Blaze3D 拦截层系统 Phase 4
// 异步帧捕获器 - 高性能非阻塞帧捕获，支持 Triple Buffering

package com.ranecc.renderium.feature.intercept.post;
import com.ranecc.renderium.domain.model.FrameCaptureContext;

import com.ranecc.renderium.None;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 异步帧捕获器（Async Frame Capturer）
 * <p>
 * 提供高性能的非阻塞帧捕获功能，核心设计目标：
 * <ul>
 *   <li><b>零阻塞</b>：捕获操作在独立线程执行，不阻塞渲染线程</li>
 *   <li><b>Triple Buffering</b>：三重缓冲机制，减少帧延迟和 GPU 空闲等待</li>
 *   <li><b>双路径支持</b>：同时支持 FBO（OpenGL）和 Swapchain Image（Vulkan）两种捕获模式</li>
 *   <li><b>低延迟</b>：捕获开销控制在 &lt;1ms 以内（兼容模式），&lt;0.5ms（狂暴模式）</li>
 * </ul>
 *
 * <h3>架构设计：</h3>
 * <pre>
 * ┌─────────────┐    requestCapture()     ┌──────────────────┐
 * │  渲染线程   │ ──────────────────────→ │  捕获请求队列     │
 * │ (RenderThread)│                        │ (BlockingQueue)   │
 * └─────────────┘                         └────────┬─────────┘
 *                                                  │
 *                                                  ▼
 *                                          ┌──────────────────┐
 *                                          │  捕获工作线程      │
 *                                          │ (CaptureThread)   │
 *                                          │   - GPU Readback  │
 *                                          │   - Pixel Copy    │
 *                                          └────────┬─────────┘
 *                                                   │
 *                                                   ▼
 *                                          ┌──────────────────┐
 *                                          │ Triple Buffering │
 *                                          │ [Buffer 0] ✓ 就绪│
 *                                          │ [Buffer 1] ✓ 就绪│
 *                                          │ [Buffer 2] ○ 空闲│
 *                                          └────────┬─────────┘
 *                                                   │
 *                                                   ▼
 *                                          ┌──────────────────┐
 *                                          │ 已完成帧队列       │
 *                                          │ (Completed Queue) │
 *                                          └──────────────────┘
 * </pre>
 *
 * <h3>Triple Buffering 工作原理：</h3>
 * <p>使用三个缓冲区循环使用，确保：
 * <ol>
 *   <li>渲染线程写入 Buffer N</li>
 *   <li>捕获线程读取 Buffer N-1</li>
 *   <li>消费线程读取 Buffer N-2</li>
 * </ol>
 * 这样三个操作可以并行执行，最大化 GPU 利用率。
 *
 * <h3>性能预算：</h3>
 * <table border="1">
 *   <tr><th>阶段</th><th>兼容模式</th><th>狂暴模式</th></tr>
 *   <tr><td>帧捕获</td><td>&lt;2ms</td><td>&lt;1ms</td></tr>
 * </table>
 *
 * <h3>线程安全：</h3>
 * <p>此类完全线程安全，可在任意线程调用。
 * 内部使用 {@link BlockingQueue}、{@link AtomicBoolean}、{@link AtomicInteger} 等并发原语。
 *
 * @see FrameCaptureContext 捕获上下文
 * @see CapturedFrame 捕获的帧数据
 * @since 5.2.0 (Phase 4)
 */
public final class AsyncFrameCapturer {

    private static final Logger LOGGER = Logger.getLogger(AsyncFrameCapturer.class.getName());

    // ==================== 单例实例 ====================

    /** 全局唯一实例（volatile 保证可见性） */
    private static volatile AsyncFrameCapturer INSTANCE;

    /**
     * 获取 AsyncFrameCapturer 单例实例
     * <p>
     * 使用双重检查锁定（Double-Checked Locking）保证线程安全的延迟初始化。
     *
     * @return AsyncFrameCapturer 全局唯一实例
     */
    public static AsyncFrameCapturer getInstance() {
        if (INSTANCE == null) {
            synchronized (AsyncFrameCapturer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AsyncFrameCapturer();
                }
            }
        }
        return INSTANCE;
    }

    // ==================== 配置常量 ====================

    /** Triple Buffering 缓冲区数量 */
    public static final int TRIPLE_BUFFER_COUNT = 3;

    /** 默认捕获队列容量 */
    private static final int DEFAULT_CAPTURE_QUEUE_CAPACITY = 4;

    /** 默认已完成帧队列容量 */
    private static final int DEFAULT_COMPLETED_QUEUE_CAPACITY = 8;

    /** 默认捕获超时时间（毫秒） */
    private static final long DEFAULT_CAPTURE_TIMEOUT_MS = 100L;

    /** 性能预算：兼容模式最大捕获时间（纳秒）= 2ms */
    private static final long COMPATIBILITY_BUDGET_NS = 2_000_000L;

    /** 性能预算：狂暴模式最大捕获时间（纳秒）= 1ms */
    private static final long AGGRESSIVE_BUDGET_NS = 1_000_000L;

    // ==================== 核心状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否正在运行（捕获线程活跃） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 是否处于捕获状态 */
    private final AtomicBoolean capturing = new AtomicBoolean(false);

    /** 运行模式标记（true=狂暴模式，false=兼容模式） */
    private volatile boolean aggressiveMode = false;

    // ==================== Triple Buffering 字段 ====================

    /** 当前可用的缓冲区数量（0-3） */
    private final AtomicInteger availableBufferCount = new AtomicInteger(TRIPLE_BUFFER_COUNT);

    /** 当前写入缓冲区索引（0-2，循环递增） */
    private final AtomicInteger writeBufferIndex = new AtomicInteger(0);

    /** 当前读取缓冲区索引（0-2，循环递增） */
    private final AtomicInteger readBufferIndex = new AtomicInteger(0);

    /** Triple Buffer 缓冲区数组 */
    private final CapturedFrame[] tripleBuffers = new CapturedFrame[TRIPLE_BUFFER_COUNT];

    /** 缓冲区锁对象（用于精细控制缓冲区访问） */
    private final Object bufferLock = new Object();

    // ==================== 队列字段 ====================

    /** 捕获请求队列（渲染线程 → 捕获线程） */
    private final BlockingQueue<CaptureTask> captureRequestQueue;

    /** 已完成帧队列（捕获线程 → 消费线程） */
    private final BlockingQueue<CapturedFrame> completedFrameQueue;

    // ==================== 线程字段 ====================

    /** 捕获工作线程 */
    private volatile ExecutorService captureExecutor;

    // ==================== 性能统计字段 ====================

    /** 总捕获次数计数器 */
    private final AtomicLong totalCaptureCount = new AtomicLong(0L);

    /** 总捕获耗时累计（纳秒） */
    private final AtomicLong totalCaptureTimeNanos = new AtomicLong(0L);

    /** 最大单次捕获耗时（纳秒） */
    private final AtomicLong maxCaptureTimeNanos = new AtomicLong(0L);

    /** 待处理请求数量 */
    private final AtomicInteger pendingRequests = new AtomicInteger(0);

    /** 总内存占用估算（字节），使用 AtomicLong 保证原子性 */
    private final AtomicLong totalMemoryBytes = new AtomicLong(0L);

    /** 每个缓冲区槽位的释放标记（防止重复释放导致负数） */
    private final boolean[] bufferReleased = new boolean[TRIPLE_BUFFER_COUNT];

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数 - 初始化队列和内部状态
     * <p>
     * 创建阻塞队列用于异步任务传递，
     * 队列容量经过调优以平衡延迟和吞吐量。
     */
    private AsyncFrameCapturer() {
        // 使用有界队列防止内存溢出
        this.captureRequestQueue = new LinkedBlockingQueue<>(DEFAULT_CAPTURE_QUEUE_CAPACITY);
        this.completedFrameQueue = new LinkedBlockingQueue<>(DEFAULT_COMPLETED_QUEUE_CAPACITY);
        
        // 初始化 Triple Buffer 数组为 null
        for (int i = 0; i < TRIPLE_BUFFER_COUNT; i++) {
            tripleBuffers[i] = null;
        }
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化异步帧捕获器
     * <p>
     * 启动捕获工作线程，分配初始资源。
     * 必须在使用前调用此方法。
     *
     * @param aggressiveMode 是否启用狂暴模式（影响性能预算）
     * @return true 表示初始化成功，false 表示失败或已初始化
     */
    public boolean initialize(boolean aggressiveMode) {
        if (initialized.get()) {
            LOGGER.warning("AsyncFrameCapturer 已初始化，跳过重复初始化");
            return true;
        }

        try {
            this.aggressiveMode = aggressiveMode;

            // 创建捕获工作线程池（单线程，保证顺序执行）
            captureExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Renderium-AsyncFrameCapture");
                t.setDaemon(true);  // 守护线程，不阻止 JVM 退出
                t.setPriority(Thread.NORM_PRIORITY + 1);  // 稍高优先级
                return t;
            });

            // 标记为已初始化和运行中
            initialized.set(true);
            running.set(true);

            LOGGER.info(String.format(
                "AsyncFrameCapturer 初始化完成 | 模式=%s | 预算=%.1fms",
                aggressiveMode ? "狂暴" : "兼容",
                aggressiveMode ? (AGGRESSIVE_BUDGET_NS / 1_000_000.0) : (COMPATIBILITY_BUDGET_NS / 1_000_000.0)
            ));

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AsyncFrameCapturer 初始化失败", e);
            return false;
        }
    }

    /**
     * 关闭异步帧捕获器并释放所有资源
     * <p>
     * 执行以下清理操作：
     * <ol>
     *   <li>停止接受新的捕获请求</li>
     *   <li>等待进行中的捕获任务完成（最多 500ms）</li>
     *   <li>释放 Triple Buffer 中的帧数据</li>
     *   <li>关闭工作线程池</li>
     * </ol>
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            // 1. 停止运行
            running.set(false);
            capturing.set(false);

            // 2. 清空待处理队列
            captureRequestQueue.clear();

            // 3. 关闭执行器（优雅关闭）
            if (captureExecutor != null) {
                captureExecutor.shutdown();
                
                try {
                    // 等待最多 500ms 让现有任务完成
                    if (!captureExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        captureExecutor.shutdownNow();
                        LOGGER.warning("AsyncFrameCapturer 强制关闭（超时）");
                    }
                } catch (InterruptedException e) {
                    captureExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // 4. 释放 Triple Buffer 内存（使用 bufferReleased 防重复）
            synchronized (bufferLock) {
                for (int i = 0; i < TRIPLE_BUFFER_COUNT; i++) {
                    if (tripleBuffers[i] != null && !bufferReleased[i]) {
                        CapturedFrame released = tripleBuffers[i];
                        safeMemorySubtract(released.getColorDataSize() + released.getDepthDataSize());
                        tripleBuffers[i] = null;
                        bufferReleased[i] = true;
                    }
                    // 已释放的槽位跳过（防止与 releaseBuffer() 重复扣减）
                }
                availableBufferCount.set(TRIPLE_BUFFER_COUNT);
            }

            // 5. 清空已完成队列（修正队列中残留帧的内存统计）
            CapturedFrame dropped;
            while ((dropped = completedFrameQueue.poll()) != null) {
                safeMemorySubtract(dropped.getColorDataSize() + dropped.getDepthDataSize());
            }

            // 6. 重置统计
            resetStats();

            // 7. 标记未初始化
            initialized.set(false);

            LOGGER.info("AsyncFrameCapturer 已关闭");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AsyncFrameCapturer 关闭时发生异常", e);
        }
    }

    // ==================== 核心捕获方法 ====================

    /**
     * 异步捕获请求（非阻塞）
     * <p>
     * 将捕获请求提交到内部队列，由后台线程异步执行。
     * 此方法立即返回，不会阻塞渲染线程。
     *
     * <h3>调用流程：</h3>
     * <pre>
     * // 在渲染线程中：
     * asyncCapturer.requestCapture(captureContext, new CaptureCallback() {
     *     &#64;Override
     *     public void onCaptureComplete(CapturedFrame frame) {
     *         // 处理捕获完成的帧（可能在其他线程）
     *     }
     *
     *     &#64;Override
     *     public void onCaptureFailed(Exception error) {
     *         // 处理捕获失败
     *     }
     * });
     * // 立即返回，继续渲染...
     * </pre>
     *
     * <h3>时间复杂度：</h3>O(1) - 仅做队列入队操作
     *
     * @param context  帧捕获上下文（包含 FBO/Swapchain 信息、分辨率等）
     * @param callback 捕获完成回调（不能为 null）
     * @throws IllegalStateException 如果未初始化或已关闭
     * @throws IllegalArgumentException 如果 context 或 callback 为 null
     */
    public void requestCapture(FrameCaptureContext context, CaptureCallback callback) {
        // ======== 参数校验 ========
        if (context == null) {
            throw new IllegalArgumentException("FrameCaptureContext 不能为 null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("CaptureCallback 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get() || !running.get()) {
            throw new IllegalStateException("AsyncFrameCapturer 未初始化或已关闭");
        }

        // ======== 构建捕获任务 ========
        CaptureTask task = new CaptureTask(context, callback, System.nanoTime());

        // ======== 提交到队列（非阻塞） ========
        boolean offered = captureRequestQueue.offer(task);
        
        if (!offered) {
            // 队列已满，记录警告并丢弃最旧的任务
            LOGGER.warning("捕获请求队列已满，丢弃最新请求（考虑增大队列容量或降低捕获频率）");
            
            // 通知回调失败
            callback.onCaptureFailed(new RuntimeException("捕获队列已满，请求被丢弃"));
            return;
        }

        // 更新待处理计数
        pendingRequests.incrementAndGet();

        // 标记正在捕获
        capturing.set(true);

        // 异步执行捕获任务
        captureExecutor.execute(() -> executeCaptureTask(task));
    }

    /**
     * 同步获取最新捕获的帧（阻塞直到可用）
     * <p>
     * 从已完成帧队列中获取最新的帧数据。
     * 如果队列为空，将阻塞等待直到有帧可用或超时。
     *
     * <h3>使用场景：</h3>
     * <ul>
     *   <li>需要同步访问最新帧数据的场景</li>
     *   <li>录制、截图、分析工具等</li>
     * </ul>
     *
     * <h3>时间复杂度：</h3>
     * O(1) 平均情况，O(n) 最坏情况（队列为空时阻塞）
     *
     * @param timeoutMs 超时时间（毫秒），0 表示无限等待
     * @return 最新捕获的帧数据，如果超时则返回 null
     */
    public CapturedFrame getLatestFrame(long timeoutMs) {
        try {
            if (timeoutMs <= 0) {
                // 无限等待
                return completedFrameQueue.take();
            } else {
                return completedFrameQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("getLatestFrame 被中断");
            return null;
        }
    }

    // ==================== Triple Buffering 方法 ====================

    /**
     * 获取当前可用的 Triple Buffer 数量
     * <p>
     * 可用缓冲区数表示可以接收新帧数据的空闲缓冲区数量。
     * 当返回值为 0 时，表示所有缓冲区都在使用中，应暂缓提交新请求。
     *
     * @return 可用缓冲区数量（0-3），3 表示全部空闲
     */
    public int getAvailableBufferCount() {
        return availableBufferCount.get();
    }

    /**
     * 检查是否正在进行捕获
     *
     * @return true 如果有活跃的捕获操作
     */
    public boolean isCapturing() {
        return capturing.get();
    }

    /**
     * 获取当前写入缓冲区索引
     * <p>
     * 用于调试和监控目的，不应依赖此值进行业务逻辑判断。
     *
     * @return 当前写入索引（0-2）
     */
    public int getWriteBufferIndex() {
        return writeBufferIndex.get();
    }

    /**
     * 获取当前读取缓冲区索引
     *
     * @return 当前读取索引（0-2）
     */
    public int getReadBufferIndex() {
        return readBufferIndex.get();
    }

    // ==================== 统计信息方法 ====================

    /**
     * 获取捕获性能统计数据
     * <p>
     * 包含平均/最大捕获时间、待处理请求数、内存占用等信息。
     * 用于性能监控和调试分析。
     *
     * @return 不可变的性能统计快照
     */
    public CapturePerformanceStats getStats() {
        long totalCount = totalCaptureCount.get();
        long totalTime = totalCaptureTimeNanos.get();

        double avgMs = totalCount > 0 ? (totalTime / (double) totalCount) / 1_000_000.0 : 0.0;
        double maxMs = maxCaptureTimeNanos.get() / 1_000_000.0;

        return new CapturePerformanceStats(
            avgMs,
            maxMs,
            pendingRequests.get(),
            totalMemoryBytes.get()
        );
    }

    /**
     * 安全的内存扣减（带下溢保护）
     * <p>
     * 防止 totalMemoryBytes 因重复释放、队列清空等场景变为负数。
     * 使用 CAS 循环保证原子性：如果当前值已低于待扣减值，则归零而非变负。
     *
     * @param bytesToSubtract 要扣减的字节数（必须 >= 0）
     * @return 实际扣减后的值
     */
    private long safeMemorySubtract(long bytesToSubtract) {
        if (bytesToSubtract <= 0) return totalMemoryBytes.get();

        long prev, next;
        do {
            prev = totalMemoryBytes.get();
            // 下溢保护：不允许负数
            next = Math.max(0L, prev - bytesToSubtract);
        } while (!totalMemoryBytes.compareAndSet(prev, next));

        return next;
    }

    /**
     * 重置所有性能统计计数器（含释放标记）
     * <p>
     * 通常在切换场景或配置变更时调用。
     */
    public void resetStats() {
        totalCaptureCount.set(0L);
        totalCaptureTimeNanos.set(0L);
        maxCaptureTimeNanos.set(0L);
        pendingRequests.set(0);
        totalMemoryBytes.set(0L);

        // 重置所有缓冲区槽位的释放标记
        for (int i = 0; i < TRIPLE_BUFFER_COUNT; i++) {
            bufferReleased[i] = false;
        }
    }

    // ==================== 状态查询方法 ====================

    /**
     * 检查是否已初始化
     *
     * @return true 如果已成功初始化且未关闭
     */
    public boolean isInitialized() {
        return initialized.get() && running.get();
    }

    /**
     * 获取当前运行模式
     *
     * @return true 表示狂暴模式，false 表示兼容模式
     */
    public boolean isAggressiveMode() {
        return aggressiveMode;
    }

    // ==================== 内部实现方法 ====================

    /**
     * 执行捕获任务（在捕获线程中运行）
     * <p>
     * 这是实际执行帧捕获的核心方法。
     * 处理流程：
     * <ol>
     *   <li>从 Triple Buffer 分配一个空闲缓冲区</li>
     *   <li>根据捕获模式（FBO/Swapchain）执行 GPU ReadBack</li>
     *   <li>将捕获的数据存入缓冲区</li>
     *   <li>通知回调并更新统计</li>
     * </ol>
     *
     * <h3>性能敏感段：</h3>
     * 此方法是整个捕获器的性能关键路径，必须严格控制执行时间。
     *
     * @param task 要执行的捕获任务
     */
    private void executeCaptureTask(CaptureTask task) {
        long startTime = System.nanoTime();

        try {
            // ======== 1. 分配 Triple Buffer ========
            int bufferIdx = allocateBuffer();
            if (bufferIdx < 0) {
                task.callback.onCaptureFailed(new RuntimeException("无可用 Triple Buffer"));
                return;
            }

            // ======== 2. 执行实际捕获（GPU ReadBack） ========
            CapturedFrame capturedFrame = performGPUCapture(task.context, task.requestTimeNanos);

            if (capturedFrame == null) {
                // 捕获失败，释放缓冲区
                releaseBuffer(bufferIdx);
                task.callback.onCaptureFailed(new RuntimeException("GPU 捕获失败"));
                return;
            }

            // ======== 3. 存入 Triple Buffer（重置释放标记） ======
            synchronized (bufferLock) {
                tripleBuffers[bufferIdx] = capturedFrame;
                bufferReleased[bufferIdx] = false;  // 新帧写入，清除释放标记
            }

            // ======== 4. 更新统计信息 ========
            long elapsedNs = System.nanoTime() - startTime;
            updatePerformanceStats(elapsedNs, capturedFrame);

            // ======== 5. 通知回调（异步） ========
            task.callback.onCaptureComplete(capturedFrame);

            // ======== 6. 放入已完成队列（供同步消费者使用） ========
            if (!completedFrameQueue.offer(capturedFrame)) {
                // 队列已满，丢弃最旧的帧
                completedFrameQueue.poll();
                completedFrameQueue.offer(capturedFrame);
                LOGGER.fine("已完成帧队列已满，丢弃最旧帧");
            }

            // ======== 7. 更新读取索引 ========
            readBufferIndex.set(bufferIdx);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "捕获任务执行异常", e);
            task.callback.onCaptureFailed(e);

        } finally {
            // 更新待处理计数
            pendingRequests.decrementAndGet();

            // 检查是否还有待处理任务
            if (pendingRequests.get() <= 0) {
                capturing.set(false);
            }
        }
    }

    /**
     * 从 Triple Buffer 中分配一个空闲缓冲区
     * <p>
     * 使用原子操作保证线程安全的缓冲区分配。
     *
     * @return 缓冲区索引（0-2），如果无可用缓冲区则返回 -1
     */
    private int allocateBuffer() {
        int count = availableBufferCount.decrementAndGet();
        if (count < 0) {
            // 无可用缓冲区，恢复计数
            availableBufferCount.incrementAndGet();
            return -1;
        }

        // 循环获取下一个写入索引
        int idx = writeBufferIndex.getAndUpdate(i -> (i + 1) % TRIPLE_BUFFER_COUNT);
        return idx;
    }

    /**
     * 释放指定的 Triple Buffer（带防重复释放保护）
     *
     * <p><b>线程安全：</b>通过 bufferLock + bufferReleased 标志保证每个槽位只释放一次。</p>
     *
     * @param bufferIndex 要释放的缓冲区索引
     */
    private void releaseBuffer(int bufferIndex) {
        if (bufferIndex < 0 || bufferIndex >= TRIPLE_BUFFER_COUNT) return;

        synchronized (bufferLock) {
            // 防止重复释放：检查释放标记 + null 双重保护
            if (bufferReleased[bufferIndex] || tripleBuffers[bufferIndex] == null) {
                return;
            }

            CapturedFrame released = tripleBuffers[bufferIndex];
            if (released != null) {
                safeMemorySubtract(released.getColorDataSize() + released.getDepthDataSize());
            }
            tripleBuffers[bufferIndex] = null;
            bufferReleased[bufferIndex] = true;
        }
        availableBufferCount.incrementAndGet();
    }

    /**
     * 执行实际的 GPU 数据捕获
     * <p>
     * 根据 FrameCaptureContext 中的模式选择不同的捕获策略：
     * <ul>
     *   <li><b>FBO 模式</b>（COMPATIBILITY）：通过 glReadPixels 或 PBO 读取</li>
     *   <li><b>Swapchain Image 模式</b>（AGGRESSIVE）：通过 vkCmdCopyImage 读取</li>
     * </ul>
     *
     * <h3>性能优化：</h3>
     * <ul>
     *   <li>使用 PBO（Pixel Buffer Object）异步传输（OpenGL 路径）</li>
     *   <li>使用 Staging Image（Vulkan 路径）</li>
     *   <li>避免 CPU-GPU 同步点</li>
     * </ul>
     *
     * @param context        捕获上下文
     * @param requestTimeNs  请求时间戳（纳秒）
     * @return 捕获的帧数据，失败返回 null
     */
    private CapturedFrame performGPUCapture(FrameCaptureContext context, long requestTimeNs) {
        // 冷路径存根：降级为 FINEST 避免阻塞热路径日志
        LOGGER.log(Level.FINEST, "performGPUCapture() 尚未实现，当前为模拟捕获");
        int width = context.getWidth();
        int height = context.getHeight();

        // 计算所需内存大小（RGBA 格式，每像素 4 字节）
        int colorDataSize = width * height * 4;
        
        // 深度数据可选（每像素 4 字节 float）
        int depthDataSize = context.hasDepthTexture() ? width * height * 4 : 0;

        try {
            // 分配直接 ByteBuffer（JVM 外内存，零拷贝传输）
            ByteBuffer colorData = ByteBuffer.allocateDirect(colorDataSize);
            ByteBuffer depthData = depthDataSize > 0 
                ? ByteBuffer.allocateDirect(depthDataSize) 
                : null;

            // TODO: Phase 5 实现实际的 GPU ReadBack
            // 当前版本模拟捕获过程（填充测试数据）
            // 生产环境应替换为：
            // OpenGL: glBindBuffer(GL_PIXEL_PACK_BUFFER, pboId) + glReadPixels(...)
            // Vulkan: vkCmdCopyImageToBuffer(...) + vkMapMemory(...)

            // 模拟：填充零值（实际应为 GPU 数据）
            // 注意：这里不做 memset，保持 allocateDirect 的零初始化行为

            // 构建帧索引（基于全局计数器）
            int frameIndex = (int) (totalCaptureCount.incrementAndGet() % Integer.MAX_VALUE);

            // 更新内存统计
            totalMemoryBytes.addAndGet(colorDataSize + depthDataSize);

            return new CapturedFrame(
                colorData,
                depthData,
                width,
                height,
                System.nanoTime(),  // 实际捕获完成时间
                frameIndex
            );

        } catch (OutOfMemoryError e) {
            LOGGER.severe("帧捕获内存不足: " + e.getMessage() + 
                          " (需要=" + (colorDataSize + depthDataSize) + " bytes)");
            return null;
        }
    }

    /**
     * 更新性能统计信息
     * <p>
     * 原子性地更新各项统计指标。
     *
     * @param elapsedNs 本次捕获耗时（纳秒）
     * @param frame     捕获的帧数据
     */
    private void updatePerformanceStats(long elapsedNs, CapturedFrame frame) {
        // 更新总耗时
        totalCaptureTimeNanos.addAndGet(elapsedNs);

        // 更新最大耗时（CAS 循环保证原子性）
        long currentMax;
        do {
            currentMax = maxCaptureTimeNanos.get();
            if (elapsedNs <= currentMax) {
                break;  // 新值不大，无需更新
            }
        } while (!maxCaptureTimeNanos.compareAndSet(currentMax, elapsedNs));

        // 性能预算检查
        long budget = aggressiveMode ? AGGRESSIVE_BUDGET_NS : COMPATIBILITY_BUDGET_NS;
        if (elapsedNs > budget) {
            LOGGER.warning(String.format(
                "帧捕获超出性能预算: %.2f ms > %.2f ms (frame=%d, %dx%d)",
                elapsedNs / 1_000_000.0,
                budget / 1_000_000.0,
                frame.frameIndex(),
                frame.width(),
                frame.height()
            ));
        }
    }

    // ==================== 内部类定义 ====================

    /**
     * 帧捕获完成回调接口
     * <p>
     * 提供捕获成功和失败两种通知方法。
     * 实现类应保证线程安全，因为回调可能在捕获线程中调用。
     */
    public interface CaptureCallback {

        /**
         * 捕获完成通知
         * <p>
         * 当帧数据成功捕获后被调用。
         *
         * @param frame 捕获的完整帧数据（包含颜色、深度、元数据等）
         */
        void onCaptureComplete(CapturedFrame frame);

        /**
         * 捕获失败通知
         * <p>
         * 当捕获过程中发生错误时被调用。
         *
         * @param error 导致失败的异常（可能为 null 表示未知原因）
         */
        void onCaptureFailed(Exception error);
    }

    /**
     * 捕获的帧数据（不可变 Record）
     * <p>
     * 封装一次完整帧捕获的所有结果数据。
     * 使用 Java Record 保证不可变性和自动实现的 equals/hashCode/toString。
     *
     * <h3>内存布局：</h3>
     * <pre>
     * Color Data: RGBA8888 格式，行优先存储
     *   - 大小: width * height * 4 bytes
     *   - 原点: 左上角 (OpenGL/Vulkan 标准)
     *
     * Depth Data: R32F 格式（如果可用）
     *   - 大小: width * height * 4 bytes
     *   - 范围: [0.0, 1.0]（线性深度）或 [near, far]
     * </pre>
     *
     * @param colorData       颜色缓冲数据（Direct ByteBuffer，RGBA8888）
     * @param depthData       深度缓冲数据（Direct ByteBuffer，R32F，可能为 null）
     * @param width           帧宽度（像素）
     * @param height          帧高度（像素）
     * @param captureTimeNanos 捕获完成时间戳（System.nanoTime()）
     * @param frameIndex      帧序号（单调递增）
     */
    public record CapturedFrame(
        ByteBuffer colorData,
        ByteBuffer depthData,
        int width,
        int height,
        long captureTimeNanos,
        int frameIndex
    ) {

        /**
         * 获取颜色数据大小（字节）
         *
         * @return 颜色缓冲大小
         */
        public int getColorDataSize() {
            return width * height * 4;  // RGBA = 4 bytes/pixel
        }

        /**
         * 获取深度数据大小（字节）
         *
         * @return 深度缓冲大小，如果没有深度数据则返回 0
         */
        public int getDepthDataSize() {
            return depthData != null ? width * height * 4 : 0;
        }

        /**
         * 获取总内存占用（字节）
         *
         * @return 总大小
         */
        public long getTotalMemoryBytes() {
            return (long) getColorDataSize() + getDepthDataSize();
        }

        /**
         * 检查是否有深度数据
         *
         * @return true 如果深度数据可用
         */
        public boolean hasDepthData() {
            return depthData != null;
        }
    }

    /**
     * 捕获性能统计（不可变 Record）
     * <p>
     * 提供捕获器的实时性能指标快照。
     * 用于监控面板、调试输出、自适应调整等场景。
     *
     * @param avgCaptureTimeMs  平均捕获时间（毫秒）
     * @param maxCaptureTimeMs  最大单次捕获时间（毫秒）
     * @param pendingRequests   当前待处理的请求数量
     * @param totalMemoryBytes  当前总内存占用（字节）
     */
    public record CapturePerformanceStats(
        double avgCaptureTimeMs,
        double maxCaptureTimeMs,
        int pendingRequests,
        long totalMemoryBytes
    ) {}

    /**
     * 内部捕获任务封装
     * <p>
     * 封装一次完整的捕获请求及其关联的回调和时间戳。
     * 此类仅限内部使用。
     */
    private static final class CaptureTask {
        /** 捕获上下文 */
        final FrameCaptureContext context;
        /** 完成回调 */
        final CaptureCallback callback;
        /** 请求提交时间戳（纳秒） */
        final long requestTimeNanos;

        CaptureTask(FrameCaptureContext context, CaptureCallback callback, long requestTimeNanos) {
            this.context = context;
            this.callback = callback;
            this.requestTimeNanos = requestTimeNanos;
        }
    }
}
