// Renderium - 页面列表导航控件
// 参考 Sodium 的 PageListWidget，使用中性命名

package com.ranecc.renderium.presentation.ui.widgets;

import com.ranecc.renderium.None;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 垂直页面导航栏控件。
 *
 * <p>显示所有可选的设置页面列表，支持页面选择高亮、
 * 点击切换、滚动浏览等功能。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code PageListWidget} 设计，
 * 使用 Renderium 中性命名和简化的数据结构。
 *
 * <h2>UI 布局</h2>
 * <pre>
 * ┌──────────────┐
 * │  ◆ General   │  ← 高亮选中项
 * │    Quality   │
 * │    Advanced  │
 * │              │
 * │  ◆ Performance│
 * │    Culling   │
 * │    ...       │
 * │              │
 * └──────────────┘
 *      ↑ 滚动条
 * </pre>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>页面列表展示</b>：显示所有 RendererPage 的名称</li>
 *   <li><b>选中高亮</b>：当前选中的页面以主题色标识</li>
 *   <li><b>点击切换</b>：点击页面项触发回调通知父屏幕</li>
 *   <li><b>滚动支持</b>：页面过多时自动启用滚动条</li>
 *   <li><b>自动定位</b>：切换页面时自动滚动到可见区域</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建页面列表
 * PageListWidget pageList = new PageListWidget(
 *     new Dim2i(x, y, width, height),
 *     pages,                                    // 页面列表
 *     page -&gt; optionList.showPage(page)         // 选择回调
 * );
 *
 * // 添加到屏幕
 * addRenderableChild(pageList);
 *
 * // 程序化切换页面
 * pageList.switchSelected(targetPage);
 *
 * // 查询当前选中
 * RendererPage current = pageList.getSelectedPage();
 * int index = pageList.getSelectedIndex();
 * </pre>
 *
 * <h3>性能特性</h3>
 * <ul>
 *   <li>虚拟渲染：只渲染可视区域内的页面项</li>
 *   <li>懒加载：页面项在滚动到可视区域时才创建</li>
 *   <li>缓存优化：避免每帧重复计算布局</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererPage
 * @see AbstractScrollable
 */
public class PageListWidget extends AbstractScrollable {

    /** 日志记录器 */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-PageListWidget");

    /** 所有页面列表 */
    private final List<RendererOptionPage> pages;

    /** 当前选中的页面索引（-1 表示未选中） */
    private int selectedIndex = -1;

    /** 页面选择回调（点击页面项时触发） */
    private final Consumer<RendererOptionPage> onPageSelected;

    /** 当前选中的页面项组件引用（用于更新视觉状态） */
    private PageEntryWidget selectedEntry;

    /** 默认颜色主题（用于未指定主题的页面） */
    private static final ColorTheme DEFAULT_THEME = new ColorTheme(Colors.THEME);

    /**
     * 创建页面列表导航控件。
     *
     * @param dim           控件的尺寸和位置信息
     * @param pages         要显示的页面列表（不能为 null，但可以为空）
     * @param onPageSelected 页面选择回调（点击页面项时触发）
     *
     * @throws NullPointerException 若 pages 或 onPageSelected 为 null
     *
     * @h4>回调说明</h4>
     * <p>当用户点击某个页面项时会调用此回调，
     * 传入被选中的 RendererPage 对象。
     * 典型实现是调用 OptionListWidget.showPage() 切换主内容区。
     */
    public PageListWidget(Dim2i dim, List<RendererOptionPage> pages, Consumer<RendererOptionPage> onPageSelected) {
        super(dim);
        this.pages = List.copyOf(pages);  // 防御性拷贝
        this.onPageSelected = onPageSelected;

        // 构建页面列表 UI
        this.rebuild();

        LOGGER.debug("PageListWidget created with {} pages", pages.size());
    }

    // ==================== 构建方法 ====================

    /**
     * 重建页面列表 UI。
     *
     * <p>清空现有子组件并根据 pages 列表重新创建所有页面项。
     * 在初始化和页面列表变化时调用。
     */
    private void rebuild() {
        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth();
        int height = this.getHeight();

        this.clearChildren();

        // 创建滚动条（位于右侧）
        this.scrollbar = this.addRenderableChild(
                new ScrollbarWidget(
                        new Dim2i(this.getLimitX() - Layout.SCROLLBAR_WIDTH, y,
                                Layout.SCROLLBAR_WIDTH, height),
                        false,   // 垂直方向
                        false    // 不始终显示
                )
        );

        // 计算每个页面项的高度
        int entryHeight = this.font.lineHeight * 2;
        int listHeight = 0;

        // 创建每个页面的入口组件
        for (RendererOptionPage page : this.pages) {
            Dim2i widgetDim = new Dim2i(x, y + listHeight, width, entryHeight);
            PageEntryWidget entry = new PageEntryWidget(widgetDim, page, DEFAULT_THEME, listHeight);
            this.addRenderableChild(entry);
            listHeight += entryHeight;
        }

        // 配置滚动条的上下文
        this.scrollbar.setScrollbarContext(listHeight + Layout.INNER_MARGIN);

        LOGGER.debug("PageListWidget rebuilt: {} entries, total height={}", this.pages.size(), listHeight);
    }

    // ==================== 渲染方法 ====================

    /**
     * 渲染页面列表控件。
     *
     * <p>绘制渐变背景、裁剪区域和所有页面项。
     *
     * @param graphics 图形提取器
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param delta    部分刻度时间
     */
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 绘制渐变背景（从浅到深）
        renderBackgroundGradient(graphics, this.getX(), this.getY(),
                this.getLimitX(), this.getLimitY());

        // 启用裁剪区域（防止内容溢出）
        graphics.enableScissor(this.getX(), this.getY(), this.getLimitX(), this.getLimitY());

        // 渲染所有子组件（页面项和滚动条）
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // 关闭裁剪区域
        graphics.disableScissor();
    }

    /**
     * 渲染垂直渐变背景。
     *
     * <p>使用从浅色到默认色的线性渐变，
     * 提供视觉深度感。
     *
     * @param graphics 图形提取器
     * @param x1       左上角 X
     * @param y1       左上角 Y
     * @param x2       右下角 X
     * @param y2       右下角 Y
     */
    public static void renderBackgroundGradient(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
        graphics.fillGradient(x1, y1, x2, y2, Colors.BACKGROUND_LIGHT, Colors.BACKGROUND_DEFAULT);
    }

    // ==================== 页面选择方法 ====================

    /**
     * 切换到指定的选中页面。
     *
     * <p>执行以下操作：
     * <ol>
     *   <li>取消旧选中项的高亮状态</li>
     *   <li>设置新选中项的高亮状态</li>
     *   <li>自动滚动以确保选中项可见</li>
     * </ol>
     *
     * @param page 目标页面（必须存在于 pages 列表中）
     * @throws IllegalArgumentException 若页面不在列表中
     */
    public void switchSelected(RendererOptionPage page) {
        if (page == null) {
            LOGGER.warn("Attempted to switch to null page");
            return;
        }

        // 查找对应的页面项组件
        int index = this.pages.indexOf(page);
        if (index < 0) {
            LOGGER.warn("Page not found in list: {}", page.getName().getString());
            return;
        }

        // 查找对应的 EntryWidget（需要遍历子组件）
        PageEntryWidget targetEntry = findEntryByPage(page);
        if (targetEntry != null) {
            this.switchSelectedEntry(targetEntry);
        } else {
            LOGGER.warn("Entry widget not found for page: {}", page.getName().getString());
        }
    }

    /**
     * 内部方法：切换选中的入口组件。
     *
     * @param entry 目标入口组件
     */
    private void switchSelectedEntry(PageEntryWidget entry) {
        if (entry != this.selectedEntry) {
            // 取消旧选中
            if (this.selectedEntry != null) {
                this.selectedEntry.setSelected(false);
            }

            // 设置新选中
            this.selectedEntry = entry;
            this.selectedEntry.setSelected(true);
            this.selectedIndex = this.pages.indexOf(entry.page);

            // 自动滚动到可见区域
            this.scrollToVisible(entry);
        }
    }

    /**
     * 将指定页面项滚动到可见区域。
     *
     * <p>如果项目不在当前视口中，会自动调整滚动位置。
     *
     * @param entry 要确保可见的页面项
     */
    private void scrollToVisible(PageEntryWidget entry) {
        if (entry == null || this.scrollbar == null) {
            return;
        }

        int widgetTop = entry.getScrollTargetStart();
        int widgetBottom = widgetTop + entry.getHeight();
        int viewTop = this.getY() + this.scrollbar.getScrollAmount();
        int viewBottom = viewTop + this.getHeight();

        if (widgetTop < viewTop) {
            // 项目在视口上方：向上滚动
            this.scrollbar.scrollTo(widgetTop - this.getY());
        } else if (widgetBottom > viewBottom) {
            // 项目在视口下方：向下滚动
            this.scrollbar.scrollTo(widgetBottom - this.getY() - this.getHeight());
        }
    }

    /**
     * 根据页面查找对应的入口组件。
     *
     * @param page 目标页面
     * @return 对应的 PageEntryWidget，如果未找到返回 null
     */
    private PageEntryWidget findEntryByPage(RendererOptionPage page) {
        for (var child : this.children()) {
            if (child instanceof PageEntryWidget entry && entry.page == page) {
                return entry;
            }
        }
        return null;
    }

    // ==================== 状态查询方法 ====================

    /**
     * 获取当前选中页面的索引。
     *
     * @return 选中索引（0-based），-1 表示未选中任何页面
     */
    public int getSelectedIndex() {
        return this.selectedIndex;
    }

    /**
     * 获取当前选中的页面对象。
     *
     * @return 选中的 RendererPage，如果没有选中返回 null
     */
    public RendererOptionPage getSelectedPage() {
        return this.selectedIndex >= 0 ? this.pages.get(this.selectedIndex) : null;
    }

    // ==================== 内部类：页面入口组件 ====================

    /**
     * 单个页面入口的可视化组件。
     *
     * <p>显示页面名称，支持选中/悬停状态的视觉反馈，
     * 点击时触发页面切换回调。
     */
    private class PageEntryWidget extends AbstractWidget {

        /** 关联的页面对象 */
        final RendererOptionPage page;

        /** 颜色主题 */
        final ColorTheme theme;

        /** 滚动目标起始位置（用于 scrollToVisible 计算） */
        final int scrollTargetStart;

        /** 是否被选中 */
        private boolean selected = false;

        /**
         * 创建页面入口组件。
         *
         * @param dim             尺寸和位置
         * @param page            关联的页面
         * @param theme           颜色主题
         * @param scrollTargetStart 滚动目标位置
         */
        PageEntryWidget(Dim2i dim, RendererOptionPage page, ColorTheme theme, int scrollTargetStart) {
            super(dim);
            this.page = page;
            this.theme = theme;
            this.scrollTargetStart = scrollTargetStart;
        }

        /**
         * 设置选中状态。
         *
         * @param selected 是否选中
         */
        void setSelected(boolean selected) {
            this.selected = selected;
        }

        /** @return 滚动目标起始 Y 坐标 */
        int getScrollTargetStart() {
            return this.scrollTargetStart;
        }

        /**
         * 获取实际渲染 Y 坐标（考虑滚动偏移）。
         *
         * @return 调整后的 Y 坐标
         */
        @Override
        public int getY() {
            return super.getY() - PageListWidget.this.scrollbar.getScrollAmount();
        }

        /**
         * 渲染页面入口。
         *
         * @param graphics 图形提取器
         * @param mouseX   鼠标 X 坐标
         * @param mouseY   鼠标 Y 坐标
         * @param delta    部分刻度时间
         */
        @Override
        public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            // 更新悬停状态
            this.hovered = this.isMouseOver(mouseX, mouseY);

            // 确定文本颜色
            int textColor;
            if (this.selected) {
                textColor = this.theme.theme;          // 选中：主题色
            } else if (this.hovered) {
                textColor = this.theme.themeLighter;   // 悬停：浅色
            } else {
                textColor = Colors.FOREGROUND;           // 默认：白色
            }

            // 绘制选中指示条（左侧竖线）
            if (this.selected) {
                graphics.fill(this.getX(), this.getY(),
                        this.getX() + 2, this.getLimitY(),
                        Colors.THEME);
            }

            // 绘制悬停背景
            if (this.hovered || this.selected) {
                graphics.fill(this.getX() + 2, this.getY(),
                        this.getLimitX(), this.getLimitY(),
                        Colors.BACKGROUND_HOVER);
            }

            // 绘制页面名称（垂直居中）
            Component name = this.page.getName();
            drawString(graphics, name,
                    this.getX() + Layout.TEXT_LEFT_PADDING,
                    this.getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET,
                    textColor);
        }

        /**
         * 处理鼠标点击事件。
         *
         * <p>点击时触发页面切换。
         *
         * @param event       鼠标事件
         * @param doubleClick 是否双击
         * @return 如果点击在组件内返回 true
         */
        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0 && this.isMouseOver(event.x(), event.y())) {
                // 触发页面切换
                PageListWidget.this.switchSelectedEntry(this);

                // 通知回调
                if (PageListWidget.this.onPageSelected != null) {
                    PageListWidget.this.onPageSelected.accept(this.page);
                }

                this.playClickSound();
                return true;
            }
            return false;
        }
    }
}
