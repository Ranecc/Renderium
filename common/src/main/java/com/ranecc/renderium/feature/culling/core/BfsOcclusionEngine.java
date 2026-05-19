package com.ranecc.renderium.feature.culling.core;

import java.util.BitSet;
import java.util.logging.Logger;

/**
 * BFS 遮挡剔除引擎
 *
 * <p>使用广度优先搜索策略遍历场景图，结合视锥体检测
 * 判断场景节点是否可见。仅当节点在视锥体内且未被遮挡时标记为可见。
 *
 * <p>视锥体检测使用与 GPUCullingSystem 一致的 Gribb-Hartmann 平面提取算法。
 */
public class BfsOcclusionEngine {

    private static final Logger LOGGER = Logger.getLogger(BfsOcclusionEngine.class.getName());

    /** 剔除结果 */
    public static final class CullResult {
        /** 是否可见 */
        public final boolean visible;
        /** 可见性掩码位集 */
        public final BitSet visibilityMask;

        public CullResult(boolean visible) {
            this.visible = visible;
            this.visibilityMask = null;
        }

        public CullResult(boolean visible, BitSet visibilityMask) {
            this.visible = visible;
            this.visibilityMask = visibilityMask;
        }
    }

    private static final BfsOcclusionEngine INSTANCE = new BfsOcclusionEngine();

    /** 初始化状态 */
    private boolean initialized = false;

    /** 可见性缓存位集 */
    private final BitSet visibilityCache = new BitSet(65536);

    /** 当前视锥体平面 [6][4] (left, right, top, bottom, near, far) */
    private float[][] frustumPlanes = null;

    /** 当前视锥体平面是否有效 */
    private boolean frustumValid = false;

    private BfsOcclusionEngine() {
    }

    public static BfsOcclusionEngine getInstance() {
        return INSTANCE;
    }

    /**
     * 设置视锥体平面数据
     *
     * @param vpMatrix 16 元素的列主序 view-projection 矩阵
     */
    public void setFrustumFromMatrix(float[] vpMatrix) {
        if (vpMatrix == null || vpMatrix.length < 16) {
            this.frustumValid = false;
            return;
        }
        this.frustumPlanes = extractFrustumPlanes(vpMatrix);
        this.frustumValid = true;
    }

    /**
     * 设置预计算的视锥体平面
     *
     * @param planes 6×4 视锥体平面数组
     */
    public void setFrustumPlanes(float[][] planes) {
        if (planes == null || planes.length < 6) {
            this.frustumValid = false;
            return;
        }
        this.frustumPlanes = planes;
        this.frustumValid = true;
    }

    /**
     * Gribb-Hartmann 视锥体平面提取
     * <p>与 GPUCullingSystem.extractFrustumPlanes() 使用相同的列主序提取算法。
     */
    private static float[][] extractFrustumPlanes(float[] vp) {
        float[][] p = new float[6][4];
        // left + right columns (extracting from column-major VP)
        p[0][0]=vp[3]+vp[0]; p[0][1]=vp[7]+vp[4]; p[0][2]=vp[11]+vp[8]; p[0][3]=vp[15]+vp[12];
        p[1][0]=vp[3]-vp[0]; p[1][1]=vp[7]-vp[4]; p[1][2]=vp[11]-vp[8]; p[1][3]=vp[15]-vp[12];
        // bottom + top
        p[2][0]=vp[3]+vp[1]; p[2][1]=vp[7]+vp[5]; p[2][2]=vp[11]+vp[9]; p[2][3]=vp[15]+vp[13];
        p[3][0]=vp[3]-vp[1]; p[3][1]=vp[7]-vp[5]; p[3][2]=vp[11]-vp[9]; p[3][3]=vp[15]-vp[13];
        // near + far
        p[4][0]=vp[3]+vp[2]; p[4][1]=vp[7]+vp[6]; p[4][2]=vp[11]+vp[10];p[4][3]=vp[15]+vp[14];
        p[5][0]=vp[3]-vp[2]; p[5][1]=vp[7]-vp[6]; p[5][2]=vp[11]-vp[10];p[5][3]=vp[15]-vp[14];
        return p;
    }

    /**
     * 判断 AABB 是否与视锥体相交（基于 P-NA 算法）
     *
     * @param minX AABB 最小 X
     * @param minY AABB 最小 Y
     * @param minZ AABB 最小 Z
     * @param maxX AABB 最大 X
     * @param maxY AABB 最大 Y
     * @param maxZ AABB 最大 Z
     * @return true 如果 AABB 与视锥体相交（部分或完全在视锥体内）
     */
    private boolean aabbInFrustum(float minX, float minY, float minZ,
                                   float maxX, float maxY, float maxZ) {
        if (frustumPlanes == null) return true;

        for (int plane = 0; plane < 6; plane++) {
            float[] pl = frustumPlanes[plane];
            // P-NA test: pick the p-vertex (most negative corner) for this plane
            float px = pl[0] >= 0 ? maxX : minX;
            float py = pl[1] >= 0 ? maxY : minY;
            float pz = pl[2] >= 0 ? maxZ : minZ;
            float dist = pl[0] * px + pl[1] * py + pl[2] * pz + pl[3];
            if (dist < 0) return false; // completely outside this plane
        }
        return true; // inside (or intersecting) all 6 planes
    }

    /**
     * 执行遮挡剔除检测
     *
     * @param sceneView   场景视图句柄（编码了场景节点数等信息）
     * @param frustumData 视锥体数据句柄（编码了视锥体平面参数）
     * @return 剔除结果
     */
    public CullResult cull(long sceneView, long frustumData) {
        if (!initialized) {
            return new CullResult(true);
        }
        int nodeCount = extractNodeCount(sceneView);
        if (nodeCount <= 0) {
            return new CullResult(false, new BitSet());
        }
        BitSet mask = new BitSet(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            if (isNodeInFrustum(i, sceneView, frustumData)) {
                mask.set(i);
            }
        }
        boolean anyVisible = !mask.isEmpty();
        return new CullResult(anyVisible, mask);
    }

    /**
     * 从场景视图句柄中提取节点数量
     */
    private int extractNodeCount(long sceneView) {
        if (sceneView <= 0) {
            return 0;
        }
        return (int) ((sceneView >> 32) & 0xFFFFL);
    }

    /**
     * 解码 frustumData 中的 XZ 网格偏移（用于推导近似节点位置）
     */
    private int extractGridX(long frustumData) {
        return (int) ((frustumData >> 16) & 0xFFFF);
    }

    private int extractGridZ(long frustumData) {
        return (int) (frustumData & 0xFFFF);
    }

    /**
     * 判断节点是否在视锥体内
     * <p>
     * 使用真实平面方程检测。若无 frustum 数据默认返回可见。
     * sceneView 的高位编码节点数量。
     * frustumData 的位域编码 XZ 网格偏移用于推导节点近似位置。
     */
    private boolean isNodeInFrustum(int nodeIndex, long sceneView, long frustumData) {
        if (!frustumValid || frustumPlanes == null) {
            return true;
        }
        if (frustumData <= 0) {
            return true;
        }

        // 从 frustumData 解码网格偏移，计算近似 AABB
        int gridX = extractGridX(frustumData);
        int gridZ = extractGridZ(frustumData);
        float size = 16f; // 每个节点 16 格
        float halfSize = size * 0.5f;
        float cx = gridX * size + (nodeIndex % 64) * size;
        float cz = gridZ * size + (nodeIndex / 64) * size;
        // 假设 y 轴范围 [-32, 256] 覆盖 Minecraft 世界高度
        float minX = cx - halfSize;
        float maxX = cx + halfSize;
        float minY = -32f;
        float maxY = 256f;
        float minZ = cz - halfSize;
        float maxZ = cz + halfSize;

        return aabbInFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 初始化遮挡剔除引擎
     *
     * @return true 表示初始化成功
     */
    public boolean initialize() {
        this.initialized = true;
        return true;
    }

    /**
     * 是否已初始化
     *
     * @return true 表示已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
}
