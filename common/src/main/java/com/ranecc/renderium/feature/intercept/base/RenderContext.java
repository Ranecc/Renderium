package com.ranecc.renderium.feature.intercept.base;
import com.ranecc.renderium.feature.intercept.base.RenderContext;

/**
 * 渲染上下文 — 拦截器系统的核心上下文对象
 *
 * <p>封装渲染过程中的所有状态信息，包括当前帧数据、渲染目标、
 * 视口配置等。在拦截链中传递，允许各阶段读取和修改渲染状态。</p>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 3.0.0
 */
public class RenderContext {

    /** 帧编号 */
    private long frameId;

    /** 时间戳（纳秒） */
    private long timestampNs;

    /** 视口宽度 */
    private int viewportWidth;

    /** 视口高度 */
    private int viewportHeight;

    /**
     * 默认构造方法
     */
    public RenderContext() {
        this.frameId = 0;
        this.timestampNs = System.nanoTime();
        this.viewportWidth = 1920;
        this.viewportHeight = 1080;
    }

    /**
     * 获取帧编号
     * @return 当前帧编号（从 0 开始递增）
     */
    public long getFrameId() {
        return frameId;
    }

    /**
     * 设置帧编号
     * @param frameId 帧编号（>= 0）
     */
    public void setFrameId(long frameId) {
        this.frameId = frameId;
    }

    /**
     * 获取时间戳
     * @return 时间戳（纳秒精度）
     */
    public long getTimestampNs() {
        return timestampNs;
    }

    /**
     * 获取视口宽度
     * @return 视口宽度（像素）
     */
    public int getViewportWidth() {
        return viewportWidth;
    }

    /**
     * 获取视口高度
     * @return 视口高度（像素）
     */
    public int getViewportHeight() {
        return viewportHeight;
    }

    @Override
    public String toString() {
        return String.format("RenderContext{frame=%d, viewport=%dx%d}", frameId, viewportWidth, viewportHeight);
    }
}
