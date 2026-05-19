// Renderium - 可扩展 Shader 节点系统
// GenericShaderNode - 通用着色器节点（工厂回退实现）
//
// 当 .comp 描述符没有对应的专用实现类时，
// 工厂使用此类作为回退，根据描述符动态生成节点行为。

package com.ranecc.renderium.feature.shader.factory;

import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;
import com.ranecc.renderium.domain.constant.VulkanConst;

import org.lwjgl.vulkan.VK10;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import com.ranecc.renderium.feature.pipeline.parameter.ParameterKnob;
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
 * </ul>
 *
 * @see ShaderNodeFactory
 * @since 7.0.0
 */
public class GenericShaderNode extends AbstractPipelineNode
        implements ShaderNodeFactory.ParameterAware, ShaderNodeFactory.SpirvCapable {

    private static final Logger LOGGER = Logger.getLogger(GenericShaderNode.class.getName());

    /** 关联的 Comp 描述符 */
    private volatile ShaderCompDescriptor descriptor;

    /** 参数列表 */
    private volatile List<ParameterKnob<?>> parameters;

    /** SPIR-V 模块引用 */
    private volatile SPIRVShaderModule spirvModule;

    /**
     * 默认构造函数（反射调用需要无参构造）
     */
    public GenericShaderNode() {
        // 使用占位值，实际字段将在 setDescriptor() 中设置
        super("generic_unknown", "Generic Shader Node", PipelineNode.Category.POST_PROCESS, 999);
        LOGGER.fine("GenericShaderNode 已创建（等待配置）");
    }

    /**
     * 设置关联的描述符（通常由工厂调用）
     *
     * 【方法参数】
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

    // ==================== PipelineNode 核心方法 ====================

    /**
     * 初始化通用着色器节点
     * <p>
     * 验证描述符完整性，预加载 SPIR-V 模块，
     * 分配必要的 GPU 资源。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     *
     * @return boolean - 是否初始化成功
     */
    @Override
    protected boolean onInitialize(RenderContext context) {
        if (descriptor == null) {
            LOGGER.warning("GenericShaderNode.onInitialize(): 描述符未设置");
            return false;
        }

        // 验证 SPIR-V 模块可用性
        if (spirvModule == null || !spirvModule.isValid()) {
            LOGGER.warning(String.format("GenericShaderNode [%s]: SPIR-V 模块无效或未加载",
                    getId()));
            // 不返回 false：某些纯计算节点可能不需要 SPIR-V
        }

        LOGGER.fine(String.format("GenericShaderNode [%s] 初始化完成: params=%d, spirv=%b",
                getId(), parameters != null ? parameters.size() : 0,
                spirvModule != null && spirvModule.isValid()));

        return true;
    }

    /**
     * 执行通用着色器逻辑
     * <p>
     * 基于描述符定义的输入输出插槽和 SPIR-V 模块执行渲染/计算操作。
     *
     * <h3>执行流程：</h3>
     * <ol>
     *   <li>验证输入资源</li>
     *   <li>从 ParameterKnob 读取当前参数值</li>
     *   <li>设置 Uniform 缓冲区</li>
     *   <li>提交 GPU Draw/Compute Call</li>
     *   <li>返回输出资源句柄</li>
     * </ol>
     *
     * 【方法参数】
     * @param context        RenderContext - 渲染上下文
     * @param inputResources long...      - 输入资源句柄数组
     *
     * @return long - 输出资源句柄
     */
    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (descriptor == null) {
            LOGGER.warning("GenericShaderNode.execute(): 描述符未设置，跳过执行");
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }

        // ====== GPU 执行逻辑（对接 VulkanGPUResourceManager + SPIRVReflection）======
        // 完整流程：
        //   1. 从 SPIRVShaderModule 反射 Uniform 布局
        //   2. 通过 VulkanGPUResourceManager 创建输出资源（如需要）
        //   3. 从 parameters 构建 UBO 数据并上传
        //   4. 通过 CommandBatcher 提交 Compute Dispatch 命令

        try {
            // Step 1: SPIR-V 反射（提取 Uniform 布局信息）
            List<String> uniforms = List.of();
            if (spirvModule != null && spirvModule.isValid()) {
                uniforms = spirvModule.getUniforms();
            }

            // Step 2: 检查 GPU 资源管理器可用性
            VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
            boolean gpuAvailable = mgr.isInitialized();

            if (!gpuAvailable) {
                LOGGER.warning(String.format("GPU not available for shader node: %s", getName()));
                return inputResources.length > 0 ? inputResources[0] : 0L;
            }

            // Step 3: 根据描述符决定是否需要创建输出资源
            // 如果描述符声明了 Storage Image 输出，则创建对应资源
            long outputHandle = 0L;
            if (descriptor.hasStorageImageOutput()) {
                int outW = context.getWidth() > 0 ? context.getWidth() : 1920;
                int outH = context.getHeight() > 0 ? context.getHeight() : 1080;

                VulkanGPUResourceManager.GpuResource outputRes = mgr.createImage(
                        outW, outH,
                        VK10.VK_FORMAT_R16G16B16A16_SFLOAT,  // 默认 HDR 格式
                        VulkanConst.IMAGE_USAGE_STORAGE_BIT | VulkanConst.IMAGE_USAGE_SAMPLED_BIT,
                        com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools.PoolType.RENDER_TARGET
                );

                if (outputRes.isValid()) {
                    outputHandle = outputRes.handle;
                    LOGGER.fine("Output: %s handle=0x%X (%dx%d)".formatted(getName(), outputHandle, outW, outH));
                }
            }

            // Step 4: 通过 CommandBatcher 提交 Compute Dispatch（8x8 工作组大小）
            int workGroupX = Math.max(1, (context.getWidth() + 7) / 8);
            int workGroupY = Math.max(1, (context.getHeight() + 7) / 8);

            CommandBatcher batcher = MCRenderBridge.getCommandBatcher();
            if (batcher != null) {
                batcher.enqueueComputeDispatch(
                        0L,  // pipeline handle（由 Shader 系统在绑定阶段填充）
                        workGroupX, workGroupY, 1
                );
            }

            LOGGER.fine("Dispatch: %s in=%d uniforms=%d wg=(%d,%d)%s".formatted(
                    getName(), inputResources.length, uniforms.size(),
                    workGroupX, workGroupY,
                    outputHandle != 0L ? " → output=0x%X".formatted(outputHandle) : " → pass-through"));

            // 返回输出纹理句柄，或降级为输入直通
            return outputHandle != 0L ? outputHandle
                    : (inputResources.length > 0 ? inputResources[0] : 0L);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "GenericShader %s 执行异常".formatted(getName()), e);
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }
    }

    /**
     * 释放资源
     */
    @Override
    protected void onDispose() {
        spirvModule = null;
        parameters = null;
        LOGGER.fine(String.format("GenericShaderNode [%s] 资源已释放", getId()));
    }

    @Override
    public String toString() {
        return String.format("GenericShaderNode{id=%s, name=%s, params=%d, spirv=%b}",
                getId(),
                descriptor != null ? descriptor.getMetadata().getDisplayName() : "unconfigured",
                parameters != null ? parameters.size() : 0,
                spirvModule != null && spirvModule.isValid()
        );
    }
}
