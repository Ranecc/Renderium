// Renderium - High-Performance Vertex Format Compression Hook Interface
// Abstracts vertex format compression for aggressive mode

package com.renderium.mixin.abstracts.hooks;

/**
 * Hook for vertex format compression (Aggressive mode only).
 *
 * <p>AG1: Reduces vertex bandwidth by ~43% through format compression.</p>
 */
@FunctionalInterface
public interface VertexFormatCompressionHook {

    /**
     * Intercept vertex buffer creation for format compression.
     *
     * @param label      Buffer label
     * @param usage      Usage flags
     * @param size       Original size
     * @param format     Vertex format descriptor
     * @return Compressed buffer, or null to use original format
     */
    Object onCreateCompressedBuffer(Object label, int usage, long size, Object format);
}
