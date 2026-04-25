// Renderium - High-Performance RenderPass Hook Interface
// Abstracts RenderPass bindTexture interception

package com.renderium.mixin.abstracts.hooks;

/**
 * Functional interface for texture binding optimization.
 *
 * <p>Implements S3: Descriptor Set reuse to reduce allocation by 95%.</p>
 */
@FunctionalInterface
public interface BindTextureHook {

    /**
     * Intercept texture binding for descriptor set reuse.
     *
     * @param texture  Texture resource
     * @param unit     Texture unit index
     * @return true if handled with cached descriptor, false for default path
     */
    boolean onBindTexture(Object texture, int unit);
}
