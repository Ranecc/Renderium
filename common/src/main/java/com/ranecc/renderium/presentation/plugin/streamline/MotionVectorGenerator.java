// Renderium - Blaze3D 优化器插件系统
// 运动向量生成器接口

package com.ranecc.renderium.presentation.plugin.streamline;
import com.ranecc.renderium.domain.model.FrameData;

/**
 * 运动向量 (Motion Vectors) 生成器接口
 * <p>
 * 负责为 Streamline SDK 生成像素级运动信息。
 * 运动向量是 Frame Generation 的必需输入，
 * 对 DLSS 质量也有显著提升。
 *
 * <h2>实现层次：</h2>
 * <ol>
 *   <li>{@link SimpleMotionVectorGenerator} - 基于相机运动的简化版本</li>
 *   <li>{@link FullMotionVectorGenerator} - 基于物体级别的完整版本</li>
 * </ol>
 *
 * <h2>输出格式：</h2>
 * <p>运动向量纹理应使用 RG16F 或 RG32F 格式：
 * <ul>
 *   <li>R 通道: X 方向运动（屏幕空间像素）</li>
 *   <li>G 通道: Y 方向运动（屏幕空间像素）</li>
 * </ul>
 *
 * @see StreamlineInputType#MOTION_VECTORS
 * @author Renderium Team
 * @since 1.0.0
 */
public interface MotionVectorGenerator {

    /**
     * 获取生成器名称
     *
     * @return 名称标识，如 "SimpleCamera" 或 "FullPerObject"
     */
    String getName();

    /**
     * 获取生成器描述
     *
     * @return 功能说明文本
     */
    String getDescription();

    /**
     * 初始化生成器
     * <p>在此阶段分配所需资源：
     * <ul>
     *   <li>FBO/RenderTarget</li>
     *   <li>Shader Program</li>
     *   <li>临时缓冲区</li>
     * </ul>
     *
     * @param width  输出纹理宽度
     * @param height 输出纹理高度
     * @return 初始化成功返回 true
     */
    boolean initialize(int width, int height);

    /**
     * 生成当前帧的运动向量
     *
     * @param outputTextureHandle 输出纹理句柄（写入目标）
     * @param frameData           当前帧数据（包含相机矩阵等）
     * @return 生成成功返回 true
     */
    boolean generate(long outputTextureHandle, StreamlineFrameData frameData);

    /**
     * 调整输出尺寸
     * <p>当窗口大小改变时调用。
     *
     * @param newWidth  新宽度
     * @param newHeight 新高度
     * @return 调整成功返回 true
     */
    boolean resize(int newWidth, int newHeight);

    /**
     * 释放所有资源
     */
    void dispose();

    /**
     * 获取生成统计
     *
     * @return 包含生成时间、精度等指标的统计对象
     */
    MotionVectorStats getStats();
}
