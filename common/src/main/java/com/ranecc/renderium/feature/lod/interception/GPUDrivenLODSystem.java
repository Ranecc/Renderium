// Renderium - Blaze3D 拦截层系统 Phase 3: LOD 多细节层次系统
// GPUDrivenLODSystem.java - GPU 驱动 LOD 系统（狂暴模式专用）
// 功能: 使用 Compute Shader 计算 LOD，生成间接绘制命令


package com.ranecc.renderium.feature.lod.interception;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.domain.enums.RenderiumMode;
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

    // ==================== GPU 资源句柄（占位符 - 实际由 Vulkan 层初始化）====================

    /** Compute Pipeline 句柄（Vulkan VkPipeline） */
    private volatile long computePipelineHandle = 0L;

    /** Chunk 数据 SSBO 缓冲区句柄 */
    private volatile long chunkDataBufferHandle = 0L;

    /** LOD 结果 SSBO 缓冲区句柄 */
    private volatile long lodResultBufferHandle = 0L;

    /** 间接绘制命令缓冲区句柄 */
    private volatile long indirectCommandBufferHandle = 0L;

    /** 参数 UBO 缓冲区句柄 */
    private volatile long paramUniformBufferHandle = 0L;

    // ==================== 配置字段 ====================

    /** 最大处理的 chunk 数量 */
    private volatile int maxChunks = DEFAULT_MAX_CHUNKS;

    // ==================== 性能统计 ====================

    /** 上一帧 GPU 处理耗时（纳秒） */
    private volatile long lastGpuProcessTimeNanos = 0L;

    /** 总计 GPU dispatch 次数 */
    private final AtomicLong totalDispatchCount = new AtomicLong(0);

    /** 总计降级次数 */
    private final AtomicLong totalFallbackCount = new AtomicLong(0);

    /** 模拟资源句柄递增生成器 */
    private final AtomicLong mockResourceHandleGenerator = new AtomicLong(0);

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


        // 仅在 AGGRESSIVE 模式下启用 GPU 路径
        if (!mode.isAggressive()) {
            LOGGER.info("GPUDrivenLODSystem: 非 AGGRESSIVE 模式，GPU 路径不可用（将使用 CPU 降级）");
            gpuEnabled.set(false);
            initialized.set(true);
            return true; // 初始化成功，但 GPU 路径不活跃
        }

        // Vulkan 操作守卫：如果 Vulkan 已故障，直接以降级模式初始化
        if (VulkanOperationGuard.isFailed()) {
            LOGGER.warning("GPUDrivenLODSystem: Vulkan 已故障，以降级模式初始化");
            gpuEnabled.set(false);
            initialized.set(true);
            return false;
        }

        try {
            // 实际 Vulkan 资源创建逻辑（VulkanOperationGuard 已保护）
            // 1. 创建 SSBO buffers (chunk data, LOD results, indirect commands)
            // 2. 创建 UBO buffer (camera params)
            // 3. 加载并编译 lod_compute.comp shader
            // 4. 创建 Compute Pipeline
            // 5. 分配描述符集


            // 占位：模拟资源分配成功
            computePipelineHandle = allocateMockResource("ComputePipeline");
            chunkDataBufferHandle = allocateMockResource("ChunkDataSSBO");
            lodResultBufferHandle = allocateMockResource("LODResultSSBO");
            indirectCommandBufferHandle = allocateMockResource("IndirectCmdBuffer");
            paramUniformBufferHandle = allocateMockResource("ParamUBO");



            gpuEnabled.set(true);
            initialized.set(true);
            consecutiveFailures.set(0);


            LOGGER.info(String.format(
                "GPUDrivenLODSystem 初始化成功 (AGGRESSIVE 模式) " +
                "[maxChunks=%d, workgroupSize=%d]",
                maxChunks, WORKGROUP_SIZE
            ));


            return true;


        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "GPUDrivenLODSystem 初始化失败，将使用 CPU 降级: " + e.getMessage(), e);
            gpuEnabled.set(false);
            initialized.set(true); // 仍然标记为已初始化（以降级模式运行）

            return false;
        }

    }

    /**
     * 关闭 GPU 驱动 LOD 系统，释放所有 GPU 资源
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        // Vulkan 操作守卫：如果 Vulkan 已故障，无需清理 GPU 资源
        if (VulkanOperationGuard.isFailed()) {
            gpuEnabled.set(false);
            initialized.set(false);
            consecutiveFailures.set(0);
            LOGGER.info("GPUDrivenLODSystem 已关闭（Vulkan 故障，跳过 GPU 资源清理）");
            return;
        }

        try {
            // 实际 Vulkan 资源释放逻辑（VulkanOperationGuard 已保护）
            // vkDestroyBuffer(...), vkFreeMemory(...), vkDestroyPipeline(...)


            computePipelineHandle = 0L;
            chunkDataBufferHandle = 0L;
            lodResultBufferHandle = 0L;
            indirectCommandBufferHandle = 0L;
            paramUniformBufferHandle = 0L;



            gpuEnabled.set(false);
            initialized.set(false);
            consecutiveFailures.set(0);



            LOGGER.info("GPUDrivenLODSystem 已关闭");


        } catch (Exception e) {
            LOGGER.severe("GPUDrivenLODSystem 关闭时出错: " + e.getMessage());
        }

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
            // ======== Step 1: 准备并上传 chunk 数据 ========
            uploadChunkData(chunkInputs);



            // ======== Step 2: 更新相机参数 UBO ========
            updateCameraParams(cameraX, cameraY, cameraZ, fov, screenWidth, screenHeight);



            // ======== Step 3: Dispatch Compute Shader ========
            int chunkCount = Math.min(chunkInputs.length, maxChunks);
            int workgroupCount = (chunkCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;



            dispatchCompute(workgroupCount);
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
        if (VulkanOperationGuard.isFailed()) return;
        LOGGER.warning("uploadChunkData() 未实现");

        // vkCmdUpdateBuffer 或 staging buffer 上传（VulkanOperationGuard 已保护）
        // 将 chunkInputs 数组序列化后上传到 chunkDataBufferHandle

        LOGGER.fine("上传 " + chunkInputs.length + " 个 chunk 数据到 GPU SSBO");
    }

    /**
     * 更新相机参数 UBO
     */
    private void updateCameraParams(double camX, double camY, double camZ,
                                     float fov, float screenW, float screenH) {
        if (VulkanOperationGuard.isFailed()) return;
        LOGGER.warning("updateCameraParams() 未实现");

        // UBO 更新（VulkanOperationGuard 已保护）
        LOGGER.fine(String.format(
            "更新相机参数 UBO: pos=(%.1f,%.1f,%.1f), fov=%.1f°, screen=%.0fx%.0f",
            camX, camY, camZ, fov, screenW, screenH
        ));
    }

    /**
     * Dispatch Compute Shader
     *
     * @param workgroupCount 工作组数量
     */
    private void dispatchCompute(int workgroupCount) {
        if (VulkanOperationGuard.isFailed()) return;
        LOGGER.warning("dispatchCompute() 未实现");

        // vkCmdDispatch（VulkanOperationGuard 已保护）
        LOGGER.fine("Dispatch LOD Compute Shader: " + workgroupCount + " 个工作组 (" +
                     (workgroupCount * WORKGROUP_SIZE) + " 个线程)");
    }

    /**
     * 插入 GPU 内存屏障
     */
    private void computeMemoryBarrier() {
        if (VulkanOperationGuard.isFailed()) return;
        LOGGER.warning("computeMemoryBarrier() 未实现");

        // vkCmdMemoryBarrier（VulkanOperationGuard 已保护）
    }

    /**
     * 从 GPU 回读 LOD 计算结果
     *
     * @param expectedCount 期望的 chunk 数量
     * @return chunkId → LOD 等级的映射
     */
    private Map<Integer, Integer> readLODResults(int expectedCount) {
        if (VulkanOperationGuard.isFailed()) return new ConcurrentHashMap<>();
        LOGGER.warning("readLODResults() 未实现，返回空 Map");

        // vkMapMemory + 回读 lodResultBufferHandle（VulkanOperationGuard 已保护）
        return new ConcurrentHashMap<>();
    }

    /**
     * 从 GPU 回读间接绘制命令
     *
     * @param expectedCount 期望的命令数量
     * @return 间接绘制命令数组
     */
    private IndirectDrawCommand[] readIndirectCommands(int expectedCount) {
        if (VulkanOperationGuard.isFailed()) return new IndirectDrawCommand[0];
        LOGGER.warning("readIndirectCommands() 未实现，返回空数组");

        // vkMapMemory + 回读 indirectCommandBufferHandle（VulkanOperationGuard 已保护）
        return new IndirectDrawCommand[0];
    }

    /**
     * 降级到 CPU 路径进行 LOD 计算
     *
     * @param chunkInputs chunk 输入数据
     * @param startTime  开始时间戳
     * @return 标记为 CPU 路径的结果
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
            new IndirectDrawCommand[0], // CPU 路径不生成间接命令
            elapsed,
            false // 未使用 GPU 路径
        );
    }

    /**
     * 分配模拟 GPU 资源句柄（用于非 Vulkan 环境下的测试）
     *
     * @param resourceName 资源名称（用于日志）
     * @return 模拟的资源句柄（非零值）
     */
    private long allocateMockResource(String resourceName) {
        long handle = mockResourceHandleGenerator.incrementAndGet();
        LOGGER.fine("分配模拟 GPU 资源: " + resourceName + " -> handle=" + handle);
        return handle;
    }


}
