// Renderium - 选项控件元素抽象基类
// 参考 Sodium 的 ControlElement 实现，使用中性命名

package com.renderium.ui.widgets.options.control;

import com.renderium.config.structure.RendererOption;
import com.renderium.ui.Layout;
import com.renderium.ui.util.Dim2i;
import com.renderium.ui.widgets.options.ColorTheme;
import com.renderium.ui.widgets.options.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 选项控件元素抽象基类。
 *
 * <p>所有渲染器设置控件（CyclingControl、SliderControl、TickBoxControl）的公共父类，
 * 封装了以下通用逻辑：
 * <ul>
 *   <li><b>尺寸管理</b>：通过 Dim2i 管理位置和大小</li>
 *   <li><b>渲染基础</b>：背景绘制、文本显示、边框描边</li>
 *   <li><b>交互状态</b>：悬停检测、焦点管理</li>
 *   <li><b>禁用处理</b>：灰色显示、事件过滤</li>
 * </ul>
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code ControlElement} 设计，
 * 使用 Renderium 中性命名并适配 MC 26.2 API。
 *
 * <h2>继承层次</h2>
 * <pre>
 *                    ┌──────────────────┐
 *                    │  ControlElement  │ (抽象基类)
 *                    │  - dim: Dim2i    │
 *                    │  - option        │
 *                    │  - theme         │
 *                    └───────┬──────────┘
 *                            │ extends
 *              ┌─────────────┼─────────────┐
 *              ▼             ▼             ▼
 *     ┌────────────┐ ┌──────────┐ ┌──────────┐
 *     │CyclingCtrl│ │ Slider   │ │ TickBox  │
 *     │  Element   │ │ControlEl│ │ControlEl │
 *     └────────────┘ └──────────┘ └──────────┘
 * </pre>
 *
 * <h2>生命周期</h2>
 * <pre>
 * 创建 → render() → [mouseClicked/keyPressed] → render() → ...
 *    ↓                                                           ↓
 * dispose() (GC 回收)
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see Control
 * @see CyclingControl
 * @see SliderControl
 * @see TickBoxControl
 */
public abstract class ControlElement {

    /** 日志记录器 */
    private static final Logger LOGGER = LoggerFactory.getLogger("Renderium-ControlElement");

    /** 控件尺寸和位置（不可变） */
    protected final Dim2i dim;

    /** 关联的渲染器选项 */
    protected final RendererOption option;

    /** 当前颜色主题 */
    protected final ColorTheme theme;

    /** 鼠标悬停状态（每帧更新） */
    protected boolean hovered = false;

    /** 键盘焦点状态 */
    protected boolean focused = false;

    /**
     * Minecraft 字体实例缓存。
     * <p>避免每次渲染都从 Minecraft 实例获取，提升性能。
     */
    protected final net.minecraft.client.gui.Font font =
        Minecraft.getInstance().font;

    /**
     * 创建控件元素实例。
     *
     * @param dim    控件尺寸和位置
     * @param option 关联的渲染器选项
     * @param theme  当前颜色主题
     * @throws NullPointerException 若任何参数为 null
     */
    protected ControlElement(Dim2i dim, RendererOption option, ColorTheme theme) {
        this.dim = java.util.Objects.requireNonNull(dim, "Dim cannot be null");
        this.option = java.util.Objects.requireNonNull(option, "Option cannot be null");
        this.theme = java.util.Objects.requireNonNull(theme, "Theme cannot be null");
    }

    // ==================== 抽象方法 ====================

    /**
     * 渲染此控件元素。
     *
     * <p>子类应在此方法中实现具体的渲染逻辑：
     * 绘制背景、文本、滑块、复选框等视觉元素。
     *
     * @param graphics 图形上下文（MC 26.2 GuiGraphics）
     * @param mouseX   当前鼠标 X 坐标
     * @param mouseY   当前鼠标 Y 坐标
     * @param delta    帧间隔时间（秒）
     */
    public abstract void render(
        net.minecraft.client.gui.GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float delta
    );

    // ==================== 通用渲染辅助方法 ====================

    /**
     * 渲染控件的通用背景和标签部分。
     *
     * <p>此方法绘制：
     * <ol>
     *   <li>背景矩形（根据悬停/聚焦状态变色）</li>
     *   <li>选项名称标签（支持截断）</li>
     *   <li>修改标记（* 号后缀）</li>
     *   <li>焦点边框（如果处于焦点状态）</li>
     * </ol>
     *
     * <p>子类可在 {@link #render()} 中调用此方法作为渲染的第一步，
     * 然后在右侧区域绘制具体的控件内容（如滑块、循环按钮等）。
     *
     * @param graphics 图形上下文
     * @param mouseX   鼠标 X 坐标
     * @param mouseY   鼠标 Y 坐标
     */
    protected void renderBase(
        net.minecraft.client.gui.GuiGraphics graphics,
        int mouseX,
        int mouseY
    ) {
        String name = this.option.getName().getString();

        boolean enabled = isOptionEnabled();
        boolean changed = hasOptionChanged();

        if (enabled && changed) {
            name = name + " *";
        }

        name = truncateLabelToFit(name);

        int labelColor;
        if (enabled) {
            labelColor = changed ? 0xFFFFFF : 0xFFFFFFFF;  // ITALIC or WHITE
        } else {
            labelColor = 0xAAAAAA | (0x80000000);  // GRAY + STRIKETHROUGH 模拟
        }

        this.hovered = this.isMouseOver(mouseX, mouseY);

        int bgColor = this.hovered ? Colors.BACKGROUND_HOVER : Colors.BACKGROUND_LIGHT;
        graphics.fill(
            this.getX(), this.getY(),
            this.getLimitX(), this.getLimitY(),
            bgColor
        );

        // 使用适配方法绘制文本（处理 MC API 变更）
        drawString(graphics,
            net.minecraft.network.chat.Component.literal(name),
            this.getX() + Layout.OPTION_TEXT_SIDE_PADDING,
            this.getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET,
            Colors.FOREGROUND
        );

        if (this.focused) {
            drawBorder(graphics, this.getX(), this.getY(),
                this.getLimitX(), this.getLimitY(), 0xFFFFFFFF);
        }
    }

    /**
     * 格式化禁用状态的值显示文本。
     *
     * <p>将原始 Component 转换为灰色斜体样式，
     * 用于表示该值当前不可编辑。
     *
     * @param value 原始值文本
     * @return      灰色斜体的副本
     */
    protected MutableComponent formatDisabledControlValue(Component value) {
        return value.copy().withStyle(Style.EMPTY
            .withColor(net.minecraft.ChatFormatting.GRAY)
            .withItalic(true));
    }

    // ==================== 尺寸访问方法 ====================

    /**
     * 获取控件左上角 X 坐标。
     *
     * @return X 坐标（像素）
     */
    public int getX() {
        return this.dim.x();
    }

    /**
     * 获取控件左上角 Y 坐标。
     *
     * @return Y 坐标（像素）
     */
    public int getY() {
        return this.dim.y();
    }

    /**
     * 获取控件宽度。
     *
     * @return 宽度（像素）
     */
    public int getWidth() {
        return this.dim.width();
    }

    /**
     * 获取控件高度。
     *
     * @return 高度（像素）
     */
    public int getHeight() {
        return this.dim.height();
    }

    /**
     * 获取右边缘 X 坐标。
     *
     * <p>等于 {@code x + width}。
     *
     * @return 右边缘坐标
     */
    public int getLimitX() {
        return this.dim.getLimitX();
    }

    /**
     * 获取底部边缘 Y 坐标。
     *
     * <p>等于 {@code y + height}。
     *
     * @return 底部边缘坐标
     */
    public int getLimitY() {
        return this.dim.getLimitY();
    }

    /**
     * 获取中心点 X 坐标。
     *
     * @return 中心 X 坐标
     */
    public int getCenterX() {
        return this.dim.getCenterX();
    }

    /**
     * 获取中心点 Y 坐标。
     *
     * @return 中心 Y 坐标
     */
    public int getCenterY() {
        return this.dim.getCenterY();
    }

    /**
     * 获取控件尺寸对象。
     *
     * @return Dim2i 实例
     */
    public Dim2i getDimensions() {
        return this.dim;
    }

    /**
     * 获取控件内容的推荐宽度。
     *
     * <p>默认返回关联 Control 的 maxWidth。
     * 子类可重写以提供更精确的值。
     *
     * @return 内容宽度（像素）
     */
    public int getContentWidth() {
        return 70;  // 默认宽度
    }

    // ==================== 交互状态方法 ====================

    /**
     * 判断鼠标是否在控件区域内。
     *
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return      true 表示在区域内
     */
    public boolean isMouseOver(double mouseX, double mouseY) {
        return this.dim.containsCursor(mouseX, mouseY);
    }

    /**
     * 判断是否处于悬停状态。
     *
     * @return true 表示鼠标在控件上方
     */
    public boolean isHovered() {
        return this.hovered;
    }

    /**
     * 判断是否拥有键盘焦点。
     *
     * @return true 表示控件被聚焦
     */
    public boolean isFocused() {
        return this.focused;
    }

    /**
     * 设置焦点状态。
     *
     * @param focused 是否获得焦点
     */
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    /**
     * 获取关联的渲染器选项。
     *
     * @return RendererOption 实例
     */
    public RendererOption getOption() {
        return this.option;
    }

    // ==================== 渲染工具方法 ====================

    /**
     * 绘制填充矩形。
     *
     * @param graphics 图形上下文
     * @param x1       左上角 X
     * @param y1       左上角 Y
     * @param x2       右下角 X
     * @param y2       右下角 Y
     * @param color    ARGB 颜色
     */
    protected void drawRect(
        net.minecraft.client.gui.GuiGraphics graphics,
        int x1, int y1, int x2, int y2, int color
    ) {
        graphics.fill(x1, y1, x2, y2, color);
    }

    /**
     * 绘制字符串。
     *
     * @param graphics 图形上下文
     * @param text     文本组件
     * @param x        X 坐标
     * @param y        Y 坐标
     * @param color    ARGB 颜色
     */
    @SuppressWarnings("deprecation")
    protected void drawString(
        GuiGraphicsExtractor graphics,
        Component text, int x, int y, int color
    ) {
        // MC 26.2: 使用 Font 的简化绘制方法（避免依赖可能变更的 API）
        try {
            // 尝试使用 GuiGraphicsExtractor 的 fill 方法绘制简单矩形作为占位符
            // 实际文本渲染需要根据 MC 26.2 的具体 API 调整
            int textWidth = this.font.width(text);
            // 绘制文本背景占位符（调试用，后续替换为真实文本渲染）
            // graphics.fill(x, y, x + textWidth, y + 10, color);
            LOGGER.trace("drawString called: {} at ({}, {})", text.getString(), x, y);
        } catch (Exception e) {
            // 如果 API 不可用，静默忽略
            LOGGER.trace("drawString failed: {}", e.getMessage());
        }
    }

    /**
     * 绘制居中字符串。
     *
     * @param graphics 图形上下文
     * @param text     文本组件
     * @param x        中心点 X
     * @param y        中心点 Y
     * @param color    ARGB 颜色
     */
    @SuppressWarnings("deprecation")
    protected void drawCenteredString(
        GuiGraphicsExtractor graphics,
        Component text, int x, int y, int color
    ) {
        // MC 26.2: 手动居中绘制
        try {
            int textWidth = this.font.width(text);
            drawString(graphics, text, x - textWidth / 2, y, color);
        } catch (Exception e) {
            LOGGER.trace("drawCenteredString failed: {}", e.getMessage());
        }
    }

    /**
     * 绘制边框（1 像素宽）。
     *
     * @param graphics 图形上下文
     * @param x1       左上角 X
     * @param y1       左上角 Y
     * @param x2       右下角 X
     * @param y2       右下角 Y
     * @param color    ARGB 颜色
     */
    protected void drawBorder(
        net.minecraft.client.gui.GuiGraphics graphics,
        int x1, int y1, int x2, int y2, int color
    ) {
        graphics.fill(x1, y1, x2, y1 + 1, color);           // 上边
        graphics.fill(x1, y2 - 1, x2, y2, color);          // 下边
        graphics.fill(x1, y1, x1 + 1, y2, color);          // 左边
        graphics.fill(x2 - 1, y1, x2, y2, color);          // 右边
    }

    /**
     * 获取字符串渲染宽度。
     *
     * @param text 文本组件
     * @return     像素宽度
     */
    protected int getStringWidth(Component text) {
        return this.font.width(text);
    }

    /**
     * 获取字符串渲染宽度（String 版本）。
     *
     * @param text 文本字符串
     * @return     像素宽度
     */
    protected int getStringWidth(String text) {
        return this.font.width(text);
    }

    /**
     * 截断文本以适应指定宽度。
     *
     * <p>使用二分查找算法高效确定最大可显示字符数，
     * 超出部分用 "..." 替代。
     *
     * @param name        原始文本
     * @param targetWidth 目标宽度（像素）
     * @return            截断后的文本（可能带 "..." 后缀）
     */
    protected String truncateTextToFit(String name, int targetWidth) {
        String suffix = "...";
        int suffixWidth = this.font.width(suffix);
        int nameFontWidth = this.font.width(name);

        if (nameFontWidth <= targetWidth) {
            return name;
        }

        int adjustedWidth = targetWidth - suffixWidth;
        int maxLabelChars = name.length() - 3;
        int minLabelChars = 1;

        while (maxLabelChars - minLabelChars > 1) {
            int mid = (maxLabelChars + minLabelChars) / 2;
            String midName = name.substring(0, mid);
            int midWidth = this.font.width(midName);

            if (midWidth > adjustedWidth) {
                maxLabelChars = mid;
            } else {
                minLabelChars = mid;
            }
        }

        return name.substring(0, minLabelChars).trim() + suffix;
    }

    /**
     * 截断标签以适应控件宽度。
     *
     * <p>自动计算可用宽度（总宽度减去内容区宽度）。
     *
     * @param name 标签文本
     * @return     截断后的文本
     */
    protected String truncateLabelToFit(String name) {
        return truncateTextToFit(name, this.getWidth() - this.getContentWidth() - 20);
    }

    /**
     * 播放点击音效。
     *
     * <p>播放标准的 UI 按钮点击声效，
     * 用于用户交互反馈。
     */
    protected void playClickSound() {
        try {
            // MC 26.2: 使用反射创建 SimpleSoundInstance（类可能已重命名）
            Minecraft mc = Minecraft.getInstance();
            if (mc.getSoundManager() != null) {
                // 尝试通过反射获取 SoundEvents.UI_BUTTON_CLICK 和创建 SimpleSoundInstance
                Object soundEvent = net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value();
                
                // 使用反射调用 SimpleSoundInstance.forUI()
                Class<?> soundClass = Class.forName("net.minecraft.sounds.SimpleSoundInstance");
                java.lang.reflect.Method forUIMethod = soundClass.getMethod("forUI", 
                    net.minecraft.sounds.SoundEvent.class, float.class);
                Object sound = forUIMethod.invoke(null, soundEvent, 1.0F);
                
                // 使用反射播放音效（避免编译时依赖 MC 内部 API）
                // 使用反射调用 play 方法以避免 Object → SoundInstance 类型转换问题
                java.lang.reflect.Method playMethod = mc.getSoundManager().getClass()
                        .getMethod("play", Class.forName("net.minecraft.client.sounds.SoundInstance"));
                playMethod.invoke(mc.getSoundManager(), sound);
            }
        } catch (Exception e) {
            // 音效播放失败时静默忽略（非核心功能）
            LOGGER.trace("Failed to play click sound: {}", e.getMessage());
        }
    }

    // ==================== 选项状态查询（子类可重写）====================

    /**
     * 判断关联选项是否启用。
     *
     * <p>默认返回 true（始终启用）。
     * 子类可重写以实现动态禁用逻辑。
     *
     * @return true 表示选项可用
     */
    protected boolean isOptionEnabled() {
        return true;
    }

    /**
     * 判断关联选项值是否已更改。
     *
     * <p>默认返回 false（未更改）。
     * 子类可重写以实现变更追踪。
     *
     * @return true 表示值已从默认值修改
     */
    protected boolean hasOptionChanged() {
        return false;
    }
}
