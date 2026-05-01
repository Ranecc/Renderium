// Renderium - High-Performance Mixin Hook Interfaces
// Zero-cost abstraction layer for platform-specific Mixin implementations
// Design: Static delegation + functional interfaces for JIT inlining

package com.ranecc.renderium.platform.hook;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

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

    private static volatile VulkanCommandOptimizer commandOptimizer;

    public static VulkanCommandOptimizer getCommandOptimizer() {
        return commandOptimizer;
    }

    public static void setCommandOptimizer(VulkanCommandOptimizer optimizer) {
        commandOptimizer = optimizer;
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
            && commandOptimizer != null
            && frameGraphOptimizer != null;
    }

    /**
     * Get initialization status as a diagnostic string.
     *
     * @return comma-separated list of initialized/uninitialized components
     */
    public static String getStatusReport() {
        return String.format(
            "Memory[%s] Pipeline[%s] Command[%s] FrameGraph[%s] Streamline[%s] Culling[%s] Pool[%s]",
            memoryOptimizer != null ? "✓" : "✗",
            pipelineOptimizer != null ? "✓" : "✗",
            commandOptimizer != null ? "✓" : "✗",
            frameGraphOptimizer != null ? "✓" : "✗",
            streamlineManager != null ? "✓" : "✗",
            multiLevelCuller != null ? "✓" : "✗",
            objectPoolManager != null ? "✓" : "✗"
        );
    }
}
