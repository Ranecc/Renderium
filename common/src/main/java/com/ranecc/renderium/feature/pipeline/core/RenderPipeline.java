package com.ranecc.renderium.feature.pipeline.core;

/**
 * TODO [REVIEW] 桩类 - 渲染管线标识
 * 用于标识和区分不同的渲染管线（如 opaque、transparent、cutout 等）
 */
public class RenderPipeline {
    private final String name;
    private final int pipelineId;

    public RenderPipeline(String name, int pipelineId) {
        this.name = name;
        this.pipelineId = pipelineId;
    }

    /** 获取管线名称 */
    public String getName() { return name; }

    /** 获取管线ID */
    public int getPipelineId() { return pipelineId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RenderPipeline)) return false;
        return pipelineId == ((RenderPipeline) o).pipelineId;
    }

    @Override
    public int hashCode() { return Integer.hashCode(pipelineId); }
}
