// Renderium - 控件工厂
// 根据 RendererOption 类型自动选择合适的 UI 控件

package com.ranecc.renderium.presentation.ui.ui.widgets.options.control;

import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import com.ranecc.renderium.None;
import net.minecraft.network.chat.Component;

/**
 * 选项控件工厂。
 *
 * <p>根据 {@link RendererOption} 的具体类型，自动选择最合适的 UI 控件实现。
 * 简化选项列表的构建过程，避免手动判断类型。
 *
 * <h2>设计模式</h2>
 * <p>采用<strong>简单工厂模式</strong>（静态工厂方法），
 * 根据输入类型返回对应的 {@link Control} 实例。
 *
 * <h2>类型映射规则</h2>
 * <pre>
 * ┌─────────────────┬────────────────────┬──────────────────┐
 * │   选项类型       │    默认控件         │    条件变体        │
 * ├─────────────────┼────────────────────┼──────────────────┤
 * │ BooleanOption   │ CyclingControl     │ TickBoxControl*  │
 * │ IntegerOption   │ SliderControl      │ CyclingControl** │
 * │ EnumOption      │ CyclingControl     │ -                │
 * └─────────────────┴────────────────────┴──────────────────┘
 *
 * * 使用 createCheckBox() 强制使用复选框
 * ** 范围 ≤ 10 且步进 = 1 时自动切换为循环控件
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 基本用法：自动选择最佳控件
 * Control control = ControlFactory.createControlFor(option);
 *
 * // 指定布尔选项使用复选框样式
 * Control checkbox = ControlFactory.createCheckBox(boolOption);
 *
 * // 指定整数选项强制使用滑块
 * Control slider = ControlFactory.createSlider(intOption);
 * </pre>
 *
 * <h2>线程安全性</h2>
 * <p>所有方法均为纯函数，无共享状态，天然线程安全。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see Control
 * @see CyclingControl
 * @see SliderControl
 * @see TickBoxControl
 */
public final class ControlFactory {

    /** 私有构造器防止实例化 */
    private ControlFactory() {
        throw new UnsupportedOperationException("ControlFactory is a utility class");
    }

    /**
     * 小范围阈值：整数范围 ≤ 此值时优先使用循环控件。
     */
    private static final int SMALL_RANGE_THRESHOLD = 10;

    // ==================== 智能工厂方法 ====================

    /**
     * 根据选项类型自动创建最佳控件。
     *
     * <p>这是<strong>推荐的主要入口方法</strong>，内部会：
     * <ol>
     *   <li>检测选项的具体类型</li>
     *   <li>对于 IntegerOption：检查范围大小决定用 Slider 还是 Cycling</li>
     *   <li>创建对应的 Control 实例并配置格式化器</li>
     * </ol>
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>option</b>: 渲染器选项实例（必填）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>对应类型的 Control 实例：
     * <table border="1">
     *   <tr><th>类型</th><th>条件</th><th>返回</th></tr>
     *   <tr><td>BooleanOption</td><td>-</td><td>CyclingControl</td></tr>
     *   <tr><td>IntegerOption</td><td>range &gt; 10 或 step &gt; 1</td><td>SliderControl</td></tr>
     *   <tr><td>IntegerOption</td><td>range ≤ 10 且 step = 1</td><td>CyclingControl</td></tr>
     *   <tr><td>EnumOption</td><td>-</td><td>CyclingControl</td></tr>
     *   <tr><td>其他</td><td>-</td><td>抛出 IllegalArgumentException</td></tr>
     * </table>
     *
     * @param option 渲染器选项
     * @return      匹配的 Control 实例
     * @throws NullPointerException     若 option 为 null
     * @throws IllegalArgumentException 若选项类型不支持
     */
    public static Control createControlFor(RendererOption option) {
        java.util.Objects.requireNonNull(option, "Option cannot be null");

        if (option instanceof BooleanOption boolOpt) {
            return createCyclingControl(boolOpt);

        } else if (option instanceof IntegerOption intOpt) {
            var range = intOpt.getRange();
            int rangeSize = range.getMax() - range.getMin();

            if (range.getStep() == 1 && rangeSize <= SMALL_RANGE_THRESHOLD) {
                return createSmallRangeControl(intOpt);
            } else {
                return createSlider(intOpt);
            }

        } else if (option instanceof EnumOption<?> enumOpt) {
            return createEnumControl(enumOpt);
        }

        throw new IllegalArgumentException(
            "Unsupported option type: " + option.getClass().getName()
        );
    }

    // ==================== 专用工厂方法 ====================

    /**
     * 为布尔选项创建循环控件（ON/OFF 文本显示）。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>option</b>: 布尔选项实例</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>CyclingControl 实例，显示 "ON"/"OFF" 文本
     *
     * @param option 布尔选项
     * @return      循环控件
     */
    public static CyclingControl createCyclingControl(BooleanOption option) {
        return new CyclingControl(
            option,
            () -> option.getValue()
                ? Component.translatable("options.on")
                : Component.translatable("options.off"),
            null
        );
    }

    /**
     * 为布尔选项创建复选框控件（图形勾选显示）。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>option</b>: 布尔选项实例</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>TickBoxControl 实例，显示 ✓/✗ 图形符号
     *
     * @param option 布尔选项
     * @return      复选框控件
     */
    public static TickBoxControl createCheckBox(BooleanOption option) {
        return new TickBoxControl(option);
    }

    /**
     * 为整数选项创建滑块控件。
     *
     * <p>适用于大范围或需要精细控制的数值选项。
     * 自动检测并使用选项自带的 valueFormatter。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>option</b>: 整数选项实例（必须包含 Range）</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>SliderControl 实例，支持拖拽、点击、滚轮操作
     *
     * @param option 整数选项
     * @return      滑块控件
     */
    public static SliderControl createSlider(IntegerOption option) {
        java.util.function.Function<Integer, Component> formatter =
            option.getValueFormatter();

        if (formatter == null) {
            formatter = guessFormatterForOption(option);
        }

        return new SliderControl(option, formatter);
    }

    /**
     * 为小范围整数选项创建循环控件。
     *
     * <p>适用于范围较小（≤10）且步进为 1 的离散值选项，
     * 如质量等级、LOD 级别等。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>option</b>: 小范围整数选项</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>CyclingControl 实例（包装 IntegerOption）
     *
     * @param option 整数选项
     * @return      循环控件
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CyclingControl createSmallRangeControl(IntegerOption option) {
        return new CyclingControl(
            option,
            () -> option.formatValue(option.getValidatedValue()),
            null
        );
    }

    /**
     * 为枚举选项创建循环控件。
     *
     * <p>在枚举常量之间循环切换，
     * 显示通过 elementNameProvider 映射的文本。
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><b>option</b>: 枚举选项实例</li>
     * </ul>
     *
     * <h4>返回值</h4>
     * <p>CyclingControl 实例，支持枚举值循环
     *
     * @param option 枚举选项
     * @return      循环控件
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CyclingControl createEnumControl(EnumOption<?> option) {
        return new CyclingControl(
            option,
            option.getEnumClass(),
            () -> {
                // 使用原始类型绕过泛型检查
                Object value = option.getValue();
                return ((EnumOption) option).getElementName((Enum) value);
            },
            null
        );
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据选项特征猜测最佳格式化器。
     *
     * <p>当选项未显式设置 valueFormatter 时调用，
     * 通过启发式规则选择合理的默认格式化器：
     * <ol>
     *   <li>若范围为 0-100 且名称含 "brightness" → brightness()</li>
     *   <li>若范围为 0-4 且名称含 "guiScale" → guiScale()</li>
     *   <li>若范围为 60-260 且名称含 "fps" → fpsLimit()</li>
     *   <li>若范围为 0-7 且名称含 "blend" → biomeBlend()</li>
     *   <li>否则 → number()</li>
     * </ol>
     *
     * @param option 整数选项
     * @return      推测的最佳格式化器
     */
    private static java.util.function.Function<Integer, Component> guessFormatterForOption(
        IntegerOption option
    ) {
        String name = option.getId().getPath().toLowerCase();
        var range = option.getRange();

        if (name.contains("brightness") || name.contains("gamma")) {
            return ControlValueFormatterImpls.brightness();
        }
        if (name.contains("guiscale")) {
            return ControlValueFormatterImpls.guiScale();
        }
        if (name.contains("fps") || name.contains("framerate")) {
            return ControlValueFormatterImpls.fpsLimit();
        }
        if (name.contains("blend") || name.contains("biome")) {
            return ControlValueFormatterImpls.biomeBlend();
        }
        if (name.contains("anisotropy") || name.contains("af")) {
            return ControlValueFormatterImpls.anisotropyBit();
        }
        if (name.contains("fade") || name.contains("chunk")) {
            return ControlValueFormatterImpls.chunkFade();
        }

        return ControlValueFormatterImpls.number();
    }
}
