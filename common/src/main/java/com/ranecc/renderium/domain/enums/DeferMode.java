package com.ranecc.renderium.domain.enums;

import net.minecraft.network.chat.Component;

/**
 * 区块构建延迟模式枚举
 * <p>
 * 控制区块网格构建任务的调度策略，影响帧时间稳定性和区块可见性。
 * 参考 Sodium 的 {@code DeferMode} 实现，使用中性命名。
 *
 * <h3>模式说明</h3>
 * <dl>
 *   <dt>{@link #ALWAYS}</dt>
 *   <dd>始终延迟到下一帧执行。提供最平滑的帧时间，
 *       但新区块可能需要 1-2 帧才能完全可见。</dd>
 *
 *   <dt>{@link #NEVER}</dt>
 *   <dd>立即在当前帧构建。新区块立即可见，
 *       但可能导致偶发的帧时间峰值。</dd>
 *
 *   <dt>{@link #ON_LOAD}</dt>
 *   <dd>仅在初次加载时延迟，后续更新立即处理。
 *       平衡了加载性能和交互响应性。</dd>
 * </dl>
 *
 * <h3>性能影响</h3>
 * <ul>
 *   <li><b>ALWAYS</b>: 最稳定的帧时间，适合追求流畅体验的场景</li>
 *   <li><b>NEVER</b>: 最快的区块可见性，适合需要即时反馈的场景</li>
 *   <li><b>ON_LOAD</b>: 折中方案，适合大多数使用场景</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在选项注册器中绑定
 * .setBinding(
 *     value -> sodiumOpts.performance.chunkBuildDeferMode = value,
 *     () -> sodiumOpts.performance.chunkBuildDeferMode
 * )
 * }</pre>
 *
 * @author Renderium Team
 * @version 5.0.0
 * @since 5.0.0
 * @see RendererVideoOptionsRegistrar#registerPerformanceOptions(RendererConfigBuilder, Object, Object)
 */
public enum DeferMode {

    /**
     * 始终延迟模式
     * <p>
     * 所有区块构建任务都推迟到下一帧执行。
     * 提供最平滑的帧时间曲线，但新区块有 1-2 帧的显示延迟。
     */
    ALWAYS("renderium.options.defer_chunk_updates.always"),

    /**
     * 从不延迟模式（已废弃，保留向后兼容）
     * <p>
     * 所有区块构建任务立即在当前帧执行。
     * 可能导致帧时间不稳定，但新区块立即可见。
     *
     * @deprecated 使用 {@link #NEVER} 替代
     */
    @Deprecated(since = "5.0", forRemoval = true)
    NEVER("renderium.options.defer_chunk_updates.never"),

    /**
     * 加载时延迟模式
     * <p>
     * 仅在区块首次加载时延迟构建，后续更新（如方块变更）立即处理。
     * 在加载性能和交互响应性之间取得平衡。
     */
    ON_LOAD("renderium.options.defer_chunk_updates.on_load");

    /** 本地化名称组件 */
    private final Component localizedName;

    /**
     * 构造延迟模式枚举值
     *
     * @param translationKey 翻译键（如 "renderium.options.defer_chunk_updates.always"）
     */
    DeferMode(String translationKey) {
        this.localizedName = Component.translatable(translationKey);
    }

    /**
     * 获取本地化的显示名称
     *
     * @return 翻译后的名称组件
     */
    public Component getLocalizedName() {
        return this.localizedName;
    }
}
