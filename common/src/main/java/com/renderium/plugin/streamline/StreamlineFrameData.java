// Renderium - Blaze3D 优化器插件系统
// Streamline 帧数据容器

package com.renderium.plugin.streamline;

/**
 * Streamline 帧数据
 * <p>
 * 封装单次 {@link StreamlineIntegrationPoint#evaluateFrame(StreamlineFrameData)}
 * 调用所需的所有输入数据。
 *
 * <h2>包含内容：</h2>
 * <ul>
 *   <li>各种纹理句柄（Vulkan Image / OpenGL Texture ID）</li>
 *   <li>相机变换矩阵</li>
 *   <li>时间戳和帧索引</li>
 *   <li>视口尺寸</li>
 * </ul>
 *
 * <h2>线程安全：</h2>
 * 此对象应在单线程（渲染线程）中使用。
 * 多线程访问可能导致竞态条件。
 *
 * @see StreamlineIntegrationPoint
 * @author Renderium Team
 * @since 1.0.0
 */
public final class StreamlineFrameData {

    // ==================== 纹理句柄 ====================

    /** 颜色缓冲句柄（Vulkan Image 或 GL Texture ID） */
    private long colorTextureHandle = 0L;

    /** 深度缓冲句柄 */
    private long depthTextureHandle = 0L;

    /** 运动向量缓冲句柄 */
    private long motionVectorTextureHandle = 0L;

    /** 历史帧句柄（Frame Generation 使用） */
    private long historyTextureHandle = 0L;

    // ==================== 相机数据 ====================

    /** 视图矩阵（float[16]，列主序） */
    private float[] viewMatrix;

    /** 投影矩阵（float[16]，列主序） */
    private float[] projectionMatrix;

    /** 上一帧视图矩阵（用于运动向量计算） */
    private float[] previousViewMatrix;

    // ==================== 帧元数据 ====================

    /** 当前帧索引（递增） */
    private int frameIndex = 0;

    /** 帧时间戳（毫秒） */
    private long timestampMs = System.currentTimeMillis();

    /** Delta 时间（秒） */
    private float deltaTime = 0f;

    // ==================== 视口信息 ====================

    /** 渲染目标宽度 */
    private int renderWidth = 0;

    /** 渲染目标高度 */
    private int renderHeight = 0;

    /** 显示宽度（可能不同于渲染宽度，用于超分辨率） */
    private int displayWidth = 0;

    /** 显示高度 */
    private int displayHeight = 0;

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数
     */
    public StreamlineFrameData() {}

    // ==================== Setter 方法（支持链式调用） ====================

    /**
     * 设置颜色纹理句柄
     *
     * @param handle Vulkan Image handle 或 OpenGL Texture ID
     * @return this，支持链式调用
     */
    public StreamlineFrameData setColorTexture(long handle) {
        this.colorTextureHandle = handle;
        return this;
    }

    /**
     * 设置深度纹理句柄
     *
     * @param handle 深度缓冲句柄
     * @return this
     */
    public StreamlineFrameData setDepthTexture(long handle) {
        this.depthTextureHandle = handle;
        return this;
    }

    /**
     * 设置运动向量纹理句柄
     *
     * @param handle 运动向量缓冲句柄
     * @return this
     */
    public StreamlineFrameData setMotionVectorTexture(long handle) {
        this.motionVectorTextureHandle = handle;
        return this;
    }

    /**
     * 设置历史帧纹理句柄
     *
     * @param handle 历史帧句柄
     * @return this
     */
    public StreamlineFrameData setHistoryTexture(long handle) {
        this.historyTextureHandle = handle;
        return this;
    }

    /**
     * 设置视图矩阵
     *
     * @param matrix float[16] 列主序矩阵
     * @return this
     */
    public StreamlineFrameData setViewMatrix(float[] matrix) {
        this.viewMatrix = matrix;
        return this;
    }

    /**
     * 设置投影矩阵
     *
     * @param matrix float[16] 列主序矩阵
     * @return this
     */
    public StreamlineFrameData setProjectionMatrix(float[] matrix) {
        this.projectionMatrix = matrix;
        return this;
    }

    /**
     * 设置上一帧视图矩阵
     *
     * @param matrix float[16] 列主序矩阵
     * @return this
     */
    public StreamlineFrameData setPreviousViewMatrix(float[] matrix) {
        this.previousViewMatrix = matrix;
        return this;
    }

    /**
     * 设置帧索引
     *
     * @param index 帧编号
     * @return this
     */
    public StreamlineFrameData setFrameIndex(int index) {
        this.frameIndex = index;
        return this;
    }

    /**
     * 设置 Delta 时间
     *
     * @param dt 帧间隔（秒）
     * @return this
     */
    public StreamlineFrameData setDeltaTime(float dt) {
        this.deltaTime = dt;
        return this;
    }

    /**
     * 设置渲染目标尺寸
     *
     * @param width  渲染宽度
     * @param height 渲染高度
     * @return this
     */
    public StreamlineFrameData setRenderSize(int width, int height) {
        this.renderWidth = width;
        this.renderHeight = height;
        return this;
    }

    /**
     * 设置显示尺寸
     *
     * @param width  显示宽度
     * @param height 显示高度
     * @return this
     */
    public StreamlineFrameData setDisplaySize(int width, int height) {
        this.displayWidth = width;
        this.displayHeight = height;
        return this;
    }

    // ==================== Getter 方法 ====================

    /** 获取颜色纹理句柄 */
    public long getColorTextureHandle() { return colorTextureHandle; }

    /** 获取深度纹理句柄 */
    public long getDepthTextureHandle() { return depthTextureHandle; }

    /** 获取运动向量纹理句柄 */
    public long getMotionVectorTextureHandle() { return motionVectorTextureHandle; }

    /** 获取历史帧纹理句柄 */
    public long getHistoryTextureHandle() { return historyTextureHandle; }

    /** 获取视图矩阵 */
    public float[] getViewMatrix() { return viewMatrix; }

    /** 获取投影矩阵 */
    public float[] getProjectionMatrix() { return projectionMatrix; }

    /** 获取上一帧视图矩阵 */
    public float[] getPreviousViewMatrix() { return previousViewMatrix; }

    /** 获取帧索引 */
    public int getFrameIndex() { return frameIndex; }

    /** 获取时间戳 */
    public long getTimestampMs() { return timestampMs; }

    /** 获取 Delta 时间 */
    public float getDeltaTime() { return deltaTime; }

    /** 获取渲染宽度 */
    public int getRenderWidth() { return renderWidth; }

    /** 获取渲染高度 */
    public int getRenderHeight() { return renderHeight; }

    /** 获取显示宽度 */
    public int getDisplayWidth() { return displayWidth; }

    /** 获取显示高度 */
    public int getDisplayHeight() { return displayHeight; }

    // ==================== 验证方法 ====================

    /**
     * 验证必需字段是否已设置
     *
     * @param requiredTypes 需要检查的输入类型
     * @return 如果所有必需字段都已设置返回 true
     */
    public boolean validate(StreamlineInputType... requiredTypes) {
        for (StreamlineInputType type : requiredTypes) {
            if (!isInputSet(type)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查指定输入是否已设置
     *
     * @param type 输入类型
     * @return 如果已设置且非零返回 true
     */
    public boolean isInputSet(StreamlineInputType type) {
        return switch (type) {
            case COLOR -> colorTextureHandle != 0L;
            case DEPTH -> depthTextureHandle != 0L;
            case MOTION_VECTORS -> motionVectorTextureHandle != 0L;
            case HISTORY -> historyTextureHandle != 0L;
            default -> true; // 可选输入默认通过
        };
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format(
                "StreamlineFrameData{frame=%d, render=%dx%d, display=%dx%d, dt=%.3f}",
                frameIndex, renderWidth, renderHeight, displayWidth, displayHeight, deltaTime
        );
    }
}
