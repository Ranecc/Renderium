// Renderium - Blaze3D VMA 渐进式清理模块
// 清理策略接口 - 定义统一清理行为契约

package com.ranecc.renderium.feature.blaze3d.memory;

/**
 * 内存清理策略接口。
 * <p>
 * 所有清理策略 (软/渐进/紧急) 都实现此接口，
 * 由 {@link GradualMemoryManager} 根据当前内存压力级别选择执行。</p>
 *
 * @see SoftCleanupStrategy 预警阶段: 只清理明显无用的资源
 * @see GradualCleanupStrategy 软/硬限制: 每帧清理固定量
 * @see EmergencyCleanupStrategy 临界状态: 强制分批清理
 * @since 2.0.0
 */
public interface CleanupStrategy {

    /**
     * 执行一轮清理操作。
     * <p>每帧由 {@link GradualMemoryManager#executeGradualCleanup()} 调用。</p>
     *
     * <h3>实现约定:</h3>
     * <ul>
     *   <li>不应阻塞主线程超过 2ms</li>
     *   <li>应记录清理日志 (debug 级别)</li>
     *   <li>应优先使用 LRU 排序的候选</li>
     *   <li>热数据必须跳过</li>
     * </ul>
     */
    void execute();

    /**
     * 获取策略名称 (用于日志)
     */
    String getName();
}
