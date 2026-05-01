// Renderium - 后处理效果类型枚举
// 定义支持的所有后处理效果类型

package com.ranecc.renderium.domain.enums;

/**
 * 后处理效果类型枚举
 * <p>
 * 定义 Renderium 支持的所有后处理效果类型。
 * 每种效果对应独立的实现类和参数配置。
 * <p>
 * 效果执行顺序（由 {@link EffectPipeline} 管理）：
 * <pre>
 * 帧数据 → [BLOOM] → [DOF] → [MOTION_BLUR] → 输出
 * </pre>
 *
 * <h3>支持的特效：</h3>
 * <ul>
 *   <li><b>BLOOM</b> - 泛光效果，模拟高亮度区域的光晕散射</li>
 *   <li><b>DOF</b> - 景深效果，基于深度缓冲的焦点模糊</li>
 *   <li><b>MOTION_BLUR</b> - 运动模糊，基于运动矢量的方向性模糊</li>
 * </ul>
 *
 * @see EffectPipeline
 * @see EffectParameters
 */
public enum EffectType {

    /**
     * 泛光效果 (Bloom)
     * <p>
     * 提取场景中的高亮区域，应用高斯模糊后再叠加回原图。
     * 常用于模拟强光源的光晕、HDR 场景的色调映射等。
     * <p>
     * 所需资源：颜色纹理
     * 性能消耗：中等（依赖模糊半径和降采样次数）
     */
    BLOOM("泛光", "Bloom", 100),

    /**
     * 景深效果 (Depth of Field)
     * <p>
     * 根据深度缓冲计算像素与焦平面的距离，
     * 对焦外区域应用不同程度的模糊。
     * 模拟真实相机的光学特性。
     * <p>
     * 所需资源：颜色纹理 + 深度纹理
     * 性能消耗：较高（需要深度采样和多通道模糊）
     */
    DOF("景深", "Depth of Field", 200),

    /**
     * 运动模糊 (Motion Blur)
     * <p>
     * 使用运动矢量或屏幕空间速度缓冲，
     * 沿运动方向对像素进行历史帧混合。
     * 用于平滑快速移动时的画面，减少闪烁感。
     * <p>
     * 所需资源：颜色纹理 + 运动矢量纹理（可选）
     * 性能消耗：中等（依赖采样数量）
     */
    MOTION_BLUR("运动模糊", "Motion Blur", 300);

    // ==================== 字段 ====================

    /** 中文名称（用于 UI 显示） */
    private final String displayName;

    /** 英文名称（用于日志和调试） */
    private final String englishName;

    /**
     * 执行优先级（数值越小越先执行）
     * <p>
     * EffectPipeline 按此值排序效果链。
     * 确保正确的视觉效果顺序：
     * Bloom → DOF → MotionBlur
     */
    private final int executionOrder;

    // ==================== 构造函数 ====================

    /**
     * 枚举构造函数
     *
     * @param displayName    中文名称
     * @param englishName    英文名称
     * @param executionOrder 执行顺序（越小越先）
     */
    EffectType(String displayName, String englishName, int executionOrder) {
        this.displayName = displayName;
        this.englishName = englishName;
        this.executionOrder = executionOrder;
    }

    // ==================== 公共方法 ====================

    /**
     * 获取效果的中文名称
     *
     * @return 中文名称字符串
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取效果的英文名称
     *
     * @return 英文名称字符串
     */
    public String getEnglishName() {
        return englishName;
    }

    /**
     * 获取效果的执行顺序
     * <p>
     * 数值越小表示在管线中越早执行。
     * Bloom(100) → DOF(200) → MotionBlur(300)
     *
     * @return 执行顺序数值
     */
    public int getExecutionOrder() {
        return executionOrder;
    }

    /**
     * 检查此效果是否需要深度缓冲
     *
     * @return true 如果需要深度纹理才能正常工作
     */
    public boolean requiresDepthBuffer() {
        return this == DOF;
    }

    /**
     * 检查此效果是否需要运动矢量
     *
     * @return true 如果需要运动矢量纹理
     */
    public boolean requiresMotionVectors() {
        return this == MOTION_BLUR;
    }
}
