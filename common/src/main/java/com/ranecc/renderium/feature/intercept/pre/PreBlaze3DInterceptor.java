// Renderium - Blaze3D 拦截层系统
// 前拦截层接口 - 定义渲染前的拦截操作

package com.ranecc.renderium.feature.intercept.pre;
import com.ranecc.renderium.feature.intercept.base.LODContext;
import com.ranecc.renderium.feature.culling.core.CullingContext;
import com.ranecc.renderium.feature.intercept.base.InterceptionCallback;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.intercept.base.InterceptionResult;
import com.ranecc.renderium.feature.culling.core.CullingContext;
import com.ranecc.renderium.tech.stub.modoutputhandler.ModOutputHandler;

import com.ranecc.renderium.None;

/**
 * 前拦截层接口（Pre-Blaze3D Interceptor）
 * <p>
 * 定义在 Blaze3D 渲染管线执行前的拦截操作，
 * 包括模组输出检测、LOD 预处理注入、剔除优化注入等。
 * <p>
 * 此接口是 Blaze3D 拦截层系统的核心抽象，
 * 实现了依赖倒置原则（DIP），允许不同的实现策略。
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │           MixinRenderSystem                 │
 * │  （Minecraft 渲染入口）                       │
 * └──────────────────┬──────────────────────────┘
 *                    │
 *                    ▼
 * ┌─────────────────────────────────────────────┐
 * │        PreBlaze3DInterceptor                │  ← 本接口
 * │  ┌─────────────────────────────────────┐    │
 * │  │ 1. intercept() - 核心拦截方法       │    │
 * │  │ 2. isModDetected() - 模组检测      │    │
 * │  │ 3. registerModHandler() - 注册处理器│    │
 * │  │ 4. injectLOD() - LOD 预处理注入     │    │
 * │  │ 5. injectCulling() - 剔除优化注入   │    │
 * │  └─────────────────────────────────────┘    │
 * └──────────────────┬──────────────────────────┘
 *                    │
 *                    ▼
 * ┌─────────────────────────────────────────────┐
 * │         Blaze3D 渲染管线                     │
 * │  （Mojang 原始渲染流程）                      │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li><b>接口隔离</b>：每个方法职责单一，符合 ISP</li>
 *   <li><b>依赖倒置</b>：高层模块依赖此抽象，而非具体实现</li>
 *   <li><b>开放封闭</b>：易于扩展新的模组处理器，无需修改现有代码</li>
 * </ul>
 *
 * <h3>线程安全：</h3>
 * <p>实现类应保证线程安全，特别是在多线程渲染环境下。
 * {@link #intercept(RenderContext)} 方法应在渲染线程调用。
 *
 * @see DefaultPreInterceptor 默认实现
 * @see InterceptionResult 拦截结果
 * @see RenderContext 渲染上下文
 * @since 5.1.0
 */
public interface PreBlaze3DInterceptor {

    // ==================== 核心方法 ====================

    /**
     * 执行前拦截操作（核心方法）
     * <p>
     * 这是前拦截层的主入口点，在 Blaze3D 渲染管线执行前被调用。
     * 负责协调所有前置拦截操作：
     * <ol>
     *   <li>模组输出检测与处理</li>
     *   <li>LOD 预处理注入</li>
     *   <li>剔除优化注入</li>
     *   <li>渲染状态捕获与转换</li>
     * </ol>
     *
     * <h3>调用时机：</h3>
     * <pre>
     * // 在 MixinRenderSystem.renderLevel() 头部或 flipFrame() 之前：
     * RenderContext context = buildRenderContext();
     * InterceptionResult result = preInterceptor.intercept(context);
     *
     * if (result.isSuccess()) {
     *     // 使用修改后的上下文继续渲染
     *     context = result.getModifiedContext();
     * } else if (result.getStatus() == InterceptionResult.Status.FAILURE) {
     *     // 回退到原始路径
     *     return originalRendering(context);
     * }
     * </pre>
     *
     * @param context 渲染上下文（包含相机、模组、资源等信息）
     * @return 拦截结果（包含修改后的上下文、性能指标、状态信息）
     * @throws IllegalArgumentException 如果 context 为 null
     * @see InterceptionResult
     */
    InterceptionResult intercept(RenderContext context);

    /**
     * 检测指定模组是否存在且已加载
     * <p>
     * 用于在运行时动态检测第三方模组（如 Sodium、Iris、Oculus），
     * 以便选择合适的渲染策略和兼容性处理。
     *
     * <h3>支持的模组 ID：</h3>
     * <ul>
     *   <li><b>sodium</b> - Sodium 性能优化模组</li>
     *   <li><b>iris</b> - Iris 光影加载器</li>
     *   <li><b>oculus</b> - Oculus VR 模组</li>
     * </ul>
     *
     * <h3>使用示例：</h3>
     * <pre>
     * if (preInterceptor.isModDetected("sodium")) {
     *     // 使用 Sodium 兼容的 FBO 拦截策略
     *     applySodiumCompatibilityMode();
     * } else if (preInterceptor.isModDetected("iris")) {
     *     // 使用 Iris 光影兼容策略
     *     applyIrisShadersCompatibility();
     * }
     * </pre>
     *
     * @param modId 模组标识符（小写，如 "sodium"、"iris"、"oculus"）
     * @return true 如果模组存在且已成功加载
     * @throws IllegalArgumentException 如果 modId 为 null 或空字符串
     */
    boolean isModDetected(String modId);

    /**
     * 注册模组输出处理器
     * <p>
     * 为指定模组注册自定义的输出处理逻辑。
     * 当检测到该模组的渲染输出时，将调用对应的处理器进行处理。
     * <p>
     * 支持多个模组同时注册，每个模组可以有独立的处理器。
     *
     * <h3>设计模式：</h3>
     * <p>使用策略模式（Strategy Pattern），将不同模组的处理逻辑
     * 封装到独立的 {@link ModOutputHandler} 实现中。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * // 注册 Sodium 输出处理器
     * preInterceptor.registerModHandler("sodium", new ModOutputHandler() {
     *     &#64;Override
     *     public void handleOutput(ModOutputContext ctx) {
     *         // 处理 Sodium 的自定义 FBO 输出
     *         interceptSodiumFBO(ctx.getFboHandle());
     *     }
     * });
     *
     * // 注册 Iris 光影处理器
     * preInterceptor.registerModHandler("iris", new IrisShaderHandler());
     * </pre>
     *
     * @param modId   模组标识符（不能为 null 或空字符串）
     * @param handler 模组输出处理器实例（不能为 null）
     * @throws IllegalArgumentException 如果 modId 或 handler 为 null
     * @throws IllegalStateException    如果该模组已注册过处理器
     * @see ModOutputHandler
     */
    void registerModHandler(String modId, ModOutputHandler handler);

    /**
     * 注入 LOD（细节层次）预处理逻辑
     * <p>
     * 在渲染管线中插入 LOD 预处理阶段，
     * 用于根据距离动态调整几何体细节级别。
     * <p>
     * LOD 系统可以显著提升远景渲染性能：
     * <ul>
     *   <li>近景：完整几何体（0-24 区块）</li>
     *   <li>中景：简化几何 + 雾效（24-32 区块）</li>
     *   <li>远景：Billboard / 高度图（32+ 区块）</li>
     * </ul>
     *
     * <h3>参数说明：</h3>
     * <ul>
     *   <li>{@code lodContext.maxDistance} - 最大 LOD 距离（区块数）</li>
     *   <li>{@code lodContext.transitionStart} - 过渡区起点</li>
     *   <li>{@code lodContext.billboardEnabled} - 是否启用 Billboard</li>
     * </ul>
     *
     * @param lodContext LOD 上下文（包含距离、过渡参数、Billboard 配置等）
     * @throws IllegalArgumentException 如果 lodContext 为 null
     * @see LODContext
     * @see com.renderium.optimization.lod.RenderiumLODManager
     */
    void injectLOD(LODContext lodContext);

    /**
     * 注入剔除优化逻辑
     * <p>
     * 在渲染管线中插入剔除优化阶段，
     * 包括视锥体剔除、遮挡剔除、背面剔除等。
     * <p>
     * 剔除优化可以大幅减少不必要的绘制调用：
     * <ul>
     *   <li>视锥体剔除：移除视野外的几何体</li>
     *   <li>遮挡剔除：移除被遮挡的几何体</li>
     *   <li>背面剔除：移除背对相机的面</li>
     * </ul>
     *
     * <h3>参数说明：</h3>
     * <ul>
     *   <li>{@code cullContext.frustumEnabled} - 是否启用视锥体剔除</li>
     *   <li>{@code cullContext.occlusionEnabled} - 是否启用遮挡剔除</li>
     *   <li>{@code cullContext.maxDrawDistance} - 最大可视距离</li>
     * </ul>
     *
     * @param cullContext 剔除上下文（包含各种剔除开关和参数）
     * @throws IllegalArgumentException 如果 cullContext 为 null
     * @see CullingContext
     * @see com.renderium.culling.CullingController
     */
    void injectCulling(CullingContext cullContext);

    // ==================== 生命周期方法 ====================

    /**
     * 初始化前拦截层
     * <p>
     * 在首次使用前必须调用此方法进行初始化。
     * 负责加载配置、初始化内部状态、准备资源等。
     *
     * @return true 表示初始化成功，false 表示失败
     */
    boolean initialize();

    /**
     * 关闭前拦截层并释放资源
     * <p>
     * 在不再需要时调用，释放所有持有的资源。
     * 关闭后可通过 {@link #initialize()} 重新初始化。
     */
    void shutdown();

    /**
     * 检查是否已初始化
     *
     * @return true 如果已成功初始化且未关闭
     */
    boolean isInitialized();

    // ==================== 异步回调支持 ====================

    /**
     * 设置异步拦截完成回调
     * <p>
     * 当 {@link #intercept(RenderContext)} 在异步模式下执行完成时，
     * 将调用此回调通知调用方。
     * <p>
     * 注意：如果未设置异步回调，则默认使用同步模式。
     *
     * @param callback 异步完成回调（可以为 null 以禁用异步模式）
     */
    void setAsyncCallback(InterceptionCallback callback);
}
