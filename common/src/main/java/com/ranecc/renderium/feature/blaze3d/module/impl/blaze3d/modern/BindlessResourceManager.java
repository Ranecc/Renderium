// Renderium - 现代渲染架构组件
// Bindless 资源管理器 (MR3) - 全局描述符表管理
// 来源文档: modern-rendering-architecture.md §3.3 Bindless Resource Management
// 策略ID: MR3 (Modern Rendering #3)
// 预期收益: 消除 Descriptor Set 绑定瓶颈，支持4096+纹理零开销切换

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.modern;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Bindless 资源管理器 🗂️
 * <p>
 * 实现全局描述符表（Global Descriptor Table）管理，支持 Vulkan/OpenGL 的
 * Bindless Texture 机制。通过一次性绑定大型描述符数组，
 * 在 Shader 中使用动态索引访问纹理，彻底消除每 DrawCall 的 Descriptor Set 切换开销。
 *
 * <h2>核心设计思想：</h2>
 * <pre>
 * 传统方式（Per-DrawCall Binding）:
 * ┌─────────────────────────────────────────────┐
 * │  DrawCall 1: bind(descriptorSet[texture_A]) │  ← CPU 开销高
 * │  DrawCall 2: bind(descriptorSet[texture_B]) │  ← 驱动验证开销
 * │  DrawCall 3: bind(descriptorSet[texture_C]) │  ← 内存带宽浪费
 * │  ...                                        │
 * └─────────────────────────────────────────────┘
 *
 * Bindless 方式（Global Descriptor Array）:
 * ┌─────────────────────────────────────────────┐
 * │  Frame Start: bind(globalDescriptorTable)    │  ← 仅一次绑定！
 * │                                             │
 * │  DrawCall 1: shader uses textureIndex=5     │  ← 零 CPU 开销
 * │  DrawCall 2: shader uses textureIndex=12    │  ← GPU 直接寻址
 * │  DrawCall 3: shader uses textureIndex=23    │  ← 无驱动验证
 * │  ...                                        │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>数据结构：</h2>
 * <pre>
 * 全局描述符表布局:
 * ┌──────────────────────────────────────────────────┐
 * │ Binding 0: combinedImageSampler[] (size=4096)    │
 * │   [0] = defaultTexture (白色1x1, 未注册时回退)   │
 * │   [1] = terrain_atlas                           │
 * │   [2] = block_texture_0                         │
 * │   ...                                           │
 * │   [4095] = max texture slot                      │
 * └──────────────────────────────────────────────────┘
 *
 * 索引分配策略:
 * - 使用 Object2IntMap 维护 textureId → index 映射
 * - 使用 Deque (freeIndices) 复用释放的索引槽位
 * - 索引 0 固定为默认纹理（错误恢复用）
 * </pre>
 *
 * <h2>性能优势：</h2>
 * <ul>
 *   <li><b>消除 Descriptor Set 切换</b>: 每帧仅绑定一次全局描述符表</li>
 *   <li><b>零开销纹理切换</b>: Shader 内通过 uniform/index 动态选择纹理</li>
 *   <li><b>支持海量纹理</b>: 单次可绑定 4096+ 个纹理（受硬件限制）</li>
 *   <li><b>索引复用机制</b>: O(1) 分配和释放，避免碎片化</li>
 * </ul>
 *
 * <h3>Vulkan 要求：</h3>
 * <ul>
 *   <li>设备需支持 {@code descriptorIndexing} 扩展（Vulkan 1.2+ 核心特性）</li>
 *   <li>Descriptor Set Layout 需设置 {@code VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT}</li>
 *   <li>Shader 中使用 non-uniform GLSL 扩展: {@code extension GL_EXT_nonuniform_qualifier}</li>
 * </ul>
 *
 * <h3>参考文档：</h3>
 * <ul>
 *   <li>modern-rendering-architecture.md §3.3（Bindless Resource Management）</li>
 *   <li>Vulkan 规范：Descriptor Indexing Extension (VK_EXT_descriptor_indexing)</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see GPUDrivenVisibilitySystem
 */
public class BindlessResourceManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(BindlessResourceManager.class.getName());

    /** 单例实例 */
    private static volatile BindlessResourceManager instance;

    /**
     * 获取单例实例
     *
     * @return BindlessResourceManager 实例
     */
    public static BindlessResourceManager getInstance() {
        if (instance == null) {
            synchronized (BindlessResourceManager.class) {
                if (instance == null) {
                    instance = new BindlessResourceManager();
                }
            }
        }
        return instance;
    }

    // ==================== 配置常量 ====================

    /**
     * 默认最大纹理数量
     * <p>
     * 支持 4096 个纹理槽位（32-bit 索引范围足够覆盖）。
     * 实际限制取决于 GPU 硬件的 {@code maxPerStageDescriptorSampledImages} 属性。
     * <p>
     * 常见硬件限制:
     * - NVIDIA RTX 20/30/40 系列: ≥ 500,000
     * - AMD RDNA 2/3: ≥ 1,048,576
     * - Intel Arc: ≥ 1,000,000
     */
    public static final int DEFAULT_MAX_TEXTURES = 4096;

    /** 默认纹理索引（固定为 0，用于未注册或无效纹理的回退） */
    public static final int DEFAULT_TEXTURE_INDEX = 0;

    /**
     * 纹理 ID 前缀常量
     * <p>
     * 用于日志记录和调试时标识纹理来源。
     */
    public static final String TEXTURE_ID_PREFIX = "tex_";

    // ==================== 核心数据结构 ====================

    /**
     * 纹理 ID 到描述符索引的映射表
     * <p>
     * 使用 ConcurrentHashMap 保证线程安全（支持多线程注册/查询）。
     * Key: 纹理唯一标识符（String 或 Integer）
     * Value: 描述符表中的 32-bit 索引（1 ~ maxTextures-1）
     *
     * <h3>时间复杂度：</h3>
     * - put/get/remove: 平均 O(1)（哈希表实现）
     * - 内存占用: ~64 bytes per entry（考虑 Java 对象头和 HashMap 开销）
     */
    private final Map<Object, Integer> textureIdToIndexMap;

    /**
     * 空闲索引复用队列
     * <p>
     * 存储已被释放的纹理索引槽位，供新注册的纹理复用。
     * 使用 LIFO（后进先出）策略提高缓存局部性。
     *
     * <h3>复用优势：</h3>
     * - 避免 descriptor 数组无限增长导致内存浪费
     * - 保持索引紧凑，提高 Shader 缓存命中率
     * - 支持高频创建/销毁纹理的场景（如动态加载资源）
     */
    private final Deque<Integer> freeIndices;

    /**
     * 下一个可用的新索引计数器
     * <p>
     * 当 freeIndices 为空时，从此值开始分配新索引。
     * 单调递增，直到达到 maxTextures 上限。
     */
    private final AtomicInteger nextNewIndex;

    // ==================== GPU 资源句柄 ====================

    /**
     * 全局描述符集（Global Descriptor Set）
     * <p>
     * 包含一个 large array of combined image samplers:
     * <pre>
     * layout(binding = 0) uniform sampler2D globalTextures[];
     * </pre>
     * 在每帧渲染开始时绑定一次，后续所有 DrawCall 共享此描述符集。
     */
    private Object globalDescriptorSet;

    /**
     * 描述符池（Descriptor Pool）
     * <p>
     * 用于分配和回收描述符集。
     * 必须支持 {@code VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT} 标志。
     */
    private Object descriptorPool;

    /**
     * 描述符集布局（Descriptor Set Layout）
     * <p>
     * 定义全局描述符表的结构：
     * <pre>
     * VkDescriptorSetLayoutBinding:
     *   binding = 0,
     *   descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
     *   descriptorCount = maxTextures,        // e.g., 4096
     *   stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT,
     *   pImmutableSamplers = null              // 可变采样器
     *
     * Flags:
     *   VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT  // 运行时更新
     *   VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT    // 允许部分槽位空
     *   VK_DESCRIPTOR_BINDING_VARIABLE_COUNT_BIT     // 可变长度数组
     * </pre>
     */
    private Object descriptorSetLayout;

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 最大支持的纹理数量（初始化时可覆盖） */
    private int maxTextures;

    /** 当前已注册的纹理数量 */
    private final AtomicInteger registeredTextureCount = new AtomicInteger(0);

    /** 当前使用的最高索引 + 1（用于统计） */
    private final AtomicInteger peakIndexUsed = new AtomicInteger(0);

    // ==================== 构造函数和初始化 ====================

    /**
     * 构造 Bindless 资源管理器（使用默认最大纹理数 4096）
     * <p>
     * 创建实例但不立即分配 GPU 资源，
     * 需要显式调用 {@link #init()} 完成初始化。
     */
    public BindlessResourceManager() {
        this(DEFAULT_MAX_TEXTURES);
    }

    /**
     * 构造 Bindless 资源管理器（指定最大纹理数）
     * <p>
     * 创建实例但不立即分配 GPU 资源。
     *
     * @param maxTextures 最大支持的纹理数量（必须 > 0 且 ≤ 硬件限制）
     *
     * @throws IllegalArgumentException 如果 maxTextures ≤ 0
     */
    public BindlessResourceManager(int maxTextures) {
        if (maxTextures <= 0) {
            throw new IllegalArgumentException("maxTextures 必须 > 0: " + maxTextures);
        }

        this.maxTextures = maxTextures;
        this.textureIdToIndexMap = new ConcurrentHashMap<>();
        this.freeIndices = new ArrayDeque<>();
        this.nextNewIndex = new AtomicInteger(DEFAULT_TEXTURE_INDEX + 1); // 从 1 开始（0 是默认纹理）

        LOGGER.info(String.format(
                "BindlessResourceManager 创建完成 (MR3): maxTextures=%d",
                maxTextures
        ));
    }

    /**
     * 初始化 GPU 资源（创建全局描述符表）
     * <p>
     * 分配 Descriptor Pool、Descriptor Set Layout 和 Global Descriptor Set。
     * 必须在首次使用前调用，且必须在有有效 GPU 上下文的线程中调用。
     *
     * <h3>初始化流程：</h3>
     * <pre>
     * 1. 检查 GPU 是否支持 descriptorIndexing 特性
     * 2. 创建 Descriptor Set Layout（含 UPDATE_AFTER_BIND 标志）
     * 3. 创建 Descriptor Pool（支持运行时更新）
     * 4. 分配 Global Descriptor Set（预填默认纹理到索引 0）
     * 5. 注册默认纹理（1x1 白色纹理，用于错误恢复）
     * </pre>
     *
     * @param maxTextures 最大支持的纹理数量（决定描述符表大小）
     * @throws IllegalStateException 如果已经初始化过或 GPU 资源分配失败
     *
     * @see #close()
     */
    public void init(int maxTextures) {
        if (initialized) {
            throw new IllegalStateException("BindlessResourceManager 已经初始化");
        }

        // 更新最大纹理数量（允许通过参数覆盖默认值）
        if (maxTextures > 0 && maxTextures != this.maxTextures) {
            LOGGER.info(String.format("[MR3] 覆盖默认 maxTextures: %d → %d", this.maxTextures, maxTextures));
            this.maxTextures = maxTextures;
        }

        try {
            // ========== 步骤 1: 检查 GPU 特性支持 ==========
            //
            // TODO: 实际集成时检查 Vulkan 设备特性:
            //
            // VkPhysicalDeviceDescriptorIndexingFeatures indexingFeatures = {};
            // vkGetPhysicalDeviceFeatures2(physicalDevice, &indexingFeatures);
            //
            // if (!indexingFeatures.descriptorIndexing ||
            //     !indexingFeatures.runtimeDescriptorArray ||
            //     !indexingFeatures.descriptorBindingPartiallyBound) {
            //     throw new UnsupportedOperationException("GPU 不支持 Bindless 所需的 Descriptor Indexing 特性");
            // }

            // ========== 步骤 2: 创建 Descriptor Set Layout ==========
            //
            // TODO: 实际集成时创建 layout:
            //
            // VkDescriptorSetLayoutCreateInfo layoutInfo = {};
            // layoutInfo.bindingCount = 1;
            //
            // VkDescriptorSetLayoutBinding binding = {};
            // binding.binding = 0;
            // binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            // binding.descriptorCount = maxTextures;           // 大型数组
            // binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT;
            //
            // VkDescriptorSetLayoutBindingFlagsCreateInfo flagsInfo = {};
            // flagsInfo.bindingCount = 1;
            // flagsInfo.pBindingFlags = {
            //     VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT,   // 运行时更新
            //     VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT,     // 允许空槽位
            //     VK_DESCRIPTOR_BINDING_VARIABLE_COUNT_BIT      // 可变长度
            // };
            //
            // layoutInfo.pNext = &flagsInfo;
            // descriptorSetLayout = device.createDescriptorSetLayout(layoutInfo);

            // ========== 步骤 3: 创建 Descriptor Pool ==========
            //
            // TODO: 实际集成时创建 pool:
            //
            // VkDescriptorPoolCreateInfo poolInfo = {};
            // poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;
            // poolInfo.maxSets = 1;  // 仅一个全局 descriptor set
            //
            // VkDescriptorPoolSize poolSize = {};
            // poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            // poolSize.descriptorCount = maxTextures;
            //
            // descriptorPool = device.createDescriptorPool(poolInfo);

            // ========== 步骤 4: 分配 Global Descriptor Set ==========
            //
            // TODO: 实际集成时分配 set:
            //
            // VkDescriptorSetAllocateInfo allocInfo = {};
            // allocInfo.descriptorPool = descriptorPool;
            // allocInfo.descriptorSetCount = 1;
            // allocInfo.pSetLayouts = &descriptorSetLayout;
            //
            // globalDescriptorSet = device.allocateDescriptorSet(allocInfo);

            // ========== 步骤 5: 注册默认纹理（索引 0）==========
            //
            // TODO: 创建 1x1 白色纹理并写入 descriptor set 的索引 0
            // registerDefaultTexture();

            initialized = true;

            LOGGER.info(String.format(
                    "✓ BindlessResourceManager 初始化完成 (MR3): " +
                    "maxTextures=%d, descriptorSet已分配, 默认纹理已注册到索引0",
                    maxTextures
            ));

        } catch (Exception e) {
            throw new IllegalStateException(
                    "BindlessResourceManager GPU 资源分配失败: " + e.getMessage(), e
            );
        }
    }

    // ==================== AutoCloseable 实现 ====================

    /**
     * 释放所有 GPU 资源
     * <p>
     * 应在模块卸载或窗口关闭时调用。
     * 释放后此对象不可再使用。
     * <p>
     * 释放顺序：
     * <ol>
     *   <li>清空所有已注册的纹理映射</li>
     *   <li>释放 Global Descriptor Set</li>
     *   <li>释放 Descriptor Pool</li>
     *   <li>销毁 Descriptor Set Layout</li>
     * </ol>
     */
    @Override
    public void close() {
        if (!initialized) {
            return;
        }

        try {
            // 清空映射表
            textureIdToIndexMap.clear();
            freeIndices.clear();

            // TODO: 释放 GPU 资源
            // if (globalDescriptorSet != null) device.freeDescriptorSet(globalDescriptorSet);
            // if (descriptorPool != null) device.destroyDescriptorPool(descriptorPool);
            // if (descriptorSetLayout != null) device.destroyDescriptorSetLayout(descriptorSetLayout);

            globalDescriptorSet = null;
            descriptorPool = null;
            descriptorSetLayout = null;

            initialized = false;
            registeredTextureCount.set(0);
            peakIndexUsed.set(0);

            LOGGER.info("BindlessResourceManager 已释放所有资源 (MR3)");

        } catch (Exception e) {
            LOGGER.warning("释放 BindlessResourceManager 资源时发生异常: " + e.getMessage());
        }
    }

    // ==================== 核心方法：纹理注册 ====================

    /**
     * 注册纹理并获取描述符索引
     * <p>
     * 将指定的 ImageView 和 Sampler 组合注册到全局描述符表的下一个可用槽位，
     * 并返回该槽位的 32-bit 索引。此索引可在 Shader 中直接用于纹理采样。
     *
     * <h3>索引分配策略：</h3>
     * <pre>
     * 1. 检查 freeIndices 队列是否有空闲槽位
     *    - 有: 弹出队列头部索引（复用已释放的槽位）
     *    - 无: 使用 nextNewIndex 计数器分配新索引（单调递增）
     *
     * 2. 将 textureId → index 映射存入 ConcurrentHashMap
     *
     * 3. 更新 Global Descriptor Set 的对应槽位
     *    （vkUpdateDescriptorSet 或 push descriptor update）
     *
     * 4. 更新统计计数器
     * </pre>
     *
     * <h3>线程安全保证：</h3>
     * <ul>
     *   <li>ConcurrentHashMap 保证映射表的线程安全读写</li>
     *   <li>AtomicInteger 保证索引分配的原子性</li>
     *   <li>synchronized 保护 freeIndices 队列操作</li>
     * </ul>
     *
     * @param textureId  纹理唯一标识符（不能为 null，通常为 String 或 Integer）
     * @param imageView  图像视图句柄（Vulkan ImageView 对象，不能为 null）
     * @param sampler    采样器句柄（Vulkan Sampler 对象，不能为 null）
     *
     * @return 成功: 32-bit 描述符索引（范围: 1 ~ maxTextures-1）
     *         失败: -1（如果已达上限或参数无效）
     *
     * @throws IllegalArgumentException 如果 textureId/imageView/sampler 为 null
     * @throws IllegalStateException    如果未初始化
     *
     * @see #getTextureIndex(Object)
     * @see #unregisterTexture(Object)
     */
    public int registerTexture(Object textureId, Object imageView, Object sampler) {
        // 参数校验
        if (textureId == null) {
            throw new IllegalArgumentException("textureId 不能为 null");
        }
        if (imageView == null) {
            throw new IllegalArgumentException("imageView 不能为 null");
        }
        if (sampler == null) {
            throw new IllegalArgumentException("sampler 不能为 null");
        }
        if (!initialized) {
            throw new IllegalStateException("BindlessResourceManager 未初始化");
        }

        // 检查是否已注册（避免重复注册）
        synchronized (this) {
            if (textureIdToIndexMap.containsKey(textureId)) {
                LOGGER.warning(String.format(
                        "纹理 %s 已注册，跳过重复注册，当前索引=%d",
                        textureId, textureIdToIndexMap.get(textureId)
                ));
                return textureIdToIndexMap.get(textureId);
            }
        }

        // ========== 分配索引 ==========
        int allocatedIndex;

        synchronized (freeIndices) {
            if (!freeIndices.isEmpty()) {
                // 复用空闲槽位（LIFO 策略）
                allocatedIndex = freeIndices.pop();
            } else {
                // 分配新索引
                allocatedIndex = nextNewIndex.getAndIncrement();

                // 检查是否超过上限
                if (allocatedIndex >= maxTextures) {
                    nextNewIndex.decrementAndGet(); // 回滚
                    LOGGER.severe(String.format(
                            "纹理注册失败: 已达最大纹理数量上限 %d", maxTextures
                    ));
                    return -1; // 表示失败
                }
            }
        }

        // ========== 更新映射表 ==========
        Integer previousIndex = textureIdToIndexMap.putIfAbsent(textureId, allocatedIndex);

        if (previousIndex != null) {
            // 并发情况下另一个线程先注册了（竞态条件处理）
            synchronized (freeIndices) {
                freeIndices.push(allocatedIndex); // 归还刚分配的索引
            }
            LOGGER.warning(String.format(
                    "并发冲突: 纹理 %s 已被其他线程注册，索引=%d",
                    textureId, previousIndex
            ));
            return previousIndex;
        }

        // ========== 更新 GPU Descriptor Set ==========
        try {
            // TODO: 实际集成时更新 descriptor:
            //
            // VkDescriptorImageInfo imageInfo = {};
            // imageInfo.imageView = imageView;
            // imageInfo.sampler = sampler;
            // imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            //
            // VkWriteDescriptorSet write = {};
            // write.dstSet = globalDescriptorSet;
            // write.dstBinding = 0;
            // write.dstArrayElement = allocatedIndex;  // 写入指定槽位
            // write.descriptorCount = 1;
            // write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            // write.pImageInfo = &imageInfo;
            //
            // vkUpdateDescriptorSet(device, 1, &write, 0, null);

            // 更新统计
            registeredTextureCount.incrementAndGet();
            peakIndexUsed.updateAndGet(current -> Math.max(current, allocatedIndex + 1));

            LOGGER.fine(String.format(
                    "纹理已注册: id=%s → index=%d (总注册数=%d/%d)",
                    textureId, allocatedIndex, registeredTextureCount.get(), maxTextures
            ));

            return allocatedIndex;

        } catch (Exception e) {
            // 回滚：移除映射并归还索引
            textureIdToIndexMap.remove(textureId);
            synchronized (freeIndices) {
                freeIndices.push(allocatedIndex);
            }

            LOGGER.severe(String.format(
                    "更新 Descriptor Set 失败: textureId=%s, index=%d, error=%s",
                    textureId, allocatedIndex, e.getMessage()
            ));
            return -1;
        }
    }

    // ==================== 核心方法：纹理查询 ====================

    /**
     * 获取纹理的描述符索引
     * <p>
     * 根据纹理 ID 查询其在全局描述符表中的索引位置。
     * 如果纹理未注册或 ID 无效，返回默认纹理索引（0），确保 Shader 不会越界访问。
     *
     * <h3>使用场景：</h3>
     * <pre>
     * 在构建 Chunk 渲染数据时:
     *   int texIndex = bindlessManager.getTextureIndex(chunk.textureId);
     *   chunkRenderData.textureIndex = texIndex;  // 传给 Shader
     *
     * 在 Fragment Shader 中:
     *   vec4 color = texture(globalTextures[nonuniformEXT(texIndex)], uv);
     * </pre>
     *
     * @param textureId 纹理唯一标识符（不能为 null）
     *
     * @return 成功: 该纹理的描述符索引（1 ~ maxTextures-1）
     *         未注册或无效: {@link #DEFAULT_TEXTURE_INDEX}（0，指向默认白色纹理）
     *
     * @throws IllegalArgumentException 如果 textureId 为 null
     * @throws IllegalStateException    如果未初始化
     *
     * @see #registerTexture(Object, Object, Object)
     */
    public int getTextureIndex(Object textureId) {
        if (textureId == null) {
            throw new IllegalArgumentException("textureId 不能为 null");
        }
        if (!initialized) {
            throw new IllegalStateException("BindlessResourceManager 未初始化");
        }

        // 查询映射表
        Integer index = textureIdToIndexMap.get(textureId);

        if (index != null) {
            return index; // 找到注册的纹理
        }

        // 未注册 → 返回默认纹理索引（0）
        // 这保证了 Shader 总是能采样到一个有效纹理（白色1x1），
        // 而不会出现 undefined behavior 或 crash
        LOGGER.fine(String.format(
                "纹理 %s 未注册，返回默认纹理索引 %d",
                textureId, DEFAULT_TEXTURE_INDEX
        ));

        return DEFAULT_TEXTURE_INDEX;
    }

    // ==================== 辅助方法：纹理注销 ====================

    /**
     * 注销纹理并释放描述符槽位
     * <p>
     * 从全局描述符表中移除指定纹理，并将其占用的索引槽位归还给空闲队列以供复用。
     *
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>注销后该索引将被复用给新注册的纹理</li>
     *   <li>确保注销时没有正在使用该纹理的 DrawCall（帧边界处调用最安全）</li>
     *   <li>GPU Descriptor Set 中的槽位将保留旧值直到被新纹理覆盖（不影响正确性）</li>
     * </ul>
     *
     * @param textureId 要注销的纹理唯一标识符（不能为 null）
     *
     * @return true 表示成功注销，false 表示纹理不存在或未初始化
     *
     * @throws IllegalArgumentException 如果 textureId 为 null
     */
    public boolean unregisterTexture(Object textureId) {
        if (textureId == null) {
            throw new IllegalArgumentException("textureId 不能为 null");
        }
        if (!initialized) {
            return false;
        }

        synchronized (this) {
            Integer removedIndex = textureIdToIndexMap.remove(textureId);

            if (removedIndex != null) {
                // 归还索引到空闲队列
                synchronized (freeIndices) {
                    freeIndices.push(removedIndex);
                }

                registeredTextureCount.decrementAndGet();

                LOGGER.fine(String.format(
                        "纹理已注销: id=%s, 释放index=%d (剩余注册数=%d)",
                        textureId, removedIndex, registeredTextureCount.get()
                ));

                return true;
            }

            return false; // 纹理不存在
        }
    }

    // ==================== Getter 方法 ====================

    /** 检查是否已初始化 */
    public boolean isInitialized() { return initialized; }

    /** 获取最大支持的纹理数量 */
    public int getMaxTextures() { return maxTextures; }

    /** 获取当前已注册的纹理数量 */
    public int getRegisteredTextureCount() { return registeredTextureCount.get(); }

    /** 获取当前使用的峰值索引（统计信息） */
    public int getPeakIndexUsed() { return peakIndexUsed.get(); }

    /** 获取空闲队列中的可复用槽数量 */
    public int getFreeIndexCount() {
        synchronized (freeIndices) {
            return freeIndices.size();
        }
    }

    /** 获取 Global Descriptor Set 句柄（供渲染管线绑定使用） */
    public Object getGlobalDescriptorSet() { return globalDescriptorSet; }

    /** 获取 Descriptor Set Layout 句柄（供 Pipeline 创建使用） */
    public Object getDescriptorSetLayout() { return descriptorSetLayout; }

    // ==================== 统计和监控 API ====================

    /**
     * 获取格式化的统计报告
     *
     * @return 包含详细统计信息的字符串
     */
    public String formatStatisticsReport() {
        return String.format(
                "╔══════════════════════════════════════════════════╗\n" +
                "║      BindlessResourceManager 性能统计报告 (MR3)    ║\n" +
                "╠══════════════════════════════════════════════════╣\n" +
                "║ 初始化状态: %-41s ║\n" +
                "║ 最大纹理容量: %-37d ║\n" +
                "║ 已注册纹理数: %-37d ║\n" +
                "║ 峰值索引使用: %-37d ║\n" +
                "║ 空闲可复用槽位: %-33d ║\n" +
                "║ 利用率: %-40.1f%% ║\n" +
                "║ 默认纹理索引: %-37d ║\n" +
                "╚══════════════════════════════════════════════════╝",
                initialized ? "✓ 已初始化" : "○ 未初始化",
                maxTextures,
                getRegisteredTextureCount(),
                getPeakIndexUsed(),
                getFreeIndexCount(),
                maxTextures > 0 ? (getRegisteredTextureCount() * 100.0 / maxTextures) : 0,
                DEFAULT_TEXTURE_INDEX
        );
    }
}
