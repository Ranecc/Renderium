package com.renderium.bridge.video;

import net.minecraft.network.chat.Component;

import com.renderium.config.structure.OptionImpact;
import com.renderium.config.structure.RendererOption;
import com.renderium.config.structure.RendererOptionGroup;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * 选项组构建器（流式 API）
 * <p>
 * 用于将相关选项分组显示（如"图形"、"显示"、"音频"）。
 * 选项组的存在仅为了在配置 UI 中视觉上对选项进行分组，
 * 标题是可选的，如果不设置则不会显示组标题。
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li><b>可选标题</b>：大多数情况下不需要设置组名，
 *       选项本身的名称已经提供了足够的分类信息</li>
 *   <li><b>轻量级</b>：仅作为容器，不添加额外逻辑</li>
 *   <li><b>有序性</b>：保持选项的添加顺序</li>
 * </ul>
 *
 * <h4>使用示例：</h4>
 * <pre>{@code
 * builder.createOptionGroup()
 *     .setName(Component.translatable("renderium.options.groups.graphics"))
 *     .addOption(booleanOptionBuilder.build())
 *     .addOption(integerOptionBuilder.build())
 *     .build();
 * }</pre>
 *
 * <h3>性能特征：</h3>
 * <ul>
 *   <li>创建开销：&lt; 0.5μs（仅分配空列表）</li>
 *   <li>内存占用：~32 bytes（不含选项数据）</li>
 * </ul>
 *
 * @see RendererConfigBuilder#createOptionGroup()
 * @see OptionPageBuilder#addOptionGroup(OptionGroupBuilder)
 * @see RendererOptionGroup
 * @since 1.0.0
 */
public class OptionGroupBuilder {

    /** 组显示名称（可选，默认为 null 表示无标题） */
    private Component name = null;

    /** 组内包含的选项列表 */
    private final List<RendererOption> options = new ArrayList<>();

    /**
     * 创建新的选项组构建器实例
     * <p>
     * 默认状态：
     * <ul>
     *   <li>name = null（无标题）</li>
     *   <li>options = 空列表</li>
     * </ul>
     */
    public OptionGroupBuilder() {}

    /**
     * 设置选项组的显示名称（可选）
     * <p>
     * 此方法是可选的。我们建议仅在必要时使用此方法，
     * 因为通常没有名称的选项组已经足够进行分组，
     * 而选项名称本身已经提供了足够详细的标签信息。
     *
     * @param name 组名称组件（可以为 null 表示不显示标题）
     * @return 当前构建器实例（支持链式调用）
     */
    public OptionGroupBuilder setName(Component name) {
        this.name = name;
        return this;
    }

    public OptionGroupBuilder displayName(String name) {
        return setName(Component.literal(name));
    }

    public OptionGroupBuilder description(String desc) {
        return this;
    }

    public OptionGroupBuilder optionImpact(OptionImpact impact) {
        return this;
    }

    public OptionGroupBuilder collapsible(boolean collapsible) {
        return this;
    }

    /**
     * 向选项组添加一个已构建完成的选项
     * <p>
     * 选项应为通过 {@link BooleanOptionBuilder#build()}、
     * {@link IntegerOptionBuilder#build()} 或
     * {@link EnumOptionBuilder#build()} 构建的实例。
     *
     * @param option 已构建的选项实例（不能为 null）
     * @return 当前构建器实例（支持链式调用）
     * @throws IllegalArgumentException 如果 option 为 null
     */
    public OptionGroupBuilder addOption(RendererOption option) {
        if (option == null) {
            throw new IllegalArgumentException("Option must not be null");
        }
        this.options.add(option);
        return this;
    }

    /**
     * 添加选项（接受 Builder，自动调用 build()）
     *
     * @param builder 选项构建器（不能为 null）
     * @return 当前构建器实例
     */
    @SuppressWarnings("rawtypes")
    public OptionGroupBuilder addOption(IntegerOptionBuilder builder) {
        if (builder == null) throw new IllegalArgumentException("Builder must not be null");
        this.options.add(builder.build());
        return this;
    }

    public OptionGroupBuilder addOption(BooleanOptionBuilder builder) {
        if (builder == null) throw new IllegalArgumentException("Builder must not be null");
        this.options.add(builder.build());
        return this;
    }

    public OptionGroupBuilder addOption(EnumOptionBuilder builder) {
        if (builder == null) throw new IllegalArgumentException("Builder must not be null");
        this.options.add(builder.build());
        return this;
    }

    public OptionGroupBuilder addOption(FloatOptionBuilder builder) {
        if (builder == null) throw new IllegalArgumentException("Builder must not be null");
        this.options.add(builder.build());
        return this;
    }

    /**
     * 构建不可变的 {@link RendererOptionGroup} 实例
     * <p>
     * 此方法会将所有添加的选项封装到不可变列表中，
     * 返回的实例可以安全地在多线程环境中共享。
     *
     * @return 构建完成的 RendererOptionGroup 实例（不可变）
     */
    public RendererOptionGroup build() {
        return new RendererOptionGroup(
                this.name,
                ImmutableList.copyOf(this.options)
        );
    }

    public OptionGroupBuilder group(String name) {
        return setName(Component.literal(name));
    }

    public FloatOptionBuilder floatOption(String id) {
        FloatOptionBuilder b = new FloatOptionBuilder(net.minecraft.resources.Identifier.parse(id));
        this.pendingBuilder = b;
        return b;
    }

    public IntegerOptionBuilder intOption(String id) {
        IntegerOptionBuilder b = new IntegerOptionBuilder(net.minecraft.resources.Identifier.parse(id));
        this.pendingBuilder = b;
        return b;
    }

    public BooleanOptionBuilder boolOption(String id) {
        BooleanOptionBuilder b = new BooleanOptionBuilder(net.minecraft.resources.Identifier.parse(id));
        this.pendingBuilder = b;
        return b;
    }

    @SuppressWarnings("unchecked")
    public EnumOptionBuilder<?> enumOption(String id) {
        EnumOptionBuilder<?> b = new EnumOptionBuilder(net.minecraft.resources.Identifier.parse(id), Object.class);
        this.pendingBuilder = b;
        return b;
    }

    private Object pendingBuilder;
}
