// Renderium - 模块系统
// 模块分类枚举 - 定义模块类型和加载条件

package com.renderium.module;

/**
 * 模块分类
 * <p>
 * 用于组织和管理不同类型的模块，
 * 决定加载顺序、用户可见性和运行模式要求。
 *
 * <h2>模式过滤：</h2>
 * <ul>
 *   <li>{@link #CORE} / {@link #SYSTEM} - 始终加载（两种模式）</li>
 *   <li>{@link #OPTIMIZATION} - 兼容模式 + 狂暴模式</li>
 *   <li>{@link #AGGRESSIVE_ONLY} - 仅狂暴模式（类Sodium等）</li>
 *   <li>{@link #SHADER} - 狂暴模式光影</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public enum ModuleCategory {

    /**
     * 系统模块
     * <p>基础设施模块，最先加载，不可禁用。
     * 例如：日志、配置、事件总线、ModuleRegistry 自身。
     * <b>适用模式：兼容 + 狂暴</b>
     */
    SYSTEM("系统模块", 100, true, false, true),

    /**
     * 核心模块
     * <p>主要功能模块，始终加载，可禁用。
     * 例如：Blaze3D 优化器、EffectPipeline 整合。
     * <b>适用模式：兼容 + 狂暴</b>
     */
    CORE("核心模块", 80, true, true, true),

    /**
     * 优化模块
     * <p>性能优化模块，默认启用。
     * 例如：内存池、命令批处理、资源缓存。
     * <b>适用模式：兼容 + 狂暴</b>
     */
    OPTIMIZATION("优化模块", 60, true, true, true),

    /**
     * 狂暴模式专属模块 ⚡
     * <p>仅在 Aggressive (狂暴) 模式下加载的激进优化模块。
     * 包括：类Sodium 渲染优化、帧生成、Async Compute 等。
     * <b>适用模式：仅狂暴 (Aggressive Only)</b>
     *
     * <h3>设计原因：</h3>
     * <ul>
     *   <li>这些优化可能影响模组兼容性</li>
     *   <li>需要更严格的稳定性验证</li>
     *   <li>可能与其他渲染模组冲突</li>
     * </ul>
     */
    AGGRESSIVE_ONLY("狂暴模式模块", 50, false, false, false),

    /**
     * 集成模块
     * <p>第三方库集成模块。
     * 例如：Streamline/DLSS、Sodium 兼容层。
     * <b>适用模式：兼容 + 狂暴</b>
     */
    INTEGRATION("集成模块", 40, true, true, true),

    /**
     * 实验性模块
     * <p>新功能或高风险优化，默认关闭。
     * 需要用户手动开启。
     * <b>适用模式：仅狂暴</b>
     */
    EXPERIMENTAL("实验性模块", 20, false, false, false),

    /**
     * 光影模块 🎨
     * <p>着色器和后处理相关模块。
     * 例如：RGB 光影包加载器、自定义 Pass 注入。
     * <b>适用模式：仅狂暴</b>
     */
    SHADER("光影模块", 30, false, false, false);

    // ==================== 字段定义 ====================

    /** 显示名称 */
    private final String displayName;

    /** 加载优先级（数值越大越早加载） */
    private final int loadPriority;

    /** 是否在兼容模式 (Compatible Mode) 加载 */
    private final boolean loadInCompatibleMode;

    /** 是否允许用户手动禁用 */
    private final boolean userDisableable;

    /** 是否在狂暴模式 (Aggressive Mode) 加载 */
    private final boolean loadInAggressiveMode;

    // ==================== 构造函数 ====================

    ModuleCategory(String displayName, int loadPriority,
                   boolean loadInCompatibleMode, boolean userDisableable,
                   boolean loadInAggressiveMode) {
        this.displayName = displayName;
        this.loadPriority = loadPriority;
        this.loadInCompatibleMode = loadInCompatibleMode;
        this.userDisableable = userDisableable;
        this.loadInAggressiveMode = loadInAggressiveMode;
    }

    // ==================== Getter 方法 ====================

    public String getDisplayName() { return displayName; }
    public int getLoadPriority() { return loadPriority; }
    public boolean shouldLoadInCompatibleMode() { return loadInCompatibleMode; }
    public boolean isUserDisableable() { return userDisableable; }

    /**
     * 检查是否应在指定模式下加载
     *
     * @param isAggressive 当前是否为狂暴模式
     * @return true 如果应该在此模式下加载
     */
    public boolean shouldLoadForMode(boolean isAggressive) {
        if (isAggressive) {
            return loadInAggressiveMode;
        } else {
            return loadInCompatibleMode;
        }
    }
}
