// Renderium - High-Performance GpuDevice Hook Interface
// Abstracts GpuDevice.createBuffer() and precompilePipeline() interception logic

package com.ranecc.renderium.platform.hook;

import com.ranecc.renderium.None;

/**
 * Functional interface for GpuDevice buffer creation optimization.
 *
 * <p><b>Performance Critical:</b> This hook is called on EVERY buffer allocation.
 * Implementation must be O(1) with minimal branching.</p>
 *
 * <h3>Hot Path Requirements:</h3>
 * <ul>
 *   <li>No object allocation in the hot path</li>
 *   <li>Use primitive comparisons where possible</li>
 *   <li>Avoid virtual method calls - prefer static final fields</li>
 *   <li>Target: &lt;10ns overhead per call</li>
 * </ul>
 *
 * @see OptimizerRegistry#getMemoryOptimizer()
 * @since 3.0.0
 */
@FunctionalInterface
public interface GpuDeviceBufferHook {

    /**
     * Intercept buffer creation and potentially return an optimized buffer.
     *
     * <p><b>Contract:</b></p>
     * <ol>
     *   <li>Check if optimization is applicable (usage type, size constraints)</li>
     *   <li>If applicable, allocate from Arena and return non-null</li>
     *   <li>If not applicable or allocation fails, return null to fall through to default</li>
     * </ol>
     *
     * @param label    Buffer label supplier (lazy evaluation)
     * @param usage    Buffer usage flags (GpuBuffer.Usage constants)
     * @param size     Requested size in bytes
     * @return Optimized GpuBuffer instance, or null to use default allocation
     */
    Object onCreateBuffer(java.util.function.Supplier<String> label, int usage, long size);

    /**
     * Called when frame data has been updated (non-abstract default method).
     *
     * @param frameData the frame data snapshot
     */
    default void onFrameDataUpdated(Object frameData) {}

    /**
     * Default implementation using MemoryOptimizer Arena allocation.
     *
     * <p>This is the reference implementation that will be used by platform Mixins.
     * It directly accesses {@link OptimizerRegistry} static fields for zero-overhead access.</p>
     */
    class Default implements GpuDeviceBufferHook {

        /** Usage flag for vertex/index buffers */
        private static final int USAGE_VERTEX_OR_INDEX = 0x01;
        /** Usage flag for uniform buffers */
        private static final int USAGE_UNIFORM = 0x02;

        /** Size threshold for Ring Buffer allocation (64KB) */
        private static final long RING_BUFFER_THRESHOLD = 65536L;
        /** Size threshold for Pool Arena allocation (4KB) */
        private static final long POOL_BLOCK_SIZE_THRESHOLD = 4096L;

        @Override
        public Object onCreateBuffer(java.util.function.Supplier<String> label, int usage, long size) {
            var optimizer = OptimizerRegistry.getMemoryOptimizer();
            if (optimizer == null || !optimizer.isEnabled()) {
                return null;
            }

            // Fast path: check usage type with bitmask (branch prediction friendly)
            if ((usage & USAGE_VERTEX_OR_INDEX) != 0) {
                // A1: Per-Frame Arena for vertex/index buffers
                return optimizer.allocateFromPerFrameArena(label, usage, size);
            }

            if ((usage & USAGE_UNIFORM) != 0 && size <= RING_BUFFER_THRESHOLD) {
                // A2: Ring Buffer Arena for small uniform buffers
                return optimizer.allocateFromRingBufferArena(label, usage, size);
            }

            if (size <= POOL_BLOCK_SIZE_THRESHOLD) {
                // A3: Pool Arena for fixed-size small blocks
                return optimizer.allocateFromPoolArena(label, usage, size);
            }

            // Fall through to default allocation
            return null;
        }
    }
}
