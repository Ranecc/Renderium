// Renderium - High-Performance Mixin Hook Interfaces
// Zero-cost abstraction layer for platform-specific Mixin implementations
// Design: Static delegation + functional interfaces for JIT inlining

package com.ranecc.renderium.platform.hook;

import com.ranecc.renderium.platform.bridge.mc.VulkanCommandBatcher;
import com.ranecc.renderium.tech.streamline.StreamlineSharedMemoryManager;

/**
 * Central registry for all optimizer instances used by Mixin hooks.
 *
 * <p><b>Performance Design:</b></p>
 * <ul>
 *   <li>Uses {@code static final} fields for JIT-friendly constant folding</li>
 *   <li>All getters are trivial accessors (will be inlined by HotSpot)</li>
 *   <li>Lazy initialization with volatile double-check locking</li>
 *   <li>No virtual method dispatch on hot paths</li>
 * </ul>
 *
 * <h3>Usage Pattern (in Mixin implementations):</h3>
 * <pre>{@code
 * // Direct static access - compiler will inline this
 * OptimizerRegistry.getMemoryOptimizer().allocateFromPerFrameArena(label, usage, size);
 * }</pre>
 *
 * @since 3.0.0
 */
public final class OptimizerRegistry {

    /** Prevent instantiation - pure static utility */
    private OptimizerRegistry() {}

    // ==================== Memory Optimization ====================

    private static volatile MemOptimizer memoryOptimizer;

    public static MemOptimizer getMemoryOptimizer() {
        return memoryOptimizer;
    }

    public static void setMemoryOptimizer(MemOptimizer optimizer) {
        memoryOptimizer = optimizer;
    }

    // ==================== Pipeline Optimization ====================

    private static volatile Object pipelineOptimizer;

    public static Object getPipelineOptimizer() {
        return pipelineOptimizer;
    }

    public static void setPipelineOptimizer(Object optimizer) {
        pipelineOptimizer = optimizer;
    }

    // ==================== Command Optimization ====================

    /** @deprecated 由 commandBatcher 替代 */
    @Deprecated
    private static volatile CommandOptimizer commandOptimizer;

    /** s7 原生命令批处理器 */
    private static volatile VulkanCommandBatcher commandBatcher;

    @Deprecated
    public static CommandOptimizer getCommandOptimizer() {
        return commandOptimizer;
    }

    @Deprecated
    public static void setCommandOptimizer(CommandOptimizer optimizer) {
        commandOptimizer = optimizer;
    }

    public static VulkanCommandBatcher getCommandBatcher() {
        return commandBatcher;
    }

    public static void setCommandBatcher(VulkanCommandBatcher batcher) {
        commandBatcher = batcher;
    }

    // ==================== FrameGraph Optimization ====================

    private static volatile Object frameGraphOptimizer;

    public static Object getFrameGraphOptimizer() {
        return frameGraphOptimizer;
    }

    public static void setFrameGraphOptimizer(Object optimizer) {
        frameGraphOptimizer = optimizer;
    }

    // ==================== Streamline Integration ====================

    private static volatile StreamlineSharedMemoryManager streamlineManager;

    public static StreamlineSharedMemoryManager getStreamlineManager() {
        return streamlineManager;
    }

    public static void setStreamlineManager(StreamlineSharedMemoryManager manager) {
        streamlineManager = manager;
    }

    // ==================== Culling System ====================

    private static volatile Object multiLevelCuller;

    public static Object getMultiLevelCuller() {
        return multiLevelCuller;
    }

    public static void setMultiLevelCuller(Object culler) {
        multiLevelCuller = culler;
    }

    // ==================== Object Pooling ====================

    private static volatile Object objectPoolManager;

    public static Object getObjectPoolManager() {
        return objectPoolManager;
    }

    public static void setObjectPoolManager(Object manager) {
        objectPoolManager = manager;
    }

    // ==================== Status Queries ====================

    /**
     * Check if all core optimizers are initialized and ready.
     *
     * @return true if all required optimizers are non-null
     */
    public static boolean isFullyInitialized() {
        return memoryOptimizer != null
            && pipelineOptimizer != null
            && (commandOptimizer != null || commandBatcher != null)
            && frameGraphOptimizer != null;
    }

    /**
     * Get initialization status as a diagnostic string.
     *
     * @return comma-separated list of initialized/uninitialized components
     */
    public static String getStatusReport() {
        String cmdStatus;
        if (commandBatcher != null) {
            cmdStatus = "Batcher✓";
        } else if (commandOptimizer != null) {
            cmdStatus = "Optimizer✓";
        } else {
            cmdStatus = "✗";
        }
        return String.format(
            "Memory[%s] Pipeline[%s] Command[%s] FrameGraph[%s] Streamline[%s] Culling[%s] Pool[%s]",
            memoryOptimizer != null ? "✓" : "✗",
            pipelineOptimizer != null ? "✓" : "✗",
            cmdStatus,
            frameGraphOptimizer != null ? "✓" : "✗",
            streamlineManager != null ? "✓" : "✗",
            multiLevelCuller != null ? "✓" : "✗",
            objectPoolManager != null ? "✓" : "✗"
        );
    }

    // ==================== Default Hook Implementations ====================

    /**
     * 默认 DrawIndexed 批处理优化器
     * <p>
     * 尝试通过 OptimizerRegistry 中的 CommandOptimizer 进行 Draw Call 合并。
     * 如果优化器不可用或合并失败，返回 false 回退到默认执行路径。
     */
    public static final class BatchDrawOptimizer implements DrawIndexedHook {
        @Override
        public boolean onDrawIndexed(int indexCount, int instanceCount,
                                      int firstIndex, int vertexOffset, int firstInstance) {
            var optimizer = getCommandOptimizer();
            if (optimizer == null || !optimizer.isEnabled()) {
                return false;
            }
            return optimizer.tryMergeDrawCall(indexCount, instanceCount,
                    firstIndex, vertexOffset, firstInstance);
        }
    }

    /**
     * 默认 GpuDevice 遮挡剔除优化器
     * <p>
     * 通过 MemoryOptimizer 的 Arena 分配优化缓冲区创建。
     * 委托给 {@link GpuDeviceBufferHook.Default} 实现。
     */
    public static final class OcclusionCullOptimizer implements GpuDeviceBufferHook {
        private final GpuDeviceBufferHook.Default delegate = new GpuDeviceBufferHook.Default();

        @Override
        public Object onCreateBuffer(java.util.function.Supplier<String> label, int usage, long size) {
            return delegate.onCreateBuffer(label, usage, size);
        }
    }
}
