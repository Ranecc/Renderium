package com.ranecc.renderium.feature.pipeline;
import com.ranecc.renderium.feature.pipeline.BfsOcclusionEngine;

/**
 * BFS 遮挡剔除引擎 — 提供 BFS 算法的统一接口和内部类型
 * [TODO] 完整实现待补充
 */
public class BfsOcclusionEngine {

    /** 遮挡剔除结果 */
    public static class CullResult {
        public int[] visibleIndices;
        public long cullTimeNanos;
        public CullResult() {}
    }

    /** 遮挡任务（对应一棵 BFS 树的根节点） */
    public static class OcclusionTask {
        public int taskId;
        public Object bounds;
        public OcclusionTask() {}
    }

    /** 相机视锥体/视图参数 */
    public static class CameraView {
        public float[] viewMatrix;
        public float[] projMatrix;
        public CameraView() {}
    }

    private BfsOcclusionEngine() {}
}
