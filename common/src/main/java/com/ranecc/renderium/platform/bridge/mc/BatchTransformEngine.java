package com.ranecc.renderium.platform.bridge.mc;

/**
 * 批量顶点变换引擎接口（标准版本）
 * <p>
 * 将大量顶点坐标批量从世界空间变换到裁剪空间。
 * 性能目标：10K 顶点 &lt; 100μs。
 *
 * @see BatchTransformEngineV3
 * @see MCRenderBridge
 * @since 1.0.0
 */
public interface BatchTransformEngine {
    void flush();

    float[] transformVertices(float[] positions, int vertexCount, float[] matrix);
}
