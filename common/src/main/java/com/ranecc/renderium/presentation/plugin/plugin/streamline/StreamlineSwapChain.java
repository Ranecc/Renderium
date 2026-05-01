// Renderium - Blaze3D 优化器插件系统
// Streamline 交换链管理器接口

package com.ranecc.renderium.presentation.plugin.plugin.streamline;

/**
 * Streamline 交换链管理器
 * <p>
 * 管理独立的 Streamline 渲染交换链，
 * 用于支持 Frame Generation 等需要多帧历史的功能。
 *
 * <h2>工作原理：</h2>
 * <pre>
 * beginFrame() → 获取渲染目标
 *      ↓
 * [游戏渲染到目标]
 *      ↓
 * endFrame() → 提交帧数据给 Streamline
 *      ↓
 * [Streamline 处理: DLSS/FG/Reflex]
 *      ↓
 * present() → 显示到屏幕
 * </pre>
 *
 * <h2>资源管理：</h2>
 * <ul>
 *   <li>维护多个 RenderTarget 用于历史帧</li>
 *   <li>管理 Vulkan SwapChain 或 OpenGL FBO</li>
 *   <li>处理窗口大小变化</li>
 * </ul>
 *
 * <h3>使用场景：</h3>
 * 仅在 {@link StreamlineIntegrationStrategy#SWAP_CHAIN} 策略下使用。
 * 其他策略不需要此组件。
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public interface StreamlineSwapChain {

    /**
     * 获取交换链状态
     *
     * @return 当前状态枚举
     */
    SwapChainState getState();

    /**
     * 初始化交换链
     * <p>创建 Streamline 原生交换链和渲染目标数组。
     *
     * @param windowHandle  平台特定的窗口句柄（HWND/Window）
     * @param width         初始宽度
     * @param height        初始高度
     * @param bufferCount   缓冲区数量（通常 3 用于三重缓冲）
     * @return 初始化成功返回 true
     */
    boolean initialize(long windowHandle, int width, int height, int bufferCount);

    /**
     * 开始新的一帧
     * <p>获取当前帧的渲染目标，推进帧索引。
     *
     * @return 当前帧的渲染目标句柄，失败返回 0
     */
    long beginFrame();

    /**
     * 结束当前帧并提交给 Streamline
     * <p>将收集的帧数据提交给 Streamline SDK 进行处理。
     *
     * @param frameData 当前帧的完整数据
     * @return 提交成功返回 true
     */
    boolean endFrame(StreamlineFrameData frameData);

    /**
     * 呈现最终结果到屏幕
     * <p>在 Streamline 处理完成后调用。
     *
     * @return 呈现成功返回 true
     */
    boolean present();

    /**
     * 调整交换链尺寸
     * <p>窗口大小改变时调用。
     *
     * @param newWidth  新宽度
     * @param newHeight 新高度
     * @return 调整成功返回 true
     */
    boolean resize(int newWidth, int newHeight);

    /**
     * 获取历史帧渲染目标
     * <p>用于 Frame Generation 的时间插值。
     *
     * @param framesAgo 几帧之前（0=当前帧，-1=上一帧）
     * @return 历史帧句柄，不存在返回 0
     */
    long getHistoryFrame(int framesAgo);

    /**
     * 销毁交换链，释放所有资源
     */
    void destroy();

    // ==================== 内部状态枚举 ====================

    /**
     * 交换链状态
     */
    enum SwapChainState {
        /** 未初始化 */
        UNINITIALIZED,
        /** 已初始化，可用 */
        READY,
        /** 正在渲染一帧 */
        RENDERING,
        /** 等待呈现 */
        PENDING_PRESENT,
        /** 已销毁 */
        DESTROYED,
        /** 出错状态 */
        ERROR
    }
}
