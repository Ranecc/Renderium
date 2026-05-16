// Renderium - WriteToBufferHook Interface
// Abstracts buffer write interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for buffer write operation interception.
 *
 * <p>Intercepts CommandEncoder.writeToBuffer() to enable
 * buffer update coalescing and optimization.</p>
 */
@FunctionalInterface
public interface WriteToBufferHook {

    /**
     * Intercept buffer write operation.
     *
     * @param buffer  The target buffer
     * @param offset  Byte offset into the buffer
     * @param size    Number of bytes to write
     * @return true if handled (cancel original), false to proceed normally
     */
    boolean onWriteToBuffer(Object buffer, long offset, long size);
}
