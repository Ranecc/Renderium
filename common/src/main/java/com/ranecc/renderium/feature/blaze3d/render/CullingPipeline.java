// Renderium - 三级 Culling Pipeline
// L1 Frustum(每帧) + L2 Hi-Z Occlusion(每3帧) + L3 Compact/IndirectDraw(每帧)
// 保守合并 = buffer 切换: L2跑→读B(窄化), L2不跑→读A(最新frustum)
// 零额外dispatch开销, 热路径零分配
//
// Indirect Draw 端到端数据流:
//   L1/L2 → visibilityBuffer → L3(indirect_draw_gen.comp) → indirectArgsBuffer
//   → vkCmdPipelineBarrier(STORAGE_WRITE→INDIRECT_COMMAND_READ)
//   → vkCmdDrawIndexedIndirect 消费 indirectArgsBuffer

package com.ranecc.renderium.feature.blaze3d.render;

import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.lod.compute.LodCullingComputePass;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanMemoryAllocator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.*;

public class CullingPipeline {

    private static final Logger LOGGER = Logger.getLogger(CullingPipeline.class.getName());

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== L1/L2/L3 调度 ====================
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private static final int L2_INTERVAL = 3;

    // ==================== 可见性双缓冲 ====================
    // A: L1 每帧写入 (frustum结果)
    // B: L2 每3帧写入 (frustum+occlusion窄化结果)
    // L3 读 A 或 B, 看 L2 本帧是否执行
    private long visibilityBufferA = 0L;
    private long visibilityBufferB = 0L;
    /** L2本帧是否实际执行了 */
    private boolean l2ExecutedThisFrame = false;

    // ==================== IndirectDraw Pipeline ====================
    private static long indirectGenPipeline = 0L;
    private static long indirectGenPipelineLayout = 0L;
    private static long indirectGenDescriptorSetLayout = 0L;
    private static long indirectGenDescriptorPool = 0L;
    private static long indirectGenDescriptorSet = 0L;
    private static byte[] INDIRECT_DRAW_GEN_SPIRV;

    // ==================== Indirect Draw 消费端资源 ====================
    /** indirectArgsBuffer: L3 Compute Shader 写入 VkDrawIndexedIndirectCommand[] */
    private static long indirectArgsBuffer = 0L;
    private static long indirectArgsMemory = 0L;
    /** drawCountBuffer: L3 Compute Shader 写入可见 chunk 计数 */
    private static long drawCountBuffer = 0L;
    private static long drawCountMemory = 0L;
    /** chunkMetaDataBuffer: 每个 chunk 的 indexCount/firstIndex/vertexOffset/chunkId */
    private static long chunkMetaDataBuffer = 0L;
    private static long chunkMetaDataMemory = 0L;
    /** generatorParamsUBO: L3 Push Constants 的 UBO 备份（可选） */
    private static long generatorParamsUBO = 0L;
    private static long generatorParamsUBOMemory = 0L;

    private static final int WORKGROUP_SIZE = 256;
    private static final int MAX_CHUNK_COUNT = 65536;
    private static final int INDIRECT_COMMAND_SIZE = 20; // VkDrawIndexedIndirectCommand
    private static final int CHUNK_META_SIZE = 16; // uvec4 per chunk

    // ==================== 相机运动检测 ====================
    private final float[] prevForward = new float[3];
    private boolean hasPrevFrame = false;
    private float cameraAngleDeltaDeg = 0f;
    private static final float ANGLE_THRESHOLD_DEG = 60f;

    // ==================== 统计 ====================
    private final AtomicLong totalL1 = new AtomicLong(0);
    private final AtomicLong totalL2 = new AtomicLong(0);
    private final AtomicLong totalL2Skip = new AtomicLong(0);
    private final AtomicLong totalL3 = new AtomicLong(0);
    private final AtomicLong totalIndirectDraws = new AtomicLong(0);

    // ==================== 子组件 ====================
    private GPUCullingSystem cullingSystem;

    public boolean initialize(GPUCullingSystem system) {
        if (initialized) return true;
        this.cullingSystem = system;
        loadIndirectGenSPIRV();
        createIndirectGenPipeline();
        createIndirectDrawResources();
        initialized = true;
        enabled = true;
        LOGGER.info("CullingPipeline 已初始化 [L1=每帧, L2=每" + L2_INTERVAL + "帧, L3=indirect_draw_gen+draw]");
        return true;
    }

    // ==================== 生命周期 ====================

    public void shutdown() {
        if (!initialized) return;
        VkDevice device = RenderiumVulkanBridge.getDevice();
        long vkDevHandle = VulkanDeviceHolder.getInstance().getDevice();
        if (device != null) {
            if (indirectGenPipeline != 0L) { vkDestroyPipeline(device, indirectGenPipeline, null); indirectGenPipeline = 0L; }
            if (indirectGenPipelineLayout != 0L) { vkDestroyPipelineLayout(device, indirectGenPipelineLayout, null); indirectGenPipelineLayout = 0L; }
            if (indirectGenDescriptorSetLayout != 0L) { vkDestroyDescriptorSetLayout(device, indirectGenDescriptorSetLayout, null); indirectGenDescriptorSetLayout = 0L; }
            if (indirectGenDescriptorPool != 0L) { vkDestroyDescriptorPool(device, indirectGenDescriptorPool, null); indirectGenDescriptorPool = 0L; }
        }
        indirectGenDescriptorSet = 0L;
        // 销毁 Indirect Draw 消费端资源
        if (vkDevHandle != 0L) {
            if (indirectArgsBuffer != 0L) { VulkanMemoryAllocator.destroyBuffer(vkDevHandle, indirectArgsBuffer, indirectArgsMemory); indirectArgsBuffer = 0L; indirectArgsMemory = 0L; }
            if (drawCountBuffer != 0L) { VulkanMemoryAllocator.destroyBuffer(vkDevHandle, drawCountBuffer, drawCountMemory); drawCountBuffer = 0L; drawCountMemory = 0L; }
            if (chunkMetaDataBuffer != 0L) { VulkanMemoryAllocator.destroyBuffer(vkDevHandle, chunkMetaDataBuffer, chunkMetaDataMemory); chunkMetaDataBuffer = 0L; chunkMetaDataMemory = 0L; }
            if (generatorParamsUBO != 0L) { VulkanMemoryAllocator.destroyBuffer(vkDevHandle, generatorParamsUBO, generatorParamsUBOMemory); generatorParamsUBO = 0L; generatorParamsUBOMemory = 0L; }
        }
        INDIRECT_DRAW_GEN_SPIRV = null;
        initialized = false;
        enabled = false;
    }

    public void enable() { enabled = true; }
    public void disable() { enabled = false; }
    public boolean isEnabled() { return enabled; }
    public boolean isInitialized() { return initialized; }

    /**
     * 每帧调用 — 执行三级剔除 Pipeline。热路径: 零分配。
     * @param cmdBuf  VkCommandBuffer
     * @param viewProj 16-float 列主序 view-projection 矩阵
     * @param camPos   3-float 相机位置
     */
    public void executeFrame(VkCommandBuffer cmdBuf, float[] viewProj, float[] camPos) {
        if (!enabled || cmdBuf == null || cullingSystem == null) return;

        int frame = frameCounter.incrementAndGet();
        cullingSystem.updateCamera(viewProj, camPos);
        detectCameraMotion(viewProj);

        // === L1: Frustum Culling — 每帧, 写入 visibilityBufferA ===
        cullingSystem.dispatchFrustumCulling(cmdBuf);
        totalL1.incrementAndGet();

        // === L2: Hi-Z Occlusion — 每3帧, 急转跳过, 写入 visibilityBufferB ===
        l2ExecutedThisFrame = (frame % L2_INTERVAL == 0) && (cameraAngleDeltaDeg < ANGLE_THRESHOLD_DEG);
        if (l2ExecutedThisFrame) {
            long cmdBufHandle = cmdBuf.address();
            try {
                dispatchHiZOcclusion(cmdBufHandle);
                totalL2.incrementAndGet();
            } catch (Exception e) {
                LOGGER.warning("L2 Hi-Z 失败: " + e.getMessage());
                l2ExecutedThisFrame = false;
            }
        } else if (cameraAngleDeltaDeg >= ANGLE_THRESHOLD_DEG) {
            totalL2Skip.incrementAndGet();
        }

        // === L3: Compact + IndirectDraw ===
        // 读哪个 buffer? L2跑→B(窄化), L2不跑→A(最新frustum)
        // 保守: L2不跑时L3直接用A → 所有frustum可见chunk都绘 → 零漏绘
        long visBuffer = l2ExecutedThisFrame ? visibilityBufferB : visibilityBufferA;
        long cmdBufHandle = cmdBuf.address();
        dispatchIndirectDrawGen(cmdBufHandle, visBuffer);
        totalL3.incrementAndGet();

        // === 消费端: vkCmdDrawIndexedIndirect ===
        executeIndirectDraw(cmdBufHandle);
    }

    // ==================== L2 Hi-Z Occlusion ====================

    private void dispatchHiZOcclusion(long cmdBufHandle) throws Exception {
        VulkanDeviceHolder holder = VulkanDeviceHolder.getInstance();
        if (!holder.isAvailable()) return;

        if (LodCullingComputePass.getHizBuildPipeline() != 0L) {
            LodCullingComputePass.bindAndDispatchHiZBuild(cmdBufHandle, holder);
            LodCullingComputePass.insertMemoryBarrier(cmdBufHandle);
        }
        if (LodCullingComputePass.getHizOcclusionPipeline() != 0L) {
            LodCullingComputePass.bindAndDispatchOcclusionQuery(cmdBufHandle, holder);
            LodCullingComputePass.insertMemoryBarrier(cmdBufHandle);
        }
    }

    // ==================== L3 IndirectDraw Gen ====================

    private void loadIndirectGenSPIRV() {
        try {
            ClassLoader cl = getClass().getClassLoader();
            try (var is = cl.getResourceAsStream("shaders/compute/culling/indirect_draw_gen.spv")) {
                if (is != null) { INDIRECT_DRAW_GEN_SPIRV = is.readAllBytes(); return; }
            }
            // .comp 源码回退路径（DDD分层：shaders-src/compute/culling）
            try (var is = cl.getResourceAsStream("shaders-src/compute/culling/indirect_draw_gen.comp")) {
                if (is != null) {
                    String src = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    INDIRECT_DRAW_GEN_SPIRV = compileGLSL(src, "indirect_draw_gen");
                }
            }
        } catch (Exception e) {
            LOGGER.warning("indirect_draw_gen 加载失败: " + e.getMessage());
        }
    }

    /**
     * 通过反射调用 GlslangCompiler 将 GLSL 源码编译为 SPIR-V。
     */
    private static byte[] compileGLSL(String source, String name) {
        try {
            Class<?> compilerClass = Class.forName(
                    "com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler");
            java.lang.reflect.Method initialize = compilerClass.getMethod("initialize");
            Object compiler = initialize.invoke(null);

            java.lang.reflect.Method compileMethod = compilerClass.getMethod(
                    "compile", String.class, Enum.class);

            Class<?> stageClass = Class.forName(
                    "com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler$Stage");
            Object computeStage = Enum.valueOf(
                    (Class<Enum>) stageClass, "COMPUTE");

            byte[] spirv = (byte[]) compileMethod.invoke(compiler, source, computeStage);

            if (spirv != null && spirv.length > 0) {
                LOGGER.info(name + " GLSL 编译成功 (" + spirv.length + " bytes)");
                return spirv;
            }
        } catch (ClassNotFoundException e) {
            LOGGER.warning(name + " GlslangCompiler 类未找到: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.warning(name + " GLSL 编译失败: " + e.getMessage());
        }
        return new byte[]{};
    }

    /**
     * 创建 indirect_draw_gen 的 Vulkan Compute Pipeline。
     * 包含: DescriptorSetLayout(6 bindings) + PushConstantRange + PipelineLayout + Pipeline
     * + DescriptorPool/Set + 绑定所有 SSBO/UBO buffer
     */
    private void createIndirectGenPipeline() {
        if (INDIRECT_DRAW_GEN_SPIRV == null || INDIRECT_DRAW_GEN_SPIRV.length == 0) return;
        VkDevice device = RenderiumVulkanBridge.getDevice();
        if (device == null) return;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            // === Descriptor Set Layout (6 bindings, matches indirect_draw_gen.comp) ===
            // binding=0: VisibilityInput SSBO
            // binding=1: ChunkMetaData SSBO
            // binding=2: IndirectDrawOutput SSBO
            // binding=3: DrawCountOutput SSBO
            // binding=4: GeneratorParams UBO
            // binding=5: DebugStatsOutput SSBO
            var bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
            for (int i = 0; i < 5; i++) {
                bindings.get(i).binding(i)
                    .descriptorType(i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            bindings.get(5).binding(5)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            var dsLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
            var dsLayoutPtr = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, dsLayoutInfo, null, dsLayoutPtr) != VK_SUCCESS) return;
            indirectGenDescriptorSetLayout = dsLayoutPtr.get(0);

            // === Push Constant Range (16 bytes: chunkCount + maxDrawCommands + stride + pad) ===
            var pcRanges = VkPushConstantRange.calloc(1, stack);
            pcRanges.get(0)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                .offset(0)
                .size(16);

            // === Pipeline Layout (binds descriptor set layout + push constant) ===
            var setLayoutPtr = stack.mallocLong(1).put(0, indirectGenDescriptorSetLayout);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .setLayoutCount(1)
                .pSetLayouts(setLayoutPtr)
                .pPushConstantRanges(pcRanges);
            var layoutPtr = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, layoutInfo, null, layoutPtr) != VK_SUCCESS) return;
            indirectGenPipelineLayout = layoutPtr.get(0);

            // === Shader Module ===
            var spirvBuf = stack.malloc(INDIRECT_DRAW_GEN_SPIRV.length);
            spirvBuf.put(INDIRECT_DRAW_GEN_SPIRV).flip();
            var smCI = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(spirvBuf);
            var smPtr = stack.mallocLong(1);
            if (vkCreateShaderModule(device, smCI, null, smPtr) != VK_SUCCESS) {
                vkDestroyPipelineLayout(device, indirectGenPipelineLayout, null);
                indirectGenPipelineLayout = 0L;
                return;
            }
            long shaderModule = smPtr.get(0);

            // === Compute Pipeline ===
            var stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule)
                .pName(stack.UTF8("main"));
            var ci = VkComputePipelineCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stage).layout(indirectGenPipelineLayout);
            var pipePtr = stack.mallocLong(1);
            if (vkCreateComputePipelines(device, 0L, ci, null, pipePtr) == VK_SUCCESS) {
                indirectGenPipeline = pipePtr.get(0);
            }
            vkDestroyShaderModule(device, shaderModule, null);

            // === Descriptor Pool (4 SSBO + 1 UBO + 1 SSBO = 5 SSBO + 1 UBO) ===
            var poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(5);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            var poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes).maxSets(1)
                .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT);
            var poolPtr = stack.mallocLong(1);
            if (vkCreateDescriptorPool(device, poolInfo, null, poolPtr) != VK_SUCCESS) return;
            indirectGenDescriptorPool = poolPtr.get(0);

            LOGGER.info("CullingPipeline: indirect_draw_gen Pipeline 创建成功");
        }
    }

    /**
     * 创建 Indirect Draw 消费端所需的 GPU Buffer:
     * - indirectArgsBuffer: VkDrawIndexedIndirectCommand[] (MAX_CHUNK_COUNT * 20 bytes)
     * - drawCountBuffer: 可见 chunk 计数 (4 bytes, 也作为 indirect count buffer)
     * - chunkMetaDataBuffer: 每个 chunk 的 indexCount/firstIndex/vertexOffset/chunkId (MAX_CHUNK_COUNT * 16 bytes)
     * - generatorParamsUBO: GeneratorParams (16 bytes)
     */
    private void createIndirectDrawResources() {
        long vkDevHandle = VulkanDeviceHolder.getInstance().getDevice();
        if (vkDevHandle == 0L) return;

        // indirectArgsBuffer: 必须同时支持 SSBO 写入 + INDIRECT_BUFFER 读取
        long[] indirectResult = VulkanMemoryAllocator.createDeviceLocalBuffer(vkDevHandle,
            MAX_CHUNK_COUNT * (long) INDIRECT_COMMAND_SIZE,
            VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VulkanConst.BUFFER_USAGE_TRANSFER_DST_BIT
                | VulkanConst.BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        if (indirectResult[0] == 0L) {
            LOGGER.severe("CullingPipeline: indirectArgsBuffer 分配失败");
            return;
        }
        indirectArgsBuffer = indirectResult[0];
        indirectArgsMemory = indirectResult[1];

        // drawCountBuffer: 原子计数器 + vkCmdDrawIndirectCount 的 count buffer
        long[] countResult = VulkanMemoryAllocator.createDeviceLocalBuffer(vkDevHandle,
            4L,
            VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VulkanConst.BUFFER_USAGE_TRANSFER_DST_BIT
                | VulkanConst.BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        if (countResult[0] == 0L) {
            LOGGER.severe("CullingPipeline: drawCountBuffer 分配失败");
            return;
        }
        drawCountBuffer = countResult[0];
        drawCountMemory = countResult[1];

        // chunkMetaDataBuffer: 每个 chunk 的绘制元数据
        long[] metaResult = VulkanMemoryAllocator.createDeviceLocalBuffer(vkDevHandle,
            MAX_CHUNK_COUNT * (long) CHUNK_META_SIZE,
            VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT | VulkanConst.BUFFER_USAGE_TRANSFER_DST_BIT);
        if (metaResult[0] == 0L) {
            LOGGER.severe("CullingPipeline: chunkMetaDataBuffer 分配失败");
            return;
        }
        chunkMetaDataBuffer = metaResult[0];
        chunkMetaDataMemory = metaResult[1];

        // generatorParamsUBO
        long[] uboResult = VulkanMemoryAllocator.createHostVisibleBuffer(vkDevHandle,
            16L,
            VulkanConst.BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        if (uboResult[0] == 0L) {
            LOGGER.severe("CullingPipeline: generatorParamsUBO 分配失败");
            return;
        }
        generatorParamsUBO = uboResult[0];
        generatorParamsUBOMemory = uboResult[1];

        // 分配并更新 DescriptorSet（绑定所有 buffer）
        allocateAndUpdateDescriptorSet();

        LOGGER.info("CullingPipeline: Indirect Draw 资源创建完成"
            + " [indirectArgs=" + Long.toHexString(indirectArgsBuffer)
            + " drawCount=" + Long.toHexString(drawCountBuffer)
            + " chunkMeta=" + Long.toHexString(chunkMetaDataBuffer) + "]");
    }

    /**
     * 分配 DescriptorSet 并绑定所有 SSBO/UBO buffer。
     */
    private void allocateAndUpdateDescriptorSet() {
        VkDevice device = RenderiumVulkanBridge.getDevice();
        if (device == null || indirectGenDescriptorPool == 0L || indirectGenDescriptorSetLayout == 0L) return;
        try (var stack = MemoryStack.stackPush()) {
            // 分配 DescriptorSet
            var dsLayoutPtr = stack.mallocLong(1).put(0, indirectGenDescriptorSetLayout);
            var allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(indirectGenDescriptorPool)
                .pSetLayouts(dsLayoutPtr);
            var dsPtr = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(device, allocInfo, dsPtr) != VK_SUCCESS) {
                LOGGER.warning("CullingPipeline: DescriptorSet 分配失败");
                return;
            }
            indirectGenDescriptorSet = dsPtr.get(0);

            // 绑定 buffer 到 descriptor
            // binding=0: VisibilityInput SSBO (visibilityBuffer from L1/L2)
            // binding=1: ChunkMetaData SSBO
            // binding=2: IndirectDrawOutput SSBO (indirectArgsBuffer)
            // binding=3: DrawCountOutput SSBO (drawCountBuffer)
            // binding=4: GeneratorParams UBO
            // binding=5: DebugStatsOutput SSBO (暂绑 drawCountBuffer 复用)

            long visBuf = (visibilityBufferA != 0L) ? visibilityBufferA : 0L;

            var buf0 = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(visBuf).offset(0).range(MAX_CHUNK_COUNT * 4L);
            var buf1 = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(chunkMetaDataBuffer).offset(0).range(MAX_CHUNK_COUNT * (long) CHUNK_META_SIZE);
            var buf2 = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(indirectArgsBuffer).offset(0).range(MAX_CHUNK_COUNT * (long) INDIRECT_COMMAND_SIZE);
            var buf3 = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(drawCountBuffer).offset(0).range(4L);
            var buf4 = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(generatorParamsUBO).offset(0).range(16L);
            var buf5 = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(drawCountBuffer).offset(0).range(4L);

            var writeDescs = VkWriteDescriptorSet.calloc(6, stack);
            writeDescs.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(indirectGenDescriptorSet).dstBinding(0).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf0);
            writeDescs.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(indirectGenDescriptorSet).dstBinding(1).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf1);
            writeDescs.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(indirectGenDescriptorSet).dstBinding(2).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf2);
            writeDescs.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(indirectGenDescriptorSet).dstBinding(3).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf3);
            writeDescs.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(indirectGenDescriptorSet).dstBinding(4).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(buf4);
            writeDescs.get(5).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(indirectGenDescriptorSet).dstBinding(5).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buf5);

            vkUpdateDescriptorSets(device, writeDescs, null);
        }
    }

    /**
     * dispatch indirect_draw_gen — 从 visibilityBuffer 生成紧凑 Indirect Draw。
     * 绑定 DescriptorSet + PushConstants + dispatch + 内存屏障。
     */
    private void dispatchIndirectDrawGen(long cmdBufHandle, long visibilityBuffer) {
        if (cmdBufHandle == 0L) return;
        int chunkCount = cullingSystem != null ? cullingSystem.getRegisteredChunkCount() : 0;
        if (chunkCount <= 0 || indirectGenPipeline == 0L) return;

        VkCommandBuffer cb = new VkCommandBuffer(cmdBufHandle, RenderiumVulkanBridge.getDevice());

        // 绑定 Compute Pipeline
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, indirectGenPipeline);

        // 绑定 DescriptorSet
        if (indirectGenDescriptorSet != 0L && indirectGenPipelineLayout != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var dsBuffer = stack.mallocLong(1).put(0, indirectGenDescriptorSet);
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                    indirectGenPipelineLayout, 0, dsBuffer, null);
            }
        }

        // Push Constants: chunkCount + maxDrawCommands + stride + pad (16 bytes)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pcData = stack.malloc(16);
            pcData.putInt(0, chunkCount);          // chunkCount
            pcData.putInt(4, MAX_CHUNK_COUNT);     // maxDrawCommands
            pcData.putInt(8, INDIRECT_COMMAND_SIZE); // stride
            pcData.putInt(12, 0);                  // pad
            vkCmdPushConstants(cb, indirectGenPipelineLayout,
                VK_SHADER_STAGE_COMPUTE_BIT, 0, pcData);
        }

        // dispatch
        int dispatchX = Math.max(1, (int) Math.ceil((double) chunkCount / WORKGROUP_SIZE));
        vkCmdDispatch(cb, dispatchX, 1, 1);

        // 内存屏障: Compute Shader 写入 → Indirect Command 读取
        // 确保 indirectArgsBuffer 和 drawCountBuffer 的写入对后续渲染管线可见
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
            vkCmdPipelineBarrier(cb,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                0, barrier, null, null);
        }
    }

    /**
     * 消费端: 从 indirectArgsBuffer 执行 vkCmdDrawIndexedIndirect。
     * GPU-Driven 渲染的核心 — CPU 零开销，1 个 draw call 替代数千个。
     *
     * <p>数据流:
     * <pre>
     * indirect_draw_gen.comp → indirectArgsBuffer (VkDrawIndexedIndirectCommand[])
     *                       → drawCountBuffer (可见 chunk 计数)
     *                       → vkCmdPipelineBarrier (STORAGE_WRITE → INDIRECT_READ)
     *                       → vkCmdDrawIndexedIndirect / vkCmdDrawIndirectCount
     * </pre>
     */
    private void executeIndirectDraw(long cmdBufHandle) {
        if (cmdBufHandle == 0L || indirectArgsBuffer == 0L) return;

        // 优先尝试 vkCmdDrawIndirectCount (Vulkan 1.2+, 无需 CPU 知道 draw count)
        if (tryDrawIndirectCount(cmdBufHandle)) {
            totalIndirectDraws.incrementAndGet();
            return;
        }

        // 回退: vkCmdDrawIndexedIndirect (需要指定 drawCount, 使用 MAX_CHUNK_COUNT 保守值)
        tryDrawIndexedIndirect(cmdBufHandle);
        totalIndirectDraws.incrementAndGet();
    }

    /**
     * 尝试使用 vkCmdDrawIndirectCount — GPU 自动从 drawCountBuffer 读取绘制数量。
     * 需要 Vulkan 1.2+ 或 VK_KHR_draw_indirect_count 扩展。
     */
    private boolean tryDrawIndirectCount(long cmdBufHandle) {
        var mh = VulkanAPIRegistry.getHandle("vkCmdDrawIndirectCount");
        if (mh == null) {
            // 尝试 KHR 版本
            mh = VulkanAPIRegistry.getHandle("vkCmdDrawIndirectCountKHR");
        }
        if (mh == null) return false;

        try {
            // vkCmdDrawIndirectCount(
            //   commandBuffer,
            //   buffer,           // indirectArgsBuffer
            //   offset,           // 0
            //   countBuffer,      // drawCountBuffer
            //   countBufferOffset,// 0
            //   maxDrawCount,     // MAX_CHUNK_COUNT
            //   stride            // 20
            // )
            mh.invokeWithArguments(cmdBufHandle, indirectArgsBuffer, 0L,
                drawCountBuffer, 0L, (long) MAX_CHUNK_COUNT, INDIRECT_COMMAND_SIZE);
            return true;
        } catch (Throwable t) {
            LOGGER.fine("vkCmdDrawIndirectCount 失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 回退方案: vkCmdDrawIndexedIndirect — 使用最大 chunk 数作为 drawCount。
     * GPU 会跳过 instanceCount=0 的命令（由 indirect_draw_gen.comp 对不可见 chunk 设置）。
     */
    private void tryDrawIndexedIndirect(long cmdBufHandle) {
        var mh = VulkanAPIRegistry.getHandle("vkCmdDrawIndexedIndirect");
        if (mh == null) {
            LOGGER.fine("vkCmdDrawIndexedIndirect 不可用");
            return;
        }
        try {
            // vkCmdDrawIndexedIndirect(
            //   commandBuffer,
            //   buffer,     // indirectArgsBuffer
            //   offset,     // 0
            //   drawCount,  // MAX_CHUNK_COUNT (GPU 跳过 instanceCount=0)
            //   stride       // 20
            // )
            mh.invokeWithArguments(cmdBufHandle, indirectArgsBuffer, 0L,
                MAX_CHUNK_COUNT, INDIRECT_COMMAND_SIZE);
        } catch (Throwable t) {
            LOGGER.warning("vkCmdDrawIndexedIndirect 失败: " + t.getMessage());
        }
    }

    // ==================== 双缓冲 ====================

    /** 设置 L1 写入的 visibilityBufferA */
    public void setVisibilityBufferA(long buf) { visibilityBufferA = buf; }

    /** 设置 L2 写入的 visibilityBufferB */
    public void setVisibilityBufferB(long buf) { visibilityBufferB = buf; }

    /** 获取 indirectArgsBuffer 句柄（供外部渲染管线查询） */
    public long getIndirectArgsBuffer() { return indirectArgsBuffer; }

    /** 获取 drawCountBuffer 句柄 */
    public long getDrawCountBuffer() { return drawCountBuffer; }

    // ==================== 相机运动检测 ====================

    private void detectCameraMotion(float[] viewProj) {
        if (viewProj == null || viewProj.length < 12) return;

        float fx = -viewProj[8], fy = -viewProj[9], fz = -viewProj[10];
        float len = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        if (len < 0.001f) return;
        fx /= len; fy /= len; fz /= len;

        if (!hasPrevFrame) {
            prevForward[0] = fx; prevForward[1] = fy; prevForward[2] = fz;
            hasPrevFrame = true;
            cameraAngleDeltaDeg = 0f;
            return;
        }

        float dot = prevForward[0]*fx + prevForward[1]*fy + prevForward[2]*fz;
        float clamped = Math.max(-1f, Math.min(1f, dot));
        cameraAngleDeltaDeg = (float) Math.toDegrees(Math.acos(clamped));

        prevForward[0] = fx; prevForward[1] = fy; prevForward[2] = fz;
    }

    // ==================== API ====================

    public void updateChunkBounds(int x, int y, int z, GPUCullingSystem.BoundingBox bounds) {
        if (cullingSystem != null) cullingSystem.updateChunkBounds(x, y, z, bounds);
    }

    public void setRenderDistance(float d) {
        if (cullingSystem != null) cullingSystem.setRenderDistance(d);
    }

    public boolean isL2Active() { return l2ExecutedThisFrame; }
    public float getCameraAngleDeltaDeg() { return cameraAngleDeltaDeg; }

    public String getStatistics() {
        return "L1=" + totalL1.get() + " L2=" + totalL2.get()
            + " L2\u23ed=" + totalL2Skip.get() + " L3=" + totalL3.get()
            + " IndirectDraw=" + totalIndirectDraws.get()
            + " \u2220\u0394=" + String.format("%.1f\u00b0", cameraAngleDeltaDeg);
    }
}
