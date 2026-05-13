// ============================================================
// StreamlineInitializationResult - Streamline SDK 初始化结果
// ============================================================
// 独立的初始化结果类，不依赖其他 streamline 内部类
//
// 使用场景：
//   - 封装 Streamline SDK 初始化的成功/失败状态
//   - 提供错误信息用于诊断
//   - 通过工厂方法创建实例
//
// @see com.renderium.core.component.StreamlineInitializer
// ============================================================

package com.ranecc.renderium.tech.streamline;

/**
 * Streamline SDK 初始化结果
 * <p>
 * 封装 Streamline 初始化操作的结果状态，包括成功/失败标志和错误信息。
 * 此类完全独立，不依赖任何其他 streamline 内部类。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建成功结果
 * StreamlineInitializationResult success = StreamlineInitializationResult.success();
 *
 * // 创建失败结果
 * StreamlineInitializationResult failure = StreamlineInitializationResult.failure("DLL not found");
 *
 * // 检查结果
 * if (!result.isSuccess()) {
 *     LOGGER.warning("初始化失败: " + result.getErrorMessage());
 * }
 * }</pre>
 *
 * <h3>线程安全性</h3>
 * <p>此类是不可变的（immutable），因此完全线程安全。
 *
 * @author Renderium Team
 * @version 1.0
 * @since 5.0.0
 */
public final class StreamlineInitializationResult {

    /** 初始化是否成功 */
    private final boolean success;

    /** 错误信息（仅失败时有值） */
    private final String errorMessage;

    /**
     * 私有构造函数 - 通过工厂方法创建实例
     *
     * @param success       初始化是否成功
     * @param errorMessage  错误信息（成功时为 null）
     */
    private StreamlineInitializationResult(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    /**
     * 创建成功的初始化结果
     *
     * @return 表示成功的结果实例，errorMessage 为 null
     */
    public static StreamlineInitializationResult success() {
        return new StreamlineInitializationResult(true, null);
    }

    /**
     * 创建失败的初始化结果
     *
     * @param errorMessage 错误描述信息，不能为 null
     * @return 表示失败的结果实例
     * @throws IllegalArgumentException 如果 errorMessage 为 null
     */
    public static StreamlineInitializationResult failure(String errorMessage) {
        if (errorMessage == null) {
            throw new IllegalArgumentException("错误信息不能为 null");
        }
        return new StreamlineInitializationResult(false, errorMessage);
    }

    /**
     * 检查初始化是否成功
     *
     * @return true 表示初始化成功，false 表示失败
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取错误信息
     *
     * @return 错误描述字符串，成功时返回 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        if (success) {
            return "StreamlineInitializationResult[SUCCESS]";
        } else {
            return String.format("StreamlineInitializationResult[FAILURE, error=%s]", errorMessage);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StreamlineInitializationResult that)) return false;
        if (success != that.success) return false;
        return errorMessage != null ? errorMessage.equals(that.errorMessage) : that.errorMessage == null;
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(success);
        result = 31 * result + (errorMessage != null ? errorMessage.hashCode() : 0);
        return result;
    }
}
