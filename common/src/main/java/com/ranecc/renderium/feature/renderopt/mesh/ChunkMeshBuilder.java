// Renderium - 区块网格构建器
// 结合 NeighborFaceCuller 和 CompactVertexFormat 构建优化的区块网格
// 支持多线程构建，使用对象池减少 GC 压力

package com.ranecc.renderium.feature.renderopt.mesh;
import com.ranecc.renderium.feature.culling.optimization.NeighborFaceCuller;

import com.ranecc.renderium.feature.renderopt.mesh.MeshData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 区块网格构建器。
 *
 * <p>结合 {@link NeighborFaceCuller} 和 {@link CompactVertexFormat}，
 * 从区块数据构建优化的渲染网格。
 *
 * <h2>构建流程</h2>
 * <ol>
 *   <li>遍历区块段中的所有方块</li>
 *   <li>使用 NeighborFaceCuller 计算面可见性</li>
 *   <li>对可见面生成顶点数据（使用 CompactVertexFormat 编码）</li>
 *   <li>按纹理/渲染层分组（减少状态切换）</li>
 *   <li>输出 {@link MeshData} 供渲染器使用</li>
 * </ol>
 *
 * <h2>多线程安全</h2>
 * <p>每个构建线程持有自己的 ChunkMeshBuilder 实例，
 * 构建完成后将 MeshData 提交到主线程的渲染队列。
 * 不需要同步机制。
 *
 * <h2>内存管理</h2>
 * <p>使用可复用的 ByteBuffer 和对象池，避免每帧大量 GC。
 * ByteBuffer 在 reset() 时清空内容但不释放底层内存。
 *
 * @see NeighborFaceCuller
 * @see CompactVertexFormat
 * @see MeshData
 * @author Renderium Team
 * @since 1.0.0
 */
public final class ChunkMeshBuilder {

    // ==================== 常量 ====================

    /** 区段尺寸 */
    private static final int SECTION_SIZE = 16;

    /** 区段方块总数 */
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    /** 初始顶点缓冲区大小（预估每个方块 4 个顶点 × 最坏情况） */
    private static final int INITIAL_VERTEX_BUFFER_SIZE = SECTION_VOLUME * 4 * 32;

    /** 初始索引缓冲区大小（预估每个方块 6 个索引 × 最坏情况） */
    private static final int INITIAL_INDEX_BUFFER_SIZE = SECTION_VOLUME * 6 * 4;

    // ==================== 依赖 ====================

    /** 邻居面剔除器（共享，只读） */
    private final NeighborFaceCuller faceCuller;

    /** 顶点格式 */
    private final CompactVertexFormat vertexFormat;

    // ==================== 缓冲区 ====================

    /** 顶点数据缓冲区 */
    private ByteBuffer vertexBuffer;

    /** 索引数据缓冲区 */
    private ByteBuffer indexBuffer;

    /** 当前顶点计数 */
    private int vertexCount;

    /** 当前索引计数 */
    private int indexCount;

    // ==================== 分组数据 ====================

    /** 按纹理分组的网格段 */
    private final List<MeshSection> sections = new ArrayList<>();

    /** 当前正在构建的纹理分组 */
    private int currentTextureId = -1;

    /** 当前分组的起始索引偏移 */
    private int currentSectionIndexStart = 0;

    // ==================== 统计 ====================

    /** 构建的网格总数 */
    private long totalMeshesBuilt = 0;

    /** 生成的总顶点数 */
    private long totalVerticesGenerated = 0;

    /** 被剔除的总面数 */
    private long totalFacesCulled = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建区块网格构建器
     *
     * @param faceCuller 邻居面剔除器
     * @param vertexFormat 顶点格式
     */
    public ChunkMeshBuilder(NeighborFaceCuller faceCuller, CompactVertexFormat vertexFormat) {
        this.faceCuller = faceCuller;
        this.vertexFormat = vertexFormat;
        this.vertexBuffer = ByteBuffer.allocate(INITIAL_VERTEX_BUFFER_SIZE).order(ByteOrder.nativeOrder());
        this.indexBuffer = ByteBuffer.allocate(INITIAL_INDEX_BUFFER_SIZE).order(ByteOrder.nativeOrder());
    }

    // ==================== 构建入口 ====================

    /**
     * 构建区块段的渲染网格
     *
     * <p>核心构建流程：
     * <ol>
     *   <li>重置缓冲区</li>
     *   <li>遍历所有方块</li>
     *   <li>面剔除 + 顶点生成</li>
     *   <li>按纹理分组</li>
     *   <li>输出 MeshData</li>
     * </ol>
     *
     * @param blockStates 方块状态 ID 数组（16×16×16）
     * @param sectionX 区段 X 坐标
     * @param sectionY 区段 Y 坐标
     * @param sectionZ 区段 Z 坐标
     * @param chunkX 区块 X 坐标（用于世界坐标计算）
     * @param chunkZ 区块 Z 坐标（用于世界坐标计算）
     * @return 构建完成的网格数据
     */
    public MeshData build(int[] blockStates,
                          int sectionX, int sectionY, int sectionZ,
                          int chunkX, int chunkZ) {
        // Step 1: 重置
        reset();

        // Step 2: 预计算面可见性
        byte[] faceVisibility = faceCuller.computeSectionFaceVisibility(
                blockStates, sectionX, sectionY, sectionZ);

        // Step 3: 遍历方块，生成网格
        int worldBaseX = (chunkX << 4) | sectionX;
        int worldBaseY = sectionY << 4;
        int worldBaseZ = (chunkZ << 4) | sectionZ;

        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = (y << 8) | (z << 4) | x;
                    int stateId = blockStates[index];
                    byte visibility = faceVisibility[index];

                    // 跳过空气和完全不可见的方块
                    if (stateId == 0 || visibility == NeighborFaceCuller.NONE_VISIBLE) {
                        continue;
                    }

                    // 为每个可见面生成顶点
                    emitFaces(stateId, visibility,
                            worldBaseX + x, worldBaseY + y, worldBaseZ + z);
                }
            }
        }

        // Step 4: 完成最后一个分组
        finishCurrentSection();

        // Step 5: 构建输出
        totalMeshesBuilt++;
        totalVerticesGenerated += vertexCount;

        return buildMeshData();
    }

    // ==================== 面发射 ====================

    /**
     * 为方块的可见面生成顶点数据
     *
     * @param stateId 方块状态 ID
     * @param visibility 面可见性位掩码
     * @param wx 世界 X 坐标
     * @param wy 世界 Y 坐标
     * @param wz 世界 Z 坐标
     */
    private void emitFaces(int stateId, byte visibility, int wx, int wy, int wz) {
        // 按面方向依次处理
        if ((visibility & NeighborFaceCuller.MASK_DOWN) != 0) {
            emitFace(stateId, NeighborFaceCuller.DOWN, wx, wy, wz);
        }
        if ((visibility & NeighborFaceCuller.MASK_UP) != 0) {
            emitFace(stateId, NeighborFaceCuller.UP, wx, wy, wz);
        }
        if ((visibility & NeighborFaceCuller.MASK_NORTH) != 0) {
            emitFace(stateId, NeighborFaceCuller.NORTH, wx, wy, wz);
        }
        if ((visibility & NeighborFaceCuller.MASK_SOUTH) != 0) {
            emitFace(stateId, NeighborFaceCuller.SOUTH, wx, wy, wz);
        }
        if ((visibility & NeighborFaceCuller.MASK_WEST) != 0) {
            emitFace(stateId, NeighborFaceCuller.WEST, wx, wy, wz);
        }
        if ((visibility & NeighborFaceCuller.MASK_EAST) != 0) {
            emitFace(stateId, NeighborFaceCuller.EAST, wx, wy, wz);
        }
    }

    /**
     * 发射单个面的顶点数据
     *
     * <p>每个面由 4 个顶点组成（四边形），通过 6 个索引（2 个三角形）绘制。
     *
     * @param stateId 方块状态 ID
     * @param face 面方向
     * @param wx 世界 X 坐标
     * @param wy 世界 Y 坐标
     * @param wz 世界 Z 坐标
     */
    private void emitFace(int stateId, int face, int wx, int wy, int wz) {
        // TODO: 根据方块状态获取纹理 ID
        int textureId = getTextureId(stateId, face);

        // 检查是否需要切换纹理分组
        if (textureId != currentTextureId) {
            finishCurrentSection();
            currentTextureId = textureId;
            currentSectionIndexStart = indexCount;
        }

        // 确保缓冲区有足够空间
        ensureVertexCapacity(4);
        ensureIndexCapacity(6);

        // 生成 4 个顶点
        int baseVertex = vertexCount;
        emitFaceVertices(face, wx, wy, wz, textureId);
        vertexCount += 4;

        // 生成 6 个索引（2 个三角形）
        emitQuadIndices(baseVertex);
        indexCount += 6;
    }

    /**
     * 发射面的 4 个顶点
     *
     * <p>根据面方向计算顶点位置，使用 CompactVertexFormat 编码
     */
    private void emitFaceVertices(int face, int wx, int wy, int wz, int textureId) {
        // 面的 4 个顶点偏移（相对于方块原点）
        // 每个面有 4 个顶点，顺序为：左下、右下、右上、左上
        float[][] offsets = FACE_VERTEX_OFFSETS[face];

        for (int i = 0; i < 4; i++) {
            // 位置
            if (vertexFormat.hasPosition()) {
                vertexBuffer.putFloat(wx + offsets[i][0]);
                vertexBuffer.putFloat(wy + offsets[i][1]);
                vertexBuffer.putFloat(wz + offsets[i][2]);
            }

            // 纹理坐标
            if (vertexFormat.hasTexCoord()) {
                if (vertexFormat.isCompressedTexCoord()) {
                    vertexBuffer.putInt(CompactVertexFormat.packTexCoord(
                            FACE_UV[i][0], FACE_UV[i][1]));
                } else {
                    vertexBuffer.putFloat(FACE_UV[i][0]);
                    vertexBuffer.putFloat(FACE_UV[i][1]);
                }
            }

            // 颜色（从方块状态获取，当前默认白色）
            if (vertexFormat.hasColor()) {
                vertexBuffer.putInt(CompactVertexFormat.packColor(1f, 1f, 1f, 1f));
            }

            // 法线
            if (vertexFormat.hasNormal()) {
                float[] normal = FACE_NORMALS[face];
                vertexBuffer.putInt(CompactVertexFormat.packNormal(normal[0], normal[1], normal[2]));
            }

            // 光照（从光照计算获取，当前默认标准亮度）
            if (vertexFormat.hasLight()) {
                vertexBuffer.putShort(CompactVertexFormat.packLight(0x00F000F0));
            }
        }
    }

    /**
     * 发射四边形的 6 个索引
     */
    private void emitQuadIndices(int baseVertex) {
        // 三角形 1: 0-1-2
        indexBuffer.putInt(baseVertex);
        indexBuffer.putInt(baseVertex + 1);
        indexBuffer.putInt(baseVertex + 2);
        // 三角形 2: 0-2-3
        indexBuffer.putInt(baseVertex);
        indexBuffer.putInt(baseVertex + 2);
        indexBuffer.putInt(baseVertex + 3);
    }

    // ==================== 面几何数据 ====================

    /** 每个面的 4 个顶点偏移 */
    private static final float[][][] FACE_VERTEX_OFFSETS = new float[6][][];

    /** 每个面的法线 */
    private static final float[][] FACE_NORMALS = new float[6][];

    /** 标准 UV 坐标 */
    private static final float[][] FACE_UV = {
            {0, 0}, {1, 0}, {1, 1}, {0, 1}
    };

    static {
        // DOWN (Y-)
        FACE_VERTEX_OFFSETS[NeighborFaceCuller.DOWN] = new float[][] {
                {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}
        };
        FACE_NORMALS[NeighborFaceCuller.DOWN] = new float[] {0, -1, 0};

        // UP (Y+)
        FACE_VERTEX_OFFSETS[NeighborFaceCuller.UP] = new float[][] {
                {0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}
        };
        FACE_NORMALS[NeighborFaceCuller.UP] = new float[] {0, 1, 0};

        // NORTH (Z-)
        FACE_VERTEX_OFFSETS[NeighborFaceCuller.NORTH] = new float[][] {
                {1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}
        };
        FACE_NORMALS[NeighborFaceCuller.NORTH] = new float[] {0, 0, -1};

        // SOUTH (Z+)
        FACE_VERTEX_OFFSETS[NeighborFaceCuller.SOUTH] = new float[][] {
                {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
        };
        FACE_NORMALS[NeighborFaceCuller.SOUTH] = new float[] {0, 0, 1};

        // WEST (X-)
        FACE_VERTEX_OFFSETS[NeighborFaceCuller.WEST] = new float[][] {
                {0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}
        };
        FACE_NORMALS[NeighborFaceCuller.WEST] = new float[] {-1, 0, 0};

        // EAST (X+)
        FACE_VERTEX_OFFSETS[NeighborFaceCuller.EAST] = new float[][] {
                {1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}
        };
        FACE_NORMALS[NeighborFaceCuller.EAST] = new float[] {1, 0, 0};
    }

    // ==================== 分组管理 ====================

    /**
     * 完成当前纹理分组
     */
    private void finishCurrentSection() {
        if (currentTextureId < 0) return;

        int indexCount = this.indexCount - currentSectionIndexStart;
        if (indexCount > 0) {
            sections.add(new MeshSection(currentTextureId, currentSectionIndexStart, indexCount));
        }

        currentTextureId = -1;
    }

    // ==================== 缓冲区管理 ====================

    /**
     * 重置构建器状态（复用缓冲区）
     */
    public void reset() {
        vertexBuffer.clear();
        indexBuffer.clear();
        vertexCount = 0;
        indexCount = 0;
        sections.clear();
        currentTextureId = -1;
        currentSectionIndexStart = 0;
    }

    /**
     * 确保顶点缓冲区有足够空间
     */
    private void ensureVertexCapacity(int additionalVertices) {
        int required = vertexCount * vertexFormat.getStride() + additionalVertices * vertexFormat.getStride();
        if (required > vertexBuffer.capacity()) {
            int newCapacity = Math.max(required, vertexBuffer.capacity() * 2);
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity).order(ByteOrder.nativeOrder());
            vertexBuffer.flip();
            newBuffer.put(vertexBuffer);
            vertexBuffer = newBuffer;
        }
    }

    /**
     * 确保索引缓冲区有足够空间
     */
    private void ensureIndexCapacity(int additionalIndices) {
        int required = indexCount * 4 + additionalIndices * 4;
        if (required > indexBuffer.capacity()) {
            int newCapacity = Math.max(required, indexBuffer.capacity() * 2);
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity).order(ByteOrder.nativeOrder());
            indexBuffer.flip();
            newBuffer.put(indexBuffer);
            indexBuffer = newBuffer;
        }
    }

    // ==================== 输出构建 ====================

    /**
     * 构建最终的 MeshData
     */
    private MeshData buildMeshData() {
        // 复制顶点数据
        vertexBuffer.flip();
        byte[] vertexData = new byte[vertexBuffer.remaining()];
        vertexBuffer.get(vertexData);

        // 复制索引数据
        indexBuffer.flip();
        byte[] indexData = new byte[indexBuffer.remaining()];
        indexBuffer.get(indexData);

        // 复制分组数据
        MeshSection[] sectionArray = sections.toArray(new MeshSection[0]);

        return new MeshData(vertexData, indexData, vertexCount, indexCount,
                vertexFormat, sectionArray);
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取方块的纹理 ID
     *
     * <p>TODO: 从方块注册表获取
     *
     * @param stateId 方块状态 ID
     * @param face 面方向
     * @return 纹理 ID
     */
    private int getTextureId(int stateId, int face) {
        // 占位：实际实现需要从方块注册表查询
        return stateId & 0xFF;
    }

    // ==================== 统计 ====================

    public long getTotalMeshesBuilt() { return totalMeshesBuilt; }
    public long getTotalVerticesGenerated() { return totalVerticesGenerated; }
    public long getTotalFacesCulled() { return totalFacesCulled; }

    public String getDiagnostics() {
        return String.format(
            "ChunkMeshBuilder{meshes=%d, vertices=%d, format=%s}",
            totalMeshesBuilt, totalVerticesGenerated, vertexFormat);
    }

    // ==================== 内部类 ====================

    /**
     * 网格段（按纹理分组）
     */
    public static final class MeshSection {
        /** 纹理 ID */
        public final int textureId;
        /** 索引缓冲区中的起始偏移（以索引为单位） */
        public final int indexStart;
        /** 索引数量 */
        public final int indexCount;

        MeshSection(int textureId, int indexStart, int indexCount) {
            this.textureId = textureId;
            this.indexStart = indexStart;
            this.indexCount = indexCount;
        }
    }
}
