// Renderium - Blaze3D 拦截层系统
// 渲染上下文类 - 封装渲染状态和资源信息

package com.ranecc.renderium.domain.model;

import java.util.Objects;

/**
 * 渲染上下文
 * <p>
 * 封装 Blaze3D 渲染管线的完整状态信息，
 * 作为 {@link PreBlaze3DInterceptor} 的输入和输出。
 * <p>
 * 包含以下关键信息：
 * <ul>
 *   <li><b>相机状态</b>：位置、朝向、视野角度</li>
 *   <li><b>渲染目标</b>：FBO/Swapchain Image 句柄</li>
 *   <li><b>模组信息</b>：已加载的第三方模组列表</li>
 *   <li><b>性能标记</b>：时间戳、帧序号</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * MixinRenderSystem
 *     │
 *     ▼
 * RenderContext（构建渲染状态）
 *     │
 *     ▼
 * PreBlaze3DInterceptor.intercept(context)  ← 前拦截入口
 *     │
 *     ├── 模组检测 + 输出处理
 *     ├── LOD 预处理注入
 *     └── 剔除优化注入
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>此类为不可变对象，线程安全。如果需要修改，
 * 应通过 {@link Builder} 创建新实例。
 *
 * @see PreBlaze3DInterceptor#intercept(RenderContext)
 * @see InterceptionResult
 * @since 5.1.0
 */
public final class RenderContext {

    // ==================== 相机状态字段 ====================

    /** 相机 X 坐标（世界空间） */
    private final float cameraX;

    /** 相机 Y 坐标（世界空间） */
    private final float cameraY;

    /** 相机 Z 坐标（世界空间） */
    private final float cameraZ;

    /** 相机俯仰角（弧度） */
    private final float pitch;

    /** 相机偏航角（弧度） */
    private final float yaw;

    /** 视野角度（度数） */
    private final float fov;

    // ==================== 渲染目标字段 ====================

    /** 当前 FBO 句柄（COMPATIBILITY 模式） */
    private final long fboHandle;

    /** 当前 Swapchain Image 句柄（AGGRESSIVE 模式） */
    private final long swapChainImage;

    /** 颜色纹理句柄 */
    private final long colorTexture;

    /** 深度纹理句柄（0 表示不可用） */
    private final long depthTexture;

    // ==================== 分辨率字段 ====================

    /** 渲染宽度（像素） */
    private final int width;

    /** 渲染高度（像素） */
    private final int height;

    // ==================== 时序字段 ====================

    /** 帧序号（单调递增） */
    private final int frameIndex;

    /** 帧间隔时间（秒） */
    private final float deltaTime;

    /** 时间戳（纳秒，System.nanoTime()） */
    private final long timestampNanos;

    // ==================== 模组信息字段 ====================

    /** 已加载的模组 ID 数组 */
    private final String[] loadedMods;

    /** 是否检测到 Sodium 模组 */
    private final boolean sodiumDetected;

    /** 是否检测到 Iris 模组 */
    private final boolean irisDetected;

    /** 是否检测到 Oculus 模组 */
    private final boolean oculusDetected;

    // ==================== 矩阵数据字段（来自 LifecycleManager） ====================

    /** 投影矩阵（16 floats，column-major，来自 op⑥） */
    private final float[] projectionMatrix;

    /** 视图矩阵（16 floats，column-major，来自 op⑩） */
    private final float[] viewMatrix;

    /** 预计算的 VP 矩阵 = view × projection */
    private final float[] viewProjectionMatrix;

    /** 逆视图矩阵（用于光照计算） */
    private final float[] invViewMatrix;

    /** 历史视图矩阵环形缓冲区（16帧，用于 TAA/MotionBlur） */
    private final float[][] previousViewMatrices;

    // ==================== 运行模式字段 ====================

    /** 当前运行模式名称（"COMPATIBILITY" / "AGGRESSIVE"） */
    private final String modeName;

    /** 空矩阵常量（当矩阵不可用时返回） */
    private static final float[] EMPTY_MATRIX = new float[16];
    static {
        EMPTY_MATRIX[0] = 1; EMPTY_MATRIX[5] = 1;
        EMPTY_MATRIX[10] = 1; EMPTY_MATRIX[15] = 1;
    }

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     * <p>
     * 通过 Builder 模式构造实例。
     *
     * @param builder 构建器实例
     */
    private RenderContext(Builder builder) {
        this.cameraX = builder.cameraX;
        this.cameraY = builder.cameraY;
        this.cameraZ = builder.cameraZ;
        this.pitch = builder.pitch;
        this.yaw = builder.yaw;
        this.fov = builder.fov;
        this.fboHandle = builder.fboHandle;
        this.swapChainImage = builder.swapChainImage;
        this.colorTexture = builder.colorTexture;
        this.depthTexture = builder.depthTexture;
        this.width = builder.width;
        this.height = builder.height;
        this.frameIndex = builder.frameIndex;
        this.deltaTime = builder.deltaTime;
        this.timestampNanos = builder.timestampNanos > 0 ? builder.timestampNanos : System.nanoTime();
        this.loadedMods = builder.loadedMods != null ? builder.loadedMods.clone() : new String[0];
        this.sodiumDetected = builder.sodiumDetected;
        this.irisDetected = builder.irisDetected;
        this.oculusDetected = builder.oculusDetected;
        this.modeName = builder.modeName != null ? builder.modeName : "COMPATIBILITY";
        this.projectionMatrix = builder.projectionMatrix != null ? builder.projectionMatrix.clone() : null;
        this.viewMatrix = builder.viewMatrix != null ? builder.viewMatrix.clone() : null;
        this.viewProjectionMatrix = builder.viewProjectionMatrix != null ? builder.viewProjectionMatrix.clone() : null;
        this.invViewMatrix = builder.invViewMatrix != null ? builder.invViewMatrix.clone() : null;
        this.previousViewMatrices = builder.previousViewMatrices != null ? deepCloneMatrixRing(builder.previousViewMatrices) : null;
    }

    /** 深拷贝 16 帧历史视图矩阵环形缓冲区 */
    private static float[][] deepCloneMatrixRing(float[][] src) {
        float[][] dst = new float[src.length][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i].clone();
        }
        return dst;
    }

    // ==================== Getter 方法：相机状态 ====================

    /**
     * 获取相机 X 坐标
     *
     * @return X 坐标（世界空间）
     */
    public float getCameraX() { return cameraX; }

    /**
     * 获取相机 Y 坐标
     *
     * @return Y 坐标（世界空间）
     */
    public float getCameraY() { return cameraY; }

    /**
     * 获取相机 Z 坐标
     *
     * @return Z 坐标（世界空间）
     */
    public float getCameraZ() { return cameraZ; }

    /**
     * 获取相机俯仰角
     *
     * @return 俯仰角（弧度）
     */
    public float getPitch() { return pitch; }

    /**
     * 获取相机偏航角
     *
     * @return 偏航角（弧度）
     */
    public float getYaw() { return yaw; }

    /**
     * 获取视野角度
     *
     * @return FOV（度数）
     */
    public float getFov() { return fov; }

    // ==================== Getter 方法：渲染目标 ====================

    /**
     * 获取 FBO 句柄
     *
     * @return FBO 句柄（COMPATIBILITY 模式使用）
     */
    public long getFboHandle() { return fboHandle; }

    /**
     * 获取 Swapchain Image 句柄
     *
     * @return Swapchain Image 句柄（AGGRESSIVE 模式使用）
     */
    public long getSwapChainImage() { return swapChainImage; }

    /**
     * 获取颜色纹理句柄
     *
     * @return 颜色纹理 VkImageView 句柄
     */
    public long getColorTexture() { return colorTexture; }

    /**
     * 获取深度纹理句柄
     *
     * @return 深度纹理 VkImageView 句柄，0 表示不可用
     */
    public long getDepthTexture() { return depthTexture; }

    /**
     * 检查深度纹理是否可用
     *
     * @return true 如果深度纹理句柄有效
     */
    public boolean hasDepthTexture() { return depthTexture != 0; }

    // ==================== Getter 方法：分辨率 ====================

    /**
     * 获取渲染宽度
     *
     * @return 宽度（像素）
     */
    public int getWidth() { return width; }

    /**
     * 获取渲染高度
     *
     * @return 高度（像素）
     */
    public int getHeight() { return height; }

    // ==================== Getter 方法：时序 ====================

    /**
     * 获取帧序号
     *
     * @return 单调递增的帧索引
     */
    public int getFrameIndex() { return frameIndex; }

    /**
     * 获取帧间隔时间
     *
     * @return 帧间隔（秒）
     */
    public float getDeltaTime() { return deltaTime; }

    /**
     * 获取时间戳
     *
     * @return 时间戳（纳秒）
     */
    public long getTimestampNanos() { return timestampNanos; }

    // ==================== Getter 方法：模组信息 ====================

    /**
     * 获取已加载的模组列表
     *
     * @return 模组 ID 数组的副本
     */
    public String[] getLoadedMods() { return loadedMods.clone(); }

    /**
     * 检查是否检测到 Sodium 模组
     *
     * @return true 如果 Sodium 已加载
     */
    public boolean isSodiumDetected() { return sodiumDetected; }

    /**
     * 检查是否检测到 Iris 模组
     *
     * @return true 如果 Iris 已加载
     */
    public boolean isIrisDetected() { return irisDetected; }

    /**
     * 检查是否检测到 Oculus 模组
     *
     * @return true 如果 Oculus 已加载
     */
    public boolean isOculusDetected() { return oculusDetected; }

    // ==================== Getter 方法：运行模式 ====================

    /**
     * 获取运行模式名称
     *
     * @return 模式名称字符串
     */
    public String getModeName() { return modeName; }

    /**
     * 检查是否为 COMPATIBILITY 模式
     *
     * @return true 如果是兼容模式
     */
    public boolean isCompatibilityMode() {
        return "COMPATIBILITY".equalsIgnoreCase(modeName);
    }

    /**
     * 检查是否为 AGGRESSIVE 模式
     *
     * @return true 如果是狂暴模式
     */
    public boolean isAggressiveMode() {
        return "AGGRESSIVE".equalsIgnoreCase(modeName);
    }

    // ==================== Getter 方法：矩阵数据 ====================

    /**
     * 获取投影矩阵
     *
     * @return 投影矩阵（16 floats，column-major），不可用时返回单位矩阵
     */
    public float[] getProjectionMatrix() {
        return projectionMatrix != null ? projectionMatrix : EMPTY_MATRIX;
    }

    /**
     * 获取视图矩阵
     *
     * @return 视图矩阵（16 floats，column-major），不可用时返回单位矩阵
     */
    public float[] getViewMatrix() {
        return viewMatrix != null ? viewMatrix : EMPTY_MATRIX;
    }

    /**
     * 获取预计算的 VP 矩阵
     *
     * @return VP 矩阵（16 floats，column-major），不可用时返回单位矩阵
     */
    public float[] getVPMatrix() {
        return viewProjectionMatrix != null ? viewProjectionMatrix : EMPTY_MATRIX;
    }

    /**
     * 获取逆视图矩阵
     *
     * @return 逆视图矩阵（16 floats，column-major），不可用时返回单位矩阵
     */
    public float[] getInvViewMatrix() {
        return invViewMatrix != null ? invViewMatrix : EMPTY_MATRIX;
    }

    /**
     * 获取历史视图矩阵环形缓冲区
     *
     * @return 16 帧历史视图矩阵数组，不可用时返回空数组
     */
    public float[][] getPreviousViewMatrices() {
        return previousViewMatrices != null ? previousViewMatrices : new float[0][];
    }

    /**
     * 检查矩阵数据是否可用
     *
     * @return true 如果投影矩阵和视图矩阵均已设置
     */
    public boolean hasMatrixData() {
        return projectionMatrix != null && viewMatrix != null;
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
            "RenderContext{camera=(%.1f,%.1f,%.1f), %dx%d, frame=%d, mode=%s, mods=[%s]}",
            cameraX, cameraY, cameraZ,
            width, height,
            frameIndex,
            modeName,
            String.join(",", loadedMods)
        );
    }

    // ==================== Builder 模式 ====================

    /**
     * RenderContext 构建器
     * <p>
     * 使用 Builder 模式提供灵活的对象构造方式，
     * 所有字段都有合理的默认值。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * RenderContext context = new RenderContext.Builder()
     *     .cameraPosition(100.0f, 64.0f, -200.0f)
     *     .cameraRotation(0.0f, 45.0f)  // pitch, yaw (度数)
     *     .fov(70.0f)
     *     .colorTexture(colorHandle)
     *     .depthTexture(depthHandle)
     *     .width(1920)
     *     .height(1080)
     *     .frameIndex(frameCount)
     *     .deltaTime(0.0167f)
     *     .modeName("COMPATIBILITY")
     *     .loadedMods(new String[]{"sodium"})
     *     .build();
     * </pre>
     */
    public static final class Builder {

        // 默认值
        private float cameraX = 0.0f;
        private float cameraY = 0.0f;
        private float cameraZ = 0.0f;
        private float pitch = 0.0f;
        private float yaw = 0.0f;
        private float fov = 70.0f;
        private long fboHandle = 0L;
        private long swapChainImage = 0L;
        private long colorTexture = 0L;
        private long depthTexture = 0L;
        private int width = 0;
        private int height = 0;
        private int frameIndex = 0;
        private float deltaTime = 0.0167f;  // 默认 ~60fps
        private long timestampNanos = 0L;
        private String[] loadedMods;
        private boolean sodiumDetected = false;
        private boolean irisDetected = false;
        private boolean oculusDetected = false;
        private String modeName = "COMPATIBILITY";

        // 矩阵数据字段（来自 LifecycleManager，可选）
        private float[] projectionMatrix;
        private float[] viewMatrix;
        private float[] viewProjectionMatrix;
        private float[] invViewMatrix;
        private float[][] previousViewMatrices;

        /**
         * 设置相机位置（世界坐标）
         *
         * @param x X 坐标
         * @param y Y 坐标
         * @param z Z 坐标
         * @return this（链式调用）
         */
        public Builder cameraPosition(float x, float y, float z) {
            this.cameraX = x;
            this.cameraY = y;
            this.cameraZ = z;
            return this;
        }

        /**
         * 设置相机旋转角度
         *
         * @param pitch 俯仰角（度数）
         * @param yaw   偏航角（度数）
         * @return this（链式调用）
         */
        public Builder cameraRotation(float pitch, float yaw) {
            this.pitch = (float) Math.toRadians(pitch);
            this.yaw = (float) Math.toRadians(yaw);
            return this;
        }

        /**
         * 设置视野角度
         *
         * @param fov FOV（度数，通常 60-120）
         * @return this（链式调用）
         */
        public Builder fov(float fov) {
            this.fov = fov;
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
         * 设置 Swapchain Image 句柄
         *
         * @param image Swapchain Image 句柄
         * @return this（链式调用）
         */
        public Builder swapChainImage(long image) {
            this.swapChainImage = image;
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
         * @param texture 深度纹理句柄（0 表示不可用）
         * @return this（链式调用）
         */
        public Builder depthTexture(long texture) {
            this.depthTexture = texture;
            return this;
        }

        /**
         * 设置分辨率
         *
         * @param width  宽度（像素，必须 > 0）
         * @param height 高度（像素，必须 > 0）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果宽度或高度 <= 0
         */
        public Builder resolution(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("分辨率必须大于 0: " + width + "x" + height);
            }
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * 设置帧序号
         *
         * @param index 帧索引（>= 0）
         * @return this（链式调用）
         */
        public Builder frameIndex(int index) {
            this.frameIndex = index;
            return this;
        }

        /**
         * 设置帧间隔时间
         *
         * @param dt 帧间隔（秒，必须 > 0）
         * @return this（链式调用）
         */
        public Builder deltaTime(float dt) {
            if (dt <= 0.0f) {
                throw new IllegalArgumentException("帧间隔必须大于 0: " + dt);
            }
            this.deltaTime = dt;
            return this;
        }

        /**
         * 设置时间戳
         *
         * @param nanos 时间戳（纳秒，0 表示使用当前时间）
         * @return this（链式调用）
         */
        public Builder timestampNanos(long nanos) {
            this.timestampNanos = nanos;
            return this;
        }

        /**
         * 设置已加载的模组列表
         *
         * @param mods 模组 ID 数组
         * @return this（链式调用）
         */
        public Builder loadedMods(String[] mods) {
            this.loadedMods = mods;
            // 自动设置模组检测标志
            if (mods != null) {
                for (String mod : mods) {
                    if ("sodium".equalsIgnoreCase(mod)) this.sodiumDetected = true;
                    else if ("iris".equalsIgnoreCase(mod)) this.irisDetected = true;
                    else if ("oculus".equalsIgnoreCase(mod)) this.oculusDetected = true;
                }
            }
            return this;
        }

        /**
         * 设置运行模式
         *
         * @param mode 模式名称（"COMPATIBILITY" 或 "AGGRESSIVE"）
         * @return this（链式调用）
         */
        public Builder modeName(String mode) {
            this.modeName = mode;
            return this;
        }

        /**
         * 设置投影矩阵（来自 LifecycleManager op⑥）
         *
         * @param matrix 16 floats，column-major
         * @return this（链式调用）
         */
        public Builder projectionMatrix(float[] matrix) {
            this.projectionMatrix = matrix;
            return this;
        }

        /**
         * 设置视图矩阵（来自 LifecycleManager op⑩）
         *
         * @param matrix 16 floats，column-major
         * @return this（链式调用）
         */
        public Builder viewMatrix(float[] matrix) {
            this.viewMatrix = matrix;
            return this;
        }

        /**
         * 设置预计算的 VP 矩阵
         *
         * @param matrix 16 floats，column-major
         * @return this（链式调用）
         */
        public Builder viewProjectionMatrix(float[] matrix) {
            this.viewProjectionMatrix = matrix;
            return this;
        }

        /**
         * 设置逆视图矩阵
         *
         * @param matrix 16 floats，column-major
         * @return this（链式调用）
         */
        public Builder invViewMatrix(float[] matrix) {
            this.invViewMatrix = matrix;
            return this;
        }

        /**
         * 设置历史视图矩阵环形缓冲区（16帧）
         *
         * @param matrices 16 个 16-float 矩阵
         * @return this（链式调用）
         */
        public Builder previousViewMatrices(float[][] matrices) {
            this.previousViewMatrices = matrices;
            return this;
        }

        /**
         * 从 MCRenderBridge.FrameDataSnapshot 批量填充矩阵数据
         * <p>
         * 便捷方法，一次性从桥接器的帧数据快照中提取所有矩阵。
         *
         * @param snapshot 帧数据快照
         * @return this（链式调用）
         */
        public Builder matrixDataFrom(com.ranecc.renderium.bridge.mc.FrameDataSnapshot snapshot) {
            if (snapshot != null) {
                this.projectionMatrix = snapshot.getProjectionMatrix();
                this.viewMatrix = snapshot.getViewMatrix();
                this.viewProjectionMatrix = snapshot.getViewProjectionMatrix();
                this.invViewMatrix = snapshot.getInvViewMatrix();
                this.previousViewMatrices = snapshot.getPreviousViewMatrices();
            }
            return this;
        }

        /**
         * 构建 RenderContext 实例
         *
         * @return 不可变的 RenderContext 实例
         * @throws IllegalStateException 如果必填字段未设置
         */
        public RenderContext build() {
            // 校验必填字段
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("分辨率是必填字段且必须大于 0");
            }
            if (frameIndex < 0) {
                throw new IllegalStateException("帧序号不能为负数");
            }

            return new RenderContext(this);
        }
    }
}
