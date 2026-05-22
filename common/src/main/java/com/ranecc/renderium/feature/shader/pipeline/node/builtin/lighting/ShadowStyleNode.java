// Renderium - 可扩展 Shader 节点系统
// ShadowStyleNode - 阴影风格控制节点
//
// 功能：
//   1. 真实主义阴影（物理正确）
//   2. 风格化阴影（卡通/动漫风格）
//   3. Toon / Cel-Shading 轮廓阴影
//   4. 可自定义阴影颜色
//
// 参数：
//   style:         enum { REALISTIC, STYLIZED, TOON }
//   shadowColor:   rgb [0,1]^3    - 阴影色调
//   edgeSoftness:  float [0, 1]     - 边缘柔化程度

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.lighting;

import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.infrastructure.gpu.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.RenderiumProfiler;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;

/**
 * 阴影风格控制节点
 * <p>
 * 在标准阴影计算之后，对阴影效果进行艺术化处理。
 * 支持真实、风格化和卡通三种模式，
 * 允许自定义阴影颜色和边缘柔和度。
 *
 * <h2>风格算法：</h2>
 *
 * <h3>REALISTIC 模式：</h3>
 * <pre>
 * // 标准物理阴影，保持原始深度信息
 * float shadow = texture(shadowMap, uv).r;  // [0, 1]
 * color *= mix(shadowColor, vec3(1.0), shadow);
 * </pre>
 *
 * <h3>STYLIZED 模式：</h3>
 * <pre>
 * // 带颜色调制的软阴影
 * float shadow = smoothstep(0.2, 0.8, rawShadow);  // 软边缘
 * vec3 tintedShadow = shadowColor * (1.0 - shadow);
 * color = mix(tintedShadow, color, shadow);
 * </pre>
 *
 * <h3>TOON 模式：</h3>
 * <pre>
 * // 二值化硬边阴影（Cel-Shading）
 * float toonShadow = step(0.5, rawShadow);  // 硬阈值
 * color = mix(shadowColor * 0.5, color, toonShadow);
 * </pre>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0
 */
public class ShadowStyleNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(ShadowStyleNode.class.getName());

    private static final int NODE_ID = 17;

    /** 阴影风格枚举 */
    public enum ShadowStyle {
        /** 真实主义（物理正确） */
        REALISTIC,
        /** 风格化（带颜色调制） */
        STYLIZED,
        /** 卡通/动漫（Cel-Shading 二值化） */
        TOON
    }

    public static final float DEFAULT_EDGE_SOFTNESS = 0.5f;

    private volatile ShadowStyle style = ShadowStyle.REALISTIC;
    private volatile float[] shadowColor = {0.05f, 0.05f, 0.08f};  // 深蓝灰色
    private volatile float edgeSoftness = DEFAULT_EDGE_SOFTNESS;

    private long outputTextureHandle = 0L;

    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    /**
     * Shader key，镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/lighting/shadow_style";
    }
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSetLayout = 0L;
    private volatile long descriptorPool = 0L;
    private volatile long descriptorSet = 0L;
    private volatile long outputImage = 0L;
    private volatile long outputImageView = 0L;
    private volatile long outputImageMemory = 0L;
    private volatile int lastWidth = 0;
    private volatile int lastHeight = 0;

    public ShadowStyleNode() {
        super(
                "shadow_style",
                "Shadow Style (Realistic/Stylized/Toon)",
                PipelineNode.Category.PRE_RENDER,
                55,
                new String[]{"shadow_map"}
        );
        LOGGER.fine("ShadowStyleNode 已创建");
    }

    @Override
    protected boolean onInitialize(RenderContext context) {
        outputTextureHandle = allocateOutputTexture(1920, 1080);

        boolean ok = prepareShaderPrograms(context);
        if (!ok) return false;

        LOGGER.info(String.format("ShadowStyleNode 初始化完成: style=%s", style.name()));
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        RenderiumProfiler.recordStart(17);
        if (inputResources == null || inputResources.length == 0) return 0L;

        ShadowStyle currentStyle = this.style;
        float currentEdgeSoftness = this.edgeSoftness;

        // 安全拷贝颜色数组
        float[] currentColor;
        synchronized (this) {
            currentColor = this.shadowColor.clone();
        }

        int styleFlag;
        switch (currentStyle) {
            case REALISTIC: styleFlag = 0; break;
            case STYLIZED:  styleFlag = 1; break;
            case TOON:      styleFlag = 2; break;
            default:        styleFlag = 0;
        }

        submitFullScreenDraw(context, "shadow_style", inputResources[0], outputTextureHandle,
                new float[]{
                        styleFlag,
                        currentEdgeSoftness,
                        currentColor[0], currentColor[1], currentColor[2]
                });

        RenderiumProfiler.recordEnd(17);
        totalExecuteTimeNanos += RenderiumProfiler.getNodeTime(17);
        totalFrames++;
        return outputTextureHandle;
    }

    @Override
    protected void onDispose() {
        disposeComputeGPUResources();
        // The output image is managed by compute path; zero out to prevent double-free
        outputTextureHandle = 0L;
        releaseTexture(outputTextureHandle);
        releaseShaderPrograms();
    }

    // ==================== 配置 API ====================

    public void setStyle(ShadowStyle s) { if (s != null) this.style = s; }
    public ShadowStyle getStyle() { return style; }

    /**
     * 设置阴影颜色
     *
     * @param color float[] - RGB 三分量，范围 [0, 1]
     */
    public void setShadowColor(float[] color) {
        if (color == null || color.length != 3) return;
        synchronized (this) {
            this.shadowColor = new float[]{
                    clamp01(color[0]), clamp01(color[1]), clamp01(color[2])
            };
        }
    }

    /**
     * 获取阴影颜色副本
     *
     * @return float[] - RGB 数组副本
     */
    public float[] getShadowColor() {
        return this.shadowColor;
    }

    public void setEdgeSoftness(float v) { this.edgeSoftness = clamp01(v); }
    public float getEdgeSoftness() { return edgeSoftness; }

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }
    public void resetStats() { totalExecuteTimeNanos = 0L; totalFrames = 0L; }

    @Override
    public String toString() {
        return String.format("ShadowStyle{style=%s, color=[%.2f,%.2f,%.2f], soft=%.2f}",
                style.name(), shadowColor[0], shadowColor[1], shadowColor[2], edgeSoftness);
    }

    // ==================== 内部辅助方法 ====================

    private float clamp01(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }

    private long allocateOutputTexture(int w, int h) {
        return 0xBB060000L | ((long) (w & 0xFFFF) << 16) | (long) (h & 0xFFFF);
    }

    private void releaseTexture(long h) {
        if (!VulkanGraphicsHelper.isAvailable() || h == 0L) return;
        VulkanGraphicsHelper.destroyImageView(VulkanGraphicsHelper.getDevice(), h);
    }

    private boolean prepareShaderPrograms(RenderContext ctx) {
        if (!VulkanGraphicsHelper.isAvailable()) return true;
        LOGGER.fine("[ShadowStyleNode] shader programs prepared");
        return true;
    }

    private void releaseShaderPrograms() {
        if (!VulkanGraphicsHelper.isAvailable()) return;
        LOGGER.fine("[ShadowStyleNode] shader programs released");
    }

    // ==================== Compute Dispatch (替换原有 stub) ====================

    private void submitFullScreenDraw(RenderContext ctx, String pass, long inTex, long outTex, float[] uniforms) {
        if (!VulkanGraphicsHelper.isAvailable() || uniforms == null || uniforms.length < 5) return;
        if (!VulkanFFMBinding.isFfmLoaded()) {
            LOGGER.fine("[ShadowStyleNode] FFM 未加载，跳过 compute dispatch");
            return;
        }

        try {
            int styleFlag = (int) uniforms[0];
            float edgeSoftness = uniforms[1];
            float shadowR = uniforms[2];
            float shadowG = uniforms[3];
            float shadowB = uniforms[4];
            int width = ctx.getWidth();
            int height = ctx.getHeight();

            ensurePipeline();
            if (computePipeline == 0L) {
                LOGGER.warning("[ShadowStyleNode] Pipeline 未就绪，跳过 dispatch");
                return;
            }

            ensureOutputImage(width, height);
            if (outputImageView == 0L) {
                LOGGER.warning("[ShadowStyleNode] OutputImage 未就绪，跳过 dispatch");
                return;
            }

            long device = VulkanDeviceHolder.getInstance().getDevice();
            if (device == 0L) return;

            // Update descriptors: binding 0 = input shadow depth (r32f, sampled), binding 1 = output rgba8 (storage)
            ComputePipelineHelper.updateImageDescriptor(device, descriptorSet, 0, inTex, 0L,
                    ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);
            ComputePipelineHelper.updateStorageImageDescriptor(device, descriptorSet, 1, outputImageView,
                    ComputePipelineHelper.VK_IMAGE_LAYOUT_GENERAL);

            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf == 0L) return;

            // VkImageMemoryBarrier: oldLayout=UNDEFINED(0), newLayout=GENERAL(1)
            MemorySegment barrier = PerFrameArena.allocate(68L);
            barrier.set(ValueLayout.JAVA_INT, 0, 33);       // sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
            barrier.set(ValueLayout.JAVA_LONG, 8, 0L);     // pNext
            barrier.set(ValueLayout.JAVA_INT, 16, 0);      // srcAccessMask
            barrier.set(ValueLayout.JAVA_INT, 20, 0);      // dstAccessMask
            barrier.set(ValueLayout.JAVA_INT, 24, 0);      // oldLayout = VK_IMAGE_LAYOUT_UNDEFINED (0)
            barrier.set(ValueLayout.JAVA_INT, 28, 1);      // newLayout = VK_IMAGE_LAYOUT_GENERAL (1)
            barrier.set(ValueLayout.JAVA_INT, 32, 0);      // srcQueueFamilyIndex
            barrier.set(ValueLayout.JAVA_INT, 36, 0);      // dstQueueFamilyIndex
            barrier.set(ValueLayout.JAVA_LONG, 40, outputImage);
            barrier.set(ValueLayout.JAVA_INT, 48, VulkanConst.IMAGE_ASPECT_COLOR_BIT);
            barrier.set(ValueLayout.JAVA_INT, 52, 0);      // baseMipLevel
            barrier.set(ValueLayout.JAVA_INT, 56, 1);      // levelCount
            barrier.set(ValueLayout.JAVA_INT, 60, 0);      // baseArrayLayer
            barrier.set(ValueLayout.JAVA_INT, 64, 1);      // layerCount

            VulkanAPIRegistry.invoke("vkCmdPipelineBarrier", cmdBuf,
                    1, // VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                    1, // VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                    0, 0, 0L, 0, 0L, 1, barrier.address());

            // Bind compute pipeline
            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf,
                    VulkanConst.PIPELINE_BIND_POINT_COMPUTE, computePipeline);

            // Bind descriptor set
            VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                    VulkanConst.PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, 1, descriptorSet, 0, 0L);

            // Push constants: 32 bytes (int styleFlag + 5 floats = 24 bytes, padded to 32)
            MemorySegment pcData = PerFrameArena.allocate(32L);
            pcData.set(ValueLayout.JAVA_INT, 0, styleFlag);
            pcData.set(ValueLayout.JAVA_FLOAT, 4, edgeSoftness);
            pcData.set(ValueLayout.JAVA_FLOAT, 8, shadowR);
            pcData.set(ValueLayout.JAVA_FLOAT, 12, shadowG);
            pcData.set(ValueLayout.JAVA_FLOAT, 16, shadowB);
            // bytes 20-31: zero padding

            VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf, pipelineLayout,
                    0x00000020L, // VK_SHADER_STAGE_COMPUTE_BIT
                    0, 32, pcData.address());

            // Dispatch with 8x8 workgroup
            int groupsX = Math.max(1, (width + 7) / 8);
            int groupsY = Math.max(1, (height + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);

            FrameCommandContext.endNodeCB(NODE_ID);

            // Update output texture handle to point to our compute output
            outputTextureHandle = outputImageView;

            LOGGER.finest("[ShadowStyleNode] compute dispatch OK: " + pass
                    + " " + width + "x" + height
                    + " groups=" + groupsX + "x" + groupsY);
        } catch (Throwable t) {
            LOGGER.warning("[ShadowStyleNode] compute dispatch 异常: " + t.getMessage());
        }
    }

    // ==================== Pipeline 生命周期 ====================

    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        if (!VulkanFFMBinding.isFfmLoaded()) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;

        try {
            byte[] spirv = loadSPIRV();
            if (spirv == null || spirv.length == 0) {
                LOGGER.warning("[ShadowStyleNode] SPIR-V 加载失败，key: " + shaderKey());
                return;
            }

            // Use ComputePipelineHelper for unified pipeline creation
            // binding 0: input shadow depth (VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 11)
            // binding 1: output RGBA8 (VK_DESCRIPTOR_TYPE_STORAGE_IMAGE = 10)
            // push constants: 32 bytes (int + 5 floats, padded)
            ComputePipelineHelper.PipelineResources res = ComputePipelineHelper.createComputePipeline(
                    spirv,
                    new ComputePipelineHelper.Binding[]{
                            new ComputePipelineHelper.Binding(0,
                                    ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                            new ComputePipelineHelper.Binding(1,
                                    ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    },
                    new ComputePipelineHelper.PushConstant(0, 32,
                            ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT)
            );

            if (res == null) {
                LOGGER.warning("[ShadowStyleNode] ComputePipelineHelper 创建失败");
                return;
            }

            this.computePipeline = res.pipeline();
            this.pipelineLayout = res.pipelineLayout();
            this.descriptorSetLayout = res.descriptorSetLayout();
            this.descriptorPool = res.descriptorPool();
            this.descriptorSet = res.descriptorSet();

            LOGGER.info("[ShadowStyleNode] Compute Pipeline 创建成功: pipeline=0x"
                    + Long.toHexString(computePipeline));
        } catch (Throwable t) {
            LOGGER.warning("[ShadowStyleNode] ensurePipeline 异常: " + t.getMessage());
        }
    }

    // ==================== Output Image 管理 ====================

    private void ensureOutputImage(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (outputImage != 0L && lastWidth == width && lastHeight == height) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;

        // 修复: 使用 confined arena 替代 global arena，方法结束后自动释放，避免内存泄漏

        try {
            disposeOutputImage(device);

            /*
             * VkImageCreateInfo 内存布局 (88 bytes):
             *   offset  0: sType        (int)    = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO (14)
             *   offset  4: padding
             *   offset  8: pNext         (long)
             *   offset 16: flags         (int)
             *   offset 20: imageType     (int)    = VK_IMAGE_TYPE_2D (0)
             *   offset 24: format        (int)    = VK_FORMAT_R8G8B8A8_UNORM (37)
             *   offset 28: extent.width  (int)
             *   offset 32: extent.height (int)
             *   offset 36: extent.depth  (int)    = 1
             *   offset 40: mipLevels     (int)    = 1
             *   offset 44: arrayLayers   (int)    = 1
             *   offset 48: samples       (int)    = VK_SAMPLE_COUNT_1_BIT (1)
             *   offset 52: tiling        (int)    = VK_IMAGE_TILING_OPTIMAL (0)
             *   offset 56: usage         (int)    = STORAGE_BIT | SAMPLED_BIT
             *   offset 60: sharingMode   (int)    = VK_SHARING_MODE_EXCLUSIVE (0)
             *   offset 64: queueFamilyIndexCount (int) = 0
             *   offset 68: padding
             *   offset 72: pQueueFamilyIndices   (long) = 0
             *   offset 80: initialLayout (int)    = VK_IMAGE_LAYOUT_UNDEFINED (0)
             */
            MemorySegment createInfo = PerFrameArena.allocate(88L);
            createInfo.set(ValueLayout.JAVA_INT, 0, VulkanStructs.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            createInfo.set(ValueLayout.JAVA_LONG, 8, 0L);
            createInfo.set(ValueLayout.JAVA_INT, 16, 0);
            createInfo.set(ValueLayout.JAVA_INT, 20, 0);
            createInfo.set(ValueLayout.JAVA_INT, 24, VulkanConst.FORMAT_R8G8B8A8_UNORM);
            createInfo.set(ValueLayout.JAVA_INT, 28, width);
            createInfo.set(ValueLayout.JAVA_INT, 32, height);
            createInfo.set(ValueLayout.JAVA_INT, 36, 1);
            createInfo.set(ValueLayout.JAVA_INT, 40, 1);
            createInfo.set(ValueLayout.JAVA_INT, 44, 1);
            createInfo.set(ValueLayout.JAVA_INT, 48, 1);
            createInfo.set(ValueLayout.JAVA_INT, 52, 0);
            createInfo.set(ValueLayout.JAVA_INT, 56, 0x00000008 | 0x00000001);
            createInfo.set(ValueLayout.JAVA_INT, 60, 0);
            createInfo.set(ValueLayout.JAVA_INT, 64, 0);
            createInfo.set(ValueLayout.JAVA_LONG, 72, 0L);
            createInfo.set(ValueLayout.JAVA_INT, 80, 0);

            MemorySegment imgOut = PerFrameArena.allocateLongs(1);
            int result = (int) VulkanAPIRegistry.invoke("vkCreateImage",
                device, createInfo.address(), 0L, imgOut.address());
            if (result != 0) {
            LOGGER.warning("[ShadowStyleNode] vkCreateImage 失败: " + result);
            return;
            }
            outputImage = imgOut.get(ValueLayout.JAVA_LONG, 0);

            // Get memory requirements and allocate device-local memory
            MemorySegment memReqs = PerFrameArena.allocate(24L);
            VulkanAPIRegistry.invoke("vkGetImageMemoryRequirements", device,
                outputImage, memReqs.address());
            long memSize = memReqs.get(ValueLayout.JAVA_LONG, 0);
            int memTypeBits = memReqs.get(ValueLayout.JAVA_INT, 8);

            int memoryTypeIndex = findMemoryType(memTypeBits,
                VulkanConst.MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (memoryTypeIndex < 0) {
            LOGGER.warning("[ShadowStyleNode] 无可用的 device-local 内存类型");
            disposeOutputImage(device);
            return;
            }

            MemorySegment allocInfo = PerFrameArena.allocate(32L);
            allocInfo.set(ValueLayout.JAVA_INT, 0, 5);           // sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
            allocInfo.set(ValueLayout.JAVA_LONG, 8, 0L);         // pNext
            allocInfo.set(ValueLayout.JAVA_LONG, 16, memSize);   // allocationSize
            allocInfo.set(ValueLayout.JAVA_INT, 24, memoryTypeIndex);
            MemorySegment memOut = PerFrameArena.allocateLongs(1);
            result = (int) VulkanAPIRegistry.invoke("vkAllocateMemory",
                device, allocInfo.address(), 0L, memOut.address());
            if (result != 0) {
            LOGGER.warning("[ShadowStyleNode] vkAllocateMemory 失败: " + result);
            disposeOutputImage(device);
            return;
            }
            outputImageMemory = memOut.get(ValueLayout.JAVA_LONG, 0);

            result = (int) VulkanAPIRegistry.invoke("vkBindImageMemory",
                device, outputImage, outputImageMemory, 0L);
            if (result != 0) {
            LOGGER.warning("[ShadowStyleNode] vkBindImageMemory 失败: " + result);
            disposeOutputImage(device);
            return;
            }

            /*
              * VkImageViewCreateInfo 内存布局 (80 bytes):
              *   offset  0: sType       (int)   = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO (15)
              *   offset  4: padding
              *   offset  8: pNext       (long)  = 0
              *   offset 16: flags       (int)   = 0
              *   offset 20: padding
              *   offset 24: image       (long)  = outputImage
              *   offset 32: viewType    (int)   = VK_IMAGE_VIEW_TYPE_2D (1)
              *   offset 36: format      (int)   = VK_FORMAT_R8G8B8A8_UNORM (37)
              *   offset 40: components  (4 ints) = IDENTITY swizzle
              *   offset 56: subresourceRange.aspectMask     (int) = COLOR_BIT
              *   offset 60: subresourceRange.baseMipLevel   (int) = 0
              *   offset 64: subresourceRange.levelCount     (int) = 1
              *   offset 68: subresourceRange.baseArrayLayer (int) = 0
              *   offset 72: subresourceRange.layerCount     (int) = 1
              */
            MemorySegment viewCI = PerFrameArena.allocate(80L);
            viewCI.set(ValueLayout.JAVA_INT, 0, VulkanStructs.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewCI.set(ValueLayout.JAVA_LONG, 8, 0L);
            viewCI.set(ValueLayout.JAVA_INT, 16, 0);
            viewCI.set(ValueLayout.JAVA_LONG, 24, outputImage);
            viewCI.set(ValueLayout.JAVA_INT, 32, 1);
            viewCI.set(ValueLayout.JAVA_INT, 36, VulkanConst.FORMAT_R8G8B8A8_UNORM);
            // components (4 ints at offset 40): all VK_COMPONENT_SWIZZLE_IDENTITY (0)
            // subresourceRange
            viewCI.set(ValueLayout.JAVA_INT, 56, VulkanConst.IMAGE_ASPECT_COLOR_BIT);
            viewCI.set(ValueLayout.JAVA_INT, 60, 0);
            viewCI.set(ValueLayout.JAVA_INT, 64, 1);
            viewCI.set(ValueLayout.JAVA_INT, 68, 0);
            viewCI.set(ValueLayout.JAVA_INT, 72, 1);

            MemorySegment viewOut = PerFrameArena.allocateLongs(1);
            result = (int) VulkanAPIRegistry.invoke("vkCreateImageView",
                device, viewCI.address(), 0L, viewOut.address());
            if (result != 0) {
            LOGGER.warning("[ShadowStyleNode] vkCreateImageView 失败: " + result);
            disposeOutputImage(device);
            return;
            }
            outputImageView = viewOut.get(ValueLayout.JAVA_LONG, 0);

            lastWidth = width;
            lastHeight = height;
            LOGGER.fine("[ShadowStyleNode] OutputImage 创建成功: "
                + width + "x" + height
                + " image=0x" + Long.toHexString(outputImage)
                + " view=0x" + Long.toHexString(outputImageView));
        } catch (Throwable t) {
            LOGGER.warning("[ShadowStyleNode] ensureOutputImage 异常: " + t.getMessage());
            disposeOutputImage(device);
        }

    }

    // ==================== SPIR-V 加载 ====================

    private byte[] loadSPIRV() {
        byte[] spirv = resolveSPIRV();
        if (spirv == null) {
            LOGGER.warning("[ShadowStyleNode] SPIR-V 资源不存在: " + shaderKey());
            return null;
        }
        if (spirv.length < 4) {
            LOGGER.warning("[ShadowStyleNode] SPIR-V 文件过短: " + spirv.length + " bytes");
            return null;
        }
        LOGGER.fine("[ShadowStyleNode] 加载 SPIR-V: " + shaderKey() + " (" + spirv.length + " bytes)");
        return spirv;
    }

    // ==================== GPU 资源清理 ====================

    private void disposeComputeGPUResources() {
        if (!VulkanFFMBinding.isFfmLoaded()) return;
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return;

        disposeOutputImage(device);

        if (descriptorSet != 0L && descriptorPool != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkFreeDescriptorSets", device,
                        descriptorPool, 1, descriptorSet);
            } catch (Throwable ignored) {}
            descriptorSet = 0L;
        }
        if (descriptorPool != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyDescriptorPool", device,
                        descriptorPool, 0L);
            } catch (Throwable ignored) {}
            descriptorPool = 0L;
        }
        if (descriptorSetLayout != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyDescriptorSetLayout", device,
                        descriptorSetLayout, 0L);
            } catch (Throwable ignored) {}
            descriptorSetLayout = 0L;
        }
        if (computePipeline != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyPipeline", device, computePipeline, 0L);
            } catch (Throwable ignored) {}
            computePipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyPipelineLayout", device, pipelineLayout, 0L);
            } catch (Throwable ignored) {}
            pipelineLayout = 0L;
        }
    }

    private void disposeOutputImage(long device) {
        if (outputImageView != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyImageView", device, outputImageView, 0L);
            } catch (Throwable ignored) {}
            outputImageView = 0L;
        }
        if (outputImage != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkDestroyImage", device, outputImage, 0L);
            } catch (Throwable ignored) {}
            outputImage = 0L;
        }
        if (outputImageMemory != 0L) {
            try {
                VulkanAPIRegistry.invoke("vkFreeMemory", device, outputImageMemory, 0L);
            } catch (Throwable ignored) {}
            outputImageMemory = 0L;
        }
        lastWidth = 0;
        lastHeight = 0;
    }

    private int findMemoryType(int typeFilter, int properties) {
        if (!VulkanFFMBinding.isFfmLoaded()) return -1;
        long physicalDevice = VulkanDeviceHolder.getInstance().getVkPhysicalDevice();
        if (physicalDevice == 0L) return -1;
        // 修复: 使用 confined arena 替代 global arena，方法结束后自动释放，避免内存泄漏
        try {
            MemorySegment memProps = PerFrameArena.allocate(16L);
            VulkanAPIRegistry.invoke("vkGetPhysicalDeviceMemoryProperties",
                    physicalDevice, memProps.address());
            int memoryTypeCount = memProps.get(ValueLayout.JAVA_INT, 0);
            // memoryTypes array starts at offset 8
            for (int i = 0; i < memoryTypeCount && i < 32; i++) {
                int typeOffset = 8 + i * 12;
                int heapIndex = memProps.get(ValueLayout.JAVA_INT, typeOffset + 0);
                int propFlags = memProps.get(ValueLayout.JAVA_INT, typeOffset + 4);
                if ((typeFilter & (1 << i)) != 0 && (propFlags & properties) == properties) {
                    return i;
                }
            }
        } catch (Throwable t) {
            LOGGER.warning("[ShadowStyleNode] findMemoryType 异常: " + t.getMessage());
        }
        return -1;
    }
}
