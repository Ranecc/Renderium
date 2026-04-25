package com.renderium.config;

import net.minecraft.network.chat.Component;

/**
 * 四边形分割模式枚举
 * <p>
 * 控制透明几何体渲染时的四边形分割策略，影响排序精度和视觉正确性。
 * 用于优化半透明方块的渲染顺序，解决深度排序问题。
 * 参考 Sodium 的 {@code QuadSplittingMode} 实现，使用中性命名和简化设计。
 *
 * <h3>技术背景</h3>
 * <p>
 * 当多个半透明方块相邻时，GPU 需要按从后到前的顺序渲染才能正确显示。
 * 四边形分割将复杂的多边形拆分为更简单的三角形/四边形集合，
 * 以实现正确的深度排序。
 *
 * <h3>模式说明</h3>
 * <dl>
 *   <dt>{@link #SAFE}</dt>
 *   <dd>安全模式。保证所有情况下都正确，但几何量可能增加最多 2 倍。
 *       不会有任何视觉错误。</dd>
 *
 *   <dt>{@link #FAST}</dt>
 *   <dd>快速模式。允许适度的几何扩展（最多 4 倍），
 *       绝大多数情况正确，极端角度可能有轻微伪影。</dd>
 *
 *   <dt>{@link #FASTEST}</dt>
 *   <dd>最快模式。不限制几何扩展量，性能最优，
 *       但在某些复杂场景可能出现排序错误。</dd>
 * </dl>
 *
 * <h3>性能对比</h3>
 * <table border="1">
 *   <tr><th>模式</th><th>几何扩展上限</th><th>GPU 开销</th><th>正确性</th></tr>
 *   <tr><td>SAFE</td><td>2x</td><td>中等</td><td>100%</td></tr>
 *   <tr><td>FAST</td><td>4x</td><td>较低</td><td>&gt;99%</td></tr>
 *   <tr><td>FASTEST</td><td>∞</td><td>最低</td><td>&gt;95%</td></tr>
 * </table>
 *
 * <h3>使用建议</h3>
 * <ul>
 *   <li>默认使用 {@link #SAFE}，除非遇到性能问题</li>
 *   <li>如果 GPU 是瓶颈且可接受轻微错误，尝试 {@link #FAST}</li>
 *   <li>仅在对性能极其敏感的场景使用 {@link #FASTEST}</li>
 * </ul>
 *
 * <h3>调试用途</h3>
 * <p>
 * 此选项默认隐藏，仅在开发模式下启用（通过
 * {@code renderium.debug.terrainSortingEnabled} 控制）。
 *
 * @author Renderium Team
 * @version 5.0.0
 * @since 5.0.0
 * @see RendererVideoOptionsRegistrar#registerPerformanceOptions(RendererConfigBuilder, Object, Object)
 */
public enum QuadSplittingMode {

    /**
     * 安全模式
     * <p>
     * 几何扩展限制为 2 倍。保证所有透明场景的渲染正确性。
     * 适用于对视觉质量要求高的场景。
     */
    SAFE("renderium.options.quad_splitting.safe", 2.0f, true),

    /**
     * 快速模式
     * <p>
     * 几何扩展限制为 4 倍。在绝大多数情况下保持正确渲染，
     * 仅在极端的复杂透明结构中可能出现轻微排序问题。
     * 推荐作为性能敏感场景的首选。
     */
    FAST("renderium.options.quad_splitting.fast", 4.0f, true),

    /**
     * 极速模式
     * <p>
     * 不限制几何扩展量。提供最佳性能，但在某些复杂场景
     * （如大型玻璃建筑、多层水面）可能出现可见的排序错误。
     * 仅建议在确实存在 GPU 瓶颈时使用。
     */
    FASTEST("renderium.options.quad_splitting.fastest", Float.POSITIVE_INFINITY, false);

    /** 本地化名称组件 */
    private final Component localizedName;

    /**
     * 几何扩展因子上限
     * <p>
     * 最终生成的几何面数与输入面数的比值上限。
     * 例如 2.0 表示最多扩展为原来的 2 倍。
     */
    private final float maxAmplificationFactor;

    /**
     * 是否量化触发法线
     * <p>
     * 启用后会对分割面的法线进行量化处理，
     * 减少因浮点精度导致的 z-fighting 问题。
     */
    private final boolean quantizeTriggerNormals;

    /**
     * 构造四边形分割模式枚举值
     *
     * @param translationKey         翻译键
     * @param maxAmplificationFactor  最大几何扩展因子（≥1.0 或 POSITIVE_INFINITY）
     * @param quantizeTriggerNormals  是否量化触发法线
     */
    QuadSplittingMode(String translationKey, float maxAmplificationFactor, boolean quantizeTriggerNormals) {
        this.localizedName = Component.translatable(translationKey);
        this.maxAmplificationFactor = maxAmplificationFactor;
        this.quantizeTriggerNormals = quantizeTriggerNormals;
    }

    /**
     * 获取本地化的显示名称
     *
     * @return 翻译后的名称组件
     */
    public Component getLocalizedName() {
        return this.localizedName;
    }

    /**
     * 获取最大几何扩展因子
     * <p>
     * 此值用于限制四边形分割算法的输出规模，
     * 防止简单输入产生过量的几何数据。
     *
     * @return 扩展因子（≥1.0），{@link Float#POSITIVE_INFINITY} 表示无限制
     */
    public float getMaxAmplificationFactor() {
        return this.maxAmplificationFactor;
    }

    /**
     * 是否启用法线量化
     * <p>
     * 法线量化可以减少浮点精度问题导致的渲染伪影，
     * 但会略微降低法线精度。
     *
     * @return true 启用量化，false 保持原始精度
     */
    public boolean isQuantizeTriggerNormals() {
        return this.quantizeTriggerNormals;
    }

    /**
     * 计算给定基础四边形数量下的最大允许总四边形数
     *
     * @param baseQuadCount 基础四边形数量（输入）
     * @return               最大允许的四边形数量（输出）
     */
    public int getMaxTotalQuads(int baseQuadCount) {
        if (Float.isInfinite(this.maxAmplificationFactor)) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(baseQuadCount * this.maxAmplificationFactor);
    }
}
