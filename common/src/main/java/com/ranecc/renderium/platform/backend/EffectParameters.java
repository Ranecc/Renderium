// Renderium - 后处理效果参数容器
// 存储各后处理效果的运行时可配置参数

package com.ranecc.renderium.platform.backend;

import com.ranecc.renderium.domain.enums.EffectType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 后处理效果参数容器
 * <p>
 * 存储每种后处理效果的所有运行时参数。
 * 支持动态修改（用于 Lua 脚本或 UI 实时调整）。
 * <p>
 * 每种效果类型有独立的参数集：
 * <ul>
 *   <li><b>Bloom</b>: intensity, threshold, radius</li>
 *   <li><b>DOF</b>: focalDistance, aperture, blurStrength</li>
 *   <li><b>MotionBlur</b>: strength, sampleCount</li>
 * </ul>
 *
 * <h3>线程安全：</h3>
 * <p>此类非线程安全。如果需要从多个线程访问，
 * 调用方应自行同步（通常在渲染线程使用即可）。
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 获取 Bloom 默认参数
 * EffectParameters bloomParams = EffectParameters.createDefault(EffectType.BLOOM);
 *
 * // 修改强度
 * bloomParams.setFloat("intensity", 0.8f);
 *
 * // 从 Lua 动态更新
 * effectPipeline.updateEffectParameter(EffectType.BLOOM, "threshold", 0.9f);
 * </pre>
 *
 * @see EffectType
 * @see EffectPipeline
 */
public final class EffectParameters {

    // ==================== 常量：参数名称定义 ====================

    /** ========== Bloom 参数 ========== */

    /** 泛光强度 (0.0 - 2.0，默认 0.5) */
    public static final String BLOOM_INTENSITY = "intensity";

    /** 高亮提取阈值 (0.0 - 1.0，默认 0.8) */
    public static final String BLOOM_THRESHOLD = "threshold";

    /** 模糊半径 (1 - 16，默认 6) */
    public static final String BLOOM_RADIUS = "radius";

    /** 降采样次数 (1 - 4，默认 2) */
    public static final String BLOOM_DOWNSAMPLES = "downsamples";

    /** ========== DOF 参数 ========== */

    /** 焦距（世界单位，默认 50.0） */
    public static final String DOF_FOCAL_DISTANCE = "focalDistance";

    /** 光圈大小 (0.1 - 32.0，默认 4.0) */
    public static final String DOF_APERTURE = "aperture";

    /** 模糊强度 (0.0 - 1.0，默认 0.5) */
    public static final String DOF_BLUR_STRENGTH = "blurStrength";

    /** 焦点过渡范围 (0.0 - 100.0，默认 10.0) */
    public static final String DOF_FOCAL_RANGE = "focalRange";

    /** ========== MotionBlur 参数 ========== */

    /** 模糊强度 (0.0 - 1.0，默认 0.5) */
    public static final String MOTION_BLUR_STRENGTH = "strength";

    /** 采样数量 (2 - 32，默认 8) */
    public static final String MOTION_BLUR_SAMPLE_COUNT = "sampleCount";

    // ==================== 字段 ====================

    /** 关联的效果类型 */
    private final EffectType effectType;

    /** 参数键值对存储 */
    private final Map<String, Float> floatParameters;

    /** 是否启用此效果 */
    private boolean enabled;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param effectType 效果类型
     * @param parameters 初始参数映射（可为空）
     * @param enabled    是否启用
     */
    private EffectParameters(EffectType effectType, Map<String, Float> parameters, boolean enabled) {
        this.effectType = Objects.requireNonNull(effectType, "effectType 不能为 null");
        this.floatParameters = new HashMap<>(parameters);
        this.enabled = enabled;
    }

    // ==================== 工厂方法：创建默认参数 ====================

    /**
     * 创建指定效果类型的默认参数
     * <p>
     * 根据效果类型返回预配置的默认值。
     * 所有参数都经过调优，适合大多数场景。
     *
     * @param type 效果类型（不能为 null）
     * @return 包含默认值的 EffectParameters 实例
     * @throws NullPointerException 如果 type 为 null
     */
    public static EffectParameters createDefault(EffectType type) {
        Objects.requireNonNull(type, "type 不能为 null");

        Map<String, Float> params = new HashMap<>();
        boolean defaultEnabled = true; // 默认启用

        switch (type) {
            case BLOOM -> {
                // Bloom 默认参数（平衡质量和性能）
                params.put(BLOOM_INTENSITY, 0.5f);      // 中等强度
                params.put(BLOOM_THRESHOLD, 0.8f);       // 较高阈值，只对最亮区域生效
                params.put(BLOOM_RADIUS, 6.0f);          // 中等模糊半径
                params.put(BLOOM_DOWNSAMPLES, 2.0f);     // 两次降采样
            }

            case DOF -> {
                // DOF 默认参数（电影级景深）
                params.put(DOF_FOCAL_DISTANCE, 50.0f);   // 焦距 50 格
                params.put(DOF_APERTURE, 4.0f);          // f/4 光圈
                params.put(DOF_BLUR_STRENGTH, 0.5f);     // 中等模糊
                params.put(DOF_FOCAL_RANGE, 10.0f);      // 10 格过渡范围
                defaultEnabled = false;                   // DOF 默认关闭（影响性能）
            }

            case MOTION_BLUR -> {
                // MotionBlur 默认参数（自然运动感）
                params.put(MOTION_BLUR_STRENGTH, 0.5f);  // 中等强度
                params.put(MOTION_BLUR_SAMPLE_COUNT, 8.0f); // 8 个采样点
                defaultEnabled = false;                   // MotionBlur 默认关闭
            }
        }

        return new EffectParameters(type, params, defaultEnabled);
    }

    // ==================== 参数访问方法 ====================

    /**
     * 获取浮点参数值
     *
     * @param name 参数名称（使用常量如 {@link #BLOOM_INTENSITY}）
     * @return 参数值
     * @throws IllegalArgumentException 如果参数不存在
     */
    public float getFloat(String name) {
        if (!floatParameters.containsKey(name)) {
            throw new IllegalArgumentException(
                String.format("未知参数 '%s' 对于效果类型 %s", name, effectType)
            );
        }
        return floatParameters.get(name);
    }

    /**
     * 设置浮点参数值
     * <p>
     * 会触发参数变更事件（如果已注册监听器）。
     * 用于 UI 滑块调整或 Lua 脚本动态修改。
     *
     * @param name  参数名称
     * @param value 新值
     * @throws IllegalArgumentException 如果参数不存在
     */
    public void setFloat(String name, float value) {
        if (!floatParameters.containsKey(name)) {
            throw new IllegalArgumentException(
                String.format("未知参数 '%s' 对于效果类型 %s", name, effectType)
            );
        }
        floatParameters.put(name, value);
    }

    /**
     * 安全获取浮点参数值（带默认值）
     * <p>
     * 如果参数不存在，返回指定的默认值而非抛异常。
     *
     * @param name         参数名称
     * @param defaultValue 参数不存在时的默认值
     * @return 参数值或默认值
     */
    public float getFloatOrDefault(String name, float defaultValue) {
        return floatParameters.getOrDefault(name, defaultValue);
    }

    /**
     * 检查参数是否存在
     *
     * @param name 参数名称
     * @return true 如果该参数已定义
     */
    public boolean hasParameter(String name) {
        return floatParameters.containsKey(name);
    }

    // ==================== 启用/禁用控制 ====================

    /**
     * 检查效果是否启用
     *
     * @return true 如果当前启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置效果的启用状态
     *
     * @param enabled true 启用，false 禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ==================== 属性访问 ====================

    /**
     * 获取关联的效果类型
     *
     * @return EffectType 枚举值
     */
    public EffectType getEffectType() {
        return effectType;
    }

    /**
     * 获取所有参数的不可变视图
     *
     * @return 参数映射（不可修改）
     */
    public Map<String, Float> getAllParameters() {
        return Map.copyOf(floatParameters);
    }

    /**
     * 获取所有参数名称集合
     *
     * @return 参数名称集合
     */
    public Iterable<String> getParameterNames() {
        return floatParameters.keySet();
    }

    // ==================== 工具方法 ====================

    /**
     * 克隆参数对象
     * <p>
     * 创建一个包含相同参数的新实例。
     * 用于保存/恢复参数快照。
     *
     * @return 新的 EffectParameters 实例
     */
    public EffectParameters clone() {
        return new EffectParameters(effectType, new HashMap<>(floatParameters), enabled);
    }

    /**
     * 重置为默认值
     * <p>
     * 将所有参数恢复到 {@link #createDefault(EffectType)} 的初始状态。
     */
    public void resetToDefaults() {
        EffectParameters defaults = createDefault(effectType);
        this.floatParameters.clear();
        this.floatParameters.putAll(defaults.floatParameters);
        this.enabled = defaults.enabled;
    }

    @Override
    public String toString() {
        return String.format(
            "EffectParameters{type=%s, enabled=%s, params=%s}",
            effectType,
            enabled,
            floatParameters
        );
    }
}
