// Renderium - Blaze3D 拦截层系统
// 拦截结果数据类 - 封装前拦截层的处理结果和性能指标

package com.ranecc.renderium.feature.intercept.base;
import com.ranecc.renderium.feature.intercept.base.InterceptionResult;
import com.ranecc.renderium.feature.intercept.base.RenderContext;

import java.util.Objects;

import com.ranecc.renderium.None;

/**
 * 拦截结果数据类
 * <p>
 * 封装 {@link PreBlaze3DInterceptor} 的处理结果，
 * 包含修改后的渲染上下文、拦截统计信息和性能指标。
 * <p>
 * 此类为不可变对象，线程安全。
 *
 * <h3>使用场景：</h3>
 * <pre>
 * // 前拦截层返回拦截结果
 * InterceptionResult result = preInterceptor.intercept(renderContext);
 *
 * // 检查是否成功
 * if (result.isSuccess()) {
 *     // 获取修改后的渲染上下文
 *     RenderContext modifiedContext = result.getModifiedContext();
 *
 *     // 记录性能指标
 *     long elapsedNanos = result.getElapsedTimeNanos();
 * }
 * </pre>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li><b>不可变性</b>：所有字段均为 final，确保线程安全</li>
 *   <li><b>Builder 模式</b>：提供灵活的对象构造方式</li>
 *   <li><b>性能监控</b>：内置耗时统计，支持性能分析</li>
 * </ul>
 *
 * @see PreBlaze3DInterceptor#intercept(RenderContext)
 * @see RenderContext
 * @since 5.1.0
 */
public final class InterceptionResult {

    // ==================== 结果状态枚举 ====================

    /**
     * 拦截结果状态枚举
     * <p>
     * 定义前拦截层处理后的可能状态：
     * <ul>
     *   <li><b>SUCCESS</b>：成功完成所有拦截操作</li>
     *   <li><b>PARTIAL</b>：部分操作成功，某些功能降级或跳过</li>
     *   <li><b>SKIPPED</b>：拦截被跳过（未启用或条件不满足）</li>
     *   <li><b>FAILURE</b>：处理失败，应回退到原始路径</li>
     * </ul>
     */
    public enum Status {
        /** 成功完成所有操作 */
        SUCCESS,

        /** 部分成功（某些功能降级） */
        PARTIAL,

        /** 被跳过（未启用） */
        SKIPPED,

        /** 处理失败 */
        FAILURE
    }

    // ==================== 核心字段 ====================

    /** 修改后的渲染上下文（可能包含 LOD/剔除优化后的数据） */
    private final RenderContext modifiedContext;

    /** 拦截结果状态 */
    private final Status status;

    /** 处理耗时（纳秒） */
    private final long elapsedTimeNanos;

    /** 检测到的模组 ID 列表（如 Sodium、Iris、Oculus） */
    private final String[] detectedMods;

    /** 已注册的模组处理器数量 */
    private final int registeredHandlerCount;

    /** LOD 注入是否成功 */
    private final boolean lodInjected;

    /** 剔除注入是否成功 */
    private final boolean cullingInjected;

    /** 状态消息（用于调试和日志） */
    private final String message;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     * <p>
     * 通过 Builder 模式构造实例，确保所有字段的有效性。
     *
     * @param builder 构建器实例
     */
    private InterceptionResult(Builder builder) {
        this.modifiedContext = builder.modifiedContext;
        this.status = Objects.requireNonNull(builder.status, "Status 不能为 null");
        this.elapsedTimeNanos = builder.elapsedTimeNanos;
        this.detectedMods = builder.detectedMods != null ? builder.detectedMods.clone() : new String[0];
        this.registeredHandlerCount = builder.registeredHandlerCount;
        this.lodInjected = builder.lodInjected;
        this.cullingInjected = builder.cullingInjected;
        this.message = builder.message != null ? builder.message : "";
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取修改后的渲染上下文
     * <p>
     * 返回经过前拦截层处理后的渲染上下文，
     * 可能包含 LOD 预处理、剔除优化等修改。
     *
     * @return 修改后的 RenderContext，如果处理失败则返回原始上下文
     */
    public RenderContext getModifiedContext() {
        return modifiedContext;
    }

    /**
     * 获取拦截结果状态
     *
     * @return 状态枚举值（SUCCESS/PARTIAL/SKIPPED/FAILURE）
     */
    public Status getStatus() {
        return status;
    }

    /**
     * 检查是否成功
     * <p>
     * 当状态为 SUCCESS 或 PARTIAL 时返回 true。
     *
     * @return true 如果处理成功或部分成功
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.PARTIAL;
    }

    /**
     * 获取处理耗时（纳秒）
     *
     * @return 耗时（ns），0 表示未计时
     */
    public long getElapsedTimeNanos() {
        return elapsedTimeNanos;
    }

    /**
     * 获取处理耗时（毫秒）
     *
     * @return 耗时（ms），保留 3 位小数
     */
    public double getElapsedTimeMillis() {
        return elapsedTimeNanos / 1_000_000.0;
    }

    /**
     * 获取检测到的模组列表
     * <p>
     * 返回已检测到的第三方模组 ID 数组（如 "sodium"、"iris"、"oculus"）。
     *
     * @return 模组 ID 数组的副本（防止外部修改）
     */
    public String[] getDetectedMods() {
        return detectedMods.clone();
    }

    /**
     * 获取已注册的模组处理器数量
     *
     * @return 处理器数量（>= 0）
     */
    public int getRegisteredHandlerCount() {
        return registeredHandlerCount;
    }

    /**
     * 检查 LOD 是否成功注入
     *
     * @return true 如果 LOD 预处理已注入
     */
    public boolean isLodInjected() {
        return lodInjected;
    }

    /**
     * 检查剔除是否成功注入
     *
     * @return true 如果剔除优化已注入
     */
    public boolean isCullingInjected() {
        return cullingInjected;
    }

    /**
     * 获取状态消息
     * <p>
     * 返回描述处理结果的详细信息，用于调试和日志记录。
     *
     * @return 状态消息字符串
     */
    public String getMessage() {
        return message;
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format(
            "InterceptionResult{status=%s, elapsed=%.2fms, mods=%d, handlers=%d, lod=%s, cull=%s, msg='%s'}",
            status,
            getElapsedTimeMillis(),
            detectedMods.length,
            registeredHandlerCount,
            lodInjected,
            cullingInjected,
            message
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InterceptionResult that = (InterceptionResult) o;
        return elapsedTimeNanos == that.elapsedTimeNanos &&
               registeredHandlerCount == that.registeredHandlerCount &&
               lodInjected == that.lodInjected &&
               cullingInjected == that.cullingInjected &&
               status == that.status &&
               Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, elapsedTimeNanos, registeredHandlerCount, lodInjected, cullingInjected, message);
    }

    // ==================== Builder 模式 ====================

    /**
     * InterceptionResult 构建器
     * <p>
     * 使用 Builder 模式提供灵活的对象构造方式，
     * 所有字段都有合理的默认值。
     *
     * <h3>使用示例：</h3>
     * <pre>
     * InterceptionResult result = new InterceptionResult.Builder()
     *     .status(InterceptionResult.Status.SUCCESS)
     *     .modifiedContext(context)
     *     .elapsedTimeNanos(System.nanoTime() - startTime)
     *     .detectedMods(new String[]{"sodium", "iris"})
     *     .lodInjected(true)
     *     .cullingInjected(true)
     *     .message("前拦截完成")
     *     .build();
     * </pre>
     */
    public static final class Builder {

        // 必填字段
        private Status status = Status.SKIPPED;

        // 可选字段（带默认值）
        private RenderContext modifiedContext;
        private long elapsedTimeNanos = 0L;
        private String[] detectedMods;
        private int registeredHandlerCount = 0;
        private boolean lodInjected = false;
        private boolean cullingInjected = false;
        private String message;

        /**
         * 设置拦截结果状态（必填）
         *
         * @param status 状态枚举值（不能为 null）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果 status 为 null
         */
        public Builder status(Status status) {
            this.status = Objects.requireNonNull(status, "Status 不能为 null");
            return this;
        }

        /**
         * 设置修改后的渲染上下文
         *
         * @param context 修改后的 RenderContext
         * @return this（链式调用）
         */
        public Builder modifiedContext(RenderContext context) {
            this.modifiedContext = context;
            return this;
        }

        /**
         * 设置处理耗时（纳秒）
         *
         * @param elapsedTimeNanos 耗时（ns，必须 >= 0）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果耗时 < 0
         */
        public Builder elapsedTimeNanos(long elapsedTimeNanos) {
            if (elapsedTimeNanos < 0) {
                throw new IllegalArgumentException("耗时不能为负数: " + elapsedTimeNanos);
            }
            this.elapsedTimeNanos = elapsedTimeNanos;
            return this;
        }

        /**
         * 设置检测到的模组列表
         *
         * @param mods 模组 ID 数组（可以为 null 或空数组）
         * @return this（链式调用）
         */
        public Builder detectedMods(String[] mods) {
            this.detectedMods = mods;
            return this;
        }

        /**
         * 设置已注册的模组处理器数量
         *
         * @param count 处理器数量（>= 0）
         * @return this（链式调用）
         * @throws IllegalArgumentException 如果 count < 0
         */
        public Builder registeredHandlerCount(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("处理器数量不能为负数: " + count);
            }
            this.registeredHandlerCount = count;
            return this;
        }

        /**
         * 设置 LOD 注入状态
         *
         * @param injected true 表示 LOD 已注入
         * @return this（链式调用）
         */
        public Builder lodInjected(boolean injected) {
            this.lodInjected = injected;
            return this;
        }

        /**
         * 设置剔除注入状态
         *
         * @param injected true 表示剔除已注入
         * @return this（链式调用）
         */
        public Builder cullingInjected(boolean injected) {
            this.cullingInjected = injected;
            return this;
        }

        /**
         * 设置状态消息
         *
         * @param message 状态消息（可以为 null）
         * @return this（链式调用）
         */
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /**
         * 构建 InterceptionResult 实例
         *
         * @return 不可变的 InterceptionResult 实例
         */
        public InterceptionResult build() {
            return new InterceptionResult(this);
        }
    }
}
