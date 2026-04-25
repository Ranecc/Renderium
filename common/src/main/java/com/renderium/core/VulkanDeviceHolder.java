package com.renderium.core;

import com.mojang.blaze3d.vulkan.VulkanDevice;

/**
 * Vulkan 设备句柄持有者（全局单例）
 * <p>
 * 持有 Minecraft 26.2-snapshot-3 官方 {@link VulkanDevice} 的底层原生句柄，
 * 供所有 Renderium FrameGraph Pass 和 Streamline SDK 使用。
 * </p>
 *
 * <h3>初始化时机：</h3>
 * <pre>
 * 在 MixinRenderSystem.initRenderer() @At("TAIL") 处通过反射提取 VulkanDevice 句柄后调用 initialize()
 * </pre>
 *
 * <h3>持有的句柄：</h3>
 * <ul>
 *   <li>{@code vulkanDevice} - 26.2 官方 VulkanDevice 实例（用于调用高级 API）</li>
 *   <li>{@code vkDeviceHandle} - VkDevice 原生句柄（LWJGL long 地址，用于 Streamline SDK）</li>
 *   <li>{@code vmaAllocator} - VMA (Vulkan Memory Allocator) 分配器句柄</li>
 *   <li>{@code graphicsQueue} - 图形队列原生句柄</li>
 *   <li>{@code computeQueue} - 计算队列原生句柄（用于 Compute Shader Pass）</li>
 * </ul>
 *
 * <h3>线程安全：</h3>
 * <ul>
 *   <li>Singleton 使用饿汉式初始化，天然线程安全</li>
 *   <li>所有字段使用 volatile 保证跨线程可见性</li>
 *   <li>initialize() 使用 synchronized 保证原子性</li>
 * </ul>
 *
 * <h3>调用示例：</h3>
 * <pre>
 * // 获取单例
 * VulkanDeviceHolder holder = VulkanDeviceHolder.getInstance();
 *
 * // 初始化（仅在 MixinRenderSystem 中调用一次）
 * holder.initialize(vkDevice, vkDevice.address(), vkDevice.vma(),
 *                   vkDevice.graphicsQueue().vkQueue().address(),
 *                   vkDevice.computeQueue().vkQueue().address());
 *
 * // 在 Pass 中使用
 * long vkDevice = holder.getVkDeviceHandle();
 * long vma = holder.getVmaAllocator();
 * long gfxQueue = holder.getGraphicsQueue();
 * </pre>
 *
 * @see com.mojang.blaze3d.vulkan.VulkanDevice
 * @see com.renderium.mixin.MixinRenderSystem
 * @since 5.2.0
 */
public final class VulkanDeviceHolder {

    /** 单例实例（饿汉式，线程安全） */
    private static final VulkanDeviceHolder INSTANCE = new VulkanDeviceHolder();

    // ==================== 原生句柄（volatile 保证可见性）====================

    /**
     * 26.2 官方 VulkanDevice 实例引用
     * 可用于调用 VulkanDevice 的高级 API（如 createCommandEncoder()）
     */
    private volatile VulkanDevice vulkanDevice;

    /**
     * VkDevice 原生句柄（LWJGL long 地址）
     * 用于 Streamline SDK 初始化和底层 Vulkan API 调用
     */
    private volatile long vkDeviceHandle;

    /**
     * VMA (Vulkan Memory Allocator) 分配器句柄
     * 用于 GPU 显存分配和管理
     */
    private volatile long vmaAllocator;

    /**
     * 图形队列原生句柄
     * 用于提交图形渲染命令
     */
    private volatile long graphicsQueue;

    /**
     * 计算队列原生句柄
     * 用于提交 Compute Shader 命令（LOD Culling 等）
     */
    private volatile long computeQueue;

    /**
     * 初始化状态标志
     * true 表示已成功初始化且未关闭
     */
    private volatile boolean initialized;

    // ==================== 构造函数（私有）====================

    private VulkanDeviceHolder() {
        // 私有构造函数防止外部实例化
    }

    // ==================== 公共 API ====================

    /**
     * 获取全局单例实例
     *
     * 【返回值】
     * @return VulkanDeviceHolder - 全局唯一的实例（永远不会为 null）
     */
    public static VulkanDeviceHolder getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化句柄持有者
     * <p>
     * 从 26.2 官方 VulkanDevice 提取所有必要的原生句柄并缓存。
     * 此方法应在 {@code MixinRenderSystem.initRenderer()} 的 @At("TAIL") 处调用。
     * </p>
     *
     * 【方法参数】
     * @param vulkanDevice      VulkanDevice - 26.2 官方 Vulkan 设备实例（不能为 null）
     * @param vkDeviceHandle     long - VkDevice 原生句柄（LWJGL address，通常为 vulkanDevice.vkDevice().address()）
     * @param vmaAllocator       long - VMA 分配器句柄（通常为 vulkanDevice.vma()）
     * @param graphicsQueue      long - 图形队列原生句柄（通常为 vulkanDevice.graphicsQueue().vkQueue().address()）
     * @param computeQueue       long - 计算队列原生句柄（通常为 vulkanDevice.computeQueue().vkQueue().address()）
     *
     * 【返回值】void
     *
     * 【异常】
     * @throws IllegalArgumentException 如果 vulkanDevice 为 null
     *
     * 【实现要点】
     * 1. 参数校验（vulkanDevice != null）
     * 2. synchronized 块保证原子性赋值
     * 3. 所有字段一次性写入后设置 initialized = true
     * 4. 避免部分初始化状态
     *
     * 【性能特征】
     * - 仅调用一次（游戏启动时）
     * - synchronized 开销可忽略
     * - 后续读取无锁（volatile 读）
     *
     * @throws IllegalArgumentException 当 vulkanDevice 为 null 时抛出
     */
    public synchronized void initialize(
            VulkanDevice vulkanDevice,
            long vkDeviceHandle,
            long vmaAllocator,
            long graphicsQueue,
            long computeQueue) {

        // 参数校验
        if (vulkanDevice == null) {
            throw new IllegalArgumentException("vulkanDevice 不能为 null");
        }

        // 原子性赋值所有字段
        this.vulkanDevice = vulkanDevice;
        this.vkDeviceHandle = vkDeviceHandle;
        this.vmaAllocator = vmaAllocator;
        this.graphicsQueue = graphicsQueue;
        this.computeQueue = computeQueue;
        this.initialized = true;
    }

    /**
     * 重置/关闭句柄持有者
     * <p>
     * 清除所有缓存的句柄引用，将状态重置为未初始化。
     * 通常在游戏关闭或渲染设备重建时调用。
     * </p>
     *
     * 【返回值】void
     *
     * 【注意事项】
     * - 不释放任何 Vulkan 资源（由 Minecraft/Blaze3D 管理）
     * - 仅清除本地引用
     * - 调用后 isInitialized() 返回 false
     */
    public synchronized void shutdown() {
        this.vulkanDevice = null;
        this.vkDeviceHandle = 0L;
        this.vmaAllocator = 0L;
        this.graphicsQueue = 0L;
        this.computeQueue = 0L;
        this.initialized = false;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取官方 VulkanDevice 实例
     *
     * 【返回值】
     * @return VulkanDevice - 26.2 官方 Vulkan 设备实例，如果未初始化返回 null
     *
     * 【用途】
     * - 调用 createCommandEncoder() 创建命令编码器
     * - 访问 VulkanDevice 的高级 API
     */
    public VulkanDevice getVulkanDevice() {
        return vulkanDevice;
    }

    /**
     * 获取 VkDevice 原生句柄
     *
     * 【返回值】
     * @return long - VkDevice 原生地址（LWJGL），如果未初始化返回 0L
     *
     * 【用途】
     * - Streamline SDK 初始化（slInit 需要 VkDevice 句柄）
     * - 底层 Vulkan API 调用（vkCreateBuffer 等）
     */
    public long getVkDeviceHandle() {
        return vkDeviceHandle;
    }

    /**
     * 获取 VMA 分配器句柄
     *
     * 【返回值】
     * @return long - VMA allocator 句柄，如果未初始化返回 0L
     *
     * 【用途】
     * - GPU 显存分配（vmaAllocateMemory 等）
     * - 纹理/缓冲区内存管理
     */
    public long getVmaAllocator() {
        return vmaAllocator;
    }

    /**
     * 获取图形队列原生句柄
     *
     * 【返回值】
     * @return long - VkQueue 原生地址，如果未初始化返回 0L
     *
     * 【用途】
     * - 提交图形渲染命令（vkQueueSubmit）
     * - Present 操作（vkQueuePresentKHR）
     */
    public long getGraphicsQueue() {
        return graphicsQueue;
    }

    /**
     * 获取计算队列原生句柄
     *
     * 【返回值】
     * @return long - VkQueue 原生地址，如果未初始化返回 0L
     *
     * 【用途】
     * - 提交 Compute Shader 命令（LOD Culling、Hi-Z 构建等）
     * - 异步计算操作
     */
    public long getComputeQueue() {
        return computeQueue;
    }

    /**
     * 检查是否已成功初始化
     *
     * 【返回值】
     * @return boolean - true 表示已成功初始化且可用，false 表示尚未初始化或已关闭
     *
     * 【使用场景】
     * - 在 MixinFrameGraph 中判断是否应该注入 Pass
     * - 在各 Pass 执行前进行前置检查
     */
    public boolean isInitialized() {
        return initialized;
    }
}
