package com.ranecc.renderium.feature.shader.spirv;

/**
 * TODO [REVIEW] 桩类 - SPIR-V 着色器模块
 * 封装编译后的 SPIR-V 二进制着色器代码
 */
public class SPIRVShaderModule {
    private final byte[] spirvBinary;
    private final String entryPoint;
    private final int stage;

    /**
     * 创建 SPIR-V 着色器模块
     * @param spirvBinary SPIR-V 二进制数据
     * @param entryPoint 入口函数名（如 main）
     * @param stage 着色器阶段 (vertex/fragment/compute 等)
     */
    public SPIRVShaderModule(byte[] spirvBinary, String entryPoint, int stage) {
        this.spirvBinary = spirvBinary != null ? spirv.clone() : new byte[0];
        this.entryPoint = entryPoint;
        this.stage = stage;
    }

    /** 获取 SPIR-V 二进制数据的副本 */
    public byte[] getSpirvBinary() { return spirvBinary != null ? spirv.clone() : new byte[0]; }

    /** 获取入口函数名 */
    public String getEntryPoint() { return entryPoint; }

    /** 获取着色器阶段标识 */
    public int getStage() { return stage; }

    /** 获取二进制数据大小（字节） */
    public int getSize() { return spirvBinary != null ? spirvBinary.length : 0; }
}
