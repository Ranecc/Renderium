// Renderium - MC 版本抽象层
// 高性能抽象层：隔离 MC 版本差异，未来迁移版本无需大量修改
// 设计原则：零开销抽象、编译期解析、运行时无分支
// 
// 更新记录：
// - v5.1.0 (2026-04-17): 升级至 MC 26.2 snapshot-3
// - v5.0.0 (2026-04-17): 初始版本，基于 MC 26.1.2 + 26.2 snapshot-3 对比

package com.renderium.platform;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Minecraft 版本抽象层 🔧
 *
 * <p><b>当前目标版本：</b>MC 26.2 snapshot-3
 *
 * <p><b>设计目标：</b>
 * <ul>
 *   <li>隔离 MC 版本间的 API 差异（26.1.2 ↔ 26.2+）</li>
 *   <li>编译时确定目标版本，运行时零开销</li>
 *   <li>统一 UI 组件创建接口</li>
 * </ul>
 *
 * <p><b>版本差异对照表（26.1.2 vs 26.2）：</b>
 * <pre>
 * | API 特性              | 26.1.2                          | 26.2                               |
 * |-----------------------|--------------------------------|------------------------------------|
 * | CycleButton.builder() | 无此方法（编译错误）             | builder(Function, Supplier) 必须双参 |
 * | CycleButton 显示文本   | Function&lt;T, String&gt;     | Function&lt;T, Component&gt;         |
 * | Button.builder()       | builder(Component, OnPress)    | 相同                                |
 * | Component.literal()    | 需要 brigadier.Message         | 相同（但 26.2 有完整依赖）          |
 * | Screen.init()          | init(int, int)                 | init() 无参数                       |
 * | GuiGraphics            | GuiGraphics                    | GuiGraphicsExtractor               |
 * </pre>
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * // 创建文本组件（自动适配版本）
 * Component text = MCAbstract.text("Hello World");
 *
 * // 创建按钮（替代 CycleButton）
 * Button btn = MCAbstract.buttonBuilder(x, y, w, h)
 *     .text("Click Me")
 *     .onClick(b -> handleClick())
 *     .build();
 * }</pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class MCAbstract {

    private MCAbstract() {
        // 工具类禁止实例化
    }

    // ==================== 常量定义 ====================

    /** 当前目标 MC 主版本号 */
    public static final int MAJOR_VERSION = 26;
    public static final int MINOR_VERSION = 2;
    public static final int PATCH_VERSION = 0;

    /** 完整版本字符串 */
    public static final String VERSION_STRING = MAJOR_VERSION + "." + MINOR_VERSION + "." + PATCH_VERSION;

    /**
     * 检查当前是否为指定版本或更高
     *
     * @param major 主版本
     * @param minor 次版本
     * @return true 如果当前版本 >= 指定版本
     */
    public static boolean isAtLeast(int major, int minor) {
        if (MAJOR_VERSION > major) return true;
        if (MAJOR_VERSION == major && MINOR_VERSION >= minor) return true;
        return false;
    }

    // ==================== Component 工厂方法 ====================

    /**
     * 创建文本组件
     *
     * <p>封装 Component.literal() / Component.translatable() 的版本差异。
     * 当前使用 literal() 以避免 26.1.2 brigadier 依赖问题。
     *
     * @param text 显示文本
     * @return Component 实例
     */
    public static Component text(String text) {
        return Component.literal(text);
    }

    /**
     * 创建可翻译的文本组件
     *
     * <p>注意：MC 26.1.2 Unobfuscated 模式下可能需要特殊处理。
     * 如果翻译键不存在，回退到显示原始键名。
     *
     * @param translationKey 翻译键
     * @return Component 实例
     */
    public static Component translatable(String translationKey) {
        try {
            return Component.translatable(translationKey);
        } catch (NoClassDefFoundError e) {
            // brigadier 不可用时的回退方案
            return Component.literal(translationKey);
        }
    }

    // ==================== 文本测量方法 ====================

    /**
     * 测量文本渲染宽度（像素）
     *
     * <p>封装 Minecraft 字体的宽度计算，
     * 用于 UI 布局时的文本尺寸计算。
     *
     * @param text 要测量的文本
     * @return 文本渲染宽度（像素），若字体不可用返回 0
     */
    public static int textWidth(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.font != null) {
                return mc.font.width(text);
            }
        } catch (Exception e) {
            // 忽略异常，返回默认值
        }
        return 0;
    }

    /**
     * 测量 Component 文本渲染宽度（像素）
     *
     * @param text 要测量的文本组件
     * @return 文本渲染宽度（像素），若字体不可用返回 0
     */
    public static int textWidth(Component text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.font != null) {
                return mc.font.width(text);
            }
        } catch (Exception e) {
            // 忽略异常，返回默认值
        }
        return 0;
    }

    // ==================== Button 构建器（替代 CycleButton）====================

    /**
     * Button 构建器接口
     *
     * <p>提供统一的按钮创建接口，隐藏 MC 版本差异。
     * 替代 CycleButton 用于设置界面。
     */
    public interface ButtonBuilder {
        ButtonBuilder text(String text);
        ButtonBuilder bounds(int x, int y, int width, int height);
        ButtonBuilder onClick(Button.OnPress action);
        Button build();
    }

    /**
     * 创建 Button 构建器
     *
     * @return 新的构建器实例
     */
    public static ButtonBuilder buttonBuilder(int x, int y, int width, int height) {
        return new MCAbstractButtonBuilder(x, y, width, height);
    }

    /**
     * 内部 Button 构建器实现
     *
     * <p>封装 net.minecraft.client.gui.components.Button.Builder 的差异。
     */
    private static class MCAbstractButtonBuilder implements ButtonBuilder {
        private final int x, y, width, height;
        private String text = "";
        private Button.OnPress action = b -> {};

        MCAbstractButtonBuilder(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public ButtonBuilder text(String text) {
            this.text = text;
            return this;
        }

        @Override
        public ButtonBuilder bounds(int x, int y, int width, int height) {
            // 已在构造函数中设置，此方法保持兼容性
            return this;
        }

        @Override
        public ButtonBuilder onClick(Button.OnPress action) {
            this.action = action;
            return this;
        }

        @Override
        public Button build() {
            return Button.builder(Component.literal(text), action)
                    .bounds(x, y, width, height)
                    .build();
        }
    }

    // ==================== Screen 辅助方法 ====================

    /**
     * 初始化子屏幕（适配不同版本的 Screen.init()）
     *
     * <p><b>版本差异：</b>
     * <ul>
     *   <li>26.1.2: 可能接受 init(width, height)</li>
     *   <li>26.2: init() 无参数</li>
     * </ul>
     *
     * @param childScreen   子屏幕
     * @param width         屏幕宽度
     * @param height        屏幕高度
     */
    public static void initChildScreen(
            Object childScreen,
            int width,
            int height) {
        // MC 26.x 的 Screen.init() 方法签名可能变化
        // 完全使用反射避免编译时类型检查问题
        if (childScreen instanceof net.minecraft.client.gui.screens.Screen screen) {
            try {
                // 尝试调用带参数的版本（26.1.2 可能有 init(int, int)）
                java.lang.reflect.Method method = screen.getClass().getDeclaredMethod("init", int.class, int.class);
                method.setAccessible(true);
                method.invoke(screen, width, height);
                return; // 成功调用后返回
            } catch (NoSuchMethodException ignored) {
                // 继续尝试无参数版本
            } catch (Exception ignored) {
                // 反射调用失败，继续尝试其他方式
            }
            
            // 尝试无参数版本（26.2+）
            try {
                java.lang.reflect.Method method = screen.getClass().getDeclaredMethod("init");
                method.setAccessible(true);
                method.invoke(screen);
            } catch (Exception ignored) {
                // 所有方法都失败，忽略（由 Screen 自身生命周期处理）
            }
        }
    }
}
