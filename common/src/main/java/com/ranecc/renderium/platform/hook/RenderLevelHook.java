// Renderium - RenderLevelHook Interface
// Abstracts LevelRenderer.render() interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for LevelRenderer render interception.
 *
 * <p>Intercepts the main world rendering entry point to enable
 * custom pre/post render processing.</p>
 */
@FunctionalInterface
public interface RenderLevelHook {

    /**
     * Intercept level rendering.
     *
     * @param levelRenderer The LevelRenderer instance
     * @param tickDelta     Partial tick time
     * @param limitTime     Time budget limit
     * @param renderBlockLayer Whether block layers should be rendered
     * @return true if handled, false to proceed normally
     */
    boolean onRenderLevel(Object levelRenderer, float tickDelta, long limitTime, boolean renderBlockLayer);
}
