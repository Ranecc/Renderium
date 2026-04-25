// Renderium - 选项启用状态提供者函数式接口
// 用于动态控制选项的可用性

package com.renderium.config.structure;

/**
 * 选项启用状态提供者。
 *
 * <p>函数式接口，用于根据运行时配置状态动态决定某个选项是否可用。
 * 主要应用于以下场景：
 * <ul>
 *   <li><b>条件依赖</b>：仅当父选项开启时才显示子选项</li>
 *   <li><b>互斥约束</b>：某些选项组合不能同时启用</li>
 *   <li><b>硬件检测</b>：根据 GPU 能力动态禁用不支持的特性</li>
 *   <li><b>模式切换</b>：在不同性能预设下显示/隐藏不同选项</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 仅在高级模式下启用复杂光照选项
 * EnabledProvider provider = state -&gt;
 *     state.readBoolOption(Identifier.parse("renderium:advanced_mode"));
 *
 * // GPU 不支持时禁用光线追踪
 * EnabledProvider rtProvider = state -&gt; {
 *     boolean rtSupported = GPUCapabilities.supportsRayTracing();
 *     return rtSupported &amp;&amp;
 *         state.readBoolOption(Identifier.parse("renderium:rt_enabled"));
 * };
 * </pre>
 *
 * <h3>性能注意事项</h3>
 * <ul>
 *   <li>此方法会在 UI 渲染时频繁调用，应避免重量级操作</li>
 *   <li>不要在此方法中进行 I/O 操作或网络请求</li>
 *   <li>建议缓存计算结果，避免重复查询相同依赖</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see BooleanOption#isEnabled(ConfigState)
 * @see IntegerOption#isEnabled(ConfigState)
 * @see EnumOption#isEnabled(ConfigState)
 */
@FunctionalInterface
public interface EnabledProvider {

    /**
     * 根据给定的配置状态判断选项是否应该启用。
     *
     * @param state 配置状态上下文，可用于查询其他选项的值
     * @return      true 表示选项可用且可编辑；false 表示选项应被禁用/灰显
     *
     * <h4>实现指南</h4>
     * <ul>
     *   <li>始终返回 true 的常量提供者：{@code state -> true}</li>
     *   <li>基于单一依赖：{@code state -> state.readBoolOption(depId)}</li>
     *   <li>复合条件：使用 &amp;&amp;、|| 组合多个查询</li>
     * </ul>
     */
    boolean isEnabled(ConfigState state);
}
