// Renderium - 运行模式枚举
// v6 三模式架构：兼容模式(COMPATIBILITY) / 受限兼容(COMPATIBILITY_LIMITED) / 狂暴模式(AGGRESSIVE)

package com.renderium.core;

/**
 * Renderium v6 三轨制运行模式枚举。
 *
 * <p>设计来源：
 * <ul>
 *   <li>双轨制.md：兼容模式 + 狂暴模式</li>
 *   <li>总体概念设计.md §一：顶层架构 - 双模式切换</li>
 *   <li>总体概念设计2.md §一：生态策略 - 双轨制</li>
 *   <li>v6 Phase 0: Sodium 残留清理与策略决策</li>
 * </ul>
 *
 * <h2>v6 核心哲学</h2>
 * <p><b>Renderium 不优化 Minecraft，它只是用极致的位掩码和堆外指针，
 * 在 MC 的尸体上搭建了一台不染一丝温度的图形指令吞吐机。</b></p>
 *
 * <h2>v6 模式定义</h2>
 * <ul>
 *   <li><b>COMPATIBILITY（兼容模式）</b>：纯净环境下的后处理 + 安全微调。
 *       无 Sodium 时默认启用或用户手动选择。</li>
 *   <li><b>COMPATIBILITY_LIMITED（受限兼容模式）</b>：检测到 Sodium 时自动启用。
 *       仅提供基础监控和日志，不进行 FBO 拦截和输出重定向。</li>
 *   <li><b>AGGRESSIVE（狂暴模式）</b>：完整 Blaze3D 优化 + Vulkan 调度优化
 *       + 应用层优化，用户手动选择（无 Sodium 时可用）。</li>
 * </ul>
 *
 * <h2>v5 → v6 变更说明</h2>
 * <p>自 v6 起，Renderium 不再深度集成 Sodium。新增 {@link #COMPATIBILITY_LIMITED} 模式，
 * 当检测到 Sodium 时自动切换到此模式，仅保留基础检测功能，移除所有 FBO 拦截逻辑。
 *
 * @author Renderium Team
 * @since 1.0.0
 * @version 6.0
 */
public enum RenderiumMode {

    /**
     * 兼容模式 (COMPATIBILITY) - 后处理 + 安全微调策略
     *
     * <p>无 Sodium 时默认启用。提供完整的后处理管线和安全微调功能。
     *
     * <h3>v6 特性定义</h3>
     * <ul>
     *   <li>✅ 完整后处理管线（DLSS/XeSS/FSR 超分辨率 + 帧生成）</li>
     *   <li>✅ 安全的 Blaze3D 微调（非侵入式性能优化）</li>
     *   <li>❌ 不进行 Chunk/PalettedContainer 劫持</li>
     *   <li>❌ 不替换底层存储结构</li>
     * </ul>
     *
     * @since 1.0.0
     */
    COMPATIBILITY("compatibility", true, false),

    /**
     * 受限兼容模式 (COMPATIBILITY_LIMITED) - 仅检测与日志
     *
     * <p>检测到 Sodium 时自动启用。在此模式下：
     * <ul>
     *   <li>❌ 不拦截/重定向 Sodium 输出</li>
     *   <li>❌ 不提供后处理增强（避免冲突）</li>
     *   <li>✅ 仅提供基础监控和日志记录</li>
     *   <li>✅ 向用户显示建议卸载 Sodium 的提示</li>
     * </ul>
     *
     * <h3>设计理由：</h3>
     * <p>Sodium 是优秀的优化模组，与 Renderium 存在功能重叠。
     * v6 策略建议用户二选一使用，而非尝试深度集成。
     * 此模式确保在共存时不会产生冲突或性能下降。
     *
     * @since 6.0
     */
    COMPATIBILITY_LIMITED("compatibility_limited", false, false),

    /**
     * 狂暴模式 (AGGRESSIVE) - 完整优化 + 夺舍策略
     *
     * <p>无 Sodium 时启用，或用户手动选择。引擎完全接管 Minecraft 的数据流转，
     * 架空原版的臃肿对象，用 Panama 堆外内存和纯 Vulkan 延迟管线进行降维打击。
     *
     * <h3>v6 特性定义</h3>
     * <ul>
     *   <li>✅ 完整 Blaze3D 优化（帧图优化、命令缓冲区优化）</li>
     *   <li>✅ Vulkan 调度优化（异步计算、多队列并行）</li>
     *   <li>✅ 应用层优化（Chunk 劫持、PalettedContainer 替换）</li>
     *   <li>劫持 ChunkSection，@Overwrite getBlockState/setBlockState</li>
     *   <li>替换 PalettedContainer → RenderiumCompactStorage (MemorySegment)</li>
     *   <li>底层存储: SoA 结构，堆外内存，零 GC</li>
     *   <li>独立实现面剔除/遮挡剔除/紧凑顶点（师承 Sodium LGPL-3.0）</li>
     *   <li>劫持官方 Vulkan 后端（黑盒劫持 VkDevice/VkQueue）</li>
     * </ul>
     *
     * <h3>网络/存档兼容性（来自 双轨制.md §二 第三步）</h3>
     * <p>在网络发包和存盘时，瞬间 new 一个原版 PalettedContainer（临时对象），
     * 把 MemorySegment 数据倒进去，调用原版逻辑写入流，然后 GC 秒杀临时对象。
     *
     * @since 1.0.0
     */
    AGGRESSIVE("aggressive", false, true);

    // ==================== 枚举字段定义 ====================

    /** 模式标识符（用于配置文件） */
    private final String id;

    /** 是否为完全兼容模式（非受限） */
    private final boolean fullCompatible;

    /** 是否为狂暴模式 */
    private final boolean aggressive;

    /**
     * 枚举构造函数
     *
     * @param id              模式标识符
     * @param fullCompatible  是否为完全兼容模式
     * @param aggressive      是否为狂暴模式
     */
    RenderiumMode(String id, boolean fullCompatible, boolean aggressive) {
        this.id = id;
        this.fullCompatible = fullCompatible;
        this.aggressive = aggressive;
    }

    // ==================== 模式查询接口 ====================

    /**
     * 检查当前模式是否为兼容模式（包括受限兼容）
     *
     * <p>v6 更新：COMPATIBILITY 和 COMPATIBILITY_LIMITED 都返回 true
     *
     * @return true 如果是兼容模式（含受限兼容）
     */
    public boolean isCompatible() {
        return this == COMPATIBILITY || this == COMPATIBILITY_LIMITED;
    }

    /**
     * 检查当前模式是否为完全兼容模式（非受限）
     *
     * @return true 如果是完全兼容模式（无 Sodium 时启用）
     * @since 6.0
     */
    public boolean isFullCompatible() {
        return fullCompatible;
    }

    /**
     * 检查当前模式是否为受限兼容模式
     *
     * @return true 如果是受限兼容模式（检测到 Sodium 时启用）
     * @since 6.0
     */
    public boolean isCompatibilityLimited() {
        return this == COMPATIBILITY_LIMITED;
    }

    /**
     * 检查当前模式是否为狂暴模式
     *
     * @return true 如果是狂暴模式（纯净环境或用户选择时启用）
     */
    public boolean isAggressive() {
        return aggressive;
    }

    // ==================== 能力查询接口 ====================

    /**
     * 当前模式是否支持 Blaze3D 帧图优化
     *
     * <p>v5 能力矩阵：
     * <ul>
     *   <li>COMPATIBILITY: ❌ 不支持（仅安全微调）</li>
     *   <li>AGGRESSIVE: ✅ 支持（完整帧图重建与优化）</li>
     * </ul>
     *
     * @return true 如果支持帧图优化
     */
    public boolean supportsFrameGraphOptimization() {
        return this == AGGRESSIVE;
    }

    /**
     * 当前模式是否支持 Vulkan 命令缓冲区优化
     *
     * <p>v5 能力矩阵：
     * <ul>
     *   <li>COMPATIBILITY: ❌ 不支持（使用默认命令提交）</li>
     *   <li>AGGRESSIVE: ✅ 支持（批量合并、异步提交、多队列调度）</li>
     * </ul>
     *
     * @return true 如果支持 Vulkan 命令优化
     */
    public boolean supportsVulkanCommandOptimization() {
        return this == AGGRESSIVE;
    }

    /**
     * 当前模式是否支持内存优化（堆外存储）
     *
     * <p>v5 能力矩阵：
     * <ul>
     *   <li>COMPATIBILITY: ❌ 不支持（使用原版内存管理）</li>
     *   <li>AGGRESSIVE: ✅ 支持（Panama MemorySegment, SoA, 零 GC）</li>
     * </ul>
     *
     * @return true 如果支持内存优化
     */
    public boolean supportsMemoryOptimization() {
        return this == AGGRESSIVE;
    }

    /**
     * 当前模式是否支持着色器管线优化
     *
     * <p>v5 能力矩阵：
     * <ul>
     *   <li>COMPATIBILITY: ⚠️ 有限支持（仅后处理着色器）</li>
     *   <li>AGGRESSIVE: ✅ 完整支持（自定义管线、SPIR-V 缓存）</li>
     * </ul>
     *
     * @return true 如果支持完整着色器管线优化
     */
    public boolean supportsShaderPipelineOptimization() {
        return this == AGGRESSIVE;
    }

    /**
     * 当前模式是否支持劫持 Chunk/PalettedContainer
     *
     * <p>只有狂暴模式才进行底层夺舍操作
     *
     * @return true 如果支持 Chunk 劫持
     */
    public boolean supportsChunkHijacking() {
        return this == AGGRESSIVE;
    }

    /**
     * 当前模式是否复用 Sodium 的优化结果
     *
     * <p>v6 更新：不再支持 Sodium 集成，此方法始终返回 false
     *
     * @return 始终返回 false（v6 不再集成 Sodium）
     * @deprecated 自 v6.0 起，Renderium 不再深度集成 Sodium
     */
    @Deprecated(since = "6.0")
    public boolean usesSodiumOptimizations() {
        return false; // v6: 不再使用 Sodium 优化
    }

    /**
     * 当前模式是否允许后处理管线运行
     *
     * <p>v6 能力矩阵：
     * <ul>
     *   <li>COMPATIBILITY: ✅ 完整后处理支持</li>
     *   <li>COMPATIBILITY_LIMITED: ❌ 不支持（避免与 Sodium 冲突）</li>
     *   <li>AGGRESSIVE: ✅ 完整后处理支持（原生 Vulkan 管线）</li>
     * </ul>
     *
     * @return true 如果允许后处理
     */
    public boolean supportsPostProcessing() {
        return this != COMPATIBILITY_LIMITED; // 受限兼容模式不启用后处理
    }

    /**
     * 获取模式的显示名称（用于 UI 和日志）
     *
     * @return 中文显示名称
     */
    public String getDisplayName() {
        return switch (this) {
            case COMPATIBILITY -> "兼容模式";
            case COMPATIBILITY_LIMITED -> "受限兼容模式";
            case AGGRESSIVE -> "狂暴模式";
        };
    }

    /**
     * 获取模式的英文名称（用于配置文件）
     *
     * @return 英文标识符
     */
    public String getId() {
        return id;
    }
}
