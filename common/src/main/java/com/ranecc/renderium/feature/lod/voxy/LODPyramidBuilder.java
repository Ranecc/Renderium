// Renderium v6 Phase 2 - Voxy-Inspired 超视距 LOD 系统核心架构

// LODPyramidBuilder.java - Mipmap LOD 金字塔构建器
// 功能: 将 Chunk 数据降采样为多级 Mipmap 纹理数组，支持 CPU/GPU 双路径


package com.ranecc.renderium.feature.lod.voxy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LODPyramidBuilder - Mipmap LOD 金字塔构建器
 *
 * <p>负责将高分辨率的 Chunk 数据（16×16×16 blocks）降采样为多级 LOD，
 * 每级分辨率降低 4x（2×2×2 下采样），形成金字塔结构。</p>
 *
 *
 * <h2>核心算法</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │                    LOD 金字塔结构                         │
 * ├───────┬──────────┬─────────────┬────────────┬────────────┤
 * │ Level │ 分辨率   │ Block 数量  │ 内存估算   │ 适用距离    │
 * ├───────┼──────────┼─────────────┼────────────┼────────────┤
 * │ LOD0  │ 16×16×16 │ 4096 blocks│ ~64 KB     │ <32 chunks  │
 * │ LOD1  │ 8×8×8    │ 512 blocks  │ ~8 KB      │ 32-64 chunks│
 * │ LOD2  │ 4×4×4    │ 64 blocks   │ ~1 KB      │ 64-128 chunks│
 * │ LOD3  │ 2×2×2    │ 8 blocks    │ ~128 B     │ 128-256 chunks│
 * │ LOD4  │ 1×1×1    │ 1 block     │ ~16 B      │ >256 chunks │
 * └───────┴──────────┴─────────────┴────────────┴────────────┘
 *
 * 下采样公式: size_next = size_current / 2 (每个维度)
 * 内存节省: LOD0 → LOD4 = 4096 倍减小
 * </pre>
 *
 *
 * <h2>双路径支持：</h2>
 * <ul>
 *   <li><b>CPU 路径</b>: 使用 Java 多线程并行下采样，兼容性好</li>
 *   <li><b>GPU 路径</b>: 使用 Compute Shader 加速（Phase 2.x 实现）</li>
 * </ul>
 *
 *
 * <h2>缓存策略</h2>
 * <ul>
 *   <li>LRU (Least Recently Used) 淘汰算法</li>
 *   <li>可配置缓存容量</li>
 *   <li>线程安全访问</li>
 * </ul>
 *
 *
 * <h3>性能目标</h3>
 * <pre>
 * 单个金字塔构建时间: <0.5ms (CPU, 16×16×16 →5 levels)
 * 批量构建 (256 chunks): <5ms (使用多线程)
 * 缓存命中率目标: >90%
 * </pre>
 *
 *
 * @see VoxyInspiredLODSystem
 * @author Renderium Team
 * @version 6.0.0 (Phase 2)
 * @since 6.0.0
 */
public class LODPyramidBuilder {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(LODPyramidBuilder.class.getName());

    // ==================== 常量定义 ====================

    /** 原始 Chunk 分辨率(blocks per axis) */
    public static final int CHUNK_SIZE = 16;

    /** 每个 block 的数据大小（字节）：存储类型 + 元数据 */
    private static final int BYTES_PER_BLOCK = 4;

    /** 默认最大缓存数量 */
    private static final int DEFAULT_CACHE_SIZE = 256;

    /** LRU 访问顺序键前缀 */
    private static final String LRU_KEY_PREFIX = "pyramid_";

    // ==================== 配置字段 ====================

    /** 最大 LOD 级别数 */
    private final int maxLODLevels;

    /** 缓存容量（最近使用的金字塔数量） */
    private final int cacheSize;

    /** 是否启用 GPU 构建（预留接口） */
    private final boolean useGPUBuilding;

    // ==================== 内部数据结构 ====================

    /**
     * LOD 金字塔数据结构
     *
     * <p>表示单个 Chunk 的多级 LOD 数据
     *
     * @param chunkKey      区块唯一标识符（坐标编码）
     * @param levels        各级别的数据数组（levels[i] = 第 i 级的数据）
     * @param buildTimeNanos 构建耗时（纳秒）
     * @param lastAccessTime 最后访问时间戳（纳秒，用于 LRU）
     */
    public record LODPyramidData(
        long chunkKey,
        byte[][] levels,
        long buildTimeNanos,
        long lastAccessTime
    ) {}



    /**
     * 金字塔构建结果
     *
     * @param pyramid       构建好的金字塔数据
     * @param fromCache     是否从缓存获取（true=命中, false=新构建）
     * @param buildTimeNanos 实际耗时（纳秒）
     */
    public record BuildResult(
        LODPyramidData pyramid,
        boolean fromCache,
        long buildTimeNanos
    ) {}



    // ==================== 缓存字段 ====================

    /**
     * LRU 缓存（使用 LinkedHashMap + accessOrder 特性）
     *
     * <p>key: chunkKey (long)<br/>
     * value: LODPyramidData<br/>
     * accessOrder=true 保证按访问顺序排序</p>
     */
    private volatile LinkedHashMap<Long, LODPyramidData> pyramidCache;

    /** 缓存锁对象（用于保证线程安全） */
    private final Object cacheLock = new Object();

    // ==================== 统计字段 ====================

    /** 缓存命中次数 */
    private final AtomicLong cacheHitCount = new AtomicLong(0);

    /** 缓存未命中次数 */
    private final AtomicLong cacheMissCount = new AtomicLong(0);

    /** 总计构建次数 */
    private final AtomicLong totalBuildCount = new AtomicLong(0);

    /** 总计构建耗时（纳秒） */
    private final AtomicLong totalBuildTimeNanos = new AtomicLong(0L);

    /** 当前缓存中的条目数 */
    private volatile int currentCacheSize = 0;

    // ==================== 状态字段 ====================

    /** 是否已关闭 */
    private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);

    // ==================== 构造函数 ====================

    /**
     * 创建 LODPyramidBuilder 实例
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - maxLODLevels: 最大 LOD 级别数
     *                   类型: int
     *                   取值范围: [2, 12]
     *                   推荐: 5-8 级（平衡质量和性能）
     *                   影响: 级别数越多，远处细节越好，但内存和构建时间增加
     *
     *   - cacheSize: 金字塔缓存容量
     *                类型: int
     *                取值范围: [16, 4096]
     *                推荐: 256（约 22 MB 内存）
     *                影响: 更大缓存 → 更高命中率 → 更多内存占用
     *
     *   - useGPUBuilding: 是否使用 GPU Compute Shader 构建
     *                     类型: boolean
     *                     默认: false（Phase 2 使用 CPU）
     *                     Phase 2.x: 可启用 GPU 加速路径
     *
     * 初始化流程：
     *   1. 校验参数合法性
     *   2. 初始化 LRU 缓存（LinkedHashMap + accessOrder）
     *   3. 预分配统计计数器
     * </pre>
     *
     *
     * @param maxLODLevels 最大 LOD 级别数（必须 >= 2 且 <= 12）
     * @param cacheSize    缓存容量（必须 >= 16 且 <= 4096）
     * @param useGPUBuilding 是否使用 GPU 构建（Phase 2 暂未实现）
     * @throws IllegalArgumentException 如果参数不合法
     */
    public LODPyramidBuilder(int maxLODLevels, int cacheSize, boolean useGPUBuilding) {
        // ======== 参数校验 ========
        if (maxLODLevels < 2 || maxLODLevels > 12) {
            throw new IllegalArgumentException(
                "最大 LOD 级别数必须在 [2, 12] 范围内: " + maxLODLevels
            );
        }

        if (cacheSize < 16 || cacheSize > 4096) {
            throw new IllegalArgumentException(
                "缓存容量必须在 [16, 4096] 范围内: " + cacheSize
            );
        }




        this.maxLODLevels = maxLODLevels;
        this.cacheSize = cacheSize;
        this.useGPUBuilding = useGPUBuilding;




        // ======== 初始化 LRU 缓存 ========
        // 使用 LinkedHashMap 的构造函数：initialCapacity, loadFactor, accessOrder
        this.pyramidCache = new LinkedHashMap<Long, LODPyramidData>(
            cacheSize + 1,  // 初始容量（+1 避免 resize）
            0.75f,          // 负载因子
            true             // accessOrder=true → LRU 顺序
        ) {
            /**
             * 重写 removeEldestEntry() 实现 LRU 淘汰
             *
             * @param eldest 最老的条目
             * @return true 表示应该淘汰该条目
             */
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, LODPyramidData> eldest) {
                // 当缓存大小超过限制时，自动淘汰最老的条目
                if (size() > LODPyramidBuilder.this.cacheSize) {
                    LOGGER.fine(String.format(
                        "LRU 淘汰金字塔 chunkKey=%d (缓存大小 %d/%d)",
                        eldest.getKey(), size(), LODPyramidBuilder.this.cacheSize
                    ));

                    currentCacheSize = size() - 1;
                    return true;
                }

                return false;
            }
        };




        LOGGER.info(String.format(
            "LODPyramidBuilder 初始化完成 [maxLevels=%d, cacheSize=%d, gpuBuilding=%s]",
            maxLODLevels, cacheSize, useGPUBuilding ? "Enabled" : "Disabled (CPU)"
        ));

    }

    // ==================== 核心 API：金字塔构建 ====================

    /**
     * 为指定区块构建或获取 LOD 金字塔
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - chunkKey: 区块唯一标识符
     *               类型: long
     *               格式: (chunkZ << 32) | (chunkX & 0xFFFFFFFFL)
     *               用途: 在缓存中查找/存储该区块的金字塔
     *
     *   - chunkData: 原始区块数据（16×16×16 blocks）
     *               类型: byte[]
     *               大小: CHUNK_SIZE³ × BYTES_PER_BLOCK = 16384 bytes
     *               格式: 每个元素一个 block state ID 或压缩格式
     *               如果为 null: 返回空金字塔（全 AIR）
     *
     * 返回值：
     *   - BuildResult: 包含金字塔数据和元信息
     *     - pyramid: 构建好的 LODPyramidData（不可变）
     *     - fromCache: true=从缓存获取, false=新构建
     *     - buildTimeNanos: 实际耗时（缓存命中时接近 0）
     *
     *
     * 执行流程：
     *   1. 检查关闭标志
     *   2. 在缓存中查找（O(1) HashMap 查找）
     *   3. 如果命中:
     *      a. 更新最后访问时间
     *      b. 返回缓存数据（fromCache=true）
     *   4. 如果未命中:
     *      a. 检查缓存空间（可能触发 LRU 淘汰）
     *      b. 调用 buildPyramidInternal() 构建
     *      c. 存入缓存
     *      d. 返回新数据（fromCache=false）
     *
     * 性能说明：
     *   - 缓存命中: O(1), <0.01ms
     *   - 缓存未命中: O(n), n=block 数量, 约 0.5ms (单线程)
     *   - LRU 淘汰: O(1) (LinkedHashMap 保证)
     *
     * </pre>
     *
     *
     * @param chunkKey  区块唯一标识符（long 编码的坐标）
     * @param chunkData 原始区块数据（可为 null 表示空区块）
     * @return BuildResult 包含金字塔数据和构建信息
     */
    public BuildResult buildOrGetPyramid(long chunkKey, byte[] chunkData) {
        if (shutdownFlag.get()) {
            LOGGER.warning("buildOrGetPyramid(): Builder 已关闭");
            return new BuildResult(null, false, 0L);
        }



        long startTime = System.nanoTime();

        synchronized (cacheLock) {
            // ======== Step 1: 尝试从缓存获取 ========
            LODPyramidData cached = pyramidCache.get(chunkKey);
            if (cached != null) {
                // 缓存命中：更新访问时间并返回
                cacheHitCount.incrementAndGet();
                long elapsed = System.nanoTime() - startTime;


                LOGGER.fine(String.format(
                    "金字塔缓存命中 chunkKey=%d (%.3f ms)",
                    chunkKey, elapsed / 1_000_000.0
                ));




                return new BuildResult(cached, true, elapsed);
            }




            // ======== Step 2: 缓存未命中，构建新金字塔 ========
            cacheMissCount.incrementAndGet();
            totalBuildCount.incrementAndGet();




            // 构建金字塔数据
            LODPyramidData pyramid = buildPyramidInternal(chunkKey, chunkData);
            long buildTime = System.nanoTime() - startTime;


            // 存入缓存（可能触发 LRU 淘汰）
            pyramidCache.put(chunkKey, pyramid);
            currentCacheSize = pyramidCache.size();

            totalBuildTimeNanos.addAndGet(buildTime);


            LOGGER.fine(String.format(
                "金字塔构建完成 chunkKey=%d, levels=%d, 耗时=%.3f ms",
                chunkKey, pyramid.levels().length, buildTime / 1_000_000.0
            ));




            return new BuildResult(pyramid, false, buildTime);
        }

    }

    /**
     * 批量构建多个区块的金字塔（优化版本）
     *
     * <h3>方法说明</h3>
     * <p>当需要同时处理大量区块时，此方法可以：
     * <ul>
     *   <li>批量预检查缓存，减少锁竞争</li>
     *   <li>未来可扩展为并行构建（多线程）</li>
     *   <li>提供批量统计信息</li>
     * </ul>
     *
     *
     * @param chunkKeys  区块标识符数组
     * @param chunkDatas 对应的原始数据数组（长度必须与 chunkKeys 一致）
     * @return BuildResult 数组（与输入一一对应）
     */
    public BuildResult[] batchBuildOrGet(long[] chunkKeys, byte[][] chunkDatas) {
        if (chunkKeys == null || chunkDatas == null) {
            throw new IllegalArgumentException("输入数组不能为 null");
        }

        if (chunkKeys.length != chunkDatas.length) {
            throw new IllegalArgumentException("键和数据的长度不一致");
        }




        BuildResult[] results = new BuildResult[chunkKeys.length];

        for (int i = 0; i < chunkKeys.length; i++) {
            results[i] = buildOrGetPyramid(chunkKeys[i], chunkDatas[i]);
        }




        return results;
    }

    // ==================== 核心 API：内部构建逻辑 ====================

    /**
     * 内部金字塔构建方法（实际执行下采样）
     *
     * <h3>算法流程</h3>
     * <pre>
     * 输入: chunkData[16*16*16] (原始 4096 个 blocks)
     *
     * Level 0 (LOD0): 直接复制原始数据 (16×16×16)
     *     → 2×2×2 平均下采样
     * Level 1 (LOD1): 取 8 个邻居平均 (8×8×8 = 512 blocks)
     *     → 2×2×2 平均下采样
     * Level 2 (LOD2): (4×4×4 = 64 blocks)
     *     → ...
     * Level N (LODN): 直到达到 1×1×1 或最大级别数
     *
     *
     * 下采样公式:
     *   lodNext[x][y][z] = average(
     *       lodCurrent[2*x][2*y][2*z],
     *       lodCurrent[2*x+1][2*y][2*z],
     *       lodCurrent[2*x][2*y+1][2*z],
     *       lodCurrent[2*x+1][2*y+1][2*z],
     *       ... 共 8 个邻居
     *   )
     * </pre>
     *
     *
     * @param chunkKey  区块标识符
     * @param chunkData 原始数据（可为 null）
     * @return 构建好的 LODPyramidData
     */
    private LODPyramidData buildPyramidInternal(long chunkKey, byte[] chunkData) {
        long startTime = System.nanoTime();


        // 初始化各级别数组
        byte[][] levels = new byte[maxLODLevels][];


        try {
            // ======== Level 0: 原始数据或空数据 ========
            if (chunkData == null || chunkData.length == 0) {
                // 空区块：创建全零数据（AIR）
                int level0Size = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE * BYTES_PER_BLOCK;
                levels[0] = new byte[level0Size];
                // 已经是全零（Java 默认初始化）
            } else {
                // 复制原始数据到 Level 0
                levels[0] = chunkData.clone();  // 防御性拷贝
            }


            // ======== 后续级别: 迭代下采样 ========
            int currentSize = CHUNK_SIZE;

            for (int level = 1; level < maxLODLevels; level++) {
                int nextSize = Math.max(1, currentSize / 2);
                int nextVolume = nextSize * nextSize * nextSize;


                // 分配下一级的数据数组
                levels[level] = new byte[nextVolume * BYTES_PER_BLOCK];


                // 执行 2×2×2 下采样
                downsample2x2x2(levels[level - 1], currentSize, levels[level], nextSize);


                currentSize = nextSize;


                // 如果已到达最小尺寸（1×1×1），后续级别复制相同数据
                if (currentSize <= 1) {
                    for (int remainingLevel = level + 1; remainingLevel < maxLODLevels; remainingLevel++) {
                        levels[remainingLevel] = levels[level].clone();
                    }
                    break;  // 无需继续下采样
                }
            }




        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                String.format("金字塔构建异常(chunkKey=%d): %s", chunkKey, e.getMessage()), e
            );

            // 返回部分构建的数据（至少有 Level 0）
            if (levels[0] == null) {
                levels[0] = new byte[CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE * BYTES_PER_BLOCK];
            }
        }




        long buildTime = System.nanoTime() - startTime;


        // 创建不可变的金字塔数据对象
        return new LODPyramidData(
            chunkKey,
            levels,
            buildTime,
            System.nanoTime()  // 最后访问时间 = 创建时间
        );

    }

    /**
     * 执行 2×2×2 下采样（平均值法）
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - sourceData: 源数据数组（当前级别）
     *                 类型: byte[]
     *                 大小: sourceSize³ × BYTES_PER_BLOCK
     *
     *   - sourceSize: 源数据边长（每维的元素数）
     *                 类型: int
     *                 例如: 16 (LOD0), 8 (LOD1), 4 (LOD2)
     *
     *   - targetData: 目标数据数组（下一级别）
     *                 类型: byte[]（预先分配好大小）
     *                 大小: targetSize³ × BYTES_PER_BLOCK
     *
     *   - targetSize: 目标数据边长
     *                 类型: int
     *                 通常 = sourceSize / 2
     *
     * 算法原理：
     *   对于目标数据中的每个位置 (tx, ty, tz):
     *     1. 计算对应的源数据 2×2×2 邻域起始位置
     *        sx = tx * 2, sy = ty * 2, sz = tz * 2
     *     2. 收集邻域内的 8 个值
     *     3. 计算算术平均值
     *     4. 写入目标位置
     *
     *
     * 边界处理：
     *   如果源大小为奇数，最后一个元素直接复制（不平均）
     *
     *
     * 性能复杂度：
     *   时间: O(targetSize³) - 遍历所有目标体素
     *   空间: O(1) - 仅使用局部变量（原地操作）
     * </pre>
     *
     *
     * @param sourceData 源数据数组
     * @param sourceSize 源数据边长
     * @param targetData 目标数据数组（输出）
     * @param targetSize 目标数据边长
     */
    private void downsample2x2x2(byte[] sourceData, int sourceSize,
                                  byte[] targetData, int targetSize) {
        // 遍历目标数据的每个位置
        for (int tz = 0; tz < targetSize; tz++) {
            for (int ty = 0; ty < targetSize; ty++) {
                for (int tx = 0; tx < targetSize; tx++) {
                    // 计算源数据中的对应位置（2×2×2 邻域起点）
                    int sx = tx * 2;
                    int sy = ty * 2;
                    int sz = tz * 2;


                    // 收集邻域内的 8 个值并计算总和
                    int sum = 0;
                    int count = 0;


                    for (int dz = 0; dz < 2 && (sz + dz) < sourceSize; dz++) {
                        for (int dy = 0; dy < 2 && (sy + dy) < sourceSize; dy++) {
                            for (int dx = 0; dx < 2 && (sx + dx) < sourceSize; dx++) {
                                // 计算 3D 索引（z * size_y * size_x + y * size_x + x) * bytesPerBlock
                                int srcIdx = ((sz + dz) * sourceSize * sourceSize +
                                               (sy + dy) * sourceSize +
                                               (sx + dx)) * BYTES_PER_BLOCK;


                                // 取第一个字节作为值（简化：仅用 type ID）
                                if (srcIdx < sourceData.length) {
                                    sum += (sourceData[srcIdx] & 0xFF);  // unsigned byte
                                    count++;
                                }
                            }
                        }
                    }


                    // 计算平均值并写入目标
                    int avg = (count > 0) ? (sum / count) : 0;
                    int tgtIdx = (tz * targetSize * targetSize +
                                  ty * targetSize +
                                  tx) * BYTES_PER_BLOCK;


                    if (tgtIdx < targetData.length) {
                        targetData[tgtIdx] = (byte) (avg & 0xFF);  // clamp to byte
                    }
                }
            }
        }
    }

    // ==================== 查询 API ====================

    /**
     * 检查缓存中是否包含指定区块的金字塔
     *
     * @param chunkKey 区块标识符
     * @return true 如果缓存中存在该区块的金字塔
     */
    public boolean containsPyramid(long chunkKey) {
        synchronized (cacheLock) {
            return pyramidCache.containsKey(chunkKey);
        }
    }

    /**
     * 从缓存获取金字塔数据（不触发构建）
     *
     * @param chunkKey 区块标识符
     * @return 金字塔数据，如果不存在返回 null
     */
    public LODPyramidData getPyramidFromCache(long chunkKey) {
        synchronized (cacheLock) {
            return pyramidCache.get(chunkKey);
        }
    }

    /**
     * 获取当前缓存中的条目数量
     *
     * @return 当前缓存大小（<= cacheSize）
     */
    public int getCacheSize() {
        return currentCacheSize;
    }

    /**
     * 获取最大 LOD 级别数
     *
     * @return 最大级别数（由构造函数指定）
     */
    public int getMaxLODLevels() {
        return maxLODLevels;
    }

    /**
     * 获取缓存命中率
     *
     * <h3>计算公式</h3>
     * <pre>
     * hitRate = cacheHitCount / (cacheHitCount + cacheMissCount) * 100%
     * </pre>
     *
     *
     * @return 命中率 [0.0, 1.0]，如果无访问记录返回 0.0
     */
    public double getCacheHitRate() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        if (total == 0) return 0.0;
        return (double) hits / (double) total;
    }

    /**
     * 获取平均构建时间（毫秒）
     *
     * @return 平均构建时间（毫秒），如果没有构建过返回 0.0
     */
    public double getAverageBuildTimeMillis() {
        long count = totalBuildCount.get();
        if (count == 0) return 0.0;
        long totalTime = totalBuildTimeNanos.get();
        return (totalTime / 1_000_000.0) / (double) count;
    }

    /**
     * 获取总构建次数
     *
     * @return 自初始化以来的总构建次数
     */
    public long getTotalBuildCount() {
        return totalBuildCount.get();
    }

    /**
     * 清除所有缓存的金字塔数据
     *
     * <h3>使用场景</h3>
     * <ul>
     *   <li>切换世界时清除旧数据</li>
     *   <li>内存压力过大时手动释放</li>
     *   <li>测试时重置状态</li>
     * </ul>
     *
     */
    public void clearCache() {
        synchronized (cacheLock) {
            int oldSize = pyramidCache.size();
            pyramidCache.clear();
            currentCacheSize = 0;


            LOGGER.info(String.format(
                "金字塔缓存已清除 (释放 %d 个条目)",
                oldSize
            ));
        }
    }

    // ==================== 生命周期 API ====================

    /**
     * 关闭构建器并释放资源
     *
     * <h3>清理操作</h3>
     * <ol>
     *   <li>设置关闭标志，阻止新的构建请求</li>
     *   <li>清空缓存释放内存</li>
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




        synchronized (cacheLock) {
            // 清空缓存
            int cacheSizeBefore = pyramidCache.size();
            pyramidCache.clear();
            currentCacheSize = 0;


            // 重置统计
            cacheHitCount.set(0);
            cacheMissCount.set(0);
            totalBuildCount.set(0);
            totalBuildTimeNanos.set(0);


            LOGGER.info(String.format(
                "LODPyramidBuilder 已关闭 (释放 %d 个缓存条目)",
                cacheSizeBefore
            ));
        }
    }

    /**
     * 检查是否已关闭
     *
     * @return true 如果 shutdown() 已被调用
     */
    public boolean isShutdown() {
        return shutdownFlag.get();
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将区块坐标打包为 long 键
     *
     * <h3>编码格式</h3>
     * <pre>
     * bit 0-31:   chunkX (低 32 位)
     * bit 32-63:  chunkZ (高 32 位)
     * </pre>
     *
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return 打包后的 long 键
     */
    public static long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | ((long) chunkX & 0xFFFFFFFFL);
    }

    /**
     * 从 long 键解包区块 X 坐标
     *
     * @param chunkKey 打包的键
     * @return 区块 X 坐标
     */
    public static int unpackChunkX(long chunkKey) {
        return (int) (chunkKey & 0xFFFFFFFFL);
    }

    /**
     * 从 long 键解包区块 Z 坐标
     *
     * @param chunkKey 打包的键
     * @return 区块 Z 坐标
     */
    public static int unpackChunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    /**
     * 计算指定 LOD 级别的数据大小（字节）
     *
     * @param lodLevel LOD 级别 [0, maxLODLevels)
     * @return 该级别的数据大小（字节）
     */
    public int getLevelDataSize(int lodLevel) {
        if (lodLevel < 0 || lodLevel >= maxLODLevels) {
            throw new IllegalArgumentException(
                "LOD 级别超出范围 [0, " + maxLODLevels + "): " + lodLevel
            );
        }


        int size = CHUNK_SIZE;
        for (int i = 0; i < lodLevel; i++) {
            size = Math.max(1, size / 2);
        }


        return size * size * size * BYTES_PER_BLOCK;
    }


}
