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
}
