// Renderium - Hi-Z Buffer 管理器
// 管理 Hi-Z Mipmap 金字塔纹理的创建、销毁和尺寸适配
// 为 LodCullingComputePass 提供纹理视图句柄

package com.ranecc.renderium.infrastructure.gpu;


import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.logging.Logger;

import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * Hi-Z 纹理数组管理器（准备性类）
 * <p>
 * 管理 Hi-Z（Hierarchical Z-Buffer）遮挡剔除所需的 GPU 纹理资源。
 * 负责维护 Hi-Z Mipmap 金字塔的纹理视图句柄，
 * 供 {@code LodCullingComputePass} 在 Compute Shader 中使用。
 *
 * <h3>架构角色：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    HiZBufferManager                         │
 * │  管理所有 Vulkan 句柄（Image/ImageView/Memory/UBO）          │
 * └────────────┬────────────────────────────────────────────────┘
 *              │ 提供句柄
 *              ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │               LodCullingComputePass                        │
 * │  使用这些句柄绑定 DescriptorSet 并调度 Compute Shader       │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>管理的资源：</h3>
 * <ul>
 *   <li><b>深度缓冲输入纹理</b> - 来自 Opaque Pass 的深度图，用于构建 Hi-Z</li>
 *   <li><b>Hi-Z Mipmap 纹理数组</b> - 10 层 Mipmap，每层为上一层 1/2 分辨率</li>
 *   <li><b>Hi-Z ImageView 数组</b> - 每层的 Storage Image 视图（用于 hiz_build.comp 写入）</li>
 *   <li><b>Hi-Z Sampler View 数组</b> - 每层的 Sampled Image 视图（用于 hiz_occlusion_culling.comp 读取）</li>
 *   <li><b>配置 UBO</b> - {@link OcclusionCullConfig} 的 GPU 缓冲区</li>
 * </ul>
 *
 * <h3>生命周期管理：</h3>
 * <pre>
 * 1. 构造: new HiZBufferManager(holder)
 * 2. 初始化: initialize(width, height) → 创建 Vulkan 资源
 * 3. 使用: getHiZMipmapImageViews() / getConfigBufferView() → 获取句柄
 * 4. 调整: resize(newWidth, newHeight) → 屏幕尺寸变化时重建
 * 5. 销毁: dispose() → 按 Vulkan 规范顺序释放资源
 * </pre>
 *
 * <h3>线程安全性：</h3>
 * <ul>
 *   <li>所有句柄字段使用 volatile 修饰，保证跨线程可见性</li>
 *   <li>initialize/dispose/resize 需外部同步（通常在渲染线程调用）</li>
 *   <li>Getter 方法无锁读取，适合高频调用</li>
 * </ul>
 *
 * <h3>Vulkan 资源规范：</h3>
 * <ul>
 *   <li>格式: VK_FORMAT_R32G32B32A32_SFLOAT (RGBA32F)</li>
 *   <li>Mipmap 层数: {@value #HIZ_MAX_MIP_LEVELS} (10)</li>
 *   <li>用途: VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT</li>
 * </ul>
 *
 * @see OcclusionCullConfig
 * @see com.renderium.framegraph.pass.LodCullingComputePass
 * @since 5.2.0
 */
public final class HiZBufferManager {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(HiZBufferManager.class.getName());

    /** 单例实例 */
    private static volatile HiZBufferManager instance;

    /** 是否启用 */
    private volatile boolean enabled = true;

    /** Hi-Z 最大 Mipmap 层数（与 GLSL shader 和 OcclusionCullConfig 一致） */
    public static final int HIZ_MAX_MIP_LEVELS = 10;

    /** 配置 UBO 大小（192 字节，std140 布局） */
    private static final long CONFIG_UBO_SIZE = 192;

    // ==================== Vulkan 结构体尺寸（x64 MSVC ABI） ====================

    private static final long SZ_IMAGE_CREATE_INFO = 88;
    private static final long SZ_IMAGE_VIEW_CREATE_INFO = 80;
    private static final long SZ_BUFFER_CREATE_INFO = 56;
    private static final long SZ_MEMORY_ALLOCATE_INFO = 32;
    private static final long SZ_MEMORY_REQUIREMENTS = 24;
    private static final long SZ_SAMPLER_CREATE_INFO = 80;

    // ==================== Vulkan 结构体字段偏移 ====================

    // VkImageCreateInfo
    private static final long OFF_ICI_STYPE = 0;
    private static final long OFF_ICI_PNEXT = 8;
    private static final long OFF_ICI_FLAGS = 16;
    private static final long OFF_ICI_IMAGE_TYPE = 20;
    private static final long OFF_ICI_FORMAT = 24;
    private static final long OFF_ICI_EXTENT_WIDTH = 28;
    private static final long OFF_ICI_EXTENT_HEIGHT = 32;
    private static final long OFF_ICI_EXTENT_DEPTH = 36;
    private static final long OFF_ICI_MIP_LEVELS = 40;
    private static final long OFF_ICI_ARRAY_LAYERS = 44;
    private static final long OFF_ICI_SAMPLES = 48;
    private static final long OFF_ICI_TILING = 52;
    private static final long OFF_ICI_USAGE = 56;
    private static final long OFF_ICI_SHARING_MODE = 60;
    private static final long OFF_ICI_QUEUE_FAMILY_INDEX_COUNT = 64;
    private static final long OFF_ICI_P_QUEUE_FAMILY_INDICES = 72;
    private static final long OFF_ICI_INITIAL_LAYOUT = 80;

    // VkImageViewCreateInfo
    private static final long OFF_IVCI_STYPE = 0;
    private static final long OFF_IVCI_PNEXT = 8;
    private static final long OFF_IVCI_FLAGS = 16;
    private static final long OFF_IVCI_IMAGE = 24;
    private static final long OFF_IVCI_VIEW_TYPE = 32;
    private static final long OFF_IVCI_FORMAT = 36;
    private static final long OFF_IVCI_COMPONENTS_R = 40;
    private static final long OFF_IVCI_COMPONENTS_G = 44;
    private static final long OFF_IVCI_COMPONENTS_B = 48;
    private static final long OFF_IVCI_COMPONENTS_A = 52;
    private static final long OFF_IVCI_SUBRESOURCE_ASPECT_MASK = 56;
    private static final long OFF_IVCI_SUBRESOURCE_BASE_MIP = 60;
    private static final long OFF_IVCI_SUBRESOURCE_LEVEL_COUNT = 64;
    private static final long OFF_IVCI_SUBRESOURCE_BASE_ARRAY = 68;
    private static final long OFF_IVCI_SUBRESOURCE_LAYER_COUNT = 72;

    // VkBufferCreateInfo
    private static final long OFF_BCI_STYPE = 0;
    private static final long OFF_BCI_PNEXT = 8;
    private static final long OFF_BCI_FLAGS = 16;
    private static final long OFF_BCI_SIZE = 24;
    private static final long OFF_BCI_USAGE = 32;
    private static final long OFF_BCI_SHARING_MODE = 36;
    private static final long OFF_BCI_QUEUE_FAMILY_INDEX_COUNT = 40;
    private static final long OFF_BCI_P_QUEUE_FAMILY_INDICES = 48;

    // VkMemoryAllocateInfo
    private static final long OFF_MAI_STYPE = 0;
    private static final long OFF_MAI_PNEXT = 8;
    private static final long OFF_MAI_ALLOCATION_SIZE = 16;
    private static final long OFF_MAI_MEMORY_TYPE_INDEX = 24;

    // VkMemoryRequirements
    private static final long OFF_MEMREQ_SIZE = 0;
    private static final long OFF_MEMREQ_ALIGNMENT = 8;
    private static final long OFF_MEMREQ_MEMORY_TYPE_BITS = 16;

    // VkSamplerCreateInfo
    private static final long OFF_SCI_STYPE = 0;
    private static final long OFF_SCI_PNEXT = 8;
    private static final long OFF_SCI_FLAGS = 16;
    private static final long OFF_SCI_MAG_FILTER = 20;
    private static final long OFF_SCI_MIN_FILTER = 24;
    private static final long OFF_SCI_MIPMAP_MODE = 28;
    private static final long OFF_SCI_ADDRESS_MODE_U = 32;
    private static final long OFF_SCI_ADDRESS_MODE_V = 36;
    private static final long OFF_SCI_ADDRESS_MODE_W = 40;
    private static final long OFF_SCI_MIP_LOD_BIAS = 44;
    private static final long OFF_SCI_ANISOTROPY_ENABLE = 48;
    private static final long OFF_SCI_MAX_ANISOTROPY = 52;
    private static final long OFF_SCI_COMPARE_ENABLE = 56;
    private static final long OFF_SCI_COMPARE_OP = 60;
    private static final long OFF_SCI_MIN_LOD = 64;
    private static final long OFF_SCI_MAX_LOD = 68;
    private static final long OFF_SCI_BORDER_COLOR = 72;
    private static final long OFF_SCI_UNNORMALIZED_COORDINATES = 76;

    // ==================== Vulkan 枚举常量 ====================

    private static final int VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO = 11;
    private static final int VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO = 12;
    private static final int VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO = 3;
    private static final int VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO = 32;
    private static final int VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO = 20;

    private static final int VK_IMAGE_TYPE_2D = 1;
    private static final int VK_IMAGE_VIEW_TYPE_2D = 1;
    private static final int VK_FORMAT_R32G32B32A32_SFLOAT = 0x57;
    private static final int VK_IMAGE_USAGE_STORAGE_BIT = 0x0008;
    private static final int VK_IMAGE_USAGE_SAMPLED_BIT = 0x0004;
    private static final int VK_IMAGE_USAGE_TRANSFER_SRC_BIT = 0x0020;
    private static final int VK_IMAGE_USAGE_TRANSFER_DST_BIT = 0x0040;
    private static final int VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT = 0x0010;

    private static final int VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 0x0001;
    private static final int VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x0002;

    private static final int VK_SAMPLE_COUNT_1_BIT = 1;
    private static final int VK_SHARING_MODE_EXCLUSIVE = 0;
    private static final int VK_IMAGE_ASPECT_COLOR_BIT = 0x0001;
    private static final int VK_IMAGE_TILING_OPTIMAL = 0;
    private static final int VK_IMAGE_LAYOUT_UNDEFINED = 0;
    private static final int VK_FILTER_NEAREST = 0;
    private static final int VK_SAMPLER_MIPMAP_MODE_NEAREST = 0;
    private static final int VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE = 2;
    private static final int VK_COMPONENT_SWIZZLE_IDENTITY = 0;
    private static final int VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK = 0;

    // ==================== Vulkan 设备引用 ====================

    /**
     * VulkanDeviceHolder 引用（不可变，构造时赋值）
     * 用于获取 VkDevice 句柄和 VMA 分配器
     */
    private final VulkanDeviceHolder deviceHolder;

    // ==================== 尺寸信息 ====================

    /**
     * 当前 Hi-Z 纹理宽度（volatile 保证线程可见性）
     * 初始值: 0（未初始化）
     */
    private volatile int currentWidth = 0;

    /**
     * 当前 Hi-Z 纹理高度（volatile 保证线程可见性）
     * 初始值: 0（未初始化）
     */
    private volatile int currentHeight = 0;

    // ==================== 深度缓冲输入资源 ====================

    /**
     * 深度缓冲输入纹理的 VkImageView 句柄（volatile，线程安全读取）
     * <p>
     * 此纹理由 Opaque Pass 输出，作为 Hi-Z 构建的输入源。
     * 实际的 VkImage 创建和管理由外部（FrameGraph）负责，
     * 此处仅存储 ImageView 句柄供 Shader 绑定使用。
     * <ul>
     *   <li>初始值: 0L（未初始化）</li>
     *   <li>有效值: 非 0 的 VkImageView 原生句柄</li>
     * </ul>
     */
    private volatile long depthBufferView = 0L;

    // ==================== Hi-Z Mipmap 资源 ====================

    /**
     * Hi-Z 各层级 Storage Image 的 VkImageView 句柄数组（volatile）
     * <p>
     * 数组长度固定为 {@value #HIZ_MAX_MIP_LEVELS} (10)。
     * 每个元素对应一层 Mipmap 的 ImageView，用于：
     * <ul>
     *   <li>{@code hiz_build.comp} 写入（VK_IMAGE_LAYOUT_GENERAL, storage image）</li>
     *   <li>索引 0 = 最高分辨率（与深度缓冲相同尺寸）</li>
     *   <li>索引 i 的分辨率为 (width/2^i, height/2^i)</li>
     * </ul>
     * <ul>
     *   <li>初始状态: 所有元素为 0L（未初始化）</li>
     *   <li>initialize() 后: 所有元素为有效的 VkImageView 句柄</li>
     *   <li>dispose() 后: 重置为 0L</li>
     * </ul>
     */
    private volatile long[] hiZMipmapImageViews = new long[HIZ_MAX_MIP_LEVELS];

    /**
     * Hi-Z 各层级 Sampled Image 的 VkImageView 句柄数组（volatile）
     * <p>
     * 与 {@link #hiZMipmapImageViews} 一一对应，但用途不同：
     * <ul>
     *   <li>用于 {@code hiz_occlusion_culling.comp} 读取（sampler2D）</li>
     *   <li>绑定到 combined image sampler descriptor</li>
     *   <li>Shader 中通过 sampler2D[10] 数组访问</li>
     * </ul>
     * 通常与 hiZMipmapImageViews 指向同一 VkImage 的不同 aspect 或布局。
     */
    private volatile long[] hiZMipmapSamplerViews = new long[HIZ_MAX_MIP_LEVELS];

    // ==================== 配置 UBO 资源 ====================

    /**
     * 配置 UBO (OcclusionCullConfig) 的 VkBuffer View 句柄（volatile）
     * <p>
     * 存储 std140 布局的 CameraAndHiZData uniform block 数据。
     * 通过 vkCmdUpdateBuffer 或 Staging Buffer 上传到 GPU。
     * <ul>
     *   <li>初始值: 0L（未初始化）</li>
     *   <li>有效值: VkBuffer 句柄（大小至少 192 字节）</li>
     *   <li>Binding: 5（与 GLSL layout (std140, binding = 5) 一致）</li>
     * </ul>
     */
    private volatile long configBufferView = 0L;

    // ==================== 内部资源追踪（用于 dispose）====================

    /**
     * Hi-Z 纹理数组的 VkImage 句柄（内部追踪，用于销毁）
     * <p>
     * 这是包含所有 Mipmap 层级的单一 VkImage 对象。
     * dispose() 时需先销毁所有 ImageView，再销毁此 Image。
     */
    private volatile long hiZImageHandle = 0L;

    /**
     * Hi-Z 纹理的 VkDeviceMemory 句柄（内部追踪，用于释放）
     * <p>
     * 如果使用 VMA 分配，此项可能为 0L（由 VMA 管理）。
     * 如果单独分配设备内存，此处存储内存句柄以便释放。
     */
    private volatile long hiZDeviceMemory = 0L;

    /**
     * 配置 UBO 的 VkBuffer 句柄（内部追踪，用于销毁）
     * <p>
     * configBufferView 是 Buffer View 或 Descriptor 中的偏移量，
     * 此处存储实际的 VkBuffer 句柄用于 vkDestroyBuffer。
     */
    private volatile long configBufferHandle = 0L;

    /**
     * Hi-Z 采样器的 VkSampler 句柄（volatile，线程安全读取）
     * <p>
     * 使用 VK_FILTER_NEAREST 和 VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE 创建，
     * 用于 Hi-Z 纹理读取时的点采样和边缘钳制。
     */
    private volatile long hiZSampler = 0L;

    /**
     * 配置 UBO 的 VkDeviceMemory 句柄（内部追踪，用于释放）
     * <p>
     * Host Visible + Host Coherent 内存，用于每帧更新 OcclusionCullConfig 数据。
     */
    private volatile long configBufferMemory = 0L;

    /**
     * 当前实际 Mipmap 层级数量
     * 根据 width/height 计算，不超过 HIZ_MAX_MIP_LEVELS
     */
    private int currentMipLevels = 0;

    // ==================== 状态标志 ====================

    /**
     * 是否已完成初始化（volatile 保证线程可见性）
     * <p>
     * true 表示所有 Vulkan 资源已成功创建且可用。
     * false 表示尚未初始化或已 dispose。
     */
    private volatile boolean initialized = false;

    // ==================== 构造函数 ====================

    /**
     * 构造 Hi-Z Buffer 管理器
     * <p>
     * 仅保存 VulkanDeviceHolder 引用，不创建任何 Vulkan 资源。
     * 实际的资源创建在 {@link #initialize(int, int)} 中完成。
     *
     * 【方法参数】
     * @param deviceHolder VulkanDeviceHolder - Vulkan 设备句柄持有者（不能为 null）
     *                                  必须已通过 initialize() 完成初始化
     *                                  用于获取 VkDevice、VMA allocator 等原生句柄
     *
     * 【异常】
     * @throws IllegalArgumentException 如果 deviceHolder 为 null
     *
     * 【性能特征】
     * - O(1) 操作，仅保存引用
     * - 无 Vulkan API 调用
     *
     * 【使用示例】
     * <pre>
     * VulkanDeviceHolder holder = VulkanDeviceHolder.getInstance();
     * HiZBufferManager manager = new HiZBufferManager(holder);
     * manager.initialize(1920, 1080);
     * </pre>
     */
    public HiZBufferManager(VulkanDeviceHolder deviceHolder) {
        if (deviceHolder == null) {
            throw new IllegalArgumentException("VulkanDeviceHolder 不能为 null");
        }
        this.deviceHolder = deviceHolder;
    }

    /**
     * 获取单例实例
     *
     * @return HiZBufferManager 单例实例，如果未初始化返回 null
     */
    public static HiZBufferManager getInstance() {
        return instance;
    }

    /**
     * 初始化并设置单例实例
     *
     * @param deviceHolder VulkanDeviceHolder 设备持有者
     * @return HiZBufferManager 实例
     */
    public static synchronized HiZBufferManager initializeInstance(VulkanDeviceHolder deviceHolder) {
        if (instance == null) {
            instance = new HiZBufferManager(deviceHolder);
        }
        return instance;
    }

    /**
     * 设置是否启用 Hi-Z 遮挡剔除
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("HiZ 遮挡剔除: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 设置最大 Mipmap 层数
     *
     * @param maxMipLevels 最大层数（4-14）
     */
    public void setMaxMipLevels(int maxMipLevels) {
        int clamped = Math.max(4, Math.min(14, maxMipLevels));
        if (clamped != maxMipLevels) {
            LOGGER.warning("setMaxMipLayers: " + maxMipLevels + " 超出范围 [4, 14]，钳制为 " + clamped);
        }
        // 注意：实际修改需要重新初始化，这里仅记录日志
        LOGGER.info("设置 Hi-Z 最大 Mipmap 层数: " + clamped + "（需重新初始化生效）");
    }

    // ==================== 核心生命周期方法 ====================

    /**
     * 初始化 Hi-Z 纹理资源
     * <p>
     * 创建 Hi-Z Mipmap 金字塔所需的所有 Vulkan 资源：
     * <ol>
     *   <li>创建 Hi-Z Image（VK_FORMAT_R32G32B32A32_SFLOAT，10 层 Mipmap）</li>
     *   <li>分配并绑定 Device Memory（或通过 VMA）</li>
     *   <li>为每层 Mipmap 创建 Storage Image ImageView（用于 hiz_build.comp）</li>
     *   <li>为每层 Mipmap创建 Sampled Image ImageView（用于 hiz_occlusion_culling.comp）</li>
     *   <li>创建配置 UBO Buffer（192 字节，std140 布局）</li>
     * </ol>
     *
     * <h3>Vulkan 资源规格：</h3>
     * <pre>
     * Image:
     *   Format:      VK_FORMAT_R32G32B32A32_SFLOAT
     *   Extent:      [width, height, 1]
     *   MipLevels:   HIZ_MAX_MIP_LEVELS (10)
     *   ArrayLayers: 1
     *   Samples:     VK_SAMPLE_COUNT_1_BIT
     *   Tiling:      VK_IMAGE_TILING_OPTIMAL
     *   Usage:       VK_IMAGE_USAGE_STORAGE_BIT |
     *                VK_IMAGE_USAGE_SAMPLED_BIT |
     *                VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
     *                VK_IMAGE_USAGE_TRANSFER_DST_BIT
     *   InitialLayout: VK_IMAGE_LAYOUT_UNDEFINED
     *
     * UBO Buffer:
     *   Size:        192 bytes (std140 aligned)
     *   Usage:       VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
     *   Memory:       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
     *                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
     * </pre>
     *
     * 【方法参数】
     * @param width  int - Hi-Z Level 0 纹理宽度（像素，必须 > 0）
     *                   通常等于屏幕宽度或向上取整到 2 的幂次
     * @param height int - Hi-Z Level 0 纹理高度（像素，必须 > 0）
     *                   通常等于屏幕高度或向上取整到 2 的幂次
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 width <= 0 或 height <= 0，抛出 IllegalArgumentException
     * - 如果已初始化，记录警告并直接返回（防止重复初始化）
     * - 如果 Vulkan 资源创建失败，抛出 RuntimeException 并清理部分创建的资源
     *
     * 【前置条件】
     * - deviceHolder.isInitialized() == true
     * - 应在渲染线程中调用（Vulkan API 非线程安全）
     *
     * 【后置条件】
     * - initialized == true
     * - all handle fields != 0L（除非创建失败）
     * - currentWidth / currentHeight 已更新
     *
     * 【性能特征】
     * - Vulkan 资源创建：约 1~5ms（取决于 GPU 和驱动）
     * - 内存分配：width × height × 16 bytes (RGBA32F) + UBO 192 bytes
     * - 例如 1920×1080: 约 ~33 MB 显存 + 192 字节 UBO
     *
     * 【线程安全性】
     * 此方法非线程安全，需外部保证串行调用
     */
    public void initialize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                "Hi-Z 尺寸必须为正整数，当前值: width=" + width + ", height=" + height);
        }

        if (this.initialized) {
            LOGGER.warning("HiZBufferManager 已初始化，跳过重复初始化");
            return;
        }

        if (!deviceHolder.isInitialized()) {
            throw new IllegalStateException(
                "VulkanDeviceHolder 未初始化，无法创建 Hi-Z 资源");
        }

        int mipLevels = (int) Math.min(
            Math.ceil(Math.log(Math.max(width, height)) / Math.log(2)) + 1,
            HIZ_MAX_MIP_LEVELS
        );

        LOGGER.info(String.format(
            "╔══════════════════════════════════════╗\n" +
            "║  HiZBufferManager 正在初始化...       ║\n" +
            "║  分辨率: %dx%d                         ║\n" +
            "║  Mipmap 层数: %d                       ║\n" +
            "║  格式: RGBA32F (R32G32B32A32_SFLOAT)  ║\n" +
            "╚══════════════════════════════════════╝",
            width, height, mipLevels));

        try {
            long vkDevice = deviceHolder.getVkDeviceHandle();
            this.currentWidth = width;
            this.currentHeight = height;
            this.currentMipLevels = mipLevels;

            try (Arena arena = Arena.ofConfined()) {
                // 获取 FFM MethodHandle
                MethodHandle vkCreateImage = VulkanFFMBinding.getVkCreateImage();
                MethodHandle vkDestroyImage = VulkanFFMBinding.getVkDestroyImage();
                MethodHandle vkCreateImageView = VulkanFFMBinding.getVkCreateImageView();
                MethodHandle vkGetImageMemoryRequirements = VulkanFFMBinding.getVkGetImageMemoryRequirements();
                MethodHandle vkAllocateMemory = VulkanFFMBinding.getVkAllocateMemory();
                MethodHandle vkBindImageMemory = VulkanFFMBinding.getVkBindImageMemory();
                MethodHandle vkCreateBuffer = VulkanFFMBinding.getVkCreateBuffer();
                MethodHandle vkDestroyBuffer = VulkanFFMBinding.getVkDestroyBuffer();
                MethodHandle vkGetBufferMemoryRequirements = VulkanFFMBinding.getVkGetBufferMemoryRequirements();
                MethodHandle vkBindBufferMemory = VulkanFFMBinding.getVkBindBufferMemory();
                MethodHandle vkCreateSampler = VulkanFFMBinding.getVkCreateSampler();

                // ==================== 1. 创建 Sampler ====================
                MemorySegment samplerCreateInfo = arena.allocate(SZ_SAMPLER_CREATE_INFO);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_STYPE / 4, VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_SCI_PNEXT / 8, 0L);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_FLAGS / 4, 0);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_MAG_FILTER / 4, VK_FILTER_NEAREST);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_MIN_FILTER / 4, VK_FILTER_NEAREST);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_MIPMAP_MODE / 4, VK_SAMPLER_MIPMAP_MODE_NEAREST);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_ADDRESS_MODE_U / 4, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_ADDRESS_MODE_V / 4, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_ADDRESS_MODE_W / 4, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_FLOAT, OFF_SCI_MIP_LOD_BIAS / 4, 0.0f);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_ANISOTROPY_ENABLE / 4, 0);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_FLOAT, OFF_SCI_MAX_ANISOTROPY / 4, 0.0f);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_COMPARE_ENABLE / 4, 0);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_COMPARE_OP / 4, 0);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_FLOAT, OFF_SCI_MIN_LOD / 4, 0.0f);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_FLOAT, OFF_SCI_MAX_LOD / 4, 0.0f);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_BORDER_COLOR / 4, VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
                samplerCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_SCI_UNNORMALIZED_COORDINATES / 4, 0);

                MemorySegment pSampler = arena.allocate(ValueLayout.JAVA_LONG);
                int samplerResult = (int) vkCreateSampler.invokeExact(vkDevice,
                    samplerCreateInfo.address(), 0L, pSampler.address());
                if (samplerResult != 0) {
                    throw new RuntimeException("vkCreateSampler 失败，VkResult=" + samplerResult);
                }
                this.hiZSampler = pSampler.getAtIndex(ValueLayout.JAVA_LONG, 0);
                LOGGER.fine("✓ Hi-Z Sampler 创建成功: 0x" + Long.toHexString(this.hiZSampler));

                // ==================== 2. 创建 Hi-Z Image ====================
                MemorySegment imageCreateInfo = arena.allocate(SZ_IMAGE_CREATE_INFO);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_STYPE / 4, VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_ICI_PNEXT / 8, 0L);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_FLAGS / 4, 0);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_IMAGE_TYPE / 4, VK_IMAGE_TYPE_2D);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_FORMAT / 4, VK_FORMAT_R32G32B32A32_SFLOAT);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_EXTENT_WIDTH / 4, width);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_EXTENT_HEIGHT / 4, height);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_EXTENT_DEPTH / 4, 1);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_MIP_LEVELS / 4, mipLevels);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_ARRAY_LAYERS / 4, 1);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_SAMPLES / 4, VK_SAMPLE_COUNT_1_BIT);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_TILING / 4, VK_IMAGE_TILING_OPTIMAL);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_USAGE / 4,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_SHARING_MODE / 4, VK_SHARING_MODE_EXCLUSIVE);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_QUEUE_FAMILY_INDEX_COUNT / 4, 0);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_ICI_P_QUEUE_FAMILY_INDICES / 8, 0L);
                imageCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_ICI_INITIAL_LAYOUT / 4, VK_IMAGE_LAYOUT_UNDEFINED);

                MemorySegment pImage = arena.allocate(ValueLayout.JAVA_LONG);
                int imageResult = (int) vkCreateImage.invokeExact(vkDevice,
                    imageCreateInfo.address(), 0L, pImage.address());
                if (imageResult != 0) {
                    throw new RuntimeException("vkCreateImage 失败，VkResult=" + imageResult);
                }
                this.hiZImageHandle = pImage.getAtIndex(ValueLayout.JAVA_LONG, 0);
                LOGGER.fine("✓ Hi-Z Image 创建成功: 0x" + Long.toHexString(this.hiZImageHandle));

                // ==================== 3. 获取内存需求并分配显存 ====================
                MemorySegment memReqs = arena.allocate(SZ_MEMORY_REQUIREMENTS);
                vkGetImageMemoryRequirements.invokeExact(vkDevice, this.hiZImageHandle, memReqs.address());
                long imageMemSize = memReqs.getAtIndex(ValueLayout.JAVA_LONG, OFF_MEMREQ_SIZE / 8);
                int memoryTypeBits = memReqs.getAtIndex(ValueLayout.JAVA_INT, (int)(OFF_MEMREQ_MEMORY_TYPE_BITS / 4));
                int imageMemoryTypeIndex = Integer.numberOfTrailingZeros(memoryTypeBits);

                MemorySegment allocInfo = arena.allocate(SZ_MEMORY_ALLOCATE_INFO);
                allocInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_MAI_STYPE / 4, VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
                allocInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_MAI_PNEXT / 8, 0L);
                allocInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_MAI_ALLOCATION_SIZE / 8, imageMemSize);
                allocInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_MAI_MEMORY_TYPE_INDEX / 4, imageMemoryTypeIndex);

                MemorySegment pMemory = arena.allocate(ValueLayout.JAVA_LONG);
                int allocResult = (int) vkAllocateMemory.invokeExact(vkDevice, allocInfo.address(), 0L, pMemory.address());
                if (allocResult != 0) {
                    throw new RuntimeException("vkAllocateMemory (Image) 失败，VkResult=" + allocResult);
                }
                this.hiZDeviceMemory = pMemory.getAtIndex(ValueLayout.JAVA_LONG, 0);

                int bindResult = (int) vkBindImageMemory.invokeExact(vkDevice,
                    this.hiZImageHandle, this.hiZDeviceMemory, 0L);
                if (bindResult != 0) {
                    throw new RuntimeException("vkBindImageMemory 失败，VkResult=" + bindResult);
                }
                LOGGER.fine("✓ Image Memory 绑定成功: size=" + imageMemSize + " bytes");

                // ==================== 4. 创建 Storage ImageViews ====================
                for (int i = 0; i < mipLevels; i++) {
                    long viewHandle = createImageView(vkDevice, arena, vkCreateImageView,
                        this.hiZImageHandle, VK_IMAGE_VIEW_TYPE_2D, VK_FORMAT_R32G32B32A32_SFLOAT,
                        VK_IMAGE_ASPECT_COLOR_BIT, i, 1);
                    this.hiZMipmapImageViews[i] = viewHandle;
                }

                // ==================== 5. 创建 Sampler ImageViews ====================
                for (int i = 0; i < mipLevels; i++) {
                    long viewHandle = createImageView(vkDevice, arena, vkCreateImageView,
                        this.hiZImageHandle, VK_IMAGE_VIEW_TYPE_2D, VK_FORMAT_R32G32B32A32_SFLOAT,
                        VK_IMAGE_ASPECT_COLOR_BIT, i, 1);
                    this.hiZMipmapSamplerViews[i] = viewHandle;
                }
                LOGGER.fine("✓ ImageViews 创建完成: " + mipLevels + " storage + " + mipLevels + " sampler");

                // ==================== 6. 创建 Config UBO Buffer ====================
                MemorySegment bufferCreateInfo = arena.allocate(SZ_BUFFER_CREATE_INFO);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_BCI_STYPE / 4, VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_BCI_PNEXT / 8, 0L);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_BCI_FLAGS / 4, 0);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_BCI_SIZE / 8, CONFIG_UBO_SIZE);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_BCI_USAGE / 4, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_BCI_SHARING_MODE / 4, VK_SHARING_MODE_EXCLUSIVE);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_BCI_QUEUE_FAMILY_INDEX_COUNT / 4, 0);
                bufferCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_BCI_P_QUEUE_FAMILY_INDICES / 8, 0L);

                MemorySegment pBuffer = arena.allocate(ValueLayout.JAVA_LONG);
                int bufferResult = (int) vkCreateBuffer.invokeExact(vkDevice, bufferCreateInfo.address(), 0L, pBuffer.address());
                if (bufferResult != 0) {
                    throw new RuntimeException("vkCreateBuffer 失败，VkResult=" + bufferResult);
                }
                this.configBufferHandle = pBuffer.getAtIndex(ValueLayout.JAVA_LONG, 0);

                // ==================== 7. 分配 + 绑定 Buffer Memory ====================
                MemorySegment bufferMemReqs = arena.allocate(SZ_MEMORY_REQUIREMENTS);
                vkGetBufferMemoryRequirements.invokeExact(vkDevice, this.configBufferHandle, bufferMemReqs.address());
                long bufferMemSize = bufferMemReqs.getAtIndex(ValueLayout.JAVA_LONG, OFF_MEMREQ_SIZE / 8);
                int bufferMemTypeBits = bufferMemReqs.getAtIndex(ValueLayout.JAVA_INT, (int)(OFF_MEMREQ_MEMORY_TYPE_BITS / 4));
                int bufferMemoryTypeIndex = Integer.numberOfTrailingZeros(bufferMemTypeBits);

                MemorySegment bufferAllocInfo = arena.allocate(SZ_MEMORY_ALLOCATE_INFO);
                bufferAllocInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_MAI_STYPE / 4, VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
                bufferAllocInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_MAI_PNEXT / 8, 0L);
                bufferAllocInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_MAI_ALLOCATION_SIZE / 8, bufferMemSize);
                bufferAllocInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_MAI_MEMORY_TYPE_INDEX / 4, bufferMemoryTypeIndex);

                MemorySegment pBufferMemory = arena.allocate(ValueLayout.JAVA_LONG);
                int bufferAllocResult = (int) vkAllocateMemory.invokeExact(vkDevice, bufferAllocInfo.address(), 0L, pBufferMemory.address());
                if (bufferAllocResult != 0) {
                    throw new RuntimeException("vkAllocateMemory (Buffer) 失败，VkResult=" + bufferAllocResult);
                }
                this.configBufferMemory = pBufferMemory.getAtIndex(ValueLayout.JAVA_LONG, 0);

                int bindBufferResult = (int) vkBindBufferMemory.invokeExact(vkDevice,
                    this.configBufferHandle, this.configBufferMemory, 0L);
                if (bindBufferResult != 0) {
                    throw new RuntimeException("vkBindBufferMemory 失败，VkResult=" + bindBufferResult);
                }
                this.configBufferView = this.configBufferHandle;
                LOGGER.fine("✓ Config UBO Buffer 创建完成: size=" + CONFIG_UBO_SIZE + " bytes");
            }

            this.initialized = true;

            LOGGER.info(String.format(
                "HiZBufferManager 初始化完成 [分辨率=%dx%d | MipLevels=%d | " +
                "Image=0x%X | Sampler=0x%X | UBO=0x%X]",
                width, height, this.currentMipLevels,
                this.hiZImageHandle, this.hiZSampler, this.configBufferHandle));

        } catch (Throwable t) {
            LOGGER.severe("HiZBufferManager 初始化失败: " + t.getMessage());
            cleanupPartialResources();
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new RuntimeException("Hi-Z 资源创建失败", t);
        }
    }

    /**
     * 辅助方法：创建 VkImageView
     *
     * @param vkDevice      VkDevice 句柄
     * @param arena         Arena 内存分配器
     * @param vkCreateImageView MH vkCreateImageView
     * @param image         VkImage 句柄
     * @param viewType      VkImageViewType (VK_IMAGE_VIEW_TYPE_2D = 1)
     * @param format        VkFormat
     * @param aspectMask    VkImageAspectFlags
     * @param baseMipLevel  基础 Mip 层级
     * @param levelCount    Mip 层级数量
     * @return VkImageView 句柄
     */
    private long createImageView(long vkDevice, Arena arena, MethodHandle vkCreateImageView,
                                  long image, int viewType, int format, int aspectMask,
                                  int baseMipLevel, int levelCount) throws Throwable {
        MemorySegment viewCreateInfo = arena.allocate(SZ_IMAGE_VIEW_CREATE_INFO);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_STYPE / 4, VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_IVCI_PNEXT / 8, 0L);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_FLAGS / 4, 0);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_LONG, OFF_IVCI_IMAGE / 8, image);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_VIEW_TYPE / 4, viewType);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_FORMAT / 4, format);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_COMPONENTS_R / 4, VK_COMPONENT_SWIZZLE_IDENTITY);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_COMPONENTS_G / 4, VK_COMPONENT_SWIZZLE_IDENTITY);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_COMPONENTS_B / 4, VK_COMPONENT_SWIZZLE_IDENTITY);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_COMPONENTS_A / 4, VK_COMPONENT_SWIZZLE_IDENTITY);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_SUBRESOURCE_ASPECT_MASK / 4, aspectMask);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_SUBRESOURCE_BASE_MIP / 4, baseMipLevel);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_SUBRESOURCE_LEVEL_COUNT / 4, levelCount);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_SUBRESOURCE_BASE_ARRAY / 4, 0);
        viewCreateInfo.setAtIndex(ValueLayout.JAVA_INT, OFF_IVCI_SUBRESOURCE_LAYER_COUNT / 4, 1);

        MemorySegment pView = arena.allocate(ValueLayout.JAVA_LONG);
        int result = (int) vkCreateImageView.invokeExact(vkDevice, viewCreateInfo.address(), 0L, pView.address());
        if (result != 0) {
            throw new RuntimeException("vkCreateImageView 失败，VkResult=" + result);
        }
        return pView.getAtIndex(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * 调整 Hi-Z 纹理尺寸
     * <p>
     * 当屏幕分辨率变化时（如窗口调整、全屏切换），需要重建 Hi-Z 纹理。
     * 此方法会：
     * <ol>
     *   <li>释放现有资源（如果已初始化）</li>
     *   <li>使用新尺寸重新创建所有资源</li>
     * </ol>
     *
     * <h3>调用时机：</h3>
     * <pre>
     * 在以下情况调用：
     * 1. 窗口大小改变事件（Minecraft window resized）
     * 2. 全屏/窗口模式切换
     * 3. DPI 缩放变化
     * 4. 超分辨率渲染分辨率改变
     * </pre>
     *
     * 【方法参数】
     * @param newWidth  int - 新的 Hi-Z 纹理宽度（像素，必须 > 0）
     * @param newHeight int - 新的 Hi-Z 纹理高度（像素，必须 > 0）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 如果 newWidth <= 0 或 newHeight <= 0，抛出 IllegalArgumentException
     * - 如果尺寸未变化，跳过重建（性能优化）
     * - 如果重建失败，恢复到未初始化状态并抛出异常
     *
     * 【性能特征】
     * - 销毁旧资源: ~1ms
     * - 创建新资源: ~1~5ms
     * - 总耗时: ~2~10ms（取决于 GPU）
     * - 如果尺寸未变化: &lt;0.01ms（提前返回）
     *
     * 【注意事项】
     * - 此操作会短暂导致 Hi-Z 不可用（在销毁和重建之间）
     * - 调用者应确保不在 resize 期间提交使用 Hi-Z 的 Compute Shader
     * - 推荐在帧边界（frame boundary）调用
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException(
                "新尺寸必须为正整数，当前值: width=" + newWidth + ", height=" + newHeight);
        }

        if (newWidth == this.currentWidth && newHeight == this.currentHeight) {
            LOGGER.fine(String.format("HiZ 尺寸未变化 (%dx%d)，跳过 resize", newWidth, newHeight));
            return;
        }

        LOGGER.info(String.format("HiZBufferManager 正在调整尺寸: %dx%d → %dx%d",
            this.currentWidth, this.currentHeight, newWidth, newHeight));

        if (this.initialized) {
            disposeInternal();
        }

        initialize(newWidth, newHeight);
    }

    /**
     * 释放所有 Vulkan 资源
     * <p>
     * 按照 Vulkan 规范推荐的顺序销毁资源：
     * <ol>
     *   <li>销毁所有 ImageView（Mipmap Storage Views + Mipmap Sampler Views + Depth Buffer View）</li>
     *   <li>销毁 UBO Buffer</li>
     *   <li>销毁 Hi-Z Image</li>
     *   <li>释放 Device Memory（如果单独分配）</li>
     * </ol>
     *
     * <h3>Vulkan 销毁顺序要求：</h3>
     * <pre>
     * 必须在 Image 仍在有效状态下销毁 ImageView
     * 必须在 Buffer 仍在有效状态下销毁 BufferView
     * Memory 可在任何时候释放（但推荐最后释放）
     * </pre>
     *
     * 【返回值】void
     *
     * 【前置条件】
     * - 无（可安全多次调用，幂等性）
     *
     * 【后置条件】
     * - initialized == false
     * - 所有句柄重置为 0L
     * - currentWidth / currentHeight 重置为 0
     *
     * 【线程安全性】
     * 此方法非线程安全，需外部保证：
     * - 无正在执行的 GPU 命令使用这些资源
     * - GPU 已完成所有引用这些资源的命令（fence 或 pipeline barrier）
     *
     * 【性能特征】
     * - Vulkan API 调用: ~10~20 次 vkDestroy* 调用
     * - CPU 耗时: ~0.1~1ms
     * - GPU 显存释放可能延迟（依赖驱动实现）
     *
     * 【异常处理】
     * - 捕获所有异常确保部分资源也能被清理
     * - 记录每个失败的销毁操作
     */
    public void dispose() {
        if (!this.initialized) {
            LOGGER.fine("HiZBufferManager 尚未初始化，跳过 dispose");
            return;
        }

        LOGGER.info("HiZBufferManager 正在释放资源...");
        disposeInternal();
        LOGGER.info("HiZBufferManager 资源已释放");
    }

    // ==================== Getter 方法（句柄查询）====================

    /**
     * 获取深度缓冲输入纹理的 ImageView 句柄
     * <p>
     * 此纹理来自 Opaque Pass 的深度输出，作为 Hi-Z 构建的输入源。
     *
     * 【返回值】
     * @return long - 深度缓冲 VkImageView 原生句柄（LWJGL address）
     *               <ul>
     *                 <li>&gt; 0: 有效句柄，可用于 DescriptorSet 绑定</li>
     *                 <li>= 0: 未初始化或无效</li>
     *               </ul>
     *
     * 【使用场景】
     * - 在 hiz_build.comp 中作为输入 Storage Image 绑定
     * - DescriptorType: VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
     * - 通常 Binding: 0 或 1（取决于 shader layout）
     *
     * 【线程安全性】
     * volatile 读，无锁，线程安全
     *
     * 【性能特征】
     * O(1) 操作，单次 volatile 字段读取
     */
    public long getDepthBufferView() {
        return this.depthBufferView;
    }

    /**
     * 获取 Hi-Z 各层级 Storage Image 的 ImageView 句柄数组
     * <p>
     * 返回的数组长度固定为 {@value #HIZ_MAX_MIP_LEVELS} (10)。
     * 每个元素对应一层 Mipmap 的 ImageView，用于 Compute Shader 写入。
     *
     * <h3>数组语义：</h3>
     * <pre>
     * 返回值[0] → Level 0 (最高分辨率, 如 2048x2048)
     * 返回值[1] → Level 1 (1/2 分辨率, 如 1024x1024)
     * 返回值[2] → Level 2 (1/4 分辨率, 如 512x512)
     * ...
     * 返回值[9] → Level 9 (最低分辨率, 如 4x4)
     * </pre>
     *
     * 【返回值】
     * @return long[10] - Hi-Z 各层级 VkImageView 句柄数组
     *                    <ul>
     *                      <li>数组长度恒定为 HIZ_MAX_MIP_LEVELS</li>
     *                      <li>元素 &gt; 0: 有效句柄</li>
     *                      <li>元素 = 0: 该层级未初始化或无效</li>
     *                    </ul>
     *
     * 【使用场景】
     * - 在 hiz_build.comp 中写入各层级 Hi-Z 数据
     * - DescriptorType: VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
     * - 可绑定为 Array Descriptor 或多个独立 Descriptor
     *
     * 【线程安全性】
     * 返回的是 volatile 数组引用的快照（Java 内存模型保证数组引用的可见性）。
     * 但数组内容的修改需要外部同步。
     * 推荐用法：获取后立即使用，不长期持有引用。
     *
     * 【性能特征】
     * O(1) 操作，单次 volatile 引用读取
     *
     * 【注意事项】
     * - 返回的是内部数组的直接引用（非拷贝），调用者不应修改
     * - 如果需要长期持有，请手动拷贝: Arrays.copyOf(views, views.length)
     */
    public long[] getHiZMipmapImageViews() {
        return this.hiZMipmapImageViews;
    }

    /**
     * 获取 Hi-Z 各层级 Sampled Image 的 ImageView 句柄数组
     * <p>
     * 与 {@link #getHiZMipmapImageViews()} 一一对应，但用途不同：
     * 用于遮挡剔除 Shader 中的纹理采样（只读）。
     *
     * <h3>Shader 中的使用方式：</h3>
     * <pre>
     * // GLSL (hiz_occlusion_culling.comp)
     * layout (set = 0, binding = 2) uniform sampler2D uHiZMap[HIZ_MAX_MIP_LEVELS];
     *
     * // 根据物体屏幕占用选择合适的 LOD 层级
     * float visibility = sampleHiZ(uHiZMap[lodLevel], screenPos);
     * </pre>
     *
     * 【返回值】
     * @return long[10] - Hi-Z 各层级 Sampled VkImageView 句柄数组
     *                    <ul>
     *                      <li>数组长度恒定为 HIZ_MAX_MIP_LEVELS</li>
     *                      <li>元素 &gt; 0: 有效句柄</li>
     *                      <li>元素 = 0: 该层级未初始化或无效</li>
     *                    </ul>
     *
     * 【使用场景】
     * - 在 hiz_occlusion_culling.comp 中读取 Hi-Z 数据进行遮挡测试
     * - DescriptorType: VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
     * - 通常 Binding: 2 或更高（取决于 shader layout）
     * - 需要 Sampler 对象配合（可复用线性采样器）
     *
     * 【线程安全性】
     * 同 {@link #getHiZMipmapImageViews()}
     *
     * 【性能特征】
     * O(1) 操作，单次 volatile 引用读取
     */
    public long[] getHiZMipmapSamplerViews() {
        return this.hiZMipmapSamplerViews;
    }

    /**
     * 获取配置 UBO 的 Buffer View 句柄
     * <p>
     * 返回存储 {@link OcclusionCullConfig} 数据的 GPU 缓冲区句柄。
     * 此缓冲区包含 std140 布局的相机参数和 Hi-Z 配置信息。
     *
     * <h3>UBO 内容结构：</h3>
     * <pre>
     * Offset  Field                  Size    Type
     * ──────  ────────────────────   ────    ─────────
     * 0       viewProjMatrix         64B     mat4
     * 64      viewMatrixInverse      64B     mat4
     * 128     projectionParams       16B     vec4
     * 144     screenSize             8B      uvec2
     * 156     hiZSize                8B      uvec2
     * 164     enableHiZCull          4B      uint
     * 168     totalChunkCount        4B      uint
     * 172     maxHiZLOD              4B      uint
     * 176     padding                4B      uint
     * ──────────────────────────────────────────────
     * Total: 192 bytes (std140 aligned)
     * </pre>
     *
     * 【返回值】
     * @return long - 配置 UBO 的 VkBuffer 句柄（或 Buffer View 偏移量）
     *               <ul>
     *                 <li>&gt; 0: 有效句柄，可用于 vkCmdBindDescriptorSets</li>
     *                 <li>= 0: 未初始化或无效</li>
     *               </ul>
     *
     * 【使用场景】
     * - 在 hiz_occlusion_culling.comp 中作为 Uniform Buffer 读取
     * - DescriptorType: VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
     * - Binding: 5（与 GLSL layout (std140, binding = 5) 一致）
     * - 每帧更新: 通过 vkCmdUpdateBuffer 或映射 Host Visible 内存
     *
     * 【数据上传流程】
     * <pre>
     * 1. 构建 OcclusionCullConfig 对象
     * 2. 调用 config.toMemorySegment(arena) 序列化
     * 3. 将 segment 内容复制到本 buffer（vkCmdUpdateBuffer 或 memcpy）
     * 4. 提交 Compute Pipeline（Shader 自动读取最新数据）
     * </pre>
     *
     * 【线程安全性】
     * volatile 读，无锁，线程安全
     *
     * 【性能特征】
     * O(1) 操作，单次 volatile 字段读取
     */
    public long getConfigBufferView() {
        return this.configBufferView;
    }

    /**
     * 检查是否已完成初始化
     * <p>
     * 用于在调用 Getter 前进行快速检查，避免使用未初始化的句柄。
     *
     * 【返回值】
     * @return boolean - true 表示已成功初始化且所有资源可用
     *                   false 表示尚未初始化、正在初始化中、或已 dispose
     *
     * 【使用场景】
     * <pre>
     * HiZBufferManager manager = ...;
     * if (manager.isInitialized()) {
     *     long[] views = manager.getHiZMipmapImageViews();
     *     // 安全地使用 views...
     * }
     * </pre>
     *
     * 【线程安全性】
     * volatile 读，无锁，线程安全
     *
     * 【性能特征】
     * O(1) 操作，单次 volatile 布尔读取
     */
    public boolean isInitialized() {
        return this.initialized;
    }

    /**
     * 获取当前 Hi-Z 纹理宽度
     *
     * 【返回值】
     * @return int - 当前 Hi-Z Level 0 宽度（像素），未初始化时返回 0
     */
    public int getCurrentWidth() { return this.currentWidth; }

    /**
     * 获取当前 Hi-Z 纹理高度
     *
     * 【返回值】
     * @return int - 当前 Hi-Z Level 0 高度（像素），未初始化时返回 0
     */
    public int getCurrentHeight() { return this.currentHeight; }

    /**
     * 获取当前实际 Mipmap 层级数量
     * <p>
     * 根据创建时的 width/height 动态计算，不超过 HIZ_MAX_MIP_LEVELS。
     *
     * @return int - 当前 Mipmap 层数（1 ~ HIZ_MAX_MIP_LEVELS）
     */
    public int getCurrentMipLevels() { return this.currentMipLevels; }

    /**
     * 获取 Hi-Z Sampler 句柄
     * <p>
     * 使用 VK_FILTER_NEAREST + VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE 创建，
     * 用于 Hi-Z 纹理采样时保证点采样和边缘钳制。
     *
     * @return long - VkSampler 句柄，未初始化时返回 0L
     */
    public long getHiZSampler() { return this.hiZSampler; }

    // ==================== 内部句柄设置方法（供 LodCullingComputePass 调用）====================

    /**
     * 设置深度缓冲 ImageView 句柄（内部使用）
     * <p>
     * 由 {@code LodCullingComputePass} 在创建资源后调用，
     * 将外部创建的深度缓冲 ImageView 句柄注册到此管理器。
     *
     * @param view VkImageView 句柄
     */
    public void setDepthBufferView(long view) {
        this.depthBufferView = view;
    }

    /**
     * 设置指定层级的 Hi-Z Mipmap ImageView 句柄（内部使用）
     *
     * @param level Mipmap 层级 (0 ~ HIZ_MAX_MIP_LEVELS-1)
     * @param view  VkImageView 句柄
     * @throws IndexOutOfBoundsException 如果 level 超出范围
     */
    public void setHiZMipmapImageView(int level, long view) {
        if (level < 0 || level >= HIZ_MAX_MIP_LEVELS) {
            throw new IndexOutOfBoundsException(
                "HiZ Mipmap level 必须在 0 ~ " + (HIZ_MAX_MIP_LEVELS - 1) + " 范围内，当前值: " + level);
        }
        this.hiZMipmapImageViews[level] = view;
    }

    /**
     * 设置指定层级的 Hi-Z Sampler ImageView 句柄（内部使用）
     *
     * @param level Mipmap 层级 (0 ~ HIZ_MAX_MIP_LEVELS-1)
     * @param view  VkImageView 句柄
     * @throws IndexOutOfBoundsException 如果 level 超出范围
     */
    public void setHiZMipmapSamplerView(int level, long view) {
        if (level < 0 || level >= HIZ_MAX_MIP_LEVELS) {
            throw new IndexOutOfBoundsException(
                "HiZ Mipmap level 必须在 0 ~ " + (HIZ_MAX_MIP_LEVELS - 1) + " 范围内，当前值: " + level);
        }
        this.hiZMipmapSamplerViews[level] = view;
    }

    /**
     * 设置配置 UBO Buffer View 句柄（内部使用）
     *
     * @param bufferView VkBuffer 句柄
     */
    public void setConfigBufferView(long bufferView) {
        this.configBufferView = bufferView;
    }

    /**
     * 设置 Hi-Z Image 句柄（内部使用，用于 dispose 时销毁）
     *
     * @param image VkImage 句柄
     */
    public void setHiZImageHandle(long image) {
        this.hiZImageHandle = image;
    }

    /**
     * 设置 Hi-Z DeviceMemory 句柄（内部使用，用于 dispose 时释放）
     *
     * @param memory VkDeviceMemory 句柄
     */
    public void setHiZDeviceMemory(long memory) {
        this.hiZDeviceMemory = memory;
    }

    /**
     * 设置配置 UBO Buffer 句柄（内部使用，用于 dispose 时销毁）
     *
     * @param buffer VkBuffer 句柄
     */
    public void setConfigBufferHandle(long buffer) {
        this.configBufferHandle = buffer;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 内部 dispose 实现（不含日志和状态检查）
     * <p>
     * 按 Vulkan 规范顺序销毁所有资源。
     * 即使中间步骤失败也继续尝试清理剩余资源。
     */
    private void disposeInternal() {
        long vkDevice = deviceHolder.getVkDeviceHandle();
        if (vkDevice == 0L) return;

        // 在本地变量中缓存 MethodHandle 避免重复查找
        MethodHandle vkDestroyImageView = VulkanFFMBinding.getVkDestroyImageView();
        MethodHandle vkDestroySampler = VulkanFFMBinding.getVkDestroySampler();
        MethodHandle vkFreeMemory = VulkanFFMBinding.getVkFreeMemory();
        MethodHandle vkDestroyBuffer = VulkanFFMBinding.getVkDestroyBuffer();
        MethodHandle vkDestroyImage = VulkanFFMBinding.getVkDestroyImage();

        // 1. 销毁 Mipmap ImageViews（当前实际层数）
        for (int i = 0; i < this.currentMipLevels; i++) {
            try {
                if (this.hiZMipmapImageViews[i] != 0L) {
                    vkDestroyImageView.invokeExact(vkDevice, this.hiZMipmapImageViews[i], 0L);
                    this.hiZMipmapImageViews[i] = 0L;
                }
            } catch (Throwable t) {
                LOGGER.finest("销毁 Storage ImageView[%d] 时忽略异常: %s".formatted(i, t.getMessage()));
            }
            try {
                if (this.hiZMipmapSamplerViews[i] != 0L) {
                    vkDestroyImageView.invokeExact(vkDevice, this.hiZMipmapSamplerViews[i], 0L);
                    this.hiZMipmapSamplerViews[i] = 0L;
                }
            } catch (Throwable t) {
                LOGGER.finest("销毁 Sampler ImageView[%d] 时忽略异常: %s".formatted(i, t.getMessage()));
            }
        }

        // 2. 销毁 Sampler
        try {
            if (this.hiZSampler != 0L) {
                vkDestroySampler.invokeExact(vkDevice, this.hiZSampler, 0L);
                this.hiZSampler = 0L;
            }
        } catch (Throwable t) {
            LOGGER.finest("销毁 Sampler 时忽略异常: " + t.getMessage());
        }

        // 3. 销毁 UBO Buffer + 显存
        try {
            if (this.configBufferHandle != 0L) {
                if (this.configBufferMemory != 0L) {
                    vkFreeMemory.invokeExact(vkDevice, this.configBufferMemory, 0L);
                    this.configBufferMemory = 0L;
                }
                vkDestroyBuffer.invokeExact(vkDevice, this.configBufferHandle, 0L);
                this.configBufferHandle = 0L;
            }
        } catch (Throwable t) {
            LOGGER.finest("销毁 UBO Buffer 时忽略异常: " + t.getMessage());
        }
        this.configBufferView = 0L;

        // 4. 销毁 Hi-Z Image + 显存
        try {
            if (this.hiZDeviceMemory != 0L) {
                vkFreeMemory.invokeExact(vkDevice, this.hiZDeviceMemory, 0L);
                this.hiZDeviceMemory = 0L;
            }
        } catch (Throwable t) {
            LOGGER.finest("释放 Hi-Z Memory 时忽略异常: " + t.getMessage());
        }

        try {
            if (this.hiZImageHandle != 0L) {
                vkDestroyImage.invokeExact(vkDevice, this.hiZImageHandle, 0L);
                this.hiZImageHandle = 0L;
            }
        } catch (Throwable t) {
            LOGGER.finest("销毁 Hi-Z Image 时忽略异常: " + t.getMessage());
        }

        this.currentWidth = 0;
        this.currentHeight = 0;
        this.currentMipLevels = 0;
        this.initialized = false;
    }

    /**
     * 清理部分创建的资源（initialize 失败时调用）
     * <p>
     * 尝试释放任何已成功创建的资源，
     * 确保不会泄漏 GPU 显存。
     */
    private void cleanupPartialResources() {
        LOGGER.warning("正在清理部分创建的 Hi-Z 资源...");
        disposeInternal();
    }

    @Override
    public String toString() {
        return String.format(
            "HiZBufferManager[initialized=%b, size=%dx%d, mipLevels=%d, sampler=0x%016X, image=0x%016X, ubo=0x%016X]",
            this.initialized,
            this.currentWidth, this.currentHeight,
            this.currentMipLevels,
            this.hiZSampler,
            this.hiZImageHandle,
            this.configBufferHandle
        );
    }
}
