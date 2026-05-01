// Renderium - Blaze3D 优化器插件系统
// 核心插件接口 - 每个 MC 版本一个实现

package com.ranecc.renderium.presentation.plugin.plugin;

/**
 * Blaze3D 优化插件接口
 * <p>
 * 每个 Minecraft 版本对应一个插件实现实例。
 * 插件负责管理该版本特定的优化策略。
 *
 * <h2>架构定位：</h2>
 * <pre>
 * Renderium Core (稳定)
 *      ↓
 * Plugin Loader (加载器)
 *      ↓
 * Blaze3DOptimizerPlugin ← 你在这里
 *      ↓
 * Blaze3D (Mojang)
 * </pre>
 *
 * <h2>生命周期：</h2>
 * <ol>
 *   <li>{@link #getMetadata()} - 返回元数据，用于版本匹配</li>
 *   <li>{@link #isSupported(Environment)} - 环境兼容性检查</li>
 *   <li>{@link #initialize(PluginContext)} - 初始化，注册子优化</li>
 *   <li>{@link #applyOptimizations()} - 应用所有优化</li>
 *   <li>{@link #getStats()} - 获取聚合统计</li>
 *   <li>{@link #shutdown()} - 卸载，清理资源</li>
 * </ol>
 *
 * <h2>实现示例：</h2>
 * <pre>{@code
 * public class Optimizer26_2 implements Blaze3DOptimizerPlugin {
 *     @Override
 *     public PluginMetadata getMetadata() {
 *         return new PluginMetadata(
 *             "renderium-optimizer-26.2",
 *             "Renderium Optimizer for 26.2",
 *             "26.2.x", "1.0.0",
 *             PluginStability.EXPERIMENTAL,
 *             List.of("Resource Pooling"),
 *             List.of()
 *         );
 *     }
 *     // ... 其他方法实现
 * }
 * }</pre>
 *
 * @see Blaze3DOptimization
 * @see PluginMetadata
 * @see PluginStability
 * @author Renderium Team
 * @since 1.0.0
 */
public interface Blaze3DOptimizerPlugin {

    /**
     * 获取插件元数据
     * <p>元数据用于：
     * <ul>
     *   <li>版本匹配（选择正确的插件）</li>
     *   <li>UI 显示（名称、描述、风险提示）</li>
     *   <li>安全检查（稳定性评估）</li>
     * </ul>
     *
     * @return 不可变的 PluginMetadata 实例
     */
    PluginMetadata getMetadata();

    /**
     * 检查是否支持当前运行环境
     * <p>在此方法中进行：
     * <ul>
     *   <li>MC 版本匹配检查</li>
     *   <li>GPU 能力验证</li>
     *   <li>冲突模组检测</li>
     *   <li>其他前置条件</li>
     * </ul>
     *
     * @param env 当前运行时环境信息
     * @return 如果支持当前环境返回 true
     */
    boolean isSupported(Environment env);

    /**
     * 初始化插件
     * <p>在此阶段：
     * <ul>
     *   <li>保存上下文引用</li>
     *   <li>创建并注册子优化 ({@link Blaze3DOptimization})</li>
     *   <li>预分配必要资源</li>
     *   <li>读取用户配置</li>
     * </ul>
     *
     * @param context 插件上下文，提供配置和资源访问
     * @return 初始化成功返回 true
     */
    boolean initialize(PluginContext context);

    /**
     * 应用所有优化
     * <p>按顺序调用所有已注册优化的 apply() 方法。
     * 任一优化失败不影响其他优化（但会影响返回值）。
     *
     * @return 所有优化都成功返回 true，任一失败返回 false
     */
    boolean applyOptimizations();

    /**
     * 卸载插件
     * <p>执行以下操作：
     * <ol>
     *   <li>调用所有子优化的 shutdown()</li>
     *   <li>释放插件持有的资源</li>
     *   <li>重置内部状态</li>
     * </ol>
     * <p><b>重要：</b>即使 shutdown 过程中出现异常，
     * 也必须尽可能多地清理资源。
     */
    void shutdown();

    /**
     * 获取优化统计汇总
     * <p>聚合所有子优化的统计数据，
     * 用于性能监控和调试。
     *
     * @return OptimizationStats 实例，包含所有指标
     */
    OptimizationStats getStats();
}
