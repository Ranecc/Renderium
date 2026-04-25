// Renderium - Frustum Culler Interface
// Custom culling algorithms for advanced occlusion culling

package com.renderium.api;

import java.util.List;

/**
 * 视锥体/遮挡剔除器接口
 * 允许实现自定义的剔除算法来优化渲染性能
 */
public interface FrustumCuller {

    /**
     * 初始化剔除器
     *
     * @param maxDrawDistance 最大可视距离（方块数）
     */
    void initialize(int maxDrawDistance);

    /**
     * 更新相机信息
     * 每帧调用以更新视锥体
     *
     * @param cameraX 相机 X 坐标
     * @param cameraY 相机 Y 坐标
     * @param cameraZ 相机 Z 坐标
     * @param pitch 俯仰角（弧度）
     * @param yaw 偏航角（弧度）
     * @param fov 视野角度（弧度）
     */
    void updateCamera(float cameraX, float cameraY, float cameraZ,
                     float pitch, float yaw, float fov);

    /**
     * 检测一个 AABB 是否在视锥体内
     *
     * @param minX AABB 最小 X
     * @param minY AABB 最小 Y
     * @param minZ AABB 最小 Z
     * @param maxX AABB 最大 X
     * @param maxY AABB 最大 Y
     * @param maxZ AABB 最大 Z
     * @return 是否可见
     */
    boolean isVisible(float minX, float minY, float minZ,
                     float maxX, float maxY, float maxZ);

    /**
     * 执行遮挡剔除
     * 返回应该渲染的区块列表
     *
     * @param chunks 当前加载的所有区块
     * @param cameraX 相机 X
     * @param cameraY 相机 Y
     * @param cameraZ 相机 Z
     * @return 可见的区块索引列表
     */
    List<Integer> computeVisibleChunks(List<ChunkBounds> chunks,
                                       float cameraX, float cameraY, float cameraZ);

    /**
     * 获取检测到的边缘区块
     * 这些区块的邻居不可见，可能导致深度接缝
     *
     * @return 边缘区块列表
     */
    List<ChunkEdge> getExposedEdges();

    /**
     * 写入可见性纹理
     * 供着色器使用（GPU-driven 剔除）
     *
     * @param commandBuffer Vulkan 命令缓冲区
     * @param visibilityTexture 可见性纹理句柄
     */
    void writeVisibilityTexture(long commandBuffer, long visibilityTexture);

    /**
     * 区块边界
     */
    record ChunkBounds(
        int chunkX,
        int chunkY,
        int chunkZ,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ
    ) {}

    /**
     * 区块边缘信息
     */
    record ChunkEdge(
        int chunkX,
        int chunkY,
        int chunkZ,
        int neighborX,
        int neighborY,
        int neighborZ,
        Direction direction
    ) {
        public enum Direction {
            NORTH, SOUTH, EAST, WEST, UP, DOWN
        }
    }
}
