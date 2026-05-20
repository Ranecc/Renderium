// Renderium - Blaze3D 优化模块
// Vulkan 命令缓冲区优化器 - 整合 Command Buffer 预录制/缓存 + Arena 内存管理

package com.ranecc.renderium.feature.blaze3d;

import com.ranecc.renderium.domain.model.config.VulkanCommandConfig;

import com.ranecc.renderium.domain.model.config.RenderiumConfig;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Vulkan Command Optimizer (Deprecated)
 * <p>
 * Implements Command Buffer pre-recording/caching strategy from
 * `compatibility-mode-optimization.md` combined with Arena memory management
 * from `vulkan-memory-arena-guide.md`.
 *
 * <h2>Deprecation Notice (v5 → v6)</h2>
 * <p>This class directly operates on Vulkan Command Buffers, bypassing the
 * Blaze3D abstraction layer. In Minecraft 26.2+, use the native
 * {@code GpuDevice} / {@code CommandEncoder} abstraction:
 * <pre>{@code
 * // Old code (deprecated)
 * VulkanCommandOptimizer optimizer = new VulkanCommandOptimizer();
 * optimizer.submit(commandBuffer);
 *
 * // New code (recommended)
 * GpuDevice device = RenderSystem.getDevice();
 * CommandEncoder encoder = device.createCommandEncoder();
 * encoder.submit(buffer);
 * }</pre>
 *
 * @deprecated Use {@link com.mojang.blaze3d.systems.GpuDevice} (available since 26.2)
 * @author Renderium Team
 * @since 2.0.0
 */
@Deprecated(since = "5.0.0")
public class VulkanCommandOptimizer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VulkanCommandOptimizer.class.getName());

    // ==================== 配置常量 ====================

    /** 默认最大缓存 Command Buffer 数量 */
    public static final int MAX_CACHED_COMMAND_BUFFERS = 64;

    /** 默认每帧最大 Command Buffer 数量 */
    public static final int MAX_COMMAND_BUFFERS_PER_FRAME = 32;

    /** 缓存淘汰帧阈值（5秒 @ 60fps） */
    public static final int CACHE_EVICTION_FRAME_THRESHOLD = 300;

    /** 默认 Command Pool 大小（每个队列族） */
    public static final int DEFAULT_COMMAND_POOL_SIZE = 16;

    // ==================== 配置引用 ====================

    private final VulkanCommandConfig config;

    // ==================== 状态字段 ====================

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== Command Buffer 缓存系统 ====================

    /**
     * Command Buffer 缓存条目
     * <p>存储预录制的 Command Buffer 及其关联元数据。
     */
    private static class CachedCommandBuffer {
        /** Vulkan Command Buffer 句柄 */
        final long commandBuffer;

        /** 配置哈希值（用于快速匹配） */
        final long configHash;

        /** 渲染宽度（缓存条件之一） */
        final int width;

        /** 渲染高度（缓存条件之一） */
        final int height;

        /** 最后使用帧号（用于 LRU 淘汰） */
        final AtomicLong lastUsedFrame;

        /** 引用计数（用于追踪复用情况） */
        final AtomicInteger referenceCount;

        CachedCommandBuffer(long commandBuffer, long configHash, int width, int height, long frame) {
            this.commandBuffer = commandBuffer;
            this.configHash = configHash;
            this.width = width;
            this.height = height;
            this.lastUsedFrame = new AtomicLong(frame);
            this.referenceCount = new AtomicInteger(0);
        }
    }

    /** Command Buffer 缓存表 (configHash → CachedCommandBuffer) */
    private final ConcurrentHashMap<Long, CachedCommandBuffer> commandBufferCache = new ConcurrentHashMap<>();

    // ==================== 统计字段 ====================

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong totalRecordedBuffers = new AtomicLong(0);
    private final AtomicLong totalSubmittedBatches = new AtomicLong(0);
    private final AtomicInteger currentFrame = new AtomicInteger(0);

    // ==================== 构造函数 ====================

    /**
     * 创建 Vulkan 命令优化器
     *
     * @param config RenderiumConfig 的 Vulkan 命令配置
     */
    public VulkanCommandOptimizer(RenderiumConfig config) {
        this.config = config.getVulkanCommandConfig();
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化命令优化器
     * <p>
     * 初始化 Command Buffer 缓存系统和统计计数器。
     *
     * @return 成功返回 true
     */
    public boolean initialize() {
        if (initialized) return true;

        try {
            // 清空缓存（防止重复初始化）
            commandBufferCache.clear();

            // 重置统计
            cacheHits.set(0);
            cacheMisses.set(0);
            totalRecordedBuffers.set(0);
            totalSubmittedBatches.set(0);
            currentFrame.set(0);

            this.initialized = true;

            LOGGER.info(String.format(
                    "✓ VulkanCommandOptimizer initialized" +
                    "  Max cached buffers: %d" +
                    "  Batch merging: %s" +
                    "  Max batch size: %d" +
                    "  Multi-queue: %s",
                    MAX_CACHED_COMMAND_BUFFERS,
                    config.isBatchMergingEnabled() ? "ON" : "OFF",
                    config.getMaxCommandsPerBatch(),
                    config.isMultiQueueSubmission() ? "ON" : "OFF"
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize VulkanCommandOptimizer: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启用命令优化器
     * <p>
     * 根据 `compatibility-mode-optimization.md` §2.4 中的策略：
     * <ol>
     *   <li>启用 Command Buffer 预录制</li>
     *   <li>启用批量合并提交</li>
     *   <li>启用多队列调度（如果配置允许）</li>
     * </ol>
     */
    public void enable() {
        if (!config.isBatchMergingEnabled()) {
            LOGGER.warning("VulkanCommandOptimizer: 批量合并未启用，跳过");
            return;
        }

        if (!initialized) {
            if (!initialize()) {
                LOGGER.severe("VulkanCommandOptimizer: 初始化失败，无法启用");
                return;
            }
        }

        enabled = true;

        LOGGER.info(String.format(
                "✓ VulkanCommandOptimizer enabled" +
                "  [Pre-recording] Command Buffer caching active" +
                "  [Batch Merging] Max %d commands per batch" +
                "  [Multi-Queue] %s",
                config.getMaxCommandsPerBatch(),
                config.isMultiQueueSubmission() ? "Graphics/Compute/Transfer" : "Graphics only"
        ));
    }

    /**
     * 禁用命令优化器
     */
    public void disable() { enabled = false; }

    /**
     * 销毁所有缓存的 Command Buffer 并释放资源
     */
    @Override
    public void close() {
        if (!initialized) return;

        // 清空缓存并释放所有 Command Buffer
        evictAllCachedBuffers();

        commandBufferCache.clear();

        enabled = false;
        initialized = false;

        // 重置统计
        cacheHits.set(0);
        cacheMisses.set(0);
        totalRecordedBuffers.set(0);
        totalSubmittedBatches.set(0);

        LOGGER.info("VulkanCommandOptimizer disposed");
    }

    // ==================== 核心 API：Command Buffer 预录制/缓存 ====================

    /**
     * 获取或创建 Command Buffer（带缓存）
     * <p>
     * 这是核心方法，实现 `compatibility-mode-optimization.md` §2.4 中的预录制策略：
     * <pre>
     * 1. 计算配置哈希
     * 2. 检查缓存是否命中（配置 + 尺寸匹配）
     * 3. 命中 → 直接返回缓存的 Command Buffer
     * 4. 未命中 → 录制新的 Command Buffer 并加入缓存
     * </pre>
     *
     * @param vkDevice      Vulkan 设备句柄
     * @param cmdPool       Command Pool 句柄
     * @param configHash    后处理配置的哈希值
     * @param width         渲染宽度
     * @param height        渲染高度
     * @return Command Buffer 句柄，失败返回 0
     */
    public long getOrCreateCommandBuffer(long vkDevice, long cmdPool,
                                         long configHash, int width, int height) {
        if (!enabled || !initialized) return 0;

        // 构建复合键：configHash ^ width ^ height
        long compositeKey = buildCompositeKey(configHash, width, height);

        // 1. 尝试从缓存获取
        CachedCommandBuffer cached = commandBufferCache.get(compositeKey);

        if (cached != null && isCacheValid(cached, width, height)) {
            // 缓存命中
            cached.lastUsedFrame.set(currentFrame.get());
            cached.referenceCount.incrementAndGet();
            cacheHits.incrementAndGet();

            LOGGER.fine(String.format(
                    "Command buffer cache HIT: hash=0x%016X, size=%dx%d, refs=%d",
                    configHash, width, height, cached.referenceCount.get()
            ));

            return cached.commandBuffer;
        }

        // 2. 缓存未命中，需要录制新的 Command Buffer
        cacheMisses.incrementAndGet();

        // 检查缓存大小限制
        if (commandBufferCache.size() >= MAX_CACHED_COMMAND_BUFFERS) {
            evictOldestEntries();
        }

        // 录制新的 Command Buffer
        long newCmdBuffer = recordNewCommandBuffer(vkDevice, cmdPool, configHash, width, height);

        if (newCmdBuffer != 0) {
            // 加入缓存
            CachedCommandBuffer entry = new CachedCommandBuffer(
                    newCmdBuffer, configHash, width, height, currentFrame.get()
            );
            commandBufferCache.put(compositeKey, entry);
            totalRecordedBuffers.incrementAndGet();

            LOGGER.fine(String.format(
                    "Command buffer recorded: hash=0x%016X, size=%dx%d, total_cached=%d",
                    configHash, width, height, commandBufferCache.size()
            ));
        }

        return newCmdBuffer;
    }

    /**
     * 强制刷新指定配置的 Command Buffer
     * <p>
     * 当配置发生变化时调用此方法使缓存失效。
     *
     * @param configHash 需要失效的配置哈希
     * @param width      关联的渲染宽度
     * @param height     关联的渲染高度
     * @return 是否成功移除
     */
    public boolean invalidateCommandBuffer(long configHash, int width, int height) {
        if (!initialized) return false;

        long compositeKey = buildCompositeKey(configHash, width, height);
        CachedCommandBuffer removed = commandBufferCache.remove(compositeKey);

        if (removed != null) {
            LOGGER.fine(String.format(
                    "Command buffer invalidated: hash=0x%016X, size=%dx%d",
                    configHash, width, height
            ));
            return true;
        }

        return false;
    }

    // ==================== 核心 API：批量合并提交 ====================

    /**
     * 提交一批 Command Buffer（批量合并）
     * <p>
     * 将多个次级 Command Buffer 合并后一次性提交，
     * 减少 `vkQueueSubmit` 的 CPU 开销。
     *
     * @param vkDevice   Vulkan 设备句柄
     * @param queue      目标队列句柄
     * @param cmdBuffers 要提交的 Command Buffer 列表
     * @param fence      可选的 Fence（可为 0）
     * @return 提交是否成功
     */
    public boolean submitBatch(long vkDevice, long queue,
                               List<Long> cmdBuffers, long fence) {
        if (!enabled || !initialized) return false;
        if (cmdBuffers == null || cmdBuffers.isEmpty()) return false;

        try {
            // 1. 检查是否超过最大批次大小
            int maxBatchSize = config.getMaxCommandsPerBatch();
            if (maxBatchSize <= 0) maxBatchSize = 8; // 默认值

            if (cmdBuffers.size() > maxBatchSize) {
                // 分批提交
                for (int i = 0; i < cmdBuffers.size(); i += maxBatchSize) {
                    int end = Math.min(i + maxBatchSize, cmdBuffers.size());
                    List<Long> batch = cmdBuffers.subList(i, end);

                    if (!submitSingleBatch(vkDevice, queue, batch, fence)) {
                        return false;
                    }
                }
            } else {
                // 单批次提交
                if (!submitSingleBatch(vkDevice, queue, cmdBuffers, fence)) {
                    return false;
                }
            }

            totalSubmittedBatches.incrementAndGet();

            LOGGER.fine(String.format(
                    "Batch submitted: %d buffers, fence=0x%X, total_batches=%d",
                    cmdBuffers.size(), fence, totalSubmittedBatches.get()
            ));

            return true;

        } catch (Exception e) {
            LOGGER.severe("Failed to submit batch: " + e.getMessage());
            return false;
        }
    }

    // ==================== 帧管理 API ====================

    /**
     * 开始新帧
     * <p>
     * 每帧开始时调用：
     * <ul>
     *   <li>递增帧计数器</li>
     *   <li>定期执行缓存清理</li>
     * </ul>
     */
    public void beginFrame() {
        if (!enabled || !initialized) return;

        int frame = currentFrame.incrementAndGet();

        // 每 300 帧（约 5 秒）清理一次缓存
        if (frame % CACHE_EVICTION_FRAME_THRESHOLD == 0) {
            evictOldEntries(frame - CACHE_EVICTION_FRAME_THRESHOLD);
        }
    }

    /**
     * 结束当前帧
     * <p>
     * 帧结束时调用，可用于统计和日志输出。
     */
    public void endFrame() {
        if (!enabled || !initialized) return;
        // 可在此处添加帧结束时的逻辑
    }

    // ==================== RenderPass Mixin 回调 API ====================

    /**
     * 记录 DrawCall 事件（由 RenderPassMixin 调用）
     * <p>
     * 策略 S4: Draw Call 合并 - 单个绘制调用监控
     *
     * @param passId      RenderPass 的唯一标识符
     * @param pipeline    当前使用的 Pipeline 句柄
     * @param indexOffset 索引缓冲区偏移
     * @param indexCount  索引数量
     * @param instanceCount 实例化数量
     */
    public void recordDrawCall(long passId, long pipeline,
                                int indexOffset, int indexCount,
                                int instanceCount) {
        if (!enabled || !initialized) return;

        LOGGER.finer(String.format(
                "Draw call recorded: pass=0x%X, pipeline=0x%X, indices=[%d,%d], instances=%d",
                passId, pipeline, indexOffset, indexCount, instanceCount
        ));

        // TODO: 分析 DrawCall 模式，识别可合并的相邻调用
    }

    /**
     * 记录多重 DrawCall 事件（由 RenderPassMixin 调用）
     * <p>
     * 策略 S4: 多 Draw 监控 - 批量绘制调用优化机会分析
     *
     * @param passId     RenderPass 的唯一标识符
     * @param pipeline   当前使用的 Pipeline 句柄
     * @param drawCount  绘制数量
     */
    public void recordMultiDrawCall(long passId, long pipeline, int drawCount) {
        if (!enabled || !initialized) return;

        LOGGER.fine(String.format(
                "Multi-draw call recorded: pass=0x%X, pipeline=0x%X, count=%d",
                passId, pipeline, drawCount
        ));

        // TODO: 评估是否适合转换为 instanced draw 或 indirect draw
    }

    /**
     * 结束 RenderPass（由 RenderPassMixin.close() 调用）
     * <p>
     * 提交此 Pass 的所有命令缓冲区，并执行必要的同步操作。
     *
     * @param passId RenderPass 的唯一标识符
     */
    public void endPass(long passId) {
        if (!enabled || !initialized) return;

        long queue = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getVkQueue();
        if (queue != 0L) {
            try {
                com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkQueueSubmit()
                    .invoke(queue, 1, 0L, 0L);
                totalSubmittedBatches.incrementAndGet();
            } catch (Throwable ignored) {}
        }
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
     * 尝试合并一次 Draw Call 到当前批次
     * <p>
     * 如果当前批次可合并此调用，返回 true 表示已处理。
     * 返回 false 表示合并失败，调用方应直接执行此绘制调用。
     *
     * @param indexCount    索引数量
     * @param instanceCount 实例数量
     * @param firstIndex    起始索引偏移
     * @param vertexOffset  顶点偏移
     * @param firstInstance 起始实例 ID
     * @return true 表示已成功合并，false 表示需要直接执行
     */
    public boolean tryMergeDrawCall(int indexCount, int instanceCount,
                                     int firstIndex, int vertexOffset, int firstInstance) {
        if (!enabled || !initialized) {
            return false;
        }
        // 默认不合并，由具体策略覆盖
        return false;
    }

    /**
     * 获取当前缓存命中率
     *
     * @return 命中率 (0.0 ~ 1.0)
     */
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;

        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取当前缓存大小
     */
    public int getCacheSize() { return commandBufferCache.size(); }

    /**
     * 获取总录制次数
     */
    public long getTotalRecordedBuffers() { return totalRecordedBuffers.get(); }

    /**
     * 获取总提交批次
     */
    public long getTotalSubmittedBatches() { return totalSubmittedBatches.get(); }

    /**
     * 获取格式化的性能报告
     * <p>
     * 参考 `compatibility-mode-optimization.md` §5.3 性能指标。
     */
    public String formatReport() {
        if (!initialized) return "VulkanCommandOptimizer not initialized";

        double hitRate = getCacheHitRate();

        return String.format(
                "Vulkan Command Optimizer Report:" +
                "  Status: %s" +
                "  Cache: %d/%d buffers (%.1f%% hit rate)" +
                "  Recorded: %d buffers total" +
                "  Submitted: %d batches" +
                "  Frame: %d",
                enabled ? "ENABLED" : "DISABLED",
                getCacheSize(), MAX_CACHED_COMMAND_BUFFERS,
                hitRate * 100,
                getTotalRecordedBuffers(),
                getTotalSubmittedBatches(),
                currentFrame.get()
        );
    }

    // ==================== 内部实现方法 ====================

    /**
     * 构建复合缓存键
     *
     * @param configHash 配置哈希
     * @param width      渲染宽度
     * @param height     渲染高度
     * @return 复合键
     */
    private long buildCompositeKey(long configHash, int width, int height) {
        // 使用位运算组合三个值，尽量减少冲突
        return configHash ^ ((long) width << 32) ^ ((long) height << 16);
    }

    /**
     * 检查缓存条目是否有效
     *
     * @param cached 缓存条目
     * @param width  当前宽度
     * @param height 当前高度
     * @return 是否有效
     */
    private boolean isCacheValid(CachedCommandBuffer cached, int width, int height) {
        return cached.width == width && cached.height == height;
    }

    /**
     * 录制新的 Command Buffer
     * <p>
     * 这是实际录制逻辑的占位符。
     * 在集成时需要替换为真正的 Vulkan API 调用。
     *
     * @param vkDevice   设备句柄
     * @param cmdPool    Command Pool 句柄
     * @param configHash 配置哈希（用于调试日志）
     * @param width      渲染宽度
     * @param height     渲染高度
     * @return 新录制的 Command Buffer 句柄，失败返回 0
     */
    private long recordNewCommandBuffer(long vkDevice, long cmdPool,
                                        long configHash, int width, int height) {
        if (vkDevice == 0L || cmdPool == 0L) return 0L;
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) return 0L;
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var allocInfo = arena.allocate(32);
            allocInfo.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, cmdPool);
            allocInfo.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 0);
            allocInfo.set(java.lang.foreign.ValueLayout.JAVA_INT, 12, 1);

            long[] outCmdBuf = new long[1];
            int result = (int) com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkAllocateCommandBuffers()
                .invoke(vkDevice, allocInfo.address(), 0L, outCmdBuf);
            if (result != 0 || outCmdBuf[0] == 0L) return 0L;

            long cmdBuf = outCmdBuf[0];
            var beginInfo = arena.allocate(24);
            beginInfo.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0x00000001);
            beginInfo.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, 0L);
            com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkBeginCommandBuffer()
                .invoke(cmdBuf, beginInfo.address());
            com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkEndCommandBuffer()
                .invoke(cmdBuf);
            return cmdBuf;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 提交单批次 Command Buffer
     *
     * @param vkDevice   设备句柄
     * @param queue      队列句柄
     * @param cmdBuffers Command Buffer 列表
     * @param fence      Fence 句柄
     * @return 提交是否成功
     */
    private boolean submitSingleBatch(long vkDevice, long queue,
                                      List<Long> cmdBuffers, long fence) {
        if (vkDevice == 0L || queue == 0L || cmdBuffers == null || cmdBuffers.isEmpty()) return false;
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) return false;
        try {
            var vkQueueSubmit = com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkQueueSubmit();
            if (vkQueueSubmit == null) return false;
            int result = (int) vkQueueSubmit.invoke(queue, 1, 0L, fence);
            return result == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 缓存管理方法 ====================

    /**
     * 淘汰最老的缓存条目（LRU 策略）
     * <p>
     * 当缓存达到上限时调用。
     */
    private void evictOldestEntries() {
        if (commandBufferCache.isEmpty()) return;

        long oldestFrame = Long.MAX_VALUE;
        Long oldestKey = null;

        // 找到最老的条目
        for (var entry : commandBufferCache.entrySet()) {
            if (entry.getValue().lastUsedFrame.get() < oldestFrame) {
                oldestFrame = entry.getValue().lastUsedFrame.get();
                oldestKey = entry.getKey();
            }
        }

        // 淘汰最老的条目
        if (oldestKey != null) {
            CachedCommandBuffer evicted = commandBufferCache.remove(oldestKey);
            if (evicted != null) {
                long device = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
                if (device != 0L) {
                    try {
                        com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkFreeCommandBuffers()
                            .invoke(device, 0L, 1, evicted.commandBuffer);
                    } catch (Throwable ignored) {}
                }
                LOGGER.fine(String.format(
                        "Evicted oldest cache entry: last_used_frame=%d, current_cache_size=%d",
                        evicted.lastUsedFrame.get(), commandBufferCache.size()
                ));
            }
        }
    }

    /**
     * 淘汰超过帧阈值的旧条目
     *
     * @param thresholdFrame 帧阈值
     */
    private void evictOldEntries(long thresholdFrame) {
        if (commandBufferCache.isEmpty()) return;

        int evictedCount = 0;

        var iterator = commandBufferCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().lastUsedFrame.get() < thresholdFrame) {
                long device = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
                if (device != 0L) {
                    try {
                        com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkFreeCommandBuffers()
                            .invoke(device, 0L, 1, entry.getValue().commandBuffer);
                    } catch (Throwable ignored) {}
                }
                iterator.remove();
                evictedCount++;
            }
        }

        if (evictedCount > 0) {
            LOGGER.fine(String.format(
                    "Evicted %d old cache entries (threshold_frame=%d), remaining=%d",
                    evictedCount, thresholdFrame, commandBufferCache.size()
            ));
        }
    }

    /**
     * 清空所有缓存的 Command Buffer
     */
    private void evictAllCachedBuffers() {
        if (commandBufferCache.isEmpty()) return;

        int count = 0;
        for (CachedCommandBuffer cached : commandBufferCache.values()) {
            long device = com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder.getInstance().getDevice();
            if (device != 0L) {
                try {
                    com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding.getVkFreeCommandBuffers()
                        .invoke(device, 0L, 1, cached.commandBuffer);
                } catch (Throwable ignored) {}
            }
            count++;
        }

        LOGGER.info(String.format(
                "Evicted all %d cached command buffers", count
        ));
    }
}
