// Renderium - 轻量 MC 抽象层
// 生命周期上下文类型 - 携带各渲染操作的具体数据

package com.renderium.bridge.mc;

/**
 * 全局 Uniform 更新上下文（来自 op② updateGlobalUniforms）
 * <p>
 * 携带 GameRenderer.render() 开头更新的全局 Uniform 数据。
 *
 * @see RenderiumLifecycleManager#fireBeforeGlobalUniform(GameRendererContext)
 * @see RenderiumLifecycleManager#fireAfterGlobalUniform(GameRendererContext)
 * @since 1.0.0
 */
public final class GameRendererContext {

    /** 窗口宽度（像素） */
    public int windowWidth;

    /** 窗口高度（像素） */
    public int windowHeight;

    /** 游戏刻 */
    public long gameTick;

    /** 相机世界坐标 X */
    public float cameraX;

    /** 相机世界坐标 Y */
    public float cameraY;

    /** 相机世界坐标 Z */
    public float cameraZ;

    /** 帧序号 */
    public int frameIndex;

    /** 重置到默认值 */
    public void reset() {
        windowWidth = 0; windowHeight = 0;
        gameTick = 0;
        cameraX = 0; cameraY = 0; cameraZ = 0;
        frameIndex = 0;
    }
}
