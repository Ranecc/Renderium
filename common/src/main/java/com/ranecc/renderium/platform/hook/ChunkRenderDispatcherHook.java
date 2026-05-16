// Renderium - ChunkRenderDispatcherHook Interface
// Abstracts ChunkRenderDispatcher interception for terrain data extraction

package com.ranecc.renderium.platform.hook;

/**
 * Functional interface for ChunkRenderDispatcher interception.
 *
 * <p>Used to extract terrain mesh data from the Minecraft render pipeline.
 * This is the data source for GBufferGeometryNode, ShadowMapNode, and Hi-Z Builder.</p>
 */
@FunctionalInterface
public interface ChunkRenderDispatcherHook {

    /**
     * Intercept chunk render dispatcher upload.
     *
     * @param chunkRenderList The chunk render list being uploaded
     * @param cameraX         Camera X position
     * @param cameraZ         Camera Z position
     * @return true if handled (skip original), false to proceed normally
     */
    boolean onUploadChunks(Object chunkRenderList, double cameraX, double cameraZ);
}
