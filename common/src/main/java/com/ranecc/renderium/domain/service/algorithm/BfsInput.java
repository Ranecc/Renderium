// 迁移自: snapshot-1.1.0: com\ranecc\renderium\domain\service\algorithm\BfsInput.java
// 迁移目标: com.ranecc.renderium.domain.service.algorithm
// 迁移规则: 优先使用 snapshot 1.1.0 版本，修改 package/import，保持业务逻辑不变

package com.ranecc.renderium.domain.service.algorithm;

import com.ranecc.renderium.domain.model.CameraContext;

/**
 * BFS 遮挡剔除算法输入值对象
 * <p>
 * 封装执行 BFS 可见性计算所需的全部参数。
 * 该类为不可变设计，通过构造函数一次性设置所有字段。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>nodeCount: 场景节点总数</li>
 *   <li>neighbors: 节点邻接表（压缩格式）</li>
 *   <li>cameraX/Y/Z: 相机位置坐标</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * BfsInput input = new BfsInput.Builder()
 *     .nodeCount(1024)
 *     .neighbors(neighborData)
 *     .cameraPosition(cameraCtx.x, cameraCtx.y, cameraCtx.z)
 *     .build();
 *
 * VisibilityResult result = strategy.execute(input);
 * }</pre>
 */
public final class BfsInput {

    /** 场景节点总数 */
    public final int nodeCount;

    /** 节点邻接表数据（压缩的一维数组） */
    public final int[] neighbors;

    /** 相机 X 坐标 */
    public final float cameraX;

    /** 相机 Y 坐标 */
    public final float cameraY;

    /** 相机 Z 坐标 */
    public final float cameraZ;

    /** 最大搜索深度 */
    public final int maxDepth;

    /**
     * 私有构造函数（通过 Builder 创建）
     */
    private BfsInput(Builder builder) {
        this.nodeCount = builder.nodeCount;
        this.neighbors = builder.neighbors;
        this.cameraX = builder.cameraX;
        this.cameraY = builder.cameraY;
        this.cameraZ = builder.cameraZ;
        this.maxDepth = builder.maxDepth;
    }

    /**
     * 从 CameraContext 创建 BfsInput 的便捷工厂方法
     *
     * @param camera CameraContext - 相机上下文
     * @return Builder 预填充相机位置的 Builder 实例
     */
    public static Builder fromCamera(CameraContext camera) {
        return new Builder()
            .cameraPosition(camera.x, camera.y, camera.z);
    }

    /**
     * BfsInput 构建器
     * <p>
     * 支持流式 API 设置各字段，最后调用 build() 创建不可变实例。
     */
    public static final class Builder {
        private int nodeCount = 0;
        private int[] neighbors = null;
        private float cameraX = 0.0f;
        private float cameraY = 0.0f;
        private float cameraZ = 0.0f;
        private int maxDepth = 64;

        public Builder nodeCount(int count) { this.nodeCount = count; return this; }

        public Builder neighbors(int[] data) { this.neighbors = data; return this; }

        public Builder cameraPosition(float x, float y, float z) {
            this.cameraX = x; this.cameraY = y; this.cameraZ = z; return this;
        }

        public Builder maxDepth(int depth) { this.maxDepth = depth; return this; }

        /**
         * 构建 BfsInput 实例
         *
         * @return 新的 BfsInput 不可变实例
         * @throws IllegalStateException 若缺少必要字段
         */
        public BfsInput build() {
            if (nodeCount <= 0) throw new IllegalStateException("nodeCount 必须大于 0");
            if (neighbors == null) throw new IllegalStateException("neighbors 不能为 null");
            if (maxDepth <= 0) throw new IllegalStateException("maxDepth 必须大于 0");
            return new BfsInput(this);
        }
    }
}
