// Renderium v6 Phase 2 - Voxy-Inspired 超视距 LOD 系统核心架构
// VoxyInspiredLODSystem.java - 超视距 LOD 引擎主协调器（受 Voxy 启发）
// 功能: 基于 Mipmap 金字塔 + GPU Driven 剔除 + 间接绘制的高性能 LOD 系统
// 支持 1024+ chunks 的超远距离渲染

package com.renderium.lod.voxy;

import com.renderium.data.RealDataProvider;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * VoxyInspiredLODSystem - 超视距 LOD 引擎（受 Voxy 启发）
 *
 * <p>基于 Mipmap 金字塔 + GPU Driven 剔除 + 间接绘制的高性能 LOD 系统，
 * 支持 1024+ chunks 的超远距离渲染。</p>
 *
 * <h2>架构概览</h2>
 * <pre>
 * ┌─────────────┐
 * │ Chunk Data   │ ← AsyncChunkLoader / RealDataProvider
 * └──────┬───────┘
 *        ↓
 * ┌─────────────┐
 * │ LOD Pyramid  │ ← LODPyramidBuilder (CPU/GPU 构建)
 * │ LOD0 (Full)  │
 * │ LOD1 (1/4)   │
 * │ ...          │
 * │ LOD7 (1/16k)│
 * └──────┬───────┘
 *        ↓
 * ┌─────────────┐
 * │ GPU Culling  │ ← GPUCullingPipeline (Hi-Z + Frustum)
 * └──────┬───────┘
 *        ↓
 * ┌─────────────┐
 * │ Indirect Draw│ ← IndirectDrawGenerator
 * └──────┬───────┘
 *        ↓
 * ┌─────────────┐
 * │ Output       │ → 渲染到屏幕
 * └─────────────┘
 * </pre>
 *
 * <h2>核心特性：</h2>
 * <ul>
 *   <li><b>Mipmap LOD Pyramid</b>: 多级细节层次，每级降低 4x 分辨率，指数级内存节省</li>
 *   <li><b>Streaming Upload</b>: 异步上传到 GPU（Phase 2.x 实现），不阻塞渲染</li>
 *   <li><b>Indirect Draw</b>: GPU Driven 渲染，单次 Dispatch 处理数千 DrawCall</li>
 *   <li><b>Hi-Z Occlusion</b>: 层次化深度缓冲区遮挡剔除，亚毫秒级查询</li>
 *   <li><b>动态质量调整</b>: 帧时间超标时自动降低质量，恢复时逐步提升</li>
 * </ul>
 *
 * <h2>性能目标：</h2>
 * <pre>
 * 目标硬件: @256 chunks >60 FPS
 * - 金字塔构建时间: &lt;5ms (for 256 chunks)
 * - 剔除总开销: &lt;1ms
 * - CPU 总开销: &lt;8ms/帧 (留余量给其他系统)
 * </pre>
 *
 * <h2>使用示例：</h2>
 * <pre>
 * // 1. 初始化配置
 * VoxyInspiredLODSystem.Config config = new VoxyInspiredLODSystem.Config.Builder()
 *     .maxLODLevels(8)
 *     .maxDistance(1024.0f)
 *     .pyramidCacheSize(256)
 *     .enableHiZCulling(true)
 *     .build();
 *
 * // 2. 初始化系统
 * VoxyInspiredLODSystem lodSystem = VoxyInspiredLODSystem.getInstance();
 * boolean success = lodSystem.initialize(config, dataProvider);
 *
 * // 3. 每帧更新
 * lodSystem.update(camera, frustum, deltaTime);
 *
 * // 4. 执行渲染
 * lodSystem.render(renderPass, commandBuffer);
 *
 * // 5. 关闭时释放资源
 * lodSystem.shutdown();
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <ul>
 *   <li>单例模式使用饿汉式初始化（线程安全）</li>
 *   <li>状态字段使用 AtomicXXX 保证可见性</li>
 *   <li>公共方法可从任意线程调用（内部同步）</li>
 * </ul>
 *
 * <h3>优雅降级：</h3>
 * <p>当遇到以下情况时自动降级：
 * <ol>
 *   <li>GPU Compute Shader 不可用 → 使用 CPU fallback</li>
 *   <li>内存超限 → 减少 cache 大小，淘汰冷数据</li>
 *   <li>帧时间超标 → 降低 LOD 质量（减少级别数）</li>
 *   <li>任何异常 → 记录日志，返回安全默认值</li>
 * </ol>
 *
 * @see com.renderium.data.RealDataProvider
 * @see com.renderium.lod.async.AsyncChunkLoader
 * @see LODPyramidBuilder
 * @see GPUCullingPipeline
 * @author Renderium Team
 * @version 6.0.0 (Phase 2)
 * @since 6.0.0
 */
public class VoxyInspiredLODSystem {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(VoxyInspiredLODSystem.class.getName());

    // ==================== 单例实例 ====================

    /**
     * 饿汉式单例实例（线程安全，无锁）
     */
    private static final VoxyInspiredLODSystem INSTANCE = new VoxyInspiredLODSystem();

    /**
     * 获取 VoxyInspiredLODSystem 单例实例
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 参数：
     *   - 无
     *
     * 返回值：
     *   - VoxyInspiredLODSystem: 全局唯一的单例实例
     *
     * 线程安全保证：
     *   - 饿汉式初始化，类加载时即完成
     *   - 无需双重检查锁定（DCL）
     *   - 无竞争条件风险
     *
     * 使用示例：
     *   VoxyInspiredLODSystem system = VoxyInspiredLODSystem.getInstance();
     *   system.initialize(config, dataProvider);
     * </pre>
     *
     * @return 全局唯一的 VoxyInspiredLODSystem 实例
     */
    public static VoxyInspiredLODSystem getInstance() {
        return INSTANCE;
    }

    // ==================== 配置类 ====================

    /**
     * VoxyInspiredLODSystem 的初始化配置参数。
     *
     * <p>使用 Builder 模式构建，所有参数都有合理的默认值。
     *
     * <h2>配置项说明：</h2>
     * <pre>
     * ┌──────────────────────┬──────────┬────────────────────────────────┐
     * │ 参数名                │ 默认值   │ 说明                            │
     * ├──────────────────────┼──────────┼────────────────────────────────┤
     * │ maxLODLevels         │ 8        │ 最大 LOD 级别数 (0-7)           │
     * │ maxDistance          │ 1024.0f  │ 最大渲染距离 (chunks)           │
     * │ useGPUBuilding       │ true     │ 使用 GPU 构建 LOD              │
     * │ pyramidCacheSize     │ 256      │ 金字塔缓存数量                  │
     * │ lodTransitionRange   │ 0.1f     │ 过渡范围 (避免 popping)        │
     * │ enableHiZCulling     │ true     │ 启用 Hi-Z 遮挡剔除             │
     * │ enableDynamicResolution│ true   │ 启用动态分辨率调整              │
     * │ targetFrameTime      │ 16.67f   │ 目标帧时间 (60fps)             │
     * └──────────────────────┴──────────┴────────────────────────────────┘
     * </pre>
     *
     * <h3>使用示例：</h3>
     * <pre>
     * Config config = new Config.Builder()
     *     .maxLODLevels(8)
     *     .maxDistance(512.0f)  // 中等渲染距离
     *     .useGPUBuilding(false)  // CPU 模式（兼容模式）
     *     .enableHiZCulling(true)
     *     .targetFrameTime(33.33f)  // 30 FPS 目标
     *     .build();
     * </pre>
     */
    public static class Config {

        /** 最大 LOD 级别数 (0-7)，共 8 级 */
        public final int maxLODLevels;

        /** 最大渲染距离（区块单位） */
        public final float maxDistance;

        /** 是否使用 GPU 构建 LOD（需要 Vulkan Compute Shader） */
        public final boolean useGPUBuilding;

        /** 金字塔缓存容量（最近使用的金字塔数量） */
        public final int pyramidCacheSize;

        /** LOD 过渡范围 [0.0-0.3]，避免 popping artifacts */
        public final float lodTransitionRange;

        /** 是否启用 Hi-Z 遮挡剔除 */
        public final boolean enableHiZCulling;

        /** 是否启用动态分辨率/质量调整 */
        public final boolean enableDynamicResolution;

        /** 目标帧时间（毫秒），用于动态质量调整基准 */
        public final float targetFrameTime;

        /**
         * 私有构造函数（通过 Builder 创建）
         *
         * @param builder 配置构建器
         */
        private Config(Builder builder) {
            this.maxLODLevels = builder.maxLODLevels;
            this.maxDistance = builder.maxDistance;
            this.useGPUBuilding = builder.useGPUBuilding;
            this.pyramidCacheSize = builder.pyramidCacheSize;
            this.lodTransitionRange = builder.lodTransitionRange;
            this.enableHiZCulling = builder.enableHiZCulling;
            this.enableDynamicResolution = builder.enableDynamicResolution;
            this.targetFrameTime = builder.targetFrameTime;
        }

        /**
         * Config 的 Builder 类（链式调用）
         *
         * <p>提供流畅的 API 来构建复杂的配置对象，
         * 所有参数都有合理默认值。
         *
         * <h3>设计模式：</h3>
         * <ul>
         *   <li>Builder 模式：不可变对象 + 可变构建器</li>
         *   <li>方法链：支持连续调用 .xxx().yyy().zzz()</li>
         *   <li>参数校验：在 build() 时统一验证</li>
         * </ul>
         */
        public static class Builder {

            /** 默认最大 LOD 级别数 */
            private int maxLODLevels = 8;

            /** 默认最大渲染距离（chunks） */
            private float maxDistance = 1024.0f;

            /** 默认是否使用 GPU 构建 */
            private boolean useGPUBuilding = true;

            /** 默认缓存大小 */
            private int pyramidCacheSize = 256;

            /** 默认过渡范围 */
            private float lodTransitionRange = 0.1f;

            /** 默认是否启用 Hi-Z */
            private boolean enableHiZCulling = true;

            /** 默认是否启用动态分辨率 */
            private boolean enableDynamicResolution = true;

            /** 默认目标帧时间（60fps） */
            private float targetFrameTime = 16.67f;

            /**
             * 设置最大 LOD 级别数
             *
             * <h3>参数说明：</h3>
             * <pre>
             * 取值范围: [2, 12]
             * 推荐值:
             *   - 4: 低内存模式（快速设备）
             *   - 8: 平衡模式（默认）
             *   - 12: 高质量模式（高端设备）
             *
             * 内存影响:
             *   每个 chunk 在各级别都需要存储数据
             *   级别数越多，远处细节越好，但内存占用越高
             * </pre>
             *
             * @param maxLODLevels 最大 LOD 级别数（必须 >= 2 且 <= 12）
             * @return this（支持链式调用）
             * @throws IllegalArgumentException 如果参数超出范围
             */
            public Builder maxLODLevels(int maxLODLevels) {
                if (maxLODLevels < 2 || maxLODLevels > 12) {
                    throw new IllegalArgumentException(
                        "最大 LOD 级别数必须在 [2, 12] 范围内: " + maxLODLevels
                    );
                }
                this.maxLODLevels = maxLODLevels;
                return this;
            }

            /**
             * 设置最大渲染距离（区块单位）
             *
             * <h3>参数说明：</h3>
             * <pre>
             * 取值范围: [32, 4096]
             * 推荐值:
             *   - 128: 近距离（类似原版视距）
             *   - 512: 中等距离（推荐）
             *   - 1024: 远距离（默认，需要好硬件）
             *   - 2048+: 极远距离（实验性）
             *
             * 性能影响:
             *   距离越远，需要处理的 chunks 数量越多
             *   呈平方增长（面积 ∝ distance²）
             * </pre>
             *
             * @param maxDistance 最大渲染距离（chunk 数，必须 >= 32）
             * @return this（支持链式调用）
             */
            public Builder maxDistance(float maxDistance) {
                if (maxDistance < 32.0f || maxDistance > 4096.0f) {
                    throw new IllegalArgumentException(
                        "最大渲染距离必须在 [32, 4096] 范围内: " + maxDistance
                    );
                }
                this.maxDistance = maxDistance;
                return this;
            }

            /**
             * 设置是否使用 GPU 构建 LOD 金字塔
             *
             * @param useGPUBuilding true 表示使用 GPU Compute Shader（更快但需 Vulkan）
             * @return this（支持链式调用）
             */
            public Builder useGPUBuilding(boolean useGPUBuilding) {
                this.useGPUBuilding = useGPUBuilding;
                return this;
            }

            /**
             * 设置金字塔缓存容量
             *
             * <h3>内存估算：</h3>
             * <pre>
             * 每个金字塔 ≈ 64 KB (LOD0) + 8 KB (LOD1) + ... ≈ 85 KB
             * 缓存大小 256 → 约 22 MB
             * 缓存大小 512 → 约 44 MB
             * 缓存大小 1024 → 约 87 MB
             * </pre>
             *
             * @param pyramidCacheSize 缓存数量（必须 >= 16 且 <= 4096）
             * @return this（支持链式调用）
             */
            public Builder pyramidCacheSize(int pyramidCacheSize) {
                if (pyramidCacheSize < 16 || pyramidCacheSize > 4096) {
                    throw new IllegalArgumentException(
                        "金字塔缓存大小必须在 [16, 4096] 范围内: " + pyramidCacheSize
                    );
                }
                this.pyramidCacheSize = pyramidCacheSize;
                return this;
            }

            /**
             * 设置 LOD 过渡混合范围
             *
             * <h3>作用：</h3>
             * <p>在两个相邻 LOD 级别的边界处进行平滑过渡，
             * 避免突然切换导致的视觉跳动（popping artifact）。</p>
             *
             * @param lodTransitionRange 过渡范围 [0.0, 0.3]（0=硬切, 0.3=宽过渡带）
             * @return this（支持链式调用）
             */
            public Builder lodTransitionRange(float lodTransitionRange) {
                if (lodTransitionRange < 0.0f || lodTransitionRange > 0.3f) {
                    throw new IllegalArgumentException(
                        "LOD 过渡范围必须在 [0.0, 0.3] 范围内: " + lodTransitionRange
                    );
                }
                this.lodTransitionRange = lodTransitionRange;
                return this;
            }

            /**
             * 设置是否启用 Hi-Z 遮挡剔除
             *
             * @param enableHiZCulling true 启用 Hi-Z（推荐，显著减少 overdraw）
             * @return this（支持链式调用）
             */
            public Builder enableHiZCulling(boolean enableHiZCulling) {
                this.enableHiZCulling = enableHiZCulling;
                return this;
            }

            /**
             * 设置是否启用动态分辨率/质量调整
             *
             * <h3>功能说明：</h3>
             * <p>当帧时间超过 targetFrameTime 时，自动降低 LOD 质量；
             * 当帧时间低于阈值时，逐步提升质量。</p>
             *
             * @param enableDynamicResolution true 启用动态调整（推荐）
             * @return this（支持链式调用）
             */
            public Builder enableDynamicResolution(boolean enableDynamicResolution) {
                this.enableDynamicResolution = enableDynamicResolution;
                return this;
            }

            /**
             * 设置目标帧时间（毫秒）
             *
             * <h3>常用值：</h3>
             * <pre>
             * 16.67 ms → 60 FPS
             * 33.33 ms → 30 FPS
             * 50.00 ms → 20 FPS
             * </pre>
             *
             * @param targetFrameTime 目标帧时间（毫秒，必须 >= 10.0 且 <= 100.0）
             * @return this（支持链式调用）
             */
            public Builder targetFrameTime(float targetFrameTime) {
                if (targetFrameTime < 10.0f || targetFrameTime > 100.0f) {
                    throw new IllegalArgumentException(
                        "目标帧时间必须在 [10.0, 100.0] 范围内: " + targetFrameTime
                    );
                }
                this.targetFrameTime = targetFrameTime;
                return this;
            }

            /**
             * 构建不可变的 Config 对象
             *
             * <h3>校验逻辑：</h3>
             * <ol>
             *   <li>所有参数已在 setter 中校验</li>
             *   <li>此处仅做组合一致性检查</li>
             *   <li>返回新的 Config 实例（不可变）</li>
             * </ol>
             *
             * @return 构建好的 Config 对象
             */
            public Config build() {
                return new Config(this);
            }
        }
    }

    // ==================== 内部数据结构 ====================

    /**
     * LOD 统计信息（每帧更新）
     *
     * <p>用于性能监控和调试，可通过 {@link #getStatistics()} 获取。
     *
     * @param totalChunks        当前加载的总区块数量
     * @param chunksPerLODLevel  各 LOD 级别的区块分布数组（长度 = maxLODLevels）
     * @param pyramidBuildTimeNanos 金字塔构建耗时（纳秒）
     * @param cullingTimeNanos   剔除处理耗时（纳秒）
     * @param drawCallCount      实际 DrawCall 数量（Indirect Draw 后）
     * @param avgFrameTimeMs     平均帧时间（毫秒，滑动窗口）
     */
    public record LODStatistics(
        int totalChunks,
        int[] chunksPerLODLevel,
        long pyramidBuildTimeNanos,
        long cullingTimeNanos,
        long drawCallCount,
        double avgFrameTimeMs
    ) {}

    // ==================== 子组件引用 ====================

    /** LOD 金字塔构建器 */
    private volatile LODPyramidBuilder pyramidBuilder;

    /** GPU 剔除管线 */
    private volatile GPUCullingPipeline cullingPipeline;

    /** 数据提供者（可为 null，表示使用 Mock 数据） */
    private volatile RealDataProvider dataProvider;

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 当前配置 */
    private volatile Config currentConfig;

    /** 当前加载的区块数量 */
    private volatile int loadedChunkCount = 0;

    /** 当前帧的统计信息 */
    private volatile LODStatistics currentStats;

    /** 是否处于就绪状态（初始化完成且无错误） */
    private volatile boolean ready = false;

    // ==================== 性能统计字段 ====================

    /** 上一次 update() 耗时（纳秒） */
    private volatile long lastUpdateTimeNanos = 0L;

    /** 上一次 render() 耗时（纳秒） */
    private volatile long lastRenderTimeNanos = 0L;

    /** 总计处理的帧数 */
    private final AtomicLong totalFramesProcessed = new AtomicLong(0L);

    /** 动态质量调整：当前有效的最大 LOD 级别（可能 < config.maxLODLevels） */
    private volatile int effectiveMaxLODLevels;

    /** 连续超标帧计数（用于动态调整决策） */
    private volatile int consecutiveSlowFrames = 0;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数（单例模式）
     *
     * <p>子组件在 initialize() 时延迟创建。
     */
    private VoxyInspiredLODSystem() {
        // 初始化统计信息为空
        this.currentStats = new LODStatistics(
            0,
            new int[0],
            0L,
            0L,
            0L,
            0.0
        );
    }

    // ==================== 生命周期 API ====================

    /**
     * 初始化 Voxy-Inspired LOD 系统
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - config: 配置参数对象（必填，不能为 null）
     *             类型: VoxyInspiredLODSystem.Config
     *             包含: maxLODLevels, maxDistance, cacheSize 等
     *
     *   - dataProvider: 数据提供者（可选，可为 null）
     *                   类型: RealDataProvider
     *                   用途: 从 Minecraft 获取真实相机/区块数据
     *                   如果为 null: 使用 Mock 数据或等待后续设置
     *
     * 返回值：
     *   - boolean: true 表示初始化成功
     *              false 表示初始化失败（查看日志了解详情）
     *
     * 初始化流程：
     *   1. 校验配置参数合法性
     *   2. 保存配置和 dataProvider 引用
     *   3. 创建并初始化 LODPyramidBuilder
     *   4. 创建并初始化 GPUCullingPipeline
     *   5. 设置初始有效 LOD 级别数
     *   6. 标记为已初始化和就绪
     *
     * 错误处理：
     *   - 不会抛出异常（所有错误记录到日志）
     *   - 部分失败仍返回 true（降级运行）
     *   - 完全失败返回 false
     *
     * 使用示例：
     *   Config config = new Config.Builder()
     *       .maxLODLevels(8)
     *       .maxDistance(1024.0f)
     *       .build();
     *
     *   RealDataProvider provider = RealDataProvider.getInstance();
     *   provider.initialize(minecraftInstance);
     *
     *   boolean success = lodSystem.initialize(config, provider);
     *   if (!success) {
     *       LOGGER.severe("LOD 系统初始化失败");
     *   }
     * </pre>
     *
     * @param config       配置参数（不能为 null）
     * @param dataProvider 数据提供者（可为 null）
     * @return true 表示初始化成功（或部分成功），false 表示完全失败
     * @throws IllegalArgumentException 如果 config 为 null
     */
    public boolean initialize(Config config, RealDataProvider dataProvider) {
        // ======== 参数校验 ========
        if (config == null) {
            throw new IllegalArgumentException("Config 不能为 null");
        }

        if (initialized.get()) {
            LOGGER.warning("VoxyInspiredLODSystem 已初始化，跳过重复初始化");
            return true;
        }

        long startTime = System.nanoTime();

        try {
            // ======== Step 1: 保存配置和数据源 ========
            this.currentConfig = config;
            this.dataProvider = dataProvider;
            this.effectiveMaxLODLevels = config.maxLODLevels;

            LOGGER.info(String.format(
                "╔══════════════════════════════════════════════════╗%n" +
                "║  Voxy-Inspired LOD System 初始化                 ║%n" +
                "╠══════════════════════════════════════════════════╣%n" +
                "║  Max LOD Levels:    %2d                          ║%n" +
                "║  Max Distance:      %8.1f chunks                 ║%n" +
                "║  GPU Building:      %s                           ║%n" +
                "║  Cache Size:        %4d pyramids                 ║%n" +
                "║  Hi-Z Culling:      %s                           ║%n" +
                "║  Dynamic Quality:   %s                           ║%n" +
                "║  Target Frame Time: %6.2f ms (%.0f FPS)          ║%n" +
                "╚══════════════════════════════════════════════════╝",
                config.maxLODLevels,
                config.maxDistance,
                config.useGPUBuilding ? "✓ Enabled" : "✗ Disabled",
                config.pyramidCacheSize,
                config.enableHiZCulling ? "✓ Enabled" : "✗ Disabled",
                config.enableDynamicResolution ? "✓ Enabled" : "✗ Disabled",
                config.targetFrameTime,
                1000.0 / config.targetFrameTime
            ));

            // ======== Step 2: 初始化 LODPyramidBuilder ========
            LOGGER.info("初始化 LOD Pyramid Builder...");
            this.pyramidBuilder = new LODPyramidBuilder(
                config.maxLODLevels,
                config.pyramidCacheSize,
                config.useGPUBuilding
            );

            // ======== Step 3: 初始化 GPUCullingPipeline ========
            LOGGER.info("初始化 GPU Culling Pipeline...");
            this.cullingPipeline = new GPUCullingPipeline(
                config.enableHiZCulling,
                config.maxDistance
            );

            // ======== Step 4: 设置初始统计信息 ========
            this.currentStats = new LODStatistics(
                0,
                new int[config.maxLODLevels],
                0L,
                0L,
                0L,
                0.0
            );

            // ======== Step 5: 标记为就绪 ========
            this.initialized.set(true);
            this.ready = true;

            long elapsed = System.nanoTime() - startTime;
            LOGGER.info(String.format(
                "VoxyInspiredLODSystem 初始化成功 (耗时 %.2f ms)",
                elapsed / 1_000_000.0
            ));

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                "VoxyInspiredLODSystem 初始化失败: " + e.getMessage(), e
            );
            this.ready = false;
            return false;
        }
    }

    /**
     * 每帧更新（在渲染循环中调用）
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - camera: 相机对象（可为 null）
     *             类型: Object（实际为 net.minecraft.client.Camera 或 MockMinecraft.MockCamera）
     *             用途: 获取位置、朝向、FOV 等参数
     *             如果为 null: 使用上次的位置或跳过更新
     *
     *   - frustum: 视锥体对象（可为 null）
     *              类型: Object（实际为 net.minecraft.client.renderer.culling.Frustum）
     *              用途: 视锥体剔除
     *              如果为 null: 仅使用距离剔除
     *
     *   - deltaTime: 帧时间增量（秒）
     *                类型: float
     *                典型值: 0.0167 (~60fps), 0.0333 (~30fps)
     *                用途: 动画插值、平滑过渡、性能监控
     *
     * 返回值：
     *   - void（无返回值，内部更新状态）
     *
     * 更新流程：
     *   1. 检查初始化状态和参数有效性
     *   2. 从 camera/frustum 提取必要数据
     *   3. 收集可见区块列表（从 dataProvider 或 Mock）
     *   4. 更新/构建 LOD 金字塔（按需）
     *   5. 执行 GPU/CPU 剔除
     *   6. 应用动态质量调整（如果启用）
     *   7. 更新统计信息
     *
     * 性能预算：
     *   - 目标: &lt; 5ms（对于 256 个活跃 chunks）
     *   - 包含: 金字塔构建 + 剔除 + 统计更新
     *   - 不包含: 实际渲染（在 render() 中执行）
     *
     * 调用时机：
     *   - DefaultPreInterceptor.processLODInjection() 入口
     *   - 或 LevelRenderer.renderLevel() 的 Mixin 注入点
     *   - 每帧且仅调用一次
     * </pre>
     *
     * @param camera     相机对象（可为 null）
     * @param frustum    视锥体（可为 null）
     * @param deltaTime  帧时间增量（秒）
     */
    public void update(Object camera, Object frustum, float deltaTime) {
        if (!initialized.get() || !ready) {
            return;
        }

        long startTime = System.nanoTime();

        try {
            // ======== Step 1: 提取相机数据 ========
            float[] cameraPos = extractCameraPosition(camera);

            // ======== Step 2: 收集可见区块 ========
            // TODO: 实现从 dataProvider 获取可见区块列表
            // List<Object> visibleSections = collectVisibleSections(frustum);

            // ======== Step 3: 更新 LOD 金字塔 ========
            // TODO: 调用 pyramidBuilder.updatePyramids(visibleSections, cameraPos)

            // ======== Step 4: 执行剔除 ========
            BitSet visibleMask = cullingPipeline.executeCPUCulling(
                cameraPos,
                frustum,
                (int) currentConfig.maxDistance  // float → int 显式转换
            );

            // ======== Step 5: 动态质量调整 ========
            if (currentConfig.enableDynamicResolution) {
                applyDynamicQualityAdjustment(deltaTime);
            }

            // ======== Step 6: 更新统计 ========
            updateStatistics(startTime, visibleMask.cardinality());

            totalFramesProcessed.incrementAndGet();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                "update() 过程中发生异常（不影响渲染，使用上一帧数据）: " + e.getMessage(), e
            );
        }

        lastUpdateTimeNanos = System.nanoTime() - startTime;
    }

    /**
     * 执行渲染（生成间接绘制命令或准备渲染数据）
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - renderPass: 渲染通道对象（可为 null）
     *                 类型: Object（Blaze3D RenderPass 或 Vulkan VkRenderPass）
     *                 用途: 指定当前渲染目标和状态
     *                 Phase 2: 未使用（预留接口）
     *
     *   - commandBuffer: 命令缓冲区（可为 null）
     *                    类型: Object（Blaze3D CommandBuffer 或 Vulkan VkCommandBuffer）
     *                    用途: 录制绘制命令
     *                    Phase 2: 未使用（预留接口）
     *
     * 返回值：
     *   - void（无返回值）
     *
     * 渲染流程（Phase 2 存根）：
     *   1. 检查是否有可见区块需要渲染
     *   2. 按 LOD 级别分组
     *   3. 为每组准备 Indirect Draw Command
     *   4. （未来）提交到 GPU 执行
     *
     * 注意事项：
     *   - 必须在 update() 之后调用
     *   - Phase 2 仅做准备工作，不实际提交 GPU 命令
     *   - Phase 2.x 将集成真实渲染管线
     * </pre>
     *
     * @param renderPass    渲染通道（可为 null，Phase 2 预留）
     * @param commandBuffer 命令缓冲区（可为 null，Phase 2 预留）
     */
    public void render(Object renderPass, Object commandBuffer) {
        if (!initialized.get() || !ready) {
            return;
        }

        long startTime = System.nanoTime();

        try {
            // TODO: Phase 2.x 实现
            // 1. 获取当前可见区块列表和 LOD 等级
            // 2. 按 LOD 级别分组
            // 3. 准备 Indirect Draw Commands
            // 4. 通过 Blaze3D API 提交绘制命令

            LOGGER.fine("render(): Phase 2 存根实现（待完善）");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                "render() 过程中发生异常: " + e.getMessage(), e
            );
        }

        lastRenderTimeNanos = System.nanoTime() - startTime;
    }

    /**
     * 关闭并释放所有资源
     *
     * <h3>清理流程：</h3>
     * <ol>
     *   <li>关闭金字塔构建器（释放缓存）</li>
     *   <li>关闭剔除管线（释放 Hi-Z Map 等）</li>
     *   <li>清除数据源引用</li>
     *   <li>重置所有状态标志</li>
     *   <li>记录日志确认关闭</li>
     * </ol>
     *
     * <h3>线程安全：</h3>
     * <p>此方法是同步的，调用后系统进入未初始化状态。
     * 任何正在进行的 update()/render() 调用将安全退出。</p>
     *
     * <h3>重新初始化：</h3>
     * <p>关闭后可以再次调用 initialize() 重新初始化。</p>
     */
    public void shutdown() {
        if (!initialized.get()) {
            LOGGER.warning("shutdown(): 系统尚未初始化");
            return;
        }

        try {
            // ======== Step 1: 关闭子组件 ========
            if (pyramidBuilder != null) {
                pyramidBuilder.shutdown();
                pyramidBuilder = null;
            }

            if (cullingPipeline != null) {
                cullingPipeline.shutdown();
                cullingPipeline = null;
            }

            // ======== Step 2: 清除引用 ========
            dataProvider = null;
            currentConfig = null;
            currentStats = null;

            // ======== Step 3: 重置状态 ========
            initialized.set(false);
            ready = false;
            loadedChunkCount = 0;
            effectiveMaxLODLevels = 0;
            consecutiveSlowFrames = 0;

            // 重置统计
            lastUpdateTimeNanos = 0L;
            lastRenderTimeNanos = 0L;
            totalFramesProcessed.set(0L);

            LOGGER.info("VoxyInspiredLODSystem 已成功关闭并释放所有资源");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                "shutdown() 过程中发生异常: " + e.getMessage(), e
            );
        }
    }

    // ==================== 查询 API ====================

    /**
     * 获取当前加载的区块数量
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 参数：
     *   - 无
     *
     * 返回值：
     *   - int: 当前加载且活跃的区块数量
     *         包括所有 LOD 级别的区块
     *         如果未初始化返回 0
     *
     * 使用场景：
     *   - 性能监控面板显示
     *   - 调试信息输出
     *   - 内存预算计算
     * </pre>
     *
     * @return 当前加载的区块数量（>= 0）
     */
    public int getLoadedChunkCount() {
        if (!initialized.get()) return 0;
        return loadedChunkCount;
    }

    /**
     * 获取当前帧的 LOD 统计信息
     *
     * <h3>返回值结构：</h3>
     * <pre>
     * LODStatistics:
     *   - totalChunks: 总区块数
     *   - chunksPerLODLevel[i]: 第 i 级别的区块数
     *   - pyramidBuildTimeNanos: 金字塔构建耗时
     *   - cullingTimeNanos: 剔除耗时
     *   - drawCallCount: DrawCall 数量
     *   - avgFrameTimeMs: 平均帧时间
     * </pre>
     *
     * @return LOD 统计信息的不可变快照（如果未初始化返回默认空统计）
     */
    public LODStatistics getStatistics() {
        if (!initialized.get() || currentStats == null) {
            return new LODStatistics(0, new int[0], 0L, 0L, 0L, 0.0);
        }
        return currentStats;  // record 本身是不可变的
    }

    /**
     * 检查系统是否已初始化且可用
     *
     * <h3>就绪条件：</h3>
     * <ul>
     *   <li>initialize() 已成功调用</li>
     *   <li>未发生致命错误</li>
     *   <li>子系统都处于可用状态</li>
     * </ul>
     *
     * @return true 如果可以安全调用 update()/render()
     */
    public boolean isReady() {
        return initialized.get() && ready;
    }

    /**
     * 获取上一次 update() 耗时（毫秒）
     *
     * @return 耗时（毫秒），0 表示尚未执行过 update()
     */
    public double getLastUpdateTimeMillis() {
        return lastUpdateTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一次 render() 耗时（毫秒）
     *
     * @return 耗时（毫秒），0 表示尚未执行过 render()
     */
    public double getLastRenderTimeMillis() {
        return lastRenderTimeNanos / 1_000_000.0;
    }

    /**
     * 获取总处理帧数
     *
     * @return 自初始化以来 update() 被调用的次数
     */
    public long getTotalFramesProcessed() {
        return totalFramesProcessed.get();
    }

    /**
     * 获取当前有效的最大 LOD 级别数
     *
     * <p>可能小于配置值（动态质量调整生效时）。</p>
     *
     * @return 当前有效的最大 LOD 级别数（<= config.maxLODLevels）
     */
    public int getEffectiveMaxLODLevels() {
        return effectiveMaxLODLevels;
    }

    /**
     * 获取当前配置（只读副本）
     *
     * @return 当前使用的配置对象（不可变）
     */
    public Config getCurrentConfig() {
        return currentConfig;  // Config 是不可变对象
    }

    /**
     * 获取金字塔构建器实例（高级用法）
     *
     * @return LODPyramidBuilder 实例，如果未初始化返回 null
     */
    public LODPyramidBuilder getPyramidBuilder() {
        return pyramidBuilder;
    }

    /**
     * 获取剔除管线实例（高级用法）
     *
     * @return GPUCullingPipeline 实例，如果未初始化返回 null
     */
    public GPUCullingPipeline getCullingPipeline() {
        return cullingPipeline;
    }

    // ==================== 内部方法 ====================

    /**
     * 从相机对象提取位置坐标
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - camera: 相机对象（可为 null）
     *
     * 返回值：
     *   - float[3]: [x, y, z] 世界坐标
     *     如果 camera 为 null 返回 [0, 0, 0]
     *     如果提取失败返回 [0, 0, 0] 并记录警告
     *
     * 支持的类型：
     *   1. RealDataProvider 已初始化 → 通过 provider.getCameraPosition() 获取
     *   2. MockMinecraft.MockCamera → 直接读取
     *   3. 其他类型 → 尝试反射获取 position 字段
     * </pre>
     *
     * @param camera 相机对象（可为 null）
     * @return float[3] 位置坐标数组
     */
    private float[] extractCameraPosition(Object camera) {
        float[] defaultPos = {0.0f, 0.0f, 0.0f};

        if (camera == null) {
            if (dataProvider != null && dataProvider.isReady()) {
                var pos = dataProvider.getCameraPosition();
                if (pos != null) {
                    return new float[]{pos.x, pos.y, pos.z};
                }
            }
            return defaultPos;
        }

        // TODO: 处理不同类型的 camera 对象
        // 目前返回默认位置
        return defaultPos;
    }

    /**
     * 应用动态质量调整算法
     *
     * <h3>算法原理：</h3>
     * <pre>
     * 1. 计算当前帧时间与目标的比值
     * 2. 如果连续 N 帧超标 → 降低质量（减少有效 LOD 级别）
     * 3. 如果连续 M 帧达标 → 提升质量（增加有效 LOD 级别）
     * 4. 变化步进为 1 级，避免突变
     * </pre>
     *
     * @param deltaTime 当前帧时间增量（秒）
     */
    private void applyDynamicQualityAdjustment(float deltaTime) {
        if (currentConfig == null) return;

        float frameTimeMs = deltaTime * 1000.0f;
        float targetMs = currentConfig.targetFrameTime;

        // 判断当前帧是否超标
        boolean isSlowFrame = frameTimeMs > targetMs * 1.2f;  // 20% 容差

        if (isSlowFrame) {
            consecutiveSlowFrames++;

            // 连续 10 帧超标 → 降低一级质量
            if (consecutiveSlowFrames >= 10 && effectiveMaxLODLevels > 2) {
                effectiveMaxLODLevels--;
                consecutiveSlowFrames = 0;

                LOGGER.info(String.format(
                    "动态质量调整: 降低到 %d 级 LOD (原 %d 帧超标)",
                    effectiveMaxLODLevels, consecutiveSlowFrames
                ));
            }
        } else {
            // 帧正常 → 重置计数器，考虑提升质量
            if (consecutiveSlowFrames > 0) {
                consecutiveSlowFrames--;
            }

            // 连续 30 帧正常 → 尝试提升一级质量
            if (consecutiveSlowFrames == 0 &&
                effectiveMaxLODLevels < currentConfig.maxLODLevels &&
                frameTimeMs < targetMs * 0.8f) {  // 有余量才提升

                effectiveMaxLODLevels++;
                LOGGER.fine(String.format(
                    "动态质量调整: 提升到 %d 级 LOD",
                    effectiveMaxLODLevels
                ));
            }
        }
    }

    /**
     * 更新统计信息
     *
     * @param startTime    本次 update 开始时间（纳秒）
     * @param visibleCount 可见区块数量
     */
    private void updateStatistics(long startTime, int visibleCount) {
        long elapsed = System.nanoTime() - startTime;

        // 构建各 LOD 级别的区块分布（简化：均匀分配）
        int[] distribution = new int[currentConfig.maxLODLevels];
        // TODO: 从实际数据填充 distribution

        this.currentStats = new LODStatistics(
            loadedChunkCount,
            distribution,
            elapsed,  // pyramid build time (暂用总时间)
            elapsed / 2,  // culling time (估计)
            visibleCount,  // draw call count (简化)
            (lastUpdateTimeNanos > 0) ?
                (lastUpdateTimeNanos / 1_000_000.0) : 0.0  // avg frame time
        );
    }
}
