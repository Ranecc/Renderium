// Renderium - 动态参数配置系统
// 参数旋钮接口 - 定义可调参数的抽象

package com.ranecc.renderium.feature.shader.pipeline.parameter;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.parameter.ParameterKnob;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;

/**
 * 参数旋钮接口 (Parameter Knob)
 * <p>
 * 定义渲染管线中可动态调整的参数抽象。
 * 每个参数旋钮对应一个可通过 UI 或 API 热更新的渲染参数，
 * 如光照强度、阴影采样数、后处理效果开关等。
 *
 * <h2>设计目标：</h2>
 * <ul>
 *   <li><b>类型安全</b>：强类型约束，编译期检查参数类型</li>
 *   <li><b>运行时热更新</b>：支持在渲染过程中实时修改参数值</li>
 *   <li><b>范围限制</b>：内置最小/最大值校验，防止非法值</li>
 *   <li><b>线程安全</b>：使用 volatile/AtomicReference 保证多线程可见性</li>
 * </ul>
 *
 * <h2>支持的参数类型：</h2>
 * <table border="1">
 *   <tr><th>类型</th><th>说明</th><th>典型用途</th></tr>
 *   <tr><td>FLOAT</td><td>浮点数</td><td>泛光强度、曝光值、法线强度</td></tr>
 *   <tr><td>INT</td><td>整数</td><td>PCF 采样数、AO 采样数、最大迭代次数</td></tr>
 *   <tr><td>ENUM</td><td>枚举选择</td><td>滤镜类型、阴影算法、色调映射模式</td></tr *   <tr><td>BOOL</td><td>布尔开关</td><td>法线映射开关、SSAO 开关、TAA 开关</td></tr>
 *   <tr><td>VEC2</td><td>二维向量</td><td>纹理偏移、UV 缩放</td></tr>
 *   <tr><td>VEC3</td><td>三维向量</td><td>光源颜色、环境光方向</td></tr>
 *   <tr><td>COLOR</td><td>颜色值 (RGBA)</td><td>雾颜色、天空盒颜色、色调偏移</td></tr>
 * </table>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │           ShaderParameterConfig         │  ← 参数容器（关联到 PipelineNode）
 * │    ┌────────────┬────────────┐          │
 * │    │ FloatKnob  │  IntKnob   │ ...      │
 * │    └────────────┴────────────┘          │
 * └──────────────────┬──────────────────────┘
 *                    │
 *                    ▼
 * ┌─────────────────────────────────────────┐
 * │        ParameterRegistry               │  ← 全局注册表（单例）
 * │   按 Category 分类管理所有 ParameterKnob  │
 * └─────────────────────────────────────────┘
 *                    │
 *                    ▼
 *            applyTo(RenderContext)
 *                    │
 *                    ▼
 *         GPU Uniforms / UBO
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建浮点参数旋钮
 * ParameterKnob<Float> bloomStrength = new FloatKnob.Builder("bloom_strength")
 *     .displayName("泛光强度")
 *     .range(0.0f, 2.0f)
 *     .step(0.1f)
 *     .defaultValue(1.0f)
 *     .category(ParameterCategory.POST_PROCESS)
 *     .build();
 *
 * // 注册到全局注册表
 * ParameterRegistry.getInstance().register(bloomStrength);
 *
 * // 运行时热更新（线程安全）
 * bloomStrength.setValue(1.5f);  // 立即生效，渲染线程可见
 *
 * // 获取当前值
 * float current = bloomStrength.getValue();  // 1.5f
 * }</pre>
 *
 * @param <T> 参数值的类型（Float, Integer, Boolean, String, float[], 等）
 * @see FloatKnob
 * @see IntKnob
 * @see EnumKnob
 * @see BoolKnob
 * @see ParameterRegistry
 * @see ShaderParameterConfig
 * @since 6.0.0
 */
public interface ParameterKnob<T> {

    // ==================== 核心标识方法 ====================

    /**
     * 获取参数唯一标识符
     * <p>
     * 标识符用于在注册表和配置文件中引用此参数。
     * 必须全局唯一，建议使用 kebab-case 命名格式。
     * <p>
     * 示例："normal-strength"、"pcf-samples"、"enable-normal-mapping"
     *
     * 【返回值】
     * @return String - 唯一标识符，不可为 null
     */
    String getId();

    /**
     * 获取参数显示名称
     * <p>
     * 用于 UI 标签文本、日志输出和配置文件的可读性。
     * 应使用简洁明了的中文名称。
     *
     * 【返回值】
     * @return String - 人类可读的显示名称，如 "法线强度"、"PCF 采样数"
     */
    String getDisplayName();

    /**
     * 获取参数类型
     * <p>
     * 决定此参数的数据格式和 UI 展示方式。
     *
     * 【返回值】
     * @return KnobType - 参数类型枚举值
     */
    KnobType getType();

    // ==================== 值操作方法 ====================

    /**
     * 获取当前参数值
     * <p>
     * 此方法必须保证线程安全，返回最新的运行时值。
     * 实现类应使用 volatile 字段或 AtomicReference 来存储值。
     *
     * 【返回值】
     * @return T - 当前参数值（具体类型取决于实现）
     */
    T getValue();

    /**
     * 设置新的参数值
     * <p>
     * 支持运行时热更新。新值会立即对渲染线程可见。
     * 实现类应在设置前进行范围校验。
     *
     * 【方法参数】
     * @param value T - 新的参数值（不能为 null）
     *
     * @throws IllegalArgumentException 如果值超出允许范围
     */
    void setValue(T value);

    // ==================== 范围与默认值方法 ====================

    /**
     * 获取参数的有效范围
     * <p>
     * 返回一个包含 [最小值, 最大值] 的数组。
     * 对于无范围限制的类型（如 BOOL、ENUM），返回空数组或特定标记值。
     *
     * 【返回值】
     * @return Object[] - 范围数组 [min, max]，长度为 2；对于 ENUM 类型返回选项列表
     */
    Object[] getRange();

    /**
     * 获取参数默认值
     * <p>
     * 用于重置参数和初始化配置。
     *
     * 【返回值】
     * @return T - 默认参数值
     */
    T getDefault();

    /**
     * 重置参数为默认值
     * <p>
     * 将当前值恢复为构造时指定的默认值。
     */
    void resetToDefault();

    // ==================== 分类与元数据方法 ====================

    /**
     * 获取参数所属分类
     * <p>
     * 分类用于在 UI 中分组显示参数，
     * 也用于按类别批量查询和导出配置。
     *
     * 【返回值】
     * @return ParameterCategory - 参数分类枚举值
     */
    ParameterCategory getCategory();

    /**
     * 获取参数描述信息
     * <p>
     * 可选的详细说明文字，用于 UI 工具提示。
     *
     * 【返回值】
     * @return String - 描述文本，可能为空字符串
     */
    default String getDescription() {
        return "";
    }

    /**
     * 检查当前值是否为默认值
     * <p>
     * 用于 UI 显示"已修改"状态标记。
     *
     * 【返回值】
     * @return boolean - true 表示当前值为默认值，false 表示已被修改
     */
    boolean isDefault();

    // ==================== 内部枚举定义 ====================

    /**
     * 参数类型枚举
     * <p>
     * 定义所有支持的参数旋钮数据类型。
     * 每种类型对应不同的 UI 控件和数据格式。
     */
    enum KnobType {
        /** 浮点数 - 使用滑块控件，支持步进值 */
        FLOAT,
        /** 整数 - 使用数字输入框或滑块 */
        INT,
        /** 枚举选择 - 使用下拉菜单 */
        ENUM,
        /** 布尔开关 - 使用复选框或开关按钮 */
        BOOL,
        /** 二维向量 - 使用双轴滑块或 X/Y 输入框 */
        VEC2,
        /** 三维向量 - 使用三轴滑块或 X/Y/Z 输入框 */
        VEC3,
        /** 颜色值 (RGBA) - 使用颜色选择器 */
        COLOR
    }

    /**
     * 参数分类枚举
     * <p>
     * 按照渲染管线阶段对参数进行逻辑分组。
     * 用于 UI 分组展示和批量操作。
     */
    enum ParameterCategory {
        /** 着色器相关参数（Uniform 变量、编译选项） */
        SHADER("着色器"),
        /** 光照计算参数（光源强度、环境光、BRDF） */
        LIGHTING("光照"),
        /** 阴影相关参数（阴影图分辨率、PCF/PCSS 配置） */
        SHADOW("阴影"),
        /** 后处理参数（Bloom、Tonemap、DOF、运动模糊） */
        POST_PROCESS("后处理"),
        /** 几何处理参数（LOD、剔除、网格优化） */
        GEOMETRY("几何"),
        /** 性能调优参数（质量等级、帧率限制） */
        PERFORMANCE("性能");

        /** 分类中文名称 */
        private final String displayName;

        ParameterCategory(String displayName) {
            this.displayName = displayName;
        }

        /**
         * 获取分类显示名称
         *
         * @return String - 中文名称
         */
        public String getDisplayName() {
            return displayName;
        }
    }

    // ==================== 变更监听器支持 ====================

    /**
     * 参数变更监听器接口
     * <p>
     * 当参数值发生变化时触发回调，
     * 用于响应式 UI 更新和联动参数调整。
     */
    interface ChangeListener<T> {
        /**
         * 参数值变更回调
         *
         * 【方法参数】
         * @param knob   ParameterKnob&lt;T&gt; - 发生变更的参数旋钮
         * @param oldValue T - 变更前的旧值
         * @param newValue T - 变更后的新值
         */
        void onValueChanged(ParameterKnob<T> knob, T oldValue, T newValue);
    }

    /**
     * 注册参数变更监听器
     *
     * 【方法参数】
     * @param listener ChangeListener&lt;T&gt; - 监听器实例
     */
    void addChangeListener(ChangeListener<T> listener);

    /**
     * 移除参数变更监听器
     *
     * 【方法参数】
     * @param listener ChangeListener&lt;T&gt; - 要移除的监听器实例
     */
    void removeChangeListener(ChangeListener<T> listener);
}
