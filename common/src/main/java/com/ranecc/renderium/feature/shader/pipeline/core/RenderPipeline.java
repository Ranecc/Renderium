package com.ranecc.renderium.feature.shader.pipeline.core;

import com.ranecc.renderium.feature.renderopt.GLStateSnapshot;

/**
 * 渲染管线核心标识与生命周期管理
 * <p>
 * 用于标识和区分不同的渲染管线（如 opaque、transparent、cutout 等），
 * 并提供 initialize / shutdown 生命周期控制。
 * </p>
 */
public class RenderPipeline {

    private final String name;
    private final int pipelineId;
    private boolean initialized;

    public RenderPipeline(String name, int pipelineId) {
        this.name = name;
        this.pipelineId = pipelineId;
        this.initialized = false;
    }

    /** 获取管线名称 */
    public String getName() { return name; }

    /** 获取管线 ID */
    public int getPipelineId() { return pipelineId; }

    /**
     * 初始化管线资源
     *
     * @return 初始化成功返回 true
     */
    public boolean initialize() {
        initialized = true;
        return true;
    }

    /**
     * 检查管线是否已初始化
     *
     * @return 已初始化返回 true
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 关闭管线，释放资源
     */
    public void shutdown() {
        initialized = false;
    }

    /**
     * Builder for creating RenderPipeline instances from GLStateSnapshot.
     */
    public static final class Builder {
        private int pipelineId;

        public static Builder fromSnapshot(GLStateSnapshot snapshot) {
            Builder builder = new Builder();
            builder.pipelineId = snapshot.getPipelineId();
            return builder;
        }

        public RenderPipeline build() {
            return new RenderPipeline("pipeline_" + pipelineId, pipelineId);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RenderPipeline)) return false;
        return pipelineId == ((RenderPipeline) o).pipelineId;
    }

    @Override
    public int hashCode() { return Integer.hashCode(pipelineId); }
}
