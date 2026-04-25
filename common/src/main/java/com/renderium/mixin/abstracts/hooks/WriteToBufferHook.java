// Renderium - High-Performance CommandEncoder Hook Interface
// Abstracts CommandEncoder buffer write operations interception

package com.renderium.mixin.abstracts.hooks;

/**
 * Functional interface for CommandEncoder buffer write operations.
 *
 * <p>Intercepts {@code writeToBuffer()} for async staging transfer.</p>
 */
@FunctionalInterface
public interface WriteToBufferHook {

    /**
     * Intercept large buffer writes for async staging optimization.
     *
     * @param buffer    Target buffer
     * @param data      Source data
     * @param offset    Byte offset
     * @param length    Data length
     * @return true if handled asynchronously, false for synchronous default
     */
    boolean onWriteToBuffer(Object buffer, Object data, long offset, int length);
}
