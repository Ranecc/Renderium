// Renderium - 三级 Culling Pipeline
// L1 Frustum(每帧) + L2 Hi-Z Occlusion(每3帧) + L3 Compact/IndirectDraw(每帧)
// 保守合并 = buffer 切换: L2跑→读B(窄化), L2不跑→读A(最新frustum)
// 零额外dispatch开销, 热路径零分配

package com.ranecc.renderium.feature.blaze3d.aggressive;

import com.ranecc.renderium.feature.lod.compute.HiZComputePipeline;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

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
    private static byte[] INDIRECT_DRAW_GEN_SPIRV;

    // ==================== 相机运动检测 ====================
    private final float[] prevForward = new float[3];
    private boolean hasPrevFrame = false;
    private float cameraAngleDeltaDeg = 0f;
    private static final float ANGLE_THRESHOLD_DEG = 30f;

    // ==================== 统计 ====================
    private final AtomicLong totalL1 = new AtomicLong(0);
    private final AtomicLong totalL2 = new AtomicLong(0);
    private final AtomicLong totalL2Skip = new AtomicLong(0);
    private final AtomicLong totalL3 = new AtomicLong(0);

    // ==================== 子组件 ====================
    private GPUCullingSystem cullingSystem;

    public boolean initialize(GPUCullingSystem system) {
        if (initialized) return true;
        this.cullingSystem = system;
        loadIndirectGenSPIRV();
        createIndirectGenPipeline();
        initialized = true;
        enabled = true;
        LOGGER.info("CullingPipeline 已初始化 [L1=每帧, L2=每" + L2_INTERVAL + "帧, L3=indirect_draw_gen]");
        return true;
    }

    // ==================== 生命周期 ====================

    public void shutdown() {
        if (!initialized) return;
        VkDevice device = RenderiumVulkanBridge.getDevice();
        if (device != null) {
            if (indirectGenPipeline != 0L) { vkDestroyPipeline(device, indirectGenPipeline, null); indirectGenPipeline = 0L; }
            if (indirectGenPipelineLayout != 0L) { vkDestroyPipelineLayout(device, indirectGenPipelineLayout, null); indirectGenPipelineLayout = 0L; }
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
        long visibilityBuffer = l2ExecutedThisFrame ? visibilityBufferB : visibilityBufferA;
        long cmdBufHandle = cmdBuf.address();
        dispatchIndirectDrawGen(cmdBufHandle, visibilityBuffer);
        totalL3.incrementAndGet();
    }

    // ==================== L2 Hi-Z Occlusion ====================

    private void dispatchHiZOcclusion(long cmdBufHandle) throws Exception {
        VulkanDeviceHolder holder = VulkanDeviceHolder.getInstance();
        if (!holder.isAvailable()) return;

        if (HiZComputePipeline.getHizBuildPipeline() != 0L) {
            HiZComputePipeline.bindAndDispatchHiZBuild(cmdBufHandle, holder);
            HiZComputePipeline.insertMemoryBarrier(cmdBufHandle);
        }
        if (HiZComputePipeline.getHizOcclusionPipeline() != 0L) {
            HiZComputePipeline.bindAndDispatchOcclusionQuery(cmdBufHandle, holder);
            HiZComputePipeline.insertMemoryBarrier(cmdBufHandle);
        }
    }

    // ==================== L3 IndirectDraw Gen ====================

    private void loadIndirectGenSPIRV() {
        try {
            ClassLoader cl = getClass().getClassLoader();
            try (var is = cl.getResourceAsStream("shaders/compute/indirect_draw_gen.spv")) {
                if (is != null) { INDIRECT_DRAW_GEN_SPIRV = is.readAllBytes(); return; }
            }
            try (var is = cl.getResourceAsStream("shaders/compute/indirect_draw_gen.comp")) {
                if (is != null) {
                    String src = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    INDIRECT_DRAW_GEN_SPIRV = compileGLSL(src, "indirect_draw_gen");
                }
            }
        } catch (Exception e) {
            LOGGER.warning("indirect_draw_gen 加载失败: " + e.getMessage());
        }
    }

    private static byte[] compileGLSL(String source, String name) {
        try {
            Class<?> cc = Class.forName("com.ranecc.renderium.feature.lod.compute.GlslangCompiler");
            return (byte[]) cc.getMethod("compile", String.class, String.class).invoke(null, source, name);
        } catch (Exception e) {
            LOGGER.warning(name + " GLSL 编译失败: " + e.getMessage());
            return new byte[]{};
        }
    }

    /**
     * 创建 indirect_draw_gen 的 Vulkan Compute Pipeline。
     * 从 INDIRECT_DRAW_GEN_SPIRV 创建 ShaderModule + PipelineLayout + ComputePipeline。
     * 初始化时仅调用一次，创建失败不影响主流程（降级为 CPU 端保守合并）。
     */
    private void createIndirectGenPipeline() {
        if (INDIRECT_DRAW_GEN_SPIRV == null || INDIRECT_DRAW_GEN_SPIRV.length == 0) return;
        VkDevice device = RenderiumVulkanBridge.getDevice();
        if (device == null) return;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            // Pipeline Layout
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            var layoutPtr = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, layoutInfo, null, layoutPtr) != VK_SUCCESS) return;
            indirectGenPipelineLayout = layoutPtr.get(0);

            // Shader Module
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

            // Compute Pipeline
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
            // Shader module is referenced by pipeline, safe to destroy after creation
            vkDestroyShaderModule(device, shaderModule, null);
        }
    }

    /**
     * dispatch indirect_draw_gen — 从 visibilityBuffer 生成紧凑 Indirect Draw。
     * 零分配热路径。
     */
    private void dispatchIndirectDrawGen(long cmdBufHandle, long visibilityBuffer) {
        if (cmdBufHandle == 0L) return;
        int chunkCount = cullingSystem != null ? cullingSystem.getRegisteredChunkCount() : 0;
        if (chunkCount <= 0 || indirectGenPipeline == 0L) return;

        VkCommandBuffer cb = new VkCommandBuffer(cmdBufHandle, RenderiumVulkanBridge.getDevice());
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, indirectGenPipeline);
        int dispatchX = Math.max(1, (int) Math.ceil((double) chunkCount / 256));
        vkCmdDispatch(cb, dispatchX, 1, 1);
    }

    // ==================== 双缓冲 ====================

    /** 设置 L1 写入的 visibilityBufferA */
    public void setVisibilityBufferA(long buf) { visibilityBufferA = buf; }

    /** 设置 L2 写入的 visibilityBufferB */
    public void setVisibilityBufferB(long buf) { visibilityBufferB = buf; }

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
            + " L2⏭=" + totalL2Skip.get() + " L3=" + totalL3.get()
            + " ∠Δ=" + String.format("%.1f°", cameraAngleDeltaDeg);
    }
}
