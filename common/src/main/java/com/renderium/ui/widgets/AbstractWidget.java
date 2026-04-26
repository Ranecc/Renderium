// Renderium - 抽象 UI 组件基类
// 参考 Sodium 的 AbstractWidget，使用中性命名

package com.renderium.ui.widgets;

import com.renderium.ui.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 抽象 UI 组件基类。
 *
 * <p>所有自定义 UI 控件的根类，提供通用的渲染、事件处理、
 * 焦点管理和尺寸查询等功能。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code AbstractWidget} 设计，
 * 适配 Minecraft 26.2 的 GUI 系统 API。
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>尺寸管理</b>：通过 {@link Dim2i} 管理组件位置和大小</li>
 *   <li><b>渲染辅助</b>：提供文本绘制、矩形填充、边框绘制等方法</li>
 *   <li><b>交互状态</b>：管理焦点（focused）和悬停（hovered）状态</li>
 *   <li><b>无障碍支持</b>：实现 NarratableEntry 接口</li>
 *   <li><b>音频反馈</b>：提供按钮点击音效播放方法</li>
 * </ul>
 *
 * <h2>接口实现</h2>
 * <pre>
 * AbstractWidget
 * ├── Renderable          → 支持渲染（extractRenderState）
 * ├── GuiEventListener    → 支持输入事件处理
 * └── NarratableEntry     → 支持屏幕阅读器无障碍访问
 * </pre>
 *
 * <h3>子类实现要求</h3>
 * <ul>
 *   <li>必须调用 {@code super(dim)} 构造器传入位置信息</li>
 *   <li>必须实现 {@link #extractRenderState(GuiGraphicsExtractor, int, int, float)} 方法</li>
 *   <li>建议重写鼠标/键盘事件处理方法以响应交互</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see Dim2i
 * @see AbstractParentWidget
 */
public abstract class AbstractWidget implements Renderable, GuiEventListener, NarratableEntry {

    /** Minecraft 字体实例（全局共享，MC 26.2 兼容） */
    @SuppressWarnings("deprecation")
    protected final Font font = getFontSafely();

    /**
     * 安全获取 Minecraft 字体实例（兼容 MC 26.2 API 变更）
     *
     * 【返回值】
     * @return Font - 字体实例，如果获取失败返回 null
     */
    private static Font getFontSafely() {
        try {
            return (Font) Minecraft.getInstance().getClass().getField("font").get(Minecraft.getInstance());
        } catch (Exception e) {
            return null;
        }
    }

    /** 组件的尺寸和位置信息（不可变） */
    private final Dim2i dim;

    /** 是否处于焦点状态 */
    protected boolean focused;

    /** 是否处于悬停状态（每帧更新） */
    protected boolean hovered;

    /**
     * 创建抽象组件实例。
     *
     * @param dim 组件的尺寸和位置信息（不能为 null）
     * @throws NullPointerException 若 dim 为 null
     */
    protected AbstractWidget(Dim2i dim) {
        this.dim = dim;
    }

    // ==================== 尺寸查询方法 ====================

    /**
     * 获取组件的尺寸和位置信息。
     *
     * @return Dim2i 对象（不可变，包含 x, y, width, height）
     */
    public Dim2i getDimensions() {
        return this.dim;
    }

    /** @return 左上角 X 坐标 */
    public int getX() { return this.dim.x(); }

    /** @return 左上角 Y 坐标 */
    public int getY() { return this.dim.y(); }

    /** @return 组件宽度（像素） */
    public int getWidth() { return this.dim.width(); }

    /** @return 组件高度（像素） */
    public int getHeight() { return this.dim.height(); }

    /** @return 右边缘 X 坐标（x + width） */
    public int getLimitX() { return this.dim.getLimitX(); }

    /** @return 底边缘 Y 坐标（y + height） */
    public int getLimitY() { return this.dim.getLimitY(); }

    /** @return 中心点 X 坐标 */
    public int getCenterX() { return this.dim.getCenterX(); }

    /** @return 中心点 Y 坐标 */
    public int getCenterY() { return this.dim.getCenterY(); }

    // ==================== 渲染辅助方法 ====================

    /**
     * 绘制左对齐文本字符串。
     *
     * @param graphics 图形提取器
     * @param text     要绘制的文本内容
     * @param x        文本起始 X 坐标
     * @param y        文本基线 Y 坐标
     * @param color    文本颜色（ARGB 格式）
     */
    protected void drawString(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
        graphics.text(this.font, text, x, y, color);
    }

    /**
     * 绘制左对齐文本组件。
     *
     * @param graphics 图形提取器
     * @param text     要绘制的文本组件（支持格式化）
     * @param x        文本起始 X 坐标
     * @param y        文本基线 Y 坐标
     * @param color    文本颜色（ARGB 格式）
     */
    protected void drawString(GuiGraphicsExtractor graphics, Component text, int x, int y, int color) {
        graphics.text(this.font, text, x, y, color);
    }

    /**
     * 绘制居中文本组件。
     *
     * @param graphics 图形提取器
     * @param text     要绘制的文本组件
     * @param x        文本中心点 X 坐标
     * @param y        文本中心点 Y 坐标
     * @param color    文本颜色（ARGB 格式）
     */
    protected void drawCenteredString(GuiGraphicsExtractor graphics, Component text, int x, int y, int color) {
        graphics.centeredText(this.font, text, x, y, color);
    }

    /**
     * 填充矩形区域。
     *
     * @param graphics 图形提取器
     * @param x1       左上角 X 坐标
     * @param y1       左上角 Y 坐标
     * @param x2       右下角 X 坐标（不包含）
     * @param y2       右下角 Y 坐标（不包含）
     * @param color    填充颜色（ARGB 格式）
     */
    protected void drawRect(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y2, color);
    }

    /**
     * 绘制矩形边框（1 像素宽）。
     *
     * @param graphics 图形提取器
     * @param x1       左上角 X 坐标
     * @param y1       左上角 Y 坐标
     * @param x2       右下角 X 坐标（不包含）
     * @param y2       右下角 Y 坐标（不包含）
     * @param color    边框颜色（ARGB 格式）
     */
    protected void drawBorder(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y1 + 1, color);           // 上边框
        graphics.fill(x1, y2 - 1, x2, y2, color);           // 下边框
        graphics.fill(x1, y1, x1 + 1, y2, color);           // 左边框
        graphics.fill(x2 - 1, y1, x2, y2, color);           // 右边框
    }

    /**
     * 计算文本渲染宽度。
     *
     * @param text 要测量的文本（支持 FormattedText 接口）
     * @return 文本的像素宽度
     */
    protected int getStringWidth(FormattedText text) {
        return this.font.width(text);
    }

    /**
     * 截断文本以适应指定宽度。
     *
     * <p>如果文本超出目标宽度，会自动添加 "..." 后缀，
     * 使用二分查找算法确定最大可显示字符数以保证性能。
     *
     * @param name        原始文本
     * @param targetWidth 最大允许宽度（像素）
     * @return 截断后的文本（可能带 "..." 后缀）
     */
    protected String truncateTextToFit(String name, int targetWidth) {
        var suffix = "...";
        var suffixWidth = this.font.width(suffix);
        var nameFontWidth = this.font.width(name);

        if (nameFontWidth <= targetWidth) {
            return name;
        }

        targetWidth -= suffixWidth;
        int maxLabelChars = name.length() - 3;
        int minLabelChars = 1;

        // 二分查找：确定最多能显示多少个字符
        while (maxLabelChars - minLabelChars > 1) {
            var mid = (maxLabelChars + minLabelChars) / 2;
            var midName = name.substring(0, mid);
            var midWidth = this.font.width(midName);

            if (midWidth > targetWidth) {
                maxLabelChars = mid;
            } else {
                minLabelChars = mid;
            }
        }

        return name.substring(0, minLabelChars).trim() + suffix;
    }

    // ==================== 音效方法 ====================

    /**
     * 播放 UI 按钮点击音效。
     *
     * <p>使用 Minecraft 标准的 UI_BUTTON_CLICK 音效，
     * 音量和音调均为默认值。
     */
    protected void playClickSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
    }

    // ==================== 状态查询方法 ====================

    /**
     * 检查组件是否处于悬停状态。
     *
     * @return 如果鼠标在组件区域内返回 true
     */
    public boolean isHovered() {
        return this.hovered;
    }

    // ==================== 焦点管理 ====================

    /**
     * 检查组件是否拥有键盘焦点。
     *
     * @return 如果组件被聚焦返回 true
     */
    @Override
    public boolean isFocused() {
        return this.focused;
    }

    /**
     * 设置组件的焦点状态。
     *
     * <p>只有通过键盘导航（Tab 或方向键）获取焦点时才会设置 focused=true，
     * 鼠标点击不会触发焦点变化（遵循 MC 26.2 的焦点策略）。
     *
     * @param focused 是否获得焦点
     */
    @Override
    public void setFocused(boolean focused) {
        if (!focused) {
            this.focused = false;
        } else {
            var inputType = Minecraft.getInstance().getLastInputType();

            // 仅允许通过键盘导航获取焦点
            if (inputType == net.minecraft.client.InputType.KEYBOARD_TAB ||
                inputType == net.minecraft.client.InputType.KEYBOARD_ARROW) {
                this.focused = true;
            }
        }
    }

    // ==================== 鼠标检测 ====================

    /**
     * 检测坐标是否在组件区域内。
     *
     * <p>使用左闭右开区间判断：
     * [x, getLimitX()) × [y, getLimitY())
     *
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 如果坐标在区域内返回 true
     */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= this.getX() && mouseX < this.getLimitX() &&
               mouseY >= this.getY() && mouseY < this.getLimitY();
    }

    // ==================== 无障碍支持 ====================

    /**
     * 获取组件的屏幕矩形区域（用于焦点导航）。
     *
     * @return 屏幕矩形对象
     */
    @Override
    public @NonNull ScreenRectangle getRectangle() {
        return new ScreenRectangle(this.getX(), this.getY(), this.getWidth(), this.getHeight());
    }

    /**
     * 获取叙述优先级。
     *
     * <p>根据当前状态返回适当的优先级：
     * <ul>
     *   <li>FOCUSED → 组件拥有键盘焦点</li>
     *   <li>HOVERED → 鼠标悬停在组件上</li>
     *   <li>NONE → 未聚焦也未悬停</li>
     * </ul>
     *
     * @return 叙述优先级枚举值
     */
    @Override
    public NarratableEntry.@NonNull NarrationPriority narrationPriority() {
        if (this.focused) {
            return NarratableEntry.NarrationPriority.FOCUSED;
        }
        if (this.hovered) {
            return NarratableEntry.NarrationPriority.HOVERED;
        }
        return NarratableEntry.NarrationPriority.NONE;
    }

    /**
     * 更新叙述内容（供屏幕阅读器读取）。
     *
     * @param builder 叙述构建器
     */
    @Override
    public void updateNarration(NarrationElementOutput builder) {
        if (this.focused) {
            builder.add(NarratedElementType.USAGE,
                    Component.translatable("narration.button.usage.focused"));
        } else if (this.hovered) {
            builder.add(NarratedElementType.USAGE,
                    Component.translatable("narration.button.usage.hovered"));
        }
    }

    /**
     * 获取下一个焦点路径。
     *
     * <p>当组件未聚焦时返回自身作为焦点候选，
     * 已聚焦时返回 null 以避免重复聚焦。
     *
     * @param event 焦点导航事件
     * @return 焦点路径或 null
     */
    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return !this.isFocused() ? ComponentPath.leaf(this) : null;
    }
}
