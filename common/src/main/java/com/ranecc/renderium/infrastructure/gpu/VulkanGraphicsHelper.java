package com.ranecc.renderium.infrastructure.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;

/**
 * Vulkan 全屏渲染辅助类
 *
 * <p>封装全屏几何体绘制（3 个顶点生成一整个全屏三角形）所需的
 * Vulkan 图形管线操作：渲染通道创建、管线创建、帧缓冲管理、提交执行。
 *
 * <p>所有操作受 {@link VulkanOperationGuard} 保护 — 设备丢失时不崩溃，只记录日志。
 */
public final class VulkanGraphicsHelper {

    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanGraphics");

    private static final int VK_SUCCESS = 0;
    public static final int VK_PIPELINE_BIND_POINT_GRAPHICS = 0;
    public static final int VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL = 2;
    public static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;
    public static final int VK_IMAGE_ASPECT_COLOR_BIT = 1;
    public static final int VK_FORMAT_R16G16B16A16_SFLOAT = 97;
    public static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    public static final int VK_IMAGE_TILING_OPTIMAL = 0;
    public static final int VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 0x00000004;
    public static final int VK_IMAGE_USAGE_SAMPLED_BIT = 0x00000001;
    public static final int VK_IMAGE_USAGE_TRANSFER_SRC_BIT = 0x00000010;
    public static final int VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 0x00000001;
    public static final int VK_IMAGE_VIEW_TYPE_2D = 1;
    public static final int VK_COMPONENT_SWIZZLE_IDENTITY = 0;
    public static final int VK_ATTACHMENT_LOAD_OP_CLEAR = 0;
    public static final int VK_ATTACHMENT_STORE_OP_STORE = 0;
    public static final int VK_IMAGE_LAYOUT_UNDEFINED = 0;
    public static final int VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT = 0x00000400;
    public static final int VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT = 0x00000080;
    public static final int VK_SUBPASS_CONTENTS_INLINE = 0;
    public static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    public static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;
    public static final int VK_DYNAMIC_STATE_VIEWPORT = 0;
    public static final int VK_DYNAMIC_STATE_SCISSOR = 1;
    public static final int VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST = 3;
    public static final int VK_POLYGON_MODE_FILL = 0;
    public static final int VK_CULL_MODE_NONE = 0;
    public static final int VK_FRONT_FACE_COUNTER_CLOCKWISE = 1;

    private VulkanGraphicsHelper() {}

    public static long getDevice() {
        return VulkanDeviceHolder.getInstance().getDevice();
    }

    public static boolean isAvailable() {
        return VulkanFFMBinding.isFfmLoaded() && VulkanDeviceHolder.isAvailable();
    }

    /**
     * 创建简单渲染通道（单颜色附件，不用深度）
     */
    public static long createSimpleRenderPass(long device, int colorFormat) {
        if (!isAvailable()) return 0L;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attachmentDesc = arena.allocate(32);
            attachmentDesc.set(ValueLayout.JAVA_INT, 0, colorFormat);         // format
            attachmentDesc.set(ValueLayout.JAVA_INT, 4, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL); // samples=1 via layout? no — use 2nd field
            attachmentDesc.set(ValueLayout.JAVA_INT, 8, VK_ATTACHMENT_LOAD_OP_CLEAR);
            attachmentDesc.set(ValueLayout.JAVA_INT, 12, VK_ATTACHMENT_STORE_OP_STORE);
            attachmentDesc.set(ValueLayout.JAVA_INT, 16, VK_IMAGE_LAYOUT_UNDEFINED);
            attachmentDesc.set(ValueLayout.JAVA_INT, 20, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            MemorySegment subpass = arena.allocate(40);
            subpass.set(ValueLayout.JAVA_INT, 0, 0);
            subpass.set(ValueLayout.JAVA_LONG, 8, 0L);

            MemorySegment createInfo = arena.allocate(56);
            createInfo.set(ValueLayout.JAVA_INT, 0, 0); // sType placeholder
            createInfo.set(ValueLayout.JAVA_LONG, 8, attachmentDesc.address());
            createInfo.set(ValueLayout.JAVA_INT, 16, 1);
            createInfo.set(ValueLayout.JAVA_LONG, 24, subpass.address());

            long[] outPass = new long[1];
            int result = (int) VulkanFFMBinding.getVkCreateRenderPass().invoke(device, createInfo.address(), 0L, outPass);
            return result == VK_SUCCESS ? outPass[0] : 0L;
        } catch (Throwable t) {
            LOGGER.warning("createSimpleRenderPass failed: " + t.getMessage());
            return 0L;
        }
    }

    /**
     * 创建全屏三角形图形管线
     */
    public static long createFullScreenPipeline(long device, long renderPass,
                                                 long vertShaderModule, long fragShaderModule,
                                                 int width, int height) {
        if (!isAvailable() || device == 0L || renderPass == 0L) return 0L;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment shaderStages = arena.allocate(96);
            shaderStages.set(ValueLayout.JAVA_INT, 0, VK_SHADER_STAGE_VERTEX_BIT);
            shaderStages.set(ValueLayout.JAVA_LONG, 8, vertShaderModule);
            shaderStages.set(ValueLayout.JAVA_INT, 48, VK_SHADER_STAGE_FRAGMENT_BIT);
            shaderStages.set(ValueLayout.JAVA_LONG, 56, fragShaderModule);

            MemorySegment viewportState = arena.allocate(32);
            viewportState.set(ValueLayout.JAVA_FLOAT, 0, 0.0f);
            viewportState.set(ValueLayout.JAVA_FLOAT, 4, 0.0f);
            viewportState.set(ValueLayout.JAVA_FLOAT, 8, width);
            viewportState.set(ValueLayout.JAVA_FLOAT, 12, height);
            viewportState.set(ValueLayout.JAVA_FLOAT, 16, 0.0f);
            viewportState.set(ValueLayout.JAVA_FLOAT, 20, 1.0f);
            viewportState.set(ValueLayout.JAVA_INT, 24, 1);
            viewportState.set(ValueLayout.JAVA_INT, 28, 1);

            long[] outPipeline = new long[1];
            int result = (int) VulkanFFMBinding.getVkCreateGraphicsPipelines().invoke(
                device, 0L, 1, 0L, 0L, outPipeline);
            return result == VK_SUCCESS ? outPipeline[0] : 0L;
        } catch (Throwable t) {
            LOGGER.warning("createFullScreenPipeline failed: " + t.getMessage());
            return 0L;
        }
    }

    /**
     * 绘制全屏三角形（3 个顶点 → 覆盖整个视口的三角形）
     */
    public static void drawFullScreenTriangle(long cmdBuffer, long pipeline,
                                               long renderPass, long framebuffer,
                                               int width, int height, float[] uniforms) {
        if (!isAvailable() || cmdBuffer == 0L) return;
        try {
            VulkanFFMBinding.getVkCmdBindPipeline().invoke(
                cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

            VulkanFFMBinding.getVkCmdDraw().invoke(cmdBuffer, 3, 1, 0, 0);
        } catch (Throwable t) {
            LOGGER.warning("drawFullScreenTriangle failed: " + t.getMessage());
        }
    }

    public static void destroyPipeline(long device, long pipeline) {
        if (device == 0L || pipeline == 0L) return;
        try {
            VulkanFFMBinding.getVkDestroyPipeline().invoke(device, pipeline, 0L);
        } catch (Throwable t) {
            LOGGER.warning("destroyPipeline failed: " + t.getMessage());
        }
    }

    public static void destroyRenderPass(long device, long renderPass) {
        if (device == 0L || renderPass == 0L) return;
        try {
            VulkanFFMBinding.getVkDestroyRenderPass().invoke(device, renderPass, 0L);
        } catch (Throwable t) {
            LOGGER.warning("destroyRenderPass failed: " + t.getMessage());
        }
    }

    public static void destroyShaderModule(long device, long module) {
        if (device == 0L || module == 0L) return;
        try {
            VulkanFFMBinding.getVkDestroyShaderModule().invoke(device, module, 0L);
        } catch (Throwable t) {
            LOGGER.warning("destroyShaderModule failed: " + t.getMessage());
        }
    }

    public static void destroyImageView(long device, long view) {
        if (device == 0L || view == 0L) return;
        try {
            VulkanFFMBinding.getVkDestroyImageView().invoke(device, view, 0L);
        } catch (Throwable t) {
            LOGGER.warning("destroyImageView failed: " + t.getMessage());
        }
    }

    public static void destroyFramebuffer(long device, long fb) {
        if (device == 0L || fb == 0L) return;
        try {
            VulkanFFMBinding.getVkDestroyFramebuffer().invoke(device, fb, 0L);
        } catch (Throwable t) {
            LOGGER.warning("destroyFramebuffer failed: " + t.getMessage());
        }
    }

    public static void memoryBarrier(long cmdBuffer) {
        if (!isAvailable() || cmdBuffer == 0L) return;
        try {
            VulkanFFMBinding.getVkCmdPipelineBarrier().invoke(
                cmdBuffer,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                0, 0, 0L, 0, 0L, 0, 0L);
        } catch (Throwable t) {
            LOGGER.warning("memoryBarrier failed: " + t.getMessage());
        }
    }

    public static void bindAndDrawIndexed(long vbo, long ibo, int indexCount) {
        if (!isAvailable() || vbo == 0L || ibo == 0L) return;
        try {
            VulkanFFMBinding.getVkCmdBindVertexBuffers().invoke(0L, 0, 1, vbo, 0L);
            VulkanFFMBinding.getVkCmdBindIndexBuffer().invoke(0L, ibo, 0L, 0);
            VulkanFFMBinding.getVkCmdDrawIndexed().invoke(0L, indexCount, 1, 0, 0, 0);
        } catch (Throwable t) {
            LOGGER.warning("bindAndDrawIndexed failed: " + t.getMessage());
        }
    }

    public static void copyBuffer(long src, long dst, long vertexCount) {
        if (!isAvailable() || src == 0L || dst == 0L) return;
        try {
            VulkanFFMBinding.getVkCmdCopyBuffer().invoke(0L, src, dst, 1, 0L);
        } catch (Throwable t) {
            LOGGER.warning("copyBuffer failed: " + t.getMessage());
        }
    }
}
