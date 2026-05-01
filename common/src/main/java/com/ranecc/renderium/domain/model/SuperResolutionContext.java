// Renderium - Blaze3D 拦截层系统
// 超分辨率上下文类 - 封装超分辨率的配置参数

package com.ranecc.renderium.domain.model;

/**
 * 超分辨率上下文
 * <p>
 * 封装超分辨率处理阶段所需的所有参数，
 * 传递给 {@link PostBlaze3DInterceptor#applySuperResolution(SuperResolutionContext)} 方法。
 *
 * <h3>支持的技术：</h3>
 * <ul>
 *   <li><b>DLSS</b> - NVIDIA Deep Learning Super Sampling</li>
 *   <li><b>XeSS</b> - Intel Xe Super Sampling</li>
 *   <li><b>FSR</b> - AMD FidelityFX Super Resolution</li>
 * </ul>
 *
 * @see PostBlaze3DInterceptor#applySuperResolution(SuperResolutionContext)
 * @since 5.1.0
 */
public final class SuperResolutionContext {

    // ==================== 字段定义 ====================

    /** 输入颜色纹理句柄（低分辨率） */
    private final long inputColorTexture;

    /** 输出颜色纹理句柄（高分辨率） */
    private final long outputColorTexture;

    /** 运动矢量纹理句柄（可选，用于 DLSS 2.0+） */
    private final long motionVectorTexture;

    /** 深度纹理句柄（可选，用于 DLSS 2.0+） */
    private final long depthTexture;

    /** 输入宽度（像素） */
    private final int inputWidth;

    /** 输入高度（像素） */
    private final int inputHeight;

    /** 输出宽度（像素） */
    private final int outputWidth;

    /** 输出高度（像素） */
    private final int outputHeight;

    /** 质量等级预设 */
    private final QualityPreset qualityPreset;

    /** 是否启用锐化后处理 */
    private final boolean sharpeningEnabled;

    /** 锐化强度 [0.0, 1.0] */
    private final float sharpeningStrength;

    // ==================== 质量等级枚举 ====================

    /**
     * 超分辨率质量等级预设
     */
    public enum QualityPreset {
        /** 性能优先（最低质量，最高性能） */
        PERFORMANCE,

        /** 平衡模式 */
        BALANCED,

        /** 质量优先 */
        QUALITY,

        /** 最高质量（DLSS 3.0+ 支持） */
        ULTRA_PERFORMANCE,

        /** DLSS 专属：超高质量 */
        DLSS_QUALITY
    }

    // ==================== 私有构造函数 ====================

    private SuperResolutionContext(Builder builder) {
        this.inputColorTexture = builder.inputColorTexture;
        this.outputColorTexture = builder.outputColorTexture;
        this.motionVectorTexture = builder.motionVectorTexture;
        this.depthTexture = builder.depthTexture;
        this.inputWidth = builder.inputWidth;
        this.inputHeight = builder.inputHeight;
        this.outputWidth = builder.outputWidth;
        this.outputHeight = builder.outputHeight;
        this.qualityPreset = builder.qualityPreset != null ? builder.qualityPreset : QualityPreset.BALANCED;
        this.sharpeningEnabled = builder.sharpeningEnabled;
        this.sharpeningStrength = builder.sharpeningStrength;
    }

    // ==================== Getter 方法 ====================

    public long getInputColorTexture() { return inputColorTexture; }
    public long getOutputColorTexture() { return outputColorTexture; }
    public long getMotionVectorTexture() { return motionVectorTexture; }
    public long getDepthTexture() { return depthTexture; }
    public boolean hasMotionVectors() { return motionVectorTexture != 0; }
    public boolean hasDepthTexture() { return depthTexture != 0; }
    public int getInputWidth() { return inputWidth; }
    public int getInputHeight() { return inputHeight; }
    public int getOutputWidth() { return outputWidth; }
    public int getOutputHeight() { return outputHeight; }
    public QualityPreset getQualityPreset() { return qualityPreset; }
    public boolean isSharpeningEnabled() { return sharpeningEnabled; }
    public float getSharpeningStrength() { return sharpeningStrength; }

    // ==================== Builder 模式 ====================

    public static final class Builder {
        private long inputColorTexture = 0L;
        private long outputColorTexture = 0L;
        private long motionVectorTexture = 0L;
        private long depthTexture = 0L;
        private int inputWidth = 0;
        private int inputHeight = 0;
        private int outputWidth = 0;
        private int outputHeight = 0;
        private QualityPreset qualityPreset = QualityPreset.BALANCED;
        private boolean sharpeningEnabled = false;
        private float sharpeningStrength = 0.5f;

        public Builder inputColorTexture(long texture) {
            this.inputColorTexture = texture;
            return this;
        }

        public Builder outputColorTexture(long texture) {
            this.outputColorTexture = texture;
            return this;
        }

        public Builder motionVectorTexture(long texture) {
            this.motionVectorTexture = texture;
            return this;
        }

        public Builder depthTexture(long texture) {
            this.depthTexture = texture;
            return this;
        }

        public Builder inputResolution(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("输入分辨率必须大于 0");
            }
            this.inputWidth = width;
            this.inputHeight = height;
            return this;
        }

        public Builder outputResolution(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("输出分辨率必须大于 0");
            }
            this.outputWidth = width;
            this.outputHeight = height;
            return this;
        }

        public Builder qualityPreset(QualityPreset preset) {
            this.qualityPreset = preset;
            return this;
        }

        public Builder sharpening(boolean enabled, float strength) {
            this.sharpeningEnabled = enabled;
            if (strength < 0.0f || strength > 1.0f) {
                throw new IllegalArgumentException("锐化强度必须在 [0.0, 1.0] 范围内: " + strength);
            }
            this.sharpeningStrength = strength;
            return this;
        }

        public SuperResolutionContext build() {
            if (inputWidth <= 0 || inputHeight <= 0) {
                throw new IllegalStateException("输入分辨率是必填字段");
            }
            if (outputWidth <= 0 || outputHeight <= 0) {
                throw new IllegalStateException("输出分辨率是必填字段");
            }
            return new SuperResolutionContext(this);
        }
    }
}
