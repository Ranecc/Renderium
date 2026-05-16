// Renderium - VertexFormatCompressionHook Interface
// Abstracts vertex format compression interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for vertex format compression interception.
 *
 * <p>Intercepts vertex format creation to enable compact vertex formats
 * that reduce memory bandwidth and improve cache utilization.</p>
 */
@FunctionalInterface
public interface VertexFormatCompressionHook {

    /**
     * Intercept vertex format compression.
     *
     * @param format     The original vertex format
     * @param elements   Vertex element descriptions
     * @return Compressed vertex format, or null to use default
     */
    Object onCompressFormat(Object format, Object[] elements);
}
