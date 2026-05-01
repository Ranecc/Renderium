// Renderium - Blaze3D 拦截层系统
// 模组输出上下文类 - 封装模组渲染输出的信息

package com.ranecc.renderium.domain.model;

/**
 * 模组输出上下文
 * <p>
 * 封装第三方模组（如 Sodium、Iris、Oculus）的渲染输出信息，
 * 作为 {@link ModOutputHandler#handleOutput(ModOutputContext)} 的输入参数。
 *
 * <h3>包含的信息：</h3>
 * <ul>
 *   <li>FBO 句柄（COMPATIBILITY 模式）</li>
 *   <li>颜色/深度纹理句柄</li>
 *   <li>分辨率信息</li>
 *   <li>模组类型标识</li>
 * </ul>
 *
 * @see ModOutputHandler
 * @see PreBlaze3DInterceptor#registerModHandler(String, ModOutputHandler)
 * @since 5.1.0
 */
public final class ModOutputContext {

    // ==================== 字段定义 ====================

    /** 模组 ID（如 "sodium"、"iris"、"oculus"） */
    private final String modId;

    /** FBO 句柄（COMPATIBILITY 模式） */
    private final long fboHandle;

    /** 颜色纹理句柄 */
    private final long colorTexture;

    /** 深度纹理句柄（0 表示不可用） */
    private final long depthTexture;

    /** 输出宽度（像素） */
    private final int width;

    /** 输出高度（像素） */
    private final int height;

    /** 帧序号 */
    private final int frameIndex;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private ModOutputContext(Builder builder) {
        this.modId = builder.modId;
        this.fboHandle = builder.fboHandle;
        this.colorTexture = builder.colorTexture;
        this.depthTexture = builder.depthTexture;
        this.width = builder.width;
        this.height = builder.height;
        this.frameIndex = builder.frameIndex;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取模组 ID
     *
     * @return 模组标识符字符串
     */
    public String getModId() { return modId; }

    /**
     * 获取 FBO 句柄
     *
     * @return FBO 句柄
     */
    public long getFboHandle() { return fboHandle; }

    /**
     * 获取颜色纹理句柄
     *
     * @return 颜色纹理句柄
     */
    public long getColorTexture() { return colorTexture; }

    /**
     * 获取深度纹理句柄
     *
     * @return 深度纹理句柄，0 表示不可用
     */
    public long getDepthTexture() { return depthTexture; }

    /**
     * 检查深度纹理是否可用
     *
     * @return true 如果深度纹理有效
     */
    public boolean hasDepthTexture() { return depthTexture != 0; }

    /**
     * 获取输出宽度
     *
     * @return 宽度（像素）
     */
    public int getWidth() { return width; }

    /**
     * 获取输出高度
     *
     * @return 高度（像素）
     */
    public int getHeight() { return height; }

    /**
     * 获取帧序号
     *
     * @return 帧索引
     */
    public int getFrameIndex() { return frameIndex; }

    // ==================== Builder 模式 ====================

    /**
     * ModOutputContext 构建器
     */
    public static final class Builder {

        private String modId = "";
        private long fboHandle = 0L;
        private long colorTexture = 0L;
        private long depthTexture = 0L;
        private int width = 0;
        private int height = 0;
        private int frameIndex = 0;

        /**
         * 设置模组 ID
         *
         * @param modId 模组标识符
         * @return this（链式调用）
         */
        public Builder modId(String modId) {
            this.modId = modId;
            return this;
        }

        /**
         * 设置 FBO 句柄
         *
         * @param handle FBO 句柄
         * @return this（链式调用）
         */
        public Builder fboHandle(long handle) {
            this.fboHandle = handle;
            return this;
        }

        /**
         * 设置颜色纹理句柄
         *
         * @param texture 颜色纹理句柄
         * @return this（链式调用）
         */
        public Builder colorTexture(long texture) {
            this.colorTexture = texture;
            return this;
        }

        /**
         * 设置深度纹理句柄
         *
         * @param texture 深度纹理句柄
         * @return this（链式调用）
         */
        public Builder depthTexture(long texture) {
            this.depthTexture = texture;
            return this;
        }

        /**
         * 设置分辨率
         *
         * @param width  宽度
         * @param height 高度
         * @return this（链式调用）
         */
        public Builder resolution(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * 设置帧序号
         *
         * @param index 帧索引
         * @return this（链式调用）
         */
        public Builder frameIndex(int index) {
            this.frameIndex = index;
            return this;
        }

        /**
         * 构建 ModOutputContext 实例
         *
         * @return 不可变的 ModOutputContext 实例
         */
        public ModOutputContext build() {
            return new ModOutputContext(this);
        }
    }
}
