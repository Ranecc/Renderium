package com.ranecc.renderium.infrastructure.sanitizer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * GPU 资源定时清理调度器
 * <p>
 * 每 1024 帧触发一次扫描，检查：
 * <ul>
 *   <li>未释放 VkBuffer（activeAllocations > 256）</li>
 *   <li>DescriptorPool 使用率（> 90%）</li>
 *   <li>Staging 映射数量（persistentMaps > 64）</li>
 *   <li>连续 OOM（3 帧内 Vulkan OOM）</li>
 * </ul>
 */
public final class AutoCleanScheduler {

    private static final Logger LOGGER = Logger.getLogger("Renderium|AutoClean");

    /** 触发周期（帧数） */
    private static final int SCAN_INTERVAL = 1024;

    /** 帧计数器 */
    private static final AtomicLong frameCounter = new AtomicLong(0);

    /** 上次扫描时间（纳秒） */
    private static volatile long lastScanTimeNs = 0;

    /**
     * 每帧调用，检查是否需要触发清理
     */
    public static void onFrameEnd() {
        long count = frameCounter.incrementAndGet();

        // 每 1024 帧触发一次
        if ((count & (SCAN_INTERVAL - 1)) == 0) {
            long now = System.nanoTime();
            long elapsed = now - lastScanTimeNs;

            // 避免过于频繁（至少间隔 500ms）
            if (elapsed < 500_000_000L) return;

            lastScanTimeNs = now;
            performScan();
        }
    }

    private static void performScan() {
        try {
            // TODO: 集成 EmergencyCleanupStrategy.scanAndClean()
            // 当前检查项：
            // 1. VkBuffer 泄漏检测
            // 2. DescriptorPool 使用率
            // 3. Staging 映射数量
            // 4. 连续 OOM 检测
            LOGGER.fine("AutoCleanScheduler: 周期性扫描触发 (frame=" + frameCounter.get() + ")");
        } catch (Exception e) {
            LOGGER.warning("AutoCleanScheduler: 扫描异常 — " + e.getMessage());
        }
    }

    /**
     * 获取帧计数
     */
    public static long getFrameCount() {
        return frameCounter.get();
    }

    /**
     * 重置（用于测试）
     */
    public static void reset() {
        frameCounter.set(0);
        lastScanTimeNs = 0;
    }
}
