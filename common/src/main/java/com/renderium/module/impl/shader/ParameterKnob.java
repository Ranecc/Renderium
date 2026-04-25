// Renderium - 光影系统 v2.0
// 参数旋钮数据模型 - UI 可暴露的渲染参数

package com.renderium.module.impl.shader;

import java.util.Objects;

/**
 * 参数旋钮 (Parameter Knob)
 * <p>
 * 定义一个可由用户通过 UI 调整的渲染参数。
 * 每个旋钮对应光影包中的一个可调数值，
 * 通过 YAML 的 parameter_knobs 部分声明。
 *
 * <h2>支持的参数类型：</h2>
 * <ul>
 *   <li><b>float</b> - 浮点数（如泛光强度 0.0~2.0）</li>
 *   <li><b>int</b> - 整数（如采样数 1~64）</li>
 *   <li><b>enum</b> - 枚举（如色调映射算法选择）</li>
 *   <li><b>bool</b> - 布尔开关</li>
 * </ul>
 *
 * <h3>YAML 声明示例：</h3>
 * <pre>{@code
 * parameter_knobs:
 *   - id: bloom_intensity
 *     label: "泛光强度"
 *     type: float
 *     range: [0.0, 2.0]
 *     default: 0.5
 *     step: 0.01
 * }</pre>
 *
 * @since 2.1.0
 */
public final class ParameterKnob {

    /** 参数唯一标识符（用于代码引用和 UBO 绑定）*/
    private final String id;

    /** 显示名称（UI 标签文本）*/
    private final String label;

    /** 参数类型 */
    private final KnobType type;

    /** 最小值（float/double 表示）*/
    private final double minValue;

    /** 最大值（float/double 表示）*/
    private final double maxValue;

    /** 默认值 */
    private final double defaultValue;

    /** 调整步长（仅 float/int 类型有效）*/
    private final double step;

    /** 枚举选项列表（仅 enum 类型有效）*/
    private final String[] enumOptions;

    /** 枚举默认索引（仅 enum 类型有效）*/
    private final int defaultEnumIndex;

    /** 当前运行时值（volatile，支持热更新）*/
    private volatile double currentValue;

    /**
     * 参数类型枚举
     */
    public enum KnobType {
        /** 浮点数 */
        FLOAT,
        /** 整数 */
        INT,
        /** 枚举选择 */
        ENUM,
        /** 布尔开关 */
        BOOLEAN
    }

    // ==================== 构造函数 ====================

    /**
     * 浮点/整数参数构造函数
     *
     * 【参数说明】
     * @param id         String - 唯一标识符
     * @param label      String - 显示名称
     * @param type       KnobType - FLOAT 或 INT
     * @param minVal     double - 最小值
     * @param maxVal     double - 最大值
     * @param defaultVal double - 默认值
     * @param step       double - 调整步长
     */
    public ParameterKnob(String id, String label, KnobType type,
                          double minVal, double maxVal, double defaultVal, double step) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.type = Objects.requireNonNull(type);

        if (type == KnobType.ENUM || type == KnobType.BOOLEAN) {
            throw new IllegalArgumentException("枚举/布尔类型请使用对应的构造函数");
        }

        this.minValue = minVal;
        this.maxValue = maxVal;
        this.defaultValue = clamp(defaultVal, minVal, maxVal);
        this.currentValue = this.defaultValue;
        this.step = Math.max(step, 0.0001);
        this.enumOptions = null;
        this.defaultEnumIndex = 0;
    }

    /**
     * 枚举参数构造函数
     *
     * @param id           String   - 唯一标识符
     * @param label        String   - 显示名称
     * @param enumOptions  String[] - 选项列表
     * @param defaultIndex int      - 默认选中索引
     */
    public ParameterKnob(String id, String label, String[] enumOptions, int defaultIndex) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.type = KnobType.ENUM;
        this.enumOptions = enumOptions.clone();
        this.defaultEnumIndex = Math.clamp(defaultIndex, 0, enumOptions.length - 1);
        this.currentValue = this.defaultEnumIndex;
        this.minValue = 0;
        this.maxValue = enumOptions.length - 1;
        this.defaultValue = this.defaultEnumIndex;
        this.step = 1.0;
    }

    /**
     * 布尔参数构造函数
     *
     * @param id          String - 唯一标识符
     * @param label       String - 显示名称
     * @param defaultBool boolean - 默认值
     */
    public ParameterKnob(String id, String label, boolean defaultBool) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.type = KnobType.BOOLEAN;
        this.defaultValue = defaultBool ? 1.0 : 0.0;
        this.currentValue = this.defaultValue;
        this.minValue = 0;
        this.maxValue = 1;
        this.step = 1.0;
        this.enumOptions = null;
        this.defaultEnumIndex = 0;
    }

    // ==================== 值操作 API ====================

    /**
     * 获取当前浮点值
     *
     * 【返回值】
     * @return double - 当前值
     */
    public double getFloatValue() { return currentValue; }

    /**
     * 获取当前整数值
     *
     * 【返回值】
     * @return int - 当前值（四舍五入）
     */
    public int getIntValue() { return (int) Math.round(currentValue); }

    /**
     * 获取当前布尔值
     *
     * 【返回值】
     * @return boolean - 当前值 > 0.5 返回 true
     */
    public boolean getBooleanValue() { return currentValue > 0.5; }

    /**
     * 获取当前枚举索引
     *
     * 【返回值】
     * @return int - 当前枚举选项索引
     */
    public int getEnumIndex() { return (int) Math.round(currentValue); }

    /**
     * 获取当前枚举选项文本
     *
     * 【返回值】
     * @return String - 当前选中的选项文本
     */
    public String getEnumValue() {
        if (enumOptions == null || enumOptions.length == 0) return "";
        int idx = getEnumIndex();
        return idx >= 0 && idx < enumOptions.length ? enumOptions[idx] : "";
    }

    /**
     * 设置浮点/整数值
     * <p>
     * 值会被自动限制在 [minValue, maxValue] 范围内。
     *
     * 【方法参数】
     * @param value double - 新值
     */
    public void setValue(double value) {
        this.currentValue = clamp(value, minValue, maxValue);
    }

    /**
     * 设置布尔值
     */
    public void setBooleanValue(boolean value) {
        this.currentValue = value ? 1.0 : 0.0;
    }

    /**
     * 设置枚举索引
     *
     * @param index int - 选项索引
     */
    public void setEnumIndex(int index) {
        if (enumOptions != null && index >= 0 && index < enumOptions.length) {
            this.currentValue = index;
        }
    }

    /**
     * 重置为默认值
     */
    public void resetToDefault() {
        this.currentValue = defaultValue;
    }

    // ==================== Getter 方法 ====================

    public String getId() { return id; }
    public String getLabel() { return label; }
    public KnobType getType() { return type; }
    public double getMinValue() { return minValue; }
    public double getMaxValue() { return maxValue; }
    public double getDefaultValue() { return defaultValue; }
    public double getStep() { return step; }
    public String[] getEnumOptions() { return enumOptions != null ? enumOptions.clone() : null; }
    public int getDefaultEnumIndex() { return defaultEnumIndex; }

    // ==================== 内部工具方法 ====================

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return String.format("ParameterKnob{id='%s', label='%s', type=%s, current=%.3f}",
                id, label, type.name(), currentValue);
    }

    // ==================== 静态工厂方法（从 Map 构建）====================

    /**
     * 从 YAML 解析的 Map 构建 ParameterKnob
     * <p>
     * 支持的 Map 字段：
     * <ul>
     *   <li>id (String, 必填)</li>
     *   <li>label (String, 必填)</li>
     *   <li>type (String: "float"/"int"/"enum"/"bool", 必填)</li>
     *   <li>range (List&lt;Double&gt;, float/int 必填)</li>
     *   <li>default (Number/Boolean, 必填)</li>
     *   <li>step (Double, 可选, 默认 0.01)</li>
     *   <li>options (List&lt;String&gt;, enum 必填)</li>
     * </ul>
     *
     * @param map Map&lt;String, Object&gt; - YAML 解析后的键值对
     * @return ParameterKnob - 构建的实例
     */
    public static ParameterKnob fromMap(java.util.Map<String, Object> map) {
        String id = getString(map, "id");
        String label = getString(map, "label");
        String typeStr = getString(map, "type").toLowerCase();

        KnobType type = switch (typeStr) {
            case "float", "f" -> KnobType.FLOAT;
            case "int", "integer", "i" -> KnobType.INT;
            case "enum", "e" -> KnobType.ENUM;
            case "boolean", "bool", "b" -> KnobType.BOOLEAN;
            default -> throw new IllegalArgumentException("未知参数类型: " + typeStr);
        };

        return switch (type) {
            case FLOAT, INT -> {
                java.util.List<Number> range = getList(map, "range");
                if (range.size() < 2) {
                    throw new IllegalArgumentException("float/int 类型需要 range 字段 [min, max]");
                }
                double min = range.get(0).doubleValue();
                double max = range.get(1).doubleValue();
                double defVal = getNumber(map, "default", (min + max) / 2.0);
                double step = getNumber(map, "step", type == KnobType.FLOAT ? 0.01 : 1.0);
                yield new ParameterKnob(id, label, type, min, max, defVal, step);
            }
            case ENUM -> {
                @SuppressWarnings("unchecked")
                java.util.List<String> options = (java.util.List<String>) map.getOrDefault("options",
                        java.util.List.of("Option A", "Option B"));
                int defIdx = getInt(map, "default", 0);
                yield new ParameterKnob(id, label, options.toArray(new String[0]), defIdx);
            }
            case BOOLEAN -> {
                boolean defBool = getBoolean(map, "default", false);
                yield new ParameterKnob(id, label, defBool);
            }
        };
    }

    // ==================== 私有静态解析辅助方法 ====================

    private static String getString(java.util.Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return val.toString();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Number> getList(java.util.Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            throw new IllegalArgumentException("缺少字段: " + key);
        }
        if (!(val instanceof java.util.List<?> list)) {
            throw new IllegalArgumentException("字段 '" + key + "' 应为数组");
        }
        java.util.List<Number> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number n) {
                result.add(n);
            } else {
                try {
                    result.add(Double.parseDouble(item.toString()));
                } catch (NumberFormatException e) {
                    result.add(0.0);
                }
            }
        }
        return result;
    }

    private static double getNumber(java.util.Map<String, Object> map, String key, double defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int getInt(java.util.Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getBoolean(java.util.Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }
}
