// Renderium - 配置状态读取上下文接口
// 提供选项之间的依赖查询能力，用于动态启用/禁用逻辑

package com.renderium.config.structure;

import net.minecraft.resources.Identifier;

/**
 * 配置状态读取上下文。
 *
 * <p>提供在运行时查询其他选项值的能力，主要用于：
 * <ul>
 *   <li>动态判断选项是否可用（EnabledProvider）</li>
 *   <li>根据其他选项值动态调整验证范围（ValidatorProvider）</li>
 *   <li>选项间的依赖关系解析</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 判断高级选项是否可用
 * boolean advancedMode = state.readBoolOption(Identifier.parse("renderium:advanced_mode"));
 *
 * // 读取枚举选项值
 * QualityPreset preset = state.readEnumOption(
 *     Identifier.parse("renderium:quality_preset"),
 *     QualityPreset.class
 * );
 * </pre>
 *
 * <h3>线程安全性</h3>
 * <p>实现类应保证线程安全，因为配置可能在渲染线程和工作线程间共享访问。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see EnabledProvider
 * @see ValidatorProvider
 */
public interface ConfigState {

    /**
     * 读取枚举类型选项的当前值。
     *
     * @param <T>   枚举类型，必须继承自 Enum&lt;T&gt;
     * @param id    选项唯一标识符（如 "renderium:quality_level"）
     * @param type  枚举类型的 Class 对象，用于反序列化
     * @return      该选项当前的枚举值，若不存在则返回该枚举的默认值
     * @throws IllegalArgumentException 若 id 为 null 或 type 不匹配
     *
     * <h4>示例用法</h4>
     * <pre>
     * AntiAliasingMode mode = state.readEnumOption(
     *     Identifier.parse("renderium:anti_aliasing"),
     *     AntiAliasingMode.class
     * );
     * </pre>
     */
    <T extends Enum<T>> T readEnumOption(Identifier id, Class<T> type);

    /**
     * 读取整数类型选项的当前值。
     *
     * @param id  选项唯一标识符（如 "renderium:render_distance"）
     * @return    该选项当前的整数值，若不存在则返回默认值 0
     * @throws IllegalArgumentException 若 id 为 null
     *
     * <h4>示例用法</h4>
     * <pre>
     * int distance = state.readIntOption(
     *     Identifier.parse("renderium:render_distance")
     * );
     * </pre>
     */
    int readIntOption(Identifier id);

    /**
     * 读取布尔类型选项的当前值。
     *
     * @param id  选项唯一标识符（如 "renderium:vsync_enabled"）
     * @return    该选项当前的布尔值，若不存在则返回 false
     * @throws IllegalArgumentException 若 id 为 null
     *
     * <h4>示例用法</h4>
     * <pre>
     * boolean vsync = state.readBoolOption(
     *     Identifier.parse("renderium:vsync_enabled")
     * );
     * </pre>
     */
    boolean readBoolOption(Identifier id);
}
