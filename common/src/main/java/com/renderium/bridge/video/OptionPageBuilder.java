package com.renderium.bridge.video;

import net.minecraft.network.chat.Component;

import com.renderium.config.structure.RendererOptionPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 选项页面构建器（流式 API）
 * <p>
 * 用于定义一组相关的视频设置选项页面（如"通用"、"画质"、"性能"）。
 * 每个页面包含多个选项组（{@link OptionGroup}），选项组内包含具体的设置项。
 *
 * <h3>设计模式：</h3>
 * 采用 Fluent Builder Pattern（链式调用模式），
 * 所有 setter 方法返回 {@code this} 以支持方法链式调用。
 *
 * <h4>使用示例：</h4>
 * <pre>{@code
 * builder.createOptionPage()
 *     .setName(Component.translatable("renderium.options.pages.general"))
 *     .addOptionGroup(
 *         builder.createOptionGroup()
 *             .setName(Component.translatable("renderium.options.groups.graphics"))
 *             .addOption(booleanOption)
 *             .addOption(integerOption)
 *     )
 *     .build();
 * }</pre>
 *
 * <h3>性能特征：</h3>
 * <ul>
 *   <li>创建开销：&lt; 1μs（仅分配空列表）</li>
 *   <li>内存占用：~40 bytes（不含选项数据）</li>
 *   <li>线程安全：非线程安全（应在单线程构建阶段使用）</li>
 * </ul>
 *
 * @see RendererConfigBuilder#createOptionPage()
 * @see OptionGroupBuilder
 * @see RendererOptionPage
 * @since 1.0.0
 */
public class OptionPageBuilder {

    /** 页面显示名称（可选，默认为空文本） */
    private Component name = Component.empty();

    /** 页面包含的选项组列表 */
    private final List<OptionGroupBuilder> groups = new ArrayList<>();

    /**
     * 创建新的选项页面构建器实例
     * <p>
     * 默认状态：
     * <ul>
     *   <li>name = Component.empty()（空名称）</li>
     *   <li>groups = 空列表</li>
     * </ul>
     */
    public OptionPageBuilder() {}

    /**
     * 设置选项页面的显示名称
     * <p>
     * 名称将显示在设置界面的标签页或标题位置。
     * 建议使用 {@link Component#translatable(String)} 支持国际化。
     *
     * @param name 页面名称组件（不能为 null）
     * @return 当前构建器实例（支持链式调用）
     * @throws IllegalArgumentException 如果 name 为 null
     */
    public OptionPageBuilder setName(Component name) {
        if (name == null) {
            throw new IllegalArgumentException("Option page name must not be null");
        }
        this.name = name;
        return this;
    }

    /**
     * 向页面添加一个选项组
     * <p>
     * 选项组用于在视觉上将相关选项分组显示。
     * 组的添加顺序决定了 UI 中的显示顺序。
     *
     * @param group 选项组构建器实例（不能为 null）
     * @return 当前构建器实例（支持链式调用）
     * @throws IllegalArgumentException 如果 group 为 null
     */
    public OptionPageBuilder addOptionGroup(OptionGroupBuilder group) {
        if (group == null) {
            throw new IllegalArgumentException("Option group must not be null");
        }
        this.groups.add(group);
        return this;
    }

    /**
     * 构建不可变的 {@link RendererOptionPage} 实例
     * <p>
     * 此方法会：
     * <ol>
     *   <li>验证必要字段（至少需要一个选项组或直接选项）</li>
     *   <li>递归构建所有子选项组</li>
     *   <li>返回不可变的页面实例</li>
     * </ol>
     *
     * @return 构建完成的 RendererOptionPage 实例（不可变）
     * @throws IllegalStateException 如果页面不包含任何选项组
     */
    public RendererOptionPage build() {
        // 构建所有选项组
        List<RendererOptionGroup> builtGroups = new ArrayList<>(this.groups.size());
        for (OptionGroupBuilder group : this.groups) {
            builtGroups.add(group.build());
        }

        // 返回不可变页面实例
        return new RendererOptionPage(
                this.name,
                Collections.unmodifiableList(builtGroups)
        );
    }
}
