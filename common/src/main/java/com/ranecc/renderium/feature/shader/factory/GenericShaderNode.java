// Renderium - 可扩展 Shader 节点系统
// GenericShaderNode - 通用着色器节点（工厂回退实现）
//
// 当 .comp 描述符没有对应的专用实现类时，
// 工厂使用此类作为回退，根据描述符动态生成节点行为。

package com.ranecc.renderium.feature.shader.factory;

import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;
import com.ranecc.renderium.infrastructure.gpu.VulkanSyncManager;
import com.ranecc.renderium.infrastructure.gpu.FrameCommandContext;
import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;

import org.lwjgl.vulkan.VK10;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.parameter.ParameterKnob;
import com.ranecc.renderium.feature.shader.comp.ShaderCompDescriptor;
import com.ranecc.renderium.feature.shader.spirv.SPIRVShaderModule;
import com.ranecc.renderium.platform.bridge.mc.CommandBatcher;
import com.ranecc.renderium.platform.bridge.mc.MCRenderBridge;
/**
 * 通用着色器节点
 * <p>
 * 作为 {@link ShaderNodeFactory} 的回退实现。当 .comp 描述符没有对应的
 * 专用 PipelineNode 实现类时，工厂自动创建此节点实例。
 *
 * <h2>行为特征：</h2>
 * <ul>
 *   <li>完全由 {@link ShaderCompDescriptor} 驱动</li>
 *   <li>通过 SPIR-V 模块执行 GPU 计算</li>
 *   <li>参数通过 {@link ParameterKnob} 系统动态绑定</li>
 *   <li>输入输出插槽在 execute() 时动态连接</li>
 *   <li>使用 {@link ComputePipelineHelper} 创建真实 Vulkan Compute Pipeline</li>
 * </ul>
 *
 * @see ShaderNodeFactory
 * @since 7.0.0
 */
public class GenericShaderNode extends AbstractPipelineNode
        implements ShaderNodeFactory.ParameterAware, ShaderNodeFactory.SpirvCapable {

    private static final Logger LOGGER = Logger.getLogger(GenericShaderNode.class.getName());

    private static final int NODE_ID = 22;

    /** 关联的 Comp 描述符 */
    private volatile ShaderCompDescriptor descriptor;

    /** 参数列表 */
    private volatile List<ParameterKnob<?>> parameters;

    /** SPIR-V 模块引用 */
    private volatile SPIRVShaderModule spirvModule;

    // ==================== Vulkan 管线句柄 ====================

    /** Compute Pipeline 句柄，0 = 未创建 */
    private volatile long computePipeline = 0L;

    /** Pipeline Layout 句柄 */
    private volatile long pipelineLayout = 0L;

    /** Descriptor Set 句柄 */
    private volatile long descriptorSet = 0L;

    /** 独立的输出图像句柄（Storage Image 输出时使用） */
    private volatile long outputImage = 0L;

    /** 输出 ImageView 句柄 */
    private volatile long outputImageView = 0L;

    /** 上次输出图像尺寸（用于尺寸变更重建检测） */
    private volatile int lastOutputWidth = 0;

    private volatile int lastOutputHeight = 0;

    /** Push Constant 缓冲区（最多 64 字节） */
    private final byte[] pushConstantBuffer = new byte[64];

    /**
     * 默认构造函数（反射调用需要无参构造）
     */
    public GenericShaderNode() {
        super("generic_unknown", "Generic Shader Node", PipelineNode.Category.POST_PROCESS, 999);
        LOGGER.fine("GenericShaderNode 已创建（等待配置）");
    }

    /**
     * 设置关联的描述符（通常由工厂调用）
     *
     * @param desc ShaderCompDescriptor - 节点描述符
     */
    public void setDescriptor(ShaderCompDescriptor desc) {
        this.descriptor = desc;
        LOGGER.fine(String.format("GenericShaderNode 配置完成: %s", desc.getMetadata().getId()));
    }

    // ==================== ParameterAware 接口实现 ====================

    @Override
    public void setParameters(List<ParameterKnob<?>> params) {
        this.parameters = params;
    }

    @Override
    public List<ParameterKnob<?>> getParameters() {
        return parameters;
    }

    // ==================== SpirvCapable 接口实现 ====================

    @Override
    public void setSpirvModule(SPIRVShaderModule module) {
        this.spirvModule = module;
    }

    @Override
    public SPIRVShaderModule getSpirvModule() {
        return spirvModule;
    }

    // ==================== Pipeline 懒加载 ====================

    /**
     * 懒加载 Compute Pipeline
     * <p>
     * 从 SPIR-V 模块获取二进制数据，根据描述符的输入输出插槽
     * 构建 Binding 描述，创建 Compute Pipeline、Pipeline Layout
     * 和 Descriptor Set。
     *
     * <h3>Binding 构建策略：</h3>
     * <ul>
     *   <li>每个输入插槽对应一个 Binding（索引从 0 开始）</li>
     *   <li>IMAGE2D 类型 → VK_DESCRIPTOR_TYPE_STORAGE_IMAGE</li>
     *   <li>SAMPLER* 类型 → VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER</li>
     *   <li>如果有 Storage Image 输出，追加一个 STORAGE_IMAGE Binding</li>
     * </ul>
     *
     * <h3>Push Constant：</h3>
     * <ul>
     *   <li>固定 64 字节，覆盖所有参数序列化</li>
     *   <li>Stage Flag = VK_SHADER_STAGE_COMPUTE_BIT</li>
     * </ul>
     */
    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        if (spirvModule == null || !spirvModule.isValid()) return;

        byte[] spirvBinary = spirvModule.getSpirvBinary();
        if (spirvBinary == null || spirvBinary.length == 0) return;

        synchronized (this) {
            if (computePipeline != 0L) return;

            try {
                int inputCount = descriptor != null ? descriptor.getInputs().size() : 0;
                boolean hasOutput = descriptor != null && descriptor.hasStorageImageOutput();
                int totalBindings = inputCount + (hasOutput ? 1 : 0);

                ComputePipelineHelper.Binding[] bindings;
                if (totalBindings > 0) {
                    bindings = new ComputePipelineHelper.Binding[totalBindings];
                    for (int i = 0; i < inputCount; i++) {
                        ShaderCompDescriptor.SlotType slotType = descriptor.getInputs().get(i).getType();
                        long descType = slotType == ShaderCompDescriptor.SlotType.IMAGE2D
                                ? ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                : ComputePipelineHelper.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                        bindings[i] = new ComputePipelineHelper.Binding(i, descType);
                    }
                    if (hasOutput) {
                        bindings[inputCount] = new ComputePipelineHelper.Binding(
                                inputCount, ComputePipelineHelper.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
                    }
                } else {
                    bindings = new ComputePipelineHelper.Binding[0];
                }

                ComputePipelineHelper.PushConstant pc = new ComputePipelineHelper.PushConstant(
                        0, pushConstantBuffer.length,
                        ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);

                ComputePipelineHelper.PipelineResources resources =
                        ComputePipelineHelper.createComputePipeline(spirvBinary, bindings, pc);

                if (resources != null) {
                    computePipeline = resources.pipeline();
                    pipelineLayout = resources.pipelineLayout();
                    descriptorSet = resources.descriptorSet();
                    LOGGER.fine(String.format("GenericShaderNode [%s] Pipeline 创建成功: pipeline=0x%x, layout=0x%x",
                            getId(), computePipeline, pipelineLayout));
                } else {
                    LOGGER.warning(String.format("GenericShaderNode [%s] Pipeline 创建失败", getId()));
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        String.format("GenericShaderNode [%s] Pipeline 创建异常", getId()), e);
            }
        }
    }

    // ==================== Push Constant 写入 ====================

    /**
     * 将 ParameterKnob 列表的值序列化到 push_constant 缓冲区
     * <p>
     * 按参数列表顺序依次写入，每写入一个参数后偏移量增加对应的字节数：
     * <ul>
     *   <li>FLOAT → 4 字节</li>
     *   <li>INT → 4 字节</li>
     *   <li>BOOL → 4 字节（0/1）</li>
     *   <li>ENUM → 4 字节（当前索引）</li>
     *   <li>VEC2 → 8 字节</li>
     *   <li>VEC3 → 12 字节</li>
     *   <li>COLOR → 16 字节</li>
     * </ul>
     *
     * 写入前将缓冲区清零，确保未覆盖区域为零。
     */
    private void writePushConstants() {
        Arrays.fill(pushConstantBuffer, (byte) 0);
        if (parameters == null || parameters.isEmpty()) return;

        int offset = 0;
        for (ParameterKnob<?> knob : parameters) {
            if (offset >= pushConstantBuffer.length) break;

            Object value = knob.getValue();
            if (value == null) continue;

            try {
                switch (knob.getType()) {
                    case FLOAT -> {
                        writeFloat(offset, ((Number) value).floatValue());
                        offset += 4;
                    }
                    case INT -> {
                        writeInt(offset, ((Number) value).intValue());
                        offset += 4;
                    }
                    case BOOL -> {
                        writeInt(offset, Boolean.TRUE.equals(value) ? 1 : 0);
                        offset += 4;
                    }
                    case ENUM -> {
                        int idx = 0;
                        if (value instanceof Enum<?> enumVal) {
                            idx = enumVal.ordinal();
                        } else if (value instanceof Number num) {
                            idx = num.intValue();
                        }
                        writeInt(offset, idx);
                        offset += 4;
                    }
                    case VEC2 -> {
                        float[] vec = toFloatArray(value, 2);
                        writeFloat(offset, vec[0]);
                        writeFloat(offset + 4, vec[1]);
                        offset += 8;
                    }
                    case VEC3 -> {
                        float[] vec = toFloatArray(value, 3);
                        writeFloat(offset, vec[0]);
                        writeFloat(offset + 4, vec[1]);
                        writeFloat(offset + 8, vec[2]);
                        offset += 12;
                    }
                    case COLOR -> {
                        float[] col = toFloatArray(value, 4);
                        writeFloat(offset, col[0]);
                        writeFloat(offset + 4, col[1]);
                        writeFloat(offset + 8, col[2]);
                        writeFloat(offset + 12, col[3]);
                        offset += 16;
                    }
                }
            } catch (Exception e) {
                LOGGER.fine(String.format("写入参数 %s 失败: %s", knob.getId(), e.getMessage()));
                offset += 4;
            }
        }
    }

    private static float[] toFloatArray(Object value, int minLen) {
        if (value instanceof float[] arr) {
            return arr.length >= minLen ? arr : Arrays.copyOf(arr, minLen);
        }
        float[] result = new float[minLen];
        if (value instanceof Number num) {
            Arrays.fill(result, num.floatValue());
        }
        return result;
    }

    private void writeFloat(int offset, float value) {
        if (offset + 4 <= pushConstantBuffer.length) {
            int bits = Float.floatToIntBits(value);
            pushConstantBuffer[offset]     = (byte) (bits & 0xFF);
            pushConstantBuffer[offset + 1] = (byte) ((bits >> 8) & 0xFF);
            pushConstantBuffer[offset + 2] = (byte) ((bits >> 16) & 0xFF);
            pushConstantBuffer[offset + 3] = (byte) ((bits >> 24) & 0xFF);
        }
    }

    private void writeInt(int offset, int value) {
        if (offset + 4 <= pushConstantBuffer.length) {
            pushConstantBuffer[offset]     = (byte) (value & 0xFF);
            pushConstantBuffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
            pushConstantBuffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
            pushConstantBuffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
        }
    }

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 初始化通用着色器节点
     * <p>
     * 验证描述符完整性，预加载 SPIR-V 模块，
     * 调用 ensurePipeline() 创建 Compute Pipeline。
     *
     * @param context RenderContext - 渲染上下文
     * @return boolean - 是否初始化成功
     */
    @Override
    protected boolean onInitialize(RenderContext context) {
        if (descriptor == null) {
            LOGGER.warning("GenericShaderNode.onInitialize(): 描述符未设置");
            return false;
        }
        if (spirvModule == null || !spirvModule.isValid()) {
            LOGGER.warning(String.format("GenericShaderNode [%s]: SPIR-V 模块无效或未加载", getId()));
        }

        ensurePipeline();

        LOGGER.fine(String.format("GenericShaderNode [%s] 初始化完成: params=%d, spirv=%b, pipeline=0x%x",
                getId(), parameters != null ? parameters.size() : 0,
                spirvModule != null && spirvModule.isValid(), computePipeline));

        return true;
    }

    /**
     * 执行通用着色器逻辑
     * <p>
     * 基于描述符定义的输入输出插槽和 SPIR-V 模块执行 GPU 计算操作。
     *
     * <h3>执行流程：</h3>
     * <ol>
     *   <li>验证输入资源和 GPU 可用性</li>
     *   <li>懒加载 Compute Pipeline（ensurePipeline）</li>
     *   <li>按需创建输出 Storage Image</li>
     *   <li>将 ParameterKnob 值序列化到 push_constant 缓冲区</li>
     *   <li>分配 Command Buffer，录制 Dispatch 命令</li>
     *   <li>更新 Descriptor Set 绑定输入/输出纹理</li>
     *   <li>vkCmdBindPipeline → vkCmdBindDescriptorSets → vkCmdPushConstants → vkCmdDispatch</li>
     *   <li>提交到 GPU Queue 并等待完成</li>
     *   <li>返回输出资源句柄</li>
     * </ol>
     *
     * @param context        RenderContext - 渲染上下文
     * @param inputResources long...      - 输入资源句柄数组
     * @return long - 输出资源句柄
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (descriptor == null) {
            LOGGER.warning("GenericShaderNode.execute(): 描述符未设置，跳过执行");
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }

        VulkanGPUResourceManager resMgr = VulkanGPUResourceManager.getInstance();
        if (!resMgr.isInitialized()) {
            LOGGER.warning(String.format("GPU not available for shader node: %s", getName()));
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }

        ensurePipeline();
        if (computePipeline == 0L) {
            LOGGER.fine(String.format("GenericShaderNode [%s] Pipeline 未就绪，直通返回", getId()));
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }

        int width = context.getWidth() > 0 ? context.getWidth() : 1920;
        int height = context.getHeight() > 0 ? context.getHeight() : 1080;

        // 创建或重建输出 Image（如果需要）
        ensureOutputImage(width, height, resMgr);
        long effectiveOutputHandle = outputImageView != 0L ? outputImageView
                : (inputResources.length > 0 ? inputResources[inputResources.length - 1] : 0L);

        // 将参数写入 push_constant 缓冲区
        writePushConstants();

        // Vulkan Compute Dispatch
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return effectiveOutputHandle;

        try {
            long cmdBuf = FrameCommandContext.beginNodeCB(NODE_ID);
            if (cmdBuf == 0L) return effectiveOutputHandle;

            // 更新 Descriptor Set 绑定输入/输出纹理
            if (descriptorSet != 0L) {
                int inputCount = Math.min(inputResources.length,
                        descriptor.getInputs().size());
                boolean hasOutput = descriptor.hasStorageImageOutput();

                for (int i = 0; i < inputCount; i++) {
                    long imageView = inputResources[i];
                    if (imageView == 0L) continue;

                    ShaderCompDescriptor.SlotType slotType = descriptor.getInputs().get(i).getType();
                    if (slotType == ShaderCompDescriptor.SlotType.IMAGE2D) {
                        ComputePipelineHelper.updateStorageImageDescriptor(
                                device, descriptorSet, i, imageView, 0L);
                    } else {
                        ComputePipelineHelper.updateImageDescriptor(
                                device, descriptorSet, i, imageView, 0L, 0L);
                    }
                }

                if (hasOutput && outputImageView != 0L) {
                    int outputBinding = inputCount;
                    ComputePipelineHelper.updateStorageImageDescriptor(
                            device, descriptorSet, outputBinding, outputImageView, 0L);
                }
            }

            // 绑定 Pipeline
            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);

            // 绑定 Descriptor Set
            if (pipelineLayout != 0L && descriptorSet != 0L) {
                MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf,
                        1, pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);
            }

            // Push Constants
            if (pipelineLayout != 0L) {
                MemorySegment pcSeg = PerFrameArena.allocate(pushConstantBuffer.length);
                for (int i = 0; i < pushConstantBuffer.length; i++) {
                    pcSeg.set(ValueLayout.JAVA_BYTE, i, pushConstantBuffer[i]);
                }
                VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf,
                        pipelineLayout, 0x00000020L, 0,
                        pushConstantBuffer.length, pcSeg.address());
            }

            // Dispatch
            int workGroupX = Math.max(1, (width + 7) / 8);
            int workGroupY = Math.max(1, (height + 7) / 8);
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf,
                    workGroupX, workGroupY, 1);

            FrameCommandContext.endNodeCB(NODE_ID);

            LOGGER.fine("Dispatch %s: %dx%d input=%d wg=(%d,%d) output=0x%x".formatted(
                    getName(), width, height, inputResources.length,
                    workGroupX, workGroupY, effectiveOutputHandle));

        } catch (Throwable t) {
            LOGGER.log(Level.FINE,
                    String.format("GenericShaderNode [%s] dispatch 失败", getName()), t);
        }

        return effectiveOutputHandle;
    }

    /**
     * 按需创建或重建输出 Storage Image
     * <p>
     * 当描述符声明了 Storage Image 输出时，根据当前分辨率创建
     * 独立的输出图像和 ImageView。分辨率变更时自动重建。
     *
     * @param width  当前帧宽度
     * @param height 当前帧高度
     * @param resMgr GPU 资源管理器
     */
    private void ensureOutputImage(int width, int height, VulkanGPUResourceManager resMgr) {
        if (!descriptor.hasStorageImageOutput()) return;
        if (outputImage != 0L && lastOutputWidth == width && lastOutputHeight == height) return;

        synchronized (this) {
            if (outputImage != 0L && lastOutputWidth == width && lastOutputHeight == height) return;

            if (outputImageView != 0L) {
                try { resMgr.destroyView(outputImageView); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                try {
                    resMgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                            outputImage, 0L, lastOutputWidth, lastOutputHeight,
                            VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                            VulkanGPUResourceManager.ResourceType.IMAGE));
                } catch (Throwable ignored) {}
                outputImage = 0L;
            }

            VulkanGPUResourceManager.GpuResource imgRes = resMgr.createImage(
                    width, height,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VulkanConst.IMAGE_USAGE_STORAGE_BIT | VulkanConst.IMAGE_USAGE_SAMPLED_BIT,
                    VmaMemoryPools.PoolType.RENDER_TARGET);

            if (imgRes == null || !imgRes.isValid()) {
                LOGGER.warning("GenericShaderNode 输出 Image 创建失败: " + width + "x" + height);
                return;
            }

            long newView = resMgr.createView(imgRes.handle,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 1);
            if (newView == 0L) {
                LOGGER.warning("GenericShaderNode 输出 ImageView 创建失败");
                resMgr.releaseResource(imgRes);
                return;
            }

            outputImage = imgRes.handle;
            outputImageView = newView;
            lastOutputWidth = width;
            lastOutputHeight = height;

            LOGGER.fine("GenericShaderNode 输出图像: " + width + "x" + height
                    + " image=0x" + Long.toHexString(outputImage)
                    + " view=0x" + Long.toHexString(outputImageView));
        }
    }

    /**
     * 释放 GPU 资源
     */
    @Override
    protected void onDispose() {
        VulkanGPUResourceManager resMgr = VulkanGPUResourceManager.getInstance();
        if (resMgr.isInitialized()) {
            if (outputImageView != 0L) {
                try { resMgr.destroyView(outputImageView); } catch (Throwable ignored) {}
                outputImageView = 0L;
            }
            if (outputImage != 0L) {
                try {
                    resMgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                            outputImage, 0L, lastOutputWidth, lastOutputHeight,
                            VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                            VulkanGPUResourceManager.ResourceType.IMAGE));
                } catch (Throwable ignored) {}
                outputImage = 0L;
            }
        }

        computePipeline = 0L;
        pipelineLayout = 0L;
        descriptorSet = 0L;
        spirvModule = null;
        parameters = null;
        lastOutputWidth = 0;
        lastOutputHeight = 0;

        LOGGER.fine(String.format("GenericShaderNode [%s] 资源已释放", getId()));
    }

    @Override
    public String toString() {
        return String.format("GenericShaderNode{id=%s, name=%s, params=%d, spirv=%b, pipeline=0x%x}",
                getId(),
                descriptor != null ? descriptor.getMetadata().getDisplayName() : "unconfigured",
                parameters != null ? parameters.size() : 0,
                spirvModule != null && spirvModule.isValid(),
                computePipeline
        );
    }
}
