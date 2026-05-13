package com.ranecc.renderium.infrastructure.vulkan.adapter;

import java.util.EnumSet;
import java.util.Set;

/**
 * VulkanFeature - Vulkan 可选功能特性标志 (26.2-snapshot-3 兼容)
 *
 * <p>枚举 Vulkan 可选扩展和核心特性，用于运行时检测设备能力，
 * 并据此调整渲染策略。</p>
 *
 * <h3>设计目的:</h3>
 * <ul>
 *   <li>提供类型安全的特性检测机制</li>
 *   <li>支持基于设备能力的条件渲染路径选择</li>
 *   <li>便于扩展新的 Vulkan 特性支持</li>
 * </ul>
 *
 * <h3>使用示例:</h3>
 * <pre>{@code
 * // 检测设备支持的特性
 * Set<String> extensions = getDeviceExtensions();
 * Set<VulkanFeature> supported = VulkanFeature.detectSupported(extensions);
 *
 * // 条件判断是否支持动态渲染
 * if (supported.contains(VulkanFeature.DYNAMIC_RENDERING)) {
 *     // 使用动态渲染路径
 * } else {
 *     // 回退到传统 RenderPass 路径
 * }
 * }</pre>
 */
public enum VulkanFeature {

    /**
     * VK_KHR_dynamic_rendering - 动态渲染（无 RenderPass 对象）
     *
     * <p>Vulkan 1.2+ 的 KHR 扩展，允许在不创建 RenderPass 对象的情况下进行渲染。
     * 显著简化渲染管线的创建和管理，减少 API 开销。</p>
     *
     * <h4>优势:</h4>
     * <ul>
     *   <li>消除 RenderPass 对象创建开销</li>
     *   <li>更灵活的渲染流程控制</li>
     *   <li>与现代图形 API（DirectX 12、Metal）设计理念一致</li>
     * </ul>
     */
    DYNAMIC_RENDERING("VK_KHR_dynamic_rendering"),

    /**
     * VK_KHR_push_descriptor - 推送描述符优化
     *
     * <p>允许直接将描述符数据推送到命令缓冲区，避免频繁更新描述符集合。
     * 对于每帧更新的数据（如变换矩阵、材质参数）特别有效。</p>
     *
     * <h4>适用场景:</h4>
     * <ul>
     *   <li>频繁更新的 Uniform 数据</li>
     *   <li>减少描述符集合切换开销</li>
     *   <li>提高 CPU 端性能</li>
     * </ul>
     */
    PUSH_DESCRIPTOR("VK_KHR_push_descriptor"),

    /**
     * VK_KHR_synchronization2 - 增强同步原语
     *
     * <p>提供了更强大和灵活的同步机制，改进了屏障(barrier)和依赖关系的表达方式。
     * 是 Vulkan 1.2 的核心特性之一。</p>
     *
     * <h4>新功能:</h4>
     * <ul>
     *   <li>更精确的内存访问控制</li>
     *   <li>增强的图像布局转换</li>
     *   <li>改进的管线阶段依赖声明</li>
     * </ul>
     */
    SYNCHRONIZATION_2("VK_KHR_synchronization2"),

    /**
     * VK_KHR_timeline_semaphore - 时间线信号量
     *
     * <p>引入时间线信号量概念，支持细粒度的异步操作协调。
     * 允许 CPU 和 GPU 更高效地并行工作。</p>
     *
     * <h4>应用场景:</h4>
     * <ul>
     *   <li>异步计算队列同步</li>
     *   <li>多帧流水线重叠</li>
     *   <li>低延迟渲染架构</li>
     * </ul>
     */
    TIMELINE_SEMAPHORE("VK_KHR_timeline_semaphore"),

    /**
     * VK_KHR_swapchain - 交换链支持
     *
     * <p>窗口系统集成的必要扩展，提供与显示器交换渲染结果的机制。
     * 几乎所有图形应用都需要此扩展。</p>
     *
     * <h4>核心功能:</h4>
     * <ul>
     *   <li>Surface 创建与管理</li>
     *   <li>SwapChain 图像获取与呈现</li>
     *   <li>垂直同步与显示模式控制</li>
     *   <li>全屏独占模式支持</li>
     * </ul>
     */
    SWAPCHAIN("VK_KHR_swapchain"),

    /**
     * VK_KHR_shader_float16 - 16 位浮点着色器
     *
     * <p>允许着色器使用 16 位半精度浮点数运算。
     * 可以显著提高 GPU 吞吐量和减少带宽占用。</p>
     *
     * <h4>性能收益:</h4>
     * <ul>
     *   <li>在某些 GPU 上吞吐量翻倍</li>
     *   <li>减少寄存器压力</li>
     *   <li>降低显存带宽消耗</li>
     * </ul>
     */
    SHADER_FLOAT16("VK_KHR_shader_float16"),

    /**
     * VK_EXT_descriptor_indexing - 描述符索引
     *
     * <p>增强了描述符集合的功能，支持部分绑定、可变数量描述符等高级特性。
     * 对于实现纹理数组、延迟绑定等技术至关重要。</p>
     *
     * <h4>关键特性:</h4>
     * <ul>
     *   <li>运行时描述符数组索引</li>
     *   <li>非均匀索引支持</li>
     *   <li>描述符集合的部分更新</li>
     *   <li>可变数量的描述符绑定</li>
     * </ul>
     */
    DESCRIPTOR_INDEXING("VK_EXT_descriptor_indexing"),

    /**
     * VK_KHR_buffer_device_address - 缓冲区设备地址
     *
     * <p>允许在着色器中直接使用缓冲区的 GPU 地址（指针）。
     * 为物理正确的光线追踪和高级数据结构提供基础支持。</p>
     *
     * <h4>应用场景:</h4>
     * <ul>
     *   <li>光线追踪加速结构遍历</li>
     *   <li>GPU 端数据结构（BVH、哈希表等）</li>
     *   <li>SSBO 的高效随机访问</li>
     *   <li>存储描述符的替代方案</li>
     * </ul>
     */
    BUFFER_DEVICE_ADDRESS("VK_KHR_buffer_device_address");

    /** 对应的 Vulkan 扩展名称 */
    private final String extensionName;

    /**
     * 构造函数
     *
     * @param extensionName Vulkan 扩展字符串标识符
     */
    VulkanFeature(String extensionName) {
        this.extensionName = extensionName;
    }

    /**
     * 获取对应的 Vulkan 扩展名称
     *
     * @return Vulkan 扩展字符串（如 "VK_KHR_swapchain"）
     */
    public String getExtensionName() {
        return extensionName;
    }

    /**
     * 从扩展名集合检测支持的功能集
     *
     * <p>遍历所有已知的 Vulkan 特性，检查其扩展名是否存在于可用扩展集合中。
     * 返回所有匹配的特性枚举集合。</p>
     *
     * <h4>算法复杂度:</h4>
     * <ul>
     *   <li>时间复杂度: O(n*m)，其中 n 是特性数量，m 是扩展集合查找时间</li>
     *   <li>空间复杂度: O(k)，k 是支持的特性数量</li>
     * </ul>
     *
     * @param availableExtensions 设备支持的扩展名集合（通常从 vkEnumerateDeviceExtensionProperties 获取）
     * @return 已支持的特性集合（EnumSet），不可变视图
     * @throws IllegalArgumentException 如果 availableExtensions 为 null
     */
    public static Set<VulkanFeature> detectSupported(Set<String> availableExtensions) {
        if (availableExtensions == null) {
            throw new IllegalArgumentException("availableExtensions cannot be null");
        }

        EnumSet<VulkanFeature> supported = EnumSet.noneOf(VulkanFeature.class);

        for (VulkanFeature feature : values()) {
            if (availableExtensions.contains(feature.extensionName)) {
                supported.add(feature);
            }
        }

        // 返回不可修改的集合视图以防止外部修改
        return Set.copyOf(supported);
    }

    /**
     * 检查单个特性是否受支持
     *
     * <p>便捷方法，用于快速检查特定特性的可用性。</p>
     *
     * @param availableExtensions 设备支持的扩展名集合
     * @return true 如果该特性受支持
     * @throws IllegalArgumentException 如果 availableExtensions 为 null
     */
    public boolean isSupported(Set<String> availableExtensions) {
        if (availableExtensions == null) {
            throw new IllegalArgumentException("availableExtensions cannot be null");
        }
        return availableExtensions.contains(this.extensionName);
    }

    /**
     * 根据扩展名查找对应的特性枚举
     *
     * <p>反向查找方法，从扩展名获取特性对象。</p>
     *
     * @param extensionName 要查找的扩展名
     * @return 对应的 VulkanFeature 枚举，如果没有找到则返回 null
     */
    public static VulkanFeature fromExtensionName(String extensionName) {
        if (extensionName == null) {
            return null;
        }

        for (VulkanFeature feature : values()) {
            if (feature.extensionName.equals(extensionName)) {
                return feature;
            }
        }

        return null;
    }

    /**
     * 获取所有必需的核心特性集合
     *
     * <p>返回大多数现代 Vulkan 应用所需要的基本特性集合。
     * 这些特性对于基本的图形渲染是必需的。</p>
     *
     * @return 必需特性集合
     */
    public static Set<VulkanFeature> getRequiredFeatures() {
        return Set.of(SWAPCHAIN);
    }

    /**
     * 获取推荐的优化特性集合
     *
     * <p>返回推荐启用的可选特性，这些特性可以显著提升性能或简化代码。</p>
     *
     * @return 推荐特性集合
     */
    public static Set<VulkanFeature> getRecommendedFeatures() {
        return Set.of(
            DYNAMIC_RENDERING,
            SYNCHRONIZATION_2,
            TIMELINE_SEMAPHORE,
            PUSH_DESCRIPTOR,
            DESCRIPTOR_INDEXING
        );
    }
}
