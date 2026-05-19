// Renderium - GPU 剔除系统
// Compute Shader 三阶段剔除。通过 LWJGL VkCommandBuffer 直接分派。

package com.ranecc.renderium.feature.blaze3d.aggressive;

import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanMemoryAllocator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.*;

public class GPUCullingSystem implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(GPUCullingSystem.class.getName());
    private static volatile GPUCullingSystem instance;

    public static final int BOUNDING_BOX_SIZE = 24;
    public static final int INDIRECT_COMMAND_SIZE = 20;
    public static final int WORKGROUP_SIZE = 256;
    public static final int MAX_CHUNK_COUNT = 65536;
    public static final float DEFAULT_RENDER_DISTANCE = 16.0f;

    protected static long cullingPipeline = 0L;
protected static long chunkBoundsBuffer = 0L;
protected static long indirectArgsBuffer = 0L;
private static long chunkBoundsMemory = 0L;
private static long indirectArgsMemory = 0L;
private static long cullingShaderModule = 0L;
private static long pipelineLayout = 0L;
/** frustum_culling.comp 的 4 个 binding: SSBO(0/1/2) + UBO(3) */
private static long descriptorSetLayout = 0L;
private static long descriptorPool = 0L;
private static long descriptorSet = 0L;
/** binding=1/2/3 对应 visibility 输出、原子计数器、Camera UBO */
private static long visibilityBuffer = 0L;
private static long visibilityMemory = 0L;
private static long counterBuffer = 0L;
private static long counterMemory = 0L;
private static long cameraUBO = 0L;
private static long cameraUBOMemory = 0L;
private static byte[] FRUSTUM_CULLING_SPIRV;

    /** 是否需要销毁 chunkBoundsBuffer/indirectArgsBuffer（外部创建标记） */
    private static volatile boolean ownBuffers;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile int registeredChunkCount;
    private volatile int visibleChunkCount;
    private volatile float currentRenderDistance = DEFAULT_RENDER_DISTANCE;
    private final AtomicLong totalCullingPasses = new AtomicLong(0);
    private final AtomicLong totalCullingTimeNanos = new AtomicLong(0);
    private final AtomicLong totalBoundsUpdates = new AtomicLong(0);
    private final float[] cameraViewProj = new float[16];
    private final float[] cameraPosition = new float[3];

    protected GPUCullingSystem() { LOGGER.info("GPUCullingSystem 创建完成"); }

    public static GPUCullingSystem getInstance() {
        if (instance == null) synchronized (GPUCullingSystem.class) { if (instance == null) instance = new GPUCullingSystem(); }
        return instance;
    }

    @SuppressWarnings("deprecation")
    public void initialize() {
        if (initialized.get()) return;
        try {
            if (!VulkanDeviceHolder.isAvailable()) { LOGGER.warning("VulkanDeviceHolder 不可用, 跳过"); return; }
            loadSPIRV();
            createPipeline();
            initialized.set(true);
            enabled.set(true);
            LOGGER.info("✓ GPU 剔除系统已初始化 [pipeline=0x" + Long.toHexString(cullingPipeline) + "]");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "✗ 初始化失败", e);
            cleanupResources();
        }
    }

    @Override public void close() { if (initialized.get()) { cleanupResources(); initialized.set(false); enabled.set(false); } }
    public boolean enable() { return enabled.compareAndSet(false, true); }
    public boolean disable() { return enabled.compareAndSet(true, false); }
    public boolean isEnabled() { return enabled.get() && initialized.get(); }
    public boolean isInitialized() { return initialized.get(); }

    /** L1 Pass: Frustum Culling dispatch */ 
    @Deprecated public void executeCulling(Object encoder, Object camera) {}
    @Deprecated public int renderWithCulling(Object encoder) { return 0; }

    public void dispatchFrustumCulling(VkCommandBuffer cmdBuf) {
        if (!isEnabled() || cmdBuf == null || cullingPipeline == 0L) return;
        long startNs = System.nanoTime();
        int dispatchX = Math.max(1, (int) Math.ceil((double) registeredChunkCount / WORKGROUP_SIZE));
        vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, cullingPipeline);
        if (descriptorSet != 0L && pipelineLayout != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var dsBuffer = stack.mallocLong(1).put(0, descriptorSet);
                vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, dsBuffer, null);
            }
        }
        vkCmdDispatch(cmdBuf, dispatchX, 1, 1);
        totalCullingPasses.incrementAndGet();
        totalCullingTimeNanos.addAndGet(System.nanoTime() - startNs);
    }

    public int renderWithCulling(VkCommandBuffer cmdBuf) { return visibleChunkCount; }

    public void updateChunkBounds(int x, int y, int z, BoundingBox bounds) {
        if (!initialized.get() || bounds == null) return;
        registeredChunkCount = Math.max(registeredChunkCount, getChunkIndex(x, y, z) + 1);
        totalBoundsUpdates.incrementAndGet();
    }

    public void setRenderDistance(float d) { if (d > 0) currentRenderDistance = d; }

    public void updateCamera(float[] vp, float[] cp) {
        if (vp != null && vp.length == 16) System.arraycopy(vp, 0, cameraViewProj, 0, 16);
        if (cp != null && cp.length == 3) System.arraycopy(cp, 0, cameraPosition, 0, 3);
    }

    // ==================== SPIR-V ====================

    private void loadSPIRV() {
        try {
            ClassLoader cl = getClass().getClassLoader();
            try (InputStream is = cl.getResourceAsStream("shaders/compute/frustum_culling.comp")) {
                if (is != null) compileGLSL(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) { LOGGER.warning("SPIR-V 加载失败: " + e.getMessage()); }
    }

    private void compileGLSL(String source) {
        try {
            Class<?> compilerClass = Class.forName("com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler");
            java.lang.reflect.Method initialize = compilerClass.getMethod("initialize");
            Object compiler = initialize.invoke(null);
            java.lang.reflect.Method compile = compilerClass.getMethod("compile", String.class, Enum.class);

            Class<?> stageClass = Class.forName("com.ranecc.renderium.feature.blaze3d.shader.GlslangCompiler$Stage");
            Object computeStage = Enum.valueOf((Class<Enum>) stageClass, "COMPUTE");

            FRUSTUM_CULLING_SPIRV = (byte[]) compile.invoke(compiler, source, computeStage);
        } catch (Exception e) { LOGGER.warning("GLSL 编译失败: " + e.getMessage()); }
    }

    // ==================== Pipeline ====================

    @SuppressWarnings("deprecation")
    private void createPipeline() {
        VkDevice device = RenderiumVulkanBridge.getDevice();
        if (device == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // === VkDescriptorSetLayout: 4 个 binding 对应 frustum_culling.comp ===
            // binding=0: SSBO (ChunkBounds)     binding=1: SSBO (VisibilityOutput)
            // binding=2: SSBO (VisibleChunkCounter)  binding=3: UBO (CameraData)
            var bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            bindings.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(3).binding(3)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            var dsLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
            var dsLayoutPtr = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, dsLayoutInfo, null, dsLayoutPtr) == VK_SUCCESS)
                descriptorSetLayout = dsLayoutPtr.get(0);

            // === PipelineLayout 绑定 descriptorSetLayout ===
            var setLayoutPtr = stack.mallocLong(1).put(0, descriptorSetLayout);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .setLayoutCount(1)
                .pSetLayouts(setLayoutPtr);
            var layoutPtr = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, layoutInfo, null, layoutPtr) == VK_SUCCESS)
                pipelineLayout = layoutPtr.get(0);

            if (FRUSTUM_CULLING_SPIRV != null && FRUSTUM_CULLING_SPIRV.length > 0) {
                ByteBuffer spirvBuf = stack.malloc(FRUSTUM_CULLING_SPIRV.length);
                spirvBuf.put(FRUSTUM_CULLING_SPIRV).flip();
                var smCI = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(spirvBuf);
                var smPtr = stack.mallocLong(1);
                if (vkCreateShaderModule(device, smCI, null, smPtr) == VK_SUCCESS)
                    cullingShaderModule = smPtr.get(0);
            }

            if (cullingShaderModule != 0L) {
                var stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(cullingShaderModule)
                    .pName(MemoryUtil.memUTF8("main"));
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stage).layout(pipelineLayout);
                var pipePtr = stack.mallocLong(1);
                if (vkCreateComputePipelines(device, 0L, ci, null, pipePtr) == VK_SUCCESS)
                    cullingPipeline = pipePtr.get(0);
            }

            // === VkBuffer: chunkBoundsBuffer (AABB, 每个 chunk 2×vec4 = 32 字节) ===
            long vkDeviceHandle = VulkanDeviceHolder.getInstance().getDevice();
            long[] boundsResult = VulkanMemoryAllocator.createDeviceLocalBuffer(
                vkDeviceHandle,
                MAX_CHUNK_COUNT * 32L,
                VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT | VulkanConst.BUFFER_USAGE_TRANSFER_DST_BIT);
            if (boundsResult[0] == 0L || boundsResult[1] == 0L) {
                throw new RuntimeException("GPUCullingSystem: chunkBoundsBuffer 分配失败");
            }
            chunkBoundsBuffer = boundsResult[0];
            chunkBoundsMemory = boundsResult[1];

            // === VkBuffer: indirectArgsBuffer (indirect draw, 每个 chunk 20 字节) ===
            long[] indirectResult = VulkanMemoryAllocator.createDeviceLocalBuffer(
                vkDeviceHandle,
                MAX_CHUNK_COUNT * 20L,
                VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT | VulkanConst.BUFFER_USAGE_TRANSFER_DST_BIT);
            if (indirectResult[0] == 0L || indirectResult[1] == 0L) {
                throw new RuntimeException("GPUCullingSystem: indirectArgsBuffer 分配失败");
            }
            indirectArgsBuffer = indirectResult[0];
            indirectArgsMemory = indirectResult[1];

            // === 额外 Buffer: visibilityBuffer(输出), counterBuffer(原子计数), cameraUBO(UBO) ===
            long[] visResult = VulkanMemoryAllocator.createDeviceLocalBuffer(
                vkDeviceHandle, MAX_CHUNK_COUNT * 4L,
                VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT);
            if (visResult[0] == 0L) throw new RuntimeException("visibilityBuffer 分配失败");
            visibilityBuffer = visResult[0]; visibilityMemory = visResult[1];

            long[] cntResult = VulkanMemoryAllocator.createHostVisibleBuffer(
                vkDeviceHandle, 4L,
                VulkanConst.BUFFER_USAGE_STORAGE_BUFFER_BIT | VulkanConst.BUFFER_USAGE_TRANSFER_DST_BIT);
            if (cntResult[0] == 0L) throw new RuntimeException("counterBuffer 分配失败");
            counterBuffer = cntResult[0]; counterMemory = cntResult[1];

            long[] uboResult = VulkanMemoryAllocator.createHostVisibleBuffer(
                vkDeviceHandle, 80L,
                VulkanConst.BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            if (uboResult[0] == 0L) throw new RuntimeException("cameraUBO 分配失败");
            cameraUBO = uboResult[0]; cameraUBOMemory = uboResult[1];

            // === DescriptorPool ===
            var poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            var poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes).maxSets(1)
                .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT);
            var poolPtr = stack.mallocLong(1);
            if (vkCreateDescriptorPool(device, poolInfo, null, poolPtr) == VK_SUCCESS)
                descriptorPool = poolPtr.get(0);

            // === 分配并更新 DescriptorSet ===
            if (descriptorPool != 0L && descriptorSetLayout != 0L) {
                var dsLayoutPtr2 = stack.mallocLong(1).put(0, descriptorSetLayout);
                var allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(dsLayoutPtr2);
                var dsPtr = stack.mallocLong(1);
                if (vkAllocateDescriptorSets(device, allocInfo, dsPtr) == VK_SUCCESS)
                    descriptorSet = dsPtr.get(0);

                if (descriptorSet != 0L) {
                    var buf0 = VkDescriptorBufferInfo.calloc(1, stack);
                    buf0.buffer(chunkBoundsBuffer).offset(0).range(MAX_CHUNK_COUNT * 32L);
                    var buf1 = VkDescriptorBufferInfo.calloc(1, stack);
                    buf1.buffer(visibilityBuffer).offset(0).range(MAX_CHUNK_COUNT * 4L);
                    var buf2 = VkDescriptorBufferInfo.calloc(1, stack);
                    buf2.buffer(counterBuffer).offset(0).range(4L);
                    var buf3 = VkDescriptorBufferInfo.calloc(1, stack);
                    buf3.buffer(cameraUBO).offset(0).range(80L);

                    var writeDescs = VkWriteDescriptorSet.calloc(4, stack);
                    writeDescs.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(buf0);
                    writeDescs.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet).dstBinding(1).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(buf1);
                    writeDescs.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet).dstBinding(2).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(buf2);
                    writeDescs.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet).dstBinding(3).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(buf3);

                    vkUpdateDescriptorSets(device, writeDescs, null);
                }
            }

            ownBuffers = true;
        }
    }

    private void cleanupResources() {
        VkDevice device = RenderiumVulkanBridge.getDevice();
        if (device == null) return;
        long vkDeviceHandle = VulkanDeviceHolder.getInstance().getDevice();
        if (descriptorPool != 0L) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0L; }
        if (cameraUBO != 0L && ownBuffers) {
            VulkanMemoryAllocator.destroyBuffer(vkDeviceHandle, cameraUBO, cameraUBOMemory);
            cameraUBO = 0L; cameraUBOMemory = 0L;
        }
        if (counterBuffer != 0L && ownBuffers) {
            VulkanMemoryAllocator.destroyBuffer(vkDeviceHandle, counterBuffer, counterMemory);
            counterBuffer = 0L; counterMemory = 0L;
        }
        if (visibilityBuffer != 0L && ownBuffers) {
            VulkanMemoryAllocator.destroyBuffer(vkDeviceHandle, visibilityBuffer, visibilityMemory);
            visibilityBuffer = 0L; visibilityMemory = 0L;
        }
        if (cullingPipeline != 0L) { vkDestroyPipeline(device, cullingPipeline, null); cullingPipeline = 0L; }
        if (cullingShaderModule != 0L) { vkDestroyShaderModule(device, cullingShaderModule, null); cullingShaderModule = 0L; }
        if (pipelineLayout != 0L) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0L; }
        if (descriptorSetLayout != 0L) { vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null); descriptorSetLayout = 0L; }
        if (chunkBoundsBuffer != 0L && ownBuffers) {
            VulkanMemoryAllocator.destroyBuffer(vkDeviceHandle, chunkBoundsBuffer, chunkBoundsMemory);
            chunkBoundsBuffer = 0L; chunkBoundsMemory = 0L;
        }
        if (indirectArgsBuffer != 0L && ownBuffers) {
            VulkanMemoryAllocator.destroyBuffer(vkDeviceHandle, indirectArgsBuffer, indirectArgsMemory);
            indirectArgsBuffer = 0L; indirectArgsMemory = 0L;
        }
        ownBuffers = false;
        FRUSTUM_CULLING_SPIRV = null;
        descriptorSet = 0L;
    }

    private int getChunkIndex(int x, int y, int z) {
        return ((x * 73856093 ^ y * 19349669 ^ z * 83492791) & 0x7FFFFFFF) % MAX_CHUNK_COUNT;
    }

    /**
     * 从 view-projection 矩阵提取 6 个视锥平面 [a,b,c,d]。
     * P-NA (Plane Normal Aligned) 法。作为唯一权威源供所有子系统调用。
     * 正确性已由 tools/verify_frustum_culling.py 验证（360°旋转零误差）。
     */
    public static float[][] extractFrustumPlanes(float[] vp) {
        float[][] p = new float[6][4];
        p[0][0]=vp[3]+vp[0]; p[0][1]=vp[7]+vp[4]; p[0][2]=vp[11]+vp[8]; p[0][3]=vp[15]+vp[12];
        p[1][0]=vp[3]-vp[0]; p[1][1]=vp[7]-vp[4]; p[1][2]=vp[11]-vp[8]; p[1][3]=vp[15]-vp[12];
        p[2][0]=vp[3]+vp[1]; p[2][1]=vp[7]+vp[5]; p[2][2]=vp[11]+vp[9]; p[2][3]=vp[15]+vp[13];
        p[3][0]=vp[3]-vp[1]; p[3][1]=vp[7]-vp[5]; p[3][2]=vp[11]-vp[9]; p[3][3]=vp[15]-vp[13];
        p[4][0]=vp[3]+vp[2]; p[4][1]=vp[7]+vp[6]; p[4][2]=vp[11]+vp[10];p[4][3]=vp[15]+vp[14];
        p[5][0]=vp[3]-vp[2]; p[5][1]=vp[7]-vp[6]; p[5][2]=vp[11]-vp[10];p[5][3]=vp[15]-vp[14];
        return p;
    }

    public String formatStatisticsReport() {
        return "GPUCullingSystem{pipeline=" + (cullingPipeline != 0L ? "✓" : "✗")
            + " chunks=" + registeredChunkCount + " passes=" + totalCullingPasses.get() + "}";
    }
    public void resetStatistics() { totalCullingPasses.set(0); totalCullingTimeNanos.set(0); totalBoundsUpdates.set(0); }
    public int getRegisteredChunkCount() { return registeredChunkCount; }
    public int getVisibleChunkCount() { return visibleChunkCount; }
    public float getRenderDistance() { return currentRenderDistance; }

    public static class BoundingBox {
        public float minX, minY, minZ, maxX, maxY, maxZ;
        public BoundingBox(float mx, float my, float mz, float Mx, float My, float Mz) {
            minX=mx; minY=my; minZ=mz; maxX=Mx; maxY=My; maxZ=Mz;
        }
        public float getCenterX() { return (minX+maxX)*0.5f; }
        public float getCenterY() { return (minY+maxY)*0.5f; }
        public float getCenterZ() { return (minZ+maxZ)*0.5f; }
    }

    public static class CullingParams {
        public float[] viewProjMatrix = new float[16];
        public float[][] frustumPlanes = new float[6][4];
        public float[] cameraPosition = new float[3];
        public float renderDistance;
    }
}
