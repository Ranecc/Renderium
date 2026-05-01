// Renderium - 搜索结果回调函数式接口

package com.ranecc.renderium.presentation.ui.ui.widgets;

import java.util.List;

/**
 * 搜索结果回调接口。
 *
 * <p>函数式接口，用于接收搜索框文本变化时的搜索结果通知。
 * 当用户在 SearchWidget 中输入或修改搜索文本时，
 * 会通过此回调将匹配的结果列表传递给调用者。
 *
 * <h2>设计目的</h2>
 * <p>解耦搜索 UI 与搜索逻辑：
 * <ul>
 *   <li>SearchWidget 只负责 UI 展示和文本输入</li>
 *   <li>搜索逻辑由外部实现（如配置管理器、索引服务等）</li>
 *   <li>通过此接口连接两者</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 创建搜索框并注册回调
 * SearchWidget searchWidget = new SearchWidget(
 *     dim,
 *     results -&gt; {
 *         // 处理搜索结果
 *         if (results.isEmpty()) {
 *             optionList.clearFilter();
 *         } else {
 *             optionList.setFilteredOptions(results);
 *         }
 *         optionList.rebuild();
 *     }
 * );
 *
 * // 使用 Lambda 表达式（推荐）
 * OnSearchResults callback = results -&gt; handleResults(results);
 *
 * // 使用方法引用
 * OnSearchResults callback = this::onSearchResultsHandler;
 * </pre>
 *
 * <h3>线程安全说明</h3>
 * <p>此回调在 Minecraft 客户端线程上触发，
 * 无需额外的同步措施。但回调实现应避免耗时操作，
 * 以免阻塞渲染线程。
 *
 * @param <T> 搜索结果的元素类型（通常为选项的名称源对象）
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see SearchWidget
 */
@FunctionalInterface
public interface OnSearchResults<T> {

    /**
     * 处理搜索结果。
     *
     * <p>当搜索框中的文本发生变化时调用，
     * 传入当前匹配的所有结果列表。
     *
     * <h4>调用时机</h4>
     * <ul>
     *   <li>用户输入或删除字符后</li>
     *   <li>程序化设置搜索文本后（通过 setValue 或 clearSearch）</li>
     *   <li>首次打开界面且搜索框有初始值时</li>
     * </ul>
     *
     * <h4>结果列表特性</h4>
     * <ul>
     *   <li>可能为空列表（无匹配结果）</li>
     *   <li>不会为 null</li>
     *   <li>按相关度排序（由搜索逻辑决定）</li>
     *   <li>列表内容在回调期间不应被修改</li>
     * </ul>
     *
     * @param results 搜索结果列表（不可为 null，但可以为空列表）
     *
     * @h4>典型实现模式</h4>
     * <pre>
     * &#64;Override
     * public void onSearchResults(List&lt;?&gt; results) {
     *     if (results.isEmpty()) {
     *         // 显示所有选项
     *         optionList.clearFilter();
     *     } else {
     *         // 仅显示匹配的选项
     *         optionList.setFilteredOptions(results);
     *     }
     *     // 刷新 UI
     *     optionList.rebuild();
     * }
     * </pre>
     */
    void onSearchResults(List<T> results);
}
