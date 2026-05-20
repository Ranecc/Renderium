package com.ranecc.renderium.domain.model;

/** 超分辨率上下文 — 封装输入输出分辨率参数 */
public class SuperResolutionContext {
    private int inputWidth, inputHeight;
    private int outputWidth, outputHeight;
    public SuperResolutionContext() {}
    public int getInputWidth() { return inputWidth; }
    public void setInputWidth(int v) { this.inputWidth = v; }
    public int getInputHeight() { return inputHeight; }
    public void setInputHeight(int v) { this.inputHeight = v; }
    public int getOutputWidth() { return outputWidth; }
    public void setOutputWidth(int v) { this.outputWidth = v; }
    public int getOutputHeight() { return outputHeight; }
    public void setOutputHeight(int v) { this.outputHeight = v; }
}
