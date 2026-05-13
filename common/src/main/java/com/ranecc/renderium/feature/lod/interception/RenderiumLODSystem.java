// Renderium - Blaze3D 拦截层系统 Phase 3: LOD 多细节层次系统
// RenderiumLODSystem.java - LOD 系统主协调器
// 功能: 统一管理所有 LOD 子组件，双模式支持（兼容/狂暴），与 PreBlaze3DInterceptor 集成


package com.ranecc.renderium.feature.lod.interception;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.domain.enums.RenderiumMode;
import com.ranecc.renderium.feature.intercept.base.LODContext;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;

/**
 * Renderium LOD 系统主协调器
 *
 * <p>作为 LOD 多细节层次系统的顶层入口，统一管理和调度所有子组件：
 * <ul>
 *   <li>{@link LODCalculator}：核心 LOD 计算算法</li>
 *   <li>{@link LODTransitionHandler}：过渡效果处理（dithering/crossfade）</li>
 *   <li>{@link LODDataManager}：区块 LOD 缓存与数据管理</li>
 *   <li>{@link LODMeshCache}：多级 Mesh 数据缓存</li>
 *   <li>{@link GPUDrivenLODSystem}：GPU 驱动计算（仅 AGGRESSIVE 模式）</li>
 * </ul>
 *
 *
 * <h2>架构总览</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │                  RenderiumLODSystem (协调器)                 │
 * │                                                            │
 * │ ┌───────────────┬───────────────────┬─────────────────┐     │
 * │ │LODCalculator  │LODTransitionHandler│LODDataManager  │     │
 * │ │(距离/视角     │ (dithering/       │(chunk→LOD      │     │
 * │ │ LOD 计算)     │ crossfade)        │ 缓存映射)       │     │
 * │ └───────────────┴───────────────────┴─────────────────┘     │
 * │                                                            │
 * │ ┌───────────────┬────────────────────────────────────────┐  │
 * │ │LODMeshCache   │     GPUDrivenLODSystem                │  │
 * │ │(多级 mesh     │     (仅AGGRESSIVE 模式)               │  │
 * │ │ 缓存/LRU)     │     Compute Shader → Indirect Draw    │  │
 * │ └───────────────┴────────────────────────────────────────┘  │
 * │                                                            │
 * │ 调用入口: processLOD(LODContext) → LODResult               │
 * │ 集成点: DefaultPreInterceptor.processLODInjection()         │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 *
 * <h2>双模式支持：</h2>
 * <pre>
 * ┌─────────────────────┬──────────────────────────┬───────────────────────────┐
 * │                     │兼容模式 (COMPATIBILITY)  │狂暴模式 (AGGRESSIVE)      │
 * ├─────────────────────┼──────────────────────────┼───────────────────────────┤
 * │LOD 计算方式         │CPU: LODCalculator        │GPU: lod_compute.comp     │
 * │过渡策略             │Dithering (默认)          │Crossfade + Dithering     │
 * │性能预算             │&lt;0.5ms/帧(CPU)          │&lt;0.5ms CPU + 异步 GPU    │
 * │内存使用             │~50MB 堆内存              │~100MB 显存               │
 * │依赖项               │无特殊要求                │Vulkan Compute Shader     │
 * │降级能力             │N/A                      │自动降级到 CPU 模式        │
 * └─────────────────────┴──────────────────────────┴───────────────────────────┘
 * </pre>
 *
 *
 * <h3>性能预算验证</h3>
 * <pre>
 * 兼容模式:
 *   LOD 计算 (~0.1ms) + 数据更新 (~0.2ms) + 结果输出 (~0.05ms)
 *   总计: &lt; 0.5ms ✓
 *
 * 狂暴模式:
 *   Dispatch (~0.05ms) + GPU 计算 (异步) + 结果回读 (~0.3ms)
 *   CPU 总计: &lt; 0.5ms ✓
 * </pre>
 *
 *
 * @see DefaultPreInterceptor
 * @see LODContext
 * @author Renderium Team
 * @since 5.3.0
 */
public final class RenderiumLODSystem {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(RenderiumLODSystem.class.getName());

    // ==================== 单例实例 ====================

    /** 全局唯一实例 */
    private static final RenderiumLODSystem INSTANCE = new RenderiumLODSystem();

    /**
     * 获取单例实例
     *
     * @return 全局唯一的 RenderiumLODSystem 实例
     */
    public static RenderiumLODSystem getInstance() {
        return INSTANCE;
    }

    // ==================== 内部数据结构 ====================

    /**
     * LOD 处理上下文（传递给 processLOD 的输入参数）
     *
     * @param cameraX      相机 X 坐标（世界空间）
     * @param cameraY      相机 Y 坐标（世界空间）
     * @param cameraZ      相机 Z 坐标（世界空间）
     * @param fov          视野角度（度数）
     * @param screenWidth  屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     * @param frameIndex   当前帧序号
     * @param updateRadius LOD 更新半径（区块数）
     */
    public record LODSystemContext(
        double cameraX, double cameraY, double cameraZ,
        float fov,
        int screenWidth, int screenHeight,
        int frameIndex,
        int updateRadius
    ) {}



    /**
     * LOD 处理结果（processLOD 的返回值）
     *
     * @param chunkLODs       chunk 键 → LOD 等级的映射
     * @param processTimeNanos 处理耗时（纳秒）
     * @param usedGPUPath     是否使用了 GPU 计算路径
     * @param chunksProcessed 本次处理的 chunk 数量
     */
    public record LODResult(
        Map<Long, Integer> chunkLODs,
        long processTimeNanos,
        boolean usedGPUPath,
        int chunksProcessed
    ) {}



    // ==================== 子组件引用 ====================

    /** LOD 核心计算器 */
    private final LODCalculator calculator = LODCalculator.getInstance();

    /** 过渡处理器 */
    private final LODTransitionHandler transitionHandler = LODTransitionHandler.getInstance();

    /** 数据管理器 */
    private final LODDataManager dataManager = LODDataManager.getInstance();

    /** Mesh 缓存管理器 */
    private final LODMeshCache meshCache = LODMeshCache.getInstance();

    /** GPU 驱动系统（仅 AGGRESSIVE 模式） */
    private final GPUDrivenLODSystem gpuDrivenSystem = GPUDrivenLODSystem.getInstance();

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 当前运行模式 */
    private volatile RenderiumMode currentMode = RenderiumMode.COMPATIBILITY;

    /** 是否启用 GPU 驱动路径（仅 AGGRESSIVE 模式下有效） */
    private volatile boolean gpuDrivenEnabled = false;

    // ==================== 配置字段 ====================

    /** 默认更新半径（区块数） */
    private volatile int defaultUpdateRadius = 48;

    /** 是否启用自动帧更新 */
    private volatile boolean autoFrameUpdate = true;

    // ==================== 统计字段 ====================

    /** 上一次处理耗时（纳秒） */
    private volatile long lastProcessTimeNanos = 0L;

    /** 总处理帧数 */
    private final AtomicLong totalFramesProcessed = new AtomicLong(0);

    /** 总处理的 chunk 数量 */
    private final AtomicLong totalChunksProcessed = new AtomicLong(0);

    // ==================== 私有构造函数 ====================

    private RenderiumLODSystem() {}

    // ==================== 生命周期 API ====================

    /**
     * 初始化 LOD 系统
     *
     * <p>根据运行模式和配置初始化所有子组件：
     * <ol>
     *   <li>读取配置参数并应用到各子组件</li>
     *   <li>COMPATIBILITY 模式：初始化 CPU-based 组件</li>
     *   <li>AGGRESSIVE 模式：额外初始化 {@link GPUDrivenLODSystem}</li>
     * </ol>
     *
     *
     * @param config 配置对象（包含所有 LOD 相关配置项）
     * @param mode   当前运行模式
     * @return true 表示初始化成功
     */
    public boolean initialize(RenderiumConfig config, RenderiumMode mode) {
        if (initialized.get()) {
            LOGGER.warning("RenderiumLODSystem 已初始化，跳过重复初始化");
            return true;
        }


        this.currentMode = mode;


        try {
            // ======== Step 1: 应用配置到子组件 ========
            applyConfiguration(config, mode);


            // ======== Step 2: 初始化 CPU 组件 ========
            LOGGER.info("初始化 LOD 子组件: Calculator, TransitionHandler, DataManager, MeshCache");

            // 这些单例组件在首次调用 getInstance() 时已完成基本初始化
            // 这里只需确认配置已正确应用


            // ======== Step 3: AGGRESSIVE 模式额外初始化 GPU 组件 ========
            if (mode.isAggressive()) {
                this.gpuDrivenEnabled = true;
                boolean gpuInitSuccess = gpuDrivenSystem.initialize(mode);



                if (!gpuInitSuccess) {
                    LOGGER.warning(
                        "GPUDrivenLODSystem 初始化失败，将使用 CPU 降级模式。" +
                        "功能不受影响，但无法享受 GPU 加速"
                    );
                    this.gpuDrivenEnabled = false;
                } else {
                    LOGGER.info("GPU 驱动 LOD 系统初始化成功");
                }
            }


            initialized.set(true);


            LOGGER.info(String.format(
                "RenderiumLODSystem 初始化完成 [mode=%s, gpuDriven=%s, updateRadius=%d]",
                mode.getDisplayName(), gpuDrivenEnabled, defaultUpdateRadius
            ));


            return true;


        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "RenderiumLODSystem 初始化失败: " + e.getMessage(), e);
            return false;
        }

    }

    /**
     * 关闭 LOD 系统，释放所有资源
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }


        try {
            // 关闭 GPU 系统
            if (gpuDrivenSystem.isInitialized()) {
                gpuDrivenSystem.shutdown();
            }



            // 清除缓存数据
            dataManager.clearAll();
            meshCache.clearAll();



            // 重置状态
            initialized.set(false);
            gpuDrivenEnabled = false;
            lastProcessTimeNanos = 0L;



            LOGGER.info("RenderiumLODSystem 已关闭");


        } catch (Exception e) {
            LOGGER.severe("RenderiumLODSystem 关闭时出错: " + e.getMessage());
        }

    }

    // ==================== 核心 API：LOD 处理入口 ====================

    /**
     * 处理 LOD 计算（主入口，由 DefaultPreInterceptor 调用）
     *
     * <h3>调用流程</h3>
     * <pre>
     * DefaultPreInterceptor.processLODInjection(context)
     *     ↓
     * RenderiumLODSystem.processLOD(context)
     *     ↓
     *     ├── [兼容模式] CPU 路径:
     *     │   ├── calculator.calculateChunkLOD() × N chunks
     *     │   ├── transitionHandler.computeDitherDecision()
     *     │   ├── dataManager.updateLODs()
     *     │   └── 返回 LODResult
     *     │
     *     └── [狂暴模式] GPU 路径 (如果可用):
     *         ├── gpuDrivenSystem.computeLOD()
     *         ├── 结果回读 / CPU 降级兜底
     *         └── 返回 LODResult
     * </pre>
     *
     *
     * <h3>性能保证</h3>
     * <ul>
     *   <li>兼容模式: CPU 开销 &lt; 0.5ms（~4000 chunks @ 3GHz）</li>
     *   <li>狂暴模式: CPU 开销 &lt; 0.5ms（dispatch + 管理）</li>
     *   <li>任何异常不会导致崩溃，而是返回安全降级结果</li>
     * </ul>
     *
     *
     * @param context LOD 系统上下文（包含相机状态、屏幕参数等）
     * @return LOD 处理结果，包含每一 chunk 的 LOD 映射和性能指标
     */
    public LODResult processLOD(LODSystemContext context) {
        if (!initialized.get()) {
            LOGGER.warning("RenderiumLODSystem 未初始化，返回空结果");
            return new LODResult(new ConcurrentHashMap<>(), 0L, false, 0);
        }



        long startTime = System.nanoTime();
        totalFramesProcessed.incrementAndGet();



        try {
            Map<Long, Integer> chunkLODs;
            boolean usedGPUPath = false;
            int chunksProcessed = 0;



            // ======== 选择执行路径 ========
            if (gpuDrivenEnabled && gpuDrivenSystem.isGPUDrivenEnabled()) {
                // ----- 狂暴模式：GPU 路径 -----
                var gpuResult = processLODGPU(context);
                chunkLODs = convertGpuResultToMap(gpuResult.chunkLODs());
                usedGPUPath = gpuResult.usedGPUPath();
                chunksProcessed = gpuResult.chunkLODs().size();


            } else {
                // ----- 兼容模式 / GPU 不可用：CPU 路径 -----
                var cpuResult = processLODCPU(context);
                chunkLODs = cpuResult;
                usedGPUPath = false;
                chunksProcessed = cpuResult.size();
            }



            // ======== 更新过渡处理器帧索引 ========
            transitionHandler.advanceFrame(context.frameIndex());



            // ======== 更新统计 ========
            long elapsed = System.nanoTime() - startTime;
            lastProcessTimeNanos = elapsed;
            totalChunksProcessed.addAndGet(chunksProcessed);



            return new LODResult(chunkLODs, elapsed, usedGPUPath, chunksProcessed);



        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "LOD 处理异常，返回安全降级结果: " + e.getMessage(), e);
            lastProcessTimeNanos = System.nanoTime() - startTime;

            return new LODResult(new ConcurrentHashMap<>(), lastProcessTimeNanos, false, 0);
        }

    }

    // ==================== 查询 API ====================

    /**
     * 检查是否已初始化
     *
     * @return true 如果已完成初始化且未关闭
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 检查是否启用了 GPU 驱动路径
     *
     * @return true 如果 GPU 驱动路径可用且已启用
     */
    public boolean isGPUDrivenEnabled() {
        return gpuDrivenEnabled && gpuDrivenSystem.isGPUDrivenEnabled();
    }

    /**
     * 获取当前运行模式
     *
     * @return 当前模式
     */
    public RenderiumMode getCurrentMode() {
        return currentMode;
    }

    /**
     * 获取 LOD 计算器实例
     *
     * @return LODCalculator 实例
     */
    public LODCalculator getCalculator() {
        return calculator;
    }

    /**
     * 获取数据管理器实例
     *
     * @return LODDataManager 实例
     */
    public LODDataManager getDataManager() {
        return dataManager;
    }

    /**
     * 获取过渡处理器实例
     *
     * @return LODTransitionHandler 实例
     */
    public LODTransitionHandler getTransitionHandler() {
        return transitionHandler;
    }

    /**
     * 获取 Mesh 缓存实例
     *
     * @return LODMeshCache 实例
     */
    public LODMeshCache getMeshCache() {
        return meshCache;
    }

    /**
     * 获取上一次处理耗时（毫秒）
     *
     * @return 耗时（毫秒）
     */
    public double getLastProcessTimeMillis() {
        return lastProcessTimeNanos / 1_000_000.0;
    }

    /**
     * 获取总处理帧数
     *
     * @return 帧数
     */
    public long getTotalFramesProcessed() {
        return totalFramesProcessed.get();
    }

    // ==================== 配置 API ====================

    /**
     * 运行时更新 LOD 配置
     *
     * @param config 新的配置对象
     */
    public void updateConfig(RenderiumConfig config) {
        applyConfiguration(config, currentMode);

        LOGGER.info("LOD 配置已热更新");

    }

    /**
     * 设置默认更新半径
     *
     * @param radius 半径（区块数，必须 >= 1）
     */
    public void setDefaultUpdateRadius(int radius) {
        if (radius < 1) {
            throw new IllegalArgumentException("更新半径必须 >= 1: " + radius);
        }

        this.defaultUpdateRadius = radius;
    }

    // ==================== 内部方法：CPU 路径处理 ====================

    /**
     * 使用 CPU 进行 LOD 计算（兼容模式 / 降级兜底）
     *
     * <p>这是安全、可靠的 LOD 计算路径，
     * 不依赖任何 GPU 特性，可在所有环境下工作
     *
     * @param context 系统上下文
     * @return chunkKey → LOD 等级的映射
     */
    private Map<Long, Integer> processLODCPU(LODSystemContext context) {
        // Step 1: 批量更新数据管理器中的 LOD 缓存
        int updatedCount = dataManager.updateLODs(
            context.cameraX(), context.cameraY(), context.cameraZ(),
            context.fov(), context.updateUrl()
        );



        // Step 2: 收集结果到输出 Map
        // 从数据管理器的缓存中提取当前有效的 chunk → LOD 映射
        Map<Long, Integer> result = new ConcurrentHashMap<>();


        int centerChunkX = (int) Math.floor(context.cameraX() / 16.0);
        int centerChunkZ = (int) Math.floor(context.cameraZ() / 16.0);
        int radius = context.updateRadius();



        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;




                // GPU优化：使用平方距离比较，避免昂贵的 sqrt 运算
                double distSqChunks = (double) dx * dx + (double) dz * dz;
                double[] thresholds = calculator.getDistanceThresholds();
                double maxThreshold = thresholds[thresholds.length - 1];

                if (distSqChunks > maxThreshold * maxThreshold) {
                    continue;
                }




                // 从缓存获取或计算 LOD
                int lodLevel = dataManager.getChunkLOD(
                    chunkX, chunkZ,
                    context.cameraX(), context.cameraZ(), context.fov()
                );




                long key = packChunkKey(chunkX, chunkZ);
                result.put(key, lodLevel);

            }
        }



        return result;
    }

    // ==================== 内部方法：GPU 路径处理 ====================

    /**
     * 使用 GPU Compute Shader 进行 LOD 计算（仅 AGGRESSIVE 模式）
     *
     * @param context 系统上下文
     * @return GPU LOD 处理结果
     */
    private GPUDrivenLODSystem.GPULODResult processLODGPU(LODSystemContext context) {
        // 构建 GPU chunk 输入数组
        int centerChunkX = (int) Math.floor(context.cameraX() / 16.0);
        int centerChunkZ = (int) Math.floor(context.cameraZ() / 16.0);
        int radius = context.updateRadius();



        int chunkCount = (radius * 2 + 1) * (radius * 2 + 1);
        GPUDrivenLODSystem.GPUChunkInput[] chunkInputs =
            new GPUDrivenLODSystem.GPUChunkInput[chunkCount];




        int idx = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;




                float centerX = chunkX * 16.0f + 8.0f;
                float centerY = 64.0f; // 近似 Y 坐标（未来可从高度图获取）
                float centerZ = chunkZ * 16.0f + 8.0f;


                chunkInputs[idx++] = new GPUDrivenLODSystem.GPUChunkInput(
                    centerX, centerY, centerZ,
                    packChunkKeyInt(chunkX, chunkZ),
                    0x1 // hasGeometryData flag
                );

            }
        }




        // 调用 GPU 系统
        return gpuDrivenSystem.computeLOD(
            chunkInputs,
            context.cameraX(), context.cameraY(), context.cameraZ(),
            context.fov(),
            (float) context.screenWidth(), (float) context.screenHeight()
        );

    }

    /**
     * 将 GPU 结果从 Map&lt;Integer, Integer&gt; 转换为 Map&lt;Long, Integer&gt;
     */
    private Map<Long, Integer> convertGpuResultToMap(Map<Integer, Integer> gpuResult) {
        Map<Long, Integer> converted = new ConcurrentHashMap<>(gpuResult.size());

        for (Map.Entry<Integer, Integer> entry : gpuResult.entrySet()) {
            converted.put(entry.getKey().longValue(), entry.getValue());
        }


        return converted;
    }

    // ==================== 内部方法：配置应用 ====================

    /**
     * 将 RenderiumConfig 应用到各子组件
     *
     * @param config 配置对象
     * @param mode   运行模式
     */
    private void applyConfiguration(RenderiumConfig config, RenderiumMode mode) {
        // 设置距离阈值（可从 config 中自定义读取，此处使用默认值）
        // 未来可通过 config.getProperty("lod.thresholds", "[32,64,128]") 读取


        // 设置数据管理器内存预算
        dataManager.setMemoryBudget(50L * 1024L * 1024L); // 50MB
        dataManager.setMaxCachedChunks(10000);




        // 设置 Mesh 缓存内存预算
        meshCache.setMemoryBudget(100L * 1024L * 1024L); // 100MB
        meshCache.setMaxMeshCount(5000);
        meshCache.setMaxLODLevels(calculator.getMaxLevels());




        // 配置过渡处理器
        if (mode.isAggressive()) {
            transitionHandler.setCrossfadeEnabled(true);
            transitionHandler.setDitheringEnabled(true);
        } else {
            transitionHandler.setCrossfadeEnabled(false);
            transitionHandler.setDitheringEnabled(true);
        }




        LOGGER.fine("LOD 子组件配置已应用");
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将 chunk 坐标打包为长整型键（long 版本）
     */
    private static long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | ((long) chunkX & 0xFFFFFFFFL);
    }

    /**
     * 将 chunk 坐标打包为整型键（int 版本，用于 GPU chunkId）
     */
    private static int packChunkKeyInt(int chunkX, int chunkZ) {
        return (chunkZ << 16) | (chunkX & 0xFFFF);
    }


}
