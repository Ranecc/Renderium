package com.ranecc.renderium.feature.intercept.handler;

/**
 * Mod 输出上下文 — 第三方 Mod 的输出处理上下文
 *
 * <p>当第三方 Mod（如 Iris、Sodium、OptiFine）尝试修改渲染输出时，
 * 使用此上下文包装其输出数据，便于 Renderium 进行拦截和转换。</p>
 *
 * <h2>使用场景</h3>
 * <ul>
 *   <li>Iris 着色器输出捕获</li>
 *   <li>Sodium 渲染管线集成</li>
 *   <li>OptiFine FBO 转换</li>
 * </ul>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 3.0.0
 */
public class ModOutputContext {

    /** 来源 Mod 名称（如 "Iris", "Sodium", "OptiFine"） */
    private final String modName;

    /** 输出类型（FBO / Texture / SwapChain） */
    private final OutputType outputType;

    /** 输出纹理 ID */
    private final int textureId;

    /** 输出宽度 */
    private final int width;

    /** 输出高度 */
    private final int height;

    /**
     * 输出类型枚举
     */
    public enum OutputType {
        /** 帧缓冲对象 */
        FBO,
        /** 纹理输出 */
        TEXTURE,
        /** 交换链 */
        SWAP_CHAIN
    }

    /**
     * 构建 Mod 输出上下文
     *
     * @param modName 来源 Mod 名称（不能为 null）
     * @param outputType 输出类型（不能为 null）
     * @param textureId 纹理 ID（>= 0）
     * @param width 宽度（必须 > 0）
     * @param height 高度（必须 > 0）
     */
    public ModOutputContext(String modName, OutputType outputType, int textureId, int width, int height) {
        if (modName == null || modName.trim().isEmpty()) {
            throw new IllegalArgumentException("Mod 名称不能为空");
        }
        if (outputType == null) {
            throw new IllegalArgumentException("输出类型不能为 null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("宽度和高度必须大于 0: " + width + "x" + height);
        }
        this.modName = modName;
        this.outputType = outputType;
        this.textureId = textureId;
        this.width = width;
        this.height = height;
    }

    /**
     * 获取来源 Mod 名称
     * @return Mod 名称（非 null）
     */
    public String getModName() {
        return modName;
    }

    /**
     * 获取输出类型
     * @return 输出类型枚举
     */
    public OutputType getOutputType() {
        return outputType;
    }

    /**
     * 获取纹理 ID
     * @return 纹理 ID
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * 获取输出宽度
     * @return 宽度（像素）
     */
    public int getWidth() {
        return width;
    }

    /**
     * 获取输出高度
     * @return 高度（像素）
     */
    public int getHeight() {
        return height;
    }

    @Override
    public String toString() {
        return String.format("ModOutputContext{mod='%s', type=%s, tex=%d, size=%dx%d}",
            modName, outputType, textureId, width, height);
    }

    /**
     * ModOutputContext 构建器
     */
    public static class Builder {
        private String modId;
        private String modName;
        private long fboHandle;
        private long colorTexture;
        private long depthTexture;
        private int width;
        private int height;
        private int frameIndex;

        /** @param modId Mod ID */
        public Builder modId(String modId) {
            this.modId = modId;
            return this;
        }

        /** @param modName Mod 名称 */
        public Builder modName(String modName) {
            this.modName = modName;
            return this;
        }

        /** @param fboHandle FBO 句柄 */
        public Builder fboHandle(long fboHandle) {
            this.fboHandle = fboHandle;
            return this;
        }

        /** @param colorTexture 颜色纹理 */
        public Builder colorTexture(long colorTexture) {
            this.colorTexture = colorTexture;
            return this;
        }

        /** @param depthTexture 深度纹理 */
        public Builder depthTexture(long depthTexture) {
            this.depthTexture = depthTexture;
            return this;
        }

        /** @param width 宽度 */
        public Builder width(int width) {
            this.width = width;
            return this;
        }

        /** @param height 高度 */
        public Builder height(int height) {
            this.height = height;
            return this;
        }

        /** @param frameIndex 帧索引 */
        public Builder frameIndex(int frameIndex) {
            this.frameIndex = frameIndex;
            return this;
        }

        /** @return 构建的 ModOutputContext 实例 */
        public ModOutputContext build() {
            return new ModOutputContext(this);
        }

        /** @return modId */
        public String getModId() { return modId; }

        /** @return modName */
        public String getModName() { return modName; }

        /** @return fboHandle */
        public long getFboHandle() { return fboHandle; }

        /** @return colorTexture */
        public long getColorTexture() { return colorTexture; }

        /** @return depthTexture */
        public long getDepthTexture() { return depthTexture; }

        /** @return width */
        public int getWidth() { return width; }

        /** @return height */
        public int getHeight() { return height; }

        /** @return frameIndex */
        public int getFrameIndex() { return frameIndex; }
    }

    // ==================== Builder 构造方法 ====================

    /**
     * 通过 Builder 构建 ModOutputContext
     *
     * @param builder Builder 实例
     */
    private ModOutputContext(Builder builder) {
        this.modName = builder.modName != null ? builder.modName : (builder.modId != null ? builder.modId : "unknown");
        this.outputType = OutputType.FBO;
        this.textureId = (int) builder.colorTexture;
        this.width = builder.width;
        this.height = builder.height;
        // 额外字段存储到上下文中，供第三方处理器通过 getter 访问
        this.modId = builder.modId;
        this.fboHandle = builder.fboHandle;
        this.colorTexture = builder.colorTexture;
        this.depthTexture = builder.depthTexture;
        this.frameIndex = builder.frameIndex;
    }

    // ==================== 扩展字段（供第三方处理器使用） ====================

    private String modId;
    private long fboHandle;
    private long colorTexture;
    private long depthTexture;
    private int frameIndex;

    /** 获取 Mod ID */
    public String getModId() { return modId; }

    /** 获取 FBO 句柄 */
    public long getFboHandle() { return fboHandle; }

    /** 获取颜色纹理句柄 */
    public long getColorTexture() { return colorTexture; }

    /** 获取深度纹理句柄 */
    public long getDepthTexture() { return depthTexture; }

    /** 获取帧索引 */
    public int getFrameIndex() { return frameIndex; }
}
