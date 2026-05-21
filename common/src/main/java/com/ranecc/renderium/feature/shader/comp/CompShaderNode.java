// Renderium - Compute Shader 节点系统
// CompShaderNode - 可编辑的 GLSL Compute Shader 管线节点
//
// 功能：
//   1. 加载 .comp (GLSL) + .comp.meta (参数描述)
//   2. 编译为 SPIR-V（通过外部编译器 glslc / spirv-v）
//   3. 创建 VkPipeline + VkPipelineLayout
//   4. 每帧：将 ShaderGraphicsConfig 参数写入 push_constant → vkCmdDispatch
//
// 架构位置：
//   AbstractPipelineNode (基类)
//     └── CompShaderNode (本类) ← 可替换的 compute shader 执行器
//
// 用户工作流：
//   1. 编辑 shaders/compute/my_effect.comp (GLSL)
//   2. 编辑 shaders/compute/my_effect.comp.meta (参数声明)
//   3. 系统自动检测变更 → 重编译 → 热重载
//   4. UI 滑块 → push_constant → shader 行为改变

package com.ranecc.renderium.feature.shader.comp;

import com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.node.AbstractPipelineNode;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;
import com.ranecc.renderium.feature.shader.settings.ShaderGraphicsConfig;
import com.ranecc.renderium.infrastructure.gpu.ComputePipelineHelper;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanGPUResourceManager;
import com.ranecc.renderium.infrastructure.gpu.VulkanSyncManager;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * Compute Shader 管线节点
 * <p>
 * 将一个 GLSL Compute Shader (.comp) 包装为可执行的渲染管线节点。
 * 通过配套的 .comp.meta 文件获取参数布局，运行时将
 * {@link ShaderGraphicsConfig} 的值映射到 push_constant 字段。
 *
 * <h3>生命周期：</h3>
 * <pre>
 * 1. load()        - 加载 .comp + .comp.meta, 编译 SPIR-V
 * 2. initialize()  - 创建 VkPipeline + VkPipelineLayout
 * 3. execute()     - 每帧调用: 更新 push_constant → vkCmdDispatch
 * 4. reload()      - 检测文件变更, 自动重新编译 (热重载)
 * 5. dispose()    - 释放 VkPipeline + VkShaderModule
 * </pre>
 *
 * <h3>参数流：</h3>
 * <pre>
 * ShaderGraphicsConfig (UI 设置)
 *   ↓ CompShaderNode.writePushConstants()
 * push_constant buffer (字节缓冲区)
 *   ↓ vkCmdPushConstants()
 * GPU (.comp shader 读取)
 * </pre>
 *
 * @since 3.0.0
 */
public class CompShaderNode extends AbstractPipelineNode {

    private static final Logger LOGGER = Logger.getLogger("Renderium|CompShader");

    // ==================== 实例状态 ====================

    /** 元数据（从 .comp.meta 加载） */
    private final CompShaderMeta meta;

    /** .comp 源码文件路径 */
    private final Path compSourcePath;

    /** .comp.meta 文件路径 */
    private final Path metaPath;

    /** SPIR-V 二进制数据（编译产物） */
    private byte[] spirvBinary;

    /** push_constant 数据缓冲区（每帧更新） */
    private final byte[] pushConstantBuffer;

    /** 上次修改时间（用于热重载检测） */
    private long lastModifiedTime;

    /** 是否已初始化（VkPipeline 已创建） */
    private volatile boolean initialized = false;

    /** 缓存的分辨率（从 RenderContext 动态获取，避免硬编码） */
    private volatile int cachedWidth = 1920;
    private volatile int cachedHeight = 1080;

    // ==================== Vulkan 管线句柄（Stage 3 真实 dispatch） ====================

    /** Compute Pipeline 句柄，0 = 未创建 */
    private volatile long computePipeline = 0L;
    private volatile long pipelineLayout = 0L;
    private volatile long descriptorSet = 0L;

    /** 独立输出图像资源（GPU compute shader 实际写入目标） */
    private volatile long outputImage = 0L;
    private volatile long outputImageView = 0L;
    private volatile int lastOutputWidth = 0;
    private volatile int lastOutputHeight = 0;

    // ==================== 构造器 ====================

    /**
     * 从 .comp 和 .comp.meta 文件创建节点
     *
     * 【方法参数】
     * @param compPath Path - .comp GLSL 源码文件路径（非 null）
     * @param metaPath Path - .comp.meta 参数元数据文件路径（非 null）
     *
     * @throws IOException 文件不存在或格式错误时抛出
     *
     * 【示例】
     * <pre>
     * CompShaderNode node = new CompShaderNode(
     *     Path.of("shaders/composite/composite.comp"),
     *     Path.of("shaders/composite/composite.comp.meta"));
     * node.load();
     * node.initialize(device);
     * </pre>
     */
    public CompShaderNode(Path compPath, Path metaPath) throws IOException {
        super("comp:" + compPath.getFileName().toString(),
              "Compute Shader: " + compPath.getFileName(),
              PipelineNode.Category.POST_PROCESS,  // Compute Shader 属于后处理阶段
              100);  // 默认优先级

        this.compSourcePath = Objects.requireNonNull(compPath);
        this.metaPath = Objects.requireNonNull(metaPath);

        // 加载元数据
        this.meta = CompShaderMeta.loadFrom(metaPath);

        // 预分配 push_constant 缓冲区
        int pcSize = meta.getPushConstantLayout() != null
                ? meta.getPushConstantLayout().getSizeBytes()
                : 0;
        this.pushConstantBuffer = new byte[pcSize];

        LOGGER.info("CompShaderNode 创建: id=" + meta.getId()
                + ", pcSize=" + pcSize + " bytes, fields="
                + (meta.getPushConstantLayout() != null ? meta.getPushConstantLayout().getFields().size() : 0));
    }

    /**
     * 使用已加载的元数据创建节点（用于内嵌 shader）
     */
    public CompShaderNode(CompShaderMeta meta, byte[] precompiledSpirv) {
        super("comp:" + meta.getId(), "Compute Shader: " + meta.getDisplayName(),
              PipelineNode.Category.POST_PROCESS,  // Compute Shader 属于后处理阶段
              100);  // 默认优先级
        this.meta = Objects.requireNonNull(meta);
        this.compSourcePath = null;  // 无源码文件（预编译）
        this.metaPath = null;
        this.spirvBinary = precompiledSpirv.clone();

        int pcSize = meta.getPushConstantLayout() != null
                ? meta.getPushConstantLayout().getSizeBytes()
                : 0;
        this.pushConstantBuffer = new byte[pcSize];
        this.initialized = true;
    }

    // ==================== 核心生命周期 ====================

    /**
     * 加载并编译 .comp 为 SPIR-V
     * <p>
     * 调用外部编译器（glslc / spirv-v）将 GLSL 编译为 SPIR-V 二进制。
     * 编译结果缓存在 {@link #spirvBinary} 中。
     *
     * @return boolean - 编译是否成功
     */
    public boolean load() {
        if (compSourcePath == null || !Files.exists(compSourcePath)) {
            if (spirvBinary != null) return true;
            LOGGER.warning("无法加载 .comp: " + compSourcePath);
            return false;
        }

        try {
            String source = Files.readString(compSourcePath);
            lastModifiedTime = Files.getLastModifiedTime(compSourcePath).toMillis();

            if (!com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard.isOk()) {
                LOGGER.fine("Vulkan guard active, skipping compilation");
                return false;
            }

            // 使用 GlslangCompiler 编译 GLSL → SPIR-V
            GlslangCompiler compiler = GlslangCompiler.getInstance();
            byte[] compiled;
            try {
                compiled = compiler.compile(source,
                    GlslangCompiler.Stage.COMPUTE, GlslangCompiler.SourceLanguage.GLSL);
            } catch (Exception ce) {
                LOGGER.warning("GLSL 编译异常: " + ce.getMessage());
                return false;
            }
            if (compiled != null && compiled.length > 0) {
                this.spirvBinary = compiled;
                this.initialized = true;
                LOGGER.info("GLSL 编译成功: " + compSourcePath + " (" + (compiled.length / 1024) + " KB SPIR-V)");
            } else {
                LOGGER.warning("GLSL 编译失败: " + compSourcePath);
                return false;
            }

            return true;

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "加载 .comp 失败: " + compSourcePath, e);
            return false;
        }
    }

    /**
     * 检测源码是否变更，必要时重新编译（热重载）
     *
     * @return boolean - 是否执行了重载
     */
    public boolean checkHotReload() {
        if (compSourcePath == null) return false;

        try {
            long currentMod = Files.getLastModifiedTime(compSourcePath).toMillis();
            if (currentMod > lastModifiedTime) {
                LOGGER.info("检测到 .comp 变更，执行热重载: " + compSourcePath);
                boolean ok = load();
                if (ok) {
                    initialized = false;  // 强制重新初始化 pipeline
                }
                return ok;
            }
        } catch (IOException e) {
            LOGGER.fine("无法读取 .comp 文件时间戳（热重载跳过）: " + compSourcePath + " - " + e.getMessage());
        }

        return false;
    }

    // ==================== 参数写入 ====================

    /**
     * 将 ShaderGraphicsConfig 的值写入 push_constant 缓冲区
     * <p>
     * 根据 .comp.meta 中定义的字段偏移和类型，
     * 将配置值序列化到字节缓冲区中。
     * 此方法在每帧 execute() 前调用。
     *
     * 【方法参数】
     * @param config ShaderGraphicsConfig - 当前图形配置（非 null）
     *
     * 【写入规则】
     * <ul>
     *   <li>float → 4 字节 IEEE 754 小端</li>
     *   <li>int/uint → 4 字节小端</li>
     *   <li>vec2 → 8 字节 (x, y)</li>
     *   <li>vec3 → 12 字节 (x, y, z)，注意 std140 对齐到 16</li>
     *   <li>vec4 → 16 字节 (x, y, z, w)</li>
     * </ul>
     */
    public void writePushConstants(ShaderGraphicsConfig config) {
        if (meta.getPushConstantLayout() == null) return;

        for (CompShaderMeta.FieldDesc field : meta.getPushConstantLayout().getFields()) {
            writeField(field, config);
        }
    }

    /**
     * 写入单个字段到 push_constant 缓冲区
     */
    private void writeField(CompShaderMeta.FieldDesc field, ShaderGraphicsConfig config) {
        int off = field.getByteOffset();
        Object value = resolveFieldValue(field, config);

        try {
            switch (field.getGlslType()) {
                case "float" -> writeFloat(off, ((Number) value).floatValue());
                case "int", "uint" -> writeInt(off, ((Number) value).intValue());
                case "bool" -> writeInt(off, Boolean.TRUE.equals(value) ? 1 : 0);
                case "vec2" -> { float[] v = (float[]) value; writeFloat(off, v[0]); writeFloat(off+4, v[1]); }
                case "vec3" -> { float[] v = (float[]) value; writeFloat(off, v[0]); writeFloat(off+4, v[1]); writeFloat(off+8, v[2]); }
                case "vec4" -> { float[] v = (float[]) value; writeFloat(off, v[0]); writeFloat(off+4, v[1]); writeFloat(off+8, v[2]); writeFloat(off+12, v[3]); }
                default -> LOGGER.warning("不支持的 GLSL 类型: " + field.getGlslType() + " (字段: " + field.getName() + ")");
            }
        } catch (Exception e) {
            LOGGER.warning("写入字段失败: " + field.getName() + " (type=" + field.getGlslType() + ")");
        }
    }

    /**
     * 根据 FieldDesc 名称解析对应的配置值
     * <p>
     * 这里建立了 **名称约定**：.comp.meta 中的字段名与
     * ShaderGraphicsConfig 属性之间的映射关系。
     *
     * 映射表：
     * <table border="1">
     *   <tr><th>.comp 字段名</th><th>ShaderGraphicsConfig 方法</th></tr>
     *   <tr><td>resolution</td><td>screenWidth × screenHeight</td></tr>
     *   <tr><td>lightDir</td><td>太阳方向向量</td></tr>
     *   <tr><td>lightIntensity</td><td>bloomIntensity / 100f</td></tr>
     *   <tr><td>ambientStrength</td><td>vignetteIntensity / 100f</td></tr>
     *   <tr><td>compositeMode</td><td>tonemapMode ordinal</td></tr>
     *   <tr><td>time</td><td>System.nanoTime() / 1e9f</td></tr>
     *   <tr><td>shadowQuality</td><td>shadowQuality.ordinal()</td></tr>
     * </table>
     */
    private Object resolveFieldValue(CompShaderMeta.FieldDesc field, ShaderGraphicsConfig config) {
        String name = field.getName();

        // 尝试从默认值回退
        if (field.getDefaultValue() != null) {
            return field.getDefaultValue();
        }

        // 名称约定映射（使用缓存的动态分辨率）
        return switch (name) {
            case "resolution" -> new float[]{(float) cachedWidth, (float) cachedHeight};  // 动态获取，非硬编码
            case "lightDir" -> new float[]{0.5f, 0.8f, 0.2f};  // 默认太阳方向
            case "lightIntensity" -> config.getBloomIntensity() / 100f * 3f;
            case "lightColor" -> new float[]{1.0f, 0.95f, 0.85f};
            case "ambientStrength" -> config.getVignetteIntensity() / 100f * 0.3f;
            case "compositeMode" -> switch (config.getTonemapMode()) {
                case OFF -> 0;
                case ACES -> 0;
                case FILMIC -> 1;
                case REINHARD -> 2;
                default -> 0;
            };
            case "time" -> (float)(System.nanoTime() % 1_000_000_000L) / 1e9f;
            case "maxSteps" -> 64 + config.getShadowQuality().getResolution() / 64;
            case "vizMode" -> config.isPbrEnabled() ? 0 : 3;
            default -> field.getDefaultValue();
        };
    }

    // ==================== 底层字节写入 ====================

    private void writeFloat(int offset, float value) {
        if (offset + 4 <= pushConstantBuffer.length) {
            int bits = Float.floatToIntBits(value);
            pushConstantBuffer[offset]   = (byte) (bits & 0xFF);
            pushConstantBuffer[offset+1] = (byte) ((bits >> 8) & 0xFF);
            pushConstantBuffer[offset+2] = (byte) ((bits >> 16) & 0xFF);
            pushConstantBuffer[offset+3] = (byte) ((bits >> 24) & 0xFF);
        }
    }

    private void writeInt(int offset, int value) {
        if (offset + 4 <= pushConstantBuffer.length) {
            pushConstantBuffer[offset]   = (byte) (value & 0xFF);
            pushConstantBuffer[offset+1] = (byte) ((value >> 8) & 0xFF);
            pushConstantBuffer[offset+2] = (byte) ((value >> 16) & 0xFF);
            pushConstantBuffer[offset+3] = (byte) ((value >> 24) & 0xFF);
        }
    }

    // ==================== AbstractPipelineNode 实现 ====================

    @Override
    public long execute(RenderContext context, long... inputResources) {
        if (meta.getPushConstantLayout() == null) {
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }

        // 从 RenderContext 动态获取并缓存分辨率
        int newWidth = context.getWidth();
        int newHeight = context.getHeight();
        if (newWidth > 0 && newHeight > 0 && (newWidth != cachedWidth || newHeight != cachedHeight)) {
            this.cachedWidth = newWidth;
            this.cachedHeight = newHeight;
        }

        // 检查热重载
        checkHotReload();

        // 懒加载 Compute Pipeline
        ensurePipeline();
        if (computePipeline == 0L) {
            return inputResources.length > 0 ? inputResources[0] : 0L;
        }

        // 确保独立输出图像已创建
        ensureOutputImage(context);
        if (outputImageView == 0L) {
            return inputResources.length > 0 ? inputResources[inputResources.length - 1] : 0L;
        }

        // 将参数写入 push_constant 缓冲区
        ShaderGraphicsConfig config = ShaderGraphicsConfig.getInstance();
        writePushConstants(config);

        // 真实 Vulkan Compute dispatch
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return inputResources.length > 0 ? inputResources[0] : 0L;

        try {
            long cmdBuf = com.ranecc.renderium.feature.lod.compute.LodCullingComputePass.allocateCommandBuffer(device);
            if (cmdBuf == 0L) return inputResources.length > 0 ? inputResources[0] : 0L;

            com.ranecc.renderium.feature.lod.compute.LodCullingComputePass.beginCommandBuffer(cmdBuf);

            // 更新 DescriptorSet 绑定输入/输出纹理
            if (descriptorSet != 0L) {
                int bindCount = Math.min(inputResources.length, meta.getBindings().size());
                for (int i = 0; i < bindCount; i++) {
                    if (i < bindCount - 1) {
                        ComputePipelineHelper.updateImageDescriptor(device, descriptorSet, i,
                            inputResources[i], 0L, 0L);
                    } else {
                        ComputePipelineHelper.updateStorageImageDescriptor(device, descriptorSet, i,
                            outputImageView, 0L);
                    }
                }
            }

            VulkanAPIRegistry.invoke("vkCmdBindPipeline", cmdBuf, 1, computePipeline);
            if (pipelineLayout != 0L && descriptorSet != 0L) {
                MemorySegment dsPtr = PerFrameArena.allocateLongs(1);
                dsPtr.set(ValueLayout.JAVA_LONG, 0, descriptorSet);
                VulkanAPIRegistry.invoke("vkCmdBindDescriptorSets", cmdBuf, 1,
                    pipelineLayout, 0, 1, dsPtr.address(), 0, 0L);
            }

            // push_constant
            int pcSize = meta.getPushConstantLayout().getSizeBytes();
            if (pcSize > 0 && pushConstantBuffer.length >= pcSize) {
                MemorySegment pcSeg = PerFrameArena.allocate(pcSize);
                for (int i = 0; i < pcSize; i++) {
                    pcSeg.set(ValueLayout.JAVA_BYTE, i, pushConstantBuffer[i]);
                }
                VulkanAPIRegistry.invoke("vkCmdPushConstants", cmdBuf,
                    pipelineLayout, 0x00000020L, 0, pcSize, pcSeg.address());
            }

            // Dispatch
            int wgX = meta.getWorkgroupX();
            int wgY = meta.getWorkgroupY();
            int wgZ = meta.getWorkgroupZ();
            VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf,
                (cachedWidth + wgX - 1) / wgX,
                (cachedHeight + wgY - 1) / wgY, wgZ);

            com.ranecc.renderium.feature.lod.compute.LodCullingComputePass.endCommandBuffer(cmdBuf);

            long queue = VulkanDeviceHolder.getInstance().getGraphicsQueue();
            if (queue != 0L) {
                VulkanSyncManager.submitAndWait(queue, cmdBuf);
            }
        } catch (Throwable t) {
            LOGGER.fine("CompShader dispatch %s 失败: %s".formatted(getName(), t.getMessage()));
        }

        return outputImageView != 0L ? outputImageView : (inputResources.length > 0 ? inputResources[inputResources.length - 1] : 0L);
    }

    /**
     * 懒加载 Compute Pipeline（从 .comp.meta 的 binding 描述 + SPIR-V 创建）
     */
    private void ensurePipeline() {
        if (computePipeline != 0L) return;
        if (spirvBinary == null || spirvBinary.length == 0) return;

        try {
            var bindings = meta.getBindings();
            ComputePipelineHelper.Binding[] bs = new ComputePipelineHelper.Binding[bindings.size()];
            for (int i = 0; i < bindings.size(); i++) {
                var b = bindings.get(i);
                int descType = switch (b.getResourceType()) {
                    case "storage_image" -> 10;  // VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                    case "storage_buffer" -> 12; // VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    default -> 11;                // VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                };
                bs[i] = new ComputePipelineHelper.Binding(b.getBindingIndex(), descType);
            }

            int pcSize = meta.getPushConstantLayout() != null
                ? meta.getPushConstantLayout().getSizeBytes() : 0;
            var pc = new ComputePipelineHelper.PushConstant(0, pcSize,
                ComputePipelineHelper.VK_SHADER_STAGE_COMPUTE_BIT);

            var resources = ComputePipelineHelper.createComputePipeline(spirvBinary, bs, pc);
            if (resources != null) {
                computePipeline = resources.pipeline();
                pipelineLayout = resources.pipelineLayout();
                descriptorSet = resources.descriptorSet();
            }
        } catch (Exception e) {
            LOGGER.warning("CompShader Pipeline 创建失败: " + e.getMessage());
        }
    }

    /**
     * 确保输出图像资源已创建（DCL 双重检查锁定）
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文（用于获取当前分辨率）
     *
     * 【方法说明】
     * 当 outputImage 尚未创建或分辨率变化时，重建独立的输出 Image + ImageView。
     * 使用 DCL (Double-Checked Locking) 模式保证线程安全且避免不必要的锁开销。
     * 格式: VK_FORMAT_R8G8B8A8_UNORM (87), 用途: STORAGE | SAMPLED (0x30)
     */
    private void ensureOutputImage(RenderContext context) {
        int w = context.getWidth();
        int h = context.getHeight();
        if (w <= 0 || h <= 0) return;

        if (outputImage != 0L && lastOutputWidth == w && lastOutputHeight == h) {
            return;
        }

        synchronized (this) {
            if (outputImage != 0L && lastOutputWidth == w && lastOutputHeight == h) {
                return;
            }

            VulkanGPUResourceManager resMgr = VulkanGPUResourceManager.getInstance();
            var imgRes = resMgr.createImage(w, h, 87, 0x30, VmaMemoryPools.PoolType.RENDER_TARGET);
            if (imgRes == null || !imgRes.isValid()) {
                LOGGER.warning("CompShader 输出 Image 创建失败: w=" + w + " h=" + h);
                return;
            }
            long newImage = imgRes.handle;
            long newView = resMgr.createView(newImage, 87, 1);
            if (newView == 0L) {
                LOGGER.warning("CompShader 输出 ImageView 创建失败");
                resMgr.releaseResource(imgRes);
                return;
            }

            long oldImage = outputImage;
            long oldView = outputImageView;
            outputImage = newImage;
            outputImageView = newView;
            lastOutputWidth = w;
            lastOutputHeight = h;

            if (oldView != 0L) {
                resMgr.destroyView(oldView);
            }
            if (oldImage != 0L) {
                resMgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                        oldImage, 0L, lastOutputWidth, lastOutputHeight, 87,
                        VulkanGPUResourceManager.ResourceType.IMAGE));
            }

            LOGGER.fine("CompShader 输出图像重建: " + w + "x" + h);
        }
    }

    // ==================== 访问器 ====================

    /** @return CompShaderMeta - 此节点的元数据 */
    public CompShaderMeta getMeta() { return meta; }

    /** @return byte[] - 当前 push_constant 数据（只读副本） */
    public byte[] getPushConstantData() { return pushConstantBuffer.clone(); }

    /** @return byte[] - SPIR-V 二进制（可能为 null 如果未编译） */
    public byte[] getSpirvBinary() { return spirvBinary != null ? spirvBinary.clone() : null; }

    /** @return boolean - 是否已初始化（VkPipeline 已创建） */
    public boolean isInitialized() { return initialized; }

    /** @return Path - .comp 源码路径 */
    public Path getCompSourcePath() { return compSourcePath; }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return String.format("CompShaderNode{id=%s, name=%s, wg=[%d,%d,%d], compiled=%s}",
                getId(), getDisplayName(),
                meta.getWorkgroupX(), meta.getWorkgroupY(), meta.getWorkgroupZ(),
                spirvBinary != null ? "YES" : "NO");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompShaderNode that)) return false;
        return meta.getId().equals(that.meta.getId());
    }

    @Override
    public int hashCode() { return meta.getId().hashCode(); }

    @Override
    protected void onDispose() {
        VulkanGPUResourceManager resMgr = VulkanGPUResourceManager.getInstance();
        if (outputImageView != 0L) {
            resMgr.destroyView(outputImageView);
            outputImageView = 0L;
        }
        if (outputImage != 0L) {
            resMgr.releaseResource(new VulkanGPUResourceManager.GpuResource(
                    outputImage, 0L, lastOutputWidth, lastOutputHeight, 87,
                    VulkanGPUResourceManager.ResourceType.IMAGE));
            outputImage = 0L;
        }
        lastOutputWidth = 0;
        lastOutputHeight = 0;
    }
}
