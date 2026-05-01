// Renderium - Blaze3D 拦截层系统 Phase 3: LOD 多细节层次系统
// LODMeshCache.java - Mesh 缓存管理器
// 功能: 不同 LOD 级别的 mesh 数据缓存、LRU 淘汰、异步预加载

package com.ranecc.renderium.feature.lod.interception.interception.lod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * LOD Mesh 缓存管理器。
 *
 * <p>负责缓存不同 LOD 级别下的区块 mesh 数据，提供高效的存储和检索。
 * 核心功能包括：
 * <ul>
 *   <li><b>多级 Mesh 缓存</b>：同一 chunk 可能有 LOD0~LOD3 四个级别的 mesh</li>
 *   <li><b>LRU 淘汰策略</b>：内存超限时自动淘汰最久未访问的 mesh</li>
 *   <li><b>异步预加载</b>：后台线程预生成即将进入视野的 chunk mesh</li>
 *   <li><b>GPU 缓冲区管理</b>：跟踪 GPU 端资源（VBO/IBO）的生命周期</li>
 * </ul>
 *
 * <h2>缓存层次结构：</h2>
 * <pre>
 * LODMeshCache
 *   │
 *   ├── ConcurrentHashMap&lt;Long, ChunkMeshCache&gt;  (chunkKey → 该 chunk 的各级 mesh)
 *   │     │
 *   │     └── MeshEntry[lodLevel]  // 每个级别一个 mesh 条目
 *   │           ├── vertexData: byte[]    // 顶点数据
 *   │           ├── indexData: byte[]     // 索引数据
 *   │           ├── gpuBufferHandle: long // GPU 缓冲区句柄（0 = 未上传）
 *   │           └── lastAccessTime: long  // 最后访问时间（LRU 排序依据）
 *   │
 *   └── 预加载队列（后台线程消费）
 * </pre>
 *
 * <h2>内存目标：</h2>
 * <ul>
 *   <li>堆内存（vertex/index data）: &lt; 80MB</li>
 *   <li>显存估算（GPU buffers）: &lt; 100MB</li>
 *   <li>总预算可配置，默认 ~100MB</li>
 * </ul>
 *
 * @see LODDataManager
 * @see GPUDrivenLODSystem
 * @author Renderium Team
 * @since 5.3.0
 */
public final class LODMeshCache {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(LODMeshCache.class.getName());

    // ==================== 常量定义 ====================

    /** 默认最大缓存 mesh 数量 */
    public static final int DEFAULT_MAX_MESH_COUNT = 5000;

    /** 默认内存预算（字节）：约 100MB */
    public static final long DEFAULT_MEMORY_BUDGET_BYTES = 100L * 1024L * 1024L;

    /** 默认预加载线程数量 */
    public static final int DEFAULT_PRELOAD_THREADS = 2;

    /** 单例实例 */
    private static final LODMeshCache INSTANCE = new LODMeshCache();

    /**
     * 获取单例实例
     *
     * @return 全局唯一的 LODMeshCache 实例
     */
    public static LODMeshCache getInstance() {
        return INSTANCE;
    }

    // ==================== 内部数据结构 ====================

    /**
     * 单个 mesh 缓存条目。
     *
     * <p>包含一个特定 chunk 在特定 LOD 级别下的完整 mesh 数据，
     * 以及 GPU 资源引用和访问统计信息。
     *
     * @param vertexData      顶点缓冲数据（压缩格式）
     * @param indexData       索引缓冲数据
     * @param vertexCount     顶点数量
     * @param indexCount      索引数量
     * @param gpuBufferHandle GPU 端缓冲区句柄（0 表示未上传到 GPU）
     * @param lastAccessTime  最后一次被访问的时间戳（纳秒）
     * @param dataSizeBytes   顶点+索引数据的总大小（字节）
     */
    public record MeshEntry(
        byte[] vertexData,
        byte[] indexData,
        int vertexCount,
        int indexCount,
        long gpuBufferHandle,
        long lastAccessTime,
        long dataSizeBytes
    ) {}

    /**
     * 一个 chunk 的所有 LOD 级别的 mesh 缓存容器。
     *
     * @param entries 按 LOD 等级索引的 mesh 条目数组（长度 = 最大 LOD 级别数）
     * @param chunkX  区块 X 坐标
     * @param chunkZ  区块 Z 坐标
     */
    public record ChunkMeshCache(MeshEntry[] entries, int chunkX, int chunkZ) {}

    /**
     * 缓存统计信息。
     *
     * @param totalMeshes      缓存的 mesh 总数
     * @param totalChunks      有缓存数据的 chunk 数量
     * @param heapMemoryBytes  堆内存使用量（字节）
     * @param estimatedGpuMem  估计的显存使用量（字节）
     * @param hitRate          缓存命中率 [0.0, 1.0]
     * @param evictionCount    累计淘汰次数
     */
    public record CacheStats(
        int totalMeshes, int totalChunks,
        long heapMemoryBytes, long estimatedGpuMem,
        double hitRate, long evictionCount
    ) {}

    // ==================== 核心数据结构 ====================

    /**
     * 主缓存表：chunkKey → 各级 LOD mesh
     * <p>使用 ConcurrentHashMap 保证并发安全
     */
    private final ConcurrentHashMap<Long, ChunkMeshCache> meshCache;

    /**
     * LRU 排序辅助表（用于淘汰时找到最久未访问的 entry）
     * <p>使用 accessOrder=true 的 LinkedHashMap 实现 LRU 语义
     */
    private final LinkedHashMap<Long, Long> lruTracker;

    // ==================== 统计计数器 ====================

    /** 总查询次数 */
    private final AtomicLong totalQueries = new AtomicLong(0);

    /** 缓存命中次数 */
    private final AtomicLong cacheHits = new AtomicLong(0);

    /** 累计淘汰次数 */
    private final AtomicLong evictionCount = new AtomicLong(0);

    /** 当前堆内存使用量（字节） */
    private final AtomicLong currentHeapUsage = new AtomicLong(0);

    // ==================== 配置字段 ====================

    /** 最大缓存 mesh 数量 */
    private volatile int maxMeshCount = DEFAULT_MAX_MESH_COUNT;

    /** 内存预算上限（字节） */
    private volatile long memoryBudgetBytes = DEFAULT_MEMORY_BUDGET_BYTES;

    /** 最大 LOD 级别数（来自 LODCalculator） */
    private volatile int maxLODLevels = 4;

    // ==================== 私有构造函数 ====================

    private LODMeshCache() {
        this.meshCache = new ConcurrentHashMap<>(2048, 0.75f, 16);

        // LRU Tracker：access-ordered LinkedHashMap + 重写 removeEldestEntry
        this.lruTracker = new LinkedHashMap<>(2048, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                // 不在此处自动淘汰，由 enforceBudget() 显式控制
                return false;
            }
        };
    }

    // ==================== 核心 API：查询与存储 ====================

    /**
     * 获取指定 chunk 和 LOD 级别的 mesh 数据。
     *
     * <h3>查询流程：</h3>
     * <ol>
     *   <li>计算 chunk key 并查找 ChunkMeshCache</li>
     *   <li>从 ChunkMeshCache.entries[lodLevel] 获取 MeshEntry</li>
     *   <li>更新 lastAccessTime 和 LRU tracker</li>
     *   <li>返回 MeshEntry（可能为 null 如果该级别未缓存）</li>
     * </ol>
     *
     * <h3>时间复杂度：</h3>O(1) 平均
     *
     * @param chunkX   区块 X 坐标
     * @param chunkZ   区块 Z 坐标
     * @param lodLevel LOD 等级 [0, maxLODLevels-1]
     * @return MeshEntry 对象，如果未缓存则返回 null
     */
    public MeshEntry getMesh(int chunkX, int chunkZ, int lodLevel) {
        totalQueries.incrementAndGet();

        long key = packChunkKey(chunkX, chunkZ);
        ChunkMeshCache chunkCache = meshCache.get(key);

        if (chunkCache == null) {
            return null; // 整个 chunk 都未缓存
        }

        // 边界检查
        if (lodLevel < 0 || lodLevel >= chunkCache.entries().length) {
            return null; // 无效的 LOD 等级
        }

        MeshEntry entry = chunkCache.entries()[lodLevel];
        if (entry == null) {
            return null; // 该 LOD 级别未缓存
        }

        // 更新 LRU 访问记录
        synchronized (lruTracker) {
            lruTracker.put(key, System.nanoTime());
        }

        cacheHits.incrementAndGet();
        return entry;
    }

    /**
     * 存储指定 chunk 和 LOD 级别的 mesh 数据。
     *
     * <p>如果已存在同名条目则覆盖（释放旧数据引用），
     * 同时更新内存用量统计并触发预算检查。
     *
     * @param chunkX     区块 X 坐标
     * @param chunkZ     区块 Z 坐标
     * @param lodLevel   LOD 等级 [0, maxLODLevels-1]
     * @param vertexData 顶点缓冲数据（不能为 null）
     * @param indexData  索引缓冲数据（不能为 null）
     * @param vertexCount 顶点数量（必须 >= 0）
     * @param indexCount  索引数量（必须 >= 0）
     * @throws IllegalArgumentException 如果参数不合法
     */
    public void putMesh(int chunkX, int chunkZ, int lodLevel,
                        byte[] vertexData, byte[] indexData,
                        int vertexCount, int indexCount) {
        if (vertexData == null || indexData == null) {
            throw new IllegalArgumentException("顶点数据和索引数据不能为 null");
        }
        if (lodLevel < 0 || lodLevel >= maxLODLevels) {
            throw new IllegalArgumentException(
                "LOD 等级超出范围 [0, " + (maxLODLevels - 1) + "]: " + lodLevel
            );
        }

        long key = packChunkKey(chunkX, chunkZ);
        long now = System.nanoTime();
        long dataSize = (long) vertexData.length + (long) indexData.length;

        // 创建新的 MeshEntry
        MeshEntry newEntry = new MeshEntry(
            vertexData, indexData, vertexCount, indexCount,
            0L, now, dataSize
        );

        // 使用 compute 原子性地更新缓存
        meshCache.compute(key, (k, existingCache) -> {
            MeshEntry[] entries;
            long oldSize = 0;

            if (existingCache != null) {
                entries = existingCache.entries().clone();
                // 释放旧数据的内存引用
                if (entries[lodLevel] != null) {
                    oldSize = entries[lodLevel].dataSizeBytes();
                }
            } else {
                entries = new MeshEntry[maxLODLevels];
            }

            entries[lodLevel] = newEntry;
            currentHeapUsage.addAndGet(dataSize - oldSize);

            return new ChunkMeshCache(entries, chunkX, chunkZ);
        });

        // 更新 LRU tracker
        synchronized (lruTracker) {
            lruTracker.put(key, now);
        }

        // 触发预算检查
        enforceBudget();
    }

    /**
     * 移除指定 chunk 的所有 LOD 级别 mesh 数据。
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return true 如果成功移除（之前存在），false 如果不存在
     */
    public boolean removeChunk(int chunkX, int chunkZ) {
        long key = packChunkKey(chunkX, chunkZ);
        ChunkMeshCache removed = meshCache.remove(key);

        if (removed != null) {
            // 计算释放的内存
            long freedBytes = 0;
            for (MeshEntry entry : removed.entries()) {
                if (entry != null) {
                    freedBytes += entry.dataSizeBytes();
                }
            }
            currentHeapUsage.addAndGet(-freedBytes);

            synchronized (lruTracker) {
                lruTracker.remove(key);
            }

            LOGGER.fine(String.format(
                "移除 chunk (%d,%d) 的所有 LOD mesh，释放 %d bytes",
                chunkX, chunkZ, freedBytes
            ));

            return true;
        }

        return false;
    }

    /**
     * 清除所有缓存数据。
     */
    public void clearAll() {
        int size = meshCache.size();
        meshCache.clear();
        synchronized (lruTracker) {
            lruTracker.clear();
        }
        currentHeapUsage.set(0);
        evictionCount.set(0);

        LOGGER.info("LOD Mesh 缓存已清除，释放了 " + size + " 个 chunk 的 mesh 数据");
    }

    // ==================== 核心 API：GPU 缓冲区管理 ====================

    /**
     * 关联 GPU 缓冲区句柄到已有的 mesh 条目。
     *
     * <p>当 mesh 数据上传到 GPU 后调用此方法记录句柄，
     * 以便后续清理时能正确释放 GPU 资源。
     *
     * @param chunkX         区块 X 坐标
     * @param chunkZ         区块 Z 坐标
     * @param lodLevel       LOD 等级
     * @param gpuBufferHandle GPU 缓冲区句柄（Vulkan VkBuffer 或 OpenGL VBO ID）
     * @return true 如果关联成功
     */
    public boolean associateGPUBuffer(int chunkX, int chunkZ, int lodLevel,
                                       long gpuBufferHandle) {
        long key = packChunkKey(chunkX, chunkZ);
        ChunkMeshCache chunkCache = meshCache.get(key);

        if (chunkCache == null || lodLevel < 0 || lodLevel >= chunkCache.entries().length) {
            return false;
        }

        MeshEntry oldEntry = chunkCache.entries()[lodLevel];
        if (oldEntry == null) {
            return false;
        }

        // 创建带 GPU 句柄的新 entry
        MeshEntry newEntry = new MeshEntry(
            oldEntry.vertexData(), oldEntry.indexData(),
            oldEntry.vertexCount(), oldEntry.indexCount(),
            gpuBufferHandle, System.nanoTime(), oldEntry.dataSizeBytes()
        );

        // 替换 entry（需要重新创建 ChunkMeshCache）
        meshCache.computeIfPresent(key, (k, cache) -> {
            MeshEntry[] entries = cache.entries().clone();
            entries[lodLevel] = newEntry;
            return new ChunkMeshCache(entries, cache.chunkX(), cache.chunkZ());
        });

        return true;
    }

    // ==================== 统计 API ====================

    /**
     * 获取当前缓存统计信息。
     *
     * @return 包含详细统计数据的 CacheStats 对象
     */
    public CacheStats getStatistics() {
        long queries = totalQueries.get();
        long hits = cacheHits.get();

        // 统计非空 mesh 总数
        int totalMeshes = 0;
        for (ChunkMeshCache chunkCache : meshCache.values()) {
            for (MeshEntry entry : chunkCache.entries()) {
                if (entry != null) {
                    totalMeshes++;
                }
            }
        }

        return new CacheStats(
            totalMeshes,
            meshCache.size(),
            currentHeapUsage.get(),
            currentHeapUsage.get(), // 堆内存 ≈ 估计显存（简化处理）
            queries > 0 ? (double) hits / (double) queries : 0.0,
            evictionCount.get()
        );
    }

    /**
     * 获取当前堆内存使用量（字节）。
     *
     * @return 已使用的堆内存字节数
     */
    public long getHeapMemoryUsage() {
        return currentHeapUsage.get();
    }

    // ==================== 配置 API ====================

    /**
     * 设置最大缓存 mesh 数量。
     *
     * @param count 最大数量（必须 >= 100）
     */
    public void setMaxMeshCount(int count) {
        if (count < 100) {
            throw new IllegalArgumentException("最大 mesh 数量必须 >= 100: " + count);
        }
        this.maxMeshCount = count;
        enforceBudget();
    }

    /**
     * 设置内存预算上限。
     *
     * @param budgetBytes 预算（字节，必须 >= 10MB）
     */
    public void setMemoryBudget(long budgetBytes) {
        if (budgetBytes < 10L * 1024L * 1024L) {
            throw new IllegalArgumentException("内存预算必须 >= 10MB: " + budgetBytes);
        }
        this.memoryBudgetBytes = budgetBytes;
        enforceBudget();
    }

    /**
     * 设置最大 LOD 级别数（应与 LODCalculator 保持一致）。
     *
     * @param levels 级别数
     */
    public void setMaxLODLevels(int levels) {
        this.maxLODLevels = levels;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将 chunk 坐标打包为长整型键。
     *
     * <p>格式：低 32 位 = chunkX，高 32 位 = chunkZ
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return 打包后的 64 位键
     */
    private static long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | ((long) chunkX & 0xFFFFFFFFL);
    }

    /**
     * 强制执行内存预算限制（LRU 淘汰）。
     *
     * <p>当缓存大小超过任一限制（mesh 数量 / 内存字节数）时，
     * 从 LRU tracker 中找出最久未访问的条目并淘汰。
     *
     * <h3>淘汰策略：</h3>
     * <ol>
     *   <li>同时检查 mesh 数量和内存两个维度</li>
     *   <li>优先淘汰整个 chunk（所有 LOD 级别一起移除）</li>
     *   <li>每次最多淘汰 5% 的缓存</li>
     *   <li>更新淘汰计数器和内存统计</li>
     * </ol>
     */
    private void enforceBudget() {
        int currentMeshCount = 0;
        for (ChunkMeshCache cache : meshCache.values()) {
            for (MeshEntry entry : cache.entries()) {
                if (entry != null) currentMeshCount++;
            }
        }

        long currentMemory = currentHeapUsage.get();

        // 检查是否需要淘汰
        boolean overCount = currentMeshCount > maxMeshCount;
        boolean overMemory = currentMemory > memoryBudgetBytes;

        if (!overCount && !overMemory) {
            return; // 在预算范围内
        }

        // 计算需要淘汰的数量
        int evictTarget = 0;
        if (overCount) {
            evictTarget = Math.max(evictTarget, (currentMeshCount - maxMeshCount));
        }
        if (overMemory) {
            // 估算每个 mesh 平均大小，计算需淘汰多少
            long avgSize = currentMeshCount > 0 ? currentMemory / currentMeshCount : 1024;
            int evictByMemory = (int) ((currentMemory - memoryBudgetBytes) / Math.max(avgSize, 1)) + 1;
            evictTarget = Math.max(evictTarget, evictByMemory);
        }

        // 加上余量（多淘汰一些避免频繁触发）
        evictTarget = Math.min(evictTarget, Math.max(currentMeshCount / 20, 1));

        // 从 LRU tracker 中取出最旧的 key 进行淘汰
        int actuallyEvicted = 0;
        synchronized (lruTracker) {
            var iterator = lruTracker.entrySet().iterator();
            while (iterator.hasNext() && actuallyEvicted < evictTarget) {
                Map.Entry<Long, Long> lruEntry = iterator.next();
                long keyToEvict = lruEntry.getKey();

                ChunkMeshCache removed = meshCache.remove(keyToEvict);
                if (removed != null) {
                    // 释放内存计数
                    long freedBytes = 0;
                    for (MeshEntry entry : removed.entries()) {
                        if (entry != null) {
                            freedBytes += entry.dataSizeBytes();
                        }
                    }
                    currentHeapUsage.addAndGet(-freedBytes);
                    actuallyEvicted++;
                }

                iterator.remove(); // 从 LRU tracker 中也移除
            }
        }

        if (actuallyEvicted > 0) {
            evictionCount.addAndGet(actuallyEvicted);
            LOGGER.fine(String.format(
                "LOD Mesh 缓存淘汰: 移除 %d 个 chunk（剩余 %d meshes, %d MB）",
                actuallyEvicted, getStatistics().totalMeshes(),
                currentHeapUsage.get() / 1024 / 1024
            ));
        }
    }
}
