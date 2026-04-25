// Renderium - 可扩展 Shader 节点系统
// SkyBoxNode - 天空盒渲染节点
//
// 功能：
//   1. 程序化天空生成（基于大气散射模型）
//   2. HDR 环境贴图天空盒
//   3. 大气散射（Rayleigh + Mie 散射）
//
// 参数：
//   skyType:     enum  { PROCEDURAL, HDR_CUBEMAP, ATMOSPHERIC }
//   sunAngle:    float [0, 180]  - 太阳高度角（度）
//   turbidity:   float [1.5, 8]  - 大气浑浊度
//   exposure:    float [0.1, 10] - 曝光值

package com.renderium.pipeline.node.builtin;

import com.renderium.interception.context.RenderContext;
import com.renderium.pipeline.node.AbstractPipelineNode;
import com.renderium.pipeline.node.PipelineNode;

import java.util.logging.Logger;

/**
 * 天空盒渲染节点
 * <p>
 * 支持三种天空渲染模式：
 * <ul>
 *   <li><b>PROCEDURAL</b>：程序化渐变天空（快速，适合性能受限场景）</li>
 *   <li><b>HDR_CUBEMAP</b>：HDR 立方体贴图环境（高质量，需要 .hdr/.exr 文件）</li>
 *   <li><b>ATMOSPHERIC</b>：基于物理的大气散射（Preetham / Hosek-Wilkie 模型）</li>
 * </ul>
 *
 * <h2>算法概述：</h2>
 * <h3>程序化模式：</h3>
 * <pre>
 * // 基于太阳角度的梯度插值
 * vec3 skyColor = mix(horizonColor, zenithColor, pow(height, exponent))
 * </pre>
 *
 * <h3>大气散射模式：</h3>
 * <pre>
 * // Rayleigh 散射（短波长光散射更强 → 蓝天）
 * beta_R = (5.8e-6, 13.5e-6, 33.1e-6) * (lambda/550)^(-4)
 *
 * // Mie 散射（气溶胶/云 → 白色散射晕）
 * beta_M = 2e-5 * (lambda/550)^(-0.84) * turbidity
 *
 * // 最终颜色 = 太阳盘面 + 单次散射 + 多次散射(地面反射)
 * </pre>
 *
 * <h2>.comp 配置示例：</h2>
 * <pre>{@code
 * {
 *   "metadata": { "id": "skybox", "category": "PRE_RENDER", "priority": 1 },
 *   "parameters": [
 *     { "id": "skyType", "type": "ENUM", "defaultValue": "ATMOSPHERIC" },
 *     { "id": "sunAngle", "type": "FLOAT", "defaultValue": 45.0, "range": [0, 180] },
 *     { "id": "turbidity", "type": "FLOAT", "defaultValue": 2.5, "range": [1.5, 8.0] },
 *     { "id": "exposure", "type": "FLOAT", "defaultValue": 1.0, "range": [0.1, 10.0] }
 *   ],
 *   "outputs": [{ "name": "outSkyColor", "type": "VEC4" }]
 * }
 * }</pre>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0
 */
public class SkyBoxNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(SkyBoxNode.class.getName());

    // ==================== 常量定义 ====================

    /** 默认太阳高度角（度，0=地平线，90=天顶） */
    public static final float DEFAULT_SUN_ANGLE = 45.0f;
    public static final float MIN_SUN_ANGLE = 0.0f;
    public static final float MAX_SUN_ANGLE = 180.0f;

    /** 默认大气浑浊度 */
    public static final float DEFAULT_TURBIDITY = 2.5f;
    public static final float MIN_TURBIDITY = 1.5f;   // 极清澈空气
    public static final float MAX_TURBIDITY = 8.0f;    // 极浑浊（沙尘暴）

    /** 默认曝光值 */
    public static final float DEFAULT_EXPOSURE = 1.0f;
    public static final float MIN_EXPOSURE = 0.1f;
    public static final float MAX_EXPOSURE = 10.0f;

    /**
     * 天空类型枚举
     */
    public enum SkyType {
        /** 程序化渐变天空（最快） */
        PROCEDURAL,
        /** HDR 端方体贴图 */
        HDR_CUBEMAP,
        /** 物理大气散射（最真实但最慢） */
        ATMOSPHERIC
    }

    // ==================== 动态参数 ====================

    /** 当前天空类型 */
    private volatile SkyType skyType = SkyType.ATMOSPHERIC;

    /** 太阳高度角（度） */
    private volatile float sunAngle = DEFAULT_SUN_ANGLE;

    /** 大气浑浊度 */
    private volatile float turbidity = DEFAULT_TURBIDITY;

    /** 曝光值 */
    private volatile float exposure = DEFAULT_EXPOSURE;

    // ==================== 运行时状态 ====================

    /** 天空盒立方体纹理句柄 */
    private long cubemapTextureHandle = 0L;

    /** HDR 环境贴图路径（如果使用 HDR_CUBEMAP 模式） */
    private volatile String hdrEnvMapPath;

    /** 输出纹理句柄 */
    private long outputTextureHandle = 0L;

    /** 性能统计 */
    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    // ==================== 构造函数 ====================

    /**
     * 构造天空盒节点
     */
    public SkyBoxNode() {
        super(
                "skybox",
                "SkyBox (天空盒)",
                PipelineNode.Category.PRE_RENDER,
                1,      // 最高优先级（最先执行）
                new String[0]  // 无前置依赖
        );
        LOGGER.fine("SkyBoxNode 已创建");
    }

    // ==================== PipelineNode 实现 ====================

    @Override
    protected boolean onInitialize(RenderContext context) {
        // 预分配输出纹理（默认 1920x1080）
        outputTextureHandle = allocateOutputTexture(1920, 1080);

        if (skyType == SkyType.HDR_CUBEMAP && hdrEnvMapPath != null) {
            boolean loaded = loadHdrCubemap(context, hdrEnvMapPath);
            if (!loaded) {
                LOGGER.warning("HDR 环境贴图加载失败，回退到程序化模式");
                this.skyType = SkyType.PROCEDURAL;
            }
        }

        // 预编译着色器程序（三种模式各一套）
        boolean shadersOk = prepareShaderPrograms(context);
        if (!shadersOk) {
            LOGGER.severe("SkyBox 着色器预编译失败");
            return false;
        }

        LOGGER.info(String.format("SkyBoxNode 初始化完成: mode=%s, sunAngle=%.1f, turbidity=%.1f",
                skyType.name(), sunAngle, turbidity));
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        long startTimeNanos = System.nanoTime();

        // 参数快照（ThreadLocal 式单次读取）
        SkyType currentType = this.skyType;
        float currentSunAngle = this.sunAngle;
        float currentTurbidity = this.turbidity;
        float currentExposure = this.exposure;

        // 根据天空类型选择不同的渲染路径
        switch (currentType) {
            case PROCEDURAL:
                renderProceduralSky(context, currentSunAngle, currentExposure);
                break;
            case HDR_CUBEMAP:
                renderHdrCubemapSky(context, currentExposure);
                break;
            case ATMOSPHERIC:
                renderAtmosphericSky(context, currentSunAngle, currentTurbidity, currentExposure);
                break;
            default:
                LOGGER.warning("未知的天空类型: " + currentType);
                return 0L;
        }

        // 更新统计
        long elapsed = System.nanoTime() - startTimeNanos;
        totalExecuteTimeNanos += elapsed;
        totalFrames++;

        return outputTextureHandle;
    }

    @Override
    protected void onDispose() {
        releaseTexture(outputTextureHandle);
        releaseTexture(cubemapTextureHandle);
        outputTextureHandle = 0L;
        cubemapTextureHandle = 0L;
        releaseShaderPrograms();
        LOGGER.fine("SkyBoxNode 资源已释放");
    }

    // ==================== 渲染路径实现 ====================

    /**
     * 程序化天空渲染（最快路径）
     * <p>
     * 使用基于太阳角度的简单梯度模型。
     * 适合性能敏感场景或作为 fallback。
     *
     * @param context  RenderContext - 渲染上下文
     * @param sunAngle float       - 太阳高度角（度）
     * @param exposure float       - 曝光值
     */
    private void renderProceduralSky(RenderContext context, float sunAngle, float exposure) {
        // Uniform 设置：
        //   u_sunAngle  = radians(sunAngle)
        //   u_exposure  = exposure
        //   u_horizonColor = vec3(1.0, 0.6, 0.4)  // 橙红色地平线
        //   u_zenithColor  = vec3(0.3, 0.5, 0.9)  // 蓝色天顶
        submitFullScreenDraw(context, "skybox_procedural", outputTextureHandle,
                new float[]{
                        (float) Math.toRadians(sunAngle),  // 太阳角（弧度）
                        exposure,                          // 曝光
                        1.0f, 0.6f, 0.4f,                  // 地平线颜色 RGB
                        0.3f, 0.5f, 0.9f                   // 天顶颜色 RGB
                });
    }

    /**
     * HDR 立方体贴图天空渲染
     *
     * @param context  RenderContext - 渲染上下文
     * @param exposure float       - 曝光值
     */
    private void renderHdrCubemapSky(RenderContext context, float exposure) {
        if (cubemapTextureHandle == 0L) {
            // 回退到程序化模式
            renderProceduralSky(context, DEFAULT_SUN_ANGLE, exposure);
            return;
        }

        // Uniform 设置：
        //   u_cubemap   = samplerCube (cubemapTextureHandle)
        //   u_exposure  = exposure
        submitFullScreenDraw(context, "skybox_hdr_cubemap", outputTextureHandle,
                new long[]{cubemapTextureHandle},
                new float[]{exposure});
    }

    /**
     * 物理大气散射天空渲染（最高质量）
     * <p>
     * 基于 Preetham 日空光模型或 Hosek-Wilkie 模型，
     * 模拟 Rayleigh 和 Mie 散射。
     *
     * <h3>着色器核心逻辑：</h3>
     * <pre>
     * // 计算视线方向与太阳方向的夹角
     * float cosTheta = dot(viewDir, sunDir);
     *
     * // Rayleigh 相位函数（蓝色优先散射）
     * float phaseR = (3.0 / (16.0 * PI)) * (1.0 + cosTheta^2);
     *
     * // Mie 相位函数（前向散射为主，产生日晕）
     * float g = 0.76;  // 不对称因子
     * float phaseM = (3.0 / (8.0 * PI)) * ((1-g^2) / (1+g^2-2*g*cosTheta)^1.5);
     *
     * // 最终颜色
     * vec3 color = (beta_R * phaseR * intensity_R + beta_M * phaseM * intensity_M)
     *             * (1.0 - exp(-opticalDepth));
     * </pre>
     *
     * @param context   RenderContext - 渲染上下文
     * @param sunAngle  float         - 太阳高度角（度）
     * @param turbidity float         - 大气浑浊度
     * @param exposure  float         - 曝光值
     */
    private void renderAtmosphericSky(RenderContext context, float sunAngle,
                                       float turbidity, float exposure) {
        // 将太阳角度转换为方向向量
        float sunAngleRad = (float) Math.toRadians(sunAngle);
        float sunX = (float) Math.cos(sunAngleRad);
        float sunY = (float) Math.sin(sunAngleRad);

        // Uniform 设置：
        //   u_sunDirection = normalize(vec3(cos(θ), sin(θ), 0))
        //   u_turbidity    = turbidity
        //   u_exposure     = exposure
        //   u_rayleigh     = vec3(5.8e-6, 13.5e-6, 33.1e-6)  // Rayleigh 系数
        //   u_mie          = 2e-5 * turbidity               // Mie 系数
        submitFullScreenDraw(context, "skybox_atmospheric", outputTextureHandle,
                new float[]{
                        sunX, sunY, 0.0f,           // 太阳方向（归一化）
                        turbidity,                   // 浑浊度
                        exposure,                    // 曝光
                        5.8e-6f, 13.5e-6f, 33.1e-6f // Rayleigh 散射系数
                });
    }

    // ==================== 配置 API ====================

    /**
     * 设置天空类型
     *
     * @param type SkyType - 天空类型枚举
     */
    public void setSkyType(SkyType type) {
        if (type != null) {
            this.skyType = type;
        }
    }

    /** @return SkyType - 当前天空类型 */
    public SkyType getSkyType() { return skyType; }

    /**
     * 设置太阳高度角
     *
     * @param angle float - 角度（0=地平线，90=天顶）
     */
    public void setSunAngle(float angle) {
        this.sunAngle = clamp(angle, MIN_SUN_ANGLE, MAX_SUN_ANGLE);
    }

    /** @return float - 当前太阳高度角 */
    public float getSunAngle() { return sunAngle; }

    /**
     * 设置大气浑浊度
     *
     * @param t float - 浑浊度值（1.5=极清澈，8.0=极浑浊）
     */
    public void setTurbidity(float t) {
        this.turbidity = clamp(t, MIN_TURBIDITY, MAX_TURBIDITY);
    }

    /** @return float - 当前浑浊度 */
    public float getTurbidity() { return turbidity; }

    /**
     * 设置曝光值
     *
     * @param e float - 曝光值
     */
    public void setExposure(float e) {
        this.exposure = clamp(e, MIN_EXPOSURE, MAX_EXPOSURE);
    }

    /** @return float - 当前曝光值 */
    public float getExposure() { return exposure; }

    /**
     * 设置 HDR 环境贴图路径
     *
     * @param path String - .hdr 或 .exr 文件路径
     */
    public void setHdrEnvMapPath(String path) {
        this.hdrEnvMapPath = path;
    }

    // ==================== 诊断 API ====================

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }

    public long getTotalFrames() { return totalFrames; }

    public void resetStats() {
        totalExecuteTimeNanos = 0L;
        totalFrames = 0L;
    }

    @Override
    public String toString() {
        return String.format("SkyBox{type=%s, sunAngle=%.1f, turbidity=%.1f, exposure=%.2f}",
                skyType.name(), sunAngle, turbidity, exposure);
    }

    // ==================== 私有辅助方法 ====================

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private long allocateOutputTexture(int width, int height) {
        // TODO: 分配 RGBA16F 格式的纹理
        return 0xBB010000L | ((long) (width & 0xFFFF) << 16) | (long) (height & 0xFFFF);
    }

    private void releaseTexture(long handle) {
        if (handle != 0L) {
            // TODO: 释放 GPU 纹理资源
        }
    }

    private boolean loadHdrCubemap(RenderContext context, String path) {
        // TODO: 加载 HDR 立方体贴图文件
        // 支持 .hdr (Radiance) 和 .exr (OpenEXR) 格式
        // 加载后转换为 Vulkan Cube Map Image
        cubemapTextureHandle = 0xCC000001L;  // 占位符句柄
        return true;
    }

    private boolean prepareShaderPrograms(RenderContext context) {
        // TODO: 编译三种模式的着色器:
        //   1. "skybox_procedural"      - 程序化渐变
        //   2. "skybox_hdr_cubemap"     - HDR 立方体采样
        //   3. "skybox_atmospheric"     - 大气散射
        return true;
    }

    private void releaseShaderPrograms() {
        // TODO: 销毁所有着色器程序
    }

    private void submitFullScreenDraw(RenderContext context, String shaderPass,
                                      long outputTex, float[] uniforms) {
        submitFullScreenDraw(context, shaderPass, outputTex, null, uniforms);
    }

    private void submitFullScreenDraw(RenderContext context, String shaderPass,
                                      long outputTex, long[] inputTextures, float[] uniforms) {
        // TODO: 提交全屏 Draw Call
        // 类似 Bloom 节点的 submitFullScreenDraw() 方法
    }
}
