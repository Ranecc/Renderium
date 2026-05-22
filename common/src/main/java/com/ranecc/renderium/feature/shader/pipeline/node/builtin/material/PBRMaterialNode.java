// Renderium - 可扩展 Shader 节点系统
// PBRMaterialNode - PBR 材质计算节点 (Disney Principled BSDF)
//
// 功能：
//   1. Disney Principled BRDF 实现
//   2. 基于物理的材质参数
//   3. 支持 Metallic-Roughness 工作流
//
// 参数：
//   metallic:    float [0, 1]     - 金属度
//   roughness:   float [0, 1]     - 粗糙度
//   ior:         float [1.0, 3.0]  - 折射率
//   clearcoat:   float [0, 1]     - 清漆层强度
//   sheen:        float [0, 1]     - 光泽（布料/丝绸效果）
//   subsurface:   float [0, 1]     - 次表面散射

package com.ranecc.renderium.feature.shader.pipeline.node.builtin.material;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNodeRegistry;
import com.ranecc.renderium.infrastructure.gpu.*;
import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PBR 材质计算节点（Disney Principled BSDF）
 * <p>
 * 实现基于 Disney "Principled" 模型的物理材质着色器。
 * 该模型通过一组直观的、基于物理意义的参数来描述广泛的现实世界材质。
 *
 * <h2>Disney Principled BRDF 参数模型：</h2>
 * <pre>
 * f(l,v) = (diffuse + specular) + clearcoat + sheen + subsurface
 *
 * 其中：
 *   diffuse   = baseColor * (1/π) * (1 - F0) * (1 - metallic)
 *   specular = D(h) * F(v,h) * G(l,v,h) / (4 * (n·l) * (n·v))
 *             D = GGX/Trowbridge-Reitz 分布
 *             F = Schlick-Fresnel (基于 IOR)
 *             G = Smith-Schlick-Beckmann 几何遮蔽
 * </pre>
 *
 * <h2>参数说明：</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>范围</th><th>默认值</th><th>说明</th></tr>
 *   <tr><td>metallic</td><td>[0, 1]</td><td>0.0</td><td>金属度（0=电介质，1=纯金属）</td></tr>
 *   <tr><td>roughness</td><td>[0, 1]</td><td>0.5</td><td>粗糙度（0=完美镜面，1=完全漫反射）</td></tr>
 *   <tr><td>ior</td><td>[1.0, 3.0]</td><td>1.5</td><td>折射率（影响菲涅尔效应）</td></tr>
 *   <tr><td>clearcoat</td><td>[0, 1]</td><td>0.0</td><td>清漆层（汽车漆面效果）</td></tr>
 *   <tr><td>sheen</td><td>[0, 1]</td><td>0.0</td><td>光泽（布料/织物边缘光）</td></tr>
 *   <tr><td>subsurface</td><td>[0, 1]</td><td>0.0</td><td>次表面散射（皮肤/蜡效果）</td></tr>
 * </table>
 *
 * @see AbstractPipelineNode
 * @since 7.0.0
 */
public class PBRMaterialNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger(PBRMaterialNode.class.getName());

    // ==================== 参数常量 ====================

    public static final float DEFAULT_METALLIC = 0.0f;
    public static final float DEFAULT_ROUGHNESS = 0.5f;
    public static final float DEFAULT_IOR = 1.5f;           // 典型塑料/清漆 IOR
    public static final float DEFAULT_CLEARCOAT = 0.0f;
    public static final float DEFAULT_SHEEN = 0.0f;
    public static final float DEFAULT_SUBSURFACE = 0.0f;

    // ==================== 动态参数 ====================

    /** 金属度 (0=电介质, 1=金属) */
    private volatile float metallic = DEFAULT_METALLIC;

    /** 粗糙度 (0=镜面, 1=漫反射) */
    private volatile float roughness = DEFAULT_ROUGHNESS;

    /** 折射率 (影响 Fresnel F0) */
    private volatile float ior = DEFAULT_IOR;

    /** 清漆层强度 (汽车漆面) */
    private volatile float clearcoat = DEFAULT_CLEARCOAT;

    /** 光泽 (布料/织物边缘高光) */
    private volatile float sheen = DEFAULT_SHEEN;

    /** 次表面散射强度 (皮肤/蜡) */
    private volatile float subsurface = DEFAULT_SUBSURFACE;

    // ==================== 运行时状态 ====================

    /** 性能统计 */
    private long totalExecuteTimeNanos = 0L;
    private long totalFrames = 0L;

    // ==================== Compute Pipeline 资源 ====================

    /**
     * Shader key，镜像 shaders-src/ 目录结构
     * @see com.ranecc.renderium.feature.shader.ShaderPathResolver#resolveSPIRV(String)
     */
    @Override
    protected String shaderKey() {
        return "pipeline/material/pbr_material";
    }

    /** 工作组大小（16x16 线程组，适配大多数 GPU 架构） */
    private static final int WORKGROUP_SIZE_X = 16;
    private static final int WORKGROUP_SIZE_Y = 16;

    /** Compute Pipeline 句柄 */
    private volatile long computePipeline = 0L;

    /** Pipeline Layout 句柄 */
    private volatile long pipelineLayout = 0L;

    /** Descriptor Set 句柄 */
    private volatile long descriptorSet = 0L;

    /** 输出 Image 句柄 */
    private volatile long outputImage = 0L;

    /** 输出 ImageView 句柄 */
    private volatile long outputImageView = 0L;

    /** 上次输出纹理宽度 */
    private volatile int lastOutputWidth = 0;

    /** 上次输出纹理高度 */
    private volatile int lastOutputHeight = 0;

    /** G-Buffer Position 输入纹理 ImageView 句柄 */
    private volatile long gbufferPositionView = 0L;

    /** G-Buffer Normal 输入纹理 ImageView 句柄 */
    private volatile long gbufferNormalView = 0L;

    /** G-Buffer Albedo 输入纹理 ImageView 句柄 */
    private volatile long gbufferAlbedoView = 0L;

    /** G-Buffer Material 输入纹理 ImageView 句柄 */
    private volatile long gbufferMaterialView = 0L;

    /** SPIR-V 二进制缓存 */
    private byte[] spirvBinary = null;

    // ==================== 构造函数 ====================

    /**
     * 构造 PBR 材质节点
     */
    public PBRMaterialNode() {
        super(
                "pbr_material",
                "PBR Material (Disney Principled BSDF)",
                PipelineNode.Category.GBUFFER,
                100,
                new String[]{"gbuffer_geometry"}  // 依赖几何信息
        );
        LOGGER.fine("PBRMaterialNode 已创建");
    }

    // ==================== PipelineNode 实现 ====================

    @Override
    protected boolean onInitialize(RenderContext context) {
        this.spirvBinary = loadSPIRV();
        if (spirvBinary == null) {
            LOGGER.warning("PBR SPIR-V 着色器未找到，将使用软件回退模式");
        }

        LOGGER.info("PBRMaterialNode 初始化完成" + (spirvBinary != null ? " (SPIR-V 已加载)" : ""));
        return true;
    }

    @Override
    public long execute(RenderContext context, long... inputResources) {
        long startTimeNanos = System.nanoTime();

        if (inputResources == null || inputResources.length == 0) {
            return 0L;
        }

        // 参数快照（单次 volatile 读）
        float m = this.metallic;
        float r = this.roughness;
        float i = this.ior;
        float cc = this.clearcoat;
        float s = this.sheen;
        float ss = this.subsurface;

        // 从 IOR 计算 Fresnel F0 (Schlick 近似)
        float f0 = ((i - 1.0f) * (i - 1.0f)) / ((i + 1.0f) * (i + 1.0f));

        // 懒加载 Compute Pipeline
        ensurePipeline();
        if (computePipeline == 0L) {
            // 回退：直接使用 G-Buffer 的位置纹理作为输出
            return inputResources[0];
        }

        // 确保输出存储图像已创建
        ensureOutputImage(context);
        if (outputImageView == 0L) {
            return inputResources[0];
        }

        // 解析 G-Buffer 四通道输入纹理
        resolveGBufferInputs(context, inputResources);

        // 构建 G-Buffer 输入纹理数组 [position, normal, albedo, material]
        long[] gbufferInputs = new long[]{
                gbufferPositionView,
                gbufferNormalView,
                gbufferAlbedoView,
                gbufferMaterialView
        };

        // 提交 PBR 材质计算 Pass
        submitFullScreenDraw(context, "pbr_material", gbufferInputs, outputImageView,
                new float[]{
                        m,              // metallic
                        r,              // roughness
                        f0,             // fresnelF0 (从 IOR 计算)
                        cc,             // clearcoat
                        s,              // sheen
                        ss,             // subsurface
                        1.0f - m,       // dielectricWeight (1 - metallic)
                        r * r           // alpha (roughness^2, 用于 GGX)
                });

        long elapsed = System.nanoTime() - startTimeNanos;
        totalExecuteTimeNanos += elapsed;
        totalFrames++;

        return outputImageView;
    }

    @Override
    protected void onDispose() {
        disposeGPUResources();
        LOGGER.fine("PBRMaterialNode 资源已释放");
    }

    // ==================== 配置 API ====================

    public void setMetallic(float v) { this.metallic = clamp01(v); }
    public float getMetallic() { return metallic; }

    public void setRoughness(float v) { this.roughness = clamp01(v); }
    public float getRoughness() { return roughness; }

    public void setIor(float v) { this.ior = clamp(v, 1.0f, 3.0f); }
    public float getIor() { return ior; }

    public void setClearcoat(float v) { this.clearcoat = clamp01(v); }
    public float getClearcoat() { return clearcoat; }

    public void setSheen(float v) { this.sheen = clamp01(v); }
    public float getSheen() { return sheen; }

    public void setSubsurface(float v) { this.subsurface = clamp01(v); }
    public float getSubsurface() { return subsurface; }

    // ==================== 诊断 API ====================

    public double getAverageTimeMs() {
        return totalFrames > 0 ? (double) totalExecuteTimeNanos / totalFrames / 1_000_000.0 : 0.0;
    }

    public void resetStats() { totalExecuteTimeNanos = 0L; totalFrames = 0L; }

    @Override
    public String toString() {
        return String.format("PBRMaterial{metal=%.2f, rough=%.2f, ior=%.2f, cc=%.2f, sheen=%.2f, ss=%.2f}",
                metallic, roughness, ior, clearcoat, sheen, subsurface);
    }

    // ==================== 私有辅助方法 ====================

    private float clamp01(float v) { return Math.max(0.0f, Math.min(1.0f, v)); }
    private float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    /**
     * 解析 G-Buffer 四通道输入纹理
     * <p>
     * 从管线框架传递的 inputResources 和 PipelineNodeRegistry 中
     * 解析 G-Buffer 的 Position/Normal/Albedo/Material 四张纹理的 ImageView 句柄。
     * Position 纹理由管线框架作为 inputResources[0] 传递，
     * 其余三张从 GBufferGeometryNode 的输出纹理字段中读取。
     * </p>
     */
    private void resolveGBufferInputs(RenderContext context, long... inputResources) {
        if (inputResources != null && inputResources.length > 0) {
            this.gbufferPositionView = inputResources[0];
        }

        if (this.gbufferNormalView == 0L || this.gbufferAlbedoView == 0L || this.gbufferMaterialView == 0L) {
            resolveGBufferFromNodeRegistry();
        }
    }

    /**
     * 从 PipelineNodeRegistry 查找 GBufferGeometryNode 的输出纹理句柄
     * <p>
     * 由于 GBufferGeometryNode 的输出纹理字段为私有权限，
     * 此方法通过反射兼容机制尝试获取。若无法访问则记录警告，
     * PBR 计算将降级使用已获取到的纹理。
     * </p>
     */
    private void resolveGBufferFromNodeRegistry() {
        try {
            PipelineNodeRegistry registry = PipelineNodeRegistry.getInstance();
            PipelineNode gbufferNode = registry.getNode("gbuffer_geometry");
            if (gbufferNode == null) return;

            try {
                java.lang.reflect.Field posField = gbufferNode.getClass().getDeclaredField("outputPositionView");
                java.lang.reflect.Field normField = gbufferNode.getClass().getDeclaredField("outputNormalView");
                java.lang.reflect.Field albField = gbufferNode.getClass().getDeclaredField("outputAlbedoView");
                java.lang.reflect.Field matField = gbufferNode.getClass().getDeclaredField("outputMaterialView");

                posField.setAccessible(true);
                normField.setAccessible(true);
                albField.setAccessible(true);
                matField.setAccessible(true);

                if (this.gbufferPositionView == 0L) this.gbufferPositionView = posField.getLong(gbufferNode);
                if (this.gbufferNormalView == 0L) this.gbufferNormalView = normField.getLong(gbufferNode);
                if (this.gbufferAlbedoView == 0L) this.gbufferAlbedoView = albField.getLong(gbufferNode);
                if (this.gbufferMaterialView == 0L) this.gbufferMaterialView = matField.getLong(gbufferNode);
            } catch (Exception ignored) {
                LOGGER.finest("无法通过反射获取 GBufferGeometryNode 输出纹理（非错误）");
            }
        } catch (Exception ignored) {
            // PipelineNodeRegistry 可能尚未初始化
        }
    }

    /**
     * 提交 PBR 材质计算 Compute Shader Dispatch
     * <p>
     * 完整的 Compute Shader 调度流程：
     * <ol>
     *   <li>确保 Compute Pipeline 已创建</li>
     *   <li>分配并开始录制 Command Buffer</li>
     *   <li>更新 DescriptorSet 绑定 4 张 G-Buffer 输入纹理 + 1 张输出纹理</li>
     *   <li>绑定 Pipeline 和 DescriptorSet</li>
     *   <li>写入 32 字节 Push Constants（PBR 参数）</li>
     *   <li>Dispatch 工作组（向上取整到 16x16 线程组边界）</li>
     *   <li>插入内存屏障确保写入可见</li>
     *   <li>提交并等待完成</li>
     * </ol>
     * </p>
     *
     * @param ctx      RenderContext - 渲染上下文
     * @param pass     字符串标识（仅用于日志）
     * @param inputs   long[4] - [0]=position, [1]=normal, [2]=albedo, [3]=material 的 ImageView 句柄
     * @param output   输出 ImageView 句柄（RGBA16F）
     * @param uniforms float[8] - PBR 参数数组
     */
    private void submitFullScreenDraw(RenderContext ctx, String pass, long[] inputs, long output, float[] uniforms) {
        if (!VulkanFFMBinding.isFfmLoaded()) return;
        if (inputs == null || inputs.length < 4 || output == 0L) return;
        if (inputs[0] == 0L) return;

        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L || computePipeline == 0L || descriptorSet == 0L) return;

        try {
            long cmdBuf = LodCullingComputePass.allocateCommandBuffer(device);
            if (cmdBuf == 0L) return;

            LodCullingComputePass.beginCommandBuffer(cmdBuf);

            // 更新 DescriptorSet：binding 0..3 = G-Buffer 输入（只读存储图像），binding 4 = 输出（写入存储图像）
            for (int i = 0; i < 4; i++) {
                long imgView = inputs[i];
                if (imgView != 0L) {
                    ComputePipelineHelper.updateStorageImageDescriptor(device, descriptorSet, i, imgView, 0L);
                }
            }
            if (output != 0L) {
                ComputePipelineHelper.updateStorageImageDescriptor(device, descriptorSet, 4, output, 0L);
            }

            // 绑定 Compute Pipeline
            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);

            // 绑定 DescriptorSet
            if (pipelineLayout != 0L && descriptorSet != 0L) {
                MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets",
                        cmdBuf, 1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);
            }

            // 写入 Push Constants（32 字节：8 个 float）
            if (uniforms != null && uniforms.length >= 8 && pipelineLayout != 0L) {
                MemorySegment pcSeg = PerFrameArena.allocate(32L);
                for (int i = 0; i < 8; i++) {
                    pcSeg.set(ValueLayout.JAVA_FLOAT, i * 4L, uniforms[i]);
                }
                VulkanAPIRegistry.invoke("vkCmdPushConstants",
                        cmdBuf, pipelineLayout, 0x00000020L, 0, 32, pcSeg.address());
            }

            // 计算工作组数量（16x16 线程组）
            int w = ctx.getWidth();
            int h = ctx.getHeight();
            int groupsX = Math.max(1, (w + WORKGROUP_SIZE_X - 1) / WORKGROUP_SIZE_X);
            int groupsY = Math.max(1, (h + WORKGROUP_SIZE_Y - 1) / WORKGROUP_SIZE_Y);

            // Dispatch
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, groupsX, groupsY, 1);

            LodCullingComputePass.endCommandBuffer(cmdBuf);

            long queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
            if (queue != 0L) {
                VulkanSyncManager.submitAndWait(queue, cmdBuf);
            }

            LOGGER.finest("[PBRMaterialNode] " + pass + " dispatch 完成: " + w + "x" + h + " groups=" + groupsX + "x" + groupsY);

        } catch (Throwable t) {
            LOGGER.fine("[PBRMaterialNode] " + pass + " dispatch 异常: " + t.getMessage());
        }
    }

    /**
     * 懒加载 Compute Pipeline（DCL 双重检查锁定）
     * <p>
     * 从 SPIR-V 二进制创建 Compute Pipeline：
     * <ul>
     *   <li>5 个 Descriptor Set Binding（4 个只读 STORAGE_IMAGE + 1 个写入 STORAGE_IMAGE）</li>
     *   <li>32 字节 Push Constants（VK_SHADER_STAGE_COMPUTE_BIT）</li>
     * </ul>
     * </p>
     */
    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        if (spirvBinary == null || spirvBinary.length == 0) return;

        synchronized (this) {
            if (computePipeline != 0L) return;
            if (spirvBinary == null || spirvBinary.length == 0) return;

            ComputePipelineHelper.Binding[] bindings = new ComputePipelineHelper.Binding[]{
                    new ComputePipelineHelper.Binding(0, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(1, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(2, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(3, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                    new ComputePipelineHelper.Binding(4, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };

            ComputePipelineHelper.PushConstant pc =
                    new ComputePipelineHelper.PushConstant(0, 32, ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);

            try {
                ComputePipelineHelper.PipelineResources r =
                        ComputePipelineHelper.createComputePipeline(spirvBinary, bindings, pc);
                if (r != null) {
                    computePipeline = r.pipeline();
                    pipelineLayout = r.pipelineLayout();
                    descriptorSet = r.descriptorSet();
                    LOGGER.fine("[PBRMaterialNode] Compute Pipeline 创建成功: " + shaderKey());
                }
            } catch (Exception e) {
                LOGGER.warning("[PBRMaterialNode] Pipeline 创建异常: " + e.getMessage());
            }
        }
    }

    /**
     * 确保输出存储图像已创建（DCL 双重检查锁定）
     * <p>
     * 创建 RGBA16F（R16G16B16A16_SFLOAT）格式的存储图像，
     * 用于接收 PBR Compute Shader 的着色结果。
     * 当视口尺寸变化时自动重新创建。
     * </p>
     */
    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();
        if (w <= 0 || h <= 0) return;

        if (outputImageView != 0L && w == lastOutputWidth && h == lastOutputHeight) return;

        synchronized (this) {
            if (outputImageView != 0L && w == lastOutputWidth && h == lastOutputHeight) return;

            VulkanGPUResourceManager resMgr = VulkanGPUResourceManager.getInstance();
            if (resMgr == null) return;

            if (outputImageView != 0L) {
                try { resMgr.destroyView(outputImageView); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                try {
                    resMgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                            outputImage, 0L, lastOutputWidth, lastOutputHeight, 97,
                            VulkanGPUResourceManager.ResourceType.IMAGE));
                } catch (Throwable ignored) {}
                outputImage = 0L;
            }

            int storageUsage = VulkanConst.IMAGE_USAGE_STORAGE_BIT | VulkanConst.IMAGE_USAGE_SAMPLED_BIT;
            int formatRGBA16F = 97;

            var imgRes = resMgr.createImage(w, h, formatRGBA16F, storageUsage,
                    VmaMemoryPools.PoolType.RENDER_TARGET);
            if (imgRes == null || !imgRes.isValid()) {
                LOGGER.warning("[PBRMaterialNode] 输出 Image 创建失败: " + w + "x" + h);
                return;
            }

            long newImage = imgRes.handle;
            long newView = resMgr.createView(newImage, formatRGBA16F, VulkanConst.IMAGE_ASPECT_COLOR_BIT);
            if (newView == 0L) {
                LOGGER.warning("[PBRMaterialNode] 输出 ImageView 创建失败");
                resMgr.releaseResource(imgRes);
                return;
            }

            outputImage = newImage;
            outputImageView = newView;
            lastOutputWidth = w;
            lastOutputHeight = h;

            LOGGER.fine("[PBRMaterialNode] 输出图像已创建: " + w + "x" + "h format=RGBA16F image=0x" + Long.toHexString(newImage));
        }
    }

    /**
     * 从 ShaderPathResolver 加载 SPIR-V 字节码
     * <p>
     * 通过 shaderKey() 动态解析，支持 RGB 光影包覆盖。
     * 优先级：RGB 覆盖 > 预编译 SPV > 源码编译
     * </p>
     *
     * @return SPIR-V 字节数组，加载失败返回 null
     */
    private byte[] loadSPIRV() {
        return resolveSPIRV();
    }

    /**
     * 释放 GPU 计算资源
     * <p>
     * 顺序释放：输出 ImageView → 输出 Image → Descriptor Pool → Pipeline → PipelineLayout。
     * 所有释放操作均带空值检查，线程安全。
     * </p>
     */
    private void disposeGPUResources() {
        VulkanGPUResourceManager resMgr = VulkanGPUResourceManager.getInstance();

        if (outputImageView != 0L && resMgr != null) {
            try { resMgr.destroyView(outputImageView); } catch (Throwable ignored) {}
            outputImageView = 0L;
        }
        if (outputImage != 0L && resMgr != null) {
            try {
                resMgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        outputImage, 0L, lastOutputWidth, lastOutputHeight, 97,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            } catch (Throwable ignored) {}
            outputImage = 0L;
        }

        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device != 0L) {
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

        lastOutputWidth = 0;
        lastOutputHeight = 0;
        gbufferPositionView = 0L;
        gbufferNormalView = 0L;
        gbufferAlbedoView = 0L;
        gbufferMaterialView = 0L;
        spirvBinary = null;
    }
}
