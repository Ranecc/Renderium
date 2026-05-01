// Renderium - 错误恢复策略枚举
// 定义不同类型的错误及其恢复策略

package com.ranecc.renderium.domain.enums;

/**
 * 错误恢复策略枚举。
 *
 * <p>定义不同类型的错误及其对应的恢复策略，
 * 帮助系统在遇到错误时做出正确的决策。
 *
 * <h2>使用示例</h2>
 * <pre>
 * try {
 *     optimizer.optimize();
 * } catch (Exception e) {
 *     RecoveryStrategy strategy = classifyError(e);
 *     switch (strategy) {
 *         case FALLBACK_TO_DEFAULT:
 *             useDefaultImplementation();
 *             break;
 *         case RETRY_WITH_REDUCED_SCOPE:
 *             optimizeWithSmallerBatch();
 *             break;
 *         case LOG_AND_CONTINUE:
 *             LOGGER.warning("Optimization failed, continuing");
 *             break;
 *     }
 * }
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public enum RecoveryStrategy {

    /**
     * 降级到默认/标准实现
     * <p>当优化版本失败时，回退到未经优化的标准实现
     * <p>适用场景：网格构建优化失败、Compute Shader 剔除失败等
     */
    FALLBACK_TO_DEFAULT("降级到标准实现"),

    /**
     * 缩小范围重试
     * <p>使用更小的批次、更低的分辨率等参数重试操作
     * <p>适用场景：内存分配失败、超大缓冲区创建失败等
     */
    RETRY_WITH_REDUCED_SCOPE("缩小范围重试"),

    /**
     * 跳过当前操作继续
     * <p>当前操作失败但不影响后续流程，记录日志后跳过
     * <p>适用场景：可选的后处理效果、非关键的性能统计等
     */
    SKIP_AND_CONTINUE("跳过并继续"),

    /**
     * 记录日志并继续
     * <p>仅记录警告，不做任何恢复操作
     * <p>适用场景：性能数据采样失败、调试信息收集失败等
     */
    LOG_AND_CONTINUE("记录日志并继续"),

    /**
     * 重置状态后重试
     * <p>清理相关状态后重新尝试操作
     * <p>适用场景：渲染状态异常、缓存污染等
     */
    RESET_AND_RETRY("重置后重试"),

    /**
     * 紧急关闭
     * <p>不可恢复的错误，需要立即关闭相关子系统
     * <p>适用场景：Vulkan 设备丢失、显存严重不足等
     */
    EMERGENCY_SHUTDOWN("紧急关闭"),

    /**
     * 抛出异常终止
     * <p>不可恢复的致命错误，应该中断整个流程
     * <p>适用场景：配置严重错误、核心资源初始化失败等
     */
    THROW_AND_TERMINATE("抛出异常终止");

    /** 策略描述 */
    private final String description;

    RecoveryStrategy(String description) {
        this.description = description;
    }

    /**
     * 获取策略描述
     *
     * @return 人类可读的策略描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 是否应该继续执行（不中断流程）
     *
     * @return 如果是可恢复策略返回 true
     */
    public boolean isRecoverable() {
        return this != EMERGENCY_SHUTDOWN && this != THROW_AND_TERMINATE;
    }

    /**
     * 是否需要重试
     *
     * @return 如果需要重试返回 true
     */
    public boolean requiresRetry() {
        return this == RETRY_WITH_REDUCED_SCOPE || this == RESET_AND_RETRY;
    }
}
