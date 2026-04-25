// Renderium - Blaze3D 拦截层系统
// 帧捕获上下文类 - 封装帧捕获的配置参数

package com.renderium.interception.context;

/**
 * 帧捕获上下文
 * <p>
 * 封装帧捕获阶段所需的所有参数，
 * 传递给 {@link PostBlaze3DInterceptor#captureFrame(FrameCaptureContext)} 方法。
 *
 * <h3>支持的捕获模式：</h3>
 * <ul>
 *   <li><b>FBO 模式</b>（COMPATIBILITY）：从 OpenGL FBO 读取像素数据</li>
 *   <li><b>Swapchain Image 模式</b>（AGGRESSIVE）：直接访问 Vulkan Swapchain Image</li>
 * </ul>
 *
 * @see PostBlaze3DInterceptor#captureFrame(FrameCaptureContext)
 * @since 5.1.0
 */
public final class FrameCaptureContext {

    // ==================== 字段定义 ====================

    /** FBO 句柄（COMPATIBILITY 模式） */
    private final long fboHandle;

    /** Swapchain Image 句柄（AGGRESSIVE 模式） */
    private final long swapChainImage;

    /** 颜色纹理句柄 */
    private final long colorTexture;

    /** 深度纹理句柄（0 表示不可用） */
    private final long depthTexture;

    /** 输出宽度（像素） */
    private final int width;

    /** 输出高度（像素） */
    private final int height;

    /** 是否为 COMPATIBILITY 模式 */
    private final boolean compatibilityMode;

    /** 帧序号 */
    private final int frameIndex;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private FrameCaptureContext(Builder builder) {
        this.fboHandle = builder.fboHandle;
        this.swapChainImage = builder.swapChainImage;
        this.colorTexture = builder.colorTexture;
        this.depthTexture = builder.depthTexture;
        this.width = builder.width;
        this.height = builder.height;
        this.compatibilityMode = builder.compatibilityMode;
        this.frameIndex = builder.frameIndex;
    }

    // ==================== Getter 方法 ====================

    public long getFboHandle() { return fboHandle; }
    public long getSwapChainImage() { return swapChainImage; }
    public long getColorTexture() { return colorTexture; }
    public long getDepthTexture() { return depthTexture; }
    public boolean hasDepthTexture() { return depthTexture != 0; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isCompatibilityMode() { return compatibilityMode; }
    public int getFrameIndex() { return frameIndex; }

    // ==================== Builder 模式 ====================

    public static final class Builder {
        private long fboHandle = 0L;
        private long swapChainImage = 0L;
        private long colorTexture = 0L;
        private long depthTexture = 0L;
        private int width = 0;
        private int height = 0;
        private boolean compatibilityMode = true;
        private int frameIndex = 0;

        /**
         * 重置 Builder 到默认状态
         * <p>
         * 用于对象池复用场景，避免每帧创建新的 Builder 实例。
         * 所有字段恢复为初始默认值。
         *
         * @return this（链式调用）
         */
        public Builder reset() {
            this.fboHandle = 0L;
            this.swapChainImage = 0L;
            this.colorTexture = 0L;
            this.depthTexture = 0L;
            this.width = 0;
            this.height = 0;
            this.compatibilityMode = true;
            this.frameIndex = 0;
            return this;
        }

        public Builder fboHandle(long handle) {
            this.fboHandle = handle;
            return this;
        }

        public Builder swapChainImage(long image) {
            this.swapChainImage = image;
            return this;
        }

        public Builder colorTexture(long texture) {
            this.colorTexture = texture;
            return this;
        }

        public Builder depthTexture(long texture) {
            this.depthTexture = texture;
            return this;
        }

        public Builder resolution(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("分辨率必须大于 0");
            }
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder compatibilityMode(boolean mode) {
            this.compatibilityMode = mode;
            return this;
        }

        public Builder frameIndex(int index) {
            this.frameIndex = index;
            return this;
        }

        public FrameCaptureContext build() {
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("分辨率是必填字段");
            }
            return new FrameCaptureContext(this);
        }
    }
}
