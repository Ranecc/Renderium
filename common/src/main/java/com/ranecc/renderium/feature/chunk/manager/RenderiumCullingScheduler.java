// Renderium - 统一剔除与构建调度中心
// 从玩家视锥与点位出发，不重复计算，协调所有子系统
// 时域分层一致性 + 双缓冲保守合并

package com.ranecc.renderium.feature.chunk.manager;

import com.ranecc.renderium.domain.model.QualityMode;
import com.ranecc.renderium.feature.blaze3d.render.GPUCullingSystem;
import com.ranecc.renderium.feature.blaze3d.render.CullingPipeline;
import com.ranecc.renderium.feature.chunk.build.ChunkBuildPipeline;
import com.ranecc.renderium.feature.chunk.build.ChunkBuildTask;
import com.ranecc.renderium.feature.chunk.build.ProgressiveMeshRefiner;
import com.ranecc.renderium.feature.chunk.sort.TranslucentSortEngine;
import com.ranecc.renderium.platform.bridge.mc.FrameDataSnapshot;
import com.ranecc.renderium.platform.bridge.mc.MCRenderBridge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 统一剔除与构建调度中心。
 *
 * <p>从玩家视锥和位置出发，单帧流程：
 * <pre>
 * 每帧 onFrame(cameraState):
 *   1. CameraMotionDetector.update()       → ω, Δθ, 预测位置, 分层边界
 *   2. FrameBudgetAllocator.allocate(ω)    → 各子系统预算切片
 *   3. 下发 Chunk 构建任务（预算控制）
 *   4. Culling L1: Frustum+Distance          ← 每帧执行
 *   5. Culling L2: Hi-Z Occlusion            ← 预算+相机双重条件
 *   6. Culling L3: 保守合并 (L1_current ∪ L2_cached)   ← 零漏绘
 *   7. 透明面排序（预算控制）
 *   8. 主线程空闲时 steal 构建任务
 * </pre>
 *
 * <h2>双缓冲保守合并</h2>
 * <pre>
 *  bufferA = L1 当前帧结果（正确）
 *  bufferB = L2 上帧结果（可能滞后 2-3 帧）
 *  L3 输出 = bufferA ∪ bufferB    ← 宁可多绘不漏绘
 *  相机急转时 L2 SKIP → L3 = bufferA  ∪  ∅
 * </pre>
 *
 * <h2>不重复计算保证</h2>
 * <ul>
 *   <li>omega 只在 CameraMotionDetector 内算一次，所有子系统 getOmega()</li>
 *   <li>分层边界只在 CameraMotionDetector 内算一次</li>
 *   <li>预算只在 FrameBudgetAllocator 内算一次</li>
 *   <li>视锥平面提取只在 GPUCullingSystem.extractFrustumPlanes() 算一次</li>
 * </ul>
 *
 * @see CameraMotionDetector
 * @see FrameBudgetAllocator
 * @see RenderSectionManager
 */
public class RenderiumCullingScheduler {

    private static final Logger LOGGER = Logger.getLogger(RenderiumCullingScheduler.class.getName());

    // ==================== 唯一信号源 ====================

    /** 相机运动检测器（唯一 omega/预测/分层源） */
    private final CameraMotionDetector detector;

    /** 帧预算分配器 */
    private final FrameBudgetAllocator budgetAlloc;

    // ==================== 子系统引用 ====================

    /** 区块渲染段管理器 */
    private final RenderSectionManager sectionManager;

    /** 区块构建管线 */
    private final ChunkBuildPipeline buildPipeline;

    /** GPU 三级剔除管线 */
    private volatile CullingPipeline cullingPipeline;

    /** 透明面排序引擎 */
    private final TranslucentSortEngine sortEngine;

    // ==================== 双缓冲（时域一致性） ====================

    /** L1 结果（当前帧视锥可见性） */
    private volatile Set<Long> bufferA;

    /** L2 结果（上一帧 Hi-Z 结果，可能滞后） */
    private volatile Set<Long> bufferB;

    /** L2 上次执行时的帧号 */
    private final AtomicLong lastL2Frame;

    /** L2 执行间隔（帧数） */
    private static final long L2_INTERVAL_FRAMES = 3;

    // ==================== 惰性遍历缓存 ====================

    /** 上次 L1 重新计算的相机位置（惰性判断用） */
    private double lastCamX, lastCamY, lastCamZ;
    /** L1 双缓冲集 A（预分配容量，避免扩容） */
    private final HashSet<Long> l1SetA = new HashSet<>(256);
    /** L1 双缓冲集 B（初始为空，首帧后交替使用） */
    private final HashSet<Long> l1SetB = new HashSet<>(0);
    /** 上次 L1 可见集缓存（指向 l1SetA 或 l1SetB，双缓冲交替） */
    private Set<Long> lastL1Result = l1SetB;
    /** 上次 L1 执行时的 Section 版本号 */
    private int lastSectionVersion = 0;

    /** L2 可复用结果集（避免每次调用分配新 HashSet） */
    private final HashSet<Long> reusableL2Set = new HashSet<>(64);

    /** 惰性阈值：相机移动超过此值才重算 L1（平方距离，方块单位） */
    private static final double LAZY_MOVE_THRESHOLD_SQ = 4.0;

    // ==================== 统计 ====================

    private final AtomicLong currentFrame;
    private final AtomicLong totalFramesProceed;
    private volatile double lastFrameTimeMs;

    // ==================== 构造 ====================

    /**
     * @param sectionManager 区块渲染段管理器（需已初始化）
     * @param buildPipeline  区块构建管线（需已初始化并 start()）
     */
    public RenderiumCullingScheduler(RenderSectionManager sectionManager,
                                      ChunkBuildPipeline buildPipeline) {
        this.detector = new CameraMotionDetector(
            new FastMotionFilter(),
            buildPipeline.getRefiner()
        );
        this.budgetAlloc = new FrameBudgetAllocator();
        this.sectionManager = sectionManager;
        this.buildPipeline = buildPipeline;
        this.sortEngine = new TranslucentSortEngine();

        // 首帧 onFrame() 会立即覆盖，此处用不可变空集避免无用分配
        this.bufferA = Collections.emptySet();
        this.bufferB = Collections.emptySet();
        this.lastL2Frame = new AtomicLong(-1);
        this.currentFrame = new AtomicLong(0);
        this.totalFramesProceed = new AtomicLong(0);
    }

    // ==================== 主帧循环 ====================

    /**
     * 每帧主入口。从相机数据出发，驱动所有子系统。
     *
     * @param camX/Y/Z       相机世界坐标 (blocks)
     * @param yaw/pitch      相机偏航/俯仰角 (°)
     * @param deltaTime      帧间隔 (秒)
     * @param renderDistBlocks 渲染距离 (blocks)
     * @param dirtyChunks   本帧变化的chunk列表（可为null）
     */
    public void onFrame(double camX, double camY, double camZ,
                         double yaw, double pitch,
                         double deltaTime, double renderDistBlocks,
                         List<ChunkBuildTask> dirtyChunks) {
        long frame = currentFrame.incrementAndGet();
        long startNs = System.nanoTime();

        // ========== Phase 0: 更新唯一信号源 ==========
        detector.updateFrame(camX, camY, camZ, yaw, pitch,
            deltaTime, renderDistBlocks, frame);

        // ========== Phase 1: 分配预算 ==========
        FrameBudgetAllocator.Slice budget = budgetAlloc.allocate(detector, frame);

        // ========== Phase 2: 下发 Chunk 构建任务 ==========
        // 通知 RenderSectionManager 分层边界（从 detector 单一源获取，不重复计算）
        sectionManager.setVisibleDistMax(detector.getVisibleDistMax());
        sectionManager.setDetectorData(
            detector.getOmega(),
            detector.getConsistencyBoundaryL1(),
            detector.getConsistencyBoundaryL2()
        );

        // Zoom 变焦检测：视锥内精细渲染，外粗化
        boolean zooming = detector.isZoomingActive();
        double effectiveBoundaryL2 = zooming
            ? detector.getConsistencyBoundaryL1() // Zoom: L2 收窄到 L1
            : detector.getConsistencyBoundaryL2();

        // 速度感知质量降级：COARSE 模式下所有 chunk cap 在 Stage 1
        QualityMode qualityMode = detector.getQualityMode();
        int maxStage = qualityMode.maxAllowedStage();
        int graceFrames = qualityMode.recoveryGraceFrames();

        int chunkScheduled = 0;
        if (dirtyChunks != null && !dirtyChunks.isEmpty()) {
            chunkScheduled = scheduleChunksWithBudget(dirtyChunks, budget.chunkBuildBudgetNs(), maxStage);
        }
        // 也处理 RenderSectionManager 内部积压的 dirty chunk
        sectionManager.updateFrame(camX, camY, camZ, yaw, pitch, deltaTime);

        // 截图兜底：连续静止 500ms 后台推 Stage 2
        if (detector.isScreenshotRefining()) {
            pushVisibleToStage2(graceFrames);
        }

        // ========== Phase 3: Culling L1 (Frustum + Distance) ==========
        // 每帧执行。生成 bufferA（CPU 侧，用于构建调度决策）
        Set<Long> newBufferA = executeCullingL1();
        // 通知 GPU 管线本帧相机数据
        if (cullingPipeline != null) {
            float[] vp = buildViewProjectionMatrix();
            float[] camPos = new float[]{
                (float) camX, (float) camY, (float) camZ
            };
            cullingPipeline.setVisibilityBufferA(0L); // 由 FrameGraph 注入点设置实际 buffer
            cullingPipeline.setVisibilityBufferB(0L);
            // 注意: executeFrame(VkCommandBuffer) 由 FrameGraph Mixin 注入点调用
        }

        // ========== Phase 4: Culling L2 (Hi-Z Occlusion) ==========
        boolean skipL2 = budget.skipL2Occlusion() || detector.shouldSkipL2Occlusion();
        if (!skipL2 && frame - lastL2Frame.get() >= L2_INTERVAL_FRAMES) {
            Set<Long> newBufferB = executeCullingL2();
            if (newBufferB != null) {
                this.bufferB = newBufferB;
                lastL2Frame.set(frame);
            }
        } else if (skipL2) {
            reusableL2Set.clear();
            this.bufferB = reusableL2Set; // 急转：清空 L2 数据，复用集合避免分配
        }

        // ========== Phase 5: Culling L3 (保守合并) ==========
        Set<Long> merged = conservativeMerge(newBufferA, bufferB);
        this.bufferA = newBufferA;

        // ========== Phase 6: 透明面排序 ==========
        if (budget.sortBudgetNs() > 0) {
            dispatchSortWithBudget(budget.sortBudgetNs());
        }

        // ========== Phase 7: 主线程 steal 构建任务 ==========
        buildPipeline.tryStealTask();

        // ========== 统计 ==========
        long elapsed = System.nanoTime() - startNs;
        this.lastFrameTimeMs = elapsed / 1_000_000.0;
        totalFramesProceed.incrementAndGet();

        if (frame % 120 == 0) {
            LOGGER.fine(String.format(
                "[CullingScheduler] frame=%d ω=%.0f°/s v=%.1fm/s Q=%s%s strat=%s budget=[c=%dμs t=%dμs s=%dμs] " +
                "L1=%d L2=%d merged=%d time=%.2fms",
                frame, detector.getOmega(), detector.getLinearVelocity(),
                detector.getQualityMode(),
                detector.isZoomingActive() ? "+ZOOM" : "",
                detector.getStrategy(),
                budget.chunkBuildBudgetNs() / 1000, budget.cullingBudgetNs() / 1000,
                budget.sortBudgetNs() / 1000,
                newBufferA.size(), bufferB.size(), merged.size(),
                lastFrameTimeMs
            ));
        }
    }

    // ==================== Culling 各级执行 ====================

    /**
     * L1 剔除: Frustum + Distance Culling。
     * 每帧执行，0.05ms 预算。
     */
    private Set<Long> executeCullingL1() {
        // 惰性判断：相机未动 && Section 无变更时复用上一帧结果
        double camX = detector.getCameraX();
        double camY = detector.getCameraY();
        double camZ = detector.getCameraZ();
        double dx = camX - lastCamX, dy = camY - lastCamY, dz = camZ - lastCamZ;
        int currentSectionVersion = sectionManager.getSections().hashCode(); // 轻量版本号
        if (dx * dx + dy * dy + dz * dz < LAZY_MOVE_THRESHOLD_SQ
                && currentSectionVersion == lastSectionVersion && !lastL1Result.isEmpty()) {
            return lastL1Result;
        }

        // 双缓冲：选择非 lastL1Result 的集合作为工作集，避免每帧分配新 HashSet
        // 不变量：工作集 != bufferA（上一帧赋值），因此清空工作集不会影响外部持有的引用
        HashSet<Long> visible = (lastL1Result == l1SetA) ? l1SetB : l1SetA;
        visible.clear();
        float[] vp = buildViewProjectionMatrix();
        float[] camPosF = new float[]{(float) camX, (float) camY, (float) camZ};
        float[][] frustum = GPUCullingSystem.extractFrustumPlanes(vp);
        float maxDist = (float) detector.getVisibleDistMax();

        for (Map.Entry<Long, RenderSectionManager.SectionInfo> entry : sectionManager.getSections().entrySet()) {
            long key = entry.getKey();
            RenderSectionManager.SectionInfo info = entry.getValue();

            // Distance culling: 平方距离比较（JIT友好）
            double dxSq = info.worldCenterX() - camPosF[0];
            double dySq = info.worldCenterY() - camPosF[1];
            double dzSq = info.worldCenterZ() - camPosF[2];
            double distSq = dxSq * dxSq + dySq * dySq + dzSq * dzSq;
            if (distSq > maxDist * maxDist) continue;

            // Frustum culling: P-NA 法（6平面，单点测试/平面）
            if (!intersectsFrustum(
                info.worldMinX(), info.worldMinY(), info.worldMinZ(),
                info.worldMaxX(), info.worldMaxY(), info.worldMaxZ(),
                frustum
            )) continue;

            visible.add(key);
        }

        // 更新缓存
        lastCamX = camX; lastCamY = camY; lastCamZ = camZ;
        lastSectionVersion = currentSectionVersion;
        lastL1Result = visible;
        return visible;
    }

    /**
     * L2 剔除: Hi-Z 遮挡剔除。
     * 每3帧执行一次，0.1ms 预算。结果滞后2-3帧。
     * 当前为 CPU 端占位（基于上一帧 depth buffer 的近似）。
     *
     * @return 通过 Hi-Z 测试的 chunk 集合，null 表示不可用
     */
    private Set<Long> executeCullingL2() {
        // 复用 reusableL2Set，避免每 3 帧分配新 HashSet
        reusableL2Set.clear();
        // L2 Hi-Z 遮挡剔除由 GPU 端 CullingPipeline 执行
        // GPU 结果通过同步 fence + staging buffer 回读到 CPU
        // 当前：GPU 端 Buffer 已创建（Batch 2），DescriptorPool + 回读链路待后续补充
        // TODO: 实现 vkCmdCopyBuffer + fence wait + CPU readback 链路
        LOGGER.fine("L2 遮挡剔除: GPU readback 未实现，回退到空结果");
        return reusableL2Set; // 待 GPU readback 就绪后填充实际数据
    }

    /**
     * L3 合并: 保守取并集。宁多绘不漏绘。
     */
    private Set<Long> conservativeMerge(Set<Long> bufA, Set<Long> bufB) {
        if (bufB == null || bufB.isEmpty()) return new HashSet<>(bufA);
        Set<Long> merged = new HashSet<>(bufA.size() + bufB.size());
        merged.addAll(bufA);
        merged.addAll(bufB);
        return merged;
    }

    // ==================== Chunk 构建调度 ====================

    /**
     * 在预算内调度 chunk 构建任务（默认 maxStage=STAGE_FINE）。
     */
    private int scheduleChunksWithBudget(List<ChunkBuildTask> tasks, long budgetNs) {
        return scheduleChunksWithBudget(tasks, budgetNs, ProgressiveMeshRefiner.STAGE_FINE);
    }

    /**
     * 在预算内调度 chunk 构建任务，受 maxStage 上限约束。
     * COARSE 模式下 maxStage=1，速度感知提前终止精细流水线。
     */
    private int scheduleChunksWithBudget(List<ChunkBuildTask> tasks, long budgetNs, int maxStage) {
        long consumed = 0;
        int scheduled = 0;
        for (ChunkBuildTask task : tasks) {
            if (consumed + task.estimatedDurationNs() > budgetNs) break;
            int cappedTarget = Math.min(task.targetStage(), maxStage);
            if (cappedTarget < 0) continue;

            // COARSE + 速度感知：用 cappedTarget 覆盖原任务的目标阶段
            ChunkBuildTask adjustedTask = cappedTarget == task.targetStage()
                ? task
                : new ChunkBuildTask(
                    task.chunkKey(), task.worldX(), task.worldY(), task.worldZ(),
                    cappedTarget, task.priority(), task.consistencyLayer(),
                    task.deadlineFrame(), task.estimatedDurationNs(),
                    task.isEmergency(), task.blockData()
                );
            boolean important = adjustedTask.consistencyLayer() <= 1;
            if (buildPipeline.getJobQueue().schedule(adjustedTask, important)) {
                consumed += adjustedTask.estimatedDurationNs();
                scheduled++;
            }
        }
        return scheduled;
    }

    /**
     * 截图兜底：连续静止 500ms 后将视锥内所有 chunk 推到 Stage 2。
     */
    private void pushVisibleToStage2(int graceFrames) {
        for (long chunkKey : bufferA) {
            int currentStage = buildPipeline.getRefiner().getCurrentStage(chunkKey);
            if (currentStage < ProgressiveMeshRefiner.STAGE_FINE) {
                ChunkBuildTask task = new ChunkBuildTask(
                    chunkKey, 0, 0, 0,
                    ProgressiveMeshRefiner.STAGE_FINE,
                    2, 2,  // 低优先级
                    currentFrame.get() + graceFrames,
                    100_000L, false, null
                );
                buildPipeline.getJobQueue().schedule(task, false);
            }
        }
    }

    // ==================== 排序调度 ====================

    private void dispatchSortWithBudget(long budgetNs) {
        if (budgetNs < 50_000L) return;
        if (sortEngine == null) return;

        Set<Long> visibleChunks = getVisibleChunks();
        if (visibleChunks == null || visibleChunks.isEmpty()) return;

        // TranslucentSortEngine.sort() 需要 TranslucentQuad[] 作为输入
        // 当前 SectionInfo 仅存储方块数据(int[][][] blocks)，未缓存构建产出的面片
        // 透明面片由 ChunkBuildPipeline 构建阶段产出（GreedyMesher.MergedQuad），
        // 需通过 RenderSectionManager 补充 translucentQuads 字段桥接
        // TODO: SectionInfo 增加 translucentQuads 字段，BuildPipeline 产出时写入
        sortEngine.sort(
            new TranslucentSortEngine.TranslucentQuad[0],
            detector.getCameraX(), detector.getCameraY(), detector.getCameraZ(),
            detector.getCurrentYaw(), detector.getCurrentPitch(),
            false
        );
    }

    // ==================== 视锥面提取 ====================

    /**
     * AABB-Frustum 相交测试（P-NA 法）。
     * 正确性已由 tools/verify_frustum_culling.py 验证。
     */
    private static boolean intersectsFrustum(
        float mnX, float mnY, float mnZ, float mxX, float mxY, float mxZ,
        float[][] planes
    ) {
        for (int i = 0; i < 6; i++) {
            float nx = planes[i][0], ny = planes[i][1], nz = planes[i][2], d = planes[i][3];
            // p-vertex: AABB 在平面法线方向上最远的顶点
            float px = nx > 0 ? mxX : mnX;
            float py = ny > 0 ? mxY : mnY;
            float pz = nz > 0 ? mxZ : mnZ;
            if (nx * px + ny * py + nz * pz + d < 0) return false;
        }
        return true;
    }

    /**
     * 构建 view-projection 矩阵（P × V，列主序，OpenGL 约定）。
     *
     * <p>数据源优先级：
     * <ol>
     *   <li>LifecycleManager → FrameDataSnapshot：使用 MC 原生投影和视图矩阵，
     *       包含 bobHurt/bobView/screenEffect 等变换，更精确</li>
     *   <li>CameraMotionDetector 本地自建矩阵：当 FrameDataSnapshot 尚未填充时回退</li>
     * </ol>
     *
     * <p>注意：FrameDataSnapshot 内部存储 view × projection，
     * 此处取出分量后按 P × V 顺序重算，以匹配 extractFrustumPlanes 的 Gribb/Hartmann 约定。
     *
     * @return 16-float 列主序 P × V 矩阵
     */
    private float[] buildViewProjectionMatrix() {
        // 优先从 LifecycleManager 同步管线获取 MC 原生矩阵
        FrameDataSnapshot fd = MCRenderBridge.getCurrentFrameData();
        float[] projSrc = fd.getProjectionMatrix();
        float[] viewSrc = fd.getViewMatrix();
        if (!isIdentityMatrix(projSrc) && !isIdentityMatrix(viewSrc)) {
            return multiplyMM(projSrc, viewSrc);
        }

        // 回退：基于 CameraMotionDetector 本地自建矩阵
        double camX = detector.getCameraX();
        double camY = detector.getCameraY();
        double camZ = detector.getCameraZ();
        double yaw = detector.getCurrentYaw();
        double pitch = detector.getCurrentPitch();
        double farPlane = detector.getVisibleDistMax() * 16.0;

        float[] view = buildLookAtMatrix(camX, camY, camZ, yaw, pitch);
        float[] proj = buildPerspectiveMatrix(70.0f, 16.0f / 9.0f, 0.05f, (float) farPlane);
        return multiplyMM(proj, view);
    }

    // ==================== 矩阵工具方法 ====================

    /**
     * 构建 lookAt 视图矩阵 (列主序, OpenGL 约定)。
     *
     * @param camX/Y/Z 相机世界坐标
     * @param yaw      偏航角 (°)
     * @param pitch    俯仰角 (°)
     * @return 4x4 列主序视图矩阵
     */
    private static float[] buildLookAtMatrix(double camX, double camY, double camZ,
                                              double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        double cosYaw = Math.cos(yawRad), sinYaw = Math.sin(yawRad);
        double cosPitch = Math.cos(pitchRad), sinPitch = Math.sin(pitchRad);

        // 前向向量 (Minecraft: Y-up, X-east, Z-south, yaw=0 指向 Z-)
        double fx = -sinYaw * cosPitch;
        double fy = -sinPitch;
        double fz = cosYaw * cosPitch;
        double fLen = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fLen; fy /= fLen; fz /= fLen;

        // 上向量 (世界 Y+)
        double ux = 0.0, uy = 1.0, uz = 0.0;

        // 右向量 = up × forward  (注意: 叉积后再取反以匹配 OpenGL lookAt)
        double rx = uy * fz - uz * fy;
        double ry = uz * fx - ux * fz;
        double rz = ux * fy - uy * fx;
        double rLen = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rLen; ry /= rLen; rz /= rLen;

        // 重新计算正交上向量 = forward × right
        ux = fy * rz - fz * ry;
        uy = fz * rx - fx * rz;
        uz = fx * ry - fy * rx;

        float[] m = new float[16];
        // 列主序
        m[0] = (float) rx;  m[4] = (float) ux;  m[8]  = (float) fx;  m[12] = 0f;
        m[1] = (float) ry;  m[5] = (float) uy;  m[9]  = (float) fy;  m[13] = 0f;
        m[2] = (float) rz;  m[6] = (float) uz;  m[10] = (float) fz;  m[14] = 0f;
        m[3] = 0f;          m[7] = 0f;          m[11] = 0f;           m[15] = 1f;

        // 平移: -R^T * eye
        m[12] = -(float)(rx * camX + ry * camY + rz * camZ);
        m[13] = -(float)(ux * camX + uy * camY + uz * camZ);
        m[14] = -(float)(fx * camX + fy * camY + fz * camZ);

        return m;
    }

    /**
     * 构建透视投影矩阵 (列主序, OpenGL 约定, 右手系)。
     *
     * @param fovDegrees 垂直视场角 (°)
     * @param aspect     宽高比
     * @param near       近裁剪面
     * @param far        远裁剪面
     * @return 4x4 列主序投影矩阵
     */
    private static float[] buildPerspectiveMatrix(float fovDegrees, float aspect,
                                                   float near, float far) {
        float f = 1.0f / (float) Math.tan(Math.toRadians(fovDegrees) / 2.0);
        float nf = 1.0f / (near - far);

        float[] m = new float[16];
        m[0] = f / aspect;  m[4] = 0f;  m[8]  = 0f;            m[12] = 0f;
        m[1] = 0f;          m[5] = f;   m[9]  = 0f;            m[13] = 0f;
        m[2] = 0f;          m[6] = 0f;  m[10] = (far + near) * nf;  m[14] = 2f * far * near * nf;
        m[3] = 0f;          m[7] = 0f;  m[11] = -1f;           m[15] = 0f;

        return m;
    }

    /**
     * 4x4 矩阵乘法 (列主序): result = lhs × rhs。
     */
    private static float[] multiplyMM(float[] lhs, float[] rhs) {
        float[] result = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += lhs[k * 4 + row] * rhs[col * 4 + k];
                }
                result[col * 4 + row] = sum;
            }
        }
        return result;
    }

    /**
     * 检测 4×4 列主序矩阵是否为单位矩阵。
     *
     * @param m 16-float 列主序矩阵
     * @return true 如果矩阵等于 4×4 单位矩阵
     */
    private static boolean isIdentityMatrix(float[] m) {
        return m[0] == 1.0f && m[1] == 0.0f && m[2] == 0.0f && m[3] == 0.0f
            && m[4] == 0.0f && m[5] == 1.0f && m[6] == 0.0f && m[7] == 0.0f
            && m[8] == 0.0f && m[9] == 0.0f && m[10] == 1.0f && m[11] == 0.0f
            && m[12] == 0.0f && m[13] == 0.0f && m[14] == 0.0f && m[15] == 1.0f;
    }

    // ==================== 查询 API ====================

    /** @return 当前帧可见 chunk 集合（L3合并后） */
    public Set<Long> getVisibleChunks() { return bufferA; }

    /** @return 相机运动检测器 */
    public CameraMotionDetector getDetector() { return detector; }

    /** @return 帧预算分配器 */
    public FrameBudgetAllocator getBudgetAllocator() { return budgetAlloc; }

    /** @return 区块构建管线 */
    public ChunkBuildPipeline getBuildPipeline() { return buildPipeline; }

    /** 注入 GPU 三级剔除管线 */
    public void setCullingPipeline(CullingPipeline pipeline) { this.cullingPipeline = pipeline; }

    /** @return GPU 剔除管线（可能为 null） */
    public CullingPipeline getCullingPipeline() { return cullingPipeline; }

    /** @return 透明排序引擎 */
    public TranslucentSortEngine getSortEngine() { return sortEngine; }

    /** @return 区块管理器 */
    public RenderSectionManager getSectionManager() { return sectionManager; }

    /** @return 上一帧耗时 (ms) */
    public double getLastFrameTimeMs() { return lastFrameTimeMs; }

    /** @return 已处理帧数 */
    public long getTotalFramesProceed() { return totalFramesProceed.get(); }
}
