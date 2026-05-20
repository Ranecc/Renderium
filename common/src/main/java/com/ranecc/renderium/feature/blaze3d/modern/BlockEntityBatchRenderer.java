package com.ranecc.renderium.feature.blaze3d.modern;

import java.util.*;

/**
 * 方块实体批量渲染器（按类型+纹理分组，减少 Draw Call）
 * <p>
 * 与 {@link ECSSceneGraph.EntityBatchRenderer} 类似，
 * 但针对方块实体（BlockEntity）的特性：位置固定、1x1 方块尺寸。
 */
public class BlockEntityBatchRenderer {

    private static final record BatchKey(int type, int textureIndex) {}

    private final Map<BatchKey, List<float[]>> batches = new HashMap<>();
    private float[] matrixBuffer = new float[16 * 256];
    private int instanceCount = 0;

    public void buildBatches(List<BlockEntityData> visibleEntities) {
        batches.clear();
        instanceCount = 0;

        for (BlockEntityData entity : visibleEntities) {
            BatchKey key = new BatchKey(entity.type, entity.textureIndex);
            batches.computeIfAbsent(key, k -> new ArrayList<>())
                   .add(entity.getTransformMatrix());
            instanceCount++;

            if (instanceCount * 16 >= matrixBuffer.length) {
                matrixBuffer = new float[matrixBuffer.length * 2];
            }
        }
    }

    public int getBatchCount() {
        return batches.size();
    }

    public int getInstanceCount() {
        return instanceCount;
    }

    public Map<BatchKey, List<float[]>> getBatches() {
        return batches;
    }
}
