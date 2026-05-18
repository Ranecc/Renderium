// Renderium - 区块渲染段管理器
// 区块生命周期统一管理 + PACELC 一致性分层 + 爆炸防涌入
// 参考: Sodium RenderSectionManager (LGPL-3.0) - 独立重写

package com.ranecc.renderium.feature.chunk.manager;

import com.ranecc.renderium.feature.chunk.build.ChunkBuildPipeline;
import com.ranecc.renderium.feature.chunk.build.ChunkBuildTask;
import com.ranecc.renderium.feature.chunk.build.ProgressiveMeshRefiner;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 区块渲染段生命周期管理器。
 *
 * <p>统一管理所有已加载区块的注册/注销/更新/排序/渲染列表。
 *
 * <h2>PACELC 一致性分层</h2>
 * <pre>
 * L1 强一致 [0,   D_L1):  λ*=max(1, (2d/D_vis)^(2/3)·λ₀)
 *                          每帧检查，有变化立即重建
 * L2 最终一致 [D_L1,D_L2): λ*=2.79帧
 *                          延迟批处理，预算内完成
 * L3 弱一致 [D_L2, ∞):    λ*=7.02帧
 *                          低优先级，爆炸时先出粗网格
 * </pre>
 *
 * <p>动态分层边界: D_L1 = D_vis/8, D_L2 = D_vis/2
 * 确保地下场景（D_vis≈16）时合理缩小层级范围。
 *
 * <h2>爆炸防涌入</h2>
 * 单帧变化预算 B=256。超过预算的变化分批处理。
 * 爆炸覆盖>100chunk时：所有受影响chunk先呈现Stage 0粗网格，
 * 随后3-5帧逐步精炼。
 *
 * @see ChunkBuildPipeline
 * @see ProgressiveMeshRefiner
 */
public class RenderSectionManager {

    private static final Logger LOGGER = Logger.getLogger(RenderSectionManager.class.getName());

    // ==================== 分层常量 ====================

    /** 变化预算（每帧最多处理的变化chunk数） */
    private static final int CHANGE_BUDGET_PER_FRAME = 256;

    /** 爆炸检测阈值（单帧受影响chunk数） */
    private static final int EXPLOSION_THRESHOLD = 100;

    /** 爆炸后精炼帧数 */
    private static final int EXPLOSION_REFINE_FRAMES = 5;

    // ==================== 内部数据结构 ====================

    /** 所有已注册的section (chunkKey → SectionInfo) */
    private final ConcurrentHashMap<Long, SectionInfo> sections;

    /** L3弱一致性chunk的脏标记（延迟处理） */
    private final ConcurrentHashMap<Long, Long> l3DirtyChunks; // chunkKey → dirtySinceFrame

    /** 爆炸影响的chunk列表（按距离排序，先进粗网格） */
    private final ArrayDeque<Long> explosionQueue;

    /** 爆炸后剩余精炼帧数 */
    private final AtomicLong explosionRefineFramesLeft;

    /** chunk构建管线 */
    private final ChunkBuildPipeline buildPipeline;

    /** 当前帧号 */
    private final AtomicLong currentFrame;

    // ==================== 相机状态（用于动态分层） ====================

    /** 相机位置 (world coords) */
    private volatile double cameraX, cameraY, cameraZ;

    /** 上一帧偏航/俯仰角（用于角速度估算） */
    private volatile double prevYaw, prevPitch;

    /** 当前可见最远距离 (blocks) */
    private volatile double visibleDistMax;

    /** 动态分层边界（由 CameraMotionDetector 注入） */
    private volatile double boundaryL1 = 16.0;
    private volatile double boundaryL2 = 32.0;

    // ==================== 构造 ====================

    public RenderSectionManager(ChunkBuildPipeline buildPipeline) {
        this.sections = new ConcurrentHashMap<>(2048);
        this.l3DirtyChunks = new ConcurrentHashMap<>(512);
        this.explosionQueue = new ArrayDeque<>(256);
        this.explosionRefineFramesLeft = new AtomicLong(0L);
        this.buildPipeline = buildPipeline;
        this.currentFrame = new AtomicLong(0L);
        this.visibleDistMax = 128.0;
    }

    // ==================== 主帧循环 ====================

    /**
     * 每帧主入口。
     *
     * @param cameraX/Y/Z  相机世界坐标
     * @param yaw          当前偏航角 (°)
     * @param pitch        当前俯仰角 (°)
     * @param deltaTime    帧间隔 (秒)
     */
    public void updateFrame(double cameraX, double cameraY, double cameraZ,
                            double yaw, double pitch, double deltaTime) {
        long frame = currentFrame.incrementAndGet();

        // 0. 更新相机状态
        this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ;
        this.prevYaw = yaw; this.prevPitch = pitch;

        // 1. omega 和分层边界由 CameraMotionDetector 统一计算（不在此重复）
        //    RenderiumCullingScheduler 会在调用本方法前通过 setDetectorData() 注入

        // 2. 收集本帧dirty chunk（按优先级排序）
        List<ChunkBuildTask> tasks = collectDirtyTasks(frame, boundaryL1, boundaryL2);

        // 4. 处理爆炸队列
        processExplosionQueue(frame, tasks);

        // 5. 处理L3延迟脏chunk
        processL3Deferred(frame, boundaryL1, boundaryL2, tasks);

        // 6. 按优先级+预算入队
        int scheduled = buildPipeline.scheduleFrame(tasks);

        // 7. 主线程空闲时偷任务
        buildPipeline.tryStealTask();

        if (frame % 60 == 0) {
            LOGGER.fine(String.format(
                "RenderSectionManager: frame=%d scheduled=%d pending=%d tracked=%d strategy=%s",
                frame, scheduled, buildPipeline.getPendingCount(),
                buildPipeline.getTrackedChunkCount(), buildPipeline.getRefinerStrategy()
            ));
        }
    }

    // ==================== 区块注册/注销 ====================

    /**
     * 区块加载。
     */
    public void onSectionAdded(int chunkX, int chunkY, int chunkZ, int[][][] blocks) {
        long key = toChunkKey(chunkX, chunkY, chunkZ);
        sections.put(key, new SectionInfo(chunkX, chunkY, chunkZ, blocks));
    }

    /**
     * 区块卸载。
     */
    public void onSectionRemoved(int chunkX, int chunkY, int chunkZ) {
        long key = toChunkKey(chunkX, chunkY, chunkZ);
        sections.remove(key);
        l3DirtyChunks.remove(key);
        buildPipeline.getRefiner().removeChunk(key);
    }

    /**
     * 区块内容变化（方块放置/破坏）。
     * 自动判定应放入哪层队列。
     */
    public void onSectionChanged(int chunkX, int chunkY, int chunkZ, int[][][] newBlocks) {
        long key = toChunkKey(chunkX, chunkY, chunkZ);
        SectionInfo info = sections.get(key);
        if (info == null) return;

        info.setDirty(true);
        info.setBlocks(newBlocks);

        // 判定层级：使用动态边界（由 CameraMotionDetector 注入，单一源）
        double dist = distanceToCamera(chunkX, chunkY, chunkZ);

        if (dist < boundaryL1) {
            // L1: 立即标记，本帧入队
            info.setDeadlineFrame(currentFrame.get());
        } else if (dist < boundaryL2) {
            // L2: 标记，下一帧入队
            info.setDeadlineFrame(currentFrame.get() + 1);
        } else {
            // L3: 延迟入队
            l3DirtyChunks.put(key, currentFrame.get());
        }
    }

    // ==================== 爆炸处理 ====================

    /**
     * 通知爆炸影响了一组chunk。
     * 自动触发"粗网格优先"策略。
     *
     * @param affectedChunks 受影响的chunk键列表
     */
    public void notifyExplosion(List<Long> affectedChunks) {
        if (affectedChunks.size() >= EXPLOSION_THRESHOLD) {
            LOGGER.info("爆炸检测: " + affectedChunks.size() + " chunk受影响, 启动粗网格优先");

            // 按距离排序（近处优先）
            affectedChunks.sort(Comparator.comparingDouble(this::chunkDistanceToCamera));
            explosionQueue.clear();
            explosionQueue.addAll(affectedChunks);
            explosionRefineFramesLeft.set(EXPLOSION_REFINE_FRAMES);

            // 将所有受影响chunk标记为dirty
            for (long key : affectedChunks) {
                SectionInfo info = sections.get(key);
                if (info != null) info.setDirty(true);
            }
        } else {
            // 小型变化：正常标记
            for (long key : affectedChunks) {
                SectionInfo info = sections.get(key);
                if (info != null) info.setDirty(true);
            }
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 收集本帧dirty chunk，按优先级排序。
     */
    private List<ChunkBuildTask> collectDirtyTasks(long frame, double dL1, double dL2) {
        List<ChunkBuildTask> tasks = new ArrayList<>();

        for (Map.Entry<Long, SectionInfo> entry : sections.entrySet()) {
            long key = entry.getKey();
            SectionInfo info = entry.getValue();

            if (!info.isDirty()) continue;

            double dist = info.distanceToCamera(cameraX, cameraY, cameraZ);

            // 确定一致性层级
            int layer;
            long lambda;
            if (dist < dL1) {
                layer = 0;
                lambda = 1; // 每帧
            } else if (dist < dL2) {
                layer = 1;
                lambda = 3; // 3帧
            } else {
                layer = 2;
                lambda = 7; // 7帧
            }

            // λ检查：是否到了该重建的时间
            long lastRebuild = info.getLastRebuildFrame();
            if (lastRebuild >= 0 && frame - lastRebuild < lambda) {
                continue; // 未到重建时间
            }

            // 检查deadline
            if (info.getDeadlineFrame() > frame) continue;

            // 确定精炼阶段
            int currentStage = buildPipeline.getRefiner().getCurrentStage(key);
            boolean isHighPriority = layer <= 1;
            int targetStage = buildPipeline.getRefiner().nextStage(key, currentStage, isHighPriority);
            if (targetStage < 0) continue; // 已完成

            ChunkBuildTask task = new ChunkBuildTask(
                key,
                info.chunkX * 16, info.chunkY * 16, info.chunkZ * 16,
                targetStage,
                layer, // priority = consistency layer
                layer,
                frame + lambda,
                estimateDuration(layer, targetStage),
                false,
                info.blocks
            );
            tasks.add(task);

            info.setLastRebuildFrame(frame);
            if (targetStage >= ProgressiveMeshRefiner.STAGE_FINE) {
                info.setDirty(false);
            }
        }

        // 按priority排序
        tasks.sort(Comparator.comparingInt(ChunkBuildTask::priority));
        return tasks;
    }

    /**
     * 处理爆炸队列：优先输出粗网格。
     */
    private void processExplosionQueue(long frame, List<ChunkBuildTask> tasks) {
        long refineLeft = explosionRefineFramesLeft.get();
        if (refineLeft <= 0) return;

        // 每帧处理一部分爆炸chunk
        int batchSize = Math.min(explosionQueue.size(), CHANGE_BUDGET_PER_FRAME / 4);
        for (int i = 0; i < batchSize && !explosionQueue.isEmpty(); i++) {
            long key = explosionQueue.poll();
            SectionInfo info = sections.get(key);
            if (info == null) continue;

            // 爆炸chunk：Stage 0 粗网格优先
            ChunkBuildTask task = new ChunkBuildTask(
                key,
                info.chunkX * 16, info.chunkY * 16, info.chunkZ * 16,
                ProgressiveMeshRefiner.STAGE_COARSE,
                0, 0, frame,
                (long)(ProfileConstants.STAGE0_COST_RATIO * ProfileConstants.L1_FULL_DURATION_NS),
                true, info.blocks
            );
            tasks.add(0, task); // 队首插入
        }

        explosionRefineFramesLeft.decrementAndGet();
    }

    /**
     * 处理L3延迟脏chunk：仅在预算充足时入队。
     */
    private void processL3Deferred(long frame, double dL1, double dL2,
                                   List<ChunkBuildTask> tasks) {
        if (l3DirtyChunks.isEmpty()) return;

        // 每帧至多处理 CHANGE_BUDGET_PER_FRAME/8 个L3 chunk
        int maxL3 = CHANGE_BUDGET_PER_FRAME / 8;
        int processed = 0;

        Iterator<Map.Entry<Long, Long>> it = l3DirtyChunks.entrySet().iterator();
        while (it.hasNext() && processed < maxL3) {
            Map.Entry<Long, Long> entry = it.next();
            long key = entry.getKey();
            long dirtySince = entry.getValue();

            // 至少延迟3帧
            if (frame - dirtySince < 3) continue;

            SectionInfo info = sections.get(key);
            if (info == null) {
                it.remove();
                continue;
            }

            ChunkBuildTask task = new ChunkBuildTask(
                key,
                info.chunkX * 16, info.chunkY * 16, info.chunkZ * 16,
                ProgressiveMeshRefiner.STAGE_COARSE,
                2, 2, frame + 7,
                ProfileConstants.L3_COARSE_DURATION_NS,
                false, info.blocks
            );
            tasks.add(task);
            info.setLastRebuildFrame(frame);
            processed++;
            it.remove();
        }
    }

    /**
     * 更新可见最远距离（用于动态分层边界计算）。
     */
    public void setVisibleDistMax(double dist) {
        this.visibleDistMax = Math.max(16.0, dist);
    }

    /**
     * 注入来自 CameraMotionDetector 的数据（omega、边界）。
     * 由 RenderiumCullingScheduler 在 updateFrame 之前调用。
     */
    public void setDetectorData(double omega, double l1, double l2) {
        this.boundaryL1 = l1;
        this.boundaryL2 = l2;
        buildPipeline.beginFrame(omega);
    }

    /**
     * 获取所有已注册 section 的映射（供调度中心遍历）。
     */
    public ConcurrentHashMap<Long, RenderSectionManager.SectionInfo> getSections() {
        return sections;
    }

    // ==================== 辅助方法 ====================

    private static long toChunkKey(int cx, int cy, int cz) {
        return ((long) cx << 42) | ((long) (cz & 0x3FFFFF) << 20) | (cy & 0xFFFFF);
    }

    private double distanceToCamera(int cx, int cy, int cz) {
        double dx = cx * 16 + 8 - cameraX;
        double dy = cy * 16 + 8 - cameraY;
        double dz = cz * 16 + 8 - cameraZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double chunkDistanceToCamera(long chunkKey) {
        SectionInfo info = sections.get(chunkKey);
        if (info == null) return Double.MAX_VALUE;
        return info.distanceToCamera(cameraX, cameraY, cameraZ);
    }

    private static long estimateDuration(int layer, int stage) {
        double baseCost = switch (layer) {
            case 0 -> ProfileConstants.L1_FULL_DURATION_NS;
            case 1 -> ProfileConstants.L2_MEDIUM_DURATION_NS;
            default -> ProfileConstants.L3_COARSE_DURATION_NS;
        };
        double stageCost = switch (stage) {
            case 0 -> ProfileConstants.STAGE0_COST_RATIO;
            case 1 -> ProfileConstants.STAGE1_COST_RATIO;
            default -> ProfileConstants.STAGE2_COST_RATIO;
        };
        return (long) (baseCost * stageCost);
    }

    // ==================== 内部数据类 ====================

    /**
     * 单个section的元信息（供调度中心遍历）。
     */
    public static class SectionInfo {
        public final int chunkX, chunkY, chunkZ;

        /** 方块数据 */
        volatile int[][][] blocks;

        /** 是否需要重建 */
        volatile boolean dirty;

        /** 上次重建帧号 */
        volatile long lastRebuildFrame = -1;

        /** 最晚重建帧号 */
        volatile long deadlineFrame = Long.MAX_VALUE;

        SectionInfo(int x, int y, int z, int[][][] b) {
            this.chunkX = x; this.chunkY = y; this.chunkZ = z;
            this.blocks = b;
            this.dirty = true; // 新加载的chunk需要首次构建
        }

        void setDirty(boolean d) { this.dirty = d; }
        boolean isDirty() { return dirty; }
        void setBlocks(int[][][] b) { this.blocks = b; }
        void setLastRebuildFrame(long f) { this.lastRebuildFrame = f; }
        long getLastRebuildFrame() { return lastRebuildFrame; }
        void setDeadlineFrame(long f) { this.deadlineFrame = f; }
        long getDeadlineFrame() { return deadlineFrame; }

        /** 世界坐标中心 */
        public double worldCenterX() { return chunkX * 16.0 + 8.0; }
        public double worldCenterY() { return chunkY * 16.0 + 8.0; }
        public double worldCenterZ() { return chunkZ * 16.0 + 8.0; }
        /** 世界坐标AABB */
        public float worldMinX() { return chunkX * 16.0f; }
        public float worldMinY() { return chunkY * 16.0f; }
        public float worldMinZ() { return chunkZ * 16.0f; }
        public float worldMaxX() { return (chunkX + 1) * 16.0f; }
        public float worldMaxY() { return (chunkY + 1) * 16.0f; }
        public float worldMaxZ() { return (chunkZ + 1) * 16.0f; }

        double distanceToCamera(double cx, double cy, double cz) {
            double dx = chunkX * 16 + 8 - cx;
            double dy = chunkY * 16 + 8 - cy;
            double dz = chunkZ * 16 + 8 - cz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    // ==================== 内部常量 ====================

    /** 各场景的构建耗时基准（纳秒） */
    private static final class ProfileConstants {
        static final long L1_FULL_DURATION_NS = 800_000L;
        static final long L2_MEDIUM_DURATION_NS = 400_000L;
        static final long L3_COARSE_DURATION_NS = 100_000L;
        static final double STAGE0_COST_RATIO = 0.05;
        static final double STAGE1_COST_RATIO = 0.20;
        static final double STAGE2_COST_RATIO = 1.00;
    }

    // ==================== 公共查询 ====================

    public int getSectionCount() { return sections.size(); }
    public ChunkBuildPipeline getBuildPipeline() { return buildPipeline; }
    public long getCurrentFrame() { return currentFrame.get(); }
}
