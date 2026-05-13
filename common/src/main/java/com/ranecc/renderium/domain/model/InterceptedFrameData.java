// Renderium - Blaze3D 拦截层系统
// 拦截帧数据容器 - 扩展 FrameData，支持 HDR 和元数据

package com.ranecc.renderium.domain.model;
import com.ranecc.renderium.domain.model.InterceptedFrameData;

import com.ranecc.renderium.domain.model.FrameData;
import java.time.Instant;
import java.util.Objects;

/**
 * 拦截帧数据容器
 * <p>
 * 扩展现有 {@link FrameData} 类，添加 HDR 数据支持和丰富的元数据。
 * 作为 {@link PostBlaze3DInterceptor} 的输入和输出数据结构。
 *
 * <h3>相比 FrameData 的增强：</h3>
 * <ul>
 *   <li><b>HDR 数据</b>：支持 HDR 颜色缓冲和色调映射参数</li>
 *   <li><b>元数据</b>：时间戳、渲染模式、质量指标等</li>
 *   <li><b>性能标记</b>：各阶段耗时、处理状态等</li>
 *   <li><b>双路径支持</b>：FBO 和 Swapchain Image 双模式</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * Blaze3D 渲染管线
 *     │
 *     ▼
 * InterceptedFrameData（封装渲染结果）
 *     │
 *     ▼
 * PostBlaze3DInterceptor.postProcess()  ← 后拦截入口
 *     │
 *     ├── 帧捕获
 *     ├── 超分辨率 (DLSS/FSR/XeSS)
 *     ├── 帧生成 (DLSS-FG/FSR-FG)
 *     └── 输出到屏幕
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>此类为不可变对象，线程安全。
 *
 * @see PostBlaze3DInterceptor#postProcess(FrameData)
 * @see FrameData
 * @since 5.1.0
 */
public final class InterceptedFrameData {

    // ==================== 基础帧数据 ====================

    /** 原始 FrameData 实例 */
    private final FrameData frameData;

    // ==================== HDR 数据字段 ====================

    /** HDR 颜色纹理句柄（0 表示非 HDR） */
    private final long hdrColorTexture;

    /** 色调映射参数 - 曝光值 */
    private final float exposure;

    /** 色调映射参数 - 伽马值 */
    private final float gamma;

    /** 是否启用 HDR 渲染 */
    private final boolean hdrEnabled;

    // ==================== 元数据字段 ====================

    /** 帧捕获时间戳（Instant） */
    private final Instant captureTimestamp;

    /** 渲染模式标识符 */
    private final String renderMode;

    /** 质量评分 [0.0, 1.0]，1.0 表示最高质量 */
    private final float qualityScore;

    /** 处理阶段标记 */
    private final ProcessingStage currentStage;

    /** 是否为 Triple Buffering 的中间帧 */
    private final boolean isTripleBuffered;

    // ==================== 性能字段 ====================

    /** Blaze3D 渲染耗时（纳秒） */
    private final long renderTimeNanos;

    /** 后处理累计耗时（纳秒） */
    private final long postProcessTimeNanos;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private InterceptedFrameData(Builder builder) {
        this.frameData = Objects.requireNonNull(builder.frameData, "FrameData 不能为 null");
        this.hdrColorTexture = builder.hdrColorTexture;
        this.exposure = builder.exposure;
        this.gamma = builder.gamma;
        this.hdrEnabled = builder.hdrEnabled;
        this.captureTimestamp = builder.captureTimestamp != null ? builder.captureTimestamp : Instant.now();
        this.renderMode = builder.renderMode != null ? builder.renderMode : "DEFAULT";
        this.qualityScore = builder.qualityScore;
        this.currentStage = builder.currentStage != null ? builder.currentStage : ProcessingStage.CAPTURED;
        this.isTripleBuffered = builder.isTripleBuffered;
        this.renderTimeNanos = builder.renderTimeNanos;
        this.postProcessTimeNanos = builder.postProcessTimeNanos;
    }

    // ==================== Getter 方法：基础数据 ====================

    /**
     * 获取原始 FrameData 实例
     *
     * @return FrameData 对象
     */
    public FrameData getFrameData() {
        return frameData;
    }

    /**
     * 获取颜色纹理句柄（代理到 FrameData）
     *
     * @return 颜色纹理句柄
     */
    public long getColorTexture() {
        return frameData.getColorTexture();
    }

    /**
     * 获取深度纹理句柄（代理到 FrameData）
     *
     * @return 深度纹理句柄
     */
    public long getDepthTexture() {
        return frameData.getDepthTexture();
    }

    /**
     * 检查深度纹理是否可用（代理到 FrameData）
     *
     * @return true 如果深度纹理有效
     */
    public boolean hasDepthTexture() {
        return frameData.hasDepthTexture();
    }

    /**
     * 获取帧宽度（代理到 FrameData）
     *
     * @return 宽度（像素）
     */
    public int getWidth() {
        return frameData.getWidth();
    }

    /**
     * 获取帧高度（代理到 FrameData）
     *
     * @return 高度（像素）
     */
    public int getHeight() {
        return frameData.getHeight();
    }

    /**
     * 获取帧序号（代理到 FrameData）
     *
     * @return 帧索引
     */
    public int getFrameIndex() {
        return frameData.getFrameIndex();
    }

    /**
     * 获取帧间隔时间（代理到 FrameData）
     *
     * @return 帧间隔（秒）
     */
    public float getDeltaTime() {
        return frameData.getDeltaTime();
    }

    // ==================== Getter 方法：HDR 数据 ====================

    /**
     * 获取 HDR 颜色纹理句柄
     *
     * @return HDR 纹理句柄，0 表示非 HDR 模式
     */
    public long getHdrColorTexture() {
        return hdrColorTexture;
    }

    /**
     * 检查是否启用 HDR
     *
     * @return true 如果 HDR 已启用
     */
    public boolean isHdrEnabled() {
        return hdrEnabled;
    }

    /**
     * 获取曝光值
     *
     * @return 曝光值（通常 0.1-10.0）
     */
    public float getExposure() {
        return exposure;
    }

    /**
     * 获取伽马值
     *
     * @return 伽马值（通常 1.0-2.4）
     */
    public float getGamma() {
        return gamma;
    }

    // ==================== Getter 方法：元数据 ====================

    /**
     * 获取帧捕获时间戳
     *
     * @return Instant 时间戳
     */
    public Instant getCaptureTimestamp() {
        return captureTimestamp;
    }

    /**
     * 获取渲染模式
     *
     * @return 模式名称字符串
     */
    public String getRenderMode() {
        return renderMode;
    }

    /**
     * 获取质量评分
     *
     * @return 质量分数 [0.0, 1.0]
     */
    public float getQualityScore() {
        return qualityScore;
    }

    /**
     * 获取当前处理阶段
     *
     * @return ProcessingStage 枚举
     */
    public ProcessingStage getCurrentStage() {
        return currentStage;
    }

    /**
     * 检查是否为 Triple Buffering 帧
     *
     * @return true 如果是三重缓冲的中间帧
     */
    public boolean isTripleBuffered() {
        return isTripleBuffered;
    }

    // ==================== Getter 方法：性能数据 ====================

    /**
     * 获取 Blaze3D 渲染耗时
     *
     * @return 耗时（纳秒）
     */
    public long getRenderTimeNanos() {
        return renderTimeNanos;
    }

    /**
     * 获取 Blaze3D 渲染耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getRenderTimeMillis() {
        return renderTimeNanos / 1_000_000.0;
    }

    /**
     * 获取后处理累计耗时
     *
     * @return 耗时（纳秒）
     */
    public long getPostProcessTimeNanos() {
        return postProcessTimeNanos;
    }

    /**
     * 获取后处理累计耗时（毫秒）
     *
     * @return 耗时（ms）
     */
    public double getPostProcessTimeMillis() {
        return postProcessTimeNanos / 1_000_000.0;
    }

    // ==================== 处理阶段枚举 ====================

    /**
     * 帧处理阶段枚举
     * <p>
     * 标记帧在处理后管线中的当前状态。
     */
    public enum ProcessingStage {
        /** 刚从 Blaze3D 捕获 */
        CAPTURED,

        /** 已完成超分辨率处理 */
        SUPER_RESOLUTION_APPLIED,

        /** 已完成帧生成 */
        FRAME_GENERATED,

        /** 已完成后处理效果 */
        POST_PROCESSED,

        /** 已输出到屏幕 */
        OUTPUT_TO_SCREEN
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
            "InterceptedFrameData{frame=%s, %dx%d, HDR=%s, stage=%s, quality=%.2f}",
            frameData,
            getWidth(),
            getHeight(),
            hdrEnabled ? "ON" : "OFF",
            currentStage,
            qualityScore
        );
    }

    // ==================== Builder 模式 ====================

    /**
     * InterceptedFrameData 构建器
     * <p>
     * 从现有 FrameData 构建，并添加 HDR 和元数据信息。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * // 从现有 FrameData 创建
     * InterceptedFrameData intercepted = new InterceptedFrameData.Builder(frameData)
     *     .hdrEnabled(true)
     *     .exposure(1.0f)
     *     .gamma(2.2f)
     *     .renderMode("COMPATIBILITY")
     *     .qualityScore(0.95f)
     *     .currentStage(ProcessingStage.CAPTURED)
     *     .renderTimeNanos(renderDuration)
     *     .build();
     * </pre>
     */
    public static final class Builder {

        // 必填字段（非 final 以支持对象池复用）
        private FrameData frameData;

        // 可选字段（带默认值）
        private long hdrColorTexture = 0L;
        private float exposure = 1.0f;
        private float gamma = 2.2f;
        private boolean hdrEnabled = false;
        private Instant captureTimestamp;
        private String renderMode = "DEFAULT";
        private float qualityScore = 1.0f;
        private ProcessingStage currentStage = ProcessingStage.CAPTURED;
        private boolean isTripleBuffered = false;
        private long renderTimeNanos = 0L;
        private long postProcessTimeNanos = 0L;

        /**
         * 构造器 - 接受必填的 FrameData
         *
         * @param frameData 原始帧数据（不能为 null）
         * @throws IllegalArgumentException 如果 frameData 为 null
         */
        public Builder(FrameData frameData) {
            this.frameData = Objects.requireNonNull(frameData, "FrameData 不能为 null");
        }

        /**
         * 重置 Builder 到默认状态并设置新的 FrameData
         * <p>
         * 用于对象池复用场景，避免每帧创建新的 Builder 实例。
         * 重置后所有可选字段恢复为默认值，frameData 替换为新值。
         *
         * @param newFrameData 新的帧数据（不能为 null）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果 newFrameData 为 null
         */
        public Builder reset(FrameData newFrameData) {
            this.frameData = Objects.requireNonNull(newFrameData, "FrameData 不能为 null");
            this.hdrColorTexture = 0L;
            this.exposure = 1.0f;
            this.gamma = 2.2f;
            this.hdrEnabled = false;
            this.captureTimestamp = null;
            this.renderMode = "DEFAULT";
            this.qualityScore = 1.0f;
            this.currentStage = ProcessingStage.CAPTURED;
            this.isTripleBuffered = false;
            this.renderTimeNanos = 0L;
            this.postProcessTimeNanos = 0L;
            return this;
        }

        /**
         * 设置 HDR 颜色纹理句柄
         *
         * @param texture HDR 纹理句柄
         * @return this（链式调用）
         */
        public Builder hdrColorTexture(long texture) {
            this.hdrColorTexture = texture;
            return this;
        }

        /**
         * 启用 HDR 并设置参数
         *
         * @param exposure 曝光值
         * @param gamma   伽马值
         * @return this（链式调用）
         */
        public Builder hdrParameters(float exposure, float gamma) {
            this.hdrEnabled = true;
            this.exposure = exposure;
            this.gamma = gamma;
            return this;
        }

        /**
         * 设置曝光值
         *
         * @param exposure 曝光值
         * @return this（链式调用）
         */
        public Builder exposure(float exposure) {
            this.exposure = exposure;
            return this;
        }

        /**
         * 设置伽马值
         *
         * @param gamma 伽马值
         * @return this（链式调用）
         */
        public Builder gamma(float gamma) {
            this.gamma = gamma;
            return this;
        }

        /**
         * 设置捕获时间戳
         *
         * @param timestamp Instant 时间戳
         * @return this（链式调用）
         */
        public Builder captureTimestamp(Instant timestamp) {
            this.captureTimestamp = timestamp;
            return this;
        }

        /**
         * 设置渲染模式
         *
         * @param mode 模式名称
         * @return this（链式调用）
         */
        public Builder renderMode(String mode) {
            this.renderMode = mode;
            return this;
        }

        /**
         * 设置质量评分
         *
         * @param score 质量分数 [0.0, 1.0]
         * @return this（链式调用）
         */
        public Builder qualityScore(float score) {
            if (score < 0.0f || score > 1.0f) {
                throw new IllegalArgumentException("质量评分必须在 [0.0, 1.0] 范围内: " + score);
            }
            this.qualityScore = score;
            return this;
        }

        /**
         * 设置当前处理阶段
         *
         * @param stage 处理阶段枚举
         * @return this（链式调用）
         */
        public Builder currentStage(ProcessingStage stage) {
            this.currentStage = stage;
            return this;
        }

        /**
         * 设置 Triple Buffering 标记
         *
         * @param tripleBuffered 是否为三重缓冲帧
         * @return this（链式调用）
         */
        public Builder tripleBuffered(boolean tripleBuffered) {
            this.isTripleBuffered = tripleBuffered;
            return this;
        }

        /**
         * 设置渲染耗时
         *
         * @param nanos 耗时（纳秒）
         * @return this（链式调用）
         */
        public Builder renderTimeNanos(long nanos) {
            this.renderTimeNanos = nanos;
            return this;
        }

        /**
         * 设置后处理耗时
         *
         * @param nanos 耗时（纳秒）
         * @return this（链式调用）
         */
        public Builder postProcessTimeNanos(long nanos) {
            this.postProcessTimeNanos = nanos;
            return this;
        }

        /**
         * 构建 InterceptedFrameData 实例
         *
         * @return 不可变的 InterceptedFrameData 实例
         */
        public InterceptedFrameData build() {
            return new InterceptedFrameData(this);
        }
    }
}
