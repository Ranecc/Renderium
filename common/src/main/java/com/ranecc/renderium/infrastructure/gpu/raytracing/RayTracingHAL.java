package com.ranecc.renderium.infrastructure.gpu.raytracing;

import java.util.logging.Logger;

import com.ranecc.renderium.infrastructure.gpu.VulkanDeviceHolder;

/**
 * Phase 5: Ray Tracing HAL — 跨厂商硬件加速光线追踪抽象
 *
 * <p>隔离 NVIDIA (RTX RT Cores)、AMD (RDNA3 Ray Accelerators)、
 * Intel (Xe-HPG Arc RT Units) 三家技术路径差异，
 * 统一使用 Khronos KHR 跨厂商扩展标准。
 *
 * <h2>厂商技术路径对比：</h2>
 * <pre>
 * | 维度          | NVIDIA Ada Lovelace        | AMD RDNA 3               | Intel Xe-HPG            |
 * |--------------|---------------------------|-------------------------|-------------------------|
 * | 遍历模式      | RT Core 固定函数 (DXR 1.0) | Shader驱动+LDS栈 (DXR 1.1)| RTU 固定函数+线程排序(DXR 1.0) |
 * | 最优API       | VK_KHR_ray_tracing_pipeline| VK_KHR_ray_query (inline)| VK_KHR_ray_query        |
 * | 硬件加速      | 3rd Gen RT Core + SER      | 2nd Gen Ray Accelerator  | RTU + Thread Sorting    |
 * | 每CU射线加速器 | 1 RT Core/SM              | 1 Ray Accelerator/CU     | 1 RTU/Xe-core          |
 * | Alpha Test优化| OMM (Opacity Micro-Maps)   | 软件 Any-Hit Shader      | 软件 Any-Hit Shader     |
 * | 几何细节      | DMM (Displaced Micro-Meshes)| Mesh Shader LOD          | Mesh Shader LOD         |
 * | 线程重排      | SER (Shader Exec Reordering)| 无                       | Thread Sorting Unit     |
 * </pre>
 *
 * <h2>参考文献：</h2>
 * <ul>
 *   <li>NVIDIA. "Ada GPU Architecture Whitepaper" (2022). §Ray Tracing (3rd Gen RT Core, SER, OMM, DMM)</li>
 *   <li>AMD. "RDNA 3 Architecture Deep Dive" (2022). §Second-gen Ray Accelerators, BVH traversal</li>
 *   <li>Intel. "Introduction to the Xe-HPG Architecture" (2023). §Ray Tracing Unit, Thread Sorting</li>
 *   <li>Intel. "Developer Guide for Real-Time Ray Tracing in Games" (2023). §DXR1.1 RayQueries bypass TSU</li>
 *   <li>Chips and Cheese. "Raytracing on Meteor Lake's iGPU" (2024). §Cross-vendor traversal comparison</li>
 *   <li>Bavoil, L. "Path Tracing Optimization in Indiana Jones: SER and Live State Reductions" (2025). NVIDIA Developer Blog</li>
 *   <li>Bavoil, L. "Path Tracing Optimization in Indiana Jones: OMMs and BLAS Compaction" (2025). NVIDIA Developer Blog</li>
 * </ul>
 *
 * <h2>Per-Vendor 优化策略（不暴力穷举）：</h2>
 *
 * <h3>NVIDIA Ada/Blackwell:</h3>
 * <ol>
 *   <li>VK_KHR_ray_tracing_pipeline + SER — 发散射线重排，24% 性能提升</li>
 *   <li>OMM — Alpha 测试几何体遍历时间从 7.90ms → 3.58ms</li>
 *   <li>DMM — BVH 构建 10x 加速，内存降 20x</li>
 *   <li>RTX Mega Geometry — Cluster BLAS，100x 三角形数</li>
 *   <li>Partitioned TLAS — 只重建变化部分</li>
 * </ol>
 *
 * <h3>AMD RDNA 3:</h3>
 * <ol>
 *   <li>VK_KHR_ray_query (inline) 优先 — 避免 DXR 1.0 shader 切换开销</li>
 *   <li>单 Shader 程序处理全遍历 — 利用 LDS 栈管理 BVH 遍历</li>
 *   <li>1.5x VGPR → 增大 in-flight 射线数</li>
 *   <li>Mesh Shader LOD — 动态降低 BVH 复杂度</li>
 *   <li>基于 RayQuery 的 coarse→fine 自适应密度</li>
 * </ol>
 *
 * <h3>Intel Arc (Xe-HPG):</h3>
 * <ol>
 *   <li>VK_KHR_ray_query (inline) — 绕过 Thread Sorting Unit 减少延迟</li>
 *   <li>小射线负载用 RayQuery（不启动新线程）</li>
 *   <li>大射线负载用 DXR 1.0 Pipeline（TSU 排序最大化 SIMD coherence）</li>
 *   <li>每个 Xe-core 的 RTU 含 2 个 BVH 遍历管线 + 1 个 triangle intersection 管线</li>
 * </ol>
 *
 * <h2>Renderium 优化策略（通用，不依赖厂商）：</h2>
 * <ol>
 *   <li><b>Stage1→Stage2 层次化</b>：Coarse BVH 遍历 → importance map → Fine hit shading</li>
 *   <li><b>视锥聚焦</b>：射线预算优先分配给相机 FOV 内、screen-center 附近</li>
 *   <li><b>几何 LOD</b>：距离越远 → BVH 层级越粗 → 射线数越少</li>
 *   <li><b>重要性采样</b>：射线优先朝向动态光源，跳过静态烘焙区域</li>
 *   <li><b>时域复用</b>：静态场景复用上一帧 Stage 1 结果</li>
 *   <li><b>AS 分区重建</b>：只重建变化的 chunk，不重构建整个场景 BVH</li>
 * </ol>
 */
public final class RayTracingHAL {

    private static final Logger LOGGER = Logger.getLogger("Renderium|RayTracingHAL");

    // ==================== 厂商枚举 + 优化路径 ====================

    /**
     * 厂商枚举 — 携带对应优化路径的扩展偏好和能力指标。
     *
     * <p>来源：
     * <ul>
     *   <li>NVIDIA: Ada Whitepaper §RT Cores, SER, OMM, DMM</li>
     *   <li>AMD: RDNA 3 Deep Dive §Ray Accelerators, BVH in Texture Units</li>
     *   <li>Intel: Xe-HPG Whitepaper §RTU + TSU; Dev Guide §DXR1.1 RayQuery bypass TSU</li>
     * </ul>
     */
    public enum GPUVendor {
        /** NVIDIA Ada/Blackwell — 3rd Gen RT Core + SER + OMM + DMM */
        NVIDIA(0x10DE, "NVIDIA", true, true,
            RTExtensionPreference.PIPELINE_WITH_SER,
            90, 95),

        /** AMD RDNA 3 — 2nd Gen Ray Accelerator, LDS-based traversal stack */
        AMD(0x1002, "AMD", true, true,
            RTExtensionPreference.INLINE_RAY_QUERY,
            70, 80),

        /** Intel Arc (Xe-HPG) — RTU + Thread Sorting Unit */
        INTEL(0x8086, "Intel", true, true,
            RTExtensionPreference.INLINE_RAY_QUERY,
            65, 75),

        /** macOS MoltenVK / unknown */
        UNKNOWN(0x0000, "Generic", false, false,
            RTExtensionPreference.DISABLED,
            0, 50);

        public final int vendorID;
        public final String displayName;
        public final boolean supportsKHR_AccelStructure;
        public final boolean supportsKHR_Pipeline;
        /** 此厂商的推荐扩展路径 */
        public final RTExtensionPreference extensionPref;
        /** 相对 NVIDIA 的 RT 性能（%） */
        public final int rtPerformancePercent;
        /** 建议的射线预算百分比（相对于最高画质） */
        public final int suggestedRayBudgetPercent;

        GPUVendor(int vendorID, String displayName,
                  boolean supportsKHR_AccelStructure, boolean supportsKHR_Pipeline,
                  RTExtensionPreference extensionPref,
                  int rtPerformancePercent, int suggestedRayBudgetPercent) {
            this.vendorID = vendorID;
            this.displayName = displayName;
            this.supportsKHR_AccelStructure = supportsKHR_AccelStructure;
            this.supportsKHR_Pipeline = supportsKHR_Pipeline;
            this.extensionPref = extensionPref;
            this.rtPerformancePercent = rtPerformancePercent;
            this.suggestedRayBudgetPercent = suggestedRayBudgetPercent;
        }
    }

    /**
     * 厂商特定的扩展优先级。
     *
     * <p>来源：Chips and Cheese (2024) 比较三家 traversal 实现：
     * <ul>
     *   <li>NVIDIA — DXR 1.0 pipeline 最优 (SER 重排发散射线)
     *   <li>AMD — DXR 1.1 RayQuery inline 最优 (单shader, LDS栈)
     *   <li>Intel — 小射线用 DXR 1.1 (绕过 TSU), 大射线用 DXR 1.0 (TSU 排序)
     * </ul>
     */
    public enum RTExtensionPreference {
        /** 禁用 RT */
        DISABLED,
        /** VK_KHR_ray_tracing_pipeline + SER (NVIDIA 最优) */
        PIPELINE_WITH_SER,
        /** VK_KHR_ray_query inline (AMD/Intel 最优) */
        INLINE_RAY_QUERY,
    }

    // ==================== RT 模式 ====================

    public enum RTMode {
        DISABLED("Disabled", 0, 0, 0),
        SHADOW_ONLY("Shadows Only", 1, 0, 0),
        REFLECTION("Reflection+Shadows", 1, 1, 0),
        FULL_GI("Full GI", 1, 2, 1),
        PATH_TRACE("Path Trace", 4, 4, 2);

        public final String label;
        public final int raysPerPixel;
        public final int diffuseBounces;
        public final int specularBounces;

        RTMode(String label, int rpp, int diffBounce, int specBounce) {
            this.label = label;
            this.raysPerPixel = rpp;
            this.diffuseBounces = diffBounce;
            this.specularBounces = specBounce;
        }
    }

    // ==================== 射线预算 ====================

    public static final class RayBudget {
        public int maxRaysPerFrame = 1024 * 1024;
        public int currentRaysThisFrame = 0;
        public long lastFrameRTCostNs = 0;
        public long targetBudgetNs = 2_000_000L;

        public void update(float deltaTimeMs) {
            if (lastFrameRTCostNs > targetBudgetNs * 2) {
                maxRaysPerFrame = (int) (maxRaysPerFrame * 0.8f);
            } else if (lastFrameRTCostNs < targetBudgetNs / 2) {
                maxRaysPerFrame = (int) (maxRaysPerFrame * 1.1f);
            }
            currentRaysThisFrame = 0;
        }

        public boolean canAfford(int rayCount) {
            return currentRaysThisFrame + rayCount <= maxRaysPerFrame;
        }

        public void spend(int rayCount) {
            currentRaysThisFrame += rayCount;
        }
    }

    // ==================== 视锥聚焦 ====================

    /**
     * 视锥聚焦采样分布 — center 100% → edge 25%。
     *
     * <p>理由：人眼对 center 区域细节感知最强（foveal vision），
     * 边缘区域可用较低密度采样 + 时域插值填充。
     */
    public static final class FrustumFocusRegion {
        public static final int ZONE_CENTER = 0;
        public static final int ZONE_MID = 1;
        public static final int ZONE_EDGE = 2;
        public static final int ZONE_OUTSIDE = 3;

        static final float[] DENSITY = {1.0f, 0.5f, 0.25f, 0.0f};

        public static float getDensity(float screenU, float screenV) {
            float distFromCenter = (float) Math.sqrt(
                (screenU - 0.5f) * (screenU - 0.5f) +
                (screenV - 0.5f) * (screenV - 0.5f)
            );
            if (distFromCenter < 0.21f) return DENSITY[0];
            if (distFromCenter < 0.50f) return DENSITY[1];
            return DENSITY[2];
        }
    }

    // ==================== 状态 ====================

    private static volatile RTMode currentMode = RTMode.DISABLED;
    private static volatile GPUVendor detectedVendor = GPUVendor.UNKNOWN;
    private static volatile RTExtensionPreference activeExtensionPref = RTExtensionPreference.DISABLED;
    private static volatile boolean initialized = false;
    private static final RayBudget budget = new RayBudget();

    private RayTracingHAL() {}

    public static boolean isAvailable() {
        return VulkanDeviceHolder.isAvailable() && detectedVendor.supportsKHR_AccelStructure;
    }

    public static RTMode getCurrentMode() { return currentMode; }
    public static GPUVendor getDetectedVendor() { return detectedVendor; }
    public static RayBudget getBudget() { return budget; }

    /**
     * 获取厂商推荐扩展路径。
     * 调用方根据此值决定使用 VK_KHR_ray_tracing_pipeline 还是
     * VK_KHR_ray_query 的 shader 路径。
     */
    public static RTExtensionPreference getActiveExtensionPref() { return activeExtensionPref; }

    public static void initialize(GPUVendor vendor) {
        if (initialized) return;
        initialized = true;
        detectedVendor = vendor;
        activeExtensionPref = vendor.extensionPref;

        if (!vendor.supportsKHR_AccelStructure) {
            LOGGER.info("Ray Tracing not available on " + vendor.displayName);
            currentMode = RTMode.DISABLED;
            return;
        }

        currentMode = selectOptimalMode(vendor);
        budget.maxRaysPerFrame = budget.maxRaysPerFrame * vendor.suggestedRayBudgetPercent / 100;

        LOGGER.info(String.format("RayTracingHAL: %s rt=%d%% budget=%d%% mode=%s ext=%s",
            vendor.displayName,
            vendor.rtPerformancePercent, vendor.suggestedRayBudgetPercent,
            currentMode.label, activeExtensionPref));
    }

    /**
     * 根据 GPU 能力选择最佳 RT 模式：
     * <ul>
     *   <li>NVIDIA → FULL_GI (RT 性能最强)
     *   <li>AMD → REFLECTION (inline RayQuery 降低切换开销)
     *   <li>Intel → SHADOW_ONLY (保守预算)
     * </ul>
     */
    private static RTMode selectOptimalMode(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> RTMode.FULL_GI;
            case AMD -> RTMode.REFLECTION;
            case INTEL -> RTMode.SHADOW_ONLY;
            default -> RTMode.DISABLED;
        };
    }

    public static void beginFrame(float deltaTimeMs) {
        if (currentMode == RTMode.DISABLED) return;
        budget.update(deltaTimeMs);
    }

    public static void endFrame(long rtCostNs) {
        budget.lastFrameRTCostNs = rtCostNs;
    }

    // ==================== LOD 几何选择 ====================

    /**
     * 根据距离选择 AS 细节层级。
     * <p>距离越远 → 更粗的 BLAS → 遍历更快但精度降低。
     */
    public static int selectGeometryLODLevel(float distance) {
        if (distance < 32f) return 0;
        if (distance < 64f) return 1;
        if (distance < 128f) return 2;
        return 3;
    }

    public static float getLODDensityFactor(int lodLevel) {
        return switch (lodLevel) {
            case 0 -> 1.00f;
            case 1 -> 0.50f;
            case 2 -> 0.25f;
            default -> 0.00f;
        };
    }

    // ==================== BVH 分区策略 ====================

    /**
     * 分区 TLAS 重建策略。
     *
     * <p>参考：
     * <ul>
     *   <li>VK_NV_partitioned_acceleration_structure — NVIDIA 分区 TLAS</li>
     *   <li>NVIDIA RTX Mega Geometry — Cluster BLAS, 只重建变化部分</li>
     * </ul>
     */
    public enum BVHRebuildStrategy {
        /** 每帧全量重建 */
        FULL_REBUILD,
        /** 仅重建变化的 chunk（分区 TLAS） */
        PARTITIONED_REBUILD,
        /** 静态几何永不重建 + 动态增量更新 */
        STATIC_BASE_DYNAMIC_ADD
    }

    public static BVHRebuildStrategy selectBVHStrategy(int staticChunkCount, int dynamicChunkCount) {
        float dynamicRatio = (float) dynamicChunkCount / Math.max(staticChunkCount + dynamicChunkCount, 1);
        if (dynamicRatio < 0.1f) return BVHRebuildStrategy.STATIC_BASE_DYNAMIC_ADD;
        if (dynamicRatio < 0.4f) return BVHRebuildStrategy.PARTITIONED_REBUILD;
        return BVHRebuildStrategy.FULL_REBUILD;
    }
}
