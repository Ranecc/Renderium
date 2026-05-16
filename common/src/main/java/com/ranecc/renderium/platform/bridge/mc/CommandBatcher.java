package com.ranecc.renderium.platform.bridge.mc;

/**
 * GPU 命令批处理器接口
 * <p>
 * 用于合并 UBO 更新、Compute Dispatch、Draw Call 等 GPU 命令，
 * 减少 Vulkan API 调用次数，降低 CPU 开销。
 *
 * @see MCRenderBridge
 * @since 1.0.0
 */
public interface CommandBatcher {
    void flush();

    int enqueueDrawCall(long pipelineHandle, long vertexCount, long instanceCount);

    void enqueueComputeDispatch(long pipelineHandle, int workGroupX, int workGroupY, int workGroupZ);

    void submitDrawCall(String drawCallId, int vertexCount);
}
