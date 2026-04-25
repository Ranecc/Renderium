// Renderium v6 Phase 1.1 - 数据桥接层
// DataProviderException - 数据获取异常

package com.renderium.data;

/**
 * 数据提供者异常 🚨
 *
 * <p>当从 Minecraft 实例获取数据失败时抛出此异常。
 * 用于标识数据桥接层（{@link RealDataProvider}）在访问 Minecraft 运行时数据时遇到的问题。
 *
 * <h2>使用场景：</h2>
 * <ul>
 *   <li><b>反射失败</b>：无法通过反射访问 Minecraft 内部字段或方法</li>
 *   <li><b>类型转换错误</b>：获取到的对象类型与预期不符</li>
 *   <li><b>空引用</b>：预期的 Minecraft 组件为 null</li>
 *   <li><b>初始化失败</b>：Minecraft 实例未正确初始化或已销毁</li>
 *   <li><b>权限不足</b>：无法访问受保护的 API</li>
 * </ul>
 *
 * <h2>与 RenderiumFatalException 的区别：</h2>
 * <ul>
 *   <li>{@code DataProviderException} 表示可恢复的数据获取错误，通常可以降级处理</li>
 *   <li>{@link com.renderium.exception.RenderiumFatalException} 表示不可恢复的系统级致命错误</li>
 * </ul>
 *
 * <h2>处理指南：</h2>
 * <pre>
 * try {
 *     Vec3 position = dataProvider.getCameraPosition();
 * } catch (DataProviderException e) {
 *     // 1. 记录详细错误信息（包含建议修复方案）
 *     LOGGER.warning("数据获取失败: " + e.getMessage());
 *
 *     // 2. 根据严重程度选择处理策略
 *     switch (e.getSeverity()) {
 *         case WARNING:
 *             // 使用缓存值或默认值继续运行
 *             break;
 *         case ERROR:
 *             // 禁用相关功能，使用降级模式
 *             break;
 *         case CRITICAL:
 *             // 触发紧急关闭流程
 *             break;
 *     }
 *
 *     // 3. 可选：根据建议修复方案尝试恢复
 *     if (e.getRecoverySuggestion() != null) {
 *         applyRecovery(e.getRecoverySuggestion());
 *     }
 * }
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>此类是不可变对象（Immutable），线程安全。
 *
 * @author Renderium Team
 * @version 6.0.0 (Phase 1.1)
 * @since 6.0.0
 * @see RealDataProvider
 */
public class DataProviderException extends RuntimeException {

    /** 异常严重程度枚举 */
    public enum Severity {
        /** 警告级别 - 可使用默认值继续运行 */
        WARNING,
        /** 错误级别 - 需要禁用相关功能 */
        ERROR,
        /** 严重级别 - 可能需要紧急关闭 */
        CRITICAL
    }

    /** 异常严重程度 */
    private final Severity severity;

    /** 发生错误的源组件名称（如 "Camera", "LevelRenderer"） */
    private final String sourceComponent;

    /** 建议的修复方案（可为 null） */
    private final String recoverySuggestion;

    /**
     * 创建数据提供者异常
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - message: 错误描述消息
     *   - severity: 异常严重程度（WARNING/ERROR/CRITICAL）
     *   - source: 发生错误的源组件名称
     *
     * 返回值：
     *   - 无（构造函数）
     *
     * 使用示例：
     *   throw new DataProviderException(
     *       "无法获取 Camera 实例",
     *       DataProviderException.Severity.ERROR,
     *       "Camera"
     *   );
     * </pre>
     *
     * @param message           错误描述
     * @param severity          异常严重程度
     * @param sourceComponent   源组件名称
     */
    public DataProviderException(String message, Severity severity, String sourceComponent) {
        super(message);
        this.severity = severity;
        this.sourceComponent = sourceComponent;
        this.recoverySuggestion = null;
    }

    /**
     * 创建带原因和建议修复方案的数据提供者异常
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - message: 错误描述消息
     *   - cause: 导致此异常的原始异常
     *   - severity: 异常严重程度
     *   - source: 源组件名称
     *   - suggestion: 建议的修复方案（可为 null）
     *
     * 使用示例：
     *   throw new DataProviderException(
     *       "反射获取 levelRenderer 失败",
     *       reflectiveException,
     *       DataProviderException.Severity.CRITICAL,
     *       "LevelRenderer",
     *       "检查 Minecraft 版本兼容性"
     *   );
     * </pre>
     *
     * @param message            错误描述
     * @param cause              原始异常
     * @param severity           异常严重程度
     * @param sourceComponent    源组件名称
     * @param recoverySuggestion 建议的修复方案（可为 null）
     */
    public DataProviderException(String message, Throwable cause,
                                 Severity severity, String sourceComponent,
                                 String recoverySuggestion) {
        super(message, cause);
        this.severity = severity;
        this.sourceComponent = sourceComponent;
        this.recoverySuggestion = recoverySuggestion;
    }

    /**
     * 获取异常严重程度
     *
     * <p>用于调用方决定处理策略：
     * <ul>
     *   <li>{@link Severity#WARNING}: 使用默认值或缓存值继续运行</li>
     *   <li>{@link Severity#ERROR}: 禁用相关功能，进入降级模式</li>
     *   <li>{@link Severity#CRITICAL}: 触发紧急关闭流程</li>
     * </ul>
     *
     * @return 严重程度枚举值
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * 获取发生错误的源组件名称
     *
     * <p>用于日志记录和问题定位。
     * 常见值："Camera", "LevelRenderer", "Frustum", "ClientLevel", "SectionRenderDispatcher"
     *
     * @return 源组件名称字符串
     */
    public String getSourceComponent() {
        return sourceComponent;
    }

    /**
     * 获取建议的修复方案
     *
     * <p>如果提供了修复建议，调用方可根据此信息尝试自动恢复或提示用户手动操作。
     *
     * @return 修复建议字符串，如果没有则为 null
     */
    public String getRecoverySuggestion() {
        return recoverySuggestion;
    }

    /**
     * 获取格式化的本地化消息
     *
     * <p>包含严重程度、源组件和完整错误信息的格式化输出。
     *
     * @return 格式化的完整消息
     */
    @Override
    public String getLocalizedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s][%s] %s",
                severity.name(),
                sourceComponent,
                getMessage()));

        if (recoverySuggestion != null && !recoverySuggestion.isEmpty()) {
            sb.append(String.format(" (建议: %s)", recoverySuggestion));
        }

        return sb.toString();
    }

    /**
     * 创建 WARNING 级别的便捷工厂方法
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - message: 错误描述
     *   - source: 源组件名称
     *
     * 返回值：
     *   - DataProviderException 实例（severity=WARNING）
     *
     * 使用示例：
     *   throw DataProviderException.warning("缓存未初始化", "Cache");
     * </pre>
     *
     * @param message 错误描述
     * @param source  源组件名称
     * @return WARNING 级别的异常实例
     */
    public static DataProviderException warning(String message, String source) {
        return new DataProviderException(message, Severity.WARNING, source);
    }

    /**
     * 创建 ERROR 级别的便捷工厂方法
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - message: 错误描述
     *   - source: 源组件名称
     *
     * 返回值：
     *   - DataProviderException 实例（severity=ERROR）
     * </pre>
     *
     * @param message 错误描述
     * @param source  源组件名称
     * @return ERROR 级别的异常实例
     */
    public static DataProviderException error(String message, String source) {
        return new DataProviderException(message, Severity.ERROR, source);
    }

    /**
     * 创建 CRITICAL 级别的便捷工厂方法（带修复建议）
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - message: 错误描述
     *   - source: 源组件名称
     *   - suggestion: 修复建议
     *
     * 返回值：
     *   - DataProviderException 实例（severity=CRITICAL）
     * </pre>
     *
     * @param message    错误描述
     * @param source     源组件名称
     * @param suggestion 修复建议
     * @return CRITICAL 级别的异常实例
     */
    public static DataProviderException critical(String message, String source, String suggestion) {
        return new DataProviderException(message, null, Severity.CRITICAL, source, suggestion);
    }
}
