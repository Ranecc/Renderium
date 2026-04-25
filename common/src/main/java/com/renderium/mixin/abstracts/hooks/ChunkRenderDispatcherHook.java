// Renderium - ChunkRenderDispatcher 拦截 Hook
// 用于从 Minecraft 渲染管线中提取地形网格数据
//
// 设计目标：
//   1. 在 ChunkRenderDispatcher.renderChunkLayer() 处注入
//   2. 提取可见区块列表、顶点缓冲区、纹理图集引用
//   3. 将数据传递给 GBufferGeometryNode / ShadowMapNode / Hi-Z Builder
//
// 数据流：
//   MC ChunkRenderDispatcher → [Mixin 注入] → ChunkRenderDispatcherHook
//     → MCDataBridgeAdapter → GBufferGeometryNode / ShadowMapNode

package com.renderium.mixin.abstracts.hooks;

import java.util.List;

/**
 * 区块渲染调度器拦截接口 🏗️
 * <p>
 * 通过 Mixin 注入到 Minecraft 的 {@code ChunkRenderDispatcher.render()} 方法中，
 * 在每帧渲染开始时捕获可见区块列表和渲染状态。
 * <p>
 * 这是 Renderium 从 Minecraft 获取真实地形数据的**唯一官方通道**。
 *
 * <h2>注入位置</h2>
 * <pre>
 * @Mixin(ChunkRenderDispatcher.class)
 * public class ChunkRenderDispatcherMixin {
 *     @Inject(method = "render(Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;Lnet/minecraft/client/util/Camera;FLnet/minecraft/client/render/Frustum;IIZ)V",
 *           at = @At("HEAD"))
 *     private void onRenderStart(BuiltChunk chunk, Camera camera, Frustum frustum,
 *                                 int frameCount, boolean spectator, CallbackInfo ci) {
 *         // 调用 Hook 实现者
 *         ChunkRenderDispatcherHook hook = HookManager.getChunkDispatcherHook();
 *         if (hook != null) {
 *             hook.onRenderFrameStart(this, camera, frustum, frameCount);
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>提取的数据类型</h3>
 * <table border="1">
 *   <tr><th>数据</th><th>用途</th><th>消费者</th></tr>
 *   <tr><td>可见区块列表</td><td>GBuffer 几何输入</td><td>GBufferGeometryNode</td></tr>
 *   <tr><td>顶点缓冲区</td><td>GPU Driven 渲染</td><td>BatchTransformEngine</td></tr>
 *   <tr><td>纹理图集</td><td>材质采样</td><td>PBRMaterialNode</td></tr>
 *   <tr><td>区块坐标 → 世界矩阵</td><td>阴影映射</td><td>ShadowMapNode</td></tr>
 * </table>
 *
 * <h3>性能约束</h3>
 * <ul>
 *   <li>Hook 执行时间 &lt; 0.1ms/帧（热路径）</li>
 *   <li>禁止在 Hook 中分配内存（使用预分配缓冲区）</li>
 *   <li>禁止在 Hook 中调用 Vulkan API（仅数据提取）</li>
 * </ul>
 *
 * @see com.renderium.bridge.MCRenderBridge MC 数据桥接器
 * @see com.renderium.pipeline.node.builtin.GBufferGeometryNode GBuffer 几何节点
 * @since 5.3.0
 */
@FunctionalInterface
public interface ChunkRenderDispatcherHook {

    /**
     * 帧渲染开始回调（在 ChunkRenderDispatcher.render() 入口处调用）
     * <p>
     * 此方法每帧调用一次，用于：
     * 1. 重置帧数据快照
     * 2. 提取当前相机参数
     * 3. 预分配可见区块列表缓冲区
     *
     * 【方法参数】
     * @param dispatcher Object - ChunkRenderDispatcher 实例（MC 原生对象）
     * @param camera     Object - 当前相机实例（MC Camera 对象）
     * @param frustum    Object - 视锥体实例（MC Frustum 对象）
     * @param frameCount int    - 当前帧号
     *
     * 【返回值】void
     *
     * 【注意事项】
     * - 此方法在渲染线程调用，必须线程安全
     * - 禁止阻塞操作或重量级计算
     * - 应尽快返回，避免影响 MC 渲染性能
     */
    void onRenderFrameStart(Object dispatcher, Object camera, Object frustum, int frameCount);

    /**
     * 区块渲染回调（每个可见区块调用一次）
     * <p>
     * 当某个区块被判定为可见并准备渲染时触发。
     * 用于收集可见区块列表和构建 GPU Driven 渲染命令。
     *
     * 【方法参数】
     * @param builtChunk   Object - BuiltChunk 实例（MC 区块渲染单元）
     * @param chunkPos     long   - 区块坐标编码 (x | (z << 32))
     * @param modelViewMat float[] - 该区块的模型视图矩阵 (4x4, 列优先)
     * @param vertexBuffer Object - 顶点缓冲区（可能为 BufferBuilder 或 MeshData）
     *
     * 【返回值】boolean - true 表示允许继续渲染，false 表示跳过（由 Renderium 接管）
     *
     * 【使用场景】
     * <pre>{@code
     * // 在 Mixin 中：
     * @Inject(method = "render", at = @At("INVOKE: ... drawChunk"))
     * private void onDrawChunk(BuiltChunk chunk, MatrixStack matrices, ...) {
     *     ChunkRenderDispatcherHook hook = HookManager.getChunkDispatcherHook();
     *     if (hook != null) {
     *         float[] mv = extractModelViewMatrix(matrices);
     *         boolean skip = hook.onVisibleChunk(chunk, encodeChunkPos(chunk), mv, null);
     *         if (skip) ci.cancel();  // Renderium 接管此区块的渲染
     *     }
     * }
     * }</pre>
     */
    default boolean onVisibleChunk(Object builtChunk, long chunkPos,
                                    float[] modelViewMat, Object vertexBuffer) {
        // 默认实现：不干预 MC 的正常渲染流程
        return true;
    }

    /**
     * 帧渲染结束回调（所有区块渲染完成后调用）
     * <p>
     * 用于：
     * 1. 最终化可见区块列表
     * 2. 触发 GBufferGeometryNode / ShadowMapNode 的数据更新
     * 3. 准备下一帧的数据缓冲区
     *
     * 【方法参数】
     * @param visibleChunkCount int - 本帧渲染的可见区块总数
     * @param renderTimeNs      long - 区块渲染总耗时（纳秒）
     *
     * 【返回值】void
     */
    default void onRenderFrameEnd(int visibleChunkCount, long renderTimeNs) {
        // 默认实现：空操作
    }

    /**
     * 获取当前帧的可见区块列表（只读）
     * <p>
     * 由 GBufferGeometryNode 和 ShadowMapNode 调用，
     * 获取本帧需要处理的区块集合。
     *
     * @return List<Object> - 可见区块列表的不可变视图；如果未初始化返回空列表
     */
    default List<Object> getVisibleChunks() {
        return List.of();
    }

    /**
     * 检查 Hook 是否已正确初始化并连接到 MC 渲染管线
     *
     * @return boolean - true 表示 Hook 已就绪可以接收数据
     */
    default boolean isInitialized() {
        return false;
    }
}
