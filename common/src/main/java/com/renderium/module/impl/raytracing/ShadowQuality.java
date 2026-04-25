// Renderium - 光线追踪模块
// ShadowQuality - 阴影质量枚举
// 功能: 定义光线追踪阴影的质量等级

package com.renderium.module.impl.raytracing;

/**
 * 阴影质量等级枚举 🌑
 * <p>
 * 定义光线追踪阴影的不同质量级别，
 * 从简单的硬阴影到高质量的接触阴影硬化 (PCSS)。
 *
 * <h2>质量与性能对比：</h2>
 * <pre>
 * ┌─────────────────┬──────────────┬────────────┬────────────────────────────┐
 * │ 质量           │ 光线数/像素   │ 性能开销    │ 视觉效果                   │
 * ├─────────────────┼──────────────┼────────────┼────────────────────────────────┤
 * │ HARD            │ 1            │ ★★☆☆☆      │ 锐利边缘，无过渡             │
 * │ SOFT            │ 8-16         │ ★★★☆☆      │ 柔和边缘，自然阴影           │
 * │ RAY_TRACED      │ 16-32        │ ★★★★☆      │ 精确软阴影，Area Light 支持  │
 * │ PCSS            │ 32-64        │ ★★★★★      │ 接触阴影硬化，电影级效果     │
 * └─────────────────┴──────────────┴────────────┴────────────────────────────────┘
 * </pre>
 *
 * @author Renderium Team
 * @since 3.0.0
 * @see RTConfig#shadowQuality
 */
public enum ShadowQuality {

    /**
     * 硬阴影 (Hard Shadows)
     * <p>
     * 最简单且最快的阴影模式。
     * 每个像素仅发射 1 条光线到光源，结果为二值 (亮/暗)。
     * 边缘锐利，无半影区域。
     *
     * <h3>适用场景：</h3>
     * <ul>
     *   <li>性能受限的硬件</li>
     *   <li>卡通/风格化渲染</li>
     *   <li>远距离或小面积光源 (如太阳光)</li>
     * </ul>
     */
    HARD("Hard Shadows", "硬阴影", 1, 1),

    /**
     * 软阴影 (Soft Shadows)
     * <p>
     * 通过在光源区域采样多条光线实现柔和的阴影边缘。
     * 使用 Poisson Disk 或 Stratified Sampling 减少噪点。
     *
     * <h3>技术细节：</h3>
     * <ul>
     *   <li>每像素 8-16 条光线采样</li>
     *   <li>光源直径决定半影大小</li>
     *   <li>可结合时间抗锯齿 (TAA) 进一步降噪</li>
     * </ul>
     */
    SOFT("Soft Shadows", "软阴影", 2, 12),

    /**
     * 完全光线追踪阴影 (Ray-Traced Shadows)
     * <p>
     * 高质量的 Area Light 阴影，支持任意形状的光源。
     * 使用更多采样点获得更平滑的阴影过渡。
     *
     * <h3>技术细节：</h3>
     * <ul>
     *   <li>每像素 16-32 条光线采样</li>
     *   <li>支持 Rectangle/Disc/Sphere 等光源形状</li>
     *   <li>精确的物理光照模拟</li>
     *   <li>推荐配合 DLSS/FSR 超分使用</li>
     * </ul>
     */
    RAY_TRACED("Ray-Traced Shadows", "光线追踪阴影", 4, 24),

    /**
     * 接触阴影硬化 (Contact Hardening Shadows - PCSS)
     * <p>
     * 最高质量的阴影模式，基于 Percentage-Closer Soft Shadows 算法。
     * 阴影边缘会根据遮挡物距离自动调整硬度：
     * 近距离接触处产生更硬的阴影，远处产生更软的阴影。
     *
     * <h3>视觉效果：</h3>
     * <pre>
     * 传统软阴影: ████████████████████░░░░░░ (均匀渐变)
     * PCSS 阴影:   ████████████████████▓▓▓░░░░ (接触处更硬)
     *                              ↑
     *                        接触点 (更锐利)
     * </pre>
     *
     * <h3>⚠️ 性能警告：</h3>
     * 此模式需要大量光线采样 (32-64 SPP)，仅在高端 GPU 上实时可行！
     */
    PCSS("Contact Hardening Shadows", "接触阴影硬化", 5, 48);

    // ==================== 字段 ====================

    /** 英文名称 */
    private final String englishName;

    /** 中文名称 */
    private final String chineseName;

    /** 性能开销等级 (1-5) */
    private final int performanceCost;

    /** 默认每像素采样数 */
    private final int defaultSamplesPerPixel;

    // ==================== 构造函数 ====================

    ShadowQuality(String englishName, String chineseName,
                  int performanceCost, int defaultSamplesPerPixel) {
        this.englishName = englishName;
        this.chineseName = chineseName;
        this.performanceCost = performanceCost;
        this.defaultSamplesPerPixel = defaultSamplesPerPixel;
    }

    // ==================== Getter 方法 ====================

    public String getEnglishName() { return englishName; }

    public String getChineseName() { return chineseName; }

    public int getPerformanceCost() { return performanceCost; }

    public int getDefaultSamplesPerPixel() { return defaultSamplesPerPixel; }

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format("%s (%s) [成本: %d/5, SPP: %d]",
                englishName, chineseName, performanceCost, defaultSamplesPerPixel);
    }
}
