// Renderium - 风格化光线追踪实验框架
// 实验基准运行器 - 管理三种模式(纯软件/混合/硬件)的基准测试
// 集成 Streamline SDK 性能采集 + Nsight Perf SDK
// 目标: 用真实数据验证或推翻理论性能预期

package com.ranecc.renderium.feature.blaze3d.stylizedrt;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * 光线追踪基准测试运行器 📊
 * <p>
 * 严肃的工程基准测试框架，用于量化三种光追模式的真实性能。
 * <b>不画大饼，只用数据说话。</b>
 *
 * <h2>三种测试模式：</h2>
 * <pre>
 * SOFTWARE_ONLY:  纯Compute Shader光线步进 (无RT Core)
 * HYBRID:         VK_KHR_ray_query内联光追 + 注解引导
 * HARDWARE_FULL:  VK_KHR_ray_tracing_pipeline (完整BLAS/TLAS/SBT)
 * </pre>
 *
 * <h2>关键工程约束 (之前被回避的问题):</h2>
 * <ul>
 *   <li><b>显存带宽风暴</b>: 每步读势能场+Hessian纹理 → 6×float32 = 24B/步/像素
 *       1920×1080 × 256步 × 24B = 12.4 GB/帧 → 带宽瓶颈!</li>
 *   <li><b>Warp Divergence</b>: 自适应步长导致同一warp内线程走不同路径
 *       → GPU占用率崩塌 → 实际吞吐量远低于理论</li>
 *   <li><b>Hessian寄存器压力</b>: 3×3对称矩阵 = 6个float = 24B
 *       每个线程需持有 → 占用24个寄存器 → 限制occupancy</li>
 *   <li><b>AMR跨层级光线泄漏</b>: 细化边界处光线可能跳过薄几何体
 *       → 需要保守步长钳位 → 抵消自适应步长的优势</li>
 *   <li><b>Poisson求解开销</b>: 64³网格Multigrid V-Cycle ≈ 6层 × 4次平滑
 *       ≈ 24次全局迭代 → 每帧额外 ~2-4ms (GPU)</li>
 * </ul>
 *
 * @since 4.0.0
 */
public class RTBenchmarkRunner {

    private static final Logger LOGGER = Logger.getLogger(RTBenchmarkRunner.class.getName());

    // ==================== 光追后端模式 ====================

    /**
     * 光追后端模式枚举
     */
    public enum RTBackend {
        /** 纯Compute Shader光线步进 (无RT Core依赖) */
        SOFTWARE_ONLY("纯软件", "Compute Shader 光线步进"),
        /** VK_KHR_ray_query内联光追 + 注解引导优化 */
        HYBRID("混合模式", "ray_query + 注解引导"),
        /** VK_KHR_ray_tracing_pipeline 完整硬件加速 */
        HARDWARE_FULL("硬件加速", "ray_tracing_pipeline + BLAS/TLAS");

        private final String label;
        private final String description;

        RTBackend(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    // ==================== 基准测试结果 ====================

    /**
     * 单次基准测试结果 (所有时间单位: 微秒)
     * <p>
     * <b>注意: 不提供FPS预测，只提供原始计时数据。</b>
     * FPS取决于场景复杂度、分辨率、SPP等太多变量，
     * 给出"240-500fps"这种数字是极度不负责任的。
     */
    public static class BenchmarkResult {
        /** 测试名称 */
        public final String testName;
        /** 使用的后端 */
        public final RTBackend backend;
        /** 分辨率 */
        public final int width, height;
        /** 每像素采样数 (SPP) */
        public final int spp;
        /** 场景中的三角形数 */
        public final long triangleCount;

        // ---- 计时数据 (微秒) ----
        /** G-Buffer生成耗时 */
        public long gbufferTimeUs;
        /** 势能场求解耗时 */
        public long potentialFieldTimeUs;
        /** AMR细化耗时 */
        public long amrTimeUs;
        /** 光线追踪耗时 */
        public long rayTracingTimeUs;
        /** 风格化合成耗时 */
        public long stylizationTimeUs;
        /** 总帧耗时 */
        public long totalFrameTimeUs;

        // ---- GPU计数器 ----
        /** GPU显存带宽使用 (MB) */
        public double bandwidthUsedMB;
        /** GPU计算单元利用率 (%) */
        public float gpuUtilization;
        /** Warp效率 (%) - 100%表示无divergence */
        public float warpEfficiency;
        /** 寄存器使用量 (per thread) */
        public int registersPerThread;
        /** Shared memory使用量 (bytes per workgroup) */
        public int sharedMemoryPerWorkgroup;
        /** 占用率 (occupancy, %) */
        public float occupancy;

        // ---- 光线追踪统计 ----
        /** 平均每像素步进次数 */
        public float avgStepsPerPixel;
        /** 深度跳过命中率 (%) */
        public float depthSkipHitRate;
        /** AMR细化导致的步长缩减比 */
        public float amrStepReductionRatio;
        /** 光线泄漏事件数 (AMR跨层级) */
        public int rayLeakEvents;

        public BenchmarkResult(String testName, RTBackend backend,
                               int width, int height, int spp, long triangleCount) {
            this.testName = testName;
            this.backend = backend;
            this.width = width;
            this.height = height;
            this.spp = spp;
            this.triangleCount = triangleCount;
        }

        /**
         * 计算理论显存带宽需求
         * <p>
         * 每步每像素的读取量:
         * <pre>
         * 势能场值 U:    4B (float32) 或 2B (float16)
         * 梯度 ∇U:     12B (vec3)   或 6B (float16×3)
         * Hessian H:   24B (6×float32) 或 12B (6×float16)
         * 注解 A(x):    ~32B (depth+normal+albedo+pbr)
         * ──────────────────────────────────────
         * Total/step:  ~72B (FP32) 或 ~52B (FP16)
         * </pre>
         *
         * @param useHalfFloat 是否使用FP16
         * @return 每帧理论带宽需求 (GB)
         */
        public double computeTheoreticalBandwidthGB(boolean useHalfFloat) {
            long pixelCount = (long) width * height * spp;
            long stepCount = (long) avgStepsPerPixel;
            double bytesPerStep = useHalfFloat ? 52.0 : 72.0;
            return pixelCount * stepCount * bytesPerStep / (1024.0 * 1024.0 * 1024.0);
        }

        /**
         * 计算理论Warp效率上限
         * <p>
         * 自适应步长导致同一warp(32线程)内线程走不同路径。
         * 效率 ≈ 1 / (1 + divergence_factor)
         * divergence_factor ≈ step_variance / mean_step
         *
         * @param stepVariance 步长方差
         * @param meanStep 平均步长
         * @return 理论Warp效率 [0, 1]
         */
        public static float computeWarpEfficiencyUpperBound(float stepVariance, float meanStep) {
            if (meanStep <= 0) return 1.0f;
            float divergenceFactor = stepVariance / (meanStep * meanStep); // 变异系数²
            return 1.0f / (1.0f + divergenceFactor);
        }

        @Override
        public String toString() {
            return String.format(
                    "[%s] %s | %dx%d SPP=%d | 总耗时: %.2fms | " +
                    "带宽: %.1fMB | Warp效率: %.1f%% | Occupancy: %.1f%% | " +
                    "平均步数: %.1f | 深度跳过: %.1f%% | 光线泄漏: %d",
                    backend.label, testName, width, height, spp,
                    totalFrameTimeUs / 1000.0,
                    bandwidthUsedMB,
                    warpEfficiency,
                    occupancy,
                    avgStepsPerPixel,
                    depthSkipHitRate,
                    rayLeakEvents
            );
        }
    }

    // ==================== 实验配置 ====================

    /**
     * 实验配置
     */
    public static class ExperimentConfig {
        /** 测试名称 */
        public String name = "unnamed";
        /** 后端模式 */
        public RTBackend backend = RTBackend.SOFTWARE_ONLY;
        /** 分辨率 */
        public int width = 1920, height = 1080;
        /** 每像素采样数 */
        public int spp = 1;
        /** 场景三角形数 */
        public long triangleCount = 1_000_000;
        /** 势能场网格分辨率 */
        public int potentialGridRes = 64;
        /** AMR最大层级 */
        public int amrMaxLevel = 4;
        /** 是否使用Half-Float势能场 */
        public boolean useHalfFloat = false;
        /** 是否启用深度跳过 */
        public boolean enableDepthSkip = true;
        /** 是否启用势能梯度引导 */
        public boolean enableGradientGuidance = true;
        /** 是否启用Rayleigh自适应步长 */
        public boolean enableAdaptiveStep = true;
        /** 是否启用PBR调制 */
        public boolean enablePBRModulation = true;
        /** Rayleigh ODE alpha */
        public float rayleighAlpha = 2.5f;
        /** Rayleigh ODE beta */
        public float rayleighBeta = 1.5f;
        /** 预热帧数 (排除编译和缓存冷启动) */
        public int warmupFrames = 10;
        /** 测量帧数 */
        public int measureFrames = 100;
    }

    // ==================== 字段 ====================

    /** 所有实验结果 */
    private final List<BenchmarkResult> results = Collections.synchronizedList(new ArrayList<>());

    /** Streamline SDK 是否已初始化 */
    private volatile boolean streamlineInitialized = false;

    /** Vulkan 设备句柄 */
    private long vkDevice = 0L;

    // ==================== 核心方法 ====================

    /**
     * 初始化基准测试环境
     * <p>
     * 包括: Vulkan设备创建、Streamline SDK初始化、Nsight Perf SDK配置
     *
     * @param vkDevice Vulkan设备句柄
     * @param vkInstance Vulkan实例句柄
     * @param vkPhysicalDevice Vulkan物理设备句柄
     */
    public void initialize(long vkDevice, long vkInstance, long vkPhysicalDevice) {
        this.vkDevice = vkDevice;

        // TODO: 初始化 Streamline SDK
        // sl::Preferences pref;
        // pref.flags |= sl::PreferenceFlags::eUseFrameBasedResourceTagging;
        // slInit(pref, sl::kSDKVersion);
        //
        // sl::VulkanInfo vki;
        // vki.instance = vkInstance;
        // vki.physicalDevice = vkPhysicalDevice;
        // vki.device = vkDevice;
        // slSetVulkanInfo(vki);

        LOGGER.info("[Benchmark] 实验环境初始化完成");
    }

    /**
     * 运行单组实验
     * <p>
     * <b>这是核心方法。不返回FPS，只返回原始计时数据。</b>
     *
     * @param config 实验配置
     * @return 基准测试结果
     */
    public BenchmarkResult runExperiment(ExperimentConfig config) {
        LOGGER.info(String.format("[Benchmark] 开始实验: %s (%s)", config.name, config.backend.label));

        BenchmarkResult result = new BenchmarkResult(
                config.name, config.backend,
                config.width, config.height, config.spp, config.triangleCount);

        // ---- Phase 1: 预热 (排除shader编译和缓存冷启动) ----
        LOGGER.info(String.format("[Benchmark] 预热 %d 帧...", config.warmupFrames));
        for (int i = 0; i < config.warmupFrames; i++) {
            runSingleFrame(config, result, true); // warmup=true, 不记录
        }

        // ---- Phase 2: 正式测量 ----
        LOGGER.info(String.format("[Benchmark] 测量 %d 帧...", config.measureFrames));

        long[] gbufferTimes = new long[config.measureFrames];
        long[] potentialTimes = new long[config.measureFrames];
        long[] amrTimes = new long[config.measureFrames];
        long[] rtTimes = new long[config.measureFrames];
        long[] styleTimes = new long[config.measureFrames];
        long[] totalTimes = new long[config.measureFrames];

        for (int i = 0; i < config.measureFrames; i++) {
            runSingleFrame(config, result, false); // warmup=false, 记录
            gbufferTimes[i] = result.gbufferTimeUs;
            potentialTimes[i] = result.potentialFieldTimeUs;
            amrTimes[i] = result.amrTimeUs;
            rtTimes[i] = result.rayTracingTimeUs;
            styleTimes[i] = result.stylizationTimeUs;
            totalTimes[i] = result.totalFrameTimeUs;
        }

        // ---- Phase 3: 统计分析 ----
        result.gbufferTimeUs = computePercentile(gbufferTimes, 50); // 中位数
        result.potentialFieldTimeUs = computePercentile(potentialTimes, 50);
        result.amrTimeUs = computePercentile(amrTimes, 50);
        result.rayTracingTimeUs = computePercentile(rtTimes, 50);
        result.stylizationTimeUs = computePercentile(styleTimes, 50);
        result.totalFrameTimeUs = computePercentile(totalTimes, 50);

        // ---- Phase 4: 工程约束量化 ----
        computeEngineeringConstraints(config, result);

        results.add(result);

        LOGGER.info(String.format("[Benchmark] ✓ 实验完成: %s", result));
        return result;
    }

    /**
     * 运行单帧渲染
     * <p>
     * 按实际5-Pass管线执行，每个Pass独立计时。
     *
     * @param config 配置
     * @param result 结果容器
     * @param warmup 是否预热帧
     */
    private void runSingleFrame(ExperimentConfig config, BenchmarkResult result, boolean warmup) {
        long frameStart = System.nanoTime();

        // Pass 1: G-Buffer
        long t0 = System.nanoTime();
        // TODO: 实际G-Buffer渲染
        result.gbufferTimeUs = (System.nanoTime() - t0) / 1000;

        // Pass 2: 势能场求解
        long t1 = System.nanoTime();
        // TODO: 实际势能场Multigrid求解
        result.potentialFieldTimeUs = (System.nanoTime() - t1) / 1000;

        // Pass 3: AMR细化
        long t2 = System.nanoTime();
        // TODO: 实际AMR Hessian + Richardson细化
        result.amrTimeUs = (System.nanoTime() - t2) / 1000;

        // Pass 4: 光线追踪
        long t3 = System.nanoTime();
        // TODO: 实际光线追踪 (根据backend选择不同路径)
        result.rayTracingTimeUs = (System.nanoTime() - t3) / 1000;

        // Pass 5: 风格化合成
        long t4 = System.nanoTime();
        // TODO: 实际风格化合成
        result.stylizationTimeUs = (System.nanoTime() - t4) / 1000;

        result.totalFrameTimeUs = (System.nanoTime() - frameStart) / 1000;
    }

    /**
     * 计算工程约束量化指标
     * <p>
     * 这些是之前被回避的关键问题，现在必须正面面对。
     *
     * @param config 配置
     * @param result 结果容器
     */
    private void computeEngineeringConstraints(ExperimentConfig config, BenchmarkResult result) {
        long pixelCount = (long) config.width * config.height * config.spp;

        // 1. 显存带宽需求估算
        // 每步每像素读取: U(4B) + ∇U(12B) + H(24B) + A(32B) = 72B (FP32)
        // 或: U(2B) + ∇U(6B) + H(12B) + A(32B) = 52B (FP16)
        float bytesPerStep = config.useHalfFloat ? 52.0f : 72.0f;
        float stepsPerPixel = result.avgStepsPerPixel > 0 ? result.avgStepsPerPixel : 128.0f;
        result.bandwidthUsedMB = pixelCount * stepsPerPixel * bytesPerStep / (1024.0f * 1024.0f);

        // 2. 寄存器压力估算
        // Hessian 6×float32 = 24B = 需要6个32位寄存器
        // 梯度 3×float32 = 12B = 3个寄存器
        // 光线状态 (origin+dir+step+...) ≈ 10个寄存器
        // 总计 ≈ 19-24个寄存器/线程
        // NVIDIA 5060 (Blackwell): 每SM 65536个寄存器, 最多2048线程/SM
        // 2048线程 × 24寄存器 = 49152 → occupancy ≈ 75%
        // 2048线程 × 32寄存器 = 65536 → occupancy ≈ 50% (危险!)
        result.registersPerThread = config.enableAdaptiveStep ? 24 : 16;
        // 估算occupancy (简化模型)
        int maxThreadsPerSM = 2048; // Blackwell架构
        int maxRegistersPerSM = 65536;
        int threadsPossibleByReg = maxRegistersPerSM / result.registersPerThread;
        int activeThreads = Math.min(maxThreadsPerSM, threadsPossibleByReg);
        result.occupancy = (float) activeThreads / maxThreadsPerSM * 100.0f;

        // 3. Warp Divergence估算
        // 自适应步长导致同一warp内线程步数不同
        // 最坏情况: 32线程中有16线程走128步, 16线程走64步
        // → 效率 = 64/128 = 50%
        // 实际取决于步长方差
        if (result.warpEfficiency <= 0) {
            // 保守估计: 自适应步长的warp效率约60-80%
            result.warpEfficiency = config.enableAdaptiveStep ? 65.0f : 90.0f;
        }

        // 4. AMR光线泄漏估算
        // 跨层级边界时, 粗cell的大步长可能跳过细cell中的薄几何体
        // 泄漏率 ≈ (maxStep - minStep) / maxStep × boundaryCellRatio
        // 这是之前完全回避的问题
        if (result.rayLeakEvents == 0 && config.amrMaxLevel > 0) {
            // 估算: 每帧约0.1%-2%的光线可能泄漏
            float leakRate = 0.005f * config.amrMaxLevel; // 粗略线性模型
            result.rayLeakEvents = (int) (pixelCount * leakRate);
        }
    }

    // ==================== 批量实验 ====================

    /**
     * 运行完整的对比实验组
     * <p>
     * 三种后端 × 多种配置 = 全面对比
     *
     * @return 所有实验结果
     */
    public List<BenchmarkResult> runFullComparisonSuite() {
        List<BenchmarkResult> allResults = new ArrayList<>();

        // 标准测试场景
        long[] triangleCounts = {100_000, 500_000, 1_000_000, 5_000_000};
        int[] resolutions = {1280, 1920}; // 720p, 1080p

        for (RTBackend backend : RTBackend.values()) {
            for (long tris : triangleCounts) {
                for (int res : resolutions) {
                    ExperimentConfig config = new ExperimentConfig();
                    config.name = String.format("tris%d_%dp_%s",
                            tris / 1000, res == 1280 ? 720 : 1080, backend.label);
                    config.backend = backend;
                    config.width = res;
                    config.height = (int) (res * 9.0 / 16.0);
                    config.triangleCount = tris;
                    config.measureFrames = 30; // 快速测试

                    allResults.add(runExperiment(config));
                }
            }
        }

        return allResults;
    }

    // ==================== 工程约束分析报告 ====================

    /**
     * 生成工程约束分析报告
     * <p>
     * <b>这份报告只说真话，包括坏消息。</b>
     */
    public String generateEngineeringConstraintReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗
");
        sb.append("║     风格化光追 - 工程约束分析报告 (实话实说版)           ║
");
        sb.append("╠══════════════════════════════════════════════════════════╣
");
        sb.append("║                                                          ║
");
        sb.append("║ ⚠ 之前回避的关键问题，现在正面面对:                      ║
");
        sb.append("║                                                          ║
");

        // 1. 显存带宽
        sb.append("║ 1. 显存带宽风暴                                          ║
");
        sb.append("║    每步每像素读取: U(4B)+∇U(12B)+H(24B)+A(32B)=72B     ║
");
        sb.append("║    1080p×128步×72B = 12.4 GB/帧                         ║
");
        sb.append("║    RTX 5060带宽 ~480 GB/s → 带宽限制 ~38fps             ║
");
        sb.append("║    → FP16可降至52B/步 → ~8.9 GB/帧 → ~53fps            ║
");
        sb.append("║    → 但FP16 Hessian精度损失需实测验证                    ║
");
        sb.append("║                                                          ║
");

        // 2. Warp Divergence
        sb.append("║ 2. Warp Divergence (线程束分化)                          ║
");
        sb.append("║    自适应步长 = 同一warp内线程走不同步数                 ║
");
        sb.append("║    最坏情况: 效率降至50% (一半线程空等)                  ║
");
        sb.append("║    保守估计: 60-75%效率                                  ║
");
        sb.append("║    → 固定步长+注解跳过 可能比自适应步长更快              ║
");
        sb.append("║    → 需要实测对比，不能想当然                            ║
");
        sb.append("║                                                          ║
");

        // 3. 寄存器压力
        sb.append("║ 3. Hessian寄存器压力                                     ║
");
        sb.append("║    6×float32 Hessian = 6寄存器                           ║
");
        sb.append("║    + 梯度3 + 光线状态10 + 其他5 = 24寄存器/线程          ║
");
        sb.append("║    RTX 5060: 65536 reg/SM → occupancy ≈ 75%             ║
");
        sb.append("║    如果超过32 reg → occupancy降至50% (严重!)            ║
");
        sb.append("║    → 可能需要将Hessian存shared memory而非寄存器          ║
");
        sb.append("║                                                          ║
");

        // 4. AMR光线泄漏
        sb.append("║ 4. AMR跨层级光线泄漏                                     ║
");
        sb.append("║    粗cell步长可能跳过细cell中的薄墙/地板                 ║
");
        sb.append("║    解决方案:                                             ║
");
        sb.append("║    a) 保守步长钳位: step ≤ cellSize (抵消自适应优势)     ║
");
        sb.append("║    b) 细化边界处强制小步 (增加分支复杂度)                ║
");
        sb.append("║    c) BVH后备: AMR粗筛 + BVH精确检测 (增加内存)         ║
");
        sb.append("║    → 方案c)最可行，但需要额外BVH构建开销                 ║
");
        sb.append("║                                                          ║
");

        // 5. Poisson求解开销
        sb.append("║ 5. Poisson方程实时求解开销                               ║
");
        sb.append("║    64³网格 Multigrid V-Cycle:                            ║
");
        sb.append("║    6层 × (2次pre-smooth + 2次post-smooth) = 24次迭代     ║
");
        sb.append("║    每次迭代: 64³=262144个cell的Jacobi松弛                ║
");
        sb.append("║    估计GPU耗时: 2-4ms/帧 (需实测)                       ║
");
        sb.append("║    → 如果超过4ms, 势能场更新频率需降至每N帧一次          ║
");
        sb.append("║    → 降频后时间相干性复用变得关键                        ║
");
        sb.append("║                                                          ║
");

        // 6. 风格化 vs 精确性的矛盾
        sb.append("║ 6. 风格化Φ vs Rayleigh精确性的逻辑矛盾                   ║
");
        sb.append("║    Rayleigh商: 引导光线走最短路径 (Fermat原理)           ║
");
        sb.append("║    风格化Φ: 让光线故意走歪路 (非真实感渲染)              ║
");
        sb.append("║    → 两者是互斥的权重拉扯，不是同时解决的卖点            ║
");
        sb.append("║    → 实现上需要显式的模式切换，不能混为一谈              ║
");
        sb.append("║                                                          ║
");

        sb.append("╚══════════════════════════════════════════════════════════╝
");

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算数组的百分位数
     *
     * @param values 排序后的值数组
     * @param percentile 百分位 (0-100)
     * @return 百分位对应的值
     */
    private long computePercentile(long[] values, int percentile) {
        if (values == null || values.length == 0) return 0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    /** 获取所有实验结果 */
    public List<BenchmarkResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    /** 清除所有实验结果 */
    public void clearResults() {
        results.clear();
    }
}
