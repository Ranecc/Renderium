// Renderium - 渲染器选项页面容器类
// 使用 record 实现不可变的页面定义

package com.renderium.config.structure;

import com.google.common.collect.ImmutableList;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 渲染器选项页面（Record 实现）。
 *
 * <p>表示视频设置菜单中的一个完整配置页面，
 * 采用 Java Record 实现不可变性，保证线程安全。
 *
 * <h2>结构组成</h2>
 * <pre>
 * RendererOptionPage
 * ├── name: Component (页面标题)
 * └── groups: ImmutableList&lt;RendererOptionGroup&gt; (选项分组列表)
 *     ├── Group 1: "基础设置"
 *     │   ├── Option A
 *     │   └── Option B
 *     ├── Group 2: "性能设置"
 *     │   ├── Option C
 *     │   └── Option D
 *     └── Group 3: null (无标题分组，用于零散选项)
 *         └── Option E
 * </pre>
 *
 * <h2>参考实现</h2>
 * <p>参考 Sodium 的 {@code OptionPage} record 设计，
 * 但使用 Renderium 中性命名，移除对 Sodium 特有类型的依赖。
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 使用 Builder 模式创建页面
 * RendererOptionPage generalPage = RendererOptionPage.builder()
 *     .name(Component.translatable("renderium.page.general"))
 *     .group(
 *         RendererOptionGroup.builder()
 *             .name(Component.translatable("renderium.group.basic"))
 *             .option(vsyncOption)
 *             .option(fpsLimitOption)
 *             .build()
 *     )
 *     .group(
 *         RendererOptionGroup.builder()
 *             .option(debugInfoOption)  // 无标题分组
 *             .build()
 *     )
 *     .build();
 * </pre>
 *
 * <h3>不可变性保证</h3>
 * <ul>
 *   <li>Record 字段自动为 final</li>
 *   <li>groups 使用 ImmutableList 保证元素不可变</li>
 *   <li>所有方法均为纯函数，无副作用</li>
 * </ul>
 *
 * @param name   页面显示名称（如"通用设置"、"质量设置"）
 * @param groups 页面包含的选项分组列表（不可变）
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererPage
 * @see RendererOptionGroup
 * @see RendererOption
 */
public record RendererOptionPage(
        Component name,
        ImmutableList<RendererOptionGroup> groups
) implements RendererPage {

    /**
     * 创建新的选项页面。
     *
     * @param name   页面名称，不能为 null
     * @param groups 分组列表，不能为 null（但可以为空列表）
     * @throws NullPointerException 若任何参数为 null
     */
    public RendererOptionPage {
        Objects.requireNonNull(name, "Page name cannot be null");
        Objects.requireNonNull(groups, "Groups list cannot be null");
    }

    /**
     * 将此页面内的所有选项注册到搜索索引。
     *
     * <p>遍历所有分组并委托给各分组的 {@link RendererOptionGroup#registerTextSources} 方法。
     * 这是 {@link RendererPage} 接口的实现方法。
     *
     * @param index      搜索索引实例
     * @param modOptions 模块选项容器（传递给子级注册方法）
     */
    @Override
    public void registerTextSources(SearchIndex index, Object modOptions) {
        for (RendererOptionGroup group : this.groups) {
            group.registerTextSources(index, modOptions);
        }
    }

    /**
     * 获取此页面包含的分组数量。
     *
     * @return 分组数量（≥ 0）
     */
    public int getGroupCount() {
        return this.groups.size();
    }

    /**
     * 获取此页面包含的所有选项的总数。
     *
     * <p>遍历所有分组并累加其选项数量。
     *
     * @return 选项总数（≥ 0）
     */
    public int getTotalOptionCount() {
        int count = 0;
        for (RendererOptionGroup group : this.groups) {
            count += group.getOptionCount();
        }
        return count;
    }

    // ==================== Builder 模式支持 ====================

    /**
     * 创建 RendererOptionPage 的构建器。
     *
     * <p>提供流式 API 用于逐步构建页面结构，
     * 特别适合包含大量选项的复杂页面。
     *
     * @return 新的 Builder 实例
     *
     * <h4>Builder 使用示例</h4>
     * <pre>
     * RendererOptionPage page = RendererOptionPage.builder()
     *     .name(Component.translatable("renderium.page.quality"))
     *     .group(
     *         RendererOptionGroup.builder()
     *             .name(Component.translatable("renderium.group.rendering"))
     *             .option(renderDistanceOpt)
     *             .option(shadowQualityOpt)
     *             .build()
     *     )
     *     .group(RendererOptionGroup.builder().option(fpsOpt).build())
     *     .build();
     * </pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * RendererOptionPage 的构建器。
     *
     * <p>采用流式接口设计，支持链式调用。
     * 内部维护一个可变列表，在 build() 时转换为不可变 ImmutableList。
     */
    public static final class Builder {

        /** 页面名称（必填） */
        private Component name;

        /** 分组列表（可变，build 时转为不可变） */
        private final List<RendererOptionGroup> groups = new ArrayList<>();

        /**
         * 设置页面名称。
         *
         * @param name 页面显示名称
         * @return      this（支持链式调用）
         */
        public Builder name(Component name) {
            this.name = name;
            return this;
        }

        /**
         * 添加一个选项分组。
         *
         * @param group 要添加的分组（不能为 null）
         * @return      this（支持链式调用）
         */
        public Builder group(RendererOptionGroup group) {
            Objects.requireNonNull(group, "Group cannot be null");
            this.groups.add(group);
            return this;
        }

        /**
         * 构建不可变的 RendererOptionPage 实例。
         *
         * @return 新的 RendererOptionPage 实例
         * @throws IllegalStateException 若未设置 name
         */
        public RendererOptionPage build() {
            if (this.name == null) {
                throw new IllegalStateException("Page name is required");
            }
            return new RendererOptionPage(
                this.name,
                ImmutableList.copyOf(this.groups)
            );
        }
    }
}
