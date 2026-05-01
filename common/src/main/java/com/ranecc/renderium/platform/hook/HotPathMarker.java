package com.ranecc.renderium.platform.hook;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 热路径标记注解 — 标识每帧渲染循环中的高频调用方法
 *
 * <p>被此注解标记的方法遵循以下约束：
 * <ul>
 *   <li>禁止在方法内分配堆内存（new 对象）</li>
 *   <li>禁止调用可能阻塞的 I/O 操作</li>
 *   <li>禁止使用 synchronized 锁</li>
 *   <li>推荐使用 final/static 方法以便 JIT 内联</li>
 * </ul>
 *
 * <p><b>性能预算</b>: 单次执行时间 &lt; 100μs（含回调）
 *
 * <h3>频率分类说明</h3>
 * <table border="1">
 *   <tr><th>频率等级</th><th>每帧调用次数</th><th>典型场景</th><th>预算</th></tr>
 *   <tr><td>PER_FRAME_HIGH</td><td>数千次</td><td>draw call 拦截</td><td>&lt;100ns</td></tr>
 *   <tr><td>PER_FRAME_MEDIUM</td><td>数百次</td><td>pipeline 切换、纹理绑定</td><td>&lt;200ns</td></tr>
 *   <tr><td>PER_FRAME_LOW</td><td>数次</td><td>render pass、command submit</td><td>&lt;1μs</td></tr>
 *   <tr><td>PER_MULTI_FRAME</td><td>多帧一次</td><td>配置检查、统计输出</td><td>可接受较重</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @HotPathMarker(
 *     frequency = Frequency.PER_FRAME_HIGH,
 *     budgetNs = 5000,
 *     description = "Draw call interception"
 * )
 * public boolean onDrawIndexed(int count, int instanceCount, ...) {
 *     // 热路径逻辑 — 零分配，极简实现
 * }
 * }</pre>
 *
 * @see HookDispatcher
 * @see FrameContext
 * @since 2.0.0
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HotPathMarker {

    /**
     * 调用频率分类
     *
     * <p>描述该热路径方法的预期调用频率，
     * 用于性能预算分配和优化优先级排序。
     * JIT 编译器可根据此信息决定内联策略。
     */
    enum Frequency {
        /**
         * 每帧数千次调用（如 draw 调用）— 最严格的性能约束
         *
         * <p>单次执行必须 &lt; 100ns，禁止任何对象分配。
         * JIT 应对此类方法进行激进内联。
         */
        PER_FRAME_HIGH,

        /**
         * 每帧数百次调用（如 pipeline 切换、纹理绑定）
         *
         * <p>单次执行应 &lt; 200ns，允许极少量栈上操作。
         */
        PER_FRAME_MEDIUM,

        /**
         * 每帧数次调用（如 render pass、frame graph execute）
         *
         * <p>单次执行应 &lt; 1μs，可接受简单的条件判断。
         */
        PER_FRAME_LOW,

        /**
         * 多帧一次调用（如配置检查、统计报告输出）
         *
         * <p>可接受较重的逻辑，但仍需避免阻塞操作。
         */
        PER_MULTI_FRAME
    }

    /**
     * 该热路径方法的预期调用频率
     *
     * @return Frequency 调用频率枚举值，默认为中等频率
     */
    Frequency frequency() default Frequency.PER_FRAME_MEDIUM;

    /**
     * 单次调用的纳秒级时间预算
     *
     * <p>超出此预算时应记录警告日志（由 {@link PerformanceProfiler} 检测）。
     * 设为 0 表示不限制（不推荐用于高频方法）。
     *
     * @return long 时间预算（纳秒），0 表示无限制
     */
    long budgetNs() default 0;

    /**
     * 该方法的热路径描述
     *
     * <p>用于生成性能报告、文档和静态分析工具的标注。
     * 建议简洁描述方法用途和在渲染管线中的位置。
     *
     * @return String 方法描述文本，默认为空字符串
     */
    String description() default "";
}
