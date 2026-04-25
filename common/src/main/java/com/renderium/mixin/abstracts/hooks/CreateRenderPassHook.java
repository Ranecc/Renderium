// Renderium - High-Performance CommandEncoder Hook Interface
// Abstracts CommandEncoder render pass creation interception

package com.renderium.mixin.abstracts.hooks;

/**
 * Functional interface for CommandEncoder render pass creation.
 *
 * <p>Intercepts {@code createRenderPass()} for Streamline integration.</p>
 */
@FunctionalInterface
public interface CreateRenderPassHook {

    /**
     * Intercept render pass creation to register with Streamline.
     *
     * @param renderPassConfig Render pass configuration
     * @return Modified or wrapped render pass, or null to use original
     */
    Object onCreateRenderPass(Object renderPassConfig);
}
