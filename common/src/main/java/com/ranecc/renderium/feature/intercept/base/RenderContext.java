// Renderium - 拦截器系统
// 渲染上下文 — 拦截器系统的核心上下文对象

package com.ranecc.renderium.feature.intercept.base;

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

    /** 已加载的 Mod 列表 */
    private java.util.List<String> loadedMods = new java.util.ArrayList<>();

    /** FBO 句柄 */
    private long fboHandle;

    /** 颜色纹理 */
    private long colorTexture;

    /** 深度纹理 */
    private long depthTexture;

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

    public int getFrameIndex() { return (int) frameId; }
    public int getWidth() { return viewportWidth; }
    public int getHeight() { return viewportHeight; }

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

    /** @return 已加载的 Mod 列表 */
    public java.util.List<String> getLoadedMods() {
        return loadedMods;
    }

    /** @param mods 已加载的 Mod 列表 */
    public void setLoadedMods(java.util.List<String> mods) {
        this.loadedMods = mods;
    }

    /** @return FBO 句柄 */
    public long getFboHandle() {
        return fboHandle;
    }

    /** @param handle FBO 句柄 */
    public void setFboHandle(long handle) {
        this.fboHandle = handle;
    }

    /** @return 颜色纹理 */
    public long getColorTexture() {
        return colorTexture;
    }

    /** @param tex 颜色纹理 */
    public void setColorTexture(long tex) {
        this.colorTexture = tex;
    }

    /** @return 深度纹理 */
    public long getDepthTexture() {
        return depthTexture;
    }

    /** @param tex 深度纹理 */
    public void setDepthTexture(long tex) {
        this.depthTexture = tex;
    }

    @Override
    public String toString() {
        return String.format("RenderContext{frame=%d, viewport=%dx%d}", frameId, viewportWidth, viewportHeight);
    }
}
