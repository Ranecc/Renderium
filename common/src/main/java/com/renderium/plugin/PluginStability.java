// Renderium - Blaze3D 优化器插件系统
// 插件稳定性等级定义

package com.renderium.plugin;

/**
 * 插件稳定性等级
 * <p>
 * 用于标识插件的成熟度和风险等级。
 * 用户可以根据此等级决定是否启用该插件。
 *
 * <h2>等级说明：</h2>
 * <ul>
 *   <li>{@link #EXPERIMENTAL} - 实验性：可能不稳定，仅用于测试</li>
 *   <li>{@link #BETA} - 测试版：基本可用，可能有小问题</li>
 *   <li>{@link #STABLE} - 稳定版：生产可用，经过充分测试</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public enum PluginStability {

    /**
     * 实验性等级
     * <p>插件处于早期开发阶段，可能存在严重问题。
     * 仅建议用于测试环境或高级用户。
     */
    EXPERIMENTAL,

    /**
     * 测试版等级
     * <p>插件基本功能已实现，但可能存在边界情况问题。
     * 建议普通用户谨慎使用。
     */
    BETA,

    /**
     * 稳定版等级
     * <p>插件经过充分测试，可以安全用于生产环境。
     * 推荐所有用户使用。
     */
    STABLE;

    /**
     * 获取显示名称（中文）
     *
     * @return 本地化显示名称
     */
    public String getDisplayName() {
        return switch (this) {
            case EXPERIMENTAL -> "实验性";
            case BETA -> "测试版";
            case STABLE -> "稳定版";
        };
    }

    /**
     * 获取警告信息
     *
     * @return 适合向用户展示的警告文本
     */
    public String getWarningMessage() {
        return switch (this) {
            case EXPERIMENTAL -> "此插件处于实验阶段，可能导致崩溃或不稳定";
            case BETA -> "此插件为测试版本，可能存在未知问题";
            case STABLE -> null; // 稳定版无需警告
        };
    }
}
