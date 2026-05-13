package com.ranecc.renderium.infrastructure.config.structure;

/**
 * TODO [REVIEW] 桩枚举 - 选项性能影响级别
 * 描述修改某个渲染选项对性能的影响程度
 */
public enum OptionImpact {
    /** 低影响 - 几乎不影响帧率 */
    LOW,
    /** 中等影响 - 可能导致轻微帧率波动 */
    MEDIUM,
    /** 高影响 - 明显影响帧率 */
    HIGH,
    /** 极高影响 - 严重影响帧率，需谨慎调整 */
    VERY_HIGH
}
