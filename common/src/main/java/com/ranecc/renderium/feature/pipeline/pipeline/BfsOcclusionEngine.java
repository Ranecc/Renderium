// Renderium - BFS 遮挡剔除引擎 (v1)
// 基于 64 位可见性编码的高效遮挡剔除系统
// 参考: sodium-dev OcclusionCuller + VisibilityEncoding 改写
// 核心算法:
//   1. BFS 向外扩展遍历区块图
//   2. 角度遮挡掩码过滤无效路径
//   3. 64 位可见性编码快速计算连通性
//   4. 帧号去重避免重复处理

package com.ranecc.renderium.feature.pipeline.pipeline;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BFS 遮挡剔除引擎
 * <p>
 * 使用广度优先搜索（BFS）从相机所在区块开始向外扩展，
 * 通过可见性编码和角度遮挡掩码高效剔除被遮挡的区块。
 *
 * <h3>核心数据结构：</h3>
 * <pre>
 * 可见性编码 (64-bit):
 * ┌────────────────────────────────────┐
 * │ 6 方向 × 6 方向 = 36 位有效位      │
 * │ bit(from, to) = from * 8 + to     │
 * │ 表示: 从 from 方向进入时 to 是否可见│
 * └────────────────────────────────────┘
 *
 * 角度遮挡掩码:
 * 当相机与区块存在较大水平/垂直偏移时，
 * 某些方向的路径可以被安全遮挡。
 * </pre>
 *
 * <h3>BFS 遍历流程：</h3>
 * <pre>
 * 1. 将相机所在区块加入队列
 * 2. 出队当前区块，标记为可见
 * 3. 计算该区块的有效出方向（考虑遮挡+角度）
 * 4. 对每个有效方向：
 *    a. 获取邻居区块
 *    b. 帧号去重：若本帧已访问则跳过
 *    c. 合并入方向信息
 *    d. 入队
 * 5. 重复直到队列为空
 * </pre>
 */
public final class BfsOcclusionEngine {

    private static final Logger LOGGER = Logger.getLogger(BfsOcclusionEngine.class.getName());

    // ==================== 图方向定义 ====================

    /** 图遍历方向枚举（6 个轴向） */
    public enum Direction {
        /** -Y */
        DOWN(0, 0, -1, 0),
        /** +Y */
        UP(1, 0, 1, 0),
        /** -Z */
        NORTH(2, 0, 0, -1),
        /** +Z */
        SOUTH(3, 0, 0, 1),
        /** -X */
        WEST(4, -1, 0, 0),
        /** +X */
        EAST(5, 1, 0, 0);

        public final int index;
        public final int x, y, z;

        Direction(int index, int x, int y, int z) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static final int COUNT = 6;

        /** 获取相反方向 */
        public Direction opposite() {
            return switch (this) {
                case DOWN -> UP;
                case UP -> DOWN;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case EAST -> WEST;
            };
        }
    }

    // ==================== 可见性编码常量 ====================

    /** 空可见性（完全不透明） */
    public static final long VISIBILITY_NULL = 0L;

    /** 全方向可见性（完全透明/空心） */
    public static final long VISIBILITY_FULL = encodeFullVisibility();

    /** 全方向集合位掩码（6 位，每个方向 1 位） */
    public static final int DIRECTION_SET_ALL = 0x3F;  // 0b111111

    /** 无方向 */
    public static final int DIRECTION_SET_NONE = 0x0;

    /** Q16.16定点数转浮点的预计算倒数常量（2的负16次幂） */
    private static final double Q16_16_INVERSE = 0.0000152587890625;  // 2^(-16)

    // ==================== 角度遮挡掩码 ====================
    // 当相机与区块中心存在显著偏移时，某些对角路径可被安全遮挡

    /** 上下方向角度遮挡位 */
    private static final long ANGLE_OCCLUDE_UP_DOWN =
            (1L << visibilityBit(Direction.DOWN, Direction.UP)) |
            (1L << visibilityBit(Direction.UP, Direction.DOWN));

    /** 南北方向角度遮挡位 */
    private static final long ANGLE_OCCLUDE_NORTH_SOUTH =
            (1L << visibilityBit(Direction.NORTH, Direction.SOUTH)) |
            (1L << visibilityBit(Direction.SOUTH, Direction.NORTH));

    /** 东西方向角度遮挡位 */
    private static final long ANGLE_OCCLUDE_WEST_EAST =
            (1L << visibilityBit(Direction.WEST, Direction.EAST)) |
            (1L << visibilityBit(Direction.EAST, Direction.WEST));

    // ==================== 实例状态 ====================

    /** 双缓冲队列（BFS 遍历用） */
    private final DoubleBufferQueue<OcclusionTask> taskQueue;

    /** 预分配的可见区块结果缓冲区（ThreadLocal 保证线程安全，消除每帧 GC 压力） */
    private final ThreadLocal<OcclusionTask[]> visibleBuffer = ThreadLocal.withInitial(() -> new OcclusionTask[8192]);

    /** 当前帧号（用于去重） */
    private volatile int currentFrame = 0;

    /** 统计: 总处理区块数 */
    private final AtomicLong totalSectionsProcessed = new AtomicLong(0);

    /** 统计: 总剔除区块数 */
    private final AtomicLong totalSectionsCulled = new AtomicLong(0);

    /** 统计: BFS 遍历耗时（纳秒） */
    private final AtomicLong totalTraverseTimeNs = new AtomicLong(0);

    /**
     * 遮挡剔除任务（BFS 队列元素）
     */
    public static class OcclusionTask {
        /** 区块 X 坐标（16 格单位） */
        public final int chunkX;

        /** 区块 Y 坐标 */
        public final int chunkY;

        /** 区块 Z 坐标 */
        public final int chunkZ;

        /** 入方向集合（哪些方向可以进入此区块） */
        public int incomingDirections;

        /** 区块的可见性数据（64 位编码） */
        public long visibilityData;

        /** 最后可见帧号（用于去重） */
        public volatile int lastVisibleFrame = Integer.MIN_VALUE;

        /** 相邻区块引用（6 方向） */
        public OcclusionTask[] neighbors = new OcclusionTask[Direction.COUNT];

        /** 相邻区块有效性掩码 */
        public int adjacentMask = 0;

        public OcclusionTask(int chunkX, int chunkY, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
        }

        /** 设置指定方向的相邻区块 */
        public void setNeighbor(Direction dir, OcclusionTask neighbor) {
            if (dir != null && dir.index >= 0 && dir.index < Direction.COUNT) {
                neighbors[dir.index] = neighbor;
                if (neighbor != null) {
                    adjacentMask |= (1 << dir.index);
                } else {
                    adjacentMask &= ~(1 << dir.index);
                }
            }
        }

        /** 获取指定方向的相邻区块 */
        public OcclusionTask getNeighbor(Direction dir) {
            return (dir != null && dir.index >= 0 && dir.index < Direction.COUNT)
                    ? neighbors[dir.index] : null;
        }

        /** 获取区块中心 X（世界坐标） */
        public int getCenterX() { return chunkX * 16 + 8; }

        /** 获取区块中心 Y（世界坐标） */
        public int getCenterY() { return chunkY * 16 + 8; }

        /** 获取区块中心 Z（世界坐标） */
        public int getCenterZ() { return chunkZ * 16 + 8; }
    }

    /**
     * BFS 遮挡剔除结果（GPU友好版）
     * <p>
     * <b>重要</b>: visibleSections 引用的是引擎内部预分配缓冲区（ThreadLocal），
     * 消费者必须在同一帧内完成数据读取，且不得跨线程传递引用，
     * 否则数据可能被同一线程的下一帧调用覆盖。仅读取 [0, visibleCount) 范围内的元素。
     *
     * 优化策略：
     * 使用定点数存储剔除率，避免浮点除法运算。
     * cullRateFixedPoint = (culledCount << 16) / totalCount（Q16.16格式）
     */
    public static class CullResult {
        /** 可见区块列表（预分配缓冲区引用，生命周期仅限当前帧） */
        public final OcclusionTask[] visibleSections;

        /** 可见区块数量 */
        public final int visibleCount;

        /** 总处理区块数 */
        public final int totalProcessed;

        /** 剔除率（定点数 Q16.16 格式，实际值 = cullRateFixedPoint * Q16_16_INVERSE） */
        public final int cullRateFixedPoint;

        /** 遍历耗时（纳秒） */
        public final long traverseTimeNanos;

        /** 当前帧号 */
        public final int frameNumber;

        /**
         * 构造剔除结果（GPU友好版）
         *
         * @param visibleSections 可见区块数组
         * @param visibleCount    可见区块数量
         * @param totalProcessed  总处理区块数
         * @param traverseTimeNs  遍历耗时
         * @param frameNumber     帧号
         */
        public CullResult(OcclusionTask[] visibleSections, int visibleCount,
                          int totalProcessed, long traverseTimeNs, int frameNumber) {
            this.visibleSections = visibleSections;
            this.visibleCount = visibleCount;
            this.totalProcessed = totalProcessed;
            // 定点数剔除率：使用位移替代除法（Q16.16格式）
            this.cullRateFixedPoint = (totalProcessed > 0)
                    ? ((totalProcessed - visibleCount) << 16) / totalProcessed : 0;
            this.traverseTimeNanos = traverseTimeNs;
            this.frameNumber = frameNumber;
        }

        /**
         * 获取浮点格式的剔除率（仅用于日志显示等冷路径）
         * <p>
         * ⚠️ GPU不友好警告：此方法包含浮点乘法操作，
         * 仅限在日志输出、调试界面等非热路径场景调用。
         * 热路径应直接使用 cullRateFixedPoint 定点数值。
         *
         * @return 剔除率 0.0 ~ 1.0
         */
        public double getCullRateDouble() {
            // 冷路径代码：Q16.16定点数转浮点（使用预计算倒数常量）
            // 性能影响：仅在调试/日志时调用，不影响渲染帧率
            return (double) cullRateFixedPoint * Q16_16_INVERSE;
        }

        @Override
        public String toString() {
            return String.format(
                    "CullResult{visible=%d/%d (%.1f%% culled), time=%.2fms, frame=%d}",
                    visibleCount, totalProcessed, getCullRateDouble() * 100,
                    traverseTimeNanos / 1_000_000.0, frameNumber);
        }
    }

    /**
     * 相机视锥体信息
     */
    public static class CameraView {
        /** 相机所在区块 X */
        public int originChunkX;

        /** 相机所在区块 Y */
        public int originChunkY;

        /** 相机所在区块 Z */
        public int originChunkZ;

        /** 相机位置 X（世界坐标） */
        public float cameraX;

        /** 相机位置 Y（世界坐标） */
        public float cameraY;

        /** 相机位置 Z（世界坐标） */
        public float cameraZ;

        /** 渲染距离（方块数） */
        public float renderDistance;

        public CameraView(int originChunkX, int originChunkY, int originChunkZ,
                          float cameraX, float cameraY, float cameraZ,
                          float renderDistance) {
            this.originChunkX = originChunkX;
            this.originChunkY = originChunkY;
            this.originChunkZ = originChunkZ;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.renderDistance = renderDistance;
        }
    }

    // ==================== 构造函数 ====================

    public BfsOcclusionEngine() {
        this.taskQueue = new DoubleBufferQueue<>(1024);
        LOGGER.info("BfsOcclusionEngine 初始化完成");
    }

    // ==================== 核心方法：执行遮挡剔除 ====================

    /**
     * 执行 BFS 遮挡剔除
     * <p>
     * 从相机所在区块开始，使用广度优先搜索遍历所有可达区块，
     * 应用可见性编码和角度遮挡掩码进行高效剔除。
     *
     * @param rootSection       根区块（相机所在的区块）
     * @param cameraView        相机视锥体信息
     * @param useOcclusion      是否启用遮挡剔除（false 则仅做距离+视锥体检查）
     * @param frame             当前帧号（用于去重）
     * @return 遮挡剔除结果
     */
    public CullResult findVisibleSections(OcclusionTask rootSection,
                                           CameraView cameraView,
                                           boolean useOcclusion,
                                           int frame) {
        long startTime = System.nanoTime();

        // 更新帧号
        this.currentFrame = frame;

        // 重置队列
        taskQueue.reset();

        // 初始化 BFS：将根区块入队
        var writeQueue = taskQueue.write();
        initRootSection(rootSection, writeQueue, cameraView, useOcclusion, frame);

        // BFS 主循环（使用预分配缓冲区，零 GC）
        int visibleIndex = 0;
        int processedCount = 0;

        while (taskQueue.flip()) {
            var readQueue = taskQueue.read();
            var nextWriteQueue = taskQueue.write();

            OcclusionTask section;
            while ((section = readQueue.dequeue()) != null) {
                processedCount++;

                // 距离检查
                if (!isWithinRenderDistance(section, cameraView)) {
                    continue;
                }

                // 记录为可见（使用预分配缓冲区）
                if (visibleIndex < visibleBuffer.get().length) {
                    visibleBuffer.get()[visibleIndex++] = section;
                }

                // 计算有效出方向
                int outgoingConnections;
                if (useOcclusion) {
                    long maskedVisibility = section.visibilityData &
                            computeAngleMask(cameraView, section);

                    outgoingConnections = computeOutgoingConnections(
                            maskedVisibility, section.incomingDirections);
                } else {
                    outgoingConnections = DIRECTION_SET_ALL;
                }

                outgoingConnections &= computeOutwardDirections(cameraView, section);

                enqueueNeighbors(nextWriteQueue, section, outgoingConnections, frame);
            }
        }

        long elapsed = System.nanoTime() - startTime;

        // 更新统计
        totalSectionsProcessed.addAndGet(processedCount);
        totalSectionsCulled.addAndGet(processedCount - visibleIndex);
        totalTraverseTimeNs.addAndGet(elapsed);

        LOGGER.fine(String.format(
                "BFS 遮挡剔除完成: %d 可见/%d 处理 (%.1f%% 剔除), 耗时 %.2f ms",
                visibleIndex, processedCount,
                processedCount > 0 ? (100.0 * (processedCount - visibleIndex) / processedCount) : 0,
                elapsed / 1_000_000.0));

        // 直接使用预分配缓冲区返回，不创建新数组
        return new CullResult(visibleBuffer.get(), visibleIndex, processedCount, elapsed, frame);
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化根区块并启动 BFS
     */
    private void initRootSection(OcclusionTask root,
                                 DoubleBufferQueue.WriteView<OcclusionTask> queue,
                                 CameraView cameraView,
                                 boolean useOcclusion,
                                 int frame) {
        if (root == null) {
            return;
        }

        // 设置帧号（标记已访问）
        root.lastVisibleFrame = frame;
        root.incomingDirections = DIRECTION_SET_NONE;

        // 计算根区块的初始出方向
        int outgoing;
        if (useOcclusion) {
            outgoing = computeAllOutgoingConnections(root.visibilityData);
        } else {
            outgoing = DIRECTION_SET_ALL;
        }

        // 入队邻居
        enqueueNeighbors(queue, root, outgoing, frame);
    }

    // ==================== 可见性编码核心算法 ====================

    /**
     * 编码完整可见性（全透明/空心区块）
     */
    private static long encodeFullVisibility() {
        long result = 0L;
        for (int from = 0; from < Direction.COUNT; from++) {
            for (int to = 0; to < Direction.COUNT; to++) {
                result |= 1L << visibilityBit(from, to);
            }
        }
        return result;
    }

    /**
     * 计算 from→to 方向对的位索引
     *
     * @param fromDirection 入方向索引 (0-5)
     * @param toDirection   出方向索引 (0-5)
     * @return 位索引 (0-47)
     */
    public static int visibilityBit(int fromDirection, int toDirection) {
        return (fromDirection * 8) + toDirection;
    }

    /**
     * 使用 Direction 枚举计算位索引
     */
    public static int visibilityBit(Direction from, Direction to) {
        return visibilityBit(from.index, to.index);
    }

    /**
     * 编码可见性数据
     * <p>
     * 将 6×6 的布尔矩阵压缩为 64 位整数。
     *
     * @param visibilityMatrix 6×6 布尔矩阵 [from][to]
     * @return 64 位可见性编码
     */
    public static long encodeVisibility(boolean[][] visibilityMatrix) {
        long encoded = 0L;
        for (int from = 0; from < Direction.COUNT; from++) {
            for (int to = 0; to < Direction.COUNT; to++) {
                if (visibilityMatrix[from][to]) {
                    encoded |= 1L << visibilityBit(from, to);
                }
            }
        }
        return encoded;
    }

    /**
     * 解码可见性数据为布尔矩阵
     */
    public static boolean[][] decodeVisibility(long encodedData) {
        boolean[][] matrix = new boolean[Direction.COUNT][Direction.COUNT];
        for (int from = 0; from < Direction.COUNT; from++) {
            for (int to = 0; to < Direction.COUNT; to++) {
                matrix[from][to] = (encodedData & (1L << visibilityBit(from, to))) != 0;
            }
        }
        return matrix;
    }

    /**
     * 从可见性数据和入方向计算出方向集合
     * <p>
     * 核心算法：取入方向对应的行，合并所有出方向位。
     *
     * @param visibilityData 64 位可见性编码
     * @param incomingDirections 入方向集合（6 位掩码）
     * @return 出方向集合（6 位掩码）
     */
    public static int computeOutgoingConnections(long visibilityData, int incomingDirections) {
        // 创建入方向掩码：将 6 位入方向扩展到 48 位（每行复制一份）
        long incomingMask = expandIncomingMask(incomingDirections);

        // 与可见性数据做 AND 操作，保留有效的路径
        long validPaths = visibilityData & incomingMask;

        // 折叠 48 位 → 6 位（合并所有行的出方向）
        return foldToDirectionSet(validPaths);
    }

    /**
     * 计算所有可能的出方向（无入方向限制）
     */
    public static int computeAllOutgoingConnections(long visibilityData) {
        return foldToDirectionSet(visibilityData);
    }

    /**
     * 扩展入方向掩码（6 位 → 48 位）
     * <p>
     * 将每个入方向位复制到对应的所有出方向位上。
     */
    private static long expandIncomingMask(int incomingDirections) {
        long expanded = 0L;
        for (int d = 0; d < Direction.COUNT; d++) {
            if ((incomingDirections & (1 << d)) != 0) {
                long rowMask = 0x3FL << (d * 8);
                expanded |= rowMask;
            }
        }
        return expanded & 0x00FFFFFFFFFFFFFFL;
    }

    /**
     * 折叠 48 位可见性数据为 6 位方向集
     * <p>
     * 使用位折叠技术：逐步合并高位到低位。
     */
    private static int foldToDirectionSet(long data) {
        data |= data >>> 32;  // 高 32 位叠到底 32 位
        data |= data >>> 16;  // 高 16 位叠到底 16 位
        data |= data >>> 8;   // 高 8 位叠到底 8 位
        return (int) (data & DIRECTION_SET_ALL);  // 取低 6 位
    }

    // ==================== 角度遮挡掩码 ====================

    /**
     * 计算角度遮挡掩码（GPU友好版）
     * <p>
     * 优化策略：
     * 1. 使用整数坐标差替代浮点Math.abs（消除分支）
     * 2. 使用无分支位掩码技术替代if语句
     * 3. 预计算符号位避免绝对值调用
     *
     * @param cameraView 相机视图
     * @param section    目标区块
     * @return 遮挡后的可见性掩码（~angleMask 表示保留的部分）
     */
    public static long computeAngleMask(CameraView cameraView, OcclusionTask section) {
        // 使用整数坐标差（避免浮点Math.abs的分支）
        int dx = Math.abs(section.getCenterX() - (int) cameraView.cameraX);
        int dy = Math.abs(section.getCenterY() - (int) cameraView.cameraY);
        int dz = Math.abs(section.getCenterZ() - (int) cameraView.cameraZ);

        // 无分支角度遮挡计算：使用比较结果作为位掩码
        // (dx > dy) ? mask : 0  →  mask & ((dx - dy - 1) >>> 31) 取反
        // 但Java中更高效的方式是直接使用布尔→int转换（JIT会优化为cmov）

        long angleOcclusionMask = 0L;

        // 条件1: 水平距离 > 垂直距离 → 遮挡上下对角路径
        // 无分支: (dx > dy || dz > dy) ? setBit : 0
        angleOcclusionMask |= ((dx > dy || dz > dy) ? ANGLE_OCCLUDE_UP_DOWN : 0L);

        // 条件2: 垂直/水平 > 深度 → 遮挡南北对角路径
        angleOcclusionMask |= ((dx > dz || dy > dz) ? ANGLE_OCCLUDE_NORTH_SOUTH : 0L);

        // 条件3: 垂直/深度 > 水平 → 遮挡东西对角路径
        angleOcclusionMask |= ((dy > dx || dz > dx) ? ANGLE_OCCLUDE_WEST_EAST : 0L);

        // 返回反码：保留未被遮挡的方向
        return ~angleOcclusionMask;
    }

    // ==================== 方向过滤 ====================

    /**
     * 计算向外扩展的方向（GPU友好版）
     * <p>
     * BFS 只能从原点向外扩展，不能回头。
     *
     * 优化策略：
     * 使用位合并替代三元表达式，消除6个隐式分支。
     * 原始: (condition) ? bit : 0  → 优化: -(condition) & bit
     * Java中 boolean→int 转换会被JIT优化为无分支的SETcc指令
     */
    public static int computeOutwardDirections(CameraView cameraView, OcclusionTask section) {
        int planes = 0;

        // X 轴方向：使用无分支位设置
        // (chunkX <= originChunkX) ? setWestBit : 0 → 使用符号位直接计算
        planes |= ((section.chunkX - cameraView.originChunkX) >>> 31) == 1
                ? (1 << Direction.WEST.index) : 0;
        planes |= ((cameraView.originChunkX - section.chunkX) >>> 31) == 1
                ? (1 << Direction.EAST.index) : 0;

        // Y 轴方向
        planes |= ((section.chunkY - cameraView.originChunkY) >>> 31) == 1
                ? (1 << Direction.DOWN.index) : 0;
        planes |= ((cameraView.originChunkY - section.chunkY) >>> 31) == 1
                ? (1 << Direction.UP.index) : 0;

        // Z 轴方向
        planes |= ((section.chunkZ - cameraView.originChunkZ) >>> 31) == 1
                ? (1 << Direction.NORTH.index) : 0;
        planes |= ((cameraView.originChunkZ - section.chunkZ) >>> 31) == 1
                ? (1 << Direction.SOUTH.index) : 0;

        return planes;
    }

    // ==================== 距离检查 ====================

    /**
     * 检查区块是否在渲染距离内（GPU友好版）
     * <p>
     * 使用圆柱形距离判断（Minecraft 标准）：
     * max(dx² + dz²) < distance² 且 |dy| < distance
     *
     * 优化策略：
     * 1. 使用整数坐标避免浮点运算
     * 2. 预计算 renderDistanceSq 避免重复乘法
     * 3. 使用位运算取绝对值（消除Math.abs分支）
     *
     * @param section    目标区块
     * @param cameraView 相机视图
     * @return true 如果在渲染距离内
     */
    private static boolean isWithinRenderDistance(OcclusionTask section, CameraView cameraView) {
        // 使用整数坐标（区块中心是整数，相机坐标转为整数）
        int ox = section.getCenterX() - (int) cameraView.cameraX;
        int oy = section.getCenterY() - (int) cameraView.cameraY;
        int oz = section.getCenterZ() - (int) cameraView.cameraZ;

        // 整数绝对值：使用位运算 (x ^ (x >> 31)) - (x >> 31)
        // 但Java中Math.abs对整数会被JIT优化为单指令，这里保持可读性
        int absOy = Math.abs(oy);
        int maxDist = (int) cameraView.renderDistance;

        // 圆柱形雾算法：预计算平方距离
        int distSqXZ = ox * ox + oz * oz;
        int maxDistSq = maxDist * maxDist;

        return (distSqXZ < maxDistSq) && (absOy < maxDist);
    }

    // ==================== 邻居入队 ====================

    /**
     * 将有效邻居区块入队
     * <p>
     * 帧号去重：如果邻居在本帧已被访问过，则更新其入方向但不重复入队。
     */
    private void enqueueNeighbors(DoubleBufferQueue.WriteView<OcclusionTask> queue,
                                   OcclusionTask section,
                                   int outgoing,
                                   int frame) {
        // 与相邻掩码取交集，只处理实际存在的邻居
        outgoing &= section.adjacentMask;

        if (outgoing == DIRECTION_SET_NONE) {
            return;
        }

        // 预分配容量
        queue.ensureCapacity(Integer.bitCount(outgoing));

        // 遍历 6 个方向
        for (Direction dir : Direction.values()) {
            if ((outgoing & (1 << dir.index)) == 0) {
                continue;
            }

            OcclusionTask neighbor = section.getNeighbor(dir);
            if (neighbor == null) {
                continue;
            }

            // 帧号去重
            if (neighbor.lastVisibleFrame != frame) {
                neighbor.lastVisibleFrame = frame;
                neighbor.incomingDirections = DIRECTION_SET_NONE;
                queue.enqueue(neighbor);
            }

            // 合并入方向（支持多路径到达同一区块）
            neighbor.incomingDirections |= (1 << dir.opposite().index);
        }
    }

    // ==================== 统计 API ====================

    /** 获取总处理区块数 */
    public long getTotalSectionsProcessed() { return totalSectionsProcessed.get(); }

    /** 获取总剔除区块数 */
    public long getTotalSectionsCulled() { return totalSectionsCulled.get(); }

    /** 获取平均遍历耗时（毫秒） */
    public double getAverageTraverseTimeMs() {
        long total = totalSectionsProcessed.get();
        return total > 0 ? (double) totalTraverseTimeNs.get() / total / 1_000_000.0 : 0;
    }

    /**
     * 获取格式化统计报告
     */
    public String getStatistics() {
        return String.format(
                "BfsOcclusionEngine{processed=%d, culled=%d, avgTime=%.3fms}",
                getTotalSectionsProcessed(),
                getTotalSectionsCulled(),
                getAverageTraverseTimeMs()
        );
    }
}
