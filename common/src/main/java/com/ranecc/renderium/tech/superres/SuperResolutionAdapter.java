// Renderium - Super Resolution Adapter Interface
// 超分辨率技术统一接口

package com.ranecc.renderium.tech.superres;
import com.ranecc.renderium.domain.model.FrameData;

import com.ranecc.renderium.None;

/**
 * 超分辨率适配器统一接口
 * <p>
 * 为 DLSS、XeSS、FSR 等超分辨率技术提供统一抽象。
 * 所有超分辨率技术都通过此接口访问，实现多技术栈的无缝切换。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link DLSSAdapter} - NVIDIA DLSS 4.5</li>
 *   <li>{@link XeSSAdapter} - Intel XeSS</li>
 *   <li>{@link FSRAdapter} - AMD FSR 3</li>
 * </ul>
 *
 * @see SuperResolutionManager
 */
public interface SuperResolutionAdapter {

    /**
     * 获取技术名称
     *
     * @return 名称（如 "DLSS 4.5"、"XeSS"、"FSR 3"）
     */
    String getName();

    /**
     * 获取技术类型
     *
     * @return 技术类型枚举
     */
    Technology getTechnology();

    /**
     * 检查技术是否可用
     * <p>
     * 检查 GPU 是否支持、SDK 是否加载等。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 检查技术是否已启用
     *
     * @return 是否已启用
     */
    boolean isEnabled();

    /**
     * 启用超分辨率
     *
     * @param quality 质量预设
     */
    void enable(Quality quality);

    /**
     * 禁用超分辨率
     */
    void disable();

    /**
     * 获取当前质量设置
     *
     * @return 质量预设
     */
    Quality getQuality();

    /**
     * 设置质量
     *
     * @param quality 质量预设
     */
    void setQuality(Quality quality);

    /**
     * 获取推荐的渲染分辨率
     *
     * @param displayWidth  显示宽度
     * @param displayHeight 显示高度
     * @param quality       质量预设
     * @return 推荐的渲染分辨率
     */
    Resolution getRecommendedResolution(int displayWidth, int displayHeight, Quality quality);

    /**
     * 执行帧评估
     * <p>
     * 在主场景渲染完成后调用。
     *
     * @param frameData 帧数据
     */
    void evaluate(FrameData frameData);

    /**
     * 更新分辨率
     *
     * @param renderWidth  渲染宽度
     * @param renderHeight 渲染高度
     * @param displayWidth 显示宽度
     * @param displayHeight 显示高度
     */
    void updateResolution(int renderWidth, int renderHeight,
                           int displayWidth, int displayHeight);

    /**
     * 关闭适配器
     */
    void shutdown();

    /**
     * 超分辨率技术类型
     */
    enum Technology {
        DLSS("NVIDIA DLSS", 0),
        XESS("Intel XeSS", 1),
        FSR("AMD FSR", 2),
        NATIVE("Native", 3),
        AUTO("Auto Select", -1);  // 自动选择最佳可用技术

        public final String displayName;
        public final int priority;

        Technology(String displayName, int priority) {
            this.displayName = displayName;
            this.priority = priority;
        }
    }

    /**
     * 质量预设
     * <p>
     * 统一的质量级别，各适配器映射到自己的质量参数。
     */
    enum Quality {
        ULTRA_QUALITY(0.77f, 0.75f, 1.5f),
        QUALITY(0.75f, 0.67f, 1.5f),
        BALANCED(0.66f, 0.58f, 1.7f),
        PERFORMANCE(0.50f, 0.50f, 2.0f),
        ULTRA_PERFORMANCE(0.33f, 0.33f, 3.0f),
        NATIVE(1.0f, 1.0f, 1.0f);

        /** DLSS/XeSS 缩放因子 */
        public final float dlssScale;
        /** XeSS 缩放因子 */
        public final float xessScale;
        /** FSR 缩放因子（1/renderScale） */
        public final float fsrScale;

        Quality(float dlssScale, float xessScale, float fsrScale) {
            this.dlssScale = dlssScale;
            this.xessScale = xessScale;
            this.fsrScale = fsrScale;
        }
    }

    /**
     * 分辨率记录
     */
    record Resolution(int width, int height) {}

    /**
     * 帧数据
     * <p>
     * 超分辨率评估所需的帧数据。
     *
     * @param commandBuffer   VkCommandBuffer 句柄
     * @param colorImageView  输入颜色纹理 VkImageView 句柄
     * @param depthImageView  深度纹理 VkImageView 句柄
     * @param motionVectorImageView 运动矢量纹理 VkImageView 句柄
     * @param outputImageView 输出纹理 VkImageView 句柄
     * @param textureWidth    纹理宽度
     * @param textureHeight   纹理高度
     * @param displayWidth    显示宽度
     * @param displayHeight   显示高度
     * @param cameraData      相机数据
     */
    record FrameData(
        long commandBuffer,
        long colorImageView,
        long depthImageView,
        long motionVectorImageView,
        long outputImageView,
        int textureWidth,
        int textureHeight,
        int displayWidth,
        int displayHeight,
        DLSSManager.CameraData cameraData
    ) {}
}
