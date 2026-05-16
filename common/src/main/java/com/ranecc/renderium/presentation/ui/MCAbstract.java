package com.ranecc.renderium.presentation.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public final class MCAbstract {

    private MCAbstract() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 创建 Minecraft Component 文本
     *
     * @param text 文本内容
     * @return Component 实例
     */
    public static Component text(String text) {
        return Component.literal(text);
    }

    /**
     * 获取文本宽度（像素）
     *
     * @param text 文本内容
     * @return 文本宽度
     */
    public static int textWidth(String text) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        return font != null ? font.width(text) : text.length() * 6;
    }

    /**
     * 创建按钮构建器
     *
     * @param x      X 坐标
     * @param y      Y 坐标
     * @param width  按钮宽度
     * @param height 按钮高度
     * @return ButtonBuilder 实例
     */
    public static ButtonBuilder buttonBuilder(int x, int y, int width, int height) {
        return new ButtonBuilder(x, y, width, height);
    }

    /**
     * 按钮构建器（链式调用）
     */
    public static final class ButtonBuilder {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private Component text;
        private Button.OnPress onPress;

        private ButtonBuilder(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /**
         * 设置按钮文本
         *
         * @param text 文本内容
         * @return 构建器实例
         */
        public ButtonBuilder text(String text) {
            this.text = Component.literal(text);
            return this;
        }

        /**
         * 设置按钮文本（Component）
         *
         * @param text 文本组件
         * @return 构建器实例
         */
        public ButtonBuilder text(Component text) {
            this.text = text;
            return this;
        }

        /**
         * 设置点击回调
         *
         * @param onClick 点击回调
         * @return 构建器实例
         */
        public ButtonBuilder onClick(Button.OnPress onClick) {
            this.onPress = onClick;
            return this;
        }

        /**
         * 构建 Button 实例
         *
         * @return Button 实例
         */
        public Button build() {
            return Button.builder(text != null ? text : Component.empty(), onPress != null ? onPress : btn -> {})
                    .bounds(x, y, width, height)
                    .build();
        }
    }
}
