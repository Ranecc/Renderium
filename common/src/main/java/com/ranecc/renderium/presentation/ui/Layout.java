// Renderium - UI 布局常量定义
// 参考 Sodium 的 Layout 类，使用中性命名

package com.ranecc.renderium.presentation.ui;

/**
 * 渲染器设置界面的布局常量。
 *
 * <p>集中管理所有 UI 组件的尺寸、间距和边距常量，
 * 确保界面布局的一致性和可维护性。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code Layout} 常量类设计，
 * 但使用 Renderium 中性命名，调整部分数值以适配项目需求。
 *
 * <h2>常量分类</h2>
 * <ul>
 *   <li><b>按钮尺寸</b>：BUTTON_SHORT, BUTTON_LONG</li>
 *   <li><b>内边距</b>：INNER_MARGIN, OPTION_GROUP_MARGIN 等</li>
 *   <li><b>组件尺寸</b>：PAGE_LIST_WIDTH, OPTION_WIDTH, SCROLLBAR_WIDTH</li>
 *   <li><b>提示框</b>：TOOLTIP_OUTER_MARGIN, MIN/MAX_TOOLTIP_WIDTH</li>
 *   <li><b>内容区域</b>：CONTENT_BORDER_MIN_WIDTH, CONTENT_BORDER_HEIGHT 等</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建按钮
 * Button button = new Button.Builder(...)
 *     .bounds(x, y, Layout.BUTTON_LONG, Layout.BUTTON_SHORT)
 *     .build();
 *
 * // 计算页面列表宽度
 * int pageListWidth = Layout.PAGE_LIST_WIDTH;  // 120px
 *
 * // 设置内边距
 * int padding = Layout.INNER_MARGIN;  // 6px
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class Layout {

    /** 私有构造器防止实例化（纯常量类） */
    private Layout() {
        throw new UnsupportedOperationException("Layout is a utility class and cannot be instantiated");
    }

    // ==================== 按钮尺寸 ====================

    /**
     * 短按钮高度（像素）。
     * <p>用于底部操作按钮（Apply/Undo/Done）。
     */
    public static final int BUTTON_SHORT = 20;

    /**
     * 长按钮宽度（像素）。
     * <p>用于需要显示文本的按钮。
     */
    public static final int BUTTON_LONG = 80;

    // ==================== 内边距和间距 ====================

    /**
     * 内边距（像素）。
     * <p>组件之间的标准间距，用于主要布局区域。
     */
    public static final int INNER_MARGIN = 6;

    /**
     * 选项组内边距（像素）。
     * <p>同一分组内选项之间的垂直间距。
     */
    public static final int OPTION_GROUP_MARGIN = 3;

    /**
     * 选项页内边距（像素）。
     * <p>页面内容与容器边缘的间距。
     */
    public static final int OPTION_PAGE_MARGIN = 6;

    /**
     * 选项模块内边距（像素）。
     * <p>大型选项模块之间的间距。
     */
    public static final int OPTION_MOD_MARGIN = 12;

    /**
     * 选项左侧缩进（像素）。
     * <p>选项控件相对于左侧标签的偏移量。
     */
    public static final int OPTION_LEFT_INSET = OPTION_GROUP_MARGIN;

    // ==================== 滚动条 ====================

    /**
     * 滚动条宽度（像素）。
     */
    public static final int SCROLLBAR_WIDTH = 8;

    // ==================== 文本样式 ====================

    /**
     * 文本左内边距（像素）。
     */
    public static final int TEXT_LEFT_PADDING = 8;

    /**
     * 段落间距（像素）。
     * <p>不同段落之间的垂直距离。
     */
    public static final int TEXT_PARAGRAPH_SPACING = 8;

    /**
     * 行间距（像素）。
     * <p>同一行内的行高增量。
     */
    public static final int TEXT_LINE_SPACING = 2;

    /**
     * 常规文本基线偏移（像素）。
     * <p>用于文本垂直居中时的微调。
     */
    public static final int REGULAR_TEXT_BASELINE_OFFSET = -4;

    // ==================== 页面导航栏 ====================

    /**
     * 页面列表宽度（像素）。
     * <p>左侧导航栏的固定宽度。
     */
    public static final int PAGE_LIST_WIDTH = 120;

    // ==================== 选项列表 ====================

    /**
     * 选项区域宽度（像素）。
     * <p>主内容区域的固定宽度（不含滚动条）。
     */
    public static final int OPTION_WIDTH = 200;

    /**
     * 选项列表滚动条偏移（像素）。
     * <p>选项列表右侧预留的滚动条空间。
     */
    public static final int OPTION_LIST_SCROLLBAR_OFFSET = 6;

    /**
     * 选项文本水平内边距（像素）。
     */
    public static final int OPTION_TEXT_SIDE_PADDING = 6;

    // ==================== 图标样式 ====================

    /**
     * 图标外边距（像素）。
     */
    public static final int ICON_MARGIN = 4;

    /**
     * 图标文本基线偏移（像素）。
     * <p>图标与旁边文本对齐时的微调值。
     */
    public static final int ICON_TEXT_BASELINE_OFFSET = -3;

    // ==================== 提示框 (Tooltip) ====================

    /**
     * 提示框外部边距（像素）。
     * <p>提示框与屏幕边缘的最小距离。
     */
    public static final int TOOLTIP_OUTER_MARGIN = 12;

    /**
     * 提示框最小宽度（像素）。
     */
    public static final int MIN_TOOLTIP_WIDTH = 150;

    /**
     * 提示框最大宽度（像素）。
     * <p>超出此宽度的文本将自动换行。
     */
    public static final int MAX_TOOLTIP_WIDTH = 350;

    // ==================== 内容区域自适应 ====================

    /**
     * 内容区域最小边框宽度（像素）。
     * <p>当屏幕宽度超过最小内容宽度 + 此值时，启用水平居中模式。
     */
    public static final int CONTENT_BORDER_MIN_WIDTH = 100;

    /**
     * 内容区域边框高度（像素）。
     * <p>当屏幕高度超过最小高度 + 此值时，启用垂直收缩模式。
     */
    public static final int CONTENT_BORDER_HEIGHT = 180;

    /**
     * 内容区域最小高度（像素）。
     * <p>低于此高度的屏幕不会启用响应式布局。
     */
    public static final int CONTENT_MIN_HEIGHT = 300;
}
