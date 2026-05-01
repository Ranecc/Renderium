// Renderium - 区段级遮挡剔除器
// 基于图遍历的可见区段搜索算法

package com.ranecc.renderium.feature.culling.optimization.optimization.culling;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 区段级遮挡剔除算法实现。
 *
 * <p>算法思路参考了 CaffeineMC 的 Sodium 项目 (LGPL-3.0) 中的 OcclusionCuller。
 * 本代码为 Renderium 团队完全独立重写，以适配 Vulkan CommandBuffer 管线。
 *
 * <h2>核心原理</h2>
 * <p>邻居面剔除解决了"单个方块的面是否可见"的问题，而区段遮挡剔除解决的是
 * "整个 16x16x16 区块是否被前方的不透明区块完全挡住"的问题。
 *
 * <p>使用广度优先搜索（BFS）从玩家所在的区段开始遍历：
 * <ol>
 *   <li>将玩家所在区段加入队列作为起点</li>
 *   <li>从队列取出一个区段，标记为可见</li>
 *   <li>检查该区段的 6 个方向邻居</li>
 *   <li>如果从当前区段到该方向的路径没有被遮挡，则将邻居加入队列</li>
 *   <li>重复直到队列为空</li>
 * </ol>
 *
 * <h2>角度遮挡优化</h2>
 * <p>当相机以倾斜角度观察时，某些方向（如上下）的穿透可能性极低。
 * 通过计算相机与区段的相对位置，可以提前屏蔽这些不可能的方向，
 * 减少不必要的遍历。
 *
 * @see <a href="https://github.com/CaffeineMC/sodium">Sodium Repository</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RenderiumSectionOcclusionCuller {

    /** 图方向常量：下、上、北、南、西、东 */
    public static final int DIR_DOWN = 0;
    public static final int DIR_UP = 1;
    public static final int DIR_NORTH = 2;
    public static final int DIR_SOUTH = 3;
    public static final int DIR_WEST = 4;
    public static final int DIR_EAST = 5;
    public static final int DIRECTION_COUNT = 6;

    /** 所有方向的掩码 */
    public static final int ALL_DIRECTIONS = 0x3F;

    /** 无有效方向 */
    public static final int NO_DIRECTION = 0x00;

    /**
     * 私有构造函数 - 工具类不应被实例化
     */
    private RenderiumSectionOcclusionCuller() {
        throw new AssertionError("工具类不可实例化");
    }

    // ==================== 核心接口 ====================

    /**
     * 执行区段遮挡剔除，找出所有可见的渲染区段
     *
     * @param context 剔除上下文（包含相机信息、区段数据等）
     * @param visitor 可见区段访问者回调
     */
    public static void findVisibleSections(OcclusionContext context, SectionVisitor visitor) {

        // 获取当前帧编号（用于防止同一帧内重复处理）
        int currentFrame = context.getFrameIndex();

        // 初始化 BFS 队列
        Queue<RenderSectionInfo> queue = new ArrayDeque<>(256);

        // 将玩家所在区段作为起点加入队列
        RenderSectionInfo origin = context.getOriginSection();
        if (origin == null) return;

        origin.setLastVisibleFrame(currentFrame);
        origin.setIncomingDirections(NO_DIRECTION);
        visitor.visit(origin);

        // 计算起始区段的出方向
        int initialOutgoing = computeInitialOutgoing(origin, context.isOcclusionEnabled());
        enqueueNeighbors(queue, origin, initialOutgoing, currentFrame, context);

        // BFS 主循环
        while (!queue.isEmpty()) {
            RenderSectionInfo section = queue.poll();

            // 检查该区段是否在渲染距离和视锥体内
            if (!isSectionVisible(section, context)) {
                continue;
            }

            // 标记为可见并通知访问者
            visitor.visit(section);

            // 计算可通向邻居的有效方向
            int outgoingConnections;
            if (context.isOcclusionEnabled()) {
                // 启用遮挡时：只允许通过未被遮挡的方向
                long visibilityData = section.getVisibilityData();
                visibilityData = applyAngleOcclusionMask(context, section);
                outgoingConnections = extractConnections(visibilityData, section.getIncomingDirections());
            } else {
                // 禁用遮挡时：允许所有方向
                outgoingConnections = ALL_DIRECTIONS;
            }

            // 只允许向外扩展（远离起点的方向）
            outgoingConnections &= computeOutwardDirections(context, section);

            // 将有效邻居入队
            enqueueNeighbors(queue, section, outgoingConnections, currentFrame, context);
        }

        // 处理近邻区段（处理大型模型可能超出 16x16x16 边界的情况）
        processNearbySections(context, visitor, currentFrame);
    }

    // ==================== 可见性判断 ====================

    /**
     * 综合判断一个区段是否应该被渲染
     *
     * <p>同时检查两个条件：
     * <ul>
     *   <li>渲染距离：使用圆柱形距离判断（原版 MC 算法）</li>
     *   <li>视锥体：使用 6 平截头体测试</li>
     * </ul>
     */
    private static boolean isSectionVisible(RenderiumSectionOcclusionCuller.RenderSectionInfo section, OcclusionContext context) {
        return isWithinRenderDistance(section, context) && isWithinFrustum(section, context);
    }

    /**
     * 圆柱形渲染距离判断
     *
     * <p>使用原版 Minecraft 的"圆柱雾"算法：
     * max(length(distance.xz), abs(distance.y)) < renderDistance
     * 这意味着水平方向是圆形裁剪，垂直方向是独立裁剪。
     *
     * @param section 要判断的区段
     * @param context 剔除上下文
     * @return true 如果在渲染距离内
     */
    private static boolean isWithinRenderDistance(RenderSectionInfo section, OcclusionContext context) {
        float dx = section.getCenterX() - context.getCameraX();
        float dy = section.getCenterY() - context.getCameraY();
        float dz = section.getCenterZ() - context.getCameraZ();

        float renderDist = context.getRenderDistance();
        float distXZ = dx * dx + dz * dz;

        // 圆柱形判断：水平圆形 + 垂直独立
        return (distXZ < renderDist * renderDist) && (Math.abs(dy) < renderDist);
    }

    /**
     * 视锥体包含测试
     *
     * @param section 要判断的区段
     * @param context 剔除上下文
     * @return true 如果在视锥体内
     */
    private static boolean isWithinFrustum(RenderSectionInfo section, OcclusionContext context) {
        return context.getFrustum().isBoxVisible(
                section.getCenterX(),
                section.getCenterY(),
                section.getCenterZ()
        );
    }

    // ==================== 遮挡优化 ====================

    /**
     * 应用角度遮挡掩码
     *
     * <p>当相机以大角度观察时，某些方向的穿透可能性极低。
     * 例如：从上方俯视时，几乎不可能看到下方区段的上方表面。
     * 提前屏蔽这些方向可以减少无效遍历。
     */
    private static long applyAngleOcclusionMask(OcclusionContext context, RenderSectionInfo section) {
        long visibilityData = section.getVisibilityData();

        float absDx = Math.abs(context.getCameraX() - section.getCenterX());
        float absDy = Math.abs(context.getCameraY() - section.getCenterY());
        float absDz = Math.abs(context.getCameraZ() - section.getCenterZ());

        // 当 X 或 Z 方向偏移远大于 Y 时，屏蔽上下方向对
        if (absDx > absDy * 2.0f || absDz > absDy * 2.0f) {
            visibilityData &= ~(1L << encodeDirectionPair(DIR_DOWN, DIR_UP));
            visibilityData &= ~(1L << encodeDirectionPair(DIR_UP, DIR_DOWN));
        }

        // 当 X 或 Y 方向偏移远大于 Z 时，屏蔽南北方向对
        if (absDx > absDz * 2.0f || absDy > absDz * 2.0f) {
            visibilityData &= ~(1L << encodeDirectionPair(DIR_NORTH, DIR_SOUTH));
            visibilityData &= ~(1L << encodeDirectionPair(DIR_SOUTH, DIR_NORTH));
        }

        // 当 Y 或 Z 方向偏移远大于 X 时，屏蔽东西方向对
        if (absDy > absDx * 2.0f || absDz > absDx * 2.0f) {
            visibilityData &= ~(1L << encodeDirectionPair(DIR_WEST, DIR_EAST));
            visibilityData &= ~(1L << encodeDirectionPair(DIR_EAST, DIR_WEST));
        }

        return visibilityData;
    }

    /**
     * 编码方向对为位索引
     *
     * <p>与 {@link VisibilityEncoding#bit(int, int)} 使用相同的编码方式：
     * {@code from * 8 + to}（SWAR 对齐），而非 {@code from * 6 + to}（紧凑但无法加速）。
     */
    private static int encodeDirectionPair(int fromDir, int toDir) {
        return (fromDir << 3) | toDir;
    }

    // ==================== 邻居入队 ====================

    /**
     * 将有效的邻居区段加入 BFS 队列
     */
    private static void enqueueNeighbors(
            Queue<RenderSectionInfo> queue,
            RenderSectionInfo section,
            int outgoing,
            int frame,
            OcclusionContext context) {

        // 与现有邻居取交集（只处理实际存在的邻居）
        int validOutgoing = outgoing & section.getAdjacentMask();

        if (validOutgoing == NO_DIRECTION) {
            return; // 没有有效邻居
        }

        // 检查每个方向
        for (int dir = 0; dir < DIRECTION_COUNT; dir++) {
            if ((validOutgoing & (1 << dir)) != 0) {
                RenderSectionInfo neighbor = section.getAdjacent(dir);
                if (neighbor != null) {
                    visitOrEnqueue(queue, neighbor, oppositeDirection(dir), frame);
                }
            }
        }
    }

    /**
     * 访问或入队一个区段
     *
     * <p>如果该区段在本帧尚未被访问过，则初始化其状态并入队；
     * 否则只更新其入方向集合。
     */
    private static void visitOrEnqueue(
            Queue<RenderSectionInfo> queue,
            RenderSectionInfo section,
            int incomingDirection,
            int frame) {

        if (section.getLastVisibleFrame() != frame) {
            // 首次访问本帧：初始化状态
            section.setLastVisibleFrame(frame);
            section.setIncomingDirections(NO_DIRECTION);
            queue.offer(section);
        }

        // 累加入方向
        section.addIncomingDirections(1 << incomingDirection);
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取相反方向
     */
    private static int oppositeDirection(int direction) {
        return switch (direction) {
            case DIR_DOWN -> DIR_UP;
            case DIR_UP -> DIR_DOWN;
            case DIR_NORTH -> DIR_SOUTH;
            case DIR_SOUTH -> DIR_NORTH;
            case DIR_WEST -> DIR_EAST;
            case DIR_EAST -> DIR_WEST;
            default -> throw new IllegalArgumentException("未知方向: " + direction);
        };
    }

    /**
     * 从可见性数据和入方向提取出方向连接
     *
     * <p>使用 {@link VisibilityEncoding#getConnections(long, int)} 实现。
     * 核心算法：将入方向掩码扩展为行选择掩码，与可见性数据做 AND，
     * 然后用 SWAR 折叠技术合并所有行的出方向。
     *
     * @param visibilityData 区段的可见性编码（6×6 方向矩阵压缩为 long）
     * @param incomingDirections 入方向掩码（哪些方向有路径进入此区段）
     * @return 出方向掩码（可以从哪些方向离开此区段）
     */
    private static int extractConnections(long visibilityData, int incomingDirections) {
        return VisibilityEncoding.getConnections(visibilityData, incomingDirections);
    }

    /**
     * 计算初始出方向
     *
     * <p>起点区段没有入方向限制，使用 {@link VisibilityEncoding#getConnections(long)}
     * 计算所有可能的出方向。如果可见性数据为空（NULL），则允许所有方向。
     *
     * @param origin 起点区段
     * @param occlusionEnabled 是否启用遮挡剔除
     * @return 初始出方向掩码
     */
    private static int computeInitialOutgoing(RenderSectionInfo origin, boolean occlusionEnabled) {
        if (occlusionEnabled) {
            long visibilityData = origin.getVisibilityData();
            if (visibilityData == VisibilityEncoding.NULL) {
                return ALL_DIRECTIONS;
            }
            return VisibilityEncoding.getConnections(visibilityData);
        }
        return ALL_DIRECTIONS;
    }

    /**
     * 计算向外扩展的有效方向（只能远离起点）
     */
    private static int computeOutwardDirections(OcclusionContext context, RenderSectionInfo section) {
        int planes = 0;
        int originChunkX = context.getOriginChunkX();
        int originChunkY = context.getOriginChunkY();
        int originChunkZ = context.getOriginChunkZ();

        // X 方向
        if (section.getChunkX() <= originChunkX) planes |= (1 << DIR_WEST);
        if (section.getChunkX() >= originChunkX) planes |= (1 << DIR_EAST);

        // Y 方向
        if (section.getChunkY() <= originChunkY) planes |= (1 << DIR_DOWN);
        if (section.getChunkY() >= originChunkY) planes |= (1 << DIR_UP);

        // Z 方向
        if (section.getChunkZ() <= originChunkZ) planes |= (1 << DIR_NORTH);
        if (section.getChunkZ() >= originChunkZ) planes |= (1 << DIR_SOUTH);

        return planes;
    }

    /**
     * 处理近邻区段（大型模型可能超出边界的情况）
     */
    private static void processNearbySections(OcclusionContext context, SectionVisitor visitor, int frame) {
        // 检查起点周围 3x3x3 范围内的区段
        // 这些区段可能在图遍历中未被访问到，但其大型模型可能部分可见
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    RenderSectionInfo nearby = context.getNearbySection(dx, dy, dz);
                    if (nearby != null &&
                        nearby.getLastVisibleFrame() != frame &&
                        isWithinLooseFrustum(nearby, context)) {

                        nearby.setLastVisibleFrame(frame);
                        visitor.visit(nearby);
                    }
                }
            }
        }
    }

    /**
     * 宽松视锥体测试（用于近邻区段）
     */
    private static boolean isWithinLooseFrustum(RenderSectionInfo section, OcclusionContext context) {
        return context.getFrustum().isBoxVisibleLoose(
                section.getCenterX(),
                section.getCenterY(),
                section.getCenterZ()
        );
    }

    // ==================== 数据结构定义 ====================

    /**
     * 渲染区段信息接口
     *
     * <p>这是遮挡剔除算法操作的核心数据结构，
     * 封装了一个 16x16x16 区块的渲染相关状态。
     */
    public interface RenderSectionInfo {

        /** 获取区段中心 X 坐标（世界坐标） */
        int getCenterX();
        /** 获取区段中心 Y 坐标（世界坐标） */
        int getCenterY();
        /** 获取区段中心 Z 坐标（世界坐标） */
        int getCenterZ();

        /** 获取区块坐标 X */
        int getChunkX();
        /** 获取区块坐标 Y */
        int getChunkY();
        /** 获取区块坐标 Z */
        int getChunkZ();

        /** 获取可见性编码数据（用于遮挡判断） */
        long getVisibilityData();
        /** 获取入方向集合 */
        int getIncomingDirections();
        /** 获取相邻区段存在掩码 */
        int getAdjacentMask();

        /** 获取指定方向的相邻区段 */
        RenderSectionInfo getAdjacent(int direction);

        /** 设置/获取最后可见帧号 */
        void setLastVisibleFrame(int frame);
        int getLastVisibleFrame();

        /** 设置/获取入方向 */
        void setIncomingDirections(int directions);
        void addIncomingDirections(int directions);
    }

    /**
     * 遮挡剔除上下文
     *
     * <p>封装了一次遮挡剔除所需的所有外部数据
     */
    public interface OcclusionContext {

        /** 获取当前帧索引 */
        int getFrameIndex();

        /** 获取玩家所在区段（BFS 起点） */
        RenderSectionInfo getOriginSection();

        /** 获取相机 X 坐标 */
        float getCameraX();
        /** 获取相机 Y 坐标 */
        float getCameraY();
        /** 获取相机 Z 坐标 */
        float getCameraZ();

        /** 获取渲染距离（区块数） */
        float getRenderDistance();

        /** 获取视锥体对象 */
        Frustum getFrustum();

        /** 是否启用了遮挡剔除 */
        boolean isOcclusionEnabled();

        /** 获取起点区块坐标 */
        int getOriginChunkX();
        int getOriginChunkY();
        int getOriginChunkZ();

        /** 获取相对起点的近邻区段 */
        RenderSectionInfo getNearbySection(int dx, int dy, int dz);
    }

    /**
     * 可见区段访问者接口
     *
     * <p>当发现一个可见区段时调用此回调
     */
    public interface SectionVisitor {

        /**
         * 访问一个可见的渲染区段
         *
         * @param section 可见的区段
         */
        void visit(RenderSectionInfo section);
    }

    /**
     * 视锥体接口
     */
    public interface Frustum {

        /** 测试包围盒是否可见 */
        boolean isBoxVisible(float centerX, float centerY, float centerZ);

        /** 宽松版包围盒测试（用于近邻区段） */
        boolean isBoxVisibleLoose(float centerX, float centerY, float centerZ);
    }
}
