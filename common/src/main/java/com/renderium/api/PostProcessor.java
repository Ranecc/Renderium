// Renderium - Post Processor Interface
// Custom post-processing effects

package com.renderium.api;

/**
 * 后处理器接口
 * 用于添加自定义后处理效果
 */
public interface PostProcessor {

    /**
     * 获取后处理器名称
     */
    String getName();

    /**
     * 获取执行顺序
     * 数值越小越先执行
     */
    default int getOrder() {
        return 1000;
    }

    /**
     * 是否启用此后处理器
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 获取所需的输入纹理
     */
    default TextureRequirement[] getInputRequirements() {
        return new TextureRequirement[0];
    }

    /**
     * 执行后处理
     *
     * @param commandBuffer Vulkan 命令缓冲区
     * @param inputs 输入纹理
     * @param output 输出纹理
     * @param width 渲染宽度
     * @param height 渲染高度
     */
    void process(long commandBuffer,
                 TextureInputs inputs,
                 TextureOutput output,
                 int width, int height);

    /**
     * 纹理需求
     */
    record TextureRequirement(
        String name,
        TextureType type
    ) {
        public enum TextureType {
            COLOR,
            DEPTH,
            MOTION_VECTOR,
            NORMAL,
            OUTPUT
        }
    }

    /**
     * 输入纹理集合
     */
    record TextureInputs(
        long colorTexture,
        long depthTexture,
        long motionVectorTexture,
        long normalTexture
    ) {}

    /**
     * 输出纹理
     */
    record TextureOutput(
        long texture,
        int width,
        int height
    ) {}

    /**
     * 后处理上下文
     * <p>
     * 封装后处理执行所需的帧数据、输出缓冲区和渲染模式信息。
     * 由 {@link BackendInterceptor} 在帧处理流程中创建并传递给 EffectPipeline。
     *
     * @param frameData   当前帧数据（颜色/深度纹理、分辨率、时序信息）
     * @param outputBuffer 输出目标缓冲区句柄（VkImage）
     * @param mode        当前运行模式（COMPATIBILITY 或 AGGRESSIVE）
     */
    record Context(
        com.renderium.backend.FrameData frameData,
        long outputBuffer,
        com.renderium.core.RenderiumMode mode
    ) {}
}
