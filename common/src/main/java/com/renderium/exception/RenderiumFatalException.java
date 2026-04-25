// Renderium - 致命异常类
// 表示不可恢复的致命错误，需要中断渲染流程

package com.renderium.exception;

/**
 * Renderium 致命异常。
 *
 * <p>表示不可恢复的致命错误，通常意味着渲染系统无法继续正常工作。
 * 抛出此异常后，调用方应该执行紧急关闭流程。
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>Vulkan 设备丢失（VK_ERROR_DEVICE_LOST）</li>
 *   <li>核心渲染资源初始化失败</li>
 *   <li>显存严重不足且无法回收</li>
 *   <li>配置严重错误导致无法启动</li>
 * </ul>
 *
 * <h2>与 RuntimeException 的区别</h2>
 * <p>RuntimeException 表示编程错误，而 RenderiumFatalException 表示
 * 运行时环境发生了不可恢复的问题。
 *
 * <h2>处理指南</h2>
 * <pre>
 * try {
 *     renderiumCore.initialize(device);
 * } catch (RenderiumFatalException e) {
 *     // 1. 记录详细错误信息
 *     LOGGER.severe("Fatal: " + e.getMessage());
 *     // 2. 触发紧急关闭
 *     renderiumCore.emergencyShutdown(e.getRecoveryStrategy());
 *     // 3. 通知用户
 *     showErrorDialog("渲染系统初始化失败", e.getLocalizedMessage());
 * }
 * </pre>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public class RenderiumFatalException extends RuntimeException {

    /** 推荐的恢复策略 */
    private final RecoveryStrategy recoveryStrategy;

    /** 错误发生时的子系统名称 */
    private final String subsystem;

    /**
     * 创建致命异常
     *
     * @param message 错误描述
     * @param strategy 推荐的恢复策略
     * @param subsystem 子系统名称
     */
    public RenderiumFatalException(String message, RecoveryStrategy strategy, String subsystem) {
        super(message);
        this.recoveryStrategy = strategy;
        this.subsystem = subsystem;
    }

    /**
     * 创建带原因的致命异常
     *
     * @param message 错误描述
     * @param cause 原始异常
     * @param strategy 推荐的恢复策略
     * @param subsystem 子系统名称
     */
    public RenderiumFatalException(String message, Throwable cause,
                                   RecoveryStrategy strategy, String subsystem) {
        super(message, cause);
        this.recoveryStrategy = strategy;
        this.subsystem = subsystem;
    }

    /**
     * 获取推荐的恢复策略
     *
     * @return 恢复策略枚举
     */
    public RecoveryStrategy getRecoveryStrategy() {
        return recoveryStrategy;
    }

    /**
     * 获取发生错误的子系统名称
     *
     * @return 子系统名称
     */
    public String getSubsystem() {
        return subsystem;
    }

    /**
     * 获取格式化的本地化消息
     *
     * @return 包含子系统和恢复策略的完整消息
     */
    @Override
    public String getLocalizedMessage() {
        return String.format("[%s] %s (恢复策略: %s)",
                subsystem, getMessage(), recoveryStrategy.getDescription());
    }
}
