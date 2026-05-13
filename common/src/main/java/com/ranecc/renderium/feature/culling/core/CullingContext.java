package com.ranecc.renderium.feature.culling.core;
import com.ranecc.renderium.feature.culling.core.CullingContext;
import com.ranecc.renderium.feature.culling.core.CullingContext;

/**
 * 剔除上下文 — 剔除系统的配置和状态容器
 *
 * <p>封装剔除系统所需的所有运行时配置，包括：
 * <ul>
 *   <li>相机参数（位置、方向、FOV）</li>
 *   <li>剔除距离设置</li>
 *   <li>性能预算限制</li>
 *   <li>调试标志</li>
 * </ul></p>
 *
 * @author Renderium Team
 * @version 1.0
 * @since 3.0.0
 */
public class CullingContext {

    /** 最大剔除距离（方块单位） */
    private final int maxCullDistance;

    /** 是否启用视锥体剔除 */
    private final boolean frustumCullingEnabled;

    /** 是否启用遮挡剔除 */
    private final boolean occlusionCullingEnabled;

    /**
     * 构建剔除上下文
     *
     * @param maxCullDistance 最大剔除距离（必须 > 0）
     * @param frustumCullingEnabled 是否启用视锥体剔除
     * @param occlusionCullingEnabled 是否启用遮挡剔除
     */
    public CullingContext(int maxCullDistance, boolean frustumCullingEnabled, boolean occlusionCullingEnabled) {
        if (maxCullDistance <= 0) {
            throw new IllegalArgumentException("最大剔除距离必须大于 0: " + maxCullDistance);
        }
        this.maxCullDistance = maxCullDistance;
        this.frustumCullingEnabled = frustumCullingEnabled;
        this.occlusionCullingEnabled = occlusionCullingEnabled;
    }

    /**
     * 获取最大剔除距离
     * @return 剔除距离（方块单位）
     */
    public int getMaxCullDistance() {
        return maxCullDistance;
    }

    /**
     * 是否启用视锥体剔除
     * @return true 表示已启用
     */
    public boolean isFrustumCullingEnabled() {
        return frustumCullingEnabled;
    }

    /**
     * 是否启用遮挡剔除
     * @return true 表示已启用
     */
    public boolean isOcclusionCullingEnabled() {
        return occlusionCullingEnabled;
    }

    @Override
    public String toString() {
        return String.format("CullingContext{distance=%d, frustum=%b, occlusion=%b}",
            maxCullDistance, frustumCullingEnabled, occlusionCullingEnabled);
    }
}
