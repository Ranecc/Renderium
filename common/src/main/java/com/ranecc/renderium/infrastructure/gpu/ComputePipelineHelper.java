package com.ranecc.renderium.infrastructure.gpu;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.infrastructure.gpu.PerFrameArena;
import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;
import com.ranecc.renderium.infrastructure.gpu.VulkanStructs;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.logging.Logger;

public final class ComputePipelineHelper {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ComputeHelper");

    public static final long VK_SHADER_STAGE_COMPUTE_BIT = 0x00000020L;
    public static final long VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 11L;
    public static final long VK_DESCRIPTOR_TYPE_STORAGE_IMAGE = 10L;
    public static final long VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 12L;
    public static final long VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6L;
    public static final long VK_IMAGE_LAYOUT_GENERAL = 0L;

    private ComputePipelineHelper() {}

    public static record Binding(int binding, long descriptorType) {}
    public static record PushConstant(int offset, int size, long stageFlags) {}

    public static record PipelineResources(
        long pipeline, long pipelineLayout, long descriptorSetLayout,
        long descriptorPool, long descriptorSet
    ) {}

    public static PipelineResources createComputePipeline(
            byte[] spirv, Binding[] bindings, PushConstant pushConstant) {
        long device = VulkanDeviceHolder.getInstance().getDevice();
        if (device == 0L) return null;

        try {
            long dev = device;

            // 1) ShaderModule
            MethodHandle vkCreateSM = VulkanAPIRegistry.getHandle("vkCreateShaderModule");
            if (vkCreateSM == null) return null;

            MemorySegment spirvSeg = PerFrameArena.allocate(spirv.length);
            for (int i = 0; i < spirv.length; i++) spirvSeg.set(ValueLayout.JAVA_BYTE, i, spirv[i]);

            MemorySegment moduleCI = PerFrameArena.allocateLongs(4);
            moduleCI.setAtIndex(ValueLayout.JAVA_LONG, 0, 46L); // SHADER_MODULE_CREATE_INFO
            moduleCI.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            moduleCI.setAtIndex(ValueLayout.JAVA_LONG, 2, spirv.length);
            moduleCI.setAtIndex(ValueLayout.JAVA_LONG, 3, spirvSeg.address());
            MemorySegment moduleOut = PerFrameArena.allocateLongs(1);
            int rc = (int) vkCreateSM.invokeExact(dev, moduleCI.address(), 0L, moduleOut.address());
            if (rc != 0) { LOGGER.fine("vkCreateShaderModule 失败: " + rc); return null; }
            long shaderModule = moduleOut.get(ValueLayout.JAVA_LONG, 0);

            // 2) DescriptorSetLayout
            MethodHandle vkCreateDSL = VulkanAPIRegistry.getHandle("vkCreateDescriptorSetLayout");
            if (vkCreateDSL == null) return null;

            MemorySegment bindingSeg = PerFrameArena.allocate(bindings.length * 40L);
            for (int i = 0; i < bindings.length; i++) {
                bindingSeg.setAtIndex(ValueLayout.JAVA_LONG, i * 5L + 0, bindings[i].binding());
                bindingSeg.setAtIndex(ValueLayout.JAVA_LONG, i * 5L + 1, bindings[i].descriptorType());
                bindingSeg.setAtIndex(ValueLayout.JAVA_LONG, i * 5L + 2, 1L);
                bindingSeg.setAtIndex(ValueLayout.JAVA_LONG, i * 5L + 3, VK_SHADER_STAGE_COMPUTE_BIT);
                bindingSeg.setAtIndex(ValueLayout.JAVA_LONG, i * 5L + 4, 0L);
            }

            MemorySegment dslCI = PerFrameArena.allocateLongs(5);
            dslCI.setAtIndex(ValueLayout.JAVA_LONG, 0, 11L);
            dslCI.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
            dslCI.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            dslCI.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) bindings.length);
            dslCI.setAtIndex(ValueLayout.JAVA_LONG, 4, bindingSeg.address());
            MemorySegment dslOut = PerFrameArena.allocateLongs(1);
            vkCreateDSL.invokeExact(dev, dslCI.address(), 0L, dslOut.address());
            long descriptorSetLayout = dslOut.get(ValueLayout.JAVA_LONG, 0);

            // 3) PipelineLayout
            MethodHandle vkCreatePL = VulkanAPIRegistry.getHandle("vkCreatePipelineLayout");
            if (vkCreatePL == null) return null;

            boolean hasPC = pushConstant != null && pushConstant.size() > 0;
            if (hasPC) {
                MemorySegment pcRange = PerFrameArena.allocateLongs(3);
                pcRange.setAtIndex(ValueLayout.JAVA_LONG, 0, pushConstant.stageFlags());
                pcRange.setAtIndex(ValueLayout.JAVA_LONG, 1, (long) pushConstant.offset());
                pcRange.setAtIndex(ValueLayout.JAVA_LONG, 2, (long) pushConstant.size());
                MemorySegment plCI = PerFrameArena.allocateLongs(6);
                plCI.setAtIndex(ValueLayout.JAVA_LONG, 0, 24L);
                plCI.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
                plCI.setAtIndex(ValueLayout.JAVA_LONG, 2, 1L);
                plCI.setAtIndex(ValueLayout.JAVA_LONG, 3, descriptorSetLayout);
                plCI.setAtIndex(ValueLayout.JAVA_LONG, 4, 1L);
                plCI.setAtIndex(ValueLayout.JAVA_LONG, 5, pcRange.address());
                MemorySegment plOut = PerFrameArena.allocateLongs(1);
                vkCreatePL.invokeExact(dev, plCI.address(), 0L, plOut.address());
                long pipelineLayout = plOut.get(ValueLayout.JAVA_LONG, 0);

                // 4) Compute Pipeline
                MethodHandle vkCreateCP = VulkanAPIRegistry.getHandle("vkCreateComputePipelines");
                if (vkCreateCP == null) return null;
                MemorySegment stageInfo = PerFrameArena.allocateLongs(5);
                stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 23L);
                stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
                stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, VK_SHADER_STAGE_COMPUTE_BIT);
                stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 3, shaderModule);
                stageInfo.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);
                MemorySegment cpCI = PerFrameArena.allocateLongs(4);
                cpCI.setAtIndex(ValueLayout.JAVA_LONG, 0, 31L);
                cpCI.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
                cpCI.setAtIndex(ValueLayout.JAVA_LONG, 2, stageInfo.address());
                cpCI.setAtIndex(ValueLayout.JAVA_LONG, 3, pipelineLayout);
                MemorySegment cpOut = PerFrameArena.allocateLongs(1);
                vkCreateCP.invokeExact(dev, 0L, 1, cpCI.address(), 0L, cpOut.address());
                long pipeline = cpOut.get(ValueLayout.JAVA_LONG, 0);

                // 5) Simple DescriptorPool + DescriptorSet
                long descriptorPool = allocatePool(dev, bindings.length);
                long descriptorSet = allocateDescriptorSet(dev, descriptorPool, descriptorSetLayout);

                LOGGER.fine(String.format("ComputePipeline 创建成功 | pipeline=0x%x layout=0x%x",
                    pipeline, pipelineLayout));
                return new PipelineResources(pipeline, pipelineLayout, descriptorSetLayout,
                    descriptorPool, descriptorSet);
            }
        } catch (Throwable t) {
            LOGGER.fine("ComputePipeline 创建失败: " + t.getMessage());
            return null;
        }
        return null;
    }

    private static long allocatePool(long device, int bindingCount) throws Throwable {
        MethodHandle vkCreateDP = VulkanAPIRegistry.getHandle("vkCreateDescriptorPool");
        if (vkCreateDP == null) return 0L;
        int[][] typeCounts = {{11, 4}, {10, 4}, {12, 1}, {6, 1}};
        MemorySegment poolSizes = PerFrameArena.allocate(typeCounts.length * 24L);
        for (int i = 0; i < typeCounts.length; i++) {
            poolSizes.setAtIndex(ValueLayout.JAVA_LONG, i * 3L + 0, (long) typeCounts[i][0]);
            poolSizes.setAtIndex(ValueLayout.JAVA_LONG, i * 3L + 1, (long) typeCounts[i][1]);
            poolSizes.setAtIndex(ValueLayout.JAVA_LONG, i * 3L + 2, 0L);
        }
        MemorySegment ci = PerFrameArena.allocateLongs(5);
        ci.setAtIndex(ValueLayout.JAVA_LONG, 0, 27L);
        ci.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        ci.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
        ci.setAtIndex(ValueLayout.JAVA_LONG, 3, (long) typeCounts.length);
        ci.setAtIndex(ValueLayout.JAVA_LONG, 4, poolSizes.address());
        MemorySegment out = PerFrameArena.allocateLongs(1);
        vkCreateDP.invokeExact(device, ci.address(), 0L, out.address());
        return out.get(ValueLayout.JAVA_LONG, 0);
    }

    private static long allocateDescriptorSet(long device, long pool, long layout) throws Throwable {
        MethodHandle vkAllocDS = VulkanAPIRegistry.getHandle("vkAllocateDescriptorSets");
        if (vkAllocDS == null) return 0L;
        MemorySegment ai = PerFrameArena.allocateLongs(5);
        ai.setAtIndex(ValueLayout.JAVA_LONG, 0, 46L);
        ai.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        ai.setAtIndex(ValueLayout.JAVA_LONG, 2, pool);
        ai.setAtIndex(ValueLayout.JAVA_LONG, 3, 1L);
        ai.setAtIndex(ValueLayout.JAVA_LONG, 4, layout);
        MemorySegment out = PerFrameArena.allocateLongs(1);
        vkAllocDS.invokeExact(device, ai.address(), out.address());
        return out.get(ValueLayout.JAVA_LONG, 0);
    }

    public static void updateImageDescriptor(long device, long descriptorSet,
            int binding, long imageView, long sampler, long imageLayout) {
        try {
            MethodHandle vkUpdateDS = VulkanAPIRegistry.getHandle("vkUpdateDescriptorSets");
            if (vkUpdateDS == null) return;

            MemorySegment imageInfo = PerFrameArena.allocateLongs(3);
            imageInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, sampler);
            imageInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, imageView);
            imageInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, imageLayout);

            MemorySegment writeDesc = PerFrameArena.allocate(64L);
            writeDesc.set(ValueLayout.JAVA_LONG, 0, 18L); // sType
            writeDesc.set(ValueLayout.JAVA_LONG, 8, 0L);
            writeDesc.set(ValueLayout.JAVA_LONG, 16, descriptorSet);
            writeDesc.set(ValueLayout.JAVA_INT, 24, binding);
            writeDesc.set(ValueLayout.JAVA_INT, 28, 0);
            writeDesc.set(ValueLayout.JAVA_INT, 32, 1);
            writeDesc.set(ValueLayout.JAVA_INT, 36, (int) VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            writeDesc.set(ValueLayout.JAVA_LONG, 40, imageInfo.address());
            writeDesc.set(ValueLayout.JAVA_LONG, 48, 0L);
            writeDesc.set(ValueLayout.JAVA_LONG, 56, 0L);
            vkUpdateDS.invokeExact(device, 1, writeDesc.address(), 0, 0L);
        } catch (Throwable t) {
            LOGGER.fine("updateImageDescriptor 失败: " + t.getMessage());
        }
    }

    public static void updateStorageImageDescriptor(long device, long descriptorSet,
            int binding, long imageView, long imageLayout) {
        try {
            MethodHandle vkUpdateDS = VulkanAPIRegistry.getHandle("vkUpdateDescriptorSets");
            if (vkUpdateDS == null) return;

            MemorySegment imageInfo = PerFrameArena.allocateLongs(3);
            imageInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L); // sampler = null for storage
            imageInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, imageView);
            imageInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, imageLayout);

            MemorySegment writeDesc = PerFrameArena.allocate(64L);
            writeDesc.set(ValueLayout.JAVA_LONG, 0, 18L);
            writeDesc.set(ValueLayout.JAVA_LONG, 8, 0L);
            writeDesc.set(ValueLayout.JAVA_LONG, 16, descriptorSet);
            writeDesc.set(ValueLayout.JAVA_INT, 24, binding);
            writeDesc.set(ValueLayout.JAVA_INT, 28, 0);
            writeDesc.set(ValueLayout.JAVA_INT, 32, 1);
            writeDesc.set(ValueLayout.JAVA_INT, 36, (int) VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            writeDesc.set(ValueLayout.JAVA_LONG, 40, imageInfo.address());
            writeDesc.set(ValueLayout.JAVA_LONG, 48, 0L);
            writeDesc.set(ValueLayout.JAVA_LONG, 56, 0L);
            vkUpdateDS.invokeExact(device, 1, writeDesc.address(), 0, 0L);
        } catch (Throwable t) {
            LOGGER.fine("updateStorageImageDescriptor 失败: " + t.getMessage());
        }
    }

    /**
     * 更新 Storage Buffer 描述符 (binding via VkDescriptorBufferInfo)
     * <p>
     * 用于将 SSBO (Storage Buffer) 绑定到 descriptorSet 的指定 binding 槽位。
     *
     * @param device        VkDevice
     * @param descriptorSet 目标 DescriptorSet 句柄
     * @param binding       binding 索引（须与 shader 中 layout(binding=N) 一致）
     * @param buffer        VkBuffer 句柄
     * @param offset        缓冲区偏移（字节）
     * @param range         缓冲区范围（字节）
     */
    public static void updateStorageBufferDescriptor(long device, long descriptorSet,
            int binding, long buffer, long offset, long range) {
        try {
            MethodHandle vkUpdateDS = VulkanAPIRegistry.getHandle("vkUpdateDescriptorSets");
            if (vkUpdateDS == null) return;

            MemorySegment bufferInfo = PerFrameArena.allocateLongs(3);
            bufferInfo.setAtIndex(ValueLayout.JAVA_LONG, 0, buffer);
            bufferInfo.setAtIndex(ValueLayout.JAVA_LONG, 1, offset);
            bufferInfo.setAtIndex(ValueLayout.JAVA_LONG, 2, range);

            MemorySegment writeDesc = PerFrameArena.allocate(64L);
            writeDesc.set(ValueLayout.JAVA_LONG, 0, 18L); // sType
            writeDesc.set(ValueLayout.JAVA_LONG, 8, 0L);  // pNext
            writeDesc.set(ValueLayout.JAVA_LONG, 16, descriptorSet);
            writeDesc.set(ValueLayout.JAVA_INT, 24, binding);
            writeDesc.set(ValueLayout.JAVA_INT, 28, 0);
            writeDesc.set(ValueLayout.JAVA_INT, 32, 1);
            writeDesc.set(ValueLayout.JAVA_INT, 36, (int) VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            writeDesc.set(ValueLayout.JAVA_LONG, 40, 0L); // pImageInfo = null
            writeDesc.set(ValueLayout.JAVA_LONG, 48, 0L); // pTexelBufferView = null
            writeDesc.set(ValueLayout.JAVA_LONG, 56, bufferInfo.address()); // pBufferInfo
            vkUpdateDS.invokeExact(device, 1, writeDesc.address(), 0, 0L);
        } catch (Throwable t) {
            LOGGER.fine("updateStorageBufferDescriptor 失败: " + t.getMessage());
        }
    }
}
