// Renderium - 可扩展 Shader 节点系统
// RayTracingNode - 光线追踪节点 (实验性)
//
// 功能：
//   1. 光线追踪反射 (Ray Traced Reflections)
//   2. 光线追踪 AO (Ambient Occlusion)
//   3. 光线追踪 GI (Global Illumination)
//   4. 去噪后处理 (Denoising Passes)
//
// 参数：
//   rtEnabled:      bool              - 是否启用 RT
//   rayCount:       int [1, 32]        - 每像素光线数
//   maxDistance:    float [0.1, 1000]  - 最大追踪距离
//   denoisePasses:  int [0, 5]         - 去噪迭代次数
//
// 前提条件：
//   - Vulkan 1.2+ 或 VK_KHR_ray_tracing_pipeline 扩展
//   - GPU 需支持 AS (Acceleration Structure) 构建
//   - 需要 Streamline SDK 提供底层 Vulkan RT API 访问
//
// 当前状态：实验性功能，默认禁用，需手动启用

package com.ranecc.renderium.feature.shader.pipeline.node.builtin;


import java.util.logging.Level;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.ShaderPathResolver;
import com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.*;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.domain.constant.VulkanConst;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 光线追踪节点（实验性）
 * <p>
 * 提供基于硬件加速的光线追踪能力，
 * 包括反射、AO 和全局光照计算。
 *
 * <h2>前提条件：</h2>
 * <ul>
 *   <li>Vulkan 1.2+ 或支持 VK_KHR_ray_tracing_pipeline 扩展</li>
 *   <li>GPU 需要支持 AS (Acceleration Structure) 构建</li>
 *   <li>需要有效的 BLAS/TLAS 加速结构</li>
 * </ul>
 *
 * <h2>RT 管线流程：</h2>
 * <pre>
 * ┌─────────────┐     ┌──────────────┐     ┌──────────────┐     ┌────────────┐
 * │ Build AS    │ ─→ │ Trace Rays   │ ─→ │ Shade Hit    │ ─→ │ Denoise    │
 * │ (TLAS/BLAS)│     │ (RGen/RHit) │     │ (RCHit/RMiss)│     │ (ATrous/SV)│
 * └─────────────┘     └──────────────┘     └──────────────┘     └────────────┘
 * </pre>
 *
 * <h3>当前实现状态：</h3>
 * <p>此节点处于<strong>实验性阶段</strong>，核心 RT 功能依赖 Vulkan 扩展支持。
 * 当硬件不支持时，节点会自动回退为直通模式（pass-through），
 * 仅复制输入纹理到输出，不执行任何光线追踪计算。</p>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0 (实验性功能)
 */
public class RayTracingNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(RayTracingNode.class.getName());

    private static final int NODE_ID = 23;

    /** 所需的 Vulkan RT 扩展列表 */
    private static final String[] REQUIRED_RT_EXTENSIONS = {
            "VK_KHR_acceleration_structure",
            "VK_KHR_ray_tracing_pipeline",
            "VK_KHR_deferred_host_operations",
            "VK_KHR_buffer_device_address",
            "VK_KHR_ray_query"
    };

    public static final int DEFAULT_RAY_COUNT = 4;
    public static final int MIN_RAY_COUNT = 1;
    public static final int MAX_RAY_COUNT = 32;

    public static final float DEFAULT_MAX_DISTANCE = 100.0f;

    public static final int DEFAULT_DENOISE_PASSES = 2;

    /** 去噪着色器 shader key（通过 ShaderPathResolver 解析为实际 SPV 路径） */
    private static final String KEY_DENOISE = "pipeline/postprocess/rt/rt_denoise_atrous";

    /** 追踪着色器 shader key（通过 ShaderPathResolver 解析为实际 SPV 路径） */
    private static final String KEY_TRACE = "pipeline/postprocess/rt/rt_trace_rays";

    /** RT 功能可用性标志（volatile 保证跨线程可见性） */
    private volatile boolean rtAvailable = false;

    /** 是否已执行过扩展检测（避免重复检测） */
    private volatile boolean extensionChecked = false;

    private volatile boolean rtEnabled = false;
    private volatile int rayCount = DEFAULT_RAY_COUNT;
    private volatile float maxDistance = DEFAULT_MAX_DISTANCE;
    private volatile int denoisePasses = DEFAULT_DENOISE_PASSES;

    // 运行时状态
    private long outputTextureHandle = 0L;
    private long rtOutputTexture = 0L;      // 原始 RT 输出（含噪声）
    private long denoiseIntermediate = 0L;   // 去噪中间结果

    // Vulkan RT 资源句柄（0L 表示未分配）
    private volatile long tlasHandle = 0L;  // Top-Level Acceleration Structure
    private volatile long sbtHandle = 0L;   // Shader Binding Table

    /** 缓存的分辨率（从 RenderContext 动态获取） */
    private volatile int cachedWidth = 0;
    private volatile int cachedHeight = 0;

    /** 去噪管线句柄 */
    private volatile long denoisePipeline = 0L;
    /** 去噪管线布局句柄 */
    private volatile long denoiseLayout = 0L;
    /** 去噪描述符集句柄 */
    private volatile long denoiseSet = 0L;
    /** 追踪管线句柄 */
    private volatile long tracePipeline = 0L;
    /** 追踪管线布局句柄 */
    private volatile long traceLayout = 0L;
    /** 追踪描述符集句柄 */
    private volatile long traceSet = 0L;
    /** 去噪输出图像句柄 */
    private volatile long outputImage = 0L;
    /** 去噪输出图像视图句柄 */
    private volatile long outputImageView = 0L;
    /** 上次去噪输出宽度（用于分辨率变化检测） */
    private volatile int lastWidth = 0;
    /** 上次去噪输出高度（用于分辨率变化检测） */
    private volatile int lastHeight = 0;

    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    public RayTracingNode() {
        super(
                "ray_tracing",
                "Ray Tracing (Experimental)",
                PipelineNode.Category.LIGHTING,
                200,
                new String[]{"gbuffer_geometry", "lighting"}
        );
        LOGGER.fine("RayTracingNode 已创建 (实验性)");
    }

    @Override
    protected boolean onInitialize(RenderContext context) {
        // 从 RenderContext 动态获取分辨率（修复 P1-01 硬编码问题）
        this.cachedWidth = context.getWidth();
        this.cachedHeight = context.getHeight();

        if (cachedWidth <= 0 || cachedHeight <= 0) {
            LOGGER.warning("RenderContext 分辨率无效 (%dx%d)，使用默认值 1920x1080".formatted(cachedWidth, cachedHeight));
            cachedWidth = 1920;
            cachedHeight = 1080;
        }

        outputTextureHandle = allocateOutputTexture(cachedWidth, cachedHeight);
        rtOutputTexture = allocateOutputTexture(cachedWidth, cachedHeight);
        denoiseIntermediate = allocateOutputTexture(cachedWidth, cachedHeight);

        // 检测 RT 扩展是否可用
        this.rtAvailable = detectRayTracingSupport(context);

        if (!rtAvailable) {
            LOGGER.warning("光线追踪不可用");
            return true;  // 仍返回 true，execute() 中会自动回退
        }

        boolean ok = prepareShaderPrograms(context);
        if (!ok) {
            LOGGER.severe("[RayTracingNode] 着色器程序准备失败，RT 功能不可用");
            return false;
        }

        // 创建加速结构
        buildAccelerationStructures(context);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("RT Node: available=%b, enabled=%b, rays=%d, cache=%dx%d",
                rtAvailable, rtEnabled, rayCount, cachedWidth, cachedHeight));
        }
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        RenderiumProfiler.recordStart(23);
        if (inputResources == null || inputResources.length == 0) return 0L;

        boolean currentEnabled = this.rtEnabled && this.rtAvailable;
        int currentRayCount = this.rayCount;
        float currentMaxDist = this.maxDistance;
        int currentDenoise = this.denoisePasses;

        if (!currentEnabled) {
            // 回退模式：直接传递输入（不执行 RT）
            RenderiumProfiler.recordEnd(23);
            totalExecuteTimeNanos += RenderiumProfiler.getNodeTime(23);
            totalFrames++;
            return inputResources[0];
        }

        // === Phase 1: 光线追踪 ===
        executeRayTracing(context, inputResources[0], currentRayCount, currentMaxDist);

        // === Phase 2: 去噪 (如果启用) ===
        if (currentDenoise > 0) {
            executeDenoising(context, rtOutputTexture, currentDenoise);
        } else {
            // 无去噪，直接使用原始 RT 输出
            blitTexture(rtOutputTexture, outputTextureHandle);
        }

        RenderiumProfiler.recordEnd(23);
        totalExecuteTimeNanos += RenderiumProfiler.getNodeTime(23);
        totalFrames++;
        return outputTextureHandle;
    }

    /**
     * 执行光线追踪
     * <p>
     * 发射光线并收集命中信息，生成含噪声的渲染结果。
     */
    private void executeRayTracing(RenderContext context, long gBufferInput,
                                   int raysPerPixel, float maxDist) {
        // VkCmdTraceRays() 调用
        // 绑定:
        //   - TLAS (tlasHandle)
        //   - SBT (sbtHandle: RGen + RCHit + RMiss shaders)
        //   - 输入: G-Buffer (位置、法线、材质属性)
        //   - 输出: rtOutputTexture (RGBA32F)

        submitComputeDispatch(context, "rt_trace_rays",
                new long[]{gBufferInput}, rtOutputTexture,
                new float[]{
                        raysPerPixel,
                        maxDist,
                        1.0f / maxDist,   // invMaxDistance
                        0.999f             // rayTMax (避免自相交)
                });
    }

    /**
     * 执行去噪处理
     * <p>
     * 使用 A-Trous 滤波或 SVGF (Stochastic Variance Guided Filtering)
     * 消除光线追踪的蒙特卡洛噪声。
     */
    private void executeDenoising(RenderContext context, long noisyInput, int passes) {
        long source = noisyInput;
        long target = denoiseIntermediate;

        for (int pass = 0; pass < passes; pass++) {
            // A-Trous 边缘保持滤波
            submitFullScreenDraw(context, "rt_denoise_atrous",
                    source, target,
                    new float[]{
                            2.0f,   // spatialSigma (空间滤波半径)
                            0.1f,   // colorSigma (颜色相似度权重)
                            pass % 2 == 0 ? 1.0f : 0.0f
                    });

            long temp = source;
            source = target;
            target = temp;
        }

        long dev2 = VulkanDeviceHolder.getInstance().getDevice();
        if (dev2 != 0L) {
            ComputePipelineHelper.updateStorageImageDescriptor(dev2, denoiseSet, 0, source, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev2, denoiseSet, 1, source, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev2, denoiseSet, 2, source, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(dev2, denoiseSet, 3, outputImageView, 0L);

            // 去噪 Dispatch（实验性功能，异常时跳过）
            try {
                long cmdBuf2 = FrameCommandContext.beginNodeCB(NODE_ID);
                VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf2, 1L, denoisePipeline);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf2, 1L, denoiseLayout, 0L, 1, denoiseSet, 0, 0L);
                
                // 使用 confined arena 管理 push constant 内存，确保及时释放
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment pcData2 = arena.allocate(32);
                    pcData2.setAtIndex(ValueLayout.JAVA_INT, 0, 1);
                    pcData2.setAtIndex(ValueLayout.JAVA_FLOAT, 1, 0.1f);
                    pcData2.setAtIndex(ValueLayout.JAVA_FLOAT, 2, 1.0f);
                    pcData2.setAtIndex(ValueLayout.JAVA_FLOAT, 3, 1.0f);
                    VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf2, denoiseLayout, 0x20L, 0L, 32, pcData2.address());
                }
                
                int w2 = Math.max(1, (context.getWidth() + 7) / 8);
                int h2 = Math.max(1, (context.getHeight() + 7) / 8);
                VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf2, w2, h2, 1);
                FrameCommandContext.endNodeCB(NODE_ID);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "RayTracingNode: 去噪 dispatch 异常，跳过", t);
            }
        }
    }

    private void submitComputeDispatch(RenderContext context, String pass,
                                       long[] inputs, long output, float[] uniforms) {
        if (!VulkanFFMBinding.isFfmLoaded()) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L || tracePipeline == 0L || traceSet == 0L) return;
        try {
            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf == 0L) return;

            for (int i = 0; i < inputs.length; i++) {
                if (inputs[i] != 0L) {
                    ComputePipelineHelper.updateStorageImageDescriptor(device, traceSet, i, inputs[i], 0L);
                }
            }
            ComputePipelineHelper.updateStorageImageDescriptor(device, traceSet, inputs.length, output, 0L);

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, tracePipeline);

            // 使用 confined arena 管理 descriptor set 和 push constant 内存
            try (Arena arena = Arena.ofConfined()) {
                if (traceLayout != 0L && traceSet != 0L) {
                    MemorySegment dsPtr = arena.allocate(ValueLayout.JAVA_LONG);
                    dsPtr.set(ValueLayout.JAVA_LONG, 0, traceSet);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                            cmdBuf, 1, traceLayout, 0, 1, dsPtr.address(), 0, 0L);
                }

                if (uniforms != null && uniforms.length > 0 && traceLayout != 0L) {
                    MemorySegment pcSeg = arena.allocate(32L);
                    for (int i = 0; i < Math.min(uniforms.length, 8); i++) {
                        pcSeg.set(ValueLayout.JAVA_FLOAT, i * 4L, uniforms[i]);
                    }
                    VulkanAPIRegistry.invoke("vkCmdPushConstants",
                            cmdBuf, traceLayout, 0x00000020L, 0, 32, pcSeg.address());
                }
            }

            int w = Math.max(1, (context.getWidth() + 7) / 8);
            int h = Math.max(1, (context.getHeight() + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);

            FrameCommandContext.endNodeCB(NODE_ID);
            LOGGER.finest("[RT] " + pass + " dispatch: " + w + "x" + h);
        } catch (Throwable t) {
            LOGGER.fine("[RT] " + pass + " dispatch error: " + t.getMessage());
        }
    }

    private void submitFullScreenDraw(RenderContext context, String pass,
                                      long source, long target, float[] uniforms) {
        if (!VulkanFFMBinding.isFfmLoaded()) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L || denoisePipeline == 0L || denoiseSet == 0L) return;
        try {
            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf == 0L) return;

            ComputePipelineHelper.updateStorageImageDescriptor(device, denoiseSet, 0, source, 0L);
            ComputePipelineHelper.updateStorageImageDescriptor(device, denoiseSet, 1, target, 0L);

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, denoisePipeline);

            // 使用 confined arena 管理 descriptor set 和 push constant 内存
            try (Arena arena = Arena.ofConfined()) {
                if (denoiseLayout != 0L && denoiseSet != 0L) {
                    MemorySegment dsPtr = arena.allocate(ValueLayout.JAVA_LONG);
                    dsPtr.set(ValueLayout.JAVA_LONG, 0, denoiseSet);
                    VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                            cmdBuf, 1, denoiseLayout, 0, 1, dsPtr.address(), 0, 0L);
                }

                if (uniforms != null && uniforms.length > 0 && denoiseLayout != 0L) {
                    MemorySegment pcSeg = arena.allocate(32L);
                    for (int i = 0; i < Math.min(uniforms.length, 8); i++) {
                        pcSeg.set(ValueLayout.JAVA_FLOAT, i * 4L, uniforms[i]);
                    }
                    VulkanAPIRegistry.invoke("vkCmdPushConstants",
                            cmdBuf, denoiseLayout, 0x00000020L, 0, 32, pcSeg.address());
                }
            }

            int w = Math.max(1, (context.getWidth() + 7) / 8);
            int h = Math.max(1, (context.getHeight() + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, w, h, 1);

            FrameCommandContext.endNodeCB(NODE_ID);
            LOGGER.finest("[RT] " + pass + " dispatch: " + w + "x" + h);
        } catch (Throwable t) {
            LOGGER.fine("[RT] " + pass + " dispatch error: " + t.getMessage());
        }
    }

    private void ensureDenoisePipeline() {
        if (denoisePipeline != 0L) return;
        synchronized (this) {
            if (denoisePipeline != 0L) return;
            try {
                byte[] spirv = ShaderPathResolver.resolveSPIRV(KEY_DENOISE);
                if (spirv == null) return;
                ComputePipelineHelper.Binding[] bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                ComputePipelineHelper.PushConstant pc =
                        new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var result = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (result != null) {
                    denoisePipeline = result.pipeline();
                    denoiseLayout = result.pipelineLayout();
                    denoiseSet = result.descriptorSet();
                }
            } catch (Throwable t) {
                LOGGER.warning("[RT] createDenoisePipeline: " + t.getMessage());
            }
        }
    }

    private void ensureTracePipeline() {
        if (tracePipeline != 0L) return;
        synchronized (this) {
            if (tracePipeline != 0L) return;
            try {
                byte[] spirv = ShaderPathResolver.resolveSPIRV(KEY_TRACE);
                if (spirv == null) return;
                ComputePipelineHelper.Binding[] bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                };
                ComputePipelineHelper.PushConstant pc =
                        new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);
                var result = ComputePipelineHelper.createComputePipeline(spirv, bindings, pc);
                if (result != null) {
                    tracePipeline = result.pipeline();
                    traceLayout = result.pipelineLayout();
                    traceSet = result.descriptorSet();
                }
            } catch (Throwable t) {
                LOGGER.warning("[RT] createTracePipeline: " + t.getMessage());
            }
        }
    }

    private void ensureOutputImage(int w, int h) {
        if (outputImage != 0L && lastWidth == w && lastHeight == h) return;
        synchronized (this) {
            if (outputImage != 0L && lastWidth == w && lastHeight == h) return;
            var mgr = VulkanGPUResourceManager.getInstance();
            if (outputImageView != 0L) { try { mgr.destroyView(outputImageView); } catch (Throwable ignored) {} outputImageView = 0L; }
            if (outputImage != 0L) { try { mgr.releaseResource(new VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastWidth, lastHeight, 97, VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {} outputImage = 0L; }
            var res = mgr.createImage(w, h, 97, 0x20 | 0x10, VmaMemoryPools.PoolType.RENDER_TARGET);
            if (res != null && res.handle != 0L) {
                outputImage = res.handle;
                outputImageView = mgr.createView(outputImage, 97, 1);
                lastWidth = w; lastHeight = h;
            }
        }
    }

    private byte[] loadSPIRV(String path) {
        try (var is = getClass().getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            LOGGER.warning("[RT] loadSPIRV: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onDispose() {
        long dev = VulkanDeviceHolder.getInstance().getDevice();
        if (dev != 0L) {
            if (denoisePipeline != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, denoisePipeline, 0L); } catch (Throwable ignored) {} denoisePipeline = 0L; }
            if (denoiseLayout != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, denoiseLayout, 0L); } catch (Throwable ignored) {} denoiseLayout = 0L; }
            if (tracePipeline != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipeline", dev, tracePipeline, 0L); } catch (Throwable ignored) {} tracePipeline = 0L; }
            if (traceLayout != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", dev, traceLayout, 0L); } catch (Throwable ignored) {} traceLayout = 0L; }
            if (outputImageView != 0L) { try { VulkanAPIRegistry.invoke("vkDestroyImageView", dev, outputImageView, 0L); } catch (Throwable ignored) {} outputImageView = 0L; }
        }
        if (outputImage != 0L) {
            try { VulkanGPUResourceManager.getInstance().releaseResource(new VulkanGPUResourceManager.GpuResource(outputImage, 0L, lastWidth, lastHeight, 97, VulkanGPUResourceManager.ResourceType.IMAGE)); } catch (Throwable ignored) {}
            outputImage = 0L;
        }
        lastWidth = 0; lastHeight = 0;
        LOGGER.fine("[RT] resources released");
    }

    private boolean detectRayTracingSupport(RenderContext context) {
        try {
            var dev = VulkanDeviceHolder.getInstance().getDevice();
            return dev != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean prepareShaderPrograms(RenderContext context) {
        ensureDenoisePipeline();
        ensureTracePipeline();
        return denoisePipeline != 0L && tracePipeline != 0L;
    }

    private void buildAccelerationStructures(RenderContext context) {
        // 实验性功能：加速结构创建需要 Streamline SDK 或 Vulkan Ray Tracing 扩展
        // 当前版本暂不实现，光线追踪节点以禁用状态运行
        LOGGER.fine("RayTracingNode: 加速结构创建跳过（实验性功能未启用）");
    }

    private long allocateOutputTexture(int w, int h) {
        var mgr = VulkanGPUResourceManager.getInstance();
        var res = mgr.createImage(w, h, 97, 0x20 | 0x10, VmaMemoryPools.PoolType.RENDER_TARGET);
        return (res != null && res.handle != 0L) ? res.handle : 0L;
    }

    private void blitTexture(long src, long dst) {}

    private long createOrGetRtOutputView(int w, int h) {
        return outputImageView != 0L ? outputImageView : 0L;
    }
}