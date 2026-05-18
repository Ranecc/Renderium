// Renderium - Clear 清除命令
// 用于清除 Color/Depth/Stencil 附件

package com.ranecc.renderium.infrastructure.vulkan.command;

import com.ranecc.renderium.feature.pipeline.core.RenderPipeline;

/**
 * Clear 清除命令。
 *
 * <p>对应 Vulkan 的 vkCmdClearAttachments / vkCmdClearColorImage /
 * vkCmdClearDepthStencilImage 调用。
 *
 * <p>使用场景：
 * <ul>
 *   <li>帧开始时清除颜色缓冲</li>
 *   <li>清除深度缓冲</li>
 *   <li>清除模板缓冲</li>
 *   <li>天空盒渲染前的清除操作</li>
 * </ul>
 *
 * @see RenderCommand
 * @author Renderium Team
 * @since 1.0.0
 */
public final class ClearCommand implements RenderCommand {

    /** 清除目标标志位 */
    public static final int CLEAR_COLOR = 0x1;
    public static final int CLEAR_DEPTH = 0x2;
    public static final int CLEAR_STENCIL = 0x4;

    /** 清除掩码（组合 CLEAR_COLOR | CLEAR_DEPTH | CLEAR_STENCIL） */
    private final int clearMask;

    /** 清除颜色 RGBA */
    private final float clearR, clearG, clearB, clearA;

    /** 清除深度值 */
    private final float clearDepth;

    /** 清除模板值 */
    private final int clearStencil;

    /**
     * 创建全量清除命令
     *
     * @param clearMask 清除掩码
     * @param r 清除颜色 R
     * @param g 清除颜色 G
     * @param b 清除颜色 B
     * @param a 清除颜色 A
     * @param depth 清除深度
     * @param stencil 清除模板
     */
    public ClearCommand(int clearMask, float r, float g, float b, float a,
                        float depth, int stencil) {
        if (clearMask == 0) {
            throw new IllegalArgumentException("clearMask must have at least one bit set");
        }
        this.clearMask = clearMask;
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
        this.clearA = a;
        this.clearDepth = depth;
        this.clearStencil = stencil;
    }

    /**
     * 创建仅颜色清除命令
     */
    public static ClearCommand clearColor(float r, float g, float b, float a) {
        return new ClearCommand(CLEAR_COLOR, r, g, b, a, 1.0f, 0);
    }

    /**
     * 创建仅深度清除命令
     */
    public static ClearCommand clearDepth(float depth) {
        return new ClearCommand(CLEAR_DEPTH, 0f, 0f, 0f, 0f, depth, 0);
    }

    /**
     * 创建颜色+深度清除命令
     */
    public static ClearCommand clearColorAndDepth(float r, float g, float b, float a, float depth) {
        return new ClearCommand(CLEAR_COLOR | CLEAR_DEPTH, r, g, b, a, depth, 0);
    }

    @Override
    public CommandType getType() {
        return CommandType.CLEAR;
    }

    @Override
    public RenderPipeline getPipeline() {
        return null; // Clear 命令不需要管线状态
    }

    public long estimateGpuTimeNs() {
        // Clear 命令非常快
        return 50L;
    }

    // ==================== Getter ====================

    public int getClearMask() { return clearMask; }
    public float getClearR() { return clearR; }
    public float getClearG() { return clearG; }
    public float getClearB() { return clearB; }
    public float getClearA() { return clearA; }
    public float getClearDepth() { return clearDepth; }
    public int getClearStencil() { return clearStencil; }
    public boolean shouldClearColor() { return (clearMask & CLEAR_COLOR) != 0; }
    public boolean shouldClearDepth() { return (clearMask & CLEAR_DEPTH) != 0; }
    public boolean shouldClearStencil() { return (clearMask & CLEAR_STENCIL) != 0; }

    @Override
    public String toString() {
        return String.format("ClearCommand{mask=0x%X, color=(%.2f,%.2f,%.2f,%.2f), depth=%.2f, stencil=%d}",
                clearMask, clearR, clearG, clearB, clearA, clearDepth, clearStencil);
    }
}
