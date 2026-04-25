// ============================================================
// CoarseToFineRenderer - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.render.CoarseToFineRenderer
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (render)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.CoarseToFineRenderer;
//     CoarseToFineRenderer renderer = new CoarseToFineRenderer();
//
//   新代码（推荐迁移）:
//     import com.renderium.core.render.CoarseToFineRenderer;
//     CoarseToFineRenderer renderer = new CoarseToFineRenderer();
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.render.CoarseToFineRenderer as NewCoarseToFineRenderer;

/**
 * 两阶段渲染引擎（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有调用均委托给
 * {@link com.renderium.core.render.CoarseToFineRenderer}。
 * <p>
 * 注意：CoarseToFineRenderer 本身已是研究原型（@Deprecated），此包装类仅用于包路径迁移。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.render.CoarseToFineRenderer}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 3.1.0
 * @see com.renderium.core.render.CoarseToFineRenderer
 */
@Deprecated(since = "6.0", forRemoval = true)
public final class CoarseToFineRenderer {

    /** 委托目标实例（新位置的实现类） */
    private final NewCoarseToFineRenderer delegate;

    // ==================== 常量委托 ====================

    /** 默认Tile大小（像素）（委托） */
    public static final int DEFAULT_TILE_SIZE = NewCoarseToFineRenderer.DEFAULT_TILE_SIZE;

    /** 默认降采样比例（委托） */
    public static final int DEFAULT_DOWNSAMPLE_RATIO = NewCoarseToFineRenderer.DEFAULT_DOWNSAMPLE_RATIO;

    /**
     * @deprecated 使用 {@code new com.renderium.core.render.CoarseToFineRenderer()} 替代
     */
    @Deprecated
    public CoarseToFineRenderer() {
        this.delegate = new NewCoarseToFineRenderer();
    }

    /**
     * @deprecated 使用 {@code new com.renderium.core.render.CoarseToFineRenderer(...)} 替代
     */
    @Deprecated
    public CoarseToFineRenderer(int tileSize, int downsampleRatio) {
        this.delegate = new NewCoarseToFineRenderer(tileSize, downsampleRatio);
    }

    // ==================== 委托公共 API ====================

    /** 查询此渲染器是否可用于生产环境（委托） */
    @Deprecated
    public boolean isAvailable() { return delegate.isAvailable(); }

    /** 执行完整的两阶段渲染流程（委托） */
    @Deprecated
    public NewCoarseToFineRenderer.RenderResult render(float[][] frameData, int width, int height) {
        return delegate.render(frameData, width, height);
    }

    /** 获取平均Phase 1耗时（微秒，委托） */
    @Deprecated
    public double getAveragePhase1TimeUs() { return delegate.getAveragePhase1TimeUs(); }

    /** 获取平均Phase 2耗时（微秒，委托） */
    @Deprecated
    public double getAveragePhase2TimeUs() { return delegate.getAveragePhase2TimeUs(); }

    /** 获取平均计算节省率（百分比，委托） */
    @Deprecated
    public double getAverageSavingsPercent() { return delegate.getAverageSavingsPercent(); }

    /** 获取状态摘要（委托） */
    @Deprecated
    public String getStatusSummary() { return delegate.getStatusSummary(); }
}
