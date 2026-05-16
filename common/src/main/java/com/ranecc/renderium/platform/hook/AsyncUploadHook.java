// Renderium - AsyncUploadHook Interface
// Abstracts asynchronous resource upload interception

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for asynchronous resource upload interception.
 *
 * <p>Intercepts async upload operations (textures, buffers) to enable
 * upload coalescing and priority management.</p>
 */
@FunctionalInterface
public interface AsyncUploadHook {

    /**
     * Intercept async resource upload.
     *
     * @param resource The resource being uploaded
     * @param priority Upload priority hint
     * @return true if handled (skip original), false to proceed normally
     */
    boolean onAsyncUpload(Object resource, int priority);
}
