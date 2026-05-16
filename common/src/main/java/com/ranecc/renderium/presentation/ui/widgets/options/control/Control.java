// Renderium - 选项控件接口
// 定义选项控件的工厂契约

package com.ranecc.renderium.presentation.ui.widgets.options.control;

import com.ranecc.renderium.infrastructure.config.structure.RendererOption;
import com.ranecc.renderium.presentation.ui.util.Dim2i;
import com.ranecc.renderium.presentation.ui.widgets.options.ColorTheme;

/**
 * 选项控件工厂接口。
 *
 * <p>定义创建 {@link ControlElement} 实例的工厂契约。
 * 每个 RendererOption 都关联一个 Control 实现，
 * 由 Control 负责创建对应的 UI 控件元素。
 *
 * <h2>设计模式</h2>
 * <p>采用<strong>抽象工厂模式</strong>：
 * <pre>
 * RendererOption ──持有──→ Control ──创建──→ ControlElement
 *      ↑                      ↑                    ↑
 *   BooleanOption         CyclingControl     CyclingControlElement
 *   IntegerOption         SliderControl       SliderControlElement
 *   EnumOption            CyclingControl     CyclingControlElement
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 创建控件
 * Control control = option.getControl();
 * ControlElement element = control.createElement(screen, list, dim, theme);
 *
 * // 添加到界面
 * list.addChild(element);
 * </pre>
 *
 * <h2>线程安全性</h2>
 * <p>实现类应保证 {@link #createElement()} 的线程安全。
 * 通常在 GUI 线程中调用，无需额外同步。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see ControlElement
 * @see RendererOption
 */
public interface Control {

    /**
     * 获取此控件关联的渲染器选项。
     *
     * @return 关联的 RendererOption 实例
     */
    RendererOption getOption();

    /**
     * 创建控件元素实例。
     *
     * <p>根据给定的布局参数和主题配置，
     * 创建可在屏幕上渲染和交互的 ControlElement 实例。
     *
     * @param screen 所属屏幕（用于访问 Minecraft 实例等）
     * @param list   选项列表容器（用于滚动偏移计算、事件冒泡等）
     * @param dim    控件尺寸和位置
     * @param theme  当前颜色主题
     * @return       新创建的 ControlElement 实例
     */
    ControlElement createElement(
        Object screen,
        Object list,
        Dim2i dim,
        ColorTheme theme
    );

    /**
     * 获取此控件的最大推荐宽度。
     *
     * <p>用于布局引擎计算列宽。
     * 返回值为像素单位。
     *
     * @return 最大推荐宽度（像素），如果不确定则返回 0
     */
    int getMaxWidth();
}
