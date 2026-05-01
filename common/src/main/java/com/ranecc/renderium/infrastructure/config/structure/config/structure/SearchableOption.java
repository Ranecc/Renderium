// Renderium - 可搜索选项标记接口
// 用于支持选项的文本搜索索引注册

package com.renderium.config.structure;

/**
 * 可搜索选项标记接口。
 *
 * <p>由需要支持文本搜索功能的 {@link RendererOption} 实现类实现。
 * 提供将自身注册到 {@link SearchIndex} 的能力。
 *
 * <p>此接口的存在使得 {@link RendererOptionGroup#registerTextSources}
 * 可以进行类型安全的分发，避免反射或 instanceof 检查的滥用。
 *
 * @author Renderium Team
 * @since 5.0.0
 */
interface SearchableOption extends RendererOption {

    /**
     * 获取选项唯一标识符
     *
     * @return 选项 ID
     */
    default net.minecraft.resources.Identifier id() {
        return getId();
    }

    /**
     * 将此选项注册到搜索索引。
     *
     * @param index       搜索索引实例
     * @param modOptions   模块选项容器
     * @param optionGroup  所属的选项分组
     */
    void registerTextSources(SearchIndex index, Object modOptions, RendererOptionGroup optionGroup);
}
