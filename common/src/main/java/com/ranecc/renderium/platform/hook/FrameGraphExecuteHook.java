// Renderium - High-Performance FrameGraph Hook Interface
// Abstracts FrameGraphBuilder.execute() interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for FrameGraph execution interception.
 *
 * <p>Implements P1: Dual-mode FrameGraph execution with Inspector wrapping.</p>
 */
@FunctionalInterface
public interface FrameGraphExecuteHook {

    /**
     * Intercept FrameGraph execution for optimization mode selection.
     *
     * @param builder   The FrameGraphBuilder instance
     * @param inspector Original inspector (may be replaced)
     * @return Replacement inspector, or null to use original
     */
    Object onExecute(Object builder, Object inspector);
}
