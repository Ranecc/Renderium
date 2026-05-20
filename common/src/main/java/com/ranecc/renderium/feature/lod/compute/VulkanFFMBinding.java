package com.ranecc.renderium.feature.lod.compute;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.VulkanAPIRegistry;

/**
 * Vulkan FFM 方法句柄加载器 — 所有 Vulkan API 函数的单一绑定入口。
 * <p>
 * 职责：
 * <ol>
 *   <li>启动时通过 Java Panama FFM 加载 vulkan-1.dll / libvulkan.so</li>
 *   <li>为所有 Vulkan API 函数创建 {@link MethodHandle} 并缓存</li>
 *   <li>全部注册到 {@link VulkanAPIRegistry}（调用方只需调用 {@code VulkanAPIRegistry.invoke("vkXxx", args)}）</li>
 * </ol>
 *
 * <h3>使用方式（新代码禁止直接调用 getter）</h3>
 * <pre>
 * // ✅ 正确：通过 VulkanAPIRegistry 统一调用
 * int rc = (int) VulkanAPIRegistry.invoke("vkCmdDispatch", cmdBuf, x, y, z);
 *
 * // ❌ 已弃用：直接通过 VulkanFFMBinding.getter 调用
 * // (保留 getter 仅供给旧代码过渡期使用，新代码不得使用)
 * </pre>
 *
 * <h3>启动安全</h3>
 * 扩展函数（如 raytracing、mesh shader）使用 {@code .orElse(null)} 模式，
 * 即使当前 GPU/驱动不支持，也不会阻止核心函数的加载。
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

    /** vkGetPhysicalDeviceMemoryProperties: 查询物理设备内存属性 */
    private static volatile MethodHandle VK_GET_PHYSICAL_DEVICE_MEMORY_PROPERTIES;

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

    // ============ Phase 7: 其他命令 ============

    /** vkCmdFillBuffer: 用固定值填充缓冲区 */
    private static volatile MethodHandle VK_CMD_FILL_BUFFER;

    /** vkCmdPushConstants: 推送常量到 Shader */
    private static volatile MethodHandle VK_CMD_PUSH_CONSTANTS;

    // ==================== 加载状态 ====================

    /** FFM 方法是否已加载 */
    private static volatile boolean ffmLoaded = false;

    // ==================== 构造函数（私有）====================

    private VulkanFFMBinding() {}

    // ==================== 延迟加载 ====================
    // P3: 移除 static {} 同步加载，改为首次 getter 调用时按需触发。
    // 避免类加载时加载 vulkan-1.dll 阻塞 2-5 秒。

    private static final Object loadLock = new Object();

    private static void ensureLoaded() {
        if (ffmLoaded) return;
        synchronized (loadLock) {
            if (ffmLoaded) return;
            loadFFMMethodHandles();
        }
    }

    // ==================== 公共 getter（首次调用触发延迟加载）====================

    public static MethodHandle getVkCreateShaderModule() { ensureLoaded(); return VK_CREATE_SHADER_MODULE; }
    public static MethodHandle getVkCreateComputePipelines() { ensureLoaded(); return VK_CREATE_COMPUTE_PIPELINES; }
    public static MethodHandle getVkDestroyShaderModule() { ensureLoaded(); return VK_DESTROY_SHADER_MODULE; }
    public static MethodHandle getVkDestroyPipeline() { ensureLoaded(); return VK_DESTROY_PIPELINE; }
    public static MethodHandle getVkDestroyPipelineLayout() { ensureLoaded(); return VK_DESTROY_PIPELINE_LAYOUT; }
    public static MethodHandle getVkDestroyDescriptorSetLayout() { ensureLoaded(); return VK_DESTROY_DESCRIPTOR_SET_LAYOUT; }
    public static MethodHandle getVkAllocateCommandBuffers() { ensureLoaded(); return VK_ALLOCATE_COMMAND_BUFFERS; }
    public static MethodHandle getVkBeginCommandBuffer() { ensureLoaded(); return VK_BEGIN_COMMAND_BUFFER; }
    public static MethodHandle getVkEndCommandBuffer() { ensureLoaded(); return VK_END_COMMAND_BUFFER; }
    public static MethodHandle getVkCmdBindPipeline() { ensureLoaded(); return VK_CMD_BIND_PIPELINE; }
    public static MethodHandle getVkCmdBindDescriptorSets() { ensureLoaded(); return VK_CMD_BIND_DESCRIPTOR_SETS; }
    public static MethodHandle getVkCmdDispatch() { ensureLoaded(); return VK_CMD_DISPATCH; }
    public static MethodHandle getVkCmdPipelineBarrier() { ensureLoaded(); return VK_CMD_PIPELINE_BARRIER; }
    public static MethodHandle getVkQueueSubmit() { ensureLoaded(); return VK_QUEUE_SUBMIT; }
    public static MethodHandle getVkWaitForFences() { ensureLoaded(); return VK_WAIT_FOR_FENCES; }
    public static MethodHandle getVkResetFences() { ensureLoaded(); return VK_RESET_FENCES; }
    public static MethodHandle getVkCreateFence() { ensureLoaded(); return VK_CREATE_FENCE; }
    public static MethodHandle getVkDestroyFence() { ensureLoaded(); return VK_DESTROY_FENCE; }
    public static MethodHandle getVkCreateCommandPool() { ensureLoaded(); return VK_CREATE_COMMAND_POOL; }
    public static MethodHandle getVkDestroyCommandPool() { ensureLoaded(); return VK_DESTROY_COMMAND_POOL; }
    public static MethodHandle getVkCreatePipelineLayout() { ensureLoaded(); return VK_CREATE_PIPELINE_LAYOUT; }
    public static MethodHandle getVkCreateDescriptorSetLayout() { ensureLoaded(); return VK_CREATE_DESCRIPTOR_SET_LAYOUT; }

    public static MethodHandle getVkCreateGraphicsPipelines() { ensureLoaded(); return VK_CREATE_GRAPHICS_PIPELINES; }
    public static MethodHandle getVkCmdDraw() { ensureLoaded(); return VK_CMD_DRAW; }
    public static MethodHandle getVkCreateRenderPass() { ensureLoaded(); return VK_CREATE_RENDER_PASS; }
    public static MethodHandle getVkDestroyRenderPass() { ensureLoaded(); return VK_DESTROY_RENDER_PASS; }
    public static MethodHandle getVkCmdBeginRenderPass() { ensureLoaded(); return VK_CMD_BEGIN_RENDER_PASS; }
    public static MethodHandle getVkCmdEndRenderPass() { ensureLoaded(); return VK_CMD_END_RENDER_PASS; }
    public static MethodHandle getVkCreateImage() { ensureLoaded(); return VK_CREATE_IMAGE; }
    public static MethodHandle getVkDestroyImage() { ensureLoaded(); return VK_DESTROY_IMAGE; }
    public static MethodHandle getVkCreateImageView() { ensureLoaded(); return VK_CREATE_IMAGE_VIEW; }
    public static MethodHandle getVkDestroyImageView() { ensureLoaded(); return VK_DESTROY_IMAGE_VIEW; }
    public static MethodHandle getVkCreateSampler() { ensureLoaded(); return VK_CREATE_SAMPLER; }
    public static MethodHandle getVkDestroySampler() { ensureLoaded(); return VK_DESTROY_SAMPLER; }
    public static MethodHandle getVkAllocateMemory() { ensureLoaded(); return VK_ALLOCATE_MEMORY; }
    public static MethodHandle getVkFreeMemory() { ensureLoaded(); return VK_FREE_MEMORY; }
    public static MethodHandle getVkBindImageMemory() { ensureLoaded(); return VK_BIND_IMAGE_MEMORY; }
    public static MethodHandle getVkGetImageMemoryRequirements() { ensureLoaded(); return VK_GET_IMAGE_MEMORY_REQUIREMENTS; }
    public static MethodHandle getVkCreateFramebuffer() { ensureLoaded(); return VK_CREATE_FRAMEBUFFER; }
    public static MethodHandle getVkDestroyFramebuffer() { ensureLoaded(); return VK_DESTROY_FRAMEBUFFER; }

    public static MethodHandle getVkCreateDescriptorPool() { ensureLoaded(); return VK_CREATE_DESCRIPTOR_POOL; }
    public static MethodHandle getVkDestroyDescriptorPool() { ensureLoaded(); return VK_DESTROY_DESCRIPTOR_POOL; }
    public static MethodHandle getVkAllocateDescriptorSets() { ensureLoaded(); return VK_ALLOCATE_DESCRIPTOR_SETS; }
    public static MethodHandle getVkUpdateDescriptorSets() { ensureLoaded(); return VK_UPDATE_DESCRIPTOR_SETS; }
    public static MethodHandle getVkFreeDescriptorSets() { ensureLoaded(); return VK_FREE_DESCRIPTOR_SETS; }
    public static MethodHandle getVkMapMemory() { ensureLoaded(); return VK_MAP_MEMORY; }
    public static MethodHandle getVkUnmapMemory() { ensureLoaded(); return VK_UNMAP_MEMORY; }
    public static MethodHandle getVkInvalidateMappedMemoryRanges() { ensureLoaded(); return VK_INVALIDATE_MAPPED_MEMORY_RANGES; }
    public static MethodHandle getVkFlushMappedMemoryRanges() { ensureLoaded(); return VK_FLUSH_MAPPED_MEMORY_RANGES; }
    public static MethodHandle getVkCmdCopyBuffer() { ensureLoaded(); return VK_CMD_COPY_BUFFER; }
    public static MethodHandle getVkCmdBindVertexBuffers() { ensureLoaded(); return VK_CMD_BIND_VERTEX_BUFFERS; }
    public static MethodHandle getVkCmdBindIndexBuffer() { ensureLoaded(); return VK_CMD_BIND_INDEX_BUFFER; }
    public static MethodHandle getVkCmdDrawIndexed() { ensureLoaded(); return VK_CMD_DRAW_INDEXED; }
    public static MethodHandle getVkCreateBuffer() { ensureLoaded(); return VK_CREATE_BUFFER; }
    public static MethodHandle getVkDestroyBuffer() { ensureLoaded(); return VK_DESTROY_BUFFER; }
    public static MethodHandle getVkGetBufferMemoryRequirements() { ensureLoaded(); return VK_GET_BUFFER_MEMORY_REQUIREMENTS; }
    public static MethodHandle getVkBindBufferMemory() { ensureLoaded(); return VK_BIND_BUFFER_MEMORY; }
    public static MethodHandle getVkFreeCommandBuffers() { ensureLoaded(); return VK_FREE_COMMAND_BUFFERS; }
    public static MethodHandle getVkCreateSemaphore() { ensureLoaded(); return VK_CREATE_SEMAPHORE; }
    public static MethodHandle getVkDestroySemaphore() { ensureLoaded(); return VK_DESTROY_SEMAPHORE; }
    public static MethodHandle getVkWaitSemaphores() { ensureLoaded(); return VK_WAIT_SEMAPHORES; }
    public static MethodHandle getVkSignalSemaphore() { ensureLoaded(); return VK_SIGNAL_SEMAPHORE; }
    public static MethodHandle getVkGetSemaphoreCounterValue() { ensureLoaded(); return VK_GET_SEMAPHORE_COUNTER_VALUE; }
    public static MethodHandle getVkGetPhysicalDeviceMemoryProperties() { ensureLoaded(); return VK_GET_PHYSICAL_DEVICE_MEMORY_PROPERTIES; }
    public static MethodHandle getVkCreateAccelerationStructureKHR() { ensureLoaded(); return VK_CREATE_ACCELERATION_STRUCTURE_KHR; }
    public static MethodHandle getVkDestroyAccelerationStructureKHR() { ensureLoaded(); return VK_DESTROY_ACCELERATION_STRUCTURE_KHR; }
    public static MethodHandle getVkCmdBuildAccelerationStructuresKHR() { ensureLoaded(); return VK_CMD_BUILD_ACCELERATION_STRUCTURES_KHR; }
    public static MethodHandle getVkCmdTraceRaysKHR() { ensureLoaded(); return VK_CMD_TRACE_RAYS_KHR; }
    public static MethodHandle getVkCreateRayTracingPipelinesKHR() { ensureLoaded(); return VK_CREATE_RAY_TRACING_PIPELINES_KHR; }
    public static MethodHandle getVkCmdCopyAccelerationStructureKHR() { ensureLoaded(); return VK_CMD_COPY_ACCELERATION_STRUCTURE_KHR; }
    public static MethodHandle getVkCmdDrawMeshTasksEXT() { ensureLoaded(); return VK_CMD_DRAW_MESH_TASKS_EXT; }

    /** FFM 方法句柄是否已加载成功 */
    public static boolean isFfmLoaded() { ensureLoaded(); return ffmLoaded; }

    /**
     * 通过 Panama FFM 加载 Vulkan API 方法句柄并注册到 {@link VulkanAPIRegistry}。
     * <p>
     * 使用 {@link Linker#nativeLinker()} 和 {@link SymbolLookup#libraryLookup}
     * 加载 vulkan-1.dll (Windows) / libvulkan.so (Linux) 中的 Vulkan 函数指针。
     * 加载完成后调用 {@link #registerAllToRegistry()} 将全部句柄注册到统一注册中心。
     *
     * <h3>启动安全策略</h3>
     * <ul>
     *   <li>核心 Vulkan 1.0 函数使用 {@code .orElseThrow()} — 不存在的 GPU 驱动无法运行</li>
     *   <li>Ray Tracing / Mesh Shader 扩展使用 {@code .orElse(null)} — 兼容不支持的 GPU</li>
     *   <li>Timeline Semaphore 使用 {@code .orElse(null)} — 兼容 Vulkan 1.1 设备</li>
     *   <li>扩展函数的 getter 返回 null，调用方通过 {@code VulkanAPIRegistry.isAvailable()} 检查</li>
     * </ul>
     */
    private static void loadFFMMethodHandles() {
        try {
            Linker linker = Linker.nativeLinker();
            String osName = System.getProperty("os.name").toLowerCase();
            String vulkanLibName = osName.contains("win") ? "vulkan-1" : "vulkan";
            var vulkanLookup = SymbolLookup.libraryLookup(vulkanLibName, Arena.ofAuto());

            // ===== 核心 Vulkan 1.0 函数（必须存在，失败则阻止加载） =====

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
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
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

            // ===== Timeline Semaphore（Vulkan 1.2 核心，1.1 需要扩展，可选加载） =====
            var tsLookup = vulkanLookup.find("vkWaitSemaphores");
            VK_WAIT_SEMAPHORES = tsLookup.isPresent()
                ? linker.downcallHandle(tsLookup.get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG))
                : null;

            VK_SIGNAL_SEMAPHORE = tsLookup.isPresent()
                ? linker.downcallHandle(vulkanLookup.find("vkSignalSemaphore").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG))
                : null;

            VK_GET_SEMAPHORE_COUNTER_VALUE = tsLookup.isPresent()
                ? linker.downcallHandle(vulkanLookup.find("vkGetSemaphoreCounterValue").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG))
                : null;

            VK_GET_PHYSICAL_DEVICE_MEMORY_PROPERTIES = linker.downcallHandle(
                    vulkanLookup.find("vkGetPhysicalDeviceMemoryProperties").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG)
            );

            // ===== Ray Tracing 扩展（非必需，GPU 不支持时不阻止加载） =====
            var rtLookup = vulkanLookup.find("vkCreateAccelerationStructureKHR");
            VK_CREATE_ACCELERATION_STRUCTURE_KHR = rtLookup.isPresent()
                ? linker.downcallHandle(rtLookup.get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG))
                : null;

            VK_DESTROY_ACCELERATION_STRUCTURE_KHR = rtLookup.isPresent()
                ? linker.downcallHandle(vulkanLookup.find("vkDestroyAccelerationStructureKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG))
                : null;

            VK_CMD_BUILD_ACCELERATION_STRUCTURES_KHR = rtLookup.isPresent()
                ? linker.downcallHandle(vulkanLookup.find("vkCmdBuildAccelerationStructuresKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG))
                : null;

            VK_CMD_TRACE_RAYS_KHR = rtLookup.isPresent()
                ? linker.downcallHandle(vulkanLookup.find("vkCmdTraceRaysKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT))
                : null;

            VK_CREATE_RAY_TRACING_PIPELINES_KHR = rtLookup.isPresent()
                ? linker.downcallHandle(vulkanLookup.find("vkCreateRayTracingPipelinesKHR").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG))
                : null;

            VK_CMD_COPY_ACCELERATION_STRUCTURE_KHR = rtLookup.isPresent()
                ? linker.downcallHandle(vulkanLookup.find("vkCmdCopyAccelerationStructureKHR").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG))
                : null;

            // ===== Mesh Shader 扩展（非必需） =====
            var msLookup = vulkanLookup.find("vkCmdDrawMeshTasksEXT");
            VK_CMD_DRAW_MESH_TASKS_EXT = msLookup.isPresent()
                ? linker.downcallHandle(msLookup.get(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
                : null;

            // vkCmdFillBuffer(VkCommandBuffer, VkBuffer, VkDeviceSize, VkDeviceSize, uint32_t)
            // 签名: void(cmdBuffer LONG, dstBuffer LONG, dstOffset LONG, size LONG, data INT)
            VK_CMD_FILL_BUFFER = linker.downcallHandle(
                    vulkanLookup.find("vkCmdFillBuffer").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT)
            );

            // vkCmdPushConstants(VkCommandBuffer, VkPipelineLayout, VkShaderStageFlags, uint32_t, uint32_t, const void*)
            // 签名: void(cmdBuffer LONG, layout LONG, stageFlags INT, offset INT, size INT, pValues LONG)
            VK_CMD_PUSH_CONSTANTS = linker.downcallHandle(
                    vulkanLookup.find("vkCmdPushConstants").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
            );

            ffmLoaded = true;
            registerAllToRegistry();
            LOGGER.info("[VulkanFFM] ✓ FFM Vulkan 方法句柄加载成功 (" + VulkanAPIRegistry.getRegisteredCount() + " APIs)");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[VulkanFFM] FFM Vulkan 方法句柄加载失败: " + t.getMessage(), t);
            ffmLoaded = false;
        }
    }

    /**
     * 将所有已加载的 Vulkan 方法句柄注册到 {@link VulkanAPIRegistry}。
     * <p>由 {@link #loadFFMMethodHandles()} 在加载完成后调用。
     */
    private static void registerAllToRegistry() {
        var all = java.util.Map.<String, java.lang.invoke.MethodHandle>ofEntries(
            java.util.Map.entry("vkCreateShaderModule", VK_CREATE_SHADER_MODULE),
            java.util.Map.entry("vkCreateComputePipelines", VK_CREATE_COMPUTE_PIPELINES),
            java.util.Map.entry("vkDestroyShaderModule", VK_DESTROY_SHADER_MODULE),
            java.util.Map.entry("vkDestroyPipeline", VK_DESTROY_PIPELINE),
            java.util.Map.entry("vkDestroyPipelineLayout", VK_DESTROY_PIPELINE_LAYOUT),
            java.util.Map.entry("vkDestroyDescriptorSetLayout", VK_DESTROY_DESCRIPTOR_SET_LAYOUT),
            java.util.Map.entry("vkAllocateCommandBuffers", VK_ALLOCATE_COMMAND_BUFFERS),
            java.util.Map.entry("vkBeginCommandBuffer", VK_BEGIN_COMMAND_BUFFER),
            java.util.Map.entry("vkEndCommandBuffer", VK_END_COMMAND_BUFFER),
            java.util.Map.entry("vkCmdBindPipeline", VK_CMD_BIND_PIPELINE),
            java.util.Map.entry("vkCmdBindDescriptorSets", VK_CMD_BIND_DESCRIPTOR_SETS),
            java.util.Map.entry("vkCmdDispatch", VK_CMD_DISPATCH),
            java.util.Map.entry("vkCmdPipelineBarrier", VK_CMD_PIPELINE_BARRIER),
            java.util.Map.entry("vkQueueSubmit", VK_QUEUE_SUBMIT),
            java.util.Map.entry("vkWaitForFences", VK_WAIT_FOR_FENCES),
            java.util.Map.entry("vkResetFences", VK_RESET_FENCES),
            java.util.Map.entry("vkCreateFence", VK_CREATE_FENCE),
            java.util.Map.entry("vkDestroyFence", VK_DESTROY_FENCE),
            java.util.Map.entry("vkCreateCommandPool", VK_CREATE_COMMAND_POOL),
            java.util.Map.entry("vkDestroyCommandPool", VK_DESTROY_COMMAND_POOL),
            java.util.Map.entry("vkCreatePipelineLayout", VK_CREATE_PIPELINE_LAYOUT),
            java.util.Map.entry("vkCreateDescriptorSetLayout", VK_CREATE_DESCRIPTOR_SET_LAYOUT),
            java.util.Map.entry("vkCreateGraphicsPipelines", VK_CREATE_GRAPHICS_PIPELINES),
            java.util.Map.entry("vkCmdDraw", VK_CMD_DRAW),
            java.util.Map.entry("vkCreateRenderPass", VK_CREATE_RENDER_PASS),
            java.util.Map.entry("vkDestroyRenderPass", VK_DESTROY_RENDER_PASS),
            java.util.Map.entry("vkCmdBeginRenderPass", VK_CMD_BEGIN_RENDER_PASS),
            java.util.Map.entry("vkCmdEndRenderPass", VK_CMD_END_RENDER_PASS),
            java.util.Map.entry("vkCreateImage", VK_CREATE_IMAGE),
            java.util.Map.entry("vkDestroyImage", VK_DESTROY_IMAGE),
            java.util.Map.entry("vkCreateImageView", VK_CREATE_IMAGE_VIEW),
            java.util.Map.entry("vkDestroyImageView", VK_DESTROY_IMAGE_VIEW),
            java.util.Map.entry("vkCreateSampler", VK_CREATE_SAMPLER),
            java.util.Map.entry("vkDestroySampler", VK_DESTROY_SAMPLER),
            java.util.Map.entry("vkAllocateMemory", VK_ALLOCATE_MEMORY),
            java.util.Map.entry("vkFreeMemory", VK_FREE_MEMORY),
            java.util.Map.entry("vkBindImageMemory", VK_BIND_IMAGE_MEMORY),
            java.util.Map.entry("vkGetImageMemoryRequirements", VK_GET_IMAGE_MEMORY_REQUIREMENTS),
            java.util.Map.entry("vkCreateFramebuffer", VK_CREATE_FRAMEBUFFER),
            java.util.Map.entry("vkDestroyFramebuffer", VK_DESTROY_FRAMEBUFFER),
            java.util.Map.entry("vkCreateDescriptorPool", VK_CREATE_DESCRIPTOR_POOL),
            java.util.Map.entry("vkDestroyDescriptorPool", VK_DESTROY_DESCRIPTOR_POOL),
            java.util.Map.entry("vkAllocateDescriptorSets", VK_ALLOCATE_DESCRIPTOR_SETS),
            java.util.Map.entry("vkUpdateDescriptorSets", VK_UPDATE_DESCRIPTOR_SETS),
            java.util.Map.entry("vkFreeDescriptorSets", VK_FREE_DESCRIPTOR_SETS),
            java.util.Map.entry("vkMapMemory", VK_MAP_MEMORY),
            java.util.Map.entry("vkUnmapMemory", VK_UNMAP_MEMORY),
            java.util.Map.entry("vkInvalidateMappedMemoryRanges", VK_INVALIDATE_MAPPED_MEMORY_RANGES),
            java.util.Map.entry("vkFlushMappedMemoryRanges", VK_FLUSH_MAPPED_MEMORY_RANGES),
            java.util.Map.entry("vkCmdCopyBuffer", VK_CMD_COPY_BUFFER),
            java.util.Map.entry("vkCmdBindVertexBuffers", VK_CMD_BIND_VERTEX_BUFFERS),
            java.util.Map.entry("vkCmdBindIndexBuffer", VK_CMD_BIND_INDEX_BUFFER),
            java.util.Map.entry("vkCmdDrawIndexed", VK_CMD_DRAW_INDEXED),
            java.util.Map.entry("vkCreateBuffer", VK_CREATE_BUFFER),
            java.util.Map.entry("vkDestroyBuffer", VK_DESTROY_BUFFER),
            java.util.Map.entry("vkGetBufferMemoryRequirements", VK_GET_BUFFER_MEMORY_REQUIREMENTS),
            java.util.Map.entry("vkBindBufferMemory", VK_BIND_BUFFER_MEMORY),
            java.util.Map.entry("vkFreeCommandBuffers", VK_FREE_COMMAND_BUFFERS),
            java.util.Map.entry("vkCreateSemaphore", VK_CREATE_SEMAPHORE),
            java.util.Map.entry("vkDestroySemaphore", VK_DESTROY_SEMAPHORE),
            java.util.Map.entry("vkWaitSemaphores", VK_WAIT_SEMAPHORES),
            java.util.Map.entry("vkSignalSemaphore", VK_SIGNAL_SEMAPHORE),
            java.util.Map.entry("vkGetSemaphoreCounterValue", VK_GET_SEMAPHORE_COUNTER_VALUE),
            java.util.Map.entry("vkGetPhysicalDeviceMemoryProperties", VK_GET_PHYSICAL_DEVICE_MEMORY_PROPERTIES),
            java.util.Map.entry("vkCreateAccelerationStructureKHR", VK_CREATE_ACCELERATION_STRUCTURE_KHR),
            java.util.Map.entry("vkDestroyAccelerationStructureKHR", VK_DESTROY_ACCELERATION_STRUCTURE_KHR),
            java.util.Map.entry("vkCmdBuildAccelerationStructuresKHR", VK_CMD_BUILD_ACCELERATION_STRUCTURES_KHR),
            java.util.Map.entry("vkCmdTraceRaysKHR", VK_CMD_TRACE_RAYS_KHR),
            java.util.Map.entry("vkCreateRayTracingPipelinesKHR", VK_CREATE_RAY_TRACING_PIPELINES_KHR),
            java.util.Map.entry("vkCmdCopyAccelerationStructureKHR", VK_CMD_COPY_ACCELERATION_STRUCTURE_KHR),
            java.util.Map.entry("vkCmdDrawMeshTasksEXT", VK_CMD_DRAW_MESH_TASKS_EXT),
            java.util.Map.entry("vkCmdFillBuffer", VK_CMD_FILL_BUFFER),
            java.util.Map.entry("vkCmdPushConstants", VK_CMD_PUSH_CONSTANTS)
        );
        VulkanAPIRegistry.registerAll(all);
    }
}
