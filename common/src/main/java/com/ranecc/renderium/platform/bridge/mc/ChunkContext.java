// Renderium - 轻量 MC 抽象层
// 区块渲染准备上下文 - 来自 op⑭ prepareChunkRenders

package com.ranecc.renderium.platform.bridge.mc;

/**
 * 区块渲染准备上下文（来自 op⑭ prepareChunkRenders）
 * <p>
 * 携带区块可见性和绘制调用统计信息。
 * 对于 LOD 和剔除系统至关重要。
 *
 * @see RenderiumLifecycleManager#fireBeforeChunkPrepare(ChunkContext)
 * @see RenderiumLifecycleManager#fireAfterChunkPrepare(ChunkContext)
 * @since 1.0.0
 */
public final class ChunkContext {

    /** 可见区块段数量 */
    public int visibleSectionCount;

    /** 总区块段数量 */
    public int totalSectionCount;

    /** 不透明绘制调用数 */
    public int opaqueDrawCallCount;

    /** 半透明绘制调用数 */
    public int translucentDrawCallCount;

    /** 视野区域是否变化 */
    public boolean viewAreaChanged;

    /** 重置到默认值 */
    public void reset() {
        visibleSectionCount = 0; totalSectionCount = 0;
        opaqueDrawCallCount = 0; translucentDrawCallCount = 0;
        viewAreaChanged = false;
    }
}
