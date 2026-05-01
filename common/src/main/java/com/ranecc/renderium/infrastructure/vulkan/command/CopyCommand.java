// Renderium - Copy Command
// 资源拷贝命令，用于纹理/缓冲区数据传输

package com.ranecc.renderium.infrastructure.vulkan.command;

import com.ranecc.renderium.None;

/**
 * Copy 拷贝命令。
 *
 * <p>对应 Vulkan 的 vkCmdCopyBuffer / vkCmdCopyImage / vkCmdCopyBufferToImage /
 * vkCmdCopyImageToBuffer 调用。
 *
 * <p>使用场景：
 * <ul>
 *   <li>Staging Buffer → Vertex/Index Buffer 上传</li>
 *   <li>纹理数据上传</li>
 *   <li>Mipmap 生成</li>
 *   <li>GPU 端数据搬移</li>
 * </ul>
 *
 * @see RenderCommand
 * @author Renderium Team
 * @since 1.0.0
 */
public final class CopyCommand implements RenderCommand {

    /** 拷贝类型 */
    public enum CopyType {
        /** Buffer → Buffer */
        BUFFER_TO_BUFFER,
        /** Buffer → Image */
        BUFFER_TO_IMAGE,
        /** Image → Buffer */
        IMAGE_TO_BUFFER,
        /** Image → Image */
        IMAGE_TO_IMAGE
    }

    /** 拷贝类型 */
    private final CopyType copyType;

    /** 源资源 handle */
    private final long srcHandle;

    /** 目标资源 handle */
    private final long dstHandle;

    /** 源偏移量（字节） */
    private final long srcOffset;

    /** 目标偏移量（字节） */
    private final long dstOffset;

    /** 拷贝大小（字节） */
    private final long size;

    /**
     * 创建 Copy 命令
     *
     * @param copyType 拷贝类型
     * @param srcHandle 源资源 handle
     * @param dstHandle 目标资源 handle
     * @param srcOffset 源偏移量
     * @param dstOffset 目标偏移量
     * @param size 拷贝大小
     */
    public CopyCommand(CopyType copyType, long srcHandle, long dstHandle,
                       long srcOffset, long dstOffset, long size) {
        if (copyType == null) {
            throw new IllegalArgumentException("copyType must not be null");
        }
        if (srcHandle == 0L || dstHandle == 0L) {
            throw new IllegalArgumentException("src and dst handles must not be zero");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        this.copyType = copyType;
        this.srcHandle = srcHandle;
        this.dstHandle = dstHandle;
        this.srcOffset = srcOffset;
        this.dstOffset = dstOffset;
        this.size = size;
    }

    @Override
    public CommandType getType() {
        return CommandType.COPY;
    }

    @Override
    public RenderPipeline getPipeline() {
        return null; // Copy 命令不需要管线状态
    }

    public long estimateGpuTimeNs() {
        // Copy 命令的 GPU 时间与数据量成正比
        return size / 256L + 100L;
    }

    // ==================== Getter ====================

    public CopyType getCopyType() { return copyType; }
    public long getSrcHandle() { return srcHandle; }
    public long getDstHandle() { return dstHandle; }
    public long getSrcOffset() { return srcOffset; }
    public long getDstOffset() { return dstOffset; }
    public long getSize() { return size; }

    @Override
    public String toString() {
        return String.format("CopyCommand{type=%s, src=0x%X, dst=0x%X, size=%d}",
                copyType, srcHandle, dstHandle, size);
    }
}
