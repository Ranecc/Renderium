// Renderium - Blaze3D VMA 渐进式清理模块
// 软清理策略 - 预警阶段: 只清理明显无用的资源

package com.ranecc.renderium.feature.blaze3d.memory;

import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;

/**
 * 软清理策略 (预警阶段: 75%-85%)。
 * <p>
 * 只清理明显无用的资源，不触碰任何可能还在使用的数据:
 * <ul>
 *   <li>过期缓存 (超过指定时间未访问)</li>
 *   <li>超出渲染距离的远处区块</li>
 *   <li>可收缩的临时缓冲池</li>
 * </ul></p>
 *
 * @see CleanupStrategy 策略接口
 * @see GradualCleanupStrategy 更激进的渐进清理
 * @since 2.0.0
 */
public final class SoftCleanupStrategy implements CleanupStrategy {

    private static final Logger LOGGER = Logger.getLogger("Renderium-SoftCleanup");

    /** 过期缓存阈值 (毫秒)，默认 30 秒 */
    private volatile long expiredCacheThresholdMs = 30_000L;

    public SoftCleanupStrategy() {}

    /**
     * 设置过期缓存时间阈值
     *
     * @param thresholdMs 毫秒数 (最小 5000)
     */
    public void setExpiredCacheThresholdMs(long thresholdMs) {
        this.expiredCacheThresholdMs = Math.max(5_000, thresholdMs);
    }

    @Override
    public void execute() {
        long startTime = System.nanoTime();
        int evictedCount = 0;
        long evictedBytes = 0;

        try {
            // 1. 清理过期的 Shader SPIR-V 缓存
            // (通过 ShaderCache 的 pruneOldEntries 接口)
            evictExpiredShaderCache();

            // 2. 清理超出渲染距离的区块 mesh (如果有 ChunkMeshManager)
            evictDistantChunks();

            // 3. 收缩临时缓冲池 (如果有 TemporaryBufferPool)
            shrinkTemporaryBuffers();

        } catch (Exception e) {
            LOGGER.warning("软清理执行异常: " + e.getMessage());
        }

        long elapsed = System.nanoTime() - startTime;
        if (evictedCount > 0) {
            LOGGER.fine(String.format("✓ Soft cleanup: %d items, %d KB (%.2fms)",
                    evictedCount, evictedBytes / 1024, elapsed / 1_000_000.0));
        }
    }

    @Override
    public String getName() {
        return "SoftCleanup(阈值=" + (expiredCacheThresholdMs / 1000) + "s)";
    }

    // ==================== 内部清理操作 ====================

    /** 清理过期 Shader 缓存 */
    private void evictExpiredShaderCache() {
        try {
            var shaderCacheClass = Class.forName(
                    "com.renderium.module.impl.blaze3d.shader.ShaderCache");
            Object cacheInstance = null;
            for (var field : shaderCacheClass.getDeclaredFields()) {
                if (field.getType().equals(shaderCacheClass)) {
                    field.setAccessible(true);
                    cacheInstance = field.get(null);
                    break;
                }
            }
            if (cacheInstance != null) {
                var method = cacheInstance.getClass().getMethod(
                        "pruneOldEntries", long.class);
                method.invoke(cacheInstance, expiredCacheThresholdMs);
                LOGGER.fine("已触发 Shader 缓存过期清理");
            }
        } catch (ClassNotFoundException ignored) {
            // ShaderCache 不存在或未加载，跳过
        } catch (Exception e) {
            LOGGER.fine("Shader 缓存清理不可用: " + e.getMessage());
        }
    }

    /** 清理远处区块 */
    private void evictDistantChunks() {
        // TODO: 与 ChunkMeshManager 集成
        // 当区块管理器可用时调用: ChunkMeshManager.unloadOutsideRenderDistance()
    }

    /** 收缩临时缓冲池 */
    private void shrinkTemporaryBuffers() {
        // TODO: 与 TemporaryBufferPool 集成
        // 当缓冲池可用时调用: pool.shrink()
    }
}
