// Renderium - High-Performance LevelRenderer Hook Interface
// Abstracts LevelRenderer renderLevel interception

package com.renderium.mixin.abstracts.hooks;

/**
 * Functional interface for level rendering interception.
 *
 * <p>Implements P1: Dual-mode rendering (compatible vs aggressive).</p>
 */
@FunctionalInterface
public interface RenderLevelHook {

    /**
     * Intercept level render start for mode detection and culling setup.
     *
     * @param levelRenderer The LevelRenderer instance
     * @param camera        Active camera
     * @param frustum       View frustum for culling
     * @return true if Renderium handled the frame, false for vanilla rendering
     */
    boolean onRenderLevel(Object levelRenderer, Object camera, Object frustum);
}
