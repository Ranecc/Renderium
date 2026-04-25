// ============================================================
// FrameDataArena - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.memory.FrameDataArena
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (memory)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.FrameDataArena;
//     FrameDataArena arena = new FrameDataArena(1920, 1080);
//
//   新代码（推荐迁移）:
//     import com.renderium.core.memory.FrameDataArena;
//     FrameDataArena arena = new FrameDataArena(1920, 1080);
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.memory.FrameDataArena as NewFrameDataArena;

/**
 * 帧数据竞技场分配器（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有调用均委托给
 * {@link com.renderium.core.memory.FrameDataArena}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.memory.FrameDataArena}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 1.0
 * @see com.renderium.core.memory.FrameDataArena
 */
@Deprecated(since = "6.0", forRemoval = true)
public final class FrameDataArena {

    /** 委托目标实例（新位置的实现类） */
    private final NewFrameDataArena delegate;

    // ==================== 常量委托 ====================

    /** 默认最大并发帧数（委托） */
    public static final int DEFAULT_MAX_FRAMES = NewFrameDataArena.DEFAULT_MAX_FRAMES;

    /** 帧头大小（字节，委托） */
    public static final int FRAME_HEADER_SIZE = NewFrameDataArena.FRAME_HEADER_SIZE;

    /** 每像素字节数（RGBA 格式，委托） */
    public static final int BYTES_PER_PIXEL = NewFrameDataArena.BYTES_PER_PIXEL;

    /** 对齐粒度（字节，委托） */
    public static final int ALIGNMENT = NewFrameDataArena.ALIGNMENT;

    /**
     * @deprecated 使用 {@code new com.renderium.core.memory.FrameDataArena(width, height)} 替代
     */
    @Deprecated
    public FrameDataArena(int width, int height) {
        this.delegate = new NewFrameDataArena(width, height);
    }

    /**
     * @deprecated 使用 {@code new com.renderium.core.memory.FrameDataArena(width, height, maxFrames)} 替代
     */
    @Deprecated
    public FrameDataArena(int width, int height, int maxFrames) {
        this.delegate = new NewFrameDataArena(width, height, maxFrames);
    }

    // ==================== 内存管理 API 委托 ====================

    /** 分配一帧存储空间（委托） */
    @Deprecated
    public int allocateFrame() { return delegate.allocateFrame(); }

    /** 释放之前分配的帧（委托） */
    @Deprecated
    public void releaseFrame(int frameOffset) { delegate.releaseFrame(frameOffset); }

    // ==================== 数据访问 API 委托 ====================

    /** 获取帧的像素数据引用（委托） */
    @Deprecated
    public int[] getPixelData(int frameOffset) { return delegate.getPixelData(frameOffset); }

    /** 将外部像素数据复制到指定帧（委托） */
    @Deprecated
    public void setPixelData(int frameOffset, int[] pixelData) {
        delegate.setPixelData(frameOffset, pixelData);
    }

    /** 设置帧的时间戳（委托） */
    @Deprecated
    public void setTimestamp(int frameOffset, long timestamp) {
        delegate.setTimestamp(frameOffset, timestamp);
    }

    /** 获取帧的时间戳（委托） */
    @Deprecated
    public long getTimestamp(int frameOffset) { return delegate.getTimestamp(frameOffset); }

    /** 设置帧的质量分数（委托） */
    @Deprecated
    public void setQualityScore(int frameOffset, float qualityScore) {
        delegate.setQualityScore(frameOffset, qualityScore);
    }

    /** 获取帧的质量分数（委托） */
    @Deprecated
    public float getQualityScore(int frameOffset) { return delegate.getQualityScore(frameOffset); }

    // ==================== 诊断和状态查询 API 委托 ====================

    /** 获取当前已分配帧数（委托） */
    @Deprecated
    public int getAllocatedCount() { return delegate.getAllocatedCount(); }

    /** 获取最大帧数（容量，委托） */
    @Deprecated
    public int getMaxFrames() { return delegate.getMaxFrames(); }

    /** 获取单帧大小（字节，委托） */
    @Deprecated
    public int getSingleFrameSize() { return delegate.getSingleFrameSize(); }

    /** 获取 Arena 总大小（字节，委托） */
    @Deprecated
    public int getTotalArenaSize() { return delegate.getTotalArenaSize(); }

    /** 获取内存利用率（委托） */
    @Deprecated
    public double getMemoryUtilization() { return delegate.getMemoryUtilization(); }

    /** 获取详细的状态报告（委托） */
    @Deprecated
    public String getStatusReport() { return delegate.getStatusReport(); }
}
