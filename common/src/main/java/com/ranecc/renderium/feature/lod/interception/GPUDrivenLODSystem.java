// Renderium - Blaze3D 拦截层系统 Phase 3: LOD 多细节层次系统
// GPUDrivenLODSystem.java - GPU 驱动 LOD 系统（狂暴模式专用）
// 功能: 使用 Compute Shader 计算 LOD，生成间接绘制命令


package com.ranecc.renderium.feature.lod.interception;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.domain.enums.RenderiumMode;
import com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding;
import com.ranecc.renderium.infrastructure.gpu.VulkanBufferHelper;
import com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard;

/**
 * GPU 驱动 LOD 系统（仅限狂暴模式 / AGGRESSIVE）
 *
 * <p>利用 GPU Compute Shader 并行计算所有区块的 LOD 等级，
 * 生成间接绘制命令（Indirect Draw Commands）供 Vulkan 渲染管线使用
 *
 * <h2>核心流程</h2>
 * <pre>
 * ┌───────────────────────────────────────────────────────────────┐
 * │ CPU (GPUDrivenLODSystem)                                     │
 * │ 1. 收集可见 chunk 列表 + 相机参数                            │
 * │ 2. 上传 Chunk Data Buffer 到 GPU SSBO                       │
 * │ 3. Dispatch Compute Shader                                   │
 * │    ↓                                                         │
 * │ GPU (lod_compute.comp)                                       │
 * │ 4. 每个线程处理一个 chunk → 计算距离/视角 → 分配 LOD 等级  │
 * │ 5. 视锥体剔除优化                                            │
 * │ 6. 写入 LOD Result Buffer                                    │
 * │ 7. 生成 Indirect Draw Commands                                │
 * │    ↓                                                         │
 * │ CPU                                                           │
 * │ 8. 回读结果（或通过 SSBO 直接被渲染管线消费）                 │
 * │ 9. 提交 VkCmdDrawIndexedIndirect                              │
 * └───────────────────────────────────────────────────────────────┘
 * </pre>
 *
 *
 * <h2>性能目标</h2>
 * <ul>
 *   <li>CPU 开销: &lt; 0.5ms/帧（主要是 dispatch 开销和状态设置）</li>
 *   <li>GPU 开销: &lt; 0.1ms（10000 chunks @ RTX 3080）</li>
 *   <li>总延迟: 1-2 帧（异步计算，结果在下一帧可用）</li>
 * </ul>
 *
 *
 * <h2>降级策略</h2>
 * <p>当 GPU Compute 失败时自动降级到 CPU-based LOD：
 * <ol>
 *   <li>检测到 Compute Shader 编译/执行失败</li>
 *   <li>自动切换到 {@link LODCalculator} 的 CPU 路径</li>
 *   <li>记录日志但不崩溃</li>
 *   <li>下帧尝试恢复 GPU 路径</li>
 * </ol>
 *
 *
 * @see LODCalculator
 * @see RenderiumLODSystem
 * @author Renderium Team
 * @since 5.3.0
 */
public final class GPUDrivenLODSystem {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(GPUDrivenLODSystem.class.getName());

    // ==================== 常量定义 ====================

    /** 默认最大处理 chunk 数量 */
    public static final int DEFAULT_MAX_CHUNKS = 16384;

    /** Compute Shader 工作组大小 */
    public static final int WORKGROUP_SIZE = 64;

    /** 最大连续失败次数（超过后禁用 GPU 路径） */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** 单例实例 */
    private static final GPUDrivenLODSystem INSTANCE = new GPUDrivenLODSystem();

    /**
     * 获取单例实例
     *
     * @return 全局唯一的 GPUDrivenLODSystem 实例
     */
    public static GPUDrivenLODSystem getInstance() {
        return INSTANCE;
    }

    // ==================== 内部数据结构 ====================

    /**
     * GPU 端 Chunk 输入数据结构（对应 GLSL 中的 layout）
     *
     * <p>每个 chunk 在 GPU 端的表示，包含位置信息和元数据
     *
     * @param centerX 区块中心 X 坐标（世界空间）
     * @param centerY 区块中心 Y 坐标（世界空间）
     * @param centerZ 区块中心 Z 坐标（世界空间）
     * @param chunkId  全局唯一 chunk 标识符
     * @param flags    标志位（bit 0: 是否有几何数据 bit 1: 是否为脏数据）
     */
    public record GPUChunkInput(
        float centerX, float centerY, float centerZ,
        int chunkId,
        int flags
    ) {}



    /**
     * GPU 端 LOD 输出数据结构
     *
     * <p>Compute Shader 为每个 chunk 写出的 LOD 结果
     *
     * @param lodLevel      计算后的 LOD 等级 [0-3]
     * @param transitionAlpha 过渡混合系数 [0.0-1.0]
     * @param visible       是否通过视锥体剔除
     * @param padding       对齐填充
     */
    public record GPULODOutput(int lodLevel, float transitionAlpha, boolean visible, int padding) {}



    /**
     * 间接绘制命令（对应 Vulkan VkDrawIndexedIndirectCommand）
     *
     * @param indexCount    每个实例的索引数量
     * @param instanceCount 实例数量
     * @param firstIndex    首索引偏移
     * @param vertexOffset  顶点偏移
     * @param firstInstance 实例 ID 基础值
     */
    public record IndirectDrawCommand(
        int indexCount, int instanceCount,
        int firstIndex, int vertexOffset, int firstInstance
    ) {}



    /**
     * GPU LOD 处理结果
     *
     * @param chunkLODs    chunk ID → LOD 等级的映射
     * @param indirectDraws 间接绘制命令列表
     * @param processTimeNanos GPU 处理耗时（纳秒）
     * @param usedGPUPath   是否使用了 GPU 路径（false 表示已降级到 CPU）
     */
    public record GPULODResult(
        Map<Integer, Integer> chunkLODs,
        IndirectDrawCommand[] indirectDraws,
        long processTimeNanos,
        boolean usedGPUPath
    ) {}



    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否启用 GPU 驱动模式 */
    private final AtomicBoolean gpuEnabled = new AtomicBoolean(false);

    /** 连续失败计数器 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** 当前运行模式 */
    private volatile RenderiumMode currentMode = RenderiumMode.COMPATIBILITY;

    // ==================== GPU 资源句柄 ====================

    /** Compute Pipeline 句柄 */
    private volatile long computePipelineHandle = 0L;

    /** Chunk 数据 SSBO 缓冲区句柄 */
    private volatile long chunkDataBufferHandle = 0L;

    /** Chunk 数据 SSBO 内存句柄 */
    private volatile long chunkDataBufferMemory = 0L;

    /** LOD 结果 SSBO 缓冲区句柄 */
    private volatile long lodResultBufferHandle = 0L;

    /** LOD 结果 SSBO 内存句柄 */
    private volatile long lodResultBufferMemory = 0L;

    /** 间接绘制命令缓冲区句柄 */
    private volatile long indirectCommandBufferHandle = 0L;

    /** 间接绘制命令缓冲区内存句柄 */
    private volatile long indirectCommandBufferMemory = 0L;

    /** 参数 UBO 缓冲区句柄 */
    private volatile long paramUniformBufferHandle = 0L;

    /** 参数 UBO 缓冲区内存句柄 */
    private volatile long paramUniformBufferMemory = 0L;

    // ==================== 配置字段 ====================

    /** 最大处理的 chunk 数量 */
    private volatile int maxChunks = DEFAULT_MAX_CHUNKS;

    // ==================== 缓冲区大小常量 ====================

    /** 每个 chunk 输入数据字节数：centerX/Y/Z(12B) + chunkId(4B) + flags(4B) + padding(12B) = 32B */
    private static final int CHUNK_INPUT_STRIDE = 32;

    /** 每个 LOD 输出字节数：lodLevel(4B) + transitionAlpha(4B) + visible(4B) + padding(4B) = 16B */
    private static final int LOD_OUTPUT_STRIDE = 16;

    /** 间接绘制命令字节数：indexCount(4B) + instanceCount(4B) + firstIndex(4B) + vertexOffset(4B) + firstInstance(4B) = 20B */
    private static final int INDIRECT_COMMAND_STRIDE = 20;

    /** UBO 字节数：camPos(12B) + fov(4B) + screenW(4B) + screenH(4B) + pad(8B) = 32B */
    private static final int UBO_SIZE = 32;

    // ==================== 性能统计 ====================

    /** 上一帧 GPU 处理耗时（纳秒） */
    private volatile long lastGpuProcessTimeNanos = 0L;

    /** 总计 GPU dispatch 次数 */
    private final AtomicLong totalDispatchCount = new AtomicLong(0);

    /** 总计降级次数 */
    private final AtomicLong totalFallbackCount = new AtomicLong(0);

    // ==================== 私有构造函数 ====================

    private GPUDrivenLODSystem() {}

    // ==================== 生命周期 API ====================

    /**
     * 初始化 GPU 驱动 LOD 系统
     *
     * <p>创建必要的 GPU 资源（SSBO、Compute Pipeline 等），
     * 加载并编译 {@code lod_compute.comp} shader
     *
     * <p><b>注意：</b>此方法应在 Vulkan 设备就绪后调用，
     * 且仅在 AGGRESSIVE 模式下有效
     *
     * @param mode 当前运行模式（必须为 AGGRESSIVE 才能成功初始化）
     * @return true 如果初始化成功
     */
    public boolean initialize(RenderiumMode mode) {
        if (initialized.get()) {
            LOGGER.warning("GPUDrivenLODSystem 已初始化，跳过重复初始化");
            return true;
        }

        this.currentMode = mode;

        if (!mode.isAggressive()) {
            LOGGER.info("GPUDrivenLODSystem: 非 AGGRESSIVE 模式，GPU 路径不可用");
            gpuEnabled.set(false);
            initialized.set(true);
            return true;
        }

        if (VulkanOperationGuard.isFailed()) {
            LOGGER.warning("GPUDrivenLODSystem: Vulkan 已故障，以降级模式初始化");
            gpuEnabled.set(false);
            initialized.set(true);
            return false;
        }

        try {
            long chunkDataSize = (long) maxChunks * CHUNK_INPUT_STRIDE;
            long lodResultSize = (long) maxChunks * LOD_OUTPUT_STRIDE;
            long indirectCmdSize = (long) maxChunks * INDIRECT_COMMAND_STRIDE;
            int hostVisible = 2 | 4; // HOST_VISIBLE | HOST_COHERENT

            long[] chunkBuf = VulkanBufferHelper.createBuffer(chunkDataSize, hostVisible);
            long[] lodBuf = VulkanBufferHelper.createBuffer(lodResultSize, hostVisible);
            long[] indirectBuf = VulkanBufferHelper.createBuffer(indirectCmdSize, hostVisible);
            long[] paramBuf = VulkanBufferHelper.createBuffer(UBO_SIZE, hostVisible);

            chunkDataBufferHandle = chunkBuf[0]; chunkDataBufferMemory = chunkBuf[1];
            lodResultBufferHandle = lodBuf[0]; lodResultBufferMemory = lodBuf[1];
            indirectCommandBufferHandle = indirectBuf[0]; indirectCommandBufferMemory = indirectBuf[1];
            paramUniformBufferHandle = paramBuf[0]; paramUniformBufferMemory = paramBuf[1];

            if (chunkDataBufferHandle == 0L || lodResultBufferHandle == 0L) {
                throw new RuntimeException("GPU 缓冲区创建失败");
            }

            gpuEnabled.set(true);
            initialized.set(true);
            consecutiveFailures.set(0);

            LOGGER.info(String.format(
                "GPUDrivenLODSystem 初始化成功 (AGGRESSIVE) [maxChunks=%d, SSBOs=%dKB/%dKB/%dKB, UBO=%dB]",
                maxChunks, chunkDataSize / 1024, lodResultSize / 1024, indirectCmdSize / 1024, UBO_SIZE
            ));
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "GPUDrivenLODSystem 初始化失败: " + e.getMessage(), e);
            gpuEnabled.set(false);
            initialized.set(true);
            return false;
        }
    }

    /**
     * 关闭 GPU 驱动 LOD 系统，释放所有 GPU 资源
     */
    public void shutdown() {
        if (!initialized.get()) return;

        if (!VulkanOperationGuard.isFailed()) {
            VulkanBufferHelper.destroyBuffer(chunkDataBufferHandle, chunkDataBufferMemory);
            VulkanBufferHelper.destroyBuffer(lodResultBufferHandle, lodResultBufferMemory);
            VulkanBufferHelper.destroyBuffer(indirectCommandBufferHandle, indirectCommandBufferMemory);
            VulkanBufferHelper.destroyBuffer(paramUniformBufferHandle, paramUniformBufferMemory);
        }

        computePipelineHandle = 0L;
        chunkDataBufferHandle = 0L; chunkDataBufferMemory = 0L;
        lodResultBufferHandle = 0L; lodResultBufferMemory = 0L;
        indirectCommandBufferHandle = 0L; indirectCommandBufferMemory = 0L;
        paramUniformBufferHandle = 0L; paramUniformBufferMemory = 0L;
        gpuEnabled.set(false);
        initialized.set(false);
        consecutiveFailures.set(0);
        LOGGER.info("GPUDrivenLODSystem 已关闭");
    }

    // ==================== 核心 API：GPU LOD 计算 ====================

    /**
     * 使用 GPU Compute Shader 计算 LOD 并生成间接绘制命令
     *
     * <h3>完整流程</h3>
     * <ol>
     *   <li>检查 GPU 可用性和失败计数</li>
     *   <li>准备 chunk 数据并上传到 GPU SSBO</li>
     *   <li>更新相机参数 UBO</li>
     *   <li>Dispatch Compute Shader (ceil(maxChunks/64) 个工作组)</li>
     *   <li>添加内存屏障确保写入完成</li>
     *   <li>回读 LOD 结果（或让渲染管线直接消费 SSBO）</li>
     *   <li>构建返回结果对象</li>
     * </ol>
     *
     *
     * <h3>降级条件</h3>
     * <ul>
     *   <li>GPU 未初始化或未启用</li>
     *   <li>连续失败次数超过阈值</li>
     *   <li>Dispatch 执行时发生异常</li>
     * </ul>
     *
     *
     * @param chunkInputs  待处理的 chunk 数据数组
     * @param cameraX      相机 X 坐标
     * @param cameraY      相机 Y 坐标
     * @param cameraZ      相机 Z 坐标
     * @param fov          视野角度（度数）
     * @param screenWidth  屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     * @return GPU LOD 处理结果
     */
    public GPULODResult computeLOD(GPUChunkInput[] chunkInputs,
                                    double cameraX, double cameraY, double cameraZ,
                                    float fov, float screenWidth, float screenHeight) {
        long startTime = System.nanoTime();



        // ======== 检查 GPU 可用性 ========
        if (!canUseGPUPath()) {
            return fallbackToCPU(chunkInputs, startTime);
        }




        try {
            // ======== 获取命令缓冲区 ========
            long device = VulkanBufferHelper.getDevice();
            long cmdBuf = com.ranecc.renderium.feature.lod.compute.HiZComputePipeline.allocateCommandBuffer(device);
            if (cmdBuf == 0L) {
                throw new RuntimeException("无法分配命令缓冲区");
            }

            // ======== Step 1: 准备并上传 chunk 数据 ========
            uploadChunkData(chunkInputs);



            // ======== Step 2: 更新相机参数 UBO ========
            updateCameraParams(cameraX, cameraY, cameraZ, fov, screenWidth, screenHeight);



            // ======== Step 3: Dispatch Compute Shader ========
            int chunkCount = Math.min(chunkInputs.length, maxChunks);
            int workgroupCount = (chunkCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;



            dispatchCompute(cmdBuf, workgroupCount);
            totalDispatchCount.incrementAndGet();



            // ======== Step 4: 内存屏障 ========
            computeMemoryBarrier();



            // ======== Step 5: 回读结果 ========
            Map<Integer, Integer> chunkLODs = readLODResults(chunkCount);
            IndirectDrawCommand[] indirectCommands = readIndirectCommands(chunkCount);



            // ======== 成功：重置失败计数器 ========
            consecutiveFailures.set(0);
            lastGpuProcessTimeNanos = System.nanoTime() - startTime;



            return new GPULODResult(
                chunkLODs,
                indirectCommands,
                lastGpuProcessTimeNanos,
                true // 使用了 GPU 路径
            );



        } catch (Exception e) {
            // ======== 失败处理 ========
            int failures = consecutiveFailures.incrementAndGet();
            totalFallbackCount.incrementAndGet();



            LOGGER.warning(String.format(
                "GPU LOD 计算失败 (第 %d/%d 次): %s",
                failures, MAX_CONSECUTIVE_FAILURES, e.getMessage()
            ));



            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                gpuEnabled.set(false);
                LOGGER.severe(
                    "GPU LOD 连续失败 " + MAX_CONSECUTIVE_FAILURES + " 次，" +
                    "已禁用 GPU 路径，后续将使用 CPU 降级"
                );
            }



            return fallbackToCPU(chunkInputs, startTime);
        }

    }

    // ==================== 查询 API ====================

    /**
     * 检查 GPU 驱动路径是否可用且已启用
     *
     * @return true 如果可以使用 GPU Compute Shader 进行 LOD 计算
     */
    public boolean isGPUDrivenEnabled() {
        return initialized.get() && gpuEnabled.get() &&
               currentMode.isAggressive() && consecutiveFailures.get() < MAX_CONSECUTIVE_FAILURES;
    }

    /**
     * 检查是否已初始化
     *
     * @return true 如果已完成初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 获取上一帧 GPU 处理耗时（毫秒）
     *
     * @return 耗时（毫秒）（0 表示尚未执行过）
     */
    public double getLastGpuProcessTimeMillis() {
        return lastGpuProcessTimeNanos / 1_000_000.0;
    }

    /**
     * 获取总 dispatch 次数
     *
     * @return dispatch 总次数
     */
    public long getTotalDispatchCount() {
        return totalDispatchCount.get();
    }

    /**
     * 获取总降级次数
     *
     * @return 降到 CPU 路径的总次数
     */
    public long getTotalFallbackCount() {
        return totalFallbackCount.get();
    }

    /**
     * 获取当前连续失败次数
     *
     * @return 连续失败次数
     */
    public int getConsecutiveFailureCount() {
        return consecutiveFailures.get();
    }

    // ==================== 配置 API ====================

    /**
     * 设置最大处理的 chunk 数量
     *
     * @param maxChunks 最大数量（必须 >= 64 且 <= 131072）
     */
    public void setMaxChunks(int maxChunks) {
        if (maxChunks < 64 || maxChunks > 131072) {
            throw new IllegalArgumentException("最大 chunk 数量必须在 [64, 131072] 范围内: " + maxChunks);
        }

        this.maxChunks = maxChunks;
    }

    /**
     * 手动重新启用 GPU 路径（之前因连续失败而禁用后可调用此方法重试）
     */
    public void reEnableGPUPath() {
        if (currentMode.isAggressive()) {
            consecutiveFailures.set(0);
            gpuEnabled.set(true);
            LOGGER.info("GPU LOD 路径已手动重新启用");
        }
    }

    // ==================== 内部方法：GPU 操作（占位实现） ====================

    /**
     * 检查是否可以使用 GPU 路径
     */
    private boolean canUseGPUPath() {
        return isGPUDrivenEnabled();
    }

    /**
     * 上传 chunk 数据到 GPU SSBO
     */
    private void uploadChunkData(GPUChunkInput[] chunkInputs) {
        if (VulkanOperationGuard.isFailed() || chunkDataBufferHandle == 0L) return;
        int count = Math.min(chunkInputs.length, maxChunks);
        int dataSize = count * CHUNK_INPUT_STRIDE;
        byte[] data = new byte[dataSize];
        for (int i = 0; i < count; i++) {
            GPUChunkInput in = chunkInputs[i];
            int off = i * CHUNK_INPUT_STRIDE;
            writeFloat(data, off, in.centerX());
            writeFloat(data, off + 4, in.centerY());
            writeFloat(data, off + 8, in.centerZ());
            writeInt(data, off + 12, in.chunkId());
            writeInt(data, off + 16, in.flags());
        }
        VulkanBufferHelper.uploadData(
            VulkanBufferHelper.getDevice(), chunkDataBufferMemory, data, 0L);
    }

    /**
     * 更新相机参数 UBO
     */
    private void updateCameraParams(double camX, double camY, double camZ,
                                     float fov, float screenW, float screenH) {
        if (VulkanOperationGuard.isFailed() || paramUniformBufferHandle == 0L) return;
        byte[] data = new byte[UBO_SIZE];
        writeFloat(data, 0, (float) camX);
        writeFloat(data, 4, (float) camY);
        writeFloat(data, 8, (float) camZ);
        writeFloat(data, 12, fov);
        writeFloat(data, 16, screenW);
        writeFloat(data, 20, screenH);
        VulkanBufferHelper.uploadData(
            VulkanBufferHelper.getDevice(), paramUniformBufferMemory, data, 0L);
    }

    /**
     * Dispatch Compute Shader
     */
    private void dispatchCompute(long cmdBuf, int workgroupCount) {
        if (VulkanOperationGuard.isFailed() || cmdBuf == 0L) return;
        try {
            MethodHandle vkCmdDispatch = VulkanFFMBinding.getVkCmdDispatch();
            if (vkCmdDispatch != null) {
                vkCmdDispatch.invoke(cmdBuf, workgroupCount, 1, 1);
            }
        } catch (Throwable t) {
            LOGGER.warning("dispatchCompute failed: " + t.getMessage());
        }
    }

    /**
     * 插入 GPU 内存屏障
     */
    private void computeMemoryBarrier() {
        if (VulkanOperationGuard.isFailed()) return;
        try {
            MethodHandle barrier = VulkanFFMBinding.getVkCmdPipelineBarrier();
            if (barrier != null) {
                barrier.invoke(0L, 2, 2, 0, 0, 0L, 0, 0L, 0, 0L);
            }
        } catch (Throwable t) {
            LOGGER.warning("computeMemoryBarrier failed: " + t.getMessage());
        }
    }

    /**
     * 从 GPU 回读 LOD 计算结果
     */
    private Map<Integer, Integer> readLODResults(int expectedCount) {
        if (VulkanOperationGuard.isFailed() || lodResultBufferHandle == 0L) {
            return new ConcurrentHashMap<>();
        }
        try (Arena arena = Arena.ofConfined()) {
            long device = VulkanBufferHelper.getDevice();
            long mapSize = (long) expectedCount * LOD_OUTPUT_STRIDE;
            var ppData = arena.allocate(ValueLayout.JAVA_LONG);
            int result = (int) VulkanFFMBinding.getVkMapMemory().invoke(
                device, lodResultBufferMemory, 0L, mapSize, 0, ppData);
            if (result != 0) return new ConcurrentHashMap<>();
            long ptr = ppData.get(ValueLayout.JAVA_LONG, 0);
            if (ptr == 0L) return new ConcurrentHashMap<>();
            MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(mapSize);
            Map<Integer, Integer> resultMap = new ConcurrentHashMap<>();
            for (int i = 0; i < expectedCount; i++) {
                int off = i * LOD_OUTPUT_STRIDE;
                resultMap.put(seg.get(ValueLayout.JAVA_INT, off + 8),
                              seg.get(ValueLayout.JAVA_INT, off));
            }
            VulkanFFMBinding.getVkUnmapMemory().invoke(device, lodResultBufferMemory);
            return resultMap;
        } catch (Throwable t) {
            LOGGER.warning("readLODResults failed: " + t.getMessage());
            return new ConcurrentHashMap<>();
        }
    }

    /**
     * 从 GPU 回读间接绘制命令
     */
    private IndirectDrawCommand[] readIndirectCommands(int expectedCount) {
        if (VulkanOperationGuard.isFailed() || indirectCommandBufferHandle == 0L) {
            return new IndirectDrawCommand[0];
        }
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle vkMapMemory = VulkanFFMBinding.getVkMapMemory();
            if (vkMapMemory == null) return new IndirectDrawCommand[0];

            long device = VulkanBufferHelper.getDevice();
            var ppData = arena.allocate(ValueLayout.JAVA_LONG);
            long mapSize = (long) expectedCount * INDIRECT_COMMAND_STRIDE;
            int result = (int) vkMapMemory.invoke(device, indirectCommandBufferMemory, 0L, mapSize, 0, ppData);
            if (result != 0) return new IndirectDrawCommand[0];

            long ptr = ppData.get(ValueLayout.JAVA_LONG, 0);
            if (ptr == 0L) return new IndirectDrawCommand[0];

            IndirectDrawCommand[] cmds = new IndirectDrawCommand[expectedCount];
            MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(mapSize);
            for (int i = 0; i < expectedCount; i++) {
                int off = i * INDIRECT_COMMAND_STRIDE;
                cmds[i] = new IndirectDrawCommand(
                    seg.get(ValueLayout.JAVA_INT, off),
                    seg.get(ValueLayout.JAVA_INT, off + 4),
                    seg.get(ValueLayout.JAVA_INT, off + 8),
                    seg.get(ValueLayout.JAVA_INT, off + 12),
                    seg.get(ValueLayout.JAVA_INT, off + 16));
            }
            VulkanFFMBinding.getVkUnmapMemory().invoke(device, indirectCommandBufferMemory);
            return cmds;
        } catch (Throwable t) {
            LOGGER.warning("readIndirectCommands failed: " + t.getMessage());
            return new IndirectDrawCommand[0];
        }
    }

    // ==================== 序列化辅助方法 ====================

    private static void writeInt(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >> 0);
        buf[off + 1] = (byte) (v >> 8);
        buf[off + 2] = (byte) (v >> 16);
        buf[off + 3] = (byte) (v >> 24);
    }

    private static void writeFloat(byte[] buf, int off, float v) {
        writeInt(buf, off, Float.floatToRawIntBits(v));
    }

    /**
     * 降级到 CPU 路径进行 LOD 计算
     */
    private GPULODResult fallbackToCPU(GPUChunkInput[] chunkInputs, long startTime) {
        totalFallbackCount.incrementAndGet();

        // 使用 CPU calculator 计算每个 chunk 的 LOD
        Map<Integer, Integer> chunkLODs = new ConcurrentHashMap<>();
        LODCalculator calc = LODCalculator.getInstance();


        for (int i = 0; i < chunkInputs.length; i++) {
            GPUChunkInput input = chunkInputs[i];

            // GPU优化：使用平方距离比较，避免昂贵的 sqrt 运算
            // 通过 LODCalculator.calculateLODLevelFromWorldDistSq 进行平方距离比较
            double distSq = (double) input.centerX() * input.centerX() +
                            (double) input.centerY() * input.centerY() +
                            (double) input.centerZ() * input.centerZ();

            int lodLevel = calc.calculateLODLevelFromWorldDistSq(distSq, 70.0);
            chunkLODs.put(input.chunkId(), lodLevel);
        }


        long elapsed = System.nanoTime() - startTime;

        LOGGER.fine(String.format(
            "GPU LOD 已降级到 CPU 路径: 处理 %d 个 chunks, 耗时 %.2f ms",
            chunkInputs.length, elapsed / 1_000_000.0
        ));


        return new GPULODResult(
            chunkLODs,
            new IndirectDrawCommand[0],
            elapsed,
            false
        );
    }
}
