// Renderium - 扁平化按钮控件
// 参考 Sodium 的 FlatButtonWidget，使用中性命名

package com.ranecc.renderium.presentation.ui.widgets;

import com.ranecc.renderium.presentation.ui.ButtonTheme;
import com.ranecc.renderium.presentation.ui.Layout;
import com.ranecc.renderium.presentation.ui.util.Dim2i;
import com.ranecc.renderium.presentation.ui.widgets.options.Colors;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * 扁平化风格按钮控件。
 *
 * <p>提供类似 Sodium 的扁平化设计风格按钮，
 * 支持多种视觉变体和状态控制。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code FlatButtonWidget} 设计，
 * 使用 Renderium 中性命名和颜色方案。
 *
 * <h2>视觉特性</h2>
 * <ul>
 *   <li><b>扁平化设计</b>：无 3D 效果，使用纯色背景</li>
 *   <li><b>多状态支持</b>：normal/hovered/disabled/selected 四种状态</li>
 *   <li><b>可选边框</b>：支持在聚焦或强制模式下显示边框</li>
 *   <li><b>主题色系统</b>：通过 ButtonTheme 统一管理颜色配置</li>
 * </ul>
 *
 * <h2>状态矩阵</h2>
 * <table border="1">
 *   <tr>
 *     <th>状态组合</th>
 *     <th>背景色</th>
 *     <th>文本色</th>
 *     <th>边框</th>
 *   </tr>
 *   <tr>
 *     <td>Enabled + Normal</td>
 *     <td>BG_DEFAULT</td>
 *     <td>THEME</td>
 *     <td>无</td>
 *   </tr>
 *   <tr>
 *     <td>Enabled + Hovered</td>
 *     <td>BG_HIGHLIGHT</td>
 *     <td>THEME_LIGHTER</td>
 *     <td>无</td>
 *   </tr>
 *   <tr>
 *     <td>Disabled</td>
 *     <td>BG_INACTIVE</td>
 *     <td>THEME_DARKER</td>
 *     <td>无</td>
 *   </tr>
 *   <tr>
 *     <td>Selected</td>
 *     <td>(同 Normal)</td>
 *     <td>(同 Normal)</td>
 *     <td>底部主题色线</td>
 *   </tr>
 *   <tr>
 *     <td>Focused</td>
 *     <td>(同 Normal)</td>
 *     <td>(同 Normal)</td>
 *     <td>全边框</td>
 *   </tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 创建简单的扁平按钮
 * FlatButtonWidget button = new FlatButtonWidget(
 *     new Dim2i(x, y, 80, 20),
 *     Component.literal("Click Me"),
 *     () -&gt; System.out.println("Clicked!"),
 *     true,    // enabled
 *     true     // visible
 * );
 *
 * // 创建带自定义主题的按钮
 * ButtonTheme customTheme = new ButtonTheme(
 *     0xFFFF6B6B, 0xFFFF8E8E, 0xFFCC5555,  // 前景色（红色系）
 *     0xE0000000, 0x90000000, 0xB0000000   // 背景色
 * );
 *
 * FlatButtonWidget customButton = new FlatButtonWidget(
 *     dim,
 *     Component.literal("Delete"),
 *     this::deleteAction,
 *     true, true, true,   // drawBackground, drawFrame, leftAlign
 *     customTheme
 * );
 *
 * // 动态控制状态
 * button.setEnabled(hasPermission);   // 根据权限启用/禁用
 * button.setVisible(isUndoAvailable); // Undo 按钮仅在可撤销时显示
 * </pre>
 *
 * <h3>典型应用场景</h3>
 * <ul>
 *   <li>搜索框的清除按钮（× 图标）</li>
 *   <li>底部操作按钮（Apply/Undo/Done）</li>
 *   <li>导航栏中的页面切换按钮</li>
 *   <li>对话框中的确认/取消按钮</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see ButtonTheme
 * @see AbstractWidget
 */
public class FlatButtonWidget extends AbstractWidget implements Renderable {

    /**
     * 默认按钮主题（Renderium 青色调）。
     * <p>适用于大多数场景的标准配色方案。
     */
    public static final ButtonTheme DEFAULT_THEME = new ButtonTheme(
            Colors.FOREGROUND,           // 正常前景（白色）
            Colors.FOREGROUND,           // 悬停前景（白色）
            Colors.FOREGROUND_DISABLED,  // 禁用前景（灰色）
            Colors.BACKGROUND_HOVER,     // 悬停背景
            Colors.BACKGROUND_DEFAULT,   // 正常背景
            Colors.BACKGROUND_LIGHT      // 禁用背景
    );

    /** 点击时执行的操作 */
    private final Runnable action;

    /** 是否绘制背景矩形 */
    private final boolean drawBackground;

    /** 是否绘制边框 */
    private final boolean drawFrame;

    /** 文本是否左对齐（false=居中） */
    private final boolean leftAlign;

    /** 按钮颜色主题配置 */
    private final ButtonTheme theme;

    /** 按钮显示的文本（可为 null 表示无文本按钮） */
    private final Component label;

    /** 是否处于选中状态（如 Tab 选中项） */
    private boolean selected;

    /** 是否启用（禁用时变灰且不响应点击） */
    private boolean enabled = true;

    /** 是否可见（隐藏时不渲染且不响应事件） */
    private boolean visible = true;

    // ==================== 构造器 ====================

    /**
     * 完整参数构造器。
     *
     * @param dim           尺寸和位置信息
     * @param label         按钮文本（可为 null）
     * @param action        点击回调（可为 null 表示无操作）
     * @param drawBackground 是否绘制背景
     * @param drawFrame     是否绘制边框
     * @param leftAlign     文本对齐方式（true=左对齐，false=居中）
     * @param theme         颜色主题配置
     */
    public FlatButtonWidget(Dim2i dim, Component label, Runnable action,
                            boolean drawBackground, boolean drawFrame,
                            boolean leftAlign, ButtonTheme theme) {
        super(dim);
        this.label = label;
        this.action = action;
        this.drawBackground = drawBackground;
        this.drawFrame = drawFrame;
        this.leftAlign = leftAlign;
        this.theme = theme;
    }

    /**
     * 简化构造器（自动根据 drawBackground 设置 drawFrame）。
     *
     * @param dim           尺寸和位置
     * @param label         文本
     * @param action        回调
     * @param drawBackground 是否绘制背景
     * @param leftAlign     对齐方式
     * @param theme         主题
     */
    public FlatButtonWidget(Dim2i dim, Component label, Runnable action,
                            boolean drawBackground, boolean leftAlign, ButtonTheme theme) {
        this(dim, label, action, drawBackground, !drawBackground, leftAlign, theme);
    }

    /**
     * 使用默认主题的构造器。
     *
     * @param dim           尺寸和位置
     * @param label         文本
     * @param action        回调
     * @param drawBackground 是否绘制背景
     * @param leftAlign     对齐方式
     */
    public FlatButtonWidget(Dim2i dim, Component label, Runnable action,
                            boolean drawBackground, boolean leftAlign) {
        this(dim, label, action, drawBackground, leftAlign, DEFAULT_THEME);
    }

    /**
     * 自动推断 drawFrame 的构造器。
     *
     * @param dim           尺寸和位置
     * @param label         文本
     * @param action        回调
     * @param drawBackground 是否绘制背景
     * @param drawFrame     是否绘制边框
     * @param leftAlign     对齐方式
     */
    public FlatButtonWidget(Dim2i dim, Component label, Runnable action,
                            boolean drawBackground, boolean drawFrame, boolean leftAlign) {
        this(dim, label, action, drawBackground, drawFrame, leftAlign, DEFAULT_THEME);
    }

    // ==================== 状态控制方法 ====================

    /**
     * 设置按钮的启用状态。
     *
     * <p>禁用的按钮会：
     * <ul>
     *   <li>显示为灰色（使用 themeDarker 和 bgInactive 颜色）</li>
     *   <li>不响应鼠标点击</li>
     *   <li>不响应键盘操作</li>
     *   <li>不支持获得键盘焦点</li>
     * </ul>
     *
     * @param enabled true=启用（正常交互），false=禁用（灰显且不可交互）
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 设置按钮的可见性。
     *
     * <p>隐藏的按钮会：
     * <ul>
     *   <li>完全不渲染（不占用渲染时间）</li>
     *   <li>不响应鼠标点击（即使坐标匹配）</li>
     *   <li>不参与焦点导航</li>
     *   <li>保持内部状态不变（可随时重新显示）</li>
     * </ul>
     *
     * <h4>典型应用</h4>
     * <pre>
     * // Undo 按钮：仅在存在未保存修改时显示
     * undoButton.setVisible(hasPendingChanges);
     *
     * // 条件功能按钮：仅在满足前提条件时显示
     * advancedButton.setVisibility(userHasAdvancedMode);
     * </pre>
     *
     * @param visible true=可见（正常渲染），false=隐藏（完全不可见）
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * 设置选中状态。
     *
     * <p>选中的按钮会在底部显示主题色的指示线，
     * 用于标识当前激活的选项（如导航栏中的当前页面）。
     *
     * @param selected 是否选中
     */
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    // ==================== 状态查询方法 ====================

    /**
     * 查询按钮是否启用。
     *
     * @return true=启用且可交互，false=禁用
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * 查询按钮是否可见。
     *
     * @return true=可见且渲染，false=隐藏
     */
    public boolean isVisible() {
        return this.visible;
    }

    // ==================== 渲染方法 ====================

    /**
     * 渲染按钮外观。
     *
     * <p>按照以下顺序绘制各层：
     * <ol>
     *   <li>背景矩形（如果 drawBackground=true 且可见）</li>
     *   <li>文本标签（如果 label 不为 null）</li>
     *   <li>选中指示线（如果 selected=true 且 enabled）</li>
     *   <li>边框（如果 drawFrame=true 或 focused=true）</li>
     * </ol>
     *
     * @param graphics 图形提取器
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param delta    部分刻度时间
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 不可见时直接返回
        if (!this.visible) {
            return;
        }

        // 更新悬停状态
        this.hovered = this.isMouseOver(mouseX, mouseY);

        // 根据状态确定颜色
        int backgroundColor = this.enabled
                ? (this.hovered ? this.theme.bgHighlight : this.theme.bgDefault)
                : this.theme.bgInactive;

        int textColor = this.getTextColor();

        // 1. 绘制背景
        if (this.drawBackground) {
            this.drawRect(graphics, this.getX(), this.getY(),
                    this.getLimitX(), this.getLimitY(), backgroundColor);
        }

        // 2. 绘制文本
        if (this.label != null) {
            int strWidth = this.getStringWidth(this.label);
            int textX = this.leftAlign
                    ? this.getX() + Layout.TEXT_LEFT_PADDING
                    : (this.getCenterX() - (strWidth / 2));

            this.drawString(graphics, this.label,
                    textX,
                    this.getCenterY() - this.font.lineHeight / 2,
                    textColor);
        }

        // 3. 绘制选中指示线（底部）
        if (this.enabled && this.selected) {
            this.drawRect(graphics,
                    this.getX(), this.getLimitY() - 1,
                    this.getLimitX(), this.getLimitY(),
                    Colors.THEME);
        }

        // 4. 绘制边框（如果需要）
        if (this.drawFrame || (this.enabled && this.isFocused())) {
            this.drawBorder(graphics,
                    this.getX(), this.getY(),
                    this.getLimitX(), this.getLimitY(),
                    Colors.BUTTON_BORDER);
        }
    }

    /**
     * 获取当前状态的文本颜色。
     *
     * @return 启用时返回 themeLighter，禁用时返回 themeDarker
     */
    protected int getTextColor() {
        return this.enabled ? this.theme.themeLighter : this.theme.themeDarker;
    }

    // ==================== 事件处理 ====================

    /**
     * 处理鼠标点击事件。
     *
     * <p>仅在以下条件全部满足时响应：
     * <ul>
     *   <li>按钮已启用（enabled=true）</li>
     *   <li>按钮可见（visible=true）</li>
     *   <li>点击位置在按钮区域内</li>
     *   <li>鼠标左键（button=0）</li>
     * </ul>
     *
     * @param event       鼠标按钮事件
     * @param doubleClick 是否双击
     * @return 如果事件被消费返回 true
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.enabled || !this.visible) {
            return false;
        }

        if (event.button() == 0 && this.isMouseOver(event.x(), event.y())) {
            this.doAction();
            return true;
        }

        return false;
    }

    /**
     * 处理键盘按下事件。
     *
     * <p>仅在按钮拥有焦点且按下选择键（Space/Enter）时触发动作。
     *
     * @param event 键盘事件对象
     * @return 如果事件被消费返回 true
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.isFocused()) {
            return false;
        }

        if (event.isSelection()) {  // Space 或 Enter 键
            this.doAction();
            return true;
        }

        return false;
    }

    /**
     * 执行按钮的动作。
     *
     * <p>调用回调函数并播放点击音效。
     * 如果回调抛出异常会记录错误但不中断执行。
     */
    private void doAction() {
        if (this.action != null) {
            try {
                this.action.run();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("Renderium-FlatButton")
                        .error("Error executing button action", e);
            }
        }

        this.playClickSound();
    }

    // ==================== 焦点导航 ====================

    /**
     * 获取下一个焦点路径。
     *
     * <p>仅在按钮可用（enabled 且 visible）时允许获得焦点。
     *
     * @param event 焦点导航事件
     * @return 焦点路径或 null（如果不可用）
     */
    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
        if (!this.enabled || !this.visible) {
            return null;
        }
        return super.nextFocusPath(event);
    }
}
