package com.ranecc.renderium.feature.lod.compute;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vulkan FFM (Foreign Function & Memory) 方法句柄绑定
 * <p>
 * 通过 Java 22+ Panama FFM API 加载 Vulkan 原生库 (vulkan-1.dll / libvulkan.so)
 * 中的所有 Vulkan API 函数指针，缓存为 {@link MethodHandle} 供其他类调用。
 * </p>
 *
 * <h3>加载的函数列表：</h3>
 * <ul>
 *   <li>vkCreateShaderModule / vkDestroyShaderModule</li>
 *   <li>vkCreateComputePipelines / vkDestroyPipeline</li>
 *   <li>vkAllocateCommandBuffers / vkBeginCommandBuffer / vkEndCommandBuffer</li>
 *   <li>vkCmdBindPipeline / vkCmdBindDescriptorSets / vkCmdDispatch</li>
 *   <li>vkCmdPipelineBarrier</li>
 *   <li>vkQueueSubmit / vkWaitForFences / vkResetFences</li>
 *   <li>vkCreateFence / vkDestroyFence</li>
 *   <li>vkCreateCommandPool / vkDestroyCommandPool</li>
 *   <li>vkCreatePipelineLayout / vkDestroyPipelineLayout</li>
 *   <li>vkCreateDescriptorSetLayout / vkDestroyDescriptorSetLayout</li>
 * </ul>
 *
 * <h3>使用方式：</h3>
 * <pre>
 * // 所有 MethodHandle 通过静态 getter 访问
 * MethodHandle createShaderModule = VulkanFFMBinding.getVkCreateShaderModule();
 * </pre>
 */
public final class VulkanFFMBinding {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|VulkanFFM");

    // ==================== FFM 方法句柄（Vulkan API）====================

    /** vkCreateShaderModule: 创建着色器模块 */
    private static volatile MethodHandle VK_CREATE_SHADER_MODULE;

    /** vkCreateComputePipelines: 创建计算管线 */
    private static volatile MethodHandle VK_CREATE_COMPUTE_PIPELINES;

    /** vkDestroyShaderModule: 销毁着色器模块 */
    private static volatile MethodHandle VK_DESTROY_SHADER_MODULE;

    /** vkDestroyPipeline: 销毁管线 */
    private static volatile MethodHandle VK_DESTROY_PIPELINE;

    /** vkDestroyPipelineLayout: 销毁管线布局 */
    private static volatile MethodHandle VK_DESTROY_PIPELINE_LAYOUT;

    /** vkDestroyDescriptorSetLayout: 销毁描述符集布局 */
    private static volatile MethodHandle VK_DESTROY_DESCRIPTOR_SET_LAYOUT;

    /** vkAllocateCommandBuffers: 分配命令缓冲区 */
    private static volatile MethodHandle VK_ALLOCATE_COMMAND_BUFFERS;

    /** vkBeginCommandBuffer: 开始命令缓冲区录制 */
    private static volatile MethodHandle VK_BEGIN_COMMAND_BUFFER;

    /** vkEndCommandBuffer: 结束命令缓冲区录制 */
    private static volatile MethodHandle VK_END_COMMAND_BUFFER;

    /** vkCmdBindPipeline: 绑定管线 */
    private static volatile MethodHandle VK_CMD_BIND_PIPELINE;

    /** vkCmdBindDescriptorSets: 绑定描述符集 */
    private static volatile MethodHandle VK_CMD_BIND_DESCRIPTOR_SETS;

    /** vkCmdDispatch: 分发计算任务 */
    private static volatile MethodHandle VK_CMD_DISPATCH;

    /** vkCmdPipelineBarrier: 插入管线屏障 */
    private static volatile MethodHandle VK_CMD_PIPELINE_BARRIER;

    /** vkQueueSubmit: 提交队列 */
    private static volatile MethodHandle VK_QUEUE_SUBMIT;

    /** vkWaitForFences: 等待栅栏 */
    private static volatile MethodHandle VK_WAIT_FOR_FENCES;

    /** vkResetFences: 重置栅栏 */
    private static volatile MethodHandle VK_RESET_FENCES;

    /** vkCreateFence: 创建栅栏 */
    private static volatile MethodHandle VK_CREATE_FENCE;

    /** vkDestroyFence: 销毁栅栏 */
    private static volatile MethodHandle VK_DESTROY_FENCE;

    /** vkCreateCommandPool: 创建命令池 */
    private static volatile MethodHandle VK_CREATE_COMMAND_POOL;

    /** vkDestroyCommandPool: 销毁命令池 */
    private static volatile MethodHandle VK_DESTROY_COMMAND_POOL;

    /** vkCreatePipelineLayout: 创建管线布局 */
    private static volatile MethodHandle VK_CREATE_PIPELINE_LAYOUT;

    /** vkCreateDescriptorSetLayout: 创建描述符集布局 */
    private static volatile MethodHandle VK_CREATE_DESCRIPTOR_SET_LAYOUT;

    // ============ 新增: 图形管线函数 ============

    /** vkCreateGraphicsPipelines: 创建图形管线 */
    private static volatile MethodHandle VK_CREATE_GRAPHICS_PIPELINES;

    /** vkCmdDraw: 绘制非索引几何体 */
    private static volatile MethodHandle VK_CMD_DRAW;

    /** vkCreateRenderPass: 创建渲染通道 */
    private static volatile MethodHandle VK_CREATE_RENDER_PASS;

    /** vkDestroyRenderPass: 销毁渲染通道 */
    private static volatile MethodHandle VK_DESTROY_RENDER_PASS;

    /** vkCmdBeginRenderPass: 开始渲染通道 */
    private static volatile MethodHandle VK_CMD_BEGIN_RENDER_PASS;

    /** vkCmdEndRenderPass: 结束渲染通道 */
    private static volatile MethodHandle VK_CMD_END_RENDER_PASS;

    /** vkCreateImage: 创建图像 */
    private static volatile MethodHandle VK_CREATE_IMAGE;

    /** vkDestroyImage: 销毁图像 */
    private static volatile MethodHandle VK_DESTROY_IMAGE;

    /** vkCreateImageView: 创建图像视图 */
    private static volatile MethodHandle VK_CREATE_IMAGE_VIEW;

    /** vkDestroyImageView: 销毁图像视图 */
    private static volatile MethodHandle VK_DESTROY_IMAGE_VIEW;

    /** vkCreateSampler: 创建采样器 */
    private static volatile MethodHandle VK_CREATE_SAMPLER;

    /** vkDestroySampler: 销毁采样器 */
    private static volatile MethodHandle VK_DESTROY_SAMPLER;

    /** vkAllocateMemory: 分配设备内存 */
    private static volatile MethodHandle VK_ALLOCATE_MEMORY;

    /** vkFreeMemory: 释放设备内存 */
    private static volatile MethodHandle VK_FREE_MEMORY;

    /** vkBindImageMemory: 绑定图像和内存 */
    private static volatile MethodHandle VK_BIND_IMAGE_MEMORY;

    /** vkGetImageMemoryRequirements: 获取内存需求 */
    private static volatile MethodHandle VK_GET_IMAGE_MEMORY_REQUIREMENTS;

    /** vkCreateFramebuffer: 创建帧缓冲 */
    private static volatile MethodHandle VK_CREATE_FRAMEBUFFER;

    /** vkDestroyFramebuffer: 销毁帧缓冲 */
    private static volatile MethodHandle VK_DESTROY_FRAMEBUFFER;

    // ============ DescriptorPool / DescriptorSet ============

    /** vkCreateDescriptorPool: 创建描述符池 */
    private static volatile MethodHandle VK_CREATE_DESCRIPTOR_POOL;

    /** vkDestroyDescriptorPool: 销毁描述符池 */
    private static volatile MethodHandle VK_DESTROY_DESCRIPTOR_POOL;

    /** vkAllocateDescriptorSets: 分配描述符集 */
    private static volatile MethodHandle VK_ALLOCATE_DESCRIPTOR_SETS;

    /** vkUpdateDescriptorSets: 更新描述符集写入 */
    private static volatile MethodHandle VK_UPDATE_DESCRIPTOR_SETS;

    /** vkFreeDescriptorSets: 释放描述符集 */
    private static volatile MethodHandle VK_FREE_DESCRIPTOR_SETS;

    // ============ GPU→CPU Readback ============

    /** vkMapMemory: 映射设备内存 */
    private static volatile MethodHandle VK_MAP_MEMORY;

    /** vkUnmapMemory: 解除内存映射 */
    private static volatile MethodHandle VK_UNMAP_MEMORY;

    /** vkInvalidateMappedMemoryRanges: 使 CPU 缓存失效 */
    private static volatile MethodHandle VK_INVALIDATE_MAPPED_MEMORY_RANGES;

    /** vkFlushMappedMemoryRanges: 刷新 CPU 缓存 */
    private static volatile MethodHandle VK_FLUSH_MAPPED_MEMORY_RANGES;

    // ============ Buffer 操作 ============

    /** vkCmdCopyBuffer: 缓冲区拷贝 */
    private static volatile MethodHandle VK_CMD_COPY_BUFFER;

    /** vkCmdBindVertexBuffers: 绑定顶点缓冲 */
    private static volatile MethodHandle VK_CMD_BIND_VERTEX_BUFFERS;

    /** vkCmdBindIndexBuffer: 绑定索引缓冲 */
    private static volatile MethodHandle VK_CMD_BIND_INDEX_BUFFER;

    /** vkCmdDrawIndexed: 索引绘制 */
    private static volatile MethodHandle VK_CMD_DRAW_INDEXED;

    // ============ Buffer 基础操作 ============

    /** vkCreateBuffer: 创建缓冲区 */
    private static volatile MethodHandle VK_CREATE_BUFFER;

    /** vkDestroyBuffer: 销毁缓冲区 */
    private static volatile MethodHandle VK_DESTROY_BUFFER;

    /** vkGetBufferMemoryRequirements: 获取缓冲区内存需求 */
    private static volatile MethodHandle VK_GET_BUFFER_MEMORY_REQUIREMENTS;

    /** vkBindBufferMemory: 绑定缓冲区内存 */
    private static volatile MethodHandle VK_BIND_BUFFER_MEMORY;

    /** vkFreeCommandBuffers: 释放命令缓冲区 */
    private static volatile MethodHandle VK_FREE_COMMAND_BUFFERS;

    // ============ Phase 4: Timeline Semaphore ============

    /** vkCreateSemaphore: 创建信号量 */
    private static volatile MethodHandle VK_CREATE_SEMAPHORE;

    /** vkDestroySemaphore: 销毁信号量 */
    private static volatile MethodHandle VK_DESTROY_SEMAPHORE;

    /** vkWaitSemaphores: 等待时间线信号量 */
    private static volatile MethodHandle VK_WAIT_SEMAPHORES;

    /** vkSignalSemaphore: 信号时间线信号量 */
    private static volatile MethodHandle VK_SIGNAL_SEMAPHORE;

    /** vkGetSemaphoreCounterValue: 查询信号量计数器 */
    private static volatile MethodHandle VK_GET_SEMAPHORE_COUNTER_VALUE;

    // ============ Phase 5: Ray Tracing ============

    /** vkCreateAccelerationStructureKHR: 创建加速结构 */
    private static volatile MethodHandle VK_CREATE_ACCELERATION_STRUCTURE_KHR;

    /** vkDestroyAccelerationStructureKHR: 销毁加速结构 */
    private static volatile MethodHandle VK_DESTROY_ACCELERATION_STRUCTURE_KHR;

    /** vkCmdBuildAccelerationStructuresKHR: 构建加速结构 */
    private static volatile MethodHandle VK_CMD_BUILD_ACCELERATION_STRUCTURES_KHR;

    /** vkCmdTraceRaysKHR: 发射光线 */
    private static volatile MethodHandle VK_CMD_TRACE_RAYS_KHR;

    /** vkCreateRayTracingPipelinesKHR: 创建光线追踪管线 */
    private static volatile MethodHandle VK_CREATE_RAY_TRACING_PIPELINES_KHR;

    /** vkCmdCopyAccelerationStructureKHR: 复制加速结构 */
    private static volatile MethodHandle VK_CMD_COPY_ACCELERATION_STRUCTURE_KHR;

    // ============ Phase 6: Mesh Shader ============

    /** vkCmdDrawMeshTasksEXT: 绘制 Mesh Shader 工作组 */
    private static volatile MethodHandle VK_CMD_DRAW_MESH_TASKS_EXT;

    // ==================== 加载状态 ====================

    /** FFM 方法是否已加载 */
    private static volatile boolean ffmLoaded = false;

    // ==================== 构造函数（私有）====================

    private VulkanFFMBinding() {}

    // ==================== 静态初始化块 ====================

    static {
        loadFFMMethodHandles();
    }

    // ==================== 公共 getter ====================

    public static MethodHandle getVkCreateShaderModule() { return VK_CREATE_SHADER_MODULE; }
    public static MethodHandle getVkCreateComputePipelines() { return VK_CREATE_COMPUTE_PIPELINES; }
    public static MethodHandle getVkDestroyShaderModule() { return VK_DESTROY_SHADER_MODULE; }
    public static MethodHandle getVkDestroyPipeline() { return VK_DESTROY_PIPELINE; }
    public static MethodHandle getVkDestroyPipelineLayout() { return VK_DESTROY_PIPELINE_LAYOUT; }
    public static MethodHandle getVkDestroyDescriptorSetLayout() { return VK_DESTROY_DESCRIPTOR_SET_LAYOUT; }
    public static MethodHandle getVkAllocateCommandBuffers() { return VK_ALLOCATE_COMMAND_BUFFERS; }
    public static MethodHandle getVkBeginCommandBuffer() { return VK_BEGIN_COMMAND_BUFFER; }
    public static MethodHandle getVkEndCommandBuffer() { return VK_END_COMMAND_BUFFER; }
    public static MethodHandle getVkCmdBindPipeline() { return VK_CMD_BIND_PIPELINE; }
    public static MethodHandle getVkCmdBindDescriptorSets() { return VK_CMD_BIND_DESCRIPTOR_SETS; }
    public static MethodHandle getVkCmdDispatch() { return VK_CMD_DISPATCH; }
    public static MethodHandle getVkCmdPipelineBarrier() { return VK_CMD_PIPELINE_BARRIER; }
    public static MethodHandle getVkQueueSubmit() { return VK_QUEUE_SUBMIT; }
    public static MethodHandle getVkWaitForFences() { return VK_WAIT_FOR_FENCES; }
    public static MethodHandle getVkResetFences() { return VK_RESET_FENCES; }
    public static MethodHandle getVkCreateFence() { return VK_CREATE_FENCE; }
    public static MethodHandle getVkDestroyFence() { return VK_DESTROY_FENCE; }
    public static MethodHandle getVkCreateCommandPool() { return VK_CREATE_COMMAND_POOL; }
    public static MethodHandle getVkDestroyCommandPool() { return VK_DESTROY_COMMAND_POOL; }
    public static MethodHandle getVkCreatePipelineLayout() { return VK_CREATE_PIPELINE_LAYOUT; }
    public static MethodHandle getVkCreateDescriptorSetLayout() { return VK_CREATE_DESCRIPTOR_SET_LAYOUT; }

    public static MethodHandle getVkCreateGraphicsPipelines() { return VK_CREATE_GRAPHICS_PIPELINES; }
    public static MethodHandle getVkCmdDraw() { return VK_CMD_DRAW; }
    public static MethodHandle getVkCreateRenderPass() { return VK_CREATE_RENDER_PASS; }
    public static MethodHandle getVkDestroyRenderPass() { return VK_DESTROY_RENDER_PASS; }
    public static MethodHandle getVkCmdBeginRenderPass() { return VK_CMD_BEGIN_RENDER_PASS; }
    public static MethodHandle getVkCmdEndRenderPass() { return VK_CMD_END_RENDER_PASS; }
    public static MethodHandle getVkCreateImage() { return VK_CREATE_IMAGE; }
    public static MethodHandle getVkDestroyImage() { return VK_DESTROY_IMAGE; }
    public static MethodHandle getVkCreateImageView() { return VK_CREATE_IMAGE_VIEW; }
    public static MethodHandle getVkDestroyImageView() { return VK_DESTROY_IMAGE_VIEW; }
    public static MethodHandle getVkCreateSampler() { return VK_CREATE_SAMPLER; }
    public static MethodHandle getVkDestroySampler() { return VK_DESTROY_SAMPLER; }
    public static MethodHandle getVkAllocateMemory() { return VK_ALLOCATE_MEMORY; }
    public static MethodHandle getVkFreeMemory() { return VK_FREE_MEMORY; }
    public static MethodHandle getVkBindImageMemory() { return VK_BIND_IMAGE_MEMORY; }
    public static MethodHandle getVkGetImageMemoryRequirements() { return VK_GET_IMAGE_MEMORY_REQUIREMENTS; }
    public static MethodHandle getVkCreateFramebuffer() { return VK_CREATE_FRAMEBUFFER; }
    public static MethodHandle getVkDestroyFramebuffer() { return VK_DESTROY_FRAMEBUFFER; }

    public static MethodHandle getVkCreateDescriptorPool() { return VK_CREATE_DESCRIPTOR_POOL; }
    public static MethodHandle getVkDestroyDescriptorPool() { return VK_DESTROY_DESCRIPTOR_POOL; }
    public static MethodHandle getVkAllocateDescriptorSets() { return VK_ALLOCATE_DESCRIPTOR_SETS; }
    public static MethodHandle getVkUpdateDescriptorSets() { return VK_UPDATE_DESCRIPTOR_SETS; }
    public static MethodHandle getVkFreeDescriptorSets() { return VK_FREE_DESCRIPTOR_SETS; }
    public static MethodHandle getVkMapMemory() { return VK_MAP_MEMORY; }
    public static MethodHandle getVkUnmapMemory() { return VK_UNMAP_MEMORY; }
    public static MethodHandle getVkInvalidateMappedMemoryRanges() { return VK_INVALIDATE_MAPPED_MEMORY_RANGES; }
    public static MethodHandle getVkFlushMappedMemoryRanges() { return VK_FLUSH_MAPPED_MEMORY_RANGES; }
    public static MethodHandle getVkCmdCopyBuffer() { return VK_CMD_COPY_BUFFER; }
    public static MethodHandle getVkCmdBindVertexBuffers() { return VK_CMD_BIND_VERTEX_BUFFERS; }
    public static MethodHandle getVkCmdBindIndexBuffer() { return VK_CMD_BIND_INDEX_BUFFER; }
    public static MethodHandle getVkCmdDrawIndexed() { return VK_CMD_DRAW_INDEXED; }
    public static MethodHandle getVkCreateBuffer() { return VK_CREATE_BUFFER; }
    public static MethodHandle getVkDestroyBuffer() { return VK_DESTROY_BUFFER; }
    public static MethodHandle getVkGetBufferMemoryRequirements() { return VK_GET_BUFFER_MEMORY_REQUIREMENTS; }
    public static MethodHandle getVkBindBufferMemory() { return VK_BIND_BUFFER_MEMORY; }
    public static MethodHandle getVkFreeCommandBuffers() { return VK_FREE_COMMAND_BUFFERS; }
    public static MethodHandle getVkCreateSemaphore() { return VK_CREATE_SEMAPHORE; }
    public static MethodHandle getVkDestroySemaphore() { return VK_DESTROY_SEMAPHORE; }
    public static MethodHandle getVkWaitSemaphores() { return VK_WAIT_SEMAPHORES; }
    public static MethodHandle getVkSignalSemaphore() { return VK_SIGNAL_SEMAPHORE; }
    public static MethodHandle getVkGetSemaphoreCounterValue() { return VK_GET_SEMAPHORE_COUNTER_VALUE; }
    public static MethodHandle getVkCreateAccelerationStructureKHR() { return VK_CREATE_ACCELERATION_STRUCTURE_KHR; }
    public static MethodHandle getVkDestroyAccelerationStructureKHR() { return VK_DESTROY_ACCELERATION_STRUCTURE_KHR; }
    public static MethodHandle getVkCmdBuildAccelerationStructuresKHR() { return VK_CMD_BUILD_ACCELERATION_STRUCTURES_KHR; }
    public static MethodHandle getVkCmdTraceRaysKHR() { return VK_CMD_TRACE_RAYS_KHR; }
    public static MethodHandle getVkCreateRayTracingPipelinesKHR() { return VK_CREATE_RAY_TRACING_PIPELINES_KHR; }
    public static MethodHandle getVkCmdCopyAccelerationStructureKHR() { return VK_CMD_COPY_ACCELERATION_STRUCTURE_KHR; }
    public static MethodHandle getVkCmdDrawMeshTasksEXT() { return VK_CMD_DRAW_MESH_TASKS_EXT; }

    /** FFM 方法句柄是否已加载成功 */
    public static boolean isFfmLoaded() { return ffmLoaded; }

    /**
     * 通过 Panama FFM 加载所有 Vulkan API 方法句柄
     * <p>
     * 使用 Linker.nativeLinker() 和 SymbolLookup.libraryLookup()
     * 加载 vulkan-1.dll (Windows) 或 libvulkan.so (Linux)
     * 中的 Vulkan C API 函数指针。
     *
     * <h3>加载的函数列表：</h3>
     * <ul>
     *   <li>vkCreateShaderModule / vkDestroyShaderModule</li>
     *   <li>vkCreateComputePipelines / vkDestroyPipeline</li>
     *   <li>vkAllocateCommandBuffers / vkBeginCommandBuffer / vkEndCommandBuffer</li>
     *   <li>vkCmdBindPipeline / vkCmdBindDescriptorSets / vkCmdDispatch</li>
     *   <li>vkCmdPipelineBarrier</li>
     *   <li>vkQueueSubmit / vkWaitForFences / vkResetFences</li>
     *   <li>vkCreateFence / vkDestroyFence</li>
     *   <li>vkCreateCommandPool / vkDestroyCommandPool</li>
     *   <li>vkCreatePipelineLayout / vkDestroyPipelineLayout</li>
     *   <li>vkCreateDescriptorSetLayout / vkDestroyDescriptorSetLayout</li>
     * </ul>
     */
    private static void loadFFMMethodHandles() {
        try {
            // 获取原生链接器
            Linker linker = Linker.nativeLinker();

            // 查找 Vulkan 库（Windows: vulkan-1.dll, Linux: libvulkan.so）
            String osName = System.getProperty("os.name").toLowerCase();
            String vulkanLibName = osName.contains("win") ? "vulkan-1" : "vulkan";

            var vulkanLookup = SymbolLookup.libraryLookup(vulkanLibName, Arena.ofAuto());

            // 定义 Vulkan 函数签名并加载方法句柄
            // 注意：这里使用简化的签名，实际参数可能需要更复杂的 MemoryLayout

            // vkCreateShaderModule(VkDevice, pCreateInfo, pAllocator, pShaderModule)
            VK_CREATE_SHADER_MODULE = linker.downcallHandle(
                    vulkanLookup.find("vkCreateShaderModule").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG,          // device: VkDevice
                            ValueLayout.JAVA_LONG,          // pCreateInfo: pointer
                            ValueLayout.JAVA_LONG,          // pAllocator: pointer
                            ValueLayout.JAVA_LONG           // pShaderModule: pointer (output)
                    )
            );

            // vkCreateComputePipelines(VkDevice, pipelineCache, createInfoCount, pCreateInfos, pAllocator, pPipelines)
            VK_CREATE_COMPUTE_PIPELINES = linker.downcallHandle(
                    vulkanLookup.find("vkCreateComputePipelines").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG,          // device
                            ValueLayout.JAVA_LONG,          // pipelineCache
                            ValueLayout.JAVA_INT,           // createInfoCount
                            ValueLayout.JAVA_LONG,          // pCreateInfos: pointer
                            ValueLayout.JAVA_LONG,          // pAllocator: pointer
                            ValueLayout.JAVA_LONG           // pPipelines: pointer (output)
                    )
            );

            // vkDestroyShaderModule(VkDevice, shaderModule, pAllocator)
            VK_DESTROY_SHADER_MODULE = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyShaderModule").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,          // device
                            ValueLayout.JAVA_LONG,          // shaderModule
                            ValueLayout.JAVA_LONG           // pAllocator
                    )
            );

            // vkDestroyPipeline(VkDevice, pipeline, pAllocator)
            VK_DESTROY_PIPELINE = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyPipeline").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,          // device
                            ValueLayout.JAVA_LONG,          // pipeline
                            ValueLayout.JAVA_LONG           // pAllocator
                    )
            );

            // vkBeginCommandBuffer(commandBuffer, pBeginInfo)
            VK_BEGIN_COMMAND_BUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkBeginCommandBuffer").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG,          // commandBuffer
                            ValueLayout.JAVA_LONG           // pBeginInfo: pointer
                    )
            );

            // vkEndCommandBuffer(commandBuffer)
            VK_END_COMMAND_BUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkEndCommandBuffer").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG           // commandBuffer
                    )
            );

            // vkCmdBindPipeline(commandBuffer, pipelineBindPoint, pipeline)
            VK_CMD_BIND_PIPELINE = linker.downcallHandle(
                    vulkanLookup.find("vkCmdBindPipeline").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,          // commandBuffer
                            ValueLayout.JAVA_INT,           // pipelineBindPoint
                            ValueLayout.JAVA_LONG           // pipeline
                    )
            );

            // vkCmdDispatch(commandBuffer, groupCountX, groupCountY, groupCountZ)
            VK_CMD_DISPATCH = linker.downcallHandle(
                    vulkanLookup.find("vkCmdDispatch").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,          // commandBuffer
                            ValueLayout.JAVA_INT,           // groupCountX
                            ValueLayout.JAVA_INT,           // groupCountY
                            ValueLayout.JAVA_INT            // groupCountZ
                    )
            );

            // vkCmdPipelineBarrier(commandBuffer, srcStageMask, dstStageMask, dependencyFlags,
            //                       memoryBarrierCount, pMemoryBuffers, bufferMemoryBarrierCount,
            //                       pBufferMemoryBarriers, imageMemoryBarrierCount, pImageMemoryBarriers)
            VK_CMD_PIPELINE_BARRIER = linker.downcallHandle(
                    vulkanLookup.find("vkCmdPipelineBarrier").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,          // commandBuffer
                            ValueLayout.JAVA_INT,           // srcStageMask
                            ValueLayout.JAVA_INT,           // dstStageMask
                            ValueLayout.JAVA_INT,           // dependencyFlags
                            ValueLayout.JAVA_INT,           // memoryBarrierCount
                            ValueLayout.JAVA_LONG,          // pMemoryBarriers
                            ValueLayout.JAVA_INT,           // bufferMemoryBarrierCount
                            ValueLayout.JAVA_LONG,          // pBufferMemoryBarriers
                            ValueLayout.JAVA_INT,           // imageMemoryBarrierCount
                            ValueLayout.JAVA_LONG           // pImageMemoryBarriers
                    )
            );

            // vkQueueSubmit(queue, submitCount, pSubmits, fence)
            VK_QUEUE_SUBMIT = linker.downcallHandle(
                    vulkanLookup.find("vkQueueSubmit").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG,          // queue
                            ValueLayout.JAVA_INT,           // submitCount
                            ValueLayout.JAVA_LONG,          // pSubmits: pointer
                            ValueLayout.JAVA_LONG           // fence
                    )
            );

            // vkWaitForFences(device, fenceCount, pFences, waitAll, timeout)
            VK_WAIT_FOR_FENCES = linker.downcallHandle(
                    vulkanLookup.find("vkWaitForFences").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG,          // device
                            ValueLayout.JAVA_INT,           // fenceCount
                            ValueLayout.JAVA_LONG,          // pFences: pointer
                            ValueLayout.JAVA_INT,           // waitAll
                            ValueLayout.JAVA_LONG           // timeout
                    )
            );

            // vkResetFences(device, fenceCount, pFences)
            VK_RESET_FENCES = linker.downcallHandle(
                    vulkanLookup.find("vkResetFences").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG,          // device
                            ValueLayout.JAVA_INT,           // fenceCount
                            ValueLayout.JAVA_LONG           // pFences: pointer
                    )
            );

            // vkAllocateCommandBuffer(device, pAllocateInfo, pCommandBuffers)
            VK_ALLOCATE_COMMAND_BUFFERS = linker.downcallHandle(
                    vulkanLookup.find("vkAllocateCommandBuffers").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,           // return: VkResult
                            ValueLayout.JAVA_LONG,          // device
                            ValueLayout.JAVA_LONG,          // pAllocateInfo: pointer
                            ValueLayout.JAVA_LONG           // pCommandBuffers: pointer (output)
                    )
            );

            // 其他方法句柄...
            VK_DESTROY_PIPELINE_LAYOUT = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyPipelineLayout").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_DESCRIPTOR_SET_LAYOUT = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyDescriptorSetLayout").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_FENCE = linker.downcallHandle(
                    vulkanLookup.find("vkCreateFence").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
                    )
            );

            VK_DESTROY_FENCE = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyFence").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_COMMAND_POOL = linker.downcallHandle(
                    vulkanLookup.find("vkCreateCommandPool").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
                    )
            );

            VK_DESTROY_COMMAND_POOL = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyCommandPool").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_PIPELINE_LAYOUT = linker.downcallHandle(
                    vulkanLookup.find("vkCreatePipelineLayout").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
                    )
            );

            VK_CREATE_DESCRIPTOR_SET_LAYOUT = linker.downcallHandle(
                    vulkanLookup.find("vkCreateDescriptorSetLayout").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG
                    )
            );

            VK_CMD_BIND_DESCRIPTOR_SETS = linker.downcallHandle(
                    vulkanLookup.find("vkCmdBindDescriptorSets").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG
                    )
            );

            // ============ 图形管线函数 ============

            VK_CREATE_GRAPHICS_PIPELINES = linker.downcallHandle(
                    vulkanLookup.find("vkCreateGraphicsPipelines").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CMD_DRAW = linker.downcallHandle(
                    vulkanLookup.find("vkCmdDraw").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT)
            );

            VK_CREATE_RENDER_PASS = linker.downcallHandle(
                    vulkanLookup.find("vkCreateRenderPass").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_RENDER_PASS = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyRenderPass").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CMD_BEGIN_RENDER_PASS = linker.downcallHandle(
                    vulkanLookup.find("vkCmdBeginRenderPass").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
            );

            VK_CMD_END_RENDER_PASS = linker.downcallHandle(
                    vulkanLookup.find("vkCmdEndRenderPass").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
            );

            VK_CREATE_IMAGE = linker.downcallHandle(
                    vulkanLookup.find("vkCreateImage").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_IMAGE = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyImage").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_IMAGE_VIEW = linker.downcallHandle(
                    vulkanLookup.find("vkCreateImageView").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_IMAGE_VIEW = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyImageView").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_SAMPLER = linker.downcallHandle(
                    vulkanLookup.find("vkCreateSampler").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_SAMPLER = linker.downcallHandle(
                    vulkanLookup.find("vkDestroySampler").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_ALLOCATE_MEMORY = linker.downcallHandle(
                    vulkanLookup.find("vkAllocateMemory").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_FREE_MEMORY = linker.downcallHandle(
                    vulkanLookup.find("vkFreeMemory").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_BIND_IMAGE_MEMORY = linker.downcallHandle(
                    vulkanLookup.find("vkBindImageMemory").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_GET_IMAGE_MEMORY_REQUIREMENTS = linker.downcallHandle(
                    vulkanLookup.find("vkGetImageMemoryRequirements").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_CREATE_FRAMEBUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkCreateFramebuffer").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_FRAMEBUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyFramebuffer").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_DESCRIPTOR_POOL = linker.downcallHandle(
                    vulkanLookup.find("vkCreateDescriptorPool").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_DESCRIPTOR_POOL = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyDescriptorPool").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_ALLOCATE_DESCRIPTOR_SETS = linker.downcallHandle(
                    vulkanLookup.find("vkAllocateDescriptorSets").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_UPDATE_DESCRIPTOR_SETS = linker.downcallHandle(
                    vulkanLookup.find("vkUpdateDescriptorSets").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG)
            );

            VK_FREE_DESCRIPTOR_SETS = linker.downcallHandle(
                    vulkanLookup.find("vkFreeDescriptorSets").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
            );

            VK_MAP_MEMORY = linker.downcallHandle(
                    vulkanLookup.find("vkMapMemory").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_UNMAP_MEMORY = linker.downcallHandle(
                    vulkanLookup.find("vkUnmapMemory").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_INVALIDATE_MAPPED_MEMORY_RANGES = linker.downcallHandle(
                    vulkanLookup.find("vkInvalidateMappedMemoryRanges").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG)
            );

            VK_FLUSH_MAPPED_MEMORY_RANGES = linker.downcallHandle(
                    vulkanLookup.find("vkFlushMappedMemoryRanges").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG)
            );

            VK_CMD_COPY_BUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkCmdCopyBuffer").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG)
            );

            VK_CMD_BIND_VERTEX_BUFFERS = linker.downcallHandle(
                    vulkanLookup.find("vkCmdBindVertexBuffers").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_CMD_BIND_INDEX_BUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkCmdBindIndexBuffer").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)
            );

            VK_CMD_DRAW_INDEXED = linker.downcallHandle(
                    vulkanLookup.find("vkCmdDrawIndexed").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );

            VK_CREATE_BUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkCreateBuffer").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_BUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyBuffer").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_GET_BUFFER_MEMORY_REQUIREMENTS = linker.downcallHandle(
                    vulkanLookup.find("vkGetBufferMemoryRequirements").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_BIND_BUFFER_MEMORY = linker.downcallHandle(
                    vulkanLookup.find("vkBindBufferMemory").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_FREE_COMMAND_BUFFERS = linker.downcallHandle(
                    vulkanLookup.find("vkFreeCommandBuffers").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_SEMAPHORE = linker.downcallHandle(
                    vulkanLookup.find("vkCreateSemaphore").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_SEMAPHORE = linker.downcallHandle(
                    vulkanLookup.find("vkDestroySemaphore").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_WAIT_SEMAPHORES = linker.downcallHandle(
                    vulkanLookup.find("vkWaitSemaphores").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_SIGNAL_SEMAPHORE = linker.downcallHandle(
                    vulkanLookup.find("vkSignalSemaphore").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_GET_SEMAPHORE_COUNTER_VALUE = linker.downcallHandle(
                    vulkanLookup.find("vkGetSemaphoreCounterValue").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CREATE_ACCELERATION_STRUCTURE_KHR = linker.downcallHandle(
                    vulkanLookup.find("vkCreateAccelerationStructureKHR").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_DESTROY_ACCELERATION_STRUCTURE_KHR = linker.downcallHandle(
                    vulkanLookup.find("vkDestroyAccelerationStructureKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CMD_BUILD_ACCELERATION_STRUCTURES_KHR = linker.downcallHandle(
                    vulkanLookup.find("vkCmdBuildAccelerationStructuresKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CMD_TRACE_RAYS_KHR = linker.downcallHandle(
                    vulkanLookup.find("vkCmdTraceRaysKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );

            VK_CREATE_RAY_TRACING_PIPELINES_KHR = linker.downcallHandle(
                    vulkanLookup.find("vkCreateRayTracingPipelinesKHR").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            VK_CMD_COPY_ACCELERATION_STRUCTURE_KHR = linker.downcallHandle(
                    vulkanLookup.find("vkCmdCopyAccelerationStructureKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            VK_CMD_DRAW_MESH_TASKS_EXT = linker.downcallHandle(
                    vulkanLookup.find("vkCmdDrawMeshTasksEXT").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );

            ffmLoaded = true;
            LOGGER.info("[VulkanFFM] ✓ FFM Vulkan 方法句柄加载成功");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[VulkanFFM] FFM Vulkan 方法句柄加载失败: " + t.getMessage(), t);
            ffmLoaded = false;
        }
    }
}
