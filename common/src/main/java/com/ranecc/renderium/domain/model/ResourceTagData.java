// Renderium - 资源标记数据结构
// 用于 DLSS/XeSS/FSR 帧生成时的资源标记

package com.ranecc.renderium.domain.model;

/**
 * 资源标记数据
 *
 * <p>用于在帧生成时标记需要被 Streamline SDK 处理的 Vulkan 资源，
 * 包括颜色纹理、深度纹理、运动向量等。
 *
 * <h2>使用场景：</h2>
 * <ul>
 *   <li>DLSS 超分辨率: 标记输入/输出纹理</li>
 *   <li>DLSS-G 帧生成: 标记前后帧资源</li>
 *   <li>Reflex 低延迟: 标记同步资源</li>
 * </ul>
 *
 * <h2>缓冲区类型：</h2>
 * <ul>
 *   <li><b>HUDLESS_COLOR</b>: 无 HUD 的颜色缓冲区</li>
 *   <li><b>DEPTH</b>: 深度缓冲区</li>
 *   <li><b>MOTION_VECTORS</b>: 运动向量缓冲区</li>
 *   <li><b>SCALING_OUTPUT_COLOR</b>: 缩放输出颜色缓冲区</li>
 *   <li><b>FRAMEGEN_OUTPUT</b>: 帧生成输出缓冲区</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see com.ranecc.renderium.streamline.ffm.SLFFMBindings
 */
public final class ResourceTagData {

    /** 缓冲区类型（Streamline SDK 定义） */
    public final int bufferType;

    /** Vulkan ImageView 句柄 */
    public final long imageView;

    /** 纹理宽度 */
    public final int width;

    /** 纹理高度 */
    public final int height;

    /**
     * 创建资源标记数据
     *
     * @param bufferType 缓冲区类型
     * @param imageView Vulkan ImageView 句柄
     * @param width 纹理宽度
     * @param height 纹理高度
     */
    public ResourceTagData(int bufferType, long imageView, int width, int height) {
        this.bufferType = bufferType;
        this.imageView = imageView;
        this.width = width;
        this.height = height;
    }

    @Override
    public String toString() {
        return "ResourceTagData{" +
               "bufferType=" + bufferType +
               ", imageView=0x" + Long.toHexString(imageView) +
               ", width=" + width +
               ", height=" + height +
               '}';
    }
}
