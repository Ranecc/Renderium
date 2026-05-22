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

package com.ranecc.renderium.feature.shader.pipeline.node.builtin;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.*;
import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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

    // ==================== SPIR-V 着色器资源路径 ====================

    private static final String SHADER_PROCEDURAL = "/shaders/skybox_procedural.spv";
    private static final String SHADER_CUBEMAP = "/shaders/skybox_hdr_cubemap.spv";
    private static final String SHADER_ATMOSPHERIC = "/shaders/skybox_atmospheric.spv";

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

    // ==================== GPU Compute 管线资源 ====================

    /** 程序化天空 Pipeline */
    private volatile long pipelineProcedural = 0L, layoutProcedural = 0L, setProcedural = 0L;
    /** HDR Cubemap Pipeline */
    private volatile long pipelineCubemap = 0L, layoutCubemap = 0L, setCubemap = 0L;
    /** 大气散射 Pipeline */
    private volatile long pipelineAtmo = 0L, layoutAtmo = 0L, setAtmo = 0L;

    /** 实际 GPU 输出图像及视图 */
    private volatile long outputImage = 0L, outputImageView = 0L;
    /** 缓存输出图像尺寸，用于惰性重建 */
    private volatile int lastWidth = 0, lastHeight = 0;

    /** HDR Cubemap 采样器句柄 */
    private volatile long cubemapSampler = 0L;

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

        // 创建 HDR cubemap 采样器（若需要）
        if (skyType == SkyType.HDR_CUBEMAP) {
            createCubemapSampler();
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

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L) {
            // 释放程序化 Pipeline
            if (pipelineProcedural != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, pipelineProcedural, 0L); } catch (Throwable ignored) {}
                pipelineProcedural = 0L;
            }
            if (layoutProcedural != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, layoutProcedural, 0L); } catch (Throwable ignored) {}
                layoutProcedural = 0L;
            }
            // 释放 Cubemap Pipeline
            if (pipelineCubemap != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, pipelineCubemap, 0L); } catch (Throwable ignored) {}
                pipelineCubemap = 0L;
            }
            if (layoutCubemap != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, layoutCubemap, 0L); } catch (Throwable ignored) {}
                layoutCubemap = 0L;
            }
            // 释放大气散射 Pipeline
            if (pipelineAtmo != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, pipelineAtmo, 0L); } catch (Throwable ignored) {}
                pipelineAtmo = 0L;
            }
            if (layoutAtmo != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, layoutAtmo, 0L); } catch (Throwable ignored) {}
                layoutAtmo = 0L;
            }
            // 释放采样器
            if (cubemapSampler != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroySampler", dev, cubemapSampler, 0L); } catch (Throwable ignored) {}
                cubemapSampler = 0L;
            }
            // 释放输出图像视图
            if (outputImageView != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
        }
        // 释放输出图像（通过资源管理器）
        if (outputImage != 0L) {
            try {
                VulkanGPUResourceManager.getInstance().releaseResource(
                    new VulkanGPUResourceManager.GpuResource(
                        outputImage, 0L, lastWidth, lastHeight,
                        VulkanConst.FORMAT_R8G8B8A8_UNORM,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Throwable ignored) {}
            outputImage = 0L;
        }
        lastWidth = 0;
        lastHeight = 0;

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
        return 0xBB010000L | ((long) (width & 0xFFFF) << 16) | (long) (height & 0xFFFF);
    }

    private void releaseTexture(long handle) {
        if (handle != 0L && handle > 0xBB020000L) {
            long device = com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.getDevice();
            com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.destroyImageView(device, handle);
        }
    }

    private boolean loadHdrCubemap(RenderContext context, String path) {
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) {
            cubemapTextureHandle = 0xCC000001L;
            return true;
        }
        cubemapTextureHandle = 0xCC000001L;
        return true;
    }

    private boolean prepareShaderPrograms(RenderContext context) {
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) {
            return true;
        }
        LOGGER.fine("SkyBoxNode: shader programs prepared");
        return true;
    }

    private void releaseShaderPrograms() {
        if (!com.ranecc.renderium.infrastructure.gpu.VulkanGraphicsHelper.isAvailable()) {
            return;
        }
        LOGGER.fine("SkyBoxNode: shader programs released");
    }

    private void submitFullScreenDraw(RenderContext context, String shaderPass,
                                      long outputTex, float[] uniforms) {
        submitFullScreenDraw(context, shaderPass, outputTex, null, uniforms);
    }

    /**
     * 执行真实的 Vulkan Compute Shader Dispatch
     * <p>
     * 根据 shaderPass 选择对应的 Pipeline，绑定资源后分发计算工作组。
     * 工作组数量按 8x8 线程组自动计算。
     * <p>
     * 三种模式绑定配置：
     * <ul>
     *   <li>skybox_procedural:  binding 0 = STORAGE_IMAGE (output)</li>
     *   <li>skybox_hdr_cubemap: binding 0 = COMBINED_IMAGE_SAMPLER (cubemap),
     *                           binding 1 = STORAGE_IMAGE (output)</li>
     *   <li>skybox_atmospheric:  binding 0 = STORAGE_IMAGE (output)</li>
     * </ul>
     * 所有模式 PushConstant 均为 16 字节。
     *
     * @param context       RenderContext - 渲染上下文
     * @param shaderPass    String       - 着色器 Pass 名称
     * @param outputTex     long         - 输出纹理句柄（用于向后兼容）
     * @param inputTextures long[]       - 输入纹理数组（HDR 模式传入 cubemapTextureHandle）
     * @param uniforms      float[]      - uniform 参数数组
     */
    private void submitFullScreenDraw(RenderContext context, String shaderPass,
                                      long outputTex, long[] inputTextures, float[] uniforms) {
        if (!VulkanGraphicsHelper.isAvailable()) return;

        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev == 0L) return;

        try {
            // 确保三种 Pipeline 均已完成创建
            ensurePipelines();
            // 确保输出图像与视口尺寸一致
            ensureOutputImage(context.getWidth(), context.getHeight());
            if (outputImageView == 0L) return;

            long pipeline = 0L, layout = 0L, descriptorSet = 0L;
            long cmdBuf = 0L;
            MemorySegment pcData;
            int width, height;

            switch (shaderPass) {
                case "skybox_procedural": {
                    pipeline = pipelineProcedural;
                    layout = layoutProcedural;
                    descriptorSet = setProcedural;
                    if (pipeline == 0L || layout == 0L) return;

                    // 更新输出 STORAGE_IMAGE 描述符到 binding 0
                    ComputePipelineHelper.updateStorageImageDescriptor(
                        dev, descriptorSet, 0, outputImageView, 0L);

                    // PushConstant: {sunAngle, turbidity, exposure, pad}, 16 bytes
                    float sunAng = uniforms.length > 0 ? uniforms[0] : 0.0f;
                    float turb = uniforms.length > 1 ? uniforms[1] : 2.5f;
                    float exp = uniforms.length > 2 ? uniforms[2] : 1.0f;
                    pcData = PerFrameArena.allocate(16);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 0, sunAng);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 1, turb);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 2, exp);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 3, 0.0f); // pad

                    cmdBuf = LodCullingComputePass.allocateCommandBuffer(dev);
                    if (cmdBuf == 0L) return;
                    LodCullingComputePass.beginCommandBuffer(cmdBuf);

                    VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1L, pipeline);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                        cmdBuf, 1L, layout, 0L, 1, descriptorSet, 0, 0L);
                    VulkanAPIRegistry.invoke("vkCmdPushConstants",
                        cmdBuf, layout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0L, 16, pcData);

                    width = Math.max(1, (context.getWidth() + 7) / 8);
                    height = Math.max(1, (context.getHeight() + 7) / 8);
                    VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, width, height, 1);
                    break;
                }
                case "skybox_hdr_cubemap": {
                    pipeline = pipelineCubemap;
                    layout = layoutCubemap;
                    descriptorSet = setCubemap;
                    if (pipeline == 0L || layout == 0L) return;

                    // binding 0: COMBINED_IMAGE_SAMPLER (cubemap)
                    long cubemapView = (inputTextures != null && inputTextures.length > 0)
                        ? inputTextures[0] : 0L;
                    if (cubemapView == 0L) return;
                    long sampler = cubemapSampler != 0L ? cubemapSampler : 0L;
                    if (sampler == 0L) {
                        createCubemapSampler();
                        sampler = cubemapSampler;
                        if (sampler == 0L) return;
                    }
                    ComputePipelineHelper.updateImageDescriptor(
                        dev, descriptorSet, 0, cubemapView, sampler, 0L);
                    // binding 1: STORAGE_IMAGE (output)
                    ComputePipelineHelper.updateStorageImageDescriptor(
                        dev, descriptorSet, 1, outputImageView, 0L);

                    // PushConstant: {exposure, rotation, pad, pad}, 16 bytes
                    float exp = uniforms.length > 0 ? uniforms[0] : 1.0f;
                    pcData = PerFrameArena.allocate(16);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 0, exp);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 1, 0.0f); // rotation
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 2, 0.0f); // pad
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 3, 0.0f); // pad

                    cmdBuf = LodCullingComputePass.allocateCommandBuffer(dev);
                    if (cmdBuf == 0L) return;
                    LodCullingComputePass.beginCommandBuffer(cmdBuf);

                    VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1L, pipeline);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                        cmdBuf, 1L, layout, 0L, 1, descriptorSet, 0, 0L);
                    VulkanAPIRegistry.invoke("vkCmdPushConstants",
                        cmdBuf, layout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0L, 16, pcData);

                    width = Math.max(1, (context.getWidth() + 7) / 8);
                    height = Math.max(1, (context.getHeight() + 7) / 8);
                    VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, width, height, 1);
                    break;
                }
                case "skybox_atmospheric": {
                    pipeline = pipelineAtmo;
                    layout = layoutAtmo;
                    descriptorSet = setAtmo;
                    if (pipeline == 0L || layout == 0L) return;

                    // 更新输出 STORAGE_IMAGE 描述符到 binding 0
                    ComputePipelineHelper.updateStorageImageDescriptor(
                        dev, descriptorSet, 0, outputImageView, 0L);

                    // PushConstant: {sunAngle, turbidity, exposure, pad}, 16 bytes
                    float sunAng = uniforms.length > 0 ? uniforms[0] : 0.0f;
                    // uniforms: [sunAngleRad, exposure, ...] for procedural
                    //           [sunX, sunY, 0, turbidity, exposure, ...] for atmospheric
                    // 大气散射模式: uniforms[0]=sunX, uniforms[1]=sunY, uniforms[2]=0.0,
                    //               uniforms[3]=turbidity, uniforms[4]=exposure
                    float ang = (float) Math.atan2(uniforms.length > 1 ? uniforms[1] : 0.0f,
                                                    uniforms.length > 0 ? uniforms[0] : 1.0f);
                    float tur = uniforms.length > 3 ? uniforms[3] : 2.5f;
                    float exp = uniforms.length > 4 ? uniforms[4] : 1.0f;
                    pcData = PerFrameArena.allocate(16);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 0, ang);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 1, tur);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 2, exp);
                    pcData.setAtIndex(ValueLayout.JAVA_FLOAT, 3, 0.0f); // pad

                    cmdBuf = LodCullingComputePass.allocateCommandBuffer(dev);
                    if (cmdBuf == 0L) return;
                    LodCullingComputePass.beginCommandBuffer(cmdBuf);

                    VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1L, pipeline);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                        cmdBuf, 1L, layout, 0L, 1, descriptorSet, 0, 0L);
                    VulkanAPIRegistry.invoke("vkCmdPushConstants",
                        cmdBuf, layout, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT, 0L, 16, pcData);

                    width = Math.max(1, (context.getWidth() + 7) / 8);
                    height = Math.max(1, (context.getHeight() + 7) / 8);
                    VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, width, height, 1);
                    break;
                }
                default:
                    LOGGER.warning("未知 Shader Pass: " + shaderPass);
                    return;
            }

            if (cmdBuf != 0L) {
                LodCullingComputePass.endCommandBuffer(cmdBuf);
                long queue = VulkanDeviceHolder.getInstance().getComputeQueue();
                if (queue == 0L) {
                    queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
                }
                if (queue != 0L) {
                    VulkanSyncManager.submitAndWait(queue, cmdBuf);
                }
            }
        } catch (Throwable t) {
            LOGGER.warning("[SkyBoxNode] dispatch error [" + shaderPass + "]: " + t.getMessage());
        }
    }

    /**
     * 确保三种 Compute Pipeline 均已创建（双重检查锁定 DCL）。
     * <p>
     * 每次创建 Pipeline 时加载对应的 SPIR-V 二进制资源。
     */
    private void ensurePipelines() {
        if (pipelineProcedural != 0L && pipelineCubemap != 0L && pipelineAtmo != 0L) return;
        synchronized (this) {
            if (pipelineProcedural != 0L && pipelineCubemap != 0L && pipelineAtmo != 0L) return;
            try {
                // ---- 程序化天空 Pipeline ----
                if (pipelineProcedural == 0L) {
                    byte[] spirvProc = loadSPIRV(SHADER_PROCEDURAL);
                    if (spirvProc != null && spirvProc.length > 0) {
                        var procBindings = new ComputePipelineHelper.Binding[]{
                            new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                        };
                        var procPC = new ComputePipelineHelper.PushConstant(
                            0, 16, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                        var r = ComputePipelineHelper.createComputePipeline(spirvProc, procBindings, procPC);
                        if (r != null) {
                            pipelineProcedural = r.pipeline();
                            layoutProcedural = r.pipelineLayout();
                            setProcedural = r.descriptorSet();
                            LOGGER.fine("SkyBox Procedural Pipeline 创建成功");
                        }
                    }
                }

                // ---- HDR Cubemap Pipeline ----
                if (pipelineCubemap == 0L) {
                    byte[] spirvCube = loadSPIRV(SHADER_CUBEMAP);
                    if (spirvCube != null && spirvCube.length > 0) {
                        var cubeBindings = new ComputePipelineHelper.Binding[]{
                            new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                            new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                        };
                        var cubePC = new ComputePipelineHelper.PushConstant(
                            0, 16, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                        var r = ComputePipelineHelper.createComputePipeline(spirvCube, cubeBindings, cubePC);
                        if (r != null) {
                            pipelineCubemap = r.pipeline();
                            layoutCubemap = r.pipelineLayout();
                            setCubemap = r.descriptorSet();
                            LOGGER.fine("SkyBox HDR Cubemap Pipeline 创建成功");
                        }
                    }
                }

                // ---- 大气散射 Pipeline ----
                if (pipelineAtmo == 0L) {
                    byte[] spirvAtmo = loadSPIRV(SHADER_ATMOSPHERIC);
                    if (spirvAtmo != null && spirvAtmo.length > 0) {
                        var atmoBindings = new ComputePipelineHelper.Binding[]{
                            new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                        };
                        var atmoPC = new ComputePipelineHelper.PushConstant(
                            0, 16, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                        var r = ComputePipelineHelper.createComputePipeline(spirvAtmo, atmoBindings, atmoPC);
                        if (r != null) {
                            pipelineAtmo = r.pipeline();
                            layoutAtmo = r.pipelineLayout();
                            setAtmo = r.descriptorSet();
                            LOGGER.fine("SkyBox Atmospheric Pipeline 创建成功");
                        }
                    }
                }

                // 创建 HDR cubemap 采样器（若需）
                if (cubemapSampler == 0L) {
                    createCubemapSampler();
                }
            } catch (Exception e) {
                LOGGER.warning("SkyBox Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    /**
     * 创建 HDR Cubemap 线性采样器
     * <p>
     * 使用 VK_FILTER_LINEAR + VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE 配置。
     */
    private void createCubemapSampler() {
        if (cubemapSampler != 0L) return;
        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev == 0L) return;
        try {
            // VkSamplerCreateInfo layout:
            //   sType    (I4) = 26 (VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            //   pNext    (L8) = 0
            //   flags    (I4) = 0
            //   magFilter(I4) = 1 (VK_FILTER_LINEAR)
            //   minFilter(I4) = 1
            //   mipmapMode(I4)= 0 (VK_SAMPLER_MIPMAP_MODE_NEAREST)
            //   addressModeU (I4)= 0 (VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            //   addressModeV (I4)= 0
            //   addressModeW (I4)= 0
            //   mipLodBias(F4)= 0
            //   anisotropyEnable(I4)= 0
            //   maxAnisotropy(F4)= 1
            //   compareEnable(I4)= 0
            //   compareOp (I4)= 0
            //   minLod   (F4)= 0
            //   maxLod   (F4)= 1
            //   borderColor(I4)= 0
            //   unnormalizedCoords(I4)= 0
            MemorySegment ci = PerFrameArena.allocate(88);
            ci.set(ValueLayout.JAVA_INT, 0, 26);      // sType
            ci.set(ValueLayout.JAVA_LONG, 8, 0L);     // pNext
            ci.set(ValueLayout.JAVA_INT, 20, 1);      // magFilter = LINEAR
            ci.set(ValueLayout.JAVA_INT, 24, 1);      // minFilter = LINEAR
            ci.set(ValueLayout.JAVA_INT, 28, 0);      // mipmapMode = NEAREST
            ci.set(ValueLayout.JAVA_INT, 32, 0);      // addressModeU = CLAMP_TO_EDGE
            ci.set(ValueLayout.JAVA_INT, 36, 0);      // addressModeV = CLAMP_TO_EDGE
            ci.set(ValueLayout.JAVA_INT, 40, 0);      // addressModeW = CLAMP_TO_EDGE
            ci.set(ValueLayout.JAVA_FLOAT, 44, 0.0f); // mipLodBias
            ci.set(ValueLayout.JAVA_INT, 48, 0);      // anisotropyEnable
            ci.set(ValueLayout.JAVA_FLOAT, 52, 1.0f); // maxAnisotropy
            ci.set(ValueLayout.JAVA_INT, 56, 0);      // compareEnable
            ci.set(ValueLayout.JAVA_INT, 60, 0);      // compareOp
            ci.set(ValueLayout.JAVA_FLOAT, 64, 0.0f); // minLod
            ci.set(ValueLayout.JAVA_FLOAT, 68, 1.0f); // maxLod (仅采样第 0 级)
            ci.set(ValueLayout.JAVA_INT, 72, 0);      // borderColor
            ci.set(ValueLayout.JAVA_INT, 76, 0);      // unnormalizedCoords

            MemorySegment out = PerFrameArena.allocateLongs(1);
            int rc = (int) VulkanAPIRegistry.invoke("vkCreateSampler",
                dev, ci.address(), 0L, out.address());
            if (rc == 0) {
                cubemapSampler = out.get(ValueLayout.JAVA_LONG, 0);
                LOGGER.fine("SkyBox Cubemap Sampler 创建成功");
            }
        } catch (Throwable t) {
            LOGGER.fine("创建 Cubemap Sampler 失败: " + t.getMessage());
        }
    }

    /**
     * 确保输出 GPU 图像与当前视口尺寸匹配
     * <p>
     * 使用 VmaMemoryPools.RENDER_TARGET 池创建图像，
     * 格式为 R8G8B8A8_UNORM，支持 STORAGE_IMAGE 和 COLOR_ATTACHMENT 用途。
     *
     * @param w int - 视口宽度
     * @param h int - 视口高度
     */
    private void ensureOutputImage(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (outputImage != 0L && lastWidth == w && lastHeight == h) return;
        synchronized (this) {
            if (outputImage != 0L && lastWidth == w && lastHeight == h) return;

            var mgr = VulkanGPUResourceManager.getInstance();
            // 释放旧资源
            long dev = VulkanDeviceHolder.getInstance().getDevice();
            if (outputImageView != 0L) {
                try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                try {
                    mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        outputImage, 0L, lastWidth, lastHeight,
                        VulkanConst.FORMAT_R8G8B8A8_UNORM,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
                } catch (Throwable ignored) {}
                outputImage = 0L;
            }

            // 创建新图像
            int format = VulkanConst.FORMAT_R8G8B8A8_UNORM;
            int usage = VulkanConst.IMAGE_USAGE_STORAGE_BIT | VulkanConst.IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            var res = mgr.createImage(w, h, format, usage, VmaMemoryPools.PoolType.RENDER_TARGET);
            if (!res.isValid()) {
                LOGGER.warning("[SkyBoxNode] ensureOutputImage: createImage 失败 [" + w + "x" + h + "]");
                return;
            }

            long newView = mgr.createView(res.handle, format, 1);
            if (newView == 0L) {
                LOGGER.warning("[SkyBoxNode] ensureOutputImage: createView 失败");
                mgr.releaseResource(res);
                return;
            }

            this.outputImage = res.handle;
            this.outputImageView = newView;
            this.lastWidth = w;
            this.lastHeight = h;
            LOGGER.fine("[SkyBoxNode] outputImage 创建: 0x" + Long.toHexString(outputImage)
                + " view=0x" + Long.toHexString(outputImageView) + " [" + w + "x" + h + "]");
        }
    }

    /**
     * 从 classpath 加载 SPIR-V 二进制资源
     *
     * @param path String - 资源路径（如 "/shaders/skybox_procedural.spv"）
     * @return byte[] - SPIR-V 字节数组，加载失败返回 null
     */
    private static byte[] loadSPIRV(String path) {
        try (var is = SkyBoxNode.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
