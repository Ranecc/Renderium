package com.ranecc.renderium.infrastructure.gpu;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 渲染调度器 — 单帧串行入口，按依赖顺序编排所有子系统的执行。
 *
 * <p>统一管理每帧的 Camera 捕获 → LOD 更新 → 剔除 → 渲染提交 流程。
 * 作为整个 Renderium 渲染管线的顶层编排器。
 *
 * <h3>每帧执行顺序</h3>
 * <pre>
 * RenderScheduler.frame()
 *   1. MCAdapter.extractCamera() — 捕获相机状态
 *   2. LODCoordinator.update() — 更新两套 LOD 系统
 *   3. CullingCoordinator.execute() — 三级剔除
 *   4. LODCoordinator.render() — 提交 LOD 渲染命令
 *   5. 性能统计
 * </pre>
 */
public final class RenderScheduler {

    private static final Logger LOGGER = Logger.getLogger("Renderium|RenderSched");

    private static volatile boolean initialized = false;
    private static volatile int frameNumber = 0;

    /** 性能追踪 */
    private static final AtomicLong totalFrameTimeNanos = new AtomicLong(0);
    private static volatile long lastFrameTimeNanos = 0L;
    private static volatile long cameraCaptureTime = 0L;
    private static volatile long lodUpdateTime = 0L;
    private static volatile long cullingTime = 0L;
    private static volatile long renderTime = 0L;

    private RenderScheduler() {}

    /**
     * 初始化渲染调度器。
     *
     * @param voxelLOD        voxel/LODSystem 实例（可为 null）
     * @param interceptionLOD interception/RenderiumLODSystem 实例（可为 null）
     */
    public static synchronized void initialize(Object voxelLOD, Object interceptionLOD) {
        if (initialized) return;

        CullingCoordinator.initialize();
        LODCoordinator.initialize(voxelLOD, interceptionLOD);

        initialized = true;
        LOGGER.info("RenderScheduler 初始化完成");
    }

    /**
     * 执行单帧渲染流程。
     *
     * @param camera         Minecraft Camera 对象
     * @param frustum        Minecraft Frustum 对象
     * @param deltaTime      帧间隔秒数
     * @param renderPass     Vulkan 渲染通道句柄
     * @param commandBuffer  Vulkan 命令缓冲区句柄
     */
    public static void frame(Object camera, Object frustum, float deltaTime,
                              Object renderPass, Object commandBuffer) {
        if (!initialized) {
            initialize(null, null);
        }

        long frameStart = System.nanoTime();
        frameNumber++;

        // ===== Step 1: 捕获相机状态 =====
        long t0 = System.nanoTime();
        MCAdapter.CameraState cam = MCAdapter.extractCamera(camera);
        cameraCaptureTime = System.nanoTime() - t0;

        // ===== Step 2: 更新 LOD 系统 =====
        long t1 = System.nanoTime();
        LODCoordinator.update(camera, frustum, deltaTime);
        lodUpdateTime = System.nanoTime() - t1;

        // ===== Step 3: 执行剔除（如果提供了候选数据） =====
        long t2 = System.nanoTime();
        // CullingCoordinator 当前被 LODCoordinator/LODSystem 内部调用
        // 未来此处可添加全局可见性列表管理
        cullingTime = System.nanoTime() - t2;

        // ===== Step 4: 提交 LOD 渲染命令 =====
        long t3 = System.nanoTime();
        LODCoordinator.render(renderPass, commandBuffer);
        renderTime = System.nanoTime() - t3;

        // ===== 性能统计 =====
        lastFrameTimeNanos = System.nanoTime() - frameStart;
        totalFrameTimeNanos.addAndGet(lastFrameTimeNanos);

        if (frameNumber % 300 == 0 && LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine(String.format(
                "帧 #%d: cam=%.3fms lod=%.3fms cull=%.3fms render=%.3fms total=%.3fms",
                frameNumber,
                cameraCaptureTime / 1_000_000.0,
                lodUpdateTime / 1_000_000.0,
                cullingTime / 1_000_000.0,
                renderTime / 1_000_000.0,
                lastFrameTimeNanos / 1_000_000.0));
        }
    }

    /**
     * 仅执行相机捕获 + LOD 更新（当渲染命令由外部触发时）。
     */
    public static void updateOnly(Object camera, Object frustum, float deltaTime) {
        if (!initialized) return;

        long start = System.nanoTime();
        MCAdapter.CameraState cam = MCAdapter.extractCamera(camera);
        LODCoordinator.update(camera, frustum, deltaTime);
        lastFrameTimeNanos = System.nanoTime() - start;
    }

    /**
     * 仅执行渲染提交（当更新已由外部触发时）。
     */
    public static void renderOnly(Object renderPass, Object commandBuffer) {
        if (!initialized) return;

        long start = System.nanoTime();
        LODCoordinator.render(renderPass, commandBuffer);
        lastFrameTimeNanos = System.nanoTime() - start;
    }

    // ==================== 查询 API ====================

    public static int getFrameNumber() { return frameNumber; }
    public static double getLastFrameTimeMillis() {
        return lastFrameTimeNanos / 1_000_000.0;
    }
    public static boolean isInitialized() { return initialized; }

    /**
     * 重置调度器。
     */
    public static synchronized void reset() {
        initialized = false;
        frameNumber = 0;
        totalFrameTimeNanos.set(0);
        lastFrameTimeNanos = 0L;
        LODCoordinator.initialize(null, null);
        CullingCoordinator.reset();
        LOGGER.fine("RenderScheduler 已重置");
    }
}
