// Renderium - 轻量 MC 抽象层
// 模型视图矩阵上下文 - 来自 op⑩ setModelViewMatrix

package com.ranecc.renderium.platform.bridge.mc;

/**
 * 模型视图矩阵设置上下文（来自 op⑩ setModelViewMatrix）
 * <p>
 * 携带 LevelRenderer 设置的模型视图矩阵。
 * 这是 TAA、运动矢量计算的关键数据源。
 *
 * @see RenderiumLifecycleManager#fireBeforeModelViewSet(MatrixContext)
 * @see RenderiumLifecycleManager#fireAfterModelViewSet(MatrixContext)
 * @since 1.0.0
 */
public final class MatrixContext {

    /** 模型视图矩阵（16 floats，column-major） */
    public final float[] modelViewMatrix = new float[16];

    /** 重置到默认值（单位矩阵） */
    public void reset() {
        identityMatrix(modelViewMatrix);
    }

    private static void identityMatrix(float[] m) {
        m[0] = 1;  m[4] = 0;  m[8]  = 0; m[12] = 0;
        m[1] = 0;  m[5] = 1;  m[9]  = 0; m[13] = 0;
        m[2] = 0;  m[6] = 0;  m[10] = 1; m[14] = 0;
        m[3] = 0;  m[7] = 0;  m[11] = 0; m[15] = 1;
    }
}
