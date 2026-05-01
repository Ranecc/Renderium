// Renderium - 光线追踪模块
// RTConfig - 光线追踪配置类
// 功能: 定义光线追踪渲染的各项配置参数

package com.ranecc.renderium.feature.raytracing.module.impl.raytracing;

/**
 * 光线追踪配置 🎯
 * <p>
 * 封装光线追踪模块的所有可配置参数，
 * 支持运行时动态调整以平衡画质和性能。
 *
 * <h2>配置分类：</h2>
 * <ul>
 *   <li><b>阴影设置</b>: 阴影类型、质量等级</li>
 *   <li><b>反射设置</b>: 反射开关、最大距离</li>
 *   <li><b>AO 设置</b>: 环境光遮蔽半径、强度</li>
 *   <li><b>全局设置</b>: 采样数、递归深度</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * RTConfig config = new RTConfig();
 * config.shadowsEnabled = true;
 * config.shadowQuality = ShadowQuality.RAY_TRACED;
 * config.aoEnabled = true;
 * config.samplesPerPixel = 2;  // 2 SPP for better quality
 *
 * rayTracingModule.configure(config);
 * </pre>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see RayTracingModule
 */
public final class RTConfig {

    // ==================== 阴影设置 ====================

    /**
     * 是否启用光线追踪阴影
     * <p>
     * 默认: true
     * 关闭后将回退到传统阴影映射 (Shadow Mapping)
     */
    public boolean shadowsEnabled = true;

    /**
     * 阴影质量等级
     * <p>
     * 默认: RAY_TRACED (完全光线追踪)
     * 可选: HARD (硬阴影), SOFT (软阴影), PCSS (接触阴影硬化)
     */
    public ShadowQuality shadowQuality = ShadowQuality.RAY_TRACED;

    // ==================== 反射设置 ====================

    /**
     * 是否启用光线追踪反射
     * <p>
     * 默认: false (性能考虑，默认关闭)
     * 启用后将为反射表面提供真实的镜面反射效果
     */
    public boolean reflectionsEnabled = false;

    /**
     * 反射光线最大距离 (世界单位)
     * <p>
     * 超过此距离的光线将停止追踪并返回环境色。
     * 较小的值可以提高性能但可能丢失远处的反射细节。
     * 默认: 100.0 米
     */
    public float reflectionMaxDistance = 100.0f;

    // ==================== AO (环境光遮蔽) 设置 ====================

    /**
     * 是否启用光线追踪 AO (Ambient Occlusion)
     * <p>
     * 默认: true
     * AO 可以增强场景的深度感和立体感，
     * 特别是在角落和接触阴影区域。
     */
    public boolean aoEnabled = true;

    /**
     * AO 采样半径 (世界单位)
     * <p>
     * 决定 AO 效果的影响范围。
     * 较大的值产生更柔和的全局遮蔽，
     * 较小的值产生更精细的局部遮蔽。
     * 默认: 5.0 米
     */
    public float aoRadius = 5.0f;

    /**
     * AO 强度因子
     * <p>
     * 控制 AO 效果的强度 (0.0 ~ 1.0)。
     * 1.0 表示最强遮蔽，0.0 表示无遮蔽。
     * 默认: 1.0
     */
    public float aoIntensity = 1.0f;

    // ==================== 全局采样与递归设置 ====================

    /**
     * 每像素采样数 (SPP - Samples Per Pixel)
     * <p>
     * 控制每个像素发射的光线数量。
     * 更高的 SPP 产生更少的噪点但需要更多计算时间:
     * <ul>
     *   <li>1 SPP: 实时性能，但有噪点 (适合预览)</li>
     *   <li>2-4 SPP: 平衡质量和性能 (推荐)</li>
     *   <li>8+ SPP: 高质量，但性能开销大</li>
     * </ul>
     * 默认: 1 (实时性能优先)
     */
    public int samplesPerPixel = 1;

    /**
     * 最大递归深度 (光线弹跳次数)
     * <p>
     * 控制光线在场景中反射/折射的最大次数。
     * 更高的值可以模拟多次反射（如镜子中的镜子），
     * 但会显著增加计算量:
     * <ul>
     *   <li>1: 仅直接光照 + 阴影</li>
     *   <li>2: 直接 + 一次反射 (推荐)</li>
     *   <li>3+: 多次反射 (高质量，高开销)</li>
     * </ul>
     * 默认: 2
     */
    public int maxRecursionDepth = 2;

    // ==================== 构造函数 ====================

    /**
     * 创建默认配置实例
     * <p>
     * 使用所有参数的默认值：
     * <ul>
     *   <li>阴影: 启用, RAY_TRACED 质量</li>
     *   <li>反射: 禁用</li>
     *   <li>AO: 启用, 半径 5.0m</li>
     *   <li>SPP: 1</li>
     *   <li>递归深度: 2</li>
     * </ul>
     */
    public RTConfig() {
        // 使用默认值初始化 (已在字段声明中指定)
    }

    // ==================== 验证方法 ====================

    /**
     * 验证配置参数的有效性
     *
     * @return true 如果所有参数都在有效范围内
     */
    public boolean validate() {
        if (samplesPerPixel < 1 || samplesPerPixel > 16) {
            return false; // SPP 超出合理范围
        }
        if (maxRecursionDepth < 1 || maxRecursionDepth > 10) {
            return false; // 递归深度超出合理范围
        }
        if (aoRadius <= 0 || aoRadius > 50.0f) {
            return false; // AO 半径无效
        }
        if (reflectionMaxDistance <= 0) {
            return false; // 反射距离必须 > 0
        }
        if (aoIntensity < 0.0f || aoIntensity > 1.0f) {
            return false; // AO 强度必须在 [0, 1]
        }
        return true;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建高性能配置 (低质量，高帧率)
     *
     * @return 针对性能优化的配置实例
     */
    public static RTConfig createPerformancePreset() {
        RTConfig config = new RTConfig();
        config.shadowsEnabled = true;
        config.shadowQuality = ShadowQuality.HARD;  // 硬阴影 (最快)
        config.reflectionsEnabled = false;
        config.aoEnabled = false;                   // 禁用 AO
        config.samplesPerPixel = 1;
        config.maxRecursionDepth = 1;               // 最小递归
        return config;
    }

    /**
     * 创建均衡配置 (质量与性能平衡)
     *
     * @return 均衡的配置实例
     */
    public static RTConfig createBalancedPreset() {
        RTConfig config = new RTConfig();
        config.shadowsEnabled = true;
        config.shadowQuality = ShadowQuality.SOFT;  // 软阴影
        config.reflectionsEnabled = false;
        config.aoEnabled = true;
        config.aoRadius = 3.0f;                     // 较小半径
        config.samplesPerPixel = 1;
        config.maxRecursionDepth = 2;
        return config;
    }

    /**
     * 创建高质量配置 (最佳画质，较低帧率)
     *
     * @return 针对画质优化的配置实例
     */
    public static RTConfig createQualityPreset() {
        RTConfig config = new RTConfig();
        config.shadowsEnabled = true;
        config.shadowQuality = ShadowQuality.PCSS;  // 接触阴影硬化
        config.reflectionsEnabled = true;
        config.reflectionMaxDistance = 200.0f;      // 更远距离
        config.aoEnabled = true;
        config.aoRadius = 8.0f;                     // 更大范围
        config.aoIntensity = 1.0f;
        config.samplesPerPixel = 4;                 // 4 SPP
        config.maxRecursionDepth = 3;               // 更多弹跳
        return config;
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format(
                "RTConfig{shadows=%b(%s), reflections=%b(%.0fm), AO=%b(r=%.1fm), " +
                "SPP=%d, recursion=%d}",
                shadowsEnabled, shadowQuality,
                reflectionsEnabled, reflectionMaxDistance,
                aoEnabled, aoRadius,
                samplesPerPixel, maxRecursionDepth
        );
    }
}
