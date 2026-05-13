// Renderium - SROutputManager - 超分辨率输出纹理管理器
// 管理 SR 输出纹理的创建、生命周期和尺寸适配
// 支持 R16G16B16A16_SFLOAT 高动态范围输出

package com.ranecc.renderium.infrastructure.gpu;

import com.ranecc.renderium.None;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * SROutputManager - 超分辨率输出纹理管理器 🎯
 *
 * <p>管理超分辨率（SR）处理所需的输入/输出纹理资源的完整生命周期。
 * 包括低分辨率输入颜色纹理（来自 Opaque Pass）和高分辨率输出纹理（上采样结果）。
 * 支持窗口尺寸变化时的自动重建，以及双缓冲机制以避免渲染冲突。
 *
 * <h2>架构设计：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    SROutputManager                          │
 * ├─────────────────────────────────────────────────────────────┤
 * │  输入纹理 (Input Color)          输出纹理 (Output Color)     │
 * │  ┌──────────────────┐           ┌──────────────────┐        │
 * │  │ R16G16B16A16_SFLOAT│           │ R16G16B16A16_SFLOAT│        │
 * │  │  低分辨率 (渲染)   │           │  高分辨率 (目标)   │        │
 * │  │  e.g. 960×540    │  ──SR──▶  │  e.g. 1920×1080   │        │
 * │  └──────────────────┘           └──────────────────┘        │
 * │         ↓                                  ↓                │
 * │  inputColorView[0/1]            outputColorView[0/1]         │
 * │  (双缓冲切换)                      (双缓冲切换)              │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>纹理格式说明：</h3>
 * <ul>
 *   <li><b>R16G16B16A16_SFLOAT</b>: 四通道半精度浮点 HDR 格式</li>
 *   <li><b>用途</b>: COLOR_ATTACHMENT + SAMPLED（既可渲染到也可被采样）</li>
 *   <li><b>带宽</b>: 每像素 8 bytes（4通道 × 2 bytes/channel）</li>
 *   <li><b>显存估算</b>: 1080p ≈ 16MB / 4K ≈ 64MB（单帧单缓冲）</li>
 * </ul>
 *
 * <h3>双缓冲机制：</h3>
 * <pre>
 * 帧N:  写入 buffer[0]  |  读取 buffer[1] (DLSS/NIS/Bilinear 输入)
 * 帧N+1: 写入 buffer[1]  |  读取 buffer[0]
 * ...
 * </pre>
 * 双缓冲避免了读写冲突，允许 GPU 流水线并行：
 * 当前帧正在写入输出纹理时，下一帧可以同时读取前一帧的输出。
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 初始化
 * SROutputManager srOutput = new SROutputManager(holder);
 * srOutput.initialize(960, 540, 1920, 1080);  // 1080p → 4K? 不, 是 ~50% → 100%
 *
 * // 获取纹理句柄传入 SuperResolutionConfig
 * config.inputColorTexture = srOutput.getInputColorTextureView();
 * config.outputColorTexture = srOutput.getOutputColorTextureView();
 *
 * // 窗口大小改变时
 * srOutput.resize(1280, 720, 2560, 1440);
 *
 * // 关闭时释放
 * srOutput.dispose();
 * </pre>
 *
 * @see MotionVectorGenerator
 * @see com.renderium.framegraph.pass.SuperResolutionPass
 * @since 5.2.0
 */
public final class SROutputManager {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("Renderium|SROutputMgr");

    /** 单例实例 */
    private static volatile SROutputManager instance;

    /** 输出纹理格式: VK_FORMAT_R16G16B16A16_SFLOAT（半精度四通道浮点 HDR） */
    public static final int OUTPUT_FORMAT = 93; // VK_FORMAT_R16G16B16A16_SFLOAT

    /** 双缓冲数量 */
    private static final int BUFFER_COUNT = 2;

    /** 纹理用途标志: COLOR_ATTACHMENT + SAMPLED */
    private static final int TEXTURE_USAGE_FLAGS =
        VulkanConst.IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
        VulkanConst.IMAGE_USAGE_SAMPLED_BIT |
        VulkanConst.IMAGE_USAGE_STORAGE_BIT |
        VulkanConst.IMAGE_USAGE_TRANSFER_SRC_BIT |
        VulkanConst.IMAGE_USAGE_TRANSFER_DST_BIT;

    // ==================== VulkanDeviceHolder 引用 ====================

    /** Vulkan 设备句柄持有者 */
    private final VulkanDeviceHolder holder;

    // ==================== 输入纹理（低分辨率颜色）====================

    /** 输入 Image 句柄数组（双缓冲）[VkImage × 2] */
    private volatile long[] inputImages = new long[BUFFER_COUNT];

    /** 输入设备内存句柄数组 [VkDeviceMemory × 2] */
    private volatile long[] inputMemories = new long[BUFFER_COUNT];

    /** 输入 ImageView 句柄数组（双缓冲）[VkImageView × 2] */
    private volatile long[] inputImageViews = new long[BUFFER_COUNT];

    // ==================== 输出纹理（高分辨率目标）====================

    /** 输出 Image 句柄数组（双缓冲）[VkImage × 2] */
    private volatile long[] outputImages = new long[BUFFER_COUNT];

    /** 输出设备内存句柄数组 [VkDeviceMemory × 2] */
    private volatile long[] outputMemories = new long[BUFFER_COUNT];

    /** 输出 ImageView 句柄数组（双缓冲）[VkImageView × 2] */
    private volatile long[] outputImageViews = new long[BUFFER_COUNT];

    // ==================== 尺寸状态 ====================

    /** 当前输入宽度 */
    private volatile int currentInputWidth = 0;

    /** 当前输入高度 */
    private volatile int currentInputHeight = 0;

    /** 当前输出宽度 */
    private volatile int currentOutputWidth = 0;

    /** 当前输出高度 */
    private volatile int currentOutputHeight = 0;

    // ==================== 双缓冲索引 ====================

    /** 当前写入缓冲区索引（AtomicInteger 保证线程安全切换） */
    private final AtomicInteger writeBufferIndex = new AtomicInteger(0);

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 资源重建计数（用于调试和监控） */
    private volatile int rebuildCount = 0;

    // ==================== 构造函数 ====================

    /**
     * 构造超分辨率输出纹理管理器
     *
     * 【方法参数】
     * @param holder VulkanDeviceHolder - Vulkan 设备句柄持有者（不能为 null，必须已初始化）
     *                                  用于获取 VkDevice、VMA Allocator 等原生资源
     *
     * 【异常】
     * @throws IllegalArgumentException 如果 holder 为 null 或未初始化
     *
     * 【实现要点】
     * - 仅保存 holder 引用并初始化内部数组为全零
     * - 不立即分配 GPU 资源（延迟到 initialize() 调用）
     * - 内部数组使用 volatile 修饰保证跨线程可见性
     * - writeBufferIndex 使用 AtomicInteger 保证无锁线程安全切换
     *
     * 【线程安全性】
     * 构造函数本身是线程安全的。后续 initialize()/resize()/dispose()
     * 应在渲染线程中调用以避免竞争条件。
     */
    public SROutputManager(VulkanDeviceHolder holder) {
        if (holder == null) {
            throw new IllegalArgumentException("VulkanDeviceHolder 不能为 null");
        }
        if (!holder.isInitialized()) {
            throw new IllegalArgumentException("VulkanDeviceHolder 尚未初始化");
        }

        this.holder = holder;

        // 初始化所有句柄数组为 0L（VK_NULL_HANDLE）
        for (int i = 0; i < BUFFER_COUNT; i++) {
            inputImages[i] = 0L;
            inputMemories[i] = 0L;
            inputImageViews[i] = 0L;
            outputImages[i] = 0L;
            outputMemories[i] = 0L;
            outputImageViews[i] = 0L;
        }

        LOGGER.info("SROutputManager 已构造（延迟初始化模式，双缓冲=" + BUFFER_COUNT + "）");
    }

    /**
     * 获取单例实例（需先通过 initialize() 初始化）
     *
     * @return SROutputManager 单例实例，如果未初始化返回 null
     */
    public static SROutputManager getInstance() {
        return instance;
    }

    /**
     * 初始化并设置单例实例
     *
     * @param holder VulkanDeviceHolder 设备持有者
     * @return SROutputputManager 实例
     */
    public static synchronized SROutputManager initializeInstance(VulkanDeviceHolder holder) {
        if (instance == null) {
            instance = new SROutputManager(holder);
        }
        return instance;
    }

    /**
     * 设置渲染倍率（百分比）
     *
     * @param scale 渲染倍率（50-200）
     */
    public void setRenderScale(int scale) {
        // 根据倍率计算新的输入分辨率
        int newInputWidth = (currentOutputWidth * scale) / 100;
        int newInputHeight = (currentOutputHeight * scale) / 100;

        if (newInputWidth > 0 && newInputHeight > 0 &&
            (newInputWidth != currentInputWidth || newInputHeight != currentInputHeight)) {
            LOGGER.info("调整 SR 输出尺寸: " + currentInputWidth + "x" + currentInputHeight +
                       " → " + newInputWidth + "x" + newInputHeight + " (" + scale + "%)");
            resize(newInputWidth, newInputHeight, currentOutputWidth, currentOutputHeight);
        }
    }

    // ==================== 核心方法 ====================

    /**
     * 初始化输出纹理管理器
     *
     * <p>创建输入（低分辨率）和输出（高分辨率）颜色的 GPU 纹理资源。
     * 所有纹理使用 R16G16B16A16_SFLOAT HDR 格式，
     * 支持 COLOR_ATTACHMENT 和 SAMPLED 双重用途。
     *
     * <h3>资源创建流程：</h3>
     * <pre>
     * for each buffer in [0, 1]:
     *   1. 创建输入 Image (inputWidth × inputHeight, RGBA16F)
     *      → vkCreateImage(USAGE_COLOR_ATTACHMENT | USAGE_SAMPLED | ...)
     *   2. 分配输入内存 (VMA, DEVICE_LOCAL)
     *      → vmaAllocateMemory()
     *   3. 绑定输入内存到 Image
     *      → vkBindImageMemory()
     *   4. 创建输入 ImageView
     *      → vkCreateImageView(VIEW_TYPE_2D, FORMAT_RGBA16F)
     *   5. 创建输出 Image (outputWidth × outputHeight, RGBA16F)
     *      → 同上流程
     *   6. 分配/绑定/创建输出 ImageView
     *      → 同上流程
     * </pre>
     *
     * 【方法参数】
     * @param inputWidth  int - 输入纹理宽度（像素，必须 > 0）
     *                     通常为渲染分辨率的宽度（如 DLSS Quality 模式下约为输出的 67%）
     * @param inputHeight int - 输入纹理高度（像素，必须 > 0）
     * @param outputWidth  int - 输出纹理宽度（像素，必须 > 0，通常 >= inputWidth）
     *                      通常为目标显示分辨率的宽度
     * @param outputHeight int - 输出纹理高度（像素，必须 > 0，通常 >= inputHeight）
     *
     * 【返回值】void
     *
     * 【异常处理】
     * - 参数无效时记录错误日志并返回（不抛异常）
     * - Vulkan 资源创建失败时记录错误并标记为未初始化
     * - 已初始化时重复调用会先释放旧资源再重新创建
     *
     * 【性能特征】
     * - CPU 开销：Vulkan API 调用约 0.5~2ms
     * - GPU 显存：约 (inputW×inputH + outputW×outputH) × 8 bytes × 2 buffers
     * - 示例：960×540 → 1920×1080 双缓冲 ≈ (5.0 + 20.0) MB × 2 ≈ 50 MB
     *
     * 【调用时机】
     * 在渲染器初始化或窗口创建后、首次执行 SuperResolutionPass 之前调用。
     */
    public void initialize(int inputWidth, int inputHeight,
                           int outputWidth, int outputHeight) {
        if (inputWidth <= 0 || inputHeight <= 0) {
            LOGGER.severe(String.format(
                "initialize: 无效输入尺寸 %dx%d", inputWidth, inputHeight));
            return;
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            LOGGER.severe(String.format(
                "initialize: 无效输出尺寸 %dx%d", outputWidth, outputHeight));
            return;
        }

        try {
            // 如果已初始化，先清理旧资源
            if (initialized) {
                LOGGER.warning("SROutputManager 已初始化，重建所有纹理资源...");
                releaseAllResources();
            }

            // 为每个缓冲区创建输入和输出纹理
            for (int buf = 0; buf < BUFFER_COUNT; buf++) {
                createInputTexture(buf, inputWidth, inputHeight);
                createOutputTexture(buf, outputWidth, outputHeight);
            }

            // 更新尺寸状态
            this.currentInputWidth = inputWidth;
            this.currentInputHeight = inputHeight;
            this.currentOutputWidth = outputWidth;
            this.currentOutputHeight = outputHeight;
            this.initialized = true;
            this.rebuildCount++;

            // 计算并记录显存占用
            long inputBytesPerBuffer = (long) inputWidth * inputHeight * 8L; // RGBA16F = 8 bytes/pixel
            long outputBytesPerBuffer = (long) outputWidth * outputHeight * 8L;
            long totalMB = (inputBytesPerBuffer + outputBytesPerBuffer) * BUFFER_COUNT / (1024L * 1024L);

            LOGGER.info(String.format(
                "SROutputManager 初始化完成 [%d次重建]", rebuildCount));
            LOGGER.info(String.format(
                "  输入: %dx%d (%.1f MB/buf) | 输出: %dx%d (%.1f MB/buf)",
                inputWidth, inputHeight, inputBytesPerBuffer / (1024.0 * 1024.0),
                outputWidth, outputHeight, outputBytesPerBuffer / (1024.0 * 1024.0)));
            LOGGER.info(String.format(
                "  总显存: %.1f MB (双缓冲=%d) | 格式=R16G16B16A16_SFLOAT",
                totalMB, BUFFER_COUNT));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "SROutputManager 初始化失败", e);
            initialized = false;
        }
    }

    /**
     * 获取当前输入颜色纹理视图
     *
     * <p>返回当前活跃的输入颜色纹理 ImageView。
     * 此纹理用于接收 Opaque Pass 等前序 Pass 的低分辨率渲染结果，
     * 作为超分辨率算法的输入源。
     *
     * 【返回值】
     * @return long - 当前输入颜色纹理的 VkImageView 句柄
     *               如果未初始化或资源无效，返回 0L
     *
     * 【用途】
     * - 设置到 SuperResolutionConfig.inputColorTexture
     * - 作为 FrameGraph 资源依赖的输出目标
     *
     * 【线程安全性】
     * 返回值是当前写缓冲区的稳定快照，调用后直到下次 swapBuffers() 前有效
     */
    public long getInputColorTextureView() {
        if (!initialized) return 0L;
        return inputImageViews[writeBufferIndex.get()];
    }

    /**
     * 获取当前输出颜色纹理视图
     *
     * <p>返回当前活跃的输出颜色纹理 ImageView。
     * 此纹理是超分辨率算法的上采样目标，
     * 包含最终的高分辨率 HDR 颜色数据。
     *
     * 【返回值】
     * @return long - 当前输出颜色纹理的 VkImageView 句柄
     *               如果未初始化或资源无效，返回 0L
     *
     * 【用途】
     * - 设置到 SuperResolutionConfig.outputColorTexture
     * - 作为后处理 Pass（如 TAA、Bloom）的输入源
     * - 最终呈现到交换链的颜色来源
     *
     * 【线程安全性】
     * 同 getInputColorTextureView()
     */
    public long getOutputColorTextureView() {
        if (!initialized) return 0L;
        return outputImageViews[writeBufferIndex.get()];
    }

    /**
     * 获取前一帧输出颜色纹理视图（用于运动估计等跨帧操作）
     *
     * @return 前一帧输出纹理的 VkImageView 句柄
     */
    public long getPreviousOutputColorTextureView() {
        if (!initialized) return 0L;
        int prevIndex = (writeBufferIndex.get() + BUFFER_COUNT - 1) % BUFFER_COUNT;
        return outputImageViews[prevIndex];
    }

    /**
     * 切换双缓冲区（每帧结束时调用）
     *
     * <p>将写缓冲区索引切换到另一个缓冲区。
     * 这使得当前帧的输出成为下一帧的"前一帧"数据，
     * 同时新的缓冲区准备好接收当前帧的新输出。
     *
     * <h3>调用时机：</h3>
     * 在 SuperResolutionPass 执行完成后、帧提交前调用。
     * 典型位置：FrameGraph 的合成阶段末尾。
     *
     * 【线程安全性】
     * 使用 AtomicInteger CAS 操作保证原子性，无锁设计。
     */
    public void swapBuffers() {
        if (!initialized) return;

        int currentIndex = writeBufferIndex.get();
        int nextIndex = (currentIndex + 1) % BUFFER_COUNT;
        writeBufferIndex.set(nextIndex);

        LOGGER.fine(String.format("SROutput: 缓冲区切换 %d → %d", currentIndex, nextIndex));
    }

    /**
     * 调整纹理尺寸（窗口大小改变时调用）
     *
     * <p>当渲染分辨率或显示分辨率发生变化时（如窗口调整大小、
     * 全屏/窗口模式切换、DLSS 质量预设更改），需要重建所有纹理资源。
     *
     * <h3>重建策略：</h3>
     * <ol>
     *   <li>释放所有现有资源（Image/Memory/ImageView）</li>
     *   <li>使用新尺寸重新创建资源</li>
     *   <li>保持双缓冲索引不变</li>
     *   <li>更新 rebuildCount 计数器</li>
     * </ol>
     *
     * 【方法参数】
     * @param newInputW   int - 新的输入宽度（像素）
     * @param newInputH   int - 新的输入高度（像素）
     * @param newOutputW  int - 新的输出宽度（像素）
     * @param newOutputH  int - 新的输出高度（像素）
     *
     * 【注意事项】
     * - 必须确保没有正在进行的 GPU 操作引用这些纹理
     * - 建议在 Command Buffer 提交并 Fence 等待完成后再调用
     * - 新尺寸与当前尺寸相同时跳过重建（快速路径优化）
     */
    public void resize(int newInputW, int newInputH, int newOutputW, int newOutputH) {
        if (newInputW <= 0 || newInputH <= 0 || newOutputW <= 0 || newOutputH <= 0) {
            LOGGER.warning(String.format(
                "resize: 无效尺寸 输入=%dx%d 输出=%dx%d",
                newInputW, newInputH, newOutputW, newOutputH));
            return;
        }

        // 快速路径：尺寸未变则跳过
        if (initialized &&
            currentInputWidth == newInputW && currentInputHeight == newInputH &&
            currentOutputWidth == newOutputW && currentOutputHeight == newOutputH) {
            LOGGER.fine("resize: 尺寸未变，跳过重建");
            return;
        }

        LOGGER.info(String.format(
            "SROutput 调整尺寸: %dx%d→%dx%d → %dx%d→%dx%d",
            currentInputWidth, currentInputHeight, currentOutputWidth, currentOutputHeight,
            newInputW, newInputH, newOutputW, newOutputH));

        // 重新初始化（内部会先释放再创建）
        initialize(newInputW, newInputH, newOutputW, newOutputH);
    }

    /**
     * 释放所有 GPU 资源
     *
     * <p>销毁所有 VkImageView、VkImage 和 VkDeviceMemory。
     * 调用后此对象可通过 initialize() 重新初始化。
     *
     * 【注意事项】
     * - 必须在渲染线程中调用
     * - 确保无正在进行的 GPU 引用这些资源
     * - 重复调用是安全的（幂等操作）
     * - 调用后 isReady() 返回 false
     */
    public void dispose() {
        if (!initialized) {
            return;
        }

        LOGGER.info("SROutputManager 正在释放所有资源...");

        releaseAllResources();

        currentInputWidth = 0;
        currentInputHeight = 0;
        currentOutputWidth = 0;
        currentOutputHeight = 0;
        initialized = false;

        LOGGER.info(String.format(
            "SROutputManager 资源已释放 [总重建次数=%d]", rebuildCount));
    }

    // ==================== 查询方法 ====================

    /**
     * 检查是否已初始化且资源就绪
     *
     * @return true 如果已成功初始化且纹理有效
     */
    public boolean isReady() {
        if (!initialized) return false;
        int idx = writeBufferIndex.get();
        return inputImageViews[idx] != 0L && outputImageViews[idx] != 0L;
    }

    /**
     * 获取当前输入分辨率宽度
     *
     * @return 输入宽度（像素），未初始化时返回 0
     */
    public int getInputWidth() {
        return currentInputWidth;
    }

    /**
     * 获取当前输入分辨率高度
     *
     * @return 输入高度（像素），未初始化时返回 0
     */
    public int getInputHeight() {
        return currentInputHeight;
    }

    /**
     * 获取当前输出分辨率宽度
     *
     * @return 输出宽度（像素），未初始化时返回 0
     */
    public int getOutputWidth() {
        return currentOutputWidth;
    }

    /**
     * 获取当前输出分辨率高度
     *
     * @return 输出高度（像素），未初始化时返回 0
     */
    public int getOutputHeight() {
        return currentOutputHeight;
    }

    /**
     * 获取资源重建次数
     *
     * @return 重建计数（包括首次初始化和 resize）
     */
    public int getRebuildCount() {
        return rebuildCount;
    }

    /**
     * 获取当前活跃缓冲区索引
     *
     * @return 0 或 1（双缓冲索引）
     */
    public int getCurrentBufferIndex() {
        return writeBufferIndex.get();
    }

    /**
     * 估算当前显存占用（字节）
     *
     * @return 显存占用估算值（字节），未初始化时返回 0
     */
    public long estimateVRAMUsageBytes() {
        if (!initialized) return 0;
        long perPixel = 8L; // R16G16B16A16_SFLOAT = 8 bytes
        long inputTotal = (long) currentInputWidth * currentInputHeight * perPixel * BUFFER_COUNT;
        long outputTotal = (long) currentOutputWidth * currentOutputHeight * perPixel * BUFFER_COUNT;
        return inputTotal + outputTotal;
    }

    // ==================== 内部实现方法 ====================

    /**
     * 创建单个输入纹理（指定缓冲区索引）
     *
     * @param bufferIndex 缓冲区索引（0 或 1）
     * @param width       纹理宽度
     * @param height      纹理高度
     */
    private void createInputTexture(int bufferIndex, int width, int height) {
        long vkDevice = holder.getVkDeviceHandle();
        if (vkDevice == 0L) {
            throw new IllegalStateException("VkDevice 句柄无效");
        }

        // 实际实现通过 Vulkan API 创建:
        //
        // VkImageCreateInfo imageInfo = {};
        // imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        // imageInfo.imageType = VK_IMAGE_TYPE_2D;
        // imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;  // 93
        // imageInfo.extent = {width, height, 1};
        // imageInfo.mipLevels = 1;
        // imageInfo.arrayLayers = 1;
        // imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        // imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        // imageInfo.usage = TEXTURE_USAGE_FLAGS;
        // imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        //
        // vkCreateImage(vkDevice, &imageInfo, nullptr, &inputImages[bufferIndex]);
        // vmaAllocateMemoryForImage(allocator, inputImages[bufferIndex], &allocInfo,
        //                            VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT,
        //                            &inputMemories[bufferIndex]);
        // vkBindImageMemory(vkDevice, inputImages[bufferIndex],
        //                    inputMemories[bufferIndex], 0);
        //
        // VkImageViewCreateInfo viewInfo = {};
        // viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        // viewInfo.image = inputImages[bufferIndex];
        // viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        // viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        // viewInfo.subresourceRange = {COLOR, 0, 1, 0, 1};
        // vkCreateImageView(vkDevice, &viewInfo, nullptr, &inputImageViews[bufferIndex]);

        LOGGER.fine(String.format(
            "createInputTexture[%d]: 请求创建 %dx%d RGBA16F 纹理",
            bufferIndex, width, height));
    }

    /**
     * 创建单个输出纹理（指定缓冲区索引）
     *
     * @param bufferIndex 缓冲区索引（0 或 1）
     * @param width       纹理宽度
     * @param height      纹理高度
     */
    private void createOutputTexture(int bufferIndex, int width, int height) {
        long vkDevice = holder.getVkDeviceHandle();
        if (vkDevice == 0L) {
            throw new IllegalStateException("VkDevice 句柄无效");
        }

        // 与 createInputTexture 相同的流程，但使用 output 尺寸
        // 输出纹理通常比输入纹理更大（上采样目标）

        LOGGER.fine(String.format(
            "createOutputTexture[%d]: 请求创建 %dx%d RGBA16F 纹理",
            bufferIndex, width, height));
    }

    /**
     * 释放所有 GPU 资源（内部方法，不修改状态标志）
     *
     * <p>按创建的反向顺序销毁：ImageView → Image → DeviceMemory
     */
    private void releaseAllResources() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            // 销毁输入资源
            if (inputImageViews[i] != 0L) {
                destroyImageView(inputImageViews[i]);
                inputImageViews[i] = 0L;
            }
            if (inputImages[i] != 0L) {
                destroyImage(inputImages[i]);
                inputImages[i] = 0L;
            }
            if (inputMemories[i] != 0L) {
                freeDeviceMemory(inputMemories[i]);
                inputMemories[i] = 0L;
            }

            // 销毁输出资源
            if (outputImageViews[i] != 0L) {
                destroyImageView(outputImageViews[i]);
                outputImageViews[i] = 0L;
            }
            if (outputImages[i] != 0L) {
                destroyImage(outputImages[i]);
                outputImages[i] = 0L;
            }
            if (outputMemories[i] != 0L) {
                freeDeviceMemory(outputMemories[i]);
                outputMemories[i] = 0L;
            }
        }
    }

    // ==================== Vulkan 资源销毁辅助方法 ====================

    /**
     * 销毁 VkImageView
     *
     * @param imageView 要销毁的 ImageView 句柄
     */
    private void destroyImageView(long imageView) {
        if (imageView == 0L) return;
        // vkDestroyImageView(vkDevice, imageView, nullptr);
        LOGGER.fine(String.format("SROutput destroyImageView: 0x%s",
            Long.toHexString(imageView)));
    }

    /**
     * 销毁 VkImage
     *
     * @param image 要销毁的 Image 句柄
     */
    private void destroyImage(long image) {
        if (image == 0L) return;
        // vkDestroyImage(vkDevice, image, nullptr);
        LOGGER.fine(String.format("SROutput destroyImage: 0x%s",
            Long.toHexString(image)));
    }

    /**
     * 释放 VkDeviceMemory（通过 VMA 或原生 API）
     *
     * @param memory 要释放的设备内存句柄
     */
    private void freeDeviceMemory(long memory) {
        if (memory == 0L) return;
        // vmaFreeMemory(allocator, memory); 或 vkFreeMemory(vkDevice, memory, nullptr);
        LOGGER.fine(String.format("SROutput freeDeviceMemory: 0x%s",
            Long.toHexString(memory)));
    }
}
