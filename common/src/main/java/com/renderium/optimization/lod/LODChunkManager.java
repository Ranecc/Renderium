// Renderium - LOD 区块管理器
// 异步加载、更新和回收远景区块数据
// 使用环形缓冲区管理 LOD 区块的生命周期

package com.renderium.optimization.lod;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * LOD 区块管理器。
 *
 * <p>管理远景区块的异步加载、更新和回收。
 * 维护一个以玩家为中心的环形区域，自动加载新进入范围的区块，
 * 回收超出范围的区块。
 *
 * <h2>架构</h2>
 * <pre>
 * 主线程（渲染）
 *   ↓ 请求更新
 * LODChunkManager
 *   ↓ 异步加载
 * ForkJoinPool
 *   ├── Worker: 从区块数据提取高度图+颜色图
 *   └── Worker: 生成 Billboard 纹理
 *   ↓ 结果回调
 * 主线程上传到 GPU
 * </pre>
 *
 * <h2>内存管理</h2>
 * <p>使用 LRU 策略管理 LOD 区块缓存：
 * <ul>
 *   <li>最大缓存数量由配置决定（默认 4096 个区块）</li>
 *   <li>超出范围 2 倍的区块立即回收</li>
 *   <li>GPU 资源延迟释放（避免频繁创建/销毁）</li>
 * </ul>
 *
 * @see LODChunk
 * @see RenderiumLODManager
 * @author Renderium Team
 * @since 1.0.0
 */
public final class LODChunkManager {

    private static final Logger LOGGER = Logger.getLogger(LODChunkManager.class.getName());

    /** 默认最大缓存区块数 */
    private static final int DEFAULT_MAX_CACHE_SIZE = 4096;

    /** 默认加载半径（区块数） */
    private static final int DEFAULT_LOAD_RADIUS = 64;

    /** 回收距离倍数（超出加载半径的多少倍后回收） */
    private static final float UNLOAD_MULTIPLIER = 2.0f;

    // ==================== 加载任务 ====================

    /**
     * LOD 加载任务
     */
    private static final class LoadTask {
        final int chunkX;
        final int chunkZ;
        final LODChunk.DataType dataType;

        LoadTask(int chunkX, int chunkZ, LODChunk.DataType dataType) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.dataType = dataType;
        }
    }

    // ==================== 实例字段 ====================

    /** LOD 区块缓存：key = chunkX * 1000000 + chunkZ */
    private final ConcurrentHashMap<Long, LODChunk> chunkCache;

    /** 异步加载线程池 */
    private final ForkJoinPool loadExecutor;

    /** 待加载队列 */
    private final ConcurrentLinkedQueue<LoadTask> loadQueue;

    /** 已完成加载的结果 */
    private final ConcurrentLinkedQueue<LODChunk> completedQueue;

    /** 最大缓存大小 */
    private final int maxCacheSize;

    /** 加载半径 */
    private volatile int loadRadius;

    /** 当前玩家区块坐标 */
    private volatile int playerChunkX;
    private volatile int playerChunkZ;

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    // ==================== 统计 ====================

    private final AtomicInteger totalLoaded = new AtomicInteger(0);
    private final AtomicInteger totalUnloaded = new AtomicInteger(0);
    private final AtomicInteger totalFailed = new AtomicInteger(0);

    // ==================== 构造与生命周期 ====================

    public LODChunkManager() {
        this(DEFAULT_MAX_CACHE_SIZE, DEFAULT_LOAD_RADIUS);
    }

    public LODChunkManager(int maxCacheSize, int loadRadius) {
        this.maxCacheSize = maxCacheSize;
        this.loadRadius = loadRadius;
        this.chunkCache = new ConcurrentHashMap<>(maxCacheSize / 4);
        this.loadExecutor = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        this.loadQueue = new ConcurrentLinkedQueue<>();
        this.completedQueue = new ConcurrentLinkedQueue<>();

        LOGGER.info("LODChunkManager created: maxCache=" + maxCacheSize + ", radius=" + loadRadius);
    }

    /**
     * 更新玩家位置，触发新区块加载和旧区块回收
     *
     * @param playerX 玩家世界 X 坐标
     * @param playerZ 玩家世界 Z 坐标
     */
    public void updatePlayerPosition(double playerX, double playerZ) {
        int newChunkX = (int) Math.floor(playerX / 16.0);
        int newChunkZ = (int) Math.floor(playerZ / 16.0);

        if (newChunkX == playerChunkX && newChunkZ == playerChunkZ) return;

        playerChunkX = newChunkX;
        playerChunkZ = newChunkZ;

        // 检查需要加载的新区块
        scheduleNewChunks();

        // 回收超出范围的区块
        unloadDistantChunks();
    }

    /**
     * 每帧更新：取回已完成的加载结果
     *
     * @return 已完成的 LOD 区块列表
     */
    public List<LODChunk> pollCompleted() {
        List<LODChunk> results = new ArrayList<>();
        LODChunk chunk;
        while ((chunk = completedQueue.poll()) != null) {
            results.add(chunk);
        }

        // 检查正在加载的任务
        loadQueue.removeIf(task -> {
            // 简化：实际应该检查 CompletableFuture 状态
            return false;
        });

        return results;
    }

    /**
     * 获取指定区块的 LOD 数据
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return LODChunk 或 null（如果未加载）
     */
    public LODChunk getChunk(int chunkX, int chunkZ) {
        return chunkCache.get(encodeKey(chunkX, chunkZ));
    }

    /**
     * 获取所有已就绪的 LOD 区块
     */
    public Collection<LODChunk> getReadyChunks() {
        List<LODChunk> ready = new ArrayList<>();
        for (LODChunk chunk : chunkCache.values()) {
            if (chunk.isReady()) {
                ready.add(chunk);
            }
        }
        return ready;
    }

    /**
     * 关闭管理器
     */
    public void shutdown() {
        shutdown = true;
        loadExecutor.shutdown();
        try {
            loadExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 释放所有 GPU 资源
        for (LODChunk chunk : chunkCache.values()) {
            chunk.releaseGPUResources();
        }
        chunkCache.clear();

        LOGGER.info("LODChunkManager shutdown: loaded=" + totalLoaded.get() +
                ", unloaded=" + totalUnloaded.get() + ", failed=" + totalFailed.get());
    }

    // ==================== 内部方法 ====================

    /**
     * 调度新区块加载
     */
    private void scheduleNewChunks() {
        int radius = loadRadius;
        int cx = playerChunkX;
        int cz = playerChunkZ;

        // Pre-compute LOD thresholds as squared distances (avoid sqrt in loop)
        // Since sqrt is monotonic: d1 < d2 <=> d1^2 < d2^2
        float nearRangeSq = RenderiumLODManager.NEAR_RANGE_CHUNKS * RenderiumLODManager.NEAR_RANGE_CHUNKS;
        float transitionEndSq = RenderiumLODManager.TRANSITION_END_CHUNKS * RenderiumLODManager.TRANSITION_END_CHUNKS;
        float maxFarRangeSq = RenderiumLODManager.MAX_FAR_RANGE_CHUNKS * RenderiumLODManager.MAX_FAR_RANGE_CHUNKS;

        // Schedule from near to far (priority for nearby chunks)
        for (int r = 0; r <= radius; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) {
                for (int dz = -r; dz <= r; dz += 4) {
                    // Only process ring area
                    if (r > 0 && Math.abs(dx) < r - 4 && Math.abs(dz) < r - 4) continue;

                    int tx = cx + dx;
                    int tz = cz + dz;
                    long key = encodeKey(tx, tz);

                    if (!chunkCache.containsKey(key)) {
                        // Determine data type using squared distance (avoids Math.sqrt)
                        float distSq = dx * dx + dz * dz;
                        RenderiumLODManager.LODLevel level;

                        // Compare squared distances to squared thresholds (equivalent to comparing distances)
                        if (distSq <= nearRangeSq) {
                            level = RenderiumLODManager.LODLevel.NEAR_FULL_DETAIL;
                        } else if (distSq <= transitionEndSq) {
                            level = RenderiumLODManager.LODLevel.MID_SIMPLIFIED;
                        } else if (distSq <= maxFarRangeSq) {
                            level = RenderiumLODManager.LODLevel.FAR_BILLBOARD;
                        } else {
                            level = RenderiumLODManager.LODLevel.HORIZON_FADE;
                        }

                        LODChunk.DataType dataType = switch (level) {
                            case MID_SIMPLIFIED -> LODChunk.DataType.HEIGHTMAP_WITH_COLOR;
                            case FAR_BILLBOARD -> LODChunk.DataType.BILLBOARD;
                            case HORIZON_FADE -> LODChunk.DataType.HEIGHTMAP_ONLY;
                            default -> LODChunk.DataType.HEIGHTMAP_WITH_COLOR;
                        };

                        LODChunk chunk = new LODChunk(tx, tz, dataType);
                        chunk.setLodLevel(level);
                        chunkCache.put(key, chunk);

                        // Async load data
                        scheduleLoad(chunk);
                    }
                }
            }
        }
    }

    /**
     * 异步加载区块数据
     */
    private void scheduleLoad(LODChunk chunk) {
        loadExecutor.submit(() -> {
            try {
                chunk.setState(LODChunk.State.LOADING);

                // TODO: 从区块数据提取高度图和颜色图
                // 实际实现需要访问 MC 的 ChunkHolder / PalettedContainer
                // 这里使用占位逻辑
                short[] heights = new short[256];
                int[] colors = new int[256];
                Arrays.fill(heights, (short) 64);
                Arrays.fill(colors, 0xFF808080);

                chunk.setHeightMap(heights);
                if (chunk.getDataType() != LODChunk.DataType.HEIGHTMAP_ONLY) {
                    chunk.setColorMap(colors);
                }

                chunk.markReady();
                completedQueue.add(chunk);
                totalLoaded.incrementAndGet();
            } catch (Exception e) {
                chunk.setState(LODChunk.State.FAILED);
                totalFailed.incrementAndGet();
            }
        });
    }

    /**
     * 回收远处的区块
     */
    private void unloadDistantChunks() {
        float unloadDist = loadRadius * UNLOAD_MULTIPLIER;
        int unloadDistSq = (int) (unloadDist * unloadDist);

        Iterator<Map.Entry<Long, LODChunk>> it = chunkCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, LODChunk> entry = it.next();
            LODChunk chunk = entry.getValue();
            int dx = chunk.getChunkX() - playerChunkX;
            int dz = chunk.getChunkZ() - playerChunkZ;
            if (dx * dx + dz * dz > unloadDistSq) {
                chunk.releaseGPUResources();
                it.remove();
                totalUnloaded.incrementAndGet();
            }
        }

        // LRU 淘汰：如果缓存超过上限
        if (chunkCache.size() > maxCacheSize) {
            chunkCache.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> e.getValue().getLastUpdateTimestamp()))
                    .limit(chunkCache.size() - maxCacheSize)
                    .forEach(e -> {
                        e.getValue().releaseGPUResources();
                        chunkCache.remove(e.getKey());
                        totalUnloaded.incrementAndGet();
                    });
        }
    }

    /**
     * 编码区块坐标为 long key
     */
    private static long encodeKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    // ==================== Getter ====================

    public int getCacheSize() { return chunkCache.size(); }
    public int getLoadRadius() { return loadRadius; }
    public void setLoadRadius(int radius) { this.loadRadius = radius; }
    public int getTotalLoaded() { return totalLoaded.get(); }
    public int getTotalUnloaded() { return totalUnloaded.get(); }

    public String getDiagnostics() {
        return String.format(
            "LODChunkManager{cache=%d/%d, radius=%d, player=(%d,%d), loaded=%d, unloaded=%d, failed=%d}",
            chunkCache.size(), maxCacheSize, loadRadius, playerChunkX, playerChunkZ,
            totalLoaded.get(), totalUnloaded.get(), totalFailed.get());
    }
}
