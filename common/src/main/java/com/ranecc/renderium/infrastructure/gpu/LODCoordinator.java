package com.ranecc.renderium.infrastructure.gpu;

import com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * LOD 调度协调器 — 桥接体素 LOD 管线 (voxel) 与拦截层 LOD (interception)。
 *
 * <p>统一接收相机状态，分派到两套系统，合并结果。
 *
 * <h3>两套 LOD 系统</h3>
 * <ul>
 *   <li><b>voxel/LODSystem</b>: 体素 Mipmap 金字塔 LOD，GPU Driven + Indirect Draw</li>
 *   <li><b>interception/RenderiumLODSystem</b>: Blaze3D 拦截层距离+视角阈值 LOD，Dithering/Crossfade 过渡</li>
 * </ul>
 *
 * <h3>协作策略</h3>
 * <ul>
 *   <li>近距离 (LOD0-1): 拦截层精细控制，体素层辅助</li>
 *   <li>中距离 (LOD2-4): 体素 Mipmap 金字塔主导，拦截层调整过渡</li>
 *   <li>远距离 (LOD5+): 体素层代理几何，拦截层只做合并</li>
 * </ul>
 */
public final class LODCoordinator {

    private static final Logger LOGGER = Logger.getLogger("Renderium|LODCoord");

    private static volatile Object voxelLODSystem = null;
    private static volatile Object interceptionLODSystem = null;
    private static volatile boolean initialized = false;

    /** 统计 */
    private static final AtomicLong totalCoordinateCalls = new AtomicLong(0);
    private static volatile long lastCoordinateTimeNanos = 0L;

    private LODCoordinator() {}

    /**
     * 初始化 LOD 协调器。
     *
     * @param voxel         voxel/LODSystem 实例
     * @param interception  interception/RenderiumLODSystem 实例（可为 null）
     */
    public static synchronized void initialize(Object voxel, Object interception) {
        voxelLODSystem = voxel;
        interceptionLODSystem = interception;
        initialized = true;
        LOGGER.info("LODCoordinator 初始化完成, voxel=" + (voxel != null)
            + ", interception=" + (interception != null));
    }

    /**
     * 每帧更新两套 LOD 系统。
     *
     * @param camera    Minecraft Camera 对象
     * @param frustum   Minecraft Frustum 对象
     * @param deltaTime 帧间隔秒数
     */
    public static void update(Object camera, Object frustum, float deltaTime) {
        if (!initialized) return;

        long startTime = System.nanoTime();
        totalCoordinateCalls.incrementAndGet();

        // 1. 更新拦截层 LOD（始终执行，提供 UI 反馈）
        if (interceptionLODSystem != null) {
            try {
                var method = interceptionLODSystem.getClass()
                    .getMethod("processLOD",
                        com.ranecc.renderium.domain.model.LODContext.class);
                var builder = new com.ranecc.renderium.domain.model.LODContext.Builder();
                var context = builder.build();
                method.invoke(interceptionLODSystem, context);
            } catch (Exception e) {
                VulkanOperationGuard.markFailed(e);
                LOGGER.fine("interceptionLOD 更新失败: " + e.getMessage());
            }
        }

        // 2. 更新体素 LOD（核心渲染路径）
        if (voxelLODSystem != null) {
            try {
                var updateMethod = voxelLODSystem.getClass()
                    .getMethod("update", Object.class, Object.class, float.class);
                updateMethod.invoke(voxelLODSystem, camera, frustum, deltaTime);
            } catch (Exception e) {
                VulkanOperationGuard.markFailed(e);
                LOGGER.fine("voxelLOD 更新失败: " + e.getMessage());
            }
        }

        // 3. 同步拦截层结果到体素层（双向协调）
        syncInterceptionToVoxel();

        lastCoordinateTimeNanos = System.nanoTime() - startTime;
    }

    /**
     * 每帧渲染时调用，触发两套系统的渲染路径。
     *
     * @param renderPass    Vulkan 渲染通道句柄
     * @param commandBuffer Vulkan 命令缓冲区句柄
     */
    public static void render(Object renderPass, Object commandBuffer) {
        if (!initialized) return;

        // 体素 LOD 渲染（主要渲染路径）
        if (voxelLODSystem != null) {
            try {
                var renderMethod = voxelLODSystem.getClass()
                    .getMethod("render", Object.class, Object.class);
                renderMethod.invoke(voxelLODSystem, renderPass, commandBuffer);
            } catch (Exception e) {
                VulkanOperationGuard.markFailed(e);
                LOGGER.fine("voxelLOD render 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 将拦截层的 LOD 结果同步到体素层（方向: interception → voxel）。
     */
    private static void syncInterceptionToVoxel() {
        if (voxelLODSystem == null || interceptionLODSystem == null) return;
        try {
            var method = voxelLODSystem.getClass()
                .getMethod("syncToInterceptionLayer");
            method.invoke(voxelLODSystem);
        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
        }
    }

    /**
     * 分发体素 LOD 的金字塔构建触发。
     *
     * @param chunkX  区块 X
     * @param chunkZ  区块 Z
     * @param data    区块数据
     * @return true 如果构建成功
     */
    public static boolean triggerPyramidBuild(int chunkX, int chunkZ, byte[] data) {
        if (voxelLODSystem == null) return false;
        try {
            var method = voxelLODSystem.getClass()
                .getMethod("triggerPyramidBuild", int.class, int.class, byte[].class);
            return (boolean) method.invoke(voxelLODSystem, chunkX, chunkZ, data);
        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.fine("triggerPyramidBuild 失败: " + e.getMessage());
            return false;
        }
    }

    public static boolean isInitialized() { return initialized; }
    public static double getLastCoordinateTimeMillis() {
        return lastCoordinateTimeNanos / 1_000_000.0;
    }
}
