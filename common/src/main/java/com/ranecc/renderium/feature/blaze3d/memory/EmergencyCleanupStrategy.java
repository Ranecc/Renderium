// Renderium - Blaze3D VMA 渐进式清理模块
// 紧急清理策略 - 临界状态(≥97%): 分批强制清理

package com.ranecc.renderium.feature.blaze3d.memory;

import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.VulkanBufferHelper;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper;

/**
 * 紧急清理策略 (临界状态: ≥97%)。
 * <p>
 * 最后的手段，但仍尽量平滑:
 * <ul>
 *   <li>暂停非关键资源加载</li>
 *   <li>分 5 批快速清理 (每批间隔 1 帧)</li>
 *   <li>最后触发一次 GC</li>
 * </ul></p>
 *
 * <h3>执行流程:</h3>
 * <pre>
 * Emergency 触发 (≥97%)
 *   │
 *   ├─ 暂停非关键 AsyncResourceLoader
 *   │
 *   ├─ Batch 0: 清理高优先级资源 (纹理/临时缓冲) [当前帧]
 *   ├─ Batch 1: 继续清理 [下一帧]
 *   ├─ Batch 2: 扩展到中优先级 [再下一帧]
 *   ├─ Batch 3: 包含实体模型 [再下一帧]
 *   ├─ Batch 4: 剩余可回收全部清理 [再下一帧]
 *   │
 *   └─ Batch 5: System.gc() [最终帧]
 * </pre>
 *
 * @see CleanupStrategy 策略接口
 * @see GradualCleanupStrategy 更温和的渐进清理
 * @since 2.0.0
 */
public final class EmergencyCleanupStrategy implements CleanupStrategy {

    private static final Logger LOGGER = Logger.getLogger("Renderium-EmergencyCleanup");

    /** 分批数量 */
    private static final int BATCH_COUNT = 5;

    /** 每批之间的帧间隔 */
    private volatile long batchDelayFrames = 1;

    /** 是否正在紧急清理中 */
    private volatile boolean isExecuting = false;

    /** 资源追踪器引用 */
    private ResourceTracker tracker;

    /** 异步资源加载器引用 */
    private Object asyncLoader; // 延迟绑定

    public EmergencyCleanupStrategy() {
        this.tracker = ResourceTracker.getInstance();
        try {
            this.asyncLoader = Class.forName(
                    "com.renderium.module.impl.blaze3d.memory.AsyncResourceLoader")
                    .getMethod("getInstance").invoke(null);
        } catch (Exception ignored) {
            // AsyncResourceLoader 可能尚未初始化
        }
    }

    /**
     * 设置分批间帧延迟
     *
     * @param frames 帧数 (默认 1)
     */
    public void setBatchDelayFrames(int frames) {
        this.batchDelayFrames = Math.max(1, Math.min(frames, 10));
    }

    @Override
    public void execute() {
        if (isExecuting) return; // 防止重入
        isExecuting = true;

        try {
            LOGGER.warning("🚨 EMERGENCY: 开始紧急内存清理");

            // Step 1: 暂停非关键资源加载
            pauseNonCriticalLoading();

            // Step 2: 计算目标释放量 (总预算的 15%)
            long targetBytes = (long) (estimateTotalBudget() * 0.15);
            long bytesPerBatch = targetBytes / BATCH_COUNT;

            // Step 3-7: 分批调度清理任务
            for (int batchIndex = 0; batchIndex < BATCH_COUNT; batchIndex++) {
                final int currentBatch = batchIndex;
                scheduleBatch(currentBatch, bytesPerBatch, targetBytes);

                // 等待指定帧数后再执行下一批
                if (batchIndex < BATCH_COUNT - 1) {
                    Thread.sleep(batchDelayFrames * 16); // ~16ms per frame
                }
            }

            // Step 8: 最终 GC (在所有批次完成后)
            scheduleFinalGC();

            LOGGER.warning("✓ EMERGENCY cleanup 调度完成 (" + BATCH_COUNT + " 批)");

        } catch (Exception e) {
            LOGGER.severe("紧急清理异常: " + e.getMessage());
        } finally {
            isExecuting = false;
        }
    }

    @Override
    public String getName() { return "EmergencyCleanup(强制, " + BATCH_COUNT + "批)"; }

    // ==================== 内部实现 ====================

    /** 暂停非关键资源加载 */
    private void pauseNonCriticalLoading() {
        if (asyncLoader != null) {
            try {
                var pauseMethod = asyncLoader.getClass().getMethod("pauseNonCritical");
                pauseMethod.invoke(asyncLoader);
                LOGGER.info("已暂停非关键资源加载");
            } catch (Exception e) {
                LOGGER.warning("无法暂停异步加载: " + e.getMessage());
            }
        }
    }

    /** 调度单批清理 */
    private void scheduleBatch(int batchIndex, long bytesPerBatch, long totalTarget) {
        long actualTarget = (batchIndex == BATCH_COUNT - 1) ?
                (totalTarget - bytesPerBatch * (BATCH_COUNT - 1)) : bytesPerBatch;

        List<ResourceTracker.CleanupCandidate> candidates =
                tracker.getCleanupCandidates(actualTarget);

        int cleaned = 0;
        for (var candidate : candidates) {
            boolean success = emergencyEvict(candidate);
            if (success) {
                tracker.onResourceEvicted(candidate.resourceId());
                cleaned++;
            }
        }

        LOGGER.warning(String.format("  Emergency batch %d/%d: 清理 %d 个资源",
                batchIndex + 1, BATCH_COUNT, cleaned));
    }

    /** 紧急驱逐单个资源 (不检查热数据) */
    private boolean emergencyEvict(ResourceTracker.CleanupCandidate candidate) {
        try {
            boolean result;
            switch (candidate.type()) {
                case CACHED_TEXTURE:
                case PARTICLE_TEXTURE:
                    evictTexture(candidate.resourceId());
                    result = true;
                    break;
                case DISTANT_CHUNK_MESH:
                case NEAR_CHUNK_MESH:
                    unloadChunk(candidate.resourceId());
                    result = true;
                    break;
                case UNUSED_SHADER:
                    evictShader(candidate.resourceId());
                    result = true;
                    break;
                case TEMPORARY_BUFFER:
                    releaseBuffer(candidate.resourceId());
                    result = true;
                    break;
                case ENTITY_MODEL:
                    evictModel(candidate.resourceId());
                    result = true;
                    break;
                default:
                    result = false;
                    break;
            }
            return result;
        } catch (Exception e) {
            LOGGER.warning("紧急清理失败: id=" + candidate.resourceId() +
                    " → " + e.getMessage());
            return false;
        }
    }

    /** 调度最终 GC */
    private void scheduleFinalGC() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(batchDelayFrames * 16L); // 等待最后一批完成
                System.gc();
                LOGGER.warning("⚠️ Emergency GC 已触发");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ==================== 资源清理操作 ====================

    /**
     * 紧急驱逐纹理资源（释放对应的 VkImageView）
     */
    private boolean evictTexture(long id) {
        long device = getVkDevice();
        if (device == 0L) return false;
        VulkanGraphicsHelper.destroyImageView(device, id);
        LOGGER.fine("[Emergency] 纹理已驱逐: id=0x" + Long.toHexString(id));
        return true;
    }

    /**
     * 紧急卸载块网格（释放对应的 VkBuffer）
     */
    private boolean unloadChunk(long id) {
        long device = getVkDevice();
        if (device == 0L) return false;
        VulkanBufferHelper.destroyBuffer(id, 0L);
        LOGGER.fine("[Emergency] 块网格已卸载: id=0x" + Long.toHexString(id));
        return true;
    }

    /**
     * 紧急驱逐着色器（释放对应的 VkShaderModule）
     */
    private boolean evictShader(long id) {
        long device = getVkDevice();
        if (device == 0L) return false;
        VulkanGraphicsHelper.destroyShaderModule(device, id);
        LOGGER.fine("[Emergency] 着色器已驱逐: id=0x" + Long.toHexString(id));
        return true;
    }

    /**
     * 紧急释放临时缓冲（释放对应的 VkBuffer + VkDeviceMemory）
     */
    private boolean releaseBuffer(long id) {
        long device = getVkDevice();
        if (device == 0L) return false;
        VulkanBufferHelper.destroyBuffer(id, 0L);
        LOGGER.fine("[Emergency] 缓冲已释放: id=0x" + Long.toHexString(id));
        return true;
    }

    /**
     * 紧急驱逐实体模型（释放对应的 VkBuffer）
     */
    private boolean evictModel(long id) {
        long device = getVkDevice();
        if (device == 0L) return false;
        VulkanBufferHelper.destroyBuffer(id, 0L);
        LOGGER.fine("[Emergency] 模型已驱逐: id=0x" + Long.toHexString(id));
        return true;
    }

    /**
     * 获取 VkDevice 句柄（通过 VulkanDeviceHolder）
     */
    private static long getVkDevice() {
        if (!VulkanDeviceHolder.isAvailable()) {
            return 0L;
        }
        return VulkanDeviceHolder.getInstance().getVkDeviceHandle();
    }

    /** 估算总预算 */
    private long estimateTotalBudget() {
        return 6L * 1024 * 1024 * 1024; // 6GB 占位值
    }

    /** 是否正在执行 */
    public boolean isExecuting() { return isExecuting; }
}
