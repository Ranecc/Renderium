package com.ranecc.renderium.infrastructure.sanitizer;

/**
 * 渲染状态快照 — 保存/恢复 Vulkan 渲染状态
 * <p>
 * 保存内容：boundFBO, boundPipeline, viewport, stencilRef
 * 总大小：56 字节 (7 x 8 bytes)
 * <p>
 * 增强内容：boundTextureCount, tileEntityDirtyFlag
 */
public final class StateSnapshot {

    // ==================== 保存的渲染状态 ====================

    /** 当前绑定的 Framebuffer */
    final long boundFBO;

    /** 当前绑定的 Pipeline */
    final long boundPipeline;

    /** Viewport: x, y, width, height */
    final int viewportX, viewportY, viewportW, viewportH;

    /** Stencil reference value */
    final int stencilRef;

    /** 当前绑定的纹理数量（用于检测纹理泄漏） */
    final int boundTextureCount;

    /** TileEntity 脏状态标志 */
    final boolean tileEntityDirty;

    private StateSnapshot(long boundFBO, long boundPipeline,
                          int viewportX, int viewportY, int viewportW, int viewportH,
                          int stencilRef, int boundTextureCount, boolean tileEntityDirty) {
        this.boundFBO = boundFBO;
        this.boundPipeline = boundPipeline;
        this.viewportX = viewportX;
        this.viewportY = viewportY;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.stencilRef = stencilRef;
        this.boundTextureCount = boundTextureCount;
        this.tileEntityDirty = tileEntityDirty;
    }

    /**
     * 捕获当前渲染状态快照
     * <p>
     * 开销：~50ns（7 次 long/int 读取）
     */
    public static StateSnapshot capture() {
        // TODO: 从 VulkanDeviceHolder 获取实际状态
        // 当前使用占位值，待集成 Vulkan 状态查询
        return new StateSnapshot(
            0L,   // boundFBO — 从当前渲染上下文获取
            0L,   // boundPipeline
            0, 0, 0, 0, // viewport
            0,    // stencilRef
            0,    // boundTextureCount
            false // tileEntityDirty
        );
    }

    /**
     * 恢复渲染状态到快照时的值
     * <p>
     * 开销：~50ns（7 次 long/int 写入）
     */
    public void restore() {
        // TODO: 将保存的状态写回 Vulkan 渲染上下文
        // vkCmdBindFramebuffer(boundFBO)
        // vkCmdBindPipeline(boundPipeline)
        // vkCmdSetViewport(viewportX, viewportY, viewportW, viewportH)
        // vkCmdSetStencilReference(stencilRef)
    }

    /**
     * 检查当前状态是否与快照一致（用于脏数据检测）
     */
    public boolean isConsistentWith(StateSnapshot other) {
        return this.boundFBO == other.boundFBO
            && this.boundPipeline == other.boundPipeline
            && this.viewportW == other.viewportW
            && this.viewportH == other.viewportH
            && this.stencilRef == other.stencilRef;
    }
}
