// Renderium - Frame Generation Mode Enum
// 帧生成模式定义

package com.ranecc.renderium.tech.framegen.framegen;

/**
 * 帧生成模式枚举
 * <p>
 * 定义帧生成的倍率。
 * 帧生成技术通过在两个渲染帧之间插入 AI 生成的帧来提高帧率：
 * <ul>
 *   <li>2X: 每个渲染帧后生成 1 个插值帧</li>
 *   <li>4X: 每个渲染帧后生成 3 个插值帧（DLSS 4）</li>
 *   <li>6X: 每个渲染帧后生成 5 个插值帧（DLSS 4.5）</li>
 * </ul>
 *
 * @author Renderium Team
 */
public enum FrameGenMode {
    /** 关闭帧生成 */
    OFF(1),
    /** 固定 2X（1 渲染帧 + 1 插值帧） */
    FIXED_2X(2),
    /** 固定 4X（1 渲染帧 + 3 插值帧，DLSS 4） */
    FIXED_4X(4),
    /** 固定 6X（1 渲染帧 + 5 插值帧，DLSS 4.5） */
    FIXED_6X(6),
    /** 动态模式（DLSS 4.5 Dynamic Multi FG，自动调整） */
    DYNAMIC(-1),
    /** DLSS 帧生成模式 */
    DLSS(2),
    /** FSR 帧生成模式 */
    FSR(2);

    /**
     * 帧生成倍率（-1 表示动态）
     */
    public final int multiplier;

    FrameGenMode(int multiplier) {
        this.multiplier = multiplier;
    }
}
