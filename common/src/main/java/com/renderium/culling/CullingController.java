// Renderium - Culling Controller
// Advanced culling algorithms for Minecraft

package com.renderium.culling;

import com.renderium.api.FrustumCuller;

import java.util.*;

/**
 * 剔除控制器
 * 管理多种剔除算法并协调它们的工作
 */
public final class CullingController {

    private static volatile CullingController instance;

    private final List<CullingStrategy> strategies;
    private int maxDrawDistance;
    private float cameraX, cameraY, cameraZ;
    private float pitch, yaw, fov;

    private final Map<String, ChunkVisibility> visibilityCache;
    private final List<FrustumCuller.ChunkEdge> exposedEdges;

    // Reusable collections to avoid per-frame allocation
    private final Set<Integer> visibleSet = new HashSet<>();
    private final List<FrustumCuller.ChunkEdge> edgesList = new ArrayList<>();
    private final List<CullingStrategy.ChunkData> strategyChunksList = new ArrayList<>();

    private CullingController() {
        this.strategies = new ArrayList<>();
        this.visibilityCache = new HashMap<>();
        this.exposedEdges = new ArrayList<>();
    }

    /**
     * 获取单例实例
     */
    public static CullingController getInstance() {
        if (instance == null) {
            synchronized (CullingController.class) {
                if (instance == null) {
                    instance = new CullingController();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化控制器
     *
     * @param maxDrawDistance 最大可视距离
     */
    public void initialize(int maxDrawDistance) {
        this.maxDrawDistance = maxDrawDistance;
        this.strategies.clear();
        this.visibilityCache.clear();
        this.exposedEdges.clear();

        // 注册默认策略
        registerStrategy(new FrustumCullingStrategy());
        registerStrategy(new DistanceCullingStrategy(maxDrawDistance));
    }

    /**
     * 注册剔除策略
     *
     * @param strategy 剔除策略
     */
    public void registerStrategy(CullingStrategy strategy) {
        Objects.requireNonNull(strategy, "Strategy cannot be null");
        strategies.add(strategy);
        strategies.sort(Comparator.comparingInt(CullingStrategy::getPriority));
    }

    /**
     * 更新相机状态
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param pitch 俯仰角
     * @param yaw 偏航角
     * @param fov 视野
     */
    public void updateCamera(float x, float y, float z, float pitch, float yaw, float fov) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        this.pitch = pitch;
        this.yaw = yaw;
        this.fov = fov;
    }

    /**
     * 执行剔除计算
     * 返回应该渲染的区块
     *
     * @param chunks 所有加载的区块
     * @return 可见区块结果
     */
    public CullingResult computeVisibleChunks(List<ChunkData> chunks) {
        // Reuse collections to avoid allocation (clear instead of new)
        visibleSet.clear();
        edgesList.clear();

        // Reuse strategy chunks list - avoid creating new list each frame
        strategyChunksList.clear();
        for (ChunkData chunk : chunks) {
            // Wrap in CullingStrategy.ChunkData for strategy compatibility
            strategyChunksList.add(new CullingStrategy.ChunkData(
                chunk.x(), chunk.y(), chunk.z(),
                chunk.minX(), chunk.minY(), chunk.minZ(),
                chunk.maxX(), chunk.maxY(), chunk.maxZ()
            ));
        }

        // Execute culling strategies sequentially
        for (CullingStrategy strategy : strategies) {
            Set<Integer> strategyResult = strategy.cull(
                strategyChunksList, cameraX, cameraY, cameraZ, pitch, yaw, fov);

            if (strategy.isAdditive()) {
                visibleSet.addAll(strategyResult);
            } else {
                if (visibleSet.isEmpty()) {
                    visibleSet.addAll(strategyResult);
                } else {
                    visibleSet.retainAll(strategyResult);
                }
            }
        }

        // Detect edge chunks with reduced object creation
        for (int idx : visibleSet) {
            if (idx < 0 || idx >= chunks.size()) continue;

            ChunkData chunk = chunks.get(idx);
            int cx = chunk.x(), cy = chunk.y(), cz = chunk.z();

            for (Direction dir : Direction.values()) {
                int[] neighbor = getNeighbor(cx, cy, cz, dir);
                int neighborIdx = findChunkIndex(chunks, neighbor[0], neighbor[1], neighbor[2]);

                if (neighborIdx < 0 || !visibleSet.contains(neighborIdx)) {
                    edgesList.add(new FrustumCuller.ChunkEdge(
                        cx, cy, cz,
                        neighbor[0], neighbor[1], neighbor[2],
                        toFrustumDirection(dir)
                    ));
                }
            }
        }

        this.exposedEdges.clear();
        this.exposedEdges.addAll(edgesList);

        return new CullingResult(visibleSet, edgesList);
    }

    /**
     * 获取暴露的边缘
     */
    public List<FrustumCuller.ChunkEdge> getExposedEdges() {
        return Collections.unmodifiableList(exposedEdges);
    }

    /**
     * 清除可见性缓存
     * 当相机移动距离超过阈值时调用
     */
    public void clearVisibilityCache() {
        visibilityCache.clear();
    }

    private FrustumCuller.ChunkEdge.Direction toFrustumDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> FrustumCuller.ChunkEdge.Direction.SOUTH;
            case SOUTH -> FrustumCuller.ChunkEdge.Direction.NORTH;
            case EAST -> FrustumCuller.ChunkEdge.Direction.WEST;
            case WEST -> FrustumCuller.ChunkEdge.Direction.EAST;
            case UP -> FrustumCuller.ChunkEdge.Direction.UP;
            case DOWN -> FrustumCuller.ChunkEdge.Direction.DOWN;
        };
    }

    private int[] getNeighbor(int x, int y, int z, Direction dir) {
        return switch (dir) {
            case NORTH -> new int[]{x, y, z - 1};
            case SOUTH -> new int[]{x, y, z + 1};
            case EAST -> new int[]{x + 1, y, z};
            case WEST -> new int[]{x - 1, y, z};
            case UP -> new int[]{x, y + 1, z};
            case DOWN -> new int[]{x, y - 1, z};
        };
    }

    private int findChunkIndex(List<ChunkData> chunks, int x, int y, int z) {
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            if (chunk.x() == x && chunk.y() == y && chunk.z() == z) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 方向枚举
     */
    public enum Direction {
        NORTH, SOUTH, EAST, WEST, UP, DOWN
    }

    /**
     * 区块数据 - 使用 CullingStrategy 中定义的记录
     */
    public record ChunkData(
        int x, int y, int z,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ
    ) {
        /**
         * 从 CullingStrategy.ChunkData 转换
         */
        public static ChunkData from(CullingStrategy.ChunkData data) {
            return new ChunkData(data.x(), data.y(), data.z(),
                data.minX(), data.minY(), data.minZ(),
                data.maxX(), data.maxY(), data.maxZ());
        }
    }

    /**
     * 剔除结果
     */
    public record CullingResult(
        Set<Integer> visibleChunks,
        List<FrustumCuller.ChunkEdge> edges
    ) {
        public int getVisibleCount() {
            return visibleChunks.size();
        }

        public int getEdgeCount() {
            return edges.size();
        }
    }

    /**
     * 区块可见性缓存键
     */
    private record ChunkVisibility(
        long cameraChunkX,
        long cameraChunkZ,
        Set<Integer> visibleChunks
    ) {}
}
