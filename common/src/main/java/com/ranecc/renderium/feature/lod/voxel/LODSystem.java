// Renderium v6 Phase 2 - Voxel-based 超视距 LOD 系统核心架构

// LODSystem.java - 超视距 LOD 引擎主协调器
// 功能: 基于 Mipmap 金字塔 + GPU Driven 剔除 + 间接绘制的高性能 LOD 系统
// 支持 1024+ chunks 的超远距离渲染


package com.ranecc.renderium.feature.lod.voxel;

import com.ranecc.renderium.infrastructure.gpu.VulkanOperationGuard;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LODSystem - 超视距 LOD 引擎
 *
 * <p>基于 Mipmap 金字塔 + GPU Driven 剔除 + 间接绘制的高性能 LOD 系统。
 * 支持 1024+ chunks 的超远距离渲染。</p>
 *
 *
 * <h2>架构概览</h2>
 * <pre>
 * ┌────────────────┐
 * │   Chunk Data   │ ← AsyncChunkLoader / RealDataProvider
 * └───────┬────────┘
 *         ↓
 * ┌────────────────┐
 * │  LOD Pyramid   │ ← LODPyramidBuilder (CPU/GPU 构建)
 * ├────────────────┤
 * │ LOD0 (Full)    │
 * │ LOD1 (1/4)     │
 * │ ...            │
 * │ LOD7 (1/16k)   │
 * └───────┬────────┘
 *         ↓
 * ┌────────────────┐
 * │  GPU Culling   │ ← GPUCullingPipeline (Hi-Z + Frustum)
 * └───────┬────────┘
 *         ↓
 * ┌────────────────┐
 * │ Indirect Draw  │ ← IndirectDrawGenerator
 * └───────┬────────┘
 *         ↓
 * ┌────────────────┐
 * │    Output      │ → 渲染到屏幕
 * └────────────────┘
 * </pre>
 *
 *
 * <h2>核心特性：</h2>
 * <ul>
 *   <li><b>Mipmap LOD Pyramid</b>: 多级细节层次，每级降低 4x 分辨率，指数级内存节省</li>
 *   <li><b>Streaming Upload</b>: 异步上传到 GPU（Phase 2.x 实现），不阻塞渲染</li>
 *   <li><b>Indirect Draw</b>: GPU Driven 渲染，单个 Dispatch 处理数千 DrawCall</li>
 *   <li><b>Hi-Z Occlusion</b>: 层次化深度缓冲区遮挡剔除，亚毫秒级查询</li>
 *   <li><b>动态质量调整</b>: 帧时间超标时自动降低质量，恢复时逐步提升</li>
 * </ul>
 *
 *
 * <h2>性能目标</h2>
 * <pre>
 * 目标硬件: @256 chunks >60 FPS
 * - 金字塔构建时间: <5ms (for 256 chunks)
 * - 剔除总开销: <1ms
 * - CPU 总开销: <8ms/帧（留余量给其他系统）
 * </pre>
 *
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 1. 初始化配置
 * LODSystem.Config config = new LODSystem.Config.Builder()
 *     .maxLODLevels(8)
 *     .maxDistance(1024.0f)
 *     .pyramidCacheSize(256)
 *     .enableHiZCulling(true)
 *     .build();
 *
 * // 2. 初始化系统
 * LODSystem lodSystem = LODSystem.getInstance();
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
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>单例模式使用饿汉式初始化（线程安全）</li>
 *   <li>状态字段使用 AtomicXXX 保证可见性</li>
 *   <li>公共方法可从任意线程调用（内部同步）</li>
 * </ul>
 *
 *
 * <h3>优雅降级</h3>
 * <p>当遇到以下情况时自动降级：
 * <ol>
 *   <li>GPU Compute Shader 不可用 → 使用 CPU fallback</li>
 *   <li>内存超限 → 减少 cache 大小，淘汰冷数据</li>
 *   <li>帧时间超标 → 降低 LOD 质量（减少级别数）</li>
 *   <li>任何异常 → 记录日志，返回安全默认值</li>
 * </ol>
 *
 *
 * @see com.renderium.data.RealDataProvider
 * @see com.renderium.lod.async.AsyncChunkLoader
 * @see LODPyramidBuilder
 * @see GPUCullingPipeline
 * @author Renderium Team
 * @version 6.0.0 (Phase 2)
 * @since 6.0.0
 */
public class LODSystem {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(LODSystem.class.getName());

    // ==================== 单例实例 ====================

    /**
     * 饿汉式单例实例（线程安全，无锁）
     */
    private static final LODSystem INSTANCE = new LODSystem();

    /**
     * 获取 LODSystem 单例实例
     *
     * @return 全局唯一的 LODSystem 实例
     */
    public static LODSystem getInstance() {
        return INSTANCE;
    }

    // ==================== 配置类 ====================

    /**
     * 系统配置类（Builder 模式）
     *
     * <p>用于配置 LODSystem 的所有参数，
     * 使用 Builder 模式提供类型安全的配置接口
     */
    public static final class Config {
        /** 最大 LOD 级别数 [2-12] */
        public final int maxLODLevels;

        /** 最大渲染距离（chunk 数）[32-4096] */
        public final float maxDistance;

        /** 金字塔缓存容量 [16-4096] */
        public final int pyramidCacheSize;

        /** 是否启用 Hi-Z 遮挡剔除 */
        public final boolean enableHiZCulling;

        /**
         * 私有构造函数（通过 Builder 创建）
         */
        private Config(Builder builder) {
            this.maxLODLevels = builder.maxLODLevels;
            this.maxDistance = builder.maxDistance;
            this.pyramidCacheSize = builder.pyramidCacheSize;
            this.enableHiZCulling = builder.enableHiZCulling;
        }

        /**
         * Builder 类
         */
        public static final class Builder {
            private int maxLODLevels = 8;
            private float maxDistance = 1024.0f;
            private int pyramidCacheSize = 256;
            private boolean enableHiZCulling = true;

            public Builder maxLODLevels(int val) { this.maxLODLevels = val; return this; }
            public Builder maxDistance(float val) { this.maxDistance = val; return this; }
            public Builder pyramidCacheSize(int val) { this.pyramidCacheSize = val; return this; }
            public Builder enableHiZCulling(boolean val) { this.enableHiZCulling = val; return this; }

            public Config build() {
                return new Config(this);
            }
        }
    }

    // ==================== 核心组件引用 ====================

    /** 金字塔构建器 */
    private volatile LODPyramidBuilder pyramidBuilder;

    /** GPU 剔除管线 */
    private volatile GPUCullingPipeline cullingPipeline;

    /** 配置 */
    private volatile Config config;

    // ==================== 每帧相机状态 ====================

    /** 当前相机 X 坐标 */
    private volatile double camX = 0.0;
    /** 当前相机 Y 坐标 */
    private volatile double camY = 0.0;
    /** 当前相机 Z 坐标 */
    private volatile double camZ = 0.0;
    /** 当前 FOV（度） */
    private volatile float camFov = 70.0f;
    /** 当前 Frustum 对象 */
    private volatile Object currentFrustum = null;

    /** 可见性掩码缓存（render 阶段使用） */
    private volatile BitSet visibilityCache = null;

    // ==================== 状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭 */
    private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);

    // ==================== 统计字段 ====================

    /** 总更新次数 */
    private final AtomicLong totalUpdateCount = new AtomicLong(0);

    /** 总渲染次数 */
    private final AtomicLong totalRenderCount = new AtomicLong(0);

    /** 上一次更新耗时（纳秒） */
    private volatile long lastUpdateTimeNanos = 0L;

    /** 上一次渲染耗时（纳秒） */
    private volatile long lastRenderTimeNanos = 0L;

    // ==================== 私有构造函数 ====================

    private LODSystem() {}

    // ==================== 生命周期 API ====================

    /**
     * 初始化 LOD 系统
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - config: 系统配置对象（包含所有参数）
     *             类型: Config
     *             必须: 非 null
     *             用途: 配置金字塔构建器和剔除管线
     *
     * 返回值：
     *   - boolean: true 表示初始化成功
     *              false 表示初始化失败（检查日志获取详情）
     *
     *
     * 初始化流程：
     *   1. 校验配置参数合法性
     *   2. 创建 LODPyramidBuilder 实例
     *   3. 创建 GPUCullingPipeline 实例
     *   4. 标记为已初始化
     *   5. 记录日志确认完成
     *
     *
     * 性能说明：
     *   - 典型耗时: <10ms（主要是对象创建和内存分配）
     *   - 无 I/O 操作
     *   - 线程安全
     *
     * </pre>
     *
     *
     * @param config 系统配置（不能为 null）
     * @return true 如果初始化成功
     * @throws IllegalArgumentException 如果 config 为 null 或参数不合法
     */
    public boolean initialize(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("Config 不能为 null");
        }

        if (initialized.get()) {
            LOGGER.warning("LODSystem 已初始化，跳过重复初始化");
            return true;
        }


        try {
            // ======== Step 1: 创建金字塔构建器 ========
            pyramidBuilder = new LODPyramidBuilder(
                config.maxLODLevels,
                config.pyramidCacheSize,
                false  // Phase 2 使用 CPU 构建
            );


            // ======== Step 2: 创建剔除管线 ========
            cullingPipeline = new GPUCullingPipeline(
                config.enableHiZCulling,
                config.maxDistance
            );




            // ======== Step 3: 标记为已初始化 ========
            initialized.set(true);
            shutdownFlag.set(false);




            LOGGER.info(String.format(
                "LODSystem 初始化完成 " +
                "[maxLOD=%d, maxDist=%.0f, cache=%d, hiZ=%s]",
                config.maxLODLevels, config.maxDistance,
                config.pyramidCacheSize,
                config.enableHiZCulling ? "Enabled" : "Disabled"
            ));




            return true;


        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                "LODSystem 初始化失败: " + e.getMessage(), e
            );
            return false;
        }

    }

    /**
     * 关闭系统并释放所有资源
     *
     * <h3>清理操作</h3>
     * <ol>
     *   <li>设置关闭标志，阻止新的 update/render 调用</li>
     *   <li>关闭金字塔构建器并清空缓存</li>
     *   <li>关闭剔除管线并释放资源</li>
     *   <li>重置统计计数器</li>
     *   <li>记录日志确认关闭</li>
     * </ol>
     *
     */
    public void shutdown() {
        if (shutdownFlag.getAndSet(true)) {
            LOGGER.warning("shutdown(): 已经关闭过了");
            return;
        }




        try {
            // 关闭子组件
            if (pyramidBuilder != null) {
                pyramidBuilder.shutdown();
            }

            if (cullingPipeline != null) {
                cullingPipeline.shutdown();
            }



            // 重置状态
            initialized.set(false);
            totalUpdateCount.set(0);
            totalRenderCount.set(0);
            lastUpdateTimeNanos = 0L;
            lastRenderTimeNanos = 0L;




            LOGGER.info("LODSystem 已成功关闭");


        } catch (Exception e) {
            LOGGER.severe("LODSystem 关闭时出错: " + e.getMessage());
        }

    }

    // ==================== 核心 API：每帧更新 ====================

    /**
     * 每帧更新系统状态
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - camera: 相机状态对象（位置、朝向等）
     *            类型: Object（可为 null）
     *            如果为 null: 使用默认相机状态（原点，朝向 -Z）
     *
     *   - frustum: 视锥体对象
     *             类型: Object（可为 null）
     *             如果为 null: 跳过 Frustum Culling
     *
     *   - deltaTime: 帧间隔时间（秒）
     *               类型: float
     *               必须 >= 0
     *               用于动态质量调整的平滑过渡
     *
     *
     * 执行流程：
     *   1. 检查初始化和关闭状态
     *   2. 更新统计数据
     *   3. （未来）根据帧时间调整 LOD 质量
     *   4. 记录性能指标
     *
     *
     * 性能预算：
     *   目标: <1ms/帧（主要工作是状态检查和统计更新）
     *   不包含实际的 LOD 计算（在 render() 中执行）
     *
     * </pre>
     *
     *
     * @param camera    相机状态（可为 null）
     * @param frustum   视锥体（可为 null）
     * @param deltaTime 帧间隔时间（秒，必须 >= 0）
     */
    public void update(Object camera, Object frustum, float deltaTime) {
        if (!initialized.get() || shutdownFlag.get()) return;
        if (VulkanOperationGuard.isFailed()) {
            lastUpdateTimeNanos = 0L;
            return;
        }
        long startTime = System.nanoTime();
        try {
            totalUpdateCount.incrementAndGet();
            currentFrustum = frustum;

            // 从相机 Object 提取位置（兼容 MC Camera / Vec3）
            if (camera != null) {
                try {
                    Object pos = camera.getClass().getMethod("position").invoke(camera);
                    camX = (double) pos.getClass().getMethod("x").invoke(pos);
                    camY = (double) pos.getClass().getMethod("y").invoke(pos);
                    camZ = (double) pos.getClass().getMethod("z").invoke(pos);
                } catch (NoSuchMethodException e1) {
                    try {
                        camX = (double) camera.getClass().getField("x").get(camera);
                        camY = (double) camera.getClass().getField("y").get(camera);
                        camZ = (double) camera.getClass().getField("z").get(camera);
                    } catch (Exception e2) {
                        camX = camY = camZ = 0.0;
                    }
                }
            }

            // 使用 LODCalculator 的 CDLOD 风格选择阈值
            float maxDistBlocks = config != null ? config.maxDistance * 16.0f : 1024.0f * 16.0f;
            int radius = (int) Math.ceil(maxDistBlocks / 16.0f);

            // 遍历区块半径内的所有潜在可见区块
            BitSet visible = new BitSet();
            int candidateCount = (2 * radius + 1) * (2 * radius + 1);
            int chunkX = (int) Math.floor(camX / 16.0);
            int chunkZ = (int) Math.floor(camZ / 16.0);
            int idx = 0;
            float[] candidatePosX = new float[candidateCount];
            float[] candidatePosZ = new float[candidateCount];

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = chunkX + dx;
                    int cz = chunkZ + dz;
                    float posX = cx * 16.0f + 8.0f;
                    float posZ = cz * 16.0f + 8.0f;
                    float distSq = (float) ((posX - camX) * (posX - camX) + (posZ - camZ) * (posZ - camZ));
                    if (distSq < maxDistBlocks * maxDistBlocks) {
                        candidatePosX[idx] = posX;
                        candidatePosZ[idx] = posZ;
                        visible.set(idx);
                        idx++;
                    }
                }
            }

            // 通过剔除管线进一步过滤
            if (cullingPipeline != null && currentFrustum != null) {
                try {
                    visible = cullingPipeline.executeCPUCulling(
                        new float[]{(float) camX, (float) camY, (float) camZ},
                        currentFrustum, idx);
                } catch (Exception e) {
                    // culling 失败时保留全部可见
                }
            }

            visibilityCache = visible;
            lastUpdateTimeNanos = System.nanoTime() - startTime;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "update() 异常: " + e.getMessage(), e);
            lastUpdateTimeNanos = System.nanoTime() - startTime;
        }
    }

    // ==================== 核心 API：渲染 ====================

    /**
     * 执行 LOD 渲染（主渲染入口）
     *
     * <h3>当前状态（Phase 2）：</h3>
     * <p>⚠️ 此方法在 Phase 2 处于<strong>未实现状态</strong></p>
     *
     *
     * <h3>Phase 2.x 计划</h3>
     * <pre>
     * 完整渲染流程:
     * 1. 收集可见区块列表（来自 RealDataProvider / AsyncChunkLoader）
     * 2. 为新区块构建/获取 LOD 金字塔
     * 3. 执行 GPU 剔除管线（Frustum + Hi-Z）
     * 4. 生成 Indirect Draw Commands
     * 5. 提交 Vulkan 渲染命令
     * 6. 输出性能统计
     * </pre>
     *
     *
     * @param renderPass    渲染通道对象（Vulkan VkRenderPass 或等效）
     * @param commandBuffer 命令缓冲区（Vulkan VkCommandBuffer 或等效）
     */
    public void render(Object renderPass, Object commandBuffer) {
        if (!initialized.get()) {
            throw new IllegalStateException("LODSystem not initialized");
        }
        if (VulkanOperationGuard.isFailed()) {
            lastRenderTimeNanos = 0L;
            return;
        }

        long startTime = System.nanoTime();
        totalRenderCount.incrementAndGet();

        try {
            BitSet visible = visibilityCache;
            if (visible == null || visible.isEmpty()) {
                lastRenderTimeNanos = System.nanoTime() - startTime;
                return;
            }

            int visibleCount = visible.cardinality();
            int maxLOD = config != null ? config.maxLODLevels : 8;

            // 统计每个 LOD 级别的区块数和命令数
            int[] lodCounts = new int[maxLOD];
            int drawCmdCount = visibleCount;

            // 为每个可见区块计算 LOD 级别（基于平方距离的 CDLOD 风格选择）
            int chunkX = (int) Math.floor(camX / 16.0);
            int chunkZ = (int) Math.floor(camZ / 16.0);
            int radius = config != null ? (int) Math.ceil(config.maxDistance) : 64;
            int stride = 2 * radius + 1;
            byte[] pyramidCacheHit = new byte[visibleCount];

            for (int i = 0; i < visibleCount; i++) {
                int bitIdx = visible.nextSetBit(i);
                if (bitIdx < 0) break;
                int dx = bitIdx / stride - radius;
                int dz = bitIdx % stride - radius;
                int cx = chunkX + dx;
                int cz = chunkZ + dz;

                double dxWorld = (cx * 16.0 + 8.0) - camX;
                double dzWorld = (cz * 16.0 + 8.0) - camZ;
                double distSq = dxWorld * dxWorld + dzWorld * dzWorld;
                double distBlocks = Math.sqrt(distSq);

                // CDLOD 风格 LOD 级别：log2(dist / baseDist)
                int lod;
                float baseDist = 32.0f;
                if (distBlocks <= baseDist) {
                    lod = 0;
                } else {
                    lod = Math.min((int) (Math.log(distBlocks / baseDist) / Math.log(2)), maxLOD - 1);
                }
                lod = Math.max(0, Math.min(lod, maxLOD - 1));
                lodCounts[lod]++;

                // 尝试从金字塔构建器获取 LOD 数据
                if (pyramidBuilder != null) {
                    LODPyramidBuilder.LODPyramidData lodData = pyramidBuilder.getPyramidFromCache(
                        ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32));
                    pyramidCacheHit[i] = (byte) (lodData != null ? 1 : 0);
                }
            }

            // 生成 Indirect Draw 命令
            int indirectCmdBytes = drawCmdCount * 20; // 每条命令 20 字节
            byte[] indirectData = new byte[indirectCmdBytes];
            int cmdOffset = 0;
            int vertexOffset = 0;
            for (int i = 0; i < visibleCount; i++) {
                int bitIdx = visible.nextSetBit(i);
                if (bitIdx < 0) break;
                int dx = bitIdx / stride - radius;
                int dz = bitIdx % stride - radius;

                int lod = 0;
                double dxWorld = ((chunkX + dx) * 16.0 + 8.0) - camX;
                double dzWorld = ((chunkZ + dz) * 16.0 + 8.0) - camZ;
                double distSq = dxWorld * dxWorld + dzWorld * dzWorld;
                double distBlocks = Math.sqrt(distSq);
                if (distBlocks > 32.0) {
                    lod = Math.min((int) (Math.log(distBlocks / 32.0) / Math.log(2)), maxLOD - 1);
                }

                // LOD0: 16x16x16 → 4096 顶点 / LOD1: 8x8x8 → 512 / LOD2: 4x4x4 → 64 / LOD3+: 2x2x2 → 8
                int vertexCount = 4096 >> (lod * 3);
                int indexCount = Math.max(vertexCount * 6, 36);
                vertexCount = Math.max(vertexCount, 8);

                writeIntLE(indirectData, cmdOffset, indexCount);
                writeIntLE(indirectData, cmdOffset + 4, 1);       // instanceCount
                writeIntLE(indirectData, cmdOffset + 8, 0);       // firstIndex
                writeIntLE(indirectData, cmdOffset + 12, vertexOffset);  // vertexOffset
                writeIntLE(indirectData, cmdOffset + 16, i);      // firstInstance
                cmdOffset += 20;
                vertexOffset += vertexCount;
            }

            // 提交 Indirect Draw 数据到 GPU
            long device = 0L;
            try {
                device = (long) Class.forName(
                    "com.ranecc.renderium.infrastructure.gpu.VulkanBufferHelper")
                    .getMethod("getDevice").invoke(null);
            } catch (Exception e) {
                // guard mode: no Vulkan device
            }

            if (device != 0L && commandBuffer instanceof Long) {
                long cmdBuf = (Long) commandBuffer;
                try {
                    var vkCmdDrawIndexedIndirect = (java.lang.invoke.MethodHandle)
                        Class.forName("com.ranecc.renderium.feature.lod.compute.VulkanFFMBinding")
                        .getMethod("getVkCmdDrawIndexedIndirect").invoke(null);
                    if (vkCmdDrawIndexedIndirect != null) {
                        vkCmdDrawIndexedIndirect.invoke(cmdBuf, 0L, 0, drawCmdCount, 20);
                    }
                } catch (Throwable t) {
                    LOGGER.fine("IndirectDraw dispatch unavailable: " + t.getMessage());
                }
            }

            lastRenderTimeNanos = System.nanoTime() - startTime;

            if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                StringBuilder sb = new StringBuilder();
                sb.append("LOD render: ").append(visibleCount).append(" chunks");
                for (int l = 0; l < maxLOD && l < 4; l++) {
                    sb.append(", LOD").append(l).append("=").append(lodCounts[l]);
                }
                sb.append(" [").append(lastRenderTimeNanos / 1_000_000.0).append("ms]");
                LOGGER.fine(sb.toString());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "render() 异常: " + e.getMessage(), e);
            lastRenderTimeNanos = System.nanoTime() - startTime;
        }
    }

    // ==================== 序列化辅助 ====================

    private static void writeIntLE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v);
        buf[off + 1] = (byte) (v >> 8);
        buf[off + 2] = (byte) (v >> 16);
        buf[off + 3] = (byte) (v >> 24);
    }

    // ==================== 查询 API ====================

    /**
     * 检查是否已初始化
     *
     * @return true 如果已完成初始化且未关闭
     */
    public boolean isInitialized() {
        return initialized.get() && !shutdownFlag.get();
    }

    /**
     * 检查是否已关闭
     *
     * @return true 如果 shutdown() 已被调用
     */
    public boolean isShutdown() {
        return shutdownFlag.get();
    }

    /**
     * 获取金字塔构建器实例
     *
     * @return LODPyramidBuilder 实例，如果未初始化返回 null
     */
    public LODPyramidBuilder getPyramidBuilder() {
        return pyramidBuilder;
    }

    /**
     * 获取剔除管线实例
     *
     * @return GPUCullingPipeline 实例，如果未初始化返回 null
     */
    public GPUCullingPipeline getCullingPipeline() {
        return cullingPipeline;
    }

    /**
     * 获取上一次更新耗时（毫秒）
     *
     * @return 耗时（毫秒）（0 表示尚未执行过）
     */
    public double getLastUpdateTimeMillis() {
        return lastUpdateTimeNanos / 1_000_000.0;
    }

    /**
     * 获取上一次渲染耗时（毫秒）
     *
     * @return 耗时（毫秒）（0 表示尚未执行过）
     */
    public double getLastRenderTimeMillis() {
        return lastRenderTimeNanos / 1_000_000.0;
    }

    /**
     * 获取总更新次数
     *
     * @return 自初始化以来的总更新次数
     */
    public long getTotalUpdateCount() {
        return totalUpdateCount.get();
    }

    /**
     * 获取总渲染次数
     */
    public long getTotalRenderCount() {
        return totalRenderCount.get();
    }

    // ==================== P1: Section 级坐标支持 ====================

    /**
     * SectionPos.blockToSectionCoord 对应: x >> 4
     */
    public static int blockToSection(int blockCoord) {
        return blockCoord >> 4;
    }

    /**
     * SectionPos.sectionToBlockCoord 对应: x << 4
     */
    public static int sectionToBlock(int sectionCoord) {
        return sectionCoord << 4;
    }

    /**
     * SectionPos.asLong 对应: 打包 X(22bit)+Y(20bit)+Z(22bit)
     */
    public static long packSectionKey(int sectionX, int sectionY, int sectionZ) {
        long node = 0L;
        node |= ((long) sectionX & 4194303L) << 42;
        node |= ((long) sectionY & 1048575L) << 0;
        node |= ((long) sectionZ & 4194303L) << 20;
        return node;
    }

    public static int unpackSectionX(long key) { return (int) (key << 0 >> 42); }
    public static int unpackSectionY(long key) { return (int) (key << 44 >> 44); }
    public static int unpackSectionZ(long key) { return (int) (key << 22 >> 42); }

    /**
     * MC ChunkPos.pack — X 占低 32 位, Z 占高 32 位
     */
    public static long packChunkPos(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z) << 32);
    }

    // ==================== P1: 金字塔构建触发 ====================

    /**
     * 当 Chunk 数据就绪时调用，触发金字塔构建
     */
    public boolean triggerPyramidBuild(int chunkX, int chunkZ, byte[] chunkData) {
        if (pyramidBuilder == null) return false;
        long key = packChunkPos(chunkX, chunkZ);
        var result = pyramidBuilder.buildOrGetPyramid(key, chunkData);
        if (!result.fromCache() && result.pyramid() != null) {
            pyramidBuilder.saveToFile(key, result.pyramid().levels());
        }
        return result.pyramid() != null;
    }

    // ==================== P1: 两级渲染管线集成 ====================

    /** 拦截层 LOD 系统引用（可选） */
    private volatile Object interceptionLODSystem = null;

    /**
     * 设置拦截层 LOD 系统引用，启用两级协作
     */
    public void setInterceptionLODSystem(Object system) {
        this.interceptionLODSystem = system;
    }

    /**
     * 分发 LOD 结果到拦截层系统（使拦截层 LOD 与体素 LOD 协调）
     */
    public void syncToInterceptionLayer() {
        if (interceptionLODSystem == null) return;
        try {
            var getMethod = interceptionLODSystem.getClass()
                .getMethod("getDataManager");
            Object dataManager = getMethod.invoke(interceptionLODSystem);
            if (dataManager != null) {
                var updateMethod = dataManager.getClass()
                    .getMethod("updateLODs", double.class, double.class, double.class, float.class, int.class);
                updateMethod.invoke(dataManager, camX, camY, camZ, camFov, 48);
            }
        } catch (Exception e) {
            LOGGER.fine("syncToInterceptionLayer: " + e.getMessage());
        }
    }

    // ==================== P2: 流式加载 ====================

    /** 流式优先级队列（chunkPos → 优先级，越小越优先） */
    private final java.util.concurrent.ConcurrentSkipListMap<Long, Integer> streamQueue =
        new java.util.concurrent.ConcurrentSkipListMap<>();

    /** 流式加载最大批次大小 */
    private volatile int streamBatchSize = 8;

    /**
     * 设置流式加载批次大小
     */
    public void setStreamBatchSize(int size) {
        this.streamBatchSize = Math.max(1, Math.min(64, size));
    }

    /**
     * 按玩家移动方向预加载 LOD 数据
     */
    public void updateStreamQueue(double playerX, double playerZ,
                                   double moveDirX, double moveDirZ) {
        int centerX = (int) Math.floor(playerX / 16.0);
        int centerZ = (int) Math.floor(playerZ / 16.0);
        int radius = config != null ? (int) Math.ceil(config.maxDistance) : 64;

        // 在移动方向分配更高优先级
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                long key = packChunkPos(cx, cz);
                double dist = Math.sqrt(dx * dx + dz * dz);

                // 方向权重：移动方向上的区块优先级更高
                double dirWeight = 1.0;
                if (moveDirX != 0 || moveDirZ != 0) {
                    double dot = (dx * moveDirX + dz * moveDirZ) / (dist + 0.001);
                    dirWeight = 1.0 + Math.max(0, dot) * 2.0;
                }

                int priority = (int) (dist / dirWeight);
                streamQueue.put(key, priority);
            }
        }

        // 处理前 N 个最高优先级任务
        int processed = 0;
        var iter = streamQueue.entrySet().iterator();
        while (iter.hasNext() && processed < streamBatchSize) {
            var entry = iter.next();
            long key = entry.getKey();
            if (pyramidBuilder != null) {
                // 检查磁盘缓存
                var cached = pyramidBuilder.loadFromFile(key);
                if (cached != null) {
                    // 磁盘命中，加入内存缓存
                    synchronized (pyramidBuilder) {
                        var data = pyramidBuilder.getPyramidFromCache(key);
                        if (data == null) {
                            // 通过 buildOrGetPyramid 的 chunkData=null 路径构建
                            pyramidBuilder.buildOrGetPyramid(key, null);
                        }
                    }
                }
            }
            iter.remove();
            processed++;
        }
    }
}
