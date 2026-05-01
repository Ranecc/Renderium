// Renderium - 选项列表控件
// 参考 Sodium 的 OptionListWidget，使用中性命名

package com.ranecc.renderium.presentation.ui.ui.widgets;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;  // 导入 Minecraft 类（用于反射访问 font 字段）
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 可滚动的选项列表控件。
 *
 * <p>作为设置界面的主内容区域，显示当前选中页面的所有选项，
 * 支持按组分类、搜索过滤、滚动浏览等功能。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code OptionListWidget} 设计，
 * 使用 Renderium 中性命名和简化的数据结构。
 *
 * <h2>UI 布局</h2>
 * <pre>
 * ┌──────────────────────────────────────┐
 * │ ◆ Renderer Settings                  │  ← 页面标题
 * │                                      │
 * │   ▶ Basic Settings                   │  ← 分组标题
 * │     Option A: [Control]              │  ← 选项控件
 * │     Option B: [Control]              │
 * │                                      │
 * │   ▶ Advanced Options                 │  ← 分组标题
 * │     Option C: [Control]              │
 * │     Option D: [Control]              │
 * │                                      │
 * │                              ↑ 滚动条  │
 * └──────────────────────────────────────┘
 * </pre>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>页面展示</b>：显示指定页面的所有选项分组和选项</li>
 *   <li><b>分组显示</b>：按 RendererOptionGroup 分类展示</li>
 *   <li><b>搜索过滤</b>：支持显示搜索结果子集</li>
 *   <li><b>滚动支持</b>：内容超出时自动启用滚动条</li>
 *   <li><b>页面跳转</b>：支持编程式跳转到指定页面位置</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建选项列表（关联父屏幕）
 * OptionListWidget optionList = new OptionListWidget(
 *     parentScreen,
 *     new Dim2i(x, y, width, height),
 *     page -&gt; pageList.switchSelected(page)   // 页面聚焦回调
 * );
 *
 * // 显示指定页面
 * optionList.showPage(generalPage);
 *
 * // 设置搜索过滤结果
 * optionList.setFilteredOptions(searchResults);
 *
 * // 清除过滤器，恢复完整视图
 * optionList.clearFilter();
 *
 * // 跳转到指定页面
 * optionList.jumpToPage(advancedPage);
 * </pre>
 *
 * <h3>性能特性</h3>
 * <ul>
 *   <li>裁剪渲染：只绘制可视区域内的组件</li>
 *   <li>延迟构建：仅在切换页面或清除过滤时重建</li>
 *   <li>轻量级标题：分组/页面标题使用简单文本绘制</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see ControlElement
 * @see AbstractScrollable
 * @see RendererOptionPage
 */
public class OptionListWidget extends AbstractScrollable {

    /** 日志记录器 */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-OptionListWidget");

    /** 默认颜色主题 */
    private static final ColorTheme DEFAULT_THEME = new ColorTheme(Colors.THEME);

    /** 当前过滤后的选项列表（null 表示无过滤） */
    private List<?> filteredOptions = null;

    /** 父屏幕引用（用于创建控件元素） */
    private final AbstractRendererSettingsScreen parent;

    /** 页面聚焦变化回调 */
    private final Consumer<RendererOptionPage> onPageFocused;

    /** 当前正在显示的页面 */
    private RendererOptionPage currentPage = null;

    /** 所有控件元素列表（包括标题和实际控件） */
    private final List<ControlElement> controls = new ArrayList<>();

    /** 上次处理的滚动偏移（用于检测滚动方向） */
    private int lastScrollAmount = 0;

    /** 是否忽略下一次滚动更新（用于程序化跳转） */
    private boolean ignoreNextScrollUpdate = false;

    /** 单个选项项的高度（像素） */
    private int entryHeight;

    /**
     * 创建选项列表控件。
     *
     * @param parent       父屏幕实例（用于访问字体、创建控件等）
     * @param dim          控件的尺寸和位置信息
     * @param onPageFocused 当主内容区滚动到新页面时触发的回调
     *
     * @throws NullPointerException 若 parent 或 dim 为 null
     *
     * @h4>回调说明</h4>
     * <p>当用户滚动导致可视区域内的主要页面发生变化时触发，
     * 用于同步左侧导航栏的高亮状态。
     */
    public OptionListWidget(AbstractRendererSettingsScreen parent, Dim2i dim,
                            Consumer<RendererOptionPage> onPageFocused) {
        super(dim.insetLeft(Layout.OPTION_GROUP_MARGIN));
        this.parent = parent;
        this.onPageFocused = onPageFocused;

        LOGGER.debug("OptionListWidget created at ({}, {}, {}, {})",
                dim.x(), dim.y(), dim.width(), dim.height());
    }

    // ==================== 页面显示控制方法 ====================

    /**
     * 显示指定的选项页面。
     *
     * <p>清空现有内容并重建 UI 以显示目标页面的所有选项。
     * 会自动清除任何活动的搜索过滤。
     *
     * @param page 要显示的选项页面（不能为 null）
     * @throws NullPointerException 若 page 为 null
     */
    public void showPage(RendererOptionPage page) {
        if (page == null) {
            throw new NullPointerException("Page cannot be null");
        }

        this.currentPage = page;
        this.filteredOptions = null;  // 清除过滤

        this.rebuild();
        LOGGER.debug("Showing page: {}", page.name().getString());
    }

    /**
     * 设置过滤后的选项列表。
     *
     * <p>用于显示搜索结果。调用后会重建 UI 以仅显示匹配的选项。
     *
     * @param results 过滤后的选项列表（不能为 null，但可以为空列表）
     * @throws NullPointerException 若 results 为 null
     */
    public void setFilteredOptions(List<?> results) {
        if (results == null) {
            throw new NullPointerException("Filtered options cannot be null");
        }

        this.filteredOptions = results;
        this.rebuild();

        LOGGER.debug("Set filtered options: {} items", results.size());
    }

    /**
     * 清除搜索过滤器。
     *
     * <p>恢复显示当前页面的所有选项。
     * 如果没有当前页面，则显示空列表。
     */
    public void clearFilter() {
        this.filteredOptions = null;
        this.rebuild();

        LOGGER.debug("Filter cleared");
    }

    /**
     * 跳转到指定页面的位置。
     *
     * <p>将滚动条移动到目标页面的起始位置，
     * 使该页面出现在可视区域的顶部。
     *
     * @param page 目标页面
     */
    public void jumpToPage(RendererOptionPage page) {
        if (page == null || this.scrollbar == null) {
            return;
        }

        // TODO: 完善页面位置追踪后实现精确跳转
        // 当前实现：重置到顶部
        this.ignoreNextScrollUpdate = true;
        this.scrollbar.scrollTo(0);

        LOGGER.debug("Jumped to page: {}", page.name().getString());
    }

    /**
     * 获取所有控件元素列表。
     *
     * @return 控件元素的不可变视图（可能为空，不会为 null）
     */
    public List<ControlElement> getControls() {
        return List.copyOf(this.controls);
    }

    // ==================== 内部构建方法 ====================

    /**
     * 重建整个选项列表 UI。
     *
     * <p>根据当前状态（是否有过滤、当前页面等）重新创建所有控件元素。
     * 此操作会清空所有现有的子组件和控件引用。
     */
    private void rebuild() {
        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth() - Layout.OPTION_LIST_SCROLLBAR_OFFSET - Layout.SCROLLBAR_WIDTH;
        int height = this.getHeight();

        // 清空现有内容
        this.clearChildren();
        this.controls.clear();

        // 创建滚动条（位于右侧）
        this.scrollbar = this.addRenderableChild(
                new ScrollbarWidget(
                        new Dim2i(x + width + Layout.OPTION_LIST_SCROLLBAR_OFFSET, y,
                                Layout.SCROLLBAR_WIDTH, height),
                        scrollAmount -> this.onScrollPositionChanged(scrollAmount)
                )
        );

        // 计算单个选项的高度
        this.entryHeight = this.font.lineHeight * 2;

        int listHeight;

        // 根据是否有过滤条件选择不同的渲染策略
        if (this.filteredOptions != null) {
            listHeight = this.renderFilteredOptions(x, y, width);
        } else if (this.currentPage != null) {
            listHeight = this.renderAllOptions(x, y, width);
        } else {
            listHeight = 0;  // 无页面可显示
        }

        // 配置滚动条的上下文
        this.scrollbar.setScrollbarContext(listHeight);

        LOGGER.trace("OptionList rebuilt: height={}, controls={}", listHeight, this.controls.size());
    }

    /**
     * 渲染过滤后的选项列表（搜索结果模式）。
     *
     * @param x 左上角 X 坐标
     * @param y 左上角 Y 坐标
     * @param width 可用宽度
     * @return 总内容高度
     */
    private int renderFilteredOptions(int x, int y, int width) {
        int listHeight = 0;

        // TODO: 后续 Task 集成 SearchIndex 后实现真正的过滤选项渲染
        // 当前实现：显示占位符提示用户搜索功能尚未完成
        Component placeholder = Component.translatable("renderium.search.coming_soon")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

        PlaceholderControlElement placeholderElem = new PlaceholderControlElement(
                new Dim2i(x, y + listHeight, width, this.entryHeight),
                placeholder
        );
        this.addRenderableChild(new ControlElementAdapter(placeholderElem));
        this.controls.add(placeholderElem);
        listHeight += this.entryHeight;

        return listHeight;
    }

    /**
     * 渲染当前页面的所有选项（正常模式）。
     *
     * <p>按分组组织显示：
     * <ol>
     *   <li>页面标题</li>
     *   <li>对于每个分组：</li>
     *   <ol>
     *     <li>分组标题（如果有名称）</li>
     *     <li>分组内的每个选项</li>
     *   </ol>
     * </ol>
     *
     * @param x      左上角 X 坐标
     * @param y      左上角 Y 坐标
     * @param width  可用宽度
     * @return 总内容高度
     */
    private int renderAllOptions(int x, int y, int width) {
        int listHeight = -Layout.OPTION_MOD_MARGIN;

        // 渲染页面标题
        listHeight += Layout.OPTION_PAGE_MARGIN;
        PageHeaderControlElement pageHeader = new PageHeaderControlElement(
                new Dim2i(x, y + listHeight, width, this.entryHeight),
                this.currentPage.name().getString(),
                DEFAULT_THEME
        );
        this.addRenderableChild(new ControlElementAdapter(pageHeader));
        this.controls.add(pageHeader);
        listHeight += this.entryHeight;

        // 渲染每个分组
        for (RendererOptionGroup group : this.currentPage.groups()) {
            // 分组间距
            listHeight += Layout.OPTION_GROUP_MARGIN;

            // 分组标题（如果有名称）
            if (group.name() != null && !group.name().getString().isBlank()) {
                GroupHeaderControlElement groupHeader = new GroupHeaderControlElement(
                        new Dim2i(x, y + listHeight, width, this.entryHeight)
                                .insetLeft(Layout.OPTION_LEFT_INSET),
                        group.name().getString()
                );
                this.addRenderableChild(new ControlElementAdapter(groupHeader));
                this.controls.add(groupHeader);
                listHeight += this.entryHeight;
            }

            // TODO: 后续 Task 实现实际的选项控件渲染
            // 当前实现：显示占位符表示该分组有待实现的选项
            for (var option : group.options()) {
                PlaceholderControlElement placeholder = new PlaceholderControlElement(
                        new Dim2i(x, y + listHeight, width, this.entryHeight)
                                .insetLeft(Layout.OPTION_LEFT_INSET),
                        option.getName()
                );
                this.addRenderableChild(new ControlElementAdapter(placeholder));
                this.controls.add(placeholder);
                listHeight += this.entryHeight;
            }
        }

        return listHeight;
    }

    /**
     * 滚动位置变化回调处理。
     *
     * <p>检测主要可见区域的变化以通知父级同步导航栏状态。
     *
     * @param scrollAmount 新的滚动偏移量
     */
    private void onScrollPositionChanged(int scrollAmount) {
        if (this.ignoreNextScrollUpdate) {
            this.ignoreNextScrollUpdate = false;
            return;
        }

        // TODO: 实现基于位置的页面焦点检测
        // 当前简化实现：始终通知当前页面
        if (this.currentPage != null && this.onPageFocused != null) {
            this.onPageFocused.accept(this.currentPage);
        }
    }

    // ==================== 渲染方法 ====================

    /**
     * 渲染选项列表控件。
     *
     * <p>启用裁剪区域防止内容溢出，然后渲染所有子组件。
     *
     * @param graphics 图形提取器
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param delta    部分刻度时间
     */
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 启用裁剪区域
        graphics.enableScissor(this.getX(), this.getY(), this.getLimitX(), this.getLimitY());

        // 渲染所有子组件
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // 关闭裁剪区域
        graphics.disableScissor();
    }

    // ==================== 内部适配器类 ====================

    /**
     * 将 ControlElement 适配为 AbstractWidget 的包装类。
     *
     * <p>因为 Minecraft 的 GUI 系统要求子组件是特定类型，
     * 所以需要此适配器将 ControlElement 包装为可添加到容器中的形式。
     */
    private class ControlElementAdapter extends AbstractWidget {

        private final ControlElement element;

        ControlElementAdapter(ControlElement element) {
            super(element.getDimensions());
            this.element = element;
        }

        @Override
        public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            this.element.render(graphics, mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            return this.element.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return this.element.isMouseOver((int) mouseX, (int) mouseY);
        }

        @Override
        public boolean isFocused() {
            return this.element.isFocused();
        }

        @Override
        public void setFocused(boolean focused) {
            this.element.setFocused(focused);
        }

        @Override
        public int getY() {
            return super.getY() - getScrollAmount();  // 应用滚动偏移
        }
    }

    // ==================== 占位符控件实现（后续替换为真实控件） ====================

    /**
     * 占位符控件元素（用于尚未实现的选项）。
     *
     * <p>显示选项名称和"即将推出"提示，
     * 后续会被真实的 BooleanControl、SliderControl 等替换。
     */
    private static class PlaceholderControlElement implements ControlElement {

        private final Dim2i dim;
        private final Component label;
        private boolean focused = false;

        PlaceholderControlElement(Dim2i dim, Component label) {
            this.dim = dim;
            this.label = label;
        }

        @Override
        public Dim2i getDimensions() {
            return this.dim;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            boolean hovered = this.isMouseOver(mouseX, mouseY);

            // 绘制悬停背景
            if (hovered) {
                graphics.fill(this.dim.x(), this.dim.y(),
                        this.dim.getLimitX(), this.dim.getLimitY(),
                        Colors.BACKGROUND_HOVER);
            }

            // 绘制标签文本
            int textColor = this.focused ? Colors.THEME : Colors.FOREGROUND;
            try {
                // MC 26.2: GuiGraphicsExtractor 可能没有 text() 方法
                // 使用反射或降级处理
                graphics.fill(
                    this.dim.x() + Layout.TEXT_LEFT_PADDING,
                    this.dim.y() + this.dim.height() / 2 - 4,
                    this.dim.x() + Layout.TEXT_LEFT_PADDING + 100,
                    this.dim.y() + this.dim.height() / 2 + 6,
                    textColor
                );
            } catch (Exception e) {
                LOGGER.trace("Failed to render label text: {}", e.getMessage());
            }
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            return this.isMouseOver((int) event.x(), (int) event.y());
        }

        @Override
        public boolean isMouseOver(int mouseX, int mouseY) {
            return this.dim.containsCursor(mouseX, mouseY);
        }

        @Override
        public boolean isFocused() {
            return this.focused;
        }

        @Override
        public void setFocused(boolean focused) {
            this.focused = focused;
        }
    }

    /**
     * 页面标题控件元素。
     */
    private static class PageHeaderControlElement implements ControlElement {

        private final Dim2i dim;
        private final String title;
        private final ColorTheme theme;
        private boolean focused = false;

        PageHeaderControlElement(Dim2i dim, String title, ColorTheme theme) {
            this.dim = dim;
            this.title = title;
            this.theme = theme;
        }

        @Override
        public Dim2i getDimensions() { return this.dim; }

        @Override
        public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            // 绘制背景
            graphics.fill(this.dim.x(), this.dim.y(),
                    this.dim.getLimitX(), this.dim.getLimitY(),
                    Colors.BACKGROUND_DEFAULT);

            // 绘制标题文本（带图标前缀）- MC 26.2 兼容处理
            try {
                String displayText = "◆ " + this.title;
                // 通过反射获取 font 字段（MC 26.2 API 可能变更）
                Object font = Minecraft.getInstance().getClass().getField("font").get(Minecraft.getInstance());
                // 使用反射调用 graphics.text() 方法
                java.lang.reflect.Method textMethod = graphics.getClass().getMethod("text",
                    font.getClass(), String.class, int.class, int.class, int.class);
                textMethod.invoke(graphics, font, displayText,
                        this.dim.x() + Layout.TEXT_LEFT_PADDING,
                        this.dim.y() + this.dim.height() / 2 - 4,
                        this.theme.theme);
            } catch (Exception e) {
                // text() 方法不可用时使用 fill 绘制占位符
                graphics.fill(
                    this.dim.x() + Layout.TEXT_LEFT_PADDING,
                    this.dim.y() + this.dim.height() / 2 - 4,
                    this.dim.x() + Layout.TEXT_LEFT_PADDING + 80,
                    this.dim.y() + this.dim.height() / 2 + 6,
                    this.theme.theme
                );
            }
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            return false;  // 标题不响应点击
        }

        @Override
        public boolean isMouseOver(int mouseX, int mouseY) {
            return this.dim.containsCursor(mouseX, mouseY);
        }

        @Override
        public boolean isFocused() { return this.focused; }

        @Override
        public void setFocused(boolean focused) { this.focused = focused; }
    }

    /**
     * 分组标题控件元素。
     */
    private static class GroupHeaderControlElement implements ControlElement {

        private final Dim2i dim;
        private final String title;
        private boolean focused = false;

        GroupHeaderControlElement(Dim2i dim, String title) {
            this.dim = dim;
            this.title = title;
        }

        @Override
        public Dim2i getDimensions() { return this.dim; }

        @Override
        public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            // 绘制背景
            graphics.fill(this.dim.x(), this.dim.y(),
                    this.dim.getLimitX(), this.dim.getLimitY(),
                    Colors.BACKGROUND_MEDIUM);

            // 绘制加粗标题文本 - MC 26.2 兼容处理
            try {
                String displayText = ChatFormatting.BOLD + this.title;
                // 通过反射获取 font 字段（MC 26.2 API 可能变更）
                Object font = Minecraft.getInstance().getClass().getField("font").get(Minecraft.getInstance());
                // 使用反射调用 graphics.text() 方法
                java.lang.reflect.Method textMethod = graphics.getClass().getMethod("text",
                    font.getClass(), String.class, int.class, int.class, int.class);
                textMethod.invoke(graphics, font, displayText,
                        this.dim.x() + Layout.TEXT_LEFT_PADDING,
                        this.dim.y() + this.dim.height() / 2 - 4,
                        Colors.FOREGROUND);
            } catch (Exception e) {
                // text() 方法不可用时使用 fill 绘制占位符
                graphics.fill(
                    this.dim.x() + Layout.TEXT_LEFT_PADDING,
                    this.dim.y() + this.dim.height() / 2 - 4,
                    this.dim.x() + Layout.TEXT_LEFT_PADDING + 100,
                    this.dim.y() + this.dim.height() / 2 + 6,
                    Colors.FOREGROUND
                );
            }
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            return false;  // 标题不响应点击
        }

        @Override
        public boolean isMouseOver(int mouseX, int mouseY) {
            return this.dim.containsCursor(mouseX, mouseY);
        }

        @Override
        public boolean isFocused() { return this.focused; }

        @Override
        public void setFocused(boolean focused) { this.focused = focused; }
    }
}
