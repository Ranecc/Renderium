// Renderium - High-Performance RenderPass Hook Interface
// Abstracts RenderPass drawIndexed interception

package com.renderium.mixin.abstracts.hooks;

/**
 * Functional interface for draw call interception.
 *
 * <p>Implements S4: Draw Call merging for batched rendering.</p>
 */
@FunctionalInterface
public interface DrawIndexedHook {

    /**
     * Intercept indexed draw call for potential merging.
     *
     * @param indexCount   Number of indices
     * @param instanceCount Instance count (1 for non-instanced)
     * @param firstIndex   First index offset
     * @param vertexOffset Base vertex offset
     * @param firstInstance First instance ID
     * @return true if draw was merged/batched, false for immediate execution
     */
    boolean onDrawIndexed(int indexCount, int instanceCount, int firstIndex,
                          int vertexOffset, int firstInstance);
}
