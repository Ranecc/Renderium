package com.ranecc.renderium.platform.bridge.mc;

/**
 * 批量顶点变换引擎接口 V3（极致性能版本）
 * <p>
 * 使用 Unsafe + 8x 循环展开 + 双步 NR 等优化手段，
 * 性能目标：10K 顶点 &lt; 30μs。
 *
 * @see BatchTransformEngine
 * @see MCRenderBridge
 * @since 3.0.0
 */
public interface BatchTransformEngineV3 {
    void flush();

    boolean isUsingUnsafe();

    float[] transformVertices(float[] positions, int vertexCount, float[] matrix);

    int getBufferCapacity();

    float[] batchMatrixMultiply(float[] a, float[] b, int count, Object context);
}
