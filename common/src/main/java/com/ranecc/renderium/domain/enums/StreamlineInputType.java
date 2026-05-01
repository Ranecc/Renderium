// Renderium - Blaze3D 优化器插件系统
// Streamline 输入类型枚举

package com.ranecc.renderium.domain.enums;

/**
 * Streamline 输入缓冲区类型
 * <p>
 * 定义 Streamline SDK 所需的各种输入资源类型。
 * 不同功能需要不同的输入组合。
 *
 * <h2>输入需求矩阵：</h2>
 * <table border="1">
 *   <tr><th>功能</th><th>COLOR</th><th>DEPTH</th><th>MOTION</th><th>HISTORY</th></tr>
 *   <tr><td>DLSS/FSR/XeSS</td><td>✓</td><td>✓</td><td>可选</td><td>-</td></tr>
 *   <tr><td>Frame Generation</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>NIS (Image Scaling)</td><td>✓</td><td>-</td><td>-</td><td>-</td></tr>
 * </table>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public enum StreamlineInputType {

    /**
     * 颜色缓冲 (Color Buffer)
     * <p>当前帧的颜色输出，通常是 HDR 或 SDR 格式。
     * 所有超分辨率技术都需要此输入。
     */
    COLOR("Color Buffer", "当前帧颜色输出"),

    /**
     * 深度缓冲 (Depth Buffer)
     * <p>场景的深度信息，用于边缘检测和重建质量提升。
     * DLSS 和 Frame Generation 强依赖此输入。
     */
    DEPTH("Depth Buffer", "场景深度信息"),

    /**
     * 运动向量 (Motion Vectors)
     * <p>像素级别的运动信息，用于帧间插值和运动补偿。
     * Frame Generation 必需，DLSS 可选但强烈建议提供。
     */
    MOTION_VECTORS("Motion Vectors", "像素级运动信息"),

    /**
     * 历史帧 (History Frame)
     * <p>前一帧或多帧的颜色数据，用于时间稳定性。
     * 仅 Frame Generation 需要。
     */
    HISTORY("History Frame", "历史帧数据"),

    /**
     * 曝光数据 (Exposure Data)
     * <p>当前场景的曝光参数，用于色调映射一致性。
     * 可选输入，有助于提高质量。
     */
    EXPOSURE("Exposure Data", "场景曝光参数"),

    /**
     * 反照率 (Albedo)
     * <p>表面的基础颜色信息，用于高级重建算法。
     * 可选输入，实验性功能。
     */
    ALBEDO("Albedo", "表面反照率");

    // ==================== 字段定义 ====================

    /** 显示名称 */
    private final String displayName;

    /** 描述文本 */
    private final String description;

    // ==================== 构造函数 ====================

    StreamlineInputType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取显示名称
     *
     * @return 名称字符串
     */
    public String getDisplayName() { return displayName; }

    /**
     * 获取描述
     *
     * @return 描述文本
     */
    public String getDescription() { return description; }

    /**
     * 检查是否为必需输入（非可选）
     *
     * @return true 如果是核心必需输入
     */
    public boolean isRequired() {
        return this == COLOR || this == DEPTH || this == MOTION_VECTORS;
    }
}
