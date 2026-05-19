// Renderium - 统一防腐层 (Anti-Corruption Layer)
// Mixin 层与优化子系统之间的唯一桥梁。
//
// 设计约束:
// 1. 不含任何 Minecraft 类引用（不 import net.minecraft.*）
// 2. 不含任何 Feature 层类引用（通过 Object 引用子系统）
// 3. 所有参数为基本类型或简单 DTO
// 4. Mixin 层只需调用 RenderiumACL，无需了解底层实现
//
// MC 版本更新时，只需更新 Mixin 类

package com.ranecc.renderium.platform.bridge.acl;

import com.ranecc.renderium.domain.model.QualityMode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * 统一防腐层 (Anti-Corruption Layer)。
 *
 * <p>这是 Mixin 层与 feature/ 优化子系统之间的唯一桥梁。
 * 不直接引用任何 Feature 类，通过 Object 引用 + 构造函数注入。
 */
public final class RenderiumACL {

    private static final Logger LOGGER = Logger.getLogger(RenderiumACL.class.getName());

    /** 单例 */
    private static volatile RenderiumACL instance;

    // ==================== 子系统引用（Object 类型，运行时由 Feature 层注入） ====================

    /** 统一调度中心 Object=RenderiumCullingScheduler */
    private volatile Object scheduler;

    /** 区块渲染段管理器 Object=RenderSectionManager */
    private volatile Object sectionManager;

    /** 区块构建管线 Object=ChunkBuildPipeline */
    private volatile Object buildPipeline;

    /** 透明面排序引擎 Object=TranslucentSortEngine */
    private volatile Object sortEngine;

    /** 是否已初始化 */
    private volatile boolean initialized;

    // ==================== 构造 ====================

    private RenderiumACL() {}

    /**
     * 初始化防腐层（由 Mod 入口在 Minecraft 加载后调用）。
     *
     * @param schedulerObj   调度中心 (RenderiumCullingScheduler)
     * @param sectionManagerObj 区块管理器 (RenderSectionManager)
     * @param buildPipelineObj  构建管线 (ChunkBuildPipeline)
     * @param sortEngineObj     排序引擎 (TranslucentSortEngine)
     */
    public synchronized void init(Object schedulerObj, Object sectionManagerObj,
                                  Object buildPipelineObj, Object sortEngineObj) {
        if (initialized) return;
        this.scheduler = schedulerObj;
        this.sectionManager = sectionManagerObj;
        this.buildPipeline = buildPipelineObj;
        this.sortEngine = sortEngineObj;
        this.initialized = true;
        LOGGER.info("RenderiumACL 初始化完成");
    }

    // ==================== 单例 ====================

    public static RenderiumACL getInstance() {
        if (instance == null) {
            synchronized (RenderiumACL.class) {
                if (instance == null) instance = new RenderiumACL();
            }
        }
        return instance;
    }

    // ==================== ACL 方法：帧入口 ====================

    public void onRenderFrame(double camX, double camY, double camZ,
                               double yaw, double pitch,
                               double deltaTime, double fovDegrees,
                               double renderDistBlocks) {
        if (!initialized) return;
        try {
            java.lang.reflect.Method onFrame = scheduler.getClass().getMethod(
                "onFrame", double.class, double.class, double.class,
                double.class, double.class, double.class, double.class, Object.class);
            onFrame.invoke(scheduler, camX, camY, camZ, yaw, pitch,
                deltaTime, renderDistBlocks, null);
        } catch (Exception e) {
            LOGGER.warning("onRenderFrame 调用失败: " + e.getMessage());
        }
    }

    // ==================== ACL 方法：区块事件 ====================

    public void onChunkAdded(int chunkX, int chunkY, int chunkZ,
                              int[][][] blocks) {
        if (!initialized) return;
        try {
            java.lang.reflect.Method onAdded = sectionManager.getClass().getMethod(
                "onSectionAdded", int.class, int.class, int.class, int[][][].class);
            onAdded.invoke(sectionManager, chunkX, chunkY, chunkZ, blocks);
        } catch (Exception e) {
            LOGGER.warning("onChunkAdded 调用失败: " + e.getMessage());
        }
    }

    public void onChunkRemoved(int chunkX, int chunkY, int chunkZ) {
        if (!initialized) return;
        try {
            java.lang.reflect.Method onRemoved = sectionManager.getClass().getMethod(
                "onSectionRemoved", int.class, int.class, int.class);
            onRemoved.invoke(sectionManager, chunkX, chunkY, chunkZ);
        } catch (Exception e) {
            LOGGER.warning("onChunkRemoved 调用失败: " + e.getMessage());
        }
    }

    public void onChunkChanged(int chunkX, int chunkY, int chunkZ,
                                int[][][] blocks) {
        if (!initialized) return;
        try {
            java.lang.reflect.Method onChanged = sectionManager.getClass().getMethod(
                "onSectionChanged", int.class, int.class, int.class, int[][][].class);
            onChanged.invoke(sectionManager, chunkX, chunkY, chunkZ, blocks);
        } catch (Exception e) {
            LOGGER.warning("onChunkChanged 调用失败: " + e.getMessage());
        }
    }

    // ==================== ACL 方法：爆炸 ====================

    public void onExplosion(List<Long> affectedChunkKeys) {
        if (!initialized) return;
        try {
            java.lang.reflect.Method notifyExplosion = sectionManager.getClass().getMethod(
                "notifyExplosion", List.class);
            notifyExplosion.invoke(sectionManager, affectedChunkKeys);
        } catch (Exception e) {
            LOGGER.warning("onExplosion 调用失败: " + e.getMessage());
        }
    }

    // ==================== ACL 方法：透明排序 ====================

    public int[] onTransparentSort(Object[] quads,
                                    double cameraX, double cameraY,
                                    double cameraZ) {
        if (!initialized || quads == null || quads.length == 0) {
            return new int[0];
        }
        try {
            java.lang.reflect.Method sort = sortEngine.getClass().getMethod(
                "sort", Object[].class, double.class, double.class,
                double.class, int.class, int.class, boolean.class);
            return (int[]) sort.invoke(sortEngine, quads, cameraX, cameraY, cameraZ,
                0, 0, false);
        } catch (Exception e) {
            LOGGER.warning("onTransparentSort 调用失败: " + e.getMessage());
            return new int[0];
        }
    }

    // ==================== 查询 API ====================

    public boolean isReady() { return initialized && scheduler != null; }

    public int getVisibleChunkCount() {
        if (scheduler == null) return 0;
        try {
            java.lang.reflect.Method getVisible = scheduler.getClass().getMethod("getVisibleChunks");
            Object visible = getVisible.invoke(scheduler);
            return visible instanceof List ? ((List<?>) visible).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public Object getScheduler() { return scheduler; }

    public CullingACL getCullingACL() {
        if (scheduler == null) return null;
        try {
            java.lang.reflect.Method getDetector = scheduler.getClass().getMethod("getDetector");
            Object detector = getDetector.invoke(scheduler);
            if (detector == null) return null;

            Class<?> dClass = detector.getClass();
            double omega = (double) dClass.getMethod("getOmega").invoke(detector);
            double linearVel = (double) dClass.getMethod("getLinearVelocity").invoke(detector);
            double fov = (double) dClass.getMethod("getCurrentFov").invoke(detector);
            QualityMode qualityMode = (QualityMode) dClass.getMethod("getQualityMode").invoke(detector);
            double[] predictedPos = (double[]) dClass.getMethod("getPredictedPosition").invoke(detector);
            double boundaryL1 = (double) dClass.getMethod("getConsistencyBoundaryL1").invoke(detector);
            double boundaryL2 = (double) dClass.getMethod("getConsistencyBoundaryL2").invoke(detector);
            boolean zooming = (boolean) dClass.getMethod("isZoomingActive").invoke(detector);
            boolean screenshotRefining = (boolean) dClass.getMethod("isScreenshotRefining").invoke(detector);

            return new CullingACL(omega, linearVel, fov, qualityMode, predictedPos,
                boundaryL1, boundaryL2, zooming, screenshotRefining);
        } catch (Exception e) {
            LOGGER.warning("getCullingACL 调用失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== 生命周期 ====================

    public void shutdown() {
        if (!initialized) return;
        try {
            java.lang.reflect.Method shutdown = buildPipeline.getClass().getMethod("shutdown");
            Object remaining = shutdown.invoke(buildPipeline);
            int count = remaining instanceof List ? ((List<?>) remaining).size() : 0;
            LOGGER.info("RenderiumACL 关闭: " + count + " 个残留任务已丢弃");
        } catch (Exception e) {
            LOGGER.warning("shutdown 调用失败: " + e.getMessage());
        }
        initialized = false;
    }
}
