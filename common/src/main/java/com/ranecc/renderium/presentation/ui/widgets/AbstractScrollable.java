// Renderium - 抽象可滚动容器基类
// 参考 Sodium 的 AbstractScrollable，使用中性命名

package com.ranecc.renderium.presentation.ui.widgets;

import com.ranecc.renderium.presentation.ui.util.Dim2i;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * 可滚动容器抽象基类。
 *
 * <p>继承 {@link AbstractParentWidget} 并集成滚动条功能，
 * 为需要滚动支持的容器组件提供统一的基础实现。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code AbstractScrollable} 设计，
 * 提供垂直/水平滚动的标准实现。
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>内置滚动条</b>：自动管理 {@link ScrollbarWidget} 实例</li>
 *   <li><b>滚轮支持</b>：自动将鼠标滚轮事件转换为滚动操作</li>
 *   <li><b>滚动偏移查询</b>：提供 getScrollAmount() 供子类使用</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>
 * public class MyScrollableList extends AbstractScrollable {
 *
 *     public MyScrollableList(Dim2i dim) {
 *         super(dim);
 *         // 初始化内容...
 *     }
 *
 *     &#64;Override
 *     public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
 *         // 启用裁剪区域
 *         graphics.enableScissor(getX(), getY(), getLimitX(), getLimitY());
 *
 *         // 渲染内容（子类根据 scrollAmount 调整 Y 坐标）
 *         renderContent(graphics, mouseX, mouseY, delta);
 *
 *         // 关闭裁剪
 *         graphics.disableScissor();
 *
 *         // 渲染滚动条
 *         this.scrollbar.extractRenderState(graphics, mouseX, mouseY, delta);
 *     }
 * }
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see AbstractParentWidget
 * @see ScrollbarWidget
 */
public abstract class AbstractScrollable extends AbstractParentWidget {

    /** 滚动条控件实例（由子类在初始化时赋值） */
    protected ScrollbarWidget scrollbar;

    /**
     * 创建可滚动容器实例。
     *
     * @param dim 容器的尺寸和位置信息
     */
    protected AbstractScrollable(Dim2i dim) {
        super(dim);
    }

    // ==================== 滚动查询方法 ====================

    /**
     * 获取当前滚动偏移量。
     *
     * <p>返回滚动条的当前值，
     * 子类在渲染内容时应使用此值调整元素位置。
     *
     * @return 滚动偏移量（像素），0 表示顶部
     */
    public int getScrollAmount() {
        return this.scrollbar.getScrollAmount();
    }

    // ==================== 滚轮事件处理 ====================

    /**
     * 处理鼠标滚轮事件。
     *
     * <p>自动将垂直滚轮事件转换为滚动操作。
     * 滚动灵敏度固定为每单位 10 像素。
     *
     * <h4>滚动方向</h4>
     * <ul>
     *   <li>向上滚动（amount &lt; 0）→ 内容向下移动（显示更上方的内容）</li>
     *   <li>向下滚动（amount &gt; 0）→ 内容向上移动（显示更下方的内容）</li>
     * </ul>
     *
     * @param mouseX           鼠标 X 坐标
     * @param mouseY           鼠标 Y 坐标
     * @param horizontalAmount 水平滚动量（通常为 0）
     * @param verticalAmount   垂直滚动量（正值向下，负值向上）
     * @return 始终返回 true（消费滚轮事件以阻止父级处理）
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        // 将滚轮事件转换为滚动操作（负号使方向符合直觉）
        this.scrollbar.scroll((int) (-verticalAmount * 10));
        return true;
    }
}
