// Renderium - Vulkan Streamline Bridge (FFM)
// 使用 Java 22+ Panama FFM API 直接调用 Streamline SDK 的 Vulkan 函数
// 不需要 JNI/C++ 桥接层

package com.ranecc.renderium.tech.streamline.streamline;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;

import java.lang.foreign.*;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vulkan Streamline 桥接器 (FFM) 🔧
 *
 * <p>使用 Java 22+ Panama FFM API 直接调用 Streamline SDK 的 Vulkan 相关函数，
 * 将劫持的 VkDevice/VkQueue 句柄传递给 Streamline SDK。
 *
 * <h2>架构设计：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    OfficialVulkanHijacker                  │
 * │  劫持 VkDevice/VkQueue 句柄                               │
 * └────────────────────────┬────────────────────────────────┘
 *                          │
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │              VulkanStreamlineBridge (this)                 │
 * │  1. 构建 sl::VulkanInfo 结构体                            │
 * │  2. 调用 slSetVulkanInfo() 注册 Vulkan 信息               │
 * │  3. 提供 tagResource() 方法标记渲染资源                   │
 * └────────────────────────┬────────────────────────────────┘
 *                          │
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    SLFFMBindings (FFM)                     │
 * │  SymbolLookup → Linker.downcallHandle → invokeExact       │
 * └────────────────────────┬────────────────────────────────┘
 *                          │
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │                  sl.interposer.dll                        │
 * │  Streamline SDK 原生库                                    │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>核心功能：</h3>
 * <ul>
 *   <li><b>setVulkanInfo()</b>: 注册 VkInstance/VkDevice/VkQueue</li>
 *   <li><b>tagResource()</b>: 标记单个资源（颜色/深度/运动向量）</li>
 *   <li><b>tagResources()</b>: 批量标记资源数组</li>
 *   <li><b>isAvailable()</b>: 检查桥接是否可用</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see SLFFMBindings
 * @see com.renderium.graphics.backend.OfficialVulkanHijacker
 */
public class VulkanStreamlineBridge {

    private static final Logger LOGGER = Logger.getLogger(VulkanStreamlineBridge.class.getName());

    // ==================== 状态管理 ====================

    /** 桥接是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** Vulkan 信息是否已注册 */
    private final AtomicBoolean vulkanRegistered = new AtomicBoolean(false);

    /** 单例实例 */
    private static volatile VulkanStreamlineBridge instance;

    /**
     * 私有构造函数（单例模式）
     */
    private VulkanStreamlineBridge() {}

    /**
     * 获取单例实例（双重检查锁定）
     *
     * @return VulkanStreamlineBridge 实例
     */
    public static VulkanStreamlineBridge getInstance() {
        if (instance == null) {
            synchronized (VulkanStreamlineBridge.class) {
                if (instance == null) {
                    instance = new VulkanStreamlineBridge();
                }
            }
        }
        return instance;
    }

    // ==================== 核心功能 ====================

    /**
     * 初始化桥接器
     *
     * @param libraryPath sl.interposer.dll 的完整路径
     * @return 是否初始化成功
     */
    public boolean initialize(String libraryPath) {
        if (initialized.get()) {
            return true;
        }

        try {
            LOGGER.info("正在加载 Streamline SDK: " + libraryPath);
            
            boolean loaded = SLFFMBindings.load(libraryPath);
            if (!loaded) {
                LOGGER.severe("无法加载 Streamline SDK");
                return false;
            }

            initialized.set(true);
            LOGGER.info("Vulkan Streamline Bridge 初始化成功（FFM 模式）");
            return true;

        } catch (Exception e) {
            LOGGER.severe("初始化 Vulkan Streamline Bridge 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 注册 Vulkan 信息到 Streamline SDK
     *
     * <p>对应 C API: sl::Result slSetVulkanInfo(const sl::VulkanInfo& info)
     *
     * <p>VulkanInfo 结构体定义（sl_vulkan_types.h）：
     * <pre>
     * struct VulkanInfo {
     *     VkInstance instance;          // 8 bytes, offset 0
     *     VkPhysicalDevice physicalDevice; // 8 bytes, offset 8
     *     VkDevice device;              // 8 bytes, offset 16
     *     VkQueue computeQueue;         // 8 bytes, offset 24
     *     uint32_t computeQueueIndex;   // 4 bytes, offset 32
     *     uint32_t graphicsQueueIndex;  // 4 bytes, offset 36
     * };
     * </pre>
     *
     * @param vkInstance VkInstance 句柄
     * @param vkPhysicalDevice VkPhysicalDevice 句柄
     * @param vkDevice VkDevice 句柄
     * @param vkComputeQueue VkQueue 计算队列句柄
     * @param computeQueueFamilyIndex 计算队列族索引
     * @param presentQueueFamilyIndex 呈现队列族索引
     * @return 是否成功注册
     */
    public boolean setVulkanInfo(long vkInstance,
                                  long vkPhysicalDevice,
                                  long vkDevice,
                                  long vkComputeQueue,
                                  int computeQueueFamilyIndex,
                                  int presentQueueFamilyIndex) {
        
        if (!initialized.get()) {
            LOGGER.warning("Bridge 未初始化，无法设置 Vulkan 信息");
            return false;
        }

        if (!SLFFMBindings.isLoaded()) {
            LOGGER.warning("Streamline SDK 未加载，无法设置 Vulkan 信息");
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {
            // 构建 sl::VulkanInfo 结构体（40 字节）
            MemorySegment vulkanInfo = arena.allocate(VulkanConst.SL_VULKAN_INFO_SIZE);

            // 填充字段
            vulkanInfo.set(ValueLayout.JAVA_LONG, 
                         VulkanConst.SL_VULKAN_INFO_OFFSET_INSTANCE, vkInstance);
            vulkanInfo.set(ValueLayout.JAVA_LONG, 
                         VulkanConst.SL_VULKAN_INFO_OFFSET_PHYSICAL_DEVICE, vkPhysicalDevice);
            vulkanInfo.set(ValueLayout.JAVA_LONG, 
                         VulkanConst.SL_VULKAN_INFO_OFFSET_DEVICE, vkDevice);
            vulkanInfo.set(ValueLayout.JAVA_LONG, 
                         VulkanConst.SL_VULKAN_INFO_OFFSET_COMPUTE_QUEUE, vkComputeQueue);
            vulkanInfo.set(ValueLayout.JAVA_INT, 
                         VulkanConst.SL_VULKAN_INFO_OFFSET_COMPUTE_QUEUE_INDEX, computeQueueFamilyIndex);
            vulkanInfo.set(ValueLayout.JAVA_INT, 
                         VulkanConst.SL_VULKAN_INFO_OFFSET_GRAPHICS_QUEUE_INDEX, presentQueueFamilyIndex);

            // 调用 slSetVulkanInfo()
            int result = SLFFMBindings.slSetVulkanInfo(vulkanInfo);

            if (SLFFMBindings.isOk(result)) {
                vulkanRegistered.set(true);
                LOGGER.info(String.format(
                    "Vulkan 信息已注册: Instance=0x%s, Device=0%s, ComputeQueue=0%s",
                    Long.toHexString(vkInstance),
                    Long.toHexString(vkDevice),
                    Long.toHexString(vkComputeQueue)));
                return true;
            } else {
                LOGGER.severe("slSetVulkanInfo 失败: " + SLFFMBindings.getResultDescription(result));
                return false;
            }

        } catch (Exception e) {
            LOGGER.severe("设置 Vulkan 信息时发生异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 标记单个资源
     *
     * <p>对应 C API: sl::Result slSetTagForFrame(...)
     *
     * @param bufferType 缓冲区类型（如 BUFFER_TYPE_HUDLESS_COLOR）
     * @param imageView Vulkan ImageView 句柄
     * @param width 纹理宽度
     * @param height 纹理高度
     * @return 是否成功标记
     */
    public boolean tagResource(int bufferType, long imageView, int width, int height) {
        if (!vulkanRegistered.get()) {
            LOGGER.fine("Vulkan 未注册，跳过资源标记");
            return true; // 存根模式下返回成功
        }

        try (Arena arena = Arena.ofConfined()) {
            // 构建 ResourceTag 结构体
            MemorySegment resourceTag = buildResourceTag(arena, bufferType, imageView, width, height);

            // 调用 slSetTagForFrame（使用空帧标记和视口）
            int result = SLFFMBindings.slSetTagForFrame(
                MemorySegment.NULL,  // frame token (null for now)
                MemorySegment.NULL,  // viewport handle (null for now)
                resourceTag,         // resource tags array
                1,                   // num resources
                MemorySegment.NULL   // command buffer (null)
            );

            if (SLFFMBindings.isOk(result)) {
                LOGGER.fine(String.format("资源标记成功: type=%d, view=0x%s", 
                           bufferType, Long.toHexString(imageView)));
                return true;
            } else {
                LOGGER.warning("资源标记失败: " + SLFFMBindings.getResultDescription(result));
                return false;
            }

        } catch (Exception e) {
            LOGGER.warning("标记资源时发生异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 批量标记资源
     *
     * @param resources 资源标记数据数组
     * @return 是否全部标记成功
     */
    public boolean tagResources(ResourceTagData[] resources) {
        if (resources == null || resources.length == 0) {
            LOGGER.warning("资源列表为空");
            return true; // 空列表视为成功
        }

        if (!vulkanRegistered.get()) {
            LOGGER.fine("Vulkan 未注册，跳过批量资源标记");
            return true;
        }

        try (Arena arena = Arena.ofConfined()) {
            // 构建 ResourceTag 数组
            int tagSize = VulkanConst.SL_RESOURCE_TAG_SIZE;
            MemorySegment resourceArray = arena.allocate(tagSize * resources.length);

            for (int i = 0; i < resources.length; i++) {
                ResourceTagData res = resources[i];
                long baseOffset = (long) i * tagSize;

                resourceArray.set(ValueLayout.JAVA_INT, baseOffset + VulkanConst.SL_RESOURCE_TAG_OFFSET_BUFFER_TYPE, res.bufferType);
                resourceArray.set(ValueLayout.JAVA_LONG, baseOffset + VulkanConst.SL_RESOURCE_TAG_OFFSET_IMAGE_VIEW, res.imageView);
                resourceArray.set(ValueLayout.JAVA_INT, baseOffset + VulkanConst.SL_RESOURCE_TAG_OFFSET_WIDTH, res.width);
                resourceArray.set(ValueLayout.JAVA_INT, baseOffset + VulkanConst.SL_RESOURCE_TAG_OFFSET_HEIGHT, res.height);
                resourceArray.set(ValueLayout.JAVA_INT, baseOffset + VulkanConst.SL_RESOURCE_TAG_OFFSET_LIFECYCLE, 
                                   VulkanConst.RESOURCE_LIFECYCLE_ONLY_VALID_NOW);
            }

            // 调用 slSetTagForFrame
            int result = SLFFMBindings.slSetTagForFrame(
                MemorySegment.NULL,  // frame token
                MemorySegment.NULL,  // viewport handle
                resourceArray,        // resource tags array
                resources.length,    // num resources
                MemorySegment.NULL   // command buffer
            );

            if (SLFFMBindings.isOk(result)) {
                LOGGER.fine("批量资源标记成功: " + resources.length + " 个资源");
                return true;
            } else {
                LOGGER.warning("批量资源标记失败: " + SLFFMBindings.getResultDescription(result));
                return false;
            }

        } catch (Exception e) {
            LOGGER.warning("批量标记资源时发生异常: " + e.getMessage());
            return false;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建 ResourceTag 结构体
     */
    private MemorySegment buildResourceTag(Arena arena, int bufferType, 
                                          long imageView, int width, int height) {
        MemorySegment tag = arena.allocate(VulkanConst.SL_RESOURCE_TAG_SIZE);
        tag.set(ValueLayout.JAVA_INT, VulkanConst.SL_RESOURCE_TAG_OFFSET_BUFFER_TYPE, bufferType);
        tag.set(ValueLayout.JAVA_LONG, VulkanConst.SL_RESOURCE_TAG_OFFSET_IMAGE_VIEW, imageView);
        tag.set(ValueLayout.JAVA_INT, VulkanConst.SL_RESOURCE_TAG_OFFSET_WIDTH, width);
        tag.set(ValueLayout.JAVA_INT, VulkanConst.SL_RESOURCE_TAG_OFFSET_HEIGHT, height);
        tag.set(ValueLayout.JAVA_INT, VulkanConst.SL_RESOURCE_TAG_OFFSET_LIFECYCLE, 
           VulkanConst.RESOURCE_LIFECYCLE_ONLY_VALID_NOW);
        return tag;
    }

    // ==================== 查询接口 ====================

    /**
     * 检查桥接是否可用
     *
     * @return true 如果已初始化且 Vulkan 已注册
     */
    public boolean isAvailable() {
        return initialized.get() && vulkanRegistered.get();
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 检查 Vulkan 是否已注册
     */
    public boolean isVulkanRegistered() {
        return vulkanRegistered.get();
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭桥接器
     */
    public void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            vulkanRegistered.set(false);
            LOGGER.info("Vulkan Streamline Bridge 已关闭");
        }
    }

    // ==================== 内部数据结构 ====================

    /**
     * 资源标记数据（用于帧生成资源标记）
     * <p>
     * 封装需要标记给 Streamline SDK 的 Vulkan 资源信息，
     * 包括缓冲区类型、图像视图句柄和尺寸。
     *
     * <h3>字段说明：</h3>
     * <ul>
     *   <li><b>bufferType</b>: 缓冲区类型（颜色/深度/运动向量等）</li>
     *   <li><b>imageView</b>: VkImageView 句柄</li>
     *   <li><b>width/height</b>: 纹理尺寸（像素）</li>
     * </ul>
     *
     * @see #tagResources(ResourceTagData[])
     * @since 3.0.0
     */
    public static class ResourceTagData {
        /** 缓冲区类型（对应 SLFFMBindings.BUFFER_TYPE_* 常量） */
        public final int bufferType;

        /** VkImageView 句柄 */
        public final long imageView;

        /** 纹理宽度（像素） */
        public final int width;

        /** 纹理高度（像素） */
        public final int height;

        /**
         * 构建资源标记数据
         *
         * @param bufferType 缓冲区类型常量
         * @param imageView  VkImageView 句柄
         * @param width      纹理宽度
         * @param height     纹理高度
         */
        public ResourceTagData(int bufferType, long imageView, int width, int height) {
            this.bufferType = bufferType;
            this.imageView = imageView;
            this.width = width;
            this.height = height;
        }
    }
}
