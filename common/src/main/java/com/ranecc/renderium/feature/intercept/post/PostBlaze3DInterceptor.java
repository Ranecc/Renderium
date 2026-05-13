// Renderium - Blaze3D 拦截层系统
// 后拦截层接口 - 定义渲染后的拦截操作

package com.ranecc.renderium.feature.intercept.post;
import com.ranecc.renderium.domain.model.OutputContext;
import com.ranecc.renderium.domain.model.FrameGenContext;
import com.ranecc.renderium.domain.model.SuperResolutionContext;
import com.ranecc.renderium.domain.model.FrameCaptureContext;
import com.ranecc.renderium.domain.model.InterceptedFrameData;
import com.ranecc.renderium.domain.model.FrameData;

import com.ranecc.renderium.None;


/**
 * 后拦截层接口（Post-Blaze3D Interceptor）
 * <p>
 * 定义在 Blaze3D 渲染管线执行后的拦截操作，
 * 包括帧捕获、超分辨率处理、帧生成和屏幕输出等。
 * <p>
 * 此接口与 {@link PreBlaze3DInterceptor} 配合使用，
 * 构成完整的 Blaze3D 双拦截层架构。
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │         Blaze3D 渲染管线                     │
 * │  （Mojang 原始渲染流程）                      │
 * └──────────────────┬──────────────────────────┘
 *                    │
 *                    ▼
 * ┌─────────────────────────────────────────────┐
 * │       PostBlaze3DInterceptor               │  ← 本接口
 * │  ┌─────────────────────────────────────┐    │
 * │  │ 1. postProcess() - 后处理入口      │    │
 * │  │ 2. captureFrame() - 帧捕获        │    │
 * │  │ 3. applySuperResolution() - 超分辨率│   │
 * │  │ 4. applyFrameGeneration() - 帧生成  │    │
 * │  │ 5. outputToScreen() - 屏幕输出     │    │
 * │  └─────────────────────────────────────┘    │
 * └──────────────────┬──────────────────────────┘
 *                    │
 *                    ▼
 * ┌─────────────────────────────────────────────┐
 * │           屏幕输出                           │
 * │  （VkPresent / Swapchain）                   │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li><b>双路径支持</b>：同时支持 FBO 和 Swapchain Image 两种捕获模式</li>
 *   <li><b>可插拔架构</b>：各阶段可独立替换实现</li>
 *   <li><b>性能优先</b>：支持异步帧捕获和 Triple Buffering</li>
 * </ul>
 *
 * <h3>线程安全：</h3>
 * <p>实现类应保证线程安全，特别是在 Triple Buffering 场景下。
 * {@link #postProcess(FrameData)} 方法应在渲染线程调用。
 *
 * @see DefaultPostInterceptor 默认实现
 * @see InterceptedFrameData 扩展帧数据
 * @see PreBlaze3DInterceptor 前拦截层接口
 * @since 5.1.0
 */
public interface PostBlaze3DInterceptor {

    // ==================== 核心方法 ====================

    /**
     * 后处理入口方法（核心）
     * <p>
     * 这是后拦截层的主入口点，在 Blaze3D 渲染完成后被调用。
     * 负责协调所有后处理操作：
     * <ol>
     *   <li>帧捕获（FBO 或 Swapchain Image）</li>
     *   <li>超分辨率处理（DLSS/FSR/XeSS）</li>
     *   <li>帧生成（DLSS-FG/FSR-FG）</li>
     *   <li>EffectPipeline 后处理</li>
     *   <li>输出到屏幕</li>
     * </ol>
     *
     * <h3>调用时机：</h3>
     * <pre>
     * // 在 MixinRenderSystem.flipFrame() 中：
     * FrameData frameData = buildFrameData();
     *
     * // 调用后拦截层
     * FrameData finalFrame = postInterceptor.postProcess(frameData);
     *
     * if (finalFrame != null) {
     *     // 已处理后拦截完成
     * } else {
     *     // 处理失败，回退到原始流程
     * }
     * </pre>
     *
     * @param frameData 从 Blaze3D 捕获的原始帧数据
     * @return 处理后的最终帧数据（可能包含 HDR、质量指标等元数据），
     *         如果处理失败则返回 null 或原始 frameData
     * @throws IllegalArgumentException 如果 frameData 为 null
     */
    InterceptedFrameData postProcess(FrameData frameData);

    /**
     * 执行帧捕获操作
     * <p>
     * 根据当前运行模式选择不同的捕获策略：
     * <ul>
     *   <li><b>COMPATIBILITY 模式</b>：通过 FBO 读取像素数据</li>
     *   <li><b>AGGRESSIVE 模式</b>：直接访问 Swapchain Image</li>
     * </ul>
     * <p>
     * 支持同步和异步两种模式：
     * <ul>
     *   <li><b>同步模式</b>：阻塞等待捕获完成</li>
     *   <li><b>异步模式</b>：立即返回，通过回调通知完成</li>
     * </ul>
     *
     * @param context 帧捕获上下文（包含目标句柄、分辨率、模式等信息）
     * @return true 表示捕获成功，false 表示失败或跳过
     * @throws IllegalArgumentException 如果 context 为 null
     * @see FrameCaptureContext
     */
    boolean captureFrame(FrameCaptureContext context);

    /**
     * 应用超分辨率处理
     * <p>
     * 通过 Streamline SDK 执行超分辨率算法，
     * 将低分辨率输入放大为高分辨率输出。
     * <p>
     * 支持的超分辨率技术：
     * <ul>
     *   <li><b>DLSS</b>（NVIDIA Deep Learning Super Sampling）</li>
     *   <li><b>XeSS</b>（Intel Xe Super Sampling）</li>
     *   <li><b>FSR</b>（AMD FidelityFX Super Resolution）</li>
     * </ul>
     *
     * @param context 超分辨率上下文（包含输入/输出纹理、质量设置等）
     * @return true 表示处理成功，false 表示失败或未启用
     * @throws IllegalArgumentException 如果 context 为 null
     * @see SuperResolutionContext
     */
    boolean applySuperResolution(SuperResolutionContext context);

    /**
     * 应用帧生成处理
     * <p>
     * 使用 AI 算法在两帧之间插入中间帧，
     * 有效提升帧率并降低延迟。
     * <p>
     * 支持的帧生成技术：
     * <ul>
     *   <li><b>DLSS-FG</b>（NVIDIA DLSS Frame Generation）</li>
     *   <li><b>FSR-FG</b>（AMD FidelityFX Super Resolution Frame Generation）</li>
     * </ul>
     *
     * @param context 帧生成上下文（包含运动矢量、相机数据等）
     * @return true 表示处理成功，false 表示失败或未启用
     * @throws IllegalArgumentException 如果 context 为 null
     * @see FrameGenContext
     */
    boolean applyFrameGeneration(FrameGenContext context);

    /**
     * 输出到屏幕
     * <p>
     * 将最终处理后的画面呈现到显示器上。
     * 根据运行模式选择不同的输出策略：
     * <ul>
     *   <li><b>COMPATIBILITY 模式</b>：通过 OpenGL SwapBuffers</li>
     *   <li><b>AGGRESSIVE 模式</b>：直接调用 VkQueuePresentKHR</li>
     * </ul>
     *
     * @param context 输出上下文（包含目标缓冲区、显示区域等）
     * @return true 表示输出成功，false 表示失败
     * @throws IllegalArgumentException 如果 context 为 null
     * @see OutputContext
     */
    boolean outputToScreen(OutputContext context);

    // ==================== 生命周期方法 ====================

    /**
     * 初始化后拦截层
     * <p>
     * 在首次使用前必须调用此方法进行初始化。
     * 负责加载 Streamline SDK、初始化 EffectPipeline 等。
     *
     * @return true 表示初始化成功，false 表示失败
     */
    boolean initialize();

    /**
     * 关闭后拦截层并释放资源
     * <p>
     * 在不再需要时调用，释放所有持有的 GPU 资源。
     */
    void shutdown();

    /**
     * 检查是否已初始化
     *
     * @return true 如果已成功初始化且未关闭
     */
    boolean isInitialized();

    // ==================== 缓冲区管理 ====================

    /**
     * 检查 Triple Buffering 是否可用
     * <p>
     * Triple Buffering 可以减少帧延迟，
     * 但需要额外的显存开销。
     *
     * @return true 如果 Triple Buffering 已启用且可用
     */
    boolean isTripleBufferingAvailable();

    /**
     * 获取当前可用的缓冲区索引
     * <p>
     * 用于 Triple Buffering 场景下确定当前使用的缓冲区。
     *
     * @return 缓冲区索引（0, 1, 或 2）
     */
    int getCurrentBufferIndex();
}
