// Renderium - 渲染器页面接口
// 定义视频设置页面的基本契约

package com.renderium.config.structure;

import net.minecraft.network.chat.Component;

/**
 * 渲染器配置页面接口。
 *
 * <p>代表视频设置菜单中的一个逻辑页面（Tab），
 * 如"通用设置"、"质量设置"、"高级设置"等。
 * 每个页面包含若干个选项分组（{@link RendererOptionGroup}）。
 *
 * <h2>职责划分</h2>
 * <p>RendererPage 主要负责：
 * <ul>
 *   <li><b>页面元数据</b>：提供页面名称和显示标题</li>
 *   <li><b>搜索集成</b>：注册页面内所有选项到搜索索引</li>
 *   <li><b>结构组织</b>：管理页面的分组层级</li>
 * </ul>
 *
 * <h2>典型页面结构</h2>
 * <pre>
 * RendererOptionPage (通用设置)
 * ├── RendererOptionGroup (基础)
 * │   ├── BooleanOption: VSync 开关
 * │   ├── IntegerOption: 最大帧率限制
 * │   └── EnumOption&lt;FullscreenMode&gt;: 全屏模式
 * ├── RendererOptionGroup (性能)
 * │   ├── IntegerOption: 渲染距离
 * │   ├── EnumOption&lt;QualityPreset&gt;: 画质预设
 * │   └── BooleanOption: 平滑帧率
 * └── RendererOptionGroup (null, 无标题)
 *     └── BooleanOption: 显示 FPS
 * </pre>
 *
 * <h2>与 Minecraft 视频设置的集成</h2>
 * <p>RendererPage 设计为可与原版 VideoSettingsScreen 共存，
 * 通过 {@link com.renderium.bridge.video.VideoSettingsProvider} 注入自定义页面。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererOptionPage
 * @see RendererOptionGroup
 * @see RendererOption
 */
public interface RendererPage {

    /**
     * 获取此页面的显示名称。
     *
     * <p>用作设置界面中的 Tab 标题或页面标题。
     * 通常为国际化翻译键，由 Minecraft 翻译系统解析。
     *
     * @return 页面名称 Component，永不为 null
     *
     * <h4>命名建议</h4>
     * <pre>
     * Component.translatable("renderium.page.general")     → "通用"
     * Component.translatable("renderium.page.quality")     → "质量"
     * Component.translatable("renderium.page.advanced")    → "高级"
     * Component.translatable("renderium.page.about")       → "关于"
     * </pre>
     */
    Component getName();

    /**
     * 将此页面内的所有选项注册到搜索索引。
     *
     * <p>遍历所有分组及其包含的选项，将它们添加到全局搜索索引中，
     * 支持用户通过关键词快速定位目标选项。
     *
     * <h3>注册流程</h3>
     * <ol>
     *   <li>遍历 {@link #getGroups()} 中的每个 RendererOptionGroup</li>
     *   <li>对每个分组，遍历其包含的所有 RendererOption</li>
     *   <li>为每个选项创建 TextSource 并注册到 SearchIndex</li>
     *   <li>TextSource 包含选项名称、所属页面、所属分组等信息</li>
     * </ol>
     *
     * @param index      搜索索引实例，用于注册可搜索的文本源
     * @param modOptions 模块选项容器，提供额外的上下文信息
     *
     * @see RendererOption#getName()
     * @see com.renderium.config.search.SearchIndex （后续 Task 实现）
     */
    void registerTextSources(SearchIndex index, Object modOptions);
}
