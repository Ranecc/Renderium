// Renderium - LOD 区块数据结构
// 存储远景区块的简化几何和元数据
// 支持多级细节（Billboard / 简化网格 / 高度图）

package com.renderium.optimization.lod;

import java.util.Arrays;

/**
 * LOD 区块数据结构。
 *
 * <p>存储远景区块的简化表示，用于替代完整区块的渲染。
 * 根据 LOD 层级，数据可以是：
 * <ul>
 *   <li><b>Billboard</b>：预渲染纹理四边形（最远）</li>
 *   <li><b>简化网格</b>：低面数的几何体（中景）</li>
 *   <li><b>高度图</b>：列级高度信息（用于遮挡判断和轮廓渲染）</li>
 * </ul>
 *
 * <h2>内存布局</h2>
 * <pre>
 * LODChunk
 * ├── 元数据 (chunkX, chunkZ, level, state)
 * ├── 高度图 (short[16][16] = 512 bytes)
 * ├── 颜色图 (int[16][16] = 1024 bytes, 可选)
 * ├── Billboard 纹理 handle (8 bytes, 可选)
 * └── 简化网格 handle (8 bytes, 可选)
 * </pre>
 * <p>每个 LODChunk 约 1.5-2 KB，远小于完整区块的 50-200 KB。
 *
 * @see RenderiumLODManager
 * @author Renderium Team
 * @since 1.0.0
 */
public final class LODChunk {

    // ==================== LOD 数据类型 ====================

    /**
     * LOD 数据类型
     */
    public enum DataType {
        /** 仅高度图（最省内存） */
        HEIGHTMAP_ONLY,
        /** 高度图 + 顶部颜色 */
        HEIGHTMAP_WITH_COLOR,
        /** 预渲染 Billboard 纹理 */
        BILLBOARD,
        /** 简化网格（低面数几何） */
        SIMPLIFIED_MESH
    }

    /**
     * LOD 区块状态
     */
    public enum State {
        /** 空数据，等待加载 */
        EMPTY,
        /** 正在加载 */
        LOADING,
        /** 数据就绪 */
        READY,
        /** 数据过期，需要更新 */
        DIRTY,
        /** 加载失败 */
        FAILED
    }

    // ==================== 常量 ====================

    /** 区块尺寸 */
    private static final int CHUNK_SIZE = 16;

    /** 高度图数据大小 */
    private static final int HEIGHTMAP_SIZE = CHUNK_SIZE * CHUNK_SIZE;

    /** 颜色图数据大小 */
    private static final int COLORMAP_SIZE = CHUNK_SIZE * CHUNK_SIZE;

    // ==================== 元数据 ====================

    /** 区块 X 坐标（世界） */
    private final int chunkX;

    /** 区块 Z 坐标（世界） */
    private final int chunkZ;

    /** 数据类型 */
    private volatile DataType dataType;

    /** 当前状态 */
    private volatile State state;

    /** 最后更新时间戳 */
    private volatile long lastUpdateTimestamp;

    /** 当前 LOD 层级 */
    private volatile RenderiumLODManager.LODLevel lodLevel;

    // ==================== 高度图数据 ====================

    /**
     * 高度图数据。
     *
     * <p>heightMap[z * 16 + x] = 该列最高不透明方块的 Y 坐标。
     * 使用 short 而非 int 节省内存（MC 的 Y 范围 -64~320，short 足够）。
     */
    private short[] heightMap;

    // ==================== 颜色图数据 ====================

    /**
     * 顶部颜色图（ARGB 格式）。
     *
     * <p>colorMap[z * 16 + x] = 该列顶部方块的平均颜色。
     * 用于 Billboard 和远景着色。
     */
    private int[] colorMap;

    // ==================== GPU 资源句柄 ====================

    /** Billboard 纹理的 Vulkan Image handle */
    private volatile long billboardTextureHandle;

    /** 简化网格的顶点缓冲区 handle */
    private volatile long meshVertexBufferHandle;

    /** 简化网格的索引缓冲区 handle */
    private volatile long meshIndexBufferHandle;

    /** 简化网格的索引数量 */
    private volatile int meshIndexCount;

    // ==================== 构造函数 ====================

    /**
     * 创建 LOD 区块
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @param dataType 数据类型
     */
    public LODChunk(int chunkX, int chunkZ, DataType dataType) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dataType = dataType;
        this.state = State.EMPTY;
        this.lodLevel = RenderiumLODManager.LODLevel.FAR_BILLBOARD;
        this.lastUpdateTimestamp = 0;

        // 按需分配
        this.heightMap = null;
        this.colorMap = null;
        this.billboardTextureHandle = 0L;
        this.meshVertexBufferHandle = 0L;
        this.meshIndexBufferHandle = 0L;
        this.meshIndexCount = 0;
    }

    // ==================== 高度图操作 ====================

    /**
     * 设置高度图数据
     *
     * @param heights 高度数组 [z * 16 + x]，长度必须为 256
     */
    public void setHeightMap(short[] heights) {
        if (heights == null || heights.length != HEIGHTMAP_SIZE) {
            throw new IllegalArgumentException("heightMap must have " + HEIGHTMAP_SIZE + " elements");
        }
        if (this.heightMap == null) {
            this.heightMap = new short[HEIGHTMAP_SIZE];
        }
        System.arraycopy(heights, 0, this.heightMap, 0, HEIGHTMAP_SIZE);
    }

    /**
     * 获取指定列的高度
     *
     * @param localX 局部 X 坐标 [0, 15]
     * @param localZ 局部 Z 坐标 [0, 15]
     * @return 高度值，如果数据不可用返回 Short.MIN_VALUE
     */
    public short getHeight(int localX, int localZ) {
        if (heightMap == null) return Short.MIN_VALUE;
        if (localX < 0 || localX >= CHUNK_SIZE || localZ < 0 || localZ >= CHUNK_SIZE) {
            return Short.MIN_VALUE;
        }
        return heightMap[localZ * CHUNK_SIZE + localX];
    }

    /**
     * 获取高度图的只读拷贝
     */
    public short[] getHeightMapCopy() {
        if (heightMap == null) return new short[HEIGHTMAP_SIZE];
        return heightMap.clone();
    }

    // ==================== 颜色图操作 ====================

    /**
     * 设置颜色图数据
     *
     * @param colors ARGB 颜色数组 [z * 16 + x]，长度必须为 256
     */
    public void setColorMap(int[] colors) {
        if (colors == null || colors.length != COLORMAP_SIZE) {
            throw new IllegalArgumentException("colorMap must have " + COLORMAP_SIZE + " elements");
        }
        if (this.colorMap == null) {
            this.colorMap = new int[COLORMAP_SIZE];
        }
        System.arraycopy(colors, 0, this.colorMap, 0, COLORMAP_SIZE);
    }

    /**
     * 获取指定列的顶部颜色
     *
     * @param localX 局部 X 坐标 [0, 15]
     * @param localZ 局部 Z 坐标 [0, 15]
     * @return ARGB 颜色值，如果数据不可用返回 0
     */
    public int getColor(int localX, int localZ) {
        if (colorMap == null) return 0;
        if (localX < 0 || localX >= CHUNK_SIZE || localZ < 0 || localZ >= CHUNK_SIZE) {
            return 0;
        }
        return colorMap[localZ * CHUNK_SIZE + localX];
    }

    // ==================== GPU 资源管理 ====================

    /**
     * 设置 Billboard 纹理句柄
     */
    public void setBillboardTexture(long handle) {
        this.billboardTextureHandle = handle;
    }

    /**
     * 设置简化网格的 GPU 句柄
     */
    public void setMeshHandles(long vertexBuffer, long indexBuffer, int indexCount) {
        this.meshVertexBufferHandle = vertexBuffer;
        this.meshIndexBufferHandle = indexBuffer;
        this.meshIndexCount = indexCount;
    }

    /**
     * 释放 GPU 资源
     */
    public void releaseGPUResources() {
        this.billboardTextureHandle = 0L;
        this.meshVertexBufferHandle = 0L;
        this.meshIndexBufferHandle = 0L;
        this.meshIndexCount = 0;
    }

    // ==================== 状态管理 ====================

    /**
     * 标记数据为就绪
     */
    public void markReady() {
        this.state = State.READY;
        this.lastUpdateTimestamp = System.currentTimeMillis();
    }

    /**
     * 标记数据为过期
     */
    public void markDirty() {
        this.state = State.DIRTY;
    }

    /**
     * 设置区块状态
     *
     * @param state 新状态
     */
    public void setState(State state) {
        this.state = state;
    }

    /**
     * 估算内存占用（字节）
     */
    public int estimateMemoryBytes() {
        int size = 64; // 基础对象开销
        if (heightMap != null) size += HEIGHTMAP_SIZE * 2;
        if (colorMap != null) size += COLORMAP_SIZE * 4;
        return size;
    }

    // ==================== Getter ====================

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public DataType getDataType() { return dataType; }
    public State getState() { return state; }
    public long getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    public RenderiumLODManager.LODLevel getLodLevel() { return lodLevel; }
    public long getBillboardTextureHandle() { return billboardTextureHandle; }
    public long getMeshVertexBufferHandle() { return meshVertexBufferHandle; }
    public long getMeshIndexBufferHandle() { return meshIndexBufferHandle; }
    public int getMeshIndexCount() { return meshIndexCount; }
    public boolean hasHeightMap() { return heightMap != null; }
    public boolean hasColorMap() { return colorMap != null; }
    public boolean hasBillboard() { return billboardTextureHandle != 0L; }
    public boolean hasMesh() { return meshVertexBufferHandle != 0L; }
    public boolean isReady() { return state == State.READY; }

    public void setDataType(DataType type) { this.dataType = type; }
    public void setLodLevel(RenderiumLODManager.LODLevel level) { this.lodLevel = level; }

    @Override
    public String toString() {
        return String.format(
            "LODChunk{(%d,%d), type=%s, state=%s, lod=%s, mem=%dKB}",
            chunkX, chunkZ, dataType, state, lodLevel, estimateMemoryBytes() / 1024);
    }
}
