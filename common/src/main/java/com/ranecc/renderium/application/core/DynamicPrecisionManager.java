package com.ranecc.renderium.application.core;

/**
 * 动态精度管理器 — Phase 2 占位实现
 * 
 * 管理不同计算路径（全局/热路径/Tile统计等）的浮点精度配置。
 * 支持运行时精度切换，用于 Kahan 累加器数值稳定性控制。
 */
public final class DynamicPrecisionManager {
    
    /** 精度等级枚举 */
    public enum PrecisionLevel {
        /** 跳过计算（性能调试用） */
        SKIP,
        /** 8-bit 整数 — 极速模式，最低精度 */
        INT8_FAST,
        /** 16-bit 半精度 (FP16) — Tensor Core 加速，平衡质量与速度 */
        FP16_MEDIUM,
        /** 32-bit 单精度 (FP32) — 标准浮点精度，无损累加 */
        FP32_FULL,
        /** Kahan 累加器精度 — 最高数值稳定性，用于关键累积运算 */
        KAHAN_PRECISE
    }
    
    /** 默认目标帧时间（毫秒） */
    public static final float DEFAULT_TARGET_FRAME_TIME_MS = 1.0f;
    
    /** 默认自动调整间隔（帧数） */
    public static final int DEFAULT_ADJUSTMENT_INTERVAL_FRAMES = 60;
    
    private static final DynamicPrecisionManager INSTANCE = new DynamicPrecisionManager();
    
    private PrecisionLevel globalPrecision = PrecisionLevel.FP16_MEDIUM;
    private PrecisionLevel hotPathPrecision = PrecisionLevel.INT8_FAST;
    private PrecisionLevel tileStatsPrecision = PrecisionLevel.FP16_MEDIUM;
    private PrecisionLevel frameAccumulationPrecision = PrecisionLevel.FP32_FULL;
    private PrecisionLevel offlineAnalysisPrecision = PrecisionLevel.KAHAN_PRECISE;
    
    /**
     * 获取单例实例
     * @return DynamicPrecisionManager 全局唯一实例
     */
    public static DynamicPrecisionManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取全局精度等级
     * @return 当前全局精度配置
     */
    public PrecisionLevel getGlobalPrecision() { 
        return globalPrecision; 
    }
    
    /**
     * 获取热路径精度等级
     * @return 热路径计算使用的精度（每像素操作）
     */
    public PrecisionLevel getHotPathPrecision() { 
        return hotPathPrecision; 
    }
    
    /**
     * 获取 Tile 统计精度等级
     * @return Tile 统计累积使用的精度（Tile级统计）
     */
    public PrecisionLevel getTileStatsPrecision() { 
        return tileStatsPrecision; 
    }
    
    /**
     * 获取帧累积精度等级
     * @return 帧间数据累积使用的精度（帧级累加）
     */
    public PrecisionLevel getFrameAccumulationPrecision() { 
        return frameAccumulationPrecision; 
    }
    
    /**
     * 获取离线分析精度等级
     * @return 离线分析场景使用的精度
     */
    public PrecisionLevel getOfflineAnalysisPrecision() { 
        return offlineAnalysisPrecision; 
    }

    /**
     * 设置全局精度等级
     * @param precision 目标精度等级
     */
    public void setGlobalPrecision(PrecisionLevel precision) {
        this.globalPrecision = precision;
    }

    /**
     * 设置热路径精度等级
     * @param precision 目标精度等级
     */
    public void setHotPathPrecision(PrecisionLevel precision) {
        this.hotPathPrecision = precision;
    }

    /**
     * 设置 Tile 统计精度等级
     * @param precision 目标精度等级
     */
    public void setTileStatsPrecision(PrecisionLevel precision) {
        this.tileStatsPrecision = precision;
    }

    /**
     * 设置帧累积精度等级
     * @param precision 目标精度等级
     */
    public void setFrameAccumulationPrecision(PrecisionLevel precision) {
        this.frameAccumulationPrecision = precision;
    }

    /**
     * 设置离线分析精度等级
     * @param precision 目标精度等级
     */
    public void setOfflineAnalysisPrecision(PrecisionLevel precision) {
        this.offlineAnalysisPrecision = precision;
    }

    /**
     * 根据帧时间自动调整精度等级
     *
     * <p>当帧时间超出目标帧时间时降级精度以保证性能，
     * 当帧时间低于目标帧时间时恢复默认精度以提升质量。
     *
     * @param frameTimeMs 当前帧时间（毫秒）
     */
    public void adjustForFrameTime(float frameTimeMs) {
        if (frameTimeMs > DEFAULT_TARGET_FRAME_TIME_MS * 3) {
            this.hotPathPrecision = PrecisionLevel.SKIP;
            this.globalPrecision = PrecisionLevel.INT8_FAST;
        } else if (frameTimeMs > DEFAULT_TARGET_FRAME_TIME_MS * 2) {
            this.hotPathPrecision = PrecisionLevel.INT8_FAST;
            this.globalPrecision = PrecisionLevel.FP16_MEDIUM;
        } else if (frameTimeMs > DEFAULT_TARGET_FRAME_TIME_MS) {
            this.hotPathPrecision = PrecisionLevel.FP16_MEDIUM;
            this.globalPrecision = PrecisionLevel.FP32_FULL;
        } else {
            this.hotPathPrecision = PrecisionLevel.INT8_FAST;
            this.globalPrecision = PrecisionLevel.FP16_MEDIUM;
        }
    }
}
