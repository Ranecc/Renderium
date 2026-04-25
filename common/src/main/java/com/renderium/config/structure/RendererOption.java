// Renderium - 渲染器选项基础接口
// 定义所有渲染选项的通用契约

package com.renderium.config.structure;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.EnumSet;
import java.util.Set;

/**
 * 渲染器选项接口。
 *
 * <p>Renderium 视频设置系统中所有选项的基础抽象，
 * 定义了选项必须具备的核心能力和生命周期方法。
 *
 * <h2>设计理念</h2>
 * <p>参考 Sodium 的 {@code Option} 抽象设计，采用<strong>接口隔离</strong>原则，
 * 将选项的行为契约与具体实现解耦。每个选项都具备：
 * <ul>
 *   <li><b>唯一标识</b>：通过 {@link Identifier} 进行全局定位</li>
 *   <li><b>元数据</b>：名称、性能影响、特性标记</li>
 *   <li><b>状态管理</b>：启用/禁用判断、重置能力</li>
 *   <li><b>可搜索性</b>：支持文本搜索索引注册</li>
 * </ul>
 *
 * <h2>实现类层次</h2>
 * <pre>
 *                     ┌────────────────┐
 *                     │  RendererOption │ (接口)
 *                     └───────┬────────┘
 *                             │ implements
 *              ┌──────────────┼──────────────┐
 *              ▼              ▼              ▼
 *       ┌──────────┐   ┌──────────┐   ┌──────────┐
 *       │BooleanOpt │   │IntegerOpt│   │ EnumOpt  │
 *       │   ion     │   │   ion    │   │  &lt;T&gt;     │
 *       └──────────┘   └──────────┘   └──────────┘
 * </pre>
 *
 * <h2>线程安全性约定</h2>
 * <p>实现类应保证以下方法的线程安全：
 * <ul>
 *   <li>{@link #getId()} - 始终安全（返回不可变对象）</li>
 *   <li>{@link #getName()} - 始终安全（返回不可变对象）</li>
 *   <li>{@link #getImpact()} - 始终安全（返回枚举常量）</li>
 *   <li>{@link #getFlags()} - 始终安全（返回不可变集合）</li>
 *   <li>{@link #isEnabled(ConfigState)} - 取决于 EnabledProvider 实现</li>
 *   <li>{@link #resetToDefault()} - 需要外部同步保护</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see BooleanOption
 * @see IntegerOption
 * @see EnumOption
 * @see RendererOptionPage
 * @see <a href="https://github.com/CaffeineMC/sodium-fabric">Sodium 参考</a>
 */
public interface RendererOption {

    /**
     * 获取此选项的唯一标识符。
     *
     * <p>标识符采用 Minecraft 标准格式 {@code namespace:path}，
     * 例如 {@code "renderium:render_distance"}。
     * 用于：
     * <ul>
     *   <li>序列化/反序列化时的键名</li>
     *   <li>跨选项依赖查询（ConfigState）</li>
     *   <li>日志记录和调试追踪</li>
     * </ul>
     *
     * @return 不可变的 Identifier 实例，永不为 null
     *
     * <h4>命名规范</h4>
     * <pre>
     * // 正确示例
     * Identifier.parse("renderium:vsync_enabled")
     * Identifier.parse("renderium:quality.preset")
     * Identifier.parse("renderium:advanced.lod_bias")
     *
     * // 错误示例（避免）
     * Identifier.parse("option1")           // 缺少命名空间
     * Identifier.parse("renderium:opt")     // 过于简短
     * </pre>
     */
    Identifier getId();

    /**
     * 获取此选项的显示名称。
     *
     * <p>通常是一个国际化翻译键（如 {@code "renderium.option.vsync"}），
     * 由 Minecraft 的翻译系统解析为用户可见文本。
     * 也支持硬编码的 Component（用于调试或动态生成的选项）。
     *
     * @return 显示名称 Component，永不为 null
     *
     * <h4>翻译键规范</h4>
     * <pre>
     * // en_us.json
     * {
     *   "renderium.option.vsync": "Vertical Synchronization",
     *   "renderium.option.render_distance": "Render Distance"
     * }
     * </pre>
     */
    Component getName();

    /**
     * 获取此选项的性能影响级别。
     *
     * <p>用于在 UI 中向用户展示该选项对帧率的预期影响程度，
     * 帮助用户做出合理的画质/性能权衡决策。
     *
     * @return 性能影响级别枚举，可能为 null（表示未知或未分类）
     *
     * @see OptionImpact
     */
    OptionImpact getImpact();

    /**
     * 获取此选项的特性标记集合。
     *
     * <p>返回一个不可变的标记集合，描述该选项的特殊属性：
     * 是否需要重启、是否为实验性功能、是否仅限高级模式等。
     * 空集合表示该选项无特殊标记。
     *
     * @return 不可变的 EnumSet，永不为 null（但可能为空集）
     *
     * @see OptionFlag
     * @see OptionFlag#REQUIRES_RESTART
     * @see OptionFlag#ADVANCED
     */
    EnumSet<OptionFlag> getFlags();

    /**
     * 判断此选项在给定配置状态下是否应该启用。
     *
     * <p>根据选项间的依赖关系和运行时条件动态决定可用性。
     * 典型应用场景：
     * <ul>
     *   <li>"高级光照"选项仅在"高级模式"开启时可用</li>
     *   <li>"DLSS"选项仅在 NVIDIA GPU 且驱动满足要求时可用</li>
     *   <li>"光线追踪"选项仅在 RTX GPU 上可用</li>
     * </ul>
     *
     * @param state 配置状态上下文，提供查询其他选项值的接口
     * @return      true 表示选项当前可编辑；false 表示应禁用/灰显
     *
     * @see ConfigState
     * @see EnabledProvider
     */
    boolean isEnabled(ConfigState state);

    /**
     * 将此选项重置为其默认值。
     *
     * <p>恢复选项到初始状态，清除所有用户自定义修改。
     * 此操作会影响绑定目标（如果已配置 Binding），
     * 并触发相关的 ApplyHook（如果有）。
     *
     * <h3>线程安全性</h3>
     * <p>此方法可能修改内部状态，调用者应确保在适当的同步上下文中执行。
     * 对于 GUI 操作，通常已在 EDT 中串行化；对于后台任务，需外部加锁。
     *
     * <h3>副作用</h3>
     * <ul>
     *   <li>将内部存储的值重置为 defaultValue</li>
     *   <li>通知观察者值已变更（如 UI 刷新）</li>
     *   <li>如果配置了 ApplyHook，会在下次 apply 时触发</li>
     * </ul>
     *
     * @see BooleanOption#resetToDefault()
     * @see IntegerOption#resetToDefault()
     * @see EnumOption#resetToDefault()
     */
    void resetToDefault();
}
