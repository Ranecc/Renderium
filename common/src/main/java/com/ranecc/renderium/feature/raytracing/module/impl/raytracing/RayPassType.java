// Renderium - 光线追踪模块
// RayPassType - 光线追踪 Pass 类型枚举
// 功能: 定义支持的光线追踪渲染 Pass 类型

package com.ranecc.renderium.feature.raytracing.module.impl.raytracing;

/**
 * 光线追踪 Pass 类型枚举 🌟
 * <p>
 * 定义光线追踪模块支持的不同渲染 Pass 类型。
 * 每个 Pass 类型对应不同的光照效果和计算复杂度，
 * 可以独立启用/禁用以灵活控制性能开销。
 *
 * <h2>Pass 类型说明：</h2>
 * <ul>
 *   <li><b>SHADOW_RAYS</b>: 阴影光线 - 检测光源到表面是否被遮挡</li>
 *   <li><b>REFLECTION_RAYS</b>: 反射光线 - 计算镜面反射效果</li>
 *   <li><b>AMBIENT_OCCLUSION</b>: 环境光遮蔽 - 增强接触阴影和深度感</li>
 *   <li><b>GLOBAL_ILLUMINATION</b>: 全局光照 - 完整的路径追踪 (高开销)</li>
 * </ul>
 *
 * <h3>性能开销对比 (从低到高)：</h3>
 * <pre>
 * AMBIENT_OCCLUSION  ★★☆☆☆  低
 * SHADOW_RAYS        ★★★☆☆  中
 * REFLECTION_RAYS    ★★★★☆  高
 * GLOBAL_ILLUMINATION★★★★★  很高
 * </pre>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see RayTracingModule#traceRays(RayPassType, Object, Attachment[], long)
 */
public enum RayPassType {

    /**
     * 阴影光线 ☀️
     * <p>
     * 从着色点向光源发射光线，检测是否有遮挡物。
     * 支持硬阴影、软阴影、PCSS 等多种质量等级。
     *
     * <h3>技术细节：</h3>
     * <ul>
     *   <li>每像素发射 1-N 条光线到每个光源</li>
     *   <li>Area Light 需要多条光线采样</li>
     *   <li>可结合 PCF/PCSS 实现软阴影边缘</li>
     *   <li>性能开销: 中等 (取决于光源数量和采样数)</li>
     * </ul>
     *
     * <h3>替代方案：</h3>
     * 关闭时回退到 Shadow Mapping (传统阴影贴图)
     */
    SHADOW_RAYS("Shadow Rays", "阴影光线", 2),

    /**
     * 反射光线 🪞
     * <p>
     * 从着色点沿反射方向发射光线，计算镜面反射颜色。
     * 用于水面、金属表面、玻璃等反射材质。
     *
     * <h3>技术细节：</h3>
     * <ul>
     *   <li>基于物理的反射模型 (Fresnel 效应)</li>
     *   <li>支持模糊反射 (Glossy Reflection) 通过多条光线</li>
     *   <li>最大距离限制避免无限追踪</li>
     *   <li>性能开销: 高 (取决于场景复杂度和反射率)</li>
     * </ul>
     *
     * <h3>使用建议：</h3>
     * 仅在有明显反射表面的场景中启用 (水面、金属等)
     */
    REFLECTION_RAYS("Reflection Rays", "反射光线", 4),

    /**
     * 环境光遮蔽 (AO) 🔵
     * <p>
     * 在着色点周围发射半球光线，检测几何遮挡程度。
     * 用于增强角落和接触阴影的暗度，增加深度感。
     *
     * <h3>技术细节：</h3>
     * <ul>
     *   <li>Screen-Space AO (SSAO) vs Ray-Traced AO (RTAO)</li>
     *   <li>本模块实现的是 RTAO (更精确但较慢)</li>
     *   <li>采样半径控制影响范围</li>
     *   <li>通常 16-32 条光线/像素即可获得良好效果</li>
     *   <li>性能开销: 低-中等 (可通过降采样优化)</li>
     * </ul>
     *
     * <h3>视觉效果：</h3>
     * 使角落变暗、接触阴影加深，增强立体感
     */
    AMBIENT_OCCLUSION("Ambient Occlusion", "环境光遮蔽", 1),

    /**
     * 全局光照 (GI) 🌍
     * <p>
     * 完整的路径追踪实现，模拟光的多次弹跳。
     * 提供最真实的全局光照效果，包括：
     * <ul>
     *   <li>Color Bleeding (颜色溢出)</li>
     *   <li>Caucustics (焦散)</li>
     *   <li>Indirect Lighting (间接光照)</li>
     *   <li>Global Illumination (全局光照)</li>
     * </ul>
     *
     * <h3>⚠️ 性能警告：</h3>
     * 此 Pass 开销非常高！仅在以下情况考虑启用:
     * <ul>
     *   <li>RTX 3070+ / RX 6800+ 硬件</li>
     *   <li>分辨率 ≤ 1080p 或使用 DLSS/FSR 超分</li>
     *   <li>SPP 较低 (1-2) 且递归深度有限 (2-3)</li>
     *   <li>静态或缓慢变化的场景 (可复用历史帧)</li>
     * </ul>
     *
     * <h3>替代方案：</h3>
     * 可使用 Light Probes / Irradiance Caching 等预计算 GI 方案
     */
    GLOBAL_ILLUMINATION("Global Illumination", "全局光照", 5);

    // ==================== 字段 ====================

    /** 英文名称 */
    private final String englishName;

    /** 中文名称 */
    private final String chineseName;

    /** 性能开销等级 (1-5, 1=最低, 5=最高) */
    private final int performanceCost;

    // ==================== 构造函数 ====================

    /**
     * 创建 Pass 类型枚举值
     *
     * @param englishName     英文名称
     * @param chineseName     中文名称
     * @param performanceCost 性能开销等级 (1-5)
     */
    RayPassType(String englishName, String chineseName, int performanceCost) {
        this.englishName = englishName;
        this.chineseName = chineseName;
        this.performanceCost = performanceCost;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取英文名称
     *
     * @return 名称字符串
     */
    public String getEnglishName() {
        return englishName;
    }

    /**
     * 获取中文名称
     *
     * @return 中文名称字符串
     */
    public String getChineseName() {
        return chineseName;
    }

    /**
     * 获取性能开销等级
     *
     * @return 开销等级 (1-5)
     */
    public int getPerformanceCost() {
        return performanceCost;
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format("%s (%s) [成本: %d/5]", englishName, chineseName, performanceCost);
    }
}
