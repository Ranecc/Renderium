// Renderium - 抽象父组件基类
// 参考 Sodium 的 AbstractParentWidget，使用中性命名

package com.ranecc.renderium.presentation.ui.ui.widgets;

import com.ranecc.renderium.None;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽象父组件基类（支持子组件管理）。
 *
 * <p>继承 {@link AbstractWidget} 并实现 {@link ContainerEventHandler} 接口，
 * 提供子组件的添加、移除、渲染和事件分发功能。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code AbstractParentWidget} 设计，
 * 适配 Minecraft 26.2 的容器事件处理系统。
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>子组件管理</b>：支持添加/移除子组件</li>
 *   <li><b>渲染分发</b>：自动渲染所有可渲染的子组件</li>
 *   <li><b>焦点管理</b>：维护当前聚焦的子组件引用</li>
 *   <li><b>拖拽状态</b>：跟踪是否正在进行拖拽操作</li>
 * </ul>
 *
 * <h2>子组件类型</h2>
 * <pre>
 * AbstractParentWidget
 * ├── children: List&lt;GuiEventListener&gt;        （所有子组件，用于事件处理）
 * └── renderableChildren: List&lt;Renderable&gt;     （可渲染子组件，用于渲染）
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>
 * public class MyContainer extends AbstractParentWidget {
 *
 *     public MyContainer(Dim2i dim) {
 *         super(dim);
 *
 *         // 添加普通子组件（仅接收事件）
 *         addChild(someListener);
 *
 *         // 添加可渲染子组件（同时接收事件和渲染）
 *         addRenderableChild(myButton);
 *         addRenderableChild(myTextField);
 *     }
 *
 *     &#64;Override
 *     public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
 *         // 先绘制自定义背景
 *         drawRect(graphics, getX(), getY(), getLimitX(), getLimitY(), Colors.BACKGROUND_DEFAULT);
 *
 *         // 再渲染所有子组件
 *         super.extractRenderState(graphics, mouseX, mouseY, delta);
 *     }
 * }
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see AbstractWidget
 * @see ScrollbarWidget
 */
public abstract class AbstractParentWidget extends AbstractWidget implements ContainerEventHandler {

    /** 所有子组件列表（用于事件处理） */
    private final List<GuiEventListener> children = new ArrayList<>();

    /** 可渲染的子组件列表（用于渲染） */
    private final List<Renderable> renderableChildren = new ArrayList<>();

    /** 当前获得焦点的子组件 */
    private GuiEventListener focusedElement;

    /** 是否正在拖拽 */
    private boolean dragging;

    /**
     * 创建抽象父组件实例。
     *
     * @param dim 组件的尺寸和位置信息
     */
    protected AbstractParentWidget(Dim2i dim) {
        super(dim);
    }

    // ==================== 子组件管理方法 ====================

    /**
     * 添加子组件（仅用于事件监听）。
     *
     * <p>子组件会被添加到 children 列表，
     * 但不会自动渲染。适用于纯逻辑组件。
     *
     * @param <T>     子组件类型（必须实现 GuiEventListener）
     * @param element 要添加的子组件
     * @return 返回传入的子组件实例（支持链式调用）
     * @throws NullPointerException 若 element 为 null
     */
    protected <T extends GuiEventListener> T addChild(T element) {
        this.children.add(element);
        return element;
    }

    /**
     * 添加可渲染子组件。
     *
     * <p>子组件会同时被添加到 children 和 renderableChildren 列表，
     * 既参与事件处理也会被渲染。
     *
     * @param <T>     子组件类型（必须同时实现 GuiEventListener 和 Renderable）
     * @param element 要添加的可渲染子组件
     * @return 返回传入的子组件实例（支持链式调用）
     * @throws NullPointerException 若 element 为 null
     */
    protected <T extends GuiEventListener & Renderable> T addRenderableChild(T element) {
        this.children.add(element);
        this.renderableChildren.add(element);
        return element;
    }

    /**
     * 移除指定子组件。
     *
     * <p>同时从 children 和 renderableChildren 列表中移除。
     * 如果该子组件是当前焦点组件，会自动取消其焦点状态。
     *
     * @param element 要移除的子组件
     */
    protected void removeChild(GuiEventListener element) {
        this.children.remove(element);
        this.renderableChildren.remove(element);

        // 如果移除的是焦点组件，清除焦点
        if (this.focusedElement == element) {
            this.focusedElement = null;
        }
    }

    /**
     * 清空所有子组件。
     *
     * <p>同时清空 children 和 renderableChildren 列表，
     * 并清除焦点状态。
     */
    protected void clearChildren() {
        // 取消当前焦点组件的焦点
        if (this.focusedElement != null) {
            this.focusedElement.setFocused(false);
            this.focusedElement = null;
        }

        this.children.clear();
        this.renderableChildren.clear();
    }

    // ==================== 渲染方法 ====================

    /**
     * 渲染所有可渲染子组件。
     *
     * <p>按照添加顺序依次调用每个子组件的 extractRenderState 方法。
     * 子类可以在调用此方法前后绘制自定义内容以实现层叠效果。
     *
     * @param graphics 图形提取器
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     * @param delta    部分刻度时间
     */
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        for (Renderable element : this.renderableChildren) {
            element.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }

    // ==================== ContainerEventHandler 实现 ====================

    /**
     * 获取所有子组件列表。
     *
     * @return 子组件列表的不可变视图
     */
    @Override
    public @NonNull List<? extends GuiEventListener> children() {
        return this.children;
    }

    /**
     * 检查是否正在执行拖拽操作。
     *
     * @return 如果正在拖拽返回 true
     */
    @Override
    public boolean isDragging() {
        return this.dragging;
    }

    /**
     * 设置拖拽状态。
     *
     * @param dragging 是否正在拖拽
     */
    @Override
    public void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    /**
     * 获取当前获得焦点的子组件。
     *
     * @return 焦点子组件，如果没有则返回 null
     */
    @Override
    public @Nullable GuiEventListener getFocused() {
        return this.focusedElement;
    }

    /**
     * 设置焦点子组件。
     *
     * <p>会自动：
     * <ol>
     *   <li>取消旧焦点组件的焦点状态</li>
     *   <li>设置新焦点组件的焦点状态</li>
     *   <li>更新内部焦点引用</li>
     * </ol>
     *
     * @param guiEventListener 新的焦点组件（可为 null 表示无焦点）
     */
    @Override
    public void setFocused(@Nullable GuiEventListener guiEventListener) {
        // 取消旧焦点
        if (this.focusedElement != null) {
            this.focusedElement.setFocused(false);
        }

        // 设置新焦点
        if (guiEventListener != null) {
            guiEventListener.setFocused(true);
        }

        this.focusedElement = guiEventListener;
    }

    /**
     * 获取下一个焦点路径。
     *
     * <p>委托给 ContainerEventHandler 的默认实现，
     * 支持在子组件之间进行 Tab 键导航。
     *
     * @param event 焦点导航事件
     * @return 焦点路径或 null
     */
    @Override
    public @Nullable ComponentPath nextFocusPath(@NonNull FocusNavigationEvent event) {
        return ContainerEventHandler.super.nextFocusPath(event);
    }
}
