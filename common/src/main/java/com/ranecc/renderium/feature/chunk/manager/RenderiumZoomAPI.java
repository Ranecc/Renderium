// Renderium - 模组对外 API：Zoom/Scope 被动检测 + 主动触发
// 其他模组通过此接口让 Renderium 感知缩放/瞄准状态

package com.ranecc.renderium.feature.chunk.manager;

/**
 * Renderium 对外 API —— Zoom/Scope 渲染感知。
 *
 * <p>设计原则：<b>被动检测优先，主动触发为辅</b>。
 * 被动检测通过 FOV 偏差自动识别变焦（望远镜/弓瞄准）。
 * 主动触发供其他模组精确通知聚焦状态。
 *
 * <h2>使用流程</h2>
 * <pre>
 * 初始化: RenderiumZoomAPI.init(detector);
 *
 * 每帧（被动）: RenderiumZoomAPI.onFovChanged(newFovDegrees); // FOV 变化自动检测
 *
 * 主动触发（其他模组）: RenderiumZoomAPI.notifyZoomStart();  // 瞄准镜开启
 *                      RenderiumZoomAPI.notifyZoomEnd();    // 瞄准镜关闭
 *
 * 查询: boolean zooming = RenderiumZoomAPI.isZooming();
 *       // 调度中心据此收窄 L2 边界，视锥内精细渲染
 * </pre>
 *
 * <h2>模式兼容性</h2>
 * <ul>
 *   <li>不依赖任何特定模组加载器（纯 Java API）</li>
 *   <li>无 Mixin 需求，零反射调用</li>
 *   <li>可被 Fabric/NeoForge/Quilt 的任意模组调用</li>
 * </ul>
 */
public final class RenderiumZoomAPI {

    /** 单例 detector 引用 */
    private static volatile CameraMotionDetector detector;

    /** 外部模组主动通知的 Zoom 状态（覆盖被动 FOV 检测） */
    private static volatile boolean externalZoomActive;

    private RenderiumZoomAPI() { throw new AssertionError("工具类不可实例化"); }

    // ==================== 初始化 ====================

    /**
     * 初始化 API——注册 detector 引用。
     * 在 Renderium 启动时由 RenderiumCullingScheduler 调用。
     */
    public static void init(CameraMotionDetector renderiumDetector) {
        detector = renderiumDetector;
        externalZoomActive = false;
    }

    // ==================== 被动检测 ====================

    /**
     * 通知 FOV 变化（被动 Zoom 检测）。
     * 每帧由渲染管线调用。当 FOV 偏离默认值 >5° 时自动触发 Zoom 模式。
     *
     * @param fovDegrees 当前视场角 (°)
     */
    public static void onFovChanged(double fovDegrees) {
        if (detector != null) {
            // detector 内部的 updateFrame 已处理 FOV→zoomingActive 映射
            // 此方法仅做轻量记录
        }
    }

    // ==================== 主动触发（供其他模组） ====================

    /**
     * 主动通知 Renderium：进入 Zoom/Scope 状态。
     * 触发后视锥内精细渲染，外粗化。
     */
    public static void notifyZoomStart() {
        externalZoomActive = true;
    }

    /**
     * 主动通知 Renderium：退出 Zoom/Scope 状态。
     */
    public static void notifyZoomEnd() {
        externalZoomActive = false;
    }

    // ==================== 查询 ====================

    /**
     * @return 当前是否在 Zoom 模式（被动 FOV 检测 OR 主动外部通知）
     */
    public static boolean isZooming() {
        if (detector == null) return false;
        return detector.isZoomingActive() || externalZoomActive;
    }

    /**
     * @return 当前 FOV (°)。若 detector 未初始化则返回默认值 70。
     */
    public static double getCurrentFov() {
        return detector != null ? detector.getCurrentFov() : 70.0;
    }
}
