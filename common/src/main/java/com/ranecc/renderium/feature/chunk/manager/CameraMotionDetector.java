// Renderium - 相机运动检测器（唯一信号源）
// 整合: 卡尔曼预测 + 角速度检测 + 视距感知 + 距离分层
// 所有子系统通过此单点获取相机状态，消除重复计算

package com.ranecc.renderium.feature.chunk.manager;

import com.ranecc.renderium.feature.chunk.build.ProgressiveMeshRefiner;

import java.util.logging.Logger;

/**
 * 相机运动检测器 —— 统一调度中心的唯一信号源。
 *
 * <p>整合三个独立功能：
 * <ol>
 *   <li><b>卡尔曼位置预测</b>（委托 {@link PlayerMotionPredictor}）<br>
 *       3步预测误差 &lt; 0.14m，驱动预测性 chunk 预构建</li>
 *   <li><b>角速度与策略判定</b><br>
 *       统一 omega 计算，所有子系统从此读取而非各自计算</li>
 *   <li><b>速度感知质量降级 (QualityMode)</b><br>
 *       高速时粗层兜底（ω、v、卡尔曼误差三重门控 + 滞回）</li>
 *   <li><b>动态视距与分层边界</b><br>
 *       D_vis 感知（地下/室内自适应），PACELC 边界 L1/L2</li>
 * </ol>
 *
 * <h2>单一数据源原则</h2>
 * <pre>
 * 旧: ChunkBuildPipeline 算 omega, ProgressiveMeshRefiner 算 omega,
 *     RenderSectionManager 算 omega, GPUCullingSystem 算 angle — 四份重复
 * 新: CameraMotionDetector.update() → 所有子系统通过 getOmega() 获取
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * CameraMotionDetector detector = new CameraMotionDetector();
 * // 每帧
 * detector.updateFrame(x, y, z, yaw, pitch, deltaTime, visibleBlocks);
 * // 子系统查询
 * double omega = detector.getOmega();
 * double[] predictedNext = detector.getPredictedPosition();
 * double dL1 = detector.getConsistencyBoundaryL1();
 * RefinementStrategy strategy = detector.getStrategy();
 * </pre>
 *
 * @see PlayerMotionPredictor
 * @see ProgressiveMeshRefiner
 * @see RenderiumCullingScheduler
 */
public class CameraMotionDetector {

    private static final Logger LOGGER = Logger.getLogger(CameraMotionDetector.class.getName());

    // ==================== 内部组件 ====================

    /** 固定增益运动滤波器（替代在线Kalman，快40x） */
    private final FastMotionFilter motionFilter;

    /** 渐进精炼策略引用（用于统一 omega → strategy 映射） */
    private final ProgressiveMeshRefiner refiner;

    // ==================== 相机状态 ====================

    /** 当前相机位置 (world coords, blocks) */
    private double camX, camY, camZ;

    /** 当前偏航角 / 俯仰角 (°) */
    private double currentYaw, currentPitch;

    /** 上一帧偏航角 / 俯仰角 */
    private double prevYaw, prevPitch;

    /** 帧间隔 (秒) */
    private double deltaTime;

    /** 当前可见最远距离 (blocks)，用于动态分层 */
    private double visibleDistMax;

    // ==================== 派生信号（缓存，避免重复计算） ====================

    /** 合成角速度 (°/s) */
    private double cachedOmega;

    /** 合成线速度 (m/s, 从卡尔曼速度矢量取模) */
    private double cachedLinearVelocity;

    /** 当前质量档位 */
    private QualityMode cachedQualityMode;

    /** 连续触发 COARSE 的帧数（滞回计数） */
    private int coarseTriggerCount;

    /** 连续不触发 COARSE 的帧数（退出滞回计数） */
    private int fineStableCount;

    /** 上次输入变化的时间 (nanoTime)，用于截图兜底检测 */
    private long lastInputChangeNanos;

    /** 截图兜底：是否正在后台推送 Stage 2 */
    private boolean screenshotRefineInProgress;

    /** 当前精炼策略 */
    private ProgressiveMeshRefiner.RefinementStrategy cachedStrategy;

    /** 动态分层边界 (blocks) */
    private double cachedBoundaryL1;
    private double cachedBoundaryL2;

    /** 分层边界的平方（sqrt-free 分级判定） */
    private double cachedBoundaryL1Sq;
    private double cachedBoundaryL2Sq;

    /** 当前 FOV (°)，用于 Zoom 被动检测 */
    private double currentFov;

    /** 是否在变焦中（FOV ≠ 默认70°） */
    private boolean zoomingActive;

    /** 3步预测位置 */
    private final double[] predictedPosition;

    /** 时间戳（用于判断缓存是否过期） */
    private long lastUpdateFrame;

    // ==================== 速度感知质量降级常量 ====================

    /** 角速度上限：超过触发 COARSE (°/s) */
    private static final double COARSE_OMEGA_ENTER = 60.0;
    /** 角速度下限：低于此才恢复 FINE (°/s) */
    private static final double COARSE_OMEGA_EXIT = 30.0;

    /** 线速度上限 (m/s) */
    private static final double COARSE_VEL_ENTER = 10.0;
    /** 线速度下限 (m/s) */
    private static final double COARSE_VEL_EXIT = 6.0;

    /** 卡尔曼预测误差上限（块），超过触发 COARSE */
    private static final double COARSE_KALMAN_ERR_ENTER = 0.5;
    /** 卡尔曼预测误差下限（块） */
    private static final double COARSE_KALMAN_ERR_EXIT = 0.2;

    /** 退出 COARSE 需要的连续稳定帧数（500ms @ 60fps ≈ 30帧） */
    private static final int COARSE_EXIT_STABLE_FRAMES = 30;

    /** 截图兜底：连续静止无输入时间 (纳秒) */
    private static final long SCREENSHOT_IDLE_NS = 500_000_000L; // 500ms

    /** 默认 FOV (°) — MC 默认值 */
    private static final double DEFAULT_FOV = 70.0;

    /** Zoom 缩放检测阈值：FOV 与默认值偏差超过此值即视为变焦 */
    private static final double ZOOM_FOV_THRESHOLD = 5.0;

    /** 预计算 LUT：λ*(d) 的 d^(2/3) 因子 */
    private static final double LAMBDA_FACTOR = Math.pow(0.5, 1.0/3.0); // (β/2α)^(1/3), β/α=1/32

    // ==================== 常数 ====================

    /** 最小有效视距（地下回避零值） */
    private static final double MIN_VISIBLE_DIST = 16.0;

    /** L1 距离比例（D_L1 = D_vis * L1_RATIO） */
    private static final double L1_RATIO = 1.0 / 8.0;

    /** L2 距离比例 */
    private static final double L2_RATIO = 1.0 / 2.0;

    // ==================== 构造 ====================

    /**
     * @param kalman  卡尔曼预测器实例（可共享）
     * @param refiner 渐进精炼策略（可共享）
     */
    public CameraMotionDetector(FastMotionFilter motionFilter,
                                 ProgressiveMeshRefiner refiner) {
        this.motionFilter = motionFilter;
        this.refiner = refiner;
        this.camX = 0; this.camY = 0; this.camZ = 0;
        this.prevYaw = 0; this.prevPitch = 0;
        this.deltaTime = 0.05;
        this.visibleDistMax = 128.0;
        this.cachedStrategy = ProgressiveMeshRefiner.RefinementStrategy.PROGRESSIVE;
        this.cachedQualityMode = QualityMode.FINE;
        this.cachedLinearVelocity = 0.0;
        this.coarseTriggerCount = 0;
        this.fineStableCount = COARSE_EXIT_STABLE_FRAMES; // 初始处于 FINE
        this.lastInputChangeNanos = System.nanoTime();
        this.screenshotRefineInProgress = false;
        this.currentFov = DEFAULT_FOV;
        this.zoomingActive = false;
        this.predictedPosition = new double[3];
        this.lastUpdateFrame = 0;
    }

    /** 默认构造（自建滤波器+精炼器） */
    public CameraMotionDetector() {
        this(new FastMotionFilter(), new ProgressiveMeshRefiner());
    }

    // ==================== 每帧更新 ====================

    /**
     * 每帧主入口。更新所有内部状态并缓存派生信号。
     *
     * @param x/y/z        相机世界坐标 (blocks)
     * @param yaw          偏航角 (°)
     * @param pitch        俯仰角 (°)
     * @param dt           距上帧的时间 (秒)
     * @param visibleBlocks 当前可见最远距离 (blocks, 可用 renderDistance * 16)
     * @param frameNumber  当前帧号
     */
    public void updateFrame(double x, double y, double z,
                            double yaw, double pitch,
                            double dt, double visibleBlocks,
                            long frameNumber) {
        updateFrame(x, y, z, yaw, pitch, dt, visibleBlocks, DEFAULT_FOV, frameNumber);
    }

    /**
     * 每帧主入口（含 FOV）。更新所有内部状态并缓存派生信号。
     *
     * @param fovDegrees 当前视场角 (°)，用于 Zoom 被动检测
     */
    public void updateFrame(double x, double y, double z,
                            double yaw, double pitch,
                            double dt, double visibleBlocks,
                            double fovDegrees, long frameNumber) {
        // 0. 缓存原始输入
        this.camX = x; this.camY = y; this.camZ = z;
        this.currentYaw = yaw; this.currentPitch = pitch;
        this.deltaTime = Math.max(0.01, dt);
        this.visibleDistMax = Math.max(MIN_VISIBLE_DIST, visibleBlocks);
        this.currentFov = fovDegrees;
        this.zoomingActive = Math.abs(fovDegrees - DEFAULT_FOV) > ZOOM_FOV_THRESHOLD;

        // 1. 固定增益滤波器更新（<0.002ms，零矩阵运算）
        motionFilter.update(x, y, z, this.deltaTime);

        // 2. 计算3步预测位置
        double[] pred = motionFilter.predictNSteps(3);
        predictedPosition[0] = pred[0];
        predictedPosition[1] = pred[1];
        predictedPosition[2] = pred[2];

        // 3. 计算角速度（统一 omega 信号）——此点唯一，其他子系统必须 getOmega()
        this.cachedOmega = ProgressiveMeshRefiner.estimateOmega(
            prevYaw, prevPitch, yaw, pitch, this.deltaTime
        );
        this.prevYaw = yaw;
        this.prevPitch = pitch;

        // 3b. 线速度（从滤波器直接获取，无矩阵操作）
        double[] vel = motionFilter.getVelocity();
        this.cachedLinearVelocity = Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1] + vel[2]*vel[2]);

        // 3c. 判定质量档位（三重门控 + 滞回）
        this.cachedQualityMode = computeQualityMode(
            cachedOmega, cachedLinearVelocity, motionFilter.getPositionUncertainty()
        );

        // 3d. 截图兜底检测
        updateScreenshotGuard(cachedOmega, cachedLinearVelocity, frameNumber);

        // 4. 更新精炼策略
        refiner.updateStrategy(cachedOmega, System.nanoTime());
        this.cachedStrategy = refiner.getCurrentStrategy();

        // 5. 动态分层边界 + 预计算平方值（sqrt-free 分级判定）
        this.cachedBoundaryL1 = Math.max(MIN_VISIBLE_DIST, visibleBlocks * L1_RATIO);
        this.cachedBoundaryL2 = Math.max(32.0, visibleBlocks * L2_RATIO);
        this.cachedBoundaryL1Sq = cachedBoundaryL1 * cachedBoundaryL1;
        this.cachedBoundaryL2Sq = cachedBoundaryL2 * cachedBoundaryL2;

        this.lastUpdateFrame = frameNumber;
    }

    // ==================== 公共查询 API ====================

    /** @return 合成角速度 (°/s) */
    public double getOmega() { return cachedOmega; }

    /** @return 合成线速度 (m/s) */
    public double getLinearVelocity() { return cachedLinearVelocity; }

    /** @return 当前质量档位 */
    public QualityMode getQualityMode() { return cachedQualityMode; }

    /** @return 当前精炼策略 */
    public ProgressiveMeshRefiner.RefinementStrategy getStrategy() { return cachedStrategy; }

    /** @return L1/L2 分层边界 (blocks) */
    public double getConsistencyBoundaryL1() { return cachedBoundaryL1; }
    public double getConsistencyBoundaryL2() { return cachedBoundaryL2; }

    /** @return 当前相机位置 */
    public double getCameraX() { return camX; }
    public double getCameraY() { return camY; }
    public double getCameraZ() { return camZ; }
    public double getCurrentYaw() { return currentYaw; }
    public double getCurrentPitch() { return currentPitch; }

    /** @return 3步预测位置 [x, y, z] */
    public double[] getPredictedPosition() { return predictedPosition; }

    /** @return 当前可见最远距离 (blocks) */
    public double getVisibleDistMax() { return visibleDistMax; }

    /** @return 帧间隔 (秒) */
    public double getDeltaTime() { return deltaTime; }

    /** @return 上次更新帧号 */
    public long getLastUpdateFrame() { return lastUpdateFrame; }

    /** @return 底层运动滤波器（用于高级查询） */
    public FastMotionFilter getMotionFilter() { return motionFilter; }

    /** @return 当前 FOV (°) */
    public double getCurrentFov() { return currentFov; }

    /** @return 是否在变焦中（FOV 偏离默认值 >5°） */
    public boolean isZoomingActive() { return zoomingActive; }

    /** @return 底层精炼器（用于高级查询） */
    public ProgressiveMeshRefiner getRefiner() { return refiner; }

    // ==================== 分级判定 ====================

    /**
     * 公共距离平方工具——所有子系统统一使用此方法，消除 10+ 处 distSq 副本。
     *
     * @param wx/wy/wz 目标世界坐标 (blocks)
     * @return 到相机的距离平方 (blocks²)
     */
    public double distSqToCamera(double wx, double wy, double wz) {
        double dx = wx - camX, dy = wy - camY, dz = wz - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * sqrt-free 一致性层级判定（用预计算平方边界替代运行时 sqrt）。
     *
     * @param distSq 目标到相机的距离平方（从 distSqToCamera() 获取）
     * @return 0=L1强一致, 1=L2最终一致, 2=L3弱一致
     */
    public int classifyConsistencyLayerSq(double distSq) {
        if (distSq < cachedBoundaryL1Sq) return 0;
        if (distSq < cachedBoundaryL2Sq) return 1;
        return 2;
    }

    /**
     * 判定指定世界坐标的 PACELC 一致性层级（保留兼容，内部调用 sqrt-free 版本）。
     */
    public int classifyConsistencyLayer(double wx, double wy, double wz) {
        return classifyConsistencyLayerSq(distSqToCamera(wx, wy, wz));
    }

    /**
     * 判定指定世界坐标的目标 lambda（最优更新间隔，帧数）。
     */
    public long getOptimalLambda(double wx, double wy, double wz) {
        int layer = classifyConsistencyLayer(wx, wy, wz);
        return switch (layer) {
            case 0 -> 1;      // 强一致：每帧
            case 1 -> 3;      // 最终一致：~3帧
            default -> 7;     // 弱一致：~7帧
        };
    }

    // ==================== 相机急转判定 ====================

    /** 急转阈值（超过此角度跳过L2 Hi-Z遮挡剔除） */
    private static final double SHARP_TURN_THRESHOLD_DEG = 30.0;

    /**
     * 判定当前帧是否需要跳过 L2 Hi-Z 遮挡剔除。
     * 相机急转时 L2 结果基于旧视锥体，保守策略直接跳过。
     *
     * @return true 本帧跳过 L2（降级为 frustum-only）
     */
    public boolean shouldSkipL2Occlusion() {
        return cachedOmega * deltaTime > SHARP_TURN_THRESHOLD_DEG;
    }

    // ==================== QualityMode 三重门控 + 滞回 ====================

    /**
     * 根据 ω、v、卡尔曼误差判定质量档位。
     *
     * <p>滞回逻辑：
     * <pre>
     * 当前 FINE → 任一指标超过 ENTER 阈值 → coarseTriggerCount++
     *            → 连续3帧触发 → 切换到 COARSE
     *
     * 当前 COARSE → 所有指标均低于 EXIT 阈值 → fineStableCount++
     *             → 连续30帧稳定 → 切换到 FINE（500ms @ 60fps）
     * </pre>
     */
    private QualityMode computeQualityMode(double omega, double linearVel, double kalmanErr) {
        boolean speedTrigger = omega >= COARSE_OMEGA_ENTER
                            || linearVel >= COARSE_VEL_ENTER
                            || kalmanErr > COARSE_KALMAN_ERR_ENTER;

        if (cachedQualityMode == QualityMode.FINE) {
            if (speedTrigger) {
                coarseTriggerCount++;
                if (coarseTriggerCount >= 3) {
                    LOGGER.fine(String.format(
                        "COARSE 触发: ω=%.0f v=%.1f kErr=%.2f", omega, linearVel, kalmanErr));
                    return QualityMode.COARSE;
                }
                return QualityMode.FINE; // 仍需累积
            }
            coarseTriggerCount = 0;
            return QualityMode.FINE;
        }

        // 当前 COARSE → 检查是否满足退出条件
        boolean stable = omega < COARSE_OMEGA_EXIT
                      && linearVel < COARSE_VEL_EXIT
                      && kalmanErr < COARSE_KALMAN_ERR_EXIT;

        if (stable) {
            fineStableCount++;
            if (fineStableCount >= COARSE_EXIT_STABLE_FRAMES) {
                LOGGER.fine("FINE 恢复: 连续" + fineStableCount + "帧稳定");
                return QualityMode.FINE;
            }
            return QualityMode.COARSE; // 稳定但不足
        }
        fineStableCount = 0;
        return QualityMode.COARSE;
    }

    // ==================== 截图兜底 ====================

    /**
     * 检测是否需要启动截图兜底精炼。
     * <p>条件：连续 500ms 无角速度且无线速度 → 后台推 Stage 2。
     */
    private void updateScreenshotGuard(double omega, double linearVel, long frameNumber) {
        if (Math.abs(omega) < 1.0 && linearVel < 0.1) {
            long idleNanos = System.nanoTime() - lastInputChangeNanos;
            if (idleNanos > SCREENSHOT_IDLE_NS && !screenshotRefineInProgress) {
                screenshotRefineInProgress = true;
                LOGGER.fine("截图兜底: 检测到连续静止 >500ms, 启动后台 Stage 2 精炼");
            }
        } else {
            lastInputChangeNanos = System.nanoTime();
            screenshotRefineInProgress = false;
        }
    }

    /**
     * @return 截图兜底是否正在激活（调度中心据此推 Stage 2）
     */
    public boolean isScreenshotRefining() {
        return screenshotRefineInProgress;
    }
}
