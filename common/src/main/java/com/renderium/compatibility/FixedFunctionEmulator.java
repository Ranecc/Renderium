// Renderium - Fixed Function Emulator
// 模拟 OpenGL 固定管线功能，为没有 Shader 的老模组提供兼容
// 参考: Mesa3D / Zink 固定管线转换

package com.renderium.compatibility;

import java.util.logging.Logger;

/**
 * 固定管线模拟器
 * <p>
 * 现代图形 API (Vulkan/DX12) 没有"固定管线"概念，
 * 所有渲染都必须通过 Shader 完成。
 * 但很多 Minecraft 老模组直接调用 GL11 的固定管线函数
 * （如 glColor4f, glTexCoord2f, glNormal3f 等）。
 * <p>
 * 该模拟器的职责：
 * <ul>
 *   <li>收集固定管线的状态数据（颜色、纹理坐标、法线等）</li>
 *   <li>在 Draw 调用时将这些数据打包为 Uniform Buffer</li>
 *   <li>自动绑定内置的"通用固定管线 Shader"</li>
 * </ul>
 *
 * <h3>设计原则：</h3>
 * <pre>
 * 你的抽象层内部必须自带一套"万能伪固定管线 Shader"。
 * 当检测到老模组在没有任何自定义 Shader 的情况下 Draw 时，
 * 你的 RenderPipeline 自动绑定这套内部 Shader，
 * 把老模组设置的 glColor 作为 Uniform 塞进去。
 * </pre>
 *
 * @see MockGL11
 * @see StateSnapshot
 */
public final class FixedFunctionEmulator {

    private static final Logger LOGGER = Logger.getLogger(FixedFunctionEmulator.class.getName());

    // ==================== 单例 ====================

    private static volatile FixedFunctionEmulator instance;

    public static synchronized FixedFunctionEmulator getInstance() {
        if (instance == null) {
            instance = new FixedFunctionEmulator();
        }
        return instance;
    }

    // ==================== 固定管线状态存储 ====================

    /**
     * 当前颜色值 (RGBA, 范围 [0.0, 1.0])
     */
    private volatile float colorR = 1.0f;
    private volatile float colorG = 1.0f;
    private volatile float colorB = 1.0f;
    private volatile float colorA = 1.0f;

    /**
     * 当前纹理坐标 (ST, 范围 [0.0, 1.0])
     */
    private volatile float texCoordS = 0.0f;
    private volatile float texCoordT = 0.0f;

    /**
     * 当前法线向量
     */
    private volatile float normalX = 0.0f;
    private volatile float normalY = 1.0f; // 默认朝上
    private volatile float normalZ = 0.0f;

    /**
     * 当前材质属性
     */
    private volatile float materialAmbient[] = {0.2f, 0.2f, 0.2f, 1.0f};
    private volatile float materialDiffuse[] = {0.8f, 0.8f, 0.8f, 1.0f};
    private volatile float materialSpecular[] = {0.0f, 0.0f, 0.0f, 1.0f};
    private volatile float materialShininess = 0.0f;

    /**
     * 光照是否启用
     */
    private volatile boolean lightingEnabled = false;

    /**
     * 纹理是否启用
     */
    private volatile boolean textureEnabled = false;

    /**
     * 是否需要使用固定管线模式
     * 当 currentShaderProgramId == -1 时为 true
     */
    private volatile boolean fixedPipelineModeActive = false;

    // ==================== 颜色状态方法 ====================

    /**
     * 设置当前颜色 (glColor4f)
     *
     * @param r Red 分量
     * @param g Green 分量
     * @param b Blue 分量
     * @param a Alpha 分量
     */
    public void setColor4f(float r, float g, float b, float a) {
        this.colorR = r;
        this.colorG = g;
        this.colorB = b;
        this.colorA = a;
    }

    /**
     * 设置当前颜色 (glColor3f)
     */
    public void setColor3f(float r, float g, float b) {
        setColor4f(r, g, b, 1.0f);
    }

    /** 获取当前颜色 R 分量 */
    public float getColorR() { return colorR; }
    /** 获取当前颜色 G 分量 */
    public float getColorG() { return colorG; }
    /** 获取当前颜色 B 分量 */
    public float getColorB() { return colorB; }
    /** 获取当前颜色 A 分量 */
    public float getColorA() { return colorA; }

    /**
     * 获取当前颜色作为 float[4] 数组
     */
    public float[] getColorArray() {
        return new float[]{colorR, colorG, colorB, colorA};
    }

    // ==================== 纹理坐标方法 ====================

    /**
     * 设置纹理坐标 (glTexCoord2f)
     */
    public void setTexCoord2f(float s, float t) {
        this.texCoordS = s;
        this.texCoordT = t;
    }

    /** 获取纹理 S 坐标 */
    public float getTexCoordS() { return texCoordS; }
    /** 获取纹理 T 坐标 */
    public float getTexCoordT() { return texCoordT; }

    // ==================== 法线向量方法 ====================

    /**
     * 设置法线向量 (glNormal3f)
     */
    public void setNormal3f(float x, float y, float z) {
        this.normalX = x;
        this.normalY = y;
        this.normalZ = z;
    }

    /** 获取法线 X 分量 */
    public float getNormalX() { return normalX; }
    /** 获取法线 Y 分量 */
    public float getNormalY() { return normalY; }
    /** 获取法线 Z 分量 */
    public float getNormalZ() { return normalZ; }

    // ==================== 材质与光照方法 ====================

    /**
     * 设置材质环境光反射率 (glMaterialfv - GL_AMBIENT)
     */
    public void setMaterialAmbient(float r, float g, float b, float a) {
        this.materialAmbient = new float[]{r, g, b, a};
    }

    /**
     * 设置材质漫反射率 (glMaterialfv - GL_DIFFUSE)
     */
    public void setMaterialDiffuse(float r, float g, float b, float a) {
        this.materialDiffuse = new float[]{r, g, b, a};
    }

    /**
     * 设置材质镜面反射率 (glMaterialfv - GL_SPECULAR)
     */
    public void setMaterialSpecular(float r, float g, float b, float a) {
        this.materialSpecular = new float[]{r, g, b, a};
    }

    /**
     * 设置材质光泽度 (glMaterialf - GL_SHININESS)
     */
    public void setMaterialShininess(float shininess) {
        this.materialShininess = shininess;
    }

    /**
     * 启用/禁用光照 (glEnable/glDisable(GL_LIGHTING))
     */
    public void setLightingEnabled(boolean enabled) {
        this.lightingEnabled = enabled;
    }

    /**
     * 启用/禁用纹理映射 (glEnable/glDisable(GL_TEXTURE_2D))
     */
    public void setTextureEnabled(boolean enabled) {
        this.textureEnabled = enabled;
    }

    /** 检查光照是否启用 */
    public boolean isLightingEnabled() { return lightingEnabled; }
    /** 检查纹理是否启用 */
    public boolean isTextureEnabled() { return textureEnabled; }

    // ==================== 固定管线模式检测 ====================

    /**
     * 标记进入固定管线模式
     * 当检测到 glUseProgram(-1) 或未绑定任何 Shader 时调用
     */
    public void enterFixedPipelineMode() {
        this.fixedPipelineModeActive = true;
    }

    /**
     * 标记退出固定管线模式
     * 当绑定了有效的 Shader Program 时调用
     */
    public void exitFixedPipelineMode() {
        this.fixedPipelineModeActive = false;
    }

    /** 检查是否处于固定管线模式 */
    public boolean isFixedPipelineModeActive() { return fixedPipelineModeActive; }

    // ==================== Uniform 数据打包 ====================

    /**
     * 将所有固定管线状态打包为 Uniform 数据块
     * <p>
     * 该数据块会被传递给内置的"通用固定管线 Shader"，
     * 用于在 Vulkan/DX12 后端模拟固定管线效果。
     *
     * <h3>数据布局 (std140 兼容):</h3>
     * <pre>
     * layout(std140) uniform FixedFunctionState {
     *     vec4 u_Color;          // offset 0  (16 bytes)
     *     vec2 u_TexCoord;      // offset 16 (8 bytes + 8 padding)
     *     vec3 u_Normal;         // offset 32 (12 bytes + 4 padding)
     *     vec4 u_MaterialAmbient;  // offset 48 (16 bytes)
     *     vec4 u_MaterialDiffuse;  // offset 64 (16 bytes)
     *     vec4 u_MaterialSpecular; // offset 80 (16 bytes)
     *     float u_Shininess;    // offset 96 (4 bytes)
     *     int u_LightingEnabled;   // offset 100 (4 bytes)
     *     int u_TextureEnabled;    // offset 104 (4 bytes)
     * };
     * // Total: 112 bytes
     * </pre>
     *
     * @return 包含所有状态的 float 数组 (28 个 float = 112 字节)
     */
    public float[] packUniformData() {
        return new float[]{
            // u_Color (vec4)
            colorR, colorG, colorB, colorA,
            // u_TexCoord (vec2) + padding
            texCoordS, texCoordT, 0.0f, 0.0f,
            // u_Normal (vec3) + padding
            normalX, normalY, normalZ, 0.0f,
            // u_MaterialAmbient (vec4)
            materialAmbient[0], materialAmbient[1], 
            materialAmbient[2], materialAmbient[3],
            // u_MaterialDiffuse (vec4)
            materialDiffuse[0], materialDiffuse[1],
            materialDiffuse[2], materialDiffuse[3],
            // u_MaterialSpecular (vec4)
            materialSpecular[0], materialSpecular[1],
            materialSpecular[2], materialSpecular[3],
            // u_Shininess (float)
            materialShininess,
            // u_LightingEnabled (int as float)
            lightingEnabled ? 1.0f : 0.0f,
            // u_TextureEnabled (int as float)
            textureEnabled ? 1.0f : 0.0f
        };
    }

    /**
     * 计算打包数据的字节大小
     */
    public static int getUniformDataSizeBytes() {
        return 28 * 4; // 28 floats * 4 bytes
    }

    // ==================== 重置方法 ====================

    /**
     * 重置所有状态到默认值
     */
    public void resetToDefaults() {
        colorR = colorG = colorB = 1.0f;
        colorA = 1.0f;
        texCoordS = texCoordT = 0.0f;
        normalX = normalZ = 0.0f;
        normalY = 1.0f;
        materialAmbient = new float[]{0.2f, 0.2f, 0.2f, 1.0f};
        materialDiffuse = new float[]{0.8f, 0.8f, 0.8f, 1.0f};
        materialSpecular = new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        materialShininess = 0.0f;
        lightingEnabled = false;
        textureEnabled = false;
        fixedPipelineModeActive = false;
    }

    // ==================== 调试信息 ====================

    /**
     * 获取调试信息字符串
     */
    public String getDebugInfo() {
        return String.format(
            "FixedFunctionEmulator Debug Info:\n" +
            "  Color: [%.2f, %.2f, %.2f, %.2f]\n" +
            "  TexCoord: [%.2f, %.2f]\n" +
            "  Normal: [%.2f, %.2f, %.2f]\n" +
            "  Lighting: %b | Texture: %b\n" +
            "  FixedPipelineMode: %b",
            colorR, colorG, colorB, colorA,
            texCoordS, texCoordT,
            normalX, normalY, normalZ,
            lightingEnabled, textureEnabled,
            fixedPipelineModeActive
        );
    }
}
