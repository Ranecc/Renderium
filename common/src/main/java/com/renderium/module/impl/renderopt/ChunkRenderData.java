// Renderium - 渲染优化模块 (狂暴模式专用)
// Chunk 渲染数据 - 批处理渲染器的输入单元

package com.renderium.module.impl.renderopt;

/**
 * Chunk 渲染数据
 * <p>
 * 封装单个 chunk 的渲染信息，
 * 作为 {@link ChunkBatchRenderer#addToBatch(ChunkRenderData)} 的输入。
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public final class ChunkRenderData {

    /** Chunk 坐标 */
    public final int x, y, z;

    /** VAO ID (OpenGL) 或 Buffer Handle (Vulkan) */
    public final long vertexArrayHandle;

    /** 索引数量 */
    public final int indexCount;

    /** 顶点数量 */
    public final int vertexCount;

    /** 材质/纹理 ID（用于批次分组） */
    public final int materialId;

    /** 距离相机的距离（用于排序） */
    public final float distanceFromCamera;

    /**
     * 创建 chunk 渲染数据
     *
     * @param x                  X 坐标
     * @param y                  Y 坐标
     * @param z                  Z 坐标
     * @param vertexArrayHandle  VAO 句柄
     * @param indexCount         索引数
     * @param vertexCount        顶点数
     * @param materialId          材质 ID
     * @param distanceFromCamera  距离
     */
    public ChunkRenderData(int x, int y, int z,
                            long vertexArrayHandle,
                            int indexCount, int vertexCount,
                            int materialId,
                            float distanceFromCamera) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vertexArrayHandle = vertexArrayHandle;
        this.indexCount = indexCount;
        this.vertexCount = vertexCount;
        this.materialId = materialId;
        this.distanceFromCamera = distanceFromCamera;
    }

    @Override
    public String toString() {
        return String.format("ChunkRenderData{%d,%d,%d indices=%d dist=%.1f}",
                x, y, z, indexCount, distanceFromCamera);
    }

    /**
     * 获取 Chunk ID (基于坐标的哈希值)
     *
     * @return Chunk 唯一标识符
     */
    public long getChunkId() {
        return ((long) x & 0xFFFFFFFFL) |
               (((long) z & 0xFFFFFFFFL) << 32);
    }

    /**
     * 获取 Section 数量 (固定为 1，简化实现)
     *
     * @return Section 数量
     */
    public int getSectionCount() {
        return 1;
    }
}
