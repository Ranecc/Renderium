// Renderium - Blaze3D 拦截层系统
// 后拦截层接口（平台钩子）- 定义渲染后的拦截操作

package com.ranecc.renderium.platform.hook;

import com.ranecc.renderium.domain.model.OutputContext;
import com.ranecc.renderium.domain.model.FrameGenContext;
import com.ranecc.renderium.domain.model.SuperResolutionContext;
import com.ranecc.renderium.domain.model.FrameCaptureContext;
import com.ranecc.renderium.domain.model.InterceptedFrameData;
import com.ranecc.renderium.domain.model.FrameData;

/**
 * 后拦截层接口（Post-Blaze3D Interceptor）- 平台钩子版本
 * <p>
 * 此接口镜像 {@code PostBlaze3DInterceptor} 的方法签名，
 * 所有参数类型已迁移到 domain.model，故无需替换。
 *
 * <p>
 * 定义在 Blaze3D 渲染管线执行后的拦截操作，
 * 包括帧捕获、超分辨率处理、帧生成和屏幕输出等。
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
 * │         PostInterceptor（平台钩子）          │
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
 * @see PreInterceptor 前拦截层接口
 * @see InterceptedFrameData 扩展帧数据
 * @since 5.1.0
 */
public interface PostInterceptor {

    // ==================== 核心方法 ====================

    /**
     * 后处理入口方法（核心）
     * <p>
     * 这是后拦截层的主入口点，在 Blaze3D 渲染完成后被调用。
     * 负责协调所有后处理操作。
     *
     * @param frameData 从 Blaze3D 捕获的原始帧数据
     * @return 处理后的最终帧数据（可能包含 HDR、质量指标等元数据），
     *         如果处理失败则返回 null 或原始 frameData
     * @throws IllegalArgumentException 如果 frameData 为 null
     */
    InterceptedFrameData postProcess(FrameData frameData);

    /**
     * 执行帧捕获操作
     *
     * @param context 帧捕获上下文（包含目标句柄、分辨率、模式等信息）
     * @return true 表示捕获成功，false 表示失败或跳过
     * @throws IllegalArgumentException 如果 context 为 null
     */
    boolean captureFrame(FrameCaptureContext context);

    /**
     * 应用超分辨率处理
     *
     * @param context 超分辨率上下文（包含输入/输出纹理、质量设置等）
     * @return true 表示处理成功，false 表示失败或未启用
     * @throws IllegalArgumentException 如果 context 为 null
     */
    boolean applySuperResolution(SuperResolutionContext context);

    /**
     * 应用帧生成处理
     *
     * @param context 帧生成上下文（包含运动矢量、相机数据等）
     * @return true 表示处理成功，false 表示失败或未启用
     * @throws IllegalArgumentException 如果 context 为 null
     */
    boolean applyFrameGeneration(FrameGenContext context);

    /**
     * 输出到屏幕
     *
     * @param context 输出上下文（包含目标缓冲区、显示区域等）
     * @return true 表示输出成功，false 表示失败
     * @throws IllegalArgumentException 如果 context 为 null
     */
    boolean outputToScreen(OutputContext context);

    // ==================== 生命周期方法 ====================

    /**
     * 初始化后拦截层
     *
     * @return true 表示初始化成功，false 表示失败
     */
    boolean initialize();

    /**
     * 关闭后拦截层并释放资源
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
     *
     * @return true 如果 Triple Buffering 已启用且可用
     */
    boolean isTripleBufferingAvailable();

    /**
     * 获取当前可用的缓冲区索引
     *
     * @return 缓冲区索引（0, 1, 或 2）
     */
    int getCurrentBufferIndex();
}
