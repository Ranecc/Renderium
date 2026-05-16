// Renderium - 绘制命令
// 封装 glDrawArrays / glDrawElements 调用为不可变命令对象
// 是最常用的渲染命令类型

package com.ranecc.renderium.infrastructure.vulkan.command;

import com.ranecc.renderium.None;
import com.ranecc.renderium.feature.pipeline.core.RenderPipeline;

/**
 * 绘制命令
 * <p>
 * 对应 OpenGL 的 `glDrawArrays` 和 `glDrawElements` 调用。
 * 由 MockGL11 Mixin 在拦截 Draw 调用时自动生成。
 * <p>
 * 该命令包含执行绘制所需的所有信息：
 * <ul>
 *   <li>管线状态 (RenderPipeline)</li>
 *   <li>顶点/索引缓冲区引用</li>
 *   <li>绘制模式 (TRIANGLES, LINES 等)</li>
 *   <li>顶点/索引数量和偏移</li>
 * </ul>
 *
 * <h3>生成时机：</h3>
 * 当老模组调用以下方法时，Mixin 拦截并编译为此命令：
 * <pre>{@code
 * GL11.glDrawArrays(mode, first, count);
 * GL11.glDrawElements(mode, count, type, indices);
 * }</pre>
 *
 * @see RenderCommand
 * @see CommandBuffer
 */
public final class DrawCommand implements RenderCommand {

    // ==================== 不可变字段 ====================

    /** 关联的渲染管线状态 */
    private final RenderPipeline pipeline;

    /** 绘制模式 (GL_TRIANGLES=4, GL_LINES=1 等) */
    private final int drawMode;

    /** 顶点数量 (DrawArrays) 或 索引数量 (DrawElements) */
    private final int count;

    /** 第一个顶点的偏移量 (DrawArrays) */
    private final int firstVertex;

    /**
     * 索引类型 (DrawElements)
     * 0 = 无索引 (DrawArrays)
     * 1 = UNSIGNED_BYTE
     * 2 = UNSIGNED_SHORT
     * 3 = UNSIGNED_INT
     */
    private final int indexType;

    /** 索引缓冲区偏移字节数 (DrawElements) */
    private final long indexBufferOffset;

    /** 实例化数量 (Instanced Drawing, 默认1=非实例化) */
    private final int instanceCount;

    /** 基础实例 ID (Instanced Drawing) */
    private final int baseInstance;

    // ==================== 构造函数 ====================

    /**
     * 创建 DrawArrays 类型的绘制命令
     *
     * @param pipeline     渲染管线状态
     * @param drawMode     绘制模式 (GL_TRIANGLES, GL_LINES 等)
     * @param firstVertex  起始顶点索引
     * @param vertexCount  顶点数量
     */
    public DrawCommand(RenderPipeline pipeline, int drawMode, int firstVertex, int vertexCount) {
        this(pipeline, drawMode, vertexCount, firstVertex, 0, 0L, 1, 0);
    }

    /**
     * 创建 DrawElements 类型的绘制命令
     *
     * @param pipeline          渲染管线状态
     * @param drawMode          绘制模式
     * @param indexCount        索引数量
     * @param indexType         索引类型 (UNSIGNED_BYTE/SHORT/INT)
     * @param indexBufferOffset 索引缓冲区字节偏移
     */
    public DrawCommand(RenderPipeline pipeline, int drawMode,
                        int indexCount, int indexType, long indexBufferOffset) {
        this(pipeline, drawMode, indexCount, 0, indexType, indexBufferOffset, 1, 0);
    }

    /**
     * 创建完整的绘制命令（包含所有参数）
     *
     * @param pipeline          渲染管线状态
     * @param drawMode          绘制模式
     * @param count             顶点/索引数量
     * @param firstVertex       首个顶点偏移
     * @param indexType         索引类型 (0=无)
     * @param indexBufferOffset 索引偏移
     * @param instanceCount     实例化数量
     * @param baseInstance      基础实例ID
     */
    public DrawCommand(RenderPipeline pipeline, int drawMode, int count,
                        int firstVertex, int indexType, long indexBufferOffset,
                        int instanceCount, int baseInstance) {
        if (pipeline == null) {
            throw new IllegalArgumentException("pipeline cannot be null");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0, got: " + count);
        }
        if (instanceCount <= 0) {
            throw new IllegalArgumentException("instanceCount must be > 0, got: " + instanceCount);
        }

        this.pipeline = pipeline;
        this.drawMode = drawMode;
        this.count = count;
        this.firstVertex = firstVertex;
        this.indexType = indexType;
        this.indexBufferOffset = indexBufferOffset;
        this.instanceCount = instanceCount;
        this.baseInstance = baseInstance;
    }

    // ==================== 查询方法 ====================

    @Override
    public RenderCommand.CommandType getType() {
        return RenderCommand.CommandType.DRAW;
    }

    @Override
    public RenderPipeline getPipeline() {
        return pipeline;
    }

    /** 是否为索引绘制 (DrawElements) */
    public boolean isIndexed() {
        return indexType != 0;
    }

    /** 是否为实例化绘制 */
    public boolean isInstanced() {
        return instanceCount > 1;
    }

    /** 获取绘制模式 */
    public int getDrawMode() {
        return drawMode;
    }

    /** 获取顶点/索引数量 */
    public int getCount() {
        return count;
    }

    /** 获取首个顶点偏移 */
    public int getFirstVertex() {
        return firstVertex;
    }

    /** 获取索引类型 */
    public int getIndexType() {
        return indexType;
    }

    /** 获取索引缓冲区偏移 */
    public long getIndexBufferOffset() {
        return indexBufferOffset;
    }

    /** 获取实例化数量 */
    public int getInstanceCount() {
        return instanceCount;
    }

    /** 获取基础实例 ID */
    public int getBaseInstance() {
        return baseInstance;
    }

    @Override
    public String toString() {
        String modeStr = isIndexed()
            ? String.format("DrawElements[mode=%d, count=%d, type=%d]", drawMode, count, indexType)
            : String.format("DrawArrays[mode=%d, first=%d, count=%d]", drawMode, firstVertex, count);

        if (isInstanced()) {
            modeStr += String.format(" instances=%d", instanceCount);
        }

        return String.format("DrawCommand{%s, pipeline=%s}", modeStr, pipeline.toString());
    }
}
