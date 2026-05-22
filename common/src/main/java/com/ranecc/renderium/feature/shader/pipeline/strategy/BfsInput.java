// ============================================================
// Renderium BFS Input - 遮挡剔除算法输入封装 (共享 DTO)
// ============================================================
// 业务链位置: AsyncRenderPipeline.processOcclusionCull()
//               → 构造 BfsInput (此文件)
//               → AdaptivePathSelector.selectBfsStrategy()
//               → JavaBfsStrategy / NativeBfsStrategy
//               → BfsOcclusionEngine (见 toCameraView())
//
// 作用: 承载 BFS 遮挡剔除所需的所有输入参数 (10 个字段)。
//       同时被 Java (BfsOcclusionEngine) 和 C++ (renderium_accel) 路径使用。
//
// 设计: 不可变对象，线程安全，可缓存复用。
// ============================================================

package com.ranecc.renderium.feature.shader.pipeline.strategy;

import com.ranecc.renderium.feature.shader.pipeline.BfsOcclusionEngine;

/**
 * BFS 遮挡剔除算法的输入参数
 * <p>
 * 不可变对象，封装执行 BFS 遮挡剔除所需的所有数据。
 * 此对象同时适用于 Java 和 C++ 实现路径。
 *
 * <h3>内存布局（与 C++ 结构体对齐）：</h3>
 * <pre>
 * struct BfsInput {
 *     int   originChunkX, originChunkY, originChunkZ;  // 12 bytes
 *     float cameraX, cameraY, cameraZ;                  // 12 bytes
 *     float renderDistance;                             // 4 bytes
 *     int   frameNumber;                                 // 4 bytes
 *     bool  useOcclusion;                               // 4 bytes (对齐)
 * };  // 总计: 36 bytes (无填充)
 * </pre>
 *
 * @since 1.0.0
 */
public final class BfsInput {

    /** 相机所在区块 X 坐标（16 格单位） */
    public final int originChunkX;

    /** 相机所在区块 Y 坐标 */
    public final int originChunkY;

    /** 相机所在区块 Z 坐标 */
    public final int originChunkZ;

    /** 相机位置 X（世界坐标） */
    public final float cameraX;

    /** 相机位置 Y（世界坐标） */
    public final float cameraY;

    /** 相机位置 Z（世界坐标） */
    public final float cameraZ;

    /** 渲染距离（方块数） */
    public final float renderDistance;

    /** 当前帧号（用于去重） */
    public final int frameNumber;

    /** 是否启用角度遮挡剔除 */
    public final boolean useOcclusion;

    /** 根区块（相机所在区块，BFS 起点） */
    public final BfsOcclusionEngine.OcclusionTask rootSection;

    /**
     * 构造 BFS 输入参数
     *
     * @param originChunkX   相机所在区块 X
     * @param originChunkY   相机所在区块 Y
     * @param originChunkZ   相机所在区块 Z
     * @param cameraX        相机 X 位置
     * @param cameraY        相机 Y 位置
     * @param cameraZ        相机 Z 位置
     * @param renderDistance 渲染距离
     * @param frameNumber    当前帧号
     * @param useOcclusion   是否启用遮挡
     * @param rootSection    根区块（可为 null，由策略内部处理）
     */
    public BfsInput(int originChunkX, int originChunkY, int originChunkZ,
                     float cameraX, float cameraY, float cameraZ,
                     float renderDistance, int frameNumber,
                     boolean useOcclusion,
                     BfsOcclusionEngine.OcclusionTask rootSection) {
        this.originChunkX = originChunkX;
        this.originChunkY = originChunkY;
        this.originChunkZ = originChunkZ;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.renderDistance = renderDistance;
        this.frameNumber = frameNumber;
        this.useOcclusion = useOcclusion;
        this.rootSection = rootSection;
    }

    /**
     * 创建 CameraView 对象（供 Java 引擎使用）
     *
     * 【返回值】
     * @return BfsOcclusionEngine.CameraView - 相机视锥体信息
     */
    public BfsOcclusionEngine.CameraView toCameraView() {
        BfsOcclusionEngine.CameraView cv = new BfsOcclusionEngine.CameraView();
        cv.posX = cameraX;
        cv.posY = cameraY;
        cv.posZ = cameraZ;
        cv.yaw = 0.0f;
        cv.pitch = 0.0f;
        return cv;
    }
}
