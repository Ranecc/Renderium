// Renderium - 轻量 MC 抽象层
// 雾效上下文 - 来自 op⑦ updateFogBuffer

package com.ranecc.renderium.platform.bridge.mc;

/**
 * 雾效缓冲区更新上下文（来自 op⑦ updateFogBuffer）
 * <p>
 * 携带雾颜色、距离、密度等信息。
 *
 * @see RenderiumLifecycleManager#fireBeforeFogBuffer(FogContext)
 * @see RenderiumLifecycleManager#fireAfterFogBuffer(FogContext)
 * @since 1.0.0
 */
public final class FogContext {

    /** 雾颜色 RGBA */
    public final float[] color = new float[4];

    /** 雾起始距离 */
    public float start;

    /** 雾终止距离 */
    public float end;

    /** 雾密度 */
    public float density;

    /** 雾类型（0=线性, 1=指数, 2=指数平方） */
    public int type;

    /** 雾效是否启用 */
    public boolean enabled;

    /** 重置到默认值 */
    public void reset() {
        color[0] = 0; color[1] = 0; color[2] = 0; color[3] = 1;
        start = 0; end = 1000; density = 0;
        type = 0; enabled = false;
    }
}
