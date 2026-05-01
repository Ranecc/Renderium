// Renderium - 渲染器选项分组容器类
// 使用 record 实现不可变的分组定义

package com.renderium.config.structure;

import com.google.common.collect.ImmutableList;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 渲染器选项分组（Record 实现）。
*
 * <p>将逻辑相关的选项组织在一起，形成 UI 中的视觉分组。
 * 每个分组可以有一个可选的标题（name 为 null 表示无标题分组）。
 *
 * <h2>典型用途</h2>
 * <table border="1">
 *   <tr><th>分组类型</th><th>name 值</th><th>示例</th></tr>
 *   <tr><td>有标题分组</td><td>非 null Component</td><td>"基础设置"、"高级选项"</td></tr>
 *   <tr><td>无标题分组</td><td>null</td><td>零散开关、调试选项</td></tr>
 * </table>
 *
 * <h2>UI 展示效果</h2>
 * <pre>
 * ┌─────────────────────────────────────┐
 * │ ▼ 基础设置                    ← 有标题分组
 * │   ├─ [✓] VSync 开关               │
 * │   ├─ [===|====] 最大帧率: 144     │
 * │   └─ [▼ 全屏模式 ▼]              │
 * │                                     │
 * │   [✓] 显示 FPS 计数器           ← 无标题分组
 * │   [✓] 显示坐标信息                │
 * └─────────────────────────────────────┘
 * </pre>
 *
 * <h2>参考实现</h2>
 * <p>参考 Sodium 的 {@code OptionGroup} record 设计，
 * 使用中性命名并增强文档和 Builder 支持。
 *
 * @param name    分组标题（可为 null，表示不显示标题）
 * @param options 此分组包含的选项列表（不可变）
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererOptionPage
 * @see RendererOption
 */
public record RendererOptionGroup(
        @Nullable Component name,
        ImmutableList<RendererOption> options
) {

    /**
     * 创建新的选项分组。
     *
     * @param name    分组标题（允许为 null）
     * @param options 选项列表（不能为 null，但可以为空列表）
     * @throws NullPointerException 若 options 为 null
     */
    public RendererOptionGroup {
        Objects.requireNonNull(options, "Options list cannot be null");
    }

    /**
     * 将此分组内的所有选项注册到搜索索引。
     *
     * <p>遍历 options 列表中的每个选项，
     * 调用其搜索注册方法（具体注册逻辑由选项类型决定）。
     *
     * @param index      搜索索引实例
     * @param modOptions 模块选项容器（传递给选项级注册方法）
     */
    public void registerTextSources(SearchIndex index, Object modOptions) {
        for (RendererOption option : this.options) {
            if (option instanceof SearchableOption searchable) {
                searchable.registerTextSources(index, modOptions, this);
            }
        }
    }

    /**
     * 获取此分组包含的选项数量。
     *
     * @return 选项数量（≥ 0）
     */
    public int getOptionCount() {
        return this.options.size();
    }

    /**
     * 判断此分组是否有标题。
     *
     * @return true 表示 name 不为 null
     */
    public boolean hasTitle() {
        return this.name != null;
    }

    // ==================== Builder 模式支持 ====================

    /**
     * 创建 RendererOptionGroup 的构建器。
     *
     * @return 新的 Builder 实例
     *
     * <h4>Builder 使用示例</h4>
     * <pre>
     * // 有标题分组
     * RendererOptionGroup basicGroup = RendererOptionGroup.builder()
     *     .name(Component.translatable("renderium.group.basic"))
     *     .option(vsyncOption)
     *     .option(fullscreenOption)
     *     .build();
     *
     * // 无标题分组
     * RendererOptionGroup miscGroup = RendererOptionGroup.builder()
     *     .option(showFpsOption)
     *     .option(showCoordsOption)
     *     .build();
     * </pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * RendererOptionGroup 的构建器。
     *
     * <p>支持可选的 name 设置和多个 option 的添加。
     */
    public static final class Builder {

        /** 分组标题（可选） */
        private Component name;

        /** 选项列表（可变） */
        private final List<RendererOption> options = new ArrayList<>();

        /**
         * 设置分组标题。
         *
         * @param name 分组标题（传 null 表示无标题分组）
         * @return      this（支持链式调用）
         */
        public Builder name(@Nullable Component name) {
            this.name = name;
            return this;
        }

        /**
         * 向分组添加一个选项。
         *
         * @param option 要添加的选项（不能为 null）
         * @return       this（支持链式调用）
         */
        public Builder option(RendererOption option) {
            Objects.requireNonNull(option, "Option cannot be null");
            this.options.add(option);
            return this;
        }

        /**
         * 构建不可变的 RendererOptionGroup 实例。
         *
         * @return 新的 RendererOptionGroup 实例
         */
        public RendererOptionGroup build() {
            return new RendererOptionGroup(
                this.name,
                ImmutableList.copyOf(this.options)
            );
        }
    }
}
