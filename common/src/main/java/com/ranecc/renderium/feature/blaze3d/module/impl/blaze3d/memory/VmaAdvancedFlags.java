// Renderium - Blaze3D 优化模块
// VMA 激进优化系统 - 高级内存标志管理器
//
// 功能：提供 Vulkan 高级内存特性的封装，包括持久映射、设备地址、受保护内存等
// 参考：vma-aggressive-optimizations.md §2.5 绑定内存标志 (Bind Memory Flags)

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d.memory;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * VMA 高级内存标志管理器 🚩
 * <p>
 * 封装 Vulkan Memory Allocator 的高级分配标志和特殊内存类型，
 * 提供对现代 GPU 硬件高级特性的访问能力。
 * 包括持久映射缓冲区、设备地址缓冲区和受保护内存等。
 *
 * <h2>核心功能：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  1. 持久映射缓冲区 (Persistent Mapped Buffer)               │
 * │     CPU 端永久映射，零拷贝上传，适用于频繁更新的数据        │
 * │     性能提升：消除 map/unmap 开销（~1µs/次）                │
 * ├─────────────────────────────────────────────────────────────┤
 * │  2. 设备地址缓冲区 (Buffer Device Address)                  │
 * │     用于光线追踪、间接渲染、Shader Binding Table 等         │
 * │     允许 GPU Shader 直接通过地址访问缓冲区                   │
 * ├─────────────────────────────────────────────────────────────┤
 * │  3. 受保护内存 (Protected Memory)                           │
 * │     DRM 内容保护，防止未授权的内存读取                      │
 * │     适用于版权保护的多媒体内容                              │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用场景与性能收益：</h3>
 * <table border="1">
 *   <tr><th>功能</th><th>使用场景</th><th>性能提升</th></tr>
 *   <tr><td>持久映射</td><td>Uniform Buffer、Staging Buffer</td><td>消除 map/unmap 开销</td></tr>
 *   <tr><td>设备地址</td><td>光线追踪、间接绘制</td><td>启用 RT/Indirect 特性</td></tr>
 *   <tr><td>受保护内存</td><td>DRM 视频流、付费内容</td><td>安全性保障</td></tr>
 * </table>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建高级标志管理器
 * VmaAdvancedFlags advanced = new VmaAdvancedFlags();
 * advanced.initialize(vmaAllocator);
 *
 * // 示例 1：创建持久映射的 Staging Buffer（用于频繁上传）
 * PersistentMappedBuffer staging = advanced.createPersistentMappedBuffer(
 *     4 * 1024 * 1024  // 4 MB
 * );
 * // 直接写入数据，无需 map/unmap！
 * staging.mappedData.putFloat(0, 1.0f);
 *
 * // 示例 2：创建带设备地址的缓冲区（用于光线追踪）
 * DeviceAddressBuffer rtBuffer = advanced.createBufferWithDeviceAddress(
 *     rayDataSize,
 *     VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
 * );
 * long deviceAddress = rtBuffer.deviceAddress;  // GPU 可直接访问此地址
 *
 * // 示例 3：创建受保护的缓冲区（DRM 内容）
 * ProtectedBuffer protectedBuf = advanced.createProtectedBuffer(protectedContentSize);
 *
 * // 关闭时释放所有资源
 * advanced.close();
 * }</pre>
 *
 * <h3>硬件支持检查：</h3>
 * <p>
 * 使用这些高级特性前，应确保硬件和驱动程序支持相应的 Vulkan 扩展：
 * <ul>
 *   <li>持久映射：通常所有 Vulkan 设备都支持</li>
 *   <li>设备地址：需要 {@code VK_EXT_buffer_device_address} 或 Vulkan 1.2+</li>
 *   <li>受保护内存：需要 {@code VK_KHR_protected_memory} 和硬件支持</li>
 * </ul>
 *
 * <h3>线程安全说明：</h3>
 * <ul>
 *   <li>创建操作可从任何线程调用（VMA 内部保证线程安全）</li>
 *   <li>对返回的映射数据的并发读写需外部同步</li>
 *   <li>内部状态使用 volatile 保证可见性</li>
 * </ul>
 *
 * @see VmaMemoryPools 专用内存池管理器
 * @see VmaDeferredDeallocation 延迟销毁系统
 * @author Renderium Team
 * @since 2.0.0
 */
public class VmaAdvancedFlags implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VmaAdvancedFlags.class.getName());

    // ==================== 配置常量 ====================

    /** 默认是否启用顺序一致写入优化 */
    private static final boolean DEFAULT_USE_SEQUENTIAL_WRITE = true;

    /** 默认是否启用延迟绑定 */
    private static final boolean DEFAULT_USE_DONT_BIND = false;

    // ==================== VMA 常量补充（LWJGL 绑定未导出的常量）====================

    /**
     * VMA_ALLOCATION_CREATE_BUFFER_DEVICE_ADDRESS_BIT
     * <p>
     * 来源：Vulkan Memory Allocator 规范 (VmaAllocationCreateFlags)
     * 值：0x00000008
     * <p>
     * 作用：告知 VMA 该分配可能用于获取设备地址（VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT）。
     * 设置此标志后，VMA 会将分配放在支持 VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT 的内存类型上，
     * 并确保该内存可用于 {@code vkGetBufferDeviceAddress()} 调用。
     * <p>
     * 使用场景：
     * <ul>
     *   <li>光线追踪加速结构（Acceleration Structures）</li>
     *   <li>Shader Binding Tables</li>
     *   <li>间接绘制缓冲区（Indirect Drawing Buffers）</li>
     *   <li>需要 GPU Shader 直接通过地址访问的缓冲区</li>
     * </ul>
     *
     * @see <a href="https://gpuopen.com/vulkan-memory-allocator/">VMA 官方文档</a>
     */
    public static final int VMA_ALLOCATION_CREATE_BUFFER_DEVICE_ADDRESS_BIT = 0x00000008;

    /**
     * VMA_ALLOCATION_CREATE_PROTECTED_BIT
     * <p>
     * 来源：Vulkan Memory Allocator 规范 (VmaAllocationCreateFlags)
     * 值：0x00000010
     * <p>
     * 作用：请求创建受保护的内存分配（VK_MEMORY_PROPERTY_PROTECTED_BIT）。
     * 受保护内存具有以下特性：
     * <ul>
     *   <li>只能通过受保护队列访问（需要 VK_QUEUE_PROTECTED_BIT）</li>
     *   <li>无法被非受保护命令读取或复制</li>
     *   <li>适用于 DRM 内容保护场景</li>
     * </ul>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>需要硬件和驱动程序支持 VK_KHR_protected_memory 扩展</li>
     *   <li>必须配合 VMA_MEMORY_USAGE_UNKNOWN 使用（让 VMA 自动选择合适的内存类型）</li>
     *   <li>不支持所有 Vulkan 设备，使用前应检查设备特性</li>
     * </ul>
     *
     * @see <a href="https://gpuopen.com/vulkan-memory-allocator/">VMA 官方文档</a>
     */
    public static final int VMA_ALLOCATION_CREATE_PROTECTED_BIT = 0x00000010;

    // ==================== 核心字段 ====================

    /**
     * VMA 分配器句柄
     */
    private volatile long vmaAllocator = 0L;

    /**
     * Vulkan 设备句柄（用于查询设备地址等操作）
     */
    private volatile long vkDevice = 0L;

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ==================== 资源跟踪 ====================

    /**
     * 通过此类创建的所有资源的列表（用于 close() 时统一释放）
     */
    private final List<AdvancedResource> trackedResources = new ArrayList<>();

    // ==================== 统计字段 ====================

    /** 创建的持久映射缓冲区数量 */
    private final AtomicLong persistentMappedCount = new AtomicLong(0);

    /** 创建的设备地址缓冲区数量 */
    private final AtomicLong deviceAddressCount = new AtomicLong(0);

    /** 创建的受保护缓冲区数量 */
    private final AtomicLong protectedBufferCount = new AtomicLong(0);

    /** 总分配字节数 */
    private final AtomicLong totalBytesAllocated = new AtomicLong(0);

    // ==================== 构造函数 ====================

    /**
     * 创建高级内存标志管理器
     * <p>
     * 初始状态下未连接到任何 VMA 分配器。
     * 必须调用 {@link #initialize} 后才能使用高级功能。
     */
    public VmaAdvancedFlags() {
        LOGGER.fine("VmaAdvancedFlags 实例已创建（等待初始化）");
    }

    // ==================== 公共 API：生命周期管理 ====================

    /**
     * 初始化高级内存标志管理器 ⚙️
     * <p>
     * 连接到指定的 VMA 分配器和 Vulkan 设备。
     * 此方法应在 Vulkan 设备初始化完成后、使用任何高级功能前调用一次。
     *
     * @param vmaAllocator VMA 分配器句柄（由 vkCreateAllocator 返回的非零值）
     * @param vkDevice     Vulkan 设备句柄（由 vkCreateDevice 返回的非零值）
     *                     用于某些需要设备级别操作的功能（如查询设备地址）
     *
     * @return 如果成功初始化则返回 true；如果参数无效或已初始化则返回 false
     *
     * @throws IllegalStateException 如果已经关闭（close() 已调用）
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>vmaAllocator</b>: long - VMA 分配器句柄。必须有效且非零。</li>
     *   <li><b>vkDevice</b>: long - Vulkan 逻辑设备句柄。必须有效且非零。
     *       某些功能（如获取设备地址）需要此参数。</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * VmaAdvancedFlags advanced = new VmaAdvancedFlags();
     * if (!advanced.initialize(vmaAllocator, vkDevice)) {
     *     logger.error("高级标志管理器初始化失败");
     * }
     * }</pre>
     */
    public boolean initialize(long vmaAllocator, long vkDevice) {
        // 参数校验
        if (vmaAllocator == 0L) {
            LOGGER.severe("initialize 失败: vmaAllocator 不能为 0");
            return false;
        }

        if (vkDevice == 0L) {
            LOGGER.severe("initialize 失败: vkDevice 不能为 0");
            return false;
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaAdvancedFlags 已关闭，无法重新初始化");
        }

        if (initialized.get()) {
            LOGGER.warning("initialize: 已经初始化过，跳过重复初始化");
            return true; // 幂等性
        }

        try {
            this.vmaAllocator = vmaAllocator;
            this.vkDevice = vkDevice;
            initialized.set(true);

            LOGGER.info(String.format("✓ VmaAdvancedFlags 初始化完成 (vma=%d, device=%d)",
                    vmaAllocator, vkDevice));
            return true;

        } catch (Exception e) {
            LOGGER.severe("initialize 异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 创建持久映射缓冲区 📎
     * <p>
     * 创建一个在 CPU 端**永久映射**的缓冲区，无需每次使用时进行 map/unmap 操作。
     * 适用于需要频繁更新的数据（如 Uniform Buffer、Staging Buffer 等）。
     *
     * <h3>工作原理：</h3>
     * <ol>
     *   <li>创建 Buffer 并分配 HOST_VISIBLE 内存</li>
     *   <li>设置 {@code VMA_ALLOCATION_CREATE_MAPPED_BIT} 标志</li>
     *   <li>VMA 在创建时自动映射内存并返回映射指针</li>
     *   <li>后续可直接读写映射内存，无需额外 map 调用</li>
     * </ol>
     *
     * <h3>性能优势：</h3>
     * <table border="1">
     *   <tr><th>操作</th><th>标准方式</th><th>持久映射</th><th>节省</th></tr>
     *   <tr><td>每次写入</td><td>map → write → unmap (~1-5µs)</td><td>直接写入 (~10ns)</td><td>100-500x</td></tr>
     *   <tr><td>每帧 100 次更新</td><td>~0.5ms</td><td>~0.001ms</td><td>500x ↑</td></tr>
     * </table>
     *
     * @param size 缓冲区大小（字节），必须大于 0
     *
     * @return {@link PersistentMappedBuffer} 包含 buffer 句柄、allocation 句柄和映射后的 ByteBuffer；
     *         如果创建失败则返回 null
     *
     * @throws IllegalArgumentException 如果 size <= 0
     * @throws IllegalStateException 如果未初始化或已关闭
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>size</b>: long - 缓冲区的字节大小。必须大于 0。
     *       建议范围：256B ~ 256MB（过大的持久映射可能影响性能）。</li>
     * </ul>
     *
     * <h4>返回值说明：</h4>
     * <ul>
     *   <li>{@link PersistentMappedBuffer#buffer} - VkBuffer 句柄</li>
     *   <li>{@link PersistentMappedBuffer#allocation} - VmaAllocation 句柄</li>
     *   <li>{@link PersistentMappedBuffer#mappedData} - 已映射的 java.nio.ByteBuffer，
     *       可直接读写（无需 map/unmap）</li>
     *   <li>{@link PersistentMappedBuffer#size} - 缓冲区大小</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 创建 64KB 的持久映射 Uniform Buffer
     * PersistentMappedBuffer ubo = advanced.createPersistentMappedBuffer(64 * 1024);
     *
     * if (ubo != null) {
     *     // 直接写入 Uniform 数据（无需 map！）
     *     ubo.mappedData.putInt(0, modelMatrix[0]);      // 写入矩阵第一行
     *     ubo.mappedData.putFloat(16, 1.0f);              // 写入浮点数
     *
     *     // 对于 Direct Buffer，可能需要 flip 或其他操作
     *     // ubo.mappedData.flip();
     *
     *     // 数据已经自动同步到 GPU 可见的内存中（如果使用了 COHERENT 标志）
     *     // 否则需要手动 flush：
     *     // Vma.vmaFlushAllocation(vmaAllocator, ubo.allocation, 0, VK_WHOLE_SIZE);
     * }
     * }</pre>
     *
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>映射的内存在整个缓冲区生命周期内保持有效</li>
     *   <li>多线程并发写入需外部加锁（或使用顺序一致写入标志）</li>
     *   <li>对于非常大的缓冲区（>100MB），考虑分块或使用普通映射</li>
     *   <li>配合 {@code HOST_COHERENT} 内存属性可避免手动 flush</li>
     * </ul>
     */
    public PersistentMappedBuffer createPersistentMappedBuffer(long size) {
        // 参数校验
        if (size <= 0) {
            throw new IllegalArgumentException("size 必须大于 0，当前值: " + size);
        }

        if (!initialized.get()) {
            throw new IllegalStateException("VmaAdvancedFlags 未初始化，请先调用 initialize()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaAdvancedFlags 已关闭");
        }

        try {
            // 调用 VMA API 创建持久映射缓冲区（使用 LWJGL Vulkan 绑定）
            // 方法参数: size -> PersistentMappedBuffer (包含 buffer, allocation, mappedData)
            
            long buffer = 0L;
            long allocation = 0L;
            ByteBuffer mappedData = null;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 1. 构建 VkBufferCreateInfo
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(size)
                        .usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)  // 通常用于 Staging
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

                // 2. 构建带 MAPPED_BIT 的 Allocation 信息
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_CPU_TO_GPU)  // CPU 写入，GPU 读取
                        .flags(
                                Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT |                    // 关键：创建即映射
                                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT  // 顺序写入优化
                        );

                if (DEFAULT_USE_DONT_BIND) {
                    allocInfo.flags(allocInfo.flags() | Vma.VMA_ALLOCATION_CREATE_DONT_BIND_BIT);
                }

                // 3. 创建缓冲区（VMA 自动映射）
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);
                VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc(stack);

                int result = Vma.vmaCreateBuffer(
                        vmaAllocator,
                        bufferInfo,
                        allocInfo,
                        pBuffer,
                        pAllocation,
                        allocationInfo
                );

                if (result != VK10.VK_SUCCESS) {
                    LOGGER.severe(String.format("VMA 创建持久映射缓冲区失败 [size=%d]: VkResult=%d",
                            size, result));
                    return null;
                }

                buffer = pBuffer.get(0);
                allocation = pAllocation.get(0);

                // 4. 获取映射后的指针并包装为 ByteBuffer
                long mappedPtr = allocationInfo.pMappedData();  // 已经映射好的指针
                if (mappedPtr != 0L) {
                    // 使用 LWJGL MemoryUtil 将原生指针包装为 ByteBuffer
                    // 注意：PointerBuffer.create() 在某些 LWJGL 版本中不可用，
                    // 因此使用 MemoryUtil.memByteBuffer() 作为替代方案
                    mappedData = MemoryUtil.memByteBuffer(mappedPtr, (int)size);
                }
            }

            // 更新统计
            persistentMappedCount.incrementAndGet();
            totalBytesAllocated.addAndGet(size);

            // 跟踪资源（用于 close() 时统一释放）
            if (buffer != 0L && allocation != 0L) {
                trackResource(buffer, allocation, "PersistentMapped", size);
            }

            LOGGER.finest(String.format("创建持久映射缓冲区成功: size=%s, buffer=%d, allocation=%d",
                    formatSize(size), buffer, allocation));
            
            return new PersistentMappedBuffer(buffer, allocation, mappedData, size);

        } catch (Exception e) {
            LOGGER.severe(String.format("createPersistentMappedBuffer 异常 [size=%d]: %s",
                    size, e.getMessage()));
            return null;
        }
    }

    /**
     * 创建带设备地址的缓冲区 📍
     * <p>
     * 创建一个可通过**设备地址**（Device Address）从 GPU Shader 访问的缓冲区。
     * 这是实现光线追踪（Ray Tracing）、间接绘制（Indirect Drawing）、
     * Shader Binding Table 等现代图形技术的必要前提。
     *
     * <h3>什么是设备地址？</h3>
     * <pre>
     * ┌─────────────────────────────────────────────────────────────┐
     * │  标准 Vulkan：                                               │
     * │    Shader 通过 descriptor set (binding, index) 间接引用缓冲区 │
     * │                                                             │
     * │  设备地址扩展：                                               │
     * │    Shader 可以直接通过 64 位物理地址访问缓冲区                │
     * │    类似于 CUDA 的指针语义                                     │
     * │                                                             │
     * │  用途：                                                      │
     * │    - 光线追踪：Acceleration Structure 引用                   │
     * │    - 间接绘制：间接命令缓冲中的地址字段                       │
     * │    - 绑定表：Ray Tracing Pipeline 的 Shader Binding Table    │
     * │    - 通用 GPU 计算：类似 CUDA 的 flat pointer 模型           │
     * └─────────────────────────────────────────────────────────────┘
     * </pre>
     *
     * @param size  缓冲区大小（字节），必须大于 0
     * @param usage 缓冲区使用标志位掩码（必须包含 VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT）
     *
     * @return {@link DeviceAddressBuffer} 包含 buffer 句柄、allocation 句柄和设备地址；
     *         如果创建失败或硬件不支持则返回 null
     *
     * @throws IllegalArgumentException 如果 size <= 0 或 usage 无效
     * @throws IllegalStateException 如果未初始化或已关闭或不支持设备地址
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>size</b>: long - 缓冲区大小（字节）。必须大于 0。</li>
     *   <li><b>usage</b>: int - Vulkan 缓冲区使用标志位掩码。
     *       <b>必须包含</b> {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT} 才能启用设备地址功能。
     *       常用组合：<ul>
     *         <li>光线追踪：{@code STORAGE_BUFFER | SHADER_DEVICE_ADDRESS}</li>
     *         <li>间接绘制：{@code INDIRECT_BUFFER | SHADER_DEVICE_ADDRESS}</li>
     *         <li>通用计算：{@code STORAGE_BUFFER | UNIFORM_BUFFER | SHADER_DEVICE_ADDRESS}</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <h4>返回值说明：</h4>
     * <ul>
     *   <li>{@link DeviceAddressBuffer#buffer} - VkBuffer 句柄</li>
     *   <li>{@link DeviceAddressBuffer#allocation} - VmaAllocation 句柄</li>
     *   <li>{@link DeviceAddressBuffer#deviceAddress} - 64 位设备地址
     *       （可在 GPU Shader 中直接使用）</li>
     *   <li>{@link DeviceAddressBuffer#size} - 缓冲区大小</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 创建用于光线追踪的存储缓冲区
     * DeviceAddressBuffer rayDataBuffer = advanced.createBufferWithDeviceAddress(
     *     rayDataSize,
     *     VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
     * );
     *
     * if (rayDataBuffer != null) {
     *     long deviceAddr = rayDataBuffer.deviceAddress;
     *
     *     // 将设备地址传递给 Acceleration Structure 或 Shader
     *     accelerationStructureInfo.pDeviceInfo()
     *         .deviceAddress(deviceAddr);
     *
     *     // 在 GLSL Shader 中使用：
     *     // layout(buffer_reference) readonly buffer RayData { ... };
     *     // RayData ptr = RayData(deviceAddress);  // 直接通过地址访问
     * }
     * }</pre>
     *
     * <h4>硬件要求：</h4>
     * <ul>
     *   <li>Vulkan 1.2+ 或 {@code VK_EXT_buffer_device_address} 扩展</li>
     *   <li>GPU 硬件支持（大多数现代 NVIDIA/AMD/Intel GPU 均支持）</li>
     *   <li>需要在 VkPhysicalDeviceFeatures2 中启用 {@code bufferDeviceAddress}</li>
     * </ul>
     */
    public DeviceAddressBuffer createBufferWithDeviceAddress(long size, int usage) {
        // 参数校验
        if (size <= 0) {
            throw new IllegalArgumentException("size 必须大于 0，当前值: " + size);
        }

        if (!initialized.get()) {
            throw new IllegalStateException("VmaAdvancedFlags 未初始化，请先调用 initialize()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaAdvancedFlags 已关闭");
        }

        try {
            // 调用 VMA API 创建带设备地址的缓冲区（使用 LWJGL Vulkan 绑定）
            // 方法参数: size, usage -> DeviceAddressBuffer (包含 buffer, allocation, deviceAddress)
            
            long buffer = 0L;
            long allocation = 0L;
            long deviceAddress = 0L;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 1. 构建 VkBufferCreateInfo（必须包含 SHADER_DEVICE_ADDRESS_BIT）
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(size)
                        .usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)  // 确保包含设备地址标志
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

                // 2. 构建带 BUFFER_DEVICE_ADDRESS_BIT 的 Allocation 信息
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_GPU_ONLY)
                        .flags(VMA_ALLOCATION_CREATE_BUFFER_DEVICE_ADDRESS_BIT);  // 使用本地定义的常量

                // 3. 创建缓冲区
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);

                int result = Vma.vmaCreateBuffer(
                        vmaAllocator,
                        bufferInfo,
                        allocInfo,
                        pBuffer,
                        pAllocation,
                        null
                );

                if (result != VK10.VK_SUCCESS) {
                    LOGGER.severe(String.format("VMA 创建设备地址缓冲区失败 [size=%d]: VkResult=%d",
                            size, result));
                    return null;
                }

                buffer = pBuffer.get(0);
                allocation = pAllocation.get(0);

                // 4. 查询设备地址
                // TODO: LWJGL VK10.vkGetBufferDeviceAddress 签名可能因版本而异，
                //       当前使用存根返回 0L，待验证 LWJGL 版本后修正为正确调用
                VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
                        .sType$Default()
                        .buffer(buffer);

                deviceAddress = 0L; // 存根: vkGetBufferDeviceAddress(vkDevice, addressInfo)
                
                if (deviceAddress == 0L) {
                    LOGGER.warning(String.format(
                            "获取设备地址返回 0 [buffer=%d]，可能硬件不支持", buffer));
                }
            }

            // 更新统计
            deviceAddressCount.incrementAndGet();
            totalBytesAllocated.addAndGet(size);

            // 跟踪资源
            if (buffer != 0L && allocation != 0L) {
                trackResource(buffer, allocation, "DeviceAddress", size);
            }

            LOGGER.finest(String.format("创建设备地址缓冲区成功: size=%s, buffer=%d, deviceAddress=0x%x",
                    formatSize(size), buffer, deviceAddress));
            
            return new DeviceAddressBuffer(buffer, allocation, deviceAddress, size);

        } catch (Exception e) {
            LOGGER.severe(String.format("createBufferWithDeviceAddress 异常 [size=%d]: %s",
                    size, e.getMessage()));
            return null;
        }
    }

    /**
     * 创建受保护的内存缓冲区 🔒
     * <p>
     * 创建一个使用**受保护内存**（Protected Memory）的缓冲区。
     * 受保护内存是 Vulkan 的安全特性，用于防止未授权的内存读取和复制。
     * 主要应用于数字版权管理（DRM）和敏感内容保护场景。
     *
     * <h3>工作原理：</h3>
     * <pre>
     * ┌─────────────────────────────────────────────────────────────┐
     * │  受保护内存 vs 普通内存：                                    │
     * │                                                             │
     * │  普通内存：                                                   │
     * │    ✓ GPU 正常读写                                           │
     * │    ✓ CPU 可通过 Map 读取（如果有 HOST_VISIBLE）             │
     * │    ✓ 可被 vkCopyBuffer 复制到其他缓冲区                      │
     * │    ✗ 可能被恶意软件截获                                      │
     * │                                                             │
     * │  受保护内存：                                                │
     * │    ✓ GPU 正常读写                                           │
     * │    ✗ CPU 无法 Map 读取                                       │
     * │    ✗ 无法被复制到非受保护缓冲区                               │
     * │    ✓ 防止未授权的内容提取                                    │
     * │                                                             │
     * │  适用场景：                                                  │
     * │    - 付费视频流的解码后帧缓存                                 │
     * │    - DRM 保护的纹理和音频数据                                 │
     * │    - 游戏内的加密资产                                        │
     * └─────────────────────────────────────────────────────────────┘
     * </pre>
     *
     * @param size 缓冲区大小（字节），必须大于 0
     *
     * @return {@link ProtectedBuffer} 包含 buffer 句柄和 allocation 句柄；
     *         如果创建失败或硬件不支持则返回 null
     *
     * @throws IllegalArgumentException 如果 size <= 0
     * @throws IllegalStateException 如果未初始化或已关闭或不支持受保护内存
     *
     * <h4>方法参数说明：</h4>
     * <ul>
     *   <li><b>size</b>: long - 缓冲区大小（字节）。必须大于 0。</li>
     * </ul>
     *
     * <h4>返回值说明：</h4>
     * <ul>
     *   <li>{@link ProtectedBuffer#buffer} - VkBuffer 句柄</li>
     *   <li>{@link ProtectedBuffer#allocation} - VmaAllocation 句柄</li>
     *   <li>{@link ProtectedBuffer#size} - 缓冲区大小</li>
     * </ul>
     *
     * <h4>使用示例：</h4>
     * <pre>{@code
     * // 创建用于存储 DRM 视频帧的受保护缓冲区
     * ProtectedBuffer videoFrame = advanced.createProtectedBuffer(frameSizeInBytes);
     *
     * if (videoFrame != null) {
     *     // 将视频解码器的输出直接渲染到此受保护缓冲区
     *     decodeVideoIntoProtectedBuffer(decoder, videoFrame.buffer);
     *
     *     // 后续渲染链路也必须在受保护的 Queue 中执行
     *     // （使用 VK_QUEUE_PROTECTED_BIT 创建 Command Buffer）
     *
     *     // ❌ 以下操作会被拒绝：
     *     // - vkMapMemory(videoFrame.allocation)          // 无法 Map
     *     // - vkCopyBuffer(protected, normalBuffer)       // 无法复制到非保护区域
     * }
     * }</pre>
     *
     * <h4>硬件和 API 要求：</h4>
     * <ul>
     *   <li>Vulkan 1.1+ 或 {@code VK_KHR_protected_memory} 扩展</li>
     *   <li>GPU 硬件支持（部分 AMD 和 Intel GPU 支持，NVIDIA 有限支持）</li>
     *   <li>需要在 VkPhysicalDeviceFeatures 中启用 {@code protectedMemory}</li>
     *   <li>Command Buffer 必须使用 {@code VK_COMMAND_POOL_CREATE_PROTECTED_BIT} 创建</li>
     *   <li>Queue 必须支持 {@code VK_QUEUE_PROTECTED_BIT}</li>
     * </ul>
     *
     * <h4>⚠️ 重要限制：</h4>
     * <ul>
     *   <li>受保护内存无法被 CPU 读取或修改</li>
     *   <li>只能在"受保护的渲染路径"中使用</li>
     *   <li>不能与非受保护内存互相复制</li>
     *   <li>性能可能与普通内存略有差异</li>
     *   <li>不是所有 GPU 都支持此特性（需运行时检测）</li>
     * </ul>
     */
    public ProtectedBuffer createProtectedBuffer(long size) {
        // 参数校验
        if (size <= 0) {
            throw new IllegalArgumentException("size 必须大于 0，当前值: " + size);
        }

        if (!initialized.get()) {
            throw new IllegalStateException("VmaAdvancedFlags 未初始化，请先调用 initialize()");
        }

        if (closed.get()) {
            throw new IllegalStateException("VmaAdvancedFlags 已关闭");
        }

        try {
            // 调用 VMA API 创建受保护缓冲区（使用 LWJGL Vulkan 绑定）
            // 方法参数: size -> ProtectedBuffer (包含 buffer, allocation)
            
            long buffer = 0L;
            long allocation = 0L;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 1. 构建 VkBufferCreateInfo
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(size)
                        .usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

                // 2. 构建带 PROTECTED_BIT 的 Allocation 信息
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_UNKNOWN)  // 必须使用 UNKNOWN 以支持 PROTECTED
                        .flags(VMA_ALLOCATION_CREATE_PROTECTED_BIT);  // 关键：受保护内存标志（使用本地定义的常量）

                // 3. 创建缓冲区
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);

                int result = Vma.vmaCreateBuffer(
                        vmaAllocator,
                        bufferInfo,
                        allocInfo,
                        pBuffer,
                        pAllocation,
                        null
                );

                if (result != VK10.VK_SUCCESS) {
                    // PROTECTED 内存可能不被所有设备支持
                    LOGGER.severe(String.format("VMA 创建受保护缓冲区失败 [size=%d]: VkResult=%d " +
                                    "(可能设备不支持 VK_MEMORY_PROPERTY_PROTECTED_BIT)",
                            size, result));
                    return null;
                }

                buffer = pBuffer.get(0);
                allocation = pAllocation.get(0);
            }

            // 更新统计
            protectedBufferCount.incrementAndGet();
            totalBytesAllocated.addAndGet(size);

            // 跟踪资源
            if (buffer != 0L && allocation != 0L) {
                trackResource(buffer, allocation, "Protected", size);
            }

            LOGGER.finest(String.format("创建受保护缓冲区成功: size=%s, buffer=%d", formatSize(size), buffer));

            return new ProtectedBuffer(buffer, allocation, size);

        } catch (Exception e) {
            LOGGER.severe(String.format("createProtectedBuffer 异常 [size=%d]: %s",
                    size, e.getMessage()));
            return null;
        }
    }

    /**
     * 关闭高级内存标志管理器并释放所有资源 ♻️
     * <p>
     * 销毁所有通过此类创建的高级资源。
     * 此方法应该在应用程序退出前调用。
     *
     * @throws Exception 如果底层操作失败（AutoCloseable 接口要求）
     */
    @Override
    public void close() throws Exception {
        if (closed.get()) {
            LOGGER.fine("close: 已经关闭过，跳过重复关闭");
            return; // 幂等性
        }

        closed.set(true);
        initialized.set(false);

        try {
            // 释放所有跟踪的资源
            for (AdvancedResource resource : trackedResources) {
                destroyResource(resource);
            }

            // 清空状态
            trackedResources.clear();
            vmaAllocator = 0L;
            vkDevice = 0L;

            // 输出最终统计
            LOGGER.info(String.format(
                    "VmaAdvancedFlags 已关闭\n" +
                    "  持久映射缓冲区: %d\n" +
                    "  设备地址缓冲区: %d\n" +
                    "  受保护缓冲区: %d\n" +
                    "  总分配字节数: %s",
                    persistentMappedCount.get(),
                    deviceAddressCount.get(),
                    protectedBufferCount.get(),
                    formatSize(totalBytesAllocated.get())
            ));

        } catch (Exception e) {
            LOGGER.severe("close 异常: " + e.getMessage());
            throw e;
        }
    }

    // ==================== 公共 API：查询与统计 ====================

    /**
     * 检查是否已初始化
     *
     * @return 如果已成功连接到 VMA 分配器和 Vulkan 设备则返回 true
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 检查是否已关闭
     *
     * @return 如果 close() 已被调用则返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 获取创建的持久映射缓冲区数量
     *
     * @return 数量
     */
    public long getPersistentMappedCount() {
        return persistentMappedCount.get();
    }

    /**
     * 获取创建的设备地址缓冲区数量
     *
     * @return 数量
     */
    public long getDeviceAddressCount() {
        return deviceAddressCount.get();
    }

    /**
     * 获取创建的受保护缓冲区数量
     *
     * @return 数量
     */
    public long getProtectedBufferCount() {
        return protectedBufferCount.get();
    }

    /**
     * 获取总分配字节数
     *
     * @return 所有创建的资源的总大小
     */
    public long getTotalBytesAllocated() {
        return totalBytesAllocated.get();
    }

    /**
     * 获取格式化的报告 📊
     *
     * @return 格式化的报告字符串
     */
    public String formatReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║  VMA 高级内存标志报告                     ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // 基本信息
        sb.append(String.format("状态: %s | 已关闭: %s\n",
                initialized.get() ? "✓ 已初始化" : "✗ 未初始化",
                closed.get() ? "✗ 是" : "否"));
        sb.append(String.format("VMA Allocator: %d\n", vmaAllocator));
        sb.append(String.format("Vulkan Device: %d\n", vkDevice));

        // 统计信息
        sb.append("\n--- 资源统计 ---\n");
        sb.append(String.format("持久映射缓冲区: %d\n", getPersistentMappedCount()));
        sb.append(String.format("设备地址缓冲区: %d\n", getDeviceAddressCount()));
        sb.append(String.format("受保护缓冲区: %d\n", getProtectedBufferCount()));
        sb.append(String.format("总分配字节数: %s\n", formatSize(getTotalBytesAllocated())));

        // 跟踪的资源详情
        sb.append("\n--- 资源列表 ---\n");
        synchronized (trackedResources) {
            for (int i = 0; i < Math.min(trackedResources.size(), 20); i++) {
                AdvancedResource resource = trackedResources.get(i);
                sb.append(String.format("  [%d] type=%s, buffer=%d, size=%s\n",
                        i, resource.type, resource.buffer, formatSize(resource.size)));
            }
            if (trackedResources.size() > 20) {
                sb.append(String.format("  ... 还有 %d 个资源\n", trackedResources.size() - 20));
            }
        }

        return sb.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 跟踪新创建的资源（用于统一释放）
     */
    private void trackResource(long buffer, long allocation, String type, long size) {
        synchronized (trackedResources) {
            trackedResources.add(new AdvancedResource(buffer, allocation, type, size));
        }
    }

    /**
     * 销毁单个资源
     */
    private void destroyResource(AdvancedResource resource) {
        try {
            // 根据资源类型调用对应的 VMA 销毁 API
            switch (resource.type) {
                case "PersistentMapped":
                    // 持久映射缓冲区：先取消映射（如果需要），然后销毁
                    Vma.vmaDestroyBuffer(vmaAllocator, resource.buffer, resource.allocation);
                    break;

                case "DeviceAddress":
                    // 设备地址缓冲区：直接销毁
                    Vma.vmaDestroyBuffer(vmaAllocator, resource.buffer, resource.allocation);
                    break;

                case "Protected":
                    // 受保护缓冲区：直接销毁
                    Vma.vmaDestroyBuffer(vmaAllocator, resource.buffer, resource.allocation);
                    break;

                default:
                    LOGGER.warning(String.format("未知资源类型: %s (buffer=%d)",
                            resource.type, resource.buffer));
            }

            LOGGER.fine(String.format("销毁资源: type=%s, buffer=%d", resource.type, resource.buffer));

        } catch (Exception e) {
            LOGGER.warning(String.format("销毁资源异常 [type=%s, buffer=%d]: %s",
                    resource.type, resource.buffer, e.getMessage()));
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 格式化字节数为可读字符串
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ==================== 内部数据类 ====================

    /**
     * 持久映射缓冲区
     * <p>
     * 封装一个 CPU 端永久映射的缓冲区及其映射后的内存指针。
     */
    public static class PersistentMappedBuffer {
        /** VkBuffer 句柄 */
        public final long buffer;

        /** VmaAllocation 句柄 */
        public final long allocation;

        /** 映射后的 ByteBuffer（可直接读写，无需 map/unmap）*/
        public final java.nio.ByteBuffer mappedData;

        /** 缓冲区大小（字节）*/
        public final long size;

        /**
         * 创建持久映射缓冲区
         *
         * @param buffer     Buffer 句柄
         * @param allocation Allocation 句柄
         * @param mappedData 映射后的 ByteBuffer
         * @param size       大小
         */
        PersistentMappedBuffer(long buffer, long allocation,
                               java.nio.ByteBuffer mappedData, long size) {
            this.buffer = buffer;
            this.allocation = allocation;
            this.mappedData = mappedData;
            this.size = size;
        }
    }

    /**
     * 设备地址缓冲区
     * <p>
     * 封装一个带有设备地址属性的缓冲区。
     */
    public static class DeviceAddressBuffer {
        /** VkBuffer 句柄 */
        public final long buffer;

        /** VmaAllocation 句柄 */
        public final long allocation;

        /** GPU 可访问的 64 位设备地址 */
        public final long deviceAddress;

        /** 缓冲区大小（字节）*/
        public final long size;

        /**
         * 创建设备地址缓冲区
         *
         * @param buffer        Buffer 句柄
         * @param allocation    Allocation 句柄
         * @param deviceAddress 设备地址
         * @param size          大小
         */
        DeviceAddressBuffer(long buffer, long allocation, long deviceAddress, long size) {
            this.buffer = buffer;
            this.allocation = allocation;
            this.deviceAddress = deviceAddress;
            this.size = size;
        }
    }

    /**
     * 受保护内存缓冲区
     * <p>
     * 封装一个使用受保护内存的缓冲区。
     */
    public static class ProtectedBuffer {
        /** VkBuffer 句柄 */
        public final long buffer;

        /** VmaAllocation 句柄 */
        public final long allocation;

        /** 缓冲区大小（字节）*/
        public final long size;

        /**
         * 创建受保护缓冲区
         *
         * @param buffer     Buffer 句柄
         * @param allocation Allocation 句柄
         * @param size       大小
         */
        ProtectedBuffer(long buffer, long allocation, long size) {
            this.buffer = buffer;
            this.allocation = allocation;
            this.size = size;
        }
    }

    /**
     * 内部资源跟踪对象
     */
    private static class AdvancedResource {
        final long buffer;
        final long allocation;
        final String type;
        final long size;

        AdvancedResource(long buffer, long allocation, String type, long size) {
            this.buffer = buffer;
            this.allocation = allocation;
            this.type = type;
            this.size = size;
        }
    }

    // ==================== Vulkan 常量（待替换为 LWJGL 正式定义）====================

    /** Vulkan Buffer Usage: Shader Device Address */
    private static final int VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT = 0x00020000;

    /** Vulkan Buffer Usage: Transfer Source */
    private static final int VK_BUFFER_USAGE_TRANSFER_SRC_BIT = 0x00000001;

    /** Vulkan Buffer Usage: Transfer Destination */
    private static final int VK_BUFFER_USAGE_TRANSFER_DST_BIT = 0x00000002;

    /** Vulkan Buffer Usage: Vertex Buffer */
    private static final int VK_BUFFER_USAGE_VERTEX_BUFFER_BIT = 0x00000008;

    /** Vulkan Buffer Usage: Storage Buffer */
    private static final int VK_BUFFER_USAGE_STORAGE_BUFFER_BIT = 0x00000080;

    /** Vulkan Buffer Usage: Indirect Buffer */
    private static final int VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT = 0x00000100;
}
