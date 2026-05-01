// Renderium - 命令缓冲�?// 多线程安全的渲染命令录制缓冲，支持命令批处理和延迟提�?// 支持两种模式：传�?ArrayList 模式（兼容）和零分配 Ring Buffer 模式（高性能�?// 参�? Vulkan Command Buffer / DX12 Command Queue

package com.renderium.graphics.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * 渲染命令缓冲�? * <p>
 * 这是 "GL 状态机 �?现代命令�? 架构的核心容器，用于�? * <ul>
 *   <li><b>命令录制</b>: 收集上层产生�?RenderCommand 序列</li>
 *   <li><b>多线程支�?/b>: 允许逻辑线程并行录制，渲染线程串行消�?/li>
 *   <li><b>批量提交</b>: 将多个小 DrawCall 合并为一次后端调�?/li>
 *   <li><b>生命周期管理</b>: 支持重置和复用，减少 GC 压力</li>
 * </ul>
 *
 * <h3>使用模式�?/h3>
 * <pre>{@code
 * // 1. 逻辑线程：录制命�? * CommandBuffer cmdBuf = commandPool.acquire();
 * cmdBuf.beginRecording();
 * cmdBuf.record(new DrawCommand(pipeline, GL_TRIANGLES, 0, vertexCount));
 * cmdBuf.record(new DrawCommand(pipeline2, GL_LINES, 0, lineCount));
 * cmdBuf.endRecording();
 *
 * // 2. 提交到渲染队�? * renderQueue.submit(cmdBuf);
 *
 * // 3. 渲染线程：消费并执行
 * for (RenderCommand cmd : cmdBuf.getCommands()) {
 *     backend.execute(cmd);
 * }
 *
 * // 4. 回收复用
 * commandPool.release(cmdBuf);
 * }</pre>
 *
 * <h3>Try-with-resources 模式�?/h3>
 * <pre>{@code
 * try (CommandBuffer cmdBuf = new CommandBuffer()) {
 *     cmdBuf.beginRecording();
 *     cmdBuf.record(command);
 *     cmdBuf.endRecording();
 *     renderQueue.submit(cmdBuf);
 * }
 * }</pre>
 *
 * @see RenderCommand
 * @see DrawCommand
 */
public final class CommandBuffer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(CommandBuffer.class.getName());

    // ==================== 状态枚�?====================

    /**
     * 命令缓冲区状�?     */
    public enum State {
        /** 初始状态，可以开始录�?*/
        INITIAL,
        /** 正在录制�?*/
        RECORDING,
        /** 录制完成，等待提�?执行 */
        EXECUTABLE,
        /** 已提交，正在被渲染线程消�?*/
        PENDING,
        /** 已完成执行，可回�?*/
        COMPLETED
    }

    // ==================== 实例字段 ====================

    /** 命令缓冲区唯一 ID (调试�? */
    private final int bufferId;

    /** 当前状�?*/
    private volatile State state;

    /** 已录制的命令列表 */
    private volatile List<RenderCommand> commands;

    /** 读写锁：录制用写锁，消费用读�?*/
    private final ReentrantReadWriteLock lock;

    /** 创建时间�?*/
    private final long createTimeNanos;

    /** 录制开始时间戳 */
    private long recordingStartNanos;

    /** 命令数量统计 */
    private int drawCommandCount;
    private int computeCommandCount;
    private int otherCommandCount;

    // ==================== 零分配模式字�?====================

    /**
     * 零分配环形缓冲区（可选）�?     * <p>当非 null 时，所�?record* 方法优先写入堆外内存�?     * 完全避免 Java 对象分配。getCommands() 会从 RingBuffer 反序列化�?     */
    private final CommandRingBuffer ringBuffer;

    /** 是否启用零分配模�?*/
    private final boolean zeroAllocMode;

    // ==================== 静态计数器 ====================
    private static volatile int nextBufferId = 0;

    // ==================== 构造函�?====================

    /** 创建新的命令缓冲�?(初始状态，使用传统 ArrayList 模式) */
    public CommandBuffer() {
        this(false, CommandRingBuffer.DEFAULT_CAPACITY);
    }

    /**
     * 创建零分配模式的命令缓冲�?     *
     * @param ringCapacity 环形缓冲区容量（命令数量�?     */
    public CommandBuffer(int ringCapacity) {
        this(true, ringCapacity);
    }

    /**
     * 内部构造函�?     *
     * @param useZeroAlloc 是否启用零分配模�?     * @param ringCapacity 零分配模式下环形缓冲区容�?     */
    private CommandBuffer(boolean useZeroAlloc, int ringCapacity) {
        this.bufferId = ++nextBufferId;
        this.state = State.INITIAL;
        this.commands = new ArrayList<>(256); // 兼容模式仍保�?        this.lock = new ReentrantReadWriteLock();
        this.createTimeNanos = System.nanoTime();
        this.drawCommandCount = 0;
        this.computeCommandCount = 0;
        this.otherCommandCount = 0;
        this.zeroAllocMode = useZeroAlloc;

        if (useZeroAlloc) {
            this.ringBuffer = new CommandRingBuffer(ringCapacity);
            LOGGER.fine(String.format("CommandBuffer#%d: zero-alloc mode enabled (capacity=%d)", bufferId, ringCapacity));
        } else {
            this.ringBuffer = null;
        }
    }

    // ==================== 录制生命周期方法 ====================

    /**
     * 开始录制命�?     *
     * @throws IllegalStateException 如果当前不在 INITIAL 状�?     */
    public void beginRecording() {
        lock.writeLock().lock();
        try {
            if (state != State.INITIAL) {
                throw new IllegalStateException(
                    "Cannot begin recording in state: " + state +
                    " (bufferId=" + bufferId + ")"
                );
            }
            state = State.RECORDING;
            recordingStartNanos = System.nanoTime();

            // 同步开始环形缓冲区录制
            if (zeroAllocMode && ringBuffer != null) {
                ringBuffer.beginRecording();
            }

            if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                LOGGER.fine(String.format("CommandBuffer#%d: beginRecording() [mode=%s]",
                    bufferId, zeroAllocMode ? "ZERO-ALLOC" : "COMPAT"));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 零分配录制方�?====================

    /**
     * 检查是否启用了零分配模�?     *
     * @return true 如果使用 RingBuffer 存储
     */
    public boolean isZeroAllocMode() {
        return zeroAllocMode;
    }

    /**
     * 获取底层环形缓冲区（仅零分配模式下可用）
     *
     * @return CommandRingBuffer 实例，或 null（兼容模式）
     */
    public CommandRingBuffer getRingBuffer() {
        return ringBuffer;
    }

    /**
     * 零分配录�?Draw 命令 (DrawArrays)
     *
     * <p>直接写入堆外内存，不创建 DrawCommand 对象�?     * 仅在零分配模式和 RECORDING 状态下可用�?     *
     * @param pipelineHash RenderPipeline �?hashCode
     * @param drawMode     绘制模式
     * @param firstVertex  起始顶点索引
     * @param vertexCount  顶点数量
     */
    public void recordDrawZ(int pipelineHash, int drawMode, int firstVertex, int vertexCount) {
        if (!zeroAllocMode || ringBuffer == null) {
            throw new IllegalStateException("Zero-alloc mode not enabled");
        }

        lock.writeLock().lock();
        try {
            if (state != State.RECORDING) {
                throw new IllegalStateException(
                    "Cannot record in state: " + state + " (bufferId=" + bufferId + ")"
                );
            }

            ringBuffer.recordDraw(pipelineHash, drawMode, firstVertex, vertexCount);
            drawCommandCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 零分配录�?Draw 命令 (DrawElements)
     *
     * @param pipelineHash      RenderPipeline �?hashCode
     * @param drawMode          绘制模式
     * @param indexCount        索引数量
     * @param indexType         索引类型 (1=BYTE, 2=SHORT, 3=INT)
     * @param indexBufferOffset 索引缓冲区字节偏�?     */
    public void recordDrawIndexedZ(int pipelineHash, int drawMode,
                                      int indexCount, int indexType,
                                      long indexBufferOffset) {
        if (!zeroAllocMode || ringBuffer == null) {
            throw new IllegalStateException("Zero-alloc mode not enabled");
        }

        lock.writeLock().lock();
        try {
            if (state != State.RECORDING) {
                throw new IllegalStateException(
                    "Cannot record in state: " + state + " (bufferId=" + bufferId + ")"
                );
            }

            ringBuffer.recordDrawIndexed(pipelineHash, drawMode, indexCount, indexType, indexBufferOffset);
            drawCommandCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 零分配录�?Compute 命令
     *
     * @param pipelineHandle 计算着色器 Pipeline handle
     * @param groupCountX X 维度工作组数�?     * @param groupCountY Y 维度工作组数�?     * @param groupCountZ Z 维度工作组数�?     * @param descriptorSet 描述符集 handle
     */
    public void recordComputeZ(long pipelineHandle, int groupCountX,
                                  int groupCountY, int groupCountZ,
                                  long descriptorSet) {
        if (!zeroAllocMode || ringBuffer == null) {
            throw new IllegalStateException("Zero-alloc mode not enabled");
        }

        lock.writeLock().lock();
        try {
            if (state != State.RECORDING) {
                throw new IllegalStateException(
                    "Cannot record in state: " + state + " (bufferId=" + bufferId + ")"
                );
            }

            ringBuffer.recordCompute(pipelineHandle, groupCountX, groupCountY, groupCountZ, descriptorSet);
            computeCommandCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 零分配录�?Clear 命令
     *
     * @param clearMask 清除掩码
     * @param r         清除颜色 R
     * @param g         清除颜色 G
     * @param b         清除颜色 B
     * @param a         清除颜色 A
     * @param depth     清除深度�?     * @param stencil   清除模板�?     */
    public void recordClearZ(int clearMask, float r, float g, float b, float a,
                                float depth, int stencil) {
        if (!zeroAllocMode || ringBuffer == null) {
            throw new IllegalStateException("Zero-alloc mode not enabled");
        }

        lock.writeLock().lock();
        try {
            if (state != State.RECORDING) {
                throw new IllegalStateException(
                    "Cannot record in state: " + state + " (bufferId=" + bufferId + ")"
                );
            }

            ringBuffer.recordClear(clearMask, r, g, b, a, depth, stencil);
            otherCommandCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 结束录制
     *
     * @return 录制的命令数�?     * @throws IllegalStateException 如果当前不在 RECORDING 状�?     */
    public int endRecording() {
        lock.writeLock().lock();
        try {
            if (state != State.RECORDING) {
                throw new IllegalStateException(
                    "Cannot end recording in state: " + state +
                    " (bufferId=" + bufferId + ")"
                );
            }
            state = State.EXECUTABLE;

            // 同步结束环形缓冲区录�?            if (zeroAllocMode && ringBuffer != null) {
                ringBuffer.endRecording();
            }

            long durationNanos = System.nanoTime() - recordingStartNanos;
            int totalCmds = zeroAllocMode ? ringBuffer.getTotalRecordedCount() : commands.size();
            LOGGER.fine(String.format(
                "CommandBuffer#%d: endRecording() - %d commands (%d draws, %d computes) in %.2fms [mode=%s]",
                bufferId, totalCmds, drawCommandCount, computeCommandCount,
                durationNanos / 1_000_000.0, zeroAllocMode ? "ZERO-ALLOC" : "COMPAT"
            ));

            return totalCmds;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 标记为已提交 (正在被消�?
     */
    public void markSubmitted() {
        lock.writeLock().lock();
        try {
            if (state != State.EXECUTABLE) {
                throw new IllegalStateException("Cannot submit non-executable buffer");
            }
            state = State.PENDING;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 标记为已完成 (可回�?
     */
    public void markCompleted() {
        lock.writeLock().lock();
        try {
            if (state != State.PENDING) {
                throw new IllegalStateException("Cannot complete non-pending buffer");
            }
            state = State.COMPLETED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 重置命令缓冲区以供复�?     *
     * @throws IllegalStateException 如果当前不在 COMPLETED �?INITIAL 状�?     */
    public void reset() {
        lock.writeLock().lock();
        try {
            if (state != State.COMPLETED && state != State.INITIAL) {
                throw new IllegalStateException(
                    "Cannot reset buffer in state: " + state +
                    " (must be COMPLETED or INITIAL)"
                );
            }
            commands.clear();
            state = State.INITIAL;
            drawCommandCount = 0;
            computeCommandCount = 0;
            otherCommandCount = 0;

            // 重置环形缓冲�?            if (ringBuffer != null) {
                ringBuffer.reset();
            }

            LOGGER.fine(String.format("CommandBuffer#%d: reset()", bufferId));
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 命令录制方法 ====================

    /**
     * 录制一条渲染命�?     *
     * @param command 要录制的命令
     * @throws NullPointerException 如果 command �?null
     * @throws IllegalStateException 如果当前不在 RECORDING 状�?     */
    public void record(RenderCommand command) {
        if (command == null) {
            throw new NullPointerException("command cannot be null");
        }

        lock.writeLock().lock();
        try {
            if (state != State.RECORDING) {
                throw new IllegalStateException(
                    "Cannot record in state: " + state +
                    " (bufferId=" + bufferId + ")"
                );
            }

            commands.add(command);

            // 统计命令类型
            switch (command.getType()) {
                case DRAW -> drawCommandCount++;
                case COMPUTE -> computeCommandCount++;
                default -> otherCommandCount++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量录制多条命令
     *
     * @param commandsToRecord 命令列表
     */
    public void recordAll(List<? extends RenderCommand> commandsToRecord) {
        lock.writeLock().lock();
        try {
            if (state != State.RECORDING) {
                throw new IllegalStateException("Cannot record in non-RECORDING state");
            }

            for (RenderCommand cmd : commandsToRecord) {
                if (cmd == null) continue; // 跳过 null
                commands.add(cmd);

                switch (cmd.getType()) {
                    case DRAW -> drawCommandCount++;
                    case COMPUTE -> computeCommandCount++;
                    default -> otherCommandCount++;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取当前状�?     *
     * @return 命令缓冲区状�?     */
    public State getState() {
        return state;
    }

    /**
     * 获取缓冲�?ID
     *
     * @return 唯一标识�?     */
    public int getBufferId() {
        return bufferId;
    }

    /**
     * 获取已录制的命令数量
     *
     * @return 命令总数
     */
    public int getCommandCount() {
        return zeroAllocMode && ringBuffer != null
            ? ringBuffer.getTotalRecordedCount()
            : commands.size();
    }

    /**
     * 获取绘制命令数量
     */
    public int getDrawCommandCount() {
        return drawCommandCount;
    }

    /**
     * 获取计算命令数量
     */
    public int getComputeCommandCount() {
        return computeCommandCount;
    }

    /**
     * 检查是否为�?     */
    public boolean isEmpty() {
        return commands.isEmpty();
    }

    /**
     * 获取不可修改的命令列表视�?     * <p>
     * 用于渲染线程安全地遍历所有命令�?     * 注意：返回的列表是快照，后续录制不会影响该视图�?     * 使用读锁允许多个消费者并发读取�?     *
     * @return 命令列表的不可变视图
     */
    public List<RenderCommand> getCommands() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(commands));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        return String.format(
            "CommandBuffer#%d{state=%s, commands=%d (draws=%d, computes=%d, other=%d)}",
            bufferId, state, commands.size(),
            drawCommandCount, computeCommandCount, otherCommandCount
        );
    }

    // ==================== AutoCloseable 实现 ====================

    /**
     * 关闭命令缓冲区，释放资源
     * <p>
     * 用于 try-with-resources 模式，确保资源正确释放�?     * 如果缓冲区正在录制中，会自动结束录制�?     */
    @Override
    public void close() {
        if (state == State.RECORDING) {
            endRecording();
        }
        if (state == State.EXECUTABLE) {
            markSubmitted();
        }
        if (state == State.PENDING) {
            markCompleted();
        }
        reset();

        // 释放环形缓冲区堆外内�?        if (ringBuffer != null) {
            ringBuffer.close();
        }
    }
}
