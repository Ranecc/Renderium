// Renderium - 轻量 MC 抽象层
// 相机重定位上下文 - 来自 op⑨ repositionCamera

package com.renderium.bridge.mc;

/**
 * 相机重定位上下文（来自 op⑨ repositionCamera）
 * <p>
 * 携带相机位置和朝向信息。这是 CSM 级联分割的关键数据源。
 *
 * @see RenderiumLifecycleManager#fireBeforeCameraRepos(CameraContext)
 * @see RenderiumLifecycleManager#fireAfterCameraRepos(CameraContext)
 * @since 1.0.0
 */
public final class CameraContext {

    /** 相机世界坐标 X */
    public float x;

    /** 相机世界坐标 Y */
    public float y;

    /** 相机世界坐标 Z */
    public float z;

    /** 相机偏航角（弧度） */
    public float yaw;

    /** 相机俯仰角（弧度） */
    public float pitch;

    /** 视野区域是否变化 */
    public boolean viewAreaChanged;

    /** 重置到默认值 */
    public void reset() {
        x = 0; y = 0; z = 0;
        yaw = 0; pitch = 0;
        viewAreaChanged = false;
    }
}
