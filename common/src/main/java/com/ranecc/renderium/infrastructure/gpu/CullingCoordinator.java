package com.ranecc.renderium.infrastructure.gpu;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;

/**
 * 剔除调度中心 — 统一三级剔除管线的调度入口。
 *
 * <p>在 GPUCullingPipeline + LodCullingComputePass 之上提供
 * 一个统一的 {@link #execute(float[], Object, BitSet, int)} 入口。
 *
 * <h3>调度策略</h3>
 * <ul>
 *   <li>GPU 路径（优先级高）: LodCullingComputePass Compute Shader → AMD / NVIDIA GPU 加速</li>
 *   <li>CPU 路径（降级）: GPUCullingPipeline CPU fallback</li>
 * </ul>
 *
 * <h3>三级剔除流程</h3>
 * <pre>
 * 全部候选
 *   ↓ Stage 1: Distance Culling — 平方距离比较，排除超出 maxDistance
 *   ↓ Stage 2: Frustum Culling — 6 平面 AABB 测试
 *   ↓ Stage 3: Hi-Z Occlusion — GPU Compute Shader 深度金字塔
 *   ↓
 * 最终可见性 BitSet
 * </pre>
 */
public final class CullingCoordinator {

    private static final Logger LOGGER = Logger.getLogger("Renderium|CullingCoord");

    private static volatile boolean initialized = false;
    private static volatile boolean gpuCullingEnabled = false;

    // ==================== 3 槽无锁环形缓冲区（Hi-Z 遮挡异步回读）====================

    private static final int RING_SIZE = 3;

    /** 每个槽的 fence（由 submitAsync 写入，checkFence 轮询） */
    private static final long[] slotFences = new long[RING_SIZE];

    /** 每个槽回读的可见性结果（Phase 1 写入，execute 读取） */
    private static final BitSet[] slotResults = new BitSet[RING_SIZE];

    /** head = 下一帧 GPU 写入的槽，tail = 最旧待读的槽 */
    private static int head = 0;
    private static int tail = 0;

    /** 缓存的最新可用结果（任一槽读完即更新） */
    private static volatile BitSet cachedHiZResult = null;

    /** 最近一次剔除结果统计 */
    private static final AtomicLong totalCulled = new AtomicLong(0);
    private static volatile long lastCullTimeNanos = 0L;

    private CullingCoordinator() {}

    /**
     * 初始化剔除调度中心。
     */
    public static synchronized void initialize() {
        if (initialized) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        VulkanSyncManager.init(device);

        gpuCullingEnabled = LodCullingComputePass.isInitialized()
            && device != 0L
            && !VulkanOperationGuard.isFailed();

        initialized = true;
        LOGGER.info("CullingCoordinator 初始化完成, GPU路径=" + gpuCullingEnabled);
    }

    /**
     * 执行三级剔除管线，返回可见性掩码。
     *
     * @param cameraPos      相机位置 [x, y, z]
     * @param frustum        Minecraft Frustum 对象（用于视锥体测试）
     * @param candidates     候选区块可见性掩码（输入输出，bit[i]=true 表示第 i 个候选参与剔除）
     * @param candidateCount 候选数量
     * @return 最终可见性掩码（bit[i]=true 表示可见）
     */
    public static BitSet execute(float[] cameraPos, Object frustum,
                                  BitSet candidates, int candidateCount) {
        if (!initialized) {
            initialize();
        }
        if (candidates == null || candidateCount <= 0) {
            return new BitSet(0);
        }

        long startTime = System.nanoTime();

        // ===== Stage 1: Distance Culling =====
        BitSet afterDist = applyDistanceCulling(cameraPos, candidateCount, candidates);
        int afterDistCount = afterDist.cardinality();

        if (afterDistCount == 0) {
            lastCullTimeNanos = System.nanoTime() - startTime;
            return afterDist;
        }

        // ===== Stage 2: Frustum Culling =====
        BitSet afterFrustum = applyFrustumCulling(frustum, afterDistCount, afterDist);
        int afterFrustumCount = afterFrustum.cardinality();

        if (afterFrustumCount == 0) {
            lastCullTimeNanos = System.nanoTime() - startTime;
            return afterFrustum;
        }

        // ===== Stage 3: Hi-Z Occlusion =====
        BitSet finalVisible = applyHiZOcclusion(cameraPos, afterFrustumCount, afterFrustum);

        lastCullTimeNanos = System.nanoTime() - startTime;
        int finalCount = finalVisible.cardinality();
        totalCulled.addAndGet(candidateCount - finalCount);

        LOGGER.fine(String.format(
            "剔除: %d → %d(dist) → %d(frust) → %d(hiz) [%.3fms]",
            candidateCount, afterDistCount, afterFrustumCount, finalCount,
            lastCullTimeNanos / 1_000_000.0));

        return finalVisible;
    }

    /**
     * Stage 1: 距离剔除 — 使用平方距离比较。
     * <p>候选索引按行列顺序排列：chunkX, chunkZ 由 idx/stide - radius 重建。
     */
    private static BitSet applyDistanceCulling(float[] cameraPos, int candidateCount, BitSet input) {
        if (cameraPos == null || VulkanOperationGuard.isFailed()) return input;

        int radius = (int) Math.ceil(Math.sqrt(candidateCount + 1)) / 2;
        int stride = 2 * radius + 1;
        int chunkX0 = (int) Math.floor(cameraPos[0] / 16.0);
        int chunkZ0 = (int) Math.floor(cameraPos[2] / 16.0);
        float maxDistBlocks = 1024.0f * 16.0f;
        float maxDistSq = maxDistBlocks * maxDistBlocks;

        BitSet result = new BitSet(candidateCount);
        for (int i = input.nextSetBit(0); i >= 0 && i < candidateCount; i = input.nextSetBit(i + 1)) {
            int dx = i / stride - radius;
            int dz = i % stride - radius;
            int cx = chunkX0 + dx;
            int cz = chunkZ0 + dz;
            float worldX = cx * 16.0f + 8.0f;
            float worldZ = cz * 16.0f + 8.0f;
            float dxWorld = worldX - cameraPos[0];
            float dzWorld = worldZ - cameraPos[2];
            if (dxWorld * dxWorld + dzWorld * dzWorld <= maxDistSq) {
                result.set(i);
            }
        }
        return result;
    }

    /**
     * Stage 2: 视锥体剔除 — 使用 MCAdapter.frustumTest 进行 AABB 测试。
     */
    private static BitSet applyFrustumCulling(Object frustum, int candidateCount, BitSet input) {
        if (frustum == null || VulkanOperationGuard.isFailed()) return input;

        int radius = (int) Math.ceil(Math.sqrt(candidateCount + 1)) / 2;
        int stride = 2 * radius + 1;
        int chunkX0 = (int) Math.floor(0 / 16.0); // 相机未知时从原点开始
        int chunkZ0 = (int) Math.floor(0 / 16.0);

        BitSet result = new BitSet(candidateCount);
        for (int i = input.nextSetBit(0); i >= 0 && i < candidateCount; i = input.nextSetBit(i + 1)) {
            int dx = i / stride - radius;
            int dz = i % stride - radius;
            int cx = chunkX0 + dx;
            int cz = chunkZ0 + dz;
            float minX = cx * 16.0f;
            float maxX = minX + 16.0f;
            float minZ = cz * 16.0f;
            float maxZ = minZ + 16.0f;
            if (MCAdapter.frustumTest(frustum, minX, -512.0, minZ, maxX, 512.0, maxZ)) {
                result.set(i);
            }
        }
        return result;
    }

    /**
     * Stage 3: Hi-Z 遮挡剔除 — GPU 优先，CPU 降级。
     */
    private static BitSet applyHiZOcclusion(float[] cameraPos, int candidateCount, BitSet input) {
        if (VulkanOperationGuard.isFailed() || !gpuCullingEnabled) return input;

        // ========== Phase 1: 消费 — 回读所有已就绪的槽 ==========
        while (tail != head) {
            long fence = slotFences[tail];
            if (fence != 0L && VulkanSyncManager.checkFence(fence)) {
                BitSet result = LodCullingComputePass.readbackVisibilityBitSet(candidateCount, tail);
                slotResults[tail] = result;
                cachedHiZResult = result;
                VulkanSyncManager.releaseFence(fence);
                slotFences[tail] = 0L;
                tail = (tail + 1) % RING_SIZE;
            } else {
                break; // GPU 尚未完成此槽，保持等待
            }
        }

        // ========== Phase 2: 生产 — 提交当前帧 Hi-Z（仅当有空槽）==========
        int nextHead = (head + 1) % RING_SIZE;
        if (nextHead != tail) { // 环形缓冲区不满
            try {
                long device = VulkanDeviceHolder.getInstance().getDevice();
                long cmdBuf = LodCullingComputePass.getOrCreateCachedCmdBuf(device);
                if (cmdBuf == 0L) return cachedHiZResult != null ? cachedHiZResult : input;

                LodCullingComputePass.beginCachedCommandBuffer(cmdBuf);
                LodCullingComputePass.bindAndDispatchHiZBuild(cmdBuf, VulkanDeviceHolder.getInstance());
                LodCullingComputePass.insertMemoryBarrier(cmdBuf);
                LodCullingComputePass.bindAndDispatchOcclusionQuery(cmdBuf, VulkanDeviceHolder.getInstance());
                LodCullingComputePass.recordVisibilityReadback(cmdBuf, head);
                LodCullingComputePass.endCommandBuffer(cmdBuf);

                long queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
                if (queue != 0L) {
                    long fence = VulkanSyncManager.acquireFence();
                    if (VulkanSyncManager.submitAsync(queue, cmdBuf, fence)) {
                        // 释放该槽旧的 fence（如果有）
                        if (slotFences[head] != 0L) VulkanSyncManager.releaseFence(slotFences[head]);
                        slotFences[head] = fence;
                        head = nextHead;
                    } else {
                        VulkanSyncManager.releaseFence(fence);
                    }
                }
            } catch (Throwable t) {
                LOGGER.fine("GPU HiZ 提交失败: " + t.getMessage());
                gpuCullingEnabled = false;
            }
        } else {
            LOGGER.finest("Ring buffer full, skipping Hi-Z submit");
        }

        // 返回最新可用结果
        return cachedHiZResult != null ? cachedHiZResult : input;
    }

    /**
     * 检查 GPU 剔除是否可用。
     */
    public static boolean isGPUCullingEnabled() {
        return gpuCullingEnabled;
    }

    /**
     * 手动启用/禁用 GPU 剔除路径。
     */
    public static void setGPUCullingEnabled(boolean enabled) {
        gpuCullingEnabled = enabled;
    }

    /**
     * 获取最近一次剔除耗时（毫秒）。
     */
    public static double getLastCullTimeMillis() {
        return lastCullTimeNanos / 1_000_000.0;
    }

    /**
     * 获取总剔除数量。
     */
    public static long getTotalCulled() {
        return totalCulled.get();
    }

    /**
     * 重置状态。
     */
    public static synchronized void reset() {
        initialized = false;
        gpuCullingEnabled = false;
        totalCulled.set(0);
        lastCullTimeNanos = 0L;
    }
}
