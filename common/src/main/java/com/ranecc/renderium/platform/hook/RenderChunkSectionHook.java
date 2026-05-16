// Renderium - RenderChunkSectionHook Interface
// Abstracts chunk section rendering interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for chunk section render interception.
 *
 * <p>Intercepts individual chunk section rendering to enable
 * custom culling and geometry optimization.</p>
 */
@FunctionalInterface
public interface RenderChunkSectionHook {

    /**
     * Intercept chunk section rendering.
     *
     * @param section The chunk section being rendered
     * @return true if handled (skip original), false to proceed normally
     */
    boolean onRenderChunkSection(Object section);
}
