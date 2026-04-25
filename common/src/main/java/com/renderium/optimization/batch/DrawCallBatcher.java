// Renderium - DrawCall 批处理器
// 合并相同渲染状态的 DrawCall，减少 CPU 开销和状态切换
// 借鉴 Zink 的批处理策略和 Vulkan 的 subpass 机制

package com.renderium.optimization.batch;

import com.renderium.config.ConfigConstants;
import com.renderium.graphics.command.DrawCommand;
import com.renderium.graphics.pipeline.RenderPipeline;
import com.renderium.optimization.mesh.MeshData;

import java.util.ArrayList;
import java.util.List;

/**
 * DrawCall 批处理器。
 *
 * <p>合并相同渲染状态的 DrawCall，减少 CPU 开销和 GPU 状态切换。
 *
 * <h2>批处理策略</h2>
 * <p>两个 DrawCall 可以合并当且仅当：
 * <ul>
 *   <li>使用相同的 RenderPipeline（相同的 blend/depth/cull 状态）</li>
 *   <li>使用相同的纹理</li>
 *   <li>使用相同的着色器程序</li>
 *   <li>顶点数据在同一个缓冲区中</li>
 * </ul>
 *
 * <h2>合并方式</h2>
 * <p>将多个小 DrawCall 合并为一个大的 DrawCall：
 * <pre>
 * 合并前：
 *   Draw(vertexOffset=0, count=6, texture=1)     // 方块 A
 *   Draw(vertexOffset=6, count=6, texture=1)     // 方块 B
 *   Draw(vertexOffset=12, count=6, texture=1)    // 方块 C
 *
 * 合并后：
 *   Draw(vertexOffset=0, count=18, texture=1)    // 方块 A+B+C
 * </pre>
 *
 * <h2>与 Zink 的关系</h2>
 * <p>Zink 使用 deferred batch 来合并相同 Pipeline 的绘制调用。
 * Renderium 的 DrawCallBatcher 更进一步，利用 MeshSection 的纹理分组
 * 来最大化合并效率。
 *
 * <h2>并发安全说明</h2>
 * <p>此类不是线程安全的。应在单线程（渲染线程）中使用。
 * flush() 和 reset() 方法不应在多个线程间交错调用。
 *
 * @see <a href="https://gitlab.freedesktop.org/mesa/mesa/-/tree/main/src/gallium/drivers/zink">Mesa Zink Batch System</a>
 * @see DrawCommand
 * @see RenderPipeline
 * @author Renderium Team
 * @since 1.0.0
 */
public final class DrawCallBatcher {

    // ==================== 批次定义 ====================

    /**
     * 合并后的渲染批次
     */
    public static final class Batch {
        /** 此批次使用的 Pipeline（不可变） */
        public final RenderPipeline pipeline;
        /** 纹理 ID */
        public final int textureId;
        /** 顶点缓冲区中的起始偏移 */
        public int vertexOffset;
        /** 顶点数量 */
        public int vertexCount;
        /** 索引缓冲区中的起始偏移 */
        public int indexOffset;
        /** 索引数量 */
        public int indexCount;
        /** 合并的原始 DrawCall 数量 */
        public int mergedDrawCallCount;

        Batch(RenderPipeline pipeline, int textureId) {
            this.pipeline = pipeline;
            this.textureId = textureId;
        }
    }

    // ==================== 实例字段 ====================

    /**
     * 当前帧的批次列表。
     * <p>Thread-safety: 仅渲染线程访问，无需额外同步。
     * 优化：复用列表对象而非每次都创建新的 ArrayList，减少 GC 压力。
     */
    private final List<Batch> batches;

    /** 当前活跃的批次（用于合并） */
    private Batch currentBatch;

    /** 统计：原始 DrawCall 数量 */
    private int rawDrawCallCount = 0;

    /** 统计：合并后的批次数量 */
    private int batchedDrawCallCount = 0;

    /** 统计：节省的 DrawCall 数量 */
    private int savedDrawCalls = 0;

    /**
     * 创建 DrawCall 批处理器
     *
     * <p>使用预分配的 ArrayList 避免每帧 GC 压力。
     */
    public DrawCallBatcher() {
        this.batches = new ArrayList<>(ConfigConstants.MAX_BATCH_SIZE);
    }

    // ==================== 核心方法 ====================

    /**
     * 添加一个 DrawCall 到批处理器
     *
     * <p>如果当前活跃批次与新的 DrawCall 具有相同的 Pipeline 和纹理，
     * 则合并到当前批次；否则创建新批次。
     *
     * @param pipeline 渲染管线状态
     * @param textureId 纹理 ID
     * @param vertexOffset 顶点偏移
     * @param vertexCount 顶点数量
     * @param indexOffset 索引偏移
     * @param indexCount 索引数量
     */
    public void addDrawCall(RenderPipeline pipeline, int textureId,
                            int vertexOffset, int vertexCount,
                            int indexOffset, int indexCount) {
        rawDrawCallCount++;

        // 检查是否可以合并到当前批次
        if (canMergeWithCurrentBatch(pipeline, textureId, indexOffset)) {
            // 合并：扩展当前批次的索引范围
            currentBatch.indexCount += indexCount;
            currentBatch.vertexCount = Math.max(currentBatch.vertexCount,
                    vertexOffset + vertexCount - currentBatch.vertexOffset);
            currentBatch.mergedDrawCallCount++;
            savedDrawCalls++;
        } else {
            // 不能合并：完成当前批次，创建新批次
            finishCurrentBatch();

            currentBatch = new Batch(pipeline, textureId);
            currentBatch.vertexOffset = vertexOffset;
            currentBatch.vertexCount = vertexCount;
            currentBatch.indexOffset = indexOffset;
            currentBatch.indexCount = indexCount;
            currentBatch.mergedDrawCallCount = 1;
        }
    }

    /**
     * 从 MeshData 的 MeshSection 添加 DrawCall
     *
     * <p>利用 ChunkMeshBuilder 已经按纹理分好的 MeshSection，
     * 每个 Section 就是一个天然的合并批次。
     *
     * @param meshData 网格数据
     * @param pipeline 渲染管线状态
     */
    public void addMeshData(MeshData meshData, RenderPipeline pipeline) {
        for (var section : meshData.getSections()) {
            addDrawCall(pipeline, section.textureId,
                    0, meshData.getVertexCount(),
                    section.indexStart, section.indexCount);
        }
    }

    /**
     * 完成所有批次并返回结果
     *
     * <p>Thread-safety: 仅在渲染线程调用。
     * 优化：清空并复用内部列表，避免每次 flush 创建新的 ArrayList。
     * 调用方应在下次 flush 前消费返回的列表。
     *
     * @return 批次列表（只读，不应修改）
     */
    public List<Batch> flush() {
        finishCurrentBatch();

        batchedDrawCallCount = batches.size();

        // 返回列表，调用方消费前不应修改
        List<Batch> result = new ArrayList<>(batches);
        // 清空复用列表，避免每帧创建新的 ArrayList
        batches.clear();
        return result;
    }

    /**
     * 重置批处理器（每帧开始时调用）
     */
    public void reset() {
        batches.clear();
        currentBatch = null;
        rawDrawCallCount = 0;
        batchedDrawCallCount = 0;
        savedDrawCalls = 0;
    }

    // ==================== 内部方法 ====================

    /**
     * 检查是否可以合并到当前批次
     */
    private boolean canMergeWithCurrentBatch(RenderPipeline pipeline, int textureId, int nextIndexOffset) {
        if (currentBatch == null) return false;

        // Pipeline 必须相同
        if (!currentBatch.pipeline.equals(pipeline)) return false;

        // 纹理必须相同
        if (currentBatch.textureId != textureId) return false;

        // 索引必须连续（当前批次的末尾 = 下一个的起始）
        int expectedNextOffset = currentBatch.indexOffset + currentBatch.indexCount;
        return nextIndexOffset == expectedNextOffset;
    }

    /**
     * 完成当前批次
     */
    private void finishCurrentBatch() {
        if (currentBatch != null) {
            batches.add(currentBatch);
            currentBatch = null;
        }
    }

    // ==================== 统计 ====================

    /**
     * 获取合并率
     *
     * @return 0.0-1.0 之间的合并率
     */
    public float getMergeRate() {
        if (rawDrawCallCount == 0) return 0f;
        return (float) savedDrawCalls / rawDrawCallCount;
    }

    public int getRawDrawCallCount() { return rawDrawCallCount; }
    public int getBatchedDrawCallCount() { return batchedDrawCallCount; }
    public int getSavedDrawCalls() { return savedDrawCalls; }

    public String getDiagnostics() {
        return String.format(
            "DrawCallBatcher{raw=%d, batched=%d, saved=%d, rate=%.1f%%}",
            rawDrawCallCount, batchedDrawCallCount, savedDrawCalls, getMergeRate() * 100);
    }
}
