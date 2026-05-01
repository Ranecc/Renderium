// Renderium - 模块系统
// 模块生命周期状态定义

package com.ranecc.renderium.feature.module;

/**
 * 模块生命周期状态
 * <p>
 * 定义模块从注册到卸载的完整状态流转。
 * 所有状态转换必须通过 {@link com.renderium.module.ModuleRegistry} 进行。
 *
 * <h2>状态流转图：</h2>
 * <pre>
 * UNREGISTERED → REGISTERED → INITIALIZED → ENABLED → DISABLED → DISPOSED
 *                    ↑                              │
 *                    └────────── (重新初始化) ←──────┘
 * </pre>
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public enum ModuleLifecycle {

    /**
     * 未注册状态
     * <p>模块尚未被注册到系统中。
     */
    UNREGISTERED("未注册", false, false),

    /**
     * 已注册状态
     * <p>模块已注册但尚未初始化。
     * 可以在此阶段配置依赖关系。
     */
    REGISTERED("已注册", false, false),

    /**
     * 已初始化状态
     * <p>模块已完成初始化（资源分配、配置加载），
     * 但功能尚未激活。
     */
    INITIALIZED("已初始化", true, false),

    /**
     * 已启用状态
     * <p>模块完全激活，正在提供功能。
     * 这是正常工作状态。
     */
    ENABLED("已启用", true, true),

    /**
     * 已禁用状态
     * <p>模块被临时禁用，资源仍保留。
     * 可以重新启用而无需完整初始化。
     */
    DISABLED("已禁用", true, false),

    /**
     * 已销毁状态
     * <p>模块已被完全卸载，所有资源已释放。
     * 此状态不可逆，需要重新注册才能使用。
     */
    DISPOSED("已销毁", false, false);

    // ==================== 字段定义 ====================

    /** 显示名称（中文） */
    private final String displayName;

    /** 是否已初始化（拥有有效资源） */
    private final boolean initialized;

    /** 是否活跃（正在提供服务） */
    private final boolean active;

    // ==================== 构造函数 ====================

    ModuleLifecycle(String displayName, boolean initialized, boolean active) {
        this.displayName = displayName;
        this.initialized = initialized;
        this.active = active;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取显示名称
     *
     * @return 中文名称
     */
    public String getDisplayName() { return displayName; }

    /**
     * 检查是否已完成初始化
     *
     * @return true 如果模块已分配资源
     */
    public boolean isInitialized() { return initialized; }

    /**
     * 检查是否处于活跃状态
     *
     * @return true 如果模块正在运行
     */
    public boolean isActive() { return active; }

    /**
     * 检查是否可以转换到目标状态
     *
     * @param target 目标状态
     * @return true 如果转换合法
     */
    public boolean canTransitionTo(ModuleLifecycle target) {
        return switch (this) {
            case UNREGISTERED -> target == REGISTERED;
            case REGISTERED -> target == INITIALIZED || target == DISPOSED;
            case INITIALIZED -> target == ENABLED || target == DISPOSED;
            case ENABLED -> target == DISABLED || target == DISPOSED;
            case DISABLED -> target == ENABLED || target == DISPOSED;
            case DISPOSED -> false; // 终态，不可转换
        };
    }
}
