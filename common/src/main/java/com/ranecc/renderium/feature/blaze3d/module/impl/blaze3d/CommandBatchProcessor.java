// Renderium - 命令批处理器
// 基于 blaze3d_optimization_analysis.md §2.2 命令批处理策略

package com.ranecc.renderium.feature.blaze3d.module.impl.blaze3d;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.ranecc.renderium.None;

/**
 * 命令批处理器。
 *
 * <p>基于 {@code blaze3d_optimization_analysis.md} 中识别的高收益优化：
 * <b>命令批处理</b>可以减少 CPU-GPU 同步次数，显著提升渲染性能。
 *
 * <h3>核心设计</h3>
 * <pre>
 * 传统方式 (Blaze3D 默认):
 *   for (RenderLayer layer : layers) {
 *       encoder.beginRenderPass(...);    // ← 立即提交
 *       render(layer);
 *       encoder.endRenderPass();         // ← 立即提交
 *   }
 *
 * 批处理方式:
 *   batch.beginBatch();
 *   for (RenderLayer layer : layers) {
 *       batch.addRenderPass(layer);      // ← 累积到批次中
 *   }
 *   batch.submitBatch();                // ← 一次性提交所有 Pass
 * </pre>
 *
 * <h3>性能收益</h3>
 * <ul>
 *   <li>减少 CPU-GPU 同步次数（从 N 次降到 1 次）</li>
 *   <li>允许驱动优化命令排序和合并</li>
 *   <li>降低 CPU 端的 API 调用开销</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 */
public final class CommandBatchProcessor {

    private static final Logger LOGGER = Logger.getLogger(CommandBatchProcessor.class.getName());

    /** 默认最大批次数 */
    public static final int DEFAULT_MAX_BATCH_SIZE = 32;

    /** 当前批次中的命令列表 */
    private final ConcurrentLinkedQueue<BatchedCommand> pendingCommands = new ConcurrentLinkedQueue<>();

    /** 是否正在构建批次 */
    private volatile boolean batchOpen = false;

    /** 当前批次 ID */
    private volatile long currentBatchId = 0;

    /** 最大批次大小 */
    private final int maxBatchSize;

    // ==================== 统计字段 ====================

    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalCommands = new AtomicLong(0);
    private final AtomicInteger maxBatchSizeSeen = new AtomicInteger(0);

    /** Draw call 计数器 */
    private final AtomicLong drawCallCount = new AtomicLong(0);

    /** 最后绑定的 Pipeline 句柄 */
    private volatile long lastBoundPipeline = 0;

    /** 最后绑定的顶点缓冲区句柄 */
    private volatile long lastBoundVertexBuffer = 0;

    /** 最后绑定的索引缓冲区句柄 */
    private volatile long lastBoundIndexBuffer = 0;

    /**
     * 批次命令条目
     */
    public static class BatchedCommand {
        /** 命令类型 */
        public enum Type {
            BEGIN_RENDER_PASS,
            END_RENDER_PASS,
            BIND_PIPELINE,
            BIND_VERTEX_BUFFER,
            BIND_INDEX_BUFFER,
            DRAW_INDEXED,
            DRAW,
            PUSH_CONSTANTS,
            OTHER
        }

        public final Type type;
        public final long[] params;
        public final Object payload;

        public BatchedCommand(Type type, long[] params, Object payload) {
            this.type = type;
            this.params = params != null ? params : new long[0];
            this.payload = payload;
        }
    }

    /**
     * 创建命令批处理器
     */
    public CommandBatchProcessor() {
        this(DEFAULT_MAX_BATCH_SIZE);
    }

    /**
     * 创建命令批处理器（自定义大小）
     *
     * @param maxBatchSize 最大批次大小
     */
    public CommandBatchProcessor(int maxBatchSize) {
        this.maxBatchSize = Math.max(1, maxBatchSize);
        LOGGER.info("CommandBatchProcessor initialized (max_batch=" + this.maxBatchSize + ")");
    }

    // ==================== 批次管理 API ====================

    /**
     * 开始新批次
     *
     * @return 批次 ID
     */
    public long beginBatch() {
        if (batchOpen) {
            LOGGER.warning("批次已打开，先关闭当前批次");
            submitBatch();
        }

        pendingCommands.clear();
        batchOpen = true;
        currentBatchId = totalBatches.incrementAndGet();

        LOGGER.finest("Batch " + currentBatchId + " opened");
        return currentBatchId;
    }

    /**
     * 结束当前批次并提交
     *
     * <p>将累积的 BatchedCommand 逐条翻译为 RenderBackendProxy 调用：
     * <ul>
     *   <li>DRAW / DRAW_INDEXED → {@code proxy.draw()} / {@code proxy.drawIndexed()}</li>
     *   <li>BIND_* → 记录绑定状态（后端暂无直接 API）</li>
     *   <li>BEGIN/END_RENDER_PASS → 记录日志（后端暂无直接 API）</li>
     *   <li>PUSH_CONSTANTS → 记录日志（后端暂无直接 API）</li>
     * </ul>
     *
     * @return 提交的命令数量
     */
    public int submitBatch() {
        if (!batchOpen || pendingCommands.isEmpty()) {
            batchOpen = false;
            return 0;
        }

        RenderBackendProxy proxy = RenderBackendProxy.getInstance();
        boolean backendAvailable = proxy.isInitialized();

        int submittedCount = 0;
        int drawCalls = 0;

        BatchedCommand cmd;
        while ((cmd = pendingCommands.poll()) != null) {
            switch (cmd.type) {
                case BEGIN_RENDER_PASS -> {
                    LOGGER.fine("Batch BEGIN_RENDER_PASS: renderPass=" + param(cmd, 0)
                        + " framebuffer=" + param(cmd, 1)
                        + " " + param(cmd, 2) + "x" + param(cmd, 3));
                    submittedCount++;
                }
                case END_RENDER_PASS -> {
                    LOGGER.fine("Batch END_RENDER_PASS");
                    submittedCount++;
                }
                case BIND_PIPELINE -> {
                    lastBoundPipeline = param(cmd, 0);
                    LOGGER.fine("Batch BIND_PIPELINE: handle=" + lastBoundPipeline);
                    submittedCount++;
                }
                case BIND_VERTEX_BUFFER -> {
                    lastBoundVertexBuffer = param(cmd, 1);
                    LOGGER.fine("Batch BIND_VERTEX_BUFFER: binding=" + param(cmd, 0)
                        + " buffer=" + lastBoundVertexBuffer
                        + " offset=" + param(cmd, 2) + " stride=" + param(cmd, 3));
                    submittedCount++;
                }
                case BIND_INDEX_BUFFER -> {
                    lastBoundIndexBuffer = param(cmd, 0);
                    LOGGER.fine("Batch BIND_INDEX_BUFFER: buffer=" + lastBoundIndexBuffer
                        + " offset=" + param(cmd, 1) + " indexType=" + param(cmd, 2));
                    submittedCount++;
                }
                case DRAW_INDEXED -> {
                    if (backendAvailable) {
                        proxy.drawIndexed(
                            (int) param(cmd, 0),
                            (int) param(cmd, 2),
                            (int) param(cmd, 3),
                            (int) param(cmd, 1)
                        );
                    }
                    drawCalls++;
                    submittedCount++;
                }
                case DRAW -> {
                    if (backendAvailable) {
                        proxy.draw(
                            (int) param(cmd, 0),
                            (int) param(cmd, 2),
                            (int) param(cmd, 1)
                        );
                    }
                    drawCalls++;
                    submittedCount++;
                }
                case PUSH_CONSTANTS -> {
                    LOGGER.fine("Batch PUSH_CONSTANTS: stageFlags=" + param(cmd, 0)
                        + " offset=" + param(cmd, 1) + " size=" + param(cmd, 2));
                    submittedCount++;
                }
                default -> {
                    LOGGER.fine("Unknown command type in batch: " + cmd.type);
                }
            }
        }

        drawCallCount.addAndGet(drawCalls);
        totalCommands.addAndGet(submittedCount);

        if (submittedCount > maxBatchSizeSeen.get()) {
            maxBatchSizeSeen.set(submittedCount);
        }

        batchOpen = false;

        LOGGER.fine("Batch " + currentBatchId + " submitted: "
            + submittedCount + " commands, " + drawCalls + " draw calls");

        return submittedCount;
    }

    /**
     * 取消当前批次（不提交）
     */
    public void cancelBatch() {
        pendingCommands.clear();
        batchOpen = false;
        LOGGER.fine("Batch " + currentBatchId + " cancelled");
    }

    // ==================== 命令添加 API ====================

    /**
     * 添加 BeginRenderPass 命令
     *
     * @param renderPass RenderPass 句柄
     * @param framebuffer Framebuffer 句柄
     * @param width 宽度
     * @param height 高度
     * @return 是否成功添加
     */
    public boolean addBeginRenderPass(long renderPass, long framebuffer, int width, int height) {
        return addCommand(BatchedCommand.Type.BEGIN_RENDER_PASS,
                         new long[]{renderPass, framebuffer, width, height}, null);
    }

    /**
     * 添加 EndRenderPass 命令
     *
     * @return 是否成功添加
     */
    public boolean addEndRenderPass() {
        return addCommand(BatchedCommand.Type.END_RENDER_PASS, null, null);
    }

    /**
     * 添加 BindPipeline 命令
     *
     * @param pipeline Pipeline 句柄
     * @return 是否成功添加
     */
    public boolean addBindPipeline(long pipeline) {
        return addCommand(BatchedCommand.Type.BIND_PIPELINE, new long[]{pipeline}, null);
    }

    /**
     * 添加 BindVertexBuffer 命令
     *
     * @param binding 绑定点
     * @param buffer 缓冲区句柄
     * @param offset 偏移
     * @param stride 步长
     * @return 是否成功添加
     */
    public boolean addBindVertexBuffer(int binding, long buffer, long offset, long stride) {
        return addCommand(BatchedCommand.Type.BIND_VERTEX_BUFFER,
                         new long[]{binding, buffer, offset, stride}, null);
    }

    /**
     * 添加 BindIndexBuffer 命令
     *
     * @param buffer 缓冲区句柄
     * @param offset 偏移
     * @param indexType 索引类型
     * @return 是否成功添加
     */
    public boolean addBindIndexBuffer(long buffer, long offset, int indexType) {
        return addCommand(BatchedCommand.Type.BIND_INDEX_BUFFER,
                         new long[]{buffer, offset, indexType}, null);
    }

    /**
     * 添加 DrawIndexed 命令
     *
     * @param indexCount 索引数量
     * @param instanceCount 实例数量
     * @param firstIndex 首个索引
     * @param baseVertex 基础顶点
     * @param firstInstance 首个实例
     * @return 是否成功添加
     */
    public boolean addDrawIndexed(int indexCount, int instanceCount,
                                  int firstIndex, int baseVertex, int firstInstance) {
        return addCommand(BatchedCommand.Type.DRAW_INDEXED,
                         new long[]{indexCount, instanceCount, firstIndex, baseVertex, firstInstance},
                         null);
    }

    /**
     * 添加 Draw 命令
     *
     * @param vertexCount 顶点数量
     * @param instanceCount 实例数量
     * @param firstVertex 首个顶点
     * @param firstInstance 首个实例
     * @return 是否成功添加
     */
    public boolean addDraw(int vertexCount, int instanceCount,
                          int firstVertex, int firstInstance) {
        return addCommand(BatchedCommand.Type.DRAW,
                         new long[]{vertexCount, instanceCount, firstVertex, firstInstance},
                         null);
    }

    /**
     * 添加 PushConstants 命令
     *
     * @param stageFlags 阶段标志
     * @param offset 偏移
     * @param data 数据
     * @return 是否成功添加
     */
    public boolean addPushConstants(int stageFlags, int offset, byte[] data) {
        return addCommand(BatchedCommand.Type.PUSH_CONSTANTS,
                         new long[]{stageFlags, offset, data.length}, data);
    }

    /**
     * 添加通用命令
     *
     * @param type 命令类型
     * @param params 参数数组
     * @param payload 负载数据
     * @return 是否成功添加
     */
    public boolean addCommand(BatchedCommand.Type type, long[] params, Object payload) {
        if (!batchOpen) {
            LOGGER.warning("未在批次模式，忽略命令: " + type);
            return false;
        }

        if (pendingCommands.size() >= maxBatchSize) {
            LOGGER.warning("批次已满 (" + maxBatchSize + ")，自动提交");
            submitBatch();
            beginBatch();
        }

        pendingCommands.add(new BatchedCommand(type, params, payload));
        return true;
    }

    // ==================== 查询接口 ====================

    /** 是否正在构建批次 */
    public boolean isBatchOpen() { return batchOpen; }

    /** 获取当前批次 ID */
    public long getCurrentBatchId() { return currentBatchId; }

    /** 获取当前批次中的命令数量 */
    public int getPendingCommandCount() { return pendingCommands.size(); }

    /** 获取总批次数 */
    public long getTotalBatches() { return totalBatches.get(); }

    /** 获取总命令数 */
    public long getTotalCommands() { return totalCommands.get(); }

    /** 获取见过的最大批次大小 */
    public int getMaxBatchSizeSeen() { return maxBatchSizeSeen.get(); }

    /** 获取总 Draw Call 数 */
    public long getDrawCallCount() { return drawCallCount.get(); }

    /** 获取最后绑定的 Pipeline 句柄 */
    public long getLastBoundPipeline() { return lastBoundPipeline; }

    /** 获取最后绑定的顶点缓冲区句柄 */
    public long getLastBoundVertexBuffer() { return lastBoundVertexBuffer; }

    /** 获取最后绑定的索引缓冲区句柄 */
    public long getLastBoundIndexBuffer() { return lastBoundIndexBuffer; }

    /**
     * 安全读取 BatchedCommand 的参数
     *
     * @param cmd 命令对象
     * @param index 参数索引
     * @return 参数值，越界或 params 为 null 时返回 0
     */
    private static long param(BatchedCommand cmd, int index) {
        return cmd.params != null && index < cmd.params.length ? cmd.params[index] : 0L;
    }

    /**
     * 获取诊断信息
     *
     * @return 格式化的状态字符串
     */
    public String getDiagnostics() {
        return String.format(
            "CommandBatchProcessor{\n" +
            "  open=%s, batch_id=%d\n" +
            "  pending=%d/%d\n" +
            "  total_batches=%d, total_commands=%d, draw_calls=%d\n" +
            "  avg_batch_size=%.1f}",
            batchOpen,
            currentBatchId,
            getPendingCommandCount(),
            maxBatchSize,
            totalBatches.get(),
            totalCommands.get(),
            drawCallCount.get(),
            totalBatches.get() > 0 ? (double) totalCommands.get() / totalBatches.get() : 0.0
        );
    }
}
