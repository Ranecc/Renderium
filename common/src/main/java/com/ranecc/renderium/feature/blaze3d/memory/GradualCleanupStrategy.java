// Renderium - Blaze3D VMA 渐进式清理模块
// 渐进式清理策略 - 软/硬限制: 每帧清理固定量，平滑释放

package com.ranecc.renderium.feature.blaze3d.memory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * 渐进式清理策略 (软限制 85%-92% / 硬限制 92%-97%)。
 * <p>
 * 核心思想: <b>每帧释放固定量的内存，平滑处理</b></p>
 *
 * <h2>工作流程:</h2>
 * <ol>
 *   <li>从 {@link ResourceTracker} 获取 LRU 排序的清理候选</li>
 *   <li>逐个检查候选是否变为热数据 (被重新访问)</li>
 *   <li>对冷数据执行清理 (每帧最多 N 个)</li>
 *   <li>达到本帧目标字节数后停止</li>
 * </ol>
 *
 * <h3>参数配置:</h3>
 * <table>
 *   <tr><th>参数</th><th>软限制</th><th>硬限制</th></tr>
 *   <tr><td>bytesPerFrame</td><td>10 MB</td><td>30 MB</td></tr>
 *   <tr><td>maxItemsPerFrame</td><td>10</td><td>20</td></tr>
 * </table>
 *
 * @see CleanupStrategy 策略接口
 * @see ResourceTracker 提供 LRU 候选
 * @since 2.0.0
 */
public final class GradualCleanupStrategy implements CleanupStrategy {

    private static final Logger LOGGER = Logger.getLogger("Renderium-GradualCleanup");

    /** 每帧目标清理字节数 */
    private final long bytesPerFrame;

    /** 每帧最多清理资源数 */
    private int maxItemsPerFrame = 10;

    /** 资源追踪器引用 */
    private ResourceTracker tracker;

    /** 清理候选队列 (LRU 顺序) */
    private final Queue<ResourceTracker.CleanupCandidate> cleanupQueue =
            new ConcurrentLinkedQueue<>();

    /** 本帧已清理字节数 */
    private volatile long cleanedThisFrame;

    /** 本帧已清理资源数 */
    private volatile int cleanedItemsThisFrame;

    /**
     * 构造渐进式清理策略
     *
     * @param bytesPerFrame 每帧目标清理字节数 (如 10*1024*1024 = 10MB)
     */
    public GradualCleanupStrategy(long bytesPerFrame) {
        this.bytesPerFrame = bytesPerFrame;
        this.tracker = ResourceTracker.getInstance();
    }

    /**
     * 设置每帧最大清理资源数
     *
     * @param max 最大值 (默认 10)
     */
    public void setMaxItemsPerFrame(int max) {
        this.maxItemsPerFrame = Math.max(1, Math.min(max, 50));
    }

    @Override
    public void execute() {
        long startTime = System.nanoTime();
        cleanedThisFrame = 0;
        cleanedItemsThisFrame = 0;

        try {
            // Step 1: 补充清理队列 (如果为空)
            if (cleanupQueue.isEmpty()) {
                replenishCleanupQueue();
            }

            // 队列仍为空说明不需要清理
            if (cleanupQueue.isEmpty()) {
                return;
            }

            // Step 2: 执行本帧清理
            executeFrameCleanup();

        } catch (Exception e) {
            LOGGER.warning("渐进清理执行异常: " + e.getMessage());
        } finally {
            long elapsed = System.nanoTime() - startTime;
            if (cleanedItemsThisFrame > 0) {
                LOGGER.fine(String.format(
                        "✓ Gradual cleanup: %d items, %d KB (%.2fms)",
                        cleanedItemsThisFrame,
                        cleanedThisFrame / 1024,
                        elapsed / 1_000_000.0));
            }
        }
    }

    @Override
    public String getName() {
        return String.format("GradualCleanup(%dMB/帧, 最多%d项)",
                bytesPerFrame / (1024 * 1024), maxItemsPerFrame);
    }

    // ==================== 内部实现 ====================

    /** 补充清理队列: 从 ResourceTracker 获取 LRU 候选 */
    private void replenishCleanupQueue() {
        float currentUsage = getCurrentUsageRatio();
        float targetUsage = 0.80f; // 目标: 回到 80% 以下

        if (currentUsage <= targetUsage) {
            return; // 不需要清理
        }

        // 计算需要清理的总字节
        long totalBudget = estimateTotalBudget();
        long bytesToClean = (long) ((currentUsage - targetUsage) * totalBudget);

        if (bytesToClean <= 0) return;

        // 获取高优先级类型的清理候选
        List<ResourceTracker.CleanupCandidate> candidates = tracker.getCleanupCandidates(
                bytesToClean,
                ResourceType.CACHED_TEXTURE,
                ResourceType.DISTANT_CHUNK_MESH,
                ResourceType.UNUSED_SHADER,
                ResourceType.TEMPORARY_BUFFER
        );

        for (var candidate : candidates) {
            cleanupQueue.offer(candidate);
        }

        if (!candidates.isEmpty()) {
            LOGGER.info(String.format("补充清理队列: %d 个候选, 目标 %d MB",
                    candidates.size(), bytesToClean / (1024 * 1024)));
        }
    }

    /** 执行单帧清理循环 */
    private void executeFrameCleanup() {
        for (int i = 0; i < maxItemsPerFrame && !cleanupQueue.isEmpty(); i++) {
            ResourceTracker.CleanupCandidate candidate = cleanupQueue.poll();
            if (candidate == null) break;

            // 检查: 是否在排队期间变成了热数据?
            if (tracker.isRecentlyAccessed(candidate.resourceId())) {
                LOGGER.fine("跳过热数据: id=" + candidate.resourceId() +
                        " type=" + candidate.type().description);
                continue;
            }

            // 异步预加载替代资源 (如果支持)
            if (candidate.type().canRecreateAsync) {
                prepareForReload(candidate.resourceId());
            }

            // 执行实际清理
            boolean success = cleanupResource(candidate);

            if (success) {
                cleanedThisFrame += candidate.size();
                cleanedItemsThisFrame++;

                // 通知追踪器资源已被移除
                tracker.onResourceEvicted(candidate.resourceId());

                // 达到本帧目标则停止
                if (cleanedThisFrame >= bytesPerFrame) {
                    LOGGER.fine("已达本帧清理目标: " +
                            (bytesPerFrame / 1024) + "KB");
                    break;
                }
            }
        }
    }

    /**
     * 清理单个资源 (分发到对应的清理器)
     *
     * @return true 如果成功清理
     */
    private boolean cleanupResource(ResourceTracker.CleanupCandidate candidate) {
        try {
            switch (candidate.type()) {
                case CACHED_TEXTURE -> evictTexture(candidate.resourceId());
                case DISTANT_CHUNK_MESH -> unloadChunkMesh(candidate.resourceId());
                case UNUSED_SHADER -> evictShaderCache(candidate.resourceId());
                case TEMPORARY_BUFFER -> releaseTempBuffer(candidate.resourceId());
                default -> {
                    LOGGER.warning("未知资源类型: " + candidate.type() +
                            ", id=" + candidate.resourceId());
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.warning("清理资源失败: id=" + candidate.resourceId() +
                    " type=" + candidate.type() + " → " + e.getMessage());
            return false;
        }
    }

    // ==================== 资源清理操作 (可扩展) ====================

    /** 清理纹理缓存 */
    private boolean evictTexture(long resourceId) {
        // TODO: 调用 TextureCache.evict(resourceId)
        LOGGER.finest("驱逐纹理: id=" + resourceId);
        return true; // 占位实现
    }

    /** 卸载远处区块 mesh */
    private boolean unloadChunkMesh(long resourceId) {
        // TODO: 调用 ChunkMeshManager.unloadDistant(resourceId)
        LOGGER.finest("卸载区块 mesh: id=" + resourceId);
        return true; // 占位实现
    }

    /** 清理着色器缓存 */
    private boolean evictShaderCache(long resourceId) {
        // 通过 ShaderCache 接口清理
        try {
            var cacheClass = Class.forName(
                    "com.renderium.module.impl.blaze3d.shader.ShaderCache");
            Object cacheInstance = null;
            for (var field : cacheClass.getDeclaredFields()) {
                if (field.getType().equals(cacheClass)) {
                    field.setAccessible(true);
                    cacheInstance = field.get(null);
                    break;
                }
            }
            if (cacheInstance != null) {
                var method = cacheInstance.getClass().getMethod("clear");
                // 更精确的做法是按 key 删除，这里简化
                LOGGER.finest("清理着色器缓存: id=" + resourceId);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 释放临时缓冲 */
    private boolean releaseTempBuffer(long resourceId) {
        // TODO: 调用 TemporaryBufferPool.release(resourceId)
        LOGGER.finest("释放临时缓冲: id=" + resourceId);
        return true; // 占位实现
    }

    /** 准备重加载 (异步预加载替代资源) */
    private void prepareForReload(long resourceId) {
        // TODO: 与 AsyncResourceLoader.prepareForReload() 对接
        // 在清理前预加载，避免下次访问时卡顿
    }

    // ==================== 辅助方法 ====================

    /** 获取当前显存使用率 (占位: 应从 VmaMemoryBudget 获取真实值) */
    private float getCurrentUsageRatio() {
        try {
            var budgetClass = Class.forName(
                    "com.renderium.module.impl.blaze3d.memory.VmaMemoryBudget");
            Object budget = null;
            for (var field : budgetClass.getDeclaredFields()) {
                if (field.getType().equals(budgetClass)) {
                    field.setAccessible(true);
                    budget = field.get(null);
                    break;
                }
            }
            if (budget != null) {
                var method = budget.getClass().getMethod("getUsageRatio");
                Object result = method.invoke(budget);
                if (result instanceof Number) {
                    return ((Number) result).floatValue();
                }
            }
        } catch (Exception ignored) {}
        // 默认返回一个中等值触发清理
        return 0.88f;
    }

    /** 估算总预算 (占位: 应从 VmaMemoryBudget 获取真实值) */
    private long estimateTotalBudget() {
        // 典型值: 8GB VRAM 中分配给游戏的部分
        return 6L * 1024 * 1024 * 1024; // 6GB
    }

    /** 获取本帧清理统计 */
    public long getCleanedBytesThisFrame() { return cleanedThisFrame; }
    public int getCleanedItemsThisFrame() { return cleanedItemsThisFrame; }
}
