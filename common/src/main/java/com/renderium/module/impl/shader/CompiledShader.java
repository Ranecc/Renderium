// Renderium - 光影模块
// 编译后的着色器

package com.renderium.module.impl.shader;

/**
 * 编译后的着色器
 * <p>
 * 封装 GLSL → SPIR-V 编译结果。
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class CompiledShader {

    private final String name;
    private final ShaderType type;
    private final byte[] spirvCode;
    private final long programHandle;

    public CompiledShader(String name, ShaderType type,
                          byte[] spirvCode, long programHandle) {
        this.name = name;
        this.type = type;
        this.spirvCode = spirvCode;
        this.programHandle = programHandle;
    }

    public String getName() { return name; }
    public ShaderType getType() { return type; }
    public byte[] getSpirvCode() { return spirvCode; }
    public long getProgramHandle() { return programHandle; }

    public enum ShaderType {
        VERTEX,
        FRAGMENT,
        COMPUTE,
        GEOMETRY
    }
}
