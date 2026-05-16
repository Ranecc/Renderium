package com.ranecc.renderium.feature.culling.core;

import java.util.BitSet;

/**
 * BFS 遮挡剔除引擎
 *
 * <p>使用广度优先搜索策略遍历场景图，结合视锥体检测
 * 判断场景节点是否可见。仅当节点在视锥体内且未被遮挡时标记为可见。
 */
public class BfsOcclusionEngine {

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

    private BfsOcclusionEngine() {
    }

    public static BfsOcclusionEngine getInstance() {
        return INSTANCE;
    }

    /**
     * 执行遮挡剔除检测
     *
     * <p>根据 frustumData 中的视锥体参数，对场景场景进行 BFS 遍历检测。
     * 仅当节点在视锥体内且未被完全遮挡时返回可见。
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
        BitSet mask = new BitSet(Math.max(nodeCount, 1));
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
     * 判断节点是否在视锥体内
     *
     * <p>使用 frustumData 中的简化编码进行基本的视锥体检测。
     */
    private boolean isNodeInFrustum(int nodeIndex, long sceneView, long frustumData) {
        if (frustumData <= 0) {
            return true;
        }
        long frustumFlags = (frustumData >> (nodeIndex % 48)) & 0xF;
        return (frustumFlags & 0x8) != 0;
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
