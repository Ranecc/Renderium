// Renderium - CreateRenderPassHook Interface
// Abstracts render pass creation interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for render pass creation interception.
 *
 * <p>Intercepts CommandEncoder.createRenderPass() to enable
 * custom render pass configuration and optimization.</p>
 */
@FunctionalInterface
public interface CreateRenderPassHook {

    /**
     * Intercept render pass creation.
     *
     * @param renderPass The render pass being created
     * @return true if handled (cancel original), false to proceed normally
     */
    boolean onCreateRenderPass(Object renderPass);
}
