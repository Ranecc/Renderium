// Renderium - 选项值变更回调钩子函数式接口
// 用于在选项值被应用时执行副作用操作

package com.renderium.config.structure;

/**
 * 选项值变更应用钩子。
 *
 * <p>函数式接口，当选项的新值被确认应用到绑定目标后触发。
 * 用于执行那些需要在值变更时同步进行的副作用操作：
 * <ul>
 *   <li><b>资源重建</b>：更改渲染距离后重新构建可见性缓冲区</li>
 *   <li><b>Shader 重载</b>：更改着色器质量后重新编译 Shader</li>
 *   <li><b>缓存失效</b>：参数变更导致已有缓存数据过期</li>
 *   <li><b>事件通知</b>：向其他系统广播配置变更事件</li>
 *   <li><b>日志记录</b>：记录关键配置变更以供调试</li>
 * </ul>
 *
 * <h2>调用时机</h2>
 * <p>ApplyHook 在以下时机被调用：
 * <ol>
 *   <li>用户在 UI 中修改了选项值并点击"应用"按钮</li>
 *   <li>配置文件加载完成后的初始应用阶段</li>
 *   <li>通过 API 编程式修改选项值时（如果实现了 apply 流程）</li>
 * </ol>
 * 注意：仅在值<b>确实发生了变化</b>时才会触发，纯重置不会触发。
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 渲染距离变更时重建 HiZ 缓冲区
 * ApplyHook rebuildHiZ = newValue -&gt; {
 *     int newDistance = (Integer) newValue;
 *     HiZBufferManager.instance().rebuildForDistance(newDistance);
 *     Logger.debug("HiZ buffer rebuilt for distance={}", newDistance);
 * };
 *
 * // 应用到选项
 * IntegerOption renderDistance = IntegerOption.builder()
 *     .id(Identifier.parse("renderium:render_distance"))
 *     .applyHook(rebuildHiZ)
 *     .build();
 * </pre>
 *
 * <h3>异常处理策略</h3>
 * <ul>
 *   <li>钩子实现应自行捕获并处理预期内的异常（如资源不足）</li>
 *   <li>不应抛出未检查异常，以免中断配置应用流程</li>
 *   <li>建议使用日志记录失败原因，而非直接抛出</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see BooleanOption
 * @see IntegerOption
 * @see EnumOption
 */
@FunctionalInterface
public interface ApplyHook {

    /**
     * 当选项的新值被应用时执行回调。
     *
     * @param value 被应用的新值，类型取决于选项的具体类型：
     *              <ul>
     *                <li>{@link BooleanOption} → {@link Boolean}</li>
     *                <li>{@link IntegerOption} → {@link Integer}</li>
     *                <li>{@link EnumOption} → 枚举类型实例</li>
     *              </ul>
     *
     * <h4>实现规范</h4>
     * <ul>
     *   <li>此方法应快速完成，避免阻塞 UI 线程</li>
     *   <li>耗时操作应异步化或提交到工作队列</li>
     *   <li>不得修改传入的 value 参数（部分情况下可能是不可变对象）</li>
     * </ul>
     */
    void onApply(Object value);
}
