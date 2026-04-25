// Renderium - 二维整数尺寸工具类
// 用于 UI 布局计算，提供不可变的矩形区域表示

package com.renderium.ui.util;

/**
 * 二维整数尺寸记录（Record）。
 *
 * <p>表示屏幕上的一个矩形区域，用于 UI 组件的定位和布局计算。
 * 采用 Java Record 实现不可变性，保证线程安全和值语义。
 *
 * <h2>设计参考</h2>
 * <p>参考 Sodium 的 {@code Dim2i} 设计，
 * 但使用 Renderium 中性命名，移除对 Sodium 特有类型的依赖。
 *
 * <h2>坐标系统</h2>
 * <pre>
 * (x, y) ┌──────────────┐ (getLimitX(), y)
 *        │              │
 *        │   内容区域    │ height
 *        │              │
 *        │              │
 * (x, getLimitY()) └──────────────┘ (getLimitX(), getLimitY())
 *                         width
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 创建内容区域
 * Dim2i contentArea = new Dim2i(100, 50, 400, 300);
 *
 * // 获取边界坐标
 * int rightEdge = contentArea.getLimitX();  // 500
 * int bottomEdge = contentArea.getLimitY(); // 350
 *
 * // 检测鼠标是否在区域内
 * if (contentArea.containsCursor(mouseX, mouseY)) {
 *     // 处理点击事件
 * }
 *
 * // 创建内边距后的新区域
 * Dim2i paddedArea = contentArea.inset(10, 10, 5, 5);
 * </pre>
 *
 * <h3>不可变性保证</h3>
 * <ul>
 *   <li>Record 字段自动为 final</li>
 *   <li>所有方法返回新实例或基本类型</li>
 *   <li>无副作用，适合并发场景</li>
 * </ul>
 *
 * @param x      左上角 X 坐标
 * @param y      左上角 Y 坐标
 * @param width  区域宽度（像素）
 * @param height 区域高度（像素）
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public record Dim2i(int x, int y, int width, int height) {

    /**
     * 获取右边缘 X 坐标。
     *
     * <p>等于 {@code x + width}，用于布局计算的右边界。
     *
     * @return 右边缘 X 坐标
     */
    public int getLimitX() {
        return this.x + this.width;
    }

    /**
     * 获取底部边缘 Y 坐标。
     *
     * <p>等于 {@code y + height}，用于布局计算的底边界。
     *
     * @return 底部边缘 Y 坐标
     */
    public int getLimitY() {
        return this.y + this.height;
    }

    /**
     * 检测指定坐标是否在此区域内。
     *
     * <p>使用左闭右开区间判断：[x, getLimitX()) × [y, getLimitY())。
     * 这与 Minecraft GUI 系统的惯例一致。
     *
     * @param px 待检测点的 X 坐标
     * @param py 待检测点的 Y 坐标
     * @return 如果点在区域内返回 true，否则返回 false
     */
    public boolean containsCursor(double px, double py) {
        return px >= this.x && px < this.getLimitX() && py >= this.y && py < this.getLimitY();
    }

    /**
     * 获取区域中心点 X 坐标。
     *
     * <p>用于居中对齐计算。使用整数除法，可能存在 1 像素的舍入误差。
     *
     * @return 中心 X 坐标
     */
    public int getCenterX() {
        return this.x + (this.width / 2);
    }

    /**
     * 获取区域中心点 Y 坐标。
     *
     * <p>用于居中对齐计算。使用整数除法，可能存在 1 像素的舍入误差。
     *
     * @return 中心 Y 坐标
     */
    public int getCenterY() {
        return this.y + (this.height / 2);
    }

    /**
     * 创建带有内边距的新区域。
     *
     * <p>从四个方向收缩当前区域，返回新的 Dim2i 实例。
     * 原实例保持不变（不可变性保证）。
     *
     * <h4>参数说明</h4>
     * <pre>
     * ┌─────────────────────────┐
     * │ ↑ top                   │
     * │ ← left      right →    │
     * │ ↓ bottom                 │
     * └─────────────────────────┘
     * </pre>
     *
     * @param left   左侧内边距（像素）
     * @param right  右侧内边距（像素）
     * @param top    顶部内边距（像素）
     * @param bottom 底部内边距（像素）
     * @return 收缩后的新区域
     */
    public Dim2i inset(int left, int right, int top, int bottom) {
        return new Dim2i(
            this.x + left,
            this.y + top,
            this.width - left - right,
            this.height - top - bottom
        );
    }

    /**
     * 在水平方向添加对称内边距。
     *
     * @param amount 左右侧边距（像素）
     * @return 收缩后的新区域
     */
    public Dim2i insetX(int amount) {
        return this.inset(amount, amount, 0, 0);
    }

    /**
     * 在垂直方向添加对称内边距。
     *
     * @param amount 上下侧边距（像素）
     * @return 收缩后的新区域
     */
    public Dim2i insetY(int amount) {
        return this.inset(0, 0, amount, amount);
    }

    /**
     * 仅在左侧添加内边距。
     *
     * @param amount 左侧边距（像素）
     * @return 收缩后的新区域
     */
    public Dim2i insetLeft(int amount) {
        return this.inset(amount, 0, 0, 0);
    }

    /**
     * 仅在右侧添加内边距。
     *
     * @param amount 右侧边距（像素）
     * @return 收缩后的新区域
     */
    public Dim2i insetRight(int amount) {
        return this.inset(0, amount, 0, 0);
    }

    /**
     * 仅在顶部添加内边距。
     *
     * @param amount 顶部边距（像素）
     * @return 收缩后的新区域
     */
    public Dim2i insetTop(int amount) {
        return this.inset(0, 0, amount, 0);
    }

    /**
     * 仅在底部添加内边距。
     *
     * @param amount 底部边距（像素）
     * @return 收缩后的新区域
     */
    public Dim2i insetBottom(int amount) {
        return this.inset(0, 0, 0, amount);
    }
}
