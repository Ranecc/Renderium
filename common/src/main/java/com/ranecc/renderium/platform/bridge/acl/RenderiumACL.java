// Renderium - 统一防腐层 (Anti-Corruption Layer)
// Mixin 层与优化子系统之间的唯一桥梁。
//
// 设计约束:
// 1. 不含任何 Minecraft 类引用（不 import net.minecraft.*）
// 2. 所有参数为基本类型或简单 DTO
// 3. Mixin 层只需调用 RenderiumACL，无需了解底层子系统
//
// MC 版本更新时，只需更新 Mixin 类和本类的适配逻辑，
// feature/chunk/ 下的优化子系统完全不动。

package com.ranecc.renderium.platform.bridge.acl;

import com.ranecc.renderium.feature.chunk.build.ChunkBuildPipeline;
import com.ranecc.renderium.feature.chunk.build.ChunkBuildTask;
import com.ranecc.renderium.feature.chunk.manager.RenderSectionManager;
import com.ranecc.renderium.feature.chunk.manager.RenderiumCullingScheduler;
import com.ranecc.renderium.feature.chunk.manager.RenderiumZoomAPI;
import com.ranecc.renderium.feature.chunk.sort.TranslucentSortEngine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * 统一防腐层 (Anti-Corruption Layer)。
 *
 * <p>这是 Mixin 层与 feature/chunk/ 优化子系统之间的唯一桥梁。
 *
 * <h2>数据流</h2>
 * <pre>
 * MC LevelRenderer.renderLevel()
 *   → MixinLevelRendererACL.onRenderFrame()        (thin glue, <=10行)
 *     → RenderiumACL.onRenderFrame(x, y, z, ...)   (防腐层)
 *       → RenderiumCullingScheduler.onFrame(...)   (调度中心)
 *         → CameraMotionDetector.updateFrame()     (唯一信号源)
 *         → RenderSectionManager.updateFrame()     (区块生命周期)
 *         → GPUCullingSystem.dispatchFrustumCull() (GPU剔除)
 *         → ChunkBuildPipeline.scheduleFrame()     (多线程构建)
 *         → TranslucentSortEngine.sort()           (透明排序)
 * </pre>
 *
 * <h2>MC版本变更保护</h2>
 * 当 MC 版本更新导致 Mixin 目标类变化时：
 * <ol>
 *   <li>更新 Mixin 注入点和方法签名</li>
 *   <li>如果 RenderiumACL 的接口需要调整，在此类做适配</li>
 *   <li>feature/chunk/ 下所有类完全不需要改动</li>
 * </ol>
 */
public final class RenderiumACL {

    private static final Logger LOGGER = Logger.getLogger(RenderiumACL.class.getName());

    /** 单例 */
    private static volatile RenderiumACL instance;

    // ==================== 子系统引用 ====================

    /** 统一调度中心（拥有所有子系统的引用） */
    private RenderiumCullingScheduler scheduler;

    /** 区块渲染段管理器 */
    private RenderSectionManager sectionManager;

    /** 区块构建管线 */
    private ChunkBuildPipeline buildPipeline;

    /** 透明面排序引擎 */
    private TranslucentSortEngine sortEngine;

    /** 是否已初始化 */
    private volatile boolean initialized;

    // ==================== 构造 ====================

    private RenderiumACL() {}

    /**
     * 初始化防腐层（在 Minecraft 加载完成后调用，由 Mod 入口触发）。
     *
     * @param cpuCores 可用 CPU 核心数 (Runtime.getRuntime().availableProcessors())
     */
    public synchronized void init(int cpuCores) {
        if (initialized) return;

        this.buildPipeline = new ChunkBuildPipeline(cpuCores);
        this.sectionManager = new RenderSectionManager(buildPipeline);
        this.scheduler = new RenderiumCullingScheduler(sectionManager, buildPipeline);
        this.sortEngine = new TranslucentSortEngine();

        // 启动多线程 Worker 池
        buildPipeline.start();

        // 注册 Zoom API
        RenderiumZoomAPI.init(scheduler.getDetector());

        this.initialized = true;
        LOGGER.info("RenderiumACL 初始化完成: " + (cpuCores) + " worker(s)");
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

    /**
     * 每帧渲染入口 —— 这是 Mixin 唯一需要调用的帧方法。
     *
     * @param camX/camY/camZ   相机世界坐标 (blocks)
     * @param yaw/pitch        相机偏航/俯仰角 (°)
     * @param deltaTime        帧间隔 (秒)
     * @param fovDegrees       当前视场角 (°)，用于 Zoom 被动检测
     * @param renderDistBlocks 渲染距离 (blocks)
     */
    public void onRenderFrame(double camX, double camY, double camZ,
                               double yaw, double pitch,
                               double deltaTime, double fovDegrees,
                               double renderDistBlocks) {
        if (!initialized) return;
        scheduler.onFrame(camX, camY, camZ, yaw, pitch,
            deltaTime, renderDistBlocks, null);
    }

    // ==================== ACL 方法：区块事件 ====================

    /**
     * 区块被加载/添加。
     *
     * @param chunkX/chunkY/chunkZ 区块坐标 (chunk 空间，非 block 空间)
     * @param blocks    方块数据 [16][16][16] int 数组，每个元素为 block ID
     */
    public void onChunkAdded(int chunkX, int chunkY, int chunkZ,
                              int[][][] blocks) {
        if (!initialized) return;
        sectionManager.onSectionAdded(chunkX, chunkY, chunkZ, blocks);
    }

    /**
     * 区块被卸载/移除。
     */
    public void onChunkRemoved(int chunkX, int chunkY, int chunkZ) {
        if (!initialized) return;
        sectionManager.onSectionRemoved(chunkX, chunkY, chunkZ);
    }

    /**
     * 区块内容发生变化（方块放置/破坏/更新）。
     *
     * @param blocks 新的方块数据 [16][16][16]
     */
    public void onChunkChanged(int chunkX, int chunkY, int chunkZ,
                                int[][][] blocks) {
        if (!initialized) return;
        sectionManager.onSectionChanged(chunkX, chunkY, chunkZ, blocks);
    }

    // ==================== ACL 方法：爆炸 ====================

    /**
     * 爆炸/大量方块变化 —— 触发粗网格优先策略。
     *
     * @param affectedChunkKeys 受影响区块的 key 列表
     *                          (key = ((chunkX << 42) | ((chunkZ & 0x3FFFFF) << 20) | (chunkY & 0xFFFFF)))
     */
    public void onExplosion(List<Long> affectedChunkKeys) {
        if (!initialized) return;
        sectionManager.notifyExplosion(affectedChunkKeys);
    }

    // ==================== ACL 方法：透明排序 ====================

    /**
     * 对透明四边形进行排序。
     *
     * @param quads     透明四边形数组
     * @param cameraX/Y/Z 相机位置 (world)
     * @return 排序后的 quad 索引数组
     */
    public int[] onTransparentSort(Object[] quads,
                                    double cameraX, double cameraY,
                                    double cameraZ) {
        if (!initialized || quads == null || quads.length == 0) {
            return new int[0];
        }
        // 转换到 TranslucentSortEngine 的内部类型
        TranslucentSortEngine.TranslucentQuad[] internalQuads =
            new TranslucentSortEngine.TranslucentQuad[quads.length];
        for (int i = 0; i < quads.length; i++) {
            // 实际类型转换由 ACL 适配层负责
            // 此处为占位——真实实现需要从 MC 的透明四边形提取数据
            internalQuads[i] = (TranslucentSortEngine.TranslucentQuad) quads[i];
        }
        return sortEngine.sort(internalQuads, cameraX, cameraY, cameraZ,
            0, 0, false);
    }

    // ==================== 查询 API（Mixin 调用者使用） ====================

    /** @return 调度中心是否已初始化就绪 */
    public boolean isReady() { return initialized && scheduler != null; }

    /** @return 当前可见 chunk 数量 */
    public int getVisibleChunkCount() {
        return scheduler != null ? scheduler.getVisibleChunks().size() : 0;
    }

    /** @return 调度中心实例（用于更高级的 Debug/Profiling 查询） */
    public RenderiumCullingScheduler getScheduler() { return scheduler; }

    /** @return Culling 专用 ACL */
    public CullingACL getCullingACL() {
        return CullingACL.getInstance(scheduler);
    }

    // ==================== 生命周期 ====================

    /** 关闭防腐层，释放所有子系统资源 */
    public void shutdown() {
        if (!initialized) return;
        List<ChunkBuildTask> remaining = buildPipeline.shutdown();
        LOGGER.info("RenderiumACL 关闭: " + remaining.size() + " 个残留任务已丢弃");
        initialized = false;
    }
}
