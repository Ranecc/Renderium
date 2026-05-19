// Renderium - Culling 专用防腐层
// 封装相机运动检测器的查询方法，供 Mixin 层或其他 ACL 查询相机状态

package com.ranecc.renderium.platform.bridge.acl;

import com.ranecc.renderium.domain.model.QualityMode;

/**
 * Culling 专用防腐层 —— 封装相机运动检测器的查询 API。
 *
 * <p>设计目的：
 * <ul>
 *   <li>Mixin 层只需调用 {@code acl.getOmega()}，无需知道具体实现</li>
 *   <li>跨子系统调用统一路径：所有 omega/分层边界 通过此层获取</li>
 * </ul>
 */
public final class CullingACL {

    private final double omega;
    private final double linearVelocity;
    private final double fov;
    private final QualityMode qualityMode;
    private final double[] predictedPosition;
    private final double consistencyBoundaryL1;
    private final double consistencyBoundaryL2;
    private final boolean zooming;
    private final boolean screenshotRefining;

    CullingACL(double omega, double linearVelocity, double fov,
               QualityMode qualityMode, double[] predictedPosition,
               double consistencyBoundaryL1, double consistencyBoundaryL2,
               boolean zooming, boolean screenshotRefining) {
        this.omega = omega;
        this.linearVelocity = linearVelocity;
        this.fov = fov;
        this.qualityMode = qualityMode;
        this.predictedPosition = predictedPosition;
        this.consistencyBoundaryL1 = consistencyBoundaryL1;
        this.consistencyBoundaryL2 = consistencyBoundaryL2;
        this.zooming = zooming;
        this.screenshotRefining = screenshotRefining;
    }

    // ==================== 查询 API ====================

    /** @return 合成角速度 (°/s) */
    public double getOmega() { return omega; }

    /** @return 合成线速度 (m/s) */
    public double getLinearVelocity() { return linearVelocity; }

    /** @return 当前质量档位 (FINE 或 COARSE) */
    public QualityMode getQualityMode() { return qualityMode; }

    /** @return 3步预测位置 [x, y, z] */
    public double[] getPredictedPosition() { return predictedPosition; }

    /** @return PACELC L1 边界 (blocks) */
    public double getConsistencyBoundaryL1() { return consistencyBoundaryL1; }

    /** @return PACELC L2 边界 (blocks) */
    public double getConsistencyBoundaryL2() { return consistencyBoundaryL2; }

    /** @return 是否在 Zoom 中 */
    public boolean isZooming() { return zooming; }

    /** @return 截图兜底是否激活 */
    public boolean isScreenshotRefining() { return screenshotRefining; }

    /** @return 当前 FOV (°) */
    public double getFov() { return fov; }
}
