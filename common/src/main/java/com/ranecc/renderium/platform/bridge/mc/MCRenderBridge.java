// Renderium - 轻量 MC 抽象层
// MC 渲染桥接器 - 代码层与 MC 渲染状态的唯一交互入口

package com.ranecc.renderium.platform.bridge.mc;

import com.ranecc.renderium.None;
import com.ranecc.renderium.domain.model.FrameData;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

/**
 * MC 渲染桥接器（无状态静态接口）
 * <p>
 * 代码层（PipelineNode、RGBShaderModule、EffectPipeline 等）
 * 通过此桥接器获取 MC 渲染状态，<b>零 Mixin 依赖</b>。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>所有方法为静态方法，无实例状态</li>
 *   <li>查询时间预算 &lt; 50ns（ThreadLocal 读取 + volatile 读取）</li>
 *   <li>代码层禁止 import 任何 Mixin 类，只通过此桥接器交互</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │  Code Layer (PipelineNode, ShaderModule)     │
 * │       ↓ 仅通过 MCRenderBridge 查询           │
 * ├─────────────────────────────────────────────┤
 * │  MCRenderBridge (Static Methods)             │
 * │  getCurrentFrameData()  → ThreadLocal        │
 * │  getBatchTransformer()  → BatchTransformEngine│
 * │  getCommandBatcher()    → CommandBatcher      │
 * │  getBackendHookPoint()  → BackendHookPoint    │
 * ├─────────────────────────────────────────────┤
 * │  LifecycleManager (填充 FrameData)           │
 * │       ↑ 由 Mixin 调用（Thin Glue）           │
 * ├─────────────────────────────────────────────┤
 * │  Mixin Layer (&lt; 150 lines total)            │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * @see FrameDataSnapshot
 * @see RenderiumLifecycleManager
 * @since 1.0.0
 */
public final class MCRenderBridge {

    private MCRenderBridge() {}

    // ==================== ThreadLocal 帧数据 ====================

    /** 每线程帧数据快照（预分配，每帧 reset 复用） */
    private static final ThreadLocal<FrameDataSnapshot> FRAME_DATA =
            ThreadLocal.withInitial(FrameDataSnapshot::new);

    // ==================== 批处理引擎引用 ====================

    /** 批量顶点变换引擎 v2（volatile 发布，初始化时设置） */
    private static volatile BatchTransformEngine batchTransformEngine;

    /** 批量顶点变换引擎 v3 - 极致性能版本（目标 10K 顶点 < 30μs） */
    private static volatile BatchTransformEngineV3 batchTransformEngineV3;

    /** GPU 命令批处理器（volatile 发布，初始化时设置） */
    private static volatile CommandBatcher commandBatcher;

    /** 后端劫持钩子（volatile 发布，初始化时设置） */
    private static volatile BackendHookPoint backendHookPoint;

    /** 生命周期管理器（volatile 发布，初始化时设置） */
    private static volatile RenderiumLifecycleManager lifecycleManager;

    // ==================== 优化器注册表（替代 GpuDeviceMixin 直接引用） ====================

    /** Memory Optimizer 引用（volatile 发布，替代 GpuDeviceMixin.memoryOptimizer） */
    private static volatile Object memoryOptimizer = null;

    /** Pipeline Optimizer 引用（volatile 发布，替代 GpuDeviceMixin.pipelineOptimizer） */
    private static volatile Object pipelineOptimizer = null;

    /** GpuDevice 初始化状态（volatile 发布，替代 GpuDeviceMixin.fullyInitialized） */
    private static volatile boolean gpuDeviceInitialized = false;

    // ==================== 公共查询 API ====================

    /**
     * 获取当前线程的帧数据快照
     * <p>
     * 由 {@link RenderiumLifecycleManager} 在每个生命周期钩子中填充。
     * 代码层通过此方法获取投影矩阵、视图矩阵、相机位置等渲染状态。
     * <p>
     * 性能：ThreadLocal 读取 + 直接引用返回，&lt; 50ns。
     *
     * @return 当前线程的帧数据快照（非 null，预分配对象）
     */
    public static FrameDataSnapshot getCurrentFrameData() {
        return FRAME_DATA.get();
    }

    /**
     * 获取批量顶点变换引擎
     * <p>
     * 用于 GBufferGeometryNode、ShadowMapNode 等需要批量变换顶点的节点。
     * 如果引擎未初始化，返回 null。
     *
     * @return BatchTransformEngine 实例，可能为 null（未初始化时）
     */
    public static BatchTransformEngine getBatchTransformer() {
        return batchTransformEngine;
    }

    /**
     * 获取批量顶点变换引擎 V3（极致性能版本）
     * <p>
     * 优先使用此版本进行高性能批量顶点变换。
     * 性能目标：10K 顶点 &lt; 30μs（Unsafe + 8x 展开 + 双步 NR）。
     * <p>
     * 如果 V3 未初始化，回退到 v2 版本。
     *
     * @return BatchTransformEngineV3 实例（可能为 null）
     */
    public static BatchTransformEngineV3 getBatchTransformerV3() {
        return batchTransformEngineV3;
    }

    /**
     * 获取 GPU 命令批处理器
     * <p>
     * 用于合并 UBO 更新、纹理上传等 GPU 命令。
     * 如果处理器未初始化，返回 null。
     *
     * @return CommandBatcher 实例，可能为 null（未初始化时）
     */
    public static CommandBatcher getCommandBatcher() {
        return commandBatcher;
    }

    /**
     * 获取后端劫持钩子
     * <p>
     * 用于矩阵去重、Draw Call 合并等优化。
     * 如果钩子未初始化，返回 null。
     *
     * @return BackendHookPoint 实例，可能为 null（未初始化时）
     */
    public static BackendHookPoint getBackendHookPoint() {
        return backendHookPoint;
    }

    /**
     * 获取生命周期管理器
     * <p>
     * 用于注册/注销生命周期监听器。
     * 如果管理器未初始化，返回 null。
     *
     * @return RenderiumLifecycleManager 实例，可能为 null（未初始化时）
     */
    public static RenderiumLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    // ==================== 优化器注册表 API（替代 GpuDeviceMixin） ====================

    /**
     * 设置 Memory Optimizer
     * <p>
     * 替代 GpuDeviceMixin.setMemoryOptimizer() 的直接调用。
     * 代码层通过此方法注入 Memory Optimizer，Mixin 层通过 getMemoryOptimizer() 读取。
     *
     * @param optimizer MemoryOptimizer 实例
     */
    public static void setMemoryOptimizer(Object optimizer) {
        memoryOptimizer = optimizer;
    }

    /**
     * 获取 Memory Optimizer
     * <p>
     * Mixin 层通过此方法获取代码层注册的 Memory Optimizer。
     *
     * @return MemoryOptimizer 实例，可能为 null
     */
    public static Object getMemoryOptimizer() {
        return memoryOptimizer;
    }

    /**
     * 设置 Pipeline Optimizer
     * <p>
     * 替代 GpuDeviceMixin.setPipelineOptimizer() 的直接调用。
     *
     * @param optimizer PipelineOptimizer 实例
     */
    public static void setPipelineOptimizer(Object optimizer) {
        pipelineOptimizer = optimizer;
    }

    /**
     * 获取 Pipeline Optimizer
     * <p>
     * Mixin 层通过此方法获取代码层注册的 Pipeline Optimizer。
     *
     * @return PipelineOptimizer 实例，可能为 null
     */
    public static Object getPipelineOptimizer() {
        return pipelineOptimizer;
    }

    /**
     * 设置 GpuDevice 初始化状态
     * <p>
     * 替代 GpuDeviceMixin.setFullyInitialized()。
     *
     * @param initialized 是否已完全初始化
     */
    public static void setGpuDeviceInitialized(boolean initialized) {
        gpuDeviceInitialized = initialized;
    }

    /**
     * 检查 GpuDevice 是否已完全初始化
     * <p>
     * 替代 GpuDeviceMixin.isFullyInitialized()。
     *
     * @return true 如果已完全初始化
     */
    public static boolean isGpuDeviceInitialized() {
        return gpuDeviceInitialized;
    }

    // ==================== 初始化 API（仅由 RenderiumCore 调用） ====================

    /**
     * 设置批量顶点变换引擎 v2
     *
     * @param engine BatchTransformEngine 实例
     */
    public static void setBatchTransformer(BatchTransformEngine engine) {
        batchTransformEngine = engine;
    }

    /**
     * 设置批量顶点变换引擎 v3（极致性能版本）
     *
     * @param engine BatchTransformEngineV3 实例
     */
    public static void setBatchTransformerV3(BatchTransformEngineV3 engine) {
        batchTransformEngineV3 = engine;
    }

    /**
     * 设置 GPU 命令批处理器
     *
     * @param batcher CommandBatcher 实例
     */
    public static void setCommandBatcher(CommandBatcher batcher) {
        commandBatcher = batcher;
    }

    /**
     * 设置后端劫持钩子
     *
     * @param hook BackendHookPoint 实例
     */
    public static void setBackendHookPoint(BackendHookPoint hook) {
        backendHookPoint = hook;
    }

    /**
     * 设置生命周期管理器
     *
     * @param manager RenderiumLifecycleManager 实例
     */
    public static void setLifecycleManager(RenderiumLifecycleManager manager) {
        lifecycleManager = manager;
    }

    // ==================== 帧边界管理 ====================

    /**
     * 标记新帧开始
     * <p>
     * 由 Mixin 在 GameRenderer.render() 入口处调用。
     * 重置帧数据快照，保留历史视图矩阵缓冲区。
     */
    public static void beginFrame() {
        FRAME_DATA.get().reset();
    }

    /**
     * 标记帧结束
     * <p>
     * 由 Mixin 在帧呈现后调用。
     * 刷新所有批处理器的待提交命令。
     */
    public static void endFrame() {
        CommandBatcher batcher = commandBatcher;
        if (batcher != null) {
            batcher.flush();
        }
    }

    // ==================== 诊断 API ====================

    /**
     * 检查桥接器是否已完全初始化
     *
     * @return true 如果所有核心组件已设置
     */
    public static boolean isFullyInitialized() {
        return lifecycleManager != null;
    }

    /**
     * 重置所有引用（用于测试或模块卸载）
     */
    public static void reset() {
        batchTransformEngine = null;
        commandBatcher = null;
        backendHookPoint = null;
        lifecycleManager = null;
        memoryOptimizer = null;
        pipelineOptimizer = null;
        gpuDeviceInitialized = false;
    }
}
