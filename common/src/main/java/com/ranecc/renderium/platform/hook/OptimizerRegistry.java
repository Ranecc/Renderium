// Renderium - High-Performance Mixin Hook Interfaces
// Zero-cost abstraction layer for platform-specific Mixin implementations
// Design: Static delegation + functional interfaces for JIT inlining

package com.ranecc.renderium.platform.hook;

import com.ranecc.renderium.feature.blaze3d.MemoryOptimizer;
import com.ranecc.renderium.feature.blaze3d.ShaderPipelineOptimizer;
import com.ranecc.renderium.feature.blaze3d.VulkanCommandOptimizer;
import com.ranecc.renderium.feature.blaze3d.FrameGraphOptimizer;
import com.ranecc.renderium.platform.bridge.mc.VulkanCommandBatcher;
import com.ranecc.renderium.tech.streamline.StreamlineSharedMemoryManager;
import com.ranecc.renderium.feature.renderopt.MultiLevelCuller;
import com.ranecc.renderium.feature.renderopt.ObjectPoolManager;

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

    private static volatile MemoryOptimizer memoryOptimizer;

    /**
     * Get the MemoryOptimizer instance for Arena-based allocation.
     *
     * @return MemoryOptimizer instance, or null if not initialized
     */
    public static MemoryOptimizer getMemoryOptimizer() {
        return memoryOptimizer;
    }

    /**
     * Initialize MemoryOptimizer (called during module load).
     *
     * @param optimizer the MemoryOptimizer instance
     */
    public static void setMemoryOptimizer(MemoryOptimizer optimizer) {
        memoryOptimizer = optimizer;
    }

    // ==================== Pipeline Optimization ====================

    private static volatile ShaderPipelineOptimizer pipelineOptimizer;

    public static ShaderPipelineOptimizer getPipelineOptimizer() {
        return pipelineOptimizer;
    }

    public static void setPipelineOptimizer(ShaderPipelineOptimizer optimizer) {
        pipelineOptimizer = optimizer;
    }

    // ==================== Command Optimization ====================

    /** @deprecated 由 commandBatcher 替代 */
    @Deprecated
    private static volatile VulkanCommandOptimizer commandOptimizer;

    /** s7 原生命令批处理器 */
    private static volatile VulkanCommandBatcher commandBatcher;

    /** @deprecated 由 getCommandBatcher() 替代 */
    @Deprecated
    public static VulkanCommandOptimizer getCommandOptimizer() {
        return commandOptimizer;
    }

    /** @deprecated 由 setCommandBatcher() 替代 */
    @Deprecated
    public static void setCommandOptimizer(VulkanCommandOptimizer optimizer) {
        commandOptimizer = optimizer;
    }

    public static VulkanCommandBatcher getCommandBatcher() {
        return commandBatcher;
    }

    public static void setCommandBatcher(VulkanCommandBatcher batcher) {
        commandBatcher = batcher;
    }

    // ==================== FrameGraph Optimization ====================

    private static volatile FrameGraphOptimizer frameGraphOptimizer;

    public static FrameGraphOptimizer getFrameGraphOptimizer() {
        return frameGraphOptimizer;
    }

    public static void setFrameGraphOptimizer(FrameGraphOptimizer optimizer) {
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

    private static volatile MultiLevelCuller multiLevelCuller;

    public static MultiLevelCuller getMultiLevelCuller() {
        return multiLevelCuller;
    }

    public static void setMultiLevelCuller(MultiLevelCuller culler) {
        multiLevelCuller = culler;
    }

    // ==================== Object Pooling ====================

    private static volatile ObjectPoolManager objectPoolManager;

    public static ObjectPoolManager getObjectPoolManager() {
        return objectPoolManager;
    }

    public static void setObjectPoolManager(ObjectPoolManager manager) {
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
