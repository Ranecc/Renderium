// Renderium - 动态验证器提供者函数式接口
// 用于根据运行时状态动态生成选项值的验证规则

package com.renderium.config.structure;

import java.util.function.Function;

/**
 * 动态验证器提供者。
 *
 * <p>泛型函数式接口，用于根据运行时配置状态返回适用于当前上下文的验证器实例。
 * 主要用于需要<strong>动态范围</strong>或<strong>条件约束</strong>的选项场景：
 * <ul>
 *   <li><b>范围联动</b>：渲染距离的最大值取决于已分配的内存量</li>
 *   <li><b>模式约束</b>：不同质量预设下允许不同的参数范围</li>
 *   <li><b>硬件限制</b>：根据 GPU 显存大小动态调整纹理分辨率上限</li>
 *   <li><b>版本兼容</b>：某些值仅在特定 Minecraft 版本中有效</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 渲染距离选项：最大值取决于内存分配
 * ValidatorProvider&lt;Range&gt; rangeProvider = state -&gt; {
 *     int memoryMB = state.readIntOption(Identifier.parse("renderium:allocated_memory"));
 *     int maxChunks = Math.min(32, memoryMB / 64);  // 每 64MB 支持一个区块
 *     return new Range(2, maxChunks, 1);  // min=2, max=dynamic, step=1
 * };
 *
 * // 应用到 IntegerOption
 * IntegerOption renderDistance = IntegerOption.builder()
 *     .id(Identifier.parse("renderium:render_distance"))
 *     .name(Component.translatable("renderium.option.render_distance"))
 *     .range(new Range(2, 16, 1))
 *     .validator(rangeProvider)  // 动态覆盖静态范围
 *     .build();
 * </pre>
 *
 * <h3>与静态 Range 的关系</h3>
 * <p>当同时设置了静态 {@code Range} 和动态 {@code ValidatorProvider} 时，
 * 验证流程为：<br>
 * <code>finalValue = validator.get(state).validate(staticRange.clamp(input))</code><br>
 * 即先应用静态范围裁剪，再进行动态验证。
 *
 * @param <T> 验证器类型（通常为 Range 或自定义约束对象）
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see IntegerOption#getValidatedValue()
 * @see Range
 */
@FunctionalInterface
public interface ValidatorProvider<T> {

    /**
     * 根据当前配置状态获取适用的验证器实例。
     *
     * @param state 配置状态上下文，包含所有选项的当前值
     * @return      当前状态下应该使用的验证器对象，不应返回 null
     *
     * <h4>线程安全性要求</h4>
     * <p>此方法可能从多个线程并发调用（UI 线程、渲染线程），
     * 返回的验证器对象应该是不可变的或线程安全的。
     *
     * <h4>性能建议</h4>
     * <ul>
     *   <li>对于简单场景，可缓存并返回同一个不可变验证器实例</li>
     *   <li>仅在依赖的状态真正变化时才创建新实例</li>
     *   <li>避免在方法内部执行重量级计算</li>
     * </ul>
     */
    T getValidator(ConfigState state);
}
