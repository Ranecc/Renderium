// Renderium - High-Performance CommandEncoder Hook Interface
// Abstracts CommandEncoder submit interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for CommandEncoder command submission optimization.
 *
 * <p>Intercepts {@code submit()} to implement:</p>
 * <ul>
 *   <li>S4: Command buffer pre-recording and caching</li>
 *   <li>S5: Multi-queue scheduling (graphics/compute/transfer)</li>
 * </ul>
 *
 * @see OptimizerRegistry#getCommandOptimizer()
 * @since 3.0.0
 */
@FunctionalInterface
public interface CommandEncoderSubmitHook {

    /**
     * Intercept command buffer submission for batch optimization.
     *
     * @param commandBuffer The command buffer being submitted
     * @return true if submission was handled (cancel original), false to proceed normally
     */
    boolean onSubmit(Object commandBuffer);
}
