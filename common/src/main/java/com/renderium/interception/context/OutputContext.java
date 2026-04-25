// Renderium - Blaze3D 拦截层系统
// 输出上下文类 - 封装屏幕输出的配置参数

package com.renderium.interception.context;

/**
 * 输出上下文
 * <p>
 * 封装屏幕输出阶段所需的所有参数，
 * 传递给 {@link PostBlaze3DInterceptor#outputToScreen(OutputContext)} 方法。
 *
 * <h3>支持的输出模式：</h3>
 * <ul>
 *   <li><b>COMPATIBILITY 模式</b>：通过 OpenGL SwapBuffers 输出</li>
 *   <li><b>AGGRESSIVE 模式</b>：通过 VkQueuePresentKHR 输出</li>
 * </ul>
 *
 * @see PostBlaze3DInterceptor#outputToScreen(OutputContext)
 * @since 5.1.0
 */
public final class OutputContext {

    // ==================== 字段定义 ====================

    /** 最终输出纹理句柄 */
    private final long outputTexture;

    /** 输出目标窗口/显示器句柄 */
    private final long windowHandle;

    /** 显示区域 X 偏移（像素） */
    private final int displayX;

    /** 显示区域 Y 偏移（像素） */
    private final int displayY;

    /** 显示区域宽度（像素） */
    private final int displayWidth;

    /** 显示区域高度（像素） */
    private final int displayHeight;

    /** 是否使用垂直同步（V-Sync） */
    private final boolean vsyncEnabled;

    /** 是否为 COMPATIBILITY 模式 */
    private final boolean compatibilityMode;

    /** Swapchain 句柄（AGGRESSIVE 模式使用） */
    private final long swapchainHandle;

    /** 当前缓冲区索引（Triple Buffering 使用） */
    private final int bufferIndex;

    // ==================== 私有构造函数 ====================

    private OutputContext(Builder builder) {
        this.outputTexture = builder.outputTexture;
        this.windowHandle = builder.windowHandle;
        this.displayX = builder.displayX;
        this.displayY = builder.displayY;
        this.displayWidth = builder.displayWidth;
        this.displayHeight = builder.displayHeight;
        this.vsyncEnabled = builder.vsyncEnabled;
        this.compatibilityMode = builder.compatibilityMode;
        this.swapchainHandle = builder.swapchainHandle;
        this.bufferIndex = builder.bufferIndex;
    }

    // ==================== Getter 方法 ====================

    public long getOutputTexture() { return outputTexture; }
    public long getWindowHandle() { return windowHandle; }
    public int getDisplayX() { return displayX; }
    public int getDisplayY() { return displayY; }
    public int getDisplayWidth() { return displayWidth; }
    public int getDisplayHeight() { return displayHeight; }
    public boolean isVsyncEnabled() { return vsyncEnabled; }
    public boolean isCompatibilityMode() { return compatibilityMode; }
    public long getSwapchainHandle() { return swapchainHandle; }
    public int getBufferIndex() { return bufferIndex; }

    // ==================== Builder 模式 ====================

    public static final class Builder {
        private long outputTexture = 0L;
        private long windowHandle = 0L;
        private int displayX = 0;
        private int displayY = 0;
        private int displayWidth = 0;
        private int displayHeight = 0;
        private boolean vsyncEnabled = true;
        private boolean compatibilityMode = true;
        private long swapchainHandle = 0L;
        private int bufferIndex = 0;

        public Builder outputTexture(long texture) {
            this.outputTexture = texture;
            return this;
        }

        public Builder windowHandle(long handle) {
            this.windowHandle = handle;
            return this;
        }

        public Builder displayRegion(int x, int y, int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("显示区域尺寸必须大于 0");
            }
            this.displayX = x;
            this.displayY = y;
            this.displayWidth = width;
            this.displayHeight = height;
            return this;
        }

        public Builder vsync(boolean enabled) {
            this.vsyncEnabled = enabled;
            return this;
        }

        public Builder compatibilityMode(boolean mode) {
            this.compatibilityMode = mode;
            return this;
        }

        public Builder swapchainHandle(long handle) {
            this.swapchainHandle = handle;
            return this;
        }

        public Builder bufferIndex(int index) {
            if (index < 0 || index > 2) {
                throw new IllegalArgumentException("缓冲区索引必须在 0-2 范围内: " + index);
            }
            this.bufferIndex = index;
            return this;
        }

        public OutputContext build() {
            if (displayWidth <= 0 || displayHeight <= 0) {
                throw new IllegalStateException("显示区域尺寸是必填字段");
            }
            return new OutputContext(this);
        }
    }
}
