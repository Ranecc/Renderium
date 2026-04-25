// Renderium - GpuDeviceMixin 存根类
// Blaze3D GpuDevice 的 Mixin 拦截点
//
// ⚠️ @deprecated 此为存根实现，实际 Mixin 将在 fabric/neoforge 模块中定义

package com.renderium.module.impl.blaze3d;

/**
 * GpuDeviceMixin 存根类（已废弃）。
 *
 * <p>用于 Blaze3D {@code GpuDevice} 的 Mixin 拦截，提供：
 * <ul>
 *   <li>MemoryOptimizer 注入点</li>
 *   <li>PipelineOptimizer 注入点</li>
 *   <li>Arena 分配策略拦截</li>
 * </ul>
 *
 * <h2>⚠️ 废弃说明</h2>
 * <p>实际 Mixin 实现应在 fabric/neoforge 模块中定义。
 * 此存根类仅用于编译通过，运行时不会使用。
 *
 * @deprecated 实际实现在 fabric/neoforge 模块的 Mixin 中
 */
@Deprecated(since = "5.0.0")
public final class GpuDeviceMixin {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(GpuDeviceMixin.class.getName());

    /** 是否完全初始化 */
    private static volatile boolean fullyInitialized = false;

    /** 注入的 MemoryOptimizer 引用 */
    private static volatile MemoryOptimizer memoryOptimizerInstance = null;

    /** 注入的 PipelineOptimizer 引用 */
    private static volatile Object pipelineOptimizerInstance = null;

    private GpuDeviceMixin() {}

    /**
     * 检查是否完全初始化（存根：始终返回 false）
     *
     * @return false（存根实现）
     */
    public static boolean isFullyInitialized() {
        return fullyInitialized;
    }

    /**
     * 设置 MemoryOptimizer 实例（存根：仅记录日志）
     *
     * @param optimizer MemoryOptimizer 实例
     */
    public static void setMemoryOptimizer(MemoryOptimizer optimizer) {
        memoryOptimizerInstance = optimizer;
        LOGGER.fine("[STUB] GpuDeviceMixin.setMemoryOptimizer() called");
        fullyInitialized = true;
    }

    /**
     * 设置 PipelineOptimizer 实例（存根：仅记录日志）
     *
     * @param optimizer PipelineOptimizer 实例
     */
    public static void setPipelineOptimizer(Object optimizer) {
        pipelineOptimizerInstance = optimizer;
        LOGGER.fine("[STUB] GpuDeviceMixin.setPipelineOptimizer() called");
    }

    /**
     * 获取当前 MemoryOptimizer 实例
     *
     * @return MemoryOptimizer 实例或 null
     */
    public static MemoryOptimizer getMemoryOptimizer() {
        return memoryOptimizerInstance;
    }
}
