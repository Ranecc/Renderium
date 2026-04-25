// Renderium - Rendering Extension Interface
// Platform-independent extension point for Minecraft's Vulkan renderer

package com.renderium.api;

import java.util.List;

/**
 * 渲染扩展接口
 * 允许模组扩展 Minecraft 26.2+ 的官方 Vulkan 渲染管线
 *
 * 实现此接口来添加自定义渲染功能，如：
 * - 自定义后处理效果
 * - DLSS/超分辨率集成
 * - 高级剔除算法
 * - 自定义着色器
 */
public interface RenderExtension {

    /**
     * 获取扩展名称
     * 用于调试和日志输出
     *
     * @return 扩展的显示名称
     */
    String getName();

    /**
     * 获取扩展优先级
     * 数值越小越先执行
     *
     * @return 优先级
     */
    default int getPriority() {
        return 1000;
    }

    /**
     * 在渲染管线初始化后调用
     * 用于获取 Vulkan 设备句柄、配置扩展
     *
     * @param vulkanDevice Vulkan 设备句柄（VkDevice）
     */
    default void onVulkanPipelineInit(long vulkanDevice) {}

    /**
     * 在每一帧开始时调用
     * 用于更新帧数据、设置渲染参数
     *
     * @param frameNumber 当前帧编号
     * @param deltaTime 自上一帧以来的时间（秒）
     */
    default void onFrameBegin(int frameNumber, float deltaTime) {}

    /**
     * 在不透明物体渲染完成后、后处理前调用
     * 用于插入自定义渲染通道
     *
     * @param commandBuffer 渲染命令缓冲区
     * @param depthTexture 深度纹理句柄（VkImage）
     * @param colorTexture 颜色纹理句柄（VkImage）
     */
    default void onOpaquePassRendered(long commandBuffer, long depthTexture, long colorTexture) {}

    /**
     * 在后处理链开始时调用
     * 用于插入自定义后处理效果
     *
     * @param commandBuffer 渲染命令缓冲区
     * @param sceneTexture 场景纹理（经过不透明渲染的结果）
     */
    default void onPostProcessingBegin(long commandBuffer, long sceneTexture) {}

    /**
     * 在最终帧输出前调用
     * 用于最终的后处理调整或输出到屏幕
     *
     * @param commandBuffer 渲染命令缓冲区
     * @param outputTexture 输出纹理
     * @param displayWidth 显示宽度
     * @param displayHeight 显示高度
     */
    default void onBeforeOutput(long commandBuffer, long outputTexture, int displayWidth, int displayHeight) {}

    /**
     * 提供自定义剔除器
     * 用于实现高级视距外剔除算法
     *
     * @return 剔除器实例，如果返回 null 则使用默认剔除
     */
    default FrustumCuller provideCuller() {
        return null;
    }

    /**
     * 提供自定义后处理器
     * 用于在渲染管线中添加自定义后处理效果
     *
     * @return 后处理器列表
     */
    default List<PostProcessor> providePostProcessors() {
        return List.of();
    }

    /**
     * 检查是否启用此扩展
     *
     * @return 是否启用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 获取依赖的扩展列表
     * 如果依赖的扩展未启用，此扩展也不会启用
     *
     * @return 依赖的扩展名称列表
     */
    default List<String> getDependencies() {
        return List.of();
    }

    /**
     * 扩展被禁用时的回调
     * 用于清理资源
     */
    default void onDisabled() {}
}
