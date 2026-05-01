package com.ranecc.renderium.domain.enums;

/**
 * 云渲染状态枚举（MC 26.2 兼容层）
 * <p>
 * 替代 Minecraft 原生 CloudStatus 枚举
 * 用于选项系统中的云渲染质量设置
 */
public enum CloudStatus {
    /** 关闭云渲染 */
    OFF,
    /** 快速（低质量）云渲染 */
    FAST,
    /** 精细（高质量）云渲染 */
    FANCY;
}
