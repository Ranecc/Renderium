// Renderium - Pass 处理器接口
// 用于定义自定义 Pass 处理器的标准接口

package com.renderium.router;

/**
 * Pass 处理器接口
 *
 * <p>定义自定义 Pass 处理器的标准接口，
 * 用于在 PassRouter 中注册和执行自定义渲染逻辑。
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see PassRouter
 */
public interface PassHandler {

    /**
     * 处理 Pass 执行
     *
     * @param commandBuffer Vulkan 命令缓冲区句柄
     */
    void handle(long commandBuffer);

    /**
     * Pass 执行前的回调（用于 ENHANCE 策略）
     *
     * @param commandBuffer Vulkan 命令缓冲区句柄
     */
    default void onBeforePass(long commandBuffer) {
        // 默认空实现
    }

    /**
     * Pass 执行后的回调（用于 ENHANCE 策略）
     *
     * @param commandBuffer Vulkan 命令缓冲区句柄
     */
    default void onAfterPass(long commandBuffer) {
        // 默认空实现
    }
}
