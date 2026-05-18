package com.ranecc.renderium.infrastructure.gpu;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Vulkan 操作全局短路守卫
 *
 * <p>任何 Vulkan FFM 调用失败（扩展不支持、驱动崩溃、OOM）→
 * 标记为 failed → 后续所有 Vulkan 操作短路 → 自动降级到光栅化/CPU 路径。
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>单点故障</b>：一个操作失败，所有后续操作短路</li>
 *   <li><b>不可恢复</b>：failed=true 后不会恢复，避免状态混乱</li>
 *   <li><b>不崩溃游戏</b>：所有 Vulkan 操作返回安全默认值</li>
 * </ul>
 */
public final class VulkanOperationGuard {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanGuard");

    private static final AtomicBoolean FAILED = new AtomicBoolean(false);
    private static final AtomicLong FAILURE_TIMESTAMP = new AtomicLong(0L);
    private static volatile String failureReason = "";

    private VulkanOperationGuard() {}

    public static boolean isFailed() {
        return FAILED.get();
    }

    public static boolean isOk() {
        return !FAILED.get();
    }

    public static String getFailureReason() {
        return failureReason;
    }

    public static long getFailureTimestamp() {
        return FAILURE_TIMESTAMP.get();
    }

    public static void markFailed(Throwable cause) {
        if (FAILED.compareAndSet(false, true)) {
            failureReason = cause != null ? cause.getMessage() : "unknown";
            FAILURE_TIMESTAMP.set(System.currentTimeMillis());
            LOGGER.severe("Vulkan 短路守卫已激活: " + failureReason);

            VulkanDeviceHolder.getInstance().markDegraded();
        }
    }

    public static void markFailed(String reason) {
        if (FAILED.compareAndSet(false, true)) {
            failureReason = reason != null ? reason : "unknown";
            FAILURE_TIMESTAMP.set(System.currentTimeMillis());
            LOGGER.severe("Vulkan 短路守卫已激活: " + failureReason);

            VulkanDeviceHolder.getInstance().markDegraded();
        }
    }
}
