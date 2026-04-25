// Renderium - Blaze3D 拦截层系统
// 帧生成上下文类 - 封帧生成的配置参数

package com.renderium.interception.context;

/**
 * 帧生成上下文
 * <p>
 * 封装帧生成处理阶段所需的所有参数，
 * 传递给 {@link PostBlaze3DInterceptor#applyFrameGeneration(FrameGenContext)} 方法。
 *
 * <h3>支持的技术：</h3>
 * <ul>
 *   <li><b>DLSS-FG</b> - NVIDIA DLSS Frame Generation</li>
 *   <li><b>FSR-FG</b> - AMD FSR Frame Generation</li>
 * </ul>
 *
 * @see PostBlaze3DInterceptor#applyFrameGeneration(FrameGenContext)
 * @since 5.1.0
 */
public final class FrameGenContext {

    // ==================== 字段定义 ====================

    /** 当前帧颜色纹理 */
    private final long currentColorTexture;

    /** 当前帧深度纹理 */
    private final long currentDepthTexture;

    /** 上一帧颜色纹理（用于运动估计） */
    private final long previousColorTexture;

    /** 运动矢量纹理（可选，如果已有预计算的运动矢量） */
    private final long motionVectorTexture;

    /** 相机数据 JSON（可选，包含位置、旋转等） */
    private final String cameraDataJson;

    /** 渲染宽度（像素） */
    private final int width;

    /** 渲染高度（像素） */
    private final int height;

    /** 帧生成倍率 */
    private final FrameGenMultiplier multiplier;

    /** 是否启用 Reflex 低延迟同步 */
    private final boolean reflexSyncEnabled;

    // ==================== 帧生成倍率枚举 ====================

    /**
     * 帧生成倍率
     */
    public enum FrameGenMultiplier {
        /** 不生成额外帧 */
        OFF,

        /** 2x 帧生成（每真实帧插入 1 个插值帧） */
        X2,

        /** 3x 帧生成（每真实帧插入 2 个插值帧） */
        X3
    }

    // ==================== 私有构造函数 ====================

    private FrameGenContext(Builder builder) {
        this.currentColorTexture = builder.currentColorTexture;
        this.currentDepthTexture = builder.currentDepthTexture;
        this.previousColorTexture = builder.previousColorTexture;
        this.motionVectorTexture = builder.motionVectorTexture;
        this.cameraDataJson = builder.cameraDataJson;
        this.width = builder.width;
        this.height = builder.height;
        this.multiplier = builder.multiplier != null ? builder.multiplier : FrameGenMultiplier.OFF;
        this.reflexSyncEnabled = builder.reflexSyncEnabled;
    }

    // ==================== Getter 方法 ====================

    public long getCurrentColorTexture() { return currentColorTexture; }
    public long getCurrentDepthTexture() { return currentDepthTexture; }
    public long getPreviousColorTexture() { return previousColorTexture; }
    public long getMotionVectorTexture() { return motionVectorTexture; }
    public boolean hasMotionVectors() { return motionVectorTexture != 0; }
    public String getCameraDataJson() { return cameraDataJson; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public FrameGenMultiplier getMultiplier() { return multiplier; }
    public boolean isReflexSyncEnabled() { return reflexSyncEnabled; }

    // ==================== Builder 模式 ====================

    public static final class Builder {
        private long currentColorTexture = 0L;
        private long currentDepthTexture = 0L;
        private long previousColorTexture = 0L;
        private long motionVectorTexture = 0L;
        private String cameraDataJson;
        private int width = 0;
        private int height = 0;
        private FrameGenMultiplier multiplier = FrameGenMultiplier.OFF;
        private boolean reflexSyncEnabled = true;

        public Builder currentColorTexture(long texture) {
            this.currentColorTexture = texture;
            return this;
        }

        public Builder currentDepthTexture(long texture) {
            this.currentDepthTexture = texture;
            return this;
        }

        public Builder previousColorTexture(long texture) {
            this.previousColorTexture = texture;
            return this;
        }

        public Builder motionVectorTexture(long texture) {
            this.motionVectorTexture = texture;
            return this;
        }

        public Builder cameraDataJson(String json) {
            this.cameraDataJson = json;
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

        public Builder multiplier(FrameGenMultiplier mult) {
            this.multiplier = mult;
            return this;
        }

        public Builder reflexSync(boolean enabled) {
            this.reflexSyncEnabled = enabled;
            return this;
        }

        public FrameGenContext build() {
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("分辨率是必填字段");
            }
            return new FrameGenContext(this);
        }
    }
}
