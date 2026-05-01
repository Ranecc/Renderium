// Renderium - High-Performance RenderPass Hook Interface
// Abstracts RenderPass close interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for render pass lifecycle.
 *
 * <p>Tracks pass duration and resource usage statistics.</p>
 */
@FunctionalInterface
public interface RenderPassCloseHook {

    /**
     * Called when a render pass is closed/completed.
     *
     * @param pass The render pass being closed
     * @param startTimeNanos Pass start time (from open)
     */
    void onRenderPassClose(Object pass, long startTimeNanos);
}
