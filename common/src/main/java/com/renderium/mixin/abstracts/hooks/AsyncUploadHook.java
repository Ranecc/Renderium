// Renderium - High-Performance Async Upload Hook Interface
// Abstracts async buffer upload for aggressive mode

package com.renderium.mixin.abstracts.hooks;

/**
 * Hook for async buffer upload (Aggressive mode only).
 *
 * <p>AG3: Hides upload latency with async transfer queue.</p>
 */
@FunctionalInterface
public interface AsyncUploadHook {

    /**
     * Intercept buffer mapping for async staging.
     *
     * @param buffer  Buffer being mapped
     * @param size    Map size
     * @param access  Access mode flags
     * @return Mapped buffer handle, or null for synchronous default
     */
    Object onMapBuffer(Object buffer, long size, int access);
}
