// Renderium - Blaze3D 优化器插件系统
// 单个优化项接口

package com.ranecc.renderium.presentation.plugin;

/**
 * 单个优化项接口
 * <p>
 * 定义一个可独立启用的 Blaze3D 层优化。
 * 每个 {@link Blaze3DOptimizerPlugin} 包含多个此接口的实现。
 *
 * <h2>生命周期：</h2>
 * <ol>
 *   <li>{@link #initialize(PluginContext)} - 初始化，准备资源</li>
 *   <li>{@link #apply()} - 应用优化，修改行为</li>
 *   <li>{@link #getStats()} - 获取统计（可选，运行时调用）</li>
 *   <li>{@link #shutdown()} - 卸载，恢复原始状态</li>
 * </ol>
 *
 * <h2>设计原则：</h2>
 * <ul>
 *   <li>每个优化应可独立启用/禁用</li>
 *   <li>失败不应影响其他优化</li>
 *   <li>shutdown 必须完全恢复原始状态</li>
 * </ul>
 *
 * @see Blaze3DOptimizerPlugin
 * @author Renderium Team
 * @since 1.0.0
 */
public interface Blaze3DOptimization {

    /**
     * 获取优化名称（唯一标识）
     * <p>用于日志输出和 UI 显示。
     *
     * @return 人类可读的名称，如 "Resource Pooling"
     */
    String getName();

    /**
     * 获取优化描述
     * <p>简要说明此优化的作用和效果。
     *
     * @return 描述文本
     */
    default String getDescription() {
        return "No description available";
    }

    /**
     * 初始化优化
     * <p>在此阶段：
     * <ul>
     *   <li>读取配置参数</li>
     *   <li>预分配资源</li>
     *   <li>验证前置条件</li>
     * </ul>
     *
     * @param context 插件上下文，提供配置和资源访问
     * @return 初始化成功返回 true，失败返回 false
     */
    boolean initialize(PluginContext context);

    /**
     * 应用优化
     * <p>执行实际的优化逻辑，如：
     * <ul>
     *   <li>替换原始分配器</li>
     *   <li>注入 Mixin 目标</li>
     *   <li>修改渲染管线</li>
     * </ul>
     *
     * @return 应用成功返回 true，失败返回 false
     */
    boolean apply();

    /**
     * 卸载优化，完全恢复原始状态
     * <p><b>重要：</b>必须确保：
     * <ul>
     *   <li>释放所有持有的资源</li>
     *   <li>恢复被替换的对象</li>
     *   <li>清除所有注入的代码</li>
     * </ul>
     */
    void shutdown();

    /**
     * 获取优化统计数据
     * <p>返回运行时的性能指标，
     * 如池命中率、节省的 Draw Call 数量等。
     *
     * @return OptimizationStats 实例，包含各项指标
     */
    OptimizationStats getStats();
}
