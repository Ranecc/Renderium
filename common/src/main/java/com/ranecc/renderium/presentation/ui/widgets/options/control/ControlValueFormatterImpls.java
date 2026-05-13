// Renderium - 控件值格式化器工具类
// 提供预定义的数值格式化器工厂方法
// 参考 Sodium 的 ControlValueFormatterImpls 实现，使用中性命名

package com.ranecc.renderium.presentation.ui.widgets.options.control;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 控件值格式化器工具类。
 *
 * <p>提供预定义的 {@link Function}&lt;Integer, Component&gt; 工厂方法，
 * 用于将整数选项值转换为用户友好的显示文本。
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li><b>国际化</b>：优先使用 Minecraft 翻译键</li>
 *   <li><b>可读性</b>：添加单位、特殊值映射等</li>
 *   <li><b>一致性</b>：与 Minecraft 原生设置界面风格统一</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 创建带百分比格式的整数选项
 * IntegerOption brightness = IntegerOption.builder()
 *     .id(Identifier.parse("renderium:brightness"))
 *     .name(Component.translatable("renderium.option.brightness"))
 *     .range(new Range(0, 100, 1))
 *     .valueFormatter(ControlValueFormatterImpls.percentage())
 *     .build();
 *
 * // 结果：50 → "50%", 100 → "100%"
 * </pre>
 *
 * <h2>可用格式化器</h2>
 * <table border="1">
 *   <tr><th>方法名</th><th>输入</th><th>输出示例</th></tr>
 *   <tr><td>{@link #percentage()}</td><td>50</td><td>"50%"</td></tr>
 *   <tr><td>{@link #number()}</td><td>42</td><td>"42"</td></tr>
 *   <tr><td>{@link #guiScale()}</td><td>0</td><td>"Auto"</td></tr>
 *   <tr><td>{@link #fpsLimit()}</td><td>260</td><td>"Unlimited"</td></tr>
 *   <tr><td>{@link #brightness()}</td><td>50</td><td>"Default"</td></tr>
 *   <tr><td>{@link #anisotropyBit()}</td><td>3</td><td>"8x"</td></tr>
 *   <tr><td>{@link #chunkFade()}</td><td>500</td><td>"0.5 s"</td></tr>
 *   <tr><td>{@link #biomeBlend()}</td><td>2</td><td>"5 x 5"</td></tr>
 * </table>
 *
 * <h2>线程安全性</h2>
 * <p>所有方法均为静态工厂，返回的函数对象无状态，天然线程安全。
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class ControlValueFormatterImpls {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(ControlValueFormatterImpls.class.getName());

    /** 私有构造器防止实例化 */
    private ControlValueFormatterImpls() {
        throw new UnsupportedOperationException("ControlValueFormatterImpls is a utility class");
    }

    // ==================== 基础格式化器 ====================

    /**
     * 百分比格式化器。
     *
     * <p>将整数值转换为百分比字符串。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: 待格式化的整数值（0-100）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本，格式为 "{v}%"（如 "50%"、"100%"）
     *
     * <h4>使用场景</h4>
     * <pre>
     * // 亮度调节、音量控制、透明度设置等
     * .valueFormatter(ControlValueFormatterImpls.percentage())
     * </pre>
     *
     * @return 格式化函数 (int → Component)
     */
    public static Function<Integer, Component> percentage() {
        return v -> Component.literal(v + "%");
    }

    /**
     * 纯数字格式化器。
     *
     * <p>将整数值直接转换为字符串，无任何修饰。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: 待格式化的整数值</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本，为数字的字符串表示（如 "42"、"256"）
     *
     * <h4>使用场景</h4>
     * <pre>
     * // 渲染距离、粒子数量等纯数值显示
     * .valueFormatter(ControlValueFormatterImpls.number())
     * </pre>
     *
     * @return 格式化函数 (int → Component)
     */
    public static Function<Integer, Component> number() {
        return v -> Component.literal(String.valueOf(v));
    }

    /**
     * 乘数格式化器。
     *
     * <p>在数值后添加 "x" 后缀。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: 待格式化的整数值</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本，格式为 "{v}x"（如 "2x"、"16x"）
     *
     * @return 格式化函数 (int → Component)
     */
    public static Function<Integer, Component> multiplier() {
        return v -> Component.literal(v + "x");
    }

    // ==================== 条件格式化器 ====================

    /**
     * 数量或禁用状态格式化器。
     *
     * <p>当值为 0 时显示禁用文本，否则应用自定义格式化器。
     * 典型用途：渲染距离为 0 时显示"关闭"，否则显示区块数。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>formatter</b>: 非零值的格式化函数（必填）</li>
     *   <li><b>disabledText</b>: 值为 0 时显示的禁用文本（必填）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>条件格式化函数：
     * <ul>
     *   <li>v=0 → disabledText</li>
     *   <li>v≠0 → formatter.apply(v)</li>
     * </ul>
     *
     * <h4>使用场景</h4>
     * <pre>
     * // 渲染距离：0→"Off", 其他→"12 chunks"
     * .valueFormatter(ControlValueFormatterImpls.quantityOrDisabled(
     *     v -> Component.translatable("renderium.unit.chunks", v),
     *     Component.translatable("renderium.options.off")
     * ))
     * </pre>
     *
     * @param formatter    非零值格式化器
     * @param disabledText 禁用状态文本
     * @return            条件格式化函数
     */
    public static Function<Integer, Component> quantityOrDisabled(
        IntFunction<Component> formatter,
        Component disabledText
    ) {
        return v -> v == 0 ? disabledText : formatter.apply(v);
    }

    // ==================== 游戏特定格式化器 ====================

    /**
     * GUI 缩放格式化器。
     *
     * <p>Minecraft GUI 缩放设置的专用格式化器。
     * 值为 0 表示自动缩放，其他值为固定倍率。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: GUI 缩放值（0-4）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本：
     * <ul>
     *   <li>v=0 → "Auto"（翻译键: options.guiScale.auto）</li>
     *   <li>v=1~4 → "{v}x"（如 "2x"）</li>
     * </ul>
     *
     * @return GUI 缩放格式化函数
     */
    public static Function<Integer, Component> guiScale() {
        return v -> (v == 0)
            ? Component.translatable("options.guiScale.auto")
            : Component.literal(v + "x");
    }

    /**
     * FPS 限制格式化器。
     *
     * <p>Minecraft 帧率限制设置的专用格式化器。
     * 特殊值 260 表示无限制（Minecraft 内部约定）。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: FPS 限制值（60-260）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本：
     * <ul>
     *   <li>v=260 → "Unlimited"（翻译键: options.framerateLimit.max）</li>
     *   <li>v=其他 → "{v} FPS"（如 "144 FPS"）</li>
     * </ul>
     *
     * @return FPS 限制格式化函数
     */
    public static Function<Integer, Component> fpsLimit() {
        return v -> (v == 260)
            ? Component.translatable("options.framerateLimit.max")
            : Component.translatable("options.framerate", v);
    }

    /**
     * 亮度（Gamma）格式化器。
     *
     * <p>Minecraft 亮度设置的专用格式化器。
     * 使用标准亮度等级映射到描述性文本。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: 亮度值（0-100）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本：
     * <ul>
     *   <li>v=0 → "Moody"（最暗）</li>
     *   <li>v=50 → "Default"（默认）</li>
     *   <li>v=100 → "Bright"（最亮）</li>
     *   <li>v=其他 → "{v}%"</li>
     * </ul>
     *
     * @return 亮度格式化函数
     */
    public static Function<Integer, Component> brightness() {
        return v -> {
            if (v == 0) {
                return Component.translatable("options.gamma.min");
            } else if (v == 50) {
                return Component.translatable("options.gamma.default");
            } else if (v == 100) {
                return Component.translatable("options.gamma.max");
            } else {
                return Component.literal(v + "%");
            }
        };
    }

    /**
     * 各向异性过滤位数格式化器。
     *
     * <p>将位数转换为实际采样倍数（2^n）。
     * 各向异性过滤用于提升斜面纹理清晰度。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: 各向异性位数（0-4）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本：
     * <ul>
     *   <li>v=0 → "Off"（关闭）</li>
     *   <li>v=1 → "2x"（2^1）</li>
     *   <li>v=2 → "4x"（2^2）</li>
     *   <li>v=3 → "8x"（2^3）</li>
     *   <li>v=4 → "16x"（2^4）</li>
     * </ul>
     *
     * @return 各向异性位数格式化函数
     */
    public static Function<Integer, Component> anisotropyBit() {
        return v -> {
            if (v == 0) {
                return Component.translatable("options.off");
            } else {
                return Component.literal((1 << v) + "x");
            }
        };
    }

    /**
     * 分辨率格式化器。
     *
     * <p>将索引值转换为显示器分辨率文本。
     * 需要传入 Monitor Supplier 以获取当前显示器信息。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>monitorSupplier</b>: 显示器供应者（必填）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>格式化函数：
     * <ul>
     *   <li>v=0 → "Current"（当前分辨率）</li>
     *   <li>v&gt;0 → "{width} x {height}"（如 "1920 x 1080"）</li>
     * </ul>
     *
     * @param monitorSupplier 显示器供应者
     * @return               分辨率格式化函数
     */
    public static Function<Integer, Component> resolution(Supplier<Object> monitorSupplier) {
        return v -> {
            Object monitor = monitorSupplier.get();

            if (monitor == null) {
                return Component.empty();
            } else if (v == 0) {
                return Component.translatable("options.fullscreen.current");
            } else {
                return Component.literal(getResolutionString(monitor, v - 1));
            }
        };
    }

    /**
     * 区块淡入时间格式化器。
     *
     * <p>将毫秒时间转换为秒显示。
     * 用于控制新加载区块的渐入效果时长。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: 淡入时间（毫秒）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本：
     * <ul>
     *   <li>v=0 → "Off"（无淡入效果）</li>
     *   <li>v&gt;0 → "{seconds} s"（如 "0.5 s"）</li>
     * </ul>
     *
     * @return 区块淡入时间格式化函数
     */
    public static Function<Integer, Component> chunkFade() {
        return v -> {
            if (v == 0) {
                return Component.translatable("gui.none");
            } else {
                double seconds = v / 1000.0;
                return Component.literal(String.format("%.1f s", seconds));
            }
        };
    }

    /**
     * 生物群系混合半径格式化器。
     *
     * <p>将混合半径设置值转换为实际影响范围。
     * 生物群系混合用于平滑不同生物群系之间的边界过渡。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>v</b>: 混合半径设置值（0-7）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>Component 文本：
     * <ul>
     *   <li>v=0 → "Off"（无混合）</li>
     *   <li>v=1 → "3 x 3"（半径 1 的方块范围）</li>
     *   <li>v=2 → "5 x 5"（半径 2 的方块范围）</li>
     *   <li>v=n → "{2n+1} x {2n+1}"</li>
     * </ul>
     *
     * @return 生物群系混合半径格式化函数
     */
    public static Function<Integer, Component> biomeBlend() {
        return v -> {
            if (v < 0 || v > 7) {
                return Component.translatable("parsing.int.invalid", v);
            } else if (v == 0) {
                return Component.translatable("gui.none");
            } else {
                int size = 2 * v + 1;
                return Component.literal(size + " x " + size);
            }
        };
    }

    // ==================== 通用模板方法 ====================

    /**
     * 创建基于翻译键的格式化器。
     *
     * <p>将值作为参数插入翻译键中。
     * 翻译键应包含一个 %s 或 %d 占位符。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>key</b>: 翻译键（如 "renderium.option.value.%d"）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>格式化函数，输出翻译后的文本
     *
     * @param key 翻译键
     * @return    翻译格式化函数
     */
    public static Function<Integer, Component> translateVariable(String key) {
        return v -> Component.translatable(key, v);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从 Monitor 对象获取分辨率字符串。
     *
     * <p>根据索引获取对应的 VideoMode 并格式化为字符串。
     * 此方法需要适配实际的 Monitor API。
     *
     * @param monitor Monitor 实例
     * @param index  模式索引（从 0 开始）
     * @return       分辨率字符串（如 "1920 x 1080"）
     */
    private static String getResolutionString(Object monitor, int index) {
        try {
            java.lang.reflect.Method getModeMethod = monitor.getClass().getMethod("getMode", int.class);
            Object mode = getModeMethod.invoke(monitor, index);

            if (mode != null) {
                java.lang.reflect.Method getWidth = mode.getClass().getMethod("getWidth");
                java.lang.reflect.Method getHeight = mode.getClass().getMethod("getHeight");

                int width = (int) getWidth.invoke(mode);
                int height = (int) getHeight.invoke(mode);

                return width + " x " + height;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get resolution from monitor: {0}", e.getMessage());
        }

        return "Unknown";
    }
}
