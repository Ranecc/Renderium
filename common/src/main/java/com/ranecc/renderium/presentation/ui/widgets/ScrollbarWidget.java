// Renderium - 滚动条控件
// 参考 Sodium 的 ScrollbarWidget，使用中性命名

package com.ranecc.renderium.presentation.ui.widgets;

import com.ranecc.renderium.None;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.IntConsumer;

/**
 * 可视化滚动条控件。
 *
 * <p>提供垂直或水平方向的滚动条 UI，
 * 支持鼠标拖拽、点击跳转、滚轮滚动等交互方式。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code ScrollbarWidget} 设计，
 * 使用 Renderium 中性命名和颜色方案。
 *
 * <h2>视觉特性</h2>
 * <ul>
 *   <li><b>自适应显示</b>：内容超出可视区域时才显示，否则隐藏</li>
 *   <li><b>渐隐效果</b>：停止滚动 1 秒后自动淡出（除非设置 alwaysShow）</li>
 *   <li><b>高亮滑块</b>：滑块颜色比轨道略亮，便于识别</li>
 * </ul>
 *
 * <h2>交互方式</h2>
 * <table border="1">
 *   <tr><th>操作</th><th>行为</th></tr>
 *   <tr><td>拖拽滑块</td><td>平滑滚动到目标位置</td></tr>
 *   <tr><td>点击轨道</td><td>向上/向下翻页</td></tr>
 *   <tr><td>滚轮</td><td>通过 scroll() 方法触发</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建垂直滚动条（带滚动变化回调）
 * Scrollbar scrollbar = new ScrollbarWidget(
 *     new Dim2i(x, y, width, height),
 *     scrollAmount -> updateContentPosition(scrollAmount)
 * );
 *
 * // 设置滚动上下文（可视区域大小 vs 总内容大小）
 * scrollbar.setScrollbarContext(300, 1000);  // 可见 300px，总共 1000px
 *
 * // 编程式滚动
 * scrollbar.scrollTo(500);  // 滚动到 500px 位置
 * scrollbar.scroll(10);     // 向下滚动 10 个单位
 *
 * // 查询当前状态
 * int currentScroll = scrollbar.getScrollAmount();
 * boolean canScroll = scrollbar.canScroll();
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see AbstractScrollable
 */
public class ScrollbarWidget extends AbstractWidget {

    /** 轨道背景色（半透明深灰） */
    private static final int TRACK_COLOR = ColorARGB.pack(50, 50, 50, 150);

    /** 滑块颜色（半透明浅灰） */
    private static final int HIGHLIGHT_COLOR = ColorARGB.pack(100, 100, 100, 150);

    /** 是否为水平方向（false = 垂直方向） */
    private final boolean horizontal;

    /** 是否始终显示（即使不滚动也可见） */
    private final boolean alwaysShow;

    /** 可见区域大小（像素） */
    private int visible;

    /** 总内容大小（像素） */
    private int total;

    /** 当前滚动偏移量（像素） */
    private int scrollAmount;

    /** 最后一次滚动的时间戳（毫秒），用于渐隐效果 */
    private long lastScrollTime;

    /** 是否正在拖拽滑块 */
    private boolean dragging;

    /** 滚动变化回调（可选） */
    private final IntConsumer onScrollChange;

    /**
     * 创建滚动条（带滚动变化回调）。
     *
     * @param dim           尺寸和位置
     * @param onScrollChange 滚动偏移变化时的回调（可为 null）
     */
    public ScrollbarWidget(Dim2i dim, IntConsumer onScrollChange) {
        this(dim, false, false, onScrollChange);
    }

    /**
     * 创建滚动条（不带回调）。
     *
     * @param dim        尺寸和位置
     * @param horizontal 是否为水平方向
     * @param alwaysShow 是否始终显示
     */
    public ScrollbarWidget(Dim2i dim, boolean horizontal, boolean alwaysShow) {
        this(dim, horizontal, alwaysShow, null);
    }

    /**
     * 完整参数构造器。
     *
     * @param dim           尺寸和位置
     * @param horizontal    是否为水平方向
     * @param alwaysShow    是否始终显示（忽略渐隐效果）
     * @param onScrollChange 滚动变化回调（可为 null）
     */
    public ScrollbarWidget(Dim2i dim, boolean horizontal, boolean alwaysShow, IntConsumer onScrollChange) {
        super(dim);
        this.horizontal = horizontal;
        this.alwaysShow = alwaysShow;
        this.onScrollChange = onScrollChange;
    }

    // ==================== 配置方法 ====================

    /**
     * 设置滚动条的上下文信息。
     *
     * <p>定义可视区域和总内容区域的大小，
     * 用于计算滑块的长度和位置范围。
     * 会自动将滚动偏移限制在有效范围内。
     *
     * @param visible 可见区域大小（像素）
     * @param total   总内容大小（像素）
     */
    public void setScrollbarContext(int visible, int total) {
        this.visible = visible;
        this.total = total;
        this.setScrollAndNotify(Math.max(0, Math.min(total - visible, this.scrollAmount)));
    }

    /**
     * 设置总内容大小（使用组件自身尺寸作为可见区域）。
     *
     * @param total 总内容大小（像素）
     */
    public void setScrollbarContext(int total) {
        this.setScrollbarContext(this.horizontal ? this.getWidth() : this.getHeight(), total);
    }

    // ==================== 状态查询方法 ====================

    /**
     * 检测是否可以滚动。
     *
     * <p>当总内容大小超过可见区域时返回 true。
     *
     * @return 如果可以滚动返回 true
     */
    public boolean canScroll() {
        return this.total > this.visible;
    }

    /**
     * 获取当前滚动偏移量。
     *
     * @return 滚动偏移量（像素，0 ~ total-visible）
     */
    public int getScrollAmount() {
        return this.scrollAmount;
    }

    // ==================== 滚动控制方法 ====================

    /**
     * 相对滚动指定的距离。
     *
     * <p>正值向下/向右滚动，负值向上/向左滚动。
     * 会自动限制在有效范围内。
     *
     * @param amount 滚动距离（像素）
     */
    public void scroll(int amount) {
        this.scrollTo(this.scrollAmount + amount);
    }

    /**
     * 绝对滚动到指定位置。
     *
     * <p>目标位置会被限制在 [0, total-visible] 范围内。
     * 只有实际发生变化时才会触发回调。
     *
     * @param target 目标滚动位置（像素）
     */
    public void scrollTo(int target) {
        if (this.setScrollAndNotify(Math.max(0, Math.min(this.total - this.visible, target)))) {
            this.lastScrollTime = System.currentTimeMillis();
        }
    }

    /**
     * 内部方法：设置滚动值并通知回调。
     *
     * @param newScrollAmount 新的滚动偏移量
     * @return 如果值发生了变化返回 true
     */
    private boolean setScrollAndNotify(int newScrollAmount) {
        if (newScrollAmount != this.scrollAmount) {
            this.scrollAmount = newScrollAmount;

            // 触发滚动变化回调
            if (this.onScrollChange != null) {
                this.onScrollChange.accept(this.scrollAmount);
            }

            return true;
        }
        return false;
    }

    // ==================== 渲染方法 ====================

    /**
     * 渲染滚动条。
     *
     * <p>根据以下条件决定是否显示：
     * <ul>
     *   <li>内容不可滚动 → 隐藏</li>
     *   <li>alwaysShow=true → 始终显示</li>
     *   <li>鼠标悬停 → 显示</li>
     *   <li>正在拖拽 → 显示</li>
     *   <li>最近 1 秒内有滚动 → 显示</li>
     *   <li>其他情况 → 渐隐</li>
     * </ul>
     *
     * @param graphics 图形提取器
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param delta    部分刻度时间
     */
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!this.canScroll()) {
            return;
        }

        boolean isMouseOver = this.isMouseOver(mouseX, mouseY);

        // 更新最后滚动时间（如果鼠标悬停）
        if (isMouseOver) {
            this.lastScrollTime = Math.max(this.lastScrollTime, System.currentTimeMillis() - 500);
        }

        long time = System.currentTimeMillis();
        long scrollTimeDiff = time - this.lastScrollTime;

        // 决定是否显示滚动条
        if (this.alwaysShow || isMouseOver || this.dragging || scrollTimeDiff < 1000) {
            // 绘制轨道背景
            graphics.fill(
                    this.getX(), this.getY(),
                    this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                    TRACK_COLOR
            );

            // 计算滑块的位置和大小
            int x1, y1, x2, y2;
            int length = this.horizontal ? this.getWidth() : this.getHeight();

            if (this.horizontal) {
                x1 = this.getX() + this.getHighlightStart(length);
                y1 = this.getY();
                x2 = x1 + this.getHighlightLength(length);
                y2 = y1 + this.getHeight();
            } else {
                x1 = this.getX();
                y1 = this.getY() + this.getHighlightStart(length);
                x2 = x1 + this.getWidth();
                y2 = y1 + this.getHighlightLength(length);
            }

            // 绘制滑块
            graphics.fill(x1, y1, x2, y2, HIGHLIGHT_COLOR);
        }
    }

    // ==================== 几何计算辅助方法 ====================

    /**
     * 计算滑块的起始位置（相对于轨道起点）。
     *
     * @param length 轨道总长度（像素）
     * @return 滑块起始偏移（像素）
     */
    private int getHighlightStart(int length) {
        return (int) Math.round(((double) this.scrollAmount / this.total) * length);
    }

    /**
     * 计算滑块的长度。
     *
     * <p>根据可见区域与总内容的比例动态计算，
     * 内容越多滑块越短。
     *
     * @param length 轨道总长度（像素）
     * @return 滑块长度（像素）
     */
    private int getHighlightLength(int length) {
        return (int) Math.round(((double) this.visible / this.total) * length);
    }

    /**
     * 检测坐标是否在滑块区域内。
     *
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 如果在滑块区域内返回 true
     */
    private boolean isMouseOverHighlight(double mouseX, double mouseY) {
        int x1, y1, x2, y2;
        int length = this.horizontal ? this.getWidth() : this.getHeight();

        if (this.horizontal) {
            x1 = this.getX() + this.getHighlightStart(length);
            y1 = this.getY();
            x2 = x1 + this.getHighlightLength(length);
            y2 = y1 + this.getHeight();
        } else {
            x1 = this.getX();
            y1 = this.getY() + this.getHighlightStart(length);
            x2 = x1 + this.getWidth();
            y2 = y1 + this.getHighlightLength(length);
        }

        return mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
    }

    // ==================== 鼠标事件处理 ====================

    /**
     * 处理鼠标按下事件。
     *
     * <p>支持两种交互方式：
     * <ul>
     *   <li>点击滑块 → 开始拖拽</li>
     *   <li>点击轨道 → 翻页滚动（向上/向下移动一个视口）</li>
     * </ul>
     *
     * @param event       鼠标按钮事件
     * @param doubleClick 是否双击
     * @return 如果事件被消费返回 true
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.isMouseOver(event.x(), event.y()) || !this.canScroll()) {
            return false;
        }

        if (this.isMouseOverHighlight(event.x(), event.y())) {
            // 点击滑块：开始拖拽
            this.dragging = true;
        } else {
            // 点击轨道：翻页滚动
            int viewportSize = this.horizontal ? this.getWidth() : this.getHeight();
            if (this.horizontal) {
                this.scroll(event.x() > this.getHighlightStart(this.getWidth()) ?
                        viewportSize : -viewportSize);
            } else {
                this.scroll(event.y() > this.getHighlightStart(this.getHeight()) ?
                        viewportSize : -viewportSize);
            }
        }

        return true;
    }

    /**
     * 处理鼠标释放事件。
     *
     * <p>结束拖拽状态并更新最后滚动时间（防止立即渐隐）。
     *
     * @param event 鼠标按钮事件
     * @return 始终返回 false（不消费此事件）
     */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.dragging = false;
        this.lastScrollTime = Math.max(this.lastScrollTime, System.currentTimeMillis() - 500);
        return false;
    }

    /**
     * 处理鼠标拖拽事件。
     *
     * <p>仅在拖拽状态下响应，根据鼠标移动量计算新的滚动位置。
     * 水平滚动条使用 deltaX，垂直滚动条使用 deltaY。
     *
     * @param event   鼠标按钮事件
     * @param deltaX  X 方向移动量
     * @param deltaY  Y 方向移动量
     * @return 如果正在拖拽返回 true
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.dragging) {
            // 根据滚动比例转换鼠标移动量为滚动量
            double scrollRatio = (double) this.total / this.visible;
            this.scroll((int) Math.round(this.horizontal ? deltaX : deltaY * scrollRatio));
            return true;
        }
        return false;
    }

    // ==================== 无障碍支持 ====================

    /**
     * 滚动条不需要叙述（空实现）。
     *
     * @param builder 叙述构建器
     */
    @Override
    public void updateNarration(NarrationElementOutput builder) {
        // 滚动条不提供叙述信息
    }

    /**
     * 滚动条不支持键盘焦点导航。
     *
     * @param event 焦点导航事件
     * @return 始终返回 null
     */
    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return null;
    }
}
