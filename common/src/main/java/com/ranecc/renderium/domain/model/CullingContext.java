// Renderium - Blaze3D 拦截层系统
// 剔除上下文类 - 封装剔除优化的配置参数

package com.ranecc.renderium.domain.model;

/**
 * 剔除上下文
 * <p>
 * 封装剔除优化阶段所需的所有配置参数，
 * 传递给 {@link PreBlaze3DInterceptor#injectCulling(CullingContext)} 方法。
 *
 * <h3>支持的剔除类型：</h3>
 * <ul>
 *   <li><b>视锥体剔除</b>（Frustum Culling）：移除视野外的几何体</li>
 *   <li><b>遮挡剔除</b>（Occlusion Culling）：移除被遮挡的几何体</li>
 *   <li><b>背面剔除</b>（Backface Culling）：移除背对相机的面</li>
 *   <li><b>相邻面剔除</b>（Neighbor Face Culling）：移除被相邻方块遮挡的面</li>
 * </ul>
 *
 * @see PreBlaze3DInterceptor#injectCulling(CullingContext)
 * @see com.ranecc.renderium.culling.CullingController
 * @since 5.1.0
 */
public final class CullingContext {

    /** 默认最大可视距离（区块数） */
    public static final int DEFAULT_MAX_DRAW_DISTANCE = 32;

    // ==================== 配置字段 ====================

    /** 是否启用视锥体剔除 */
    private final boolean frustumCullingEnabled;

    /** 是否启用遮挡剔除（Hi-Z Occlusion） */
    private final boolean occlusionCullingEnabled;

    /** 是否启用背面剔除 */
    private final boolean backfaceCullingEnabled;

    /** 是否启用相邻面剔除 */
    private final boolean neighborFaceCullingEnabled;

    /** 最大可视距离（区块数） */
    private final int maxDrawDistance;

    /** Hi-Z Mipmap 级别（用于遮挡剔除） */
    private final int hizMipmapLevels;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private CullingContext(Builder builder) {
        this.frustumCullingEnabled = builder.frustumCullingEnabled;
        this.occlusionCullingEnabled = builder.occlusionCullingEnabled;
        this.backfaceCullingEnabled = builder.backfaceCullingEnabled;
        this.neighborFaceCullingEnabled = builder.neighborFaceCullingEnabled;
        this.maxDrawDistance = builder.maxDrawDistance;
        this.hizMipmapLevels = builder.hizMipmapLevels;
    }

    // ==================== Getter 方法 ====================

    /**
     * 检查是否启用视锥体剔除
     *
     * @return true 如果视锥体剔除已启用
     */
    public boolean isFrustumCullingEnabled() { return frustumCullingEnabled; }

    /**
     * 检查是否启用遮挡剔除
     *
     * @return true 如果遮挡剔除已启用
     */
    public boolean isOcclusionCullingEnabled() { return occlusionCullingEnabled; }

    /**
     * 检查是否启用背面剔除
     *
     * @return true 如果背面剔除已启用
     */
    public boolean isBackfaceCullingEnabled() { return backfaceCullingEnabled; }

    /**
     * 检查是否启用相邻面剔除
     *
     * @return true 如果相邻面剔除已启用
     */
    public boolean isNeighborFaceCullingEnabled() { return neighborFaceCullingEnabled; }

    /**
     * 获取最大可视距离
     *
     * @return 最大距离（区块数）
     */
    public int getMaxDrawDistance() { return maxDrawDistance; }

    /**
     * 获取 Hi-Z Mipmap 级别
     *
     * @return Mipmap 级别数（通常 4-8）
     */
    public int getHizMipmapLevels() { return hizMipmapLevels; }

    // ==================== Builder 模式 ====================

    /**
     * CullingContext 构建器
     * <p>
     * 提供灵活的剔除配置构造方式，所有参数都有合理的默认值。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * CullingContext cullCtx = new CullingContext.Builder()
     *     .frustumCullingEnabled(true)       // 启用视锥体剔除
     *     .occlusionCullingEnabled(true)      // 启用遮挡剔除
     *     .backfaceCullingEnabled(true)       // 启用背面剔除
     *     .neighborFaceCullingEnabled(true)   // 启用相邻面剔除
     *     .maxDrawDistance(32)               // 最大 32 区块
     *     .hizMipmapLevels(6)                // Hi-Z 6 级 Mipmap
     *     .build();
     * </pre>
     */
    public static final class Builder {

        private boolean frustumCullingEnabled = true;
        private boolean occlusionCullingEnabled = true;
        private boolean backfaceCullingEnabled = true;
        private boolean neighborFaceCullingEnabled = true;
        private int maxDrawDistance = DEFAULT_MAX_DRAW_DISTANCE;
        private int hizMipmapLevels = 6;

        /**
         * 设置视锥体剔除启用状态
         *
         * @param enabled 是否启用
         * @return this（链式调用）
         */
        public Builder frustumCullingEnabled(boolean enabled) {
            this.frustumCullingEnabled = enabled;
            return this;
        }

        /**
         * 设置遮挡剔除启用状态
         *
         * @param enabled 是否启用
         * @return this（链式调用）
         */
        public Builder occlusionCullingEnabled(boolean enabled) {
            this.occlusionCullingEnabled = enabled;
            return this;
        }

        /**
         * 设置背面剔除启用状态
         *
         * @param enabled 是否启用
         * @return this（链式调用）
         */
        public Builder backfaceCullingEnabled(boolean enabled) {
            this.backfaceCullingEnabled = enabled;
            return this;
        }

        /**
         * 设置相邻面剔除启用状态
         *
         * @param enabled 是否启用
         * @return this（链式调用）
         */
        public Builder neighborFaceCullingEnabled(boolean enabled) {
            this.neighborFaceCullingEnabled = enabled;
            return this;
        }

        /**
         * 设置最大可视距离
         *
         * @param distance 最大距离（区块数，必须 > 0）
         * @return this（链式调用）
         */
        public Builder maxDrawDistance(int distance) {
            if (distance <= 0) {
                throw new IllegalArgumentException("最大可视距离必须大于 0: " + distance);
            }
            this.maxDrawDistance = distance;
            return this;
        }

        /**
         * 设置 Hi-Z Mipmap 级别
         *
         * @param levels Mipmap 级别数（通常 4-8）
         * @return this（链式调用）
         */
        public Builder hizMipmapLevels(int levels) {
            if (levels < 1 || levels > 16) {
                throw new IllegalArgumentException("Hi-Z Mipmap 级别必须在 1-16 范围内: " + levels);
            }
            this.hizMipmapLevels = levels;
            return this;
        }

        /**
         * 构建 CullingContext 实例
         *
         * @return 不可变的 CullingContext 实例
         */
        public CullingContext build() {
            return new CullingContext(this);
        }
    }
}
