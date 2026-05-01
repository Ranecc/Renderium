// Renderium - Blaze3D 优化器插件系统
// 安全检查接口

package com.ranecc.renderium.presentation.plugin.plugin;

/**
 * 安全检查接口
 * <p>
 * 定义单个安全检查项，用于在插件加载前验证系统状态。
 * 所有检查必须全部通过才能继续加载。
 *
 * <h2>内置检查项：</h2>
 * <ul>
 *   <li>{@code MixinIntegrityCheck} - Mixin 注入完整性</li>
 *   <li>{@code RenderLoopCheck} - 渲染循环状态</li>
 *   <li>{@code MemoryLeakCheck} - 内存泄漏检测</li>
 *   <li>{@code CrashRateCheck} - 崩溃率评估</li>
 * </ul>
 *
 * @see PluginSafetyManager
 * @author Renderium Team
 * @since 1.0.0
 */
public interface SafetyCheck {

    /**
     * 获取检查项名称
     * <p>用于日志输出和错误报告。
     *
     * @return 人类可读的名称，如 "Mixin Integrity"
     */
    String getName();

    /**
     * 获取检查项描述
     *
     * @return 说明此检查目的的文本
     */
    default String getDescription() {
        return "No description";
    }

    /**
     * 执行安全检查
     * <p>对指定插件执行此检查项的验证逻辑。
     *
     * @param plugin 待检查的插件实例
     * @return 检查通过返回 true
     */
    boolean check(Blaze3DOptimizerPlugin plugin);

    /**
     * 获取严重性等级
     * <p>定义此检查失败时的处理策略：
     * <ul>
     *   <li>WARNING - 记录警告但可继续</li>
     *   <li>ERROR - 阻止加载</li>
     *   <li>CRITICAL - 立即回滚并禁用</li>
     * </ul>
     *
     * @return 严重性等级
     */
    default Severity getSeverity() {
        return Severity.ERROR;
    }

    /**
     * 检查失败时获取建议消息
     *
     * @return 给用户的修复建议
     */
    default String getFailureAdvice() {
        return "Contact mod developer for support";
    }

    // ==================== 严重性枚举 ====================

    /**
     * 检查严重性等级
     */
    enum Severity {
        /** 警告：记录日志但不阻止 */
        WARNING,
        /** 错误：阻止当前操作 */
        ERROR,
        /** 严重：立即触发紧急处理 */
        CRITICAL
    }
}
