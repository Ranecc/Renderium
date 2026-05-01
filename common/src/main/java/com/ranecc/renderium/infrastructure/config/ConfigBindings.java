package com.ranecc.renderium.infrastructure.config;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 配置绑定工具类（自动快照提交）
 *
 * <p>封装选项绑定的 setter/getter，
 * 在 setter 执行后自动调用 {@link RenderiumConfig#commitSnapshot()}，
 * 确保热路径配置快照及时更新。
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li><b>零侵入</b>：不修改原有绑定逻辑，仅包装一层</li>
 *   <li><b>自动同步</b>：setter 执行后自动提交快照</li>
 *   <li><b>性能友好</b>：仅在冷路径（UI 回调）触发，不影响热路径</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 原始绑定
 * .setBinding(
 *     value -> config.setSuperResolutionEnabled(value),
 *     () -> config.isSuperResolutionEnabled()
 * )
 *
 * // 使用工具类（自动提交快照）
 * .setBinding(
 *     ConfigBindings.setter(config::setSuperResolutionEnabled, config),
 *     config::isSuperResolutionEnabled
 * )
 * }</pre>
 *
 * @author Renderium Team
 * @since 5.3.0
 */
public final class ConfigBindings {

    /** 私有构造器（工具类） */
    private ConfigBindings() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 创建带自动快照提交的 Boolean setter
     *
     * @param originalSetter 原始 setter 方法引用
     * @param config         配置实例（用于提交快照）
     * @return 包装后的 setter（执行后自动调用 commitSnapshot）
     * @throws NullPointerException 如果参数为 null
     */
    public static Consumer<Boolean> booleanSetter(Consumer<Boolean> originalSetter, RenderiumConfig config) {
        Objects.requireNonNull(originalSetter, "originalSetter cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        return value -> {
            originalSetter.accept(value);
            config.commitSnapshot();
        };
    }

    /**
     * 创建带自动快照提交的 Integer setter
     *
     * @param originalSetter 原始 setter 方法引用
     * @param config         配置实例（用于提交快照）
     * @return 包装后的 setter（执行后自动调用 commitSnapshot）
     */
    public static Consumer<Integer> intSetter(Consumer<Integer> originalSetter, RenderiumConfig config) {
        Objects.requireNonNull(originalSetter, "originalSetter cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        return value -> {
            originalSetter.accept(value);
            config.commitSnapshot();
        };
    }

    /**
     * 创建带自动快照提交的 Float setter
     *
     * @param originalSetter 原始 setter 方法引用
     * @param config         配置实例（用于提交快照）
     * @return 包装后的 setter（执行后自动调用 commitSnapshot）
     */
    public static Consumer<Float> floatSetter(Consumer<Float> originalSetter, RenderiumConfig config) {
        Objects.requireNonNull(originalSetter, "originalSetter cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        return value -> {
            originalSetter.accept(value);
            config.commitSnapshot();
        };
    }

    /**
     * 创建带自动快照提交的 Enum setter
     *
     * @param <T>            枚举类型
     * @param originalSetter 原始 setter 方法引用
     * @param config         配置实例（用于提交快照）
     * @return 包装后的 setter（执行后自动调用 commitSnapshot）
     */
    public static <T extends Enum<T>> Consumer<T> enumSetter(Consumer<T> originalSetter, RenderiumConfig config) {
        Objects.requireNonNull(originalSetter, "originalSetter cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        return value -> {
            originalSetter.accept(value);
            config.commitSnapshot();
        };
    }

    // ==================== 便捷工厂方法 ====================

    /**
     * 创建完整的 Boolean 绑定对（setter + getter）
     *
     * @param setter 原始 setter
     * @param getter 原始 getter
     * @param config 配置实例
     * @return [包装后的 setter, 原始 getter]
     */
    public static Object[] booleanBinding(
            Consumer<Boolean> setter,
            Supplier<Boolean> getter,
            RenderiumConfig config) {
        return new Object[]{ booleanSetter(setter, config), getter };
    }

    /**
     * 创建完整的 Integer 绑定对（setter + getter）
     *
     * @param setter 原始 setter
     * @param getter 原始 getter
     * @param config 配置实例
     * @return [包装后的 setter, 原始 getter]
     */
    public static Object[] integerBinding(
            Consumer<Integer> setter,
            Supplier<Integer> getter,
            RenderiumConfig config) {
        return new Object[]{ intSetter(setter, config), getter };
    }

    /**
     * 创建完整的 Enum 绑定对（setter + getter）
     *
     * @param <T>    枚举类型
     * @param setter 原始 setter
     * @param getter 原始 getter
     * @param config 配置实例
     * @return [包装后的 setter, 原始 getter]
     */
    public static <T extends Enum<T>> Object[] enumBinding(
            Consumer<T> setter,
            Supplier<T> getter,
            RenderiumConfig config) {
        return new Object[]{ enumSetter(setter, config), getter };
    }
}
