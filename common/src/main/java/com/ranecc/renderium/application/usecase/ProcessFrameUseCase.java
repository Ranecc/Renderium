package com.ranecc.renderium.application.usecase;
import com.ranecc.renderium.domain.model.FrameData;

import com.ranecc.renderium.domain.model.FrameDataSnapshot;
import com.ranecc.renderium.domain.service.scheduling.AdaptivePathSelector;
import com.ranecc.renderium.platform.hook.HookManager;

import java.util.Arrays;
import java.util.logging.Logger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * 帧处理用例（Application Layer - Use Case）
 *
 * <p>负责单帧渲染处理的完整流程编排，
 * 协调数据收集、算法执行和结果分发三个阶段。
 *
 * <h3>帧处理流程</h3>
 * <ol>
 *   <li><b>收集帧数据</b>：从 MCRenderBridge 获取当前帧的相机、矩阵、可见性等数据</li>
 *   <li><b>运行算法</b>：通过 AdaptivePathSelector 选择最优路径执行 BFS/LOD/Kahan 算法</li>
 *   <li><b>分发结果</b>：将算法输出分发到 HookManager 的各个钩子</li>
 * </ol>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li><b>性能优先</b>：热路径方法尽量减少对象分配和同步开销</li>
 *   <li><b>容错性</b>：单帧失败不影响后续帧处理</li>
 *   <li><b>可观测性</b>：详细记录每阶段耗时用于性能分析</li>
 * </ul>
 *
 * @see FrameDataSnapshot
 * @see AdaptivePathSelector
 * @see HookManager
 * @since 1.1.0
 */
public class ProcessFrameUseCase {

    private static final Logger LOGGER = Logger.getLogger(ProcessFrameUseCase.class.getName());

    /** 帧预算上限（纳秒）：590μs，参考 performance-tuning 文档 7.1 节 */
    private static final long FRAME_BUDGET_NS = 590_000L;

    /** 连续空帧计数器：跟踪连续出现 0 可见区块的帧数 */
    private int consecutiveEmptyFrames;

    /** 上一帧的可见区块数：用于检测可见性是否发生变化 */
    private int lastVisibleSectionCount = -1;

    /** 上一帧数据哈希：用于检测帧数据是否完全相同（避免重复计算） */
    private int lastFrameDataHash;

    /** 帧数据快照（复用对象以减少 GC 压力） */
    private FrameDataSnapshot frameDataSnapshot;

    /** 自适应路径选择器（由 InitializeUseCase 注入） */
    private AdaptivePathSelector scheduler;

    /**
     * 默认构造函数
     */
    public ProcessFrameUseCase() {
        this.frameDataSnapshot = new FrameDataSnapshot();
    }

    /**
     * 设置调度器（由外部注入）
     *
     * @param scheduler AdaptivePathSelector 实例
     */
    public void setScheduler(AdaptivePathSelector scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 执行单帧处理流程
     *
     * <p>这是渲染循环的核心方法，每帧调用一次。
     * 整个流程分为三个阶段，每个阶段都有超时保护和异常隔离。
     *
     * <h4>方法签名</h4>
     * <ul>
     *   <li><b>参数：</b>deltaTime - 帧间隔时间（秒）（float 类型）</li>
     *   <li><b>返回值：</b>void</li>
     *   <li><b>异常：</b>无（内部捕获所有异常并记录日志）</li>
     * </ul>
     *
     * <h4>性能特征</h4>
     * <ul>
     *   <li>目标耗时：< 5ms（不含算法执行时间）</li>
     *   <li>内存分配：接近零（复用 FrameDataSnapshot 对象）</li>
     *   <li>线程安全：应在单线程（渲染线程）中调用</li>
     * </ul>
     *
     * @param deltaTime 帧间隔时间（单位：秒，例如 0.0167 表示 60 FPS）
     */
    public void execute(float deltaTime) {
        long frameStartTime = System.nanoTime();

        try {
            // Phase 1: 收集帧数据
            if (!collectFrameData(deltaTime)) {
                LOGGER.fine("Frame data collection skipped or failed");
                return;
            }

            // 预算保护：空场景早退——无区块或仅天空盒时跳过剔除/渲染算法
            if (shouldSkipFrame(frameDataSnapshot)) {
                return;
            }

            long collectTime = System.nanoTime() - frameStartTime;
            if (collectTime > 1_000_000L) { // > 1ms
                LOGGER.warning("Frame data collection took " + (collectTime / 1_000_000.0) + "ms");
            }

            // 预算执行：Phase 1 消耗超过帧预算的 50% 时，跳过重量级算法走轻量路径
            if (collectTime > FRAME_BUDGET_NS * 0.5) {
                LOGGER.fine("Phase 1 overran budget (" + (collectTime / 1000) + "μs), using lightweight path");
                dispatchToPlatform(frameDataSnapshot);
                return;
            }

            // Phase 2: 运行算法
            long algoStart = System.nanoTime();
            boolean algorithmSuccess = runAlgorithms(frameDataSnapshot);
            long algoTime = System.nanoTime() - algoStart;

            if (!algorithmSuccess) {
                LOGGER.fine("Algorithm execution failed or skipped for frame "
                           + frameDataSnapshot.getFrameIndex());
            }

            if (algoTime > 10_000_000L) { // > 10ms
                LOGGER.warning("Algorithm execution took " + (algoTime / 1_000_000.0) + "ms");
            }

            // 预算执行：Phase 2 结束后总耗时超过帧预算时，跳过 Phase 3 分发
            long totalElapsed = System.nanoTime() - frameStartTime;
            if (totalElapsed > FRAME_BUDGET_NS) {
                LOGGER.fine("Frame budget exceeded after Phase 2 (" + (totalElapsed / 1000) + "μs), skipping dispatch");
                return;
            }

            // Phase 3: 分发结果到平台
            long dispatchStart = System.nanoTime();
            dispatchToPlatform(frameDataSnapshot);
            long dispatchTime = System.nanoTime() - dispatchStart;

            if (dispatchTime > 1_000_000L) { // > 1ms
                LOGGER.warning("Result dispatch took " + (dispatchTime / 1_000_000.0) + "ms");
            }

            // 性能统计
            long totalFrameTime = System.nanoTime() - frameStartTime;
            if (totalFrameTime > 16_666_666L) { // > 16.6ms (60 FPS 预算)
                LOGGER.warning("Total frame processing exceeded budget: "
                             + (totalFrameTime / 1_000_000.0) + "ms");
            }

        } catch (Exception e) {
            LOGGER.severe("Unhandled exception in processFrame: " + e.getMessage());
            // 不抛出异常，确保不会崩溃渲染循环
        }
    }

    /**
     * 获取当前帧数据快照（用于调试和监控）
     *
     * @return FrameDataSnapshot 当前帧数据（可能为 null）
     */
    public FrameDataSnapshot getCurrentFrameData() {
        return frameDataSnapshot;
    }

    // ==================== 私有处理阶段 ====================

    /**
     * 判断当前帧是否应跳过算法执行
     *
     * <p>综合多种条件进行早退判断，避免对空场景或无变化帧执行不必要的计算：
     * <ol>
     *   <li>世界未加载（totalSectionCount == 0）</li>
     *   <li>无可见区块（visibleSectionCount == 0）</li>
     *   <li>连续空帧 >= 3（持续空场景，更激进地跳过）</li>
     *   <li>帧数据与上一帧完全相同（相机和区块均未变化）</li>
     * </ol>
     *
     * @param snapshot 当前帧数据快照
     * @return true 如果应跳过此帧的算法执行
     */
    private boolean shouldSkipFrame(FrameDataSnapshot snapshot) {
        if (snapshot == null) {
            return true;
        }

        int visibleCount = snapshot.getVisibleSectionCount();
        int totalCount = snapshot.getTotalSectionCount();

        // 世界未加载或无可视区块 → 统一走连续空帧计数
        if (totalCount == 0 || visibleCount <= 0) {
            consecutiveEmptyFrames++;
            if (consecutiveEmptyFrames >= 3) {
                LOGGER.finest("Sustained empty scene (" + consecutiveEmptyFrames
                    + " consecutive frames), aggressive skip");
            }
            return true;
        }

        // 非空帧，重置连续空帧计数
        consecutiveEmptyFrames = 0;

        // 帧数据未变化——通过哈希比较相机位置、旋转和区块可见性
        int currentHash = computeFrameDataHash(snapshot);
        if (currentHash == lastFrameDataHash && visibleCount == lastVisibleSectionCount) {
            LOGGER.finest("Frame data unchanged from previous frame, skipping render algorithms");
            return true;
        }

        lastFrameDataHash = currentHash;
        lastVisibleSectionCount = visibleCount;
        return false;
    }

    /**
     * 计算帧数据哈希值，用于检测帧间数据是否相同
     *
     * <p>基于以下数据计算哈希：
     * <ul>
     *   <li>相机位置（X/Y/Z）和旋转（Yaw/Pitch）</li>
     *   <li>视图矩阵（16 个 float）</li>
     *   <li>可见区块数和总区块数</li>
     *   <li>视口区域是否变化标志</li>
     * </ul>
     *
     * @param snapshot 帧数据快照
     * @return 哈希值
     */
    private int computeFrameDataHash(FrameDataSnapshot snapshot) {
        int hash = 1;
        hash = 31 * hash + Float.floatToIntBits(snapshot.getCameraX());
        hash = 31 * hash + Float.floatToIntBits(snapshot.getCameraY());
        hash = 31 * hash + Float.floatToIntBits(snapshot.getCameraZ());
        hash = 31 * hash + Float.floatToIntBits(snapshot.getYaw());
        hash = 31 * hash + Float.floatToIntBits(snapshot.getPitch());
        hash = 31 * hash + Arrays.hashCode(snapshot.getViewMatrix());
        hash = 31 * hash + snapshot.getVisibleSectionCount();
        hash = 31 * hash + snapshot.getTotalSectionCount();
        hash = 31 * hash + (snapshot.isViewAreaChanged() ? 1 : 0);
        return hash;
    }

    /**
     * Phase 1: 收集帧数据
     *
     * <p>从 platform.bridge.MCRenderBridge 获取当前帧的所有必要数据：
     * <ul>
     *   <li>相机位置和旋转</li>
     *   <li>投影矩阵和视图矩阵</li>
     *   <li>FOV 和裁剪面参数</li>
     *   <li>窗口尺寸</li>
     *   <li>区块可见性信息</li>
     *   <li>雾效参数</li>
     * </ul>
     *
     * @param deltaTime 帧间隔时间
     * @return true 如果数据收集成功，false 如果应该跳过此帧
     */
    private boolean collectFrameData(float deltaTime) {
        try {
            // 重置快照对象（复用而非新建）
            frameDataSnapshot.reset();

            // 从 MCRenderBridge 获取帧数据
            // 注意：这里使用接口或 Object 类型，避免直接依赖 Minecraft 类
            Object bridgeInstance = getMCRenderBridgeInstance();
            if (bridgeInstance == null) {
                LOGGER.fine("MCRenderBridge not available, skipping frame data collection");
                return false;
            }

            // 通过反射或接口获取数据（实际实现取决于平台层）
            collectCameraData(bridgeInstance);
            collectMatrixData(bridgeInstance);
            collectVisibilityData(bridgeInstance);
            collectFogData(bridgeInstance);
            collectWindowData(bridgeInstance);

            return true;

        } catch (Exception e) {
            LOGGER.warning("Failed to collect frame data: " + e.getMessage());
            return false;
        }
    }

    /**
     * Phase 2: 运行算法
     *
     * <p>通过 AdaptivePathSelector 选择最优执行路径：
     * <ul>
     *   <li>BFS 遮挡剔除算法</li>
     *   <li>LOD 距离计算算法</li>
     *   <li>Kahan 高精度累加算法</li>
     * </ul>
     *
     * <p>调度器会根据当前 GPU 负载、算法可用性和配置偏好
     * 自动选择 Java 或 Native 路径。
     *
     * @param frameData 帧数据快照
     * @return true 如果算法执行成功
     */
    private boolean runAlgorithms(FrameDataSnapshot frameData) {
        if (scheduler == null) {
            LOGGER.warning("Scheduler not initialized, skipping algorithm execution");
            return false;
        }

        try {
            // 通过调度器执行算法（具体策略由调度器内部决定）
            scheduler.executeAlgorithms(frameData);
            return true;

        } catch (Exception e) {
            LOGGER.warning("Algorithm execution failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Phase 3: 分发结果到平台
     *
     * <p>将算法输出分发到 HookManager 的各个钩子：
     * <ul>
     *   <li>GpuDeviceBufferHook：GPU 缓冲区管理</li>
     *   <li>DrawIndexedHook：批量绘制优化</li>
     *   <li>BindTextureHook：纹理绑定优化</li>
     *   <li>其他已注册的钩子</li>
     * </ul>
     *
     * @param frameData 包含算法结果的帧数据
     */
    private void dispatchToPlatform(FrameDataSnapshot frameData) {
        try {
            // 将算法结果分发到各个平台钩子
            // 这里简化为通知 HookManager 更新状态
            // 实际实现会在各 hook 的回调中被触发

            if (HookManager.getGpuDeviceBufferHook() != null) {
                HookManager.getGpuDeviceBufferHook().onFrameDataUpdated(frameData);
            }

            if (HookManager.getDrawIndexedHook() != null) {
                HookManager.getDrawIndexedHook().onVisibilityUpdated(
                    frameData.getVisibleSectionCount(),
                    frameData.getTotalSectionCount()
                );
            }

        } catch (Exception e) {
            LOGGER.warning("Failed to dispatch results to platform: " + e.getMessage());
        }
    }

    // ==================== 数据收集辅助方法 ====================

    // ==================== MethodHandle 缓存（替代每帧反射 getMethod） ====================

    private static volatile MethodHandle MH_getCurrentFrameData;
    private static volatile MethodHandle MH_getCameraX, MH_getCameraY, MH_getCameraZ;
    private static volatile MethodHandle MH_getYaw, MH_getPitch;
    private static volatile MethodHandle MH_getFov, MH_getNearPlane, MH_getFarPlane;
    private static volatile MethodHandle MH_getProjectionMatrix, MH_getViewMatrix, MH_getInvViewMatrix;
    private static volatile MethodHandle MH_getVisibleSectionCount, MH_getTotalSectionCount;
    private static volatile MethodHandle MH_getOpaqueDrawCallCount, MH_getTranslucentDrawCallCount;
    private static volatile MethodHandle MH_isViewAreaChanged;
    private static volatile MethodHandle MH_getFogColor, MH_getFogStart, MH_getFogEnd, MH_getFogDensity, MH_getFogType, MH_isFogEnabled;
    private static volatile MethodHandle MH_getWindowWidth, MH_getWindowHeight, MH_getGameTick;
    private static volatile boolean mhInitialized = false;

    /** 初始化一次 MethodHandle 缓存 */
    private static void ensureSnapshotMH() {
        if (mhInitialized) return;
        try {
            Class<?> bridgeClass = Class.forName("com.ranecc.renderium.platform.bridge.mc.MCRenderBridge");
            MH_getCurrentFrameData = MethodHandles.publicLookup().unreflect(
                bridgeClass.getMethod("getCurrentFrameData"));

            Class<?> snapClass = Class.forName("com.ranecc.renderium.platform.bridge.mc.FrameDataSnapshot");
            MH_getCameraX = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getCameraX"));
            MH_getCameraY = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getCameraY"));
            MH_getCameraZ = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getCameraZ"));
            MH_getYaw = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getYaw"));
            MH_getPitch = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getPitch"));
            MH_getFov = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getFov"));
            MH_getNearPlane = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getNearPlane"));
            MH_getFarPlane = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getFarPlane"));
            MH_getProjectionMatrix = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getProjectionMatrix"));
            MH_getViewMatrix = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getViewMatrix"));
            MH_getInvViewMatrix = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getInvViewMatrix"));
            MH_getVisibleSectionCount = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getVisibleSectionCount"));
            MH_getTotalSectionCount = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getTotalSectionCount"));
            MH_getOpaqueDrawCallCount = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getOpaqueDrawCallCount"));
            MH_getTranslucentDrawCallCount = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getTranslucentDrawCallCount"));
            MH_isViewAreaChanged = MethodHandles.publicLookup().unreflect(snapClass.getMethod("isViewAreaChanged"));
            MH_getFogColor = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getFogColor"));
            MH_getFogStart = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getFogStart"));
            MH_getFogEnd = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getFogEnd"));
            MH_getFogDensity = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getFogDensity"));
            MH_getFogType = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getFogType"));
            MH_isFogEnabled = MethodHandles.publicLookup().unreflect(snapClass.getMethod("isFogEnabled"));
            MH_getWindowWidth = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getWindowWidth"));
            MH_getWindowHeight = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getWindowHeight"));
            MH_getGameTick = MethodHandles.publicLookup().unreflect(snapClass.getMethod("getGameTick"));
            mhInitialized = true;
        } catch (Exception e) {
            LOGGER.warning("MethodHandle 缓存初始化失败（将回退到反射）: " + e.getMessage());
        }
    }

    /** 安全的 MethodHandle invoke，返回 null 时降级 */
    private static Object invokeMH(MethodHandle mh, Object target) {
        if (mh == null) return null;
        try { return mh.invoke(target); }
        catch (Throwable t) { return null; }
    }

    private Object getMCRenderBridgeInstance() {
        try {
            Class<?> bridgeClass = Class.forName("com.ranecc.renderium.platform.bridge.mc.MCRenderBridge");
            java.lang.reflect.Method getInstanceMethod = bridgeClass.getMethod("getInstance");
            return getInstanceMethod.invoke(null);
        } catch (ClassNotFoundException e) {
            LOGGER.finest("MCRenderBridge not found in classpath");
            return null;
        } catch (Exception e) {
            LOGGER.warning("Failed to get MCRenderBridge instance: " + e.getMessage());
            return null;
        }
    }

    /**
     * 收集相机数据 — 使用 MethodHandle 缓存替代每帧 getMethod().
     */
    private void collectCameraData(Object bridge) {
        try {
            ensureSnapshotMH();
            Object snapshot = invokeMH(MH_getCurrentFrameData, bridge);
            if (snapshot == null) return;
            frameDataSnapshot.setCameraPosition(
                (float) (float) invokeMH(MH_getCameraX, snapshot),
                (float) (float) invokeMH(MH_getCameraY, snapshot),
                (float) (float) invokeMH(MH_getCameraZ, snapshot)
            );
            frameDataSnapshot.setCameraRotation(
                (float) (float) invokeMH(MH_getYaw, snapshot),
                (float) (float) invokeMH(MH_getPitch, snapshot)
            );
            frameDataSnapshot.setFov((float) (float) invokeMH(MH_getFov, snapshot));
            frameDataSnapshot.setNearPlane((float) (float) invokeMH(MH_getNearPlane, snapshot));
            frameDataSnapshot.setFarPlane((float) (float) invokeMH(MH_getFarPlane, snapshot));
        } catch (Exception e) {
            LOGGER.warning("collectCameraData failed: " + e.getMessage());
        }
    }

    private void collectMatrixData(Object bridge) {
        try {
            ensureSnapshotMH();
            Object snapshot = invokeMH(MH_getCurrentFrameData, bridge);
            if (snapshot == null) return;
            frameDataSnapshot.setProjectionMatrix(
                (float[]) invokeMH(MH_getProjectionMatrix, snapshot));
            frameDataSnapshot.setViewMatrix(
                (float[]) invokeMH(MH_getViewMatrix, snapshot));
            frameDataSnapshot.setInvViewMatrix(
                (float[]) invokeMH(MH_getInvViewMatrix, snapshot));
            frameDataSnapshot.recomputeVPMatrix();
        } catch (Exception e) {
            LOGGER.warning("collectMatrixData failed: " + e.getMessage());
        }
    }

    private void collectVisibilityData(Object bridge) {
        try {
            ensureSnapshotMH();
            Object snapshot = invokeMH(MH_getCurrentFrameData, bridge);
            if (snapshot == null) return;
            frameDataSnapshot.setChunkVisibility(
                (int) (int) invokeMH(MH_getVisibleSectionCount, snapshot),
                (int) (int) invokeMH(MH_getTotalSectionCount, snapshot),
                (int) (int) invokeMH(MH_getOpaqueDrawCallCount, snapshot),
                (int) (int) invokeMH(MH_getTranslucentDrawCallCount, snapshot),
                (boolean) (boolean) invokeMH(MH_isViewAreaChanged, snapshot)
            );
        } catch (Exception e) {
            LOGGER.warning("collectVisibilityData failed: " + e.getMessage());
        }
    }

    private void collectFogData(Object bridge) {
        try {
            ensureSnapshotMH();
            Object snapshot = invokeMH(MH_getCurrentFrameData, bridge);
            if (snapshot == null) return;
            float[] fogColor = (float[]) invokeMH(MH_getFogColor, snapshot);
            frameDataSnapshot.setFogData(
                fogColor[0], fogColor[1], fogColor[2], fogColor.length > 3 ? fogColor[3] : 1.0f,
                (float) (float) invokeMH(MH_getFogStart, snapshot),
                (float) (float) invokeMH(MH_getFogEnd, snapshot),
                (float) (float) invokeMH(MH_getFogDensity, snapshot),
                (int) (int) invokeMH(MH_getFogType, snapshot),
                (boolean) (boolean) invokeMH(MH_isFogEnabled, snapshot)
            );
        } catch (Exception e) {
            LOGGER.warning("collectFogData failed: " + e.getMessage());
        }
    }

    private void collectWindowData(Object bridge) {
        try {
            ensureSnapshotMH();
            Object snapshot = invokeMH(MH_getCurrentFrameData, bridge);
            if (snapshot == null) return;
            frameDataSnapshot.setWindowSize(
                (int) (int) invokeMH(MH_getWindowWidth, snapshot),
                (int) (int) invokeMH(MH_getWindowHeight, snapshot)
            );
            frameDataSnapshot.setGameTick(
                (long) (long) invokeMH(MH_getGameTick, snapshot));
        } catch (Exception e) {
            LOGGER.warning("collectWindowData failed: " + e.getMessage());
        }
    }
}
