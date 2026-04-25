// Renderium - Blaze3D 拦截层系统
// LOD 上下文类 - 封装 LOD 预处理的配置参数

package com.renderium.interception.context;

/**
 * LOD（细节层次）上下文
 * <p>
 * 封装 LOD 预处理阶段所需的所有配置参数，
 * 传递给 {@link PreBlaze3DInterceptor#injectLOD(LODContext)} 方法。
 *
 * <h3>LOD 层级说明：</h3>
 * <pre>
 * 玩家位置
 *     │
 *     ├── [0-24 区块] 近景：完整几何体（NEAR_FULL_DETAIL）
 *     │   └── 完整面剔除、所有光照、所有方块实体
 *     │
 *     ├── [24-32 区块] 中景：简化几何 + 雾效（MID_SIMPLIFIED）
 *     │   └── 降低精度光照、逐渐增加雾密度
 *     │
 *     ├── [32-128 区块] 远景：Billboard / 高度图（FAR_BILLBOARD）
 *     │   └── 预渲染纹理四边形、大气透视效果
 *     │
 *     └── [128+ 区块] 极远：纯色/天空盒融合（HORIZON_FADE）
 *         └── 地平线轮廓、与天空盒无缝混合
 * </pre>
 *
 * @see PreBlaze3DInterceptor#injectLOD(LODContext)
 * @see com.renderium.optimization.lod.RenderiumLODManager
 * @since 5.1.0
 */
public final class LODContext {

    /** 默认最大 LOD 距离（区块数） */
    public static final int DEFAULT_MAX_DISTANCE = 128;

    /** 默认过渡区起点（区块数） */
    public static final int DEFAULT_TRANSITION_START = 24;

    /** 默认过渡区终点（区块数） */
    public static final int DEFAULT_TRANSITION_END = 32;

    /** 默认近景范围（区块数） */
    public static final int DEFAULT_NEAR_RANGE = 24;

    // ==================== 配置字段 ====================

    /** 最大 LOD 距离（区块数，1 区块 = 16 格） */
    private final int maxDistance;

    /** 过渡区起点距离（区块数） */
    private final int transitionStart;

    /** 过渡区终点距离（区块数） */
    private final int transitionEnd;

    /** 是否启用 Billboard 渲染（远景优化） */
    private final boolean billboardEnabled;

    /** 是否启用大气透视效果（远景颜色淡化） */
    private final boolean atmosphericPerspectiveEnabled;

    /** Billboard 最大纹理尺寸（像素） */
    private final int billBoardTextureSize;

    /** 远景水体替代颜色处理开关 */
    private final boolean farWaterHandlingEnabled;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private LODContext(Builder builder) {
        this.maxDistance = builder.maxDistance;
        this.transitionStart = builder.transitionStart;
        this.transitionEnd = builder.transitionEnd;
        this.billboardEnabled = builder.billboardEnabled;
        this.atmosphericPerspectiveEnabled = builder.atmosphericPerspectiveEnabled;
        this.billBoardTextureSize = builder.billBoardTextureSize;
        this.farWaterHandlingEnabled = builder.farWaterHandlingEnabled;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取最大 LOD 距离
     *
     * @return 最大距离（区块数）
     */
    public int getMaxDistance() { return maxDistance; }

    /**
     * 获取过渡区起点
     *
     * @return 过渡区起点（区块数）
     */
    public int getTransitionStart() { return transitionStart; }

    /**
     * 获取过渡区终点
     *
     * @return 过渡区终点（区块数）
     */
    public int getTransitionEnd() { return transitionEnd; }

    /**
     * 检查是否启用 Billboard 渲染
     *
     * @return true 如果 Billboard 已启用
     */
    public boolean isBillboardEnabled() { return billboardEnabled; }

    /**
     * 检查是否启用大气透视效果
     *
     * @return true 如果大气透视已启用
     */
    public boolean isAtmosphericPerspectiveEnabled() { return atmosphericPerspectiveEnabled; }

    /**
     * 获取 Billboard 纹理尺寸
     *
     * @return 纹理尺寸（像素）
     */
    public int getBillBoardTextureSize() { return billBoardTextureSize; }

    /**
     * 检查是否启用远景水体特殊处理
     *
     * @return true 如果远景水体处理已启用
     */
    public boolean isFarWaterHandlingEnabled() { return farWaterHandlingEnabled; }

    // ==================== Builder 模式 ====================

    /**
     * LODContext 构建器
     * <p>
     * 提供灵活的 LOD 配置构造方式，所有参数都有合理的默认值。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * LODContext lodCtx = new LODContext.Builder()
     *     .maxDistance(128)                    // 最大 128 区块
     *     .transitionRange(24, 32)             // 过渡区 24-32 区块
     *     .billboardEnabled(true)              // 启用 Billboard
     *     .atmosphericPerspectiveEnabled(true) // 启用大气透视
     *     .build();
     * </pre>
     */
    public static final class Builder {

        private int maxDistance = DEFAULT_MAX_DISTANCE;
        private int transitionStart = DEFAULT_TRANSITION_START;
        private int transitionEnd = DEFAULT_TRANSITION_END;
        private boolean billboardEnabled = true;
        private boolean atmosphericPerspectiveEnabled = true;
        private int billBoardTextureSize = 256;
        private boolean farWaterHandlingEnabled = true;

        /**
         * 设置最大 LOD 距离
         *
         * @param distance 最大距离（区块数，必须 > 0）
         * @return this（链式调用）
         */
        public Builder maxDistance(int distance) {
            if (distance <= 0) {
                throw new IllegalArgumentException("最大距离必须大于 0: " + distance);
            }
            this.maxDistance = distance;
            return this;
        }

        /**
         * 设置过渡区范围
         *
         * @param start 起点（区块数）
         * @param end   终点（区块数，必须 > start）
         * @return this（链式调用）
         */
        public Builder transitionRange(int start, int end) {
            if (start >= end) {
                throw new IllegalArgumentException("过渡区终点必须大于起点: " + start + " >= " + end);
            }
            this.transitionStart = start;
            this.transitionEnd = end;
            return this;
        }

        /**
         * 设置 Billboard 启用状态
         *
         * @param enabled 是否启用
         * @return this（链式调用）
         */
        public Builder billboardEnabled(boolean enabled) {
            this.billboardEnabled = enabled;
            return this;
        }

        /**
         * 设置大气透视启用状态
         *
         * @param enabled 是否启用
         * @return this（链式调用）
         */
        public Builder atmosphericPerspectiveEnabled(boolean enabled) {
            this.atmosphericPerspectiveEnabled = enabled;
            return this;
        }

        /**
         * 设置 Billboard 纹理尺寸
         *
         * @param size 纹理尺寸（像素，必须为 2 的幂次方且 >= 64）
         * @return this（链式调用）
         */
        public Builder billBoardTextureSize(int size) {
            if (size < 64 || (size & (size - 1)) != 0) {
                throw new IllegalArgumentException("纹理尺寸必须 >= 64 且为 2 的幂次方: " + size);
            }
            this.billBoardTextureSize = size;
            return this;
        }

        /**
         * 设置远景水体处理启用状态
         *
         * @param enabled 是否启用
         * @return this（链式调用）
         */
        public Builder farWaterHandlingEnabled(boolean enabled) {
            this.farWaterHandlingEnabled = enabled;
            return this;
        }

        /**
         * 构建 LODContext 实例
         *
         * @return 不可变的 LODContext 实例
         */
        public LODContext build() {
            return new LODContext(this);
        }
    }
}
