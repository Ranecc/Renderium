// Renderium - 控件元素接口
// 定义选项控件的统一抽象

package com.ranecc.renderium.presentation.ui.widgets;

import com.ranecc.renderium.None;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * 选项控件元素的统一抽象接口。
 *
 * <p>定义了 OptionListWidget 中所有可选控件必须实现的契约，
 * 包括尺寸查询、渲染、交互检测等功能。
 *
 * <h2>设计目的</h2>
 * <p>提供统一的控件抽象层，使得 OptionListWidget 可以：
 * <ul>
 *   <li>混合管理不同类型的控件（开关、滑块、下拉框等）</li>
 *   <li>统一进行布局计算和渲染调度</li>
 *   <li>一致地处理鼠标和键盘交互</li>
 *   <li>支持焦点管理和无障碍访问</li>
 * </ul>
 *
 * <h2>实现类示例</h2>
 * <pre>
 * ControlElement 的典型实现包括：
 * ├── BooleanControlElement      （布尔开关控件）
 * ├── SliderControlElement       （数值滑块控件）
 * ├── EnumControlElement          （枚举选择控件）
 * ├── GroupHeaderControlElement   （分组标题控件）
 * └── PageHeaderControlElement    （页面标题控件）
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建自定义控件
 * public class MyCustomControl implements ControlElement {
 *     private final Dim2i dimensions;
 *     private boolean focused = false;
 *
 *     public MyCustomControl(Dim2i dim) {
 *         this.dimensions = dim;
 *     }
 *
 *     &#64;Override
 *     public Dim2i getDimensions() {
 *         return this.dimensions;
 *     }
 *
 *     &#64;Override
 *     public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
 *         // 绘制控件外观
 *         int color = this.focused ? Colors.THEME : Colors.FOREGROUND;
 *         graphics.fill(getX(), getY(), getLimitX(), getLimitY(), color);
 *     }
 *
 *     &#64;Override
 *     public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
 *         if (isMouseOver(event.x(), event.y())) {
 *             // 处理点击逻辑
 *             return true;
 *         }
 *         return false;
 *     }
 *
 *     // ... 实现其他方法
 * }
 * </pre>
 *
 * <h3>性能要求</h3>
 * <ul>
 *   <li>{@code render()} 方法执行时间 &lt; 0.5ms</li>
 *   <li>{@code isMouseOver()} 应使用简单数学运算避免复杂逻辑</li>
 *   <li>避免在渲染方法中分配内存</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see OptionListWidget
 * @see Dim2i
 */
public interface ControlElement {

    /**
     * 获取控件的尺寸和位置信息。
     *
     * <p>返回不可变的 Dim2i 对象，
     * 包含控件的 x、y 坐标以及宽度和高度。
     * 此信息用于布局计算和碰撞检测。
     *
     * @return Dim2i 尺寸对象（不能为 null）
     *
     * @h4>使用场景</h4>
     * <ul>
     *   <li>OptionListWidget 计算总内容高度</li>
     *   <li>碰撞检测判断鼠标是否在控件内</li>
     *   <li>滚动时确定控件是否在可视区域内</li>
     * </ul>
     */
    Dim2i getDimensions();

    /**
     * 渲染控件的外观。
     *
     * <p>在每帧渲染循环中被调用，
     * 负责绘制控件的完整视觉呈现。
     *
     * <h4>渲染注意事项</h4>
     * <ul>
     *   <li>使用传入的 GuiGraphicsExtractor 进行绘制</li>
     *   <li>坐标系统相对于屏幕原点（左上角）</li>
     *   <li>应根据 focused/hovered 状态调整视觉效果</li>
     *   <li>避免在此方法中修改状态（保持纯渲染）</li>
     * </ul>
     *
     * @param graphics 图形提取器（MC 26.2 API）
     * @param mouseX   当前鼠标 X 坐标（可用于悬停效果）
     * @param mouseY   当前鼠标 Y 坐标（可用于悬停效果）
     *
     * @h4>性能优化建议</h4>
     * <pre>
     * // 缓存常用值避免重复计算
     * int x = this.dim.x();
     * int y = this.dim.y();  // 注意：对于滚动列表，y 可能需要减去 scrollOffset
     *
     * // 批量绘制减少 API 调用
     * graphics.fill(x, y, x + width, y + height, bgColor);
     * graphics.text(font, text, textX, textY, textColor);
     * </pre>
     */
    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

    /**
     * 处理鼠标点击事件。
     *
     * <p>当鼠标按钮在控件区域内释放时调用。
     * 实现类应根据点击位置和按钮类型执行相应操作。
     *
     * <h4>事件参数说明</h4>
     * <ul>
     *   <li>event.button() → 鼠标按钮编号（0=左键，1=中键，2=右键）</li>
     *   <li>event.x() / event.y() → 点击位置的屏幕坐标</li>
     *   <li>doubleClick → 是否为双击事件</li>
     * </ul>
     *
     * @param event       鼠标按钮事件（包含按钮类型和坐标）
     * @param doubleClick 是否为双击操作
     * @return 如果事件被消费（已处理）返回 true，否则返回 false
     *
     * @h4>典型实现</h4>
     * <pre>
     * &#64;Override
     * public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
     *     // 仅响应左键点击
     *     if (event.button() != 0) {
     *         return false;
     *     }
     *
     *     // 检测是否点击在控件内
     *     if (!isMouseOver(event.x(), event.y())) {
     *         return false;
     *     }
     *
     *     // 处理点击逻辑（如切换状态、打开菜单等）
     *     this.toggleState();
     *     return true;  // 消费事件
     * }
     * </pre>
     */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick);

    /**
     * 检测指定坐标是否在控件区域内。
     *
     * <p>用于快速判断鼠标是否悬停在控件上，
     * 应使用高效的边界检查算法。
     *
     * <h4>坐标系说明</h4>
     * <p>使用屏幕绝对坐标（非相对坐标），
     * 对于滚动列表中的控件，应考虑滚动偏移量。
     *
     * @param mouseX 鼠标 X 坐标（屏幕坐标）
     * @param mouseY 鼠标 Y 坐标（屏幕坐标）
     * @return 如果坐标在控件区域内返回 true
     *
     * @h4>性能要求</h4>
     * <p>此方法可能每帧被多次调用（针对多个控件），
     * 因此应保证 O(1) 时间复杂度，仅使用简单的比较运算。
     *
     * @h4>参考实现</h4>
     * <pre>
     * &#64;Override
     * public boolean isMouseOver(int mouseX, int mouseY) {
     *     Dim2i dim = getDimensions();
     *     return mouseX >= dim.x() && mouseX < dim.getLimitX() &&
     *            mouseY >= dim.y() && mouseY < dim.getLimitY();
     * }
     * </pre>
     */
    boolean isMouseOver(int mouseX, int mouseY);

    /**
     * 检查控件是否拥有键盘焦点。
     *
     * <p>拥有焦点的控件会：
     * <ul>
     *   <li>接收键盘事件（keyPressed/keyReleased）</li>
     *   <li>显示焦点指示器（如边框高亮）</li>
     *   <li>被屏幕阅读器优先朗读</li>
     * </ul>
     *
     * @return 如果控件当前被聚焦返回 true
     */
    boolean isFocused();

    /**
     * 设置控件的焦点状态。
     *
     * <p>当容器组件（如 OptionListWidget）管理焦点切换时会调用此方法。
     * 实现类应在内部记录焦点状态，并在渲染时据此调整外观。
     *
     * @param focused 是否获得焦点（true=获得焦点，false=失去焦点）
     *
     * @h4>副作用</h4>
     * <ul>
     *   <li>获得焦点时可能需要显示光标或选中标记</li>
     *   <li>失去焦点时应保存未提交的编辑内容</li>
     *   <li>状态变化时应触发重绘请求</li>
     * </ul>
     */
    void setFocused(boolean focused);
}
