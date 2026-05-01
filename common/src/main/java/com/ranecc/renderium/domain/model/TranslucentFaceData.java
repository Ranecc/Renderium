// Renderium - 渲染优化模块 (狂暴模式专用)
// 透明面数据 - 用于排序和渲染

package com.ranecc.renderium.domain.model;

/**
 * 透明面数据
 * <p>
 * 封装单个半透明面的信息，
 * 作为 {@link TranslucentPipeline} 的输入单元。
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public final class TranslucentFaceData {

    /** 方块世界坐标 */
    public final int blockX, blockY, blockZ;

    /** 面方向 (0-5: DOWN, UP, NORTH, SOUTH, WEST, EAST) */
    public final int faceDirection;

    /** 距离相机的距离（用于排序） */
    public final float distanceFromCamera;

    /** 材质 ID */
    public final int materialId;

    /** 是否为动态方块（水、岩浆等流动方块） */
    public final boolean isDynamic;

    public TranslucentFaceData(int blockX, int blockY, int blockZ,
                                 int faceDirection, float distanceFromCamera,
                                 int materialId, boolean isDynamic) {
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.faceDirection = faceDirection;
        this.distanceFromCamera = distanceFromCamera;
        this.materialId = materialId;
        this.isDynamic = isDynamic;
    }
}
