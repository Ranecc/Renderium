package com.ranecc.renderium.feature.intercept.post.sr;

/**
 * TODO [REVIEW] 桩类 - 超分辨率处理上下文
 * 包含超分辨率处理所需的输入输出尺寸和配置信息
 */
public class SuperResolutionContext {
    private final int inputWidth;
    private final int inputHeight;
    private final int outputWidth;
    private final int outputHeight;

    public SuperResolutionContext(int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    /** 输入图像宽度 */
    public int getInputWidth() { return inputWidth; }

    /** 输入图像高度 */
    public int getInputHeight() { return inputHeight; }

    /** 输出图像宽度 */
    public int getOutputWidth() { return outputWidth; }

    /** 输出图像高度 */
    public int getOutputHeight() { return outputHeight; }
}
