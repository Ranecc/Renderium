// Renderium - Blaze3D VMA 渐进式清理模块
// 资源类型枚举 - 定义清理优先级

package com.ranecc.renderium.feature.blaze3d.memory;

/**
 * GPU 资源类型。
 * <p>
 * 定义不同类型资源的清理优先级和行为特征，
 * 供 {@link ResourceTracker} 和 {@link GradualCleanupStrategy} 使用。</p>
 *
 * <h2>优先级分类：</h2>
 * <pre>
 * ┌────────────────────┬──────────┬─────────────────────────────┐
 * │ 类别               │ 优先级   │ 特征                         │
 * ├────────────────────┼──────────┼─────────────────────────────┤
 * │ 高优先级 (先清理)   │ 1-4      │ 可异步重建，低访问频率       │
 * │ 中优先级           │ 5-6      │ 偶尔使用，可延迟加载         │
 * │ 低优先级 (后清理)   │ 7-9      │ 频繁使用，不可丢失           │
 * └────────────────────┴──────────┴─────────────────────────────┘
 * </pre>
 *
 * @see ResourceTracker 按本类型追踪资源
 * @since 2.0.0
 */
public enum ResourceType {

    // ========== 高优先级: 先清理 (冷数据，可异步重建) ==========

    /** 缓存纹理 — 远处地形/天空等，可按需重新生成 */
    CACHED_TEXTURE(1, "缓存纹理", true),

    /** 远处区块网格 — 超出渲染距离的区块 mesh */
    DISTANT_CHUNK_MESH(2, "远处区块网格", true),

    /** 未使用的着色器 SPIR-V — 可从磁盘缓存重载 */
    UNUSED_SHADER(3, "未使用着色器", true),

    /** 临时缓冲 — Staging/一次性数据 */
    TEMPORARY_BUFFER(4, "临时缓冲", true),

    // ========== 中优先级: 偶尔使用 ==========

    /** 实体模型 — 当前不在视野内的实体 */
    ENTITY_MODEL(5, "实体模型", false),

    /** 粒子纹理 — 粒子系统释放后可回收 */
    PARTICLE_TEXTURE(6, "粒子纹理", false),

    // ========== 低优先级: 后清理 (热数据，频繁使用) ==========

    /** 近处区块网格 — 玩家周围的活跃区块 */
    NEAR_CHUNK_MESH(7, "近处区块网格", false),

    /** GUI 界面纹理 — 界面需要时必须可用 */
    GUI_TEXTURE(8, "GUI 纹理", false),

    /** 天空盒纹理 — 每帧都可见 */
    SKYBOX_TEXTURE(9, "天空盒纹理", false);

    /** 清理优先级 (数值越小越先被清理) */
    public final int cleanupPriority;

    /** 可读名称 */
    public final String description;

    /** 是否支持异步重建 (清理前可预加载替代资源) */
    public final boolean canRecreateAsync;

    ResourceType(int priority, String description, boolean canRecreateAsync) {
        this.cleanupPriority = priority;
        this.description = description;
        this.canRecreateAsync = canRecreateAsync;
    }
}
