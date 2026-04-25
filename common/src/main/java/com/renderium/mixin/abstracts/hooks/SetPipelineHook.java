// Renderium - High-Performance RenderPass Hook Interface
// Abstracts RenderPass setPipeline interception

package com.renderium.mixin.abstracts.hooks;

/**
 * Functional interface for pipeline switching interception.
 *
 * <p>Records pipeline switches for hot-spot analysis and caching.</p>
 */
@FunctionalInterface
public interface SetPipelineHook {

    /**
     * Called when a new pipeline is bound to the render pass.
     *
     * @param pipeline The pipeline being set
     * @param pass     Current render pass context
     */
    void onSetPipeline(Object pipeline, Object pass);
}
