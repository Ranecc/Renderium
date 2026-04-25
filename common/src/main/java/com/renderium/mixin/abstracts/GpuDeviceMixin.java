// Renderium - GpuDevice Mixin 抽象接口
// 跨模块访问的公共接口

package com.renderium.mixin.abstracts;

/**
 * GpuDevice Mixin 公共接口。
 *
 * <p>提供跨模块（common → fabric/neoforge）的 GpuDevice 状态访问。
 * Fabric/NeoForge 实现类应继承此接口并实现具体逻辑。</p>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class GpuDeviceMixin {

    /** 是否完全初始化 */
    private static volatile boolean fullyInitialized = false;

    /** Memory Optimizer 引用 */
    private static volatile Object memoryOptimizer = null;

    /** Pipeline Optimizer 引用 */
    private static volatile Object pipelineOptimizer = null;

    private GpuDeviceMixin() {} // 防止实例化

    /**
     * 检查是否已完全初始化
     *
     * @return 是否初始化完成
     */
    public static boolean isFullyInitialized() {
        return fullyInitialized;
    }

    /**
     * 设置初始化状态
     *
     * @param initialized 是否初始化完成
     */
    public static void setFullyInitialized(boolean initialized) {
        fullyInitialized = initialized;
    }

    /**
     * 设置 Memory Optimizer
     *
     * @param optimizer MemoryOptimizer 实例
     */
    public static void setMemoryOptimizer(Object optimizer) {
        memoryOptimizer = optimizer;
    }

    /**
     * 获取 Memory Optimizer
     *
     * @return MemoryOptimizer 实例
     */
    public static Object getMemoryOptimizer() {
        return memoryOptimizer;
    }

    /**
     * 设置 Pipeline Optimizer
     *
     * @param optimizer PipelineOptimizer 实例
     */
    public static void setPipelineOptimizer(Object optimizer) {
        pipelineOptimizer = optimizer;
    }

    /**
     * 获取 Pipeline Optimizer
     *
     * @return PipelineOptimizer 实例
     */
    public static Object getPipelineOptimizer() {
        return pipelineOptimizer;
    }
}
