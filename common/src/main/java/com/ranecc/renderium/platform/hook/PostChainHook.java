// Renderium - High-Performance PostChain Hook Interface
// Abstracts post-processing chain integration

package com.ranecc.renderium.platform.hook;
import com.ranecc.renderium.domain.model.FrameData;

/**
 * Functional interface for PostChain (post-processing) integration.
 *
 * <p>Integrates with Streamline for motion vectors and frame data.</p>
 */
@FunctionalInterface
public interface PostChainHook {

    /**
     * Intercept post-processing chain addition to frame.
     *
     * @param postChain  The PostChain instance
     * @param frameData  Current frame data context
     * @return true if Streamline integration was applied
     */
    boolean onAddToFrame(Object postChain, Object frameData);
}
