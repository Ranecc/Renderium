package com.ranecc.renderium.feature.blaze3d.render;

/**
 * 方块实体数据（用于可见性查询结果）
 * <p>
 * 轻量级数据传输对象，包含渲染所需的核心信息。
 * 相比完整 BlockEntity 对象大幅减少内存占用。
 */
public class BlockEntityData {
    public final int index;
    public final float x, y, z;
    public final int type;
    public final int textureIndex;

    public BlockEntityData(int index, float x, float y, float z, int type, int textureIndex) {
        this.index = index;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.textureIndex = textureIndex;
    }

    public float[] getTransformMatrix() {
        return new float[]{
            1, 0, 0, x,
            0, 1, 0, y,
            0, 0, 1, z,
            0, 0, 0, 1
        };
    }
}
