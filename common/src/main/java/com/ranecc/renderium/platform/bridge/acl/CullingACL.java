// Renderium - Culling 专用防腐层
// 包装 CameraMotionDetector 的查询方法，供 Mixin 层或其他 ACL 查询相机状态

package com.ranecc.renderium.platform.bridge.acl;

import com.ranecc.renderium.feature.chunk.manager.CameraMotionDetector;
import com.ranecc.renderium.feature.chunk.manager.QualityMode;
import com.ranecc.renderium.feature.chunk.manager.RenderiumCullingScheduler;

/**
 * Culling 专用防腐层 —— 封装 {@link CameraMotionDetector} 的查询 API。
 *
 * <p>设计目的：
 * <ul>
 *   <li>Mixin 层只需调用 {@code acl.getOmega()}，无需知道 CameraMotionDetector</li>
 *   <li>跨子系统调用统一路径：所有 omega/分层边界 通过此层获取</li>
 * </ul>
 *
 * <h2>获取实例</h2>
 * <pre>
 * CullingACL acl = RenderiumACL.getInstance().getCullingACL();
 * double omega = acl.getOmega();
 * boolean coarse = acl.getQualityMode() == QualityMode.COARSE;
 * </pre>
 */
public final class CullingACL {

    private final CameraMotionDetector detector;

    private CullingACL(CameraMotionDetector detector) {
        this.detector = detector;
    }

    /** 
     * 工厂方法 —— 从 RenderiumCullingScheduler 获取 CameraMotionDetector。
     * 包内可见，通过 RenderiumACL.getCullingACL() 对外暴露。
     */
    static CullingACL getInstance(RenderiumCullingScheduler scheduler) {
        return new CullingACL(scheduler.getDetector());
    }

    // ==================== 查询 API ====================

    /** @return 合成角速度 (°/s) */
    public double getOmega() { return detector.getOmega(); }

    /** @return 合成线速度 (m/s) */
    public double getLinearVelocity() { return detector.getLinearVelocity(); }

    /** @return 当前质量档位 (FINE 或 COARSE) */
    public QualityMode getQualityMode() { return detector.getQualityMode(); }

    /** @return 3步预测位置 [x, y, z] */
    public double[] getPredictedPosition() { return detector.getPredictedPosition(); }

    /** @return PACELC L1 边界 (blocks) */
    public double getConsistencyBoundaryL1() { return detector.getConsistencyBoundaryL1(); }

    /** @return PACELC L2 边界 (blocks) */
    public double getConsistencyBoundaryL2() { return detector.getConsistencyBoundaryL2(); }

    /** @return 是否在 Zoom 中 */
    public boolean isZooming() { return detector.isZoomingActive(); }

    /** @return 截图兜底是否激活 */
    public boolean isScreenshotRefining() { return detector.isScreenshotRefining(); }

    /** @return 当前 FOV (°) */
    public double getFov() { return detector.getCurrentFov(); }
}
