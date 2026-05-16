// Renderium - Frame Generator Interface
// 帧生成技术统一接口

package com.ranecc.renderium.tech.framegen;

/**
 * 帧生成器接口
 * <p>
 * 为 DLSS Frame Generation、FSR 3 Frame Generation 等帧生成技术提供统一抽象。
 * <p>
 * 帧生成技术通过在两个渲染帧之间插入 AI 生成的帧来提高帧率：
 * <ul>
 *   <li>2X: 每个渲染帧后生成 1 个插值帧</li>
 *   <li>4X: 每个渲染帧后生成 3 个插值帧（DLSS 4）</li>
 *   <li>6X: 每个渲染帧后生成 5 个插值帧（DLSS 4.5）</li>
 * </ul>
 *
 * @see FrameGenMode
 * @see DLSSFGAdapter
 * @see FSRFGAdapter
 */
public interface FrameGenerator {

    /**
     * 获取技术名称
     */
    String getName();

    /**
     * 检查帧生成是否支持
     */
    boolean isSupported();

    /**
     * 检查帧生成是否启用
     */
    boolean isEnabled();

    /**
     * 获取当前帧生成模式
     */
    FrameGenMode getMode();

    /**
     * 启用帧生成
     *
     * @param mode 帧生成模式
     */
    void enable(FrameGenMode mode);

    /**
     * 禁用帧生成
     */
    void disable();

    /**
     * 执行帧生成
     * <p>
     * 在主帧渲染完成后调用，生成插值帧。
     *
     * @param currentFrame  当前帧数据
     * @param previousFrame 前一帧数据
     * @param cmdBuffer     VkCommandBuffer 句柄
     */
    void generateFrame(FrameGenData currentFrame, FrameGenData previousFrame, long cmdBuffer);

    /**
     * 关闭帧生成器
     */
    void shutdown();

    /**
     * 帧生成数据
     *
     * @param colorImageView        颜色纹理 VkImageView 句柄
     * @param depthImageView        深度纹理 VkImageView 句柄
     * @param motionVectorImageView 运动矢量纹理 VkImageView 句柄
     * @param textureWidth          纹理宽度
     * @param textureHeight         纹理高度
     * @param cameraData            相机数据
     */
    record FrameGenData(
        long colorImageView,
        long depthImageView,
        long motionVectorImageView,
        int textureWidth,
        int textureHeight,
        com.ranecc.renderium.tech.dlss.DLSSManager.CameraData cameraData
    ) {}
}
