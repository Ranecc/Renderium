// ============================================================
// AdaptivePrecisionManager - 向后兼容包装类 (Deprecated)
// ============================================================
// ⚠️ 此类已迁移至: com.renderium.core.precision.AdaptivePrecisionManager
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包
//
// 使用方式:
//   旧代码（无需修改）:
//     import com.renderium.core.AdaptivePrecisionManager;
//
//   新代码（推荐迁移）:
//     import com.renderium.core.precision.AdaptivePrecisionManager;
//
// 此类将在未来版本中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

import com.renderium.core.precision.AdaptivePrecisionManager as NewAdaptivePrecisionManager;

/**
 * @deprecated 已迁移至 {@link com.renderium.core.precision.AdaptivePrecisionManager}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 1.0
 * @see com.renderium.core.precision.AdaptivePrecisionManager
 */
@Deprecated(since = "6.0", forRemoval = true)
public class AdaptivePrecisionManager extends NewAdaptivePrecisionManager {

    /**
     * @deprecated 使用 {@code new com.renderium.core.precision.AdaptivePrecisionManager()} 替代
     */
    @Deprecated
    public AdaptivePrecisionManager() {
        super();
    }

    /**
     * @deprecated 使用 {@code new com.renderium.core.precision.AdaptivePrecisionManager(tileSize)} 替代
     */
    @Deprecated
    public AdaptivePrecisionManager(int tileSize) {
        super(tileSize);
    }
}
