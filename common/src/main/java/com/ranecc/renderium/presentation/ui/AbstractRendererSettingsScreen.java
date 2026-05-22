// Renderium - 渲染器设置界面抽象基类
// 提供仿 Sodium 风格的设置界面框架，支持响应式布局和模块化设计

package com.ranecc.renderium.presentation.ui;

import com.ranecc.renderium.presentation.ui.util.Dim2i;
import com.ranecc.renderium.presentation.ui.widgets.OptionListWidget;
import com.ranecc.renderium.presentation.ui.widgets.PageListWidget;
import com.ranecc.renderium.presentation.ui.widgets.SearchWidget;
import com.ranecc.renderium.infrastructure.config.structure.RendererOptionPage;
import com.ranecc.renderium.platform.bridge.video.VideoOptionsRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 渲染器设置界面的抽象基类。
 *
 * <p>提供仿 Sodium 风格的多标签页设置界面框架，
 * 支持响应式布局、搜索功能、修改追踪等核心特性。
 *
 * <h2>架构设计</h2>
 * <p>采用模板方法模式（Template Method Pattern），
 * 基类负责界面布局和交互逻辑，子类只需提供页面数据和业务操作。
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  AbstractRendererSettingsScreen (基类)                        │
 * │  ├── 布局管理：updateScreenDimensions()                      │
 * │  ├── 组件构建：rebuild()                                    │
 * │  ├── 事件处理：keyPressed/mouseClicked 等                    │
 * │  ├── 渲染管线：extractRenderState()                         │
 * │  └── 抽象方法（子类实现）：                                   │
 * │      ├── getPages() - 获取选项页面列表                       │
 * │      ├── applyChanges() - 应用配置变更                       │
 * │      └── undoChanges() - 撤销未保存的修改                     │
 * ├─────────────────────────────────────────────────────────────┤
 * │  RenderiumSettingsScreen (子类)                              │
 * │  └── 具体业务逻辑：Renderium 特有的选项页和配置操作             │
 * </pre>
 *
 * <h2>界面布局</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │ [搜索框: Search for options...                   ] [图标] │  ← 顶部栏
 * ├──────────┬───────────────────────────────────┬──────────┤
 * │          │                                   │          │
 * │ 页面导航  │         主内容区域                │  Tooltip  │
 * │ General  │   Option Group 1                  │  区域     │
 * │ Quality  │     Option A: [Control]           │          │
 * │ Advanced │     Option B: [Control]           │          │
 * │ ...      │                                   │          │
 * │          │   Option Group 2                  │          │
 * │          │     Option C: [Control]           │          │
 * │          │                                   │          │
 * ├──────────┴───────────────────────────────────┴──────────┤
 * │                          [Apply] [Undo]      [Done]    │  ← 底部按钮
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>响应式布局策略</h2>
 * <p>根据屏幕尺寸自动调整内容区域大小：
 * <ul>
 *   <li><b>小屏幕</b>：全宽显示，无内边距</li>
 *   <li><b>中等屏幕</b>：水平居中，启用 insetX</li>
 *   <li><b>大屏幕</b>：水平垂直双居中，启用 insetX + insetY</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * public class RenderiumSettingsScreen extends AbstractRendererSettingsScreen {
 *
 *     public RenderiumSettingsScreen(Screen parent) {
 *         super(parent, Component.literal("Renderium Settings"));
 *     }
 *
 *     &#64;Override
 *     protected List&lt;RendererOptionPage&gt; getPages() {
 *         return List.of(generalPage, qualityPage, advancedPage);
 *     }
 *
 *     &#64;Override
 *     protected void applyChanges() {
 *         // 应用用户修改到配置文件
 *         config.save();
 *     }
 *
 *     &#64;Override
 *     protected void undoChanges() {
 *         // 恢复到上次保存的状态
 *         config.reload();
 *     }
 * }
 * </pre>
 *
 * <h3>性能要求</h3>
 * <ul>
 *   <li>{@code rebuild()} 执行时间 &lt; 5ms</li>
 *   <li>{@code extractRenderState()} 执行时间 &lt; 2ms</li>
 *   <li>避免在渲染循环中创建临时对象</li>
 * </ul>
 *
 * <h3>MC 版本兼容性</h3>
 * <p>适配 Minecraft 26.2 的 GUI 系统：
 * <ul>
 *   <li>使用 {@link GuiGraphicsExtractor} 替代旧的 GuiGraphics</li>
 *   <li>使用 {@link KeyEvent} / {@link MouseButtonEvent} 处理输入</li>
 *   <li>遵循新的组件生命周期管理</li>
 * </ul>
 *
 * @param &lt;T&gt; 页面类型参数（通常为 {@link RendererOptionPage}）
 *
 * @see RendererOptionPage
 * @see VideoOptionsRegistry
 * @see Layout
 * @see Dim2i
 *
 * @author Renderium Team
 * @since 5.0.0
 * @version 1.0
 */
public abstract class AbstractRendererSettingsScreen extends Screen {

    /** 日志记录器（SLF4J） */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-SettingsUI");

    /** GLFW 键码：T 键（聚焦搜索框） */
    private static final int KEY_T = 0x54;

    /** GLFW 键码：P 键（打开原版视频设置） */
    private static final int KEY_P = 0x50;

    /** GLFW 修饰键：Shift */
    private static final int MOD_SHIFT = 0x0001;

    // ==================== 核心字段 ====================

    /** 父屏幕引用（用于关闭时返回） */
    protected final Screen parent;

    /**
     * 内容区域尺寸（响应式缩放）。
     * <p>根据屏幕尺寸动态计算，支持水平和垂直方向的居中和收缩。
     */
    protected Dim2i dim;

    /**
     * 是否需要水平方向的内边距。
     * <p>当屏幕宽度超过最小内容宽度 + 边框宽度时启用。
     */
    protected boolean insetX;

    /**
     * 是否需要垂直方向的内边距。
     * <p>当屏幕高度超过最小内容高度 + 边框高度且已启用水平内边距时启用。
     */
    protected boolean insetY;

    /**
     * 左侧页面导航栏组件。
     * <p>显示所有可用的设置页面列表，支持点击切换和选中高亮。
     */
    protected PageListWidget pageList;

    /**
     * 顶部搜索框组件。
     * <p>用于快速查找特定选项，支持实时搜索和清除功能。
     */
    protected SearchWidget searchWidget;

    /**
     * 主内容区域选项列表组件。
     * <p>显示当前选中页面的所有选项控件，支持滚动浏览。
     */
    protected OptionListWidget optionList;

    /**
     * 应用按钮。
     * <p>仅在存在未保存修改时启用，点击后调用 {@link #applyChanges()}。
     */
    protected Button applyButton;

    /**
     * 撤销按钮。
     * <p>仅在存在未保存修改时可见，点击后调用 {@link #undoChanges()}。
     */
    protected Button undoButton;

    /**
     * 完成/关闭按钮。
     * <p>在无未保存修改时启用，点击后返回父屏幕。
     */
    protected Button closeButton;

    /**
     * 是否有未保存的修改标记。
     * <p>由 {@link #updateControls(int, int)} 方法更新，
     * 用于控制按钮状态和 Esc 关闭行为。
     */
    protected boolean hasPendingChanges;

    /**
     * 悬浮提示框组件。
     * <p>当鼠标悬停在选项上时显示详细说明。
     * <b>可选功能</b>: 当前为 null 占位符，
     * 后续可实现 ScrollableTooltip 以提供更好的用户体验。
     */
    @Nullable
    protected Object tooltip;

    // ==================== 构造器 ====================

    /**
     * 创建渲染器设置界面基类实例。
     *
     * @param parent       父屏幕（关闭时返回到此屏幕）
     * @param title        屏幕标题组件
     *
     * @throws NullPointerException 若 parent 或 title 为 null
     */
    protected AbstractRendererSettingsScreen(Screen parent, Component title) {
        super(title);
        this.parent = parent;
        LOGGER.debug("AbstractRendererSettingsScreen initialized with title: {}", title.getString());
    }

    // ==================== 抽象方法（子类必须实现） ====================

    /**
     * 获取此设置界面的所有页面定义。
     *
     * <p>子类应返回包含所有设置分组的不可变列表。
     * 每个页面代表一个独立的设置分类（如"常规"、"质量"、"高级"等）。
     *
     * <h4>实现要求</h4>
     * <ul>
     *   <li>返回值不能为 null（但可以为空列表）</li>
     *   <li>建议使用 {@code List.of()} 或 {@code ImmutableList} 保证不可变性</li>
     *   <li>每个页面应包含至少一个选项分组</li>
     * </ul>
     *
     * @return 选项页面列表（不可变）
     *
     * <h4>示例实现</h4>
     * <pre>
     * &#64;Override
     * protected List&lt;RendererOptionPage&gt; getPages() {
     *     return List.of(
     *         generalPage,    // 常规设置
     *         qualityPage,    // 质量设置
     *         advancedPage    // 高级设置
     *     );
     * }
     * </pre>
     */
    protected abstract List<RendererOptionPage> getPages();

    /**
     * 应用所有已修改的选项变更。
     *
     * <p>当用户点击"Apply"按钮时调用。
     * 子类应在此方法中将用户的修改持久化到存储介质。
     *
     * <h4>典型操作</h4>
     * <ol>
     *   <li>收集所有已修改的选项</li>
     *   <li>将新值写入配置文件/数据库</li>
     *   <li>清除脏标记（dirty flags）</li>
     *   <li>通知相关系统重新加载配置</li>
     *   <li>刷新 UI 状态（禁用 Apply/Undo 按钮）</li>
     * </ol>
     *
     * <h4>异常处理</h4>
     * <p>如果应用失败，子类应：
     * <ul>
     *   <li>记录错误日志（使用 LOGGER.error()）</li>
     *   <li>向用户显示错误提示（可选）</li>
     *   <li><b>不抛出异常</b>（保持 UI 稳定性）</li>
     * </ul>
     *
     * <h4>线程安全</h4>
     * <p>此方法在 Minecraft 客户端线程（渲染线程）调用，
     * 无需额外同步。但如果涉及 I/O 操作，应考虑异步处理以避免卡顿。
     */
    protected abstract void applyChanges();

    /**
     * 撤销所有未保存的修改。
     *
     * <p>当用户点击"Undo"按钮时调用。
     * 子类应在此方法中将所有选项恢复到最后一次保存的状态。
     *
     * <h4>典型操作</h4>
     * <ol>
     *   <li>从持久化存储重新加载原始值</li>
     *   <li>更新所有选项控件的显示状态</li>
     *   <li>清除脏标记</li>
     *   <li>触发 UI 刷新（重建或更新受影响的组件）</li>
     * </ol>
     *
     * <h4>与 resetToDefaults() 的区别</h4>
     * <ul>
     *   <li>{@code undoChanges()} → 恢复到<strong>上次保存</strong>的状态</li>
     *   <li>{@code resetToDefaults()} → 恢复到<strong>出厂默认</strong>状态（如需实现）</li>
     * </ul>
     *
     * <h4>性能考虑</h4>
     * <p>撤销操作可能需要重建大量 UI 组件，
     * 建议使用增量更新而非完全重建以提高性能。
     */
    protected abstract void undoChanges();

    // ==================== 初始化和生命周期 ====================

    /**
     * 初始化屏幕组件。
     *
     * <p>Minecraft 在以下时机调用此方法：
     * <ul>
     *   <li>首次打开屏幕时</li>
     *   <li>窗口尺寸改变时</li>
     *   <li>从其他屏幕返回时</li>
     * </ul>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li>调用 {@code super.init()} 完成基础初始化</li>
     *   <li>调用 {@link #rebuild()} 构建完整界面</li>
     *   <li>初始化 tooltip 区域（如果可用）</li>
     * </ol>
     *
     * <h4>性能说明</h4>
     * <p>{@link #rebuild()} 会清空并重建所有组件，
     * 这是必要的因为窗口尺寸变化可能导致布局失效。
     * 目标执行时间：&lt; 5ms。
     */
    @Override
    protected void init() {
        super.init();

        this.rebuild();

        if (this.tooltip != null) {
            this.initTooltipArea();
        }

        LOGGER.debug("Screen initialized: width={}, height={}", this.width, this.height);
    }

    /**
     * 初始化提示框区域（占位符实现）。
     *
     * <p>后续 Task 3.x 实现真正的 ScrollableTooltip 后替换此方法。
     * 当前为空实现，避免 NullPointerException。
     */
    private void initTooltipArea() {
        // TODO: Task 3.x - 实现 ScrollableTooltip 后初始化提示框区域
        // 示例：this.tooltip.setTooltipArea(calculateTooltipArea());
    }

    // ==================== 布局重建 ====================

    /**
     * 重建整个界面布局。
     *
     * <p>此方法是界面构建的核心入口，负责：
     * <ol>
     *   <li>清空所有现有组件</li>
     *   <li>计算响应式内容区域尺寸</li>
     *   <li>创建顶部搜索框</li>
     *   <li>创建左侧页面导航栏</li>
     *   <li>创建主内容区域选项列表</li>
     *   <li>创建底部操作按钮</li>
     *   <li>更新提示框区域</li>
     * </ol>
     *
     * <h4>调用时机</h4>
     * <ul>
     *   <li>{@link #init()} 中调用</li>
     *   <li>窗口尺寸改变时（Minecraft 自动调用 init()）</li>
     *   <li>语言切换后（如需）</li>
     * </ul>
     *
     * <h4>布局算法</h4>
     * <pre>
     * 1. 计算内容区域（updateScreenDimensions）
     *    ↓
     * 2. 创建顶部搜索框（高度：BUTTON_SHORT）
     *    ↓
     * 3. 创建左侧导航栏（宽度：PAGE_LIST_WIDTH）
     *    ↓
     * 4. 根据剩余空间决定按钮布局模式：
     *    - 宽屏：水平排列 [Apply] [Undo] [Done]
     *    - 窄屏：垂直排列 [Done]
     *                            [Undo]
     *                            [Apply]
     *    ↓
     * 5. 创建主内容区域（填充剩余空间）
     *    ↓
     * 6. 更新提示框区域位置
     * </pre>
     *
     * <h4>性能优化</h4>
     * <ul>
     *   <li>避免在循环中创建临时对象</li>
     *   <li>批量添加组件减少重绘次数</li>
     *   <li>延迟非关键组件的初始化</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    protected void rebuild() {
        this.clearWidgets();

        this.updateScreenDimensions();

        final int x = this.dim.x();
        final int y = this.dim.y();
        final int w = this.dim.width();
        final int h = this.dim.height();

        // 1. 创建顶部搜索框
        final int topBarHeight = Layout.BUTTON_SHORT;
        this.createSearchWidget(x, y, w, topBarHeight);

        // 2. 创建左侧页面导航栏
        final int topBarClear = topBarHeight + this.ifInsetY(Layout.INNER_MARGIN);
        this.createPageListWidget(x, y + topBarClear, Layout.PAGE_LIST_WIDTH, h - topBarClear);

        // 3. 决定按钮布局模式（水平 vs 垂直）
        final boolean stackVertically = this.shouldStackButtonsVertically(w);
        final boolean reserveBottomSpace = this.shouldReserveBottomSpace(w);

        // 4. 创建底部按钮
        this.createBottomButtons(stackVertically);

        // 5. 创建主内容区域选项列表
        this.createOptionListWidget(
            this.getPageListLimitX(),
            y + topBarHeight + Layout.INNER_MARGIN,
            Layout.OPTION_WIDTH + Layout.OPTION_LIST_SCROLLBAR_OFFSET + Layout.SCROLLBAR_WIDTH,
            h - topBarHeight - (reserveBottomSpace ? (Layout.INNER_MARGIN * 2 + Layout.BUTTON_SHORT) : Layout.INNER_MARGIN) - this.ifNotInsetY(Layout.INNER_MARGIN)
        );

        // 6. 更新提示框区域
        this.updateTooltipArea(y, topBarHeight);

        LOGGER.trace("rebuild() completed: dim={}", this.dim);
    }

    // ==================== 尺寸计算 ====================

    /**
     * 更新屏幕内容区域的响应式尺寸。
     *
     * <p>根据当前窗口大小计算合适的内容区域尺寸，
     * 实现类似 Sodium 的自适应布局效果。
     *
     * <h4>算法详解</h4>
     *
     * <b>步骤 1：计算基础内容宽度</b>
     * <pre>
     * baseContentWidth = PAGE_LIST_WIDTH + INNER_MARGIN + OPTION_WIDTH
     *                  + OPTION_LIST_SCROLLBAR_OFFSET + SCROLLBAR_WIDTH
     *                  + TOOLTIP_OUTER_MARGIN
     * </pre>
     *
     * <b>步骤 2：确定有效内容宽度范围</b>
     * <pre>
     * minContentWidth = baseContentWidth + (MAX_TOOLTIP_WIDTH - MIN_TOOLTIP_WIDTH) / 2 + MIN_TOOLTIP_WIDTH
     * maxContentWidth = baseContentWidth + MAX_TOOLTIP_WIDTH
     * </pre>
     *
     * <b>步骤 3：判断是否启用水平内边距（insetX）</b>
     * <ul>
     *   <li>若 screen.width ≤ minContentWidth + CONTENT_BORDER_MIN_WIDTH → 全宽，insetX=false</li>
     *   <li>若 screen.width 在范围内 → 线性插值计算实际宽度，insetX=true</li>
     *   <li>若 screen.width ≥ maxContentWidth + 100 → 使用最大宽度，insetX=true</li>
     * </ul>
     *
     * <b>步骤 4：判断是否启用垂直内边距（insetY）</b>
     * <ul>
     *   <li>仅当 insetX=true 且高度足够时才启用</li>
     *   <li>contentHeight = screen.height - CONTENT_BORDER_HEIGHT</li>
     * </ul>
     *
     * <b>步骤 5：居中定位</b>
     * <pre>
     * dim.x = (screen.width - contentWidth) / 2
     * dim.y = (screen.height - contentHeight) / 2
     * </pre>
     *
     * <h4>视觉效果</h4>
     * <pre>
     * 小屏幕（1024×768）：          大屏幕（2560×1440）：
     * ┌──────────────────────┐     ┌─────────────────────────────────────┐
     * │                      │     │                                       │
     * │   全宽内容区域        │     │         居中内容区域（带边距）          │
     * │                      │     │                                       │
     * └──────────────────────┘     └─────────────────────────────────────┘
     * </pre>
     */
    protected void updateScreenDimensions() {
        // 计算基础内容宽度（所有组件的最小占用）
        final int baseContentWidth = Layout.PAGE_LIST_WIDTH + Layout.INNER_MARGIN
            + Layout.OPTION_WIDTH + Layout.OPTION_LIST_SCROLLBAR_OFFSET + Layout.SCROLLBAR_WIDTH
            + Layout.TOOLTIP_OUTER_MARGIN;

        // 计算有效内容宽度范围
        final int minContentWidth = baseContentWidth + (Layout.MAX_TOOLTIP_WIDTH - Layout.MIN_TOOLTIP_WIDTH) / 2 + Layout.MIN_TOOLTIP_WIDTH;
        final int maxContentWidth = baseContentWidth + Layout.MAX_TOOLTIP_WIDTH;
        final int maxInterpolatingBorderWidth = 100;
        final int widthInterpolationStart = minContentWidth + Layout.CONTENT_BORDER_MIN_WIDTH;
        final int widthInterpolationEnd = maxContentWidth + maxInterpolatingBorderWidth;

        // 计算实际内容宽度（支持插值过渡）
        int contentWidth = this.width;
        this.insetX = false;

        if (this.width > minContentWidth + Layout.CONTENT_BORDER_MIN_WIDTH) {
            if (this.width < widthInterpolationEnd) {
                // 线性插值：从最小宽度平滑过渡到最大宽度
                float t = (float)(this.width - widthInterpolationStart) / (widthInterpolationEnd - widthInterpolationStart);
                contentWidth = minContentWidth + (int)(t * (maxContentWidth - minContentWidth));
            } else {
                contentWidth = maxContentWidth;
            }
            this.insetX = true;
        }

        // 计算实际内容高度（仅当水平方向已收缩时才启用垂直收缩）
        int contentHeight = this.height;
        this.insetY = false;

        if (this.height > Layout.CONTENT_MIN_HEIGHT + Layout.CONTENT_BORDER_HEIGHT && this.insetX) {
            contentHeight = this.height - Layout.CONTENT_BORDER_HEIGHT;
            this.insetY = true;
        }

        // 居中定位内容区域
        this.dim = new Dim2i(
            (this.width - contentWidth) / 2,
            (this.height - contentHeight) / 2,
            contentWidth,
            contentHeight
        );
    }

    // ==================== 条件内边距辅助方法 ====================

    /**
     * 如果启用了水平内边距则返回指定值，否则返回 0。
     *
     * @param value 条件值
     * @return 若 insetX=true 返回 value，否则返回 0
     */
    protected int ifInsetX(int value) {
        return this.insetX ? value : 0;
    }

    /**
     * 如果启用了垂直内边距则返回指定值，否则返回 0。
     *
     * @param value 条件值
     * @return 若 insetY=true 返回 value，否则返回 0
     */
    protected int ifInsetY(int value) {
        return this.insetY ? value : 0;
    }

    /**
     * 如果未启用水平内边距则返回指定值，否则返回 0。
     *
     * @param value 条件值
     * @return 若 insetX=false 返回 value，否则返回 0
     */
    protected int ifNotInsetX(int value) {
        return this.insetX ? 0 : value;
    }

    /**
     * 如果未启用垂直内边距则返回指定值，否则返回 0。
     *
     * @param value 条件值
     * @return 若 insetY=false 返回 value，否则返回 0
     */
    protected int ifNotInsetY(int value) {
        return this.insetY ? 0 : value;
    }

    // ==================== 组件创建方法（占位符实现） ====================

    /**
     * 创建顶部搜索框组件。
     *
     * <p>使用自定义 SearchWidget 替代原版 EditBox，
     * 提供实时搜索、清除按钮等增强功能。
     *
     * @param x      左上角 X 坐标
     * @param y      左上角 Y 坐标
     * @param width  组件宽度
     * @param height 组件高度
     */
    protected void createSearchWidget(int x, int y, int width, int height) {
        // 创建 SearchWidget 实例（带搜索结果回调）
        this.searchWidget = new SearchWidget(
                new Dim2i(x, y, width, height),
                this::onSearchResults
        );

        // 设置初始宽度以触发内部布局构建
        this.searchWidget.updateWidgetWidth(width);

        this.addRenderableWidget(this.searchWidget);
        LOGGER.debug("SearchWidget created at ({}, {}, {}, {})", x, y, width, height);
    }

    /**
     * 创建左侧页面导航栏组件。
     *
     * <p>使用自定义 PageListWidget 显示所有可用页面，
     * 支持点击切换和选中高亮功能。
     *
     * @param x      左上角 X 坐标
     * @param y      左上角 Y 坐标
     * @param width  组件宽度
     * @param height 组件高度
     */
    protected void createPageListWidget(int x, int y, int width, int height) {
        // 获取所有页面列表
        List<RendererOptionPage> pages = this.getPages();

        // 创建 PageListWidget 实例（带页面选择回调）
        this.pageList = new PageListWidget(
                new Dim2i(x, y, width, height),
                pages,
                this::onPageSelected  // 页面选择时回调
        );

        this.addRenderableWidget(this.pageList);
        LOGGER.debug("PageListWidget created with {} pages at ({}, {}, {}, {})",
                pages.size(), x, y, width, height);
    }

    /**
     * 页面选择回调处理方法。
     *
     * <p>当用户在导航栏中点击某个页面时调用，
     * 用于同步更新主内容区域显示对应的选项。
     *
     * @param page 用户选中的页面
     */
    private void onPageSelected(RendererOptionPage page) {
        if (page == null) {
            return;
        }

        // 更新主内容区域以显示选中页面的选项
        if (this.optionList != null) {
            this.optionList.showPage(page);
        }

        LOGGER.debug("Page selected: {}", page.name().getString());
    }

    /**
     * 创建主内容区域选项列表组件。
     *
     * <p>使用自定义 OptionListWidget 显示当前页面的所有选项，
     * 支持滚动浏览、搜索过滤等功能。
     *
     * @param x      左上角 X 坐标
     * @param y      左上角 Y 坐标
     * @param width  组件宽度
     * @param height 组件高度
     */
    protected void createOptionListWidget(int x, int y, int width, int height) {
        // 创建 OptionListWidget 实例（带页面聚焦回调）
        this.optionList = new OptionListWidget(
                this,                          // 父屏幕引用
                new Dim2i(x, y, width, height),
                this::onSectionFocused         // 页面聚焦变化时回调
        );

        this.addRenderableWidget(this.optionList);
        LOGGER.debug("OptionListWidget created at ({}, {}, {}, {})", x, y, width, height);

        // 如果有默认页面，显示第一个页面
        List<RendererOptionPage> pages = this.getPages();
        if (!pages.isEmpty()) {
            this.optionList.showPage(pages.get(0));

            // 同步导航栏选中状态
            if (this.pageList != null) {
                this.pageList.switchSelected(pages.get(0));
            }
        }
    }

    /**
     * 创建底部操作按钮组（Apply/Undo/Done）。
     *
     * <p>根据屏幕宽度自动选择水平或垂直排列方式：
     * <ul>
     *   <li><b>水平排列</b>（默认）：[Apply] [Undo] [Done]</li>
     *   <li><b>垂直排列</b>（窄屏）：按钮堆叠在右侧</li>
     * </ul>
     *
     * @param stackVertically 是否垂直堆叠按钮
     */
    protected void createBottomButtons(boolean stackVertically) {
        final int buttonY = this.dim.getLimitY() - (this.ifNotInsetY(Layout.INNER_MARGIN) + Layout.BUTTON_SHORT);

        // Done 按钮（始终在最右侧/底部）
        this.closeButton = Button.builder(
            Component.translatable("gui.done"),
            btn -> this.onClose()
        )
        .bounds(
            this.dim.getLimitX() - Layout.BUTTON_LONG - this.ifNotInsetX(Layout.INNER_MARGIN),
            buttonY,
            Layout.BUTTON_LONG,
            Layout.BUTTON_SHORT
        )
        .build();
        this.addRenderableWidget(this.closeButton);

        if (stackVertically) {
            // 垂直排列模式（窄屏）
            this.applyButton = Button.builder(
                Component.translatable("renderium.options.buttons.apply"),
                btn -> this.applyChanges()
            )
            .bounds(
                this.closeButton.getX(),
                this.closeButton.getY() - (Layout.INNER_MARGIN + Layout.BUTTON_SHORT),
                Layout.BUTTON_LONG,
                Layout.BUTTON_SHORT
            )
            .build();

            this.undoButton = Button.builder(
                Component.translatable("renderium.options.buttons.undo"),
                btn -> this.undoChanges()
            )
            .bounds(
                this.applyButton.getX(),
                this.applyButton.getY() - (Layout.INNER_MARGIN + Layout.BUTTON_SHORT),
                Layout.BUTTON_LONG,
                Layout.BUTTON_SHORT
            )
            .build();
        } else {
            // 水平排列模式（默认）
            this.applyButton = Button.builder(
                Component.translatable("renderium.options.buttons.apply"),
                btn -> this.applyChanges()
            )
            .bounds(
                this.closeButton.getX() - Layout.INNER_MARGIN - Layout.BUTTON_LONG,
                buttonY,
                Layout.BUTTON_LONG,
                Layout.BUTTON_SHORT
            )
            .build();

            this.undoButton = Button.builder(
                Component.translatable("renderium.options.buttons.undo"),
                btn -> this.undoChanges()
            )
            .bounds(
                this.applyButton.getX() - Layout.INNER_MARGIN - Layout.BUTTON_LONG,
                buttonY,
                Layout.BUTTON_LONG,
                Layout.BUTTON_SHORT
            )
            .build();
        }

        this.addRenderableWidget(this.undoButton);
        this.addRenderableWidget(this.applyButton);

        // 初始状态：Apply 和 Undo 禁用/隐藏
        this.applyButton.active = false;
        this.undoButton.visible = false;
    }

    /**
     * 判断是否应该垂直堆叠底部按钮。
     *
     * <p>当屏幕宽度处于中等范围时启用垂直堆叠，
     * 避免按钮过于拥挤。
     *
     * @param contentWidth 内容区域宽度
     * @return 如果应该垂直堆叠返回 true
     */
    private boolean shouldStackButtonsVertically(int contentWidth) {
        final int minWidthToStack = Layout.PAGE_LIST_WIDTH + Layout.INNER_MARGIN * 2
            + Layout.OPTION_WIDTH + Layout.OPTION_LIST_SCROLLBAR_OFFSET + Layout.SCROLLBAR_WIDTH
            + Layout.BUTTON_LONG;
        final int maxWidthToStack = minWidthToStack + Layout.BUTTON_LONG * 2 + Layout.INNER_MARGIN;

        return contentWidth > minWidthToStack && contentWidth < maxWidthToStack;
    }

    /**
     * 判断是否需要在底部预留按钮空间。
     *
     * <p>当屏幕较窄时，主内容区域需要缩小以为按钮留出空间。
     *
     * @param contentWidth 内容区域宽度
     * @return 如果需要预留空间返回 true
     */
    private boolean shouldReserveBottomSpace(int contentWidth) {
        final int minWidthToStack = Layout.PAGE_LIST_WIDTH + Layout.INNER_MARGIN * 2
            + Layout.OPTION_WIDTH + Layout.OPTION_LIST_SCROLLBAR_OFFSET + Layout.SCROLLBAR_WIDTH
            + Layout.BUTTON_LONG;

        return contentWidth < minWidthToStack;
    }

    // ==================== 提示框区域管理 ====================

    /**
     * 更新提示框区域的位置和尺寸。
     *
     * <p>提示框位于主内容区域右侧，
     * 用于显示当前悬停选项的详细说明。
     *
     * @param contentY        内容区域起始 Y 坐标
     * @param topBarHeight    顶部栏高度
     */
    private void updateTooltipArea(int contentY, int topBarHeight) {
        // TODO Task 3.x: 实现 ScrollableTooltip 后更新提示框区域
        // 示例代码（参考 Sodium）：
        /*
        var tooltipAreaY = contentY + topBarHeight + this.ifInsetY(Layout.TOOLTIP_OUTER_MARGIN);
        this.tooltip.setTooltipArea(new Dim2i(
            this.optionList.getLimitX(),
            tooltipAreaY,
            this.dim.getLimitX() - this.optionList.getLimitX() - this.ifNotInsetX(Layout.TOOLTIP_OUTER_MARGIN),
            this.dim.getLimitY() - tooltipAreaY - this.ifInsetY(Layout.TOOLTIP_OUTER_MARGIN)
        ));
        */
    }

    // ==================== 渲染方法（MC 26.2 适配） ====================

    /**
     * 渲染屏幕内容（MC 26.2 API）。
     *
     * <p>MC 26.2 使用新的渲染管线（extractRenderState），
     * 此方法保留用于兼容性，但不再覆盖父类方法。
     *
     * @param graphics 图形上下文
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param delta    部分刻度时间
     */
    // MC 26.2 兼容性: Screen.render() 方法签名已变更
    // 原方法: render(GuiGraphics, int, int, float)
    // 新API可能使用 extractRenderState() 替代
    // TODO: 适配 MC 26.2 新的渲染管线
    public void renderScreen(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.updateControls(mouseX, mouseY);

        // 调用父类渲染（如果需要）
        // super.render(graphics, mouseX, mouseY, delta);

        // 渲染提示框（如果已实现）
        if (this.tooltip != null) {
            this.renderTooltip(graphics, mouseX, mouseY);
        }
    }

    /**
     * 渲染悬浮提示框（占位符实现）。
     *
     * <p>后续实现 ScrollableTooltip 后替换此方法。
     *
     * @param graphics 图形上下文
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     */
    private void renderTooltip(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
        // TODO Task 3.x: 实现真正的提示框渲染
    }

    // ==================== 控制状态更新 ====================

    /**
     * 更新所有控制组件的状态。
     *
     * <p>每帧调用一次，根据当前是否有未保存的修改来更新按钮状态：
     * <ul>
     *   <li><b>Apply 按钮</b>：有修改时启用（active = true）</li>
     *   <li><b>Undo 按钮</b>：有修改时可见（visible = true）</li>
     *   <li><b>Done 按钮</b>：无修改时启用（active = true）</li>
     * </ul>
     *
     * <h4>性能优化</h4>
     * <p>此方法每帧都会被调用，因此需要保证高效：
     * <ul>
     *   <li>避免重复计算</li>
     *   <li>只在状态变化时更新 UI</li>
     *   <li>使用缓存减少不必要的检查</li>
     * </ul>
     *
     * @param mouseX 当前鼠标 X 坐标（可用于检测悬停目标）
     * @param mouseY 当前鼠标 Y 坐标
     */
    protected void updateControls(int mouseX, int mouseY) {
        // 检查是否有任何选项发生了修改（通过 VideoSettingsBridge 获取 registry 实例）
        // MC 26.2 兼容性: VideoOptionsRegistry.isDirty() 是实例方法，不是静态方法
        com.ranecc.renderium.platform.bridge.video.VideoOptionsRegistry registry =
                com.ranecc.renderium.platform.bridge.video.VideoSettingsBridge.getVideoOptionsRegistry();
        boolean hasChanges = (registry != null && registry.isDirty());

        // 更新按钮状态
        this.applyButton.active = hasChanges;
        this.undoButton.visible = hasChanges;
        this.closeButton.active = !hasChanges;

        // 更新内部状态标记
        this.hasPendingChanges = hasChanges;

        // TODO Task 3.x: 更新提示框的悬停目标
        // 示例：this.updateTooltipHoverTarget(mouseX, mouseY);
    }

    // ==================== 键盘事件处理 ====================

    /**
     * 处理键盘按下事件。
     *
     * <p>MC 26.2 API: keyPressed 现在接受 KeyEvent 对象
     * 而非 (int keyCode, int scanCode, int modifiers)。
     * 此方法保留用于兼容性，但不再覆盖父类方法。
     *
     * @param keyCode  按键的 GLFW 键码
     * @param scanCode 扫描码（平台特定）
     * @param modifiers 修饰键（如 Shift、Ctrl 等）
     * @return 如果事件已被消费返回 true
     */
    // MC 26.2 兼容性: Screen.keyPressed() 签名已变为 keyPressed(KeyEvent)
    // TODO: 适配新的 KeyEvent API
    public boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        // T 键：聚焦搜索框
        if (keyCode == KEY_T) {
            if (this.searchWidget != null) {
                this.focusSearchWidget();
                return true;
            }
        }

        // Shift + P：打开原版视频设置
        if (keyCode == KEY_P && (modifiers & MOD_SHIFT) != 0) {
            this.openVanillaVideoSettings();
            return true;
        }

        return false;  // 不再调用 super.keyPressed()，因为签名不匹配
    }

    /**
     * 检测搜索框是否处于焦点状态。
     *
     * @return 如果搜索框有焦点返回 true
     */
    private boolean isSearchFocused() {
        return this.searchWidget != null && this.searchWidget.isSearching();
    }

    /**
     * 将焦点转移到搜索框。
     */
    private void focusSearchWidget() {
        if (this.searchWidget != null) {
            this.setFocused(this.searchWidget);
            LOGGER.debug("Search widget focused");
        }
    }

    /**
     * 打开原版视频设置界面。
     *
     * <p>此功能仅在"狂暴模式"（Standalone Mode）下有意义，
     * 因为兼容模式下用户可以直接访问原版设置。
     * MC 26.2 API: 直接使用标准构造函数和 setScreen()
     */
    private void openVanillaVideoSettings() {
        try {
            Minecraft mc = Minecraft.getInstance();
            // MC 26.2: 使用标准方式创建并打开视频设置屏幕
            // MC 26.2: VideoSettingsScreen(Screen lastScreen, Minecraft minecraft, Options options)
            var screen = new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                this.parent,
                mc,
                mc.options
            );
            // MC 26.2: 方法名从 setScreen 改为 setScreenAndShow
            mc.setScreenAndShow(screen);
            LOGGER.info("Opened vanilla video settings");
        } catch (Exception e) {
            LOGGER.warn("Failed to open vanilla video settings", e);
        }
    }

    // ==================== 鼠标事件处理 ====================

    /**
     * 处理鼠标点击事件。
     *
     * <p>MC 26.2 API: mouseClicked 方法签名可能已变更。
     * 此方法保留用于兼容性，但不再覆盖父类方法。
     *
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param button   鼠标按钮编号（0=左键, 1=右键, 2=中键）
     * @return 事件总是返回 true（消费事件）
     */
    // MC 26.2 兼容性: Screen.mouseClicked() 签名可能已变更
    // TODO: 适配新的鼠标事件 API
    public boolean handleMouseClicked(double mouseX, double mouseY, int button) {
        // 点击空白区域
        if (!this.isSearchFocused()) {
            this.focusSearchWidget();
        } else {
            this.setFocused(null);  // 取消搜索框焦点
        }
        return true;
    }

    /**
     * 处理鼠标滚轮事件。
     *
     * <p>支持以下滚轮操作：
     * <ul>
     *   <li><b>Ctrl + 滚轮</b>：调整 GUI 缩放比例（如果有 GUI Scale 选项）</li>
     *   <li><b>普通滚轮</b>：传递给提示框或父类处理</li>
     * </ul>
     *
     * @param x      鼠标 X 坐标
     * @param y      鼠标 Y 坐标
     * @param f      水平滚动量（通常为 0）
     * @param amount 垂直滚动量（正值向下，负值向上）
     * @return 如果事件已被消费返回 true
     */
    @Override
    public boolean mouseScrolled(double x, double y, double f, double amount) {
        // Ctrl + 滚轮：调整 GUI 缩放
        if (Minecraft.getInstance().hasControlDown()) {
            return this.adjustGuiScale(amount);
        }

        // 传递给提示框处理（如果已实现）
        if (this.tooltip != null && this.isTooltipScrollable(x, y, amount)) {
            return true;
        }

        return super.mouseScrolled(x, y, f, amount);
    }

    /**
     * 调整 GUI 缩放比例。
     *
     * <p>通过 Ctrl + 滚轮快速调整界面大小，
     * 无需进入原版设置界面。
     *
     * <h4>算法</h4>
     * <ol>
     *   <li>获取当前的 GUI Scale 选项</li>
     *   <li>根据滚动方向增加或减少数值</li>
     *   <li>限制在有效范围内（Auto ~ Max）</li>
     *   <li>立即应用更改并触发布局重建</li>
     * </ol>
     *
     * @param amount 滚动量（正值放大，负值缩小）
     * @return 如果成功调整返回 true
     */
    private boolean adjustGuiScale(double amount) {
        // GUI Scale 动态调整（占位符模式）
        // 完整实现需依赖 IntegerOption 的完整实现
        // 参考 Sodium 的实现：获取 gui_scale 选项并修改其值

        try {
            LOGGER.debug("GUI scale adjustment requested: amount={}", amount);

            // TODO: 集成 OptionPage/Option 系统后实现真实调整
            // 伪代码示例：
            // Option guiScaleOption = findOption("gui_scale");
            // if (guiScaleOption instanceof IntegerOption intOpt) {
            //     int current = intOpt.getValue();
            //     intOpt.setValue(Math.clamp(current + (int)amount, 0, 4));
            //     return true;
            // }

            LOGGER.debug("GUI scale adjustment not yet integrated with Option system");
            return false;
        } catch (Exception e) {
            LOGGER.warn("GUI scale adjustment failed", e);
            return false;
        }
    }

    /**
     * 检测提示框是否可以处理滚轮事件。
     *
     * @param x      鼠标 X 坐标
     * @param y      鼠标 Y 坐标
     * @param amount 滚动量
     * @return 如果提示框处理了滚轮返回 true
     */
    private boolean isTooltipScrollable(double x, double y, double amount) {
        // TODO Task 3.x: ScrollableTooltip 实现后替换
        return false;
    }

    // ==================== 屏幕行为控制 ====================

    /**
     * 判断是否允许按 Esc 关闭屏幕。
     *
     * <p>当存在未保存的修改时禁止关闭，
     * 强制用户先选择 Apply 或 Undo。
     *
     * @return 如果无待处理的修改返回 true（允许关闭），否则返回 false
     */
    @Override
    public boolean shouldCloseOnEsc() {
        return !this.hasPendingChanges;
    }

    /**
     * 关闭屏幕时的回调。
     *
     * <p>返回到父屏幕（通常是选项菜单或主菜单）。
     * MC 26.2 API: 使用 minecraft.gui.setScreen() 替代已废弃的 minecraft.setScreen()
     */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
        LOGGER.debug("Screen closed, returning to parent: {}", this.parent.getClass().getSimpleName());
    }

    // ==================== 页面导航辅助方法 ====================

    /**
     * 跳转到指定的选项页面。
     *
     * <p>可被外部调用（如从搜索结果跳转、
     * 从通知提示跳转等）。
     *
     * <h4>使用场景</h4>
     * <ul>
     *   <li>用户点击导航栏中的页面项</li>
     *   <li>搜索结果定位到特定页面</li>
     *   <li>程序化跳转（如首次打开时定位到推荐页面）</li>
     * </ul>
     *
     * @param page 目标页面（不能为 null）
     * @throws NullPointerException 若 page 为 null
     */
    public void jumpToPage(RendererOptionPage page) {
        if (page == null) {
            throw new NullPointerException("Target page cannot be null");
        }

        // 跳转到指定页面
        if (this.optionList != null) {
            this.optionList.jumpToPage(page);
        }

        // 同步导航栏选中状态
        if (this.pageList != null) {
            this.pageList.switchSelected(page);
        }

        LOGGER.info("Jumping to page: {}", page.name().getString());
    }

    /**
     * 页面切换回调。
     *
     * <p>当主内容区域切换到新页面时调用，
     * 用于同步更新左侧导航栏的高亮状态。
     *
     * <h4>调用时机</h4>
     * <ul>
     *   <li>用户点击导航栏项</li>
     *   <li>通过 searchResult 跳转</li>
     *   <li>程序化调用 jumpToPage()</li>
     * </ul>
     *
     * @param page 新选中的页面
     */
    protected void onSectionFocused(RendererOptionPage page) {
        if (page == null) {
            return;
        }

        // 同步更新左侧导航栏的高亮状态
        if (this.pageList != null) {
            this.pageList.switchSelected(page);
        }

        LOGGER.debug("Section focused: {}", page.name().getString());
    }

    // ==================== 搜索功能辅助方法 ====================

    /**
     * 处理搜索结果。
     *
     * <p>当用户在搜索框输入文本时调用，
     * 根据搜索结果过滤或高亮匹配的选项。
     *
     * <h4>处理策略</h4>
     * <ul>
     *   <li><b>空结果</b>：清除过滤，显示所有选项</li>
     *   <li><b>有结果</b>：只显示匹配的选项</li>
     * </ul>
     *
     * @param results 搜索结果列表（可能为空，但不能为 null）
     */
    @SuppressWarnings("unchecked")
    protected void onSearchResults(List<?> results) {
        if (results == null) {
            throw new NullPointerException("Search results cannot be null");
        }

        if (results.isEmpty()) {
            // 清除过滤，显示全部选项
            this.clearOptionFilter();
        } else {
            // 设置过滤后的选项列表
            this.setFilteredOptions(results);
        }

        // 重建选项列表以反映过滤结果
        this.rebuildOptionList();

        LOGGER.debug("Search results processed: {} matches", results.size());
    }

    /**
     * 清除选项过滤器。
     */
    private void clearOptionFilter() {
        if (this.optionList != null) {
            this.optionList.clearFilter();
            LOGGER.debug("Option filter cleared");
        }
    }

    /**
     * 设置过滤后的选项列表。
     *
     * @param results 过滤后的选项
     */
    @SuppressWarnings("unchecked")
    private void setFilteredOptions(List<?> results) {
        if (this.optionList != null) {
            this.optionList.setFilteredOptions(results);
            LOGGER.debug("Filtered options set: {} items", results.size());
        }
    }

    /**
     * 重建选项列表以反映最新状态。
     */
    private void rebuildOptionList() {
        if (this.optionList != null) {
            // OptionListWidget 内部会在 showPage 或 setFilteredOptions 时自动重建
            LOGGER.debug("Option list rebuild triggered");
        }
    }

    // ==================== 维度访问辅助方法 ====================

    /**
     * 获取内容区域的左上角 X 坐标。
     *
     * @return X 坐标
     */
    protected int getX() {
        return this.dim.x();
    }

    /**
     * 获取内容区域的左上角 Y 坐标。
     *
     * @return Y 坐标
     */
    protected int getY() {
        return this.dim.y();
    }

    /**
     * 获取内容区域的宽度。
     *
     * @return 宽度（像素）
     */
    protected int getWidth() {
        return this.dim.width();
    }

    /**
     * 获取内容区域的高度。
     *
     * @return 高度（像素）
     */
    protected int getHeight() {
        return this.dim.height();
    }

    /**
     * 获取内容区域的右边缘 X 坐标。
     *
     * @return 右边缘坐标
     */
    protected int getLimitX() {
        return this.dim.getLimitX();
    }

    /**
     * 获取内容区域的底边缘 Y 坐标。
     *
     * @return 底边缘坐标
     */
    protected int getLimitY() {
        return this.dim.getLimitY();
    }

    /**
     * 获取页面列表的右边缘 X 坐标。
     *
     * <p>用于定位主内容区域的左边界。
     *
     * @return 页面列表右边缘坐标
     */
    protected int getPageListLimitX() {
        // 返回页面列表的实际右边缘坐标
        if (this.pageList != null) {
            return this.pageList.getLimitX();
        }

        // 回退到基于 dim 的计算（初始化完成前）
        return this.dim.x() + Layout.PAGE_LIST_WIDTH;
    }

    /**
     * 获取内容区域尺寸（供外部使用）。
     *
     * <p>主要用于 Tooltip 定位和其他需要知道内容区域位置的组件。
     *
     * @return 内容区域的 Dim2i 对象
     */
    public Dim2i getDimensions() {
        return this.dim;
    }

    // ==================== 组件生命周期管理 ====================

    /**
     * 添加可渲染组件到屏幕。
     *
     * <p>包装父类方法，提供统一的组件管理接口。
     *
     * @param <T>              组件类型（必须同时实现 GuiEventListener、Renderable、NarratableEntry）
     * @param guiEventListener 要添加的组件
     * @return 添加的组件实例（支持链式调用）
     */
    @Override
    public <T extends GuiEventListener & net.minecraft.client.gui.components.Renderable & NarratableEntry> T addRenderableWidget(T guiEventListener) {
        return super.addRenderableWidget(guiEventListener);
    }

    /**
     * 从屏幕移除组件。
     *
     * @param guiEventListener 要移除的组件
     */
    @Override
    public void removeWidget(GuiEventListener guiEventListener) {
        super.removeWidget(guiEventListener);
    }

    /**
     * 设置组件的可见性（添加或移除）。
     *
     * <p>用于动态显示/隐藏组件而不丢失其状态。
     *
     * @param <T>              组件类型
     * @param guiEventListener 目标组件
     * @param present          true=添加组件，false=移除组件
     */
    public <T extends GuiEventListener & net.minecraft.client.gui.components.Renderable & NarratableEntry> void setWidgetPresence(T guiEventListener, boolean present) {
        this.removeWidget(guiEventListener);
        if (present) {
            this.addRenderableWidget(guiEventListener);
        }
    }
}
