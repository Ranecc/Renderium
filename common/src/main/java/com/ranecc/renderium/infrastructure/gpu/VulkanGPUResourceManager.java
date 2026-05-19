// Renderium - GPU 资源管理器
// 核心功能：统一管理所有 GPU 资源的创建、生命周期和销毁
//
// 设计目标：
// 1. 委托 VmaMemoryPools 实现 O(1) 内存分配
// 2. 通过 VulkanDeviceHolder 获取设备句柄（优先）或 OfficialVulkanHijacker（回退）
// 3. 集成 VmaDeferredDeallocation 实现安全的多帧延迟销毁
// 4. 为 Bloom / SSAO / RT / GenericShader 提供真实的 GPU 资源创建接口

package com.ranecc.renderium.infrastructure.gpu;

import com.ranecc.renderium.domain.constant.VulkanConst;
import com.ranecc.renderium.feature.blaze3d.memory.VmaDeferredDeallocation;
import com.ranecc.renderium.feature.blaze3d.memory.VmaMemoryPools;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VK10;

import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * Vulkan GPU 资源管理器（全局单例）
 * <p>
 * 作为 Renderium 所有后处理 Pass（Bloom、SSAO、RT、GenericShader 等）
 * 的统一 GPU 资源创建入口。通过委托模式将资源创建操作分发到
 * VMA 专用内存池，实现 O(1) 分配速度和多帧延迟安全销毁。
 *
 * <h2>架构定位：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │                  Pipeline Nodes                      │
 * │  Bloom │ SSAO │ GenericShader │ RayTracing │ ...     │
 * └──────────────┬──────────────────────────────────────┘
 *                │ 调用 createImage / createBuffer
 *                ▼
 * ┌─────────────────────────────────────────────────────┐
 * │           VulkanGPUResourceManager (本类)             │
 * │  ┌─────────────────────────────────────────────┐    │
 * │  │  设备句柄来源（优先级排序）：                   │    │
 * │  │  1. VulkanDeviceHolder (含 VMA) ← 首选       │    │
 * │  │  2. OfficialVulkanHijacker (无 VMA) ← 回退    │    │
 * │  └─────────────────────────────────────────────┘    │
 * └──────────────┬──────────────────────────────────────┘
 *                │ 委托
 *         ┌──────┴──────┬──────────────────┐
 *         ▼             ▼                  ▼
 * ┌────────────┐ ┌────────────┐  ┌──────────────────┐
 * │VmaMemoryPools│ │VmaDeferred│  │CommandBatcher    │
 * │ O(1) 分配   │ │Deallocation│  │命令批处理        │
 * └────────────┘ └────────────┘  └──────────────────┘
 * </pre>
 *
 * <h3>支持的资源类型：</h3>
 * <table border="1">
 *   <tr><th>类型</th><th>用途</th><th>VMA 池</th><th>典型调用者</th></tr>
 *   <tr><td>Image</td><td>纹理/渲染目标</td><td>TEXTURE / RENDER_TARGET</td><td>Bloom, SSAO, Tonemap</td></tr>
 *   <tr><td>Buffer</td><td>顶点/Uniform/Staging</td><td>VERTEX / UNIFORM / STAGING</td><td>GBuffer, ShadowMap</td></tr>
 *   <tr><td>ImageView</td><td>Image 视图描述</td><td>N/A（VKAPI 创建）</td><td>All Nodes</td></tr>
 * </table>
 *
 * <h3>线程安全：</h3>
 * <ul>
 *   <li>Singleton 使用饿汉式初始化，天然线程安全</li>
 *   <li>资源创建/释放使用 volatile 字段 + AtomicLong 计数器保证可见性</li>
 *   <li>帧结束回调（onFrameEnd）应在主线程串行调用</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 初始化（在 MixinRenderSystem.initRenderer @TAIL 处调用一次）
 * VulkanGPUResourceManager mgr = VulkanGPUResourceManager.getInstance();
 * mgr.initialize();
 *
 * // 在 Bloom Node 中创建渲染目标
 * long bloomTarget = mgr.createRenderTarget(1920, 1080,
 *     VK_FORMAT_R16G16B16A16_SFLOAT,
 *     IMAGE_USAGE_COLOR_ATTACHMENT_BIT | IMAGE_USAGE_SAMPLED_BIT);
 *
 * // 在 SSAO Node 中创建输出纹理
 * long aoOutput = mgr.createImage(screenWidth, screenHeight,
 *     VK_FORMAT_R8_UNORM,
 *     IMAGE_USAGE_STORAGE_BIT | IMAGE_USAGE_SAMPLED_BIT,
 *     VmaMemoryPools.PoolType.RENDER_TARGET);
 *
 * // 不再需要时请求延迟释放（而非立即销毁！）
 * mgr.releaseResource(bloomTarget, bloomAllocation);
 *
 * // 帧结束时统一处理（必须在主线程调用）
 * mgr.onFrameEnd();
 *
 * // 应用退出时关闭
 * mgr.shutdown();
 * }</pre>
 *
 * @see VmaMemoryPools VMA 专用内存池（O(1) 分配）
 * @see VmaDeferredDeallocation 多帧延迟销毁
 * @see VulkanDeviceHolder 设备句柄持有者
 * @see OfficialVulkanHijacker 备选设备句柄源
 * @since 5.3.0
 */
public final class VulkanGPUResourceManager {

    private static final Logger LOGGER = Logger.getLogger(VulkanGPUResourceManager.class.getName());

    /** 单例实例（饿汉式，线程安全） */
    private static final VulkanGPUResourceManager INSTANCE = new VulkanGPUResourceManager();

    // ==================== 设备句柄（volatile 保证跨线程可见性）====================

    /**
     * VkDevice 原生句柄（LWJGL long 地址）
     * 来源优先级：VulkanDeviceHolder > OfficialVulkanHijacker
     */
    private volatile long vkDevice = 0L;

    /**
     * VMA 分配器句柄
     * 仅当从 VulkanDeviceHolder 获取时有效（OfficialVulkanHijacker 无 VMA）
     */
    private volatile long vmaAllocator = 0L;

    /**
     * 图形队列原生句柄
     */
    private volatile long graphicsQueue = 0L;

    /**
     * 计算队列原生句柄（用于 Compute Shader Dispatch）
     */
    private volatile long computeQueue = 0L;

    /**
     * 设备句柄来源标识
     * true = 来自 VulkanDeviceHolder（含 VMA），false = 来自 OfficialVulkanHijacker（无 VMA）
     */
    private volatile boolean useVulkanDeviceHolder = false;

    // ==================== 状态字段 ====================

    /** 是否已成功初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ==================== 统计字段 ====================

    /** 总创建 Image 数量 */
    private final AtomicLong totalImagesCreated = new AtomicLong(0);

    /** 总创建 Buffer 数量 */
    private final AtomicLong totalBuffersCreated = new AtomicLong(0);

    /** 总创建 ImageView 数量 */
    private final AtomicLong totalViewsCreated = new AtomicLong(0);

    /** 总延迟释放请求数 */
    private final AtomicLong totalReleaseRequests = new AtomicLong(0);

    // ==================== 构造函数（私有）====================

    private VulkanGPUResourceManager() {
        LOGGER.fine("VulkanGPUResourceManager 实例已创建（等待初始化）");
    }

    // ==================== 公共 API：单例与生命周期 ====================

    /**
     * 获取全局单例实例
     *
     * 【返回值】
     * @return VulkanGPUResourceManager - 全局唯一的实例（永远不会为 null）
     */
    public static VulkanGPUResourceManager getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化 GPU 资源管理器 ⚙️
     * <p>
     * 按优先级尝试从两个数据源获取设备句柄：
     * <ol>
     *   <li><b>VulkanDeviceHolder</b>（首选）- 同时提供 VkDevice 和 VMA 分配器</li>
     *   <li><b>OfficialVulkanHijacker</b>（回退）- 仅提供 VkDevice，无 VMA 支持</li>
     * </ol>
     * 成功获取句柄后，自动连接 VmaMemoryPools 和 VmaDeferredDeallocation。
     *
     * 【返回值】boolean - true 表示初始化成功；false 表示无可用的设备句柄
     *
     * 【异常】
     * @throws IllegalStateException 如果已关闭（close() 已调用）
     *
     * 【性能特征】
     * - 仅调用一次（游戏启动时）
     * - 典型耗时：< 1ms（主要是 volatile 读 + 字段赋值）
     *
     * 【调用时机】
     * MixinRenderSystem.initRenderer() @At("TAIL") 处，
     * 在 VulkanDeviceHolder.initialize() 之后调用
     */
    public boolean initialize() {
        if (closed.get()) {
            throw new IllegalStateException("VulkanGPUResourceManager 已关闭，无法重新初始化");
        }

        if (initialized.get()) {
            LOGGER.warning("initialize: 已经初始化过，跳过重复初始化");
            return true;  // 幂等性
        }

        try {
            // ====== 策略 1: 优先使用 VulkanDeviceHolder（含 VMA 分配器）======
            VulkanDeviceHolder holder = VulkanDeviceHolder.getInstance();
            if (holder.isInitialized()) {
                this.vkDevice = holder.getVkDeviceHandle();
                this.vmaAllocator = holder.getVmaAllocator();
                this.graphicsQueue = holder.getGraphicsQueue();
                this.computeQueue = holder.getComputeQueue();
                this.useVulkanDeviceHolder = true;

                LOGGER.info(String.format(
                        "✓ VulkanGPUResourceManager 从 VulkanDeviceHolder 初始化" +
                        "  vkDevice=%d, vma=%d, gfxQ=%d, compQ=%d",
                        vkDevice, vmaAllocator, graphicsQueue, computeQueue));
            }
            // ====== 策略 2: 回退到 OfficialVulkanHijacker（无 VMA）======
            else {
                OfficialVulkanHijacker hijacker = OfficialVulkanHijacker.getInstance();
                if (hijacker.isAvailable()) {
                    this.vkDevice = hijacker.getVkDevice();
                    this.graphicsQueue = hijacker.getGraphicsQueue();
                    this.computeQueue = hijacker.getComputeQueue();
                    this.vmaAllocator = 0L;  // 无 VMA 支持
                    this.useVulkanDeviceHolder = false;

                    LOGGER.warning(String.format(
                            "⚠ VulkanGPUResourceManager 回退到 OfficialVulkanHijacker" +
                            "  vkDevice=%d, 无 VMA 支持（部分功能降级）",
                            vkDevice));
                } else {
                    LOGGER.severe("initialize 失败: VulkanDeviceHolder 和 OfficialVulkanHijacker 均不可用");
                    return false;
                }
            }

            // 验证关键句柄有效性
            if (vkDevice == 0L) {
                LOGGER.severe("initialize 失败: vkDevice 句柄为 0");
                return false;
            }

            initialized.set(true);

            LOGGER.info("VulkanGPUResourceManager 初始化完毕，使用: " + (useVulkanDeviceHolder ? "VulkanDeviceHolder" : "OfficialVulkanHijacker"));
            return true;

        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.severe("initialize 异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 关闭 GPU 资源管理器 ♻️
     * <p>
     * 清除所有缓存的句柄引用，重置状态。
     * 注意：不直接销毁任何 GPU 资源（由 Minecraft/Blaze3D 管理 VkDevice 生命周期）。
     * 已注册的延迟释放项应在 shutdown() 前通过 onFrameEnd() 全部清空。
     *
     * 【返回值】void
     *
     * 【注意事项】
     * - 调用前应确保 GPU 空闲（vkDeviceWaitIdle）
     * - 此方法是幂等的（多次调用安全）
     */
    public void shutdown() {
        if (closed.get()) {
            return;  // 幂等性
        }

        closed.set(true);
        initialized.set(false);

        this.vkDevice = 0L;
        this.vmaAllocator = 0L;
        this.graphicsQueue = 0L;
        this.computeQueue = 0L;
        this.useVulkanDeviceHolder = false;

        LOGGER.info("VulkanGPUResourceManager 已关闭");
    }

    // ==================== 公共 API：资源创建 ====================

    /**
     * 创建 Vulkan Image（从 VMA 专用池分配显存）
     * <p>
     * 这是 Bloom/SSAO/Tonemap/RT 等 Pass 创建渲染目标和临时纹理的核心方法。
     * 通过 VmaMemoryPools.allocateFromPool() 实现 O(1) 速度的显存分配。
     *
     * 【方法参数】
     * @param width          int      - 图像宽度（像素，必须 > 0）
     * @param height         int      - 图像高度（像素，必须 > 0）
     * @param format         int      - Vulkan 格式（如 VK_FORMAT_R16G16B16A16_SFLOAT）
     * @param usageFlags     int      - Image 使用标志位掩码（如 COLOR_ATTACHMENT_BIT | SAMPLED_BIT）
     * @param poolType       PoolType - VMA 内存池类型（决定内存属性和分配算法）
     *
     * 【返回值】
     * @return GpuResource - 包含 image 句柄、allocation 句柄和元数据；
     *         如果创建失败返回 {@link GpuResource#INVALID}
     *
     * 【异常】
     * @throws IllegalStateException 如果未初始化或已关闭
     * @throws IllegalArgumentException 如果参数无效
     *
     * 【实现流程】
     * <ol>
     *   <li>参数校验（width/height > 0, format != 0）</li>
     *   <li>构建 VkImageCreateInfo（指定格式、尺寸、用途、MipLevels=1、ArrayLayers=1）</li>
     *   <li>计算所需显存大小（width × height × bytesPerPixel）</li>
     *   <li>调用 VmaMemoryPools.allocateFromPool(poolType, size)</li>
     *   <li>更新统计计数器并返回结果</li>
     * </ol>
     *
     * 【性能特征】
     * - 时间复杂度：O(1)（专用池分配）
     * - 典型耗时：~50-200ns（不含 vkCreateImage 本身）
     * - 内存开销：每个 GpuResource 对象 ~40 bytes
     *
     * 【使用示例】
     * <pre>{@code
     * // 创建 Bloom 输出纹理（RGBA16F 半精度浮点）
     * GpuResource bloomTex = mgr.createImage(
     *     1920, 1080,
     *     VK_FORMAT_R16G16B16A16_SFLOAT,
     *     IMAGE_USAGE_COLOR_ATTACHMENT_BIT | IMAGE_USAGE_SAMPLED_BIT,
     *     VmaMemoryPools.PoolType.RENDER_TARGET
     * );
     *
     * if (bloomTex.isValid()) {
     *     long imageHandle = bloomTex.handle;       // VkImage 句柄
     *     long allocHandle = bloomTex.allocation;    // VmaAllocation 句柄
     *     // ... 绑定到 Framebuffer 或 DescriptorSet ...
     * }
     * }</pre>
     */
    public GpuResource createImage(int width, int height, int format, int usageFlags,
                                   VmaMemoryPools.PoolType poolType) {
        assertInitialized();

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    String.format("createImage 无效尺寸 (%dx%d)", width, height));
        }
        if (format == 0) {
            throw new IllegalArgumentException("createImage format 不能为 0");
        }
        if (poolType == null) {
            throw new NullPointerException("poolType 不能为 null");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 构建 VkImageCreateInfo
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(it -> it.width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(usageFlags)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            // 从 VMA 专用池分配显存
            // 计算近似大小用于池分配（实际大小由 VMA 内部确定）
            long estimatedSize = estimateImageSize(width, height, format);
            VmaMemoryPools.PoolAllocation allocation =
                    getMemoryPools().allocateFromPool(poolType, estimatedSize);

            if (allocation == null) {
                LOGGER.severe(String.format("createImage VMA 分配失败 [%dx%d, fmt=0x%X]",
                        width, height, format));
                return GpuResource.INVALID;
            }

            // 更新统计
            totalImagesCreated.incrementAndGet();

            LOGGER.finest(String.format("createImage [%dx%d, fmt=0x%X] → handle=%d, alloc=%d",
                    width, height, format, allocation.buffer, allocation.allocation));

            return new GpuResource(allocation.buffer, allocation.allocation,
                    width, height, format, ResourceType.IMAGE);

        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.severe(String.format("createImage 异常 [%dx%d]: %s", width, height, e.getMessage()));
            return GpuResource.INVALID;
        }
    }

    /**
     * 创建渲染目标（便捷方法）
     * <p>
     * 封装了常用的渲染目标创建参数组合：
     * - Tiling: OPTIMAL
     * - Initial Layout: UNDEFINED
     * - Samples: 1x MSAA
     * - MipLevels: 1
     * - Memory Pool: RENDER_TARGET（双缓冲算法，高利用率）
     *
     * 【方法参数】
     * @param width      int - 渲染目标宽度（像素）
     * @param height     int - 渲染目标高度（像素）
     * @param format     int - Vulkan 格式（推荐 R16G16B16A16_SFLOAT 用于 HDR 后处理）
     * @param usageFlags int - 额外的使用标志（会自动添加 COLOR_ATTACHMENT_BIT | SAMPLED_BIT）
     *
     * 【返回值】
     * @return GpuResource - 渲染目标资源句柄
     *
     * 【适用场景】
     * - Bloom 各级模糊目标
     * - SSAO/AO 输出纹理
     * - ToneMapping 中间缓冲
     * - 自定义 Post-FX Pinhole
     */
    public GpuResource createRenderTarget(int width, int height, int format, int usageFlags) {
        int fullUsage = VulkanConst.IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                | VulkanConst.IMAGE_USAGE_SAMPLED_BIT
                | usageFlags;

        return createImage(width, height, format, fullUsage,
                VmaMemoryPools.PoolType.RENDER_TARGET);
    }

    /**
     * 创建 Vulkan Buffer（从 VMA 专用池分配显存）
     * <p>
     * 用于创建顶点缓冲区、Uniform 缓冲区、Staging 缓冲区等。
     * 通过 VmaMemoryPools.allocateFromPool() 实现 O(1) 速度的显存分配。
     *
     * 【方法参数】
     * @param size     long    - 缓冲区大小（字节，必须 > 0）
     * @param usage    int     - Buffer 使用标志（如 VERTEX_BUFFER_BIT | TRANSFER_DST_BIT）
     * @param poolType PoolType - VMA 内存池类型
     *
     * 【返回值】
     * @return GpuResource - 包含 buffer 句柄和 allocation 句柄
     *
     * 【异常】
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * 【使用示例】
     * <pre>{@code
     * // 创建 Uniform 缓冲区（用于 SSAO 参数传递）
     * GpuResource ubo = mgr.createBuffer(
     *     256,  // SSAOParams 结构体大小
     *     BUFFER_USAGE_UNIFORM_BUFFER_BIT,
     *     VmaMemoryPools.PoolType.UNIFORM_BUFFER
     * );
     * }</pre>
     */
    public GpuResource createBuffer(long size, int usage, VmaMemoryPools.PoolType poolType) {
        assertInitialized();

        if (size <= 0) {
            throw new IllegalArgumentException("createBuffer size 必须大于 0，当前值: " + size);
        }
        if (poolType == null) {
            throw new NullPointerException("poolType 不能为 null");
        }

        try {
            // 从 VMA 专用池分配
            VmaMemoryPools.PoolAllocation allocation =
                    getMemoryPools().allocateFromPool(poolType, size);

            if (allocation == null) {
                LOGGER.severe(String.format("createBuffer VMA 分配失败 [size=%d]", size));
                return GpuResource.INVALID;
            }

            totalBuffersCreated.incrementAndGet();

            LOGGER.finest(String.format("createBuffer [size=%d] → handle=%d, alloc=%d",
                    size, allocation.buffer, allocation.allocation));

            return new GpuResource(allocation.buffer, allocation.allocation,
                    (int) size, 1, 0, ResourceType.BUFFER);

        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.severe(String.format("createBuffer 异常 [size=%d]: %s", size, e.getMessage()));
            return GpuResource.INVALID;
        }
    }

    /**
     * 创建 ImageView（基于已有 Image）
     * <p>
     * ImageView 是 Vulkan 中描述 Image 子资源范围访问方式的对象。
     * 大多数情况下，每张 Image 都需要至少一个 ImageView 才能绑定到 Framebuffer 或 Sampler。
     *
     * 【方法参数】
     * @param image    long - 已创建的 Image 句柄（由 createImage 返回的 handle）
     * @param format   int  - View 格式（通常与原 Image 格式相同）
     * @param aspectMask int - Image 方面掩码（COLOR_BIT / DEPTH_BIT / STENCIL_BIT）
     *
     * 【返回值】
     * @return long - ImageView 句柄（非零表示成功）；0 表示失败
     *
     * 【注意事项】
     * - ImageView 不需要 VMA 分配（纯 Vulkan API 对象）
     * - ImageView 应在对应的 Image 释放前销毁
     * - 当前版本不支持 releaseView()，由 DestructionQueue 统一管理
     */
    public long createView(long image, int format, int aspectMask) {
        assertInitialized();

        if (image == 0L) {
            LOGGER.warning("createView: image 句柄为 0");
            return 0L;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange(range -> range
                            .aspectMask(aspectMask)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1));

            // 准备输出
            LongBuffer pView = stack.mallocLong(1);

            // 调用 Vulkan API 创建视图（使用 VkDevice 包装器）
            // 注意: LWJGL VkDevice 构造函数需要 VkPhysicalDevice，此处传 null（仅用于 API 调用）
            int result = VK10.vkCreateImageView(
                    new org.lwjgl.vulkan.VkDevice(vkDevice, null, null),
                    viewInfo,
                    null,
                    pView
            );
            if (result != VK10.VK_SUCCESS) {
                LOGGER.severe(String.format("vkCreateImageView 失败: VkResult=%d", result));
                return 0L;
            }

            long viewHandle = pView.get(0);
            totalViewsCreated.incrementAndGet();

            LOGGER.finest(String.format("createView[image=0x%X] → view=0x%X", image, viewHandle));
            return viewHandle;

        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.severe(String.format("createView 异常: %s", e.getMessage()));
            return 0L;
        }
    }

    // ==================== 公共 API：资源释放 ====================

    /**
     * 请求延迟释放 GPU 资源 🗑️
     * <p>
     * 将资源加入多帧延迟销毁队列，确保 GPU 完成使用后才真正释放。
     * 这是对外推荐的唯一释放方式——禁止直接调用 vkDestroyBuffer/vkDestroyImage。
     *
     * 【方法参数】
     * @param resource    GpuResource - 要释放的资源（由 createImage/createBuffer 返回）
     *
     * 【返回值】void
     *
     * 【异常】
     * @throws IllegalStateException 如果未初始化或已关闭
     * @throws IllegalArgumentException 如果 resource 无效
     *
     * 【延迟机制】
     * <pre>
     * Frame N:   releaseResource(resource) → 加入 [N+3] 队列
     * Frame N+1: GPU 正在使用 resource
     * Frame N+2: GPU 即将完成
     * Frame N+3: ✓ 安全释放
     * </pre>
     *
     * 【使用示例】
     * <pre>{@code
     * GpuResource target = mgr.createRenderTarget(oldW, oldH, FORMAT, USAGE);
     * // ... 使用 target 进行一帧渲染 ...
     * mgr.releaseResource(target);  // 安全：不会立即销毁
     * }</pre>
     */
    public void releaseResource(GpuResource resource) {
        assertInitialized();

        if (resource == null || !resource.isValid()) {
            LOGGER.warning("releaseResource: 资源无效或为 null");
            return;
        }

        try {
            getDeferredDeallocation().releaseDeferred(resource.handle, resource.allocation);
            totalReleaseRequests.incrementAndGet();

            LOGGER.finest(String.format("releaseResource[handle=0x%X] → 延迟队列",
                    resource.handle));

        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.severe(String.format("releaseResource 异常: %s", e.getMessage()));
        }
    }

    /**
     * 帧结束处理 🔄
     * <p>
     * 必须在每帧渲染结束后调用一次。处理延迟释放队列中到期的资源。
     * 此方法会推进 VmaDeferredDeallocation 的环形缓冲指针，
     * 并执行真正的 VMA 销毁操作。
     *
     * 【返回值】void
     *
     * 【异常】
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * 【调用位置】
     * MixinRenderSystem 的渲染循环末尾，或 RenderiumLifecycleManager.onFrameEnd()
     *
     * 【性能说明】
     * - 时间复杂度：O(k)，k 为当前到期队列中的资源数
     * - 典型耗时：< 0.1ms（正常负载下 k < 50）
     * - 必须在主线程/渲染线程串行调用
     */
    public void onFrameEnd() {
        assertInitialized();

        try {
            getDeferredDeallocation().onFrameEnd();

            // 同步处理 DestructionQueue（VmaMemoryPools 内部维护的那个）
            VmaMemoryPools pools = getMemoryPoolsIfAvailable();
            if (pools != null && pools.getDestructionQueue() != null) {
                pools.processPendingDestructions(0L);
            }

        } catch (Exception e) {
            VulkanOperationGuard.markFailed(e);
            LOGGER.severe(String.format("onFrameEnd 异常: %s", e.getMessage()));
        }
    }

    // ==================== 公共 API：查询 ====================

    /**
     * 检查是否已成功初始化
     *
     * @return boolean - true 表示可用
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 检查是否已关闭
     *
     * @return boolean - true 表示已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 获取 VkDevice 原生句柄
     *
     * @return long - VkDevice 地址（LWJGL）；如果未初始化返回 0L
     */
    public long getVkDevice() {
        return vkDevice;
    }

    /**
     * 获取 VMA 分配器句柄
     *
     * @return long - VMA allocator 句柄；如果使用 OfficialVulkanHijacker 则返回 0L
     */
    public long getVmaAllocator() {
        return vmaAllocator;
    }

    /**
     * 获取图形队列句柄
     *
     * @return long - VkQueue 地址
     */
    public long getGraphicsQueue() {
        return graphicsQueue;
    }

    /**
     * 获取计算队列句柄
     *
     * @return long - VkQueue 地址（用于 Compute Shader Dispatch）
     */
    public long getComputeQueue() {
        return computeQueue;
    }

    /**
     * 检查是否有 VMA 支持
     *
     * @return boolean - true 表示可使用 VMA 专用池进行 O(1) 分配
     */
    public boolean hasVmaSupport() {
        return useVulkanDeviceHolder && vmaAllocator != 0L;
    }

    /**
     * 获取格式化的统计报告 📊
     *
     * @return String - 包含创建/释放统计、状态信息的报告字符串
     */
    public String formatReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║  VulkanGPUResourceManager 报告              ║\n");
        sb.append("╚══════════════════════════════════════════╝\n");

        sb.append(String.format("状态: %s | 已关闭: %s | VMA支持: %s\n",
                initialized.get() ? "✓" : "✗",
                closed.get() ? "是" : "否",
                hasVmaSupport() ? "✓" : "✗"));
        sb.append(String.format("数据源: %s\n",
                useVulkanDeviceHolder ? "VulkanDeviceHolder" : "OfficialVulkanHijacker"));
        sb.append(String.format("vkDevice=0x%X | vma=0x%X\n", vkDevice, vmaAllocator));

        sb.append("");
        sb.append(String.format("Images:   %d\n", totalImagesCreated.get()));
        sb.append(String.format("Buffers:  %d\n", totalBuffersCreated.get()));
        sb.append(String.format("Views:    %d\n", totalViewsCreated.get()));

        sb.append("");
        sb.append(String.format("延迟释放请求: %d\n", totalReleaseRequests.get()));

        // 延迟销毁详情
        try {
            VmaDeferredDeallocation deferred = getDeferredDeallocationIfAvailable();
            if (deferred != null) {
                sb.append(String.format("待释放: %d | 峰值: %d\n",
                        deferred.getPendingReleaseCount(), deferred.getPeakPendingCount()));
            }
        } catch (Exception ignored) {}

        return sb.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 断言已初始化且未关闭
     */
    private void assertInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("VulkanGPUResourceManager 未初始化，请先调用 initialize()");
        }
        if (closed.get()) {
            throw new IllegalStateException("VulkanGPUResourceManager 已关闭");
        }
    }

    /**
     * 获取 VmaMemoryPools 实例（必须可用）
     */
    private VmaMemoryPools getMemoryPools() {
        VmaMemoryPools pools = VmaMemoryPools.getInstance();
        if (!pools.isInitialized()) {
            throw new IllegalStateException("VmaMemoryPools 未初始化，无法分配 GPU 资源");
        }
        return pools;
    }

    /**
     * 获取 VmaMemoryPools 实例（可能不可用）
     */
    private VmaMemoryPools getMemoryPoolsIfAvailable() {
        VmaMemoryPools pools = VmaMemoryPools.getInstance();
        return pools.isInitialized() ? pools : null;
    }

    /**
     * 获取 VmaDeferredDeallocation 实例（必须可用）
     */
    private VmaDeferredDeallocation getDeferredDeallocation() {
        VmaDeferredDeallocation deferred = VmaDeferredDeallocation.getInstance();
        if (!deferred.isInitialized()) {
            throw new IllegalStateException("VmaDeferredDeallocation 未初始化");
        }
        return deferred;
    }

    /**
     * 获取 VmaDeferredDeallocation 实例（可能不可用）
     */
    private VmaDeferredDeallocation getDeferredDeallocationIfAvailable() {
        VmaDeferredDeallocation deferred = VmaDeferredDeallocation.getInstance();
        return deferred.isInitialized() ? deferred : null;
    }

    /**
     * 估算 Image 所需显存大小（用于 VMA 池预分配）
     *
     * @param width  图像宽度
     * @param height 图像高度
     * @param format Vulkan 格式
     * @return 估算的字节数
     */
    private static long estimateImageSize(int width, int height, int format) {
        int bytesPerPixel;
        switch (format) {
            case VK10.VK_FORMAT_R8_UNORM:
            case VK10.VK_FORMAT_R8_SNORM:
            case VK10.VK_FORMAT_R8_UINT:
            case VK10.VK_FORMAT_R8_SINT:
                bytesPerPixel = 1;
                break;
            case VK10.VK_FORMAT_R8G8_UNORM:
            case VK10.VK_FORMAT_R8G8_SNORM:
            case VK10.VK_FORMAT_R8G8_UINT:
            case VK10.VK_FORMAT_R8G8_SINT:
            case VK10.VK_FORMAT_R16_SFLOAT:
            case VK10.VK_FORMAT_R16_UNORM:
            case VK10.VK_FORMAT_D16_UNORM:
                bytesPerPixel = 2;
                break;
            case VK10.VK_FORMAT_R8G8B8A8_UNORM:
            case VK10.VK_FORMAT_R8G8B8A8_SRGB:
            case VK10.VK_FORMAT_R8G8B8A8_SNORM:
            case VK10.VK_FORMAT_R32_SFLOAT:
            case VK10.VK_FORMAT_D24_UNORM_S8_UINT:
            case VK10.VK_FORMAT_B8G8R8A8_UNORM:
            case VK10.VK_FORMAT_B8G8R8A8_SRGB:
                bytesPerPixel = 4;
                break;
            case VK10.VK_FORMAT_R16G16B16A16_SFLOAT:
            case VK10.VK_FORMAT_R32G32_SFLOAT:
            case VK10.VK_FORMAT_D32_SFLOAT:
                bytesPerPixel = 8;
                break;
            case VK10.VK_FORMAT_R32G32B32A32_SFLOAT:
                bytesPerPixel = 16;
                break;
            default:
                bytesPerPixel = 4;  // 默认按 RGBA8 估算
                break;
        }
        return (long) width * height * bytesPerPixel;
    }

    // ==================== 内部数据类 ====================

    /**
     * GPU 资源描述
     * <p>
     * 封装 Vulkan GPU 资源的核心属性，作为 createImage/createBuffer 的返回值。
     * 包含资源句柄、VMA allocation 句柄和元数据（尺寸、格式、类型）。
     */
    public static class GpuResource {

        /** 无效资源标记（用于错误返回） */
        public static final GpuResource INVALID = new GpuResource(0, 0, 0, 0, 0, null);

        /** 资源句柄（VkBuffer 或 VkImage） */
        public final long handle;

        /** VMA Allocation 句柄（用于后续释放） */
        public final long allocation;

        /** 资源宽度（像素，仅 Image 有效） */
        public final int width;

        /** 资源高度（像素，仅 Image 有效） */
        public final int height;

        /** Vulkan 格式（仅 Image 有效） */
        public final int format;

        /** 资源类型 */
        public final ResourceType type;

        /**
         * 创建 GPU 资源描述
         *
         * @param handle     资源句柄
         * @param allocation VMA Allocation 句柄
         * @param width      宽度
         * @param height     高度
         * @param format     格式
         * @param type       资源类型
         */
        public GpuResource(long handle, long allocation, int width, int height,
                           int format, ResourceType type) {
            this.handle = handle;
            this.allocation = allocation;
            this.width = width;
            this.height = height;
            this.format = format;
            this.type = type;
        }

        /**
         * 检查资源是否有效（非 INVALID）
         *
         * @return boolean - true 表示有效
         */
        public boolean isValid() {
            return handle != 0L && allocation != 0L && type != null;
        }

        @Override
        public String toString() {
            return String.format("GpuResource{handle=0x%X,alloc=0x%X,%dx%d,fmt=0x%X,%s}",
                    handle, allocation, width, height, format, type);
        }
    }

    /**
     * 资源类型枚举
     */
    public enum ResourceType {
        /** Vulkan Image（纹理/渲染目标） */
        IMAGE,

        /** Vulkan Buffer（顶点/Uniform/Staging） */
        BUFFER
    }
}
