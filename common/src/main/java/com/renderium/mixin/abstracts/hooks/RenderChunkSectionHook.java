// Renderium - High-Performance Chunk Section Render Hook Interface
// Abstracts chunk section rendering interception

package com.renderium.mixin.abstracts.hooks;

/**
 * Functional interface for chunk section rendering.
 *
 * <p>Implements P2: Object pooling for Per-Frame chunk data.</p>
 */
@FunctionalInterface
public interface RenderChunkSectionHook {

    /**
     * Intercept chunk section render for object pool reuse.
     *
     * @param section   Chunk section being rendered
     * @param builder   Mesh builder (may be pooled)
     * @return Pooled mesh build result, or null to create new
     */
    Object onRenderChunkSection(Object section, Object builder);
}
