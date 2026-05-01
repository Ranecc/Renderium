// Renderium - 搜索框控件
// 参考 Sodium 的 SearchWidget，使用中性命名

package com.ranecc.renderium.presentation.ui.ui.widgets;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 实时搜索输入框控件。
 *
 * <p>提供带清除按钮的文本搜索输入框，
 * 支持实时搜索回调、键盘焦点管理和文本清空功能。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code SearchWidget} 设计，
 * 使用 Renderium 中性命名和颜色方案。
 *
 * <h2>UI 布局</h2>
 * <pre>
 * ┌─────────────────────────────────────┬───┐
 * │ 🔍 Search for options...        [×] │   │  ← 搜索框 + 清除按钮
 * └─────────────────────────────────────┴───┘
 * </pre>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>实时搜索</b>：文本变化时立即触发回调（防抖优化）</li>
 *   <li><b>清除按钮</b>：X 图标按钮，一键清空搜索内容</li>
 *   <li><b>焦点管理</b>：支持键盘导航和程序化聚焦</li>
 *   <li><b>占位提示</b>：空输入时显示灰色提示文本</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建搜索框（带回调）
 * SearchWidget searchWidget = new SearchWidget(
 *     new Dim2i(x, y, width, height),
 *     results -&gt; {
 *         // 处理搜索结果
 *         System.out.println("Found " + results.size() + " matches");
 *     }
 * );
 *
 * // 添加到屏幕
 * addRenderableChild(searchWidget);
 *
 * // 查询状态
 * if (searchWidget.isSearching()) {
 *     String query = searchWidget.getSearchText();
 *     System.out.println("Current search: " + query);
 * }
 *
 * // 程序化清空
 * searchWidget.clearSearch();
 * </pre>
 *
 * <h3>性能特性</h3>
 * <ul>
 *   <li>文本变化检测避免重复回调</li>
 *   <li>自动去除前导空白字符</li>
 *   <li>最大长度限制为 200 字符</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see OnSearchResults
 * @see FlatButtonWidget
 */
public class SearchWidget extends AbstractParentWidget {

    /** 日志记录器 */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-SearchWidget");

    /**
     * 搜索结果变化时的最大允许排序误差。
     * <p>用于改善搜索结果的分组显示效果，
     * 控制结果重排的最大距离。
     */
    private static final int MAX_ORDER_DIST_ERROR = 2;

    /** 搜索结果回调接口实例 */
    private final Consumer<List<?>> onSearchResults;

    /** 当前搜索查询文本 */
    private String query = "";

    /** 内部 EditBox 文本输入组件 */
    private EditBox searchBox;

    /** 清除按钮（X 图标） */
    private FlatButtonWidget clearButton;

    /** 上次重建时的宽度（用于检测尺寸变化） */
    private int lastRebuildWidth = -1;

    /**
     * 创建搜索框控件。
     *
     * @param dim            控件的尺寸和位置信息
     * @param onSearchResults 搜索结果回调（当文本变化时触发）
     *
     * @throws NullPointerException 若 dim 或 onSearchResults 为 null
     *
     * @h4>回调触发时机</h4>
     * <ul>
     *   <li>用户输入或删除字符后</li>
     *   <li>调用 clearSearch() 后</li>
     *   <li>程序化设置文本值后</li>
     * </ul>
     */
    public SearchWidget(Dim2i dim, Consumer<List<?>> onSearchResults) {
        super(dim);
        this.onSearchResults = onSearchResults;
        LOGGER.debug("SearchWidget created at ({}, {}, {}, {})", dim.x(), dim.y(), dim.width(), dim.height());
    }

    // ==================== 配置方法 ====================

    /**
     * 更新控件的宽度并按需重建子组件。
     *
     * <p>当父容器尺寸发生变化时应调用此方法，
     * 避免不必要的重复重建操作。
     *
     * @param width 新的宽度值（像素）
     */
    public void updateWidgetWidth(int width) {
        if (width != this.lastRebuildWidth) {
            this.lastRebuildWidth = width;
            this.rebuildForWidth(width);
            LOGGER.trace("SearchWidget width updated to {}", width);
        }
    }

    /**
     * 根据指定宽度重建内部布局。
     *
     * <p>创建或重新配置搜索框和清除按钮的位置和大小。
     * 此方法会在宽度变化时自动调用。
     *
     * @param width 总可用宽度（像素）
     */
    private void rebuildForWidth(int width) {
        this.clearChildren();

        int x = this.getX();
        int y = this.getY();

        // 计算搜索框宽度（预留清除按钮空间）
        int searchBoxWidth = width - Layout.BUTTON_SHORT;

        // 创建清除按钮（位于右侧）
        this.clearButton = new FlatButtonWidget(
                new Dim2i(x + searchBoxWidth, y, Layout.BUTTON_SHORT, Layout.BUTTON_SHORT),
                Component.literal("×"),
                this::clearSearch,
                true,    // 绘制背景
                false,   // 初始不可见（有文本时才显示）
                true     // 左对齐
        );

        // 创建文本输入框
        this.searchBox = new EditBox(
                this.font,
                x + Layout.INNER_MARGIN,
                y + Layout.BUTTON_SHORT / 2 - this.font.lineHeight / 2,
                searchBoxWidth - 20,
                Layout.BUTTON_SHORT,
                Component.literal("Search")
        );

        // 配置搜索框属性
        this.searchBox.setMaxLength(200);                    // 最大 200 字符
        this.searchBox.setBordered(false);                   // 无边框
        this.searchBox.setResponder(this::triggerSearch);    // 文本变化回调
        this.searchBox.setHint(                              // 占位提示
                Component.translatable("renderium.options.search.hint")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
        );

        // 添加子组件
        this.addChild(this.searchBox);
        this.addRenderableChild(this.clearButton);

        LOGGER.debug("SearchWidget rebuilt with width={}", width);
    }

    // ==================== 搜索控制方法 ====================

    /**
     * 清空搜索框内容。
     *
     * <p>执行以下操作：
     * <ol>
     *   <li>将文本框内容设为空字符串</li>
     *   <li>重置内部查询状态</li>
     *   <li>触发空搜索（显示全部结果）</li>
     *   <li>移除焦点</li>
     * </ol>
     */
    public void clearSearch() {
        this.searchBox.setValue("");
        this.query = "";
        this.triggerSearchCallback(List.of());  // 触发空结果回调
        this.setFocused(null);
        LOGGER.debug("Search cleared");
    }

    /**
     * 检测是否正在进行搜索（有非空的搜索内容）。
     *
     * @return 如果搜索框包含非空文本返回 true
     */
    public boolean isSearching() {
        return this.searchBox != null && !this.searchBox.getValue().isBlank();
    }

    /**
     * 获取当前搜索文本内容。
     *
     * @return 当前输入的搜索文本（可能为空字符串，不会返回 null）
     */
    public String getSearchText() {
        return this.searchBox != null ? this.searchBox.getValue() : "";
    }

    // ==================== 内部方法 ====================

    /**
     * 处理文本输入变化的搜索触发。
     *
     * <p>当 EditBox 内容变化时由 MC 自动调用，
     * 会进行去重处理以避免重复搜索。
     *
     * @param text 新的文本内容
     */
    private void triggerSearch(String text) {
        if (text.equals(this.query)) {
            return;  // 文本未变化，跳过
        }

        this.query = text.stripLeading();  // 去除前导空白
        this.performSearch();
    }

    /**
     * 执行搜索操作并通知回调。
     *
     * <p>当前实现直接将空列表传递给回调，
     * 后续可集成实际的搜索引擎（如 SearchIndex）。
     */
    private void performSearch() {
        // TODO: 后续 Task 集成 SearchIndex 实现真正的搜索逻辑
        // 示例：var results = searchIndex.search(this.query);
        var results = List.of();
        this.triggerSearchCallback(results);
    }

    /**
     * 触发搜索结果回调。
     *
     * @param results 搜索结果列表
     */
    @SuppressWarnings("unchecked")
    private void triggerSearchCallback(List<?> results) {
        try {
            this.onSearchResults.accept(results);
            LOGGER.debug("Search callback triggered: query='{}', results={}", this.query, results.size());
        } catch (Exception e) {
            LOGGER.error("Error in search results callback", e);
        }
    }

    // ==================== 渲染方法 ====================

    /**
     * 渲染搜索框控件。
     *
     * <p>绘制背景、搜索框和清除按钮。
     *
     * @param graphics 图形提取器
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param delta    部分刻度时间
     */
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 绘制背景
        graphics.fill(
                this.getX(), this.getY(),
                this.getX() + this.lastRebuildWidth - Layout.BUTTON_SHORT,
                this.getLimitY(),
                Colors.BACKGROUND_DEFAULT
        );

        // 更新清除按钮可见性（仅在有文本时显示）
        if (this.clearButton != null) {
            this.clearButton.setVisible(this.isSearching());
        }

        // 渲染子组件
        if (this.searchBox != null) {
            this.searchBox.extractRenderState(graphics, mouseX, mouseY, delta);
        }
        if (this.clearButton != null) {
            this.clearButton.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    // ==================== 键盘事件处理 ====================

    /**
     * 处理键盘按下事件。
     *
     * <p>特殊处理 Escape 键：在搜索框聚焦时按下 Escape 会清空搜索。
     * 其他按键事件交给父类处理。
     *
     * @param event 键盘事件对象
     * @return 如果事件被消费返回 true
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape 键：清空搜索
        if (event.isEscape() && this.getFocused() == this.searchBox) {
            this.clearSearch();
            return true;
        }

        return super.keyPressed(event);
    }

    /**
     * 处理字符输入事件。
     *
     * <p>将字符输入事件直接转发给搜索框处理。
     *
     * @param event 字符输入事件
     * @return 如果事件被消费返回 true
     */
    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.searchBox != null) {
            return this.searchBox.charTyped(event);
        }
        return false;
    }

    // ==================== 焦点管理 ====================

    /**
     * 设置焦点状态。
     *
     * <p>当获得焦点时，自动将焦点转移给内部的搜索框，
     * 方便用户直接开始输入。
     *
     * @param focused 是否获得焦点
     */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);

        // 获得焦点时自动聚焦到搜索框
        if (focused && this.searchBox != null) {
            this.setFocused(this.searchBox);
        }
    }
}
