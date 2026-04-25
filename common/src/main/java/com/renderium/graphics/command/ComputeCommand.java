// Renderium - Compute 计算命令
// 用于 GPU Compute Shader 调度

package com.renderium.graphics.command;

import com.renderium.graphics.pipeline.RenderPipeline;

/**
 * Compute 计算命令。
 *
 * <p>对应 Vulkan 的 vkCmdDispatch 调用。
 * 用于 GPU 计算着色器的调度，例如：
 * <ul>
 *   <li>粒子系统并行计算</li>
 *   <li>后处理效果（模糊、色调映射）</li>
 *   <li>遮挡剔除计算</li>
 *   <li>LOD 选择计算</li>
 * </ul>
 *
 * @see RenderCommand
 * @author Renderium Team
 * @since 1.0.0
 */
public final class ComputeCommand implements RenderCommand {

    /** 计算着色器 Pipeline handle */
    private final long pipelineHandle;

    /** X 维度工作组数量 */
    private final int groupCountX;

    /** Y 维度工作组数量 */
    private final int groupCountY;

    /** Z 维度工作组数量 */
    private final int groupCountZ;

    /** 关联的描述符集 handle */
    private final long descriptorSet;

    /**
     * 创建 Compute 命令
     *
     * @param pipelineHandle 计算着色器 Pipeline handle
     * @param groupCountX X 维度工作组数量
     * @param groupCountY Y 维度工作组数量
     * @param groupCountZ Z 维度工作组数量
     * @param descriptorSet 描述符集 handle
     */
    public ComputeCommand(long pipelineHandle, int groupCountX, int groupCountY,
                          int groupCountZ, long descriptorSet) {
        if (pipelineHandle == 0L) {
            throw new IllegalArgumentException("pipelineHandle must not be zero");
        }
        if (groupCountX <= 0 || groupCountY <= 0 || groupCountZ <= 0) {
            throw new IllegalArgumentException(
                "group counts must be positive: x=" + groupCountX + " y=" + groupCountY + " z=" + groupCountZ);
        }
        this.pipelineHandle = pipelineHandle;
        this.groupCountX = groupCountX;
        this.groupCountY = groupCountY;
        this.groupCountZ = groupCountZ;
        this.descriptorSet = descriptorSet;
    }

    @Override
    public CommandType getType() {
        return CommandType.COMPUTE;
    }

    @Override
    public RenderPipeline getPipeline() {
        return null; // Compute 命令使用 pipelineHandle 而不是 RenderPipeline
    }

    public long estimateGpuTimeNs() {
        // Compute 命令的 GPU 时间取决于工作组数量，给出粗略估计
        return (long) groupCountX * groupCountY * groupCountZ * 100L;
    }

    // ==================== Getter ====================

    public long getPipelineHandle() { return pipelineHandle; }
    public int getGroupCountX() { return groupCountX; }
    public int getGroupCountY() { return groupCountY; }
    public int getGroupCountZ() { return groupCountZ; }
    public long getDescriptorSet() { return descriptorSet; }

    @Override
    public String toString() {
        return String.format("ComputeCommand{pipeline=0x%X, groups=(%d,%d,%d)}",
                pipelineHandle, groupCountX, groupCountY, groupCountZ);
    }
}
