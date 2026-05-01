// Renderium - 标准网格数据格式
// 双模式架构的统一渲染输出格式

package com.ranecc.renderium.feature.renderopt.mesh.optimization.mesh;

import java.lang.foreign.MemorySegment;

/**
 * Renderium 标准网格数据格式。
 *
 * <p>这是双模式架构（兼容模式 + 狂暴模式）的统一输出格式。
 * 无论底层使用哪种优化策略，最终都必须产出此格式的数据，
 * 然后提交给 CommandBuffer 走 Vulkan 渲染管线。
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>SoA 布局</b>：Structure of Arrays，每个属性独立数组存储</li>
 *   <li><b>堆外内存</b>：使用 MemorySegment (Panama FFM)，避免 GC 压力</li>
 *   <li><b>Vulkan 友好</b>：数据可直接映射为 Vulkan Buffer</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RenderiumMeshData {

    /** 顶点位置数据（堆外内存）- 每个顶点 3 个 float (x, y, z) */
    private final MemorySegment positions;

    /** 顶点法线数据（堆外内存）- 每个顶点 3 个 float (nx, ny, nz) */
    private final MemorySegment normals;

    /** 顶点纹理坐标（堆外内存）- 每个顶点 2 个 float (u, v) */
    private final MemorySegment texCoords;

    /** 顶点颜色数据（堆外内存）- 每个顶点 1 个 int (ARGB) */
    private final MemorySegment colors;

    /** 顶点光照数据（堆外内存）- 每个顶点 1 个 int (block_light | sky_light) */
    private final MemorySegment lightData;

    /** 索引数据（堆外内存）- 用于 DrawElements 调用 */
    private final MemorySegment indices;

    /** 顶点数量 */
    private final int vertexCount;

    /** 索引数量 */
    private final int indexCount;

    /** 材质 ID（用于 Pipeline 选择） */
    private final int materialId;

    /** 区段索引（用于 LOD 判断） */
    private final int sectionIndex;

    /** 包围盒最小点 X */
    private final float minX;
    /** 包围盒最小点 Y */
    private final float minY;
    /** 包围盒最小点 Z */
    private final float minZ;
    /** 包围盒最大点 X */
    private final float maxX;
    /** 包围盒最大点 Y */
    private final float maxY;
    /** 包围盒最大点 Z */
    private final float maxZ;

    /**
     * 创建网格数据
     *
     * @param positions 位置数组
     * @param normals 法线数组
     * @param texCoords 纹理坐标数组
     * @param colors 颜色数组
     * @param lightData 光照数据
     * @param indices 索引数组
     * @param vertexCount 顶点数
     * @param indexCount 索引数
     * @param materialId 材质 ID
     * @param sectionIndex 区段索引
     * @param minX/Y/Z/maxX/Y/Z 包围盒
     */
    public RenderiumMeshData(
            MemorySegment positions,
            MemorySegment normals,
            MemorySegment texCoords,
            MemorySegment colors,
            MemorySegment lightData,
            MemorySegment indices,
            int vertexCount,
            int indexCount,
            int materialId,
            int sectionIndex,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ) {

        this.positions = positions;
        this.normals = normals;
        this.texCoords = texCoords;
        this.colors = colors;
        this.lightData = lightData;
        this.indices = indices;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.materialId = materialId;
        this.sectionIndex = sectionIndex;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    // ==================== Getter 方法 ====================

    public MemorySegment getPositions() { return positions; }
    public MemorySegment getNormals() { return normals; }
    public MemorySegment getTexCoords() { return texCoords; }
    public MemorySegment getColors() { return colors; }
    public MemorySegment getLightData() { return lightData; }
    public MemorySegment getIndices() { return indices; }
    public int getVertexCount() { return vertexCount; }
    public int getIndexCount() { return indexCount; }
    public int getMaterialId() { return materialId; }
    public int getSectionIndex() { return sectionIndex; }

    // ==================== 包围盒查询 ====================

    public float getMinX() { return minX; }
    public float getMinY() { return minY; }
    public float getMinZ() { return minZ; }
    public float getMaxX() { return maxX; }
    public float getMaxY() { return maxY; }
    public float getMaxZ() { return maxZ; }

    /**
     * 获取包围盒中心 X 坐标
     */
    public float getCenterX() { return (minX + maxX) * 0.5f; }

    /**
     * 获取包围盒中心 Y 坐标
     */
    public float getCenterY() { return (minY + maxY) * 0.5f; }

    /**
     * 获取包围盒中心 Z 坐标
     */
    public float getCenterZ() { return (minZ + maxZ) * 0.5f; }

    /**
     * 计算近似球体半径（用于粗略距离判断）
     */
    public float getBoundingRadius() {
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f;
    }

    // ==================== 内存管理 ====================

    /**
     * 释放所有堆外内存资源
     *
     * <p>调用后此对象不可再使用。通常在 Vulkan 上传完成后调用。
     */
    public void release() {
        // MemorySegment 的释放由 Arena 管理
        // 此处仅做标记或通知 Arena 回收
    }

    /**
     * 检查此网格数据是否有效（非空且有内容）
     */
    public boolean isValid() {
        return vertexCount > 0 && indexCount > 0 &&
               positions != null && !positions.equals(MemorySegment.NULL);
    }

    /**
     * 计算此网格数据的总内存占用（字节）
     */
    public long getTotalMemoryBytes() {
        long total = 0L;
        if (positions != null) total += vertexCount * 3L * 4L;  // 3 floats per vertex
        if (normals != null) total += vertexCount * 3L * 4L;
        if (texCoords != null) total += vertexCount * 2L * 4L;  // 2 floats per vertex
        if (colors != null) total += vertexCount * 4L;          // 1 int per vertex
        if (lightData != null) total += vertexCount * 4L;
        if (indices != null) total += indexCount * 4L;          // 1 int per index
        return total;
    }

    @Override
    public String toString() {
        return String.format("RenderiumMeshData{vertices=%d, indices=%d, material=%d, section=%d, bounds=[%.1f,%.1f,%.1f]x[%.1f,%.1f,%.1f]}",
                vertexCount, indexCount, materialId, sectionIndex,
                minX, minY, minZ, maxX, maxY, maxZ);
    }
}
