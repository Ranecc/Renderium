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

            ffmLoaded = true;
            LOGGER.info("[VulkanFFM] ✓ FFM Vulkan 方法句柄加载成功");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[VulkanFFM] FFM Vulkan 方法句柄加载失败: " + t.getMessage(), t);
            ffmLoaded = false;
        }
    }
}
