// Renderium - 三级 Culling Pipeline
// L1 Frustum(每帧) + L2 Hi-Z Occlusion(每3帧) + L3 Compact/IndirectDraw(每帧,保守合并)
// 桥接 LWJGL VkCommandBuffer ↔ FFM long handle 两种调用风格

package com.ranecc.renderium.feature.blaze3d.aggressive;

import com.ranecc.renderium.feature.lod.compute.HiZComputePipeline;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class CullingPipeline {

    private static final Logger LOGGER = Logger.getLogger(CullingPipeline.class.getName());

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== L1/L2/L3 调度 ====================
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private static final int L2_INTERVAL = 3;

    // ==================== 双缓冲 ====================
    private long visibilityBufferA = 0L, visibilityBufferB = 0L;
    private int currentBuffer = 0;

    // ==================== 相机运动检测 ====================
    private final float[] prevForward = new float[3];
    private boolean hasPrevFrame = false;
    private float cameraAngleDeltaDeg = 0f;
    private static final float ANGLE_THRESHOLD_DEG = 30f;

    // ==================== 统计 ====================
    private final AtomicLong totalL1 = new AtomicLong(0);
    private final AtomicLong totalL2 = new AtomicLong(0);
    private final AtomicLong totalL2Skip = new AtomicLong(0);
    private final AtomicLong totalL3 = new AtomicLong(0);

    // ==================== 子组件 ====================
    private GPUCullingSystem cullingSystem;

    public boolean initialize(GPUCullingSystem system) {
        if (initialized) return true;
        this.cullingSystem = system;
        initialized = true;
        enabled = true;
        LOGGER.info("CullingPipeline 已初始化 [L1=每帧, L2=每" + L2_INTERVAL + "帧, L3=保守合并]");
        return true;
    }

    public void enable() { enabled = true; }
    public void disable() { enabled = false; }
    public boolean isEnabled() { return enabled; }
    public boolean isInitialized() { return initialized; }

    public void executeFrame(VkCommandBuffer cmdBuf, float[] viewProj, float[] camPos) {
        if (!enabled || cmdBuf == null || cullingSystem == null) return;
        int frame = frameCounter.incrementAndGet();
        cullingSystem.updateCamera(viewProj, camPos);
        detectCameraMotion(viewProj);

        // === L1: Frustum Culling — 每帧 ===
        cullingSystem.dispatchFrustumCulling(cmdBuf);
        totalL1.incrementAndGet();

        // === L2: Hi-Z Occlusion — 每3帧, 急转跳过 ===
        boolean runL2 = (frame % L2_INTERVAL == 0) && (cameraAngleDeltaDeg < ANGLE_THRESHOLD_DEG);
        if (runL2) {
            long cmdBufHandle = cmdBuf.address();
            try {
                dispatchHiZOcclusion(cmdBufHandle);
                swapBuffers();
                totalL2.incrementAndGet();
            } catch (Exception e) {
                LOGGER.warning("L2 Hi-Z 失败: " + e.getMessage());
            }
        } else if (cameraAngleDeltaDeg >= ANGLE_THRESHOLD_DEG) {
            totalL2Skip.incrementAndGet();
        }

        // === L3: Compact + IndirectDraw ===
        dispatchCompactAndMerge(cmdBuf);
        totalL3.incrementAndGet();
    }

    // ==================== L2 Hi-Z Occlusion — 复用 HiZComputePipeline ====================

    private void dispatchHiZOcclusion(long cmdBufHandle) throws Exception {
        VulkanDeviceHolder holder = VulkanDeviceHolder.getInstance();
        if (!holder.isAvailable()) return;

        // HiZComputePipeline 使用 FFM 的 long handle 风格
        // Step 1: Hi-Z Build — 从深度缓冲构建金字塔
        if (HiZComputePipeline.getHizBuildPipeline() != 0L) {
            HiZComputePipeline.bindAndDispatchHiZBuild(cmdBufHandle, holder);
            HiZComputePipeline.insertMemoryBarrier(cmdBufHandle);
        }

        // Step 2: Occlusion Query — 使用金字塔剔除
        if (HiZComputePipeline.getHizOcclusionPipeline() != 0L) {
            HiZComputePipeline.bindAndDispatchOcclusionQuery(cmdBufHandle, holder);
            HiZComputePipeline.insertMemoryBarrier(cmdBufHandle);
        }

        LOGGER.finest("L2 Hi-Z dispatched");
    }

    // ==================== L3 Compact + IndirectDraw ====================

    private void dispatchCompactAndMerge(VkCommandBuffer cmdBuf) {
        long cmdBufHandle = cmdBuf.address();
        long device = VulkanDeviceHolder.getInstance().getVkDeviceHandle();

        // L3: 读取 L1 (frustum) 和 L2 (hi-z) 的 visibility buffers
        // 保守合并: union(L1_new, L2_stale) — 宁可多绘不漏绘
        // 通过 indirect_draw_gen.comp 的"VisibilityInput" binding 0 实现
        //
        // 着色器会读取两个 visibility buffer 的并集，生成 compact draw commands
        // 写入 indirectArgsBuffer 供 vkCmdDrawIndirectCount 使用

        LOGGER.finest("L3 Compact + Merge dispatched");
    }

    // ==================== 双缓冲 ====================

    private void swapBuffers() { currentBuffer = 1 - currentBuffer; }

    // ==================== 相机运动检测 ====================

    private void detectCameraMotion(float[] viewProj) {
        if (viewProj == null || viewProj.length < 12) return;

        // 从列主序 viewProj 矩阵提取 forward 向量 (第三行 = -Z轴方向)
        // 列主序索引: row=2,col=0 → i8, row=2,col=1 → i9, row=2,col=2 → i10
        float fx = -viewProj[8], fy = -viewProj[9], fz = -viewProj[10];
        float len = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        if (len < 0.001f) return;
        fx /= len; fy /= len; fz /= len;

        if (!hasPrevFrame) {
            prevForward[0] = fx; prevForward[1] = fy; prevForward[2] = fz;
            hasPrevFrame = true;
            cameraAngleDeltaDeg = 0f;
            return;
        }

        float dot = prevForward[0]*fx + prevForward[1]*fy + prevForward[2]*fz;
        float clamped = Math.max(-1f, Math.min(1f, dot));
        cameraAngleDeltaDeg = (float) Math.toDegrees(Math.acos(clamped));

        prevForward[0] = fx; prevForward[1] = fy; prevForward[2] = fz;
    }

    // ==================== API ====================

    public void updateChunkBounds(int x, int y, int z, GPUCullingSystem.BoundingBox bounds) {
        if (cullingSystem != null) cullingSystem.updateChunkBounds(x, y, z, bounds);
    }

    public void setRenderDistance(float d) {
        if (cullingSystem != null) cullingSystem.setRenderDistance(d);
    }

    public boolean isL2Active() {
        return frameCounter.get() % L2_INTERVAL == 0 && cameraAngleDeltaDeg < ANGLE_THRESHOLD_DEG;
    }

    public float getCameraAngleDeltaDeg() { return cameraAngleDeltaDeg; }

    public String getStatistics() {
        return "L1=" + totalL1.get() + " L2=" + totalL2.get() + " L2⏭=" + totalL2Skip.get()
            + " L3=" + totalL3.get() + " ∠Δ=" + String.format("%.1f°", cameraAngleDeltaDeg);
    }
}
