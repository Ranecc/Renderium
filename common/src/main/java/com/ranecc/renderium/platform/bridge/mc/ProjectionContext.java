// Renderium - 轻量 MC 抽象层
// 投影矩阵上下文 - 来自 op⑥ setProjectionMatrix

package com.ranecc.renderium.platform.bridge.mc;

/**
 * 投影矩阵设置上下文（来自 op⑥ setProjectionMatrix）
 * <p>
 * 携带经过 bobHurt/bobView/screenEffect 变换后的最终投影矩阵。
 * 这是 ShadowMapNode 等节点获取投影矩阵的关键数据源。
 *
 * @see RenderiumLifecycleManager#fireBeforeSetProjection(ProjectionContext)
 * @see RenderiumLifecycleManager#fireAfterSetProjection(ProjectionContext)
 * @since 1.0.0
 */
public final class ProjectionContext {

    /** 最终投影矩阵（16 floats，column-major） */
    public final float[] projectionMatrix = new float[16];

    /** 视野角度（度数） */
    public float fov;

    /** 近裁剪面距离 */
    public float nearPlane;

    /** 远裁剪面距离 */
    public float farPlane;

    /** 重置到默认值 */
    public void reset() {
        identityMatrix(projectionMatrix);
        fov = 70.0f;
        nearPlane = 0.05f;
        farPlane = 1000.0f;
    }

    private static void identityMatrix(float[] m) {
        m[0] = 1;  m[4] = 0;  m[8]  = 0; m[12] = 0;
        m[1] = 0;  m[5] = 1;  m[9]  = 0; m[13] = 0;
        m[2] = 0;  m[6] = 0;  m[10] = 1; m[14] = 0;
        m[3] = 0;  m[7] = 0;  m[11] = 0; m[15] = 1;
    }
}
