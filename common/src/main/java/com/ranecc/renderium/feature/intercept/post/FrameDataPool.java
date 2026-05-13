// Renderium - Blaze3D 拦截层系统
// 帧数据对象池 - 消除热路径上的每帧对象分配

package com.ranecc.renderium.feature.intercept.post;
import com.ranecc.renderium.domain.model.FrameData;
import com.ranecc.renderium.domain.model.FrameCaptureContext;
import com.ranecc.renderium.domain.model.InterceptedFrameData;

import com.ranecc.renderium.None;

/**
 * 帧数据对象池 - 消除热路径上的每帧对象分配
 * <p>
 * 使用 {@link ThreadLocal} 复用 Builder 对象，避免每帧 new Builder() 产生的 GC 压力。
 * 在 60fps 下每秒可减少 ~120 次短生命周期对象分配（2 个 Builder/帧）。
 *
 * <h3>使用模式：</h3>
 * <pre>
 * // 1. 获取 Builder（传入当前帧的 FrameData）
 * InterceptedFrameData.Builder builder = FrameDataPool.acquireFrameDataBuilder(frameData);
 * try {
 *     InterceptedFrameData data = builder
 *         .renderMode("COMPATIBILITY")
 *         .qualityScore(1.0f)
 *         .build();
 * } finally {
 *     // 2. 归还 Builder（自动重置）
 *     FrameDataPool.releaseFrameDataBuilder(builder);
 * }
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>每个线程拥有独立的 Builder 实例，无需同步。
 * 渲染线程和异步捕获线程各自持有不同的 Builder，互不干扰。
 *
 * @since 5.1.0
 */
final class FrameDataPool {

    /**
     * 占位 FrameData - 用于 InterceptedFrameData.Builder 的 ThreadLocal 初始化
     * <p>
     * InterceptedFrameData.Builder 构造函数要求非 null 的 FrameData，
     * 此占位对象满足 Builder 校验，仅在 ThreadLocal 首次初始化时使用。
     * 后续 acquireFrameDataBuilder(FrameData) 会通过 reset() 替换为真实帧数据。
     * <p>
     * 使用满足 FrameData.Builder 校验的最小有效值：colorTexture=1, width=1, height=1, deltaTime=0.001。
     */
    private static final FrameData PLACEHOLDER_FRAME_DATA = new FrameData.Builder()
            .colorTexture(1L)
            .width(1)
            .height(1)
            .deltaTime(0.001f)
            .build();

    /**
     * InterceptedFrameData.Builder 线程本地池
     * <p>
     * 初始创建时使用占位 FrameData，使用时必须先调用 reset(FrameData) 设置真实帧数据。
     */
    private static final ThreadLocal<InterceptedFrameData.Builder> FRAME_DATA_BUILDER =
            ThreadLocal.withInitial(() -> new InterceptedFrameData.Builder(PLACEHOLDER_FRAME_DATA));

    /**
     * FrameCaptureContext.Builder 线程本地池
     */
    private static final ThreadLocal<FrameCaptureContext.Builder> CAPTURE_CONTEXT_BUILDER =
            ThreadLocal.withInitial(FrameCaptureContext.Builder::new);

    private FrameDataPool() {}

    /**
     * 获取当前线程的 InterceptedFrameData.Builder 并重置为指定帧数据
     * <p>
     * 调用此方法后，Builder 的所有字段已恢复为默认值，frameData 设置为指定值。
     * 使用完毕后必须调用 {@link #releaseFrameDataBuilder(InterceptedFrameData.Builder)} 归还。
     *
     * @param frameData 当前帧的原始数据（不能为 null）
     * @return 已重置的 Builder 实例
     */
    static InterceptedFrameData.Builder acquireFrameDataBuilder(FrameData frameData) {
        return FRAME_DATA_BUILDER.get().reset(frameData);
    }

    /**
     * 重置并归还 InterceptedFrameData.Builder 到池中
     * <p>
     * 将 Builder 的 frameData 替换为占位对象，释放对真实帧数据的引用，
     * 避免阻止 FrameData 被 GC 回收。其余字段在下次 acquire 时由 reset() 重置。
     * 应在 try-finally 块中调用以确保异常时也能归还。
     *
     * @param builder 要归还的 Builder 实例（可以为 null，此时无操作）
     */
    static void releaseFrameDataBuilder(InterceptedFrameData.Builder builder) {
        if (builder != null) {
            builder.reset(PLACEHOLDER_FRAME_DATA);
        }
    }

    /**
     * 获取当前线程的 FrameCaptureContext.Builder 并重置为默认状态
     * <p>
     * 调用此方法后，Builder 的所有字段已恢复为默认值。
     * 使用完毕后必须调用 {@link #releaseCaptureContextBuilder(FrameCaptureContext.Builder)} 归还。
     *
     * @return 已重置的 Builder 实例
     */
    static FrameCaptureContext.Builder acquireCaptureContextBuilder() {
        return CAPTURE_CONTEXT_BUILDER.get().reset();
    }

    /**
     * 重置并归还 FrameCaptureContext.Builder 到池中
     * <p>
     * 将 Builder 的所有字段重置为默认值，准备下次复用。
     * 应在 try-finally 块中调用以确保异常时也能归还。
     *
     * @param builder 要归还的 Builder 实例（可以为 null，此时无操作）
     */
    static void releaseCaptureContextBuilder(FrameCaptureContext.Builder builder) {
        if (builder != null) {
            builder.reset();
        }
    }
}
