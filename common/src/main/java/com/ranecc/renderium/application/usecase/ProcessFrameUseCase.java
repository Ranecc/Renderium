package com.ranecc.renderium.application.usecase;
import com.ranecc.renderium.domain.model.FrameData;

import com.ranecc.renderium.domain.model.FrameDataSnapshot;
import com.ranecc.renderium.domain.service.scheduling.AdaptivePathSelector;
import com.ranecc.renderium.platform.hook.HookManager;

import java.util.logging.Logger;

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

            long collectTime = System.nanoTime() - frameStartTime;
            if (collectTime > 1_000_000L) { // > 1ms
                LOGGER.warning("Frame data collection took " + (collectTime / 1_000_000.0) + "ms");
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

    /**
     * 获取 MCRenderBridge 实例
     *
     * <p>使用延迟查找避免硬编码依赖。
     *
     * @return Bridge 实例（Object 类型），未找到返回 null
     */
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
     * 收集相机数据
     *
     * @param bridge MCRenderBridge 实例
     */
    private void collectCameraData(Object bridge) {
        // TODO: 实现相机数据收集逻辑
        // 通过反射或接口调用 bridge.getCurrentFrameData()
        // 然后提取 cameraX/Y/Z, yaw, pitch, fov 等
    }

    /**
     * 收集矩阵数据
     *
     * @param bridge MCRenderBridge 实例
     */
    private void collectMatrixData(Object bridge) {
        // TODO: 实现矩阵数据收集逻辑
        // 提取 projectionMatrix, viewMatrix, viewProjectionMatrix 等
    }

    /**
     * 收集可见性数据
     *
     * @param bridge MCRenderBridge 实例
     */
    private void collectVisibilityData(Object bridge) {
        // TODO: 实现可见性数据收集逻辑
        // 提取 visibleSectionCount, totalSectionCount, drawCallCounts 等
    }

    /**
     * 收集雾效数据
     *
     * @param bridge MCRenderBridge 实例
     */
    private void collectFogData(Object bridge) {
        // TODO: 实现雾效数据收集逻辑
        // 提取 fogColor, fogStart, fogEnd, fogDensity 等
    }

    /**
     * 收集窗口数据
     *
     * @param bridge MCRenderBridge 实例
     */
    private void collectWindowData(Object bridge) {
        // TODO: 实现窗口数据收集逻辑
        // 提取 windowWidth, windowHeight 等
    }
}
