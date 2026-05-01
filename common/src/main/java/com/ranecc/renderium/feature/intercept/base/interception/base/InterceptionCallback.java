// Renderium - Blaze3D 拦截层系统
// 异步拦截完成回调接口 - 支持异步拦截操作的通知机制

package com.ranecc.renderium.feature.intercept.base.interception.base;

/**
 * 异步拦截完成回调接口
 * <p>
 * 定义异步拦截操作完成后的通知机制。
 * 当 {@link PreBlaze3DInterceptor#intercept(RenderContext)} 在异步模式下
 * 执行完成后，将通过此接口通知调用方。
 *
 * <h3>使用场景：</h3>
 * <ul>
 *   <li>异步模组检测：在后台线程检测模组加载状态</li>
 *   <li>异步 LOD 计算：在 Compute Shader 中计算 LOD 层级</li>
 *   <li>异步剔除预处理：在独立线程中执行遮挡剔除查询</li>
 * </ul>
 *
 * <h3>线程安全：</h3>
 * <p>回调方法可能在任意线程中调用，实现类应保证线程安全。
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 设置异步回调
 * preInterceptor.setAsyncCallback(new InterceptionCallback() {
 *     &#64;Override
 *     public void onInterceptionComplete(InterceptionResult result) {
 *         // 处理异步完成的拦截结果
 *         if (result.isSuccess()) {
 *             applyModifiedContext(result.getModifiedContext());
 *         }
 *     }
 *
 *     &#64;Override
 *     public void onInterceptionError(Throwable error) {
 *         // 处理错误
 *         LOGGER.severe("异步拦截失败: " + error.getMessage());
 *     }
 * });
 * </pre>
 *
 * @see PreBlaze3DInterceptor#setAsyncCallback(InterceptionCallback)
 * @see InterceptionResult
 * @since 5.1.0
 */
public interface InterceptionCallback {

    /**
     * 拦截操作成功完成时调用
     * <p>
     * 当异步拦截操作成功执行完毕后调用此方法。
     * 调用方应根据结果状态决定后续操作：
     * <ul>
     *   <li>SUCCESS：使用修改后的上下文继续渲染</li>
     *   <li>PARTIAL：部分功能降级，可继续但需记录警告</li>
     *   <li>SKIPPED：被跳过，使用原始路径</li>
     *   <li>FAILURE：失败，回退到原始渲染路径</li>
     * </ul>
     *
     * @param result 拦截结果（包含修改后的上下文和性能指标）
     */
    void onInterceptionComplete(InterceptionResult result);

    /**
     * 拦截操作发生错误时调用
     * <p>
     * 当异步拦截操作抛出异常或发生未预期错误时调用。
     * 调用方应进行适当的错误处理和降级策略。
     *
     * @param error 导致失败的异常对象
     */
    void onInterceptionError(Throwable error);
}
