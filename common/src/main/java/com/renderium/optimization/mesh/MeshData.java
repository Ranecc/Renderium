// Renderium - 网格数据容器
// 存储构建完成的区块网格数据，供渲染器上传到 GPU

package com.renderium.optimization.mesh;

/**
 * 网格数据容器。
 *
 * <p>存储由 {@link ChunkMeshBuilder} 构建完成的网格数据。
 * 包含顶点数据、索引数据、顶点格式和按纹理分组的渲染段。
 *
 * <p>MeshData 是不可变的，构建后不会被修改。
 * 渲染器可以安全地在多线程中读取。
 *
 * @see ChunkMeshBuilder
 * @see CompactVertexFormat
 * @author Renderium Team
 * @since 1.0.0
 */
public final class MeshData {

    /** 顶点数据（AoS 布局，按 vertexFormat.stride 排列） */
    private final byte[] vertexData;

    /** 索引数据（32 位无符号整数） */
    private final byte[] indexData;

    /** 顶点数量 */
    private final int vertexCount;

    /** 索引数量 */
    private final int indexCount;

    /** 顶点格式 */
    private final CompactVertexFormat vertexFormat;

    /** 渲染段（按纹理分组） */
    private final ChunkMeshBuilder.MeshSection[] sections;

    /** GPU 端顶点缓冲区 handle（上传后设置） */
    private volatile long gpuVertexBufferHandle;

    /** GPU 端索引缓冲区 handle（上传后设置） */
    private volatile long gpuIndexBufferHandle;

    /** 是否已上传到 GPU */
    private volatile boolean uploaded;

    /** 上传时间戳（用于 LRU 淘汰） */
    private volatile long lastUsedFrame;

    /**
     * 创建网格数据
     */
    public MeshData(byte[] vertexData, byte[] indexData,
                    int vertexCount, int indexCount,
                    CompactVertexFormat vertexFormat,
                    ChunkMeshBuilder.MeshSection[] sections) {
        this.vertexData = vertexData;
        this.indexData = indexData;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.vertexFormat = vertexFormat;
        this.sections = sections;
        this.uploaded = false;
        this.lastUsedFrame = 0;
    }

    // ==================== Getter ====================

    public byte[] getVertexData() { return vertexData; }
    public byte[] getIndexData() { return indexData; }
    public int getVertexCount() { return vertexCount; }
    public int getIndexCount() { return indexCount; }
    public CompactVertexFormat getVertexFormat() { return vertexFormat; }
    public ChunkMeshBuilder.MeshSection[] getSections() { return sections; }
    public long getGpuVertexBufferHandle() { return gpuVertexBufferHandle; }
    public long getGpuIndexBufferHandle() { return gpuIndexBufferHandle; }
    public boolean isUploaded() { return uploaded; }
    public long getLastUsedFrame() { return lastUsedFrame; }

    /**
     * 获取顶点数据大小（字节）
     */
    public int getVertexDataSize() {
        return vertexData.length;
    }

    /**
     * 获取索引数据大小（字节）
     */
    public int getIndexDataSize() {
        return indexData.length;
    }

    /**
     * 获取渲染段数量
     */
    public int getSectionCount() {
        return sections.length;
    }

    // ==================== GPU 上传状态 ====================

    /**
     * 标记已上传到 GPU
     *
     * @param vertexBufferHandle GPU 顶点缓冲区 handle
     * @param indexBufferHandle GPU 索引缓冲区 handle
     */
    public void markUploaded(long vertexBufferHandle, long indexBufferHandle) {
        this.gpuVertexBufferHandle = vertexBufferHandle;
        this.gpuIndexBufferHandle = indexBufferHandle;
        this.uploaded = true;
    }

    /**
     * 更新最后使用帧
     */
    public void touch(long frameIndex) {
        this.lastUsedFrame = frameIndex;
    }

    /**
     * 释放 GPU 资源
     */
    public void release() {
        this.uploaded = false;
        this.gpuVertexBufferHandle = 0L;
        this.gpuIndexBufferHandle = 0L;
    }

    @Override
    public String toString() {
        return String.format(
            "MeshData{vertices=%d, indices=%d, sections=%d, uploaded=%s, format=%s}",
            vertexCount, indexCount, sections.length, uploaded, vertexFormat);
    }
}
