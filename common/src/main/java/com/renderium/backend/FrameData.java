// Renderium - 帧数据封装
// 从 Blaze3D 捕获的渲染帧数据，用于后处理管线

package com.renderium.backend;

/**
 * 帧数据容器
 * <p>
 * 封装从 Blaze3D CommandEncoder 捕获的完整帧数据，
 * 作为 {@link BackendInterceptor} 的输入参数。
 * <p>
 * 包含后处理所需的所有资源句柄和元数据：
 * <ul>
 *   <li>颜色缓冲（必需）- 渲染后的场景画面</li>
 *   <li>深度缓冲（可选）- 用于 DOF 等效果</li>
 *   <li>分辨率信息</li>
 *   <li>时序信息（帧序号、帧间隔）</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 在 MixinRenderSystem.flipFrame() 中构建
 * FrameData frameData = new FrameData.Builder()
 *     .colorTexture(colorImageViewHandle)
 *     .depthTexture(depthImageViewHandle)
 *     .width(displayWidth)
 *     .height(displayHeight)
 *     .frameIndex(currentFrame)
 *     .deltaTime(frameTime)
 *     .build();
 *
 * // 提交给 BackendInterceptor
 * boolean intercepted = BackendInterceptor.getInstance()
 *     .interceptFrameSubmit(frameData, currentMode);
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>此类为不可变对象，线程安全。
 *
 * @see BackendInterceptor#interceptFrameSubmit(FrameData, com.renderium.core.RenderiumMode)
 */
public final class FrameData {

    // ==================== 资源句柄 ====================

    /** 颜色缓冲 VkImageView 句柄（必需） */
    private final long colorTexture;

    /**
     * 深度缓冲 VkImageView 句柄（可选）
     * <p>
     * 当值为 0 时表示深度缓冲不可用，
     * 后处理管线应跳过需要深度的效果（如 DOF）。
     */
    private final long depthTexture;

    // ==================== 分辨率信息 ====================

    /** 帧宽度（像素） */
    private final int width;

    /** 帧高度（像素） */
    private final int height;

    // ==================== 时序信息 ====================

    /** 帧序号（单调递增） */
    private final int frameIndex;

    /**
     * 帧间隔时间（秒）
     * <p>
     * 用于计算运动矢量、 Reflex 低延迟等。
     * 典型值：60fps 时约为 0.0167s
     */
    private final float deltaTime;

    // ==================== 构造函数（私有，使用 Builder） ====================

    /**
     * 私有构造函数
     *
     * @param colorTexture 颜色纹理句柄
     * @param depthTexture 深度纹理句柄
     * @param width        宽度
     * @param height       高度
     * @param frameIndex   帧索引
     * @param deltaTime    帧间隔
     */
    private FrameData(long colorTexture, long depthTexture,
                      int width, int height,
                      int frameIndex, float deltaTime) {
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.width = width;
        this.height = height;
        this.frameIndex = frameIndex;
        this.deltaTime = deltaTime;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取颜色缓冲 VkImageView 句柄
     *
     * @return 颜色纹理句柄（非零值）
     */
    public long getColorTexture() {
        return colorTexture;
    }

    /**
     * 获取深度缓冲 VkImageView 句柄
     *
     * @return 深度纹理句柄，0 表示不可用
     */
    public long getDepthTexture() {
        return depthTexture;
    }

    /**
     * 获取帧宽度
     *
     * @return 宽度（像素），大于 0
     */
    public int getWidth() {
        return width;
    }

    /**
     * 获取帧高度
     *
     * @return 高度（像素），大于 0
     */
    public int getHeight() {
        return height;
    }

    /**
     * 获取帧序号
     *
     * @return 单调递增的帧索引
     */
    public int getFrameIndex() {
        return frameIndex;
    }

    /**
     * 获取帧间隔时间
     *
     * @return 帧间隔（秒），正值
     */
    public float getDeltaTime() {
        return deltaTime;
    }

    /**
     * 检查深度缓冲是否可用
     *
     * @return true 如果深度缓冲句柄有效
     */
    public boolean hasDepthTexture() {
        return depthTexture != 0;
    }

    // ==================== Builder 模式 ====================

    /**
     * FrameData 构建器
     * <p>
     * 使用 Builder 模式提供灵活的对象构造方式，
     * 所有字段都有合理的默认值或必填校验。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * FrameData data = new FrameData.Builder()
     *     .colorTexture(0x12345678L)
     *     .width(1920)
     *     .height(1080)
     *     .frameIndex(42)
     *     .deltaTime(0.0167f)
     *     .build();
     * </pre>
     */
    public static final class Builder {

        // 必填字段（使用特殊标记表示未设置）
        private long colorTexture = 0;
        private long depthTexture = 0;
        private int width = 0;
        private int height = 0;
        private int frameIndex = 0;
        private float deltaTime = 0.0f;

        /**
         * 设置颜色缓冲纹理句柄（必填）
         *
         * @param colorTexture VkImageView 句柄（非零）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果句柄为 0
         */
        public Builder colorTexture(long colorTexture) {
            if (colorTexture == 0) {
                throw new IllegalArgumentException("颜色纹理句柄不能为 0");
            }
            this.colorTexture = colorTexture;
            return this;
        }

        /**
         * 设置深度缓冲纹理句柄（可选）
         *
         * @param depthTexture VkImageView 句柄（0 表示不可用）
         * @return this（链式调用）
         */
        public Builder depthTexture(long depthTexture) {
            this.depthTexture = depthTexture;
            return this;
        }

        /**
         * 设置帧宽度（必填）
         *
         * @param width 宽度（像素，必须大于 0）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果宽度 <= 0
         */
        public Builder width(int width) {
            if (width <= 0) {
                throw new IllegalArgumentException("宽度必须大于 0，当前值: " + width);
            }
            this.width = width;
            return this;
        }

        /**
         * 设置帧高度（必填）
         *
         * @param height 高度（像素，必须大于 0）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果高度 <= 0
         */
        public Builder height(int height) {
            if (height <= 0) {
                throw new IllegalArgumentException("高度必须大于 0，当前值: " + height);
            }
            this.height = height;
            return this;
        }

        /**
         * 设置帧序号（必填）
         *
         * @param frameIndex 帧索引（通常 >= 0）
         * @return this（链式调用）
         */
        public Builder frameIndex(int frameIndex) {
            this.frameIndex = frameIndex;
            return this;
        }

        /**
         * 设置帧间隔时间（必填）
         *
         * @param deltaTime 帧间隔（秒，必须 > 0）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果 deltaTime <= 0
         */
        public Builder deltaTime(float deltaTime) {
            if (deltaTime <= 0.0f) {
                throw new IllegalArgumentException("帧间隔必须大于 0，当前值: " + deltaTime);
            }
            this.deltaTime = deltaTime;
            return this;
        }

        /**
         * 构建 FrameData 对象
         *
         * @return 不可变的 FrameData 实例
         * @throws IllegalStateException 如果必填字段未设置
         */
        public FrameData build() {
            // 校验必填字段
            if (colorTexture == 0) {
                throw new IllegalStateException("colorTexture 是必填字段");
            }
            if (width <= 0) {
                throw new IllegalStateException("width 是必填字段且必须大于 0");
            }
            if (height <= 0) {
                throw new IllegalStateException("height 是必填字段且必须大于 0");
            }
            if (deltaTime <= 0.0f) {
                throw new IllegalStateException("deltaTime 是必填字段且必须大于 0");
            }

            return new FrameData(
                colorTexture, depthTexture,
                width, height,
                frameIndex, deltaTime
            );
        }

        /**
         * 重置 Builder 到默认状态，用于复用
         * <p>
         * 配合 {@code ThreadLocal<FrameData.Builder>} 使用，消除每帧的 Builder 对象分配。
         * 重置后需重新设置所有必填字段再调用 {@link #build()}。
         *
         * @return this（链式调用）
         */
        public Builder reset() {
            this.colorTexture = 0;
            this.depthTexture = 0;
            this.width = 0;
            this.height = 0;
            this.frameIndex = 0;
            this.deltaTime = 0.0f;
            return this;
        }
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
            "FrameData{color=0x%X, depth=%s, %dx%d, frame=%d, dt=%.4fms}",
            colorTexture,
            depthTexture != 0 ? String.format("0x%X", depthTexture) : "N/A",
            width, height,
            frameIndex,
            deltaTime * 1000.0f
        );
    }
}
